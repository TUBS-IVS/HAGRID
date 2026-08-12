# -*- coding: utf-8 -*-
"""Extract network-level KPIs from the per-run Java LMD dashboards of the
Hannover capacity-sensitivity sweep into sweep_data.json + sweep_kpis.csv.

Reads the server-rendered SUMMARY=[...] JSON (per provider, with per-vehicle
vehDetails) out of each dashboard HTML. Explicit run list -- no globbing
(exclusions 30R1 / 175 / 50v2_m are deliberate, see the 2026-07-29 spec).

Run:  python -u extract_sweep.py
"""
import csv
import hashlib
import json
import sys
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO_ROOT = HERE.parents[2]
DESKTOP = Path(r"C:\Users\Hendrik Bimmermann\Desktop\Sim_Results")
V2_DIR = DESKTOP / "0726" / "Run1" / "Dashboards"

WORKTIME_LIMIT_H = 7.0     # MAXROUTEDURATION; overtime beyond it exists (costOT)
CAPA_LIMIT_FRAC = 0.9      # parcels > 0.9 * cap -> capacity-limited

# --- sweep points per series -------------------------------------------------
# Explicit, never globbed: a missing file must raise, not silently shrink the
# sweep. EXPECTED_RUNS is the deliberate cross-check on these lists - update it
# in the same edit, so an accidental change to a list fails the run.

V1_CAPS = list(range(30, 401, 10))                       # 38 points

# Original v2 arm (sim-PC, 2026-07-23/27): 30-150 step 10. Extended 2026-08-01/10
# by the dev-PC with 160-290, and 2026-08-11/12 with 70 (the run lost to the July
# JVM crash), 300 and 310. Caps 320-400 are still being produced -- add them as
# they land; v3 already covers that range.
V2_CAPS = list(range(30, 151, 10)) + list(range(160, 311, 10))     # 29 points
V2_MISSING = tuple(range(320, 401, 10))                            # still running

# v3 arm (sim-PC, 2026-08-01/10): 30-400 step 10. COMPLETE as of 2026-08-12 --
# the three ZGC EXCEPTION_ACCESS_VIOLATION casualties (170v3, 270v3, 330v3) were
# redone on G1, and 390v3/400v3 finished.
V3_CAPS = list(range(30, 401, 10))                                 # 38 points

EXPECTED_RUNS = {"v1": 39, "v2": 29, "v3": 38}   # v1 includes the 120_v2 replicate

# V2_MISSING is not decoration: present + missing must partition the full grid, so
# moving a cap out of the missing list without adding it to V2_CAPS (or vice versa)
# fails here instead of silently shrinking the v2 arm by one point.
_full_grid = list(range(30, 401, 10))
if sorted(V2_CAPS + list(V2_MISSING)) != _full_grid:
    raise ValueError(f"V2_CAPS + V2_MISSING must partition {_full_grid[0]}..{_full_grid[-1]} "
                     f"step 10; got {len(V2_CAPS)} + {len(V2_MISSING)} caps with overlaps or gaps")
if len(V2_CAPS) != EXPECTED_RUNS["v2"] or len(V3_CAPS) != EXPECTED_RUNS["v3"]:
    raise ValueError(f"list lengths v2={len(V2_CAPS)} v3={len(V3_CAPS)} disagree with "
                     f"EXPECTED_RUNS {EXPECTED_RUNS} - update both in the same edit")


def board(name: str) -> str:
    return f"HAGRID_Dashboard_BASECASE_13052025_{name}_iter150_jsprit1000.html"


