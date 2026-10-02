"""Annual projection from the frozen reference baseline contract."""

from __future__ import annotations

from typing import Any

import numpy as np
import pandas as pd

from hagrid_demand.common.contracts import ATOL, RTOL, AnnualProjection, assert_balance, verified_scope_id
from hagrid_demand.common.provenance import canonical_digest

from .reference import reconcile_carriers


def _table(value: Any, label: str) -> pd.DataFrame:
    if not isinstance(value, pd.DataFrame):
        raise ValueError(f"{label} must be a DataFrame")
    return value.copy()


def _year_row(table: pd.DataFrame, year: int, required: set[str], label: str) -> pd.DataFrame:
    if missing := required.difference(table.columns):
        raise ValueError(f"{label} missing columns: {sorted(missing)}")
    rows = table.loc[pd.to_numeric(table.year, errors="coerce").eq(year)].copy()
    if rows.empty:
        raise ValueError(f"{label} has no row for year {year}")
    return rows


def _regional_level(cfg: dict, years: list[int], verified_scope: object) -> tuple[str, pd.DataFrame | None]:
    if "external_annual_series" in cfg:
        raise ValueError("external_annual_series must be declared in regional_level")
    level = cfg.get("regional_level", {"mode": "national_series"})
    if not isinstance(level, dict) or level.get("mode", "national_series") not in {"national_series", "external_annual_series"}:
        raise ValueError("regional_level.mode must be national_series or external_annual_series")
    mode = level.get("mode", "national_series")
    if mode == "national_series":
        return mode, None
    if any(key in level for key in ("annual_growth", "growth", "volume_growth", "national_series", "volume")):
        raise ValueError("external_annual_series cannot be combined with a national growth channel")
    source = level.get("series")
    if not isinstance(source, (list, pd.DataFrame)):
        raise ValueError("external_annual_series requires a yearly series")
    external = source.copy() if isinstance(source, pd.DataFrame) else pd.DataFrame(source)
    required = {"year", "value", "unit", "provenance", "scope"}
    if missing := required.difference(external.columns):
        raise ValueError(f"external_annual_series missing columns: {sorted(missing)}")
    if external.year.map(lambda value: type(value) is int).eq(False).any() or external.year.lt(2021).any():
        raise ValueError("external_annual_series years must be integer years from 2021")
    if external.year.duplicated().any() or set(external.year) != set(years):
        raise ValueError("external_annual_series must cover exactly the requested years")
    values = pd.to_numeric(external.value, errors="coerce")
    if values.isna().any() or not np.isfinite(values).all() or (values < 0).any():
        raise ValueError("external_annual_series values must be finite and nonnegative")
    text_fields = ("unit", "provenance", "scope")
    if (not external.unit.eq("packages/year").all() or any(
            not isinstance(value, str) or not value.strip() for field in text_fields for value in external[field])):
        raise ValueError("external_annual_series requires unit=packages/year, provenance, and scope")
    if not external.scope.eq(verified_scope).all():
        raise ValueError("external_annual_series scope must match verified reference scope")
    external["value"] = values.astype(float)
    return mode, external.set_index("year", drop=False)


def _national_total(reference: dict, volume: pd.DataFrame, year: int) -> tuple[float, float]:
    regional = float(reference.get("regional_annual", np.nan))
    if not np.isfinite(regional) or regional <= 0:
        raise ValueError("reference.regional_annual must be finite and positive")
    current = _year_row(volume, year, {"year", "value"}, "volume")
    base = _year_row(volume, 2021, {"year", "value"}, "volume")
    if len(current) != 1 or len(base) != 1:
        raise ValueError("volume must have one row per year")
    numerator, denominator = float(current.iloc[0].value), float(base.iloc[0].value)
    if not np.isfinite(numerator) or not np.isfinite(denominator) or numerator < 0 or denominator <= 0:
        raise ValueError("volume values must be finite and V_2021 must be positive")
    return regional * numerator / denominator, numerator / denominator


