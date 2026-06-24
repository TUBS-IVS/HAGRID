# Lausitz Rail-PT + DRT-Intermodality Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Re-enable regional **rail** PT across the Lausitz DRT scenarios and make DRT a native intermodal **feeder** to rail (DRT access/egress to rail stops), dropping bus + tram — so DRT is a clean *substitute* for bus and a clean *feeder* to rail.

**Architecture:** Builds on the completed 1b-prep passenger-only `DRT_BASELINE` (commits `8415c00..d518ada`). The change is **additive** to `LausitzDrtConfigurator`: a new `RailScheduleFilter` produces a rail-only schedule + matching transit-vehicles artifact; the configurator gains nullable `railScheduleFile` / `railTransitVehiclesFile` / `vehicleTypesFile` parameters (null ⇒ legacy DRT-only behaviour, so existing portable tests keep working; non-null ⇒ transit on, SwissRailRaptor + DRT intermodal access/egress, native vehicle-types). `DrtScenarioBuilder` tags the rail stops reachable by DRT as intermodal, reusing the **DRT service-area shapefile as the intermodal-area** (single source of truth — expanding the service area later auto-expands intermodal coverage). The rail+intermodality config lives in the shared configurator path, so it is common to Baseline / Shared-Use / Modular by construction.

**Tech Stack:** Java 21, MATSim `2025.0-PR3552`, `org.matsim.contrib:drt`+`dvrp`, `com.github.matsim-scenarios:matsim-lausitz:2.0` (native helpers `PrepareTransitSchedule.tagIntermodalStops`, `LausitzScenario.setExplicitIntermodalityParamsForWalkToPt` reused as a dependency), SwissRailRaptor (`ch.sbb.matsim.config.SwissRailRaptorConfigGroup`, auto-installed by MATSim core when transit is on), JUnit5 + AssertJ + `MatsimTestUtils`.

## Global Constraints

- **Module/package:** all new code under `hagrid.integrated.drt`; tests under the mirrored `src/test/java` path. Maven module = `parcel-demand-2-matsim-pipeline`.
- **Backward compatibility (non-negotiable):** the Hannover/freight path stays byte-for-byte untouched (gated on `HAGRIDSimulationConfig.isDrtScenario()`). The DRT-only behaviour of `LausitzDrtConfigurator.build(...)` must be preserved when the new rail params are `null`.
- **DRT stays full-DVRP + `serviceAreaBased`** (locked decision) — the rail layer does not change DRT simulation type.
- **`vspDefaultsCheckingLevel=abort` is kept** (native value, decision locked). Each new VSP-defaults violation surfaced by enabling PT must be fixed properly, not silenced.
- **Intermodal-area = the DRT service-area shapefile** (`getDrtServiceAreaShapefile()` / `serviceAreaShp` param). Do NOT stage a separate `pt-intermodal-areas-*.shp`.
- **Drop bus AND tram** — keep only `rail`-mode TransitRoutes.
- **Native passenger vehicle-types** (`vehiclesSource=modeVehicleTypesFromVehiclesData`) when the staged `vehicleTypesFile` is available; otherwise fall back to `defaultVehicle` (so unit tests stay portable).
- **No remote fetches at run time** — every file ref is a staged local path or nulled (`counts` stays nulled, `simwrapper` module stays dropped).
- **TDD, DRY, YAGNI, frequent commits.** No placeholders, no `TODO`s, complete code in every step.
- **Windows/PowerShell dev box:** Maven runs via the repo's portable `mvn`; run a single test with `mvn -pl parcel-demand-2-matsim-pipeline test -Dtest=ClassName#method`.

---

## File Structure

**Created:**
- `parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/drt/RailScheduleFilter.java` — filters a transit schedule to `rail` routes only + drops now-unreferenced transit vehicles; writes both artifacts.
- `parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/drt/RailScheduleFilterTest.java`
- `parcel-demand-2-matsim-pipeline/src/test/resources/lausitz-rail-native-like.config.xml` — fixture config mirroring the staged native config but used by rail-on tests (same as the existing `lausitz-native-like.config.xml`; the rail tests pass real rail params).

**Modified:**
- `hagrid/integrated/drt/LausitzDrtConfigurator.java` — additive rail params; re-enable transit, SwissRailRaptor, DRT intermodal access/egress, native vehicle-types; remove teleported-pt + restore `pt` as a choice mode when rail is on.
- `hagrid/integrated/drt/LausitzDrtPreprocessor.java` — produce the rail-filtered schedule + transit-vehicles artifacts.
- `hagrid/simulation/DrtScenarioBuilder.java` — thread the new params through; tag intermodal rail stops after `loadScenario`.
- `hagrid/HagridPaths.java` — staged-input getters (raw transit schedule/vehicles, native vehicle-types) + run-scoped output getters (rail-filtered schedule/vehicles).
- `hagrid/simulation/HAGRIDSimulationConfig.java` — corresponding `get*` accessors + `validateInputFiles` entries.
- `docs/DATA-LAUSITZ.md` — staging rows for transit schedule, transit vehicles, vehicle-types.
- Test updates: `LausitzDrtConfiguratorTest.java`, `DrtBaselineEndToEndTest.java` (replace the teleported-pt boot-fix locks with rail-on locks).

---

## Task 1: `RailScheduleFilter` — keep only rail routes + matching transit vehicles

**Files:**
- Create: `parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/drt/RailScheduleFilter.java`
- Test: `parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/drt/RailScheduleFilterTest.java`

**Interfaces:**
- Produces:
  - `static void filter(TransitSchedule schedule, Vehicles transitVehicles)` — in-place: removes every TransitRoute whose `getTransportMode()` is not `"rail"`, removes lines left with zero routes, and removes transit vehicles no longer referenced by any surviving departure. Stop facilities are intentionally **kept as a superset** (harmless; SwissRailRaptor only uses stops referenced by surviving routes).
  - `static void run(String scheduleIn, String vehiclesIn, String scheduleOut, String vehiclesOut)` — reads schedule + transit vehicles (mirroring `PrepareTransitSchedule.call`), applies `filter`, writes both with `TransitScheduleWriter` / `MatsimVehicleWriter`.
  - `static final String RAIL_MODE = "rail";`

- [ ] **Step 1: Write the failing test**

