import json

import geopandas as gpd
import pandas as pd
from shapely.geometry import box


OSM = """<?xml version="1.0" encoding="UTF-8"?>
<osm version="0.6" generator="test">
 <node id="1" lat="52.3700" lon="9.7300"/><node id="2" lat="52.3700" lon="9.7302"/>
 <node id="3" lat="52.3702" lon="9.7302"/><node id="4" lat="52.3702" lon="9.7300"/>
 <node id="5" lat="52.3701" lon="9.7301"><tag k="addr:street" v="Teststraße"/><tag k="addr:housenumber" v="1"/><tag k="shop" v="bakery"/></node>
 <node id="11" lat="53.0000" lon="9.7300"/><node id="12" lat="53.0000" lon="9.7302"/>
 <node id="13" lat="53.0002" lon="9.7302"/><node id="14" lat="53.0002" lon="9.7300"/>
 <way id="10"><nd ref="1"/><nd ref="2"/><nd ref="3"/><nd ref="4"/><nd ref="1"/><tag k="building" v="retail"/><tag k="addr:street" v="Teststraße"/><tag k="addr:housenumber" v="1"/></way>
 <way id="20"><nd ref="11"/><nd ref="12"/><nd ref="13"/><nd ref="14"/><nd ref="11"/><tag k="building" v="house"/></way>
</osm>"""


def test_clip_keeps_region_buildings_with_tags_and_writes_manifest(tmp_path):
    from hagrid_demand.baseline.osm import BUILDINGS_FILE, MANIFEST_FILE, POINTS_FILE, clip_osm_region

    pbf = tmp_path / "tiny.osm"
    pbf.write_text(OSM, encoding="utf-8")
    region = gpd.GeoSeries([box(9.72, 52.36, 9.74, 52.38)], crs=4326).to_crs(25832)
    pd.DataFrame({"postal_cod": ["30159"], "geometry": [region.iloc[0].wkt]}).to_csv(tmp_path / "plz.csv", index=False)

    manifest = clip_osm_region(pbf, tmp_path / "plz.csv", tmp_path / "out", buffer_m=0)

    buildings = gpd.read_parquet(tmp_path / "out" / BUILDINGS_FILE)
    points = gpd.read_parquet(tmp_path / "out" / POINTS_FILE)
    assert buildings.osm_way_id.tolist() == ["10"]
    assert buildings.loc[0, "addr_street"] == "Teststraße" and buildings.loc[0, "building"] == "retail"
    assert buildings.crs.to_epsg() == 25832
    assert points.loc[0, "shop"] == "bakery" and points.loc[0, "addr_housenumber"] == "1"
    assert manifest["files"][BUILDINGS_FILE] == 1
    assert json.loads((tmp_path / "out" / MANIFEST_FILE).read_text(encoding="utf-8"))["license"].startswith("ODbL")


def test_parse_other_tags_handles_escaped_quotes():
    from hagrid_demand.baseline.osm import parse_other_tags

    assert parse_other_tags('"a"=>"1","name"=>"x \\"y\\""') == {"a": "1", "name": 'x \\"y\\"'}
    assert parse_other_tags(None) == {}


TRANSIT_OSM = """<?xml version="1.0" encoding="UTF-8"?>
<osm version="0.6" generator="test">
 <node id="1" lat="52.3701" lon="9.7301"><tag k="railway" v="station"/><tag k="name" v="Test Hbf"/></node>
 <node id="2" lat="52.3702" lon="9.7303"><tag k="railway" v="tram_stop"/><tag k="name" v="Markt"/></node>
 <node id="3" lat="52.3703" lon="9.7305"><tag k="highway" v="bus_stop"/></node>
 <node id="4" lat="52.3704" lon="9.7307"><tag k="amenity" v="bus_station"/></node>
 <node id="5" lat="53.0001" lon="9.7301"><tag k="railway" v="station"/><tag k="name" v="Far away"/></node>
</osm>
"""


