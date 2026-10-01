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

    import numpy as np

    from hagrid_demand.baseline.land_use import aged_histograms, load_land_use_inputs

    q = np.asarray(load_land_use_inputs()["mortality"]["q"], dtype=float)
    persons = pd.DataFrame({"district_id": ["A", "A"], "age": [60, 20], "persons": [100., 50.]})
    index = pd.DataFrame({"year": [2025, 2030], "district_id": ["A", "A"], "population_index": [1.0, 1.2]})
    table = aged_histograms(persons, [2025, 2030], 2025, index, q).set_index(["year", "district_id", "age"]).persons
    assert table.loc[(2030, "A", 65)] > 0 and table.get((2030, "A", 60), 0.) == 0.
    assert table.loc[(2030, "A", 25)] > 0
    assert table.loc[2030].sum() == pytest.approx(150. * 1.2)
    survival = np.prod(1 - q[60:65]) / np.prod(1 - q[20:25])
    assert table.loc[(2030, "A", 65)] / table.loc[(2030, "A", 25)] == pytest.approx(2.0 * survival)


def test_aged_histograms_apply_survival_from_the_life_table():
    import numpy as np
    import pandas as pd

    from hagrid_demand.baseline.land_use import aged_histograms, load_land_use_inputs

    q = np.asarray(load_land_use_inputs()["mortality"]["q"], dtype=float)
    assert len(q) == 101 and q[85] > q[60] > q[30] > 0
    persons = pd.DataFrame({"district_id": ["A", "A", "A"], "age": [30, 80, 99], "persons": [1000., 1000., 1000.]})
    index = pd.DataFrame({"year": [2025, 2030], "district_id": ["A", "A"], "population_index": [1.0, 1.0]})
    table = aged_histograms(persons, [2025, 2030], 2025, index, q).set_index(["year", "district_id", "age"]).persons
    survival = lambda age: np.prod(1 - q[age:age + 5])                   # five years from `age` on
    old_100 = (1 - q[99]) * np.prod([1 - q[100]] * 4)                     # 99 -> 100, then four years in the open bucket
    assert table.loc[(2030, "A", 85)] / table.loc[(2030, "A", 35)] == pytest.approx(survival(80) / survival(30))
    assert table.loc[(2030, "A", 100)] / table.loc[(2030, "A", 35)] == pytest.approx(old_100 / survival(30))
    assert table.loc[2030].sum() == pytest.approx(3000.)                 # the total still follows the forecast


def _age_seed(ages, district_ids):
    import pandas as pd

    return pd.DataFrame([{"district_id": district, "age": age, "persons": 10.} for district in district_ids for age in ages])


def test_aged_histograms_rake_to_the_forecast_age_structure():
    import numpy as np
    import pandas as pd

    from hagrid_demand.baseline.land_use import aged_histograms, load_land_use_inputs

    inputs = load_land_use_inputs()
    structure, q = inputs["age_structure"], inputs["mortality"]["q"]
    kinds = {"1.1": "city", "31": "umland"}
    persons = _age_seed(range(0, 101), list(kinds))
    index = pd.DataFrame({"year": [2025, 2025, 2030, 2030], "district_id": ["1.1", "31"] * 2, "population_index": [1., 1., 1.1, 0.9]})
    table = aged_histograms(persons, [2025, 2030], 2025, index, q, structure, kinds)
    for year, district in ((2025, "31"), (2030, "1.1")):
        rows = table.loc[table.year.eq(year) & table.district_id.eq(district)].set_index("age").persons
        weight = (year - 2024) / 10.
        youth, old = (np.interp(weight, [0, 1], structure["districts"][district][key]) for key in ("youth", "old"))
        working = rows.sum() / (1 + youth / 100 + old / 100)
        assert rows.loc[:17].sum() == pytest.approx(working * youth / 100, rel=1e-9)        # Tabelle 11 per district
        assert rows.loc[65:].sum() == pytest.approx(working * old / 100, rel=1e-9)
        area = np.array([np.interp(year - 2024, [0, 5, 10], [structure["areas"][kinds[district]][y][k] for y in ("2024", "2029", "2034")])
                         for k in range(10)])
        assert rows.loc[85:].sum() / rows.loc[65:].sum() == pytest.approx(area[9] / area[7:].sum(), rel=1e-9)   # Tabelle 5 within 65+
        assert rows.loc[0:2].sum() / rows.loc[:17].sum() == pytest.approx(area[0] / area[:4].sum(), rel=1e-9)
    totals = table.groupby(["year", "district_id"]).persons.sum()
    assert totals.loc[(2030, "1.1")] == pytest.approx(1010. * 1.1) and totals.loc[(2030, "31")] == pytest.approx(1010. * 0.9)


