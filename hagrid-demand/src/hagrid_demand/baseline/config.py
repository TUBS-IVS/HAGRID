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
    "dhl_exclude_above", "regional_level", "weight", "stock_updates", "baseline_run", "assumptions", "spatial", "business_potential", "matsim_export", "volume_fit_policy", "reference_operating_days_rule",
    "osm_buildings", "osm_points", "buildings", "anchor", "stops", "notebook_output_dir", "temporal", "annual_store",
    "calendar", "process", "regime", "detail_draws", "legacy_contract", "legacy_grid", "legacy_samples",
}
_PATH_KEYS = {"notebook_output_dir", "osm_buildings", "osm_points", "input_dir", "output_dir", "cache_root", "dashboard_root", "foundation_run", "weekly_source", "stock_updates", "baseline_run", "legacy_contract", "legacy_grid", "legacy_samples"}
_SOURCE_PATH_KEYS = {"file", "path", "input_path", "source_path", "directory", "dir"}


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


def _validate_regional_level(value: Any) -> dict:
    if value is None:
        return {"mode": "national_series"}
    if not isinstance(value, dict):
        raise ValueError("regional_level must be an object")
    mode = value.get("mode")
    if mode not in {"national_series", "external_annual_series"}:
        raise ValueError("regional_level.mode must be national_series or external_annual_series")
    if mode == "external_annual_series" and not isinstance(value.get("series"), list):
        raise ValueError("external_annual_series requires a series list")
    return value


def _validate_spatial(value: Any) -> dict:
    if value is None:
        return {"mode": "dirichlet"}
    if not isinstance(value, dict):
        raise ValueError("spatial must be an object")
    mode = value.get("mode", "dirichlet")
    if mode not in {"dirichlet", "correlated"}:
        raise ValueError("spatial.mode must be dirichlet or correlated")
    if mode == "correlated":
        for name in ("length_scale_m", "log_sigma", "rho"):
            if name not in value or isinstance(value[name], bool) or not isinstance(value[name], (int, float)):
                raise ValueError(f"correlated spatial requires numeric {name}")
        if not value["length_scale_m"] > 0 or not value["log_sigma"] >= 0 or not 0 <= value["rho"] < 1:
            raise ValueError("correlated spatial parameters are out of range")
    for name in ("carrier_plz_log_sd", "site_frailty_cv"):
        if name in value and (isinstance(value[name], bool) or not isinstance(value[name], (int, float)) or not value[name] >= 0):
            raise ValueError(f"spatial.{name} must be a nonnegative number")
    return value


def _declared_source_paths(value: Any, *, config_path: Path, input_dir: Path) -> list[Path]:
    paths = []
    if isinstance(value, dict):
        for key, item in value.items():
            if key in _SOURCE_PATH_KEYS and isinstance(item, str):
                base = input_dir if key == "file" else config_path.parent
                paths.append((base / item).resolve())
            else:
                paths.extend(_declared_source_paths(item, config_path=config_path, input_dir=input_dir))
    elif isinstance(value, list):
        for item in value:
            paths.extend(_declared_source_paths(item, config_path=config_path, input_dir=input_dir))
    return paths


def calendar_delivery_days(year: int, calendar: Any) -> int:
    """Count the dates of *year* with positive delivery weight under the daily calendar settings."""
    from .calendar import DEFAULT_WEEKDAY_WEIGHTS, public_holidays

    if not isinstance(calendar, dict):
        raise ValueError("calendar must be a mapping")
    weekdays = calendar.get("weekday_weights", {}).get("private", DEFAULT_WEEKDAY_WEIGHTS)
    holidays = set(public_holidays(year, calendar.get("holiday_region", "NI"))) | set(calendar.get("holiday_dates", []))
    closed = float(calendar.get("holiday_factor", 0.)) == 0
    count = 0
    day = dt.date(year, 1, 1)
    while day.year == year:
        if float(weekdays[day.weekday()]) > 0 and not (closed and day.isoformat() in holidays):
            count += 1
        day += dt.timedelta(days=1)
    return count


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
    if config["reference_operating_days"] == "calendar":
        config["reference_operating_days"] = calendar_delivery_days(config["reference_year"], config.get("calendar", {}))
        config["reference_operating_days_rule"] = "calendar"
    if (isinstance(config["reference_operating_days"], bool)
            or not isinstance(config["reference_operating_days"], int)
            or config["reference_operating_days"] <= 0):
        raise ValueError("reference_operating_days must be a positive integer or \"calendar\"")
    if "dates" in config:
        if not isinstance(config["dates"], list):
            raise ValueError("dates must be a list")
        _validate_dates(config["dates"])
    if "years" in config and (not isinstance(config["years"], list) or not config["years"]
                               or any(type(year) is not int or year < 2021 for year in config["years"])):
        raise ValueError("years must contain integer years from 2021")
    config.setdefault("years", [config["reference_year"]])
    if config.get("legacy_export") and not isinstance(config.get("legacy_contract"), str):
        raise ValueError("legacy_export requires legacy_contract")
    config["regional_level"] = _validate_regional_level(config.get("regional_level"))
    config["spatial"] = _validate_spatial(config.get("spatial"))
    for key in _PATH_KEYS & set(config):
        if config[key] is not None:
            if not isinstance(config[key], str):
                raise ValueError(f"{key} must be a path string")
            config[key] = _resolve(path, config[key])
    if config.get("cache_root") is None:
        config["cache_root"] = str(Path(config["output_dir"]) / ".stage-cache")
    input_paths = [Path(config["input_dir"])]
    for key in ("foundation_run", "weekly_source", "stock_updates", "baseline_run"):
        if config.get(key):
            input_paths.append(Path(config[key]))
    input_paths.extend(_declared_source_paths(config.get("sources", []), config_path=path,
                                              input_dir=Path(config["input_dir"])))
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
