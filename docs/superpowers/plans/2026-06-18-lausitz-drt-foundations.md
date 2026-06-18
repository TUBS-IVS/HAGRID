# Lausitz DRT-Freight — Phase 1a (Foundations) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the `StudyArea` dimension (HANNOVER default + LAUSITZ_HOYERSWERDA), make HAGRID input paths study-area-scoped without breaking existing Hannover runs, and add the two new config/data scaffolding classes (`IntegratedScenarioConfig`, `DepotNetwork`) that the DRT scenarios will consume.

**Architecture:** HAGRID stays the frontend/orchestrator (spec Option B). This plan is the *foundation* sub-plan of Phase 1 — it touches only configuration and path plumbing plus two new self-contained classes in a new `hagrid.integrated` package. It deliberately does **not** add the `DRT_*` `Scenario` enum values, the matsim-lausitz config composition, the dispatch logic, or the KPI handler — those are sub-plans 1b–1e (see "Out of Scope / Follow-on Plans"). Everything here is unit-testable and leaves all existing scenarios (`BASECASE`, `WHITE_LABEL`, …) running exactly as today.

**Tech Stack:** Java 21, Maven (multi-module), MATSim `2025.0-PR3552`, matsim-lausitz `2.0` (local `mvn install`), JUnit 5 (Jupiter) + AssertJ, Lombok available. New code lives in module `parcel-demand-2-matsim-pipeline`, root package `hagrid`.

## Global Constraints

- **Java release:** 21 (`maven.compiler.release=21`).
- **MATSim version:** `2025.0-PR3552` (already set in root `pom.xml`; do not change).
- **matsim-lausitz:** consumed as `com.github.matsim-scenarios:matsim-lausitz:2.0` from the local `.m2` (built locally; JitPack cannot build it). Not used in *this* sub-plan, but the dependency is present.
- **Backward compatibility (HARD):** `StudyArea.HANNOVER` is the default everywhere and MUST resolve input paths to `hagrid-input/` exactly as today. No existing run, test, or output layout may change. All 155 existing tests must still pass.
- **Package for new integrated code:** `hagrid.integrated` (under `parcel-demand-2-matsim-pipeline/src/main/java/`).
- **Test style:** JUnit 5 `@Nested`/`@DisplayName`, AssertJ `assertThat(...)`, mirror `HagridPathsTest`/`HagridConfigTest`.
- **Build/test commands:** the `freight` module must be installed once so the `parcel` module resolves it:
  `mvn -pl freight install -DskipTests` — then per-test runs use
  `mvn -pl parcel-demand-2-matsim-pipeline test -Dtest=<TestClass>`.
- **Commits:** one commit per task, conventional-commit style, after its tests pass. End every commit message with the Co-Authored-By trailer used in this repo.

---

### Task 1: `StudyArea` enum

**Files:**
- Create: `parcel-demand-2-matsim-pipeline/src/main/java/hagrid/utils/general/StudyArea.java`
- Test: `parcel-demand-2-matsim-pipeline/src/test/java/hagrid/utils/general/StudyAreaTest.java`

**Interfaces:**
- Produces: `enum hagrid.utils.general.StudyArea { HANNOVER, LAUSITZ_HOYERSWERDA }` with instance method `String folder()` returning the input-subfolder name (`""` for HANNOVER, `"lausitz"` for LAUSITZ_HOYERSWERDA). Consumed by `HagridPaths` (Task 2) and `HagridConfig` (Task 3).

- [ ] **Step 1: Write the failing test**

Create `StudyAreaTest.java`:

```java
package hagrid.utils.general;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

@DisplayName("StudyArea")
class StudyAreaTest {

    @Test
    @DisplayName("HANNOVER maps to the empty input subfolder (preserves legacy layout)")
    void hannoverFolderIsEmpty() {
        assertThat(StudyArea.HANNOVER.folder()).isEmpty();
    }

    @Test
    @DisplayName("LAUSITZ_HOYERSWERDA maps to the 'lausitz' input subfolder")
    void lausitzFolder() {
        assertThat(StudyArea.LAUSITZ_HOYERSWERDA.folder()).isEqualTo("lausitz");
    }

    @Test
    @DisplayName("valueOf is case-sensitive enum lookup")
    void valueOfRoundTrips() {
        assertThat(StudyArea.valueOf("HANNOVER")).isSameAs(StudyArea.HANNOVER);
        assertThat(StudyArea.valueOf("LAUSITZ_HOYERSWERDA")).isSameAs(StudyArea.LAUSITZ_HOYERSWERDA);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl parcel-demand-2-matsim-pipeline test -Dtest=StudyAreaTest`
