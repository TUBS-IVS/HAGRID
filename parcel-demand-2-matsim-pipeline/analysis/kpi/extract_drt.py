# -*- coding: utf-8 -*-
"""Passenger/system/channel KPI rows from MATSim's DRT analysis CSVs
(authoritative; event reconstruction is ~3% low) plus optional event-based
service-time KPIs via drt_service_time.reconstruct.

1d MODULAR contamination
------------------------
On a DRT_MODULAR run the SAME vehicles that serve passengers also run freight
excursions, so several of the system KPIs below stop being passenger numbers.
Design D7 promised no correction would be needed here; that promise holds for
the pax REQUEST side (parcels never become DVRP passengers, so
drt_customer_stats is pax-truth) but NOT for the vehicle side, and the reason it
does not is structural: 1c's contamination came from parcel AGENTS, which carry
a `parcel_` id prefix and can therefore be filtered; 1d's comes from freight
TASKS on ordinary passenger vehicles, and nothing filters those.

The event-reconstruction KPIs ARE corrected (drt_service_time now separates
freight driving, freight dwell and capsule-swap retooling from passenger drive
and stop time -- see that module's docstring). The drt_vehicle_stats-derived
ones CANNOT be: MATSim aggregates the excursion kilometres into
totalDistance/emptyRatio/d_p_d_t before this code ever sees them, and there is
no per-task distance breakdown in that CSV to subtract. Both sets are named in
the `meta/modular_contaminated_kpis` provenance row emitted below, following the
precedent pax_only.CONTAMINATION_KPI sets for Shared-Use.
See METHODS-LOG §2.14.
"""
import sys
from pathlib import Path

import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "drt-headline"))
import drt_service_time  # noqa: E402

from common import row  # noqa: E402

#: Name of the meta provenance row emitted on a 1d Modular run. Mirrors
#: pax_only.CONTAMINATION_KPI (the Shared-Use equivalent) -- one name, defined
#: once, next to the machinery that writes it.
MODULAR_CONTAMINATION_KPI = "modular_contaminated_kpis"

#: NOT correctable in analysis, at all. Source is drt_vehicle_stats, which reports
#: whole-vehicle totals: the excursion's kilometres are already inside
#: `totalDistance` (-> drt_empty_ratio's denominator) and inside `d_p/d_t` by the
#: time this code sees the CSV, and there is no per-task distance split to net
#: them out against. Getting these right needs a per-link, per-task distance
#: reconstruction that cannot be built for freight legs (D7: freight stops emit no
#: native events).
MODULAR_UNCORRECTABLE = (
    "drt_vehicle_km",
    "drt_empty_ratio",
    "drt_dp_over_dt",
)

#: Correct as measurements, but their window/denominator is the vehicle's ACTIVE
#: span, which on a 1d run includes the freight excursion. They are therefore
#: depressed relative to the baseline's and are NOT directly comparable with it.
#: Unlike the group above a reader CAN net these out, because
#: `drt_freight_hours_total` (published below) is exactly the excess -- so they are
#: reported as "adjustable", not as silently-wrong passenger numbers.
MODULAR_FREIGHT_IN_WINDOW = (
    "drt_tour_hours_total",
    "service_ratio_active",
    "fleet_utilisation_by_time",
    "fleet_utilisation_by_trips",
    "mean_pax_aboard",
)

#: WERE contaminated, now corrected by drt_service_time's freight/retooling split.
#: Named in the provenance row too, so a reader holding an older CSV can tell which
#: side of the fix it came from: before it, `drt_wait_hours_total` absorbed the
#: fleet's entire freight workload as "idle" and `drt_service_hours_total` counted
#: capsule retooling as passenger service.
MODULAR_CORRECTED = (
    "drt_drive_hours_total",
    "drt_service_hours_total",
    "drt_wait_hours_total",
)


