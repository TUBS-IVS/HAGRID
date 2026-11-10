package hagrid.core.config;
import hagrid.core.simulation.SimulationRunnerUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;
class CentralConfigurationPathsTest {
    @TempDir Path temp;
    @Test void configuredSimulationInputsAndOutputsResolveAgainstTheFileAndAreCapturedOnce() throws Exception {
        Path file=Files.writeString(temp.resolve("hagrid.properties"),"run.concept=basecase\nrun.date=2025-05-13\n"
                +"paths.simulationNetwork=data/car.xml\npaths.bikeNetwork=data/bike.xml\npaths.networkChangeEvents=data/events.xml\n"
                +"paths.deliveryCarriers=data/delivery.xml\npaths.jspritCacheDirectory=cache\npaths.outputDirectory=output\n");
        var cfg=SimulationRunnerUtils.parseScenario("config="+file.toString().replace('\\','/'));
        Files.writeString(file,"invalid replacement");
        assertThat(cfg.getCarNetworkPath()).isEqualTo(temp.resolve("data/car.xml"));
        assertThat(cfg.getBikeNetworkPath()).isEqualTo(temp.resolve("data/bike.xml"));
        assertThat(cfg.getNetworkChangeEventPath()).isEqualTo(temp.resolve("data/events.xml"));
        assertThat(cfg.getDeliveryCarrierPath()).isEqualTo(temp.resolve("data/delivery.xml"));
        assertThat(cfg.getJspritCacheDir()).isEqualTo(temp.resolve("cache"));
        assertThat(cfg.getOutputDirectory()).isEqualTo(temp.resolve("output"));
    }
    @Test void dashboardUsesCapturedFileAndExplicitCliRatherThanRawDefaultFlags() throws Exception {
        Path file=Files.writeString(temp.resolve("hagrid.properties"),"run.concept=basecase\nrun.date=2025-05-13\nresults.writeDashboard=true\n");
        var cfg=SimulationRunnerUtils.parseScenario("config="+file.toString().replace('\\','/'));
        var override=SimulationRunnerUtils.parseScenario("config="+file.toString().replace('\\','/')+",results.writeDashboard=false");
        Files.writeString(file,"invalid replacement");
        assertThat(cfg.isWriteDashboard()).isTrue();
        assertThat(override.isWriteDashboard()).isFalse();
    }
    @Test void explicitPathCliOverridesTheCapturedFile() throws Exception {
        Path file=Files.writeString(temp.resolve("hagrid.properties"),"run.concept=basecase\nrun.date=2025-05-13\npaths.simulationNetwork=file.xml\n");
        Path cli=temp.resolve("override.xml");
        var cfg=SimulationRunnerUtils.parseScenario("config="+file.toString().replace('\\','/')+",paths.simulationNetwork="+cli.toString().replace('\\','/'));
        assertThat(cfg.getCarNetworkPath()).isEqualTo(cli);
    }
}
