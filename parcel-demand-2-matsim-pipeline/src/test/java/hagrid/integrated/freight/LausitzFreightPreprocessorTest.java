package hagrid.integrated.freight;

import hagrid.utils.demand.Delivery;
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
import java.util.List;
import java.util.Random;
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

    @Test
    @DisplayName("routeWithDurationCap splits an over-7h workload into multiple tours (Hannover MaxRouteDuration)")
    void durationCapForcesTourSplit() {
        // Star network centred on a depot, two ~6h arms (east + west). Each arm alone fits the 7h cap;
        // visiting BOTH in one tour is ~12h and cannot. With capacity deliberately non-binding, only the
        // ~7h route-duration cap can force a split -> >=2 tours proves the constraint is applied.
        // (Stock CarriersUtils.runJsprit would pack everything into a single ~12h tour within the 12h window.)
        Network net = NetworkUtils.createNetwork();
        Node c = NetworkUtils.createAndAddNode(net, Id.createNodeId("C"), new Coord(0, 0));
        Node depotNode = NetworkUtils.createAndAddNode(net, Id.createNodeId("D"), new Coord(0, -1));
        // SHORT depot link (1m) so it adds ~no travel; the vehicle effectively starts at the centre C.
        NetworkUtils.createAndAddLink(net, Id.createLinkId("D_C"), depotNode, c, 1.0, 13.9, 1800, 1);
        NetworkUtils.createAndAddLink(net, Id.createLinkId("C_D"), c, depotNode, 1.0, 13.9, 1800, 1);

        Node prevE = c, prevW = c;
        for (int i = 1; i <= 2; i++) {
            Node e = NetworkUtils.createAndAddNode(net, Id.createNodeId("E" + i), new Coord(50000.0 * i, 0));
            Node w = NetworkUtils.createAndAddNode(net, Id.createNodeId("W" + i), new Coord(-50000.0 * i, 0));
            link(net, prevE, e); link(net, e, prevE);   // 50km links @ 13.9 m/s ~= 1h each
            link(net, prevW, w); link(net, w, prevW);
            prevE = e; prevW = w;
        }

        VehicleType van = VehicleUtils.createVehicleType(Id.create("ct_cep_size_l", VehicleType.class));
        van.getCapacity().setOther(230);             // >> 6 parcels -> capacity is NOT the splitter
        van.setNetworkMode("car");
        van.getCostInformation().setCostsPerMeter(0.0004).setCostsPerSecond(0.0).setFixedCost(170.0);
        CarrierVehicleTypes types = new CarrierVehicleTypes();
        types.getVehicleTypes().put(van.getId(), van);

        java.util.List<Delivery> deliveries = new java.util.ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            deliveries.add(Delivery.builder().id("e" + i).coordinate(new Coord(50000.0 * i, 0)).provider("dhl")
                    .parcelType(Delivery.ParcelType.B2B).amount(1)
                    .deliveryMode(Delivery.DeliveryMode.HOME).postalCode("02977").build());
            deliveries.add(Delivery.builder().id("w" + i).coordinate(new Coord(-50000.0 * i, 0)).provider("dhl")
                    .parcelType(Delivery.ParcelType.B2B).amount(1)
                    .deliveryMode(Delivery.DeliveryMode.HOME).postalCode("02977").build());
        }

        Carrier carrier = LmdCarrierBuilder.build("dhl", deliveries, Id.createLinkId("D_C"),
                net, new VehicleType[]{van}, 2, 15, List.of(8), new Random(1));
        Carriers carriers = new Carriers();
        carriers.addCarrier(carrier);

        LausitzFreightPreprocessor.routeWithDurationCap(carriers, net, types, /*jsprit*/ 50);

        assertThat(carrier.getSelectedPlan()).isNotNull();
        // The decisive proof that the Hannover MaxRouteDurationConstraint is applied: a workload that an
        // unconstrained jsprit would pack into ONE ~8h tour (within the 12h vehicle window) is forced into
        // >=2 tours by the ~7h route-duration cap. Stock CarriersUtils.runJsprit produces a single tour here.
        assertThat(carrier.getSelectedPlan().getScheduledTours())
                .as("the ~7h route-duration cap must split the ~8h two-arm workload into >=2 tours")
                .hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("clipToArea drops deliveries outside the area and removes emptied providers")
    void clipToServiceAreaDropsOutOfAreaDeliveries() {
        // Service area = rectangle [0,0]..[1000,1000]. dhl has one in-area + one far-out delivery;
        // hermes has ONLY a far-out delivery -> provider must be dropped entirely.
        org.locationtech.jts.geom.GeometryFactory gf = new org.locationtech.jts.geom.GeometryFactory();
        org.locationtech.jts.geom.Geometry area = gf.createPolygon(new org.locationtech.jts.geom.Coordinate[]{
                new org.locationtech.jts.geom.Coordinate(0, 0),
                new org.locationtech.jts.geom.Coordinate(1000, 0),
                new org.locationtech.jts.geom.Coordinate(1000, 1000),
                new org.locationtech.jts.geom.Coordinate(0, 1000),
                new org.locationtech.jts.geom.Coordinate(0, 0)});

        Delivery dhlIn = Delivery.builder().id("in").coordinate(new Coord(500, 500)).provider("dhl")
                .parcelType(Delivery.ParcelType.B2C).amount(3)
                .deliveryMode(Delivery.DeliveryMode.HOME).postalCode("02977").build();
        Delivery dhlOut = Delivery.builder().id("out").coordinate(new Coord(50000, 50000)).provider("dhl")
                .parcelType(Delivery.ParcelType.B2C).amount(2)
                .deliveryMode(Delivery.DeliveryMode.HOME).postalCode("02977").build();
        Delivery hermesOut = Delivery.builder().id("hout").coordinate(new Coord(-9000, -9000)).provider("hermes")
                .parcelType(Delivery.ParcelType.B2C).amount(5)
                .deliveryMode(Delivery.DeliveryMode.HOME).postalCode("02977").build();

        java.util.Map<String, List<Delivery>> byProvider = new java.util.LinkedHashMap<>();
        byProvider.put("dhl", new java.util.ArrayList<>(List.of(dhlIn, dhlOut)));
        byProvider.put("hermes", new java.util.ArrayList<>(List.of(hermesOut)));

        var clipped = LausitzFreightPreprocessor.clipToArea(byProvider, area);

        assertThat(clipped).as("hermes had only out-of-area demand -> provider dropped").containsOnlyKeys("dhl");
        assertThat(clipped.get("dhl")).as("only the in-area dhl delivery survives").containsExactly(dhlIn);
    }

    @Test
    @DisplayName("routeWithDurationCap records jobs jsprit cannot assign as carrier attributes")
    void tracksUnassignedJobs() {
        // Square car network, depot on a_b. dhl gets one routable stop (2 parcels) plus one stop whose
        // 500 parcels exceed every van capacity (230) -> jsprit can never insert it and must leave it
        // unassigned. hermes is fully assignable -> must carry explicit zero attributes.
        Network net = NetworkUtils.createNetwork();
        Node a = NetworkUtils.createAndAddNode(net, Id.createNodeId("a"), new Coord(0, 0));
        Node b = NetworkUtils.createAndAddNode(net, Id.createNodeId("b"), new Coord(1000, 0));
        Node c = NetworkUtils.createAndAddNode(net, Id.createNodeId("c"), new Coord(1000, 1000));
        Node d = NetworkUtils.createAndAddNode(net, Id.createNodeId("d"), new Coord(0, 1000));
        for (var e : new Node[][]{{a, b}, {b, c}, {c, d}, {d, a}, {b, a}, {c, b}, {d, c}, {a, d}}) {
            NetworkUtils.createAndAddLink(net, Id.createLinkId(e[0].getId() + "_" + e[1].getId()),
                    e[0], e[1], 1000, 13.9, 1800, 1);
        }

        VehicleType van = VehicleUtils.createVehicleType(Id.create("ct_cep_size_l", VehicleType.class));
        van.getCapacity().setOther(230);
        van.setNetworkMode("car");
        van.getCostInformation().setCostsPerMeter(0.0004).setCostsPerSecond(0.0).setFixedCost(170.0);
        CarrierVehicleTypes types = new CarrierVehicleTypes();
        types.getVehicleTypes().put(van.getId(), van);

        List<Delivery> dhlDeliveries = List.of(
                Delivery.builder().id("ok").coordinate(new Coord(500, 100)).provider("dhl")
                        .parcelType(Delivery.ParcelType.B2B).amount(2)
                        .deliveryMode(Delivery.DeliveryMode.HOME).postalCode("02977").build(),
                Delivery.builder().id("huge").coordinate(new Coord(900, 900)).provider("dhl")
                        .parcelType(Delivery.ParcelType.B2B).amount(500)
                        .deliveryMode(Delivery.DeliveryMode.HOME).postalCode("02977").build());
        List<Delivery> hermesDeliveries = List.of(
                Delivery.builder().id("fine").coordinate(new Coord(100, 900)).provider("hermes")
                        .parcelType(Delivery.ParcelType.B2B).amount(3)
                        .deliveryMode(Delivery.DeliveryMode.HOME).postalCode("02977").build());

        Carrier dhl = LmdCarrierBuilder.build("dhl", dhlDeliveries, Id.createLinkId("a_b"),
                net, new VehicleType[]{van}, 2, 15, List.of(8), new Random(1));
        Carrier hermes = LmdCarrierBuilder.build("hermes", hermesDeliveries, Id.createLinkId("a_b"),
                net, new VehicleType[]{van}, 2, 15, List.of(8), new Random(1));
        Carriers carriers = new Carriers();
        carriers.addCarrier(dhl);
        carriers.addCarrier(hermes);

        LausitzFreightPreprocessor.routeWithDurationCap(carriers, net, types, /*jsprit*/ 10);

        // dhl: the oversized stop (service id dhl_1) is unassigned; the routable one is still scheduled
        assertThat(dhl.getAttributes().getAttribute("unassignedJobs"))
                .as("dhl must report exactly the oversized stop as unassigned").isEqualTo(1);
        assertThat(dhl.getAttributes().getAttribute("unassignedParcels"))
                .as("parcel-level count of the unassigned stop").isEqualTo(500);
        assertThat((String) dhl.getAttributes().getAttribute("unassignedJobsAsString"))
                .as("unassigned service ids are persisted for the dashboard").contains("dhl_1");
        assertThat(dhl.getSelectedPlan().getScheduledTours())
                .as("the routable dhl stop must still be driven").isNotEmpty();

        // hermes: fully assignable -> explicit zeros (dashboard reads the attribute unconditionally)
        assertThat(hermes.getAttributes().getAttribute("unassignedJobs")).isEqualTo(0);
        assertThat(hermes.getAttributes().getAttribute("unassignedParcels")).isEqualTo(0);
        assertThat((String) hermes.getAttributes().getAttribute("unassignedJobsAsString")).isEqualTo("[]");
    }

    /** Adds a 50km, 13.9 m/s directed link (~1h travel) named {@code <from>_<to>}. */
    private static void link(Network net, Node from, Node to) {
        NetworkUtils.createAndAddLink(net, Id.createLinkId(from.getId() + "_" + to.getId()),
                from, to, 50000, 13.9, 1800, 1);
    }
}
