# 1c — DRT_SHAREDUSE (Cargo Hitching, Step C) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The `DRT_SHAREDUSE` scenario runs pax + parcels in ONE online-dispatched DRT fleet with split 2D capacity (seats, parcel slots): parcels enter as segment-stop requests (pickup at nearest depot → delivery at the segment), accepted only if the marginal insertion cost stays below a χ threshold, retried while pending, and counted as undelivered (δ) at day end — NO jsprit on the parcel side.

**Architecture:** Everything rides on native MATSim mechanisms proven by the 2026-07-06 spike (`docs/superpowers/notes/2026-07-06-shareduse-dvrp-insertion-spike.md` — read it first, it is the evidence base): parcels are **dummy parcel-persons** (plan `act(depot) → drt leg → act(segment)`, load attribute `dvrp:load:parcels`), 2D capacity comes from MATSim 2025.0's native `DvrpLoad` (hence a version bump as gate task), the χ-gate is a decorator on the modal `InsertionCostCalculator` (QSim scope), "pending" is the native retry queue restricted to parcels, and the stretched segment dwell is a `PassengerStopDurationProvider` + `StopTimeCalculator` override pair (controller scope). One new Guice module (`SharedUseModule`) installs all of it LAST, mirroring the proven `ReturnToDepotRebalancingModule` scope pattern.

**Tech Stack:** Java 17, MATSim **2025.0** (bumped from 2025.0-PR3552), drt/dvrp contribs, matsim-lausitz 2.0 (unchanged binary dep), JUnit 5. No new Maven deps.

## Decisions (user-decided or vetoable)

- **D1 (user 2026-07-06) — Phase 1 without Packstationen:** all parcels door-to-door. `DeliveryChannelResolver` ships anyway (B2B→DOOR always; B2C→locker-first *when a locker list exists*, door fallback) with an EMPTY locker list, so plugging in `packstations.csv` later is data, not code. Channel shares are emitted as KPIs from day one (door = 100 % until then).
- **D2 (user 2026-07-06) — autonomy switch = separate follow-up plan** (it spans both integrated scenarios). 1c is conventional operation only.
- **D3 — MATSim bump `2025.0-PR3552` → `2025.0` as Task 1 with the full test suite + all four e2e tests as compatibility gate** (spike: smallest upgrade that unlocks `DvrpLoad`; matsim-lausitz 2.0 binary-compat is the flagged open risk — the gate proves or falsifies it before anything else is built).
- **D4 — Vehicle = 10 seats + 20 parcel slots** (spec §6.1). Seats go into the fleet XML scalar (`mapFleetCapacity=passengers`); parcel slots come from the `DvrpLoadFromFleet` override. Fleet size stays the existing `fleetSize` runner key.
- **D5 — χ threshold as runner key `chiThreshold` (seconds of marginal vehicle time-loss), default 600** — same parse/ctor pattern as `fleetSize`/`freight`. `InsertionCostCalculator` cost IS `totalTimeLoss` in seconds (spike §3), so no unit conversion; the EUR interpretation (25 €/h → 600 s ≈ 4.17 €) is a reporting concern.
- **D6 — Parcel submission times uniformly jittered in [08:00, 10:00]** (seeded `Random`), inside the LMD time window 08–20 h; avoids ~1,000 simultaneous submissions at one tick. Calibration lever later.
- **D7 — `freight=true` is IGNORED for DRT_SHAREDUSE** (log + proceed): no jsprit preprocessing, no `FreightRunComposer`/CarrierModule — the spec's "no jsprit on the parcel side" and no double-serving of demand.
- **D8 — `IntegratedScenarioConfig` stays unwired** (it is production-dead today, grounding §6). The autonomy follow-up plan is its natural integration point; 1c threads only `chiThreshold` through `HAGRIDSimulationConfig` to avoid double config plumbing.
- **D9 — KPI contract file `shareduse_channel_stats.csv`** (`metric;value`) written by an event handler at shutdown, consumed by 1e's `extract_shareduse.py` (Task 8; requires the 1e plan to have landed — execute 1e first, as agreed).
- **D10 — KNOWN, ACCEPTED distortions of stock outputs in SHAREDUSE runs:** MATSim's `drt_customer_stats`/occupancy/fare aggregates mix parcel-requests into pax numbers (parcels physically board, get fared by `PtAndDrtFareModule`, appear in legs). Pax-only truth is recoverable from `output_drt_legs_drt.csv` via the `personId` prefix — Task 8 emits corrected `*_pax_only` KPI rows, and the comparison uses those. Legacy dashboards are NOT adapted (they remain baseline tools).

## Global Constraints

- **Read the spike note first** (`docs/superpowers/notes/2026-07-06-shareduse-dvrp-insertion-spike.md`); its §7 binding-scope table is binding: controller-scope keys via `Controler.addOverridingModule`-installed `AbstractDvrpModeModule`, QSim keys ONLY via `installOverridingQSimModule` — controller-scope `bindModal` of a QSim key produced `BindingAlreadySet` before.
- Parcel-person ids start with **`parcel_`** — this prefix IS the request classifier everywhere (χ-gate, retry queue, KPI handler, extractor). Constant, defined once.
- Parcel subpopulation name: **`parcel`**, selector-only replanning (no ReRoute/SubtourModeChoice/TimeAllocationMutator — those would move parcels off drt).
- Dwell formula parity with LMD: `min(2 min × parcels, 15 min)` per segment (constants `DURATION_PER_PARCEL_MIN = 2`, `MAX_DURATION_PER_STOP_MIN = 15`, mirrors `LmdCarrierBuilder.java:98-100`).
- All existing scenarios (`DRT_BASELINE` married/pax-only, `LMD_BASELINE`, Hannover legacy) must stay green — the suite (~274) is the regression net for every task.
- Branch `hendrik`; never merge to master; `git add` explicit files; no `.gitignore` edits (`*.shp` test resources need `git add -f`).
- A handful of 2025.0 signatures were spot-checked but not line-diffed by the spike (flagged there) — where a step says **VERIFY-SOURCE**, check the signature in the unzipped sources (`~/.m2/repository/org/matsim/contrib/{drt,dvrp}/2025.0/*-sources.jar`; the spike left unzipped copies in its scratchpad) before compiling against it, and adapt mechanically if it drifted.

## File Structure

```
pom.xml (parcel-demand-2-matsim-pipeline)                       ← MODIFY (Task 1: matsim.version)
src/main/java/hagrid/integrated/shareduse/
├── SharedUse.java                    ← NEW constants (prefix, subpop, seats, slots, dwell)  (Task 2)
├── DeliveryChannelResolver.java      ← NEW B2B→door; B2C→locker-first→door                  (Task 2)
├── ParcelAgentGenerator.java         ← NEW deliveries → parcel-persons into the population  (Task 2)
├── SharedUseStopDurationProvider.java← NEW per-request dwell                                 (Task 4)
├── ChiGateInsertionCostCalculator.java ← NEW χ decorator                                    (Task 5)
├── ParcelOnlyRetryQueue.java         ← NEW parcel-only pending semantics                     (Task 5)
├── SharedUseModule.java              ← NEW composition (controller + QSim overrides)         (Task 4/5)
└── SharedUseKpiHandler.java          ← NEW δ/channel event counter + CSV writer              (Task 7)
src/main/java/hagrid/integrated/drt/LausitzDrtPreprocessor.java ← MODIFY (Task 3: capacity, parcel injection)
src/main/java/hagrid/integrated/drt/DrtConfigComposer.java      ← MODIFY (Task 4: load params, retry params, parcel strategy, activity params)
src/main/java/hagrid/simulation/SimulationRunnerUtils.java      ← MODIFY (Task 6: dispatch)
src/main/java/hagrid/simulation/HAGRIDSimulationConfig.java     ← MODIFY (Task 6: chiThreshold)
src/test/java/hagrid/integrated/shareduse/…                     ← NEW unit tests per class + SharedUseEndToEndTest (Task 7)
parcel-demand-2-matsim-pipeline/analysis/kpi/extract_shareduse.py ← NEW (Task 8, after 1e)
```

## Interface contract with 1e (execute 1e first)

- `RunMetadataWriter` gains nothing here (operation_mode stays "conventional"); the SHAREDUSE run dir gets `shareduse_channel_stats.csv` + the standard MATSim outputs.
- Task 8 registers `extract_shareduse.py` in `build_kpis.EXTRACTORS` (predicate: file exists), emitting `channel/*` rows (door/locker shares, undelivered_rate δ) and corrected `passenger/*_pax_only` rows.

---

### Task 1: MATSim version bump 2025.0-PR3552 → 2025.0 (compatibility gate)

**Files:**
- Modify: `parcel-demand-2-matsim-pipeline/pom.xml` (property `matsim.version`)

**Interfaces:**
- Consumes: nothing new.
- Produces: a green build against MATSim 2025.0 — the precondition for `DvrpLoad`. Everything after this task assumes 2025.0 APIs.

- [ ] **Step 1: Bump the version property**

In `pom.xml`, change the `matsim.version` property value `2025.0-PR3552` → `2025.0`. Touch nothing else (matsim-lausitz stays `com.github.matsim-scenarios:matsim-lausitz:2.0`).

- [ ] **Step 2: Compile**

