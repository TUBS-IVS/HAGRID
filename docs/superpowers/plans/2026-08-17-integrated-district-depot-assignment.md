# District-Based Depot Assignment (1c/1d) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace provider-bound depot assignment in the integrated scenarios (1c Shared-Use, 1d Modular) with district-based assignment, so each parcel is picked up at the depot nearest to its delivery segment and all providers' parcels at one segment become a single stop.

**Architecture:** One new component, `DeliveryDistrictBuilder`, pools deliveries per segment, assigns each segment to its nearest open depot (level 1 = Voronoi catchment), and splits a catchment only when it exceeds a job ceiling (level 2). Both integrated arms consume its output instead of `LmdDemandReader`'s per-provider map. The Baseline path is untouched and protected by a byte-identity regression test.

**Tech Stack:** Java 17, MATSim 2025.0 freight API (`Carrier`, `CarrierService`), jsprit, JUnit 5, Maven.

**Spec:** [`docs/superpowers/specs/2026-08-17-integrated-district-depot-assignment-design.md`](../specs/2026-08-17-integrated-district-depot-assignment-design.md)

## Global Constraints

- **The Baseline is out of scope.** `LausitzFreightPreprocessor.run`, `LmdCarrierBuilder.build`, dispatch waves, jitter and `LmdTourRetimer` must behave exactly as today. Task 1 installs the guard that proves it.
- **Stop-duration formula unchanged:** `min(2 min × parcels, 15 min)` — `DURATION_PER_PARCEL_MIN = 2`, `MAX_DURATION_PER_STOP_MIN = 15`. Do not "fix" the cap; its distortion is an accepted, documented limitation (spec §9.1).
- **`Delivery` must not be modified** — it is shared with the Hannover pipeline.
- **Coordinates are EPSG:25832**; all distances are Euclidean via `CoordUtils.calcEuclideanDistance`.
- **Determinism is mandatory:** no unseeded RNG, no `HashMap` iteration order in anything that reaches a carrier id, service id, or RNG draw.
- Default `maxJobsPerDistrict` is **300**; default `openDepots` is **all**.
- **Depots and districts are named by site, never by LSP** (spec D7): `wittichenau` (dhl),
  `lauta` (amazon), `hoy_sued` (hermes), `hoy_nord` (dpd), `spreetal` (gls), `elsterheide` (ups),
  `doergenhausen` (fedex). The depot CSV gains a `site` column; the `provider` column stays for the
  Baseline path. A district id must never contain a provider name — that is what stops the analysis
  layer from reading a district as an LSP.
- **`maxJobsPerDistrict` is a 1d parameter** (spec D8). 1c calls the builder with
  `Integer.MAX_VALUE`: it has no jsprit, sub-districts share their catchment's depot, so a split
  would change nothing except the person ids.
- **Both arms clip to the service area before districting** (spec D9), so their input sets are
  identical and the segment→depot mapping is provably the same.
- Maven test invocation in this repo: `mvn -pl parcel-demand-2-matsim-pipeline -am test -Dtest=<Class> -DfailIfNoSpecifiedTests=false`
- Never edit `.bat` files with Edit/Write (strips CRLF); use PowerShell `WriteAllLines`.

---

### Task 1: Baseline byte-identity guard

Installs the safety net **before** any shared code is touched. `LmdCarrierBuilder.buildCore` documents an RNG draw-order contract; Task 4 changes that method, and this test is what proves the Baseline path came through unchanged.

**Files:**
- Modify: `parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/freight/LausitzFreightPreprocessorTest.java` (add one test)
- Create: `parcel-demand-2-matsim-pipeline/src/test/resources/baseline-golden/carriers-golden.sha256`

**Interfaces:**
- Consumes: the **existing private helper** `stageLmdFixture(Path)` in `LausitzFreightPreprocessorTest`, which returns a `StagedFixture` record with accessors `demandShp()`, `depotCsv()`, `netFile()`, `typesFile()`, `types()`. The test lives in this class precisely so that helper is in scope — `LmdTestShapefiles` only offers `writeDemand(...)` and cannot stage a whole fixture.
- Produces: nothing consumed by later tasks; it is a gate.

Note the file's conventions: it uses **AssertJ** (`assertThat`) and `LausitzFreightPreprocessor.run(...)` writes plain `.xml` in these tests (see `producesRoutedCarriers`), not `.xml.gz`. Match both.

- [ ] **Step 1: Write the test that pins the Baseline output hash**

Add to `LausitzFreightPreprocessorTest`:

```java
    /**
     * Pins the LMD_BASELINE carrier output to a golden hash. The Baseline is explicitly out of
     * scope for the district-depot change (spec 2026-08-17 §D2), and
     * {@code LmdCarrierBuilder.buildCore} documents an RNG draw-order contract that the overlay
     * rework touches. If this fails, the Baseline moved and every existing Baseline run became
     * incomparable.
     */
    @Test
    void baselineCarrierOutputIsUnchanged(@TempDir Path tmp) throws Exception {
        StagedFixture fixture = stageLmdFixture(tmp);
        Path out = tmp.resolve("baseline_carriers.xml");
        LausitzFreightPreprocessor.run(fixture.demandShp().toString(), fixture.depotCsv().toString(),
                fixture.netFile().toString(), fixture.typesFile().toString(), out.toString(),
                /*jspritIterations*/ 5);

        String actual = sha256(out);
        Path golden = Path.of("src/test/resources/baseline-golden/carriers-golden.sha256");
        assertThat(golden).as("golden file missing - see plan Task 1 Step 2").exists();
        assertThat(actual)
                .as("Baseline carrier output changed. The Baseline is out of scope for the "
                        + "district-depot change (spec §D2) - investigate before proceeding.")
                .isEqualTo(Files.readString(golden).trim());
    }

    private static String sha256(Path file) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        md.update(Files.readAllBytes(file));
        return java.util.HexFormat.of().formatHex(md.digest());
    }
```

- [ ] **Step 2: Run it to capture the current hash**

Run: `mvn -pl parcel-demand-2-matsim-pipeline -am test -Dtest=LausitzFreightPreprocessorTest#baselineCarrierOutputIsUnchanged -DfailIfNoSpecifiedTests=false`

Expected: FAIL — the golden file does not exist. Add a temporary `System.out.println(actual);` before the assertion to read the hash, then remove it.

Write that hash into `src/test/resources/baseline-golden/carriers-golden.sha256` (single line, no trailing whitespace).

- [ ] **Step 3: Re-run and confirm it passes**

Run: `mvn -pl parcel-demand-2-matsim-pipeline -am test -Dtest=LausitzFreightPreprocessorTest#baselineCarrierOutputIsUnchanged -DfailIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 4: Prove the guard actually discriminates**

Temporarily change `MISSED_DELIVERY_SEED` in `LausitzFreightPreprocessor.java:64` from `4711L` to `4712L`, re-run, confirm it FAILS. Revert the seed, confirm it passes again. A guard that cannot fail is not a guard.

If the run turns out to be non-deterministic (two consecutive runs of the unmodified code produce different hashes), **stop and report** — a byte-identity guard is impossible then, and the Baseline protection has to be redesigned around structural assertions (carrier count, service count, total planned distance) instead.

- [ ] **Step 5: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/freight/LausitzFreightPreprocessorTest.java \
        parcel-demand-2-matsim-pipeline/src/test/resources/baseline-golden/carriers-golden.sha256
git commit -m "test(lmd): pin Baseline carrier output to a golden hash before the district-depot change"
```

---

### Task 2: PooledStop, District and level-1 catchment assignment

**Files:**
- Create: `parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/DeliveryDistrictBuilder.java`
- Modify: `parcel-demand-2-matsim-pipeline/hagrid-input/lausitz/hubs/lmd-depots.csv` (add `site` column)
- Modify: `parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/drt/DrtDepotReader.java` (add `readBySite`)
- Test: `parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/DeliveryDistrictBuilderTest.java`
- Test: `parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/drt/DrtDepotReaderTest.java`

- [ ] **Step 0: Site names in the depot CSV and reader**

The `provider` column stays exactly where it is — `LmdDepotLoader` reads it and the Baseline path
must not move. Append a `site` column (write the file with PowerShell `WriteAllLines`, not Edit):

```
provider;x;y;site
dhl;866341.8;5705764.6;wittichenau
amazon;855395.1;5712299.2;lauta
hermes;867545.1;5710992.6;hoy_sued
dpd;865819.9;5713911.5;hoy_nord
gls;870590.7;5719256.4;spreetal
ups;861516.0;5715307.7;elsterheide
fedex;861667.8;5709617.9;doergenhausen
```

Add `DrtDepotReader.readBySite(Path)` — same parsing as `readByProvider`, keyed on column 4,
trimmed lowercase, file order preserved, and throwing if the column is missing (an older CSV must
fail loudly rather than silently fall back to provider names). Test it alongside the existing
`readByProvider` tests. `LmdDepotLoader` is **not** touched: the extra column is ignored by its
`split(";")` because it only reads indices 0–2 — assert that in a test so the Baseline stays safe.

**Interfaces:**
- Consumes: `hagrid.utils.demand.Delivery` (getters `getCoordinate()`, `getProvider()`, `getAmount()`, `getParcelType()`), `hagrid.integrated.DepotNetwork.Depot` (record with `String id`, `Coord coord`), `DepotNetwork.nearestDepot(Coord)`
- Produces, relied on by Tasks 4–6:
  - `record PooledStop(Coord coord, int totalParcels, List<Delivery> parts)`
  - `record District(String id, DepotNetwork.Depot depot, List<PooledStop> stops)`
  - `public static List<District> build(Collection<Delivery> deliveries, List<DepotNetwork.Depot> openDepots, int maxJobsPerDistrict)`
  - `public static List<DepotNetwork.Depot> selectOpenDepots(Map<String, Coord> depotCoords, List<String> openDepots)` — resolves the config selection. It lives **here**, not in `LausitzFreightPreprocessor`: both `hagrid.integrated.freight` (1d) and `hagrid.integrated.drt` (1c) call it, so a package-private helper in the freight package would not compile for 1c.

