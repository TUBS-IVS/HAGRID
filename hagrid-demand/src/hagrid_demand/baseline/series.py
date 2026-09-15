"""Pure, reproducible reconstructions of the 00--02 national input series."""

from __future__ import annotations

from typing import Any
import warnings

import numpy as np
import pandas as pd
from scipy.optimize import curve_fit
from scipy.optimize import OptimizeWarning

from .sources import validate_series_inputs


def _sigmoid(x, maximum, midpoint, steepness):
    return maximum / (1 + np.exp(-steepness * (np.asarray(x) - midpoint)))


def _bounded_sigmoid(x, maximum, midpoint, steepness, lower=0.20):
    return lower + _sigmoid(x, maximum, midpoint, steepness)


def _exp(x, scale, rate):
    return scale * np.exp(rate * (np.asarray(x) - 2028))


def _volume_logistic(x, maximum, growth_rate, midpoint):
    """Notebook 02 logistic curve: (maximum, growth rate, midpoint)."""
    return maximum / (1 + np.exp(-growth_rate * (np.asarray(x) - midpoint)))


def _curve_fit(function, x, y, **kwargs):
    """Fit when support is sufficient; covariance is intentionally not consumed."""
    with warnings.catch_warnings():
        warnings.simplefilter("ignore", OptimizeWarning)
        return curve_fit(function, x, y, **kwargs)[0]


def _market(inputs: dict[str, Any], years: list[int]) -> pd.DataFrame:
    spec = inputs["market_inputs"]
    anchor = spec["carrier_anchor"]
    categories = list(anchor["shares"])
    start, end = anchor["year_start"], anchor["year_end"]
    share_2016 = {carrier: anchor["shares"][carrier]["value"] - anchor["change_2016_2022"][carrier]["value"]
                  for carrier in categories}
    annual = {carrier: anchor["change_2016_2022"][carrier]["value"] / (end - start) for carrier in categories}
    rows = []
    amazon = spec["amazon"]
    fit = _curve_fit(_sigmoid, np.array([point["year"] for point in amazon["points"]], dtype=float),
                     np.array([point["value"] for point in amazon["points"]], dtype=float),
                     p0=amazon["initial_guess"]["values"], maxfev=20_000)
    for year in years:
        values = {}
        for carrier in categories:
            if year < start:
                value = share_2016[carrier] - annual[carrier] * (start - year)
            elif year <= end:
                value = share_2016[carrier] + (anchor["shares"][carrier]["value"] - share_2016[carrier]) * (year - start) / (end - start)
            else:
                value = anchor["shares"][carrier]["value"]
                for delta in range(1, year - end + 1):
                    value += annual[carrier] * (1 / np.sqrt(delta + 1)) * anchor["post_2022_multiplier"]["value"]
            values[carrier] = max(0.0, value)
        values["Amazon"] = 0.0 if year == 2014 else max(0.0, float(_sigmoid(year, *fit)))
        total = sum(values.values())
        for provider, value in values.items():
            provenance = amazon["points"][-1] if provider == "Amazon" else anchor["shares"][provider]
            rows.append({"year": year, "carrier": provider, "market_share": value / total,
                         "status": "derived_projection", "unit": "share",
                         "source_status": provenance["status"], "source_unit": provenance["unit"],
                         "source_reference": provenance["source"],
                         "source_notebook_cell": provenance["notebook_cell"]})
    return pd.DataFrame(rows)


