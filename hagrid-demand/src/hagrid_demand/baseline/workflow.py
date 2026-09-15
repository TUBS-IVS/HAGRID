"""Atomic orchestration of the deterministic reference baseline."""

from __future__ import annotations

import json
import os
from pathlib import Path
import re
import shutil
import uuid

import geopandas as gpd
import pandas as pd

from hagrid_demand.common.cache import resolve_stage, stage_key
from hagrid_demand.common.provenance import canonical_json, resource_hash
from hagrid_demand.data import (build_business, build_residential, read_dhl, read_hermes,
                                read_persons, read_plz)

from .config import load_baseline_config
from .dashboard import render_baseline
from .potentials import build_potentials
from .reference import solve_reference
from .series import build_series
from .sources import packaged_series_inputs, prepare_sources


_RUN_ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9_-]{0,100}")


def _json(path: Path, value: dict) -> None:
    temporary = path.with_name(f".{path.name}.{uuid.uuid4().hex}.tmp")
    temporary.write_text(json.dumps(value, sort_keys=True, indent=2, ensure_ascii=False, allow_nan=False) + "\n", encoding="utf-8")
    os.replace(temporary, path)


def _source_specs(config: dict) -> dict[str, Path]:
    specs = config.get("sources")
    if not isinstance(specs, list):
        raise ValueError("raw source mode requires a sources list")
    result = {}
    for spec in specs:
        if not isinstance(spec, dict) or not isinstance(spec.get("adapter"), str) or not isinstance(spec.get("file"), str):
            raise ValueError("each raw source requires adapter and file")
        result[spec["adapter"]] = Path(config["input_dir"]) / spec["file"]
        if spec["adapter"] == "dhl" and spec.get("year") != 2021:
            raise ValueError("DHL source metadata must declare year 2021")
    required = {"persons", "companies", "dhl", "hermes", "plz"}
    if missing := required.difference(result):
        raise ValueError(f"raw source mode is missing adapters: {sorted(missing)}")
    return result


def _raw_source_paths(config: dict) -> list[Path]:
    paths = [Path(config["config_path"])]
    if config["source_mode"] == "raw":
        paths.extend(_source_specs(config).values())
        paths.append(Path(config["weekly_source"]))
    elif config.get("foundation_run"):
        paths.append(Path(config["foundation_run"]))
    return paths


def _require_foundation_columns(table: pd.DataFrame, name: str, columns: set[str]) -> None:
    if missing := columns.difference(table.columns):
        raise ValueError(f"foundation {name} missing required columns: {sorted(missing)}")


