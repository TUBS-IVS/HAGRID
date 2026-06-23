# Lausitz DRT_BASELINE Prep (1b-prep) — Passenger-Only Runnable

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make a real, passenger-only `DRT_BASELINE` MATSim run launchable end-to-end on the Lausitz/Hoyerswerda study area, using the native matsim-lausitz config as the scoring base and HAGRID's full-DVRP DRT fleet.

**Architecture:** A new reusable preprocessing core (`LausitzDrtPreprocessor`) produces the run-scoped DRT inputs (drt-augmented network, service-area-clipped passenger population, DVRP fleet) from staged raw inputs. A new `LausitzDrtConfigurator` builds the run `Config` from the staged native lausitz config — redirecting network/plans to the produced files, stripping PT/counts, calling the native vsp scoring helpers, then composing HAGRID's full-DVRP DRT on top. Freight is decoupled from DRT runs so the passenger-only baseline doesn't require the Hannover freight pipeline. A thin `PrepareLausitzDrtInputs` main runs the preprocessing; the existing `HAGRIDSimulationRunner` then runs the sim. Freight/Hannover code paths stay provably untouched (all new behaviour is gated on `isDrtScenario()`).

**Tech Stack:** Java 21, MATSim 2025.0-PR3552, matsim drt/dvrp contrib, `com.github.matsim-scenarios:matsim-lausitz:2.0` (local `.m2`), matsim vsp contrib (transitive), JUnit5 + AssertJ + `MatsimTestUtils`.

## Global Constraints

- **MATSim version:** `2025.0-PR3552` (root pom; aligned with matsim-lausitz). Do not bump.
- **Study area coupling:** any DRT concept requires `studyArea=LAUSITZ_HOYERSWERDA` (already enforced in `SimulationRunnerUtils.parseScenario`). `StudyArea.LAUSITZ_HOYERSWERDA.folder() == "lausitz"`.
- **Input root (Lausitz):** `parcel-demand-2-matsim-pipeline/hagrid-input/lausitz/` (resolved by `HagridPaths` from `StudyArea`). Run-scoped outputs: `hagrid-output/{RUN_ID}/`.
- **DRT = full DVRP, DRT-only (no PT intermodality)** — locked project decision. `DrtConfigGroup.simulationType=fullSimulation`, real fleet via `vehiclesFile`. NOT native `estimateAndTeleport`.
- **100% sample is non-negotiable.** Compute is bounded ONLY by study-area size + population clipping, never by subsampling.
- **Hannover / freight path provably untouched:** every change is additive or gated on `isDrtScenario()`; HANNOVER is never a DRT scenario, so its behaviour cannot change. No edits to the freight build steps' logic — only guards around them.
- **Windows:** build/run via `mvn`; large inputs are not versioned (see Task 0 staging doc). Test heap: matsim-lausitz surefire uses `-Xmx6500m`.

## KEY DECISIONS — review before executing (user can veto any)

