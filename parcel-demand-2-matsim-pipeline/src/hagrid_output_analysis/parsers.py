"""
HAGRID Output Analysis – Event & Carrier Parsers
==================================================

**Key optimisation:** Event parsing is now **single-pass**.  The
previous implementation read the events file *twice* with different
type filters.  This version reads it once, collecting link traversals,
service boundaries, and tour start/end events simultaneously.

All functions return plain data structures.  Network merging (which
requires the ``network`` GeoDataFrame) is deliberately **not** done
here; the caller is responsible for that join.
"""

from __future__ import annotations

import gzip
import json
import xml.etree.ElementTree as ET
from collections import defaultdict
from typing import Any

import matsim
import pandas as pd

from hagrid_output_analysis.config import DAYEND
from hagrid_output_analysis.models import Carrier, Plan, Service, Vehicle


# ====================================================================
# Event parsing (SINGLE PASS)
# ====================================================================

def _classify_vehicle(vehicle: str) -> str | None:
    """Return the count-column key, or *None* if not freight."""
    if "_Supply_Vehicle_" in vehicle or "_veh_supply_" in vehicle:
        if "supply_light_van" in vehicle:
            return "supply_van"
        if "light" in vehicle:
            return "truck_light_count"
        return "truck_count"
    if (
        "_CEP_Vehicle_" in vehicle
        or "_veh_cep_" in vehicle
        or "_egrocery_van_" in vehicle
    ):
        return "van_count"
    if "_cargoBike_" in vehicle or "_cargobike_" in vehicle:
        return "bike_count"
    return None


_FREIGHT_KEYWORDS: tuple[str, ...] = (
    "_Supply_Vehicle_", "_veh_supply_",
    "_CEP_Vehicle_", "_veh_cep_",
    "_egrocery_van_",
    "_cargoBike_", "_cargobike_",
)


def parse_events(
    event_file: str,
    dayend: int = DAYEND,
) -> dict[str, Any]:
    """**Single-pass** event parser.

    Reads ``left link``, ``actstart``, and ``actend`` events in one
    pass and returns all data previously gathered by ``parse_events``
    *and* ``create_plot_data``.

    Returns
    -------
    dict with keys:
        vehicle_tour : dict[str, list[tuple[str, float]]]
        service_events : dict[str, list[str]]
        link_counts : DataFrame  (link_id as index, count columns)
        nr_events : int
        tour_start_events : list[dict]   – actend where actType == 'start'
        tour_end_events : list[dict]     – actstart where actType == 'end'
        service_start_events : list[dict] – actstart where actType == 'service'
        service_end_events : list[dict]   – actend where actType == 'service'
    """
    events = matsim.event_reader(
        event_file, types="left link,actstart,actend",
    )

    link_counts: dict[str, dict[str, int]] = defaultdict(lambda: defaultdict(int))
    vehicle_tour: dict[str, list[tuple[str, float]]] = defaultdict(list)
    service_events: dict[str, list[str]] = defaultdict(list)
    last_link: dict[str, str] = {}

    # For plot_data (previously a second pass)
    tour_start_events: list[dict] = []
    tour_end_events: list[dict] = []
    service_start_events: list[dict] = []
    service_end_events: list[dict] = []

    nr_events = 0

    for ev in events:
        if ev["time"] > dayend:
            continue
        nr_events += 1
        etype = ev["type"]

        if etype == "left link":
            vehicle = ev["vehicle"]
            link = ev["link"]
            time = ev["time"]
            vtype = _classify_vehicle(vehicle)
            if vtype is None:
                continue

            # De-duplicate consecutive same-link entries
            if link != last_link.get(vehicle):
                link_counts[link][vtype] += 1
                vehicle_tour[vehicle].append((link, time))
                last_link[vehicle] = link

                # sub-size counting for vans
                if vtype == "van_count":
                    if "size_m" in vehicle:
                        link_counts[link]["m_count"] += 1
                    elif "size_xl" in vehicle:
                        link_counts[link]["xl_count"] += 1

        elif etype == "actstart":
            person = ev.get("person", "")
            act = ev.get("actType", "")

            if act == "end" and any(kw in person for kw in _FREIGHT_KEYWORDS):
                vehicle_tour[person].append((ev["link"], ev["time"]))
                tour_end_events.append(ev)

            elif act == "service":
                if "_supply_" not in person:
                    service_start_events.append(ev)
                service_events[person].append(ev["link"])

        elif etype == "actend":
            person = ev.get("person", "")
            act = ev.get("actType", "")

            if act == "start" and "_supply_" not in person:
                tour_start_events.append(ev)

            elif act == "service" and "_supply_" not in person:
                service_end_events.append(ev)

    # Build link-counts DataFrame
    df = pd.DataFrame(link_counts).T
    df.index.name = "link_id"
    df.fillna(0, inplace=True)

    for col in ("truck_count", "truck_light_count", "supply_van", "van_count", "bike_count"):
        if col not in df.columns:
            df[col] = 0

    df["total_count"] = (
        df["truck_count"] + df["truck_light_count"]
        + df.get("supply_van", 0) + df["van_count"] + df["bike_count"]
    )
    df["total_count_supply"] = (
        df["truck_count"] + df["truck_light_count"] + df.get("supply_van", 0)
    )

    return {
        "vehicle_tour": dict(vehicle_tour),
        "service_events": dict(service_events),
        "link_counts": df,
        "nr_events": nr_events,
        "tour_start_events": tour_start_events,
        "tour_end_events": tour_end_events,
        "service_start_events": service_start_events,
        "service_end_events": service_end_events,
    }


