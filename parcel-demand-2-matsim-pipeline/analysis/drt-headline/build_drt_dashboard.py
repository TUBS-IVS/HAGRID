# -*- coding: utf-8 -*-
"""
DRT Dashboard (depot-dispatching headline) — Lausitz / Hoyerswerda
==================================================================
One self-contained HTML for the passenger-DRT run:
  • Headline KPI cards (all with tooltip), incl. placeholder COST KPI + mean pax aboard.
  • Per-vehicle tour MAP (CARTO dark, line color = passengers aboard (occupancy scale),
    numbered stops, fleet-wide PUDO dots, depot markers, heatmap toggle for pickups/dropoffs).
  • Tagesverlauf: demand, rejections, mean wait, PT-feeder (abs/share toggle).
  • Verteilungen: wait, tour distance, tour duration.
  • Besetzungs-Decomposition, final modal split (pie), convergence, service-time detail.

Run:  PYTHONIOENCODING=utf-8 python -u build_drt_dashboard.py
Out:  drt_dashboard.html   (self-contained; CARTO tiles need internet)
"""
import os, re, gzip, math
import xml.etree.ElementTree as ET
import pandas as pd
import plotly.graph_objects as go
import plotly.io as pio
from plotly.subplots import make_subplots
from pyproj import Transformer

from drt_service_time import reconstruct, _fmt_hms   # validated service-time core

# ------------------------------------------------------------------ paths
REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
RUN = "DRT_BASELINE_13052025_fleet120_depot_railpt_iter150_jsprit100"
PREFIX = "DRT_BASELINE_13052025_fleet120_depot_railpt"
MM = os.path.join(REPO, "parcel-demand-2-matsim-pipeline", "hagrid-matsim-output", RUN)
HO = os.path.join(REPO, "parcel-demand-2-matsim-pipeline", "hagrid-output", PREFIX)
BASE = os.path.join(MM, PREFIX)
ANALYSIS_DIR = os.path.dirname(os.path.abspath(__file__))
HTML_OUT = os.path.join(ANALYSIS_DIR, "drt_dashboard.html")

NETWORK = BASE + ".output_network.xml.gz"
DRT_LEGS = BASE + ".output_drt_legs_drt.csv"
CUST = BASE + ".drt_customer_stats_drt.csv"
VSTATS = BASE + ".drt_vehicle_stats_drt.csv"
MODESTATS = BASE + ".modestats.csv"
TRIPS = BASE + ".output_trips.csv.gz"
FLEET = os.path.join(HO, PREFIX + "_drt_fleet.xml.gz")
RAIL_SCHED = os.path.join(HO, PREFIX + "_rail-transitSchedule.xml.gz")
SHP = os.path.join(REPO, "parcel-demand-2-matsim-pipeline", "hagrid-input", "lausitz", "drt", "drt-service-area.shp")
DEPOT_CSV = os.path.join(REPO, "parcel-demand-2-matsim-pipeline", "hagrid-input", "lausitz", "hubs", "lmd-depots.csv")

# Pre-filtered drt event cache. Lives INSIDE this run's output directory so it is physically
# keyed to the run — a stale copy from another run can never be picked up (the old hardcoded
# scratchpad path mixed fleet80_depot events into the railpt dashboard). Built once on first run.
EV = os.path.join(MM, PREFIX + ".drt_events_filtered.txt")
RAW_EVENTS = BASE + ".output_events.xml.gz"

FLEET_SIZE = 80
TF = Transformer.from_crs("EPSG:25832", "EPSG:4326", always_xy=True)
BG = "#0F1117"; CARD = "#1A1D27"; BORDER = "#2A2D3A"; TEXT = "#E0E0E0"; DIM = "#8A93A6"; ACC = "#38BDF8"
COL_OCC = "#4ADE80"; COL_EMPTY = "#94A3B8"; COL_PU = "#FDE68A"; COL_DO = "#7DD3FC"
COL_DEPOT = "#F472B6"

# NOTE: the legacy pastel per-vehicle palette (TOUR_COLORS) was dropped 2026-07-02 — tour lines
# are now colored by OCCUPANCY LEVEL (OCC_COLORS, same scale as the occupancy chart).

# ------------------------------------------------------------ PLACEHOLDER cost function
# Zeitbasiert nach Rudolph-Ankern (~80/20 Personal/Fahrzeug): 20 €/h Personal + 5 €/h Fahrzeug,
# Preisbasis Fahrzeug-SCHICHTstunde (Idle zählt mit). !! PLATZHALTER — Kostenfunktion ist noch
# zu präzisieren (Beschluss 2026-07-02); Zahl nur als Größenordnung interpretieren. !!
COST_LABOUR_EUR_H = 20.0
COST_VEHICLE_EUR_H = 5.0
# Literatur-Benchmark (Currie & Fournier 2020, Transport Policy: DRT operating cost per
# vehicle-hour, AU$ 2019): 3. Ära (2009-2019) Median ~110 AU$ ≈ 68 €; 2. Ära ~60 AU$ ≈ 37 €.
# Vollkosten inkl. Overhead — brackets das Bottom-up-Modell (das nur Personal+Fahrzeug enthält).
COST_LIT_EUR_H = 68.0
COST_LIT_LOW_EUR_H = 37.0

# build the per-run event cache on first use (same filter as the old manual grep 'drt_')
if not os.path.exists(EV):
    print(f"Filtering drt events from {RAW_EVENTS} (one-time per run) ...")
    with gzip.open(RAW_EVENTS, "rt", encoding="utf-8") as src, \
            open(EV, "w", encoding="utf-8") as dst:
        for line in src:
            if "drt_" in line:
                dst.write(line)

# ------------------------------------------------------------------ 1. service time (validated core)
print("Reconstructing service time ...")
svc = reconstruct(EV, FLEET)
fleet = svc["fleet"]; per_veh = svc["per_veh"]; sim_h = svc["sim_horizon"]
print(f"  fleet active={fleet['ratio_active']*100:.1f}%  shift={fleet['ratio_shift']*100:.1f}%  sim={fleet['ratio_sim']*100:.1f}%")

# ------------------------------------------------------------------ 2. map pass: per-vehicle link path + occupancy + rejections
print("Reconstructing per-vehicle tours (map) ...")
re_type = re.compile(r'type="([^"]+)"'); re_link = re.compile(r'link="([^"]+)"')
re_veh = re.compile(r'\bvehicle="([^"]+)"'); re_pers = re.compile(r'person="([^"]+)"')
re_time = re.compile(r'time="([0-9.]+)"')
occ = {}; veh_path = {}; used_links = set()
rej_times = []          # simulation times of rejected drt requests (final iteration)

def _ev_lines(path):
    op = gzip.open(path, "rt", encoding="utf-8") if path.endswith(".gz") else open(path, "r", encoding="utf-8")
    with op as f:
        for line in f:
            if "drt_" in line:
                yield line

for line in _ev_lines(EV):
    mt = re_type.search(line)
    if not mt:
        continue
    et = mt.group(1)
    if et == "entered link":
        mv = re_veh.search(line); ml = re_link.search(line)
        if mv and ml and mv.group(1).startswith("drt_"):
            v = mv.group(1); veh_path.setdefault(v, []).append((ml.group(1), occ.get(v, 0))); used_links.add(ml.group(1))
    elif et == "PersonEntersVehicle":
        mv = re_veh.search(line); mp = re_pers.search(line)
        if mv and mp and mv.group(1).startswith("drt_") and mp.group(1) != mv.group(1):
            occ[mv.group(1)] = occ.get(mv.group(1), 0) + 1
    elif et == "PersonLeavesVehicle":
        mv = re_veh.search(line); mp = re_pers.search(line)
        if mv and mp and mv.group(1).startswith("drt_") and mp.group(1) != mv.group(1):
            occ[mv.group(1)] = max(0, occ.get(mv.group(1), 0) - 1)
    elif et == "PassengerRequest rejected" and 'mode="drt"' in line:
        mtm = re_time.search(line)
        if mtm:
            rej_times.append(float(mtm.group(1)))
