"""
HAGRID Output Analysis – Vehicle Statistics & Demand Analysis
==============================================================

Functions for:

1. Computing per-vehicle statistics (tour km, services, area-type
   split) from parsed event tours.
2. Enriching the result DataFrame with carrier demand information
   (deliveries, B2B/B2C ratios, load/delivery factors).
3. Calculating per-vehicle costs.
4. Building the minute-level vehicle-status matrix.

**Key design decisions:**

* :func:`calculate_costs` is **vectorised** — called once on the whole
  DataFrame (fixes previous O(n²) behaviour).
* :func:`add_vehicle_demand_to_result` uses a **collect-then-merge**
  pattern — builds a dict and joins in one pass instead of repeated
  ``result.loc[mask, col] =`` writes inside a loop.
* Vehicle capacities come from
  :data:`~.config.VEHICLE_CAPACITY_BY_SIZE` — no hardcoded magic
  numbers.
"""

from __future__ import annotations

import json
import logging
from collections import defaultdict
from typing import TYPE_CHECKING, Any

import numpy as np
import pandas as pd
from tqdm import tqdm

from hagrid_output_analysis.config import (
    COST_PARAMS_BY_SIZE,
    VEHICLE_CAPACITY_BY_SIZE,
    VEHICLE_TIME_COST_PER_SEC,
)
from hagrid_output_analysis.models import Carrier
from hagrid_output_analysis.utils import (
    extract_main_area_type,
    extract_provider,
    extract_veh_size,
    is_in_hannover,
)

if TYPE_CHECKING:
    from pandas.core.groupby import DataFrameGroupBy

logger = logging.getLogger(__name__)


# ====================================================================
# Per-vehicle statistics from tours & services
# ====================================================================

def vehicle_stats(
    vehicle_tour: dict[str, list[tuple[str, float]]],
    service_events: dict[str, list[str]],
    link_length: dict[str, float],
    link_raumtyp: dict[str, int],
) -> pd.DataFrame:
    """Compute distance and area-type statistics per vehicle.

    Returns a DataFrame with columns:
        vehicle_id, veh_class, service_num, tour_km,
        first_service_dist, raumsplit, service_dists,
        initial_delivery_distance
    """
    from hagrid_output_analysis.utils import classify_vehicle

    rows: list[dict[str, Any]] = []

    for veh, ser in service_events.items():
        if not ser:
            continue

        vclass = classify_vehicle(veh)

        tour_km = 0.0
        first_service_dist: float | None = None
        service_dists: dict[str, float] = {}

        for link, _ in vehicle_tour.get(veh, []):
            link_km = link_length.get(link, 0.0) / 1000.0
            tour_km += link_km
            for s in ser:
                if link == s and s not in service_dists:
                    service_dists[s] = tour_km
                    if first_service_dist is None:
                        first_service_dist = tour_km

        # Fallback for services not found on tour
        for s in ser:
            if s not in service_dists:
                service_dists[s] = tour_km
        if first_service_dist is None:
            first_service_dist = tour_km

        # Area-type distribution
        raumtypen: dict[int, int] = defaultdict(int)
        for s in ser:
            raumtypen[link_raumtyp.get(s, 0)] += 1

        rows.append({
            "vehicle_id": veh,
            "veh_class": vclass,
            "service_num": len(ser),
            "tour_km": tour_km,
            "first_service_dist": first_service_dist,
            "raumsplit": dict(raumtypen),
            "service_dists": service_dists,
            "initial_delivery_distance": first_service_dist,
        })

    return pd.DataFrame(rows)


# ====================================================================
# Process vehicle data (add provider, size, main area type)
# ====================================================================

def process_vehicle_data(veh_df: pd.DataFrame) -> pd.DataFrame:
    """Add ``provider``, ``veh_size``, ``main_area_type`` columns."""
    df = veh_df.copy()
    df["provider"] = df["vehicle_id"].apply(extract_provider)
    df["veh_size"] = df["vehicle_id"].apply(extract_veh_size)
    df["main_area_type"] = df["raumsplit"].apply(extract_main_area_type)
    return df


# ====================================================================
# Vehicle capacity helpers
# ====================================================================

