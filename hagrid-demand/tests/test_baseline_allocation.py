import numpy as np
import pandas as pd


def test_dirichlet_support_balance_and_input_order():
    from hagrid_demand.baseline.allocation import spatial_dirichlet

    weights = np.array([.6, .4, 0.])
    plz = np.array(["01", "02", "02"])
    sites = np.array(["a", "b", "c"])
    result = spatial_dirichlet(weights, plz, sites, 100, 50, np.random.default_rng(42))
    reordered = spatial_dirichlet(weights[[2, 0, 1]], plz[[2, 0, 1]], sites[[2, 0, 1]],
                                  100, 50, np.random.default_rng(42))

    assert np.isclose(result.sum(), 1)
    assert result[2] == 0
    assert np.array_equal(result, reordered[[1, 2, 0]])


def test_dirichlet_means_preserve_site_and_postal_targets():
    from hagrid_demand.baseline.allocation import spatial_dirichlet

    weights = np.array([.6, .2, .2, 0.])
    plz = np.array(["01", "01", "02", "03"])
    sites = np.array(["a", "b", "c", "d"])
    draws = np.array([spatial_dirichlet(weights, plz, sites, 40, 20, np.random.default_rng(seed))
                      for seed in range(4096)])
    mean = draws.mean(axis=0)
    standard_error = draws.std(axis=0, ddof=1) / np.sqrt(len(draws))
    target = weights / weights.sum()
    assert np.all(np.abs(mean[:3] - target[:3]) <= 5 * standard_error[:3] + 1e-4)
    postal = np.column_stack([draws[:, :2].sum(axis=1), draws[:, 2], draws[:, 3]])
    postal_target = np.array([.8, .2, 0.])
    postal_se = postal.std(axis=0, ddof=1) / np.sqrt(len(draws))
    assert np.all(np.abs(postal.mean(axis=0) - postal_target) <= 5 * postal_se + 1e-4)
    assert np.array_equal(draws[:, 3], np.zeros(len(draws)))


def test_dirichlet_between_and_within_concentrations_control_distinct_levels():
    from hagrid_demand.baseline.allocation import spatial_dirichlet

    weights = np.array([.5, .3, .2])
    plz = np.array(["01", "01", "02"])
    sites = np.array(["a", "b", "c"])
    postal_variable = np.array([spatial_dirichlet(weights, plz, sites, .2, 1_000., np.random.default_rng(seed))
                                for seed in range(256)])
    local_variable = np.array([spatial_dirichlet(weights, plz, sites, 1_000., .2, np.random.default_rng(seed))
                               for seed in range(256)])
    postal_share = postal_variable[:, :2].sum(axis=1)
    local_share = local_variable[:, 0] / local_variable[:, :2].sum(axis=1)

    assert postal_share.var() > .1
    assert local_share.var() > .1
    assert local_variable[:, :2].sum(axis=1).var() < .001
    assert (postal_variable[:, 0] / postal_variable[:, :2].sum(axis=1)).var() < .001


def test_fixed_annual_counts_are_exact_and_expected_mode_is_conditional_poisson():
    from hagrid_demand.baseline.allocation import annual_day_counts

    calendars = {
        "private": pd.DataFrame({"date": pd.date_range("2024-01-01", periods=2), "calendar_weight": [.9, .1]}),
        "business": pd.DataFrame({"date": pd.date_range("2024-01-01", periods=2), "calendar_weight": [.2, .8]}),
    }
    shocks = {"private": np.array([1., 10.]), "business": np.array([5., 1.])}
    fixed = annual_day_counts(11.5, .25, calendars, shocks, 7, 0, 0, 2024, "fixed_annual")
    expected = annual_day_counts(11.5, .25, calendars, shocks, 7, 0, 0, 2024, "expected_annual")

    assert fixed.groupby("segment")["count"].sum().to_dict() == {"business": 3, "private": 9}
    assert fixed["count"].sum() == round(11.5)
    assert set(expected.columns) >= {"date", "segment", "calendar_weight", "shock", "count"}


