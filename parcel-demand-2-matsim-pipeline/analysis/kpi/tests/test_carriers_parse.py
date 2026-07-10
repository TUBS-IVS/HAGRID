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
    assert t0.event_vehicle_id("dhl") == "freight_dhl_veh_dhl_ct_cep_size_s_h8_v0_0"


def test_carrier_attrs_not_polluted_by_service_attrs():
    # regression: carrier attrs must not accidentally include service-level names
    cs = {c.carrier_id: c for c in cp.parse_carriers(FIX / "MINI.output_carriers.xml.gz")}
    assert "capacityDemand" not in cs["dhl"].attrs