```java
package hagrid.integrated.drt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.transitSchedule.api.*;
import org.matsim.vehicles.*;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RailScheduleFilter")
class RailScheduleFilterTest {

    /** Builds a schedule with one rail line, one bus line, one tram line; each route has one
     *  departure referencing its own vehicle. */
    private org.matsim.api.core.v01.Scenario buildScenario() {
        var scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        TransitSchedule schedule = scenario.getTransitSchedule();
        TransitScheduleFactory sf = schedule.getFactory();
        Vehicles vehicles = scenario.getTransitVehicles();
        VehiclesFactory vf = vehicles.getFactory();

        // two stop facilities, on dummy links
        TransitStopFacility s1 = sf.createTransitStopFacility(
                Id.create("s1", TransitStopFacility.class), new org.matsim.api.core.v01.Coord(0, 0), false);
        s1.setLinkId(Id.createLinkId("l1"));
        TransitStopFacility s2 = sf.createTransitStopFacility(
                Id.create("s2", TransitStopFacility.class), new org.matsim.api.core.v01.Coord(1000, 0), false);
        s2.setLinkId(Id.createLinkId("l2"));
        schedule.addStopFacility(s1);
        schedule.addStopFacility(s2);

        VehicleType vt = vf.createVehicleType(Id.create("railVeh", VehicleType.class));
        vehicles.addVehicleType(vt);

        addLine(schedule, sf, vehicles, vf, vt, "railLine", "rail", s1, s2, "railDep", "railV");
        addLine(schedule, sf, vehicles, vf, vt, "busLine", "bus", s1, s2, "busDep", "busV");
        addLine(schedule, sf, vehicles, vf, vt, "tramLine", "tram", s1, s2, "tramDep", "tramV");
        return scenario;
    }

    private void addLine(TransitSchedule schedule, TransitScheduleFactory sf,
                         Vehicles vehicles, VehiclesFactory vf, VehicleType vt,
                         String lineId, String mode, TransitStopFacility a, TransitStopFacility b,
                         String depId, String vehId) {
        TransitLine line = sf.createTransitLine(Id.create(lineId, TransitLine.class));
        List<TransitRouteStop> stops = List.of(
                sf.createTransitRouteStop(a, 0, 0),
                sf.createTransitRouteStop(b, 600, 600));
        TransitRoute route = sf.createTransitRoute(
                Id.create(lineId + "_r", TransitRoute.class), null, stops, mode);
        Vehicle v = vf.createVehicle(Id.createVehicleId(vehId), vt);
        vehicles.addVehicle(v);
        route.addDeparture(sf.createDeparture(Id.create(depId, Departure.class), 8 * 3600));
        route.getDepartures().values().iterator().next().setVehicleId(v.getId());
        line.addRoute(route);
        schedule.addTransitLine(line);
    }

    @Test
    @DisplayName("filter() keeps only rail lines and drops bus/tram vehicles")
    void keepsOnlyRail() {
        var scenario = buildScenario();
        TransitSchedule schedule = scenario.getTransitSchedule();
        Vehicles vehicles = scenario.getTransitVehicles();

        RailScheduleFilter.filter(schedule, vehicles);

        assertThat(schedule.getTransitLines().keySet().stream().map(Id::toString))
                .containsExactly("railLine");
        // every surviving route is rail
        assertThat(schedule.getTransitLines().values().stream()
                .flatMap(l -> l.getRoutes().values().stream())
                .allMatch(r -> RailScheduleFilter.RAIL_MODE.equals(r.getTransportMode()))).isTrue();
        // bus/tram vehicles removed, rail vehicle kept
        assertThat(vehicles.getVehicles().keySet().stream().map(Id::toString))
                .containsExactly("railV");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl parcel-demand-2-matsim-pipeline test -Dtest=RailScheduleFilterTest`
Expected: FAIL — `RailScheduleFilter` does not exist (compilation error).

- [ ] **Step 3: Write minimal implementation**

```java
package hagrid.integrated.drt;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.transitSchedule.api.*;
import org.matsim.vehicles.MatsimVehicleWriter;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.Vehicles;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Filters a MATSim transit schedule down to {@code rail} TransitRoutes only (drops bus + tram),
 * and removes transit vehicles no longer referenced by any surviving departure.
 *
 * <p>Rationale (rail-PT design, 2026-06-23): rail is kept so DRT can act as a feeder; bus is
 * dropped so DRT is a clean substitute; tram is dropped (Cottbus is out of the DRT service area).
 * Mode is read per {@link TransitRoute#getTransportMode()} (gtfs2matsim tags it per route).</p>
 *
 * <p>Stop facilities are deliberately left as a superset — SwissRailRaptor only routes over stops
 * referenced by surviving routes, and pruning them risks dangling references for no benefit.</p>
 */
public final class RailScheduleFilter {

    public static final String RAIL_MODE = "rail";

    private RailScheduleFilter() {}

    /** In-place filter: keep only rail routes + the transit vehicles they use. */
    public static void filter(TransitSchedule schedule, Vehicles transitVehicles) {
        Set<Id<Vehicle>> usedVehicles = new HashSet<>();
        List<TransitLine> linesToRemove = new ArrayList<>();

        for (TransitLine line : schedule.getTransitLines().values()) {
            List<TransitRoute> routesToRemove = new ArrayList<>();
            for (TransitRoute route : line.getRoutes().values()) {
                if (!RAIL_MODE.equals(route.getTransportMode())) {
                    routesToRemove.add(route);
                } else {
                    route.getDepartures().values()
                            .forEach(d -> usedVehicles.add(d.getVehicleId()));
                }
            }
            routesToRemove.forEach(line::removeRoute);
            if (line.getRoutes().isEmpty()) {
                linesToRemove.add(line);
            }
        }
        linesToRemove.forEach(schedule::removeTransitLine);

        // Drop transit vehicles no longer referenced by any surviving departure.
        List<Id<Vehicle>> vehiclesToRemove = new ArrayList<>();
        for (Id<Vehicle> id : transitVehicles.getVehicles().keySet()) {
            if (!usedVehicles.contains(id)) {
                vehiclesToRemove.add(id);
            }
        }
        vehiclesToRemove.forEach(transitVehicles::removeVehicle);
    }

    /** File-to-file: read schedule + transit vehicles, filter, write both. */
    public static void run(String scheduleIn, String vehiclesIn, String scheduleOut, String vehiclesOut) {
        Config config = ConfigUtils.createConfig();
        config.global().setCoordinateSystem("EPSG:25832");
        config.transit().setTransitScheduleFile(scheduleIn);
        config.transit().setVehiclesFile(vehiclesIn);
        config.transit().setUseTransit(true);
        Scenario scenario = ScenarioUtils.loadScenario(config);

        filter(scenario.getTransitSchedule(), scenario.getTransitVehicles());

        new TransitScheduleWriter(scenario.getTransitSchedule()).writeFile(scheduleOut);
        new MatsimVehicleWriter(scenario.getTransitVehicles()).writeFile(vehiclesOut);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl parcel-demand-2-matsim-pipeline test -Dtest=RailScheduleFilterTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/drt/RailScheduleFilter.java \
        parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/drt/RailScheduleFilterTest.java
git commit -m "feat(drt): add RailScheduleFilter (rail-only schedule + matching transit vehicles)"
```

---

## Task 2: Path getters + DATA-LAUSITZ staging rows

**Files:**
- Modify: `parcel-demand-2-matsim-pipeline/src/main/java/hagrid/HagridPaths.java` (add to the Lausitz section near line 283–320)
- Modify: `parcel-demand-2-matsim-pipeline/src/main/java/hagrid/simulation/HAGRIDSimulationConfig.java` (add accessors near line 398–455; add validation near line 478–484)
- Modify: `docs/DATA-LAUSITZ.md`
- Test: `parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/drt/RailPathsTest.java` (new)

**Interfaces:**
- Produces (HagridPaths): `lausitzTransitScheduleRaw()`, `lausitzTransitVehiclesRaw()`, `lausitzVehicleTypes()` (staged inputs); `railScheduleFiltered()`, `railTransitVehiclesFiltered()` (run-scoped outputs).
- Produces (HAGRIDSimulationConfig): `getLausitzTransitScheduleRaw()`, `getLausitzTransitVehiclesRaw()`, `getLausitzVehicleTypes()`, `getRailScheduleFiltered()`, `getRailTransitVehiclesFiltered()`.