def _vehicle_capacity_from_id(
    vehicle_id: str,
    vtypes: dict | None = None,
) -> int:
    """Derive parcel capacity from the vehicle ID.

    If *vtypes* (parsed from ``output_carriersVehicleTypes.xml.gz``)
    is provided, look up the capacity there first.  Otherwise fall
    back to :data:`~.config.VEHICLE_CAPACITY_BY_SIZE`.
    """
    if vtypes:
        from hagrid_output_analysis.parsers import resolve_vehicle_type
        vt = resolve_vehicle_type(vehicle_id, vtypes)
        if vt is not None:
            return vt["capacity"]

    size = extract_veh_size(vehicle_id)
    if size in VEHICLE_CAPACITY_BY_SIZE:
        return VEHICLE_CAPACITY_BY_SIZE[size]
    for key, (cap, _, _) in COST_PARAMS_BY_SIZE.items():
        if key in vehicle_id:
            return cap
    return 230


# ====================================================================
# Cost calculation (VECTORISED – called ONCE, not per vehicle)
# ====================================================================

def _match_cost_params(
    vehicle_id: str,
    vtypes: dict | None = None,
) -> tuple[int, float, float]:
    """Return ``(capacity, fix_cost, km_cost)`` for a vehicle ID.

    If *vtypes* is given, read from the parsed vehicle-type XML.
    """
    if vtypes:
        from hagrid_output_analysis.parsers import resolve_vehicle_type
        vt = resolve_vehicle_type(vehicle_id, vtypes)
        if vt is not None:
            return vt["capacity"], vt["fixed_cost"], vt["cost_per_km"]

    for key in ("size_l", "size_m", "supply_light_van", "light"):
        if key in vehicle_id:
            return COST_PARAMS_BY_SIZE[key]
    return COST_PARAMS_BY_SIZE["default"]


def calculate_costs(
    df: pd.DataFrame,
    vtypes: dict | None = None,
    time_cost_per_sec: float | None = None,
) -> pd.DataFrame:
    """Vectorised cost calculation.

    **Must be called once on the complete DataFrame**, not inside a
    per-vehicle loop.

    If *vtypes* (from ``parse_vehicle_types()``) is given, costs and
    capacities are read from the simulation XML rather than hardcoded.

    Parameters
    ----------
    time_cost_per_sec : float, optional
        Labour / time cost in € per second.  Defaults to the module-
        level ``VEHICLE_TIME_COST_PER_SEC`` (22.87 €/h).

    Adds columns: ``vehicle_fix_cost``, ``vehicle_km_cost``,
    ``vehicle_time_cost``, ``overtime_cost``, ``vehicle_cost``,
    ``vehicle_capacity``.
    """
    tc = time_cost_per_sec if time_cost_per_sec is not None else VEHICLE_TIME_COST_PER_SEC

    out = df.copy()
    params = out["vehicle_id"].apply(lambda vid: _match_cost_params(vid, vtypes))
    caps, fixes, km_costs = zip(*params)

    out["vehicle_capacity"] = list(caps)
    out["vehicle_fix_cost"] = list(fixes)
    out["vehicle_km_cost"] = out["tour_km"] * pd.Series(km_costs, index=out.index)
    out["vehicle_time_cost"] = out["Tour Duration"] * tc

    overtime = (out["Tour Duration"] - 7.5 * 3600).clip(lower=0)
    out["overtime_cost"] = overtime * tc
    out["vehicle_cost"] = (
        out["vehicle_fix_cost"] + out["vehicle_km_cost"] + out["overtime_cost"]
    )
    return out


# ====================================================================
# Add carrier-level demand information (collect-then-merge)
# ====================================================================

