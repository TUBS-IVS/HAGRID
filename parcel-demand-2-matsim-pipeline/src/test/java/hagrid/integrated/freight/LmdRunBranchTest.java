package hagrid.integrated.freight;

import hagrid.simulation.SimulationRunnerUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("LMD run branch + validation")
class LmdRunBranchTest {

    @Test
    @DisplayName("validateInputFiles for LMD complains about the LMD inputs, not Hannover carriers")
    void validatesLmdInputs() {
        var cfg = SimulationRunnerUtils.parseScenario("concept=LMD_BASELINE,date=2025-05-13,tag=VALTEST");
        assertThatThrownBy(cfg::validateInputFiles)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LMD")          // names an LMD input
                .hasMessageNotContaining("Supply carriers"); // NOT the Hannover freight checks
    }
}
