"""Measure all Task-5 runs; writes out/<run>.csv and out/summary.csv (';'-separated).

Data are read in place (gitignored, absent in worktrees): by default from this checkout's
hagrid/simulation/{hagrid-matsim-output,input} (runs root: HAGRID_OUTPUT_ROOT if set); from a
worktree pass --runs-root / --inputs-root pointing at the checkout that holds the data.
Lausitz demand shapefile per run was identified by exact per-provider parcel match:
band arms = level_ctrsnap_{central,low,high} clipped to drt-service-area.shp,
DRT_BASELINE arms = level_central (== top-level demand) clipped to drt-service-area.shp.
"""
import argparse
import glob
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import audit_delivery_rate as a  # noqa: E402

#: <repo>/hagrid/simulation, derived from this file's place in the repo (analysis/common/delivery-rate/)
MODULE = Path(__file__).resolve().parents[3] / "hagrid" / "simulation"
HERE = Path(__file__).parent / "out"


def default_runs_root():
    """HAGRID_OUTPUT_ROOT (same variable the simulation uses for its MATSim tree), else the module's."""
    env = os.environ.get("HAGRID_OUTPUT_ROOT", "").strip()
    return Path(env) if env else MODULE / "hagrid-matsim-output"


def default_inputs_root():
    return MODULE / "input"


def demand(inp, level):
    return glob.glob(str(inp / "demand" / level / "*.shp"))[0]


HANNOVER = [f"BASECASE_13052025_{n}v2_iter150_jsprit1000" for n in (160, 190, 230, 290)]
LAUSITZ = {
    "LMD_BASELINE_13052025_bandz_central_iter0_jsprit100": "level_ctrsnap_central",
    "LMD_BASELINE_13052025_bandz_low_iter0_jsprit100": "level_ctrsnap_low",
    "LMD_BASELINE_13052025_bandz_high_iter0_jsprit100": "level_ctrsnap_high",
    "DRT_BASELINE_13052025_b120rgs_iter250_jsprit100": "level_central",
}
for s in (1338, 1339, 1340, 1341):
    LAUSITZ[f"DRT_BASELINE_13052025_b120rgs_s{s}_iter250_jsprit100"] = "level_central"


def carriers_file(runs_root, run):
    g = glob.glob(str(Path(runs_root) / run / "*output_carriers.xml.gz"))
    return g[0] if g else None


def parse_args(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--runs-root", type=Path, default=None,
                    help="dir holding the MATSim run dirs (default: HAGRID_OUTPUT_ROOT, else "
                         "<repo>/hagrid/simulation/hagrid-matsim-output)")
    ap.add_argument("--inputs-root", type=Path, default=None,
                    help="input root holding lausitz/ (default: <repo>/hagrid/simulation/input)")
    args = ap.parse_args(argv)
    if args.runs_root is None:
        args.runs_root = default_runs_root()
    if args.inputs_root is None:
        args.inputs_root = default_inputs_root()
    return args


def main(argv=None):
    args = parse_args(argv)
    out_root = args.runs_root
    inp = args.inputs_root / "lausitz"
    area = inp / "drt" / "drt-service-area.shp"
    print("runs root:", out_root, "| inputs root:", args.inputs_root)
    HERE.mkdir(exist_ok=True)
    summary = []
    for run in HANNOVER:
        f = carriers_file(out_root, run)
        if not f:
            print("SKIP (no carriers file)", run)
            continue
        rows = a.audit_hannover(f)
        a.write_csv(rows, HERE / f"{run}.csv")
        summary += [dict(r, run=run, area="hannover") for r in rows]
    for run, lvl in LAUSITZ.items():
        f = carriers_file(out_root, run)
        if not f:
            print("SKIP (no carriers file)", run)
            continue
        shp = demand(inp, lvl)
        stats = a.read_demand_shp(shp, str(area))
        dl = a.deliveries_from_shp(shp, str(area))
        replay = {p: a.replay_missed(p, d)[0] for p, d in dl.items() if d}
        rows = a.audit_lausitz(f, stats, replay)
        for r in rows:
            assert r["shp_parcels"] == r["parcels"], (run, r["provider"], r["shp_parcels"], r["parcels"])
        a.write_csv(rows, HERE / f"{run}.csv")
        summary += [dict(r, run=run, area="lausitz") for r in rows]
    cols = ["run", "area"] + a.COLUMNS
    with open(HERE / "summary.csv", "w", encoding="utf-8", newline="") as fh:
        fh.write(";".join(cols) + "\n")
        for r in summary:
            fh.write(";".join(f"{r[c]:.4f}" if isinstance(r[c], float) else str(r[c]) for c in cols) + "\n")
    print("rows:", len(summary))


if __name__ == "__main__":
    main()
