# Spike: 1d Modular (U-Shift capsule swap) — how a DRT vehicle executes an offline freight tour

**Date:** 2026-07-27
**Purpose:** de-risk the 1d Modular plan the same way `2026-07-06-shareduse-dvrp-insertion-spike.md`
de-risked 1c. Establish, from source, *how* a DRT vehicle can leave passenger service, drive to a depot,
swap capsule, execute a pre-planned multi-stop jsprit tour, and come back — and which of that is native.
**Method:** read the actual 2025.0 sources, not documentation. All file references below were read from
sources jars unpacked from the local `.m2`:

- `org.matsim.contrib:dvrp:2025.0` (`dvrp-2025.0-sources.jar`)
- `org.matsim.contrib:drt:2025.0` (`drt-2025.0-sources.jar`)
- `org.matsim.contrib:drt-extensions:2025.0` (`drt-extensions-2025.0-sources.jar`, **downloaded during
  this spike** — the local `.m2` previously held only the stale `2025.0-PR3552`)

HAGRID currently pins `matsim.version=2025.0` (bump done 2026-07-20), so `dvrp` and `drt` findings apply
to the code on `hendrik` as-is. `drt-extensions` is **not** a HAGRID dependency today.

---

## 1. Headline findings

1. **The capsule swap is a NATIVE drt-core mechanic, not an extension feature.**
   `DrtCapacityChangeTask` / `DefaultDrtCapacityChangeTask` live in **`org.matsim.contrib.drt.schedule`**
   (drt core), backed by `org.matsim.contrib.dvrp.schedule.CapacityChangeTask` (dvrp core). Both are
   already on HAGRID's classpath.
2. **The DRT insertion logic fully understands time-varying capacity.** `InsertionGenerator` walks the
   stop sequence and re-reads `getChangedCapacity()` at every stop, so a request is only inserted where
   it fits the capacity valid *at that point in the schedule*. This means "one capsule at a time" is
   enforceable by the core: a capacity change to `(passengers=0, parcels=216)` makes the cargo phase
   structurally closed to passengers.
3. **The vehicle actually performs the swap.** `DrtActionCreator` maps a `CapacityChangeTask` to
   `VehicleCapacityChangeActivity` (dvrp core) — a real timed activity in the QSim.
4. **`drt-extensions/services` cannot carry a multi-stop tour** — `DrtServiceTask` is a single
   `DefaultStayTask` at one `OperationFacility` link with one `intendedDuration`. It is therefore **a
   template to copy, not a dependency to adopt** (see §4 for why adopting it is actively unattractive).
5. **Everything 1d needs to *schedule* the excursion is demonstrated by `ServiceTaskSchedulerImpl`** —
   runtime splicing of drive + stay tasks onto a live DRT schedule, including the awkward cases
   (vehicle currently relocating, vehicle already at the target link). That code is the reference
   implementation to port and generalise from 1 stop to N stops.
6. **`drt-extensions/reconfiguration` is NOT usable as-is for 1d.** `CapacityReconfigurationEngine`
   plans capacity changes in `onPrepareSim()` and `Verify.verify(schedule.getTasks().size() == 1)` — it
   only works on a still-`PLANNED` schedule at iteration start. 1d's swap decision is a *runtime*,
   idle-gated decision. Its value is as a worked example of building the drive → stay → capacity-change
   → stay task chain, nothing more.
7. **`drt-extensions/preplanned` is the wrong shape.** `PreplannedDrtOptimizer` *replaces* the optimizer
   and serves only pre-planned requests; 1d needs online passenger dispatch running alongside offline
   freight.

**Consequence for the plan: 1d needs no new Maven dependency.** It is built from `drt`/`dvrp` core APIs,
following patterns copied from `drt-extensions` sources. This is the same conclusion 1c reached about
`DvrpLoad` (native core, no fork), and it removes the "add `drt-extensions:2025.0` + prove binary compat"
gate the backlog had pencilled in for 1d.

---

## 2. The five hook points (verbatim, with the native reference for each)

