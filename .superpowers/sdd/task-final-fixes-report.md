# Final Fixes Report — Lausitz Dedicated LMD Baseline Branch Review

**Date:** 2026-06-25  **Branch:** hendrik  **Base commit:** 965e1e5

---

## Fix 1 — DECISION (b): SPEC update for DRT studyArea defaulting

**File:** `docs/superpowers/specs/2026-06-25-lausitz-dedicated-lmd-baseline-design.md`  
**Change:** Decision 4 revised to clarify that the `DRT_*` enum values + their pipeline are structurally unchanged, but `parseScenario` now derives + validates `StudyArea=LAUSITZ_HOYERSWERDA` for ALL Lausitz-bound scenarios (both `DRT_*` and `LMD_BASELINE`) when `studyArea` is omitted; passing `studyArea=HANNOVER` still throws. The old text said `DRT_*` family is "unchanged" (contradicting the unified `requiresLausitz()` defaulting behavior). No code changed.

---

## Fix 2 — Consolidate maxIterations==0 invariant in HAGRIDSimulationConfig

**File:** `parcel-demand-2-matsim-pipeline/src/main/java/hagrid/simulation/HAGRIDSimulationConfig.java`

**Constructor guard added** (10-arg constructor, after the `maxIterations < 0` check):
```java
if (maxIterations == 0) {
    boolean isLmd;
    try {
        isLmd = HagridConfig.Scenario.valueOf(concept.toUpperCase())
                == HagridConfig.Scenario.LMD_BASELINE;
    } catch (IllegalArgumentException ex) {
        isLmd = false;
    }
    if (!isLmd) {
        throw new IllegalArgumentException(
                "maxIterations=0 is only valid for LMD_BASELINE; concept '"
                        + concept + "' requires maxIterations > 0");
    }
}
```
Uses the same swallow-unknown `valueOf` pattern already used by `isDrtScenario()`/`isLmdBaseline()`.

**Behavior:**
- `LMD_BASELINE` with `maxIterations=0` — allowed (passes the guard)
- Any other known concept (e.g. `BASECASE`, `DRT_BASELINE`) with `maxIterations=0` — throws `IllegalArgumentException`
- Unknown concept string with `maxIterations=0` — also throws (unknown → `isLmd=false`)
- `parseScenario` keeps its friendlier early check (defense in depth, user-facing error messages); the constructor guard is the hard invariant backstop

**@throws Javadoc** updated on BOTH constructors:
- Short (8-arg) constructor: `@throws IllegalArgumentException if maxIterations < 0; if maxIterations == 0 and the concept is not LMD_BASELINE; or if jspritIterations is not positive`
- Full (10-arg) constructor: same wording

---

## Fix 3 — PROVIDERS divergence guard test

**File:** `parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/freight/LmdDemandReaderTest.java`

Added test `providersAreConsistent()`:
```java
@Test
@DisplayName("LmdDemandReader.PROVIDERS and LmdDepotLoader.PROVIDERS are identical sets")
void providersAreConsistent() {
    assertThat(Arrays.asList(LmdDemandReader.PROVIDERS))
            .containsExactlyInAnyOrderElementsOf(LmdDepotLoader.PROVIDERS);
}
```
Also added `import java.util.Arrays;`. The constants themselves were not refactored (kept low-risk). The test catches any future divergence between:
- `LmdDepotLoader.PROVIDERS` — `public static final Set<String>` of 7 LSPs
- `LmdDemandReader.PROVIDERS` — `static final String[]` of 7 LSPs (package-private)

---

## Fix 4 — LmdPathsTest assertion rigor for lmdCarriersRouted()

**File:** `parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/freight/LmdPathsTest.java`

**Before:**
```java
assertThat(paths.lmdCarriersRouted()).contains("LMD_BASELINE_13052025")
        .endsWith("lmd_carriers_routed.xml");
```
**After:**
```java
assertThat(paths.lmdCarriersRouted())
        .endsWith(Path.of("LMD_BASELINE_13052025", "carriers",
                "LMD_BASELINE_13052025_lmd_carriers_routed.xml").toString());
```
The actual path resolves to `…/hagrid-output/LMD_BASELINE_13052025/carriers/LMD_BASELINE_13052025_lmd_carriers_routed.xml`. The new assertion verifies run-id prefix in the directory segment AND the `carriers/` subdirectory AND the full filename together, matching the `endsWith(Path.of(...).toString())` style used by the other getters.

---

## Fix 5 — Import ordering in SimulationRunnerUtils.java

**File:** `parcel-demand-2-matsim-pipeline/src/main/java/hagrid/simulation/SimulationRunnerUtils.java`

Reordered 6 LMD-branch `org.matsim.*` imports added out of alphabetical position. Correct order applied:
```
org.matsim.api.core.v01.Scenario
org.matsim.api.core.v01.network.Network
org.matsim.core.api.experimental.events.EventsManager
org.matsim.core.config.Config               ← moved up (was after GeoFileReader)
org.matsim.core.config.ConfigUtils          ← moved up
org.matsim.core.controler.Controler
org.matsim.core.controler.OutputDirectoryHierarchy  ← moved up (was after ScenarioUtils)
org.matsim.core.events.EventsUtils
org.matsim.core.events.MatsimEventsReader
org.matsim.core.network.NetworkUtils
org.matsim.core.network.io.MatsimNetworkReader
org.matsim.core.scenario.ScenarioUtils      ← moved up (was after GeoFileReader)
org.matsim.core.utils.gis.GeoFileReader
org.matsim.freight.carriers.CarriersUtils
org.matsim.freight.carriers.FreightCarriersConfigGroup
org.matsim.freight.carriers.controller.CarrierModule
```
Pure cosmetic; compilation unaffected.

---

## Test results

### Targeted test run
Command: `mvn -pl parcel-demand-2-matsim-pipeline test -Dtest=ParseScenarioLmdTest,LmdRunBranchTest,LmdDemandReaderTest,LmdPathsTest,ScenarioParsingTest`

| Test class | Run | Pass | Fail |
|---|---|---|---|
| LmdDemandReaderTest | 2 | 2 | 0 |
| LmdPathsTest | 1 | 1 | 0 |
| LmdRunBranchTest | 2 | 2 | 0 |
| ParseScenarioLmdTest | 4 | 4 | 0 |
| ScenarioParsingTest | 4 | 4 | 0 |
| **Total** | **13** | **13** | **0** |

Key cases verified:
- `lmdMaxIterZeroAllowed` — LMD with maxIter=0 passes parseScenario AND the new constructor guard
- `basecaseMaxIterZeroRejected` — BASECASE with maxIter=0 throws at parseScenario (defense-in-depth; constructor guard also fires if reached)
- `providersAreConsistent` — both PROVIDERS constants are identical 7-element sets
- `lmdGettersResolve` — `lmdCarriersRouted()` endsWith the full `runId/carriers/runId_lmd_carriers_routed.xml` path

### Full module suite
Command: `mvn -pl parcel-demand-2-matsim-pipeline test` (-Xmx8g)

**234 tests run, 0 failures, 0 errors, 0 skipped. BUILD SUCCESS. (3:01 min)**

No regression in any pre-existing test.

---

## Concerns

None. All invariants are self-consistent. The constructor guard is triggered slightly later than `parseScenario`'s check (which is intentional — defense in depth); in normal usage `parseScenario` catches the error with a friendlier message first.
