package hagrid.simulation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The legacy Java dashboard ({@code writeDashboard=true}) reads
 * {@code <runId>.output_carriers.xml.gz}. DRT_SHAREDUSE (1c) and DRT_MODULAR (1d) never run the
 * carrier modules ({@link SimulationRunnerUtils#runsCarrierModules(hagrid.HagridConfig.Scenario,
 * boolean)}), so that file cannot exist for them — {@code generateDashboard} threw
 * {@code IllegalStateException} from {@code HAGRIDSimulationRunner.main} AFTER a fully completed
 * run, i.e. after simulation, KPIs and the v2 dashboard. Only the exit code was wrong, but every
 * 1c/1d run reported failure and no automation could trust it.
 *
 * <p>These tests are hermetic: they never reach a MATSim output directory. The two skip cases
 * return before any file is touched, and the carrier case fails on the missing-file check, which
 * is exactly the behaviour that must survive.</p>
 */
@DisplayName("generateDashboard: the legacy dashboard is skipped for carrier-less scenarios")
class GenerateDashboardGuardTest {

    @Test
    @DisplayName("DRT_SHAREDUSE (1c) is skipped instead of throwing on the missing carriers file")
    void sharedUseIsSkipped() {
        HAGRIDSimulationConfig cfg = SimulationRunnerUtils.parseScenario(
                "concept=drt_shareduse,date=2025-05-13,studyArea=LAUSITZ_HOYERSWERDA,tag=dashguard");
        assertThatCode(() -> SimulationRunnerUtils.generateDashboard(cfg))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("DRT_MODULAR (1d) is skipped instead of throwing on the missing carriers file")
    void modularIsSkipped() {
        HAGRIDSimulationConfig cfg = SimulationRunnerUtils.parseScenario(
                "concept=DRT_MODULAR,date=2025-06-10,maxIter=1,jspritIter=1,tag=dashguard");
        assertThatCode(() -> SimulationRunnerUtils.generateDashboard(cfg))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a passenger-only DRT run (freight=false) is skipped too — same missing-carriers cause")
    void passengerOnlyDrtIsSkipped() {
        HAGRIDSimulationConfig cfg = SimulationRunnerUtils.parseScenario(
                "concept=drt_baseline,date=2025-05-13,studyArea=LAUSITZ_HOYERSWERDA,tag=dashguard,freight=false");
        assertThatCode(() -> SimulationRunnerUtils.generateDashboard(cfg))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the married baseline (DRT + freight=true) is NOT skipped — it does route carriers")
    void marriedBaselineStillThrows() {
        HAGRIDSimulationConfig cfg = SimulationRunnerUtils.parseScenario(
                "concept=drt_baseline,date=2025-05-13,studyArea=LAUSITZ_HOYERSWERDA,tag=dashguard,freight=true");
        assertThatThrownBy(() -> SimulationRunnerUtils.generateDashboard(cfg))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Missing output files");
    }

    @Test
    @DisplayName("the guard is not too broad: a carrier scenario still fails loudly on missing outputs")
    void carrierScenarioStillThrows() {
        // Without this case a guard that skips EVERY scenario would pass the two tests above and
        // silently disable the Hannover sweep's data source (its SUMMARY blob comes from exactly
        // this dashboard).
        HAGRIDSimulationConfig cfg = SimulationRunnerUtils.parseScenario(
                "concept=lmd_baseline,date=2025-05-13,studyArea=LAUSITZ_HOYERSWERDA,tag=dashguard");
        assertThatThrownBy(() -> SimulationRunnerUtils.generateDashboard(cfg))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Missing output files");
    }
}
