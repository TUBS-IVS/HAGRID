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

Also returns the per-vehicle time composition STAY / DRIVE / STOP (+ the three Modular
freight components below) from the dvrpTask events, which contextualises the ratio.

1d MODULAR (capsule swap): the same fleet also runs freight excursions
--------------------------------------------------------------------
On a DRT_MODULAR run a vehicle leaves passenger service, swaps its passenger capsule for a
cargo capsule, runs a multi-stop freight tour and swaps back. That puts THREE extra kinds of
time on the very same vehicles, and none of them is passenger service:

  MODULAR_FREIGHT_DRIVE   approach / inter-stop / return legs   -> `freight_drive_s`
  MODULAR_FREIGHT_STOP    freight dwell at a delivery stop      -> `freight_stop_s`
  "STOP" inside a freight window   the two capsule swaps        -> `retooling_s`

The first two are separable by task-type NAME. The swap is NOT: `ModularCapacityChangeTask`
extends the native `DefaultDrtCapacityChangeTask` on purpose (design C6 — inherit the swap
mechanics untouched), and `DrtTaskType(DrtTaskBaseType)` sets `name = baseType.name()`, so the
swap's `taskType` attribute is literally `"STOP"` — byte-identical to an ordinary passenger
stop. It is separated here by TIME instead: the `modularTour` events (`ModularTourEvent`) carry
`phase` + `vehicle`, so DISPATCHED..COMPLETED brackets each excursion per vehicle, and a
STOP-typed task starting inside that bracket is a swap. This is sound because the design's
strict lockout (D2) keeps passenger requests off a vehicle for the whole excursion: within a
freight window there ARE no passenger stops.

Before this separation existed all three landed wrong -- the two freight names fell into
never-read dict keys and therefore reappeared as `waiting_s` ("idle between jobs"), and the
swaps were counted as passenger stop time. See the module-level note in `analysis/kpi/
extract_drt.py` for which published KPIs that corrupted, and METHODS-LOG §2.14.

On a run with no freight tasks (every non-1d scenario) all three components are 0.0 and every
other number is bit-identical to before, so baseline KPIs are untouched.

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
RE_PHASE = re.compile(r'phase="([^"]+)"')

#: dvrpTaskStarted `taskType` names of the 1d Modular freight tasks — mirrors the Java
#: hagrid.integrated.modular.Modular.FREIGHT_DRIVE_TASK_TYPE / FREIGHT_STOP_TASK_TYPE.
#: The capsule swap is deliberately absent: it has no distinguishable name (see module
#: docstring), and is identified by the freight-window bracket instead.
FREIGHT_DRIVE_TASK = "MODULAR_FREIGHT_DRIVE"
FREIGHT_STOP_TASK = "MODULAR_FREIGHT_STOP"

#: hagrid.integrated.modular.ModularTourEvent.EVENT_TYPE and the two phases that bracket one
#: freight excursion on one vehicle. PLANNED/EXPIRED carry no vehicle and are ignored here.
MODULAR_EVENT_TYPE = "modularTour"
PHASE_OPEN, PHASE_CLOSE = "DISPATCHED", "COMPLETED"

#: Time-composition buckets. Everything except STAY is "productive" (in service), so it both
#: counts against the tour span and is subtracted from it when deriving `waiting_s`.
COMPOSITION_KEYS = ("STAY", "DRIVE", "STOP", "FREIGHT_DRIVE", "FREIGHT_STOP", "RETOOLING")
PRODUCTIVE_KEYS = ("DRIVE", "STOP", "FREIGHT_DRIVE", "FREIGHT_STOP", "RETOOLING")


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


#: Load dimension holding the seat count. Mirrors the Java side:
#: DrtFleetGenerator.LOAD_DIMENSION / SharedUse's DvrpLoadParams mapFleetCapacity.
SEAT_DIMENSION = "passengers"


