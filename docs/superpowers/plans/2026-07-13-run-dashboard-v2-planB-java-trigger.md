# Run-Dashboard v2 — Plan B: Java Auto-Trigger — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** After every MATSim run, Java invokes `analysis/kpi/build_kpis.py` as a blocking, failure-tolerant subprocess, controlled by a new scenario option `kpiDashboard` (default **true**).

**Architecture:** A new boolean option `kpiDashboard` is parsed in `SimulationRunnerUtils.parseScenario` and threaded through `HAGRIDSimulationConfig` (mirroring `drtWithFreight` — the legacy `writeDashboard` `boolean[]`-at-main pattern CANNOT reach the trigger point inside `runSimulation`). A new `KpiDashboardTrigger` class owns command construction + ProcessBuilder execution (net-new — there is no ProcessBuilder anywhere in the repo). It is called once after each of the 3 `writeRunMetadataSafely(cfg)` call sites.

**Tech Stack:** Java 21, JUnit Jupiter 5.11.4 + AssertJ 3.27.7 (no Mockito — not a pipeline dependency), log4j2 Logger (same as `SimulationRunnerUtils`).

## Global Constraints

- Spec §3.4: subprocess is **synchronous/blocking** (the run is not "done" until the dashboard build finishes or times out), timeout **30 min**, `try/catch`-all — a dashboard failure must NEVER kill or fail a run; on any failure WARN with the exact manual command.
- Python resolution: literal `python` from `PATH`; spawn failure → WARN + manual command, run continues.
- Command: `python -u <pipelineRoot>/analysis/kpi/build_kpis.py --run-dir <absRunDir>` (full build, no `--no-events`).
- Legacy `writeDashboard` (Java dashboard) stays untouched, default false, deprecated.
- `SimulationBatGenerator` needs **no change** (verified: it never emits `kpiDashboard`; `parseScenario`'s `getOrDefault` supplies the true default).
- No `.gitignore` changes; explicit `git add` lists; branch `hendrik`; no master merge.
- Java tests run with: `mvn -pl parcel-demand-2-matsim-pipeline test -Dtest=<ClassName>` from the repo root.

## File Structure

- Modify: `parcel-demand-2-matsim-pipeline/src/main/java/hagrid/simulation/SimulationRunnerUtils.java` (parse + 3 trigger calls)
- Modify: the file defining `HAGRIDSimulationConfig` (same package usage as `SimulationRunnerUtils`; 3-constructor chain at lines ~117/139/164) — new field/getter/ctor param
- Create: `parcel-demand-2-matsim-pipeline/src/main/java/hagrid/simulation/KpiDashboardTrigger.java`
- Test: `parcel-demand-2-matsim-pipeline/src/test/java/hagrid/simulation/ParseScenarioKpiDashboardTest.java`
- Test: `parcel-demand-2-matsim-pipeline/src/test/java/hagrid/simulation/KpiDashboardTriggerTest.java`
- Modify tests: `LmdBaselineEndToEndTest.java`, `MarriedBaselineEndToEndTest.java`, `DrtBaselineEndToEndTest.java` (hermetic-ize / assert)

## Reference facts (verified 2026-07-13 against the code)

- `writeRunMetadataSafely(cfg)` call sites in `SimulationRunnerUtils.runSimulation`: **line ~274** (DRT/married branch, followed by `return;`), **line ~305** (LMD branch, followed by `return;`), **line ~334** (Hannover freight fall-through, followed by `System.gc()`).
- Option parsing precedent (`parseScenario`, ~line 139): `boolean drtWithFreight = bool(map.getOrDefault("freight", "true"), "freight");` — `bool(...)` helper exists at ~line 492 (accepts true/1/yes|false/0/no, throws otherwise).
- `parseScenario` ends with the 11-arg canonical `HAGRIDSimulationConfig` constructor call (~line 177).
- Output dir at the call sites: `cfg.getOutputDirectory()` → `<pipelineRoot>/hagrid-matsim-output/<runId>_iter<N>_jsprit<M>`.
- Pipeline root at runtime: `new HagridPaths().getPipelineRoot()` (same pattern as `SimulationBatGenerator.writeBatFile`); CWD is the pipeline module root (enforced by `.bat` `cd /d "%~dp0"` + `-Dhagrid.pipeline.root=.`), but the trigger must not rely on that — normalize to absolute paths and set `ProcessBuilder.directory(...)`.

---

## Task 1: Parse `kpiDashboard` + thread through `HAGRIDSimulationConfig`

**Files:**
- Modify: `.../hagrid/simulation/SimulationRunnerUtils.java` (parseScenario)
- Modify: `HAGRIDSimulationConfig.java` (find via the import in `SimulationRunnerUtils.java`)
- Test: `.../src/test/java/hagrid/simulation/ParseScenarioKpiDashboardTest.java`

**Interfaces:**
- Produces: `HAGRIDSimulationConfig.isKpiDashboardEnabled() -> boolean` (consumed by Task 2/3). New 12-arg canonical constructor; the existing 11-arg overload delegates with `kpiDashboard=true` so no existing caller breaks.

- [ ] **Step 1: Write the failing test**

```java
package hagrid.simulation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("parseScenario: kpiDashboard option")
class ParseScenarioKpiDashboardTest {

    @Test
    @DisplayName("kpiDashboard defaults to true when absent")
    void defaultsTrue() {
        HAGRIDSimulationConfig cfg = SimulationRunnerUtils.parseScenario(
                "concept=basecase,date=2025-05-13");
        assertThat(cfg.isKpiDashboardEnabled()).isTrue();
    }

    @Test
    @DisplayName("kpiDashboard=false disables the trigger")
    void explicitFalse() {
        HAGRIDSimulationConfig cfg = SimulationRunnerUtils.parseScenario(
                "concept=basecase,date=2025-05-13,kpiDashboard=false");
        assertThat(cfg.isKpiDashboardEnabled()).isFalse();
    }

    @Test
    @DisplayName("invalid kpiDashboard value throws")
    void invalidThrows() {
        assertThatThrownBy(() -> SimulationRunnerUtils.parseScenario(
                "concept=basecase,date=2025-05-13,kpiDashboard=maybe"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("kpiDashboard");
    }
}
```
(If `parseScenario("concept=basecase,date=2025-05-13")` needs more mandatory keys, copy the minimal spec string from `ScenarioParsingTest.defaultHannover` verbatim.)

- [ ] **Step 2: Run, verify fail** — `mvn -pl parcel-demand-2-matsim-pipeline test -Dtest=ParseScenarioKpiDashboardTest` → compile error (`isKpiDashboardEnabled` undefined).

- [ ] **Step 3: Implement.**
  1. In `HAGRIDSimulationConfig`: add `private final boolean kpiDashboard;` next to `drtWithFreight` (~line 99). Extend the canonical constructor (~line 164) with a 12th param `boolean kpiDashboard` and assign it. Keep the existing 11-arg constructor as an overload delegating with `true` (so tests/other callers stay source-compatible). Add:
```java
    /** Scenario option {@code kpiDashboard} (default true): build the Python KPI dashboard after the run. */
    public boolean isKpiDashboardEnabled() {
        return kpiDashboard;
    }
```
  2. In `SimulationRunnerUtils.parseScenario`, next to the `freight` line (~139):
```java
        boolean kpiDashboard = bool(map.getOrDefault("kpiDashboard", "true"), "kpiDashboard");
```
  Note: `bool(...)` throws `Invalid boolean for kpiDashboard: maybe` — but the test asserts `hasMessageContaining("kpiDashboard")`, which that message satisfies. Then append `kpiDashboard` to the constructor call (~line 177), switching it to the 12-arg canonical.

- [ ] **Step 4: Run, verify pass** (also run `ScenarioParsingTest`, `ParseScenarioDrtFreightTest`, `ParseScenarioLmdTest`, `HAGRIDSimulationConfigTest` — must stay green).

- [ ] **Step 5: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/src/main/java/hagrid/simulation/SimulationRunnerUtils.java \
        parcel-demand-2-matsim-pipeline/src/main/java/hagrid/simulation/HAGRIDSimulationConfig.java \
        parcel-demand-2-matsim-pipeline/src/test/java/hagrid/simulation/ParseScenarioKpiDashboardTest.java
git commit -m "feat(sim): kpiDashboard scenario option, default true (v2 Plan B Task 1)"
```
(Adjust the config file path if `HAGRIDSimulationConfig.java` lives elsewhere — use the actual path found in Step 3.)

---

## Task 2: `KpiDashboardTrigger` (command build + failure-tolerant subprocess)

**Files:**
- Create: `parcel-demand-2-matsim-pipeline/src/main/java/hagrid/simulation/KpiDashboardTrigger.java`
- Test: `parcel-demand-2-matsim-pipeline/src/test/java/hagrid/simulation/KpiDashboardTriggerTest.java`

**Interfaces:**
- Produces:
  - `static List<String> buildCommand(Path script, Path runDir)` (pure)
  - `static boolean runProcess(List<String> command, Path workDir, long timeoutMinutes)` (spawn + stream + wait; false on nonzero exit / timeout / spawn failure; never throws)
  - `public static void triggerSafely(HAGRIDSimulationConfig cfg)` (guard on `isKpiDashboardEnabled()`, resolve paths, WARN with manual command on failure; never throws) — consumed by Task 3.

- [ ] **Step 1: Write the failing tests**

```java
package hagrid.simulation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("KpiDashboardTrigger")
class KpiDashboardTriggerTest {

    private static String javaBin() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    @Test
    @DisplayName("buildCommand produces python -u <script> --run-dir <dir>")
    void buildCommandShape() {
        List<String> cmd = KpiDashboardTrigger.buildCommand(
                Path.of("analysis", "kpi", "build_kpis.py"), Path.of("out", "run1"));
        assertThat(cmd).hasSize(5);
        assertThat(cmd.get(0)).isEqualTo("python");
        assertThat(cmd.get(1)).isEqualTo("-u");
        assertThat(cmd.get(2)).endsWith("build_kpis.py");
        assertThat(cmd.get(3)).isEqualTo("--run-dir");
        assertThat(cmd.get(4)).endsWith("run1");
    }

    @Test
    @DisplayName("runProcess returns true on exit 0")
    void runProcessSuccess() {
        assertThat(KpiDashboardTrigger.runProcess(
                List.of(javaBin(), "-version"), null, 5)).isTrue();
    }

    @Test
    @DisplayName("runProcess returns false (no throw) when the executable does not exist")
    void runProcessMissingExecutable() {
        assertThat(KpiDashboardTrigger.runProcess(
                List.of("definitely-not-a-real-exe-xyz-42"), null, 1)).isFalse();
    }

    @Test
    @DisplayName("runProcess returns false on nonzero exit")
    void runProcessNonZeroExit() {
        assertThat(KpiDashboardTrigger.runProcess(
                List.of(javaBin(), "-cp", ".", "NoSuchMainClass_xyz"), null, 5)).isFalse();
    }
}
```

- [ ] **Step 2: Run, verify fail** (class missing).

- [ ] **Step 3: Implement `KpiDashboardTrigger.java`** (copy the Logger import/declaration style from the top of `SimulationRunnerUtils.java`; import `HagridPaths` exactly as `SimulationBatGenerator.java` does):

```java
package hagrid.simulation;

// Logger imports: copy verbatim from SimulationRunnerUtils.java
// HagridPaths import: copy verbatim from SimulationBatGenerator.java

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Invokes the Python KPI-dashboard builder ({@code analysis/kpi/build_kpis.py}) for a finished
 * run. Blocking by design (spec 3.4: the run is not "done" until the dashboard exists or the
 * build timed out) and failure-tolerant by design: a dashboard problem must never kill or fail
 * a multi-hour MATSim run.
 */
public final class KpiDashboardTrigger {

    private static final Logger LOG = LogManager.getLogger(KpiDashboardTrigger.class);
    static final long TIMEOUT_MINUTES = 30;

    private KpiDashboardTrigger() {
    }

    static List<String> buildCommand(Path script, Path runDir) {
        return List.of("python", "-u", script.toString(), "--run-dir", runDir.toString());
    }

    static boolean runProcess(List<String> command, Path workDir, long timeoutMinutes) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            if (workDir != null) {
                pb.directory(workDir.toFile());
            }
            Process p = pb.start();
            Thread reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        LOG.info("[kpi-dashboard] {}", line);
                    }
                } catch (IOException ignored) {
                    // stream closes when the process ends or is destroyed
                }
            }, "kpi-dashboard-log");
            reader.setDaemon(true);
            reader.start();
            if (!p.waitFor(timeoutMinutes, TimeUnit.MINUTES)) {
                p.destroyForcibly();
                LOG.warn("KPI dashboard build timed out after {} min", timeoutMinutes);
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            LOG.warn("Could not start the KPI dashboard build: {}", e.toString());
            return false;
        }
    }

    /** Never throws. No-op when the scenario option {@code kpiDashboard} is false. */
    public static void triggerSafely(HAGRIDSimulationConfig cfg) {
        try {
            if (!cfg.isKpiDashboardEnabled()) {
                return;
            }
            Path pipelineRoot = new HagridPaths().getPipelineRoot().toAbsolutePath().normalize();
            Path script = pipelineRoot.resolve("analysis").resolve("kpi").resolve("build_kpis.py");
            Path runDir = cfg.getOutputDirectory().toAbsolutePath().normalize();
            List<String> cmd = buildCommand(script, runDir);
            LOG.info("kpiDashboard=true -> building KPI dashboard: {}", String.join(" ", cmd));
            long t0 = System.currentTimeMillis();
            boolean ok = runProcess(cmd, pipelineRoot, TIMEOUT_MINUTES);
            if (ok) {
                LOG.info("KPI dashboard written to {} ({} s)", runDir.resolve("analysis"),
                        (System.currentTimeMillis() - t0) / 1000);
            } else {
                LOG.warn("KPI dashboard build FAILED - run it manually:\n  python -u {} --run-dir {}",
                        script, runDir);
            }
        } catch (Exception e) {
            LOG.warn("KPI dashboard trigger failed - run manually: python -u "
                    + "<pipeline>/analysis/kpi/build_kpis.py --run-dir {} ({})",
                    cfg.getOutputDirectoryAsString(), e.toString());
        }
    }
}
```
(If `HagridPaths`'s no-arg constructor is unavailable/deprecated, use exactly the construction `SimulationBatGenerator.writeBatFile` uses — that is the proven runtime pattern.)

- [ ] **Step 4: Run, verify pass.**

- [ ] **Step 5: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/src/main/java/hagrid/simulation/KpiDashboardTrigger.java \
        parcel-demand-2-matsim-pipeline/src/test/java/hagrid/simulation/KpiDashboardTriggerTest.java
git commit -m "feat(sim): failure-tolerant Python dashboard subprocess trigger (v2 Plan B Task 2)"
```

