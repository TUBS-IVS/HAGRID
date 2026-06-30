# -*- coding: utf-8 -*-
"""
DRT service-time reconstruction from the MATSim event stream.
================================================================
Core KPI requested by the user: **Service-Zeit = Zeit-mit-Personen / Gesamtbetriebszeit**,
per vehicle AND fleet, reported against THREE denominators:

  (a) active   : first dispatch -> last productive task end   (DRIVE/STOP span; excludes
                 the leading/trailing idle-at-depot STAY)            -> efficiency while in service
  (b) shift    : the vehicle's DVRP service window t_1 - t_0 (fleet file; here 0..86400)
                                                                     -> prices idle-at-depot within the shift
  (c) sim      : 0 .. max event time (the simulated horizon)         -> diluted by night idle

Numerator (all three) = time with >=1 passenger physically aboard, integrated over the day
from PersonEntersVehicle/PersonLeavesVehicle (driver excluded: person == vehicle id).

Also returns the per-vehicle time composition STAY / DRIVE(empty) / DRIVE(occupied) / STOP
from the dvrpTask events, which contextualises the ratio.

Reusable by the dashboard builder; runnable standalone for a quick numeric check:
    PYTHONIOENCODING=utf-8 python -u drt_service_time.py <events.txt|output_events.xml.gz> [fleet.xml.gz]
"""
import os, re, gzip, sys

RE_TYPE = re.compile(r'type="([^"]+)"')
RE_TIME = re.compile(r'time="([^"]+)"')
RE_VEH  = re.compile(r'\bvehicle="([^"]+)"')
RE_DVEH = re.compile(r'dvrpVehicle="([^"]+)"')
RE_PERS = re.compile(r'person="([^"]+)"')
RE_TTYPE = re.compile(r'taskType="([^"]+)"')


def _open(path):
    if path.endswith(".gz"):
        return gzip.open(path, "rt", encoding="utf-8")
    return open(path, "r", encoding="utf-8")


def read_shift_windows(fleet_file):
    """vehicle -> (t_0, t_1) from the DVRP fleet file."""
    out = {}
    if not fleet_file or not os.path.exists(fleet_file):
        return out
    rid = re.compile(r'id="([^"]+)"'); r0 = re.compile(r't_0="([^"]+)"'); r1 = re.compile(r't_1="([^"]+)"')
    with _open(fleet_file) as f:
        for line in f:
            if "<vehicle " not in line:
                continue
            mi, m0, m1 = rid.search(line), r0.search(line), r1.search(line)
            if mi and m0 and m1:
                out[mi.group(1)] = (float(m0.group(1)), float(m1.group(1)))
    return out


def read_capacity(fleet_file, default=8):
    """Seat capacity from the first <vehicle ... capacity="N"/> in the DVRP fleet file."""
    if not fleet_file or not os.path.exists(fleet_file):
        return default
    rc = re.compile(r'capacity="([^"]+)"')
    with _open(fleet_file) as f:
        for line in f:
            m = rc.search(line)
            if m:
                try:
                    return int(float(m.group(1)))
                except ValueError:
                    return default
    return default