def add_vehicle_demand_to_result(
    carriers: list[Carrier],
    result: pd.DataFrame,
    vtypes: dict | None = None,
) -> pd.DataFrame:
    """Enrich *result* with deliveries, missed parcels, B2B/B2C ratios.

    Uses a **collect-then-merge** pattern: demand data is gathered into
    a flat dict keyed by ``vehicle_id`` and merged into *result* in a
    single pass — much faster than writing ``result.loc[mask, ...]``
    per vehicle inside a loop.

    .. important::

       Cost calculation is **not** done here.  Call
       :func:`calculate_costs` separately on the returned DataFrame.
    """
    # Pre-initialise columns so downstream code never hits KeyError
    # (e.g. when *carriers* is empty).
    for col in ("deliveries", "missed deliveries", "b2b_ration",
                "b2c_ration", "ration_check", "vehicle_load_factor",
                "vehicle_deliver_factor"):
        if col not in result.columns:
            result[col] = np.nan

    demand_records: dict[str, dict[str, Any]] = {}
    services_map: dict[str, list] = {}
    expected_ids: list[str] = []

    for carrier in carriers:
        missed_set = set(carrier.missed_deliveries)
        svc_by_id = {s.service_id: s for s in carrier.services.values()}

        for v in carrier.vehicles.values():
            vid = f"freight_{carrier.carrier_id}_veh_{v.vehicle_id}"
            expected_ids.append(vid)

            if not v.plans:
                continue

            b2b, b2c, missed = 0, 0, 0
            veh_services: list = []

            for act_key in v.plans[0].activities:
                if "service" not in act_key:
                    continue
                svc = svc_by_id.get(act_key)
                if svc is None:
                    continue
                veh_services.append(svc)

                # Prefer explicit b2b/b2c attributes (merged-service
                # format).  Fall back to capacity_demand + type attr
                # when those are absent (standard HAGRID output).
                s_b2b = int(svc.get_attr("b2b", 0) or 0)
                s_b2c = int(svc.get_attr("b2c", 0) or 0)

                if s_b2b == 0 and s_b2c == 0:
                    # Standard format: demand is in capacity_demand,
                    # type is in extra["type"] ("B2B" / "B2C").
                    svc_type = str(svc.get_attr("type", "")).upper()
                    if svc_type == "B2B":
                        s_b2b = svc.capacity_demand
                    else:
                        s_b2c = svc.capacity_demand

                b2b += s_b2b
                b2c += s_b2c

                svc_demand = s_b2b + s_b2c
                if svc.service_id in missed_set:
                    missed += svc_demand
                else:
                    raw_merged = svc.get_attr("mergedMetadata")
                    if raw_merged:
                        missed += _count_merged_missed(
                            raw_merged, missed_set,
                        )

            total = b2b + b2c
            cap = _vehicle_capacity_from_id(vid, vtypes)

            demand_records[vid] = {
                "deliveries": total,
                "missed deliveries": round(missed),
                "b2b_ration": b2b / total if total else np.nan,
                "b2c_ration": b2c / total if total else np.nan,
                "ration_check": 1.0 if total else np.nan,
                "vehicle_load_factor": total / cap,
                "vehicle_deliver_factor": (
                    (total - missed) / total if total else np.nan
                ),
            }
            services_map[vid] = veh_services

    # Merge collected data into result in one pass
    if demand_records:
        demand_df = pd.DataFrame.from_dict(demand_records, orient="index")
        for col in demand_df.columns:
            result[col] = result["vehicle_id"].map(demand_df[col])

    _validate_vehicle_ids(result, expected_ids)

    svc_num = result.get("service_num")
    if svc_num is not None:
        result["deliveries_per_stop"] = result["deliveries"] / svc_num
    result["Hannover"] = result["vehicle_id"].apply(is_in_hannover)
    result["services"] = result["vehicle_id"].map(services_map)

    return result


# ====================================================================
# Spatial enrichment – coords, weights, delivery area
# ====================================================================