---

## Task 3: Wire the 3 run-exit call sites + e2e proof

**Files:**
- Modify: `.../hagrid/simulation/SimulationRunnerUtils.java` (3 one-liners)
- Modify: `.../src/test/java/hagrid/integrated/freight/LmdBaselineEndToEndTest.java`
- Modify: `.../src/test/java/hagrid/integrated/drt/MarriedBaselineEndToEndTest.java`
- Modify: `.../src/test/java/hagrid/integrated/drt/DrtBaselineEndToEndTest.java`

**Interfaces:**
- Consumes: `KpiDashboardTrigger.triggerSafely(cfg)` (Task 2), `cfg.isKpiDashboardEnabled()` (Task 1).

- [x] **Step 1: Hermetic-ize the DRT e2e tests.** Read `MarriedBaselineEndToEndTest` and `DrtBaselineEndToEndTest`: wherever they build their config (scenario spec string → append `,kpiDashboard=false`; direct constructor → use the 12-arg canonical with `kpiDashboard=false`). Rationale: these run real MATSim; the Python build would add runtime + a python dependency without adding assertion value there.
  > **Execution note (2026-07-15):** Only `MarriedBaselineEndToEndTest` needed a change (direct 12-arg ctor → `kpiDashboard=false`, cosmetic — it builds a `Controler` directly and never calls `runSimulation`, so the trigger never fires there anyway). `DrtBaselineEndToEndTest` was left **unchanged**: it builds a `Controler` directly and constructs **no** `HAGRIDSimulationConfig`, so there was nothing to hermetic-ize. Net: only `LmdBaselineEndToEndTest` exercises the real trigger (it alone calls `SimulationRunnerUtils.runSimulation`).

