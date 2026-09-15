"""Calibrated, coordinate-based spatial allocation fields.

The correlated alternative deliberately lives beside the Dirichlet allocator:
it modifies only site shares and never the annual or daily segment total.
"""

from __future__ import annotations

import json
import math
import os
from pathlib import Path
from typing import Iterator

import numpy as np
import pandas as pd

from hagrid_demand.common.contracts import AnnualProjection, SpatialPlan
from hagrid_demand.common.provenance import canonical_digest
from hagrid_demand.common.rng import RNG_VERSION, named_rng


_ALGORITHM_VERSION = 1
_FIELD_VERSION = 1
_CHECKPOINTS: dict[str, dict[int, np.ndarray]] = {}


def _finite_vector(value: object, label: str, *, positive_sum: bool = True) -> np.ndarray:
    result = np.asarray(value, dtype=float)
    if result.ndim != 1 or not np.isfinite(result).all() or (result < 0).any() or (positive_sum and result.sum() <= 0):
        raise ValueError(f"{label} must be one-dimensional finite nonnegative weights with positive support")
    return result


def _xy(value: object, count: int) -> np.ndarray:
    result = np.asarray(value, dtype=float)
    if result.shape != (count, 2) or not np.isfinite(result).all():
        raise ValueError("xy must be finite projected x/y coordinates for every localized site")
    return result


def _site_ids(value: object, count: int) -> np.ndarray:
    result = np.asarray(value, dtype=object)
    identifiers = [str(item).strip() for item in result]
    if result.ndim != 1 or len(result) != count or any(not item for item in identifiers) or len(set(identifiers)) != count:
        raise ValueError("site_ids must be unique non-blank identifiers")
    return np.asarray(identifiers, dtype=object)


def _number(value: object, label: str, *, lower: float | None = None, upper: float | None = None) -> float:
    try:
        result = float(value)
    except (TypeError, ValueError) as exc:
        raise ValueError(f"{label} must be numeric") from exc
    if not math.isfinite(result) or (lower is not None and result < lower) or (upper is not None and result > upper):
        raise ValueError(f"invalid {label}")
    return result


def _parameters(parameters: dict) -> dict:
    if not isinstance(parameters, dict):
        raise ValueError("spatial parameters must be a mapping")
    features = parameters.get("fourier_features", parameters.get("features", 64))
    if isinstance(features, bool) or not isinstance(features, (int, np.integer)) or not 8 <= int(features) <= 4096:
        raise ValueError("fourier_features must be an integer in [8, 4096]")
    seed = parameters.get("seed", 0)
    if isinstance(seed, bool) or not isinstance(seed, (int, np.integer)):
        raise ValueError("spatial seed must be an integer")
    anchor = str(parameters.get("field_anchor_date", "2021-01-01"))
    try:
        anchor = pd.Timestamp(anchor).normalize().date().isoformat()
    except (TypeError, ValueError) as exc:
        raise ValueError("field_anchor_date must be an ISO date") from exc
    return {"length_scale_m": _number(parameters.get("length_scale_m"), "length_scale_m", lower=np.finfo(float).eps),
            "log_sigma": _number(parameters.get("log_sigma", 0.), "log_sigma", lower=0.),
            "rho": _number(parameters.get("rho", 0.), "rho", lower=0., upper=np.nextafter(1., 0.)),
            "fourier_features": int(features), "seed": int(seed), "field_anchor_date": anchor,
            "field_version": _FIELD_VERSION,
            "segment": str(parameters.get("segment", "all")),
            "checkpoint_interval": int(parameters.get("checkpoint_interval", 31))}


def _design(design: dict) -> dict:
    if not isinstance(design, dict):
        raise ValueError("spatial calibration design must be a mapping")
    result = {"calibration_draws": int(design.get("calibration_draws", 512)),
              "validation_draws": int(design.get("validation_draws", 2048)),
              "calibration_seed": int(design.get("calibration_seed", 101)),
              "validation_seed": int(design.get("validation_seed", 202)),
              "max_iterations": int(design.get("max_iterations", 50)),
              "calibration_algorithm_version": int(design.get("calibration_algorithm_version", _ALGORITHM_VERSION))}
    if any(value <= 0 for key, value in result.items() if key.endswith("draws") or key == "max_iterations"):
        raise ValueError("calibration draws and max_iterations must be positive")
    if result["calibration_seed"] == result["validation_seed"]:
        raise ValueError("calibration and validation seeds must be separate")
    if result["calibration_algorithm_version"] != _ALGORITHM_VERSION:
        raise ValueError("unsupported calibration algorithm version")
    return result


