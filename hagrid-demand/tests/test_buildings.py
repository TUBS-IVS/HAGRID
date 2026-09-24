import numpy as np
import pandas as pd

import street_fixtures as fx


def test_load_buildings_drops_irrelevant_types_and_marks_pois():
    from hagrid_demand.baseline.buildings import load_buildings

    b = load_buildings(fx.osm_buildings())
    assert b.building_key.tolist() == ["osm:w1", "osm:w2", "osm:w3", "osm:w5"]
    assert b.set_index("building_key").loc["osm:w3", "poi"]
    assert (b.area_m2 > 0).all()


def test_zensus_cell_matches_laea_100m_grid():
    import geopandas as gpd
    from shapely.geometry import Point
    from hagrid_demand.baseline.buildings import zensus_cell

    cell = zensus_cell(gpd.GeoSeries([Point(4305350, 3251050)], crs=3035))
    assert cell.tolist() == ["100mN32510E43053"]


def test_private_sites_go_to_containing_or_nearest_building_or_stay_points():
    from hagrid_demand.baseline.buildings import assign_private, load_buildings

    result = assign_private(fx.sites(), load_buildings(fx.osm_buildings())).set_index("site_id")
    assert result.loc["res:a", ["building_key", "stage"]].tolist() == ["osm:w1", "within"]
    assert result.loc["res:b", "building_key"] == "osm:w2"
    assert result.loc["res:c", ["building_key", "stage"]].tolist() == ["pt:res:c", "point"]


def test_firms_prefer_fitting_buildings_in_their_cell_and_are_reproducible():
    from hagrid_demand.baseline.buildings import assign_firms, load_buildings

    buildings = load_buildings(fx.osm_buildings())
    residents = pd.Series({"osm:w1": 2, "osm:w2": 6})
    first = assign_firms(fx.sites(), buildings, residents, seed=7).set_index("site_id")
    second = assign_firms(fx.sites(), buildings, residents, seed=7).set_index("site_id")
    assert first.equals(second)
    assert first.loc["biz:x", "building_key"] in {"osm:w3", "osm:w2", "osm:w1"}
    assert first.loc["biz:y", ["building_key", "stage"]].tolist() == ["osm:w5", "cell"]
    assert set(first.stage) <= {"cell", "fallback", "point"}
