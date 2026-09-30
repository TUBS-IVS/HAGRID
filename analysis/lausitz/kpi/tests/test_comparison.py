# -*- coding: utf-8 -*-
import shutil
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from build_comparison import build_comparison
from build_kpis import build

FIX = Path(__file__).parent / "fixtures" / "drtrun"


def _fake_run(tmp_path, name):
    """Copy the fixture, build its CSVs (file prefix stays DRT_TEST), then
    rebrand the run_id in metadata + CSVs so the comparison sees two distinct runs."""
    d = tmp_path / name
    shutil.copytree(FIX, d)
    build(d, no_events=True)                       # writes analysis/*.csv as DRT_TEST
    rid = name.rsplit("_iter", 1)[0]               # e.g. DRT_TEST_A
    meta_f = d / "run_metadata.json"
    meta_f.write_text(meta_f.read_text(encoding="utf-8")
                      .replace('"DRT_TEST"', '"' + rid + '"'), encoding="utf-8")
    for f in ("kpis_long.csv", "kpis_wide.csv", "kpi_timeseries.csv"):
        p = d / "analysis" / f
        p.write_text(p.read_text(encoding="utf-8").replace("DRT_TEST", rid),
                     encoding="utf-8")
    return d


def test_comparison_two_runs(tmp_path):
    # two pseudo-runs from the same fixture (KPI values identical, ids differ)
    a = _fake_run(tmp_path, "DRT_TEST_A_iter1_jsprit1")
    b = _fake_run(tmp_path, "DRT_TEST_B_iter1_jsprit1")
    out = tmp_path / "cmp.html"
    build_comparison([a, b], out_file=out)         # CSVs exist -> no rebuild
    html = out.read_text(encoding="utf-8")
    assert "Vergleich" in html
    assert "DRT_TEST_A" in html and "DRT_TEST_B" in html
    # per-run tabs are now the real compact tab builders (v2 Plan C Task 10):
    # LMD provider chart present, but no distribution canvas / drilldown rows
    # (those are non-compact-only -- compact per-run tabs stay lean).
    assert 'id="c_p_parcels_run0"' in html
    assert 'id="c_wdist_run0"' not in html
    assert 'class="vehrow"' not in html
    assert len(html.encode("utf-8")) < 3_000_000   # comparison budget


def test_comparison_page_defines_all_chart_plugins(tmp_path):
    # regression (Plan D final review): the per-run compact tabs are the real
    # render_drt/render_lmd build_tab output, which can emit a modal donut
    # (centerTotal), vlines, feeder toggles or drilldown calls. The comparison
    # page must DEFINE those plugins, same as render_run_page -- previously its
    # body_js was TAB_JS only, so centerTotalPlugin was undefined here and the
    # modal donut's hole total silently never drew.
    a = _fake_run(tmp_path, "DRT_TEST_A_iter1_jsprit1")
    b = _fake_run(tmp_path, "DRT_TEST_B_iter1_jsprit1")
    out = tmp_path / "cmp.html"
    build_comparison([a, b], out_file=out)
    html = out.read_text(encoding="utf-8")
    assert "centerTotalPlugin" in html   # DONUT_JS
    assert "vlinePlugin" in html          # VLINE_JS
    assert "mkToggle" in html             # TOGGLE_JS
    assert "toggleVeh" in html            # DRILL_JS


_CONFIG = ('<?xml version="1.0" ?><config>'
           '<module name="controller"><param name="outputDirectory" value="C:/Users/x/out" />'
           '</module><module name="multiModeDrt"><parameterset type="drt">'
           '<param name="numberOfThreads" value="{threads}" /></parameterset></module></config>')


def _write_config(run_dir, threads):
    rid = run_dir.name.rsplit("_iter", 1)[0]
    (run_dir / (rid + ".output_config.xml")).write_text(_CONFIG.format(threads=threads),
                                                         encoding="utf-8")


def test_comparison_page_shows_the_config_diff(tmp_path):
    """basew21 (METHODS-LOG 3.14): byte-identical populations, but numberOfThreads 14 vs 12,
    worth ~103 rides. The comparison page shows every substantive config difference of each
    run against the first, so a pair that differs in more than the parameter under test is
    visible where the figures are read -- not only when someone runs config_diff.py by hand."""
    a = _fake_run(tmp_path, "DRT_TEST_A_iter1_jsprit1")
    b = _fake_run(tmp_path, "DRT_TEST_B_iter1_jsprit1")
    _write_config(a, 14)
    _write_config(b, 12)
    out = tmp_path / "cmp.html"
    build_comparison([a, b], out_file=out)
    html = out.read_text(encoding="utf-8")
    assert "Konfigurations-Abgleich" in html
    assert "multiModeDrt/drt/numberOfThreads" in html
    assert "14" in html and "12" in html
    assert "1 substanzielle Abweichung" in html
    assert "controller/outputDirectory" not in html     # bookkeeping path, not shown


def test_comparison_page_reports_identical_configs_and_missing_ones(tmp_path):
    a = _fake_run(tmp_path, "DRT_TEST_A_iter1_jsprit1")
    b = _fake_run(tmp_path, "DRT_TEST_B_iter1_jsprit1")
    c = _fake_run(tmp_path, "DRT_TEST_C_iter1_jsprit1")
    _write_config(a, 12)
    _write_config(b, 12)                                # c has none
    out = tmp_path / "cmp.html"
    build_comparison([a, b, c], out_file=out)
    html = out.read_text(encoding="utf-8")
    assert "identisch" in html
    assert "output_config.xml fehlt" in html
