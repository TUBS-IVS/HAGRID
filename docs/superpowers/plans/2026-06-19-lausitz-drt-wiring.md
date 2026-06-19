# Lausitz DRT Wiring & Native DRT Composition — Implementation Plan (Phase 1b)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Compose and run the `DRT_BASELINE` scenario end-to-end in HAGRID for the Lausitz / Hoyerswerda study area — a real full-DVRP DRT fleet (DRT-only, no PT intermodality) built from the native Lausitz DRT parameters, running on a network clipped to the DRT service zone with a clipped passenger population, alongside HAGRID's existing freight — verified by a self-contained MATSim integration smoke test.

**Architecture:** HAGRID stays the orchestrator. We do **not** subclass matsim-lausitz's `MATSimApplication` (HAGRID uses its own `HAGRIDScenarioBuilder` + manual `Controler` assembly). Instead we **reuse the DRT/DVRP contrib APIs directly** and mirror the native Lausitz DRT *parameter values* (sourced verbatim from `LausitzDrtScenario` / `DrtAndIntermodalityOptions`), with two deliberate divergences agreed with the user: `simulationType = fullSimulation` (real dispatched fleet, not `estimateAndTeleport`) and **no PT intermodality** (DRT-only). All new code lives under `hagrid.integrated` (loaded only for `DRT_*` scenarios); non-DRT runs are byte-for-byte unchanged.

**Tech Stack:** Java 21, MATSim `2025.0-PR3552`, `org.matsim.contrib:drt`, `org.matsim.contrib:dvrp` (both already declared in `parcel-demand-2-matsim-pipeline/pom.xml`), GeoTools 31.1 + JTS 1.16.1, JUnit 5.11.4 + AssertJ 3.27.7, `MatsimTestUtils` for integration tests.

## Global Constraints

- **MATSim version:** `2025.0-PR3552` (root pom `<matsim.version>`). Do not change.
- **Backward compatibility (non-negotiable):** `StudyArea.HANNOVER` is the default everywhere; every existing non-DRT scenario (`BASECASE`, `WHITE_LABEL`, `UCC`, batch variants) must run exactly as today. New behaviour activates **only** when the scenario is a `DRT_*` value.
- **DRT scenarios require `StudyArea.LAUSITZ_HOYERSWERDA`** — validated at runtime, never hardcoded into the `Scenario` enum.
- **Full DVRP for all DRT scenarios:** `DrtConfigGroup.simulationType = fullSimulation`. Never `estimateAndTeleport`.
- **DRT-only:** no SwissRailRaptor intermodal access/egress, no transit-schedule tagging, no DRT↔PT fare integration. Internal Hoyerswerda DRT trips only.
- **100 % passenger sample is non-negotiable** for real runs (subsampling distorts LMD tour geometry). Compute is controlled only via study-area size. The service area is a **parameterised shapefile input**, never hardcoded.
- **CRS = `EPSG:25832`** on both HAGRID and matsim-lausitz. Assume aligned; assert it where a CRS is read.
- **Package:** all new production code under `parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/` (sub-package `drt/` for DRT-composition classes). Tests mirror the package under `src/test/java/`.
- **Test conventions:** JUnit 5 (`@Test`, `@Nested`, `@DisplayName`), AssertJ static imports (`import static org.assertj.core.api.Assertions.*;`), `@TempDir` for filesystem tests, `@RegisterExtension public MatsimTestUtils utils = new MatsimTestUtils();` for MATSim integration tests. Builder-style config validation throws `IllegalArgumentException`.
- **Module test command:** `mvn -pl parcel-demand-2-matsim-pipeline test` (single class: append `-Dtest=ClassName`).
- **TDD, DRY, YAGNI, frequent commits.** No placeholders, no stubs left behind.

---

## File Structure

**Created:**
- `hagrid/integrated/drt/DrtNetworkPreparer.java` — clip Lausitz network to the service-area geometry, add `drt` to allowed modes, clean the sub-network.
- `hagrid/integrated/PopulationClipper.java` — stream the 100 % plans, keep persons with a relevant activity inside the service zone, write clipped plans.
- `hagrid/integrated/drt/DrtFleetGenerator.java` — generate `fleetSize` DVRP vehicles on in-zone links → DVRP vehicles XML.
- `hagrid/integrated/drt/DrtConfigComposer.java` — set up `DvrpConfigGroup` + `MultiModeDrtConfigGroup`/`DrtConfigGroup` (full DVRP, service-area, native params, DRT-only) and install the DRT modules on a `Controler`.
- Test classes mirroring each of the above, plus `DrtBaselineIntegrationTest.java` (smoke test).

**Modified:**
- `hagrid/HagridConfig.java` — add `DRT_BASELINE`, `DRT_SHAREDUSE`, `DRT_MODULAR` to the `Scenario` enum; add `Scenario.isDrt()`; add DRT↔study-area validation in `setStudyArea`.
- `hagrid/HagridPaths.java` — add Lausitz-specific input/output path getters (service-area shapefile, raw network, raw 100 % plans, clipped network, clipped plans, DRT fleet file).
- `hagrid/simulation/HAGRIDSimulationConfig.java` — carry `StudyArea`; expose `isDrtScenario()`, `getFleetSize()`, and the DRT path getters; build `HagridPaths` study-area-scoped.
- `hagrid/simulation/SimulationRunnerUtils.java` — parse `studyArea` + `fleetSize` in `parseScenario`; validate DRT↔Lausitz; install DRT modules in `runSimulation` for DRT scenarios.
- `hagrid/simulation/HAGRIDScenarioBuilder.java` — for DRT scenarios, point the config at the clipped network + clipped plans and compose the DRT config before loading the scenario.

---

## Reference: verified native DRT parameter values

Sourced verbatim from `matsim-lausitz` (`LausitzDrtScenario` / `DrtAndIntermodalityOptions.configureDrtConfig`, tag v2.0.2). Mirror these so the passenger LoS stays comparable to the official scenario; only `simulationType` and the omission of intermodality diverge.

| Parameter | Value |
|---|---|
| `operationalScheme` | `serviceAreaBased` |
| `stopDuration` | `60.0` s |
| `simulationType` | **`fullSimulation`** (diverges from native `estimateAndTeleport`) |
| `DvrpConfigGroup.networkModes` | `Set.of(TransportMode.drt)` |
| constraints `maxWaitTime` | `1200.0` s |
| constraints `maxTravelTimeAlpha` | `1.5` |
| constraints `maxTravelTimeBeta` | `1200.0` s |
| insertion search | `ExtensiveInsertionSearchParams` |
| staging activity + scoring | `DrtConfigs.adjustMultiModeDrtConfig(multiModeDrt, config.scoring(), config.routing())` |
| `subtourModeChoice` modes | append `TransportMode.drt` |

Verified contrib API (via `javap` against the resolved `drt`/`dvrp` 2025.0-PR3552 jars):
- `DrtConfigGroup` public fields: `mode`, `stopDuration`, `operationalScheme`, `drtServiceAreaShapeFile`, `vehiclesFile`, `simulationType`; methods `addOrGetDrtOptimizationConstraintsParams()`, `addDrtInsertionSearchParams(DrtInsertionSearchParams)`.
- `DrtConfigGroup.SimulationType.{fullSimulation, estimateAndTeleport}`; `DrtConfigGroup.OperationalScheme.{stopbased, door2door, serviceAreaBased}`.
- `MultiModeDrtConfigGroup.get(Config)`, `.addParameterSet(ConfigGroup)`, `.getModalElements()`.
- `DvrpConfigGroup.get(Config)`, public field `networkModes`.
- `DrtOptimizationConstraintsParams.addOrGetDefaultDrtOptimizationConstraintsSet()` → `DefaultDrtOptimizationConstraintsSet` (public fields `maxTravelTimeAlpha`, `maxTravelTimeBeta`; `maxWaitTime` on the parent set).
- `DrtConfigs.adjustMultiModeDrtConfig(MultiModeDrtConfigGroup, ScoringConfigGroup, RoutingConfigGroup)`.
- `FleetWriter(Stream<? extends DvrpVehicleSpecification>)`, `.write(String)`; `ImmutableDvrpVehicleSpecification.newBuilder().id(Id<DvrpVehicle>).startLinkId(Id<Link>).capacity(int).serviceBeginTime(double).serviceEndTime(double).build()`.
- Modules: `new DvrpModule()`, `new MultiModeDrtModule()`, `DvrpQSimComponents.activateAllModes(MultiModal<?>...)` (`MultiModeDrtConfigGroup` implements `MultiModal`).

