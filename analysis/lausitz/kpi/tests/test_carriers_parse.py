# tests/test_carriers_parse.py
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import carriers_parse as cp

FIX = Path(__file__).parent / "fixtures" / "mini_lmd"


def test_parse_vehicle_types():
    vt = cp.parse_vehicle_types(FIX / "MINI.output_carriersVehicleTypes.xml.gz")
    assert vt["ct_cep_size_s"].capacity == 100.0
    assert vt["ct_cep_size_s"].fixed_cost_per_day == 150.0
    assert vt["cargoBike_t"].capacity == 30.0
    assert vt["supply_truck"].capacity == 350.0
    assert vt["supply_truck"].fixed_cost_per_day == 400.0
    assert vt["supply_truck"].costs_per_meter == 0.0009
    assert vt["ct_cep_size_s"].costs_per_meter == 0.0003


def test_parse_carriers_structure():
    cs = {c.carrier_id: c for c in cp.parse_carriers(FIX / "MINI.output_carriers.xml.gz")}
    assert set(cs) == {"dhl", "hermes", "amazon_supply"}
    dhl = cs["dhl"]
    assert dhl.attrs["provider"] == "dhl"
    assert cp.attr_int(dhl.attrs, "numberOfParcels") == 100
    assert dhl.services["s0"].capacity_demand == 60
    assert dhl.vehicles["dhl_ct_cep_size_s_h8_v0"].type_id == "ct_cep_size_s"
    assert len(dhl.tours) == 2
    t0 = dhl.tours[0]
    assert t0.vehicle_id == "dhl_ct_cep_size_s_h8_v0"
    assert t0.service_ids == ["s0", "s1"]
    assert t0.event_vehicle_id("dhl") == "freight_dhl_veh_dhl_ct_cep_size_s_h8_v0_1"


def test_event_vehicle_id_is_the_tour_position_not_the_tour_id(tmp_path):
    """MATSim's CarrierAgent.createDriverId numbers the driver 1, 2, ... in the order of the
    selected plan's scheduled tours; jsprit's tourId plays no part. Until 2026-09-29 this parser
    used the tourId, which only coincides while the tourIds happen to be in plan order -- on
    b120rgs that was 10 of 41 tours, so kpi_vehicles.csv, the hourly LMD series, the per-type
    km and the LMD map silently lost the other 31."""
    xml = tmp_path / "c.xml"
    xml.write_text(
        '<carriers><carrier id="dhl"><plans><plan selected="true">'
        '<tour tourId="7" vehicleId="dhl_l_v2_t0"></tour>'
        '<tour tourId="1" vehicleId="dhl_m_v0_t1"></tour>'
        '</plan></plans></carrier></carriers>', encoding="utf-8")
    (dhl,) = cp.parse_carriers(xml)
    assert [t.event_vehicle_id("dhl") for t in dhl.tours] == [
        "freight_dhl_veh_dhl_l_v2_t0_1", "freight_dhl_veh_dhl_m_v0_t1_2"]
    assert [t.tour_id for t in dhl.tours] == ["7", "1"]     # the jsprit id is kept as data


def test_carrier_attrs_not_polluted_by_service_attrs():
    # regression: carrier attrs must not accidentally include service-level names
    cs = {c.carrier_id: c for c in cp.parse_carriers(FIX / "MINI.output_carriers.xml.gz")}
    assert "capacityDemand" not in cs["dhl"].attrs


def test_selected_plan_score():
    cs = {c.carrier_id: c for c in cp.parse_carriers(FIX / "MINI.output_carriers.xml.gz")}
    assert cs["dhl"].selected_plan_score == -100.0
    assert cs["hermes"].selected_plan_score == -40.0
