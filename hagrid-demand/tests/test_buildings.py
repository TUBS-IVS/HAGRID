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


def test_normalize_street_unifies_common_spellings():
    from hagrid_demand.baseline.buildings import normalize_street

    assert normalize_street("Hans-Böckler-Straße") == normalize_street("Hans Böckler Strasse") == "hans böckler strasse"
    assert normalize_street("Alexanderstr.") == "alexanderstrasse"
    assert normalize_street("  ") is None and normalize_street(None) is None


def test_match_streets_prefers_name_then_nearest_and_projects_side_and_section():
    import geopandas as gpd
    from shapely.geometry import Point
    from hagrid_demand.baseline.buildings import match_streets, normalize_street, project_on_streets, street_parts

    streets = fx.streets().assign(street_norm=lambda f: f.street.map(normalize_street))
    points = gpd.GeoDataFrame({"building_key": ["n", "near", "far"], "plz": ["01000"] * 3,
                               "street_norm": ["bstrasse", None, None]},
                              geometry=[Point(fx.X0 + 10, fx.Y0 + 10), Point(fx.X0 + 120, fx.Y0 - 15), Point(fx.X0 + 900, fx.Y0 + 900)],
                              crs=fx.CRS)
    matched = match_streets(points, streets).set_index("building_key")
    assert matched.loc["n", ["sid", "match_stage"]].tolist() == [1, "name"]
    assert matched.loc["near", ["sid", "match_stage"]].tolist() == [0, "nearest"]
    assert matched.loc["far", ["sid", "match_stage"]].tolist() == [-1, "none"]

    located = points.assign(sid=points.building_key.map(matched.sid)).query("sid >= 0")
    projected = project_on_streets(located, street_parts(streets)).set_index("building_key")
    assert projected.loc["near", "side"] == "right" and projected.loc["near", "section_id"] == "0-0-2"
    assert abs(projected.loc["near", "position_m"] - 120) < 1e-6
    assert projected.loc["n", "position_m"] == 0.0  # projection clamps to the start of Bstraße


def test_projection_uses_the_nearest_part_of_a_multilinestring_street():
    import geopandas as gpd
    from shapely.geometry import MultiLineString, Point
    from hagrid_demand.baseline.buildings import project_on_streets, street_parts

    street = gpd.GeoDataFrame({"sid": [5]}, geometry=[MultiLineString([[(0, 0), (100, 0)], [(0, 500), (100, 500)]])], crs=fx.CRS)
    point = gpd.GeoDataFrame({"building_key": ["k"], "sid": [5]}, geometry=[Point(40, 510)], crs=fx.CRS)
    projected = project_on_streets(point, street_parts(street)).iloc[0]
    assert projected.part == 1 and abs(projected.position_m - 40) < 1e-9 and projected.side == "left"
