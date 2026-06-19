package hagrid.integrated.drt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Coordinate;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.*;
import org.matsim.core.network.NetworkUtils;

import java.util.Set;

import static org.assertj.core.api.Assertions.*;

@DisplayName("DrtNetworkPreparer")
class DrtNetworkPreparerTest {

    /** Square service area covering (0,0)-(1000,1000). */
    private Geometry square() {
        GeometryFactory gf = new GeometryFactory();
        return gf.createPolygon(new Coordinate[]{
                new Coordinate(0, 0), new Coordinate(1000, 0),
                new Coordinate(1000, 1000), new Coordinate(0, 1000), new Coordinate(0, 0)
        });
    }

    private Network twoLinkNetwork() {
        Network n = NetworkUtils.createNetwork();
        NetworkFactory f = n.getFactory();
        Node a = f.createNode(Id.createNodeId("a"), new Coord(100, 100));   // inside
        Node b = f.createNode(Id.createNodeId("b"), new Coord(900, 900));   // inside
        Node c = f.createNode(Id.createNodeId("c"), new Coord(5000, 5000)); // outside
        n.addNode(a); n.addNode(b); n.addNode(c);
        Link inside = f.createLink(Id.createLinkId("in"), a, b);
        inside.setAllowedModes(Set.of("car"));
        Link insideRev = f.createLink(Id.createLinkId("in_rev"), b, a);
        insideRev.setAllowedModes(Set.of("car"));
        Link leaving = f.createLink(Id.createLinkId("out"), b, c);
        leaving.setAllowedModes(Set.of("car"));
        n.addLink(inside); n.addLink(insideRev); n.addLink(leaving);
        return n;
    }

    @Test
    @DisplayName("keeps only links fully inside the service area")
    void clipsToArea() {
        Network result = DrtNetworkPreparer.prepare(twoLinkNetwork(), square());
        assertThat(result.getLinks()).containsKey(Id.createLinkId("in"));
        assertThat(result.getLinks()).doesNotContainKey(Id.createLinkId("out"));
    }

    @Test
    @DisplayName("adds drt to allowed modes of retained car links")
    void addsDrtMode() {
        Network result = DrtNetworkPreparer.prepare(twoLinkNetwork(), square());
        Link in = result.getLinks().get(Id.createLinkId("in"));
        assertThat(in.getAllowedModes()).contains("car", "drt");
    }
}