- **D1 (chosen by user):** Config base = **native** `lausitz-v2024.2-100pct.config.xml` (staged), not a from-scratch config. Gives calibrated scoring + the correct activity-param convention.
- **D2:** Reuse the native/vsp static helpers (NOT subclass `MATSimApplication`): `org.matsim.contrib.vsp.scenario.SnzActivities.addScoringParams(config)`, `org.matsim.contrib.vsp.scoring.RideScoringParamsFromCarParams.setRideScoringParamsBasedOnCarParams(scoring, 2.0)`, and `org.matsim.run.prepare.PrepareNetwork.prepareDrtNetwork(net, serviceAreaShp)`. Exact signatures to be confirmed in Task 1's spike against the jars on the classpath.
- **D3:** Network is the **FULL** staged Lausitz network with `drt` added to car links inside the service area (native `prepareDrtNetwork`). NOT clipped → out-of-area trip legs still route. The 1b `DrtNetworkPreparer` (clips + adds drt to all modes) is **superseded** for this path (left in place, unused here).
- **D4:** Population is **clipped to the service area** (home anchor inside) via `PopulationClipper`, and additionally filtered to **subpopulation `person`** (drop matsim-lausitz `longDistanceFreight`/`commercialPersonTraffic*`/`goodsTraffic` background traffic for the passenger-only milestone — our freight is the separate PANDA LMD added later in Project H/1c). This is the compute bound.
- **D5:** PT stripped from the DRT run: remove `transit`/`transitRouter` modules, `counts` module (remote file), and `pt` from `subtourModeChoice.modes`; keep the `pt` scoring `modeParams` (so `drt` ASC = `pt` ASC works). Keep `car`/`ride`/`bike`/`walk` person modes; drop `longDistanceFreight` from `qsim.mainModes`/`routing.networkModes` (no freight agents).
- **D6:** Freight pipeline (vehicle types, carrier merge, freight zones, `CarrierModule`, `HAGRIDSimulationModule`) is **skipped for DRT scenarios**, gated on `isDrtScenario()`. Re-enabled when Project H/1c adds the dedicated LMD.
- **D7:** Fleet defaults: capacity `8`, service window `0..86400 s`, `fleetSize` from the CLI arg (default 50; for debug pass e.g. `fleetSize=20`). Spec §11 may refine later.

---

## File Structure

**Create:**
- `docs/DATA-LAUSITZ.md` — staging instructions (which raw files go where; not versioned).
- `.../hagrid/integrated/drt/LausitzDrtPreprocessor.java` — reusable core: produce drt-network + clipped population + fleet into the run dir.
- `.../hagrid/integrated/drt/LausitzDrtConfigurator.java` — build the run `Config` from the staged native config (redirect/strip/score/compose).
- `.../hagrid/integrated/drt/PrepareLausitzDrtInputs.java` — thin CLI main wrapping the preprocessor.
- `.../hagrid/simulation/DrtScenarioBuilder.java` — DRT-only scenario build (config + register `DrtRouteFactory` + `loadScenario`), no freight.
- Tests: `LausitzDrtPreprocessorTest`, `LausitzDrtConfiguratorTest`, `DrtScenarioBuilderTest`, `DrtBaselineEndToEndTest` (under `src/test/java/hagrid/...`).
- `run_drt_baseline.bat` (repo root) — chain preprocessing + sim.

**Modify:**
- `.../hagrid/simulation/HAGRIDSimulationConfig.java` — `validateInputFiles()`: gate the freight-input checks behind `!isDrtScenario()`; add getters for the staged native config + raw network/plans/service-area as needed (most already exist).
- `.../hagrid/simulation/SimulationRunnerUtils.java` — `runSimulation()`: DRT branch that skips freight-zone load + `CarrierModule` + `HAGRIDSimulationModule`, builds via `DrtScenarioBuilder`, installs DRT modules.
- `.../hagrid/HagridPaths.java` — add `lausitzBaseConfig()` getter (`hagrid-input/lausitz/config/lausitz-v2024.2-100pct.config.xml`).

---

## Task 0: Stage raw Lausitz inputs + document

**Files:**
- Create: `docs/DATA-LAUSITZ.md`
- Stage (copy, not committed) into `parcel-demand-2-matsim-pipeline/hagrid-input/lausitz/`:
  - `network/lausitz-network.xml.gz` ← `~/Documents/GitHub/PANDA/DatenPaketmengen/Lausitz/network/lausitz-v2024.2-network-with-pt.xml.gz`
  - `population/lausitz-100pct.plans.xml.gz` ← `~/Downloads/lausitz-v2024.2-100pct.plans-initial.xml.gz`
  - `drt/drt-service-area.shp` (+ `.dbf/.shx/.prj/.cpg`) ← `~/Documents/GitHub/matsim-lausitz/input/drt-area/hoyerswerda-ruhland_Bhf-utm32N.*`
  - `config/lausitz-v2024.2-100pct.config.xml` ← `~/Documents/GitHub/matsim-lausitz/input/v2024.2/lausitz-v2024.2-100pct.config.xml`

