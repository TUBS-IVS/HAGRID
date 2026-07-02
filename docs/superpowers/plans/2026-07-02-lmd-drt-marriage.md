# LMD + DRT Marriage (One Controler) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Run the Lausitz Baseline as ONE MATSim run — passenger DRT (fleet, rail-PT, fares, depot dispatching) and the multi-LSP LMD van fleet (offline jsprit tours) in a single `Controler`, replacing today's two separate simulations.

**Architecture:** The DRT composition path (`DrtScenarioBuilder` → `LausitzDrtConfigurator` → `DrtConfigComposer`) stays the config backbone. The LMD side is grafted on: `LausitzFreightPreprocessor` routes the carriers offline (unchanged), then a new `FreightRunComposer` loads the routed carriers into the DRT scenario and installs `CarrierModule` + the two Guice bindings it needs. A new `freight=true|false` run option gates the graft; `freight=false` reproduces today's passenger-only DRT run bit-for-bit. `LMD_BASELINE` (freight-only, maxIter=0) stays untouched as a diagnostic scenario.

**Tech Stack:** MATSim 2025.0 (freight contrib `org.matsim.freight.carriers`, drt/dvrp contribs), jsprit via `HAGRIDRouterUtils`, JUnit 5 + AssertJ + `MatsimTestUtils`.

## Why this is not just "install two modules" — the three load-bearing findings

1. **Empty CarrierStrategyManager crashes at replanning.** The LMD branch binds
   `CarrierControllerUtils.createDefaultCarrierStrategyManager()` — verified via bytecode: that is
   `new CarrierStrategyManagerImpl()` with **zero strategies**, and
   `CarrierControllerListener.notifyReplanning` → `GenericStrategyManagerImpl.run` **throws
   `RuntimeException` when `chooseStrategy` returns null** (no strategies registered). Today this
   never fires because LMD runs `maxIter=0` (replanning never happens). The married run has
   `maxIter=150` → the run would crash entering iteration 1. Fix: a manager with exactly one
   `GenericPlanStrategyImpl<>(new KeepSelected<>())` strategy — carrier plans stay FIXED across
   iterations (offline jsprit is the Baseline definition), passengers replan normally.
2. **Two different networks, one QSim.** jsprit routes carriers on the **car-filtered raw network**
   (`LausitzFreightPreprocessor.carNetwork`: car-mode filter + `NetworkCleaner`); the married QSim runs
   on the **drt-tagged network** (`getDrtNetworkClipped()` = full network with `drt` added to in-area
   car links; no links deleted). Carrier routes reference link IDs, so every routed link must exist in
   the married network. Expected to hold by construction (car-component ⊂ full network) — the e2e test
   asserts it so a future network-prep change cannot silently break replay.
3. **No-freight hardcodings in the DRT path are all compatible — verified.** The
   `longDistanceFreight` removals in `LausitzDrtConfigurator` (qsim mainModes / routing networkModes /
   travelTimeCalculator) target the *native Lausitz long-haul subpopulation mode*, NOT our vans: LMD
   van types drive `networkMode="car"` (verified in `lmd-vehicle-types.xml`). The preprocessor's
   person-only population filter is also fine — carrier agents enter the QSim via `CarrierModule`'s
   agent source, not via the population. `simStarttimeInterpretation=onlyUseStarttime` (set by
   `DrtConfigComposer`) is what the carrier waves need anyway.

## Decisions locked for this plan (user can veto before execution)

