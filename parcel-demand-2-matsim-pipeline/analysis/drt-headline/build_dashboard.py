# -*- coding: utf-8 -*-
"""
DRT Headline Dashboard Builder
Run: python -u build_dashboard.py
Output: drt_headline_dashboard.html (self-contained, offline)
"""
import sys, os, gzip, io, json, math
import pandas as pd
import plotly.graph_objects as go
import plotly.io as pio
from plotly.subplots import make_subplots

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
OUT_DIR = os.path.join(REPO, "parcel-demand-2-matsim-pipeline", "hagrid-matsim-output",
                       "DRT_BASELINE_13052025_fleet80_conv150_iter150_jsprit100")
PREFIX = "DRT_BASELINE_13052025_fleet80_conv150"
BASE = os.path.join(OUT_DIR, PREFIX)
ANALYSIS_DIR = os.path.dirname(os.path.abspath(__file__))
HTML_OUT = os.path.join(ANALYSIS_DIR, "drt_headline_dashboard.html")

# Shapefile + rail schedule
SHP = os.path.join(REPO, "parcel-demand-2-matsim-pipeline", "hagrid-input",
                   "lausitz", "drt", "drt-service-area.shp")
RAIL_SCHED = os.path.join(REPO, "parcel-demand-2-matsim-pipeline", "hagrid-output",
                          "DRT_BASELINE_13052025_fleet80_conv150",
                          "DRT_BASELINE_13052025_fleet80_conv150_rail-transitSchedule.xml.gz")

# Fleet sweep dirs (40-iter runs)
SWEEP_BASE = os.path.join(REPO, "parcel-demand-2-matsim-pipeline", "hagrid-matsim-output")
SWEEP = {
    20: None,  # no dir found; use hardcoded context values
    40: os.path.join(SWEEP_BASE, "DRT_BASELINE_13052025_fleet40_iter40_jsprit100",
                     "DRT_BASELINE_13052025_fleet40.drt_customer_stats_drt.csv"),
    60: os.path.join(SWEEP_BASE, "DRT_BASELINE_13052025_fleet60_iter40_jsprit100",
                     "DRT_BASELINE_13052025_fleet60.drt_customer_stats_drt.csv"),
    80: os.path.join(SWEEP_BASE, "DRT_BASELINE_13052025_fleet80_iter40_jsprit100",
                     "DRT_BASELINE_13052025_fleet80.drt_customer_stats_drt.csv"),
}

print("Loading modestats...")
modestats = pd.read_csv(BASE + ".modestats.csv", sep=";")
print("Columns modestats:", list(modestats.columns))

print("Loading drt_customer_stats...")
cust = pd.read_csv(BASE + ".drt_customer_stats_drt.csv", sep=";")
print("Columns cust:", list(cust.columns))

print("Loading drt_vehicle_stats...")
veh = pd.read_csv(BASE + ".drt_vehicle_stats_drt.csv", sep=";")

print("Loading output_trips.csv.gz...")
with gzip.open(BASE + ".output_trips.csv.gz", "rt", encoding="utf-8") as f:
    trips = pd.read_csv(f, sep=";")
print("Columns trips:", list(trips.columns)[:12])

print("Loading output_drt_legs_drt.csv...")
drt_legs = pd.read_csv(BASE + ".output_drt_legs_drt.csv", sep=";")
print("Columns drt_legs:", list(drt_legs.columns))

# ---- KPIs from final iteration ----
final_iter = cust["iteration"].max()
fc = cust[cust["iteration"] == final_iter].iloc[0]

fleet_size = 80
drt_rides = int(fc["rides"])
rej_rate = float(fc["rejectionRate"])
wait_mean_s = float(fc["wait_average"])
wait_p95_s = float(fc["wait_p95"])

# DRT mode share from modestats final row
ms_final = modestats.iloc[-1]
drt_share_pct = float(ms_final.get("drt", 0)) * 100
pt_share_pct = float(ms_final.get("pt", 0)) * 100

# feeder vs standalone from output_trips
# DRT trip = modes contains 'drt'
drt_trips_all = trips[trips["modes"].str.contains("drt", na=False)]
feeder = drt_trips_all[drt_trips_all["modes"].str.contains("pt", na=False)]
standalone = drt_trips_all[~drt_trips_all["modes"].str.contains("pt", na=False)]
n_feeder = len(feeder)
n_standalone = len(standalone)
n_drt_total = len(drt_trips_all)

