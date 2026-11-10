package hagrid.core.config;

import hagrid.core.HagridConfig;
import hagrid.core.simulation.HAGRIDSimulationConfig;
import hagrid.core.simulation.SimulationRunnerUtils;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;

import static hagrid.core.config.ResolvedHagridConfig.Source.*;
import static org.assertj.core.api.Assertions.*;

class SimulationRoutingConfigAdapterTest {
    @Test void commonSolverRouteDurationIsExplicitAcrossEveryFreightProfile() {
        for(var concept:java.util.List.of("basecase","lmd_baseline","drt_modular")) {
            var launch=SimulationRunnerUtils.parseScenario("concept="+concept+",date=2025-05-13,solverMaxRouteDuration=14400");
            var resolved=launch.getResolvedRoutingConfig();
            assertThat(resolved.tourRules().maxRouteDurationSeconds()).isEqualTo(14400);
            assertThat(resolved.provenance()).containsEntry("tourRules.maxRouteDurationSeconds",CLI);
        }
    }
    @Test void invalidCommonSolverRouteDurationFailsBeforeRunningAnything() {
        for(var value:java.util.List.of("0","-1","NaN","1.5","2147483648"))
            assertThatThrownBy(()->SimulationRunnerUtils.parseScenario("concept=lmd_baseline,date=2025-05-13,solverMaxRouteDuration="+value))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("solverMaxRouteDuration");
    }
    @Test void commonModularCapAlsoReachesTheNativeDispatchGetter() {
        var launch=SimulationRunnerUtils.parseScenario("concept=drt_modular,date=2025-05-13,solverMaxRouteDuration=14400");
        assertThat(launch.getMaxTourDurationSeconds()).isEqualTo(14400);
    }
    @Test void conflictingExplicitModularCapsAreRejected() {
        assertThatThrownBy(()->SimulationRunnerUtils.parseScenario(
                "concept=drt_modular,date=2025-05-13,maxTourDuration=12600,solverMaxRouteDuration=14400"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("duration");
    }
    @Test void configurableInitialSolverDefaultBecomesTheScenarioProfile() {
        var launch=SimulationRunnerUtils.parseScenario("concept=basecase,date=2025-05-13");
        var defaults=demand(launch);defaults.routing().setSolverMaxRouteDurationSeconds(14400);
        var resolved=SimulationRoutingConfigAdapter.resolve(defaults,launch,HagridConfigResolver.Overrides.empty());
        assertThat(resolved.tourRules().maxRouteDurationSeconds()).isEqualTo(14400);
        assertThat(defaults.routing().getMaxRouteDurationSeconds()).isEqualTo(14400);
    }
    private static HagridConfig demand(HAGRIDSimulationConfig launch) {
        var config = new HagridConfig();
        config.setStudyArea(launch.getStudyArea());
        config.setConcept(launch.getConcept());
        config.setTag(launch.getTag());
        config.setSimulationDate(launch.getSimulationDate());
        return config;
    }

    @Test void explicitResourceCliOptionsReachEveryFreightProfileAndWinOverRunDefaults() {
        for(var concept:java.util.List.of("basecase","lmd_baseline","drt_modular")) {
            var launch=SimulationRunnerUtils.parseScenario("concept="+concept+",date=2025-05-13,solverThreads=2,maxActiveSolvers=1");
            var defaults=demand(launch);defaults.resources().setInternalSolverThreads(3);
            var resolved=SimulationRoutingConfigAdapter.resolve(defaults,launch,
                    HagridConfigResolver.Overrides.of(Map.of("resources.internalSolverThreads","4","resources.maxActiveSolvers","2")));
            assertThat(resolved.resources().internalSolverThreads()).isEqualTo(2);
            assertThat(resolved.resources().maxActiveSolvers()).hasValue(1);
            assertThat(resolved.provenance()).containsEntry("resources.internalSolverThreads",CLI).containsEntry("resources.maxActiveSolvers",CLI);
        }
    }

    @Test void invalidResourceCliLimitsFailAtParsingAndOmittedLimitsKeepTheirDefaults() {
        for(var key:java.util.List.of("solverThreads","maxActiveSolvers"))for(var value:java.util.List.of("0","-1"))
            assertThatThrownBy(()->SimulationRunnerUtils.parseScenario("concept=basecase,date=2025-05-13,"+key+"="+value))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must be positive");
        var launch=SimulationRunnerUtils.parseScenario("concept=basecase,date=2025-05-13");
        var resolved=SimulationRoutingConfigAdapter.resolve(demand(launch),launch,HagridConfigResolver.Overrides.empty());
        assertThat(resolved.resources()).isEqualTo(ResolvedHagridConfig.Resources.defaults());
    }
    @Test
    void parserDefaultsDoNotBecomeExplicitOverrides() {
        var launch = SimulationRunnerUtils.parseScenario("concept=basecase,date=2025-05-13");
        var profile = HagridConfigResolver.Overrides.of(Map.of(
                "tourRules.maxRouteDurationSeconds", "25200", "routing.zoneBasedCachingEnabled", "false",
                "routing.zoneBasedCachingThresholdMeters", "800", "tourRules.uTurnPenaltyCost", "2"));
        var run = HagridConfigResolver.Overrides.of(Map.of("optimization.replanningIterations", "250"));
        var resolved = SimulationRoutingConfigAdapter.resolve(demand(launch), launch, profile, run);
        assertThat(resolved.optimization().initialIterations()).isZero();
        assertThat(resolved.optimization().replanningIterations()).hasValue(250);
        assertThat(resolved.routing().zoneBasedCachingEnabled()).isFalse();
        assertThat(resolved.routing().zoneBasedCachingThresholdMeters()).isZero();
        assertThat(resolved.tourRules().uTurnPenaltyCost()).isEqualTo(2);
        assertThat(resolved.provenance()).containsEntry("routing.zoneBasedCachingEnabled", PROFILE)
                .containsEntry("optimization.replanningIterations", RUN);
        assertThat(launch.getExplicitScenarioInputs().orElseThrow()).containsOnlyKeys("concept", "date");
    }

    @Test
    void explicitCliBudgetWinsWithoutChangingInitialBudget() {
        var launch = SimulationRunnerUtils.parseScenario("concept=basecase,date=2025-05-13,jspritIter=37");
        var defaults = demand(launch);
        defaults.routing().setJspritIterations(3);
        var resolved = SimulationRoutingConfigAdapter.resolve(defaults, launch,
                HagridConfigResolver.Overrides.of(Map.of("optimization.replanningIterations", "20")));
        assertThat(resolved.optimization().initialIterations()).isEqualTo(3);
        assertThat(resolved.optimization().replanningIterations()).hasValue(37);
        assertThat(resolved.optimization().offlineIterations()).isEmpty();
        assertThat(resolved.tourRules().maxRouteDurationSeconds()).isEqualTo(25200);
        assertThat(resolved.provenance()).containsEntry("optimization.replanningIterations", CLI);
    }

    @Test
    void explicitFalseIsNotTreatedAsUnsetAndCacheDefaultKeepsItsSource() {
        var launch = SimulationRunnerUtils.parseScenario("concept=basecase,date=2025-05-13,zoneCaching=false");
        var resolved = SimulationRoutingConfigAdapter.resolve(demand(launch), launch,
                HagridConfigResolver.Overrides.of(Map.of("routing.zoneBasedCachingEnabled", "false")),
                HagridConfigResolver.Overrides.empty());
        assertThat(resolved.routing().zoneBasedCachingEnabled()).isFalse();
        assertThat(resolved.provenance()).containsEntry("routing.zoneBasedCachingEnabled", CLI);
        assertThatThrownBy(()->SimulationRunnerUtils.parseScenario("concept=basecase,date=2025-05-13,zoneCaching=true"))
                .hasMessageContaining("routing.zoneBasedCachingEnabled").hasMessageContaining("removed");
    }

    @Test
    void cargoBikeCliAliasUsesKmhAndOverridesTheSharedDefault() {
        var launch = SimulationRunnerUtils.parseScenario(
                "concept=basecase,date=2025-05-13,cargoBikeMaxSpeedKmh=18");
        var resolved = SimulationRoutingConfigAdapter.resolve(demand(launch), launch,
                HagridConfigResolver.Overrides.empty());
        assertThat(resolved.fleet().cargoBikeMaxSpeedMps()).isEqualTo(5);
        assertThat(resolved.provenance()).containsEntry("fleet.cargoBikeMaxSpeedKmh", CLI);
        var ordinary = SimulationRunnerUtils.parseScenario("concept=basecase,date=2025-05-13");
        assertThat(SimulationRoutingConfigAdapter.resolve(demand(ordinary), ordinary,
                HagridConfigResolver.Overrides.empty()).fleet().cargoBikeMaxSpeedMps()).isEqualTo(20.0 / 3.6);
    }

    @Test
    void modularOfflineBudgetAndTourCapRemainSeparateFromHannover() {
        var launch = SimulationRunnerUtils.parseScenario(
                "concept=drt_modular,date=2025-05-13,jspritIter=41,maxTourDuration=5400");
        var resolved = SimulationRoutingConfigAdapter.resolve(demand(launch), launch,
                HagridConfigResolver.Overrides.empty());
        assertThat(resolved.optimization().offlineIterations()).hasValue(41);
        assertThat(resolved.optimization().replanningIterations()).isEmpty();
        assertThat(resolved.optimization().replanningStartIteration()).isEmpty();
        assertThat(resolved.optimization().carriersPerGroup()).isEmpty();
        assertThat(resolved.tourRules().maxRouteDurationSeconds()).isEqualTo(5400);
        assertThat(resolved.provenance()).containsEntry("tourRules.maxRouteDurationSeconds", CLI);
    }

    @Test
    void lmdDoesNotInheritTheModularTourCap() {
        var launch = SimulationRunnerUtils.parseScenario(
                "concept=lmd_baseline,date=2025-05-13,jspritIter=41,maxTourDuration=5400,uTurnPenalty=4,zoneCaching=false");
        var resolved = SimulationRoutingConfigAdapter.resolve(demand(launch), launch,
                HagridConfigResolver.Overrides.empty());
        assertThat(resolved.optimization().offlineIterations()).hasValue(41);
        assertThat(resolved.tourRules().maxRouteDurationSeconds()).isEqualTo(25200);
        assertThat(resolved.provenance()).containsEntry("tourRules.maxRouteDurationSeconds", PROFILE);
        assertThat(resolved.tourRules().uTurnPenaltyCost()).isZero();
        assertThat(resolved.routing().zoneBasedCachingEnabled()).isFalse();
        assertThat(launch.getExplicitScenarioInputs().orElseThrow()).containsEntry("uTurnPenalty", "4");
    }

    @Test
    void scenariosWithoutCarrierRoutingReceiveNoSolverBudgetOrSchedule() {
        for (String spec : java.util.List.of(
                "concept=drt_shareduse,date=2025-05-13,tag=test,jspritIter=41",
                "concept=drt_baseline,date=2025-05-13,freight=false,jspritIter=41")) {
            var launch = SimulationRunnerUtils.parseScenario(spec);
            var resolved = SimulationRoutingConfigAdapter.resolve(demand(launch), launch,
                    HagridConfigResolver.Overrides.empty());
            assertThat(resolved.optimization().offlineIterations()).isEmpty();
            assertThat(resolved.optimization().replanningIterations()).isEmpty();
            assertThat(resolved.optimization().replanningStartIteration()).isEmpty();
            assertThat(launch.getExplicitScenarioInputs().orElseThrow()).containsEntry("jspritIter", "41");
        }
    }

    @Test
    void constructorInputsRemainUsableWithoutInventingCliProvenance() {
        var launch = new HAGRIDSimulationConfig("basecase", LocalDate.of(2025, 5, 13),
                150, 55, false, 750, 2, "");
        var resolved = SimulationRoutingConfigAdapter.resolve(demand(launch), launch,
                HagridConfigResolver.Overrides.empty());
        assertThat(launch.getExplicitScenarioInputs()).isEmpty();
        assertThat(resolved.optimization().replanningIterations()).hasValue(55);
        assertThat(resolved.routing().zoneBasedCachingThresholdMeters()).isZero();
        assertThat(resolved.provenance()).containsEntry("optimization.replanningIterations", RUN);
    }

    @Test
    void ignoredLausitzConstructorOptionsDoNotClaimToBeEffectiveRunOverrides() {
        var launch = new HAGRIDSimulationConfig("lmd_baseline", LocalDate.of(2025, 5, 13),
                150, 55, false, 750, 4, "", hagrid.core.util.StudyArea.LAUSITZ_HOYERSWERDA, 50);
        var resolved = SimulationRoutingConfigAdapter.resolve(demand(launch), launch,
                HagridConfigResolver.Overrides.empty());
        assertThat(resolved.optimization().offlineIterations()).hasValue(55);
        assertThat(resolved.tourRules().uTurnPenaltyCost()).isZero();
        assertThat(resolved.routing().zoneBasedCachingEnabled()).isFalse();
        assertThat(resolved.provenance()).containsEntry("optimization.offlineIterations", RUN)
                .containsEntry("tourRules.uTurnPenaltyCost", DEFAULTS);
    }

    @Test
    void incompatibleDemandScenarioIsRejectedAndExplicitInputsAreImmutable() {
        var launch = SimulationRunnerUtils.parseScenario("concept=lmd_baseline,date=2025-05-13");
        assertThatIllegalArgumentException().isThrownBy(() -> SimulationRoutingConfigAdapter.resolve(
                new HagridConfig(), launch, HagridConfigResolver.Overrides.empty()))
                .withMessageContaining("studyArea");
        assertThatThrownBy(() -> launch.getExplicitScenarioInputs().orElseThrow().put("jspritIter", "1"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void invalidBikeCliValuesAreRejectedBeforeSimulation() {
        for (String speed : java.util.List.of("0", "-1", "NaN", "Infinity", "4.9E-324")) {
            assertThatIllegalArgumentException().isThrownBy(() -> SimulationRunnerUtils.parseScenario(
                    "concept=basecase,date=2025-05-13,cargoBikeMaxSpeedKmh=" + speed))
                    .withMessageContaining("cargoBikeMaxSpeedKmh");
        }
    }

    @Test
    void conflictingOfflineAndReplanningBudgetsAreRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> HagridConfigResolver.resolve(new HagridConfig(),
                HagridConfigResolver.Overrides.empty(), HagridConfigResolver.Overrides.of(Map.of(
                        "optimization.offlineIterations", "41", "optimization.replanningIterations", "37")), Map.of()))
                .withMessageContaining("offlineIterations").withMessageContaining("replanningIterations");
    }
}
