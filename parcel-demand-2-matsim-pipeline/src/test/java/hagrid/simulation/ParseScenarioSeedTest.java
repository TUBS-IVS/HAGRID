package hagrid.simulation;

import hagrid.utils.general.StudyArea;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Review F3 (error-band enabler): the MATSim global random seed used to be pinned in the
 * builders — no runner key existed, so multi-seed error-band replicate runs were impossible.
 * {@code seed=} is now a scenario-spec key, default 1337 (backward compatible: identical
 * specs stay identical), threaded through {@link HAGRIDSimulationConfig#getSeed()}.
 */
@DisplayName("parseScenario: seed option (MATSim global random seed, review F3)")
class ParseScenarioSeedTest {

    @Test
    @DisplayName("seed parses to the given value")
    void seedParses() {
        HAGRIDSimulationConfig cfg = SimulationRunnerUtils.parseScenario(
                "concept=drt_baseline,date=2025-05-13,studyArea=LAUSITZ_HOYERSWERDA,seed=4711");
        assertEquals(4711L, cfg.getSeed());
    }

    @Test
    @DisplayName("seed defaults to 1337 when omitted (identical runs stay identical)")
    void seedDefaults() {
        HAGRIDSimulationConfig cfg = SimulationRunnerUtils.parseScenario(
                "concept=drt_baseline,date=2025-05-13,studyArea=LAUSITZ_HOYERSWERDA");
        assertEquals(1337L, cfg.getSeed());
    }

    @Test
    @DisplayName("non-numeric seed is rejected")
    void seedRejectsNonNumeric() {
        assertThatThrownBy(() -> SimulationRunnerUtils.parseScenario(
                "concept=drt_baseline,date=2025-05-13,studyArea=LAUSITZ_HOYERSWERDA,seed=abc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("seed");
    }

    @Test
    @DisplayName("config chain: shorter constructors default the seed to 1337")
    void configDefaultsSeed() {
        HAGRIDSimulationConfig cfg = new HAGRIDSimulationConfig(
                "DRT_BASELINE", LocalDate.of(2025, 5, 13), 1, 1,
                false, 0.0, 0.0, "", StudyArea.LAUSITZ_HOYERSWERDA, 4,
                false, false, 600.0, false);
        assertEquals(1337L, cfg.getSeed());
    }

    @Test
    @DisplayName("config chain: fullest constructor carries an explicit seed")
    void configCarriesExplicitSeed() {
        HAGRIDSimulationConfig cfg = new HAGRIDSimulationConfig(
                "DRT_BASELINE", LocalDate.of(2025, 5, 13), 1, 1,
                false, 0.0, 0.0, "", StudyArea.LAUSITZ_HOYERSWERDA, 4,
                false, false, 600.0, false, 20260728L);
        assertEquals(20260728L, cfg.getSeed());
    }
}
