"""Demand locations on OSM buildings: persons, firms, addresses, DHL streets and street sections."""

from __future__ import annotations

import re

import geopandas as gpd
import numpy as np
import pandas as pd
import shapely
from shapely.geometry import box

from hagrid_demand.common.rng import named_rng


EXCLUDED_TYPES = ("garage", "garages", "roof", "hut", "shed", "carport", "greenhouse", "barn", "farm_auxiliary", "parking",
                  "construction", "ruins", "bunker", "toilets", "transformer_tower")
RESIDENTIAL_TYPES = {"residential", "house", "apartments", "detached", "semidetached_house", "terrace", "dormitory", "bungalow"}
_BRANCH_GROUP = {"G": "retail", "I": "retail", "C": "industry", "F": "industry", "H": "industry",
                 **{code: "services" for code in "JKLMN"}, **{code: "public" for code in "OPQ"}}
_FIT_TYPES = {
    "retail": {"retail", "commercial", "supermarket", "kiosk", "hotel"},
    "industry": {"industrial", "warehouse", "manufacture", "hangar", "transportation"},
    "services": {"office", "commercial"},
    "public": {"public", "school", "university", "hospital", "civic", "government", "kindergarten"},
    "other": {"commercial", "office", "industrial"},
}
_RESIDENTIAL_FACTOR = {"retail": .5, "industry": .1, "services": .5, "public": .3, "other": .5}
_OTHER_FACTOR = .3


def load_buildings(frame: gpd.GeoDataFrame, exclude_types=EXCLUDED_TYPES) -> gpd.GeoDataFrame:
    """Relevant OSM buildings with a stable key (``osm:w<way>`` or ``osm:r<relation>``)."""
    way = frame["osm_way_id"] if "osm_way_id" in frame else pd.Series(None, index=frame.index, dtype=object)
    relation = frame["osm_id"] if "osm_id" in frame else pd.Series(None, index=frame.index, dtype=object)
    key = np.where(way.notna(), "osm:w" + way.astype(str), "osm:r" + relation.astype(str))
    empty = pd.Series(None, index=frame.index, dtype=object)
    poi_columns = [column for column in ("shop", "office", "amenity", "craft") if column in frame]
    result = gpd.GeoDataFrame({
        "building_key": key, "building_type": frame["building"].astype(str).to_numpy(),
        "addr_street": frame.get("addr_street", empty).to_numpy(),
        "addr_housenumber": frame.get("addr_housenumber", empty).to_numpy(),
        "poi": frame[poi_columns].notna().any(axis=1).to_numpy() if poi_columns else np.zeros(len(frame), dtype=bool),
    }, geometry=frame.geometry.to_numpy(), crs=frame.crs)
    result = result[~result.building_type.isin(exclude_types) & result.geometry.notna() & ~result.geometry.is_empty]
    result = result.drop_duplicates("building_key").reset_index(drop=True)
    result["area_m2"] = result.geometry.area
    return result


def zensus_cell(points: gpd.GeoSeries) -> pd.Series:
    """Zensus 100 m cell identifier (ETRS89-LAEA) of each point, e.g. ``100mN32510E43053``."""
    laea = points.to_crs(3035)
    x = np.floor(laea.x.to_numpy() / 100).astype("int64")
    y = np.floor(laea.y.to_numpy() / 100).astype("int64")
    return pd.Series([f"100mN{north}E{east}" for east, north in zip(x, y)], index=points.index)


def _cell_polygons(cells: pd.Series, crs) -> gpd.GeoDataFrame:
    unique = pd.Series(pd.unique(cells))
    parsed = unique.str.extract(r"100mN(?P<y>-?\d+)E(?P<x>-?\d+)").astype("int64")
    shapes = [box(x * 100, y * 100, x * 100 + 100, y * 100 + 100) for x, y in zip(parsed.x, parsed.y)]
    return gpd.GeoDataFrame({"cell": unique}, geometry=shapes, crs=3035).to_crs(crs)


def _nearest(points: gpd.GeoDataFrame, buildings: gpd.GeoDataFrame, max_distance_m: float) -> pd.Series:
    near = gpd.sjoin_nearest(points[["site_id", "geometry"]], buildings[["building_key", "geometry"]],
                             max_distance=max_distance_m, how="left", distance_col="distance_m")
    near = near.sort_values(["site_id", "distance_m", "building_key"]).drop_duplicates("site_id")
    return near.set_index("site_id").building_key


