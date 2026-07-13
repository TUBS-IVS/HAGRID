# Run-Dashboard v2 — Legacy Scope in the 1e Frame — Design Spec

**Date:** 2026-07-10
**Status:** Approved by user (structure, maps, auto-trigger, LMD depth, architecture, design sections all confirmed 2026-07-10)
**Predecessor:** `docs/superpowers/plans/2026-07-06-1e-kpi-csv-and-dashboard.md` (Task 1e, executed 2026-07-09)

## 1. Problem

The 1e dashboard deliberately descoped most of the legacy dashboards' content (Decision D4: no maps, no per-vehicle geometry, no event-detail sections) to hit the < 1 MB budget. The result restored performance but lost analysis scope the user relies on:

- **Legacy DRT dashboard** (`analysis/drt-headline/build_drt_dashboard.py`, ~25 MB Plotly): 22 KPI tiles, 14 charts (incl. convergence-over-iterations, distributions, occupancy decomposition, per-vehicle service time), and the per-vehicle tour map with heatmaps.
- **Legacy LMD dashboard** (`src/main/java/hagrid/analysis/DashboardGenerator.java`, ~3 MB Chart.js + Leaflet): 20 KPI tiles, ~30 charts (nearly all per-provider / per-vehicle-type), 3 interactive tables, 2 Leaflet maps.
- **1e dashboard** (`analysis/kpi/`): ~10 tiles, 5 charts, KPI table. Freight side especially thin (4 tiles + 1 chart, zero provider breakdown — deferred in 1e Task 7).

Goal: restore the legacy scope inside the new (1e) design system, with good performance, generated automatically after every run at a defined location.

## 2. User Decisions (all confirmed 2026-07-10)

- **U1 — Structure:** ONE dashboard per run with a tab bar **DRT | LMD** (not two files). Only tabs with data appear (pure LMD run → LMD tab only). System KPIs (modal split etc.) stay on the DRT tab as today.
- **U2 — Maps:** included, but rendered with **Leaflet + canvas** (like the Java LMD dashboard) instead of inline Plotly. **The real acceptance criterion is browser usability, not a byte count** (confirmed 2026-07-10: the legacy 25 MB Plotly dashboard was the problem because it was sluggish/unresponsive and sometimes failed to load in-browser — file size is a proxy for that, not the goal itself). File size is measured and tracked as a diagnostic, but the actual gate is: opens and is interactive within a few seconds on a normal dev machine. A rough ~5 MB working assumption replaces 1e's < 1 MB for the run page, but see §3.5 (Map size — measure before fixing a budget) for how the real number gets set. Comparison dashboard stays map-free at < 3 MB (unaffected, no maps in scope there).
- **U3 — Auto-trigger:** Java invokes the Python builder as a subprocess at the end of every run, failure-tolerant (a dashboard failure must never kill a multi-hour run). New scenario option `kpiDashboard`, default **true**. Legacy `writeDashboard` (Java dashboard) unchanged, default false, considered deprecated.
- **U4 — LMD depth:** full legacy scope — all provider/vehicle-type charts, VRP efficiency table, provider summary with per-vehicle drilldown, cost components, scoring convergence.
- **U5 — Architecture:** everything in Python (extend `analysis/kpi/`). No Java→JSON data bridge; the provider classification logic is ported from `DashboardGenerator.java` into Python.
- **U6 — Design:** the 1e design system is kept and is the single visual language. Legacy content is translated into it: donuts → horizontal stacked bars, radar/polar → bars, dual-axis → two separate charts. All 1e dataviz rules continue to apply.

## 3. Architecture

The 1e principle stands: **the canonical CSVs are the source for everything except map geometry.** New data lands in *additional* CSV files with their own schemas; the 1e long-format schema (`run_id;study_area;scenario;operation_mode;kpi_group;kpi_name;value;unit;source`) is never changed — nothing existing breaks, `build_comparison.py` keeps working unmodified against `kpis_long.csv`.

### 3.1 New data files (all in `<run>/analysis/`, `;`-delimited, dot decimals, UTF-8)