def merge_link_counts_with_network(
    link_counts: pd.DataFrame,
    network: Any,
) -> Any:
    """Left-join *link_counts* onto *network* GeoDataFrame."""
    nv = network.merge(link_counts, left_on="link_id", right_index=True, how="left")
    nv.fillna(0, inplace=True)
    return nv


# ====================================================================
# Plot-data builder (from pre-collected events)
# ====================================================================

def build_plot_data(
    vehicles: list[str],
    tour_start_events: list[dict],
    tour_end_events: list[dict],
    service_start_events: list[dict],
    service_end_events: list[dict],
) -> tuple[pd.DataFrame, Any, Any]:
    """Build per-vehicle timing DataFrame from already-parsed events.

    This replaces the old ``create_plot_data`` which re-read the
    events file a second time.

    Returns
    -------
    plot_data : DataFrame
    startG : DataFrameGroupBy  (service starts by person)
    endG : DataFrameGroupBy    (service ends by person)
    """
    # Tour start / end
    df_tour_start = pd.DataFrame(tour_start_events)
    df_tour_end = pd.DataFrame(tour_end_events)

    veh_set = set(vehicles)
    plot_rows: list[dict] = []
    for vid in vehicles:
        start_row = (
            df_tour_start[df_tour_start["person"] == vid] if not df_tour_start.empty else pd.DataFrame()
        )
        end_row = (
            df_tour_end[df_tour_end["person"] == vid] if not df_tour_end.empty else pd.DataFrame()
        )
        start_t = float(start_row.iloc[0]["time"]) if not start_row.empty else 0.0
        end_t = float(end_row.iloc[0]["time"]) if not end_row.empty else 0.0
        plot_rows.append({
            "vehicle_id": vid,
            "Start time": start_t,
            "End time": end_t,
            "Tour Duration": end_t - start_t,
            "Service Duration": 0.0,
            "Travel Duration": 0.0,
        })

    plot_data = pd.DataFrame(plot_rows)

    # Service events grouped
    df_svc_start = pd.DataFrame(service_start_events)
    df_svc_end = pd.DataFrame(service_end_events)

    if not df_svc_start.empty and not df_svc_end.empty:
        startG = df_svc_start.groupby("person")
        endG = df_svc_end.groupby("person")

        for vid in vehicles:
            try:
                g_s = startG.get_group(vid).reset_index(drop=True)
                g_e = endG.get_group(vid).reset_index(drop=True)
            except KeyError:
                continue
            merged = pd.merge(g_s, g_e, left_index=True, right_index=True)
            svc_dur = (merged["time_y"] - merged["time_x"]).sum()
            mask = plot_data["vehicle_id"] == vid
            plot_data.loc[mask, "Service Duration"] = abs(svc_dur)
    else:
        startG = pd.DataFrame().groupby([])
        endG = pd.DataFrame().groupby([])

    plot_data["Travel Duration"] = (
        plot_data["Tour Duration"] - plot_data["Service Duration"]
    )

    for col in ("Start time", "End time", "Tour Duration",
                "Service Duration", "Travel Duration"):
        plot_data[f"{col} formatted"] = pd.to_datetime(plot_data[col], unit="s")

    return plot_data, startG, endG


