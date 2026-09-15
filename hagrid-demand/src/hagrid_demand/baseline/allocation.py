"""Exact annual count regimes and stochastic site/provider allocation."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Iterator

import numpy as np
import pandas as pd

from hagrid_demand.common.cache import dependency_snapshot, resolve_stage, stage_key
from hagrid_demand.common.contracts import SpatialPlan
from hagrid_demand.common.provenance import canonical_digest
from hagrid_demand.common.rng import RNG_VERSION, named_rng


_SEGMENTS = ("private", "business")


def _segment_order(value: object) -> int:
    text = str(value).lower()
    return _SEGMENTS.index(text) if text in _SEGMENTS else len(_SEGMENTS)


def _number(value: object, label: str, *, nonnegative: bool = True) -> float:
    try:
        result = float(value)
    except (TypeError, ValueError) as exc:
        raise ValueError(f"{label} must be a finite number") from exc
    if not np.isfinite(result) or (nonnegative and result < 0):
        raise ValueError(f"{label} must be a finite {'nonnegative ' if nonnegative else ''}number")
    return result


def _normalised(values: np.ndarray, label: str) -> np.ndarray:
    values = np.asarray(values, dtype=float)
    if values.ndim != 1 or not np.isfinite(values).all() or (values < 0).any() or values.sum() <= 0:
        raise ValueError(f"{label} must have finite nonnegative positive support")
    return values / values.sum()


def _calendar_frame(value: object, segment: str) -> pd.DataFrame:
    if not isinstance(value, pd.DataFrame) or not {"date", "calendar_weight"}.issubset(value.columns):
        raise ValueError(f"calendar for {segment} requires date and calendar_weight columns")
    result = value.loc[:, ["date", "calendar_weight"]].copy()
    result["date"] = pd.to_datetime(result.date, errors="coerce")
    result["calendar_weight"] = pd.to_numeric(result.calendar_weight, errors="coerce")
    if result.date.isna().any() or result.date.duplicated().any():
        raise ValueError(f"calendar for {segment} must contain unique valid dates")
    _normalised(result.calendar_weight.to_numpy(float), f"calendar for {segment}")
    return result.sort_values("date", kind="stable").reset_index(drop=True)


def _shock_values(value: object, dates: pd.Series, segment: str) -> np.ndarray:
    if isinstance(value, pd.Series):
        values = value.to_numpy(dtype=float)
    elif isinstance(value, pd.DataFrame):
        if not {"date", "shock"}.issubset(value.columns):
            raise ValueError(f"shocks for {segment} requires date and shock columns")
        mapped = value.copy()
        mapped["date"] = pd.to_datetime(mapped.date, errors="coerce")
        if mapped.date.isna().any() or mapped.date.duplicated().any():
            raise ValueError(f"shocks for {segment} must have unique valid dates")
        values = dates.map(mapped.set_index("date").shock).to_numpy(dtype=float)
    else:
        values = np.asarray(value, dtype=float)
    if values.shape != (len(dates),) or not np.isfinite(values).all() or (values < 0).any():
        raise ValueError(f"shocks for {segment} must be finite nonnegative daily values")
    return values


def _largest_remainder(annual_total: float, b2b: float) -> dict[str, int]:
    total = _number(annual_total, "annual_total")
    business_share = _number(b2b, "b2b")
    if business_share > 1:
        raise ValueError("b2b must be in [0, 1]")
    expected = np.array([total * (1 - business_share), total * business_share], dtype=float)
    integer = np.floor(expected).astype(int)
    remaining = int(round(total)) - int(integer.sum())
    # Descending remainders, then the documented stable segment order.
    for index in sorted(range(len(_SEGMENTS)), key=lambda item: (-float(expected[item] - integer[item]), item))[:remaining]:
        integer[index] += 1
    return dict(zip(_SEGMENTS, map(int, integer), strict=True))


def annual_day_counts(annual_total: float, b2b: float, calendars: dict, shocks: dict, seed: int,
                      outer_id: int, inner_id: int, year: int, regime: str) -> pd.DataFrame:
    """Draw complete-year segment/day counts under the requested quantity regime."""
    if regime not in {"fixed_annual", "expected_annual"}:
        raise ValueError("regime must be fixed_annual or expected_annual")
    if type(year) is not int or year < 2021:
        raise ValueError("year must be an integer from 2021")
    if not isinstance(calendars, dict) or not isinstance(shocks, dict):
        raise ValueError("calendars and shocks must be mappings")
    totals = _largest_remainder(annual_total, b2b)
    records = []
    for segment in _SEGMENTS:
        frame = _calendar_frame(calendars.get(segment), segment)
        factors = _shock_values(shocks.get(segment), frame.date, segment)
        weights = frame.calendar_weight.to_numpy(float)
        if regime == "fixed_annual":
            weighted = weights * factors
            # A zero annual segment is explicit even when a caller supplies an
            # all-zero shock path; its otherwise irrelevant day probabilities
            # must not turn that zero-support case into an error.
            probabilities = (_normalised(weighted, f"fixed annual day weights for {segment}")
                             if weighted.sum() > 0 else _normalised(weights, f"calendar for {segment}"))
            if weighted.sum() <= 0 and totals[segment] > 0:
                raise ValueError(f"fixed annual day weights for {segment} must have positive support")
            rng = named_rng(seed, year=year, outer_id=outer_id, inner_id=inner_id,
                            segment=segment, channel="annual-day-counts")
            counts = rng.multinomial(totals[segment], probabilities)
        else:
            counts = np.asarray([
                named_rng(seed, year=year, outer_id=outer_id, inner_id=inner_id, segment=segment,
                          date=date.date().isoformat(), channel="expected-day-count").poisson(
                              annual_total * (b2b if segment == "business" else 1 - b2b) * weight * factor
                          )
                for date, weight, factor in zip(frame.date, weights, factors, strict=True)
            ], dtype=np.int64)
        records.append(pd.DataFrame({"date": frame.date, "year": year, "segment": segment,
                                     "calendar_weight": weights, "shock": factors, "count": counts}))
    result = pd.concat(records, ignore_index=True)
    result["_segment_order"] = result.segment.map(_segment_order)
    return result.sort_values(["date", "_segment_order"], kind="stable").drop(columns="_segment_order").reset_index(drop=True)


def spatial_dirichlet(weights: np.ndarray, plz: np.ndarray, site_ids: np.ndarray, between: float,
                      within: float, rng: np.random.Generator) -> np.ndarray:
    """Draw site shares with separate postal and within-postal concentrations."""
    values = np.asarray(weights, dtype=float)
    postal = np.asarray(plz, dtype=object)
    sites = np.asarray(site_ids, dtype=object)
    if values.ndim != 1 or postal.ndim != 1 or sites.ndim != 1 or not (len(values) == len(postal) == len(sites)):
        raise ValueError("weights, plz, and site_ids must be one-dimensional arrays of equal length")
    if not isinstance(rng, np.random.Generator):
        raise ValueError("rng must be a numpy Generator")
    between_value = _number(between, "between", nonnegative=False)
    within_value = _number(within, "within", nonnegative=False)
    if between_value <= 0 or within_value <= 0:
        raise ValueError("between and within must be positive Dirichlet concentrations")
    if not np.isfinite(values).all() or (values < 0).any() or any(not str(value).strip() for value in postal) or any(not str(value).strip() for value in sites):
        raise ValueError("spatial inputs require finite nonnegative weights and non-empty identifiers")
    result = np.zeros(len(values), dtype=float)
    active = values > 0
    if not active.any():
        raise ValueError("spatial weights require positive support")
    active_indices = np.flatnonzero(active)
    order = sorted(active_indices.tolist(), key=lambda index: (str(postal[index]), str(sites[index])))
    sorted_indices = np.asarray(order, dtype=int)
    sorted_values = values[sorted_indices]
    sorted_postal = np.asarray([str(postal[index]) for index in sorted_indices], dtype=object)
    groups = sorted(set(sorted_postal.tolist()))
    postal_weights = np.asarray([sorted_values[sorted_postal == group].sum() for group in groups], dtype=float)
    postal_shares = rng.dirichlet(between_value * _normalised(postal_weights, "postal weights"))
    for group, postal_share in zip(groups, postal_shares, strict=True):
        group_indices = sorted_indices[sorted_postal == group]
        local = _normalised(values[group_indices], f"site weights in postal {group}")
        local_share = np.ones(1, dtype=float) if len(group_indices) == 1 else rng.dirichlet(within_value * local)
        result[group_indices] = postal_share * local_share
    return result


def _frame_digest(frame: pd.DataFrame) -> str:
    normalized = frame.copy()
    for column in normalized.columns:
        if pd.api.types.is_datetime64_any_dtype(normalized[column]):
            normalized[column] = normalized[column].dt.strftime("%Y-%m-%d")
    return canonical_digest({"columns": normalized.columns.tolist(), "rows": normalized.to_dict(orient="records")})


def _target_fingerprints(annual: pd.DataFrame) -> dict[str, str]:
    values = {}
    for (year, segment), rows in annual.groupby(["year", "segment"], sort=True):
        ordered = rows.loc[:, ["site_id", "plz", "annual_expected"]].copy()
        ordered["site_id"] = ordered.site_id.astype(str)
        ordered["plz"] = ordered.plz.astype(str)
        ordered = ordered.sort_values(["plz", "site_id"], kind="stable").reset_index(drop=True)
        values[f"{int(year)}:{segment}"] = _frame_digest(ordered)
    return values


def _spatial_parameters(cfg: dict) -> dict:
    raw = cfg.get("spatial", cfg.get("spatial_dirichlet", {}))
    if not isinstance(raw, dict):
        raise ValueError("spatial configuration must be a mapping")
    return raw


def make_dirichlet_plan(annual: pd.DataFrame, cfg: dict) -> SpatialPlan:
    """Create a non-calibrated plan bound to its annual target distribution."""
    _annual_frame(annual)
    spatial = _spatial_parameters(cfg)
    return SpatialPlan(mode="dirichlet", target_fingerprints=_target_fingerprints(annual),
                       parameter_fingerprint=canonical_digest({"spatial": spatial}), status="complete")


def _annual_frame(value: object) -> pd.DataFrame:
    if not isinstance(value, pd.DataFrame):
        raise ValueError("annual must be a DataFrame")
    required = {"year", "site_id", "plz", "segment", "annual_expected", "allocation_status"}
    if missing := required.difference(value.columns):
        raise ValueError(f"annual missing columns: {sorted(missing)}")
    result = value.copy()
    result["year"] = pd.to_numeric(result.year, errors="coerce")
    result["annual_expected"] = pd.to_numeric(result.annual_expected, errors="coerce")
    result["segment"] = result.segment.astype(str).str.lower()
    if (result.year.isna().any() or (result.year % 1 != 0).any() or result.year.lt(2021).any()
            or result.annual_expected.isna().any() or not np.isfinite(result.annual_expected).all()
            or result.annual_expected.lt(0).any() or ~result.segment.isin(_SEGMENTS).all()
            or result.site_id.isna().any() or result.plz.isna().any()
            or result.duplicated(["year", "segment", "site_id"]).any()):
        raise ValueError("annual requires valid unique nonnegative site targets")
    result["year"] = result.year.astype(int)
    result["site_id"] = result.site_id.astype(str)
    result["plz"] = result.plz.astype(str)
    return result.sort_values(["year", "segment", "plz", "site_id"], key=lambda series: series.map(_segment_order) if series.name == "segment" else series,
                              kind="stable").reset_index(drop=True)


def _calendar_year(value: pd.DataFrame, year: int, segment: str) -> pd.DataFrame:
    if not isinstance(value, pd.DataFrame) or not {"date", "segment", "calendar_weight"}.issubset(value.columns):
        raise ValueError("calendar requires date, segment, and calendar_weight columns")
    dates = pd.to_datetime(value.date, errors="coerce")
    rows = value.loc[(dates.dt.year == year) & value.segment.astype(str).str.lower().eq(segment)].copy()
    rows["date"] = pd.to_datetime(rows.date)
    result = _calendar_frame(rows, segment)
    full_year = pd.date_range(f"{year}-01-01", f"{year}-12-31", freq="D")
    if not result.date.reset_index(drop=True).equals(pd.Series(full_year)):
        raise ValueError(f"calendar must contain every date in {year} for {segment}")
    return result


def _process_shocks(calendar: dict[str, pd.DataFrame], cfg: dict, *, year: int, outer_id: int, inner_id: int,
                    coupling_id: str | None) -> dict[str, np.ndarray]:
    process = cfg.get("process", {})
    if not isinstance(process, dict):
        raise ValueError("process configuration must be a mapping")
    common_sd = _number(process.get("common_day_log_sd", cfg.get("common_day_log_sd", 0.)), "common_day_log_sd")
    segment_sds = process.get("segment_day_log_sd", cfg.get("segment_day_log_sd", {}))
    if isinstance(segment_sds, (int, float)):
        segment_sds = {segment: segment_sds for segment in _SEGMENTS}
    if not isinstance(segment_sds, dict):
        raise ValueError("segment_day_log_sd must be a mapping")
    seed = cfg.get("seed")
    if isinstance(seed, bool) or not isinstance(seed, (int, np.integer)):
        raise ValueError("cfg.seed must be an integer")
    effective_inner = coupling_id if coupling_id is not None else inner_id
    result = {segment: [] for segment in _SEGMENTS}
    # Calendars have been verified to have the same complete date index.
    dates = calendar["private"].date
    for date in dates:
        day = date.date().isoformat()
        common_z = named_rng(int(seed), year=year, outer_id=outer_id, inner_id=effective_inner,
                             date=day, channel="common-day-factor").normal()
        common = np.exp(common_sd * common_z - .5 * common_sd ** 2)
        for segment in _SEGMENTS:
            segment_sd = _number(segment_sds.get(segment, segment_sds.get(str(segment).lower(), 0.)),
                                 f"segment_day_log_sd.{segment}")
            z = named_rng(int(seed), year=year, outer_id=outer_id, inner_id=effective_inner, segment=segment,
                          date=day, channel="segment-day-factor").normal()
            result[segment].append(common * np.exp(segment_sd * z - .5 * segment_sd ** 2))
    return {segment: np.asarray(values, dtype=float) for segment, values in result.items()}


def _concentration(spatial: dict, name: str, segment: str) -> float:
    value = spatial.get(name, spatial.get(f"{name}_concentration", 1.))
    if isinstance(value, dict):
        value = value.get(segment, value.get(str(segment).lower(), 1.))
    result = _number(value, f"spatial.{name}", nonnegative=False)
    if result <= 0:
        raise ValueError(f"spatial.{name} must be positive")
    return result


def _complete_counts(annual: pd.DataFrame, calendar: dict[str, pd.DataFrame], shocks: dict[str, np.ndarray], cfg: dict,
                     outer_id: int, inner_id: int, year: int, cache_dir: Path, coupling_id: str | None) -> tuple[pd.DataFrame, dict]:
    expected = annual.groupby("segment").annual_expected.sum().reindex(_SEGMENTS, fill_value=0.)
    total = float(expected.sum())
    b2b = 0. if total == 0 else float(expected["business"] / total)
    regime = cfg.get("regime", cfg.get("quantity_regime", "expected_annual"))
    if regime not in {"fixed_annual", "expected_annual"}:
        raise ValueError("regime must be fixed_annual or expected_annual")
    calendar_payload = {segment: calendar[segment].assign(date=calendar[segment].date.dt.strftime("%Y-%m-%d")).to_dict(orient="records")
                        for segment in _SEGMENTS}
    dependencies = {"annual_targets": expected.to_dict(), "calendar": calendar_payload,
                    "shocks": {segment: shocks[segment].tolist() for segment in _SEGMENTS},
                    "year": year, "outer_id": outer_id, "inner_id": inner_id, "coupling_id": coupling_id}
    snapshot = dependency_snapshot(dependencies)
    cache_config = {"schema_version": 1, "rng_version": RNG_VERSION, "seed": int(cfg["seed"]), "regime": regime}
    fingerprint = stage_key("annual_day_counts", dependencies, cache_config, {"allocation": Path(__file__)},
                            dependency_snapshot=snapshot)
    root = Path(cache_dir)
    run_id = canonical_digest({"fingerprint": fingerprint})[:16]
    run_dir = root / "daily-count-runs" / run_id
    totals = _largest_remainder(total, b2b)

    def build(output: Path) -> None:
        counts = annual_day_counts(total, b2b, calendar, shocks, int(cfg["seed"]), outer_id,
                                   coupling_id if coupling_id is not None else inner_id, year, regime)
        counts.to_parquet(output / "annual_day_counts.parquet", index=False)
        (output / "status.json").write_text(json.dumps({
            "quantity_regime": regime, "regime": regime,
            "complete_calendar_year": year, "complete_calendar_years": [year], "calendar_years": [year],
            "rounded_annual_total": int(round(total)), "rounded_segment_totals": totals,
            "selected_dates_are_filter_only": True,
        }, sort_keys=True), encoding="utf-8")

    def validate(output: Path) -> None:
        counts = pd.read_parquet(output / "annual_day_counts.parquet")
        if set(counts.columns) != {"date", "year", "segment", "calendar_weight", "shock", "count"}:
            raise ValueError("annual count artifact has invalid columns")
        if regime == "fixed_annual" and counts.groupby("segment")["count"].sum().to_dict() != totals:
            raise ValueError("fixed annual count artifact does not conserve segment totals")
        if json.loads((output / "status.json").read_text(encoding="utf-8")).get("selected_dates_are_filter_only") is not True:
            raise ValueError("annual count artifact lacks filter-only status")

    path = resolve_stage(run_dir, "annual_day_counts", fingerprint, cache_root=root, dependencies=dependencies,
                         build=build, validate=validate, dependency_snapshot=snapshot)
    return pd.read_parquet(path / "annual_day_counts.parquet"), json.loads((path / "status.json").read_text(encoding="utf-8"))


def _validate_plan(plan: SpatialPlan, annual: pd.DataFrame, cfg: dict) -> None:
    if not isinstance(plan, SpatialPlan) or plan.mode != "dirichlet" or plan.status != "complete":
        raise ValueError("generate_days requires a complete dirichlet SpatialPlan")
    if plan.target_fingerprints != _target_fingerprints(annual):
        raise ValueError("SpatialPlan target fingerprints do not match annual inputs")
    if plan.parameter_fingerprint != canonical_digest({"spatial": _spatial_parameters(cfg)}):
        raise ValueError("SpatialPlan parameter fingerprint does not match configuration")


def generate_days(annual: pd.DataFrame, profiles: pd.DataFrame, calendar: pd.DataFrame, cfg: dict,
                  outer_id: int, inner_id: int, *, spatial_plan: SpatialPlan, cache_dir: Path,
                  coupling_id: str | None = None) -> Iterator[pd.DataFrame]:
    """Yield one complete site/provider detail chunk per selected day.

    The cached annual count draw is always built from every calendar date.  A
    ``cfg['dates']`` selection only filters these already-realised chunks.
    """
    annual_frame = _annual_frame(annual)
    if not isinstance(profiles, pd.DataFrame) or not {"year", "segment", "carrier", "share"}.issubset(profiles.columns):
        raise ValueError("profiles requires year, segment, carrier, and share columns")
    if not isinstance(cfg, dict):
        raise ValueError("cfg must be a mapping")
    _validate_plan(spatial_plan, annual_frame, cfg)
    selected = cfg.get("dates")
    if selected is None:
        selected_dates = None
    elif isinstance(selected, (list, tuple, set)):
        selected_dates = set(pd.to_datetime(list(selected), errors="raise").normalize())
    else:
        raise ValueError("dates must be a collection of ISO dates or null")
    spatial = _spatial_parameters(cfg)
    for year in sorted(annual_frame.year.unique().tolist()):
        annual_year = annual_frame.loc[annual_frame.year.eq(year)].copy()
        calendars = {segment: _calendar_year(calendar, year, segment) for segment in _SEGMENTS}
        if not calendars["private"].date.equals(calendars["business"].date):
            raise ValueError("all segment calendars must use the same complete dates")
        shocks = _process_shocks(calendars, cfg, year=year, outer_id=outer_id, inner_id=inner_id,
                                 coupling_id=coupling_id)
        counts, _ = _complete_counts(annual_year, calendars, shocks, cfg, outer_id, inner_id, year,
                                     Path(cache_dir), coupling_id)
        profiles_year = profiles.copy()
        profiles_year["year"] = pd.to_numeric(profiles_year.year, errors="coerce")
        profiles_year["segment"] = profiles_year.segment.astype(str).str.lower()
        profiles_year["carrier"] = profiles_year.carrier.astype(str)
        profiles_year["share"] = pd.to_numeric(profiles_year.share, errors="coerce")
        if profiles_year.year.isna().any() or profiles_year.share.isna().any() or not np.isfinite(profiles_year.share).all() or profiles_year.share.lt(0).any():
            raise ValueError("profiles require finite nonnegative shares")
        profiles_year = profiles_year.loc[profiles_year.year.eq(year)]
        rows_by_date: dict[pd.Timestamp, list[pd.DataFrame]] = {}
        for segment in _SEGMENTS:
            sites = annual_year.loc[annual_year.segment.eq(segment)].sort_values(["plz", "site_id"], kind="stable").reset_index(drop=True)
            carriers = profiles_year.loc[profiles_year.segment.eq(segment), ["carrier", "share"]].sort_values("carrier", kind="stable")
            if carriers.empty:
                raise ValueError(f"profiles have no carriers for {year}/{segment}")
            if carriers.carrier.duplicated().any():
                raise ValueError(f"profiles have duplicate carriers for {year}/{segment}")
            carrier_share = _normalised(carriers.share.to_numpy(float), f"carrier shares for {year}/{segment}")
            site_weights = sites.annual_expected.to_numpy(float)
            positive_support = site_weights.sum() > 0
            counts_segment = counts.loc[counts.segment.eq(segment)].set_index("date")
            calendar_segment = calendars[segment].set_index("date")
            for date, row in counts_segment.iterrows():
                daily_count = int(row["count"])
                if positive_support:
                    share_rng = named_rng(int(cfg["seed"]), year=year, outer_id=outer_id,
                                          inner_id=coupling_id if coupling_id is not None else inner_id,
                                          segment=segment, date=date.date().isoformat(), channel="spatial-dirichlet")
                    shares = spatial_dirichlet(site_weights, sites.plz.to_numpy(), sites.site_id.to_numpy(),
                                               _concentration(spatial, "between", segment), _concentration(spatial, "within", segment), share_rng)
                    allocation_rng = named_rng(int(cfg["seed"]), year=year, outer_id=outer_id,
                                               inner_id=coupling_id if coupling_id is not None else inner_id,
                                               segment=segment, date=date.date().isoformat(), channel="site-counts")
                    site_counts = allocation_rng.multinomial(daily_count, shares)
                else:
                    if daily_count:
                        raise ValueError(f"positive daily {segment} count has no site support")
                    shares = np.zeros(len(sites), dtype=float)
                    site_counts = np.zeros(len(sites), dtype=int)
                detail_rows = []
                for index, site in sites.iterrows():
                    carrier_rng = named_rng(int(cfg["seed"]), year=year, outer_id=outer_id,
                                            inner_id=coupling_id if coupling_id is not None else inner_id,
                                            segment=segment, site_id=site.site_id, date=date.date().isoformat(),
                                            channel="carrier-counts")
                    assigned = carrier_rng.multinomial(int(site_counts[index]), carrier_share)
                    detail_rows.append(pd.DataFrame({
                        "date": date, "year": year, "outer_id": outer_id, "inner_id": inner_id,
                        "site_id": site.site_id, "plz": site.plz, "segment": segment,
                        "carrier": carriers.carrier.to_numpy(), "allocation_status": site.allocation_status,
                        "baseline_expected": float(site.annual_expected) * float(calendar_segment.at[date, "calendar_weight"]) * carrier_share,
                        "conditional_expected": float(daily_count) * float(shares[index]) * carrier_share,
                        "daily_count": daily_count, "count": assigned,
                    }))
                rows_by_date.setdefault(date, []).append(pd.concat(detail_rows, ignore_index=True))
        for date in sorted(rows_by_date):
            if selected_dates is None or date.normalize() in selected_dates:
                frame = pd.concat(rows_by_date[date], ignore_index=True)
                frame["_segment_order"] = frame.segment.map(_segment_order)
                yield frame.sort_values(["date", "_segment_order", "plz", "site_id", "carrier"], kind="stable").drop(columns="_segment_order").reset_index(drop=True)