**Interfaces:**
- Produces: the four staged input families, addressed by `HagridPaths` getters `lausitzNetworkRaw()`, `passengerPlansRaw()`, `drtServiceAreaShapefile()`, and the new `lausitzBaseConfig()` (Task 1 adds the getter; the file is staged here).

- [ ] **Step 1: Add `lausitzBaseConfig()` to `HagridPaths`**
```java
/** Native matsim-lausitz base config (scoring/activity-param source for DRT runs). */
public String lausitzBaseConfig() {
    return inputBase.resolve("config").resolve("lausitz-v2024.2-100pct.config.xml").toString();
}
```
- [ ] **Step 2: Write `docs/DATA-LAUSITZ.md`** documenting each source→destination path above, that files are git-ignored, and the exact copy commands (Git Bash):
```bash
HI="parcel-demand-2-matsim-pipeline/hagrid-input/lausitz"
mkdir -p "$HI"/{network,population,drt,config}
cp ~/Documents/GitHub/PANDA/DatenPaketmengen/Lausitz/network/lausitz-v2024.2-network-with-pt.xml.gz "$HI/network/lausitz-network.xml.gz"
cp ~/Downloads/lausitz-v2024.2-100pct.plans-initial.xml.gz "$HI/population/lausitz-100pct.plans.xml.gz"
for e in shp dbf shx prj cpg; do cp ~/Documents/GitHub/matsim-lausitz/input/drt-area/hoyerswerda-ruhland_Bhf-utm32N.$e "$HI/drt/drt-service-area.$e"; done
cp ~/Documents/GitHub/matsim-lausitz/input/v2024.2/lausitz-v2024.2-100pct.config.xml "$HI/config/lausitz-v2024.2-100pct.config.xml"
```
- [ ] **Step 3: Run the copy commands; verify** all files exist under `hagrid-input/lausitz/`.
Run: `ls -R parcel-demand-2-matsim-pipeline/hagrid-input/lausitz/`
Expected: network/, population/, drt/ (5 files), config/ all populated.
- [ ] **Step 4: Ensure `hagrid-input/lausitz/` is git-ignored** (large binaries). Add `parcel-demand-2-matsim-pipeline/hagrid-input/lausitz/` to `.gitignore` if not already covered.
- [ ] **Step 5: Commit** (doc + getter + gitignore only — no binaries)
```bash
git add docs/DATA-LAUSITZ.md .gitignore parcel-demand-2-matsim-pipeline/src/main/java/hagrid/HagridPaths.java
git commit -m "docs(lausitz): DATA-LAUSITZ staging guide + lausitzBaseConfig path"
```

---

## Task 1: `LausitzDrtConfigurator` — build the run Config from the native config (SPIKE-first)

This task carries the highest external-integration risk (remote-URL config, PT stripping, vsp helper signatures). De-risk with a throwaway test that actually loads + runs, THEN lock the production method.

**Files:**
- Create: `.../hagrid/integrated/drt/LausitzDrtConfigurator.java`
- Test: `.../hagrid/integrated/drt/LausitzDrtConfiguratorTest.java`

**Interfaces:**
- Consumes: staged native config path (`HagridPaths.lausitzBaseConfig()`), produced drt-network + clipped-plans + fleet paths (Task 2), service-area shp path.
- Produces:
  ```java
  public static Config build(String baseConfigPath, String drtNetworkFile, String plansFile,
                             String serviceAreaShp, String fleetFile,
                             String outputDir, String runId, int lastIteration)
  ```
  Returns a fully-composed `Config` ready for `ScenarioUtils.createScenario`.

