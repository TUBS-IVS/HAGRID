# DRT Depot-Dispatching Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace round-robin DRT vehicle placement with real depot dispatching — vehicles spawn evenly across the 7 shared LMD depots (snapped to the DRT sub-network), rebalance toward demand zones during the day (idle = may stay put), and return toward depots at service end (simulated where cheap, KPI-accounted for the residual).

**Architecture:** Pure-config changes wherever MATSim supports them (demand rebalancing + square-grid zones are config-only via `MinCostFlowRebalancingStrategyParams` + `DrtZoneSystemParams`). Depot spawning is a new `DrtFleetGenerator` path that snaps depot coords to the existing `drtSubNet` and even-splits the fleet. Return-to-depot is a custom modal `RebalancingTargetCalculator` (demand-based by day, depot-targeting in a final window) plus a Python KPI fallback for vehicles still at a last stop at sim end.

**Tech Stack:** Java 21, MATSim `2025.0-PR3552` (contrib `drt`/`dvrp`), JUnit Jupiter + AssertJ, Python (analysis/dashboard).

## Global Constraints

- MATSim core + contrib `drt`/`dvrp` version: `2025.0-PR3552` (do not change). Java 21.
- Depot coordinates are EPSG:25832; reuse the existing config getter `HAGRIDSimulationConfig.getLmdDepotCsv()` for the depot source — **do NOT add a new config key**.
- Depot CSV format is `provider;x;y` with a header line; for DRT only the `x;y` columns matter.
- The DRT spawn target is the in-memory `drtSubNet` built in `LausitzDrtPreprocessor.run(...)` (links carrying `TransportMode.drt`). Snap depot coords to **that** network.
- Reuse the verified rebalancing API exactly (see Task 4/5). Enum constant casing is the one uncertainty — each task that touches the rebalancing API has an explicit verification step before implementing.
- **Commits:** no `.gitignore` in this repo → stage explicit files only. The `git commit` steps below are the intended grouping, but **do not commit until Hendrik approves** — surface the staged diff and wait. Branch is `hendrik`; never merge to `master`.
- Run tests from the module dir: `mvn -pl parcel-demand-2-matsim-pipeline -Dtest=<Class> test`.

---

### Task 1: DrtDepotReader — read depot coordinates

**Files:**
- Create: `parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/drt/DrtDepotReader.java`
- Test: `parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/drt/DrtDepotReaderTest.java`

**Interfaces:**
- Produces: `static List<Coord> DrtDepotReader.readCoords(Path csv)` — returns depot coords in file order; throws `IllegalArgumentException` on an empty/malformed file.

- [ ] **Step 1: Write the failing test**

```java
package hagrid.integrated.drt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

@DisplayName("DrtDepotReader")
class DrtDepotReaderTest {

    @Test
    @DisplayName("reads provider;x;y rows into coords, skipping header and blank lines")
    void readsCoords(@TempDir Path tmp) throws Exception {
        Path csv = tmp.resolve("depots.csv");
        Files.writeString(csv, "provider;x;y\ndhl;100.0;200.0\namazon;300.5;400.5\n\n");
        List<Coord> coords = DrtDepotReader.readCoords(csv);
        assertThat(coords).hasSize(2);
        assertThat(coords.get(0)).isEqualTo(new Coord(100.0, 200.0));
        assertThat(coords.get(1)).isEqualTo(new Coord(300.5, 400.5));
    }

    @Test
    @DisplayName("rejects a file with no data rows")
    void rejectsEmpty(@TempDir Path tmp) throws Exception {
        Path csv = tmp.resolve("e.csv");
        Files.writeString(csv, "provider;x;y\n");
        assertThatThrownBy(() -> DrtDepotReader.readCoords(csv))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl parcel-demand-2-matsim-pipeline -Dtest=DrtDepotReaderTest test`
Expected: FAIL — `DrtDepotReader` does not exist (compile error).

- [ ] **Step 3: Write minimal implementation**

```java
package hagrid.integrated.drt;

import org.matsim.api.core.v01.Coord;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads depot coordinates from a {@code provider;x;y} CSV (EPSG:25832). For DRT
 * only the x;y columns matter — the provider/LSP identity is ignored. Returns
 * coordinates in file order.
 */
public final class DrtDepotReader {

    private DrtDepotReader() {}

    public static List<Coord> readCoords(Path csv) {
        List<Coord> coords = new ArrayList<>();
        try {
            List<String> lines = Files.readAllLines(csv);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (line.isEmpty()) {
                    continue;
                }
                if (i == 0 && line.toLowerCase().startsWith("provider")) {
                    continue;
                }
                String[] p = line.split(";");
                if (p.length < 3) {
                    throw new IllegalArgumentException("malformed depot row: " + line);
                }
                coords.add(new Coord(Double.parseDouble(p[1].trim()), Double.parseDouble(p[2].trim())));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (coords.isEmpty()) {
            throw new IllegalArgumentException("no depot coordinates in " + csv);
        }
        return coords;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl parcel-demand-2-matsim-pipeline -Dtest=DrtDepotReaderTest test`
