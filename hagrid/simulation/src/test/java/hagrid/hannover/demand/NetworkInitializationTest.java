package hagrid.hannover.demand;

import com.google.inject.Guice;
import hagrid.core.HagridConfig;
import hagrid.core.HagridPaths;
import hagrid.hannover.HagridModule;
import hagrid.hannover.pipeline.PipelineStepException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.ConfigWriter;
import org.matsim.core.network.NetworkUtils;
import org.matsim.api.core.v01.network.NetworkWriter;

import java.nio.file.*;
import java.time.LocalDate;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class NetworkInitializationTest {
    @TempDir Path directory;

    @Test void sameConfiguredNetworkIsImportedExactlyOnce() throws Exception {
        Path input = network();
        var effective = effectiveConfig();
        effective.paths().setNetwork(input.toString());
        effective.setAdditionalConfigurationValues(java.util.Map.of("run.seed", "9876543210"));
        var injector = Guice.createInjector(new HagridModule(xml("./network.xml"), effective));
        var scenario = injector.getInstance(Scenario.class);
        assertThat(scenario.getNetwork().getLinks()).isEmpty();
        assertThat(injector.getInstance(HagridConfig.class)).isSameAs(effective);
        assertThat(scenario.getConfig().global().getRandomSeed()).isEqualTo(9876543210L);
        var reads = new AtomicInteger();
        var processor = new NetworkProcessor() {
            @Override protected void readNetwork(Network target, String path) {
                reads.incrementAndGet(); super.readNetwork(target, path);
            }
        };
        injector.injectMembers(processor);
        processor.run();
        assertThat(reads.get()).isEqualTo(1);
        assertThat(scenario.getNetwork().getLinks()).hasSize(2);
        for (var key : java.util.List.of("carFilteredNetwork", "parcelServiceNetwork")) {
            assertThat(((Network) scenario.getScenarioElement(key)).getLinks()).hasSize(2);
        }
        assertThatThrownBy(processor::run).isInstanceOf(PipelineStepException.class);
        assertThat(reads.get()).isEqualTo(1);
    }

    @Test void xmlNetworkCanReplaceAnUnspecifiedHagridDefault() throws Exception {
        var effective = effectiveConfig();
        network();
        var injector = Guice.createInjector(new HagridModule(xml("network.xml"), effective));
        assertThat(Path.of(effective.getNetworkXmlPath())).isEqualTo(directory.resolve("network.xml").toRealPath());
        injector.getInstance(NetworkProcessor.class).run();
        assertThat(injector.getInstance(Scenario.class).getNetwork().getLinks()).hasSize(2);
    }

    @Test void conflictingExplicitNetworksFailBeforeOutput() throws Exception {
        var effective = effectiveConfig();
        effective.paths().setNetwork(directory.resolve("other.xml").toString());
        assertThatThrownBy(() -> new HagridModule(xml("network.xml"), effective))
                .isInstanceOf(PipelineStepException.class).hasMessageContaining("Configuration")
                .hasRootCauseMessage("Conflicting network inputs: MATSim=" + directory.resolve("network.xml")
                        + ", HAGRID=" + directory.resolve("other.xml"));
        assertThat(Path.of(effective.io().networkFiltered())).doesNotExist();
    }

    @Test void explicitNetworkSurvivesDatePathDerivation() throws Exception {
        var effective = effectiveConfig();
        String custom = directory.resolve("custom.xml").toString();
        effective.paths().setNetwork(custom);
        effective.setSimulationDate(LocalDate.of(2025, 5, 14));
        assertThat(effective.getNetworkXmlPath()).isEqualTo(custom);
    }

    @Test void failedOutputPreservesExistingNetworkAndSubnetRegistrations() throws Exception {
        var effective = effectiveConfig();
        effective.paths().setNetwork(network().toString());
        var injector = Guice.createInjector(new HagridModule(xml("network.xml"), effective));
        var scenario = injector.getInstance(Scenario.class);
        var original = scenario.getNetwork();
        Path output = Path.of(effective.io().networkFiltered());
        Files.createDirectories(output);
        Files.writeString(output.resolve("marker"), "keep");
        assertThatThrownBy(() -> injector.getInstance(NetworkProcessor.class).run()).isInstanceOf(PipelineStepException.class);
        assertThat(scenario.getNetwork()).isSameAs(original);
        assertThat(original.getLinks()).isEmpty();
        assertThat(scenario.getScenarioElement("carFilteredNetwork")).isNull();
        assertThat(scenario.getScenarioElement("parcelServiceNetwork")).isNull();
        assertThat(Files.readString(output.resolve("marker"))).isEqualTo("keep");
    }

    @Test void malformedNetworkNeverPublishesPartialNodesOrOverwritesPriorOutput() throws Exception {
        var effective = effectiveConfig();
        Path input = Files.writeString(directory.resolve("network.xml"), "<network><nodes><node id='a' x='0' y='0'/></nodes><links><link");
        effective.paths().setNetwork(input.toString());
        var injector = Guice.createInjector(new HagridModule(xml("network.xml"), effective));
        var scenario = injector.getInstance(Scenario.class);
        Path output = Path.of(effective.io().networkFiltered());
        Files.createDirectories(output.getParent()); Files.writeString(output, "prior output");
        assertThatThrownBy(() -> injector.getInstance(NetworkProcessor.class).run()).isInstanceOf(PipelineStepException.class);
        assertThat(scenario.getNetwork().getNodes()).isEmpty();
        assertThat(scenario.getScenarioElement("carFilteredNetwork")).isNull();
        assertThat(Files.readString(output)).isEqualTo("prior output");
    }

    private HagridConfig effectiveConfig() throws Exception {
        var config = new HagridConfig();
        var field = HagridConfig.class.getDeclaredField("hagridPaths"); field.setAccessible(true);
        field.set(config, new HagridPaths(directory));
        config.setSimulationDate(LocalDate.of(2025, 5, 13));
        return config;
    }

    private String xml(String network) {
        var config = ConfigUtils.createConfig(); config.network().setInputFile(network);
        Path xml = directory.resolve("config.xml"); new ConfigWriter(config).write(xml.toString());
        return xml.toString();
    }

    private Path network() {
        var network = NetworkUtils.createNetwork();
        var a = NetworkUtils.createAndAddNode(network, Id.createNodeId("a"), new Coord(0, 0));
        var b = NetworkUtils.createAndAddNode(network, Id.createNodeId("b"), new Coord(100, 0));
        var out = NetworkUtils.createAndAddLink(network, Id.createLinkId("out"), a, b, 100, 10, 1000, 1);
        var back = NetworkUtils.createAndAddLink(network, Id.createLinkId("back"), b, a, 100, 10, 1000, 1);
        out.setAllowedModes(Set.of("car")); back.setAllowedModes(Set.of("car"));
        Path input = directory.resolve("network.xml"); new NetworkWriter(network).write(input.toString());
        return input;
    }
}
