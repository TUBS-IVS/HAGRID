"""One offline, catalog-backed dashboard for frozen baseline reference runs."""

from __future__ import annotations

import html
import json
import math
from numbers import Real
import os
from pathlib import Path
import uuid

import pandas as pd

from hagrid_demand.common.cache import file_lock
from hagrid_demand.common.provenance import canonical_digest, resource_hash


_CATALOG_SCHEMA_VERSION = 1
_REPORT_SCHEMA_VERSION = 1
_STAGES = (
    ("overview", "Übersicht"),
    ("data_quality", "Daten/Qualität"),
    ("market_b2b", "Markt und B2B"),
    ("regional_reference", "Regionale Referenz"),
    ("future", "Zukunft/DHL-Gewicht"),
    ("calendar", "Kalender"),
    ("daily", "Tägliche Mengen und Orte"),
    ("carriers", "Anbieter"),
    ("monte_carlo", "Monte Carlo"),
    ("sensitivity", "Sensitivität"),
    ("checks", "Prüfungen/Exporte"),
)


def _atomic_text(path: Path, value: str) -> None:
    path = Path(path)
    temporary = path.with_name(f".{path.name}.{uuid.uuid4().hex}.tmp")
    temporary.write_text(value, encoding="utf-8")
    os.replace(temporary, path)


def _atomic_json(path: Path, value: dict) -> None:
    _atomic_text(path, json.dumps(value, sort_keys=True, indent=2, ensure_ascii=False, allow_nan=False) + "\n")


def _report_markdown(report: dict) -> str:
    scope, b2b, quality = report["excluded_quantities"], report["b2b_adjustment"], report["remaining_potentials"]
    text = (
        f"# HAGRID Referenzlauf: {report['run_id']}\n\n"
        f"Referenzjahr 2021. Die Tagesmittel-Annahme verwendet {report['operating_days']} Betriebstage.\n\n"
        "## Ausgeschlossene Beobachtungen\n\n"
        f"{scope['excluded_rows']} in-scope Beobachtungen / {scope['excluded_volume']:.1f} Mengeneinheiten ausgeschlossen. "
        f"Außerhalb des verifizierten Scope: {scope.get('out_of_scope_rows', 0)}.\n\n"
        "## B2B-Anpassung\n\n"
        f"Ziel {b2b['b2b_target']:.6f}; erreicht {b2b['b2b_achieved']:.6f}; Residuum {b2b['b2b_residual']:.3g}.\n\n"
        "## Restpotenziale\n\n"
        f"Unbekannte PLZ: {len(quality.get('unknown_plz_sites', []))}; bekannte PLZ außerhalb Anker: "
        f"{len(quality.get('known_plz_outside_anchor_sites', []))}.\n"
    )
    anchor = report.get("views", {}).get("anchor")
    if anchor:
        corrected = [row for row in anchor["corrections"] if row["applied"]]
        rates = anchor["rates_dhl_per_day"]
        text += (
            "\n## Straßen-Anker\n\n"
            f"DHL-B2B-Anteil aus den Straßendaten: {anchor['q_dhl']:.3f}. DHL-Raten je Tag: {rates['person']:.4f} je Einwohner, "
            f"{rates['company']:.3f} je Firma.\n\n"
            f"B2B-Anteil: beobachteter Teil {report['b2b_adjustment']['b2b_achieved']:.4f} (Ziel "
            f"{report['b2b_adjustment']['b2b_target']:.4f}); inklusive Rückfall {anchor.get('b2b_incl_fallback') or 0.:.4f}.\n\n"
            "Pegelkorrektur: " + (", ".join(f"{row['plz']} (Faktor {row['factor']:.2f})" for row in corrected) or "keine") + ".\n\n"
            "Tagesmenge nach Ankerstatus: " + ", ".join(f"{key} {value:,.0f}" for key, value in anchor["daily_by_status"].items()) + ".\n\n"
            "Holdout des Strukturmodells (PLZ-wMAPE): " + ", ".join(
                f"{name} {values['postal_wmape']:.1%}" for name, values in anchor["holdout"].items()) + ".\n\n"
            "Gebäude und Adressen: © OpenStreetMap contributors (ODbL), Stand 01.01.2021.\n"
        )
        zero = anchor.get("zero_street_units")
        if zero:
            text += (f"\nDHL-Straßen mit Wert 0 ohne Datenlücke: {zero['streets']} Straßen mit {zero['persons']:,.0f} Einwohnern "
                     f"und {zero['firms']:,.0f} Firmen erhalten keine Nachfrage.\n")
    stops = report.get("views", {}).get("stops")
    if stops and stops.get("days"):
        comparison = {row["date"]: row for row in (stops.get("notebook_comparison") or [])}
        text += ("\n## Tage und Stopps\n\n| Datum | Pakete | B2B | Stopps | Pakete/Stopp (Median) | belieferte Wohngebäude | "
                 "belieferte Firmengebäude | Notebook |\n|---|---|---|---|---|---|---|---|\n")
        for day in stops["days"]:
            share = day.get("active_share", {})
            other = comparison.get(day["date"])
            notebook = f"{other['diff_pct']:+.1f} %, PLZ-r {other['postal_correlation']:.3f}" if other else "–"
            per_stop = day.get("parcels_per_stop", {}).get("median")
            text += (f"| {day['date']} | {day['parcels']:,} | {day['b2b'] / day['parcels']:.1%} | {day.get('stops_active', day.get('features', 0)):,} | "
                     f"{per_stop if per_stop is not None else '–'} | {share.get('private', 0):.1%} | {share.get('business', 0):.1%} | {notebook} |\n"
                     if day.get("parcels") else f"| {day['date']} | 0 | – | 0 | – | – | – | {notebook} |\n")
    return text


