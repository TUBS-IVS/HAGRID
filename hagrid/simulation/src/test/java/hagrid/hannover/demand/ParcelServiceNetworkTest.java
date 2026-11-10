package hagrid.hannover.demand;

import hagrid.core.HagridConfig;
import hagrid.core.HagridPaths;
import hagrid.core.routing.RoutingNetworkValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.io.NetworkWriter;
import org.matsim.core.scenario.ScenarioUtils;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class ParcelServiceNetworkTest {
    @TempDir Path directory;

    @Test
    void generatedParcelGraphHasOnlyItsOwnAdjacency() throws Exception {
        var parcel = fixture().parcel;
        assertThat(parcel.getLinks().keySet()).containsExactlyInAnyOrder(Id.createLinkId("home"), Id.createLinkId("stop"));
        assertThatCode(() -> RoutingNetworkValidator.validate(parcel)).doesNotThrowAnyException();
        assertThat(parcel.getNodes().values()).allSatisfy(node -> {
            assertThat(node.getInLinks().keySet()).doesNotContain(Id.createLinkId("motorway"));
            assertThat(node.getOutLinks().keySet()).doesNotContain(Id.createLinkId("motorway"));
        });
    }

    @Test
    void changingParcelGraphCannotChangeTheCarRoutingGraph() throws Exception {
        var f = fixture();
        var id = Id.createLinkId("stop");
        f.parcel.getLinks().get(id).setFreespeed(3);
        f.parcel.getNodes().get(Id.createNodeId("b")).setCoord(new Coord(-1, -1));
        assertThat(f.car.getLinks().get(id).getFreespeed()).isEqualTo(10);
        assertThat(f.car.getNodes().get(Id.createNodeId("b")).getCoord().getX()).isEqualTo(200);
        assertThat(f.parcel.getLinks().get(id)).isNotSameAs(f.car.getLinks().get(id));
    }

    private Fixture fixture() throws Exception {
        var input = NetworkUtils.createNetwork();
        for (String node : List.of("a", "b", "c")) NetworkUtils.createAndAddNode(input, Id.createNodeId(node), new Coord((node.charAt(0) - 'a' + 1) * 100, 0));
        for (String[] link : List.of(new String[]{"home", "a", "b"}, new String[]{"stop", "b", "c"}, new String[]{"motorway", "c", "a"})) {
            var edge = NetworkUtils.createAndAddLink(input, Id.createLinkId(link[0]), input.getNodes().get(Id.createNodeId(link[1])),
                    input.getNodes().get(Id.createNodeId(link[2])), 100, 10, 1000, 1);
            edge.setAllowedModes(Set.of("car"));
            edge.getAttributes().putAttribute("osm:way:highway", link[0].equals("motorway") ? "motorway" : "residential");
        }
        Path file = directory.resolve("network.xml");
        new NetworkWriter(input).write(file.toString());
        var config = new HagridConfig();
        set(config, "hagridPaths", new HagridPaths(directory));
        config.setSimulationDate(LocalDate.of(2025, 5, 13));
        config.paths().setNetwork(file.toString());
        java.nio.file.Files.createDirectories(Path.of(config.io().networkFiltered()).getParent());
        var scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        var processor = new NetworkProcessor();
        set(processor, "scenario", scenario);
        set(processor, "hagridConfig", config);
        processor.processNetwork();
        return new Fixture((Network) scenario.getScenarioElement("carFilteredNetwork"),
                (Network) scenario.getScenarioElement("parcelServiceNetwork"));
    }
    private static void set(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
    private record Fixture(Network car, Network parcel) {}
}
