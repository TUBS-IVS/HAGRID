"""Read-only source processing into immutable, locally stored foundation runs."""

from datetime import datetime, timezone
import hashlib
import importlib.metadata
import json
import os
from pathlib import Path
import platform
import re
import subprocess
import uuid

import geopandas as gpd
import pandas as pd

from .data import (build_business, build_residential, read_dhl, read_hermes, read_persons,
                   read_plz, require_unique, write_json)
from .linking import candidate_links
from .dashboard import build_dashboard


def digest(path):
    h = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for chunk in iter(lambda: stream.read(8 * 1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def load_config(path):
    path = Path(path).resolve()
    cfg = json.loads(path.read_text(encoding="utf-8"))
    cfg["input_dir"] = str((path.parent / cfg["input_dir"]).resolve())
    cfg["output_dir"] = str((path.parent / cfg["output_dir"]).resolve())
    if cfg.get("observation_definitions_confirmed"):
        raise ValueError("This adapter version cannot certify measurement definitions; use false and review the report")
    if cfg["max_street_distance_m"] <= 0 or cfg["building_spread_tolerance_m"] < 0:
        raise ValueError("Invalid spatial thresholds")
    expected = {"persons": "H01", "companies": "H02", "dhl": "H03", "hermes": "H04", "plz": "H06"}
    if len(cfg["sources"]) != len(expected) or {s["adapter"]: s["id"] for s in cfg["sources"]} != expected:
        raise ValueError("Expected exactly one configured source for each foundation adapter and canonical source ID")
    root = Path(cfg["input_dir"])
    for source in cfg["sources"]:
        resolved = (root / source["file"]).resolve()
        if not resolved.is_relative_to(root) or not resolved.is_file():
            raise ValueError(f"Missing input or path outside input_dir: {source['id']}")
    return cfg


def source_manifest(cfg):
    root = Path(cfg["input_dir"])
    sources = []
    used = set()
    for spec in cfg["sources"]:
        path = root / spec["file"]
        files = [path]
        if path.suffix.lower() == ".shp":
            for suffix in [".shx", ".dbf", ".prj"]:
                companion = path.with_suffix(suffix)
                if not companion.exists():
                    raise ValueError(f"{spec['id']}: missing shapefile component {suffix}")
                files.append(companion)
            files.extend(path.with_suffix(s) for s in [".cpg"] if path.with_suffix(s).exists())
        entries = [{"path": str(p), "bytes": p.stat().st_size, "sha256": digest(p)} for p in files]
        used.update(p.name for p in files)
        sources.append({**spec, "files": entries, "reference_year_confirmed": False,
                        "definition_confirmed": False, "derived_from": [],
                        "lineage_status": "requires_review", "usage_conditions": "unrecorded",
                        "quality_flags": ["reference_period_and_provenance_require_review"]})
    # Inventory auxiliary inputs, without pretending they have been processed or hashed.
    auxiliary = [{"file": p.name, "bytes": p.stat().st_size, "sha256": None,
                  "status": "inventory_only_not_ingested"}
                 for p in sorted(root.iterdir()) if p.is_file() and p.name not in used]
    duplicates = {}
    for source in sources:
        for f in source["files"]:
            duplicates.setdefault(f["sha256"], []).append(f["path"])
    return {"sources": sources, "auxiliary_inventory": auxiliary,
            "byte_identical_files": [v for v in duplicates.values() if len(v) > 1]}


def _counts(series):
    return {str(k): int(v) for k, v in series.value_counts(dropna=False).items()}


def _atomic_json(path, value):
    """Publish the foundation's consumer manifest only as one complete JSON file."""
    path = Path(path)
    temporary = path.with_name(f".{path.name}.{uuid.uuid4().hex}.tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    os.replace(temporary, path)


def _write_artifact_manifest(run, state):
    """Certify the neutral tables which a baseline is allowed to consume."""
    run = Path(run)
    names = ("sites.parquet", "dhl_observations.parquet", "hermes_observations.parquet",
             "postal_support.parquet", "site_postal_candidates.parquet")
    artifacts = {}
    for name in names:
        artifact = run / name
        if not artifact.is_file():
            raise ValueError(f"Foundation artifact is missing: {name}")
        table = gpd.read_parquet(artifact) if name in {
            "sites.parquet", "dhl_observations.parquet", "postal_support.parquet"
        } else pd.read_parquet(artifact)
        geometry = "geometry" in table.columns
        artifacts[name] = {
            "relative_path": name,
            "sha256": digest(artifact),
            "schema": table.columns.tolist(),
            "crs": f"EPSG:{table.crs.to_epsg()}" if geometry and table.crs.to_epsg() is not None
            else table.crs.to_wkt() if geometry else None,
        }
    _atomic_json(run / "artifact_manifest.json", {
        "schema_version": 1,
        "status": "complete",
        "run_id": state["run_id"],
        "run_status": state["status"],
        "artifacts": artifacts,
    })


def run_foundation(config_path, run_id):
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,100}", run_id):
        raise ValueError("Invalid run_id")
    cfg = load_config(config_path)
    output_root = Path(cfg["output_dir"])
    run = output_root / run_id
    if run.resolve().is_relative_to(Path(cfg["input_dir"])):
        raise ValueError("Output may not be inside the input directory")
    run.mkdir(parents=True, exist_ok=False)
    state = {"run_id": run_id, "started_at": datetime.now(timezone.utc).isoformat(),
             "status": "running", "completed_stages": []}
    write_json(run / "run.json", state)

    def completed(name):
        state["completed_stages"].append(name)
        write_json(run / "run.json", state)
        print(f"Completed: {name}", flush=True)

    try:
        write_json(run / "config.resolved.json", cfg)
        source_code = {p.name: digest(p) for p in sorted(Path(__file__).parent.iterdir()) if p.suffix in {".py", ".html"}}
        try:
            revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=Path(__file__).parent,
                                                text=True, stderr=subprocess.DEVNULL).strip()
        except (OSError, subprocess.CalledProcessError):
            revision = None
        write_json(run / "runtime.json", {"python": platform.python_version(), "git_revision": revision,
                   "source_code_sha256": source_code, "config_sha256": digest(config_path),
                   "packages": {n: importlib.metadata.version(n) for n in
                                ["pandas", "numpy", "geopandas", "shapely", "pyogrio", "pyarrow"]},
                   "randomness": "none; deterministic foundation"})
        print("Hashing consumed inputs and shapefile components...", flush=True)
        manifest = source_manifest(cfg)
        write_json(run / "sources.json", manifest)
        completed("ingest")
        paths = {s["adapter"]: Path(cfg["input_dir"]) / s["file"] for s in cfg["sources"]}
        persons = read_persons(paths["persons"], cfg["persons_crs"], cfg["target_crs"])
        person_count = len(persons)
        residential = build_residential(persons, cfg["building_spread_tolerance_m"])
        del persons
        business = build_business(paths["companies"], cfg["target_crs"])
        cols = ["site_id", "source_id", "source_key", "recipient_type", "population", "known_households",
                "missing_households", "employees", "branch", "spread_m", "location_status", "geometry"]
        sites = gpd.GeoDataFrame(pd.concat([residential[cols], business[cols]], ignore_index=True), crs=cfg["target_crs"])
        require_unique(sites, "site_id", "sites")
        if int(sites.population.sum()) != person_count:
            raise ValueError("Population balance failed")
        sites.to_parquet(run / "sites.parquet", index=False)
        # Separate demand identity allows later physical co-location without merging firms.
        units = sites[["site_id", "recipient_type", "source_id", "population", "employees", "branch"]].copy()
        units.insert(0, "demand_unit_id", "unit:" + units.site_id)
        units.to_parquet(run / "demand_units.parquet", index=False)
        completed("build_sites")
        dhl = read_dhl(paths["dhl"], cfg["target_crs"])
        hermes = read_hermes(paths["hermes"])
        postal = read_plz(paths["plz"], cfg["plz_crs"], cfg["target_crs"])
        dhl.to_parquet(run / "dhl_observations.parquet", index=False)
        hermes.to_parquet(run / "hermes_observations.parquet", index=False)
        postal.to_parquet(run / "postal_support.parquet", index=False)
        audit = {"persons": person_count, "residential_units": len(residential), "business_units": len(business),
                 "sites": len(sites), "population_balance": int(sites.population.sum()) == person_count,
                 "missing_household_persons": int(residential.missing_households.sum()),
                 "residential_locations": _counts(residential.location_status),
                 "business_locations": _counts(business.location_status),
                 "invalid_business_employee_values": int(business.invalid_employees.sum()),
                 "dhl_rows": len(dhl), "dhl_value_status": _counts(dhl.value_status),
                 "dhl_unusable_geometries": int((~dhl.geometry_usable).sum()),
                 "dhl_rows_with_repeated_street_key": int(dhl.repeated_street_key.sum()),
                 "hermes_rows": len(hermes), "hermes_value_status": _counts(hermes.value_status),
                 "hermes_plz_without_polygon": int((~hermes.plz.isin(postal.plz)).sum()),
                 "crs_provenance": cfg["crs_provenance"]}
        write_json(run / "audit.json", audit)
        completed("audit_observations")
        links, membership, status = candidate_links(sites, dhl, postal, cfg["max_street_distance_m"])
        links.to_parquet(run / "dhl_candidate_links.parquet", index=False)
        # This is a spatial candidate, never independent PLZ evidence for an
        # unresolved geometry.  The baseline consumer uses the label rather
        # than silently upgrading it to a verified postal assignment.
        membership["plz_evidence"] = "spatial_candidate_unverified"
        membership.to_parquet(run / "site_postal_candidates.parquet", index=False)
        status.to_parquet(run / "site_link_status.parquet", index=False)
        # Hermes links represent polygon membership candidates, not verified measurement coverage.
        hermes_links = membership.dropna(subset=["plz"]).merge(hermes[["observation_id", "plz", "year"]], on="plz")
        hermes_links["link_status"] = "postal_membership_unverified"
        hermes_links.loc[hermes_links.postal_candidates > 1, "link_status"] = "ambiguous_postal_boundary"
        hermes_links.to_parquet(run / "hermes_candidate_links.parquet", index=False)
        completed("link_candidates")
        groups = status.groupby("link_status").agg(sites=("site_id", "size"), population=("population", "sum"))
        summary = {"run_id": run_id, "foundation_complete": True, "calibration_ready": False,
                   "counts": audit, "candidate_link_rows": len(links), "hermes_candidate_link_rows": len(hermes_links),
                   "link_status": {str(k): {"sites": int(v.sites), "population": int(v.population)}
                                   for k, v in groups.iterrows()},
                   "blockers_for_calibration": ["Confirm observation units, reference windows and segment definitions",
                     "Resolve repeated LSP street records: fragmentation versus distinct observations",
                     "Validate candidate links using address/entrance or source mapping evidence",
                     "Confirm source years, spatial provenance and PLZ CRS metadata"],
                   "limits": ["No parcel demand has been estimated", "No observed counts distributed to sites",
                              "Candidate links carry no allocation weights", "No travel access or entrances inferred"]}
        write_json(run / "summary.json", summary)
        rows = "\n".join(f"| {k} | {int(v.sites):,} | {int(v.population):,} |" for k, v in groups.iterrows())
        report = f"""# HAGRID data foundation: {run_id}

The data run is complete. **Not yet approved for demand calibration.**

| Inventory | Count |
|---|---:|
| Persons | {person_count:,} |
| Private building units | {len(residential):,} |
| Firm sites | {len(business):,} |
| LSP line observations | {len(dhl):,} |
| Hermes PLZ/year observations | {len(hermes):,} |

## Spatial candidate assignment

| Status | Sites | Residents |
|---|---:|---:|
{rows}

The assignment uses uniquely assigned PLZ and the nearest LSP line up to {cfg['max_street_distance_m']} m.
This is a documented candidate search, not proof of the actual delivery street.
Equidistant candidates and repeated street keys remain visible.
Sites with contradictory building coordinates are not linked automatically.
Parcel volumes were neither estimated nor distributed to sites.

## To clarify before calibration

- Unit and period of the DHL/Hermes values; zero values versus missing coverage.
- Meaning of repeated LSP street keys: line fragments or separate observations.
- Confirm candidates using addresses, entrances or original assignments.
- Confirm reference years, provenance of the site data and original PLZ CRS metadata.

## Traceability

`sources.json` contains SHA-256 hashes of all consumed files including SHP components.
`config.resolved.json`, `runtime.json` and `run.json` document configuration, code and stages.
`audit.json` and `summary.json` contain aggregated checks. Parquet files contain local
sites and source identifiers; the run directory is excluded from Git tracking.
"""
        (run / "report.md").write_text(report, encoding="utf-8")
        completed("report")
        build_dashboard(run)
        completed("dashboard")
        state["status"] = "complete_with_calibration_blockers"
        state["finished_at"] = datetime.now(timezone.utc).isoformat()
        write_json(run / "run.json", state)
        _write_artifact_manifest(run, state)
        return run
    except Exception as exc:
        state["status"] = "failed"
        state["error"] = f"{type(exc).__name__}: {exc}"
        write_json(run / "run.json", state)
        raise
