# -*- coding: utf-8 -*-
"""Network geometry + DRT path reconstruction + polyline simplification.

Ports the map-pass logic from the legacy
analysis/drt-headline/build_drt_dashboard.py (lines ~91-150) into reusable,
tested functions for the v2 KPI dashboard maps (Plan D Tasks 5-7):

- reconstruct_drt_paths(): single-pass parse of a (pre-filtered, "drt_"-only)
  events cache into per-DRT-vehicle [(link_id, occupancy)] paths, mirroring
  the legacy regex-based occupancy bookkeeping exactly (driver excluded via
  person != vehicle).
- freight_used_links(): union of link ids touched by freight vehicles, from
  a freight_events.FreightEvents.veh_links.
- load_link_geometry(): streaming ET.iterparse over the (gzipped, no-
  namespace) MATSim network XML, restricted to a caller-supplied set of used
  links, batch-transformed EPSG:25832 -> EPSG:4326 via pyproj.
- drop_collinear() / douglas_peucker(): polyline simplification helpers.
- polyline_runs(): chains a per-vehicle link path into Leaflet-ready
  (lat, lon) point runs, breaking a new run wherever the link geometry is
  not spatially contiguous (this replaces the legacy Plotly `None`-
  separator idiom).

pyproj is a hard top-level import here (this module assumes it is
installed); maps.py is expected to guard the import of this module with
try/except ImportError so the rest of the dashboard still builds without
pyproj/geopandas/shapely present.
"""
import gzip
import math
import re
import xml.etree.ElementTree as ET
from dataclasses import dataclass

from pyproj import Transformer

RE_TYPE = re.compile(r'type="([^"]+)"')
RE_LINK = re.compile(r'link="([^"]+)"')
RE_VEHICLE = re.compile(r'\bvehicle="([^"]+)"')
RE_PERSON = re.compile(r'person="([^"]+)"')

TF = Transformer.from_crs("EPSG:25832", "EPSG:4326", always_xy=True)


@dataclass
class LinkGeo:
    flon: float
    flat: float
    tlon: float
    tlat: float
    length_m: float


def reconstruct_drt_paths(drt_cache):
    """Port of build_drt_dashboard.py:91-124. `drt_cache` is a plain-text
    (not gzipped) events cache already filtered to "drt_"-containing lines
    (see events_cache.ensure_caches). Returns (veh_path, used_links):
      veh_path[vehicle_id] = [(link_id, occupancy_at_entry), ...]
      used_links = set of all link ids seen via "entered link"
    Occupancy is captured at the moment a link is entered; the driver
    (person == vehicle) never contributes to occupancy."""
    occ = {}
    veh_path = {}
    used_links = set()
    with open(drt_cache, "r", encoding="utf-8") as f:
        for line in f:
            mt = RE_TYPE.search(line)
            if not mt:
                continue
            et = mt.group(1)
            if et == "entered link":
                mv = RE_VEHICLE.search(line)
                ml = RE_LINK.search(line)
                if mv and ml and mv.group(1).startswith("drt_"):
                    v = mv.group(1)
                    veh_path.setdefault(v, []).append((ml.group(1), occ.get(v, 0)))
                    used_links.add(ml.group(1))
            elif et == "PersonEntersVehicle":
                mv = RE_VEHICLE.search(line)
                mp = RE_PERSON.search(line)
                if mv and mp and mv.group(1).startswith("drt_") and mp.group(1) != mv.group(1):
                    occ[mv.group(1)] = occ.get(mv.group(1), 0) + 1
            elif et == "PersonLeavesVehicle":
                mv = RE_VEHICLE.search(line)
                mp = RE_PERSON.search(line)
                if mv and mp and mv.group(1).startswith("drt_") and mp.group(1) != mv.group(1):
                    occ[mv.group(1)] = max(0, occ.get(mv.group(1), 0) - 1)
    return veh_path, used_links


def freight_used_links(fev):
    """Union of link ids touched by any freight vehicle, from
    freight_events.FreightEvents.veh_links (dict[vehicle_id] -> [(link_id, time)])."""
    used = set()
    for entries in fev.veh_links.values():
        for link_id, _time in entries:
            used.add(link_id)
    return used