def build_report_data(reference_dir: Path, config: dict | None = None, run_id: str | None = None,
                      baseline_fingerprint: str | None = None) -> dict:
    """Build display data only from the frozen semantic reference artifacts."""
    reference_dir = Path(reference_dir)
    if config is None:
        state, config, _ = _load_complete_run(reference_dir)
        run_id, baseline_fingerprint = state["run_id"], state["baseline_fingerprint"]
    if run_id is None or baseline_fingerprint is None:
        raise ValueError("run_id and baseline_fingerprint are required")
    postal = pd.read_parquet(reference_dir / "reference_postal.parquet")
    profiles = pd.read_parquet(reference_dir / "reference_carrier_profiles.parquet")
    checks = json.loads((reference_dir / "reference_checks.json").read_text(encoding="utf-8"))
    reconciliation = json.loads((reference_dir / "reference_reconciliation.json").read_text(encoding="utf-8"))
    regional = json.loads((reference_dir / "reference_regional_annual.json").read_text(encoding="utf-8"))
    required_postal = ["plz", "dhl_retained_mean", "reference_annual", "private_annual", "business_annual", "b2b_share", "dhl_share"]
    if postal.columns.tolist() != required_postal:
        raise ValueError("semantic reference postal schema is invalid")
    if not isinstance(regional.get("regional_annual"), Real) or isinstance(regional["regional_annual"], bool):
        raise ValueError("semantic regional annual amount is invalid")
    postal_rows = json.loads(postal.to_json(orient="records"))
    report = {
        "schema_version": _REPORT_SCHEMA_VERSION,
        "run_id": run_id,
        "baseline_fingerprint": baseline_fingerprint,
        "reference_year": config["reference_year"],
        "operating_days": config["reference_operating_days"],
        "regional_annual": float(regional["regional_annual"]),
        "postal": postal_rows,
        "excluded_quantities": checks["scope_ledger"],
        "b2b_adjustment": {key: checks[key] for key in (
            "b2b_target", "b2b_achieved", "b2b_residual", "k", "k_status", "log_k",
        )},
        "remaining_potentials": checks["source_quality"],
        "views": {
            "reference": {"postal": postal_rows, "regional_annual": float(regional["regional_annual"])},
            "market_b2b": {"reconciliation": reconciliation, "carrier_profiles": json.loads(profiles.to_json(orient="records"))},
            "quality": {"scope": checks["scope_ledger"], "source_quality": checks["source_quality"],
                        "allocation": checks.get("allocation_balance"), "eta": checks.get("eta_diagnostics")},
        },
        "status": "complete_reference",
    }
    anchor_path = reference_dir / "reference_anchor.json"
    if anchor_path.is_file():
        anchor = json.loads(anchor_path.read_text(encoding="utf-8"))
        sites = pd.read_parquet(reference_dir / "reference_sites.parquet")
        persons = sites[sites.segment.eq("private")].groupby("plz").population.sum()
        annual = (sites.groupby(["plz", "segment"]).reference_annual.sum().unstack(fill_value=0.)
                  .reindex(columns=["private", "business"], fill_value=0.))
        total = annual.sum(axis=1)
        residents = persons.reindex(annual.index)
        plausibility = pd.DataFrame({
            "plz": annual.index.astype(str),
            "parcels_per_person_year": (total / residents).where(residents > 0).to_numpy(),
            "b2c_parcels_per_person_year": (annual.private / residents).where(residents > 0).to_numpy(),
            "b2b_share": (annual.business / total).where(total > 0).to_numpy()})
        anchor["plausibility"] = json.loads(plausibility.to_json(orient="records"))
        for candidate in (reference_dir / "buildings", reference_dir.parent / "buildings"):
            if (candidate / "buildings_report.json").is_file():
                anchor["buildings"] = json.loads((candidate / "buildings_report.json").read_text(encoding="utf-8"))
                break
        report["views"]["anchor"] = anchor
    for candidate in (reference_dir / "matsim", reference_dir.parent / "matsim"):
        manifest_path = candidate / "matsim_export.json"
        if manifest_path.is_file():
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            report["views"]["stops"] = {"days": manifest.get("days", []),
                                        "notebook_comparison": manifest.get("notebook_comparison")}
            break
    daily_path = reference_dir / "daily_aggregates.parquet"
    if daily_path.is_file():
        daily = pd.read_parquet(daily_path)
        if {"date", "plz", "segment", "carrier", "count"}.issubset(daily.columns):
            daily["date"] = pd.to_datetime(daily["date"]).dt.strftime("%Y-%m-%d")
            postal_amount = daily.groupby(["date", "plz"], as_index=False)["count"].sum()
            denominator = postal_amount.groupby("date")["count"].transform("sum")
            normalized = postal_amount.copy()
            normalized["share"] = (normalized["count"] / denominator).astype(object).where(denominator.ne(0), None)
            report["views"]["daily"] = {
                "daily_amount": daily.groupby(["date", "segment", "carrier"], as_index=False)["count"].sum().to_dict(orient="records"),
                "postal_amount": postal_amount.to_dict(orient="records"),
                "normalized_postal_share": normalized.to_dict(orient="records"),
            }
            report["status"] = "complete_daily"
    return report