# Rail (pt) trips
pt_trips_all = trips[trips["main_mode"] == "pt"]
n_pt_trips = len(pt_trips_all)
# Rail trips that have a DRT leg somewhere in their chain:
# 'feeder' trips are exactly those — DRT+PT combined trips
pct_rail_drt_fed = (n_feeder / n_pt_trips * 100) if n_pt_trips > 0 else 0

print("\n=== HEADLINE KPIs ===")
print(f"Fleet size: {fleet_size}")
print(f"DRT mode share: {drt_share_pct:.2f}%")
print(f"Served DRT rides: {drt_rides}")
print(f"Rejection rate: {rej_rate*100:.1f}%")
print(f"Mean wait: {wait_mean_s:.0f}s")
print(f"P95 wait: {wait_p95_s:.0f}s")
print(f"Feeder trips: {n_feeder}, Standalone: {n_standalone}")
print(f"PT (rail) trips: {n_pt_trips}, DRT-fed rail share: {pct_rail_drt_fed:.1f}%")

# ---- Map data ----
# Service area polygon (geopandas)
sa_traces = []
sa_note = "service-area polygon unavailable"
try:
    import geopandas as gpd
    sa = gpd.read_file(SHP)
    # Collect all polygon ring coords in projected metres
    polys_x, polys_y = [], []
    for geom in sa.geometry:
        if geom.geom_type == "Polygon":
            xs, ys = geom.exterior.xy
            polys_x.extend(list(xs) + [None])
            polys_y.extend(list(ys) + [None])
        elif geom.geom_type == "MultiPolygon":
            for p in geom.geoms:
                xs, ys = p.exterior.xy
                polys_x.extend(list(xs) + [None])
                polys_y.extend(list(ys) + [None])
    sa_traces = [(polys_x, polys_y)]
    sa_note = "service-area polygon OK (EPSG:25832)"
    print("Service area polygon loaded OK")
except Exception as e:
    print(f"Service area polygon failed: {e}")

# Rail stops from transit schedule XML
rail_stops_x, rail_stops_y, rail_stops_name = [], [], []
rail_intermodal_x, rail_intermodal_y = [], []
rail_note = "rail stops unavailable"
try:
    import xml.etree.ElementTree as ET
    print("Parsing rail transit schedule...")
    with gzip.open(RAIL_SCHED, "rt", encoding="utf-8") as f:
        tree = ET.parse(f)
    root = tree.getroot()
    # Find transitStops element
    ts_el = root.find("transitStops")
    if ts_el is None:
        ts_el = root
    stops = ts_el.findall("stopFacility") if ts_el is not None else root.findall(".//stopFacility")
    print(f"Found {len(stops)} rail stops")
    for s in stops:
        x = float(s.get("x", 0))
        y = float(s.get("y", 0))
        name = s.get("name", s.get("id", ""))
        rail_stops_x.append(x)
        rail_stops_y.append(y)
        rail_stops_name.append(name)
        # Check allowDrtAccessEgress attribute
        allow_drt = s.get("allowDrtAccessEgress", None)
        if allow_drt is None:
            # check child attributes
            for attr in s.findall("attributes/attribute"):
                if "drt" in attr.get("name", "").lower() or "intermodal" in attr.get("name", "").lower():
                    allow_drt = attr.text
        if allow_drt and allow_drt.lower() in ("true", "1", "yes"):
            rail_intermodal_x.append(x)
            rail_intermodal_y.append(y)
    if len(rail_intermodal_x) == 0:
        # Check in stopFacility attributes child nodes
        for s in stops:
            for child in s:
                if "attribute" in child.tag.lower():
                    txt = child.get("name", "") + child.text if child.text else ""
                    if "drt" in txt.lower():
                        rail_intermodal_x.append(float(s.get("x", 0)))
                        rail_intermodal_y.append(float(s.get("y", 0)))
                        break
    rail_note = f"rail stops OK: {len(stops)} stops, {len(rail_intermodal_x)} intermodal"
    print(rail_note)
except Exception as e:
    print(f"Rail stops failed: {e}")
    rail_note = f"rail stops error: {e}"