- [ ] **Step 1 (spike): write a throwaway exploratory test** `spikeLoadsAndStrips()` that loads the staged native config with `ConfigUtils.loadConfig(path)`, then asserts the modules present, so we SEE what loads. Run it; record which modules exist and whether load touches remote URLs (it must NOT fetch — `loadConfig` only parses).
Run: `mvn -pl parcel-demand-2-matsim-pipeline test -Dtest=LausitzDrtConfiguratorTest#spikeLoadsAndStrips`
Expected: config loads; `transit`, `counts`, `subtourModeChoice`, `scoring` modules present; network/plans `inputFile` are the SVN URLs (to be overridden).
- [ ] **Step 2: write the failing production test** `buildProducesRunnableDrtConfig()`:
```java
@Test
void buildProducesRunnableDrtConfig() {
    Config cfg = LausitzDrtConfigurator.build(
        baseConfig, drtNet, plans, serviceShp, fleet, outDir, "DRT_TEST", 0);
    // network/plans redirected
    assertThat(cfg.network().getInputFile()).isEqualTo(drtNet);
    assertThat(cfg.plans().getInputFile()).isEqualTo(plans);
    // PT stripped
    assertThat(cfg.transit().isUseTransit()).isFalse();
    assertThat(List.of(cfg.subtourModeChoice().getModes())).doesNotContain("pt").contains("drt");
    // counts cleared (no remote fetch)
    assertThat(cfg.counts().getCountsFileName()).isNull();
    // DRT composed (full sim + fleet)
    var drt = MultiModeDrtConfigGroup.get(cfg).getModalElements().iterator().next();
    assertThat(drt.simulationType).isEqualTo(DrtConfigGroup.SimulationType.fullSimulation);
    assertThat(drt.vehiclesFile).isEqualTo(fleet);
    // activity params present (SnzActivities) — at least 'home_*' style scored
    assertThat(cfg.scoring().getActivityParams()).isNotEmpty();
}
```
Run: `mvn -pl parcel-demand-2-matsim-pipeline test -Dtest=LausitzDrtConfiguratorTest#buildProducesRunnableDrtConfig`
Expected: FAIL (method not implemented).
- [ ] **Step 3: implement `LausitzDrtConfigurator.build`** in this order:
  1. `Config config = ConfigUtils.loadConfig(baseConfigPath);`
  2. Redirect IO: `config.network().setInputFile(drtNetworkFile); config.plans().setInputFile(plansFile);`
  3. Controller: `setOutputDirectory(outputDir); setRunId(runId); setFirstIteration(0); setLastIteration(lastIteration); setOverwriteFileSetting(overwriteExistingFiles);`
  4. Strip PT (D5): `config.transit().setUseTransit(false);` remove transit schedule/vehicles file refs (`setTransitScheduleFile(null)` / `setVehiclesFile(null)`); clear counts: `config.counts().setInputFile(null);`; remove `pt` from `config.subtourModeChoice().setModes(...)` and ensure `walk`/`bike`/`ride`/`car` remain.
  5. Drop freight subpop modes (D5): set `config.qsim().setMainModes(["car","bike"])`; `config.routing().setNetworkModes(["car","ride","bike"])`; `config.travelTimeCalculator().setAnalyzedModes(["car"])`. (Confirm setters in spike; if `longDistanceFreight` strategysettings remain harmless with no such agents, leave them.)
  6. Activity + ride scoring (D2): `SnzActivities.addScoringParams(config);` then `RideScoringParamsFromCarParams.setRideScoringParamsBasedOnCarParams(config.scoring(), 2.0);` — exact package/signature confirmed in spike; if `SnzActivities` requires the population, fall back to scanning the clipped plans for distinct activity types and adding `ActivityParams(type).setTypicalDuration(parseSuffix(type))` (the `type_<seconds>` convention). Implement the fallback only if the direct call doesn't compile/work.
  7. DRT compose (reuse): `DrtConfigComposer.composeConfig(config, serviceAreaShp, fleetFile);` (sets full-sim + fleet + serviceAreaBased + drt scoring/subtour/leg params).
