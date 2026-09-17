package hagrid.simulation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import hagrid.utils.general.StudyArea;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("freight= run option (married DRT_BASELINE gate)")
class ParseScenarioDrtFreightTest {

    @Test
    @DisplayName("DRT_BASELINE defaults to freight=true (married is the spec baseline)")
    void drtDefaultsToFreightOn() {
        HAGRIDSimulationConfig cfg = SimulationRunnerUtils.parseScenario(
                "concept=DRT_BASELINE,date=2025-05-13,maxIter=1");
        assertThat(cfg.isDrtWithFreight()).isTrue();
    }

    @Test
    @DisplayName("freight=false yields the passenger-only DRT run")
    void freightOffParsed() {
        HAGRIDSimulationConfig cfg = SimulationRunnerUtils.parseScenario(
                "concept=DRT_BASELINE,date=2025-05-13,maxIter=1,freight=false");
        assertThat(cfg.isDrtWithFreight()).isFalse();
    }

    @Test
    @DisplayName("isDrtWithFreight is false for non-DRT concepts even with freight=true")
    void lmdIgnoresFreightFlag() {
        HAGRIDSimulationConfig cfg = SimulationRunnerUtils.parseScenario(
                "concept=LMD_BASELINE,date=2025-05-13,maxIter=0,freight=true");
        assertThat(cfg.isDrtWithFreight()).isFalse();
    }

    @Test
    @DisplayName("10-arg constructor keeps married default (true) for DRT concepts")
    void tenArgCtorDefaultsTrue() {
        HAGRIDSimulationConfig cfg = new HAGRIDSimulationConfig(
                "DRT_BASELINE", java.time.LocalDate.of(2025, 5, 13), 1, 1,
                false, 0.0, 0.0, "", StudyArea.LAUSITZ_HOYERSWERDA, 80);
        assertThat(cfg.isDrtWithFreight()).isTrue();
    }
}
