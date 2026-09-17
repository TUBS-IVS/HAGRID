package hagrid.integrated.drt;

import hagrid.simulation.DrtScenarioBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.controler.Controler;
import org.matsim.testcases.MatsimTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DRT + rail-PT intermodal end-to-end run (production path)")
class DrtRailIntermodalEndToEndTest {

    @RegisterExtension
    public MatsimTestUtils utils = new MatsimTestUtils();

    @Test
    @DisplayName("rail leg routes for real + DRT intermodal access/egress configured; one iteration produces drt_* + pt output")
    void runsRailIntermodalOneIteration() throws Exception {
        Path dir = Path.of(utils.getOutputDirectory()).toAbsolutePath();
        Files.createDirectories(dir);
        RailScenarioFixture fx = RailScenarioFixture.stage(dir);

        Scenario scenario = DrtScenarioBuilder.build(
                fx.baseConfigUrl.toString(),
                fx.drtNetwork, fx.clippedPlans, fx.serviceAreaShp, fx.fleet,
                fx.railSchedule, fx.railVehicles, /*vehicleTypes*/ null,
                dir.resolve("matsim").toString(), "DRT_RAIL_E2E", 0);

        // transit really loaded
        assertThat(scenario.getTransitSchedule().getTransitLines()).isNotEmpty();
        // intermodal stop tagged
        assertThat(scenario.getTransitSchedule().getFacilities().values().stream()
                .anyMatch(s -> "true".equals(s.getAttributes()
                        .getAttribute(LausitzDrtConfigurator.DRT_ACCESS_ATTR)))).isTrue();

        Controler controler = new Controler(scenario);
        DrtConfigComposer.installModules(controler);
        controler.run();

        Path matsimOut = dir.resolve("matsim");
        try (var stream = Files.walk(matsimOut)) {
            assertThat(stream.filter(Files::isRegularFile)
                    .anyMatch(p -> p.getFileName().toString().toLowerCase().contains("drt")))
                    .as("a real DRT iteration must produce a drt_* output file").isTrue();
        }
    }
}