def assign_private(sites: gpd.GeoDataFrame, buildings: gpd.GeoDataFrame, max_distance_m: float = 65.) -> pd.DataFrame:
    """Person building points -> containing building, else nearest building, else the point itself."""
    private = sites.loc[sites.segment.eq("private"), ["site_id", "geometry"]]
    joined = gpd.sjoin(private, buildings[["building_key", "geometry"]], predicate="within", how="left")
    joined = joined.sort_values(["site_id", "building_key"]).drop_duplicates("site_id")
    result = pd.DataFrame({"site_id": joined.site_id.to_numpy(), "building_key": joined.building_key.to_numpy()})
    result["stage"] = np.where(result.building_key.notna(), "within", None)
    missing = result.building_key.isna()
    if missing.any():
        near = _nearest(private[private.site_id.isin(result.loc[missing, "site_id"])], buildings, max_distance_m)
        result.loc[missing, "building_key"] = result.loc[missing, "site_id"].map(near).to_numpy()
        result.loc[missing & result.building_key.notna(), "stage"] = "nearest"
    still = result.building_key.isna()
    result.loc[still, "building_key"] = "pt:" + result.loc[still, "site_id"]
    result.loc[still, "stage"] = "point"
    return result


def _fit(group: str, building_type: str, poi: bool, residents: float) -> float:
    if poi or building_type in _FIT_TYPES[group]:
        return 1.
    if building_type in RESIDENTIAL_TYPES or (building_type == "yes" and residents > 0):
        return _RESIDENTIAL_FACTOR[group]
    return _OTHER_FACTOR


def assign_firms(sites: gpd.GeoDataFrame, buildings: gpd.GeoDataFrame, residents: pd.Series, seed: int,
                 candidate_distance_m: float = 100., fallback_distance_m: float = 250.) -> pd.DataFrame:
    """Firm points -> a fitting building in their Zensus cell (area x branch fit), reproducible per firm."""
    firms = sites.loc[sites.segment.eq("business"), ["site_id", "branch", "geometry"]].reset_index(drop=True)
    firms["cell"] = zensus_cell(firms.geometry).to_numpy()
    firms["group"] = firms.branch.map(_BRANCH_GROUP).fillna("other")
    cells = _cell_polygons(firms.cell, buildings.crs)
    in_cell = gpd.sjoin(buildings[["building_key", "building_type", "poi", "area_m2", "geometry"]], cells, predicate="intersects")
    candidates = pd.DataFrame(in_cell.drop(columns=["geometry", "index_right"]).assign(bgeom=in_cell.geometry.to_numpy()))
    pairs = pd.DataFrame(firms[["site_id", "cell", "group"]].assign(fgeom=firms.geometry.to_numpy())).merge(candidates, on="cell")
    pairs["distance_m"] = shapely.distance(pairs.fgeom.to_numpy(), pairs.bgeom.to_numpy())
    pairs = pairs[pairs.distance_m <= candidate_distance_m].copy()
    pairs["residents"] = pairs.building_key.map(residents).fillna(0.)
    pairs["fit"] = [_fit(g, t, bool(p), r) for g, t, p, r in zip(pairs.group, pairs.building_type, pairs.poi, pairs.residents)]
    pairs["weight"] = pairs.area_m2 * pairs.fit
    chosen: dict[str, tuple[str, str, float]] = {}
    for site_id, group in pairs.sort_values(["site_id", "building_key"]).groupby("site_id", sort=True):
        weights = group.weight.to_numpy(float)
        if weights.sum() <= 0:
            continue
        index = named_rng(int(seed), firm=str(site_id), channel="firm-building").choice(len(group), p=weights / weights.sum())
        chosen[site_id] = (group.building_key.iloc[index], "cell", float(group.fit.iloc[index]))
    result = pd.DataFrame({"site_id": firms.site_id})
    result["building_key"] = result.site_id.map(lambda site: chosen[site][0] if site in chosen else None)
    result["stage"] = result.site_id.map(lambda site: chosen[site][1] if site in chosen else None)
    result["fit"] = result.site_id.map(lambda site: chosen[site][2] if site in chosen else np.nan)
    missing = result.building_key.isna()
    if missing.any():
        near = _nearest(firms[firms.site_id.isin(result.loc[missing, "site_id"])], buildings, fallback_distance_m)
        result.loc[missing, "building_key"] = result.loc[missing, "site_id"].map(near).to_numpy()
        result.loc[missing & result.building_key.notna(), "stage"] = "fallback"
    still = result.building_key.isna()
    result.loc[still, "building_key"] = "pt:" + result.loc[still, "site_id"]
    result.loc[still, "stage"] = "point"
    return result


