import hashlib
import json

import geopandas as gpd
import pandas as pd
import pytest

from baseline_fixtures import write_fixture


def _foundation_fixture(tmp_path):
    """Materialize a verified foundation shaped like the shared foundation run."""
    from hagrid_demand.baseline.workflow import run_baseline

    raw_config = write_fixture(tmp_path)
    raw_run = run_baseline(raw_config, "raw-input")
    source = raw_run / "sources"
    foundation = tmp_path / "foundation"
    foundation.mkdir()

    sites = gpd.read_parquet(source / "sites.parquet")
    unresolved_site = sites.iloc[0].site_id
    sites.loc[sites.site_id.eq(unresolved_site), "location_status"] = "missing_geometry"
    membership = sites[["site_id", "plz"]].copy()
    membership["plz_evidence"] = "spatial_candidate_unverified"
    membership.loc[membership.site_id.eq(unresolved_site), "plz_evidence"] = "independently_verified"
    sites = sites.drop(columns="plz")
    sites.to_parquet(foundation / "sites.parquet", index=False)
    membership.to_parquet(foundation / "site_postal_candidates.parquet", index=False)
    for name in ("dhl_observations.parquet", "hermes_observations.parquet", "postal_support.parquet"):
        (foundation / name).write_bytes((source / name).read_bytes())

    names = ("sites.parquet", "site_postal_candidates.parquet", "dhl_observations.parquet",
             "hermes_observations.parquet", "postal_support.parquet")
    geometry_names = {"sites.parquet", "dhl_observations.parquet", "postal_support.parquet"}
    artifacts = {}
    for name in names:
        table = gpd.read_parquet(foundation / name) if name in geometry_names else pd.read_parquet(foundation / name)
        artifacts[name] = {
            "relative_path": name,
            "sha256": hashlib.sha256((foundation / name).read_bytes()).hexdigest(),
            "schema": table.columns.tolist(),
            "crs": f"EPSG:{table.crs.to_epsg()}" if name in geometry_names else None,
        }
    manifest = {
        "schema_version": 1,
        "status": "complete",
        "run_id": "fixture-foundation",
        "run_status": "complete_with_calibration_blockers",
        "artifacts": artifacts,
    }
    (foundation / "artifact_manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
    (foundation / "run.json").write_text(json.dumps({
        "run_id": "fixture-foundation",
        "status": "complete_with_calibration_blockers",
        "completed_stages": ["ingest", "build_sites", "audit_observations", "link_candidates", "report", "dashboard"],
    }), encoding="utf-8")

    config = json.loads(raw_config.read_text(encoding="utf-8"))
    config.update({"source_mode": "foundation_run", "foundation_run": "foundation", "output_dir": "foundation-output"})
    config_path = tmp_path / "foundation-baseline.json"
    config_path.write_text(json.dumps(config), encoding="utf-8")
    return config_path, foundation, unresolved_site


def test_foundation_run_preserves_unverified_location_status_for_known_postal_site(tmp_path):
    """A known postal code does not turn an unresolved site into a located allocation."""
    from hagrid_demand.baseline.workflow import run_baseline

    config, _foundation, unresolved_site = _foundation_fixture(tmp_path)
    run = run_baseline(config, "foundation-location")

    staged = gpd.read_parquet(run / "sources" / "sites.parquet").set_index("site_id")
    reference = pd.read_parquet(run / "reference_sites.parquet").set_index("site_id")
    assert staged.loc[unresolved_site, "plz"] == "01000"
    assert staged.loc[unresolved_site, "allocation_status"] == "unlocated"
    assert reference.loc[unresolved_site, "allocation_status"] == "unlocated"


def test_foundation_run_requires_location_status_in_verified_site_table(tmp_path):
    """Foundation mode must fail explicitly instead of implicitly treating missing status as located."""
    from hagrid_demand.baseline.workflow import run_baseline

    config, foundation, _unresolved_site = _foundation_fixture(tmp_path)
    sites = gpd.read_parquet(foundation / "sites.parquet").drop(columns="location_status")
    sites.to_parquet(foundation / "sites.parquet", index=False)
    manifest_path = foundation / "artifact_manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    manifest["artifacts"]["sites.parquet"]["sha256"] = hashlib.sha256((foundation / "sites.parquet").read_bytes()).hexdigest()
    manifest["artifacts"]["sites.parquet"]["schema"].remove("location_status")
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

    with pytest.raises(ValueError, match="foundation sites missing required columns.*location_status"):
        run_baseline(config, "foundation-missing-status")


def test_actual_foundation_producer_manifest_is_consumed_by_baseline_without_nesting(tmp_path):
    """The neutral producer's completed manifest, rather than a fabricated fixture, powers foundation mode."""
    from hagrid_demand.pipeline import run_foundation
    from hagrid_demand.baseline.workflow import run_baseline

    raw_config = write_fixture(tmp_path)
    raw_payload = json.loads(raw_config.read_text(encoding="utf-8"))
    foundation_config = {
        "input_dir": "inputs", "output_dir": "foundation-runs", "persons_crs": "EPSG:25832",
        "plz_crs": "EPSG:25832", "target_crs": "EPSG:25832", "max_street_distance_m": 100,
        "building_spread_tolerance_m": 5, "observation_definitions_confirmed": False,
        "crs_provenance": "fixture", "sources": raw_payload["sources"],
    }
    foundation_config_path = tmp_path / "foundation.json"
    foundation_config_path.write_text(json.dumps(foundation_config), encoding="utf-8")
    foundation = run_foundation(foundation_config_path, "actual-foundation")
    manifest = json.loads((foundation / "artifact_manifest.json").read_text(encoding="utf-8"))
    assert manifest["status"] == "complete"
    assert manifest["artifacts"]["sites.parquet"]["relative_path"] == "sites.parquet"
    assert manifest["artifacts"]["sites.parquet"]["sha256"]
    assert manifest["artifacts"]["sites.parquet"]["crs"] == "EPSG:25832"

    config = raw_payload
    config.update({"source_mode": "foundation_run", "foundation_run": str(foundation),
                   "output_dir": "foundation-baseline-output"})
    config_path = tmp_path / "baseline-foundation.json"
    config_path.write_text(json.dumps(config), encoding="utf-8")
    run = run_baseline(config_path, "actual-foundation-baseline")
    assert json.loads((run / "run.json").read_text(encoding="utf-8"))["status"] == "complete_reference"