def extract(run_dir, prefix, fleet_file=None, drt_events_cache=None, recon=None):
    run_dir = Path(run_dir)

    def p(suffix):
        return run_dir / (prefix + suffix)

    rows = []

    cs = pd.read_csv(p(".drt_customer_stats_drt.csv"), sep=";").iloc[-1]
    rides, rejections = int(cs["rides"]), int(cs["rejections"])
    rows += [
        row("passenger", "drt_rides", rides, "trips", "drt_customer_stats"),
        row("passenger", "drt_rejections", rejections, "requests", "drt_customer_stats"),
        row("passenger", "drt_rejection_rate",
            rejections / max(1, rides + rejections), "share", "computed(int cols)"),
        row("passenger", "wait_mean", float(cs["wait_average"]), "s", "drt_customer_stats"),
        row("passenger", "wait_median", float(cs["wait_median"]), "s", "drt_customer_stats"),
        row("passenger", "wait_p95", float(cs["wait_p95"]), "s", "drt_customer_stats"),
        row("passenger", "wait_below_10min",
            float(cs["percentage_WT_below_10"]) / 100.0, "share", "drt_customer_stats"),
        row("passenger", "wait_below_15min",
            float(cs["percentage_WT_below_15"]) / 100.0, "share", "drt_customer_stats"),
        row("passenger", "in_vehicle_time_mean",
            float(cs["inVehicleTravelTime_mean"]), "s", "drt_customer_stats"),
        row("passenger", "detour_factor",
            float(cs["distance_m_mean"]) / float(cs["directDistance_m_mean"]),
            "ratio", "computed"),
    ]
    if "rides_pax" in cs.index:
        rows.append(row("passenger", "drt_passengers", int(cs["rides_pax"]),
                         "pax", "drt_customer_stats"))
    if "distance_m_mean" in cs.index:
        rows.append(row("passenger", "drt_trip_distance_mean",
                         float(cs["distance_m_mean"]) / 1000.0,
                         "km", "drt_customer_stats"))

    vs = pd.read_csv(p(".drt_vehicle_stats_drt.csv"), sep=";").iloc[-1]
    rows += [
        row("system", "drt_vehicles", int(vs["vehicles"]), "vehicles", "drt_vehicle_stats"),
        row("system", "drt_vehicle_km", float(vs["totalDistance"]) / 1000.0, "km", "drt_vehicle_stats"),
        row("system", "drt_empty_ratio", float(vs["emptyRatio"]), "share", "drt_vehicle_stats"),
        row("passenger", "drt_passenger_km",
            float(vs["totalPassengerDistanceTraveled"]) / 1000.0, "km", "drt_vehicle_stats"),
        row("system", "drt_dp_over_dt", float(vs["d_p/d_t"]), "ratio", "drt_vehicle_stats"),
    ]

    sh = pd.read_csv(p(".drt_sharing_metrics_drt.csv"), sep=";").iloc[-1]
    rows += [
        row("passenger", "pooling_rate", float(sh["poolingRate"]), "share", "drt_sharing_metrics"),
        row("passenger", "sharing_factor", float(sh["sharingFactor"]), "ratio", "drt_sharing_metrics"),
    ]

    ms = pd.read_csv(p(".modestats.csv"), sep=";").iloc[-1]
    for mode in [c for c in ms.index if c != "iteration"]:
        rows.append(row("system", "modal_share_" + mode, float(ms[mode]), "share", "modestats"))

    trips = pd.read_csv(p(".output_trips.csv.gz"), sep=";")
    drt_trips = trips[trips["modes"].str.contains("drt", na=False)]
    feeder = drt_trips["modes"].str.contains("pt", na=False)
    pt_trips = trips[trips["main_mode"] == "pt"]
    rows += [
        row("channel", "drt_feeder_trips", int(feeder.sum()), "trips", "output_trips"),
        row("channel", "drt_feeder_share",
            float(feeder.mean()) if len(drt_trips) else 0.0, "share", "computed"),
        row("channel", "rail_trips_drt_fed_share",
            (int(feeder.sum()) / len(pt_trips)) if len(pt_trips) else 0.0, "share", "computed"),
    ]

    if recon is not None or drt_events_cache is not None:
        r = recon if recon is not None else drt_service_time.reconstruct(
            str(drt_events_cache), str(fleet_file) if fleet_file else None)
        fl = r["fleet"]
        seg_t = fl["seg_time"]
        tot_t = sum(seg_t.values())
        rows += [
            row("system", "service_ratio_active", fl["ratio_active"], "share", "events"),
            row("passenger", "mean_pax_aboard",
                (sum(lv * s for lv, s in seg_t.items()) / tot_t) if tot_t else 0.0,
                "pax", "events"),
        ]
        # Shift- and capacity-denominated KPIs exist ONLY when the DVRP fleet file was
        # actually found. drt_service_time no longer substitutes a default seat count or
        # the sim horizon for a missing shift window, so an absent fleet file now drops
        # these KPIs instead of silently publishing a wrong denominator. The meta rows
        # below make that omission visible in kpis_long.csv rather than in stdout only.
        if "ratio_shift" in fl:
            rows.append(row("system", "service_ratio_shift", fl["ratio_shift"], "share", "events"))
            rows.append(row("system", "fleet_shift_hours",
                             fl["sum_shift_s"] / 3600.0, "h", "events/fleet file"))
        if fl.get("capacity"):
            # Also the KPI maps.py looks for when colouring occupancy -- without it that
            # lookup silently fell back to a hardcoded 8 seats.
            rows.append(row("system", "drt_vehicle_capacity", int(fl["capacity"]),
                             "seats", "fleet file"))
            rows.append(row("system", "fleet_utilisation_by_time",
                             fl["util_by_time"], "share", "events"))
            rows.append(row("system", "fleet_utilisation_by_trips",
                             fl["util_by_trips"], "share", "events"))
        else:
            rows.append(row("meta", "fleet_file_missing", 1, "flag",
                             "capacity/shift KPIs omitted - DVRP fleet file not found"))
        if "tour_s" in fl:
            rows.append(row("system", "drt_tour_hours_total",
                             fl["tour_s"] / 3600.0, "h", "events"))
        if "drive_s" in fl:
            rows.append(row("system", "drt_drive_hours_total",
                             fl["drive_s"] / 3600.0, "h", "events"))
        if "waiting_s" in fl:
            rows.append(row("system", "drt_wait_hours_total",
                             fl["waiting_s"] / 3600.0, "h", "events"))
        if "stop_s" in fl:
            rows.append(row("system", "drt_service_hours_total",
                             fl["stop_s"] / 3600.0, "h", "events"))
        rows += _modular_rows(fl)
    return rows


