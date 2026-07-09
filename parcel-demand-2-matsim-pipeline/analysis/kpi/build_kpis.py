# -*- coding: utf-8 -*-
"""Build the canonical KPI CSVs (+ dashboard, Task 9) for ONE run directory.

Usage (from analysis/kpi/):
    python -u build_kpis.py --run-dir ../../hagrid-matsim-output/DRT_BASELINE_13052025_married120_iter150_jsprit100
"""
import argparse
from pathlib import Path

import economics
import extract_drt
import extract_freight
import kpi_writer
import timeseries
from events_cache import ensure_caches
from run_meta import load_run_meta

# 1c/1d register their scenario-specific extractors here:
# each entry: (predicate(run_dir, meta) -> bool, extract(run_dir, prefix) -> rows)
EXTRACTORS = []


def _default_fleet_file(run_dir, meta):
    # <module-root>/hagrid-output/<run_id>/<run_id>_drt_fleet.xml.gz
    cand = run_dir.parent.parent / "hagrid-output" / meta.run_id / (meta.run_id + "_drt_fleet.xml.gz")
    return cand if cand.exists() else None


def build(run_dir, no_events=False, fleet_file=None, out_dir=None):
    run_dir = Path(run_dir)
    meta = load_run_meta(run_dir)
    out = Path(out_dir) if out_dir else run_dir / "analysis"
    out.mkdir(parents=True, exist_ok=True)

    is_drt = (run_dir / (meta.prefix + ".drt_customer_stats_drt.csv")).exists()
    has_freight = (run_dir / "analysis" / "freight" / "TimeDistance_perCarrier.tsv").exists()

    drt_cache = frt_cache = None
    if not no_events and (run_dir / (meta.prefix + ".output_events.xml.gz")).exists():
        drt_cache, frt_cache = ensure_caches(run_dir, meta.prefix)

    fleet = Path(fleet_file) if fleet_file else _default_fleet_file(run_dir, meta)

    rows = []
    if is_drt:
        rows += extract_drt.extract(run_dir, meta.prefix, fleet_file=fleet,
                                    drt_events_cache=drt_cache if is_drt else None)
    if has_freight:
        rows += extract_freight.extract(run_dir, meta.prefix)
    for predicate, extract_fn in EXTRACTORS:
        if predicate(run_dir, meta):
            rows += extract_fn(run_dir, meta.prefix)
    rows += economics.extract(rows, fleet_size=meta.fleet_size)

    kpi_writer.write_long(rows, meta, out / "kpis_long.csv")
    kpi_writer.write_wide(rows, meta, out / "kpis_wide.csv")
    ts = timeseries.extract(run_dir, meta.prefix, freight_cache=frt_cache)
    timeseries.write(ts, meta, out / "kpi_timeseries.csv")

    print("KPI CSVs written to " + str(out) + " (" + str(len(rows)) + " KPIs, "
          + str(len(ts)) + " timeseries points)")
    return out


def main():
    ap = argparse.ArgumentParser(description="Canonical KPI CSVs for one HAGRID run")
    ap.add_argument("--run-dir", required=True)
    ap.add_argument("--no-events", action="store_true",
                    help="skip event-based KPIs (service time, freight stops/h)")
    ap.add_argument("--fleet-file", default=None)
    ap.add_argument("--out-dir", default=None)
    a = ap.parse_args()
    build(a.run_dir, no_events=a.no_events, fleet_file=a.fleet_file, out_dir=a.out_dir)


if __name__ == "__main__":
    main()
