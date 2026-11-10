package hagrid.hannover.pipeline;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import hagrid.core.HagridConfig;
import hagrid.core.HagridPaths;
import hagrid.core.routing.ThreadingType;
import hagrid.hannover.demand.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;

import java.nio.file.Path;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class PipelineFailurePropagationTest {
    @TempDir Path directory;

    @Test void routingFailureReachesCallerAndDoesNotPublishSuccessfulTiming() {
        var scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        var cause = new IllegalStateException("deliberate solver failure");
        var calls = new AtomicInteger();
        var injector = Guice.createInjector(new AbstractModule() {
            @Override protected void configure() {
                bind(Scenario.class).toInstance(scenario);
                bind(HagridConfig.class).toInstance(new HagridConfig());
                bind(NetworkProcessor.class).toInstance(new NetworkProcessor() { public void run() {} });
                bind(LogisticsDataProcessor.class).toInstance(new LogisticsDataProcessor() { public void run() {} });
                bind(DemandProcessor.class).toInstance(new DemandProcessor() { public void run() {} });
                bind(DeliveryGenerator.class).toInstance(new DeliveryGenerator() { public void run() {} });
                bind(CarrierGenerator.class).toInstance(new CarrierGenerator() { public void run() {} });
                bind(SupplyCarrierGenerator.class).toInstance(new SupplyCarrierGenerator() { public void run() {} });
                bind(CarrierRouter.class).toInstance(new CarrierRouter() {
                    public void run() { calls.incrementAndGet(); throw cause; }
                });
            }
        });
        var executor = new PipelineExecutor(injector);
        assertThatThrownBy(() -> executor.executeAll(false, true))
                .isInstanceOf(PipelineStepException.class).hasMessageContaining("Routing").hasCause(cause);
        assertThat(calls.get()).isEqualTo(1);
        assertThat(scenario.getScenarioElement("pipelineTiming")).isNull();
    }

    @Test void directRoutingEntryPointPreservesCause() {
        var cause = new IllegalArgumentException("invalid vehicle");
        var injector = Guice.createInjector(new AbstractModule() {
            @Override protected void configure() {
                bind(Scenario.class).toInstance(ScenarioUtils.createScenario(ConfigUtils.createConfig()));
                bind(HagridConfig.class).toInstance(new HagridConfig());
                bind(CarrierRouter.class).toInstance(new CarrierRouter() { public void run() { throw cause; } });
            }
        });
        assertThatThrownBy(() -> new PipelineExecutor(injector).runRouter(ThreadingType.COMPLETABLE_FUTURE))
                .isInstanceOf(PipelineStepException.class).hasCause(cause);
    }

    @Test void missingVehicleTypesFailBeforeAnyLogisticsPublication() {
        var scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        var config = new HagridConfig();
        config.paths().setVehicleTypes(directory.resolve("missing-types.xml").toString());
        var processor = inject(new LogisticsDataProcessor(), scenario, config);
        assertThatThrownBy(processor::run).isInstanceOf(PipelineStepException.class)
                .hasMessageContaining("Logistics Data").hasStackTraceContaining("missing-types.xml");
        for (var key : java.util.List.of("hubList", "parcelLockerList", "shippingPointList", "hanoverGeoData", "carrierVehicleTypes")) {
            assertThat(scenario.getScenarioElement(key)).as(key).isNull();
        }
    }

    @Test void missingDeliveryPrerequisitesAreNotReportedAsSuccess() {
        var scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        var processor = inject(new DeliveryGenerator(), scenario, new HagridConfig());
        assertThatThrownBy(processor::run).isInstanceOf(PipelineStepException.class)
                .hasMessageContaining("Delivery Generation").hasRootCauseMessage("Carrier demand data is missing in the scenario.");
        assertThat(scenario.getScenarioElement("deliveries")).isNull();
    }

    @Test void fullScenarioRunnerDoesNotReplacePriorSummaryAfterRequiredStageFailure() throws Exception {
        Path xml = directory.resolve("input/hannover/config/config.xml");
        Files.createDirectories(xml.getParent());
        var matsim = ConfigUtils.createConfig(); matsim.network().setInputFile("../../../network.xml");
        new org.matsim.core.config.ConfigWriter(matsim).write(xml.toString());
        writeNetwork();
        Path properties = Files.writeString(directory.resolve("hagrid.properties"),
                "paths.vehicleTypes=missing-types.xml\nrun.seed=9876543210\n");
        var definition = ScenarioConfig.builder().configurationFile(properties).build();
        var date = LocalDate.of(2025, 5, 13);
        var paths = new HagridPaths(directory); paths.initializeRun(definition.createRunId("basecase", date));
        Path summary = Path.of(paths.scenarioSummary());
        Files.createDirectories(summary.getParent()); Files.writeString(summary, "previous verified run");
        assertThatThrownBy(() -> new ScenarioRunner(definition, directory).runSingleScenario("basecase", date))
                .isInstanceOf(PipelineStepException.class).hasMessageContaining("Logistics Data");
        assertThat(Files.readString(summary)).isEqualTo("previous verified run");
        assertThat(CacheConfig.getGlobal()).isEmpty();
    }

    @Test void fullRunnerRejectsNetworkConflictBeforeCreatingOutputDirectories() throws Exception {
        Path xml = directory.resolve("input/hannover/config/config.xml");
        Files.createDirectories(xml.getParent());
        var matsim = ConfigUtils.createConfig(); matsim.network().setInputFile("../../../network.xml");
        new org.matsim.core.config.ConfigWriter(matsim).write(xml.toString());
        Path properties = Files.writeString(directory.resolve("hagrid.properties"), "paths.network=other.xml\n");
        var definition = ScenarioConfig.builder().configurationFile(properties).build();
        assertThatThrownBy(() -> new ScenarioRunner(definition, directory)
                .runSingleScenario("basecase", LocalDate.of(2025, 5, 13)))
                .isInstanceOf(PipelineStepException.class).hasMessageContaining("Configuration")
                .hasStackTraceContaining("Conflicting network inputs");
        assertThat(directory.resolve("hagrid-output")).doesNotExist();
    }

    private void writeNetwork() {
        var network = org.matsim.core.network.NetworkUtils.createNetwork();
        var a = org.matsim.core.network.NetworkUtils.createAndAddNode(network, org.matsim.api.core.v01.Id.createNodeId("a"), new org.matsim.api.core.v01.Coord(0, 0));
        var b = org.matsim.core.network.NetworkUtils.createAndAddNode(network, org.matsim.api.core.v01.Id.createNodeId("b"), new org.matsim.api.core.v01.Coord(100, 0));
        for (var link : java.util.List.of(
                org.matsim.core.network.NetworkUtils.createAndAddLink(network, org.matsim.api.core.v01.Id.createLinkId("out"), a, b, 100, 10, 1000, 1),
                org.matsim.core.network.NetworkUtils.createAndAddLink(network, org.matsim.api.core.v01.Id.createLinkId("back"), b, a, 100, 10, 1000, 1))) {
            link.setAllowedModes(java.util.Set.of("car"));
        }
        new org.matsim.api.core.v01.network.NetworkWriter(network).write(directory.resolve("network.xml").toString());
    }

    private static <T> T inject(T processor, Scenario scenario, HagridConfig config) {
        Guice.createInjector(new AbstractModule() {
            @Override protected void configure() {
                bind(Scenario.class).toInstance(scenario);
                bind(HagridConfig.class).toInstance(config);
            }
        }).injectMembers(processor);
        return processor;
    }
}
