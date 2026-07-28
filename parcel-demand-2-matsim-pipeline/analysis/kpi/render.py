# -*- coding: utf-8 -*-
"""Lean self-contained HTML dashboard rendered EXCLUSIVELY from the canonical
KPI CSVs (kpis_long.csv + kpi_timeseries.csv). No maps, no per-vehicle
geometry, no plotly — Chart.js 4 (vendored, ~205 KB) is the only script.

Palette = validated dataviz reference palette (categorical slots fixed order;
sequential blue; ink/grid tokens), light + dark via prefers-color-scheme."""
import json
from dataclasses import dataclass
from pathlib import Path

import pandas as pd

VENDOR = Path(__file__).parent / "vendor" / "chart.umd.min.js"

# categorical slots (fixed order, never cycled) — light / dark steps
CAT_LIGHT = ["#2a78d6", "#1baf7a", "#eda100", "#008300",
             "#4a3aa7", "#e34948", "#e87ba4", "#eb6834"]
CAT_DARK = ["#3987e5", "#199e70", "#c98500", "#008300",
            "#9085e9", "#e66767", "#d55181", "#d95926"]
SEQ_LIGHT, SEQ_DARK = "#2a78d6", "#3987e5"   # sequential blue (magnitude charts)
SIZE_LIGHT, SIZE_DARK = "#0f8a86", "#17a39d"  # teal size ramp (S/M/L vtype bars + fleet donut)

# fixed mode->slot assignment (color follows the entity)
MODE_SLOTS = {"car": 0, "ride": 1, "walk": 2, "bike": 3, "drt": 4, "pt": 5}
# fixed scenario->slot assignment for the comparison view (1c/1d extend here)
SCENARIO_SLOTS = {"DRT_BASELINE": 0, "DRT_SHAREDUSE": 1, "DRT_MODULAR": 2, "LMD_BASELINE": 3}

# fixed provider->slot assignment (color follows the entity); unknown/"other" -> None -> gray
OTHER_COLOR = "#8a8f98"
PROVIDER_SLOTS = {"dhl": 0, "amazon": 1, "hermes": 2, "dpd": 3,
                  "gls": 4, "ups": 5, "fedex": 6, "dp/dhl": 7}


def provider_slot(name):
    """int slot for a known provider, else None (rendered gray via OTHER_COLOR)."""
    return PROVIDER_SLOTS.get(name)


def size_marker(indices):
    """__sizes color marker: a list of size-rank ints (0=smallest .. n-1=largest)
    aligned by index to a dataset's data[]. Consumed by resolveColors' teal size
    ramp (alphaSize) -- the same marker/colors are used by D3's LMD vtype bars
    and the fleet composition donut, so both paint with identical shades."""
    return {"__sizes": list(indices)}

CSS = """
:root { color-scheme: light dark; }
body { margin:0; font-family: system-ui, -apple-system, "Segoe UI", sans-serif;
       background: var(--page); color: var(--ink); }
.viz-root {
  --page:#f9f9f7; --surface:#fcfcfb; --ink:#0b0b0b; --ink2:#52514e;
  --muted:#898781; --grid:#e1e0d9; --axis:#c3c2b7;
  --border:rgba(11,11,11,0.10); --seq:#2a78d6; --size:#0f8a86;
}
@media (prefers-color-scheme: dark) { .viz-root {
  --page:#0d0d0d; --surface:#1a1a19; --ink:#ffffff; --ink2:#c3c2b7;
  --muted:#898781; --grid:#2c2c2a; --axis:#383835;
  --border:rgba(255,255,255,0.10); --seq:#3987e5; --size:#17a39d;
}}
.wrap { max-width: min(1680px, 96vw); margin: 0 auto; padding: 24px; }
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
.warnbanner { background:rgba(237,161,0,0.14); border:1px solid #eda100;
  border-radius:8px; padding:8px 12px; font-size:12.5px; margin:0 0 10px; }
"""

