# 1d Paper-Readiness Fixwave Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the [H]-findings of the 2026-07-29 paper-readiness review (δ-censoring, contamination fix wave 2) plus the cheap [M]/[L] items (test hardening, paper exports, one-liners, legacy-dashboard removal), find-and-documented in METHODS-LOG §2.16–§2.23 and BACKLOG `[H]` 1d.

**Architecture:** Java side: thread three plan-time counters (demand / jsprit-unassigned / missed-overlay) plus two derived stats (max parcels per tour, peak concurrent swaps) from the routed carriers file through `ModularDispatchModule` into `ModularKpiHandler`'s CSV (append-only contract). Python side: decouple the contamination marker from the events path, emit mechanically corrected `*_pax` companion rows, harden the reconstruction against the two untested boundary shapes, and surface everything on the rendered pages (banner precedent from Shared-Use). Docs close the loop.

**Tech Stack:** Java 17 / MATSim 2025.0 (JUnit 5 + AssertJ), Python 3 / pandas (pytest).

**User decisions baked in (2026-07-29):** δ-censoring = count + warn, never abort. Contaminated KPIs = banner + automatic conversion (plus a new `[M]` backlog item "KPI-Landschaft konsolidieren"). Delivery windows: 1d stays 07:30–21:00; **1c will be raised to 21:00 later** (new `[H]` backlog item + METHODS-LOG decision — NO code change to 1c in this wave). Legacy drt-headline dashboards: delete, after verifying the Hannover-LMD legacy pipeline does not use them.

## Global Constraints

- **Control arm inviolable:** `ModularControlArmTest` (bit-identical θ=1.0 pax outputs, both compositions) must pass UNCHANGED. No edit in this plan may alter pax-side behaviour.
- **CSV append-only contract:** `modular_tour_stats.csv` metric names and their ORDER up to `tours_rejected_at_splice` are published API; new metrics are APPENDED after it, never inserted (ModularKpiHandler.java:324-326 comment).
- **Loud but NON-FATAL:** the KPI handler logs accounting anomalies and still writes its CSV; it never throws mid-run (class javadoc contract). Startup-time validation (before `controler.run()`) MAY warn loudly but must not abort for the δ-censoring counter (user decision: count + warn).
- **No CarrierModule on 1d** (design §3.4): `parcels_unassigned` from `extract_freight.py` stays LMD-only; the modular chain gets its OWN counter.
- **Git hygiene:** per-path `git add` only — NEVER `git add -A`/`-u`. Never touch `run_lmd_band.ps1`, `track_sweep.ps1`, `docs/obsidian/**` (parallel workstream).
- **Windows:** `python -u` for test runs; ASCII-only in `print()`/log output of Python code.
- **Tests foreground only**, no background jobs; Java: `mvn -pl parcel-demand-2-matsim-pipeline test -Dtest=<Class>` (timeout generous, ~10 min for e2e classes); Python: `python -u -m pytest analysis/kpi/tests/<file> -v` from `parcel-demand-2-matsim-pipeline/`.
- Task-type names, event attribute names and `metric;value` semicolon format are cross-language contracts — change NEITHER side unilaterally.
- Plan test sketches are DIRECTION, not gospel (project rule since Task 2 of the 1d build): before trusting a sketch, verify it discriminates — it must fail on the pre-fix code for the reason it names.

---

### Task 1: Java accounting exports — δ-censoring counter + plan-time stats

**Files:**
- Create: `parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/modular/ModularPlanStats.java`
- Modify: `parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/modular/ModularTourConverter.java`
- Modify: `parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/modular/ModularDispatchModule.java` (ctor + KPI-handler binding)
- Modify: `parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/modular/ModularKpiHandler.java`
- Modify: `parcel-demand-2-matsim-pipeline/src/main/java/hagrid/simulation/SimulationRunnerUtils.java:385-403` (modular wiring branch)
- Test: `parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/modular/ModularTourConverterTest.java`, `ModularKpiHandlerTest.java`

**Interfaces:**
- Produces: `record ModularPlanStats(long parcelsDemand, long parcelsUnassignedJsprit, long parcelsMissedOverlay, int maxParcelsPerTour, Map<String, Id<Link>> depotByTourId)` — consumed by Task 1's own module/handler changes; the five new CSV metrics (exact names below) are consumed by Task 2.
- Produces CSV metrics APPENDED in this exact order after `tours_rejected_at_splice`:
  `parcels_demand`, `parcels_unassigned_jsprit`, `parcels_missed_overlay`, `max_parcels_per_tour`, `peak_concurrent_swaps`.
