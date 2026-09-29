# -*- coding: utf-8 -*-
"""Cross-scenario comparison dashboard from N runs' canonical KPI CSVs.

Usage (from analysis/lausitz/kpi/):
    python -u build_comparison.py --runs <runDirA> <runDirB> [--out <file>] [--build-missing] [--no-events]
"""
import argparse
from pathlib import Path

import build_kpis
import config_diff
import render
from run_meta import load_run_meta


def _output_config(run_dir, prefix):
    """<prefix>.output_config.xml, else the run dir's only *.output_config.xml, else None."""
    exact = Path(run_dir) / (prefix + ".output_config.xml")
    if exact.exists():
        return exact
    found = list(Path(run_dir).glob("*.output_config.xml"))
    return found[0] if len(found) == 1 else None


def config_checks(runs):
    """Every run's full config diff against the FIRST run (the reference of the page).
    Shown, not enforced: a scenario comparison differs in parameters by design; the point is
    that a reader sees whether it differs ONLY in those (METHODS-LOG 3.14)."""
    base = runs[0]
    out = []
    for r in runs[1:]:
        missing = [x["label"] for x in (base, r) if x["config"] is None]
        result = None if missing else config_diff.diff(base["config"], r["config"])
        out.append({"base": base["label"], "other": r["label"], "missing": missing,
                    "result": result})
    return out


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
        runs.append({"label": label, "scenario": meta.scenario, "data": data,
                     "config": _output_config(d, meta.prefix)})

    if out_file is None:
        cmp_dir = Path(run_dirs[0]).parent / "comparison"
        cmp_dir.mkdir(exist_ok=True)
        out_file = cmp_dir / ("comparison_" + "_vs_".join(r["label"] for r in runs) + ".html")
    html = render.render_comparison_page(runs, title="Szenario-Vergleich: "
                                         + ", ".join(r["label"] for r in runs),
                                         config_checks=config_checks(runs))
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