def _write_sources(config: dict, output: Path) -> None:
    prepared = prepare_sources(config, output)
    if config["source_mode"] != "raw":
        tables = prepared["foundation"]
        required = {"sites.parquet", "dhl_observations.parquet", "hermes_observations.parquet", "postal_support.parquet"}
        if missing := required.difference(tables):
            raise ValueError(f"foundation run missing required tables: {sorted(missing)}")
        sites = tables["sites.parquet"].copy()
        _require_foundation_columns(
            sites, "sites", {"site_id", "recipient_type", "population", "employees", "location_status", "geometry"}
        )
        dhl_table = tables["dhl_observations.parquet"]
        _require_foundation_columns(dhl_table, "DHL observations", {"year"})
        if not dhl_table.year.eq(2021).all():
            raise ValueError("foundation DHL data must contain only year 2021")
        membership = tables.get("site_postal_candidates.parquet")
        if membership is None:
            raise ValueError("foundation run requires site_postal_candidates.parquet")
        _require_foundation_columns(membership, "site postal candidates", {"site_id", "plz"})
        counts = membership.groupby("site_id").plz.count()
        unique = membership.loc[membership.site_id.map(counts).eq(1), ["site_id", "plz"]].drop_duplicates("site_id")
        sites = sites.merge(unique, on="site_id", how="left", validate="one_to_one")
        sites["segment"] = sites.recipient_type
        sites["allocation_status"] = sites.location_status.map(
            lambda value: "located" if value == "source_point_unverified" else "unlocated"
        )
        employees = pd.to_numeric(sites["employees"], errors="coerce")
        sites["invalid_employees"] = sites.recipient_type.eq("business") & (employees.isna() | employees.lt(0))
        for name in required:
            table = tables[name]
            if "geometry" in table.columns:
                gpd.GeoDataFrame(table, geometry="geometry", crs=getattr(table, "crs", None)).to_parquet(output / name, index=False)
            else:
                table.to_parquet(output / name, index=False)
        gpd.GeoDataFrame(sites, geometry="geometry", crs=getattr(sites, "crs", None)).to_parquet(output / "sites.parquet", index=False)
        _json(output / "sources.json", {"mode": "foundation_run", "foundation_run": config["foundation_run"], "invalid_employees": int(sites.invalid_employees.sum())})
        return
    paths = _source_specs(config)
    persons = read_persons(paths["persons"], config["persons_crs"], config["target_crs"])
    residential = build_residential(persons, tolerance=5.0)
    businesses = build_business(paths["companies"], config["target_crs"])
    columns = ["site_id", "recipient_type", "population", "employees", "branch", "location_status", "invalid_employees", "geometry"]
    residential["invalid_employees"] = False
    sites = gpd.GeoDataFrame(pd.concat([residential[columns], businesses[columns]], ignore_index=True),
                             geometry="geometry", crs=config["target_crs"])
    postal = read_plz(paths["plz"], config["plz_crs"], config["target_crs"])
    membership = gpd.sjoin(sites[["site_id", "geometry"]], postal, how="left", predicate="intersects")
    counts = membership.groupby("site_id").plz.count()
    unique = membership.loc[membership.site_id.map(counts).eq(1), ["site_id", "plz"]].drop_duplicates("site_id")
    sites = sites.merge(unique, on="site_id", how="left", validate="one_to_one")
    sites["segment"] = sites.recipient_type
    sites["allocation_status"] = sites.location_status.map(
        lambda value: "located" if value == "source_point_unverified" else "unlocated"
    )
    dhl = read_dhl(paths["dhl"], config["target_crs"])
    if not dhl.year.eq(2021).all():
        raise ValueError("raw DHL data must contain only year 2021")
    hermes = read_hermes(paths["hermes"])
    sites.to_parquet(output / "sites.parquet", index=False)
    dhl.to_parquet(output / "dhl_observations.parquet", index=False)
    hermes.to_parquet(output / "hermes_observations.parquet", index=False)
    postal.to_parquet(output / "postal_support.parquet", index=False)
    source_manifest = json.loads((output / "sources.json").read_text(encoding="utf-8"))
    source_manifest["raw_sources"] = {name: resource_hash(path) for name, path in _source_specs(config).items()}
    source_manifest["invalid_employees"] = int(sites.invalid_employees.sum())
    _json(output / "sources.json", source_manifest)
    assert prepared["mode"] == "raw"


def _write_series(config: dict, output: Path) -> None:
    series = build_series(packaged_series_inputs(), [config["reference_year"]], volume_fit_policy="observed_only")
    for name, table in series.items():
        table.to_parquet(output / f"{name}.parquet", index=False)


def _write_potentials(source: Path, output: Path) -> None:
    sites = gpd.read_parquet(source / "sites.parquet")
    potentials = build_potentials(sites.drop(columns="geometry"))
    potentials.to_parquet(output / "potentials.parquet", index=False)


def _profiles(series_dir: Path, reference_year: int) -> tuple[dict, float]:
    market = pd.read_parquet(series_dir / "market.parquet")
    priors = pd.read_parquet(series_dir / "provider_priors.parquet")
    b2b = pd.read_parquet(series_dir / "b2b.parquet")
    market = market.loc[market.year.eq(reference_year)].set_index("provider").sort_index()
    priors = priors.set_index("provider").reindex(market.index)
    if priors.isna().any().any():
        raise ValueError("provider priors do not cover reference market")
    return ({"m": market["share"].to_numpy(float), "q_prior": priors["initial"].to_numpy(float),
             "lower": priors["lower"].to_numpy(float), "upper": priors["upper"].to_numpy(float),
             "scale": priors["scale"].to_numpy(float), "carriers": market.index.tolist()},
            float(b2b.loc[b2b.year.eq(reference_year), "share"].item()))


def _write_reference(config: dict, source: Path, series_dir: Path, potentials_dir: Path, output: Path) -> None:
    profiles, b2b = _profiles(series_dir, config["reference_year"])
    solved = solve_reference(pd.read_parquet(potentials_dir / "potentials.parquet"),
                             gpd.read_parquet(source / "dhl_observations.parquet"), profiles, b2b,
                             config["reference_operating_days"])
    solved["postal"].to_parquet(output / "reference_postal.parquet", index=False)
    solved["sites"].drop(columns="geometry", errors="ignore").to_parquet(output / "reference_sites.parquet", index=False)
    solved["carriers"].to_parquet(output / "reference_carriers.parquet", index=False)
    _json(output / "reference_checks.json", {**solved["checks"], "source_quality": solved["source_quality"],
                                                "implied_rates": solved["implied_rates"], "regional_annual": solved["regional_annual"]})


