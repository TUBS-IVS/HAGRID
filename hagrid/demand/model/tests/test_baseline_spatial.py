import numpy as np
import pandas as pd


def _inputs():
    target = np.array([.41, .23, .18, .12, .06])
    xy = np.array([[0., 0.], [100., 0.], [0., 100.], [900., 0.], [1000., 0.]])
    site_ids = np.array(["a", "b", "c", "d", "e"])
    parameters = {"length_scale_m": 250., "log_sigma": .9, "rho": .8,
                  "fourier_features": 48, "seed": 41, "field_anchor_date": "2024-01-01"}
    design = {"calibration_draws": 128, "validation_draws": 512,
              "calibration_seed": 101, "validation_seed": 202,
              "max_iterations": 50, "calibration_algorithm_version": 1}
    return target, xy, site_ids, parameters, design


def test_calibration_corrects_mean_on_independent_holdout_fields():
    """Removing the log-base correction makes this holdout mean miss the target."""
    from hagrid_demand.baseline.spatial import calibrate_spatial, spatial_diagnostics, spatial_field

    target, xy, site_ids, parameters, design = _inputs()
    result = calibrate_spatial(target, xy, site_ids, parameters, design)
    draws = (np.exp(result["log_base"] + parameters["log_sigma"] * spatial_field(
        "2024-01-01", site_ids, xy, parameters,
        {"seed": design["validation_seed"], "draw": draw, "channel": "holdout"}
    ) - np.max(result["log_base"] + parameters["log_sigma"] * spatial_field(
        "2024-01-01", site_ids, xy, parameters,
        {"seed": design["validation_seed"], "draw": draw, "channel": "holdout"}
    ))) for draw in range(design["validation_draws"]))
    shares = (value / value.sum() for value in draws)
    diagnostic = spatial_diagnostics(shares, target, np.array(["01", "01", "02", "02", "03"]))

    assert result["status"] == "complete"
    assert diagnostic["status"] == "complete"
    assert diagnostic["tvd"] <= .01


def test_calibration_identity_changes_for_target_and_parameter_changes():
    from hagrid_demand.baseline.spatial import calibrate_spatial

    target, xy, site_ids, parameters, design = _inputs()
    original = calibrate_spatial(target, xy, site_ids, parameters, design)
    changed_target = calibrate_spatial(np.array([.40, .24, .18, .12, .06]), xy, site_ids, parameters, design)
    changed_parameter = calibrate_spatial(target, xy, site_ids, {**parameters, "log_sigma": .7}, design)

    assert original["fingerprint"] != changed_target["fingerprint"]
    assert original["fingerprint"] != changed_parameter["fingerprint"]


def test_zero_sigma_preserves_target_exactly():
    from hagrid_demand.baseline.spatial import calibrate_spatial, spatial_field

    target, xy, site_ids, parameters, design = _inputs()
    result = calibrate_spatial(target, xy, site_ids, {**parameters, "log_sigma": 0.}, design)
    assert result["status"] == "complete"
    np.testing.assert_array_equal(np.asarray(result["diagnostics"]["mean"]), target)


def test_field_is_persistent_and_nearby_sites_share_a_local_component():
    from hagrid_demand.baseline.spatial import spatial_field

    _, xy, site_ids, parameters, _ = _inputs()
    today = spatial_field("2024-01-02", site_ids, xy, parameters, {"seed": 12})
    tomorrow = spatial_field("2024-01-03", site_ids, xy, parameters, {"seed": 12})

    assert not np.array_equal(today, tomorrow)
    assert abs(today[0] - today[1]) < abs(today[0] - today[4])


def test_unlocated_mass_stays_outside_correlated_coordinate_calibration(tmp_path):
    from hagrid_demand.baseline.spatial import resolve_spatial_plan
    from hagrid_demand.common.contracts import AnnualProjection

    sites = pd.DataFrame({"year": [2024, 2024, 2024], "segment": ["private"] * 3,
                          "site_id": ["a", "b", "rest"], "plz": ["01", "01", "99"],
                          "annual_expected": [5., 3., 2.], "allocation_status": ["located", "located", "unlocated"]})
    projection = AnnualProjection(sites, pd.DataFrame(), pd.DataFrame(), {"hashes": {}})
    reference = {"geometry": pd.DataFrame({"site_id": ["a", "b"], "x": [0., 100.], "y": [0., 0.]})}
    cfg = {"seed": 9, "spatial": {"mode": "correlated", "length_scale_m": 100., "log_sigma": .3,
                                      "rho": .5, "fourier_features": 16,
                                      "calibration_draws": 64, "validation_draws": 128}}

    plan = resolve_spatial_plan(reference, projection, cfg, 0, tmp_path)
    entry = plan.calibration["2024:private"]

    assert entry["site_ids"] == ["a", "b"]
    assert entry["unlocated_share"] == .2
    assert plan.status in {"complete", "mean_preservation_unresolved"}


