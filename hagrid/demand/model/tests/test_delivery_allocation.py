import numpy as np
import pandas as pd
import pytest

from hagrid_demand.baseline.allocation import (carrier_plz_tilt, delivery_frame, draw_delivery_days, generate_days,
                                               make_dirichlet_plan, site_frailty)
from hagrid_demand.baseline.calendar import DEFAULT_WEEKDAY_WEIGHTS, calendar_weights

CFG = {"seed": 11, "spatial": {"mode": "dirichlet"}}
DATES = pd.date_range("2025-01-01", periods=3)


def _inputs(segments=("private", "business")):
    rows = [{"year": 2025, "site_id": f"{segment[0]}{index}", "plz": "30159" if index < 3 else "30161",
             "segment": segment, "annual_expected": 10. + index, "allocation_status": "observed"}
            for segment in segments for index in range(5)]
    annual = pd.DataFrame(rows)
    profiles = pd.DataFrame([{"year": 2025, "segment": segment, "carrier": carrier, "share": share}
                             for segment in ("private", "business") for carrier, share in (("A", .7), ("B", .3))])
    delivered = {("private", "A"): np.array([5, 0, 7]), ("private", "B"): np.array([3, 0, 2]),
                 ("business", "A"): np.array([4, 0, 1]), ("business", "B"): np.array([0, 0, 6])}
    delivered = {key: value for key, value in delivered.items() if key[0] in segments}
    return annual, profiles, delivered


def _draw(annual, profiles, delivered):
    plan = make_dirichlet_plan(annual, CFG)
    return list(draw_delivery_days(annual, profiles, delivered, DATES, CFG, 0, 0, spatial_plan=plan))


def test_carrier_totals_equal_delivered():
    annual, profiles, delivered = _inputs()
    for day, (date, segments) in enumerate(_draw(annual, profiles, delivered)):
        for segment, item in segments.items():
            assert item.counts.shape == (5, 2)
            assert item.counts.sum(axis=0).tolist() == [int(delivered[(segment, carrier)][day]) for carrier in item.carriers]


def test_frame_schema_matches_legacy(tmp_path):
    annual, profiles, delivered = _inputs()
    date, segments = _draw(annual, profiles, delivered)[0]
    expected = {key: value.astype(float) for key, value in delivered.items()}
    frame = delivery_frame(date, segments, expected, 0, 2025, 0, 0)
    calendar = pd.concat([calendar_weights(2025, segment, None, {"weekday_weights": {segment: DEFAULT_WEEKDAY_WEIGHTS}})
                          for segment in ("private", "business")], ignore_index=True)
    legacy = next(generate_days(annual, profiles, calendar, {**CFG, "dates": ["2025-01-02"]}, 0, 0,
                                spatial_plan=make_dirichlet_plan(annual, CFG), cache_dir=tmp_path))
    assert list(frame.columns) == list(legacy.columns)
    assert int(frame["count"].sum()) == 12
    assert frame.baseline_expected.sum() == pytest.approx(12.)


def test_zero_delivery_day_and_empty_segment():
    annual, profiles, delivered = _inputs(("private",))
    days = _draw(annual, profiles, delivered)
    assert set(days[1][1]) == {"private"} and days[1][1]["private"].counts.sum() == 0
    with pytest.raises(ValueError, match="no site support"):
        _draw(annual, profiles, {**delivered, ("business", "A"): np.array([1, 0, 0])})


def test_draws_are_deterministic():
    annual, profiles, delivered = _inputs()
    first, second = _draw(annual, profiles, delivered), _draw(annual, profiles, delivered)
    assert all(np.array_equal(a[1][s].counts, b[1][s].counts) for a, b in zip(first, second) for s in a[1])


def test_prepared_spatial_dirichlet_matches_legacy():
    from hagrid_demand.common.rng import named_rng
    from hagrid_demand.baseline.allocation import prepare_spatial, spatial_dirichlet, spatial_dirichlet_prepared

    weights = np.array([3., 0., 2., 5., 1., 4., 7.])
    plz = np.array(["30161", "30159", "30159", "30161", "30163", "30159", "30165"], dtype=object)
    sites = np.array([f"s{index}" for index in range(7)], dtype=object)
    structure = prepare_spatial(weights, plz, sites)
    for within, per_site in ((None, 40.), (500., None)):
        legacy = spatial_dirichlet(weights, plz, sites, 50_000., within, named_rng(5, channel="x"), within_per_site=per_site)
        prepared = spatial_dirichlet_prepared(structure, 50_000., within, named_rng(5, channel="x"), within_per_site=per_site)
        assert np.array_equal(legacy, prepared)


