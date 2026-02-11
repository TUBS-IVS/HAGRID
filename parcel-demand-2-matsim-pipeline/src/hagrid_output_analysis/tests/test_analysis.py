"""Tests for hagrid_output_analysis.analysis."""

from __future__ import annotations

import json

import numpy as np
import pandas as pd
import pytest

from hagrid_output_analysis.analysis import (
    _count_merged_missed,
    _match_cost_params,
    _parse_weights,
    _validate_vehicle_ids,
    _vehicle_capacity_from_id,
    add_vehicle_demand_to_result,
    calculate_costs,
    process_vehicle_data,
    vehicle_stats,
)
from hagrid_output_analysis.config import COST_PARAMS_BY_SIZE
from hagrid_output_analysis.models import Carrier


# ====================================================================
# _parse_weights
# ====================================================================

class TestParseWeights:
    def test_list_input(self):
        assert _parse_weights([1, 2, 3]) == [1, 2, 3]

    def test_tuple_input(self):
        assert _parse_weights((1.0, 2.0)) == [1.0, 2.0]

    def test_json_string_list(self):
        assert _parse_weights("[1, 2, 3]") == [1, 2, 3]

    def test_json_string_scalar(self):
        assert _parse_weights("42") == [42]

    def test_plain_string(self):
        assert _parse_weights("not_json_at_all") == ["not_json_at_all"]

    def test_empty(self):
        assert _parse_weights([]) == []
        assert _parse_weights(None) == []


# ====================================================================
# _count_merged_missed
# ====================================================================

class TestCountMergedMissed:
    def test_basic_missed(self):
        merged = json.dumps({
            "sub_A": {"capacity": 3, "weights": [1, 2, 3]},
            "sub_B": {"capacity": 2, "weights": [1, 2]},
        })
        # Only sub_A is missed
        assert _count_merged_missed(merged, {"sub_A"}) == 3

    def test_all_missed(self):
        merged = json.dumps({
            "sub_A": {"capacity": 3, "weights": [1, 2, 3]},
            "sub_B": {"capacity": 2, "weights": [1, 2]},
        })
        assert _count_merged_missed(merged, {"sub_A", "sub_B"}) == 5

    def test_none_missed(self):
        merged = json.dumps({
            "sub_A": {"capacity": 3, "weights": [1, 2, 3]},
        })
        assert _count_merged_missed(merged, set()) == 0

    def test_bad_json(self):
        assert _count_merged_missed("not valid json!!!", {"x"}) == 0

    def test_no_capacity_uses_weights_length(self):
        merged = json.dumps({
            "sub_A": {"capacity": 0, "weights": [1, 2]},
        })
        assert _count_merged_missed(merged, {"sub_A"}) == 2

    def test_no_capacity_no_weights_defaults_to_1(self):
        merged = json.dumps({
            "sub_A": {},
        })
        assert _count_merged_missed(merged, {"sub_A"}) == 1

    def test_weights_as_json_string(self):
        merged = json.dumps({
            "sub_A": {"capacity": 2, "weights": "[1.0, 2.0]"},
        })
        assert _count_merged_missed(merged, {"sub_A"}) == 2


# ====================================================================
# _vehicle_capacity_from_id
# ====================================================================

class TestVehicleCapacity:
    def test_size_m(self):
        assert _vehicle_capacity_from_id("freight_dhl_veh_cep_size_m_1") == 165

    def test_size_l(self):
        assert _vehicle_capacity_from_id("freight_dhl_veh_cep_size_l_1") == 230

    def test_fallback_supply(self):
        # "supply_light_van" is in COST_PARAMS_BY_SIZE
        assert _vehicle_capacity_from_id("freight_dhl_veh_supply_light_van_1") == 230

    def test_unknown_defaults_230(self):
        assert _vehicle_capacity_from_id("completely_unknown_vid") == 230


# ====================================================================
# _match_cost_params
# ====================================================================

class TestMatchCostParams:
    def test_size_l(self):
        cap, fix, km = _match_cost_params("freight_dhl_veh_size_l_1")
        assert cap == COST_PARAMS_BY_SIZE["size_l"][0]
        assert fix == COST_PARAMS_BY_SIZE["size_l"][1]

    def test_size_m(self):
        cap, _, _ = _match_cost_params("freight_dhl_veh_size_m_1")
        assert cap == 165

    def test_supply(self):
        cap, _, _ = _match_cost_params("freight_dhl_veh_supply_light_van_1")
        assert cap == 230

    def test_default_fallback(self):
        cap, _, _ = _match_cost_params("some_unknown_vehicle")
        assert cap == COST_PARAMS_BY_SIZE["default"][0]


# ====================================================================
# calculate_costs
# ====================================================================

class TestCalculateCosts:
    @pytest.fixture
    def sample_df(self) -> pd.DataFrame:
        return pd.DataFrame({
            "vehicle_id": [
                "freight_dhl_veh_size_m_1",
                "freight_dhl_veh_size_l_2",
            ],
            "tour_km": [50.0, 100.0],
            "Tour Duration": [8 * 3600, 6 * 3600],
        })

    def test_adds_cost_columns(self, sample_df: pd.DataFrame):
        result = calculate_costs(sample_df)
        for col in ("vehicle_fix_cost", "vehicle_km_cost",
                     "vehicle_time_cost", "overtime_cost", "vehicle_cost"):
            assert col in result.columns

    def test_does_not_mutate_input(self, sample_df: pd.DataFrame):
        original_cols = set(sample_df.columns)
        calculate_costs(sample_df)
        assert set(sample_df.columns) == original_cols

    def test_overtime_logic(self, sample_df: pd.DataFrame):
        result = calculate_costs(sample_df)
        # 8h tour → 0.5h overtime; 6h tour → no overtime
        assert result.loc[0, "overtime_cost"] > 0
        assert result.loc[1, "overtime_cost"] == 0.0

    def test_vehicle_cost_is_sum(self, sample_df: pd.DataFrame):
        result = calculate_costs(sample_df)
        for i in range(len(result)):
            expected = (
                result.loc[i, "vehicle_fix_cost"]
                + result.loc[i, "vehicle_km_cost"]
                + result.loc[i, "overtime_cost"]
            )
            assert abs(result.loc[i, "vehicle_cost"] - expected) < 0.01


