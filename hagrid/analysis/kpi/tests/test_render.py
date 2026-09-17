# -*- coding: utf-8 -*-
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import pandas as pd

from build_kpis import build
import render

FIX = Path(__file__).parent / "fixtures" / "drtrun"


def _build_clean(tmp_path):
    """`build()` on the baseline fixture WITH a readable DVRP fleet file.

    The fixture directory has no fleet file next to it and `build`'s
    `_default_fleet_file` looks for one two levels up (`hagrid-output/<run_id>/...`),
    so without this the build resolves `fleet_file=None` and `extract_drt` correctly
    emits `meta/fleet_file_missing` -- which puts a "Hinweise" block on every page
    rendered here and makes the "a baseline page carries no meta rows" assertions
    below impossible to state. A real DRT run always writes its fleet file, so
    handing one over is the realistic shape of a clean baseline build, not a
    workaround for the flag. (Backlog parking P1 moved that flag out of the event-
    reconstruction branch, which is why a `no_events=True` build now reaches it.)
    """
    fleet = tmp_path / "fleet.xml"
    fleet.write_text(
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<vehicles>\n'
        '  <vehicle id="drt_0" start_link="1" t_0="0.0" t_1="86400.0" capacity="10"/>\n'
        '</vehicles>\n', encoding="utf-8")
    return build(FIX, no_events=True, fleet_file=fleet, out_dir=tmp_path)


def test_load_run_data_missing_files_graceful(tmp_path):
    d = render.load_run_data(tmp_path)          # no CSVs at all
    assert d.provider.empty and d.vehicles.empty and d.iterations.empty


def test_tile_tooltip():
    html = render._tile("5", "X", tip='a "quoted" tip')
    assert 'title="a &quot;quoted&quot; tip"' in html


def test_run_page_has_drt_tab_and_plugins(tmp_path):
    out = _build_clean(tmp_path)
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
    out = _build_clean(tmp_path)
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
    out = _build_clean(tmp_path)
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
    out = _build_clean(tmp_path)
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
    out = _build_clean(tmp_path)
    data = render.load_run_data(out)
    assert "Hinweise" not in render.render_kpi_table(data.kpis)

    import pax_only
    html = render.render_kpi_table(_with_meta_row(data).kpis)
    assert "Hinweise" in html
    assert pax_only.CONTAMINATION_KPI in html


def test_kpi_source_and_tip_src_cite_actual_source(tmp_path):
    # E4: tooltips must cite the row's ACTUAL source column (pax_only override
    # rewrites it on Shared-Use runs), never a hardcoded string.
    out = _build_clean(tmp_path)
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
    out = _build_clean(tmp_path)
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


def test_table_groups_cover_every_canonical_kpi_group():
    """Whole-branch review Finding 2: the table loop enumerated a hardcoded literal, so
    Task 13's whole `modular` group reached kpis_long.csv and appeared nowhere on the page.
    Deriving the list from common.KPI_GROUPS is what stops that recurring -- assert the
    coverage property itself, not the presence of one group name."""
    import common

    groups = render.table_groups()
    assert "modular" in groups
    assert "meta" not in groups          # rendered separately, as the "Hinweise" block
    missing = [g for g in common.KPI_GROUPS if g != "meta" and g not in groups]
    assert missing == [], missing
    assert len(groups) == len(set(groups))
    # the established reading order is preserved (existing pages stay byte-identical)
    assert groups[:5] == ["passenger", "system", "freight", "economic", "channel"]


def test_modular_kpis_are_rendered_in_the_table(tmp_path):
    """End of the chain: a `modular` row in the CSV must actually appear in the HTML."""
    out = _build_clean(tmp_path)
    data = render.load_run_data(out)
    r = data.kpis.iloc[0].copy()
    r["kpi_group"] = "modular"
    r["kpi_name"] = "swaps_completed"
    r["value"] = 137
    r["unit"] = "swaps"
    r["source"] = "modular_tour_stats"
    data.kpis.loc[len(data.kpis)] = r

    html = render.render_kpi_table(data.kpis)

    assert "swaps_completed" in html
    assert ">modular<" in html