- [ ] **Step 4: run the production test to green.**
Run: `mvn -pl parcel-demand-2-matsim-pipeline test -Dtest=LausitzDrtConfiguratorTest#buildProducesRunnableDrtConfig`
Expected: PASS.
- [ ] **Step 5: delete the spike test method; keep only the production test. Commit.**
```bash
git add parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/drt/LausitzDrtConfigurator.java parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/drt/LausitzDrtConfiguratorTest.java
git commit -m "feat(drt): LausitzDrtConfigurator builds full-DVRP DRT config from native lausitz config"
```

---

## Task 2: `LausitzDrtPreprocessor` — produce drt-network + clipped population + fleet

**Files:**
- Create: `.../hagrid/integrated/drt/LausitzDrtPreprocessor.java`
- Test: `.../hagrid/integrated/drt/LausitzDrtPreprocessorTest.java`

**Interfaces:**
- Consumes: raw network/plans/service-area paths + run-scoped output paths + fleet params, all from a `HAGRIDSimulationConfig`.
- Produces:
  ```java
  // reusable core (later callable from the run flow too):
  public static void run(String rawNetwork, String rawPlans, String serviceAreaShp,
                         String drtNetworkOut, String plansOut, String fleetOut,
                         int fleetSize, int capacity, double serviceBegin, double serviceEnd)
  // convenience overload binding to config getters:
  public static void run(HAGRIDSimulationConfig cfg)
  ```
  Writes the three run-scoped files referenced by `cfg.getDrtNetworkClipped()/getPassengerPlansClipped()/getDrtFleetFile()`.

- [ ] **Step 1: write failing test** `producesDrtNetworkClippedPlansAndFleet()` using a tiny fixture network (full, multimodal: a car square + one isolated rail link) + a tiny multi-subpop population (2 `person` inside, 1 `person` outside, 1 `goodsTraffic` inside) + a `square(2000)` service area written to a temp shapefile (use `GeoFileReader`-readable shp, or assert via geometry helper). Assert: (a) drt-network has `drt` on car links inside, NOT on the rail link; (b) clipped plans contain only the 2 inside `person` agents (outside dropped, goodsTraffic dropped); (c) fleet file exists with `fleetSize` vehicles.
Run: `mvn -pl parcel-demand-2-matsim-pipeline test -Dtest=LausitzDrtPreprocessorTest#producesDrtNetworkClippedPlansAndFleet`
Expected: FAIL.
- [ ] **Step 2: implement the core `run(...)`**:
  1. Read full network: `Network net = NetworkUtils.readNetwork(rawNetwork);`
  2. Load service-area geometry: `Geometry area = GeoUtils.getBoundaryGeometry(GeoFileReader.getAllFeatures(serviceAreaShp));`
  3. Add drt to area car links (D3) on the FULL net: `PrepareNetwork.prepareDrtNetwork(net, serviceAreaShp);` (native helper; confirm signature — takes the shp path string). Then `new NetworkWriter(net).write(drtNetworkOut);`
  4. Read population: `Population pop = PopulationUtils.readPopulation(rawPlans);`
  5. Clip + person-only (D4): `Population clipped = PopulationClipper.clip(pop, area);` then remove non-`person` subpopulation agents — filter on `PopulationUtils.getSubpopulation(person)` / the `subpopulation` attribute; keep only `"person"`. Write: `PopulationUtils.writePopulation(clipped, plansOut);`
  6. Fleet: `DrtFleetGenerator.write(net, fleetSize, capacity, serviceBegin, serviceEnd, Path.of(fleetOut));`
- [ ] **Step 3: implement the `run(HAGRIDSimulationConfig cfg)` overload** — create the run dir (`Files.createDirectories(cfg.getOutputDirectory())`), then call the core with `cfg.getLausitzNetworkRaw(), cfg.getPassengerPlansRaw(), cfg.getDrtServiceAreaShapefile(), cfg.getDrtNetworkClipped(), cfg.getPassengerPlansClipped(), cfg.getDrtFleetFile(), cfg.getFleetSize(), 8, 0.0, 86400.0`.
- [ ] **Step 4: run test to green.**
Run: `mvn -pl parcel-demand-2-matsim-pipeline test -Dtest=LausitzDrtPreprocessorTest#producesDrtNetworkClippedPlansAndFleet`
Expected: PASS.
- [ ] **Step 5: commit.**
```bash
git add parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/drt/LausitzDrtPreprocessor.java parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/drt/LausitzDrtPreprocessorTest.java
git commit -m "feat(drt): LausitzDrtPreprocessor produces drt-network + clipped person plans + fleet"
```

