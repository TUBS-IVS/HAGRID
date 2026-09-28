import numpy as np
import pandas as pd
import pytest


def _table():
    rows = []
    for plz, rate in (("A", .06), ("B", .06), ("C", .156)):
        for index in range(25):
            persons = 100 + index
            rows.append({"sid": len(rows), "plz": plz, "street": f"s{index}", "value": rate * persons,
                         "persons": persons, "companies": 0, "buildings": 3, "excluded": False})
    rows.append({"sid": len(rows), "plz": "A", "street": "gewerbe", "value": 0.06 * 10 + 0.4 * 20,
                 "persons": 10, "companies": 20, "buildings": 4, "excluded": False})
    rows.append({"sid": len(rows), "plz": "A", "street": "luecke", "value": 0., "persons": 200, "companies": 0,
                 "buildings": 5, "excluded": False})
    rows.append({"sid": len(rows), "plz": "A", "street": "leer", "value": 7., "persons": 0, "companies": 0,
                 "buildings": 0, "excluded": False})
    rows.append({"sid": len(rows), "plz": "A", "street": "gross", "value": 5000., "persons": 5, "companies": 1,
                 "buildings": 1, "excluded": True})
    return pd.DataFrame(rows)


def test_level_correction_scales_only_extreme_postal_levels():
    from hagrid_demand.baseline.anchor import AnchorConfig, apply_correction, level_correction

    corrections = level_correction(_table(), AnchorConfig()).set_index("plz")
    assert corrections.loc["C", "applied"] and corrections.loc["C", "factor"] == pytest.approx(2.6)
    assert not corrections.loc["A", "applied"] and corrections.loc["A", "factor"] == 1.
    corrected = apply_correction(_table(), corrections.reset_index())
    assert corrected.loc[corrected.plz.eq("C"), "dhl_corrected"].sum() == pytest.approx(
        _table().loc[lambda t: t.plz.eq("C"), "value"].sum() / 2.6)


def test_rates_decomposition_statuses_and_b2b_share():
    from hagrid_demand.baseline.anchor import (AnchorConfig, apply_correction, decompose, fit_dhl_rates,
                                               level_correction, observed_b2b_share)

    cfg = AnchorConfig()
    table = apply_correction(_table(), level_correction(_table(), cfg))
    rates = fit_dhl_rates(table)
    assert rates["person"] == pytest.approx(.06, rel=1e-6) and rates["company"] == pytest.approx(.4, rel=1e-6)
    parts = decompose(table, rates, cfg).set_index("street")
    assert parts.loc["gewerbe", "dhl_business"] == pytest.approx(8.) and parts.loc["gewerbe", "dhl_private"] == pytest.approx(.6)
    assert parts.loc["luecke", "anchor_status"] == "gap" and parts.loc["luecke", "dhl_private"] == pytest.approx(12.)
    assert parts.loc["leer", "anchor_status"] == "observed_unstructured"
    assert parts.loc["gross", "anchor_status"] == "excluded"
    observed = parts[parts.anchor_status.isin(["observed", "observed_unstructured"])]
    assert (observed.dhl_private + observed.dhl_business).sum() == pytest.approx(observed.dhl_corrected.sum())
    assert 0 < observed_b2b_share(parts.reset_index()) < 1


def test_structure_holdout_reports_street_and_postal_errors():
    from hagrid_demand.baseline.anchor import AnchorConfig, apply_correction, level_correction, structure_holdout

    table = apply_correction(_table(), level_correction(_table(), AnchorConfig()))
    result = structure_holdout(table, seed=1, folds=3)
    assert set(result) == {"M0_persons", "M1_persons_companies"}
    assert result["M1_persons_companies"]["street_wmape"] <= result["M0_persons"]["street_wmape"] + 1e-9


def _street_world():
    import geopandas as gpd
    import street_fixtures as fx
    from shapely.geometry import Point

    buildings = gpd.GeoDataFrame({
        "building_key": ["h1", "h2", "f1", "off"], "footprint": [True] * 4, "building_type": ["house"] * 3 + ["point"],
        "area_m2": [100.] * 4, "plz": ["01000"] * 4, "population": [60, 40, 0, 5], "companies": [1, 0, 3, 0],
        "employees": [0, 0, 30, 0], "street_norm": [None] * 4, "sid": [0, 0, 1, -1], "match_stage": ["nearest"] * 3 + ["none"],
        "distance_m": [5.] * 3 + [None], "part": [0, 0, 0, None], "position_m": [10., 70., 30., None],
        "side": ["left"] * 3 + [None], "section_id": ["0-0-0", "0-0-1", "1-0-0", None],
        "axis_x": [0.] * 4, "axis_y": [0.] * 4},
        geometry=[Point(fx.X0 + 10, fx.Y0 + 10), Point(fx.X0 + 70, fx.Y0 + 10), Point(fx.X0 + 410, fx.Y0 + 10),
                  Point(fx.X0 + 900, fx.Y0 + 900)], crs=fx.CRS)
    streets = fx.streets().assign(value=[6., 1.3])
    profiles = {"m": [.42, .58], "q_prior": [.3, .2], "lower": [0., 0.], "upper": [1., 1.], "scale": [1., 1.],
                "carriers": ["DHL", "Other"]}
    return buildings, streets, profiles


