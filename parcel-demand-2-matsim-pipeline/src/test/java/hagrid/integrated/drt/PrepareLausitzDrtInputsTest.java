package hagrid.integrated.drt;

import hagrid.simulation.HAGRIDSimulationConfig;
import hagrid.utils.general.StudyArea;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link PrepareLausitzDrtInputs}.
 *
 * <p>The heavy preprocessor ({@link LausitzDrtPreprocessor}) is NOT invoked here
 * because no staged DRT input data is available in the unit-test environment.
 * The test covers the rejection path: a non-DRT config passed to
 * {@link PrepareLausitzDrtInputs#process(List)} must be rejected immediately
 * with a clear {@link IllegalArgumentException} before any preprocessor call.
 */
@DisplayName("PrepareLausitzDrtInputs")
class PrepareLausitzDrtInputsTest {

    /** Builds a minimal HANNOVER/BASECASE config (non-DRT) for the rejection-path test. */
    private static HAGRIDSimulationConfig nonDrtConfig() {
        return new HAGRIDSimulationConfig(
                "basecase",
                LocalDate.of(2025, 5, 13),
                1,   // maxIterations (min valid value)
                1,   // jspritIterations
                false, 0.0, 1.0, "",
                StudyArea.HANNOVER, 50);
    }

    @Test
    @DisplayName("runsPreprocessorForDrtScenarioOnly — rejects non-DRT config with clear message")
    void runsPreprocessorForDrtScenarioOnly() {
        HAGRIDSimulationConfig nonDrt = nonDrtConfig();

        assertThatThrownBy(() -> PrepareLausitzDrtInputs.process(List.of(nonDrt)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PrepareLausitzDrtInputs only handles DRT scenarios:")
                .hasMessageContaining(nonDrt.getRunId());
    }
}
