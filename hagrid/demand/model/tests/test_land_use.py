import pytest


def test_land_use_inputs_cover_the_forecast():
    """The packaged table reproduces the published totals of the 2025-2035 forecast (tables 7 and 8)."""
    from hagrid_demand.baseline.land_use import load_land_use_inputs

    units = load_land_use_inputs()["districts"]
    city = [unit for unit in units if unit["kind"] == "city"]
    umland = [unit for unit in units if unit["kind"] == "umland"]
    assert len(units) == 49 and len(city) == 29 and len(umland) == 20
    # the rounded district values of 2034 add up to 566,836 (the table total says 566,835)
    assert sum(unit["pop_2024"] for unit in city) == 558051 and sum(unit["pop_2034"] for unit in city) == 566836
    assert sum(unit["pop_2024"] for unit in umland) == 645435 and sum(unit["pop_2034"] for unit in umland) == 642077
    stadtteile = [name for unit in city for name in unit["stadtteile"]]
    assert len(stadtteile) == 51 and len(set(stadtteile)) == 51
    assert all(unit["municipality"] for unit in umland)
    by_id = {unit["id"]: unit for unit in units}
    assert (by_id["6.2"]["pop_2024"], by_id["6.2"]["pop_2034"]) == (22965, 26640)  # Bemerode, Kronsberg
    assert (by_id["4.1+4.2"]["pop_2024"], by_id["4.1+4.2"]["pop_2034"]) == (14790 + 19584, 15030 + 20147)
    assert by_id["4.1+4.2"]["stadtteile"] == ["Groß-Buchholz"]


def test_resolve_land_use_merges_and_validates():
    from hagrid_demand.baseline.land_use import resolve_land_use

    assert resolve_land_use(None) is None and resolve_land_use({"enabled": False}) is None
    cfg = resolve_land_use({"enabled": True, "variant": "innenentwicklung", "cohort_shift": 0.5})
    assert (cfg["variant"], cfg["cohort_shift"], cfg["base_year"], cfg["new_firm_share"], cfg["grid_m"]) == \
        ("innenentwicklung", 0.5, 2025, 0.3, 50)
    assert len(cfg["developments"]) == 8 and cfg["firm_rates"]["Q"] == 0.015 and cfg["firm_rates"]["default"] == 0.005
    assert {area["name"] for area in cfg["developments"]} >= {"Kronsberg-Süd", "Wasserstadt Limmer", "Seelze-Süd"}
    custom = resolve_land_use({"enabled": True, "developments": [
        {"name": "Testgebiet", "district_id": "6.2", "residents": 100, "start_year": 2027, "ramp_years": 2,
         "geometry": {"center": [550000.0, 5800000.0], "radius_m": 300}}]})
    assert [area["name"] for area in custom["developments"]] == ["Testgebiet"]
    bad_blocks = [{"cohort_shift": 1.5}, {"new_firm_share": -0.1}, {"grid_m": 0}, {"variant": "boom"}, {"base_year": 2020},
                  {"developments": [{"name": "x", "district_id": "6.2", "residents": 10, "start_year": 2026, "ramp_years": 2,
                                     "geometry": {}}]},
                  {"developments": [{"name": "x", "district_id": "99", "residents": 10, "start_year": 2026, "ramp_years": 2,
                                     "geometry": {"center": [1.0, 2.0], "radius_m": 100}}]},
                  {"unknown_key": 1}]
    for bad in bad_blocks:
        with pytest.raises(ValueError):
            resolve_land_use({"enabled": True, **bad})


# --- Task 2: factor functions -------------------------------------------------------------------------------------

def _inputs(variant_offsets=None):
    """Two districts: a growing city district and a shrinking municipality (hand-checked numbers)."""
    return {"districts": [{"id": "A", "name": "Stadtbezirk", "kind": "city", "pop_2024": 1000, "pop_2034": 1100, "stadtteile": ["S1", "S2"]},
                          {"id": "B", "name": "Testdorf", "kind": "umland", "pop_2024": 2000, "pop_2034": 1900, "municipality": "Testdorf"}],
            "variants": {"prognose": {"city": 0.0, "umland": 0.0}, "innenentwicklung": {"city": 0.001, "umland": -0.001},
                         **(variant_offsets or {})}}