Run (from `parcel-demand-2-matsim-pipeline/`): `mvn -q clean compile`
Expected drift surface (spike §1) if anything fails to compile: `DvrpVehicle.getCapacity()` now returns `DvrpLoad`; `PassengerRequestCreator.createRequest` takes `List<Route>`; `DrtRequestCreator` ctor gains `DvrpLoadType`. HAGRID binds none of these — `DrtFleetGenerator` writes `DvrpVehicleSpecification`s (scalar `capacity(int)` is expected to survive, since 2025.0's `FleetReader` still reads the scalar attribute — VERIFY-SOURCE if it breaks). Fix mechanically, change no behavior.

- [ ] **Step 3: Full suite**

Run: `mvn -q test`
Expected: all green (~274). Failures in drt/dvrp-touching tests = the real compatibility signal; investigate against the spike's drift list before changing test expectations (never loosen an assertion to make the bump pass).

- [ ] **Step 4: The four e2e gates explicitly**

Run: `mvn -q test -Dtest=DrtBaselineEndToEndTest,MarriedBaselineEndToEndTest,LmdBaselineEndToEndTest,DrtRailIntermodalEndToEndTest`
Expected: PASS — this proves matsim-lausitz 2.0 (compiled against PR3552) links against 2025.0 (spike's headline UNVERIFIED risk). If `PtAndDrtFareModule`/`LausitzScenario` linkage breaks here, STOP and report — that decision (fork/rebuild lausitz vs. pin) is the user's.

- [ ] **Step 5: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/pom.xml
git commit -m "build: bump matsim.version to 2025.0 (unlocks DvrpLoad for Shared-Use) (1c Task 1)"
```

---

### Task 2: `SharedUse` constants, `DeliveryChannelResolver`, `ParcelAgentGenerator`

**Files:**
- Create: `src/main/java/hagrid/integrated/shareduse/SharedUse.java`
- Create: `src/main/java/hagrid/integrated/shareduse/DeliveryChannelResolver.java`
- Create: `src/main/java/hagrid/integrated/shareduse/ParcelAgentGenerator.java`
- Test: `src/test/java/hagrid/integrated/shareduse/DeliveryChannelResolverTest.java`, `ParcelAgentGeneratorTest.java`

**Interfaces:**
- Consumes: `hagrid.utils.demand.Delivery` (fields `getCoordinate(): Coord`, `getAmount(): int`, `getParcelType(): ParcelType` (B2B/B2C), `getProvider(): String`); `LmdDemandReader.read(String)/group(Collection)`; `DepotNetwork` (`nearestDepot(Coord)`, record `Depot(String id, Coord coord)`); `DrtDepotReader.readCoords(Path)`; `NetworkUtils.getNearestLinkExactly(Network, Coord)`; `GeoUtils.getBoundaryGeometry` (same clip approach as `LausitzFreightPreprocessor`).
- Produces:
  - `SharedUse` constants: `PARCEL_PERSON_PREFIX="parcel_"`, `PARCEL_SUBPOPULATION="parcel"`, `SEATS=10`, `PARCEL_SLOTS=20`, `LOAD_ATTRIBUTE="dvrp:load:parcels"`, `DWELL_ATTRIBUTE="parcelDwellSeconds"`, `CHANNEL_ATTRIBUTE="parcelChannel"`, `ACT_DEPOT="parcelDepot"`, `ACT_DELIVERY="parcelDelivery"`, `PICKUP_DURATION_S=120.0`, `DURATION_PER_PARCEL_MIN=2`, `MAX_DURATION_PER_STOP_MIN=15`, `SUBMIT_FROM_S=8*3600`, `SUBMIT_TO_S=10*3600`.
  - `DeliveryChannelResolver.resolve(Delivery d) -> Channel` (`enum Channel { DOOR, LOCKER }`), constructed with `List<Coord> lockerLocations` (empty in Phase 1) — B2B always DOOR; B2C→LOCKER only if a locker within `maxLockerDistance` exists, else DOOR.
  - `ParcelAgentGenerator.generate(Map<String,List<Delivery>> byProvider, Geometry serviceArea, Network drtNetwork, List<Coord> depotCoords, Population targetPopulation, long seed) -> Result` with `record Result(int personsAdded, int parcels, int skippedSameLink, int clippedOutside)` — adds one parcel-person per in-area delivery.

- [ ] **Step 1: Write the failing resolver test**

```java
package hagrid.integrated.shareduse;

import hagrid.utils.demand.Delivery;
import hagrid.utils.demand.ParcelType;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DeliveryChannelResolverTest {

    private static Delivery delivery(ParcelType type) {
        return Delivery.builder().id("d1").coordinate(new Coord(0, 0))
                .provider("dhl").amount(3).parcelType(type).build();
    }

    @Test
    void b2bIsAlwaysDoor() {
        var resolver = new DeliveryChannelResolver(List.of(new Coord(10, 10)), 500.0);
        assertEquals(DeliveryChannelResolver.Channel.DOOR, resolver.resolve(delivery(ParcelType.B2B)));
    }

    @Test
    void b2cWithoutLockersFallsBackToDoor() {
        var resolver = new DeliveryChannelResolver(List.of(), 500.0);   // Phase 1: empty
        assertEquals(DeliveryChannelResolver.Channel.DOOR, resolver.resolve(delivery(ParcelType.B2C)));
    }

    @Test
    void b2cWithLockerInRangeIsLocker() {
        var resolver = new DeliveryChannelResolver(List.of(new Coord(100, 0)), 500.0);
        assertEquals(DeliveryChannelResolver.Channel.LOCKER, resolver.resolve(delivery(ParcelType.B2C)));
    }

    @Test
    void b2cWithLockerOutOfRangeIsDoor() {
        var resolver = new DeliveryChannelResolver(List.of(new Coord(10_000, 0)), 500.0);
        assertEquals(DeliveryChannelResolver.Channel.DOOR, resolver.resolve(delivery(ParcelType.B2C)));
    }
}
```

(If `Delivery.builder()` requires more fields, set the minimum that compiles — check the Lombok `@Builder` on `hagrid/utils/demand/Delivery.java`.)

- [ ] **Step 2: Run to verify failure**

Run: `mvn -q test -Dtest=DeliveryChannelResolverTest`
Expected: COMPILATION FAILURE (classes missing).

- [ ] **Step 3: Implement `SharedUse` + `DeliveryChannelResolver`**

```java
package hagrid.integrated.shareduse;

/** Shared-Use (cargo hitching) constants — single source of truth for the
 *  parcel classifier prefix, subpopulation, capacities and dwell model. */
public final class SharedUse {
    public static final String PARCEL_PERSON_PREFIX = "parcel_";
    public static final String PARCEL_SUBPOPULATION = "parcel";
    public static final int SEATS = 10;                 // spec §6.1
    public static final int PARCEL_SLOTS = 20;          // spec §6.1
    public static final String LOAD_ATTRIBUTE = "dvrp:load:parcels";  // DefaultDvrpLoadFromTrip prefix + dimension
    public static final String DWELL_ATTRIBUTE = "parcelDwellSeconds";
    public static final String CHANNEL_ATTRIBUTE = "parcelChannel";
    public static final String ACT_DEPOT = "parcelDepot";
    public static final String ACT_DELIVERY = "parcelDelivery";
    public static final double PICKUP_DURATION_S = 120.0;
    public static final int DURATION_PER_PARCEL_MIN = 2;    // parity: LmdCarrierBuilder
    public static final int MAX_DURATION_PER_STOP_MIN = 15; // parity: LmdCarrierBuilder
    public static final double SUBMIT_FROM_S = 8 * 3600.0;  // D6
    public static final double SUBMIT_TO_S = 10 * 3600.0;

    private SharedUse() {
    }

    public static double segmentDwellSeconds(int parcels) {
        return Math.min(DURATION_PER_PARCEL_MIN * 60.0 * parcels, MAX_DURATION_PER_STOP_MIN * 60.0);
    }

    public static boolean isParcelPerson(String personId) {
        return personId.startsWith(PARCEL_PERSON_PREFIX);
    }
}
```

```java
package hagrid.integrated.shareduse;

import hagrid.utils.demand.Delivery;
import hagrid.utils.demand.ParcelType;
import org.matsim.api.core.v01.Coord;
import org.matsim.core.utils.geometry.CoordUtils;

import java.util.List;

/**
 * Delivery-channel logic (spec §4.2): B2B door-to-door always; B2C Packstation/
 * Filiale-first with door fallback. Phase 1 runs with an EMPTY locker list
 * (user decision 2026-07-06) — the locker branch activates once a locations
 * file is staged, without code changes here.
 */
public final class DeliveryChannelResolver {

    public enum Channel { DOOR, LOCKER }

    private final List<Coord> lockerLocations;
    private final double maxLockerDistanceMeters;

    public DeliveryChannelResolver(List<Coord> lockerLocations, double maxLockerDistanceMeters) {
        this.lockerLocations = List.copyOf(lockerLocations);
        this.maxLockerDistanceMeters = maxLockerDistanceMeters;
    }

    public Channel resolve(Delivery delivery) {
        if (delivery.getParcelType() == ParcelType.B2B) {
            return Channel.DOOR;
        }
        return lockerLocations.stream()
                .anyMatch(l -> CoordUtils.calcEuclideanDistance(l, delivery.getCoordinate())
                        <= maxLockerDistanceMeters)
                ? Channel.LOCKER : Channel.DOOR;
    }
}
```

- [ ] **Step 4: Run resolver tests**

Run: `mvn -q test -Dtest=DeliveryChannelResolverTest`
Expected: 4 tests PASS.

- [ ] **Step 5: Write the failing generator test**

Reuse the e2e grid fixture (`DrtE2eFixtures.buildGrid()` — see `DrtBaselineEndToEndTest` for usage):

```java
package hagrid.integrated.shareduse;

import hagrid.utils.demand.Delivery;
import hagrid.utils.demand.ParcelType;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.*;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.config.ConfigUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ParcelAgentGeneratorTest {

    private static Geometry square(double size) {
        var gf = new GeometryFactory();
        return gf.createPolygon(new org.locationtech.jts.geom.Coordinate[]{
                new org.locationtech.jts.geom.Coordinate(0, 0),
                new org.locationtech.jts.geom.Coordinate(size, 0),
                new org.locationtech.jts.geom.Coordinate(size, size),
                new org.locationtech.jts.geom.Coordinate(0, size),
                new org.locationtech.jts.geom.Coordinate(0, 0)});
    }

    @Test
    void generatesOnePersonPerInAreaDeliveryWithLoadDwellAndPlan() {
        Network net = hagrid.integrated.drt.DrtE2eFixtures.buildGrid();   // adapt import to the fixture's package
        Population pop = ScenarioUtils.createScenario(ConfigUtils.createConfig()).getPopulation();
        Map<String, List<Delivery>> demand = Map.of("dhl", List.of(
                Delivery.builder().id("s1").coordinate(new Coord(800, 800))
                        .provider("dhl").amount(3).parcelType(ParcelType.B2C).build(),
                Delivery.builder().id("s2").coordinate(new Coord(9_999_999, 0))   // outside
                        .provider("dhl").amount(2).parcelType(ParcelType.B2B).build()));

        var result = ParcelAgentGenerator.generate(demand, square(2000), net,
                List.of(new Coord(500, 500)), pop, 4711L);

        assertEquals(1, result.personsAdded());
        assertEquals(3, result.parcels());
        assertEquals(1, result.clippedOutside());

        Person p = pop.getPersons().values().iterator().next();
        assertTrue(p.getId().toString().startsWith(SharedUse.PARCEL_PERSON_PREFIX));
        assertEquals(SharedUse.PARCEL_SUBPOPULATION, PopulationUtils.getSubpopulation(p));
        assertEquals(3, (int) (Integer) p.getAttributes().getAttribute(SharedUse.LOAD_ATTRIBUTE));
        assertEquals(SharedUse.segmentDwellSeconds(3),
                (double) (Double) p.getAttributes().getAttribute(SharedUse.DWELL_ATTRIBUTE), 1e-9);
        assertEquals("DOOR", p.getAttributes().getAttribute(SharedUse.CHANNEL_ATTRIBUTE));

        Plan plan = p.getSelectedPlan();
        assertEquals(3, plan.getPlanElements().size());
        Activity depot = (Activity) plan.getPlanElements().get(0);
        Leg leg = (Leg) plan.getPlanElements().get(1);
        Activity delivery = (Activity) plan.getPlanElements().get(2);
        assertEquals(SharedUse.ACT_DEPOT, depot.getType());
        assertEquals("drt", leg.getMode());
        assertEquals(SharedUse.ACT_DELIVERY, delivery.getType());
        assertTrue(depot.getEndTime().seconds() >= SharedUse.SUBMIT_FROM_S
                && depot.getEndTime().seconds() <= SharedUse.SUBMIT_TO_S);
        assertNotEquals(depot.getLinkId(), delivery.getLinkId());   // validator guard
    }

    @Test
    void deterministicForFixedSeed() {
        Network net = hagrid.integrated.drt.DrtE2eFixtures.buildGrid();
        Map<String, List<Delivery>> demand = Map.of("dhl", List.of(
                Delivery.builder().id("s1").coordinate(new Coord(800, 800))
                        .provider("dhl").amount(1).parcelType(ParcelType.B2C).build()));
        Population p1 = ScenarioUtils.createScenario(ConfigUtils.createConfig()).getPopulation();
        Population p2 = ScenarioUtils.createScenario(ConfigUtils.createConfig()).getPopulation();
        ParcelAgentGenerator.generate(demand, square(2000), net, List.of(new Coord(500, 500)), p1, 4711L);
        ParcelAgentGenerator.generate(demand, square(2000), net, List.of(new Coord(500, 500)), p2, 4711L);
        Activity a1 = (Activity) p1.getPersons().values().iterator().next().getSelectedPlan().getPlanElements().get(0);
        Activity a2 = (Activity) p2.getPersons().values().iterator().next().getSelectedPlan().getPlanElements().get(0);
        assertEquals(a1.getEndTime().seconds(), a2.getEndTime().seconds(), 1e-9);
    }
}
```

(`DrtE2eFixtures` package/visibility: it lives with the drt e2e tests — if it is package-private, add a tiny local grid builder in this test instead; the assertions are what matter.)

- [ ] **Step 6: Run to verify failure**

Run: `mvn -q test -Dtest=ParcelAgentGeneratorTest`
Expected: COMPILATION FAILURE (`ParcelAgentGenerator` missing).

- [ ] **Step 7: Implement `ParcelAgentGenerator`**

```java
package hagrid.integrated.shareduse;

import hagrid.integrated.DepotNetwork;
import hagrid.utils.demand.Delivery;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.*;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.PopulationUtils;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

/**
 * Turns the segment-aggregated PANDA deliveries into dummy parcel-persons:
 * plan = act(parcelDepot @ nearest depot link, endTime = jittered submit time)
 *        -> leg(drt) -> act(parcelDelivery @ segment link).
 * The drt departure triggers the native request submission (spike §2, path b).
 * Provider identity is dissolved (Einheitsunternehmen) but kept as attribute.
 */
public final class ParcelAgentGenerator {

    private static final Logger LOG = LogManager.getLogger(ParcelAgentGenerator.class);
    private static final GeometryFactory GF = new GeometryFactory();

    public record Result(int personsAdded, int parcels, int skippedSameLink, int clippedOutside) {
    }

    private ParcelAgentGenerator() {
    }

    public static Result generate(Map<String, List<Delivery>> byProvider, Geometry serviceArea,
                                  Network drtNetwork, List<Coord> depotCoords,
                                  Population population, long seed) {
        DepotNetwork depots = new DepotNetwork(depotCoords.stream()
                .map(c -> new DepotNetwork.Depot("depot_" + c.getX() + "_" + c.getY(), c))
                .collect(Collectors.toList()));
        DeliveryChannelResolver resolver = new DeliveryChannelResolver(List.of(), 500.0); // Phase 1: no lockers
        Random rnd = new Random(seed);
        PopulationFactory pf = population.getFactory();

        int persons = 0, parcels = 0, skipped = 0, outside = 0, index = 0;
        for (Map.Entry<String, List<Delivery>> e : byProvider.entrySet()) {
            for (Delivery d : e.getValue()) {
                index++;                                     // DBF has no id column -> index is the identity
                if (!serviceArea.contains(GF.createPoint(new org.locationtech.jts.geom.Coordinate(
                        d.getCoordinate().getX(), d.getCoordinate().getY())))) {
                    outside++;
                    continue;
                }
                Coord depotCoord = depots.nearestDepot(d.getCoordinate()).coord();
                Link depotLink = NetworkUtils.getNearestLinkExactly(drtNetwork, depotCoord);
                Link segmentLink = NetworkUtils.getNearestLinkExactly(drtNetwork, d.getCoordinate());
                if (depotLink.getId().equals(segmentLink.getId())) {
                    skipped++;                               // DefaultPassengerRequestValidator rejects from==to
                    LOG.warn("parcel segment {} snaps to its depot link {} - skipped", index, depotLink.getId());
                    continue;
                }

                Person p = pf.createPerson(Id.createPersonId(SharedUse.PARCEL_PERSON_PREFIX
                        + d.getProvider() + "_" + index + "_" + d.getParcelType()));
                PopulationUtils.putSubpopulation(p, SharedUse.PARCEL_SUBPOPULATION);
                p.getAttributes().putAttribute(SharedUse.LOAD_ATTRIBUTE, d.getAmount());
                p.getAttributes().putAttribute(SharedUse.DWELL_ATTRIBUTE,
                        SharedUse.segmentDwellSeconds(d.getAmount()));
                p.getAttributes().putAttribute(SharedUse.CHANNEL_ATTRIBUTE, resolver.resolve(d).name());
                p.getAttributes().putAttribute("provider", d.getProvider());

                Plan plan = pf.createPlan();
                Activity depot = pf.createActivityFromLinkId(SharedUse.ACT_DEPOT, depotLink.getId());
                depot.setEndTime(SharedUse.SUBMIT_FROM_S
                        + rnd.nextDouble() * (SharedUse.SUBMIT_TO_S - SharedUse.SUBMIT_FROM_S));
                plan.addActivity(depot);
                plan.addLeg(pf.createLeg("drt"));
                plan.addActivity(pf.createActivityFromLinkId(SharedUse.ACT_DELIVERY, segmentLink.getId()));
                p.addPlan(plan);
                population.addPerson(p);
                persons++;
                parcels += d.getAmount();
            }
        }
        LOG.info("ParcelAgentGenerator: {} parcel-persons ({} parcels), {} outside area, {} same-link skipped",
                persons, parcels, outside, skipped);
        return new Result(persons, parcels, skipped, outside);
    }
}
```

- [ ] **Step 8: Run tests, then full suite; commit**

Run: `mvn -q test -Dtest=ParcelAgentGeneratorTest,DeliveryChannelResolverTest` then `mvn -q test`
Expected: PASS / all green.

```bash
git add parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/shareduse/SharedUse.java \
        parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/shareduse/DeliveryChannelResolver.java \
        parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/shareduse/ParcelAgentGenerator.java \
        parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/shareduse/DeliveryChannelResolverTest.java \
        parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/shareduse/ParcelAgentGeneratorTest.java
git commit -m "feat(shareduse): channel resolver + parcel-agent generator (1c Task 2)"
```

---

### Task 3: Preprocessing wiring — capacity per concept + parcel injection

**Files:**
- Modify: `src/main/java/hagrid/integrated/drt/LausitzDrtPreprocessor.java`
- Test: extend `src/test/java/hagrid/integrated/drt/…` (new `SharedUsePreprocessorTest` or extend the existing preprocessor test)

**Interfaces:**
- Consumes: `HAGRIDSimulationConfig` (`getConcept()`, `getLmdDemandShapefile()`, `getLmdDepotCsv()`, `getDrtServiceAreaShapefile()`, `getDrtNetworkClipped()`, `getPassengerPlansClipped()`); `HagridConfig.Scenario.valueOf(...)`; `ParcelAgentGenerator`.
- Produces: for `DRT_SHAREDUSE`, `drt_population.xml.gz` additionally contains the `parcel` subpopulation, and the fleet is written with `capacity = SharedUse.SEATS` (10) instead of 8. All other concepts byte-identical behavior.

- [ ] **Step 1: Write the failing test**

Model it on `DrtBaselineEndToEndTest`'s fixture usage (grid + square shapefile + demand shapefile via `LmdTestShapefiles.writeDemand` from the married e2e): run `LausitzDrtPreprocessor.run(cfg)` with `concept=drt_shareduse` and assert:

```java
Population prepared = PopulationUtils.readPopulation(cfg.getPassengerPlansClipped());
long parcelPersons = prepared.getPersons().values().stream()
        .filter(p -> p.getId().toString().startsWith(SharedUse.PARCEL_PERSON_PREFIX)).count();
long paxPersons = prepared.getPersons().size() - parcelPersons;
assertTrue(parcelPersons >= 1, "parcel subpopulation missing");
assertTrue(paxPersons >= 1, "pax must survive the person-filter");
// fleet capacity = 10 for shareduse
String fleetXml = readGzToString(cfg.getDrtFleetFile());
assertTrue(fleetXml.contains("capacity=\"10\""));
```

And a second assertion set with `concept=drt_baseline` on the same fixtures: NO `parcel_` persons, `capacity="8"` (regression).

- [ ] **Step 2: Run to verify failure**

Run: `mvn -q test -Dtest=SharedUsePreprocessorTest`
Expected: FAIL (no parcel persons, capacity 8).

- [ ] **Step 3: Implement in `LausitzDrtPreprocessor`**

In `run(HAGRIDSimulationConfig cfg)` (`LausitzDrtPreprocessor.java:148`, hardcoded params around `:159-175`):

```java
HagridConfig.Scenario scenario = HagridConfig.Scenario.valueOf(cfg.getConcept().toUpperCase());
boolean sharedUse = scenario == HagridConfig.Scenario.DRT_SHAREDUSE;
int capacity = sharedUse ? SharedUse.SEATS : 8;
```

and pass `capacity` where the literal `8` was. The **person-filter gotcha** (`:82-87` removes every subpopulation except `"person"`): inject parcels AFTER the filter and BEFORE `writePopulation(clipped, plansOut)` — since `run(cfg)` currently delegates to the 15-arg overload, the least invasive seam is a post-step in `run(cfg)` that re-reads and re-writes the plans file:

```java
if (sharedUse) {
    Population pop = PopulationUtils.readPopulation(cfg.getPassengerPlansClipped());
    Network drtNet = NetworkUtils.readNetwork(cfg.getDrtNetworkClipped());
    Geometry area = GeoUtils.getBoundaryGeometry(cfg.getDrtServiceAreaShapefile());
    Map<String, List<Delivery>> demand =
            LmdDemandReader.group(LmdDemandReader.read(cfg.getLmdDemandShapefile()));
    List<Coord> depots = DrtDepotReader.readCoords(Path.of(cfg.getLmdDepotCsv()));
    ParcelAgentGenerator.Result r = ParcelAgentGenerator.generate(
            demand, area, drtNet, depots, pop, 4711L);
    LOG.info("SHAREDUSE: injected {} parcel-persons ({} parcels) into {}",
            r.personsAdded(), r.parcels(), cfg.getPassengerPlansClipped());
    PopulationUtils.writePopulation(pop, cfg.getPassengerPlansClipped());
}
```

(Match the actual local API: `GeoUtils.getBoundaryGeometry` signature as used at `LausitzDrtPreprocessor.java:73`; the drt-subnetwork built at `:90-96` may be reusable — prefer snapping on the drt-filtered subnetwork, same as the fleet, so parcel links are guaranteed drt-taggable.)

- [ ] **Step 4: Run tests + full suite; commit**

Run: `mvn -q test -Dtest=SharedUsePreprocessorTest` then `mvn -q test`
Expected: PASS / all green (baseline regression assertions included).

```bash
git add parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/drt/LausitzDrtPreprocessor.java \
        parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/drt/SharedUsePreprocessorTest.java
git commit -m "feat(shareduse): preprocessor injects parcel subpopulation + seats=10 fleet (1c Task 3)"
```

---

### Task 4: Dwell + 2D load — `SharedUseStopDurationProvider`, config composition, `SharedUseModule` (controller half)

**Files:**
- Create: `src/main/java/hagrid/integrated/shareduse/SharedUseStopDurationProvider.java`
- Create: `src/main/java/hagrid/integrated/shareduse/SharedUseModule.java` (controller-scope half; QSim half in Task 5)
- Modify: `src/main/java/hagrid/integrated/drt/DrtConfigComposer.java` (SHAREDUSE config: load params, retry params, parcel strategy, activity params)
- Test: `SharedUseStopDurationProviderTest.java`, `SharedUseConfigTest.java`

**Interfaces:**
- Consumes (VERIFY-SOURCE each in the 2025.0 sources before compiling): `org.matsim.contrib.drt.stops.PassengerStopDurationProvider` (`calcPickupDuration(DvrpVehicle, DrtRequest)`, `calcDropoffDuration(...)`), `StopTimeCalculator`, `ParallelStopTimeCalculator`, `MinimumStopDurationAdapter`, `StaticPassengerStopDurationProvider`; `DrtConfigGroup.addOrGetLoadParams()` (`DvrpLoadParams`: `dimensions`, `mapFleetCapacity`, `defaultRequestDimension`); `DrtRequestInsertionRetryParams` (param set `dvrpRequestRetry`: `retryInterval`, `maxRequestAge`); `org.matsim.contrib.dvrp.load.DvrpLoadFromFleet` + `DvrpLoadType.fromMap`; `DrtRequest.getPassengerIds()`.
- Produces: `SharedUseStopDurationProvider(double paxStopDuration)` — parcels: pickup = `PICKUP_DURATION_S`, dropoff = person's `DWELL_ATTRIBUTE`; pax: native `paxStopDuration`/0. `SharedUseModule(double chiThreshold)` (mode `"drt"`). `DrtConfigComposer.composeSharedUse(Config config)` static helper that mutates the composed config for SHAREDUSE.
- The dwell provider needs the `Person` — `DrtRequest` exposes passenger ids only; resolve attributes via the injected `Population` (modal module has injector access). Provider is constructed with a `Map<Id<Person>, Double> dwellById` snapshot taken from the population at module install (parcel-persons are static — no plan innovation, so a snapshot is safe).

- [ ] **Step 1: Write the failing provider test**

```java
package hagrid.integrated.shareduse;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SharedUseStopDurationProviderTest {

    private final SharedUseStopDurationProvider provider = new SharedUseStopDurationProvider(
            60.0, Map.of(Id.createPersonId("parcel_dhl_1_B2C"), 360.0));

    @Test
    void parcelPickupIsDepotLoadTime() {
        assertEquals(SharedUse.PICKUP_DURATION_S,
                provider.pickupDurationFor(Id.createPersonId("parcel_dhl_1_B2C")), 1e-9);
    }

    @Test
    void parcelDropoffIsSegmentDwell() {
        assertEquals(360.0, provider.dropoffDurationFor(Id.createPersonId("parcel_dhl_1_B2C")), 1e-9);
    }

    @Test
    void paxKeepsNativeDurations() {
        assertEquals(60.0, provider.pickupDurationFor(Id.createPersonId("p42")), 1e-9);
        assertEquals(0.0, provider.dropoffDurationFor(Id.createPersonId("p42")), 1e-9);
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `mvn -q test -Dtest=SharedUseStopDurationProviderTest`
Expected: COMPILATION FAILURE.

- [ ] **Step 3: Implement the provider**

```java
package hagrid.integrated.shareduse;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contrib.drt.passenger.DrtRequest;
import org.matsim.contrib.drt.stops.PassengerStopDurationProvider;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;

import java.util.Map;

/**
 * Pax keep the native stop duration (pickup = drt stopDuration, dropoff = 0,
 * mirroring StaticPassengerStopDurationProvider.of(stopDuration, 0)); parcel
 * requests get the depot load time (pickup) and the segment dwell (dropoff,
 * person attribute written by ParcelAgentGenerator). Used inside
 * ParallelStopTimeCalculator, so a shared pax+parcel stop takes max(durations)
 * — and the insertion SEARCH prices it (spike §4).
 */
public final class SharedUseStopDurationProvider implements PassengerStopDurationProvider {

    private final double paxStopDuration;
    private final Map<Id<Person>, Double> parcelDwellById;

    public SharedUseStopDurationProvider(double paxStopDuration, Map<Id<Person>, Double> parcelDwellById) {
        this.paxStopDuration = paxStopDuration;
        this.parcelDwellById = Map.copyOf(parcelDwellById);
    }

    double pickupDurationFor(Id<Person> personId) {
        return SharedUse.isParcelPerson(personId.toString()) ? SharedUse.PICKUP_DURATION_S : paxStopDuration;
    }

    double dropoffDurationFor(Id<Person> personId) {
        if (SharedUse.isParcelPerson(personId.toString())) {
            Double dwell = parcelDwellById.get(personId);
            return dwell != null ? dwell : SharedUse.segmentDwellSeconds(1);
        }
        return 0.0;
    }

    @Override
    public double calcPickupDuration(DvrpVehicle vehicle, DrtRequest request) {
        return request.getPassengerIds().stream().mapToDouble(this::pickupDurationFor).max()
                .orElse(paxStopDuration);
    }

    @Override
    public double calcDropoffDuration(DvrpVehicle vehicle, DrtRequest request) {
        return request.getPassengerIds().stream().mapToDouble(this::dropoffDurationFor).max().orElse(0.0);
    }
}
```

- [ ] **Step 4: Run provider tests**

Run: `mvn -q test -Dtest=SharedUseStopDurationProviderTest`
Expected: 3 PASS.

- [ ] **Step 5: Config composition — write the failing config test**

```java
package hagrid.integrated.shareduse;

import hagrid.integrated.drt.DrtConfigComposer;
import org.junit.jupiter.api.Test;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;

import static org.junit.jupiter.api.Assertions.*;

class SharedUseConfigTest {

    @Test
    void sharedUseConfigHasLoadRetryStrategyAndActivityParams() {
        Config config = /* compose the baseline drt config the same way the existing
                           DrtConfigComposer test does (reuse its helper), then: */ null;
        DrtConfigComposer.composeSharedUse(config);

        DrtConfigGroup drt = MultiModeDrtConfigGroup.get(config).getModalElements().iterator().next();
        var load = drt.addOrGetLoadParams();
        assertEquals(java.util.List.of("passengers", "parcels"), load.getDimensions()); // VERIFY-SOURCE accessor
        // retry params present with all-day pending window
        assertTrue(drt.getParameterSets("dvrpRequestRetry").iterator().hasNext());
        // parcel subpopulation: selector-only strategy
        boolean parcelSelector = config.replanning().getStrategySettings().stream()
                .anyMatch(s -> "parcel".equals(s.getSubpopulation())
                        && "ChangeExpBeta".equals(s.getStrategyName()) && s.getWeight() == 1.0);
        assertTrue(parcelSelector);
        boolean parcelInnovation = config.replanning().getStrategySettings().stream()
                .anyMatch(s -> "parcel".equals(s.getSubpopulation())
                        && !"ChangeExpBeta".equals(s.getStrategyName()));
        assertFalse(parcelInnovation);
        // activity params exist and are non-scoring
        assertFalse(config.scoring().getActivityParams(SharedUse.ACT_DEPOT).isScoringThisActivityAtAll());
        assertFalse(config.scoring().getActivityParams(SharedUse.ACT_DELIVERY).isScoringThisActivityAtAll());
    }
}
```

(Use the existing DrtConfigComposer unit test as the template for obtaining a composed baseline `Config` — there is one in `src/test/java/hagrid/integrated/drt/`; reuse its setup verbatim. VERIFY-SOURCE the `DvrpLoadParams` accessor names — spike lists the field names `dimensions`, `mapFleetCapacity`, `defaultRequestDimension`.)

- [ ] **Step 6: Implement `composeSharedUse` in `DrtConfigComposer`**

```java
/** SHAREDUSE additions on top of the composed baseline drt config (call AFTER composeConfig):
 *  2D load type, all-day parcel retry window, parcel selector-only replanning,
 *  non-scoring parcel activity types. */
public static void composeSharedUse(Config config) {
    DrtConfigGroup drt = MultiModeDrtConfigGroup.get(config).getModalElements().iterator().next();

    var load = drt.addOrGetLoadParams();                       // param set "load"
    load.setDimensions(java.util.List.of("passengers", "parcels"));  // VERIFY-SOURCE setter names
    load.setMapFleetCapacity("passengers");                    // fleet XML scalar -> seats
    load.setDefaultRequestDimension("passengers");             // pax legs need no attributes

    var retry = new org.matsim.contrib.drt.optimizer.insertion.DrtRequestInsertionRetryParams(); // VERIFY-SOURCE package
    retry.setRetryInterval(300);
    retry.setMaxRequestAge(86400.0);                           // pending until day end (parcel-only via queue subclass)
    drt.addParameterSet(retry);

    ReplanningConfigGroup.StrategySettings parcelSelector = new ReplanningConfigGroup.StrategySettings();
    parcelSelector.setStrategyName("ChangeExpBeta");           // pure selector, no innovation
    parcelSelector.setSubpopulation(SharedUse.PARCEL_SUBPOPULATION);
    parcelSelector.setWeight(1.0);
    config.replanning().addStrategySettings(parcelSelector);

    for (String actType : java.util.List.of(SharedUse.ACT_DEPOT, SharedUse.ACT_DELIVERY)) {
        ScoringConfigGroup.ActivityParams params = new ScoringConfigGroup.ActivityParams(actType);
        params.setScoringThisActivityAtAll(false);
        config.scoring().addActivityParams(params);
    }
}
```

(Field-style vs setter-style on `DvrpLoadParams`/`DrtRequestInsertionRetryParams`: MATSim config groups often expose public fields — VERIFY-SOURCE and use whichever exists; the test pins the observable result.)

- [ ] **Step 7: Controller half of `SharedUseModule`**

```java
package hagrid.integrated.shareduse;

import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.api.core.v01.Id;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.stops.MinimumStopDurationAdapter;
import org.matsim.contrib.drt.stops.ParallelStopTimeCalculator;
import org.matsim.contrib.drt.stops.PassengerStopDurationProvider;
import org.matsim.contrib.drt.stops.StopTimeCalculator;
import org.matsim.contrib.dvrp.load.DvrpLoadFromFleet;
import org.matsim.contrib.dvrp.load.DvrpLoadType;
import org.matsim.contrib.dvrp.run.AbstractDvrpModeModule;

import java.util.HashMap;
import java.util.Map;

/**
 * Shared-Use composition for mode "drt". Controller scope here (dwell + 2D fleet
 * capacity); the QSim overrides (chi gate + parcel-only retry) are installed via
 * installOverridingQSimModule in Task 5. MUST be added via
 * controler.addOverridingModule AFTER DrtConfigComposer.installModules (same
 * override-ordering mechanism PtAndDrtFareModule relies on).
 */
public final class SharedUseModule extends AbstractDvrpModeModule {

    private final DrtConfigGroup drtCfg;
    private final double chiThreshold;

    public SharedUseModule(DrtConfigGroup drtCfg, double chiThreshold) {
        super(drtCfg.getMode());
        this.drtCfg = drtCfg;
        this.chiThreshold = chiThreshold;
    }

    @Override
    public void install() {
        bindModal(PassengerStopDurationProvider.class).toProvider(modalProvider(getter -> {
            Population population = getter.get(Population.class);
            Map<Id<Person>, Double> dwell = new HashMap<>();
            population.getPersons().values().stream()
                    .filter(p -> SharedUse.isParcelPerson(p.getId().toString()))
                    .forEach(p -> dwell.put(p.getId(),
                            (Double) p.getAttributes().getAttribute(SharedUse.DWELL_ATTRIBUTE)));
            return new SharedUseStopDurationProvider(drtCfg.stopDuration, dwell);
        })).asEagerSingleton();

        bindModal(StopTimeCalculator.class).toProvider(modalProvider(getter ->
                new MinimumStopDurationAdapter(
                        new ParallelStopTimeCalculator(getter.getModal(PassengerStopDurationProvider.class)),
                        drtCfg.stopDuration))).asEagerSingleton();

        bindModal(DvrpLoadFromFleet.class).toProvider(modalProvider(getter -> {
            DvrpLoadType loadType = getter.getModal(DvrpLoadType.class);
            return (capacity, vehicleId) -> loadType.fromMap(Map.of(
                    "passengers", capacity,                 // fleet XML scalar = seats (10)
                    "parcels", SharedUse.PARCEL_SLOTS));
        })).asEagerSingleton();

        // QSim half installed in Task 5
    }
}
```

(VERIFY-SOURCE: `ParallelStopTimeCalculator`/`MinimumStopDurationAdapter` constructor argument order; `DvrpLoadFromFleet` functional signature `getDvrpVehicleLoad(int, Id<DvrpVehicle>)` — adapt the lambda if it is not a SAM interface.)

- [ ] **Step 8: Run config test + full suite; commit**

Run: `mvn -q test -Dtest=SharedUseConfigTest,SharedUseStopDurationProviderTest` then `mvn -q test`

```bash
git add parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/shareduse/SharedUseStopDurationProvider.java \
        parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/shareduse/SharedUseModule.java \
        parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/drt/DrtConfigComposer.java \
        parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/shareduse/SharedUseStopDurationProviderTest.java \
        parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/shareduse/SharedUseConfigTest.java
git commit -m "feat(shareduse): 2D load + per-request dwell composition (1c Task 4)"
```

---

### Task 5: χ-gate + parcel-only pending — QSim half

**Files:**
- Create: `src/main/java/hagrid/integrated/shareduse/ChiGateInsertionCostCalculator.java`
- Create: `src/main/java/hagrid/integrated/shareduse/ParcelOnlyRetryQueue.java`
- Modify: `src/main/java/hagrid/integrated/shareduse/SharedUseModule.java` (add `installOverridingQSimModule`)
- Test: `ChiGateInsertionCostCalculatorTest.java`, `ParcelOnlyRetryQueueTest.java`

**Interfaces:**
- Consumes (VERIFY-SOURCE): `InsertionCostCalculator` (`double calculate(DrtRequest, Insertion, DetourTimeInfo)`, constant `INFEASIBLE_SOLUTION_COST`); `DefaultInsertionCostCalculator(CostCalculationStrategy, DrtOptimizationConstraintsSet)` construction as in `DrtModeOptimizerQSimModule` (spike §3 quotes the PR3552 line; re-check 2025.0); `DrtRequestInsertionRetryQueue` (concrete class, `boolean tryAddFailedRequest(DrtRequest, double now)` public non-final; constructor takes `DrtRequestInsertionRetryParams` — VERIFY-SOURCE).
- Produces: `ChiGateInsertionCostCalculator(InsertionCostCalculator delegate, double chiThreshold)`; `ParcelOnlyRetryQueue extends DrtRequestInsertionRetryQueue`; the complete `SharedUseModule`.

- [ ] **Step 1: Write the failing χ-gate test**

`DrtRequest` builds via `DrtRequest.newBuilder()` (spike §1); a stub delegate makes the test independent of the real cost machinery:

```java
package hagrid.integrated.shareduse;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contrib.drt.optimizer.insertion.InsertionCostCalculator;
import org.matsim.contrib.drt.passenger.DrtRequest;
import org.matsim.contrib.dvrp.optimizer.Request;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChiGateInsertionCostCalculatorTest {

    private static DrtRequest request(String personId) {
        return DrtRequest.newBuilder()
                .id(Id.create("r1", Request.class))
                .passengerIds(List.of(Id.createPersonId(personId)))
                .mode("drt")
                .build();                                   // VERIFY-SOURCE: mandatory builder fields
    }

    @Test
    void parcelAboveThresholdIsInfeasible() {
        var gate = new ChiGateInsertionCostCalculator((req, ins, detour) -> 700.0, 600.0);
        assertEquals(InsertionCostCalculator.INFEASIBLE_SOLUTION_COST,
                gate.calculate(request("parcel_dhl_1_B2C"), null, null));
    }

    @Test
    void parcelBelowThresholdKeepsCost() {
        var gate = new ChiGateInsertionCostCalculator((req, ins, detour) -> 500.0, 600.0);
        assertEquals(500.0, gate.calculate(request("parcel_dhl_1_B2C"), null, null));
    }

    @Test
    void paxIsNeverGated() {
        var gate = new ChiGateInsertionCostCalculator((req, ins, detour) -> 99_999.0, 600.0);
        assertEquals(99_999.0, gate.calculate(request("p42"), null, null));
    }
}
```

- [ ] **Step 2: Run to verify failure; implement**

Run: `mvn -q test -Dtest=ChiGateInsertionCostCalculatorTest` → COMPILATION FAILURE. Then:

```java
package hagrid.integrated.shareduse;

import org.matsim.contrib.drt.optimizer.insertion.InsertionCostCalculator;
import org.matsim.contrib.drt.optimizer.insertion.InsertionGenerator.Insertion;
import org.matsim.contrib.drt.optimizer.insertion.InsertionDetourTimeCalculator.DetourTimeInfo;
import org.matsim.contrib.drt.passenger.DrtRequest;

/**
 * Step-C static acceptance (spec §4.2): a PARCEL insertion whose marginal cost
 * (totalTimeLoss, seconds of additional vehicle time) exceeds chi is infeasible;
 * passenger requests pass through untouched. The rejected parcel goes to the
 * retry queue (ParcelOnlyRetryQueue) and stays "pending".
 */
public final class ChiGateInsertionCostCalculator implements InsertionCostCalculator {

    private final InsertionCostCalculator delegate;
    private final double chiThreshold;

    public ChiGateInsertionCostCalculator(InsertionCostCalculator delegate, double chiThreshold) {
        this.delegate = delegate;
        this.chiThreshold = chiThreshold;
    }

    static boolean isParcel(DrtRequest request) {
        return request.getPassengerIds().stream()
                .anyMatch(id -> SharedUse.isParcelPerson(id.toString()));
    }

    @Override
    public double calculate(DrtRequest drtRequest, Insertion insertion, DetourTimeInfo detourTimeInfo) {
        double cost = delegate.calculate(drtRequest, insertion, detourTimeInfo);
        if (isParcel(drtRequest) && cost > chiThreshold) {
            return INFEASIBLE_SOLUTION_COST;
        }
        return cost;
    }
}
```

Run the test again → 3 PASS. (Exact import paths for `Insertion`/`DetourTimeInfo` — VERIFY-SOURCE; they are nested types in 2025.0 as in PR3552.)

- [ ] **Step 3: `ParcelOnlyRetryQueue` — failing test, then implement**

Test: construct with params (`retryInterval=300, maxRequestAge=86400`); `tryAddFailedRequest(paxRequest, now)` returns `false` (native immediate rejection); `tryAddFailedRequest(parcelRequest, now)` returns `true` and the request re-emerges via the queue's retrieval method (name per source, e.g. `getRequestsToRetryNow(now + 300)`) — VERIFY-SOURCE the retrieval API and assert accordingly.

```java
package hagrid.integrated.shareduse;

import org.matsim.contrib.drt.optimizer.insertion.DrtRequestInsertionRetryParams;
import org.matsim.contrib.drt.optimizer.insertion.DrtRequestInsertionRetryQueue;
import org.matsim.contrib.drt.passenger.DrtRequest;

/** Pending semantics for parcels ONLY: pax rejections stay native-immediate
 *  (identical KPI semantics to DRT_BASELINE), parcels retry until maxRequestAge. */
public final class ParcelOnlyRetryQueue extends DrtRequestInsertionRetryQueue {

    public ParcelOnlyRetryQueue(DrtRequestInsertionRetryParams params) {
        super(params);
    }

    @Override
    public boolean tryAddFailedRequest(DrtRequest request, double now) {
        if (!ChiGateInsertionCostCalculator.isParcel(request)) {
            return false;
        }
        return super.tryAddFailedRequest(request, now);
    }
}
```

- [ ] **Step 4: Complete `SharedUseModule` with the QSim half**

Append inside `install()` (pattern: spike §7; scope law: QSim keys ONLY here):

```java
installOverridingQSimModule(new org.matsim.contrib.dvrp.run.AbstractDvrpModeQSimModule(getMode()) {
    @Override
    protected void configureQSim() {
        bindModal(org.matsim.contrib.drt.optimizer.insertion.InsertionCostCalculator.class)
                .toProvider(modalProvider(getter -> new ChiGateInsertionCostCalculator(
                        new org.matsim.contrib.drt.optimizer.insertion.DefaultInsertionCostCalculator(
                                getter.getModal(org.matsim.contrib.drt.optimizer.CostCalculationStrategy.class),
                                drtCfg.addOrGetDrtOptimizationConstraintsParams()
                                        .addOrGetDefaultDrtOptimizationConstraintsSet()),
                        chiThreshold)));
        bindModal(org.matsim.contrib.drt.optimizer.insertion.DrtRequestInsertionRetryQueue.class)
                .toInstance(new ParcelOnlyRetryQueue(
                        drtCfg.getDrtRequestInsertionRetryParams()));   // VERIFY-SOURCE accessor
    }
});
```

(`DefaultInsertionCostCalculator` construction mirrors the contrib's own binding, spike §3 verbatim quote; re-check the 2025.0 constructor. The retry-params accessor on `DrtConfigGroup` — VERIFY-SOURCE; PR3552 exposes the param set via the config group, name may be `getDrtRequestInsertionRetryParams()` returning `Optional`.)

- [ ] **Step 5: Run all shareduse tests + full suite; commit**

Run: `mvn -q test -Dtest=ChiGateInsertionCostCalculatorTest,ParcelOnlyRetryQueueTest` then `mvn -q test`

```bash
git add parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/shareduse/ChiGateInsertionCostCalculator.java \
        parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/shareduse/ParcelOnlyRetryQueue.java \
        parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/shareduse/SharedUseModule.java \
        parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/shareduse/ChiGateInsertionCostCalculatorTest.java \
        parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/shareduse/ParcelOnlyRetryQueueTest.java
git commit -m "feat(shareduse): chi insertion gate + parcel-only retry queue (1c Task 5)"
```

---

### Task 6: Runner dispatch + `chiThreshold` key

**Files:**
- Modify: `src/main/java/hagrid/simulation/SimulationRunnerUtils.java` (`runSimulation` DRT branch, `parseScenario`, ctor call)
- Modify: `src/main/java/hagrid/simulation/HAGRIDSimulationConfig.java` (field/getter/ctor param `chiThreshold`)
- Test: extend the existing `parseScenario`-covering test (find it via `Glob src/test/java/**/SimulationRunner*Test.java`) + `SharedUseDispatchTest`

**Interfaces:**
- Consumes: existing dispatch (`SimulationRunnerUtils.java:226-271`), `HagridConfig.Scenario.DRT_SHAREDUSE` (already in the enum, `HagridConfig.java:72`), `isDrtScenario()` already true for SHAREDUSE.
- Produces: runner key `chiThreshold` (nonNegDouble, default `600.0`), getter `HAGRIDSimulationConfig.getChiThreshold()`; SHAREDUSE dispatch = DRT path WITHOUT jsprit/carriers, WITH `composeSharedUse` + `SharedUseModule`.

- [ ] **Step 1: Failing parse test**

In the existing runner-parse test class add:

```java
@Test
void chiThresholdParsesAndDefaults() {
    HAGRIDSimulationConfig cfg = SimulationRunnerUtils.parseScenarios(new String[]{
            "concept=drt_shareduse,date=2025-05-13,studyArea=LAUSITZ_HOYERSWERDA,chiThreshold=450"}).get(0);
    assertEquals(450.0, cfg.getChiThreshold(), 1e-9);
    HAGRIDSimulationConfig def = SimulationRunnerUtils.parseScenarios(new String[]{
            "concept=drt_shareduse,date=2025-05-13,studyArea=LAUSITZ_HOYERSWERDA"}).get(0);
    assertEquals(600.0, def.getChiThreshold(), 1e-9);
}
```

- [ ] **Step 2: Implement key threading**

Follow the `fleetSize`/`freight` precedent exactly: parse in `parseScenario` (`SimulationRunnerUtils.java:105-180`) via the `nonNegDouble` helper with default `600.0`; add `private final double chiThreshold` + getter + widest-ctor param in `HAGRIDSimulationConfig` (chained ctors at `:117/:139/:164` — extend the widest, default the others). Run the parse test → PASS.

- [ ] **Step 3: Dispatch — modify the DRT branch in `runSimulation`**

At `SimulationRunnerUtils.java:236-271`, introduce the scenario switch:

```java
boolean sharedUse = HagridConfig.Scenario.valueOf(cfg.getConcept().toUpperCase())
        == HagridConfig.Scenario.DRT_SHAREDUSE;

if (cfg.isDrtWithFreight() && !sharedUse) {          // D7: SHAREDUSE never runs jsprit
    LausitzFreightPreprocessor.run(...);              // unchanged existing call
} else if (sharedUse && cfg.isDrtWithFreight()) {
    LOG.info("DRT_SHAREDUSE: freight flag ignored - parcels ride the DRT fleet (no jsprit/carriers)");
}

Scenario scenario = DrtScenarioBuilder.build(cfg);
if (sharedUse) {
    DrtConfigComposer.composeSharedUse(scenario.getConfig());
}
if (cfg.isDrtWithFreight() && !sharedUse) {
    FreightRunComposer.addCarriers(...);              // unchanged
}
Controler controler = new Controler(scenario);
... // depots + DrtConfigComposer.installModules(...) unchanged
if (cfg.isDrtWithFreight() && !sharedUse) {
    FreightRunComposer.installCarrierModules(...);    // unchanged
}
if (sharedUse) {
    DrtConfigGroup drtCfg = MultiModeDrtConfigGroup.get(scenario.getConfig())
            .getModalElements().iterator().next();
    controler.addOverridingModule(new SharedUseModule(drtCfg, cfg.getChiThreshold()));  // LAST module
}
controler.run();
```

**Ordering is load-bearing:** `composeSharedUse` must run BEFORE `new Controler(scenario)` (config is read at controler construction); `SharedUseModule` must be the LAST `addOverridingModule` (spike §7 install order).

- [ ] **Step 4: `SharedUseDispatchTest`** — a boot-level test in the style of `DrtBaselineIntegrationTest` (inline config, `lastIteration=0`, fleet 4): assert the injector boots with `SharedUseModule` installed (no `BindingAlreadySet`, no missing binding) and one parcel-person's request gets submitted (events contain a `DrtRequest submitted` for a `parcel_` person or the drt output exists). Keep it minimal — the full assertion set lives in Task 7's e2e.

- [ ] **Step 5: Full suite; commit**

Run: `mvn -q test`

```bash
git add parcel-demand-2-matsim-pipeline/src/main/java/hagrid/simulation/SimulationRunnerUtils.java \
        parcel-demand-2-matsim-pipeline/src/main/java/hagrid/simulation/HAGRIDSimulationConfig.java \
        <touched test files>
git commit -m "feat(shareduse): DRT_SHAREDUSE dispatch + chiThreshold key (1c Task 6)"
```

---

### Task 7: δ/channel KPI handler + `SharedUseEndToEndTest`

**Files:**
- Create: `src/main/java/hagrid/integrated/shareduse/SharedUseKpiHandler.java`
- Modify: `src/main/java/hagrid/integrated/shareduse/SharedUseModule.java` (register handler + shutdown writer)
- Test: `SharedUseKpiHandlerTest.java`, `SharedUseEndToEndTest.java`

**Interfaces:**
- Consumes: events `PassengerRequestSubmittedEvent` (has request id + person ids; in 2025.0 also the serialized DvrpLoad), `PassengerRequestRejectedEvent`, `PassengerDroppedOffEvent` (all `org.matsim.contrib.dvrp.passenger`); person attributes (`LOAD_ATTRIBUTE`, `CHANNEL_ATTRIBUTE`) via injected `Population`; `ShutdownListener` + `OutputDirectoryHierarchy` for the output path.
- Produces: `<matsim-output>/shareduse_channel_stats.csv`, format `metric;value` (`;`, dot decimals, UTF-8 — 1e conventions), metrics: `segments_submitted`, `segments_delivered`, `segments_rejected_final`, `segments_pending_eod`, `parcels_submitted`, `parcels_delivered`, `parcels_undelivered`, `undelivered_rate`, `share_channel_door`, `share_channel_locker`, `mean_delivery_delay_s` (dropoff − submission, mean over delivered segments).
- δ definition (spike §6): submitted-without-dropoff; `pending_eod = submitted − delivered − rejected_final` (no event fires for still-pending requests at QSim end — the handler derives it at shutdown, NOT from PersonStuck).

- [ ] **Step 1: Failing handler unit test** — feed synthetic events (2 parcel submissions with loads 3+2, 1 dropoff for the first after 1800 s, no event for the second; 1 pax submission+dropoff that must be ignored) and assert the counters + the written CSV: `segments_submitted=2`, `segments_delivered=1`, `segments_pending_eod=1`, `parcels_submitted=5`, `parcels_delivered=3`, `undelivered_rate=0.4`, `mean_delivery_delay_s=1800`, `share_channel_door=1.0`.

- [ ] **Step 2: Implement the handler** — an `EventHandler` implementing the three event interfaces (VERIFY-SOURCE the handler interface names, e.g. `PassengerRequestSubmittedEventHandler`), keyed by request id, parcel-filtered via `SharedUse.isParcelPerson` on the event's person ids; loads/channels resolved once from `Population` at construction; `writeCsv(Path)` called from a `ShutdownListener` registered in `SharedUseModule`:

```java
// in SharedUseModule.install() (controller scope):
bind(SharedUseKpiHandler.class).asEagerSingleton();
addEventHandlerBinding().to(SharedUseKpiHandler.class);
addControlerListenerBinding().to(SharedUseKpiHandler.class);   // implements ShutdownListener
// output path: getter.get(OutputDirectoryHierarchy.class).getOutputFilename("shareduse_channel_stats.csv")
```

- [ ] **Step 3: `SharedUseEndToEndTest`** — the production-path proof, modeled on `MarriedBaselineEndToEndTest` (grid fixtures, `LmdTestShapefiles.writeDemand`, depots csv, `lausitz-native-like.config.xml`, `lastIteration = 1` so replanning fires on the parcel subpopulation):
  1. `LausitzDrtPreprocessor.run(cfg)` with `concept=drt_shareduse`, `fleetSize=4`.
  2. `DrtScenarioBuilder.build(cfg)` + `composeSharedUse` + `Controler` + `DrtConfigComposer.installModules` + `SharedUseModule` (exact Task 6 order).
  3. `controler.run()`, then assert: (a) run completes (no Guice/validator crash — THE composition proof); (b) `shareduse_channel_stats.csv` exists with `segments_submitted >= 1`; (c) conservation: `segments_delivered + segments_rejected_final + segments_pending_eod == segments_submitted`; (d) NO `output_carriers.xml.gz` (no CarrierModule); (e) at least one `PersonEntersVehicle` of a `parcel_` person into a `drt_` vehicle in the events file (a parcel physically rode) — with fleet 4 / a handful of requests on the grid this must succeed; if it flakes, raise χ/fleet in the fixture, never assert-away.

- [ ] **Step 4: Full suite; commit**

Run: `mvn -q test`

```bash
git add parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/shareduse/SharedUseKpiHandler.java \
        parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/shareduse/SharedUseModule.java \
        parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/shareduse/SharedUseKpiHandlerTest.java \
        parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/shareduse/SharedUseEndToEndTest.java
git commit -m "feat(shareduse): delta/channel KPI handler + end-to-end test (1c Task 7)"
```

---

### Task 8: `extract_shareduse.py` (1e contract — requires the 1e plan landed)

**Files:**
- Create: `parcel-demand-2-matsim-pipeline/analysis/kpi/extract_shareduse.py`
- Modify: `parcel-demand-2-matsim-pipeline/analysis/kpi/build_kpis.py` (register in `EXTRACTORS`)
- Test: `analysis/kpi/tests/test_extract_shareduse.py` (+ fixture `shareduse_channel_stats.csv`, legs fixture reused)

**Interfaces:**
- Consumes: `<run>/shareduse_channel_stats.csv` (Task 7 format); `<prefix>.output_drt_legs_drt.csv` (`personId` column — parcel legs carry the `parcel_` prefix).
- Produces: rows `channel/undelivered_rate`, `channel/share_channel_door`, `channel/share_channel_locker`, `freight/parcels_submitted|delivered|undelivered` (source `shareduse_channel_stats`), `freight/mean_delivery_delay_s`; PLUS corrected pax rows (D10) computed from legs with `personId` not starting `parcel_`: `passenger/drt_rides_pax_only`, `passenger/wait_mean_pax_only`, `passenger/wait_median_pax_only` (source `output_drt_legs pax-filter`).

- [ ] **Step 1: Failing test** — fixture stats CSV (`metric;value` lines from Task 7's unit-test expectations) + a legs fixture containing 3 pax legs and 2 `parcel_` legs; assert the channel rows land, `drt_rides_pax_only == 3`, `wait_mean_pax_only` averages ONLY the pax rows, and every row has the five extractor keys.

- [ ] **Step 2: Implement** (mirror `extract_freight.py` style: `common.row`, pandas, `;`):

```python
# -*- coding: utf-8 -*-
"""Shared-Use KPI rows: channel/delta stats from shareduse_channel_stats.csv
plus pax-only corrected passenger KPIs (stock drt_customer_stats mixes parcels
into pax numbers in DRT_SHAREDUSE runs - D10 of the 1c plan)."""
from pathlib import Path

import pandas as pd

from common import row

PARCEL_PREFIX = "parcel_"


def has_shareduse_stats(run_dir, meta):
    return (Path(run_dir) / "shareduse_channel_stats.csv").exists()


def extract(run_dir, prefix):
    run_dir = Path(run_dir)
    rows = []
    stats = dict(pd.read_csv(run_dir / "shareduse_channel_stats.csv", sep=";").values)
    rows += [
        row("channel", "undelivered_rate", float(stats["undelivered_rate"]), "share", "shareduse_channel_stats"),
        row("channel", "share_channel_door", float(stats["share_channel_door"]), "share", "shareduse_channel_stats"),
        row("channel", "share_channel_locker", float(stats["share_channel_locker"]), "share", "shareduse_channel_stats"),
        row("freight", "parcels_submitted", int(stats["parcels_submitted"]), "parcels", "shareduse_channel_stats"),
        row("freight", "parcels_delivered", int(stats["parcels_delivered"]), "parcels", "shareduse_channel_stats"),
        row("freight", "parcels_undelivered", int(stats["parcels_undelivered"]), "parcels", "shareduse_channel_stats"),
        row("freight", "mean_delivery_delay_s", float(stats["mean_delivery_delay_s"]), "s", "shareduse_channel_stats"),
    ]
    legs_f = run_dir / (prefix + ".output_drt_legs_drt.csv")
    if legs_f.exists():
        legs = pd.read_csv(legs_f, sep=";")
        pax = legs[~legs["personId"].astype(str).str.startswith(PARCEL_PREFIX)]
        rows += [
            row("passenger", "drt_rides_pax_only", int(len(pax)), "trips", "output_drt_legs pax-filter"),
            row("passenger", "wait_mean_pax_only", float(pax["waitTime"].mean()), "s", "output_drt_legs pax-filter"),
            row("passenger", "wait_median_pax_only", float(pax["waitTime"].median()), "s", "output_drt_legs pax-filter"),
        ]
    return rows
```

Register in `build_kpis.py`: `EXTRACTORS.append((extract_shareduse.has_shareduse_stats, extract_shareduse.extract))` (import at top).

- [ ] **Step 3: Run pytest; commit**

Run (from `analysis/kpi/`): `python -u -m pytest tests/ -q`

```bash
git add parcel-demand-2-matsim-pipeline/analysis/kpi/extract_shareduse.py \
        parcel-demand-2-matsim-pipeline/analysis/kpi/build_kpis.py \
        parcel-demand-2-matsim-pipeline/analysis/kpi/tests/test_extract_shareduse.py \
        <fixture files>
git commit -m "feat(kpi): shared-use channel/delta extractor + pax-only corrections (1c Task 8)"
```

---

### Task 9: Real-data smoke run (manual acceptance)

**Files:** none (run + inspect).

- [ ] **Step 1: Prepare + run** (two-step invocation, from `parcel-demand-2-matsim-pipeline/`, `-Xmx16g`, IDENTICAL args both steps — see memory `feedback_drt_runs_operational`):

```
mvn exec:java -Dexec.mainClass=hagrid.integrated.drt.PrepareLausitzDrtInputs -Dexec.args="concept=drt_shareduse,date=2025-05-13,studyArea=LAUSITZ_HOYERSWERDA,fleetSize=120,maxIter=1,tag=susmoke"
mvn exec:java -Dexec.mainClass=hagrid.HAGRIDSimulationRunner -Dexec.args="concept=drt_shareduse,date=2025-05-13,studyArea=LAUSITZ_HOYERSWERDA,fleetSize=120,maxIter=1,tag=susmoke"
```

(Exact exec invocation as used for the married smoke — check the session log / `track_sweep.ps1` for the proven command shape incl. `-Xmx16g`.)

- [ ] **Step 2: Acceptance checks** on `hagrid-matsim-output/DRT_SHAREDUSE_13052025_susmoke_iter1_jsprit100/`:
  - Run exits 0; `shareduse_channel_stats.csv` present; `segments_submitted` ≈ in-area segment count (~1,000 of the 1,056 — Ruhland-clip removes a few); conservation identity holds.
  - `parcels_delivered > 0` (the fleet actually carries parcels) and `undelivered_rate < 1.0`.
  - Log line from `ParcelAgentGenerator` shows plausible counts (parcels ≈ 6,3xx in-area, skippedSameLink small).
  - NO `output_carriers.xml.gz`; `run_metadata.json` says `scenario=DRT_SHAREDUSE` (1e Task 1 writer).
  - Then (1e landed): `python -u build_kpis.py --run-dir ...susmoke...` → channel rows + `*_pax_only` rows appear in `kpis_long.csv`.
  - Report the numbers to the user — the overnight iter-150 headline run is a SEPARATE user decision (runtime will exceed the pax-only ~6 h; parcels add ~1,000 requests + longer stops).

---

## Self-Review (run after writing, fix inline)

1. **Spec §4.2 coverage:** segment-stop insertion unit ✅ (PANDA demand IS segment-aggregated, 1,056 segments; one person per segment-delivery); depot pickup ✅ (nearest depot, `DepotNetwork` finally in production); stretched dwell ✅ (LMD-parity formula, priced by the insertion search); 2D capacity ✅ (native DvrpLoad, Task 1 bump); online pax insertion untouched ✅; χ static acceptance ✅ (Task 5); pending/undelivered δ ✅ (retry queue + event-derived, Task 7); channel logic ✅ structurally, door-only per D1; channel shares as params+KPIs — shares emitted as KPIs ✅, the *input* share lever (`b2cLockerShare`) deliberately NOT wired (D8, no lockers to share into); Ride-and-Collect Phase 2 ✅ (absent); operator = Einheitsunternehmen ✅ (provider dissolved, kept as attribute).
2. **Placeholder scan:** no TBDs. VERIFY-SOURCE markers are explicit verification instructions against already-fetched sources jars with the expected signatures stated — deliberate for the handful of 2025.0 accessors the spike did not line-verify.
3. **Type consistency:** `SharedUse` constants referenced identically across Tasks 2–8 ✅; `ChiGateInsertionCostCalculator.isParcel` reused by `ParcelOnlyRetryQueue` ✅; `composeSharedUse(Config)` (Task 4) called in Task 6 dispatch ✅; `SharedUseModule(DrtConfigGroup, double)` ctor consistent Task 4/5/6 ✅; CSV metric names Task 7 == fixture keys Task 8 ✅.

## Execution notes

- **Order:** Task 1 is the gate — if the e2e gate fails, STOP (user decision on the lausitz dep). Tasks 2→7 sequential; Task 8 requires the 1e plan landed (execute 1e first, as agreed); Task 9 is manual acceptance.
- **Deferred, tracked:** Packstation locations file + `b2cLockerShare` wiring (D1); autonomy switch plan (D2); parcel time-window semantics beyond the sliding retry window; prebooked advance submission; χ calibration + fleet sizing (calibration phase, after 1d).
- The spike's residual risks list (`docs/superpowers/notes/2026-07-06-shareduse-dvrp-insertion-spike.md`, bottom) is the executor's watch-list — especially: gate χ on raw `totalTimeLoss` (not soft-violation-penalized cost) if `DiscourageSoftConstraintViolations` turns out to be the bound strategy.