- **D1 — `DRT_BASELINE` becomes the married Baseline, default `freight=true`.** This is
  spec-faithful (design spec §5.4 always defined DRT_BASELINE as "Multi-LSP jsprit plans … + native
  DRT"). `freight=false` gives the previous passenger-only run for diagnostics. `LMD_BASELINE`
  unchanged. The scenario-naming sweep the user wants stays a separate, later housekeeping task.
- **D2 — carriers do not replan.** One offline jsprit solve in preprocessing; `KeepSelected` across
  all 150 iterations. Consequence (report as method note): jsprit plans against free-flow travel
  times, execution happens in mixed traffic — planned-vs-executed tour duration becomes an honest KPI,
  not an error.
- **D3 — one output directory, both dashboards.** The married run dir contains `drt_*` files AND
  `output_carriers.xml.gz` + freight events; the legacy `DashboardGenerator` (LMD) and
  `build_drt_dashboard.py` (DRT) both read from it. No merged mega-dashboard in this plan (that is 1e).
- **D4 — the pending "DRT re-run with fleet 120" and the married headline run collapse into ONE
  overnight run** (`fleetSize=120, freight=true, maxIter=150`): it answers the supply-cap/peak
  question, activates fares + capacity-bounded depot return, and produces the married Baseline in a
  single ~8.5 h run.

## Global Constraints

- 100 % sample is NON-NEGOTIABLE (no population subsampling).
- Branch `hendrik`; never merge to `master` without asking; commit per task.
- Full test suite must stay green (currently 261/0/0): `mvn -q test` in `parcel-demand-2-matsim-pipeline/`.
- Hannover paths (`BASECASE` etc.) byte-for-byte untouched; `LMD_BASELINE` behavior unchanged.
- jsprit route-duration cap stays `HAGRIDRouterUtils.MAXROUTEDURATION` (7 h, public).
- Windows: use `python -u`, ASCII-only in `print()`; NEVER edit `.bat` files with Edit/Write (CRLF) — use PowerShell `WriteAllLines`.
- No non-ASCII characters in new Java log/exception strings (cp1252 console).
- Test-resource shapefiles need `git add -f` (`.gitignore` line 90 ignores `*.shp` globally).

## File Structure

| File | Action | Responsibility |
|---|---|---|
| `src/main/java/hagrid/simulation/HAGRIDSimulationConfig.java` | Modify | new `drtWithFreight` field + 11-arg ctor + `isDrtWithFreight()` + input validation |
| `src/main/java/hagrid/simulation/SimulationRunnerUtils.java` | Modify | parse `freight=` token; married branch in `runSimulation`; LMD branch refactored onto the composer |
| `src/main/java/hagrid/integrated/freight/FreightRunComposer.java` | Create | load routed carriers into a scenario + install CarrierModule/scoring/KeepSelected strategy (shared LMD-only ↔ married) |
| `src/test/java/hagrid/simulation/ParseScenarioDrtFreightTest.java` | Create | `freight` flag parsing + defaults |
| `src/test/java/hagrid/integrated/freight/FreightRunComposerTest.java` | Create | composer unit tests |
| `src/test/java/hagrid/integrated/freight/LmdTestShapefiles.java` | Modify | `final class` → `public final class`, `static` methods → `public static` (reused cross-package) |
| `src/test/java/hagrid/integrated/drt/DrtE2eFixtures.java` | Create | grid/demand/shapefile fixtures extracted from `DrtBaselineEndToEndTest` |
| `src/test/java/hagrid/integrated/drt/DrtBaselineEndToEndTest.java` | Modify | use `DrtE2eFixtures` (no behavior change) |
| `src/test/java/hagrid/integrated/drt/MarriedBaselineEndToEndTest.java` | Create | THE gate: real `Controler.run()` with DRT + carriers + `lastIteration=1` (replanning fires) |
| `src/main/java/hagrid/analysis/FreightEventHandler.java` | Modify | `isFreight` guard on service/tour-boundary activity branches (married events contain pax + DRT acts) |
| `src/test/java/hagrid/analysis/FreightEventHandlerMixedTrafficTest.java` | Create | non-freight agents' events are ignored |

---

### Task 1: `freight` run option (config + parsing + input validation)

**Files:**
- Modify: `parcel-demand-2-matsim-pipeline/src/main/java/hagrid/simulation/HAGRIDSimulationConfig.java` (ctor block ~lines 111–180, getters ~line 401, `validateInputFiles` ~lines 556–569)
- Modify: `parcel-demand-2-matsim-pipeline/src/main/java/hagrid/simulation/SimulationRunnerUtils.java` (`parseScenario`, lines ~129–180)
- Test: `parcel-demand-2-matsim-pipeline/src/test/java/hagrid/simulation/ParseScenarioDrtFreightTest.java`

**Interfaces:**
- Consumes: existing `HAGRIDSimulationConfig` 10-arg constructor, `bool(...)` parse helper in `SimulationRunnerUtils`.
- Produces: `HAGRIDSimulationConfig(String concept, LocalDate date, int maxIterations, int jspritIterations, boolean zoneBasedCachingEnabled, double zoneBasedCachingThresholdMeters, double uTurnPenaltyCost, String tag, StudyArea studyArea, int fleetSize, boolean drtWithFreight)` (11-arg) and `public boolean isDrtWithFreight()` — Task 3 branches on it. Spec token: `freight=true|false`, default `true`.

- [ ] **Step 1: Write the failing tests**

```java
package hagrid.simulation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("freight= run option (married DRT_BASELINE gate)")
class ParseScenarioDrtFreightTest {

    @Test
    @DisplayName("DRT_BASELINE defaults to freight=true (married is the spec baseline)")
    void drtDefaultsToFreightOn() {
        HAGRIDSimulationConfig cfg = SimulationRunnerUtils.parseScenario(
                "concept=DRT_BASELINE,date=2025-05-13,maxIter=1");
        assertThat(cfg.isDrtWithFreight()).isTrue();
    }

    @Test
    @DisplayName("freight=false yields the passenger-only DRT run")
    void freightOffParsed() {
        HAGRIDSimulationConfig cfg = SimulationRunnerUtils.parseScenario(
                "concept=DRT_BASELINE,date=2025-05-13,maxIter=1,freight=false");
        assertThat(cfg.isDrtWithFreight()).isFalse();
    }

    @Test
    @DisplayName("isDrtWithFreight is false for non-DRT concepts even with freight=true")
    void lmdIgnoresFreightFlag() {
        HAGRIDSimulationConfig cfg = SimulationRunnerUtils.parseScenario(
                "concept=LMD_BASELINE,date=2025-05-13,maxIter=0,freight=true");
        assertThat(cfg.isDrtWithFreight()).isFalse();
    }

    @Test
    @DisplayName("10-arg constructor keeps married default (true) for DRT concepts")
    void tenArgCtorDefaultsTrue() {
        HAGRIDSimulationConfig cfg = new HAGRIDSimulationConfig(
                "DRT_BASELINE", java.time.LocalDate.of(2025, 5, 13), 1, 1,
                false, 0.0, 0.0, "", hagrid.StudyArea.LAUSITZ_HOYERSWERDA, 80);
        assertThat(cfg.isDrtWithFreight()).isTrue();
    }
}
```

Note: if `StudyArea`'s package is not `hagrid` (check the import used at the top of
`HAGRIDSimulationConfig.java` and copy it), fix the import in the test accordingly.

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q test -Dtest=ParseScenarioDrtFreightTest -pl parcel-demand-2-matsim-pipeline`
Expected: COMPILE ERROR — `isDrtWithFreight()` undefined.

- [ ] **Step 3: Implement `HAGRIDSimulationConfig` changes**

Add the field next to `fleetSize` (~line 97):

```java
    /**
     * Whether a DRT run also carries the offline-routed LMD carriers in the SAME Controler
     * (the "married" Baseline). Only meaningful when {@link #isDrtScenario()} is true.
     */
    private final boolean drtWithFreight;