def test_subset_dates_reproduce_full_year_draws():
    annual, profiles, delivered = _inputs()
    full = _draw(annual, profiles, delivered)
    plan = make_dirichlet_plan(annual, CFG)
    subset = {key: value[[2]] for key, value in delivered.items()}
    (date, segments), = list(draw_delivery_days(annual, profiles, subset, DATES[[2]], CFG, 0, 0, spatial_plan=plan))
    assert date == DATES[2]
    assert all(np.array_equal(segments[s].counts, full[2][1][s].counts) for s in segments)


def test_carrier_plz_tilt_preserves_margins():
    weights, shares = np.array([.5, .3, .2]), np.array([.6, .3, .1])
    z = np.random.default_rng(2).standard_normal((3, 3))
    ratio = carrier_plz_tilt(weights, shares, .5, z)
    assert np.allclose((shares[:, None] * ratio).sum(axis=0), 1., atol=1e-9)
    assert np.allclose((ratio * weights[None, :]).sum(axis=1), 1., atol=1e-9)
    assert ratio.std() > .05
    assert np.array_equal(carrier_plz_tilt(weights, shares, 0., z), np.ones((3, 3)))


def test_site_frailty_keeps_group_totals():
    weights = np.arange(1., 2001.)
    groups = np.repeat(np.arange(20), 100)
    tilted = site_frailty(weights, groups, .5, np.random.default_rng(4))
    assert np.allclose(np.bincount(groups, tilted), np.bincount(groups, weights))
    assert .4 < (tilted / weights).std() < .6
    assert np.array_equal(site_frailty(weights, groups, 0., np.random.default_rng(4)), weights)


def test_strongholds_and_frailty_keep_carrier_totals():
    annual, profiles, delivered = _inputs(("private",))
    big = {key: value * 100_000 for key, value in delivered.items()}
    cfg = {"seed": 11, "spatial": {"mode": "dirichlet", "carrier_plz_log_sd": 1., "site_frailty_cv": .5}}
    streets = pd.Series(["s1", "s1", "s2", "s3", "s3"], index=[f"p{index}" for index in range(5)])
    days = list(draw_delivery_days(annual, profiles, big, DATES, cfg, 0, 0, spatial_plan=make_dirichlet_plan(annual, cfg),
                                   site_groups=streets))
    plain = list(draw_delivery_days(annual, profiles, big, DATES, CFG, 0, 0, spatial_plan=make_dirichlet_plan(annual, CFG)))
    for day, (date, segments) in enumerate(days):
        item = segments["private"]
        assert item.counts.sum(axis=0).tolist() == [int(big[("private", carrier)][day]) for carrier in item.carriers]
    tilted, base = days[0][1]["private"].counts, plain[0][1]["private"].counts
    share = lambda counts: counts[:3].sum(axis=0) / counts.sum(axis=0)
    assert abs(share(tilted)[0] - share(tilted)[1]) > .02 > abs(share(base)[0] - share(base)[1])


def test_legacy_mode_rejects_strongholds_and_frailty(tmp_path):
    annual, profiles, _ = _inputs()
    cfg = {"seed": 1, "spatial": {"mode": "dirichlet", "site_frailty_cv": .5}}
    with pytest.raises(ValueError, match="shipping_transit"):
        list(generate_days(annual, profiles, pd.DataFrame(), cfg, 0, 0, spatial_plan=make_dirichlet_plan(annual, cfg),
                           cache_dir=tmp_path))


def test_frailty_keeps_plz_totals_when_streets_cross_plz():
    annual, profiles, delivered = _inputs(("private",))
    cfg = {"seed": 11, "spatial": {"mode": "dirichlet", "between": 1e12, "within_per_site": 1e9, "site_frailty_cv": .5}}
    streets = pd.Series(["s1"] * 5, index=[f"p{index}" for index in range(5)])
    (date, segments), *_ = draw_delivery_days(annual, profiles, delivered, DATES, cfg, 0, 0,
                                              spatial_plan=make_dirichlet_plan(annual, cfg), site_groups=streets)
    item = segments["private"]
    weights = item.sites.annual_expected.to_numpy(float)
    first = item.sites.plz.eq("30159").to_numpy()
    assert item.shares[first].sum() == pytest.approx(weights[first].sum() / weights.sum(), abs=1e-4)
