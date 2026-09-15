"""Balanced DHL-anchor reference calculation with no grid allocation."""
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
    """Exact active-set polish for the one-equality diagonal quadratic program."""
    fitted = np.empty_like(prior)
    free = np.ones(len(prior), dtype=bool)
    while True:
        fixed_mass = float(m[~free] @ fitted[~free]) if (~free).any() else 0.
        denominator = float(np.sum((m[free] * scale[free]) ** 2))
        if denominator == 0:
            if not np.isclose(fixed_mass, b, atol=_ATOL, rtol=_RTOL):
                raise ValueError("infeasible carrier bounds after active-set projection")
            return fitted
        multiplier = (float(m[free] @ prior[free]) + fixed_mass - b) / denominator
        fitted[free] = prior[free] - multiplier * m[free] * scale[free] ** 2
        below = free & (fitted < lower)
        above = free & (fitted > upper)
        if not below.any() and not above.any():
            return fitted
        fitted[below] = lower[below]
        fitted[above] = upper[above]
        free[below | above] = False


def reconcile_carriers(m: np.ndarray, q: np.ndarray, b: float, lower: np.ndarray,
                       upper: np.ndarray, scale: np.ndarray) -> dict:
    """Minimize scaled movement from carrier B2B priors under exact market balance."""
    m, prior, lower, upper, scale = (_array(x, name) for x, name in
                                    ((m, "m"), (q, "q"), (lower, "lower"), (upper, "upper"), (scale, "scale")))
    if not (len(m) == len(prior) == len(lower) == len(upper) == len(scale)):
        raise ValueError("carrier arrays must have the same length")
    if (m < 0).any() or not np.isclose(m.sum(), 1., atol=_ATOL, rtol=_RTOL):
        raise ValueError("m must be a nonnegative simplex")
    if not np.isfinite(b) or not 0 <= b <= 1 or (lower > upper).any() or (lower < 0).any() or (upper > 1).any() or (scale <= 0).any():
        raise ValueError("invalid carrier bounds, scales, or B2B target")
    feasible_low, feasible_high = float(m @ lower), float(m @ upper)
    if b < feasible_low - _ATOL or b > feasible_high + _ATOL:
        raise ValueError(f"infeasible carrier bounds: reachable [{feasible_low}, {feasible_high}], target {b}")
    positive = m > 0
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
        objective = lambda candidate: float(np.sum(((candidate - prior) / scale) ** 2))
        result = minimize(objective, np.clip(prior, lower, upper), method="SLSQP", bounds=list(zip(lower, upper)),
                          constraints=LinearConstraint(m.reshape(1, -1), b, b),
                          options={"ftol": 1e-13, "maxiter": 1000})
        fitted = np.asarray(result.x, dtype=float)
        solver = {"success": bool(result.success), "message": str(result.message), "iterations": int(result.nit)}
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
    return {"q": fitted, "conditional": conditional,
            "diagnostics": {"objective": float(np.sum(((fitted - prior) / scale) ** 2)),
                            "market_b2b": balance, "feasible_range": [feasible_low, feasible_high],
                            "solver": solver}}


def _profiles(profiles: dict, b: float) -> tuple[np.ndarray, list[str]]:
    conditional = np.asarray(profiles.get("conditional"), dtype=float)
    if conditional.ndim != 2 or conditional.shape[0] != 2 or conditional.shape[1] < 1:
        raise ValueError("profiles requires a 2 by C conditional array")
    if not np.isfinite(conditional).all() or (conditional < 0).any():
        raise ValueError("conditional carrier profiles must be finite and nonnegative")
    for row, active in enumerate((b < 1, b > 0)):
        if active and not np.isclose(conditional[row].sum(), 1., atol=_ATOL, rtol=_RTOL):
            raise ValueError("active conditional profile must sum to one")
    carriers = profiles.get("carriers") or ["DHL"] + [f"carrier_{index}" for index in range(1, conditional.shape[1])]
    if len(carriers) != conditional.shape[1]:
        raise ValueError("carrier labels do not match conditional profiles")
    return conditional, list(carriers)


