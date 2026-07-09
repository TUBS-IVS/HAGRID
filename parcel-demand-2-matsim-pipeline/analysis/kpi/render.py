# -*- coding: utf-8 -*-
"""Lean self-contained HTML dashboard rendered EXCLUSIVELY from the canonical
KPI CSVs (kpis_long.csv + kpi_timeseries.csv). No maps, no per-vehicle
geometry, no plotly — Chart.js 4 (vendored, ~205 KB) is the only script.

Palette = validated dataviz reference palette (categorical slots fixed order;
sequential blue; ink/grid tokens), light + dark via prefers-color-scheme."""
import json
from pathlib import Path

import pandas as pd

VENDOR = Path(__file__).parent / "vendor" / "chart.umd.min.js"

# categorical slots (fixed order, never cycled) — light / dark steps
CAT_LIGHT = ["#2a78d6", "#1baf7a", "#eda100", "#008300",
             "#4a3aa7", "#e34948", "#e87ba4", "#eb6834"]
CAT_DARK = ["#3987e5", "#199e70", "#c98500", "#008300",
            "#9085e9", "#e66767", "#d55181", "#d95926"]
SEQ_LIGHT, SEQ_DARK = "#2a78d6", "#3987e5"   # sequential blue (magnitude charts)

# fixed mode->slot assignment (color follows the entity)
MODE_SLOTS = {"car": 0, "ride": 1, "walk": 2, "bike": 3, "drt": 4, "pt": 5}
# fixed scenario->slot assignment for the comparison view (1c/1d extend here)
SCENARIO_SLOTS = {"DRT_BASELINE": 0, "DRT_SHAREDUSE": 1, "DRT_MODULAR": 2, "LMD_BASELINE": 3}

CSS = """
:root { color-scheme: light dark; }
body { margin:0; font-family: system-ui, -apple-system, "Segoe UI", sans-serif;
       background: var(--page); color: var(--ink); }
.viz-root {
  --page:#f9f9f7; --surface:#fcfcfb; --ink:#0b0b0b; --ink2:#52514e;
  --muted:#898781; --grid:#e1e0d9; --axis:#c3c2b7;
  --border:rgba(11,11,11,0.10); --seq:#2a78d6;
}
@media (prefers-color-scheme: dark) { .viz-root {
  --page:#0d0d0d; --surface:#1a1a19; --ink:#ffffff; --ink2:#c3c2b7;
  --muted:#898781; --grid:#2c2c2a; --axis:#383835;
  --border:rgba(255,255,255,0.10); --seq:#3987e5;
}}
.wrap { max-width: 1240px; margin: 0 auto; padding: 24px; }
h1 { font-size: 20px; } h2 { font-size: 15px; color: var(--ink2); margin: 28px 0 10px; }
.tiles { display:grid; grid-template-columns: repeat(auto-fill,minmax(170px,1fr)); gap:10px; }
.tile { background:var(--surface); border:1px solid var(--border); border-radius:10px; padding:12px 14px; }
.tile .v { font-size:26px; font-weight:600; }
.tile .l { font-size:12px; color:var(--ink2); margin-top:2px; }
.tile .s { font-size:11px; color:var(--muted); margin-top:2px; }
.grid2 { display:grid; grid-template-columns:repeat(auto-fit,minmax(420px,1fr)); gap:14px; }
.panel { background:var(--surface); border:1px solid var(--border); border-radius:10px; padding:14px; }
.panel h3 { margin:0 0 8px; font-size:13px; color:var(--ink2); font-weight:600; }
table.kpis { border-collapse:collapse; width:100%; font-size:12.5px; }
table.kpis td, table.kpis th { padding:4px 8px; border-bottom:1px solid var(--grid);
  text-align:left; font-variant-numeric: tabular-nums; }
table.kpis th { color:var(--muted); font-weight:600; }
.tablewrap { overflow-x:auto; }
"""

TAB_CSS = """
.tabbar { display:flex; gap:6px; margin:14px 0; flex-wrap:wrap; }
.tabbar button { border:1px solid var(--border); background:var(--surface);
  color:var(--ink2); border-radius:8px; padding:6px 12px; cursor:pointer; font-size:13px; }
.tabbar button.on { color:var(--ink); font-weight:600; border-color:var(--axis); }
.tab { display:none; } .tab.on { display:block; }
"""