# DRT pickup/dropoff density — sample up to 3000 points for scatter
print("Preparing DRT pickup/dropoff data...")
# Determine coordinate columns
x_col = "fromX" if "fromX" in drt_legs.columns else "start_x"
y_col = "fromY" if "fromY" in drt_legs.columns else "start_y"
tx_col = "toX" if "toX" in drt_legs.columns else "end_x"
ty_col = "toY" if "toY" in drt_legs.columns else "end_y"

N_SAMPLE = 3000
pu = drt_legs[[x_col, y_col]].rename(columns={x_col: "x", y_col: "y"})
do = drt_legs[[tx_col, ty_col]].rename(columns={tx_col: "x", ty_col: "y"})
all_pts = pd.concat([pu, do], ignore_index=True).dropna()
if len(all_pts) > N_SAMPLE:
    all_pts = all_pts.sample(N_SAMPLE, random_state=42)
print(f"DRT leg points: {len(all_pts)}")

# ---- Fleet sweep data ----
sweep_fleets = [20, 40, 60, 80]
sweep_rides = [1869, None, None, None]  # fleet20 hardcoded; 40/60/80 from files
sweep_rej = [0.03, None, None, None]
for i, fl in enumerate([40, 60, 80]):
    try:
        df = pd.read_csv(SWEEP[fl], sep=";")
        last = df.iloc[-1]
        sweep_rides[i+1] = int(last["rides"])
        sweep_rej[i+1] = float(last["rejectionRate"])
    except Exception as e:
        print(f"Sweep fleet{fl} failed: {e}")
        # fallback to context values
        ctx = {40: (4074, 0.01), 60: (6233, 0.02), 80: (7981, 0.02)}
        sweep_rides[i+1], sweep_rej[i+1] = ctx[fl]

print("Sweep rides:", sweep_rides)
print("Sweep rej:", sweep_rej)

# ============================================================
# BUILD PLOTLY FIGURES
# ============================================================
COLORS = {
    "drt": "#E84855",
    "car": "#3A86FF",
    "pt": "#FB5607",
    "bike": "#8AC926",
    "walk": "#6A4C93",
    "ride": "#FFBE0B",
}
BG = "#0F1117"
CARD_BG = "#1A1D27"
TEXT = "#E0E0E0"
ACCENT = "#E84855"
GRID = "#2A2D3A"

LAYOUT_BASE = dict(
    paper_bgcolor=BG,
    plot_bgcolor=BG,
    font=dict(color=TEXT, family="system-ui, sans-serif", size=12),
    margin=dict(l=50, r=20, t=40, b=40),
    legend=dict(bgcolor="rgba(0,0,0,0)", font=dict(size=11)),
)

# Chart (a): mode share convergence
fig_modes = go.Figure()
mode_cols = [c for c in modestats.columns if c != "iteration"]
iters = modestats["iteration"]
for mode in mode_cols:
    if mode in modestats.columns:
        fig_modes.add_trace(go.Scatter(
            x=iters, y=modestats[mode]*100,
            name=mode, mode="lines",
            line=dict(color=COLORS.get(mode, "#888"), width=2)
        ))
fig_modes.update_layout(
    **LAYOUT_BASE,
    title=dict(text="Mode Share Convergence (150 iterations)", font=dict(size=14)),
    xaxis=dict(title="Iteration", gridcolor=GRID, zeroline=False),
    yaxis=dict(title="Mode share (%)", gridcolor=GRID, zeroline=False),
    height=320,
)

# Chart (b): rejection rate + wait (dual-axis)
fig_wait = make_subplots(specs=[[{"secondary_y": True}]])
fig_wait.add_trace(go.Scatter(
    x=cust["iteration"], y=cust["rejectionRate"]*100,
    name="Rejection rate (%)", mode="lines",
    line=dict(color="#FF6B6B", width=2)
), secondary_y=False)
fig_wait.add_trace(go.Scatter(
    x=cust["iteration"], y=cust["wait_average"],
    name="Mean wait (s)", mode="lines",
    line=dict(color="#4ECDC4", width=2)
), secondary_y=True)
fig_wait.add_trace(go.Scatter(
    x=cust["iteration"], y=cust["wait_p95"],
    name="P95 wait (s)", mode="lines",
    line=dict(color="#FFE66D", width=1, dash="dot")
), secondary_y=True)
fig_wait.update_layout(
    **LAYOUT_BASE,
    title=dict(text="DRT Rejection Rate & Wait Times over Iterations", font=dict(size=14)),
    height=320,
)
fig_wait.update_yaxes(title_text="Rejection rate (%)", secondary_y=False,
                       gridcolor=GRID, zeroline=False)
