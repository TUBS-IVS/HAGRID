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
    sites = sites.drop(columns="plz")
    sites.to_parquet(foundation / "sites.parquet", index=False)
    membership.to_parquet(foundation / "site_postal_candidates.parquet", index=False)
    for name in ("dhl_observations.parquet", "hermes_observations.parquet", "postal_support.parquet"):
        (foundation / name).write_bytes((source / name).read_bytes())

    schemas = {
        "sites.parquet": ["site_id", "recipient_type", "population", "employees", "location_status", "geometry"],
        "site_postal_candidates.parquet": ["site_id", "plz"],
        "dhl_observations.parquet": ["observation_id", "plz", "year", "value", "geometry"],
        "hermes_observations.parquet": ["observation_id", "plz", "year", "value"],
        "postal_support.parquet": ["plz", "geometry"],
    }
    manifest = {
        "artifacts": {
            name: hashlib.sha256((foundation / name).read_bytes()).hexdigest()
            for name in schemas
        },
        "schemas": schemas,
    }
    (foundation / "artifact_manifest.json").write_text(json.dumps(manifest), encoding="utf-8")

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
    manifest["artifacts"]["sites.parquet"] = hashlib.sha256((foundation / "sites.parquet").read_bytes()).hexdigest()
    manifest["schemas"]["sites.parquet"].remove("location_status")
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

    with pytest.raises(ValueError, match="foundation sites missing required columns.*location_status"):
        run_baseline(config, "foundation-missing-status")
