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
- `board/` — React app (web-artifacts-builder scaffold: React 18 + TS + Tailwind + shadcn, Recharts).
  Deliverable: `board/bundle.html` (self-contained, ~0.8 MB).

## Refresh (e.g. when the pending v3 runs land)

Still outstanding at the last refresh (2026-08-10): `170v3` / `270v3` / `330v3` (JVM crash,
redo armed on the sim-PC), `390v3` / `400v3` (were still running), `290v2` (finishing on the
dev-PC), and `70v2` (never redone). Collect the dashboard into
`Desktop\Sim_Results\0726\Run1\Dashboards\` keeping its **full original filename**, then:

```bash
# 1. drop the cap out of V3_MISSING (or into V2_CAPS) and bump EXPECTED_RUNS in the SAME edit
#    -- EXPECTED_RUNS is the deliberate cross-check; a stale value fails the run, by design
python extract_sweep.py
cp sweep_data.json board/src/data/sweep_data.json
cd board
pnpm exec parcel build index.html --dist-dir dist --no-source-maps
pnpm exec html-inline dist/index.html > bundle.html
```

(`node shot.cjs` renders headless screenshots via Edge for a quick visual check.)
