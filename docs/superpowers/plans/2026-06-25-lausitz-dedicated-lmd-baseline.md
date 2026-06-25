# Lausitz Dedicated LMD (Baseline) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Run the PANDA parcel demand for Hoyerswerda through HAGRID's jsprit/`CarrierModule` freight pipeline as an independent, Lausitz-scoped MATSim run triggered by a new `Scenario.LMD_BASELINE`.

**Architecture:** A new `hagrid.integrated.freight` preprocessor reads the PANDA demand shapefile, groups it into **one carrier per LSP** anchored at that LSP's synthetic depot, builds carriers directly via the MATSim freight API (`CarriersUtils`/`CarrierService.Builder`/`CarrierVehicle.Builder` — the same calls `CarrierGenerator` makes internally), routes them offline with `CarriersUtils.runJsprit`, and writes a run-scoped routed carrier XML. The run path loads that XML on the staged Lausitz car network and executes it with `CarrierModule`. The Hannover/freight path stays byte-for-byte untouched, gated on the scenario.

**Tech Stack:** Java 21, MATSim `2025.0-PR3552`, `org.matsim.freight.carriers.*` (jsprit via `CarriersUtils.runJsprit`), GeoTools `SimpleFeature`, JUnit5 + AssertJ + `MatsimTestUtils`. Maven module `parcel-demand-2-matsim-pipeline`, portable repo `mvn`.

**Spec:** `docs/superpowers/specs/2026-06-25-lausitz-dedicated-lmd-baseline-design.md`.

**Spike-informed reuse boundary (resolved before this plan):** `CarrierGenerator.addAndGetCarrierService`/`addCarrierServicesToCarriers`/`addCarrierVehiclesToCarrier` are **private** and `CarrierGenerator.run()` is a Guice `Runnable` that pulls four scenario elements (`deliveries`, `parcelServiceNetwork`, `carrierVehicleTypes`, `hubList`) and mutates a `summary` element. We therefore **replicate** the handful of builder calls + the service-duration formula in `hagrid.integrated.freight` (no Guice, no scenario-element coupling) and **reuse the data + model + jsprit + module**: the van vehicle-types XML, the `min(durationPerParcel·60·count, maxDurationPerStop·60)` formula, `CarriersUtils.runJsprit`, `CarrierModule`, and the existing `FreightEventHandler`/dashboard.

## Global Constraints

- **Module/package:** all new code under `hagrid.integrated.freight`; tests under the mirrored `src/test/java/hagrid/integrated/freight` path. Maven module = `parcel-demand-2-matsim-pipeline`.
- **Backward compatibility (non-negotiable):** the Hannover/freight path stays byte-for-byte untouched. All new behaviour is gated on the new scenario; existing `BASECASE`/`WHITE_LABEL`/… runs and the `DRT_*` runs are unaffected.
- **Enum value name:** `LMD_BASELINE`. Study area is **`StudyArea.LAUSITZ_HOYERSWERDA`** (there is NO `StudyArea.LAUSITZ`).
- **Multi-LSP status quo:** one carrier per LSP {`dhl`, `amazon`, `hermes`, `dpd`, `gls`, `ups`, `fedex`}, each from its own depot. No PLZ/KMeans split, no parcel-locker services, no supply/inbound carriers, no white-label, no cargobikes (vans `ct_cep_size_m`=165 / `ct_cep_size_l`=230 only).
- **No region filter:** trust the PANDA clip; do NOT call `GeoUtils.filterFeaturesByRegions` (Hannover-hardcoded via `NAME_3` + postal-code table).
- **Service-duration model (reused verbatim):** `min(durationPerParcel·60·count, maxDurationPerStop·60)` seconds; `getDurationPerParcel()`/`getMaxDurationPerStop()` are `int` minutes.
- **Demand schema:** per-provider B2C = `<provider>_tag`, B2B = `<provider>_type` (fallback `<provider>_typ`); attribute names truncated to 10 chars; geometry at attribute index `0` (Point); `postal_cod` (String); `id` (Long). Two columns are summed (match the code, not the stale "tag − type" comment).
- **No remote fetches at run time** — every file ref is a staged local path.
- **TDD, DRY, YAGNI, frequent commits.** No placeholders, complete code in every step.
- **Windows/PowerShell dev box:** run a single test with `mvn -pl parcel-demand-2-matsim-pipeline test -Dtest=ClassName#method`.

---

## File Structure

**Created (main):**
- `hagrid/integrated/freight/LmdDepotLoader.java` — reads the provider-tagged 7-depot CSV, snaps each depot to the nearest car link, returns `Map<String, Id<Link>>` keyed by lowercase provider.
- `hagrid/integrated/freight/LmdDemandReader.java` — reads the PANDA demand shapefile and converts features to `Map<String, List<Delivery>>` keyed by lowercase provider (no PLZ, no region filter, no locker).
- `hagrid/integrated/freight/LmdCarrierBuilder.java` — builds one `Carrier` per provider: a `CarrierService` per delivery stop (service-duration formula) + van vehicles anchored at the provider depot.
- `hagrid/integrated/freight/LausitzFreightPreprocessor.java` — orchestration + CLI: read → group → snap depots → build carriers → load scenario (network + van types + `FreightCarriersConfigGroup`) → `CarriersUtils.runJsprit` → write routed carrier XML.

**Modified (main):**
- `hagrid/HagridConfig.java` — add `Scenario.LMD_BASELINE` + `requiresLausitz()` helper.
- `hagrid/simulation/SimulationRunnerUtils.java` — `parseScenario` forces/validates LAUSITZ for LMD; `runSimulation` LMD branch.
- `hagrid/simulation/HAGRIDSimulationConfig.java` — `isLmdBaseline()`; Lausitz hub-CSV / demand / van-types / routed-carrier-XML getters; `validateInputFiles` LMD branch.
- `hagrid/HagridPaths.java` — Lausitz LMD input getters (hub CSV, demand dir, van types) + run-scoped routed-carrier-XML output getter.
- `docs/DATA-LAUSITZ.md` — staging rows (7-depot hub CSV, PANDA demand, Lausitz freight van-types).

**Created (test):** mirrored test classes per task; `run_lmd_baseline.bat` at repo root.

---

## Task 1: Spike — confirm PANDA read + freight/jsprit API on the classpath

**Goal:** De-risk the two unknowns the spec flagged before writing production code: (1) the PANDA demand shapefile reads through MATSim's `GeoFileReader` and yields sane per-provider counts; (2) `CarriersUtils.runJsprit(Scenario)`, `CarrierService.Builder`, `CarrierVehicle.Builder` exist with the expected signatures in the PR3552 freight jar. This is exploratory — its deliverable is a findings note committed under `docs/superpowers/`, not a permanent test.

**Files:**
- Create (throwaway, deleted in Step 5): `parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/freight/SpikePandaReadTest.java`
- Create: `docs/superpowers/notes/2026-06-25-lmd-spike-findings.md`

- [ ] **Step 1: Stage the PANDA demand into HAGRID**

The PANDA export is at `~/Documents/GitHub/PANDA/output/lausitz/hagrid_parcel_demand_2025-05-13_(Tuesday).shp` (+ sidecars `.dbf/.shx/.prj`). Copy all sidecars into `parcel-demand-2-matsim-pipeline/hagrid-input/lausitz/demand/`:

```bash
mkdir -p parcel-demand-2-matsim-pipeline/hagrid-input/lausitz/demand
cp ~/Documents/GitHub/PANDA/output/lausitz/hagrid_parcel_demand_2025-05-13_\(Tuesday\).* \
   parcel-demand-2-matsim-pipeline/hagrid-input/lausitz/demand/
```

- [ ] **Step 2: Write the spike probe**

```java
package hagrid.integrated.freight;

import org.geotools.api.feature.simple.SimpleFeature;
import org.junit.jupiter.api.Test;
import org.matsim.core.utils.gis.GeoFileReader;
import org.matsim.freight.carriers.CarriersUtils;

import java.nio.file.Path;
import java.util.Collection;

import static org.assertj.core.api.Assertions.assertThat;

class SpikePandaReadTest {
    static final String[] PROVIDERS = {"amazon", "dhl", "dpd", "fedex", "gls", "hermes", "ups"};

    @Test
    void readsPandaDemandAndCountsProviders() {
        Path shp = Path.of("hagrid-input/lausitz/demand/hagrid_parcel_demand_2025-05-13_(Tuesday).shp");
        Collection<SimpleFeature> features = new GeoFileReader().readFileAndInitialize(shp.toString());
        assertThat(features).isNotEmpty();
        System.out.println("Features: " + features.size());

        long grand = 0;
        for (String p : PROVIDERS) {
            String tag = safe10(p + "_tag"), type = safe10(p + "_type"), typ = safe10(p + "_typ");
            long b2c = 0, b2b = 0;
            for (SimpleFeature f : features) {
                b2c += asLong(f.getAttribute(tag));
                Object t = f.getAttribute(type);
                if (t == null) t = f.getAttribute(typ);
                b2b += asLong(t);
            }
            System.out.printf("%-7s B2B=%d B2C=%d total=%d%n", p, b2b, b2c, b2b + b2c);
            grand += b2b + b2c;
        }
        System.out.println("GRAND TOTAL parcels = " + grand);
        assertThat(grand).isGreaterThan(0);

        // API smoke: the method we will call in Task 8 must resolve at compile + load time.
        assertThat(CarriersUtils.class.getMethods())
                .anyMatch(m -> m.getName().equals("runJsprit"));
    }

    static String safe10(String s) { return s.length() > 10 ? s.substring(0, 10) : s; }
    static long asLong(Object v) { return v instanceof Number ? ((Number) v).longValue() : 0L; }
}
```

