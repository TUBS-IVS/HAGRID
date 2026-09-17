# -*- coding: utf-8 -*-
"""Structured, single-pass parse of the freight events cache written by
events_cache.ensure_caches() (the ".freight_events_filtered.txt" file).

Activity events (actstart/actend) key on the `person` attribute -- the
freight driver person id is identical to the event_vehicle_id, format
freight_<carrier>_veh_<veh>_<tour>. Link events (`entered link`) key on the
`vehicle` attribute. Both are pre-filtered to ids containing "freight" by
the cache writer, but the cache is non-exclusive (a line matching both the
drt_ and freight_ predicates lands in both caches), so a non-freight vehicle
id (e.g. a shared drt_freight_* DRT vehicle) can legitimately appear here
too -- it is kept under its own key, not merged or dropped.

Consumed by timeseries (hourly series) and maps (link sequences / tours) in
later Plan D tasks.

`hourly_series()` (Task 3) builds the event-derived freight hourly rows
(shaped like timeseries._ts's {series, hour, value, unit} dicts) consumed
by build_kpis.build() and appended to kpi_timeseries.csv:
  - freight_parcels_h_<provider> (parcels/h): integer `hour` bucket, ported
    verbatim from the Java buildParcelsPerHourByProviderJson.
  - freight_depot_departures / freight_depot_arrivals (vehicles/h): integer
    `hour` bucket, counted over ALL vehicles in fev with no per-provider
    grouping and no exclusion filter.
  - freight_active_vehicles_<provider> (vehicles): 5-minute (1/12-hour)
    sampling of a vehicle active in the half-open interval
    [first_departure, last_arrival) -- unlike every other series here, its
    `hour` field is a FRACTIONAL hour (t/3600, e.g. 8.0, 8.0833, ...), not
    an integer bucket; render treats it as a plain x value.
"""
import re
from dataclasses import dataclass, field
from pathlib import Path

import freight_classify

RE_TIME = re.compile(r'time="([0-9.]+)"')
RE_PERSON = re.compile(r'person="([^"]+)"')
RE_VEHICLE = re.compile(r'\bvehicle="([^"]+)"')
RE_LINK = re.compile(r'link="([^"]+)"')
RE_ACT_TYPE = re.compile(r'actType="([^"]+)"')


@dataclass
class FreightEvents:
    service_starts: dict = field(default_factory=dict)
    depot_departures: dict = field(default_factory=dict)
    depot_arrivals: dict = field(default_factory=dict)
    veh_links: dict = field(default_factory=dict)


def parse_freight_cache(cache_path):
    fev = FreightEvents()
    with open(cache_path, "r", encoding="utf-8") as f:
        for line in f:
            m_time = RE_TIME.search(line)
            if not m_time:
                continue
            time = float(m_time.group(1))

            if 'type="entered link"' in line:
                m_veh = RE_VEHICLE.search(line)
                m_link = RE_LINK.search(line)
                if not (m_veh and m_link and "freight" in m_veh.group(1)):
                    continue
                fev.veh_links.setdefault(m_veh.group(1), []).append((m_link.group(1), time))
                continue

            m_person = RE_PERSON.search(line)
            if not (m_person and "freight" in m_person.group(1)):
                continue
            person = m_person.group(1)
            m_act = RE_ACT_TYPE.search(line)
            act_type = m_act.group(1) if m_act else None

            if 'type="actstart"' in line and act_type == "service":
                fev.service_starts.setdefault(person, []).append(time)
            elif 'type="actend"' in line and act_type == "start":
                fev.depot_departures.setdefault(person, []).append(time)
            elif 'type="actstart"' in line and act_type == "end":
                fev.depot_arrivals.setdefault(person, []).append(time)

    for times in fev.service_starts.values():
        times.sort()
    return fev