print(f"  vehicles with path={len(veh_path)}  distinct links={len(used_links):,}  rejections={len(rej_times)}")

# ------------------------------------------------------------------ 3. network geometry (only used links)
print("Parsing network geometry ...")
node_xy = {}; link_xy = {}
with gzip.open(NETWORK, "rt", encoding="utf-8") as f:
    for _, el in ET.iterparse(f, events=("end",)):
        if el.tag == "node":
            node_xy[el.get("id")] = (float(el.get("x")), float(el.get("y"))); el.clear()
        elif el.tag == "link":
            lid = el.get("id")
            if lid in used_links:
                fn = node_xy.get(el.get("from")); tn = node_xy.get(el.get("to"))
                if fn and tn:
                    link_xy[lid] = (fn[0], fn[1], tn[0], tn[1])
            el.clear()
link_wgs = {}
_ids = list(link_xy.keys())
if _ids:
    _flon, _flat = TF.transform([link_xy[i][0] for i in _ids], [link_xy[i][1] for i in _ids])
    _tlon, _tlat = TF.transform([link_xy[i][2] for i in _ids], [link_xy[i][3] for i in _ids])
    for k, i in enumerate(_ids):
        link_wgs[i] = (round(_flon[k], 5), round(_flat[k], 5), round(_tlon[k], 5), round(_tlat[k], 5))

def link_len_m(lid):
    fx, fy, tx, ty = link_xy[lid]; return math.hypot(tx - fx, ty - fy)

# ------------------------------------------------------------------ 4. drt_legs (pickup/dropoff + rides per veh)
print("Loading drt_legs ...")
legs = pd.read_csv(DRT_LEGS, sep=";").dropna(subset=["vehicleId", "fromX", "fromY", "toX", "toY"])
pu_lon, pu_lat = TF.transform(legs["fromX"].values, legs["fromY"].values)
do_lon, do_lat = TF.transform(legs["toX"].values, legs["toY"].values)
legs = legs.assign(pu_lon=pu_lon, pu_lat=pu_lat, do_lon=do_lon, do_lat=do_lat)
rides_per_veh = legs.groupby("vehicleId").size().to_dict()

# ------------------------------------------------------------------ 5. per-vehicle map geometry + km
# Geometry is bucketed by OCCUPANCY LEVEL (0 = leer, 1..CAP Pax): the map colors every traversed
# link by how many passengers were aboard — same OCC_COLORS scale as the occupancy chart —
# instead of one arbitrary pastel color per vehicle (user fix 2026-07-02).
vehicles = sorted(veh_path.keys(), key=lambda v: int(v.split("_")[1]))
veh_geo_lv = {}   # vehicle -> {occupancy level -> ([lons], [lats]) with None separators}
veh_km = {}
dist_by_occ = {}            # occupancy level -> fleet driven km on that level (deadhead = level 0)
for v in vehicles:
    geo = {}; om = em = 0.0
    for lid, o in veh_path[v]:
        w = link_wgs.get(lid)
        if w is None:
            continue
        flon, flat, tlon, tlat = w
        dist_by_occ[o] = dist_by_occ.get(o, 0.0) + link_len_m(lid) / 1000.0
        lons, lats = geo.setdefault(o, ([], []))
        lons += [flon, tlon, None]; lats += [flat, tlat, None]
        if o >= 1:
            om += link_len_m(lid)
        else:
            em += link_len_m(lid)
    veh_geo_lv[v] = geo
    veh_km[v] = (om / 1000.0, em / 1000.0)
fleet_occ_km = sum(k[0] for k in veh_km.values()); fleet_emp_km = sum(k[1] for k in veh_km.values())
fleet_total_km = fleet_occ_km + fleet_emp_km
fleet_deadhead = fleet_emp_km / fleet_total_km * 100.0 if fleet_total_km else 0.0

# person km: actual in-vehicle routed distance per leg (travelDistance_m, incl. pooling detour).
# NOTE: do NOT use straight-line fromX/toX — that under-reports by ~2x (was a bug).
legs = legs.assign(leg_km=legs["travelDistance_m"] / 1000.0)
person_km = legs["leg_km"].sum()
person_km_per_veh = legs.groupby("vehicleId")["leg_km"].sum().to_dict()
# detour factor = actual routed distance / direct (beeline-network) distance, fleet aggregate
_direct_km = legs["directTravelDistance_m"].sum() / 1000.0
detour_factor = (person_km / _direct_km) if _direct_km else 0.0

# ------------------------------------------------------------------ 6. CSV KPIs
print("Loading CSV stats ...")
cust = pd.read_csv(CUST, sep=";")
modestats = pd.read_csv(MODESTATS, sep=";")
# authoritative fleet distances from MATSim's own DRT stats (last iteration)
vstats = pd.read_csv(VSTATS, sep=";").iloc[-1]
veh_km_total = float(vstats["totalDistance"]) / 1000.0
veh_km_empty = float(vstats["totalEmptyDistance"]) / 1000.0
deadhead_pct = float(vstats["emptyRatio"]) * 100.0
veh_km_occ = veh_km_total - veh_km_empty
pax_km_matsim = float(vstats["totalPassengerDistanceTraveled"]) / 1000.0
# cross-check the legs-derived person-km against MATSim's own aggregate (guards event/CSV mixing)
_pkm_dev = abs(person_km - pax_km_matsim) / pax_km_matsim * 100.0 if pax_km_matsim else 0.0
print(f"  person-km cross-check: legs={person_km:,.0f} vs MATSim={pax_km_matsim:,.0f}  (dev {_pkm_dev:.2f}%)")
if _pkm_dev > 1.0:
    print("  WARNUNG: person-km weicht >1% von MATSim totalPassengerDistanceTraveled ab — Datenquellen pruefen!")
fc = cust.iloc[-1]
drt_rides = int(fc["rides"]); rej = float(fc["rejectionRate"]) * 100
wait_mean = float(fc["wait_average"]); wait_p95 = float(fc["wait_p95"])
wait_median = float(legs["waitTime"].median())
drt_share = float(modestats.iloc[-1]["drt"]) * 100
pt_share = float(modestats.iloc[-1]["pt"]) * 100
with gzip.open(TRIPS, "rt", encoding="utf-8") as f:
    trips = pd.read_csv(f, sep=";")
drt_trips = trips[trips["modes"].str.contains("drt", na=False)].copy()
feeder_mask = drt_trips["modes"].str.contains("pt", na=False)
n_feeder = int(feeder_mask.sum())
n_standalone = len(drt_trips) - n_feeder
pt_trips = trips[trips["main_mode"] == "pt"]
pct_rail_drtfed = (n_feeder / len(pt_trips) * 100) if len(pt_trips) else 0.0

# mean pax aboard over the active tour time (time-weighted, incl. empty driving) = util_by_time * CAP
CAP = fleet.get("capacity", 8)
_seg_time = fleet.get("seg_time", {})
_tot_seg_time = sum(_seg_time.values())
pax_aboard_mean = (sum(lv * s for lv, s in _seg_time.items()) / _tot_seg_time) if _tot_seg_time else 0.0

# PLACEHOLDER cost: fleet shift hours x (labour + vehicle) per hour + literature benchmark
fleet_shift_h = fleet.get("sum_shift_s", 0.0) / 3600.0
cost_total = fleet_shift_h * (COST_LABOUR_EUR_H + COST_VEHICLE_EUR_H)
cost_per_ride = cost_total / drt_rides if drt_rides else 0.0
cost_labour_share = COST_LABOUR_EUR_H / (COST_LABOUR_EUR_H + COST_VEHICLE_EUR_H) * 100.0
cost_lit_total = fleet_shift_h * COST_LIT_EUR_H
cost_lit_per_ride = cost_lit_total / drt_rides if drt_rides else 0.0
cost_lit_low_per_ride = (fleet_shift_h * COST_LIT_LOW_EUR_H / drt_rides) if drt_rides else 0.0

