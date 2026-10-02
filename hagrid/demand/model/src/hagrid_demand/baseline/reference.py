"""Balanced LSP-anchor reference calculation with no grid allocation."""
from __future__ import annotations

import numpy as np
import pandas as pd
from scipy.optimize import LinearConstraint, brentq, minimize
from scipy.special import expit


_ATOL, _RTOL = 1e-8, 1e-10


def _array(value, name):
    result = np.asarray(value, dtype=float)
    if result.ndim != 1 or not np.isfinite(result).all():
        raise ValueError(f"{name} must be a finite one-dimensional array")
    return result


def _bounded_quadratic_projection(m, prior, b, lower, upper, scale):
    """Solve the clipped monotone Lagrange equation for the exact bounded optimum."""
    fixed = np.clip(prior, lower, upper)
    fixed_balance = float(m @ fixed)
    if fixed_balance == b:
        return fixed

    positive = m > 0
    feasible_low, feasible_high = float(m @ lower), float(m @ upper)
    if b == feasible_low:
        result = fixed.copy(); result[positive] = lower[positive]
        return result
    if b == feasible_high:
        result = fixed.copy(); result[positive] = upper[positive]
        return result

    # The KKT multiplier can itself be outside float range when scale**2
    # underflows.  Store log(m * scale**2) and evaluate lambda*coefficient
    # from its signed log magnitude instead of materialising either factor.
    log_coefficient = np.full(len(m), -np.inf)
    log_coefficient[positive] = np.log(m[positive]) + 2. * np.log(scale[positive])
    direction = 1. if fixed_balance > b else -1.
    distance_to_bound = prior - lower if direction > 0 else upper - prior
    movable = positive & (distance_to_bound > 0)
    if not movable.any():
        raise ValueError("carrier scales leave the requested balance on an unreachable plateau")
    breakpoints = np.log(distance_to_bound[movable]) - log_coefficient[movable]

    log_max = np.log(np.finfo(float).max)
    log_min = np.log(np.nextafter(0., 1.))

    def candidate(log_magnitude):
        log_product = log_magnitude + log_coefficient
        magnitude = np.zeros(len(m), dtype=float)
        overflowing = log_product >= log_max
        representable = (log_product > log_min) & ~overflowing
        magnitude[overflowing] = np.inf
        magnitude[representable] = np.exp(log_product[representable])
        with np.errstate(over="ignore", invalid="ignore"):
            return np.clip(prior - direction * magnitude, lower, upper)

    def residual(log_magnitude):
        return float(m @ candidate(log_magnitude) - b)

    def enforce_balance(result):
        """Remove log-space rounding from one interior coordinate exactly."""
        interior = positive & (result > lower) & (result < upper)
        for position in sorted(np.flatnonzero(interior), key=lambda index: (-m[index], index)):
            adjusted = result.copy()
            adjusted[position] = (b - float(m @ adjusted) + m[position] * adjusted[position]) / m[position]
            if lower[position] <= adjusted[position] <= upper[position]:
                return adjusted
        return result

    # Keep exact signs as the root criterion.  In particular, an endpoint
    # residual of 1e-9 must not be treated as an exact zero.
    def crossed(value):
        return value <= 0. if direction > 0 else value >= 0.

    low = float(breakpoints.min() - 2.)
    low_residual = residual(low)
    for _ in range(64):
        if not crossed(low_residual):
            break
        low -= 8.
        low_residual = residual(low)
    else:
        raise ValueError("carrier scales leave the requested balance on an unreachable plateau")

    high = float(breakpoints.max() + 2.)
    high_residual = residual(high)
    for _ in range(64):
        if crossed(high_residual):
            break
        high += 8.
        high_residual = residual(high)
    else:
        raise ValueError("carrier scales leave the requested balance on an unreachable plateau")

    for _ in range(160):
        middle = (low + high) / 2.
        middle_residual = residual(middle)
        if middle_residual == 0.:
            return enforce_balance(candidate(middle))
        if crossed(middle_residual):
            high = middle
        else:
            low = middle
    return enforce_balance(candidate((low + high) / 2.))