1d's `ModularDispatchModule` needs exactly five overrides. Every one has a working precedent in the
extension sources.

| # | Hook | Native reference to copy | What 1d does with it |
|---|---|---|---|
| 1 | `DrtOptimizer` decorator | `services/optimizer/DrtServiceTaskOptimizer` | Delegates all pax handling; on `notifyMobsimBeforeSimStep` runs the freight dispatcher *before* the delegate. This is where the idle-threshold gate lives. |
| 2 | `VehicleEntry.EntryFactory` decorator | `services/optimizer/DrtServiceEntryFactory` | Returns `null` for a vehicle committed to a freight excursion → excluded from pax insertion. |
| 3 | `DrtTaskFactory` decorator | `services/tasks/DrtServiceTaskFactoryImpl` | Adds factory methods for the 1d task types; delegates the rest to `DrtTaskFactoryImpl`. |
| 4 | `VrpAgentLogic.DynActionCreator` decorator | `services/schedule/DrtServiceDynActionCreator` | Turns 1d's custom tasks into `DynActivity`s; delegates everything else. |
| 5 | `ScheduleTimingUpdater.StayTaskEndTimeCalculator` decorator | `operations/shifts/schedule/ShiftDrtStayTaskEndTimeCalculator` | **Mandatory, see §3.1.** Preserves the intended duration of custom stay tasks under timing updates. |

