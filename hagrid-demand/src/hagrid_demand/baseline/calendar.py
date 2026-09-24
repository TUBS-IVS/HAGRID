"""Deterministic, normalised daily calendar factors for annual demand totals."""

from __future__ import annotations

import datetime as dt

import numpy as np
import pandas as pd


# Weekday shares Monday..Sunday from ParcelDemandScenarioGenerator
# (``get_daily_relative_change``); the notebook has no Sunday deliveries.
DEFAULT_WEEKDAY_WEIGHTS = [0.16, 0.17, 0.19, 0.18, 0.15, 0.115, 0.0]
_HOLIDAY_REGIONS = {"NI"}


def _easter_sunday(year: int) -> dt.date:
    """Gregorian Easter Sunday (anonymous Gregorian algorithm)."""
    a, b, c = year % 19, year // 100, year % 100
    d, e = divmod(b, 4)
    f = (b + 8) // 25
    g = (b - f + 1) // 3
    h = (19 * a + b - d - g + 15) % 30
    i, k = divmod(c, 4)
    l = (32 + 2 * e + 2 * i - h - k) % 7
    m = (a + 11 * h + 22 * l) // 451
    month, day = divmod(h + l - 7 * m + 114, 31)
    return dt.date(year, month, day + 1)


def public_holidays(year: int, region: str | None) -> list[str]:
    """Return the statutory public holidays of *region* as sorted ISO dates."""
    if region is None:
        return []
    if region not in _HOLIDAY_REGIONS:
        raise ValueError(f"unsupported holiday region {region!r}; supported: {sorted(_HOLIDAY_REGIONS)}")
    easter = _easter_sunday(year)
    days = {dt.date(year, 1, 1), easter - dt.timedelta(days=2), easter + dt.timedelta(days=1),
            dt.date(year, 5, 1), easter + dt.timedelta(days=39), easter + dt.timedelta(days=50),
            dt.date(year, 10, 3), dt.date(year, 12, 25), dt.date(year, 12, 26)}
    if year >= 2018:  # Reformationstag is a permanent public holiday in Lower Saxony since 2018.
        days.add(dt.date(year, 10, 31))
    return [day.isoformat() for day in sorted(days)]


def _weights(value: object, count: int, label: str) -> np.ndarray:
    if isinstance(value, dict):
        try:
            value = [value[index] if index in value else value[str(index)] for index in range(count)]
        except KeyError as exc:
            raise ValueError(f"{label} requires {count} ordered values") from exc
    result = np.asarray(value, dtype=float)
    if result.shape != (count,) or not np.isfinite(result).all() or (result < 0).any() or result.sum() <= 0:
        raise ValueError(f"{label} must contain finite nonnegative values with positive support")
    return result


def _weekday_weights(segment: str, cfg: dict) -> np.ndarray:
    source = cfg.get("weekday_weights")
    if not isinstance(source, dict):
        raise ValueError("weekday_weights must map segments to seven values")
    values = source.get(segment, source.get(str(segment).lower()))
    if values is None:
        raise ValueError(f"weekday_weights has no profile for segment {segment!r}")
    return _weights(values, 7, "weekday_weights")


def _weekly_factors(weekly: pd.DataFrame | None) -> np.ndarray | None:
    if weekly is None:
        return None
    if not isinstance(weekly, pd.DataFrame) or not {"week", "weight"}.issubset(weekly.columns):
        raise ValueError("weekly requires week and weight columns")
    profile = weekly.loc[:, ["week", "weight"]].copy()
    profile["week"] = pd.to_numeric(profile.week, errors="coerce")
    profile["weight"] = pd.to_numeric(profile.weight, errors="coerce")
    if profile.week.isna().any() or profile.weight.isna().any():
        raise ValueError("weekly profile must be numeric")
    profile["week"] = profile.week.astype(int)
    if profile.week.duplicated().any() or set(profile.week) != set(range(1, 53)):
        raise ValueError("weekly profile must contain ISO weeks 1 through 52 once")
    factors = profile.set_index("week").loc[range(1, 53), "weight"].to_numpy(float)
    if not np.isfinite(factors).all() or (factors < 0).any() or factors.sum() <= 0:
        raise ValueError("weekly profile must have finite nonnegative positive support")
    return factors


