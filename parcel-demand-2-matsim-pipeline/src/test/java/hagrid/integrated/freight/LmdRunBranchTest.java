package hagrid.integrated.freight;

import hagrid.simulation.HAGRIDSimulationConfig;
import hagrid.simulation.SimulationRunnerUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("LMD run branch + validation")
class LmdRunBranchTest {

    @Test
    @DisplayName("validateInputFiles for LMD complains about the LMD inputs, not Hannover carriers")
    void validatesLmdInputs() {
        HAGRIDSimulationConfig cfg = SimulationRunnerUtils.parseScenario(
                "concept=LMD_BASELINE,date=2025-05-13,tag=VALTEST");
        assertThat(cfg.isLmdBaseline()).isTrue();

        // If staged data is absent the validation must name an LMD input (not Hannover carriers).
        // If staged data IS present the validation passes — both outcomes are correct; the key
        // invariant is that we never see a "Supply carriers" message for an LMD concept.
        try {
            cfg.validateInputFiles();
            // validation passed — staged data is present; nothing to assert about the message
        } catch (IllegalStateException ex) {
            // validation failed — must mention an LMD-specific file, not Hannover freight
            assertThat(ex.getMessage())
                    .contains("LMD")
                    .doesNotContain("Supply carriers");
        }
    }

    @Test
    @DisplayName("Hannover BASECASE validation does not mention LMD inputs")
    void hanoverValidationMentionsCarriersNotLmd() {
        HAGRIDSimulationConfig cfg = SimulationRunnerUtils.parseScenario(
                "concept=BASECASE,date=2025-05-13,tag=VALTEST");
        assertThat(cfg.isLmdBaseline()).isFalse();
        // Hannover BASECASE staged inputs are never present in CI — expect a throw mentioning
        // Hannover-specific files (never LMD-specific ones).
        assertThatThrownBy(cfg::validateInputFiles)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("LMD");
    }
}