# --- Task 4 (1d paper-readiness fixwave): contamination banner on tiles,
# marker payload on the comparison page, secondary badges (review I1/I8/I4/M11) ---

CONTAMINATION_BADGE = "enthaelt Frachtanteil, s. METHODS-LOG 2.14"


def _with_kpi_row(data, kpi_name, value, kpi_group="system", unit="h", source="events"):
    """Append one synthesized KPI row, following the `_with_meta_row` pattern
    above -- the drtrun fixture (no_events=True) carries none of the
    event-reconstruction-only KPIs the affected tiles need."""
    row = data.kpis.iloc[0].copy()
    row["kpi_group"] = kpi_group
    row["kpi_name"] = kpi_name
    row["value"] = value
    row["unit"] = unit
    row["source"] = source
    data.kpis.loc[len(data.kpis)] = row
    return data


def _with_modular_marker(data, source="subtract: drt_tour_hours_total | rescale x tour/(tour-freight)"):
    """Append the meta/modular_contaminated_kpis provenance row, mimicking
    extract_drt._modular_marker_rows on a DRT_MODULAR run."""
    import extract_drt
    return _with_kpi_row(data, extract_drt.MODULAR_CONTAMINATION_KPI, 8,
                         kpi_group="meta", unit="kpis", source=source)


def _with_secondary_marker(data):
    """Append the meta/modular_secondary_contaminated provenance row."""
    return _with_kpi_row(
        data, "modular_secondary_contaminated", 8, kpi_group="meta", unit="kpis",
        source="kpi_distributions.csv (...) and kpi_vehicles.csv (...); see METHODS-LOG 2.14")


def test_baseline_run_page_has_no_contamination_badges(tmp_path):
    # Pin: without either marker row, no badge string appears anywhere --
    # existing (pre-Task-4) pages must render unchanged.
    out = _build_clean(tmp_path)
    data = render.load_run_data(out)
    html = render.render_run_page(data, title="DRT_TEST")
    assert CONTAMINATION_BADGE not in html
    assert "Frachtexkursionen erscheinen als leere Fahrten" not in html
    assert "pax-bereinigt" not in html


def test_contamination_badge_on_affected_tiles_near_vehicle_km_tile(tmp_path):
    out = _build_clean(tmp_path)
    data = render.load_run_data(out)
    _with_modular_marker(data)
    _with_kpi_row(data, "service_ratio_active", 0.42, unit="share")
    _with_kpi_row(data, "service_ratio_active_pax", 0.55, unit="share")
    _with_kpi_row(data, "fleet_utilisation_by_time", 0.30, unit="share")
    _with_kpi_row(data, "fleet_utilisation_by_time_pax", 0.40, unit="share")
    _with_kpi_row(data, "fleet_utilisation_by_trips", 0.28, unit="share")
    _with_kpi_row(data, "mean_pax_aboard", 0.9, kpi_group="passenger", unit="pax")
    _with_kpi_row(data, "mean_pax_aboard_pax", 1.2, kpi_group="passenger", unit="pax")
    _with_kpi_row(data, "drt_tour_hours_total", 100.0, unit="h")
    _with_kpi_row(data, "drt_tour_hours_total_pax", 80.0, unit="h")

    html = render.render_run_page(data, title="DRT_TEST")

    # one badge for each of the 6 affected tiles (drt_vehicle_km/drt_empty_ratio
    # share ONE tile): Fahrzeug-km, Service-Zeit (aktiv), Auslastung (Fahrten),
    # Auslastung (Zeit), Ø Pax an Bord, Tourdauer gesamt.
    assert html.count(CONTAMINATION_BADGE) == 6
    veh_km_idx = html.index("Fahrzeug-km")
    first_badge_idx = html.index(CONTAMINATION_BADGE)
    assert veh_km_idx < first_badge_idx < veh_km_idx + 400   # badge sits right after the tile
    # *_pax companions render as the tile's sub-line
    assert "pax-bereinigt: 55,0 %" in html
    assert "pax-bereinigt: 40,0 %" in html
    assert "pax-bereinigt: 1,20" in html
    assert "pax-bereinigt: 80,0 h" in html