def _b2b(inputs: dict[str, Any], years: list[int]) -> pd.DataFrame:
    spec = inputs["b2b_inputs"]
    anchors = pd.DataFrame(spec["anchors"])
    observed = anchors.set_index("year")
    # Notebook 01 fits years relative to 2009; retaining that coordinate system
    # preserves both its bounded-sigmoid parameters and its forecast values.
    reference_year = 2009
    x = anchors.year.to_numpy(dtype=float) - reference_year
    y = anchors.share.to_numpy(dtype=float)
    rows = []
    try:
        params = _curve_fit(lambda values, maximum, midpoint, steepness: _bounded_sigmoid(
            values, maximum, midpoint, steepness, spec["lower_bound"]), x, y,
            p0=spec["initial_guess"], maxfev=20_000) if len(x) >= 3 else None
        failure = None if params is not None else "fit_failed:insufficient_support"
    except (RuntimeError, TypeError, ValueError, FloatingPointError) as exc:
        params, failure = None, f"fit_failed:{type(exc).__name__}"
    for year in years:
        if year in observed.index:
            row = observed.loc[year]
            share, status, provenance = float(row.share), row.status, row
        elif year < 2024:
            share, status, provenance = float(np.interp(year - reference_year, x, y)), "interpolated", spec
        elif failure is None:
            share, status, provenance = float(_bounded_sigmoid(year - reference_year, *params, spec["lower_bound"])), "forecast", spec
        else:
            share, status, provenance = np.nan, failure, spec
        rows.append({"year": year, "share": share, "status": status, "unit": provenance["unit"],
                     "source": provenance["source"], "notebook_cell": provenance["notebook_cell"]})
    return pd.DataFrame(rows)


def _volume(inputs: dict[str, Any], years: list[int], policy: str) -> pd.DataFrame:
    spec = inputs["volume_inputs"]
    anchors = pd.DataFrame(spec["anchors"])
    if policy not in {"observed_only", "legacy_assumptions"}:
        raise ValueError("volume_fit_policy must be observed_only or legacy_assumptions")
    fit_data = anchors if policy == "legacy_assumptions" else anchors.loc[anchors.status.eq("observed")]
    x, y = fit_data.year.to_numpy(dtype=float), fit_data.value.to_numpy(dtype=float)
    fits: dict[str, np.ndarray] = {}
    errors: dict[str, str] = {}
    if len(x) < 2:
        errors["linear"] = "insufficient_support"
    else:
        try:
            fits["linear"] = np.polyfit(x, y, 1)
        except (ValueError, np.linalg.LinAlgError) as exc:
            errors["linear"] = type(exc).__name__
    logistic_x, logistic_y = x[x > 2005], y[x > 2005]
    if len(logistic_x) < 3:
        errors["logistic"] = "insufficient_support"
    else:
        try:
            fits["logistic"] = _curve_fit(_volume_logistic, logistic_x, logistic_y,
                                            p0=[10e9, .1, 2015], maxfev=20_000)
        except (RuntimeError, TypeError, ValueError, FloatingPointError) as exc:
            errors["logistic"] = type(exc).__name__
    if len(x) < 2:
        errors["exponential"] = "insufficient_support"
    else:
        try:
            fits["exponential"] = _curve_fit(_exp, x, y, p0=[1e9, .05], maxfev=20_000)
        except (RuntimeError, TypeError, ValueError, FloatingPointError) as exc:
            errors["exponential"] = type(exc).__name__
    indexed = anchors.set_index("year")
    rows = []
    for year in years:
        candidate = {
            "linear": float(np.polyval(fits["linear"], year)) if "linear" in fits else np.nan,
            "logistic": float(_volume_logistic(year, *fits["logistic"])) if "logistic" in fits else np.nan,
            "exponential": float(_exp(year, *fits["exponential"])) if "exponential" in fits else np.nan,
        }
        legacy = indexed.loc[year] if year in indexed.index and indexed.loc[year].status == "legacy_estimate" else None
        if year in indexed.index and indexed.loc[year].status == "observed":
            anchor = indexed.loc[year]
            value, status, provenance, curve = float(anchor.value), anchor.status, anchor, "observed_anchor"
        elif policy == "legacy_assumptions" and legacy is not None:
            value, status, provenance, curve = float(legacy.value), legacy.status, legacy, "legacy_assumption"
        elif "linear" in fits:
            value, status, provenance, curve = candidate["linear"], "forecast", spec, "linear"
        else:
            value, status, provenance, curve = np.nan, "fit_failed", spec, None
        candidate_metadata = {}
        for model in ("linear", "logistic", "exponential"):
            candidate_metadata[f"{model}_fit_status"] = "ok" if model in fits else "failed"
            candidate_metadata[f"{model}_fit_error"] = errors.get(model)
        rows.append({"year": year, "value": value, "status": status, "unit": provenance["unit"], "curve": curve,
                     "provenance": provenance["source"],
                     "fit_policy": policy, "fit_status": "ok" if not errors else "partial_failure",
                     "source": provenance["source"], "notebook_cell": provenance["notebook_cell"],
                     "legacy_value": float(legacy.value) if legacy is not None else np.nan,
                     "legacy_status": legacy.status if legacy is not None else None,
                     "legacy_source": legacy.source if legacy is not None else None,
                     "legacy_notebook_cell": legacy.notebook_cell if legacy is not None else None,
                     **candidate, **candidate_metadata})
    return pd.DataFrame(rows)


