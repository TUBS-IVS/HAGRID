"""Annual projection from the frozen reference baseline contract."""

from __future__ import annotations

from typing import Any

import numpy as np
import pandas as pd

from hagrid_demand.common.contracts import ATOL, RTOL, AnnualProjection, assert_balance
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


def _regional_level(cfg: dict, years: list[int]) -> tuple[str, pd.DataFrame | None]:
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
    if (not external.unit.eq("packages/year").all() or external.provenance.astype(str).str.strip().eq("").any()
            or external.scope.astype(str).str.strip().eq("").any()):
        raise ValueError("external_annual_series requires unit=packages/year, provenance, and scope")
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


def _profile(series: dict, year: int) -> tuple[pd.DataFrame, float]:
    market = _year_row(_table(series.get("market"), "series.market"), year,
                       {"year", "carrier", "market_share"}, "market")
    providers = _year_row(_table(series.get("providers"), "series.providers"), year,
                          {"year", "carrier", "q_prior", "q_scale", "lower", "upper"}, "providers")
    b2b = _year_row(_table(series.get("b2b"), "series.b2b"), year, {"year", "share"}, "b2b")
    if len(b2b) != 1 or market.carrier.astype(str).duplicated().any() or providers.carrier.astype(str).duplicated().any():
        raise ValueError("carrier and B2B series require unique labels per year")
    market = market.assign(carrier=market.carrier.astype(str)).set_index("carrier", drop=False)
    providers = providers.assign(carrier=providers.carrier.astype(str)).set_index("carrier", drop=False)
    if set(market.index) != set(providers.index):
        raise ValueError("market and provider carrier labels differ")
    providers = providers.reindex(market.index)
    values, target = market.market_share.to_numpy(float), float(b2b.iloc[0].share)
    if not np.isfinite(values).all() or (values < 0).any() or not np.isclose(values.sum(), 1., atol=ATOL, rtol=RTOL):
        raise ValueError("market shares must be a nonnegative simplex")
    result = reconcile_carriers(values, providers.q_prior.to_numpy(float), target,
                                providers.lower.to_numpy(float), providers.upper.to_numpy(float), providers.q_scale.to_numpy(float))
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


def project_annual(reference: dict, series: dict, years: list[int], cfg: dict) -> AnnualProjection:
    """Project a regional annual total once and preserve reference shares by segment."""
    if not isinstance(reference, dict) or not isinstance(series, dict) or not isinstance(cfg, dict):
        raise ValueError("reference, series, and cfg must be mappings")
    if cfg.get("memory", {}).get("fixed") != 1:
        raise ValueError("Plan 02 requires memory.fixed=1")
    requested = _years(years)
    mode, external = _regional_level(cfg, requested)
    sites = _table(reference.get("sites", reference.get("reference_sites")), "reference.sites")
    required = {"site_id", "plz", "segment", "historical_share"}
    if missing := required.difference(sites.columns):
        raise ValueError(f"reference.sites missing columns: {sorted(missing)}")
    sites["segment"] = sites.segment.astype(str).str.lower()
    sites["historical_share"] = pd.to_numeric(sites.historical_share, errors="coerce")
    if (not sites.segment.isin(["private", "business"]).all() or sites.duplicated(["site_id", "segment"]).any()
            or sites.plz.isna().any() or sites.plz.astype(str).str.strip().eq("").any()
            or sites.historical_share.isna().any() or not np.isfinite(sites.historical_share).all() or (sites.historical_share < 0).any()):
        raise ValueError("reference sites require unique supported private/business site keys")
    support = sites.groupby("segment").historical_share.sum()
    shares = sites.historical_share / sites.segment.map(support)
    volume = _table(series.get("volume"), "series.volume") if mode == "national_series" else pd.DataFrame()
    site_frames, profile_frames, postal_frames, balance_rows = [], [], [], []
    regional_reference = float(reference.get("regional_annual", np.nan))
    if not np.isfinite(regional_reference) or regional_reference <= 0:
        raise ValueError("reference.regional_annual must be finite and positive")
    for year in requested:
        total, growth = (_national_total(reference, volume, year) if mode == "national_series"
                         else (float(external.at[year, "value"]), float(external.at[year, "value"]) / regional_reference))
        profile, b2b = _profile(series, year)
        targets = {"private": total * (1 - b2b), "business": total * b2b}
        for segment, target in targets.items():
            if target > ATOL and (segment not in support or support[segment] <= 0):
                raise ValueError(f"positive {segment} annual target requires positive site support")
        annual = pd.DataFrame({"year": year, "site_id": sites.site_id, "plz": sites.plz.astype(str), "segment": sites.segment,
                               "annual_expected": shares * sites.segment.map(targets), "share": shares})
        postal = annual.groupby("plz", as_index=False)["annual_expected"].sum()
        postal.insert(0, "year", year)
        postal["memory_weight"] = 1.
        postal["regional_level_mode"] = mode
        postal["growth_factor"] = growth
        postal["b2b_share"] = b2b
        assert_balance(annual.annual_expected.sum(), total)
        assert_balance(postal.annual_expected.sum(), total)
        segment_errors = {}
        for segment, target in targets.items():
            actual = annual.loc[annual.segment.eq(segment), "annual_expected"].sum()
            assert_balance(actual, target)
            segment_errors[segment] = float(actual - target)
        balance_rows.append({"year": year, "regional_error": float(annual.annual_expected.sum() - total),
                             "postal_error": float(postal.annual_expected.sum() - total), "segment_errors": segment_errors})
        site_frames.append(annual); profile_frames.append(profile); postal_frames.append(postal)
    result_sites = pd.concat(site_frames, ignore_index=True)
    result_profiles = pd.concat(profile_frames, ignore_index=True)
    result_postal = pd.concat(postal_frames, ignore_index=True)
    hashes = {"sites": _frame_hash(result_sites), "profiles": _frame_hash(result_profiles), "postal": _frame_hash(result_postal)}
    checks = {"hashes": hashes, "balances": {"by_year": balance_rows,
              "regional_error": max(abs(row["regional_error"]) for row in balance_rows),
              "postal_error": max(abs(row["postal_error"]) for row in balance_rows), "atol": ATOL, "rtol": RTOL}}
    return AnnualProjection(result_sites, result_profiles, result_postal, checks)
