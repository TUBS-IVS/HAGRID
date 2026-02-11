"""
HAGRID Output Analysis – Domain Models
========================================

Pure-Python data containers that mirror the MATSim carrier / vehicle /
plan / service XML structure.

Design decisions
----------------
* **``@dataclass(slots=True)``** – memory-efficient, thousands of
  instances per run.
* **No Java-style getters/setters** – attributes are accessed directly.
* All ``__repr__`` outputs are concise and useful for debugging.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from typing import Any

from hagrid_output_analysis.utils import extract_provider

# ====================================================================
# Service
# ====================================================================


@dataclass(slots=True)
class Service:
    """A single delivery or pick-up service.

    Attributes
    ----------
    service_type : str
        Typically ``"service"``.
    service_id : str
        Unique identifier in the carrier plan.
    capacity_demand : int
        Parcels (or weight units) requested.
    duration : int
        Dwell time at the stop in **seconds**.
    link : str
        MATSim link ID where this service takes place.
    extra : dict
        Arbitrary key/value pairs from the XML
        (b2b, b2c, mergedMetadata, ...).
    """

    service_type: str
    service_id: str
    capacity_demand: int
    duration: int
    link: str
    extra: dict[str, Any] = field(default_factory=dict)

    def __post_init__(self) -> None:
        self.capacity_demand = int(self.capacity_demand)
        try:
            self.duration = int(self.duration)
        except (TypeError, ValueError):
            self.duration = 0

    def __repr__(self) -> str:
        return (
            f"Service({self.service_id!r}, demand={self.capacity_demand}, "
            f"link={self.link!r})"
        )

    # -- attribute helpers -------------------------------------------

    def get_attr(self, key: str, default: Any = None) -> Any:
        """Return an extra attribute or *default*."""
        return self.extra.get(key, default)

    def set_attr(self, key: str, value: Any) -> None:
        """Store an extra attribute."""
        self.extra[key] = value

    # Backward-compatible aliases used by the analysis pipeline.
    get_attribute = get_attr
    set_attribute = set_attr
    get_service_id = lambda self: self.service_id  # noqa: E731
    get_service_link = lambda self: self.link  # noqa: E731
    get_demand = lambda self: self.capacity_demand  # noqa: E731
    get_duration = lambda self: self.duration  # noqa: E731


# ====================================================================
# Vehicle
# ====================================================================


@dataclass(slots=True)
class Vehicle:
    """A MATSim freight vehicle.

    Attributes
    ----------
    vehicle_id : str
        Unique vehicle identifier.
    vehicle_type : str
        Short type tag (``"cep"``, ``"supply"``, ...).
    plans : list[Plan]
        List of plans (usually exactly one after MATSim output).
    """

    vehicle_id: str
    vehicle_type: str
    plans: list[Plan] = field(default_factory=list, repr=False)

    def __repr__(self) -> str:
        return f"Vehicle({self.vehicle_id!r}, type={self.vehicle_type!r})"

    def add_plan(self, plan: Plan) -> None:
        self.plans.append(plan)


# ====================================================================
# Plan
# ====================================================================


@dataclass(slots=True)
class Plan:
    """Ordered sequence of activities and legs making up a tour.

    Attributes
    ----------
    plan_id : str
        ``"plan_<vehicle_id>"``.
    vehicle : str
        Vehicle ID that performs this plan.
    activities : dict
        ``{act_key: xml_element}``.
    legs : dict
        ``{leg_key: xml_element}``.
    plan_sequence : list
        Interleaved activity/leg keys (populated by
        :meth:`build_sequence`).
    """

    plan_id: str
    vehicle: str
    activities: dict = field(default_factory=dict, repr=False)
    legs: dict = field(default_factory=dict, repr=False)
    plan_sequence: list = field(default_factory=list, repr=False)

    def __repr__(self) -> str:
        return (
            f"Plan({self.plan_id!r}, "
            f"acts={len(self.activities)}, legs={len(self.legs)})"
        )

    def build_sequence(self) -> None:
        """Build an ordered list alternating activities and legs."""
        keys_act = list(self.activities)
        keys_leg = list(self.legs)
        seq: list = []
        for i in range(len(keys_leg)):
            seq.append(keys_act[i])
            seq.append(keys_leg[i])
        if keys_act:
            seq.append(keys_act[-1])
        self.plan_sequence = seq

    # Backward-compatible alias.
    create_internal_plan_sequence = build_sequence


# ====================================================================
# Carrier
# ====================================================================


@dataclass(slots=True)
class Carrier:
    """Logistics carrier (DHL, DPD, ...) with vehicles and services.

    Attributes
    ----------
    carrier_id : str
        Unique carrier ID as it appears in the MATSim XML.
    provider : str
        Normalised logistic-provider name (lowercase).
    vehicles : dict[str, Vehicle]
        Vehicles keyed by vehicle ID.
    services : dict[str, Service]
        Services keyed by service ID.
    missed_deliveries : list[str]
        Service IDs that were not delivered.
    """

    carrier_id: str
    provider: str = field(init=False)
    vehicles: dict[str, Vehicle] = field(default_factory=dict, repr=False)
    services: dict[str, Service] = field(default_factory=dict, repr=False)
    missed_deliveries: list[str] = field(default_factory=list, repr=False)

    def __post_init__(self) -> None:
        self.provider = extract_provider(self.carrier_id)

    def __repr__(self) -> str:
        return (
            f"Carrier({self.carrier_id!r}, "
            f"vehs={len(self.vehicles)}, svcs={len(self.services)})"
        )

    def add_vehicle(self, vehicle: Vehicle) -> None:
        self.vehicles[vehicle.vehicle_id] = vehicle

    def add_service(self, service: Service) -> None:
        self.services[service.service_id] = service

    @property
    def total_demand(self) -> int:
        return sum(s.capacity_demand for s in self.services.values())

    @property
    def num_vehicles(self) -> int:
        return len(self.vehicles)

    @property
    def num_services(self) -> int:
        return len(self.services)