def test_age_raking_fills_age_groups_the_seed_lacks():
    import pandas as pd

    from hagrid_demand.baseline.land_use import aged_histograms, load_land_use_inputs

    inputs = load_land_use_inputs()
    persons = _age_seed(range(20, 60), ["31"])                       # nobody under 20 or from 60 on
    index = pd.DataFrame({"year": [2025], "district_id": ["31"], "population_index": [1.]})
    table = aged_histograms(persons, [2025], 2025, index, inputs["mortality"]["q"], inputs["age_structure"], {"31": "umland"})
    rows = table.set_index("age").persons
    assert rows.loc[85:].sum() > 0 and rows.loc[:2].sum() > 0
    assert rows.sum() == pytest.approx(400.)


def test_age_structure_covers_only_its_forecast_districts():
    from hagrid_demand.baseline.land_use import covers_age_structure, load_land_use_inputs

    inputs = load_land_use_inputs()
    packaged = {unit["id"]: unit["kind"] for unit in inputs["districts"]}
    assert covers_age_structure(inputs["age_structure"], packaged)
    assert not covers_age_structure(inputs["age_structure"], {**packaged, "A": "city"})
    assert not covers_age_structure(inputs["age_structure"], {"1.1": "elsewhere"})


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
    histograms = aged_histograms(persons, [2025, 2035], 2025, index, load_land_use_inputs()["mortality"]["q"])
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


# --- Task 3: new sites ----------------------------------------------------------------------------------------------

def _landuse():
    import geopandas as gpd
    from shapely.geometry import box

    return gpd.GeoDataFrame({"name": ["Testgebiet", None, None], "fclass": ["residential", "commercial", "industrial"]},
                            geometry=[box(0, 0, 200, 100), box(1000, 0, 1100, 100), box(2000, 0, 2300, 100)], crs=25832)


def _postal():
    import geopandas as gpd
    from shapely.geometry import box

    return gpd.GeoDataFrame({"plz": ["30001", "30002"]}, geometry=[box(-1000, -1000, 500, 1000), box(500, -1000, 5000, 1000)], crs=25832)


def _area(name="Testgebiet", **geometry):
    return {"name": name, "district_id": "A", "residents": 100, "start_year": 2026, "ramp_years": 2,
            "geometry": geometry or {"osm_landuse_name": "Testgebiet"}}


def test_development_geometry_prefers_osm_name():
    import math

    from hagrid_demand.baseline.land_use import development_geometry

    assert development_geometry(_area(), _landuse()).area == pytest.approx(200 * 100)
    circle = development_geometry(_area(center=[5000., 5000.], radius_m=100.), _landuse())
    assert circle.area == pytest.approx(math.pi * 100 ** 2, rel=0.01) and circle.centroid.x == pytest.approx(5000.)
    with pytest.raises(ValueError, match="Nirgendwo"):
        development_geometry(_area(osm_landuse_name="Nirgendwo"), _landuse())


