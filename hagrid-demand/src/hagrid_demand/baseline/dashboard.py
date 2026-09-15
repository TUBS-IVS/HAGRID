"""Offline, consolidated entry point for deterministic baseline reports."""

from __future__ import annotations

import html
import json
import math
from numbers import Real
import os
from pathlib import Path
import uuid

from hagrid_demand.common.provenance import resource_hash


_REPORT_ARTIFACTS = ("report_data.json", "report.md")
_REPORT_SCHEMA = {
    "run_id", "reference_year", "operating_days", "regional_annual", "postal",
    "excluded_quantities", "b2b_adjustment", "remaining_potentials", "status",
}


def _valid_report(value: object, run_id: str) -> bool:
    if not isinstance(value, dict) or value.get("run_id") != run_id or value.get("status") != "complete_reference":
        return False
    if type(value.get("reference_year")) is not int or value["reference_year"] != 2021:
        return False
    if type(value.get("operating_days")) is not int or value["operating_days"] <= 0:
        return False
    annual = value.get("regional_annual")
    if isinstance(annual, bool) or not isinstance(annual, Real):
        return False
    try:
        if not math.isfinite(float(annual)):
            return False
    except (OverflowError, TypeError, ValueError):
        return False
    return isinstance(value.get("postal"), list) and isinstance(value.get("excluded_quantities"), dict) and isinstance(value.get("b2b_adjustment"), dict) and isinstance(value.get("remaining_potentials"), dict)


def _restore(source: Path, target: Path) -> None:
    temporary = target.with_name(f".{target.name}.{uuid.uuid4().hex}.tmp")
    temporary.write_bytes(source.read_bytes())
    os.replace(temporary, target)


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


def _report_stage(manifest: object) -> dict:
    if not isinstance(manifest, dict):
        raise ValueError("baseline run has incomplete stage manifest")
    stages = manifest.get("stages")
    if not isinstance(stages, dict) or not {"reference", "report"}.issubset(stages):
        raise ValueError("baseline run has incomplete stage manifest")
    report = stages["report"]
    if not isinstance(report, dict) or not isinstance(report.get("run_artifacts"), dict):
        raise ValueError("baseline run has incomplete stage manifest")
    return report


def _verify_and_restore_report_artifacts(run: Path, report_stage: dict) -> None:
    """Validate each immutable report copy before atomically repairing its public mirror."""
    artifacts = report_stage["run_artifacts"]
    for name in _REPORT_ARTIFACTS:
        expected = artifacts.get(f"report/{name}")
        source = run / "report" / name
        public = run / name
        if not isinstance(expected, str) or not source.is_file() or resource_hash(source) != expected:
            raise ValueError("baseline report artifact hash mismatch")
        if not public.is_file() or resource_hash(public) != expected:
            _restore(source, public)


def _candidate_report(candidate: Path) -> tuple[dict, Path] | None:
    """Return one complete candidate report while containing all sibling corruption."""
    try:
        data = candidate / "report_data.json"
        if not data.is_file() or not (candidate / "run.json").is_file():
            return None
        state = json.loads((candidate / "run.json").read_text(encoding="utf-8"))
        manifest = json.loads((candidate / "stage_manifest.json").read_text(encoding="utf-8"))
        report = json.loads(data.read_text(encoding="utf-8"))
        if not isinstance(state, dict) or not isinstance(manifest, dict) or not isinstance(report, dict):
            return None
        stages = manifest.get("stages")
        if not isinstance(stages, dict) or not {"reference", "report"}.issubset(stages):
            return None
        report_stage = stages.get("report")
        if not isinstance(report_stage, dict):
            return None
        artifacts = report_stage.get("run_artifacts")
        if not isinstance(artifacts, dict):
            return None
        expected = artifacts.get("report/report_data.json")
        if (
            state.get("status") == "complete_reference"
            and isinstance(expected, str)
            and expected == resource_hash(data)
            and _REPORT_SCHEMA.issubset(report)
            and _valid_report(report, candidate.name)
        ):
            return report, data
    except (OSError, TypeError, KeyError, ValueError, AttributeError, json.JSONDecodeError):
        return None
    return None


def render_baseline(run: Path) -> Path:
    """Refresh the shared index from a completed run's verified report artifacts."""
    run = Path(run)
    try:
        state = json.loads((run / "run.json").read_text(encoding="utf-8"))
        if not isinstance(state, dict) or state.get("status") != "complete_reference":
            raise ValueError("baseline run is not complete_reference")
    except (OSError, TypeError, ValueError, AttributeError, json.JSONDecodeError) as exc:
        raise ValueError("baseline run is incomplete") from exc
    try:
        manifest = json.loads((run / "stage_manifest.json").read_text(encoding="utf-8"))
        _verify_and_restore_report_artifacts(run, _report_stage(manifest))
    except ValueError:
        raise
    except (OSError, TypeError, KeyError, AttributeError, json.JSONDecodeError) as exc:
        raise ValueError("baseline run has incomplete stage manifest") from exc
    try:
        config = json.loads((run / "config.resolved.json").read_text(encoding="utf-8"))
        if not isinstance(config, dict):
            raise ValueError("baseline config is incomplete")
        dashboard_root = Path(config.get("dashboard_root") or Path(config["output_dir"]) / "dashboard")
    except (OSError, TypeError, KeyError, ValueError, AttributeError, json.JSONDecodeError) as exc:
        raise ValueError("baseline run has incomplete configuration") from exc
    dashboard_root.mkdir(parents=True, exist_ok=True)
    reports = [report for candidate in sorted(Path(config["output_dir"]).iterdir(), key=lambda item: item.name)
               if (report := _candidate_report(candidate)) is not None]
    reports.sort(key=lambda item: str(item[0]["run_id"]))
    temporary = dashboard_root / f".index.{uuid.uuid4().hex}.tmp"
    temporary.write_text(_index(reports, dashboard_root), encoding="utf-8")
    os.replace(temporary, dashboard_root / "index.html")
    return dashboard_root / "index.html"
