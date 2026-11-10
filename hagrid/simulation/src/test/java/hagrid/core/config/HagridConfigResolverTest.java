package hagrid.core.config;

import hagrid.core.HagridConfig;
import hagrid.core.util.StudyArea;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static hagrid.core.config.ResolvedHagridConfig.Source.*;
import static org.assertj.core.api.Assertions.*;

class HagridConfigResolverTest {
    private final HagridConfig defaults = new HagridConfig();

    private ResolvedHagridConfig resolve(HagridConfigResolver.Overrides profile,
                                         HagridConfigResolver.Overrides run, Map<String, String> cli) {
        return HagridConfigResolver.resolve(defaults, profile, run, cli);
    }

    @Test void demandResourceSnapshotIsImmutableAndHigherPrecedenceStillApplies() {
        defaults.resources().setInternalSolverThreads(2);defaults.resources().setMaxActiveSolvers(1);
        var frozen=defaults.resources().snapshot();
        assertThat(resolve(HagridConfigResolver.hannoverTarget(),HagridConfigResolver.Overrides.empty(),Map.of()).resources()).isEqualTo(frozen);
        defaults.resources().setInternalSolverThreads(3);
        assertThat(frozen.internalSolverThreads()).isEqualTo(2);
        assertThat(resolve(HagridConfigResolver.hannoverTarget(),
                HagridConfigResolver.Overrides.of(Map.of("resources.internalSolverThreads","4")),Map.of()).resources().internalSolverThreads()).isEqualTo(4);
        assertThatThrownBy(()->defaults.resources().setMaxActiveSolvers(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->defaults.resources().setInternalSolverThreads(0)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void plainCanonicalDefaultsReadTheConfiguredSolverCapRatherThanDemandWindow() {
        defaults.routing().setSolverMaxRouteDurationSeconds(14400);
        var resolved=resolve(HagridConfigResolver.Overrides.empty(),HagridConfigResolver.Overrides.empty(),Map.of());
        assertThat(resolved.tourRules().maxRouteDurationSeconds()).isEqualTo(14400);
        assertThat(resolved.provenance()).containsEntry("tourRules.maxRouteDurationSeconds",DEFAULTS);
        assertThat(defaults.routing().getMaxRouteDurationSeconds()).isEqualTo(14400);
    }
    @Test
    void precedenceAndUnits() {
        var profile = HagridConfigResolver.hannoverTarget();
        var resolved = resolve(profile, HagridConfigResolver.Overrides.empty(),
                Map.of("fleet.cargoBikeMaxSpeedKmh", "18"));
        assertThat(resolved.tourRules().maxRouteDurationSeconds()).isEqualTo(25200);
        assertThat(resolved.fleet().cargoBikeMaxSpeedMps()).isEqualTo(5.0);
        assertThat(resolved.tourRules().maxDriverTimeSeconds()).isEqualTo(36000);
        assertThat(resolved.tourRules().durationPerParcelSeconds()).isEqualTo(120);
        assertThat(resolved.tourRules().maxDurationPerStopSeconds()).isEqualTo(900);
        assertThat(resolved.tourRules().vehicleAvailabilityBufferSeconds()).isEqualTo(3600);
        assertThat(resolved.provenance()).containsEntry("tourRules.maxRouteDurationSeconds", PROFILE)
                .containsEntry("fleet.cargoBikeMaxSpeedKmh", CLI);
        var run = HagridConfigResolver.Overrides.of(Map.of("tourRules.maxRouteDurationSeconds", "18000"));
        assertThat(resolve(profile, run, Map.of()).tourRules().maxRouteDurationSeconds()).isEqualTo(18000);
        assertThat(resolve(profile, run, Map.of()).provenance())
                .containsEntry("tourRules.maxRouteDurationSeconds", RUN);
        assertThat(resolve(profile, run, Map.of("tourRules.maxRouteDurationSeconds", "20000"))
                .tourRules().maxRouteDurationSeconds()).isEqualTo(20000);
    }

    @Test
    void unsetRunValuesDoNotOverwriteProfile() {
        var resolved = resolve(HagridConfigResolver.hannoverLegacySolver(),
                HagridConfigResolver.Overrides.empty(), Map.of());
        assertThat(resolved.tourRules().maxRouteDurationSeconds()).isEqualTo(25200);
        assertThat(resolved.optimization().initialIterations()).isZero();
        assertThat(resolved.optimization().replanningStartIteration()).hasValue(25);
        assertThat(resolved.optimization().carriersPerGroup()).hasValue(16);
    }

    @Test
    void lausitzHasExplicitLimitAndNoHannoverReplanningSchedule() {
        defaults.setStudyArea(StudyArea.LAUSITZ_HOYERSWERDA);
        defaults.setScenario(HagridConfig.Scenario.LMD_BASELINE);
        var resolved = resolve(HagridConfigResolver.lausitz(7200),
                HagridConfigResolver.Overrides.empty(), Map.of());
        assertThat(resolved.tourRules().maxRouteDurationSeconds()).isEqualTo(7200);
        assertThat(resolved.optimization().replanningStartIteration()).isEmpty();
        assertThat(resolved.optimization().carriersPerGroup()).isEmpty();
        assertThat(resolved.scenario().concept()).isEqualTo(HagridConfig.Scenario.LMD_BASELINE);
        assertThat(resolved.scenario().studyArea()).isEqualTo(StudyArea.LAUSITZ_HOYERSWERDA);
    }

    @Test
    void profilesCannotBeAccidentallyAppliedToAnotherStudyArea() {
        assertThatIllegalArgumentException().isThrownBy(() -> resolve(HagridConfigResolver.lausitz(7200),
                HagridConfigResolver.Overrides.empty(), Map.of())).withMessageContaining("LAUSITZ_HOYERSWERDA");
        defaults.setStudyArea(StudyArea.LAUSITZ_HOYERSWERDA);
        assertThatIllegalArgumentException().isThrownBy(() -> resolve(HagridConfigResolver.hannoverTarget(),
                HagridConfigResolver.Overrides.empty(), Map.of())).withMessageContaining("HANNOVER");
    }

    @Test
    void zoneProviderSelectionRemainsIndependentOfRetiredApproximation() {
        var resolved = resolve(HagridConfigResolver.Overrides.empty(), HagridConfigResolver.Overrides.of(Map.of(
                "routing.isUsingZones", "true", "routing.zoneBasedCachingEnabled", "false",
                "demand.demandBorder", "501", "routing.zoneBasedCachingThresholdMeters", "1500")), Map.of());
        assertThat(resolved.routing().legacyZoneProviderEnabled()).contains(true);
        assertThat(resolved.routing().zoneBasedCachingEnabled()).isFalse();
        assertThat(resolved.routing().zoneBasedCachingThresholdMeters()).isZero();
        assertThat(resolved.demand().demandBorder()).isEqualTo(501);
        assertThat(resolved.demand().dhlBorder()).isEqualTo(450);
    }

    @Test
    void resolutionAndSparseOverridesAreIndependentOfLaterMutation() {
        var entries = new HashMap<String, String>();
        entries.put("tourRules.maxRouteDurationSeconds", "18000");
        var run = HagridConfigResolver.Overrides.of(entries);
        entries.put("tourRules.maxRouteDurationSeconds", "1");
        var resolved = resolve(HagridConfigResolver.Overrides.empty(), run, Map.of());
        defaults.routing().setMaxRouteDurationSeconds(2);
        defaults.vehicles().setActiveSizes(java.util.List.of("bike"));
        assertThat(resolved.tourRules().maxRouteDurationSeconds()).isEqualTo(18000);
        assertThat(resolved.fleet().activeSizes()).containsExactly("m", "l");
        assertThatThrownBy(() -> resolved.fleet().activeSizes().add("s"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> resolved.provenance().put("x", RUN))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void bikeDefaultIsTwentyKmhAndResourceLimitsRemainExplicit() {
        var resolved = resolve(HagridConfigResolver.Overrides.empty(), HagridConfigResolver.Overrides.empty(), Map.of());
        assertThat(resolved.fleet().cargoBikeMaxSpeedMps()).isEqualTo(20.0 / 3.6);
        assertThat(resolved.resources().maxActiveSolvers()).isEmpty();
        assertThat(resolved.resources().internalSolverThreads()).isEqualTo(1);
        assertThat(resolved.optimization().replanningIterations()).isEmpty();
        assertThat(resolved.provenance()).containsEntry("fleet.cargoBikeMaxSpeedKmh", DEFAULTS);
        assertThat(resolved.effectiveValues()).containsEntry("tourRules.maxRouteDurationSeconds", "25200");
    }

    @Test
    void existingFleetConfigurationSuppliesBikeSpeedBeforeExplicitOverrides() {
        assertThat(defaults.vehicles().getCargoBikeMaxSpeedKmh()).isEqualTo(20);
        defaults.vehicles().setCargoBikeMaxSpeedKmh(25);
        assertThat(resolve(HagridConfigResolver.Overrides.empty(), HagridConfigResolver.Overrides.empty(), Map.of())
                .fleet().cargoBikeMaxSpeedMps()).isEqualTo(25.0 / 3.6);
        assertThat(resolve(HagridConfigResolver.Overrides.empty(), HagridConfigResolver.Overrides.empty(),
                Map.of("fleet.cargoBikeMaxSpeedKmh", "18")).fleet().cargoBikeMaxSpeedMps()).isEqualTo(5);
    }

    @Test
    void invalidBikeCapsAreRejectedAtTheExistingConfigurationEntry() {
        for (double speed : new double[]{0, -20, Double.NaN, Double.POSITIVE_INFINITY, Double.MIN_VALUE}) {
            assertThatIllegalArgumentException().isThrownBy(() -> defaults.vehicles().setCargoBikeMaxSpeedKmh(speed))
                    .withMessageContaining("cargoBikeMaxSpeedKmh");
        }
    }

    @Test
    void invalidValuesFailWithTheirCanonicalKey() {
        for (String value : java.util.List.of("0", "-18", "NaN", "Infinity", "abc")) {
            assertThatIllegalArgumentException().isThrownBy(() -> resolve(HagridConfigResolver.Overrides.empty(),
                    HagridConfigResolver.Overrides.empty(), Map.of("fleet.cargoBikeMaxSpeedKmh", value)))
                    .withMessageContaining("fleet.cargoBikeMaxSpeedKmh");
        }
        for (var entry : Map.of("resources.internalSolverThreads", "0", "resources.maxActiveSolvers", "-1",
                "optimization.initialIterations", "-1", "tourRules.vehicleAvailabilityBufferSeconds", "-1",
                "routing.zoneBasedCachingEnabled", "yes", "demand.demandBorder", "0").entrySet()) {
            assertThatIllegalArgumentException().isThrownBy(() -> resolve(HagridConfigResolver.Overrides.empty(),
                    HagridConfigResolver.Overrides.empty(), Map.of(entry.getKey(), entry.getValue())))
                    .withMessageContaining(entry.getKey());
        }
    }

    @Test
    void unknownKeysAndConflictingUnitsCannotBeSilentlyIgnored() {
        assertThatIllegalArgumentException().isThrownBy(() -> resolve(HagridConfigResolver.Overrides.empty(),
                HagridConfigResolver.Overrides.empty(), Map.of("routing.zoneCachingEnabld", "true")))
                .withMessageContaining("routing.zoneCachingEnabld");
        assertThatIllegalArgumentException().isThrownBy(() -> resolve(HagridConfigResolver.Overrides.empty(),
                HagridConfigResolver.Overrides.empty(), Map.of("fleet.cargoBikeMaxSpeedMps", "18")))
                .withMessageContaining("fleet.cargoBikeMaxSpeedMps");
    }

    @Test
    void invalidDefaultRulesAreValidatedToo() {
        defaults.routing().setMaxDriverTimeMinutes(Double.NaN);
        assertThatIllegalArgumentException().isThrownBy(() -> resolve(HagridConfigResolver.Overrides.empty(),
                HagridConfigResolver.Overrides.empty(), Map.of())).withMessageContaining("maxDriverTimeSeconds");
    }

    @Test
    void invertedDeliveryWindowIsRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> resolve(HagridConfigResolver.Overrides.empty(),
                HagridConfigResolver.Overrides.empty(), Map.of("tourRules.deliveryWindowStartSeconds", "80000",
                        "tourRules.deliveryWindowEndSeconds", "70000"))).withMessageContaining("deliveryWindow");
    }
}
