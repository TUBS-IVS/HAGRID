"""
HAGRID Output Analysis – Batch Runner
=======================================

Orchestrates the full pipeline for one or many MATSim runs:

1. Discover runs in a batch directory.
2. Parse events and carriers.
3. Compute vehicle stats, costs, EV assignment.
4. Run the emissions model.
5. Persist all results as ``.pkl`` files.

**Improvements over the notebook version**

* Responsibilities are split into ``process_single_run`` (pure logic)
  and ``run_batch`` (orchestration / I/O).
* ``logging`` module used instead of ``print``.
* Broad ``except Exception`` replaced with targeted handling.
"""

from __future__ import annotations

import datetime
import logging
import os
import pickle
import time
from pathlib import Path
from typing import Any

import geopandas as gpd
import numpy as np
import pandas as pd

from hagrid_output_analysis.analysis import (
    add_vehicle_demand_to_result,
    attach_service_times,
    build_vehicle_status_matrix,
    calculate_costs,
    compute_derived_kpis,
    enrich_vehicle_spatial,
    process_vehicle_data,
    vehicle_stats,
)
from hagrid_output_analysis.config import (
    AREA_TYPE_LABELS,
    RunConfig,
)
from hagrid_output_analysis.emissions import (
    TIME_INTERVALS,
    build_vehicle_emissions_column,
    build_wide,
    build_wide_pollutant,
    emissions_by_area_type,
    filter_low_utilisation,
    network_totals,
    run_emissions_loop,
    to_long,
)
from hagrid_output_analysis.ev_model import (
    assign_ev_flags,
    build_base_class_map,
    build_effective_class_map,
    compute_ev_counts,
    ev_sanity_report,
)
from hagrid_output_analysis.parsers import (
    build_plot_data,
    merge_link_counts_with_network,
    parse_carriers_from_xml,
    parse_events,
    parse_vehicle_types,
)
from hagrid_output_analysis.reference import ReferenceData
from hagrid_output_analysis.utils import format_runtime

logger = logging.getLogger(__name__)


# ====================================================================
# Run discovery
# ====================================================================

def discover_runs(batch_dir: str | Path) -> list[dict[str, str]]:
    """Find all MATSim runs (event + carrier file pairs) under *batch_dir*.

    Returns a list of dicts with keys:
        scenario, run_name, event_file, carrier_file
    """
    batch_dir = str(batch_dir)
    runs: list[dict[str, str]] = []
    if not os.path.isdir(batch_dir):
        return runs

    for scen_entry in sorted(os.listdir(batch_dir)):
        scen_path = os.path.join(batch_dir, scen_entry)
        if not os.path.isdir(scen_path):
            continue
        scenario = scen_entry.split(" ")[0]
        subs = [
            os.path.join(scen_path, d)
            for d in os.listdir(scen_path)
            if os.path.isdir(os.path.join(scen_path, d))
        ]
        search_roots = subs if subs else [scen_path]

        for root in search_roots:
            try:
                files = os.listdir(root)
            except PermissionError:
                continue
            ev = [f for f in files if f.endswith(("output_events.xml", "output_events.xml.gz"))]
            ca = [f for f in files if f.endswith(("output_carriers.xml", "output_carriers.xml.gz"))]
            vt = [f for f in files if f.endswith(("output_carriersVehicleTypes.xml", "output_carriersVehicleTypes.xml.gz"))]
            if not ev or not ca:
                continue

            def _prefix(fn: str) -> str:
                return fn.replace(".gz", "").replace(".output_events.xml", "").replace(".output_carriers.xml", "").replace(".output_carriersVehicleTypes.xml", "")

            ev_map = {_prefix(f): f for f in ev}
            ca_map = {_prefix(f): f for f in ca}
            vt_map = {_prefix(f): f for f in vt}
            for pref in sorted(set(ev_map) & set(ca_map)):
                entry: dict[str, str] = {
                    "scenario": scenario,
                    "run_name": os.path.basename(root) if root != scen_path else pref,
                    "event_file": os.path.join(root, ev_map[pref]),
                    "carrier_file": os.path.join(root, ca_map[pref]),
                }
                if pref in vt_map:
                    entry["vehicle_types_file"] = os.path.join(root, vt_map[pref])
                runs.append(entry)
    return runs


# ====================================================================
# Persistence
# ====================================================================

def _save_pkl(obj: Any, path: str | Path) -> str:
    """Save *obj* as ``.pkl``, return the written path."""
    p = Path(path).with_suffix(".pkl")
    p.parent.mkdir(parents=True, exist_ok=True)
    with open(p, "wb") as fh:
        pickle.dump(obj, fh)
    logger.info("Saved %s", p)
    return str(p)