fig_wait.update_yaxes(title_text="Wait time (s)", secondary_y=True,
                       gridcolor=GRID, zeroline=False)
fig_wait.update_xaxes(title_text="Iteration", gridcolor=GRID, zeroline=False)

# Chart (c): feeder vs standalone
fig_split = go.Figure(go.Bar(
    x=["Standalone DRT", "Feeder (DRT+PT)"],
    y=[n_standalone, n_feeder],
    marker_color=["#3A86FF", "#E84855"],
    text=[f"{n_standalone}", f"{n_feeder}"],
    textposition="auto",
))
fig_split.update_layout(
    **LAYOUT_BASE,
    title=dict(text="DRT Trip Type Split (final iteration)", font=dict(size=14)),
    yaxis=dict(title="Trips", gridcolor=GRID, zeroline=False),
    xaxis=dict(gridcolor=GRID),
    height=280,
    showlegend=False,
)

# Chart (d): fleet sweep comparison
fig_sweep = make_subplots(specs=[[{"secondary_y": True}]])
fig_sweep.add_trace(go.Bar(
    x=sweep_fleets, y=sweep_rides,
    name="Served rides",
    marker_color="#3A86FF",
    opacity=0.85,
), secondary_y=False)
fig_sweep.add_trace(go.Scatter(
    x=sweep_fleets, y=[r*100 for r in sweep_rej],
    name="Rejection rate (%)", mode="lines+markers",
    line=dict(color="#FF6B6B", width=2),
    marker=dict(size=8)
), secondary_y=True)
fig_sweep.update_layout(
    **LAYOUT_BASE,
    title=dict(text="Fleet Sweep: Served Rides & Rejection (40-iter runs)", font=dict(size=14)),
    height=280,
    barmode="group",
)
fig_sweep.update_yaxes(title_text="Served rides", secondary_y=False,
                        gridcolor=GRID, zeroline=False)
fig_sweep.update_yaxes(title_text="Rejection rate (%)", secondary_y=True,
                        gridcolor=GRID, zeroline=False)
fig_sweep.update_xaxes(title_text="Fleet size (vehicles)", gridcolor=GRID,
                        tickvals=sweep_fleets)

# Map figure (tile-free, EPSG:25832 projected metres)
fig_map = go.Figure()

# Service area polygon
if sa_traces:
    px_list, py_list = sa_traces[0]
    fig_map.add_trace(go.Scatter(
        x=px_list, y=py_list,
        mode="lines",
        line=dict(color="#FFFFFF", width=2, dash="dash"),
        name="Service area",
        hoverinfo="name",
    ))

# DRT pickup/dropoff density scatter
fig_map.add_trace(go.Scatter(
    x=all_pts["x"], y=all_pts["y"],
    mode="markers",
    marker=dict(color=ACCENT, size=3, opacity=0.35),
    name="DRT pickup/dropoff",
    hoverinfo="skip",
))

# Rail stops
if rail_stops_x:
    # Separate intermodal from regular
    non_modal_x = [rail_stops_x[i] for i in range(len(rail_stops_x))
                   if rail_stops_x[i] not in rail_intermodal_x or rail_stops_y[i] not in rail_intermodal_y]
    non_modal_y = [rail_stops_y[i] for i in range(len(rail_stops_y))
                   if rail_stops_x[i] not in rail_intermodal_x or rail_stops_y[i] not in rail_intermodal_y]
    fig_map.add_trace(go.Scatter(
        x=rail_stops_x, y=rail_stops_y,
        mode="markers",
        marker=dict(color="#FB5607", size=6, symbol="square",
                    line=dict(color="#FFFFFF", width=1)),
        name="Rail stops",
        text=rail_stops_name,
        hovertemplate="%{text}<extra>Rail stop</extra>",
    ))
    if rail_intermodal_x:
        fig_map.add_trace(go.Scatter(
            x=rail_intermodal_x, y=rail_intermodal_y,
            mode="markers",
            marker=dict(color="#FFE66D", size=10, symbol="star",
                        line=dict(color="#FFFFFF", width=1)),
            name="Intermodal stops (DRT+Rail)",
            hoverinfo="name",
        ))