def test_transit_extract_keeps_stations_and_tram_stops_in_the_region(tmp_path):
    import geopandas as gpd
    import pandas as pd
    from shapely.geometry import box

    from hagrid_demand.baseline.osm import extract_transit_stations

    pbf = tmp_path / "transit.osm"
    pbf.write_text(TRANSIT_OSM, encoding="utf-8")
    region = gpd.GeoSeries([box(9.72, 52.36, 9.74, 52.38)], crs=4326).to_crs(25832)
    pd.DataFrame({"postal_cod": ["30159"], "geometry": [region.iloc[0].wkt]}).to_csv(tmp_path / "plz.csv", index=False)
    out = extract_transit_stations(pbf, tmp_path / "plz.csv", tmp_path / "transit.parquet", buffer_m=0)
    stations = gpd.read_parquet(out)
    assert sorted(stations.kind) == ["bus_station", "station", "tram_stop"] and stations.crs.to_epsg() == 25832
    assert "Test Hbf" in set(stations.name)


BOUNDARY_OSM = """<?xml version="1.0" encoding="UTF-8"?>
<osm version="0.6" generator="test">
 <node id="1" lat="52.3600" lon="9.7200"/><node id="2" lat="52.3600" lon="9.7400"/>
 <node id="3" lat="52.3800" lon="9.7400"/><node id="4" lat="52.3800" lon="9.7200"/>
 <node id="5" lat="52.3650" lon="9.7250"/><node id="6" lat="52.3650" lon="9.7300"/>
 <node id="7" lat="52.3700" lon="9.7300"/><node id="8" lat="52.3700" lon="9.7250"/>
 <node id="9" lat="52.3000" lon="9.6000"/><node id="10" lat="52.3000" lon="9.9000"/>
 <node id="11" lat="52.4500" lon="9.9000"/><node id="12" lat="52.4500" lon="9.6000"/>
 <way id="30"><nd ref="1"/><nd ref="2"/><nd ref="3"/><nd ref="4"/><nd ref="1"/></way>
 <way id="31"><nd ref="5"/><nd ref="6"/><nd ref="7"/><nd ref="8"/><nd ref="5"/></way>
 <way id="32"><nd ref="9"/><nd ref="10"/><nd ref="11"/><nd ref="12"/><nd ref="9"/></way>
 <relation id="100"><member type="way" ref="30" role="outer"/><tag k="type" v="boundary"/><tag k="boundary" v="administrative"/>
  <tag k="admin_level" v="8"/><tag k="name" v="Testgemeinde"/></relation>
 <relation id="101"><member type="way" ref="31" role="outer"/><tag k="type" v="boundary"/><tag k="boundary" v="administrative"/>
  <tag k="admin_level" v="10"/><tag k="name" v="Teststadtteil"/></relation>
 <relation id="102"><member type="way" ref="32" role="outer"/><tag k="type" v="boundary"/><tag k="boundary" v="administrative"/>
  <tag k="admin_level" v="6"/><tag k="name" v="Testkreis"/></relation>
</osm>"""


def test_extract_boundaries_keeps_levels_8_and_10(tmp_path):
    from hagrid_demand.baseline.osm import extract_boundaries

    pbf = tmp_path / "boundaries.osm"
    pbf.write_text(BOUNDARY_OSM, encoding="utf-8")
    region = gpd.GeoSeries([box(9.715, 52.355, 9.745, 52.385)], crs=4326).to_crs(25832)
    pd.DataFrame({"postal_cod": ["30159"], "geometry": [region.iloc[0].wkt]}).to_csv(tmp_path / "plz.csv", index=False)
    out = extract_boundaries(pbf, tmp_path / "plz.csv", tmp_path / "boundaries.parquet", buffer_m=0)
    frame = gpd.read_parquet(out)
    assert frame.crs.to_epsg() == 25832 and set(frame.columns) >= {"osm_id", "name", "admin_level", "geometry"}
    assert sorted(zip(frame.admin_level, frame.name)) == [(8, "Testgemeinde"), (10, "Teststadtteil")]
    assert frame.loc[frame.admin_level.eq(8)].geometry.iloc[0].area > frame.loc[frame.admin_level.eq(10)].geometry.iloc[0].area
