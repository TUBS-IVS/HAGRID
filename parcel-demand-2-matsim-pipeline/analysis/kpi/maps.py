# -*- coding: utf-8 -*-
"""DRT map layers -> a compact `map_data` dict (v2 KPI dashboard, Plan D
Task 6). render_maps.py (Task 8) turns this dict into Leaflet; this module
is data-layer only -- no rendering, no JS.

build_map_data() assembles:
  - drt.vehicles: per-vehicle tour polylines, bucketed by occupancy level
    (reuses geometry.py's polyline_runs/drop_collinear/douglas_peucker --
    Task 4/5), plus per-vehicle numbered pickup/dropoff stops.
  - drt.pu / drt.do: fleet-wide pickup/dropoff dot layers (optionally
    downsampled via the legacy n_sample lever).
  - drt.service_area / drt.depots / drt.rail_stops: optional layers, each
    independently and gracefully omitted (ASCII console note, never a raise)
    when their source file/library is missing -- ports of
    build_drt_dashboard.py:247-299.
  - drt.cap: DRT vehicle capacity, read from the `system/drt_vehicle_capacity`
    KPI in kpis_long.csv (extract_drt sources it from the DVRP fleet file);
    DEFAULT_CAP only when that KPI is absent, which means the fleet file was
    not found for this run.
  - center: mean of whatever WGS84 points are available, falling back to
    Hoyerswerda.
  - lmd: always present; stays `{}` unless a `fev` (FreightEvents) is
    supplied (Task 7), in which case it is filled with tours/stops/heat/
    depots built from `fev` + `carriers` + `excluded` + `link_geo`:
      - tours: per freight vehicle in fev.veh_links, resolved to a
        (provider, carrier) via matching event_vehicle_id against each
        carrier's tours -- a vehicle with no carrier match (e.g. a shared
        drt_freight_* vehicle) is not a real LMD tour and is omitted
        entirely; `excluded` vehicles are omitted too. runs reuse the same
        polyline_runs/drop_collinear/douglas_peucker chain as drt.vehicles.
        A matched vehicle whose links all lack geometry keeps runs: [].
      - stops: order-based zip of a tour's service-start times to its
        service capacityDemands (same demand-lookup default of 1 as
        freight_events.parcels_per_hour_by_provider), each stop's coords
        are the midpoint of the vehicle's nearest PRECEDING entered-link at
        that start time; a start with no known preceding link is skipped.
      - heat: entered-link counts over ALL freight vehicles (no carrier
        match required, no exclusion filter), midpoint per link.
      - depots: shared verbatim with drt.depots (same source list).

All emitted coordinates are [lat, lon], rounded to 5 decimals.
"""
import json
from pathlib import Path

import pandas as pd
from pyproj import Transformer

import freight_classify
import geometry

TF = Transformer.from_crs("EPSG:25832", "EPSG:4326", always_xy=True)

DEFAULT_CAP = 8
HOYERSWERDA_CENTER = [51.44, 14.24]


# --------------------------------------------------------------- vehicles

def _build_vehicles(veh_path, link_geo):
    """veh_path[v] = [(link_id, occ), ...] -> {v: {"segs": {"<occ>": [[[lat,lon],...] per run]},
    "stops": []}}. `stops` is filled in later by _attach_stops(). A vehicle
    whose links are all absent from link_geo keeps segs={} (not dropped)."""
    vehicles = {}
    for veh, path in (veh_path or {}).items():
        by_occ = {}
        for link_id, occ in path:
            by_occ.setdefault(occ, []).append((link_id, occ))
        segs = {}
        for occ, subpath in by_occ.items():
            runs = []
            for run in geometry.polyline_runs(subpath, link_geo or {}):
                simplified = geometry.douglas_peucker(geometry.drop_collinear(run), 1e-5)
                if simplified:
                    runs.append([[round(lat, 5), round(lon, 5)] for lat, lon in simplified])
            if runs:
                segs[str(occ)] = runs
        vehicles[veh] = {"segs": segs, "stops": []}
    return vehicles


# --------------------------------------------------------------- legs csv

def _time_col(df):
    for c in ("departureTime", "startTime"):
        if c in df.columns:
            return c
    return None


