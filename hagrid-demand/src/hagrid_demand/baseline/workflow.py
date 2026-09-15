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

from hagrid_demand.common.cache import dependency_snapshot, resolve_stage, stage_key
from hagrid_demand.common.provenance import canonical_json, resource_hash
from hagrid_demand.data import (build_business, build_residential, read_dhl, read_hermes,
                                read_persons, read_plz)

from .config import load_baseline_config
from .dashboard import _report_markdown, build_report_data, render_baseline
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
        paths.append(Path(config["foundation_run"]) / "artifact_manifest.json")
        paths.append(Path(config["weekly_source"]))
    return paths


def _require_foundation_columns(table: pd.DataFrame, name: str, columns: set[str]) -> None:
    if missing := columns.difference(table.columns):
        raise ValueError(f"foundation {name} missing required columns: {sorted(missing)}")


def _postal_assignments(sites: gpd.GeoDataFrame, membership: pd.DataFrame) -> gpd.GeoDataFrame:
    """Keep spatial candidates distinct from independently supplied postal evidence."""
    _require_foundation_columns(membership, "site postal candidates", {"site_id", "plz", "plz_evidence"})
    candidates = membership[["site_id", "plz", "plz_evidence"]].copy()
    candidates = candidates.dropna(subset=["site_id", "plz", "plz_evidence"])
    candidates["plz"] = candidates.plz.astype(str).str.strip()
    if candidates.plz.eq("").any():
        raise ValueError("site postal candidates require non-empty PLZ values")
    allowed = {"spatial_candidate_unverified", "independently_verified"}
    if not candidates.plz_evidence.isin(allowed).all():
        raise ValueError("site postal candidates require explicit spatial or independently_verified evidence")
    if candidates.duplicated(["site_id", "plz", "plz_evidence"]).any():
        raise ValueError("site postal candidates must not duplicate postal evidence")
    result = sites.copy()
    result["plz"] = pd.NA
    result["plz_evidence"] = pd.NA
    result["allocation_status"] = "unlocated"
    if candidates.empty:
        return gpd.GeoDataFrame(result, geometry="geometry", crs=sites.crs)
    count = candidates.groupby("site_id").plz.nunique()
    unique_ids = set(count.loc[count.eq(1)].index)
    independent = candidates.loc[candidates.plz_evidence.eq("independently_verified")]
    independent_count = independent.groupby("site_id").plz.nunique()
    independent_unique = set(independent_count.loc[independent_count.eq(1)].index).intersection(unique_ids)
    spatial = candidates.loc[candidates.plz_evidence.eq("spatial_candidate_unverified")]
    spatial_count = spatial.groupby("site_id").plz.nunique()
    spatial_unique = set(spatial_count.loc[spatial_count.eq(1)].index).intersection(unique_ids)
    independent_map = independent.loc[independent.site_id.isin(independent_unique)].drop_duplicates("site_id").set_index("site_id").plz
    spatial_map = spatial.loc[spatial.site_id.isin(spatial_unique)].drop_duplicates("site_id").set_index("site_id").plz
    valid_points = result.location_status.eq("source_point_unverified")
    for index, site_id in result.site_id.items():
        if site_id in independent_map.index:
            result.at[index, "plz"] = independent_map.at[site_id]
            result.at[index, "plz_evidence"] = "independently_verified"
            result.at[index, "allocation_status"] = "located" if bool(valid_points.at[index]) else "unlocated"
        elif bool(valid_points.at[index]) and site_id in spatial_map.index:
            result.at[index, "plz"] = spatial_map.at[site_id]
            result.at[index, "plz_evidence"] = "spatial_candidate_unverified"
            result.at[index, "allocation_status"] = "located"
    return gpd.GeoDataFrame(result, geometry="geometry", crs=sites.crs)


