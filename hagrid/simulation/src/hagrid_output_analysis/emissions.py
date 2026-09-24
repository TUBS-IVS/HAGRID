"""
HAGRID Output Analysis – Emissions Model
==========================================

Drive, idle, and cold-start emissions for each vehicle, disaggregated
into a 15-minute × link-id grid.

**Improvements over the notebook version**

* ``get_ambient_temp()`` / ``get_season_mult()`` called at calculation
  time, not stale at import time.
* ``calc_wtt_from_energy_g_per_km`` exposed as a standalone function.
* Low-utilisation vehicle filter integrated.
* Area-type emission breakdowns (``network_15min_by_area``,
  ``network_15min_pivot``) built automatically.
* The god-loop is split into ``_drive_emissions``,
  ``_idle_cold_emissions``, and ``run_emissions_loop``.
"""

from __future__ import annotations

from collections import Counter, defaultdict
from typing import Any

import numpy as np
import pandas as pd
from tqdm import tqdm

from hagrid_output_analysis.config import (
    BASE_IDLE_CO_GPS,
    BASE_IDLE_HC_GPS,
    BASE_IDLE_NOX_GPS,
    DEFAULT_PARAMS,
    EMISSION_CH4_gpkm,
    EMISSION_CO2_gpkm,
    EMISSION_N2O_gpkm,
    ENERGY_MJ_PER_KM,
    EV_CLASSES,
    GWP,
    IDLE_MULT_BY_CLASS,
    LOW_UTIL_THRESHOLD,
    SUPPLY_TRUCK_CLASSES,
    VEH_CLASS_PARAMS,
    WTT_CO2E_G_PER_MJ,
    RunConfig,
    get_ambient_temp,
    get_season_mult,
)


# ====================================================================
# Time intervals used by the 15-minute grid
# ====================================================================

TIME_INTERVALS: list[str] = [
    f"{h:02}:{m:02}" for h in range(24) for m in (0, 15, 30, 45)
]


# ====================================================================
# Factor interpolation helpers
# ====================================================================

def _interp(lo: float, hi: float, load_pct: float) -> float:
    lp = max(0.0, min(100.0, float(load_pct)))
    return lo + (hi - lo) * (lp / 100.0)


def _get_factor(
    table: dict, vehicle_type: str, segment: str, load_pct: float,
) -> float | None:
    segs = table.get(vehicle_type)
    if segs is None:
        return None
    pair = segs.get(segment)
    if pair is None:
        return None
    return _interp(pair[0], pair[1], load_pct)


def _species_to_co2e(co2_g: float, ch4_g: float, n2o_g: float) -> float:
    return co2_g * GWP["CO2"] + ch4_g * GWP["CH4"] + n2o_g * GWP["N2O"]


# ====================================================================
# Public emission factor functions
# ====================================================================

def ttw_gpkm(
    vehicle_type: str, segment: str, load_pct: float,
    basis: str = "CO2",
) -> float | None:
    """TTW grams per km for drive phase."""
    co2 = _get_factor(EMISSION_CO2_gpkm, vehicle_type, segment, load_pct)
    if co2 is None:
        return None
    if basis == "CO2":
        return co2
    ch4 = _get_factor(EMISSION_CH4_gpkm, vehicle_type, segment, load_pct) or 0.0
    n2o = _get_factor(EMISSION_N2O_gpkm, vehicle_type, segment, load_pct) or 0.0
    return _species_to_co2e(co2, ch4, n2o)


def calc_wtt_from_energy_g_per_km(
    vehicle_type: str, segment: str, load_pct: float,
) -> float | None:
    """WTT grams CO2e per km for vans (if energy data available)."""
    mj = _get_factor(ENERGY_MJ_PER_KM, vehicle_type, segment, load_pct)
    if mj is None:
        return None
    return mj * WTT_CO2E_G_PER_MJ