Expected: PASS (2 tests).

- [ ] **Step 5: Stage (commit on Hendrik's go-ahead)**

```bash
git add parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/drt/DrtDepotReader.java \
        parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/drt/DrtDepotReaderTest.java
git commit -m "feat(drt): DrtDepotReader reads provider;x;y depot coords"
```

---

### Task 2: DrtFleetGenerator — depot spawn, snap, even split

**Files:**
- Modify: `parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/drt/DrtFleetGenerator.java`
- Test: `parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/drt/DrtFleetGeneratorTest.java`

**Interfaces:**
- Consumes: a `List<Coord>` of depot coordinates (from Task 1) and the DRT sub-network.
- Produces: `static void DrtFleetGenerator.writeFromDepots(Network net, List<Coord> depotCoords, int fleetSize, int capacity, double serviceBegin, double serviceEnd, Path out)` — snaps each depot coord to the nearest link in `net`, assigns vehicle `i` to `depotLink[i % nDepots]`, writes the fleet XML. The existing `write(...)` round-robin method is retained unchanged (still covered by existing tests).

- [ ] **Step 1: Write the failing test** (append to `DrtFleetGeneratorTest`)

Add these imports at the top if missing: `import java.util.List;`

```java
    /** Two disjoint links: l1 near (50,0), l2 near (1050,0). */
    private Network twoLinkNet() {
        Network n = NetworkUtils.createNetwork();
        NetworkFactory f = n.getFactory();
        Node a = f.createNode(Id.createNodeId("a"), new Coord(0, 0));
        Node b = f.createNode(Id.createNodeId("b"), new Coord(100, 0));
        Node c = f.createNode(Id.createNodeId("c"), new Coord(1000, 0));
        Node d = f.createNode(Id.createNodeId("d"), new Coord(1100, 0));
        n.addNode(a); n.addNode(b); n.addNode(c); n.addNode(d);
        Link l1 = f.createLink(Id.createLinkId("l1"), a, b);
        Link l2 = f.createLink(Id.createLinkId("l2"), c, d);
        l1.setAllowedModes(Set.of("car", "drt"));
        l2.setAllowedModes(Set.of("car", "drt"));
        n.addLink(l1); n.addLink(l2);
        return n;
    }

    private static int count(String xml, String needle) {
        return xml.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }

    @Test
    @DisplayName("spawns vehicles evenly across depots, snapped to nearest links")
    void evenSplitAcrossDepots(@TempDir Path tmp) throws Exception {
        Path out = tmp.resolve("fleet.xml");
        // one depot near l1, one near l2
        DrtFleetGenerator.writeFromDepots(twoLinkNet(),
                List.of(new Coord(40, 0), new Coord(1040, 0)),
                4, 8, 0.0, 86400.0, out);
        String xml = Files.readString(out);
        assertThat(count(xml, "<vehicle ")).isEqualTo(4);
        assertThat(count(xml, "start_link=\"l1\"")).isEqualTo(2);
        assertThat(count(xml, "start_link=\"l2\"")).isEqualTo(2);
    }

    @Test
    @DisplayName("snaps a depot that lies outside the network to the nearest link")
    void snapsOutsideDepot(@TempDir Path tmp) throws Exception {
        Path out = tmp.resolve("fleet2.xml");
        // single depot far to the east -> nearest link is l2
        DrtFleetGenerator.writeFromDepots(twoLinkNet(),
                List.of(new Coord(9000, 0)), 3, 8, 0.0, 86400.0, out);
        String xml = Files.readString(out);
        assertThat(count(xml, "<vehicle ")).isEqualTo(3);
        assertThat(count(xml, "start_link=\"l2\"")).isEqualTo(3);
    }

    @Test
    @DisplayName("rejects an empty depot list")
    void rejectsNoDepots(@TempDir Path tmp) {
        assertThatThrownBy(() -> DrtFleetGenerator.writeFromDepots(twoLinkNet(),
                List.of(), 3, 8, 0.0, 86400.0, tmp.resolve("f.xml")))
                .isInstanceOf(IllegalArgumentException.class);
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl parcel-demand-2-matsim-pipeline -Dtest=DrtFleetGeneratorTest test`
Expected: FAIL — `writeFromDepots` does not exist (compile error).

- [ ] **Step 3: Write minimal implementation** (add to `DrtFleetGenerator`)

Add imports: `import org.matsim.api.core.v01.Coord;` and `import org.matsim.core.network.NetworkUtils;`

```java
    /**
     * Generates a fleet whose vehicles are dispatched from depots. Each depot
     * coordinate is snapped to the nearest link in {@code net} (the DRT
     * sub-network), so depots lying just outside the service area snap to the
     * nearest in-area link. Vehicles are split evenly across depots:
     * vehicle {@code i} starts at {@code depotLink[i % nDepots]}.
     */
    public static void writeFromDepots(Network net, List<Coord> depotCoords, int fleetSize,
                                       int capacity, double serviceBegin, double serviceEnd, Path out) {
        if (fleetSize < 1) {
            throw new IllegalArgumentException("fleetSize must be >= 1, got " + fleetSize);
        }
        if (depotCoords == null || depotCoords.isEmpty()) {
            throw new IllegalArgumentException("need at least one depot coordinate");
        }
        if (net.getLinks().isEmpty()) {
            throw new IllegalArgumentException("cannot place a DRT fleet: network has no links");
        }
        List<Id<Link>> depotLinks = new ArrayList<>(depotCoords.size());
        for (Coord c : depotCoords) {
            depotLinks.add(NetworkUtils.getNearestLinkExactly(net, c).getId());
        }
        List<DvrpVehicleSpecification> specs = new ArrayList<>(fleetSize);
        for (int i = 0; i < fleetSize; i++) {
            Id<Link> startLink = depotLinks.get(i % depotLinks.size());
            specs.add(ImmutableDvrpVehicleSpecification.newBuilder()
                    .id(Id.create("drt_" + i, DvrpVehicle.class))
                    .startLinkId(startLink)
                    .capacity(capacity)
                    .serviceBeginTime(serviceBegin)
                    .serviceEndTime(serviceEnd)
                    .build());
        }
        new FleetWriter(Stream.of(specs.toArray(new DvrpVehicleSpecification[0]))).write(out.toString());
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl parcel-demand-2-matsim-pipeline -Dtest=DrtFleetGeneratorTest test`
Expected: PASS (existing 2 tests + 3 new).

- [ ] **Step 5: Stage (commit on go-ahead)**

```bash
git add parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/drt/DrtFleetGenerator.java \
        parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/drt/DrtFleetGeneratorTest.java
git commit -m "feat(drt): depot-based fleet spawn with snap + even split"
```

---

### Task 3: Wire depot dispatch into LausitzDrtPreprocessor

**Files:**
- Modify: `parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/drt/LausitzDrtPreprocessor.java`
- Modify (call sites): `parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/drt/LausitzDrtPreprocessorTest.java`, `parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/drt/PrepareLausitzDrtInputsTest.java` (and any other caller of `LausitzDrtPreprocessor.run(...)` — find them first, see Step 1).

**Interfaces:**
- Consumes: `DrtDepotReader.readCoords` (Task 1), `DrtFleetGenerator.writeFromDepots` (Task 2), `HAGRIDSimulationConfig.getLmdDepotCsv()`.
- Produces: a new `String depotCsv` parameter on both `run(...)` overloads, inserted **immediately after `serviceAreaShp`**. `run(cfg)` passes `cfg.getLmdDepotCsv()`.

- [ ] **Step 1: Find all callers of the run overloads**

Run a search for `LausitzDrtPreprocessor.run(` across `src/test` and `src/main`. Every call site must gain the new `depotCsv` argument after the `serviceAreaShp` argument. The production call site is `run(cfg)` itself.

- [ ] **Step 2: Write/adjust the failing test** (in `LausitzDrtPreprocessorTest`)

Add a test asserting that the generated fleet anchors vehicles on the depot-nearest links. Mirror the existing fixture in that test file (it already builds a tiny network + service-area shp + depot csv). Concretely, after calling `run(...)` with a depot CSV containing one depot near a known in-area link, assert the fleet XML's `start_link` equals that link id:

```java
    @Test
    @DisplayName("fleet vehicles are anchored on the depot-nearest drt links")
    void fleetAnchoredAtDepots(@TempDir Path tmp) throws Exception {
        // reuse this test's existing fixture builders for network + service-area shp + plans.
        // Write a depot CSV with a single depot at the coordinate of a known in-area link's midpoint.
        Path depotCsv = tmp.resolve("depots.csv");
        Files.writeString(depotCsv, "provider;x;y\ndhl;<X>;<Y>\n"); // <X>,<Y> = coord inside the fixture service area
        Path fleetOut = tmp.resolve("fleet.xml");
        LausitzDrtPreprocessor.run(rawNet, rawPlans, serviceAreaShp, depotCsv.toString(),
                drtNetOut, plansOut, fleetOut.toString(), /*fleetSize*/ 3, 8, 0.0, 86400.0);
        String xml = Files.readString(fleetOut);
        assertThat(xml).contains("<vehicle ");
        // all 3 vehicles share the single depot's snapped link
        assertThat(xml.split("start_link=", -1).length - 1).isEqualTo(3);
    }
```

Note: use the EXISTING fixture variable names from `LausitzDrtPreprocessorTest` for `rawNet/rawPlans/serviceAreaShp/drtNetOut/plansOut`. Pick `<X>,<Y>` inside that fixture's service-area polygon.

- [ ] **Step 3: Run test to verify it fails**

Run: `mvn -pl parcel-demand-2-matsim-pipeline -Dtest=LausitzDrtPreprocessorTest test`
Expected: FAIL — `run(...)` has no `depotCsv` parameter (compile error).

- [ ] **Step 4: Implement — add `depotCsv` param + switch to depot spawn**

In the 10-arg `run(...)`, change the signature to insert `String depotCsv` after `serviceAreaShp`:

```java
    public static void run(String rawNetwork, String rawPlans, String serviceAreaShp, String depotCsv,
                           String drtNetworkOut, String plansOut, String fleetOut,
                           int fleetSize, int capacity, double serviceBegin, double serviceEnd) {
```

Replace the final fleet-write line (currently `DrtFleetGenerator.write(drtSubNet, fleetSize, capacity, serviceBegin, serviceEnd, Path.of(fleetOut));`) with:

```java
        java.util.List<org.matsim.api.core.v01.Coord> depots =
                DrtDepotReader.readCoords(Path.of(depotCsv));
        DrtFleetGenerator.writeFromDepots(drtSubNet, depots, fleetSize, capacity,
                serviceBegin, serviceEnd, Path.of(fleetOut));
```

In the 14-arg overload, add `String depotCsv` after `serviceAreaShp` and forward it:

```java
        run(rawNetwork, rawPlans, serviceAreaShp, depotCsv, drtNetworkOut, plansOut, fleetOut,
                fleetSize, capacity, serviceBegin, serviceEnd);
```

In `run(cfg)`, pass the depot CSV after the service-area shapefile:

```java
                cfg.getDrtServiceAreaShapefile(),
                cfg.getLmdDepotCsv(),
                cfg.getDrtNetworkClipped(),
```

- [ ] **Step 5: Update the other call sites**

Add the `depotCsv` argument (after `serviceAreaShp`) to every call found in Step 1 (`PrepareLausitzDrtInputsTest` and any others). For tests that don't care about depots, point them at the real `hagrid-input/lausitz/hubs/lmd-depots.csv` or a small inline depot CSV inside the service area.

- [ ] **Step 6: Run tests to verify they pass**

Run: `mvn -pl parcel-demand-2-matsim-pipeline -Dtest=LausitzDrtPreprocessorTest,PrepareLausitzDrtInputsTest test`
Expected: PASS.

- [ ] **Step 7: Stage (commit on go-ahead)**

```bash
git add parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/drt/LausitzDrtPreprocessor.java \
        parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/drt/LausitzDrtPreprocessorTest.java \
        parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/drt/PrepareLausitzDrtInputsTest.java
git commit -m "feat(drt): dispatch DRT fleet from shared LMD depots"
```

---

### Task 4: Demand-based rebalancing + square-grid zones (config)

**Files:**
- Modify: `parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/drt/DrtConfigComposer.java`
- Test: `parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/drt/DrtConfigComposerTest.java`

**Interfaces:**
- Produces: after `composeConfig(...)`, the DRT mode carries a `RebalancingParams` (MinCostFlow, `EstimatedDemand` target from `PreviousIterationDemand`) and a `DrtZoneSystemParams` with a `SquareGridZoneSystemParams` (cell `ZONE_CELL_SIZE_M`). No new method signatures change.

- [ ] **Step 1: Verify the exact rebalancing API names**

Confirm against the dependency (the earlier extraction reported these; verify constant **casing** before writing, since MATSim enums vary):
- `org.matsim.contrib.drt.optimizer.rebalancing.RebalancingParams` — public field `interval` (int); attach via `drt.addParameterSet(...)`; read back via `drt.getRebalancingParams()` → `Optional<RebalancingParams>`.
- `org.matsim.contrib.drt.optimizer.rebalancing.mincostflow.MinCostFlowRebalancingStrategyParams` — fields `targetAlpha`, `targetBeta`, `rebalancingTargetCalculatorType`, `zonalDemandEstimatorType`, `demandEstimationPeriod`; enum `RebalancingTargetCalculatorType.EstimatedDemand`; enum `ZonalDemandEstimatorType.PreviousIterationDemand`.
- `org.matsim.contrib.drt.analysis.zonal.DrtZoneSystemParams` — field `targetLinkSelection` (enum `TargetLinkSelection.MostCentral`); attach via `drt.addParameterSet(...)`.
- `org.matsim.contrib.common.zones.systems.grid.square.SquareGridZoneSystemParams` — field `cellSize` (double); attach via `drtZoneParams.addParameterSet(...)`.

If a name/casing differs, adapt the code below to the verified name. (Quick check: `javap -p -classpath <drt jar> org.matsim.contrib.drt.optimizer.rebalancing.mincostflow.MinCostFlowRebalancingStrategyParams` etc.)

- [ ] **Step 2: Write the failing test** (append to `DrtConfigComposerTest`)

```java
    @Test
    @DisplayName("configures demand-based MinCostFlow rebalancing with a square-grid zone system")
    void rebalancingAndZones() {
        Config config = ConfigUtils.createConfig();
        DrtConfigComposer.composeConfig(config, "a.shp", "f.xml");
        DrtConfigGroup drt = MultiModeDrtConfigGroup.get(config).getModalElements().iterator().next();

        var rebal = drt.getRebalancingParams();
        assertThat(rebal).isPresent();
        assertThat(rebal.get().interval).isEqualTo(1800);

        var strategy = rebal.get().getRebalancingStrategyParams();
        assertThat(strategy)
                .isInstanceOf(org.matsim.contrib.drt.optimizer.rebalancing.mincostflow.MinCostFlowRebalancingStrategyParams.class);
        var mcf = (org.matsim.contrib.drt.optimizer.rebalancing.mincostflow.MinCostFlowRebalancingStrategyParams) strategy;
        assertThat(mcf.rebalancingTargetCalculatorType)
                .isEqualTo(org.matsim.contrib.drt.optimizer.rebalancing.mincostflow.MinCostFlowRebalancingStrategyParams.RebalancingTargetCalculatorType.EstimatedDemand);
        assertThat(mcf.zonalDemandEstimatorType)
                .isEqualTo(org.matsim.contrib.drt.optimizer.rebalancing.mincostflow.MinCostFlowRebalancingStrategyParams.ZonalDemandEstimatorType.PreviousIterationDemand);

        var zones = drt.getZoneSystemParams();
        assertThat(zones).isPresent();
    }
```

Note: confirm the zone-system getter name (`drt.getZoneSystemParams()` vs querying parameter sets) in Step 1; adjust the last assertion to the verified getter.

- [ ] **Step 3: Run test to verify it fails**

Run: `mvn -pl parcel-demand-2-matsim-pipeline -Dtest=DrtConfigComposerTest test`
Expected: FAIL — `getRebalancingParams()` empty / `getZoneSystemParams()` empty.

- [ ] **Step 4: Implement** — add constants + config in `composeConfig`

Add imports:
```java
import org.matsim.contrib.drt.analysis.zonal.DrtZoneSystemParams;
import org.matsim.contrib.common.zones.systems.grid.square.SquareGridZoneSystemParams;
import org.matsim.contrib.drt.optimizer.rebalancing.RebalancingParams;
import org.matsim.contrib.drt.optimizer.rebalancing.mincostflow.MinCostFlowRebalancingStrategyParams;
```

Add constants near the existing ones:
```java
    // Depot-dispatching rebalancing (PoC defaults).
    private static final double ZONE_CELL_SIZE_M = 2000.0;
    private static final int REBALANCE_INTERVAL_S = 1800;
    private static final int DEMAND_ESTIMATION_PERIOD_S = 1800;
```

Inside the `if (multi.getModalElements().isEmpty())` block, **after** `drt.setDrtInsertionSearchParams(...)` and **before** `multi.addParameterSet(drt);`, insert:

```java
            // Rebalancing zones: square grid over the service area.
            DrtZoneSystemParams zoneParams = new DrtZoneSystemParams();
            zoneParams.targetLinkSelection = DrtZoneSystemParams.TargetLinkSelection.MostCentral;
            SquareGridZoneSystemParams gridParams = new SquareGridZoneSystemParams();
            gridParams.cellSize = ZONE_CELL_SIZE_M;
            zoneParams.addParameterSet(gridParams);
            drt.addParameterSet(zoneParams);

            // Demand-based MinCostFlow rebalancing (idle vehicles flow toward demand;
            // balanced zones keep their vehicles -> "stay put" emerges naturally).
            RebalancingParams rebalancing = new RebalancingParams();
            rebalancing.interval = REBALANCE_INTERVAL_S;
            MinCostFlowRebalancingStrategyParams mcf = new MinCostFlowRebalancingStrategyParams();
            mcf.rebalancingTargetCalculatorType =
                    MinCostFlowRebalancingStrategyParams.RebalancingTargetCalculatorType.EstimatedDemand;
            mcf.zonalDemandEstimatorType =
                    MinCostFlowRebalancingStrategyParams.ZonalDemandEstimatorType.PreviousIterationDemand;
            mcf.demandEstimationPeriod = DEMAND_ESTIMATION_PERIOD_S;
            mcf.targetAlpha = 0.5;
            mcf.targetBeta = 0.5;
            rebalancing.addParameterSet(mcf);
            drt.addParameterSet(rebalancing);
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -pl parcel-demand-2-matsim-pipeline -Dtest=DrtConfigComposerTest test`
Expected: PASS (existing 5 tests + 1 new).

- [ ] **Step 6: Stage (commit on go-ahead)**

```bash
git add parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/drt/DrtConfigComposer.java \
        parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/drt/DrtConfigComposerTest.java
git commit -m "feat(drt): demand-based MinCostFlow rebalancing + square-grid zones"
```

> **Checkpoint:** After Task 4 the PoC is fully runnable — depot spawn + demand rebalancing. Run the DRT baseline e2e smoke (`mvn -pl parcel-demand-2-matsim-pipeline -Dtest=DrtBaselineEndToEndTest test`) to confirm rebalancing wiring boots and vehicles start at depots. Return-to-depot (Tasks 5/6) layers on top.

---

### Task 5: Return-to-depot — custom modal RebalancingTargetCalculator (HIGHER RISK)

This task is the version-sensitive one (custom modal binding + injection helpers). It is isolated and optional: if the binding proves too fiddly during execution, stop, keep Tasks 1–4, and rely on Task 6 (KPI-accounted return) — that is the agreed fallback. Validate this task LAST and report honestly whether the simulated return works.

**Files:**
- Create: `parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/drt/ReturnToDepotTargetCalculator.java`
- Create: `parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/drt/ReturnToDepotRebalancingModule.java`
- Modify: `parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/drt/DrtConfigComposer.java` (extend `installModules`)
- Modify: `parcel-demand-2-matsim-pipeline/src/main/java/hagrid/simulation/SimulationRunnerUtils.java` (pass depot coords + timing into `installModules`)
- Test: `parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/drt/ReturnToDepotTargetCalculatorTest.java`

**Interfaces:**
- Consumes: `RebalancingTargetCalculator` (delegate, demand-based), `Set<Zone>` (depot zones), `double returnStart`, `double targetPerDepotZone`.
- `RebalancingTargetCalculator.calculate(double timeStep, Map<Zone, List<DvrpVehicle>> vehiclesByZone)` returns `ToDoubleFunction<Zone>`.

- [ ] **Step 1: Verify injection + zone API**

Confirm against the dependency:
- `RebalancingTargetCalculator` FQN `org.matsim.contrib.drt.optimizer.rebalancing.targetcalculator.RebalancingTargetCalculator`; method `ToDoubleFunction<Zone> calculate(double timeStep, Map<Zone, List<DvrpVehicle>> vehiclesByZone)`.
- `DemandEstimatorAsTargetCalculator(ZonalDemandEstimator, double demandEstimationPeriod)` constructor (the daytime delegate).
- `ZonalDemandEstimator` FQN `org.matsim.contrib.drt.optimizer.rebalancing.demandestimator.ZonalDemandEstimator` (modally bound when `PreviousIterationDemand` is selected — confirm it is injectable per mode).
- `org.matsim.contrib.common.zones.ZoneSystem` — the method mapping a `Coord` to its `Zone` (likely `Optional<Zone> getZoneForCoord(Coord)`; if absent, use `ZoneSystemUtils` or iterate zones and test geometry). Record the exact method.
- `AbstractDvrpModeModule` modal injection helpers: `modalProvider(getter -> ...)` + `getter.getModal(Class)` and `bindModal(Class).toProvider(...).asEagerSingleton()`. Confirm signatures.
- Override ordering: confirm that an `addOverridingModule(...)` installed AFTER `MultiModeDrtModule` rebinds `RebalancingTargetCalculator` for the mode (it should, via Guice override semantics).

- [ ] **Step 2: Write the failing unit test** (pure switch logic — no MATSim injection)

```java
package hagrid.integrated.drt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.matsim.contrib.common.zones.Zone;
import org.matsim.contrib.drt.optimizer.rebalancing.targetcalculator.RebalancingTargetCalculator;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToDoubleFunction;

import static org.assertj.core.api.Assertions.*;

@DisplayName("ReturnToDepotTargetCalculator")
class ReturnToDepotTargetCalculatorTest {

    // two stub zones; identity-based equality is fine for Set membership
    private final Zone depotZone = org.mockito.Mockito.mock(Zone.class); // if no mockito: see note
    private final Zone otherZone = org.mockito.Mockito.mock(Zone.class);

    @Test
    @DisplayName("delegates to the daytime calculator before the return window")
    void daytimeDelegates() {
        ToDoubleFunction<Zone> sentinel = z -> 42.0;
        RebalancingTargetCalculator daytime = (t, vbz) -> sentinel;
        var calc = new ReturnToDepotTargetCalculator(daytime, Set.of(depotZone), 80000.0, 100.0);
        ToDoubleFunction<Zone> f = calc.calculate(70000.0, Map.of());
        assertThat(f.applyAsDouble(otherZone)).isEqualTo(42.0);
    }

    @Test
    @DisplayName("targets depot zones in the return window")
    void returnWindowTargetsDepots() {
        RebalancingTargetCalculator daytime = (t, vbz) -> (z -> 42.0);
        var calc = new ReturnToDepotTargetCalculator(daytime, Set.of(depotZone), 80000.0, 100.0);
        ToDoubleFunction<Zone> f = calc.calculate(85000.0, Map.of());
        assertThat(f.applyAsDouble(depotZone)).isEqualTo(100.0);
        assertThat(f.applyAsDouble(otherZone)).isEqualTo(0.0);
    }
}
```

Note: this project has no Mockito. Replace the two `Mockito.mock(Zone.class)` lines with a tiny stub — implement `Zone` as an anonymous class, or define a `private static final class StubZone implements Zone { ... }` returning dummy values for the interface methods (check `Zone`'s methods in Step 1 and stub them). Identity equality is all the test needs.

- [ ] **Step 3: Run test to verify it fails**

Run: `mvn -pl parcel-demand-2-matsim-pipeline -Dtest=ReturnToDepotTargetCalculatorTest test`
Expected: FAIL — class does not exist.

- [ ] **Step 4: Implement the calculator**

```java
package hagrid.integrated.drt;

import org.matsim.contrib.common.zones.Zone;
import org.matsim.contrib.drt.optimizer.rebalancing.targetcalculator.RebalancingTargetCalculator;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToDoubleFunction;

/**
 * Time-switched rebalancing target: before {@code returnStart}, delegates to a
 * demand-based calculator (idle vehicles flow toward demand). From
 * {@code returnStart} onward, targets the depot zones so idle vehicles drive
 * home over the network (end-of-day return). Vehicles still serving at sim end
 * stop at their last stop and are handled by the KPI fallback (Task 6).
 */
public final class ReturnToDepotTargetCalculator implements RebalancingTargetCalculator {

    private final RebalancingTargetCalculator daytime;
    private final Set<Zone> depotZones;
    private final double returnStart;
    private final double targetPerDepotZone;

    public ReturnToDepotTargetCalculator(RebalancingTargetCalculator daytime, Set<Zone> depotZones,
                                         double returnStart, double targetPerDepotZone) {
        this.daytime = daytime;
        this.depotZones = depotZones;
        this.returnStart = returnStart;
        this.targetPerDepotZone = targetPerDepotZone;
    }

    @Override
    public ToDoubleFunction<Zone> calculate(double timeStep, Map<Zone, List<DvrpVehicle>> vehiclesByZone) {
        if (timeStep < returnStart) {
            return daytime.calculate(timeStep, vehiclesByZone);
        }
        return zone -> depotZones.contains(zone) ? targetPerDepotZone : 0.0;
    }
}
```

- [ ] **Step 5: Run unit test to verify it passes**

Run: `mvn -pl parcel-demand-2-matsim-pipeline -Dtest=ReturnToDepotTargetCalculatorTest test`
Expected: PASS.

- [ ] **Step 6: Implement the modal binding module**

```java
package hagrid.integrated.drt;

import org.matsim.api.core.v01.Coord;
import org.matsim.contrib.common.zones.Zone;
import org.matsim.contrib.common.zones.ZoneSystem;
import org.matsim.contrib.drt.optimizer.rebalancing.demandestimator.ZonalDemandEstimator;
import org.matsim.contrib.drt.optimizer.rebalancing.targetcalculator.DemandEstimatorAsTargetCalculator;
import org.matsim.contrib.drt.optimizer.rebalancing.targetcalculator.RebalancingTargetCalculator;
import org.matsim.contrib.dvrp.run.AbstractDvrpModeModule;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Rebinds the DRT mode's {@link RebalancingTargetCalculator} to a
 * {@link ReturnToDepotTargetCalculator}: demand-based by day, depot-targeting in
 * the final window. Installed AFTER MultiModeDrtModule so it overrides the
 * default target-calculator binding.
 */
public final class ReturnToDepotRebalancingModule extends AbstractDvrpModeModule {

    private final List<Coord> depotCoords;
    private final double returnStart;
    private final double targetPerDepotZone;
    private final double demandEstimationPeriod;

    public ReturnToDepotRebalancingModule(String mode, List<Coord> depotCoords,
                                          double returnStart, double targetPerDepotZone,
                                          double demandEstimationPeriod) {
        super(mode);
        this.depotCoords = depotCoords;
        this.returnStart = returnStart;
        this.targetPerDepotZone = targetPerDepotZone;
        this.demandEstimationPeriod = demandEstimationPeriod;
    }

    @Override
    public void install() {
        bindModal(RebalancingTargetCalculator.class).toProvider(modalProvider(getter -> {
            ZoneSystem zones = getter.getModal(ZoneSystem.class);
            ZonalDemandEstimator estimator = getter.getModal(ZonalDemandEstimator.class);
            Set<Zone> depotZones = new LinkedHashSet<>();
            for (Coord c : depotCoords) {
                zones.getZoneForCoord(c).ifPresent(depotZones::add); // adapt to verified API
            }
            RebalancingTargetCalculator daytime =
                    new DemandEstimatorAsTargetCalculator(estimator, demandEstimationPeriod);
            return new ReturnToDepotTargetCalculator(daytime, depotZones, returnStart, targetPerDepotZone);
        })).asEagerSingleton();
    }
}
```

Adapt `zones.getZoneForCoord(c)` and the `getModal(...)` calls to the exact API confirmed in Step 1.

- [ ] **Step 7: Extend `installModules` to install the return module**

In `DrtConfigComposer`, add an overload that wires the return module (keep the existing no-arg overload for callers/tests that don't need return-to-depot):

```java
    public static void installModules(Controler controler, java.util.List<org.matsim.api.core.v01.Coord> depotCoords,
                                      double returnStart, double targetPerDepotZone, double demandEstimationPeriod) {
        installModules(controler);
        controler.addOverridingModule(new ReturnToDepotRebalancingModule(
                org.matsim.api.core.v01.TransportMode.drt, depotCoords, returnStart, targetPerDepotZone, demandEstimationPeriod));
    }
```

- [ ] **Step 8: Wire it in SimulationRunnerUtils**

In `SimulationRunnerUtils.runSimulation(...)`, the DRT branch currently calls `DrtConfigComposer.installModules(controler);`. Replace with the return-aware overload, computing the args from `cfg`:

```java
        java.util.List<org.matsim.api.core.v01.Coord> depots =
                DrtDepotReader.readCoords(java.nio.file.Path.of(cfg.getLmdDepotCsv()));
        double serviceEnd = 86400.0;          // matches LausitzDrtPreprocessor default
        double returnWindow = 5400.0;          // last 90 min target depots
        DrtConfigComposer.installModules(controler, depots, serviceEnd - returnWindow,
                cfg.getFleetSize(), 1800.0);
```

(Read the surrounding method first; place this where `installModules` was called. If `serviceEnd` is later promoted to config, source it from there.)

- [ ] **Step 9: Run the DRT e2e smoke to verify the wiring boots**

Run: `mvn -pl parcel-demand-2-matsim-pipeline -Dtest=DrtBaselineEndToEndTest test`
Expected: PASS — run completes; check the log shows rebalancing relocations near the return window (vehicles drive toward depot links).

If this step fails on the modal binding after a reasonable attempt: revert Task 5's `SimulationRunnerUtils` change to the plain `installModules(controler)`, keep Tasks 1–4, and rely on Task 6. Report the exact binding error.

- [ ] **Step 10: Stage (commit on go-ahead)**

```bash
git add parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/drt/ReturnToDepotTargetCalculator.java \
        parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/drt/ReturnToDepotRebalancingModule.java \
        parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/drt/DrtConfigComposer.java \
        parcel-demand-2-matsim-pipeline/src/main/java/hagrid/simulation/SimulationRunnerUtils.java \
        parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/drt/ReturnToDepotTargetCalculatorTest.java
git commit -m "feat(drt): simulated return-to-depot via time-switched rebalancing target"
```

---

### Task 6: KPI fallback — account the residual return distance (Python, post-run)

Runs after the DRT PoC rerun. Covers vehicles that are still at a last stop at sim end (not returned by rebalancing): add each such vehicle's final-position → home-depot distance to vehicle-km.

**Files:**
- Modify: the DRT dashboard/analysis pipeline under `parcel-demand-2-matsim-pipeline/analysis/drt-headline/` (the script that computes fleet vehicle-km — reuse `build_vehicle_tours.py`'s event-parsing or read `output_vehicleDistanceStats_drt.csv` + last position per vehicle).

- [ ] **Step 1:** From `output_events.xml.gz`, get each vehicle's last `entered link` position at/after `serviceEnd`. Map each vehicle to its home depot link (vehicle `i` → `depotLink[i % nDepots]`, same rule as Task 2; or snap the vehicle's start link to the nearest depot coord). For vehicles whose last position ≠ home depot link, compute the network shortest-path distance (or euclidean as a documented approximation) final→depot.
- [ ] **Step 2:** Sum these residual return distances and report them as a separate "return-to-depot (accounted)" line in the DRT dashboard KPIs, added to total vehicle-km. **Log** the count of vehicles accounted this way and the share of total km — no silent inclusion.
- [ ] **Step 3:** Sanity-check against `output_vehicleDistanceStats_drt.csv` (total/empty km) so the accounted return is a small, explainable delta.
- [ ] **Step 4: Stage (commit on go-ahead)** the analysis script changes.

---

## Self-Review

**Spec coverage:**
- Depot source / reuse lmd-depots.csv → Task 1 + Task 3 (`getLmdDepotCsv()`). ✓
- Snap to DRT sub-network → Task 2 (`getNearestLinkExactly` on `drtSubNet`). ✓
- Even split → Task 2 (`i % nDepots`). ✓
- Rebalancing toward demand + stay-put → Task 4 (`EstimatedDemand`/`PreviousIterationDemand`, MinCostFlow). ✓
- Square-grid zone system (~2 km) → Task 4 (`SquareGridZoneSystemParams.cellSize = 2000`). ✓
- Return-to-depot (rebalancing) + KPI fallback → Task 5 + Task 6. ✓
- Defaulted params (cell size 2000, interval 1800, return window 5400) → Task 4 constants + Task 5 wiring. ✓
- Forced relocation deferred → not in plan (spec §10). ✓

**Placeholder scan:** The only `<...>` tokens are fixture coordinates in Task 3's test (the executor fills them from the existing `LausitzDrtPreprocessorTest` fixture) and the Step-1 API-verification notes (legitimate verify-then-implement, with concrete intended code given). No "TBD"/"add error handling"/"similar to Task N".

**Type consistency:** `writeFromDepots(Network, List<Coord>, int, int, double, double, Path)` is defined in Task 2 and consumed in Task 3. `readCoords(Path) → List<Coord>` defined in Task 1, consumed in Tasks 3 & 5. `RebalancingTargetCalculator.calculate(double, Map<Zone,List<DvrpVehicle>>) → ToDoubleFunction<Zone>` consistent across Tasks 5 definition and test. `installModules` overloads consistent across Task 5 Steps 7–8.

**Known risk:** Task 5 (custom modal binding + `ZoneSystem.getZoneForCoord` + override ordering) is version-sensitive; it is isolated, validated last, and has the Task 6 fallback. This is by design, not a gap.