- [ ] **Step 1: Write the failing test**

```java
package hagrid.integrated.drt;

import hagrid.HagridPaths;
import hagrid.utils.general.StudyArea;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("HagridPaths rail getters")
class RailPathsTest {

    @Test
    @DisplayName("rail staged-input + run-scoped output getters resolve under the Lausitz input/run roots")
    void railGettersResolve() {
        HagridPaths paths = new HagridPaths(StudyArea.LAUSITZ_HOYERSWERDA);
        paths.initializeRun("RAILTEST");

        assertThat(paths.lausitzTransitScheduleRaw())
                .endsWith("transit/lausitz-transitSchedule.xml.gz");
        assertThat(paths.lausitzTransitVehiclesRaw())
                .endsWith("transit/lausitz-transitVehicles.xml.gz");
        assertThat(paths.lausitzVehicleTypes())
                .endsWith("vehicles/lausitz-vehicle-types.xml");
        assertThat(paths.railScheduleFiltered()).contains("RAILTEST").endsWith("rail-transitSchedule.xml.gz");
        assertThat(paths.railTransitVehiclesFiltered()).contains("RAILTEST").endsWith("rail-transitVehicles.xml.gz");
    }
}
```

> NOTE: confirm the `HagridPaths` constructor + `initializeRun` signatures against the existing Lausitz tests (e.g. how `drtServiceAreaShapefile()` getters are exercised). If `HagridPaths` is constructed differently in this codebase, mirror that construction here — the assertions on the returned strings are the contract.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl parcel-demand-2-matsim-pipeline test -Dtest=RailPathsTest`
Expected: FAIL — getters not defined.

- [ ] **Step 3: Add the getters in `HagridPaths.java`**

Insert after `lausitzBaseConfig()` (line 303) in the staged-inputs block:

```java
    /** Staged native transit schedule (full: rail+bus+tram) before rail-filtering. */
    public String lausitzTransitScheduleRaw() {
        return inputBase.resolve("transit").resolve("lausitz-transitSchedule.xml.gz").toString();
    }

    /** Staged native transit vehicles (full) before rail-filtering. */
    public String lausitzTransitVehiclesRaw() {
        return inputBase.resolve("transit").resolve("lausitz-transitVehicles.xml.gz").toString();
    }

    /** Staged native passenger vehicle-types (car etc.) — enables modeVehicleTypesFromVehiclesData. */
    public String lausitzVehicleTypes() {
        return inputBase.resolve("vehicles").resolve("lausitz-vehicle-types.xml").toString();
    }
```

Insert after `drtFleetFile()` (line 320) in the run-scoped outputs block:

```java
    /** Rail-only transit schedule for this run (bus + tram filtered out). */
    public String railScheduleFiltered() {
        return runDir().resolve(p() + "rail-transitSchedule.xml.gz").toString();
    }

    /** Transit vehicles referenced by the rail-only schedule. */
    public String railTransitVehiclesFiltered() {
        return runDir().resolve(p() + "rail-transitVehicles.xml.gz").toString();
    }
```

- [ ] **Step 4: Add accessors in `HAGRIDSimulationConfig.java`**

Insert after `getDrtFleetFile()` (line 455):

```java
    /** Staged native transit schedule (full) before rail-filtering. */
    public String getLausitzTransitScheduleRaw() {
        return paths.lausitzTransitScheduleRaw();
    }

    /** Staged native transit vehicles (full) before rail-filtering. */
    public String getLausitzTransitVehiclesRaw() {
        return paths.lausitzTransitVehiclesRaw();
    }

    /** Staged native passenger vehicle-types (enables modeVehicleTypesFromVehiclesData). */
    public String getLausitzVehicleTypes() {
        return paths.lausitzVehicleTypes();
    }

    /** Rail-only transit schedule for this run. */
    public String getRailScheduleFiltered() {
        return paths.railScheduleFiltered();
    }

    /** Transit vehicles referenced by the rail-only schedule. */
    public String getRailTransitVehiclesFiltered() {
        return paths.railTransitVehiclesFiltered();
    }
```

Then extend `validateInputFiles()` (the `if (isDrtScenario())` block, after line 483) with the staged raw transit inputs (these are consumed by the preprocessor, so they must exist before a run):

```java
            checkFile(Path.of(getLausitzTransitScheduleRaw()), "Lausitz transit schedule (raw)", missing);
            checkFile(Path.of(getLausitzTransitVehiclesRaw()), "Lausitz transit vehicles (raw)", missing);
            checkFile(Path.of(getLausitzVehicleTypes()), "Lausitz vehicle-types", missing);
```

- [ ] **Step 5: Update `docs/DATA-LAUSITZ.md`**

Add three rows to the Staged Files table:

```markdown
| `transit/lausitz-transitSchedule.xml.gz` | `~/Documents/GitHub/matsim-lausitz/input/v2024.2/lausitz-v2024.2-transitSchedule.xml.gz` |
| `transit/lausitz-transitVehicles.xml.gz` | `~/Documents/GitHub/matsim-lausitz/input/v2024.2/lausitz-v2024.2-transitVehicles.xml.gz` |
| `vehicles/lausitz-vehicle-types.xml` | `~/Documents/GitHub/matsim-lausitz/input/v2024.2/lausitz-v2024.2-vehicle-types.xml` |
```

And the matching copy commands (Git Bash), extending the existing block:

```bash
mkdir -p "$HI"/{transit,vehicles}
cp ~/Documents/GitHub/matsim-lausitz/input/v2024.2/lausitz-v2024.2-transitSchedule.xml.gz "$HI/transit/lausitz-transitSchedule.xml.gz"
cp ~/Documents/GitHub/matsim-lausitz/input/v2024.2/lausitz-v2024.2-transitVehicles.xml.gz "$HI/transit/lausitz-transitVehicles.xml.gz"
cp ~/Documents/GitHub/matsim-lausitz/input/v2024.2/lausitz-v2024.2-vehicle-types.xml "$HI/vehicles/lausitz-vehicle-types.xml"
```

> NOTE: confirm the exact staged source filenames/paths in the matsim-lausitz clone (`input/v2024.2/`); the transitSchedule was already verified present (`lausitz-v2024.2-transitSchedule.xml.gz`, ~1.4 MB).

- [ ] **Step 6: Run test to verify it passes**

Run: `mvn -pl parcel-demand-2-matsim-pipeline test -Dtest=RailPathsTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/src/main/java/hagrid/HagridPaths.java \
        parcel-demand-2-matsim-pipeline/src/main/java/hagrid/simulation/HAGRIDSimulationConfig.java \
        parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/drt/RailPathsTest.java \
        docs/DATA-LAUSITZ.md