def calc_drive_emissions(
    distance_m: float,
    vehicle_type: str,
    segment: str,
    load_pct: float,
    cfg: RunConfig | None = None,
) -> float:
    """Distance-based drive emissions [grams]."""
    cfg = cfg or RunConfig()
    if cfg.without_supply_trucks and vehicle_type in SUPPLY_TRUCK_CLASSES:
        return 0.0
    gpkm = ttw_gpkm(vehicle_type, segment, load_pct, basis=cfg.emissions_basis)
    if gpkm is None:
        return 0.0
    total = gpkm
    if cfg.emissions_basis == "CO2e" and cfg.use_wtw:
        wtt = calc_wtt_from_energy_g_per_km(vehicle_type, segment, load_pct)
        if wtt is not None:
            total += wtt
    return (float(distance_m) / 1000.0) * total


# ====================================================================
# Engine-on share & idle emissions
# ====================================================================

def engine_on_share(
    dwell_sec: float,
    vehicle_type: str,
    ambient_temp_c: float,
    curbside: bool = True,
    t_long_override: int | None = None,
) -> float:
    """Fraction of dwell time with engine running."""
    p = VEH_CLASS_PARAMS.get(vehicle_type, DEFAULT_PARAMS)
    s = max(0.0, float(dwell_sec))
    if t_long_override == 0:
        return 0.0

    t_short = p["t_short"]
    t_mid = p["t_mid"]
    t_long = p["t_long"] if t_long_override is None else t_long_override

    if s <= t_short:
        share = 0.10
    elif s <= t_mid:
        share = 0.10 + (0.01 - 0.10) * ((s - t_short) / max(1.0, t_mid - t_short))
    elif s <= t_long:
        share = 0.01 + (0.00 - 0.01) * ((s - t_mid) / max(1.0, t_long - t_mid))
    else:
        share = 0.03

    share *= p.get("curbside_mult", 1.0) if curbside else 0.85

    if ambient_temp_c <= 0:
        share *= 1.10
    elif ambient_temp_c >= 25:
        share *= 1.03

    return max(0.01, min(1.0, share))


def idle_pollutant_temp_mult(temp_c: float) -> float:
    if temp_c <= 0:
        return 1.15
    if temp_c >= 25:
        return 1.05
    return 1.0


def idle_emissions(
    dwell_sec: float,
    vehicle_type: str,
    ambient_temp_c: float | None = None,
    curbside: bool = True,
    is_ev: bool = False,
    cfg: RunConfig | None = None,
) -> float:
    """TTW idle climate-gas emissions [grams]."""
    if is_ev:
        return 0.0
    cfg = cfg or RunConfig()
    if cfg.without_supply_trucks and vehicle_type in SUPPLY_TRUCK_CLASSES:
        return 0.0
    if ambient_temp_c is None:
        ambient_temp_c = get_ambient_temp()

    p = VEH_CLASS_PARAMS.get(vehicle_type, DEFAULT_PARAMS)
    share = engine_on_share(
        dwell_sec, vehicle_type, ambient_temp_c, curbside, cfg.t_long_override,
    )
    g_per_sec = p["idle_g_per_sec_ttw"] * share * get_season_mult()
    return max(0.0, dwell_sec) * g_per_sec


# ====================================================================
# Cold-start emissions
# ====================================================================

def effective_cold_dwell_threshold(vehicle_type: str, ambient_temp_c: float) -> float:
    base = VEH_CLASS_PARAMS.get(vehicle_type, DEFAULT_PARAMS).get("min_cold_dwell", 300)
    if ambient_temp_c < 0:
        return base * 0.5
    if ambient_temp_c > 20:
        return base * 1.5
    return float(base)


def motor_was_off(
    dwell_sec: float,
    vehicle_type: str,
    ambient_temp_c: float,
    curbside: bool = True,
    is_ev: bool = False,
    cfg: RunConfig | None = None,
) -> bool:
    if is_ev:
        return False
    cfg = cfg or RunConfig()
    if cfg.without_supply_trucks and vehicle_type in SUPPLY_TRUCK_CLASSES:
        return False
    p = VEH_CLASS_PARAMS.get(vehicle_type, DEFAULT_PARAMS)
    share = engine_on_share(dwell_sec, vehicle_type, ambient_temp_c, curbside, cfg.t_long_override)
    t_long = p["t_long"] if cfg.t_long_override is None else cfg.t_long_override
    return share < p.get("off_threshold", 0.30) and float(dwell_sec) >= float(t_long)


