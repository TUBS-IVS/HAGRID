package hagrid.simulation;

import hagrid.utils.general.StudyArea;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

@DisplayName("Scenario parsing — study area & DRT")
class ScenarioParsingTest {

    @Test
    @DisplayName("non-DRT scenario defaults to HANNOVER")
    void defaultHannover() {
        HAGRIDSimulationConfig cfg = SimulationRunnerUtils.parseScenario(
                "concept=basecase,date=2025-05-13");
        assertThat(cfg.getStudyArea()).isEqualTo(StudyArea.HANNOVER);
        assertThat(cfg.isDrtScenario()).isFalse();
    }

    @Test
    @DisplayName("DRT scenario with studyArea=LAUSITZ_HOYERSWERDA parses")
    void drtLausitz() {
        HAGRIDSimulationConfig cfg = SimulationRunnerUtils.parseScenario(
                "concept=drt_baseline,date=2025-05-13,studyArea=LAUSITZ_HOYERSWERDA,fleetSize=20");
        assertThat(cfg.getStudyArea()).isEqualTo(StudyArea.LAUSITZ_HOYERSWERDA);
        assertThat(cfg.isDrtScenario()).isTrue();
        assertThat(cfg.getFleetSize()).isEqualTo(20);
    }

    @Test
    @DisplayName("DRT scenario without studyArea defaults to LAUSITZ")
    void drtDefaultsToLausitz() {
        HAGRIDSimulationConfig cfg = SimulationRunnerUtils.parseScenario(
                "concept=drt_baseline,date=2025-05-13");
        assertThat(cfg.getStudyArea()).isEqualTo(StudyArea.LAUSITZ_HOYERSWERDA);
        assertThat(cfg.isDrtScenario()).isTrue();
    }

    @Test
    @DisplayName("DRT scenario with studyArea=HANNOVER is rejected")
    void drtWithHannoverRejected() {
        assertThatThrownBy(() -> SimulationRunnerUtils.parseScenario(
                "concept=drt_baseline,date=2025-05-13,studyArea=HANNOVER"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("LAUSITZ_HOYERSWERDA");
    }
}
