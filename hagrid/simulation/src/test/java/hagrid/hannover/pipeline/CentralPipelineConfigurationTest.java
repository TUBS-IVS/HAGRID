package hagrid.hannover.pipeline;

import hagrid.core.HagridConfig;
import hagrid.core.simulation.SimulationRunnerUtils;
import hagrid.hannover.util.SimulationBatGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class CentralPipelineConfigurationTest {
    @TempDir Path root;
    @Test void sameFileConfiguresDemandAndGeneratedSimulationWithoutHiddenLaunchDefaults() throws Exception {
        Path file=Files.writeString(root.resolve("hagrid.properties"),"tourRules.maxRouteDurationSeconds=14400\n"
                +"tourRules.vehicleAvailabilityBufferSeconds=900\ntourRules.deliveryWindowStartSeconds=32400\n"
                +"tourRules.deliveryWindowEndSeconds=68400\nsimulation.maxIterations=42\nsimulation.jspritIterations=20\n");
        var scenario=ScenarioConfig.builder().configurationFile(file).build();
        var target=new HagridConfig();
        var runner=new ScenarioRunner(scenario,root);
        var apply=ScenarioRunner.class.getDeclaredMethod("applyScenarioConfig",HagridConfig.class,String.class,LocalDate.class,CacheConfig.class);
        apply.setAccessible(true);apply.invoke(runner,target,"basecase",LocalDate.of(2025,5,13),CacheConfig.builder().enabled(false).build());
        assertThat(target.getMaxRouteDuration()).isEqualTo(14400);
        assertThat(target.routing().getVehicleAvailabilityBufferSeconds()).isEqualTo(900);
        assertThat(target.routing().getDeliveryTimeWindow().getStart()).isEqualTo(32400);
        var method=SimulationBatGenerator.class.getDeclaredMethod("buildScenarioArgs",ScenarioConfig[].class,SimulationBatGenerator.SimulationSettings.class);
        method.setAccessible(true);
        var args=(List<?>)method.invoke(null,new ScenarioConfig[]{scenario},new SimulationBatGenerator.SimulationSettings(150,100,false,0,1,false));
        var launch=SimulationRunnerUtils.parseScenario(args.getFirst().toString());
        assertThat(launch.getMaxIterations()).isEqualTo(42);
        assertThat(launch.getJspritIterations()).isEqualTo(20);
        assertThat(launch.getResolvedRoutingConfig().tourRules().maxRouteDurationSeconds()).isEqualTo(target.getMaxRouteDuration());
        assertThat(launch.getResolvedRoutingConfig().tourRules().vehicleAvailabilityBufferSeconds()).isEqualTo(900);
    }
    @Test void capturedSectionOptionsSurviveBothScenarioApplications() throws Exception {
        Path file=Files.writeString(root.resolve("sections.properties"),"routing.sectionMaxSpanMeters=100\nrouting.haltCatalogFile=halts.json\nrouting.sectionFastRegret=true\nresources.internalSolverThreads=2\n");
        var runner=new ScenarioRunner(ScenarioConfig.builder().configurationFile(file).build(),root);
        var apply=ScenarioRunner.class.getDeclaredMethod("applyScenarioConfig",HagridConfig.class,String.class,LocalDate.class,CacheConfig.class);apply.setAccessible(true);
        var target=new HagridConfig();apply.invoke(runner,target,"basecase",LocalDate.of(2025,5,13),null);
        assertThat(hagrid.core.config.SectionSettings.from(target.getAdditionalConfigurationValues()).fastRegret()).isTrue();assertThat(target.resources().getInternalSolverThreads()).isEqualTo(2);
        Files.writeString(file,"routing.sectionFastRegret=false\n");
        apply.invoke(runner,target,"basecase",LocalDate.of(2025,5,13),CacheConfig.builder().enabled(false).build());
        assertThat(hagrid.core.config.SectionSettings.from(target.getAdditionalConfigurationValues()).fastRegret()).isTrue();assertThat(target.resources().getInternalSolverThreads()).isEqualTo(2);
    }
    @Test void fileBindingsAlsoReachSupplyHubsProvidersAndNetwork() throws Exception {
        Path file=Files.writeString(root.resolve("hagrid.properties"),"supply.vehicleCapacity=800\nhubs.parcelLockerDemand=15\n"
                +"providers.deliveryRates.dhl=93\nnetwork.minLinkLengthMeters=12\nfleet.activeSizes=m,l\n");
        var c=new HagridConfig();c.loadConfiguration(file);
        assertThat(c.supply().getVehicleCapacity()).isEqualTo(800);
        assertThat(c.hubs().getParcelLockerDemand()).isEqualTo(15);
        assertThat(c.providers().getDeliveryRate("dhl")).isEqualTo(93);
        assertThat(c.network().getMinLinkLengthMeters()).isEqualTo(12);
        assertThat(c.vehicles().getActiveSizes()).containsExactly("m","l");
    }
}
