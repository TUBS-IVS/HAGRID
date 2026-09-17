# -*- coding: utf-8 -*-
"""Cross-scenario comparison dashboard from N runs' canonical KPI CSVs.

Usage (from analysis/kpi/):
    python -u build_comparison.py --runs <runDirA> <runDirB> [--out <file>] [--build-missing] [--no-events]
"""
import argparse
from pathlib import Path

import build_kpis
import render
from run_meta import load_run_meta


def build_comparison(run_dirs, out_file=None, build_missing=False, no_events=False):
    runs = []
    for d in run_dirs:
        d = Path(d)
        meta = load_run_meta(d)
        analysis = d / "analysis"
        if not (analysis / "kpis_long.csv").exists():
            if not build_missing:
                raise FileNotFoundError(str(analysis / "kpis_long.csv")
                                        + " (run build_kpis.py first or pass --build-missing)")
            build_kpis.build(d, no_events=no_events)
        data = render.load_run_data(analysis)
        label = meta.tag if meta.tag else meta.run_id
        runs.append({"label": label, "scenario": meta.scenario, "data": data})

    if out_file is None:
        cmp_dir = Path(run_dirs[0]).parent / "comparison"
        cmp_dir.mkdir(exist_ok=True)
        out_file = cmp_dir / ("comparison_" + "_vs_".join(r["label"] for r in runs) + ".html")
    html = render.render_comparison_page(runs, title="Szenario-Vergleich: "
                                         + ", ".join(r["label"] for r in runs))
    Path(out_file).write_text(html, encoding="utf-8")
    print("comparison dashboard: " + str(out_file))
    return Path(out_file)


def main():
    ap = argparse.ArgumentParser(description="Cross-scenario KPI comparison dashboard")
    ap.add_argument("--runs", nargs="+", required=True)
    ap.add_argument("--out", default=None)
    ap.add_argument("--build-missing", action="store_true")
    ap.add_argument("--no-events", action="store_true")
    a = ap.parse_args()
    build_comparison(a.runs, out_file=a.out, build_missing=a.build_missing,
                     no_events=a.no_events)


if __name__ == "__main__":
    main()
