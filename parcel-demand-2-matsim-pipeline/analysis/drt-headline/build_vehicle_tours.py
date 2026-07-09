# -*- coding: utf-8 -*-
"""
DRT Vehicle Tour Dashboard Builder
-----------------------------------
Reconstructs the *real* per-vehicle tours of the DRT fleet from the MATSim
event stream (actual driven network path, incl. empty deadhead repositioning),
colours each link segment by occupancy (occupied vs empty), and renders an
interactive OSM map with a per-vehicle dropdown -- the DRT analogue of the LMD
freight-tour map.

Inputs (from the fleet80/iter150 headline run):
  - <scratch>/drt_events.txt   pre-filtered DRT events (grep 'vehicle="drt_')
  - *.output_network.xml.gz    link -> node geometry (EPSG:25832)
  - *.output_drt_legs_drt.csv  pickup/dropoff coords + per-vehicle ride counts
  - drt-service-area.shp       service-area polygon (for intermodal stop test)
  - *_rail-transitSchedule.xml.gz  rail stops (intermodal = those in the polygon)

Run:  PYTHONIOENCODING=utf-8 python -u build_vehicle_tours.py
Out:  drt_vehicle_tours.html  (self-contained; OSM tiles need internet)
"""
import os, re, gzip, math
import xml.etree.ElementTree as ET
import pandas as pd
import plotly.graph_objects as go
import plotly.io as pio
from pyproj import Transformer

# ------------------------------------------------------------------ paths
REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
RUN = "DRT_BASELINE_13052025_fleet80_conv150_iter150_jsprit100"
PREFIX = "DRT_BASELINE_13052025_fleet80_conv150"
OUT_DIR = os.path.join(REPO, "parcel-demand-2-matsim-pipeline", "hagrid-matsim-output", RUN)
BASE = os.path.join(OUT_DIR, PREFIX)
ANALYSIS_DIR = os.path.dirname(os.path.abspath(__file__))
HTML_OUT = os.path.join(ANALYSIS_DIR, "drt_vehicle_tours.html")

NETWORK = BASE + ".output_network.xml.gz"
DRT_LEGS = BASE + ".output_drt_legs_drt.csv"
SHP = os.path.join(REPO, "parcel-demand-2-matsim-pipeline", "hagrid-input",
                   "lausitz", "drt", "drt-service-area.shp")
RAIL_SCHED = os.path.join(REPO, "parcel-demand-2-matsim-pipeline", "hagrid-output",
                          PREFIX, PREFIX + "_rail-transitSchedule.xml.gz")

SCRATCH = ("C:/Users/HENDRI~1/AppData/Local/Temp/claude/"
           "c--Users-Hendrik-Bimmermann-Documents-GitHub-HAGRID/"
           "77734cc3-26d2-41f0-ba48-2a0671b91f18/scratchpad")
DRT_EVENTS = os.path.join(SCRATCH, "drt_events.txt")

TF = Transformer.from_crs("EPSG:25832", "EPSG:4326", always_xy=True)

# ------------------------------------------------------------------ 1. events
# Single pass over the (time-ordered) pre-filtered DRT events.
#   - maintain passenger occupancy per vehicle (driver has person==vehicle: skip)
#   - record the ordered (link, occupancy-at-entry) sequence per vehicle
print("Parsing DRT events ...")
re_type = re.compile(r'type="([^"]+)"')
re_link = re.compile(r'link="([^"]+)"')
re_veh = re.compile(r'vehicle="([^"]+)"')
re_pers = re.compile(r'person="([^"]+)"')

