"""Tests for hagrid_output_analysis.utils."""

import pytest

from hagrid_output_analysis.utils import (
    area_type_group,
    area_type_name,
    classify_vehicle,
    extract_main_area_type,
    extract_provider,
    extract_veh_size,
    format_runtime,
    is_in_hannover,
    is_supply_vehicle,
    veh_size_to_class,
)


class TestExtractProvider:
    @pytest.mark.parametrize(
        "identifier, expected",
        [
            ("freight_dhl_30159_carrier1", "dhl"),
            ("freight_amazon_12345_x", "amazon"),
            ("freight_ups_carrier_1", "ups"),
            ("freight_gls_carrier_1", "gls"),
            ("freight_dpd_carrier_1", "dpd"),
            ("freight_fedex_carrier_1", "fedex"),
            ("freight_hermes_carrier_1", "hermes"),
        ],
    )
    def test_known_providers(self, identifier: str, expected: str):
        assert extract_provider(identifier) == expected

    def test_white_label(self):
        assert extract_provider("freight_wl_30159_test") == "white-label"

    def test_fallback_split(self):
        assert extract_provider("freight_newcarrier_123") == "newcarrier"

    def test_unknown(self):
        assert extract_provider("nounderscore") == "unknown"

    def test_case_insensitive(self):
        assert extract_provider("freight_DHL_upper") == "dhl"


class TestClassifyVehicle:
    @pytest.mark.parametrize(
        "vid, expected",
        [
            ("freight_dhl_30159_veh_cep_size_m_CEP_Vehicle_1", "van"),
            ("freight_dhl_30159_veh_cep_size_l_1", "van"),
            ("freight_dhl_30159_veh_supply_light_van_Supply_Vehicle_1", "truck"),
            ("freight_dhl_30159_veh_cargoBike_1", "bike"),
            ("freight_dhl_30159_veh_cargobike_1", "bike"),
            ("freight_dhl_30159_veh_egrocery_van_1", "van"),
            ("unknown_thing", "unknown"),
        ],
    )
    def test_classification(self, vid: str, expected: str):
        assert classify_vehicle(vid) == expected


class TestExtractVehSize:
    def test_size_m(self):
        assert extract_veh_size("freight_dhl_30159_veh_cep_size_m_1") == "m"

    def test_size_l(self):
        assert extract_veh_size("freight_dhl_30159_veh_cep_size_l_1") == "l"

    def test_no_size_prefix(self):
        assert extract_veh_size("freight_dhl_supply_light_van_1") == "unknown"


class TestExtractMainAreaType:
    def test_normal(self):
        assert extract_main_area_type({1: 5, 3: 10, 5: 2}) == 3

    def test_empty(self):
        assert extract_main_area_type({}) == 0

    def test_single(self):
        assert extract_main_area_type({7: 1}) == 7


class TestVehSizeToClass:
    def test_known(self):
        assert veh_size_to_class("m") == "van [< 2t]"
        assert veh_size_to_class("l") == "van [> 2t]"

    def test_unknown(self):
        assert veh_size_to_class("xyz") is None


class TestIsInHannover:
    def test_hannover_plz(self):
        assert is_in_hannover("freight_dhl_30159_veh_1") is True

    def test_non_hannover(self):
        assert is_in_hannover("freight_dhl_99999_veh_1") is False


class TestIsSupplyVehicle:
    def test_supply(self):
        assert is_supply_vehicle("freight_dhl_veh_supply_light_van_1") is True

    def test_cep(self):
        assert is_supply_vehicle("freight_dhl_veh_cep_size_m_1") is False


class TestAreaTypeHelpers:
    def test_area_type_name_valid(self):
        assert area_type_name(1) == "Metropolitan Center"
        assert area_type_name(8) == "Rural without Industrial Influence"

    def test_area_type_name_invalid(self):
        assert area_type_name(99) == "unknown"
        assert area_type_name("garbage") == "unknown"

    def test_area_type_group_valid(self):
        assert area_type_group(1) == "Urban"
        assert area_type_group(4) == "Suburban"
        assert area_type_group(7) == "Rural"

    def test_area_type_group_invalid(self):
        assert area_type_group(99) == "unknown"


class TestFormatRuntime:
    def test_milliseconds(self):
        result = format_runtime(0.5)
        assert "ms" in result

    def test_seconds(self):
        result = format_runtime(42.5)
        assert "s" in result
        assert "min" not in result

    def test_minutes(self):
        result = format_runtime(125.3)
        assert "min" in result
