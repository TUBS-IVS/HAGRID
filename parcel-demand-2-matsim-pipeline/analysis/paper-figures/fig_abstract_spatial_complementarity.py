# -*- coding: utf-8 -*-
"""
Abstract figure — spatial complementarity, single-panel compact version
=======================================================================
Half-page-width (80 x 40 mm, 2:1) variant of Figure 2 for an abstract:
full DRT service area with passenger request-origin density (blue) and
parcel delivery stops (green dots), map furniture (north arrow, scale bar)
in a side column so nothing overlaps the map. No legend, no counts —
those live in the abstract text / caption. Serif (Times New Roman).

Authored at FINAL PRINT SIZE so font sizes are true: embed at 80 mm width.

Run:  python -u fig_abstract_spatial_complementarity.py
Out:  fig_abstract_spatial_complementarity.png (600 dpi) + .pdf (vector)
"""
import os
import re
import xml.etree.ElementTree as ET

import geopandas as gpd
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd
import shapely
from matplotlib.collections import LineCollection
from matplotlib.colors import ListedColormap, to_rgb
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

# ------------------------------------------------------------------ style (Fig. 2 palette)
BLUE = "#577CAA"
BLUE_DARK = "#33517A"
GREEN = "#55AA7F"
GREEN_DARK = "#2F7051"
INK = "#333333"
ROAD = "#C9C9C9"
ROAD_MINOR = "#DEDEDE"
BOUNDARY = "#9AA0A6"

plt.rcParams.update({
    "font.family": "serif",
    "font.serif": ["Times New Roman", "Times", "Nimbus Roman", "DejaVu Serif"],
    "font.size": 7,
    "text.color": INK,
    "axes.edgecolor": BOUNDARY,
    "pdf.fonttype": 42,
})

# ------------------------------------------------------------------ data (same as Fig. 2)
area = gpd.read_file(SHP)
service = area.union_all()
ax0, ay0, ax1, ay1 = service.bounds

legs = pd.read_csv(BASE + ".output_drt_legs_drt.csv", sep=";")
req_x, req_y = legs["fromX"].to_numpy(), legs["fromY"].to_numpy()
print(f"passenger requests: {len(legs)}")

svc, n_parcels = {}, 0
for _, el in ET.iterparse(CARRIERS):
    if el.tag.endswith("service"):
        svc[el.get("to")] = svc.get(el.get("to"), 0) + int(el.get("capacityDemand", 1))
        n_parcels += int(el.get("capacityDemand", 1))
        el.clear()
print(f"parcel stops: {len(svc)} links, {n_parcels} parcels")

links = pd.read_csv(BASE + ".output_links.csv.gz", sep=";", low_memory=False,
                    usecols=["link", "type", "geometry"], dtype={"link": str})
coord_re = re.compile(r"LINESTRING\(\s*([\d.\-eE]+)\s+([\d.\-eE]+),\s*([\d.\-eE]+)\s+([\d.\-eE]+)")

def endpoints(geom):
    m = coord_re.search(geom)
    return tuple(float(v) for v in m.groups()) if m else None

link_geom = dict(zip(links["link"], links["geometry"]))
stop_x, stop_y, stop_n = [], [], []
for link, dem in svc.items():
    g = link_geom.get(link)
    pts = endpoints(g) if isinstance(g, str) else None
    if pts is None:
        continue
    stop_x.append((pts[0] + pts[2]) / 2)
    stop_y.append((pts[1] + pts[3]) / 2)
    stop_n.append(dem)
stop_x, stop_y, stop_n = map(np.asarray, (stop_x, stop_y, stop_n))

pad = 800.0
roads = links[links["geometry"].str.contains("LINESTRING", na=False)
              & links["type"].str.startswith("highway", na=False)].copy()
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

# ------------------------------------------------------------------ density surface
CELL, SIGMA_M = 25.0, 350.0
gx = np.arange(ax0 - pad, ax1 + pad, CELL)
gy = np.arange(ay0 - pad, ay1 + pad, CELL)
H, _, _ = np.histogram2d(req_x, req_y, bins=[gx, gy])
D = gaussian_filter(H, sigma=SIGMA_M / CELL).T
cx, cy = (gx[:-1] + gx[1:]) / 2, (gy[:-1] + gy[1:]) / 2
CX, CY = np.meshgrid(cx, cy)
inside = shapely.contains_xy(service, CX.ravel(), CY.ravel()).reshape(D.shape)
D = np.where(inside, D, np.nan)
vmax = np.nanpercentile(D, 99.7)
Dm = np.ma.masked_invalid(D)

N = 256
t = np.linspace(0, 1, N)
rgb = np.stack([np.interp(t, [0, 0.45, 1], [to_rgb("#DCE6F2")[i], to_rgb(BLUE)[i],
                                            to_rgb(BLUE_DARK)[i]]) for i in range(3)], axis=1)