def normalize_street(value) -> str | None:
    """Comparable street name: lower case, ss for sharp s, "str." -> "strasse", unified separators."""
    if value is None or (isinstance(value, float) and np.isnan(value)):
        return None
    text = str(value).strip().lower().replace("ß", "ss")
    if not text:
        return None
    text = re.sub(r"str\.(?=\s|$)", "strasse", text)
    text = re.sub(r"[\s\-]+", " ", text).strip()
    return text or None


def street_parts(streets: gpd.GeoDataFrame) -> gpd.GeoDataFrame:
    """One row per LineString part of each DHL street (``part`` follows the explode order)."""
    parts = streets[["sid", "geometry"]].explode(index_parts=True)
    parts = parts.reset_index(level=1).rename(columns={"level_1": "part"}).reset_index(drop=True)
    parts["part"] = parts.part.astype("int64")
    parts["length_m"] = parts.geometry.length
    return gpd.GeoDataFrame(parts, geometry="geometry", crs=streets.crs)


def match_streets(points: gpd.GeoDataFrame, streets: gpd.GeoDataFrame, max_distance_m: float = 100.,
                  name_max_distance_m: float = 500., extended_distance_m: float | None = None) -> pd.DataFrame:
    """Building -> DHL street: same normalised name and PLZ first, else the nearest street.

    ``extended_distance_m`` adds a last stage (``nearest_far``) for sites behind internal access roads
    (industrial estates, hospitals) whose parcels DHL records on the nearest public street.
    """
    frame = pd.DataFrame({"building_key": points.building_key.to_numpy(), "plz": points.plz.astype(str).to_numpy(),
                          "street_norm": points.street_norm.to_numpy(), "pgeom": points.geometry.to_numpy()})
    lines = pd.DataFrame({"sid": streets.sid.to_numpy(), "plz": streets.plz.astype(str).to_numpy(),
                          "street_norm": streets.street_norm.to_numpy(), "sgeom": streets.geometry.to_numpy()})
    candidates = frame.dropna(subset=["street_norm"]).merge(lines, on=["plz", "street_norm"])
    candidates["distance_m"] = shapely.distance(candidates.pgeom.to_numpy(), candidates.sgeom.to_numpy())
    named = (candidates[candidates.distance_m <= name_max_distance_m]
             .sort_values(["building_key", "distance_m", "sid"]).drop_duplicates("building_key").set_index("building_key"))
    result = frame[["building_key"]].copy()
    result["sid"] = result.building_key.map(named.sid)
    result["distance_m"] = result.building_key.map(named.distance_m)
    result["match_stage"] = np.where(result.sid.notna(), "name", None)
    missing = result.sid.isna()
    if missing.any():
        near = gpd.sjoin_nearest(points[points.building_key.isin(result.loc[missing, "building_key"])][["building_key", "geometry"]],
                                 streets[["sid", "geometry"]], max_distance=max_distance_m, how="left", distance_col="distance_m")
        near = near.sort_values(["building_key", "distance_m", "sid"]).drop_duplicates("building_key").set_index("building_key")
        result.loc[missing, "sid"] = result.loc[missing, "building_key"].map(near.sid).to_numpy()
        result.loc[missing, "distance_m"] = result.loc[missing, "building_key"].map(near.distance_m).to_numpy()
        result.loc[missing & result.sid.notna(), "match_stage"] = "nearest"
    far = result.sid.isna()
    if extended_distance_m is not None and extended_distance_m > max_distance_m and far.any():
        near = gpd.sjoin_nearest(points[points.building_key.isin(result.loc[far, "building_key"])][["building_key", "geometry"]],
                                 streets[["sid", "geometry"]], max_distance=extended_distance_m, how="left", distance_col="distance_m")
        near = near.sort_values(["building_key", "distance_m", "sid"]).drop_duplicates("building_key").set_index("building_key")
        result.loc[far, "sid"] = result.loc[far, "building_key"].map(near.sid).to_numpy()
        result.loc[far, "distance_m"] = result.loc[far, "building_key"].map(near.distance_m).to_numpy()
        result.loc[far & result.sid.notna(), "match_stage"] = "nearest_far"
    result.loc[result.sid.isna(), "match_stage"] = "none"
    result["sid"] = result.sid.fillna(-1).astype("int64")
    return result


