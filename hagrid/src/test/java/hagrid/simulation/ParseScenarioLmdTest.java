package hagrid.simulation;

import hagrid.utils.general.StudyArea;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("parseScenario for LMD_BASELINE")
class ParseScenarioLmdTest {

    @Test
    @DisplayName("LMD_BASELINE defaults the study area to LAUSITZ_HOYERSWERDA")
    void defaultsToLausitz() {
        HAGRIDSimulationConfig cfg = SimulationRunnerUtils.parseScenario(
                "concept=LMD_BASELINE,date=2025-05-13");
        assertThat(cfg.getStudyArea()).isEqualTo(StudyArea.LAUSITZ_HOYERSWERDA);
        assertThat(cfg.isLmdBaseline()).isTrue();
        assertThat(cfg.isDrtScenario()).isFalse();
    }

    @Test
    @DisplayName("LMD_BASELINE with studyArea=HANNOVER is rejected")
    void rejectsHannover() {
        assertThatThrownBy(() -> SimulationRunnerUtils.parseScenario(
                "concept=LMD_BASELINE,date=2025-05-13,studyArea=HANNOVER"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("LAUSITZ_HOYERSWERDA");
    }

    @Test
    @DisplayName("LMD_BASELINE with maxIter=0 parses successfully and reports 0 iterations")
    void lmdMaxIterZeroAllowed() {
        HAGRIDSimulationConfig cfg = SimulationRunnerUtils.parseScenario(
                "concept=LMD_BASELINE,date=2025-05-13,maxIter=0");
        assertThat(cfg.getMaxIterations()).isEqualTo(0);
        assertThat(cfg.isLmdBaseline()).isTrue();
    }

    @Test
    @DisplayName("BASECASE with maxIter=0 is rejected")
    void basecaseMaxIterZeroRejected() {
        assertThatThrownBy(() -> SimulationRunnerUtils.parseScenario(
                "concept=BASECASE,date=2025-05-13,maxIter=0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxIter=0 is only allowed for LMD_BASELINE")
                .hasMessageContaining("BASECASE");
    }
}