def _holiday_dates(value: object) -> set[dt.date]:
    if value is None:
        return set()
    if not isinstance(value, (list, tuple, set)):
        raise ValueError("holiday_dates must be ISO date strings")
    try:
        return {dt.date.fromisoformat(str(item)) for item in value}
    except ValueError as exc:
        raise ValueError("holiday_dates must be ISO date strings") from exc


def calendar_weights(year: int, segment: str, weekly: pd.DataFrame | None, cfg: dict) -> pd.DataFrame:
    """Return one normalised calendar weight per local date in *year*.

    A complete ISO-week profile and a non-neutral monthly profile would express
    seasonality twice, so the two are deliberately mutually exclusive.
    """
    if type(year) is not int or year < 2021:
        raise ValueError("year must be a valid integer from 2021")
    if not isinstance(cfg, dict):
        raise ValueError("cfg must be a mapping")
    weekdays = _weekday_weights(segment, cfg)
    monthly = _weights(cfg.get("monthly_weights", [1.] * 12), 12, "monthly_weights")
    weekly_factors = _weekly_factors(weekly)
    if weekly_factors is not None and not np.allclose(monthly, monthly[0], atol=0., rtol=0.):
        raise ValueError("weekly and non-neutral monthly seasonality are mutually exclusive")
    holidays = _holiday_dates(cfg.get("holiday_dates", []))
    factor = cfg.get("holiday_factor", 1.)
    try:
        holiday_factor = float(factor)
    except (TypeError, ValueError) as exc:
        raise ValueError("holiday_factor must be finite and nonnegative") from exc
    if not np.isfinite(holiday_factor) or holiday_factor < 0:
        raise ValueError("holiday_factor must be finite and nonnegative")
    strength_values = cfg.get("seasonality_strength", {})
    if not isinstance(strength_values, dict):
        raise ValueError("seasonality_strength must map segments to a number")
    try:
        strength = float(strength_values.get(segment, strength_values.get(str(segment).lower(), 1.)))
    except (TypeError, ValueError) as exc:
        raise ValueError("seasonality_strength must be finite in [0, 2]") from exc
    if not np.isfinite(strength) or not 0 <= strength <= 2:
        raise ValueError("seasonality_strength must be finite in [0, 2]")

    dates = pd.date_range(dt.date(year, 1, 1), dt.date(year, 12, 31), freq="D")
    iso = dates.isocalendar()
    iso_week = iso.week.to_numpy(dtype=int)
    if weekly_factors is None:
        season = monthly[dates.month.to_numpy(dtype=int) - 1]
    else:
        season = np.empty(len(dates), dtype=float)
        standard = iso_week <= 52
        season[standard] = weekly_factors[iso_week[standard] - 1]
        season[~standard] = (weekly_factors[51] + weekly_factors[0]) / 2.
    season = np.power(season, strength)
    weekday = weekdays[dates.dayofweek.to_numpy(dtype=int)]
    holiday = np.asarray([holiday_factor if date.date() in holidays else 1. for date in dates], dtype=float)
    raw = season * weekday * holiday
    if not np.isfinite(raw).all() or (raw < 0).any() or raw.sum() <= 0:
        raise ValueError("Invalid calendar support")
    return pd.DataFrame({
        "date": dates, "year": year, "segment": str(segment), "iso_year": iso.year.to_numpy(dtype=int),
        "iso_week": iso_week, "weekday": dates.dayofweek.to_numpy(dtype=int), "season_factor": season,
        "weekday_factor": weekday, "holiday_factor": holiday, "calendar_weight": raw / raw.sum(),
    })
