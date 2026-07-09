# -*- coding: utf-8 -*-
"""
Figure 2 — Spatial complementarity of parcel demand and passenger DRT requests
==============================================================================
Two-panel map (2:1, horizontal) for the Hoyerswerda study area, built from the
married250 headline run (pax DRT + LMD freight in one MATSim Controler):

  (a) full DRT service area — passenger request-origin density (KDE surface)
      with parcel delivery stops as dots (size = parcels per stop)
  (b) zoom on the Hoyerswerda core

Colors follow Fig. 1: passenger side #577CAA (blue), parcel side #55AA7F (green).
CVD-validated pair (dE normal 57 / protan 49 / deutan 46 / tritan 13).

Run:  python -u fig2_spatial_complementarity.py
Out:  fig2_spatial_complementarity.png (600 dpi) + .pdf (vector, KDE rasterized)
"""
import os
import re
import xml.etree.ElementTree as ET

import geopandas as gpd
import matplotlib.patheffects as pe
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd
import shapely
from matplotlib.collections import LineCollection
from matplotlib.colors import ListedColormap, to_rgb
from matplotlib.gridspec import GridSpec
from matplotlib.legend_handler import HandlerTuple
from matplotlib.lines import Line2D
from pyproj import Transformer
from scipy.ndimage import gaussian_filter

# ------------------------------------------------------------------ paths
REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
RUN = "DRT_BASELINE_13052025_married250_iter300_jsprit1000"
PREFIX = "DRT_BASELINE_13052025_married250"
MM = os.path.join(REPO, "parcel-demand-2-matsim-pipeline", "hagrid-matsim-output", RUN)
BASE = os.path.join(MM, PREFIX)
CARRIERS = os.path.join(REPO, "parcel-demand-2-matsim-pipeline", "hagrid-output", PREFIX,
                        "carriers", PREFIX + "_lmd_carriers_routed.xml")
SHP = os.path.join(REPO, "parcel-demand-2-matsim-pipeline", "hagrid-input", "lausitz",
                   "drt", "drt-service-area.shp")
OUT_DIR = os.path.dirname(os.path.abspath(__file__))

# ------------------------------------------------------------------ style
BLUE = "#577CAA"        # passenger side (Fig. 1 palette)
BLUE_DARK = "#33517A"
GREEN = "#55AA7F"       # parcel side (Fig. 1 palette)
GREEN_DARK = "#2F7051"
INK = "#333333"
MUTED = "#8A8A8A"
ROAD = "#C9C9C9"
ROAD_MINOR = "#DEDEDE"
BOUNDARY = "#9AA0A6"
DOT_SCALE = 2.6         # one scale for BOTH panels so the size legend is exact

plt.rcParams.update({
    "font.family": "sans-serif",
    "font.sans-serif": ["Arial", "Helvetica", "DejaVu Sans"],
    "font.size": 7.5,
    "text.color": INK,
    "axes.edgecolor": BOUNDARY,
    "pdf.fonttype": 42,  # editable text in the PDF
})

# ------------------------------------------------------------------ data: service area
area = gpd.read_file(SHP)
service = area.union_all()
core = area.loc[area["name"] == "hoyerswerda", "geometry"].iloc[0]
ax0, ay0, ax1, ay1 = service.bounds

# ------------------------------------------------------------------ data: passenger requests
legs = pd.read_csv(BASE + ".output_drt_legs_drt.csv", sep=";")
req_x = legs["fromX"].to_numpy()
req_y = legs["fromY"].to_numpy()
n_requests = len(legs)
print(f"passenger requests (served ride origins): {n_requests}")

# ------------------------------------------------------------------ data: parcel stops
# carrier services: to=<linkId>, capacityDemand=<parcels>; aggregate across carriers per link
svc = {}
n_parcels = 0
for _, el in ET.iterparse(CARRIERS):
    if el.tag.endswith("service"):
        link = el.get("to")
        dem = int(el.get("capacityDemand", 1))
        svc[link] = svc.get(link, 0) + dem
        n_parcels += dem
        el.clear()