def parse_capacity(raw):
    """Seat count from a DVRP `capacity` attribute value, or None if unreadable.

    Two serialisations occur and BOTH are real inputs here:
      - scalar, as DrtFleetGenerator writes it:            capacity="8"
      - per-dimension, as MATSim dumps it on a 2D-load run: capacity="passengers=8,parcels=20"
    The second is exactly the Shared-Use case, so parsing only the scalar form would
    fail precisely on the scenario under active work."""
    if raw is None:
        return None
    raw = raw.strip()
    if "=" in raw:
        for part in raw.split(","):
            key, _, value = part.partition("=")
            if key.strip() == SEAT_DIMENSION:
                try:
                    return int(float(value))
                except ValueError:
                    return None
        return None  # 2D load without a "passengers" dimension: not a seat count
    try:
        return int(float(raw))
    except ValueError:
        return None


def read_capacity(fleet_file):
    """Seat capacity from the first <vehicle ... capacity="..."/> in the DVRP fleet
    file, or None when the fleet file is absent/unreadable.

    NO DEFAULT ON PURPOSE. This used to fall back to 8, which silently produced
    utilisation KPIs computed against the wrong denominator whenever the fleet file
    could not be located -- and it stopped being even coincidentally right when
    SharedUse.BASE_SEATS was raised to 10 (2026-07-20), since a DRT_BASELINE vehicle
    now seats 10 while a Shared-Use one seats 8. Callers must treat None as
    "utilisation not computable" and omit those KPIs rather than invent a divisor."""
    if not fleet_file or not os.path.exists(fleet_file):
        return None
    rc = re.compile(r'capacity="([^"]+)"')
    with _open(fleet_file) as f:
        for line in f:
            m = rc.search(line)
            if m:
                return parse_capacity(m.group(1))
    return None


def _veh_sort_key(v):
    """Numeric-suffix sort key for vehicle ids, tolerant of any id shape.

    Real runs use "drt_<int>", but the id is free-form MATSim data: "drt_veh_1"
    used to raise ValueError here (split("_")[1] == "veh"), which forced a test
    fixture to rewrite its ids. Take the LAST underscore-separated token if it is
    an int, else 0; the id string breaks ties, so the order stays deterministic
    even though the caller sorts a set (arbitrary iteration order).
    """
    s = str(v)
    tail = s.rsplit("_", 1)[-1]
    return (int(tail) if tail.isdigit() else 0, s)


def freight_windows(marks):
    """[open, close) freight-excursion intervals for ONE vehicle, from its (time, +1/-1)
    DISPATCHED/COMPLETED marks.

    A vehicle can hold only one excursion at a time (the design's commitment predicate
    guarantees it), but the depth counter is kept anyway so a malformed stream degrades into
    one wide window instead of into silently interleaved half-windows.

    An excursion that never COMPLETEs — dispatched but stranded at end of day, which is
    exactly `tours_dispatched_incomplete` — leaves an OPEN window running to +inf. That is the
    correct reading, not a fallback: the vehicle never swaps back, so it never returns to
    passenger service, and every remaining task on it is freight.

    Sorting puts a close (-1) before an open (+1) at an identical timestamp, so a vehicle
    re-dispatched in the same simstep it completed produces two windows, not one.
    """
    out = []
    depth = 0
    start = None
    for t, delta in sorted(marks):
        if delta > 0:
            if depth == 0:
                start = t
            depth += 1
        elif depth > 0:
            depth -= 1
            if depth == 0:
                out.append((start, t))
                start = None
    if depth > 0:
        out.append((start, float("inf")))
    return out


def _classify(ttype, t0, windows):
    """Composition bucket for one finished task. The two freight names are self-identifying;
    a STOP starting inside a freight window is a capsule swap (retooling), NOT a passenger
    stop -- see the module docstring for why the name cannot tell them apart. Any other name
    keeps its own key: unknown task types stay visible instead of being folded into a bucket
    they may not belong in."""
    if ttype == FREIGHT_DRIVE_TASK:
        return "FREIGHT_DRIVE"
    if ttype == FREIGHT_STOP_TASK:
        return "FREIGHT_STOP"
    if ttype == "STOP" and any(a <= t0 < b for a, b in windows):
        return "RETOOLING"
    return ttype


