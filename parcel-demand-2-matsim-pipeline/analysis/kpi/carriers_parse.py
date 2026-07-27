# -*- coding: utf-8 -*-
"""Pure streaming parser for MATSim carriers.xml.gz / carriersVehicleTypes.xml.gz.

No aggregation, no classification -- this module only turns the freight-supply
XML into plain dataclasses (that is Task 3's job). Namespace-agnostic tag-suffix
matching mirrors the existing idiom in extract_freight._carrier_attrs so real
married250 runs (default xmlns) and the namespace-free mini_lmd fixtures both
parse the same way.
"""
from __future__ import annotations  # PEP 563: keep `list[str]` etc. lazy for Python 3.8 (sim-PC)

import gzip
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from pathlib import Path


@dataclass
class ServiceDef:
    service_id: str
    capacity_demand: int


@dataclass
class VehicleDef:
    vehicle_id: str
    type_id: str


@dataclass
class TourDef:
    vehicle_id: str
    tour_id: str
    service_ids: list[str] = field(default_factory=list)

    def event_vehicle_id(self, carrier_id):
        return "freight_" + carrier_id + "_veh_" + self.vehicle_id + "_" + self.tour_id


@dataclass
class CarrierDef:
    carrier_id: str
    attrs: dict[str, str] = field(default_factory=dict)
    services: dict[str, ServiceDef] = field(default_factory=dict)
    vehicles: dict[str, VehicleDef] = field(default_factory=dict)
    tours: list[TourDef] = field(default_factory=list)
    selected_plan_score: float | None = None


@dataclass
class VehTypeDef:
    type_id: str
    capacity: float
    fixed_cost_per_day: float
    costs_per_meter: float


def attr_float(attrs, name, default=0.0):
    v = attrs.get(name)
    try:
        return float(v)
    except (TypeError, ValueError):
        return default


def attr_int(attrs, name, default=0):
    v = attrs.get(name)
    try:
        return int(float(v))
    except (TypeError, ValueError):
        return default


def _carrier_attrs(carrier_el):
    """Carrier attrs from the carrier's OWN <attributes> child only -- do NOT
    use el.iter(), which would also pick up nested <service>/<costInformation>
    <attribute> elements."""
    out = {}
    for child in carrier_el:
        if child.tag.endswith("attributes"):
            for a in child:
                if a.tag.endswith("attribute"):
                    out[a.get("name")] = (a.text or "").strip()
    return out


def _carrier_services(carrier_el):
    out = {}
    for child in carrier_el:
        if child.tag.endswith("services"):
            for s in child:
                if s.tag.endswith("service"):
                    sid = s.get("id")
                    out[sid] = ServiceDef(
                        service_id=sid,
                        capacity_demand=attr_int(s.attrib, "capacityDemand", default=1),
                    )
    return out


def _carrier_vehicles(carrier_el):
    out = {}
    for child in carrier_el:
        if child.tag.endswith("capabilities"):
            for vs in child:
                if vs.tag.endswith("vehicles"):
                    for v in vs:
                        if v.tag.endswith("vehicle"):
                            vid = v.get("id")
                            out[vid] = VehicleDef(vehicle_id=vid, type_id=v.get("typeId"))
    return out


def _selected_plan(carrier_el):
    plans_el = None
    for child in carrier_el:
        if child.tag.endswith("plans"):
            plans_el = child
            break
    if plans_el is None:
        return None

    all_plans = [p for p in plans_el if p.tag.endswith("plan")]
    if not all_plans:
        return None
    for p in all_plans:
        if (p.get("selected") or "").lower() == "true":
            return p
    if len(all_plans) == 1:
        return all_plans[0]
    return None


def _selected_plan_score(carrier_el):
    """Selected plan's `score` attr as float, or None when absent/unparseable."""
    plan_el = _selected_plan(carrier_el)
    if plan_el is None:
        return None
    v = plan_el.get("score")
    if v is None:
        return None
    try:
        return float(v)
    except (TypeError, ValueError):
        return None


def _carrier_tours(carrier_el):
    tours = []
    plan_el = _selected_plan(carrier_el)
    if plan_el is None:
        return tours

    for idx, tour_el in enumerate(t for t in plan_el if t.tag.endswith("tour")):
        vehicle_id = tour_el.get("vehicleId")
        tour_id = tour_el.get("tourId")
        if tour_id is None:
            tour_id = str(idx)
        service_ids = []
        for act in tour_el:
            if act.tag.endswith("act") and act.get("type") == "service":
                sid = act.get("serviceId")
                if sid is not None:
                    service_ids.append(sid)
        tours.append(TourDef(vehicle_id=vehicle_id, tour_id=tour_id, service_ids=service_ids))
    return tours


def parse_carriers(carriers_xml_gz: Path) -> list[CarrierDef]:
    """Stream-parse carriers.xml.gz -> list[CarrierDef]. Namespace-agnostic
    (tag-suffix matching); parses the selected <plan> only."""
    out = []
    with gzip.open(Path(carriers_xml_gz), "rb") as f:
        for _, el in ET.iterparse(f):
            if el.tag.endswith("carrier"):
                out.append(CarrierDef(
                    carrier_id=el.get("id"),
                    attrs=_carrier_attrs(el),
                    services=_carrier_services(el),
                    vehicles=_carrier_vehicles(el),
                    tours=_carrier_tours(el),
                    selected_plan_score=_selected_plan_score(el),
                ))
                el.clear()
    return out


def parse_vehicle_types(vtypes_xml_gz: Path) -> dict[str, VehTypeDef]:
    """Stream-parse carriersVehicleTypes.xml.gz -> {type_id: VehTypeDef}."""
    out = {}
    with gzip.open(Path(vtypes_xml_gz), "rb") as f:
        for _, el in ET.iterparse(f):
            if el.tag.endswith("vehicleType"):
                type_id = el.get("id")
                capacity = 0.0
                fixed_cost_per_day = 0.0
                costs_per_meter = 0.0
                for child in el:
                    if child.tag.endswith("capacity"):
                        capacity = attr_float(child.attrib, "other")
                    elif child.tag.endswith("costInformation"):
                        fixed_cost_per_day = attr_float(child.attrib, "fixedCostsPerDay")
                        costs_per_meter = attr_float(child.attrib, "costsPerMeter")
                out[type_id] = VehTypeDef(
                    type_id=type_id,
                    capacity=capacity,
                    fixed_cost_per_day=fixed_cost_per_day,
                    costs_per_meter=costs_per_meter,
                )
                el.clear()
    return out
