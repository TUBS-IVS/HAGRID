"""Package inputs and auditable source preparation for the demand baseline."""

from __future__ import annotations

import hashlib
import importlib.resources
import json
from pathlib import Path
from typing import Any

import pandas as pd


_INPUT_FILES = ("market_inputs.json", "b2b_inputs.json", "volume_inputs.json", "provider_priors.json")


def _digest(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(8 * 1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def packaged_series_inputs() -> dict[str, Any]:
    """Read only the four versioned constant files installed with this package."""
    data = importlib.resources.files("hagrid_demand").joinpath("baseline/data")
    return {
        filename.removesuffix(".json"): json.loads(data.joinpath(filename).read_text(encoding="utf-8"))
        for filename in _INPUT_FILES
    }


def _weekly_profile(path: Path) -> pd.DataFrame:
    """Reproduce notebook 03's 52-week relative profile without importing its export."""
    # The historical workbook has its real headings on the second physical row; test and
    # replacement workbooks commonly have them on the first one.
    weekly = pd.read_excel(path, sheet_name="Tabelle1")
    if not {"2019", "2020", "2021"}.issubset({str(column) for column in weekly.columns}):
        weekly = pd.read_excel(path, sheet_name="Tabelle1", header=1)
    weekly.columns = [str(column).replace(".0", "") for column in weekly.columns]
    required = ["2019", "2020", "2021"]
    if not set(required).issubset(weekly.columns):
        raise ValueError("Weekly workbook must contain 2019, 2020, and 2021 columns")
    week_column = next((column for column in weekly.columns if column.lower() in {"week", "kw", "calendar_week"}),
                       weekly.columns[0])
    result = pd.DataFrame({"week": pd.to_numeric(weekly[week_column], errors="coerce")})
    for year in required:
        result[year] = pd.to_numeric(weekly[year], errors="coerce")
    result = result.loc[result.week.between(1, 52)].dropna(subset=["week"]).copy()
    result["week"] = result["week"].astype(int)
    if result.week.duplicated().any() or result.week.tolist() != list(range(1, 53)):
        raise ValueError("Weekly workbook must contain each numeric calendar week 1 through 52 once")
    relative = result[required].div(result[required].mean(axis=0), axis=1)
    result = result[["week"]].copy()
    result["relative_volume"] = relative.mean(axis=1)
    result["relative_volume"] = result["relative_volume"] / result["relative_volume"].mean()
    if result.relative_volume.isna().any() or (result.relative_volume <= 0).any():
        raise ValueError("Weekly source must yield positive relative values for all 52 weeks")
    return result


def read_foundation(path: Path) -> dict[str, pd.DataFrame]:
    """Load only manifest-hashed foundation tables and check their declared schemas."""
    path = Path(path)
    manifest_path = path / "artifact_manifest.json"
    if not manifest_path.is_file():
        raise FileNotFoundError(f"Foundation artifact manifest is required: {manifest_path}")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    artifacts = manifest.get("artifacts")
    schemas = manifest.get("schemas")
    if not isinstance(artifacts, dict) or not isinstance(schemas, dict):
        raise ValueError("Foundation artifact manifest requires artifacts and schemas mappings")
    tables: dict[str, pd.DataFrame] = {}
    for name, expected in artifacts.items():
        if not isinstance(name, str) or not isinstance(expected, (str, dict)):
            raise ValueError("Foundation artifact manifest has an invalid artifact entry")
        expected_hash = expected if isinstance(expected, str) else expected.get("sha256")
        artifact = path / name
        if not isinstance(expected_hash, str) or not artifact.is_file() or _digest(artifact) != expected_hash:
            raise ValueError(f"Foundation artifact hash mismatch: {name}")
        if artifact.suffix == ".parquet":
            table = pd.read_parquet(artifact)
        elif artifact.suffix == ".csv":
            table = pd.read_csv(artifact)
        else:
            raise ValueError(f"Unsupported foundation artifact table: {name}")
        columns = schemas.get(name)
        if not isinstance(columns, list) or any(column not in table.columns for column in columns):
            raise ValueError(f"Foundation artifact schema mismatch: {name}")
        tables[name] = table
    return tables


def prepare_sources(config: dict, output: Path) -> dict[str, Any]:
    """Prepare raw weekly seasonality or verify an already materialized foundation.

    This deliberately never invokes ``pipeline.run_foundation``: the baseline owns its
    run state and foundation mode is a read-and-verify operation.
    """
    mode = config.get("source_mode")
    output = Path(output)
    if mode == "foundation_run":
        run = config.get("foundation_run")
        if not run:
            raise ValueError("foundation_run is required in foundation_run mode")
        tables = read_foundation(Path(run))
        return {"mode": mode, "foundation": tables}
    if mode != "raw":
        raise ValueError("source_mode must be raw or foundation_run")
    root = Path(config["input_dir"])
    source = Path(config.get("weekly_source") or root / "Parcels19_20_21_inter.xlsx")
    if not source.is_file():
        raise FileNotFoundError(f"Missing weekly source: {source}")
    profile = _weekly_profile(source)
    output.mkdir(parents=True, exist_ok=True)
    profile.to_csv(output / "weekly_profile.csv", index=False)
    provenance = {
        "weekly_source": str(source.resolve()),
        "weekly_source_sha256": _digest(source),
        "source": "Parcels19_20_21_inter.xlsx / Tabelle1",
        "notebook_cell": "03_EstimateWeekyParcelDistribution.py CELL 2",
        "unit": "relative weekly factor (mean=1)",
        "status": "derived_from_raw_source",
    }
    (output / "sources.json").write_text(json.dumps(provenance, indent=2) + "\n", encoding="utf-8")
    return {"mode": mode, "weekly_profile": profile, "sources": provenance}
