# Shared-Use DVRP Insertion Spike — 2026-07-06

## Purpose
De-risk the Shared-Use scenario (spec §4.2, Step C): online insertion of PARCEL
segment-stops into the passenger DRT fleet with split 2D capacity
(seatsOccupied, parcelsOnboard) — in the MATSim version HAGRID actually runs
(`matsim.version = 2025.0-PR3552`). Every claim below is backed by sources
unzipped from the resolved jars (ground truth), unless explicitly flagged
UNVERIFIED.

**Evidence base**
- PR3552 sources: `~/.m2/repository/org/matsim/contrib/{drt,dvrp}/2025.0-PR3552/*-sources.jar`
  (sources classifier DOES exist on repo.matsim.org; fetched via `mvn dependency:get -Dclassifier=sources`).
- Release sources: `~/.m2/repository/org/matsim/contrib/{drt,dvrp}/2025.0/*-sources.jar` (fetched for the upgrade-path analysis).
- Core sources: `~/.m2/repository/org/matsim/matsim/2025.0-PR3552/matsim-2025.0-PR3552-sources.jar`.
- Version forensics: `2025.0-PR3552` is the PR build of matsim-libs **PR #3552 "Pt pax volumes into pt dashboard"
  (vsp-gleich), squash-merged to master 2024-11-13 as commit `7b0d465`** — i.e. the jar reflects
  matsim master of **Nov 2024**. The final **2025.0 release jars were built 2025-04-11** and contain
  ~5 more months of master. matsim-lausitz 2.0's parent is **`org.matsim:matsim-all:2025.0-PR3552`**
  (verified in `matsim-lausitz-2.0.pom`) — HAGRID's pin exists to match it exactly.

**Headline finding:** the DvrpLoad / multi-dimensional-capacity machinery does **NOT exist in
2025.0-PR3552** (capacity is a scalar `int` end-to-end), but it **exists complete in the final
2025.0 release** — same major version, released 2025-04-11. The Shared-Use 2D capacity therefore
requires bumping `matsim.version` from `2025.0-PR3552` → `2025.0`, which is the *smallest possible*
upgrade (matsim-lausitz 2.0 was compiled against 2025.0-PR3552, i.e. an older state of the same
release line).

---

## 1. DvrpLoad / multi-dimensional capacity

### In 2025.0-PR3552 (what HAGRID runs today): NOT present
No `org.matsim.contrib.dvrp.load` package, no class matching `*Load*` in either jar
(`find -iname "*load*"` over both unzipped jars: zero hits). Capacity is scalar:

```
// dvrp-2025.0-PR3552: org.matsim.contrib.dvrp.fleet.DvrpVehicle (javap -p)
public interface DvrpVehicle extends Identifiable<DvrpVehicle> {
  int getCapacity();
  ...
}
// DvrpVehicleSpecification: int getCapacity();
// ImmutableDvrpVehicleSpecification$Builder: Builder capacity(int);
// PassengerRequest: int getPassengerCount();
```

### In 2025.0 (release, 2025-04-11): complete
Package `org.matsim.contrib.dvrp.load` with `DvrpLoad`, `DvrpLoadType`, `IntegerLoad(Type)`,
`IntegersLoad(Type)`, `DvrpLoadParams`, `DvrpLoadModule`, `DvrpLoadFromFleet`, `DvrpLoadFromVehicle`
(+ `DvrpLoadFromTrip` in `dvrp.passenger`, `CapacityLoadAnalysisHandler` in `dvrp.analysis`).
This is Tarek Chouaki's multidimensional-load work (MUM25 paper "Multidimensional vehicle loads
and capacities for Demand Responsive Transport in MATSim").

```java
// dvrp-2025.0: org.matsim.contrib.dvrp.load.DvrpLoad
public interface DvrpLoad {
    DvrpLoad add(DvrpLoad other);
    DvrpLoad subtract(DvrpLoad other);
    boolean fitsIn(DvrpLoad other);
    boolean isEmpty();
    Number getElement(int i);
}
// DvrpLoadType: fromMap(Map<String,Number>), getEmptyLoad(), getDimensions(),
//               size(), serialize(DvrpLoad), deserialize(String)
// IntegersLoadType(String... dimensions)   // e.g. new IntegersLoadType("passengers","parcels")

// dvrp-2025.0: DvrpVehicle
DvrpLoad getCapacity();
void setCapacity(DvrpLoad capacity);
```