def cold_start_emissions(
    vehicle_type: str,
    ambient_temp_c: float,
) -> float:
    p = VEH_CLASS_PARAMS.get(vehicle_type, DEFAULT_PARAMS)
    base = float(p.get("cold_start_base_co2_g", 0.0))
    if ambient_temp_c <= 0:
        return base * 1.10
    if ambient_temp_c >= 25:
        return base * 0.98
    return base


# ====================================================================
# Emissions grid helpers
# ====================================================================

_BUCKET_KEYS = ("drive", "idle", "cold", "total", "NOx_idle", "HC_idle", "CO_idle")


def _empty_bucket() -> dict[str, float]:
    return {k: 0.0 for k in _BUCKET_KEYS}


def init_emissions_grid(
    size_keys: list[str],
    link_ids: Any,
    time_intervals: list[str] | None = None,
) -> dict:
    """Pre-allocate the 3D emissions grid."""
    ti = time_intervals or TIME_INTERVALS
    return {
        size: {
            lid: {ts: _empty_bucket() for ts in ti}
            for lid in link_ids
        }
        for size in size_keys
    }


def add_emission(
    grid: dict, veh_size: str, link_id: str,
    interval: str, kind: str, grams: float,
) -> None:
    """Robustly add grams to the grid cell."""
    if veh_size not in grid:
        grid[veh_size] = {}
    if link_id not in grid[veh_size]:
        grid[veh_size][link_id] = {ts: _empty_bucket() for ts in TIME_INTERVALS}
    if interval not in grid[veh_size][link_id]:
        grid[veh_size][link_id][interval] = _empty_bucket()
    cell = grid[veh_size][link_id][interval]
    cell[kind] += float(grams)
    cell["total"] += float(grams)


# ====================================================================
# Timestamp helpers for grid placement
# ====================================================================