- Produces: `ModularTourEvent.dispatched(...)` gains two attributes `plannedDurationS`, `routedDurationS` (additive — regex-based Python consumers unaffected).

**Background (verified anchors):**
- `HAGRIDRouterUtils.recordUnassignedJobs` (`:402-418`) writes per-carrier int attributes `unassignedJobs`, `unassignedParcels`.
- `LmdCarrierBuilder.buildCore` (`:201-204`) writes per-carrier int attributes `numberOfParcels` (= total demand incl. unassigned + missed) and `missedParcels`.
- `SimulationRunnerUtils` modular branch (`:385-403`) reads the routed carriers via `ModularTourConverter.read(...)`, converts via `.convert(...)`, then constructs `ModularDispatchModule(drtCfg, tours, cfg.getIdleThreshold())`.
- Conservation identity 0 (new): `parcels_demand == parcels_planned + parcels_unassigned_jsprit` — `numberOfParcels` counts every parcel the demand file put on this carrier; jsprit either tours it (→ `parcels_planned` via tour stops) or leaves it unassigned. `missedParcels` is a statistical overlay and does NOT reduce either side.

**Steps:**

- [ ] **Step 1: failing test — converter exposes plan stats.** In `ModularTourConverterTest`, build a `Carriers` fixture with two carriers carrying attributes (`numberOfParcels`=10/5, `unassignedParcels`=2/0, `missedParcels`=1/0) and assert:

```java
ModularPlanStats stats = ModularTourConverter.planStats(carriers, tours);
assertThat(stats.parcelsDemand()).isEqualTo(15);
assertThat(stats.parcelsUnassignedJsprit()).isEqualTo(2);
assertThat(stats.parcelsMissedOverlay()).isEqualTo(1);
assertThat(stats.maxParcelsPerTour())
        .isEqualTo(tours.stream().mapToInt(ModularFreightTour::totalParcels).max().orElse(0));
assertThat(stats.depotByTourId()).containsKeys(tours.get(0).tourId());
```

