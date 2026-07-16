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
    assert len(html.encode("utf-8")) < 2_000_000   # performance budget


def test_build_writes_dashboard(tmp_path):
    out = build(FIX, no_events=True, out_dir=tmp_path)
    assert (out / "kpi_dashboard.html").exists()
