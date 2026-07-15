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