def _load_legs(run_dir, prefix, n_sample):
    """Returns a DataFrame with added pu_lat/pu_lon/do_lat/do_lon (WGS84,
    rounded 5) columns, or None if the legs CSV is absent. n_sample (if set
    and the row count exceeds it) downsamples the RAW rows first (the
    legacy lever), before the coordinate transform / pu/do construction."""
    path = Path(run_dir) / (prefix + ".output_drt_legs_drt.csv")
    if not path.exists():
        return None
    df = pd.read_csv(path, sep=";").dropna(subset=["fromX", "fromY", "toX", "toY"])
    df = df.reset_index(drop=True)
    if n_sample is not None and len(df) > n_sample:
        df = df.sample(n_sample, random_state=42).reset_index(drop=True)
    if df.empty:
        df = df.assign(pu_lat=[], pu_lon=[], do_lat=[], do_lon=[])
        return df
    # NB: pass plain python lists (not numpy arrays) -- pyproj emits a
    # ndim>0-to-scalar DeprecationWarning when transforming length-1 numpy
    # arrays.
    pu_lon, pu_lat = TF.transform(list(df["fromX"]), list(df["fromY"]))
    do_lon, do_lat = TF.transform(list(df["toX"]), list(df["toY"]))
    df = df.assign(
        pu_lat=[round(v, 5) for v in pu_lat], pu_lon=[round(v, 5) for v in pu_lon],
        do_lat=[round(v, 5) for v in do_lat], do_lon=[round(v, 5) for v in do_lon],
    )
    return df


def _pu_do(legs):
    if legs is None or legs.empty:
        return [], []
    pu = [[la, lo] for la, lo in zip(legs["pu_lat"], legs["pu_lon"])]
    do = [[la, lo] for la, lo in zip(legs["do_lat"], legs["do_lon"])]
    return pu, do


def _attach_stops(vehicles, legs):
    """Fills each vehicle's "stops" list from the (possibly downsampled)
    legs, filtered by vehicleId, numbered 1..n by departure order. Each leg
    emits a pu record (from-coord) and a do record (to-coord) sharing `n`
    and `t` (departure/start time if a column for it exists, else 0)."""
    for veh, entry in vehicles.items():
        stops = []
        if legs is not None and not legs.empty and "vehicleId" in legs.columns:
            sub = legs[legs["vehicleId"] == veh]
            if not sub.empty:
                col = _time_col(sub)
                if col is not None:
                    sub = sub.sort_values(col)
                sub = sub.reset_index(drop=True)
                for n, row in enumerate(sub.itertuples(), start=1):
                    t = int(getattr(row, col)) if col is not None else 0
                    stops.append({"lat": row.pu_lat, "lon": row.pu_lon, "t": t, "n": n, "kind": "pu"})
                    stops.append({"lat": row.do_lat, "lon": row.do_lon, "t": t, "n": n, "kind": "do"})
        entry["stops"] = stops


# --------------------------------------------------------------- cap

CAP_KPI = "drt_vehicle_capacity"


def _read_cap(run_dir, default=DEFAULT_CAP):
    """DRT seat count for the occupancy colouring, from the `drt_vehicle_capacity` KPI.

    The previous lookup matched any kpi_name containing "capacity" AND a kpi_group
    containing "drt" -- no such row is ever written to kpis_long.csv (the only
    "capacity" KPI lives in the separate kpis_provider.csv, under kpi_group "freight"),
    so the branch was unreachable and every map silently used DEFAULT_CAP. extract_drt
    now emits the seat count from the DVRP fleet file under the exact name matched here."""
    path = Path(run_dir) / "analysis" / "kpis_long.csv"
    if not path.exists():
        return default
    try:
        df = pd.read_csv(path, sep=";")
        rows = df[df["kpi_name"].astype(str) == CAP_KPI]
        if not rows.empty:
            return int(float(rows.iloc[0]["value"]))
        print("[maps] " + CAP_KPI + " KPI absent (no fleet file?) -- occupancy scaled to "
              + str(default) + " seats")  # ASCII only
    except Exception as e:
        print("[maps] cap KPI read skipped: " + str(e))  # ASCII only
    return default


# --------------------------------------------------------------- optional layers

