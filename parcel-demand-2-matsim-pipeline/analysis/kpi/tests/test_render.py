# -*- coding: utf-8 -*-
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from build_kpis import build
import render

FIX = Path(__file__).parent / "fixtures" / "drtrun"


def test_load_run_data_missing_files_graceful(tmp_path):
    d = render.load_run_data(tmp_path)          # no CSVs at all
    assert d.provider.empty and d.vehicles.empty and d.iterations.empty


def test_tile_tooltip():
    html = render._tile("5", "X", tip='a "quoted" tip')
    assert 'title="a &quot;quoted&quot; tip"' in html


def test_run_page_has_drt_tab_and_plugins(tmp_path):
    out = build(FIX, no_events=True, out_dir=tmp_path)
    data = render.load_run_data(out)
    html = render.render_run_page(data, title="DRT_TEST")
    # NOTE: the DRT tab is now real render_drt.build_tab (v2 Plan C Task 5/6) --
    # tiles + charts. The vendored Chart.js script is still always inlined
    # regardless of whether any chart uses it.
    assert "chart.umd.min.js" not in html          # inlined, not referenced
    assert ">DRT<" in html and "showTab" in html
    assert "vlinePlugin" in html and "mkToggle" in html
    assert "9171" in html                          # rides tile still present (regression)
    assert "<canvas" in html                       # Task 6 charts render real canvases
    assert "prefers-color-scheme" in html          # dark mode present
    # v2 Plan C Task 10: real build_tabs no longer embed their own KPI table --
    # render_run_page always appends it once, below the tabs.
    assert "Alle KPIs" in html
    assert len(html.encode("utf-8")) < 2_000_000   # performance budget


def test_build_writes_dashboard(tmp_path):
    out = build(FIX, no_events=True, out_dir=tmp_path)
    assert (out / "kpi_dashboard.html").exists()


# --- Plan D Task D1: full-width wrap, vline chip legibility, donut helper, size ramp ---

def test_wrap_is_near_full_width():
    assert "1240px" not in render.CSS
    assert "min(1680px, 96vw)" in render.CSS


def test_vline_chip_and_stagger():
    # high-contrast ink on a chip (surface fill, border), staggered by index
    assert "V('--ink')" in render.VLINE_JS
    assert "V('--surface')" in render.VLINE_JS
    assert "V('--border')" in render.VLINE_JS
    assert "i * 15" in render.VLINE_JS
    assert "vlinePlugin" in render.VLINE_JS and "'vlines'" in render.VLINE_JS


def test_donut_cfg_shape():
    title, cid, cfg, height = render._donut(
        "c_donut", "T", ["A", "B"], [3, 7], render.size_marker([0, 1]), height=180)
    assert title == "T" and cid == "c_donut" and height == 180
    assert cfg["type"] == "doughnut"
    opts = cfg["options"]
    assert opts["plugins"]["legend"]["display"] is True
    assert opts["plugins"]["legend"]["position"] == "bottom"
    assert cfg["data"]["datasets"][0]["borderWidth"] == 2
    assert cfg["data"]["datasets"][0]["__sizes"] == [0, 1]
    assert cfg["data"]["labels"] == ["A", "B"]


def test_donut_center_total_default_and_override():
    _, _, cfg, _ = render._donut("c1", "T", ["A", "B"], [3, 7], {"__slots": [0, None]})
    assert cfg["options"]["plugins"]["centerTotal"]["text"] == "10"
    _, _, cfg2, _ = render._donut("c2", "T", ["A", "B"], [3, 7], {"__slots": [0, None]},
                                   center_label="10 Fahrzeuge")
    assert cfg2["options"]["plugins"]["centerTotal"]["text"] == "10 Fahrzeuge"


def test_size_ramp_present_and_distinct():
    # alphaSize mirrors alphaSeq, reading the new --size var
    assert "function alphaSize(a)" in render.JS_RESOLVE
    assert "--size" in render.JS_RESOLVE
    assert "__sizes" in render.JS_RESOLVE
    assert "--size" in render.CSS
    # distinct hue from --seq blue and from every CAT slot (light + dark)
    assert render.SIZE_LIGHT != render.SEQ_LIGHT
    assert render.SIZE_LIGHT not in render.CAT_LIGHT
    assert render.SIZE_DARK != render.SEQ_DARK
    assert render.SIZE_DARK not in render.CAT_DARK


def test_donut_registers_center_plugin_in_run_page(tmp_path):
    out = build(FIX, no_events=True, out_dir=tmp_path)
    data = render.load_run_data(out)
    html = render.render_run_page(data, title="DRT_TEST")
    assert "centerTotal" in html
