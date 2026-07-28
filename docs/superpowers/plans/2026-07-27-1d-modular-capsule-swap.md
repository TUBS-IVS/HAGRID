# 1d — DRT_MODULAR (U-Shift Capsule Swap) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The `DRT_MODULAR` scenario runs one DRT fleet whose vehicles swap between a 10-seat passenger capsule and a 216-parcel cargo capsule at a depot: freight tours are planned offline by jsprit (3.5 h cap), dispatched online through an idle-fleet threshold gate, executed as a spliced task chain (`drive→depot | swap | (drive→stop, dwell)×N | drive→depot | swap-back | STAY`), with δ decomposed into `expired_pending` vs `dispatched_incomplete` in a run-ID-prefixed `modular_tour_stats.csv`.

**Architecture:** Everything rides on **native drt/dvrp core** mechanics proven by the 2026-07-27 spike (`docs/superpowers/notes/2026-07-27-modular-capsule-swap-dvrp-spike.md` — read it first, it is the evidence base): the capsule swap is `DefaultDrtCapacityChangeTask` (drt core), freight stops are plain stay tasks (never `DrtStopTask`s — parcels never become agents, D7), the schedule splice generalises `ServiceTaskSchedulerImpl` (drt-extensions **pattern source only**, NOT a dependency), and the whole thing is one Guice module (`ModularDispatchModule`) composed exactly like the proven `SharedUseModule` (controller half + `installOverridingQSimModule`). Design spec: `docs/superpowers/specs/2026-07-27-1d-modular-capsule-swap-design.md` (user-approved 2026-07-27).

**Tech Stack:** Java 17, MATSim 2025.0 (already pinned), drt/dvrp contribs, jsprit via matsim freight contrib, JUnit 5 + AssertJ, Python/pandas (analysis). **No new Maven dependency** (design D1).

## Plan-level concretisations (deviations from the design spec — flag these in review)

The design (§3.2) lists six hooks; source-grounding during planning showed three can be simplified and one needs a concretisation. C1–C3/C5/C6 change NO observed behaviour of the design, only its mechanics. **Revision 2026-07-28 (grilling pass with the user):** C4 was REPLACED (delivery day 07:30–21:00 instead of wave windows — a real behaviour change vs the design's implicit wave reading, user-decided), C7/C8 added, and the demand-level pairing softened to POC discipline (see Global Constraints). Design spec carries a matching one-line note.

- **C1 — Hook 3 (DrtTaskFactory decorator) dropped.** Only the splicer ever creates 1d tasks — it constructs them directly (`new ModularFreightStopTask(...)`, `new ModularCapacityChangeTask(...)`); native code paths never need to create them. Drive tasks use the natively-bound factory unchanged. One less override, no behaviour change.
- **C2 — Hook 4 (DynActionCreator decorator) dropped.** `ModularFreightStopTask` has DRT base type `STAY` → the native `DrtActionCreator` renders it as `IdleDynActivity` already; `CapacityChangeTask` is handled natively (spike: `DrtActionCreator` STOP branch → `VehicleCapacityChangeActivity`). Accepted cosmetic consequence: the vehicle's activity during a freight dwell is typed "DrtStay" in the QSim — our KPI events (Task 3/7) carry the real semantics.
- **C3 — Divert-from-RELOCATE not implemented.** The dispatcher's candidate pool is idle vehicles only (`DrtScheduleInquiry.isIdle` = current task is the trailing STAY), so the splicer's relocate-divert branch is unreachable; the splicer asserts its precondition instead of silently mishandling it. (Vehicles relocating under rebalancing are simply not candidates that simstep.)
- **C4 (REVISED 2026-07-28, user decision) — Expiry envelope = the full delivery day 07:30–21:00; 1d has NO dispatch waves.** The DRT fleet is written with `serviceEndTime=86400`, so the design's "before the vehicle shift end" needs concretising — but the first draft's answer (the LMD wave window `start + cap + 1h`) would have smuggled the baseline's *supply* structure into the 1d dispatch deadline: a full-length 3.5 h tour would expire ~46 min after its wave, right through the morning pax peak, and the midday lull could never be used. User decision: parcels arrive at the depot overnight; same-day delivery 07:30–21:00 counts, the time of day does not. Therefore `runModular` builds its jsprit vehicles with the EXPLICIT window `[Modular.DELIVERY_DAY_START_S, Modular.DELIVERY_DAY_END_S]` = [27000, 75600] and aligns the service-start time windows to the same interval (documented deviation from the baseline's 08:00–20:00 `DAY_START/DAY_END`); no waves, no departure jitter (the capsule vehicles are virtual — the DRT fleet executes the tours). `ModularFreightTour.latestEnd` is 21:00 for every tour; the pending-expiry formula (`now + 2×RETOOLING_S + plannedDuration > latestEnd`) and the splicer's completion-envelope check are mechanically unchanged — a 3.5 h tour stays dispatchable until ~17:16. Accepted consequences (user 2026-07-28): (a) 1d makes a weaker time promise than the LMD baseline (same-day instead of in-wave) — document as a concept property, not a side effect; (b) jsprit cuts 1d tours structurally differently (216 capsule, 3.5 h cap, day window) — the comparison runs over δ/parcels and operational KPIs, not tour geometry; the 25200 s control arm isolates the cap effect. The LMD-baseline wave mechanics themselves are a separate backlog item (`[M]` LMD Dispatch-Stunden, updated 2026-07-28).
- **C5 — Swap capacities are 1-D.** The run's `DvrpLoadType` stays the default 1-D `passengers` type; swap-out sets capacity to the empty load (0 passengers), swap-back to `vehicle.getCapacity()` (10). A `parcels` load dimension would have nothing to count — parcels are never DVRP loads (D7); the 216-parcel cargo capacity is the documented never-binding concept parameter (D8). The design's chain notation `CapacityChange(pax=0, parcels=216)` is realised as `CapacityChange(passengers=0)` + documentation.
- **C6 — `ModularCapacityChangeTask` subclasses `DefaultDrtCapacityChangeTask`** (design said "use the native task directly") — it adds only identity metadata (`tourId`, `swapBack` flag, `intendedDuration`) needed by the end-time calculator, the commitment predicate and the KPI events. The native swap mechanics are inherited untouched.
- **C7 (2026-07-28) — Morning surge accepted; dispatch order interleaved across providers.** With the day window all tours share `plannedStart ≈ 07:30` → all become pending at ~07:16, the fleet is fully idle at that hour, and the gate's while-loop dispatches in ONE simstep (= 1 sim-second) until `idleShare ≤ θ` — i.e. ~(1−θ)·fleet vehicles leave at once, locked out for pax up to ~3.5 h + retooling + deadhead. Accepted deliberately (user: "erst Ergebnisse ansehen"): the θ-sweep plus pax KPIs vs baseline make the cost visible; no dispatch-rate limit, no demand forecast. (Predictive gate fed by previous-iteration request/rejection rates — native precedent `PreviousIterationDrtDemandEstimator` — is parked as backlog `[M]`, decide after the first runs.) To avoid a systematic per-provider δ bias when the gate is scarce, pending order is **`(submissionTime, tourIndex, provider)`** — all `_t0` tours first, then all `_t1`, … — instead of alphabetical `tourId` (which would put ALL dhl tours before the first gls tour). `ModularFreightTour` carries an explicit `tourIndex` component for this.
- **C8 (2026-07-28) — Late delivery is measured, not prevented.** Feasibility is checked at dispatch with planned times (free-speed routing + planned dwells); mobsim delays can push the actual completion past 21:00 with the tour still counting COMPLETED. The KPI handler therefore also writes `tours_completed_late` (COMPLETED event time > `DELIVERY_DAY_END_S`) and `parcels_served_late` (Σ parcels of STOP_SERVED events with time > `DELIVERY_DAY_END_S`); `extract_modular.py` exports both (dashboard card noted in the backlog). No abort mechanism, no behaviour change — the 07:30–21:00 promise stays ex-ante, this makes the ex-post violation visible.

## Global Constraints