def reconstruct(events_path, fleet_file=None):
    """
    Single time-ordered pass over the DRT events. Returns a dict with per-vehicle and
    fleet service-time metrics. `events_path` may be the full output_events.xml.gz or a
    pre-filtered file (lines containing 'drt_').
    """
    shift = read_shift_windows(fleet_file)
    capacity = read_capacity(fleet_file)

    occ = {}                 # vehicle -> current passenger count
    occ_change = {}          # vehicle -> list of (time, delta)   (passengers only)
    cur_task = {}            # vehicle -> (taskType, startTime)
    task_time = {}           # vehicle -> {STAY,DRIVE,STOP: seconds}
    first_prod = {}          # vehicle -> first DRIVE/STOP start
    last_prod = {}           # vehicle -> last DRIVE/STOP end
    first_evt = {}           # vehicle -> first event time
    last_evt = {}            # vehicle -> last event time
    max_t = 0.0

    def touch(v, t):
        if v not in first_evt:
            first_evt[v] = t
        last_evt[v] = t

    with _open(events_path) as f:
        for line in f:
            if "drt_" not in line:
                continue
            mt = RE_TYPE.search(line)
            if not mt:
                continue
            etype = mt.group(1)
            mtime = RE_TIME.search(line)
            if not mtime:
                continue
            t = float(mtime.group(1))
            if t > max_t:
                max_t = t

            if etype == "dvrpTaskStarted":
                mv = RE_DVEH.search(line); mtt = RE_TTYPE.search(line)
                if not mv:
                    continue
                v = mv.group(1)
                if not v.startswith("drt_"):
                    continue
                ttype = mtt.group(1) if mtt else "?"
                cur_task[v] = (ttype, t)
                touch(v, t)
            elif etype == "dvrpTaskEnded":
                mv = RE_DVEH.search(line)
                if not mv:
                    continue
                v = mv.group(1)
                if not v.startswith("drt_") or v not in cur_task:
                    continue
                ttype, t0 = cur_task.pop(v)
                dur = max(0.0, t - t0)
                d = task_time.setdefault(v, {"STAY": 0.0, "DRIVE": 0.0, "STOP": 0.0})
                d[ttype] = d.get(ttype, 0.0) + dur
                if ttype in ("DRIVE", "STOP"):
                    if v not in first_prod:
                        first_prod[v] = t0
                    last_prod[v] = t
                touch(v, t)
            elif etype == "PersonEntersVehicle":
                mv = RE_VEH.search(line); mp = RE_PERS.search(line)
                if not mv or not mp:
                    continue
                v = mv.group(1); p = mp.group(1)
                if v.startswith("drt_") and p != v:           # passenger boards
                    occ_change.setdefault(v, []).append((t, +1))
                    touch(v, t)
            elif etype == "PersonLeavesVehicle":
                mv = RE_VEH.search(line); mp = RE_PERS.search(line)
                if not mv or not mp:
                    continue
                v = mv.group(1); p = mp.group(1)
                if v.startswith("drt_") and p != v:           # passenger alights
                    occ_change.setdefault(v, []).append((t, -1))
                    touch(v, t)

    # integrate occupancy (>=1) over time per vehicle
    occupied_time = {}
    for v, changes in occ_change.items():
        changes.sort()
        o = 0; prev_t = None; acc = 0.0
        for (t, d) in changes:
            if prev_t is not None and o >= 1:
                acc += (t - prev_t)
            o += d
            prev_t = t
        occupied_time[v] = acc

    # ---- occupancy decomposition: constant-occupancy SEGMENTS over the active tour window ----
    # A "segment" = maximal interval of constant vehicle occupancy x in {0..CAPACITY}.
    # Bound to [first_prod, last_prod] per vehicle (overnight depot parking excluded -> assumption b).
    # Yields per-level TIME and segment COUNT; basis for the occupancy charts and for the two
    # utilisation KPIs (by trips = unweighted mean of x/cap over segments; by time = duration-weighted).
    occ_seg_time = {}     # vehicle -> {level: seconds}
    occ_seg_count = {}    # vehicle -> {level: count}
    for v in set(list(occ_change.keys()) + list(first_prod.keys())):
        a0 = first_prod.get(v); a1 = last_prod.get(v)
        if a0 is None or a1 is None or a1 <= a0:
            continue
        st = {}; sc = {}
        # net occupancy delta grouped by timestamp, clipped to the active window
        from collections import defaultdict
        by_t = defaultdict(int)
        for (t, d) in occ_change.get(v, []):
            if a0 < t < a1:
                by_t[t] += d
        o = 0; prev_t = a0
        for t in sorted(by_t):
            if t > prev_t:
                st[o] = st.get(o, 0.0) + (t - prev_t); sc[o] = sc.get(o, 0) + 1
            o += by_t[t]; prev_t = t
        if a1 > prev_t:
            st[o] = st.get(o, 0.0) + (a1 - prev_t); sc[o] = sc.get(o, 0) + 1
        occ_seg_time[v] = st; occ_seg_count[v] = sc

    vehicles = sorted(
        set(list(task_time.keys()) + list(occ_change.keys()) + list(shift.keys())),
        key=lambda v: int(v.split("_")[1]) if v.split("_")[-1].isdigit() else 0)

    sim_horizon = max_t
    per_veh = {}
    for v in vehicles:
        occt = occupied_time.get(v, 0.0)
        tt = task_time.get(v, {"STAY": 0.0, "DRIVE": 0.0, "STOP": 0.0})
        active = (last_prod[v] - first_prod[v]) if (v in first_prod and v in last_prod) else 0.0
        t0, t1 = shift.get(v, (0.0, sim_horizon))
        shift_span = max(0.0, t1 - t0)
        per_veh[v] = {
            "occupied_s": occt,
            "stay_s": tt.get("STAY", 0.0),
            "drive_s": tt.get("DRIVE", 0.0),
            "stop_s": tt.get("STOP", 0.0),
            "active_s": active,
            "shift_s": shift_span,
            "ratio_active": (occt / active) if active > 0 else 0.0,
            "ratio_shift": (occt / shift_span) if shift_span > 0 else 0.0,
            "ratio_sim": (occt / sim_horizon) if sim_horizon > 0 else 0.0,
            "occ_seg_time": occ_seg_time.get(v, {}),
            "occ_seg_count": occ_seg_count.get(v, {}),
        }

    # fleet ratios = sum numerators / sum denominators (correct aggregate, not mean of ratios)
    sum_occ = sum(p["occupied_s"] for p in per_veh.values())
    sum_active = sum(p["active_s"] for p in per_veh.values())
    sum_shift = sum(p["shift_s"] for p in per_veh.values())
    n = len(per_veh)
    drive_s = sum(p["drive_s"] for p in per_veh.values())
    stop_s = sum(p["stop_s"] for p in per_veh.values())
    # fleet occupancy decomposition: sum per-level time & segment count across vehicles
    fleet_seg_time = {}; fleet_seg_count = {}
    for p in per_veh.values():
        for lv, s in p["occ_seg_time"].items():
            fleet_seg_time[lv] = fleet_seg_time.get(lv, 0.0) + s
        for lv, c in p["occ_seg_count"].items():
            fleet_seg_count[lv] = fleet_seg_count.get(lv, 0) + c
    tot_seg_time = sum(fleet_seg_time.values())
    tot_seg_count = sum(fleet_seg_count.values())
    # utilisation = mean of (x/capacity); by_trips unweighted over segments, by_time duration-weighted
    util_by_trips = (sum((lv / capacity) * c for lv, c in fleet_seg_count.items()) / tot_seg_count) if tot_seg_count else 0.0
    util_by_time = (sum((lv / capacity) * s for lv, s in fleet_seg_time.items()) / tot_seg_time) if tot_seg_time else 0.0
    # tour duration = active span; waiting = idle between jobs = tour - driving - service (assumption b)
    tour_s = sum_active
    waiting_s = max(0.0, tour_s - drive_s - stop_s)
    fleet = {
        "n_vehicles": n,
        "capacity": capacity,
        "occupied_s": sum_occ,
        "sum_active_s": sum_active,
        "sum_shift_s": sum_shift,
        "sim_horizon_s": sim_horizon,
        "ratio_active": (sum_occ / sum_active) if sum_active > 0 else 0.0,
        "ratio_shift": (sum_occ / sum_shift) if sum_shift > 0 else 0.0,
        "ratio_sim": (sum_occ / (sim_horizon * n)) if (sim_horizon > 0 and n) else 0.0,
        "stay_s": sum(p["stay_s"] for p in per_veh.values()),
        "drive_s": drive_s,
        "stop_s": stop_s,
        "tour_s": tour_s,
        "waiting_s": waiting_s,
        "util_by_trips": util_by_trips,
        "util_by_time": util_by_time,
        "seg_time": fleet_seg_time,
        "seg_count": fleet_seg_count,
    }
    return {"per_veh": per_veh, "fleet": fleet, "sim_horizon": sim_horizon, "capacity": capacity}


