import argparse
import json
from datetime import datetime, timezone
import re


def main():
    parser = argparse.ArgumentParser(description="Reproducible HAGRID demand estimation and evaluation")
    sub = parser.add_subparsers(dest="command", required=True)
    diagnostic = sub.add_parser("diagnose-2021", help="Repeated postal CV without growth or daily simulation")
    diagnostic.add_argument("--config", required=True)
    diagnostic.add_argument("--special-customers", required=True)
    diagnostic.add_argument("--run-id", default=None)
    foundation = sub.add_parser("foundation", help="Manifest, sites, observations and candidate links")
    foundation.add_argument("--config", required=True)
    foundation.add_argument("--run-id", default=None)
    dashboard = sub.add_parser("dashboard", help="Rebuild offline HTML dashboard from an existing run")
    dashboard.add_argument("--run-dir", required=True)
    spatial = sub.add_parser("spatial-demo", help="Equal-total fixed versus spatially varying demand experiment")
    spatial.add_argument("--config", required=True)
    spatial.add_argument("--run-id", default=None)
    model = sub.add_parser("run", help="Fit, compare, freeze, forecast, sample carriers and export delivery")
    model.add_argument("--config", required=True)
    model.add_argument("--run-id", default=None)
    apply = sub.add_parser("predict", help="Apply a frozen model without refitting")
    apply.add_argument("--config", required=True)
    apply.add_argument("--model-run", required=True)
    apply.add_argument("--run-id", default=None)
    logistics = sub.add_parser("logistics-audit", help="Resume historical/current OSM downloads and audit Langenhagen")
    logistics.add_argument("--foundation", required=True)
    logistics.add_argument("--output", required=True)
    logistics.add_argument("--regional", action="store_true")
    logistics.add_argument("--snapshots", nargs="+", choices=["2021", "current"],default=["current","2021"])
    street = sub.add_parser("street-reference", help="Reconstruct DHL 2021 at streets with an explicit unallocated ledger")
    street.add_argument("--model-run", required=True)
    street.add_argument("--output", required=True)
    baseline = sub.add_parser("baseline", help="Deterministic demand baseline commands")
    baseline_sub = baseline.add_subparsers(dest="baseline_command")
    for name, help_text in [
        ("run", "Build the deterministic reference baseline"),
        ("simulate", "Run a configured baseline simulation"),
        ("sensitivity", "Run a configured baseline sensitivity analysis"),
        ("report", "Render an existing baseline run"),
        ("osm-clip", "Clip a Geofabrik OSM extract to the study region"),
        ("export-day", "Write the MATSim shapefile of one day from a run's annual store"),
        ("annual-dashboard", "Render the annual calendar dashboard of a run"),
    ]:
        command = baseline_sub.add_parser(name, help=help_text)
        if name == "osm-clip":
            command.add_argument("--pbf", required=True)
            command.add_argument("--plz", required=True)
            command.add_argument("--out", required=True)
            command.add_argument("--buffer-m", type=float, default=250.)
        elif name == "report":
            command.add_argument("--run-dir", required=True)
        elif name == "export-day":
            command.add_argument("--run", required=True)
            command.add_argument("--date", required=True)
            command.add_argument("--out", default=None)
        elif name == "annual-dashboard":
            command.add_argument("--run", required=True)
            command.add_argument("--out", required=True)
            command.add_argument("--year", type=int, default=None)
        else:
            command.add_argument("--config", required=True)
            command.add_argument("--run-id", default=None)
            command.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    if args.command == "baseline":
        try:
            if args.baseline_command == "osm-clip":
                from .baseline.osm import clip_osm_region
                print(json.dumps(clip_osm_region(args.pbf, args.plz, args.out, args.buffer_m), indent=2, ensure_ascii=False))
                return 0
            if args.baseline_command == "annual-dashboard":
                from .baseline.annual_dashboard import write_annual_dashboard
                print(f"Annual dashboard: {write_annual_dashboard(args.run, args.out, args.year)}")
                return 0
            if args.baseline_command == "export-day":
                from .baseline.annual import export_day
                print(json.dumps(export_day(args.run, args.date, args.out), indent=2, ensure_ascii=False))
                return 0
            if args.baseline_command == "report":
                from .baseline.dashboard import render_baseline
                print(f"Baseline dashboard: {render_baseline(args.run_dir)}")
                return 0
            if args.baseline_command in {"simulate", "sensitivity"}:
                parser.error(f"baseline {args.baseline_command} follows Plan 02; use baseline run for the reference")
            from .baseline.dashboard import render_baseline
            from .baseline.workflow import run_baseline
            run = run_baseline(args.config, args.run_id or datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ"), args.resume)
            print(f"Baseline dashboard: {render_baseline(run)}")
            return 0
        except (ValueError, FileExistsError, FileNotFoundError, NotImplementedError) as exc:
            parser.exit(2, f"Baseline failed: {exc}\n")
    if args.command == "logistics-audit":
        from .logistics_osm import main as audit
        audit(["--foundation", args.foundation, "--output", args.output] + (["--regional"] if args.regional else []) + ["--snapshots",*args.snapshots])
        return 0
    if args.command == "street-reference":
        from .street_reconstruct import main as reconstruct
        reconstruct(["--model-run", args.model_run, "--output", args.output])
        return 0
    if args.command == "dashboard":
        from .dashboard import build_dashboard
        print(f"Dashboard: {build_dashboard(args.run_dir)}")
        return 0
    run_id = args.run_id or datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,100}", run_id):
        parser.error("run-id must contain only letters, numbers, underscores and hyphens")
    try:
        if args.command == 'diagnose-2021':
            from .diagnostics import run_diagnostics
            run = run_diagnostics(args.config, run_id, args.special_customers)
            print(f"Diagnosis: {run / 'dashboard.html'}")
            return 0
        if args.command in {"run", "predict"}:
            from .workflow import run_model
            run = run_model(args.config, run_id, getattr(args, "model_run", None))
            print(f"Model dashboard: {run / 'dashboard.html'}")
            return 0
        if args.command == "spatial-demo":
            from .spatial import run_spatial
            run = run_spatial(args.config, run_id)
            print(f"Spatial comparison: {run / 'dashboard.html'}")
            return 0
        from .pipeline import run_foundation
        run = run_foundation(args.config, run_id)
    except (ValueError, FileExistsError, FileNotFoundError) as exc:
        parser.exit(2, f"Data foundation failed: {exc}\n")
    print(f"Report: {run / 'report.md'}")
    print(f"Dashboard: {run / 'dashboard.html'}")
    return 0
