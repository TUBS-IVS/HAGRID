package hagrid.hannover.pipeline;

import hagrid.core.HagridConfig;
import hagrid.core.config.HagridConfigResolver;
import hagrid.core.config.SimulationRoutingConfigAdapter;
import hagrid.core.simulation.SimulationRunnerUtils;
import hagrid.hannover.util.SimulationBatGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class CargoBikeConfigurationFlowTest {
    @TempDir Path root;

    private void apply(ScenarioConfig scenario, HagridConfig target) throws Exception {
        // Exercise the existing mapping without launching the input-data pipeline.
        var runner = new ScenarioRunner(scenario, root);
        var method = ScenarioRunner.class.getDeclaredMethod("applyScenarioConfig", HagridConfig.class,
                String.class, LocalDate.class, CacheConfig.class);
        method.setAccessible(true);
        method.invoke(runner, target, "basecase", LocalDate.of(2025, 5, 13), CacheConfig.builder().enabled(false).build());
    }

    private String startArgument(ScenarioConfig scenario) throws Exception {
        // Build the actual arguments without writing or running a machine-specific batch file.
        var method = SimulationBatGenerator.class.getDeclaredMethod("buildScenarioArgs", ScenarioConfig[].class,
                SimulationBatGenerator.SimulationSettings.class);
        method.setAccessible(true);
        var arguments = (List<?>) method.invoke(null, new ScenarioConfig[]{scenario},
                new SimulationBatGenerator.SimulationSettings(150, 100, false, 0, 1, false));
        return arguments.getFirst().toString();
    }

    @Test
    void configuredSolverDurationSurvivesDemandAndGeneratedSimulationStart() throws Exception {
        var scenario = ScenarioConfig.builder().solverMaxRouteDurationSeconds(14400).build();
        var target = new HagridConfig();
        apply(scenario, target);
        assertThat(target.routing().getSolverMaxRouteDurationSeconds()).isEqualTo(14400);
        assertThat(target.routing().getMaxRouteDurationSeconds()).isEqualTo(14400);
        var argument = startArgument(scenario);
        assertThat(argument).contains("solverMaxRouteDuration=14400");
        var launch = SimulationRunnerUtils.parseScenario(argument);
        var resolved = SimulationRoutingConfigAdapter.resolve(target, launch, HagridConfigResolver.Overrides.empty());
        assertThat(resolved.tourRules().maxRouteDurationSeconds()).isEqualTo(14400);
    }

    @Test
    void omittedSolverDurationPreservesTheExistingConfiguredValue() throws Exception {
        var scenario = ScenarioConfig.builder().build();
        var target = new HagridConfig();
        target.routing().setSolverMaxRouteDurationSeconds(18000);
        apply(scenario, target);
        assertThat(target.routing().getSolverMaxRouteDurationSeconds()).isEqualTo(18000);
        assertThat(startArgument(scenario)).doesNotContain("solverMaxRouteDuration");
        assertThat(scenario.getPipelineSettings().getSolverMaxRouteDurationSeconds()).isEmpty();
    }

    @Test
    void solverDurationBuilderIsValidatedAndCapturedPerScenario() {
        var builder = ScenarioConfig.builder().solverMaxRouteDurationSeconds(14400);
        var first = builder.build();
        var second = builder.solverMaxRouteDurationSeconds(18000).build();
        assertThat(first.getPipelineSettings().getSolverMaxRouteDurationSeconds()).hasValue(14400);
        assertThat(second.getPipelineSettings().getSolverMaxRouteDurationSeconds()).hasValue(18000);
        for (var invalid : new int[]{0, -1})
            assertThatIllegalArgumentException().isThrownBy(() -> ScenarioConfig.builder().solverMaxRouteDurationSeconds(invalid));
    }

    @Test
    void configuredCapSurvivesTheExistingPipelineAndSimulationArguments() throws Exception {
        var scenario = ScenarioConfig.builder().cargoBikeMaxSpeedKmh(18).build();
        var target = new HagridConfig();
        apply(scenario, target);
        assertThat(target.vehicles().getCargoBikeMaxSpeedKmh()).isEqualTo(18);
        String argument = startArgument(scenario);
        assertThat(argument).contains("cargoBikeMaxSpeedKmh=18.0");
        var launch = SimulationRunnerUtils.parseScenario(argument);
        var resolved = SimulationRoutingConfigAdapter.resolve(target, launch, HagridConfigResolver.Overrides.empty());
        assertThat(resolved.fleet().cargoBikeMaxSpeedMps()).isEqualTo(5);
        assertThat(resolved.optimization().initialIterations()).isZero();
        assertThat(resolved.optimization().replanningIterations()).hasValue(100);
    }

    @Test
    void unsetBuilderSpeedPreservesTheCommonDefaultAndExistingProfileValue() throws Exception {
        var scenario = ScenarioConfig.builder().build();
        assertThat(scenario.getVehicleConfig().getCargoBikeMaxSpeedKmh()).isEmpty();
        var defaults = new HagridConfig();
        apply(scenario, defaults);
        assertThat(defaults.vehicles().getCargoBikeMaxSpeedKmh()).isEqualTo(20);
        var customized = new HagridConfig();
        customized.vehicles().setCargoBikeMaxSpeedKmh(25);
        apply(scenario, customized);
        assertThat(customized.vehicles().getCargoBikeMaxSpeedKmh()).isEqualTo(25);
        assertThat(startArgument(scenario)).doesNotContain("cargoBikeMaxSpeedKmh");
    }

    @Test
    void changingBuilderAfterBuildDoesNotChangeAnEarlierScenario() {
        var builder = ScenarioConfig.builder().cargoBikeMaxSpeedKmh(18);
        var earlier = builder.build();
        var later = builder.cargoBikeMaxSpeedKmh(22).build();
        assertThat(earlier.getVehicleConfig().getCargoBikeMaxSpeedKmh()).hasValue(18);
        assertThat(later.getVehicleConfig().getCargoBikeMaxSpeedKmh()).hasValue(22);
    }

    @Test
    void invalidSpeedsFailAtTheBuilderEntry() {
        for (double speed : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY, Double.MIN_VALUE}) {
            assertThatIllegalArgumentException().isThrownBy(() -> ScenarioConfig.builder().cargoBikeMaxSpeedKmh(speed))
                    .withMessageContaining("cargoBikeMaxSpeedKmh");
        }
    }
}