def _seconds_to_interval(seconds: float) -> str:
    """Convert simulation seconds → HH:MM (15-min floor)."""
    ts = pd.to_timedelta(seconds, unit="s")
    total_min = int(ts.total_seconds() // 60)
    h, m = divmod(total_min, 60)
    return f"{h:02}:{(m // 15) * 15:02}"


def _first_link_after(
    tour: list[tuple[str, float]], post_ts: Any,
) -> tuple[str | None, str | None]:
    if post_ts is None:
        return None, None
    target = pd.to_datetime(post_ts).timestamp()
    for lid, ltime in tour:
        if float(ltime) >= target:
            return lid, _seconds_to_interval(ltime)
    return None, None


def _find_next_positive_load(
    veh_id: str, link_ts: Any, vehicle_status_df: pd.DataFrame,
) -> float:
    current = pd.Timestamp(link_ts) + pd.Timedelta(minutes=1)
    while current.strftime("%H:%M") in vehicle_status_df.columns:
        val = vehicle_status_df.loc[veh_id, current.strftime("%H:%M")]
        if val != -1:
            return max(0.0, float(val))
        current += pd.Timedelta(minutes=1)
    return 0.0


# ====================================================================
# Distribute idle grams into 15-min bins
# ====================================================================

def _distribute_idle(
    start_ts: Any, dwell_sec: float, link_id: str,
    veh_size: str, grid: dict, g_per_sec: float,
    pol_rates: dict[str, float] | None = None,
    idle_pollutants_on: bool = True,
) -> None:
    if start_ts is None or dwell_sec <= 0:
        return
    t = pd.to_datetime(start_ts).floor("min")
    remaining = float(dwell_sec)
    while remaining > 0:
        step = min(60.0, remaining)
        h, m = t.hour, t.minute
        interval = f"{h:02}:{(m // 15) * 15:02}"
        add_emission(grid, veh_size, link_id, interval, "idle", g_per_sec * step)
        if pol_rates and idle_pollutants_on:
            cell = grid.setdefault(veh_size, {}).setdefault(
                link_id, {ts: _empty_bucket() for ts in TIME_INTERVALS}
            ).setdefault(interval, _empty_bucket())
            cell["NOx_idle"] += pol_rates["nox"] * step
            cell["HC_idle"] += pol_rates["hc"] * step
            cell["CO_idle"] += pol_rates["co"] * step
        t += pd.to_timedelta(60, unit="s")
        remaining -= step


# ====================================================================
# Low-utilisation filter
# ====================================================================

def filter_low_utilisation(
    result: pd.DataFrame,
    vehicle_tour: dict,
    network_volumes: pd.DataFrame,
    threshold: float = LOW_UTIL_THRESHOLD,
) -> tuple[pd.DataFrame, dict, pd.DataFrame, set[str]]:
    """Remove low-utilisation vehicles from result and adjust network volumes.

    Returns (result_filtered, vehicle_tour_filtered, nv_adjusted, low_util_ids).
    """
    util_col = None
    for col in ("vehicle_load_factor", "Vehicle Utilization (%)"):
        if col in result.columns:
            util_col = col
            break
    if util_col is None:
        return result, vehicle_tour, network_volumes, set()

    col_max = pd.to_numeric(result[util_col], errors="coerce").max()
    thr = 100 * threshold if (col_max is not None and col_max > 1.5) else threshold

    low_mask = pd.to_numeric(result[util_col], errors="coerce") < thr
    low_ids = set(result.loc[low_mask, "vehicle_id"].dropna().astype(str).unique())
    keep_ids = set(result.loc[~low_mask, "vehicle_id"].dropna().astype(str).unique())

    result_f = result.loc[~low_mask].copy()
    tour_f = {v: t for v, t in vehicle_tour.items() if str(v) in keep_ids}

    # Adjust network volumes
    nv = network_volumes.copy()
    link_col = "link_id" if "link_id" in nv.columns else None
    count_col = next((c for c in ("van_count", "veh_count", "count") if c in nv.columns), None)

    if link_col and count_col:
        dec: Counter = Counter()
        for vid in low_ids:
            seq = vehicle_tour.get(vid, [])
            for item in seq:
                lid = str(item[0]) if isinstance(item, tuple) else str(item)
                for part in lid.split("-"):
                    p = part.strip()
                    if p:
                        dec[p] += 1
        if dec:
            dec_series = nv[link_col].astype(str).str.strip().map(pd.Series(dec)).fillna(0).astype(int)
            nv[count_col] = (pd.to_numeric(nv[count_col], errors="coerce").fillna(0) - dec_series).clip(lower=0).astype(int)

    print(f"    ✓  Removed {len(low_ids)} low-util vehicles (threshold {threshold:.0%})")
    return result_f, tour_f, nv, low_ids


# ====================================================================
# Main emissions loop
# ====================================================================

def run_emissions_loop(
    vehicle_tour: dict[str, list[tuple[str, float]]],
    result: pd.DataFrame,
    vehicle_status_df: pd.DataFrame,
    effective_class_map: dict[str, str],
    base_class_map: dict[str, str],
    is_ev_map: dict[str, int],
    link_length: dict[str, float],
    link_type: dict[str, str],
    link_ids: Any,
    cfg: RunConfig | None = None,
) -> dict[str, Any]:
    """Run the full emissions loop over all vehicles.

    Returns a dict with all emission outputs ready for persistence.
    """
    cfg = cfg or RunConfig()
    ambient = get_ambient_temp()
    season = get_season_mult()

    size_keys = list(set(base_class_map.values()))
    grid = init_emissions_grid(size_keys, link_ids)
    emissions_by_size: dict[str, float] = {k: 0.0 for k in size_keys}

    veh_em_drive: dict[str, float] = {}
    veh_em_idle: dict[str, float] = {}
    veh_em_cold: dict[str, float] = {}
    veh_em_total: dict[str, float] = {}
    veh_nox: dict[str, float] = {}
    veh_hc: dict[str, float] = {}
    veh_co: dict[str, float] = {}

    n_total = len(vehicle_tour)

    for i, (vid, tour) in enumerate(vehicle_tour.items()):
        entry = result[result["vehicle_id"] == vid]
        if entry.empty:
            continue

        is_ev = bool(is_ev_map.get(vid, 0))
        calc_cls = effective_class_map.get(vid)
        bucket_cls = base_class_map.get(vid)
        if calc_cls is None or bucket_cls is None:
            continue

        if cfg.without_supply_trucks and bucket_cls in SUPPLY_TRUCK_CLASSES:
            continue

        drive_g = _drive_phase(
            tour, vid, calc_cls, bucket_cls, grid, link_length, link_type,
            vehicle_status_df, cfg,
        )
        idle_g, cold_g, nox_g, hc_g, co_g = _idle_cold_phase(
            entry, tour, vid, calc_cls, bucket_cls, grid,
            link_type, ambient, season, is_ev, cfg,
        )

        total_g = drive_g + idle_g + cold_g
        veh_em_drive[vid] = drive_g
        veh_em_idle[vid] = idle_g
        veh_em_cold[vid] = cold_g
        veh_em_total[vid] = total_g
        veh_nox[vid] = nox_g
        veh_hc[vid] = hc_g
        veh_co[vid] = co_g
        emissions_by_size[bucket_cls] = emissions_by_size.get(bucket_cls, 0.0) + total_g

        if (i + 1) % 200 == 0 or i == n_total - 1:
            pct = (i + 1) / n_total * 100
            bar_len = 30
            filled = int(bar_len * (i + 1) / n_total)
            bar = "█" * filled + "░" * (bar_len - filled)
            print(f"\r         {bar} {pct:5.1f}%  ({i + 1:,}/{n_total:,})", end="", flush=True)
    print()  # newline after progress bar

    return {
        "emissions_grid": grid,
        "emissions_by_size": emissions_by_size,
        "vehicle_emissions_drive": veh_em_drive,
        "vehicle_emissions_idle": veh_em_idle,
        "vehicle_emissions_cold": veh_em_cold,
        "vehicle_emissions_total": veh_em_total,
        "veh_idle_NOx": veh_nox,
        "veh_idle_HC": veh_hc,
        "veh_idle_CO": veh_co,
    }


def _drive_phase(
    tour, vid, calc_cls, bucket_cls, grid,
    link_length, link_type, vehicle_status_df, cfg,
) -> float:
    """Compute drive emissions for one vehicle."""
    total = 0.0
    for lid, ltime in tour:
        length = link_length.get(lid)
        ltype = link_type.get(lid)
        if length is None or ltype is None:
            continue
        interval = _seconds_to_interval(ltime)
        link_ts = pd.to_timedelta(float(ltime), unit="s") + pd.Timestamp("00:00:00")
        load = _find_next_positive_load(vid, link_ts, vehicle_status_df)
        g = calc_drive_emissions(length, calc_cls, ltype, load, cfg)
        total += g
        add_emission(grid, bucket_cls, lid, interval, "drive", g)
    return total


def _idle_cold_phase(
    entry, tour, vid, calc_cls, bucket_cls, grid,
    link_type, ambient, season, is_ev, cfg,
) -> tuple[float, float, float, float, float]:
    """Compute idle and cold-start emissions for one vehicle."""
    idle_total = 0.0
    cold_total = 0.0
    nox_sum = hc_sum = co_sum = 0.0

    try:
        services = entry["services"].iloc[0]
    except Exception:
        services = []

    if not services:
        return 0.0, 0.0, 0.0, 0.0, 0.0

    for svc in services:
        dwell = float(svc.duration) if svc.duration else 0.0
        if dwell <= 0:
            continue

        stop_link = svc.link
        stop_type = link_type.get(stop_link, "urban")
        curbside = stop_type == "urban"

        # Idle
        ig = idle_emissions(dwell, calc_cls, ambient, curbside, is_ev, cfg)
        idle_total += ig

        start_ts = svc.get_attr("start_ts")
        if start_ts is not None:
            start_ts = pd.to_datetime(start_ts)
            if calc_cls in EV_CLASSES:
                gps = 0.0
                pol = None
            else:
                p = VEH_CLASS_PARAMS.get(bucket_cls, DEFAULT_PARAMS)
                share = engine_on_share(dwell, bucket_cls, ambient, curbside, cfg.t_long_override)
                gps = p["idle_g_per_sec_ttw"] * share * season
                pol = None
                if cfg.idle_pollutants_on:
                    mult = IDLE_MULT_BY_CLASS.get(bucket_cls, 1.0)
                    tmult = idle_pollutant_temp_mult(ambient)
                    pol = {
                        "nox": BASE_IDLE_NOX_GPS * mult * tmult * share,
                        "hc": BASE_IDLE_HC_GPS * mult * tmult * share,
                        "co": BASE_IDLE_CO_GPS * mult * tmult * share,
                    }
                    nox_sum += pol["nox"] * dwell
                    hc_sum += pol["hc"] * dwell
                    co_sum += pol["co"] * dwell

            _distribute_idle(start_ts, dwell, stop_link, bucket_cls, grid, gps, pol, cfg.idle_pollutants_on)

        # Cold start
        off = motor_was_off(dwell, bucket_cls, ambient, curbside, is_ev, cfg)
        svc.set_attr("motor_off", off)
        dwell_thr = effective_cold_dwell_threshold(bucket_cls, ambient)

        if calc_cls not in EV_CLASSES and off and dwell >= dwell_thr:
            post = svc.get_attr("post_start_ts")
            link_cold, int_cold = _first_link_after(tour, post)
            if link_cold is None:
                end_ts = svc.get_attr("end_ts") or (
                    (start_ts or pd.Timestamp("00:00:00")) + pd.to_timedelta(dwell, unit="s")
                )
                int_cold = _seconds_to_interval(pd.to_datetime(end_ts).timestamp() if isinstance(end_ts, str) else float(end_ts.timestamp()))
                link_cold = stop_link

            ce = cold_start_emissions(calc_cls, ambient)
            add_emission(grid, bucket_cls, link_cold, int_cold, "cold", ce)
            cold_total += ce

    return idle_total, cold_total, nox_sum, hc_sum, co_sum


# ====================================================================
# Post-processing: build wide / long DataFrames from grid
# ====================================================================

def build_wide(
    grid: dict, link_ids: Any,
    kind: str = "total",
    time_intervals: list[str] | None = None,
) -> pd.DataFrame:
    """Aggregate grid into a wide DataFrame (link_id × intervals)."""
    ti = time_intervals or TIME_INTERVALS
    wide = pd.DataFrame(0.0, index=link_ids, columns=ti)
    for _size, links in (grid or {}).items():
        tmp: dict[str, dict[str, float]] = {}
        for lid, intervals in (links or {}).items():
            row = {ts: float((p or {}).get(kind, 0.0)) for ts, p in (intervals or {}).items()}
            if row:
                tmp[lid] = row
        if not tmp:
            continue
        df_s = pd.DataFrame.from_dict(tmp, orient="index").fillna(0.0)
        df_s = df_s.reindex(columns=ti, fill_value=0.0).reindex(index=link_ids, fill_value=0.0)
        wide = wide.add(df_s, fill_value=0.0)
    return wide.reset_index().rename(columns={"index": "link_id"})


def build_wide_pollutant(
    grid: dict, link_ids: Any,
    pollutant_key: str = "NOx_idle",
    time_intervals: list[str] | None = None,
) -> pd.DataFrame:
    """Aggregate an idle-pollutant field from the grid."""
    ti = time_intervals or TIME_INTERVALS
    wide = pd.DataFrame(0.0, index=link_ids, columns=ti)
    for _size, links in (grid or {}).items():
        tmp: dict[str, dict[str, float]] = {}
        for lid, intervals in (links or {}).items():
            row = {ts: float((p or {}).get(pollutant_key, 0.0)) for ts, p in (intervals or {}).items()}
            if row:
                tmp[lid] = row
        if not tmp:
            continue
        df_s = pd.DataFrame.from_dict(tmp, orient="index").fillna(0.0)
        df_s = df_s.reindex(columns=ti, fill_value=0.0).reindex(index=link_ids, fill_value=0.0)
        wide = wide.add(df_s, fill_value=0.0)
    return wide.reset_index().rename(columns={"index": "link_id"})


def to_long(
    df_wide: pd.DataFrame,
    time_intervals: list[str] | None = None,
    value_name: str = "emissions_g",
) -> pd.DataFrame:
    """Melt a wide emissions DF into long format."""
    ti = time_intervals or TIME_INTERVALS
    val_cols = [c for c in df_wide.columns if c in ti]
    long = df_wide.melt(
        id_vars=["link_id"], value_vars=val_cols,
        var_name="interval_15min", value_name=value_name,
    )
    long["emissions_kg"] = long[value_name] / 1_000.0
    long["emissions_t"] = long[value_name] / 1_000_000.0
    return long


def network_totals(
    long_df: pd.DataFrame,
    value_col: str = "emissions_g",
) -> pd.DataFrame:
    """Network-wide totals per 15-min interval."""
    net = (
        long_df.groupby("interval_15min", as_index=False)[value_col]
        .sum()
        .rename(columns={value_col: "network_emissions_g"})
    )
    net["network_emissions_kg"] = net["network_emissions_g"] / 1_000.0
    net["network_emissions_t"] = net["network_emissions_g"] / 1_000_000.0
    return net


# ====================================================================
# Area-type emission breakdown (NEW – was missing from package)
# ====================================================================

def emissions_by_area_type(
    emissions_long: pd.DataFrame,
    link_raumtyp: dict[str, int],
    time_intervals: list[str] | None = None,
) -> tuple[pd.DataFrame, pd.DataFrame]:
    """Attach area types and compute 15-min × area-type aggregation.

    Returns
    -------
    by_area : DataFrame  with columns interval_15min, area_type, emissions_g_sum, emissions_kg_sum
    pivot   : DataFrame  pivoted (interval × area_type in kg)
    """
    ti = time_intervals or TIME_INTERVALS
    df = emissions_long.copy()
    df["area_type_id"] = df["link_id"].map(link_raumtyp)
    df["area_type"] = df["area_type_id"].fillna("Unknown")

    by_area = (
        df.groupby(["interval_15min", "area_type"], as_index=False)["emissions_g"]
        .sum()
        .rename(columns={"emissions_g": "emissions_g_sum"})
    )
    by_area["emissions_kg_sum"] = by_area["emissions_g_sum"] / 1_000.0

    pivot = (
        by_area.pivot(index="interval_15min", columns="area_type", values="emissions_kg_sum")
        .reindex(index=ti)
        .fillna(0.0)
    )
    return by_area, pivot


# ====================================================================
# Build per-vehicle emission dicts for result column
# ====================================================================

def build_vehicle_emissions_column(
    result: pd.DataFrame,
    veh_drive: dict, veh_idle: dict, veh_cold: dict, veh_total: dict,
) -> pd.DataFrame:
    """Add a nested ``emissions`` dict column to *result*."""
    all_vids = set(result["vehicle_id"].tolist()) | set(veh_total.keys())
    em_dict: dict[str, dict] = {}
    for vid in all_vids:
        d = float(veh_drive.get(vid, 0.0) or 0.0)
        idle = float(veh_idle.get(vid, 0.0) or 0.0)
        c = float(veh_cold.get(vid, 0.0) or 0.0)
        t = float(veh_total.get(vid, 0.0) or 0.0)
        em_dict[vid] = {
            "drive": d, "idle": idle, "cold": c, "total": t,
            "shares": {"idle": idle / t if t > 0 else 0.0, "cold": c / t if t > 0 else 0.0},
        }
    result = result.copy()
    result["emissions"] = result["vehicle_id"].map(em_dict)
    return result
