package hagrid.simulation;

import hagrid.HagridConfig.Scenario;
import hagrid.integrated.modular.Modular;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
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
 *
 * <p>Task 11 (1d) broadened this class beyond its original DRT_SHAREDUSE-only scope: it also
 * covers {@code idleThreshold}/{@code maxTourDuration} (DRT_MODULAR's 1d keys, parsed/constructed
 * with the exact same pattern as {@code chiThreshold} above) and the
 * {@link SimulationRunnerUtils#runsCarrierModules(Scenario, boolean)} static guard those keys'
 * runner branch depends on — extended here rather than in a new file because chiThreshold is
 * the closest analogous key already covered by a dedicated parse-test class.</p>
 */
@DisplayName("parseScenario: chiThreshold/noParcels (1c) + idleThreshold/maxTourDuration (1d) + carrier-module guard")
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

    // ---- Task 11 (1d): idleThreshold / maxTourDuration (DRT_MODULAR) ------------------------

    @Test
    @DisplayName("parseScenario: modular keys parse with 1c-pattern defaults and validation")
    void parsesModularKeys() {
        HAGRIDSimulationConfig cfg = SimulationRunnerUtils.parseScenario(
                "concept=DRT_MODULAR,date=2025-06-10,maxIter=1,jspritIter=1,fleetSize=120,"
                + "idleThreshold=0.7,maxTourDuration=25200");
        assertThat(cfg.getIdleThreshold()).isEqualTo(0.7);
        assertThat(cfg.getMaxTourDurationSeconds()).isEqualTo(25200);

        HAGRIDSimulationConfig defaults = SimulationRunnerUtils.parseScenario(
                "concept=DRT_MODULAR,date=2025-06-10,maxIter=1,jspritIter=1");
        assertThat(defaults.getIdleThreshold()).isEqualTo(Modular.DEFAULT_IDLE_THRESHOLD);
        assertThat(defaults.getMaxTourDurationSeconds()).isEqualTo(Modular.DEFAULT_MAX_TOUR_DURATION_S);

        assertThatThrownBy(() -> SimulationRunnerUtils.parseScenario(
                "concept=DRT_MODULAR,date=2025-06-10,maxIter=1,jspritIter=1,idleThreshold=1.5"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("idleThreshold=1.0 (never-dispatch control arm, Task 12) is ACCEPTED, not rejected")
    void idleThresholdOneIsAccepted() {
        // The upper bound is INCLUSIVE (ctor validates idleThreshold > 1.0, not >= 1.0) because
        // 1.0 is the never-dispatch control arm the next task (12) is built on. A one-character
        // slip from > to >= would silently reject it while every other test here (including
        // idleThreshold=1.5 throwing above) stays green — that case does not discriminate this
        // boundary at all. Pin it explicitly instead of relying on the reject-side test alone.
        HAGRIDSimulationConfig cfg = SimulationRunnerUtils.parseScenario(
                "concept=DRT_MODULAR,date=2025-06-10,maxIter=1,jspritIter=1,idleThreshold=1.0");
        assertThat(cfg.getIdleThreshold()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("carrier-module guard: married yes, shareduse no, MODULAR NO (double-delivery), lmd n/a")
    void carrierModuleGuard() {
        assertThat(SimulationRunnerUtils.runsCarrierModules(Scenario.DRT_BASELINE, true)).isTrue();
        assertThat(SimulationRunnerUtils.runsCarrierModules(Scenario.DRT_BASELINE, false)).isFalse();
        assertThat(SimulationRunnerUtils.runsCarrierModules(Scenario.DRT_SHAREDUSE, true)).isFalse();
        assertThat(SimulationRunnerUtils.runsCarrierModules(Scenario.DRT_MODULAR, true)).isFalse();
        assertThat(SimulationRunnerUtils.runsCarrierModules(Scenario.DRT_MODULAR, false)).isFalse();
    }
}