print(f"parcel stops: {len(svc)} links, {n_parcels} parcels")

# link -> midpoint coordinate + road classes, from the run's own links CSV
links = pd.read_csv(BASE + ".output_links.csv.gz", sep=";", low_memory=False,
                    usecols=["link", "type", "geometry"], dtype={"link": str})
coord_re = re.compile(r"LINESTRING\(\s*([\d.\-eE]+)\s+([\d.\-eE]+),\s*([\d.\-eE]+)\s+([\d.\-eE]+)")

def endpoints(geom):
    m = coord_re.search(geom)
    return tuple(float(v) for v in m.groups()) if m else None

link_geom = dict(zip(links["link"], links["geometry"]))
stop_x, stop_y, stop_n, missing = [], [], [], 0
for link, dem in svc.items():
    g = link_geom.get(link)
    pts = endpoints(g) if isinstance(g, str) else None
    if pts is None:
        missing += 1
        continue
    stop_x.append((pts[0] + pts[2]) / 2)
    stop_y.append((pts[1] + pts[3]) / 2)
    stop_n.append(dem)
stop_x, stop_y, stop_n = map(np.asarray, (stop_x, stop_y, stop_n))
print(f"parcel stop coords resolved: {len(stop_x)} (missing links: {missing})")

# ------------------------------------------------------------------ data: roads (bbox-clipped)
pad = 800.0
in_bbox = links["geometry"].str.contains("LINESTRING", na=False)
roads = links[in_bbox & links["type"].str.startswith("highway", na=False)].copy()
eps = roads["geometry"].map(endpoints)
roads = roads[eps.notna()].copy()
E = np.array(eps.dropna().tolist())
keep = ((np.minimum(E[:, 0], E[:, 2]) < ax1 + pad) & (np.maximum(E[:, 0], E[:, 2]) > ax0 - pad)
        & (np.minimum(E[:, 1], E[:, 3]) < ay1 + pad) & (np.maximum(E[:, 1], E[:, 3]) > ay0 - pad))
E = E[keep]
rtypes = roads["type"].to_numpy()[keep]
major_mask = np.isin(rtypes, ["highway.motorway", "highway.motorway_link", "highway.trunk",
                              "highway.trunk_link", "highway.primary", "highway.primary_link",
                              "highway.secondary", "highway.secondary_link"])
segs_major = E[major_mask].reshape(-1, 2, 2)
segs_minor = E[np.isin(rtypes, ["highway.tertiary", "highway.tertiary_link"])].reshape(-1, 2, 2)
print(f"road segments: major {len(segs_major)}, minor {len(segs_minor)}")

# ------------------------------------------------------------------ request-density surface
# 25 m histogram + gaussian smoothing (sigma 350 m), masked to the service area.
# The colormap carries its own alpha ramp -> the field fades out smoothly instead
# of ending in a hard mask edge.
CELL = 25.0
SIGMA_M = 350.0
gx = np.arange(ax0 - pad, ax1 + pad, CELL)
gy = np.arange(ay0 - pad, ay1 + pad, CELL)
H, _, _ = np.histogram2d(req_x, req_y, bins=[gx, gy])
D = gaussian_filter(H, sigma=SIGMA_M / CELL).T  # rows = y
cx = (gx[:-1] + gx[1:]) / 2
cy = (gy[:-1] + gy[1:]) / 2
CX, CY = np.meshgrid(cx, cy)
inside = shapely.contains_xy(service, CX.ravel(), CY.ravel()).reshape(D.shape)
D = np.where(inside, D, np.nan)
vmax = np.nanpercentile(D, 99.7)
Dm = np.ma.masked_invalid(D)