---

## Task 1: `Scenario` enum + DRT↔StudyArea validation

**Files:**
- Modify: `parcel-demand-2-matsim-pipeline/src/main/java/hagrid/HagridConfig.java` (enum at lines 61–70; `setStudyArea` at lines 825–832)
- Test: `parcel-demand-2-matsim-pipeline/src/test/java/hagrid/HagridConfigTest.java` (existing — append)

**Interfaces:**
- Produces: `Scenario.DRT_BASELINE`, `Scenario.DRT_SHAREDUSE`, `Scenario.DRT_MODULAR`; instance method `boolean Scenario.isDrt()`. `HagridConfig.setStudyArea(StudyArea)` throws `IllegalArgumentException` when a DRT scenario is paired with a non-Lausitz study area.

- [ ] **Step 1: Write failing tests**

Append to `HagridConfigTest.java` (inside the class, matching its existing style):

```java
@Nested
@DisplayName("DRT scenarios")
class DrtScenarios {

    @Test
    @DisplayName("isDrt() true only for DRT_* values")
    void isDrtFlag() {
        assertThat(HagridConfig.Scenario.DRT_BASELINE.isDrt()).isTrue();
        assertThat(HagridConfig.Scenario.DRT_SHAREDUSE.isDrt()).isTrue();
        assertThat(HagridConfig.Scenario.DRT_MODULAR.isDrt()).isTrue();
        assertThat(HagridConfig.Scenario.BASECASE.isDrt()).isFalse();
        assertThat(HagridConfig.Scenario.UCC.isDrt()).isFalse();
    }

    @Test
    @DisplayName("DRT scenario + HANNOVER is rejected")
    void drtRequiresLausitz() {
        HagridConfig cfg = new HagridConfig();
        cfg.setScenario(HagridConfig.Scenario.DRT_BASELINE);
        assertThatThrownBy(() -> cfg.setStudyArea(StudyArea.HANNOVER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DRT")
                .hasMessageContaining("LAUSITZ_HOYERSWERDA");
    }

    @Test
    @DisplayName("DRT scenario + LAUSITZ_HOYERSWERDA is accepted")
    void drtWithLausitzOk() {
        HagridConfig cfg = new HagridConfig();
        cfg.setScenario(HagridConfig.Scenario.DRT_BASELINE);
        assertThatCode(() -> cfg.setStudyArea(StudyArea.LAUSITZ_HOYERSWERDA))
                .doesNotThrowAnyException();
        assertThat(cfg.getStudyArea()).isEqualTo(StudyArea.LAUSITZ_HOYERSWERDA);
    }

    @Test
    @DisplayName("non-DRT scenario keeps HANNOVER default behaviour")
    void nonDrtUnchanged() {
        HagridConfig cfg = new HagridConfig();
        assertThatCode(() -> cfg.setStudyArea(StudyArea.HANNOVER)).doesNotThrowAnyException();
        assertThat(cfg.getStudyArea()).isEqualTo(StudyArea.HANNOVER);
    }
}
```

> If `setScenario` does not exist with that exact name, find the existing scenario setter in `HagridConfig` (grep `setScenario`/`scenario =`) and use it; adjust the test calls accordingly.

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -pl parcel-demand-2-matsim-pipeline test -Dtest=HagridConfigTest`
Expected: FAIL — `DRT_BASELINE` symbol not found / no `isDrt()`.

- [ ] **Step 3: Extend the enum and add `isDrt()`**

In `HagridConfig.java`, replace the enum (lines 61–70) with:

```java
public enum Scenario {
    BASECASE,
    WHITE_LABEL,
    UCC,
    COLLECTION_POINTS,
    BATCHMODERATE,
    BATCHMEDIUM,
    BATCHHIGH,
    BATCHFULL,
    DRT_BASELINE,    // multi-LSP freight + native DRT (two independent fleets)
    DRT_SHAREDUSE,   // cargo hitching, 2D split capacity (Phase 1c)
    DRT_MODULAR;     // capsule swap (Phase 1d)

    /** True for the integrated passenger+freight DRT scenarios (require StudyArea.LAUSITZ_HOYERSWERDA). */
    public boolean isDrt() {
        return this == DRT_BASELINE || this == DRT_SHAREDUSE || this == DRT_MODULAR;
    }
}
```

- [ ] **Step 4: Add validation in `setStudyArea`**

In `HagridConfig.java`, modify `setStudyArea` (lines 825–832) to validate the pairing **before** mutating state:

```java
public void setStudyArea(StudyArea studyArea) {
    if (scenario != null && scenario.isDrt() && studyArea != StudyArea.LAUSITZ_HOYERSWERDA) {
        throw new IllegalArgumentException(
                "DRT scenario " + scenario + " requires StudyArea.LAUSITZ_HOYERSWERDA, got " + studyArea);
    }
    this.studyArea = studyArea;
    this.hagridPaths = new HagridPaths(studyArea);
    if (this.runId != null) {
        this.hagridPaths.initializeRun(this.runId);
    }
    deriveInputPaths();
}
```

> Use the actual field name for the current scenario (grep the class — likely `scenario`). If the scenario can be `null` at this point, the `scenario != null` guard already handles it.

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn -pl parcel-demand-2-matsim-pipeline test -Dtest=HagridConfigTest`
Expected: PASS (all original `HagridConfigTest` cases still green + 4 new).

- [ ] **Step 6: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/src/main/java/hagrid/HagridConfig.java \
        parcel-demand-2-matsim-pipeline/src/test/java/hagrid/HagridConfigTest.java
git commit -m "feat(integrated): add DRT_* scenarios + DRT<->Lausitz study-area validation"
```

---

## Task 2: Lausitz input/output path getters in `HagridPaths`

**Files:**
- Modify: `parcel-demand-2-matsim-pipeline/src/main/java/hagrid/HagridPaths.java`
- Test: `parcel-demand-2-matsim-pipeline/src/test/java/hagrid/HagridPathsTest.java` (existing — append)

**Interfaces:**
- Consumes: `StudyArea`, the existing `inputBase`/`runDir` fields and `studyArea.folder()` scoping (Task-1a foundations).
- Produces (all relative to the study-area-scoped `inputBase` = `hagrid-input/lausitz/` for Lausitz):
  - `String drtServiceAreaShapefile()` → `{inputBase}/drt/drt-service-area.shp`
  - `String lausitzNetworkRaw()` → `{inputBase}/network/lausitz-network.xml.gz`
  - `String passengerPlansRaw()` → `{inputBase}/population/lausitz-100pct.plans.xml.gz`
  - Run-scoped outputs (require `initializeRun` first), under `runDir`:
  - `String drtNetworkClipped()` → `{runDir}/{runId}_drt_network.xml.gz`
  - `String passengerPlansClipped()` → `{runDir}/{runId}_drt_population.xml.gz`
  - `String drtFleetFile()` → `{runDir}/{runId}_drt_fleet.xml.gz`

- [ ] **Step 1: Write failing tests**

Append to `HagridPathsTest.java`:

```java
@Nested
@DisplayName("Lausitz DRT paths")
class LausitzDrtPaths {

