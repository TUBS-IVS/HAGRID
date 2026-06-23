# Task 2 Report — LausitzDrtPreprocessor

## What was built

`LausitzDrtPreprocessor` (new class) with two public static methods:

```java
public static void run(String rawNetwork, String rawPlans, String serviceAreaShp,
                       String drtNetworkOut, String plansOut, String fleetOut,
                       int fleetSize, int capacity, double serviceBegin, double serviceEnd)

public static void run(HAGRIDSimulationConfig cfg) throws IOException
```

The core `run(...)` does:
1. Reads full network via `NetworkUtils.readNetwork`
2. Calls `PrepareNetwork.prepareDrtNetwork(net, serviceAreaShp)` — adds `drt` to car links inside the area, runs `MultimodalNetworkCleaner(drt)`, full network preserved
3. Writes drt-network via `new NetworkWriter(net).write(drtNetworkOut)`
4. Loads service-area geometry via `GeoUtils.getBoundaryGeometry(GeoFileReader.getAllFeatures(serviceAreaShp))`
5. Reads full population via `PopulationUtils.readPopulation`
6. Clips to area via `PopulationClipper.clip(pop, area)`; then removes non-`"person"` subpopulation agents via `PopulationUtils.getSubpopulation(person)`
7. Writes clipped population via `PopulationUtils.writePopulation`
8. Writes fleet via `DrtFleetGenerator.write(net, fleetSize, capacity, serviceBegin, serviceEnd, Path.of(fleetOut))`

The `run(HAGRIDSimulationConfig cfg)` overload calls `Files.createDirectories(cfg.getOutputDirectory())` then delegates to the core with the standard DRT params (capacity=8, window 0..86400).

## TDD RED→GREEN

### Step 1 — RED phase
```
mvn -pl parcel-demand-2-matsim-pipeline test -Dtest=LausitzDrtPreprocessorTest#producesDrtNetworkClippedPlansAndFleet
```
Result: **FAIL** — `LausitzDrtPreprocessor` did not exist (compilation failure + class-not-found).

During test setup iteration, the initial `writeSquareShapefile` using `ShapefileDataStoreFactory.addFeatures()` also failed with:
```
java.lang.NoSuchMethodError: 'org.locationtech.jts.geom.LinearRing org.locationtech.jts.geom.Polygon.getExteriorRing()'
```
See "Service-area shapefile in test" section below for how this was resolved.

### Step 2 — GREEN phase
After implementing the core `run(...)` and the config overload:
```
mvn -pl parcel-demand-2-matsim-pipeline test -Dtest=LausitzDrtPreprocessorTest#producesDrtNetworkClippedPlansAndFleet
```
Result: **BUILD SUCCESS — Tests run: 1, Failures: 0, Errors: 0**

### Step 3 — DRT package suite
```
mvn -pl parcel-demand-2-matsim-pipeline test -Dtest=LausitzDrtPreprocessorTest,LausitzDrtConfiguratorTest,DrtFleetGeneratorTest,DrtNetworkPreparerTest
```
Result: **BUILD SUCCESS — Tests run: 6, Failures: 0, Errors: 0**

## Service-area shapefile in the test

**Problem encountered:** The first implementation of `writeSquareShapefile` used `ShapefileDataStoreFactory` (GeoTools 31.1). Calling `store.addFeatures(features)` internally calls `Polygon.getExteriorRing()` which in JTS 1.16.1 (runtime on classpath, explicitly declared in pom) returns `LineString`, but GeoTools 31.1 was compiled expecting `LinearRing`. This is a pre-existing binary incompatibility in the project (GeoTools 31.1 needs JTS ≥1.18; runtime is JTS 1.16.1).

**Resolution:** Wrote the shapefile as raw binary (ESRI Shapefile format), bypassing GeoTools entirely for the write path. The raw writer produces a correct `.shp` + `.shx` + `.dbf` triple that `ShpOptions.getGeometry()` and `GeoFileReader.getAllFeatures()` read correctly (because the read path uses `ShapefileDataStore.getFeatures()` which builds `Polygon` via `GeometryFactory.createPolygon()` — it does NOT call `getExteriorRing()`).

**No production pom was changed.** The JTS version conflict pre-dates this task.

## Reuse of `PrepareNetwork.prepareDrtNetwork`

Used verbatim as specified. Signature confirmed via `javap`:
```
public static void prepareDrtNetwork(org.matsim.api.core.v01.network.Network, java.lang.String)
```
The method uses `ShpOptions(drtAreaShp, null, null).getGeometry()` internally, iterates links, adds `"drt"` to car links whose endpoint nodes are inside the geometry, then runs `MultimodalNetworkCleaner(drt)` on the same network. The full network is preserved (no links removed). The test fixture includes a rail link whose nodes are outside the service area — that link correctly does NOT get `drt`.

**Note:** The existing `DrtNetworkPreparer.prepare(Network, Geometry)` clips the network (removes non-area links). `LausitzDrtPreprocessor` does NOT use it — it uses the matsim-lausitz native helper as specified.

## Files changed

| File | Action |
|------|--------|
| `parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/drt/LausitzDrtPreprocessor.java` | Created |
| `parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/drt/LausitzDrtPreprocessorTest.java` | Created |

## Commit

```
git add parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/drt/LausitzDrtPreprocessor.java \
        parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/drt/LausitzDrtPreprocessorTest.java
git commit -m "feat(drt): LausitzDrtPreprocessor produces drt-network + clipped person plans + fleet"
```

SHA: (see commit below)

## Concerns

1. **JTS version conflict (pre-existing):** The project declares `jts-core:1.16.1` but GeoTools 31.1 was compiled against JTS ≥1.18. This is a latent binary incompatibility that only manifests when writing shapefiles via GeoTools (e.g. if production code ever writes a shapefile with a Polygon geometry, it will get `NoSuchMethodError`). Reading shapefiles works fine. Consider upgrading `jts-core` to `1.20.0` (jar is already in the local Maven repo) in a follow-up task.

2. **Fleet uses the full (drt-annotated) network for vehicle anchoring:** `DrtFleetGenerator.write` anchors vehicles on all network links (sorted by ID, round-robin). With the full network, this includes the rail link outside the service area. In production the Lausitz network will only have car links in the service area tagged with `drt`, and the `MultimodalNetworkCleaner` ensures the drt subgraph is connected — but vehicles may be anchored on non-drt links. This matches the existing `DrtFleetGenerator` contract (it uses all links in the passed network). If only DRT-capable links should host vehicles, the call site should filter the network — out of scope for this task.
