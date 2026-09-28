"""Clip a Geofabrik OSM extract to the HAGRID study region (buildings and address/POI points)."""

from __future__ import annotations

import hashlib
import json
import re
import time
from pathlib import Path

import geopandas as gpd
import pandas as pd
import pyogrio
from shapely import wkt


BUILDINGS_FILE = "osm_buildings_region_hannover_2021.parquet"
POINTS_FILE = "osm_address_poi_region_hannover_2021.parquet"
MANIFEST_FILE = "osm_region_hannover_2021_manifest.json"
TAG_KEYS = ("addr:street", "addr:housenumber", "addr:postcode", "building:levels", "building:use",
            "shop", "office", "amenity", "craft", "industrial", "healthcare")
_PAIR = re.compile(r'"((?:[^"\\]|\\.)*)"=>"((?:[^"\\]|\\.)*)"')


def parse_other_tags(value) -> dict[str, str]:
    """Parse GDAL's hstore-style ``other_tags`` column."""
    if not isinstance(value, str):
        return {}
    return dict(_PAIR.findall(value))


def expand_tags(frame: pd.DataFrame) -> pd.DataFrame:
    """Materialise the tags HAGRID needs as columns (``addr:street`` -> ``addr_street``)."""
    frame = frame.copy()
    tags = frame["other_tags"].map(parse_other_tags) if "other_tags" in frame else pd.Series([{}] * len(frame), index=frame.index)
    for key in TAG_KEYS:
        values = tags.map(lambda item, key=key: item.get(key))
        if key in frame.columns:
            values = frame[key].where(frame[key].notna(), values)
        frame[key.replace(":", "_")] = values
    return frame


def study_region(plz_csv: Path, buffer_m: float):
    plz = pd.read_csv(plz_csv)
    plz = gpd.GeoDataFrame(plz, geometry=plz.geometry.map(wkt.loads), crs="EPSG:25832")
    return plz.union_all().buffer(buffer_m), len(plz)


TRANSIT_RAILWAY = ("station", "halt", "tram_stop")


def extract_transit_stations(pbf: Path, plz_csv: Path, out: Path, buffer_m: float = 250.) -> Path:
    """Write railway stations, halts, tram stops and bus terminals of the buffered postal-area union (EPSG:25832).

    Stations change little over the years, so the 2021 extract serves the station context of pickup points.
    """
    pbf, out = Path(pbf), Path(out)
    region, _ = study_region(Path(plz_csv), buffer_m)
    bbox = tuple(gpd.GeoSeries([region], crs=25832).to_crs(4326).total_bounds)
    pyogrio.set_gdal_config_options({"OSM_MAX_TMPFILE_SIZE": "4000", "OSM_USE_CUSTOM_INDEXING": "YES"})
    frames = []
    for layer in ("points", "multipolygons"):
        frame = pyogrio.read_dataframe(pbf, layer=layer, bbox=bbox)
        if frame.empty:
            continue
        tags = frame["other_tags"].map(parse_other_tags) if "other_tags" in frame else pd.Series([{}] * len(frame), index=frame.index)
        railway = frame["railway"] if "railway" in frame else tags.map(lambda item: item.get("railway"))
        amenity = frame["amenity"] if "amenity" in frame else tags.map(lambda item: item.get("amenity"))
        transport = tags.map(lambda item: item.get("public_transport"))
        kind = pd.Series(pd.NA, index=frame.index, dtype=object)
        kind = kind.mask(railway.isin(TRANSIT_RAILWAY), railway)
        kind = kind.mask(kind.isna() & amenity.eq("bus_station"), "bus_station")
        kind = kind.mask(kind.isna() & transport.eq("station"), "station")
        selected = frame.loc[kind.notna()].copy()
        if selected.empty:
            continue
        selected["kind"] = kind.loc[selected.index].astype(str)
        selected = selected.to_crs(25832)
        selected["geometry"] = selected.geometry.centroid
        frames.append(selected[["osm_id", "name", "kind", "geometry"] if "name" in selected else ["osm_id", "kind", "geometry"]])
    stations = (gpd.GeoDataFrame(pd.concat(frames, ignore_index=True), crs=25832) if frames
                else gpd.GeoDataFrame({"osm_id": [], "name": [], "kind": []}, geometry=gpd.GeoSeries([], crs=25832), crs=25832))
    if "name" not in stations:
        stations["name"] = None
    stations = stations[stations.intersects(region)].reset_index(drop=True)
    out.parent.mkdir(parents=True, exist_ok=True)
    stations.to_parquet(out, index=False)
    return out


def clip_osm_region(pbf: Path, plz_csv: Path, out_dir: Path, buffer_m: float = 250.) -> dict:
    """Write buildings, address/POI points and a manifest for the buffered postal-area union."""
    started = time.time()
    pbf, out_dir = Path(pbf), Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    region, plz_count = study_region(Path(plz_csv), buffer_m)
    bbox = tuple(gpd.GeoSeries([region], crs=25832).to_crs(4326).total_bounds)
    pyogrio.set_gdal_config_options({"OSM_MAX_TMPFILE_SIZE": "4000", "OSM_USE_CUSTOM_INDEXING": "YES"})

    buildings = pyogrio.read_dataframe(pbf, layer="multipolygons", bbox=bbox, where="building IS NOT NULL")
    buildings = expand_tags(buildings).to_crs(25832)
    buildings = buildings[buildings.intersects(region)].copy()
    buildings["area_m2"] = buildings.area
    keep = ["osm_id", "osm_way_id", "building", "name", "landuse", *[key.replace(":", "_") for key in TAG_KEYS], "area_m2", "geometry"]
    buildings = buildings[[column for column in keep if column in buildings.columns]].reset_index(drop=True)
    buildings.to_parquet(out_dir / BUILDINGS_FILE, index=False)

    points = expand_tags(pyogrio.read_dataframe(pbf, layer="points", bbox=bbox)).to_crs(25832)
    relevant = points["addr_housenumber"].notna() | points[["shop", "office", "amenity", "craft"]].notna().any(axis=1)
    points = points[relevant & points.intersects(region)]
    keep_points = ["osm_id", "name", *[key.replace(":", "_") for key in TAG_KEYS], "geometry"]
    points = points[[column for column in keep_points if column in points.columns]].reset_index(drop=True)
    points.to_parquet(out_dir / POINTS_FILE, index=False)

    manifest = {
        "source": pbf.name, "source_md5": hashlib.md5(pbf.read_bytes()).hexdigest(),
        "license": "ODbL 1.0, (c) OpenStreetMap contributors",
        "region": f"union of {plz_count} postal areas buffered by {buffer_m:g} m, EPSG:25832",
        "files": {BUILDINGS_FILE: int(len(buildings)), POINTS_FILE: int(len(points))},
        "building_types": {str(key): int(value) for key, value in buildings.building.value_counts().head(25).items()},
        "share_buildings_with_address": round(float(buildings.addr_housenumber.notna().mean()) if len(buildings) else 0., 4),
        "runtime_s": round(time.time() - started, 1),
    }
    (out_dir / MANIFEST_FILE).write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    return manifest
