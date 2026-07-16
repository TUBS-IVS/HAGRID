# tests/test_extract_freight_provider.py
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import extract_freight_provider as efp

FIX = Path(__file__).parent / "fixtures" / "mini_lmd"


def _by(rows, provider, name):
    for r in rows:
        if r["provider"] == provider and r["kpi_name"] == name:
            return r["value"]
    return None


def test_low_util_exclusion_marks_1parcel_tour():
    pf = efp.parse_run(FIX, "MINI")
    # dhl_..._v1 carries 1 parcel into a cap-100 van -> lf 0.01 < 0.05 -> excluded
    excl = pf.excluded
    assert "freight_dhl_veh_dhl_ct_cep_size_s_h8_v1_1" in excl
    assert "freight_dhl_veh_dhl_ct_cep_size_s_h8_v0_0" not in excl
    # supply vehicles are never excluded even at high load (only delivery considered)
    assert "freight_amazon_supply_veh_amazon_Supply_Vehicle_v0_0" not in excl


def test_provider_cost_reallocation():
    rows = efp.extract(FIX, "MINI")
    # dhl variable cost = costDistance 200 + costTime 0 + costOvertime 0 = 200
    # ratio = nonExcluded 1 / allTours 2 = 0.5 -> var 100; fixed = surviving veh v0 = 150
    # total = dist(100) + time(0) + fixed(150) + overtime(0) = 250
    assert _by(rows, "dhl", "cost_dist") == 100.0
    assert _by(rows, "dhl", "cost_fixed") == 150.0
    assert _by(rows, "dhl", "cost_total") == 250.0
    assert _by(rows, "dhl", "excluded_vehicles") == 1


def test_provider_parcels_and_type_rows():
    rows = efp.extract(FIX, "MINI")
    assert _by(rows, "dhl", "parcels_total") == 100
    # missed re-allocated: round(10 * 1/2) = 5
    assert _by(rows, "dhl", "parcels_missed") == 5
    assert _by(rows, "hermes", "vehicles") == 1
    # per-type row present for the CEP van
    assert _by(rows, "type:VAN", "vehicles") is not None
    assert _by(rows, "type:CARGOBIKE", "distance_km") == 20.0


def test_supply_provider_present_but_not_double_counted():
    rows = efp.extract(FIX, "MINI")
    # amazon is supply-only here; it still gets provider rows (supply carrier),
    # but its vehicles are never in the excluded set (asserted above)
    assert _by(rows, "amazon", "km") == 200.0


def test_travel_hours_and_score_per_provider():
    rows = efp.extract(FIX, "MINI")
    assert _by(rows, "dhl", "travel_hours") == 8.333
    assert _by(rows, "dhl", "score") == -100.0


def test_all_rows():
    rows = efp.extract(FIX, "MINI")
    assert _by(rows, "all", "carriers_delivery") == 2
    assert _by(rows, "all", "carriers_supply") == 1
    # surviving delivery vehicles: dhl v0 (2 stops, lf 0.9) + hermes v0 (1 stop, lf 25/30)
    assert _by(rows, "all", "stops") == 3
    assert abs(_by(rows, "all", "avg_load_factor") - (0.9 + 25/30) / 2) < 1e-6
    assert abs(_by(rows, "all", "travel_hours") - (8.333 + 1.667 + 4.167)) < 1e-6


def test_vtype_rows_present_with_real_capacity():
    # Task 12: fine vtype:<type_id> rows, additive alongside the broad type:
    # rows -- capacity is the REAL per-type value (100/30/350), not a mean.
    rows = efp.extract(FIX, "MINI")
    assert _by(rows, "vtype:ct_cep_size_s", "capacity") == 100.0
    assert _by(rows, "vtype:cargoBike_t", "capacity") == 30.0
    assert _by(rows, "vtype:supply_truck", "capacity") == 350.0
    # 1 surviving vehicle each (dhl v1 is the excluded low-util tour)
    assert _by(rows, "vtype:ct_cep_size_s", "vehicles") == 1
    assert _by(rows, "vtype:cargoBike_t", "vehicles") == 1
    assert _by(rows, "vtype:supply_truck", "vehicles") == 1


def test_vtype_rows_distance_km_no_double_count():
    # distance_km per type_id, bucketed off the same TimeDistance_perVehicle
    # rows as the broad type: rows -- must sum to the same grand total
    # (excluded v1's 40km is skipped by both).
    rows = efp.extract(FIX, "MINI")
    assert _by(rows, "vtype:ct_cep_size_s", "distance_km") == 60.0
    assert _by(rows, "vtype:cargoBike_t", "distance_km") == 20.0
    assert _by(rows, "vtype:supply_truck", "distance_km") == 200.0

    fine_total = sum(_by(rows, p, "distance_km") for p in
                      ["vtype:ct_cep_size_s", "vtype:cargoBike_t", "vtype:supply_truck"])
    broad_total = sum(_by(rows, p, "distance_km") for p in
                       ["type:VAN", "type:CARGOBIKE", "type:TRUCK", "type:TRUCK_LIGHT",
                        "type:SUPPLY_VAN"] if _by(rows, p, "distance_km") is not None)
    assert fine_total == broad_total == 280.0


def test_broad_type_rows_unaffected_by_vtype_addition():
    # the pre-existing broad type: row assertions must still hold unchanged
    rows = efp.extract(FIX, "MINI")
    assert _by(rows, "type:VAN", "vehicles") == 1
    assert _by(rows, "type:CARGOBIKE", "distance_km") == 20.0


def test_write_schema(tmp_path):
    rows = efp.extract(FIX, "MINI")
    from run_meta import load_run_meta
    # mini has no run_metadata.json/dirname pattern -> build a stub meta
    class M: run_id = "MINI"
    out = tmp_path / "kpis_provider.csv"
    efp.write(rows, M, out)
    head = out.read_text(encoding="utf-8").splitlines()[0]
    assert head == "run_id;provider;kpi_name;value;unit;source"