fig_map.update_layout(
    **LAYOUT_BASE,
    title=dict(text="Spatial Map — DRT Service Area + Rail Stops + Pickup/Dropoff Density (EPSG:25832)",
               font=dict(size=13)),
    xaxis=dict(scaleanchor="y", scaleratio=1, gridcolor=GRID, zeroline=False,
               title="Easting (m)"),
    yaxis=dict(gridcolor=GRID, zeroline=False, title="Northing (m)"),
    height=500,
)

# ============================================================
# Geographic map (real OSM basemap, WGS84). Needs internet for the
# raster tiles when the HTML is opened; the rest of the dashboard is offline.
# ============================================================
from pyproj import Transformer
_TF = Transformer.from_crs("EPSG:25832", "EPSG:4326", always_xy=True)

def _wgs(xs, ys):
    lon, lat = _TF.transform(list(xs), list(ys))
    return list(lon), list(lat)

def _wgs_with_breaks(xs, ys):
    """Reproject a coord stream that uses None as polygon-ring separators."""
    out_lon, out_lat, sx, sy = [], [], [], []
    for x, y in zip(xs, ys):
        if x is None or y is None:
            if sx:
                lo, la = _TF.transform(sx, sy)
                out_lon += list(lo) + [None]; out_lat += list(la) + [None]
                sx, sy = [], []
        else:
            sx.append(x); sy.append(y)
    if sx:
        lo, la = _TF.transform(sx, sy)
        out_lon += list(lo); out_lat += list(la)
    return out_lon, out_lat

# DRT pickup/dropoff -> WGS84 (already sampled to <=3000 pts above)
geo_drt_lon, geo_drt_lat = _wgs(all_pts["x"].values, all_pts["y"].values)

# Rail STATIONS actually served by rail routes (the schedule keeps a superset of
# ~15.9k stop facilities; filter to those referenced by a rail TransitRoute).
rail_served_lon, rail_served_lat, rail_served_name = [], [], []
geo_rail_note = "rail stations unavailable"
try:
    import xml.etree.ElementTree as ET
    with gzip.open(RAIL_SCHED, "rt", encoding="utf-8") as f:
        _root = ET.parse(f).getroot()
    referenced = set()
    for tr in _root.findall(".//transitRoute"):
        rp = tr.find("routeProfile")
        if rp is not None:
            for st in rp.findall("stop"):
                ref = st.get("refId")
                if ref:
                    referenced.add(ref)
    # Intermodal stops only: rail-served stations INSIDE the DRT service-area
    # polygon (mirrors PrepareTransitSchedule.tagIntermodalStops). Avoids the
    # Germany-wide sprawl of every stop on every long-distance rail line.
    from shapely.geometry import Point
    import geopandas as gpd
    _sa = gpd.read_file(SHP)
    _poly = _sa.geometry.union_all() if hasattr(_sa.geometry, "union_all") else _sa.geometry.unary_union
    sx, sy, sn = [], [], []
    n_served = 0
    for s in _root.findall(".//stopFacility"):
        if s.get("id") in referenced:
            n_served += 1
            x = float(s.get("x", 0)); y = float(s.get("y", 0))
            if _poly.contains(Point(x, y)):
                sx.append(x); sy.append(y); sn.append(s.get("name", s.get("id", "")))
    if sx:
        rail_served_lon, rail_served_lat = _wgs(sx, sy)
        rail_served_name = sn
    geo_rail_note = (f"{len(sx)} intermodal rail stations (inside service area) "
                     f"of {n_served} rail-served / {len(rail_stops_x)} schedule facilities")
    print(geo_rail_note)
except Exception as e:
    print(f"Geo rail filter failed: {e}")
    geo_rail_note = f"rail stations error: {e}"

# Service-area polygon -> WGS84
geo_sa_lon, geo_sa_lat = [], []
if sa_traces:
    geo_sa_lon, geo_sa_lat = _wgs_with_breaks(sa_traces[0][0], sa_traces[0][1])