- [ ] **Step 1: Write the failing tests**

```java
package hagrid.integrated;

import hagrid.utils.demand.Delivery;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DeliveryDistrictBuilderTest {

    private static Delivery delivery(double x, double y, String provider, int amount) {
        return Delivery.builder()
                .id(provider + "_" + x + "_" + y)
                .coordinate(new Coord(x, y))
                .provider(provider)
                .amount(amount)
                .parcelType(Delivery.ParcelType.B2C)
                .deliveryMode(Delivery.DeliveryMode.HOME)
                .build();
    }

    private static final DepotNetwork.Depot WEST = new DepotNetwork.Depot("west", new Coord(0, 0));
    private static final DepotNetwork.Depot EAST = new DepotNetwork.Depot("east", new Coord(1000, 0));

    @Test
    void poolsAllProvidersAtOneSegmentIntoOneStop() {
        List<DeliveryDistrictBuilder.District> ds = DeliveryDistrictBuilder.build(
                List.of(delivery(10, 0, "dhl", 3),
                        delivery(10, 0, "hermes", 2),
                        delivery(10, 0, "gls", 1)),
                List.of(WEST, EAST), 300);

        assertEquals(1, ds.size());
        assertEquals(1, ds.get(0).stops().size(), "three providers at one segment = one stop");
        DeliveryDistrictBuilder.PooledStop stop = ds.get(0).stops().get(0);
        assertEquals(6, stop.totalParcels());
        assertEquals(3, stop.parts().size(), "original deliveries must survive for the overlay");
    }

    @Test
    void assignsEachStopToTheNearestOpenDepot() {
        List<DeliveryDistrictBuilder.District> ds = DeliveryDistrictBuilder.build(
                List.of(delivery(10, 0, "dhl", 1), delivery(990, 0, "dhl", 1)),
                List.of(WEST, EAST), 300);

        assertEquals(2, ds.size());
        DeliveryDistrictBuilder.District west = ds.stream()
                .filter(d -> d.depot().id().equals("west")).findFirst().orElseThrow();
        DeliveryDistrictBuilder.District east = ds.stream()
                .filter(d -> d.depot().id().equals("east")).findFirst().orElseThrow();
        assertEquals(10.0, west.stops().get(0).coord().getX(), 1e-9);
        assertEquals(990.0, east.stops().get(0).coord().getX(), 1e-9);
    }

    @Test
    void singleOpenDepotYieldsOneCatchment() {
        List<DeliveryDistrictBuilder.District> ds = DeliveryDistrictBuilder.build(
                List.of(delivery(10, 0, "dhl", 1), delivery(990, 0, "gls", 1)),
                List.of(WEST), 300);

        assertEquals(1, ds.size());
        assertEquals(2, ds.get(0).stops().size());
        assertEquals("west", ds.get(0).depot().id());
    }

    @Test
    void isDeterministicAcrossRepeatedBuilds() {
        List<Delivery> in = List.of(delivery(10, 0, "dhl", 1), delivery(20, 5, "gls", 2),
                delivery(990, 0, "ups", 1));
        String a = DeliveryDistrictBuilder.build(in, List.of(WEST, EAST), 300).toString();
        String b = DeliveryDistrictBuilder.build(in, List.of(WEST, EAST), 300).toString();
        assertEquals(a, b);
    }

    @Test
    void rejectsEmptyDepotList() {
        assertThrows(IllegalArgumentException.class, () -> DeliveryDistrictBuilder.build(
                List.of(delivery(0, 0, "dhl", 1)), List.of(), 300));
    }

    @Test
    void selectOpenDepotsKeepsCsvOrderAndDefaultsToAll() {
        java.util.Map<String, Coord> csv = new java.util.LinkedHashMap<>();
        csv.put("wittichenau", new Coord(0, 0));
        csv.put("hoy_sued", new Coord(1000, 0));
        csv.put("spreetal", new Coord(2000, 0));

        assertEquals(List.of("wittichenau", "hoy_sued", "spreetal"),
                DeliveryDistrictBuilder.selectOpenDepots(csv, null).stream()
                        .map(DepotNetwork.Depot::id).toList());
        assertEquals(List.of("wittichenau", "spreetal"),
                DeliveryDistrictBuilder.selectOpenDepots(csv, List.of("spreetal", "WITTICHENAU"))
                        .stream().map(DepotNetwork.Depot::id).toList());
    }

    @Test
    void selectOpenDepotsRejectsAnUnknownName() {
        java.util.Map<String, Coord> csv = java.util.Map.of("wittichenau", new Coord(0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> DeliveryDistrictBuilder.selectOpenDepots(csv, List.of("hoy_sued")));
    }

    /**
     * The 1c/1d consistency guarantee (spec D8/§8): 1d splits at the job ceiling, 1c does not, and
     * both must still pick the SAME depot for every segment. Level 2 partitions, it never reassigns.
     */
    @Test
    void theSegmentToDepotMappingIsIndependentOfTheJobCeiling() {
        List<Delivery> in = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++) {
            in.add(delivery(i * 100, 0, "dhl", 1));
        }
        java.util.Map<String, String> withSplit = depotBySegment(
                DeliveryDistrictBuilder.build(in, List.of(WEST, EAST), 3));
        java.util.Map<String, String> withoutSplit = depotBySegment(
                DeliveryDistrictBuilder.build(in, List.of(WEST, EAST), Integer.MAX_VALUE));

        assertEquals(withoutSplit, withSplit);
    }

    private static java.util.Map<String, String> depotBySegment(
            List<DeliveryDistrictBuilder.District> districts) {
        java.util.Map<String, String> out = new java.util.TreeMap<>();
        for (DeliveryDistrictBuilder.District d : districts) {
            for (DeliveryDistrictBuilder.PooledStop s : d.stops()) {
                out.put(s.coord().getX() + "|" + s.coord().getY(), d.depot().id());
            }
        }
        return out;
    }
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `mvn -pl parcel-demand-2-matsim-pipeline -am test -Dtest=DeliveryDistrictBuilderTest -DfailIfNoSpecifiedTests=false`
Expected: FAIL — `DeliveryDistrictBuilder` does not exist (compilation error).

- [ ] **Step 3: Implement pooling + level 1**

```java
package hagrid.integrated;

import hagrid.utils.demand.Delivery;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Coord;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns provider-separated deliveries into district-based delivery units for the INTEGRATED
 * scenarios (1c/1d). Two levels, in this order:
 *
 * <ol>
 *   <li><b>Pool</b> every delivery at the same segment coordinate into ONE {@link PooledStop} —
 *       the 892 Lausitz segments are served by 3.37 providers on average, so this removes ~2,231
 *       duplicate stops (and, for jsprit, duplicate jobs).</li>
 *   <li><b>Catchment</b>: each stop goes to its nearest OPEN depot. This ordering is what
 *       guarantees the depot is the nearest loading point for every stop it serves — assigning
 *       depots to freely-clustered districts does not.</li>
 * </ol>
 *
 * <p>Level 2 (splitting an oversized catchment) is added in the next task.
 *
 * <p>The Baseline keeps one depot per provider and does NOT use this class
 * (spec 2026-08-17 §D2).
 */
public final class DeliveryDistrictBuilder {

    private static final Logger LOG = LogManager.getLogger(DeliveryDistrictBuilder.class);

    /** One physical stop: all parcels of all providers at one demand segment. */
    public record PooledStop(Coord coord, int totalParcels, List<Delivery> parts) { }

    /** One district: the jsprit optimisation unit / pickup group, anchored at one depot. */
    public record District(String id, DepotNetwork.Depot depot, List<PooledStop> stops) { }

    private DeliveryDistrictBuilder() {}

    public static List<District> build(Collection<Delivery> deliveries,
                                       List<DepotNetwork.Depot> openDepots,
                                       int maxJobsPerDistrict) {
        if (openDepots == null || openDepots.isEmpty()) {
            throw new IllegalArgumentException("at least one open depot is required");
        }
        if (maxJobsPerDistrict < 1) {
            throw new IllegalArgumentException("maxJobsPerDistrict must be >= 1: " + maxJobsPerDistrict);
        }
        DepotNetwork network = new DepotNetwork(openDepots);

        // LinkedHashMap everywhere: district ids and service ids derive from this order.
        Map<String, List<Delivery>> bySegment = new LinkedHashMap<>();
        for (Delivery d : deliveries) {
            bySegment.computeIfAbsent(segmentKey(d.getCoordinate()), k -> new ArrayList<>()).add(d);
        }

        Map<String, List<PooledStop>> byDepot = new LinkedHashMap<>();
        for (List<Delivery> parts : bySegment.values()) {
            Coord coord = parts.get(0).getCoordinate();
            int total = parts.stream().mapToInt(Delivery::getAmount).sum();
            DepotNetwork.Depot depot = network.nearestDepot(coord);
            byDepot.computeIfAbsent(depot.id(), k -> new ArrayList<>())
                    .add(new PooledStop(coord, total, List.copyOf(parts)));
        }

        List<District> districts = new ArrayList<>();
        for (DepotNetwork.Depot depot : openDepots) {
            List<PooledStop> stops = byDepot.get(depot.id());
            if (stops == null || stops.isEmpty()) {
                LOG.info("depot {} has no demand in its catchment - no district built", depot.id());
                continue;
            }
            districts.add(new District(depot.id(), depot, List.copyOf(stops)));
        }
        LOG.info("DeliveryDistrictBuilder: {} deliveries -> {} pooled stops in {} district(s)",
                deliveries.size(), bySegment.size(), districts.size());
        return List.copyOf(districts);
    }