TAB_CSS = """
.tabbar { display:flex; gap:6px; margin:14px 0; flex-wrap:wrap; }
.tabbar button { border:1px solid var(--border); background:var(--surface);
  color:var(--ink2); border-radius:8px; padding:6px 12px; cursor:pointer; font-size:13px; }
.tabbar button.on { color:var(--ink); font-weight:600; border-color:var(--axis); }
.tab { display:none; } .tab.on { display:block; }
tr.vehrow { display:none; } tr.vehrow.show { display:table-row; }
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
    # Shared-Use channel outcomes: on a chi sweep the delta lives here -- runs
    # without these rows (baseline) simply skip the bar/cell (see the
    # `v is None -> continue` guard and the "-" union-table fallback below).
    ("undelivered_rate", "Paket-Nichtzustellquote", 100.0, "%"),
    ("parcels_delivered", "Pakete zugestellt", 1.0, ""),
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
const OTHER = '#8a8f98';
Chart.defaults.font.family = 'system-ui, -apple-system, "Segoe UI", sans-serif';
Chart.defaults.color = V('--ink2');
Chart.defaults.borderColor = V('--grid');
Chart.defaults.plugins.legend.labels.boxWidth = 10;
function mk(id, cfg) { new Chart(document.getElementById(id), cfg); }
"""


# canonical empty-with-columns schemas (used when a CSV is missing) — keeps
# downstream .empty checks and column indexing safe regardless of which of
# the 6 canonical CSVs a given run actually produced.
_KPIS_LONG_COLUMNS = ["run_id", "study_area", "scenario", "operation_mode",
                      "kpi_group", "kpi_name", "value", "unit", "source"]
_TS_COLUMNS = ["run_id", "series", "hour", "value", "unit"]
_PROVIDER_COLUMNS = ["run_id", "provider", "kpi_name", "value", "unit", "source"]
_ITERATIONS_COLUMNS = ["run_id", "series", "iteration", "value", "unit"]
_DISTRIBUTIONS_COLUMNS = ["run_id", "series", "bin_lo", "bin_hi", "value", "unit"]
_VEHICLES_COLUMNS = ["run_id", "role", "vehicle_id", "provider", "vehicle_type",
                      "distance_km", "duration_h", "travel_h", "parcels", "stops",
                      "load_factor", "excluded", "occupied_h", "active_h", "shift_h",
                      "ratio_active"]


def _read_csv_or_empty(path, columns):
    return pd.read_csv(path, sep=";") if path.exists() else pd.DataFrame(columns=columns)


@dataclass
class RunData:
    kpis: pd.DataFrame
    ts: pd.DataFrame
    provider: pd.DataFrame
    iterations: pd.DataFrame
    distributions: pd.DataFrame
    vehicles: pd.DataFrame


def load_run_data(analysis_dir):
    """Read all 6 canonical KPI CSVs for one run directory. Each missing CSV
    (including kpis_long.csv itself) falls back to an empty DataFrame with the
    right columns, so callers can always do `.empty` checks and column access."""
    analysis_dir = Path(analysis_dir)
    return RunData(
        kpis=_read_csv_or_empty(analysis_dir / "kpis_long.csv", _KPIS_LONG_COLUMNS),
        ts=_read_csv_or_empty(analysis_dir / "kpi_timeseries.csv", _TS_COLUMNS),
        provider=_read_csv_or_empty(analysis_dir / "kpis_provider.csv", _PROVIDER_COLUMNS),
        iterations=_read_csv_or_empty(analysis_dir / "kpi_iterations.csv", _ITERATIONS_COLUMNS),
        distributions=_read_csv_or_empty(
            analysis_dir / "kpi_distributions.csv", _DISTRIBUTIONS_COLUMNS),
        vehicles=_read_csv_or_empty(analysis_dir / "kpi_vehicles.csv", _VEHICLES_COLUMNS),
    )


def _kpi(kpis, name, default=None):
    m = kpis[kpis["kpi_name"] == name]
    return float(m.iloc[0]["value"]) if len(m) else default