git commit -m "feat(drt): rail transit path getters + DATA-LAUSITZ staging rows"
```

---

## Task 3: `LausitzDrtConfigurator` — re-enable rail PT, SwissRailRaptor, DRT intermodal access/egress, native vehicle-types

This is the central change. It is additive: when `railScheduleFile == null` the method behaves exactly as today (DRT-only, teleported-pt). When non-null it enables rail.

**Files:**
- Modify: `parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/drt/LausitzDrtConfigurator.java`
- Create: `parcel-demand-2-matsim-pipeline/src/test/resources/lausitz-rail-native-like.config.xml`
- Modify: `parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/drt/LausitzDrtConfiguratorTest.java`

**Interfaces:**
- Produces: new overload
  ```java
  static Config build(String baseConfigPath, String drtNetworkFile, String plansFile,
                      String serviceAreaShp, String fleetFile,
                      String railScheduleFile, String railTransitVehiclesFile, String vehicleTypesFile,
                      String outputDir, String runId, int lastIteration)
  ```
  The existing 8-arg `build(...)` is kept as a thin delegate passing `null, null, null` for the three new params (preserves all current callers/tests until they are migrated in later tasks).
- Consumes: `RailScheduleFilter.RAIL_MODE` (none directly — rail file is produced by the preprocessor in Task 5).

- [ ] **Step 1: Create the rail fixture config**

Copy `src/test/resources/lausitz-native-like.config.xml` verbatim to `src/test/resources/lausitz-rail-native-like.config.xml`. It already contains a `transit` module (`useTransit=true` + remote-looking refs), `vehicles`, `subtourModeChoice` with `pt`, and `scoring` with `pt` modeParams — exactly the structure the rail path needs to rewrite.

- [ ] **Step 2: Write the failing test (rail-on branch)**

Add to `LausitzDrtConfiguratorTest.java`:

```java
    @Test
    @DisplayName("build() with rail params enables transit PT, SwissRailRaptor and DRT intermodal access/egress")
    void buildEnablesRailPt(@TempDir Path tmp) {
        URL url = LausitzDrtConfiguratorTest.class.getClassLoader()
                .getResource("lausitz-rail-native-like.config.xml");
        assertThat(url).as("rail fixture config must be on the classpath").isNotNull();

        String railSchedule = tmp.resolve("rail-transitSchedule.xml.gz").toString();
        String railVehicles = tmp.resolve("rail-transitVehicles.xml.gz").toString();
        String vehicleTypes = tmp.resolve("lausitz-vehicle-types.xml").toString();

        Config cfg = LausitzDrtConfigurator.build(
                url.toString(),
                tmp.resolve("drt_network.xml.gz").toString(),
                tmp.resolve("plans.xml.gz").toString(),
                tmp.resolve("service-area.shp").toString(),
                tmp.resolve("fleet.xml.gz").toString(),
                railSchedule, railVehicles, vehicleTypes,
                tmp.resolve("matsim").toString(), "DRT_RAIL_TEST", 0);

        // transit on, pointing at the rail-filtered artifacts
        assertThat(cfg.transit().isUseTransit()).isTrue();
        assertThat(cfg.transit().getTransitScheduleFile()).isEqualTo(railSchedule);
        assertThat(cfg.transit().getVehiclesFile()).isEqualTo(railVehicles);

        // pt restored as a choice mode (alongside drt); no teleported pt router
        assertThat(List.of(cfg.subtourModeChoice().getModes())).contains("pt", "drt");
        assertThat(cfg.routing().getTeleportedModeParams()).doesNotContainKey("pt");

        // SwissRailRaptor intermodal access/egress configured with a drt access mode
        var srr = org.matsim.core.config.ConfigUtils.addOrGetModule(
                cfg, ch.sbb.matsim.config.SwissRailRaptorConfigGroup.class);
        assertThat(srr.isUseIntermodalAccessEgress()).isTrue();
        assertThat(srr.getIntermodalAccessEgressParameterSets().stream()
                .anyMatch(p -> "drt".equals(p.getMode())))
                .as("drt must be an intermodal access/egress mode").isTrue();

        // native vehicle-types restored
        var vehiclesCfg = org.matsim.core.config.ConfigUtils.addOrGetModule(
                cfg, org.matsim.core.config.groups.VehiclesConfigGroup.class);
        assertThat(vehiclesCfg.getVehiclesFile()).isEqualTo(vehicleTypes);
        assertThat(cfg.qsim().getVehiclesSource())
                .isEqualTo(org.matsim.core.config.groups.QSimConfigGroup.VehiclesSource.modeVehicleTypesFromVehiclesData);
    }
```

Also **replace** the legacy `buildProducesRunnableDrtConfig` "Fix 2: teleported pt router registered" assertion (lines 68–78) with a comment that teleported-pt only applies to the DRT-only (null rail params) path, and keep that assertion under the existing 8-arg call (which now delegates with null rail params) — so the DRT-only boot path remains locked.

- [ ] **Step 3: Run test to verify it fails**

Run: `mvn -pl parcel-demand-2-matsim-pipeline test -Dtest=LausitzDrtConfiguratorTest`
Expected: FAIL — new 11-arg overload does not exist.

- [ ] **Step 4: Implement the additive rail branch**

In `LausitzDrtConfigurator.java`:

1. Keep the existing 8-arg `build(...)` but make it delegate:

```java
    public static Config build(String baseConfigPath, String drtNetworkFile, String plansFile,
                               String serviceAreaShp, String fleetFile,
                               String outputDir, String runId, int lastIteration) {
        return build(baseConfigPath, drtNetworkFile, plansFile, serviceAreaShp, fleetFile,
                /*railScheduleFile*/ null, /*railTransitVehiclesFile*/ null, /*vehicleTypesFile*/ null,
                outputDir, runId, lastIteration);
    }
