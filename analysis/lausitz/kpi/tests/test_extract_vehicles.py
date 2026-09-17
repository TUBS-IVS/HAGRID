# -*- coding: utf-8 -*-
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import extract_vehicles as ev

FIX = Path(__file__).parent / "fixtures" / "mini_lmd"


def test_freight_rows_join_tsv():
    rows = ev.extract(FIX, "MINI")
    frt = {r["vehicle_id"]: r for r in rows if r["role"] == "freight"}
    v1 = frt["freight_dhl_veh_dhl_ct_cep_size_s_h8_v1_1"]
    assert v1["distance_km"] == 40.0 and v1["excluded"] == 1
    v0 = frt["freight_dhl_veh_dhl_ct_cep_size_s_h8_v0_0"]
    assert v0["provider"] == "dhl" and v0["parcels"] == 90 and v0["stops"] == 2
    assert v0["travel_h"] == 5.0 and v0["excluded"] == 0


def test_freight_vehicle_type_is_raw_type_id():
    # Task 12: freight vehicle_type is now the raw type_id (e.g.
    # "ct_cep_size_s"), not the broad classify_vehicle() bucket ("VAN") --
    # so the Task-9 drilldown "Typ" column shows s/m/l granularity.
    rows = ev.extract(FIX, "MINI")
    frt = {r["vehicle_id"]: r for r in rows if r["role"] == "freight"}
    v0 = frt["freight_dhl_veh_dhl_ct_cep_size_s_h8_v0_0"]
    assert v0["vehicle_type"] == "ct_cep_size_s"
    hermes = frt["freight_hermes_veh_hermes_cargoBike_v0_0"]
    assert hermes["vehicle_type"] == "cargoBike_t"


def test_drt_rows_from_recon():
    recon = {"per_veh": {"drt_1": {"occupied_s": 1800.0, "active_s": 3600.0,
                                   "shift_s": 7200.0, "ratio_active": 0.5}},
             "fleet": {}}
    rows = ev.extract(FIX, "MINI", recon=recon)
    drt = [r for r in rows if r["role"] == "drt"]
    assert drt[0]["vehicle_id"] == "drt_1" and drt[0]["occupied_h"] == 0.5
    assert drt[0]["ratio_active"] == 0.5 and drt[0]["provider"] == "drt"
    assert drt[0]["vehicle_type"] == "DRT"  # unaffected by the freight type_id change


def test_write_header(tmp_path):
    class M: run_id = "MINI"
    out = tmp_path / "kpi_vehicles.csv"
    ev.write(ev.extract(FIX, "MINI"), M, out)
    head = out.read_text(encoding="utf-8").splitlines()[0]
    assert head == ("run_id;role;vehicle_id;provider;vehicle_type;distance_km;duration_h;"
                    "travel_h;parcels;stops;load_factor;excluded;occupied_h;active_h;"
                    "shift_h;ratio_active")
