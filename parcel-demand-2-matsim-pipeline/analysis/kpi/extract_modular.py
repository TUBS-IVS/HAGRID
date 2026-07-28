# -*- coding: utf-8 -*-
"""Modular (1d capsule swap) KPI rows from <prefix>.modular_tour_stats.csv (Task 9 contract).

The pax REQUEST side needs no correction here (design D7): parcels never ride as DVRP
passengers, so drt_customer_stats is pax-truth as-is. That is where D7's promise stops --
it does NOT extend to the vehicle side. The same vehicles run the freight excursions, so
several drt_vehicle_stats- and event-derived SYSTEM KPIs are mixed on a 1d run; unlike 1c
there is no `parcel_` id prefix to filter on, because the contamination is freight TASKS on
passenger vehicles rather than parcel agents. Those KPIs are handled (corrected where
possible, named where not) in extract_drt.py -- see its module docstring and
`meta/modular_contaminated_kpis`, plus METHODS-LOG §2.14.

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
"""
from pathlib import Path

import pandas as pd

from common import row


def has_modular_stats(run_dir, meta):
    # Run-ID-prefixed, like every MATSim output (1c bug 89f1ee5 - never a bare filename).
    return (Path(run_dir) / (meta.prefix + ".modular_tour_stats.csv")).exists()


def extract(run_dir, prefix):
    stats = dict(pd.read_csv(Path(run_dir) / (prefix + ".modular_tour_stats.csv"),
                             sep=";").values)
    delta = float(stats["delta_parcels"])
    # UNDISPATCHED, not "expired" (review Minor 7): this bucket is expired_pending PLUS
    # pending_eod -- parcels whose tour never got onto a vehicle, whether because its
    # completion envelope passed or because the day simply ended with it still queued. The
    # algebra was always this; only the published KPI name said otherwise, and nothing
    # downstream consumes it yet, so it is renamed now rather than kept wrong forever.
    undispatched = float(stats["parcels_expired_pending"]) + float(stats["parcels_pending_eod"])
    incomplete = float(stats["parcels_dispatched_unserved"])
    dispatched_tours = float(stats["tours_dispatched"])
    rows = [
        row("freight", "parcels_planned", int(stats["parcels_planned"]), "parcels", "modular_tour_stats"),
        row("freight", "parcels_served", int(stats["parcels_served"]), "parcels", "modular_tour_stats"),
        row("freight", "delta_parcels", int(delta), "parcels", "modular_tour_stats"),
        row("freight", "delta_share_undispatched",
            undispatched / delta if delta else 0.0, "share", "modular_tour_stats"),
        row("freight", "delta_share_dispatched_incomplete",
            incomplete / delta if delta else 0.0, "share", "modular_tour_stats"),
        row("freight", "tour_completion_rate",
            float(stats["tours_completed"]) / dispatched_tours if dispatched_tours else 0.0,
            "share", "modular_tour_stats"),
        row("freight", "tours_planned", int(stats["tours_planned"]), "tours", "modular_tour_stats"),
        row("freight", "tours_dispatched", int(dispatched_tours), "tours", "modular_tour_stats"),
        # C8 ex-post honesty of the 07:30-21:00 promise (dashboard card noted in backlog)
        row("freight", "tours_completed_late", int(stats["tours_completed_late"]), "tours", "modular_tour_stats"),
        row("freight", "parcels_served_late", int(stats["parcels_served_late"]), "parcels", "modular_tour_stats"),
        # Review Finding 3: splits "the gate was too tight" from "the tour never fit" in the
        # delta decomposition above - see key metric definition 5 in the module docstring.
        row("freight", "tours_rejected_at_splice", int(stats["tours_rejected_at_splice"]),
            "tours", "modular_tour_stats"),
        row("modular", "swaps_completed", int(stats["swaps_completed"]), "swaps", "modular_tour_stats"),
        row("modular", "retooling_hours", float(stats["retooling_hours"]), "h", "modular_tour_stats"),
        row("modular", "deadhead_km_planned", float(stats["deadhead_km_planned"]), "km", "modular_tour_stats"),
        row("modular", "service_km_planned", float(stats["service_km_planned"]), "km", "modular_tour_stats"),
        # completed tours only (incomplete excursions have no completion timestamp) - the
        # "vehicle-hours withdrawn from pax service" ingredient; the pax-side delta comes
        # from comparing wait/rejection KPIs against the 10-seat baseline run.
        row("modular", "freight_vehicle_hours", float(stats["freight_vehicle_hours"]), "h", "modular_tour_stats"),
    ]
    return rows