CSS = CSS + TAB_CSS

TAB_JS = """
function showTab(i) {
  document.querySelectorAll('.tab').forEach((t, j) => t.classList.toggle('on', i === j));
  document.querySelectorAll('.tabbar button').forEach((b, j) => b.classList.toggle('on', i === j));
  window.dispatchEvent(new Event('resize'));
}
"""

HEADLINE_KPIS = [
    ("modal_share_drt", "DRT-Modal-Share", 100.0, "%"),
    ("drt_rides", "DRT-Fahrten", 1.0, ""),
    ("wait_median", "Wartezeit Median", 1.0, "s"),
    ("drt_rejection_rate", "Ablehnungsquote", 100.0, "%"),
    ("service_ratio_shift", "Service-Zeit (Schicht)", 100.0, "%"),
    ("drt_vehicle_km", "DRT-Fahrzeug-km", 1.0, "km"),
    ("delivery_rate", "Zustellquote", 100.0, "%"),
    ("freight_vehicle_km", "Freight-km", 1.0, "km"),
    ("freight_cost_per_parcel", "Kosten je Paket", 1.0, "EUR"),
]


def _scenario_slot(scenario, fallback_index):
    return SCENARIO_SLOTS.get(scenario, 4 + (fallback_index % 4))


JS_SETUP = """
const css = getComputedStyle(document.querySelector('.viz-root'));
const V = n => css.getPropertyValue(n).trim();
const DARK = matchMedia('(prefers-color-scheme: dark)').matches;
const CAT = DARK ? %s : %s;
Chart.defaults.font.family = 'system-ui, -apple-system, "Segoe UI", sans-serif';
Chart.defaults.color = V('--ink2');
Chart.defaults.borderColor = V('--grid');
Chart.defaults.plugins.legend.labels.boxWidth = 10;
function mk(id, cfg) { new Chart(document.getElementById(id), cfg); }
"""


def load_run_csvs(analysis_dir):
    analysis_dir = Path(analysis_dir)
    kpis = pd.read_csv(analysis_dir / "kpis_long.csv", sep=";")
    ts_f = analysis_dir / "kpi_timeseries.csv"
    ts = pd.read_csv(ts_f, sep=";") if ts_f.exists() else pd.DataFrame(
        columns=["run_id", "series", "hour", "value", "unit"])
    return kpis, ts


def _kpi(kpis, name, default=None):
    m = kpis[kpis["kpi_name"] == name]
    return float(m.iloc[0]["value"]) if len(m) else default


def _tile(value, label, sub=""):
    return ('<div class="tile"><div class="v">' + value + '</div><div class="l">'
            + label + '</div><div class="s">' + sub + '</div></div>')


def _fmt_pct(v, digits=1):
    return ("{:." + str(digits) + "f}").format(v * 100).replace(".", ",") + " %"


def _fmt_de(v, digits=0):
    s = ("{:,." + str(digits) + "f}").format(v)
    return s.replace(",", "X").replace(".", ",").replace("X", ".")


def _panel(title, canvas_id, height=210):
    return ('<div class="panel"><h3>' + title + '</h3>'
            '<div style="height:' + str(height) + 'px"><canvas id="' + canvas_id
            + '"></canvas></div></div>')


def _series(ts, name):
    m = ts[ts["series"] == name].sort_values("hour")
    return list(m["hour"].astype(int)), list(m["value"].astype(float))


