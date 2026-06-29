package hagrid.integrated.freight;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.io.NetworkWriter;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierPlanXmlReader;
import org.matsim.freight.carriers.Carriers;
import org.matsim.freight.carriers.CarrierVehicleTypeWriter;
import org.matsim.freight.carriers.CarrierVehicleTypes;
import org.matsim.testcases.MatsimTestUtils;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LausitzFreightPreprocessor")
class LausitzFreightPreprocessorTest {

    @RegisterExtension
    public MatsimTestUtils utils = new MatsimTestUtils();

    @Test
    @DisplayName("run() produces a routed carrier XML with one carrier per demanded LSP")
    void producesRoutedCarriers() throws Exception {
        Path dir = Path.of(utils.getOutputDirectory());

        // tiny grid network (car), 4 links in a square
        var net = NetworkUtils.createNetwork();
        Node a = NetworkUtils.createAndAddNode(net, Id.createNodeId("a"), new Coord(0, 0));
        Node b = NetworkUtils.createAndAddNode(net, Id.createNodeId("b"), new Coord(1000, 0));
        Node c = NetworkUtils.createAndAddNode(net, Id.createNodeId("c"), new Coord(1000, 1000));
        Node d = NetworkUtils.createAndAddNode(net, Id.createNodeId("d"), new Coord(0, 1000));
        for (var e : new Node[][]{{a, b}, {b, c}, {c, d}, {d, a}, {b, a}, {c, b}, {d, c}, {a, d}}) {
            NetworkUtils.createAndAddLink(net, Id.createLinkId(e[0].getId() + "_" + e[1].getId()),
                    e[0], e[1], 1000, 13.9, 1800, 1);
        }
        Path netFile = dir.resolve("net.xml.gz");
        new NetworkWriter(net).write(netFile.toString());

        // van types XML
        CarrierVehicleTypes types = new CarrierVehicleTypes();
        VehicleType m = VehicleUtils.createVehicleType(Id.create("ct_cep_size_m", VehicleType.class));
        m.getCapacity().setOther(165); m.setNetworkMode("car");
        m.getCostInformation().setCostsPerMeter(0.0004).setCostsPerSecond(0.0).setFixedCost(170.0);
        types.getVehicleTypes().put(m.getId(), m);
        Path typesFile = dir.resolve("vans.xml");
        new CarrierVehicleTypeWriter(types).write(typesFile.toString());

        // depot CSV (dhl + hermes) and demand shapefile-free path:
        Path depotCsv = dir.resolve("depots.csv");
        Files.writeString(depotCsv, "provider;x;y\ndhl;0;0\nhermes;1000;1000\n");

        // Write a demand shapefile with 3 points carrying dhl + hermes parcels.
        Path demandShp = dir.resolve("demand.shp");
        LmdTestShapefiles.writeDemand(demandShp,
                new double[][]{{200, 100}, {800, 100}, {500, 900}},
                new long[]{4, 6, 0},     // dhl_tag (B2C)
                new long[]{1, 0, 0},     // dhl_type (B2B)
                new long[]{0, 0, 5});    // hermes_tag (B2C)

        Path carriersOut = dir.resolve("lmd_carriers_routed.xml");
        LausitzFreightPreprocessor.run(demandShp.toString(), depotCsv.toString(),
                netFile.toString(), typesFile.toString(), carriersOut.toString(), /*jsprit*/ 1);

        assertThat(Files.exists(carriersOut)).isTrue();

        // load the result and assert carriers + selected (routed) plans
        var scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        Carriers carriers = new Carriers();
        new CarrierPlanXmlReader(carriers, types).readFile(carriersOut.toString());
        assertThat(carriers.getCarriers()).containsOnlyKeys(
                Id.create("dhl", Carrier.class), Id.create("hermes", Carrier.class));
        assertThat(carriers.getCarriers().get(Id.create("dhl", Carrier.class)).getSelectedPlan())
                .as("dhl carrier must have a routed (selected) plan").isNotNull();
        assertThat(carriers.getCarriers().get(Id.create("hermes", Carrier.class)).getSelectedPlan())
                .as("hermes carrier must have a routed (selected) plan").isNotNull();
    }

    @Test
    @DisplayName("carNetwork() keeps only the connected car sub-network (drops PT-only links + islands)")
    void carNetworkExcludesPtLinksAndDisconnectedComponents() {
        // A network-with-pt analogue: one connected car square + a PT-only link + a detached car island.
        Network full = NetworkUtils.createNetwork();

        // connected car square (8 directed links, strongly connected) — the keep set
        Node a = NetworkUtils.createAndAddNode(full, Id.createNodeId("a"), new Coord(0, 0));
        Node b = NetworkUtils.createAndAddNode(full, Id.createNodeId("b"), new Coord(1000, 0));
        Node c = NetworkUtils.createAndAddNode(full, Id.createNodeId("c"), new Coord(1000, 1000));
        Node d = NetworkUtils.createAndAddNode(full, Id.createNodeId("d"), new Coord(0, 1000));
        for (var e : new Node[][]{{a, b}, {b, c}, {c, d}, {d, a}, {b, a}, {c, b}, {d, c}, {a, d}}) {
            NetworkUtils.createAndAddLink(full, Id.createLinkId(e[0].getId() + "_" + e[1].getId()),
                    e[0], e[1], 1000, 13.9, 1800, 1);
        }

        // PT-only link (allowedModes = {pt}) — must be excluded entirely (services snap here in the raw net)
        Node pt1 = NetworkUtils.createAndAddNode(full, Id.createNodeId("pt_regio_1"), new Coord(100, 100));
        Node pt2 = NetworkUtils.createAndAddNode(full, Id.createNodeId("pt_regio_2"), new Coord(200, 100));
        Link ptLink = NetworkUtils.createAndAddLink(full, Id.createLinkId("pt_0"), pt1, pt2, 100, 13.9, 1800, 1);
        ptLink.setAllowedModes(Set.of("pt"));

        // detached car island (strongly connected pair, but smaller than the square) — NetworkCleaner drops it
        Node e1 = NetworkUtils.createAndAddNode(full, Id.createNodeId("island1"), new Coord(50000, 50000));
        Node e2 = NetworkUtils.createAndAddNode(full, Id.createNodeId("island2"), new Coord(51000, 50000));
        NetworkUtils.createAndAddLink(full, Id.createLinkId("island_fwd"), e1, e2, 1000, 13.9, 1800, 1);
        NetworkUtils.createAndAddLink(full, Id.createLinkId("island_bwd"), e2, e1, 1000, 13.9, 1800, 1);

        Network car = LausitzFreightPreprocessor.carNetwork(full);

        assertThat(car.getLinks().keySet())
                .as("keeps the connected car square")
                .contains(Id.createLinkId("a_b"), Id.createLinkId("b_c"),
                        Id.createLinkId("c_d"), Id.createLinkId("d_a"));
        assertThat(car.getLinks().keySet())
                .as("PT-only links are excluded (no pt_* in the routing graph)")
                .doesNotContain(Id.createLinkId("pt_0"));
        assertThat(car.getLinks().keySet())
                .as("disconnected island is removed by NetworkCleaner")
                .doesNotContain(Id.createLinkId("island_fwd"), Id.createLinkId("island_bwd"));
        assertThat(car.getNodes().keySet())
                .as("no PT pseudo-nodes survive (the source of the pt_regio 'no route' wedge)")
                .doesNotContain(Id.createNodeId("pt_regio_1"), Id.createNodeId("pt_regio_2"));
    }
}