- [ ] **Step 2: Extend `LmdBaselineEndToEndTest` as the positive proof.** Keep its config at the default (`kpiDashboard` absent → true). After the existing `run_metadata.json` assertion, add:

```java
        // v2 Plan B: the auto-trigger must have produced the Python KPI dashboard.
        // Skip (not fail) on machines without python on PATH — the trigger itself is
        // failure-tolerant, so only the assertion needs the guard.
        boolean pythonAvailable = KpiDashboardTrigger.runProcess(
                java.util.List.of("python", "--version"), null, 1);
        org.junit.jupiter.api.Assumptions.assumeTrue(pythonAvailable, "python not on PATH");
        assertThat(java.nio.file.Files.exists(
                cfg.getOutputDirectory().resolve("analysis").resolve("kpi_dashboard.html")))
                .as("kpiDashboard=true (default) must produce analysis/kpi_dashboard.html")
                .isTrue();
```

- [ ] **Step 3: Run the e2e, verify the new assertion FAILS** (trigger not wired yet): `mvn -pl parcel-demand-2-matsim-pipeline test -Dtest=LmdBaselineEndToEndTest`.

- [ ] **Step 4: Wire the trigger.** In `SimulationRunnerUtils.runSimulation`, add `KpiDashboardTrigger.triggerSafely(cfg);` immediately after EACH of the 3 `writeRunMetadataSafely(cfg);` lines (~274, ~305, ~334 — the first two before their `return;`, the third before the `System.gc()` block).