occ = {}                     # vehicle -> current passenger count
veh_path = {}                # vehicle -> list of (link_id, occ_at_entry)
used_links = set()
n_lines = 0
with open(DRT_EVENTS, "r", encoding="utf-8") as f:
    for line in f:
        n_lines += 1
        mt = re_type.search(line)
        if not mt:
            continue
        etype = mt.group(1)
        if etype == "entered link":
            mv = re_veh.search(line); ml = re_link.search(line)
            if not mv or not ml:
                continue
            v = mv.group(1)
            if not v.startswith("drt_"):
                continue
            lk = ml.group(1)
            veh_path.setdefault(v, []).append((lk, occ.get(v, 0)))
            used_links.add(lk)
        elif etype == "PersonEntersVehicle":
            mv = re_veh.search(line); mp = re_pers.search(line)
            if not mv or not mp:
                continue
            v = mv.group(1); p = mp.group(1)
            if v.startswith("drt_") and p != v:        # passenger, not driver
                occ[v] = occ.get(v, 0) + 1
        elif etype == "PersonLeavesVehicle":
            mv = re_veh.search(line); mp = re_pers.search(line)
            if not mv or not mp:
                continue
            v = mv.group(1); p = mp.group(1)
            if v.startswith("drt_") and p != v:
                occ[v] = max(0, occ.get(v, 0) - 1)

print(f"  lines={n_lines:,}  vehicles={len(veh_path)}  distinct links used={len(used_links):,}")

# ------------------------------------------------------------------ 2. network
print("Parsing network geometry ...")
node_xy = {}
link_xy = {}     # link_id -> (fx, fy, tx, ty) in EPSG:25832
with gzip.open(NETWORK, "rt", encoding="utf-8") as f:
    for ev, el in ET.iterparse(f, events=("end",)):
        tag = el.tag
        if tag == "node":
            nid = el.get("id")
            node_xy[nid] = (float(el.get("x")), float(el.get("y")))
            el.clear()
        elif tag == "link":
            lid = el.get("id")
            if lid in used_links:
                fn = node_xy.get(el.get("from")); tn = node_xy.get(el.get("to"))
                if fn and tn:
                    link_xy[lid] = (fn[0], fn[1], tn[0], tn[1])
            el.clear()
print(f"  nodes={len(node_xy):,}  link geometries resolved={len(link_xy):,}")

# Reproject every used link's endpoints once (batch per unique link).
print("Reprojecting link endpoints to WGS84 ...")
link_wgs = {}    # link_id -> (flon, flat, tlon, tlat)
_ids = list(link_xy.keys())
_fx = [link_xy[i][0] for i in _ids]; _fy = [link_xy[i][1] for i in _ids]
_tx = [link_xy[i][2] for i in _ids]; _ty = [link_xy[i][3] for i in _ids]
_flon, _flat = TF.transform(_fx, _fy)
_tlon, _tlat = TF.transform(_tx, _ty)
for k, i in enumerate(_ids):
    link_wgs[i] = (round(_flon[k], 5), round(_flat[k], 5),
                   round(_tlon[k], 5), round(_tlat[k], 5))

def link_len_m(lid):
    fx, fy, tx, ty = link_xy[lid]
    return math.hypot(tx - fx, ty - fy)

# ------------------------------------------------------------------ 3. drt_legs (pickup/dropoff + ride counts)
print("Loading drt_legs (pickup/dropoff) ...")
legs = pd.read_csv(DRT_LEGS, sep=";")
legs = legs.dropna(subset=["vehicleId", "fromX", "fromY", "toX", "toY"])
pu_lon, pu_lat = TF.transform(legs["fromX"].values, legs["fromY"].values)
do_lon, do_lat = TF.transform(legs["toX"].values, legs["toY"].values)
legs = legs.assign(pu_lon=pu_lon, pu_lat=pu_lat, do_lon=do_lon, do_lat=do_lat)
rides_per_veh = legs.groupby("vehicleId").size().to_dict()

# ------------------------------------------------------------------ 4. service area + intermodal stops
print("Computing service-area polygon + intermodal rail stops ...")
service_poly = None
sa_lon, sa_lat = [], []
try:
    import geopandas as gpd
    sa = gpd.read_file(SHP)
    service_poly = sa.geometry.union_all() if hasattr(sa.geometry, "union_all") else sa.geometry.unary_union
    # outline -> WGS84 (None-separated rings)
    def _ring(xs, ys):
        lo, la = TF.transform(list(xs), list(ys))
        return list(lo) + [None], list(la) + [None]
    geoms = sa.geometry.tolist()
    for g in geoms:
        polys = g.geoms if g.geom_type == "MultiPolygon" else [g]
        for p in polys:
            xs, ys = p.exterior.xy
            lo, la = _ring(xs, ys)
            sa_lon += lo; sa_lat += la
    print("  service-area polygon OK")