def _write_report(config: dict, reference_dir: Path, output: Path, run_id: str) -> None:
    postal = pd.read_parquet(reference_dir / "reference_postal.parquet")
    checks = json.loads((reference_dir / "reference_checks.json").read_text(encoding="utf-8"))
    report = {"run_id": run_id, "reference_year": config["reference_year"], "operating_days": config["reference_operating_days"],
              "regional_annual": float(postal.reference_annual.sum()), "postal": json.loads(postal.to_json(orient="records")),
              "excluded_quantities": checks["scope_ledger"],
              "b2b_adjustment": {key: checks[key] for key in ("b2b_target", "b2b_achieved", "b2b_residual", "k", "k_status", "log_k")},
              "remaining_potentials": checks["source_quality"], "status": "complete_reference"}
    _json(output / "report_data.json", report)
    scope, b2b, quality = report["excluded_quantities"], report["b2b_adjustment"], report["remaining_potentials"]
    (output / "report.md").write_text(
        f"# HAGRID Referenzlauf: {run_id}\n\n"
        f"Referenzjahr 2021. Die Tagesmittel-Annahme verwendet {report['operating_days']} Betriebstage.\n\n"
        f"## Ausgeschlossene Beobachtungen\n\n{scope['excluded_rows']} Beobachtungen / {scope['excluded_volume']:.1f} Mengeneinheiten ausgeschlossen.\n\n"
        f"## B2B-Anpassung\n\nZiel {b2b['b2b_target']:.6f}; erreicht {b2b['b2b_achieved']:.6f}; Residuum {b2b['b2b_residual']:.3g}.\n\n"
        f"## Restpotenziale\n\nUnbekannte PLZ: {len(quality.get('unknown_plz_sites', []))}; bekannte PLZ außerhalb Anker: {len(quality.get('known_plz_outside_anchor_sites', []))}.\n",
        encoding="utf-8")


def _validate(output: Path, names: list[str]) -> None:
    missing = [name for name in names if not (output / name).is_file()]
    if missing:
        raise ValueError(f"stage did not write required artifacts: {missing}")


def _copy_public(run: Path, stage: str, name: str) -> None:
    source = run / stage / name
    target = run / name
    temporary = target.with_name(f".{target.name}.{uuid.uuid4().hex}.tmp")
    shutil.copy2(source, temporary)
    os.replace(temporary, target)


def _run_state(run: Path, config: dict) -> dict:
    return {"run_id": run.name, "status": "running", "config": config, "completed_stages": []}