```

Change the existing 10-arg constructor body (line ~133) into a delegation and add the 11-arg one:

```java
    public HAGRIDSimulationConfig(String concept, LocalDate date, int maxIterations, int jspritIterations,
                          boolean zoneBasedCachingEnabled, double zoneBasedCachingThresholdMeters,
                          double uTurnPenaltyCost, String tag, StudyArea studyArea, int fleetSize) {
        this(concept, date, maxIterations, jspritIterations,
                zoneBasedCachingEnabled, zoneBasedCachingThresholdMeters,
                uTurnPenaltyCost, tag, studyArea, fleetSize, /*drtWithFreight*/ true);
    }

    public HAGRIDSimulationConfig(String concept, LocalDate date, int maxIterations, int jspritIterations,
                          boolean zoneBasedCachingEnabled, double zoneBasedCachingThresholdMeters,
                          double uTurnPenaltyCost, String tag, StudyArea studyArea, int fleetSize,
                          boolean drtWithFreight) {
        // ... (move the ENTIRE existing 10-arg body here, unchanged) ...
        this.drtWithFreight = drtWithFreight;
    }
```

Add the getter next to `isLmdBaseline()` (~line 417):

```java
    /** True iff this is a DRT scenario that also carries the LMD carriers (married Baseline). */
    public boolean isDrtWithFreight() {
        return isDrtScenario() && drtWithFreight;
    }
```

Extend `validateInputFiles()` inside the existing `if (isDrtScenario())` block (after the
rail checks, ~line 568):

```java
            if (isDrtWithFreight()) {
                // Married baseline additionally needs the LMD preprocessing inputs.
                checkFile(Path.of(getLmdDemandShapefile()), "LMD demand shapefile", missing);
                checkFile(Path.of(getLmdVehicleTypes()), "LMD vehicle types", missing);
                checkFile(Path.of(getLausitzNetworkRaw()), "Lausitz network (raw, jsprit routing)", missing);
            }
```

(The depot CSV is already checked in the DRT branch.)

- [ ] **Step 4: Implement `parseScenario` change**

In `SimulationRunnerUtils.parseScenario`, after the `fleetSize` line (~line 140):

```java
        boolean drtWithFreight = bool(map.getOrDefault("freight", "true"), "freight");
```

Extend the log line (~line 175) with ` freight={}` / `drtWithFreight`, and change the return
(~line 178) to the 11-arg constructor:

```java
        return new HAGRIDSimulationConfig(concept, date, maxIter, jspritIter,
                zoneCaching, zoneThreshold, uTurnPenaltyCost, tag, studyArea, fleetSize,
                drtWithFreight);
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn -q test -Dtest=ParseScenarioDrtFreightTest -pl parcel-demand-2-matsim-pipeline`
Expected: 4 tests PASS.

- [ ] **Step 6: Run the full suite (regression gate)**

Run: `mvn -q test -pl parcel-demand-2-matsim-pipeline`
Expected: all green (261+4).

- [ ] **Step 7: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/src/main/java/hagrid/simulation/HAGRIDSimulationConfig.java parcel-demand-2-matsim-pipeline/src/main/java/hagrid/simulation/SimulationRunnerUtils.java parcel-demand-2-matsim-pipeline/src/test/java/hagrid/simulation/ParseScenarioDrtFreightTest.java
git commit -m "feat(marriage): freight= run option gating LMD carriers in DRT runs"
```

---

### Task 2: `FreightRunComposer` — shared carrier-composition + the KeepSelected fix

**Files:**
- Create: `parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/freight/FreightRunComposer.java`
- Modify: `parcel-demand-2-matsim-pipeline/src/main/java/hagrid/simulation/SimulationRunnerUtils.java` (LMD branch, lines ~274–297)
- Test: `parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/freight/FreightRunComposerTest.java`

**Interfaces:**
- Consumes: `FreightCarriersConfigGroup`, `CarriersUtils.loadCarriersAccordingToFreightConfig(Scenario)`, `CarrierModule`, `hagrid.simulation.ScoringFunctions(Network, double)`, `CarrierControllerUtils.createDefaultCarrierStrategyManager()`.
- Produces (Task 3 relies on these exact signatures):
  - `public static void addCarriers(Scenario scenario, String carriersFile, String vehicleTypesFile)`
  - `public static void installCarrierModules(Controler controler, Scenario scenario)`
  - `public static CarrierStrategyManager keepSelectedStrategyManager()`

- [ ] **Step 1: Write the failing tests**