N = 256
t = np.linspace(0, 1, N)
ramp_anchor = np.array([0.0, 0.45, 1.0])
ramp_cols = np.array([to_rgb("#DCE6F2"), to_rgb(BLUE), to_rgb(BLUE_DARK)])
rgb = np.stack([np.interp(t, ramp_anchor, ramp_cols[:, i]) for i in range(3)], axis=1)
alpha = np.clip(t / 0.20, 0, 1) * 0.88   # fade in over the lowest 20 %, then constant
cmap = ListedColormap(np.column_stack([rgb, alpha]))
cmap.set_bad(alpha=0.0)

# ------------------------------------------------------------------ figure (2:1)
FIG_W, FIG_H = 7.87, 3.94  # inches = 200 x 100 mm

# zoom window: core polygon + margin
cbx0, cby0, cbx1, cby1 = core.bounds
zm = 500.0
zx0, zx1 = cbx0 - zm, cbx1 + zm
zy0, zy1 = cby0 - zm, cby1 + zm

ov_aspect = (ax1 - ax0 + 2 * pad) / (ay1 - ay0 + 2 * pad)
zoom_aspect = (zx1 - zx0) / (zy1 - zy0)

fig = plt.figure(figsize=(FIG_W, FIG_H))
gs = GridSpec(1, 2, width_ratios=[ov_aspect, zoom_aspect], wspace=0.04,
              left=0.005, right=0.995, top=0.93, bottom=0.10)
axA = fig.add_subplot(gs[0])
axB = fig.add_subplot(gs[1])


def draw_panel(ax, x0, x1, y0, y1, road_lw, road_minor_lw):
    ax.set_xlim(x0, x1)
    ax.set_ylim(y0, y1)
    ax.set_aspect("equal")
    ax.set_xticks([])
    ax.set_yticks([])
    for s in ax.spines.values():
        s.set_linewidth(0.6)
    # roads under the density field
    ax.add_collection(LineCollection(segs_minor, colors=ROAD_MINOR, linewidths=road_minor_lw, zorder=1))
    ax.add_collection(LineCollection(segs_major, colors=ROAD, linewidths=road_lw, zorder=1.2))
    # request density (alpha ramp lives in the colormap)
    ax.pcolormesh(gx, gy, Dm, cmap=cmap, vmin=0, vmax=vmax,
                  rasterized=True, zorder=2, shading="flat")
    # service-area boundary
    for geom in getattr(service, "geoms", [service]):
        bx, by = geom.exterior.xy
        ax.plot(bx, by, color=BOUNDARY, lw=0.9, ls=(0, (4, 2)), zorder=3)
    # parcel stops
    ax.scatter(stop_x, stop_y, s=np.sqrt(stop_n) * DOT_SCALE, facecolor=GREEN,
               edgecolor=GREEN_DARK, linewidths=0.35, alpha=0.9, zorder=4)


draw_panel(axA, ax0 - pad, ax1 + pad, ay0 - pad, ay1 + pad, road_lw=0.5, road_minor_lw=0.3)
draw_panel(axB, zx0, zx1, zy0, zy1, road_lw=0.7, road_minor_lw=0.45)

# zoom indicator on the overview
axA.add_patch(plt.Rectangle((zx0, zy0), zx1 - zx0, zy1 - zy0, fill=False,
                            edgecolor=INK, lw=0.7, zorder=5))

# ------------------------------------------------------------------ place labels
TF = Transformer.from_crs("EPSG:4326", "EPSG:25832", always_xy=True)
halo = [pe.withStroke(linewidth=1.6, foreground="white", alpha=0.9)]
places = {  # lon, lat
    "Hoyerswerda": (14.2438, 51.4372),
    "Lauta": (14.1023, 51.4441),
    "Wittichenau": (14.2460, 51.3835),
    "Bernsdorf": (14.0680, 51.3720),
}
for name, (lon, lat) in places.items():
    px, py = TF.transform(lon, lat)
    if ax0 < px < ax1 and ay0 < py < ay1:
        axA.annotate(name, (px, py), fontsize=6.5, color=INK, style="italic",
                     ha="center", va="center", zorder=6, path_effects=halo)
    else:
        print(f"place OUTSIDE area, skipped: {name} ({px:.0f},{py:.0f})")