# ====================================================================
# Single-run processing
# ====================================================================

def process_single_run(
    cfg: RunConfig,
    ref: ReferenceData,
) -> dict[str, Any]:
    """Execute the full analysis pipeline for one MATSim run.

    Parameters
    ----------
    cfg : RunConfig
        Scenario configuration **including** ``event_file`` and
        ``carrier_file`` paths.
    ref : ReferenceData
        Pre-loaded spatial reference bundle.

    Returns
    -------
    dict
        All computed DataFrames and dicts, ready for persistence.

    Raises
    ------
    ValueError
        If ``cfg.event_file`` or ``cfg.carrier_file`` is *None*.
    """
    if not cfg.event_file or not cfg.carrier_file:
        raise ValueError(
            "RunConfig must specify 'event_file' and 'carrier_file'."
        )

    event_file = cfg.event_file
    carrier_file = cfg.carrier_file
    _vtf = cfg.vehicle_types_file
    t0 = time.time()
    _W = 72  # print width

    print("\n" + "═" * _W)
    print("  HAGRID Pipeline — Single Run")
    print("═" * _W)

    # ── 1) Parse events (single pass) ────────────────────────────
    _step_start = time.time()
    print(f"\n▸ [1/9]  Parsing events …")
    print(f"         {os.path.basename(event_file)}")
    ev = parse_events(event_file)
    vehicle_tour = ev["vehicle_tour"]
    service_events = ev["service_events"]
    link_counts = ev["link_counts"]
    _n_veh = len(vehicle_tour)
    _n_ev = ev["nr_events"]
    print(f"    ✓  {_n_veh:,} vehicles · {_n_ev:,} events  ({format_runtime(time.time() - _step_start)})")

    # ── 2) Network volumes ───────────────────────────────────────
    _step_start = time.time()
    print(f"\n▸ [2/9]  Merging link counts with network …")
    network_volumes = merge_link_counts_with_network(link_counts, ref.network)
    clipped = gpd.clip(network_volumes, ref.gdf_areas)
    print(f"    ✓  {len(network_volumes):,} links → {len(clipped):,} after clip  ({format_runtime(time.time() - _step_start)})")

    # ── 3) Vehicle statistics ────────────────────────────────────
    _step_start = time.time()
    print(f"\n▸ [3/9]  Computing vehicle statistics …")
    veh_df = vehicle_stats(vehicle_tour, service_events, ref.link_length, ref.link_raumtyp)
    veh_df = process_vehicle_data(veh_df)
    print(f"    ✓  {len(veh_df):,} vehicles enriched  ({format_runtime(time.time() - _step_start)})")

    # ── 4) Build plot data ───────────────────────────────────────
    _step_start = time.time()
    print(f"\n▸ [4/9]  Building timing data …")
    vehicles = [v for v in vehicle_tour if "_supply_" not in v]
    plot_data, startG, endG = build_plot_data(
        vehicles,
        ev["tour_start_events"],
        ev["tour_end_events"],
        ev["service_start_events"],
        ev["service_end_events"],
    )
    print(f"    ✓  {len(vehicles):,} CEP tours  ({format_runtime(time.time() - _step_start)})")

    # ── 5) Merge & enrich ────────────────────────────────────────
    _step_start = time.time()
    print(f"\n▸ [5/9]  Merge & spatial enrichment …")
    result = plot_data.merge(veh_df, on="vehicle_id")

    # ── 6) Carriers, vehicle types, demand, costs ────────────────
    print(f"\n▸ [6/9]  Parsing carriers & demand …")
    print(f"         {os.path.basename(carrier_file)}")
    carriers = parse_carriers_from_xml(carrier_file)

    vtypes: dict | None = None
    if _vtf:
        vtypes = parse_vehicle_types(_vtf)
        print(f"         {len(vtypes)} vehicle types from {os.path.basename(_vtf)}")

    result = add_vehicle_demand_to_result(carriers, result, vtypes=vtypes)
    result = attach_service_times(result, startG, endG)

    # Apply costs ONCE (vectorised, outside per-vehicle loop)
    result = calculate_costs(
        result, vtypes=vtypes,
        time_cost_per_sec=cfg.vehicle_time_cost_per_sec,
    )

    # Spatial enrichment (coords, weights, delivery area)
    result = enrich_vehicle_spatial(result, carriers)

    # Build vehicle-status matrix
    vehicle_status_df = build_vehicle_status_matrix(result, startG, endG)
    _n_del = int(result["deliveries"].sum()) if "deliveries" in result.columns else 0
    print(f"    ✓  {len(carriers)} carriers · {len(result):,} vehicles · {_n_del:,} deliveries  ({format_runtime(time.time() - _step_start)})")

    # ── 7) Low-utilisation filter ────────────────────────────────
    print(f"\n▸ [7/9]  Low-utilisation filter (threshold {cfg.low_util_threshold:.0%}) …")
    nv_filtered = network_volumes[network_volumes["total_count"] > 0]
    result_f, tour_f, nv_filtered, low_ids = filter_low_utilisation(
        result, vehicle_tour, nv_filtered, cfg.low_util_threshold,
    )

    # ── 8) EV model ──────────────────────────────────────────────
    _step_start = time.time()
    print(f"\n▸ [8/9]  EV model (fleet target {cfg.ev_target:.0%}) …")
    ev_plan = compute_ev_counts(result, cfg=cfg)
    result = assign_ev_flags(result, ev_plan)
    eff_map = build_effective_class_map(result, cfg=cfg)
    base_map = build_base_class_map(result, cfg=cfg)
    is_ev_map = result.set_index("vehicle_id")["is_ev"].to_dict()
    ev_sanity_report(result, ev_plan, cfg=cfg)
    print(f"    ✓  EV model done  ({format_runtime(time.time() - _step_start)})")

    # ── 9) Emissions ─────────────────────────────────────────────
    _step_start = time.time()
    print(f"\n▸ [9/9]  Emissions ({cfg.emissions_basis}" + (" + WTT" if cfg.use_wtw else "") + ") …")
    link_ids = nv_filtered["link_id"].unique()
    print(f"         {len(link_ids):,} active links · {len(result):,} vehicles")
    em = run_emissions_loop(
        vehicle_tour=vehicle_tour,
        result=result,
        vehicle_status_df=vehicle_status_df,
        effective_class_map=eff_map,
        base_class_map=base_map,
        is_ev_map=is_ev_map,
        link_length=ref.link_length,
        link_type=ref.link_type,
        link_ids=link_ids,
        cfg=cfg,
    )
    print(f"    ✓  Emissions done  ({format_runtime(time.time() - _step_start)})")

    # Post-process emissions
    grid = em["emissions_grid"]
    wide_total = build_wide(grid, link_ids, "total")
    wide_drive = build_wide(grid, link_ids, "drive")
    wide_idle = build_wide(grid, link_ids, "idle")
    wide_cold = build_wide(grid, link_ids, "cold")
    nox_wide = build_wide_pollutant(grid, link_ids, "NOx_idle")
    hc_wide = build_wide_pollutant(grid, link_ids, "HC_idle")
    co_wide = build_wide_pollutant(grid, link_ids, "CO_idle")

    long_total = to_long(wide_total)
    nox_long = to_long(nox_wide, value_name="NOx_g")
    hc_long = to_long(hc_wide, value_name="HC_g")
    co_long = to_long(co_wide, value_name="CO_g")

    net_total = network_totals(long_total)
    net_nox = network_totals(nox_long.rename(columns={"NOx_g": "emissions_g"}))
    net_hc = network_totals(hc_long.rename(columns={"HC_g": "emissions_g"}))
    net_co = network_totals(co_long.rename(columns={"CO_g": "emissions_g"}))

    # Area-type breakdown
    by_area, pivot = emissions_by_area_type(long_total, ref.link_raumtyp)

    # Merged 15-min + network geometry
    merged_15min = nv_filtered.merge(long_total, on="link_id", how="left")

    # Per-vehicle emission column
    result = build_vehicle_emissions_column(
        result,
        em["vehicle_emissions_drive"],
        em["vehicle_emissions_idle"],
        em["vehicle_emissions_cold"],
        em["vehicle_emissions_total"],
    )

    # Area-type name
    result["main_area_type_name"] = (
        result["main_area_type"].astype(str)
        .map({str(k): v for k, v in AREA_TYPE_LABELS.items()})
        .fillna("Unknown")
    )

    # Derived KPIs (cost/parcel, speed, inter-stop distances, …)
    result = compute_derived_kpis(result)

    # ── Summary ──────────────────────────────────────────────────
    _total_em_kg = sum(em["vehicle_emissions_total"].values()) / 1000
    _n_ev_total = int(result["is_ev"].sum()) if "is_ev" in result.columns else 0
    _runtime = time.time() - t0
    print(f"\n{'─' * _W}")
    print(f"  ✅  Pipeline complete in {format_runtime(_runtime)}")
    print(f"{'─' * _W}")
    print(f"  Vehicles:    {len(result):>6,}   ({_n_ev_total} EVs, {len(result) - _n_ev_total} ICE)")
    print(f"  Deliveries:  {int(result['deliveries'].sum()):>6,}")
    print(f"  Emissions:   {_total_em_kg:>9,.1f} kg {cfg.emissions_basis}")
    print(f"  Low-util:    {len(low_ids):>6}   removed")
    print(f"{'═' * _W}\n")

    return {
        "emissions_result": result,
        "emissions_grid": grid,
        "emissions_wide_total": wide_total,
        "emissions_wide_drive": wide_drive,
        "emissions_wide_idle": wide_idle,
        "emissions_wide_cold": wide_cold,
        "idle_NOx_wide": nox_wide,
        "idle_HC_wide": hc_wide,
        "idle_CO_wide": co_wide,
        "emissions_15min_long": long_total,
        "idle_NOx_long": nox_long,
        "idle_HC_long": hc_long,
        "idle_CO_long": co_long,
        "network_totals_total": net_total,
        "network_totals_NOx": net_nox,
        "network_totals_HC": net_hc,
        "network_totals_CO": net_co,
        "network_15min_by_area": by_area,
        "network_15min_pivot": pivot,
        "merged_15min": merged_15min,
        "vehicle_emissions_total": em["vehicle_emissions_total"],
        "vehicle_emissions_drive": em["vehicle_emissions_drive"],
        "vehicle_emissions_idle": em["vehicle_emissions_idle"],
        "vehicle_emissions_cold": em["vehicle_emissions_cold"],
        "vehicle_emissions_dict": {
            vid: {"drive": em["vehicle_emissions_drive"].get(vid, 0.0),
                  "idle": em["vehicle_emissions_idle"].get(vid, 0.0),
                  "cold": em["vehicle_emissions_cold"].get(vid, 0.0),
                  "total": em["vehicle_emissions_total"].get(vid, 0.0)}
            for vid in em["vehicle_emissions_total"]
        },
        "veh_idle_NOx": em["veh_idle_NOx"],
        "veh_idle_HC": em["veh_idle_HC"],
        "veh_idle_CO": em["veh_idle_CO"],
        "vehicle_status_df": vehicle_status_df,
        "network_volumes": network_volumes,
        "network_volumes_filtered": nv_filtered,
    }