```java
package hagrid.integrated.freight;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.freight.carriers.*;
import org.matsim.freight.carriers.controller.CarrierStrategyManager;
import org.matsim.testcases.MatsimTestUtils;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("FreightRunComposer — carriers into a (married) scenario")
class FreightRunComposerTest {

    @RegisterExtension
    public MatsimTestUtils utils = new MatsimTestUtils();

    @Test
    @DisplayName("keepSelectedStrategyManager registers exactly ONE strategy (empty manager would crash replanning)")
    void keepSelectedManagerHasOneStrategy() {
        CarrierStrategyManager manager = FreightRunComposer.keepSelectedStrategyManager();
        assertThat(manager.getStrategies(null))
                .as("married runs iterate (maxIter>0): an empty manager throws at the first replanning")
                .hasSize(1);
    }

    @Test
    @DisplayName("addCarriers loads the routed carriers + vehicle types into the scenario")
    void addCarriersLoadsCarriers() throws Exception {
        Path dir = Path.of(utils.getOutputDirectory());

        // vehicle types XML
        CarrierVehicleTypes types = new CarrierVehicleTypes();
        VehicleType van = VehicleUtils.createVehicleType(Id.create("ct_cep_size_m", VehicleType.class));
        van.getCapacity().setOther(165);
        van.setNetworkMode("car");
        van.getCostInformation().setCostsPerMeter(0.0004).setCostsPerSecond(0.0).setFixedCost(170.0);
        types.getVehicleTypes().put(van.getId(), van);
        Path typesFile = dir.resolve("vans.xml");
        new CarrierVehicleTypeWriter(types).write(typesFile.toString());

        // one carrier with a vehicle + service, written the way the preprocessor writes them
        Carrier carrier = CarriersUtils.createCarrier(Id.create("dhl", Carrier.class));
        carrier.getCarrierCapabilities().setFleetSize(CarrierCapabilities.FleetSize.INFINITE);
        CarriersUtils.addCarrierVehicle(carrier, CarrierVehicle.Builder
                .newInstance(Id.createVehicleId("dhl_van_1"), Id.createLinkId("l0"), van)
                .setEarliestStart(8 * 3600).setLatestEnd(16 * 3600).build());
        CarriersUtils.addService(carrier, CarrierService.Builder
                .newInstance(Id.create("dhl_1", CarrierService.class), Id.createLinkId("l1"))
                .setCapacityDemand(3).build());
        Carriers carriers = new Carriers();
        carriers.addCarrier(carrier);
        Path carriersFile = dir.resolve("carriers.xml");
        CarriersUtils.writeCarriers(carriers, carriersFile.toString());

        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        FreightRunComposer.addCarriers(scenario, carriersFile.toString(), typesFile.toString());

        assertThat(CarriersUtils.getCarriers(scenario).getCarriers())
                .containsKey(Id.create("dhl", Carrier.class));
        assertThat(CarriersUtils.getCarrierVehicleTypes(scenario).getVehicleTypes())
                .containsKey(Id.create("ct_cep_size_m", VehicleType.class));
    }
}
```

API note for the implementer: if `getStrategies(null)` is not on the `CarrierStrategyManager`
interface in this MATSim version, verify with
`javap -p org/matsim/freight/carriers/controller/CarrierStrategyManagerImpl.class` — it IS on the
impl (verified 2026-07-02); adjust the assertion to cast if needed.

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q test -Dtest=FreightRunComposerTest -pl parcel-demand-2-matsim-pipeline`
Expected: COMPILE ERROR — `FreightRunComposer` does not exist.

- [ ] **Step 3: Implement `FreightRunComposer`**

```java
package hagrid.integrated.freight;

import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.replanning.GenericPlanStrategyImpl;
import org.matsim.core.replanning.selectors.KeepSelected;
import org.matsim.freight.carriers.CarriersUtils;
import org.matsim.freight.carriers.FreightCarriersConfigGroup;
import org.matsim.freight.carriers.controller.CarrierControllerUtils;
import org.matsim.freight.carriers.controller.CarrierModule;
import org.matsim.freight.carriers.controller.CarrierScoringFunctionFactory;
import org.matsim.freight.carriers.controller.CarrierStrategyManager;

/**
 * Composes the offline-routed LMD carriers into a MATSim run. Shared by the freight-only
 * {@code LMD_BASELINE} (maxIter=0) and the married {@code DRT_BASELINE} (pax DRT + LMD vans
 * in ONE Controler, maxIter&gt;0).
 *
 * <p>Carrier plans are produced offline by jsprit ({@link LausitzFreightPreprocessor}) and are
 * NOT innovated during the run: the strategy manager carries exactly one
 * {@code KeepSelected} strategy. An empty manager
 * ({@code createDefaultCarrierStrategyManager()} alone) throws
 * {@code RuntimeException} at the first replanning event — which only maxIter=0 runs survive.</p>
 */
public final class FreightRunComposer {

    private FreightRunComposer() {}

    /** Points the freight config group at the routed carriers and loads them into the scenario. */
    public static void addCarriers(Scenario scenario, String carriersFile, String vehicleTypesFile) {
        FreightCarriersConfigGroup freight =
                ConfigUtils.addOrGetModule(scenario.getConfig(), FreightCarriersConfigGroup.class);
        freight.setCarriersFile(carriersFile);
        freight.setCarriersVehicleTypesFile(vehicleTypesFile);
        CarriersUtils.loadCarriersAccordingToFreightConfig(scenario);
    }

    /**
     * Installs {@link CarrierModule} plus the two bindings it requires (scoring + strategy).
     * Safe for iterating runs: the KeepSelected strategy re-selects the fixed jsprit plan.
     */
    public static void installCarrierModules(Controler controler, Scenario scenario) {
        controler.addOverridingModule(new CarrierModule());
        controler.addOverridingModule(new AbstractModule() {
            @Override public void install() {
                bind(CarrierScoringFunctionFactory.class)
                        .toInstance(new hagrid.simulation.ScoringFunctions(scenario.getNetwork(), 0.0));
                bind(CarrierStrategyManager.class).toInstance(keepSelectedStrategyManager());
            }
        });
    }

    /** A carrier strategy manager whose single strategy re-selects the current plan (no innovation). */
    public static CarrierStrategyManager keepSelectedStrategyManager() {
        CarrierStrategyManager manager = CarrierControllerUtils.createDefaultCarrierStrategyManager();
        manager.addStrategy(new GenericPlanStrategyImpl<>(new KeepSelected<>()), null, 1.0);
        return manager;
    }
}
```

- [ ] **Step 4: Refactor the LMD branch onto the composer**

In `SimulationRunnerUtils.runSimulation`, LMD branch: keep the config creation (lines 267–273)
but replace the freight-config block + Guice block (lines 274–297) with:

```java
            Scenario scenario = ScenarioUtils.loadScenario(config);
            hagrid.integrated.freight.FreightRunComposer.addCarriers(
                    scenario, cfg.getLmdCarriersRouted(), cfg.getLmdVehicleTypes());

            Controler controler = new Controler(scenario);
            hagrid.integrated.freight.FreightRunComposer.installCarrierModules(controler, scenario);
