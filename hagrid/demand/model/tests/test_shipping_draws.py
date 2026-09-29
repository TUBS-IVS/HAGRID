import numpy as np
import pytest

from hagrid_demand.baseline.calendar import public_holidays
from hagrid_demand.baseline.shipping import delivery_calendar, resolve_temporal, shipping_weights
from hagrid_demand.baseline.shipping_draws import carrier_day_factors, expected_deliveries, simulate_deliveries, week_index


def _setup(year=2025, **overrides):
    temporal = resolve_temporal({"mode": "shipping_transit", **overrides})
    holidays = public_holidays(year, "NI")
    cal = delivery_calendar(year, holidays)
    shipping = {segment: shipping_weights(year, segment, None, temporal, {"holiday_dates": holidays})
                for segment in ("private", "business")}
    return temporal, cal, shipping


def test_simulation_is_reproducible():
    temporal, cal, shipping = _setup()
    targets = {("private", "DHL"): 5_000., ("business", "UPS"): 2_000.}
    first = simulate_deliveries(targets, shipping, cal, temporal, seed=7, year=2025, regime="expected_annual")
    again = simulate_deliveries(targets, shipping, cal, temporal, seed=7, year=2025, regime="expected_annual")
    other = simulate_deliveries(targets, shipping, cal, temporal, seed=8, year=2025, regime="expected_annual")
    assert all(np.array_equal(first[key], again[key]) for key in targets)
    assert any(not np.array_equal(first[key], other[key]) for key in targets)


def test_simulation_conserves_expectation():
    temporal, cal, shipping = _setup()
    targets = {("private", "Amazon"): 50_000., ("business", "UPS"): 50_000.}
    totals = np.array([[simulate_deliveries(targets, shipping, cal, temporal, seed=seed, year=2025,
                                            regime="expected_annual")[key].sum() for key in targets] for seed in range(200)])
    assert np.allclose(totals.mean(axis=0), 50_000., rtol=0.005)
    fixed = simulate_deliveries(targets, shipping, cal, temporal, seed=1, year=2025, regime="fixed_annual")
    assert [int(fixed[key].sum()) for key in targets] == [50_000, 50_000]
    expected = expected_deliveries(targets, shipping, cal, temporal)
    assert all(expected[key].sum() == pytest.approx(50_000., rel=1e-9) for key in targets)


def test_weekly_cv_matches_configured_sd():
    temporal, cal, shipping = _setup(week_log_sd=0.05, week_ar=0., carrier_week_log_sd=0., events=[])
    targets = {("private", "DHL"): 5e7}
    _, shipped = simulate_deliveries(targets, shipping, cal, temporal, seed=3, year=2025, regime="expected_annual",
                                     return_shipments=True)
    weeks = week_index(cal)
    full = [week for week in range(1, weeks.max()) if (weeks == week).sum() == 7]
    ratio = [shipped[("private", "DHL")][weeks == week].sum() / (5e7 * shipping["private"][weeks == week].sum())
             for week in full]
    assert 0.04 <= np.std(ratio) <= 0.06


def test_business_saturday_share_follows_open_rate():
    temporal, cal, shipping = _setup(week_log_sd=0., carrier_week_log_sd=0.)
    closed = expected_deliveries({("business", "DHL"): 1e6}, shipping, cal, temporal)[("business", "DHL")]
    open_all = expected_deliveries({("business", "DHL"): 1e6}, shipping, cal,
                                   {**temporal, "business_saturday_open": 1.})[("business", "DHL")]
    saturday = cal.weekday == 5
    assert closed[saturday].sum() == pytest.approx(0.2 * open_all[saturday].sum(), rel=1e-9)
    drawn = simulate_deliveries({("business", "DHL"): 1e6}, shipping, cal, temporal, seed=5, year=2025,
                                regime="expected_annual")[("business", "DHL")]
    assert drawn[saturday].sum() / drawn.sum() == pytest.approx(closed[saturday].sum() / 1e6, abs=0.005)


def test_zero_target_yields_zero():
    temporal, cal, shipping = _setup()
    result = simulate_deliveries({("business", "Amazon"): 0.}, shipping, cal, temporal, seed=1, year=2025,
                                 regime="expected_annual")
    assert result[("business", "Amazon")].sum() == 0 and len(result[("business", "Amazon")]) == 365


@pytest.mark.parametrize("year,days", [(2026, 365), (2028, 366)])
def test_iso_week_53_and_leap_year(year, days):
    temporal, cal, shipping = _setup(year)
    weeks = week_index(cal)
    assert len(weeks) == days and weeks.max() == len(np.unique(weeks)) - 1
    result = simulate_deliveries({("private", "DHL"): 1_000.}, shipping, cal, temporal, seed=2, year=year,
                                 regime="expected_annual")
    assert result[("private", "DHL")].shape == (days,)


def test_carrier_day_factors_shift_shares_only():
    shares = np.array([.5, .3, .2])
    z = np.random.default_rng(1).standard_normal((3, 2000))
    factors = carrier_day_factors(shares, .1, z)
    assert np.allclose((shares[:, None] * factors).sum(axis=0), 1., atol=1e-12)
    assert .05 < np.log(factors[0]).std() < .1
    assert np.array_equal(carrier_day_factors(shares, 0., z), np.ones_like(z))


def test_carrier_day_shock_moves_daily_shares():
    targets = {("private", "DHL"): 2e6, ("private", "Hermes"): 1e6}
    spread = []
    for sd in (0., .1):
        temporal, cal, shipping = _setup(carrier_day_log_sd=sd, carrier_week_log_sd=0.)
        out = simulate_deliveries(targets, shipping, cal, temporal, seed=3, year=2025, regime="fixed_annual")
        assert [int(out[key].sum()) for key in targets] == [2_000_000, 1_000_000]
        total = out[("private", "DHL")] + out[("private", "Hermes")]
        spread.append((out[("private", "DHL")][total > 0] / total[total > 0]).std())
    assert spread[1] > 3 * spread[0]


def test_carrier_day_log_sd_default_and_validation():
    assert resolve_temporal({"mode": "shipping_transit"})["carrier_day_log_sd"] == .03
    with pytest.raises(ValueError, match="carrier_day_log_sd"):
        resolve_temporal({"mode": "shipping_transit", "carrier_day_log_sd": -.1})


def test_events_shift_carrier_volume_within_the_year():
    targets = {("private", "Amazon"): 1e6, ("private", "DHL"): 1e6}
    runs = {}
    for label, events in (("on", "standard"), ("off", [])):
        temporal, cal, shipping = _setup(events=events)
        runs[label] = simulate_deliveries(targets, shipping, cal, temporal, seed=5, year=2025, regime="fixed_annual")
        expected = expected_deliveries(targets, shipping, cal, temporal)
        assert all(expected[key].sum() == pytest.approx(targets[key], rel=1e-9) for key in targets)
    prime = (cal.dates >= "2025-07-09") & (cal.dates <= "2025-07-12")
    amazon_on, amazon_off = runs["on"][("private", "Amazon")], runs["off"][("private", "Amazon")]
    assert amazon_on.sum() == amazon_off.sum() == 1_000_000
    assert amazon_on[prime].sum() > 1.5 * amazon_off[prime].sum()
    dhl_on, dhl_off = runs["on"][("private", "DHL")], runs["off"][("private", "DHL")]
    assert abs(dhl_on[prime].sum() / dhl_off[prime].sum() - 1) < .05
