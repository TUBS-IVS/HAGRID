package hagrid.simulation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("parseScenario: chiThreshold option (DRT_SHAREDUSE)")
class ParseScenarioSharedUseTest {

    @Test
    @DisplayName("chiThreshold parses to the given value")
    void chiThresholdParses() {
        HAGRIDSimulationConfig cfg = SimulationRunnerUtils.parseScenarios(new String[]{
                "concept=drt_shareduse,date=2025-05-13,studyArea=LAUSITZ_HOYERSWERDA,chiThreshold=450"}).get(0);
        assertEquals(450.0, cfg.getChiThreshold(), 1e-9);
    }

    @Test
    @DisplayName("chiThreshold defaults to 600.0 when omitted")
    void chiThresholdDefaults() {
        HAGRIDSimulationConfig def = SimulationRunnerUtils.parseScenarios(new String[]{
                "concept=drt_shareduse,date=2025-05-13,studyArea=LAUSITZ_HOYERSWERDA"}).get(0);
        assertEquals(600.0, def.getChiThreshold(), 1e-9);
    }
}
