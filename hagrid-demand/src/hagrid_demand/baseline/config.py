"""Validation and resolution for baseline JSON configurations."""

from __future__ import annotations

import datetime as dt
import json
import math
from pathlib import Path
from typing import Any

from hagrid_demand.common.rng import RNG_VERSION


SCHEMA_VERSION = 1
_ALLOWED_KEYS = {
    "schema_version", "rng_version", "seed", "input_dir", "output_dir", "cache_root", "dashboard_root",
    "source_mode", "sources", "foundation_run", "weekly_source", "reference_year", "reference_operating_days",
    "output_scope", "dates", "years", "legacy_export", "persons_crs", "plz_crs", "target_crs",
    "dhl_exclude_above", "regional_level", "weight", "stock_updates", "baseline_run", "assumptions",
}
_PATH_KEYS = {"input_dir", "output_dir", "cache_root", "dashboard_root", "foundation_run", "weekly_source", "stock_updates", "baseline_run"}


def _reject_nonfinite(value: Any) -> None:
    if isinstance(value, float) and not math.isfinite(value):
        raise ValueError("Configuration values must be finite")
    if isinstance(value, dict):
        for item in value.values():
            _reject_nonfinite(item)
    elif isinstance(value, list):
        for item in value:
            _reject_nonfinite(item)


def _resolve(path: Path, value: str | None) -> str | None:
    return str((path.parent / value).resolve()) if value is not None else None


def _validate_dates(dates: list[Any]) -> None:
    normalized = []
    for value in dates:
        if not isinstance(value, str):
            raise ValueError("dates must contain ISO date strings")
        try:
            normalized.append(dt.date.fromisoformat(value).isoformat())
        except ValueError as exc:
            raise ValueError(f"Invalid ISO date: {value!r}") from exc
    if len(normalized) != len(set(normalized)):
        raise ValueError("Configuration contains duplicate dates")


def load_baseline_config(path: Path) -> dict:
    """Load a strict baseline config and resolve all declared paths at its location."""
    path = Path(path).resolve()
    try:
        config = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise ValueError(f"Invalid baseline JSON: {exc}") from exc
    if not isinstance(config, dict):
        raise ValueError("Baseline config must be a JSON object")
    unknown = set(config) - _ALLOWED_KEYS
    if unknown:
        raise ValueError(f"Unknown baseline config keys: {', '.join(sorted(unknown))}")
    _reject_nonfinite(config)
    required = {"schema_version", "rng_version", "seed", "input_dir", "output_dir", "source_mode",
                "reference_year", "reference_operating_days", "output_scope"}
    missing = required - set(config)
    if missing:
        raise ValueError(f"Missing baseline config keys: {', '.join(sorted(missing))}")
    if config["schema_version"] != SCHEMA_VERSION or config["rng_version"] != RNG_VERSION:
        raise ValueError("Unsupported baseline schema or RNG version")
    if isinstance(config["seed"], bool) or not isinstance(config["seed"], int):
        raise ValueError("seed must be an integer")
    if config["source_mode"] not in {"raw", "foundation_run"}:
        raise ValueError("source_mode must be raw or foundation_run")
    if config["output_scope"] not in {"reference", "daily"}:
        raise ValueError("output_scope must be reference or daily")
    if not isinstance(config["reference_year"], int) or config["reference_year"] < 2021:
        raise ValueError("reference_year must be an integer from 2021")
    if not isinstance(config["reference_operating_days"], int) or config["reference_operating_days"] <= 0:
        raise ValueError("reference_operating_days must be a positive integer")
    if "dates" in config:
        if not isinstance(config["dates"], list):
            raise ValueError("dates must be a list")
        _validate_dates(config["dates"])
    for key in _PATH_KEYS & set(config):
        if config[key] is not None:
            if not isinstance(config[key], str):
                raise ValueError(f"{key} must be a path string")
            config[key] = _resolve(path, config[key])
    if config.get("cache_root") is None:
        config["cache_root"] = str(Path(config["output_dir"]) / ".stage-cache")
    input_paths = [Path(config["input_dir"])]
    for key in ("foundation_run", "weekly_source", "stock_updates"):
        if config.get(key):
            input_paths.append(Path(config[key]))
    output_paths = [Path(config["output_dir"])]
    if config.get("cache_root"):
        output_paths.append(Path(config["cache_root"]))
    if config.get("dashboard_root"):
        output_paths.append(Path(config["dashboard_root"]))
    for output in output_paths:
        if any(output.is_relative_to(input_path) for input_path in input_paths):
            raise ValueError("output paths may not be inside input paths")
    config["config_path"] = str(path)
    return config