```

2. Add the 11-arg overload. Reuse the existing body up to step 4 (strip PT) but branch on `railScheduleFile`:

```java
    public static Config build(String baseConfigPath, String drtNetworkFile, String plansFile,
                               String serviceAreaShp, String fleetFile,
                               String railScheduleFile, String railTransitVehiclesFile, String vehicleTypesFile,
                               String outputDir, String runId, int lastIteration) {

        Config config = ConfigUtils.loadConfig(baseConfigPath);

        drtNetworkFile = absolutise(drtNetworkFile);
        plansFile = absolutise(plansFile);
        serviceAreaShp = absolutise(serviceAreaShp);
        fleetFile = absolutise(fleetFile);
        railScheduleFile = absolutise(railScheduleFile);
        railTransitVehiclesFile = absolutise(railTransitVehiclesFile);
        vehicleTypesFile = absolutise(vehicleTypesFile);

        config.network().setInputFile(drtNetworkFile);
        config.plans().setInputFile(plansFile);

        config.controller().setOutputDirectory(outputDir);
        config.controller().setRunId(runId);
        config.controller().setFirstIteration(0);
        config.controller().setLastIteration(lastIteration);
        config.controller().setOverwriteFileSetting(
                OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);

        boolean railEnabled = railScheduleFile != null && !railScheduleFile.isBlank();

        // ---- transit: rail on, or fully stripped (DRT-only) ----
        if (railEnabled) {
            config.transit().setUseTransit(true);
            config.transit().setTransitScheduleFile(railScheduleFile);
            config.transit().setVehiclesFile(railTransitVehiclesFile);
        } else {
            config.transit().setUseTransit(false);
            config.transit().setTransitScheduleFile(null);
            config.transit().setVehiclesFile(null);
        }
        config.counts().setInputFile(null);
        config.getModules().remove("simwrapper");

        // ---- passenger vehicle source ----
        org.matsim.core.config.groups.VehiclesConfigGroup vehicles =
                ConfigUtils.addOrGetModule(config, org.matsim.core.config.groups.VehiclesConfigGroup.class);
        if (vehicleTypesFile != null && !vehicleTypesFile.isBlank()) {
            // native vehicle-types: best comparability (decision locked)
            vehicles.setVehiclesFile(vehicleTypesFile);
            config.qsim().setVehiclesSource(
                    org.matsim.core.config.groups.QSimConfigGroup.VehiclesSource.modeVehicleTypesFromVehiclesData);
        } else {
            // portable fallback (tests): synthesise a default car type from config
            vehicles.setVehiclesFile(null);
            if (config.qsim().getVehiclesSource()
                    == org.matsim.core.config.groups.QSimConfigGroup.VehiclesSource.modeVehicleTypesFromVehiclesData
                    || config.qsim().getVehiclesSource()
                    == org.matsim.core.config.groups.QSimConfigGroup.VehiclesSource.fromVehiclesData) {
                config.qsim().setVehiclesSource(
                        org.matsim.core.config.groups.QSimConfigGroup.VehiclesSource.defaultVehicle);
            }
        }

        // ---- choice modes: keep pt when rail is on; strip pt when DRT-only ----
        String[] modes = config.subtourModeChoice().getModes();
        List<String> choiceModes = new ArrayList<>();
        for (String m : modes) {
            if (railEnabled || !TransportMode.pt.equals(m)) {
                choiceModes.add(m);
            }
        }
        config.subtourModeChoice().setModes(choiceModes.toArray(new String[0]));

        // ---- legacy pt legs: real routing via rail (no teleport) OR teleport hack (DRT-only) ----
        if (!railEnabled) {
            if (!config.routing().getTeleportedModeParams().containsKey(TransportMode.pt)) {
                org.matsim.core.config.groups.RoutingConfigGroup.TeleportedModeParams ptTeleport =
                        new org.matsim.core.config.groups.RoutingConfigGroup.TeleportedModeParams(TransportMode.pt);
                ptTeleport.setTeleportedModeSpeed(50.0 / 3.6);
                ptTeleport.setBeelineDistanceFactor(1.3);
                config.routing().addTeleportedModeParams(ptTeleport);
            }
        }

        // ---- drop longDistanceFreight network mode (no freight agents this milestone) ----
        config.qsim().setMainModes(withoutFreight(config.qsim().getMainModes()));
        config.routing().setNetworkModes(withoutFreight(config.routing().getNetworkModes()));
        config.travelTimeCalculator().setAnalyzedModes(
                Set.copyOf(withoutFreight(config.travelTimeCalculator().getAnalyzedModes())));

        // ---- activity + ride scoring ----
        SnzActivities.addScoringParams(config);
        RideScoringParamsFromCarParams.setRideScoringParamsBasedOnCarParams(
                config.scoring(), RIDE_SCORING_FACTOR);

        // ---- compose full-DVRP DRT ----
        DrtConfigComposer.composeConfig(config, serviceAreaShp, fleetFile);

        // ---- intermodality: SwissRailRaptor walk + drt access/egress to rail stops ----
        if (railEnabled) {
            configureRailIntermodality(config);
        }

        return config;
    }
```

3. Add the intermodality helper (mirrors `DrtAndIntermodalityOptions.configureDrtConfig` lines 140–158 + native walk params, values verbatim):

```java
    /** DRT access/egress radius for intermodal rail feeding (native value, metres). */
    private static final double DRT_INTERMODAL_RADIUS_M = 50_000.0;
    private static final double DRT_INTERMODAL_SEARCH_EXT_M = 1_000.0;
    /** Stop attribute set by {@code PrepareTransitSchedule.tagIntermodalStops}. */
    static final String DRT_ACCESS_ATTR = "allowDrtAccessEgress";

    private static void configureRailIntermodality(Config config) {
        ch.sbb.matsim.config.SwissRailRaptorConfigGroup srr =
                ConfigUtils.addOrGetModule(config, ch.sbb.matsim.config.SwissRailRaptorConfigGroup.class);

        // Native walk intermodality (reuse the matsim-lausitz helper, sets useIntermodalAccessEgress=true
        // + CalcLeastCostModePerStop + a walk access/egress param). Idempotent if already set.
        if (!srr.isUseIntermodalAccessEgress()) {
            org.matsim.run.scenarios.LausitzScenario.setExplicitIntermodalityParamsForWalkToPt(srr);
        }

        // Add drt as an intermodal access/egress mode, gated to tagged stops.
        boolean hasDrt = srr.getIntermodalAccessEgressParameterSets().stream()
                .anyMatch(p -> TransportMode.drt.equals(p.getMode()));
        if (!hasDrt) {
            ch.sbb.matsim.config.SwissRailRaptorConfigGroup.IntermodalAccessEgressParameterSet drtParam =
                    new ch.sbb.matsim.config.SwissRailRaptorConfigGroup.IntermodalAccessEgressParameterSet();
            drtParam.setMode(TransportMode.drt);
            drtParam.setInitialSearchRadius(DRT_INTERMODAL_RADIUS_M);
            drtParam.setMaxRadius(DRT_INTERMODAL_RADIUS_M);
            drtParam.setSearchExtensionRadius(DRT_INTERMODAL_SEARCH_EXT_M);
            drtParam.setStopFilterAttribute(DRT_ACCESS_ATTR);
            drtParam.setStopFilterValue("true");
            srr.addIntermodalAccessEgress(drtParam);
        }

        // Native couples DRT maxWalkDistance to the transit search radius (drt is a pt feeder).
        double searchRadius = ConfigUtils.addOrGetModule(
                config, org.matsim.pt.config.TransitRouterConfigGroup.class).getSearchRadius();
        for (org.matsim.contrib.drt.run.DrtConfigGroup drt :
                org.matsim.contrib.drt.run.MultiModeDrtConfigGroup.get(config).getModalElements()) {
            var set = (org.matsim.contrib.drt.optimizer.constraints.DefaultDrtOptimizationConstraintsSet)
                    drt.addOrGetDrtOptimizationConstraintsParams().addOrGetDefaultDrtOptimizationConstraintsSet();
            set.maxWalkDistance = searchRadius;
        }
    }
```

> NOTE (VSP-defaults / `abort`): enabling transit may surface new `vspDefaultsCheckingLevel=abort` violations (e.g. `intermodalAccessEgressModeSelection`, transit-router params). Per the locked decision, fix each properly — do NOT downgrade to `warn`. Enumerate them at the first run in Task 6; expected candidates are already covered by `setExplicitIntermodalityParamsForWalkToPt`.

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn -pl parcel-demand-2-matsim-pipeline test -Dtest=LausitzDrtConfiguratorTest`
Expected: PASS (both the DRT-only locks via the 8-arg delegate AND the new rail-on test).

- [ ] **Step 6: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/drt/LausitzDrtConfigurator.java \
        parcel-demand-2-matsim-pipeline/src/test/resources/lausitz-rail-native-like.config.xml \
        parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/drt/LausitzDrtConfiguratorTest.java
