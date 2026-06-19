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
}