| File | Schema | Content |
|---|---|---|
| `kpis_provider.csv` | `run_id;provider;kpi_name;value;unit;source` | per-provider freight KPIs (parcels total/missed/unassigned, delivery rate, vehicles, tours, km, tour hours, cost components: fixed/dist/time/total, avg load factor, stops, stops/h, stops/km, parcels/km, cost/parcel) + per-vehicle-type rows (`provider` column carries `type:<vehicleType>` prefix rows) |
| `kpi_iterations.csv` | `run_id;series;iteration;value;unit` | convergence: rides, rejection rate, wait mean/p95 (from `drt_customer_stats`, all rows), modal shares per mode (from `modestats`, all rows), carrier scores executed/worst/avg/best (from `carrier_scores.txt`, graceful skip if absent) |
| `kpi_distributions.csv` | `run_id;series;bin_lo;bin_hi;value;unit` | binned histograms computed in Python: DRT wait time (60 s bins), DRT tour distance & active tour duration per vehicle, LMD tour distance (10 km bins) & duration (0.5 h bins); occupancy decomposition (per occupancy level 0..cap: share of km / time / segments — `series` = `occ_km`/`occ_time`/`occ_segments`, `bin_lo` = occupancy level) |
| `kpi_timeseries.csv` | unchanged schema, **new series** | `freight_parcels_h_<provider>` (event↔carrier-plan join), `freight_active_vehicles_<provider>` (5-min sampling), `freight_depot_departures` / `freight_depot_arrivals`, `drt_feeder_trips`, `drt_requests_submitted` |
| `map_data.json` | internal (not canonical) | compact Leaflet layer payloads (see 3.3); regenerated with the dashboard, embedded inline, also left on disk for debugging |

### 3.2 New/extended Python modules (`analysis/kpi/`)

- **`extract_freight_provider.py` (NEW):** provider + vehicle-type classification ported from `DashboardGenerator.java` (ID keyword matching: `_CEP_Vehicle_`, `ct_cep_size*`, `_cargoBike_`, `_Supply_Vehicle_`, `freight_` …). Aggregates `TimeDistance_perCarrier.tsv`, `TimeDistance_perVehicle.tsv`, carriers XML (services, `capacityDemand`, carrier attributes), vehicle-types XML (capacities, fixed costs). Known 1e caveats carry over: HAGRID carriers are MATSim *services* → `Load_perVehicle.tsv` is header-only on real runs (use the documented fallbacks); v1 uses raw `missedParcels` sums (legacy Java scales by a non-exclusive-tour ratio — difference documented, accepted).
- **`extract_iterations.py` (NEW):** convergence series (see table above).
- **`distributions.py` (NEW):** histogram binning; consumes `output_drt_legs`, `drt_service_time.reconstruct` results (occupancy segments, per-vehicle km/duration), and the freight events cache.
- **`events_cache.py` (EXTEND):** a **single sequential pass** over `output_events.xml.gz` builds both filtered line-caches (DRT-relevant lines and freight-relevant lines: service start/end, link-enter for freight vehicles) in one read — never two separate passes over the same gzip. The freight cache additionally captures departures/arrivals per vehicle and link-enter sequences needed for tour polylines and the parcels/h join; the DRT cache already feeds `drt_service_time.reconstruct`. (Confirmed 2026-07-10: no LMD-only/DRT-only runs are planned, so both caches are always needed together — a combined pass has no downside.)
  - **Forward-compat constraint (confirmed 2026-07-10):** 1c (Shared-Use cargo-hitching) and 1d (Modular) introduce scenarios where ONE vehicle carries both passengers and parcels in the same tour. The two line-caches (and the DRT/freight vehicle-ID classification in `extract_freight_provider.py`) must therefore treat cache/role membership as **non-exclusive** — a vehicle ID can and will appear in both the DRT-relevant and freight-relevant filtered caches simultaneously. The single-pass filter must not assume "this line belongs to exactly one cache" or de-duplicate across caches; each cache's inclusion test is independent (a DRT-pattern match and a freight-pattern match on the same event line both fire if both patterns match).
