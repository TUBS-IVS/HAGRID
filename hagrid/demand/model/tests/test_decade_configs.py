import datetime as dt
import json
from pathlib import Path

import pytest

CONFIGS = Path(__file__).resolve().parents[1] / "configs"


def _comparable(year: int) -> list[str]:
    """Fri/Sat of ISO week 19 and Mon-Sat of ISO week 20: the eight ParcelDemandScenarioGenerator days."""
    days = [dt.date.fromisocalendar(year, 19, 5), dt.date.fromisocalendar(year, 19, 6)]
    days += [dt.date.fromisocalendar(year, 20, weekday) for weekday in range(1, 7)]
    return [day.isoformat() for day in days]


@pytest.mark.parametrize("name, export_years, scenario", [
    ("trend", list(range(2025, 2036)), None),
    ("saettigung", [2030, 2035], ("legacy_assumptions", "logistic")),
    ("boom", [2030, 2035], ("legacy_assumptions", "exponential")),
])
def test_decade_configs_load_and_dates_follow_iso_rule(name, export_years, scenario):
    from hagrid_demand.baseline.config import load_baseline_config

    loaded = load_baseline_config(CONFIGS / f"decade-{name}.json")
    assert loaded["years"] == list(range(2025, 2036))
    assert loaded["annual_store"] is True
    assert loaded["dates"] == [day for year in export_years for day in _comparable(year)]
    if scenario is None:
        assert "volume_scenario" not in loaded
    else:
        assert loaded["volume_scenario"] == {"name": name, "policy": scenario[0], "curve": scenario[1], "chain_year": 2025}


def test_baseline_daily_config_writes_the_annual_store():
    """The default year run must feed the annual dashboard (README section 5)."""
    assert json.loads((CONFIGS / "baseline-daily.json").read_text(encoding="utf-8"))["annual_store"] is True
