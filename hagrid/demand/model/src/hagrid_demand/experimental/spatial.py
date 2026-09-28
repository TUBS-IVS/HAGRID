"""Conditional spatial redistribution experiment; not a calibrated demand forecast."""

from datetime import date, timedelta, datetime, timezone
import hashlib
import json
from pathlib import Path
import re

import geopandas as gpd
import numpy as np
import pandas as pd

from ..data import valid_points, write_json


def spatial_basis(xy, length_scale, features, seed, segment):
    """Random Fourier approximation to exp(-distance²/(2*length_scale²))."""
    rng = np.random.default_rng(np.random.SeedSequence([seed, segment, 0]))
    frequencies = rng.normal(size=(2, features)) / length_scale
    phases = rng.uniform(0, 2 * np.pi, features)
    basis = np.empty((len(xy), features), dtype=np.float32)
    for start in range(0, len(xy), 8192):
        basis[start:start + 8192] = np.cos(xy[start:start + 8192] @ frequencies + phases) * np.sqrt(2 / features)
    return basis


def coefficients(seed, segment, features, rho, day_offset):
    # Date-addressable innovations: extending an experiment does not alter earlier days.
    state = np.random.default_rng(np.random.SeedSequence([seed, segment, 1, 0])).normal(size=features)
    for day in range(1, day_offset + 1):
        innovation = np.random.default_rng(np.random.SeedSequence([seed, segment, 1, day])).normal(size=features)
        state = rho * state + np.sqrt(1 - rho * rho) * innovation
    return state


def normalize_weights(weights, field, log_sigma):
    weights = np.asarray(weights, dtype=float)
    if len(weights) == 0 or not np.isfinite(weights).all() or (weights < 0).any() or weights.sum() <= 0:
        raise ValueError("Positive finite segment weight sum required")
    baseline = weights / weights.sum()
    # Subtract a common constant for numerical stability; segment total stays unchanged.
    active = weights > 0
    log_factor = log_sigma * np.asarray(field, dtype=float)
    if not np.isfinite(log_factor).all():
        raise ValueError("Nonfinite spatial field")
    shifted = weights * np.exp(log_factor - log_factor[active].max())
    shifted /= shifted.sum()
    return baseline, shifted


def paired_counts(baseline, shifted, total, seed, segment, day_offset):
    """Same uniform draws for both variants; each marginal is multinomial."""
    rng = np.random.default_rng(np.random.SeedSequence([seed, segment, 2, day_offset]))
    draws = rng.random(total)
    outputs = []
    for probability in (baseline, shifted):
        cdf = probability.cumsum()
        cdf[-1] = 1.0
        bins = np.searchsorted(cdf, draws, side="right")
        outputs.append(np.bincount(bins, minlength=len(probability)))
    return outputs


