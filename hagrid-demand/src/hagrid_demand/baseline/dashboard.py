"""Offline, consolidated entry point for deterministic baseline reports."""

from __future__ import annotations

import html
import json
import math
import os
from pathlib import Path
import uuid

import pandas as pd

from hagrid_demand.common.provenance import resource_hash


def _valid_report(value: object, run_id: str) -> bool:
    if not isinstance(value, dict) or value.get("run_id") != run_id or value.get("status") != "complete_reference":
        return False
    if type(value.get("reference_year")) is not int or value["reference_year"] != 2021:
        return False
    if type(value.get("operating_days")) is not int or value["operating_days"] <= 0:
        return False
    if not isinstance(value.get("regional_annual"), (int, float)) or not math.isfinite(value["regional_annual"]):
        return False
    return isinstance(value.get("postal"), list) and isinstance(value.get("excluded_quantities"), dict) and isinstance(value.get("b2b_adjustment"), dict) and isinstance(value.get("remaining_potentials"), dict)


def _write_json(path: Path, value: dict) -> None:
    temporary = path.with_name(f".{path.name}.{uuid.uuid4().hex}.tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    os.replace(temporary, path)


def _write_text(path: Path, value: str) -> None:
    temporary = path.with_name(f".{path.name}.{uuid.uuid4().hex}.tmp")
    temporary.write_text(value, encoding="utf-8")
    os.replace(temporary, path)


def _restore(source: Path, target: Path) -> None:
    temporary = target.with_name(f".{target.name}.{uuid.uuid4().hex}.tmp")
    temporary.write_bytes(source.read_bytes())
    os.replace(temporary, target)


def _report_data(run: Path) -> dict:
    postal = pd.read_parquet(run / "reference_postal.parquet")
    checks = json.loads((run / "reference_checks.json").read_text(encoding="utf-8"))
    config = json.loads((run / "config.resolved.json").read_text(encoding="utf-8"))
    return {
        "run_id": run.name,
        "reference_year": config["reference_year"],
        "operating_days": config["reference_operating_days"],
        "regional_annual": float(postal.reference_annual.sum()),
        "postal": json.loads(postal.to_json(orient="records")),
        "excluded_quantities": checks["scope_ledger"],
        "b2b_adjustment": {key: checks[key] for key in ("b2b_target", "b2b_achieved", "b2b_residual", "k", "k_status", "log_k")},
        "remaining_potentials": {
            "unknown_plz_sites": checks.get("source_quality", {}).get("unknown_plz_sites", []),
            "known_plz_outside_anchor_sites": checks.get("source_quality", {}).get("known_plz_outside_anchor_sites", []),
        },
        "status": "complete_reference",
    }


def _index(reports: list[tuple[dict, Path]], dashboard_root: Path) -> str:
    def href(path: Path) -> str:
        try:
            return Path(os.path.relpath(path, dashboard_root)).as_posix()
        except ValueError:
            return path.resolve().as_uri()
    rows = "".join(
        "<tr><td>{run}</td><td>{year}</td><td>{days}</td><td>{annual:,.1f}</td><td><a href=\"{href}\">Daten</a></td></tr>".format(
            run=html.escape(str(data["run_id"])), year=data["reference_year"], days=data["operating_days"],
            annual=float(data["regional_annual"]),
            href=html.escape(href(path), quote=True),
        ) for data, path in reports
    ) or "<tr><td colspan=\"5\">Noch kein Referenzlauf vorhanden.</td></tr>"
    return """<!doctype html><html lang=\"de\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">
<title>HAGRID Referenzdashboard</title><style>body{font:16px system-ui,sans-serif;margin:0;background:#f4f7f8;color:#18303b}header,main{max-width:960px;margin:auto;padding:28px}header{background:#173d50;color:#fff;max-width:none;padding-left:max(calc((100vw - 960px)/2),28px)}table{width:100%;border-collapse:collapse;background:#fff}th,td{padding:12px;text-align:left;border-bottom:1px solid #dce5e8}th{background:#e9f0f2}a{color:#126a89}.note{line-height:1.55;color:#41606d}</style></head><body>
<header><small>HAGRID / DETERMINISTISCHE REFERENZ</small><h1>Gemeinsames Referenzdashboard</h1></header><main>
<p class=\"note\">Dieser Einstieg bündelt abgeschlossene Referenzläufe. Jeder Lauf behält seine eigenen, reproduzierbaren Berichtsdaten. Tagesläufe folgen erst mit Plan 02.</p>
<table><thead><tr><th>Run</th><th>Jahr</th><th>Betriebstage</th><th>Jahresmenge</th><th>Bericht</th></tr></thead><tbody>""" + rows + "</tbody></table></main></body></html>"


def render_baseline(run: Path) -> Path:
    """Refresh the shared index from a completed run's verified report artifacts."""
    run = Path(run)
    try:
        state = json.loads((run / "run.json").read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ValueError("baseline run is incomplete") from exc
    if state.get("status") != "complete_reference":
        raise ValueError("baseline run is not complete_reference")
    try:
        manifest = json.loads((run / "stage_manifest.json").read_text(encoding="utf-8"))
        stages = manifest["stages"]
        if not all(name in stages for name in ("reference", "report")):
            raise ValueError("baseline run has incomplete stage manifest")
        expected = stages["report"].get("run_artifacts", {}).get("report/report_data.json")
        public = run / "report_data.json"
        if expected != resource_hash(public) if public.is_file() else True:
            cached = run / "report" / "report_data.json"
            if not cached.is_file() or expected != resource_hash(cached):
                raise ValueError("baseline report artifact hash mismatch")
            _restore(cached, public)
            markdown = run / "report" / "report.md"
            if markdown.is_file():
                _restore(markdown, run / "report.md")
    except (OSError, KeyError, TypeError, json.JSONDecodeError) as exc:
        raise ValueError("baseline run has incomplete stage manifest") from exc
    config = json.loads((run / "config.resolved.json").read_text(encoding="utf-8"))
    report_path = run / "report_data.json"
    if not report_path.is_file():
        raise ValueError("baseline run has no complete report artifacts")
    dashboard_root = Path(config.get("dashboard_root") or Path(config["output_dir"]) / "dashboard")
    dashboard_root.mkdir(parents=True, exist_ok=True)
    reports = []
    for candidate in sorted(Path(config["output_dir"]).iterdir(), key=lambda item: item.name):
        data = candidate / "report_data.json"
        if data.is_file() and (candidate / "run.json").is_file():
            try:
                candidate_state = json.loads((candidate / "run.json").read_text(encoding="utf-8"))
                candidate_data = json.loads(data.read_text(encoding="utf-8"))
                candidate_manifest = json.loads((candidate / "stage_manifest.json").read_text(encoding="utf-8"))
                schema = {"run_id", "reference_year", "operating_days", "regional_annual", "postal", "excluded_quantities", "b2b_adjustment", "remaining_potentials", "status"}
                expected = candidate_manifest.get("stages", {}).get("report", {}).get("run_artifacts", {}).get("report/report_data.json")
                if candidate_state.get("status") == "complete_reference" and expected == resource_hash(data) and {"reference", "report"}.issubset(candidate_manifest.get("stages", {})) and schema.issubset(candidate_data) and _valid_report(candidate_data, candidate.name):
                    reports.append((candidate_data, data))
            except (OSError, TypeError, json.JSONDecodeError):
                continue
    reports = sorted(reports, key=lambda item: str(item[0]["run_id"]))
    temporary = dashboard_root / f".index.{uuid.uuid4().hex}.tmp"
    temporary.write_text(_index(reports, dashboard_root), encoding="utf-8")
    os.replace(temporary, dashboard_root / "index.html")
    return dashboard_root / "index.html"
