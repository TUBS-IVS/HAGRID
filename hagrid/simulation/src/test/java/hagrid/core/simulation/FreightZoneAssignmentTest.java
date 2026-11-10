package hagrid.core.simulation;

import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.scenario.ScenarioUtils;

import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FreightZoneAssignmentTest {
    @Test
    void polygonAssignsFreightZoneToContainedLink() {
        var scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        var link = link(scenario, "inside", 2, 8);
        assign(scenario, List.of(zone(41)));
        assertThat(link.getAttributes().getAttribute("zone")).isEqualTo(41);
    }

    @Test
    void endpointCanClassifyALinkWhoseMidpointIsOutside() {
        var scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        var link = link(scenario, "crossing", 2, 100);
        assign(scenario, List.of(zone(41)));
        assertThat(link.getAttributes().getAttribute("zone")).isEqualTo(41);
    }

    @Test
    void existingZoneIsNormalizedAndPreservedInsteadOfReplacingItWithThePolygon() {
        var scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        var link = link(scenario, "tagged", 2, 8);
        link.getAttributes().putAttribute("zone", " 23 ");
        assign(scenario, List.of(zone(41)));
        assertThat(link.getAttributes().getAttribute("zone")).isEqualTo(23);
    }

    @Test
    void missingFeaturesAndOutsideLinksDoNotInventZones() {
        var scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        var inside = link(scenario, "inside", 2, 8);
        var outside = link(scenario, "outside", 100, 110);
        assign(scenario, List.of());
        assertThat(inside.getAttributes().getAttribute("zone")).isNull();
        assign(scenario, List.of(zone(41)));
        assertThat(outside.getAttributes().getAttribute("zone")).isNull();
    }


    @Test
    void overlappingZonesKeepLegacyPriorityAcrossSeveralIndexLevels() {
        var scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        var link = link(scenario, "overlap", 2, 8);
        var features = new java.util.ArrayList<SimpleFeature>();
        features.add(zone(10, 0, 0, 20, 20));
        features.add(zone(20, 0, -10, 220, 10));
        for (int i = 0; i < 12; i++) {
            double x = 15 + 5 * i;
            double y = i < 6 ? -1000 : -100;
            features.add(zone(100 + i, x, y, x + 2, y + 10));
        }
        assign(scenario, features);
        // The legacy single-leaf tree put the lower-center-Y polygon first.
        assertThat(link.getAttributes().getAttribute("zone")).isEqualTo(20);
    }

    @Test
    void largerDisjointZoneCollectionAssignsTheCorrectZone() {
        var scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        var link = link(scenario, "zone-5", 502, 508);
        var features = new java.util.ArrayList<SimpleFeature>();
        for (int i = 0; i < 32; i++) features.add(zone(i, i * 100, 0, i * 100 + 20, 20));
        assign(scenario, features);
        assertThat(link.getAttributes().getAttribute("zone")).isEqualTo(5);
    }

    private static void assign(Scenario scenario, Collection<SimpleFeature> features) {
        FreightZoneAssigner.assign(scenario.getNetwork(), features);
    }

    private static Link link(Scenario scenario, String id, double fromX, double toX) {
        var net = scenario.getNetwork();
        var from = NetworkUtils.createAndAddNode(net, Id.createNodeId(id + "-from"), new Coord(fromX, 2));
        var to = NetworkUtils.createAndAddNode(net, Id.createNodeId(id + "-to"), new Coord(toX, 2));
        return NetworkUtils.createAndAddLink(net, Id.createLinkId(id), from, to, toX - fromX, 10, 1000, 1);
    }

    private static SimpleFeature zone(long id) {
        return zone(id, 0, 0, 20, 20);
    }

    private static SimpleFeature zone(long id, double minX, double minY, double maxX, double maxY) {
        var type = new SimpleFeatureTypeBuilder();
        type.setName("zones");
        type.add("the_geom", Polygon.class);
        type.add("NO", Long.class);
        var builder = new SimpleFeatureBuilder(type.buildFeatureType());
        builder.add(new GeometryFactory().createPolygon(new Coordinate[]{
                new Coordinate(minX, minY), new Coordinate(maxX, minY), new Coordinate(maxX, maxY),
                new Coordinate(minX, maxY), new Coordinate(minX, minY)}));
        builder.add(id);
        return builder.buildFeature("zone-" + id);
    }
}