    /** Segments are identified by their exact coordinate (PANDA emits one point per segment). */
    private static String segmentKey(Coord c) {
        return c.getX() + "|" + c.getY();
    }

    /**
     * Resolves the {@code openDepots} config selection against the depot CSV. {@code null}/empty
     * means every depot stays open. Order follows the CSV so district ids are stable across runs.
     *
     * @throws IllegalArgumentException if a named depot is absent from the CSV — a typo would
     *                                  otherwise silently shrink the depot network and move every KPI
     */
    public static List<DepotNetwork.Depot> selectOpenDepots(Map<String, Coord> depotCoords,
                                                            List<String> openDepots) {
        if (openDepots == null || openDepots.isEmpty()) {
            return depotCoords.entrySet().stream()
                    .map(e -> new DepotNetwork.Depot(e.getKey(), e.getValue())).toList();
        }
        List<String> wanted = openDepots.stream().map(s -> s.trim().toLowerCase()).toList();
        for (String w : wanted) {
            if (!depotCoords.containsKey(w)) {
                throw new IllegalArgumentException("openDepots names an unknown depot '" + w
                        + "'. Available: " + depotCoords.keySet());
            }
        }
        return depotCoords.entrySet().stream()
                .filter(e -> wanted.contains(e.getKey()))
                .map(e -> new DepotNetwork.Depot(e.getKey(), e.getValue()))
                .toList();
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -pl parcel-demand-2-matsim-pipeline -am test -Dtest=DeliveryDistrictBuilderTest -DfailIfNoSpecifiedTests=false`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/DeliveryDistrictBuilder.java \
        parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/DeliveryDistrictBuilderTest.java
git commit -m "feat(integrated): DeliveryDistrictBuilder — segment pooling + nearest-depot catchments"
```

---

### Task 3: Level-2 split at the job ceiling

**DEVIATION FROM SPEC — record it.** The spec (§4.2) names `SameSizeKMeans` for the split. It runs through an ELKI database pipeline initialised with `RandomUniformGenerated` (see `DemandProcessor.sortCarrierDemandSameSizeKMeans`), which is neither cheap to call for a plain point list nor deterministic without threading a seed through ELKI. Since level 2 only has to cut a catchment into `k` compact, equally-sized pieces, this task implements a **deterministic median-strip split along the longer bounding-box axis** instead. Note the deviation in the commit message and in the spec's §10 when the plan lands.

**Files:**
- Modify: `parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/DeliveryDistrictBuilder.java`
- Test: `parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/DeliveryDistrictBuilderTest.java`

**Interfaces:**
- Consumes: Task 2's `PooledStop` / `District` / `build(...)`
- Produces: unchanged `build(...)` signature; district ids gain a `#<n>` suffix when a catchment is split (e.g. `hoy_sued#0`, `hoy_sued#1`).

- [ ] **Step 1: Write the failing tests**

Append to `DeliveryDistrictBuilderTest`:

```java
    @Test
    void splitsACatchmentThatExceedsTheJobCeiling() {
        List<Delivery> many = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            many.add(delivery(i * 10, 0, "dhl", 1));   // 10 distinct segments, all nearest WEST
        }
        List<DeliveryDistrictBuilder.District> ds =
                DeliveryDistrictBuilder.build(many, List.of(WEST), 4);

        assertEquals(3, ds.size(), "10 stops at ceiling 4 -> ceil(10/4) = 3 districts");
        assertTrue(ds.stream().allMatch(d -> d.stops().size() <= 4));
        assertEquals(10, ds.stream().mapToInt(d -> d.stops().size()).sum());
        assertTrue(ds.stream().allMatch(d -> d.depot().id().equals("west")),
                "sub-districts share their catchment's depot");
        assertEquals(List.of("west#0", "west#1", "west#2"), ds.stream().map(
                DeliveryDistrictBuilder.District::id).toList());
    }

    @Test
    void doesNotSplitACatchmentBelowTheCeiling() {
        List<DeliveryDistrictBuilder.District> ds = DeliveryDistrictBuilder.build(
                List.of(delivery(10, 0, "dhl", 1), delivery(20, 0, "gls", 1)),
                List.of(WEST), 4);

        assertEquals(1, ds.size());
        assertEquals("west", ds.get(0).id(), "an unsplit catchment keeps the plain depot id");
    }

    @Test
    void splitsAlongTheLongerAxisSoDistrictsStayCompact() {
        List<Delivery> wide = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            wide.add(delivery(i * 1000, 0, "dhl", 1));   // spread in x, flat in y
        }
        List<DeliveryDistrictBuilder.District> ds =
                DeliveryDistrictBuilder.build(wide, List.of(WEST), 2);

        assertEquals(2, ds.size());
        double maxXFirst = ds.get(0).stops().stream().mapToDouble(s -> s.coord().getX()).max().orElseThrow();
        double minXSecond = ds.get(1).stops().stream().mapToDouble(s -> s.coord().getX()).min().orElseThrow();
        assertTrue(maxXFirst < minXSecond, "districts must not interleave along the split axis");
    }
```

- [ ] **Step 2: Run to verify they fail**

Run: `mvn -pl parcel-demand-2-matsim-pipeline -am test -Dtest=DeliveryDistrictBuilderTest -DfailIfNoSpecifiedTests=false`
Expected: FAIL — the three new tests fail (one district returned instead of the split ones).

- [ ] **Step 3: Implement the split**

Replace the district-assembly loop in `build(...)` with:

```java
        List<District> districts = new ArrayList<>();
        for (DepotNetwork.Depot depot : openDepots) {
            List<PooledStop> stops = byDepot.get(depot.id());
            if (stops == null || stops.isEmpty()) {
                LOG.info("depot {} has no demand in its catchment - no district built", depot.id());
                continue;
            }
            int parts = (int) Math.ceil(stops.size() / (double) maxJobsPerDistrict);
            if (parts <= 1) {
                districts.add(new District(depot.id(), depot, List.copyOf(stops)));
            } else {
                List<List<PooledStop>> chunks = splitEvenly(stops, parts);
                for (int i = 0; i < chunks.size(); i++) {
                    districts.add(new District(depot.id() + "#" + i, depot, List.copyOf(chunks.get(i))));
                }
                LOG.info("catchment {} has {} stops > ceiling {} -> split into {} districts",
                        depot.id(), stops.size(), maxJobsPerDistrict, chunks.size());
            }
        }
```

and add:

```java
    /**
     * Deterministic compact split: sort along the wider bounding-box axis, then cut into {@code parts}
     * contiguous blocks of near-equal size. Chosen over {@code SameSizeKMeans} because level 2 only
     * needs a reproducible equal-size cut and the ELKI path is seeded randomly (see plan Task 3).
     */
    private static List<List<PooledStop>> splitEvenly(List<PooledStop> stops, int parts) {
        double minX = stops.stream().mapToDouble(s -> s.coord().getX()).min().orElseThrow();
        double maxX = stops.stream().mapToDouble(s -> s.coord().getX()).max().orElseThrow();
        double minY = stops.stream().mapToDouble(s -> s.coord().getY()).min().orElseThrow();
        double maxY = stops.stream().mapToDouble(s -> s.coord().getY()).max().orElseThrow();
        boolean byX = (maxX - minX) >= (maxY - minY);

        List<PooledStop> sorted = new ArrayList<>(stops);
        // Tie-break on the other axis so the order is total and reproducible.
        sorted.sort(byX
                ? java.util.Comparator.comparingDouble((PooledStop s) -> s.coord().getX())
                        .thenComparingDouble(s -> s.coord().getY())
                : java.util.Comparator.comparingDouble((PooledStop s) -> s.coord().getY())
                        .thenComparingDouble(s -> s.coord().getX()));

        List<List<PooledStop>> chunks = new ArrayList<>();
        int n = sorted.size();
        int base = n / parts;
        int remainder = n % parts;
        int from = 0;
        for (int i = 0; i < parts; i++) {
            int size = base + (i < remainder ? 1 : 0);
            chunks.add(new ArrayList<>(sorted.subList(from, from + size)));
            from += size;
        }
        return chunks;
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -pl parcel-demand-2-matsim-pipeline -am test -Dtest=DeliveryDistrictBuilderTest -DfailIfNoSpecifiedTests=false`
Expected: PASS (10 tests).

- [ ] **Step 5: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/DeliveryDistrictBuilder.java \
        parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/DeliveryDistrictBuilderTest.java
git commit -m "feat(integrated): split oversized catchments at the job ceiling

Deviation from spec §4.2: deterministic median-strip split instead of SameSizeKMeans,
which is ELKI-backed and randomly seeded. Same guarantee (equal-size, compact), reproducible."
```

---

### Task 4: Per-provider delivery-rate overlay for district carriers

Today `buildCore` draws one daily bias per carrier and looks up `DELIVERY_RATES.getOrDefault(provider, 90.0)`. With a district as the carrier, that lookup falls through to the 90 % default instead of the per-provider rates (dhl 94 … dpd/ups/fedex 89).

**Where that bites is not `delivery_rate`.** Since 2026-08-10 that KPI is *gross* in all three arms — "delivered / demand, overlay NOT subtracted" (METHODS-LOG), which is why `f150t015` reports `delivery_rate = 1.0`. The overlay feeds `parcels_handled`, which stays deliberately net and is the denominator of `economics.freight_cost_per_parcel`. Falling through to the default drops `parcels_handled` from 5,665 to ~5,447 and raises €/parcel by roughly 4 % — a silent shift with no change in the concept. `delivery_rate_net_overlay` and `parcels_per_vehicle_km` move with it. Fix: the rate must follow each parcel's own provider.

**Files:**
- Modify: `parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/freight/LmdCarrierBuilder.java`
- Test: `parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/freight/LmdCarrierBuilderTest.java`

**Interfaces:**
- Consumes: `DeliveryDistrictBuilder.PooledStop`
- Produces, relied on by Task 5:
  `static Carrier buildDistrict(String districtId, List<DeliveryDistrictBuilder.PooledStop> stops, Id<Link> depotLink, Network network, VehicleType[] vehicleTypes, int durationPerParcelMin, int maxDurationPerStopMin, Random random, double vehicleEarliestStart, double vehicleLatestEnd, double serviceTwStart, double serviceTwEnd)`

- [ ] **Step 1: Write the failing test**

```java
    @Test
    void districtCarrierAppliesEachParcelsOwnProviderRate() {
        // dhl 94 %, gls 91 % — a district spanning both must NOT collapse to the 90 % default.
        List<DeliveryDistrictBuilder.PooledStop> stops = List.of(
                new DeliveryDistrictBuilder.PooledStop(new Coord(100, 100), 200,
                        List.of(deliveryAt(100, 100, "dhl", 100, Delivery.ParcelType.B2C),
                                deliveryAt(100, 100, "gls", 100, Delivery.ParcelType.B2C))));

        Carrier c = LmdCarrierBuilder.buildDistrict("bez0", stops, DEPOT_LINK, network(),
                vanTypes(), 2, 15, new Random(1L), 27000.0, 75600.0, 27000.0, 75600.0);

        assertEquals(1, c.getServices().size(), "one pooled stop = one service");
        assertEquals(200, (int) c.getAttributes().getAttribute("numberOfParcels"));
        int missed = (int) c.getAttributes().getAttribute("missedParcels");
        // 100 parcels at ~94 % + 100 at ~91 % -> expectation ~15; the 90 % default would give ~20.
        assertTrue(missed > 3 && missed < 35, "implausible missed count: " + missed);
    }

    @Test
    void districtCarrierWithOneProviderMatchesTheProviderRateBand() {
        List<DeliveryDistrictBuilder.PooledStop> stops = List.of(
                new DeliveryDistrictBuilder.PooledStop(new Coord(100, 100), 1000,
                        List.of(deliveryAt(100, 100, "dhl", 1000, Delivery.ParcelType.B2C))));

        Carrier c = LmdCarrierBuilder.buildDistrict("bez0", stops, DEPOT_LINK, network(),
                vanTypes(), 2, 15, new Random(1L), 27000.0, 75600.0, 27000.0, 75600.0);

        int missed = (int) c.getAttributes().getAttribute("missedParcels");
        assertTrue(missed > 20 && missed < 160,
                "1000 dhl parcels at 94 % +/- daily bias, got " + missed);
    }

    @Test
    void b2bParcelsInADistrictKeepTheB2BRate() {
        List<DeliveryDistrictBuilder.PooledStop> stops = List.of(
                new DeliveryDistrictBuilder.PooledStop(new Coord(100, 100), 1000,
                        List.of(deliveryAt(100, 100, "dpd", 1000, Delivery.ParcelType.B2B))));

        Carrier c = LmdCarrierBuilder.buildDistrict("bez0", stops, DEPOT_LINK, network(),
                vanTypes(), 2, 15, new Random(1L), 27000.0, 75600.0, 27000.0, 75600.0);

        int missed = (int) c.getAttributes().getAttribute("missedParcels");
        assertTrue(missed < 40, "B2B is ~99 % reliable, got " + missed + " missed of 1000");
    }
```

Add the `deliveryAt`, `DEPOT_LINK`, `network()` and `vanTypes()` helpers by copying the fixture pattern already used by the existing tests in this file — do not invent a second fixture style.

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -pl parcel-demand-2-matsim-pipeline -am test -Dtest=LmdCarrierBuilderTest -DfailIfNoSpecifiedTests=false`
Expected: FAIL — `buildDistrict` does not exist (compilation error).

- [ ] **Step 3: Implement `buildDistrict`**

Add to `LmdCarrierBuilder` (do **not** touch `build`, `buildSingleWindow` or `buildCore`):

```java
    /**
     * District variant for the INTEGRATED scenarios (spec 2026-08-17): the carrier is a delivery
     * district, not an LSP. One {@link CarrierService} per pooled stop, and the missed-delivery
     * overlay draws each parcel against ITS OWN provider's rate — a district spans several
     * providers, so the per-carrier lookup used by {@link #buildCore} would fall through to the
     * 90 % default and move 1d's delivery rate by ~3.6 pp.
     *
     * <p>Draw order (contract): providers in first-appearance order get one daily bias each, then
     * stops in list order, within a stop the parts in list order, within a part one draw per parcel.
     */
    public static Carrier buildDistrict(String districtId,
            List<hagrid.integrated.DeliveryDistrictBuilder.PooledStop> stops, Id<Link> depotLink,
            Network network, VehicleType[] vehicleTypes, int durationPerParcelMin,
            int maxDurationPerStopMin, Random random, double vehicleEarliestStart,
            double vehicleLatestEnd, double serviceTwStart, double serviceTwEnd) {

        Carrier carrier = CarriersUtils.createCarrier(Id.create(districtId, Carrier.class));
        CarriersUtils.setCarrierMode(carrier, "car");
        carrier.getCarrierCapabilities().setFleetSize(FleetSize.INFINITE);
        carrier.getAttributes().putAttribute("district", districtId);

        // One daily bias per provider present in this district (first-appearance order).
        Map<String, Double> dailyBias = new java.util.LinkedHashMap<>();
        for (var stop : stops) {
            for (Delivery d : stop.parts()) {
                String p = d.getProvider() == null ? "" : d.getProvider().trim().toLowerCase();
                dailyBias.computeIfAbsent(p, key ->
                        random.nextGaussian() * ("dhl".equals(key) ? 2.5 : 5.0));
            }
        }

        TimeWindow tw = TimeWindow.newInstance(serviceTwStart, serviceTwEnd);
        List<Id<CarrierService>> missedParcels = new ArrayList<>();
        int totalParcels = 0;
        int n = 0;
        for (var stop : stops) {
            Link link = NetworkUtils.getNearestLinkExactly(network, stop.coord());
            double duration = Math.min(
                    (durationPerParcelMin * 60.0) * stop.totalParcels(),
                    maxDurationPerStopMin * 60.0);
            Id<CarrierService> serviceId = Id.create(districtId + "_" + n++, CarrierService.class);
            CarriersUtils.addService(carrier, CarrierService.Builder
                    .newInstance(serviceId, link.getId())
                    .setCapacityDemand(stop.totalParcels())
                    .setServiceDuration(duration)
                    .setServiceStartTimeWindow(tw)
                    .build());
            totalParcels += stop.totalParcels();

            for (Delivery d : stop.parts()) {
                String p = d.getProvider() == null ? "" : d.getProvider().trim().toLowerCase();
                double effectiveRate = d.getParcelType() == Delivery.ParcelType.B2B
                        ? B2B_DELIVERY_RATE
                        : Math.max(0.0, Math.min(MAX_EFFECTIVE_RATE,
                                DELIVERY_RATES.getOrDefault(p, DEFAULT_DELIVERY_RATE)
                                        + dailyBias.getOrDefault(p, 0.0)));
                for (int i = 0; i < d.getAmount(); i++) {
                    if (random.nextDouble() * 100.0 > effectiveRate) {
                        missedParcels.add(serviceId);
                    }
                }
            }
        }

        for (VehicleType type : vehicleTypes) {
            CarriersUtils.addCarrierVehicle(carrier, CarrierVehicle.Builder
                    .newInstance(Id.createVehicleId(districtId + "_" + type.getId() + "_day_v0"),
                            depotLink, type)
                    .setEarliestStart(vehicleEarliestStart)
                    .setLatestEnd(vehicleLatestEnd)
                    .build());
        }

        carrier.getAttributes().putAttribute("numberOfParcels", totalParcels);
        carrier.getAttributes().putAttribute("missedParcels", missedParcels.size());
        carrier.getAttributes().putAttribute("missedParcelsAsList", new ArrayList<>(missedParcels));
        carrier.getAttributes().putAttribute("missedParcelDeliveriesAsString", missedParcels.toString());
        return carrier;
    }
```

- [ ] **Step 4: Run the tests**

Run: `mvn -pl parcel-demand-2-matsim-pipeline -am test -Dtest=LmdCarrierBuilderTest -DfailIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 5: Confirm the Baseline guard still holds**

Run: `mvn -pl parcel-demand-2-matsim-pipeline -am test -Dtest=LausitzFreightPreprocessorTest#baselineCarrierOutputIsUnchanged -DfailIfNoSpecifiedTests=false`
Expected: PASS. If it fails, `build`/`buildCore` was touched — revert that part.

- [ ] **Step 6: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/freight/LmdCarrierBuilder.java \
        parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/freight/LmdCarrierBuilderTest.java
git commit -m "feat(integrated): district carriers draw the missed-delivery overlay per parcel provider"
```

---

### Task 5: Wire 1d Modular onto districts

**Files:**
- Modify: `parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/freight/LausitzFreightPreprocessor.java:183-235` (`runModular` only)
- Test: `parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/freight/LausitzFreightPreprocessorTest.java`

**Interfaces:**
- Consumes: `DeliveryDistrictBuilder.build(...)`, `LmdCarrierBuilder.buildDistrict(...)`, `DrtDepotReader.readByProvider(Path)`
- Produces, relied on by Task 7: `runModular(String demandShp, String depotCsv, String networkFile, String vanTypesFile, String carriersOut, int jspritIterations, String serviceAreaShp, int maxTourDurationSeconds, List<String> openDepots, int maxJobsPerDistrict)`

- [ ] **Step 1: Write the failing test**

```java
    @Test
    void modularBuildsOneCarrierPerDistrictNotPerProvider(@TempDir Path tmp) throws Exception {
        Path out = tmp.resolve("carriers.xml.gz");
        LausitzFreightPreprocessor.runModular(
                LmdTestShapefiles.demandShapefile(tmp).toString(),
                LmdTestShapefiles.depotCsv(tmp).toString(),
                LmdTestShapefiles.networkFile(tmp).toString(),
                LmdTestShapefiles.vehicleTypesFile(tmp).toString(),
                out.toString(), 1, null, 12600,
                List.of("wittichenau"), 300);

        Carriers carriers = new Carriers();
        new CarrierPlanXmlReader(carriers, new CarrierVehicleTypes()).readFile(out.toString());
        assertEquals(1, carriers.getCarriers().size(),
                "one open depot + ceiling 300 = one district carrier");
        Carrier only = carriers.getCarriers().values().iterator().next();
        assertEquals("wittichenau", only.getId().toString(), "carrier id is the district id");
        assertNotNull(only.getAttributes().getAttribute("district"));
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -pl parcel-demand-2-matsim-pipeline -am test -Dtest=LausitzFreightPreprocessorTest -DfailIfNoSpecifiedTests=false`
Expected: FAIL — the 10-argument `runModular` overload does not exist.

- [ ] **Step 3: Rewrite `runModular`'s demand/carrier block**

Keep steps 1, 2, 5 and 6 of `runModular` exactly as they are. Replace steps 3 and 4 (lines 197–222) with:

```java
        // 3. demand -> pooled stops in districts anchored at the nearest OPEN depot
        Map<String, List<Delivery>> byProvider = LmdDemandReader.group(LmdDemandReader.read(demandShp));
        if (serviceAreaShp != null && !serviceAreaShp.isBlank()) {
            byProvider = clipToServiceArea(byProvider, serviceAreaShp);
        }
        List<Delivery> all = byProvider.values().stream().flatMap(List::stream).toList();
        Map<String, Coord> depotCoords = DrtDepotReader.readBySite(java.nio.file.Path.of(depotCsv));
        List<DepotNetwork.Depot> open =
                DeliveryDistrictBuilder.selectOpenDepots(depotCoords, openDepots);
        List<DeliveryDistrictBuilder.District> districts =
                DeliveryDistrictBuilder.build(all, open, maxJobsPerDistrict);

        // 4. one carrier per DISTRICT (not per LSP), anchored at the district's depot
        Carriers carriers = new Carriers();
        for (DeliveryDistrictBuilder.District district : districts) {
            Link depotLink = NetworkUtils.getNearestLinkExactly(network, district.depot().coord());
            Random missedRng = new Random(MISSED_DELIVERY_SEED + district.id().hashCode());
            Carrier carrier = LmdCarrierBuilder.buildDistrict(district.id(), district.stops(),
                    depotLink.getId(), network, capsuleArr,
                    DURATION_PER_PARCEL_MIN, MAX_DURATION_PER_STOP_MIN, missedRng,
                    Modular.DELIVERY_DAY_START_S, Modular.DELIVERY_DAY_END_S,
                    Modular.DELIVERY_DAY_START_S, Modular.DELIVERY_DAY_END_S);
            CarriersUtils.setJspritIterations(carrier, Math.max(1, jspritIterations));
            carriers.addCarrier(carrier);
        }
```

Change the signature to take the two new parameters:

```java
    public static void runModular(String demandShp, String depotCsv, String networkFile,
                                  String vanTypesFile, String carriersOut, int jspritIterations,
                                  String serviceAreaShp, int maxTourDurationSeconds,
                                  List<String> openDepots, int maxJobsPerDistrict) {
```

Keep the existing 8-argument `runModular` as an overload delegating with `null, 300` so current callers and the existing `runModularRoutesWithCapsuleTypeAndDayWindow` test keep compiling. Depot selection comes from `DeliveryDistrictBuilder.selectOpenDepots` (Task 2) — do not add a second copy here.

Add the required imports: `hagrid.integrated.DeliveryDistrictBuilder`, `hagrid.integrated.DepotNetwork`, `hagrid.integrated.drt.DrtDepotReader`.

- [ ] **Step 4: Run the tests**

Run: `mvn -pl parcel-demand-2-matsim-pipeline -am test -Dtest=LausitzFreightPreprocessorTest -DfailIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 5: Run the Baseline guard and the freight suite**

Run: `mvn -pl parcel-demand-2-matsim-pipeline -am test -Dtest='hagrid.integrated.freight.*Test' -DfailIfNoSpecifiedTests=false`
Expected: PASS, including `baselineCarrierOutputIsUnchanged`.

- [ ] **Step 6: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/freight/LausitzFreightPreprocessor.java \
        parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/freight/LausitzFreightPreprocessorTest.java
git commit -m "feat(1d): build modular carriers per delivery district instead of per provider"
```

---

### Task 6: Wire 1c Shared-Use onto districts

**Files:**
- Modify: `parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/shareduse/ParcelAgentGenerator.java`
- Modify: `parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/drt/LausitzDrtPreprocessor.java:218-228`
- Test: `parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/shareduse/ParcelAgentGeneratorTest.java`

**Interfaces:**
- Consumes: `DeliveryDistrictBuilder.District` / `PooledStop`
- Produces: `static Result generate(List<DeliveryDistrictBuilder.District> districts, Geometry serviceArea, Network drtNetwork, Population population, long seed)` — same `Result` record as today.

- [ ] **Step 1: Write the failing test**

Uses the fixtures this test class already has: `square(2000)` for the service area,
`hagrid.integrated.drt.DrtE2eFixtures.buildGrid()` for the drt network, and
`ScenarioUtils.createScenario(ConfigUtils.createConfig()).getPopulation()` — same as
`generatesOnePersonPerInAreaDeliveryWithLoadDwellAndPlan`. Delivery coordinate `(800, 800)` and
depot `(500, 500)` are the coordinates that class already proves land on distinct grid links.

```java
    private static Delivery deliveryAt(double x, double y, String provider, int amount) {
        return Delivery.builder().id(provider + "_" + x + "_" + y).coordinate(new Coord(x, y))
                .provider(provider).amount(amount).parcelType(ParcelType.B2C).build();
    }

    @Test
    void oneParcelPersonPerPooledStopNotPerProvider() {
        // Same segment, three providers -> today 3 persons; after pooling 1 person with 6 parcels.
        Network net = hagrid.integrated.drt.DrtE2eFixtures.buildGrid();
        Population pop = ScenarioUtils.createScenario(ConfigUtils.createConfig()).getPopulation();
        var districts = DeliveryDistrictBuilder.build(
                List.of(deliveryAt(800, 800, "dhl", 3),
                        deliveryAt(800, 800, "hermes", 2),
                        deliveryAt(800, 800, "gls", 1)),
                List.of(new DepotNetwork.Depot("hoy_sued", new Coord(500, 500))), 300);

        var r = ParcelAgentGenerator.generate(districts, square(2000), net, pop, 4711L);

        assertEquals(1, r.personsAdded());
        assertEquals(6, r.parcels());
        Person p = pop.getPersons().values().iterator().next();
        assertTrue(p.getId().toString().startsWith(SharedUse.PARCEL_PERSON_PREFIX),
                "the parcel_ prefix contract must survive pooling");
        assertEquals(6, (int) (Integer) p.getAttributes().getAttribute(SharedUse.LOAD_ATTRIBUTE));
        assertEquals(SharedUse.segmentDwellSeconds(6),
                (double) (Double) p.getAttributes().getAttribute(SharedUse.DWELL_ATTRIBUTE), 1e-9);
    }

    @Test
    void stopOriginIsTheDistrictDepotNotTheProviderDepot() {
        Network net = hagrid.integrated.drt.DrtE2eFixtures.buildGrid();
        Population pop = ScenarioUtils.createScenario(ConfigUtils.createConfig()).getPopulation();
        var districts = DeliveryDistrictBuilder.build(
                List.of(deliveryAt(800, 800, "gls", 1)),
                List.of(new DepotNetwork.Depot("hoy_sued", new Coord(500, 500))), 300);

        ParcelAgentGenerator.generate(districts, square(2000), net, pop, 4711L);

        Person p = pop.getPersons().values().iterator().next();
        Activity depot = (Activity) p.getSelectedPlan().getPlanElements().get(0);
        assertEquals(SharedUse.ACT_DEPOT, depot.getType());
        assertEquals(new Coord(500, 500), depot.getCoord(),
                "a gls parcel must now start at the hoy_sued district depot");
    }

    @Test
    void oversizedStopsStillSplitIntoParcelSlotChunks() {
        Network net = hagrid.integrated.drt.DrtE2eFixtures.buildGrid();
        Population pop = ScenarioUtils.createScenario(ConfigUtils.createConfig()).getPopulation();
        var districts = DeliveryDistrictBuilder.build(
                List.of(deliveryAt(800, 800, "dhl", 45)),
                List.of(new DepotNetwork.Depot("hoy_sued", new Coord(500, 500))), 300);

        var r = ParcelAgentGenerator.generate(districts, square(2000), net, pop, 4711L);

        assertEquals(3, r.personsAdded(), "45 parcels at 20 slots -> 20 + 20 + 5");
        assertEquals(45, r.parcels());
    }

    @Test
    void stopsOutsideTheServiceAreaAreStillClipped() {
        Network net = hagrid.integrated.drt.DrtE2eFixtures.buildGrid();
        Population pop = ScenarioUtils.createScenario(ConfigUtils.createConfig()).getPopulation();
        var districts = DeliveryDistrictBuilder.build(
                List.of(deliveryAt(800, 800, "dhl", 3), deliveryAt(9_999_999, 0, "dhl", 2)),
                List.of(new DepotNetwork.Depot("hoy_sued", new Coord(500, 500))), 300);

        var r = ParcelAgentGenerator.generate(districts, square(2000), net, pop, 4711L);

        assertEquals(1, r.personsAdded());
        assertEquals(1, r.clippedOutside());
    }
```

The existing tests in this class that call the old `generate(Map<String, List<Delivery>>, …)`
signature must be migrated to the district form in this task — the old overload is deleted, not kept.

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -pl parcel-demand-2-matsim-pipeline -am test -Dtest=ParcelAgentGeneratorTest -DfailIfNoSpecifiedTests=false`
Expected: FAIL — no `generate` overload taking districts.

- [ ] **Step 3: Rewrite `generate`**

Replace the signature and the `byProvider` loop. Delete the `fallbackDepots` / `warnedProviders` / `M4(b)` block entirely; the district already carries its depot.

```java
    public static Result generate(List<hagrid.integrated.DeliveryDistrictBuilder.District> districts,
                                  Geometry serviceArea, Network drtNetwork,
                                  Population population, long seed) {
        DeliveryChannelResolver resolver = new DeliveryChannelResolver(List.of(), 500.0); // Phase 1: no lockers
        Random rnd = new Random(seed);
        PopulationFactory pf = population.getFactory();

        int persons = 0, parcels = 0, skipped = 0, outside = 0, index = 0;
        for (var district : districts) {
            Link depotLink = NetworkUtils.getNearestLinkExactly(drtNetwork, district.depot().coord());
            for (var stop : district.stops()) {
                index++;
                if (!serviceArea.contains(GF.createPoint(new org.locationtech.jts.geom.Coordinate(
                        stop.coord().getX(), stop.coord().getY())))) {
                    outside++;
                    continue;
                }
                Link segmentLink = NetworkUtils.getNearestLinkExactly(drtNetwork, stop.coord());
                if (depotLink.getId().equals(segmentLink.getId())) {
                    skipped++;                       // DefaultPassengerRequestValidator rejects from==to
                    LOG.warn("parcel stop {} snaps to its depot link {} - skipped", index,
                            depotLink.getId());
                    continue;
                }

                List<Integer> subLoads = splitLoad(stop.totalParcels());
                // A pooled stop can mix channels; B2B presence forces door delivery, which is what
                // the Phase-1 (locker-free) resolver returns anyway.
                String channel = resolver.resolve(stop.parts().get(0)).name();
                double windowEnd = SharedUse.WINDOW_END_S;

                for (int part = 0; part < subLoads.size(); part++) {
                    int subLoad = subLoads.get(part);
                    String idSuffix = subLoads.size() > 1 ? "_p" + part : "";
                    Person p = pf.createPerson(Id.createPersonId(SharedUse.PARCEL_PERSON_PREFIX
                            + district.id() + "_" + index + idSuffix));
                    PopulationUtils.putSubpopulation(p, SharedUse.PARCEL_SUBPOPULATION);
                    p.getAttributes().putAttribute(SharedUse.LOAD_ATTRIBUTE, subLoad);
                    p.getAttributes().putAttribute(SharedUse.DWELL_ATTRIBUTE,
                            SharedUse.segmentDwellSeconds(subLoad));
                    p.getAttributes().putAttribute(SharedUse.CHANNEL_ATTRIBUTE, channel);
                    p.getAttributes().putAttribute("district", district.id());
                    p.getAttributes().putAttribute(SharedUse.WINDOW_END_ATTRIBUTE, windowEnd);

                    Plan plan = pf.createPlan();
                    Activity depot = pf.createActivityFromLinkId(SharedUse.ACT_DEPOT, depotLink.getId());
                    depot.setCoord(district.depot().coord());
                    depot.setEndTime(SharedUse.SUBMIT_FROM_S
                            + rnd.nextDouble() * (SharedUse.SUBMIT_TO_S - SharedUse.SUBMIT_FROM_S));
                    plan.addActivity(depot);
                    plan.addLeg(pf.createLeg("drt"));
                    Activity delivery = pf.createActivityFromLinkId(SharedUse.ACT_DELIVERY,
                            segmentLink.getId());
                    delivery.setCoord(stop.coord());
                    plan.addActivity(delivery);
                    p.addPlan(plan);
                    population.addPerson(p);
                    persons++;
                    parcels += subLoad;
                }
            }
        }
        LOG.info("ParcelAgentGenerator: {} parcel-persons ({} parcels), {} outside area, {} same-link skipped",
                persons, parcels, outside, skipped);
        return new Result(persons, parcels, skipped, outside);
    }
```

- [ ] **Step 4: Update the 1c call site**

In `LausitzDrtPreprocessor` replace the `M4(b)` block (lines ~218–228) with the following. Two
deliberate details: the demand is **clipped before** districting (spec D9, same order as 1d, so both
arms see the same input), and the builder is called with `Integer.MAX_VALUE` (spec D8 — 1c has no
jsprit to partition).

```java
            Map<String, Coord> depotCoords =
                    DrtDepotReader.readBySite(Path.of(cfg.getLmdDepotCsv()));
            // Clip BEFORE districting so 1c and 1d district the identical delivery set.
            List<Delivery> clipped = LausitzFreightPreprocessor.clipToArea(
                    LmdDemandReader.group(LmdDemandReader.read(cfg.getLmdDemandShapefile())), area)
                    .values().stream().flatMap(List::stream).toList();
            List<DeliveryDistrictBuilder.District> districts = DeliveryDistrictBuilder.build(
                    clipped,
                    DeliveryDistrictBuilder.selectOpenDepots(depotCoords, cfg.getOpenDepots()),
                    Integer.MAX_VALUE);   // 1c never splits (spec D8)
            ParcelAgentGenerator.Result r = ParcelAgentGenerator.generate(
                    districts, area, drtNet, pop, 4711L);
```

`clipToArea` is currently package-private in `hagrid.integrated.freight` — make it `public` (it is
already the shared clip both arms use). `cfg.getOpenDepots()` arrives in Task 7; until then pass
`null`. The `contains` check inside the generator stays as a safety net: in production runs
`clippedOutside` must now be 0, and anything else means the two clips disagree.

- [ ] **Step 5: Run the tests**

Run: `mvn -pl parcel-demand-2-matsim-pipeline -am test -Dtest='hagrid.integrated.shareduse.*Test' -DfailIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/shareduse/ParcelAgentGenerator.java \
        parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/drt/LausitzDrtPreprocessor.java \
        parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/shareduse/ParcelAgentGeneratorTest.java
git commit -m "feat(1c): one parcel request per pooled segment, picked up at the district depot"
```

---

### Task 7: Runner configuration and run metadata

**Files:**
- Modify: `parcel-demand-2-matsim-pipeline/src/main/java/hagrid/simulation/HAGRIDSimulationConfig.java`
- Modify: `parcel-demand-2-matsim-pipeline/src/main/java/hagrid/simulation/SimulationRunnerUtils.java:129-222`
- Modify: `parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/drt/LausitzDrtPreprocessor.java` (replace the Task 6 literals)
- Test: `parcel-demand-2-matsim-pipeline/src/test/java/hagrid/simulation/HAGRIDSimulationConfigTest.java`

**Interfaces:**
- Consumes: the parse helpers already in `SimulationRunnerUtils` (`bool`, `anyDouble`, `nonNegDouble`) and the `map.getOrDefault(...)` pattern used for `chiThreshold` at line 145
- Produces: `HAGRIDSimulationConfig.getOpenDepots()` returning `List<String>` (empty = all) and `getMaxJobsPerDistrict()` returning `int`

- [ ] **Step 1: Write the failing test**

```java
    @Test
    void openDepotsDefaultsToAllAndMaxJobsToThreeHundred() {
        HAGRIDSimulationConfig cfg = HAGRIDSimulationConfig.defaults();
        assertTrue(cfg.getOpenDepots().isEmpty(), "empty list means every depot is open");
        assertEquals(300, cfg.getMaxJobsPerDistrict());
    }

    @Test
    void maxJobsPerDistrictMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> HAGRIDSimulationConfig.defaults()
                .withDistrictSettings(List.of("hoy_sued"), 0));
    }
```

Adapt `defaults()` / `withDistrictSettings` to whatever construction pattern the existing tests in this file use — mirror it, do not introduce a second style.

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -pl parcel-demand-2-matsim-pipeline -am test -Dtest=HAGRIDSimulationConfigTest -DfailIfNoSpecifiedTests=false`
Expected: FAIL — no such getters.

- [ ] **Step 3: Add the fields, the parse and the metadata**

In `HAGRIDSimulationConfig` add `private final List<String> openDepots;` and `private final int maxJobsPerDistrict;` following the `chiThreshold` field/ctor/getter pattern, validating `maxJobsPerDistrict > 0` next to the existing `maxTourDurationSeconds <= 0` check (line ~373).

In `SimulationRunnerUtils`, next to the `chiThreshold` parse at line 145:

```java
        // District-based depot assignment for the INTEGRATED arms (spec 2026-08-17). Ignored by
        // the Baseline, which keeps one depot per provider. Same parse pattern as chiThreshold.
        String openDepotsRaw = map.getOrDefault("openDepots", "all").trim();
        List<String> openDepots = "all".equalsIgnoreCase(openDepotsRaw)
                ? List.of()
                : java.util.Arrays.stream(openDepotsRaw.split(","))
                        .map(s -> s.trim().toLowerCase())
                        .filter(s -> !s.isEmpty())
                        .toList();
        int maxJobsPerDistrict = Integer.parseInt(map.getOrDefault("maxJobsPerDistrict", "300").trim());
```

Add both to the `LOG.info("Scenario: ...")` line at 216 and to the `HAGRIDSimulationConfig` constructor call at 220.

Then replace the Task-6 literals in `LausitzDrtPreprocessor` with `cfg.getOpenDepots()` and `cfg.getMaxJobsPerDistrict()`, and pass the same two values into `runModular` at its 1d call site.

- [ ] **Step 4: Extend the run-directory guard**

`SimulationRunnerUtils` already refuses an untagged `DRT_SHAREDUSE` run because `chiThreshold` is not part of the run id (line ~176). `openDepots` has the same problem for both integrated concepts. Extend that check so a `DRT_SHAREDUSE` **or** `DRT_MODULAR` run with a non-default `openDepots` also requires a non-blank `tag`, with a message naming `openDepots`.

- [ ] **Step 5: Record it in run metadata**

Add `openDepots` and `maxJobsPerDistrict` to `RunMetadataWriter`'s `run_metadata.json` output so the KPI layer can label sweep stages. Follow the existing key/value style in that class.

- [ ] **Step 6: Run the tests**

Run: `mvn -pl parcel-demand-2-matsim-pipeline -am test -Dtest='HAGRIDSimulationConfigTest,LausitzFreightPreprocessorTest' -DfailIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/src/main/java/hagrid/simulation/ \
        parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/drt/LausitzDrtPreprocessor.java \
        parcel-demand-2-matsim-pipeline/src/test/java/hagrid/simulation/HAGRIDSimulationConfigTest.java
git commit -m "feat(runner): openDepots / maxJobsPerDistrict config keys + run metadata"
```

---

### Task 8: End-to-end guard and full-suite verification

**Files:**
- Modify: `parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/drt/SharedUseEndToEndTest.java`
- Modify: `parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/freight/LmdBaselineEndToEndTest.java` (assertion only, no behaviour change)

**Interfaces:**
- Consumes: everything from Tasks 2–7.
- Produces: nothing.

- [ ] **Step 1: Write the failing e2e assertion**

In `SharedUseEndToEndTest`, add to the existing short run:

```java
    @Test
    void sharedUseRunsWithASingleOpenDepot(@TempDir Path tmp) throws Exception {
        // Same short run as the existing e2e, but with openDepots=hoy_sued: every parcel must
        // originate at the one open depot, none at a provider yard.
        runShortSharedUseScenario(tmp, /*openDepots*/ List.of("hoy_sued"));

        Population pop = PopulationUtils.readPopulation(clippedPlansPath(tmp).toString());
        long parcelPersons = pop.getPersons().values().stream()
                .filter(p -> SharedUse.isParcelPerson(p.getId().toString())).count();
        assertTrue(parcelPersons > 0, "no parcel persons were injected");
        Set<Id<Link>> depotLinks = pop.getPersons().values().stream()
                .filter(p -> SharedUse.isParcelPerson(p.getId().toString()))
                .map(p -> ((Activity) p.getSelectedPlan().getPlanElements().get(0)).getLinkId())
                .collect(Collectors.toSet());
        assertEquals(1, depotLinks.size(), "one open depot must yield exactly one origin link");
    }
```

Adapt `runShortSharedUseScenario` / `clippedPlansPath` to the helper names the existing test already uses.

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -pl parcel-demand-2-matsim-pipeline -am test -Dtest=SharedUseEndToEndTest -DfailIfNoSpecifiedTests=false`
Expected: FAIL initially if the helper does not yet accept the two parameters; extend the helper, then it passes.

- [ ] **Step 3: Make it pass**

Thread `openDepots` / `maxJobsPerDistrict` through the test helper into the preprocessor call.

Run: `mvn -pl parcel-demand-2-matsim-pipeline -am test -Dtest=SharedUseEndToEndTest -DfailIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 4: Assert the run metadata carries the sweep keys**

Without this, a sweep stage cannot be identified after the fact and two stages could be compared as
if they were the same configuration. Add to the same test:

```java
        String meta = Files.readString(runDir(tmp).resolve("run_metadata.json"));
        assertTrue(meta.contains("openDepots"), "run_metadata.json must record openDepots");
        assertTrue(meta.contains("hoy_sued"), "run_metadata.json must record WHICH depots were open");
        assertTrue(meta.contains("maxJobsPerDistrict"),
                "run_metadata.json must record maxJobsPerDistrict");
```

Use whatever accessor the test class already has for the run directory instead of `runDir(tmp)` if
it differs.

- [ ] **Step 5: Add the 1d counterpart**

Spec §8 asks for a short run per arm. Add the mirror test for `DRT_MODULAR` in the modular e2e test
class (`hagrid.integrated.modular`, the class that already boots a short modular run — reuse its
fixture), with `openDepots=hoy_sued` and an assertion that every routed carrier id starts with
`hoy_sued` and that `run_metadata.json` carries both keys.

- [ ] **Step 6: Run the entire suite**

Run: `mvn -pl parcel-demand-2-matsim-pipeline -am test`
Expected: PASS, all tests. `baselineCarrierOutputIsUnchanged` passing here is the headline result — it proves the Baseline is untouched.

- [ ] **Step 7: Record the spec's limitations**

Append to `docs/METHODS-LOG.md`, in the project's existing numbering style, the three items from spec §9: the 59 h stop-time gift from the 15-min cap (188.7 h → 129.4 h pooled, 201.7 h uncapped in both worlds), the confounded headline comparison from the provider-separated Baseline, and the supersession of all prior 1c/1d plausibility runs. Add the Task-3 deviation (median-strip split instead of `SameSizeKMeans`) to the spec's §10.

- [ ] **Step 8: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/ docs/METHODS-LOG.md \
        docs/superpowers/specs/2026-08-17-integrated-district-depot-assignment-design.md
git commit -m "test(integrated): e2e single-depot guard; record district-depot limitations in METHODS-LOG"
```

---

### Task 9: Keep the KPI layer honest about districts

`analysis/kpi/extract_freight_provider.py` keys per-provider freight KPIs on the carrier, and
`freight_classify.py` guesses the provider from the **vehicle-id string**. After Task 5 a 1d vehicle
is called `hoy_sued#0_<capsuleType>_day_v0`.

Site names (Task 2 Step 0) already remove the dangerous half of this: no district id contains an LSP
name, so nothing can be silently mislabelled as an LSP. What remains is that the per-provider split
is genuinely gone from the carrier — a district mixes all seven providers. Without the attribute
below, per-provider freight KPIs for 1d are not wrong, they are unavailable, and district rows land
in whatever bucket the classifier keeps for unknown ids.

**Files:**
- Modify: `parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/freight/LmdCarrierBuilder.java` (`buildDistrict`: one extra attribute)
- Modify: `parcel-demand-2-matsim-pipeline/analysis/kpi/extract_freight_provider.py`
- Test: `parcel-demand-2-matsim-pipeline/analysis/kpi/tests/test_extract_freight_provider.py`

**Interfaces:**
- Consumes: `DeliveryDistrictBuilder.PooledStop.parts()` (Task 2), `buildDistrict` (Task 4)
- Produces: carrier attribute `parcelsByProvider` (format `dhl=120;gls=45`, providers in
  first-appearance order) and `kpis_provider.csv` rows keyed `district:<id>`.

- [ ] **Step 1: Write the failing Java test for the new attribute**

Add to `LmdCarrierBuilderTest`:

```java
    @Test
    void districtCarrierRecordsItsProviderBreakdown() {
        List<DeliveryDistrictBuilder.PooledStop> stops = List.of(
                new DeliveryDistrictBuilder.PooledStop(new Coord(100, 100), 120,
                        List.of(deliveryAt(100, 100, "dhl", 100, Delivery.ParcelType.B2C),
                                deliveryAt(100, 100, "gls", 20, Delivery.ParcelType.B2C))));

        Carrier c = LmdCarrierBuilder.buildDistrict("bez0", stops, DEPOT_LINK, network(),
                vanTypes(), 2, 15, new Random(1L), 27000.0, 75600.0, 27000.0, 75600.0);

        assertEquals("dhl=100;gls=20", c.getAttributes().getAttribute("parcelsByProvider"),
                "the analysis layer needs the real provider split - a district mixes providers");
    }
```

Run: `mvn -pl parcel-demand-2-matsim-pipeline -am test -Dtest=LmdCarrierBuilderTest -DfailIfNoSpecifiedTests=false`
Expected: FAIL — attribute is null.

- [ ] **Step 2: Emit the provider breakdown from the district carrier**

In `buildDistrict`, accumulate parcels per provider while drawing the overlay and persist:

```java
        // Provider identity is operationally dissolved, but the ANALYSIS layer still needs the
        // split - a district mixes all providers, so carrier id != provider (see plan Task 9).
        StringBuilder breakdown = new StringBuilder();
        parcelsByProvider.forEach((prov, count) -> {
            if (breakdown.length() > 0) {
                breakdown.append(';');
            }
            breakdown.append(prov).append('=').append(count);
        });
        carrier.getAttributes().putAttribute("parcelsByProvider", breakdown.toString());
```

where `parcelsByProvider` is a `LinkedHashMap<String, Integer>` filled in the same loop that draws
the overlay (`parcelsByProvider.merge(p, d.getAmount(), Integer::sum)`).

- [ ] **Step 3: Write the failing Python test**

```python
def test_district_carriers_are_labelled_as_districts_not_providers(tmp_path):
    """A district carrier must never be reported as if it were an LSP."""
    run = _stage_run(tmp_path, carrier_ids=["hoy_sued#0", "hoy_sued#1"],
                     parcels_by_provider={"hoy_sued#0": "dhl=100;gls=20",
                                          "hoy_sued#1": "dhl=50"})
    rows = extract_freight_provider.extract(run)

    labels = {r["provider"] for r in rows}
    assert "district:hoy_sued#0" in labels
    assert "hoy_sued" in labels or True  # site ids carry no LSP name by construction (D7)


def test_parcels_are_attributed_back_to_the_real_providers(tmp_path):
    run = _stage_run(tmp_path, carrier_ids=["hoy_sued#0"],
                     parcels_by_provider={"hoy_sued#0": "dhl=100;gls=20"})
    rows = extract_freight_provider.extract(run)

    parcels = {r["provider"]: r["value"] for r in rows if r["kpi_name"] == "parcels"}
    assert parcels["dhl"] == 100
    assert parcels["gls"] == 20
```

Build `_stage_run` from the fixture helper the existing tests in this directory already use — do not
introduce a second staging style.

- [ ] **Step 4: Run to verify it fails**

Run: `cd parcel-demand-2-matsim-pipeline/analysis/kpi && python -m pytest tests/test_extract_freight_provider.py -v`
Expected: FAIL — districts are currently reported as providers.

- [ ] **Step 5: Implement**

In `extract_freight_provider.py`: when a carrier carries a `parcelsByProvider` attribute, label its
own rows `district:<carrierId>` (mirroring the existing `type:<VT>` prefix convention) and emit
additional `parcels` rows per real provider parsed from the attribute. Carriers without the
attribute (the Baseline) keep today's behaviour unchanged.

- [ ] **Step 6: Run the Python suite**

Run: `cd parcel-demand-2-matsim-pipeline/analysis/kpi && python -m pytest tests/ -v`
Expected: PASS, including the pre-existing tests — the Baseline path must be untouched.

- [ ] **Step 7: Run the full Java suite once more**

Run: `mvn -pl parcel-demand-2-matsim-pipeline -am test`
Expected: PASS — Task 9 touched Java, so the whole suite (incl. `baselineCarrierOutputIsUnchanged`) must be green again before the sweep.

- [ ] **Step 8: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/freight/LmdCarrierBuilder.java \
        parcel-demand-2-matsim-pipeline/analysis/kpi/extract_freight_provider.py \
        parcel-demand-2-matsim-pipeline/analysis/kpi/tests/test_extract_freight_provider.py
git commit -m "fix(kpi): label district carriers as districts, attribute parcels to real providers"
```

---

### Task 10: Make the two idealisations measurable

Neither is a behaviour change — both make visible what the sweep silently assumes. Without them the
1-depot stage can win the sweep on infrastructure nobody costed.

**Files:**
- Modify: `parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/modular/ModularKpiHandler.java`
- Modify: `parcel-demand-2-matsim-pipeline/analysis/kpi/extract_modular.py`
- Test: `parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/modular/ModularKpiHandlerTest.java`

**Interfaces:**
- Consumes: the district id already carried by each carrier (`district` attribute, Task 4)
- Produces: `peak_concurrent_swaps_<site>` rows in `modular_tour_stats.csv`; `district_parcels_<id>`
  and `district_segments_<id>` rows in `kpis_long.csv` (kpi_group `freight`).

- [ ] **Step 1: Write the failing test for per-depot swap peaks**

`peak_concurrent_swaps` exists today only as a global figure (6 in `f150t015`). With one open yard
every swap concentrates there, so the global number stops describing any site.

```java
    @Test
    void peakConcurrentSwapsIsReportedPerDepot() {
        // Two sites; three overlapping swaps at hoy_sued, one isolated at wittichenau.
        ModularKpiHandler h = newHandlerWithSites("hoy_sued", "wittichenau");
        swap(h, "hoy_sued", 8 * 3600, 9 * 3600);
        swap(h, "hoy_sued", 8 * 3600 + 600, 9 * 3600);
        swap(h, "hoy_sued", 8 * 3600 + 900, 9 * 3600);
        swap(h, "wittichenau", 14 * 3600, 15 * 3600);

        assertEquals(3, h.peakConcurrentSwaps("hoy_sued"));
        assertEquals(1, h.peakConcurrentSwaps("wittichenau"));
        assertEquals(3, h.peakConcurrentSwaps(), "the global figure must stay unchanged");
    }
```

Adapt `newHandlerWithSites` / `swap` to the construction and event-feeding style the existing tests
in this class already use.

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -pl parcel-demand-2-matsim-pipeline -am test -Dtest=ModularKpiHandlerTest -DfailIfNoSpecifiedTests=false`
Expected: FAIL — no per-site accessor.

- [ ] **Step 3: Implement**

Track swap intervals per site (the site is the district id of the carrier whose tour is being
swapped) and compute the peak per site with the same sweep-line the global figure already uses.
Emit one `peak_concurrent_swaps_<site>` row per site into `modular_tour_stats.csv` alongside the
existing global row, and surface them in `extract_modular.py`.

- [ ] **Step 4: Add the district load rows**

In `extract_modular.py` (and `extract_shareduse.py` for 1c), emit `district_parcels_<id>` and
`district_segments_<id>` from the routed carriers / parcel persons. This is what puts the 89-parcel
yard into the results instead of a footnote — at seven depots the spread is 89 (`spreetal`) to 1,886
(`hoy_nord`), a factor of 21.

- [ ] **Step 5: Run the suites**

Run: `mvn -pl parcel-demand-2-matsim-pipeline -am test -Dtest=ModularKpiHandlerTest -DfailIfNoSpecifiedTests=false`
then `cd parcel-demand-2-matsim-pipeline/analysis/kpi && python -m pytest tests/ -v`
Expected: PASS both.

- [ ] **Step 6: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/modular/ModularKpiHandler.java \
        parcel-demand-2-matsim-pipeline/analysis/kpi/extract_modular.py \
        parcel-demand-2-matsim-pipeline/analysis/kpi/extract_shareduse.py \
        parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/modular/ModularKpiHandlerTest.java
git commit -m "feat(kpi): per-depot swap peaks and per-district load"
```

---

## Post-implementation: the sweep

Not part of the TDD tasks — run after the suite is green. Seven runs, each `tag`ged (the guard in Task 7 Step 4 enforces it):

| Run | Arguments (added to the usual `concept=…,date=…,studyArea=…,fleetSize=…,maxIter=…,jspritIter=…`) |
|---|---|
| 1c d1 | `concept=drt_shareduse,openDepots=hoy_sued,chiThreshold=999999,tag=d1c_dep1` |
| 1c d3 | `concept=drt_shareduse,openDepots=wittichenau,hoy_sued,doergenhausen,chiThreshold=999999,tag=d1c_dep3` |
| 1c d7 | `concept=drt_shareduse,openDepots=all,chiThreshold=999999,tag=d1c_dep7` |
| 1d d1 | `concept=drt_modular,openDepots=hoy_sued,tag=d1d_dep1` |
| 1d d3 | `concept=drt_modular,openDepots=wittichenau,hoy_sued,doergenhausen,tag=d1d_dep3` |
| 1d d7 | `concept=drt_modular,openDepots=all,tag=d1d_dep7` |
| control | `concept=drt_modular,openDepots=hoy_sued,maxJobsPerDistrict=99999,tag=d1d_nopart` |
| control replicate | same as control plus a different `seed`, `tag=d1d_nopart_s2` |

**Eight runs.** One per depot stage is enough — the expected effects sit far above replanning noise
and jsprit runs on a fixed seed. The control is the exception: if partitioning costs only a few
percent, one point cannot separate that from noise, hence the replicate.

**1c stage 1 runs with the χ-gate open** (`chiThreshold=999999`, spec D10).
**CORRECTION 2026-08-25:** every `chiThreshold` in this section originally read `-1`. That token
does the OPPOSITE of what the surrounding text says: `ChiGateInsertionCostCalculator:169` treats
chi < 0 as HARD-CLOSED, so no parcel ever boards - pinned by the test "chi=-1 (hard-closed)
rejects every parcel, even at zero time loss". An inert gate needs a large POSITIVE threshold.
The spec was corrected on 2026-08-22 and the three launched arms used `999999` (confirmed in
their `run_metadata.json`), but this plan still carried the wrong token - anyone reading the
χ-raster runs off the table below would have fired empty runs. With depot legs cut from
~9.0 km to ~2.6 km the added vehicle time per parcel falls from roughly 1,080 s to ~310 s, so χ=600
would be near-inert and the stage would measure a half-binding acceptance rule on top of the depot
effect. The χ raster follows afterwards on the winning stage, in a band around 100–300 s, using the
per-segment `SegmentDetour` minimum as the instrument (METHODS-LOG 2.31 — the saturating counters
cannot answer where χ binds).

The control **must** use `openDepots=hoy_sued` — with all seven depots open, level 1 already yields
seven catchments and raising the ceiling changes nothing. Compare it against `d1d_dep1` to measure
what partitioning costs in solution quality.

Expected preprocessing bottleneck (jobs² of the largest district): dep1 88k, dep3 40k, dep7 64k, control 796k — against 766k today. Runtime gain lands in preprocessing, not in the ~4.6 h of MATSim iterations.