**Config selection of the load type** — per drt mode, param set `load` inside the drt config group:

```java
// drt-2025.0: DrtConfigGroup
public DvrpLoadParams addOrGetLoadParams()          // param set SET_NAME = "load"
// DvrpLoadParams fields (@Parameter):
//   List<String> dimensions = ["passengers"]       // >1 dimension => IntegersLoadType
//   String mapVehicleTypeSeats = "passengers"; mapVehicleTypeStandingRoom/Volume/Weight/Other
//   String mapFleetCapacity = "passengers"         // fleet-XML scalar capacity -> this dimension
//   String defaultRequestDimension = "passengers"  // unit load for requests w/o attributes
//   int analysisInterval                            // capacity/load analysis output
```
`DvrpLoadModule.install()` binds modal `DvrpLoadType` — `IntegerLoadType` if 1 dimension,
`IntegersLoadType` otherwise; it is installed by `FleetModule`, which `DrtModeModule` calls with
`drtCfg.addOrGetLoadParams()` (drt-2025.0 `DrtModeModule` line 77-80).

**How a request declares its load** (2025.0 flow, all verified in source):
1. Person or trip attributes: `dvrp:load:<dimension> = <Number>` or serialized `dvrp:load`
   (`DefaultDvrpLoadFromTrip.LOAD_ATTRIBUTE_PREFIX = "dvrp:load:"`); fallback = unit load in
   `defaultRequestDimension`.
2. `DrtRouteCreator` (constructor takes `DvrpLoadFromTrip loadCreator, DvrpLoadType loadType`)
   stamps the load into the `DrtRoute` route description at routing time
   (`DrtRoute.setLoad(DvrpLoad, DvrpLoadType)` — serialized string in the route).
3. `DrtRequestCreator(String mode, EventsManager, DvrpLoadType)` reads it back:
   `load = load.add(drtRoute.getLoad(dvrpLoadType))` and builds
   `DrtRequest.newBuilder()...load(load)`; `DrtRequest.getLoad()` returns `DvrpLoad`.
4. Even `DrtRequestSubmittedEvent`/`PassengerRequestSubmittedEvent` carry `(DvrpLoad load,
   String serializedDvrpLoad)` — parcel/pax classification is possible from the event stream alone.

**Fleet side (2025.0):** the dvrp fleet XML still has a scalar `capacity` attribute;
`FleetReader(FleetSpecification, DvrpLoadFromFleet)` maps it through
`DvrpLoadFromFleet.getDvrpVehicleLoad(int capacity, Id<DvrpVehicle>)` into ONE dimension
(`mapFleetCapacity`). For a true 2D fleet either (a) override the modal `DvrpLoadFromFleet`
binding (bound in `FleetModule`, controller scope) to return
`loadType.fromMap(Map.of("passengers", 10, "parcels", 20))`, or (b) use the matsim-Vehicles
path (`DvrpLoadFromVehicle`, maps VehicleType seats/standingRoom/other onto dimensions).
(a) is the minimal change given HAGRID's `DrtFleetGenerator` already writes the fleet XML.

**Upgrade cost 2025.0-PR3552 → 2025.0** (compile-visible API drift found while diffing sources):
- `PassengerRequestCreator.createRequest(..., Route route, ...)` → `(..., List<Route> routes, ...)`.
- `DrtRequestCreator` constructor gains `DvrpLoadType`.
- `DefaultUnplannedRequestInserter` constructor gains `RequestFleetFilter`.
- `DvrpVehicle.getCapacity()` `int` → `DvrpLoad` (any HAGRID code reading fleet capacity must adapt;
  `Waypoint.Stop.outgoingOccupancy` int → DvrpLoad likewise).