    @Test
    @DisplayName("input getters resolve under the lausitz input folder")
    void inputsScopedToLausitz(@TempDir Path tempDir) {
        HagridPaths p = new HagridPaths(tempDir, StudyArea.LAUSITZ_HOYERSWERDA);
        assertThat(p.drtServiceAreaShapefile()).contains("lausitz").endsWith("drt-service-area.shp");
        assertThat(p.lausitzNetworkRaw()).contains("lausitz").endsWith("lausitz-network.xml.gz");
        assertThat(p.passengerPlansRaw()).contains("lausitz").endsWith("lausitz-100pct.plans.xml.gz");
    }

    @Test
    @DisplayName("run-scoped DRT outputs embed the runId")
    void outputsCarryRunId(@TempDir Path tempDir) {
        HagridPaths p = new HagridPaths(tempDir, StudyArea.LAUSITZ_HOYERSWERDA);
        p.initializeRun("DRT_BASELINE_13052025");
        assertThat(p.drtNetworkClipped()).contains("DRT_BASELINE_13052025").endsWith("_drt_network.xml.gz");
        assertThat(p.passengerPlansClipped()).contains("DRT_BASELINE_13052025").endsWith("_drt_population.xml.gz");
        assertThat(p.drtFleetFile()).contains("DRT_BASELINE_13052025").endsWith("_drt_fleet.xml.gz");
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -pl parcel-demand-2-matsim-pipeline test -Dtest=HagridPathsTest`
Expected: FAIL — methods do not exist.

- [ ] **Step 3: Add the getters**

In `HagridPaths.java`, add (mirror the style of the existing input/output getters; use the existing `inputBase` and `runDir` fields and the existing run-init guard helper — grep an existing run-scoped getter such as `networkFiltered()` to see how it guards/resolves):

```java
// --- Lausitz DRT inputs (study-area-scoped under inputBase) ---

/** DRT service-area shapefile (parameterised; defines the Hoyerswerda DRT zone). */
public String drtServiceAreaShapefile() {
    return inputBase.resolve("drt").resolve("drt-service-area.shp").toString();
}

/** Raw Lausitz car network (before clipping to the DRT service area). */
public String lausitzNetworkRaw() {
    return inputBase.resolve("network").resolve("lausitz-network.xml.gz").toString();
}

/** Raw 100 % matsim-lausitz passenger plans (before clipping to the service area). */
public String passengerPlansRaw() {
    return inputBase.resolve("population").resolve("lausitz-100pct.plans.xml.gz").toString();
}

// --- Lausitz DRT run-scoped outputs (require initializeRun) ---

/** Network clipped to the DRT service area with drt added as an allowed mode. */
public String drtNetworkClipped() {
    return requireRun().resolve(runId + "_drt_network.xml.gz").toString();
}

/** Passenger plans clipped to the DRT service area. */
public String passengerPlansClipped() {
    return requireRun().resolve(runId + "_drt_population.xml.gz").toString();
}

/** Generated DVRP fleet vehicles file for the DRT fleet. */
public String drtFleetFile() {
    return requireRun().resolve(runId + "_drt_fleet.xml.gz").toString();
}
```

> `requireRun()` stands for whatever the class already uses to assert `initializeRun` was called and return `runDir` (grep the existing run-scoped getters and reuse that exact helper / field, e.g. `runDir`). If run-scoped getters simply use `runDir` directly with no helper, do the same.

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -pl parcel-demand-2-matsim-pipeline test -Dtest=HagridPathsTest`
Expected: PASS (existing 28 + 2 new).

- [ ] **Step 5: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/src/main/java/hagrid/HagridPaths.java \
        parcel-demand-2-matsim-pipeline/src/test/java/hagrid/HagridPathsTest.java
git commit -m "feat(paths): add Lausitz DRT input/output path getters"
```

---

## Task 3: `DrtNetworkPreparer` — clip network to the service area + add `drt` mode

**Files:**
- Create: `parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/drt/DrtNetworkPreparer.java`
- Test: `parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/drt/DrtNetworkPreparerTest.java`

**Interfaces:**
- Consumes: a `Network` (in-memory) and a JTS `Geometry` (service-area polygon, EPSG:25832). MATSim `org.matsim.core.network.algorithms.MultimodalNetworkCleaner`, `org.matsim.api.core.v01.network.*`, `org.locationtech.jts.geom.Geometry`.
- Produces: `static Network prepare(Network full, Geometry serviceArea)` → a new network containing only links whose **both endpoints** lie inside `serviceArea`, with `drt` added to the allowed modes of every retained car link, then cleaned for the `drt` mode. Pure in-memory (no I/O) so it is trivially testable.

- [ ] **Step 1: Write the failing test**

```java
package hagrid.integrated.drt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Coordinate;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.*;
import org.matsim.core.network.NetworkUtils;

import java.util.Set;

import static org.assertj.core.api.Assertions.*;

@DisplayName("DrtNetworkPreparer")
class DrtNetworkPreparerTest {

    /** Square service area covering (0,0)-(1000,1000). */
    private Geometry square() {
        GeometryFactory gf = new GeometryFactory();
        return gf.createPolygon(new Coordinate[]{
                new Coordinate(0, 0), new Coordinate(1000, 0),
                new Coordinate(1000, 1000), new Coordinate(0, 1000), new Coordinate(0, 0)
        });
    }

    private Network twoLinkNetwork() {
        Network n = NetworkUtils.createNetwork();
        NetworkFactory f = n.getFactory();
        Node a = f.createNode(Id.createNodeId("a"), new Coord(100, 100));   // inside
        Node b = f.createNode(Id.createNodeId("b"), new Coord(900, 900));   // inside
        Node c = f.createNode(Id.createNodeId("c"), new Coord(5000, 5000)); // outside
        n.addNode(a); n.addNode(b); n.addNode(c);
        Link inside = f.createLink(Id.createLinkId("in"), a, b);
        inside.setAllowedModes(Set.of("car"));
        Link leaving = f.createLink(Id.createLinkId("out"), b, c);
        leaving.setAllowedModes(Set.of("car"));
        n.addLink(inside); n.addLink(leaving);
        return n;
    }

    @Test
    @DisplayName("keeps only links fully inside the service area")
    void clipsToArea() {
        Network result = DrtNetworkPreparer.prepare(twoLinkNetwork(), square());
        assertThat(result.getLinks()).containsKey(Id.createLinkId("in"));
        assertThat(result.getLinks()).doesNotContainKey(Id.createLinkId("out"));
    }

    @Test
    @DisplayName("adds drt to allowed modes of retained car links")
    void addsDrtMode() {
        Network result = DrtNetworkPreparer.prepare(twoLinkNetwork(), square());
        Link in = result.getLinks().get(Id.createLinkId("in"));
        assertThat(in.getAllowedModes()).contains("car", "drt");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -pl parcel-demand-2-matsim-pipeline test -Dtest=DrtNetworkPreparerTest`
Expected: FAIL — `DrtNetworkPreparer` does not exist.

- [ ] **Step 3: Implement `DrtNetworkPreparer`**

```java
package hagrid.integrated.drt;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.*;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.algorithms.MultimodalNetworkCleaner;

import java.util.HashSet;
import java.util.Set;

/**
 * Prepares a DRT sub-network from a full network: keeps links whose both
 * endpoints lie inside the DRT service area, adds {@link TransportMode#drt} to
 * their allowed modes, and cleans the result so the drt sub-network stays
 * connected. Pure in-memory; callers handle file I/O.
 */
public final class DrtNetworkPreparer {

    private static final GeometryFactory GF = new GeometryFactory();

    private DrtNetworkPreparer() {}

    public static Network prepare(Network full, Geometry serviceArea) {
        Network out = NetworkUtils.createNetwork();
        NetworkFactory f = out.getFactory();

        for (Link link : full.getLinks().values()) {
            if (!contains(serviceArea, link.getFromNode().getCoord())
                    || !contains(serviceArea, link.getToNode().getCoord())) {
                continue;
            }
            Node from = copyNode(out, f, link.getFromNode());
            Node to = copyNode(out, f, link.getToNode());
            Link copy = f.createLink(link.getId(), from, to);
            copy.setLength(link.getLength());
            copy.setFreespeed(link.getFreespeed());
            copy.setCapacity(link.getCapacity());
            copy.setNumberOfLanes(link.getNumberOfLanes());
            Set<String> modes = new HashSet<>(link.getAllowedModes());
            modes.add(TransportMode.drt);
            copy.setAllowedModes(modes);
            NetworkUtils.setType(copy, NetworkUtils.getType(link));
            out.addLink(copy);
        }

        // Keep the drt sub-network strongly connected.
        new MultimodalNetworkCleaner(out).run(Set.of(TransportMode.drt));
        return out;
    }

    private static Node copyNode(Network out, NetworkFactory f, Node src) {
        Node existing = out.getNodes().get(src.getId());
        if (existing != null) {
            return existing;
        }
        Node n = f.createNode(src.getId(), src.getCoord());
        out.addNode(n);
        return n;
    }

    private static boolean contains(Geometry area, Coord c) {
        return area.contains(GF.createPoint(new Coordinate(c.getX(), c.getY())));
    }
}
```

> `MultimodalNetworkCleaner.run(Set<String>)` is the long-stable signature; if the resolved contrib differs, the compile error in Step 4 will name the correct one — adjust to the available overload (e.g. `run(Set.of(TransportMode.drt))`).

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -pl parcel-demand-2-matsim-pipeline test -Dtest=DrtNetworkPreparerTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/drt/DrtNetworkPreparer.java \
        parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/drt/DrtNetworkPreparerTest.java
git commit -m "feat(integrated): add DrtNetworkPreparer (clip to service area + drt mode)"
```

---

## Task 4: `PopulationClipper` — clip the 100 % plans to the service area

**Files:**
- Create: `parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/PopulationClipper.java`
- Test: `parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/PopulationClipperTest.java`

**Interfaces:**
- Consumes: a `Population` (in-memory) and a JTS `Geometry`. Uses `org.matsim.api.core.v01.population.*`, `org.locationtech.jts.geom.prep.PreparedGeometry` for fast containment.
- Produces: `static Population clip(Population full, Geometry serviceArea)` → a new `Population` containing only persons whose **selected plan's first activity** (home anchor) lies inside `serviceArea`. (First-activity rule chosen for determinism; documented as the clip criterion — widen later if calibration needs it.)

- [ ] **Step 1: Write the failing test**

```java
package hagrid.integrated;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.*;
import org.matsim.core.population.PopulationUtils;

import static org.assertj.core.api.Assertions.*;

@DisplayName("PopulationClipper")
class PopulationClipperTest {

    private Geometry square() {
        GeometryFactory gf = new GeometryFactory();
        return gf.createPolygon(new Coordinate[]{
                new Coordinate(0, 0), new Coordinate(1000, 0),
                new Coordinate(1000, 1000), new Coordinate(0, 1000), new Coordinate(0, 0)
        });
    }

    private Person personWithHome(String id, double x, double y) {
        Population pop = PopulationUtils.createPopulation(
                org.matsim.core.config.ConfigUtils.createConfig());
        PopulationFactory pf = pop.getFactory();
        Person p = pf.createPerson(Id.createPersonId(id));
        Plan plan = pf.createPlan();
        plan.addActivity(pf.createActivityFromCoord("home", new Coord(x, y)));
        p.addPlan(plan);
        p.setSelectedPlan(plan);
        return p;
    }

    @Test
    @DisplayName("keeps persons whose home activity is inside the area")
    void keepsInside() {
        Population full = PopulationUtils.createPopulation(
                org.matsim.core.config.ConfigUtils.createConfig());
        full.addPerson(personWithHome("inside", 500, 500));
        full.addPerson(personWithHome("outside", 9000, 9000));

        Population clipped = PopulationClipper.clip(full, square());

        assertThat(clipped.getPersons()).containsKey(Id.createPersonId("inside"));
        assertThat(clipped.getPersons()).doesNotContainKey(Id.createPersonId("outside"));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -pl parcel-demand-2-matsim-pipeline test -Dtest=PopulationClipperTest`
Expected: FAIL — `PopulationClipper` does not exist.

- [ ] **Step 3: Implement `PopulationClipper`**

```java
package hagrid.integrated;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.population.*;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PopulationUtils;

/**
 * Clips a passenger population to a DRT service area. A person is kept when the
 * first activity of its selected plan (home anchor) lies inside the area.
 */
public final class PopulationClipper {

    private static final GeometryFactory GF = new GeometryFactory();

    private PopulationClipper() {}

    public static Population clip(Population full, Geometry serviceArea) {
        PreparedGeometry prepared = new PreparedGeometryFactory().create(serviceArea);
        Population out = PopulationUtils.createPopulation(ConfigUtils.createConfig());
        for (Person person : full.getPersons().values()) {
            Coord anchor = firstActivityCoord(person);
            if (anchor != null && prepared.contains(
                    GF.createPoint(new Coordinate(anchor.getX(), anchor.getY())))) {
                out.addPerson(person);
            }
        }
        return out;
    }

    private static Coord firstActivityCoord(Person person) {
        Plan plan = person.getSelectedPlan();
        if (plan == null) {
            return null;
        }
        for (PlanElement pe : plan.getPlanElements()) {
            if (pe instanceof Activity act && act.getCoord() != null) {
                return act.getCoord();
            }
        }
        return null;
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -pl parcel-demand-2-matsim-pipeline test -Dtest=PopulationClipperTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/PopulationClipper.java \
        parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/PopulationClipperTest.java
git commit -m "feat(integrated): add PopulationClipper (clip plans to DRT service area)"
```

---

## Task 5: `DrtFleetGenerator` — generate the DVRP fleet vehicles file

**Files:**
- Create: `parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/drt/DrtFleetGenerator.java`
- Test: `parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/drt/DrtFleetGeneratorTest.java`

**Interfaces:**
- Consumes: a `Network` (the DRT sub-network from Task 3), `fleetSize`, `capacity`, service window, an output `Path`. Uses `org.matsim.contrib.dvrp.fleet.{FleetWriter, ImmutableDvrpVehicleSpecification, DvrpVehicleSpecification, DvrpVehicle}`.
- Produces: `static void write(Network net, int fleetSize, int capacity, double serviceBegin, double serviceEnd, Path out)` — writes a DVRP vehicles XML with `fleetSize` vehicles, each anchored on a deterministically chosen network link (round-robin over sorted link ids → reproducible without `Math.random()`).

- [ ] **Step 1: Write the failing test**

```java
package hagrid.integrated.drt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.*;
import org.matsim.core.network.NetworkUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

@DisplayName("DrtFleetGenerator")
class DrtFleetGeneratorTest {

    private Network net() {
        Network n = NetworkUtils.createNetwork();
        NetworkFactory f = n.getFactory();
        Node a = f.createNode(Id.createNodeId("a"), new Coord(0, 0));
        Node b = f.createNode(Id.createNodeId("b"), new Coord(100, 0));
        n.addNode(a); n.addNode(b);
        Link l = f.createLink(Id.createLinkId("l1"), a, b);
        l.setAllowedModes(Set.of("car", "drt"));
        n.addLink(l);
        return n;
    }

    @Test
    @DisplayName("writes a fleet file with the requested number of vehicles")
    void writesFleet(@TempDir Path tmp) throws Exception {
        Path out = tmp.resolve("fleet.xml");
        DrtFleetGenerator.write(net(), 5, 8, 0.0, 86400.0, out);

        assertThat(Files.exists(out)).isTrue();
        String xml = Files.readString(out);
        assertThat(xml).contains("<vehicles");
        // 5 vehicle entries
        int count = xml.split("<vehicle ", -1).length - 1;
        assertThat(count).isEqualTo(5);
        assertThat(xml).contains("start_link=\"l1\"");
    }

    @Test
    @DisplayName("rejects a fleet with no in-network link to anchor on")
    void rejectsEmptyNetwork(@TempDir Path tmp) {
        Network empty = NetworkUtils.createNetwork();
        assertThatThrownBy(() -> DrtFleetGenerator.write(empty, 3, 8, 0.0, 86400.0, tmp.resolve("f.xml")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

> The `start_link` / `<vehicle ` substrings are the standard DVRP fleet-file format. If the writer emits a slightly different attribute name in this contrib version, adjust the assertion to what `FleetWriter` actually produces (inspect the written file once).

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -pl parcel-demand-2-matsim-pipeline test -Dtest=DrtFleetGeneratorTest`
Expected: FAIL — `DrtFleetGenerator` does not exist.

- [ ] **Step 3: Implement `DrtFleetGenerator`**

```java
package hagrid.integrated.drt;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;
import org.matsim.contrib.dvrp.fleet.DvrpVehicleSpecification;
import org.matsim.contrib.dvrp.fleet.FleetWriter;
import org.matsim.contrib.dvrp.fleet.ImmutableDvrpVehicleSpecification;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Generates a DVRP fleet vehicles file for the DRT fleet. Vehicles are anchored
 * on network links round-robin over sorted link ids (reproducible, no RNG).
 */
public final class DrtFleetGenerator {

    private DrtFleetGenerator() {}

    public static void write(Network net, int fleetSize, int capacity,
                             double serviceBegin, double serviceEnd, Path out) {
        if (fleetSize < 1) {
            throw new IllegalArgumentException("fleetSize must be >= 1, got " + fleetSize);
        }
        List<Id<Link>> linkIds = new ArrayList<>(net.getLinks().keySet());
        if (linkIds.isEmpty()) {
            throw new IllegalArgumentException("cannot place a DRT fleet: network has no links");
        }
        linkIds.sort(Comparator.comparing(Id::toString));

        List<DvrpVehicleSpecification> specs = new ArrayList<>(fleetSize);
        for (int i = 0; i < fleetSize; i++) {
            Id<Link> startLink = linkIds.get(i % linkIds.size());
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
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -pl parcel-demand-2-matsim-pipeline test -Dtest=DrtFleetGeneratorTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/drt/DrtFleetGenerator.java \
        parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/drt/DrtFleetGeneratorTest.java
git commit -m "feat(integrated): add DrtFleetGenerator (DVRP fleet vehicles file)"
```

---

## Task 6: `DrtConfigComposer` — full-DVRP, service-area, DRT-only config + module install

**Files:**
- Create: `parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/drt/DrtConfigComposer.java`
- Test: `parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/drt/DrtConfigComposerTest.java`

**Interfaces:**
- Consumes: a MATSim `Config`, a `Controler`, the service-area shapefile path and fleet-file path (Strings). The DRT/DVRP contrib API listed in the reference section.
- Produces:
  - `static void composeConfig(Config config, String serviceAreaShp, String fleetFile)` — adds `DvrpConfigGroup` (networkModes = {drt}), a single `DrtConfigGroup` (mode=drt, serviceAreaBased, stopDuration 60, simulationType **fullSimulation**, native constraints, ExtensiveInsertionSearch, the shp + fleet file), runs `DrtConfigs.adjustMultiModeDrtConfig`, and appends `drt` to `subtourModeChoice`. **No** intermodality / SwissRailRaptor / fare config.
  - `static void installModules(Controler controler)` — adds `DvrpModule`, `MultiModeDrtModule`, and `configureQSimComponents(DvrpQSimComponents.activateAllModes(MultiModeDrtConfigGroup.get(config)))`.

- [ ] **Step 1: Write the failing test**

```java
package hagrid.integrated.drt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.contrib.dvrp.run.DvrpConfigGroup;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.*;

@DisplayName("DrtConfigComposer")
class DrtConfigComposerTest {

    @Test
    @DisplayName("composes a single full-simulation, service-area DRT mode")
    void composesDrtMode() {
        Config config = ConfigUtils.createConfig();
        DrtConfigComposer.composeConfig(config, "input/drt/drt-service-area.shp", "out/fleet.xml");

        MultiModeDrtConfigGroup multi = MultiModeDrtConfigGroup.get(config);
        assertThat(multi.getModalElements()).hasSize(1);
        DrtConfigGroup drt = multi.getModalElements().iterator().next();
        assertThat(drt.mode).isEqualTo(TransportMode.drt);
        assertThat(drt.simulationType).isEqualTo(DrtConfigGroup.SimulationType.fullSimulation);
        assertThat(drt.operationalScheme).isEqualTo(DrtConfigGroup.OperationalScheme.serviceAreaBased);
        assertThat(drt.stopDuration).isEqualTo(60.0);
        assertThat(drt.drtServiceAreaShapeFile).isEqualTo("input/drt/drt-service-area.shp");
        assertThat(drt.vehiclesFile).isEqualTo("out/fleet.xml");
    }

    @Test
    @DisplayName("registers dvrp network mode = drt")
    void dvrpNetworkMode() {
        Config config = ConfigUtils.createConfig();
        DrtConfigComposer.composeConfig(config, "a.shp", "f.xml");
        assertThat(DvrpConfigGroup.get(config).networkModes).containsExactly(TransportMode.drt);
    }

    @Test
    @DisplayName("adds drt to subtour mode choice")
    void drtInModeChoice() {
        Config config = ConfigUtils.createConfig();
        DrtConfigComposer.composeConfig(config, "a.shp", "f.xml");
        assertThat(Arrays.asList(config.subtourModeChoice().getModes())).contains(TransportMode.drt);
    }

    @Test
    @DisplayName("does NOT configure PT intermodality (DRT-only)")
    void noIntermodality() {
        Config config = ConfigUtils.createConfig();
        DrtConfigComposer.composeConfig(config, "a.shp", "f.xml");
        // SwissRailRaptor module must not have been added with intermodal access/egress
        assertThat(config.getModules()).doesNotContainKey("swissRailRaptor");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -pl parcel-demand-2-matsim-pipeline test -Dtest=DrtConfigComposerTest`
Expected: FAIL — `DrtConfigComposer` does not exist.

- [ ] **Step 3: Implement `DrtConfigComposer`**

```java
package hagrid.integrated.drt;

import org.matsim.api.core.v01.TransportMode;
import org.matsim.contrib.drt.optimizer.constraints.DefaultDrtOptimizationConstraintsSet;
import org.matsim.contrib.drt.optimizer.constraints.DrtOptimizationConstraintsParams;
import org.matsim.contrib.drt.optimizer.insertion.ExtensiveInsertionSearchParams;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.run.DrtConfigs;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.contrib.drt.run.MultiModeDrtModule;
import org.matsim.contrib.dvrp.run.DvrpConfigGroup;
import org.matsim.contrib.dvrp.run.DvrpModule;
import org.matsim.contrib.dvrp.run.DvrpQSimComponents;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.QSimConfigGroup;
import org.matsim.core.controler.Controler;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Composes the native Lausitz DRT configuration into a HAGRID {@link Config},
 * with two deliberate divergences from the native setup: full DVRP simulation
 * (real dispatched fleet) and DRT-only (no PT intermodality). Parameter values
 * mirror matsim-lausitz {@code LausitzDrtScenario}/{@code DrtAndIntermodalityOptions}.
 */
public final class DrtConfigComposer {

    // Native Lausitz DRT parameters (verbatim).
    private static final double STOP_DURATION_S = 60.0;
    private static final double MAX_WAIT_TIME_S = 1200.0;
    private static final double MAX_TRAVEL_TIME_ALPHA = 1.5;
    private static final double MAX_TRAVEL_TIME_BETA_S = 1200.0;

    private DrtConfigComposer() {}

    public static void composeConfig(Config config, String serviceAreaShp, String fleetFile) {
        DvrpConfigGroup dvrp = ConfigUtils.addOrGetModule(config, DvrpConfigGroup.class);
        dvrp.networkModes = Set.of(TransportMode.drt);

        MultiModeDrtConfigGroup multi = ConfigUtils.addOrGetModule(config, MultiModeDrtConfigGroup.class);
        if (multi.getModalElements().isEmpty()) {
            DrtConfigGroup drt = new DrtConfigGroup();
            drt.mode = TransportMode.drt;
            drt.operationalScheme = DrtConfigGroup.OperationalScheme.serviceAreaBased;
            drt.stopDuration = STOP_DURATION_S;
            drt.simulationType = DrtConfigGroup.SimulationType.fullSimulation;
            drt.drtServiceAreaShapeFile = serviceAreaShp;
            drt.vehiclesFile = fleetFile;

            DrtOptimizationConstraintsParams constraints = drt.addOrGetDrtOptimizationConstraintsParams();
            DefaultDrtOptimizationConstraintsSet set =
                    (DefaultDrtOptimizationConstraintsSet) constraints.addOrGetDefaultDrtOptimizationConstraintsSet();
            set.maxWaitTime = MAX_WAIT_TIME_S;
            set.maxTravelTimeAlpha = MAX_TRAVEL_TIME_ALPHA;
            set.maxTravelTimeBeta = MAX_TRAVEL_TIME_BETA_S;

            drt.addDrtInsertionSearchParams(new ExtensiveInsertionSearchParams());
            multi.addParameterSet(drt);
        }

        // DynAgents need only the start time.
        config.qsim().setSimStarttimeInterpretation(QSimConfigGroup.StarttimeInterpretation.onlyUseStarttime);

        // Staging activity + drt scoring/routing params (core helper, not Lausitz-specific).
        DrtConfigs.adjustMultiModeDrtConfig(multi, config.scoring(), config.routing());

        // Offer drt in mode choice (DRT-only: no intermodal access/egress).
        List<String> modes = new ArrayList<>(List.of(config.subtourModeChoice().getModes()));
        if (!modes.contains(TransportMode.drt)) {
            modes.add(TransportMode.drt);
            config.subtourModeChoice().setModes(modes.toArray(new String[0]));
        }
    }

    public static void installModules(Controler controler) {
        Config config = controler.getConfig();
        controler.addOverridingModule(new DvrpModule());
        controler.addOverridingModule(new MultiModeDrtModule());
        controler.configureQSimComponents(
                DvrpQSimComponents.activateAllModes(MultiModeDrtConfigGroup.get(config)));
    }
}
```

> If `set.maxWaitTime` does not resolve as a public field on `DefaultDrtOptimizationConstraintsSet` (it is declared on the parent `DrtOptimizationConstraintsSet`), the compile error will say so — keep the assignment; the field is inherited and accessible. `ExtensiveInsertionSearchParams` and `DrtConfigGroup` have no-arg constructors (matches native usage).

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -pl parcel-demand-2-matsim-pipeline test -Dtest=DrtConfigComposerTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/drt/DrtConfigComposer.java \
        parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/drt/DrtConfigComposerTest.java
git commit -m "feat(integrated): add DrtConfigComposer (full-DVRP, service-area, DRT-only)"
```

---

## Task 7: Thread `StudyArea` + DRT through the runner

**Files:**
- Modify: `parcel-demand-2-matsim-pipeline/src/main/java/hagrid/simulation/HAGRIDSimulationConfig.java` (constructor at lines 98–129; `new HagridPaths()` at line 120)
- Modify: `parcel-demand-2-matsim-pipeline/src/main/java/hagrid/simulation/SimulationRunnerUtils.java` (`parseScenario` lines 100–136; `runSimulation` lines 182–220)
- Modify: `parcel-demand-2-matsim-pipeline/src/main/java/hagrid/simulation/HAGRIDScenarioBuilder.java` (`setupConfig` ~lines 119–156; `build` ~lines 54–109)
- Test: `parcel-demand-2-matsim-pipeline/src/test/java/hagrid/simulation/ScenarioParsingTest.java` (new)

**Interfaces:**
- Consumes: `Scenario.isDrt()` (Task 1), the Lausitz path getters (Task 2), `DrtConfigComposer` (Task 6).
- Produces:
  - `HAGRIDSimulationConfig` overloaded constructor adding `StudyArea studyArea` and `int fleetSize`; getters `getStudyArea()`, `isDrtScenario()`, `getFleetSize()`, and the DRT path getters (`getDrtServiceAreaShapefile()`, `getDrtNetworkClipped()`, `getPassengerPlansClipped()`, `getDrtFleetFile()`).
  - `parseScenario` accepts `studyArea=` (default `HANNOVER`) and `fleetSize=` (default `50`), and **rejects** a DRT concept with a non-Lausitz study area.

- [ ] **Step 1: Write the failing test**

```java
package hagrid.simulation;

import hagrid.utils.general.StudyArea;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

@DisplayName("Scenario parsing — study area & DRT")
class ScenarioParsingTest {

    @Test
    @DisplayName("non-DRT scenario defaults to HANNOVER")
    void defaultHannover() {
        HAGRIDSimulationConfig cfg = SimulationRunnerUtils.parseScenario(
                "concept=basecase,date=2025-05-13");
        assertThat(cfg.getStudyArea()).isEqualTo(StudyArea.HANNOVER);
        assertThat(cfg.isDrtScenario()).isFalse();
    }

    @Test
    @DisplayName("DRT scenario with studyArea=LAUSITZ_HOYERSWERDA parses")
    void drtLausitz() {
        HAGRIDSimulationConfig cfg = SimulationRunnerUtils.parseScenario(
                "concept=drt_baseline,date=2025-05-13,studyArea=LAUSITZ_HOYERSWERDA,fleetSize=20");
        assertThat(cfg.getStudyArea()).isEqualTo(StudyArea.LAUSITZ_HOYERSWERDA);
        assertThat(cfg.isDrtScenario()).isTrue();
        assertThat(cfg.getFleetSize()).isEqualTo(20);
    }

    @Test
    @DisplayName("DRT scenario without Lausitz is rejected")
    void drtWithoutLausitzRejected() {
        assertThatThrownBy(() -> SimulationRunnerUtils.parseScenario(
                "concept=drt_baseline,date=2025-05-13"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("LAUSITZ_HOYERSWERDA");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -pl parcel-demand-2-matsim-pipeline test -Dtest=ScenarioParsingTest`
Expected: FAIL — no `getStudyArea()` / parsing.

- [ ] **Step 3: Extend `HAGRIDSimulationConfig`**

Add `studyArea` + `fleetSize` fields, a new constructor (keep the old one delegating with `HANNOVER`/default fleet so nothing else breaks), and getters. Replace line 120's `new HagridPaths()` with study-area scoping:

```java
// new fields
private final StudyArea studyArea;
private final int fleetSize;

// keep the existing 8-arg constructor, delegating:
public HAGRIDSimulationConfig(String concept, LocalDate date, int maxIterations, int jspritIterations,
                      boolean zoneBasedCachingEnabled, double zoneBasedCachingThresholdMeters,
                      double uTurnPenaltyCost, String tag) {
    this(concept, date, maxIterations, jspritIterations, zoneBasedCachingEnabled,
            zoneBasedCachingThresholdMeters, uTurnPenaltyCost, tag, StudyArea.HANNOVER, 50);
}

// new full constructor (body mirrors the original, with the two changes marked)
public HAGRIDSimulationConfig(String concept, LocalDate date, int maxIterations, int jspritIterations,
                      boolean zoneBasedCachingEnabled, double zoneBasedCachingThresholdMeters,
                      double uTurnPenaltyCost, String tag, StudyArea studyArea, int fleetSize) {
    // ... identical validation as the original constructor ...
    this.studyArea = Objects.requireNonNull(studyArea, "studyArea must not be null");
    this.fleetSize = fleetSize;
    // ... identical runId construction ...
    this.paths = new HagridPaths(studyArea);   // CHANGED from new HagridPaths()
    this.paths.initializeRun(runId);
    // ... identical ensureSharedSimulationInputs ...
}

public StudyArea getStudyArea() { return studyArea; }
public int getFleetSize() { return fleetSize; }
public boolean isDrtScenario() {
    return HagridConfig.Scenario.valueOf(concept.toUpperCase()).isDrt();
}

public String getDrtServiceAreaShapefile() { return paths.drtServiceAreaShapefile(); }
public String getLausitzNetworkRaw()       { return paths.lausitzNetworkRaw(); }
public String getPassengerPlansRaw()       { return paths.passengerPlansRaw(); }
public String getDrtNetworkClipped()       { return paths.drtNetworkClipped(); }
public String getPassengerPlansClipped()   { return paths.passengerPlansClipped(); }
public String getDrtFleetFile()            { return paths.drtFleetFile(); }
```

Add the imports `import hagrid.HagridConfig;` and `import hagrid.utils.general.StudyArea;`.

> `isDrtScenario()` maps the concept string to the enum via `valueOf(concept.toUpperCase())`. Concepts like `drt_baseline` → `DRT_BASELINE`. If a non-enum concept string is ever passed it will throw `IllegalArgumentException` from `valueOf`; guard with a try/catch returning `false` if the existing pipeline allows free-form concept names (grep how `concept` is otherwise validated).

- [ ] **Step 4: Extend `parseScenario`**

In `SimulationRunnerUtils.parseScenario` (after the existing `tag` line ~129, before the `return`):

```java
StudyArea studyArea = StudyArea.valueOf(
        map.getOrDefault("studyArea", "HANNOVER").trim().toUpperCase());
int fleetSize = positiveInt(map.getOrDefault("fleetSize", "50"), "fleetSize");

// DRT concept requires the Lausitz study area
boolean isDrt;
try {
    isDrt = hagrid.HagridConfig.Scenario.valueOf(concept.toUpperCase()).isDrt();
} catch (IllegalArgumentException ex) {
    isDrt = false;
}
if (isDrt && studyArea != StudyArea.LAUSITZ_HOYERSWERDA) {
    throw new IllegalArgumentException(
            "DRT concept '" + concept + "' requires studyArea=LAUSITZ_HOYERSWERDA, got " + studyArea);
}

return new HAGRIDSimulationConfig(concept, date, maxIter, jspritIter,
        zoneCaching, zoneThreshold, uTurnPenaltyCost, tag, studyArea, fleetSize);
```

Add `import hagrid.utils.general.StudyArea;` to `SimulationRunnerUtils`. Update the old `return new HAGRIDSimulationConfig(...)` (lines 134–135) to the new call above. Also add `studyArea` and `fleetSize` lines to the usage text in `printUsage()`.

- [ ] **Step 5: Wire the DRT branch into `runSimulation`**

In `SimulationRunnerUtils.runSimulation`, after the `HAGRIDSimulationModule` is added (line 207) and before `controler.run()` (line 211):

```java
        if (cfg.isDrtScenario()) {
            hagrid.integrated.drt.DrtConfigComposer.installModules(controler);
            LOG.info("DRT modules installed (fleet size {}).", cfg.getFleetSize());
        }
```

- [ ] **Step 6: Wire the DRT branch into `HAGRIDScenarioBuilder`**

In `HAGRIDScenarioBuilder.setupConfig` (after the network/CRS block, ~line 135), branch for DRT so the config points at the clipped inputs and the DRT config is composed **before** `ScenarioUtils.loadScenario(config)` runs (line 80 of `build`):

```java
        if (simConfig.isDrtScenario()) {
            // Clipped DRT network + 100% population clipped to the service zone
            config.network().setInputFile(simConfig.getDrtNetworkClipped());
            config.plans().setInputFile(simConfig.getPassengerPlansClipped());
            // Compose native DRT params (full DVRP, service-area, DRT-only)
            hagrid.integrated.drt.DrtConfigComposer.composeConfig(
                    config, simConfig.getDrtServiceAreaShapefile(), simConfig.getDrtFleetFile());
        }
```

> The clipped network / plans / fleet files are produced by Tasks 3–5 during preprocessing (a separate preprocessing entry point, out of scope for the smoke test, which builds them directly). For DRT runs, ensure `validateInputFiles()` also checks these three exist — add `checkFile` calls guarded by `isDrtScenario()` in `HAGRIDSimulationConfig.validateInputFiles()`.

- [ ] **Step 7: Run the parsing test + full module regression**

Run: `mvn -pl parcel-demand-2-matsim-pipeline test -Dtest=ScenarioParsingTest`
Expected: PASS.
Run: `mvn -pl parcel-demand-2-matsim-pipeline test`
Expected: BUILD SUCCESS, 0 failures (all prior tests + new).

- [ ] **Step 8: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/src/main/java/hagrid/simulation/HAGRIDSimulationConfig.java \
        parcel-demand-2-matsim-pipeline/src/main/java/hagrid/simulation/SimulationRunnerUtils.java \
        parcel-demand-2-matsim-pipeline/src/main/java/hagrid/simulation/HAGRIDScenarioBuilder.java \
        parcel-demand-2-matsim-pipeline/src/test/java/hagrid/simulation/ScenarioParsingTest.java
git commit -m "feat(integrated): thread StudyArea + DRT through runner; install DRT modules for DRT_*"
```

---

## Task 8: DRT_BASELINE integration smoke test

**Files:**
- Create: `parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/drt/DrtBaselineIntegrationTest.java`
- Test inputs: build a tiny scenario programmatically (no external files) so the test is self-contained and fast.

**Interfaces:**
- Consumes: `DrtNetworkPreparer` (3), `DrtFleetGenerator` (5), `DrtConfigComposer` (6). Uses `MatsimTestUtils` for an isolated output dir.

**What it proves:** the composed full-DVRP DRT config + installed modules actually run a MATSim iteration on a clipped network with a clipped population and a generated fleet, and produce DRT output — i.e. the composition seam is correct end-to-end. (The combined freight+DRT headline run is a later calibration activity, not this smoke test.)

- [ ] **Step 1: Write the failing integration test**

```java
package hagrid.integrated.drt;

import hagrid.integrated.PopulationClipper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.locationtech.jts.geom.*;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.*;
import org.matsim.api.core.v01.population.*;
import org.matsim.contrib.dvrp.run.DvrpConfigGroup;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.io.NetworkWriter;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.testcases.MatsimTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

@DisplayName("DRT_BASELINE integration smoke test")
class DrtBaselineIntegrationTest {

    @RegisterExtension
    public MatsimTestUtils utils = new MatsimTestUtils();

    @Test
    @DisplayName("composed full-DVRP DRT runs one iteration and produces output")
    void runsOneIteration() throws Exception {
        Path dir = Path.of(utils.getOutputDirectory());

        // --- tiny grid network fully inside a 0..2000 service square ---
        Network net = grid();
        Geometry area = square(2000);
        Network drtNet = DrtNetworkPreparer.prepare(net, area);
        Path netFile = dir.resolve("drt_network.xml.gz");
        new NetworkWriter(drtNet).write(netFile.toString());

        // --- fleet ---
        Path fleet = dir.resolve("fleet.xml.gz");
        DrtFleetGenerator.write(drtNet, 4, 8, 0.0, 86400.0, fleet);

        // --- tiny population, all homes inside the area ---
        Population pop = demand(drtNet);
        Path plans = dir.resolve("plans.xml.gz");
        PopulationUtils.writePopulation(PopulationClipper.clip(pop, area), plans.toString());

        // --- config ---
        Config config = ConfigUtils.createConfig();
        config.global().setCoordinateSystem("EPSG:25832");
        config.network().setInputFile(netFile.toString());
        config.plans().setInputFile(plans.toString());
        config.controller().setOutputDirectory(dir.resolve("matsim").toString());
        config.controller().setLastIteration(0);
        config.controller().setOverwriteFileSetting(
                OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
        // generic activity scoring so agents can score
        ScoringConfigGroup.ActivityParams home = new ScoringConfigGroup.ActivityParams("home");
        home.setTypicalDuration(12 * 3600);
        config.scoring().addActivityParams(home);
        ScoringConfigGroup.ActivityParams work = new ScoringConfigGroup.ActivityParams("work");
        work.setTypicalDuration(8 * 3600);
        config.scoring().addActivityParams(work);

        DrtConfigComposer.composeConfig(config, "UNUSED_for_door2door.shp", fleet.toString());
        // For the smoke test, override to door2door so no real shapefile is needed.
        org.matsim.contrib.drt.run.MultiModeDrtConfigGroup.get(config).getModalElements()
                .iterator().next().operationalScheme =
                org.matsim.contrib.drt.run.DrtConfigGroup.OperationalScheme.door2door;
        org.matsim.contrib.drt.run.MultiModeDrtConfigGroup.get(config).getModalElements()
                .iterator().next().drtServiceAreaShapeFile = null;

        Controler controler = new Controler(ScenarioUtils.loadScenario(config));
        DrtConfigComposer.installModules(controler);
        controler.run();

        // DRT output produced
        Path drtOut = dir.resolve("matsim");
        assertThat(Files.exists(drtOut)).isTrue();
        assertThat(DvrpConfigGroup.get(config).networkModes).contains(TransportMode.drt);
        // a DRT-specific output file exists (customer stats / vehicle stats)
        try (var stream = Files.walk(drtOut)) {
            assertThat(stream.anyMatch(p -> p.getFileName().toString().toLowerCase().contains("drt")))
                    .as("expected at least one drt_* output file").isTrue();
        }
    }

    // --- helpers ---

    private Geometry square(double size) {
        GeometryFactory gf = new GeometryFactory();
        return gf.createPolygon(new Coordinate[]{
                new Coordinate(0, 0), new Coordinate(size, 0),
                new Coordinate(size, size), new Coordinate(0, size), new Coordinate(0, 0)});
    }

    private Network grid() {
        Network n = NetworkUtils.createNetwork();
        NetworkFactory f = n.getFactory();
        Node[] nodes = new Node[4];
        double[][] xy = {{100, 100}, {1000, 100}, {1000, 1000}, {100, 1000}};
        for (int i = 0; i < 4; i++) {
            nodes[i] = f.createNode(Id.createNodeId("n" + i), new Coord(xy[i][0], xy[i][1]));
            n.addNode(nodes[i]);
        }
        for (int i = 0; i < 4; i++) {
            addLink(n, f, "l" + i, nodes[i], nodes[(i + 1) % 4]);
            addLink(n, f, "l" + i + "r", nodes[(i + 1) % 4], nodes[i]);
        }
        return n;
    }

    private void addLink(Network n, NetworkFactory f, String id, Node a, Node b) {
        Link l = f.createLink(Id.createLinkId(id), a, b);
        l.setLength(1000);
        l.setFreespeed(13.9);
        l.setCapacity(1800);
        l.setNumberOfLanes(1);
        l.setAllowedModes(Set.of("car"));
        n.addLink(l);
    }

    private Population demand(Network net) {
        Population pop = PopulationUtils.createPopulation(ConfigUtils.createConfig());
        PopulationFactory pf = pop.getFactory();
        for (int i = 0; i < 5; i++) {
            Person p = pf.createPerson(Id.createPersonId("p" + i));
            Plan plan = pf.createPlan();
            Activity h = pf.createActivityFromCoord("home", new Coord(150, 150));
            h.setEndTime(8 * 3600 + i * 60);
            plan.addActivity(h);
            Leg leg = pf.createLeg(TransportMode.drt);
            plan.addLeg(leg);
            plan.addActivity(pf.createActivityFromCoord("work", new Coord(950, 950)));
            p.addPlan(plan);
            p.setSelectedPlan(plan);
            pop.addPerson(p);
        }
        return pop;
    }
}
```

> The smoke test uses `door2door` (no shapefile) on purpose so it needs no external files; the production path uses `serviceAreaBased` + the real shapefile (Task 6 / Task 7). If `ScenarioUtils.loadScenario` requires a `DrtRouteFactory` registration (it does in some versions), add before `loadScenario`: `scenario.getPopulation().getFactory().getRouteFactories().setRouteFactory(org.matsim.contrib.drt.routing.DrtRoute.class, new org.matsim.contrib.drt.routing.DrtRouteFactory());` — build the `Scenario` first via `ScenarioUtils.createScenario(config)` + `ScenarioUtils.loadScenario(scenario)` to get the handle. The compile/run error will tell you; this mirrors `LausitzDrtScenario.configureDrtScenario`.

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -pl parcel-demand-2-matsim-pipeline test -Dtest=DrtBaselineIntegrationTest`
Expected: FAIL initially (missing pieces / route factory) — iterate per the note until it runs one iteration and the DRT-output assertion passes.

- [ ] **Step 3: Make it pass**

Apply the `DrtRouteFactory` registration if required (see note). No production code should need to change — if it does, that is a real integration gap; fix it in the relevant Task-6/7 class and re-run its unit test too.

- [ ] **Step 4: Run the full module test suite**

Run: `mvn -pl parcel-demand-2-matsim-pipeline test`
Expected: BUILD SUCCESS, 0 failures/errors (all prior tests + all new).

- [ ] **Step 5: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/drt/DrtBaselineIntegrationTest.java
git commit -m "test(integrated): add DRT_BASELINE MATSim integration smoke test"
```

---

## Self-Review

**1. Spec coverage (vs. `2026-06-17-lausitz-drt-freight-integration-design.md` §5.4, §5.5, §9 steps 1–2 & 9):**
- §5.4 `Scenario` enum DRT_* values → Task 1. ✅
- §5.5 `StudyArea` coupling validated at runtime (not in the enum) → Task 1 (`setStudyArea`) + Task 7 (`parseScenario`). ✅
- §5.5 study-area-scoped inputs (network, clipped plans, service-area shape, DRT config) → Task 2 paths + Tasks 3/4 clipping. ✅
- §5.5 shape-based service-area clip (not the `Region` filter) → Task 3 (network) + Task 4 (population). ✅
- §9 step 9 "compose native DRT config" → Task 6, with the two user-approved divergences (full DVRP, DRT-only) recorded in Global Constraints. ✅
- §3.1 native DRT params reused → Task 6 reference table (verbatim values). ✅
- §10 integration smoke test (tiny scenario, runs + produces output) → Task 8. ✅
- **Deliberately out of scope for 1b (later plans):** parcel-request list / segment aggregation (1c), FreightTourRequest path (1d), `IntegratedKPIHandler` + KPI CSV (1e), autonomy network/speed effects (the `excludedRoadTypes`/`maxSpeed` plumbing already lives in `IntegratedScenarioConfig` and is applied when the dispatch logic lands in 1c/1d). Noted so the reviewer doesn't expect them here.

**2. Placeholder scan:** No "TBD/implement later/handle appropriately". Every code step shows the actual code. The `>` notes are *verification instructions* tied to specific API-version risks (e.g. `MultimodalNetworkCleaner.run`, `DrtRouteFactory`), each with a concrete fallback — not deferred work.

**3. Type consistency:** `DrtConfigComposer.composeConfig(Config, String, String)` and `installModules(Controler)` are used identically in Tasks 7 & 8. `DrtNetworkPreparer.prepare(Network, Geometry)`, `PopulationClipper.clip(Population, Geometry)`, `DrtFleetGenerator.write(Network,int,int,double,double,Path)` signatures match every call site. `Scenario.isDrt()` (Task 1) is consumed by `HAGRIDSimulationConfig.isDrtScenario()` (Task 7). Path getter names (`drtNetworkClipped`, `passengerPlansClipped`, `drtFleetFile`, `drtServiceAreaShapefile`) are identical in Tasks 2 and 7.

**Known risk (flagged, not hidden):** several MATSim contrib calls are verified by `javap` against the resolved `2025.0-PR3552` jars (config groups, enums, `FleetWriter`, modules). Two runtime-only behaviours — `MultimodalNetworkCleaner.run(Set)` and the `DrtRouteFactory` requirement in `ScenarioUtils.loadScenario` — are confirmable only by compiling/running; each has an inline fallback. The TDD compile+test gate at every step surfaces any drift immediately.
