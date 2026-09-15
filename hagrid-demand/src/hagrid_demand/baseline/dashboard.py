"""Offline, consolidated entry point for deterministic baseline reports."""

from __future__ import annotations

import html
import json
import os
from pathlib import Path
import uuid

import pandas as pd


def _write_json(path: Path, value: dict) -> None:
    temporary = path.with_name(f".{path.name}.{uuid.uuid4().hex}.tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    os.replace(temporary, path)


def _write_text(path: Path, value: str) -> None:
    temporary = path.with_name(f".{path.name}.{uuid.uuid4().hex}.tmp")
    temporary.write_text(value, encoding="utf-8")
    os.replace(temporary, path)


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
    rows = "".join(
        "<tr><td>{run}</td><td>{year}</td><td>{days}</td><td>{annual:,.1f}</td><td><a href=\"{href}\">Daten</a></td></tr>".format(
            run=html.escape(str(data["run_id"])), year=data["reference_year"], days=data["operating_days"],
            annual=float(data["regional_annual"]),
            href=html.escape(Path(os.path.relpath(path, dashboard_root)).as_posix(), quote=True),
        ) for data, path in reports
    ) or "<tr><td colspan=\"5\">Noch kein Referenzlauf vorhanden.</td></tr>"
    return """<!doctype html><html lang=\"de\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">
<title>HAGRID Referenzdashboard</title><style>body{font:16px system-ui,sans-serif;margin:0;background:#f4f7f8;color:#18303b}header,main{max-width:960px;margin:auto;padding:28px}header{background:#173d50;color:#fff;max-width:none;padding-left:max(calc((100vw - 960px)/2),28px)}table{width:100%;border-collapse:collapse;background:#fff}th,td{padding:12px;text-align:left;border-bottom:1px solid #dce5e8}th{background:#e9f0f2}a{color:#126a89}.note{line-height:1.55;color:#41606d}</style></head><body>
<header><small>HAGRID / DETERMINISTISCHE REFERENZ</small><h1>Gemeinsames Referenzdashboard</h1></header><main>
<p class=\"note\">Dieser Einstieg bündelt abgeschlossene Referenzläufe. Jeder Lauf behält seine eigenen, reproduzierbaren Berichtsdaten. Tagesläufe folgen erst mit Plan 02.</p>
<table><thead><tr><th>Run</th><th>Jahr</th><th>Betriebstage</th><th>Jahresmenge</th><th>Bericht</th></tr></thead><tbody>""" + rows + "</tbody></table></main></body></html>"


def render_baseline(run: Path) -> Path:
    """Write a run's report data and refresh the single dashboard entry point."""
    run = Path(run)
    config = json.loads((run / "config.resolved.json").read_text(encoding="utf-8"))
    report = _report_data(run)
    _write_json(run / "report_data.json", report)
    scope = report["excluded_quantities"]
    adjustment = report["b2b_adjustment"]
    remaining = report["remaining_potentials"]
    _write_text(run / "report.md", f"""# HAGRID Referenzlauf: {report['run_id']}

Status: deterministische Referenz für {report['reference_year']}.

## Referenzannahmen

Die Jahresmenge beträgt {report['regional_annual']:,.1f} Pakete. Die Umrechnung verwendet
{report['operating_days']} Betriebstage pro Jahr. Diese Tagesmittel-Annahme ist eine dokumentierte
Rechenbasis und keine Aussage über einzelne Zustelltage.

## Ausgeschlossene Mengen

Der DHL-Scope schließt {scope['excluded_rows']} Beobachtungen mit {scope['excluded_volume']:,.1f}
Mengeneinheiten aus; {scope['retained_rows']} Beobachtungen mit {scope['retained_volume']:,.1f}
bleiben als Referenzanker erhalten.

## B2B-Anpassung

Zielanteil: {adjustment['b2b_target']:.6f}; erreicht: {adjustment['b2b_achieved']:.6f};
Residuum: {adjustment['b2b_residual']:.3g}. Der Log-Faktor der B2B-Anpassung beträgt
{adjustment['log_k']:.6g} ({adjustment['k_status']}).

## Restpotenziale

Standorte ohne PLZ: {len(remaining['unknown_plz_sites'])}. Standorte mit bekannter PLZ außerhalb
des DHL-Ankers: {len(remaining['known_plz_outside_anchor_sites'])}. Sie bleiben als Restpotenziale
dokumentiert und werden nicht stillschweigend verteilt.

Die maschinenlesbaren Werte stehen in `report_data.json`; der gemeinsame Offline-Einstieg ist
`../dashboard/index.html`.
""")
    dashboard_root = Path(config.get("dashboard_root") or Path(config["output_dir"]) / "dashboard")
    dashboard_root.mkdir(parents=True, exist_ok=True)
    reports = []
    for candidate in sorted(Path(config["output_dir"]).iterdir(), key=lambda item: item.name):
        data = candidate / "report_data.json"
        if data.is_file():
            try:
                reports.append((json.loads(data.read_text(encoding="utf-8")), data))
            except json.JSONDecodeError:
                continue
    reports = sorted(reports, key=lambda item: str(item[0]["run_id"]))
    temporary = dashboard_root / f".index.{uuid.uuid4().hex}.tmp"
    temporary.write_text(_index(reports, dashboard_root), encoding="utf-8")
    os.replace(temporary, dashboard_root / "index.html")
    return dashboard_root / "index.html"