def _modular_rows(fl):
    """The 1d freight time components + the contamination provenance row.

    Emitted ONLY when the reconstruction actually observed freight activity, so a
    baseline / Shared-Use / LMD run's kpis_long.csv keeps exactly the KPI names it
    had before -- the same discipline `meta/fleet_file_missing` and
    `meta/parcel_contaminated_kpis` follow.

    The three component rows exist so the fleet's freight workload is VISIBLE
    rather than merely no longer misfiled: `drt_drive_hours_total` and
    `drt_service_hours_total` are passenger-only now, and without these the
    difference between them and the tour span would just be an unexplained gap.
    They are also what makes MODULAR_FREIGHT_IN_WINDOW adjustable by hand.
    """
    if not fl.get("modular_freight_seen"):
        return []
    rows = [
        row("system", "drt_freight_drive_hours_total",
            fl["freight_drive_s"] / 3600.0, "h", "events(MODULAR_FREIGHT_DRIVE)"),
        row("system", "drt_freight_dwell_hours_total",
            fl["freight_stop_s"] / 3600.0, "h", "events(MODULAR_FREIGHT_STOP)"),
        # The capsule swaps. Event-derived and therefore INDEPENDENT of the Java
        # handler's `retooling_hours` (= swaps_completed x 420 s), which is a
        # derivation, not a measurement -- the two agreeing is a real cross-check.
        row("system", "drt_retooling_hours_total",
            fl["retooling_s"] / 3600.0, "h", "events(STOP inside a freight window)"),
        row("system", "drt_freight_hours_total",
            fl["freight_s"] / 3600.0, "h", "events(freight drive + dwell + retooling)"),
    ]
    rows.append(row(
        "meta", MODULAR_CONTAMINATION_KPI,
        len(MODULAR_UNCORRECTABLE) + len(MODULAR_FREIGHT_IN_WINDOW), "kpis",
        "1d: freight tasks share the passenger fleet. NOT CORRECTABLE (drt_vehicle_stats"
        " already contains the excursion km): " + ",".join(MODULAR_UNCORRECTABLE)
        + " | ACTIVE WINDOW INCLUDES FREIGHT, subtract drt_freight_hours_total to compare"
        " with the baseline: " + ",".join(MODULAR_FREIGHT_IN_WINDOW)
        + " | CORRECTED (freight/retooling split out of pax drive+stop time): "
        + ",".join(MODULAR_CORRECTED)))
    return rows