- HAGRID itself binds none of these today (composition is config + module install only), so the
  expected breakage surface is small; matsim-lausitz 2.0 is a binary dep compiled against
  PR3552 — UNVERIFIED whether it links cleanly against 2025.0 (must run the existing
  DrtBaseline E2E tests after bumping; `PtAndDrtFareModule` and `LausitzScenario` touch config
  and scoring APIs, not dvrp internals, so risk is moderate-low).

---

## 2. Request entry path for non-passenger cargo

The ONLY native path into the optimizer is `VrpOptimizer.requestSubmitted(Request)`, called from
exactly two places (both verified in PR3552 source):

1. **`DefaultPassengerEngine.handleDeparture(...)` → `validateAndSubmitRequest(...)`** — an agent
   with a `drt`-mode leg departs; `PassengerRequestCreator.createRequest(...)` builds the request:

```java
// dvrp-PR3552: DefaultPassengerEngine.handleDepartureImpl (immediate request)
request = requestCreator.createRequest(internalPassengerHandling.createRequestId(),
        groupIds, route, getLink(fromLinkId), getLink(toLinkId), now, now);
...
synchronized (optimizer) {
    optimizer.requestSubmitted(request);   // async; rejection comes back via events
}
```

2. **`PrebookingManager.processBookingQueue(...)`** — same `optimizer.requestSubmitted(request)`,
   but decoupled from departure. However `prebook(...)` REQUIRES a live mobsim agent whose plan
   contains the leg:

```java
// drt-PR3552: PrebookingManager
public void prebook(MobsimAgent agent, Leg leg, double earliestDepartureTime)
// internally: WithinDayAgentUtils.getModifiablePlan(personLeg.agent()) + "past leg" check;
// the request<->leg link is a leg attribute "prebookedRequestId:<mode>"
```

**Evaluation of the three candidate paths:**

- **(a) Prebooking without a physically-appearing passenger: NO.** The pickup is executed by
  `PrebookingStopActivity`: while the booked agent has not arrived at the pickup link, the
  vehicle **waits at the stop** (`isLastStep()` stays false), until
  `abandonVoter.abandonRequest(now, vehicle, request)` (default `MaximumDelayAbandonVoter`,
  `prebookingParams.maximumPassengerDelay`) → `prebookingManager.abandon(...)` → unschedule +
  `PassengerRequestRejectedEvent` with cause `"abandoned by vehicle"`. A never-appearing parcel
  "ghost" would block a shared vehicle at the depot for `maximumPassengerDelay` seconds — unusable.
  Prebooking is an optional ADD-ON to (b) (submit at 00:00 for a 08:00 departure), not a
  replacement for an agent.
- **(b) Dummy parcel-agents in the population: YES — recommended.** One `Person` per SEGMENT stop,
  plan = `activity(depotLink, endTime=t_submit) → leg(drt) → activity(segmentLink)`. Departure
  triggers native request creation/validation/submission; the agent physically waits at the depot
  link, so pickup succeeds; the "ride" of the parcel-agent IS the delivery; dropoff at the segment
  link completes the request. Zero custom engine code; works identically in PR3552 and 2025.0.
  In 2025.0 the person carries `dvrp:load:parcels = <n>` so the request consumes n parcel slots
  and 0 seats.
- **(c) Direct synthetic `DrtRequest` submission (no agent): NO.** `DefaultDrtOptimizer.requestSubmitted`
  is public and callable from a custom QSim engine, and insertion/scheduling would work — but at
  the pickup stop the executor calls
  `DefaultPassengerEngine.tryPickUpPassengers(...)` → `activePassengers.get(requestId)` returns
  `null` → `Verify.verify(pickedUp, "Not possible without prebooking")` crashes (PR3552 source,
  line 272-278). Avoiding that means replacing the modal `PassengerEngine` — one binding per mode,
  shared with real passengers: invasive and fights the contrib.

**Recommendation: (b)**, with staggered `t_submit` (e.g. all at operation start, or batched) —
this also gives Step C its "pending" pool naturally (see §3/§6).