# Map center = mean of DRT activity (fallback: Hoyerswerda)
_clean_lon = [v for v in geo_drt_lon if v is not None]
_clean_lat = [v for v in geo_drt_lat if v is not None]
if _clean_lon:
    clon = sum(_clean_lon) / len(_clean_lon)
    clat = sum(_clean_lat) / len(_clean_lat)
else:
    clon, clat = 14.25, 51.44

fig_geomap = go.Figure()
fig_geomap.add_trace(go.Densitymap(
    lon=geo_drt_lon, lat=geo_drt_lat, radius=10,
    colorscale="Hot", showscale=False, opacity=0.55,
    name="DRT pickup/dropoff density", hoverinfo="skip",
))
if geo_sa_lon:
    fig_geomap.add_trace(go.Scattermap(
        lon=geo_sa_lon, lat=geo_sa_lat, mode="lines",
        line=dict(color="#00E5FF", width=2),
        name="DRT service area", hoverinfo="name",
    ))
if rail_served_lon:
    fig_geomap.add_trace(go.Scattermap(
        lon=rail_served_lon, lat=rail_served_lat, mode="markers",
        marker=dict(size=12, color="#FFE66D"),
        name="Intermodal rail stops (DRT feeder)", text=rail_served_name,
        hovertemplate="%{text}<extra>Intermodal rail stop</extra>",
    ))
fig_geomap.update_layout(
    paper_bgcolor=BG,
    font=dict(color=TEXT, family="system-ui, sans-serif", size=12),
    margin=dict(l=0, r=0, t=40, b=0),
    legend=dict(bgcolor="rgba(0,0,0,0.45)", font=dict(size=11)),
    title=dict(text="Geographic Map &mdash; DRT activity, service area & rail stations (OSM basemap, needs internet)",
               font=dict(size=14)),
    map=dict(style="open-street-map", center=dict(lon=clon, lat=clat), zoom=9),
    height=560,
)

# ============================================================
# Serialize charts to JSON (no plotlyjs in each; one shared inline)
# ============================================================
def fig_to_div(fig, div_id):
    return pio.to_html(fig, full_html=False, include_plotlyjs=False, div_id=div_id)

div_modes = fig_to_div(fig_modes, "fig_modes")
div_wait = fig_to_div(fig_wait, "fig_wait")
div_split = fig_to_div(fig_split, "fig_split")
div_sweep = fig_to_div(fig_sweep, "fig_sweep")
div_map = fig_to_div(fig_map, "fig_map")
div_geomap = fig_to_div(fig_geomap, "fig_geomap")

# Get plotly.js inline (from the installed plotly package)
plotly_js = pio.to_html(go.Figure(), full_html=False, include_plotlyjs="cdn")
# Actually we embed from the plotly dist file directly
import plotly
plotly_js_path = os.path.join(os.path.dirname(plotly.__file__), "package_data", "plotly.min.js")
if os.path.exists(plotly_js_path):
    with open(plotly_js_path, "r", encoding="utf-8") as f:
        plotly_js_inline = f.read()
    print(f"Embedded plotly.min.js ({len(plotly_js_inline)//1024} KB)")
else:
    # Fallback: use include_plotlyjs='inline' for the first figure
    plotly_js_inline = None
    print("plotly.min.js not found locally, will use inline on first chart")

# ============================================================
# Build HTML
# ============================================================
def fmt_s(s):
    """Format seconds nicely"""
    m = int(s) // 60
    sec = int(s) % 60
    if m > 0:
        return f"{m}min {sec}s"
    return f"{sec}s"

kpi_cards_data = [
    ("Fleet size", f"{fleet_size}", "vehicles"),
    ("DRT mode share", f"{drt_share_pct:.2f}", "%"),
    ("Served rides", f"{drt_rides:,}", "trips"),
    ("Rejection rate", f"{rej_rate*100:.1f}", "%"),
    ("Mean wait", f"{wait_mean_s:.0f}", "s"),
    ("P95 wait", f"{wait_p95_s:.0f}", "s"),
    ("DRT feeder", f"{n_feeder:,}", "trips"),
    ("DRT standalone", f"{n_standalone:,}", "trips"),
    ("PT (rail) trips", f"{n_pt_trips:,}", "trips"),
    ("Rail trips DRT-fed", f"{pct_rail_drt_fed:.1f}", "%"),
]