# ------------------------------------------------------------------ 7. service-area + intermodal stops + depots
sa_lon, sa_lat, service_poly = [], [], None
try:
    import geopandas as gpd
    sa = gpd.read_file(SHP)
    service_poly = sa.geometry.union_all() if hasattr(sa.geometry, "union_all") else sa.geometry.unary_union
    for g in sa.geometry.tolist():
        for p in (g.geoms if g.geom_type == "MultiPolygon" else [g]):
            xs, ys = p.exterior.xy
            lo, la = TF.transform(list(xs), list(ys))
            sa_lon += list(lo) + [None]; sa_lat += list(la) + [None]
except Exception as e:
    print(f"  service-area polygon FAILED: {e}")
im_lon, im_lat, im_name, im_feed = [], [], [], []
FEED_RADIUS_M = 600.0     # a DRT drop within this of a rail stop counts as feeding it
try:
    from shapely.geometry import Point
    with gzip.open(RAIL_SCHED, "rt", encoding="utf-8") as f:
        root = ET.parse(f).getroot()
    referenced = {st.get("refId") for tr in root.findall(".//transitRoute")
                  for rp in [tr.find("routeProfile")] if rp is not None
                  for st in rp.findall("stop") if st.get("refId")}
    # dedupe physical stations by rounded coord (schedule has short_x, short_x.1, ... at one spot)
    station = {}   # (round x, round y) -> [x, y, name]
    for s in root.findall(".//stopFacility"):
        if s.get("id") in referenced:
            x = float(s.get("x", 0)); y = float(s.get("y", 0))
            if service_poly is not None and service_poly.contains(Point(x, y)):
                station.setdefault((round(x), round(y)), [x, y, s.get("name", s.get("id", ""))])
    # feeder count per station = DRT dropoffs within FEED_RADIUS_M (legs in EPSG:25832)
    do_x = legs["toX"].values; do_y = legs["toY"].values
    sx, sy, sn, sf = [], [], [], []
    for (x, y, nm) in station.values():
        cnt = int((((do_x - x) ** 2 + (do_y - y) ** 2) ** 0.5 < FEED_RADIUS_M).sum())
        sx.append(x); sy.append(y); sn.append(nm); sf.append(cnt)
    if sx:
        im_lon, im_lat = [list(z) for z in TF.transform(sx, sy)]; im_name = sn; im_feed = sf
    served = sum(1 for c in im_feed if c > 0)
    print(f"  intermodal stations in service area = {len(im_lon)}  (DRT-fed: {served})")
    for nm, c in sorted(zip(im_name, im_feed), key=lambda t: -t[1]):
        print(f"    {c:5d} DRT-drops <{int(FEED_RADIUS_M)}m  {nm}")
except Exception as e:
    print(f"  intermodal stops FAILED: {e}")

# depots (shared LMD depots = DRT spawn/return depots)
dep_lon, dep_lat, dep_name = [], [], []
try:
    dep = pd.read_csv(DEPOT_CSV, sep=";")
    _dlon, _dlat = TF.transform(dep["x"].values, dep["y"].values)
    dep_lon = list(_dlon); dep_lat = list(_dlat); dep_name = [p.upper() for p in dep["provider"]]
    print(f"  depots on map = {len(dep_lon)}")
except Exception as e:
    print(f"  depots FAILED: {e}")

# ------------------------------------------------------------------ 7b. orphan diagnostic (points off the lines)
# User observation: single pickup/dropoff dots visually detached from tour lines. Check for the
# busiest vehicle how far each PU/DO dot is from its OWN path polyline vertices.
try:
    _dv = max(vehicles, key=lambda v: rides_per_veh.get(v, 0))
    _pl = [(lo, la) for lons, lats in veh_geo_lv[_dv].values()
           for lo, la in zip(lons, lats) if lo is not None]
    _lv = legs[legs["vehicleId"] == _dv]
    def _min_d(lon, lat):
        return min(math.hypot((lon - plo) * 68000, (lat - pla) * 111000) for plo, pla in _pl)
    _dists = [_min_d(lo, la) for lo, la in zip(_lv["pu_lon"], _lv["pu_lat"])] + \
             [_min_d(lo, la) for lo, la in zip(_lv["do_lon"], _lv["do_lat"])]
    _far = sum(1 for d in _dists if d > 250)
    print(f"  orphan check ({_dv}): max dot-to-path {max(_dists):.0f} m, "
          f"{_far}/{len(_dists)} dots >250m off the polyline")
except Exception as e:
    print(f"  orphan check FAILED: {e}")

# ================================================================== FIGURES
print("Building figures ...")
PLOT_BG = "rgba(0,0,0,0)"
def _style(fig, h=300):
    fig.update_layout(paper_bgcolor=PLOT_BG, plot_bgcolor=PLOT_BG, font=dict(color=TEXT, size=12),
                      margin=dict(l=50, r=20, t=30, b=40), height=h, legend=dict(font=dict(size=10)))
    fig.update_xaxes(gridcolor="#23262F", zeroline=False); fig.update_yaxes(gridcolor="#23262F", zeroline=False)
    return fig

