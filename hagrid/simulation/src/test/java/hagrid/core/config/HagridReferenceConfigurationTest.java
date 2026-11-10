package hagrid.core.config;

import hagrid.core.simulation.SimulationRunnerUtils;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

class HagridReferenceConfigurationTest {
    @Test void editableReferenceLoadsAndKeepsTheSharedCapAcrossProfiles() {
        Path directory=Path.of("").toAbsolutePath();
        while(directory!=null && !Files.exists(directory.resolve("config/hagrid.properties"))) directory=directory.getParent();
        assertThat(directory).isNotNull();
        Path file=directory.resolve("config/hagrid.properties");
        assertThat(HagridConfigFile.load(file).values()).containsEntry("tourRules.maxRouteDurationSeconds","25200");
        for(String concept:java.util.List.of("basecase","lmd_baseline","drt_modular","drt_shareduse")) {
            var cfg=SimulationRunnerUtils.parseScenario("config="+file.toString().replace('\\','/')+",concept="+concept+",date=2025-05-13,tag=reference");
            var resolved=cfg.getResolvedRoutingConfig();
            assertThat(resolved.tourRules().maxRouteDurationSeconds()).isEqualTo(25200);
            assertThat(resolved.scoring().maxWorkDurationSeconds()).isEqualTo(25200);
            assertThat(resolved.fleet().cargoBikeMaxSpeedMps()).isEqualTo(20/3.6);
            assertThat(resolved.tourRules().deliveryWindowStartSeconds()).isEqualTo(concept.equals("basecase")?28800:27000);
        }
    }
}