def test_contamination_badge_absent_without_marker_even_with_pax_rows(tmp_path):
    # A run carrying the *_pax rows but NOT the marker (e.g. Task-3-only CSV
    # from a build that predates Task 4) must not show any badge.
    out = _build_clean(tmp_path)
    data = render.load_run_data(out)
    _with_kpi_row(data, "drt_tour_hours_total", 100.0, unit="h")
    _with_kpi_row(data, "drt_tour_hours_total_pax", 80.0, unit="h")

    html = render.render_run_page(data, title="DRT_TEST")

    assert CONTAMINATION_BADGE not in html
    assert "pax-bereinigt: 80,0 h" in html   # sub-line is independent of the badge


CONTAMINATION_BADGE_HTML = '<div class="warnbanner">' + CONTAMINATION_BADGE + '</div>'


def test_contamination_badge_helper_is_kpi_name_gated():
    # Unit-level: `_contamination_badge` must not fire for an unrelated KPI
    # name even when the marker row IS present (defends every future call
    # site against copy-paste passing the wrong name).
    import extract_drt
    import render_drt
    kpis = pd.DataFrame([
        {"kpi_group": "meta", "kpi_name": extract_drt.MODULAR_CONTAMINATION_KPI,
         "value": 8, "unit": "kpis", "source": "x"},
    ])
    assert render_drt._contamination_badge(kpis, "drt_vehicle_km") == CONTAMINATION_BADGE_HTML
    assert render_drt._contamination_badge(kpis, "drt_rides") == ""


def test_secondary_badge_on_occ_tourduration_and_vehicle_charts():
    import render_drt

    kpis = pd.DataFrame([
        {"kpi_group": "meta", "kpi_name": "modular_secondary_contaminated",
         "value": 8, "unit": "kpis", "source": "kpi_distributions.csv"},
    ])
    distributions = pd.DataFrame([
        {"series": "drt_tour_duration", "bin_lo": 0, "bin_hi": 1, "value": 3, "unit": "h"},
        {"series": "occ_time", "bin_lo": 0, "bin_hi": 0, "value": 0.3, "unit": "share"},
        {"series": "occ_time", "bin_lo": 1, "bin_hi": 1, "value": 0.7, "unit": "share"},
    ])
    vehicles = pd.DataFrame([{"role": "drt", "vehicle_id": "veh1", "occupied_h": 5.5}])
    data = render.RunData(kpis=kpis, ts=pd.DataFrame(columns=["series", "hour", "value"]),
                          provider=pd.DataFrame(), iterations=pd.DataFrame(),
                          distributions=distributions, vehicles=vehicles)

    html, js = render_drt.build_tab(data, uid="drt")

    assert html.count(CONTAMINATION_BADGE) == 3   # occ chart + tour-duration chart + vehicle chart
    for anchor_text in ("Besetzungs-Dekomposition", "Aktive Tourdauer je Fahrzeug",
                        "Besetzte Zeit je Fahrzeug"):
        idx = html.index(anchor_text)
        assert CONTAMINATION_BADGE in html[idx:idx + 600]


