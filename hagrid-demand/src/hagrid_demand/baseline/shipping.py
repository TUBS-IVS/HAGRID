"""Shipping-day and transit calendar: a parcel ships on a day, travels k delivery days and lands."""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Sequence

import numpy as np
import pandas as pd

from .calendar import calendar_weights


_DATA = Path(__file__).with_name("data") / "temporal_inputs.json"
_SEGMENTS = ("private", "business")
_KEYS = {"mode", "shipping_weekday_weights", "transit_days", "saturday_delivery", "business_saturday_open",
         "week_log_sd", "week_ar", "carrier_week_log_sd", "carrier_day_log_sd", "weekday_concentration",
         "christmas_pull_forward_days", "new_year_spread"}


def load_temporal_inputs() -> dict:
    """Return the documented default profiles, kernel and Saturday opening share."""
    return json.loads(_DATA.read_text(encoding="utf-8"))


def _finite(value: object, label: str) -> float:
    try:
        result = float(value)
    except (TypeError, ValueError) as exc:
        raise ValueError(f"{label} must be a finite number") from exc
    if not np.isfinite(result):
        raise ValueError(f"{label} must be a finite number")
    return result


def _unit(value: object, label: str) -> float:
    result = _finite(value, label)
    if not 0 <= result <= 1:
        raise ValueError(f"{label} must be in [0, 1]")
    return result


def _profile(value: object, label: str) -> np.ndarray:
    result = np.asarray(value, dtype=float)
    if result.shape != (7,) or not np.isfinite(result).all() or (result < 0).any() or result.sum() <= 0:
        raise ValueError(f"{label} must contain seven finite nonnegative values with positive support")
    return result / result.sum()


def _kernel(value: object, label: str) -> np.ndarray:
    result = np.asarray(value, dtype=float)
    if (result.ndim != 1 or len(result) == 0 or not np.isfinite(result).all() or (result < 0).any()
            or abs(result.sum() - 1.) > 1e-9):
        raise ValueError(f"{label} must be nonnegative shares of the 1st, 2nd, ... delivery day summing to 1")
    return result


def _weekly_landing(shipped: int, offset: int) -> int:
    """Weekday (Mon=0) of the offset-th delivery day after *shipped* in a holiday-free week."""
    day = shipped
    for _ in range(offset):
        day = (day + 1) % 7
        if day == 6:
            day = 0
    return day


def derive_shipping_profile(target_delivery: Sequence[float], kernel: Sequence[float],
                            weekend_split: tuple[float, float] = (2 / 3, 1 / 3)) -> np.ndarray:
    """Return the Mon..Sun shipping shares whose expected deliveries reproduce *target_delivery*.

    Saturday and Sunday shipments land on the same delivery days, so their split is not identifiable
    and is fixed by *weekend_split*.
    """
    target = np.asarray(target_delivery, dtype=float)
    if target.shape != (7,) or target[6] != 0 or (target < 0).any() or target[:6].sum() <= 0:
        raise ValueError("target_delivery needs seven nonnegative shares with no Sunday deliveries")
    kernel_values = _kernel(kernel, "kernel")
    matrix = np.zeros((7, 7))
    for shipped in range(7):
        for offset, probability in enumerate(kernel_values, start=1):
            matrix[_weekly_landing(shipped, offset), shipped] += probability
    basis = np.zeros((7, 6))
    basis[:5, :5] = np.eye(5)
    basis[5, 5], basis[6, 5] = weekend_split
    solution = np.linalg.solve(matrix[:6] @ basis, target[:6] / target[:6].sum())
    if (solution < -1e-12).any():
        raise ValueError("target_delivery cannot be reproduced with nonnegative shipping shares")
    profile = basis @ np.clip(solution, 0., None)
    return profile / profile.sum()