Expected: FAIL — compilation error, `StudyArea` does not exist.

- [ ] **Step 3: Write minimal implementation**

Create `StudyArea.java`:

```java
package hagrid.utils.general;

/**
 * Geographic study area for a HAGRID run. Orthogonal to {@link hagrid.HagridConfig.Scenario}
 * (which is a delivery-concept selector and geography-agnostic).
 *
 * <p>The {@link #folder()} value is the input subfolder under {@code hagrid-input/}.
 * {@code HANNOVER} uses the empty string so its inputs stay directly under
 * {@code hagrid-input/} — preserving the legacy layout and all existing runs.</p>
 */
public enum StudyArea {

    /** Default. Region Hannover (and its sub-municipalities via {@link Region}). Legacy input layout. */
    HANNOVER(""),

    /** Lausitz / Hoyerswerda — native matsim-lausitz DRT service area. */
    LAUSITZ_HOYERSWERDA("lausitz");

    private final String folder;

    StudyArea(String folder) {
        this.folder = folder;
    }

    /** Input subfolder under {@code hagrid-input/}; empty for {@link #HANNOVER}. */
    public String folder() {
        return folder;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl parcel-demand-2-matsim-pipeline test -Dtest=StudyAreaTest`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/src/main/java/hagrid/utils/general/StudyArea.java \
        parcel-demand-2-matsim-pipeline/src/test/java/hagrid/utils/general/StudyAreaTest.java
git commit -m "feat(integrated): add StudyArea dimension enum

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: Study-area-scoped `HagridPaths`

**Files:**
- Modify: `parcel-demand-2-matsim-pipeline/src/main/java/hagrid/HagridPaths.java` (constructors + `inputBase` derivation + accessor)
- Test: `parcel-demand-2-matsim-pipeline/src/test/java/hagrid/HagridPathsTest.java` (add a `StudyAreaScoping` nested class)

**Interfaces:**
- Consumes: `StudyArea` (Task 1).
- Produces: new constructors `HagridPaths(StudyArea)`, `HagridPaths(Path, StudyArea)`; accessor `StudyArea getStudyArea()`. Existing `HagridPaths()` and `HagridPaths(Path)` keep their signatures and default to `StudyArea.HANNOVER`. `inputBase()` is now `pipelineRoot/hagrid-input[/<area.folder()>]`. Consumed by `HagridConfig` (Task 3).

- [ ] **Step 1: Write the failing test**

Append this nested class inside `HagridPathsTest` (before the final closing brace), and add the import `import hagrid.utils.general.StudyArea;` at the top:

```java
    @Nested
    @DisplayName("Study-area scoping")
    class StudyAreaScoping {

        @Test
        @DisplayName("default constructor uses HANNOVER and legacy hagrid-input layout")
        void defaultIsHannoverLegacyLayout() {
            HagridPaths p = new HagridPaths();
            assertThat(p.getStudyArea()).isEqualTo(StudyArea.HANNOVER);
            assertThat(p.inputBase().toString()).endsWith("hagrid-input");
        }

        @Test
        @DisplayName("HANNOVER input base has no extra subfolder")
        void hannoverNoSubfolder(@TempDir Path tempDir) {
            HagridPaths p = new HagridPaths(tempDir, StudyArea.HANNOVER);
            assertThat(p.inputBase()).isEqualTo(tempDir.resolve("hagrid-input"));
        }

        @Test
        @DisplayName("LAUSITZ_HOYERSWERDA input base is scoped under hagrid-input/lausitz")
        void lausitzScoped(@TempDir Path tempDir) {
            HagridPaths p = new HagridPaths(tempDir, StudyArea.LAUSITZ_HOYERSWERDA);
            assertThat(p.getStudyArea()).isEqualTo(StudyArea.LAUSITZ_HOYERSWERDA);
            assertThat(p.inputBase()).isEqualTo(tempDir.resolve("hagrid-input").resolve("lausitz"));
            // network input file is scoped too
            assertThat(p.networkFile()).contains("lausitz");
        }

        @Test
        @DisplayName("output bases are NOT study-area-scoped (RUN_ID disambiguates outputs)")
        void outputsNotScoped(@TempDir Path tempDir) {
            HagridPaths p = new HagridPaths(tempDir, StudyArea.LAUSITZ_HOYERSWERDA);
            assertThat(p.matsimOutputBase()).isEqualTo(tempDir.resolve("hagrid-matsim-output"));
        }
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl parcel-demand-2-matsim-pipeline test -Dtest=HagridPathsTest`
Expected: FAIL — `getStudyArea()` and the `(Path, StudyArea)` constructor do not exist.