def run_spatial(config_path, run_id):
    config_path = Path(config_path).resolve()
    cfg = json.loads(config_path.read_text(encoding="utf-8"))
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,100}", run_id):
        raise ValueError("Invalid run_id")
    if not 1 <= cfg["days"] <= 366 or not 8 <= cfg["fourier_features"] <= 512:
        raise ValueError("days must be 1..366; features must be 8..512")
    start, anchor = date.fromisoformat(cfg["start_date"]), date.fromisoformat(cfg["field_anchor_date"])
    offset = (start - anchor).days
    if not 0 <= offset <= 36525 or not isinstance(cfg["seed"], int) or cfg["seed"] < 0:
        raise ValueError("Start must follow anchor within 100 years; seed must be a nonnegative integer")
    if set(cfg["segments"]) != {"private", "business"}:
        raise ValueError("Expected private and business segment definitions")
    for name, spec in cfg["segments"].items():
        if (not isinstance(spec["daily_total"], int) or spec["daily_total"] < 0
                or not 0 <= spec["temporal_rho"] < 1 or spec["length_scale_m"] <= 0
                or not 0 <= spec["log_sigma"] <= 3):
            raise ValueError(f"Invalid segment parameters: {name}")
    source = (config_path.parent / cfg["foundation_run"]).resolve()
    cfg["foundation_run"] = str(source)
    output = (config_path.parent / cfg["output_dir"] / run_id).resolve()
    if output == source or output.is_relative_to(source):
        raise ValueError("Experiment output must be separate from foundation run")
    sites = gpd.read_parquet(source / "sites.parquet").sort_values("site_id").reset_index(drop=True)
    if not sites.site_id.is_unique or not valid_points(sites.geometry).all():
        raise ValueError("Spatial experiment needs unique sites and valid point locations; no silent exclusions")
    if sites.crs.is_geographic or any(a.unit_conversion_factor != 1 for a in sites.crs.axis_info[:2]):
        raise ValueError("Metre-based projected coordinates required")
    membership = pd.read_parquet(source / "site_postal_candidates.parquet")
    unique = membership.loc[membership.postal_candidates.eq(1), ["site_id", "plz"]]
    if not unique.site_id.is_unique:
        raise ValueError("Nonunique postal membership")
    sites["plz"] = sites.site_id.map(unique.set_index("site_id").plz).fillna("unassigned")
    output.mkdir(parents=True, exist_ok=False)
    state = {"run_id": run_id, "status": "running", "kind": "uncalibrated_spatial_experiment"}
    write_json(output / "run.json", state)
    try:
        write_json(output / "config.resolved.json", cfg)
        from ..pipeline import digest
        write_json(output / "provenance.json", {"created_at": datetime.now(timezone.utc).isoformat(),
            "inputs": {name: digest(source / name) for name in ["sites.parquet", "site_postal_candidates.parquet", "summary.json"]},
            "code": {p.relative_to(Path(__file__).parents[1]).as_posix(): digest(p) for p in [*Path(__file__).parent.iterdir(), *Path(__file__).parents[1].iterdir()] if p.suffix in {".py", ".html"}},
            "numpy_version": np.__version__, "config_sha256": digest(config_path)})
        aggregates, diagnostics = [], []
        for segment_id, segment in enumerate(["private", "business"]):
            spec = cfg["segments"][segment]
            subset = sites.loc[sites.recipient_type.eq(segment)].copy()
            weights = pd.to_numeric(subset[spec["weight_column"]], errors="coerce").to_numpy(dtype=float)
            xy = np.column_stack([subset.geometry.x, subset.geometry.y])
            basis = spatial_basis(xy, spec["length_scale_m"], cfg["fourier_features"], cfg["seed"], segment_id)
            state_vector = coefficients(cfg["seed"], segment_id, cfg["fourier_features"], spec["temporal_rho"], offset)
            previous_field = None
            for index in range(cfg["days"]):
                day = offset + index
                if index:
                    innovation = np.random.default_rng(np.random.SeedSequence([cfg["seed"], segment_id, 1, day])).normal(size=cfg["fourier_features"])
                    state_vector = spec["temporal_rho"] * state_vector + np.sqrt(1-spec["temporal_rho"]**2) * innovation
                field = basis @ state_vector
                baseline, shifted = normalize_weights(weights, field, spec["log_sigma"])
                base_counts, shifted_counts = paired_counts(baseline, shifted, spec["daily_total"], cfg["seed"], segment_id, day)
                day_date = str(start + timedelta(days=index))
                result = pd.DataFrame({"site_id": subset.site_id.to_numpy(), "plz": subset.plz.to_numpy(),
                    "baseline_expected": baseline * spec["daily_total"], "spatial_expected": shifted * spec["daily_total"],
                    "baseline_count": base_counts, "spatial_count": shifted_counts})
                if base_counts.sum() != spec["daily_total"] or shifted_counts.sum() != spec["daily_total"]:
                    raise AssertionError("Integer segment total not conserved")
                if not np.isclose(result.spatial_expected.sum(), spec["daily_total"], atol=1e-7, rtol=0):
                    raise AssertionError("Expected segment total not conserved")
                result.to_parquet(output / f"{day_date}_{segment}.parquet", index=False)
                area = result.groupby("plz").sum(numeric_only=True).reset_index()
                area["date"], area["segment"] = day_date, segment
                aggregates.extend(json.loads(area.to_json(orient="records")))
                diagnostics.append({"date": day_date, "segment": segment, "total": spec["daily_total"],
                    "expected_spatial_redistribution_pct": float(.5 * np.abs(shifted-baseline).sum() * 100),
                    "field_correlation_previous_day": float(np.corrcoef(field, previous_field)[0, 1]) if previous_field is not None and np.std(field)>0 and np.std(previous_field)>0 else None,
                    "integer_totals_preserved": True})
                previous_field = field
            print(f"Completed spatial comparison: {segment}", flush=True)
        summary = {"run_id": run_id, "calibrated": False, "interpretation": cfg["interpretation"],
                   "config": cfg, "postal_daily": aggregates, "diagnostics": diagnostics,
                   "notes": ["Daily totals are illustrative, not measured or fitted.",
                     "No carrier allocation, calendar effects or total-volume shocks in this controlled comparison.",
                     "Same segment totals and shared random draws isolate spatial redistribution.",
                     "Length scale describes an approximate covariance kernel, not delivery catchments.",
                     "AR coefficient governs latent waves; finite-location correlation and parcel-count correlation differ."]}
        write_json(output / "comparison.json", summary)
        render_spatial_dashboard(output, summary, source)
        state["status"] = "complete"
        write_json(output / "run.json", state)
        return output
    except Exception as exc:
        state.update(status="failed", error=f"{type(exc).__name__}: {exc}")
        write_json(output / "run.json", state)
        raise


def render_spatial_dashboard(output, summary, source):
    import html
    postal = gpd.read_parquet(source / "postal_support.parquet")
    shapes = []
    for row in postal.itertuples():
        geom = row.geometry.simplify(40, preserve_topology=True)
        parts = [geom] if geom.geom_type == "Polygon" else list(geom.geoms)
        path = " ".join("M" + " L".join(f"{x:.0f},{-y:.0f}" for x,y in ring.coords) + " Z"
                        for part in parts for ring in [part.exterior, *part.interiors])
        shapes.append({"plz": row.plz, "path": path})
    bounds = postal.total_bounds
    summary = {**summary, "shapes": shapes, "box": [bounds[0], -bounds[3], bounds[2]-bounds[0], bounds[3]-bounds[1]]}
    payload = json.dumps(summary, ensure_ascii=False, allow_nan=False).replace("<", "\\u003c").replace("&", "\\u0026")
    template = Path(__file__).with_name("spatial.html").read_text(encoding="utf-8")
    (output / "dashboard.html").write_text(template.replace("__RUN__", html.escape(summary["run_id"])).replace("__DATA__", payload), encoding="utf-8")
