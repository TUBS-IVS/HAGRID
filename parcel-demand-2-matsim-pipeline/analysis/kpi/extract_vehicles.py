# -*- coding: utf-8 -*-
"""Per-vehicle KPI CSV (freight + DRT rows), v2 Plan C Task 3.

Rendering/drilldown data source: one row per vehicle (freight tour or DRT
vehicle) so a later rendering task can build scatters/per-vehicle bars
without re-parsing carriers XML or event reconstructions.

Freight rows come from `extract_freight_provider.parse_run(...).vehrecords`
joined to `TimeDistance_perVehicle.tsv` (indexed by vehicleId) for
distance/duration/travel-time. DRT rows come straight from
`recon["per_veh"]` (already computed for drt-headline/distributions).
Either source is optional -- a DRT-only run emits only drt rows, a
freight-only run emits only freight rows.
"""
import csv
from pathlib import Path

import pandas as pd

import extract_freight_provider as efp

COLUMNS = ["run_id", "role", "vehicle_id", "provider", "vehicle_type",
           "distance_km", "duration_h", "travel_h", "parcels", "stops",
           "load_factor", "excluded", "occupied_h", "active_h", "shift_h",
           "ratio_active"]


def _freight_rows(run_dir, prefix, pf):
    run_dir = Path(run_dir)
    if pf is None:
        try:
            pf = efp.parse_run(run_dir, prefix)
        except (FileNotFoundError, OSError) as e:
            print("[vehicles] freight rows skipped: " + str(e))  # ASCII only
            return []

    tv_file = run_dir / "analysis" / "freight" / "TimeDistance_perVehicle.tsv"
    tv = pd.read_csv(tv_file, sep="\t") if tv_file.exists() else pd.DataFrame()
    tv_by_id = {}
    if len(tv):
        for _, r in tv.iterrows():
            tv_by_id[r["vehicleId"]] = r

    rows = []
    for vr in pf.vehrecords:
        tv_row = tv_by_id.get(vr.event_vehicle_id)
        rows.append({
            "role": "freight",
            "vehicle_id": vr.event_vehicle_id,
            "provider": vr.provider,
            "vehicle_type": vr.vtype if vr.vtype is not None else vr.type_id,
            "distance_km": float(tv_row["travelDistance[km]"]) if tv_row is not None else None,
            "duration_h": float(tv_row["tourDuration[h]"]) if tv_row is not None else None,
            "travel_h": float(tv_row["travelTime[h]"]) if tv_row is not None else None,
            "parcels": vr.parcels,
            "stops": vr.stops,
            "load_factor": vr.load_factor,
            "excluded": 1 if vr.excluded else 0,
            "occupied_h": None,
            "active_h": None,
            "shift_h": None,
            "ratio_active": None,
        })
    return rows


def _drt_rows(recon):
    if not recon:
        return []
    rows = []
    for veh_id, v in recon.get("per_veh", {}).items():
        rows.append({
            "role": "drt",
            "vehicle_id": veh_id,
            "provider": "drt",
            "vehicle_type": "DRT",
            "distance_km": None,
            "duration_h": None,
            "travel_h": None,
            "parcels": None,
            "stops": None,
            "load_factor": None,
            "excluded": None,
            "occupied_h": v["occupied_s"] / 3600.0,
            "active_h": v["active_s"] / 3600.0,
            "shift_h": v["shift_s"] / 3600.0,
            "ratio_active": v["ratio_active"],
        })
    return rows


def extract(run_dir, prefix, recon=None, pf=None):
    rows = []
    rows += _freight_rows(run_dir, prefix, pf)
    rows += _drt_rows(recon)
    return rows


def _fmt(v):
    if v is None:
        return ""
    if isinstance(v, float):
        return "{:.6g}".format(v)
    return str(v)


def write(rows, meta, out_file):
    with open(out_file, "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f, delimiter=";")
        w.writerow(COLUMNS)
        for r in rows:
            w.writerow([meta.run_id] + [_fmt(r.get(c)) for c in COLUMNS[1:]])
