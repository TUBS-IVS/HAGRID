# -*- coding: utf-8 -*-
"""Shared-Use KPI rows: channel/delta stats from shareduse_channel_stats.csv
(1c Task 7) plus pax-only corrected passenger KPIs. The stock DRT aggregates
(drt_customer_stats, extract_drt.py) mix parcel_ persons into their
rides/wait numbers on a DRT_SHAREDUSE run, since parcels ride as DVRP
passengers too -- this module re-derives the pax-only numbers straight from
the per-leg CSV instead (D10 of the 1c plan).

Classifier (D10(b)): PARCEL_PREFIX mirrors the Java single source of truth
hagrid.integrated.shareduse.SharedUse.PARCEL_PERSON_PREFIX exactly. MATSim's
own DRT-legs analysis writer does not serialize a per-leg Goods-load column,
so the personId-prefix predicate is the only classifier signal available
here (the Java side additionally carries the dvrp:load attribute on the
Population -- that is where SharedUseKpiHandler sources its LOAD-weighted
parcel counts, already pre-aggregated for us in shareduse_channel_stats.csv).

Best-effort D10(c) fare split: output_drt_legs_drt.csv already carries a
native `fareForLeg` column (MATSim's own per-leg DRT-fare output, populated
by PtAndDrtFareModule at fare-computation time when fares are configured for
the run; 0.0 per leg otherwise). Summing it split by the same parcel/pax
predicate is equivalent to summing the underlying PersonMoney(DRT-fare)
events without opening the (large, gzipped) events file -- 1e has no
existing personMoney reader (extract_drt.py/economics.py do not touch fares
at all), so this leg-column sum is the only readily-available source and is
emitted here, guarded by the column's presence. economic/parcel_fare_revenue
is the PPC-enabling field (1c Task 8 brief); PPC itself is a deferred
post-hoc computation on kpis_long.csv, not built here.

D10(a) system/freight_veh_km is deliberately NOT emitted (deferred, see the
Task 8 report): the legs CSV's travelDistance_m is a per-PERSON trip
distance (pickup to dropoff), not a vehicle-km. When a pax and a parcel are
pooled aboard the same vehicle for an overlapping segment -- the entire
point of shared-use -- each gets its own leg row with its own
travelDistance_m, so naively summing parcel-leg distances would double-count
vehicle-km already attributed to the pax side whenever they overlap. A
correct decomposition needs per-link, per-identity occupancy reconstruction
(which link segments had a parcel aboard, not just how many riders);
geometry.reconstruct_drt_paths only tracks an occupancy COUNT per link, not
rider identity, so extending it is exactly the kind of new events-parsing
project the task brief says to avoid here.
"""
from pathlib import Path

import pandas as pd

from common import row

PARCEL_PREFIX = "parcel_"  # hagrid.integrated.shareduse.SharedUse.PARCEL_PERSON_PREFIX


def has_shareduse_stats(run_dir, meta):
    return (Path(run_dir) / "shareduse_channel_stats.csv").exists()


def extract(run_dir, prefix):
    run_dir = Path(run_dir)
    rows = []

    stats = dict(pd.read_csv(run_dir / "shareduse_channel_stats.csv", sep=";").values)
    rows += [
        row("channel", "undelivered_rate", float(stats["undelivered_rate"]), "share", "shareduse_channel_stats"),
        row("channel", "share_channel_door", float(stats["share_channel_door"]), "share", "shareduse_channel_stats"),
        row("channel", "share_channel_locker", float(stats["share_channel_locker"]), "share", "shareduse_channel_stats"),
        row("freight", "parcels_submitted", int(stats["parcels_submitted"]), "parcels", "shareduse_channel_stats"),
        row("freight", "parcels_delivered", int(stats["parcels_delivered"]), "parcels", "shareduse_channel_stats"),
        row("freight", "parcels_undelivered", int(stats["parcels_undelivered"]), "parcels", "shareduse_channel_stats"),
        row("freight", "mean_delivery_delay_s", float(stats["mean_delivery_delay_s"]), "s", "shareduse_channel_stats"),
    ]

    legs_f = run_dir / (prefix + ".output_drt_legs_drt.csv")
    if legs_f.exists():
        legs = pd.read_csv(legs_f, sep=";")
        is_parcel = legs["personId"].astype(str).str.startswith(PARCEL_PREFIX)
        pax = legs[~is_parcel]
        parcel = legs[is_parcel]

        rows.append(row("passenger", "drt_rides_pax_only", int(len(pax)),
                         "trips", "output_drt_legs pax-filter"))
        if len(pax):
            # Guard against an empty pax slice: pandas .mean()/.median() on
            # zero rows return NaN, which must never be emitted as a KPI
            # (mirrors extract_freight's avg_max_load NaN guard).
            rows += [
                row("passenger", "wait_mean_pax_only",
                    float(pax["waitTime"].mean()), "s", "output_drt_legs pax-filter"),
                row("passenger", "wait_median_pax_only",
                    float(pax["waitTime"].median()), "s", "output_drt_legs pax-filter"),
            ]

        if "fareForLeg" in legs.columns:
            rows += [
                row("economic", "fare_revenue_pax_only",
                    float(pax["fareForLeg"].sum()), "EUR", "output_drt_legs pax-filter"),
                row("economic", "parcel_fare_revenue",
                    float(parcel["fareForLeg"].sum()), "EUR", "output_drt_legs pax-filter"),
            ]

    return rows
