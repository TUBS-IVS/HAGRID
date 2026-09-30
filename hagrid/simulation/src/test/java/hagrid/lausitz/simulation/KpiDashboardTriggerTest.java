package hagrid.lausitz.simulation;

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