def load_link_geometry(network_gz, used_links):
    """Port of build_drt_dashboard.py:128-150. Streams the gzipped,
    namespace-less MATSim network XML and returns dict[link_id, LinkGeo]
    restricted to `used_links`. flon/flat/tlon/tlat are EPSG:4326 (WGS84),
    rounded to 5 decimals; length_m is computed from the EPSG:25832
    (metre) projected coordinates, not the transformed WGS84 ones."""
    node_xy = {}
    link_xy = {}
    with gzip.open(network_gz, "rt", encoding="utf-8") as f:
        for _, el in ET.iterparse(f, events=("end",)):
            if el.tag == "node":
                node_xy[el.get("id")] = (float(el.get("x")), float(el.get("y")))
                el.clear()
            elif el.tag == "link":
                lid = el.get("id")
                if lid in used_links:
                    fn = node_xy.get(el.get("from"))
                    tn = node_xy.get(el.get("to"))
                    if fn and tn:
                        link_xy[lid] = (fn[0], fn[1], tn[0], tn[1])
                el.clear()

    link_geo = {}
    ids = list(link_xy.keys())
    if ids:
        flon, flat = TF.transform([link_xy[i][0] for i in ids], [link_xy[i][1] for i in ids])
        tlon, tlat = TF.transform([link_xy[i][2] for i in ids], [link_xy[i][3] for i in ids])
        for k, lid in enumerate(ids):
            fx, fy, tx, ty = link_xy[lid]
            link_geo[lid] = LinkGeo(
                flon=round(flon[k], 5),
                flat=round(flat[k], 5),
                tlon=round(tlon[k], 5),
                tlat=round(tlat[k], 5),
                length_m=math.hypot(tx - fx, ty - fy),
            )
    return link_geo


def drop_collinear(pts, eps=1e-6):
    """Drop points that are (near-)exactly collinear with their neighbors.
    `pts` = [(lon, lat), ...]. Keeps the first and last point always."""
    if len(pts) < 3:
        return list(pts)
    out = [pts[0]]
    for i in range(1, len(pts) - 1):
        ax, ay = out[-1]
        bx, by = pts[i]
        cx, cy = pts[i + 1]
        cross = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
        if abs(cross) > eps:
            out.append(pts[i])
    out.append(pts[-1])
    return out


def _perp_dist(pt, a, b):
    """Perpendicular distance from pt to the line segment a-b."""
    ax, ay = a
    bx, by = b
    px, py = pt
    dx, dy = bx - ax, by - ay
    if dx == 0 and dy == 0:
        return math.hypot(px - ax, py - ay)
    t = ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)
    t = max(0.0, min(1.0, t))
    proj_x, proj_y = ax + t * dx, ay + t * dy
    return math.hypot(px - proj_x, py - proj_y)


def douglas_peucker(pts, tol):
    """Standard Douglas-Peucker polyline simplification. `pts` = [(lon, lat), ...]."""
    if len(pts) < 3:
        return list(pts)

    dmax = 0.0
    index = 0
    a, b = pts[0], pts[-1]
    for i in range(1, len(pts) - 1):
        d = _perp_dist(pts[i], a, b)
        if d > dmax:
            index = i
            dmax = d

    if dmax > tol:
        left = douglas_peucker(pts[: index + 1], tol)
        right = douglas_peucker(pts[index:], tol)
        return left[:-1] + right
    return [a, b]


def polyline_runs(path, link_geo):
    """Chain a per-vehicle [(link_id, occupancy)] path into Leaflet-ready
    point runs: [[(lat, lon), ...], ...]. Consecutive links stay in the same
    run while the previous link's "to" coordinate equals the next link's
    "from" coordinate; otherwise a new run starts. Links missing from
    link_geo are skipped. Replaces the legacy Plotly `None`-separator
    idiom used for discontinuous per-vehicle tours on a single trace."""
    runs = []
    current = []
    prev_to = None
    for link_id, _occ in path:
        g = link_geo.get(link_id)
        if g is None:
            continue
        f_pt = (g.flat, g.flon)
        t_pt = (g.tlat, g.tlon)
        if current and prev_to == f_pt:
            current.append(t_pt)
        else:
            if current:
                runs.append(current)
            current = [f_pt, t_pt]
        prev_to = t_pt
    if current:
        runs.append(current)
    return runs