def reconcile_carriers(m: np.ndarray, q: np.ndarray, b: float, lower: np.ndarray,
                       upper: np.ndarray, scale: np.ndarray) -> dict:
    """Minimize scaled movement from carrier B2B priors under exact market balance."""
    m, prior, lower, upper, scale = (_array(x, name) for x, name in
                                    ((m, "m"), (q, "q"), (lower, "lower"), (upper, "upper"), (scale, "scale")))
    if not (len(m) == len(prior) == len(lower) == len(upper) == len(scale)):
        raise ValueError("carrier arrays must have the same length")
    market_weight_input_sum = float(m.sum())
    if (m < 0).any() or not np.isclose(market_weight_input_sum, 1., atol=_ATOL, rtol=_RTOL):
        raise ValueError("m must be a nonnegative simplex")
    market_weight_normalization_factor = 1. / market_weight_input_sum
    m = m * market_weight_normalization_factor
    normalization_anchor = int(np.argmax(m))
    for _ in range(2):
        correction = 1. - float(m.sum())
        if correction == 0.:
            break
        m[normalization_anchor] += correction
    market_weight_normalized_sum = float(m.sum())
    if not np.isfinite(b) or not 0 <= b <= 1 or (lower > upper).any() or (lower < 0).any() or (upper > 1).any() or (scale <= 0).any():
        raise ValueError("invalid carrier bounds, scales, or B2B target")
    feasible_low, feasible_high = float(m @ lower), float(m @ upper)
    if b < feasible_low or b > feasible_high:
        raise ValueError(f"infeasible carrier bounds: reachable [{feasible_low}, {feasible_high}], target {b}")
    requested_b = float(b)
    target_adjustment = 0.
    positive = m > 0
    log_scale = np.log(scale)
    conditioning_log_scale = log_scale - float(log_scale.max())

    def objective_value(candidate, log_divisor):
        distance = np.abs(candidate - prior)
        nonzero = distance > 0
        if not nonzero.any():
            return 0., "finite", None
        logs = 2. * (np.log(distance[nonzero]) - log_divisor[nonzero])
        maximum = float(logs.max())
        log_total = maximum + float(np.log(np.exp(logs - maximum).sum()))
        if log_total > np.log(np.finfo(float).max):
            return None, "overflow_log_retained", log_total
        return float(np.exp(log_total)), "finite", log_total

    def conditioning_objective(candidate):
        distance = np.abs(candidate - prior)
        nonzero = distance > 0
        if not nonzero.any():
            return 0.
        with np.errstate(over="ignore", divide="ignore", invalid="ignore"):
            log_ratios = np.log(distance[nonzero]) - conditioning_log_scale[nonzero]
            capped_ratios = np.exp(np.minimum(log_ratios, np.log(1e140)))
            return float(np.square(capped_ratios).sum())

    solver = {"success": True, "message": "closed-form boundary case"}
    if b == 0:
        if np.any(lower[positive] > _ATOL):
            raise ValueError("infeasible carrier bounds for b=0")
        fitted = np.clip(prior, lower, upper); fitted[positive] = 0.
    elif b == 1:
        if np.any(upper[positive] < 1 - _ATOL):
            raise ValueError("infeasible carrier bounds for b=1")
        fitted = np.clip(prior, lower, upper); fitted[positive] = 1.
    else:
        objective = conditioning_objective
        result = minimize(objective, np.clip(prior, lower, upper), method="SLSQP", bounds=list(zip(lower, upper)),
                          constraints=LinearConstraint(m.reshape(1, -1), b, b),
                          options={"ftol": 1e-13, "maxiter": 1000})
        fitted = np.asarray(result.x, dtype=float)
        solver = {"success": bool(result.success), "message": str(result.message), "iterations": int(getattr(result, "nit", 0))}
        if not np.isfinite(fitted).all():
            raise ValueError("carrier reconciliation produced no finite candidate")
        fitted = _bounded_quadratic_projection(m, prior, b, lower, upper, scale)
    balance = float(m @ fitted)
    if (fitted < lower - _ATOL).any() or (fitted > upper + _ATOL).any() or not np.isclose(balance, b, atol=_ATOL, rtol=_RTOL):
        raise ValueError(f"carrier reconciliation failed validation: target={b}, actual={balance}")
    conditional = np.zeros((2, len(m)), dtype=float)
    if b < 1:
        conditional[0] = m * (1 - fitted) / (1 - b)
    if b > 0:
        conditional[1] = m * fitted / b
    active = [b < 1, b > 0]
    for row, is_active in enumerate(active):
        if is_active and not np.isclose(conditional[row].sum(), 1., atol=_ATOL, rtol=_RTOL):
            raise ValueError("conditional carrier profile is not balanced")
    objective, objective_status, log_objective = objective_value(fitted, log_scale)
    conditioning, conditioning_status, log_conditioning = objective_value(fitted, conditioning_log_scale)
    return {"q": fitted, "conditional": conditional,
            "diagnostics": {"objective": objective, "objective_status": objective_status,
                            "log_objective": log_objective,
                            "conditioning_objective": conditioning,
                            "conditioning_objective_status": conditioning_status,
                            "log_conditioning_objective": log_conditioning,
                            "market_b2b": balance, "feasible_range": [feasible_low, feasible_high],
                            "market_weight_input_sum": market_weight_input_sum,
                            "market_weight_normalization_factor": market_weight_normalization_factor,
                            "market_weight_normalized_sum": market_weight_normalized_sum,
                            "target_requested": requested_b, "target_adjustment": target_adjustment,
                            "solver": solver}}