- [ ] **Step 3: Write minimal implementation**

In `HagridPaths.java`:

(a) Add the import near the top:

```java
import hagrid.utils.general.StudyArea;
```

(b) Add a field next to the other base directories (after `private final Path matsimOutputBase;`):

```java
    private final StudyArea studyArea;
```

(c) Replace the two existing constructors (the no-arg `HagridPaths()` and `HagridPaths(Path pipelineRoot)`) with these four:

```java
    /** Auto-detecting root, default study area (HANNOVER). */
    public HagridPaths() {
        this(detectPipelineRoot(), StudyArea.HANNOVER);
    }

    /** Auto-detecting root, explicit study area. */
    public HagridPaths(StudyArea studyArea) {
        this(detectPipelineRoot(), studyArea);
    }

    /** Explicit root, default study area (HANNOVER). */
    public HagridPaths(Path pipelineRoot) {
        this(pipelineRoot, StudyArea.HANNOVER);
    }

    /** Explicit root and study area. */
    public HagridPaths(Path pipelineRoot, StudyArea studyArea) {
        this.pipelineRoot = pipelineRoot;
        this.studyArea = studyArea;
        Path hagridInput = pipelineRoot.resolve("hagrid-input");
        this.inputBase = studyArea.folder().isEmpty()
                ? hagridInput
                : hagridInput.resolve(studyArea.folder());
        this.outputBase = pipelineRoot.resolve("hagrid-output");
        this.matsimOutputBase = pipelineRoot.resolve("hagrid-matsim-output");
    }
```

(d) Add the accessor next to `getPipelineRoot()`:

```java
    public StudyArea getStudyArea() { return studyArea; }
```

> Note: `outputBase`/`matsimOutputBase` stay un-scoped on purpose — outputs are RUN_ID-based and the RUN_ID already encodes the scenario. `sharedDir()` (under `outputBase`) and the Hannover-specific `copySharedSimulationInputs()` filenames are unchanged here; Lausitz shared-input handling is sub-plan 1b.

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -pl parcel-demand-2-matsim-pipeline test -Dtest=HagridPathsTest`
Expected: PASS — the existing tests (default HANNOVER ⇒ `hagrid-input`) plus the 4 new ones. Confirms backward compatibility.

- [ ] **Step 5: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/src/main/java/hagrid/HagridPaths.java \
        parcel-demand-2-matsim-pipeline/src/test/java/hagrid/HagridPathsTest.java
git commit -m "feat(paths): make HagridPaths input root study-area-scoped (HANNOVER default unchanged)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: `HagridConfig` study-area support

**Files:**
- Modify: `parcel-demand-2-matsim-pipeline/src/main/java/hagrid/HagridConfig.java`
- Test: `parcel-demand-2-matsim-pipeline/src/test/java/hagrid/HagridConfigTest.java` (add a `StudyAreaSettings` nested class)

**Interfaces:**
- Consumes: `StudyArea` (Task 1), `HagridPaths(StudyArea)` (Task 2).
- Produces: `StudyArea getStudyArea()`, `void setStudyArea(StudyArea)`, `void setStudyAreaAsString(String)` on `HagridConfig`. Setting the study area rebuilds the internal `HagridPaths` and re-derives input paths. `setStudyAreaAsString` is the entry point sub-plan 1b will call from `ScenarioRunner`.

- [ ] **Step 1: Write the failing test**

Append this nested class inside `HagridConfigTest` (before the final closing brace); add `import hagrid.utils.general.StudyArea;` at the top:

```java
    @Nested
    @DisplayName("Study Area")
    class StudyAreaSettings {

        @Test
        @DisplayName("default study area is HANNOVER")
        void defaultStudyArea() {
            assertThat(config.getStudyArea()).isEqualTo(StudyArea.HANNOVER);
        }

        @Test
        @DisplayName("setStudyArea updates the value")
        void setStudyAreaEnum() {
            config.setStudyArea(StudyArea.LAUSITZ_HOYERSWERDA);
            assertThat(config.getStudyArea()).isEqualTo(StudyArea.LAUSITZ_HOYERSWERDA);
        }

        @Test
        @DisplayName("setStudyAreaAsString is case-insensitive")
        void setStudyAreaString() {
            config.setStudyAreaAsString("lausitz_hoyerswerda");
            assertThat(config.getStudyArea()).isEqualTo(StudyArea.LAUSITZ_HOYERSWERDA);
        }
    }
