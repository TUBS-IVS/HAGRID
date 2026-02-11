"""Tests for hagrid_output_analysis.models."""

import pytest

from hagrid_output_analysis.models import Carrier, Plan, Service, Vehicle


class TestService:
    def test_creation(self, sample_service: Service):
        assert sample_service.service_id == "service_001"
        assert sample_service.capacity_demand == 7
        assert sample_service.duration == 360
        assert sample_service.link == "link_42"

    def test_get_set_attr(self, sample_service: Service):
        assert sample_service.get_attr("b2b") == 3
        assert sample_service.get_attr("b2c") == 4
        assert sample_service.get_attr("missing", "default") == "default"

        sample_service.set_attr("test_key", 42)
        assert sample_service.get_attr("test_key") == 42

    def test_backward_compat_aliases(self, sample_service: Service):
        assert sample_service.get_attribute("b2b") == 3
        assert sample_service.get_service_id() == "service_001"
        assert sample_service.get_service_link() == "link_42"
        assert sample_service.get_demand() == 7
        assert sample_service.get_duration() == 360

    def test_capacity_demand_coerced_to_int(self):
        s = Service("svc", "id", "5", 100, "link")  # type: ignore[arg-type]
        assert isinstance(s.capacity_demand, int)
        assert s.capacity_demand == 5

    def test_duration_coerced_to_int(self):
        s = Service("svc", "id", 1, "360", "link")  # type: ignore[arg-type]
        assert isinstance(s.duration, int)
        assert s.duration == 360

    def test_duration_bad_value_falls_back(self):
        s = Service("svc", "id", 1, "invalid", "link")  # type: ignore[arg-type]
        assert s.duration == 0

    def test_repr(self, sample_service: Service):
        r = repr(sample_service)
        assert "service_001" in r
        assert "demand=7" in r


class TestVehicle:
    def test_creation(self, sample_vehicle: Vehicle):
        assert sample_vehicle.vehicle_id == "cep_size_m_1"
        assert sample_vehicle.vehicle_type == "cep"
        assert sample_vehicle.plans == []

    def test_add_plan(self, sample_vehicle: Vehicle, sample_plan: Plan):
        sample_vehicle.add_plan(sample_plan)
        assert len(sample_vehicle.plans) == 1
        assert sample_vehicle.plans[0] is sample_plan

    def test_repr(self, sample_vehicle: Vehicle):
        r = repr(sample_vehicle)
        assert "cep_size_m_1" in r


class TestPlan:
    def test_build_sequence(self, sample_plan: Plan):
        # Plan has 3 activities and 2 legs → sequence is
        # [act0, leg0, act1, leg1, act2]
        assert len(sample_plan.plan_sequence) == 5

    def test_backward_compat_alias(self):
        p = Plan(plan_id="p1", vehicle="v1")
        p.activities = {"a": None, "b": None}
        p.legs = {"l": None}
        p.create_internal_plan_sequence()
        assert len(p.plan_sequence) == 3

    def test_repr(self, sample_plan: Plan):
        r = repr(sample_plan)
        assert "acts=" in r
        assert "legs=" in r


class TestCarrier:
    def test_auto_provider(self, sample_carrier: Carrier):
        assert sample_carrier.provider == "dhl"

    def test_add_vehicle(self, sample_carrier: Carrier):
        assert sample_carrier.num_vehicles == 1

    def test_add_service(self, sample_carrier: Carrier):
        assert sample_carrier.num_services == 1

    def test_total_demand(self, sample_carrier: Carrier):
        assert sample_carrier.total_demand == 7

    def test_repr(self, sample_carrier: Carrier):
        r = repr(sample_carrier)
        assert "dhl_30159_carrier1" in r
        assert "vehs=" in r
        assert "svcs=" in r

    def test_provider_amazon(self):
        c = Carrier("amazon_12345_carrier1")
        assert c.provider == "amazon"

    def test_provider_white_label(self):
        c = Carrier("wl_30159_carrier1")
        assert c.provider == "white-label"