---

## Task 3: `PrepareLausitzDrtInputs` main (thin CLI wrapper)

**Files:**
- Create: `.../hagrid/integrated/drt/PrepareLausitzDrtInputs.java`
- Test: `.../hagrid/integrated/drt/PrepareLausitzDrtInputsTest.java` (parses args → config; asserts it dispatches only DRT scenarios)

**Interfaces:**
- Consumes: `SimulationRunnerUtils.parseScenarios(args)` (existing), `LausitzDrtPreprocessor.run(cfg)` (Task 2).
- Produces: a `main(String[] args)` that, for each parsed DRT scenario, runs the preprocessor.

- [ ] **Step 1: write failing test** `runsPreprocessorForDrtScenarioOnly()` — call a package-private `process(List<HAGRIDSimulationConfig>)` with one DRT + one non-DRT config (mock/stub the preprocessor via a seam, or assert it throws on a non-DRT config). Assert non-DRT config is rejected with a clear message.
Run: `mvn -pl parcel-demand-2-matsim-pipeline test -Dtest=PrepareLausitzDrtInputsTest`
Expected: FAIL.
- [ ] **Step 2: implement** `main`: `parseScenarios(args)` → for each cfg, `if (!cfg.isDrtScenario()) throw new IllegalArgumentException("PrepareLausitzDrtInputs only handles DRT scenarios: " + cfg.getRunId());` else `LausitzDrtPreprocessor.run(cfg);` Log each produced file path.
- [ ] **Step 3: run test to green.** Run: `mvn -pl parcel-demand-2-matsim-pipeline test -Dtest=PrepareLausitzDrtInputsTest` → PASS.
- [ ] **Step 4: commit.**
```bash
git add parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/drt/PrepareLausitzDrtInputs.java parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/drt/PrepareLausitzDrtInputsTest.java
git commit -m "feat(drt): PrepareLausitzDrtInputs CLI runs DRT preprocessing for a scenario"
```

---

## Task 4: Decouple freight from DRT runs (validate + run branch + DRT-only builder)

**Files:**
- Modify: `.../hagrid/simulation/HAGRIDSimulationConfig.java` (`validateInputFiles`)
- Modify: `.../hagrid/simulation/SimulationRunnerUtils.java` (`runSimulation`)
- Create: `.../hagrid/simulation/DrtScenarioBuilder.java`
- Test: `.../hagrid/simulation/DrtScenarioBuilderTest.java`, and a `validateInputFiles` unit test

**Interfaces:**
- Consumes: `LausitzDrtConfigurator.build(...)` (Task 1).
- Produces: `DrtScenarioBuilder.build(HAGRIDSimulationConfig cfg) -> Scenario` (registers `DrtRouteFactory` before `loadScenario`). A `runSimulation` DRT branch that skips freight.