Also one test: a carrier with NO attributes contributes 0 to every sum (defensive `getAttribute(...) == null` handling, mirroring `extract_freight`'s tolerance) — and a WARN is NOT required for that case.

- [ ] **Step 2: run, verify FAIL** (`planStats` undefined).
- [ ] **Step 3: implement.** `ModularPlanStats` record; `ModularTourConverter.planStats(Carriers, List<ModularFreightTour>)` static method: sums the three attributes over carriers (null-safe, `(Integer) attrs.getAttribute("numberOfParcels")` etc.), computes `maxParcelsPerTour` and `depotByTourId` from the tour list. Add identity-0 check INSIDE `planStats`: if `parcelsDemand != plannedFromTours + parcelsUnassignedJsprit`, `LOG.error(...)` naming all three numbers (loud, non-fatal — an empty-tour skip in `convert` can legitimately cause this and already WARNs). If `parcelsUnassignedJsprit > 0`, `LOG.warn("jsprit left {} parcels UNPLANNED under the current tour cap - delta_parcels is measured against the post-assignment base; see METHODS-LOG 2.16", ...)`.
- [ ] **Step 4: run converter test, verify PASS.**
- [ ] **Step 5: failing test — KPI handler CSV appends.** Extend `ModularKpiHandlerTest`: construct the handler with a `ModularPlanStats` (new ctor param, see Step 7) and, after a synthetic event stream with two SWAP_DONE events on the same depot at t=30000 and t=30200 (overlap: |30200−30000| < 420) plus one at a second depot, assert the CSV tail contains, in order:

```java
assertThat(lines).containsSubsequence(
        "tours_rejected_at_splice;0",
        "parcels_demand;15",
        "parcels_unassigned_jsprit;2",
        "parcels_missed_overlay;1",
        "max_parcels_per_tour;8",
        "peak_concurrent_swaps;2");
```

Peak-concurrent definition: swap k at depot d occupies `[tk − RETOOLING_S, tk]` (SWAP_DONE fires at swap END); `peak_concurrent_swaps` = max over depots of the max overlap count (classic sweep-line: sort interval starts/ends, running counter). Assert also that the pre-existing 21 metric lines are byte-identical to before (append-only pin).
- [ ] **Step 6: run, verify FAIL.**
- [ ] **Step 7: implement.** `ModularKpiHandler` ctor gains `ModularPlanStats planStats` (module passes it; keep `@Inject` off the new ctor if it breaks Guice — bind via provider in `ModularDispatchModule.install()` instead: `bind(ModularKpiHandler.class).toProvider(() -> new ModularKpiHandler(controlerIOProvider.get(), planStats)).asEagerSingleton()` — the module already holds the stats). SWAP_DONE events must know their depot: the handler already receives `ModularTourEvent`s carrying `tourId` — resolve depot via `planStats.depotByTourId()`. Collect swap times per depot in `notifyShutdown` from the accumulated per-tour data — NOTE: `TourStat` only counts swaps; add `List<Double> swapTimes` to `TourStat` (appended in the SWAP_DONE case). Emit the five appended lines.
- [ ] **Step 8: DISPATCHED event attributes.** `ModularTourEvent.dispatched(...)` gains `plannedDurationS` (from `tour.plannedDuration()`) and `routedDurationS` (from the splicer's routed excursion: `ScheduledExcursion` gains `double routedDurationS` = swap-back end − dispatch `now`; `ModularTourScheduler` already computes the completion time for the envelope check — reuse that exact variable). Extend `getAttributes()`. Update `ModularTourDispatcher`'s emission call. Adjust the existing event/dispatcher tests for the new signature (they pin attribute maps — extend the expected maps).
- [ ] **Step 9: wire the runner.** `SimulationRunnerUtils` modular branch: after `convert(...)`, `ModularPlanStats stats = ModularTourConverter.planStats(routed, tours);` and pass into `new ModularDispatchModule(drtCfg, tours, cfg.getIdleThreshold(), stats)`. Extend the module ctor + `LOG.info` line (add `unassigned={}`).
- [ ] **Step 10: full modular test classes foreground** (`-Dtest=Modular*Test`), verify green, including `ModularControlArmTest` and `ModularEndToEndTest` (e2e asserts CSV — its expected metric COUNT changes from 21 to 26: update ONLY by appending expectations, never reordering).
- [ ] **Step 11: commit** `feat(modular): plan-time accounting - demand/unassigned/missed counters, max tour load, peak swaps (review F1/F3/F5/F7)`.

---

### Task 2: Python data layer — δ counter, raw decomposition rows, identity checks, CSV policy

**Files:**
- Modify: `parcel-demand-2-matsim-pipeline/analysis/kpi/extract_modular.py`
- Test: `parcel-demand-2-matsim-pipeline/analysis/kpi/tests/test_extract_modular.py`

**Interfaces:**
- Consumes: the five appended CSV metrics from Task 1.
- Produces `kpis_long.csv` rows (group, name): (`freight`, `parcels_demand`), (`freight`, `parcels_unassigned_jsprit`), (`freight`, `parcels_missed_overlay`), (`freight`, `max_parcels_per_tour`), (`modular`, `peak_concurrent_swaps`) + the raw decomposition counters (`freight` group): `parcels_expired_pending`, `parcels_pending_eod`, `parcels_dispatched`, `parcels_dispatched_unserved`, `tours_completed`, `tours_dispatched_incomplete`, `tours_expired_pending`, `tours_pending_eod` — plus meta rows `modular_identity_violated` (only on violation) and `modular_stats_unreadable` (only on parse failure).
- Produces: on `tours_dispatched == 0` (θ=1.0 arm) the rows `tour_completion_rate` and `delta_share_dispatched_incomplete` are **OMITTED** (undefined ≠ 0.0); same for `delta_share_undispatched` when `delta_parcels == 0`. Task 4's render must tolerate absence (it already does: guarded lookups).

**Steps:**

- [ ] **Step 1: failing tests.** Extend `test_extract_modular.py`:
  - fixture CSV gains the five new lines; assert the five new rows appear with correct values/units (`parcels`, `parcels`, `parcels`, `parcels`, `swaps`).
  - raw-counter test: every one of the 8 decomposition counters appears as its own row equal to the fixture value.
  - identity test: a fixture violating identity 4 (`parcels_dispatched != parcels_served + parcels_dispatched_unserved`) yields a `("meta", "modular_identity_violated")` row whose `source` names the failing identity; a conforming fixture yields NO such row.
  - shares-sum test: on every conforming fixture, `delta_share_undispatched + delta_share_dispatched_incomplete == 1.0` (approx) whenever `delta_parcels > 0`.
  - θ=1.0 convention test: `tours_dispatched;0` fixture → `tour_completion_rate` and `delta_share_dispatched_incomplete` rows ABSENT (assert not in the name set), `delta_parcels` still present.
  - unreadable-CSV test: header-only file and 0-byte file each produce `("meta", "modular_stats_unreadable")` and NO crash and no other modular rows; a MISSING file still yields `has_modular_stats() == False` (existing behaviour, pin it).
  - backward-compat test: an OLD 21-metric CSV (no new lines) still extracts everything else and emits a `("meta", "modular_stats_pre_review")`-row? — NO: keep it simpler, old CSVs simply lack the new rows; assert extraction succeeds and the five new names are absent. (Runs predating this wave are already marked "alte, falsche Werte" in §2.14.)
- [ ] **Step 2: run, verify FAIL.**
- [ ] **Step 3: implement.** Wrap the `pd.read_csv`/dict build in `try/except (pandas.errors.EmptyDataError, KeyError)` → `[row("meta", "modular_stats_unreadable", 1, "flag", "<ASCII reason>")]`. Use `stats.get(...)` for the five new metrics (absent on old CSVs → skip their rows). Emit raw counters + identity checks (all five identities from ModularKpiHandler's javadoc, computable from the raw counters; plus identity 0 `parcels_demand == parcels_planned + parcels_unassigned_jsprit` when the new metrics exist; plus negative-residual check on the two `*_pending_eod`). Omit undefined ratios (guard `if dispatched_tours:` → emit, else skip; same for `delta`).
- [ ] **Step 4: run task tests + FULL `python -u -m pytest analysis/kpi/tests -v` foreground, verify green** (render tests consume extract output — the omitted-row convention must not break them; if one asserts `tour_completion_rate` presence unconditionally, fix THAT test's fixture to dispatch ≥1 tour).
- [ ] **Step 5: commit** `feat(kpi): modular delta accounting - demand anchor, raw decomposition rows, identity checks, CSV policy (review F1/I6/M1/M2/M4)`.

---

### Task 3: Python marker layer — scenario-gated marker, corrected recipe, `*_pax` companion rows

**Files:**
- Modify: `parcel-demand-2-matsim-pipeline/analysis/kpi/extract_drt.py`
- Modify: `parcel-demand-2-matsim-pipeline/analysis/kpi/build_kpis.py:76-82`
- Test: `parcel-demand-2-matsim-pipeline/analysis/kpi/tests/test_extract_drt.py` (create if missing — check first), `test_render.py` (the no-events case)

**Interfaces:**
- Consumes: `extract_modular.has_modular_stats(run_dir, meta)` (existing predicate).
- Produces: `extract_drt.extract(run_dir, prefix, fleet_file=None, recon=None, modular=False)` — **build_kpis passes `modular=extract_modular.has_modular_stats(run_dir, meta)`**.
- Produces rows: marker `meta/modular_contaminated_kpis` now emitted whenever `modular=True` (CSV-path signal), independent of events; `meta/fleet_file_missing` moved out of the events block; new corrected rows (only when `modular=True` AND event path available): `drt_tour_hours_total_pax` (subtract), `service_ratio_active_pax`, `fleet_utilisation_by_time_pax`, `mean_pax_aboard_pax` (rescale × `tour_h/(tour_h − freight_h)`, guard `tour_h > freight_h`); set changes: `fleet_utilisation_by_trips` MOVES from `MODULAR_FREIGHT_IN_WINDOW` to `MODULAR_UNCORRECTABLE`; new tuple `MODULAR_SECONDARY_CONTAMINATED = ("drt_tour_duration", "occ_time", "occ_segments", "occ_km", "drt_tour_distance", "vehicles.active_h", "vehicles.ratio_active", "map.occupancy_colors")` emitted as `meta/modular_secondary_contaminated` row.
- Produces docstring truth: "NOT corrected here (correctable in principle by windowing the LinkEnter stream; not done — a self-computed number would replace MATSim's authoritative CSV)" replaces every "cannot be corrected / no events exist for freight legs" claim (review I3 — freight drives DO emit LinkEnterEvents).

**Steps:**

- [ ] **Step 1: failing tests.**
  - no-events marker test (kills review C1): build a scratch run dir with a valid `modular_tour_stats.csv` and NO events cache; call `extract_drt.extract(run_dir, prefix, modular=True)` → rows contain `meta/modular_contaminated_kpis`; `extract(..., modular=False)` on a baseline fixture → row absent. Then the integration pin: `build_kpis.build(fixture_with_modular_csv, no_events=True)` → `kpis_long.csv` contains the marker row (this is the exact reproduced failure).
  - recipe test: with a recon dict where `tour_s=7200`, `freight_s=1800`, `ratio_active=0.5`, mean-pax numerator fixed → `drt_tour_hours_total_pax == 1.5`, `service_ratio_active_pax == 0.5 * 7200/5400`, and marker `source` text contains "rescale" and does NOT contain "subtract drt_freight_hours_total to compare".
  - set test: `"fleet_utilisation_by_trips" in MODULAR_UNCORRECTABLE` and not in `MODULAR_FREIGHT_IN_WINDOW`.
- [ ] **Step 2: run, verify FAIL.**
- [ ] **Step 3: implement** (signature, marker relocation incl. `fleet_file_missing`, sets, `*_pax` rows, docstring + marker text rewrite; ASCII only). The marker's numeric `value` stays the count of affected KPIs; the `source` text gains the per-KPI correction verbs: `subtract: drt_tour_hours_total | rescale x tour/(tour-freight): service_ratio_active, fleet_utilisation_by_time, mean_pax_aboard | not recoverable: fleet_utilisation_by_trips | not corrected (see METHODS-LOG 2.14): drt_vehicle_km, drt_empty_ratio, drt_dp_over_dt`.
- [ ] **Step 4: run + full pytest foreground, green.**
- [ ] **Step 5: commit** `fix(kpi): contamination marker survives no-events builds; corrected recipe + _pax companion rows (review C1/I2/I3)`.

---

### Task 4: Render layer — banner on tiles, marker payload on comparison page, secondary badges

**Files:**
- Modify: `parcel-demand-2-matsim-pipeline/analysis/kpi/render.py` (comparison page ~`:546-675`, `_meta_notes:291-307`)
- Modify: `parcel-demand-2-matsim-pipeline/analysis/kpi/render_drt.py` (tiles `:106-183`, warnbanner precedent `:601-606`)
- Modify: `parcel-demand-2-matsim-pipeline/analysis/kpi/maps.py` (occupancy legend caption)
- Test: `parcel-demand-2-matsim-pipeline/analysis/kpi/tests/test_render.py`

**Interfaces:**
- Consumes: `meta/modular_contaminated_kpis` + `meta/modular_secondary_contaminated` rows (Task 3), `*_pax` rows (Task 3).
- Produces: (a) run-page tiles for `drt_vehicle_km`/`drt_empty_ratio`, `service_ratio_active`, `fleet_utilisation_by_*`, `mean_pax_aboard`, `drt_tour_hours_total` render a `warnbanner`-class badge ("enthaelt Frachtanteil, s. METHODS-LOG 2.14" — reuse the exact CSS class from `render_drt.py:601-606`) whenever the marker row is present, and the `*_pax` values are shown as the tile's sub-line where they exist; (b) `render_comparison_page` renders a "Hinweise" block (reuse `_meta_notes` — call it with the union of all runs' meta rows, prefixing each item with the run label) so the marker payload is on the page that produces cross-scenario figures; (c) `_meta_notes` HTML-escapes name/value/source (`html.escape`, review M11); (d) the occupancy chart / "Aktive Tourdauer" chart / vehicle table get the same badge when `modular_secondary_contaminated` is present; (e) `maps` vehicle-layer legend gains one caption line when the marker exists ("Frachtexkursionen erscheinen als leere Fahrten").

**Steps:**

- [ ] **Step 1: failing tests** in `test_render.py` (extend the existing synthesized-row pattern `:106-118, 211-226`): marker present → run page HTML contains the badge text near `drt_vehicle_km` tile AND the comparison page HTML contains the marker `source` payload; marker absent (baseline fixture) → byte-identical page as before (pin: no badge string anywhere). Escape test: a meta row with `source="a<b&c"` renders escaped.
- [ ] **Step 2: run, verify FAIL.**
- [ ] **Step 3: implement.** Keep it structural: one helper `_contamination_badge(kpis, kpi_name)` in `render_drt.py` consulted by the affected tiles; comparison page calls `_meta_notes` per run with label prefix.
- [ ] **Step 4: full pytest foreground, green.**
- [ ] **Step 5: commit** `fix(render): contamination banner on tiles + comparison-page marker payload + escaped meta notes (review I1/I8/I4/M11)`.

---

### Task 5: Python reconstruction hardening — depot-local fixture, open-window diagnostic, e2e fixture

**Files:**
- Modify: `parcel-demand-2-matsim-pipeline/analysis/drt-headline/drt_service_time.py` (add `open_freight_windows` to the fleet dict; `RE_TIME` word boundary `:54`)
- Modify: `parcel-demand-2-matsim-pipeline/analysis/kpi/extract_drt.py` (`_modular_rows`: emit `meta/modular_open_freight_windows` when > 0, source text cross-references `tours_dispatched_incomplete`)
- Test: `parcel-demand-2-matsim-pipeline/analysis/kpi/tests/test_modular_service_time.py`, new fixture dir `parcel-demand-2-matsim-pipeline/analysis/kpi/tests/fixtures/modularrun/` + new test file `test_build_modular_e2e.py`

**Interfaces:**
- Consumes: Task 3's marker relocation (the e2e fixture asserts the FULL chain).
- Produces: `reconstruct(...)["fleet"]["open_freight_windows"]` = count of windows ending at `+inf` over all vehicles; meta row `modular_open_freight_windows` (value = count, source = "expected == tours_dispatched_incomplete from modular_tour_stats.csv; mismatch means lost/reordered modularTour marks").

**Steps:**

- [ ] **Step 1: failing test A — depot-local `<=` (kills review I5's surviving mutation).** New case in `test_modular_service_time.py`: an excursion whose swap-out STOP task starts at EXACTLY the DISPATCHED timestamp (no approach drive: vehicle already at the depot — build the event stream with `dvrpTaskStarted type=STOP time=1000` and the `modularTour DISPATCHED time=1000` mark). Assert the 420 s land in `retooling_s`, not `stop_s`. **Verify it discriminates:** temporarily flip `a <= t0` to `a < t0` locally, confirm the new test FAILS, flip back, confirm PASS. Record both runs in the report.
- [ ] **Step 2: failing test B — open-window count.** Marks `[(100,+1)]` (never closed) → `fleet["open_freight_windows"] == 1`; balanced marks → 0.
- [ ] **Step 3: implement** (count in `reconstruct` from `windows_by_veh`, `float("inf")` upper bounds; meta row in `_modular_rows` when > 0; `RE_TIME = re.compile(r'\btime="([^"]+)"')`).
- [ ] **Step 4: e2e fixture (kills review M9).** `fixtures/modularrun/`: copy the minimal `drtrun` fixture shape, add (a) a 26-metric `<prefix>.modular_tour_stats.csv`, (b) a tiny hand-written `<prefix>.output_events.xml.gz` containing one vehicle's `dvrpTaskStarted/Ended` sequence + `modularTour` DISPATCHED/COMPLETED pair + the required run scaffolding the drtrun fixture already has. `test_build_modular_e2e.py`: `build(fixture, no_events=False)` → `kpis_long.csv` has marker + `drt_freight_hours_total` + `*_pax` rows; rendered run page contains the badge; `build(fixture, no_events=True)` → marker STILL present (C1 pin at the integration level).
- [ ] **Step 5: full pytest foreground, green. Commit** `test(kpi): depot-local retooling pin, open-window diagnostic, modular e2e fixture (review I5/I7/M9/M12)`.

---

### Task 6: Java hardening — pinning tests + one-liners

**Files:**
- Modify: `parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/modular/ModularTourDispatcher.java` (javadoc `:44-59` only), `ModularTourEvent.java:35` (comment), `ModularOptimizer.java` (belt-2 javadoc), `ModularVehicleTypes.java:29-34` (+INF guard), `ModularKpiHandler.java` (late-convention javadoc line)
- Modify: `parcel-demand-2-matsim-pipeline/src/main/java/hagrid/simulation/HAGRIDSimulationConfig.java` (modular input pre-check: depot CSV)
- Test: `ModularOptimizerTest.java`, `ModularDispatchModuleTest` (create — guard test), `ModularVehicleTypesTest.java`, `HAGRIDSimulationConfigTest.java`

**Steps:**

- [ ] **Step 1: belt-2 CME pinning test (review J-F8).** In `ModularOptimizerTest`, construct a schedule where `enforceIntendedDurations`' loop iterates while the timing updater's ripple triggers the native `REMOVE_STAY_TASK` path (a delayed plain non-last STAY behind a corrupted freight task — the Task-8-review scenario the `List.copyOf` at `ModularOptimizer.java:135` exists for). **Discrimination check:** temporarily revert `List.copyOf(schedule.getTasks())` to `schedule.getTasks()`, confirm the new test fails with `ConcurrentModificationException`, restore.
- [ ] **Step 2: OptimizerRebindGuard firing test (review J-F9).** Direct-construction test: `new OptimizerRebindGuard(mock/plain DefaultDrtOptimizer-substitute, "drt")` → `IllegalStateException` whose message contains "composition is inert"; with a `ModularOptimizer` instance → no throw. (Package-private access — test lives in the same package.)
- [ ] **Step 3: +INF donor guard (review J-F7).** `ModularVehicleTypes.createCapsuleTypes`: after picking the donor, `Preconditions.checkState(Double.isFinite(donor.getCapacity().getOther()), ...)` naming the van-types file. Test: a types file where one van omits `other` capacity → exception naming the file; the normal fixture stays green.
- [ ] **Step 4: depot-CSV pre-check.** In `HAGRIDSimulationConfig`'s modular input-check branch, add the same `requireFile`-style check used for the LMD demand shapefile, for `getLmdDepotCsv()`. Pin with the existing config-test pattern.
- [ ] **Step 5: javadoc/wording one-liners** (no behaviour change — justify skipping TDD in the report):
  - `ModularTourDispatcher.java:48`: `{@code :95}` → `{@code :121}`; **soften the F2 overclaim** (`:50-53`): replace "always larger, and systematically so wherever DRT routing diverges from jsprit's" with "the pre-check omits the approach leg (guaranteed optimistic in that one respect); beyond that, jsprit's car-network time and the DRT-routed time are NOT ordered — near latestEnd the EXPIRED bucket can absorb tours the splicer would still have accepted (METHODS-LOG 2.18)".
  - `ModularTourEvent.java:35`: "or stop count (STOP_SERVED)" → "or the PARCEL count of that stop (STOP_SERVED)".
  - `ModularOptimizer` belt-2 javadoc: exclude the RUNNING capacity-change task from the "any upcoming freight task" claim (review J-F6: `VehicleCapacityChangeActivity` captures its end time as a final double — extension cannot reach the agent).
  - `ModularKpiHandler` C8 note: "late = event time (dwell END for STOP_SERVED, swap-back end for COMPLETED) strictly after 21:00".
- [ ] **Step 6: run all touched Java test classes foreground, green. Commit** `test(modular): pin CME guard + rebind guard, +INF donor check, depot precheck, doc corrections (review J-F6..F11, 2.18)`.

---

### Task 7: Delete the legacy drt-headline dashboards (after dependency proof)

**Files:**
- Delete (ONLY after Step 1 comes back clean): `parcel-demand-2-matsim-pipeline/analysis/drt-headline/build_drt_dashboard.py`, `build_dashboard.py`, `build_vehicle_tours.py`
- Keep unconditionally: `analysis/drt-headline/drt_service_time.py` (core module of the v2 pipeline)
- Modify: any README/doc line referencing the deleted scripts

**Steps:**

- [ ] **Step 1: dependency proof (HARD GATE — user constraint: Hannover-LMD legacy must keep working).** Grep the ENTIRE repo (`*.py`, `*.ps1`, `*.bat`, `*.md`, `*.xml`, `pom.xml`, `.github/`) for `build_drt_dashboard`, `build_vehicle_tours`, and drt-headline `build_dashboard` references. Explicitly check `hagrid_output_analysis/**` (the Hannover legacy analysis package — its `emissions.py` is untouchable regardless) and any Hannover run scripts. **If ANY live reference exists outside `analysis/drt-headline/` itself and outside pure-docs mentions: STOP, report BLOCKED with the reference list — do not delete.**
- [ ] **Step 2: delete the three scripts** (`git rm` per path). If `drt_service_time.py`'s `__main__` block references any of them, verify it does not (it is self-contained per the review).
- [ ] **Step 3: full Python test suite foreground** (nothing in `analysis/kpi/tests` may import them — the review confirmed `sys.path` imports target `drt_service_time` only), green.
- [ ] **Step 4: docs sweep.** Update the BACKLOG rail-stops port reference (`Legacy build_drt_dashboard.py:260-289`) to cite the deleting commit's parent SHA ("geloescht 2026-07-29, Stand in Git-Historie <sha>"); same for any other doc mention found in Step 1.
- [ ] **Step 5: commit** `chore(analysis): delete legacy drt-headline dashboards - superseded by KPI dashboard v2 (review M7/M8; Hannover legacy verified untouched)`.

---

### Task 8: Documentation closeout

**Files:**
- Modify: `docs/METHODS-LOG.md`, `docs/BACKLOG.md`, `docs/BACKLOG-DONE.md`, `docs/superpowers/specs/2026-07-27-1d-modular-capsule-swap-design.md`

**Steps:**

- [ ] **Step 1: METHODS-LOG.** (a) New DECISION entry in §1.2: "Einheitliches Lieferfenster bis 21:00 für 1c UND 1d" (User 2026-07-29) — 1d bleibt 07:30–21:00, 1c wird nachgezogen (Arbeit im BACKLOG `[H]`); annotate the M5 line (§1.2:76) with "→ revidiert 2026-07-29 für die 21:00-Baseline, s. Eintrag unten" (never delete). (b) Annotate §2.16 (δ-Zensur): "Behoben 2026-07-29: `parcels_demand`/`parcels_unassigned_jsprit` + Identity 0 in Java und Python, `<commit>`". (c) Annotate §2.14-Ergänzung points 1–4 with what this wave fixed (marker CSV-gated, recipe corrected + `*_pax` rows, wording fixed, secondary consumers badged) and what remains (actual km correction NOT built — decision unchanged). (d) Annotate §2.18 (javadoc softened), §2.21 (1c-Fenster-Entscheidung ersetzt die Versprechens-Tabelle für 1c↔1d; missed-Overlay jetzt exportiert; Baseline bleibt 08:00–20:00 — der Baseline-Vergleichs-Caveat BLEIBT), §2.22 (`max_parcels_per_tour` exportiert — D8-Beleg pro Run), §2.23 (C10 nachgetragen; late-Konvention im Javadoc).
- [ ] **Step 2: design spec.** Add C10 (flat 420+420 s submission look-ahead) to the concretisation list; reformulate D8's justification ("bindet selten; `max_parcels_per_tour` weist es pro Run nach; wo Kapazität bindet, wirkt sie konservativ gegen 1d") — keep the original wording struck-through-with-note per repo convention of annotating, not rewriting history.
- [ ] **Step 3: BACKLOG.** (a) New `[H]` item under Shared-Use: "1c-Lieferfenster auf 07:30–21:00 anheben (User-Entscheidung 2026-07-29)" — with the consequence line: laufende/fertige 1c-Läufe (chid600, base10c, χ-Sweep-Punkte) fahren die alten M5-Fenster und sind für Headline-Vergleiche gegen 1d danach neu zu fahren. (b) New `[M]` item: "KPI-Landschaft konsolidieren — ein Konzept für Kontaminations-Marker, `*_pax`-Zusatzzeilen und pax_only-Overrides, bevor weitere Szenarien dazukommen (User 2026-07-29: 'kein KPI-Chaos')". (c) Update the Paper-Readiness-Review block: mark the delivered sub-items ✅ with commit refs, move delivered ones to BACKLOG-DONE with proof (commits + test names), keep the NOT-delivered residuals ([M] hourly-degradation check result, economics allocation, actual km correction) explicitly open. (d) Date bump.
- [ ] **Step 4: verify docs against code** (backlog rule: no phantom claims — every ✅ names its commit), commit `docs(modular): fixwave closeout - 21:00 window decision, review items resolved/moved (BACKLOG/METHODS-LOG/spec)`.

---

## Self-Review

- **Coverage vs. review:** F1→T1+T2; C1/I2/I3→T3; I1/I8/I4/M11→T4; I5/I6/I7/M9/M12/M1/M2/M4→T2/T5; J-F6..F11→T6; M7/M8→T7; F5(peak swaps)/F3(max load)/F7(missed)/M-F15(durations)→T1; decisions (21:00, KPI-TODO)→T8. NOT covered by design: F2/variance (run work), economics allocation (blocked on cost-function rebuild), actual km correction (decision: not built), B2B per-stop export (superseded by the 21:00 decision), geometry link-length (only needed for the km correction), re-routing cache (perf, deferred), hourly degradation (data already exists in `kpi_timeseries.csv` — T8 Step 3c records the verification result instead of building anything).
- **Type consistency:** `ModularPlanStats` field names used identically in T1 steps 1/3/7/9; CSV metric names identical across T1 step 5, T2 interfaces, T5 fixture; `modular=` kwarg name identical in T3 interface and step 1.
- **Placeholder scan:** all steps carry concrete anchors/code or an explicit verify-first instruction; the two "create if missing" checks (test_extract_drt.py, ModularDispatchModuleTest) are real repo-state checks, not deferrals.