def _raw_postal_candidates(sites: gpd.GeoDataFrame, postal: gpd.GeoDataFrame) -> pd.DataFrame:
    """Derive only spatial candidates for sites whose source geometry is usable."""
    eligible = sites.loc[sites.location_status.eq("source_point_unverified"), ["site_id", "geometry"]]
    if eligible.empty:
        return pd.DataFrame(columns=["site_id", "plz", "plz_evidence"])
    membership = gpd.sjoin(eligible, postal[["plz", "geometry"]], how="left", predicate="intersects")
    membership = membership[["site_id", "plz"]].dropna(subset=["plz"]).drop_duplicates()
    membership["plz_evidence"] = "spatial_candidate_unverified"
    return pd.DataFrame(membership)


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
        sites = _postal_assignments(gpd.GeoDataFrame(sites, geometry="geometry", crs=getattr(sites, "crs", None)), membership)
        sites["segment"] = sites.recipient_type
        employees = pd.to_numeric(sites["employees"], errors="coerce")
        sites["invalid_employees"] = sites.recipient_type.eq("business") & (employees.isna() | employees.lt(0))
        for name in required:
            table = tables[name]
            if "geometry" in table.columns:
                gpd.GeoDataFrame(table, geometry="geometry", crs=getattr(table, "crs", None)).to_parquet(output / name, index=False)
            else:
                table.to_parquet(output / name, index=False)
        sites.to_parquet(output / "sites.parquet", index=False)
        membership.to_parquet(output / "site_postal_candidates.parquet", index=False)
        source_manifest = json.loads((output / "sources.json").read_text(encoding="utf-8"))
        source_manifest.update({"mode": "foundation_run", "foundation_run": config["foundation_run"],
                                "foundation_manifest": resource_hash(Path(config["foundation_run"]) / "artifact_manifest.json"),
                                "invalid_employees": int(sites.invalid_employees.sum())})
        _json(output / "sources.json", source_manifest)
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
    membership = _raw_postal_candidates(sites, postal)
    sites = _postal_assignments(sites, membership)
    sites["segment"] = sites.recipient_type
    dhl = read_dhl(paths["dhl"], config["target_crs"])
    if not dhl.year.eq(2021).all():
        raise ValueError("raw DHL data must contain only year 2021")
    hermes = read_hermes(paths["hermes"])
    sites.to_parquet(output / "sites.parquet", index=False)
    dhl.to_parquet(output / "dhl_observations.parquet", index=False)
    hermes.to_parquet(output / "hermes_observations.parquet", index=False)
    postal.to_parquet(output / "postal_support.parquet", index=False)
    membership.to_parquet(output / "site_postal_candidates.parquet", index=False)
    source_manifest = json.loads((output / "sources.json").read_text(encoding="utf-8"))
    source_manifest["raw_sources"] = {name: resource_hash(path) for name, path in _source_specs(config).items()}
    source_manifest["invalid_employees"] = int(sites.invalid_employees.sum())
    _json(output / "sources.json", source_manifest)
    assert prepared["mode"] == "raw"


def _write_series(config: dict, source: Path, output: Path) -> None:
    weekly = pd.read_csv(source / "weekly_profile.csv")
    series = build_series(packaged_series_inputs(), [config["reference_year"]], volume_fit_policy="observed_only",
                          weekly_profile=weekly)
    for name, table in series.items():
        table.to_parquet(output / f"{name}.parquet", index=False)


def _write_potentials(source: Path, output: Path) -> None:
    sites = gpd.read_parquet(source / "sites.parquet")
    potentials = build_potentials(sites.drop(columns="geometry"))
    potentials.to_parquet(output / "potentials.parquet", index=False)


def _profiles(series_dir: Path, reference_year: int) -> tuple[dict, float]:
    market = pd.read_parquet(series_dir / "market.parquet")
    priors = pd.read_parquet(series_dir / "providers.parquet")
    b2b = pd.read_parquet(series_dir / "b2b.parquet")
    market = market.loc[market.year.eq(reference_year)].copy()
    if market.carrier.duplicated().any() or market.empty:
        raise ValueError("market series must contain one row per carrier for the reference year")
    market = market.set_index("carrier", drop=False)
    priors = priors.loc[priors.year.eq(reference_year)].set_index("carrier").reindex(market.index)
    if priors.isna().any().any():
        raise ValueError("provider priors do not cover reference market")
    return ({"m": market["market_share"].to_numpy(float), "q_prior": priors["q_prior"].to_numpy(float),
             "lower": priors["lower"].to_numpy(float), "upper": priors["upper"].to_numpy(float),
             "scale": priors["q_scale"].to_numpy(float), "carriers": market.carrier.tolist()},
            float(b2b.loc[b2b.year.eq(reference_year), "share"].item()))


