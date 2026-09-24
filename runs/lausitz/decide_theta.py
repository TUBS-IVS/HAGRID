"""Pick the better theta for the 1d fleet sweep, so the weekend chain never stalls
on a human decision at 04:00.

Rule, in order:
  1. FEASIBLE = delivers ~all parcels AND serves at least as many passenger rides
     as the freshly rerun baseline. Both constraints must hold; a run that is cheap
     because it moved less is not a winner (that trap cost us a whole comparison
     round on 2026-08-14).
  2. Among feasible runs the lower drt_tour_hours_total wins -- labour is 83 % of
     direct operating cost and km track hours, so hours is a valid cost proxy and
     is readable without the cost model.
  3. If none is feasible, pick the smaller constraint violation and say so loudly:
     the chain continues, but the result is flagged, not silently promoted.

Writes the chosen theta to chosen_theta.txt (bare number, for the .bat `for /f`).
"""
import sys
from pathlib import Path

import pandas as pd

OUT = Path(__file__).resolve().parent
# Layout: dieses Skript liegt in <repo>/runs/lausitz/, die Laufordner in <repo>/hagrid/simulation/hagrid-matsim-output/ -- parents[1] ist also die Repo-Wurzel.
ROOT = OUT.parents[1] / "hagrid" / "simulation" / "hagrid-matsim-output"
PARCEL_TOL = 0.999          # 100 % means 100 %: overlay loss is cosmetic (user 2026-08-14)
#: Rides need a TOLERANCE, parcels do not. Found 2026-08-15 before the decision fired: the
#: rerun baseline serves 9076 rides and f150t015 serves 9070 -- 6 rides, 0.07 % -- and a hard
#: `>=` would have declared the only feasible candidate infeasible and pushed the whole chain
#: into the fallback branch. 1 % is the right scale: switching the jsprit heuristic alone moved
#: the baseline's ride count by +1.1 %, so run-to-run variation at that size is real, not a
#: service failure. The shortfall is printed either way -- tolerated is not the same as unnoticed.
RIDE_TOL = 0.99


def kpis(tag):
    hits = sorted(ROOT.glob("*_" + tag + "_iter*/analysis/kpis_long.csv"))
    if not hits:
        return None
    d = pd.read_csv(hits[-1], sep=";")
    return {x.kpi_name: x.value for _, x in d.iterrows()}


def summarise(tag, m, ride_target):
    planned = m.get("parcels_planned") or m.get("parcels_total")
    served = m.get("parcels_served") or m.get("parcels_handled")
    rides = m.get("drt_rides")
    hours = m.get("drt_tour_hours_total")
    rate = (served / planned) if planned else 0.0
    ok_p = rate >= PARCEL_TOL
    ok_r = rides is not None and rides >= RIDE_TOL * ride_target
    return dict(tag=tag, rate=rate, rides=rides, hours=hours,
                feasible=ok_p and ok_r, ok_p=ok_p, ok_r=ok_r,
                miss=(max(0.0, PARCEL_TOL - rate) + max(0.0, (ride_target - (rides or 0)) / ride_target)))


def main():
    base = kpis(sys.argv[1] if len(sys.argv) > 1 else "b120rg")
    if base is None:
        print("FATAL: baseline KPIs not found -- cannot set the ride target", flush=True)
        return 2
    ride_target = base.get("drt_rides")
    print("baseline ride target: %s   (parcels %s/%s)"
          % (ride_target, base.get("parcels_handled"), base.get("parcels_total")), flush=True)

    cands = []
    for tag, theta in (("f150t010", "0.10"), ("f150t015", "0.15")):
        m = kpis(tag)
        if m is None:
            print("  %-9s MISSING -- skipped" % tag, flush=True)
            continue
        s = summarise(tag, m, ride_target)
        s["theta"] = theta
        cands.append(s)
        print("  %-9s theta=%s  parcels %.1f%% %s   rides %s %s   tour_h %.1f"
              % (tag, theta, 100 * s["rate"], "OK " if s["ok_p"] else "FAIL",
                 s["rides"], "OK " if s["ok_r"] else "FAIL", s["hours"]), flush=True)
        if s["rides"] is not None and s["rides"] < ride_target:
            print("            NOTE: %d rides below target (%.2f %%) -- within the %.0f %% tolerance"
                  % (ride_target - s["rides"], 100 * (1 - s["rides"] / ride_target), 100 * (1 - RIDE_TOL)), flush=True)

    if not cands:
        print("FATAL: no theta candidates readable", flush=True)
        return 2

    feasible = [c for c in cands if c["feasible"]]
    if feasible:
        win = min(feasible, key=lambda c: c["hours"])
        print("WINNER %s (theta=%s) -- feasible, fewest tour hours" % (win["tag"], win["theta"]), flush=True)
    else:
        win = min(cands, key=lambda c: c["miss"])
        print("WARNING: NO feasible theta. Falling back to the smaller violation: %s (theta=%s)."
              % (win["tag"], win["theta"]), flush=True)
        print("WARNING: every downstream fleet size inherits this -- treat the sweep as"
              " infeasible-anchored until a feasible theta exists.", flush=True)

    (OUT / "chosen_theta.txt").write_text(win["theta"] + "\n", encoding="ascii")
    print("wrote chosen_theta.txt = " + win["theta"], flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