- **Read first:** the spike note (`docs/superpowers/notes/2026-07-27-modular-capsule-swap-dvrp-spike.md`) and the design spec (`docs/superpowers/specs/2026-07-27-1d-modular-capsule-swap-design.md`). The spike's §2 splice invariants and §3 gotchas are binding.
- **Binding scope rule (1c-proven):** controller-scope keys via `Controler.addOverridingModule`-installed `AbstractDvrpModeModule`; QSim keys ONLY inside `installOverridingQSimModule` — controller-scope `bindModal` of a QSim key produces `BindingAlreadySet`.
- **Optimizer rebind rule:** the native `DrtModeOptimizerQSimModule` registers `DrtOptimizer` via `addModalComponent` (binding + QSim-component registration). The override module must `bindModal(DrtOptimizer.class).toProvider(...)` ONLY — a second `addModalComponent` would double-register the QSim component.
- **Determinism:** no `UUID.randomUUID()` anywhere (spike §3.5) — tour ids derive from carrier id + tour index; all iteration over maps uses insertion-ordered or sorted collections; jsprit stays single-threaded with seed 4711 (existing `configureAlgorithm`); QSim-scoped dispatcher state resets naturally per iteration (fresh QSim injector), controller-scoped KPI handler resets via `reset(int)` (1c lesson `dd34b23`).
- **Run-ID-prefixed outputs:** the KPI CSV is written via `controlerIO.getOutputFilename(...)` → `<runId>.modular_tour_stats.csv`; the extractor matches that exact name (1c bug `89f1ee5` designed out).
- Parameters (design §5): pax capsule **10 seats** (already the DRT_MODULAR fleet capacity via `DrtInputsFingerprint.expectedCapacity` — verified, no change); cargo capsule **216** (documented never-binding); retooling **420 s**; tour cap default **12600 s** / control arm 25200 s; idle threshold default **0.50**; look-ahead **420 s + retooling**; **delivery day 07:30–21:00** (`Modular.DELIVERY_DAY_START_S`/`DELIVERY_DAY_END_S`, C4 revised — vehicle window AND service-start TW, no waves); stop dwell `min(2 min × parcels, 15 min)` (LMD parity) × dwell factor 1.0 (autonomy hook stays parameterised, out of scope).
- All existing scenarios (`DRT_BASELINE` married/pax-only, `DRT_SHAREDUSE`, `LMD_BASELINE`, Hannover legacy) must stay green — the full suite is the regression net for every task. Run tests from `parcel-demand-2-matsim-pipeline/`.
- **VERIFY-SOURCE** steps: check the exact signature in the unzipped 2025.0 sources before compiling (`~/.m2/repository/org/matsim/contrib/{drt,dvrp}/2025.0/*-sources.jar`; unzipped copies exist in the spike scratchpad) and adapt mechanically if drifted.
- Branch `hendrik`; never merge to master; commit per task with explicit `git add` of named files; `.shp`/binary test fixtures need `git add -f` (gitignore).
- Windows: `python -u` for Python runs; no non-ASCII in `print()`; never Edit/Write `.bat` files.
- **Demand level (run discipline, not code — softened 2026-07-28, user):** POC runs simply use the current PANDA export stand (Zensus `level_central` = 6,024 parcels since 2026-07-27; the design spec's 7,271 is the retired OSM stand, now `level_osm_central`). **No pairing obligation now** — the serious paper runs later need ONE matched set (10-seat re-baseline + 1c + 1d on the identical demand file), not the POC. `HagridPaths.lmdDemandShapefile()` is hard-wired, so levels are swapped by file exchange — verify the staged file's SHA256 before each *reported* run (`run_lmd_band.ps1` pattern) and record the stand in the run tag.

## File Structure

```
src/main/java/hagrid/integrated/modular/
├── Modular.java                        ← NEW constants + task types + commitment predicate   (Task 3)
├── ModularFreightStopTask.java         ← NEW plain stay task w/ intended duration (D7)       (Task 3)
├── ModularCapacityChangeTask.java      ← NEW native swap task + tour identity (C6)           (Task 3)
├── ModularTourEvent.java               ← NEW single typed KPI event (6 phases)               (Task 3)
├── ModularTourEventHandler.java        ← NEW handler interface                               (Task 3)
├── ModularVehicleTypes.java            ← NEW U-Shift cargo capsule CarrierVehicleType        (Task 2)
├── ModularFreightTour.java             ← NEW immutable tour record (+Stop)                   (Task 4)
├── ModularTourConverter.java           ← NEW routed CarrierPlan → List<ModularFreightTour>   (Task 4)
├── ModularStayTaskEndTimeCalculator.java ← NEW mandatory end-time decorator (spike §3.1)     (Task 5)
├── ModularTourScheduler.java           ← NEW schedule splicer (spike §2 invariants)          (Task 6)
├── ModularTourDispatcher.java          ← NEW gate + selection + expiry + events              (Task 7)
├── ModularEntryFactory.java            ← NEW strict pax lockout (D2)                         (Task 8)
├── ModularOptimizer.java               ← NEW DrtOptimizer decorator (tick + enforce)         (Task 8)
├── ModularKpiHandler.java              ← NEW controller-scope KPI CSV writer                 (Task 9)
└── ModularDispatchModule.java          ← NEW Guice composition (controller + QSim)           (Task 10)
src/main/java/hagrid/utils/routing/HAGRIDRouterUtils.java        ← MODIFY (Task 1: cap param)
src/main/java/hagrid/integrated/freight/LmdCarrierBuilder.java   ← MODIFY (Task 1: cap param)
src/main/java/hagrid/integrated/freight/LausitzFreightPreprocessor.java ← MODIFY (Task 2: runModular)
src/main/java/hagrid/simulation/HAGRIDSimulationConfig.java      ← MODIFY (Task 11: idleThreshold, maxTourDuration)
src/main/java/hagrid/simulation/SimulationRunnerUtils.java       ← MODIFY (Task 11: third wiring case)
src/test/java/hagrid/integrated/modular/…                        ← NEW unit tests per class
src/test/java/hagrid/integrated/drt/ModularEndToEndTest.java     ← NEW e2e smoke (Task 10)
src/test/java/hagrid/integrated/drt/ModularControlArmTest.java   ← NEW θ=1.0 ≡ baseline (Task 12)
analysis/kpi/extract_modular.py                                  ← NEW extractor (Task 13)
analysis/kpi/build_kpis.py                                       ← MODIFY (Task 13: register)
analysis/kpi/tests/test_extract_modular.py                       ← NEW pytest (Task 13)
```

## Canonical interface table (types used across tasks — keep names EXACTLY consistent)

| Symbol | Defined in | Signature |
|---|---|---|
| `Modular.CARGO_CAPACITY_PARCELS` | Task 3 | `int` = 216 |
| `Modular.RETOOLING_S` | Task 3 | `double` = 420.0 |
| `Modular.FREIGHT_LOOKAHEAD_S` | Task 3 | `double` = 420.0 |
| `Modular.DEFAULT_IDLE_THRESHOLD` | Task 3 | `double` = 0.50 |
| `Modular.DEFAULT_MAX_TOUR_DURATION_S` | Task 3 | `int` = 12600 |
| `Modular.DELIVERY_DAY_START_S` / `DELIVERY_DAY_END_S` | Task 2 | `double` = 27000.0 (07:30) / 75600.0 (21:00) — C4 revised |
| `Modular.CARGO_CAPSULE_TYPE_ID` | Task 3 | `String` = `"ushift_cargo_capsule"` |
| `Modular.FREIGHT_STOP_TASK_TYPE` / `FREIGHT_DRIVE_TASK_TYPE` | Task 3 | `DrtTaskType("MODULAR_FREIGHT_STOP", STAY)` / `("MODULAR_FREIGHT_DRIVE", DRIVE)` |
| `Modular.hasUnperformedFreightTask(Schedule)` | Task 3 | `static boolean` |
| `ModularFreightStopTask(double begin, double end, Link link, int parcels, String tourId, int stopIndex)` | Task 3 | getters `getIntendedDuration()`, `getParcels()`, `getTourId()`, `getStopIndex()` |
| `ModularCapacityChangeTask(double begin, double end, Link link, DvrpLoad changedCapacity, String tourId, boolean swapBack)` | Task 3 | getters `getIntendedDuration()` (=RETOOLING_S), `getTourId()`, `isSwapBack()` |
| `ModularTourEvent` | Task 3 | phases `PLANNED, EXPIRED, DISPATCHED, SWAP_DONE, STOP_SERVED, COMPLETED`; static factories per phase |
| `ModularVehicleTypes.createCapsuleTypes(String vanTypesFile)` | Task 2 | `static CarrierVehicleTypes` (exactly one type: the capsule) |
| `LausitzFreightPreprocessor.runModular(String demandShp, String depotCsv, String networkFile, String vanTypesFile, String carriersOut, int jspritIterations, String serviceAreaShp, int maxTourDurationSeconds)` | Task 2 | `static void` |
| `LmdCarrierBuilder.buildSingleWindow(provider, deliveries, depotLink, network, vanTypes, durationPerParcelMin, maxDurationPerStopMin, random, vehicleEarliestStart, vehicleLatestEnd, serviceTwStart, serviceTwEnd)` | Task 1 | `static Carrier`; ONE un-jittered vehicle per van type, explicit window + service TW (C4/C7); legacy `build` untouched (waves) |
| `HAGRIDRouterUtils.configureAlgorithm(vrp, serviceCount, jspritIterations, network, uTurnPenaltyCost, maxRouteDurationSeconds)` | Task 1 | new fullest overload |
| `LausitzFreightPreprocessor.routeWithDurationCap(carriers, network, vehicleTypes, jspritIterations, maxRouteDurationSeconds)` | Task 1 | new overload |
| `ModularFreightTour(String tourId, String provider, int tourIndex, Id<Link> depotLink, double plannedStart, double plannedDuration, double latestEnd, List<Stop> stops)` | Task 4 | record; `Stop(Id<Link> link, double serviceDuration, int parcels)`; `int totalParcels()`; `double submissionTime()`; `tourIndex` = C7 interleave key |
| `ModularTourConverter.convert(Carriers carriers, Network carNetwork, Network drtNetwork)` | Task 4 | `static List<ModularFreightTour>`; + `static Carriers read(String carriersFile, CarrierVehicleTypes types)` |
| `ModularStayTaskEndTimeCalculator(ScheduleTimingUpdater.StayTaskEndTimeCalculator delegate)` | Task 5 | implements `ScheduleTimingUpdater.StayTaskEndTimeCalculator` |
| `ModularTourScheduler(Network network, TravelTime travelTime, TravelDisutility travelDisutility, DrtTaskFactory taskFactory, DvrpLoadType loadType)` | Task 6 | `Optional<ScheduledExcursion> schedule(DvrpVehicle vehicle, ModularFreightTour tour, double now)`; record `ScheduledExcursion(double deadheadMeters, double serviceMeters, double plannedCompletion)` |
| `ModularTourDispatcher(String mode, List<ModularFreightTour> tours, double idleThreshold, Fleet fleet, DrtScheduleInquiry scheduleInquiry, ModularTourScheduler scheduler, Network network, EventsManager events)` | Task 7 | `void dispatch(double now)`; `void observeTaskTransition(DvrpVehicle vehicle, Task previous, double now)` |
| `ModularEntryFactory(VehicleEntry.EntryFactory delegate)` | Task 8 | implements `VehicleEntry.EntryFactory` |
| `ModularOptimizer(DrtOptimizer delegate, ModularTourDispatcher dispatcher, ScheduleTimingUpdater scheduleTimingUpdater, MobsimTimer timer)` | Task 8 | implements `DrtOptimizer` |
| `ModularKpiHandler(OutputDirectoryHierarchy controlerIO)` | Task 9 | implements `ModularTourEventHandler, ShutdownListener`; `FILE_NAME = "modular_tour_stats.csv"` |
| `ModularDispatchModule(DrtConfigGroup drtCfg, List<ModularFreightTour> tours, double idleThreshold)` | Task 10 | extends `AbstractDvrpModeModule` |
| `HAGRIDSimulationConfig.getIdleThreshold()` / `getMaxTourDurationSeconds()` | Task 11 | `double` / `int`; runner keys `idleThreshold` / `maxTourDuration` |
| `SimulationRunnerUtils.runsCarrierModules(HagridConfig.Scenario, boolean drtWithFreight)` | Task 11 | `static boolean` — the testable carrier-module guard |

---

### Task 1: jsprit cap parameter + single-window carrier builder (Lausitz path only)

Two enablers, same files. **(1)** The 3.5 h cap (D5) must flow through the places that today hard-code `HAGRIDRouterUtils.MAXROUTEDURATION` (25200): the two jsprit constraints and the departure-time updater. **(2)** C4 (revised): 1d vehicles carry an EXPLICIT operating window 07:30–21:00 instead of the baseline's wave mechanics — new `LmdCarrierBuilder.buildSingleWindow(...)`. The wave-relative window derivation at `LmdCarrierBuilder:142-144` is NOT touched (the pre-revision draft parameterised it; dead code once 1d uses the explicit window). Hannover and `LMD_BASELINE` keep 25200 + waves byte-identically (regression: their routing results are seed-stable).

**Files:**
- Modify: `src/main/java/hagrid/utils/routing/HAGRIDRouterUtils.java` (around lines 89-120)
- Modify: `src/main/java/hagrid/integrated/freight/LmdCarrierBuilder.java` (build signature, line 142-144)
- Modify: `src/main/java/hagrid/integrated/freight/LausitzFreightPreprocessor.java` (`routeWithDurationCap` overload)
- Test: `src/test/java/hagrid/integrated/freight/LmdCarrierBuilderTest.java` (extend)

**Interfaces:**
- Consumes: existing `configureAlgorithm(vrp, serviceCount, jspritIterations, network, uTurnPenaltyCost)`, `LmdCarrierBuilder.build(provider, deliveries, depotLink, network, vanTypes, durationPerParcelMin, maxDurationPerStopMin, dispatchHours, random)`.
- Produces: `configureAlgorithm(..., int maxRouteDurationSeconds)` fullest overload; `routeWithDurationCap(..., int maxRouteDurationSeconds)`; `LmdCarrierBuilder.buildSingleWindow(provider, deliveries, depotLink, network, vanTypes, durationPerParcelMin, maxDurationPerStopMin, random, vehicleEarliestStart, vehicleLatestEnd, serviceTwStart, serviceTwEnd)` — ONE un-jittered vehicle per van type with the explicit window, services with the aligned start TW. Existing signatures delegate with `HAGRIDRouterUtils.MAXROUTEDURATION`; legacy `build` keeps its wave behaviour untouched.

- [ ] **Step 1: Write the failing test**

Add to `LmdCarrierBuilderTest` (reuse its existing fixture helpers for network/deliveries — read the test first):

```java
@Test
@DisplayName("buildSingleWindow: explicit 07:30-21:00 window, no waves, no jitter; legacy build untouched")
void singleWindowBuildsExplicitWindow() {
    Carrier modular = LmdCarrierBuilder.buildSingleWindow("dhl", deliveries, depotLink, network,
            vanTypes, 2, 15, new Random(1), 27000.0, 75600.0, 27000.0, 75600.0);

    // exactly ONE vehicle per van type, window exactly as passed (no wave copies, no jitter)
    assertThat(modular.getCarrierCapabilities().getCarrierVehicles()).hasSize(vanTypes.length);
    modular.getCarrierCapabilities().getCarrierVehicles().values().forEach(v -> {
        assertThat(v.getEarliestStartTime()).isEqualTo(27000.0);
        assertThat(v.getLatestEndTime()).isEqualTo(75600.0);
    });
    // services carry the ALIGNED start window (C4 revised: 07:30-21:00, not DAY_START/DAY_END)
    modular.getServices().values().forEach(s -> {
        assertThat(s.getServiceStartTimeWindow().getStart()).isEqualTo(27000.0);
        assertThat(s.getServiceStartTimeWindow().getEnd()).isEqualTo(75600.0);
    });
    // missed-delivery overlay still applied (same RNG core as build)
    assertThat(modular.getAttributes().getAttribute("numberOfParcels")).isNotNull();

    // legacy 9-arg build: wave-relative window EXACTLY as before (byte-identity guard)
    Carrier legacy = LmdCarrierBuilder.build("dhl", deliveries, depotLink, network, vanTypes,
            2, 15, List.of(8), new Random(1));
    legacy.getCarrierCapabilities().getCarrierVehicles().values().forEach(v ->
            assertThat(v.getLatestEndTime()).isEqualTo(Math.min(
                    v.getEarliestStartTime() + HAGRIDRouterUtils.MAXROUTEDURATION + 3600.0,
                    21 * 3600.0)));
}
```

(VERIFY-SOURCE: the `CarrierService` time-window accessor name in the freight contrib 2025.0 — historic versions carried a typo'd `getServiceStaringTimeWindow()`; adapt mechanically.)

- [ ] **Step 2: Run it, verify it fails**

Run: `mvn -q test -Dtest=LmdCarrierBuilderTest` — expected: compile error (no `buildSingleWindow`).

- [ ] **Step 3: Implement**

`LmdCarrierBuilder`: extract the carrier-skeleton + service-building + missed-delivery-overlay body of `build(...)` (lines 81-126: createCarrier through the missed-parcel attributes) into a private helper that takes the service time window as a parameter — **the RNG draw order inside must stay exactly as today** (dailyBias first, then per-parcel misses; the byte-identity of legacy `build` output hangs on it). `build(...)` calls it with `TimeWindow.newInstance(DAY_START, DAY_END)` and keeps its wave-vehicle loop untouched. Add the single-window variant:

```java
/**
 * 1d single-window variant (plan C4 revised, user 2026-07-28): the capsule "vehicles" are
 * virtual (the DRT fleet executes the tours), so NO dispatch waves, NO departure jitter -
 * ONE vehicle per van type with an explicit operating window, services with the aligned
 * start time window (07:30-21:00 for 1d instead of the baseline's 08:00-20:00).
 */
public static Carrier buildSingleWindow(String provider, List<Delivery> deliveries,
        Id<Link> depotLink, Network network, VehicleType[] vanTypes, int durationPerParcelMin,
        int maxDurationPerStopMin, Random random, double vehicleEarliestStart,
        double vehicleLatestEnd, double serviceTwStart, double serviceTwEnd) {
    Carrier carrier = buildCore(provider, deliveries, network, vanTypes, durationPerParcelMin,
            maxDurationPerStopMin, random, TimeWindow.newInstance(serviceTwStart, serviceTwEnd));
    for (VehicleType vanType : vanTypes) {
        CarrierVehicle vehicle = CarrierVehicle.Builder
                .newInstance(Id.createVehicleId(provider + "_" + vanType.getId() + "_day_v0"),
                        depotLink, vanType)
                .setEarliestStart(vehicleEarliestStart)
                .setLatestEnd(vehicleLatestEnd)
                .build();
        CarriersUtils.addCarrierVehicle(carrier, vehicle);
    }
    return carrier;
}
```

(Exact helper signature is the executor's choice — the contract is: legacy `build` output byte-identical, `buildSingleWindow` = same services/overlay + explicit-window vehicles, `FleetSize.INFINITE` clones as needed.)

`HAGRIDRouterUtils`: same pattern — the existing 5-arg `configureAlgorithm(vrp, serviceCount, jspritIterations, network, uTurnPenaltyCost)` becomes a delegate to a new 6-arg overload with `int maxRouteDurationSeconds` as last parameter; inside, the three usages (`UpdateDepartureTimeAndPracticalTimeWindows`, `MaxRouteDurationConstraint`, `TimeWindowConstraintWithDriverTime`) use the parameter. Do NOT touch the `MAXROUTEDURATION` constant or its javadoc (Hannover parity).

`LausitzFreightPreprocessor.routeWithDurationCap`: add a 5th parameter `int maxRouteDurationSeconds`, pass it to `configureAlgorithm(vrp, serviceCount, iters, null, 0.0, maxRouteDurationSeconds)`; keep the 4-arg signature delegating with `HAGRIDRouterUtils.MAXROUTEDURATION`. (Note: the current 4-arg call site uses the 3-arg `configureAlgorithm(vrp, serviceCount, iters)` — route the new overload through the 6-arg form with `network=null, uTurnPenaltyCost=0.0`, which is the same behaviour.)

- [ ] **Step 4: Run the test + the touched suites**

Run: `mvn -q test -Dtest=LmdCarrierBuilderTest,LausitzFreightPreprocessorTest,LmdBaselineEndToEndTest` — expected: PASS (legacy behaviour byte-identical).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/hagrid/utils/routing/HAGRIDRouterUtils.java src/main/java/hagrid/integrated/freight/LmdCarrierBuilder.java src/main/java/hagrid/integrated/freight/LausitzFreightPreprocessor.java src/test/java/hagrid/integrated/freight/LmdCarrierBuilderTest.java
git commit -m "feat(modular): jsprit cap param + single-window carrier builder, no waves for 1d (Task 1)"
```

---

### Task 2: U-Shift cargo-capsule vehicle type + `LausitzFreightPreprocessor.runModular`

**Files:**
- Create: `src/main/java/hagrid/integrated/modular/ModularVehicleTypes.java`
- Modify: `src/main/java/hagrid/integrated/freight/LausitzFreightPreprocessor.java`
- Test: `src/test/java/hagrid/integrated/modular/ModularVehicleTypesTest.java`
- Test: `src/test/java/hagrid/integrated/freight/LausitzFreightPreprocessorTest.java` (extend)

**Interfaces:**
- Consumes: Task 1's `routeWithDurationCap(..., maxRouteDurationSeconds)` and `LmdCarrierBuilder.buildSingleWindow(...)`; constants `Modular.CARGO_CAPSULE_TYPE_ID` / `DELIVERY_DAY_START_S` / `DELIVERY_DAY_END_S` — **forward reference**: until Task 3 lands, create a minimal `Modular` seed class in THIS task containing only `CARGO_CAPACITY_PARCELS`, `CARGO_CAPSULE_TYPE_ID` and the two `DELIVERY_DAY_*` constants, which Task 3 then extends.
- Produces: `ModularVehicleTypes.createCapsuleTypes(String vanTypesFile)` and `LausitzFreightPreprocessor.runModular(demandShp, depotCsv, networkFile, vanTypesFile, carriersOut, jspritIterations, serviceAreaShp, maxTourDurationSeconds)`.

- [ ] **Step 1: Write the failing tests**

`ModularVehicleTypesTest`:

```java
@Test
@DisplayName("capsule type: 216 parcel capacity, id ushift_cargo_capsule, costs cloned from the largest van")
void capsuleClonesLargestVanCosts(@TempDir Path tmp) throws Exception {
    // van fixture mirrors MarriedBaselineEndToEndTest lines 63-70
    CarrierVehicleTypes vans = new CarrierVehicleTypes();
    VehicleType m = VehicleUtils.createVehicleType(Id.create("ct_cep_size_m", VehicleType.class));
    m.getCapacity().setOther(165);
    m.setNetworkMode("car");
    m.getCostInformation().setCostsPerMeter(0.0004).setCostsPerSecond(0.0).setFixedCost(170.0);
    vans.getVehicleTypes().put(m.getId(), m);
    VehicleType l = VehicleUtils.createVehicleType(Id.create("ct_cep_size_l", VehicleType.class));
    l.getCapacity().setOther(250);
    l.setNetworkMode("car");
    l.getCostInformation().setCostsPerMeter(0.0006).setCostsPerSecond(0.0).setFixedCost(200.0);
    vans.getVehicleTypes().put(l.getId(), l);
    Path typesFile = tmp.resolve("vans.xml");
    new CarrierVehicleTypeWriter(vans).write(typesFile.toString());

    CarrierVehicleTypes result = ModularVehicleTypes.createCapsuleTypes(typesFile.toString());

    assertThat(result.getVehicleTypes()).hasSize(1);
    VehicleType capsule = result.getVehicleTypes().values().iterator().next();
    assertThat(capsule.getId().toString()).isEqualTo(Modular.CARGO_CAPSULE_TYPE_ID);
    assertThat(capsule.getCapacity().getOther()).isEqualTo((double) Modular.CARGO_CAPACITY_PARCELS);
    assertThat(capsule.getNetworkMode()).isEqualTo("car");
    // cost donor = LARGEST-capacity van (deterministic; ties impossible with m/l)
    assertThat(capsule.getCostInformation().getCostsPerMeter()).isEqualTo(0.0006);
    assertThat(capsule.getCostInformation().getFixedCosts()).isEqualTo(200.0);
}
```

Extend `LausitzFreightPreprocessorTest` (reuse its existing demand/depot/network fixture staging — read the test first; the married e2e's staging at `MarriedBaselineEndToEndTest.java:52-83` is the same recipe):

```java
@Test
@DisplayName("runModular: ONLY the capsule type, full 07:30-21:00 day window (C4 revised)")
void runModularRoutesWithCapsuleTypeAndDayWindow(@TempDir Path tmp) throws Exception {
    // stage grid network / demand shp / depots csv / vans.xml exactly like the existing
    // run(...) test in this class, then:
    Path carriersOut = tmp.resolve("modular_carriers_routed.xml");
    LausitzFreightPreprocessor.runModular(demandShp.toString(), depotCsv.toString(),
            netFile.toString(), typesFile.toString(), carriersOut.toString(),
            /*jspritIterations*/ 1, shpFile.toString(), /*maxTourDurationSeconds*/ 12600);

    Carriers routed = new Carriers();
    CarrierVehicleTypes capsuleTypes = ModularVehicleTypes.createCapsuleTypes(typesFile.toString());
    new CarrierPlanXmlReader(routed, capsuleTypes).readFile(carriersOut.toString());

    assertThat(routed.getCarriers()).isNotEmpty();
    for (Carrier c : routed.getCarriers().values()) {
        assertThat(c.getSelectedPlan()).isNotNull();
        // every carrier vehicle is a capsule
        c.getCarrierCapabilities().getCarrierVehicles().values().forEach(v ->
                assertThat(v.getType().getId().toString()).isEqualTo(Modular.CARGO_CAPSULE_TYPE_ID));
        // C4 revised: every vehicle carries the full delivery-day window 07:30-21:00 (no waves)
        for (ScheduledTour st : c.getSelectedPlan().getScheduledTours()) {
            assertThat(st.getVehicle().getEarliestStartTime())
                    .isEqualTo(Modular.DELIVERY_DAY_START_S);
            assertThat(st.getVehicle().getLatestEndTime())
                    .isEqualTo(Modular.DELIVERY_DAY_END_S);
        }
    }
}
```

- [ ] **Step 2: Run both, verify they fail**

Run: `mvn -q test -Dtest=ModularVehicleTypesTest,LausitzFreightPreprocessorTest` — expected: compile errors (classes/methods missing).

- [ ] **Step 3: Implement**

Create the minimal `Modular` seed class (Task 3 extends it):

```java
package hagrid.integrated.modular;

/** Constants for the 1d Modular (U-Shift capsule swap) scenario. Extended in Task 3. */
public final class Modular {
    /** Cargo capsule parcel capacity (spec §6.1). DOCUMENTED NEVER-BINDING (design D8):
     *  216 x 2 min dwell = 7.2h exceeds any tour cap <= 7h, so time always binds first.
     *  It sizes the jsprit vehicle; it is NOT a DvrpLoad dimension (design D7 / plan C5). */
    public static final int CARGO_CAPACITY_PARCELS = 216;
    public static final String CARGO_CAPSULE_TYPE_ID = "ushift_cargo_capsule";

    /** Delivery day (plan C4 revised, user 2026-07-28): parcels arrive at the depot overnight,
     *  same-day delivery 07:30-21:00 is what counts - NO dispatch waves in 1d. Used as the
     *  jsprit vehicle operating window AND the service-start time window. */
    public static final double DELIVERY_DAY_START_S = 7.5 * 3600.0;   // 07:30
    public static final double DELIVERY_DAY_END_S = 21 * 3600.0;      // 21:00

    private Modular() {}
}
```

`ModularVehicleTypes`:

```java
package hagrid.integrated.modular;

import org.matsim.api.core.v01.Id;
import org.matsim.freight.carriers.CarrierVehicleTypeReader;
import org.matsim.freight.carriers.CarrierVehicleTypes;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

import java.util.Comparator;

/**
 * The U-Shift cargo capsule as a jsprit/carrier vehicle type (design D4: 7 depot groups,
 * ONLY the vehicle type swapped). Cost parameters are cloned from the largest van in the
 * existing LMD vehicle-types file so the jsprit objective stays comparable with the LMD
 * baseline instead of inventing new cost numbers; capacity is the 216-parcel capsule (D8).
 */
public final class ModularVehicleTypes {

    private ModularVehicleTypes() {}

    /** Returns a CarrierVehicleTypes holding EXACTLY one type: the cargo capsule. */
    public static CarrierVehicleTypes createCapsuleTypes(String vanTypesFile) {
        CarrierVehicleTypes vanTypes = new CarrierVehicleTypes();
        new CarrierVehicleTypeReader(vanTypes).readFile(vanTypesFile);
        VehicleType donor = vanTypes.getVehicleTypes().values().stream()
                .max(Comparator.comparingDouble(t -> t.getCapacity().getOther()))
                .orElseThrow(() -> new IllegalStateException(
                        "No van vehicle types in " + vanTypesFile));

        VehicleType capsule = VehicleUtils.createVehicleType(
                Id.create(Modular.CARGO_CAPSULE_TYPE_ID, VehicleType.class));
        capsule.getCapacity().setOther((double) Modular.CARGO_CAPACITY_PARCELS);
        capsule.setNetworkMode(donor.getNetworkMode());
        capsule.setMaximumVelocity(donor.getMaximumVelocity());
        capsule.getCostInformation()
                .setCostsPerMeter(donor.getCostInformation().getCostsPerMeter())
                .setCostsPerSecond(donor.getCostInformation().getCostsPerSecond())
                .setFixedCost(donor.getCostInformation().getFixedCosts());

        CarrierVehicleTypes out = new CarrierVehicleTypes();
        out.getVehicleTypes().put(capsule.getId(), capsule);
        return out;
    }
}
```

(VERIFY-SOURCE: `VehicleCapacity.getOther()` returns `Double` — if it can be null for the donor file, guard with `Optional`/default like the reader leaves it; `getMaximumVelocity()` may be `Double.POSITIVE_INFINITY` — copy as-is.)

`LausitzFreightPreprocessor.runModular` — mirror `run(...)` (lines 105-159) with three deltas ((1) capsule type, (2) cap threaded, (3) provider→depot rule unchanged = 1c-M4 parity is already what `run` does):

```java
/**
 * DRT_MODULAR preprocessing (1d): identical demand/depot/clip pipeline as the LMD baseline,
 * but every carrier gets ONE vehicle type - the 216-parcel U-Shift cargo capsule -, the
 * jsprit route-duration cap is the Modular tour cap (design D5: 12600s default, 25200s
 * control arm) instead of the 7h driver shift, and there are NO dispatch waves (plan C4
 * revised): one un-jittered vehicle template per carrier with the full delivery-day window
 * 07:30-21:00, service-start TWs aligned to the same interval.
 */
public static void runModular(String demandShp, String depotCsv, String networkFile,
                              String vanTypesFile, String carriersOut, int jspritIterations,
                              String serviceAreaShp, int maxTourDurationSeconds) {
    Config config = ConfigUtils.createConfig();
    config.network().setInputFile(networkFile);
    Scenario scenario = ScenarioUtils.loadScenario(config);
    Network network = carNetwork(scenario.getNetwork());
    ((MutableScenario) scenario).setNetwork(network);

    CarrierVehicleTypes capsuleTypes =
            hagrid.integrated.modular.ModularVehicleTypes.createCapsuleTypes(vanTypesFile);
    VehicleType[] capsuleArr = capsuleTypes.getVehicleTypes().values().toArray(new VehicleType[0]);

    Map<String, List<Delivery>> byProvider = LmdDemandReader.group(LmdDemandReader.read(demandShp));
    if (serviceAreaShp != null && !serviceAreaShp.isBlank()) {
        byProvider = clipToServiceArea(byProvider, serviceAreaShp);
    }
    Map<String, Id<Link>> depots = LmdDepotLoader.load(depotCsv, network);

    Carriers carriers = new Carriers();
    for (Map.Entry<String, List<Delivery>> e : byProvider.entrySet()) {
        String provider = e.getKey();
        Id<Link> depot = depots.get(provider);
        if (depot == null) {
            throw new IllegalStateException("No depot for provider with demand: " + provider);
        }
        Random missedRng = new Random(MISSED_DELIVERY_SEED + provider.hashCode());
        Carrier carrier = LmdCarrierBuilder.buildSingleWindow(provider, e.getValue(), depot,
                network, capsuleArr, DURATION_PER_PARCEL_MIN, MAX_DURATION_PER_STOP_MIN,
                missedRng,
                hagrid.integrated.modular.Modular.DELIVERY_DAY_START_S,
                hagrid.integrated.modular.Modular.DELIVERY_DAY_END_S,
                hagrid.integrated.modular.Modular.DELIVERY_DAY_START_S,
                hagrid.integrated.modular.Modular.DELIVERY_DAY_END_S);
        CarriersUtils.setJspritIterations(carrier, Math.max(1, jspritIterations));
        carriers.addCarrier(carrier);
    }

    routeWithDurationCap(carriers, network, capsuleTypes, jspritIterations, maxTourDurationSeconds);

    try {
        java.nio.file.Files.createDirectories(java.nio.file.Path.of(carriersOut).getParent());
    } catch (java.io.IOException e) {
        throw new IllegalStateException("Cannot create output directory for modular carriers: "
                + carriersOut, e);
    }
    CarriersUtils.writeCarriers(carriers, carriersOut);
}
```

- [ ] **Step 4: Run the tests**

Run: `mvn -q test -Dtest=ModularVehicleTypesTest,LausitzFreightPreprocessorTest` — expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/hagrid/integrated/modular/Modular.java src/main/java/hagrid/integrated/modular/ModularVehicleTypes.java src/main/java/hagrid/integrated/freight/LausitzFreightPreprocessor.java src/test/java/hagrid/integrated/modular/ModularVehicleTypesTest.java src/test/java/hagrid/integrated/freight/LausitzFreightPreprocessorTest.java
git commit -m "feat(modular): U-Shift cargo-capsule vehicle type + runModular jsprit preprocessing (Task 2)"
```

---

### Task 3: Modular core — constants, task types, custom tasks, commitment predicate, KPI event

**Files:**
- Modify: `src/main/java/hagrid/integrated/modular/Modular.java` (extend Task 2's seed)
- Create: `src/main/java/hagrid/integrated/modular/ModularFreightStopTask.java`
- Create: `src/main/java/hagrid/integrated/modular/ModularCapacityChangeTask.java`
- Create: `src/main/java/hagrid/integrated/modular/ModularTourEvent.java`
- Create: `src/main/java/hagrid/integrated/modular/ModularTourEventHandler.java`
- Test: `src/test/java/hagrid/integrated/modular/ModularTest.java`

**Interfaces:**
- Consumes: `DrtTaskType` (drt core), `DefaultStayTask`, `DefaultDrtCapacityChangeTask(double, double, Link, DvrpLoad)` (verified constructor, spike), `Schedule`/`Task` (dvrp), `Event`/`EventHandler` (core).
- Produces: everything in the canonical interface table rows for Task 3. `ModularTourEvent` attribute keys: `tourId`, `phase`, `vehicle`, `parcels`, `deadheadMeters`, `serviceMeters` (written into `getAttributes()` so they serialize into `output_events.xml.gz` and are assertable via `GenericEvent` in e2e tests).

- [ ] **Step 1: Write the failing tests**

```java
package hagrid.integrated.modular;
// imports: org.matsim.api.core.v01.Id, network Link/Node/NetworkUtils, dvrp fleet/schedule, drt schedule, assertj

class ModularTest {

    @Test
    @DisplayName("task types carry the DRT base types the timing machinery branches on")
    void taskTypeBaseTypes() {
        assertThat(Modular.FREIGHT_STOP_TASK_TYPE.getDrtBaseType())
                .contains(DrtTaskBaseType.STAY);
        assertThat(Modular.FREIGHT_DRIVE_TASK_TYPE.getDrtBaseType())
                .contains(DrtTaskBaseType.DRIVE);
    }

    @Test
    @DisplayName("freight stop task preserves intended duration + metadata")
    void freightStopTaskMetadata() {
        Link link = fixtureLink();
        ModularFreightStopTask t = new ModularFreightStopTask(100.0, 340.0, link, 2, "dhl_t0", 1);
        assertThat(t.getIntendedDuration()).isEqualTo(240.0);
        assertThat(t.getParcels()).isEqualTo(2);
        assertThat(t.getTourId()).isEqualTo("dhl_t0");
        assertThat(t.getStopIndex()).isEqualTo(1);
        assertThat(t.getTaskType()).isEqualTo(Modular.FREIGHT_STOP_TASK_TYPE);
    }

    @Test
    @DisplayName("capacity-change task: native swap + tour identity, intended duration = retooling")
    void capacityChangeTaskMetadata() {
        Link link = fixtureLink();
        DvrpLoad zero = new IntegerLoadType("passengers").getEmptyLoad();
        ModularCapacityChangeTask t = new ModularCapacityChangeTask(
                600.0, 600.0 + Modular.RETOOLING_S, link, zero, "dhl_t0", false);
        assertThat(t.getChangedCapacity()).isEqualTo(zero);
        assertThat(t.getIntendedDuration()).isEqualTo(Modular.RETOOLING_S);
        assertThat(t.isSwapBack()).isFalse();
        assertThat(t.getTourId()).isEqualTo("dhl_t0");
    }

    @Test
    @DisplayName("hasUnperformedFreightTask: true from dispatch until the swap-back is PERFORMED")
    void commitmentPredicate() {
        Link link = fixtureLink();
        DvrpVehicle vehicle = fixtureVehicle(link);        // schedule: one initial STAY 0..86400
        Schedule schedule = vehicle.getSchedule();
        schedule.nextTask();                                // PLANNED -> STARTED, current = STAY
        assertThat(Modular.hasUnperformedFreightTask(schedule)).isFalse();

        // splice a minimal freight tail: truncate stay, add freight drive + swap + trailing stay
        StayTask stay = (StayTask) schedule.getCurrentTask();
        stay.setEndTime(100.0);
        schedule.addTask(new DrtDriveTask(fixturePath(link, 100.0), Modular.FREIGHT_DRIVE_TASK_TYPE));
        double arr = schedule.getTasks().get(schedule.getTaskCount() - 1).getEndTime();
        schedule.addTask(new ModularCapacityChangeTask(arr, arr + Modular.RETOOLING_S, link,
                new IntegerLoadType("passengers").getEmptyLoad(), "t", true));
        schedule.addTask(new DrtStayTask(arr + Modular.RETOOLING_S, 86400.0, link));
        assertThat(Modular.hasUnperformedFreightTask(schedule)).isTrue();

        schedule.nextTask();  // drive running
        assertThat(Modular.hasUnperformedFreightTask(schedule)).isTrue();
        schedule.nextTask();  // swap running (drive PERFORMED)
        assertThat(Modular.hasUnperformedFreightTask(schedule)).isTrue();
        schedule.nextTask();  // trailing stay running (swap PERFORMED)
        assertThat(Modular.hasUnperformedFreightTask(schedule)).isFalse();
    }

    @Test
    @DisplayName("event round-trips its attributes")
    void eventAttributes() {
        ModularTourEvent e = ModularTourEvent.dispatched(3600.0, "dhl_t0",
                Id.create("drt_1", DvrpVehicle.class), 12, 2500.0, 4200.0);
        assertThat(e.getEventType()).isEqualTo(ModularTourEvent.EVENT_TYPE);
        assertThat(e.getAttributes())
                .containsEntry("tourId", "dhl_t0")
                .containsEntry("phase", "DISPATCHED")
                .containsEntry("vehicle", "drt_1")
                .containsEntry("parcels", "12")
                .containsEntry("deadheadMeters", "2500.0")
                .containsEntry("serviceMeters", "4200.0");
    }
}
```

Fixture helpers inside the test class: `fixtureLink()` builds a 2-node/1-link network via `NetworkUtils`; `fixtureVehicle(link)` builds a `DvrpVehicleImpl` from an `ImmutableDvrpVehicleSpecification` (serviceBeginTime 0, serviceEndTime 86400, capacity 10) and adds an initial `DrtStayTask(0, 86400, link)`; `fixturePath(link, depTime)` builds a trivial one-link `VrpPathWithTravelData` via `VrpPaths.createZeroLengthPathForDiversion` or a 2-link `VrpPaths.calcAndCreatePath` with a `FreeSpeedTravelTime` router (VERIFY-SOURCE: pick whichever constructs cheaply for a same-link "path"; `DrtDriveTask(VrpPathWithTravelData, DrtTaskType)` is the constructor).

- [ ] **Step 2: Run, verify compile failure**

Run: `mvn -q test -Dtest=ModularTest` — expected: FAIL (classes missing).

- [ ] **Step 3: Implement**

Extend `Modular`:

```java
package hagrid.integrated.modular;

import org.matsim.contrib.drt.schedule.DrtTaskType;
import org.matsim.contrib.dvrp.schedule.Schedule;
import org.matsim.contrib.dvrp.schedule.Task;

import static org.matsim.contrib.drt.schedule.DrtTaskBaseType.DRIVE;
import static org.matsim.contrib.drt.schedule.DrtTaskBaseType.STAY;

public final class Modular {

    public static final int CARGO_CAPACITY_PARCELS = 216;            // (javadoc from Task 2)
    public static final String CARGO_CAPSULE_TYPE_ID = "ushift_cargo_capsule";
    public static final double DELIVERY_DAY_START_S = 7.5 * 3600.0;  // (javadoc from Task 2)
    public static final double DELIVERY_DAY_END_S = 21 * 3600.0;

    /** Pure capsule-swap (retooling) duration, spec §6.1: 7 min. */
    public static final double RETOOLING_S = 420.0;
    /** Submission look-ahead base (spec §4.3): effective look-ahead = this + RETOOLING_S. */
    public static final double FREIGHT_LOOKAHEAD_S = 420.0;
    /** Passenger-first dispatch gate default (design D6 / spec §6.1). */
    public static final double DEFAULT_IDLE_THRESHOLD = 0.50;
    /** Tour-duration cap default: 3.5h concept parameter (design D5); 25200 = control arm. */
    public static final int DEFAULT_MAX_TOUR_DURATION_S = 12600;

    /** Freight stop = plain STAY-base task (design D7): parcels never touch the passenger engine. */
    public static final DrtTaskType FREIGHT_STOP_TASK_TYPE = new DrtTaskType("MODULAR_FREIGHT_STOP", STAY);
    /** Approach / inter-stop / return legs of a freight excursion. */
    public static final DrtTaskType FREIGHT_DRIVE_TASK_TYPE = new DrtTaskType("MODULAR_FREIGHT_DRIVE", DRIVE);

    private Modular() {}

    /**
     * TRUE while the schedule still holds any un-performed freight-excursion task. This is the
     * SINGLE commitment predicate (design D2 strict lockout) shared by ModularEntryFactory
     * (pax candidate exclusion) and ModularTourDispatcher (idle pool) - deliberately WIDER than
     * drt-extensions' current/one-before-last check, which is insufficient for multi-stop
     * tours (spike §3.3).
     */
    public static boolean hasUnperformedFreightTask(Schedule schedule) {
        return switch (schedule.getStatus()) {
            case PLANNED -> schedule.getTasks().stream().anyMatch(Modular::isFreightTask);
            case STARTED -> {
                int from = schedule.getCurrentTask().getTaskIdx();
                yield schedule.getTasks().stream()
                        .filter(t -> t.getTaskIdx() >= from)
                        .anyMatch(t -> isFreightTask(t) && t.getStatus() != Task.TaskStatus.PERFORMED);
            }
            default -> false; // UNPLANNED / COMPLETED
        };
    }

    private static boolean isFreightTask(Task t) {
        return t.getTaskType().equals(FREIGHT_STOP_TASK_TYPE)
                || t.getTaskType().equals(FREIGHT_DRIVE_TASK_TYPE)
                || t instanceof ModularCapacityChangeTask;
    }
}
```

`ModularFreightStopTask`:

```java
package hagrid.integrated.modular;

import org.matsim.api.core.v01.network.Link;
import org.matsim.contrib.dvrp.schedule.DefaultStayTask;

/**
 * A freight delivery dwell on a Modular excursion. Deliberately a PLAIN stay task, never a
 * DrtStopTask (design D7): parcels are not agents, the passenger engine never sees this stop,
 * and drt_customer_stats stays uncontaminated. Duration = jsprit service duration
 * (min(2min x parcels, 15min), LMD parity) x deliveryDwellFactor (1.0 in 1d; §4.4 autonomy hook).
 */
public final class ModularFreightStopTask extends DefaultStayTask {

    private final double intendedDuration;
    private final int parcels;
    private final String tourId;
    private final int stopIndex;

    public ModularFreightStopTask(double beginTime, double endTime, Link link,
                                  int parcels, String tourId, int stopIndex) {
        super(Modular.FREIGHT_STOP_TASK_TYPE, beginTime, endTime, link);
        this.intendedDuration = endTime - beginTime;
        this.parcels = parcels;
        this.tourId = tourId;
        this.stopIndex = stopIndex;
    }

    /** The dwell the excursion plan intends; the end-time calculator preserves it under delays. */
    public double getIntendedDuration() { return intendedDuration; }
    public int getParcels() { return parcels; }
    public String getTourId() { return tourId; }
    public int getStopIndex() { return stopIndex; }
}
```

`ModularCapacityChangeTask`:

```java
package hagrid.integrated.modular;

import org.matsim.api.core.v01.network.Link;
import org.matsim.contrib.drt.schedule.DefaultDrtCapacityChangeTask;
import org.matsim.contrib.dvrp.load.DvrpLoad;

/**
 * The capsule swap: the NATIVE drt-core capacity change (spike §1 headline - the swap IS a
 * DefaultDrtCapacityChangeTask; DrtActionCreator maps it to VehicleCapacityChangeActivity, and
 * InsertionGenerator re-reads getChangedCapacity() per stop), plus the tour identity the
 * dispatcher/KPI side needs (plan C6). Swap-out changes capacity to 0 passengers; swap-back
 * restores vehicle.getCapacity(). The 216-parcel cargo side is documentation (D8/C5).
 */
public final class ModularCapacityChangeTask extends DefaultDrtCapacityChangeTask {

    private final String tourId;
    private final boolean swapBack;

    public ModularCapacityChangeTask(double beginTime, double endTime, Link link,
                                     DvrpLoad changedCapacity, String tourId, boolean swapBack) {
        super(beginTime, endTime, link, changedCapacity);
        this.tourId = tourId;
        this.swapBack = swapBack;
    }

    /** Retooling is a fixed concept parameter - the intended duration under timing updates. */
    public double getIntendedDuration() { return Modular.RETOOLING_S; }
    public String getTourId() { return tourId; }
    /** false = swap-out (pax -> cargo) at excursion start; true = swap-back at the end. */
    public boolean isSwapBack() { return swapBack; }
}
```

`ModularTourEvent` + handler:

```java
package hagrid.integrated.modular;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.Event;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;

import java.util.Map;

/**
 * The ONE typed event of the Modular scenario, phase-tagged. Freight stops are plain stay
 * tasks and emit no native events (design D7), so tour accounting flows exclusively through
 * these events into ModularKpiHandler. Serialized attributes make e2e assertions possible on
 * output_events.xml.gz (read back as GenericEvent).
 */
public final class ModularTourEvent extends Event {

    public static final String EVENT_TYPE = "modularTour";

    public enum Phase { PLANNED, EXPIRED, DISPATCHED, SWAP_DONE, STOP_SERVED, COMPLETED }

    private final String tourId;
    private final Phase phase;
    private final Id<DvrpVehicle> vehicleId;   // null for PLANNED / EXPIRED
    private final int parcels;                 // tour total (PLANNED/EXPIRED/DISPATCHED) or stop count (STOP_SERVED); 0 otherwise
    private final double deadheadMeters;       // DISPATCHED only: approach + return legs
    private final double serviceMeters;        // DISPATCHED only: inter-stop legs

    private ModularTourEvent(double time, String tourId, Phase phase, Id<DvrpVehicle> vehicleId,
                             int parcels, double deadheadMeters, double serviceMeters) {
        super(time);
        this.tourId = tourId;
        this.phase = phase;
        this.vehicleId = vehicleId;
        this.parcels = parcels;
        this.deadheadMeters = deadheadMeters;
        this.serviceMeters = serviceMeters;
    }

    public static ModularTourEvent planned(double time, String tourId, int parcels) {
        return new ModularTourEvent(time, tourId, Phase.PLANNED, null, parcels, 0, 0);
    }
    public static ModularTourEvent expired(double time, String tourId, int parcels) {
        return new ModularTourEvent(time, tourId, Phase.EXPIRED, null, parcels, 0, 0);
    }
    public static ModularTourEvent dispatched(double time, String tourId, Id<DvrpVehicle> vehicle,
                                              int parcels, double deadheadMeters, double serviceMeters) {
        return new ModularTourEvent(time, tourId, Phase.DISPATCHED, vehicle, parcels,
                deadheadMeters, serviceMeters);
    }
    public static ModularTourEvent swapDone(double time, String tourId, Id<DvrpVehicle> vehicle) {
        return new ModularTourEvent(time, tourId, Phase.SWAP_DONE, vehicle, 0, 0, 0);
    }
    public static ModularTourEvent stopServed(double time, String tourId, Id<DvrpVehicle> vehicle,
                                              int parcels) {
        return new ModularTourEvent(time, tourId, Phase.STOP_SERVED, vehicle, parcels, 0, 0);
    }
    public static ModularTourEvent completed(double time, String tourId, Id<DvrpVehicle> vehicle) {
        return new ModularTourEvent(time, tourId, Phase.COMPLETED, vehicle, 0, 0, 0);
    }

    public String getTourId() { return tourId; }
    public Phase getPhase() { return phase; }
    public Id<DvrpVehicle> getVehicleId() { return vehicleId; }
    public int getParcels() { return parcels; }
    public double getDeadheadMeters() { return deadheadMeters; }
    public double getServiceMeters() { return serviceMeters; }

    @Override
    public String getEventType() { return EVENT_TYPE; }

    @Override
    public Map<String, String> getAttributes() {
        Map<String, String> attrs = super.getAttributes();
        attrs.put("tourId", tourId);
        attrs.put("phase", phase.name());
        if (vehicleId != null) attrs.put("vehicle", vehicleId.toString());
        attrs.put("parcels", Integer.toString(parcels));
        attrs.put("deadheadMeters", Double.toString(deadheadMeters));
        attrs.put("serviceMeters", Double.toString(serviceMeters));
        return attrs;
    }
}
```

```java
package hagrid.integrated.modular;

import org.matsim.core.events.handler.EventHandler;

/** Typed handler for {@link ModularTourEvent} (custom-event reflection dispatch, DVRP pattern). */
public interface ModularTourEventHandler extends EventHandler {
    void handleEvent(ModularTourEvent event);
}
```

(VERIFY-SOURCE: `DrtTaskType.getDrtBaseType()` accessor name — the record exposes the base type; adapt the first test's accessor if it's a plain component accessor like `baseType()`.)

- [ ] **Step 4: Run the tests**

Run: `mvn -q test -Dtest=ModularTest` — expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/hagrid/integrated/modular/ src/test/java/hagrid/integrated/modular/ModularTest.java
git commit -m "feat(modular): core task types, commitment predicate, tour KPI event (Task 3)"
```

---

### Task 4: `ModularFreightTour` + `ModularTourConverter` (jsprit plan → dispatchable tours)

**Files:**
- Create: `src/main/java/hagrid/integrated/modular/ModularFreightTour.java`
- Create: `src/main/java/hagrid/integrated/modular/ModularTourConverter.java`
- Test: `src/test/java/hagrid/integrated/modular/ModularTourConverterTest.java`

**Interfaces:**
- Consumes: `Carriers`/`Carrier`/`ScheduledTour`/`Tour.TourElement`/`Tour.Leg`/`Tour.ServiceActivity`/`CarrierService` (freight contrib), `NetworkUtils.getNearestLinkExactly`, Task 3's `Modular.FREIGHT_LOOKAHEAD_S`/`RETOOLING_S`.
- Produces: the `ModularFreightTour` record and `ModularTourConverter.convert(Carriers, Network carNetwork, Network drtNetwork)`; `read(String carriersFile, CarrierVehicleTypes types)`.

- [ ] **Step 1: Write the failing test**

Build a `Carriers` in memory (no file round-trip): one carrier `dhl` with one `ScheduledTour` of two services. Use the freight API (`Tour.Builder`) — VERIFY-SOURCE the exact builder methods (`Tour.Builder.newInstance(...)`, `.scheduleStart(Id<Link>)`, `.addLeg(...)`, `.scheduleService(CarrierService)`, `.scheduleEnd(Id<Link>)`, then `ScheduledTour.newInstance(tour, carrierVehicle, departureTime)`); the married e2e (`MarriedBaselineEndToEndTest.java:108-120`) shows the element traversal side.

```java
@Test
@DisplayName("converts a routed tour: id, times, latestEnd, stops snapped to the drt network")
void convertsScheduledTour() {
    // carNetwork: 2-node line a->b (links "car_1","car_2"); drtNetwork: DIFFERENT link ids
    // ("drt_1","drt_2") at the same coordinates -> forces the nearest-link snap path.
    Network carNet = buildCarNet();
    Network drtNet = buildDrtNet();

    CarrierService s1 = CarrierService.Builder
            .newInstance(Id.create("dhl_0", CarrierService.class), Id.createLinkId("car_1"))
            .setCapacityDemand(3).setServiceDuration(360.0).build();
    CarrierService s2 = CarrierService.Builder
            .newInstance(Id.create("dhl_1", CarrierService.class), Id.createLinkId("car_2"))
            .setCapacityDemand(2).setServiceDuration(240.0).build();
    Carriers carriers = fixtureCarriersWithOneTour("dhl", Id.createLinkId("car_1"),
            /*departure*/ 8 * 3600.0, /*vehicleLatestEnd*/ 17 * 3600.0, List.of(s1, s2));

    List<ModularFreightTour> tours = ModularTourConverter.convert(carriers, carNet, drtNet);

    assertThat(tours).hasSize(1);
    ModularFreightTour t = tours.get(0);
    assertThat(t.tourId()).isEqualTo("dhl_t0");                    // carrier + tour index, NO UUID
    assertThat(t.provider()).isEqualTo("dhl");
    assertThat(t.tourIndex()).isEqualTo(0);                        // C7 interleave key
    assertThat(t.plannedStart()).isEqualTo(8 * 3600.0);
    assertThat(t.latestEnd()).isEqualTo(17 * 3600.0);
    assertThat(t.totalParcels()).isEqualTo(5);
    assertThat(t.stops()).hasSize(2);
    assertThat(t.stops().get(0).serviceDuration()).isEqualTo(360.0);
    // every link id exists in the DRT network (snap worked)
    assertThat(drtNet.getLinks()).containsKey(t.depotLink());
    t.stops().forEach(s -> assertThat(drtNet.getLinks()).containsKey(s.link()));
    // plannedDuration = leg times + service durations
    assertThat(t.plannedDuration()).isGreaterThanOrEqualTo(600.0);
    // submission = plannedStart - (lookahead + retooling)
    assertThat(t.submissionTime())
            .isEqualTo(8 * 3600.0 - (Modular.FREIGHT_LOOKAHEAD_S + Modular.RETOOLING_S));
}

@Test
@DisplayName("deterministic order: carriers sorted by id, tours by plan order")
void deterministicTourOrder() { /* two carriers 'gls','dhl' one tour each ->
        converted list is [dhl_t0, gls_t0] regardless of Carriers iteration order */ }
```

- [ ] **Step 2: Run, verify failure**

Run: `mvn -q test -Dtest=ModularTourConverterTest` — expected: FAIL (classes missing).

- [ ] **Step 3: Implement**

```java
package hagrid.integrated.modular;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;

import java.util.List;

/**
 * One offline-planned freight tour, ready for online dispatch. All link ids are ALREADY
 * snapped to the DRT network (the jsprit side routes on the car network - different link set).
 * latestEnd is the jsprit vehicle's operating-window end = DELIVERY_DAY_END_S (21:00) for every
 * 1d tour (plan C4 revised: full delivery day, no waves; vehicle.getServiceEndTime() is 86400
 * and therefore vacuous as an envelope). tourIndex is the C7 interleave key.
 */
public record ModularFreightTour(String tourId, String provider, int tourIndex,
                                 Id<Link> depotLink, double plannedStart, double plannedDuration,
                                 double latestEnd, List<Stop> stops) {

    public record Stop(Id<Link> link, double serviceDuration, int parcels) {}

    public ModularFreightTour {
        stops = List.copyOf(stops);
        if (stops.isEmpty()) throw new IllegalArgumentException("tour without stops: " + tourId);
    }

    public int totalParcels() {
        return stops.stream().mapToInt(Stop::parcels).sum();
    }

    /** Spec §4.3: submission = plannedTourStart - (look-ahead base + retooling). */
    public double submissionTime() {
        return plannedStart - (Modular.FREIGHT_LOOKAHEAD_S + Modular.RETOOLING_S);
    }
}
```

```java
package hagrid.integrated.modular;
// imports: freight carriers API, Network/NetworkUtils, java.util

/**
 * Converts routed CarrierPlans (LausitzFreightPreprocessor.runModular output) into
 * ModularFreightTours. Deterministic: carriers sorted by id, tours indexed in plan order,
 * ids "<carrier>_t<i>" (spike §3.5: never UUIDs). Stop/depot links are snapped from the car
 * network onto the DRT network by coordinate (getNearestLinkExactly), mirroring
 * LausitzDrtPreprocessor's parcel-snap precedent.
 */
public final class ModularTourConverter {

    private ModularTourConverter() {}

    public static Carriers read(String carriersFile, CarrierVehicleTypes types) {
        Carriers carriers = new Carriers();
        new CarrierPlanXmlReader(carriers, types).readFile(carriersFile);
        return carriers;
    }

    public static List<ModularFreightTour> convert(Carriers carriers, Network carNetwork,
                                                   Network drtNetwork) {
        List<ModularFreightTour> tours = new ArrayList<>();
        carriers.getCarriers().values().stream()
                .sorted(Comparator.comparing(c -> c.getId().toString()))
                .forEach(carrier -> {
                    if (carrier.getSelectedPlan() == null) return;
                    int i = 0;
                    for (ScheduledTour st : carrier.getSelectedPlan().getScheduledTours()) {
                        tours.add(toModularTour(carrier.getId().toString(), i++, st,
                                carNetwork, drtNetwork));
                    }
                });
        return tours;
    }

    private static ModularFreightTour toModularTour(String carrierId, int index, ScheduledTour st,
                                                    Network carNetwork, Network drtNetwork) {
        List<ModularFreightTour.Stop> stops = new ArrayList<>();
        double duration = 0.0;
        for (Tour.TourElement el : st.getTour().getTourElements()) {
            if (el instanceof Tour.Leg leg) {
                duration += leg.getExpectedTransportTime();
            } else if (el instanceof Tour.ServiceActivity act) {
                CarrierService service = act.getService();
                duration += service.getServiceDuration();
                stops.add(new ModularFreightTour.Stop(
                        toDrtLink(service.getServiceLinkId(), carNetwork, drtNetwork),
                        service.getServiceDuration(),
                        service.getCapacityDemand()));
            }
        }
        return new ModularFreightTour(
                carrierId + "_t" + index,
                carrierId,
                index,
                toDrtLink(st.getVehicle().getLinkId(), carNetwork, drtNetwork),
                st.getDeparture(),
                duration,
                st.getVehicle().getLatestEndTime(),
                stops);
    }

    private static Id<Link> toDrtLink(Id<Link> carLinkId, Network carNetwork, Network drtNetwork) {
        if (drtNetwork.getLinks().containsKey(carLinkId)) return carLinkId;
        Link carLink = carNetwork.getLinks().get(carLinkId);
        if (carLink == null) {
            throw new IllegalStateException("Tour link " + carLinkId + " in neither network");
        }
        return NetworkUtils.getNearestLinkExactly(drtNetwork, carLink.getToNode().getCoord()).getId();
    }
}
```

(VERIFY-SOURCE: `CarrierService.getServiceLinkId()` vs `getLocationLinkId()`, `Tour.ServiceActivity.getService()`, `getCapacityDemand()` — check `Tour`/`CarrierService` in the freight contrib sources; the married e2e proves `Tour.Leg`/`getTourElements()`.)

- [ ] **Step 4: Run the tests** — `mvn -q test -Dtest=ModularTourConverterTest` — expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/hagrid/integrated/modular/ModularFreightTour.java src/main/java/hagrid/integrated/modular/ModularTourConverter.java src/test/java/hagrid/integrated/modular/ModularTourConverterTest.java
git commit -m "feat(modular): jsprit plan -> dispatchable ModularFreightTours, drt-net snapped (Task 4)"
```

---

### Task 5: `ModularStayTaskEndTimeCalculator` (the mandatory silent-deletion guard)

**Files:**
- Create: `src/main/java/hagrid/integrated/modular/ModularStayTaskEndTimeCalculator.java`
- Test: `src/test/java/hagrid/integrated/modular/ModularStayTaskEndTimeCalculatorTest.java`

**Interfaces:**
- Consumes: `ScheduleTimingUpdater.StayTaskEndTimeCalculator` (interface: `double calcNewEndTime(DvrpVehicle, StayTask, double newBeginTime)`), Task 3's tasks.
- Produces: the decorator that Task 10 wires into a rebuilt `ScheduleTimingUpdater`.

- [ ] **Step 1: Write the failing test**

```java
class ModularStayTaskEndTimeCalculatorTest {

    private final ScheduleTimingUpdater.StayTaskEndTimeCalculator delegate =
            (vehicle, task, newBeginTime) -> 12345.0;   // sentinel: "delegated"

    private final ModularStayTaskEndTimeCalculator calc =
            new ModularStayTaskEndTimeCalculator(delegate);

    @Test
    @DisplayName("delayed freight stop keeps its intended duration (native branch would DELETE it)")
    void freightStopShiftPreservesDuration() {
        ModularFreightStopTask stop = new ModularFreightStopTask(1000.0, 1240.0, link(), 2, "t", 0);
        // upstream delay pushes begin past the old end (1240) - native STAY branch: REMOVE_STAY_TASK
        assertThat(calc.calcNewEndTime(null, stop, 1300.0)).isEqualTo(1300.0 + 240.0);
    }

    @Test
    @DisplayName("delayed swap keeps the 7-min retooling (native STOP branch would recompute via pax calculator)")
    void capacityChangeShiftPreservesRetooling() {
        ModularCapacityChangeTask swap = new ModularCapacityChangeTask(1000.0,
                1000.0 + Modular.RETOOLING_S, link(),
                new IntegerLoadType("passengers").getEmptyLoad(), "t", false);
        assertThat(calc.calcNewEndTime(null, swap, 2000.0)).isEqualTo(2000.0 + Modular.RETOOLING_S);
    }

    @Test
    @DisplayName("all other stay tasks delegate untouched")
    void delegatesEverythingElse() {
        DrtStayTask stay = new DrtStayTask(0.0, 100.0, link());
        assertThat(calc.calcNewEndTime(null, stay, 50.0)).isEqualTo(12345.0);
    }
}
```

- [ ] **Step 2: Run, verify failure** — `mvn -q test -Dtest=ModularStayTaskEndTimeCalculatorTest` — FAIL (class missing).

- [ ] **Step 3: Implement**

```java
package hagrid.integrated.modular;

import org.matsim.contrib.dvrp.fleet.DvrpVehicle;
import org.matsim.contrib.dvrp.schedule.ScheduleTimingUpdater;
import org.matsim.contrib.dvrp.schedule.StayTask;

/**
 * MANDATORY (spike §3.1): without this decorator the core timing update silently DELETES a
 * delayed freight dwell (STAY branch returns REMOVE_STAY_TASK once oldEnd <= newBegin) and
 * silently SHRINKS a shifted capsule swap (STOP branch recomputes a DrtCapacityChangeTask's
 * duration via the PASSENGER stop-time calculator - empty pickup/dropoff sets -> generic
 * stopDuration instead of 7min retooling). Both failure modes are wrong timings without any
 * exception. Pattern: ShiftDrtStayTaskEndTimeCalculator (drt-extensions/operations, template).
 * Belt 2 (enforceIntendedDuration in the optimizer, Task 8) re-asserts the same durations.
 */
public final class ModularStayTaskEndTimeCalculator
        implements ScheduleTimingUpdater.StayTaskEndTimeCalculator {

    private final ScheduleTimingUpdater.StayTaskEndTimeCalculator delegate;

    public ModularStayTaskEndTimeCalculator(ScheduleTimingUpdater.StayTaskEndTimeCalculator delegate) {
        this.delegate = delegate;
    }

    @Override
    public double calcNewEndTime(DvrpVehicle vehicle, StayTask task, double newBeginTime) {
        if (task instanceof ModularFreightStopTask stop) {
            return newBeginTime + stop.getIntendedDuration();
        }
        if (task instanceof ModularCapacityChangeTask swap) {
            return newBeginTime + swap.getIntendedDuration();
        }
        return delegate.calcNewEndTime(vehicle, task, newBeginTime);
    }
}
```

- [ ] **Step 4: Run the test** — expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/hagrid/integrated/modular/ModularStayTaskEndTimeCalculator.java src/test/java/hagrid/integrated/modular/ModularStayTaskEndTimeCalculatorTest.java
git commit -m "feat(modular): end-time calculator decorator - silent stay-deletion guard (Task 5)"
```

---

### Task 6: `ModularTourScheduler` — the schedule splicer

**Files:**
- Create: `src/main/java/hagrid/integrated/modular/ModularTourScheduler.java`
- Test: `src/test/java/hagrid/integrated/modular/ModularTourSchedulerTest.java`

**Interfaces:**
- Consumes: Task 3 tasks/constants, Task 4 `ModularFreightTour`; dvrp `VrpPaths.calcAndCreatePath(Link, Link, double, LeastCostPathCalculator, TravelTime)`, `DrtTaskFactory.createDriveTask/createStayTask`, `DvrpLoadType.getEmptyLoad()`, `DvrpVehicle.getCapacity()`.
- Produces: `Optional<ScheduledExcursion> schedule(DvrpVehicle vehicle, ModularFreightTour tour, double now)`; record `ScheduledExcursion(double deadheadMeters, double serviceMeters, double plannedCompletion)`.

- [ ] **Step 1: Write the failing tests**

Fixture: a 4-node line network (all links drt-routable), a `DvrpVehicleImpl` with initial `DrtStayTask(0, 86400, startLink)` started via `schedule.nextTask()`, router = `new SpeedyALTFactory().createPathCalculator(network, new TimeAsTravelDisutility(new FreeSpeedTravelTime()), new FreeSpeedTravelTime())` (VERIFY-SOURCE: `TimeAsTravelDisutility` ctor), `DrtTaskFactory taskFactory = new DrtTaskFactoryImpl()`, `DvrpLoadType loadType = new IntegerLoadType("passengers")`.

```java
@Test
@DisplayName("splices the full excursion chain and keeps the spike §2 invariants")
void splicesFullChain() {
    ModularFreightTour tour = tour(depotLink, /*latestEnd*/ 21 * 3600.0,
            stop(stopLink1, 240.0, 2), stop(stopLink2, 360.0, 3));

    Optional<ModularTourScheduler.ScheduledExcursion> result =
            scheduler.schedule(vehicle, tour, /*now*/ 8 * 3600.0);

    assertThat(result).isPresent();
    List<? extends Task> tasks = vehicle.getSchedule().getTasks();
    // [0] initial STAY truncated to now (invariant: currently-executing trailing STAY is
    //     truncated, not removed)
    assertThat(tasks.get(0).getEndTime()).isEqualTo(8 * 3600.0);
    // chain: drive - swapOut - drive - stop1 - drive - stop2 - drive - swapBack - STAY
    assertThat(tasks.get(1).getTaskType()).isEqualTo(Modular.FREIGHT_DRIVE_TASK_TYPE);
    assertThat(tasks.get(2)).isInstanceOf(ModularCapacityChangeTask.class);
    assertThat(((ModularCapacityChangeTask) tasks.get(2)).isSwapBack()).isFalse();
    assertThat(((ModularCapacityChangeTask) tasks.get(2)).getChangedCapacity())
            .isEqualTo(loadType.getEmptyLoad());              // 0 seats during cargo phase
    assertThat(tasks.get(3).getTaskType()).isEqualTo(Modular.FREIGHT_DRIVE_TASK_TYPE);
    assertThat(tasks.get(4)).isInstanceOf(ModularFreightStopTask.class);
    assertThat(((ModularFreightStopTask) tasks.get(4)).getParcels()).isEqualTo(2);
    assertThat(tasks.get(6)).isInstanceOf(ModularFreightStopTask.class);
    assertThat(tasks.get(7).getTaskType()).isEqualTo(Modular.FREIGHT_DRIVE_TASK_TYPE);
    ModularCapacityChangeTask swapBack = (ModularCapacityChangeTask) tasks.get(8);
    assertThat(swapBack.isSwapBack()).isTrue();
    assertThat(swapBack.getChangedCapacity()).isEqualTo(vehicle.getCapacity()); // 10 seats restored
    // invariant: schedule ends with STAY reaching serviceEndTime
    Task last = tasks.get(tasks.size() - 1);
    assertThat(last).isInstanceOf(DrtStayTask.class);
    assertThat(last.getEndTime()).isEqualTo(vehicle.getServiceEndTime());
    // swap durations
    assertThat(tasks.get(2).getEndTime() - tasks.get(2).getBeginTime())
            .isEqualTo(Modular.RETOOLING_S);
    // stop dwell = jsprit service duration
    assertThat(tasks.get(4).getEndTime() - tasks.get(4).getBeginTime()).isEqualTo(240.0);
    // commitment predicate flips
    assertThat(Modular.hasUnperformedFreightTask(vehicle.getSchedule())).isTrue();
}

@Test
@DisplayName("REJECTS (returns empty, schedule untouched) when completion would overrun the envelope")
void rejectsOverrun() {
    // latestEnd barely after now -> even immediate dispatch cannot finish
    ModularFreightTour tour = tour(depotLink, /*latestEnd*/ 8 * 3600.0 + 60.0,
            stop(stopLink1, 240.0, 2));
    int before = vehicle.getSchedule().getTaskCount();

    assertThat(scheduler.schedule(vehicle, tour, 8 * 3600.0)).isEmpty();
    // NOT the native silent-drop: the schedule must be COMPLETELY unmodified
    assertThat(vehicle.getSchedule().getTaskCount()).isEqualTo(before);
    assertThat(vehicle.getSchedule().getTasks().get(0).getEndTime())
            .isEqualTo(vehicle.getServiceEndTime());
    assertThat(Modular.hasUnperformedFreightTask(vehicle.getSchedule())).isFalse();
}

@Test
@DisplayName("vehicle already at the depot link: no zero-length approach drive is inserted")
void vehicleAlreadyAtDepot() { /* start vehicle ON depotLink; expect chain to begin directly
        with the swap-out task (no leading FREIGHT_DRIVE task); same trailing invariants */ }

@Test
@DisplayName("returned excursion carries the planned km split (deadhead vs service)")
void excursionKmSplit() { /* deadheadMeters = approach+return path distances > 0;
        serviceMeters = inter-stop distances > 0; plannedCompletion = swap-back end time */ }
```

- [ ] **Step 2: Run, verify failure** — `mvn -q test -Dtest=ModularTourSchedulerTest` — FAIL.

- [ ] **Step 3: Implement**

```java
package hagrid.integrated.modular;
// imports: dvrp path/schedule/fleet/load, drt schedule, matsim network/router, java.util

/**
 * Splices a freight excursion onto a live DRT schedule at dispatch time - the generalisation
 * of drt-extensions' ServiceTaskSchedulerImpl from 1 stop to N (spike §2). Invariants held:
 * (1) a DRT schedule always ends with a STAY task; (2) the currently-executing trailing STAY
 * is TRUNCATED, a pending one REMOVED; (3) never schedule past the completion envelope - and
 * unlike the native template, infeasibility is an EXPLICIT Optional.empty() with the schedule
 * untouched, never a silent no-op (spike §2 invariant + design §3.3).
 *
 * Precondition (plan C3): the vehicle is IDLE (current task == trailing STAY). The dispatcher
 * only selects idle vehicles; the assert makes a violated assumption loud instead of corrupt.
 */
public class ModularTourScheduler {

    /** Planned-km split of one dispatched excursion (KPI payload for the DISPATCHED event). */
    public record ScheduledExcursion(double deadheadMeters, double serviceMeters,
                                     double plannedCompletion) {}

    private final Network network;
    private final TravelTime travelTime;
    private final LeastCostPathCalculator router;
    private final DrtTaskFactory taskFactory;
    private final DvrpLoadType loadType;

    public ModularTourScheduler(Network network, TravelTime travelTime,
                                TravelDisutility travelDisutility,
                                DrtTaskFactory taskFactory, DvrpLoadType loadType) {
        this.network = network;
        this.travelTime = travelTime;
        this.router = new SpeedyALTFactory().createPathCalculator(network, travelDisutility, travelTime);
        this.taskFactory = taskFactory;
        this.loadType = loadType;
    }

    public Optional<ScheduledExcursion> schedule(DvrpVehicle vehicle, ModularFreightTour tour,
                                                 double now) {
        Schedule schedule = vehicle.getSchedule();
        Task last = Schedules.getLastTask(schedule);
        if (!(last instanceof StayTask lastStay)) {
            throw new IllegalStateException("DRT schedule must end with STAY: " + vehicle.getId());
        }
        boolean stayIsCurrent = schedule.getStatus() == Schedule.ScheduleStatus.STARTED
                && schedule.getCurrentTask() == lastStay;
        // C3 precondition: dispatcher hands us idle vehicles only
        if (!stayIsCurrent) {
            throw new IllegalStateException("Modular dispatch expects an idle vehicle (trailing"
                    + " STAY running), got " + schedule.getCurrentTask() + " on " + vehicle.getId());
        }

        Link depot = network.getLinks().get(tour.depotLink());
        double departure = Math.max(lastStay.getBeginTime(), now);

        // ---- 1. route the ENTIRE chain first: feasibility before any mutation ----
        List<VrpPathWithTravelData> paths = new ArrayList<>();
        double deadhead = 0.0;
        double service = 0.0;

        Link from = lastStay.getLink();
        double t = departure;
        VrpPathWithTravelData approach = null;
        if (from != depot) {
            approach = VrpPaths.calcAndCreatePath(from, depot, t, router, travelTime);
            t = approach.getArrivalTime();
            deadhead += pathDistance(approach);
        }
        double swapOutBegin = t;
        t += Modular.RETOOLING_S;

        Link prev = depot;
        List<VrpPathWithTravelData> stopLegs = new ArrayList<>();
        for (ModularFreightTour.Stop stop : tour.stops()) {
            Link stopLink = network.getLinks().get(stop.link());
            VrpPathWithTravelData leg = VrpPaths.calcAndCreatePath(prev, stopLink, t, router, travelTime);
            stopLegs.add(leg);
            service += pathDistance(leg);
            t = leg.getArrivalTime() + stop.serviceDuration();
            prev = stopLink;
        }
        VrpPathWithTravelData back = VrpPaths.calcAndCreatePath(prev, depot, t, router, travelTime);
        deadhead += pathDistance(back);
        double swapBackBegin = back.getArrivalTime();
        double completion = swapBackBegin + Modular.RETOOLING_S;

        double envelope = Math.min(tour.latestEnd(), vehicle.getServiceEndTime());
        if (completion > envelope) {
            return Optional.empty();      // explicit reject - schedule untouched
        }

        // ---- 2. mutate: truncate the running trailing STAY, append the chain ----
        lastStay.setEndTime(departure);

        if (approach != null) {
            schedule.addTask(taskFactory.createDriveTask(vehicle, approach,
                    Modular.FREIGHT_DRIVE_TASK_TYPE));
        }
        schedule.addTask(new ModularCapacityChangeTask(swapOutBegin,
                swapOutBegin + Modular.RETOOLING_S, depot, loadType.getEmptyLoad(),
                tour.tourId(), false));

        double cursor = swapOutBegin + Modular.RETOOLING_S;
        for (int i = 0; i < tour.stops().size(); i++) {
            ModularFreightTour.Stop stop = tour.stops().get(i);
            VrpPathWithTravelData leg = stopLegs.get(i);
            schedule.addTask(taskFactory.createDriveTask(vehicle, leg,
                    Modular.FREIGHT_DRIVE_TASK_TYPE));
            double arrive = leg.getArrivalTime();
            schedule.addTask(new ModularFreightStopTask(arrive, arrive + stop.serviceDuration(),
                    network.getLinks().get(stop.link()), stop.parcels(), tour.tourId(), i));
            cursor = arrive + stop.serviceDuration();
        }
        schedule.addTask(taskFactory.createDriveTask(vehicle, back, Modular.FREIGHT_DRIVE_TASK_TYPE));
        schedule.addTask(new ModularCapacityChangeTask(swapBackBegin, completion, depot,
                vehicle.getCapacity(), tour.tourId(), true));
        schedule.addTask(taskFactory.createStayTask(vehicle, completion,
                Math.max(vehicle.getServiceEndTime(), completion), depot));

        return Optional.of(new ScheduledExcursion(deadhead, service, completion));
    }

    private static double pathDistance(VrpPathWithTravelData path) {
        // VERIFY-SOURCE: prefer VrpPaths.calcDistance(path) if present in 2025.0; else sum
        // link lengths per VrpPaths' first/last-link convention.
        return VrpPaths.calcDistance(path);
    }
}
```

Note for the executor: the same-link case (`from == depot`) starts the chain directly with the swap (test 3); the truncated STAY's `setEndTime(departure)` with `departure == now` matches the template's `task.setEndTime(timer.getTimeOfDay())`.

- [ ] **Step 4: Run the tests** — expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/hagrid/integrated/modular/ModularTourScheduler.java src/test/java/hagrid/integrated/modular/ModularTourSchedulerTest.java
git commit -m "feat(modular): schedule splicer - excursion chain with explicit feasibility (Task 6)"
```

---

### Task 7: `ModularTourDispatcher` — gate, selection, expiry, δ events

**Files:**
- Create: `src/main/java/hagrid/integrated/modular/ModularTourDispatcher.java`
- Test: `src/test/java/hagrid/integrated/modular/ModularTourDispatcherTest.java`

**Interfaces:**
- Consumes: Tasks 3/4/6; `Fleet` (dvrp), `DrtScheduleInquiry.isIdle`, `EventsManager`.
- Produces: `void dispatch(double now)` (called every simstep by Task 8's optimizer, BEFORE the delegate); `void observeTaskTransition(DvrpVehicle vehicle, Task previous, double now)` (called from `nextTask` when the current task changed).

- [ ] **Step 1: Write the failing tests**

Use a fake `Fleet` (map of 2-3 fixture vehicles as in Task 6's test), a `DrtScheduleInquiry` built with a settable `MobsimTimer` (VERIFY-SOURCE: `new MobsimTimer(1.0)` + `setTime(double)`; if the constructor differs, wrap time behind the test's own inquiry stub — `DrtScheduleInquiry` is a final concrete class, so alternatively construct it with the real timer and `timer.setTime(now)` before each call), the real `ModularTourScheduler` from Task 6, and `EventsUtils.createEventsManager()` with a recording `ModularTourEventHandler`.

```java
@Test
@DisplayName("gate: dispatches only while idleShare > threshold (strict), theta=1.0 never opens")
void gateRespectsThreshold() {
    // fleet of 2, both idle -> share 1.0
    dispatcherWithThreshold(1.0).dispatch(t0);
    assertThat(recorded(Phase.DISPATCHED)).isEmpty();          // 1.0 > 1.0 is false -> control arm

    dispatcherWithThreshold(0.4).dispatch(t0);
    // after the FIRST dispatch share drops to 1/2=0.5 > 0.4 -> second tour also dispatches;
    // after the second, share 0/2=0 -> stop. Assert exactly 2 DISPATCHED.
    assertThat(recorded(Phase.DISPATCHED)).hasSize(2);
}

@Test
@DisplayName("activation fires PLANNED at submissionTime, not before")
void plannedAtSubmissionTime() {
    dispatcher.dispatch(tour.submissionTime() - 1.0);
    assertThat(recorded(Phase.PLANNED)).isEmpty();
    dispatcher.dispatch(tour.submissionTime());
    assertThat(recorded(Phase.PLANNED)).hasSize(1);
}

@Test
@DisplayName("expiry: pending tour EXPIREs when immediate dispatch could no longer finish by latestEnd")
void expiresWhenEnvelopePasses() {
    // now such that now + 2*RETOOLING + plannedDuration > latestEnd
    dispatcher.dispatch(lateNow);
    assertThat(recorded(Phase.EXPIRED)).extracting(ModularTourEvent::getTourId)
            .containsExactly("dhl_t0");
    // an expired tour is gone: later gate-open ticks do not dispatch it (no replanning, spec §4.3)
    dispatcher.dispatch(lateNow + 60.0);
    assertThat(recorded(Phase.DISPATCHED)).isEmpty();
}

@Test
@DisplayName("vehicle selection: nearest idle to the depot, deterministic id tie-break")
void nearestIdleSelection() {
    // veh A on a link near the depot, veh B far -> A gets the tour
    dispatcher.dispatch(t0);
    assertThat(recorded(Phase.DISPATCHED).get(0).getVehicleId().toString()).isEqualTo("vehA");
}

@Test
@DisplayName("equal submission: providers interleave (dhl_t0, gls_t0, ...), no alphabetical block (C7)")
void providerInterleaving() { /* three tours dhl_t0, dhl_t1, gls_t0 with IDENTICAL submissionTime,
        fleet/threshold sized so exactly 2 dispatches fit -> DISPATCHED tour ids are
        [dhl_t0, gls_t0], NOT [dhl_t0, dhl_t1] */ }

@Test
@DisplayName("committed vehicles leave the idle pool (commitment predicate, not bookkeeping)")
void committedVehicleExcluded() { /* dispatch tour1 to the only near vehicle; a second tour in
        the same tick must go to the far vehicle or stay pending - assert the spliced vehicle
        is never double-booked */ }

@Test
@DisplayName("observeTaskTransition: performed stop -> STOP_SERVED; swap-back -> SWAP_DONE + COMPLETED")
void taskTransitionEvents() {
    // walk the spliced schedule with schedule.nextTask() and feed the PREVIOUS task into
    // observeTaskTransition after each step; assert per-phase events with correct parcels
}
```

- [ ] **Step 2: Run, verify failure** — FAIL (class missing).

- [ ] **Step 3: Implement**

```java
package hagrid.integrated.modular;
// imports: dvrp fleet, drt scheduler, matsim events/network, java.util

/**
 * Online freight dispatcher (design §3.3): passenger-primary via the idle-share gate.
 * QSim-scoped -> all state resets per iteration by construction (the 1c dd34b23 lesson).
 *
 * Tick order (ModularOptimizer calls dispatch() BEFORE the delegate's rebalancing runs, so a
 * just-spliced vehicle is already non-idle when MinCostFlow/ReturnToDepot look for vehicles):
 *   1. activate tours whose submissionTime has arrived (PLANNED event),
 *   2. expire pending tours whose completion envelope has passed (EXPIRED - explicit
 *      reject-and-log, never the native silent drop; no replanning, spec §4.3 step 5),
 *   3. while idleShare > idleThreshold (STRICT - theta=1.0 is the never-dispatch control arm):
 *      dispatch the longest-pending tour to the idle vehicle nearest its depot
 *      (deterministic tie-break by vehicle id). Pending order is (submissionTime, tourIndex,
 *      provider) - plan C7: with the day window ALL tours become pending at ~07:16, so a plain
 *      tourId sort would dispatch every dhl tour before the first gls tour and bias per-provider
 *      delta; interleaving by tour index removes that.
 */
public class ModularTourDispatcher {

    private static final Logger LOG = LogManager.getLogger(ModularTourDispatcher.class);

    private final String mode;
    private final List<ModularFreightTour> tours;      // sorted by (submissionTime, tourIndex, provider) - C7
    private final double idleThreshold;
    private final Fleet fleet;
    private final DrtScheduleInquiry scheduleInquiry;
    private final ModularTourScheduler scheduler;
    private final Network network;
    private final EventsManager events;

    private int nextToActivate = 0;
    private final List<ModularFreightTour> pending = new ArrayList<>();

    public ModularTourDispatcher(String mode, List<ModularFreightTour> tours, double idleThreshold,
                                 Fleet fleet, DrtScheduleInquiry scheduleInquiry,
                                 ModularTourScheduler scheduler, Network network,
                                 EventsManager events) {
        this.mode = mode;
        this.tours = tours.stream()
                .sorted(Comparator.comparingDouble(ModularFreightTour::submissionTime)
                        .thenComparingInt(ModularFreightTour::tourIndex)     // C7 interleave
                        .thenComparing(ModularFreightTour::provider))
                .toList();
        this.idleThreshold = idleThreshold;
        this.fleet = fleet;
        this.scheduleInquiry = scheduleInquiry;
        this.scheduler = scheduler;
        this.network = network;
        this.events = events;
    }

    public void dispatch(double now) {
        while (nextToActivate < tours.size()
                && tours.get(nextToActivate).submissionTime() <= now) {
            ModularFreightTour t = tours.get(nextToActivate++);
            pending.add(t);
            events.processEvent(ModularTourEvent.planned(now, t.tourId(), t.totalParcels()));
        }
        if (pending.isEmpty()) return;

        // C4 envelope: even an immediate dispatch (approach ~0) could not finish anymore
        pending.removeIf(t -> {
            if (now + 2 * Modular.RETOOLING_S + t.plannedDuration() > t.latestEnd()) {
                LOG.warn("Modular tour {} expired pending at {} (latestEnd {}).",
                        t.tourId(), now, t.latestEnd());
                events.processEvent(ModularTourEvent.expired(now, t.tourId(), t.totalParcels()));
                return true;
            }
            return false;
        });
        if (pending.isEmpty()) return;

        List<DvrpVehicle> idle = fleet.getVehicles().values().stream()
                .filter(scheduleInquiry::isIdle)
                .filter(v -> !Modular.hasUnperformedFreightTask(v.getSchedule()))
                .sorted(Comparator.comparing(v -> v.getId().toString()))
                .collect(Collectors.toCollection(ArrayList::new));
        int fleetSize = fleet.getVehicles().size();

        Iterator<ModularFreightTour> it = pending.iterator();
        while (it.hasNext() && !idle.isEmpty()
                && (double) idle.size() / fleetSize > idleThreshold) {
            ModularFreightTour tour = it.next();
            DvrpVehicle vehicle = nearestToDepot(idle, tour);
            Optional<ModularTourScheduler.ScheduledExcursion> excursion =
                    scheduler.schedule(vehicle, tour, now);
            if (excursion.isPresent()) {
                idle.remove(vehicle);
                it.remove();
                events.processEvent(ModularTourEvent.dispatched(now, tour.tourId(),
                        vehicle.getId(), tour.totalParcels(),
                        excursion.get().deadheadMeters(), excursion.get().serviceMeters()));
            }
            // infeasible for the nearest vehicle -> stays pending; expiry (above) is the exit
        }
    }

    /** Feed from ModularOptimizer.nextTask: previous = the task just PERFORMED. */
    public void observeTaskTransition(DvrpVehicle vehicle, Task previous, double now) {
        if (previous instanceof ModularFreightStopTask stop) {
            events.processEvent(ModularTourEvent.stopServed(now, stop.getTourId(),
                    vehicle.getId(), stop.getParcels()));
        } else if (previous instanceof ModularCapacityChangeTask swap) {
            events.processEvent(ModularTourEvent.swapDone(now, swap.getTourId(), vehicle.getId()));
            if (swap.isSwapBack()) {
                events.processEvent(ModularTourEvent.completed(now, swap.getTourId(),
                        vehicle.getId()));
            }
        }
    }

    private DvrpVehicle nearestToDepot(List<DvrpVehicle> idle, ModularFreightTour tour) {
        Coord depot = network.getLinks().get(tour.depotLink()).getToNode().getCoord();
        DvrpVehicle best = null;
        double bestDist = Double.POSITIVE_INFINITY;
        for (DvrpVehicle v : idle) {   // idle is id-sorted -> '<' keeps the smallest id on ties
            StayTask stay = (StayTask) v.getSchedule().getCurrentTask();
            double d = CoordUtils.calcEuclideanDistance(
                    stay.getLink().getToNode().getCoord(), depot);
            if (d < bestDist) {
                bestDist = d;
                best = v;
            }
        }
        return best;
    }
}
```

- [ ] **Step 4: Run the tests** — expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/hagrid/integrated/modular/ModularTourDispatcher.java src/test/java/hagrid/integrated/modular/ModularTourDispatcherTest.java
git commit -m "feat(modular): idle-gated dispatcher - activation, expiry, nearest-idle selection (Task 7)"
```

---

### Task 8: `ModularOptimizer` + `ModularEntryFactory`

**Files:**
- Create: `src/main/java/hagrid/integrated/modular/ModularOptimizer.java`
- Create: `src/main/java/hagrid/integrated/modular/ModularEntryFactory.java`
- Test: `src/test/java/hagrid/integrated/modular/ModularOptimizerTest.java`
- Test: `src/test/java/hagrid/integrated/modular/ModularEntryFactoryTest.java`

**Interfaces:**
- Consumes: `DrtOptimizer` (= `VrpOptimizer` + `MobsimBeforeSimStepListener`), `VehicleEntry.EntryFactory`, `ScheduleTimingUpdater.updateBeforeNextTask/updateTimingsStartingFromTaskIdx`, `MobsimTimer`, Tasks 3/7.
- Produces: the two decorators Task 10 binds.

- [ ] **Step 1: Write the failing tests**

`ModularEntryFactoryTest`:

```java
@Test
@DisplayName("returns null for a freight-committed vehicle (D2 strict lockout), delegates otherwise")
void lockoutViaCommitmentPredicate() {
    VehicleEntry sentinel = mock-or-null-object;  // a delegate returning a canned VehicleEntry
    ModularEntryFactory factory = new ModularEntryFactory((vehicle, time) -> sentinel);

    DvrpVehicle idle = fixtureVehicleIdle();                 // as in Task 3's test
    assertThat(factory.create(idle, 0.0)).isSameAs(sentinel);

    DvrpVehicle committed = fixtureVehicleWithSplicedTour(); // reuse Task 6's splice fixture
    assertThat(factory.create(committed, 0.0)).isNull();

    // walk the schedule to completion -> vehicle re-enters the pax candidate set
    performAllFreightTasks(committed);
    assertThat(factory.create(committed, 0.0)).isSameAs(sentinel);
}
```

`ModularOptimizerTest` (delegate = a recording stub `DrtOptimizer`):

```java
@Test
@DisplayName("simstep: dispatcher ticks BEFORE the delegate")
void dispatchesBeforeDelegate() { /* recording order list: ["dispatch", "delegate"] */ }

@Test
@DisplayName("nextTask enforces intended durations (belt 2 of spike §3.1)")
void enforceIntendedDuration() {
    // spliced schedule; shrink the upcoming swap task end so duration < RETOOLING_S,
    // call optimizer.nextTask(vehicle); assert the swap's endTime was pushed back out to
    // begin + RETOOLING_S and downstream task begins were shifted accordingly.
}

@Test
@DisplayName("nextTask forwards the PERFORMED task to dispatcher.observeTaskTransition")
void forwardsTaskTransitions() { /* delegate stub advances schedule.nextTask(); assert the
        dispatcher stub saw the previously-current task exactly once */ }
```

- [ ] **Step 2: Run, verify failure** — FAIL.

- [ ] **Step 3: Implement**

```java
package hagrid.integrated.modular;

import org.matsim.contrib.drt.optimizer.VehicleEntry;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;

/**
 * D2 strict lockout: from the moment a freight tour is spliced, the vehicle leaves the
 * passenger candidate set until the swap-back is performed. The capacity change alone does
 * NOT protect the approach/return legs (spike §3.2), and the drt-extensions predicate is too
 * narrow for multi-stop tours (spike §3.3) - hence the schedule-wide commitment predicate.
 */
public final class ModularEntryFactory implements VehicleEntry.EntryFactory {

    private final VehicleEntry.EntryFactory delegate;

    public ModularEntryFactory(VehicleEntry.EntryFactory delegate) {
        this.delegate = delegate;
    }

    @Override
    public VehicleEntry create(DvrpVehicle vehicle, double currentTime) {
        if (Modular.hasUnperformedFreightTask(vehicle.getSchedule())) {
            return null;
        }
        return delegate.create(vehicle, currentTime);
    }
}
```

```java
package hagrid.integrated.modular;
// imports: drt optimizer, dvrp schedule/fleet/optimizer, mobsim framework

/**
 * DrtOptimizer decorator (pattern: drt-extensions DrtServiceTaskOptimizer). All passenger
 * handling delegates to the native DefaultDrtOptimizer; the dispatcher ticks each simstep
 * BEFORE the delegate so freshly-committed vehicles are non-idle by the time the delegate's
 * rebalancing looks for relocatable vehicles. nextTask re-asserts the intended durations of
 * upcoming freight tasks (belt 2 against spike §3.1) and feeds performed-task transitions to
 * the dispatcher for the KPI events.
 */
public class ModularOptimizer implements DrtOptimizer {

    private final DrtOptimizer delegate;
    private final ModularTourDispatcher dispatcher;
    private final ScheduleTimingUpdater scheduleTimingUpdater;
    private final MobsimTimer timer;

    public ModularOptimizer(DrtOptimizer delegate, ModularTourDispatcher dispatcher,
                            ScheduleTimingUpdater scheduleTimingUpdater, MobsimTimer timer) {
        this.delegate = delegate;
        this.dispatcher = dispatcher;
        this.scheduleTimingUpdater = scheduleTimingUpdater;
        this.timer = timer;
    }

    @Override
    public void requestSubmitted(Request request) {
        delegate.requestSubmitted(request);
    }

    @Override
    public void nextTask(DvrpVehicle vehicle) {
        scheduleTimingUpdater.updateBeforeNextTask(vehicle);
        enforceIntendedDurations(vehicle);

        Task previous = currentOrNull(vehicle);
        delegate.nextTask(vehicle);
        Task next = currentOrNull(vehicle);
        if (previous != null && previous != next) {
            dispatcher.observeTaskTransition(vehicle, previous, timer.getTimeOfDay());
        }
    }

    @Override
    public void notifyMobsimBeforeSimStep(MobsimBeforeSimStepEvent e) {
        dispatcher.dispatch(e.getSimulationTime());
        delegate.notifyMobsimBeforeSimStep(e);
    }

    /** Belt 2 (spike §3.1): if timing updates undershot an intended freight duration, push
     *  the end back out and ripple the shift downstream (DrtServiceTaskOptimizer pattern). */
    private void enforceIntendedDurations(DvrpVehicle vehicle) {
        Schedule schedule = vehicle.getSchedule();
        if (schedule.getStatus() != Schedule.ScheduleStatus.STARTED
                || schedule.getCurrentTask() == Schedules.getLastTask(schedule)) {
            return;
        }
        int currentIdx = schedule.getCurrentTask().getTaskIdx();
        for (Task t : schedule.getTasks()) {
            if (t.getTaskIdx() < currentIdx) continue;
            double intended;
            if (t instanceof ModularFreightStopTask stop) intended = stop.getIntendedDuration();
            else if (t instanceof ModularCapacityChangeTask swap) intended = swap.getIntendedDuration();
            else continue;
            double current = t.getEndTime() - t.getBeginTime();
            if (current < intended) {
                double end = t.getBeginTime() + intended;
                t.setEndTime(end);
                scheduleTimingUpdater.updateTimingsStartingFromTaskIdx(vehicle,
                        t.getTaskIdx() + 1, end);
            }
        }
    }

    private static Task currentOrNull(DvrpVehicle vehicle) {
        Schedule schedule = vehicle.getSchedule();
        return schedule.getStatus() == Schedule.ScheduleStatus.STARTED
                ? schedule.getCurrentTask() : null;
    }
}
```

- [ ] **Step 4: Run the tests** — expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/hagrid/integrated/modular/ModularOptimizer.java src/main/java/hagrid/integrated/modular/ModularEntryFactory.java src/test/java/hagrid/integrated/modular/ModularOptimizerTest.java src/test/java/hagrid/integrated/modular/ModularEntryFactoryTest.java
git commit -m "feat(modular): optimizer decorator (tick + duration belt) and strict pax lockout (Task 8)"
```

---

### Task 9: `ModularKpiHandler` — run-ID-prefixed `modular_tour_stats.csv`

**Files:**
- Create: `src/main/java/hagrid/integrated/modular/ModularKpiHandler.java`
- Test: `src/test/java/hagrid/integrated/modular/ModularKpiHandlerTest.java`

**Interfaces:**
- Consumes: Task 3's `ModularTourEvent`/`ModularTourEventHandler`; `OutputDirectoryHierarchy.getOutputFilename` (the run-ID-prefix mechanism, 1c bug `89f1ee5` designed out); `ShutdownListener`.
- Produces: `<runId>.modular_tour_stats.csv`, format `metric;value` (semicolon — matches the 1c extractor convention). Metrics: `tours_planned, tours_expired_pending, tours_dispatched, tours_completed, tours_dispatched_incomplete, tours_pending_eod, parcels_planned, parcels_expired_pending, parcels_dispatched, parcels_served, parcels_dispatched_unserved, parcels_pending_eod, delta_parcels, swaps_completed, retooling_hours, deadhead_km_planned, service_km_planned, freight_vehicle_hours, tours_completed_late, parcels_served_late`. `freight_vehicle_hours` = Σ over COMPLETED tours of `(completed.time − dispatched.time)/3600` — the "vehicle-hours withdrawn from pax service" ingredient of design §4; incomplete excursions are excluded and that exclusion is documented in the CSV-consuming extractor. The two `_late` metrics are C8 (ex-post honesty of the 07:30–21:00 promise): `tours_completed_late` = COMPLETED events with `time > Modular.DELIVERY_DAY_END_S`, `parcels_served_late` = Σ parcels of STOP_SERVED events with `time > Modular.DELIVERY_DAY_END_S`.

Conservation identities (assert in test, log at shutdown):
- `tours_planned == tours_expired_pending + tours_dispatched + tours_pending_eod`
- `tours_dispatched == tours_completed + tours_dispatched_incomplete`
- `parcels_planned == parcels_expired_pending + parcels_dispatched + parcels_pending_eod`
- `parcels_dispatched == parcels_served + parcels_dispatched_unserved`
- `delta_parcels == parcels_planned - parcels_served`

- [ ] **Step 1: Write the failing test**

Mirror `SharedUseKpiHandlerTest`'s structure (read it first). Feed a deterministic event sequence directly into `handleEvent`:

```java
@Test
@DisplayName("delta decomposition + conservation identities from a mixed event sequence")
void aggregatesAndConserves(@TempDir Path tmp) throws Exception {
    ModularKpiHandler handler = new ModularKpiHandler(fixtureControlerIO(tmp, "TESTRUN"));
    // tour A: planned -> dispatched -> 2 stops (3+2 parcels) -> completed (both swaps)
    handler.handleEvent(ModularTourEvent.planned(100, "dhl_t0", 5));
    handler.handleEvent(ModularTourEvent.dispatched(200, "dhl_t0", vehId, 5, 2500.0, 4200.0));
    handler.handleEvent(ModularTourEvent.swapDone(300, "dhl_t0", vehId));
    handler.handleEvent(ModularTourEvent.stopServed(400, "dhl_t0", vehId, 3));
    handler.handleEvent(ModularTourEvent.stopServed(500, "dhl_t0", vehId, 2));
    handler.handleEvent(ModularTourEvent.swapDone(600, "dhl_t0", vehId));
    handler.handleEvent(ModularTourEvent.completed(600, "dhl_t0", vehId));
    // tour B: planned -> expired
    handler.handleEvent(ModularTourEvent.planned(100, "dhl_t1", 4));
    handler.handleEvent(ModularTourEvent.expired(700, "dhl_t1", 4));
    // tour C: planned -> dispatched -> 1 of 2 stops served, never completed (EOD)
    handler.handleEvent(ModularTourEvent.planned(100, "gls_t0", 7));
    handler.handleEvent(ModularTourEvent.dispatched(800, "gls_t0", vehId2, 7, 1000.0, 2000.0));
    handler.handleEvent(ModularTourEvent.swapDone(900, "gls_t0", vehId2));
    handler.handleEvent(ModularTourEvent.stopServed(950, "gls_t0", vehId2, 4));
    // tour D: planned, still pending at EOD
    handler.handleEvent(ModularTourEvent.planned(100, "gls_t1", 2));
    // tour E: completes LATE (after 21:00 = 75600) - C8 marker case
    handler.handleEvent(ModularTourEvent.planned(100, "hermes_t0", 2));
    handler.handleEvent(ModularTourEvent.dispatched(70000, "hermes_t0", vehId, 2, 500.0, 800.0));
    handler.handleEvent(ModularTourEvent.swapDone(70600, "hermes_t0", vehId));
    handler.handleEvent(ModularTourEvent.stopServed(75900, "hermes_t0", vehId, 2));
    handler.handleEvent(ModularTourEvent.swapDone(76000, "hermes_t0", vehId));
    handler.handleEvent(ModularTourEvent.completed(76100, "hermes_t0", vehId));

    handler.notifyShutdown(fixtureShutdownEvent());

    Map<String, Double> csv = readMetricCsv(tmp, "TESTRUN.modular_tour_stats.csv");
    assertThat(csv.get("tours_planned")).isEqualTo(5);
    assertThat(csv.get("tours_dispatched")).isEqualTo(3);
    assertThat(csv.get("tours_completed")).isEqualTo(2);
    assertThat(csv.get("tours_dispatched_incomplete")).isEqualTo(1);
    assertThat(csv.get("tours_expired_pending")).isEqualTo(1);
    assertThat(csv.get("tours_pending_eod")).isEqualTo(1);
    assertThat(csv.get("parcels_planned")).isEqualTo(20);
    assertThat(csv.get("parcels_served")).isEqualTo(11);
    assertThat(csv.get("parcels_expired_pending")).isEqualTo(4);
    assertThat(csv.get("parcels_dispatched_unserved")).isEqualTo(3);
    assertThat(csv.get("parcels_pending_eod")).isEqualTo(2);
    assertThat(csv.get("delta_parcels")).isEqualTo(9);
    assertThat(csv.get("swaps_completed")).isEqualTo(5);
    assertThat(csv.get("retooling_hours")).isCloseTo(5 * 420.0 / 3600.0, within(1e-9));
    assertThat(csv.get("deadhead_km_planned")).isCloseTo(4.0, within(1e-9));
    assertThat(csv.get("service_km_planned")).isCloseTo(7.0, within(1e-9));
    // COMPLETED tours: A (200 -> 600) + E (70000 -> 76100)
    assertThat(csv.get("freight_vehicle_hours")).isCloseTo((400.0 + 6100.0) / 3600.0, within(1e-9));
    // C8: E completed at 76100 > 75600, its 2-parcel stop served at 75900 > 75600
    assertThat(csv.get("tours_completed_late")).isEqualTo(1);
    assertThat(csv.get("parcels_served_late")).isEqualTo(2);
}

@Test
@DisplayName("reset(iteration) clears per-iteration state - CSV reflects ONLY the final iteration")
void resetClearsState(@TempDir Path tmp) { /* feed tour A, reset(1), feed tour B only,
        shutdown -> csv counts only tour B (1c dd34b23 lesson) */ }
```

- [ ] **Step 2: Run, verify failure** — FAIL.

- [ ] **Step 3: Implement**

```java
package hagrid.integrated.modular;
// imports: com.google.inject.Inject, matsim core controler (OutputDirectoryHierarchy,
//          ShutdownEvent, ShutdownListener), java.io/nio, java.util

/**
 * Modular tour KPI aggregation (design §4). Consumes ONLY ModularTourEvents (freight stops
 * are plain stay tasks and emit no native events, D7) and writes the run-ID-prefixed
 * modular_tour_stats.csv at shutdown (metric;value - the 1c extractor convention; bug
 * 89f1ee5 designed out via controlerIO.getOutputFilename).
 *
 * Per-iteration reset (1c dd34b23 lesson): controller-scope singleton, so reset(int) clears
 * the per-tour state - the CSV reflects ONLY the final iteration.
 */
public final class ModularKpiHandler implements ModularTourEventHandler, ShutdownListener {

    static final String FILE_NAME = "modular_tour_stats.csv";

    /** Mutable per-tour accumulator, keyed by tourId (insertion-ordered for determinism). */
    private static final class TourStat {
        int parcelsPlanned;
        boolean dispatched, expired, completed;
        boolean completedLate;          // C8: completed after DELIVERY_DAY_END_S
        int parcelsServed;
        int parcelsServedLate;          // C8: stops served after DELIVERY_DAY_END_S
        int swaps;
        double deadheadMeters, serviceMeters;
        double dispatchedAt = Double.NaN, completedAt = Double.NaN;
    }

    private final Map<String, TourStat> byTour = new LinkedHashMap<>();
    private final java.nio.file.Path outputCsv;

    @Inject
    public ModularKpiHandler(OutputDirectoryHierarchy controlerIO) {
        this.outputCsv = java.nio.file.Path.of(controlerIO.getOutputFilename(FILE_NAME));
    }

    @Override
    public void reset(int iteration) {
        byTour.clear();
    }

    @Override
    public void handleEvent(ModularTourEvent event) {
        TourStat s = byTour.computeIfAbsent(event.getTourId(), k -> new TourStat());
        switch (event.getPhase()) {
            case PLANNED -> s.parcelsPlanned = event.getParcels();
            case EXPIRED -> s.expired = true;
            case DISPATCHED -> {
                s.dispatched = true;
                s.dispatchedAt = event.getTime();
                s.deadheadMeters = event.getDeadheadMeters();
                s.serviceMeters = event.getServiceMeters();
            }
            case SWAP_DONE -> s.swaps++;
            case STOP_SERVED -> {
                s.parcelsServed += event.getParcels();
                if (event.getTime() > Modular.DELIVERY_DAY_END_S) {
                    s.parcelsServedLate += event.getParcels();                    // C8
                }
            }
            case COMPLETED -> {
                s.completed = true;
                s.completedAt = event.getTime();
                s.completedLate = event.getTime() > Modular.DELIVERY_DAY_END_S;   // C8
            }
        }
    }

    @Override
    public void notifyShutdown(ShutdownEvent event) {
        long toursPlanned = byTour.size();
        long toursExpired = count(s -> s.expired);
        long toursDispatched = count(s -> s.dispatched);
        long toursCompleted = count(s -> s.completed);
        long toursIncomplete = toursDispatched - toursCompleted;
        long toursPendingEod = toursPlanned - toursExpired - toursDispatched;

        long parcelsPlanned = sum(s -> true, s -> s.parcelsPlanned);
        long parcelsExpired = sum(s -> s.expired, s -> s.parcelsPlanned);
        long parcelsDispatched = sum(s -> s.dispatched, s -> s.parcelsPlanned);
        long parcelsServed = sum(s -> true, s -> s.parcelsServed);
        long parcelsUnserved = parcelsDispatched - parcelsServed;
        long parcelsPendingEod = parcelsPlanned - parcelsExpired - parcelsDispatched;

        long swaps = sum(s -> true, s -> s.swaps);
        double freightVehicleHours = byTour.values().stream()
                .filter(s -> s.completed)
                .mapToDouble(s -> (s.completedAt - s.dispatchedAt) / 3600.0).sum();

        // conservation identities (design §4) - loud but non-fatal at shutdown
        if (toursPlanned != toursExpired + toursDispatched + toursPendingEod
                || parcelsPlanned != parcelsExpired + parcelsDispatched + parcelsPendingEod) {
            org.apache.logging.log4j.LogManager.getLogger(ModularKpiHandler.class)
                    .error("Modular KPI conservation identity VIOLATED - CSV is suspect.");
        }

        List<String> lines = new ArrayList<>(List.of("metric;value",
                "tours_planned;" + toursPlanned,
                "tours_expired_pending;" + toursExpired,
                "tours_dispatched;" + toursDispatched,
                "tours_completed;" + toursCompleted,
                "tours_dispatched_incomplete;" + toursIncomplete,
                "tours_pending_eod;" + toursPendingEod,
                "parcels_planned;" + parcelsPlanned,
                "parcels_expired_pending;" + parcelsExpired,
                "parcels_dispatched;" + parcelsDispatched,
                "parcels_served;" + parcelsServed,
                "parcels_dispatched_unserved;" + parcelsUnserved,
                "parcels_pending_eod;" + parcelsPendingEod,
                "delta_parcels;" + (parcelsPlanned - parcelsServed),
                "swaps_completed;" + swaps,
                "retooling_hours;" + (swaps * Modular.RETOOLING_S / 3600.0),
                "deadhead_km_planned;" + (sumD(s -> s.dispatched, s -> s.deadheadMeters) / 1000.0),
                "service_km_planned;" + (sumD(s -> s.dispatched, s -> s.serviceMeters) / 1000.0),
                "freight_vehicle_hours;" + freightVehicleHours,
                "tours_completed_late;" + count(s -> s.completedLate),
                "parcels_served_late;" + sum(s -> true, s -> s.parcelsServedLate)));
        try {
            java.nio.file.Files.write(outputCsv, lines, java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("Cannot write " + outputCsv, e);
        }
    }

    private long count(java.util.function.Predicate<TourStat> p) {
        return byTour.values().stream().filter(p).count();
    }
    private long sum(java.util.function.Predicate<TourStat> p,
                     java.util.function.ToIntFunction<TourStat> f) {
        return byTour.values().stream().filter(p).mapToInt(f).sum();
    }
    private double sumD(java.util.function.Predicate<TourStat> p,
                        java.util.function.ToDoubleFunction<TourStat> f) {
        return byTour.values().stream().filter(p).mapToDouble(f).sum();
    }
}
```

(Constructor injection mirrors `SharedUseKpiHandler.java:93-103`; an EXPIRED-after-PLANNED tour keeps `parcelsPlanned` from its PLANNED event — the dispatcher guarantees PLANNED precedes every other phase for a tour.)

- [ ] **Step 4: Run the test** — expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/hagrid/integrated/modular/ModularKpiHandler.java src/test/java/hagrid/integrated/modular/ModularKpiHandlerTest.java
git commit -m "feat(modular): KPI handler - delta decomposition, conservation, per-iteration reset (Task 9)"
```

---

### Task 10: `ModularDispatchModule` (Guice composition) + `ModularEndToEndTest`

**Files:**
- Create: `src/main/java/hagrid/integrated/modular/ModularDispatchModule.java`
- Test: `src/test/java/hagrid/integrated/drt/ModularEndToEndTest.java`

**Interfaces:**
- Consumes: everything from Tasks 3-9; native modal keys `Fleet, MobsimTimer, DepotFinder, RebalancingStrategy, DrtScheduleInquiry, ScheduleTimingUpdater, EmptyVehicleRelocator, UnplannedRequestInserter, DrtRequestInsertionRetryQueue, StopTimeCalculator, DriveTaskUpdater, VehicleDataEntryFactoryImpl, DrtTaskFactory, TravelTime, TravelDisutilityFactory, Network, DvrpLoadType` (all bound by `DrtModeOptimizerQSimModule` 2025.0, verified against source lines 92-206).
- Produces: `new ModularDispatchModule(DrtConfigGroup drtCfg, List<ModularFreightTour> tours, double idleThreshold)` — installed LAST via `controler.addOverridingModule` (Task 11 / tests).

- [ ] **Step 1: Write the failing e2e test**

`ModularEndToEndTest` — stage fixtures exactly like `MarriedBaselineEndToEndTest.java:48-97` (grid network, 5 pax + 1 pt agent, square shapefile, `depots.csv` = `provider;x;y\ndhl;500.0;500.0`, van types file, `LmdTestShapefiles.writeDemand`, `LausitzDrtPreprocessor.run(...)` with `/*capacity*/ 10`), with the freight side switched to `runModular`:

```java
@Test
@DisplayName("DRT_MODULAR e2e: capsules swap, parcels leave on DRT vehicles, KPI CSV conserves, no carriers")
void runsModularThroughOneIteration() throws Exception {
    // ... staging block (married e2e recipe; jspritIterations=1, maxTourDurationSeconds=12600) ...
    LausitzFreightPreprocessor.runModular(demandShp.toString(), depotCsv.toString(),
            rawNetFile.toString(), typesFile.toString(), carriersOut.toString(),
            1, shpFile.toString(), 12600);

    Scenario scenario = DrtScenarioBuilder.build(cfgUrl.toString(), drtNetFile.toString(),
            clippedPlans.toString(), shpFile.toString(), fleetFile.toString(),
            matsimOut.toString(), "MODULAR_E2E", /*lastIteration*/ 1);

    // tours: read routed carriers, convert against car + drt networks
    Carriers routed = ModularTourConverter.read(carriersOut.toString(),
            ModularVehicleTypes.createCapsuleTypes(typesFile.toString()));
    Network carNet = LausitzFreightPreprocessor.carNetwork(
            NetworkUtils.readNetwork(rawNetFile.toString()));
    Network drtNet = NetworkUtils.createNetwork();
    new TransportModeNetworkFilter(NetworkUtils.readNetwork(drtNetFile.toString()))
            .filter(drtNet, Set.of(TransportMode.drt));
    List<ModularFreightTour> tours = ModularTourConverter.convert(routed, carNet, drtNet);
    assertThat(tours).isNotEmpty();

    Controler controler = new Controler(scenario);
    // 5-arg installModules -> ReturnToDepotRebalancingModule ACTIVE: this e2e must prove the
    // splicer and the return-to-depot pull coexist on the same schedule tail (design §6).
    DrtConfigComposer.installModules(controler, List.of(new Coord(500.0, 500.0)),
            /*returnStart*/ 84600.0, /*perDepotCapacity*/ 4.0, /*demandEstimationPeriod*/ 1800.0);
    DrtConfigGroup drtCfg = MultiModeDrtConfigGroup.get(scenario.getConfig())
            .getModalElements().iterator().next();
    // idleThreshold=0.0: gate opens whenever ANY vehicle is idle -> deterministic dispatch
    controler.addOverridingModule(new ModularDispatchModule(drtCfg, tours, 0.0));

    controler.run();   // (a) composes and completes - Guice/validator/runtime proof

    // (b) KPI CSV exists run-ID-prefixed and conserves
    Path csv = matsimOut.resolve("MODULAR_E2E.modular_tour_stats.csv");
    assertThat(csv).exists();
    Map<String, Double> stats = readMetricCsv(csv);
    assertThat(stats.get("tours_dispatched")).isGreaterThanOrEqualTo(1.0);
    assertThat(stats.get("tours_completed")).isGreaterThanOrEqualTo(1.0);
    assertThat(stats.get("parcels_served")).isGreaterThanOrEqualTo(1.0);
    assertThat(stats.get("tours_planned"))
            .isEqualTo(stats.get("tours_expired_pending") + stats.get("tours_dispatched")
                    + stats.get("tours_pending_eod"));
    assertThat(stats.get("swaps_completed")).isGreaterThanOrEqualTo(2.0);

    // (c) NO CarrierModule output (the double-delivery guard, design §3.4)
    try (var files = Files.walk(matsimOut)) {
        assertThat(files.filter(Files::isRegularFile)
                .map(p -> p.getFileName().toString())
                .filter(n -> n.contains("output_carriers"))).isEmpty();
    }

    // (d) events file carries the modularTour COMPLETED phase (custom events serialized)
    // read output_events via EventsUtils + a GenericEvent-tolerant handler and assert at
    // least one event with eventType "modularTour" and attribute phase=COMPLETED.
}
```

Escape hatch (documented in the test javadoc, only if reproduced): if the 5-arg `installModules` trips the KNOWN, unrelated `DrtZonalWaitTimesAnalyzer` geopackage shutdown bug (`SharedUseEndToEndTest.java:129-135` — Windows path-length artifact), fall back to the 2-arg `installModules` here and add a dedicated `ModularRebalTest` mirroring `SharedUseRebalTest`'s harness to cover the splicer/return-to-depot coexistence — that coverage is a design §6 requirement and must not silently vanish.

- [ ] **Step 2: Run, verify failure** — `mvn -q test -Dtest=ModularEndToEndTest` — FAIL (module missing).

- [ ] **Step 3: Implement the module**

```java
package hagrid.integrated.modular;
// imports: drt optimizer/schedule/scheduler/stops/run, dvrp fleet/load/run/schedule,
//          matsim core mobsim/router/events, google inject

/**
 * Composition for DRT_MODULAR (1d). Controller half: the KPI handler. QSim half (via
 * installOverridingQSimModule, the SharedUseModule-proven mechanism): four rebinds -
 * ScheduleTimingUpdater (decorated end-time calculator, Task 5), VehicleEntry.EntryFactory
 * (strict lockout, Task 8), DrtOptimizer (dispatcher-ticking decorator around a manually
 * constructed DefaultDrtOptimizer - the DrtServiceOptimizerQSimModule pattern), plus the
 * dispatcher/splicer singletons. MUST be added LAST via controler.addOverridingModule, after
 * DrtConfigComposer.installModules.
 *
 * NOTE (binding rule): DrtOptimizer is REBOUND with bindModal(...), NOT addModalComponent -
 * the native DrtModeOptimizerQSimModule already registered the QSim component for
 * modalKey(DrtOptimizer.class); a second registration would double-drive the optimizer.
 */
public final class ModularDispatchModule extends AbstractDvrpModeModule {

    private final DrtConfigGroup drtCfg;
    private final List<ModularFreightTour> tours;
    private final double idleThreshold;

    public ModularDispatchModule(DrtConfigGroup drtCfg, List<ModularFreightTour> tours,
                                 double idleThreshold) {
        super(drtCfg.getMode());
        this.drtCfg = drtCfg;
        this.tours = List.copyOf(tours);
        this.idleThreshold = idleThreshold;
    }

    @Override
    public void install() {
        bind(ModularKpiHandler.class).asEagerSingleton();
        addEventHandlerBinding().to(ModularKpiHandler.class);
        addControlerListenerBinding().to(ModularKpiHandler.class);

        installOverridingQSimModule(new AbstractDvrpModeQSimModule(getMode()) {
            @Override
            protected void configureQSim() {
                // Task 5 decorator inside a rebuilt updater (the calculator is constructed
                // inline by the native module - no separate key to decorate; source-verified
                // against DrtModeOptimizerQSimModule.java:187-190).
                bindModal(ScheduleTimingUpdater.class).toProvider(modalProvider(getter ->
                        new ScheduleTimingUpdater(getter.get(MobsimTimer.class),
                                new ModularStayTaskEndTimeCalculator(
                                        new DrtStayTaskEndTimeCalculator(
                                                getter.getModal(StopTimeCalculator.class))),
                                getter.getModal(DriveTaskUpdater.class)))).asEagerSingleton();

                bindModal(VehicleEntry.EntryFactory.class).toProvider(modalProvider(getter ->
                        new ModularEntryFactory(getter.getModal(VehicleDataEntryFactoryImpl.class))))
                        .asEagerSingleton();

                bindModal(ModularTourScheduler.class).toProvider(modalProvider(getter -> {
                    TravelTime travelTime = getter.getModal(TravelTime.class);
                    TravelDisutility disutility = getter.getModal(TravelDisutilityFactory.class)
                            .createTravelDisutility(travelTime);
                    return new ModularTourScheduler(getter.getModal(Network.class), travelTime,
                            disutility, getter.getModal(DrtTaskFactory.class),
                            getter.getModal(DvrpLoadType.class));
                })).asEagerSingleton();

                bindModal(ModularTourDispatcher.class).toProvider(modalProvider(getter ->
                        new ModularTourDispatcher(getMode(), tours, idleThreshold,
                                getter.getModal(Fleet.class),
                                getter.getModal(DrtScheduleInquiry.class),
                                getter.getModal(ModularTourScheduler.class),
                                getter.getModal(Network.class),
                                getter.get(EventsManager.class)))).asEagerSingleton();

                bindModal(DrtOptimizer.class).toProvider(modalProvider(getter ->
                        new ModularOptimizer(
                                new DefaultDrtOptimizer(drtCfg, getter.getModal(Fleet.class),
                                        getter.get(MobsimTimer.class),
                                        getter.getModal(DepotFinder.class),
                                        getter.getModal(RebalancingStrategy.class),
                                        getter.getModal(DrtScheduleInquiry.class),
                                        getter.getModal(ScheduleTimingUpdater.class),
                                        getter.getModal(EmptyVehicleRelocator.class),
                                        getter.getModal(UnplannedRequestInserter.class),
                                        getter.getModal(DrtRequestInsertionRetryQueue.class)),
                                getter.getModal(ModularTourDispatcher.class),
                                getter.getModal(ScheduleTimingUpdater.class),
                                getter.get(MobsimTimer.class)))).asEagerSingleton();
            }
        });
    }
}
```

(VERIFY-SOURCE: `DefaultDrtOptimizer` constructor order against `DrtModeOptimizerQSimModule.java:92-99`; `EventsManager` retrieval in QSim scope — `getter.get(EventsManager.class)` is how DVRP QSim providers obtain it, cross-check with `ServiceTaskSchedulerImpl`'s wiring in `DrtServiceQSimModule`.)

- [ ] **Step 4: Run the e2e** — `mvn -q test -Dtest=ModularEndToEndTest` — expected: PASS (allow several minutes; jspritIterations=1, 1 MATSim iteration, fleet 4).

- [ ] **Step 5: Run the neighboring e2e suites (override-collision regression)**

Run: `mvn -q test -Dtest=SharedUseEndToEndTest,MarriedBaselineEndToEndTest,DrtBaselineEndToEndTest` — expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/hagrid/integrated/modular/ModularDispatchModule.java src/test/java/hagrid/integrated/drt/ModularEndToEndTest.java
git commit -m "feat(modular): Guice composition + end-to-end proof incl. return-to-depot coexistence (Task 10)"
```

---

### Task 11: Runner wiring — `idleThreshold` / `maxTourDuration` keys + the third DRT case

**Files:**
- Modify: `src/main/java/hagrid/simulation/HAGRIDSimulationConfig.java`
- Modify: `src/main/java/hagrid/simulation/SimulationRunnerUtils.java` (parse + `runSimulation` DRT branch, lines 244-315)
- Test: `src/test/java/hagrid/simulation/` — extend the existing config/parse test class (locate via `grep -r "parseScenario" src/test`; if none exists for parsing, create `SimulationRunnerUtilsParseTest`)

**Interfaces:**
- Consumes: Tasks 2/4/10.
- Produces: runner keys `idleThreshold` (default 0.50) and `maxTourDuration` (seconds, default 12600) — parse/ctor pattern identical to `chiThreshold`; `getIdleThreshold()` / `getMaxTourDurationSeconds()`; static guard `SimulationRunnerUtils.runsCarrierModules(scenario, drtWithFreight)`.

- [ ] **Step 1: Write the failing tests**

```java
@Test
@DisplayName("parseScenario: modular keys parse with 1c-pattern defaults and validation")
void parsesModularKeys() {
    HAGRIDSimulationConfig cfg = SimulationRunnerUtils.parseScenario(
            "concept=DRT_MODULAR,date=2025-06-10,maxIter=1,jspritIter=1,fleetSize=120,"
            + "idleThreshold=0.7,maxTourDuration=25200");
    assertThat(cfg.getIdleThreshold()).isEqualTo(0.7);
    assertThat(cfg.getMaxTourDurationSeconds()).isEqualTo(25200);

    HAGRIDSimulationConfig defaults = SimulationRunnerUtils.parseScenario(
            "concept=DRT_MODULAR,date=2025-06-10,maxIter=1,jspritIter=1");
    assertThat(defaults.getIdleThreshold()).isEqualTo(Modular.DEFAULT_IDLE_THRESHOLD);
    assertThat(defaults.getMaxTourDurationSeconds()).isEqualTo(Modular.DEFAULT_MAX_TOUR_DURATION_S);

    assertThatThrownBy(() -> SimulationRunnerUtils.parseScenario(
            "concept=DRT_MODULAR,date=2025-06-10,maxIter=1,jspritIter=1,idleThreshold=1.5"))
            .isInstanceOf(IllegalArgumentException.class);
}

@Test
@DisplayName("carrier-module guard: married yes, shareduse no, MODULAR NO (double-delivery), lmd n/a")
void carrierModuleGuard() {
    assertThat(SimulationRunnerUtils.runsCarrierModules(Scenario.DRT_BASELINE, true)).isTrue();
    assertThat(SimulationRunnerUtils.runsCarrierModules(Scenario.DRT_BASELINE, false)).isFalse();
    assertThat(SimulationRunnerUtils.runsCarrierModules(Scenario.DRT_SHAREDUSE, true)).isFalse();
    assertThat(SimulationRunnerUtils.runsCarrierModules(Scenario.DRT_MODULAR, true)).isFalse();
    assertThat(SimulationRunnerUtils.runsCarrierModules(Scenario.DRT_MODULAR, false)).isFalse();
}
```

- [ ] **Step 2: Run, verify failure** — FAIL.

- [ ] **Step 3: Implement**

`HAGRIDSimulationConfig`: add fields `private final double idleThreshold;` and `private final int maxTourDurationSeconds;` with javadoc mirroring `chiThreshold`'s (lines 111-117: "only meaningful for DRT_MODULAR, harmless otherwise"). Add ONE new fullest constructor extending the current 15-arg one by the two parameters (all shorter constructors default them to `Modular.DEFAULT_IDLE_THRESHOLD` / `Modular.DEFAULT_MAX_TOUR_DURATION_S`); validate `0.0 <= idleThreshold <= 1.0` and `maxTourDurationSeconds > 0` in the constructor body. Getters `getIdleThreshold()` / `getMaxTourDurationSeconds()`. Extend `validateInputFiles()` so DRT_MODULAR always validates the LMD input trio (demand shapefile, depot csv, vehicle types) regardless of the `freight` flag — locate the existing `drtWithFreight`-guarded check around line 743 and widen its condition to `drtWithFreight || concept is DRT_MODULAR`.

`SimulationRunnerUtils.parseScenario`: after the `noParcels` parse (line 147):

```java
// Modular dispatch gate (design D6) and jsprit tour cap (design D5, seconds). Same
// parse/ctor pattern as chiThreshold; harmless defaults for every other concept.
double idleThreshold = nonNegDouble(map.getOrDefault("idleThreshold",
        Double.toString(hagrid.integrated.modular.Modular.DEFAULT_IDLE_THRESHOLD)), "idleThreshold");
int maxTourDuration = positiveInt(map.getOrDefault("maxTourDuration",
        Integer.toString(hagrid.integrated.modular.Modular.DEFAULT_MAX_TOUR_DURATION_S)), "maxTourDuration");
```

— thread both through the new fullest constructor and append them to the summary LOG line (line 182).

`SimulationRunnerUtils.runSimulation` — the third case. Extract the guard first:

```java
/**
 * Whether a run carries the offline-routed LMD carriers INSIDE the mobsim (CarrierModule).
 * DRT_SHAREDUSE: parcels ride the DRT fleet as passengers - no carriers (1c D7).
 * DRT_MODULAR: the DRT fleet executes the jsprit tours itself via task splicing - running
 * the CarrierModule too would deliver every parcel TWICE (phantom vans + DRT), silently
 * corrupting every freight KPI (design §3.4).
 */
static boolean runsCarrierModules(hagrid.HagridConfig.Scenario scenario, boolean drtWithFreight) {
    return drtWithFreight
            && scenario != hagrid.HagridConfig.Scenario.DRT_SHAREDUSE
            && scenario != hagrid.HagridConfig.Scenario.DRT_MODULAR;
}
```

Then inside the `isDrtScenario()` branch (lines 244-309), replacing the `!sharedUse` conditions:

```java
hagrid.HagridConfig.Scenario concept =
        hagrid.HagridConfig.Scenario.valueOf(cfg.getConcept().toUpperCase());
boolean sharedUse = concept == hagrid.HagridConfig.Scenario.DRT_SHAREDUSE;
boolean modular = concept == hagrid.HagridConfig.Scenario.DRT_MODULAR;
boolean carrierModules = runsCarrierModules(concept, cfg.isDrtWithFreight());

if (carrierModules) {
    hagrid.integrated.freight.LausitzFreightPreprocessor.run(/* unchanged married call */);
} else if (modular) {
    // jsprit YES (capsule type + tour cap), CarrierModule NO (design §3.4)
    hagrid.integrated.freight.LausitzFreightPreprocessor.runModular(
            cfg.getLmdDemandShapefile(), cfg.getLmdDepotCsv(), cfg.getLausitzNetworkRaw(),
            cfg.getLmdVehicleTypes(), cfg.getLmdCarriersRouted(), cfg.getJspritIterations(),
            cfg.getDrtServiceAreaShapefile(), cfg.getMaxTourDurationSeconds());
    LOG.info("DRT_MODULAR: jsprit tours routed (cap {}s); freight flag ignored - the DRT "
            + "fleet executes them (no CarrierModule).", cfg.getMaxTourDurationSeconds());
} else if (sharedUse && cfg.isDrtWithFreight()) {
    LOG.info("DRT_SHAREDUSE: freight flag ignored - parcels ride the DRT fleet (no jsprit/carriers)");
}
```

`FreightRunComposer.addCarriers` (line 269-272) and `installCarrierModules` (line 285-288) both switch from `cfg.isDrtWithFreight() && !sharedUse` to `carrierModules`. After the sharedUse module-install block (line 289-306), add:

```java
} else if (modular) {
    org.matsim.contrib.drt.run.DrtConfigGroup drtCfg =
            org.matsim.contrib.drt.run.MultiModeDrtConfigGroup.get(scenario.getConfig())
                    .getModalElements().iterator().next();
    org.matsim.freight.carriers.Carriers routed = hagrid.integrated.modular.ModularTourConverter
            .read(cfg.getLmdCarriersRouted(),
                    hagrid.integrated.modular.ModularVehicleTypes
                            .createCapsuleTypes(cfg.getLmdVehicleTypes()));
    org.matsim.api.core.v01.network.Network carNet =
            hagrid.integrated.freight.LausitzFreightPreprocessor.carNetwork(
                    org.matsim.core.network.NetworkUtils.readNetwork(cfg.getLausitzNetworkRaw()));
    org.matsim.api.core.v01.network.Network drtNet =
            org.matsim.core.network.NetworkUtils.createNetwork();
    new org.matsim.core.network.algorithms.TransportModeNetworkFilter(
            org.matsim.core.network.NetworkUtils.readNetwork(cfg.getDrtNetworkClipped()))
            .filter(drtNet, java.util.Set.of(org.matsim.api.core.v01.TransportMode.drt));
    java.util.List<hagrid.integrated.modular.ModularFreightTour> tours =
            hagrid.integrated.modular.ModularTourConverter.convert(routed, carNet, drtNet);
    controler.addOverridingModule(new hagrid.integrated.modular.ModularDispatchModule(
            drtCfg, tours, cfg.getIdleThreshold()));
    LOG.info("MODULAR run '{}' (DRT fleet {}, {} freight tours, idleThreshold={}, cap={}s).",
            cfg.getRunId(), cfg.getFleetSize(), tours.size(), cfg.getIdleThreshold(),
            cfg.getMaxTourDurationSeconds());
}
```

(Match the file's existing fully-qualified-name style in this method — see lines 248-298.)

- [ ] **Step 4: Run the tests + the DRT e2e suites**

Run: `mvn -q test -Dtest=SimulationRunnerUtilsParseTest,ModularEndToEndTest,SharedUseEndToEndTest,MarriedBaselineEndToEndTest` — expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/hagrid/simulation/HAGRIDSimulationConfig.java src/main/java/hagrid/simulation/SimulationRunnerUtils.java src/test/java/hagrid/simulation/SimulationRunnerUtilsParseTest.java
git commit -m "feat(modular): runner keys idleThreshold/maxTourDuration + third DRT wiring case (Task 11)"
```

---

### Task 12: Control-arm test — `idleThreshold=1.0` reproduces the plain baseline bit-identically

The 1d analog of 1c's χ→0 proof, but STRONGER (design §6): with the gate never opening there is no dispatch, no splice, and — unlike 1c — not even an extra population member, so the pax side must be **bit-identical**, not merely within noise.

**Files:**
- Test: `src/test/java/hagrid/integrated/drt/ModularControlArmTest.java`

**Interfaces:**
- Consumes: Task 10's module + e2e staging.

- [ ] **Step 1: Write the failing test**

```java
@Test
@DisplayName("theta=1.0: zero dispatches AND pax outputs byte-identical to a run without the module")
void gateNeverOpensReproducesBaseline() throws Exception {
    // stage fixtures ONCE (same recipe as ModularEndToEndTest), then two runs into
    // separate matsim output dirs with the SAME runId "CONTROL_E2E":
    //   run A: + ModularDispatchModule(drtCfg, tours, 1.0)   <- gate never opens (strict >)
    //   run B: no modular module at all (plain baseline composition)
    // Both use the identical scenario inputs and 2-arg installModules.

    // (a) A dispatched nothing; every tour expired or pending at EOD
    Map<String, Double> stats = readMetricCsv(outA.resolve("CONTROL_E2E.modular_tour_stats.csv"));
    assertThat(stats.get("tours_dispatched")).isZero();
    assertThat(stats.get("parcels_served")).isZero();
    assertThat(stats.get("delta_parcels")).isEqualTo(stats.get("parcels_planned"));

    // (b) bit-identical pax outputs (no dispatch -> no splice -> no RNG divergence)
    assertThat(Files.mismatch(
            outA.resolve("CONTROL_E2E.output_drt_legs_drt.csv"),
            outB.resolve("CONTROL_E2E.output_drt_legs_drt.csv"))).isEqualTo(-1L);
    assertThat(Files.mismatch(
            outA.resolve("CONTROL_E2E.drt_customer_stats_drt.csv"),
            outB.resolve("CONTROL_E2E.drt_customer_stats_drt.csv"))).isEqualTo(-1L);
}
```

(The exact stock-output filenames: check what `ModularEndToEndTest`'s run actually wrote and pin the two most pax-relevant CSVs — the drt legs and customer stats; `Files.mismatch == -1` is the byte-identity assertion.)

- [ ] **Step 2: Run, verify it fails** — FAIL (test class new; also genuinely proves the property).

- [ ] **Step 3: Implement/fix until green**

No production code is EXPECTED here. If the byte-identity fails, that is a REAL FINDING (the module leaks into pax behaviour without dispatching — e.g. the rebuilt `ScheduleTimingUpdater` or the entry-factory changed iteration order); investigate and fix the leak in the module, do not weaken the assertion. Permitted relaxation ONLY if a stock output embeds wall-clock timestamps: swap that file for a timestamp-free one, never for "within noise".

- [ ] **Step 4: Run the test** — expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/test/java/hagrid/integrated/drt/ModularControlArmTest.java
git commit -m "test(modular): theta=1.0 control arm - bit-identical pax baseline proof (Task 12)"
```

---

### Task 13: `extract_modular.py` + registration in `build_kpis.EXTRACTORS`

**Files:**
- Create: `analysis/kpi/extract_modular.py`
- Modify: `analysis/kpi/build_kpis.py` (line 28-29 area: append the extractor tuple)
- Test: `analysis/kpi/tests/test_extract_modular.py`

**Interfaces:**
- Consumes: Task 9's CSV contract (`metric;value`, name `<prefix>.modular_tour_stats.csv`); 1e's `common.row(group, name, value, unit, source)` and the `EXTRACTORS` tuple contract `(predicate(run_dir, meta) -> bool, extract(run_dir, prefix) -> rows)` (see `build_kpis.py:26-29` and `extract_shareduse.py:51-72`).
- Produces: KPI rows under `freight/*` and `modular/*`.

- [ ] **Step 1: Write the failing pytest**

```python
# analysis/kpi/tests/test_extract_modular.py
import extract_modular


def _write_stats(tmp_path, prefix):
    lines = ["metric;value",
             "tours_planned;10", "tours_expired_pending;2", "tours_dispatched;7",
             "tours_completed;6", "tours_dispatched_incomplete;1", "tours_pending_eod;1",
             "parcels_planned;500", "parcels_expired_pending;80", "parcels_dispatched;400",
             "parcels_served;350", "parcels_dispatched_unserved;50", "parcels_pending_eod;20",
             "delta_parcels;150", "swaps_completed;13", "retooling_hours;1.516",
             "deadhead_km_planned;42.5", "service_km_planned;120.0",
             "freight_vehicle_hours;21.75",
             "tours_completed_late;1", "parcels_served_late;12"]
    (tmp_path / (prefix + ".modular_tour_stats.csv")).write_text("\n".join(lines))


def test_predicate_matches_run_id_prefixed_file(tmp_path):
    class Meta:
        prefix = "DRT_MODULAR_X"
    assert not extract_modular.has_modular_stats(tmp_path, Meta)
    _write_stats(tmp_path, "DRT_MODULAR_X")
    assert extract_modular.has_modular_stats(tmp_path, Meta)


def test_extract_emits_delta_decomposition(tmp_path):
    _write_stats(tmp_path, "P")
    rows = extract_modular.extract(tmp_path, "P")
    by_name = {(r["kpi_group"], r["kpi_name"]): r["value"] for r in rows}
    assert by_name[("freight", "parcels_served")] == 350
    assert by_name[("freight", "delta_parcels")] == 150
    assert by_name[("freight", "delta_share_expired_pending")] == (80 + 20) / 150
    assert by_name[("freight", "delta_share_dispatched_incomplete")] == 50 / 150
    assert by_name[("modular", "swaps_completed")] == 13
    assert by_name[("modular", "retooling_hours")] == 1.516
    assert by_name[("modular", "deadhead_km_planned")] == 42.5
    assert by_name[("modular", "freight_vehicle_hours")] == 21.75
    assert by_name[("freight", "tour_completion_rate")] == 6 / 7
    assert by_name[("freight", "tours_completed_late")] == 1
    assert by_name[("freight", "parcels_served_late")] == 12
```

(Adapt the row-dict key names to whatever `common.row` actually produces — read `common.py` first; the existing `test_*` files in `analysis/kpi/tests/` show the import/bootstrap conventions incl. the `sys.path` setup.)

- [ ] **Step 2: Run, verify failure**

Run (from `parcel-demand-2-matsim-pipeline/analysis/kpi/`): `python -u -m pytest tests/test_extract_modular.py -q` — expected: FAIL (module missing).

- [ ] **Step 3: Implement**

```python
# -*- coding: utf-8 -*-
"""Modular (1d capsule swap) KPI rows from <prefix>.modular_tour_stats.csv (Task 9 contract).

Unlike Shared-Use, the pax side needs NO correction here (design D7): parcels never ride as
DVRP passengers, so drt_customer_stats is pax-truth as-is. This module only surfaces the
freight/tour side: the delta decomposition (expired_pending + pending_eod vs
dispatched_incomplete), tour completion, and the swap/retooling/deadhead cost of modularity.
Stock vehicle-distance stats DO include the freight excursions (same fleet, by design);
deadhead/service km planned splits come from dispatch-time routing, written by the handler.
"""
from pathlib import Path

import pandas as pd

from common import row


def has_modular_stats(run_dir, meta):
    # Run-ID-prefixed, like every MATSim output (1c bug 89f1ee5 - never a bare filename).
    return (Path(run_dir) / (meta.prefix + ".modular_tour_stats.csv")).exists()


def extract(run_dir, prefix):
    stats = dict(pd.read_csv(Path(run_dir) / (prefix + ".modular_tour_stats.csv"),
                             sep=";").values)
    delta = float(stats["delta_parcels"])
    expired = float(stats["parcels_expired_pending"]) + float(stats["parcels_pending_eod"])
    incomplete = float(stats["parcels_dispatched_unserved"])
    dispatched_tours = float(stats["tours_dispatched"])
    rows = [
        row("freight", "parcels_planned", int(stats["parcels_planned"]), "parcels", "modular_tour_stats"),
        row("freight", "parcels_served", int(stats["parcels_served"]), "parcels", "modular_tour_stats"),
        row("freight", "delta_parcels", int(delta), "parcels", "modular_tour_stats"),
        row("freight", "delta_share_expired_pending",
            expired / delta if delta else 0.0, "share", "modular_tour_stats"),
        row("freight", "delta_share_dispatched_incomplete",
            incomplete / delta if delta else 0.0, "share", "modular_tour_stats"),
        row("freight", "tour_completion_rate",
            float(stats["tours_completed"]) / dispatched_tours if dispatched_tours else 0.0,
            "share", "modular_tour_stats"),
        row("freight", "tours_planned", int(stats["tours_planned"]), "tours", "modular_tour_stats"),
        row("freight", "tours_dispatched", int(dispatched_tours), "tours", "modular_tour_stats"),
        # C8 ex-post honesty of the 07:30-21:00 promise (dashboard card noted in backlog)
        row("freight", "tours_completed_late", int(stats["tours_completed_late"]), "tours", "modular_tour_stats"),
        row("freight", "parcels_served_late", int(stats["parcels_served_late"]), "parcels", "modular_tour_stats"),
        row("modular", "swaps_completed", int(stats["swaps_completed"]), "swaps", "modular_tour_stats"),
        row("modular", "retooling_hours", float(stats["retooling_hours"]), "h", "modular_tour_stats"),
        row("modular", "deadhead_km_planned", float(stats["deadhead_km_planned"]), "km", "modular_tour_stats"),
        row("modular", "service_km_planned", float(stats["service_km_planned"]), "km", "modular_tour_stats"),
        # completed tours only (incomplete excursions have no completion timestamp) - the
        # "vehicle-hours withdrawn from pax service" ingredient; the pax-side delta comes
        # from comparing wait/rejection KPIs against the 10-seat baseline run.
        row("modular", "freight_vehicle_hours", float(stats["freight_vehicle_hours"]), "h", "modular_tour_stats"),
    ]
    return rows
```

Registration in `build_kpis.py` (after line 29, same pattern):

```python
EXTRACTORS.append((extract_modular.has_modular_stats, extract_modular.extract))
```

with the matching `import extract_modular` next to `import extract_shareduse`.

- [ ] **Step 4: Run the pytest suite**

Run: `python -u -m pytest tests/ -q` — expected: ALL PASS (existing KPI tests stay green).

- [ ] **Step 5: Commit**

```bash
git add analysis/kpi/extract_modular.py analysis/kpi/build_kpis.py analysis/kpi/tests/test_extract_modular.py
git commit -m "feat(kpi): extract_modular - delta decomposition + modularity-cost rows (Task 13)"
```

---

### Task 14: Full regression gate + documentation closeout

**Files:**
- Modify: `docs/BACKLOG.md` ([H] Modular item: implementation status)
- Modify: `docs/superpowers/specs/2026-07-27-1d-modular-capsule-swap-design.md` (Status line → implemented, note C1-C6 concretisations)

**Interfaces:** none new.

- [ ] **Step 1: Full Java suite**

Run (from `parcel-demand-2-matsim-pipeline/`): `mvn -q test` — expected: ALL PASS (~274 pre-existing + the new Modular tests). Any red in a pre-existing test is a regression to fix before proceeding.

- [ ] **Step 2: Full Python KPI suite**

Run: `python -u -m pytest analysis/kpi/tests -q` — expected: ALL PASS.

- [ ] **Step 3: Update docs**

`docs/BACKLOG.md`: under the [H] Modular item, mark the implementation done with date, keep the open run-work (10-seat re-baseline, idle-threshold sweep, 7.0h control arm, predictive-gate decision) and the parked sensitivity ideas untouched. Design spec: flip `Status:` to `implemented <date> (plan 2026-07-27-1d-modular-capsule-swap.md)` and append one line listing C1–C8 (C4 revised 2026-07-28: delivery day 07:30–21:00, no waves) as accepted plan concretisations.

- [ ] **Step 4: Commit**

```bash
git add docs/BACKLOG.md docs/superpowers/specs/2026-07-27-1d-modular-capsule-swap-design.md
git commit -m "docs(modular): 1d implementation closeout - status, concretisations (Task 14)"
```

---

## Validation runs (after implementation — run discipline, NOT plan tasks)

Per design §6 + §7, revised 2026-07-28 (POC first, paper runs later — user), sequenced with the user (long runs go to the sim-PC; laptop sleep kills them):

1. **POC smoke run** `concept=DRT_MODULAR,maxIter=1,fleetSize=120,idleThreshold=0.5` on the current PANDA stand (Zensus `level_central`, 6,024 parcels) — conservation + no-carriers spot checks from Task 10 repeated at scale; FIRST look: the 07:16 surge shape and the C8 late metrics (user: "erst Ergebnisse ansehen", feeds the predictive-gate backlog decision).
2. **Control arm** `idleThreshold=1.0` — pax KPIs bit-identical to the same run without the module (Task 12's property at scale).
3. **10-seat re-baseline** (`DRT_BASELINE`, capacity 10, fresh `PrepareLausitzDrtInputs` — fleet files still carry capacity=8) — needed for the PAPER-grade comparisons (1c + 1d), NOT for the POC.
4. **Headline sweep (paper phase)** — idleThreshold ∈ {0.25, 0.5, 0.75, 1.0} full sweep × cap ∈ {12600, 25200} two points (D6; grid only if the main curve shows interaction), all arms + re-baseline + 1c on ONE matched demand file (SHA256-verified).
