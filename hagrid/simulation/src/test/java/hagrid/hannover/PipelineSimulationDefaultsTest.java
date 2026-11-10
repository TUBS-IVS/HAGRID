package hagrid.hannover;

import hagrid.core.simulation.SimulationRunnerUtils;
import hagrid.hannover.pipeline.ScenarioConfig;
import hagrid.hannover.util.SimulationBatGenerator;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class PipelineSimulationDefaultsTest {
    @Test void configuredPipelineDefaultsGenerateExactSimulationArgumentsBeforeAnyInputLoad() throws Exception {
        var settings=assertDoesNotThrow(()->new SimulationBatGenerator.SimulationSettings(
                (int)value("MAX_ITER"),(int)value("SIM_JSPRIT_ITER"),(boolean)value("ZONE_CACHING"),
                (int)value("ZONE_THRESHOLD"),(double)value("U_TURN_PENALTY"),(boolean)value("WRITE_DASHBOARD")));
        var generate=SimulationBatGenerator.class.getDeclaredMethod("buildScenarioArgs",ScenarioConfig[].class,SimulationBatGenerator.SimulationSettings.class);
        generate.setAccessible(true);
        var args=(List<?>)generate.invoke(null,value("SCENARIOS"),settings);
        assertThat(args).isNotEmpty();
        for(var argument:args) {
            assertThat(argument.toString()).doesNotContain("zoneCaching", "zoneThreshold");
            var launch=SimulationRunnerUtils.parseScenario(argument.toString());
            assertThat(launch.getResolvedRoutingConfig().routing().zoneBasedCachingEnabled()).isFalse();
            assertThat(launch.getJspritIterations()).isEqualTo(settings.jspritIter());
        }
    }
    private static Object value(String name) throws Exception {
        var field=HAGRID2MATSimPipelineRunner.class.getDeclaredField(name);field.setAccessible(true);return field.get(null);
    }
}
