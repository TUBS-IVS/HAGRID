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
"""
import re
from dataclasses import dataclass, field
from pathlib import Path

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