def render_run_sections(kpis, ts, uid):
    """Tiles + charts + table for ONE run. uid makes canvas ids unique so the
    comparison page can embed several runs."""
    html, js = [], []

    tiles = []
    v = _kpi(kpis, "modal_share_drt")
    if v is not None:
        tiles.append(_tile(_fmt_pct(v), "DRT-Modal-Share", "modestats, letzte Iteration"))
    v = _kpi(kpis, "drt_rides")
    if v is not None:
        tiles.append(_tile(_fmt_de(v), "DRT-Fahrten", "bediente Requests"))
    v = _kpi(kpis, "wait_median")
    if v is not None:
        tiles.append(_tile(_fmt_de(v / 60.0, 1) + " min", "Wartezeit (Median)",
                           "P95: " + _fmt_de((_kpi(kpis, "wait_p95") or 0) / 60.0, 1) + " min"))
    v = _kpi(kpis, "drt_rejection_rate")
    if v is not None:
        tiles.append(_tile(_fmt_pct(v, 2), "Ablehnungsquote", "aus Integer-Spalten"))
    v = _kpi(kpis, "drt_vehicles")
    if v is not None:
        tiles.append(_tile(_fmt_de(v), "DRT-Flotte", "Fahrzeuge"))
    v = _kpi(kpis, "service_ratio_shift")
    if v is not None:
        tiles.append(_tile(_fmt_pct(v), "Service-Zeit (Schicht)", "Zeit mit Pax / Schichtzeit"))
    v = _kpi(kpis, "parcels_total")
    if v is not None:
        tiles.append(_tile(_fmt_de(v), "Pakete", "gesamt"))
    v = _kpi(kpis, "delivery_rate")
    if v is not None:
        tiles.append(_tile(_fmt_pct(v), "Zustellquote", "ohne missed/unassigned"))
    v = _kpi(kpis, "freight_vehicles")
    if v is not None:
        tiles.append(_tile(_fmt_de(v), "Lieferfahrzeuge",
                           "Auslastung " + _fmt_pct(_kpi(kpis, "avg_max_load") or 0)))
    v = _kpi(kpis, "freight_total_costs")
    if v is not None:
        tiles.append(_tile(_fmt_de(v) + " EUR", "Freight-Kosten (jsprit)",
                           _fmt_de(_kpi(kpis, "freight_cost_per_parcel") or 0, 2) + " EUR/Paket"))
    html.append('<div class="tiles">' + "".join(tiles) + "</div>")

    charts = []

    # modal split: part-to-whole -> ONE horizontal stacked bar, fixed mode slots
    modes = kpis[kpis["kpi_name"].str.startswith("modal_share_")]
    if len(modes):
        labels, data, colors = [], [], []
        for _, r in modes.iterrows():
            mode = r["kpi_name"].replace("modal_share_", "")
            labels.append(mode)
            data.append(round(float(r["value"]) * 100, 2))
            colors.append(MODE_SLOTS.get(mode, 6))
        charts.append(("Modal Split [%]", "c_modal_" + uid, {
            "type": "bar",
            "data": {"labels": ["Modal Split"],
                     "datasets": [{"label": l, "data": [d], "stack": "s",
                                   "categoryPercentage": 0.5,
                                   "__slot": c} for l, d, c in zip(labels, data, colors)]},
            "options": {"indexAxis": "y", "responsive": True, "maintainAspectRatio": False,
                        "scales": {"x": {"stacked": True, "max": 100,
                                         "grid": {"display": False}},
                                   "y": {"stacked": True, "display": False}}},
        }, 120))

    hrs, rides = _series(ts, "drt_rides")
    if hrs:
        charts.append(("DRT-Fahrten je Stunde", "c_rides_" + uid, {
            "type": "bar",
            "data": {"labels": hrs, "datasets": [{
                "label": "Fahrten/h", "data": rides, "__seq": True,
                "borderRadius": 4, "maxBarThickness": 18}]},
            "options": {"responsive": True, "maintainAspectRatio": False,
                        "plugins": {"legend": {"display": False}}},
        }, 210))

    hrs, wm = _series(ts, "drt_wait_mean")
    if hrs:
        charts.append(("Mittlere Wartezeit je Stunde [s]", "c_wait_" + uid, {
            "type": "line",
            "data": {"labels": hrs, "datasets": [{
                "label": "Wartezeit [s]", "data": wm, "__seq": True,
                "borderWidth": 2, "pointRadius": 0, "tension": 0.25}]},
            "options": {"responsive": True, "maintainAspectRatio": False,
                        "plugins": {"legend": {"display": False}}},
        }, 210))

    hrs, rej = _series(ts, "drt_rejections")
    if hrs:
        charts.append(("Abgelehnte Requests je Stunde", "c_rej_" + uid, {
            "type": "bar",
            "data": {"labels": hrs, "datasets": [{
                "label": "Rejections/h", "data": rej, "__seq": True,
                "borderRadius": 4, "maxBarThickness": 18}]},
            "options": {"responsive": True, "maintainAspectRatio": False,
                        "plugins": {"legend": {"display": False}}},
        }, 180))

    hrs, stops = _series(ts, "freight_service_stops")
    if hrs:
        charts.append(("Freight-Servicestopps je Stunde", "c_frt_" + uid, {
            "type": "bar",
            "data": {"labels": hrs, "datasets": [{
                "label": "Stopps/h", "data": stops, "__seq": True,
                "borderRadius": 4, "maxBarThickness": 18}]},
            "options": {"responsive": True, "maintainAspectRatio": False,
                        "plugins": {"legend": {"display": False}}},
        }, 180))

    html.append('<div class="grid2">'
                + "".join(_panel(t, cid, h) for t, cid, _cfg, h in charts)
                + "</div>")

    # full KPI table (grouped, tabular-nums) — the "table view" accessibility fallback
    rows_html = []
    for grp in ["passenger", "system", "freight", "economic", "channel"]:
        for _, r in kpis[kpis["kpi_group"] == grp].iterrows():
            rows_html.append("<tr><td>" + grp + "</td><td>" + str(r["kpi_name"])
                             + "</td><td>" + str(r["value"]) + "</td><td>"
                             + str(r["unit"]) + "</td><td>" + str(r["source"]) + "</td></tr>")
    html.append('<h2>Alle KPIs</h2><div class="panel tablewrap"><table class="kpis">'
                '<tr><th>Gruppe</th><th>KPI</th><th>Wert</th><th>Einheit</th><th>Quelle</th></tr>'
                + "".join(rows_html) + "</table></div>")

    for _, cid, cfg, _ in charts:
        js.append("mk(" + json.dumps(cid) + ", resolveColors(" + json.dumps(cfg) + "));")
    return "".join(html), "\n".join(js)


