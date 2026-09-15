"""Annual projection from the frozen reference baseline contract."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

import numpy as np
import pandas as pd

from hagrid_demand.common.provenance import canonical_digest

from .reference import reconcile_carriers


@dataclass(frozen=True)
class AnnualProjection:
    sites: pd.DataFrame
    carrier_profiles: pd.DataFrame
    postal: pd.DataFrame
    hashes: dict[str, str]

    @property
    def site_hash(self) -> str:
        return self.hashes["sites"]

    @property
    def carrier_profile_hash(self) -> str:
        return self.hashes["carrier_profiles"]

    @property
    def postal_hash(self) -> str:
        return self.hashes["postal"]

    @property
    def profiles(self) -> pd.DataFrame:
        """Alias for the year/segment carrier-profile table."""
        return self.carrier_profiles

    @property
    def artifact_hashes(self) -> dict[str, str]:
        return self.hashes


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


def _annual_total(reference: dict, volume: pd.DataFrame, year: int, cfg: dict) -> float:
    external = cfg.get("external_annual_series")
    if external is not None:
        if any(key in cfg for key in ("annual_growth", "growth", "volume_growth", "growth_rate",
                                      "growth_factor", "volume_curve")):
            raise ValueError("external_annual_series cannot be combined with a growth channel")
        if isinstance(external, dict):
            try:
                value = external[year] if year in external else external[str(year)]
            except KeyError as exc:
                raise ValueError(f"external_annual_series has no value for year {year}") from exc
        elif isinstance(external, pd.DataFrame):
            if "year" not in external:
                raise ValueError("external_annual_series missing columns: ['year']")
            amount = next((name for name in ("value", "annual", "packages") if name in external), None)
            if amount is None:
                raise ValueError("external_annual_series requires value, annual, or packages")
            row = _year_row(external, year, {"year", amount}, "external_annual_series")
            if len(row) != 1:
                raise ValueError("external_annual_series must have one row per year")
            value = row.iloc[0][amount]
        else:
            raise ValueError("external_annual_series must be a year mapping or DataFrame")
        value = float(value)
        if not np.isfinite(value) or value < 0:
            raise ValueError("external_annual_series values must be finite and nonnegative")
        return value
    regional = float(reference.get("regional_annual", np.nan))
    if not np.isfinite(regional) or regional < 0:
        raise ValueError("reference.regional_annual must be finite and nonnegative")
    current = _year_row(volume, year, {"year", "value"}, "volume")
    base = _year_row(volume, 2021, {"year", "value"}, "volume")
    if len(current) != 1 or len(base) != 1:
        raise ValueError("volume must have one row per year")
    numerator, denominator = float(current.iloc[0].value), float(base.iloc[0].value)
    if not np.isfinite(numerator) or not np.isfinite(denominator) or numerator < 0 or denominator <= 0:
        raise ValueError("volume values must be finite and V_2021 must be positive")
    return regional * numerator / denominator


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
    values = market.market_share.to_numpy(float)
    target = float(b2b.iloc[0].share)
    if not np.isfinite(values).all() or (values < 0).any() or not np.isclose(values.sum(), 1., atol=1e-8, rtol=1e-10):
        raise ValueError("market shares must be a nonnegative simplex")
    result = reconcile_carriers(values, providers.q_prior.to_numpy(float), target,
                                providers.lower.to_numpy(float), providers.upper.to_numpy(float),
                                providers.q_scale.to_numpy(float))
    conditional = result["conditional"]
    rows = []
    for position, carrier in enumerate(market.index):
        rows.extend([
            {"year": year, "segment": "private", "carrier": carrier, "market_share": values[position],
             "q_adjusted": result["q"][position], "share": conditional[0, position]},
            {"year": year, "segment": "business", "carrier": carrier, "market_share": values[position],
             "q_adjusted": result["q"][position], "share": conditional[1, position]},
        ])
    return pd.DataFrame(rows), target


def _frame_hash(frame: pd.DataFrame) -> str:
    return canonical_digest({"columns": frame.columns.tolist(), "rows": frame.to_dict(orient="records")})


def project_annual(reference: dict, series: dict, years: list[int], cfg: dict) -> AnnualProjection:
    """Project regional totals once, then preserve reference site shares by segment."""
    if not isinstance(reference, dict) or not isinstance(series, dict) or not isinstance(cfg, dict):
        raise ValueError("reference, series, and cfg must be mappings")
    if cfg.get("memory", {}).get("fixed") != 1:
        raise ValueError("Plan 02 requires memory.fixed=1")
    requested = sorted({int(year) for year in years})
    if not requested:
        raise ValueError("years must not be empty")
    sites = _table(reference.get("sites", reference.get("reference_sites")), "reference.sites")
    required = {"site_id", "segment", "historical_share"}
    if missing := required.difference(sites.columns):
        raise ValueError(f"reference.sites missing columns: {sorted(missing)}")
    sites["segment"] = sites.segment.astype(str).str.lower()
    if not sites.segment.isin(["private", "business"]).all() or sites.duplicated(["site_id", "segment"]).any():
        raise ValueError("reference sites require unique private/business site keys")
    sites["historical_share"] = pd.to_numeric(sites.historical_share, errors="coerce")
    if sites.historical_share.isna().any() or not np.isfinite(sites.historical_share).all() or (sites.historical_share < 0).any():
        raise ValueError("historical_share must be finite and nonnegative")
    shares = sites.groupby("segment").historical_share.transform("sum")
    if (shares <= 0).any():
        raise ValueError("each reference segment requires positive historical support")
    sites["historical_share"] = sites.historical_share / shares
    volume = (_table(series.get("volume"), "series.volume")
              if cfg.get("external_annual_series") is None else pd.DataFrame())
    projected, profiles = [], []
    for year in requested:
        total = _annual_total(reference, volume, year, cfg)
        profile, b2b = _profile(series, year)
        profiles.append(profile)
        segment_total = {"private": total * (1 - b2b), "business": total * b2b}
        annual = sites.copy()
        annual["year"] = year
        annual["regional_annual"] = total
        annual["segment_annual"] = annual.segment.map(segment_total)
        annual["reference_annual"] = annual.historical_share * annual.segment_annual
        projected.append(annual)
    site_result = pd.concat(projected, ignore_index=True)
    profiles_result = pd.concat(profiles, ignore_index=True)
    postal_columns = ["year", "segment", "reference_annual"]
    if "plz" in site_result:
        postal_columns.insert(2, "plz")
    postal = site_result.loc[:, postal_columns].groupby(postal_columns[:-1], dropna=False, as_index=False)["reference_annual"].sum()
    hashes = {"sites": _frame_hash(site_result), "carrier_profiles": _frame_hash(profiles_result), "postal": _frame_hash(postal)}
    return AnnualProjection(site_result, profiles_result, postal, hashes)