def _inputs():
    annual = pd.DataFrame({
        "year": [2024, 2024, 2024], "site_id": ["b", "a", "zero"], "plz": ["02", "01", "01"],
        "segment": ["private", "private", "business"], "allocation_status": ["located"] * 3,
        "annual_expected": [9., 3., 0.], "share": [.75, .25, 0.],
    })
    profiles = pd.DataFrame({"year": [2024, 2024, 2024], "segment": ["private", "private", "business"],
                             "carrier": ["Z", "A", "A"], "share": [.4, .6, 1.]})
    days = pd.date_range("2024-01-01", "2024-12-31", freq="D")
    calendar = pd.concat([
        pd.DataFrame({"date": days, "year": 2024, "segment": "private", "calendar_weight": np.where(days.month == 1, 20., 1.)}),
        pd.DataFrame({"date": days, "year": 2024, "segment": "business", "calendar_weight": np.where(days.month == 1, 3., 1.)}),
    ], ignore_index=True)
    calendar["calendar_weight"] = calendar.groupby("segment").calendar_weight.transform(lambda x: x / x.sum())
    return annual, profiles, calendar


def test_generate_days_uses_complete_year_before_date_filter_and_conserves_carriers(tmp_path):
    from hagrid_demand.baseline.allocation import generate_days, make_dirichlet_plan

    annual, profiles, calendar = _inputs()
    cfg = {"seed": 19, "regime": "fixed_annual", "dates": ["2024-01-01"],
           "spatial": {"between": 30., "within": 20.},
           "process": {"common_day_log_sd": 1.3, "segment_day_log_sd": {"private": .7, "business": .2}}}
    plan = make_dirichlet_plan(annual, cfg)
    first = pd.concat(list(generate_days(annual, profiles, calendar, cfg, 2, 3, spatial_plan=plan, cache_dir=tmp_path)))
    all_cfg = {**cfg, "dates": None}
    whole = pd.concat(list(generate_days(annual, profiles, calendar, all_cfg, 2, 3, spatial_plan=plan, cache_dir=tmp_path)))

    assert first.equals(whole.loc[whole.date.eq(pd.Timestamp("2024-01-01"))].reset_index(drop=True))
    assert whole.groupby("segment")["count"].sum().to_dict() == {"private": 12, "business": 0}
    assert (whole.groupby(["date", "segment"])["count"].sum() == whole.groupby(["date", "segment"]).daily_count.first()).all()
    assert whole.loc[whole.site_id.eq("zero"), "count"].eq(0).all()


def test_generate_days_applies_one_shared_common_factor_to_both_segments(tmp_path):
    from hagrid_demand.baseline.allocation import generate_days, make_dirichlet_plan

    annual, profiles, calendar = _inputs()
    annual.loc[annual.segment.eq("business"), "annual_expected"] = 4.
    cfg = {"seed": 4, "regime": "expected_annual", "dates": ["2024-01-01"],
           "spatial": {"between": 5., "within": 5.},
           "process": {"common_day_log_sd": .8, "segment_day_log_sd": {"private": 0., "business": 0.}}}
    list(generate_days(annual, profiles, calendar, cfg, 0, 0,
                        spatial_plan=make_dirichlet_plan(annual, cfg), cache_dir=tmp_path))
    artifact = next(tmp_path.rglob("annual_day_counts.parquet"))
    counts = pd.read_parquet(artifact)
    shocks = counts.pivot(index="date", columns="segment", values="shock")

    assert np.array_equal(shocks["private"].to_numpy(), shocks["business"].to_numpy())


def test_generate_days_is_invariant_to_annual_profile_and_calendar_input_order(tmp_path):
    from hagrid_demand.baseline.allocation import generate_days, make_dirichlet_plan

    annual, profiles, calendar = _inputs()
    cfg = {"seed": 9, "regime": "fixed_annual", "dates": ["2024-02-01"],
           "spatial": {"between": 10., "within": 10.}, "process": {}}
    first = pd.concat(list(generate_days(annual, profiles, calendar, cfg, 1, 1,
                                         spatial_plan=make_dirichlet_plan(annual, cfg), cache_dir=tmp_path / "one")))
    reversed_annual = annual.iloc[::-1].reset_index(drop=True)
    reversed_profiles = profiles.iloc[::-1].reset_index(drop=True)
    reversed_calendar = calendar.iloc[::-1].reset_index(drop=True)
    second = pd.concat(list(generate_days(reversed_annual, reversed_profiles, reversed_calendar, cfg, 1, 1,
                                          spatial_plan=make_dirichlet_plan(reversed_annual, cfg), cache_dir=tmp_path / "two")))

    assert first.equals(second)
