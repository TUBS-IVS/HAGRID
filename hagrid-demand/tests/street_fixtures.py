"""Tiny street/building world: two DHL streets, four buildings, persons and firms (EPSG:25832)."""

from __future__ import annotations

import geopandas as gpd
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
