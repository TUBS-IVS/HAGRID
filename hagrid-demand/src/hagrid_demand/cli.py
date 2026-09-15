import argparse
from datetime import datetime, timezone
import re

from .pipeline import run_foundation
from .dashboard import build_dashboard
from .spatial import run_spatial
from .workflow import run_model


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
    args = parser.parse_args()
    if args.command == "logistics-audit":
        from .logistics_osm import main as audit
        audit(["--foundation", args.foundation, "--output", args.output] + (["--regional"] if args.regional else []) + ["--snapshots",*args.snapshots])
        return 0
    if args.command == "street-reference":
        from .street_reconstruct import main as reconstruct
        reconstruct(["--model-run", args.model_run, "--output", args.output])
        return 0
    if args.command == "dashboard":
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
            run = run_model(args.config, run_id, getattr(args, "model_run", None))
            print(f"Model dashboard: {run / 'dashboard.html'}")
            return 0
        if args.command == "spatial-demo":
            run = run_spatial(args.config, run_id)
            print(f"Spatial comparison: {run / 'dashboard.html'}")
            return 0
        run = run_foundation(args.config, run_id)
    except (ValueError, FileExistsError, FileNotFoundError) as exc:
        parser.exit(2, f"Data foundation failed: {exc}\n")
    print(f"Report: {run / 'report.md'}")
    print(f"Dashboard: {run / 'dashboard.html'}")
    return 0