def _profile(series: dict, year: int, dhl_fixed: dict | None = None) -> tuple[pd.DataFrame, float]:
    """Carrier profiles of *year*; ``dhl_fixed`` pins the LSP's B2B share to q_2021 * b(y) / b_2021."""
    market = _year_row(_table(series.get("market"), "series.market"), year,
                       {"year", "carrier", "market_share"}, "market")
    providers = _year_row(_table(series.get("providers"), "series.providers"), year,
                          {"year", "carrier", "q_prior", "q_scale", "lower", "upper"}, "providers")
    b2b = _year_row(_table(series.get("b2b"), "series.b2b"), year, {"year", "share"}, "b2b")
    if len(b2b) != 1 or market.carrier.astype(str).duplicated().any() or providers.carrier.astype(str).duplicated().any():
        raise ValueError("carrier and B2B series require unique labels per year")
    # a canonical carrier order: the reconciliation sums and optimises over the carriers, so in the order of the input
    # rows its last bits would depend on that order (and on the platform)
    market = market.assign(carrier=market.carrier.astype(str)).set_index("carrier", drop=False).sort_index()
    providers = providers.assign(carrier=providers.carrier.astype(str)).set_index("carrier", drop=False)
    if set(market.index) != set(providers.index):
        raise ValueError("market and provider carrier labels differ")
    providers = providers.reindex(market.index)
    values, target = market.market_share.to_numpy(float), float(b2b.iloc[0].share)
    if not np.isfinite(values).all() or (values < 0).any() or not np.isclose(values.sum(), 1., atol=ATOL, rtol=RTOL):
        raise ValueError("market shares must be a nonnegative simplex")
    lower, upper = providers.lower.to_numpy(float).copy(), providers.upper.to_numpy(float).copy()
    prior = providers.q_prior.to_numpy(float).copy()
    if dhl_fixed is not None:
        dhl = [index for index, label in enumerate(market.index) if str(label).strip().casefold() == "dhl"]
        if len(dhl) != 1:
            raise ValueError("dhl_fixed requires exactly one DHL carrier")
        q_dhl = float(dhl_fixed["q_2021"]) * target / float(dhl_fixed["b_2021"])
        lower[dhl[0]] = upper[dhl[0]] = q_dhl
        prior = np.clip(prior, lower, upper)
    result = reconcile_carriers(values, prior, target, lower, upper, providers.q_scale.to_numpy(float))
    rows = []
    for index, carrier in enumerate(market.index):
        for row, segment in enumerate(("private", "business")):
            rows.append({"year": year, "segment": segment, "carrier": carrier, "market_share": values[index],
                         "share": result["conditional"][row, index], "q": result["q"][index]})
    return pd.DataFrame(rows), target


def _frame_hash(frame: pd.DataFrame) -> str:
    return canonical_digest({"columns": frame.columns.tolist(), "rows": frame.to_dict(orient="records")})


def _years(values: list[int]) -> list[int]:
    if not isinstance(values, list) or not values or any(type(year) is not int or year < 2021 for year in values):
        raise ValueError("years must contain genuine integer years from 2021")
    return sorted(set(values))


def _site_factor_lookup(site_factors: pd.DataFrame | None) -> pd.Series | None:
    """Validated factor series indexed by (year, site_id, segment); None without factors."""
    if site_factors is None:
        return None
    if not isinstance(site_factors, pd.DataFrame) or {"year", "site_id", "segment", "factor"} - set(site_factors.columns):
        raise ValueError("site_factors needs the columns year, site_id, segment and factor")
    values = pd.to_numeric(site_factors.factor, errors="coerce").to_numpy(float)
    if not np.isfinite(values).all() or (values < 0).any():
        raise ValueError("site_factors must be finite and nonnegative")
    index = pd.MultiIndex.from_arrays([site_factors.year.astype(int).to_numpy(), site_factors.site_id.astype(str).to_numpy(),
                                       site_factors.segment.astype(str).to_numpy()])
    lookup = pd.Series(values, index=index)
    if lookup.index.duplicated().any():
        raise ValueError("site_factors must be unique per year, site_id and segment")
    return lookup