- [ ] **Step 5: Run, verify pass** — `LmdBaselineEndToEndTest` green (dashboard html exists), then the full module test suite: `mvn -pl parcel-demand-2-matsim-pipeline test`. Expected: all green; the married/drt e2e run WITHOUT spawning python (check their logs contain no `[kpi-dashboard]` lines).

- [ ] **Step 6: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/src/main/java/hagrid/simulation/SimulationRunnerUtils.java \
        parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/freight/LmdBaselineEndToEndTest.java \
        parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/drt/MarriedBaselineEndToEndTest.java \
        parcel-demand-2-matsim-pipeline/src/test/java/hagrid/integrated/drt/DrtBaselineEndToEndTest.java
git commit -m "feat(sim): auto-build KPI dashboard at all 3 run exits (v2 Plan B Task 3)"
```

---

## Self-Review (against spec §3.4)

- New option `kpiDashboard`, default true → Task 1 (parse + config threading; the spec's "SimulationRunnerUtils" placement forced the config-field approach — the legacy `extractDashboardFlags` pattern cannot reach `runSimulation`). ✔
- Trigger immediately after `writeRunMetadataSafely(...)` at all 3 exits → Task 3. ✔
- try/catch-all, 30-min timeout, stdout/stderr → run log (`[kpi-dashboard]` prefix via log4j2), WARN + exact manual command on any failure, `python` from PATH, spawn-fail tolerated → Task 2. ✔
- Blocking/synchronous (spec-confirmed) → `runProcess` waits; operational consequence (machine must not sleep until the build ends) already captured in `feedback_drt_runs_operational`. ✔
- `SimulationBatGenerator` unchanged (verified against its source). ✔
- Legacy `writeDashboard` untouched. ✔
- Type consistency: `isKpiDashboardEnabled()` used in Task 2/3 matches Task 1's getter; `triggerSafely(cfg)` matches Task 3's call. ✔