def _fmt_hms(s):
    s = int(s); return f"{s//3600:d}:{(s%3600)//60:02d}:{s%60:02d}"


if __name__ == "__main__":
    ev = sys.argv[1]
    fl = sys.argv[2] if len(sys.argv) > 2 else None
    r = reconstruct(ev, fl)
    fleet = r["fleet"]
    print(f"\n=== FLEET SERVICE-TIME ({fleet['n_vehicles']} vehicles) ===")
    print(f"  occupied (pax aboard) total : {_fmt_hms(fleet['occupied_s'])}  "
          f"(avg/veh {_fmt_hms(fleet['occupied_s']/max(1,fleet['n_vehicles']))})")
    print(f"  ratio vs ACTIVE service time: {fleet['ratio_active']*100:5.1f}%   "
          f"(sum active {_fmt_hms(fleet['sum_active_s'])})")
    print(f"  ratio vs SHIFT window       : {fleet['ratio_shift']*100:5.1f}%   "
          f"(sum shift  {_fmt_hms(fleet['sum_shift_s'])})")
    print(f"  ratio vs SIM horizon        : {fleet['ratio_sim']*100:5.1f}%   "
          f"(horizon {_fmt_hms(fleet['sim_horizon_s'])})")
    print(f"  fleet time: STAY {_fmt_hms(fleet['stay_s'])}  DRIVE {_fmt_hms(fleet['drive_s'])}  "
          f"STOP {_fmt_hms(fleet['stop_s'])}")
    print(f"\n  capacity={fleet['capacity']}  utilisation by trips={fleet['util_by_trips']*100:.1f}%  "
          f"by time={fleet['util_by_time']*100:.1f}%")
    print(f"  tour duration {_fmt_hms(fleet['tour_s'])} = driving {_fmt_hms(fleet['drive_s'])} "
          f"+ service {_fmt_hms(fleet['stop_s'])} + waiting {_fmt_hms(fleet['waiting_s'])}")
    print("  segments by occupancy:", {lv: fleet['seg_count'].get(lv, 0)
                                        for lv in range(fleet['capacity'] + 1)})
    pv = r["per_veh"]
    busiest = sorted(pv.items(), key=lambda kv: kv[1]["ratio_active"], reverse=True)[:5]
    idlest = sorted(pv.items(), key=lambda kv: kv[1]["ratio_shift"])[:5]
    print("\n  top 5 by active-ratio:", [(v, f"{d['ratio_active']*100:.0f}%") for v, d in busiest])
    print("  bottom 5 by shift-ratio:", [(v, f"{d['ratio_shift']*100:.0f}%") for v, d in idlest])