def _kpi_source(kpis, name):
    """The `source` column of a KPI row ("" when the row is absent). Tile
    tooltips must cite THIS, not a hardcoded string: on a Shared-Use run
    pax_only.apply_overrides swaps the canonical row for its pax-only
    correction, so e.g. drt_rides' source becomes "output_drt_legs pax-filter"
    and a literal "drt_customer_stats" citation would be stale exactly when
    the distinction matters."""
    m = kpis[kpis["kpi_name"] == name]
    if not len(m):
        return ""
    src = m.iloc[0]["source"]
    return "" if pd.isna(src) else str(src)


#: E5: door==1 / locker==0 is true BY CONSTRUCTION in this study phase (no
#: Packstationen staged), so the channel-share rows are a config echo, not a
#: finding -- every place they render appends this note.
CHANNEL_CONFIG_NOTE = "Konfiguration (keine Packstationen im Szenario), kein Ergebnis"
_CHANNEL_SHARE_NAMES = ("share_channel_door", "share_channel_locker")


def _channel_config_echo(kpis):
    """True when the channel-share rows merely echo the scenario config:
    locker share exactly 0 AND door share exactly 1."""
    return (_kpi(kpis, "share_channel_door") == 1.0
            and _kpi(kpis, "share_channel_locker") == 0.0)