def project_on_streets(points: gpd.GeoDataFrame, parts: gpd.GeoDataFrame, section_length_m: float = 50.) -> pd.DataFrame:
    """Project buildings onto the nearest part of their DHL street: position, section and street side."""
    frame = pd.DataFrame({"building_key": points.building_key.to_numpy(), "sid": points.sid.to_numpy(),
                          "pgeom": points.geometry.to_numpy()})
    pairs = frame.merge(pd.DataFrame({"sid": parts.sid.to_numpy(), "part": parts.part.to_numpy(),
                                      "length_m": parts.length_m.to_numpy(), "lgeom": parts.geometry.to_numpy()}), on="sid")
    pairs["distance"] = shapely.distance(pairs.pgeom.to_numpy(), pairs.lgeom.to_numpy())
    best = pairs.sort_values(["building_key", "distance", "part"]).drop_duplicates("building_key").reset_index(drop=True)
    lines, pts = best.lgeom.to_numpy(), best.pgeom.to_numpy()
    position = shapely.line_locate_point(lines, pts)
    axis = shapely.line_interpolate_point(lines, position)
    before = shapely.line_interpolate_point(lines, np.maximum(position - 1., 0.))
    after = shapely.line_interpolate_point(lines, np.minimum(position + 1., best.length_m.to_numpy()))
    tx, ty = shapely.get_x(after) - shapely.get_x(before), shapely.get_y(after) - shapely.get_y(before)
    vx, vy = shapely.get_x(pts) - shapely.get_x(axis), shapely.get_y(pts) - shapely.get_y(axis)
    cross = tx * vy - ty * vx
    section = np.floor(position / section_length_m).astype("int64")
    return pd.DataFrame({
        "building_key": best.building_key, "part": best.part.astype("int64"), "position_m": position,
        "side": np.where(cross > 0, "left", "right"),
        "section_id": [f"{s}-{p}-{k}" for s, p, k in zip(best.sid, best.part, section)],
        "axis_x": shapely.get_x(axis), "axis_y": shapely.get_y(axis),
    })


_COLUMNS = ["building_key", "footprint", "building_type", "area_m2", "plz", "population", "companies", "employees",
            "street_norm", "sid", "match_stage", "distance_m", "part", "position_m", "side", "section_id", "axis_x", "axis_y",
            "geometry"]


def _with_point_attributes(buildings: gpd.GeoDataFrame, points: gpd.GeoDataFrame) -> gpd.GeoDataFrame:
    """Mark POIs inside buildings and fill missing addresses from address points inside the footprint."""
    buildings = buildings.copy()
    if not len(points):
        return buildings
    poi_columns = [column for column in ("shop", "office", "amenity", "craft") if column in points]
    if poi_columns:
        poi_points = points[points[poi_columns].notna().any(axis=1)]
        hit = gpd.sjoin(poi_points[["geometry"]], buildings[["building_key", "geometry"]], predicate="within")
        buildings.loc[buildings.building_key.isin(hit.building_key), "poi"] = True
    if {"addr_housenumber", "addr_street"}.issubset(points.columns):
        address = points[points.addr_housenumber.notna() & points.addr_street.notna()]
        inside = gpd.sjoin(address[["addr_street", "geometry"]], buildings[["building_key", "geometry"]], predicate="within")
        inside = inside.sort_index().drop_duplicates("building_key").set_index("building_key")
        fill = buildings.addr_street.isna()
        buildings.loc[fill, "addr_street"] = buildings.loc[fill, "building_key"].map(inside.addr_street)
    return buildings


