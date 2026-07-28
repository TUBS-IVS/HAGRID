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


# --- Review fix package 2026-07-27: dashboard honesty on Shared-Use runs (E2-E6) ---

def _with_meta_row(data):
    """Append a meta/parcel_contaminated_kpis provenance row to a loaded run's
    kpis frame, mimicking what pax_only.apply_overrides does on a Shared-Use
    run (the drtrun fixture is a baseline run and carries no meta rows)."""
    import pax_only
    row = data.kpis.iloc[0].copy()
    row["kpi_group"] = "meta"
    row["kpi_name"] = pax_only.CONTAMINATION_KPI
    row["value"] = 3
    row["unit"] = "kpis"
    row["source"] = "pooling_rate,sharing_factor,drt_passenger_km"
    data.kpis.loc[len(data.kpis)] = row
    return data


def test_konvergenz_banner_only_on_contaminated_runs(tmp_path):
    # E2: baseline page has no banner text; a run with the contamination meta
    # row gets the warnbanner INSIDE the Konvergenz group (after its <h2>).
    out = build(FIX, no_events=True, out_dir=tmp_path)
    data = render.load_run_data(out)
    clean = render.render_run_page(data, title="DRT_TEST")
    assert "Iterationsreihen enthalten Paket-Agenten" not in clean

    html = render.render_run_page(_with_meta_row(data), title="DRT_TEST")
    assert "Iterationsreihen enthalten Paket-Agenten" in html
    konv = html.index("<h2>Konvergenz</h2>")
    banner = html.index("Iterationsreihen enthalten Paket-Agenten")
    assert banner > konv
    # directly attached to the heading, before the chart grid
    assert html[konv:banner].count("<canvas") == 0


def test_meta_notes_block_renders_only_when_meta_rows_exist(tmp_path):
    # E3: the meta group never fit the grouped-table loop -- it must surface
    # as a "Hinweise" block; baseline pages stay free of it.
    out = build(FIX, no_events=True, out_dir=tmp_path)
    data = render.load_run_data(out)
    assert "Hinweise" not in render.render_kpi_table(data.kpis)

    import pax_only
    html = render.render_kpi_table(_with_meta_row(data).kpis)
    assert "Hinweise" in html
    assert pax_only.CONTAMINATION_KPI in html


def test_kpi_source_and_tip_src_cite_actual_source(tmp_path):
    # E4: tooltips must cite the row's ACTUAL source column (pax_only override
    # rewrites it on Shared-Use runs), never a hardcoded string.
    out = build(FIX, no_events=True, out_dir=tmp_path)
    data = render.load_run_data(out)
    src = render._kpi_source(data.kpis, "drt_rides")
    assert src  # fixture carries a source for the rides row
    import render_drt
    tip = render_drt._tip_src("Bediente DRT-Legs.", data.kpis, "drt_rides")
    assert tip.endswith("Quelle: " + src + ".")
    # absent row -> plain description, no dangling "Quelle:"
    assert render_drt._tip_src("X.", data.kpis, "no_such_kpi") == "X."


def test_channel_share_config_echo_footnote(tmp_path):
    # E5: door==1.0 AND locker==0.0 is a config echo (no lockers staged) and
    # must be footnoted as such in the KPI table.
    out = build(FIX, no_events=True, out_dir=tmp_path)
    data = render.load_run_data(out)
    row = data.kpis.iloc[0].copy()
    for name, val in (("share_channel_door", 1.0), ("share_channel_locker", 0.0)):
        r = row.copy()
        r["kpi_group"] = "channel"
        r["kpi_name"] = name
        r["value"] = val
        r["unit"] = "share"
        r["source"] = "shareduse_channel_stats"
        data.kpis.loc[len(data.kpis)] = r
    html = render.render_kpi_table(data.kpis)
    assert render.CHANNEL_CONFIG_NOTE in html
    # a real (non-echo) split must NOT carry the footnote
    data.kpis.loc[data.kpis["kpi_name"] == "share_channel_door", "value"] = 0.9
    data.kpis.loc[data.kpis["kpi_name"] == "share_channel_locker", "value"] = 0.1
    assert render.CHANNEL_CONFIG_NOTE not in render.render_kpi_table(data.kpis)


def test_headline_kpis_include_shareduse_outcomes():
    # E6: a chi-sweep comparison page must chart delta instead of burying it.
    names = [n for n, *_ in render.HEADLINE_KPIS]
    assert "undelivered_rate" in names
    assert "parcels_delivered" in names
