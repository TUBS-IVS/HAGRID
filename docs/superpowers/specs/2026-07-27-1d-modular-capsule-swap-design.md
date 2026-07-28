# Design: 1d Modular (U-Shift capsule swap) — DRT_MODULAR scenario

**Date:** 2026-07-27
**Status:** implemented 2026-07-28 (plan `docs/superpowers/plans/2026-07-27-1d-modular-capsule-swap.md`)
**Revision 2026-07-28 (grilling pass, user-decided):** §4.3's "before the vehicle shift end" is concretised as a **full delivery day 07:30–21:00 — 1d has NO dispatch waves** (parcels arrive at the depot overnight; same-day delivery counts, time of day does not). The LMD wave windows would have made a 3.5 h tour expire ~46 min after its wave and killed the midday-lull story. Also added: interleaved dispatch order across providers, `tours_completed_late`/`parcels_served_late` KPIs. Details: plan C4 (revised)/C7/C8 in `../plans/2026-07-27-1d-modular-capsule-swap.md`.
**Parent spec:** `2026-06-17-lausitz-drt-freight-integration-design.md` §4.3 (Modular), §4.4 (autonomy switch)
**Spike:** `docs/superpowers/notes/2026-07-27-modular-capsule-swap-dvrp-spike.md` (source-verified against
`dvrp`/`drt`/`drt-extensions` 2025.0 — read it before planning; all "native reference" pointers below
are grounded there)
**Precedent:** 1c Shared-Use (`2026-07-06-1c-shareduse-cargo-hitching.md`, executed + validated 2026-07-27)

## 1. Purpose and scope

Third scenario of the Hoyerswerda integrated pax+freight study: a single-operator DRT fleet whose
vehicles swap between a **passenger capsule** and a **cargo capsule** at a depot — one capsule at a
time (spec §4.3). Freight tours are planned **offline by jsprit** (as in the Baseline); the dispatch
decision (which vehicle, when) is **online and passenger-primary** via an idle-fleet threshold.

In scope: conventional operation (Opt 2 code path), full run wiring, KPIs, validation runs.
Out of scope (deferred, unchanged): §4.4 autonomy switch (own follow-up plan after 1c+1d — the
per-stop dwell stays parameterised so that plan only flips parameters); Opt 1 mobile Packstation
(Phase 2); single-pool consolidated jsprit (backlogged as sensitivity idea, see §10).

## 2. Decisions (user-decided 2026-07-27)

- **D1 — Spike before plan.** Done; see spike note. Headline: capsule swap = native drt-core
  `DefaultDrtCapacityChangeTask`; `InsertionGenerator` natively respects time-varying capacity;
  **no new Maven dependency** (`drt-extensions` is a pattern source only — its modules require
  `DrtWithExtensionsConfigGroup`, incompatible with HAGRID's composed native Lausitz config, and its
  `DrtServiceTask` is single-stop anyway).
- **D2 — Pax lockout at dispatch (strict).** From the moment a tour is assigned, the vehicle leaves the
  passenger candidate set (EntryFactory returns `null` while the schedule holds any un-performed freight
  task). Honest reading of "one capsule at a time"; keeps the jsprit tour start deterministic. The pax
  service lost on the approach leg IS the measured integration cost.