# ====================================================================
# Carrier XML parsing
# ====================================================================

def parse_carriers_from_xml(
    source: str | ET.Element,
) -> list[Carrier]:
    """Parse carriers from a gzipped XML or an already-parsed root element.

    Parameters
    ----------
    source : str or Element
        If ``str``, interpreted as a file path (gzipped XML).
        If ``Element``, used directly as the XML root.

    Returns
    -------
    list[Carrier]
    """
    if isinstance(source, str):
        with gzip.open(source, mode="rt") as fh:
            tree = ET.parse(fh)
            root = tree.getroot()
    else:
        root = source

    ns = {"m": "http://www.matsim.org/files/dtd"}
    carriers: list[Carrier] = []

    for carrier_xml in root.findall("m:carrier", ns):
        carrier_id = carrier_xml.attrib.get("id", "")
        if "supply" in carrier_id:
            continue

        carrier = Carrier(carrier_id)

        # Missed deliveries
        for attr_block in carrier_xml.findall("m:attributes", ns):
            for attr in attr_block.findall("m:attribute", ns):
                if attr.attrib.get("name") == "missedParcelDeliveriesAsString":
                    raw = (attr.text or "").replace("[", "").replace("]", "").replace(" ", "")
                    carrier.missed_deliveries = [s for s in raw.split(",") if s]

        # Services
        for svc_xml in carrier_xml.findall(".//m:service", ns):
            sid = svc_xml.attrib.get("id", "")
            demand = int(svc_xml.attrib.get("capacityDemand", 0))
            dur_raw = svc_xml.attrib.get("serviceDuration", "0")
            try:
                if ":" in dur_raw:
                    h, m, s = map(int, dur_raw.split(":"))
                    dur = h * 3600 + m * 60 + s
                else:
                    dur = int(dur_raw)
            except (ValueError, TypeError):
                dur = 0
            svc_link = svc_xml.attrib.get("to", "")

            known_keys = {"id", "capacityDemand", "serviceDuration", "to"}
            extra: dict[str, Any] = {
                k: v for k, v in svc_xml.attrib.items() if k not in known_keys
            }
            attrs_el = svc_xml.find("m:attributes", ns)
            if attrs_el is not None:
                for a in attrs_el.findall("m:attribute", ns):
                    key = a.attrib.get("name", "")
                    val: Any = a.text
                    if key in ("b2b", "b2c"):
                        try:
                            val = int(val)  # type: ignore[arg-type]
                        except (ValueError, TypeError):
                            val = 0
                    extra[key] = val

            carrier.add_service(
                Service("service", sid, demand, dur, svc_link, extra)
            )

        # Plans → Vehicles
        for plan_xml in carrier_xml.findall(".//m:plan", ns):
            if plan_xml.attrib.get("selected") != "true":
                continue
            for tour_idx, tour_xml in enumerate(plan_xml.findall(".//m:tour", ns), 1):
                veh_id = f"{tour_xml.attrib.get('vehicleId', '')}_{tour_idx}"
                vehicle = Vehicle(veh_id, "cep")

                activities: dict[str, Any] = {}
                legs: dict[str, Any] = {}
                routes: dict[str, Any] = {}

                for act in tour_xml.findall(".//m:act", ns):
                    act_type = act.attrib.get("type", "")
                    if act_type in ("start", "end"):
                        activities[act_type] = act
                    else:
                        activities[act.attrib.get("serviceId", act_type)] = act

                for li, leg in enumerate(tour_xml.findall(".//m:leg", ns)):
                    legs[f"leg_{li}"] = leg

                for ri, route in enumerate(tour_xml.findall(".//m:route", ns)):
                    routes[f"route_{ri}"] = (
                        route.text.split(" ") if route.text else None
                    )

                for leg_key, leg_el in legs.items():
                    route_key = f"route_{leg_key.split('_')[1]}"
                    leg_el.attrib["route"] = routes.get(route_key)

                plan = Plan(f"plan_{veh_id}", vehicle.vehicle_id, activities, legs)
                plan.build_sequence()
                vehicle.add_plan(plan)
                carrier.add_vehicle(vehicle)

        carriers.append(carrier)

    return carriers


# ====================================================================
# Vehicle-type XML parsing
# ====================================================================