Binding pattern for all five: `AbstractDvrpModeQSimModule` + `bindModal(...).toProvider(modalProvider(...))`,
exactly as `DrtServiceOptimizerQSimModule` does. HAGRID already uses this pattern in
[SharedUseModule.java:148-192](../../../parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/shareduse/SharedUseModule.java#L148-L192)
(`installOverridingQSimModule`), so the composition mechanism is proven in-repo.

### Reference: how `ServiceTaskSchedulerImpl` splices tasks onto a live schedule

The core move (`ServiceTaskSchedulerImpl.scheduleServiceTask`, the general `else` branch):

```
task     = schedule.getTasks().get(schedule.getTaskCount() - 1)   // the trailing STAY
lastLink = ((StayTask) task).getLink()
path     = VrpPaths.calcAndCreatePath(lastLink, toLink, departureTime, router, travelTime)
if (path.getArrivalTime() < vehicle.getServiceEndTime()) {
    if (schedule.getCurrentTask() == task) task.setEndTime(timer.getTimeOfDay());
    else                                   schedule.removeLastTask();
    schedule.addTask(taskFactory.createDriveTask(vehicle, path, RELOCATE_SERVICE_TASK_TYPE));
    // ... append the stay/service task ...
    schedule.addTask(taskFactory.createStayTask(vehicle, endTime,
                     Math.max(vehicle.getServiceEndTime(), endTime), link));   // schedule must END with STAY
}
```

Three invariants that fall out of this and apply verbatim to 1d:

- **A DRT schedule must always end with a STAY task.** Every append sequence re-adds a trailing stay.
- **Never schedule past `vehicle.getServiceEndTime()`** — `addServiceTask` silently returns if
  `startTime` or `endTime` would exceed it. 1d must decide explicitly what happens to a freight tour
  that would run past shift end (reject vs. truncate) rather than inherit a silent no-op.
- **The currently-executing trailing STAY is truncated, not removed** (`task.setEndTime(now)`); a
  not-yet-started one is removed.

---

## 3. Gotchas found (these are the reason the spike was worth doing)

### 3.1 A custom stay task will be silently DELETED or SHRUNK without a custom end-time calculator

`DrtStayTaskEndTimeCalculator.calcNewEndTime` (drt core) branches on `DrtTaskBaseType`:

- **`STAY` and not the last task** → returns `oldEndTime`, but if `oldEndTime <= newBeginTime` it returns
  `ScheduleTimingUpdater.REMOVE_STAY_TASK`. A delayed upstream task therefore **deletes** the capsule-swap
  or freight-dwell task outright.
- **`STOP`** → casts to `DrtStopTask` and calls `stopTimeCalculator.shiftEndTime(...)`. Since
  `DefaultDrtCapacityChangeTask extends DefaultDrtStopTask`, a shifted capacity-change task gets its
  duration recomputed by the *passenger* stop-time calculator from its (empty) pickup/dropoff sets — the
  7-minute swap can collapse to the generic `stopDuration`.

Both failure modes are silent — wrong timings, no exception. The two native mitigations:

- `ShiftDrtStayTaskEndTimeCalculator` — decorator that matches each custom task type first and returns
  `newBeginTime + duration`, delegating only the residual cases. **This is the pattern 1d must follow.**
- `DrtServiceTaskOptimizer.enforceIntendedDuration` — belt-and-braces re-assertion inside `nextTask`:
  if the current duration undershoots `getIntendedDuration()`, push `endTime` back out and call
  `scheduleTimingUpdater.updateTimingsStartingFromTaskIdx(vehicle, idx+1, endTime)`.

Recommendation: implement **both**, as the extension does.

### 3.2 The capacity change alone does NOT keep passengers out of the freight excursion

The capacity change closes the *cargo phase* (0 seats → nothing fits). It does **not** protect the
`drive-to-depot` leg or the `drive-back` leg, and freight stops modelled as plain stay tasks are not
`DrtStopTask` waypoints, so `InsertionGenerator` does not see them at all — it can still append a
passenger pickup after the whole excursion, or insert one into the approach leg.

`DrtServiceEntryFactory` exists precisely for this: it returns `null` so the vehicle is dropped from the
candidate set entirely. 1d needs the same, with a wider predicate — **blocked while the schedule holds
any un-performed freight task**, not just while the current task is one.

This has a design consequence worth deciding explicitly (see §5, Q2): a strict blocker means a vehicle
that has just been committed to a tour stops serving passengers *immediately*, which is the honest
reading of "one capsule at a time", but costs pax service on the approach leg.

### 3.3 `DrtServiceEntryFactory`'s own predicate is narrower than it looks

```java
if (vehicle.getSchedule().getCurrentTask() instanceof OperationalStop) return null;
int taskCount = schedule.getTaskCount();
if (taskCount > 1) {
    Task oneBeforeLast = schedule.getTasks().get(taskCount - 2);
    if (oneBeforeLast.getStatus() != Task.TaskStatus.PERFORMED
        && oneBeforeLast.getTaskType().equals(DrtServiceTask.TYPE)) return null;
}
```

It only inspects the *current* task and the *one-before-last* task. That is sufficient for the
single-stop service case and **insufficient for a multi-stop tour**. Do not copy it literally.

### 3.4 `DrtServiceTask` requires an `OperationFacility`

`DrtServiceTask`'s constructor asserts `link.getId().equals(operationFacility.getLinkId())`, and
`OperationFacility` comes from `drt-extensions/operations`. Reusing the class would drag in the
`operations` package *and* `DrtWithExtensionsConfigGroup` (see §4). 1d's swap task should be a HAGRID
class over HAGRID's existing [DepotNetwork](../../../parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/DepotNetwork.java) instead.

### 3.5 Service ids are `UUID.randomUUID()`

`ServiceTaskSchedulerImpl.addServiceTask` mints ids with `UUID.randomUUID()`. HAGRID runs must stay
reproducible — 1d must derive its tour/task ids from the jsprit plan (carrier + tour index), never from
a random UUID. Same class of issue as the 1c per-iteration id collision fixed in `dd34b23`.

---

## 4. Why NOT to add `drt-extensions:2025.0` as a dependency

`DrtServiceQSimModule` reads its parameters via
`((DrtWithExtensionsConfigGroup) drtConfigGroup).getServicesParams().orElseThrow()`. Using the
extension's own run modules therefore requires the DRT mode's config group to *be* a
`DrtWithExtensionsConfigGroup`. HAGRID composes the **native matsim-lausitz `DrtConfigGroup`** and the
whole architecture rests on "compose, do not reimplement" the native config
([DrtConfigComposer.java](../../../parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/drt/DrtConfigComposer.java)).
Swapping the config-group type is a change to the shared DRT foundation that all three scenarios sit on
— it would put Baseline comparability at risk to buy a class that cannot do multi-stop tours anyway.

**Verdict: copy the patterns, take no dependency.** This also keeps the 2025.0 binary-compat surface
exactly where the 2026-07-21 control run validated it.

---

## 5. Open design questions for the 1d design session

These are *decisions*, not unknowns — the spike answered the technical side of each.

- **Q1 — Does the cargo capsule get a capacity change at all?** Technically native and cheap
  (`DefaultDrtCapacityChangeTask` + `IntegersLoadType`, reusing 1c's 2D-load setup). It buys honest
  occupancy accounting and a second, core-enforced guarantee of exclusivity. Cost: the swap task becomes
  a `DrtStopTask` subtype and inherits gotcha §3.1's STOP branch.
- **Q2 — When exactly does the vehicle stop serving passengers?** At dispatch (strict, matches "one
  capsule at a time", loses the approach leg) or at depot arrival (lets pax ride along to the depot,
  needs the approach leg to stay divertible). §3.2 makes this a real choice, not an implementation detail.
- **Q3 — What happens to a tour that would overrun `vehicle.getServiceEndTime()`?** The native code
  silently drops it. 1d must choose reject-and-log (spec §4.3 step 5: "rejected freight requests are not
  replanned") vs. allow-overrun.
- **Q4 — Are freight stops plain stay tasks, or `DrtStopTask`s?** Plain stay tasks = clean separation,
  no passenger-engine involvement at all, and no contamination of `drt_customer_stats` (the accepted
  distortion 1c had to correct for via `pax_only.py`). This is the architecture the external
  "Dummy_Chat.txt" critique argued for, and unlike 1c it is *available* here, because Modular is the
  exclusive case, not co-riding.
- **Q5 — Does the 1c re-baseline seat basis (M1: 10 seats) apply to the Modular passenger capsule?**
  Spec §6.1 puts the U-Shift passenger capsule at 8 seats, flagged "to confirm".

---

## 6. Verification status

| Claim | Evidence |
|---|---|
| `DrtCapacityChangeTask` is in drt core | read `drt-2025.0-sources.jar!/org/matsim/contrib/drt/schedule/DrtCapacityChangeTask.java` |
| `CapacityChangeTask` is in dvrp core | read `dvrp-2025.0-sources.jar!/org/matsim/contrib/dvrp/schedule/CapacityChangeTask.java` |
| Insertion respects per-stop capacity | read `InsertionGenerator` lines 163-199, 232-239, 304-346, 387-394 |
| Vehicle performs the swap | read `DrtActionCreator.createAction` STOP branch → `VehicleCapacityChangeActivity` |
| `DrtServiceTask` is single-stop | read `services/tasks/DrtServiceTask.java` (one link, one `intendedDuration`) |
| Runtime schedule splicing works | read `services/schedule/ServiceTaskSchedulerImpl.scheduleServiceTask` |
| `reconfiguration` is prepare-sim only | read `CapacityReconfigurationEngine.onPrepareSim` + its three `Verify.verify` guards |
| `preplanned` replaces the optimizer | read `PreplannedDrtOptimizer` (implements `DrtOptimizer`, serves `PreplannedSchedules`) |
| Extension modules need `DrtWithExtensionsConfigGroup` | read `DrtServiceQSimModule` constructor cast |
| Custom stay tasks can be deleted/shrunk | read `DrtStayTaskEndTimeCalculator.calcNewEndTime` both branches |

**Not verified (deliberately out of spike scope, must be proven during implementation):** that a
capacity-change task and HAGRID's `ReturnToDepotRebalancingModule` compose without fighting over the
same trailing stay task; and the end-to-end timing of a full multi-stop excursion. Both are e2e-test
territory, not source-reading territory.