def test_development_sites_fill_the_polygon_and_split_residents():
    import pandas as pd

    from hagrid_demand.baseline.land_use import development_geometry, development_sites

    residents = pd.DataFrame({"year": [2025, 2026, 2027], "name": "Testgebiet", "district_id": "A", "residents_model": [0., 50., 100.]})
    sites = development_sites([_area()], _landuse(), residents, pd.Series({"A": 0.001}), 50., _postal())
    polygon = development_geometry(_area(), _landuse())
    assert len(sites) == 8 and sites.within(polygon.buffer(1e-6)).all()          # 4 x 2 grid points of 50 m
    assert sites.population.sum() == pytest.approx(100.) and sites.population.nunique() == 1
    assert sites.historical_share.sum() == pytest.approx(100. * 0.001)
    assert set(sites.segment) == {"private"} and set(sites.plz) == {"30001"} and set(sites.year_opened) == {2026}
    assert sites.site_id.tolist()[:2] == ["lu:res:testgebiet:0", "lu:res:testgebiet:1"]
    small = development_sites([_area(center=[100., 50.], radius_m=20.)], _landuse(), residents, pd.Series({"A": 0.001}), 50., _postal())
    assert len(small) >= 5 and small.population.sum() == pytest.approx(100.)


def test_new_firms_follow_growth_and_are_deterministic():
    import numpy as np
    import pandas as pd

    from hagrid_demand.baseline.land_use import new_firms

    companies = pd.DataFrame({"branch": ["Q"] * 50 + ["G"] * 10 + ["C"] * 10, "employees": [2.] * 50 + [5.] * 20})
    rates = {"Q": 0.12, "G": 0.0, "C": -0.01, "default": 0.0}

    def run(seed):
        return new_firms(companies, 0.002, [2025, 2026, 2027], 2025, rates, 0.5, _landuse(), _postal(),
                         lambda year: np.random.default_rng([seed, year]))

    firms = run(1)
    assert set(firms.branch) == {"Q"} and firms.groupby("year_opened").size().to_dict() == {2026: 3, 2027: 3}
    assert firms.employees.sum() == pytest.approx(12.) and abs(firms.employees.sum() - 0.5 * (100 * 1.12 ** 2 - 100)) <= 2.
    assert firms.historical_share.tolist() == pytest.approx([2. * 0.002] * 6)
    commercial = _landuse().loc[lambda frame: frame.fclass.isin(["commercial", "industrial"])].union_all()
    assert firms.within(commercial).all() and set(firms.segment) == {"business"} and set(firms.plz) == {"30002"}
    assert firms.site_id.tolist()[0] == "lu:biz:Q:2026:0"
    again, other = run(1), run(2)
    assert firms.geometry.geom_equals(again.geometry).all() and not firms.geometry.geom_equals(other.geometry).all()


def test_land_use_stops_are_contiguous_and_grouped_per_area():
    import geopandas as gpd
    import pandas as pd
    from shapely.geometry import Point

    from hagrid_demand.baseline.land_use import land_use_stops

    sites = gpd.GeoDataFrame({"site_id": ["lu:res:b:0", "lu:res:a:0", "lu:res:a:1", "lu:biz:Q:2027:0", "lu:biz:Q:2026:0"],
                              "segment": ["private"] * 3 + ["business"] * 2, "plz": "30001",
                              "area": ["B", "A", "A", None, None], "year_opened": [2027, 2026, 2026, 2027, 2026]},
                             geometry=[Point(index, 0) for index in range(5)], crs=25832)
    stops, links = land_use_stops(sites, 100)
    assert stops.stop_index.tolist() == [100, 101, 102, 103, 104]
    assert stops.stop_id.tolist() == ["lu:biz:Q:2026:0", "lu:res:a:0", "lu:res:a:1", "lu:biz:Q:2027:0", "lu:res:b:0"]
    by_id = stops.set_index("stop_id").str_idx
    assert by_id["lu:res:a:0"] == by_id["lu:res:a:1"] < 0 and by_id["lu:res:b:0"] not in (by_id["lu:res:a:0"],)
    assert by_id["lu:biz:Q:2026:0"] != by_id["lu:biz:Q:2027:0"] and by_id["lu:biz:Q:2026:0"] <= -1000
    assert links.set_index("site_id").stop_id.to_dict() == {site: site for site in sites.site_id}
    assert {"stop_id", "stop_index", "str_idx", "part", "side", "section_id", "plz", "n_units", "expected_daily", "year_opened"} <= set(stops.columns)


