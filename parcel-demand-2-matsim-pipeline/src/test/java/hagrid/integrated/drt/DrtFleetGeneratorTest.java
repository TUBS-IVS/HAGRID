package hagrid.integrated.drt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.*;
import org.matsim.core.network.NetworkUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

@DisplayName("DrtFleetGenerator")
class DrtFleetGeneratorTest {

    private Network net() {
        Network n = NetworkUtils.createNetwork();
        NetworkFactory f = n.getFactory();
        Node a = f.createNode(Id.createNodeId("a"), new Coord(0, 0));
        Node b = f.createNode(Id.createNodeId("b"), new Coord(100, 0));
        n.addNode(a); n.addNode(b);
        Link l = f.createLink(Id.createLinkId("l1"), a, b);
        l.setAllowedModes(Set.of("car", "drt"));
        n.addLink(l);
        return n;
    }

    @Test
    @DisplayName("writes a fleet file with the requested number of vehicles")
    void writesFleet(@TempDir Path tmp) throws Exception {
        Path out = tmp.resolve("fleet.xml");
        DrtFleetGenerator.write(net(), 5, 8, 0.0, 86400.0, out);

        assertThat(Files.exists(out)).isTrue();
        String xml = Files.readString(out);
        assertThat(xml).contains("<vehicles");
        // 5 vehicle entries
        int count = xml.split("<vehicle ", -1).length - 1;
        assertThat(count).isEqualTo(5);
        assertThat(xml).contains("start_link=\"l1\"");
    }

    @Test
    @DisplayName("rejects a fleet with no in-network link to anchor on")
    void rejectsEmptyNetwork(@TempDir Path tmp) {
        Network empty = NetworkUtils.createNetwork();
        assertThatThrownBy(() -> DrtFleetGenerator.write(empty, 3, 8, 0.0, 86400.0, tmp.resolve("f.xml")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Two disjoint links: l1 near (50,0), l2 near (1050,0). */
    private Network twoLinkNet() {
        Network n = NetworkUtils.createNetwork();
        NetworkFactory f = n.getFactory();
        Node a = f.createNode(Id.createNodeId("a"), new Coord(0, 0));
        Node b = f.createNode(Id.createNodeId("b"), new Coord(100, 0));
        Node c = f.createNode(Id.createNodeId("c"), new Coord(1000, 0));
        Node d = f.createNode(Id.createNodeId("d"), new Coord(1100, 0));
        n.addNode(a); n.addNode(b); n.addNode(c); n.addNode(d);
        Link l1 = f.createLink(Id.createLinkId("l1"), a, b);
        Link l2 = f.createLink(Id.createLinkId("l2"), c, d);
        l1.setAllowedModes(Set.of("car", "drt"));
        l2.setAllowedModes(Set.of("car", "drt"));
        n.addLink(l1); n.addLink(l2);
        return n;
    }

    private static int count(String xml, String needle) {
        return xml.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }

    @Test
    @DisplayName("spawns vehicles evenly across depots, snapped to nearest links")
    void evenSplitAcrossDepots(@TempDir Path tmp) throws Exception {
        Path out = tmp.resolve("fleet.xml");
        // one depot near l1, one near l2
        DrtFleetGenerator.writeFromDepots(twoLinkNet(),
                List.of(new Coord(40, 0), new Coord(1040, 0)),
                4, 8, 0.0, 86400.0, out);
        String xml = Files.readString(out);
        assertThat(count(xml, "<vehicle ")).isEqualTo(4);
        assertThat(count(xml, "start_link=\"l1\"")).isEqualTo(2);
        assertThat(count(xml, "start_link=\"l2\"")).isEqualTo(2);
    }

    @Test
    @DisplayName("snaps a depot that lies outside the network to the nearest link")
    void snapsOutsideDepot(@TempDir Path tmp) throws Exception {
        Path out = tmp.resolve("fleet2.xml");
        // single depot far to the east -> nearest link is l2
        DrtFleetGenerator.writeFromDepots(twoLinkNet(),
                List.of(new Coord(9000, 0)), 3, 8, 0.0, 86400.0, out);
        String xml = Files.readString(out);
        assertThat(count(xml, "<vehicle ")).isEqualTo(3);
        assertThat(count(xml, "start_link=\"l2\"")).isEqualTo(3);
    }

    @Test
    @DisplayName("rejects an empty depot list")
    void rejectsNoDepots(@TempDir Path tmp) {
        assertThatThrownBy(() -> DrtFleetGenerator.writeFromDepots(twoLinkNet(),
                List.of(), 3, 8, 0.0, 86400.0, tmp.resolve("f.xml")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