# ====================================================================
# vehicle_stats
# ====================================================================

class TestVehicleStats:
    def test_basic(self):
        vehicle_tour = {
            "veh_A": [("link_1", 100.0), ("link_2", 200.0)],
        }
        service_events = {
            "veh_A": ["link_2"],
        }
        link_length = {"link_1": 1000.0, "link_2": 2000.0}
        link_raumtyp = {"link_2": 3}

        df = vehicle_stats(vehicle_tour, service_events,
                           link_length, link_raumtyp)

        assert len(df) == 1
        assert df.iloc[0]["vehicle_id"] == "veh_A"
        assert df.iloc[0]["service_num"] == 1
        assert df.iloc[0]["tour_km"] == pytest.approx(3.0)
        assert 3 in df.iloc[0]["raumsplit"]

    def test_empty_services_skipped(self):
        df = vehicle_stats(
            {"veh_A": [("l1", 10)]},
            {"veh_A": []},
            {"l1": 1000},
            {},
        )
        assert len(df) == 0


# ====================================================================
# process_vehicle_data
# ====================================================================

class TestProcessVehicleData:
    def test_adds_columns(self):
        df = pd.DataFrame({
            "vehicle_id": [
                "freight_dhl_30159_veh_cep_size_m_1",
                "freight_amazon_12345_veh_cep_size_l_2",
            ],
            "raumsplit": [{1: 5, 3: 2}, {7: 10}],
        })
        result = process_vehicle_data(df)
        assert "provider" in result.columns
        assert "veh_size" in result.columns
        assert "main_area_type" in result.columns
        assert result.iloc[0]["provider"] == "dhl"
        assert result.iloc[1]["provider"] == "amazon"
        assert result.iloc[0]["veh_size"] == "m"
        assert result.iloc[1]["main_area_type"] == 7


# ====================================================================
# add_vehicle_demand_to_result
# ====================================================================

class TestAddVehicleDemandToResult:
    def test_basic_enrichment(self, sample_carrier: Carrier):
        result = pd.DataFrame({
            "vehicle_id": [
                "freight_dhl_30159_carrier1_veh_cep_size_m_1",
            ],
            "service_num": [1],
        })
        enriched = add_vehicle_demand_to_result([sample_carrier], result)

        assert enriched.loc[0, "deliveries"] == 7
        assert enriched.loc[0, "missed deliveries"] == 0
        assert enriched.loc[0, "Hannover"] is True or enriched.loc[0, "Hannover"] == True  # noqa
        assert "services" in enriched.columns

    def test_b2b_b2c_ratios(self, sample_carrier: Carrier):
        result = pd.DataFrame({
            "vehicle_id": [
                "freight_dhl_30159_carrier1_veh_cep_size_m_1",
            ],
            "service_num": [1],
        })
        enriched = add_vehicle_demand_to_result([sample_carrier], result)

        # b2b=3, b2c=4, total=7
        assert enriched.loc[0, "b2b_ration"] == pytest.approx(3 / 7)
        assert enriched.loc[0, "b2c_ration"] == pytest.approx(4 / 7)
        assert enriched.loc[0, "ration_check"] == pytest.approx(1.0)

    def test_missed_from_merged_metadata(
        self, dhl_carrier_with_missed: Carrier,
    ):
        """Verify that missed parcels from merged sub-services are
        correctly accounted for (this was a bug: the return value
        of _account_merged_missed was previously ignored)."""
        result = pd.DataFrame({
            "vehicle_id": [
                "freight_dhl_30159_carrier2_veh_cep_size_l_2",
            ],
            "service_num": [1],
        })
        enriched = add_vehicle_demand_to_result(
            [dhl_carrier_with_missed], result,
        )

        # sub_svc_A has capacity=2 and is missed
        assert enriched.loc[0, "missed deliveries"] == 2

    def test_unmatched_vehicles_stay_nan(self, sample_carrier: Carrier):
        result = pd.DataFrame({
            "vehicle_id": ["freight_UNKNOWN_veh_99"],
            "service_num": [0],
        })
        enriched = add_vehicle_demand_to_result([sample_carrier], result)
        assert pd.isna(enriched.loc[0, "deliveries"])

    def test_empty_carriers(self):
        result = pd.DataFrame({
            "vehicle_id": ["freight_dhl_veh_1"],
            "service_num": [0],
        })
        enriched = add_vehicle_demand_to_result([], result)
        assert "Hannover" in enriched.columns


# ====================================================================
# _validate_vehicle_ids (logging, not printing)
# ====================================================================

class TestValidateVehicleIds:
    def test_no_crash_on_missing(self, caplog):
        df = pd.DataFrame({"vehicle_id": ["a", "b"]})
        with caplog.at_level("WARNING"):
            _validate_vehicle_ids(df, ["a", "b", "c"])
        assert "Missing vehicle" in caplog.text

    def test_no_crash_on_empty(self, caplog):
        df = pd.DataFrame({"vehicle_id": pd.Series(dtype=str)})
        _validate_vehicle_ids(df, [])
