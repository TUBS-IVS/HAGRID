"""Byte-compare the result files of two MATSim runs (jsprit cache gate, spec 2026-10-01 section 9.3).

Usage:
  python tools/compare_run_outputs.py --a DIR_A --run-a RUNID_A --b DIR_B --run-b RUNID_B
         [--routed-a FILE --routed-b FILE] [--modular]

Gzipped files are compared decompressed, in chunks. One ASCII line per file; exit code 0 only if
every compared file exists in both runs and is byte-identical.
"""
import argparse
import gzip
import sys
from pathlib import Path

COMMON = ["output_events.xml.gz", "drt_vehicle_stats_drt.csv", "drt_customer_stats_drt.csv",
          "output_drt_legs_drt.csv", "modestats.csv"]
BASELINE_ONLY = ["output_carriers.xml.gz"]
MODULAR_ONLY = ["modular_tour_stats.csv"]
CHUNK = 1 << 20


def _open(path: Path):
    return gzip.open(path, "rb") if path.suffix == ".gz" else open(path, "rb")


def same_bytes(a: Path, b: Path) -> bool:
    with _open(a) as fa, _open(b) as fb:
        while True:
            ca, cb = fa.read(CHUNK), fb.read(CHUNK)
            if ca != cb:
                return False
            if not ca:
                return True


def verdict(a: Path, b: Path) -> str:
    if not a.is_file() or not b.is_file():
        return "MISSING"
    return "EQUAL" if same_bytes(a, b) else "DIFF"


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
    pairs = [(n, Path(args.a) / f"{args.run_a}.{n}", Path(args.b) / f"{args.run_b}.{n}") for n in names]
    if args.routed_a and args.routed_b:
        pairs.append(("lmd_carriers_routed.xml", Path(args.routed_a), Path(args.routed_b)))

    bad = 0
    for name, fa, fb in pairs:
        v = verdict(fa, fb)
        print(f"{v:8s} {name}")
        bad += v != "EQUAL"
    print("ALL EQUAL" if bad == 0 else f"{bad} file(s) not equal")
    return 0 if bad == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
