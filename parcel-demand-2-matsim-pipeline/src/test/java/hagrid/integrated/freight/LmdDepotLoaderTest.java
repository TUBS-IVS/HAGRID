package hagrid.integrated.freight;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.network.NetworkUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LmdDepotLoader")
class LmdDepotLoaderTest {

    private Network twoLinkNetwork() {
        Network net = NetworkUtils.createNetwork();
        Node a = NetworkUtils.createAndAddNode(net, Id.createNodeId("a"), new Coord(0, 0));
        Node b = NetworkUtils.createAndAddNode(net, Id.createNodeId("b"), new Coord(1000, 0));
        Node c = NetworkUtils.createAndAddNode(net, Id.createNodeId("c"), new Coord(2000, 0));
        NetworkUtils.createAndAddLink(net, Id.createLinkId("ab"), a, b, 1000, 13.9, 1800, 1);
        NetworkUtils.createAndAddLink(net, Id.createLinkId("bc"), b, c, 1000, 13.9, 1800, 1);
        return net;
    }

    @Test
    @DisplayName("load() snaps each provider depot to the nearest link")
    void loadsAndSnaps(@TempDir Path tmp) throws Exception {
        Path csv = tmp.resolve("lmd-depots.csv");
        Files.writeString(csv, "provider;x;y\ndhl;100;10\nhermes;1900;10\n");

        Map<String, Id<Link>> depots = LmdDepotLoader.load(csv.toString(), twoLinkNetwork());

        assertThat(depots).containsOnlyKeys("dhl", "hermes");
        assertThat(depots.get("dhl")).isEqualTo(Id.createLinkId("ab"));
        assertThat(depots.get("hermes")).isEqualTo(Id.createLinkId("bc"));
    }

    @Test
    @DisplayName("load() rejects an empty file")
    void rejectsEmpty(@TempDir Path tmp) throws Exception {
        Path csv = tmp.resolve("empty.csv");
        Files.writeString(csv, "provider;x;y\n");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> LmdDepotLoader.load(csv.toString(), twoLinkNetwork()))
                .isInstanceOf(IllegalStateException.class);
    }
}