JS_RESOLVE = """
function resolveColors(cfg) {
  for (const ds of cfg.data.datasets) {
    if (ds.__seq) { ds.backgroundColor = V('--seq'); ds.borderColor = V('--seq'); }
    else if (ds.__slot !== undefined) {
      ds.backgroundColor = CAT[ds.__slot %% CAT.length];
      ds.borderColor = ds.backgroundColor;
    }
    else if (ds.__slots !== undefined) {
      ds.backgroundColor = ds.__slots.map(s => CAT[s %% CAT.length]);
      ds.borderColor = ds.backgroundColor;
    }
    delete ds.__seq; delete ds.__slot; delete ds.__slots;
  }
  return cfg;
}
"""


def render_page(title, body_html, body_js):
    vendor = VENDOR.read_text(encoding="utf-8")
    return ("<!-- generated by analysis/kpi -->\n<meta charset='utf-8'>"
            "<title>" + title + "</title><style>" + CSS + "</style>"
            '<div class="viz-root"><div class="wrap"><h1>' + title + "</h1>"
            + body_html + "</div></div>"
            "<script>" + vendor + "</script>"
            "<script>" + (JS_SETUP % (json.dumps(CAT_DARK), json.dumps(CAT_LIGHT)))
            + (JS_RESOLVE % ()) + body_js + "</script>")


def render_run_page(kpis, ts, title):
    body, js = render_run_sections(kpis, ts, uid="r0")
    return render_page(title, body, js)