---

## 3. Insertion cost hook for χ < χ_threshold

**Where bound:** `InsertionCostCalculator` is a **modal QSim-scope binding** in
`DrtModeOptimizerQSimModule` (drt-PR3552, line 107):

```java
// drt-PR3552: DrtModeOptimizerQSimModule.configureQSim()
bindModal(InsertionCostCalculator.class).toProvider(modalProvider(
        getter -> new DefaultInsertionCostCalculator(getter.getModal(CostCalculationStrategy.class),
                drtCfg.addOrGetDrtOptimizationConstraintsParams().addOrGetDefaultDrtOptimizationConstraintsSet())));
```

```java
// drt-PR3552: InsertionCostCalculator
public interface InsertionCostCalculator {
    double INFEASIBLE_SOLUTION_COST = Double.POSITIVE_INFINITY;
    double calculate(DrtRequest drtRequest, Insertion insertion, DetourTimeInfo detourTimeInfo);
}
```

The returned cost is **`detourTimeInfo.getTotalTimeLoss()`** = additional vehicle operating time in
seconds (`CostCalculationStrategy.RejectSoftConstraintViolations/DiscourageSoftConstraintViolations`)
— exactly the spec's "marginal insertion cost χ". The insertion search
(`ExtensiveInsertionSearchQSimModule` injects the modal `InsertionCostCalculator` into both the
admissible pre-filter and the final `BestInsertionFinder`) picks the min-cost feasible insertion;
if ALL insertions evaluate to `INFEASIBLE_SOLUTION_COST`, `findBestInsertion` returns empty.

**Per-request-type decoration: YES.** `DrtRequest` carries `getPassengerIds()` (and in 2025.0
`getLoad()`), so a decorator can detect parcel requests (person-id prefix convention, or
load dimension) and return `INFEASIBLE_SOLUTION_COST` whenever
`delegate.calculate(...) > chiThreshold`:

```java
// SharedUse χ-gate (QSim scope, installOverridingQSimModule — see §7)
bindModal(InsertionCostCalculator.class).toProvider(modalProvider(getter -> {
    var delegate = new DefaultInsertionCostCalculator(getter.getModal(CostCalculationStrategy.class), constraintsSet);
    return (req, insertion, detour) -> {
        double cost = delegate.calculate(req, insertion, detour);
        return isParcel(req) && cost > chiThreshold ? InsertionCostCalculator.INFEASIBLE_SOLUTION_COST : cost;
    };
}));
```

**`DrtOfferAcceptor`** (`Optional<AcceptedDrtRequest> acceptDrtOffer(DrtRequest request,
double departureTime, double arrivalTime)`, bound modally in QSim scope, same module, lines
150-152) fires AFTER the best insertion is found, and only sees times, not the marginal cost —
wrong hook for χ. **`RequestFleetFilter` does not exist in PR3552** (zero grep hits); it exists in
2025.0 (`Collection<VehicleEntry> filter(DrtRequest, Map<Id<DvrpVehicle>,VehicleEntry>, double now)`,
default-bound to `RequestFleetFilter.none` in `DrtModeOptimizerQSimModule`) — it is a fleet
PRE-filter, also not a cost hook. The `InsertionCostCalculator` decorator is the right extension
point in both versions.

**Pending vs finally rejected** (`DefaultUnplannedRequestInserter`, PR3552 — identical logic in 2025.0):

```java
private void retryOrReject(DrtRequest req, double now, String cause) {
    if (!insertionRetryQueue.tryAddFailedRequest(req, now)) {
        eventsManager.processEvent(new PassengerRequestRejectedEvent(now, mode, req.getId(),
                req.getPassengerIds(), cause));   // cause: "no_insertion_found" | "offer_rejected"
    }
}
```