```

(Behavioral delta for LMD_BASELINE: the strategy manager now carries one KeepSelected strategy
instead of none — invisible at maxIter=0, and strictly safer.)

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn -q test -Dtest=FreightRunComposerTest -pl parcel-demand-2-matsim-pipeline`
Expected: 2 tests PASS.

- [ ] **Step 6: Run the LMD regression tests + full suite**

Run: `mvn -q test -Dtest="Lmd*,LausitzFreightPreprocessorTest" -pl parcel-demand-2-matsim-pipeline`
Expected: PASS (incl. `LmdBaselineEndToEndTest.bootsOnRealData` if data staged).
Run: `mvn -q test -pl parcel-demand-2-matsim-pipeline`
Expected: all green.

- [ ] **Step 7: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/freight/FreightRunComposer.java parcel-demand-2-matsim-pipeline/src/main/java/hagrid/simulation/SimulationRunnerUtils.java parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/freight/FreightRunComposerTest.java
git commit -m "feat(marriage): FreightRunComposer with KeepSelected strategy (iterating-run-safe carrier wiring)"
```

---

### Task 3: Married branch in `runSimulation` + end-to-end proof with a real replanning iteration

**Files:**
- Modify: `parcel-demand-2-matsim-pipeline/src/main/java/hagrid/simulation/SimulationRunnerUtils.java` (DRT branch, lines ~236–253)
- Modify: `parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/freight/LmdTestShapefiles.java` (visibility)
- Create: `parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/drt/DrtE2eFixtures.java`
- Modify: `parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/drt/DrtBaselineEndToEndTest.java` (use fixtures)
- Test: `parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/drt/MarriedBaselineEndToEndTest.java`

**Interfaces:**
- Consumes: `FreightRunComposer.addCarriers/installCarrierModules` (Task 2), `cfg.isDrtWithFreight()` (Task 1), `LausitzFreightPreprocessor.run(String,String,String,String,String,int,String)` (7-arg), `LausitzDrtPreprocessor.run(...)` (existing e2e signature), `DrtScenarioBuilder.build(...)` (8-arg test overload), `DrtConfigComposer.installModules(Controler)`.
- Produces: the married production branch; `DrtE2eFixtures.buildGrid()`, `DrtE2eFixtures.buildDemand()`, `DrtE2eFixtures.writeSquareShapefile(Path, double)` for future tests.

- [ ] **Step 1: Extract the e2e fixtures (mechanical refactor, tests stay green)**

Create `DrtE2eFixtures` in `hagrid.integrated.drt` (test sources): move `buildGrid()`,
`addLink(...)`, `buildDemand()`, `writeSquareShapefile(...)`, `writeIntLE`, `writeDoubleLE`
verbatim out of `DrtBaselineEndToEndTest` as `public static` members of a
`public final class DrtE2eFixtures` (private ctor). Update `DrtBaselineEndToEndTest` to call
`DrtE2eFixtures.buildGrid()` etc. No logic changes.

In `LmdTestShapefiles.java` change the declarations to
`public final class LmdTestShapefiles` and `public static void writeDemand(...)` (it will be
imported from the `hagrid.integrated.drt` test package).

Run: `mvn -q test -Dtest="DrtBaselineEndToEndTest,LausitzFreightPreprocessorTest" -pl parcel-demand-2-matsim-pipeline`
Expected: PASS (pure refactor).

- [ ] **Step 2: Write the failing married e2e test**

```java
package hagrid.integrated.drt;

import hagrid.integrated.freight.FreightRunComposer;
import hagrid.integrated.freight.LausitzFreightPreprocessor;
import hagrid.integrated.freight.LmdTestShapefiles;
import hagrid.simulation.DrtScenarioBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.controler.Controler;
import org.matsim.core.network.io.NetworkWriter;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.freight.carriers.*;
import org.matsim.testcases.MatsimTestUtils;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end proof of the MARRIED baseline: passenger DRT and offline-routed LMD carriers in
 * ONE Controler, run for lastIteration=1 so a real REPLANNING event fires. With the empty
 * default CarrierStrategyManager this run would crash entering iteration 1
 * ("No strategy found") — the KeepSelected manager from FreightRunComposer must survive it.
 */
@DisplayName("Married baseline end-to-end (DRT + LMD carriers, one Controler, replanning fires)")
class MarriedBaselineEndToEndTest {

    @RegisterExtension
    public MatsimTestUtils utils = new MatsimTestUtils();