def _scope(dhl: pd.DataFrame) -> tuple[pd.DataFrame, dict]:
    required = {"observation_id", "plz", "value", "value_status"}
    if missing := required.difference(dhl.columns):
        raise ValueError(f"dhl missing required columns: {sorted(missing)}")
    if dhl.observation_id.isna().any() or dhl.observation_id.duplicated().any():
        raise ValueError("DHL observation_id must be present and unique")
    values = pd.to_numeric(dhl.value, errors="coerce")
    if values.isna().any():
        raise ValueError("DHL has missing values")
    if not np.isfinite(values).all():
        raise ValueError("DHL values must be finite")
    if (values < 0).any():
        raise ValueError("DHL has negative values")
    if dhl.value_status.isna().any():
        raise ValueError("DHL value_status is required")
    if dhl.plz.isna().any() or dhl.plz.astype(str).str.strip().eq("").any():
        raise ValueError("DHL observations require a known PLZ")
    source = dhl.copy(); source["value"] = values.astype(float); source["plz"] = source.plz.astype(str)
    excluded = source.value.gt(1000.)
    retained = source.loc[~excluded].copy()
    return retained, {"rule": "exclude complete observation when value > 1000", "threshold": 1000.,
                      "excluded_rows": int(excluded.sum()), "excluded_volume": float(source.loc[excluded, "value"].sum()),
                      "retained_rows": int((~excluded).sum()), "retained_volume": float(retained.value.sum())}