def render_comparison_page(runs, title):
    """runs: list of dicts {label, scenario, kpis (DataFrame), ts (DataFrame)}."""
    charts, js = [], []

    # headline grouped horizontal bars: one chart per KPI, one bar per run
    for idx, (name, label, scale, unit) in enumerate(HEADLINE_KPIS):
        labels, values, slots = [], [], []
        for i, r in enumerate(runs):
            v = _kpi(r["kpis"], name)
            if v is None:
                continue
            labels.append(r["label"])
            values.append(round(v * scale, 3))
            slots.append(_scenario_slot(r["scenario"], i))
        if not values:
            continue
        cid = "cmp_" + str(idx)
        charts.append(_panel(label + ((" [" + unit + "]") if unit and unit != "%" else
                                      (" [%]" if unit == "%" else "")), cid,
                             height=60 + 34 * len(values)))
        js.append("mk(" + json.dumps(cid) + ", resolveColors(" + json.dumps({
            "type": "bar",
            "data": {"labels": labels, "datasets": [{
                "label": label, "data": values,
                "__slots": slots, "borderRadius": 4, "maxBarThickness": 22}]},
            "options": {"indexAxis": "y", "responsive": True,
                        "maintainAspectRatio": False,
                        "plugins": {"legend": {"display": False}}},
        }) + "));")

    # timeseries overlays: one line per run (color = scenario slot)
    for sname, slabel in [("drt_rides", "DRT-Fahrten je Stunde"),
                          ("drt_wait_mean", "Mittlere Wartezeit je Stunde [s]"),
                          ("freight_service_stops", "Freight-Stopps je Stunde")]:
        datasets = []
        for i, r in enumerate(runs):
            hrs, vals = _series(r["ts"], sname)
            if hrs:
                datasets.append({"label": r["label"],
                                 "data": [{"x": h, "y": v} for h, v in zip(hrs, vals)],
                                 "__slot": _scenario_slot(r["scenario"], i),
                                 "borderWidth": 2, "pointRadius": 0, "tension": 0.25})
        if datasets:
            cid = "cmpts_" + sname
            charts.append(_panel(slabel, cid, height=230))
            js.append("mk(" + json.dumps(cid) + ", resolveColors(" + json.dumps({
                "type": "line", "data": {"datasets": datasets},
                "options": {"responsive": True, "maintainAspectRatio": False,
                            "parsing": False,
                            "scales": {"x": {"type": "linear", "min": 0, "max": 30}}},
            }) + "));")

    # full comparison table: KPI rows, runs as columns
    all_names = []
    for r in runs:
        for _, k in r["kpis"].iterrows():
            key = (k["kpi_group"], k["kpi_name"], k["unit"])
            if key not in all_names:
                all_names.append(key)
    header = "<tr><th>Gruppe</th><th>KPI</th><th>Einheit</th>" + "".join(
        "<th>" + r["label"] + "</th>" for r in runs) + "</tr>"
    body_rows = []
    for grp, name, unit in all_names:
        cells = ""
        for r in runs:
            m = r["kpis"][(r["kpis"]["kpi_name"] == name) & (r["kpis"]["kpi_group"] == grp)]
            cells += "<td>" + (str(m.iloc[0]["value"]) if len(m) else "-") + "</td>"
        body_rows.append("<tr><td>" + grp + "</td><td>" + name + "</td><td>"
                         + str(unit) + "</td>" + cells + "</tr>")
    table = ('<h2>Alle KPIs im Vergleich</h2><div class="panel tablewrap">'
             '<table class="kpis">' + header + "".join(body_rows) + "</table></div>")

    cmp_tab = '<div class="grid2">' + "".join(charts) + "</div>" + table

    # per-run tabs reuse the single-run sections
    tabs_html = ['<div class="tab on">' + cmp_tab + "</div>"]
    run_js = []
    for i, r in enumerate(runs):
        body, sec_js = render_run_sections(r["kpis"], r["ts"], uid="run" + str(i))
        tabs_html.append('<div class="tab">' + body + "</div>")
        run_js.append(sec_js)
    tabbar = ('<div class="tabbar"><button class="on" onclick="showTab(0)">Vergleich</button>'
              + "".join('<button onclick="showTab(' + str(i + 1) + ')">' + r["label"]
                        + "</button>" for i, r in enumerate(runs)) + "</div>")

    # __slots (per-bar colors) needs a tiny resolver extension:
    body_js = TAB_JS + "\n" + "\n".join(js) + "\n" + "\n".join(run_js)
    return render_page(title, tabbar + "".join(tabs_html), body_js)