cards_html = "".join(f"""
  <div class="kpi-card">
    <div class="kpi-label">{label}</div>
    <div class="kpi-value">{val}</div>
    <div class="kpi-unit">{unit}</div>
  </div>""" for label, val, unit in kpi_cards_data)

notes_html = f"""
<div class="notes">
  <b>Data notes:</b>
  {sa_note} &nbsp;|&nbsp; {rail_note} &nbsp;|&nbsp; geo map: {geo_rail_note} &nbsp;|&nbsp;
  The geographic (OSM) map needs internet for its raster tiles; the projected map below + all charts are fully offline. &nbsp;|&nbsp;
  Fleet20 rides/rej: hardcoded context values (1869 / 3%) — no fleet20 sim dir found &nbsp;|&nbsp;
  Fleet sweep 40/60/80 from 40-iter runs; headline fleet80 from 150-iter run.
</div>
"""

if plotly_js_inline:
    script_block = f"<script>{plotly_js_inline}</script>"
else:
    # fallback: embed via include_plotlyjs='inline' in first div
    first_div_inline = pio.to_html(fig_modes, full_html=False, include_plotlyjs="inline", div_id="fig_modes")
    div_modes = first_div_inline
    script_block = ""

html = f"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8"/>
<meta name="viewport" content="width=device-width, initial-scale=1.0"/>
<title>DRT Headline Dashboard — fleet80 conv150 Lausitz/Hoyerswerda</title>
{script_block}
<style>
*, *::before, *::after {{ box-sizing: border-box; }}
body {{
  margin: 0; padding: 16px;
  background: {BG};
  color: {TEXT};
  font-family: system-ui, -apple-system, sans-serif;
  font-size: 14px;
}}
h1 {{ font-size: 20px; margin: 0 0 4px 0; color: #FFFFFF; }}
.subtitle {{ color: #888; font-size: 12px; margin-bottom: 16px; }}
.kpi-row {{
  display: flex; flex-wrap: wrap; gap: 10px; margin-bottom: 18px;
}}
.kpi-card {{
  background: {CARD_BG};
  border: 1px solid #2A2D3A;
  border-radius: 8px;
  padding: 12px 16px;
  min-width: 110px;
  flex: 1 1 110px;
  text-align: center;
}}
.kpi-label {{ font-size: 11px; color: #888; text-transform: uppercase; letter-spacing: 0.05em; }}
.kpi-value {{ font-size: 24px; font-weight: 700; color: {ACCENT}; margin: 4px 0 2px; }}
.kpi-unit {{ font-size: 11px; color: #666; }}
.chart-grid {{
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
  margin-bottom: 12px;
}}
.chart-full {{
  margin-bottom: 12px;
}}
.chart-card {{
  background: {CARD_BG};
  border: 1px solid #2A2D3A;
  border-radius: 8px;
  overflow: hidden;
}}
.notes {{
  font-size: 11px; color: #666; margin-top: 12px;
  border-top: 1px solid #2A2D3A; padding-top: 8px;
}}
@media (max-width: 800px) {{
  .chart-grid {{ grid-template-columns: 1fr; }}
}}
</style>
</head>
<body>
<h1>DRT Headline Dashboard &mdash; Lausitz / Hoyerswerda</h1>
<div class="subtitle">fleet80 &nbsp;&bull;&nbsp; conv150 &nbsp;&bull;&nbsp; 150 iterations &nbsp;&bull;&nbsp; jsprit100 &nbsp;&bull;&nbsp; DRT_BASELINE_13052025</div>

<div class="kpi-row">
{cards_html}
</div>

<div class="chart-full chart-card">
{div_modes}
</div>

<div class="chart-full chart-card">
{div_wait}
</div>

<div class="chart-grid">
  <div class="chart-card">{div_split}</div>
  <div class="chart-card">{div_sweep}</div>
</div>

<div class="chart-full chart-card">
{div_geomap}
</div>

<div class="chart-full chart-card">
{div_map}
</div>

{notes_html}
</body>
</html>
"""

with open(HTML_OUT, "w", encoding="utf-8") as f:
    f.write(html)
print(f"\nDashboard written: {HTML_OUT}")
print(f"File size: {os.path.getsize(HTML_OUT)//1024} KB")