def solve_reference(potentials: pd.DataFrame, dhl: pd.DataFrame, profiles: dict, b: float,
                    operating_days: int) -> dict:
    """Reconcile providers then derive a single balanced, grid-free 2021 reference."""
    if not np.isfinite(b) or not 0 <= b <= 1 or isinstance(operating_days, bool) or int(operating_days) != operating_days or operating_days <= 0:
        raise ValueError("B2B target and operating_days must be valid")
    reconciliation = None
    if "conditional" not in profiles:
        try:
            reconciliation = reconcile_carriers(profiles.get("m", profiles.get("market")),
                                                profiles.get("q", profiles.get("q_prior")), b,
                                                profiles["lower"], profiles["upper"], profiles["scale"])
        except KeyError as exc:
            raise ValueError("profiles requires conditional profiles or carrier reconciliation inputs") from exc
        profiles = {**profiles, "conditional": reconciliation["conditional"]}
    conditional, carriers = _profiles(profiles, b)
    retained, scope_ledger = _scope(dhl)
    if retained.empty or retained.value.sum() <= 0:
        raise ValueError("empty DHL reference region has no positive retained observation")
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
        raise ValueError("positive DHL has no potential support")
    support = modeled.pivot_table(index="plz", columns="segment", values="weight", aggfunc="sum", fill_value=0.)
    support = anchor.set_index("plz").join(support, how="left").fillna(0.)
    for segment in ("private", "business"):
        if segment not in support:
            support[segment] = 0.
    support = support[["dhl_retained_mean", "private", "business"]]
    positive = support.dhl_retained_mean.to_numpy(float) > 0
    private, business, dhl_values = (support[name].to_numpy(float) for name in ("private", "business", "dhl_retained_mean"))
    if np.any(positive & ((private + business) <= 0)):
        raise ValueError("positive DHL has no potential support in at least one PLZ")
    private_dhl, business_dhl = conditional[0, 0], conditional[1, 0]
    if b == 0:
        if np.any(positive & (private <= 0)):
            raise ValueError("positive DHL has no private support for b=0")
        local_b = np.zeros(len(support)); k, eta, iterations = 1., 0., 0
    elif b == 1:
        if np.any(positive & (business <= 0)):
            raise ValueError("positive DHL has no business support for b=1")
        local_b = np.ones(len(support)); k, eta, iterations = 1., 0., 0
    else:
        def totals(candidate_eta):
            local = np.zeros_like(private)
            both = (private > 0) & (business > 0)
            local[both] = expit(candidate_eta + np.log(business[both]) - np.log(private[both]))
            local[(private <= 0) & (business > 0)] = 1.
            share = (1 - local) * private_dhl + local * business_dhl
            if np.any(positive & (share <= 0)):
                raise ValueError("positive DHL requires a positive local DHL share")
            total = np.divide(dhl_values, share, out=np.zeros_like(dhl_values), where=share > 0)
            return local, share, total
        def residual(candidate_eta):
            local, _, total = totals(candidate_eta)
            return float(total @ local / total.sum() - b)
        eta_low, eta_high = -30., 30.
        low, high = residual(eta_low), residual(eta_high)
        if np.isclose(low, high, atol=1e-12, rtol=0):
            if abs(residual(0.)) > 1e-10:
                raise ValueError(f"constant reachable B2B share does not match target; reachable={residual(0.) + b}")
            eta, iterations = 0., 0
        else:
            while low * high > 0 and max(abs(eta_low), abs(eta_high)) < 700.:
                if low < 0 and high < 0:
                    eta_high = min(700., eta_high * 2.)
                    high = residual(eta_high)
                elif low > 0 and high > 0:
                    eta_low = max(-700., eta_low * 2.)
                    low = residual(eta_low)
                else:
                    break
            if abs(low) <= 1e-10:
                eta, iterations = eta_low, 0
            elif abs(high) <= 1e-10:
                eta, iterations = eta_high, 0
            elif low * high > 0:
                raise ValueError(f"B2B target has no sign change; reachable residuals [{low}, {high}]")
            else:
                root, details = brentq(residual, eta_low, eta_high, xtol=1e-12, full_output=True)
                eta, iterations = float(root), int(details.iterations)
        k = float(np.exp(eta)); local_b, _, _ = totals(eta)
    dhl_share = (1 - local_b) * private_dhl + local_b * business_dhl
    if np.any(positive & (dhl_share <= 0)):
        raise ValueError("positive DHL requires a positive local DHL share")
    postal_total_mean = np.divide(dhl_values, dhl_share, out=np.zeros_like(dhl_values), where=dhl_share > 0)
    achieved_b = float(postal_total_mean @ local_b / postal_total_mean.sum())
    if not np.isclose(achieved_b, b, atol=1e-10, rtol=0):
        raise ValueError(f"B2B reference balance failed: target={b}, actual={achieved_b}")
    annual = postal_total_mean * float(operating_days)
    postal = support.reset_index().rename(columns={"private": "private_potential", "business": "business_potential"})
    postal["b2b_share"] = local_b; postal["dhl_share"] = dhl_share; postal["reference_annual"] = annual
    postal["private_annual"] = annual * (1 - local_b); postal["business_annual"] = annual * local_b
    postal["private_support"] = np.where(postal.private_potential > 0, "supported", "empty")
    postal["business_support"] = np.where(postal.business_potential > 0, "supported", "empty")
    site_support = modeled.merge(postal[["plz", "private_potential", "business_potential", "private_annual", "business_annual"]], on="plz", how="left")
    denominator = np.where(site_support.segment.eq("private"), site_support.private_potential, site_support.business_potential)
    segment_annual = np.where(site_support.segment.eq("private"), site_support.private_annual, site_support.business_annual)
    site_support["reference_annual"] = np.divide(site_support.weight * segment_annual, denominator,
                                                    out=np.zeros(len(site_support)), where=denominator > 0)
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
    carriers_frame = pd.DataFrame({"year": 2021, "carrier": carriers, "share": carrier_market})
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
    if not np.isclose(allocation_check.error.to_numpy(float), 0., atol=_ATOL, rtol=_RTOL).all() or not np.isclose(regional_error, 0., atol=_ATOL, rtol=_RTOL):
        raise ValueError("site allocation balance failed")
    checks = {"scope_ledger": scope_ledger, "k": k, "eta": eta, "eta_iterations": iterations,
              "b2b_target": float(b), "b2b_achieved": achieved_b, "b2b_residual": achieved_b - b,
              "dhl_reconstructed_mean": reconstructed, "dhl_retained_mean": float(dhl_values.sum()),
              "regional_annual_balance": regional_error,
              "allocation_balance": {"postal_segment_max_error": float(np.abs(allocation_check.error).max()),
                                     "regional_error": regional_error, "shares": share_errors},
              "allocation_ledger": site_support.groupby("allocation_status", dropna=False)["reference_annual"].sum().to_dict(),
              "reconciliation": None if reconciliation is None else reconciliation["diagnostics"]}
    if not np.isclose(reconstructed, dhl_values.sum(), atol=_ATOL, rtol=_RTOL):
        raise ValueError("reconstructed DHL balance failed")
    return {"sites": site_support, "postal": postal.drop(columns=["private_potential", "business_potential"]),
            "carriers": carriers_frame, "regional_annual": regional_annual, "checks": checks,
            "source_quality": {"unknown_plz_sites": unknown.site_id.astype(str).tolist(),
                               "unknown_plz_weight": float(unknown.weight.sum()),
                               "known_plz_outside_anchor_sites": known_outside_anchor.site_id.astype(str).tolist(),
                               "known_plz_outside_anchor_weight": float(known_outside_anchor.weight.sum())},
            "implied_rates": implied_rates}
