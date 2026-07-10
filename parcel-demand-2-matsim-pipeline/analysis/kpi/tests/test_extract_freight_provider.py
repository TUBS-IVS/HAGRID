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


def test_write_schema(tmp_path):
    rows = efp.extract(FIX, "MINI")
    from run_meta import load_run_meta
    # mini has no run_metadata.json/dirname pattern -> build a stub meta
    class M: run_id = "MINI"
    out = tmp_path / "kpis_provider.csv"
    efp.write(rows, M, out)
    head = out.read_text(encoding="utf-8").splitlines()[0]
    assert head == "run_id;provider;kpi_name;value;unit;source"