def enrich_vehicle_spatial(
    result: pd.DataFrame,
    carriers: list[Carrier],
) -> pd.DataFrame:
    """Add ``coords_list``, ``weights_list``, ``total_weight``,
    ``avg_weight_per_parcel`` and ``delivery_area_km2`` to *result*.

    Ported from the *result-analysis-matsim* notebook.  Extracts
    service coordinates (``"(x;y)"`` format in EPSG 25832) and
    per-parcel weights from the carrier service attributes, including
    merged sub-services.
    """
    import json
    import re

    from collections import defaultdict
    from shapely.geometry import MultiPoint, Point
    from shapely.ops import transform as shapely_transform
    import pyproj

    vehicle_coords: dict[str, list] = defaultdict(list)
    vehicle_weights: dict[str, list] = defaultdict(list)

    _coord_re = re.compile(r"\(([\d.]+);([\d.]+)\)")

    for carrier in carriers:
        svc_by_id = {s.service_id: s for s in carrier.services.values()}

        for v in carrier.vehicles.values():
            vid = f"freight_{carrier.carrier_id}_veh_{v.vehicle_id}"
            if not v.plans:
                continue

            for act_key in v.plans[0].activities:
                if "service" not in act_key:
                    continue
                svc = svc_by_id.get(act_key)
                if svc is None:
                    continue

                # --- direct coordinate on the service --------------------
                coord_raw = svc.get_attr("coord")
                if coord_raw:
                    m = _coord_re.match(str(coord_raw))
                    if m:
                        vehicle_coords[vid].append(
                            Point(float(m.group(1)), float(m.group(2)))
                        )

                # --- direct weights on the service -----------------------
                weights_raw = svc.get_attr("weights")
                if weights_raw:
                    for w in re.split(r"[;,\s]+", str(weights_raw).strip()):
                        try:
                            vehicle_weights[vid].append(max(float(w), 0.1))
                        except (ValueError, TypeError):
                            pass

                # --- merged sub-services ---------------------------------
                merged_raw = svc.get_attr("mergedMetadata")
                if merged_raw:
                    try:
                        merged_dict = json.loads(merged_raw)
                    except (json.JSONDecodeError, TypeError):
                        continue
                    for _sub_id, meta in merged_dict.items():
                        if "coord" in meta and isinstance(meta["coord"], list):
                            x, y = meta["coord"]
                            vehicle_coords[vid].append(
                                Point(float(x), float(y))
                            )
                        if "weights" in meta:
                            for w in meta["weights"]:
                                try:
                                    vehicle_weights[vid].append(
                                        max(float(w), 0.1)
                                    )
                                except (ValueError, TypeError):
                                    pass

    # Build lookup Series
    coords_series = pd.Series(vehicle_coords, name="coords_list")
    weights_series = pd.Series(vehicle_weights, name="weights_list")

    out = result.copy()
    out["coords_list"] = out["vehicle_id"].map(coords_series)
    out["weights_list"] = out["vehicle_id"].map(weights_series)

    # Convex-hull delivery area (EPSG 25832 → EPSG 3857 for m² area)
    _project = pyproj.Transformer.from_crs(
        "epsg:25832", "epsg:3857", always_xy=True,
    ).transform

    def _calc_area_km2(coords):
        if not isinstance(coords, list) or len(coords) < 3:
            return 0.0
        try:
            hull = MultiPoint(coords).convex_hull
            hull_m = shapely_transform(_project, hull)
            return round(hull_m.area / 1e6, 3)
        except Exception:
            return 0.0

    out["delivery_area_km2"] = out["coords_list"].apply(_calc_area_km2)
    out["total_weight"] = out["weights_list"].apply(
        lambda ws: round(sum(ws), 2) if isinstance(ws, list) else 0.0,
    )
    out["avg_weight_per_parcel"] = out["weights_list"].apply(
        lambda ws: round(sum(ws) / len(ws), 2)
        if isinstance(ws, list) and ws
        else 0.0,
    )

    return out


# ====================================================================
# Derived KPIs (post-pipeline enrichment)
# ====================================================================

_AREA_TYPE_AGG_MAP = {1: "Urban", 2: "Urban", 3: "Urban",
                      4: "Suburban", 5: "Suburban", 6: "Suburban",
                      7: "Rural", 8: "Rural"}

_CARRIER_CATEGORY_MAP = {
    "dhl": "Mixed", "gls": "Mixed", "dpd": "Mixed",
    "hermes": "B2C", "amazon": "B2C",
    "fedex": "B2B", "fxt": "B2B", "tnt": "B2B", "ups": "B2B",
}

_DISTANCE_BINS = [0, 50, 100, 150, 200, 250, np.inf]
_DISTANCE_LABELS = ["≤ 50", "≤ 100", "≤ 150", "≤ 200", "≤ 250", "> 250"]


def _inter_stop_metrics(row: pd.Series) -> dict:
    """Avg / median / max inter-stop distance, with and without stem."""
    sd = row.get("service_dists")
    init = row.get("first_service_dist", 0.0) or 0.0
    empty = {
        "avg_dist_with_init": 0.0, "avg_dist_wo_init": 0.0,
        "median_dist_with_init": 0.0, "median_dist_wo_init": 0.0,
        "max_dist_with_init": 0.0, "max_dist_wo_init": 0.0,
    }
    if not sd:
        return empty
    dists = sorted(sd.values())
    with_init = [init] + [dists[i + 1] - dists[i] for i in range(len(dists) - 1)]
    wo_init = [dists[i + 1] - dists[i] for i in range(len(dists) - 1)] if len(dists) > 1 else []
    return {
        "avg_dist_with_init": float(np.mean(with_init)) if with_init else 0.0,
        "avg_dist_wo_init": float(np.mean(wo_init)) if wo_init else 0.0,
        "median_dist_with_init": float(np.median(with_init)) if with_init else 0.0,
        "median_dist_wo_init": float(np.median(wo_init)) if wo_init else 0.0,
        "max_dist_with_init": float(max(with_init)) if with_init else 0.0,
        "max_dist_wo_init": float(max(wo_init)) if wo_init else 0.0,
    }