def _tile(value, label, sub="", tip=""):
    title_attr = (' title="' + tip.replace('"', "&quot;") + '"') if tip else ""
    return ('<div class="tile"' + title_attr + '><div class="v">' + value + '</div><div class="l">'
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


def chart_js(cid, cfg):
    """One `mk(id, resolveColors(cfg));` statement for a chart config."""
    return "mk(" + json.dumps(cid) + ", resolveColors(" + json.dumps(cfg) + "));"


def _donut(cid, title, labels, values, color_marker, height=200, center_label=None):
    """Shared Chart.js doughnut chart for `_render_group` -- returns the same
    `(title, cid, cfg, height)` tuple shape as `render_drt._modal_chart` /
    `_iter_chart`, so D2/D3 can drop it straight into a `_render_group(...,
    charts)` list (panel + `chart_js` wiring is unchanged).

    Per the 2026-07-16 user override, pie/donut is allowed ONLY for the
    modal-split (D2, colored via `{"__slots": [...]}`) and fleet-composition
    (D3, colored via `size_marker([...])`) charts -- `color_marker` is a dict
    merged into the single dataset so resolveColors assigns the fixed colors
    plus the 2px `--surface` segment gap; legend is always shown at the
    bottom; hover shows value + percentage (resolveColors injects the tooltip
    callback for any `type: "doughnut"` cfg). `center_label`, if given,
    overrides the hole text (default: the raw sum of `values`), drawn by the
    `centerTotal` afterDraw plugin registered in DONUT_JS."""
    ds = {"data": list(values), "borderWidth": 2}
    ds.update(color_marker)
    total_text = center_label if center_label is not None else str(sum(values))
    # No in-canvas plugins.title: `_render_group` renders the title as an <h3>
    # panel header (the convention every other chart follows), so an in-canvas
    # title would double-label the donut. Title is returned as tuple[0] for the h3.
    options = {
        "responsive": True,
        "maintainAspectRatio": False,
        "cutout": "62%",
        "plugins": {
            "legend": {"display": True, "position": "bottom"},
            "centerTotal": {"text": total_text},
        },
    }
    cfg = {"type": "doughnut", "data": {"labels": list(labels), "datasets": [ds]},
           "options": options}
    return (title, cid, cfg, height)


def _meta_notes(kpis):
    """Provenance rows (kpi_group == "meta", e.g. parcel_contaminated_kpis,
    run_meta_degraded, fleet_file_missing) as a small "Hinweise" block -- they
    never fit the ["passenger", ..., "channel"] table loop, so before this
    block they were written to the CSV but invisible on the page. Baseline
    runs carry no meta rows -> empty string, page byte-identical."""
    if kpis.empty:
        return ""
    meta = kpis[kpis["kpi_group"] == "meta"]
    if not len(meta):
        return ""
    items = []
    for _, r in meta.iterrows():
        items.append("<li><b>" + str(r["kpi_name"]) + "</b>: " + str(r["value"])
                     + " " + str(r["unit"]) + " &mdash; " + str(r["source"]) + "</li>")
    return ('<h2>Hinweise</h2><div class="panel">'
            '<ul style="margin:0;padding-left:18px">' + "".join(items) + "</ul></div>")


def render_kpi_table(kpis):
    """Full KPI table (grouped, tabular-nums) — the "table view" accessibility
    fallback. Shared by the DRT/LMD tab pages (appended once, below the tabs).
    Prefixed by the meta-group "Hinweise" block (absent on baseline runs)."""
    rows_html = []
    config_echo = (not kpis.empty) and _channel_config_echo(kpis)
    for grp in ["passenger", "system", "freight", "economic", "channel"]:
        for _, r in kpis[kpis["kpi_group"] == grp].iterrows():
            source = str(r["source"])
            if (config_echo and grp == "channel"
                    and r["kpi_name"] in _CHANNEL_SHARE_NAMES):
                source += " &mdash; " + CHANNEL_CONFIG_NOTE
            rows_html.append("<tr><td>" + grp + "</td><td>" + str(r["kpi_name"])
                             + "</td><td>" + str(r["value"]) + "</td><td>"
                             + str(r["unit"]) + "</td><td>" + source + "</td></tr>")
    return (_meta_notes(kpis)
            + '<h2>Alle KPIs</h2><div class="panel tablewrap"><table class="kpis">'
            '<tr><th>Gruppe</th><th>KPI</th><th>Wert</th><th>Einheit</th><th>Quelle</th></tr>'
            + "".join(rows_html) + "</table></div>")


JS_RESOLVE = """
function alphaSeq(a) {
  const hex = V('--seq').replace('#', '');
  const r = parseInt(hex.substring(0, 2), 16);
  const g = parseInt(hex.substring(2, 4), 16);
  const b = parseInt(hex.substring(4, 6), 16);
  return 'rgba(' + r + ',' + g + ',' + b + ',' + a + ')';
}
function alphaSize(a) {
  const hex = V('--size').replace('#', '');
  const r = parseInt(hex.substring(0, 2), 16);
  const g = parseInt(hex.substring(2, 4), 16);
  const b = parseInt(hex.substring(4, 6), 16);
  return 'rgba(' + r + ',' + g + ',' + b + ',' + a + ')';
}
function resolveColors(cfg) {
  const isDonut = cfg.type === 'doughnut';
  for (const ds of cfg.data.datasets) {
    if (ds.__seq) { ds.backgroundColor = V('--seq'); ds.borderColor = V('--seq'); }
    else if (ds.__slot !== undefined) {
      ds.backgroundColor = CAT[ds.__slot %% CAT.length];
      ds.borderColor = ds.backgroundColor;
    }
    else if (ds.__slots !== undefined) {
      ds.backgroundColor = ds.__slots.map(s => s === null ? OTHER : CAT[s %% CAT.length]);
      ds.borderColor = ds.backgroundColor;
    }
    else if (ds.__sizes !== undefined) {
      const maxIdx = Math.max(ds.__sizes.length - 1, 1);
      ds.backgroundColor = ds.__sizes.map(i => alphaSize(0.4 + 0.6 * i / maxIdx));
      ds.borderColor = ds.backgroundColor;
    }
    else if (ds.__ramp !== undefined) {
      const level = ds.__ramp[0], cap = ds.__ramp[1];
      ds.backgroundColor = level === 0 ? OTHER : alphaSeq(0.25 + 0.75 * level / cap);
      ds.borderColor = ds.backgroundColor;
    }
    if (isDonut) { ds.borderColor = V('--surface'); if (ds.borderWidth === undefined) ds.borderWidth = 2; }
    delete ds.__seq; delete ds.__slot; delete ds.__slots; delete ds.__sizes; delete ds.__ramp;
  }
  if (isDonut) {
    cfg.options = cfg.options || {};
    cfg.options.plugins = cfg.options.plugins || {};
    const tt = cfg.options.plugins.tooltip || {};
    tt.callbacks = tt.callbacks || {};
    tt.callbacks.label = function(ctx) {
      const v = ctx.parsed;
      const sum = ctx.dataset.data.reduce((a, b) => a + b, 0);
      const pct = sum ? Math.round(1000 * v / sum) / 10 : 0;
      return ctx.label + ': ' + v + ' (' + pct + '%%)';
    };
    cfg.options.plugins.tooltip = tt;
  }
  return cfg;
}
"""

VLINE_JS = """
const vlinePlugin = { id: 'vlines',
  afterDraw(chart, args, opts) {
    if (!opts || !opts.lines) return;
    const {ctx, chartArea, scales} = chart; const x = scales.x;
    opts.lines.forEach((ln, i) => {
      const px = x.getPixelForValue(ln.x);
      if (px < chartArea.left || px > chartArea.right) return;
      ctx.save(); ctx.strokeStyle = V('--axis'); ctx.setLineDash([4, 3]);
      ctx.beginPath(); ctx.moveTo(px, chartArea.top); ctx.lineTo(px, chartArea.bottom); ctx.stroke();
      ctx.restore();
      // stagger each line's label vertically so Median/O/P95 chips don't overlap,
      // and draw on a small filled chip (high-contrast --ink, not faint --ink2)
      ctx.save();
      ctx.font = '11px system-ui';
      const ty = chartArea.top + 10 + i * 15;
      const tw = ctx.measureText(ln.label).width;
      const padX = 4, bx = px + 4, by = ty - 10, bw = tw + padX * 2, bh = 14;
      ctx.fillStyle = V('--surface'); ctx.strokeStyle = V('--border'); ctx.lineWidth = 1;
      if (ctx.roundRect) {
        ctx.beginPath(); ctx.roundRect(bx, by, bw, bh, 3); ctx.fill(); ctx.stroke();
      } else {
        ctx.fillRect(bx, by, bw, bh); ctx.strokeRect(bx, by, bw, bh);
      }
      ctx.fillStyle = V('--ink');
      ctx.fillText(ln.label, bx + padX, ty);
      ctx.restore();
    });
  }};
Chart.register(vlinePlugin);
"""

DONUT_JS = """
const centerTotalPlugin = { id: 'centerTotal',
  afterDraw(chart, args, opts) {
    if (!opts || opts.text === undefined) return;
    const {ctx, chartArea} = chart;
    ctx.save();
    ctx.fillStyle = V('--ink'); ctx.font = '600 15px system-ui';
    ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
    ctx.fillText(String(opts.text), (chartArea.left + chartArea.right) / 2,
                 (chartArea.top + chartArea.bottom) / 2);
    ctx.restore();
  }};
Chart.register(centerTotalPlugin);
"""

TOGGLE_JS = """
function mkToggle(btnId, canvasId, cfgA, cfgB, labelA, labelB) {
  const resolvedA = resolveColors(cfgA), resolvedB = resolveColors(cfgB);
  const chart = new Chart(document.getElementById(canvasId), resolvedA);
  let showingA = true;
  document.getElementById(btnId).addEventListener('click', () => {
    showingA = !showingA;
    const cfg = showingA ? resolvedA : resolvedB;
    chart.data = cfg.data; chart.options = cfg.options; chart.update();
    document.getElementById(btnId).textContent = showingA ? labelA : labelB;
  });
}
"""

DRILL_JS = """
function toggleVeh(key) {
  document.querySelectorAll('tr[data-drill="' + key + '"]').forEach(r => r.classList.toggle('show'));
}
"""


def render_page(title, body_html, body_js, extra_head=""):
    # `extra_head` (Task 8: the vendored Leaflet CSS+JS + MAP_DATA_<uid> block)
    # is injected into the HEAD region -- right after the page CSS and BEFORE
    # body_html -- so `L` (Leaflet) and MAP_DATA are defined before the final
    # body_js <script> (the tab builders' map JS runs there) executes. Map-free
    # pages pass extra_head="" and carry zero Leaflet bytes.
    vendor = VENDOR.read_text(encoding="utf-8")
    return ("<!-- generated by analysis/kpi -->\n<meta charset='utf-8'>"
            "<title>" + title + "</title><style>" + CSS + "</style>" + extra_head
            + '<div class="viz-root"><div class="wrap"><h1>' + title + "</h1>"
            + body_html + "</div></div>"
            "<script>" + vendor + "</script>"
            "<script>" + (JS_SETUP % (json.dumps(CAT_DARK), json.dumps(CAT_LIGHT)))
            + (JS_RESOLVE % ()) + body_js + "</script>")


def render_run_page(data, title, maps=None):
    """Full run dashboard: DRT tab + LMD tab (as applicable) + KPI table.

    `data`: RunData (see load_run_data). `maps`: optional {"drt": block,
    "lmd": block} passed through to the tab builders as map_block.

    render_drt.build_tab / render_lmd.build_tab (Tasks 5/7) are the real
    renderers -- no fallback, no placeholder (v2 Plan C Task 10). Imported
    here (not at module level) since both import names back out of this
    module -- a module-level import would be circular."""
    import render_drt
    import render_lmd

    maps = maps or {}
    kpis = data.kpis

    has_drt = (not kpis.empty) and (kpis["kpi_group"] == "passenger").any()
    has_lmd = (not data.provider.empty) or (
        (not kpis.empty) and (kpis["kpi_group"] == "freight").any())

    tab_defs = []          # (label, html, js)

    if has_drt:
        html, js = render_drt.build_tab(data, "rd", map_block=maps.get("drt"))
        tab_defs.append(("DRT", html, js))

    if has_lmd:
        html, js = render_lmd.build_tab(data, "rl", map_block=maps.get("lmd"))
        tab_defs.append(("LMD", html, js))

    if not tab_defs:
        body = render_kpi_table(kpis)
        body_js = TAB_JS + VLINE_JS + DONUT_JS + TOGGLE_JS + DRILL_JS
        return render_page(title, body, body_js, extra_head=maps.get("head", ""))

    tabbar = ('<div class="tabbar">' + "".join(
        '<button class="' + ("on" if i == 0 else "") + '" onclick="showTab(' + str(i)
        + ')">' + label + "</button>" for i, (label, _, _) in enumerate(tab_defs))
        + "</div>")
    tabs_html = "".join(
        '<div class="tab' + (" on" if i == 0 else "") + '">' + html + "</div>"
        for i, (_, html, _) in enumerate(tab_defs))
    joined_js = "\n".join(js for _, _, js in tab_defs if js)

    body = tabbar + tabs_html + render_kpi_table(kpis)
    body_js = TAB_JS + VLINE_JS + DONUT_JS + TOGGLE_JS + DRILL_JS + joined_js
    return render_page(title, body, body_js, extra_head=maps.get("head", ""))


def render_comparison_page(runs, title):
    """runs: list of dicts {label, scenario, data (RunData)}.

    Tab 0 (comparison: headline grouped bars + timeseries overlays + full
    KPI comparison table) sources kpis/ts off `r["data"]` but is otherwise
    byte-for-byte the same logic as before Task 10. Per-run tabs are the
    real compact DRT/LMD tab builders (imported here, not at module level,
    for the same circular-import reason as render_run_page)."""
    import render_drt
    import render_lmd

    charts, js = [], []

    # headline grouped horizontal bars: one chart per KPI, one bar per run
    for idx, (name, label, scale, unit) in enumerate(HEADLINE_KPIS):
        labels, values, slots = [], [], []
        for i, r in enumerate(runs):
            v = _kpi(r["data"].kpis, name)
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
            hrs, vals = _series(r["data"].ts, sname)
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
        for _, k in r["data"].kpis.iterrows():
            key = (k["kpi_group"], k["kpi_name"], k["unit"])
            if key not in all_names:
                all_names.append(key)
    header = "<tr><th>Gruppe</th><th>KPI</th><th>Einheit</th>" + "".join(
        "<th>" + r["label"] + "</th>" for r in runs) + "</tr>"
    # E5: the channel-share rows are a config echo (no lockers staged) when
    # EVERY run that carries them shows door==1/locker==0 exactly.
    carrying = [r for r in runs
                if _kpi(r["data"].kpis, "share_channel_door") is not None]
    share_echo = bool(carrying) and all(
        _channel_config_echo(r["data"].kpis) for r in carrying)
    body_rows = []
    for grp, name, unit in all_names:
        cells = ""
        for r in runs:
            kpis = r["data"].kpis
            m = kpis[(kpis["kpi_name"] == name) & (kpis["kpi_group"] == grp)]
            cells += "<td>" + (str(m.iloc[0]["value"]) if len(m) else "-") + "</td>"
        name_cell = name
        if share_echo and grp == "channel" and name in _CHANNEL_SHARE_NAMES:
            name_cell += " &mdash; " + CHANNEL_CONFIG_NOTE
        body_rows.append("<tr><td>" + grp + "</td><td>" + name_cell + "</td><td>"
                         + str(unit) + "</td>" + cells + "</tr>")
    table = ('<h2>Alle KPIs im Vergleich</h2><div class="panel tablewrap">'
             '<table class="kpis">' + header + "".join(body_rows) + "</table></div>")

    cmp_tab = '<div class="grid2">' + "".join(charts) + "</div>" + table

    # per-run tabs: real compact DRT/LMD tab builders, gated by presence
    # exactly like render_run_page's has_drt/has_lmd.
    tabs_html = ['<div class="tab on">' + cmp_tab + "</div>"]
    run_js = []
    for i, r in enumerate(runs):
        data = r["data"]
        kpis = data.kpis
        uid = "run" + str(i)
        has_drt = (not kpis.empty) and (kpis["kpi_group"] == "passenger").any()
        has_lmd = (not data.provider.empty) or (
            (not kpis.empty) and (kpis["kpi_group"] == "freight").any())

        body_parts, js_parts = [], []
        if has_drt:
            h, j = render_drt.build_tab(data, uid, compact=True)
            body_parts.append(h)
            js_parts.append(j)
        if has_lmd:
            h, j = render_lmd.build_tab(data, uid, compact=True)
            body_parts.append(h)
            js_parts.append(j)

        tabs_html.append('<div class="tab">' + "".join(body_parts) + "</div>")
        run_js.append("\n".join(p for p in js_parts if p))
    tabbar = ('<div class="tabbar"><button class="on" onclick="showTab(0)">Vergleich</button>'
              + "".join('<button onclick="showTab(' + str(i + 1) + ')">' + r["label"]
                        + "</button>" for i, r in enumerate(runs)) + "</div>")

    # __slots (per-bar colors) needs a tiny resolver extension.
    # The per-run compact tabs are built by render_drt/render_lmd build_tab and
    # can emit a modal donut (centerTotal), vlines, feeder toggles or drilldowns,
    # so this page must DEFINE those plugins too -- same set render_run_page ships
    # (otherwise e.g. the modal donut's centerTotal plugin is undefined here and
    # its hole total silently never draws).
    body_js = (TAB_JS + VLINE_JS + DONUT_JS + TOGGLE_JS + DRILL_JS
               + "\n" + "\n".join(js) + "\n" + "\n".join(run_js))
    return render_page(title, tabbar + "".join(tabs_html), body_js)
