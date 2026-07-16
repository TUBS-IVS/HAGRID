# tests/test_render_drt.py
import json
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


def _data_ts(ts):
    """Like `_data` but with a caller-supplied `ts` (timeseries) frame and
    empty (column-less) kpis -- for hourly-chart-only fixtures."""
    kpis = pd.DataFrame(columns=["kpi_group", "kpi_name", "value", "unit", "source"])
    empty = pd.DataFrame()
    return render.RunData(kpis=kpis, ts=ts, provider=empty, iterations=empty,
                          distributions=empty, vehicles=empty)


def _cfg_from_line(line):
    """Recover the JSON `cfg` dict from one `mk(id, resolveColors(cfg));`
    line (the fixed shape emitted by render.chart_js)."""
    marker = "resolveColors("
    start = line.index(marker) + len(marker)
    assert line.endswith("));")
    return json.loads(line[start:-3])


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
        {"series": "modal_share_car", "iteration": 0, "value": 0.6},   # >=2 series -> legend
        {"series": "modal_share_car", "iteration": 1, "value": 0.55},
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
    # chart 14 (modal shares over iterations) has >=2 series -> legend enabled
    modal_line = next(l for l in js.splitlines() if "c_it_modal_drt" in l)
    assert '"legend": {"display": true}' in modal_line


def test_charts_compact_excludes_distributions_and_convergence():
    data = _full_data()
    html, js = render_drt.build_tab(data, uid="drt", compact=True)
    assert "c_it_" not in html            # no convergence canvases in compact mode
    assert "c_wdist" not in html          # no distribution canvases in compact mode


def test_modal_split_is_donut():
    data = _full_data()
    html, js = render_drt.build_tab(data, uid="drt")
    modal_line = next(l for l in js.splitlines() if "c_modal_drt" in l)
    cfg = _cfg_from_line(modal_line)
    assert cfg["type"] == "doughnut"
    ds = cfg["data"]["datasets"][0]
    assert "__slots" in ds
    assert cfg["options"]["plugins"]["centerTotal"]["text"] == "100 %"


def test_combined_requests_chart_single_axis_bar_and_line():
    ts = pd.DataFrame([
        {"series": "drt_rides", "hour": 8, "value": 12},
        {"series": "drt_rides", "hour": 9, "value": 15},
        {"series": "drt_requests_submitted", "hour": 8, "value": 14},
        {"series": "drt_requests_submitted", "hour": 9, "value": 18},
    ])
    html, js = render_drt.build_tab(_data_ts(ts), uid="drt")
    req_line = next(l for l in js.splitlines() if "c_req_drt" in l)
    cfg = _cfg_from_line(req_line)
    types = [ds["type"] for ds in cfg["data"]["datasets"]]
    assert types.count("bar") == 1 and types.count("line") == 1
    labels = [ds["label"] for ds in cfg["data"]["datasets"]]
    assert "bediente Abfahrten" in labels
    assert "Anfragen" in labels
    # single shared y-axis: no second yAxisID / y1 scale anywhere in the cfg
    assert "yAxisID" not in json.dumps(cfg)
    assert cfg["options"].get("scales", {}).get("y1") is None
    assert "eingereicht" not in html
    assert "eingereicht" not in js


def test_combined_requests_chart_renders_with_only_one_series_present():
    # drt_requests_submitted entirely absent -- chart still renders (rides
    # present), submitted series 0-filled across all 24 hours.
    ts = pd.DataFrame([
        {"series": "drt_rides", "hour": 8, "value": 12},
    ])
    html, js = render_drt.build_tab(_data_ts(ts), uid="drt")
    req_line = next(l for l in js.splitlines() if "c_req_drt" in l)
    cfg = _cfg_from_line(req_line)
    submitted_ds = next(ds for ds in cfg["data"]["datasets"] if ds["type"] == "line")
    assert submitted_ds["data"] == [0.0] * 24


def test_hourly_charts_span_0_23_with_gap_fill():
    ts = pd.DataFrame([
        {"series": "drt_rejections", "hour": 0, "value": 1},
        {"series": "drt_rejections", "hour": 5, "value": 2},
        {"series": "drt_rejections", "hour": 21, "value": 3},
        # note: hour 22 and 23 are absent -- must 0-fill, not be dropped.
    ])
    html, js = render_drt.build_tab(_data_ts(ts), uid="drt")
    rej_line = next(l for l in js.splitlines() if "c_rej_drt" in l)
    cfg = _cfg_from_line(rej_line)
    assert cfg["data"]["labels"] == list(range(24))
    vals = cfg["data"]["datasets"][0]["data"]
    assert len(vals) == 24
    assert vals[0] == 1.0 and vals[5] == 2.0 and vals[21] == 3.0
    assert vals[22] == 0.0 and vals[23] == 0.0


def test_feeder_toggle_spans_0_23():
    ts = pd.DataFrame([
        {"series": "drt_feeder_trips", "hour": 3, "value": 4},
        {"series": "drt_rides", "hour": 3, "value": 8},
    ])
    html, js = render_drt.build_tab(_data_ts(ts), uid="drt")
    toggle_line = next(l for l in js.splitlines() if "mkToggle" in l)
    # mkToggle(btnId, canvasId, cfgA, cfgB, "Absolut", "Anteil") -- pull cfgA's
    # labels out via the raw JSON args (positions 2/3 are the two configs).
    args_start = toggle_line.index("(") + 1
    assert toggle_line.endswith(");")
    parts = json.loads("[" + toggle_line[args_start:-2] + "]")
    cfg_a = parts[2]
    assert cfg_a["data"]["labels"] == list(range(24))
    assert len(cfg_a["data"]["datasets"][0]["data"]) == 24