def _valid_report(value: object, run_id: str, baseline_fingerprint: str | None = None) -> bool:
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
    if not (isinstance(value.get("postal"), list) and isinstance(value.get("excluded_quantities"), dict)
            and isinstance(value.get("b2b_adjustment"), dict) and isinstance(value.get("remaining_potentials"), dict)):
        return False
    return baseline_fingerprint is None or value.get("baseline_fingerprint") == baseline_fingerprint


def _verify_frozen_reference(run: Path, state: dict) -> None:
    recorded = state.get("baseline_fingerprint_artifacts")
    fingerprint = state.get("baseline_fingerprint")
    if not isinstance(recorded, dict) or not isinstance(fingerprint, str) or len(fingerprint) != 64:
        raise ValueError("baseline fingerprint is absent from run state")
    actual: dict[str, dict[str, str]] = {}
    for group, entries in recorded.items():
        if not isinstance(group, str) or not isinstance(entries, dict):
            raise ValueError("baseline fingerprint artifact mapping is invalid")
        actual[group] = {}
        for relative, expected in entries.items():
            candidate = Path(relative) if isinstance(relative, str) else None
            if (candidate is None or candidate.is_absolute() or ".." in candidate.parts
                    or not isinstance(expected, str) or len(expected) != 64):
                raise ValueError("baseline fingerprint artifact mapping is invalid")
            artifact = (run / candidate).resolve()
            if not artifact.is_relative_to(run.resolve()) or not artifact.is_file():
                raise ValueError("semantic reference artifact is missing")
            actual[group][relative] = resource_hash(artifact)
    if actual != recorded or canonical_digest({"baseline_reference_contract": 1, "artifacts": actual}) != fingerprint:
        raise ValueError("baseline fingerprint does not match semantic reference artifacts")


