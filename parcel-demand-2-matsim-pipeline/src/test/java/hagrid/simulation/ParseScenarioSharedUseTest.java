package hagrid.simulation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NOTE (review I2/M5): every DRT_SHAREDUSE spec below carries an explicit {@code tag=...}.
 * parseScenario now REJECTS a shareduse spec with a blank tag, because chiThreshold/noParcels
 * are not part of the runId and MATSim deletes an existing output directory — two sweep points
 * differing only in chi would silently destroy each other's outputs (see
 * {@link #blankTagIsRejectedForSharedUse()}).
 */
@DisplayName("parseScenario: chiThreshold + noParcels options (DRT_SHAREDUSE)")
class ParseScenarioSharedUseTest {

    @Test
    @DisplayName("chiThreshold parses to the given value")
    void chiThresholdParses() {
        HAGRIDSimulationConfig cfg = SimulationRunnerUtils.parseScenarios(new String[]{
                "concept=drt_shareduse,date=2025-05-13,studyArea=LAUSITZ_HOYERSWERDA,tag=chi450,chiThreshold=450"}).get(0);
        assertEquals(450.0, cfg.getChiThreshold(), 1e-9);
    }

    @Test
    @DisplayName("chiThreshold defaults to 600.0 when omitted")
    void chiThresholdDefaults() {
        HAGRIDSimulationConfig def = SimulationRunnerUtils.parseScenarios(new String[]{
                "concept=drt_shareduse,date=2025-05-13,studyArea=LAUSITZ_HOYERSWERDA,tag=chi600"}).get(0);
        assertEquals(600.0, def.getChiThreshold(), 1e-9);
    }

    @Test
    @DisplayName("chiThreshold=-1 (hard-closed gate) is accepted")
    void chiThresholdNegativeParses() {
        // < 0 = gate hard-closed (rejects ALL parcels; Task-10 leakage probe) — the parser
        // must not reject negative values (was nonNegDouble before rev. 2026-07-27).
        HAGRIDSimulationConfig cfg = SimulationRunnerUtils.parseScenarios(new String[]{
                "concept=drt_shareduse,date=2025-05-13,studyArea=LAUSITZ_HOYERSWERDA,tag=chineg1,chiThreshold=-1"}).get(0);
        assertEquals(-1.0, cfg.getChiThreshold(), 1e-9);
    }

    @Test
    @DisplayName("noParcels defaults to false when omitted")
    void noParcelsDefaultsFalse() {
        HAGRIDSimulationConfig def = SimulationRunnerUtils.parseScenarios(new String[]{
                "concept=drt_shareduse,date=2025-05-13,studyArea=LAUSITZ_HOYERSWERDA,tag=chi600"}).get(0);
        assertFalse(def.isNoParcels(), "noParcels must default to false (parcels injected)");
    }

    @Test
    @DisplayName("noParcels=true parses to the leakage-control switch")
    void noParcelsParsesTrue() {
        HAGRIDSimulationConfig cfg = SimulationRunnerUtils.parseScenarios(new String[]{
                "concept=drt_shareduse,date=2025-05-13,studyArea=LAUSITZ_HOYERSWERDA,tag=noparcels,noParcels=true"}).get(0);
        assertTrue(cfg.isNoParcels(), "noParcels=true must enable the 8-seat leakage control");
    }

    @Test
    @DisplayName("I2/M5: a DRT_SHAREDUSE spec WITHOUT a tag is rejected (output-collision guard)")
    void blankTagIsRejectedForSharedUse() {
        // runId = CONCEPT_date[_tag]; chiThreshold/noParcels are not part of it, and MATSim
        // deletes an existing output dir — two chi sweep points without distinguishing tags
        // would silently destroy each other. The parser must refuse instead.
        assertThatThrownBy(() -> SimulationRunnerUtils.parseScenario(
                "concept=drt_shareduse,date=2025-05-13,studyArea=LAUSITZ_HOYERSWERDA,chiThreshold=300"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tag")
                .hasMessageContaining("chi");
    }

    @Test
    @DisplayName("I2/M5 guard is shareduse-only: other DRT concepts still parse without a tag")
    void otherDrtConceptsStillParseWithoutTag() {
        HAGRIDSimulationConfig cfg = SimulationRunnerUtils.parseScenario(
                "concept=drt_baseline,date=2025-05-13,studyArea=LAUSITZ_HOYERSWERDA");
        assertEquals("", cfg.getTag(), "non-shareduse concepts keep the optional-tag behaviour");
    }
}
