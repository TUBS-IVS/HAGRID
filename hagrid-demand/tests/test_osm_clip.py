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