def spatial_basis(xy: np.ndarray, parameters: dict, seed_keys: dict | None = None) -> np.ndarray:
    """Return a deterministic random-Fourier basis for a projected coordinate set."""
    params = _parameters(parameters)
    points = _xy(xy, len(xy))
    keys = seed_keys or {}
    base_seed = int(keys.get("basis_seed", params["seed"]))
    rng = named_rng(base_seed, channel="spatial-basis", field_version=_FIELD_VERSION,
                    segment=keys.get("segment", params["segment"]), features=params["fourier_features"],
                    length_scale_m=params["length_scale_m"])
    frequencies = rng.normal(size=(2, params["fourier_features"])) / params["length_scale_m"]
    phases = rng.uniform(0., 2 * np.pi, params["fourier_features"])
    return np.cos(points @ frequencies + phases) * math.sqrt(2. / params["fourier_features"])


def _state(day: int, params: dict, keys: dict) -> np.ndarray:
    """Advance a stationary AR(1) state from a reusable block checkpoint."""
    if day < 0:
        raise ValueError("spatial field date must not precede field_anchor_date")
    state_keys = {key: value for key, value in keys.items() if key not in {"date", "year", "channel", "checkpoint_dir"}}
    payload = {"params": params, "seed_keys": state_keys, "rng_version": RNG_VERSION}
    identity = canonical_digest(payload)
    interval = max(1, params["checkpoint_interval"])
    checkpoints = _CHECKPOINTS.setdefault(identity, {})
    checkpoint_root = keys.get("checkpoint_dir")
    checkpoint_dir = Path(checkpoint_root) / identity if checkpoint_root is not None else None
    if checkpoint_dir is not None and checkpoint_dir.is_dir():
        for path in checkpoint_dir.glob("*.npy"):
            try:
                point = int(path.stem)
                cached = np.load(path, allow_pickle=False)
            except (OSError, ValueError):
                continue
            if point <= day and cached.shape == (params["fourier_features"],) and np.isfinite(cached).all():
                checkpoints.setdefault(point, np.asarray(cached, dtype=float))
    if 0 not in checkpoints:
        checkpoints[0] = named_rng(int(keys.get("seed", params["seed"])), channel="spatial-state-start",
                                   field_version=_FIELD_VERSION, **{key: value for key, value in state_keys.items() if key != "seed"}).normal(size=params["fourier_features"])
    start = max((point for point in checkpoints if point <= day), default=0)
    state = checkpoints[start].copy()
    for offset in range(start + 1, day + 1):
        innovation = named_rng(int(keys.get("seed", params["seed"])), channel="spatial-state-innovation",
                                field_version=_FIELD_VERSION, day_offset=offset,
                                **{key: value for key, value in state_keys.items() if key != "seed"}).normal(size=params["fourier_features"])
        state = params["rho"] * state + math.sqrt(1. - params["rho"] ** 2) * innovation
        if offset % interval == 0:
            checkpoints[offset] = state.copy()
            if checkpoint_dir is not None:
                checkpoint_dir.mkdir(parents=True, exist_ok=True)
                temporary = checkpoint_dir / f".{offset}.tmp"
                with temporary.open("wb") as stream:
                    np.save(stream, state, allow_pickle=False)
                os.replace(temporary, checkpoint_dir / f"{offset}.npy")
    return state


def spatial_field(date: str, site_ids: np.ndarray, xy: np.ndarray, parameters: dict, seed_keys: dict) -> np.ndarray:
    """Generate one stationary, time-persistent Gaussian field without PLZ barriers."""
    params = _parameters(parameters)
    identifiers = _site_ids(site_ids, len(site_ids))
    points = _xy(xy, len(identifiers))
    if not isinstance(seed_keys, dict):
        raise ValueError("seed_keys must be a mapping")
    try:
        offset = (pd.Timestamp(date).normalize().date() - pd.Timestamp(params["field_anchor_date"]).date()).days
    except (TypeError, ValueError) as exc:
        raise ValueError("date must be an ISO date") from exc
    basis = spatial_basis(points, params, seed_keys)
    return np.asarray(basis @ _state(offset, params, dict(seed_keys)), dtype=float)


def _softmax(log_values: np.ndarray) -> np.ndarray:
    shifted = np.asarray(log_values, dtype=float) - np.max(log_values)
    result = np.exp(shifted)
    return result / result.sum()


