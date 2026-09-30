"""Tiny street/building world: two LSP streets, four buildings, persons and firms (EPSG:25832)."""

from __future__ import annotations

import geopandas as gpd
import pandas as pd
from shapely.geometry import LineString, Point, box

CRS = "EPSG:25832"
X0, Y0 = 550_000.0, 5_800_000.0


def osm_buildings() -> gpd.GeoDataFrame:
    rows = [
        {"osm_way_id": "1", "osm_id": None, "building": "house", "addr_street": "Aweg", "addr_housenumber": "1", "shop": None,
         "geometry": box(X0 + 10, Y0 + 5, X0 + 20, Y0 + 15)},
        {"osm_way_id": "2", "osm_id": None, "building": "apartments", "addr_street": "Aweg", "addr_housenumber": "3", "shop": None,
         "geometry": box(X0 + 60, Y0 + 5, X0 + 75, Y0 + 20)},
        {"osm_way_id": "3", "osm_id": None, "building": "retail", "addr_street": None, "addr_housenumber": None, "shop": "bakery",
         "geometry": box(X0 + 110, Y0 - 25, X0 + 130, Y0 - 5)},
        {"osm_way_id": "4", "osm_id": None, "building": "garage", "addr_street": None, "addr_housenumber": None, "shop": None,
         "geometry": box(X0 + 30, Y0 + 5, X0 + 34, Y0 + 9)},
        {"osm_way_id": "5", "osm_id": None, "building": "industrial", "addr_street": "Bstraße", "addr_housenumber": "9", "shop": None,
         "geometry": box(X0 + 400, Y0 + 5, X0 + 450, Y0 + 40)},
    ]
    return gpd.GeoDataFrame(rows, geometry="geometry", crs=CRS)


def sites() -> gpd.GeoDataFrame:
    rows = [
        {"site_id": "res:a", "segment": "private", "population": 2, "employees": None, "branch": None, "plz": "01000", "geometry": Point(X0 + 15, Y0 + 10)},
        {"site_id": "res:b", "segment": "private", "population": 6, "employees": None, "branch": None, "plz": "01000", "geometry": Point(X0 + 70, Y0 + 12)},
        {"site_id": "res:c", "segment": "private", "population": 1, "employees": None, "branch": None, "plz": "01000", "geometry": Point(X0 + 900, Y0 + 900)},
        {"site_id": "biz:x", "segment": "business", "population": 0, "employees": 5, "branch": "G", "plz": "01000", "geometry": Point(X0 + 118, Y0 - 30)},
        {"site_id": "biz:y", "segment": "business", "population": 0, "employees": 40, "branch": "C", "plz": "01000", "geometry": Point(X0 + 420, Y0 + 45)},
    ]
    return gpd.GeoDataFrame(rows, geometry="geometry", crs=CRS)


def streets() -> gpd.GeoDataFrame:
    return gpd.GeoDataFrame({
        "sid": [0, 1], "plz": ["01000", "01000"], "street": ["Aweg", "Bstraße"], "value": [4.0, 3.0],
        "geometry": [LineString([(X0, Y0), (X0 + 200, Y0)]), LineString([(X0 + 380, Y0), (X0 + 500, Y0)])],
    }, crs=CRS)


def postal() -> gpd.GeoDataFrame:
    return gpd.GeoDataFrame({"plz": ["01000"], "geometry": [box(X0 - 100, Y0 - 100, X0 + 1000, Y0 + 1000)]}, crs=CRS)


def write_street_fixture(root):
    """The raw baseline fixture plus OSM buildings for its persons and firms (street anchor mode)."""
    import json
    from pathlib import Path

    from baseline_fixtures import CRS as FIXTURE_CRS, write_fixture

    config_path = write_fixture(Path(root))
    inputs = Path(root) / "inputs"
    dhl = gpd.read_file(inputs / "dhl.shp")
    dhl["tagesschni"] = [30, 10, 30, 10]  # residential Alpha/Gamma, firm streets Beta/Delta: LSP B2B share 0.25
    dhl.to_file(inputs / "dhl.shp")
    gpd.GeoDataFrame({
        "osm_way_id": ["1", "2", "3", "4"], "osm_id": [None] * 4,
        "building": ["house", "house", "retail", "office"],
        "addr_street": ["Alpha", "Gamma", "Beta", "Delta"], "addr_housenumber": ["1", "2", "3", "4"], "shop": [None] * 4,
    }, geometry=[box(8, 8, 12, 12), box(108, 8, 112, 12), box(18, 18, 22, 22), box(118, 18, 122, 22)], crs=FIXTURE_CRS
    ).to_parquet(inputs / "osm_buildings.parquet", index=False)
    gpd.GeoDataFrame({"osm_id": pd.Series([], dtype=object), "addr_street": pd.Series([], dtype=object),
                      "addr_housenumber": pd.Series([], dtype=object), "shop": pd.Series([], dtype=object)},
                     geometry=gpd.GeoSeries([], crs=FIXTURE_CRS)).to_parquet(inputs / "osm_points.parquet", index=False)
    config = json.loads(config_path.read_text(encoding="utf-8"))
    config.update({"osm_buildings": "inputs/osm_buildings.parquet", "osm_points": "inputs/osm_points.parquet"})
    config_path.write_text(json.dumps(config), encoding="utf-8")
    return config_path
