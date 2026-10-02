"""Compare the result files of two MATSim runs (jsprit cache gate, spec 2026-10-01 section 9.3).

Usage:
  python tools/compare_run_outputs.py --a DIR_A --run-a RUNID_A --b DIR_B --run-b RUNID_B
         [--routed-a FILE --routed-b FILE] [--modular]

Criterion (decided 2026-10-02 after the first gate run, spec section 9.3):
- Every MATSim output file is compared with its own run id replaced by "<RUN>": the DRT CSVs
  carry the run id in their first column, so two otherwise identical runs differ there.
- output_events.xml.gz is compared as a multiset of events per time step. With
  qsim.numberOfThreads > 1 the order of events WITHIN one time step changes from run to run,
  even between two fresh runs with the same seed; the events themselves do not. Verdict
  REORDERED = same events in every time step, different order in some.
- Everything else, and the routed carriers file (compared raw), must be byte-identical.

One ASCII line per file; exit code 0 only if every compared file exists in both runs and is
EQUAL (or REORDERED, for the events file only).
"""
import argparse
import gzip
import hashlib
import re
import sys
from pathlib import Path

EVENTS = "output_events.xml.gz"
COMMON = [EVENTS, "drt_vehicle_stats_drt.csv", "drt_customer_stats_drt.csv",
          "output_drt_legs_drt.csv", "modestats.csv"]
BASELINE_ONLY = ["output_carriers.xml.gz"]
MODULAR_ONLY = ["modular_tour_stats.csv"]
RUN = b"<RUN>"
TIME = re.compile(rb'time="([^"]+)"')
MOD = 1 << 160


def _read(path: Path) -> bytes:
    return gzip.open(path, "rb").read() if path.suffix == ".gz" else path.read_bytes()


def _event_steps(path: Path, run_id: str):
    """Per time step: (count, order-independent multiset hash, order-dependent digest)."""
    steps = {}
    rid = run_id.encode()
    with gzip.open(path, "rb") as f:
        for line in f:
            if b"<event " not in line:
                continue
            ev = line.strip().replace(rid, RUN)
            t = TIME.search(ev).group(1)
            h = int.from_bytes(hashlib.sha1(ev).digest(), "big")
            count, total, order = steps.get(t, (0, 0, hashlib.sha1()))
            order.update(ev + b"\n")
            steps[t] = (count + 1, (total + h) % MOD, order)
    return {t: (c, s, o.digest()) for t, (c, s, o) in steps.items()}


def events_verdict(a: Path, run_a: str, b: Path, run_b: str) -> str:
    sa, sb = _event_steps(a, run_a), _event_steps(b, run_b)
    if set(sa) != set(sb) or any(sa[t][:2] != sb[t][:2] for t in sa):
        return "DIFF"
    reordered = sum(1 for t in sa if sa[t][2] != sb[t][2])
    if reordered == 0:
        return "EQUAL"
    n = sum(v[0] for v in sa.values())
    return "REORDERED (%d events, %d of %d time steps in another order)" % (n, reordered, len(sa))


def verdict(name: str, a: Path, run_a: str, b: Path, run_b: str) -> str:
    if not a.is_file() or not b.is_file():
        return "MISSING"
    if name == EVENTS:
        return events_verdict(a, run_a, b, run_b)
    da, db = _read(a), _read(b)
    if run_a is not None:
        da, db = da.replace(run_a.encode(), RUN), db.replace(run_b.encode(), RUN)
    return "EQUAL" if da == db else "DIFF"


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--a", required=True)
    p.add_argument("--run-a", required=True)
    p.add_argument("--b", required=True)
    p.add_argument("--run-b", required=True)
    p.add_argument("--routed-a")
    p.add_argument("--routed-b")
    p.add_argument("--modular", action="store_true")
    args = p.parse_args(argv)

    names = COMMON + (MODULAR_ONLY if args.modular else BASELINE_ONLY)
    pairs = [(n, Path(args.a) / f"{args.run_a}.{n}", args.run_a, Path(args.b) / f"{args.run_b}.{n}", args.run_b)
             for n in names]
    if args.routed_a and args.routed_b:
        # raw: the routed carriers file must be byte-identical as it stands
        pairs.append(("lmd_carriers_routed.xml", Path(args.routed_a), None, Path(args.routed_b), None))

    bad = 0
    for name, fa, ra, fb, rb in pairs:
        v = verdict(name, fa, ra, fb, rb)
        print(f"{v:8s} {name}")
        bad += not (v == "EQUAL" or (name == EVENTS and v.startswith("REORDERED")))
    print("ALL EQUAL" if bad == 0 else f"{bad} file(s) not equal")
    return 0 if bad == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