def _draw_fields(xy: np.ndarray, site_ids: np.ndarray, params: dict, seed: int, draws: int, channel: str) -> np.ndarray:
    basis = spatial_basis(xy, params, {"basis_seed": params["seed"], "segment": params["segment"]})
    # Each stationary draw uses an independent coefficient stream.  These are
    # intentionally not adjacent AR days, which would understate holdout error.
    states = np.vstack([named_rng(seed, channel=channel, field_version=_FIELD_VERSION, draw=index).normal(size=params["fourier_features"])
                        for index in range(draws)])
    return states @ basis.T


def spatial_diagnostics(draws: Iterator[np.ndarray], target: np.ndarray, plz: np.ndarray) -> dict:
    """Evaluate independent share draws against site and postal acceptance limits."""
    expected = _finite_vector(target, "target")
    expected = expected / expected.sum()
    postal = np.asarray(plz, dtype=object)
    if postal.shape != expected.shape or any(not str(value).strip() for value in postal):
        raise ValueError("plz must provide one non-blank value per target")
    values = np.asarray(list(draws), dtype=float)
    if values.ndim != 2 or values.shape[1] != len(expected) or len(values) == 0 or not np.isfinite(values).all() or (values < 0).any():
        raise ValueError("draws must be finite nonnegative share vectors")
    if not np.allclose(values.sum(axis=1), 1., atol=1e-10, rtol=1e-10):
        raise ValueError("each spatial draw must sum to one")
    mean = values.mean(axis=0)
    standard_error = values.std(axis=0, ddof=1) / math.sqrt(len(values)) if len(values) > 1 else np.full(len(values[0]), np.inf)
    postal_rows = []
    for code in sorted({str(value) for value in postal}):
        mask = postal.astype(str) == code
        shares = values[:, mask].sum(axis=1)
        target_share = float(expected[mask].sum())
        error = float(shares.mean() - target_share)
        se = float(shares.std(ddof=1) / math.sqrt(len(shares))) if len(shares) > 1 else math.inf
        postal_rows.append({"plz": code, "target": target_share, "mean": float(shares.mean()), "error": error, "mcse": se,
                            "accepted": abs(error) <= .005 + 3 * se})
    tvd = float(.5 * np.abs(mean - expected).sum())
    status = "complete" if len(values) >= 2 and tvd <= .01 and all(row["accepted"] for row in postal_rows) else "mean_preservation_unresolved"
    return {"draws": int(len(values)), "mean": mean.tolist(), "target": expected.tolist(), "site_error": (mean - expected).tolist(),
            "site_mcse": standard_error.tolist(), "tvd": tvd, "postal": postal_rows, "status": status}


def _calibration_fingerprint(weights: np.ndarray, points: np.ndarray, identifiers: np.ndarray, params: dict, plan: dict) -> str:
    return canonical_digest({"target": weights.tolist(), "xy": points.tolist(), "site_ids": identifiers.tolist(),
                             "parameters": params, "design": plan, "rng_version": RNG_VERSION})


def calibrate_spatial(target: np.ndarray, xy: np.ndarray, site_ids: np.ndarray, parameters: dict, design: dict) -> dict:
    """Fit log base weights on calibration fields and verify them on holdout fields."""
    weights = _finite_vector(target, "target")
    weights = weights / weights.sum()
    if (weights <= 0).any():
        raise ValueError("calibrate_spatial requires strictly positive localized target support")
    identifiers = _site_ids(site_ids, len(weights))
    points = _xy(xy, len(weights))
    params, plan = _parameters(parameters), _design(design)
    fingerprint = _calibration_fingerprint(weights, points, identifiers, params, plan)
    log_base = np.log(weights)
    if params["log_sigma"] == 0:
        diagnostics = {"draws": plan["validation_draws"], "mean": weights.tolist(), "target": weights.tolist(),
                       "site_error": np.zeros(len(weights)).tolist(), "site_mcse": np.zeros(len(weights)).tolist(),
                       "tvd": 0., "postal": [], "status": "complete"}
        basis_fingerprint = canonical_digest({"basis": spatial_basis(points, params, {"basis_seed": params["seed"], "segment": params["segment"]}).tolist()})
        return {"log_base": log_base.tolist(), "diagnostics": diagnostics, "fingerprint": fingerprint, "status": "complete",
                "parameters": params, "design": plan, "iterations": 0,
                "holdout_basis_fingerprint": basis_fingerprint}
    fields = _draw_fields(points, identifiers, params, plan["calibration_seed"], plan["calibration_draws"], "spatial-calibration")
    converged = False
    for iteration in range(plan["max_iterations"]):
        shares = np.asarray([_softmax(log_base + params["log_sigma"] * field) for field in fields])
        mean = shares.mean(axis=0)
        log_base += .5 * (np.log(weights) - np.log(mean))
        log_base -= log_base.max()
        if .5 * np.abs(mean - weights).sum() <= 1e-8:
            converged = True
            break
    holdout_fields = _draw_fields(points, identifiers, params, plan["validation_seed"], plan["validation_draws"], "spatial-holdout")
    diagnostics = spatial_diagnostics((_softmax(log_base + params["log_sigma"] * field) for field in holdout_fields),
                                      weights, np.asarray(["all"] * len(weights), dtype=object))
    # Reaching the update budget is not acceptance.  Only independent holdout
    # criteria certify the plan.
    status = "complete" if converged and diagnostics["status"] == "complete" else "mean_preservation_unresolved"
    basis_fingerprint = canonical_digest({"basis": spatial_basis(points, params, {"basis_seed": params["seed"], "segment": params["segment"]}).tolist()})
    return {"log_base": log_base.tolist(), "diagnostics": diagnostics, "fingerprint": fingerprint, "status": status,
            "parameters": params, "design": plan, "iterations": iteration + 1, "calibration_converged": converged,
            "holdout_basis_fingerprint": basis_fingerprint}