```

> The correctness of the *path* re-derivation (network input path now under `hagrid-input/lausitz`) is covered by Task 2's `HagridPathsTest.StudyAreaScoping`; Task 3 only verifies the field/setter plumbing and that `setStudyArea` re-runs `deriveInputPaths()` against a freshly study-area-scoped `HagridPaths`.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl parcel-demand-2-matsim-pipeline test -Dtest=HagridConfigTest`
Expected: FAIL — `getStudyArea()`/`setStudyArea(...)` do not exist.

- [ ] **Step 3: Write minimal implementation**

In `HagridConfig.java`:

(a) Add import:

```java
import hagrid.utils.general.StudyArea;
```

(b) Add a field in the SCENARIO SETTINGS block (next to `private Set<Region> filterRegions = ...;`):

```java
    private StudyArea studyArea = StudyArea.HANNOVER;
```

(c) Change the `hagridPaths` field from `final` and construct it with the study area:

```java
    private HagridPaths hagridPaths = new HagridPaths(StudyArea.HANNOVER);
```

(d) Extract the path-derivation out of `initializeDefaults()` into a reusable method, and call it. Replace the body of `initializeDefaults()` so it reads:

```java
    private void initializeDefaults() {
        providers.initializeDefaultRates(scenario);
        vehicles.initializeDefaultDispatchHours();
        deriveInputPaths();
    }

    /** (Re)derive all input file paths from the current {@link HagridPaths}. */
    private void deriveInputPaths() {
        inputPaths.setNetwork(hagridPaths.networkFile());
        inputPaths.setVehicleTypes(hagridPaths.vehicleTypesFile());
        inputPaths.setHubData(hagridPaths.hubDataFile());
        inputPaths.setShippingPoints(hagridPaths.shippingPointsDir());
        inputPaths.setParcelLockers(hagridPaths.parcelLockersFile());
    }
```

(e) Add the accessors/setters in the SCENARIO SETTINGS accessor area (next to `getScenario()` / `setScenario(...)`):

```java
    public StudyArea getStudyArea() { return studyArea; }

    /** Sets the study area, rebuilds the path resolver, and re-derives input paths. */
    public void setStudyArea(StudyArea studyArea) {
        this.studyArea = studyArea;
        this.hagridPaths = new HagridPaths(studyArea);
        deriveInputPaths();
    }

    /** ScenarioRunner entry point: parse a study-area name (case-insensitive). */
    public void setStudyAreaAsString(String studyArea) {
        setStudyArea(StudyArea.valueOf(studyArea.trim().toUpperCase()));
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -pl parcel-demand-2-matsim-pipeline test -Dtest=HagridConfigTest`
Expected: PASS — existing config tests plus the new `StudyAreaSettings` ones.

- [ ] **Step 5: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/src/main/java/hagrid/HagridConfig.java \
        parcel-demand-2-matsim-pipeline/src/test/java/hagrid/HagridConfigTest.java
git commit -m "feat(config): thread StudyArea through HagridConfig (re-derives input paths)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: `IntegratedScenarioConfig` (parameters + autonomy switch)

**Files:**
- Create: `parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/IntegratedScenarioConfig.java`
- Test: `parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/IntegratedScenarioConfigTest.java`

**Interfaces:**
- Produces: `hagrid.integrated.IntegratedScenarioConfig` with a builder, defaults from the design spec, and the §4.4 operation-mode helpers:
  - `enum OperationMode { CONVENTIONAL, AUTONOMOUS }`
  - getters for all params (see code)
  - `double effectiveLabourCostPerHour()` — `0.0` when AUTONOMOUS, else `cargoLabourCostPerHour`
  - `double effectiveDeliveryDwellFactor()` — `deliveryDwellFactorAutonomous` when AUTONOMOUS, else `1.0`
  - `OptionalDouble effectiveMaxSpeedMps()` — the AV cap when AUTONOMOUS, else empty (network limits)
  - `List<String> effectiveExcludedRoadTypes()` — the excluded set when AUTONOMOUS, else empty
- Consumed by sub-plans 1c (Shared-Use) and 1d (Modular).

- [ ] **Step 1: Write the failing test**

Create `IntegratedScenarioConfigTest.java`:

```java
package hagrid.integrated;

import hagrid.integrated.IntegratedScenarioConfig.OperationMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

@DisplayName("IntegratedScenarioConfig")
class IntegratedScenarioConfigTest {

    @Nested
    @DisplayName("Defaults")
    class Defaults {
        @Test
        @DisplayName("spec-grounded defaults")
        void defaults() {
            IntegratedScenarioConfig c = IntegratedScenarioConfig.builder().build();
            assertThat(c.getOperationMode()).isEqualTo(OperationMode.CONVENTIONAL);
            assertThat(c.getRetoolingTimeSeconds()).isEqualTo(420.0);   // 7 min
            assertThat(c.getIdleThreshold()).isEqualTo(0.50);
            assertThat(c.getAutonomousMaxSpeedKmh()).isEqualTo(30.0);
            assertThat(c.getDepotCount()).isEqualTo(3);
            assertThat(c.getExcludedRoadTypes()).containsExactly("motorway", "motorway_link");
        }
    }

    @Nested
    @DisplayName("Operation mode helpers (spec section 4.4)")
    class OperationModeHelpers {
        @Test
        @DisplayName("CONVENTIONAL: labour on, dwell x1, no speed cap, no road exclusion")
        void conventional() {
            IntegratedScenarioConfig c = IntegratedScenarioConfig.builder()
                    .operationMode(OperationMode.CONVENTIONAL)
                    .cargoLabourCostPerHour(20.0)
                    .build();
            assertThat(c.effectiveLabourCostPerHour()).isEqualTo(20.0);
            assertThat(c.effectiveDeliveryDwellFactor()).isEqualTo(1.0);
            assertThat(c.effectiveMaxSpeedMps()).isEmpty();
            assertThat(c.effectiveExcludedRoadTypes()).isEmpty();
        }

        @Test
        @DisplayName("AUTONOMOUS: labour off, dwell stretched, speed capped, motorways excluded")
        void autonomous() {
            IntegratedScenarioConfig c = IntegratedScenarioConfig.builder()
                    .operationMode(OperationMode.AUTONOMOUS)
                    .cargoLabourCostPerHour(20.0)
                    .deliveryDwellFactorAutonomous(1.5)
                    .autonomousMaxSpeedKmh(30.0)
                    .build();
            assertThat(c.effectiveLabourCostPerHour()).isZero();
            assertThat(c.effectiveDeliveryDwellFactor()).isEqualTo(1.5);
            assertThat(c.effectiveMaxSpeedMps()).hasValue(30.0 / 3.6);
            assertThat(c.effectiveExcludedRoadTypes()).containsExactly("motorway", "motorway_link");
        }
    }

    @Nested
    @DisplayName("Validation")
    class Validation {
        @Test
        @DisplayName("idleThreshold must be within [0,1]")
        void idleThresholdRange() {
            assertThatThrownBy(() -> IntegratedScenarioConfig.builder().idleThreshold(1.5).build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("idleThreshold");
        }

        @Test
        @DisplayName("depotCount must be >= 1")
        void depotCountPositive() {
            assertThatThrownBy(() -> IntegratedScenarioConfig.builder().depotCount(0).build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("depotCount");
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl parcel-demand-2-matsim-pipeline test -Dtest=IntegratedScenarioConfigTest`
Expected: FAIL — class does not exist.

- [ ] **Step 3: Write minimal implementation**

Create `IntegratedScenarioConfig.java`:

```java
package hagrid.integrated;

import java.util.List;
import java.util.OptionalDouble;

/**
 * Parameters for the integrated DRT-freight scenarios (Shared-Use, Modular).
 *
 * <p>Defaults are taken from the design spec
 * (docs/superpowers/specs/2026-06-17-lausitz-drt-freight-integration-design.md). Many are
 * calibration levers. The autonomy "operation mode" (spec section 4.4) is an orthogonal switch
 * whose four coupled effects — labour cost, delivery dwell factor, max-speed cap, motorway
 * exclusion — are exposed via the {@code effective*} helpers so callers never branch on the mode.</p>
 */
public final class IntegratedScenarioConfig {

    /** Conventional = human aboard (driver/attendant); Autonomous = driverless (spec section 4.4). */
    public enum OperationMode { CONVENTIONAL, AUTONOMOUS }

    private final OperationMode operationMode;
    private final double cargoLabourCostPerHour;   // EUR/h, applied when CONVENTIONAL
    private final double vehicleTimeCostPerHour;    // EUR/h, technical (energy/capital/maintenance)
    private final double deliveryDwellFactorAutonomous; // >1.0, robot is slower at the door
    private final double autonomousMaxSpeedKmh;     // vehicle maximumVelocity cap when AUTONOMOUS
    private final List<String> excludedRoadTypes;   // road classes barred when AUTONOMOUS
    private final double retoolingTimeSeconds;      // Modular capsule swap (pure swap only)
    private final double freightLookAheadSeconds;   // Modular submission look-ahead base
    private final double idleThreshold;             // Modular passenger-first dispatch gate [0,1]
    private final int depotCount;                   // parameterised depots (pickup / swap)
    private final double b2cLockerShare;            // Shared-Use B2C share routed to Packstation [0,1]
    private final int fleetSize;                    // calibration lever

    private IntegratedScenarioConfig(Builder b) {
        this.operationMode = b.operationMode;
        this.cargoLabourCostPerHour = b.cargoLabourCostPerHour;
        this.vehicleTimeCostPerHour = b.vehicleTimeCostPerHour;
        this.deliveryDwellFactorAutonomous = b.deliveryDwellFactorAutonomous;
        this.autonomousMaxSpeedKmh = b.autonomousMaxSpeedKmh;
        this.excludedRoadTypes = List.copyOf(b.excludedRoadTypes);
        this.retoolingTimeSeconds = b.retoolingTimeSeconds;
        this.freightLookAheadSeconds = b.freightLookAheadSeconds;
        this.idleThreshold = b.idleThreshold;
        this.depotCount = b.depotCount;
        this.b2cLockerShare = b.b2cLockerShare;
        this.fleetSize = b.fleetSize;
    }

    // --- raw getters ---
    public OperationMode getOperationMode() { return operationMode; }
    public double getCargoLabourCostPerHour() { return cargoLabourCostPerHour; }
    public double getVehicleTimeCostPerHour() { return vehicleTimeCostPerHour; }
    public double getDeliveryDwellFactorAutonomous() { return deliveryDwellFactorAutonomous; }
    public double getAutonomousMaxSpeedKmh() { return autonomousMaxSpeedKmh; }
    public List<String> getExcludedRoadTypes() { return excludedRoadTypes; }
    public double getRetoolingTimeSeconds() { return retoolingTimeSeconds; }
    public double getFreightLookAheadSeconds() { return freightLookAheadSeconds; }
    public double getIdleThreshold() { return idleThreshold; }
    public int getDepotCount() { return depotCount; }
    public double getB2cLockerShare() { return b2cLockerShare; }
    public int getFleetSize() { return fleetSize; }

    // --- operation-mode helpers (spec section 4.4) ---
    public boolean isAutonomous() { return operationMode == OperationMode.AUTONOMOUS; }

    /** Delivery labour EUR/h: zero when autonomous, else the configured rate. */
    public double effectiveLabourCostPerHour() {
        return isAutonomous() ? 0.0 : cargoLabourCostPerHour;
    }

    /** Per-stop dwell multiplier: stretched (robot) when autonomous, else 1.0. */
    public double effectiveDeliveryDwellFactor() {
        return isAutonomous() ? deliveryDwellFactorAutonomous : 1.0;
    }

    /** Vehicle max speed cap in m/s when autonomous; empty = follow network/road limits. */
    public OptionalDouble effectiveMaxSpeedMps() {
        return isAutonomous() ? OptionalDouble.of(autonomousMaxSpeedKmh / 3.6) : OptionalDouble.empty();
    }

    /** Road classes barred from routing when autonomous; empty when conventional. */
    public List<String> effectiveExcludedRoadTypes() {
        return isAutonomous() ? excludedRoadTypes : List.of();
    }

    public static Builder builder() { return new Builder(); }

    /** Mutable builder with spec-grounded defaults and validation on build(). */
    public static final class Builder {
        private OperationMode operationMode = OperationMode.CONVENTIONAL;
        private double cargoLabourCostPerHour = 20.0;   // ~80% of 25 EUR/h gross (Rudolph anchor)
        private double vehicleTimeCostPerHour = 5.0;     // ~20% technical
        private double deliveryDwellFactorAutonomous = 1.5; // provisional; calibration lever
        private double autonomousMaxSpeedKmh = 30.0;     // U-Shift floor; sensitivity to 50
        private List<String> excludedRoadTypes = List.of("motorway", "motorway_link");
        private double retoolingTimeSeconds = 420.0;     // 7 min pure swap
        private double freightLookAheadSeconds = 420.0;  // base; effective = approach + swap
        private double idleThreshold = 0.50;             // Paper 1 starting point
        private int depotCount = 3;                       // 2-3 parameterised depots
        private double b2cLockerShare = 0.7;             // provisional; calibration lever
        private int fleetSize = 50;                       // calibration lever (P95 <= 7 min)

        public Builder operationMode(OperationMode v) { this.operationMode = v; return this; }
        public Builder cargoLabourCostPerHour(double v) { this.cargoLabourCostPerHour = v; return this; }
        public Builder vehicleTimeCostPerHour(double v) { this.vehicleTimeCostPerHour = v; return this; }
        public Builder deliveryDwellFactorAutonomous(double v) { this.deliveryDwellFactorAutonomous = v; return this; }
        public Builder autonomousMaxSpeedKmh(double v) { this.autonomousMaxSpeedKmh = v; return this; }
        public Builder excludedRoadTypes(List<String> v) { this.excludedRoadTypes = v; return this; }
        public Builder retoolingTimeSeconds(double v) { this.retoolingTimeSeconds = v; return this; }
        public Builder freightLookAheadSeconds(double v) { this.freightLookAheadSeconds = v; return this; }
        public Builder idleThreshold(double v) { this.idleThreshold = v; return this; }
        public Builder depotCount(int v) { this.depotCount = v; return this; }
        public Builder b2cLockerShare(double v) { this.b2cLockerShare = v; return this; }
        public Builder fleetSize(int v) { this.fleetSize = v; return this; }

        public IntegratedScenarioConfig build() {
            require(idleThreshold >= 0.0 && idleThreshold <= 1.0, "idleThreshold must be in [0,1]");
            require(b2cLockerShare >= 0.0 && b2cLockerShare <= 1.0, "b2cLockerShare must be in [0,1]");
            require(depotCount >= 1, "depotCount must be >= 1");
            require(fleetSize >= 1, "fleetSize must be >= 1");
            require(retoolingTimeSeconds >= 0.0, "retoolingTimeSeconds must be >= 0");
            require(deliveryDwellFactorAutonomous >= 1.0, "deliveryDwellFactorAutonomous must be >= 1.0");
            require(autonomousMaxSpeedKmh > 0.0, "autonomousMaxSpeedKmh must be > 0");
            return new IntegratedScenarioConfig(this);
        }

        private static void require(boolean ok, String msg) {
            if (!ok) throw new IllegalArgumentException(msg);
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl parcel-demand-2-matsim-pipeline test -Dtest=IntegratedScenarioConfigTest`
Expected: PASS (defaults + 2 mode helpers + 2 validation tests).