def test_generate_days_uses_a_verified_correlated_plan_without_coordinates_for_rest_mass(tmp_path):
    """Changing correlated-plan validation to Dirichlet-only must fail this allocation."""
    from hagrid_demand.baseline.allocation import generate_days
    from hagrid_demand.baseline.spatial import resolve_spatial_plan
    from hagrid_demand.common.contracts import AnnualProjection

    days = pd.date_range("2024-01-01", "2024-12-31")
    annual = pd.DataFrame({"year": [2024, 2024], "segment": ["private", "private"],
                           "site_id": ["a", "rest"], "plz": ["01", "99"], "annual_expected": [8000., 2000.],
                           "allocation_status": ["located", "unlocated"]})
    profiles = pd.DataFrame({"year": [2024], "segment": ["private"], "carrier": ["DHL"], "share": [1.]})
    calendar = pd.concat([pd.DataFrame({"date": days, "year": 2024, "segment": segment,
                                        "calendar_weight": np.repeat(1 / len(days), len(days))})
                          for segment in ("private", "business")], ignore_index=True)
    projection = AnnualProjection(annual, pd.DataFrame(), pd.DataFrame(), {"hashes": {}})
    cfg = {"seed": 13, "regime": "fixed_annual", "dates": ["2024-01-01"], "process": {},
           "spatial": {"mode": "correlated", "length_scale_m": 100., "log_sigma": 0., "rho": .4,
                       "fourier_features": 16, "calibration_draws": 32, "validation_draws": 64}}
    plan = resolve_spatial_plan({"geometry": pd.DataFrame({"site_id": ["a"], "x": [0.], "y": [0.]})},
                                projection, cfg, 0, tmp_path)
    result = next(generate_days(annual, profiles, calendar, cfg, 0, 0, spatial_plan=plan, cache_dir=tmp_path))

    assert result.groupby("site_id")["count"].sum().sum() == result.daily_count.iloc[0]
    assert result.loc[result.site_id.eq("rest"), "allocation_status"].eq("unlocated").all()


def test_baseline_config_accepts_declared_correlated_spatial_parameters(tmp_path):
    """Removing the spatial config allowance rejects a valid daily correlated run."""
    import json
    from hagrid_demand.baseline.config import load_baseline_config

    (tmp_path / "input").mkdir()
    config = {"schema_version": 1, "rng_version": 1, "seed": 4, "input_dir": "input", "output_dir": "runs",
              "source_mode": "raw", "reference_year": 2021, "reference_operating_days": 313, "output_scope": "daily",
              "spatial": {"mode": "correlated", "length_scale_m": 1000., "log_sigma": .3, "rho": .5}}
    path = tmp_path / "baseline.json"
    path.write_text(json.dumps(config), encoding="utf-8")

    assert load_baseline_config(path)["spatial"]["mode"] == "correlated"


def test_resolved_holdout_and_runtime_use_the_same_segment_basis(tmp_path):
    """Changing calibration's segment identity away from the runtime basis breaks this probe."""
    from hagrid_demand.baseline.spatial import resolve_spatial_plan
    from hagrid_demand.common.contracts import AnnualProjection

    sites = pd.DataFrame({"year": [2024, 2024], "segment": ["private", "private"],
                          "site_id": ["a", "b"], "plz": ["01", "02"], "annual_expected": [6., 4.],
                          "allocation_status": ["located", "located"]})
    cfg = {"seed": 7, "spatial": {"mode": "correlated", "length_scale_m": 100., "log_sigma": .2, "rho": .4,
                                      "fourier_features": 16, "calibration_draws": 64, "validation_draws": 128}}
    plan = resolve_spatial_plan({"geometry": pd.DataFrame({"site_id": ["a", "b"], "x": [0., 90.], "y": [0., 0.]})},
                                AnnualProjection(sites, pd.DataFrame(), pd.DataFrame(), {"hashes": {}}), cfg, 0, tmp_path)

    assert plan.calibration["2024:private"]["holdout_basis_fingerprint"] == plan.calibration["2024:private"]["runtime_basis_fingerprint"]


def test_ar_field_does_not_reset_at_year_boundary_and_reuses_checkpoint(tmp_path):
    import hagrid_demand.baseline.spatial as spatial

    _, xy, site_ids, parameters, _ = _inputs()
    keys = {"seed": 14, "segment": "private", "checkpoint_dir": str(tmp_path)}
    dec31 = spatial.spatial_field("2024-12-31", site_ids, xy, parameters, {**keys, "year": 2024})
    first_jan = spatial.spatial_field("2025-01-01", site_ids, xy, parameters, {**keys, "year": 2025})
    spatial._CHECKPOINTS.clear()
    checkpoint_jan = spatial.spatial_field("2025-01-01", site_ids, xy, parameters, {**keys, "year": 2025})

    assert not np.array_equal(dec31, first_jan)
    np.testing.assert_array_equal(first_jan, checkpoint_jan)
    assert len([path for path in tmp_path.iterdir() if path.is_dir()]) == 1