def _write_reference(config: dict, source: Path, series_dir: Path, potentials_dir: Path, output: Path) -> None:
    profiles, b2b = _profiles(series_dir, config["reference_year"])
    source_sites = gpd.read_parquet(source / "sites.parquet")
    postal_scope = gpd.read_parquet(source / "postal_support.parquet")
    invalid = source_sites.loc[source_sites.recipient_type.eq("business") & source_sites.invalid_employees.astype(bool)
                               & source_sites.plz.isin(postal_scope.plz), "site_id"].astype(str).tolist()
    if invalid:
        raise ValueError(f"invalid employees for in-scope business sites: {invalid}")
    solved = solve_reference(pd.read_parquet(potentials_dir / "potentials.parquet"),
                             gpd.read_parquet(source / "dhl_observations.parquet"), profiles, b2b,
                             config["reference_operating_days"], scope_plz=postal_scope.plz.astype(str).tolist())
    postal_columns = ["plz", "dhl_retained_mean", "reference_annual", "private_annual", "business_annual", "b2b_share", "dhl_share"]
    solved["postal"].loc[:, postal_columns].to_parquet(output / "reference_postal.parquet", index=False)
    site_columns = ["site_id", "plz", "segment", "population", "employees", "branch", "weight", "historical_share",
                    "structural_share", "reference_annual", "allocation_status"]
    sites = solved["sites"].loc[:, site_columns].copy()
    sites.to_parquet(output / "reference_sites.parquet", index=False)
    geometry = source_sites.loc[source_sites.site_id.isin(sites.site_id), ["site_id", "geometry"]].copy()
    geometry = gpd.GeoDataFrame(geometry, geometry="geometry", crs=source_sites.crs)
    geometry.to_parquet(output / "reference_geometry.parquet", index=False)
    carriers = solved["carriers"]
    carrier_profiles = pd.concat([
        carriers.assign(segment="private", share=carriers.private_share),
        carriers.assign(segment="business", share=carriers.business_share),
    ], ignore_index=True)
    carrier_profiles = carrier_profiles[["year", "segment", "carrier", "market_share", "q_prior", "q_scale", "lower", "upper", "q_adjusted", "share"]]
    carrier_profiles.to_parquet(output / "reference_carrier_profiles.parquet", index=False)
    _json(output / "reference_reconciliation.json", solved["reconciliation"])
    _json(output / "reference_regional_annual.json", {"year": config["reference_year"], "regional_annual": solved["regional_annual"]})
    checks = {**solved["checks"], "source_quality": solved["source_quality"], "implied_rates": solved["implied_rates"],
              "regional_annual": solved["regional_annual"]}
    _json(output / "reference_checks.json", checks)
    _json(output / "checks.json", checks)


def _write_report(config: dict, reference_dir: Path, output: Path, run_id: str, baseline_fingerprint: str) -> None:
    # The cached report stage is derived from exactly the same frozen semantic
    # reference contract as public dashboard regeneration.
    report = build_report_data(reference_dir, config, run_id, baseline_fingerprint)
    _json(output / "report_data.json", report)
    (output / "report.md").write_text(_report_markdown(report), encoding="utf-8")


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


def _frozen_reference_artifacts(run: Path) -> dict:
    """Hash the public reference contract that downstream stages are allowed to consume."""
    groups = {
        "structure": ("reference_sites.parquet", "reference_postal.parquet"),
        "geometry": ("reference_geometry.parquet",),
        "series": ("series/market.parquet", "series/b2b.parquet", "series/providers.parquet", "series/weekly.parquet"),
        "scope": ("sources/postal_support.parquet", "reference_checks.json"),
        "reconciliation": ("reference_carrier_profiles.parquet", "reference_reconciliation.json"),
        "regional": ("reference_regional_annual.json", "checks.json"),
    }
    result = {}
    for group, names in groups.items():
        result[group] = {}
        for name in names:
            artifact = run / name
            if not artifact.is_file():
                raise ValueError(f"missing frozen semantic reference artifact: {name}")
            result[group][name] = resource_hash(artifact)
    return result


