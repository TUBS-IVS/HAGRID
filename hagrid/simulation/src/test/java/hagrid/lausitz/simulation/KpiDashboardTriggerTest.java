package hagrid.lausitz.simulation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("KpiDashboardTrigger")
class KpiDashboardTriggerTest {

    private static String javaBin() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    @Test
    @DisplayName("buildCommand produces python -X faulthandler -u <script> --run-dir <dir>")
    void buildCommandShape() {
        List<String> cmd = KpiDashboardTrigger.buildCommand(
                Path.of("analysis", "kpi", "build_kpis.py"), Path.of("out", "run1"));
        assertThat(cmd).hasSize(7);
        assertThat(cmd.subList(0, 4)).containsExactly("python", "-X", "faulthandler", "-u");
        assertThat(cmd.get(4)).endsWith("build_kpis.py");
        assertThat(cmd.get(5)).isEqualTo("--run-dir");
        assertThat(cmd.get(6)).endsWith("run1");
    }

    // --- hardening 2026-11-02 ------------------------------------------------------------------

    @Test
    @DisplayName("runOnce returns the real exit code of a failing build")
    void runOnceReturnsTheExitCode() {
        int code = KpiDashboardTrigger.runOnce(List.of(javaBin(), "-cp", ".", "NoSuchMainClass_xyz"), null, 5,
                line -> { });
        assertThat(code).isEqualTo(1);
    }

    @Test
    @DisplayName("runOnce reports a missing executable as NOT_STARTED, not as an exit code")
    void runOnceMissingExecutable() {
        assertThat(KpiDashboardTrigger.runOnce(List.of("definitely-not-a-real-exe-xyz-42"), null, 1, line -> { }))
                .isEqualTo(KpiDashboardTrigger.NOT_STARTED);
    }

    @Test
    @DisplayName("runOnce hands over every output line before it returns (reader joined)")
    void runOnceDeliversTheLastLines() {
        List<String> lines = Collections.synchronizedList(new ArrayList<>());
        int code = KpiDashboardTrigger.runOnce(List.of(javaBin(), "-XshowSettings:properties", "-version"), null, 5,
                line -> {
                    try {
                        Thread.sleep(2);   // a slow sink: without the join the method returns first
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    lines.add(line);
                });
        assertThat(code).isZero();
        assertThat(lines).hasSizeGreaterThan(30);
        assertThat(lines.get(lines.size() - 1)).as("the last line of java -version").containsIgnoringCase("vm");
    }

    @Test
    @DisplayName("runWithRetry tries a non-zero exit once more and succeeds on the second attempt")
    void aCrashIsRetriedOnce() {
        AtomicInteger calls = new AtomicInteger();
        boolean ok = KpiDashboardTrigger.runWithRetry(() -> calls.incrementAndGet() == 1 ? -1073741819 : 0, 2);
        assertThat(ok).isTrue();
        assertThat(calls).hasValue(2);
    }

    @Test
    @DisplayName("runWithRetry gives up after the second failure")
    void twoFailuresGiveUp() {
        AtomicInteger calls = new AtomicInteger();
        assertThat(KpiDashboardTrigger.runWithRetry(() -> { calls.incrementAndGet(); return 1; }, 2)).isFalse();
        assertThat(calls).hasValue(2);
    }

    @Test
    @DisplayName("runWithRetry does not retry a timeout or a build that cannot start")
    void timeoutAndNotStartedAreNotRetried() {
        for (int code : new int[] {KpiDashboardTrigger.TIMED_OUT, KpiDashboardTrigger.NOT_STARTED}) {
            AtomicInteger calls = new AtomicInteger();
            assertThat(KpiDashboardTrigger.runWithRetry(() -> { calls.incrementAndGet(); return code; }, 2)).isFalse();
            assertThat(calls).hasValue(1);
        }
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

    @Test
    @DisplayName("scriptFor: absolute module root two levels below the repo -> <repo>/analysis/lausitz/kpi/build_kpis.py")
    void scriptForAbsoluteRoot(@org.junit.jupiter.api.io.TempDir Path repo) {
        Path module = repo.resolve("hagrid").resolve("simulation");
        Path expected = repo.resolve("analysis").resolve("lausitz").resolve("kpi").resolve("build_kpis.py");
        assertThat(KpiDashboardTrigger.scriptFor(module)).isEqualTo(expected);
    }

    @Test
    @DisplayName("scriptFor: relative root 'hagrid/simulation' resolves against the CWD and finds the real repo by its marker")
    void scriptForRelativeRootFromRepo() {
        // Until 2026-09-28 this test expected cwd/analysis/... -- the bare two-levels-up
        // arithmetic, which under Surefire (CWD = module dir) is a path that does not exist.
        // The marker walk now climbs on to the directory that really holds build_kpis.py. In
        // the IDE case it was written for (CWD = repo root) old and new answer are identical.
        Path moduleDir = Path.of("").toAbsolutePath().normalize();
        Path expected = moduleDir.getParent().getParent()
                .resolve("analysis").resolve("lausitz").resolve("kpi").resolve("build_kpis.py");
        assertThat(KpiDashboardTrigger.scriptFor(Path.of("hagrid", "simulation"))).isEqualTo(expected);
        assertThat(java.nio.file.Files.exists(expected)).isTrue();
    }

    @Test
    @DisplayName("scriptFor: a module THREE levels below the repo is found by the marker, not by counting levels")
    void scriptForFindsRepoByMarkerAtAnyDepth(@org.junit.jupiter.api.io.TempDir Path repo)
            throws java.io.IOException {
        Path script = repo.resolve("analysis").resolve("lausitz").resolve("kpi").resolve("build_kpis.py");
        java.nio.file.Files.createDirectories(script.getParent());
        java.nio.file.Files.writeString(script, "# marker");
        Path module = repo.resolve("studies").resolve("hagrid").resolve("simulation");
        assertThat(KpiDashboardTrigger.scriptFor(module)).isEqualTo(script);
    }

    @Test
    @DisplayName("scriptFor: relative root '.' (the bat case, CWD = module dir) climbs two levels")
    void scriptForDotRootFromModule() {
        // Unter Surefire ist das CWD der Modulordner hagrid/simulation; zwei Ebenen hoeher liegt die Repo-Wurzel.
        Path cwd = Path.of("").toAbsolutePath().normalize();
        Path expected = cwd.getParent().getParent().resolve("analysis").resolve("lausitz").resolve("kpi").resolve("build_kpis.py");
        assertThat(KpiDashboardTrigger.scriptFor(Path.of("."))).isEqualTo(expected);
        assertThat(java.nio.file.Files.exists(expected)).as("the real build_kpis.py is where scriptFor points").isTrue();
    }
}
