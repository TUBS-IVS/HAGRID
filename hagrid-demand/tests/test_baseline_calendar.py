import pandas as pd
import pytest


def test_calendar_leap_year_and_holidays():
    from hagrid_demand.baseline.calendar import calendar_weights

    cfg = {"weekday_weights": {"private": [1] * 7}, "monthly_weights": [1] * 12,
           "holiday_dates": ["2024-01-01"], "holiday_factor": 0}
    calendar = calendar_weights(2024, "private", None, cfg)

    assert len(calendar) == 366
    assert abs(calendar.calendar_weight.sum() - 1) < 1e-12
    assert calendar.set_index("date").loc["2024-01-01", "calendar_weight"] == 0


def test_calendar_uses_iso_week_53_mean_and_rejects_monthly_double_seasonality():
    from hagrid_demand.baseline.calendar import calendar_weights

    weekly = pd.DataFrame({"week": list(range(1, 53)), "weight": [1] + [2] * 50 + [5]})
    cfg = {"weekday_weights": {"private": [1] * 7}, "monthly_weights": [1] * 12}
    calendar = calendar_weights(2026, "private", weekly, cfg).set_index("date")

    assert calendar.loc["2026-12-28", "season_factor"] == pytest.approx(3)
    cfg["monthly_weights"] = [2] + [1] * 11
    with pytest.raises(ValueError, match="weekly.*monthly"):
        calendar_weights(2026, "private", weekly, cfg)


def test_calendar_seasonality_strength_zero_neutralizes_weekly_profile():
    from hagrid_demand.baseline.calendar import calendar_weights

    weekly = pd.DataFrame({"week": list(range(1, 53)), "weight": list(range(1, 53))})
    cfg = {"weekday_weights": {"private": [1] * 7}, "monthly_weights": [1] * 12,
           "seasonality_strength": {"private": 0}}
    calendar = calendar_weights(2021, "private", weekly, cfg)

    assert calendar.season_factor.nunique() == 1
    assert calendar.calendar_weight.nunique() == 1
    cfg["seasonality_strength"] = {"private": 2.1}
    with pytest.raises(ValueError, match="seasonality_strength"):
        calendar_weights(2021, "private", weekly, cfg)


def test_lower_saxony_public_holidays_follow_easter():
    from hagrid_demand.baseline.calendar import public_holidays

    assert public_holidays(2025, "NI") == [
        "2025-01-01", "2025-04-18", "2025-04-21", "2025-05-01", "2025-05-29",
        "2025-06-09", "2025-10-03", "2025-10-31", "2025-12-25", "2025-12-26",
    ]
    assert "2021-05-13" in public_holidays(2021, "NI")
    assert public_holidays(2025, None) == []
    with pytest.raises(ValueError, match="holiday region"):
        public_holidays(2025, "BY")


def test_default_calendar_uses_notebook_weekday_distribution_without_sundays():
    from hagrid_demand.baseline.calendar import DEFAULT_WEEKDAY_WEIGHTS, calendar_weights

    cfg = {"weekday_weights": {"private": DEFAULT_WEEKDAY_WEIGHTS}, "monthly_weights": [1] * 12}
    calendar = calendar_weights(2025, "private", None, cfg).set_index("date")

    assert DEFAULT_WEEKDAY_WEIGHTS == [0.16, 0.17, 0.19, 0.18, 0.15, 0.115, 0.0]
    assert calendar.loc["2025-03-09", "calendar_weight"] == 0
    assert calendar.loc["2025-03-05", "calendar_weight"] / calendar.loc["2025-03-08", "calendar_weight"] == pytest.approx(0.19 / 0.115)
    assert (calendar.calendar_weight > 0).sum() == 313


def test_reference_operating_days_can_follow_the_delivery_calendar(tmp_path):
    import json

    from baseline_fixtures import write_fixture
    from hagrid_demand.baseline.config import load_baseline_config

    path = write_fixture(tmp_path)
    config = json.loads(path.read_text(encoding="utf-8"))
    config["reference_operating_days"] = "calendar"
    path.write_text(json.dumps(config), encoding="utf-8")
    resolved = load_baseline_config(path)

    # 2021: 313 Monday-Saturday dates minus 7 Lower Saxony holidays on those weekdays.
    assert resolved["reference_operating_days"] == 306
    assert resolved["reference_operating_days_rule"] == "calendar"