def test_site_factors_combine_stock_propensity_and_openings():
    import pandas as pd

    from hagrid_demand.baseline.land_use import site_factors

    sites = pd.DataFrame({"site_id": ["s1", "b1", "lu:res:t:0", "lu:biz:Q:2027:0"], "segment": ["private", "business", "private", "business"],
                          "district_id": ["A", "A", "A", None], "year_opened": [None, None, 2026, 2027], "area": [None, None, "Testgebiet", None]})
    existing = pd.DataFrame({"year": [2025, 2026, 2027], "district_id": "A", "existing_factor": [1., 1.01, 1.02]})
    propensity = pd.DataFrame({"year": [2025, 2026, 2027], "district_id": "A", "propensity_index": [1., .99, .98]})
    firms = pd.DataFrame({"year": [2025, 2026, 2027], "site_id": "b1", "factor": [1., 1.01, 1.03]})
    residents = pd.DataFrame({"year": [2025, 2026, 2027], "name": "Testgebiet", "district_id": "A", "residents_model": [0., 50., 100.]})
    table = site_factors(sites, [2025, 2026, 2027], 2025, existing, propensity, firms, residents).set_index(["year", "site_id"]).factor
    assert [table.loc[(2025, site)] for site in sites.site_id] == pytest.approx([1., 1., 0., 0.])
    assert table.loc[(2027, "s1")] == pytest.approx(1.02 * .98) and table.loc[(2027, "b1")] == pytest.approx(1.03)
    assert table.loc[(2026, "lu:res:t:0")] == pytest.approx(.5 * .99) and table.loc[(2027, "lu:res:t:0")] == pytest.approx(.98)
    assert table.loc[(2026, "lu:biz:Q:2027:0")] == 0. and table.loc[(2027, "lu:biz:Q:2027:0")] == 1.


def test_site_firm_factor_weights_branches_by_employees():
    import pandas as pd

    from hagrid_demand.baseline.land_use import site_firm_factor

    companies = pd.DataFrame({"site_id": ["b1", "b1", "b2"], "branch": ["Q", "G", None], "employees": [30., 10., 0.]})
    table = site_firm_factor(companies, [2025, 2035], 2025, {"Q": 0.015, "G": 0.0, "default": 0.005}, 0.3).set_index(["year", "site_id"]).factor
    q = 1 + 0.7 * (1.015 ** 10 - 1)
    assert table.loc[(2035, "b1")] == pytest.approx((30 * q + 10 * 1.0) / 40)
    assert table.loc[(2035, "b2")] == pytest.approx(1 + 0.7 * (1.005 ** 10 - 1)) and table.loc[(2025, "b1")] == pytest.approx(1.)


def test_site_factors_accept_a_geodataframe():
    """GeoDataFrame.area is the geometric area, not the 'area' column: development sites must still be recognised."""
    import geopandas as gpd
    import pandas as pd
    from shapely.geometry import Point

    from hagrid_demand.baseline.land_use import site_factors

    sites = gpd.GeoDataFrame({"site_id": ["lu:res:t:0"], "segment": ["private"], "district_id": ["A"], "year_opened": [2026],
                              "area": ["Testgebiet"]}, geometry=[Point(0, 0)], crs=25832)
    residents = pd.DataFrame({"year": [2025, 2026], "name": "Testgebiet", "district_id": "A", "residents_model": [0., 100.]})
    empty = pd.DataFrame({"year": [], "district_id": [], "existing_factor": []})
    table = site_factors(sites, [2025, 2026], 2025, empty, pd.DataFrame({"year": [], "district_id": [], "propensity_index": []}),
                         pd.DataFrame({"year": [], "site_id": [], "factor": []}), residents).set_index(["year", "site_id"]).factor
    assert table.loc[(2025, "lu:res:t:0")] == 0. and table.loc[(2026, "lu:res:t:0")] == 1.


