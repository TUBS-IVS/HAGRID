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