def test_secondary_badge_absent_without_marker():
    import render_drt

    kpis = pd.DataFrame(columns=["kpi_group", "kpi_name", "value", "unit", "source"])
    distributions = pd.DataFrame([
        {"series": "drt_tour_duration", "bin_lo": 0, "bin_hi": 1, "value": 3, "unit": "h"},
    ])
    vehicles = pd.DataFrame([{"role": "drt", "vehicle_id": "veh1", "occupied_h": 5.5}])
    data = render.RunData(kpis=kpis, ts=pd.DataFrame(columns=["series", "hour", "value"]),
                          provider=pd.DataFrame(), iterations=pd.DataFrame(),
                          distributions=distributions, vehicles=vehicles)

    html, js = render_drt.build_tab(data, uid="drt")

    assert CONTAMINATION_BADGE not in html


def test_comparison_page_meta_notes_below_table_with_run_label_prefix(tmp_path):
    out = _build_clean(tmp_path)
    data_a = render.load_run_data(out)
    data_b = render.load_run_data(out)
    _with_modular_marker(data_b, source="drt_source_marker_xyz")
    runs = [{"label": "Lauf A", "scenario": "DRT_BASELINE", "data": data_a},
            {"label": "Lauf B", "scenario": "DRT_MODULAR", "data": data_b}]

    html = render.render_comparison_page(runs, title="Vergleich")

    assert "drt_source_marker_xyz" in html
    assert "Lauf B: " in html
    table_idx = html.index("Alle KPIs im Vergleich")
    notes_idx = html.index("Hinweise")
    assert notes_idx > table_idx

    # a comparison of baseline-only runs carries no Hinweise block at all -- and
    # since render_comparison_page also renders each run's per-run tab via
    # build_tab(compact=True), whose _tiles() runs unconditionally, pin the tile
    # badge/sub-line absent there too (a regression could reintroduce it on this
    # page without ever touching render_run_page's own baseline test).
    runs_baseline = [{"label": "Lauf A", "scenario": "DRT_BASELINE", "data": data_a}]
    html_baseline = render.render_comparison_page(runs_baseline, title="Vergleich")
    assert "Hinweise" not in html_baseline
    assert CONTAMINATION_BADGE not in html_baseline
    assert "pax-bereinigt" not in html_baseline


def test_meta_notes_escapes_html_special_characters():
    kpis = pd.DataFrame([
        {"kpi_group": "meta", "kpi_name": "marker<&>", "value": "a<b&c",
         "unit": "u<1>", "source": "a<b&c"},
    ])

    html = render._meta_notes(kpis)

    assert "marker<&>" not in html
    assert "a<b&c" not in html
    assert "u<1>" not in html
    assert "marker&lt;&amp;&gt;" in html   # kpi_name escaped too, not just value/source
    assert html.count("a&lt;b&amp;c") == 2   # value + source, both escaped
    assert "u&lt;1&gt;" in html


def test_maps_drt_vehicle_layer_gains_legend_caption_when_marker_present(tmp_path):
    import maps
    import render_maps

    run = tmp_path / "MINI_run"
    run.mkdir()
    analysis = run / "analysis"
    analysis.mkdir()
    (analysis / "kpis_long.csv").write_text(
        "run_id;study_area;scenario;operation_mode;kpi_group;kpi_name;value;unit;source\n"
        "MINI;x;DRT_MODULAR;y;meta;modular_contaminated_kpis;8;kpis;test\n",
        encoding="utf-8")

    data = maps.build_map_data(run, "MINI")
    assert data["drt"]["modular_contaminated"] is True

    blocks = render_maps.build_blocks(data, uid="m0")
    assert "Frachtexkursionen erscheinen als leere Fahrten" in blocks["drt"]["html"]


def test_maps_drt_vehicle_layer_no_caption_when_marker_absent(tmp_path):
    import maps
    import render_maps

    run = tmp_path / "MINI_run"
    run.mkdir()

    data = maps.build_map_data(run, "MINI")
    assert data["drt"]["modular_contaminated"] is False

    blocks = render_maps.build_blocks(data, uid="m0")
    assert "Frachtexkursionen erscheinen als leere Fahrten" not in blocks["drt"]["html"]