- [ ] **Step 5: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/IntegratedScenarioConfig.java \
        parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/IntegratedScenarioConfigTest.java
git commit -m "feat(integrated): add IntegratedScenarioConfig with operation-mode (autonomy) switch

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: `DepotNetwork` (depots + nearest-depot assignment)

**Files:**
- Create: `parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/DepotNetwork.java`
- Test: `parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/DepotNetworkTest.java`

**Interfaces:**
- Consumes: `org.matsim.api.core.v01.Coord`, `org.matsim.core.utils.geometry.CoordUtils`.
- Produces: `hagrid.integrated.DepotNetwork` holding a non-empty, immutable list of `DepotNetwork.Depot` (record with `String id`, `Coord coord`); method `Depot nearestDepot(Coord)` returning the Euclidean-nearest depot; `List<Depot> depots()`. Consumed by sub-plans 1c (Shared-Use parcel pickup) and 1d (Modular capsule swap).

- [ ] **Step 1: Write the failing test**

Create `DepotNetworkTest.java`:

```java
package hagrid.integrated;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

@DisplayName("DepotNetwork")
class DepotNetworkTest {

    private static final DepotNetwork.Depot A = new DepotNetwork.Depot("depot_A", new Coord(0.0, 0.0));
    private static final DepotNetwork.Depot B = new DepotNetwork.Depot("depot_B", new Coord(1000.0, 0.0));
    private static final DepotNetwork.Depot C = new DepotNetwork.Depot("depot_C", new Coord(0.0, 1000.0));

    @Test
    @DisplayName("nearestDepot returns the closest depot by Euclidean distance")
    void nearest() {
        DepotNetwork net = new DepotNetwork(List.of(A, B, C));
        assertThat(net.nearestDepot(new Coord(900.0, 50.0))).isEqualTo(B);
        assertThat(net.nearestDepot(new Coord(10.0, 10.0))).isEqualTo(A);
        assertThat(net.nearestDepot(new Coord(50.0, 900.0))).isEqualTo(C);
    }

    @Test
    @DisplayName("depots() is the immutable configured list")
    void depotsImmutable() {
        DepotNetwork net = new DepotNetwork(List.of(A, B));
        assertThat(net.depots()).containsExactly(A, B);
        assertThatThrownBy(() -> net.depots().add(C))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("an empty depot list is rejected")
    void rejectsEmpty() {
        assertThatThrownBy(() -> new DepotNetwork(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one depot");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl parcel-demand-2-matsim-pipeline test -Dtest=DepotNetworkTest`