def test_site_factors_key_sites_by_segment():
    """A building can be a home and a firm at once; each row of the pair gets its own factor."""
    import pandas as pd

    from hagrid_demand.baseline.land_use import site_factors

    sites = pd.DataFrame({"site_id": ["x", "x"], "segment": ["private", "business"], "district_id": ["A", "A"],
                          "year_opened": [None, None], "area": [None, None]})
    existing = pd.DataFrame({"year": [2025, 2030], "district_id": "A", "existing_factor": [1., 1.1]})
    propensity = pd.DataFrame({"year": [2025, 2030], "district_id": "A", "propensity_index": [1., 1.]})
    firms = pd.DataFrame({"year": [2025, 2030], "site_id": "x", "factor": [1., 0.9]})
    residents = pd.DataFrame(columns=["year", "name", "district_id", "residents_model"])
    table = site_factors(sites, [2025, 2030], 2025, existing, propensity, firms, residents)
    assert list(table.columns) == ["year", "site_id", "segment", "factor"]
    factor = table.set_index(["year", "site_id", "segment"]).factor
    assert factor.loc[(2030, "x", "private")] == pytest.approx(1.1) and factor.loc[(2030, "x", "business")] == pytest.approx(0.9)



def test_new_firms_cover_the_growth_between_simulated_years():
    """With gaps between simulated years the new firms carry the growth since the previous simulated year."""
    import numpy as np
    import pandas as pd

    from hagrid_demand.baseline.land_use import new_firms

    companies = pd.DataFrame({"branch": ["Q"] * 50, "employees": [2.] * 50})
    firms = new_firms(companies, 0.002, [2025, 2030], 2025, {"Q": 0.12, "default": 0.0}, 0.5, _landuse(), _postal(),
                      lambda year: np.random.default_rng([1, year]))
    growth = 100. * (1.12 ** 5 - 1.)                                      # 2025 -> 2030
    assert firms.groupby("year_opened").size().to_dict() == {2030: int(np.floor(0.5 * growth / 2. + 0.5))}


def test_land_use_stops_never_share_the_reference_off_street_group():
    """Reference stops use str_idx -1 for off-street buildings; development areas and firms need their own groups."""
    import geopandas as gpd
    from shapely.geometry import Point

    from hagrid_demand.baseline.land_use import land_use_stops

    sites = gpd.GeoDataFrame({"site_id": ["lu:res:a:0", "lu:res:b:0", "lu:biz:Q:2026:0"], "segment": ["private", "private", "business"],
                              "plz": "30001", "area": ["A", "B", None], "year_opened": [2026, 2026, 2026]},
                             geometry=[Point(index, 0) for index in range(3)], crs=25832)
    stops, _ = land_use_stops(sites, 1_000_000)
    homes = stops.loc[stops.stop_id.str.startswith("lu:res:")].str_idx
    assert (homes <= -100).all() and homes.nunique() == 2 and -1 not in set(stops.str_idx)


# --- review findings ----------------------------------------------------------------------------------------------

def test_resolve_land_use_rejects_developments_before_the_base_year():
    """Development residents move in from start_year on; in the base year they would change the reference shares."""
    from hagrid_demand.baseline.land_use import resolve_land_use

    area = {"name": "Früh", "district_id": "6.2", "residents": 10, "start_year": 2025, "ramp_years": 2,
            "geometry": {"center": [550000.0, 5800000.0], "radius_m": 100}}
    with pytest.raises(ValueError, match="start_year"):
        resolve_land_use({"enabled": True, "developments": [area]})
    with pytest.raises(ValueError, match="start_year"):
        resolve_land_use({"enabled": True, "base_year": 2027})           # the standard areas start in 2026


