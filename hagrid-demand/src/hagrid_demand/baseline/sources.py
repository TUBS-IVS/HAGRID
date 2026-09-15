"""Package inputs and auditable source preparation for the demand baseline."""

from __future__ import annotations

import hashlib
import importlib.resources
import json
import copy
from pathlib import Path
from typing import Any

import pandas as pd


_INPUT_FILES = ("market_inputs.json", "b2b_inputs.json", "volume_inputs.json", "provider_priors.json")
_METADATA_FIELDS = ("source", "notebook_cell", "status", "unit")


def _require_metadata(value: dict[str, Any], label: str) -> None:
    for field in _METADATA_FIELDS:
        if not isinstance(value.get(field), str) or not value[field].strip():
            raise ValueError(f"{label}.{field} must be a non-empty string")


def _inherit_metadata(parent: dict[str, Any], value: dict[str, Any], label: str) -> dict[str, Any]:
    """Validate an explicitly stated field or copy the validated parent value."""
    inherited = dict(value)
    for field in _METADATA_FIELDS:
        inherited[field] = inherited.get(field, parent[field])
    _require_metadata(inherited, label)
    return inherited


def validate_series_inputs(inputs: dict[str, Any]) -> dict[str, Any]:
    """Strictly validate and materialize inherited provenance for each constant row."""
    result = copy.deepcopy(inputs)
    for name in ("market_inputs", "b2b_inputs", "volume_inputs", "provider_priors"):
        if not isinstance(result.get(name), dict):
            raise ValueError(f"{name} must be an object")
        _require_metadata(result[name], name)
    market = result["market_inputs"]
    if not isinstance(market.get("carrier_anchor"), dict) or not isinstance(market.get("amazon"), dict):
        raise ValueError("market_inputs requires carrier_anchor and amazon objects")
    market["carrier_anchor"] = _inherit_metadata(market, market["carrier_anchor"], "market_inputs.carrier_anchor")
    market["amazon"] = _inherit_metadata(market, market["amazon"], "market_inputs.amazon")
    for name, key in (("b2b_inputs", "anchors"), ("volume_inputs", "anchors")):
        spec = result[name]
        if not isinstance(spec.get(key), list):
            raise ValueError(f"{name}.{key} must be a list")
        normalized = []
        for index, item in enumerate(spec[key]):
            if not isinstance(item, dict):
                raise ValueError(f"{name}.{key}[{index}] must be an object")
            normalized.append(_inherit_metadata(spec, item, f"{name}.{key}[{index}]"))
        spec[key] = normalized
    providers = result["provider_priors"].get("providers")
    if not isinstance(providers, dict):
        raise ValueError("provider_priors.providers must be an object")
    normalized_providers = {}
    for provider, value in providers.items():
        if not isinstance(value, dict):
            raise ValueError(f"provider_priors.providers.{provider} must be an object")
        normalized_providers[provider] = _inherit_metadata(
            result["provider_priors"], value, f"provider_priors.providers.{provider}"
        )
    result["provider_priors"]["providers"] = normalized_providers
    return result


def _digest(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(8 * 1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def packaged_series_inputs() -> dict[str, Any]:
    """Read only the four versioned constant files installed with this package."""
    data = importlib.resources.files("hagrid_demand").joinpath("baseline/data")
    inputs = {
        filename.removesuffix(".json"): json.loads(data.joinpath(filename).read_text(encoding="utf-8"))
        for filename in _INPUT_FILES
    }
    return validate_series_inputs(inputs)


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