def _priors(inputs: dict[str, Any], years: list[int]) -> pd.DataFrame:
    """Materialize the public, year-keyed provider-prior contract.

    The packaged priors are currently constant assumptions, but publishing a row
    per requested year prevents consumers from accidentally applying an
    undated value to a different market series year.
    """
    rows = []
    for year in years:
        for carrier, value in inputs["provider_priors"]["providers"].items():
            rows.append({"year": year, "carrier": carrier, "lower": 0.0, "upper": 1.0,
                         "q_prior": value["initial"], "q_scale": value["scale"],
                         "b2b_preference": value["b2b_preference"], "status": "assumption", "unit": "share",
                         "source": value["source"], "notebook_cell": value["notebook_cell"],
                         "legacy_bounds": value["legacy_bounds"]})
    return pd.DataFrame(rows)


def _weekly(weekly_profile: pd.DataFrame | None) -> pd.DataFrame:
    """Return a normalised 52-week consumer table.

    A standalone series build has no raw workbook dependency.  It deliberately
    exposes a neutral, clearly labelled profile in that case; the workflow
    always supplies the source-derived profile from the sources stage.
    """
    if weekly_profile is None:
        return pd.DataFrame({"week": list(range(1, 53)), "weight": np.ones(52),
                             "status": "neutral_unsupplied", "unit": "relative_weekly_factor"})
    if not isinstance(weekly_profile, pd.DataFrame) or "week" not in weekly_profile:
        raise ValueError("weekly_profile requires week and weight or relative_volume")
    column = "weight" if "weight" in weekly_profile else "relative_volume" if "relative_volume" in weekly_profile else None
    if column is None:
        raise ValueError("weekly_profile requires week and weight or relative_volume")
    result = weekly_profile[["week", column]].copy().rename(columns={column: "weight"})
    result["week"] = pd.to_numeric(result.week, errors="coerce")
    result["weight"] = pd.to_numeric(result.weight, errors="coerce")
    if (result.week.isna().any() or result.weight.isna().any() or not np.isfinite(result.weight).all()
            or (result.weight <= 0).any()):
        raise ValueError("weekly_profile must contain finite positive weights")
    result["week"] = result.week.astype(int)
    if result.week.duplicated().any() or result.week.tolist() != list(range(1, 53)):
        raise ValueError("weekly_profile must contain weeks 1 through 52 once")
    result["weight"] = result.weight / result.weight.mean()
    result["status"] = "derived_from_raw_source"
    result["unit"] = "relative_weekly_factor"
    return result


def build_series(inputs: dict, years: list[int], *, volume_fit_policy: str,
                 weekly_profile: pd.DataFrame | None = None) -> dict:
    """Build national baseline inputs without consuming historical notebook exports."""
    inputs = validate_series_inputs(inputs)
    years = sorted({int(year) for year in years})
    if not years:
        raise ValueError("years must contain at least one year")
    return {"market": _market(inputs, years), "b2b": _b2b(inputs, years),
            "volume": _volume(inputs, years, volume_fit_policy), "providers": _priors(inputs, years),
            "weekly": _weekly(weekly_profile)}
