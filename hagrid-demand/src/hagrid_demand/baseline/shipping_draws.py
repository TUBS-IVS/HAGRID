"""Stochastic weekly layer: week and carrier factors, weekday splits and per-parcel transit draws."""

from __future__ import annotations

import numpy as np

from hagrid_demand.common.rng import named_rng

from .shipping import DeliveryCalendar, expected_delivery, kernel_for, landing, saturday_accept


def week_index(cal: DeliveryCalendar) -> np.ndarray:
    """Running week number per day; a new week starts on each Monday, days before the first Monday are week 0."""
    monday = cal.weekday == 0
    return np.cumsum(monday) - (1 if monday[0] else 0)


def ar1_lognormal(count: int, sd: float, rho: float, rng: np.random.Generator) -> np.ndarray:
    """Mean-one log-normal factors whose log follows a stationary AR(1) with correlation *rho*."""
    noise = rng.standard_normal(count)
    latent = np.empty(count)
    if count:
        latent[0] = noise[0]
    scale = np.sqrt(1. - rho ** 2)
    for index in range(1, count):
        latent[index] = rho * latent[index - 1] + scale * noise[index]
    return np.exp(sd * latent - .5 * sd ** 2)


def expected_deliveries(targets: dict[tuple[str, str], float], shipping: dict[str, np.ndarray], cal: DeliveryCalendar,
                        temporal: dict) -> dict[tuple[str, str], np.ndarray]:
    """Expected delivered parcels per day for each (segment, carrier) annual target."""
    return {(segment, carrier): float(target) * expected_delivery(shipping[segment], cal, kernel_for(temporal, carrier),
                                                                  saturday_accept(temporal, carrier, segment))
            for (segment, carrier), target in targets.items()}


def _day_factors(process: dict | None, count: int, rng_keys: dict, segment: str) -> np.ndarray:
    """Optional mean-one daily shocks (process.common_day_log_sd / segment_day_log_sd)."""
    process = process or {}
    common = float(process.get("common_day_log_sd", 0.))
    segments = process.get("segment_day_log_sd", {})
    specific = float(segments.get(segment, 0.)) if isinstance(segments, dict) else float(segments)
    factors = np.ones(count)
    for sd, channel, extra in ((common, "shipping-common-day", {}), (specific, "shipping-segment-day", {"segment": segment})):
        if sd > 0:
            z = named_rng(**rng_keys, **extra, channel=channel).standard_normal(count)
            factors *= np.exp(sd * z - .5 * sd ** 2)
    return factors


def simulate_deliveries(targets: dict[tuple[str, str], float], shipping: dict[str, np.ndarray], cal: DeliveryCalendar,
                        temporal: dict, *, seed: int, year: int, regime: str, outer_id: int = 0, inner_id: int = 0,
                        process: dict | None = None, return_shipments: bool = False):
    """Draw delivered parcels per day and (segment, carrier) from shipping days, transit and the Saturday rule.

    Shipments carry a week factor per segment (AR(1)), a week factor per carrier and a Dirichlet split of
    each week over its shipping days; every parcel then draws its transit offset and, when it lands on a
    Saturday, whether it is delivered that day or on the next Monday–Friday delivery day.
    """
    if regime not in {"expected_annual", "fixed_annual"}:
        raise ValueError("regime must be expected_annual or fixed_annual")
    weeks = week_index(cal)
    week_count = int(weeks.max()) + 1
    days = len(cal.dates)
    saturday = cal.weekday == 5
    keys = {"seed": int(seed), "year": int(year), "outer_id": outer_id, "inner_id": inner_id}
    delivered_all, shipped_all = {}, {}
    for segment in ("private", "business"):
        carriers = sorted(carrier for (item, carrier) in targets if item == segment)
        if not carriers:
            continue
        base = np.asarray(shipping[segment], dtype=float)
        week_factor = ar1_lognormal(week_count, temporal["week_log_sd"], temporal["week_ar"],
                                    named_rng(**keys, segment=segment, channel="shipping-week-factor"))[weeks]
        split = np.zeros(days)
        split_rng = named_rng(**keys, segment=segment, channel="shipping-weekday-split")
        for week in range(week_count):
            members = np.flatnonzero((weeks == week) & (base > 0))
            if len(members) == 0:
                continue
            mass = base[members].sum()
            split[members] = (base[members] if len(members) == 1 else
                              mass * split_rng.dirichlet(temporal["weekday_concentration"] * base[members] / mass))
        day_factor = _day_factors(process, days, keys, segment)
        for carrier in carriers:
            target = float(targets[(segment, carrier)])
            if target <= 0:
                delivered_all[(segment, carrier)] = np.zeros(days, dtype=np.int64)
                shipped_all[(segment, carrier)] = np.zeros(days, dtype=np.int64)
                continue
            carrier_keys = {**keys, "segment": segment, "carrier": carrier}
            carrier_factor = ar1_lognormal(week_count, temporal["carrier_week_log_sd"], 0.,
                                           named_rng(**carrier_keys, channel="shipping-carrier-week"))[weeks]
            mean = target * split * week_factor * carrier_factor * day_factor
            counts_rng = named_rng(**carrier_keys, channel="shipping-counts")
            shipped = (counts_rng.multinomial(int(round(target)), mean / mean.sum()) if regime == "fixed_annual"
                       else counts_rng.poisson(mean)).astype(np.int64)
            kernel = kernel_for(temporal, carrier)
            offsets = named_rng(**carrier_keys, channel="shipping-transit").multinomial(shipped, kernel)
            accept = saturday_accept(temporal, carrier, segment)
            saturday_rng = named_rng(**carrier_keys, channel="shipping-saturday")
            delivered = np.zeros(days, dtype=np.int64)
            for offset in range(len(kernel)):
                destination = landing(cal, offset + 1)
                counts = offsets[:, offset]
                on_saturday = saturday[destination]
                np.add.at(delivered, destination[~on_saturday], counts[~on_saturday])
                stay = counts[on_saturday] if accept >= 1 else saturday_rng.binomial(counts[on_saturday], accept)
                np.add.at(delivered, destination[on_saturday], stay)
                np.add.at(delivered, cal.next_weekday_delivery[destination[on_saturday]], counts[on_saturday] - stay)
            delivered_all[(segment, carrier)] = delivered
            shipped_all[(segment, carrier)] = shipped
    return (delivered_all, shipped_all) if return_shipments else delivered_all