def compute_derived_kpis(df: pd.DataFrame) -> pd.DataFrame:
    """Compute all derived last-mile delivery KPIs.

    Adds the following column groups to *df*:

    **Carrier & area classification**
      ``carrier_name``, ``plz_gebiet``, ``carrier``,
      ``carrier_category`` (B2B / B2C / Mixed),
      ``area_type_agg`` (Urban / Suburban / Rural)

    **Efficiency KPIs**
      ``cost_per_parcel``, ``cost_per_kg``, ``cost_per_km``,
      ``parcels_per_km``, ``emissions_per_parcel``,
      ``emissions_per_km``, ``emissions_per_kg``

    **Time KPIs**
      ``tour_duration_hrs``, ``driving_time_hrs``,
      ``driving_share_pct``, ``avg_speed_kmh``,
      ``time_per_stop_min``, ``time_per_delivery_min``

    **Tour structure**
      ``stem_distance_share``, ``stop_density_per_km2``,
      ``distance_class``,
      ``avg_dist_with_init``, ``avg_dist_wo_init``,
      ``median_dist_with_init``, ``median_dist_wo_init``,
      ``max_dist_with_init``, ``max_dist_wo_init``

    **Quality**
      ``failed_delivery_rate``, ``weight_per_km``

    Parameters
    ----------
    df : DataFrame
        The ``emissions_result`` DataFrame from ``process_single_run``.

    Returns
    -------
    DataFrame – enriched copy with all derived columns added.
    """
    out = df.copy()

    # ---- Carrier extraction ------------------------------------------
    _pat = r"^freight_([a-z]+)_(\d+)(?:_(\d+))?_veh"
    parts = out["vehicle_id"].str.extract(_pat)
    out["carrier_name"] = parts[0]
    out["plz_gebiet"] = parts[1]
    out["carrier"] = out["carrier_name"].fillna("") + "_" + out["plz_gebiet"].fillna("")

    # Carrier category
    out["carrier_category"] = out["carrier_name"].map(_CARRIER_CATEGORY_MAP).fillna("Mixed")

    # ---- Area aggregation --------------------------------------------
    out["area_type_agg"] = (
        out["main_area_type"]
        .apply(lambda x: _AREA_TYPE_AGG_MAP.get(int(x), "Unknown")
               if pd.notna(x) else "Unknown")
    )

    # ---- Time KPIs ---------------------------------------------------
    td = out.get("Tour Duration")
    trav = out.get("Travel Duration")
    svc_d = out.get("Service Duration")
    snum = out.get("service_num", pd.Series(0, index=out.index))
    deliv = out.get("deliveries", pd.Series(0, index=out.index))

    out["tour_duration_hrs"] = td / 3600 if td is not None else np.nan
    out["driving_time_hrs"] = trav / 3600 if trav is not None else np.nan
    out["driving_share_pct"] = np.where(
        td > 0, (trav / td) * 100, np.nan,
    ) if td is not None else np.nan
    out["avg_speed_kmh"] = np.where(
        trav > 0, out["tour_km"] / (trav / 3600), np.nan,
    ) if trav is not None else np.nan
    out["time_per_stop_min"] = np.where(
        snum > 0, (svc_d / snum) / 60, np.nan,
    ) if svc_d is not None else np.nan
    out["time_per_delivery_min"] = np.where(
        deliv > 0, (svc_d / deliv) / 60, np.nan,
    ) if svc_d is not None else np.nan

    # ---- Cost KPIs ---------------------------------------------------
    vc = out.get("vehicle_cost", pd.Series(np.nan, index=out.index))
    out["cost_per_parcel"] = np.where(deliv > 0, vc / deliv, np.nan)
    out["cost_per_km"] = np.where(
        out["tour_km"] > 0, vc / out["tour_km"], np.nan,
    )
    tw = out.get("total_weight", pd.Series(0, index=out.index))
    out["cost_per_kg"] = np.where(tw > 0, vc / tw, np.nan)

    # ---- Delivery efficiency -----------------------------------------
    out["parcels_per_km"] = np.where(
        out["tour_km"] > 0, deliv / out["tour_km"], np.nan,
    )

    # ---- Emission KPIs -----------------------------------------------
    # ``emissions`` is a dict column (drive/idle/cold/total) → extract total
    em_raw = out.get("emissions", pd.Series(0, index=out.index))
    em = em_raw.apply(
        lambda x: float(x.get("total", 0.0)) if isinstance(x, dict) else float(x or 0.0),
    )
    out["emissions_total_g"] = em
    out["emissions_per_parcel"] = np.where(deliv > 0, em / deliv, np.nan)
    out["emissions_per_km"] = np.where(
        out["tour_km"] > 0, em / out["tour_km"], np.nan,
    )
    out["emissions_per_kg"] = np.where(tw > 0, em / tw, np.nan)

    # ---- Tour structure KPIs -----------------------------------------
    out["stem_distance_share"] = np.where(
        out["tour_km"] > 0,
        out["first_service_dist"] / out["tour_km"],
        np.nan,
    )
    da = out.get("delivery_area_km2", pd.Series(0, index=out.index))
    out["stop_density_per_km2"] = np.where(da > 0, snum / da, np.nan)
    out["weight_per_km"] = np.where(
        out["tour_km"] > 0, tw / out["tour_km"], np.nan,
    )

    # Distance class bins
    out["distance_class"] = pd.cut(
        out["tour_km"], bins=_DISTANCE_BINS, labels=_DISTANCE_LABELS, right=True,
    )

    # Inter-stop distance metrics
    if "service_dists" in out.columns:
        metrics = out.apply(_inter_stop_metrics, axis=1)
        metrics_df = pd.DataFrame(metrics.tolist(), index=out.index)
        for col in metrics_df.columns:
            out[col] = metrics_df[col]

    # ---- Quality KPIs ------------------------------------------------
    missed = out.get("missed deliveries", pd.Series(0, index=out.index))
    total_attempts = deliv + missed
    out["failed_delivery_rate"] = np.where(
        total_attempts > 0, missed / total_attempts, 0.0,
    )

    return out