def resolve_temporal(cfg: dict | None) -> dict | None:
    """Validate the ``temporal`` block; ``None`` means the legacy delivery-calendar mode."""
    if cfg is None:
        return None
    if not isinstance(cfg, dict):
        raise ValueError("temporal must be a mapping")
    if unknown := set(cfg) - _KEYS:
        raise ValueError(f"unknown temporal keys: {sorted(unknown)}")
    mode = cfg.get("mode", "delivery_calendar")
    if mode == "delivery_calendar":
        return None
    if mode != "shipping_transit":
        raise ValueError("temporal.mode must be delivery_calendar or shipping_transit")
    inputs = load_temporal_inputs()
    transit = cfg.get("transit_days", {})
    if not isinstance(transit, dict):
        raise ValueError("temporal.transit_days must map carriers (or 'default') to shares")
    kernels = {str(name): _kernel(value, f"temporal.transit_days.{name}") for name, value in transit.items()}
    kernels.setdefault("default", _kernel(inputs["transit_days_default"]["values"], "transit_days_default"))
    weights = cfg.get("shipping_weekday_weights", {})
    if not isinstance(weights, dict):
        raise ValueError("temporal.shipping_weekday_weights must map segments to profiles")
    shipping = {}
    for segment in _SEGMENTS:
        value = weights.get(segment, "standard")
        if isinstance(value, str) and value == "standard":
            value = (inputs["shipping_weekday_weights"]["business"]["values"] if segment == "business" else
                     derive_shipping_profile(inputs["delivery_target_private"]["values"], kernels["default"],
                                             tuple(inputs["weekend_split"]["values"])))
        shipping[segment] = _profile(value, f"temporal.shipping_weekday_weights.{segment}")
    saturday_cfg = cfg.get("saturday_delivery", {})
    if not isinstance(saturday_cfg, dict):
        raise ValueError("temporal.saturday_delivery must map carriers (or 'default') to shares")
    saturday = {str(name): _unit(value, f"temporal.saturday_delivery.{name}") for name, value in saturday_cfg.items()}
    saturday.setdefault("default", 1.)
    week_ar = _finite(cfg.get("week_ar", 0.5), "temporal.week_ar")
    if not 0 <= week_ar < 1:
        raise ValueError("temporal.week_ar must be in [0, 1)")
    pull_forward = cfg.get("christmas_pull_forward_days", 14)
    if isinstance(pull_forward, bool) or not isinstance(pull_forward, int) or pull_forward < 0:
        raise ValueError("temporal.christmas_pull_forward_days must be a nonnegative integer")
    if not isinstance(cfg.get("new_year_spread", True), bool):
        raise ValueError("temporal.new_year_spread must be true or false")
    result = {"mode": mode, "shipping": shipping, "kernels": kernels, "saturday": saturday,
              "christmas_pull_forward_days": pull_forward, "new_year_spread": cfg.get("new_year_spread", True),
              "business_saturday_open": _unit(cfg.get("business_saturday_open", inputs["business_saturday_open"]["value"]),
                                              "temporal.business_saturday_open"),
              "week_ar": week_ar}
    for name, default in (("week_log_sd", 0.016), ("carrier_week_log_sd", 0.02), ("carrier_day_log_sd", 0.03)):
        value = _finite(cfg.get(name, default), f"temporal.{name}")
        if value < 0:
            raise ValueError(f"temporal.{name} must be nonnegative")
        result[name] = value
    concentration = _finite(cfg.get("weekday_concentration", 1000.), "temporal.weekday_concentration")
    if concentration <= 0:
        raise ValueError("temporal.weekday_concentration must be positive")
    result["weekday_concentration"] = concentration
    return result


def kernel_for(temporal: dict, carrier: str) -> np.ndarray:
    return temporal["kernels"].get(str(carrier), temporal["kernels"]["default"])


def saturday_accept(temporal: dict, carrier: str, segment: str) -> float:
    """Share of Saturday landings that is delivered on the Saturday (carrier service × recipient opening)."""
    service = temporal["saturday"].get(str(carrier), temporal["saturday"]["default"])
    return service * (temporal["business_saturday_open"] if segment == "business" else 1.)


@dataclass(frozen=True)
class DeliveryCalendar:
    """Delivery days of one year with cyclic successor indices."""

    dates: pd.DatetimeIndex
    delivery: np.ndarray
    weekday: np.ndarray
    next_delivery: np.ndarray
    next_weekday_delivery: np.ndarray


def _successor(mask: np.ndarray) -> np.ndarray:
    """Index of the next day strictly after each day where *mask* holds, wrapping around the year."""
    if not mask.any():
        raise ValueError("the calendar has no delivery day")
    count = len(mask)
    result = np.empty(count, dtype=np.int64)
    upcoming = -1
    for position in range(2 * count - 1, -1, -1):
        day = position % count
        if position < count:
            result[day] = upcoming
        if mask[day]:
            upcoming = day
    return result