except Exception as e:
    print(f"  service-area polygon FAILED: {e}")

# rail stops referenced by a rail TransitRoute; intermodal = inside the polygon
im_lon, im_lat, im_name = [], [], []
n_rail_total = 0
try:
    from shapely.geometry import Point
    with gzip.open(RAIL_SCHED, "rt", encoding="utf-8") as f:
        root = ET.parse(f).getroot()
    referenced = set()
    for tr in root.findall(".//transitRoute"):
        rp = tr.find("routeProfile")
        if rp is not None:
            for st in rp.findall("stop"):
                if st.get("refId"):
                    referenced.add(st.get("refId"))
    sx, sy, sn = [], [], []
    for s in root.findall(".//stopFacility"):
        if s.get("id") in referenced:
            x = float(s.get("x", 0)); y = float(s.get("y", 0))
            n_rail_total += 1
            if service_poly is not None and service_poly.contains(Point(x, y)):
                sx.append(x); sy.append(y); sn.append(s.get("name", s.get("id", "")))
    if sx:
        im_lon, im_lat = TF.transform(sx, sy)
        im_lon = list(im_lon); im_lat = list(im_lat); im_name = sn
    print(f"  rail-served stations={n_rail_total}  ->  intermodal (in service area)={len(im_lon)}")
except Exception as e:
    print(f"  intermodal stops FAILED: {e}")

# ------------------------------------------------------------------ 5. build per-vehicle tour geometry
print("Building per-vehicle tour geometry ...")
COL_OCC = "#2ECC71"     # occupied  (green)
COL_EMPTY = "#FF5A36"   # empty / deadhead (orange-red)
COL_PU = "#FFD400"      # pickup    (yellow)
COL_DO = "#00BFFF"      # dropoff   (blue)

vehicles = sorted(veh_path.keys(), key=lambda v: int(v.split("_")[1]))

veh_stats = {}   # vehicle -> dict(rides, occ_km, empty_km, deadhead_pct)
veh_geo = {}     # vehicle -> dict(occ=(lon,lat), empty=(lon,lat))
for v in vehicles:
    occ_lon, occ_lat, emp_lon, emp_lat = [], [], [], []
    occ_m = 0.0; emp_m = 0.0
    for lid, o in veh_path[v]:
        w = link_wgs.get(lid)
        if w is None:
            continue
        flon, flat, tlon, tlat = w
        if o >= 1:
            occ_lon += [flon, tlon, None]; occ_lat += [flat, tlat, None]
            occ_m += link_len_m(lid)
        else:
            emp_lon += [flon, tlon, None]; emp_lat += [flat, tlat, None]
            emp_m += link_len_m(lid)
    tot = occ_m + emp_m
    veh_geo[v] = dict(occ=(occ_lon, occ_lat), empty=(emp_lon, emp_lat))
    veh_stats[v] = dict(
        rides=int(rides_per_veh.get(v, 0)),
        occ_km=occ_m / 1000.0,
        empty_km=emp_m / 1000.0,
        deadhead_pct=(emp_m / tot * 100.0) if tot > 0 else 0.0,
    )

total_occ_km = sum(s["occ_km"] for s in veh_stats.values())
total_emp_km = sum(s["empty_km"] for s in veh_stats.values())
fleet_deadhead = total_emp_km / (total_occ_km + total_emp_km) * 100.0
print(f"  fleet veh-km: occupied={total_occ_km:,.0f}  empty={total_emp_km:,.0f}  "
      f"deadhead={fleet_deadhead:.1f}%")

# default selected vehicle = the one with the most rides
default_v = max(vehicles, key=lambda v: veh_stats[v]["rides"])
print(f"  default vehicle = {default_v} ({veh_stats[default_v]['rides']} rides)")