# ====================================================================
# KPI summary table builder
# ====================================================================

# Structured KPI catalogue (column → label), grouped by category.
KPI_STRUCTURE: dict[str, dict[str, str]] = {
    "Temporal": {
        "tour_duration_hrs": "Tour Duration [hrs]",
        "driving_time_hrs": "Driving Time [hrs]",
    },
    "Tour Structure": {
        "tour_km": "Tour Length [km]",
        "first_service_dist": "Initial Delivery Dist. [km]",
        "service_num": "Stops [#]",
        "avg_dist_wo_init": "Mean Inter-Stop Dist. [km]",
        "max_dist_wo_init": "Max Segment Dist. [km]",
        "delivery_area_km2": "Delivery Area [km²]",
        "driving_share_pct": "Driving Share [%]",
        "avg_speed_kmh": "Avg. Speed [km/h]",
        "stem_distance_share": "Stem-Distance Share",
        "stop_density_per_km2": "Stop Density [#/km²]",
    },
    "Delivery": {
        "deliveries": "Deliveries [#]",
        "deliveries_per_stop": "Deliveries/Stop [#]",
        "missed deliveries": "Missed Deliveries [#]",
        "failed_delivery_rate": "Failed Delivery Rate",
        "vehicle_deliver_factor": "Delivery Factor",
        "b2b_ration": "B2B Ratio",
    },
    "Load": {
        "total_weight": "Total Weight [kg]",
        "avg_weight_per_parcel": "Avg. Weight/Parcel [kg]",
        "vehicle_load_factor": "Vehicle Utilization",
        "weight_per_km": "Weight/km [kg/km]",
    },
    "Cost": {
        "vehicle_cost": "Vehicle Cost [€]",
        "cost_per_parcel": "Cost/Parcel [€]",
        "cost_per_km": "Cost/km [€]",
        "cost_per_kg": "Cost/kg [€]",
    },
    "Emissions": {
        "emissions_total_g": "Emissions [g CO₂]",
        "emissions_per_parcel": "Emissions/Parcel [g]",
        "emissions_per_km": "Emissions/km [g]",
        "emissions_per_kg": "Emissions/kg [g]",
    },
}