def _profiles(profiles: dict, b: float) -> tuple[np.ndarray, list[str], int]:
    conditional = np.asarray(profiles.get("conditional"), dtype=float)
    if conditional.ndim != 2 or conditional.shape[0] != 2 or conditional.shape[1] < 1:
        raise ValueError("profiles requires a 2 by C conditional array")
    if not np.isfinite(conditional).all() or (conditional < 0).any():
        raise ValueError("conditional carrier profiles must be finite and nonnegative")
    for row, active in enumerate((b < 1, b > 0)):
        if active and not np.isclose(conditional[row].sum(), 1., atol=_ATOL, rtol=_RTOL):
            raise ValueError("active conditional profile must sum to one")
    carriers = profiles.get("carriers")
    explicit_dhl_index = profiles.get("dhl_index")
    if carriers is None:
        # A one-carrier array is inherently unambiguous.  With multiple
        # columns, inventing ``DHL`` at position zero would silently make a
        # data-layout detail a business identity; require a declared index.
        if conditional.shape[1] == 1 and explicit_dhl_index is None:
            carriers = ["DHL"]
        else:
            if (isinstance(explicit_dhl_index, bool) or not isinstance(explicit_dhl_index, int)
                    or not 0 <= explicit_dhl_index < conditional.shape[1]):
                raise ValueError("multi-carrier profiles require carrier labels or dhl_index")
            carriers = [f"carrier_{index}" for index in range(conditional.shape[1])]
            carriers[explicit_dhl_index] = "DHL"
    if not isinstance(carriers, (list, tuple)) or len(carriers) != conditional.shape[1]:
        raise ValueError("carrier labels do not match conditional profiles")
    labels = []
    normalized = []
    for label in carriers:
        if not isinstance(label, str) or not label.strip():
            raise ValueError("carrier labels must be non-empty strings")
        labels.append(label.strip())
        normalized.append(label.strip().casefold())
    if len(set(normalized)) != len(normalized):
        if normalized.count("dhl") > 1:
            raise ValueError("DHL carrier label is ambiguous after normalization")
        raise ValueError("carrier labels must be unique after normalization")
    dhl_indices = [index for index, label in enumerate(normalized) if label == "dhl"]
    if len(dhl_indices) != 1:
        raise ValueError("profiles must contain exactly one DHL carrier label")
    if explicit_dhl_index is not None:
        if (isinstance(explicit_dhl_index, bool) or not isinstance(explicit_dhl_index, int)
                or explicit_dhl_index != dhl_indices[0]):
            raise ValueError("dhl_index must identify the validated DHL carrier label")
    return conditional, labels, dhl_indices[0]