    @Test
    @DisplayName("runsMarriedBaselineThroughOneReplanningIteration")
    void runsMarriedBaselineThroughOneReplanningIteration() throws Exception {
        Path dir = Path.of(utils.getOutputDirectory()).toAbsolutePath();
        Files.createDirectories(dir);

        // ---- shared raw fixtures (identical to DrtBaselineEndToEndTest) ----
        Network rawNet = DrtE2eFixtures.buildGrid();
        Path rawNetFile = dir.resolve("raw_network.xml.gz");
        new NetworkWriter(rawNet).write(rawNetFile.toString());
        Path rawPlansFile = dir.resolve("raw_plans.xml.gz");
        PopulationUtils.writePopulation(DrtE2eFixtures.buildDemand(), rawPlansFile.toString());
        Path shpFile = dir.resolve("service-area.shp");
        DrtE2eFixtures.writeSquareShapefile(shpFile, 2000.0);
        Path depotCsv = dir.resolve("depots.csv");
        Files.writeString(depotCsv, "provider;x;y\ndhl;500.0;500.0\n");

        // ---- LMD side: van type + tiny PANDA-like demand + PRODUCTION jsprit preprocessing ----
        CarrierVehicleTypes types = new CarrierVehicleTypes();
        VehicleType van = VehicleUtils.createVehicleType(Id.create("ct_cep_size_m", VehicleType.class));
        van.getCapacity().setOther(165);
        van.setNetworkMode("car");
        van.getCostInformation().setCostsPerMeter(0.0004).setCostsPerSecond(0.0).setFixedCost(170.0);
        types.getVehicleTypes().put(van.getId(), van);
        Path typesFile = dir.resolve("vans.xml");
        new CarrierVehicleTypeWriter(types).write(typesFile.toString());

        Path demandShp = dir.resolve("demand.shp");
        LmdTestShapefiles.writeDemand(demandShp,
                new double[][]{{300, 200}, {800, 600}},
                new long[]{3, 2},    // dhl B2C parcels
                new long[]{1, 0},    // dhl B2B parcels
                new long[]{0, 0});   // hermes: none

        Path carriersOut = dir.resolve("lmd_carriers_routed.xml");
        LausitzFreightPreprocessor.run(demandShp.toString(), depotCsv.toString(),
                rawNetFile.toString(), typesFile.toString(), carriersOut.toString(),
                /*jspritIterations*/ 1, shpFile.toString());
        assertThat(Files.exists(carriersOut)).isTrue();

        // ---- DRT side: production preprocessor (drt-tagged net, person plans, fleet) ----
        Path drtNetFile = dir.resolve("drt_network.xml.gz");
        Path clippedPlans = dir.resolve("clipped_plans.xml.gz");
        Path fleetFile = dir.resolve("fleet.xml.gz");
        LausitzDrtPreprocessor.run(
                rawNetFile.toString(), rawPlansFile.toString(), shpFile.toString(),
                depotCsv.toString(), drtNetFile.toString(), clippedPlans.toString(),
                fleetFile.toString(), /*fleetSize*/ 4, /*capacity*/ 8,
                /*serviceBegin*/ 0.0, /*serviceEnd*/ 86400.0);

        URL cfgUrl = getClass().getClassLoader().getResource("lausitz-native-like.config.xml");
        assertThat(cfgUrl).isNotNull();
        Path matsimOut = dir.resolve("matsim");

        // ---- marriage: ONE scenario, ONE Controler; lastIteration=1 -> replanning fires ----
        Scenario scenario = DrtScenarioBuilder.build(
                cfgUrl.toString(), drtNetFile.toString(), clippedPlans.toString(),
                shpFile.toString(), fleetFile.toString(),
                matsimOut.toString(), "MARRIED_E2E", /*lastIteration*/ 1);
        FreightRunComposer.addCarriers(scenario, carriersOut.toString(), typesFile.toString());

        // Finding-2 guard: every link a routed carrier plan references must exist in the
        // married (drt-tagged) network, else the QSim cannot replay the tours.
        for (Carrier carrier : CarriersUtils.getCarriers(scenario).getCarriers().values()) {
            assertThat(carrier.getSelectedPlan()).isNotNull();
            for (ScheduledTour tour : carrier.getSelectedPlan().getScheduledTours()) {
                for (Tour.TourElement el : tour.getTour().getTourElements()) {
                    if (el instanceof Tour.Leg leg && leg.getRoute() instanceof NetworkRoute route) {
                        for (Id<Link> linkId : route.getLinkIds()) {
                            assertThat(scenario.getNetwork().getLinks())
                                    .as("carrier route link must exist in married network: " + linkId)
                                    .containsKey(linkId);
                        }
                    }
                }
            }
        }

        Controler controler = new Controler(scenario);
        DrtConfigComposer.installModules(controler);
        FreightRunComposer.installCarrierModules(controler, scenario);
        controler.run();

        // ---- both halves really ran ----
        try (var s = Files.walk(matsimOut)) {
            assertThat(s.filter(Files::isRegularFile)
                    .anyMatch(p -> p.getFileName().toString().toLowerCase().contains("drt")))
                    .as("expected a drt_* output file (pax DRT ran)").isTrue();
        }
        try (var s = Files.walk(matsimOut)) {
            assertThat(s.filter(Files::isRegularFile)
                    .anyMatch(p -> p.getFileName().toString().endsWith("output_carriers.xml.gz")))
                    .as("expected output_carriers.xml.gz (CarrierModule ran)").isTrue();
        }
    }
}
```

API notes for the implementer (verified 2026-07-02, adjust only if the compiler disagrees):
`ScheduledTour#getTour()`, `Tour#getTourElements()`, `Tour.Leg#getRoute()` are the freight-contrib
accessors; `LausitzDrtPreprocessor.run` has the 11-arg signature used by
`DrtBaselineEndToEndTest` (copy from there). If `Tour.Leg#getRoute()` returns `Route`, the
`instanceof NetworkRoute` pattern covers it.

- [ ] **Step 3: Run the test to verify it fails for the RIGHT reason**

Run: `mvn -q test -Dtest=MarriedBaselineEndToEndTest -pl parcel-demand-2-matsim-pipeline`
Expected: PASS is possible already at this point (the test drives composer + builder directly,
which exist after Task 2). If it fails, the failure is the point of this task — most likely
candidates: Guice binding clash between CarrierModule and DVRP (fix: adjust module install order
— DrtConfigComposer first, then FreightRunComposer, as written), or a missing scoring param for a
carrier activity type (record the exact message and fix in the production path, not the test).
Do NOT proceed while this test is red.

