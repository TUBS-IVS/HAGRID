package hagrid.lausitz.drt;

import hagrid.core.util.LogCapture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.prep.PreparedPolygon;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Node;
import org.matsim.contrib.common.zones.Zone;
import org.matsim.contrib.common.zones.ZoneImpl;
import org.matsim.contrib.common.zones.ZoneSystem;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ReturnToDepotRebalancingModule.buildDepotCapacities")
class ReturnToDepotRebalancingModuleTest {

    private static final GeometryFactory GF = new GeometryFactory();

    /** Axis-aligned square zone [x0, x0+size] x [y0, y0+size]; centroid in its middle. */
    private static Zone square(String id, double x0, double y0, double size) {
        var poly = GF.createPolygon(new Coordinate[]{
                new Coordinate(x0, y0), new Coordinate(x0 + size, y0),
                new Coordinate(x0 + size, y0 + size), new Coordinate(x0, y0 + size),
                new Coordinate(x0, y0)});
        return new ZoneImpl(Id.create(id, Zone.class), new PreparedPolygon(poly), "grid");
    }

    /** Only getZones() is used by the code under test. */
    private static ZoneSystem zonesOf(Zone... zones) {
        Map<Id<Zone>, Zone> map = new LinkedHashMap<>();
        for (Zone z : zones) map.put(z.getId(), z);
        return new ZoneSystem() {
            public Optional<Zone> getZoneForLinkId(Id<Link> id) { throw new UnsupportedOperationException(); }
            public Optional<Zone> getZoneForNodeId(Id<Node> id) { throw new UnsupportedOperationException(); }
            public List<Link> getLinksForZoneId(Id<Zone> id) { throw new UnsupportedOperationException(); }
            public Map<Id<Zone>, Zone> getZones() { return map; }
        };
    }

    @Test
    @DisplayName("a depot inside a zone is assigned to it without any warning")
    void depotInsideAZoneIsQuiet() {
        Zone a = square("A", 0, 0, 1000);
        try (LogCapture log = LogCapture.of(ReturnToDepotRebalancingModule.class)) {
            Map<Zone, Double> caps = ReturnToDepotRebalancingModule.buildDepotCapacities(
                    zonesOf(a), List.of(new Coord(500, 500)), 5.0);
            assertThat(caps).containsExactly(Map.entry(a, 5.0));
            assertThat(log.warnings()).isEmpty();
        }
    }

    @Test
    @DisplayName("a depot outside every zone still falls back to the nearest centroid, and says so")
    void depotOutsideAllZonesFallsBackAndWarns() {
        Zone a = square("A", 0, 0, 1000);          // centroid (500, 500): 1000 m from the depot
        Zone b = square("B", 5000, 5000, 1000);    // centroid (5500, 5500): far away
        try (LogCapture log = LogCapture.of(ReturnToDepotRebalancingModule.class)) {
            Map<Zone, Double> caps = ReturnToDepotRebalancingModule.buildDepotCapacities(
                    zonesOf(a, b), List.of(new Coord(1500, 500)), 5.0);
            // behaviour unchanged: the evening pull still goes to the nearest zone
            assertThat(caps).containsExactly(Map.entry(a, 5.0));
            assertThat(log.warnings()).singleElement().satisfies(m -> {
                assertThat(m).contains("1500").contains("A");
                assertThat(m).containsIgnoringCase("outside");
            });
        }
    }
}
