import numpy as np
import pandas as pd
import pytest

from hagrid_demand.baseline.allocation import delivery_frame, draw_delivery_days, generate_days, make_dirichlet_plan
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