- [ ] **Step 4: Wire the married branch into `runSimulation`**

Replace the DRT branch (currently lines 236–253) with:

```java
        // DRT path: passenger DRT; married baseline additionally carries the LMD carriers.
        if (cfg.isDrtScenario()) {
            if (cfg.isDrtWithFreight()) {
                // 1. offline jsprit routing — the exact same call the LMD_BASELINE uses,
                //    clipped to the SAME service-area shapefile (identical geography).
                hagrid.integrated.freight.LausitzFreightPreprocessor.run(
                        cfg.getLmdDemandShapefile(), cfg.getLmdDepotCsv(),
                        cfg.getLausitzNetworkRaw(), cfg.getLmdVehicleTypes(),
                        cfg.getLmdCarriersRouted(), cfg.getJspritIterations(),
                        cfg.getDrtServiceAreaShapefile());
            }

            Scenario scenario = DrtScenarioBuilder.build(cfg);
            if (cfg.isDrtWithFreight()) {
                hagrid.integrated.freight.FreightRunComposer.addCarriers(
                        scenario, cfg.getLmdCarriersRouted(), cfg.getLmdVehicleTypes());
            }

            Controler controler = new Controler(scenario);
            java.util.List<org.matsim.api.core.v01.Coord> depots =
                    hagrid.integrated.drt.DrtDepotReader.readCoords(java.nio.file.Path.of(cfg.getLmdDepotCsv()));
            double serviceEnd = 86400.0;       // matches LausitzDrtPreprocessor default
            double returnWindow = 5400.0;       // last 90 min target depots
            // Depot parking capacity = even fleet split (matches the spawn distribution): each depot
            // zone absorbs at most ceil(fleet/depots) returning vehicles, so the end-of-day return
            // fills the nearest depot first and overflows to the next once full.
            double perDepotCapacity = Math.ceil((double) cfg.getFleetSize() / Math.max(1, depots.size()));
            hagrid.integrated.drt.DrtConfigComposer.installModules(controler, depots,
                    serviceEnd - returnWindow, perDepotCapacity, 1800.0);
            if (cfg.isDrtWithFreight()) {
                hagrid.integrated.freight.FreightRunComposer.installCarrierModules(controler, scenario);
                LOG.info("MARRIED baseline run '{}' (DRT fleet {} + LMD carriers).",
                        cfg.getRunId(), cfg.getFleetSize());
            } else {
                LOG.info("DRT passenger-only run '{}' (fleet {}).", cfg.getRunId(), cfg.getFleetSize());
            }
            controler.run();
            logDuration("Simulation '" + cfg.getRunId() + "'", t0);
            return;
        }
```

- [ ] **Step 5: Run the DRT + married + LMD test set, then the full suite**

Run: `mvn -q test -Dtest="MarriedBaselineEndToEndTest,DrtBaselineEndToEndTest,Lmd*" -pl parcel-demand-2-matsim-pipeline`
Expected: PASS.
Run: `mvn -q test -pl parcel-demand-2-matsim-pipeline`
Expected: all green.

- [ ] **Step 6: Commit**

```bash
git add -f parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/drt/DrtE2eFixtures.java parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/drt/MarriedBaselineEndToEndTest.java
git add parcel-demand-2-matsim-pipeline/src/main/java/hagrid/simulation/SimulationRunnerUtils.java parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/drt/DrtBaselineEndToEndTest.java parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/freight/LmdTestShapefiles.java
git commit -m "feat(marriage): DRT_BASELINE carries LMD carriers in one Controler, e2e-proven through a replanning iteration"
```

---

### Task 4: `FreightEventHandler` mixed-traffic hardening

The married events file contains pax activities, DRT vehicle/driver events, AND freight events.
`FreightEventHandler` filters `LinkLeaveEvent` via `classifyVehicle` (keyword allowlist — DRT
vehicles and pax cars fall through to `null`, fine), **but the `ActivityStartEvent` branch counts
every `"service"` activity without an `isFreight(person)` guard** (line ~96), and the
`ActivityEndEvent` branch must be checked for the same hole. In an LMD-only run only freight
agents emit `service`; in the married run this is an unguarded assumption.

**Files:**
- Modify: `parcel-demand-2-matsim-pipeline/src/main/java/hagrid/analysis/FreightEventHandler.java` (lines ~82–130)
- Test: `parcel-demand-2-matsim-pipeline/src/test/java/hagrid/analysis/FreightEventHandlerMixedTrafficTest.java`

**Interfaces:**
- Consumes: existing `FreightEventHandler` public API (`handleEvent(...)`, `getServiceEvents()` or the equivalent accessors — read the class top-to-bottom first and use the real accessor names).
- Produces: no API change; behavior change only for non-freight person IDs.

- [ ] **Step 1: Write the failing test**

```java
package hagrid.analysis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.ActivityStartEvent;
import org.matsim.api.core.v01.population.Person;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("FreightEventHandler ignores non-freight agents in married (mixed-traffic) events")
class FreightEventHandlerMixedTrafficTest {

    @Test
    @DisplayName("a 'service' activity of a NON-freight person is not counted")
    void nonFreightServiceActivityIgnored() {
        FreightEventHandler handler = new FreightEventHandler();

        // freight driver -> counted
        handler.handleEvent(new ActivityStartEvent(9 * 3600.0,
                Id.create("freight_dhl_veh_1_driver", Person.class),
                Id.createLinkId("l1"), null, "service", new Coord(0, 0)));
        // DRT/pax-side agent emitting a same-named activity -> must be ignored
        handler.handleEvent(new ActivityStartEvent(9 * 3600.0,
                Id.create("drt_taxi_7", Person.class),
                Id.createLinkId("l1"), null, "service", new Coord(0, 0)));

        assertThat(handler.getServiceEvents())
                .as("only the freight driver's service activity may be recorded")
                .containsOnlyKeys("freight_dhl_veh_1_driver");
    }
}
```

