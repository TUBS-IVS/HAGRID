# tests/test_render_drt.py
import sys
from pathlib import Path
import pandas as pd
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import render, render_drt


def _data(rows):
    kpis = pd.DataFrame(rows)
    empty = pd.DataFrame()
    return render.RunData(kpis=kpis, ts=pd.DataFrame(columns=["series", "hour", "value"]),
                          provider=empty, iterations=empty, distributions=empty, vehicles=empty)


def test_tiles_full_set():
    rows = [{"kpi_group": "passenger", "kpi_name": n, "value": v, "unit": "", "source": ""}
            for n, v in [("drt_rides", 9171), ("wait_median", 600), ("wait_p95", 1173),
                         ("detour_factor", 1.43), ("pooling_rate", 0.25)]]
    rows.append({"kpi_group": "system", "kpi_name": "drt_vehicles", "value": 250,
                 "unit": "vehicles", "source": ""})
    html, js = render_drt.build_tab(_data(rows), uid="drt")
    assert "9.171" in html            # German-formatted rides tile
    assert "Umwegfaktor" in html and "1,43" in html
    assert 'title="' in html          # tooltips present


def test_events_only_tiles_skip_gracefully():
    html, js = render_drt.build_tab(_data([{"kpi_group": "passenger", "kpi_name": "drt_rides",
                                            "value": 10, "unit": "", "source": ""}]), uid="drt")
    assert "Service-Zeit" not in html   # no service_ratio_* rows -> tiles absent


def test_map_block_inserted():
    html, js = render_drt.build_tab(_data([{"kpi_group": "passenger", "kpi_name": "drt_rides",
                                            "value": 10, "unit": "", "source": ""}]),
                                    uid="drt", map_block={"html": "<div id='MAPX'></div>", "js": "//mapjs"})
    assert "MAPX" in html and "//mapjs" in js


def _full_data():
    kpis = pd.DataFrame([
        {"kpi_group": "passenger", "kpi_name": "drt_rides", "value": 100, "unit": "", "source": ""},
        {"kpi_group": "passenger", "kpi_name": "wait_median", "value": 600, "unit": "s", "source": ""},
        {"kpi_group": "passenger", "kpi_name": "modal_share_drt", "value": 0.1, "unit": "share", "source": ""},
        {"kpi_group": "passenger", "kpi_name": "modal_share_car", "value": 0.5, "unit": "share", "source": ""},
    ])
    ts = pd.DataFrame([
        {"series": "drt_rides", "hour": 8, "value": 12},
        {"series": "drt_rides", "hour": 9, "value": 15},
        {"series": "drt_feeder_trips", "hour": 8, "value": 3},
        {"series": "drt_feeder_trips", "hour": 9, "value": 5},
    ])
    distributions = pd.DataFrame([
        {"series": "drt_wait", "bin_lo": 0, "bin_hi": 60, "value": 40, "unit": "s"},
        {"series": "drt_wait", "bin_lo": 60, "bin_hi": 120, "value": 20, "unit": "s"},
        {"series": "occ_time", "bin_lo": 0, "bin_hi": 0, "value": 0.3, "unit": "share"},
        {"series": "occ_time", "bin_lo": 1, "bin_hi": 1, "value": 0.7, "unit": "share"},
    ])
    iterations = pd.DataFrame([
        {"series": "drt_rides", "iteration": 0, "value": 50},
        {"series": "drt_rides", "iteration": 1, "value": 60},
        {"series": "modal_share_drt", "iteration": 0, "value": 0.08},
        {"series": "modal_share_drt", "iteration": 1, "value": 0.1},
    ])
    vehicles = pd.DataFrame([
        {"role": "drt", "vehicle_id": "veh1", "occupied_h": 5.5},
        {"role": "drt", "vehicle_id": "veh2", "occupied_h": 3.2},
    ])
    return render.RunData(kpis=kpis, ts=ts, provider=pd.DataFrame(), iterations=iterations,
                          distributions=distributions, vehicles=vehicles)


def test_charts_full_set():
    data = _full_data()
    html, js = render_drt.build_tab(data, uid="drt")
    assert "mkToggle" in js               # feeder absolute/share toggle
    assert "vlines" in js                 # wait-distribution Median/Ø/P95 markers
    assert "__ramp" in js                 # occupancy-decomposition dataset markers
    assert "c_it_rides_drt" in html       # convergence canvas present
    assert "veh1" in (html + js) and "veh2" in (html + js)  # per-vehicle labels


def test_charts_compact_excludes_distributions_and_convergence():
    data = _full_data()
    html, js = render_drt.build_tab(data, uid="drt", compact=True)
    assert "c_it_" not in html            # no convergence canvases in compact mode
    assert "c_wdist" not in html          # no distribution canvases in compact mode
