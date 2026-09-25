import numpy as np
import pandas as pd
import pytest

from hagrid_demand.baseline.calendar import public_holidays
from hagrid_demand.baseline.shipping import (delivery_calendar, derive_shipping_profile, event_factor, expected_delivery, landing,
                                             resolve_temporal, shipping_weights)

KERNEL = np.array([0.85, 0.13, 0.02])
TARGET = np.array([0.16, 0.17, 0.19, 0.18, 0.15, 0.115, 0.0])


def _index(cal, date):
    return int(cal.dates.get_loc(pd.Timestamp(date)))


def test_derived_private_profile_reproduces_delivery_target():
    profile = derive_shipping_profile(TARGET, KERNEL)
    assert np.allclose(profile, [0.178, 0.200, 0.185, 0.150, 0.113, 0.116, 0.058], atol=0.0011)
    assert profile.sum() == pytest.approx(1.)
    cal = delivery_calendar(2025, [])
    delivered = expected_delivery(profile[cal.weekday], cal, KERNEL, 1.)
    per_weekday = np.array([delivered[cal.weekday == day].mean() for day in range(7)])
    assert np.allclose(per_weekday / per_weekday.sum(), TARGET / TARGET.sum(), atol=0.001)


def test_landing_skips_sunday_and_holidays():
    cal = delivery_calendar(2025, public_holidays(2025, "NI"))
    one = landing(cal, 1)
    for shipped, landed in [("2025-05-16", "2025-05-17"), ("2025-05-17", "2025-05-19"),
                            ("2025-04-17", "2025-04-19"), ("2025-04-19", "2025-04-22")]:
        assert cal.dates[one[_index(cal, shipped)]] == pd.Timestamp(landed)


def test_business_saturday_roll_skips_holiday():
    cal = delivery_calendar(2025, public_holidays(2025, "NI"))
    weights = np.zeros(len(cal.dates))
    weights[_index(cal, "2025-04-17")] = 1.
    delivered = expected_delivery(weights, cal, np.array([1.]), 0.2)
    assert delivered[_index(cal, "2025-04-19")] == pytest.approx(0.2)
    assert delivered[_index(cal, "2025-04-22")] == pytest.approx(0.8)
    assert delivered.sum() == pytest.approx(1.)


def test_expected_delivery_conserves_mass_across_year_end():
    cal = delivery_calendar(2025, public_holidays(2025, "NI"))
    weights = np.random.default_rng(3).random(len(cal.dates))
    delivered = expected_delivery(weights, cal, KERNEL, 0.2)
    assert delivered.sum() == pytest.approx(weights.sum(), rel=1e-12)
    assert delivered[~cal.delivery].sum() == 0.
    last = np.zeros(len(cal.dates))
    last[_index(cal, "2025-12-31")] = 1.
    wrapped = expected_delivery(last, cal, np.array([1.]), 1.)
    assert wrapped[_index(cal, "2025-01-02")] == pytest.approx(1.)


def test_resolve_temporal_defaults_and_errors():
    assert resolve_temporal(None) is None
    assert resolve_temporal({"mode": "delivery_calendar"}) is None
    temporal = resolve_temporal({"mode": "shipping_transit"})
    assert np.allclose(temporal["shipping"]["business"], np.array([23, 21, 18, 16, 17, 5, 0]) / 100)
    assert np.allclose(temporal["kernels"]["default"], KERNEL)
    assert temporal["saturday"]["default"] == 1. and temporal["business_saturday_open"] == 0.2
    assert (temporal["week_log_sd"], temporal["week_ar"], temporal["carrier_week_log_sd"],
            temporal["weekday_concentration"]) == (0.016, 0.5, 0.02, 1000.)
    for bad in [{"mode": "x"}, {"mode": "shipping_transit", "transit_days": {"default": [0.5, 0.4]}},
                {"mode": "shipping_transit", "shipping_weekday_weights": {"private": [-1, 1, 1, 1, 1, 1, 1]}},
                {"mode": "shipping_transit", "unknown": 1}, {"mode": "shipping_transit", "week_ar": 1.}]:
        with pytest.raises(ValueError):
            resolve_temporal(bad)


def test_shipping_weights_zero_on_holidays_and_sundays():
    temporal = resolve_temporal({"mode": "shipping_transit"})
    holidays = public_holidays(2025, "NI")
    weights = shipping_weights(2025, "business", None, temporal, {"holiday_dates": holidays})
    dates = pd.date_range("2025-01-01", "2025-12-31")
    assert weights.sum() == pytest.approx(1.)
    assert weights[dates.dayofweek == 6].sum() == 0.
    assert all(weights[dates.get_loc(pd.Timestamp(day))] == 0. for day in holidays)