def _holdout_diagnostics(result: dict, target: np.ndarray, plz: np.ndarray, xy: np.ndarray, site_ids: np.ndarray) -> dict:
    """Recheck a cached correction on its independent fields at the actual PLZ grain."""
    params, design = result["parameters"], result["design"]
    fields = _draw_fields(xy, site_ids, params, int(design["validation_seed"]), int(design["validation_draws"]), "spatial-holdout")
    base = np.asarray(result["log_base"], dtype=float)
    return spatial_diagnostics((_softmax(base + float(params["log_sigma"]) * field) for field in fields), target, plz)


def _annual_target_fingerprints(annual: pd.DataFrame) -> dict[str, str]:
    values = {}
    for (year, segment), rows in annual.groupby(["year", "segment"], sort=True):
        ordered = rows.loc[:, ["site_id", "plz", "allocation_status", "annual_expected"]].copy()
        ordered["site_id"] = ordered.site_id.astype(str)
        ordered["plz"] = ordered.plz.astype(str)
        ordered = ordered.sort_values(["plz", "site_id"], kind="stable").reset_index(drop=True)
        values[f"{int(year)}:{segment}"] = canonical_digest({"columns": ordered.columns.tolist(), "rows": ordered.to_dict(orient="records")})
    return values


def _geometry(reference: dict) -> pd.DataFrame:
    if not isinstance(reference, dict):
        raise ValueError("reference must be a mapping")
    value = reference.get("geometry", reference.get("sites"))
    if not isinstance(value, pd.DataFrame) or "site_id" not in value.columns:
        raise ValueError("correlated spatial allocation requires reference geometry by site_id")
    result = value.copy()
    if {"x", "y"}.issubset(result.columns):
        result = result.loc[:, ["site_id", "x", "y"]]
    elif "geometry" in result.columns:
        result = pd.DataFrame({"site_id": result.site_id, "x": result.geometry.map(lambda item: getattr(item, "x", np.nan)),
                               "y": result.geometry.map(lambda item: getattr(item, "y", np.nan))})
    else:
        raise ValueError("reference geometry requires x/y or point geometry")
    result["site_id"] = result.site_id.astype(str)
    if result.site_id.duplicated().any():
        raise ValueError("reference geometry requires unique site IDs")
    return result.set_index("site_id")