def test_allocation_status_is_part_of_spatial_plan_identity(tmp_path):
    from hagrid_demand.baseline.spatial import resolve_spatial_plan
    from hagrid_demand.common.contracts import AnnualProjection

    sites = pd.DataFrame({"year": [2024, 2024], "segment": ["private", "private"], "site_id": ["a", "b"],
                          "plz": ["01", "02"], "annual_expected": [6., 4.], "allocation_status": ["located", "unlocated"]})
    cfg = {"seed": 7, "spatial": {"mode": "correlated", "length_scale_m": 100., "log_sigma": 0., "rho": .4,
                                      "fourier_features": 16, "calibration_draws": 32, "validation_draws": 64}}
    reference = {"geometry": pd.DataFrame({"site_id": ["a", "b"], "x": [0., 90.], "y": [0., 0.]})}
    first = resolve_spatial_plan(reference, AnnualProjection(sites, pd.DataFrame(), pd.DataFrame(), {"hashes": {}}), cfg, 0, tmp_path)
    swapped = sites.assign(allocation_status=["unlocated", "located"])
    second = resolve_spatial_plan(reference, AnnualProjection(swapped, pd.DataFrame(), pd.DataFrame(), {"hashes": {}}), cfg, 0, tmp_path)

    assert first.target_fingerprints != second.target_fingerprints


def test_unlocated_geometry_can_be_present_but_missing_coordinates(tmp_path):
    from hagrid_demand.baseline.spatial import resolve_spatial_plan
    from hagrid_demand.common.contracts import AnnualProjection

    sites = pd.DataFrame({"year": [2024, 2024], "segment": ["private", "private"], "site_id": ["a", "rest"],
                          "plz": ["01", "99"], "annual_expected": [8., 2.], "allocation_status": ["located", "unlocated"]})
    cfg = {"seed": 8, "spatial": {"mode": "correlated", "length_scale_m": 100., "log_sigma": 0., "rho": .4,
                                      "fourier_features": 16, "calibration_draws": 32, "validation_draws": 64}}
    reference = {"geometry": pd.DataFrame({"site_id": ["a", "rest"], "x": [0., np.nan], "y": [0., np.nan]})}

    plan = resolve_spatial_plan(reference, AnnualProjection(sites, pd.DataFrame(), pd.DataFrame(), {"hashes": {}}), cfg, 0, tmp_path)

    assert plan.status == "complete"


def test_generate_days_blocks_unresolved_correlated_plan(tmp_path):
    from dataclasses import replace
    from hagrid_demand.baseline.allocation import generate_days
    from hagrid_demand.baseline.spatial import resolve_spatial_plan
    from hagrid_demand.common.contracts import AnnualProjection

    days = pd.date_range("2024-01-01", "2024-12-31")
    annual = pd.DataFrame({"year": [2024], "segment": ["private"], "site_id": ["a"], "plz": ["01"],
                           "annual_expected": [1000.], "allocation_status": ["located"]})
    cfg = {"seed": 3, "regime": "fixed_annual", "dates": ["2024-01-01"], "process": {},
           "spatial": {"mode": "correlated", "length_scale_m": 100., "log_sigma": 0., "rho": .4,
                       "fourier_features": 16, "calibration_draws": 32, "validation_draws": 64}}
    plan = resolve_spatial_plan({"geometry": pd.DataFrame({"site_id": ["a"], "x": [0.], "y": [0.]})},
                                AnnualProjection(annual, pd.DataFrame(), pd.DataFrame(), {"hashes": {}}), cfg, 0, tmp_path)
    calendar = pd.concat([pd.DataFrame({"date": days, "year": 2024, "segment": segment,
                                        "calendar_weight": np.repeat(1 / len(days), len(days))})
                          for segment in ("private", "business")], ignore_index=True)
    profiles = pd.DataFrame({"year": [2024], "segment": ["private"], "carrier": ["DHL"], "share": [1.]})

    with np.testing.assert_raises_regex(ValueError, "mean_preservation_unresolved"):
        next(generate_days(annual, profiles, calendar, cfg, 0, 0,
                           spatial_plan=replace(plan, status="mean_preservation_unresolved"), cache_dir=tmp_path))
    with np.testing.assert_raises_regex(ValueError, "target fingerprints"):
        next(generate_days(annual.assign(allocation_status="unlocated"), profiles, calendar, cfg, 0, 0,
                           spatial_plan=plan, cache_dir=tmp_path))


def test_calibration_rejects_zero_localized_support_before_log_base():
    from hagrid_demand.baseline.spatial import calibrate_spatial

    target, xy, site_ids, parameters, design = _inputs()
    with np.testing.assert_raises_regex(ValueError, "strictly positive"):
        calibrate_spatial(np.array([.5, .5, 0., 0., 0.]), xy, site_ids, parameters, design)