HOURS = list(range(24))
def _by_hour(series_sec):
    h = (pd.Series(series_sec) // 3600).astype(int).clip(0, 23)
    cnt = h.value_counts().reindex(HOURS, fill_value=0).sort_index()
    return cnt

# --- (T1) demand / rides over the day  (WICHTIG)
dep_h = (legs["departureTime"] // 3600).astype(int).clip(0, 23)
rides_h = dep_h.value_counts().reindex(HOURS, fill_value=0).sort_index()
sub_h = (legs["submissionTime"] // 3600).astype(int).clip(0, 23)
subs_h = sub_h.value_counts().reindex(HOURS, fill_value=0).sort_index()
figT1 = go.Figure()
figT1.add_trace(go.Bar(x=HOURS, y=rides_h.values, name="Abfahrten (bedient)", marker_color=ACC))
figT1.add_trace(go.Scatter(x=HOURS, y=subs_h.values, name="Anfragen (submission)", mode="lines+markers",
                           line=dict(color="#FBBF24", width=2), marker=dict(size=5)))
figT1.update_xaxes(title_text="Stunde", dtick=2); figT1.update_yaxes(title_text="Anzahl"); _style(figT1)

# --- (T2) rejections over the day
rej_h = _by_hour(rej_times) if rej_times else pd.Series([0]*24, index=HOURS)
figT2 = go.Figure()
figT2.add_trace(go.Bar(x=HOURS, y=rej_h.values, name="Rejections", marker_color="#F87171"))
figT2.update_xaxes(title_text="Stunde", dtick=2); figT2.update_yaxes(title_text="abgelehnte Anfragen"); _style(figT2)

# --- (T3) mean wait over the day
wait_by_h = legs.groupby(dep_h)["waitTime"].mean().reindex(HOURS)
figT3 = go.Figure()
figT3.add_trace(go.Scatter(x=HOURS, y=wait_by_h.values, mode="lines+markers", name="Ø Wartezeit",
                           line=dict(color="#A78BFA", width=2), marker=dict(size=5)))
figT3.add_hline(y=wait_mean, line_dash="dot", line_color=DIM,
                annotation_text=f"Tagesmittel {wait_mean:.0f}s", annotation_font_color=DIM)
figT3.update_xaxes(title_text="Stunde", dtick=2); figT3.update_yaxes(title_text="Ø Wartezeit [s]"); _style(figT3)

# --- (T4) PT feeder over the day (absolute / share, toggle)
def _trip_hour(s):
    return s.astype(str).str.slice(0, 2).astype(int).clip(0, 23)
drt_trips = drt_trips.assign(dep_h=_trip_hour(drt_trips["dep_time"]))
all_trips_h = _trip_hour(trips["dep_time"]).value_counts().reindex(HOURS, fill_value=0).sort_index()
feeder_h = drt_trips[feeder_mask.values]["dep_h"].value_counts().reindex(HOURS, fill_value=0).sort_index()
feeder_share_h = (feeder_h / all_trips_h.astype(float).replace(0.0, float("nan")) * 100).fillna(0.0)
figT4 = go.Figure()
figT4.add_trace(go.Bar(x=HOURS, y=feeder_h.values, name="DRT+Bahn-Feeder", marker_color="#F472B6", visible=True))
figT4.add_trace(go.Bar(x=HOURS, y=feeder_share_h.values, name="Anteil an allen Wegen [%]",
                       marker_color="#F472B6", visible=False))
figT4.update_layout(updatemenus=[dict(type="buttons", direction="right", x=1.0, xanchor="right", y=1.18,
    bgcolor=CARD, font=dict(color=TEXT, size=11),
    buttons=[dict(label="absolut", method="update",
                  args=[{"visible": [True, False]}, {"yaxis.title.text": "Feeder-Wege"}]),
             dict(label="Anteil %", method="update",
                  args=[{"visible": [False, True]}, {"yaxis.title.text": "Anteil an allen Wegen [%]"}])])])
figT4.update_xaxes(title_text="Stunde", dtick=2); figT4.update_yaxes(title_text="Feeder-Wege"); _style(figT4)

# --- (H1) wait distribution (absolute)
figH1 = go.Figure()
figH1.add_trace(go.Histogram(x=legs["waitTime"], xbins=dict(start=0, size=60), marker_color="#A78BFA",
                             name="Wartezeit"))
for val, nm in [(wait_median, f"Median {wait_median:.0f}s"), (wait_mean, f"Ø {wait_mean:.0f}s"),
                (wait_p95, f"P95 {wait_p95:.0f}s")]:
    figH1.add_vline(x=val, line_dash="dot", line_color=DIM, annotation_text=nm,
                    annotation_font_color=DIM, annotation_font_size=10)
figH1.update_xaxes(title_text="Wartezeit [s] (60s-Bins)"); figH1.update_yaxes(title_text="Fahrten"); _style(figH1)

# --- (H2/H3) tour distance & duration distribution (per vehicle-tour = one vehicle day)
tour_km = [veh_km[v][0] + veh_km[v][1] for v in vehicles]
tour_h = [per_veh.get(v, {}).get("active_s", 0) / 3600.0 for v in vehicles]
figH2 = go.Figure()
figH2.add_trace(go.Histogram(x=tour_km, nbinsx=16, marker_color=ACC, name="Tour-km"))
figH2.update_xaxes(title_text="Tour-Distanz je Fahrzeug [km] (Event-Rekonstruktion)")
figH2.update_yaxes(title_text="Fahrzeuge"); _style(figH2)
figH3 = go.Figure()
figH3.add_trace(go.Histogram(x=tour_h, nbinsx=16, marker_color="#34D399", name="Tour-Dauer"))
figH3.update_xaxes(title_text="aktive Tour-Dauer je Fahrzeug [h]"); figH3.update_yaxes(title_text="Fahrzeuge")
_style(figH3)

# --- (P) final modal split (last iteration, pie)
_mode_cols = [c for c in modestats.columns if c not in ("iteration",)]
_last = modestats.iloc[-1]
_shares = [(m, float(_last[m]) * 100) for m in _mode_cols if float(_last[m]) > 0.0001]
_shares.sort(key=lambda t: -t[1])
MODE_COLORS = {"car": "#FBBF24", "ride": "#34D399", "walk": "#9CA3AF", "bike": "#60A5FA",
               "pt": "#F472B6", "drt": ACC, "freight": "#6B7280"}
figP = go.Figure(go.Pie(labels=[m for m, _ in _shares], values=[s for _, s in _shares],
                        marker=dict(colors=[MODE_COLORS.get(m, "#8B5CF6") for m, _ in _shares]),
                        hole=0.45, textinfo="label+percent", textfont=dict(size=11),
                        hovertemplate="%{label}: %{value:.2f}%<extra></extra>"))
figP.update_layout(showlegend=False, annotations=[dict(text="letzte<br>Iteration", showarrow=False,
                                                       font=dict(size=11, color=DIM))])
_style(figP, 320)

# --- (A/B/C) convergence
figC1 = make_subplots(specs=[[{"secondary_y": True}]])
figC1.add_trace(go.Scatter(x=cust["iteration"], y=cust["rides"], name="Fahrten", line=dict(color=ACC, width=2)), secondary_y=False)
figC1.add_trace(go.Scatter(x=cust["iteration"], y=cust["rejectionRate"]*100, name="Rejection %", line=dict(color="#F87171", width=2)), secondary_y=True)
figC1.update_yaxes(title_text="Fahrten", secondary_y=False); figC1.update_yaxes(title_text="Rejection %", secondary_y=True)
figC1.update_xaxes(title_text="Iteration"); _style(figC1)

figC2 = go.Figure()
figC2.add_trace(go.Scatter(x=cust["iteration"], y=cust["wait_average"], name="Ø Wartezeit", line=dict(color="#A78BFA", width=2)))
figC2.add_trace(go.Scatter(x=cust["iteration"], y=cust["wait_p95"], name="P95 Wartezeit", line=dict(color="#F472B6", width=2)))
figC2.update_xaxes(title_text="Iteration"); figC2.update_yaxes(title_text="Sekunden"); _style(figC2)

figC3 = go.Figure()
for m, c in [("drt", ACC), ("pt", "#F472B6"), ("car", "#FBBF24"), ("ride", "#34D399"), ("walk", "#9CA3AF"), ("bike", "#60A5FA")]:
    if m in modestats.columns:
        figC3.add_trace(go.Scatter(x=modestats["iteration"], y=modestats[m]*100, name=m, line=dict(width=1.6, color=c)))
figC3.update_xaxes(title_text="Iteration"); figC3.update_yaxes(title_text="Modal Share %"); _style(figC3)

# --- (D) service-time per vehicle (sorted bar, hover all 3)
sv = sorted(vehicles, key=lambda v: per_veh.get(v, {}).get("ratio_active", 0), reverse=True)
ra = [per_veh.get(v, {}).get("ratio_active", 0)*100 for v in sv]
rs = [per_veh.get(v, {}).get("ratio_shift", 0)*100 for v in sv]
rsim = [per_veh.get(v, {}).get("ratio_sim", 0)*100 for v in sv]
occ_min = [per_veh.get(v, {}).get("occupied_s", 0)/60 for v in sv]
figD = go.Figure()
figD.add_trace(go.Bar(x=list(range(len(sv))), y=ra, marker_color=ACC, name="aktiv",
              customdata=list(zip(sv, rs, rsim, occ_min)),
              hovertemplate="%{customdata[0]}<br>aktiv %{y:.0f}%<br>Schicht %{customdata[1]:.0f}%<br>Sim %{customdata[2]:.0f}%<br>belegt %{customdata[3]:.0f} min<extra></extra>"))
figD.update_xaxes(title_text="Fahrzeuge (nach aktiver Belegt-Quote sortiert)", showticklabels=False)
figD.update_yaxes(title_text="Belegt-Zeit / aktive Dienstzeit  [%]"); _style(figD, 320)
# NOTE: the former "Flotten-Zeitbudget" tile (figE) was DROPPED (2026-07-02): its stacked
# Stehen/Fahren/Halt content duplicates the Betriebszeit row of the occupancy chart plus the
# Total-Duration KPI cards, and the "davon belegt" overlay was easily misread as a 4th category.

# --- (O) occupancy decomposition: ONE 100%-stacked bar chart, 3 labelled rows (cleaner look)
OCC_COLORS = ["#334155", "#155E75", "#0E7490", "#0891B2", "#06B6D4", "#22D3EE",
              "#67E8F9", "#A5F3FC", "#FDE68A"][:CAP + 1]
seg_count = fleet.get("seg_count", {}); seg_time = fleet.get("seg_time", {})
ROWS = [("Gefahrene km", dist_by_occ, "km", lambda v: f"{v:,.0f}".replace(",", ".")),
        ("Betriebszeit", {lv: s / 3600.0 for lv, s in seg_time.items()}, "h", lambda v: f"{v:,.0f}".replace(",", ".")),
        ("Fahrten (Segmente)", seg_count, "Segm.", lambda v: f"{int(v):,}".replace(",", "."))]
row_labels = [r[0] for r in ROWS]
figO = go.Figure()
for lv in range(CAP + 1):
    nm = "leer" if lv == 0 else f"{lv} Pax"
    xs = [r[1].get(lv, 0.0) for r in ROWS]
    cd = [[nm, r[3](r[1].get(lv, 0.0)), r[2]] for r in ROWS]
    figO.add_trace(go.Bar(
        y=row_labels, x=xs, name=nm, orientation="h", marker=dict(color=OCC_COLORS[lv],
        line=dict(color=BG, width=1)), width=0.55, customdata=cd,
        hovertemplate="%{y} — %{customdata[0]}<br>%{customdata[1]} %{customdata[2]}<br>%{x:.1f}%<extra></extra>"))
figO.update_layout(barmode="stack", barnorm="percent", bargap=0.35,
                   legend=dict(orientation="h", y=-0.28, x=0.5, xanchor="center", font=dict(size=10),
                               traceorder="normal"))
figO.update_xaxes(title_text="Anteil [%]", range=[0, 100])
figO.update_yaxes(automargin=True)
_style(figO, 250)

# --- (F) per-vehicle map — CARTO dark, occupancy-colored tours, depots, numbered stops, PUDO dots, heatmap
figM = go.Figure()
base_traces = 0
if sa_lon:
    figM.add_trace(go.Scattermap(lon=sa_lon, lat=sa_lat, mode="lines", line=dict(color="#22D3EE", width=1.5),
                  name="Servicegebiet", hoverinfo="name")); base_traces += 1
if im_lon:
    srv = [i for i, c in enumerate(im_feed) if c > 0]
    uns = [i for i, c in enumerate(im_feed) if c == 0]
    if uns:
        figM.add_trace(go.Scattermap(
            lon=[im_lon[i] for i in uns], lat=[im_lat[i] for i in uns], mode="markers",
            marker=dict(size=8, color="#6B7280"),
            name="Bahnhalt (ohne DRT-Feeder)", text=[im_name[i] for i in uns],
            hovertemplate="%{text}<br>0 DRT-Feeder<extra>Bahnhalt</extra>")); base_traces += 1
    if srv:
        fcnt = [im_feed[i] for i in srv]; fmax = max(fcnt)
        sizes = [14 + 30 * (c / fmax) ** 0.5 for c in fcnt]
        figM.add_trace(go.Scattermap(
            lon=[im_lon[i] for i in srv], lat=[im_lat[i] for i in srv], mode="markers",
            marker=dict(size=sizes, color="#FFE66D"),
            name="Bahnhalt (DRT-gespeist)", text=[im_name[i] for i in srv],
            customdata=fcnt,
            hovertemplate="%{text}<br>%{customdata} DRT-Feeder<extra>Bahnhalt</extra>")); base_traces += 1
if dep_lon:
    figM.add_trace(go.Scattermap(
        lon=dep_lon, lat=dep_lat, mode="markers+text",
        marker=dict(size=15, color=COL_DEPOT), text=dep_name, textposition="top center",
        textfont=dict(size=10, color=COL_DEPOT),
        name="Depot (Spawn/Return)",
        hovertemplate="Depot %{text}<extra></extra>")); base_traces += 1

# per-vehicle traces: ONE line trace PER OCCUPANCY LEVEL (color = OCC_COLORS, same scale as the
# occupancy chart: leer, 1 Pax, 2 Pax, ...) + numbered PU/DO markers. Trace counts vary per
# vehicle (only levels it actually reached), so visibility uses an explicit index map.
occ_name = lambda o: "leer" if o == 0 else f"{o} Pax"
hh = lambda s: f"{int(s)//3600:02d}:{(int(s)%3600)//60:02d}"
levels_present = sorted({o for v in vehicles for o in veh_geo_lv[v]})

# legend proxies: one zero-data trace per occupancy level so the legend is identical in every
# tour view (real level traces keep showlegend=False — 80 vehicles would duplicate entries).
# legendgroup ties proxy + real traces together, so a legend click toggles the whole level.
proxy_idx = []
for o in levels_present:
    proxy_idx.append(len(figM.data))
    figM.add_trace(go.Scattermap(lon=[None], lat=[None], mode="lines",
                                 line=dict(color=OCC_COLORS[min(o, len(OCC_COLORS) - 1)], width=3),
                                 name=occ_name(o), legendgroup=f"occ{o}",
                                 visible=False, hoverinfo="skip"))

veh_trace_idx = {}   # vehicle -> [level-line indices..., PU idx, DO idx]
for v in vehicles:
    idxs = []
    lv = legs[legs["vehicleId"] == v].sort_values("departureTime").reset_index()
    stop_no = [str(n + 1) for n in range(len(lv))]
    pu_cd = [[n + 1, str(p), hh(t), f"{w:.0f}"] for n, (p, t, w) in
             enumerate(zip(lv["personId"], lv["departureTime"], lv["waitTime"]))]
    do_cd = [[n + 1, str(p), hh(t), f"{d:.1f}"] for n, (p, t, d) in
             enumerate(zip(lv["personId"], lv["arrivalTime"], lv["leg_km"]))]
    for o in sorted(veh_geo_lv[v]):
        lons, lats = veh_geo_lv[v][o]
        idxs.append(len(figM.data))
        figM.add_trace(go.Scattermap(lon=lons, lat=lats, mode="lines",
                                     line=dict(color=OCC_COLORS[min(o, len(OCC_COLORS) - 1)],
                                               width=1.2 if o == 0 else 3),
                                     opacity=0.55 if o == 0 else 0.9,
                                     name=occ_name(o), legendgroup=f"occ{o}", showlegend=False,
                                     hoverinfo="skip", visible=False))
    idxs.append(len(figM.data))
    figM.add_trace(go.Scattermap(lon=lv["pu_lon"], lat=lv["pu_lat"], mode="markers+text",
                                 marker=dict(size=13, color=COL_PU), text=stop_no,
                                 textfont=dict(size=9, color="#1A1D27"), name="Pickup #",
                                 customdata=pu_cd, visible=False,
                                 hovertemplate="Pickup #%{customdata[0]} — %{customdata[2]} Uhr<br>"
                                               "Fahrgast %{customdata[1]}<br>Wartezeit %{customdata[3]} s<extra></extra>"))
    idxs.append(len(figM.data))
    figM.add_trace(go.Scattermap(lon=lv["do_lon"], lat=lv["do_lat"], mode="markers+text",
                                 marker=dict(size=13, color=COL_DO), text=stop_no,
                                 textfont=dict(size=9, color="#1A1D27"), name="Dropoff #",
                                 customdata=do_cd, visible=False,
                                 hovertemplate="Dropoff #%{customdata[0]} — %{customdata[2]} Uhr<br>"
                                               "Fahrgast %{customdata[1]}<br>Fahrt %{customdata[3]} km<extra></extra>"))
    veh_trace_idx[v] = idxs

# fleet-wide PUDO dot layers for the all-vehicles tour view (small, unnumbered — user fix
# 2026-07-02: pickups/dropoffs were invisible in the Touren mode)
agg_pu_cd = [[str(p), hh(t), f"{w:.0f}", str(v)] for p, t, w, v in
             zip(legs["personId"], legs["departureTime"], legs["waitTime"], legs["vehicleId"])]
agg_do_cd = [[str(p), hh(t), f"{d:.1f}", str(v)] for p, t, d, v in
             zip(legs["personId"], legs["arrivalTime"], legs["leg_km"], legs["vehicleId"])]
agg_pu_idx = len(figM.data)
figM.add_trace(go.Scattermap(lon=legs["pu_lon"], lat=legs["pu_lat"], mode="markers",
                             marker=dict(size=5, color=COL_PU), name="Pickups", opacity=0.85,
                             customdata=agg_pu_cd, visible=False,
                             hovertemplate="Pickup — %{customdata[1]} Uhr<br>Fahrgast %{customdata[0]}<br>"
                                           "Fahrzeug %{customdata[3]}<br>Wartezeit %{customdata[2]} s<extra></extra>"))
agg_do_idx = len(figM.data)
figM.add_trace(go.Scattermap(lon=legs["do_lon"], lat=legs["do_lat"], mode="markers",
                             marker=dict(size=5, color=COL_DO), name="Dropoffs", opacity=0.85,
                             customdata=agg_do_cd, visible=False,
                             hovertemplate="Dropoff — %{customdata[1]} Uhr<br>Fahrgast %{customdata[0]}<br>"
                                           "Fahrzeug %{customdata[3]}<br>Fahrt %{customdata[2]} km<extra></extra>"))
# heatmap traces (appended LAST): pickup + dropoff density (legacy leaflet.heat analog)
heat_pu_idx = len(figM.data)
figM.add_trace(go.Densitymap(lon=legs["pu_lon"], lat=legs["pu_lat"], radius=14,
                             colorscale=[[0, "rgba(30,58,95,0)"], [0.3, "#3B82F6"], [0.6, "#38BDF8"], [1, "#22D3EE"]],
                             name="Pickups", showscale=False, visible=False))
heat_do_idx = len(figM.data)
figM.add_trace(go.Densitymap(lon=legs["do_lon"], lat=legs["do_lat"], radius=14,
                             colorscale=[[0, "rgba(45,27,78,0)"], [0.3, "#8B5CF6"], [0.6, "#C084FC"], [1, "#E879F9"]],
                             name="Dropoffs", showscale=False, visible=False))

def vis(sel, heat=None):
    """Visibility vector: sel=-1 all tours (occupancy colors + PUDO dots), sel=i single
    vehicle (occupancy colors + numbered PU/DO), heat='pu'/'do'/'both' heatmap-only."""
    out = [False] * len(figM.data)
    for j in range(base_traces):
        out[j] = True
    if heat:
        out[heat_pu_idx] = heat in ("pu", "both")
        out[heat_do_idx] = heat in ("do", "both")
        return out
    for j in proxy_idx:
        out[j] = True
    if sel == -1:
        for v in vehicles:
            for j in veh_trace_idx[v][:-2]:      # level lines only, no numbered stops
                out[j] = True
        out[agg_pu_idx] = out[agg_do_idx] = True
    else:
        for j in veh_trace_idx[vehicles[sel]]:   # level lines + numbered PU/DO
            out[j] = True
    return out

def mtitle(sel):
    if sel == -1:
        return (f"Alle {len(vehicles)} DRT-Fahrzeuge • Linienfarbe = Personen an Bord "
                f"• Flotten-Leerfahrtanteil {fleet_deadhead:.0f}%")
    v = vehicles[sel]; p = per_veh.get(v, {})
    return (f"{v} • {rides_per_veh.get(v,0)} Fahrgäste • belegt {veh_km[v][0]:.0f} km / leer {veh_km[v][1]:.0f} km "
            f"• Belegt-Zeit (aktiv) {p.get('ratio_active',0)*100:.0f}% • Linienfarbe = Personen an Bord")

btns = [dict(label=f"Alle Fahrzeuge ({len(vehicles)})", method="update",
             args=[{"visible": vis(-1)}, {"title.text": mtitle(-1)}])]
for i, v in enumerate(vehicles):
    p = per_veh.get(v, {})
    btns.append(dict(label=f"{v} — {rides_per_veh.get(v,0)} F, {p.get('ratio_active',0)*100:.0f}% belegt",
                     method="update", args=[{"visible": vis(i)}, {"title.text": mtitle(i)}]))
heat_btns = [
    dict(label="Touren", method="update", args=[{"visible": vis(-1)}, {"title.text": mtitle(-1)}]),
    dict(label="Heatmap Pickups", method="update",
         args=[{"visible": vis(0, heat="pu")}, {"title.text": f"Heatmap: {len(legs):,} Pickups".replace(",", ".")}]),
    dict(label="Heatmap Dropoffs", method="update",
         args=[{"visible": vis(0, heat="do")}, {"title.text": f"Heatmap: {len(legs):,} Dropoffs".replace(",", ".")}]),
    dict(label="Heatmap beide", method="update",
         args=[{"visible": vis(0, heat="both")}, {"title.text": "Heatmap: Pickups + Dropoffs"}]),
]
di = vehicles.index(max(vehicles, key=lambda v: rides_per_veh.get(v, 0)))
for i, vv in enumerate(vis(di)):
    figM.data[i].visible = vv
clon = (sum(im_lon)/len(im_lon)) if im_lon else 14.25
clat = (sum(im_lat)/len(im_lat)) if im_lat else 51.44
figM.update_layout(paper_bgcolor=BG, font=dict(color=TEXT, size=12), margin=dict(l=0, r=0, t=54, b=0),
                   legend=dict(bgcolor="rgba(0,0,0,0.5)", font=dict(size=10)),
                   title=dict(text=mtitle(di), font=dict(size=14), x=0.01),
                   map=dict(style="carto-darkmatter", center=dict(lon=clon, lat=clat), zoom=10), height=720,
                   updatemenus=[
                       dict(buttons=btns, direction="down", showactive=True, x=0.01, xanchor="left",
                            y=0.99, yanchor="top", bgcolor="#1A1D27", font=dict(color=TEXT, size=11), active=di+1),
                       dict(type="buttons", buttons=heat_btns, direction="right", x=0.99, xanchor="right",
                            y=0.99, yanchor="top", bgcolor="#1A1D27", font=dict(color=TEXT, size=11))])

# ================================================================== HTML
print("Assembling HTML ...")
def card(label, value, sub="", big=False, tip="", badge=""):
    vs = "font-size:2.0rem" if big else "font-size:1.5rem"
    t = f' title="{tip}"' if tip else ""
    cur = ' style="cursor:help"' if tip else ""
    b = f'<span class="badge">{badge}</span>' if badge else ""
    return (f'<div class="kpi"{t}{cur}><div class="kl">{label}{b}</div>'
            f'<div class="kv" style="{vs}">{value}</div><div class="ks">{sub}</div></div>')

avg_trip_km = (person_km / drt_rides) if drt_rides else 0.0
_de = lambda n, dec=0: f"{n:,.{dec}f}".replace(",", "X").replace(".", ",").replace("X", ".")  # German number
_fmt_h = lambda s: _de(s / 3600.0) + " h"   # seconds -> rounded hours (German thousands sep)
_util_tip = ("Mittel von (Passagiere/Kapazitaet) ueber ALLE Konstant-Besetzungs-Segmente "
             "inkl. Leerfahrt-Level 0 (Annahme a). Fahrten: jedes Segment gleich gewichtet; "
             "Zeit: nach Segmentdauer gewichtet.")
_tour_tip = ("Aktive Tour: erste Abfahrt bis letzte Aufgabe je Fzg; naechtliches Depot-Parken "
             "ausgeklammert (Annahme b). Tour = Fahren + Dwell + Warten. Auf Stunden gerundet.")
_trip_tip = ("Personenkm / Fahrgaeste. Personenkm = Summe travelDistance_m = tatsaechlich gefahrene "
             "In-Vehicle-Distanz inkl. Pooling-Umweg (Cross-Check gegen MATSim "
             "totalPassengerDistanceTraveled: Abweichung {:.2f}%).".format(_pkm_dev))
_vkm_tip = ("Fahrzeugkm aus MATSim drt_vehicle_stats (totalDistance, autoritativ). Die Karten-Touren "
            "werden separat aus dem Eventstream rekonstruiert und liegen dort ~3% niedriger "
            "(Luftlinie zwischen Link-Knoten statt echter Linklaenge).")
_detour_tip = ("Tatsaechlich gefahrene In-Vehicle-Distanz / direkte Netz-Distanz "
               "(Summe travelDistance_m / Summe directTravelDistance_m aus drt_legs). 1.0 = umwegfrei.")
_cost_tip = ("PLATZHALTER-Kostenfunktion (Beschluss 2026-07-02, noch zu praezisieren!): "
             f"Flotten-Schichtstunden ({_de(fleet_shift_h)} h) x ({COST_LABOUR_EUR_H:.0f} EUR/h Personal "
             f"+ {COST_VEHICLE_EUR_H:.0f} EUR/h Fahrzeug) nach Rudolph ~80/20. Nur direkte Kosten, "
             "KEIN Overhead. Idle-Zeit zaehlt mit (Fahrzeug-Stunde ist die Preisbasis).")
_cost_lit_tip = ("Literatur-Benchmark: Currie & Fournier (2020, Transport Policy), DRT-Vollkosten je "
                 f"Fahrzeug-Stunde. 3. Aera (2009-2019) Median ~110 AU$2019 = {COST_LIT_EUR_H:.0f} EUR/h "
                 f"(2. Aera ~60 AU$ = {COST_LIT_LOW_EUR_H:.0f} EUR/h -> {cost_lit_low_per_ride:.2f} EUR/Fahrt). "
                 "Vollkosten inkl. Overhead/Verwaltung — Obergrenze zum Bottom-up-Platzhalter. "
                 "Studie: hohe Kosten je Fzg-h korrelieren signifikant mit DRT-Einstellung.")
_pax_tip = ("Zeitgewichtetes Mittel der Fahrgaeste an Bord ueber die AKTIVE Tourzeit inkl. "
            f"Leerfahrten (= Utilization (Zeit) x Kapazitaet {CAP}). Mass fuer den Pooling-Grad.")
_feeder_tip = ("DRT-Fahrt in einem Weg mit Bahn-Anschluss (modes enthaelt pt; Fahrplan ist rail-only, "
               "Busse existieren im Szenario nicht). Kein Kettennachweis am Bahnsteig — Naehe-Proxy.")
_solo_tip = "DRT-Fahrten ohne pt-Leg im selben Weg: reine Punkt-zu-Punkt-Bedienung."
_dwell_tip = "Summe der STOP-Task-Zeiten (Ein-/Ausstieg an Pickup/Dropoff) ueber die Flotte."
_svc_tip = ("Zeit mit >=1 Fahrgast an Bord / Bezugszeitraum. aktiv = erste Abfahrt bis letzte "
            "Aufgabe; Schicht = Dienstfenster aus der Flottendatei (hier 0-24h).")
_wait_tip = "Fahrgast-Wartezeit von Anfrage-Submission bis Einstieg (drt_legs waitTime)."

kpi_cards = "".join([
    card("Anzahl Fahrzeuge", f"{fleet['n_vehicles']}", f"Kapazitaet {fleet['capacity']} Sitze",
         tip="DVRP-Flotte aus der Flottendatei; alle Fahrzeuge mit Events im letzten Iteration."),
    card("Gesamtpassagiere", _de(drt_rides), "befoerderte Fahrgaeste",
         tip="rides aus drt_customer_stats (letzte Iteration) = bediente DRT-Legs."),
    card("Kosten Bottom-up", f"{_de(cost_total)} €", f"{cost_per_ride:.2f} € je Fahrt  •  {cost_labour_share:.0f}% Personalanteil",
         tip=_cost_tip, badge="PLATZHALTER"),
    card("Kosten Literatur-Benchmark", f"{_de(cost_lit_total)} €", f"{cost_lit_per_ride:.2f} € je Fahrt  •  {COST_LIT_EUR_H:.0f} €/Fzg-h (Currie/Fournier)",
         tip=_cost_lit_tip, badge="BENCHMARK"),
    card("Ø Fahrgaeste an Bord", f"{pax_aboard_mean:.2f}", f"zeitgewichtet, aktive Tour (max {CAP})", tip=_pax_tip),
    card("Avg. Utilization (Fahrten)", f"{fleet['util_by_trips']*100:.1f}%", "Ø Besetzung/Kapazitaet je Segment", tip=_util_tip),
    card("Avg. Utilization (Zeit)", f"{fleet['util_by_time']*100:.1f}%", "zeitgewichtet (Pooling-Effekt)", tip=_util_tip),
    card("Avg. Trip Length", f"{avg_trip_km:.1f} km", "Ø Fahrgast-Wegelaenge", tip=_trip_tip),
    card("Avg. DRT-Detour-Factor", f"{detour_factor:.2f}", "gefahren / direkt", tip=_detour_tip),
    card("Total Tour Duration", _fmt_h(fleet['tour_s']), "Σ aktive Touren (Flotte)", tip=_tour_tip),
    card("Total Driving Time", _fmt_h(fleet['drive_s']), "Σ DRIVE (Flotte)",
         tip="Summe der DRIVE-Task-Zeiten (Fahrzeug bewegt sich, mit oder ohne Fahrgast)."),
    card("Total Waiting Time", _fmt_h(fleet['waiting_s']), "Leerlauf zwischen Auftraegen", tip=_tour_tip),
    card("Total Dwell Time", _fmt_h(fleet['stop_s']), "Σ STOP an Pickup/Dropoff", tip=_dwell_tip),
    card("Service-Zeit (aktiv)", f"{fleet['ratio_active']*100:.1f}%", "belegt / aktive Dienstzeit", big=True, tip=_svc_tip),
    card("Service-Zeit (Schicht)", f"{fleet['ratio_shift']*100:.1f}%", "belegt / Schichtfenster 0-24h", big=True, tip=_svc_tip),
    card("DRT Modal Share", f"{drt_share:.2f}%", f"pt {pt_share:.2f}%",
         tip="Anteil DRT an allen Wegen (modestats, letzte Iteration)."),
    card("Rejection", f"{rej:.1f}%", "abgelehnte Anfragen",
         tip="rejectionRate aus drt_customer_stats: Anfragen ohne machbare Einfuegung (no_insertion_found)."),
    card("Wartezeit Ø / Median / P95", f"{wait_mean:.0f} / {wait_median:.0f} / {wait_p95:.0f}s", "Pax-Wartezeit", tip=_wait_tip),
    card("DRT solo", _de(n_standalone), "Punkt-zu-Punkt ohne Bahnanschluss", tip=_solo_tip),
    card("DRT+Bahn (Feeder)", _de(n_feeder), f"{pct_rail_drtfed:.0f}% der Bahn-Wege DRT-gespeist", tip=_feeder_tip),
    card("Fahrzeugkm (gesamt)", f"{_de(veh_km_total)} km", f"belegt {_de(veh_km_occ)} / leer {_de(veh_km_empty)} km  •  Leerfahrt {deadhead_pct:.1f}%", tip=_vkm_tip),
    card("Personenkm", f"{_de(person_km)} km", "Σ gefahrene In-Vehicle-Distanz (mit Umweg)", tip=_trip_tip),
])

# compact "feeders per rail station" breakdown (served first, then count of unserved)
if im_feed:
    _fed = sorted([(n, c) for n, c in zip(im_name, im_feed) if c > 0], key=lambda t: -t[1])
    _unfed = sum(1 for c in im_feed if c == 0)
    feeder_breakdown = " · ".join(f"{n} {c}" for n, c in _fed) or "keine"
    if _unfed:
        feeder_breakdown += f" · {_unfed} Halte ohne Feeder"
else:
    feeder_breakdown = "n/a (keine intermodalen Halte gefunden)"

def div(fig, dom_id):
    return pio.to_html(fig, full_html=False, include_plotlyjs=False, div_id=dom_id,
                       config={"displayModeBar": False})

import plotly
with open(os.path.join(os.path.dirname(plotly.__file__), "package_data", "plotly.min.js"), encoding="utf-8") as f:
    pjs = f.read()

html = f"""<!DOCTYPE html><html lang="de"><head><meta charset="UTF-8"/>
<meta name="viewport" content="width=device-width, initial-scale=1.0"/>
<title>DRT Dashboard — fleet80 depot-dispatching — Lausitz/Hoyerswerda</title>
<script>{pjs}</script>
<style>
*,*::before,*::after{{box-sizing:border-box}}
body{{margin:0;padding:18px;background:{BG};color:{TEXT};font-family:system-ui,-apple-system,sans-serif;font-size:14px}}
h1{{font-size:21px;margin:0 0 2px;color:#fff}} .sub{{color:{DIM};font-size:12px;margin-bottom:16px}}
h2{{font-size:13px;letter-spacing:1.5px;text-transform:uppercase;color:{ACC};margin:22px 0 10px;font-weight:700}}
.grid{{display:grid;gap:12px;grid-template-columns:repeat(auto-fit,minmax(190px,1fr))}}
.kpi{{background:{CARD};border:1px solid {BORDER};border-radius:10px;padding:13px 15px}}
.kl{{font-size:.72rem;color:{DIM};text-transform:uppercase;letter-spacing:.5px}}
.kv{{font-weight:800;color:#fff;margin:3px 0;line-height:1.1}} .ks{{font-size:.72rem;color:{DIM}}}
.badge{{background:#7C2D12;color:#FDBA74;border-radius:4px;padding:1px 5px;font-size:.62rem;margin-left:6px;letter-spacing:.5px}}
.hero .kpi{{border-color:#1e5e7a;background:linear-gradient(135deg,#15303d,#1A1D27)}}
.hero .kv{{color:{COL_OCC}}}
.row{{display:grid;gap:12px;grid-template-columns:1fr 1fr 1fr}}
.row2{{display:grid;gap:12px;grid-template-columns:1fr 1fr}}
@media(max-width:1100px){{.row,.row2{{grid-template-columns:1fr}}}}
.card{{background:{CARD};border:1px solid {BORDER};border-radius:10px;padding:10px 12px}}
.card h3{{font-size:.82rem;color:{TEXT};margin:2px 0 6px}}
.note{{font-size:11px;color:{DIM};margin-top:8px;border-top:1px solid {BORDER};padding-top:8px;line-height:1.5}}
</style></head><body>
<h1>DRT-Dashboard &mdash; Lausitz / Hoyerswerda</h1>
<div class="sub">fleet80 &bull; Depot-Dispatching (Spawn + Rebalancing + Return-to-Depot) &bull; 151 Iterationen &bull; reale 100%-Nachfrage</div>

<h2>Headline-KPIs</h2>
<div class="grid hero">{kpi_cards}</div>

<h2>Pro-Fahrzeug-Touren &mdash; jedes DRT-Fahrzeug einzeln wählbar</h2>
<div class="card" style="padding:0;overflow:hidden">{div(figM,'mapdiv')}</div>
<div class="note"><b>Touren</b> aus dem MATSim-Eventstream rekonstruiert: farbige Linie = belegt (Pastellfarbe je Fahrzeug),
grau = Leerfahrt, nummerierte gelbe/blaue Punkte = Pickup/Dropoff in Tour-Reihenfolge (Hover: Fahrgast, Zeit, Wartezeit/Distanz).
Pink = die 7 Depots (Spawn/Return). Dropdown links wählt ein Fahrzeug; Buttons rechts schalten auf Heatmap
(Pickup-/Dropoff-Dichte, analog HAGRID-Legacy). Gelbe Kreise = Bahnhalte, Größe ∝ DRT-Feeder (Dropoffs &lt;{int(FEED_RADIUS_M)} m).
<b>Hinweis Geometrie:</b> Linien sind Luftlinien-Segmente zwischen Link-Knoten (nicht die echte Straßengeometrie) —
Punkte können daher wenige hundert Meter neben der Linie liegen; in der Ansicht „Alle Fahrzeuge" sind Leerfahrten
ausgeblendet, einzelne Punkte „ohne Linie" gehören zu verdeckten Leerfahrt-Anfahrten.
CARTO-Kacheln brauchen Internet.<br><b>DRT-Feeder je Bahnhof:</b> {feeder_breakdown}</div>

<h2>Tagesverlauf</h2>
<div class="row2">
  <div class="card"><h3>Nachfrage: Anfragen &amp; bediente Abfahrten je Stunde</h3>{div(figT1,'t1')}</div>
  <div class="card"><h3>Rejections je Stunde</h3>{div(figT2,'t2')}</div>
  <div class="card"><h3>Ø Wartezeit je Stunde</h3>{div(figT3,'t3')}</div>
  <div class="card"><h3>DRT+Bahn-Feeder je Stunde (absolut / Anteil umschaltbar)</h3>{div(figT4,'t4')}</div>
</div>

<h2>Verteilungen</h2>
<div class="row">
  <div class="card"><h3>Wartezeit-Verteilung</h3>{div(figH1,'h1')}</div>
  <div class="card"><h3>Tour-Distanz je Fahrzeug</h3>{div(figH2,'h2')}</div>
  <div class="card"><h3>Tour-Dauer je Fahrzeug</h3>{div(figH3,'h3')}</div>
</div>

<h2>Besetzung &amp; Modal Split</h2>
<div class="row2">
  <div class="card"><h3>Fahrten (Segmente) · Betriebszeit · gefahrene km — je 100%-gestapelt nach Besetzung</h3>{div(figO,'o1')}
  <div class="note">0 = Leerfahrt/Deadhead … {CAP} = voll; je Zeile auf 100% normiert. Eine <b>Fahrt</b> =
  Konstant-Besetzungs-Segment im aktiven Tour-Fenster (Annahme b). Die km-Zeile nutzt die
  Event-Rekonstruktion (~3% niedriger als MATSim, s. Fahrzeugkm-Tooltip).</div></div>
  <div class="card"><h3>Finaler Modal Split (letzte Iteration)</h3>{div(figP,'p1')}</div>
</div>

<h2>Konvergenz über 151 Iterationen</h2>
<div class="row">
  <div class="card"><h3>Fahrten &amp; Rejection</h3>{div(figC1,'c1')}</div>
  <div class="card"><h3>Wartezeit (Ø / P95)</h3>{div(figC2,'c2')}</div>
  <div class="card"><h3>Modal Shares</h3>{div(figC3,'c3')}</div>
</div>

<h2>Service-Zeit im Detail</h2>
<div class="card"><h3>Belegt-Zeit je Fahrzeug (aktive Dienstzeit)</h3>{div(figD,'d1')}</div>
<div class="note"><b>Service-Zeit</b> = Zeit mit ≥1 Fahrgast an Bord, geteilt durch (aktiv) erste Abfahrt→letzte Aufgabe
bzw. (Schicht) Dienstfenster 0–24h. Die frühere „Flotten-Zeitbudget"-Kachel wurde entfernt: ihr Inhalt steckt
vollständig in der Betriebszeit-Zeile der Besetzungs-Grafik + den Total-Dauer-KPIs.</div>
</body></html>"""

with open(HTML_OUT, "w", encoding="utf-8") as f:
    f.write(html)
print(f"Dashboard written: {HTML_OUT}  ({os.path.getsize(HTML_OUT)//1024//1024} MB)")
