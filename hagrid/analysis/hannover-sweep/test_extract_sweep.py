# -*- coding: utf-8 -*-
"""Fixture test for the SUMMARY parser + KPI/limit math (pytest)."""
import extract_sweep as ex

FIXTURE = """<html><script>
var SUMMARY=[{"provider":"dhl","carriers":1,"vehicles":3,"parcels":250,"missed":5,
 "distKm":100.5,"tourDurH":20.0,"drivingH":8.0,"cost":500.0,
 "vehDetails":[
   {"vid":"a","parcels":95,"stops":40,"distKm":30.0,"durH":7.5,"cap":100},
   {"vid":"b","parcels":95,"stops":40,"distKm":40.0,"durH":6.0,"cap":100},
   {"vid":"c","parcels":60,"stops":30,"distKm":30.5,"durH":7.2,"cap":100}]},
 {"provider":"gls","carriers":1,"vehicles":1,"parcels":50,"missed":0,
 "distKm":9.5,"tourDurH":4.0,"drivingH":2.0,"cost":100.0,
 "vehDetails":[{"vid":"d","parcels":50,"stops":25,"distKm":9.5,"durH":4.0,"cap":100}]}];
var CARRIER_DETAIL=[{"id":"dhl_1","tours":3},{"id":"gls_1","tours":1}];
</script></html>"""


def test_extract_run(tmp_path):
    f = tmp_path / "board.html"
    f.write_text(FIXTURE, encoding="utf-8")
    r = ex.extract_run("v1", 100, None, f)
    k, li = r["kpis"], r["limits"]
    assert k["tour_km"] == 110.0
    assert k["tour_h"] == 24.7   # Sum vehDetails durH (uniform across schema generations)
    assert k["cost_eur"] == 600
    assert k["vehicles"] == 4
    assert k["parcels"] == 300
    assert k["parcels_per_vehicle"] == 75.0
    assert k["utilization"] == 0.75          # 300 / 400
    # a: 7.5h & 95>90 -> both; b: 95>90 -> capa; c: 7.2h -> worktime; d: neither
    assert li == {"worktime_only": 1, "capa_only": 1, "both": 1, "neither": 1,
                  "total_tours": 4}
    assert r["meta"]["carrier_detail_tours"] == 4


def test_marker_must_be_unique(tmp_path):
    f = tmp_path / "board.html"
    f.write_text(FIXTURE + 'SUMMARY=[{"x":1}]', encoding="utf-8")
    try:
        ex.extract_run("v1", 100, None, f)
        raise AssertionError("expected ValueError on duplicate marker")
    except ValueError:
        pass