def project_annual(reference: dict, series: dict, years: list[int], cfg: dict,
                   site_factors: pd.DataFrame | None = None) -> AnnualProjection:
    """Project a regional annual total once and preserve reference shares by segment.

    ``site_factors`` (``year, site_id, segment, factor``) re-weights the historical shares per year (land-use
    dynamics): weight = historical share x factor, normalised per segment; a missing factor counts as 1.
    """
    if not isinstance(reference, dict) or not isinstance(series, dict) or not isinstance(cfg, dict):
        raise ValueError("reference, series, and cfg must be mappings")
    if cfg.get("memory", {}).get("fixed") != 1:
        raise ValueError("Plan 02 requires memory.fixed=1")
    requested = _years(years)
    sites = _table(reference.get("sites", reference.get("reference_sites")), "reference.sites")
    required = {"site_id", "plz", "segment", "historical_share", "allocation_status"}
    if missing := required.difference(sites.columns):
        raise ValueError(f"reference.sites missing columns: {sorted(missing)}")
    sites["segment"] = sites.segment.astype(str).str.lower()
    sites["historical_share"] = pd.to_numeric(sites.historical_share, errors="coerce")
    if (not sites.segment.isin(["private", "business"]).all() or sites.duplicated(["site_id", "segment"]).any()
            or sites.plz.isna().any() or sites.plz.astype(str).str.strip().eq("").any()
            or sites.allocation_status.isna().any() or ~sites.allocation_status.isin(["located", "unlocated"]).all()
            or sites.historical_share.isna().any() or not np.isfinite(sites.historical_share).all() or (sites.historical_share < 0).any()):
        raise ValueError("reference sites require unique supported private/business site keys")
    support = sites.groupby("segment").historical_share.sum()
    verified_scope = reference.get("scope_id", verified_scope_id(sites.plz.astype(str).tolist()))
    if not isinstance(verified_scope, str) or len(verified_scope) != 64:
        raise ValueError("reference scope_id must be a canonical scope digest")
    mode, external = _regional_level(cfg, requested, verified_scope)
    volume = _table(series.get("volume"), "series.volume") if mode == "national_series" else pd.DataFrame()
    site_frames, profile_frames, postal_frames, balance_rows, external_metadata = [], [], [], [], []
    regional_reference = float(reference.get("regional_annual", np.nan))
    if not np.isfinite(regional_reference) or regional_reference <= 0:
        raise ValueError("reference.regional_annual must be finite and positive")
    factor_of = _site_factor_lookup(site_factors)
    for year in requested:
        total, growth = (_national_total(reference, volume, year) if mode == "national_series"
                         else (float(external.at[year, "value"]), float(external.at[year, "value"]) / regional_reference))
        profile, b2b = _profile(series, year, cfg.get("dhl_b2b"))
        targets = {"private": total * (1 - b2b), "business": total * b2b}
        share = pd.Series(0., index=sites.index, dtype=float)
        support_status = pd.Series("zero_target_no_support", index=sites.index, dtype=object)
        weights, year_support, open_rows = sites["historical_share"], support, None
        if factor_of is not None:
            keys = pd.MultiIndex.from_arrays([np.full(len(sites), year), sites.site_id.astype(str).to_numpy(), sites.segment.to_numpy()])
            factor = np.nan_to_num(factor_of.reindex(keys).to_numpy(float), nan=1.)
            weights = sites["historical_share"] * factor
            year_support = weights.groupby(sites.segment).sum()
            open_rows = factor != 0.  # closed sites (not yet opened, emptied stock) are left out of the year
        for segment, target in targets.items():
            segment_mask = sites.segment.eq(segment)
            segment_support = float(year_support.get(segment, 0.))
            if target > ATOL and segment_support <= 0:
                raise ValueError(f"positive {segment} annual target requires positive site support")
            if segment_support > 0:
                share.loc[segment_mask] = weights.loc[segment_mask] / segment_support
                support_status.loc[segment_mask] = "supported"
        annual = pd.DataFrame({"year": year, "site_id": sites.site_id, "plz": sites.plz.astype(str), "segment": sites.segment,
                               "allocation_status": sites.allocation_status, "support_status": support_status,
                               "annual_expected": share * sites.segment.map(targets), "share": share})
        if open_rows is not None:
            annual = annual.loc[open_rows].reset_index(drop=True)
        grouped = annual.groupby(["plz", "segment"], as_index=False)["annual_expected"].sum()
        grid = pd.MultiIndex.from_product([sorted(annual.plz.unique()), ["private", "business"]],
                                          names=["plz", "segment"]).to_frame(index=False)
        postal = grid.merge(grouped, on=["plz", "segment"], how="left")
        postal["annual_expected"] = postal.annual_expected.fillna(0.)
        postal["support_status"] = postal.segment.map(
            lambda segment: "supported" if float(support.get(segment, 0.)) > 0 else "zero_target_no_support"
        )
        postal.insert(0, "year", year)
        postal["memory_weight"] = 1.
        postal["regional_level_mode"] = mode
        postal["growth_factor"] = growth
        postal["b2b_share"] = b2b
        postal = postal[["year", "plz", "segment", "annual_expected", "support_status", "memory_weight",
                         "regional_level_mode", "growth_factor", "b2b_share"]]
        assert_balance(annual.annual_expected.sum(), total)
        assert_balance(postal.annual_expected.sum(), total)
        segment_errors, share_sums, postal_site_errors = {}, {}, []
        for segment, target in targets.items():
            actual = annual.loc[annual.segment.eq(segment), "annual_expected"].sum()
            assert_balance(actual, target)
            segment_errors[segment] = float(actual - target)
            segment_support = float(year_support.get(segment, 0.))
            share_sum = float(annual.loc[annual.segment.eq(segment), "share"].sum())
            assert_balance(share_sum, 1. if segment_support > 0 else 0.)
            share_sums[segment] = share_sum
        grouped_sites = annual.groupby(["plz", "segment"], as_index=False)["annual_expected"].sum()
        observed_postal = postal.merge(grouped_sites, on=["plz", "segment"], how="left", suffixes=("_postal", "_sites"))
        observed_postal["annual_expected_sites"] = observed_postal.annual_expected_sites.fillna(0.)
        for row in observed_postal.itertuples(index=False):
            assert_balance(row.annual_expected_postal, row.annual_expected_sites)
            postal_site_errors.append({"plz": row.plz, "segment": row.segment,
                                       "error": float(row.annual_expected_postal - row.annual_expected_sites)})
        balance_rows.append({"year": year, "regional_error": float(annual.annual_expected.sum() - total),
                             "postal_error": float(postal.annual_expected.sum() - total), "segment_errors": segment_errors,
                             "share_sums": share_sums, "postal_site_errors": postal_site_errors})
        if external is not None:
            external_metadata.append(external.loc[year, ["year", "value", "unit", "provenance", "scope"]].to_dict())
        site_frames.append(annual); profile_frames.append(profile); postal_frames.append(postal)
    result_sites = pd.concat(site_frames, ignore_index=True).sort_values(["year", "segment", "plz", "site_id"], kind="stable").reset_index(drop=True)
    result_profiles = pd.concat(profile_frames, ignore_index=True).sort_values(["year", "segment", "carrier"], kind="stable").reset_index(drop=True)
    result_postal = pd.concat(postal_frames, ignore_index=True).sort_values(["year", "segment", "plz"], kind="stable").reset_index(drop=True)
    if result_postal.duplicated(["year", "plz", "segment"]).any():
        raise ValueError("projection postal keys must be unique")
    hashes = {"sites": _frame_hash(result_sites), "profiles": _frame_hash(result_profiles), "postal": _frame_hash(result_postal)}
    checks = {"hashes": hashes, "balances": {"by_year": balance_rows,
              "regional_error": max(abs(row["regional_error"]) for row in balance_rows),
              "postal_error": max(abs(row["postal_error"]) for row in balance_rows),
              "share_sums": [row["share_sums"] for row in balance_rows],
              "postal_site_errors": [row["postal_site_errors"] for row in balance_rows], "atol": ATOL, "rtol": RTOL},
              "scope_id": verified_scope,
              "external_annual_series": {"scope_id": verified_scope, "records": external_metadata} if external is not None else None,
              "identity_hash": canonical_digest({"mode": mode, "verified_scope": verified_scope, "external": external_metadata})}
    return AnnualProjection(result_sites, result_profiles, result_postal, checks)