def build_buildings(sites: gpd.GeoDataFrame, osm_buildings: gpd.GeoDataFrame, osm_points: gpd.GeoDataFrame,
                    streets: gpd.GeoDataFrame, postal: gpd.GeoDataFrame, cfg: dict | None, seed: int):
    """Demand buildings with persons, firms, PLZ, DHL street, section and side (spec 5.1-5.4, 5.11)."""
    cfg = cfg or {}
    buildings = load_buildings(osm_buildings, tuple(cfg.get("exclude_types", EXCLUDED_TYPES)))
    buildings = _with_point_attributes(buildings, osm_points)
    private = assign_private(sites, buildings, float(cfg.get("residential_max_distance_m", 65.)))
    by_site = sites.set_index("site_id")
    residents = private.assign(persons=private.site_id.map(by_site.population)).groupby("building_key").persons.sum()
    firms = assign_firms(sites, buildings, residents, seed, float(cfg.get("firm_candidate_distance_m", 100.)),
                         float(cfg.get("firm_fallback_distance_m", 250.)))
    mapping = pd.concat([private.assign(segment="private"), firms.drop(columns="fit").assign(segment="business")], ignore_index=True)
    mapping["population"] = mapping.site_id.map(by_site.population).fillna(0.).astype(float)
    mapping["employees"] = mapping.site_id.map(by_site.employees).fillna(0.).astype(float)
    mapping["is_firm"] = mapping.segment.eq("business").astype(int)
    demand = mapping.groupby("building_key").agg(population=("population", "sum"), companies=("is_firm", "sum"),
                                                  employees=("employees", "sum"))
    located = buildings[buildings.building_key.isin(demand.index)].copy()
    located["footprint"] = True
    points = mapping[mapping.building_key.str.startswith("pt:")].drop_duplicates("building_key")
    point_rows = gpd.GeoDataFrame({"building_key": points.building_key.to_numpy(), "building_type": "point",
                                   "addr_street": None, "addr_housenumber": None, "poi": False, "area_m2": 0., "footprint": False},
                                  geometry=list(points.site_id.map(by_site.geometry)), crs=sites.crs)
    table = gpd.GeoDataFrame(pd.concat([located, point_rows], ignore_index=True), geometry="geometry", crs=sites.crs)
    table = table.join(demand, on="building_key")
    table["geometry"] = table.geometry.representative_point()
    with_plz = gpd.sjoin(table[["building_key", "geometry"]], postal[["plz", "geometry"]], predicate="within", how="left")
    table["plz"] = table.building_key.map(with_plz.drop_duplicates("building_key").set_index("building_key").plz)
    outside = table.plz.isna()
    if outside.any():
        # Units outside every postal polygon take the nearest postal area; a missing PLZ would reach MATSim as "<NA>".
        nearest = gpd.sjoin_nearest(table.loc[outside, ["building_key", "geometry"]], postal[["plz", "geometry"]], how="left")
        nearest = nearest.sort_values(["building_key", "plz"]).drop_duplicates("building_key").set_index("building_key").plz
        table.loc[outside, "plz"] = table.loc[outside, "building_key"].map(nearest).to_numpy()
    table["plz"] = table.plz.astype(str)
    table["street_norm"] = table.addr_street.map(normalize_street)
    lines = streets.assign(street_norm=streets.street.map(normalize_street))
    matched = match_streets(table[["building_key", "plz", "street_norm", "geometry"]], lines,
                            float(cfg.get("match_distance_m", 100.)), float(cfg.get("name_max_distance_m", 500.)),
                            float(cfg.get("extended_match_distance_m", 250.)))
    table = table.merge(matched, on="building_key", how="left")
    projected = project_on_streets(table.loc[table.sid >= 0, ["building_key", "sid", "geometry"]], street_parts(lines),
                                   float(cfg.get("section_length_m", 50.)))
    table = gpd.GeoDataFrame(table.merge(projected, on="building_key", how="left"), geometry="geometry", crs=sites.crs)
    total_persons = float(sites.loc[sites.segment.eq("private"), "population"].sum())
    in_buildings = mapping.segment.eq("private") & ~mapping.building_key.str.startswith("pt:")
    report = {
        "buildings": int(len(table)), "footprint_share": float(table.footprint.mean()) if len(table) else 0.,
        "persons_in_buildings_share": float(mapping.loc[in_buildings, "population"].sum() / total_persons) if total_persons else 0.,
        "private_stages": {str(k): int(v) for k, v in private.stage.value_counts().items()},
        "firm_stages": {str(k): int(v) for k, v in firms.stage.value_counts().items()},
        "street_match_stages": {str(k): int(v) for k, v in table.match_stage.value_counts().items()},
        "plz_from_nearest_postal": sorted(table.loc[outside.to_numpy(), "building_key"].astype(str).tolist()),
    }
    return table[_COLUMNS], mapping[["site_id", "segment", "building_key", "stage"]], report
