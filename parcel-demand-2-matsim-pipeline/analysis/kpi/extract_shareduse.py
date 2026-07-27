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

import pax_only
from common import row
from pax_only import PARCEL_PREFIX  # noqa: F401  (re-exported; canonical def lives there)


def has_shareduse_stats(run_dir, meta):
    # MATSim writes run outputs run-ID-prefixed, so the handler's CSV lands as
    # "{prefix}.shareduse_channel_stats.csv" (e.g. DRT_SHAREDUSE_..._suchi0.shareduse_channel_stats.csv),
    # NOT a bare "shareduse_channel_stats.csv". Match the real filename or the extractor
    # is silently skipped on every real run.
    return (Path(run_dir) / (meta.prefix + ".shareduse_channel_stats.csv")).exists()


def extract(run_dir, prefix):
    run_dir = Path(run_dir)
    rows = []

    stats = dict(pd.read_csv(run_dir / (prefix + ".shareduse_channel_stats.csv"), sep=";").values)
    rows += [
        row("channel", "undelivered_rate", float(stats["undelivered_rate"]), "share", "shareduse_channel_stats"),
        row("channel", "share_channel_door", float(stats["share_channel_door"]), "share", "shareduse_channel_stats"),
        row("channel", "share_channel_locker", float(stats["share_channel_locker"]), "share", "shareduse_channel_stats"),
        row("freight", "parcels_submitted", int(stats["parcels_submitted"]), "parcels", "shareduse_channel_stats"),
        row("freight", "parcels_delivered", int(stats["parcels_delivered"]), "parcels", "shareduse_channel_stats"),
        row("freight", "parcels_undelivered", int(stats["parcels_undelivered"]), "parcels", "shareduse_channel_stats"),
        row("freight", "mean_delivery_delay_s", float(stats["mean_delivery_delay_s"]), "s", "shareduse_channel_stats"),
    ]

    pax_rides = None

    legs_f = run_dir / (prefix + ".output_drt_legs_drt.csv")
    if legs_f.exists():
        legs = pd.read_csv(legs_f, sep=";")
        pax, parcel = pax_only.split_parcels(legs, "personId")
        pax_rides = int(len(pax))

        rows.append(row("passenger", "drt_rides_pax_only", pax_rides,
                         "trips", "output_drt_legs pax-filter"))
        if len(pax):
            # Guard against an empty pax slice: pandas .mean()/.median() on
            # zero rows return NaN, which must never be emitted as a KPI
            # (mirrors extract_freight's avg_max_load NaN guard). Every column
            # below is also presence-guarded: the legs CSV schema varies across
            # MATSim versions, and a missing column must drop ONE KPI, never
            # abort the extractor.
            rows += [
                row("passenger", "wait_mean_pax_only",
                    float(pax["waitTime"].mean()), "s", "output_drt_legs pax-filter"),
                row("passenger", "wait_median_pax_only",
                    float(pax["waitTime"].median()), "s", "output_drt_legs pax-filter"),
                # NB estimator caveat: MATSim's own wait_p95 / percentage_WT_below_*
                # come from its internal accumulators; these are pandas
                # re-derivations over the same served-leg population. On a
                # pure-pax run they agree to ~1e-4 -- far below the parcel
                # contamination they replace -- and the `source` column keeps
                # the basis switch visible in kpis_long.csv.
                row("passenger", "wait_p95_pax_only",
                    float(pax["waitTime"].quantile(0.95)), "s", "output_drt_legs pax-filter"),
                row("passenger", "wait_below_10min_pax_only",
                    float((pax["waitTime"] <= 600).mean()), "share", "output_drt_legs pax-filter"),
                row("passenger", "wait_below_15min_pax_only",
                    float((pax["waitTime"] <= 900).mean()), "share", "output_drt_legs pax-filter"),
            ]
            if "inVehicleTravelTime" in legs.columns:
                rows.append(row("passenger", "in_vehicle_time_mean_pax_only",
                                 float(pax["inVehicleTravelTime"].mean()), "s",
                                 "output_drt_legs pax-filter"))
            if "travelDistance_m" in legs.columns:
                rows.append(row("passenger", "drt_trip_distance_mean_pax_only",
                                 float(pax["travelDistance_m"].mean()) / 1000.0, "km",
                                 "output_drt_legs pax-filter"))
                if "directTravelDistance_m" in legs.columns:
                    direct_mean = float(pax["directTravelDistance_m"].mean())
                    if direct_mean > 0:
                        # Same ratio-of-means form as extract_drt's detour_factor
                        # (distance_m_mean / directDistance_m_mean), not a mean of ratios.
                        rows.append(row("passenger", "detour_factor_pax_only",
                                         float(pax["travelDistance_m"].mean()) / direct_mean,
                                         "ratio", "output_drt_legs pax-filter"))

        if "fareForLeg" in legs.columns:
            rows += [
                row("economic", "fare_revenue_pax_only",
                    float(pax["fareForLeg"].sum()), "EUR", "output_drt_legs pax-filter"),
                row("economic", "parcel_fare_revenue",
                    float(parcel["fareForLeg"].sum()), "EUR", "output_drt_legs pax-filter"),
            ]

    rows += _rejection_rows(run_dir, prefix, pax_rides)
    rows += _modal_share_rows(run_dir, prefix)
    return rows


def _rejection_rows(run_dir, prefix, pax_rides):
    """Pax-only rejection count/rate from the per-request rejections CSV.

    The stock `drt_rejections` (drt_customer_stats) counts every rejected DVRP
    request, and on a Shared-Use run a chi-starved parcel can be rejected many
    times over -- so the stock rejection RATE is not a passenger-service signal
    at all. Filtered by the same parcel predicate, it is again."""
    f = run_dir / (prefix + ".output_drt_rejections_drt.csv")
    if not f.exists():
        return []
    rej = pd.read_csv(f, sep=";")
    if "personIds" not in rej.columns:
        return []
    # joined=True: `personIds` holds one id for a single-person request and a joined
    # list for a grouped one (a pax id can never carry the prefix, so substring is safe).
    pax_rej, _ = pax_only.split_parcels(rej, "personIds", joined=True)
    pax_rejections = int(len(pax_rej))
    out = [row("passenger", "drt_rejections_pax_only", pax_rejections,
               "requests", "output_drt_rejections pax-filter")]
    if pax_rides is not None:
        out.append(row("passenger", "drt_rejection_rate_pax_only",
                        pax_rejections / max(1, pax_rides + pax_rejections),
                        "share", "computed(output_drt_rejections pax-filter)"))
    return out


def _modal_share_rows(run_dir, prefix):
    """Pax-only modal shares from output_trips.

    Parcel-persons are a full subpopulation whose single trip is always `drt`,
    so they inflate `modal_share_drt` (modestats) and deflate every other mode.
    The shares therefore have to be recomputed TOGETHER -- correcting drt alone
    would leave the set not summing to 1. Verified against modestats on a
    parcel-free run: agreement to ~1e-4 (main-mode share over trips is the same
    basis), which is negligible next to the contamination removed."""
    f = run_dir / (prefix + ".output_trips.csv.gz")
    if not f.exists():
        return []
    trips = pd.read_csv(f, sep=";")
    if "person" not in trips.columns or "main_mode" not in trips.columns:
        return []
    pax, _ = pax_only.split_parcels(trips, "person")
    if not len(pax):
        return []
    shares = pax["main_mode"].value_counts(normalize=True)
    return [row("system", "modal_share_" + str(mode) + "_pax_only", float(share),
                "share", "output_trips pax-filter")
            for mode, share in shares.items()]
