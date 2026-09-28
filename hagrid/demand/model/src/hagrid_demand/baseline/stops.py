"""Adaptive delivery stops (spec 5.12): group buildings per street side within a walking span."""

from __future__ import annotations

import hashlib

import geopandas as gpd
import numpy as np
import pandas as pd

from .buildings import street_parts


def _stop_id(*parts) -> str:
    return "stp:" + hashlib.sha1("|".join(str(part) for part in parts).encode("utf-8")).hexdigest()[:16]


def build_stops(units: gpd.GeoDataFrame, expected_daily: pd.Series, streets: gpd.GeoDataFrame,
                walking_radius_m: float = 40., own_stop_parcels_per_day: float = 15.,
                section_length_m: float = 50.) -> tuple[gpd.GeoDataFrame, pd.DataFrame]:
    """Stable stops per (street, part, side); a stop spans at most 2 x walking radius along the street."""
    frame = units.copy()
    frame["expected_daily"] = frame.building_key.map(expected_daily).fillna(0.).to_numpy(float)
    parts = {(int(row.sid), int(row.part)): row.geometry for row in street_parts(streets).itertuples()}
    line_of = streets.set_index("sid").geometry
    stops, mapping = [], []

    def emit(members: pd.DataFrame, sid: int, part, side: str) -> None:
        weights = members.expected_daily.to_numpy(float)
        weights = weights if weights.sum() > 0 else np.ones(len(members))
        position = float(np.average(members.position_m.to_numpy(float), weights=weights))
        line = parts.get((sid, part)) if part is not None else line_of[sid]
        stop_id = _stop_id(sid, part, side, members.building_key.iloc[0])
        plz = members.groupby("plz").expected_daily.sum().idxmax()
        stops.append({"stop_id": stop_id, "str_idx": sid, "part": part, "side": side,
                      "section_id": f"{sid}-{part if part is not None else 's'}-{int(position // section_length_m)}",
                      "plz": str(plz), "n_units": int(len(members)), "expected_daily": float(members.expected_daily.sum()),
                      "geometry": line.interpolate(position)})
        mapping.extend({"building_key": key, "stop_id": stop_id} for key in members.building_key)

    on_street = frame[frame.sid >= 0].copy()
    on_street["part_key"] = pd.to_numeric(on_street.part, errors="coerce").fillna(-1).astype("int64")
    on_street = on_street.sort_values(["sid", "part_key", "side", "position_m", "building_key"], kind="stable")
    for (sid, part_key, side), group in on_street.groupby(["sid", "part_key", "side"], sort=True):
        part = None if part_key < 0 else int(part_key)
        cluster, start = [], None
        for index, row in group.iterrows():
            if row.expected_daily >= own_stop_parcels_per_day:
                emit(group.loc[[index]], int(sid), part, side)
                continue
            if cluster and row.position_m - start > 2 * walking_radius_m:
                emit(group.loc[cluster], int(sid), part, side)
                cluster = []
            if not cluster:
                start = row.position_m
            cluster.append(index)
        if cluster:
            emit(group.loc[cluster], int(sid), part, side)
    for row in frame[frame.sid < 0].sort_values("building_key").itertuples():
        stop_id = _stop_id("off", row.building_key)
        stops.append({"stop_id": stop_id, "str_idx": -1, "part": None, "side": None, "section_id": None, "plz": str(row.plz),
                      "n_units": 1, "expected_daily": float(row.expected_daily), "geometry": row.geometry})
        mapping.append({"building_key": row.building_key, "stop_id": stop_id})
    result = gpd.GeoDataFrame(stops, geometry="geometry", crs=units.crs).sort_values("stop_id").reset_index(drop=True)
    result.insert(1, "stop_index", np.arange(len(result), dtype="int64"))
    return result, pd.DataFrame(mapping)