# ------------------------------------------------------------------ 6. plotly figure
print("Building figure ...")
BG = "#0F1117"; TEXT = "#E0E0E0"
fig = go.Figure()

# --- base traces (always visible): service area + intermodal stops
base_traces = 0
if sa_lon:
    fig.add_trace(go.Scattermap(lon=sa_lon, lat=sa_lat, mode="lines",
                  line=dict(color="#00E5FF", width=2),
                  name="Servicegebiet", hoverinfo="name")); base_traces += 1
if im_lon:
    fig.add_trace(go.Scattermap(lon=im_lon, lat=im_lat, mode="markers",
                  marker=dict(size=13, color="#FFE66D"),
                  name="Intermodale Halte (DRT+Bahn)", text=im_name,
                  hovertemplate="%{text}<extra>Intermodaler Halt</extra>")); base_traces += 1

# --- per-vehicle traces: empty, occupied, pickups, dropoffs (4 each)
TRACES_PER_VEH = 4
for v in vehicles:
    el, ela = veh_geo[v]["empty"]
    ol, ola = veh_geo[v]["occ"]
    lv = legs[legs["vehicleId"] == v]
    fig.add_trace(go.Scattermap(lon=el, lat=ela, mode="lines",
                  line=dict(color=COL_EMPTY, width=2),
                  name="Leerfahrt", legendgroup="empty",
                  hoverinfo="skip", visible=False))
    fig.add_trace(go.Scattermap(lon=ol, lat=ola, mode="lines",
                  line=dict(color=COL_OCC, width=3),
                  name="Belegt", legendgroup="occ",
                  hoverinfo="skip", visible=False))
    fig.add_trace(go.Scattermap(lon=lv["pu_lon"], lat=lv["pu_lat"], mode="markers",
                  marker=dict(size=9, color=COL_PU),
                  name="Pickup", legendgroup="pu",
                  hovertemplate="Pickup @ %{customdata}<extra></extra>",
                  customdata=lv["departureTime"].astype(int).astype(str),
                  visible=False))
    fig.add_trace(go.Scattermap(lon=lv["do_lon"], lat=lv["do_lat"], mode="markers",
                  marker=dict(size=9, color=COL_DO, symbol="circle"),
                  name="Dropoff", legendgroup="do",
                  hovertemplate="Dropoff @ %{customdata}<extra></extra>",
                  customdata=lv["arrivalTime"].astype(int).astype(str),
                  visible=False))

total_traces = base_traces + TRACES_PER_VEH * len(vehicles)

def visibility_for(sel_idx):
    """sel_idx = index into `vehicles`, or -1 for 'all (occupied only)'."""
    vis = [True] * base_traces
    for vi in range(len(vehicles)):
        block = [False, False, False, False]   # empty, occ, pu, do
        if sel_idx == -1:
            block[1] = True                     # all: occupied paths only
        elif vi == sel_idx:
            block = [True, True, True, True]     # the selected vehicle: everything
        vis += block
    return vis

def title_for(sel_idx):
    if sel_idx == -1:
        return (f"DRT-Flottentouren - alle {len(vehicles)} Fahrzeuge (nur belegte Fahrten) "
                f"&bull; Flotten-Leerfahrtanteil {fleet_deadhead:.0f}%")
    v = vehicles[sel_idx]; s = veh_stats[v]
    return (f"DRT-Tour {v} &bull; {s['rides']} Fahrgaeste &bull; "
            f"belegt {s['occ_km']:.0f} km / leer {s['empty_km']:.0f} km "
            f"&bull; Leerfahrtanteil {s['deadhead_pct']:.0f}%")

# dropdown buttons: one "all" + one per vehicle
buttons = [dict(label=f"Alle Fahrzeuge ({len(vehicles)})", method="update",
                args=[{"visible": visibility_for(-1)},
                      {"title.text": title_for(-1)}])]
