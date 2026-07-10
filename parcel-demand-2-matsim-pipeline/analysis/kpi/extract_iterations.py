# -*- coding: utf-8 -*-
"""Convergence-over-iterations rows per run -> kpi_iterations.csv
(run_id;series;iteration;value;unit). Sources: drt_customer_stats_drt.csv
(all rows), modestats.csv (all rows), carrier_scores.txt (optional; absent
for the real married250 run -> graceful skip, no crash)."""
import csv
from pathlib import Path

import pandas as pd


def _it(series, iteration, value, unit):
    return {"series": series, "iteration": int(iteration), "value": value, "unit": unit}


def extract(run_dir, prefix):
    run_dir = Path(run_dir)
    rows = []

    cs_f = run_dir / (prefix + ".drt_customer_stats_drt.csv")
    if cs_f.exists():
        cs = pd.read_csv(cs_f, sep=";")
        for _, r in cs.iterrows():
            it = r["iteration"]
            rides, rejections = int(r["rides"]), int(r["rejections"])
            rows.append(_it("drt_rides", it, rides, "trips"))
            rows.append(_it("drt_rejection_rate", it,
                             rejections / max(1, rides + rejections), "share"))
            rows.append(_it("wait_mean", it, float(r["wait_average"]), "s"))
            rows.append(_it("wait_p95", it, float(r["wait_p95"]), "s"))

    ms_f = run_dir / (prefix + ".modestats.csv")
    if ms_f.exists():
        ms = pd.read_csv(ms_f, sep=";")
        modes = [c for c in ms.columns if c != "iteration"]
        for _, r in ms.iterrows():
            it = r["iteration"]
            for mode in modes:
                rows.append(_it("modal_share_" + mode, it, float(r[mode]), "share"))

    cscore_f = run_dir / (prefix + ".carrier_scores.txt")
    if cscore_f.exists():
        sc = pd.read_csv(cscore_f, sep="\t")
        for _, r in sc.iterrows():
            it = r["iteration"]
            for col in ("executed", "worst", "avg", "best"):
                rows.append(_it("carrier_score_" + col, it, float(r[col]), "score"))

    return rows


def write(rows, meta, out_file):
    with open(out_file, "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f, delimiter=";")
        w.writerow(["run_id", "series", "iteration", "value", "unit"])
        for r in rows:
            v = r["value"]
            w.writerow([meta.run_id, r["series"], r["iteration"],
                        "{:.6g}".format(v) if isinstance(v, float) else v, r["unit"]])