def build_kpi_summary(
    df: pd.DataFrame,
    group_col: str = "area_type_agg",
    agg_funcs: list[str] | None = None,
) -> pd.DataFrame:
    """Build a structured KPI summary table grouped by *group_col*.

    Parameters
    ----------
    df : DataFrame
        Result from ``compute_derived_kpis``.
    group_col : str
        Column to group by.  Common choices:
        ``"area_type_agg"`` (Urban / Suburban / Rural),
        ``"main_area_type_name"`` (8 area types),
        ``"provider"``, ``"carrier_category"``.
    agg_funcs : list[str], optional
        Aggregation functions.  Defaults to
        ``["count", "mean", "std", "median"]``.

    Returns
    -------
    DataFrame with a MultiIndex on columns ``(kpi_label, agg_func)``.
    """
    if agg_funcs is None:
        agg_funcs = ["count", "mean", "std", "median"]

    # Collect available numeric KPI columns
    kpi_cols: list[str] = []
    rename_map: dict[str, str] = {}
    for _group_name, metrics in KPI_STRUCTURE.items():
        for col, label in metrics.items():
            if col in df.columns and pd.api.types.is_numeric_dtype(df[col]):
                kpi_cols.append(col)
                rename_map[col] = label

    summary = df.groupby(group_col)[kpi_cols].agg(agg_funcs)
    summary.rename(columns=rename_map, level=0, inplace=True)

    # Add vehicle count
    vcounts = df.groupby(group_col).size().rename("Vehicles [#]")
    for fn in agg_funcs:
        summary[("Vehicles [#]", fn)] = vcounts if fn == "count" else (
            vcounts if fn == "mean" else np.nan
        )

    return summary


# ====================================================================
# Merged-metadata missed-delivery accounting
# ====================================================================

def _count_merged_missed(
    raw_merged: str,
    missed_set: set[str],
) -> int:
    """Parse ``mergedMetadata`` JSON and return total missed sub-demand.

    Each key in the JSON dict is a sub-service ID.  If it appears in
    *missed_set*, its demand (``capacity`` or ``len(weights)``) is
    counted.
    """
    missed = 0
    try:
        merged_dict: dict = json.loads(raw_merged)
    except (json.JSONDecodeError, TypeError):
        logger.debug("Could not parse mergedMetadata: %.60s…", raw_merged)
        return 0

    for sid, md in merged_dict.items():
        if sid not in missed_set:
            continue

        cap = int(md.get("capacity", 0) or 0)
        weights = _parse_weights(md.get("weights", []))

        if cap > 0 and weights and cap != len(weights):
            logger.warning(
                "Merged service %s: capacity=%d ≠ len(weights)=%d",
                sid, cap, len(weights),
            )

        missed += cap if cap > 0 else (len(weights) if weights else 1)

    return missed


def _parse_weights(raw: Any) -> list:
    """Normalise the ``weights`` field from merged metadata.

    Handles plain lists, JSON-encoded strings, and scalar values.
    """
    if isinstance(raw, (list, tuple)):
        return list(raw)
    if isinstance(raw, str):
        try:
            parsed = json.loads(raw)
            return parsed if isinstance(parsed, list) else [parsed]
        except (json.JSONDecodeError, TypeError):
            return [raw]
    return []


# ====================================================================
# Vehicle-ID validation
# ====================================================================

def _validate_vehicle_ids(
    result: pd.DataFrame,
    expected_ids: list[str],
) -> None:
    """Log a concise validation report comparing carrier vehicles
    against *result* rows.
    """
    actual = set(result["vehicle_id"].astype(str))
    missing = [vid for vid in expected_ids if vid not in actual]
    dup_count = int(result["vehicle_id"].duplicated().sum())
    empty_count = int(result["vehicle_id"].isna().sum())

    logger.info(
        "Vehicle ID validation: expected=%d found=%d missing=%d "
        "duplicates=%d empty=%d",
        len(expected_ids),
        len(expected_ids) - len(missing),
        len(missing),
        dup_count,
        empty_count,
    )
    for vid in missing[:5]:
        logger.warning("Missing vehicle in result: %s", vid)


# ====================================================================
# Attach service timestamps from events
# ====================================================================