def _load_complete_run(run: Path) -> tuple[dict, dict, Path]:
    run = Path(run).resolve()
    try:
        state = json.loads((run / "run.json").read_text(encoding="utf-8"))
        config = json.loads((run / "config.resolved.json").read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ValueError("baseline run is incomplete") from exc
    if not isinstance(state, dict) or state.get("status") not in {"complete_reference", "complete_daily"} or not isinstance(config, dict):
        raise ValueError("baseline run is incomplete")
    if state.get("config_sha256") != resource_hash(run / "config.resolved.json"):
        raise ValueError("baseline configuration hash does not match run state")
    output_dir = Path(config.get("output_dir", "")).resolve()
    if run.parent != output_dir:
        raise ValueError("baseline run destination does not match resolved configuration")
    dashboard_root = Path(config.get("dashboard_root") or output_dir / "dashboard").resolve()
    _verify_frozen_reference(run, state)
    return state, config, dashboard_root


def _stage_status(state: dict, run: Path | None = None) -> dict[str, str]:
    """Read persisted run/stage statuses; partial artefacts are never success."""
    complete = set(state.get("completed_stages", []))
    manifest_stages = {}
    if run is not None:
        try:
            manifest = json.loads((Path(run) / "stage_manifest.json").read_text(encoding="utf-8"))
            if isinstance(manifest, dict) and isinstance(manifest.get("stages"), dict):
                manifest_stages = manifest["stages"]
        except (OSError, json.JSONDecodeError):
            pass
    run_status = state.get("status") if isinstance(state.get("status"), str) else "running"
    result = {stage: "not_run" for stage, _ in _STAGES}
    aliases = {"data_quality": "sources", "market_b2b": "series", "regional_reference": "reference",
               "daily": "daily", "calendar": "calendar", "carriers": "daily", "checks": "reference"}
    for stage, source in aliases.items():
        record = manifest_stages.get(source)
        status = record.get("status") if isinstance(record, dict) else None
        if status in {"not_run", "running", "complete", "failed", "blocked"}:
            result[stage] = status
        elif source in complete:
            # completed_stages is the persisted status artefact written by the workflow.
            result[stage] = "complete"
        elif run_status in {"failed", "blocked"} and source in manifest_stages:
            result[stage] = run_status
    result["overview"] = "complete" if run_status.startswith("complete") else run_status if run_status in {"failed", "blocked", "running"} else "not_run"
    return result


def _entry(run: Path, state: dict, report: dict, dashboard_root: Path) -> tuple[str, dict]:
    key = f"{state['run_id']}@{state['baseline_fingerprint']}"
    try:
        report_path = Path(os.path.relpath(run / "report_data.json", dashboard_root)).as_posix()
    except ValueError:
        report_path = (run / "report_data.json").as_uri()
    return key, {
        "run_id": state["run_id"],
        "baseline_fingerprint": state["baseline_fingerprint"],
        "report_data": report_path,
        "reference_year": report["reference_year"],
        "regional_annual": report["regional_annual"],
        "status": state["status"],
        "stage_status": _stage_status(state, run),
        # The catalog is self-contained so the single dashboard document can
        # present its semantic reference/B2B/quality views without fetching
        # run-specific HTML or trusting a stale presentation cache.
        "views": report["views"],
    }


def _candidate_entry(candidate: Path, dashboard_root: Path) -> tuple[str, dict] | None:
    try:
        state, config, expected_root = _load_complete_run(candidate)
        if expected_root != dashboard_root:
            return None
        # Reconstruct catalog entries from the verified reference contract.
        # report_data.json is deliberately presentation-only and may be
        # missing, stale, or tampered without changing the frozen demand data.
        report = build_report_data(candidate, config, state["run_id"], state["baseline_fingerprint"])
        if not _valid_report(report, state["run_id"], state["baseline_fingerprint"]):
            return None
        return _entry(candidate, state, report, dashboard_root)
    except (OSError, TypeError, ValueError, AttributeError, json.JSONDecodeError):
        return None


def _catalog(output_dir: Path, dashboard_root: Path) -> dict:
    runs: dict[str, dict] = {}
    if output_dir.is_dir():
        for candidate in sorted(output_dir.iterdir(), key=lambda path: path.name):
            if candidate.is_dir() and (entry := _candidate_entry(candidate, dashboard_root)) is not None:
                key, value = entry
                runs[key] = value
    return {"schema_version": _CATALOG_SCHEMA_VERSION, "runs": list(runs.values())}


def build_report_data_for_run(run: Path) -> dict:
    """Build report data for a completed baseline or adapt a historical/failed run."""
    run = Path(run).resolve()
    try:
        state, config, _ = _load_complete_run(run)
        return build_report_data(run, config, state["run_id"], state["baseline_fingerprint"])
    except (OSError, ValueError, TypeError, json.JSONDecodeError):
        from .report_adapters import adapt_existing_report
        return adapt_existing_report(run)


def register_report(run: Path, dashboard_root: Path) -> dict:
    """Atomically upsert a run's report in the shared catalog without semantic writes."""
    run, dashboard_root = Path(run).resolve(), Path(dashboard_root).resolve()
    try:
        state = json.loads((run / "run.json").read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ValueError("run state is required for dashboard registration") from exc
    if not isinstance(state, dict) or not isinstance(state.get("run_id"), str):
        raise ValueError("run state is invalid for dashboard registration")
    report = build_report_data_for_run(run)
    try:
        report_path = Path(os.path.relpath(run / "report_data.json", dashboard_root)).as_posix()
    except ValueError:
        report_path = (run / "report_data.json").as_uri()
    entry = {"run_id": state["run_id"], "kind": "baseline", "baseline_fingerprint": state.get("baseline_fingerprint"),
             "report_data_path": report_path, "report_data_hash": resource_hash(run / "report_data.json") if (run / "report_data.json").is_file() else None,
             "stage_status": _stage_status(state, run), "stages": _stage_status(state, run), "status": state.get("status"),
             "views": report.get("views", {}), "report": report}
    dashboard_root.mkdir(parents=True, exist_ok=True)
    with file_lock(dashboard_root / ".report_catalog.lockfile"):
        catalog_path = dashboard_root / "report_catalog.json"
        try:
            catalog = json.loads(catalog_path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            catalog = {"schema_version": _CATALOG_SCHEMA_VERSION, "runs": []}
        old = catalog.get("runs", []) if isinstance(catalog, dict) else []
        if isinstance(old, dict):
            old = list(old.values())
        key = (entry["run_id"], entry["baseline_fingerprint"])
        catalog = {"schema_version": _CATALOG_SCHEMA_VERSION,
                   "runs": sorted([candidate for candidate in old if (candidate.get("run_id"), candidate.get("baseline_fingerprint")) != key] + [entry], key=lambda item: (item["run_id"], str(item.get("baseline_fingerprint"))))}
        _atomic_json(catalog_path, catalog)
    return entry


def render_catalog(dashboard_root: Path) -> Path:
    """Publish the one dashboard shell atomically while holding the catalog lock."""
    dashboard_root = Path(dashboard_root).resolve(); dashboard_root.mkdir(parents=True, exist_ok=True)
    with file_lock(dashboard_root / ".report_catalog.lockfile"):
        try:
            catalog = json.loads((dashboard_root / "report_catalog.json").read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            catalog = {"schema_version": _CATALOG_SCHEMA_VERSION, "runs": []}
        _atomic_text(dashboard_root / "index.html", _index(catalog))
    return dashboard_root / "index.html"


def _index(catalog: dict) -> str:
    payload = json.dumps(catalog, sort_keys=True, separators=(",", ":"), ensure_ascii=False).replace("</", "<\\/")
    navigation = "".join(
        f'<button id="{stage.replace("_", "-")}" data-stage="{stage}">{html.escape(label)}</button>'
        for stage, label in _STAGES
    )
    stages = json.dumps([stage for stage, _ in _STAGES])
    return f"""<!doctype html><html lang="de"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>HAGRID Dashboard</title><style>
:root{{color-scheme:light;font-family:system-ui,sans-serif;color:#18303b;background:#f4f7f8}}body{{margin:0}}header{{background:#173d50;color:#fff;padding:22px max(24px,calc((100vw - 1160px)/2))}}main{{max-width:1160px;margin:auto;padding:18px 24px}}nav{{display:flex;flex-wrap:wrap;gap:7px;margin:14px 0}}button,select{{font:inherit;padding:7px 10px;border:1px solid #b9cbd2;border-radius:5px;background:#fff}}button.active{{background:#126a89;color:#fff}}.filters{{display:flex;flex-wrap:wrap;gap:10px;background:#e9f0f2;padding:12px;border-radius:6px}}pre{{white-space:pre-wrap;background:#fff;border:1px solid #dce5e8;padding:14px;overflow:auto}}.status{{font-weight:600}}small{{color:#48616d}}</style></head><body>
<header><small>HAGRID / KONSOLIDIERTE AUSWERTUNG</small><h1>Nachfrage-Dashboard</h1><p>Referenz und spätere Stages teilen einen Einstieg; nicht berechnete Stages sind ausdrücklich markiert.</p></header>
<main><div class="filters"><label>Run <select id="run"></select></label><label>Jahr <select id="year"><option value="2021">2021</option></select></label><label>Datum <input id="date" type="date"></label><label>Region <input id="region" placeholder="PLZ/Region"></label><label>Segment <select id="segment"><option value="all">Alle</option><option value="private">Privat</option><option value="business">Geschäftlich</option></select></label><label>Anbieter <select id="carrier"><option value="all">Alle</option></select></label></div>
<nav>{navigation}</nav><p id="status" class="status"></p><pre id="view"></pre></main>
<script>const catalogFile='report_catalog.json';const catalog={payload};const stages={stages};const store='hagrid-baseline-filters-v1';
const byId=id=>document.getElementById(id);const run=byId('run');const year=byId('year');const date=byId('date');const region=byId('region');const segment=byId('segment');const carrier=byId('carrier');const entries=catalog.runs||[];
const params=()=>new URLSearchParams(location.hash.replace(/^#/,''));function selected(){{const p=params();const saved=JSON.parse(localStorage.getItem(store)||'{{}}');return {{run:p.get('run')||saved.run||entries[0]?.run_id||'',stage:p.get('stage')||saved.stage||'overview',year:p.get('year')||saved.year||'2021',date:p.get('date')||saved.date||'',region:p.get('region')||saved.region||'',segment:p.get('segment')||saved.segment||'all',carrier:p.get('carrier')||saved.carrier||'all'}}}}
function write(s){{localStorage.setItem(store,JSON.stringify(s));location.hash=new URLSearchParams(s).toString()}}const viewForStage={{overview:'reference',data_quality:'quality',market_b2b:'market_b2b',regional_reference:'reference',checks:'quality'}};function refresh(){{const s=selected();run.innerHTML='';entries.forEach(e=>{{const o=document.createElement('option');o.value=e.run_id;o.textContent=e.run_id+' · '+String(e.baseline_fingerprint||'historical').slice(0,12);run.append(o)}});let e=entries.find(x=>x.run_id===s.run)||entries[0];if(e)s.run=e.run_id;run.value=s.run;year.value=s.year;date.value=s.date;region.value=s.region;segment.value=s.segment;const profiles=e?.views?.market_b2b?.carrier_profiles||e?.report?.views?.market_b2b?.carrier_profiles||[];const remembered=s.carrier;carrier.innerHTML='<option value="all">Alle</option>';[...new Set(profiles.map(p=>p.carrier).filter(Boolean))].forEach(name=>{{const o=document.createElement('option');o.value=name;o.textContent=name;carrier.append(o)}});carrier.value=[...carrier.options].some(o=>o.value===remembered)?remembered:'all';s.carrier=carrier.value;document.querySelectorAll('nav button').forEach(b=>b.classList.toggle('active',b.dataset.stage===s.stage));const stages=e?.stage_status||e?.stages||{{}};byId('status').textContent=e?('Stage '+s.stage+': '+(stages[s.stage]||'not_run')+' · Run '+e.run_id):'Kein Lauf vorhanden.';const views=e?.views||e?.report?.views||{{}};const view=views[viewForStage[s.stage]]||{{status:e?(stages[s.stage]||'not_run'):'not_run'}};byId('view').textContent=e?JSON.stringify({{filters:{{year:s.year,date:s.date,region:s.region,segment:s.segment,carrier:s.carrier}},stage:s.stage,view,catalog_file:catalogFile}},null,2):''}}
[run,year,date,region,segment,carrier].forEach(x=>x.addEventListener('change',()=>{{const s=selected();s.run=run.value;s.year=year.value;s.date=date.value;s.region=region.value;s.segment=segment.value;s.carrier=carrier.value;write(s)}}));document.querySelectorAll('nav button').forEach(b=>b.addEventListener('click',()=>{{const s=selected();s.stage=b.dataset.stage;write(s)}}));addEventListener('hashchange',refresh);refresh();</script></body></html>"""


def render_baseline(run: Path) -> Path:
    """Regenerate presentation data from a verified frozen reference and atomically register it."""
    run = Path(run).resolve()
    state, config, dashboard_root = _load_complete_run(run)
    report = build_report_data(run, config, state["run_id"], state["baseline_fingerprint"])
    _atomic_json(run / "report_data.json", report)
    _atomic_text(run / "report.md", _report_markdown(report))
    register_report(run, dashboard_root)
    return render_catalog(dashboard_root)
