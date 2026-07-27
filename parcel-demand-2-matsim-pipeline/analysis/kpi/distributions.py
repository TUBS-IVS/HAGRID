# -*- coding: utf-8 -*-
"""Network-free binned distributions -> kpi_distributions.csv
(run_id;series;bin_lo;bin_hi;value;unit). Sources: output_drt_legs (wait
time), drt_service_time.reconstruct results (per-vehicle active duration,
occupancy segments), and TimeDistance_perVehicle.tsv (LMD tour distance /
duration). Network-dependent series (drt_tour_distance, occ_km) are emitted
only when the caller supplies veh_km / occ_km_shares (computed from the
MATSim network by build_kpis.build via geometry.py, Plan D Task 5) --
otherwise a dynamic deferred-note prints naming only the series still
absent."""
import csv
from pathlib import Path

import pandas as pd

import carriers_parse
import pax_only


def _dist(series, bin_lo, bin_hi, value, unit):
    return {"series": series, "bin_lo": bin_lo, "bin_hi": bin_hi,
            "value": value, "unit": unit}


def bin_fixed(values, width):
    """Fixed-width binning: v falls in [lo, lo+width) where
    lo = width*floor(v/width). Returns {(lo, hi): count}."""
    counts = {}
    for v in values:
        lo = width * int(v // width)
        hi = lo + width
        key = (lo, hi)
        counts[key] = counts.get(key, 0) + 1
    return counts


def bin_equal_width(values, n_bins):
    """Equal-width binning over the observed [min, max] range.
    Returns {(lo, hi): count} with n_bins bins. If all values are equal
    (zero range), a single bin spanning the value is returned."""
    values = list(values)
    if not values:
        return {}
    lo_all, hi_all = min(values), max(values)
    if hi_all == lo_all:
        return {(lo_all, hi_all): len(values)}
    width = (hi_all - lo_all) / n_bins
    counts = {}
    for v in values:
        idx = int((v - lo_all) / width)
        if idx >= n_bins:  # v == hi_all falls in the last bin
            idx = n_bins - 1
        b_lo = lo_all + idx * width
        b_hi = b_lo + width
        key = (b_lo, b_hi)
        counts[key] = counts.get(key, 0) + 1
    return counts


def extract(run_dir, prefix, recon=None, veh_km=None, occ_km_shares=None):
    run_dir = Path(run_dir)
    rows = []

    legs_f = run_dir / (prefix + ".output_drt_legs_drt.csv")
    if legs_f.exists():
        legs, _ = pax_only.split_parcels(pd.read_csv(legs_f, sep=";"), "personId")
        # Passenger wait distribution: parcel legs are excluded for the same reason
        # the wait KPIs are (pax_only.apply_overrides). No-op outside Shared-Use.
        for (lo, hi), n in bin_fixed(legs["waitTime"], 60).items():
            rows.append(_dist("drt_wait", lo, hi, int(n), "s"))

    if recon is not None:
        per_veh = recon.get("per_veh", {})
        durations_h = [v["active_s"] / 3600.0 for v in per_veh.values()]
        for (lo, hi), n in bin_equal_width(durations_h, 16).items():
            rows.append(_dist("drt_tour_duration", lo, hi, int(n), "h"))

        fleet = recon.get("fleet", {})
        seg_time = fleet.get("seg_time", {})
        tot_time = sum(seg_time.values())
        for lv in sorted(seg_time):
            share = (seg_time[lv] / tot_time) if tot_time else 0.0
            rows.append(_dist("occ_time", lv, lv, share, "share"))

        seg_count = fleet.get("seg_count", {})
        tot_count = sum(seg_count.values())
        for lv in sorted(seg_count):
            share = (seg_count[lv] / tot_count) if tot_count else 0.0
            rows.append(_dist("occ_segments", lv, lv, share, "share"))

    tv_f = run_dir / "analysis" / "freight" / "TimeDistance_perVehicle.tsv"
    if tv_f.exists():
        tv = pd.read_csv(tv_f, sep="\t")
        for (lo, hi), n in bin_fixed(tv["travelDistance[km]"], 10).items():
            rows.append(_dist("lmd_tour_distance", lo, hi, int(n), "km"))
        for (lo, hi), n in bin_fixed(tv["tourDuration[h]"], 0.5).items():
            rows.append(_dist("lmd_tour_duration", lo, hi, int(n), "h"))

    carriers_xml = run_dir / (prefix + ".output_carriers.xml.gz")
    if carriers_xml.exists():
        scores = [c.selected_plan_score for c in carriers_parse.parse_carriers(carriers_xml)
                  if c.selected_plan_score is not None]
        if scores:
            for (lo, hi), n in bin_equal_width(scores, 12).items():
                rows.append(_dist("lmd_carrier_score", lo, hi, int(n), "score"))

    if veh_km is not None:
        for (lo, hi), n in bin_equal_width(list(veh_km.values()), 16).items():
            rows.append(_dist("drt_tour_distance", lo, hi, int(n), "km"))

    if occ_km_shares is not None:
        for lv in sorted(occ_km_shares):
            rows.append(_dist("occ_km", lv, lv, occ_km_shares[lv], "share"))

    deferred = []
    if veh_km is None:
        deferred.append("drt_tour_distance")
    if occ_km_shares is None:
        deferred.append("occ_km")
    if deferred:
        print("[distributions] " + "/".join(deferred)
              + " deferred to Plan D (needs network km)")
    return rows


def write(rows, meta, out_file):
    with open(out_file, "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f, delimiter=";")
        w.writerow(["run_id", "series", "bin_lo", "bin_hi", "value", "unit"])
        for r in rows:
            lo, hi, v = r["bin_lo"], r["bin_hi"], r["value"]
            lo_s = "{:.6g}".format(lo) if isinstance(lo, float) else lo
            hi_s = "{:.6g}".format(hi) if isinstance(hi, float) else hi
            v_s = "{:.6g}".format(v) if isinstance(v, float) else v
            w.writerow([meta.run_id, r["series"], lo_s, hi_s, v_s, r["unit"]])