def _scope(dhl: pd.DataFrame, scope_plz: object = None) -> tuple[pd.DataFrame, dict]:
    required = {"observation_id", "plz", "value", "value_status"}
    if missing := required.difference(dhl.columns):
        raise ValueError(f"dhl missing required columns: {sorted(missing)}")
    if dhl.observation_id.isna().any() or dhl.observation_id.duplicated().any():
        raise ValueError("LSP observation_id must be present and unique")
    values = pd.to_numeric(dhl.value, errors="coerce")
    if values.isna().any():
        raise ValueError("LSP has missing values")
    if not np.isfinite(values).all():
        raise ValueError("LSP values must be finite")
    if (values < 0).any():
        raise ValueError("LSP has negative values")
    if dhl.value_status.isna().any():
        raise ValueError("LSP value_status is required")
    if dhl.plz.isna().any() or dhl.plz.astype(str).str.strip().eq("").any():
        raise ValueError("LSP observations require a known PLZ")
    source = dhl.copy(); source["value"] = values.astype(float); source["plz"] = source.plz.astype(str).str.strip()
    if scope_plz is None:
        in_scope = pd.Series(True, index=source.index)
        scope_labels = None
    else:
        if isinstance(scope_plz, (str, bytes)):
            raise ValueError("scope_plz must be an iterable of verified postal codes")
        try:
            scope_labels = [str(value).strip() for value in scope_plz]
        except TypeError as exc:
            raise ValueError("scope_plz must be an iterable of verified postal codes") from exc
        if not scope_labels or any(not value for value in scope_labels) or len(scope_labels) != len(set(scope_labels)):
            raise ValueError("scope_plz must contain unique non-empty verified postal codes")
        in_scope = source.plz.isin(set(scope_labels))
    outside = source.loc[~in_scope]
    scoped = source.loc[in_scope].copy()
    excluded = scoped.value.gt(1000.)
    retained = scoped.loc[~excluded].copy()
    return retained, {
        "rule": "exclude complete in-scope observation when value > 1000",
        "threshold": 1000.,
        "scope_mode": "verified_postal_support" if scope_labels is not None else "standalone_all_observations",
        "scope_plz_count": None if scope_labels is None else len(scope_labels),
        "out_of_scope_rows": int(len(outside)),
        "out_of_scope_volume": float(outside.value.sum()),
        "out_of_scope_positive_rows": int(outside.value.gt(0).sum()),
        "out_of_scope_positive_volume": float(outside.loc[outside.value.gt(0), "value"].sum()),
        "out_of_scope_zero_rows": int(outside.value.eq(0).sum()),
        "out_of_scope_above_threshold_rows": int(outside.value.gt(1000.).sum()),
        "out_of_scope_above_threshold_volume": float(outside.loc[outside.value.gt(1000.), "value"].sum()),
        "excluded_rows": int(excluded.sum()),
        "excluded_volume": float(scoped.loc[excluded, "value"].sum()),
        "retained_rows": int((~excluded).sum()),
        "retained_volume": float(retained.value.sum()),
    }