Adjust the accessor name (`getServiceEvents()`) and the `ActivityStartEvent` constructor arity to
the real ones in this codebase/MATSim version (read `FreightEventHandler` + one existing usage
first); keep the assertion semantics identical.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=FreightEventHandlerMixedTrafficTest -pl parcel-demand-2-matsim-pipeline`
Expected: FAIL — both keys recorded.

- [ ] **Step 3: Implement the guard**

In `handleEvent(ActivityStartEvent)` change the `service` branch (line ~96):

```java
        } else if ("service".equals(actType) && isFreight(person)) {
```

Audit `handleEvent(ActivityEndEvent)` (lines ~102–130) and apply the same `isFreight(person)`
guard to every branch keyed only on an activity-type string (`"start"`, `"service"`, `"end"`).

- [ ] **Step 4: Run test + full suite**

Run: `mvn -q test -Dtest=FreightEventHandlerMixedTrafficTest -pl parcel-demand-2-matsim-pipeline`
Expected: PASS.
Run: `mvn -q test -pl parcel-demand-2-matsim-pipeline`
Expected: all green (dashboard tests unaffected: freight-only fixtures keep passing).

- [ ] **Step 5: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/src/main/java/hagrid/analysis/FreightEventHandler.java parcel-demand-2-matsim-pipeline/src/test/java/hagrid/analysis/FreightEventHandlerMixedTrafficTest.java
git commit -m "fix(analysis): FreightEventHandler ignores non-freight agents in mixed pax+freight events"
```

---

### Task 5: Run protocol + docs (manual, no code)

**Files:**
- Modify: memory `project_lausitz_drt_freight.md` (live status) — at session close.
- No `.bat` edits with Edit/Write; if a launcher is wanted, write it via PowerShell `WriteAllLines`.

- [ ] **Step 1: Real-data smoke run (~15 min, supervised)** — proves the production path on the
  full 41,937-agent scenario + real PANDA demand before burning a night:

```
mvn -q compile exec:java "-Dexec.mainClass=hagrid.simulation.HAGRIDSimulationRunner" "-Dexec.args=concept=DRT_BASELINE,date=2025-05-13,maxIter=1,jspritIter=5,fleetSize=80,tag=marriedsmoke"
```

Expected: exit 0; output dir `DRT_BASELINE_13052025_marriedsmoke_iter1_jsprit5` contains BOTH
`drt_*` files and `*.output_carriers.xml.gz`; log shows "MARRIED baseline run"; the jsprit phase
logs 7 carriers and 0 (or few) unassigned jobs. Iteration 1 completes → replanning survived on
real data.

- [ ] **Step 2: Overnight headline run (user-triggered, ~8.5 h)** — collapses the pending
  fleet-120 DRT re-run and the married Baseline into one run (Decision D4):

```
mvn -q compile exec:java "-Dexec.mainClass=hagrid.simulation.HAGRIDSimulationRunner" "-Dexec.args=concept=DRT_BASELINE,date=2025-05-13,maxIter=150,jspritIter=100,fleetSize=120,writeDashboard=true,tag=married120"
```

Laptop: plugged in, sleep disabled (runs die on sleep — feedback_drt_runs_operational).

- [ ] **Step 3: Dashboards off the married output**
  - LMD dashboard: `writeDashboard=true` path — verify `generateDashboard` resolves the married
    run dir (it keys on runId + iter/jsprit counts); delivery-rate, unassigned and tour KPIs must
    match plausibility (7 carriers, ~51 vans, waves visible).
  - DRT dashboard: point `RUN`/`PREFIX` in `analysis/drt-headline/build_drt_dashboard.py` at the
    married run dir; the run-keyed event cache keeps runs separated.
  - Sanity cross-check: DRT KPIs of the married run vs the pax-only fleet80 baseline — expect
    small deltas from fares+fleet size, NOT from the vans (51 vans in a 41,937-agent QSim are
    noise); LMD tour durations vs the solo LMD run — expect slightly longer executed tours
    (mixed traffic), planned durations identical.

- [ ] **Step 4: Record results** — update the project memory (headline numbers, any surprises),
  session log via /close. Push the branch on ask.

---

## Explicit non-goals (deferred, do not creep in)

- Canonical KPI CSV / `IntegratedKPIHandler` (1e — next plan; needs exactly this married events file).
- Shared-Use (1c) and Modular (1d) scenarios.
- Scenario-enum renaming sweep (user: revisit later).
- Congested-travel-time feedback into jsprit (method note, not a task).
- Ruhland service-area enlargement (re-evaluate AT the marriage per memory — decision needed from
  the user before the headline run if the corridor should be widened; default: keep current
  Hoyerswerda-only shape so results stay comparable with the 2026-06-29 baselines).

## Self-review notes

- Spec coverage: the married Controler is design-spec §5.4 `DRT_BASELINE` ("Multi-LSP jsprit plans,
  dedicated fleet, fixed execution | native DRT | coupling: none") — Tasks 1–3 implement exactly
  that; §7 KPI export is explicitly out (1e).
- The riskiest unknowns are each pinned by a test: empty-strategy-manager crash (Task 3 e2e,
  lastIteration=1), link-ID replay (Task 3 in-test guard), mixed-event KPI bleed (Task 4).
- Type/name consistency: `FreightRunComposer.addCarriers/installCarrierModules/keepSelectedStrategyManager`
  are used with identical signatures in Tasks 2, 3; `isDrtWithFreight()` defined in Task 1, consumed
  in Task 3.