Expected: FAIL — class does not exist.

- [ ] **Step 3: Write minimal implementation**

Create `DepotNetwork.java`:

```java
package hagrid.integrated;

import org.matsim.api.core.v01.Coord;
import org.matsim.core.utils.geometry.CoordUtils;

import java.util.List;

/**
 * The set of parameterised depots in/around the study area. Depots serve as parcel-pickup origins
 * (Shared-Use) and capsule-swap points (Modular). Parcels/requests are assigned to the nearest depot.
 */
public final class DepotNetwork {

    /** A single depot location. */
    public record Depot(String id, Coord coord) { }

    private final List<Depot> depots;

    public DepotNetwork(List<Depot> depots) {
        if (depots == null || depots.isEmpty()) {
            throw new IllegalArgumentException("DepotNetwork requires at least one depot");
        }
        this.depots = List.copyOf(depots);
    }

    /** Immutable list of configured depots. */
    public List<Depot> depots() {
        return depots;
    }

    /** Returns the depot with the smallest Euclidean distance to {@code coord}. */
    public Depot nearestDepot(Coord coord) {
        Depot best = null;
        double bestDist = Double.POSITIVE_INFINITY;
        for (Depot d : depots) {
            double dist = CoordUtils.calcEuclideanDistance(coord, d.coord());
            if (dist < bestDist) {
                bestDist = dist;
                best = d;
            }
        }
        return best;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl parcel-demand-2-matsim-pipeline test -Dtest=DepotNetworkTest`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/DepotNetwork.java \
        parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/DepotNetworkTest.java
git commit -m "feat(integrated): add DepotNetwork with nearest-depot assignment

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: Full regression — all modules compile and all tests pass

**Files:** none (verification only).

- [ ] **Step 1: Install freight, run the full parcel test suite**

Run:
```bash
mvn -pl freight install -DskipTests
mvn -pl parcel-demand-2-matsim-pipeline test
```
Expected: BUILD SUCCESS. Tests run = 155 (existing) + new (StudyArea 3, HagridPaths +4, HagridConfig +4, IntegratedScenarioConfig 5, DepotNetwork 3), Failures: 0, Errors: 0. Confirms the foundation is in place and **all existing behaviour is preserved**.

- [ ] **Step 2: No commit** (verification task; all code already committed in Tasks 1–5).

---

## Out of Scope / Follow-on Plans

These are deliberately **not** in this plan; each becomes its own plan once 1a is merged. They require a focused investigation of `RunLausitzDrtScenario` / `DrtAndIntermodalityOptions` and the drt/dvrp API before they can be written to no-placeholder detail.

- **1b — Lausitz wiring & DRT composition:** add `DRT_BASELINE/DRT_SHAREDUSE/DRT_MODULAR` to the `Scenario` enum; DRT_*↔`LAUSITZ_HOYERSWERDA` runtime validation; thread `studyArea` through `ScenarioConfig.Builder` + `ScenarioRunner` (mirror `filterRegions`); compose the native Lausitz DRT config (reuse `RunLausitzDrtScenario` / `input/drt-area`); clip network + 100% population to the study area; study-area-scoped shared simulation inputs.
- **1c — Shared-Use:** `ParcelRequest` (segment-aggregated stops, no jsprit), `SplitCapacityVehicle`, `DeliveryChannelResolver` (B2B→door, B2C→Packstation→door), `SharedUseDispatchLogic` (online insertion, static acceptance).
- **1d — Modular:** `FreightTourRequest(Creator)`, `ModularDispatchLogic` (idle-threshold gate), `CapsuleSwapActivity` (retooling = pure swap; look-ahead = approach + swap).
- **1e — KPIs & dashboard:** `IntegratedKPIHandler` (event-driven, mirror `FreightEventHandler`) + canonical KPI CSV (long + wide, spec section 7) + dashboard extension.
- **Reproducible dependency (parallel track):** fork matsim-lausitz + add `jitpack.yml` (`jdk: openjdk21`) so CI/other machines consume it via JitPack instead of a manual local `mvn install`.