# ------------------------------------------------------------------ scale bars + north
def scalebar(ax, km, x_frac=0.06, y_frac=0.05):
    (x0, x1), (y0, y1) = ax.get_xlim(), ax.get_ylim()
    bx = x0 + (x1 - x0) * x_frac
    by = y0 + (y1 - y0) * y_frac
    ax.plot([bx, bx + km * 1000], [by, by], color=INK, lw=1.1, solid_capstyle="butt", zorder=7)
    ax.annotate(f"{km} km", (bx + km * 500, by + (y1 - y0) * 0.012), ha="center",
                va="bottom", fontsize=6, color=INK, zorder=7, path_effects=halo)

scalebar(axA, 5)
scalebar(axB, 1)

# north arrow (overview, top-left)
(x0, x1), (y0, y1) = axA.get_xlim(), axA.get_ylim()
nx = x0 + (x1 - x0) * 0.045
ny = y1 - (y1 - y0) * 0.115
axA.annotate("N", (nx, ny + (y1 - y0) * 0.065), ha="center", va="center",
             fontsize=7, color=INK, zorder=7)
axA.annotate("", xy=(nx, ny + (y1 - y0) * 0.045), xytext=(nx, ny),
             arrowprops=dict(arrowstyle="-|>", color=INK, lw=0.9), zorder=7)

# panel titles + headline counts on the title row
axA.set_title("a   DRT service area (207 km²)", loc="left", fontsize=8, pad=3, color=INK)
axB.set_title("b   Hoyerswerda core", loc="left", fontsize=8, pad=3, color=INK)
axB.set_title(f"{n_requests:,} passenger requests · {n_parcels:,} parcels · one weekday",
              loc="right", fontsize=6.5, pad=3, color=MUTED)

# ------------------------------------------------------------------ legend row below the maps
def sq(frac):
    return Line2D([], [], marker="s", ls="", markerfacecolor=cmap(frac),
                  markeredgecolor="none", markersize=6)

def dot(n):
    return Line2D([], [], marker="o", ls="", markerfacecolor=GREEN,
                  markeredgecolor=GREEN_DARK, markeredgewidth=0.4,
                  markersize=max(np.sqrt(np.sqrt(n) * DOT_SCALE), 1.5), alpha=0.9)

handles = [(sq(0.3), sq(0.65), sq(1.0)),
           (dot(1), dot(10), dot(25)),
           Line2D([], [], color=BOUNDARY, lw=0.9, ls=(0, (4, 2)))]
labels = ["Passenger DRT requests (origin density, low → high)",
          "Parcel delivery stops (1 / 10 / 25 parcels per stop)",
          "DRT service area"]
fig.legend(handles=handles, labels=labels, loc="lower center", ncols=3,
           bbox_to_anchor=(0.5, -0.008), frameon=False, fontsize=6.5,
           handler_map={tuple: HandlerTuple(ndivide=None, pad=0.55)},
           handlelength=2.6, columnspacing=1.8, handletextpad=0.8)

# source note: classic in-map attribution, bottom-right of the overview panel
axA.annotate("Road network: © OpenStreetMap contributors", xy=(0.99, 0.015),
             xycoords="axes fraction", ha="right", va="bottom", fontsize=5,
             color=MUTED, zorder=7, path_effects=halo)

# ------------------------------------------------------------------ save
png = os.path.join(OUT_DIR, "fig2_spatial_complementarity.png")
pdf = os.path.join(OUT_DIR, "fig2_spatial_complementarity.pdf")
fig.savefig(png, dpi=600)
fig.savefig(pdf)
print(f"written:\n  {png}\n  {pdf}")
