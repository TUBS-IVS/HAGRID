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