# ====================================================================
# Batch runner
# ====================================================================

def run_batch(
    batch_dir: str | Path,
    output_base: str | Path,
    ref: ReferenceData,
    cfg: RunConfig | None = None,
) -> pd.DataFrame:
    """Discover and process all MATSim runs in *batch_dir*.

    The base *cfg* is used as a template.  For each discovered run the
    ``event_file``, ``carrier_file``, and ``vehicle_types_file`` fields
    are replaced with the discovered paths.

    Parameters
    ----------
    batch_dir : path
        Root directory containing scenario sub-folders.
    output_base : path
        Where to write ``.pkl`` result bundles.
    ref : ReferenceData
        Pre-loaded spatial reference data.
    cfg : RunConfig, optional
        Template configuration (file-path fields are overridden per run).

    Returns
    -------
    DataFrame – summary of processed runs.
    """
    runs = discover_runs(batch_dir)
    if not runs:
        logger.warning("No runs found in %s", batch_dir)
        return pd.DataFrame()

    base_cfg = cfg or RunConfig()
    scenarios = sorted({r["scenario"] for r in runs})
    logger.info("Discovered %d runs across scenarios: %s", len(runs), scenarios)

    os.makedirs(output_base, exist_ok=True)
    summary_rows: list[dict] = []

    for i, r in enumerate(runs, 1):
        logger.info(
            "\n[%d/%d] %s / %s", i, len(runs), r["scenario"], r["run_name"],
        )
        out_dir = os.path.join(str(output_base), r["scenario"], r["run_name"])

        # Build a per-run config by replacing the file paths
        from dataclasses import replace as _replace
        run_cfg = _replace(
            base_cfg,
            event_file=r["event_file"],
            carrier_file=r["carrier_file"],
            vehicle_types_file=r.get("vehicle_types_file", base_cfg.vehicle_types_file),
        )

        try:
            results = process_single_run(run_cfg, ref)
            for key, obj in results.items():
                _save_pkl(obj, os.path.join(out_dir, f"{r['run_name']}_{key}"))

            summary_rows.append({
                "scenario": r["scenario"],
                "run_name": r["run_name"],
                "status": "ok",
            })
        except Exception as exc:
            logger.exception("Run %s/%s failed", r["scenario"], r["run_name"])
            summary_rows.append({
                "scenario": r["scenario"],
                "run_name": r["run_name"],
                "status": f"error: {exc}",
            })

    summary = pd.DataFrame(summary_rows)
    try:
        summary.to_parquet(os.path.join(str(output_base), "summary_impact.parquet"), index=False)
    except Exception:
        summary.to_pickle(os.path.join(str(output_base), "summary_impact.pkl"))
    logger.info("Batch complete. Summary at %s", output_base)
    return summary