def test_land_use_years_must_not_precede_the_base_year():
    from hagrid_demand.baseline.land_use import resolve_land_use, validate_land_use_years

    cfg = resolve_land_use({"enabled": True})
    validate_land_use_years(cfg, [2025, 2030])
    with pytest.raises(ValueError, match="base_year"):
        validate_land_use_years(cfg, [2024, 2025])


def test_resolve_land_use_rejects_duplicate_area_names_and_slugs():
    """Site ids of development homes carry the slug of the area name; two areas must never share one."""
    from hagrid_demand.baseline.land_use import resolve_land_use

    def area(name):
        return {"name": name, "district_id": "44", "residents": 10, "start_year": 2026, "ramp_years": 2,
                "geometry": {"center": [540000.0, 5804000.0], "radius_m": 100}}

    for names in (["Seelze-Süd", "Seelze-Süd"], ["Seelze-Süd", "Seelze Sud"]):
        with pytest.raises(ValueError, match="unique"):
            resolve_land_use({"enabled": True, "developments": [area(name) for name in names]})


def test_firm_factor_applies_the_full_decline_of_shrinking_branches():
    """No firms close in the model and none open in a shrinking branch, so its existing firms carry the whole decline."""
    import pandas as pd

    from hagrid_demand.baseline.land_use import firm_factor

    table = firm_factor(pd.Series({"c": "C", "q": "Q"}), [2025, 2035], 2025, {"C": -0.005, "Q": 0.015, "default": 0.0}, 0.3)
    factor = table.set_index(["year", "site_id"]).factor
    assert factor.loc[(2035, "c")] == pytest.approx(0.995 ** 10)
    assert factor.loc[(2035, "q")] == pytest.approx(1. + 0.7 * (1.015 ** 10 - 1.))   # growth: 30 % goes to new firms


def test_new_firms_spread_over_the_areas_within_a_year():
    """Within one year every commercial area takes a new firm before any area takes a second one."""
    import numpy as np
    import pandas as pd

    from hagrid_demand.baseline.land_use import new_firms

    companies = pd.DataFrame({"branch": ["Q"] * 50, "employees": [2.] * 50})
    for seed in range(20):
        firms = new_firms(companies, 0.002, [2025, 2026], 2025, {"Q": 0.12, "default": 0.0}, 0.5, _landuse(), _postal(),
                          lambda year, seed=seed: np.random.default_rng([seed, year]))
        area = np.where(firms.geometry.x < 1500., "commercial", "industrial")
        assert len(firms) == 3 and sorted(area[:2]) == ["commercial", "industrial"]


def test_stop_ranges_must_not_overlap():
    """Pickup points follow the reference stops, land-use stops start at LAND_USE_STOP_BASE; the ranges must not meet."""
    from hagrid_demand.baseline.land_use import check_stop_ranges

    check_stop_ranges(reference_last=67332, points=600, land_use_first=1_000_000)
    check_stop_ranges(reference_last=67332, points=600, land_use_first=None)
    check_stop_ranges(reference_last=999_000, points=999, land_use_first=1_000_000)
    with pytest.raises(ValueError, match="overlap"):
        check_stop_ranges(reference_last=999_500, points=600, land_use_first=1_000_000)


def test_new_firms_spread_over_the_areas_across_branches():
    """Without replacement holds for all new firms of a year, not only within one branch."""
    import numpy as np
    import pandas as pd

    from hagrid_demand.baseline.land_use import new_firms

    companies = pd.DataFrame({"branch": ["Q"] * 20 + ["J"] * 20, "employees": [2.] * 40})
    for seed in range(20):
        firms = new_firms(companies, 0.002, [2025, 2026], 2025, {"Q": 0.05, "J": 0.05, "default": 0.0}, 1.0, _landuse(), _postal(),
                          lambda year, seed=seed: np.random.default_rng([seed, year]))
        area = np.where(firms.geometry.x < 1500., "commercial", "industrial")
        assert sorted(firms.branch) == ["J", "Q"] and sorted(area) == ["commercial", "industrial"]