def run_list():
    """(series, cap, replicate_id, path) -- replicate_id None = sweep point.

    Caps 50 and 300 have no plain-tag v1 board; their sweep points ARE the
    old-code runs tagged 50v2_l / 300v2 (file dates 2026-02-19, "v2" was just
    the tag back then). Only 120_v2 is a genuine replicate (120 also exists).

    v2 and v3 at the same capacity are genuine replicates, not duplicates: the
    tag is part of the runId and runId.hashCode() seeds both the demand-layer
    RNG and CarrierVehicleFactory (CarrierGenerator.java:87-89). The Hannover
    LMD code path itself was verified identical between the two arms."""
    runs = []
    for cap in V1_CAPS:
        tag = {50: "50v2_l", 300: "300v2"}.get(cap, str(cap))
        runs.append(("v1", cap, None, DESKTOP / board(tag)))
    runs.append(("v1", 120, "120_v2", DESKTOP / board("120_v2")))
    for cap in V2_CAPS:
        runs.append(("v2", cap, None, V2_DIR / board(f"{cap}v2")))
    for cap in V3_CAPS:
        runs.append(("v3", cap, None, V2_DIR / board(f"{cap}v3")))
    return runs


def slice_json_array(text: str, marker: str):
    """Return the JSON array assigned as `<marker>=[...]` via bracket matching
    (string-aware). Fails loud on 0 or >1 occurrences."""
    needle = marker + "=["
    first = text.find(needle)
    if first < 0:
        raise ValueError(f"marker {marker}=[ not found")
    if text.find(needle, first + 1) >= 0:
        raise ValueError(f"marker {marker}=[ found more than once")
    i = first + len(needle) - 1          # points at '['
    depth = 0
    in_str = False
    esc = False
    for j in range(i, len(text)):
        c = text[j]
        if in_str:
            if esc:
                esc = False
            elif c == "\\":
                esc = True
            elif c == '"':
                in_str = False
            continue
        if c == '"':
            in_str = True
        elif c in "[{":
            depth += 1
        elif c in "]}":
            depth -= 1
            if depth == 0:
                return json.loads(text[i:j + 1])
    raise ValueError(f"unbalanced brackets after {marker}=[")


def classify(veh):
    """4-class tour limit: worktime durH>7.0, capacity parcels>0.9*cap."""
    wt = veh["durH"] > WORKTIME_LIMIT_H
    ca = veh["parcels"] > CAPA_LIMIT_FRAC * veh["cap"]
    if wt and ca:
        return "both"
    if wt:
        return "worktime_only"
    if ca:
        return "capa_only"
    return "neither"


def extract_run(series, cap, rep, path):
    text = path.read_text(encoding="utf-8")
    summary = slice_json_array(text, "SUMMARY")
    carrier_detail = slice_json_array(text, "CARRIER_DETAIL")

    vehs = [v for p in summary for v in p["vehDetails"]]
    n_vehicles = sum(p["vehicles"] for p in summary)
    if len(vehs) != n_vehicles:
        raise ValueError(f"{path.name}: vehDetails {len(vehs)} != Sum vehicles {n_vehicles}")
    n_tours = sum(c["tours"] for c in carrier_detail)
    cap_sum = sum(v["cap"] for v in vehs)
    parcels = sum(p["parcels"] for p in summary)

    # tour_h uniformly from per-tour durH (present in every schema generation).
    # Feb-2026 boards (50v2_l, 300v2) lack the provider-level tourDurH field;
    # where it exists it must agree with the vehDetails sum (1% tolerance).
    tour_h = sum(v["durH"] for v in vehs)
    if "tourDurH" in summary[0]:
        prov_h = sum(p["tourDurH"] for p in summary)
        if prov_h > 0 and abs(prov_h - tour_h) / prov_h > 0.01:
            print(f"  WARN {path.name}: tourDurH {prov_h:.0f} vs vehDetails sum {tour_h:.0f}")

    limits = {k: 0 for k in ("worktime_only", "capa_only", "both", "neither")}
    for v in vehs:
        limits[classify(v)] += 1

    return {
        "series": series,
        "cap": cap,
        "replicate": rep,
        "file": path.name,
        "kpis": {
            "tour_km": round(sum(p["distKm"] for p in summary), 1),
            "tour_h": round(tour_h, 1),
            "cost_eur": round(sum(p["cost"] for p in summary), 0),
            "vehicles": n_vehicles,
            "parcels": parcels,
            "parcels_per_vehicle": round(parcels / n_vehicles, 1),
            "utilization": round(sum(v["parcels"] for v in vehs) / cap_sum, 4),
        },
        "limits": {**limits, "total_tours": len(vehs)},
        "meta": {"carrier_detail_tours": n_tours, "providers": len(summary)},
    }


