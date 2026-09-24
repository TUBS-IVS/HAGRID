"""Tests for hagrid_output_analysis.emissions."""

import pytest

from hagrid_output_analysis.emissions import (
    TIME_INTERVALS,
    calc_drive_emissions,
    calc_wtt_from_energy_g_per_km,
    cold_start_emissions,
    engine_on_share,
    idle_emissions,
    ttw_gpkm,
)


class TestTtwGpkm:
    @pytest.mark.parametrize("road_type", ["urban", "rural", "highway"])
    def test_known_class(self, road_type: str):
        result = ttw_gpkm("van [< 2t]", road_type, 50)
        assert result is not None
        assert result > 0

    def test_unknown_class_returns_none(self):
        result = ttw_gpkm("spaceship", "urban", 50)
        assert result is None

    def test_higher_speed_may_differ(self):
        slow = ttw_gpkm("van [< 2t]", "urban", 20)
        fast = ttw_gpkm("van [< 2t]", "urban", 80)
        # Both should be positive
        assert slow > 0
        assert fast > 0


class TestCalcDriveEmissions:
    def test_positive_result(self):
        em = calc_drive_emissions(1000, "van [< 2t]", "urban", 50)
        assert em > 0

    def test_zero_distance(self):
        em = calc_drive_emissions(0, "van [< 2t]", "urban", 50)
        assert em == 0.0

    def test_truck_higher_than_van(self):
        van = calc_drive_emissions(1000, "van [< 2t]", "urban", 50)
        truck = calc_drive_emissions(1000, "truck [< 10t]", "urban", 50)
        assert truck > van


class TestIdleEmissions:
    def test_positive(self):
        em = idle_emissions(300, "van [< 2t]", 14.0)
        assert em > 0

    def test_zero_duration(self):
        em = idle_emissions(0, "van [< 2t]", 14.0)
        assert em == 0.0

    def test_longer_idle_more_emissions_within_short_range(self):
        """Within the 'engine always on' range, more idle → more emissions."""
        short = idle_emissions(30, "van [< 2t]", 14.0)
        medium = idle_emissions(60, "van [< 2t]", 14.0)
        assert medium >= short


class TestColdStartEmissions:
    def test_positive(self):
        em = cold_start_emissions("van [< 2t]", 14.0)
        assert em > 0

    def test_cold_weather_higher(self):
        warm = cold_start_emissions("van [< 2t]", 25.0)
        cold = cold_start_emissions("van [< 2t]", -5.0)
        assert cold > warm


class TestEngineOnShare:
    def test_between_0_and_1(self):
        share = engine_on_share(120, "van [< 2t]", 14.0)
        assert 0 < share <= 1.0

    def test_short_stop_positive_share(self):
        """Even for very short stops, engine_on_share is positive."""
        share = engine_on_share(10, "van [< 2t]", 14.0)
        assert share > 0


class TestWttFromEnergy:
    def test_known_class(self):
        wtt = calc_wtt_from_energy_g_per_km("van [< 2t]", "urban", 50)
        assert wtt is not None
        assert wtt > 0

    def test_unknown_class(self):
        wtt = calc_wtt_from_energy_g_per_km("spaceship", "urban", 50)
        assert wtt is None


class TestTimeIntervals:
    def test_count(self):
        assert len(TIME_INTERVALS) == 96

    def test_first_and_last(self):
        assert TIME_INTERVALS[0] == "00:00"
        assert TIME_INTERVALS[-1] == "23:45"
