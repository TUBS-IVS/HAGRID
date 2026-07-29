# Hannover Capacity-Sweep Board — Design

**Date:** 2026-07-29
**Status:** Approved by user (design conversation 2026-07-29)
**Goal:** A presentable, interactive single-file HTML board ("Analyse-Explorer") over the
Hannover LMD capacity-sensitivity runs, built with the `web-artifacts-builder` skill
(React + shadcn, bundled to one `bundle.html`). Primary audience: the researcher; visual
quality good enough to show colleagues. This doubles as a test of the skill itself.

## 1. Data inventory (explicit run list — no globbing)

All inputs are the per-run Java LMD dashboards (`HAGRID_Dashboard_<runId>_iter150_jsprit1000.html`),
which embed a server-rendered `SUMMARY=[...]` JSON array (per provider, with per-vehicle
`vehDetails`). One extraction script reads exactly these files:

**Series v1 (old code, Feb–Apr 2026), 38 sweep points + 3 replicates:**
- `C:\Users\Hendrik Bimmermann\Desktop\Sim_Results\HAGRID_Dashboard_BASECASE_13052025_<cap>_iter150_jsprit1000.html`
  for cap = 30, 40, …, 400 (step 10; 38 files).
- Replicates (same code, reseeded tag): `120_v2` (Desktop), `300v2` (Desktop),
  `50v2_l` (repo root). Despite the "v2" in their tags these are **old-code** runs
  (file dates 2026-02-19, no unassigned KPI) → they enter as extra v1 points at
  caps 120, 300, 50. `300v2` exists on Desktop and in repo root; implementation
  verifies the copies are identical (checksum) and uses the Desktop copy.

**Series v2 (current code incl. CarrierServiceMerger split fix, 2026-07-23/27), 12 points:**
- `C:\Users\Hendrik Bimmermann\Desktop\Sim_Results\0726\Run1\Dashboards\HAGRID_Dashboard_BASECASE_13052025_<cap>v2_iter150_jsprit1000.html`
  for cap = 30, 40, 50, 60, 80, 90, 100, 110, 120, 130, 140, 150. (70v2 crashed; the gap
  stays visible until the manual re-run lands.)

**Excluded (user decision):** `30R1` (third code state: current code *before* the split fix),
`175`, `50v2_m`.

## 2. Extraction — `parcel-demand-2-matsim-pipeline/analysis/hannover-sweep/extract_sweep.py`

Python, stdlib + pandas. Slices the `SUMMARY=[…]` JSON out of each HTML (regex on the
assignment, `json.loads`). Per run it computes network-level values (summed over providers):

| KPI | Definition |
|---|---|
| `tour_km` | Σ provider `distKm` |
| `tour_h` | Σ provider `tourDurH` |
| `cost_eur` | Σ provider `cost` |
| `vehicles` | Σ provider `vehicles` |
| `parcels` | Σ provider `parcels` (context for the ratio) |
| `parcels_per_vehicle` | `parcels / vehicles` |
| `utilization` | Σ per-vehicle `parcels_i` / Σ per-vehicle `cap_i` (from `vehDetails`) |

**Tour-limit classification** (per tour = per `vehDetails` entry; one tour per vehicle with
`FleetSize.INFINITE` — the extractor asserts Σ `CARRIER_DETAIL.tours` ≈ vehicle count and
records the actual ratio):
- worktime-limited: `durH > 7.0` (MAXROUTEDURATION = 7 h; overtime beyond it exists and is
  penalised via `costOT`, so the class is non-empty)
- capacity-limited: `parcels > 0.9 * cap`
- Four disjoint classes: worktime-only / capacity-only / **both** / neither.
  Output: counts + shares (denominator = total tours of the run).

**Self-validation:** the extractor recomputes what the board's KPI tiles would show
(Σ vehicles etc.) and checks anchors from known runs (v1-30 ≈ 3206 vehicles, 30v2 ≈ 3204).
A missing/duplicate `SUMMARY`, an unparseable file, or two runs claiming the same
(series, cap, replicate-id) → loud failure with the file name.

**Output:**
- `sweep_data.json` (~80 kB): run meta (series, cap, tag, replicate flag) + absolute KPI
  values + limit-class counts/shares. Embedded into the React app as a module.
- `sweep_kpis.csv`: same numbers flat, for later paper use.

Normalisation is **not** stored: the board computes `value / value@cap30(same series) * 100`
at render time (v1 → v1-30 run, v2 → 30v2 run). One source of truth.

## 3. Board — single page, top to bottom

1. **Header** — title; badges: Region Hannover · demand 13.05.2025 · iter150/jsprit1000 ·
   v1: 38+3 replicates · v2: 12; caveat chip "1 run per capacity" with tooltip (no
   uncertainty bands possible; replicates only at v1 caps 50/120/300).
2. **Capacity profile** — capacity slider/select; the 6 KPIs as tiles; where a v2 run
   exists at that cap: v1 and v2 side by side + Δ%.
3. **Sweep chart (centrepiece)** — line chart over capacity; KPI selectable via chips;
   **absolute / % toggle** (each series normalised to its own c=30); v1 and v2 as two
   colours; replicate runs as individual scatter points, the line passing through the
   **mean** at replicated caps. Subtle background bands annotate the two regimes
   (capacity-wall ≲110, spatially/time-bound ≳190).
4. **Limit analysis** — 100 %-stacked area over capacity, 4 classes; toggle share/count;
   series switch v1/v2. Expected reading: capacity-limited dominates left, dies out
   rightwards, worktime-limited takes over.
5. **Old vs new** — Δ% (v2 vs v1, same cap) over caps 30–150 as bars for the selected KPI;
   the 70 gap stays visible.
6. **Data table** — all runs × KPIs, sortable, client-side CSV download.

## 4. Tech

- Install `web-artifacts-builder` from `anthropics/skills` (not yet installed locally);
  `init-artifact.sh` via Git Bash; Node v24 present.
- React 18 + TS + Tailwind + shadcn (skill scaffold); **Recharts** for charts.
- Colours: the validated HAGRID dataviz palette tokens from
  `parcel-demand-2-matsim-pipeline/analysis/kpi/render.py` (CAT slots, ink/grid tokens) —
  not Tailwind defaults. v1/v2 get two fixed categorical slots.
- Project lives in `parcel-demand-2-matsim-pipeline/analysis/hannover-sweep/`
  (`extract_sweep.py`, `board/` with the app, bundle output).
- Deliverable: one self-contained `bundle.html` (~1–2 MB).
- Update path (e.g. when 70v2 lands): re-run extractor, re-run bundle — two commands,
  documented in a README line.

## 5. Deliberately out of scope (lean)

Provider drill-down, maps, cost breakdown (`CARRIER_DETAIL` component costs), narrative
presentation layer for non-experts, auto-refresh/live data. The JSON keeps the raw
classification counts from day one so these extensions need no re-parse.

## 6. Testing

- Extractor: pytest with a small fixture HTML (known sums → exact expected KPIs and
  limit classes) + the real-board anchor checks above.
- Board: build must pass; visual check in the browser. No further test automation for a
  test board.

## 7. Known caveats (recorded, accepted)

- v1 vs v2 are different code states; they are never mixed into one curve — the code
  effect lives exclusively in the old-vs-new view and the paired tiles.
- One run per capacity (except v1 50/120/300) → no variance estimates.
- The boards' `vehDetails` reflect the DashboardGenerator's low-utilisation filter as
  shipped in each run; both series were produced by the same generator logic, so the
  comparison is internally consistent.