- **`timeseries.py` (EXTEND):** new series listed in 3.1.
- **`maps.py` (NEW):** builds Leaflet layers from events caches + `output_network.xml.gz` + `drt-service-area.shp` + `lmd-depots.csv` + rail schedule. Layers — DRT: service area, depots, rail stops (fed/unfed), per-vehicle tour polylines colored by occupancy level, numbered PU/DO markers for the selected vehicle, PU/DO heatmaps. LMD: tour polylines (color by provider/carrier), service stop clusters with popups, link heatmap. Size control: coordinates rounded to 5 decimals, polyline simplification (drop collinear intermediate vertices; if still over budget, Douglas-Peucker), no background network layer (the base map tiles provide context — drops the Java dashboard's 50k-link background layer).
- **`render.py` (EXTEND):** tab infrastructure (reuse the comparison page's tab mechanism); section layouts per §4; vendored **Leaflet 1.9.4 + leaflet.heat + markercluster** inline next to Chart.js (`vendor/`); map tiles (CARTO dark / positron per theme) remain the only online dependency, exactly like the legacy dashboards.
- **`build_kpis.py` (EXTEND):** orchestrates the new extractors; `--no-events` still works and then simply omits event-dependent sections (service time, maps, distributions, provider hourly series) — CSVs that don't need events are always written.

### 3.3 Rendering (per-run page)

Tab bar: **DRT | LMD**. Sections translated from the legacy dashboards into the 1e design system:

**DRT tab** (from `build_drt_dashboard.py`):
1. Headline tiles — the full 22-tile set (incl. service time active/shift, utilization by-trips/by-time, mean pax aboard, detour factor, tour/drive/wait/dwell totals, cost bottom-up placeholder + Currie/Fournier benchmark, feeder vs. solo, veh-km with empty ratio, person-km). All with hover tooltips.
2. Map (Leaflet): tours by occupancy, depots, rail stops, heatmap toggles, vehicle dropdown.
3. Daily profile: demand/h (served bars + submitted line as **two charts**, no dual axis), rejections/h, mean wait/h, feeder trips/h (absolute + share toggle).
4. Distributions: wait histogram (median/mean/P95 markers), tour distance & duration histograms.
5. Occupancy & modal split: occupancy decomposition (100 % stacked horizontal, 3 rows), modal split (1e stacked bar, not donut).
6. Convergence: rides & rejection (two charts), wait mean/P95, modal shares over iterations.
7. Service time detail: per-vehicle occupied-time sorted bar.

**LMD tab** (from `DashboardGenerator.java`):
1. Headline tiles — the full 20-tile set (active vehicles, carriers split, fleet mix counts, parcels, stops, utilization, delivery rate, unassigned, avg tour length/speed, cost, distance, durations).
2. Map (Leaflet): tours / stops / heatmap modes, provider & carrier & vehicle filters.
3. Provider analytics: parcels & vehicles, utilization by provider, cost by provider + cost components (stacked), stops/h, parcels/km, time split travel-vs-service, avg tour distance & stops.
4. Vehicle-type analytics: distance by type, load factor by type, km & stops per tour by type (all bars — no polar/radar).
5. Tour structure: distance + duration histograms.
6. Hourly: departures & service starts/h, parcels/h by provider (stacked), active vehicles over time by provider, depot departures/arrivals.
7. Scoring: convergence line, final-iteration distribution, breakdown by provider (stacked bar — not donut).
8. Operational scatter: utilization vs. tour distance, parcels vs. tour duration (Chart.js scatter, per vehicle).
9. Tables: VRP efficiency (per provider), provider summary with per-vehicle drilldown rows, low-utilization exclusion notice (port the < 5 % filter incl. proportional cost re-allocation).

Dropped from legacy (deliberate): the Java dashboard's link-volume map + link-traffic charts (network diagnosis, not KPI analysis — stays available via `writeDashboard=true`) and the Plotly per-vehicle tours standalone page.

**Comparison dashboard:** structurally unchanged (headline bars, timeseries overlays, KPI table). Its per-run tabs reuse the extended tile sets + core charts but exclude maps and distributions (stays < 3 MB).

### 3.4 Auto-trigger (Java)

`SimulationRunnerUtils`: immediately after `writeRunMetadataSafely(...)`, if scenario option `kpiDashboard` (new, default **true**) is set, run
`python -u <repo>/parcel-demand-2-matsim-pipeline/analysis/kpi/build_kpis.py --run-dir <matsimOutputDir>`
as a subprocess with: try/catch-all, a generous timeout (30 min — events parsing on big runs takes minutes), stdout/stderr routed to the run log, WARN + the exact manual command on any failure. Python resolution: `python` from `PATH` (Windows dev machine reality); if spawn fails → WARN with manual command, run continues. `SimulationBatGenerator` needs no change (option defaults to true).

**Confirmed 2026-07-10 (blocking, not detached):** the subprocess call is synchronous — the Java process (and whatever waits on it, e.g. an overnight batch script) does not consider the run "done" until the dashboard build finishes or times out. Rationale: a detached/fire-and-forget build has no reliable completion signal and could silently never run if the machine sleeps or the parent process exits right after MATSim finishes. Consequence: the existing operational rule "don't let the machine sleep until the run is done" (`feedback_drt_runs_operational` memory) now covers the dashboard build too, not just the MATSim iterations — a multi-hour run's true end moves ~minutes later.

### 3.5 Map size — measure before fixing a budget

The real acceptance criterion is **browser usability** (opens and is interactively responsive within a few seconds on a normal dev machine), not a specific byte count — the legacy 25 MB Plotly dashboard's actual problem was sluggishness/failed loads, and file size was only ever a proxy for that. Process:

1. Implement the Leaflet map layers per §3.3 with the stated simplification (5-decimal coordinate rounding, collinear-vertex dropping, Douglas-Peucker fallback) but no fixed byte target yet.
2. Render against the real `married250` run (250 DRT vehicles + LMD carriers — the worst case we have, and representative of the future shared-vehicle 1c/1d scenarios per the non-exclusive-cache constraint above).
3. Measure actual file size AND actual browser load/interaction time (open in a real browser, note time-to-interactive, check the vehicle dropdown / heatmap toggle responsiveness with all 250 entries).
4. If it's fast and responsive: record the measured size as the new documented budget (whatever it is — could be 5, 8, or 12 MB) and assert that number (not a guessed one) in tests.
5. If it's sluggish: apply the same lever the legacy `build_dashboard.py` already used successfully — downsample PU/DO points (its `N_SAMPLE=3000` precedent) and/or lazy-load per-vehicle polylines (only inject the selected vehicle's geometry into the page, fetch others on dropdown change) — before reaching for a harder cut like dropping heatmaps.

This step becomes an early implementation-plan task (spike), not a design assumption baked in now.

## 4. Constraints (carried over from 1e + new)

- Never edit the legacy dashboards (`DashboardGenerator.java`, the three `drt-headline` scripts) — they stay as deprecated fallbacks until v2 is validated on a real run.
- 1e long-CSV schema frozen; new data = new files, never new columns there.
- ASCII-only `print()` (cp1252), `python -u`.
- Dataviz rules: no dual axes, no pies/donuts/radar/polar, fixed categorical slots (color follows scenario/provider entity), sequential single-hue for magnitudes, legend ≥ 2 series, ink-colored text, German number formatting presentation-only. Light + dark theme.
- Provider colors: fixed provider→slot map (stable across runs and between charts and map).
- No `.gitignore` changes; explicit `git add` lists; branch `hendrik`; no master merge.
- Budgets as test-asserted acceptance criteria: per-run page **≤ 5 MB** (real married250 fixture-scale check), comparison < 3 MB. Page must render offline except map tiles.
- Existing runs stay analyzable (legacy dir-name fallback, graceful skips for missing files: `carrier_scores.txt`, sharing metrics, rejections CSV).

## 5. Validation

1. TDD throughout (pytest fixtures: mini carriers XML with 2 providers + supply, mini events, verbatim heads of real married250 outputs; JUnit for the Java trigger).
2. Final validation against the real `married250` run: side-by-side number check vs. BOTH legacy dashboards; every intentional deviation (missedParcels scaling, event-reconstruction ~3 % low for map-derived values, dropped link-volume map) listed in the run report.
3. Size measurement of the real dashboards recorded (per-run + comparison).

## 6. Out of scope

- Refining the placeholder cost model (`economics.py`) — separate task, still open from 1e.
- Retiring/deleting the legacy dashboards — only after v2 is validated.
- 1c/1d-specific KPIs (arrive via the 1e extractor contract as before).
- Link-volume map / link-traffic charts (available via the deprecated Java dashboard if ever needed).
