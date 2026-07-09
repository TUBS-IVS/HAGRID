# -*- coding: utf-8 -*-
# Spatial association between parcel destinations and DRT request origins
# (married250 run). Grid-cell counts inside the service area, multiple cell
# sizes (MAUP check): Pearson, Spearman, Bhattacharyya overlap, co-location.
import os, re
import xml.etree.ElementTree as ET
import numpy as np
import pandas as pd
import geopandas as gpd
import shapely
from scipy.stats import pearsonr, spearmanr

REPO = r"c:\Users\Hendrik Bimmermann\Documents\GitHub\HAGRID"
RUN = "DRT_BASELINE_13052025_married250_iter300_jsprit1000"
PREFIX = "DRT_BASELINE_13052025_married250"
BASE = os.path.join(REPO, "parcel-demand-2-matsim-pipeline", "hagrid-matsim-output", RUN, PREFIX)
CARRIERS = os.path.join(REPO, "parcel-demand-2-matsim-pipeline", "hagrid-output", PREFIX,
                        "carriers", PREFIX + "_lmd_carriers_routed.xml")
SHP = os.path.join(REPO, "parcel-demand-2-matsim-pipeline", "hagrid-input", "lausitz",
                   "drt", "drt-service-area.shp")

service = gpd.read_file(SHP).union_all()
ax0, ay0, ax1, ay1 = service.bounds

legs = pd.read_csv(BASE + ".output_drt_legs_drt.csv", sep=";")
rx, ry = legs["fromX"].to_numpy(), legs["fromY"].to_numpy()

svc = {}
for _, el in ET.iterparse(CARRIERS):
    if el.tag.endswith("service"):
        svc[el.get("to")] = svc.get(el.get("to"), 0) + int(el.get("capacityDemand", 1))
        el.clear()
links = pd.read_csv(BASE + ".output_links.csv.gz", sep=";", low_memory=False,
                    usecols=["link", "geometry"], dtype={"link": str})
cre = re.compile(r"LINESTRING\(\s*([\d.\-eE]+)\s+([\d.\-eE]+),\s*([\d.\-eE]+)\s+([\d.\-eE]+)")
geom = dict(zip(links["link"], links["geometry"]))
px, py, pw = [], [], []
for link, dem in svc.items():
    m = cre.search(geom.get(link, "") or "")
    if m:
        x0, y0, x1, y1 = map(float, m.groups())
        px.append((x0 + x1) / 2); py.append((y0 + y1) / 2); pw.append(dem)
px, py, pw = map(np.asarray, (px, py, pw))
print(f"requests n={len(rx)}, parcel stops n={len(px)}, parcels={pw.sum()}")

print(f"\n{'cell':>6} {'nCells':>6} {'active':>6} | {'Pearson':>8} {'Spearman':>8} "
      f"{'Pear(log)':>9} | {'BC':>5} | {'coloc%':>6}")
for cell in (250, 500, 1000, 2000):
    gx = np.arange(ax0, ax1 + cell, cell)
    gy = np.arange(ay0, ay1 + cell, cell)
    R, _, _ = np.histogram2d(rx, ry, bins=[gx, gy])
    P, _, _ = np.histogram2d(px, py, bins=[gx, gy], weights=pw)
    cx = (gx[:-1] + gx[1:]) / 2
    cy = (gy[:-1] + gy[1:]) / 2
    CX, CY = np.meshgrid(cx, cy, indexing="ij")
    inside = shapely.contains_xy(service, CX.ravel(), CY.ravel())
    r = R.ravel()[inside]
    p = P.ravel()[inside]
    active = (r > 0) | (p > 0)
    pear = pearsonr(r, p)[0]
    spear = spearmanr(r, p)[0]
    pear_log = pearsonr(np.log1p(r), np.log1p(p))[0]
    # Bhattacharyya overlap of the two normalized distributions (1 = identical)
    bc = np.sum(np.sqrt((r / r.sum()) * (p / p.sum())))
    # share of parcels in cells that also have >=1 request
    coloc = p[r > 0].sum() / p.sum() * 100
    print(f"{cell:>5}m {inside.sum():>6} {active.sum():>6} | {pear:>8.3f} {spear:>8.3f} "
          f"{pear_log:>9.3f} | {bc:>5.3f} | {coloc:>5.1f}%")

    if cell == 500:
        # active-cells-only robustness (kills the both-zero rural inflation)
        pear_a = pearsonr(r[active], p[active])[0]
        spear_a = spearmanr(r[active], p[active])[0]
        print(f"        500m, only active cells (n={active.sum()}): "
              f"Pearson {pear_a:.3f}, Spearman {spear_a:.3f}")
