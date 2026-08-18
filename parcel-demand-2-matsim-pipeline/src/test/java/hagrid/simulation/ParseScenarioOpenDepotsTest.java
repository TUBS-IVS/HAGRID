package hagrid.simulation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 7 (spec 2026-08-17): {@code openDepots} / {@code maxJobsPerDistrict} runner keys, parsed
 * with the same {@code map.getOrDefault(...)} pattern as {@code chiThreshold}
 * ({@link ParseScenarioSharedUseTest}), and the output-collision tag guard extended to
 * {@code DRT_MODULAR} whenever {@code openDepots} deviates from "all". None of this parse/guard
 * logic is exercised by {@code HAGRIDSimulationConfigTest} (which constructs configs directly,
 * bypassing the string parser entirely), so it is covered here instead.
 */
@DisplayName("parseScenario: openDepots / maxJobsPerDistrict (district-based depot assignment, spec 2026-08-17)")
class ParseScenarioOpenDepotsTest {

    @Test
    @DisplayName("openDepots/maxJobsPerDistrict default to \"every depot open\" / 300 when omitted")
    void defaultsToAllAndThreeHundred() {
        HAGRIDSimulationConfig cfg = SimulationRunnerUtils.parseScenario(
                "concept=drt_baseline,date=2025-05-13,studyArea=LAUSITZ_HOYERSWERDA");
        assertThat(cfg.getOpenDepots()).isEmpty();
        assertThat(cfg.getMaxJobsPerDistrict()).isEqualTo(300);
    }

    @Test
    @DisplayName("openDepots=all explicitly parses to the empty (every-depot-open) list")
    void explicitAllParsesToEmptyList() {
        HAGRIDSimulationConfig cfg = SimulationRunnerUtils.parseScenario(
                "concept=drt_baseline,date=2025-05-13,studyArea=LAUSITZ_HOYERSWERDA,openDepots=all");
        assertThat(cfg.getOpenDepots()).isEmpty();
    }

    @Test
    @DisplayName("openDepots parses a comma-separated list, lower-cased, preserving input order")
    void parsesCommaSeparatedListPreservingOrder() {
        HAGRIDSimulationConfig cfg = SimulationRunnerUtils.parseScenario(
                "concept=DRT_MODULAR,date=2025-06-10,maxIter=1,jspritIter=1,tag=depot3,"
                        + "openDepots=HOY_SUED,Lauta,spreetal");
        assertThat(cfg.getOpenDepots()).containsExactly("hoy_sued", "lauta", "spreetal");
    }

    @Test
    @DisplayName("maxJobsPerDistrict parses to the given value")
    void maxJobsPerDistrictParses() {
        HAGRIDSimulationConfig cfg = SimulationRunnerUtils.parseScenario(
                "concept=drt_baseline,date=2025-05-13,studyArea=LAUSITZ_HOYERSWERDA,maxJobsPerDistrict=50");
        assertThat(cfg.getMaxJobsPerDistrict()).isEqualTo(50);
    }

    @Test
    @DisplayName("maxJobsPerDistrict=0 is rejected (must be positive)")
    void maxJobsPerDistrictRejectsNonPositive() {
        assertThatThrownBy(() -> SimulationRunnerUtils.parseScenario(
                "concept=drt_baseline,date=2025-05-13,studyArea=LAUSITZ_HOYERSWERDA,maxJobsPerDistrict=0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxJobsPerDistrict");
    }

    @Test
    @DisplayName("DRT_MODULAR with non-default openDepots and no tag is rejected (output-collision guard)")
    void modularWithNonDefaultOpenDepotsRequiresTag() {
        assertThatThrownBy(() -> SimulationRunnerUtils.parseScenario(
                "concept=DRT_MODULAR,date=2025-06-10,maxIter=1,jspritIter=1,openDepots=hoy_sued"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tag")
                .hasMessageContaining("openDepots");
    }

    @Test
    @DisplayName("DRT_MODULAR with non-default openDepots AND a tag parses successfully")
    void modularWithNonDefaultOpenDepotsAndTagSucceeds() {
        HAGRIDSimulationConfig cfg = SimulationRunnerUtils.parseScenario(
                "concept=DRT_MODULAR,date=2025-06-10,maxIter=1,jspritIter=1,tag=depot1,openDepots=hoy_sued");
        assertThat(cfg.getOpenDepots()).containsExactly("hoy_sued");
    }

    @Test
    @DisplayName("DRT_MODULAR with default openDepots (\"all\") does NOT require a tag")
    void modularWithDefaultOpenDepotsDoesNotRequireTag() {
        HAGRIDSimulationConfig cfg = SimulationRunnerUtils.parseScenario(
                "concept=DRT_MODULAR,date=2025-06-10,maxIter=1,jspritIter=1");
        assertThat(cfg.getTag()).isEmpty();
        assertThat(cfg.getOpenDepots()).isEmpty();
    }

    @Test
    @DisplayName("openDepots guard is DRT_SHAREDUSE/DRT_MODULAR-only: DRT_BASELINE ignores it, no tag required")
    void otherDrtConceptsIgnoreTheOpenDepotsGuard() {
        HAGRIDSimulationConfig cfg = SimulationRunnerUtils.parseScenario(
                "concept=drt_baseline,date=2025-05-13,studyArea=LAUSITZ_HOYERSWERDA,openDepots=hoy_sued");
        assertThat(cfg.getTag()).isEmpty();
        assertThat(cfg.getOpenDepots()).containsExactly("hoy_sued");
    }
}