git commit -m "feat(drt): re-enable rail PT + SwissRailRaptor DRT intermodal access/egress in configurator"
```

---

## Task 4: `DrtScenarioBuilder` — thread rail params + tag intermodal rail stops

**Files:**
- Modify: `parcel-demand-2-matsim-pipeline/src/main/java/hagrid/simulation/DrtScenarioBuilder.java`
- Test: `parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/drt/DrtScenarioBuilderRailTest.java` (new)

**Interfaces:**
- Consumes: `LausitzDrtConfigurator.build(...)` (11-arg); `org.matsim.run.prepare.PrepareTransitSchedule.tagIntermodalStops`; `org.matsim.application.options.ShpOptions`.
- Produces: new path-based overload threading `railScheduleFile, railTransitVehiclesFile, vehicleTypesFile`; the `HAGRIDSimulationConfig` overload supplies them from the Task-2 getters. Stops within the service-area shp get attribute `allowDrtAccessEgress="true"` after `loadScenario`.

- [ ] **Step 1: Write the failing test**

```java
package hagrid.integrated.drt;

import hagrid.simulation.DrtScenarioBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.matsim.api.core.v01.Scenario;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.testcases.MatsimTestUtils;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DrtScenarioBuilder rail intermodal tagging")
class DrtScenarioBuilderRailTest {

    @RegisterExtension
    public MatsimTestUtils utils = new MatsimTestUtils();

    @Test
    @DisplayName("build() tags rail stops inside the service area as intermodal DRT access/egress")
    void tagsIntermodalStops() throws Exception {
        Path dir = Path.of(utils.getOutputDirectory()).toAbsolutePath();
        // Reuse the e2e helpers (Task 6) for the raw network/plans/shp + rail schedule + preprocessing.
        RailScenarioFixture fx = RailScenarioFixture.stage(dir);

        Scenario scenario = DrtScenarioBuilder.build(
                fx.baseConfigUrl,
                fx.drtNetwork, fx.clippedPlans, fx.serviceAreaShp, fx.fleet,
                fx.railSchedule, fx.railVehicles, /*vehicleTypes*/ null,
                dir.resolve("matsim").toString(), "RAIL_TAG_TEST", 0);

        // at least one rail stop inside the service area got tagged
        long tagged = scenario.getTransitSchedule().getFacilities().values().stream()
                .filter(s -> "true".equals(s.getAttributes().getAttribute(
                        LausitzDrtConfigurator.DRT_ACCESS_ATTR)))
                .count();
        assertThat(tagged).as("rail stops inside the service area must be tagged for DRT access/egress")
                .isGreaterThan(0);
    }
}
```

> NOTE: `RailScenarioFixture` is a small shared test helper introduced in Task 6 (a static `stage(dir)` that builds the raw network/plans/service-area shp + a tiny rail schedule, runs the preprocessor, and returns the produced paths + the rail fixture config URL). If implementing Task 4 before Task 6, inline the fixture here and extract it in Task 6.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl parcel-demand-2-matsim-pipeline test -Dtest=DrtScenarioBuilderRailTest`
Expected: FAIL — new overload + tagging not present.

- [ ] **Step 3: Implement the threaded overload + tagging**

Replace the path-based `build(...)` body with the 11-arg version and add tagging after `loadScenario`; keep the 8-arg overload delegating with nulls and the `HAGRIDSimulationConfig` overload supplying the getters:

```java
    public static Scenario build(HAGRIDSimulationConfig cfg) {
        return build(
                cfg.getLausitzBaseConfig(),
                cfg.getDrtNetworkClipped(),
                cfg.getPassengerPlansClipped(),
                cfg.getDrtServiceAreaShapefile(),
                cfg.getDrtFleetFile(),
                cfg.getRailScheduleFiltered(),
                cfg.getRailTransitVehiclesFiltered(),
                cfg.getLausitzVehicleTypes(),
                cfg.getOutputDirectoryAsString(),
                cfg.getRunId(),
                cfg.getMaxIterations());
    }

    /** Legacy DRT-only overload (no rail) — preserved for existing callers/tests. */
    public static Scenario build(String baseConfigPath, String drtNetworkFile, String plansFile,
                                 String serviceAreaShp, String fleetFile,
                                 String outputDir, String runId, int lastIteration) {
        return build(baseConfigPath, drtNetworkFile, plansFile, serviceAreaShp, fleetFile,
                null, null, null, outputDir, runId, lastIteration);
    }

    public static Scenario build(String baseConfigPath, String drtNetworkFile, String plansFile,
                                 String serviceAreaShp, String fleetFile,
                                 String railScheduleFile, String railTransitVehiclesFile, String vehicleTypesFile,
                                 String outputDir, String runId, int lastIteration) {

        Config config = LausitzDrtConfigurator.build(
                baseConfigPath, drtNetworkFile, plansFile, serviceAreaShp, fleetFile,
                railScheduleFile, railTransitVehiclesFile, vehicleTypesFile,
                outputDir, runId, lastIteration);

        Scenario scenario = ScenarioUtils.createScenario(config);
        scenario.getPopulation().getFactory().getRouteFactories()
                .setRouteFactory(DrtRoute.class, new DrtRouteFactory());

        ScenarioUtils.loadScenario(scenario);

        // Tag rail stops reachable by DRT as intermodal access/egress. The intermodal-area IS the
        // DRT service-area shapefile (single source of truth — see plan decision 1), so every rail
        // stop inside the DRT zone (incl. Hoyerswerda + Ruhland Bahnhof) becomes DRT-feedable, and
        // expanding the service area later auto-expands intermodal coverage.
        if (railScheduleFile != null && !railScheduleFile.isBlank()) {
            String shp = java.nio.file.Paths.get(serviceAreaShp).toAbsolutePath().normalize().toString();
            PrepareTransitSchedule.tagIntermodalStops(
                    scenario.getTransitSchedule(),
                    new org.matsim.application.options.ShpOptions(shp, null, null));
        }

        return scenario;
    }
```

Add imports: `org.matsim.core.config.Config` (already present), `org.matsim.run.prepare.PrepareTransitSchedule`.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl parcel-demand-2-matsim-pipeline test -Dtest=DrtScenarioBuilderRailTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/src/main/java/hagrid/simulation/DrtScenarioBuilder.java \
        parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/drt/DrtScenarioBuilderRailTest.java