def parse_vehicle_types(
    source: str | ET.Element,
) -> dict[str, dict[str, Any]]:
    """Parse ``output_carriersVehicleTypes.xml.gz`` into a lookup dict.

    Parameters
    ----------
    source : str or Element
        File path (gzipped XML) or already-parsed root element.

    Returns
    -------
    dict mapping ``type_id`` → ``{capacity, fixed_cost, cost_per_km,
    cost_per_sec, cost_per_sec_waiting, cost_per_sec_service,
    length_m, max_velocity_mps, skills}``.
    """
    if isinstance(source, str):
        with gzip.open(source, mode="rt") as fh:
            tree = ET.parse(fh)
            root = tree.getroot()
    else:
        root = source

    ns = {"m": "http://www.matsim.org/files/dtd"}
    vtypes: dict[str, dict[str, Any]] = {}

    for vt_xml in root.findall("m:vehicleType", ns):
        tid = vt_xml.attrib.get("id", "")

        # Capacity
        cap_el = vt_xml.find("m:capacity", ns)
        capacity = float(cap_el.attrib.get("other", 0)) if cap_el is not None else 0.0

        # Physical dimensions
        len_el = vt_xml.find("m:length", ns)
        length_m = float(len_el.attrib.get("meter", 0)) if len_el is not None else 0.0

        vel_el = vt_xml.find("m:maximumVelocity", ns)
        max_vel = float(vel_el.attrib.get("meterPerSecond", 0)) if vel_el is not None else 0.0

        # Cost information
        cost_el = vt_xml.find("m:costInformation", ns)
        fixed_cost = 0.0
        cost_per_m = 0.0
        cost_per_sec = 0.0
        cost_per_sec_waiting = 0.0
        cost_per_sec_service = 0.0

        if cost_el is not None:
            fixed_cost = float(cost_el.attrib.get("fixedCostsPerDay", 0))
            cost_per_m = float(cost_el.attrib.get("costsPerMeter", 0))
            cost_per_sec = float(cost_el.attrib.get("costsPerSecond", 0))

            for attr_el in cost_el.findall(".//m:attribute", ns):
                name = attr_el.attrib.get("name", "")
                if name == "costsPerSecondWaiting":
                    cost_per_sec_waiting = float(attr_el.text or 0)
                elif name == "costsPerSecondInService":
                    cost_per_sec_service = float(attr_el.text or 0)

        # Skills
        skills = ""
        attrs_el = vt_xml.find("m:attributes", ns)
        if attrs_el is not None:
            for a in attrs_el.findall("m:attribute", ns):
                if a.attrib.get("name") == "skills":
                    skills = a.text or ""

        vtypes[tid] = {
            "capacity": int(capacity),
            "fixed_cost": fixed_cost,
            "cost_per_km": cost_per_m * 1000.0,
            "cost_per_sec": cost_per_sec,
            "cost_per_sec_waiting": cost_per_sec_waiting,
            "cost_per_sec_service": cost_per_sec_service,
            "length_m": length_m,
            "max_velocity_mps": max_vel,
            "skills": skills,
        }

    return vtypes


def resolve_vehicle_type(vehicle_id: str, vtypes: dict[str, dict]) -> dict | None:
    """Find the vehicle-type entry for a full vehicle ID.

    The carrier XML vehicle IDs look like ``cep_size_m_12`` and the
    type IDs are ``ct_cep_size_m``.  We strip the trailing numeric
    suffix and prepend ``ct_`` to build the type key.
    """
    # freight_dpd_30419_veh_cep_size_m_8_1 → extract "cep_size_m_8_1"
    parts = vehicle_id.split("_veh_", 1)
    if len(parts) < 2:
        return None
    veh_part = parts[1]  # e.g. "cep_size_m_8_1"
    # Remove last two numeric segments (fleet-id + tour-index)
    tokens = veh_part.split("_")
    # Find where the size code is: "cep_size_<code>_..."
    try:
        size_idx = tokens.index("size") + 1
        base = "_".join(tokens[: size_idx + 1])  # "cep_size_m"
    except ValueError:
        # Fallback: try prefix match
        for tid in vtypes:
            if tid.startswith("ct_") and tid[3:] in veh_part:
                return vtypes[tid]
        return None

    type_key = f"ct_{base}"
    return vtypes.get(type_key)

