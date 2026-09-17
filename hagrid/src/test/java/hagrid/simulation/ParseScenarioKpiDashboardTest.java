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