- **D3 — Passenger capsule = 10 seats** (= 1c-M1 base vehicle, spec §6.1's "8, to confirm" overridden).
  The pax capsule carries no parcels, so there is no physical reason for seat loss; this makes
  Baseline vs. Modular a single-factor comparison (vehicle-time given to freight), unconfounded by a
  capacity penalty. `DrtInputsFingerprint.expectedCapacity` already returns `BASE_SEATS`(10) for
  DRT_MODULAR — no change needed there (verified).
- **D4 — Tour planning: 7 depot groups, vehicle type swapped.** The existing 7-carrier structure stays
  and is *read* as "7 depots of one operator"; only the vehicle type changes to the cargo capsule.
  Parcel→depot follows the same provider rule as 1c-M4 → 1c and 1d stay directly comparable, real depot
  geography preserved, smallest change to proven code. Single-pool jsprit (free depot choice) and the
  consolidation/integration decomposition are **backlogged** as later sensitivity ideas.
- **D5 — Tour-duration cap 3.5 h (concept parameter), 7.0 h control arm.** Rationale (user + analysis):
  Modular's entire content is *reversibility* — the fleet re-decides during the day how much of itself
  to give to freight. A 7 h binding makes that decision once per day and degenerates Modular into "a
  fixed sub-fleet drives parcels all day" (= Baseline with shared ownership); the idle-threshold gate
  then has nothing to regulate. Back-of-envelope (segment ~6 parcels → ~12 min/stop, ~40 % drive share,
  7,271 parcels): total freight vehicle-hours are nearly cap-invariant (~464 vs ~496 h); the cap only
  chooses between ~61 all-day absences and ~121 short ones. Cost of the short cap (more swaps, more
  deadhead per parcel) is itself a reported 1d result. The 7.0 h arm is the constructive degeneration
  proof, analogous to 1c's χ→0 arm.
- **D6 — Sweep design: idle-threshold full sweep (policy knob, M6-analog), cap only {3.5 h, 7.0 h}.**
  No full grid unless the main curve shows interaction.
- **D7 — Freight stops are plain stay tasks, NOT `DrtStopTask`s.** Parcels never become agents; the
  passenger engine is never touched. This is the real freight/pax separation the external critique
  demanded — available here (exclusive case) unlike 1c (co-riding). Consequences: `drt_customer_stats`
  stays uncontaminated (no `pax_only.py` correction needed for Modular's vehicle KPIs beyond what 1c
  already handles), and rebalancing needs no pax-only estimator variant (no phantom depot departures).
- **D8 — 216-parcel capsule capacity is documented as never binding.** 216 × 2 min = 7.2 h pure dwell
  exceeds any cap ≤ 7 h; time always binds first. Stated in methodology, not hidden in a vehicle file.

## 3. Architecture

New package `hagrid.integrated.modular`, composed exactly like
`hagrid.integrated.shareduse.SharedUseModule` (controller half + `installOverridingQSimModule`).
No new dependency; all types from `drt`/`dvrp` core, patterns copied from `drt-extensions` sources.

### 3.1 The freight excursion as a task chain

Spliced onto a live schedule at dispatch time (generalising
`ServiceTaskSchedulerImpl.scheduleServiceTask` from 1 stop to N — see spike §2 for the splice
invariants: schedule must end with STAY; truncate a running trailing STAY, remove a pending one;
never schedule past `serviceEndTime`):

```
drive→depot | CapacityChange(pax=0, parcels=216), 7 min          ← swap = native DefaultDrtCapacityChangeTask
| (drive→stop, ModularFreightStopTask dwell)  × N                ← plain stay tasks (D7)
| drive→depot | CapacityChange(pax=10, parcels=0), 7 min | STAY
```

The capacity change **is** the swap — no separate swap activity class. Stop dwell = the jsprit plan's
service duration (2 min/parcel, cap 15 min — parity with `LausitzFreightPreprocessor`), multiplied by a
`deliveryDwellFactor` parameter fixed at 1.0 in this plan (the §4.4 autonomy plan's hook).

### 3.2 The six hooks (all with native reference implementations, see spike §2)

| # | Hook | Pattern source | 1d role |
|---|---|---|---|
| 1 | `DrtOptimizer` decorator | `DrtServiceTaskOptimizer` | dispatcher tick in `notifyMobsimBeforeSimStep`; `enforceIntendedDuration` in `nextTask` |
| 2 | `VehicleEntry.EntryFactory` decorator | `DrtServiceEntryFactory` | `null` while schedule holds any un-performed freight task (D2). Do NOT copy the template's narrow current/one-before-last predicate (spike §3.3) |
| 3 | `DrtTaskFactory` decorator | `DrtServiceTaskFactoryImpl` | creates `ModularFreightStopTask`; capacity changes use the native task directly |
| 4 | `DynActionCreator` decorator | `DrtServiceDynActionCreator` | freight stop → `DynActivity`; capacity change is handled natively by `DrtActionCreator` |
| 5 | `StayTaskEndTimeCalculator` decorator | `ShiftDrtStayTaskEndTimeCalculator` | **mandatory** — without it the core silently deletes delayed freight tasks (`REMOVE_STAY_TASK`) or recomputes swap duration via the pax stop-time calculator (spike §3.1). Implement both belts: calculator + `enforceIntendedDuration` |
| 6 | Schedule splicer | `ServiceTaskSchedulerImpl` | builds §3.1's chain; handles divert-from-RELOCATE |

### 3.3 Dispatcher semantics

- **Submission:** each jsprit tour becomes a `FreightTourRequest` at
  `submissionTime = plannedTourStart − (travelToDepot_est + retoolingTime)` (spec §4.3).
- **Pending:** a submitted request waits in the dispatcher; the gate is evaluated every simstep:
  dispatch iff `idleVehicleShare > idleThreshold`. Idle = current task is a STAY-base task and the
  vehicle is not committed to freight (same predicate family as hook 2).
- **Vehicle selection:** nearest idle vehicle to the tour's depot (minimises approach deadhead);
  deterministic tie-break by vehicle id. IDs for tours/tasks derive from carrier + tour index — never
  `UUID.randomUUID()` (spike §3.5; reproducibility, and the 1c iteration-reset lesson `dd34b23` applies:
  dispatcher state must reset per iteration).
- **Terminal failure:** a pending request fails when its latest feasible start passes — i.e. when even
  an immediate dispatch could no longer complete tour + return swap before the vehicle shift end
  (`serviceEndTime`); the native code's silent drop (spike §2 invariant) is replaced by an explicit
  reject-and-log. No replanning (spec §4.3 step 5).

### 3.4 Runner wiring — a third case (verified against `SimulationRunnerUtils`)

| Concept | jsprit preprocessing | CarrierModule in mobsim | freight execution |
|---|---|---|---|
| DRT_BASELINE (married) | yes | yes | carrier vans |
| DRT_SHAREDUSE | no (`freight` ignored) | no | parcels ride DRT via passenger engine |
| **DRT_MODULAR** | **yes** | **NO** | **DRT fleet via task splicing** |

`LausitzFreightPreprocessor.run` executes (7h→cap parameter, cargo-capsule vehicle type), but
`FreightRunComposer.addCarriers`/`installCarrierModules` must NOT run — otherwise parcels are delivered
twice (once by phantom carrier vans, once by the DRT fleet). The CarrierPlans are handed to the
`ModularDispatchModule` instead. `freight=true|false` is ignored for DRT_MODULAR (logged, like the
SHAREDUSE branch). New runner key `idleThreshold` (default 0.50), parsed like `chiThreshold`;
`maxTourDuration` (default 12600 s) threaded into the preprocessor.

### 3.5 Preprocessing changes

`LausitzFreightPreprocessor`/`LmdCarrierBuilder` stay, three deltas: (1) vehicle type → one U-Shift
cargo-capsule type (216) instead of the 3 van types; (2) `HAGRIDRouterUtils.MAXROUTEDURATION` (25200)
becomes a passed-through parameter — careful: `LmdCarrierBuilder:143` derives the vehicle time window
from it, so the wave-window logic moves with it (Hannover/LMD paths keep 7 h by default); (3)
parcel→depot per provider rule (1c-M4 parity). Demand file: **same level as 1c's headline runs** —
mismatched demand levels would break the cross-scenario comparison. **Correction (same day, evening):
`level_central` is now 6,024 parcels (Zensus-2022 building stock); the 7,271 originally written here is
the retired OSM stand, since renamed `level_osm_central`.** The reversibility back-of-envelope in D5 was
computed on 7,271 — its conclusion (freight vehicle-hours nearly cap-invariant; the cap chooses between
few long and many short absences) is scale-invariant and therefore unaffected, but the absolute hour
figures (~464 / ~496 h) scale down by ~17 % at the Zensus level.

## 4. KPIs and events

Plain stay tasks emit no native events (D7), so 1d mints its own — pattern
`DrtServiceScheduledEvent` family: `ModularTourScheduled/Started/Completed` + per-stop served counts. A
`ModularKpiHandler` (controller scope, per-iteration reset) writes **run-ID-prefixed**
`modular_tour_stats.csv` (the 1c bare-filename bug `89f1ee5` is designed out from the start).

Reported: δ with decomposition `expired_pending` vs `dispatched_incomplete` (§3.3); tour completion
rate; swaps and retooling-hours per vehicle-day; freight-attributable deadhead-km; pax-side: vehicle-
hours withdrawn from pax service vs. wait/rejection deltas against the 10-seat Baseline. New
`extract_modular.py` registered in `build_kpis.EXTRACTORS` (1e contract). PPC does not apply (no
co-riding discomfort); the freight-attributable vehicle-hours serve the same welfare decomposition.

## 5. Parameters

| Parameter | Value | Source/decision |
|---|---|---|
| Pax capsule seats | 10 | D3 (= 1c-M1 base) |
| Cargo capsule slots | 216, documented never-binding | spec §6.1 + D8 |
| Retooling (pure swap) | 420 s | spec §6.1; sensitivity 2–15 min later |
| Tour cap | 12600 s default; 25200 s control arm | D5 |
| Idle threshold | sweep; default 0.50 | D6, spec §6.1 |
| Look-ahead | approach + retooling | spec §4.3 |
| Freight stop dwell | 2 min/parcel, cap 15 min, × factor 1.0 | LMD parity; autonomy hook |

## 6. Validation

- **Smoke run** (maxIter=1, real data, fleet 120) — parcels leave on DRT vehicles, conservation of
  tour/parcel counts in `modular_tour_stats.csv`, no CarrierModule output present.
- **idleThreshold=1.0 control** — gate never opens → zero tours → pax KPIs must reproduce the 10-seat
  Baseline. 1d's χ→0 analog; expected **bit-identical** (no dispatch → no schedule splicing → unlike 1c
  there is not even an RNG perturbation from extra population members).
- **e2e risk to cover:** `ReturnToDepotRebalancingModule` also appends to the trailing stay at shift
  end — two components, one schedule tail; must be proven compatible by an e2e test, plus full-chain
  timing of one multi-stop excursion (spike §6 "not verified").

## 7. Dependencies

1. **10-seat re-baseline run is now a prerequisite for 1d as well** (previously deferred "1c-only").
2. Demand level pairing with 1c headline (`level_central`) — see §3.5.
3. 1e extractor contract (already landed).

## 8. Open items intentionally NOT in this design

Autonomy switch plan (after 1c+1d; `IntegratedScenarioConfig` is its integration point); Opt 1 mobile
Packstation (Phase 2); retooling-time / depot-count sensitivities (spec §11, after headline).

## 9. Backlog notes filed with this design

Single-pool jsprit (free depot choice) + consolidation-vs-integration decomposition as later
sensitivity ideas under the `[H]` Modular item in `docs/BACKLOG.md`.

## 10. Plan concretisations (accepted during implementation, 2026-07-28)

The implementation plan (`../plans/2026-07-27-1d-modular-capsule-swap.md`) recorded eight
concretisations against this design during planning/implementation, all accepted:

- **C1** — Hook 3 (`DrtTaskFactory` decorator) dropped; only the schedule splicer ever
  constructs 1d task instances directly, so the decorator has nothing to do. No behaviour change.
- **C2** — Hook 4 (`DynActionCreator` decorator) dropped; the native `DrtActionCreator` already
  renders the freight stop/swap correctly. Cosmetic-only consequence (QSim activity label reads
  "DrtStay"); the KPI events (Task 3/7) carry the real semantics.
- **C3** — Divert-from-RELOCATE not implemented; the dispatcher's candidate pool is idle vehicles
  only, so the splicer's relocate-divert branch is unreachable and asserts its precondition
  instead of handling it.
- **C4 (REVISED 2026-07-28, user decision)** — Expiry envelope = the full delivery day
  07:30–21:00, **no dispatch waves** (parcels arrive at the depot overnight; same-day delivery
  counts, time of day does not). Replaces the design's implicit LMD-wave reading, which would have
  expired a 3.5 h tour ~46 min after its wave and killed the midday-lull story.
- **C5** — Swap capacities are 1-D (`passengers` `DvrpLoadType` only); parcels are never DVRP
  loads (D7), so the design's `CapacityChange(pax=0, parcels=216)` notation is realised as
  `CapacityChange(passengers=0)` plus documentation of the never-binding 216 capsule slots (D8).
- **C6** — `ModularCapacityChangeTask` subclasses `DefaultDrtCapacityChangeTask` (design §3.2 said
  "use the native task directly"), adding only identity metadata (`tourId`, `swapBack`,
  `intendedDuration`) needed by the end-time calculator, the commitment predicate and the KPI
  events; native swap mechanics are inherited unchanged.
- **C7 (2026-07-28)** — The ~07:16 morning surge (all tours share `plannedStart ≈ 07:30`, so the
  gate dispatches ~(1−θ)·fleet vehicles in one simstep) is accepted as a concept property, not
  fixed. Pending order is interleaved across providers (`submissionTime, tourIndex, provider`)
  instead of alphabetical `tourId`, to avoid a systematic per-provider δ bias when the gate is
  scarce.
- **C8 (2026-07-28)** — Late delivery is measured, not prevented: dispatch-time feasibility uses
  planned times, but mobsim delays can push actual completion past 21:00 while the tour still
  counts COMPLETED. `tours_completed_late`/`parcels_served_late` make that ex-post violation of
  the 07:30–21:00 promise visible without changing dispatch behaviour.

- **C9 (found during implementation, Task 8) — corrects the design's own draft, not just the
  plan's.** The plan's `ModularOptimizer.nextTask` draft called
  `scheduleTimingUpdater.updateBeforeNextTask` explicitly and ran the intended-duration belt
  *before* delegating. That is wrong: `DefaultDrtOptimizer.nextTask` already makes that call, and a
  second consecutive call is not idempotent whenever `drtCfg.isUpdateRoutes()` is true, because
  `ScheduleTimingUpdater`'s guard has a time-independent `driveTaskUpdater != NOOP` operand. The
  double update silently undid the belt's repair on every task transition. The implemented order
  is: capture the previous task, delegate, then enforce intended durations, then notify. The
  drt-extensions template the plan derived its draft from carries the same latent bug.