- [ ] **Step 3: Run the spike**

Run: `mvn -pl parcel-demand-2-matsim-pipeline test -Dtest=SpikePandaReadTest`
Expected: PASS. Capture the printed per-provider counts and `GRAND TOTAL` from the console.

- [ ] **Step 4: Confirm the freight builder signatures**

Inspect the freight jar to lock the exact builder signatures used in Tasks 7–8 (do this once; record the output):

```bash
JAR=$(find ~/.m2/repository/org/matsim -name 'matsim-*PR3552*.jar' | head -1)
javap -cp "$JAR" org.matsim.freight.carriers.CarriersUtils | grep -E 'runJsprit|createCarrier|addService|addCarrierVehicle|setJspritIterations|writeCarriers|loadCarriers'
javap -cp "$JAR" 'org.matsim.freight.carriers.CarrierService$Builder' | grep -E 'newInstance|setCapacityDemand|setServiceDuration|setServiceStartingTimeWindow|build'
javap -cp "$JAR" 'org.matsim.freight.carriers.CarrierVehicle$Builder' | grep -E 'newInstance|setEarliestStart|setLatestEnd|build'
```

- [ ] **Step 5: Record findings + remove the throwaway test**

Write `docs/superpowers/notes/2026-06-25-lmd-spike-findings.md` with: the feature count + per-provider B2B/B2C totals + grand total (cross-check vs PANDA's reported ~6.35k), and the verbatim `javap` signatures for the calls used downstream. Then delete `SpikePandaReadTest.java` (it referenced a real data path, not a portable fixture).

- [ ] **Step 6: Commit**

```bash
git add docs/superpowers/notes/2026-06-25-lmd-spike-findings.md
git commit -m "spike(lmd): confirm PANDA demand reads + freight/jsprit API signatures"
```

---

## Task 2: `Scenario.LMD_BASELINE` + `requiresLausitz()`

**Files:**
- Modify: `parcel-demand-2-matsim-pipeline/src/main/java/hagrid/HagridConfig.java` (enum at `:61-78`)
- Test: `parcel-demand-2-matsim-pipeline/src/test/java/hagrid/ScenarioEnumTest.java` (new)

**Interfaces:**
- Produces: `HagridConfig.Scenario.LMD_BASELINE`; `Scenario.isDrt()` (unchanged — returns `false` for `LMD_BASELINE`); `Scenario.requiresLausitz()` (new — `true` for all `DRT_*` and `LMD_BASELINE`, else `false`).

- [ ] **Step 1: Write the failing test**

```java
package hagrid;

import hagrid.HagridConfig.Scenario;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScenarioEnumTest {
    @Test
    void lmdBaselineIsFreightButLausitzBound() {
        assertThat(Scenario.LMD_BASELINE.isDrt()).isFalse();
        assertThat(Scenario.LMD_BASELINE.requiresLausitz()).isTrue();
    }

    @Test
    void drtScenariosRequireLausitz() {
        assertThat(Scenario.DRT_BASELINE.requiresLausitz()).isTrue();
        assertThat(Scenario.DRT_SHAREDUSE.requiresLausitz()).isTrue();
        assertThat(Scenario.DRT_MODULAR.requiresLausitz()).isTrue();
    }

    @Test
    void hannoverConceptsDoNotRequireLausitz() {
        assertThat(Scenario.BASECASE.requiresLausitz()).isFalse();
        assertThat(Scenario.BASECASE.isDrt()).isFalse();
        assertThat(Scenario.WHITE_LABEL.requiresLausitz()).isFalse();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl parcel-demand-2-matsim-pipeline test -Dtest=ScenarioEnumTest`
Expected: FAIL — `LMD_BASELINE` / `requiresLausitz` do not exist (compile error).

- [ ] **Step 3: Add the enum value + helper**

In `HagridConfig.java`, change the enum (currently ending `DRT_MODULAR;`) to append `LMD_BASELINE` and add `requiresLausitz()`:

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
        DRT_MODULAR,     // capsule swap (Phase 1d)
        LMD_BASELINE;    // dedicated conventional multi-LSP last-mile delivery, Lausitz (freight, non-DRT)

        /** True for the integrated passenger+freight DRT scenarios (require StudyArea.LAUSITZ_HOYERSWERDA). */
        public boolean isDrt() {
            return this == DRT_BASELINE || this == DRT_SHAREDUSE || this == DRT_MODULAR;
        }

        /** True for every Lausitz-bound scenario (all DRT scenarios + the dedicated LMD baseline). */
        public boolean requiresLausitz() {
            return isDrt() || this == LMD_BASELINE;
        }
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl parcel-demand-2-matsim-pipeline test -Dtest=ScenarioEnumTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/src/main/java/hagrid/HagridConfig.java \
        parcel-demand-2-matsim-pipeline/src/test/java/hagrid/ScenarioEnumTest.java
git commit -m "feat(lmd): add Scenario.LMD_BASELINE + requiresLausitz()"
```

---

## Task 3: CLI parse forces + validates LAUSITZ for `LMD_BASELINE`

**Files:**
- Modify: `parcel-demand-2-matsim-pipeline/src/main/java/hagrid/simulation/SimulationRunnerUtils.java` (`parseScenario`, `:101-152`)
- Modify: `parcel-demand-2-matsim-pipeline/src/main/java/hagrid/simulation/HAGRIDSimulationConfig.java` (add `isLmdBaseline()` near `isDrtScenario()` `:385-391`)
- Test: `parcel-demand-2-matsim-pipeline/src/test/java/hagrid/simulation/ParseScenarioLmdTest.java` (new)

**Interfaces:**
- Consumes: `Scenario.requiresLausitz()` (Task 2).
- Produces: `HAGRIDSimulationConfig.isLmdBaseline()` (`true` iff `concept` maps to `LMD_BASELINE`). `parseScenario` returns a config whose `getStudyArea() == LAUSITZ_HOYERSWERDA` for `concept=LMD_BASELINE` (defaulted when omitted), and throws `IllegalArgumentException` if `studyArea=HANNOVER` is passed with `concept=LMD_BASELINE`.

- [ ] **Step 1: Write the failing test**

```java
package hagrid.simulation;

import hagrid.utils.general.StudyArea;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("parseScenario for LMD_BASELINE")
class ParseScenarioLmdTest {

    @Test
    @DisplayName("LMD_BASELINE defaults the study area to LAUSITZ_HOYERSWERDA")
    void defaultsToLausitz() {
        HAGRIDSimulationConfig cfg = SimulationRunnerUtils.parseScenario(
                "concept=LMD_BASELINE,date=2025-05-13");
        assertThat(cfg.getStudyArea()).isEqualTo(StudyArea.LAUSITZ_HOYERSWERDA);
        assertThat(cfg.isLmdBaseline()).isTrue();
        assertThat(cfg.isDrtScenario()).isFalse();
    }

    @Test
    @DisplayName("LMD_BASELINE with studyArea=HANNOVER is rejected")
    void rejectsHannover() {
        assertThatThrownBy(() -> SimulationRunnerUtils.parseScenario(
                "concept=LMD_BASELINE,date=2025-05-13,studyArea=HANNOVER"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("LAUSITZ_HOYERSWERDA");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl parcel-demand-2-matsim-pipeline test -Dtest=ParseScenarioLmdTest`
Expected: FAIL — `isLmdBaseline()` missing and no LAUSITZ defaulting for LMD.

- [ ] **Step 3: Add `isLmdBaseline()` to `HAGRIDSimulationConfig`**

Insert directly after `isDrtScenario()` (`:391`):

```java
    /** True iff the concept maps to the dedicated Lausitz LMD baseline. */
    public boolean isLmdBaseline() {
        try {
            return HagridConfig.Scenario.valueOf(concept.toUpperCase())
                    == HagridConfig.Scenario.LMD_BASELINE;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }
```

- [ ] **Step 4: Force + validate LAUSITZ in `parseScenario`**

In `SimulationRunnerUtils.parseScenario`, replace the existing study-area + DRT-validation block (`:132-146`) with one that uses `requiresLausitz()` and defaults/forces the area:

```java
        StudyArea studyArea = StudyArea.valueOf(
                map.getOrDefault("studyArea", "HANNOVER").trim().toUpperCase());
        int fleetSize = positiveInt(map.getOrDefault("fleetSize", "50"), "fleetSize");

        // Lausitz-bound concepts (all DRT scenarios + LMD_BASELINE) require LAUSITZ_HOYERSWERDA.
        boolean requiresLausitz;
        try {
            requiresLausitz = hagrid.HagridConfig.Scenario.valueOf(concept.toUpperCase()).requiresLausitz();
        } catch (IllegalArgumentException ex) {
            requiresLausitz = false;
        }
        if (requiresLausitz) {
            if (!map.containsKey("studyArea")) {
                studyArea = StudyArea.LAUSITZ_HOYERSWERDA;   // default it for the user
            } else if (studyArea != StudyArea.LAUSITZ_HOYERSWERDA) {
                throw new IllegalArgumentException(
                        "concept '" + concept + "' requires studyArea=LAUSITZ_HOYERSWERDA, got " + studyArea);
            }
        }
```

(Leave the `return new HAGRIDSimulationConfig(...)` call at `:151-152` unchanged — it already passes `studyArea`.)

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -pl parcel-demand-2-matsim-pipeline test -Dtest=ParseScenarioLmdTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/src/main/java/hagrid/simulation/SimulationRunnerUtils.java \
        parcel-demand-2-matsim-pipeline/src/main/java/hagrid/simulation/HAGRIDSimulationConfig.java \
        parcel-demand-2-matsim-pipeline/src/test/java/hagrid/simulation/ParseScenarioLmdTest.java
git commit -m "feat(lmd): parseScenario forces+validates LAUSITZ for LMD_BASELINE"
```

---

## Task 4: Lausitz LMD path getters

**Files:**
- Modify: `parcel-demand-2-matsim-pipeline/src/main/java/hagrid/HagridPaths.java` (Lausitz input getters near `:283-318`; run-scoped output near `:320-345`)
- Modify: `parcel-demand-2-matsim-pipeline/src/main/java/hagrid/simulation/HAGRIDSimulationConfig.java` (delegating getters near the Lausitz block `:357-480`)
- Test: `parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/freight/LmdPathsTest.java` (new)

**Interfaces:**
- Produces (HagridPaths): `lmdDepotCsv()`, `lmdDemandShapefile()`, `lmdVehicleTypes()` (staged inputs under `inputBase`); `lmdCarriersRouted()` (run-scoped output under `carrierDir()`).
- Produces (HAGRIDSimulationConfig): `getLmdDepotCsv()`, `getLmdDemandShapefile()`, `getLmdVehicleTypes()`, `getLmdCarriersRouted()` (all `String`).

- [ ] **Step 1: Write the failing test**

```java
package hagrid.integrated.freight;

import hagrid.HagridPaths;
import hagrid.utils.general.StudyArea;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("HagridPaths LMD getters")
class LmdPathsTest {

    @Test
    @DisplayName("LMD input + run-scoped output getters resolve under the Lausitz roots")
    void lmdGettersResolve() {
        HagridPaths paths = new HagridPaths(StudyArea.LAUSITZ_HOYERSWERDA);
        paths.initializeRun("LMD_BASELINE_13052025");

        assertThat(paths.lmdDepotCsv()).contains("lausitz").endsWith("hubs/lmd-depots.csv");
        assertThat(paths.lmdDemandShapefile()).contains("lausitz")
                .endsWith("demand/hagrid_parcel_demand_2025-05-13_(Tuesday).shp");
        assertThat(paths.lmdVehicleTypes()).contains("lausitz").endsWith("vehicles/lmd-vehicle-types.xml");
        assertThat(paths.lmdCarriersRouted()).contains("LMD_BASELINE_13052025")
                .endsWith("lmd_carriers_routed.xml");
    }
}
```

> NOTE: confirm the single-arg `new HagridPaths(StudyArea)` constructor exists (the report cites `new HagridPaths(StudyArea)` usage at `HagridConfig.java:48` and the two-arg `HagridPaths(Path, StudyArea)` at `:94`). If only the two-arg form is public, use the same construction the existing Lausitz tests use (e.g. `RailPathsTest`).

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl parcel-demand-2-matsim-pipeline test -Dtest=LmdPathsTest`
Expected: FAIL — getters not defined.

- [ ] **Step 3: Add the getters in `HagridPaths.java`**

In the Lausitz input block (after `lausitzVehicleTypes()` `:318`):

```java
    /** Provider-tagged synthetic LMD depot CSV (one row per LSP). */
    public String lmdDepotCsv() {
        return inputBase.resolve("hubs").resolve("lmd-depots.csv").toString();
    }

    /** PANDA parcel-demand shapefile staged for the LMD baseline (date-named as exported). */
    public String lmdDemandShapefile() {
        return inputBase.resolve("demand")
                .resolve("hagrid_parcel_demand_2025-05-13_(Tuesday).shp").toString();
    }

    /** Lausitz freight van vehicle-types (ct_cep_size_m / _l only). */
    public String lmdVehicleTypes() {
        return inputBase.resolve("vehicles").resolve("lmd-vehicle-types.xml").toString();
    }
```

In the carrier output block (after `carrierPlansCombined()` `:250`):

```java
    /** Routed LMD carrier plans for this run (jsprit output). */
    public String lmdCarriersRouted() {
        return carrierDir().resolve(p() + "lmd_carriers_routed.xml").toString();
    }
```

- [ ] **Step 4: Add delegating getters in `HAGRIDSimulationConfig.java`**

Insert in the Lausitz getter block (after `getLausitzVehicleTypes()`):

```java
    public String getLmdDepotCsv() { return paths.lmdDepotCsv(); }
    public String getLmdDemandShapefile() { return paths.lmdDemandShapefile(); }
    public String getLmdVehicleTypes() { return paths.lmdVehicleTypes(); }
    public String getLmdCarriersRouted() { return paths.lmdCarriersRouted(); }
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -pl parcel-demand-2-matsim-pipeline test -Dtest=LmdPathsTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/src/main/java/hagrid/HagridPaths.java \
        parcel-demand-2-matsim-pipeline/src/main/java/hagrid/simulation/HAGRIDSimulationConfig.java \
        parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/freight/LmdPathsTest.java
git commit -m "feat(lmd): Lausitz LMD path getters (depot CSV, demand, van types, routed carriers)"
```

---

## Task 5: `LmdDepotLoader` — read the 7-depot CSV + snap to network

**Files:**
- Create: `parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/freight/LmdDepotLoader.java`
- Test: `parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/freight/LmdDepotLoaderTest.java`

**Interfaces:**
- Produces: `static Map<String, Id<Link>> load(String csvPath, Network network)` — parses a semicolon CSV with header `provider;x;y` (one row per LSP), snaps each `(x,y)` to the nearest link via `NetworkUtils.getNearestLinkExactly`, returns provider(lowercased) → link id. Throws `IllegalStateException` on a missing/empty file or a provider not in the known set.
- Consumes: nothing from earlier tasks.

**CSV format (documented here, staged in Task 10):** header line `provider;x;y`, then 7 rows, e.g. `dhl;EPSG25832_x;EPSG25832_y`. Coordinates in EPSG:25832 (same CRS as the demand + network).

- [ ] **Step 1: Write the failing test**

```java
package hagrid.integrated.freight;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.network.NetworkUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LmdDepotLoader")
class LmdDepotLoaderTest {

    private Network twoLinkNetwork() {
        Network net = NetworkUtils.createNetwork();
        Node a = NetworkUtils.createAndAddNode(net, Id.createNodeId("a"), new Coord(0, 0));
        Node b = NetworkUtils.createAndAddNode(net, Id.createNodeId("b"), new Coord(1000, 0));
        Node c = NetworkUtils.createAndAddNode(net, Id.createNodeId("c"), new Coord(2000, 0));
        NetworkUtils.createAndAddLink(net, Id.createLinkId("ab"), a, b, 1000, 13.9, 1800, 1);
        NetworkUtils.createAndAddLink(net, Id.createLinkId("bc"), b, c, 1000, 13.9, 1800, 1);
        return net;
    }

    @Test
    @DisplayName("load() snaps each provider depot to the nearest link")
    void loadsAndSnaps(@TempDir Path tmp) throws Exception {
        Path csv = tmp.resolve("lmd-depots.csv");
        Files.writeString(csv, "provider;x;y\ndhl;100;10\nhermes;1900;10\n");

        Map<String, Id<Link>> depots = LmdDepotLoader.load(csv.toString(), twoLinkNetwork());

        assertThat(depots).containsOnlyKeys("dhl", "hermes");
        assertThat(depots.get("dhl")).isEqualTo(Id.createLinkId("ab"));
        assertThat(depots.get("hermes")).isEqualTo(Id.createLinkId("bc"));
    }

    @Test
    @DisplayName("load() rejects an empty file")
    void rejectsEmpty(@TempDir Path tmp) throws Exception {
        Path csv = tmp.resolve("empty.csv");
        Files.writeString(csv, "provider;x;y\n");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> LmdDepotLoader.load(csv.toString(), twoLinkNetwork()))
                .isInstanceOf(IllegalStateException.class);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl parcel-demand-2-matsim-pipeline test -Dtest=LmdDepotLoaderTest`
Expected: FAIL — `LmdDepotLoader` does not exist.

- [ ] **Step 3: Write the implementation**

```java
package hagrid.integrated.freight;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.network.NetworkUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loads the synthetic LMD depot CSV (one row per LSP) and snaps each depot to the nearest
 * car link. CSV format: header {@code provider;x;y}, coordinates in EPSG:25832.
 */
public final class LmdDepotLoader {

    /** The seven LSPs modelled in the Lausitz LMD baseline. */
    public static final Set<String> PROVIDERS =
            Set.of("dhl", "amazon", "hermes", "dpd", "gls", "ups", "fedex");

    private LmdDepotLoader() {}

    public static Map<String, Id<Link>> load(String csvPath, Network network) {
        List<String> lines;
        try {
            lines = Files.readAllLines(Path.of(csvPath));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read LMD depot CSV: " + csvPath, e);
        }

        Map<String, Id<Link>> depots = new LinkedHashMap<>();
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.toLowerCase().startsWith("provider")) {
                continue; // skip header + blank lines
            }
            String[] parts = line.split(";");
            if (parts.length < 3) {
                throw new IllegalStateException("Malformed LMD depot row (need provider;x;y): " + line);
            }
            String provider = parts[0].trim().toLowerCase();
            if (!PROVIDERS.contains(provider)) {
                throw new IllegalStateException("Unknown LMD provider in depot CSV: " + provider);
            }
            double x = Double.parseDouble(parts[1].trim());
            double y = Double.parseDouble(parts[2].trim());
            Link link = NetworkUtils.getNearestLinkExactly(network, new Coord(x, y));
            if (link == null) {
                throw new IllegalStateException("No network link near depot for " + provider);
            }
            depots.put(provider, link.getId());
        }

        if (depots.isEmpty()) {
            throw new IllegalStateException("LMD depot CSV contained no depot rows: " + csvPath);
        }
        return depots;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl parcel-demand-2-matsim-pipeline test -Dtest=LmdDepotLoaderTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/freight/LmdDepotLoader.java \
        parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/freight/LmdDepotLoaderTest.java
git commit -m "feat(lmd): LmdDepotLoader (provider-tagged depot CSV snapped to network)"
```

---

## Task 6: `LmdDemandReader` — features → per-LSP `Delivery` groups

**Files:**
- Create: `parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/freight/LmdDemandReader.java`
- Test: `parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/freight/LmdDemandReaderTest.java`

**Interfaces:**
- Produces:
  - `static Map<String, List<Delivery>> group(Collection<SimpleFeature> features)` — for each feature, for each provider, reads B2C (`<provider>_tag`) + B2B (`<provider>_type`/`_typ`), and (if > 0) builds one `Delivery` per channel keyed under the lowercased provider. No PLZ split, no region filter, no locker.
  - `static Collection<SimpleFeature> read(String shpPath)` — thin wrapper over `new GeoFileReader().readFileAndInitialize(shpPath)`.
- Consumes: `hagrid.utils.demand.Delivery` (existing Lombok `@Builder`); `Delivery.ParcelType`, `Delivery.DeliveryMode`.

- [ ] **Step 1: Write the failing test (pure grouping, in-memory features)**

```java
package hagrid.integrated.freight;

import hagrid.utils.demand.Delivery;
import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.api.feature.simple.SimpleFeatureType;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LmdDemandReader.group")
class LmdDemandReaderTest {

    private SimpleFeatureType type() {
        SimpleFeatureTypeBuilder b = new SimpleFeatureTypeBuilder();
        b.setName("demand");
        b.add("the_geom", Point.class);
        b.add("id", Long.class);
        b.add("postal_cod", String.class);
        b.add("dhl_tag", Long.class);
        b.add("dhl_type", Long.class);
        b.add("hermes_tag", Long.class);
        b.add("hermes_type", Long.class);
        return b.buildFeatureType();
    }

    private SimpleFeature feature(SimpleFeatureType t, long id, double x, double y,
                                  long dhlB2c, long dhlB2b, long herB2c) {
        GeometryFactory gf = new GeometryFactory();
        Point p = gf.createPoint(new org.locationtech.jts.geom.Coordinate(x, y));
        SimpleFeatureBuilder fb = new SimpleFeatureBuilder(t);
        fb.add(p);
        fb.add(id);
        fb.add("02977");
        fb.add(dhlB2c);
        fb.add(dhlB2b);
        fb.add(herB2c);
        fb.add(0L);
        return fb.buildFeature(String.valueOf(id));
    }

    @Test
    @DisplayName("groups deliveries by provider, splitting B2B and B2C")
    void groupsByProvider() {
        SimpleFeatureType t = type();
        List<SimpleFeature> features = List.of(
                feature(t, 1, 100, 100, 5, 2, 3),   // dhl 5 B2C + 2 B2B, hermes 3 B2C
                feature(t, 2, 200, 200, 0, 0, 4));  // hermes 4 B2C only

        Map<String, List<Delivery>> grouped = LmdDemandReader.group(features);

        assertThat(grouped).containsOnlyKeys("dhl", "hermes");
        // dhl: one B2C delivery (5) + one B2B delivery (2) from feature 1
        assertThat(grouped.get("dhl")).hasSize(2);
        assertThat(grouped.get("dhl")).anyMatch(
                d -> d.getParcelType() == Delivery.ParcelType.B2B && d.getAmount() == 2);
        assertThat(grouped.get("dhl")).anyMatch(
                d -> d.getParcelType() == Delivery.ParcelType.B2C && d.getAmount() == 5);
        // hermes: B2C 3 (feature 1) + B2C 4 (feature 2)
        assertThat(grouped.get("hermes")).hasSize(2);
        assertThat(grouped.get("hermes")).allMatch(d -> d.getParcelType() == Delivery.ParcelType.B2C);
        assertThat(grouped.get("hermes")).allMatch(d -> d.getDeliveryMode() == Delivery.DeliveryMode.HOME);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl parcel-demand-2-matsim-pipeline test -Dtest=LmdDemandReaderTest`
Expected: FAIL — `LmdDemandReader` does not exist.

- [ ] **Step 3: Write the implementation**

```java
package hagrid.integrated.freight;

import hagrid.utils.demand.Delivery;
import org.geotools.api.feature.simple.SimpleFeature;
import org.locationtech.jts.geom.Point;
import org.matsim.api.core.v01.Coord;
import org.matsim.core.utils.gis.GeoFileReader;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the PANDA parcel-demand shapefile and groups it into one bucket per LSP for the Lausitz
 * LMD baseline. Each segment feature yields, per provider, up to two {@link Delivery} objects:
 * one B2C ({@code <provider>_tag}) and one B2B ({@code <provider>_type}/{@code _typ}). No PLZ
 * grouping, no region filtering, no parcel-locker diversion — every delivery is a HOME stop at
 * the segment point (decision: B2C door-vs-Packstation is a non-question for the segment demand).
 */
public final class LmdDemandReader {

    /** Same seven LSPs as {@link LmdDepotLoader#PROVIDERS}, ordered for stable output. */
    static final String[] PROVIDERS = {"dhl", "amazon", "hermes", "dpd", "gls", "ups", "fedex"};

    private LmdDemandReader() {}

    public static Collection<SimpleFeature> read(String shpPath) {
        try {
            return new GeoFileReader().readFileAndInitialize(shpPath);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read LMD demand shapefile: " + shpPath, e);
        }
    }

    public static Map<String, List<Delivery>> group(Collection<SimpleFeature> features) {
        Map<String, List<Delivery>> grouped = new LinkedHashMap<>();
        for (String p : PROVIDERS) {
            grouped.put(p, new ArrayList<>());
        }

        for (SimpleFeature feature : features) {
            Point point = ((Point) feature.getAttribute(0)).getCentroid();
            Coord coord = new Coord(point.getX(), point.getY());
            String pointId = String.valueOf(feature.getAttribute("id"));
            String postalCode = (String) feature.getAttribute("postal_cod");

            for (String provider : PROVIDERS) {
                long b2c = asLong(feature.getAttribute(safe10(provider + "_tag")));
                Object b2bAttr = feature.getAttribute(safe10(provider + "_type"));
                if (b2bAttr == null) {
                    b2bAttr = feature.getAttribute(safe10(provider + "_typ"));
                }
                long b2b = asLong(b2bAttr);

                if (b2b > 0) {
                    grouped.get(provider).add(delivery(pointId + "_B2B", coord, provider,
                            Delivery.ParcelType.B2B, (int) b2b, postalCode));
                }
                if (b2c > 0) {
                    grouped.get(provider).add(delivery(pointId + "_B2C", coord, provider,
                            Delivery.ParcelType.B2C, (int) b2c, postalCode));
                }
            }
        }

        // Drop providers with no demand so downstream builds no empty carriers.
        grouped.values().removeIf(List::isEmpty);
        return grouped;
    }

    private static Delivery delivery(String id, Coord coord, String provider,
                                     Delivery.ParcelType type, int amount, String postalCode) {
        return Delivery.builder()
                .id(id)
                .coordinate(coord)
                .provider(provider)
                .parcelType(type)
                .amount(amount)
                .postalCode(postalCode)
                .deliveryMode(Delivery.DeliveryMode.HOME)
                .build();
    }

    static String safe10(String s) { return s.length() > 10 ? s.substring(0, 10) : s; }
    static long asLong(Object v) { return v instanceof Number ? ((Number) v).longValue() : 0L; }
}
```

> NOTE: if `removeIf` on the `values()` view is rejected by the JDK collection, copy to a new map filtering empties — but `LinkedHashMap.values().removeIf` is supported and removes the corresponding entries.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl parcel-demand-2-matsim-pipeline test -Dtest=LmdDemandReaderTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/freight/LmdDemandReader.java \
        parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/freight/LmdDemandReaderTest.java
git commit -m "feat(lmd): LmdDemandReader (PANDA features -> per-LSP Delivery groups)"
```

---

## Task 7: `LmdCarrierBuilder` — one carrier per LSP (services + van vehicles)

**Files:**
- Create: `parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/freight/LmdCarrierBuilder.java`
- Test: `parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/freight/LmdCarrierBuilderTest.java`

**Interfaces:**
- Produces: `static Carrier build(String provider, List<Delivery> deliveries, Id<Link> depotLink, Network network, VehicleType[] vanTypes, int durationPerParcelMin, int maxDurationPerStopMin)` — returns a `Carrier` id=`provider` with one `CarrierService` per delivery (capacity = `amount`, duration via the reused formula, snapped to the nearest link) and, per van type, one `CarrierVehicle` at `depotLink`; `FleetSize.INFINITE`; carrier mode `car`.
- Consumes: `Delivery` (Task 6); `LmdDepotLoader` output (Task 5). Service-duration formula mirrors `CarrierGenerator.java:968-970`.

- [ ] **Step 1: Write the failing test**

```java
package hagrid.integrated.freight;

import hagrid.utils.demand.Delivery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.network.NetworkUtils;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierService;
import org.matsim.freight.carriers.CarrierVehicle;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LmdCarrierBuilder")
class LmdCarrierBuilderTest {

    private Network net() {
        Network n = NetworkUtils.createNetwork();
        Node a = NetworkUtils.createAndAddNode(n, Id.createNodeId("a"), new Coord(0, 0));
        Node b = NetworkUtils.createAndAddNode(n, Id.createNodeId("b"), new Coord(1000, 0));
        NetworkUtils.createAndAddLink(n, Id.createLinkId("ab"), a, b, 1000, 13.9, 1800, 1);
        return n;
    }

    private VehicleType van(String id, double cap) {
        VehicleType t = VehicleUtils.createVehicleType(Id.create(id, VehicleType.class));
        t.getCapacity().setOther(cap);
        t.setNetworkMode("car");
        return t;
    }

    @Test
    @DisplayName("builds a carrier with one service per delivery + a van per type at the depot")
    void buildsCarrier() {
        Network n = net();
        List<Delivery> deliveries = List.of(
                Delivery.builder().id("d1_B2C").coordinate(new Coord(100, 0)).provider("dhl")
                        .parcelType(Delivery.ParcelType.B2C).amount(10)
                        .deliveryMode(Delivery.DeliveryMode.HOME).postalCode("02977").build(),
                Delivery.builder().id("d2_B2B").coordinate(new Coord(900, 0)).provider("dhl")
                        .parcelType(Delivery.ParcelType.B2B).amount(3)
                        .deliveryMode(Delivery.DeliveryMode.HOME).postalCode("02977").build());
        VehicleType[] vans = {van("ct_cep_size_m", 165), van("ct_cep_size_l", 230)};

        Carrier carrier = LmdCarrierBuilder.build(
                "dhl", deliveries, Id.createLinkId("ab"), n, vans,
                /*durationPerParcelMin*/ 2, /*maxDurationPerStopMin*/ 15);

        assertThat(carrier.getId().toString()).isEqualTo("dhl");
        assertThat(carrier.getServices()).hasSize(2);
        // 10 parcels -> 2*10=20 min > 15 cap -> 900s ; 3 parcels -> 2*3=6 min -> 360s
        assertThat(carrier.getServices().values())
                .extracting(CarrierService::getServiceDuration)
                .containsExactlyInAnyOrder(900.0, 360.0);
        // one vehicle per van type, both at the depot link
        assertThat(carrier.getCarrierCapabilities().getCarrierVehicles()).hasSize(2);
        assertThat(carrier.getCarrierCapabilities().getCarrierVehicles().values())
                .allMatch(v -> v.getLinkId().equals(Id.createLinkId("ab")));
    }
}
```

> NOTE: `CarrierService.getServiceDuration()` is the accessor in this MATSim version (confirmed against the builder `setServiceDuration` in Task 1's javap). If the accessor is named differently, adjust the assertion to match the javap output recorded in the spike note.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl parcel-demand-2-matsim-pipeline test -Dtest=LmdCarrierBuilderTest`
Expected: FAIL — `LmdCarrierBuilder` does not exist.

- [ ] **Step 3: Write the implementation**

```java
package hagrid.integrated.freight;

import hagrid.utils.demand.Delivery;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.network.NetworkUtils;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierCapabilities.FleetSize;
import org.matsim.freight.carriers.CarrierService;
import org.matsim.freight.carriers.CarrierVehicle;
import org.matsim.freight.carriers.CarriersUtils;
import org.matsim.freight.carriers.TimeWindow;
import org.matsim.vehicles.VehicleType;

import java.util.List;

/**
 * Builds a single {@link Carrier} for one LSP in the Lausitz LMD baseline. One {@link CarrierService}
 * per {@link Delivery} (capacity = parcel count, duration via the reused HAGRID formula
 * {@code min(durationPerParcel*60*count, maxDurationPerStop*60)}), and one {@link CarrierVehicle}
 * per van type anchored at the LSP depot link. Fleet size is INFINITE so jsprit decides tour count.
 */
public final class LmdCarrierBuilder {

    /** Whole-day operating window for the delivery vehicles + services (08:00–20:00). */
    private static final double DAY_START = 8 * 3600;
    private static final double DAY_END = 20 * 3600;

    private LmdCarrierBuilder() {}

    public static Carrier build(String provider, List<Delivery> deliveries, Id<Link> depotLink,
                                Network network, VehicleType[] vanTypes,
                                int durationPerParcelMin, int maxDurationPerStopMin) {
        Carrier carrier = CarriersUtils.createCarrier(Id.create(provider, Carrier.class));
        CarriersUtils.setCarrierMode(carrier, "car");
        carrier.getCarrierCapabilities().setFleetSize(FleetSize.INFINITE);

        int n = 0;
        for (Delivery d : deliveries) {
            Link link = NetworkUtils.getNearestLinkExactly(network, d.getCoordinate());
            double duration = Math.min(
                    (durationPerParcelMin * 60.0) * d.getAmount(),
                    maxDurationPerStopMin * 60.0);
            CarrierService service = CarrierService.Builder
                    .newInstance(Id.create(provider + "_" + n++, CarrierService.class), link.getId())
                    .setCapacityDemand(d.getAmount())
                    .setServiceDuration(duration)
                    .setServiceStartingTimeWindow(TimeWindow.newInstance(DAY_START, DAY_END))
                    .build();
            CarriersUtils.addService(carrier, service);
        }

        for (VehicleType vanType : vanTypes) {
            CarrierVehicle vehicle = CarrierVehicle.Builder
                    .newInstance(Id.createVehicleId(provider + "_" + vanType.getId().toString()),
                            depotLink, vanType)
                    .setEarliestStart(DAY_START)
                    .setLatestEnd(DAY_END)
                    .build();
            CarriersUtils.addCarrierVehicle(carrier, vehicle);
        }

        return carrier;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl parcel-demand-2-matsim-pipeline test -Dtest=LmdCarrierBuilderTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/freight/LmdCarrierBuilder.java \
        parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/freight/LmdCarrierBuilderTest.java
git commit -m "feat(lmd): LmdCarrierBuilder (one carrier per LSP, services + van fleet at depot)"
```

---

## Task 8: `LausitzFreightPreprocessor` — orchestrate + jsprit + write routed carriers

**Files:**
- Create: `parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/freight/LausitzFreightPreprocessor.java`
- Test: `parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/freight/LausitzFreightPreprocessorTest.java`

**Interfaces:**
- Produces: `static void run(String demandShp, String depotCsv, String networkFile, String vehicleTypesFile, String carriersOut, int jspritIterations)` — reads demand → `LmdDemandReader.group` → `LmdDepotLoader.load` → per provider `LmdCarrierBuilder.build` → loads carriers + van types into a fresh `Scenario` with `FreightCarriersConfigGroup` → sets per-carrier jsprit iterations → `CarriersUtils.runJsprit(scenario)` → `CarrierPlanWriter` to `carriersOut`. Also a `main(String[])` CLI reading `(demandShp, depotCsv, networkFile, vehicleTypesFile, carriersOut, jspritIterations)`.
- Consumes: Tasks 5, 6, 7; `durationPerParcel`/`maxDurationPerStop` defaults (2 / 15) passed to the builder.

- [ ] **Step 1: Write the failing integration test (tiny synthetic scenario)**

```java
package hagrid.integrated.freight;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.io.NetworkWriter;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierPlanXmlReader;
import org.matsim.freight.carriers.Carriers;
import org.matsim.freight.carriers.CarrierVehicleTypeWriter;
import org.matsim.freight.carriers.CarrierVehicleTypes;
import org.matsim.testcases.MatsimTestUtils;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LausitzFreightPreprocessor")
class LausitzFreightPreprocessorTest {

    @RegisterExtension
    public MatsimTestUtils utils = new MatsimTestUtils();

    @Test
    @DisplayName("run() produces a routed carrier XML with one carrier per demanded LSP")
    void producesRoutedCarriers() throws Exception {
        Path dir = Path.of(utils.getOutputDirectory());

        // tiny grid network (car), 4 links in a square
        var net = NetworkUtils.createNetwork();
        Node a = NetworkUtils.createAndAddNode(net, Id.createNodeId("a"), new Coord(0, 0));
        Node b = NetworkUtils.createAndAddNode(net, Id.createNodeId("b"), new Coord(1000, 0));
        Node c = NetworkUtils.createAndAddNode(net, Id.createNodeId("c"), new Coord(1000, 1000));
        Node d = NetworkUtils.createAndAddNode(net, Id.createNodeId("d"), new Coord(0, 1000));
        for (var e : new Node[][]{{a, b}, {b, c}, {c, d}, {d, a}, {b, a}, {c, b}, {d, c}, {a, d}}) {
            NetworkUtils.createAndAddLink(net, Id.createLinkId(e[0].getId() + "_" + e[1].getId()),
                    e[0], e[1], 1000, 13.9, 1800, 1);
        }
        Path netФile = dir.resolve("net.xml.gz");
        new NetworkWriter(net).write(netФile.toString());

        // van types XML
        CarrierVehicleTypes types = new CarrierVehicleTypes();
        VehicleType m = VehicleUtils.createVehicleType(Id.create("ct_cep_size_m", VehicleType.class));
        m.getCapacity().setOther(165); m.setNetworkMode("car");
        m.getCostInformation().setCostsPerMeter(0.0004).setCostsPerSecond(0.0).setFixedCost(170.0);
        types.getVehicleTypes().put(m.getId(), m);
        Path typesFile = dir.resolve("vans.xml");
        new CarrierVehicleTypeWriter(types).write(typesFile.toString());

        // depot CSV (dhl + hermes) and demand shapefile-free path:
        Path depotCsv = dir.resolve("depots.csv");
        Files.writeString(depotCsv, "provider;x;y\ndhl;0;0\nhermes;1000;1000\n");

        // Write a demand shapefile with 3 points carrying dhl + hermes parcels.
        Path demandShp = dir.resolve("demand.shp");
        LmdTestShapefiles.writeDemand(demandShp,
                new double[][]{{200, 100}, {800, 100}, {500, 900}},
                new long[]{4, 6, 0},     // dhl_tag (B2C)
                new long[]{1, 0, 0},     // dhl_type (B2B)
                new long[]{0, 0, 5});    // hermes_tag (B2C)

        Path carriersOut = dir.resolve("lmd_carriers_routed.xml");
        LausitzFreightPreprocessor.run(demandShp.toString(), depotCsv.toString(),
                netФile.toString(), typesFile.toString(), carriersOut.toString(), /*jsprit*/ 1);

        assertThat(Files.exists(carriersOut)).isTrue();

        // load the result and assert carriers + selected (routed) plans
        var scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        Carriers carriers = new Carriers();
        new CarrierPlanXmlReader(carriers, types).readFile(carriersOut.toString());
        assertThat(carriers.getCarriers()).containsOnlyKeys(
                Id.create("dhl", Carrier.class), Id.create("hermes", Carrier.class));
        assertThat(carriers.getCarriers().get(Id.create("dhl", Carrier.class)).getSelectedPlan())
                .as("dhl carrier must have a routed (selected) plan").isNotNull();
    }
}
```

> NOTE: `LmdTestShapefiles.writeDemand(...)` is a shared test helper that writes a point shapefile with columns `id, postal_cod, dhl_tag, dhl_type, hermes_tag, hermes_type` using GeoTools' `ShapefileDataStoreFactory` + `SimpleFeatureBuilder`. Point shapefiles write cleanly (the JTS/GeoTools skew noted in the repo affects **polygon** writes); if a write fails in this environment, fall back to writing via `org.matsim.core.utils.gis.GeoFileWriter.writeFeatures(features, path)`. Create this helper in Step 1b.

- [ ] **Step 1b: Create the shapefile test helper**

```java
package hagrid.integrated.freight;

import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.api.feature.simple.SimpleFeatureType;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.matsim.core.utils.gis.GeoFileWriter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Writes tiny point demand shapefiles for LMD tests (columns match the PANDA schema subset). */
final class LmdTestShapefiles {
    private LmdTestShapefiles() {}

    static void writeDemand(Path shp, double[][] xy, long[] dhlTag, long[] dhlType, long[] hermesTag) {
        SimpleFeatureTypeBuilder tb = new SimpleFeatureTypeBuilder();
        tb.setName("demand");
        tb.setCRS(org.geotools.referencing.CRS.decodeQuietly("EPSG:25832"));
        tb.add("the_geom", Point.class);
        tb.add("id", Long.class);
        tb.add("postal_cod", String.class);
        tb.add("dhl_tag", Long.class);
        tb.add("dhl_type", Long.class);
        tb.add("hermes_tag", Long.class);
        tb.add("hermes_type", Long.class);
        SimpleFeatureType type = tb.buildFeatureType();

        GeometryFactory gf = new GeometryFactory();
        SimpleFeatureBuilder fb = new SimpleFeatureBuilder(type);
        List<SimpleFeature> features = new ArrayList<>();
        for (int i = 0; i < xy.length; i++) {
            Point p = gf.createPoint(new Coordinate(xy[i][0], xy[i][1]));
            fb.add(p);
            fb.add((long) (i + 1));
            fb.add("02977");
            fb.add(dhlTag[i]);
            fb.add(dhlType[i]);
            fb.add(hermesTag[i]);
            fb.add(0L);
            features.add(fb.buildFeature(String.valueOf(i + 1)));
        }
        GeoFileWriter.writeFeatures(features, shp.toString());
    }
}
```

> NOTE: `GeoFileWriter.writeFeatures(Collection<SimpleFeature>, String)` is the MATSim writer used elsewhere in the repo; confirm the exact static signature against an existing caller (search `GeoFileWriter`). If the project instead uses GeoTools' `ShapefileDataStore` directly somewhere, mirror that. Either way this is a **point** shapefile, which is safe to write.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl parcel-demand-2-matsim-pipeline test -Dtest=LausitzFreightPreprocessorTest`
Expected: FAIL — `LausitzFreightPreprocessor` does not exist.

- [ ] **Step 3: Write the implementation**

```java
package hagrid.integrated.freight;

import hagrid.utils.demand.Delivery;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.freight.carriers.*;
import org.matsim.vehicles.VehicleType;

import java.util.List;
import java.util.Map;

/**
 * Lausitz dedicated-LMD preprocessor: PANDA demand + per-LSP synthetic depots -> one carrier per LSP
 * -> offline jsprit routing -> routed carrier XML. Reuses the MATSim freight API + van vehicle-types
 * data + the HAGRID service-duration model; deliberately bypasses the Hannover Guice pipeline
 * (region filter / PLZ split / lockers / supply / white-label).
 */
public final class LausitzFreightPreprocessor {

    /** HAGRID defaults (minutes), reused for comparability with the Hannover LMD. */
    private static final int DURATION_PER_PARCEL_MIN = 2;
    private static final int MAX_DURATION_PER_STOP_MIN = 15;

    private LausitzFreightPreprocessor() {}

    public static void run(String demandShp, String depotCsv, String networkFile,
                           String vehicleTypesFile, String carriersOut, int jspritIterations) {
        // 1. network
        Config config = ConfigUtils.createConfig();
        config.network().setInputFile(networkFile);
        Scenario scenario = ScenarioUtils.loadScenario(config);
        Network network = scenario.getNetwork();

        // 2. van vehicle types
        CarrierVehicleTypes vehicleTypes = new CarrierVehicleTypes();
        new CarrierVehicleTypeReader(vehicleTypes).readFile(vehicleTypesFile);
        VehicleType[] vans = vehicleTypes.getVehicleTypes().values().toArray(new VehicleType[0]);
        if (vans.length == 0) {
            throw new IllegalStateException("No van vehicle types loaded from " + vehicleTypesFile);
        }

        // 3. demand -> per-LSP deliveries ; depots -> per-LSP link
        Map<String, List<Delivery>> byProvider = LmdDemandReader.group(LmdDemandReader.read(demandShp));
        Map<String, Id<Link>> depots = LmdDepotLoader.load(depotCsv, network);

        // 4. one carrier per demanded LSP, anchored at its depot
        Carriers carriers = new Carriers();
        for (Map.Entry<String, List<Delivery>> e : byProvider.entrySet()) {
            String provider = e.getKey();
            Id<Link> depot = depots.get(provider);
            if (depot == null) {
                throw new IllegalStateException("No depot for provider with demand: " + provider);
            }
            Carrier carrier = LmdCarrierBuilder.build(provider, e.getValue(), depot, network, vans,
                    DURATION_PER_PARCEL_MIN, MAX_DURATION_PER_STOP_MIN);
            CarriersUtils.setJspritIterations(carrier, Math.max(1, jspritIterations));
            carriers.addCarrier(carrier);
        }

        // 5. load freight config + carriers into the scenario, then route offline with jsprit
        FreightCarriersConfigGroup freightConfig =
                ConfigUtils.addOrGetModule(config, FreightCarriersConfigGroup.class);
        freightConfig.setCarriersFile(null);
        CarriersUtils.addOrGetCarriers(scenario).getCarriers().putAll(carriers.getCarriers());
        for (VehicleType vt : vans) {
            CarriersUtils.getCarrierVehicleTypes(scenario).getVehicleTypes().put(vt.getId(), vt);
        }
        CarriersUtils.runJsprit(scenario);

        // 6. write the routed carriers
        new CarrierPlanWriter(CarriersUtils.getCarriers(scenario)).write(carriersOut);
    }

    public static void main(String[] args) {
        if (args.length < 6) {
            throw new IllegalArgumentException(
                    "Usage: demandShp depotCsv networkFile vehicleTypesFile carriersOut jspritIterations");
        }
        run(args[0], args[1], args[2], args[3], args[4], Integer.parseInt(args[5]));
    }
}
```

> NOTE: the exact `CarriersUtils` helpers (`addOrGetCarriers`, `getCarrierVehicleTypes`, `getCarriers`, `runJsprit`, `setJspritIterations`) must match the javap from Task 1 Step 4. The verbatim names there win; adjust this block to the recorded signatures (e.g. some MATSim versions use `CarriersUtils.getCarriers(scenario)` returning the scenario `Carriers`, and load types via `CarriersUtils.getCarrierVehicleTypes(scenario)`). Do not invent — copy from the spike note.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl parcel-demand-2-matsim-pipeline test -Dtest=LausitzFreightPreprocessorTest`
Expected: PASS (carrier XML written, both carriers have selected/routed plans).

- [ ] **Step 5: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/freight/LausitzFreightPreprocessor.java \
        parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/freight/LausitzFreightPreprocessorTest.java \
        parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/freight/LmdTestShapefiles.java
git commit -m "feat(lmd): LausitzFreightPreprocessor (group -> build -> jsprit -> routed carrier XML)"
```

---

## Task 9: Run-path LMD branch + input validation

**Files:**
- Modify: `parcel-demand-2-matsim-pipeline/src/main/java/hagrid/simulation/SimulationRunnerUtils.java` (`runSimulation`, the DRT short-circuit at `:209-217`)
- Modify: `parcel-demand-2-matsim-pipeline/src/main/java/hagrid/simulation/HAGRIDSimulationConfig.java` (`validateInputFiles`, `:489-527`)
- Test: `parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/freight/LmdRunBranchTest.java` (new)

**Interfaces:**
- Consumes: `HAGRIDSimulationConfig.isLmdBaseline()` (Task 3); the LMD path getters (Task 4); `LausitzFreightPreprocessor` (Task 8).
- Produces: an `else if (cfg.isLmdBaseline())` branch in `runSimulation` that (1) runs the preprocessor to produce the routed carrier XML, (2) builds a freight scenario on the staged Lausitz network with the routed carriers + van types loaded via `FreightCarriersConfigGroup`, (3) installs `CarrierModule`, (4) runs the Controler. `validateInputFiles` gains an LMD branch requiring the demand shp + depot CSV + van types + Lausitz network, and the Hannover freight block is guarded to exclude LMD.

- [ ] **Step 1: Write the failing test (validation contract — fast, no MATSim run)**

```java
package hagrid.integrated.freight;

import hagrid.simulation.SimulationRunnerUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("LMD run branch + validation")
class LmdRunBranchTest {

    @Test
    @DisplayName("validateInputFiles for LMD complains about the LMD inputs, not Hannover carriers")
    void validatesLmdInputs() {
        var cfg = SimulationRunnerUtils.parseScenario("concept=LMD_BASELINE,date=2025-05-13,tag=VALTEST");
        assertThatThrownBy(cfg::validateInputFiles)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LMD")          // names an LMD input
                .hasMessageNotContaining("Supply carriers"); // NOT the Hannover freight checks
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl parcel-demand-2-matsim-pipeline test -Dtest=LmdRunBranchTest`
Expected: FAIL — currently LMD falls into the Hannover `!isDrtScenario()` block and reports "Supply carriers" missing.

- [ ] **Step 3: Add the `validateInputFiles` LMD branch**

In `HAGRIDSimulationConfig.validateInputFiles()`, change the first guard and add an LMD block:

```java
        if (!isDrtScenario() && !isLmdBaseline()) {
            checkFile(getConfigPath(), "Simulation config", missing);
            checkFile(getVehicleTypePath(), "Vehicle types", missing);
            checkFile(getCarNetworkPath(), "Car network", missing);
            checkFile(getBikeNetworkPath(), "Bike network", missing);
            checkFile(getNetworkChangeEventPath(), "Network change events", missing);
            checkFile(getFreightZonePath(), "Freight zone shapefile", missing);
            checkFile(getDeliveryCarrierPath(), "Delivery carriers", missing);
            checkFile(getSupplyCarrierPath(), "Supply carriers", missing);
        }

        if (isLmdBaseline()) {
            checkFile(Path.of(getLmdDemandShapefile()), "LMD demand shapefile", missing);
            checkFile(Path.of(getLmdDepotCsv()), "LMD depot CSV", missing);
            checkFile(Path.of(getLmdVehicleTypes()), "LMD vehicle types", missing);
            checkFile(Path.of(getLausitzNetworkRaw()), "Lausitz network", missing);
        }
```

> NOTE: `getLausitzNetworkRaw()` should already exist (the report cites `HagridPaths.lausitzNetworkRaw()`); if there's no delegating `HAGRIDSimulationConfig` getter yet, add `public String getLausitzNetworkRaw() { return paths.lausitzNetworkRaw(); }` next to the other Lausitz getters.

- [ ] **Step 4: Add the `runSimulation` LMD branch**

In `SimulationRunnerUtils.runSimulation`, immediately after the DRT short-circuit `return;` (`:217`) and before the freight section, insert:

```java
        // LMD baseline: dedicated conventional multi-LSP delivery on the Lausitz network.
        if (cfg.isLmdBaseline()) {
            // 1. preprocess: produce the routed carrier XML
            hagrid.integrated.freight.LausitzFreightPreprocessor.run(
                    cfg.getLmdDemandShapefile(), cfg.getLmdDepotCsv(),
                    cfg.getLausitzNetworkRaw(), cfg.getLmdVehicleTypes(),
                    cfg.getLmdCarriersRouted(), cfg.getJspritIterations());

            // 2. build the run scenario on the Lausitz network with the routed carriers
            Config config = ConfigUtils.createConfig();
            config.network().setInputFile(cfg.getLausitzNetworkRaw());
            config.controller().setOutputDirectory(cfg.getOutputDirectoryAsString());
            config.controller().setRunId(cfg.getRunId());
            config.controller().setLastIteration(cfg.getMaxIterations());
            config.controller().setOverwriteFileSetting(
                    org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
            FreightCarriersConfigGroup freightConfig =
                    ConfigUtils.addOrGetModule(config, FreightCarriersConfigGroup.class);
            freightConfig.setCarriersFile(cfg.getLmdCarriersRouted());
            freightConfig.setCarriersVehicleTypesFile(cfg.getLmdVehicleTypes());

            Scenario scenario = ScenarioUtils.loadScenario(config);
            CarriersUtils.loadCarriersAccordingToFreightConfig(scenario);

            Controler controler = new Controler(scenario);
            controler.addOverridingModule(new CarrierModule());
            LOG.info("LMD baseline run '{}' on the Lausitz network.", cfg.getRunId());
            controler.run();
            logDuration("Simulation '" + cfg.getRunId() + "'", t0);
            return;
        }
```

Add imports if missing: `org.matsim.core.config.Config`, `org.matsim.core.config.ConfigUtils`, `org.matsim.core.scenario.ScenarioUtils`, `org.matsim.freight.carriers.CarriersUtils`, `org.matsim.freight.carriers.CarrierModule`, `org.matsim.freight.carriers.FreightCarriersConfigGroup`.

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -pl parcel-demand-2-matsim-pipeline test -Dtest=LmdRunBranchTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/src/main/java/hagrid/simulation/SimulationRunnerUtils.java \
        parcel-demand-2-matsim-pipeline/src/main/java/hagrid/simulation/HAGRIDSimulationConfig.java \
        parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/freight/LmdRunBranchTest.java
git commit -m "feat(lmd): runSimulation LMD branch + LMD input validation"
```

---

## Task 10: Real-data smoke test + staging + run script

**Files:**
- Create: `parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/freight/LmdBaselineEndToEndTest.java`
- Create: `run_lmd_baseline.bat` (repo root)
- Modify: `docs/DATA-LAUSITZ.md` (staging rows)
- Create (staged, git-ignored data): `hagrid-input/lausitz/hubs/lmd-depots.csv`, `hagrid-input/lausitz/vehicles/lmd-vehicle-types.xml`

**Interfaces:**
- Consumes: the whole chain (Tasks 2–9). The e2e test is gated to run only when the staged real inputs exist (so CI without data is green).

- [ ] **Step 1: Stage the depot CSV + van types + network**

Create `hagrid-input/lausitz/hubs/lmd-depots.csv` with the 7 provider rows (coordinates EPSG:25832 — peripheral, good Autobahn connectivity; finalize values with Hendrik; the real regional depots in the spec are the documented fallback). Header `provider;x;y`, then `dhl;…;…` etc. for all of {dhl, amazon, hermes, dpd, gls, ups, fedex}.

Create `hagrid-input/lausitz/vehicles/lmd-vehicle-types.xml` containing only the van types `ct_cep_size_m` (165) and `ct_cep_size_l` (230), copied verbatim from `parcel-demand-2-matsim-pipeline/sim-input/carrier/BASECASE_13052025_carrier_files/BASECASE_13052025_vehicle_types.xml` (drop `ct_cep_bike` and the supply truck).

Confirm the staged Lausitz network exists at `hagrid-input/lausitz/network/lausitz-network.xml.gz` (the DRT side stages it; reuse it). The PANDA demand was staged in Task 1 Step 1.

- [ ] **Step 2: Add staging rows to `docs/DATA-LAUSITZ.md`**

```markdown
| `demand/hagrid_parcel_demand_2025-05-13_(Tuesday).shp` (+ .dbf/.shx/.prj) | `~/Documents/GitHub/PANDA/output/lausitz/` (PANDA export) |
| `hubs/lmd-depots.csv` | Authored: 7 synthetic per-LSP depots (EPSG:25832), peripheral/Autobahn-near; real regional depots documented in the LMD design spec as fallback |
| `vehicles/lmd-vehicle-types.xml` | Subset (vans `ct_cep_size_m`/`_l`) of `sim-input/carrier/BASECASE_13052025_carrier_files/BASECASE_13052025_vehicle_types.xml` |
```

- [ ] **Step 3: Write the data-gated e2e smoke test**

```java
package hagrid.integrated.freight;

import hagrid.simulation.HAGRIDSimulationConfig;
import hagrid.simulation.SimulationRunnerUtils;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LMD baseline end-to-end (real staged data)")
class LmdBaselineEndToEndTest {

    @Test
    @DisplayName("a maxIter=0 LMD_BASELINE run boots, routes carriers, and produces freight output")
    void bootsOnRealData() throws Exception {
        HAGRIDSimulationConfig cfg = SimulationRunnerUtils.parseScenario(
                "concept=LMD_BASELINE,date=2025-05-13,maxIter=0,jspritIter=1,tag=SMOKE");

        // Skip cleanly when the real Lausitz inputs are not staged on this machine.
        Assumptions.assumeTrue(Files.exists(Path.of(cfg.getLmdDemandShapefile())),
                "PANDA demand not staged — skipping e2e");
        Assumptions.assumeTrue(Files.exists(Path.of(cfg.getLmdDepotCsv())),
                "LMD depot CSV not staged — skipping e2e");

        cfg.validateInputFiles();
        SimulationRunnerUtils.runSimulation(cfg);

        // routed carrier XML + a MATSim freight output dir were produced
        assertThat(Files.exists(Path.of(cfg.getLmdCarriersRouted()))).isTrue();
        assertThat(Files.exists(cfg.getOutputDirectory())).isTrue();
    }
}
```

- [ ] **Step 4: Run the e2e test**

Run: `mvn -pl parcel-demand-2-matsim-pipeline test -Dtest=LmdBaselineEndToEndTest`
Expected: PASS (or skipped if data absent). With data staged: the run boots, jsprit routes ~7 carriers, MATSim executes maxIter=0, the routed carrier XML + `hagrid-matsim-output/LMD_BASELINE_13052025_SMOKE/` exist.

- [ ] **Step 5: Add the run script**

Create `run_lmd_baseline.bat` (mirror `run_drt_baseline.bat`):

```bat
@echo off
REM Dedicated LMD baseline (Lausitz). Adjust date/jspritIter as needed.
call mvnw.cmd -q -pl parcel-demand-2-matsim-pipeline exec:java ^
  -Dexec.mainClass=hagrid.simulation.HAGRIDSimulationRunner ^
  -Dexec.args="concept=LMD_BASELINE,date=2025-05-13,maxIter=0,jspritIter=100,tag=v1"
```

- [ ] **Step 6: Run the full module suite (regression — Hannover + DRT untouched)**

Run: `mvn -pl parcel-demand-2-matsim-pipeline test`
Expected: PASS — all pre-existing Hannover + DRT tests still green; new LMD tests green; e2e skipped or green depending on staged data.

- [ ] **Step 7: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/freight/LmdBaselineEndToEndTest.java \
        run_lmd_baseline.bat docs/DATA-LAUSITZ.md
git commit -m "test(lmd): real-data e2e smoke test + DATA-LAUSITZ staging + run script"
```

---

## Self-Review

**Spec coverage:**
- §A Scenario + gating → Tasks 2 (enum), 3 (parse/validate), 9 (run branch).
- §B preprocessor + per-LSP carriers + reuse boundary → Tasks 6, 7, 8.
- §B depot loader (provider-bound) → Task 5.
- §C data flow (demand → carriers → jsprit → carrier XML → CarrierModule) → Tasks 6–9.
- §D depot model (7 per-LSP, peripheral) → Tasks 5 + 10 (staging).
- §E network + paths → Tasks 4 (paths) + 9/10 (Lausitz network reuse).
- §"skipped/off" (region filter, supply, locker, white-label, cargobikes) → enforced by construction in Tasks 6–8 (we never call those code paths) + Task 9 validation guard.
- Spike-first (PANDA read + jsprit API) → Task 1.
- Output reuse (FreightEventHandler/dashboard) → CarrierModule run in Task 9; dashboard generation is the existing post-run path (unchanged), exercised in Task 10.

**Placeholder scan:** No "TBD"/"implement later". Three `NOTE:` callouts ask the implementer to reconcile exact MATSim freight signatures against the **Task 1 javap output** — that is a deliberate spike-verification handoff (the spec mandates spike-first), not a content gap; the code blocks are complete and runnable as written against the documented signatures.

**Type consistency:** `Map<String, List<Delivery>>` (provider→deliveries) produced by `LmdDemandReader.group` (Task 6) and consumed by the preprocessor (Task 8); `Map<String, Id<Link>>` (provider→depot) produced by `LmdDepotLoader.load` (Task 5) and consumed in Task 8; `LmdCarrierBuilder.build(...)` signature (Task 7) matches its call in Task 8; provider keys are lowercased consistently in Tasks 5/6/7/8; getter names (`getLmdDemandShapefile`/`getLmdDepotCsv`/`getLmdVehicleTypes`/`getLmdCarriersRouted`) defined in Task 4 and used in Tasks 9/10; `isLmdBaseline()` defined in Task 3, used in Tasks 9. `requiresLausitz()` defined in Task 2, used in Task 3.

**Open risks carried into execution (flagged, not blocking):**
- Exact `CarriersUtils` jsprit-load/run helper names — pinned by Task 1 Step 4 javap; Task 8's NOTE says copy from the spike note.
- Point-shapefile write in tests (`GeoFileWriter.writeFeatures`) — confirm the static signature against an existing repo caller; point writes avoid the polygon JTS/GeoTools skew.
- `CarrierService.getServiceDuration()` accessor name — confirm via the same javap.