def test_district_population_hits_the_forecast_and_base_year():
    from hagrid_demand.baseline.land_use import district_population

    table = district_population(_inputs(), list(range(2025, 2036)), 2025, "prognose").set_index(["year", "district_id"])
    assert table.loc[(2025, "A"), "population_index"] == pytest.approx(1.0)
    assert table.loc[(2025, "A"), "population"] == pytest.approx(1010.0)          # 1000 + 100 / 10
    assert table.loc[(2034, "A"), "population"] == pytest.approx(1100.0)
    assert table.loc[(2034, "A"), "population_index"] == pytest.approx(1100 / 1010)
    assert table.loc[(2035, "A"), "population"] == pytest.approx(1100 * 1.1 ** 0.1)
    assert table.loc[(2034, "B"), "population_index"] == pytest.approx(1900 / 1990)
    assert (table.population_index == table.forecast_index).all()


def test_variants_shift_city_and_umland_but_keep_the_region():
    from hagrid_demand.baseline.land_use import district_population

    years = list(range(2025, 2036))
    base = district_population(_inputs(), years, 2025, "prognose").set_index(["year", "district_id"])
    inner = district_population(_inputs(), years, 2025, "innenentwicklung").set_index(["year", "district_id"])
    assert inner.loc[(2035, "A"), "population_index"] > base.loc[(2035, "A"), "population_index"]
    assert inner.loc[(2035, "B"), "population_index"] < base.loc[(2035, "B"), "population_index"]
    for year in years:
        assert inner.loc[year].population.sum() == pytest.approx(base.loc[year].population.sum())
    assert inner.loc[(2035, "A"), "forecast_index"] == pytest.approx(base.loc[(2035, "A"), "population_index"])


def test_aged_histograms_shift_one_year_per_year():
    import pandas as pd

    from hagrid_demand.baseline.land_use import aged_histograms

    persons = pd.DataFrame({"district_id": ["A", "A"], "age": [60, 20], "persons": [100., 50.]})
    index = pd.DataFrame({"year": [2025, 2030], "district_id": ["A", "A"], "population_index": [1.0, 1.2]})
    table = aged_histograms(persons, [2025, 2030], 2025, index).set_index(["year", "district_id", "age"]).persons
    assert table.loc[(2030, "A", 65)] > 0 and table.get((2030, "A", 60), 0.) == 0.
    assert table.loc[(2030, "A", 25)] > 0
    assert table.loc[2030].sum() == pytest.approx(150. * 1.2)
    assert table.loc[(2030, "A", 65)] / table.loc[(2030, "A", 25)] == pytest.approx(2.0)


def test_propensity_cohort_shift_limits():
    import numpy as np

    from hagrid_demand.baseline.land_use import load_land_use_inputs, propensity

    curve = load_land_use_inputs()["propensity_curve"]["bands"]
    assert propensity(np.array([70.]), 2035, 2025, curve, 0.) == pytest.approx([0.61])
    assert propensity(np.array([70.]), 2035, 2025, curve, 1.) == pytest.approx([0.80])
    assert propensity(np.array([10., 20., 30., 50., 80.]), 2025, 2025, curve, 0.7) == pytest.approx([0., .84, .91, .80, .40])


def test_propensity_index_is_one_in_base_year_and_falls_with_ageing():
    import pandas as pd

    from hagrid_demand.baseline.land_use import aged_histograms, load_land_use_inputs, propensity_index

    curve = load_land_use_inputs()["propensity_curve"]["bands"]
    persons = pd.DataFrame({"district_id": ["A"], "age": [60], "persons": [100.]})
    index = pd.DataFrame({"year": [2025, 2035], "district_id": ["A", "A"], "population_index": [1.0, 1.0]})
    histograms = aged_histograms(persons, [2025, 2035], 2025, index)
    ageing = propensity_index(histograms, curve, 0., 2025).set_index(["year", "district_id"]).propensity_index
    assert ageing.loc[(2025, "A")] == pytest.approx(1.0)
    assert ageing.loc[(2035, "A")] == pytest.approx(0.61 / 0.80)   # everybody is 70 in 2035
    cohort = propensity_index(histograms, curve, 1., 2025).set_index(["year", "district_id"]).propensity_index
    assert cohort.loc[(2035, "A")] == pytest.approx(1.0)            # the cohort keeps its propensity


