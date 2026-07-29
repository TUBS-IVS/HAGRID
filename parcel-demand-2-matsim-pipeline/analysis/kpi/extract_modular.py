# -*- coding: utf-8 -*-
"""Modular (1d capsule swap) KPI rows from <prefix>.modular_tour_stats.csv (Task 9 contract).

The pax REQUEST side needs no correction here (design D7): parcels never ride as DVRP
passengers, so drt_customer_stats is pax-truth as-is. That is where D7's promise stops --
it does NOT extend to the vehicle side. The same vehicles run the freight excursions, so
several drt_vehicle_stats- and event-derived SYSTEM KPIs are mixed on a 1d run; unlike 1c
there is no `parcel_` id prefix to filter on, because the contamination is freight TASKS on
passenger vehicles rather than parcel agents. Those KPIs are handled (corrected where
possible, named where not) in extract_drt.py -- see its module docstring and
`meta/modular_contaminated_kpis`, plus METHODS-LOG 2.14.

This module only surfaces the freight/tour side: the delta decomposition
(undispatched = expired_pending + pending_eod, vs dispatched_incomplete), tour completion,
the splice-rejection diagnostic, and the swap/retooling/deadhead cost of modularity.

Key metric definitions (for readers of this extractor):
1. deadhead_km_planned counts approach + return legs only; every inter-stop leg,
   including depot->first-stop, counts as service_km_planned. This differs from usual
   freight convention where depot->first-stop is often deadhead.
2. freight_vehicle_hours excludes incomplete excursions (they have no completion timestamp),
   which biases it downward exactly when the fleet is most saturated.
3. WATCH THE TOUR SETS (review Minor 6): 1. is summed over DISPATCHED tours while 2. is summed
   over DISPATCHED AND COMPLETED ones. A dispatched-but-incomplete excursion therefore
   contributes its kilometres and none of its hours, so any km-per-freight-hour ratio formed
   from these is INFLATED -- the more so the more saturated the arm. Both filters are
   deliberate (see ModularKpiHandler's javadoc); it is the mismatch between them that bites.
4. These are planned kilometres from dispatch-time routing, not travelled kilometres from
   events -- which is why the metric names carry _planned.
5. tours_rejected_at_splice is a DIAGNOSTIC that overlaps the delta buckets, not a fourth
   bucket: it counts tours the splicer refused at least once, which is a different failure
   from the dispatcher's pending expiry (car-network plannedDuration vs. the actual DRT-routed
   completion plus approach leg). Read against tours_dispatched: a tour counted here that
   never reached DISPATCHED had the gate open for it and still did not fit, so the answer is
   "loosen the tour cap", not "lower theta".

Task 2 (paper-readiness fixwave, review F1/I6/M1/M2/M4, METHODS-LOG 2.16) added four more
things, all documented at their point of use below:
6. Task 1's five appended plan-time metrics (parcels_demand, parcels_unassigned_jsprit,
   parcels_missed_overlay, max_parcels_per_tour, peak_concurrent_swaps) via stats.get() --
   absent on pre-review CSVs, whose five names then simply do not appear in kpis_long.csv
   (no "pre_review" flag row; see the backward-compat test).
7. The 8 raw decomposition counters (parcels_expired_pending, parcels_pending_eod,
   parcels_dispatched, parcels_dispatched_unserved, tours_completed,
   tours_dispatched_incomplete, tours_expired_pending, tours_pending_eod) published as their
   own rows (review M2): the delta-bucket comment below argued at length that "undispatched"
   is two different failures folded together, then published neither half -- fixed now.
8. Python-side re-checking of the five conservation identities from ModularKpiHandler's
   javadoc, plus identity 0 (parcels_demand == parcels_planned + parcels_unassigned_jsprit,
   only when the Task-1 metrics exist) and the two negative-residual guards (review I6):
   Java already computes and logs these, but into the MATSim run log, which this pipeline
   never reads. A violation now surfaces as a `("meta", "modular_identity_violated")` row
   naming every failed check, emitted ONLY when at least one actually fails.
9. The OMITTED-not-0.0 convention for undefined ratios (review M4): on the theta=1.0 control
   arm (tours_dispatched == 0), tour_completion_rate and delta_share_dispatched_incomplete
   are UNDEFINED, not 0.0 -- a 0.0 plots on a theta-sweep chart as a genuine "0% of dispatched
   tours completed" data point instead of "no tours were dispatched". Likewise
   delta_share_undispatched and delta_share_dispatched_incomplete are omitted when
   delta_parcels == 0 (the other zero-delta case: a run that delivered everything).

Unreadable-CSV policy (review M1): a MISSING modular_tour_stats.csv is a different case --
`has_modular_stats` gates every call into `extract`, so that stays silent
(has_modular_stats() == False, no row at all). A file that EXISTS but is 0 bytes
(pandas.errors.EmptyDataError at read time) or header-only (parses to an empty dict, so any
key lookup below raises KeyError) now degrades the same way every other optional input in
this pipeline already does (meta/fleet_file_missing, meta/run_meta_degraded precedent): one
flagged `("meta", "modular_stats_unreadable")` row and nothing else from this extractor, never
an exception reaching build_kpis.
"""
from pathlib import Path

