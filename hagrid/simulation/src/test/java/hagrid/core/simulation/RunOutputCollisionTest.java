package hagrid.core.simulation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Review 2026-10-02 #2, the half the completed-run guard in
 * {@link HAGRIDSimulationConfig#validateInputFiles()} cannot see: two specs of ONE launch that
 * resolve to the same output directory. At validation time neither has run, so no
 * run_metadata.json exists yet; the second would delete the first at its own startup.
 */
@DisplayName("one launch, two specs, one output directory")
class RunOutputCollisionTest {

    private static final String SPEC =
            "concept=drt_modular,date=2025-05-13,tag=collisiontest,fleetSize=130";

    @Test
    @DisplayName("same output directory twice in one launch → abort before any run starts")
    void rejectsTwoSpecsWritingTheSameOutputDirectory() {
        List<HAGRIDSimulationConfig> cfgs = SimulationRunnerUtils.parseScenarios(new String[]{
                SPEC + ",seed=1337", SPEC + ",seed=1338"});

        assertThatThrownBy(() -> SimulationRunnerUtils.validateAll(cfgs))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("same output directory")
                .hasMessageContaining("DRT_MODULAR_13052025_collisiontest");
    }

    @Test
    @DisplayName("overwrite=true parses onto the config; default is false")
    void overwriteKeyParses() {
        assertThat(SimulationRunnerUtils.parseScenario(SPEC).isOverwriteCompletedRun()).isFalse();
        assertThat(SimulationRunnerUtils.parseScenario(SPEC + ",overwrite=true")
                .isOverwriteCompletedRun()).isTrue();
        assertThatThrownBy(() -> SimulationRunnerUtils.parseScenario(SPEC + ",overwrite=ja"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("overwrite");
    }
}
