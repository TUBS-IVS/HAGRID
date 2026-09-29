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


OLD_ROUT = ('var ROUT_EFF=[{"provider":"dhl","tours":3,"avgKm":40.0,"stemPct":30.0},'
            '{"provider":"gls","tours":1,"avgKm":20.0,"stemPct":50.0}];')
NEW_ROUT = ('var ROUT_EFF=[{"provider":"dhl","tours":3,"avgKm":40.0,"stemPct":60.0,'
            '"stemInPct":32.0,"stemOutPct":28.0},'
            '{"provider":"gls","tours":1,"avgKm":20.0,"stemPct":80.0,'
            '"stemInPct":50.0,"stemOutPct":30.0}];')


def _board(tmp_path, rout):
    f = tmp_path / "board.html"
    f.write_text(FIXTURE.replace("</script>", rout + "</script>"), encoding="utf-8")
    return f


def test_stem_is_km_weighted_like_the_board_total_line(tmp_path):
    """Board TOTAL line: sum(stemPct/100 * avgKm * tours) / sum(avgKm * tours). The
    network value is NOT the mean of the provider percentages."""
    s = ex.extract_run("v1", 100, None, _board(tmp_path, OLD_ROUT))["stem"]
    # (0.30*120 + 0.50*20) / 140 = 46 / 140
    assert s["stem_pct_network"] == round(100 * 46 / 140, 2)
    assert s["stem_pct_provider_max"] == 50.0
    assert s["stem_def"] == "outbound_only"
    assert s["stem_in_pct_network"] is None


def test_stem_definition_is_read_off_the_board(tmp_path):
    """METHODS-LOG 2.49: boards since 2026-08-28 count both depot legs and carry
    stemInPct/stemOutPct; older ones only the outbound leg. Same key, two definitions."""
    s = ex.extract_run("v1", 100, None, _board(tmp_path, NEW_ROUT))["stem"]
    assert s["stem_def"] == "in_plus_out"
    assert s["stem_in_pct_network"] == round(100 * (0.32 * 120 + 0.50 * 20) / 140, 2)
    assert s["stem_out_pct_network"] == round(100 * (0.28 * 120 + 0.30 * 20) / 140, 2)


def test_board_without_rout_eff_has_no_stem(tmp_path):
    f = tmp_path / "board.html"
    f.write_text(FIXTURE, encoding="utf-8")
    assert ex.extract_run("v1", 100, None, f)["stem"] is None


def test_series_mixing_both_stem_definitions_fails(tmp_path):
    old = ex.extract_run("v2", 30, None, _board(tmp_path, OLD_ROUT))
    (tmp_path / "n").mkdir()
    new = ex.extract_run("v2", 40, None, _board(tmp_path / "n", NEW_ROUT))
    try:
        ex.check_stem_vintage([old, new])
        raise AssertionError("expected ValueError on mixed stem definitions")
    except ValueError as e:
        assert "v2" in str(e)
    ex.check_stem_vintage([old, old])        # one definition per series is fine


def test_marker_must_be_unique(tmp_path):
    f = tmp_path / "board.html"
    f.write_text(FIXTURE + 'SUMMARY=[{"x":1}]', encoding="utf-8")
    try:
        ex.extract_run("v1", 100, None, f)
        raise AssertionError("expected ValueError on duplicate marker")
    except ValueError:
        pass