import pandas as pd

from common import row


def has_modular_stats(run_dir, meta):
    # Run-ID-prefixed, like every MATSim output (1c bug 89f1ee5 - never a bare filename).
    return (Path(run_dir) / (meta.prefix + ".modular_tour_stats.csv")).exists()


def extract(run_dir, prefix):
    csv_path = Path(run_dir) / (prefix + ".modular_tour_stats.csv")
    try:
        stats = dict(pd.read_csv(csv_path, sep=";").values)
        return _rows_from_stats(stats)
    except (pd.errors.EmptyDataError, KeyError) as exc:
        # Review M1: degrade like every other optional input (meta/fleet_file_missing,
        # meta/run_meta_degraded) rather than crash build_kpis. A 0-byte file raises
        # EmptyDataError at read_csv time; a header-only file parses to {} and then raises
        # KeyError the moment any required metric is looked up below.
        return [row("meta", "modular_stats_unreadable", 1, "flag",
                     "modular_tour_stats.csv unreadable (" + type(exc).__name__ + ": "
                     + str(exc) + ") - no modular KPI rows emitted, review M1")]


def _rows_from_stats(stats):
    # All counts, not measurements -- ints throughout (ambiguity resolution: identity checks
    # compare ints exactly, including identity 5's delta_parcels).
    tours_planned = int(stats["tours_planned"])
    tours_expired_pending = int(stats["tours_expired_pending"])
    tours_dispatched = int(stats["tours_dispatched"])
    tours_completed = int(stats["tours_completed"])
    tours_dispatched_incomplete = int(stats["tours_dispatched_incomplete"])
    tours_pending_eod = int(stats["tours_pending_eod"])

    parcels_planned = int(stats["parcels_planned"])
    parcels_expired_pending = int(stats["parcels_expired_pending"])
    parcels_dispatched = int(stats["parcels_dispatched"])
    parcels_served = int(stats["parcels_served"])
    parcels_dispatched_unserved = int(stats["parcels_dispatched_unserved"])
    parcels_pending_eod = int(stats["parcels_pending_eod"])
    delta = int(stats["delta_parcels"])

    # UNDISPATCHED, not "expired" (review Minor 7): this bucket is expired_pending PLUS
    # pending_eod -- parcels whose tour never got onto a vehicle, whether because its
    # completion envelope passed or because the day simply ended with it still queued.
    undispatched = parcels_expired_pending + parcels_pending_eod
    incomplete = parcels_dispatched_unserved

    rows = [
        row("freight", "parcels_planned", parcels_planned, "parcels", "modular_tour_stats"),
        row("freight", "parcels_served", parcels_served, "parcels", "modular_tour_stats"),
        row("freight", "delta_parcels", delta, "parcels", "modular_tour_stats"),
    ]
    # Review M4: undefined is not 0.0. delta_share_undispatched and
    # delta_share_dispatched_incomplete are both omitted when delta == 0 (the "everything
    # delivered" case); delta_share_dispatched_incomplete and tour_completion_rate are ALSO
    # omitted when tours_dispatched == 0 (the theta=1.0 control arm), independent of delta --
    # a policy choice (not just a division guard): incomplete is trivially 0 whenever nothing
    # was ever dispatched, and reporting that as a share would misread as a measured outcome.
    if delta:
        rows.append(row("freight", "delta_share_undispatched",
                         undispatched / delta, "share", "modular_tour_stats"))
    if delta and tours_dispatched:
        rows.append(row("freight", "delta_share_dispatched_incomplete",
                         incomplete / delta, "share", "modular_tour_stats"))
    if tours_dispatched:
        rows.append(row("freight", "tour_completion_rate",
                         tours_completed / tours_dispatched, "share", "modular_tour_stats"))
    rows += [
        row("freight", "tours_planned", tours_planned, "tours", "modular_tour_stats"),
        row("freight", "tours_dispatched", tours_dispatched, "tours", "modular_tour_stats"),
        # C8 ex-post honesty of the 07:30-21:00 promise (dashboard card noted in backlog)
        row("freight", "tours_completed_late", int(stats["tours_completed_late"]),
            "tours", "modular_tour_stats"),
        row("freight", "parcels_served_late", int(stats["parcels_served_late"]),
            "parcels", "modular_tour_stats"),
        # Review Finding 3: splits "the gate was too tight" from "the tour never fit" in the
        # delta decomposition above - see key metric definition 5 in the module docstring.
        row("freight", "tours_rejected_at_splice", int(stats["tours_rejected_at_splice"]),
            "tours", "modular_tour_stats"),
        # Review M2: the raw decomposition counters, published individually so a paper table
        # of the delta breakdown can be built straight from kpis_long.csv.
        row("freight", "tours_expired_pending", tours_expired_pending,
            "tours", "modular_tour_stats"),
        row("freight", "tours_completed", tours_completed, "tours", "modular_tour_stats"),
        row("freight", "tours_dispatched_incomplete", tours_dispatched_incomplete,
            "tours", "modular_tour_stats"),
        row("freight", "tours_pending_eod", tours_pending_eod, "tours", "modular_tour_stats"),
        row("freight", "parcels_expired_pending", parcels_expired_pending,
            "parcels", "modular_tour_stats"),
        row("freight", "parcels_dispatched", parcels_dispatched,
            "parcels", "modular_tour_stats"),
        row("freight", "parcels_dispatched_unserved", parcels_dispatched_unserved,
            "parcels", "modular_tour_stats"),
        row("freight", "parcels_pending_eod", parcels_pending_eod,
            "parcels", "modular_tour_stats"),
        row("modular", "swaps_completed", int(stats["swaps_completed"]),
            "swaps", "modular_tour_stats"),
        row("modular", "retooling_hours", float(stats["retooling_hours"]),
            "h", "modular_tour_stats"),
        row("modular", "deadhead_km_planned", float(stats["deadhead_km_planned"]),
            "km", "modular_tour_stats"),
        row("modular", "service_km_planned", float(stats["service_km_planned"]),
            "km", "modular_tour_stats"),
        # completed tours only (incomplete excursions have no completion timestamp) - the
        # "vehicle-hours withdrawn from pax service" ingredient; the pax-side delta comes
        # from comparing wait/rejection KPIs against the 10-seat baseline run.
        row("modular", "freight_vehicle_hours", float(stats["freight_vehicle_hours"]),
            "h", "modular_tour_stats"),
    ]

    # Task 1 (review F1/F3/F5/F7, METHODS-LOG 2.16): five metrics appended after
    # tours_rejected_at_splice. Absent on pre-review CSVs -- stats.get() (not stats[...])
    # so an old 21-metric run keeps extracting everything else and simply lacks these five
    # names (brief's explicit decision AGAINST a "modular_stats_pre_review" flag row).
    has_plan_stats = "parcels_demand" in stats
    parcels_demand = None
    parcels_unassigned_jsprit = None
    if has_plan_stats:
        parcels_demand = int(stats.get("parcels_demand"))
        parcels_unassigned_jsprit = int(stats.get("parcels_unassigned_jsprit"))
        parcels_missed_overlay = int(stats.get("parcels_missed_overlay"))
        max_parcels_per_tour = int(stats.get("max_parcels_per_tour"))
        peak_concurrent_swaps = int(stats.get("peak_concurrent_swaps"))
        rows += [
            row("freight", "parcels_demand", parcels_demand, "parcels", "modular_tour_stats"),
            row("freight", "parcels_unassigned_jsprit", parcels_unassigned_jsprit,
                "parcels", "modular_tour_stats"),
            row("freight", "parcels_missed_overlay", parcels_missed_overlay,
                "parcels", "modular_tour_stats"),
            row("freight", "max_parcels_per_tour", max_parcels_per_tour,
                "parcels", "modular_tour_stats"),
            row("modular", "peak_concurrent_swaps", peak_concurrent_swaps,
                "swaps", "modular_tour_stats"),
        ]

    rows.append(_identity_violation_row(
        tours_planned, tours_expired_pending, tours_dispatched, tours_completed,
        tours_dispatched_incomplete, tours_pending_eod, parcels_planned,
        parcels_expired_pending, parcels_dispatched, parcels_served,
        parcels_dispatched_unserved, parcels_pending_eod, delta,
        has_plan_stats, parcels_demand, parcels_unassigned_jsprit))
    return [r for r in rows if r is not None]