def delivery_calendar(year: int, holidays: Iterable[str]) -> DeliveryCalendar:
    """Monday..Saturday are delivery days unless they are public holidays; Sundays never are."""
    dates = pd.date_range(f"{year}-01-01", f"{year}-12-31", freq="D")
    weekday = dates.dayofweek.to_numpy(dtype=np.int64)
    closed = set(pd.to_datetime(list(holidays)).normalize()) if holidays else set()
    holiday = np.asarray([date in closed for date in dates], dtype=bool)
    delivery = (weekday < 6) & ~holiday
    return DeliveryCalendar(dates=dates, delivery=delivery, weekday=weekday, next_delivery=_successor(delivery),
                            next_weekday_delivery=_successor((weekday < 5) & ~holiday))


def landing(cal: DeliveryCalendar, offset: int) -> np.ndarray:
    """Index of the offset-th delivery day strictly after each day."""
    index = np.arange(len(cal.dates))
    for _ in range(offset):
        index = cal.next_delivery[index]
    return index


def expected_delivery(shipping_weights: np.ndarray, cal: DeliveryCalendar, kernel: np.ndarray, accept: float) -> np.ndarray:
    """Deterministic delivered mass per day; Saturday landings not accepted move to the next Mon–Fri delivery day."""
    weights = np.asarray(shipping_weights, dtype=float)
    result = np.zeros(len(cal.dates))
    saturday = cal.weekday == 5
    for offset, probability in enumerate(kernel, start=1):
        target = landing(cal, offset)
        mass = weights * probability
        on_saturday = saturday[target]
        np.add.at(result, target[~on_saturday], mass[~on_saturday])
        np.add.at(result, target[on_saturday], accept * mass[on_saturday])
        np.add.at(result, cal.next_weekday_delivery[target[on_saturday]], (1. - accept) * mass[on_saturday])
    return result


def _move_mass(weights: np.ndarray, source: np.ndarray, target: np.ndarray) -> None:
    """Move the shipping mass of the *source* days onto the *target* days in proportion to their weights."""
    if source.any() and target.any() and weights[target].sum() > 0:
        weights[target] += weights[source].sum() * weights[target] / weights[target].sum()
        weights[source] = 0.


def _season_holidays(weights: np.ndarray, dates: pd.DatetimeIndex, closed: np.ndarray, year: int, temporal: dict) -> None:
    """Christmas orders are placed before Christmas; New Year's orders spread over the rest of its week.

    Shipping mass of 24-26 December moves into the ``christmas_pull_forward_days`` days before 24 December,
    the mass of 1 January onto the open shipping days until the end of its ISO week (the next 7 days if
    1 January is a Sunday). Neither is caught up on the next shipping day.
    """
    open_days = (weights > 0) & ~closed
    days = int(temporal.get("christmas_pull_forward_days", 0))
    if days > 0:
        eve = pd.Timestamp(year=year, month=12, day=24)
        _move_mass(weights, ((dates >= eve) & (dates <= eve + pd.Timedelta(days=2))),
                   open_days & (dates >= eve - pd.Timedelta(days=days)) & (dates < eve))
    if temporal.get("new_year_spread", False):
        new_year = pd.Timestamp(year=year, month=1, day=1)
        end = new_year + pd.Timedelta(days=6 - new_year.dayofweek if new_year.dayofweek < 6 else 7)
        _move_mass(weights, dates == new_year, open_days & (dates > new_year) & (dates <= end))


def shipping_weights(year: int, segment: str, weekly: pd.DataFrame | None, temporal: dict, calendar_cfg: dict) -> np.ndarray:
    """Normalised shipping weight per day: season × shipping weekday profile.

    Nothing ships on a public holiday; the orders of that day ship on the next open shipping day,
    which produces the catch-up peak after holidays.
    """
    cfg = {key: value for key, value in calendar_cfg.items() if key not in {"weekday_weights", "holiday_factor"}}
    cfg["weekday_weights"] = {segment: temporal["shipping"][segment].tolist()}
    cfg["holiday_factor"] = 1.
    frame = calendar_weights(year, segment, weekly, cfg)
    weights = frame.calendar_weight.to_numpy(float).copy()
    holidays = set(pd.to_datetime(list(calendar_cfg.get("holiday_dates", []))).normalize())
    closed = frame.date.isin(holidays).to_numpy()
    _season_holidays(weights, pd.DatetimeIndex(frame.date), closed, year, temporal)
    if closed.any():
        target = _successor((weights > 0) & ~closed)
        for day in np.flatnonzero(closed):
            weights[target[day]] += weights[day]
            weights[day] = 0.
    return weights