- [ ] **Step 1: write failing test** `validateSkipsFreightForDrt()` — a DRT `HAGRIDSimulationConfig` whose 3 clipped DRT files exist (temp) but whose freight files are ABSENT should `validateInputFiles()` without throwing.
Run: `mvn -pl parcel-demand-2-matsim-pipeline test -Dtest=HAGRIDSimulationConfigTest#validateSkipsFreightForDrt`
Expected: FAIL (currently throws on missing delivery/supply carriers).
- [ ] **Step 2: edit `validateInputFiles`** — wrap the freight-input checks (config, vehicle types, car network, bike network, change events, freight zone, delivery carriers, supply carriers) in `if (!isDrtScenario()) { ... }`. Inside `if (isDrtScenario())` keep the 3 clipped-file checks AND add `checkFile(Path.of(getDrtServiceAreaShapefile()), ...)` + `checkFile(Path.of(paths.lausitzBaseConfig()), ...)`. Run Step-1 test → PASS.
- [ ] **Step 3: write failing test** `DrtScenarioBuilderTest#buildsDrtOnlyScenario()` — given configurator-produced inputs (reuse the Task-2 fixture pipeline to produce tiny drt-net/plans/fleet + a tiny native-like config, OR a hand-built minimal config through `LausitzDrtConfigurator` against a temp config file), assert `build(cfg)` returns a `Scenario` with a non-empty population and a network that has `drt` links, and that `DrtRoute` factory is registered (deserialises a drt leg).
Run: `mvn -pl parcel-demand-2-matsim-pipeline test -Dtest=DrtScenarioBuilderTest#buildsDrtOnlyScenario`
Expected: FAIL.
- [ ] **Step 4: implement `DrtScenarioBuilder.build(cfg)`** (mirrors the proven smoke-test load path, no freight):
```java
Config config = LausitzDrtConfigurator.build(
    cfg.getPaths().lausitzBaseConfig(), cfg.getDrtNetworkClipped(),
    cfg.getPassengerPlansClipped(), cfg.getDrtServiceAreaShapefile(),
    cfg.getDrtFleetFile(), cfg.getOutputDirectoryAsString(), cfg.getRunId(), cfg.getMaxIterations());
Scenario scenario = ScenarioUtils.createScenario(config);
scenario.getPopulation().getFactory().getRouteFactories()
        .setRouteFactory(DrtRoute.class, new DrtRouteFactory());
ScenarioUtils.loadScenario(scenario);
return scenario;
```
(Add a `getPaths()` accessor to `HAGRIDSimulationConfig` if not present, or pass `lausitzBaseConfig()` via a new getter.) Run Step-3 test → PASS.
- [ ] **Step 5: edit `SimulationRunnerUtils.runSimulation`** — branch at the top:
```java
if (cfg.isDrtScenario()) {
    Scenario scenario = DrtScenarioBuilder.build(cfg);
    Controler controler = new Controler(scenario);
    DrtConfigComposer.installModules(controler);
    LOG.info("DRT passenger-only run '{}' (fleet {}).", cfg.getRunId(), cfg.getFleetSize());
    controler.run();
    logDuration("Simulation '" + cfg.getRunId() + "'", t0);
    return;
}
// ... existing freight path unchanged ...
```
(No freight-zone load, no `CarrierModule`/`HAGRIDSimulationModule` for DRT.)
- [ ] **Step 6: run the full DRT-related test subset green; confirm non-DRT path compiles unchanged.**
Run: `mvn -pl parcel-demand-2-matsim-pipeline test -Dtest=DrtScenarioBuilderTest,HAGRIDSimulationConfigTest,ScenarioParsingTest`
Expected: PASS.
- [ ] **Step 7: commit.**
```bash
git add parcel-demand-2-matsim-pipeline/src/main/java/hagrid/simulation/HAGRIDSimulationConfig.java parcel-demand-2-matsim-pipeline/src/main/java/hagrid/simulation/SimulationRunnerUtils.java parcel-demand-2-matsim-pipeline/src/main/java/hagrid/simulation/DrtScenarioBuilder.java parcel-demand-2-matsim-pipeline/src/test/java/hagrid/simulation/DrtScenarioBuilderTest.java parcel-demand-2-matsim-pipeline/src/test/java/hagrid/simulation/HAGRIDSimulationConfigTest.java
git commit -m "feat(drt): passenger-only DRT run path (freight-decoupled) via DrtScenarioBuilder"
```

---

## Task 5: End-to-end DRT_BASELINE run test (real iteration through the production path)

**Files:**
- Test: `.../hagrid/integrated/drt/DrtBaselineEndToEndTest.java`