git commit -m "feat(drt): tag intermodal rail stops via service-area shp in DrtScenarioBuilder"
```

---

## Task 5: `LausitzDrtPreprocessor` — produce the rail-filtered schedule + transit vehicles

**Files:**
- Modify: `parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/drt/LausitzDrtPreprocessor.java`
- Test: `parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/drt/LausitzDrtPreprocessorTest.java` (extend)

**Interfaces:**
- Consumes: `RailScheduleFilter.run(...)`; the Task-2 getters.
- Produces: the run-scoped `rail-transitSchedule.xml.gz` + `rail-transitVehicles.xml.gz`. The path-based `run(...)` gains 4 params: `rawSchedule, rawTransitVehicles, railScheduleOut, railVehiclesOut` (appended; existing tests that call the old arity will need migration — keep an overload that skips schedule filtering when `rawSchedule == null`).

- [ ] **Step 1: Write the failing test**

Add to `LausitzDrtPreprocessorTest.java` (reuse its existing `writeSquareShapefile` + tiny network/plans helpers):

```java
    @Test
    @DisplayName("run() writes a rail-only filtered schedule + transit vehicles")
    void writesRailFilteredSchedule(@TempDir Path tmp) throws Exception {
        // ... build rawNet, rawPlans, serviceAreaShp as in the existing preprocessor test ...
        // build a tiny schedule with one rail + one bus line and write it + its transit vehicles
        Path rawSchedule = tmp.resolve("schedule.xml.gz");
        Path rawTransitVeh = tmp.resolve("transitVehicles.xml.gz");
        RailScheduleFixtures.writeRailAndBus(rawSchedule, rawTransitVeh);   // helper: 1 rail + 1 bus line

        Path railSchedule = tmp.resolve("rail-schedule.xml.gz");
        Path railVeh = tmp.resolve("rail-transitVehicles.xml.gz");

        LausitzDrtPreprocessor.run(
                rawNet.toString(), rawPlans.toString(), serviceShp.toString(),
                drtNet.toString(), clippedPlans.toString(), fleet.toString(),
                rawSchedule.toString(), rawTransitVeh.toString(),
                railSchedule.toString(), railVeh.toString(),
                /*fleetSize*/ 2, /*capacity*/ 8, 0.0, 86400.0);

        // the filtered schedule loads and contains only rail
        var c = org.matsim.core.config.ConfigUtils.createConfig();
        c.transit().setTransitScheduleFile(railSchedule.toString());
        c.transit().setVehiclesFile(railVeh.toString());
        c.transit().setUseTransit(true);
        var sc = org.matsim.core.scenario.ScenarioUtils.loadScenario(c);
        assertThat(sc.getTransitSchedule().getTransitLines().values().stream()
                .flatMap(l -> l.getRoutes().values().stream())
                .allMatch(r -> "rail".equals(r.getTransportMode()))).isTrue();
    }
```

> NOTE: factor the tiny rail/bus schedule writer into a `RailScheduleFixtures` test helper (or reuse the in-code builder from `RailScheduleFilterTest`) and write it via `TransitScheduleWriter` + `MatsimVehicleWriter`.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl parcel-demand-2-matsim-pipeline test -Dtest=LausitzDrtPreprocessorTest#writesRailFilteredSchedule`
Expected: FAIL — the 11-arg `run(...)` overload does not exist.

- [ ] **Step 3: Implement**

Add the extended path-based overload (the existing 10-arg `run(...)` delegates with `null` schedule paths = no filtering, for the DRT-only e2e test), and the `HAGRIDSimulationConfig` overload supplies the new paths:

```java
    public static void run(String rawNetwork, String rawPlans, String serviceAreaShp,
                           String drtNetworkOut, String plansOut, String fleetOut,
                           String rawSchedule, String rawTransitVehicles,
                           String railScheduleOut, String railVehiclesOut,
                           int fleetSize, int capacity, double serviceBegin, double serviceEnd) {

        // network + population + fleet exactly as before
        run(rawNetwork, rawPlans, serviceAreaShp, drtNetworkOut, plansOut, fleetOut,
                fleetSize, capacity, serviceBegin, serviceEnd);

        // rail-only transit artifacts (skip if no schedule supplied — DRT-only path)
        if (rawSchedule != null && !rawSchedule.isBlank()) {
            RailScheduleFilter.run(rawSchedule, rawTransitVehicles, railScheduleOut, railVehiclesOut);
        }
    }
```

And in the `run(HAGRIDSimulationConfig cfg)` overload, replace the call with the extended one:

```java
        run(
                cfg.getLausitzNetworkRaw(),
                cfg.getPassengerPlansRaw(),
                cfg.getDrtServiceAreaShapefile(),
                cfg.getDrtNetworkClipped(),
                cfg.getPassengerPlansClipped(),
                cfg.getDrtFleetFile(),
                cfg.getLausitzTransitScheduleRaw(),
                cfg.getLausitzTransitVehiclesRaw(),
                cfg.getRailScheduleFiltered(),
                cfg.getRailTransitVehiclesFiltered(),
                cfg.getFleetSize(),
                8,
                0.0,
                86400.0
        );
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -pl parcel-demand-2-matsim-pipeline test -Dtest=LausitzDrtPreprocessorTest`
Expected: PASS (existing tests via the delegating 10-arg overload + the new rail test).

- [ ] **Step 5: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/drt/LausitzDrtPreprocessor.java \
        parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/drt/LausitzDrtPreprocessorTest.java
git commit -m "feat(drt): preprocessor produces rail-filtered schedule + transit vehicles"
```

---

## Task 6: End-to-end — a rail leg routes and a DRT-access-to-rail trip works (the spike, productionized)

This is the capstone that de-risks the integration of SwissRailRaptor intermodality inside HAGRID's hand-rolled (non-`MATSimApplication`) builder. It mirrors `DrtBaselineEndToEndTest` but with transit ON.

**Files:**
- Create: `parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/drt/RailScenarioFixture.java` (shared test helper)
- Create: `parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/drt/DrtRailIntermodalEndToEndTest.java`
- Modify: `parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/drt/DrtBaselineEndToEndTest.java` (the legacy DRT-only e2e stays as-is — it still exercises the null-rail path and the teleported-pt boot fix).

**Interfaces:**
- `RailScenarioFixture.stage(Path dir)` → returns a record/holder with `baseConfigUrl, drtNetwork, clippedPlans, serviceAreaShp, fleet, railSchedule, railVehicles`. Builds the raw 4-node car grid + a rail line whose two stops sit INSIDE the service-area square + agents (one with a `pt` leg home→work), then runs `LausitzDrtPreprocessor.run(...11-arg...)`.

- [ ] **Step 1: Write the failing e2e test**

```java
package hagrid.integrated.drt;

import hagrid.simulation.DrtScenarioBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.core.controler.Controler;
import org.matsim.testcases.MatsimTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DRT + rail-PT intermodal end-to-end run (production path)")
class DrtRailIntermodalEndToEndTest {

    @RegisterExtension
    public MatsimTestUtils utils = new MatsimTestUtils();