def _baseline_fingerprint(run: Path) -> tuple[str, dict]:
    artifacts = _frozen_reference_artifacts(run)
    from hagrid_demand.common.provenance import canonical_digest

    return canonical_digest({"baseline_reference_contract": 1, "artifacts": artifacts}), artifacts


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
        source_snapshot = dependency_snapshot(source_dependencies)
        source_fingerprint = stage_key("sources", source_dependencies, config,
                                       {"workflow": Path(__file__), "sources": Path(__file__).with_name("sources.py"),
                                        "data": Path(__file__).parents[1] / "data.py", "linking": Path(__file__).parents[1] / "linking.py"},
                                       dependency_snapshot=source_snapshot)
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
        source_artifacts = ["sources.json", "sites.parquet", "dhl_observations.parquet", "hermes_observations.parquet",
                            "postal_support.parquet", "site_postal_candidates.parquet", "weekly_profile.csv"]
        resolve_stage(run, "sources", source_fingerprint, cache_root=cache_root, dependencies=source_dependencies,
                      build=lambda output: _write_sources(config, output),
                      validate=lambda output: _validate(output, source_artifacts), dependency_snapshot=source_snapshot)
        state["completed_stages"].append("sources")
        series_dependencies = {"inputs": Path(__file__).parent / "data", "weekly": run / "sources" / "weekly_profile.csv",
                               "year": config["reference_year"]}
        series_snapshot = dependency_snapshot(series_dependencies)
        series_fingerprint = stage_key("series", series_dependencies, config,
                                       {"workflow": Path(__file__), "series": Path(__file__).with_name("series.py"),
                                        "sources": Path(__file__).with_name("sources.py")}, dependency_snapshot=series_snapshot)
        resolve_stage(run, "series", series_fingerprint, cache_root=cache_root, dependencies=series_dependencies,
                      build=lambda output: _write_series(config, run / "sources", output),
                      validate=lambda output: _validate(output, ["market.parquet", "b2b.parquet", "volume.parquet", "providers.parquet", "weekly.parquet"]),
                      dependency_snapshot=series_snapshot)
        state["completed_stages"].append("series")
        potential_dependencies = {"sources": run / "sources"}
        potential_snapshot = dependency_snapshot(potential_dependencies)
        potential_fingerprint = stage_key("potentials", potential_dependencies, config,
                                          {"workflow": Path(__file__), "potentials": Path(__file__).with_name("potentials.py")},
                                          dependency_snapshot=potential_snapshot)
        resolve_stage(run, "potentials", potential_fingerprint, cache_root=cache_root, dependencies=potential_dependencies,
                      build=lambda output: _write_potentials(run / "sources", output),
                      validate=lambda output: _validate(output, ["potentials.parquet"]), dependency_snapshot=potential_snapshot)
        state["completed_stages"].append("potentials")
        reference_dependencies = {"sources": run / "sources", "series": run / "series", "potentials": run / "potentials"}
        reference_snapshot = dependency_snapshot(reference_dependencies)
        reference_fingerprint = stage_key("reference", reference_dependencies, config,
                                          {"workflow": Path(__file__), "reference": Path(__file__).with_name("reference.py")},
                                          dependency_snapshot=reference_snapshot)
        resolve_stage(run, "reference", reference_fingerprint, cache_root=cache_root, dependencies=reference_dependencies,
                      build=lambda output: _write_reference(config, run / "sources", run / "series", run / "potentials", output),
                      validate=lambda output: _validate(output, ["reference_postal.parquet", "reference_sites.parquet",
                                                                    "reference_geometry.parquet", "reference_carrier_profiles.parquet",
                                                                    "reference_reconciliation.json", "reference_regional_annual.json",
                                                                    "reference_checks.json", "checks.json"]), dependency_snapshot=reference_snapshot)
        for name in ("reference_postal.parquet", "reference_sites.parquet", "reference_geometry.parquet",
                     "reference_carrier_profiles.parquet", "reference_reconciliation.json", "reference_regional_annual.json",
                     "reference_checks.json", "checks.json"):
            _copy_public(run, "reference", name)
        state["completed_stages"].append("reference")
        baseline_fingerprint, baseline_artifacts = _baseline_fingerprint(run)
        state["baseline_fingerprint"] = baseline_fingerprint
        state["baseline_fingerprint_artifacts"] = baseline_artifacts
        state["config_sha256"] = resource_hash(run / "config.resolved.json")
        state["regional_annual"] = json.loads((run / "reference_regional_annual.json").read_text(encoding="utf-8"))["regional_annual"]
        _json(run / "run.json", state)
        report_dependencies = {"reference": run / "reference", "run_id": run_id, "baseline_fingerprint": baseline_fingerprint}
        report_snapshot = dependency_snapshot(report_dependencies)
        report_fingerprint = stage_key("report", report_dependencies, config,
                                       {"workflow": Path(__file__), "dashboard": Path(__file__).with_name("dashboard.py")},
                                       dependency_snapshot=report_snapshot)
        resolve_stage(run, "report", report_fingerprint, cache_root=cache_root, dependencies=report_dependencies,
                      build=lambda output: _write_report(config, run / "reference", output, run_id, baseline_fingerprint),
                      validate=lambda output: _validate(output, ["report_data.json", "report.md"]),
                      dependency_snapshot=report_snapshot)
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
