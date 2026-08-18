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
    @DisplayName("the parent plan's own 3-depot sweep spec parses to those three sites in that order")
    void planSweepExampleParsesInOrder() {
        // docs/superpowers/plans/2026-08-17-integrated-district-depot-assignment.md:1458 -
        // pinned verbatim since this is the exact syntax the depot sweep is driven by.
        HAGRIDSimulationConfig cfg = SimulationRunnerUtils.parseScenario(
                "concept=drt_modular,openDepots=wittichenau,hoy_sued,doergenhausen,"
                        + "date=2025-06-10,maxIter=1,jspritIter=1,tag=d1d_dep3");
        assertThat(cfg.getOpenDepots()).containsExactly("wittichenau", "hoy_sued", "doergenhausen");
    }

    /**
     * Fix round 1 (review Important 1): the tokenizer's bare-token continuation rule must be
     * narrowed to {@code openDepots} only. A prior, over-broad version let a stray bare token
     * continue ANY preceding key's value instead of failing - e.g.
     * {@code concept=DRT_MODULAR,maxIterr,date=...,maxIter=1} silently mangled {@code concept}
     * to {@code "DRT_MODULAR,MAXITERR"} (the enum-lookup gates swallow the resulting
     * {@code IllegalArgumentException} and just return {@code false}, so the corruption surfaced
     * only much later as a cryptic {@code No enum constant} error). These three tests pin the
     * narrowed behaviour so that regression cannot come back silently.
     */
    @Test
    @DisplayName("a stray bare token after an unrelated key is still rejected (concept must not be silently mangled)")
    void strayTokenAfterUnrelatedKeyIsStillRejected() {
        assertThatThrownBy(() -> SimulationRunnerUtils.parseScenario(
                "concept=DRT_MODULAR,maxIterr,date=2025-06-10,maxIter=1,jspritIter=1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid token")
                .hasMessageContaining("maxIterr");
    }

    @Test
    @DisplayName("a stray bare token after tag=... is still rejected (no silently mangled run-directory name)")
    void strayTokenAfterTagIsStillRejected() {
        assertThatThrownBy(() -> SimulationRunnerUtils.parseScenario(
                "concept=DRT_MODULAR,date=2025-06-10,maxIter=1,jspritIter=1,tag=depot1,stray"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid token")
                .hasMessageContaining("stray");
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