def _service_area(run_dir):
    """Returns (rings, service_poly) or None. Lazy geopandas import: on
    ImportError, missing shp, or any read error -> None + ASCII note (never
    raises). Port of build_drt_dashboard.py:247-259."""
    shp = Path(run_dir) / ".." / ".." / "hagrid-input" / "lausitz" / "drt" / "drt-service-area.shp"
    try:
        import geopandas as gpd
    except ImportError:
        print("[maps] geopandas not installed -- service_area skipped")
        return None
    try:
        if not shp.exists():
            print("[maps] service-area shp not found -- service_area skipped")
            return None
        sa = gpd.read_file(shp)
        service_poly = sa.geometry.union_all() if hasattr(sa.geometry, "union_all") else sa.geometry.unary_union
        rings = []
        for g in sa.geometry.tolist():
            for p in (g.geoms if g.geom_type == "MultiPolygon" else [g]):
                xs, ys = p.exterior.xy
                lo, la = TF.transform(list(xs), list(ys))
                rings.append([[round(a, 5), round(o, 5)] for a, o in zip(la, lo)])
        return rings, service_poly
    except Exception as e:
        print("[maps] service-area read FAILED: " + str(e))  # ASCII only
        return None


def _depots(run_dir):
    """Returns a list of {"name","lat","lon"} or None. Port of
    build_drt_dashboard.py:291-299 -- with one intentional deviation: the
    legacy script uppercases the provider name (`p.upper()`) for its display
    label, but v2 keeps the provider name verbatim from the CSV (lowercase),
    consistent with how PROVIDER_SLOTS/CAT/classify key providers elsewhere
    in v2. Uppercasing here would be inconsistent with that convention and
    could break any future name-to-provider matching."""
    path = Path(run_dir) / ".." / ".." / "hagrid-input" / "lausitz" / "hubs" / "lmd-depots.csv"
    try:
        if not path.exists():
            print("[maps] depots csv not found -- depots skipped")
            return None
        dep = pd.read_csv(path, sep=";")
        lon, lat = TF.transform(list(dep["x"]), list(dep["y"]))
        return [{"name": p, "lat": round(la, 5), "lon": round(lo, 5)}
                for p, la, lo in zip(dep["provider"], lat, lon)]
    except Exception as e:
        print("[maps] depots read FAILED: " + str(e))  # ASCII only
        return None


# --------------------------------------------------------------- lmd (Task 7)

def _link_midpoint(link_geo, link_id):
    """Rounded (lat, lon) midpoint of `link_id`'s geometry, or None if the
    link is absent from `link_geo`."""
    g = (link_geo or {}).get(link_id)
    if g is None:
        return None
    return round((g.flat + g.tlat) / 2, 5), round((g.flon + g.tlon) / 2, 5)


def _evid_lookup(carriers):
    """evid -> (provider, carrier_id) for every tour of every carrier, keyed
    by TourDef.event_vehicle_id(carrier_id) -- the same id fev.veh_links is
    keyed on for a real freight vehicle."""
    lookup = {}
    for c in carriers or []:
        prov = freight_classify.provider_of(c.carrier_id, c.attrs.get("provider"))
        for t in c.tours:
            lookup[t.event_vehicle_id(c.carrier_id)] = (prov, c.carrier_id)
    return lookup


def _lmd_tours(fev, carriers, excluded, link_geo):
    """One entry per freight vehicle that resolves to a (provider, carrier)
    via `_evid_lookup` -- an unmatched vehicle (e.g. a shared drt_freight_*
    id) is not a real LMD tour and is omitted. `excluded` vehicles are
    omitted too. runs reuse the same polyline_runs/drop_collinear/
    douglas_peucker chain as the DRT vehicles layer; a matched vehicle whose
    links are all absent from link_geo keeps runs: []."""
    lookup = _evid_lookup(carriers)
    link_geo = link_geo or {}
    tours = []
    for veh, path in (fev.veh_links or {}).items():
        if veh in (excluded or set()):
            continue
        resolved = lookup.get(veh)
        if resolved is None:
            continue
        prov, carrier_id = resolved
        runs = []
        for run in geometry.polyline_runs(path, link_geo):
            simplified = geometry.douglas_peucker(geometry.drop_collinear(run), 1e-5)
            if simplified:
                runs.append([[round(lat, 5), round(lon, 5)] for lat, lon in simplified])
        tours.append({"veh": veh, "provider": prov, "carrier": carrier_id, "runs": runs})
    return tours


def _nearest_preceding_link(veh_links, t):
    """Link id of the entry in `veh_links` ([(link_id, link_time), ...])
    with the largest link_time <= t, or None if no such entry exists."""
    best_link, best_time = None, None
    for link_id, link_time in veh_links or []:
        if link_time <= t and (best_time is None or link_time > best_time):
            best_link, best_time = link_id, link_time
    return best_link


