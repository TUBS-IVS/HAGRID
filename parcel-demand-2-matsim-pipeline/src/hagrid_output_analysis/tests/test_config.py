"""Tests for hagrid_output_analysis.config."""

import pytest

from hagrid_output_analysis.config import (
    AREA_TYPE_LABELS,
    DAYEND,
    EMISSION_CO2_gpkm,
    LOW_UTIL_THRESHOLD,
    VEHICLE_CAPACITY_BY_SIZE,
    VEHICLE_SIZE_MAPPING,
    AreaType,
    RunConfig,
    get_ambient_temp,
    get_season_mult,
)


class TestAreaType:
    def test_enum_values(self):
        assert AreaType.METROPOLITAN_CENTER == 1
        assert AreaType.RURAL_NO_INDUSTRIAL == 8

    def test_all_types_have_labels(self):
        for at in AreaType:
            assert at.value in AREA_TYPE_LABELS

    def test_int_behaviour(self):
        """AreaType is IntEnum — arithmetic works."""
        assert AreaType.METROPOLITAN_CENTER + 1 == 2


class TestRunConfig:
    def test_defaults(self):
        rc = RunConfig()
        assert rc.emissions_basis == "CO2"
        assert rc.without_supply_trucks is False
        assert rc.use_wtw is False
        assert rc.idle_pollutants_on is True
        assert rc.low_util_threshold == LOW_UTIL_THRESHOLD

    def test_immutable(self):
        rc = RunConfig()
        with pytest.raises(AttributeError):
            rc.emissions_basis = "CO2e"  # type: ignore[misc]

    def test_custom_values(self):
        rc = RunConfig(
            without_supply_trucks=True,
            t_long_override=900,
            emissions_basis="CO2e",
        )
        assert rc.without_supply_trucks is True
        assert rc.t_long_override == 900
        assert rc.emissions_basis == "CO2e"


class TestTemperatureSeason:
    def test_ambient_temp_returns_float(self):
        temp = get_ambient_temp()
        assert isinstance(temp, float)
        assert -10 < temp < 40

    def test_season_mult_returns_float(self):
        mult = get_season_mult()
        assert isinstance(mult, float)
        assert 0.9 < mult < 1.2


class TestConstants:
    def test_dayend(self):
        assert DAYEND == 86400

    def test_emission_co2_has_required_classes(self):
        for vc in ("van [< 2t]", "van [> 2t]", "truck [< 10t]"):
            assert vc in EMISSION_CO2_gpkm
            for road in ("urban", "rural", "highway"):
                assert road in EMISSION_CO2_gpkm[vc]
                lo, hi = EMISSION_CO2_gpkm[vc][road]
                assert 0 < lo <= hi

    def test_vehicle_size_mapping_has_required_keys(self):
        for key in ("m", "l", "truck", "truck_light", "supply_light_van"):
            assert key in VEHICLE_SIZE_MAPPING

    def test_vehicle_capacity_by_size(self):
        assert VEHICLE_CAPACITY_BY_SIZE["l"] == 230
        assert VEHICLE_CAPACITY_BY_SIZE["m"] == 165
        assert VEHICLE_CAPACITY_BY_SIZE["truck"] == 2000
        assert VEHICLE_CAPACITY_BY_SIZE["truck_light"] == 1000
        assert VEHICLE_CAPACITY_BY_SIZE["supply_light_van"] == 230
