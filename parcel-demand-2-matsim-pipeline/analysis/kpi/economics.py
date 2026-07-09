# -*- coding: utf-8 -*-
"""PLACEHOLDER economic KPIs — bottom-up 25 EUR/veh-shift-h split into
labour 20 / vehicle 5 (Rudolph LMD breakdown, ~80/20). Must be refined before
the headline evaluation; every placeholder KPI carries _placeholder in its name."""
from common import row

LABOUR_EUR_PER_H = 20.0
VEHICLE_EUR_PER_H = 5.0


def _get(rows, name):
    for r in rows:
        if r["kpi_name"] == name:
            return r["value"]
    return None


def extract(all_rows, fleet_size=None):
    rows = []
    shift_h = _get(all_rows, "fleet_shift_hours")
    if shift_h is None and fleet_size:
        shift_h = fleet_size * 24.0  # DVRP shift 0..86400 per vehicle
    if shift_h:
        labour = shift_h * LABOUR_EUR_PER_H
        total = shift_h * (LABOUR_EUR_PER_H + VEHICLE_EUR_PER_H)
        rows.append(row("economic", "drt_cost_bottom_up_placeholder",
                        total, "EUR", "placeholder 25 EUR/veh-shift-h"))
        rides = _get(all_rows, "drt_rides")
        if rides:
            rows.append(row("economic", "drt_cost_per_ride_placeholder",
                            total / rides, "EUR/trip", "computed"))
        rows.append(row("economic", "drt_labour_share_placeholder",
                        labour / total, "share", "placeholder Rudolph 80/20"))
    fc = _get(all_rows, "freight_total_costs")
    parcels = _get(all_rows, "parcels_handled")
    if fc is not None and parcels:
        rows.append(row("economic", "freight_cost_per_parcel",
                        fc / parcels, "EUR/parcel", "computed"))
    return rows