def _lmd_stops(fev, carriers, excluded, link_geo):
    """Per-stop records: for each carrier tour (skipping `excluded`
    vehicles), zip the vehicle's service-start times to its services'
    capacityDemands by order (same demand.get(sid, 1) default as
    freight_events.parcels_per_hour_by_provider). Each stop sits at the
    midpoint of the vehicle's nearest preceding entered-link at that start
    time; a start with no known preceding link (or a link absent from
    link_geo) is skipped."""
    excluded = excluded or set()
    stops = []
    for c in carriers or []:
        prov = freight_classify.provider_of(c.carrier_id, c.attrs.get("provider"))
        demand = {sid: s.capacity_demand for sid, s in c.services.items()}
        for t in c.tours:
            vid = t.event_vehicle_id(c.carrier_id)
            if vid in excluded:
                continue
            starts = fev.service_starts.get(vid, [])
            demands = [demand.get(sid, 1) for sid in t.service_ids]
            veh_links = fev.veh_links.get(vid, [])
            for st, d in zip(starts, demands):
                link_id = _nearest_preceding_link(veh_links, st)
                if link_id is None:
                    continue
                mid = _link_midpoint(link_geo, link_id)
                if mid is None:
                    continue
                lat, lon = mid
                stops.append({"lat": lat, "lon": lon, "provider": prov, "veh": vid,
                              "t": int(st), "demand": d})
    return stops


def _lmd_heat(fev, link_geo):
    """Entered-link counts over ALL freight vehicles in fev.veh_links (no
    carrier match required, no exclusion filter -- raw link usage). Links
    absent from link_geo are skipped."""
    counts = {}
    for entries in (fev.veh_links or {}).values():
        for link_id, _time in entries:
            counts[link_id] = counts.get(link_id, 0) + 1
    heat = []
    for link_id, cnt in counts.items():
        mid = _link_midpoint(link_geo, link_id)
        if mid is None:
            continue
        lat, lon = mid
        heat.append([lat, lon, cnt])
    return heat


def _build_lmd(fev, carriers, excluded, link_geo, depots):
    return {
        "tours": _lmd_tours(fev, carriers, excluded, link_geo),
        "stops": _lmd_stops(fev, carriers, excluded, link_geo),
        "heat": _lmd_heat(fev, link_geo),
        "depots": depots or [],
    }


# --------------------------------------------------------------- center

def _compute_center(pu, do, link_geo, depots):
    pts = list(pu) + list(do)
    if not pts and link_geo:
        pts = [[g.flat, g.flon] for g in link_geo.values()] + [[g.tlat, g.tlon] for g in link_geo.values()]
    if not pts and depots:
        pts = [[d["lat"], d["lon"]] for d in depots]
    if not pts:
        return list(HOYERSWERDA_CENTER)
    lat = sum(p[0] for p in pts) / len(pts)
    lon = sum(p[1] for p in pts) / len(pts)
    return [round(lat, 5), round(lon, 5)]


# --------------------------------------------------------------- public API

def build_map_data(run_dir, prefix, veh_path=None, link_geo=None, fev=None,
                    carriers=None, excluded=None, n_sample=None):
    """Assembles the map_data dict consumed verbatim by render_maps.py
    (Task 8). `lmd` stays `{}` unless `fev` (a freight_events.FreightEvents)
    is supplied, in which case it is built from `fev`/`carriers`/`excluded`/
    `link_geo` (Task 7) -- see the module docstring for the construction."""
    run_dir = Path(run_dir)
    veh_path = veh_path or {}
    link_geo = link_geo or {}
    excluded = excluded or set()

    vehicles = _build_vehicles(veh_path, link_geo)
    legs = _load_legs(run_dir, prefix, n_sample)
    pu, do = _pu_do(legs)
    _attach_stops(vehicles, legs)

    drt = {
        "vehicles": vehicles,
        "pu": pu,
        "do": do,
        "cap": _read_cap(run_dir),
    }

    sa = _service_area(run_dir)
    if sa is not None:
        drt["service_area"] = sa[0]  # sa = (rings, polygon); polygon no longer consumed

    depots = _depots(run_dir)
    if depots is not None:
        drt["depots"] = depots

    center = _compute_center(pu, do, link_geo, depots)

    lmd = {}
    if fev is not None:
        lmd = _build_lmd(fev, carriers, excluded, link_geo, depots)

    return {"center": center, "drt": drt, "lmd": lmd}


def write(map_data, out_file):
    with open(out_file, "w", encoding="utf-8") as f:
        json.dump(map_data, f, separators=(",", ":"))