def test_existing_factor_clamps_and_reports():
    import pandas as pd

    from hagrid_demand.baseline.land_use import existing_factor

    persons = pd.Series({"A": 1000., "B": 500.})
    index = pd.DataFrame({"year": [2030, 2030], "district_id": ["A", "B"], "population_index": [1.05, 1.0]})
    residents = pd.DataFrame({"year": [2030, 2030], "name": ["Neubau", "Riesig"], "district_id": ["A", "B"], "residents_model": [30., 2000.]})
    table, warnings = existing_factor(persons, index, residents)
    table = table.set_index(["year", "district_id"]).existing_factor
    assert table.loc[(2030, "A")] == pytest.approx((1050. - 30.) / 1000.)
    assert table.loc[(2030, "B")] == 0.
    assert warnings == [{"year": 2030, "district_id": "B", "missing_persons": 1500.0}]


def test_development_residents_are_scaled_to_model_persons():
    import pandas as pd

    from hagrid_demand.baseline.land_use import development_residents

    areas = [{"name": "Kronsberg-Süd", "district_id": "6.2", "residents": 3000, "start_year": 2026, "ramp_years": 4,
              "geometry": {"osm_landuse_name": "Kronsberg-Süd"}}]
    table = development_residents(areas, [2025, 2026, 2029, 2035], pd.Series({"6.2": 0.95})).set_index("year").residents_model
    assert table.loc[2025] == 0. and table.loc[2026] == pytest.approx(3000 * .95 / 4)
    assert table.loc[2029] == pytest.approx(2850.) and table.loc[2035] == pytest.approx(2850.)


def test_firm_factor_compounds_by_branch():
    import pandas as pd

    from hagrid_demand.baseline.land_use import firm_factor

    branch = pd.Series({"f1": "Q", "f2": "X", "f3": None})
    rates = {"Q": 0.015, "default": 0.005}
    table = firm_factor(branch, [2025, 2035], 2025, rates, 0.3).set_index(["year", "site_id"]).factor
    assert table.loc[(2025, "f1")] == pytest.approx(1.0)
    assert table.loc[(2035, "f1")] == pytest.approx(1 + 0.7 * (1.015 ** 10 - 1))
    assert table.loc[(2035, "f2")] == pytest.approx(1 + 0.7 * (1.005 ** 10 - 1))
    assert table.loc[(2035, "f3")] == pytest.approx(table.loc[(2035, "f2")])


def _boundaries():
    import geopandas as gpd
    from shapely.geometry import box

    return gpd.GeoDataFrame({"osm_id": ["1", "2", "3", "4"], "name": ["Hannover", "Testdorf", "S1", "S2"], "admin_level": [8, 8, 10, 10]},
                            geometry=[box(0, 0, 100, 100), box(200, 0, 300, 100), box(0, 0, 50, 100), box(50, 0, 100, 100)], crs=25832)


def test_districts_unites_stadtteile_and_rejects_missing_names():
    from hagrid_demand.baseline.land_use import districts

    frame = districts(_boundaries(), _inputs()).set_index("district_id")
    assert sorted(frame.index) == ["A", "B"] and frame.loc["A", "kind"] == "city"
    assert frame.loc["A"].geometry.area == pytest.approx(100 * 100) and frame.loc["B"].geometry.area == pytest.approx(100 * 100)
    broken = _inputs()
    broken["districts"][0]["stadtteile"] = ["S1", "S3"]
    with pytest.raises(ValueError, match="S3"):
        districts(_boundaries(), broken)


def test_assign_districts_uses_nearest_for_outside_points():
    import numpy as np

    from hagrid_demand.baseline.land_use import assign_districts, districts

    frame = districts(_boundaries(), _inputs())
    xy = np.array([[25., 50.], [250., 50.], [180., 50.], [-20., 50.]])
    assert assign_districts(xy, frame).tolist() == ["A", "B", "B", "A"]


def test_propensity_cohort_shift_never_lowers_young_ages():
    """A 20-year-old of 2035 was a child in 2025; the cohort effect must not give them a child's propensity."""
    import numpy as np

    from hagrid_demand.baseline.land_use import load_land_use_inputs, propensity

    curve = load_land_use_inputs()["propensity_curve"]["bands"]
    assert propensity(np.array([20., 30., 50.]), 2035, 2025, curve, 1.) == pytest.approx([0.84, 0.91, 0.91])