def test_street_reference_hits_b2b_target_and_dhl_identity_and_keeps_every_parcel():
    from hagrid_demand.baseline.anchor import solve_street_reference

    buildings, streets, profiles = _street_world()
    solved = solve_street_reference(buildings, streets, profiles, b=.23, operating_days=300, cfg={"min_streets": 99},
                                    seed=1, scope_plz=["01000"])

    checks = solved["checks"]
    assert checks["observed_identity"]["b2b_residual"] == pytest.approx(0., abs=1e-9)
    assert checks["observed_identity"]["total_residual"] == pytest.approx(0., abs=1e-9)
    sites = solved["sites"]
    assert sites.columns.tolist() == ["site_id", "plz", "segment", "population", "employees", "branch", "weight",
                                      "historical_share", "structural_share", "reference_annual", "allocation_status"]
    assert sites.groupby("segment").historical_share.sum().to_dict() == pytest.approx({"business": 1., "private": 1.})
    assert sites.loc[sites.site_id.eq("off"), "reference_annual"].sum() > 0  # structural fallback, not lost
    assert set(sites.loc[sites.site_id.eq("h1"), "segment"]) == {"private", "business"}  # mixed-use building
    assert solved["regional_annual"] == pytest.approx(sites.reference_annual.sum())
    q = solved["carriers"].set_index("carrier").q_adjusted
    assert q["DHL"] == pytest.approx(solved["anchor"]["q_dhl"])
    assert set(solved["geometry"].site_id) == set(sites.site_id)
    business = sites.loc[sites.segment.eq("business"), "reference_annual"].sum()
    assert solved["anchor"]["b2b_incl_fallback"] == pytest.approx(business / sites.reference_annual.sum())


def test_street_reference_places_unstructured_dhl_on_synthetic_points():
    from hagrid_demand.baseline.anchor import solve_street_reference

    buildings, streets, profiles = _street_world()
    streets = streets.copy()
    extra = streets.iloc[[0]].assign(sid=2, value=4., street="Leer")
    streets = type(streets)(__import__("pandas").concat([streets, extra], ignore_index=True), crs=streets.crs)
    import warnings
    with warnings.catch_warnings(record=True) as caught:
        warnings.simplefilter("always")
        solved = solve_street_reference(buildings, streets, profiles, b=.23, operating_days=300, cfg={"min_streets": 99},
                                        seed=1, scope_plz=["01000"])
    synthetic = solved["sites"][solved["sites"].site_id.str.startswith("syn:2:")]
    assert not [w for w in caught if issubclass(w.category, FutureWarning)]
    assert len(synthetic) > 0
    daily = synthetic.reference_annual.sum() / 300
    streets_out = solved["streets"].set_index("sid")
    assert daily == pytest.approx(streets_out.loc[2, "private_daily"] + streets_out.loc[2, "business_daily"])


def test_street_reference_reports_persons_and_firms_on_zero_streets():
    from hagrid_demand.baseline.anchor import solve_street_reference

    buildings, streets, profiles = _street_world()
    buildings = buildings.copy()
    extra = buildings.iloc[[1]].assign(building_key="z1", sid=2, population=3., companies=0)
    buildings = type(buildings)(__import__("pandas").concat([buildings, extra], ignore_index=True), crs=buildings.crs)
    streets = streets.copy()
    zero = streets.iloc[[0]].assign(sid=2, value=0., street="Null")
    streets = type(streets)(__import__("pandas").concat([streets, zero], ignore_index=True), crs=streets.crs)
    solved = solve_street_reference(buildings, streets, profiles, b=.23, operating_days=300, cfg={"min_streets": 99},
                                    seed=1, scope_plz=["01000"])
    assert solved["anchor"]["zero_street_units"] == {"streets": 1, "persons": 3.0, "firms": 0.0}
