package hagrid.hannover;

import hagrid.core.simulation.SimulationRunnerUtils;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertAll;

class HannoverBatchLaunchersTest {
    @Test void activeBatchLaunchersUseExactRoutingWithTheSameConfiguredBudgets() {
        assertAll(List.of("run_hagrid_sim.bat","run_stepB_v2dev_batch.bat").stream()
                .map(name->(org.junit.jupiter.api.function.Executable)()->{
            String batch=Files.readString(Path.of("../../runs/hannover").resolve(name));
            assertThat(batch).doesNotContain("zoneCaching=", "zoneThreshold=");
            var args=new ArrayList<String>();
            for(String line:batch.lines().toList()) {
                if(line.startsWith("set \"SCENARIO_"))args.add(line.substring(line.indexOf('=')+1,line.length()-1));
                if(line.startsWith("set \"ARGS_TAIL="))args.add("concept=basecase,date=2025-05-13,tag=160v2"+line.substring(line.indexOf('=')+1,line.length()-1));
            }
            assertThat(args).isNotEmpty();
            for(String scenario:args) {
                var launch=SimulationRunnerUtils.parseScenario(scenario);
                assertThat(launch.getMaxIterations()).isEqualTo(150);
                assertThat(launch.getJspritIterations()).isEqualTo(1000);
                assertThat(launch.getResolvedRoutingConfig().routing().zoneBasedCachingEnabled()).isFalse();
            }
        }));
    }
}
