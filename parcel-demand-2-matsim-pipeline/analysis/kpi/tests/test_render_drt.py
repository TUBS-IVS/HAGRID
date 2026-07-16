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