    @Test
    @DisplayName("rail leg routes for real + DRT intermodal access/egress configured; one iteration produces drt_* + pt output")
    void runsRailIntermodalOneIteration() throws Exception {
        Path dir = Path.of(utils.getOutputDirectory()).toAbsolutePath();
        Files.createDirectories(dir);
        RailScenarioFixture fx = RailScenarioFixture.stage(dir);

        Scenario scenario = DrtScenarioBuilder.build(
                fx.baseConfigUrl,
                fx.drtNetwork, fx.clippedPlans, fx.serviceAreaShp, fx.fleet,
                fx.railSchedule, fx.railVehicles, /*vehicleTypes*/ null,
                dir.resolve("matsim").toString(), "DRT_RAIL_E2E", 0);

        // transit really loaded
        assertThat(scenario.getTransitSchedule().getTransitLines()).isNotEmpty();
        // intermodal stop tagged
        assertThat(scenario.getTransitSchedule().getFacilities().values().stream()
                .anyMatch(s -> "true".equals(s.getAttributes()
                        .getAttribute(LausitzDrtConfigurator.DRT_ACCESS_ATTR)))).isTrue();

        Controler controler = new Controler(scenario);
        DrtConfigComposer.installModules(controler);
        controler.run();

        Path matsimOut = dir.resolve("matsim");
        try (var stream = Files.walk(matsimOut)) {
            assertThat(stream.filter(Files::isRegularFile)
                    .anyMatch(p -> p.getFileName().toString().toLowerCase().contains("drt")))
                    .as("a real DRT iteration must produce a drt_* output file").isTrue();
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl parcel-demand-2-matsim-pipeline test -Dtest=DrtRailIntermodalEndToEndTest`
Expected: FAIL — `RailScenarioFixture` missing / integration not complete.

- [ ] **Step 3: Implement `RailScenarioFixture`**

Build it by lifting the network/plans/shapefile helpers from `DrtBaselineEndToEndTest` (copy the `buildGrid`, `addLink`, `buildDemand`, and `writeSquareShapefile` + LE writers verbatim — they already exist there) and adding an in-code rail schedule whose two stops are inside the 0..2000 service-area square (e.g. at (200,200) and (1800,1800)), each on a real network link, with one rail departure + one rail transit vehicle. Write schedule + transit vehicles with `TransitScheduleWriter` / `MatsimVehicleWriter`, then call the 11-arg `LausitzDrtPreprocessor.run(...)`. Expose the produced paths + the `lausitz-rail-native-like.config.xml` classpath URL as public fields.

- [ ] **Step 4: Run test; fix VSP-defaults `abort` violations as they surface**

Run: `mvn -pl parcel-demand-2-matsim-pipeline test -Dtest=DrtRailIntermodalEndToEndTest`
Expected first run: may FAIL with a `vspExperimental` abort listing the offending param(s). For each, add the proper config value (do not switch to `warn`). Re-run until PASS. Likely already-covered candidates: intermodal access/egress mode selection (set by `setExplicitIntermodalityParamsForWalkToPt`), transit-router params.

- [ ] **Step 5: Run the full DRT test suite (regression)**

Run: `mvn -pl parcel-demand-2-matsim-pipeline test -Dtest=hagrid.integrated.drt.*`
Expected: all green (DRT-only e2e + config + preprocessor + filter + rail e2e).

- [ ] **Step 6: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/drt/RailScenarioFixture.java \
        parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/drt/DrtRailIntermodalEndToEndTest.java \
        parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/drt/LausitzDrtConfigurator.java
git commit -m "test(drt): end-to-end rail-PT + DRT intermodality run on the production path"
```

---

## Task 7: Real-data run + DATA staging verification + memory/session-log update

The unit/integration suite proves the machinery portably. This task proves it on the real staged Lausitz data, exactly as 1b-prep proved the passenger-only baseline.

**Files:**
- Modify: `parcel-demand-2-matsim-pipeline/run_drt_baseline.bat` (no code change expected — it already drives `PrepareLausitzDrtInputs` + the runner; re-run end-to-end with rail on).

- [ ] **Step 1: Stage the new raw inputs** per the updated `docs/DATA-LAUSITZ.md` (transit schedule, transit vehicles, vehicle-types). Verify the files exist under `hagrid-input/lausitz/{transit,vehicles}/`.

- [ ] **Step 2: Run preprocessing** for `DRT_BASELINE` (`PrepareLausitzDrtInputs`) and confirm the run dir now also contains `*rail-transitSchedule.xml.gz` + `*rail-transitVehicles.xml.gz`, and that the filtered schedule contains rail lines only (spot-check line count vs the inspected rail=1.201 routes).

- [ ] **Step 3: Run one real iteration** of `DRT_BASELINE` via `run_drt_baseline.bat` (set `PYTHONIOENCODING`/console as in 1b-prep). Confirm: boot succeeds, transit is loaded, a `pt` (rail) leg routes for at least one agent, and the `drt_*` + pt output set is produced. (0 drt rides at maxIter=1 remains expected — adoption needs replanning iterations.)

- [ ] **Step 4: Update project memory + session log.** Record: rail-PT enabled across the shared DRT config path (so common to all 3 scenarios by construction); intermodal-area = service-area shp (single source of truth); native vehicle-types restored; tram dropped; teleported-pt boot hack removed; the inbound-rail-commuters follow-up still open.

- [ ] **Step 5: Commit** (docs/run-artifact-gitignore housekeeping only — no staged binaries):

```bash
git add docs/DATA-LAUSITZ.md SESSION_LOG.md
git commit -m "docs(drt): rail-PT staging + real-data run notes"
```

---

## Cross-scenario note (Scope item 3)

The rail + intermodality config lands entirely in the **shared** `LausitzDrtConfigurator` / `DrtScenarioBuilder` path, which every DRT scenario (`DRT_BASELINE`, and the future `DRT_SHAREDUSE` / `DRT_MODULAR` from plans 1c/1d) routes through. Therefore "apply to all three scenarios" is satisfied **by construction** — no per-scenario rail code. When 1c/1d add freight integration, they layer on top of this common rail layer unchanged. No separate task is needed now (YAGNI); 1c/1d will inherit it.

---

## Out of scope (tracked, not built here)
- **Inbound rail commuters** (population clipped by home-in-area ⇒ inbound rail arrivals absent). Explicit user-requested follow-up *after* the rail baseline runs (cordon / retain rail-relevant out-of-area agents).
- **Bus + tram** (filtered out).
- **Freight/parcel LMD** (Project H = 1c onward).
- **Headline multi-iteration calibration.**

---

## Self-Review

**Spec coverage:**
- Scope 1 (stage + rail-filter schedule; re-enable rail PT) → Tasks 1, 2, 3, 5. ✓
- Scope 2 (DRT intermodal access/egress to rail via native machinery + a Ruhland intermodal-area) → Task 3 (`configureRailIntermodality`) + Task 4 (`tagIntermodalStops`), intermodal-area = service-area shp per the locked decision. ✓
- Scope 3 (apply to all three scenarios) → Cross-scenario note (shared config path). ✓
- Scope 4 (undo the conflicting 1b-prep PT-strip pieces for rail) → Task 3: transit on, teleported-pt removed, pt restored as a choice mode, SwissRailRaptor on; counts/simwrapper drops kept. ✓
- Design B `vehiclesSource` open item → resolved (native vehicle-types when staged, default fallback for tests) per decision. ✓
- Design risks: `vspDefaults=abort` kept + fixed at first run (Task 6 Step 4); intermodal-area covers Hoyerswerda+Ruhland (guaranteed by reusing the service-area shp); tram dropped (Task 1). ✓

**Placeholder scan:** No `TODO`/"add appropriate…"; every code step shows complete code. The few `NOTE:` blocks flag *verification points against the live codebase* (constructor signatures, staged filenames), not deferred work — confirm them during execution.

**Type consistency:** `build(...)` 11-arg signature is identical in `LausitzDrtConfigurator` (producer) and `DrtScenarioBuilder` (consumer); `RailScheduleFilter.RAIL_MODE` and `LausitzDrtConfigurator.DRT_ACCESS_ATTR` are referenced by the exact constant names declared; getter names (`getRailScheduleFiltered`, `getRailTransitVehiclesFiltered`, `getLausitzVehicleTypes`, `getLausitzTransitScheduleRaw`, `getLausitzTransitVehiclesRaw`) match between `HagridPaths`, `HAGRIDSimulationConfig`, and their call sites.