def _identity_violation_row(tours_planned, tours_expired_pending, tours_dispatched,
        tours_completed, tours_dispatched_incomplete, tours_pending_eod, parcels_planned,
        parcels_expired_pending, parcels_dispatched, parcels_served,
        parcels_dispatched_unserved, parcels_pending_eod, delta,
        has_plan_stats, parcels_demand, parcels_unassigned_jsprit):
    """Review I6: re-check the five conservation identities from ModularKpiHandler's javadoc
    (plus identity 0 and the two negative-residual guards) instead of trusting the CSV --
    Java already computes and logs these, but into the MATSim run log, which this pipeline
    never reads. Returns a single named meta row, or None when everything conserves."""
    checks = [
        ("identity_1_tours_planned", tours_planned,
         tours_expired_pending + tours_dispatched + tours_pending_eod),
        ("identity_2_tours_dispatched", tours_dispatched,
         tours_completed + tours_dispatched_incomplete),
        ("identity_3_parcels_planned", parcels_planned,
         parcels_expired_pending + parcels_dispatched + parcels_pending_eod),
        ("identity_4_parcels_dispatched", parcels_dispatched,
         parcels_served + parcels_dispatched_unserved),
        ("identity_5_delta_parcels", delta, parcels_planned - parcels_served),
    ]
    if has_plan_stats:
        # METHODS-LOG 2.16: parcels_demand is the RAW demand before jsprit dropped anything;
        # parcels_planned + parcels_unassigned_jsprit must account for all of it.
        checks.append(("identity_0_parcels_demand", parcels_demand,
                        parcels_planned + parcels_unassigned_jsprit))
    failed = [name for name, lhs, rhs in checks if lhs != rhs]
    # Minor 2 (Task 9 review, carried into this Python check): the two *_pending_eod residuals
    # are derived by subtraction and can go negative if a tour/parcel is double-counted into
    # more than one mutually-exclusive bucket -- a failure mode none of the five identities
    # above can see by themselves.
    if tours_pending_eod < 0:
        failed.append("tours_pending_eod_negative")
    if parcels_pending_eod < 0:
        failed.append("parcels_pending_eod_negative")
    if not failed:
        return None
    return row("meta", "modular_identity_violated", len(failed), "flag",
               "conservation identities failed: " + ", ".join(failed))
