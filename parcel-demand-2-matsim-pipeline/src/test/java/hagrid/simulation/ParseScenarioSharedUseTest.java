package hagrid.simulation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("parseScenario: chiThreshold + noParcels options (DRT_SHAREDUSE)")
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

    @Test
    @DisplayName("noParcels defaults to false when omitted")
    void noParcelsDefaultsFalse() {
        HAGRIDSimulationConfig def = SimulationRunnerUtils.parseScenarios(new String[]{
                "concept=drt_shareduse,date=2025-05-13,studyArea=LAUSITZ_HOYERSWERDA"}).get(0);
        assertFalse(def.isNoParcels(), "noParcels must default to false (parcels injected)");
    }

    @Test
    @DisplayName("noParcels=true parses to the leakage-control switch")
    void noParcelsParsesTrue() {
        HAGRIDSimulationConfig cfg = SimulationRunnerUtils.parseScenarios(new String[]{
                "concept=drt_shareduse,date=2025-05-13,studyArea=LAUSITZ_HOYERSWERDA,noParcels=true"}).get(0);
        assertTrue(cfg.isNoParcels(), "noParcels=true must enable the 8-seat leakage control");
    }
}