def solve_reference(potentials: pd.DataFrame, dhl: pd.DataFrame, profiles: dict, b: float,
                    operating_days: int, *, scope_plz: object = None) -> dict:
    """Reconcile providers then derive a single balanced, grid-free 2021 reference."""
    if not np.isfinite(b) or not 0 <= b <= 1 or isinstance(operating_days, bool) or int(operating_days) != operating_days or operating_days <= 0:
        raise ValueError("B2B target and operating_days must be valid")
    reconciliation = None
    reconciliation_inputs = None
    if "conditional" not in profiles:
        try:
            reconciliation_inputs = {
                "m": _array(profiles.get("m", profiles.get("market")), "m"),
                "q_prior": _array(profiles.get("q", profiles.get("q_prior")), "q_prior"),
                "lower": _array(profiles["lower"], "lower"),
                "upper": _array(profiles["upper"], "upper"),
                "scale": _array(profiles["scale"], "scale"),
            }
            reconciliation = reconcile_carriers(reconciliation_inputs["m"], reconciliation_inputs["q_prior"], b,
                                                reconciliation_inputs["lower"], reconciliation_inputs["upper"],
                                                reconciliation_inputs["scale"])
        except KeyError as exc:
            raise ValueError("profiles requires conditional profiles or carrier reconciliation inputs") from exc
        profiles = {**profiles, "conditional": reconciliation["conditional"]}
    conditional, carriers, dhl_index = _profiles(profiles, b)
    retained, scope_ledger = _scope(dhl, scope_plz)
    if retained.empty or retained.value.sum() <= 0:
        raise ValueError("empty LSP reference region has no positive retained observation")
    required = {"site_id", "plz", "segment", "weight", "allocation_status"}
    if missing := required.difference(potentials.columns):
        raise ValueError(f"potentials missing required columns: {sorted(missing)}")
    sites = potentials.copy()
    if sites.duplicated(["site_id", "segment"]).any():
        raise ValueError("potential site/segment keys must be unique")
    sites["weight"] = pd.to_numeric(sites.weight, errors="coerce")
    if sites.weight.isna().any() or not np.isfinite(sites.weight).all() or (sites.weight < 0).any():
        raise ValueError("potential weights must be finite and nonnegative")
    if not (sites.weight > 0).any():
        raise ValueError("potential support is all-zero")
    sites["segment"] = sites.segment.astype(str).str.lower()
    if not sites.segment.isin(["private", "business"]).all():
        raise ValueError("potential segments must be private or business")
    known = sites.plz.notna() & sites.plz.astype(str).str.strip().ne("")
    sites.loc[known, "plz"] = sites.loc[known, "plz"].astype(str)
    unknown = sites.loc[~known].copy()
    anchor = retained.groupby("plz", as_index=False)["value"].sum().rename(columns={"value": "dhl_retained_mean"})
    modeled = sites.loc[known & sites.plz.isin(anchor.plz)].copy()
    known_outside_anchor = sites.loc[known & ~sites.plz.isin(anchor.plz)].copy()
    if modeled.empty:
        raise ValueError("positive LSP has no potential support")
    support = modeled.pivot_table(index="plz", columns="segment", values="weight", aggfunc="sum", fill_value=0.)
    support = anchor.set_index("plz").join(support, how="left").fillna(0.)
    for segment in ("private", "business"):
        if segment not in support:
            support[segment] = 0.
    support = support[["dhl_retained_mean", "private", "business"]]
    if not np.isfinite(support.to_numpy(float)).all() or (support.to_numpy(float) < 0).any():
        raise ValueError("aggregated postal support must be finite and nonnegative")
    positive = support.dhl_retained_mean.to_numpy(float) > 0
    private, business, dhl_values = (support[name].to_numpy(float) for name in ("private", "business", "dhl_retained_mean"))
    if np.any(positive & ((private + business) <= 0)):
        raise ValueError("positive LSP has no potential support in at least one PLZ")
    private_dhl, business_dhl = conditional[0, dhl_index], conditional[1, dhl_index]
    eta_diagnostics = {
        "initial_endpoints": [-30.0, 30.0],
        "initial_residuals": None,
        "expanded_endpoints": None,
        "expanded_residuals": None,
        "reachable_range": None,
        "status": None,
    }
    if b == 0:
        if np.any(positive & (private <= 0)):
            raise ValueError("positive LSP has no private support for b=0")
        local_b = np.zeros(len(support)); k, k_status, log_k, eta, iterations = 1., "finite", 0., 0., 0
        eta_diagnostics.update(expanded_endpoints=[0.0, 0.0], expanded_residuals=[0.0, 0.0],
                               reachable_range=[0.0, 0.0], status="boundary_b2b_zero")
    elif b == 1:
        if np.any(positive & (business <= 0)):
            raise ValueError("positive LSP has no business support for b=1")
        local_b = np.ones(len(support)); k, k_status, log_k, eta, iterations = 1., "finite", 0., 0., 0
        eta_diagnostics.update(expanded_endpoints=[0.0, 0.0], expanded_residuals=[0.0, 0.0],
                               reachable_range=[1.0, 1.0], status="boundary_b2b_one")
    else:
        def totals(candidate_eta):
            local = np.zeros_like(private)
            both = (private > 0) & (business > 0)
            local[both] = expit(candidate_eta + np.log(business[both]) - np.log(private[both]))
            local[(private <= 0) & (business > 0)] = 1.
            share = (1 - local) * private_dhl + local * business_dhl
            if np.any(positive & (share <= 0)):
                raise ValueError("positive LSP requires a positive local LSP share")
            total = np.divide(dhl_values, share, out=np.zeros_like(dhl_values), where=share > 0)
            return local, share, total
        def residual(candidate_eta):
            local, _, total = totals(candidate_eta)
            return float(total @ local / total.sum() - b)
        mixed_support = positive & (private > 0) & (business > 0)
        if not mixed_support.any():
            constant_residual = residual(0.)
            eta_diagnostics.update(initial_residuals=[constant_residual, constant_residual],
                                   expanded_endpoints=[0.0, 0.0],
                                   expanded_residuals=[constant_residual, constant_residual],
                                   reachable_range=[constant_residual + b, constant_residual + b],
                                   status="constant_support")
            if abs(constant_residual) > 1e-10:
                raise ValueError(f"constant reachable B2B share does not match target; reachable={constant_residual + b}")
            eta, iterations = 0., 0
        else:
            eta_low, eta_high = -30., 30.
            low, high = residual(eta_low), residual(eta_high)
            eta_diagnostics["initial_residuals"] = [low, high]
            expansion = 60.
            finite_limit = np.finfo(float).max / 4.
            while low * high > 0 and max(abs(eta_low), abs(eta_high)) < finite_limit:
                if low < 0 and high < 0:
                    eta_high = min(finite_limit, eta_high + expansion)
                    high = residual(eta_high)
                elif low > 0 and high > 0:
                    eta_low = max(-finite_limit, eta_low - expansion)
                    low = residual(eta_low)
                else:
                    break
                expansion *= 2.
            eta_diagnostics.update(expanded_endpoints=[float(eta_low), float(eta_high)],
                                   expanded_residuals=[float(low), float(high)],
                                   reachable_range=[float(min(b + low, b + high)), float(max(b + low, b + high))])
            if low == 0.:
                eta, iterations = eta_low, 0
                eta_diagnostics["status"] = "endpoint_root"
            elif high == 0.:
                eta, iterations = eta_high, 0
                eta_diagnostics["status"] = "endpoint_root"
            elif low * high > 0:
                eta_diagnostics["status"] = "no_sign_change"
                raise ValueError(f"B2B target has no sign change; reachable residuals [{low}, {high}]")
            else:
                root, details = brentq(residual, eta_low, eta_high, xtol=1e-12, full_output=True)
                eta, iterations = float(root), int(details.iterations)
                eta_diagnostics["status"] = "root_found"
        log_k = float(eta)
        if eta > np.log(np.finfo(float).max):
            k, k_status = None, "overflow_log_k_retained"
        elif eta < np.log(np.nextafter(0., 1.)):
            k, k_status = None, "underflow_log_k_retained"
        else:
            k, k_status = float(np.exp(eta)), "finite"
        local_b, _, _ = totals(eta)
    dhl_share = (1 - local_b) * private_dhl + local_b * business_dhl
    if np.any(positive & (dhl_share <= 0)):
        raise ValueError("positive LSP requires a positive local LSP share")
    postal_total_mean = np.divide(dhl_values, dhl_share, out=np.zeros_like(dhl_values), where=dhl_share > 0)
    if not np.isfinite(postal_total_mean).all() or (postal_total_mean < 0).any():
        raise ValueError("inferred postal totals must be finite and nonnegative")
    achieved_b = float(postal_total_mean @ local_b / postal_total_mean.sum())
    if not np.isclose(achieved_b, b, atol=1e-10, rtol=0):
        raise ValueError(f"B2B reference balance failed: target={b}, actual={achieved_b}")
    annual = postal_total_mean * float(operating_days)
    if not np.isfinite(annual).all() or (annual < 0).any():
        raise ValueError("annual postal totals must be finite and nonnegative")
    postal = support.reset_index().rename(columns={"private": "private_potential", "business": "business_potential"})
    postal["b2b_share"] = local_b; postal["dhl_share"] = dhl_share; postal["reference_annual"] = annual
    postal["private_annual"] = annual * (1 - local_b); postal["business_annual"] = annual * local_b
    postal["private_support"] = np.where(postal.private_potential > 0, "supported", "empty")
    postal["business_support"] = np.where(postal.business_potential > 0, "supported", "empty")
    site_support = modeled.merge(postal[["plz", "private_potential", "business_potential", "private_annual", "business_annual"]], on="plz", how="left")
    denominator = np.where(site_support.segment.eq("private"), site_support.private_potential, site_support.business_potential)
    segment_annual = np.where(site_support.segment.eq("private"), site_support.private_annual, site_support.business_annual)
    allocation_fraction = np.divide(site_support.weight, denominator, out=np.zeros(len(site_support)), where=denominator > 0)
    site_support["reference_annual"] = allocation_fraction * segment_annual
    if not np.isfinite(site_support.reference_annual).all() or (site_support.reference_annual < 0).any():
        raise ValueError("site allocations must be finite and nonnegative")
    site_support["historical_share"] = 0.; site_support["structural_share"] = 0.
    for segment in ("private", "business"):
        mask = site_support.segment.eq(segment)
        historical_total, structural_total = site_support.loc[mask, "reference_annual"].sum(), site_support.loc[mask, "weight"].sum()
        if historical_total > 0:
            site_support.loc[mask, "historical_share"] = site_support.loc[mask, "reference_annual"] / historical_total
        if structural_total > 0:
            site_support.loc[mask, "structural_share"] = site_support.loc[mask, "weight"] / structural_total
    reconstructed = float((postal_total_mean * dhl_share).sum())
    regional_annual = float(annual.sum())
    carrier_market = (1 - b) * conditional[0] + b * conditional[1]
    if reconciliation is not None:
        adjusted_q = reconciliation["q"]
        market = reconciliation_inputs["m"] / float(reconciliation_inputs["m"].sum())
        q_prior = reconciliation_inputs["q_prior"]
        q_scale = reconciliation_inputs["scale"]
        lower = reconciliation_inputs["lower"]
        upper = reconciliation_inputs["upper"]
    else:
        market = carrier_market.copy()
        adjusted_q = np.divide(b * conditional[1], market, out=np.zeros_like(market), where=market > 0)
        q_prior = np.full(len(carriers), np.nan)
        q_scale = np.full(len(carriers), np.nan)
        lower = np.full(len(carriers), np.nan)
        upper = np.full(len(carriers), np.nan)
    carriers_frame = pd.DataFrame({"year": 2021, "carrier": carriers, "market_share": market,
                                   "q_prior": q_prior, "q_scale": q_scale, "lower": lower, "upper": upper,
                                   "q_adjusted": adjusted_q, "private_share": conditional[0],
                                   "business_share": conditional[1], "share": carrier_market})
    persons = float(site_support.loc[site_support.segment.eq("private"), "weight"].sum())
    companies = int(site_support.segment.eq("business").sum())
    private_annual, business_annual = float(postal.private_annual.sum()), float(postal.business_annual.sum())
    implied_rates = {"persons_packages_per_operating_day": None if persons == 0 else private_annual / persons / operating_days,
                     "company_locations_packages_per_operating_day": None if companies == 0 else business_annual / companies / operating_days,
                     "semantics": "Aggregated model rates, not causal individual ordering rates."}
    allocated = site_support.groupby(["plz", "segment"], as_index=False)["reference_annual"].sum()
    expected = pd.concat([
        postal[["plz", "private_annual"]].rename(columns={"private_annual": "expected_annual"}).assign(segment="private"),
        postal[["plz", "business_annual"]].rename(columns={"business_annual": "expected_annual"}).assign(segment="business"),
    ], ignore_index=True)
    allocation_check = expected.merge(allocated, on=["plz", "segment"], how="left").fillna({"reference_annual": 0.})
    allocation_check["error"] = allocation_check.reference_annual - allocation_check.expected_annual
    share_errors = {}
    for segment in ("private", "business"):
        mask = site_support.segment.eq(segment)
        historical_sum = float(site_support.loc[mask, "historical_share"].sum())
        structural_sum = float(site_support.loc[mask, "structural_share"].sum())
        if site_support.loc[mask, "reference_annual"].sum() > 0 and not np.isclose(historical_sum, 1., atol=_ATOL, rtol=_RTOL):
            raise ValueError(f"historical shares are not normalized for {segment}")
        if site_support.loc[mask, "weight"].sum() > 0 and not np.isclose(structural_sum, 1., atol=_ATOL, rtol=_RTOL):
            raise ValueError(f"structural shares are not normalized for {segment}")
        share_errors[segment] = {"historical_sum": historical_sum, "structural_sum": structural_sum}
    regional_error = float(site_support.reference_annual.sum() - regional_annual)
    allocation_matches = np.isclose(allocation_check.reference_annual.to_numpy(float),
                                    allocation_check.expected_annual.to_numpy(float), atol=_ATOL, rtol=_RTOL)
    regional_matches = np.isclose(float(site_support.reference_annual.sum()), regional_annual, atol=_ATOL, rtol=_RTOL)
    if not allocation_matches.all() or not regional_matches:
        raise ValueError("site allocation balance failed")
    reconciliation_payload = {
        "market": [{"carrier": carrier, "market_share": float(value)} for carrier, value in zip(carriers, market, strict=True)],
        "providers": [{"carrier": carrier, "q_prior": None if not np.isfinite(prior) else float(prior),
                       "q_scale": None if not np.isfinite(scale) else float(scale),
                       "lower": None if not np.isfinite(bound_low) else float(bound_low),
                       "upper": None if not np.isfinite(bound_high) else float(bound_high)}
                      for carrier, prior, scale, bound_low, bound_high in zip(carriers, q_prior, q_scale, lower, upper, strict=True)],
        "adjusted_q": [{"carrier": carrier, "q_adjusted": float(value)} for carrier, value in zip(carriers, adjusted_q, strict=True)],
        "conditional": [{"segment": segment, "carrier": carrier, "share": float(conditional[row, index])}
                        for row, segment in enumerate(("private", "business")) for index, carrier in enumerate(carriers)],
        "diagnostics": None if reconciliation is None else reconciliation["diagnostics"],
        # Keep the complete reference-level eta solve alongside the carrier
        # projection.  A consumer therefore does not have to infer a numeric
        # bracket from a display-only checks artifact.
        "reference_balance": {
            "k": k,
            "k_status": k_status,
            "log_k": log_k,
            "eta": eta,
            "iterations": iterations,
            "status": eta_diagnostics["status"],
            "initial_endpoints": eta_diagnostics["initial_endpoints"],
            "initial_residuals": eta_diagnostics["initial_residuals"],
            "expanded_endpoints": eta_diagnostics["expanded_endpoints"],
            "expanded_residuals": eta_diagnostics["expanded_residuals"],
            "reachable_range": eta_diagnostics["reachable_range"],
            "residual": achieved_b - b,
        },
    }
    checks = {"scope_ledger": scope_ledger, "k": k, "k_status": k_status, "log_k": log_k,
              "eta": eta, "eta_iterations": iterations,
              "eta_diagnostics": eta_diagnostics,
              "dhl_carrier": carriers[dhl_index], "dhl_carrier_index": dhl_index,
              "dhl_market_share": float(carrier_market[dhl_index]),
              "b2b_target": float(b), "b2b_achieved": achieved_b, "b2b_residual": achieved_b - b,
              "dhl_reconstructed_mean": reconstructed, "dhl_retained_mean": float(dhl_values.sum()),
              "regional_annual_balance": regional_error,
              "allocation_balance": {"postal_segment_max_error": float(np.abs(allocation_check.error).max()),
                                     "regional_error": regional_error, "shares": share_errors},
              "allocation_ledger": site_support.groupby("allocation_status", dropna=False)["reference_annual"].sum().to_dict(),
              "reconciliation": None if reconciliation is None else reconciliation["diagnostics"]}
    if not np.isclose(reconstructed, dhl_values.sum(), atol=_ATOL, rtol=_RTOL):
        raise ValueError("reconstructed LSP balance failed")
    return {"sites": site_support, "postal": postal.drop(columns=["private_potential", "business_potential"]),
            "carriers": carriers_frame, "regional_annual": regional_annual, "checks": checks,
            "reconciliation": reconciliation_payload,
            "source_quality": {"unknown_plz_sites": unknown.site_id.astype(str).tolist(),
                               "unknown_plz_weight": float(unknown.weight.sum()),
                               "known_plz_outside_anchor_sites": known_outside_anchor.site_id.astype(str).tolist(),
                               "known_plz_outside_anchor_weight": float(known_outside_anchor.weight.sum())},
            "implied_rates": implied_rates}
