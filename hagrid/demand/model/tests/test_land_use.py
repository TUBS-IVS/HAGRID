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