`DrtRequestInsertionRetryQueue` (bound in `DrtModeOptimizerQSimModule` line 92, QSim scope) is
driven by config param set **`dvrpRequestRetry`** (`DrtRequestInsertionRetryParams`:
`int retryInterval = 120`, `double maxRequestAge = 0` — 0 = no retry). A failed request is
re-queued while `submissionTime + maxRequestAge >= now + retryInterval`; on each retry its
`latestStartTime`/`latestArrivalTime` are SHIFTED by the elapsed delta (sliding window). So
**"pending" = config**: set `maxRequestAge ≈ serviceEnd`. CAVEAT: the queue is mode-global —
passenger rejections would also be retried all day, silently changing pax KPIs vs the DRT_BASELINE.
Fix: the queue is a concrete class with a public non-final `tryAddFailedRequest`; bind a subclass
that returns `false` for non-parcel requests (native immediate pax rejection) and applies the long
age only to parcels.

---

## 4. Per-request stop duration

Two cooperating extension points, BOTH per-drt-mode (verified PR3552, unchanged in 2025.0):

```java
// drt-PR3552: org.matsim.contrib.drt.stops
public interface PassengerStopDurationProvider {
    double calcPickupDuration(DvrpVehicle vehicle, DrtRequest request);
    double calcDropoffDuration(DvrpVehicle vehicle, DrtRequest request);
}
public interface StopTimeCalculator {
    double initEndTimeForPickup(DvrpVehicle vehicle, double beginTime, DrtRequest request);
    double updateEndTimeForPickup(DvrpVehicle vehicle, DrtStopTask stop, double insertionTime, DrtRequest request);
    double initEndTimeForDropoff(DvrpVehicle vehicle, double beginTime, DrtRequest request);
    double updateEndTimeForDropoff(DvrpVehicle vehicle, DrtStopTask stop, double insertionTime, DrtRequest request);
    double shiftEndTime(DvrpVehicle vehicle, DrtStopTask stop, double beginTime);
}
```

**Where bound: CONTROLLER scope** (`DrtModeModule.install()`, PR3552 lines 88-107) — NOT QSim scope:
- `PassengerStopDurationProvider` → `StaticPassengerStopDurationProvider.of(drtCfg.stopDuration, 0.0)`.
- `StopTimeCalculator` → **without prebooking: `DefaultStopTimeCalculator(drtCfg.stopDuration)`,
  which IGNORES the provider entirely** (fixed stopDuration; extra pickups at the same stop do not
  extend it). With prebooking:
  `MinimumStopDurationAdapter(new PrebookingStopTimeCalculator(provider), drtCfg.stopDuration)`.

So overriding ONLY `PassengerStopDurationProvider` does nothing to schedules unless the
`StopTimeCalculator` binding is replaced too. Available provider-based calculators:
`CumulativeStopTimeCalculator` (durations SUM at a shared stop) and `ParallelStopTimeCalculator`
(durations MAX at a shared stop — replicates native pax semantics while letting a parcel-segment
stop stretch to its own dwell). Recommended:
`new MinimumStopDurationAdapter(new ParallelStopTimeCalculator(sharedUseProvider), paxStopDuration)`
with `sharedUseProvider` returning segment dwell (from HAGRID's segment delivery-time model, stored
as person attribute on the parcel-person, e.g. B2B ≈120 s door, B2C locker-batch 30-60 s + door
fallback) for parcel requests and native values for pax.

**Does the insertion SEARCH honor it? YES — verbatim:** `InsertionDetourTimeCalculator`
(used inside `InsertionGenerator`/insertion search, constructed with the modal `StopTimeCalculator`) calls it
when evaluating every candidate insertion:

```java
// drt-PR3552: InsertionDetourTimeCalculator.calcPickupDetourInfo
double departureTime = stopTimeCalculator.initEndTimeForPickup(vEntry.vehicle, arrivalTime, drtRequest);
double stopDuration = departureTime - arrivalTime;
double pickupTimeLoss = toPickupTT + stopDuration + fromPickupTT - replacedDriveTT;
```

and `DefaultRequestInsertionScheduler` + `DrtStayTaskEndTimeCalculator` use the same instance for
schedule execution — search and execution stay consistent. The stretched parcel dwell therefore
automatically inflates χ for insertions that pass through a segment stop.

---

## 5. 2D capacity in the insertion generator

