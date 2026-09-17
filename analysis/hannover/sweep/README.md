# Hannover Capacity-Sweep Board

Interactive single-file dashboard over the Hannover LMD capacity-sensitivity runs
(spec: `docs/superpowers/specs/2026-07-29-hannover-sweep-board-design.md`).

- `extract_sweep.py` — parses the per-run Java LMD dashboard HTMLs (explicit run lists,
  never globbed: `V1_CAPS` / `V2_CAPS` / `V3_CAPS`, currently 39 v1 + 25 v2 + 33 v3 = 97 runs)
  into `sweep_data.json` + `sweep_kpis.csv`. Test: `python -m pytest test_extract_sweep.py`.

  **Three series, and only one of the three comparisons is a code effect.** v1 is the old
  code. v2 and v3 both run the merger-split fix on a Hannover path verified identical
  between them, so a v3−v2 difference at the same capacity is pure reseed spread (the tag
  is part of the runId; `runId.hashCode()` seeds the demand layer and `CarrierVehicleFactory`).
  That difference is the board's only uncertainty estimate — measured 0.0–0.1 % at cap 30.
- `provenance/` — what each sim-PC series actually ran. The v2/v3/v4 comparison is only a
  reseed spread as long as the model code was identical, so the working-tree diff behind a
  series is part of its result. `provenance/v4-sim-working-tree.patch` holds v4's (verified to
  apply cleanly to `019fd5f`); the sim-PC tree itself was reset on 2026-08-25.
- `board/` — React app (web-artifacts-builder scaffold: React 18 + TS + Tailwind + shadcn, Recharts).
  Deliverable: `board/bundle.html` (self-contained, ~0.8 MB).

## Refresh (e.g. when the pending v3 runs land)

Still outstanding at the last refresh (2026-08-12): **v2 at caps 320–400** (9 runs, being
produced on the sim-PC — a `java` was live at the time of writing, so this board is a
snapshot of a moving arm). **v3 is complete** (38/38: the three ZGC casualties 170/270/330
were redone on G1 and sit within the reseed band, 390/400 finished). Collect the dashboard
into `Desktop\Sim_Results\0726\Run1\Dashboards\` keeping its **full original filename**,
then:

```bash
# 1. move the cap from V2_MISSING into V2_CAPS and bump EXPECTED_RUNS in the SAME edit
#    -- both are cross-checked at import time: V2_CAPS + V2_MISSING must partition the
#    full 30..400 grid, and the list lengths must match EXPECTED_RUNS. A half-edit fails
#    the run, by design
python extract_sweep.py
cp sweep_data.json board/src/data/sweep_data.json
cd board
pnpm exec parcel build index.html --dist-dir dist --no-source-maps
pnpm exec html-inline dist/index.html > bundle.html
```

(`node shot.cjs` renders headless screenshots via Edge for a quick visual check.)
