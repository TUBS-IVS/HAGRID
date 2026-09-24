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
    @DisplayName("scriptFor: relative root 'hagrid/simulation' resolves against the CWD (IDE case)")
    void scriptForRelativeRootFromRepo() {
        Path cwd = Path.of("").toAbsolutePath().normalize();
        assertThat(KpiDashboardTrigger.scriptFor(Path.of("hagrid", "simulation")))
                .isEqualTo(cwd.resolve("analysis").resolve("lausitz").resolve("kpi").resolve("build_kpis.py"));
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
