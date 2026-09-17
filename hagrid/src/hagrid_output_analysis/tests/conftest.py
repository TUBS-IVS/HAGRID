"""Shared fixtures for hagrid_output_analysis tests."""

from __future__ import annotations

import pytest

from hagrid_output_analysis.models import Carrier, Plan, Service, Vehicle


# ====================================================================
# Service fixtures
# ====================================================================

@pytest.fixture
def sample_service() -> Service:
    """A minimal Service with B2B/B2C extras."""
    return Service(
        service_type="service",
        service_id="service_001",
        capacity_demand=7,
        duration=360,
        link="link_42",
        extra={"b2b": 3, "b2c": 4},
    )


@pytest.fixture
def service_with_merged() -> Service:
    """Service carrying mergedMetadata JSON."""
    import json

    merged = {
        "sub_svc_A": {"capacity": 2, "weights": [1.0, 1.5]},
        "sub_svc_B": {"capacity": 3, "weights": "[1.0, 2.0, 3.0]"},
    }
    return Service(
        service_type="service",
        service_id="service_merged",
        capacity_demand=5,
        duration=300,
        link="link_99",
        extra={"b2b": 2, "b2c": 3, "mergedMetadata": json.dumps(merged)},
    )


# ====================================================================
# Vehicle / Plan / Carrier fixtures
# ====================================================================

@pytest.fixture
def sample_vehicle() -> Vehicle:
    return Vehicle(vehicle_id="cep_size_m_1", vehicle_type="cep")


@pytest.fixture
def sample_plan(sample_service: Service) -> Plan:
    """Plan with one service activity and one start/end activity."""
    plan = Plan(plan_id="plan_v1", vehicle="v1")
    plan.activities = {
        "start": None,
        "service_001": None,
        "end": None,
    }
    plan.legs = {"leg_1": None, "leg_2": None}
    plan.build_sequence()
    return plan


@pytest.fixture
def sample_carrier(sample_service: Service) -> Carrier:
    """Carrier with one vehicle that has a plan referencing sample_service."""
    c = Carrier(carrier_id="dhl_30159_carrier1")
    c.add_service(sample_service)

    v = Vehicle(vehicle_id="cep_size_m_1", vehicle_type="cep")
    plan = Plan(plan_id="plan_cep_size_m_1", vehicle="cep_size_m_1")
    plan.activities = {
        "start": None,
        "service_001": None,
        "end": None,
    }
    plan.legs = {"leg_1": None, "leg_2": None}
    plan.build_sequence()
    v.add_plan(plan)
    c.add_vehicle(v)
    return c


@pytest.fixture
def dhl_carrier_with_missed(service_with_merged: Service) -> Carrier:
    """Carrier with missed deliveries including merged sub-services."""
    c = Carrier(carrier_id="dhl_30159_carrier2")
    c.add_service(service_with_merged)
    c.missed_deliveries = ["sub_svc_A"]

    v = Vehicle(vehicle_id="cep_size_l_2", vehicle_type="cep")
    plan = Plan(plan_id="plan_cep_size_l_2", vehicle="cep_size_l_2")
    plan.activities = {
        "start": None,
        "service_merged": None,
        "end": None,
    }
    plan.legs = {"leg_1": None, "leg_2": None}
    plan.build_sequence()
    v.add_plan(plan)
    c.add_vehicle(v)
    return c