def resolve_spatial_plan(reference: dict, projection: AnnualProjection, cfg: dict, outer_id: int, cache_dir: Path) -> SpatialPlan:
    """Resolve cached per-year/segment correlated calibrations after holdout checks."""
    if not isinstance(projection, AnnualProjection) or not isinstance(cfg, dict):
        raise ValueError("reference, projection, and cfg are required")
    spatial = cfg.get("spatial", {})
    if not isinstance(spatial, dict):
        raise ValueError("spatial configuration must be a mapping")
    mode = spatial.get("mode", "dirichlet")
    annual = projection.sites.copy()
    required = {"year", "segment", "site_id", "plz", "annual_expected", "allocation_status"}
    if missing := required.difference(annual.columns):
        raise ValueError(f"projection sites missing columns: {sorted(missing)}")
    targets = _annual_target_fingerprints(annual)
    if mode == "dirichlet":
        return SpatialPlan(mode="dirichlet", calibration={}, target_fingerprints=targets,
                           parameter_fingerprint=canonical_digest({"spatial": spatial}), status="complete")
    if mode != "correlated":
        raise ValueError("spatial.mode must be dirichlet or correlated")
    params = _parameters({**spatial, "seed": cfg.get("seed", spatial.get("seed", 0))})
    design = _design(spatial)
    geometry = _geometry(reference)
    results, statuses = {}, []
    root = Path(cache_dir) / "spatial_calibration"
    for (year, segment), rows in annual.groupby(["year", "segment"], sort=True):
        rows = rows.sort_values(["plz", "site_id"], kind="stable").reset_index(drop=True)
        expected = pd.to_numeric(rows.annual_expected, errors="coerce").to_numpy(float)
        if not np.isfinite(expected).all() or (expected < 0).any():
            raise ValueError("annual targets must be finite nonnegative")
        located = rows.allocation_status.astype(str).eq("located").to_numpy() & (expected > 0)
        unlocated = ~rows.allocation_status.astype(str).eq("located").to_numpy()
        total = expected.sum()
        key = f"{int(year)}:{segment}"
        if total == 0 or not located.any():
            results[key] = {"site_ids": [], "xy": [], "log_base": [], "unlocated_share": 1. if total else 0.,
                            "unlocated_site_ids": rows.loc[unlocated, "site_id"].astype(str).tolist(),
                            "status": "skipped_no_localized_support", "fingerprint": canonical_digest({"target": targets[key], "empty": True})}
            statuses.append("complete")
            continue
        local = rows.loc[located, ["site_id", "plz"]].copy()
        missing = [site for site in local.site_id.astype(str) if site not in geometry.index]
        if missing:
            raise ValueError(f"located sites have no coordinates: {missing}")
        xy = geometry.loc[local.site_id.astype(str), ["x", "y"]].to_numpy(float)
        if not np.isfinite(xy).all():
            raise ValueError("positive localized support requires finite point coordinates")
        target = expected[located] / expected[located].sum()
        segment_params = _parameters({**params, "segment": str(segment)})
        payload = {"target": target.tolist(), "xy": xy.tolist(), "site_ids": local.site_id.astype(str).tolist(),
                   "parameters": segment_params, "design": design, "outer_id": int(outer_id), "target_fingerprint": targets[key]}
        path = root / canonical_digest(payload) / "calibration.json"
        if path.is_file():
            result = json.loads(path.read_text(encoding="utf-8"))
            if result.get("fingerprint") != _calibration_fingerprint(target, xy, local.site_id.astype(str).to_numpy(), segment_params, design):
                raise ValueError("spatial calibration cache fingerprint mismatch")
        else:
            result = calibrate_spatial(target, xy, local.site_id.to_numpy(), segment_params, design)
            path.parent.mkdir(parents=True, exist_ok=True)
            temporary = path.with_suffix(".tmp")
            temporary.write_text(json.dumps(result, sort_keys=True, allow_nan=False), encoding="utf-8")
            temporary.replace(path)
        result["diagnostics"] = _holdout_diagnostics(result, target, local.plz.astype(str).to_numpy(), xy, local.site_id.to_numpy())
        result["status"] = ("complete" if result.get("calibration_converged", True)
                            and result["diagnostics"]["status"] == "complete" else "mean_preservation_unresolved")
        temporary = path.with_suffix(".tmp")
        temporary.write_text(json.dumps(result, sort_keys=True, allow_nan=False), encoding="utf-8")
        temporary.replace(path)
        result["runtime_basis_fingerprint"] = canonical_digest({"basis": spatial_basis(
            xy, result["parameters"], {"basis_seed": result["parameters"]["seed"], "segment": str(segment)}).tolist()})
        result = {**result, "site_ids": local.site_id.astype(str).tolist(), "xy": xy.tolist(),
                  "unlocated_share": float(expected[unlocated].sum() / total),
                  "unlocated_site_ids": rows.loc[unlocated, "site_id"].astype(str).tolist(),
                  "localized_share": float(expected[located].sum() / total)}
        results[key] = result
        statuses.append(result["status"])
    status = "complete" if all(value == "complete" for value in statuses) else "mean_preservation_unresolved"
    return SpatialPlan(mode="correlated", calibration=results, target_fingerprints=targets,
                       parameter_fingerprint=canonical_digest({"spatial": spatial}), status=status)
