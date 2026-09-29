import datetime as dt
import json
from pathlib import Path

import pytest

CONFIGS = Path(__file__).resolve().parents[1] / "configs"


def test_comparison_days_follow_the_iso_rule_and_avoid_holidays():
    from hagrid_demand.baseline.calendar import public_holidays
    from hagrid_demand.baseline.comparison_days import comparison_days

    plain = [dt.date.fromisocalendar(2025, 19, 5), dt.date.fromisocalendar(2025, 19, 6)]
    plain += [dt.date.fromisocalendar(2025, 20, weekday) for weekday in range(1, 7)]
    assert comparison_days(2025) == [day.isoformat() for day in plain]
    # Ascension Day 2026-05-14 (Thursday of ISO week 20) moves to the Thursday one week later.
    days_2026 = comparison_days(2026)
    assert "2026-05-14" not in days_2026 and "2026-05-21" in days_2026
    for year in range(2025, 2036):
        days = comparison_days(year)
        assert len(days) == 8 and len(set(days)) == 8
        assert not set(days) & set(public_holidays(year, "NI"))
        assert [dt.date.fromisoformat(day).weekday() for day in days] == [4, 5, 0, 1, 2, 3, 4, 5]


@pytest.mark.parametrize("name, export_years, scenario", [
    ("trend", list(range(2025, 2036)), None),
    ("saettigung", [2030, 2035], ("legacy_assumptions", "logistic")),
    ("boom", [2030, 2035], ("legacy_assumptions", "exponential")),
])
def test_decade_configs_load_and_export_the_comparison_days(name, export_years, scenario):
    from hagrid_demand.baseline.comparison_days import comparison_days
    from hagrid_demand.baseline.config import load_baseline_config

    loaded = load_baseline_config(CONFIGS / f"decade-{name}.json")
    assert loaded["years"] == list(range(2025, 2036))
    assert loaded["annual_store"] is True
    assert loaded["dates"] == [day for year in export_years for day in comparison_days(year)]
    if scenario is None:
        assert "volume_scenario" not in loaded
    else:
        assert loaded["volume_scenario"] == {"name": name, "policy": scenario[0], "curve": scenario[1], "chain_year": 2025}


def test_baseline_daily_config_writes_the_annual_store():
    """The default year run must feed the annual dashboard (README section 5)."""
    assert json.loads((CONFIGS / "baseline-daily.json").read_text(encoding="utf-8"))["annual_store"] is True