def run_baseline(config_path: Path, run_id: str, resume: bool = False) -> Path:
    """Build or safely resume the one-year deterministic reference baseline."""
    if not _RUN_ID.fullmatch(run_id):
        raise ValueError("Invalid run_id")
    config = load_baseline_config(Path(config_path))
    if config["reference_year"] != 2021:
        raise ValueError("The reference milestone requires reference_year=2021")
    if config.get("dhl_exclude_above") != 1000:
        raise ValueError("The reference milestone requires dhl_exclude_above=1000")
    if config["output_scope"] != "reference":
        raise NotImplementedError("daily output_scope is available after Plan 02; no daily run was produced")
    run = Path(config["output_dir"]) / run_id
    if run.exists() and not resume:
        raise FileExistsError(f"Baseline run already exists: {run}")
    if resume:
        if not run.is_dir() or not (run / "config.resolved.json").is_file():
            raise FileNotFoundError(f"No resumable baseline run: {run}")
        prior = json.loads((run / "config.resolved.json").read_text(encoding="utf-8"))
        if canonical_json(prior) != canonical_json(config):
            raise ValueError("cannot resume: resolved configuration changed")
    else:
        run.mkdir(parents=True, exist_ok=False)
        _json(run / "config.resolved.json", config)
        _json(run / "run.json", _run_state(run, config))
    try:
        source_dependencies = {"source_files": _raw_source_paths(config)}
        source_fingerprint = stage_key("sources", source_dependencies, config, {"workflow": Path(__file__), "sources": Path(__file__).with_name("sources.py"), "data": Path(__file__).parents[1] / "data.py", "linking": Path(__file__).parents[1] / "linking.py"})
    except Exception as exc:
        state = _run_state(run, config); state.update(status="failed", error=f"{type(exc).__name__}: {exc}")
        _json(run / "run.json", state)
        raise
    if resume and (run / "stage_manifest.json").is_file():
        prior_manifest = json.loads((run / "stage_manifest.json").read_text(encoding="utf-8"))
        previous = prior_manifest.get("stages", {}).get("sources", {}).get("fingerprint")
        if previous is not None and previous != source_fingerprint:
            raise ValueError("cannot resume: consumed source files changed")
    if resume:
        prior_state = json.loads((run / "run.json").read_text(encoding="utf-8"))
        if prior_state.get("consumed_source_fingerprint") != source_fingerprint:
            raise ValueError("cannot resume: consumed source files changed")
    state = _run_state(run, config)
    state["consumed_source_fingerprint"] = source_fingerprint
    _json(run / "run.json", state)
    try:
        cache_root = Path(config["cache_root"])
        source_artifacts = ["sources.json", "sites.parquet", "dhl_observations.parquet", "hermes_observations.parquet", "postal_support.parquet"]
        if config["source_mode"] == "raw":
            source_artifacts.append("weekly_profile.csv")
        resolve_stage(run, "sources", source_fingerprint, cache_root=cache_root, dependencies=source_dependencies,
                      build=lambda output: _write_sources(config, output),
                      validate=lambda output: _validate(output, source_artifacts))
        state["completed_stages"].append("sources")
        series_dependencies = {"inputs": Path(__file__).parent / "data", "year": config["reference_year"]}
        series_fingerprint = stage_key("series", series_dependencies, config, {"workflow": Path(__file__), "series": Path(__file__).with_name("series.py"), "sources": Path(__file__).with_name("sources.py")})
        resolve_stage(run, "series", series_fingerprint, cache_root=cache_root, dependencies=series_dependencies,
                      build=lambda output: _write_series(config, output),
                      validate=lambda output: _validate(output, ["market.parquet", "b2b.parquet", "volume.parquet", "provider_priors.parquet"]))
        state["completed_stages"].append("series")
        potential_dependencies = {"sources": run / "sources"}
        potential_fingerprint = stage_key("potentials", potential_dependencies, config, {"workflow": Path(__file__), "potentials": Path(__file__).with_name("potentials.py")})
        resolve_stage(run, "potentials", potential_fingerprint, cache_root=cache_root, dependencies=potential_dependencies,
                      build=lambda output: _write_potentials(run / "sources", output),
                      validate=lambda output: _validate(output, ["potentials.parquet"]))
        state["completed_stages"].append("potentials")
        reference_dependencies = {"sources": run / "sources", "series": run / "series", "potentials": run / "potentials"}
        reference_fingerprint = stage_key("reference", reference_dependencies, config, {"workflow": Path(__file__), "reference": Path(__file__).with_name("reference.py")})
        resolve_stage(run, "reference", reference_fingerprint, cache_root=cache_root, dependencies=reference_dependencies,
                      build=lambda output: _write_reference(config, run / "sources", run / "series", run / "potentials", output),
                      validate=lambda output: _validate(output, ["reference_postal.parquet", "reference_sites.parquet", "reference_carriers.parquet", "reference_checks.json"]))
        for name in ("reference_postal.parquet", "reference_sites.parquet", "reference_carriers.parquet", "reference_checks.json"):
            _copy_public(run, "reference", name)
        state["completed_stages"].append("reference")
        report_dependencies = {"reference": run / "reference", "run_id": run_id}
        report_fingerprint = stage_key("report", report_dependencies, config, {"workflow": Path(__file__), "dashboard": Path(__file__).with_name("dashboard.py")})
        resolve_stage(run, "report", report_fingerprint, cache_root=cache_root, dependencies=report_dependencies,
                      build=lambda output: _write_report(config, run / "reference", output, run_id),
                      validate=lambda output: _validate(output, ["report_data.json", "report.md"]))
        for name in ("report_data.json", "report.md"):
            _copy_public(run, "report", name)
        state["completed_stages"].append("report")
        state["completed_stages"].append("dashboard")
        state["status"] = "complete_reference"
        _json(run / "run.json", state)
        render_baseline(run)
        return run
    except Exception as exc:
        state["status"] = "failed"
        state["error"] = f"{type(exc).__name__}: {exc}"
        _json(run / "run.json", state)
        raise