**Interfaces:**
- Consumes: `LausitzDrtPreprocessor.run(...)` + `DrtScenarioBuilder.build` + `DrtConfigComposer.installModules` (the production path), with a tiny synthetic fixture (NOT the 137 MB plans).

- [ ] **Step 1: write the test** `runsDrtBaselineOneIteration()`: build a tiny full network (car square + 1 rail link) + a small `person` population inside a `square(2000)` + a temp service-area shapefile + a temp config file modelled on the native one (PT/counts present, so the configurator's strip path is exercised). Run `LausitzDrtPreprocessor.run(...)` to produce inputs, then `DrtScenarioBuilder.build` + `Controler` + `installModules` + `controler.run()` with `lastIteration=0`. Assert a `drt_*` output file exists.
- [ ] **Step 2: run it.**
Run: `mvn -pl parcel-demand-2-matsim-pipeline test -Dtest=DrtBaselineEndToEndTest#runsDrtBaselineOneIteration`
Expected: PASS (mirrors the existing `DrtBaselineIntegrationTest` but through the production preprocessor + builder + configurator).
- [ ] **Step 3: commit.**
```bash
git add parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/drt/DrtBaselineEndToEndTest.java
git commit -m "test(drt): end-to-end DRT_BASELINE one-iteration run through production path"
```

---

## Task 6: `run_drt_baseline.bat` — chain preprocessing + sim

**Files:**
- Create: `run_drt_baseline.bat` (repo root)

- [ ] **Step 1: write the bat** — two `mvn exec:java` invocations with identical scenario args so the runId matches: first `mainClass=hagrid.integrated.drt.PrepareLausitzDrtInputs`, then `mainClass=hagrid.HAGRIDSimulationRunner`, args `concept=drt_baseline,date=2025-05-13,studyArea=LAUSITZ_HOYERSWERDA,fleetSize=20,maxIter=1`. Mirror `run_analysis.bat`'s `MAVEN_OPTS` (`-Xmx16g`) + `cd /d "%~dp0"` + exit-code propagation.
- [ ] **Step 2: smoke-run locally** (staged data from Task 0 present):
Run: `./run_drt_baseline.bat`
Expected: preprocessing writes `hagrid-output/DRT_BASELINE_13052025/DRT_BASELINE_13052025_drt_{network,population,fleet}.*`, then MATSim runs ≥1 iteration and writes a `drt_*` output. (This is the manual acceptance check; capture the console tail.)
- [ ] **Step 3: commit.**
```bash
git add run_drt_baseline.bat
git commit -m "chore(drt): run_drt_baseline.bat chains preprocessing + passenger DRT sim"
```

---

## Self-Review

- **Spec coverage:** 1b-prep's three gaps are covered — (1) preprocessing entry point = Tasks 2+3+6; (2) staged real Lausitz data = Task 0; (3) runnable passenger DRT = Tasks 1+4+5. Freight ("supply demand") is explicitly deferred (D6) per the passenger-only milestone.
- **Open risks flagged for execution:** (a) exact signatures of `SnzActivities.addScoringParams`, `RideScoringParamsFromCarParams`, `PrepareNetwork.prepareDrtNetwork` — confirmed in Task 1's spike (fallbacks specified); (b) whether the stripped native config runs cleanly with no `person`-only-incompatible leftovers — surfaced by Task 5's end-to-end run; (c) the full 100% plans + full network run is memory-heavy — bounded by population clipping (D4) and `-Xmx16g`; the real-data acceptance is Task 6 Step 2, not an automated test.
- **Type consistency:** preprocessor output paths come only from `HAGRIDSimulationConfig.getDrtNetworkClipped/getPassengerPlansClipped/getDrtFleetFile`; the configurator consumes those same strings; `DrtScenarioBuilder` and the runner branch consume the configurator. No name drift.
- **Untouched-path guarantee:** all production changes are either new files or `if (isDrtScenario())` guards; HANNOVER (never DRT) is unaffected.
