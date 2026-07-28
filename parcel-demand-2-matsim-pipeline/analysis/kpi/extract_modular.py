# -*- coding: utf-8 -*-
"""Modular (1d capsule swap) KPI rows from <prefix>.modular_tour_stats.csv (Task 9 contract).

Unlike Shared-Use, the pax side needs NO correction here (design D7): parcels never ride as
DVRP passengers, so drt_customer_stats is pax-truth as-is. This module only surfaces the
freight/tour side: the delta decomposition (expired_pending + pending_eod vs
dispatched_incomplete), tour completion, and the swap/retooling/deadhead cost of modularity.
Stock vehicle-distance stats DO include the freight excursions (same fleet, by design);
deadhead/service km planned splits come from dispatch-time routing, written by the handler.

Key metric definitions (for readers of this extractor):
1. deadhead_km_planned counts approach + return legs only; every inter-stop leg,
   including depot->first-stop, counts as service_km_planned. This differs from usual
   freight convention where depot->first-stop is often deadhead.
2. freight_vehicle_hours excludes incomplete excursions (they have no completion timestamp),
   which biases it downward exactly when the fleet is most saturated.
3. These are planned kilometres from dispatch-time routing, not travelled kilometres from
   events -- which is why the metric names carry _planned.
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
    expired = float(stats["parcels_expired_pending"]) + float(stats["parcels_pending_eod"])
    incomplete = float(stats["parcels_dispatched_unserved"])
    dispatched_tours = float(stats["tours_dispatched"])
    rows = [
        row("freight", "parcels_planned", int(stats["parcels_planned"]), "parcels", "modular_tour_stats"),
        row("freight", "parcels_served", int(stats["parcels_served"]), "parcels", "modular_tour_stats"),
        row("freight", "delta_parcels", int(delta), "parcels", "modular_tour_stats"),
        row("freight", "delta_share_expired_pending",
            expired / delta if delta else 0.0, "share", "modular_tour_stats"),
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