**PR3552: scalar only.** All capacity checks in `InsertionGenerator` are `int` arithmetic:

```java
// drt-PR3552: InsertionGenerator.generateInsertions
if (drtRequest.getPassengerCount() > vEntry.vehicle.getCapacity()) { return Collections.EMPTY_LIST; }
int occupancy = vEntry.start.occupancy;
boolean allowed = occupancy + drtRequest.getPassengerCount() <= vEntry.vehicle.getCapacity();
...
if (currentStop.outgoingOccupancy + request.getPassengerCount() > vEntry.vehicle.getCapacity()) {...}
```

`Waypoint.Start.occupancy` / `Waypoint.Stop.outgoingOccupancy` are `public final int`. There is NO
injectable seam: `ExtensiveInsertionProvider.create(...)` does
`new InsertionGenerator(stopTimeCalculator, admissibleTimeEstimator)` internally — replacing
capacity semantics in PR3552 would mean re-binding the entire modal `DrtInsertionSearch`
(and duplicating the extensive-search machinery). **Not worth it.**

**2025.0: native multi-dimensional.** Same class, now `DvrpLoad`-based, including a
full-parcel-but-free-seats case working exactly as the spec wants:

```java
// drt-2025.0: InsertionGenerator.generateInsertions
DvrpLoad vehicleCapacity = vEntry.vehicle.getCapacity();
boolean compatibleWithOneCapacity = drtRequest.getLoad().fitsIn(vehicleCapacity);
DvrpLoad occupancy = vEntry.start.occupancy;
boolean allowed = drtRequest.getLoad().fitsIn(vehicleCapacity);
allowed = allowed && occupancy.add(drtRequest.getLoad()).fitsIn(vehicleCapacity);
...
if (!request.getLoad().fitsIn(capacity) || !currentStop.outgoingOccupancy.add(request.getLoad()).fitsIn(capacity)) {...}
```

`IntegersLoad.fitsIn` is per-dimension ≤, so a vehicle with occupancy (2 pax, 20 parcels) and
capacity (10, 20) accepts a 1-seat pax request and rejects a parcel request — the split 2D
capacity of spec §4.2 verbatim. (2025.0 even supports mid-route capacity reconfiguration via
`stop.getChangedCapacity()` / capacity-change tasks — noted for future, not needed for Step C.)

---

## 6. End-of-day unserved parcels (δ)

Verified event/termination behavior:
- While the retry window is open, an uninserted request lives in `DrtRequestInsertionRetryQueue`
  and its parcel-agent waits at the depot link inside `DefaultPassengerEngine.activePassengers`.
  **No event fires while pending.**
- If the retry window closes (`maxRequestAge` exceeded): `PassengerRequestRejectedEvent`
  (cause `no_insertion_found` or `offer_rejected`); `DefaultPassengerEngine.handleEvent` then
  sets the waiting agents to ABORT → core QSim emits `PersonStuckEvent` and counts them lost
  (QSim.java line 467 area, `agentCounter.incLost()`).
