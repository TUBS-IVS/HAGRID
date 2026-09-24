"""Demand locations on OSM buildings: persons, firms, addresses, DHL streets and street sections."""

from __future__ import annotations

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
    parsed = unique.str.extract(r"100mN(?P<y>\d+)E(?P<x>\d+)").astype("int64")
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
