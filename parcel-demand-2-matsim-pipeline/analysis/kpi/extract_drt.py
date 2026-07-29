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
and stop time -- see that module's docstring). Four more KPIs
(MODULAR_FREIGHT_IN_WINDOW) are corrected HERE, by hand, against
`drt_freight_hours_total` -- but review I2 found the old recipe wrong for 3 of
the 4: `drt_tour_hours_total` is a subtraction, but `service_ratio_active`,
`fleet_utilisation_by_time` and `mean_pax_aboard` are RATIOS whose denominator
is the active window, so removing the freight window means RESCALING by
tour_h / (tour_h - freight_h) -- valid only because an excursion is
occupancy-0 throughout (D2's passenger lockout). See `_modular_pax_rows` for
the `*_pax` companion rows that carry this out.

`fleet_utilisation_by_trips` cannot be recovered even by hand (moved to
MODULAR_UNCORRECTABLE, review I2): it is a SEGMENT count, and a freight-only
vehicle contributes an unpublished occupancy-0 segment no published KPI
carries a boundary for.

The three drt_vehicle_stats-derived KPIs in MODULAR_NOT_CORRECTED are NOT
corrected here -- review I3: that is a DECISION, not a limit ("cannot be
corrected" was factually wrong). MATSim aggregates the excursion kilometres
into totalDistance/emptyRatio/d_p_d_t before this code ever sees the CSV, but
freight DRIVES are real DVRP drives that DO emit LinkEnterEvents
(ModularTourScheduler.java:151,161,171) -- the same events MATSim's own
DrtVehicleDistanceStats used to build these very numbers, and this repo
already reconstructs per-link distances elsewhere
(geometry.reconstruct_drt_paths; formerly also
drt-headline/build_vehicle_tours.py, deleted 2026-07-29, see git history). A
window correction of these three is buildable in principle; it is not built
because a self-computed number would replace MATSim's authoritative CSV.
(D7's request-side claim stays true and is a separate fact, not the reason
these go uncorrected: freight STOPS -- the parcel delivery stops themselves --
emit no native passenger events.)

All three sets, plus MODULAR_SECONDARY_CONTAMINATED (consumers with no
provenance channel of their own: kpi_distributions.csv, kpi_vehicles.csv, the
occupancy map), are named in the `meta/modular_contaminated_kpis` /
`meta/modular_secondary_contaminated` provenance rows emitted below, following
the precedent pax_only.CONTAMINATION_KPI sets for Shared-Use. Review C1: both
rows are emitted whenever `modular=True` (the CSV-path signal that this run's
modular_tour_stats.csv exists), independent of whether THIS build happened to
reconstruct events -- the marker used to live only inside the event-reconstruction
branch, so a --no-events build (or a run whose output_events.xml.gz was never
cached) published every contaminated KPI with no marker at all.
See METHODS-LOG 2.14.
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

#: NOT corrected here -- review I3: this is a decision, not a limit ("cannot be
#: corrected" was the wrong claim). Source is drt_vehicle_stats, which reports
#: whole-vehicle totals: the excursion's kilometres are already inside
#: `totalDistance` (-> drt_empty_ratio's denominator) and inside `d_p/d_t` by the
#: time this code sees the CSV, and there is no per-task distance split in that
#: CSV to net them out against. A window correction (reconstructing per-link
#: distance from the DRT event stream and subtracting the freight window, the
#: way drt_service_time already does for TIME) is correctable IN PRINCIPLE --
#: freight drives are real DVRP drives and do emit LinkEnterEvents -- it is just
#: not done: a self-computed number would replace MATSim's authoritative CSV.
#: (D7's freight-STOPS claim is a separate, still-true fact about the REQUEST
#: side -- see the module docstring; it is not why these three go uncorrected.)
MODULAR_NOT_CORRECTED = (
    "drt_vehicle_km",
    "drt_empty_ratio",
    "drt_dp_over_dt",
)

#: NOT correctable in analysis, at all -- unlike the trio above there is no "in
#: principle" fix here. `fleet_utilisation_by_trips` is a SEGMENT count
#: (unweighted mean of x/capacity over constant-occupancy intervals); a
#: freight-only vehicle contributes its own occupancy-0 segment, but that
#: segment carries no published boundary a reader could subtract or rescale
#: against. MOVED here from MODULAR_FREIGHT_IN_WINDOW (review I2): the old
#: recipe wrongly claimed a flat subtraction recovered it along with the other
#: four "adjustable" KPIs.
MODULAR_UNCORRECTABLE = MODULAR_NOT_CORRECTED + ("fleet_utilisation_by_trips",)

#: Correct as measurements, but their window/denominator is the vehicle's ACTIVE
#: span, which on a 1d run includes the freight excursion -- depressed relative
#: to the baseline's and NOT directly comparable with it. Review I2: the fix is
#: NOT a uniform subtraction of `drt_freight_hours_total`. `drt_tour_hours_total`
#: (the span itself) is a subtraction; the other three are RATIOS whose
#: denominator is that span, so removing the freight window means RESCALING by
#: tour_h / (tour_h - freight_h) -- valid because an excursion is occupancy-0
#: throughout (D2's passenger lockout). See `_modular_pax_rows` for the `*_pax`
#: companion rows that apply whichever recipe is correct for each name here.
MODULAR_FREIGHT_IN_WINDOW = (
    "drt_tour_hours_total",
    "service_ratio_active",
    "fleet_utilisation_by_time",
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

#: Contaminated the same way as the two sets above, but published by OTHER
#: extractors (kpi_distributions.csv, kpi_vehicles.csv, the occupancy map) that
#: carry no provenance channel of their own -- named here so at least one row in
#: kpis_long.csv says so (METHODS-LOG 2.14, Paper-Readiness-Review point 4). Not
#: corrected by this module; a reader must treat every one of these as
#: contaminated on a 1d run.
MODULAR_SECONDARY_CONTAMINATED = (
    "drt_tour_duration", "occ_time", "occ_segments", "occ_km", "drt_tour_distance",
    "vehicles.active_h", "vehicles.ratio_active", "map.occupancy_colors",
)

#: The marker's "source" text: one correction verb per KPI (review I2 -- the old
#: text claimed a single "subtract drt_freight_hours_total" recipe fixed all of
#: MODULAR_FREIGHT_IN_WINDOW, which is only true for the first name below).
_MARKER_SOURCE = (
    "subtract: " + MODULAR_FREIGHT_IN_WINDOW[0]
    + " | rescale x tour/(tour-freight): " + ", ".join(MODULAR_FREIGHT_IN_WINDOW[1:])
    + " | not recoverable: fleet_utilisation_by_trips"
    + " | not corrected (see METHODS-LOG 2.14): " + ", ".join(MODULAR_NOT_CORRECTED)
)


def extract(run_dir, prefix, fleet_file=None, recon=None, modular=False):
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

    # Review C1: independent of whether the event path ran at all -- the
    # contamination is a fact about which CSVs this RUN wrote (a
    # modular_tour_stats.csv means the same passenger vehicles ran freight
    # excursions), not about whether THIS build happened to reconstruct events.
    if modular:
        rows += _modular_marker_rows()

    if recon is not None:
        fl = recon["fleet"]
        seg_t = fl["seg_time"]
        tot_t = sum(seg_t.values())
        mean_pax_aboard = (sum(lv * s for lv, s in seg_t.items()) / tot_t) if tot_t else 0.0
        rows += [
            row("system", "service_ratio_active", fl["ratio_active"], "share", "events"),
            row("passenger", "mean_pax_aboard", mean_pax_aboard, "pax", "events"),
        ]
        # Shift- and capacity-denominated KPIs exist ONLY when the DVRP fleet file was
        # actually found. drt_service_time no longer substitutes a default seat count or
        # the sim horizon for a missing shift window, so an absent fleet file now drops
        # these KPIs instead of silently publishing a wrong denominator. The meta row
        # below makes that omission visible in kpis_long.csv rather than in stdout only.
        # Checked directly against `fleet_file_known` (drt_service_time's own verdict on
        # whether it could read the file), not against `capacity` being falsy -- the two
        # used to be conflated, which mixed "no fleet file" with "fleet file present but
        # its capacity attribute was unparseable" into one flag.
        if not fl.get("fleet_file_known"):
            rows.append(row("meta", "fleet_file_missing", 1, "flag",
                             "capacity/shift KPIs omitted - DVRP fleet file not found"))
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
        if modular:
            rows += _modular_pax_rows(fl, mean_pax_aboard)
    return rows


def _modular_marker_rows():
    """meta/modular_contaminated_kpis + meta/modular_secondary_contaminated.

    Review C1: gated on the `modular` CSV-path flag alone (see `extract`'s call
    site), never on anything inside `fl` -- a run's vehicle-side contamination
    does not depend on whether this particular build reconstructed events.
    """
    return [
        row("meta", MODULAR_CONTAMINATION_KPI,
            len(MODULAR_UNCORRECTABLE) + len(MODULAR_FREIGHT_IN_WINDOW), "kpis",
            _MARKER_SOURCE),
        row("meta", "modular_secondary_contaminated",
            len(MODULAR_SECONDARY_CONTAMINATED), "kpis",
            "kpi_distributions.csv (drt_tour_duration, occ_time, occ_segments, occ_km,"
            " drt_tour_distance) and kpi_vehicles.csv (active_h, ratio_active) and the"
            " occupancy map (map.occupancy_colors) carry no provenance channel for 1d"
            " freight contamination; see METHODS-LOG 2.14"),
    ]


def _modular_rows(fl):
    """The 1d freight time component rows.

    Emitted ONLY when the reconstruction actually observed freight activity, so a
    baseline / Shared-Use / LMD run's kpis_long.csv keeps exactly the KPI names it
    had before -- the same discipline `meta/fleet_file_missing` and
    `meta/parcel_contaminated_kpis` follow. These are event-derived FACTS (not
    gated on the `modular` CSV-path flag the way the marker and the `*_pax`
    correction rows are): a modular_tour_stats.csv could in principle exist
    without any freight excursion having actually run.

    The four component rows exist so the fleet's freight workload is VISIBLE
    rather than merely no longer misfiled: `drt_drive_hours_total` and
    `drt_service_hours_total` are passenger-only now, and without these the
    difference between them and the tour span would just be an unexplained gap.
    They are also the `drt_freight_hours_total` that `_modular_pax_rows` below
    subtracts/rescales against.

    A fifth, CONDITIONAL row (review I7) rides along: `meta/modular_open_freight_windows`,
    emitted only when `drt_service_time.reconstruct` found at least one excursion window
    still open at +inf (a DISPATCHED with no matching COMPLETED). That count should equal
    `tours_dispatched_incomplete` from modular_tour_stats.csv; a mismatch means a modularTour
    mark was lost or reordered between Java and here, which would otherwise silently turn the
    rest of that vehicle's day into unflagged freight.
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
    # Review I7: an unbalanced modularTour mark (a DISPATCHED with no matching COMPLETED)
    # otherwise turns a vehicle's remainder-of-day into freight with no diagnostic anywhere
    # in kpis_long.csv -- this is the only place that count becomes visible. Emitted only
    # when > 0 (same discipline as every other meta row here): a baseline/Shared-Use/LMD run,
    # or a 1d run where every excursion closed cleanly, keeps exactly the rows it had before.
    open_windows = fl.get("open_freight_windows", 0)
    if open_windows > 0:
        rows.append(row(
            "meta", "modular_open_freight_windows", open_windows, "windows",
            "expected == tours_dispatched_incomplete from modular_tour_stats.csv;"
            " mismatch means lost/reordered modularTour marks"))
    return rows


def _modular_pax_rows(fl, mean_pax_aboard):
    """The `*_pax` companion rows: review I2's actual correction recipe for the
    four MODULAR_FREIGHT_IN_WINDOW KPIs that CAN be recovered by hand
    (`fleet_utilisation_by_trips` cannot -- it lives in MODULAR_UNCORRECTABLE
    instead). Only when `modular=True` AND the event path ran (the caller only
    invokes this from inside the `recon is not None` branch).

    `drt_tour_hours_total_pax` is a plain subtraction, emitted whenever there is
    freight time to subtract. The other three are RATIOS whose denominator is
    the active window, so removing the freight window means RESCALING by
    tour_h / (tour_h - freight_h) -- valid only because an excursion is
    occupancy-0 throughout (D2's passenger lockout), so none of the freight
    window's time was ever counted as passenger-carrying to begin with. Guard:
    the three rescaled rows are omitted (not clamped) when tour_h does not
    exceed freight_h -- a fleet whose freight hours meet or exceed its active
    span cannot be rescaled into a meaningful ratio.
    """
    if "tour_s" not in fl:
        return []
    freight_h = fl.get("freight_s", 0.0) / 3600.0
    if freight_h <= 0:
        return []
    tour_h = fl["tour_s"] / 3600.0

    rows = [row("system", "drt_tour_hours_total_pax", tour_h - freight_h, "h",
                 "events(corrected: freight window removed)")]

    if tour_h <= freight_h:
        return rows

    rescale = tour_h / (tour_h - freight_h)
    rows.append(row("system", "service_ratio_active_pax",
                     fl["ratio_active"] * rescale, "share",
                     "events(corrected: freight window removed)"))
    if fl.get("capacity"):
        rows.append(row("system", "fleet_utilisation_by_time_pax",
                         fl["util_by_time"] * rescale, "share",
                         "events(corrected: freight window removed)"))
    rows.append(row("passenger", "mean_pax_aboard_pax",
                     mean_pax_aboard * rescale, "pax",
                     "events(corrected: freight window removed)"))
    return rows
