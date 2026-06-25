package hagrid.integrated.freight;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
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
    }
}