def test_holiday_shipments_spread_over_next_shipping_days():
    holidays = public_holidays(2025, "NI")
    cal = delivery_calendar(2025, holidays)

    def delivered(days):
        temporal = resolve_temporal({"mode": "shipping_transit", "holiday_spread_days": days})
        weights = shipping_weights(2025, "private", None, temporal, {"holiday_dates": holidays})
        assert weights.sum() == pytest.approx(1.) and weights[cal.dates.isin(pd.to_datetime(list(holidays)))].sum() == 0
        return pd.Series(expected_delivery(weights, cal, KERNEL, 1.), index=cal.dates)

    next_day, spread = delivered(1), delivered(3)
    easter, normal = pd.date_range("2025-04-22", "2025-04-26"), pd.date_range("2025-05-13", "2025-05-17")
    assert next_day["2025-04-22"] > next_day["2025-05-13"]
    assert spread[easter].max() < next_day[easter].max() and spread[easter].sum() > spread[normal].sum()
    with pytest.raises(ValueError, match="holiday_spread_days"):
        resolve_temporal({"mode": "shipping_transit", "holiday_spread_days": 0})


def test_christmas_orders_ship_before_christmas():
    holidays = public_holidays(2025, "NI")
    dates = pd.date_range("2025-01-01", "2025-12-31")
    weights = lambda **extra: pd.Series(shipping_weights(2025, "private", None, resolve_temporal({"mode": "shipping_transit", **extra}),
                                                         {"holiday_dates": holidays}), index=dates)
    new, old = weights(), weights(christmas_pull_forward_days=0, holiday_spread_days=1)
    assert new.sum() == pytest.approx(old.sum())
    assert new["2025-12-24":"2025-12-26"].sum() == 0
    assert old["2025-12-27"] > 2 * old["2025-12-06"] and new["2025-12-27"] == pytest.approx(new["2025-12-06"])
    assert new["2025-12-10":"2025-12-23"].sum() > old["2025-12-10":"2025-12-23"].sum()
    assert new["2025-01-02"] < old["2025-01-02"] and new["2025-01-03":"2025-01-05"].sum() > old["2025-01-03":"2025-01-05"].sum()
    with pytest.raises(ValueError, match="christmas_pull_forward_days"):
        resolve_temporal({"mode": "shipping_transit", "christmas_pull_forward_days": -1})


def test_half_delivery_days_move_the_rest_to_the_next_day():
    cal = delivery_calendar(2025, public_holidays(2025, "NI"))
    weights = np.zeros(len(cal.dates))
    weights[_index(cal, "2025-12-23")] = 1.
    private = expected_delivery(weights, cal, np.array([1.]), 1., half_days={"12-24": .5})
    business = expected_delivery(weights, cal, np.array([1.]), .2, half_days={"12-24": .5}, business=True)
    assert private[_index(cal, "2025-12-24")] == pytest.approx(.5) and private[_index(cal, "2025-12-27")] == pytest.approx(.5)
    assert business[_index(cal, "2025-12-24")] == pytest.approx(.5) and business[_index(cal, "2025-12-29")] == pytest.approx(.5)
    assert private.sum() == pytest.approx(1.) and business.sum() == pytest.approx(1.)
    assert resolve_temporal({"mode": "shipping_transit"})["half_delivery_days"] == {"12-24": .5, "12-31": .5}
    with pytest.raises(ValueError, match="half_delivery_days"):
        resolve_temporal({"mode": "shipping_transit", "half_delivery_days": {"12-24": 1.5}})


def test_carrier_events_prime_day_black_week_singles_day():
    temporal = resolve_temporal({"mode": "shipping_transit"})
    dates = pd.date_range("2025-01-01", "2025-12-31")
    amazon = pd.Series(event_factor(dates, temporal, "private", "Amazon"), index=dates)
    dhl = pd.Series(event_factor(dates, temporal, "private", "DHL"), index=dates)
    assert (amazon["2025-07-08":"2025-07-11"] == 2.).all() and amazon["2025-07-12"] == 1. and dhl["2025-07-08"] == 1.
    assert (amazon["2025-11-28":"2025-12-01"] == 1.8).all() and dhl["2025-11-28"] == 1.8 and amazon["2025-12-02"] == 1.
    assert dhl["2025-11-18"] == 1.15 and amazon["2025-11-18"] == 1.
    assert event_factor(dates, temporal, "business", "Amazon").max() == 1.
    assert event_factor(dates, resolve_temporal({"mode": "shipping_transit", "events": []}), "private", "Amazon").max() == 1.
    with pytest.raises(ValueError, match="events"):
        resolve_temporal({"mode": "shipping_transit", "events": [{"name": "x", "carriers": "all", "segments": ["private"], "uplift": -1}]})