def validate(runs):
    """Anchor + consistency checks; loud failure on violation."""
    by_key = {}
    for r in runs:
        key = (r["series"], r["cap"], r["replicate"])
        if key in by_key:
            raise ValueError(f"duplicate run key {key}")
        by_key[key] = r
    v1_30 = by_key[("v1", 30, None)]["kpis"]["vehicles"]
    if not 3100 <= v1_30 <= 3300:
        raise ValueError(f"anchor failed: v1-30 vehicles {v1_30}, expected ~3206")
    for r in runs:
        t, v = r["meta"]["carrier_detail_tours"], r["limits"]["total_tours"]
        if abs(t - v) / v > 0.02:
            print(f"  WARN {r['file']}: CARRIER_DETAIL tours {t} vs vehicles {v}")
    counts = {s: sum(1 for r in runs if r["series"] == s) for s in EXPECTED_RUNS}
    if counts != EXPECTED_RUNS:
        raise ValueError(f"run counts {counts} != expected {EXPECTED_RUNS}")
    unknown = {r["series"] for r in runs} - set(EXPECTED_RUNS)
    if unknown:
        raise ValueError(f"runs present for undeclared series: {sorted(unknown)}")


def main():
    # 300v2 exists on Desktop AND in repo root -- verify identical, use Desktop
    dup_a, dup_b = DESKTOP / board("300v2"), REPO_ROOT / board("300v2")
    if dup_a.exists() and dup_b.exists():
        ha = hashlib.sha256(dup_a.read_bytes()).hexdigest()
        hb = hashlib.sha256(dup_b.read_bytes()).hexdigest()
        print(f"300v2 desktop==repo-root: {ha == hb}")

    runs = []
    for series, cap, rep, path in run_list():
        if not path.exists():
            raise FileNotFoundError(path)
        r = extract_run(series, cap, rep, path)
        runs.append(r)
        k, li = r["kpis"], r["limits"]
        print(f"{series} cap={cap:<4} rep={str(rep):<8} veh={k['vehicles']:<5} "
              f"km={k['tour_km']:<8} util={k['utilization']:.3f} "
              f"wt={li['worktime_only']:<5} ca={li['capa_only']:<5} "
              f"both={li['both']:<5} none={li['neither']}")

    validate(runs)

    # Derived, never a literal: a hardcoded date keeps asserting the vintage of an
    # earlier extraction after the inputs have changed, which is how a refreshed
    # board ends up looking older (or newer) than its data.
    out = {"source": "HAGRID Java LMD dashboards (SUMMARY blob)",
           "extracted": date.today().isoformat(),
           "worktime_limit_h": WORKTIME_LIMIT_H,
           "capa_limit_frac": CAPA_LIMIT_FRAC,
           "runs": runs}
    (HERE / "sweep_data.json").write_text(json.dumps(out, indent=1), encoding="utf-8")

    cols = ["series", "cap", "replicate", "tour_km", "tour_h", "cost_eur", "vehicles",
            "parcels", "parcels_per_vehicle", "utilization",
            "worktime_only", "capa_only", "both", "neither", "total_tours"]
    with open(HERE / "sweep_kpis.csv", "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(cols)
        for r in runs:
            w.writerow([r["series"], r["cap"], r["replicate"] or ""]
                       + [r["kpis"][c] for c in cols[3:10]]
                       + [r["limits"][c] for c in cols[10:]])
    print(f"\nOK: {len(runs)} runs -> sweep_data.json + sweep_kpis.csv")


if __name__ == "__main__":
    sys.exit(main())