for vi, v in enumerate(vehicles):
    s = veh_stats[v]
    buttons.append(dict(
        label=f"{v}  -  {s['rides']} Fahrten, {s['deadhead_pct']:.0f}% leer",
        method="update",
        args=[{"visible": visibility_for(vi)}, {"title.text": title_for(vi)}]))

# initial state = default vehicle
default_idx = vehicles.index(default_v)
for i, vis in enumerate(visibility_for(default_idx)):
    fig.data[i].visible = vis

# map center = service area centroid (fallback Hoyerswerda)
if im_lon:
    clon = sum(im_lon) / len(im_lon); clat = sum(im_lat) / len(im_lat)
else:
    clon, clat = 14.25, 51.44

fig.update_layout(
    paper_bgcolor=BG, font=dict(color=TEXT, family="system-ui, sans-serif", size=12),
    margin=dict(l=0, r=0, t=70, b=0),
    legend=dict(bgcolor="rgba(0,0,0,0.5)", font=dict(size=11)),
    title=dict(text=title_for(default_idx), font=dict(size=15), x=0.01, xanchor="left"),
    map=dict(style="open-street-map", center=dict(lon=clon, lat=clat), zoom=10),
    height=760,
    updatemenus=[dict(buttons=buttons, direction="down", showactive=True,
                      x=0.01, xanchor="left", y=0.99, yanchor="top",
                      bgcolor="#1A1D27", font=dict(color=TEXT, size=11),
                      active=default_idx + 1)],   # +1 because button 0 = "all"
)

# ------------------------------------------------------------------ 7. write HTML
print("Writing HTML ...")
fig_div = pio.to_html(fig, full_html=False, include_plotlyjs=False, div_id="tourmap")
import plotly
pjs = os.path.join(os.path.dirname(plotly.__file__), "package_data", "plotly.min.js")
with open(pjs, "r", encoding="utf-8") as f:
    plotly_js = f.read()

note = (f"Echte gefahrene Pfade aus dem MATSim-Eventstream (output_events) rekonstruiert. "
        f"Gruen = besetzt, Orange-Rot = Leerfahrt (Deadhead). "
        f"Gelb = Pickup, Blau = Dropoff. {len(vehicles)} Fahrzeuge, "
        f"Flotten-Leerfahrtanteil {fleet_deadhead:.1f}%. "
        f"Dropdown links oben waehlt ein Fahrzeug (oder 'Alle'). "
        f"OSM-Hintergrund braucht Internet fuer die Kacheln.")

html = f"""<!DOCTYPE html>
<html lang="de"><head><meta charset="UTF-8"/>
<meta name="viewport" content="width=device-width, initial-scale=1.0"/>
<title>DRT Vehicle Tours - fleet80 conv150 Lausitz/Hoyerswerda</title>
<script>{plotly_js}</script>
<style>
*,*::before,*::after{{box-sizing:border-box}}
body{{margin:0;padding:14px;background:{BG};color:{TEXT};
font-family:system-ui,-apple-system,sans-serif;font-size:14px}}
h1{{font-size:19px;margin:0 0 4px 0;color:#fff}}
.sub{{color:#888;font-size:12px;margin-bottom:12px}}
.card{{background:#1A1D27;border:1px solid #2A2D3A;border-radius:8px;overflow:hidden}}
.note{{font-size:11px;color:#777;margin-top:10px;border-top:1px solid #2A2D3A;padding-top:8px;line-height:1.5}}
</style></head><body>
<h1>DRT-Fahrzeugtouren &mdash; Lausitz / Hoyerswerda</h1>
<div class="sub">fleet80 &bull; conv150 &bull; 150 Iterationen &bull; echte Touren aus dem Eventstream</div>
<div class="card">{fig_div}</div>
<div class="note"><b>Hinweis:</b> {note}</div>
</body></html>"""

with open(HTML_OUT, "w", encoding="utf-8") as f:
    f.write(html)
print(f"Dashboard written: {HTML_OUT}")
print(f"File size: {os.path.getsize(HTML_OUT)//1024} KB  ({total_traces} traces)")