def parcels_per_hour_by_provider(fev, carriers, excluded):
    """Ported verbatim from the Java DashboardGenerator.
    buildParcelsPerHourByProviderJson: 1:1 zip of service-start times to
    tour stop demands (by stop order) when counts match; otherwise spreads
    the tour's total demand evenly over the available starts so the total
    parcel count is conserved even when a start event is missing."""
    out = {}  # provider -> {hour: parcels}
    for c in carriers:
        prov = freight_classify.provider_of(c.carrier_id, c.attrs.get("provider"))
        demand = {sid: s.capacity_demand for sid, s in c.services.items()}
        for t in c.tours:
            vid = t.event_vehicle_id(c.carrier_id)
            if vid in excluded:
                continue
            stop_demands = [demand.get(sid, 1) for sid in t.service_ids]
            starts = fev.service_starts.get(vid, [])
            if not stop_demands or not starts:
                continue
            bins = out.setdefault(prov, {})
            if len(starts) == len(stop_demands):        # 1:1 zip by stop order
                for st, d in zip(starts, stop_demands):
                    h = min(24, int(st // 3600)); bins[h] = bins.get(h, 0) + d
            else:                                        # mismatch: spread, conserve total
                per = sum(stop_demands) / len(starts)
                for st in starts:
                    h = min(24, int(st // 3600)); bins[h] = bins.get(h, 0) + per
    return out


def _depot_hour_counts(times_by_vehicle):
    """Hourly count of depot events over ALL vehicles (no per-provider
    grouping, no exclusion filter -- per Task 3 brief: 'over all vehicles').
    `times_by_vehicle` is one of fev.depot_departures / fev.depot_arrivals."""
    counts = {}
    for times in times_by_vehicle.values():
        for t in times:
            h = min(24, int(t // 3600))
            counts[h] = counts.get(h, 0) + 1
    return counts


def active_vehicles_by_provider(fev, carriers, excluded):
    """5-minute (1/12-hour) sampling of active-vehicle counts per provider.
    A vehicle is active in the half-open interval
    [first_departure, last_arrival) -- resolved via the same carrier/tour
    walk as parcels_per_hour_by_provider (skips `excluded`). Returned hours
    are FRACTIONAL (t/3600), not integer buckets."""
    out = {}  # provider -> {fractional_hour: active_count}
    for c in carriers:
        prov = freight_classify.provider_of(c.carrier_id, c.attrs.get("provider"))
        for t in c.tours:
            vid = t.event_vehicle_id(c.carrier_id)
            if vid in excluded:
                continue
            deps = fev.depot_departures.get(vid)
            arrs = fev.depot_arrivals.get(vid)
            if not deps or not arrs:
                continue
            start_h = min(deps) / 3600.0
            end_h = max(arrs) / 3600.0
            n_steps = int(round((end_h - start_h) * 12))
            if n_steps <= 0:
                continue
            bins = out.setdefault(prov, {})
            for i in range(n_steps):
                h = round(start_h + i / 12.0, 6)
                bins[h] = bins.get(h, 0) + 1
    return out


def hourly_series(fev, carriers, excluded):
    """Event-derived freight hourly rows for kpi_timeseries.csv -- see the
    module docstring for the series produced and the fractional-hour
    convention used by freight_active_vehicles_<provider>."""
    rows = []

    parcels = parcels_per_hour_by_provider(fev, carriers, excluded)
    for prov in sorted(parcels):
        series = "freight_parcels_h_" + prov
        for h in sorted(parcels[prov]):
            rows.append({"series": series, "hour": h, "value": parcels[prov][h],
                         "unit": "parcels/h"})

    dep_counts = _depot_hour_counts(fev.depot_departures)
    for h in sorted(dep_counts):
        rows.append({"series": "freight_depot_departures", "hour": h,
                     "value": dep_counts[h], "unit": "vehicles/h"})

    arr_counts = _depot_hour_counts(fev.depot_arrivals)
    for h in sorted(arr_counts):
        rows.append({"series": "freight_depot_arrivals", "hour": h,
                     "value": arr_counts[h], "unit": "vehicles/h"})

    active = active_vehicles_by_provider(fev, carriers, excluded)
    for prov in sorted(active):
        series = "freight_active_vehicles_" + prov
        for h in sorted(active[prov]):
            rows.append({"series": series, "hour": h, "value": active[prov][h],
                         "unit": "vehicles"})

    return rows