def attach_service_times(
    result_df: pd.DataFrame,
    startG: DataFrameGroupBy,
    endG: DataFrameGroupBy,
    snap_1s: bool = True,
) -> pd.DataFrame:
    """Write ``start_ts``, ``end_ts``, ``post_start_ts``,
    ``observed_dur_s``, ``snapped`` into each Service object's extras.
    """
    for _, row in result_df.iterrows():
        vid = row["vehicle_id"]
        services = row.get("services")
        if not services:
            continue

        try:
            g_start = (
                startG.get_group(vid)
                .sort_values("time")
                .reset_index(drop=True)
            )
            g_end = (
                endG.get_group(vid)
                .sort_values("time")
                .reset_index(drop=True)
            )
        except (KeyError, AttributeError):
            continue

        n = min(len(g_start), len(g_end), len(services))
        for i in range(n):
            svc = services[i]
            s_sec = float(g_start.loc[i, "time"])
            e_sec = float(g_end.loc[i, "time"])
            declared = svc.duration
            observed = e_sec - s_sec
            snapped = False

            if snap_1s and declared and abs(observed - declared) == 1.0:
                e_sec = s_sec + declared
                observed = declared
                snapped = True

            start_ts = pd.to_datetime(s_sec, unit="s")
            end_ts = pd.to_datetime(e_sec, unit="s")
            post_ts = end_ts + pd.to_timedelta(1, unit="s")

            svc.set_attr("start_ts", start_ts.isoformat())
            svc.set_attr("end_ts", end_ts.isoformat())
            svc.set_attr("post_start_ts", post_ts.isoformat())
            svc.set_attr("observed_dur_s", observed)
            svc.set_attr("snapped", snapped)

    return result_df


# ====================================================================
# Build vehicle-status matrix (minute level)
# ====================================================================

def build_vehicle_status_matrix(
    result: pd.DataFrame,
    startG: DataFrameGroupBy,
    endG: DataFrameGroupBy,
) -> pd.DataFrame:
    """Minute-level matrix of vehicle load percentages.

    Codes: ``-3`` = inactive, ``-2`` = loading, ``-1`` = driving,
    ``0..100`` = load percentage during service.
    """
    vehicles_list = result["vehicle_id"].unique()
    time_cols = [f"{h:02}:{m:02}" for h in range(24) for m in range(60)]
    status_df = pd.DataFrame(-3, index=vehicles_list, columns=time_cols)

    for name, group_start in tqdm(
        startG, desc="Building status matrix", unit="tour",
    ):
        row = result[result.vehicle_id == name]
        if row.empty:
            continue

        num_del = row["deliveries"].iloc[0]
        services = row["services"].iloc[0]
        veh_size = row["veh_size"].iloc[0]

        start_tour = pd.to_datetime(row["Start time formatted"].iloc[0])
        end_tour = pd.to_datetime(row["End time formatted"].iloc[0])
        loading_start = (start_tour - pd.Timedelta(minutes=60)).round("min")
        start_min = start_tour.round("min")
        end_min = end_tour.round("min")

        ls_str = loading_start.strftime("%H:%M")
        st_str = start_min.strftime("%H:%M")
        et_str = end_min.strftime("%H:%M")

        if ls_str in status_df.columns and st_str in status_df.columns:
            status_df.loc[name, ls_str:st_str] = -2
        if st_str in status_df.columns and et_str in status_df.columns:
            status_df.loc[name, st_str:et_str] = -1

        expected_cap = VEHICLE_CAPACITY_BY_SIZE.get(veh_size, 230)

        try:
            group_end = endG.get_group(name)
        except KeyError:
            continue

        combined = pd.concat([group_start, group_end]).sort_values("time")
        combined["group_id"] = [i // 2 for i in range(len(combined))]
        grouped = [g for _, g in combined.groupby("group_id")]

        current_load = num_del
        for idx, g in enumerate(grouped):
            if idx >= len(services):
                break
            svc = services[idx]
            start = pd.to_datetime(g.iloc[0]["time"], unit="s").round("min")
            end = pd.to_datetime(g.iloc[1]["time"], unit="s").round("min")
            s_str = start.strftime("%H:%M")
            e_str = end.strftime("%H:%M")
            load_pct = round((current_load / expected_cap) * 100)
            if s_str in status_df.columns and e_str in status_df.columns:
                status_df.loc[name, s_str:e_str] = load_pct
            current_load -= svc.capacity_demand

    return status_df