- **At QSim end** (`QSim.cleanupSim()` → every engine's `afterSim()`):
  `DefaultPassengerEngine.afterSim()` is **EMPTY** (verified) — parcels still waiting for pickup
  at sim end produce **NO rejection and NO stuck event** from the passenger engine.
  `ActivityEngineDefaultImpl.afterSim()` emits `PersonStuckEvent` only for agents still in
  activities with finite end time (i.e. parcel-agents that never even departed).
- The retry queue itself is never drained/rejected at shutdown (no afterSim hook in
  `DefaultDrtOptimizer` / `DefaultUnplannedRequestInserter`).

**Therefore δ must be computed from the request-event stream, not from stuck events:**
δ = parcel `DrtRequestSubmittedEvent`s without a matching `PassengerDroppedOffEvent`
(subclassify: `PassengerRequestRejectedEvent` = explicitly rejected; neither = pending-at-dayend).
Parcel requests are identifiable by passenger id (person-id prefix) and, in 2025.0, directly by
the load payload on `PassengerRequestSubmittedEvent` (`DvrpLoad load, String serializedDvrpLoad`).
All these events are in `org.matsim.contrib.dvrp.passenger` / `org.matsim.contrib.drt.passenger.events`.

---

## 7. Composition compatibility (SharedUseModule sketch)

Binding-site map established by this spike (the injector-scope gotcha generalizes):
**override a modal binding in the SAME scope where the contrib binds it.**

| Key | Bound by | Scope |
|---|---|---|
| `PassengerStopDurationProvider`, `StopTimeCalculator`, `DvrpLoadFromFleet`(2025.0), `DvrpLoadType`(2025.0) | `DrtModeModule` / `FleetModule` | controller (`AbstractDvrpModeModule`) |
| `InsertionCostCalculator`, `DrtOfferAcceptor`, `DrtRequestInsertionRetryQueue`, `UnplannedRequestInserter`, `VehicleEntry.EntryFactory`, `RequestFleetFilter`(2025.0) | `DrtModeOptimizerQSimModule` | QSim (`AbstractDvrpModeQSimModule`) |
| `PassengerRequestCreator`, `PassengerRequestValidator`, `AdvanceRequestProvider` | `DrtModeQSimModule` | QSim |
| `PassengerEngine`/`PassengerHandler` | `PassengerEngineQSimModule` | QSim |

```java
public final class SharedUseModule extends AbstractDvrpModeModule {   // mode = "drt"
    @Override public void install() {
        // controller scope: dwell + 2D fleet capacity (overrides DrtModeModule/FleetModule bindings
        // via Controler.addOverridingModule ordering — same mechanism PtAndDrtFareModule relies on)
        bindModal(PassengerStopDurationProvider.class).toProvider(...SharedUseStopDurationProvider...);
        bindModal(StopTimeCalculator.class).toProvider(...MinimumStopDurationAdapter(
                new ParallelStopTimeCalculator(provider), paxStopDuration)...);
        bindModal(DvrpLoadFromFleet.class).toProvider(...2D capacity: (seats, parcelSlots)...); // 2025.0

        // QSim scope: χ-gate + parcel-only retry (mirrors ReturnToDepotRebalancingModule's
        // proven installOverridingQSimModule pattern — controller-scope bindModal of a
        // QSim-bound key is what produced BindingAlreadySet)
        installOverridingQSimModule(new AbstractDvrpModeQSimModule(getMode()) {
            @Override protected void configureQSim() {
                bindModal(InsertionCostCalculator.class).toProvider(...χ decorator, §3...);
                bindModal(DrtRequestInsertionRetryQueue.class).toInstance(...parcel-only retry, §3...);
            }
        });
    }
}
// install order in the runner: DvrpModule, MultiModeDrtModule, (PtAndDrtFareModule),
// ReturnToDepotRebalancingModule, SharedUseModule  — SharedUseModule LAST.
```

No-collision argument, per existing module:
- `MultiModeDrtModule`/`DvrpModule`: we override their keys in matching scopes (Guice
  `Modules.override` semantics for controller modules added later via `addOverridingModule`;
  `installOverridingQSimModule` for QSim keys) — no second binding in any single injector.
- `PtAndDrtFareModule`: binds fare/event handlers only — disjoint keys.
- `ReturnToDepotRebalancingModule`: overrides only `RebalancingTargetCalculator` — disjoint.
- `CarrierModule`: binds freight-carrier keys (CarrierAgentTracker etc.), entirely disjoint from
  modal drt keys; the married DRT_BASELINE proves QSim cohabitation. **For DRT_SHAREDUSE it must
  nevertheless be ABSENT** — the spec says NO jsprit on the parcel side, and leaving LMD carriers
  in would double-serve the demand. Technically coexistence would not produce binding conflicts.
- Population side: parcel-persons go into a dedicated subpopulation with NO replanning strategies
  (fixed plans) so mode innovation never moves parcels off drt; `DefaultPassengerRequestValidator`
  rejects `fromLink == toLink` — segment stop must differ from the depot link (guard in the
  demand builder).

**PoC decision:** skipped. The decisive facts are version facts (DvrpLoad absent in PR3552 /
present+integrated in 2025.0), directly proven from the resolved sources; a PR3552-based PoC
cannot demonstrate the 2D capacity that defines the scenario, and a 2025.0 PoC requires the
version bump first (a compile-level change to the whole module, not a cheap test).

---

## Recommended architecture for 1c (Shared-Use, Step C)

1. **Version:** bump `matsim.version` 2025.0-PR3552 → **2025.0** (parent pom). Keep
   matsim-lausitz 2.0; re-run `DrtBaselineEndToEndTest` + `MarriedBaselineEndToEndTest` as the
   compatibility gate. (Smallest upgrade that unlocks DvrpLoad; same release line the PR build
   belongs to.)
2. **Load representation:** drt config `load` params: `dimensions = [passengers, parcels]`,
   `defaultRequestDimension = passengers` (pax legs need no attributes at all);
   `mapFleetCapacity = passengers`; override modal `DvrpLoadFromFleet` for (seats=10, parcelSlots=20).
3. **Request path:** HAGRID aggregates parcels → SEGMENT stops → for each segment ONE
   parcel-person: attributes `dvrp:load:parcels = n_segment`, dwell attribute from the segment
   delivery-time model; plan `act(depotLink, endTime=t_submit) → leg drt → act(segmentLink)`;
   dedicated no-innovation subpopulation. No custom engine, no prebooking in the first iteration
   (prebooking = later option for advance submission; requires the same agents anyway).
4. **Acceptance χ:** decorator on modal `InsertionCostCalculator` (QSim scope):
   parcels with `totalTimeLoss > χ_threshold` → `INFEASIBLE_SOLUTION_COST`.
5. **Pending semantics:** `dvrpRequestRetry` params `retryInterval ≈ 300 s`,
   `maxRequestAge = serviceEnd`; subclass `DrtRequestInsertionRetryQueue` so retry applies to
   parcels only (pax keep native immediate rejection).
6. **Dwell:** `SharedUseStopDurationProvider` (parcel: segment dwell / depot load time; pax:
   native) + `MinimumStopDurationAdapter(new ParallelStopTimeCalculator(provider), 60 s)`,
   both controller-scope overrides. Insertion search prices the stretched dwell automatically (§4).
7. **KPI δ:** event-based counter (submitted-without-dropoff, §6), NOT PersonStuckEvent.
8. **Modules:** `SharedUseModule` as sketched in §7, installed last; CarrierModule absent.

## Open risks / UNVERIFIED
- **matsim-lausitz 2.0 binary compatibility with matsim 2025.0** — must be proven by the E2E tests
  (its parent is 2025.0-PR3552; the drift list in §1 touches APIs it likely does not use, but this
  is inference, not evidence).
- Other HAGRID deps at `${matsim.version}` (freight contrib for LMD_BASELINE, pt2matsim excluded-core)
  need the same recompile check — the LMD jsprit path was verified only against PR3552 signatures
  (2026-06-25 note).
- 2025.0 `DrtOfferAcceptor`/retry/`DefaultUnplannedRequestInserter` logic re-verified in 2025.0
  sources; the rest of §2/§4 was read primarily in PR3552 sources and spot-checked in 2025.0
  (`PassengerStopDurationProvider` identical; `DrtModeModule` stop bindings not re-diffed line-by-line —
  low risk, flag at implementation).
- Prebooking × DvrpLoad interplay in 2025.0 untested (irrelevant while prebooking stays off).
- Sliding time windows on retry (`latestStartTime += Δ`) mean a pending parcel's window walks
  forward all day — acceptable for parcels (they have no hard promise), but the χ-gate should use
  wait-time-independent cost only (totalTimeLoss is; the soft-violation penalties in
  `DiscourageSoftConstraintViolations` add wait/travel-time violation terms — consider
  `RejectSoftConstraintViolations` semantics for parcels or gate on raw `totalTimeLoss`).
- Exact drt customer-stats output columns for rejections not re-read this spike — δ counting is
  specified via raw events instead.
