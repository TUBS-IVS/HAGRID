# -*- coding: utf-8 -*-
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from build_kpis import build
from render import load_run_csvs, render_run_page

FIX = Path(__file__).parent / "fixtures" / "drtrun"


def test_render_run_page(tmp_path):
    out = build(FIX, no_events=True, out_dir=tmp_path)
    kpis, ts = load_run_csvs(out)
    html = render_run_page(kpis, ts, title="DRT_TEST")
    assert "<canvas" in html
    assert "chart.umd.min.js" not in html          # inlined, not referenced
    assert "Chart(" in html or "new Chart" in html
    assert "9171" in html                          # rides tile
    assert "prefers-color-scheme" in html          # dark mode present
    assert len(html.encode("utf-8")) < 1_000_000   # performance budget


def test_build_writes_dashboard(tmp_path):
    out = build(FIX, no_events=True, out_dir=tmp_path)
    assert (out / "kpi_dashboard.html").exists()