alpha = np.clip(t / 0.20, 0, 1) * 0.88
cmap = ListedColormap(np.column_stack([rgb, alpha]))
cmap.set_bad(alpha=0.0)

# ------------------------------------------------------------------ figure: 80 x 40 mm
FIG_W, FIG_H = 3.15, 1.575  # inches
fig = plt.figure(figsize=(FIG_W, FIG_H))

# map axes sized to the data aspect, flush left; furniture column to the right
data_w = (ax1 - ax0) + 2 * pad
data_h = (ay1 - ay0) + 2 * pad
m_y0, m_y1 = 0.015, 0.985
map_h_in = (m_y1 - m_y0) * FIG_H
map_w_in = map_h_in * data_w / data_h
map_w_frac = map_w_in / FIG_W
ax = fig.add_axes([0.005, m_y0, map_w_frac, m_y1 - m_y0])
ax.set_xlim(ax0 - pad, ax1 + pad)
ax.set_ylim(ay0 - pad, ay1 + pad)
ax.set_aspect("equal")
ax.set_xticks([])
ax.set_yticks([])
for s in ax.spines.values():
    s.set_linewidth(0.5)

ax.add_collection(LineCollection(segs_minor, colors=ROAD_MINOR, linewidths=0.2, zorder=1))
ax.add_collection(LineCollection(segs_major, colors=ROAD, linewidths=0.35, zorder=1.2))
ax.pcolormesh(gx, gy, Dm, cmap=cmap, vmin=0, vmax=vmax, rasterized=True,
              zorder=2, shading="flat")
for geom in getattr(service, "geoms", [service]):
    bx, by = geom.exterior.xy
    ax.plot(bx, by, color=BOUNDARY, lw=0.7, ls=(0, (3, 1.5)), zorder=3)
ax.scatter(stop_x, stop_y, s=np.maximum(np.sqrt(stop_n) * 0.55, 0.45), facecolor=GREEN,
           edgecolor=GREEN_DARK, linewidths=0.15, alpha=0.85, zorder=4)

# place label (single, plain black, top-left in the quiet white area — off the data cloud)
ax.annotate("Hoyerswerda", xy=(0.025, 0.955), xycoords="axes fraction",
            fontsize=7, color="black", style="italic", ha="left", va="top", zorder=6)

# ------------------------------------------------------------------ furniture column
fig.canvas.draw()  # finalize transforms so data->figure scaling is exact
col_x0 = 0.005 + map_w_frac
col_cx = col_x0 + (1 - col_x0) / 2

# north arrow (top of column)
fig.text(col_cx, 0.88, "N", ha="center", va="center", fontsize=7, color=INK)
plt.annotate("", xy=(col_cx, 0.83), xytext=(col_cx, 0.71),
             xycoords="figure fraction", textcoords="figure fraction",
             arrowprops=dict(arrowstyle="-|>", color=INK, lw=0.7))

# minimalist legend (centered in the column): density swatches + parcel dots.
# Legend dots are enlarged for visibility (no counts attached, so purely identity).
def sq(frac):
    return Line2D([], [], marker="s", ls="", markerfacecolor=cmap(frac),
                  markeredgecolor="none", markersize=5)

def dot(ms):
    return Line2D([], [], marker="o", ls="", markerfacecolor=GREEN,
                  markeredgecolor=GREEN_DARK, markeredgewidth=0.3,
                  markersize=ms, alpha=0.9)

leg = fig.legend(handles=[(sq(0.3), sq(0.65), sq(1.0)), (dot(2.5), dot(3.5), dot(4.5))],
                 labels=["Passenger requests", "Parcel stops"],
                 loc="center", bbox_to_anchor=(col_cx - 0.015, 0.51), frameon=False,
                 fontsize=6.5, handlelength=1.8, labelspacing=0.7, handletextpad=0.5,
                 handler_map={tuple: HandlerTuple(ndivide=None, pad=0.45)})

# scale bar: 5 km, true to the map scale (bottom of column)
p0 = ax.transData.transform((ax0, ay0))
p1 = ax.transData.transform((ax0 + 5000, ay0))
bar_frac = (p1[0] - p0[0]) / fig.bbox.width
bar_x0 = col_cx - bar_frac / 2
bar_y = 0.17
fig.add_artist(Line2D([bar_x0, bar_x0 + bar_frac], [bar_y, bar_y],
                      transform=fig.transFigure, color=INK, lw=1.0,
                      solid_capstyle="butt"))
fig.text(col_cx, bar_y + 0.035, "5 km", ha="center", va="bottom", fontsize=6.5, color=INK)

# ------------------------------------------------------------------ save
png = os.path.join(OUT_DIR, "fig_abstract_spatial_complementarity.png")
pdf = os.path.join(OUT_DIR, "fig_abstract_spatial_complementarity.pdf")
fig.savefig(png, dpi=600)
fig.savefig(pdf)
print(f"written:\n  {png}\n  {pdf}")
