"""Source adapters and deterministic location construction."""

import hashlib
import json
from pathlib import Path

import geopandas as gpd
import numpy as np
import pandas as pd
import shapely


def stable_id(prefix, value):
    return prefix + ":" + hashlib.sha256(str(value).encode("utf-8")).hexdigest()[:24]


def write_json(path, value):
    Path(path).write_text(json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n", encoding="utf-8")


def require_unique(frame, column, label):
    if frame[column].isna().any() or frame[column].duplicated().any():
        raise ValueError(f"{label}: missing or duplicate {column}; resolve source identities before proceeding")


def clean_text(series):
    return series.astype("string").str.strip().replace("", pd.NA)


def normalize_plz(series):
    s = clean_text(series).str.replace(r"\.0$", "", regex=True)
    return s.where(s.str.fullmatch(r"\d{1,5}", na=False)).str.zfill(5)


def valid_points(geometry):
    return (geometry.notna() & ~geometry.is_empty & geometry.is_valid
            & geometry.geom_type.eq("Point"))


def parse_wkt(values):
    # Shapely accepts None, but not pandas' nullable string sentinel.
    objects = values.astype(object).where(values.notna(), None)
    return shapely.from_wkt(objects, on_invalid="ignore")


def read_persons(path, crs, target_crs):
    frame = pd.read_csv(path, usecols=["id", "Building", "Household", "geometry"], dtype="string")
    for col in ["id", "Building", "Household"]:
        frame[col] = clean_text(frame[col])
    require_unique(frame, "id", "persons")
    geometry = parse_wkt(frame.pop("geometry"))
    return gpd.GeoDataFrame(frame, geometry=geometry, crs=crs).to_crs(target_crs)


def build_residential(persons, tolerance):
    """One demand unit per Building; missing IDs remain individual unresolved units.

    Use an existing representative point, never the centroid of conflicting locations.
    Conflicting locations remain in counts but are excluded from automatic linking.
    """
    frame = persons.copy()
    require_unique(frame, "id", "persons")
    frame["unit_key"] = "building:" + frame.Building
    missing = frame.Building.isna()
    frame.loc[missing, "unit_key"] = "unresolved-person:" + frame.loc[missing, "id"]
    good = valid_points(frame.geometry)
    frame["x"] = shapely.get_x(frame.geometry.where(good))
    frame["y"] = shapely.get_y(frame.geometry.where(good))
    grouped = frame.groupby("unit_key", sort=True, dropna=False)
    table = grouped.agg(population=("id", "size"), known_households=("Household", "nunique"),
                        missing_households=("Household", lambda s: int(s.isna().sum())),
                        xmin=("x", "min"), xmax=("x", "max"), ymin=("y", "min"), ymax=("y", "max"),
                        located_persons=("x", "count"))
    # Stable across source row order, and always a point actually present in the source.
    first = frame.loc[good].sort_values("id").drop_duplicates("unit_key").set_index("unit_key")
    table["geometry"] = first.geometry.reindex(table.index)
    table["spread_m"] = np.hypot(table.xmax - table.xmin, table.ymax - table.ymin)
    table["location_status"] = "source_point_unverified"
    table.loc[table.located_persons < table.population, "location_status"] = "incomplete_geometry"
    table.loc[table.spread_m > tolerance, "location_status"] = "conflicting_building_coordinates"
    table.loc[table.located_persons == 0, "location_status"] = "missing_geometry"
    table.loc[table.index.str.startswith("unresolved-person:"), "location_status"] = "missing_building_id"
    table["site_id"] = [stable_id("res", key) for key in table.index]
    table["recipient_type"] = "private"
    table["source_id"] = "H01"
    table["employees"] = np.nan
    table["branch"] = pd.NA
    table["source_key"] = table.index
    return gpd.GeoDataFrame(table.reset_index(drop=True), geometry="geometry", crs=persons.crs)


def build_business(path, target_crs):
    frame = gpd.read_file(path, columns=["id", "employees", "branch", "type"]).to_crs(target_crs)
    frame["id"] = clean_text(frame.id)
    require_unique(frame, "id", "companies")
    frame["site_id"] = frame.id.map(lambda value: stable_id("biz", value))
    frame["source_key"] = frame.id
    frame["source_id"] = "H02"
    frame["recipient_type"] = "business"
    frame["employees"] = pd.to_numeric(frame.employees, errors="coerce")
    frame["invalid_employees"] = frame.employees.isna() | (frame.employees < 0)
    frame["population"] = 0
    frame["known_households"] = 0
    frame["missing_households"] = 0
    frame["spread_m"] = 0.0
    frame["location_status"] = np.where(valid_points(frame.geometry), "source_point_unverified", "missing_or_invalid_geometry")
    return frame


def read_dhl(path, target_crs):
    frame = gpd.read_file(path).to_crs(target_crs)
    frame["source_row"] = np.arange(len(frame))
    frame["source_id"] = "H03"
    frame["observation_id"] = frame.source_row.map(lambda n: f"H03:row:{n}")
    frame["carrier"] = "DHL"
    frame["year"] = 2021
    frame["plz"] = normalize_plz(frame.plz)
    frame["street"] = clean_text(frame["name"])
    frame["value"] = pd.to_numeric(frame.tagesschni, errors="coerce")
    frame["value_status"] = np.select([frame.value.isna(), frame.value < 0, frame.value == 0],
                                       ["missing", "invalid_negative", "reported_zero"], default="reported_positive")
    frame["repeated_street_key"] = frame.duplicated(["plz", "street"], keep=False)
    frame["unit"] = "unconfirmed_daily_mean"
    frame["definition_confirmed"] = False
    frame["geometry_usable"] = (frame.geometry.notna() & ~frame.geometry.is_empty & frame.geometry.is_valid
                                 & frame.geom_type.isin(["LineString", "MultiLineString"]))
    return frame


def read_hermes(path):
    frame = pd.read_csv(path, sep=None, engine="python", encoding="utf-8-sig", dtype="string")
    frame.columns = frame.columns.str.strip().str.lstrip("\ufeff")
    frame["PLZ"] = normalize_plz(frame.PLZ)
    require_unique(frame, "PLZ", "Hermes postal rows")
    years = [c for c in frame if c.isdigit() and len(c) == 4]
    if not years:
        raise ValueError("Hermes: no year columns")
    result = frame.melt(id_vars="PLZ", value_vars=years, var_name="year", value_name="raw_value")
    result["plz"] = result.pop("PLZ")
    result["year"] = result.year.astype(int)
    result["value"] = pd.to_numeric(result.raw_value.str.replace(",", ".", regex=False), errors="coerce").astype(float)
    result["value_status"] = np.select([result.value.isna(), result.value < 0, result.value == 0],
                                        ["missing", "invalid_negative", "reported_zero"], default="reported_positive")
    result["observation_id"] = "H04:" + result.plz + ":" + result.year.astype(str)
    result["source_id"] = "H04"
    result["carrier"] = "Hermes"
    result["unit"] = "unconfirmed"
    result["definition_confirmed"] = False
    return result


def read_plz(path, crs, target_crs):
    frame = pd.read_csv(path, dtype="string")
    frame["plz"] = normalize_plz(frame.postal_cod)
    geometry = parse_wkt(frame.geometry)
    frame = gpd.GeoDataFrame(frame.drop(columns="geometry"), geometry=geometry, crs=crs).to_crs(target_crs)
    # Do not silently repair invalid polygons or discard unmappable areas.
    if frame.plz.isna().any() or frame.geometry.isna().any() or (~frame.is_valid).any() or frame.is_empty.any():
        raise ValueError("PLZ: missing identifier or invalid geometry; inspect source before linking")
    if not frame.geom_type.isin(["Polygon", "MultiPolygon"]).all():
        raise ValueError("PLZ: expected polygon geometry")
    return frame[["plz", "geometry"]].dissolve(by="plz").reset_index()