def reconstruct(events_path, fleet_file=None):
    """
    Single time-ordered pass over the DRT events. Returns a dict with per-vehicle and
    fleet service-time metrics. `events_path` may be the full output_events.xml.gz or a
    pre-filtered file (lines containing 'drt_').

    Finished tasks are BUFFERED and classified after the pass rather than bucketed as they
    end, because a capsule swap can only be recognised once its vehicle's whole
    DISPATCHED..COMPLETED bracket is known -- and the COMPLETED event shares its timestamp
    with the swap-back task's end, so a streaming classifier would depend on the intra-timestep
    ordering of two independent event sources.
    """
    shift = read_shift_windows(fleet_file)
    capacity = read_capacity(fleet_file)

    occ_change = {}          # vehicle -> list of (time, delta)   (passengers only)
    cur_task = {}            # vehicle -> (taskType, startTime)
    done_tasks = {}          # vehicle -> list of (startTime, endTime, taskType)
    excursion_marks = {}     # vehicle -> list of (time, +1 DISPATCHED / -1 COMPLETED)
    task_time = {}           # vehicle -> {COMPOSITION_KEYS: seconds}
    first_prod = {}          # vehicle -> first productive-task start
    last_prod = {}           # vehicle -> last productive-task end
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
                done_tasks.setdefault(v, []).append((t0, t, ttype))
                touch(v, t)
            elif etype == MODULAR_EVENT_TYPE:
                # 1d only. PLANNED/EXPIRED carry no `vehicle` attribute (no vehicle is
                # assigned yet), so they never match here and need no phase filter of their own.
                mv = RE_VEH.search(line); mp = RE_PHASE.search(line)
                if not mv or not mp:
                    continue
                v = mv.group(1); phase = mp.group(1)
                if not v.startswith("drt_"):
                    continue
                if phase == PHASE_OPEN:
                    excursion_marks.setdefault(v, []).append((t, +1))
                elif phase == PHASE_CLOSE:
                    excursion_marks.setdefault(v, []).append((t, -1))
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

    # ---- classify the buffered tasks against each vehicle's freight-excursion windows ----
    # Deferred to here (not done while streaming) for the reason given in the docstring.
    windows_by_veh = {v: freight_windows(m) for v, m in excursion_marks.items()}
    for v, recs in done_tasks.items():
        windows = windows_by_veh.get(v, ())
        d = task_time.setdefault(v, {k: 0.0 for k in COMPOSITION_KEYS})
        for (t0, t1, ttype) in recs:
            key = _classify(ttype, t0, windows)
            d[key] = d.get(key, 0.0) + max(0.0, t1 - t0)
            if key in PRODUCTIVE_KEYS:
                # Freight tasks extend the active window too. For a COMPLETED excursion this
                # changes nothing (the two swaps already bracket it), but a stranded
                # incomplete one has no swap-back, and without this its hours would be
                # counted in the components while falling outside the tour span they are
                # subtracted from -- pushing waiting_s to its 0.0 clamp and losing the excess.
                # min/max rather than first-seen/last-seen: DVRP tasks on one vehicle are
                # sequential, so the two agree, but nothing here depends on that holding.
                if v not in first_prod or t0 < first_prod[v]:
                    first_prod[v] = t0
                if v not in last_prod or t1 > last_prod[v]:
                    last_prod[v] = t1
    #: True when this run carries ANY Modular freight activity -- the signal extract_drt uses
    #: to decide whether the 1d contamination provenance row applies. Derived from what was
    #: actually observed, not from the scenario name.
    modular_freight_seen = bool(windows_by_veh) or any(
        d.get("FREIGHT_DRIVE", 0.0) or d.get("FREIGHT_STOP", 0.0)
        for d in task_time.values())

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
        key=_veh_sort_key)

    sim_horizon = max_t
    per_veh = {}
    for v in vehicles:
        occt = occupied_time.get(v, 0.0)
        tt = task_time.get(v, {k: 0.0 for k in COMPOSITION_KEYS})
        active = (last_prod[v] - first_prod[v]) if (v in first_prod and v in last_prod) else 0.0
        # Vehicles present in the events but absent from the fleet file keep the
        # sim-horizon substitute (a per-vehicle gap in an otherwise known fleet).
        # A WHOLLY unknown fleet is handled at the fleet level below, where the
        # shift-based aggregates are omitted instead of silently fabricated.
        t0, t1 = shift.get(v, (0.0, sim_horizon))
        shift_span = max(0.0, t1 - t0)
        per_veh[v] = {
            "occupied_s": occt,
            "stay_s": tt.get("STAY", 0.0),
            # PASSENGER driving / stopping. On a 1d run the freight components below are NOT
            # folded in here: keeping these two pax-only is what makes them mean the same
            # thing on a Modular run as on the baseline it is compared against. All five
            # productive components are subtracted when deriving waiting_s, so the
            # tour = drive + stop + freight + retooling + waiting identity still closes.
            "drive_s": tt.get("DRIVE", 0.0),
            "stop_s": tt.get("STOP", 0.0),
            # 1d Modular components; all 0.0 on every other scenario.
            "freight_drive_s": tt.get("FREIGHT_DRIVE", 0.0),
            "freight_stop_s": tt.get("FREIGHT_STOP", 0.0),
            "retooling_s": tt.get("RETOOLING", 0.0),
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
    freight_drive_s = sum(p["freight_drive_s"] for p in per_veh.values())
    freight_stop_s = sum(p["freight_stop_s"] for p in per_veh.values())
    retooling_s = sum(p["retooling_s"] for p in per_veh.values())
    # fleet occupancy decomposition: sum per-level time & segment count across vehicles
    fleet_seg_time = {}; fleet_seg_count = {}
    for p in per_veh.values():
        for lv, s in p["occ_seg_time"].items():
            fleet_seg_time[lv] = fleet_seg_time.get(lv, 0.0) + s
        for lv, c in p["occ_seg_count"].items():
            fleet_seg_count[lv] = fleet_seg_count.get(lv, 0) + c
    tot_seg_time = sum(fleet_seg_time.values())
    tot_seg_count = sum(fleet_seg_count.values())
    # tour duration = active span; waiting = idle between jobs = tour - every productive
    # component (assumption b). The three Modular components MUST be subtracted here: they are
    # 0.0 on every other scenario, but on a 1d run leaving them out republished the fleet's
    # entire freight workload -- driving, dwelling and retooling alike -- as fleet IDLENESS.
    tour_s = sum_active
    freight_s = freight_drive_s + freight_stop_s + retooling_s
    waiting_s = max(0.0, tour_s - drive_s - stop_s - freight_s)
    fleet = {
        "n_vehicles": n,
        "capacity": capacity,
        "fleet_file_known": bool(shift),
        "occupied_s": sum_occ,
        "sum_active_s": sum_active,
        "sim_horizon_s": sim_horizon,
        "ratio_active": (sum_occ / sum_active) if sum_active > 0 else 0.0,
        "ratio_sim": (sum_occ / (sim_horizon * n)) if (sim_horizon > 0 and n) else 0.0,
        "stay_s": sum(p["stay_s"] for p in per_veh.values()),
        "drive_s": drive_s,
        "stop_s": stop_s,
        # 1d Modular; 0.0 elsewhere. `freight_s` is the total vehicle-time this fleet spent
        # NOT in passenger service because of the capsule-swap concept.
        "freight_drive_s": freight_drive_s,
        "freight_stop_s": freight_stop_s,
        "retooling_s": retooling_s,
        "freight_s": freight_s,
        "modular_freight_seen": modular_freight_seen,
        "tour_s": tour_s,
        "waiting_s": waiting_s,
        "seg_time": fleet_seg_time,
        "seg_count": fleet_seg_count,
    }
    # Shift-denominated aggregates need the DVRP service windows from the fleet file.
    # Without it every shift_span above is the sim-horizon substitute, so sum_shift is
    # not a shift at all -- omit the keys rather than publish a look-alike number.
    if shift:
        fleet["sum_shift_s"] = sum_shift
        fleet["ratio_shift"] = (sum_occ / sum_shift) if sum_shift > 0 else 0.0
    # utilisation = mean of (x/capacity); by_trips unweighted over segments, by_time
    # duration-weighted. Both need a real seat count -- see read_capacity on why there
    # is deliberately no default divisor.
    if capacity:
        fleet["util_by_trips"] = (sum((lv / capacity) * c for lv, c in fleet_seg_count.items())
                                  / tot_seg_count) if tot_seg_count else 0.0
        fleet["util_by_time"] = (sum((lv / capacity) * s for lv, s in fleet_seg_time.items())
                                 / tot_seg_time) if tot_seg_time else 0.0
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
    if "ratio_shift" in fleet:
        print(f"  ratio vs SHIFT window       : {fleet['ratio_shift']*100:5.1f}%   "
              f"(sum shift  {_fmt_hms(fleet['sum_shift_s'])})")
    else:
        print("  ratio vs SHIFT window       :   n/a   (no fleet file -> DVRP service windows unknown)")
    print(f"  ratio vs SIM horizon        : {fleet['ratio_sim']*100:5.1f}%   "
          f"(horizon {_fmt_hms(fleet['sim_horizon_s'])})")
    print(f"  fleet time: STAY {_fmt_hms(fleet['stay_s'])}  DRIVE {_fmt_hms(fleet['drive_s'])}  "
          f"STOP {_fmt_hms(fleet['stop_s'])}")
    if fleet["capacity"]:
        print(f"\n  capacity={fleet['capacity']}  utilisation by trips={fleet['util_by_trips']*100:.1f}%  "
              f"by time={fleet['util_by_time']*100:.1f}%")
        print("  segments by occupancy:", {lv: fleet['seg_count'].get(lv, 0)
                                            for lv in range(fleet['capacity'] + 1)})
    else:
        print("\n  capacity=unknown (no fleet file) -> utilisation not computed")
        print("  segments by occupancy:", dict(sorted(fleet['seg_count'].items())))
    if fleet["modular_freight_seen"]:
        print(f"  tour duration {_fmt_hms(fleet['tour_s'])} = driving {_fmt_hms(fleet['drive_s'])} "
              f"+ service {_fmt_hms(fleet['stop_s'])} + freight-drive "
              f"{_fmt_hms(fleet['freight_drive_s'])} + freight-dwell "
              f"{_fmt_hms(fleet['freight_stop_s'])} + retooling {_fmt_hms(fleet['retooling_s'])} "
              f"+ waiting {_fmt_hms(fleet['waiting_s'])}")
        print(f"  [1d MODULAR] freight total {_fmt_hms(fleet['freight_s'])} withdrawn from "
              f"passenger service; driving/service above are PASSENGER-only")
    else:
        print(f"  tour duration {_fmt_hms(fleet['tour_s'])} = driving {_fmt_hms(fleet['drive_s'])} "
              f"+ service {_fmt_hms(fleet['stop_s'])} + waiting {_fmt_hms(fleet['waiting_s'])}")
    pv = r["per_veh"]
    busiest = sorted(pv.items(), key=lambda kv: kv[1]["ratio_active"], reverse=True)[:5]
    idlest = sorted(pv.items(), key=lambda kv: kv[1]["ratio_shift"])[:5]
    print("\n  top 5 by active-ratio:", [(v, f"{d['ratio_active']*100:.0f}%") for v, d in busiest])
    print("  bottom 5 by shift-ratio:", [(v, f"{d['ratio_shift']*100:.0f}%") for v, d in idlest])
