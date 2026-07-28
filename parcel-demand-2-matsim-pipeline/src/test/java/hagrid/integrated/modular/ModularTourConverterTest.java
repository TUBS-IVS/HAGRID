package hagrid.integrated.modular;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.network.NetworkUtils;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierPlan;
import org.matsim.freight.carriers.CarrierService;
import org.matsim.freight.carriers.CarrierVehicle;
import org.matsim.freight.carriers.Carriers;
import org.matsim.freight.carriers.CarriersUtils;
import org.matsim.freight.carriers.ScheduledTour;
import org.matsim.freight.carriers.Tour;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TDD for Task 4: {@link ModularTourConverter} turns routed {@code CarrierPlan}s (jsprit, CAR
 * network) into dispatchable {@link ModularFreightTour}s (DRT network). Both tests build the
 * carrier fixtures in memory via the freight API - no file round-trip - so the assertions are
 * about the conversion logic itself, not the XML reader.
 */
@DisplayName("ModularTourConverter - jsprit plan -> dispatchable ModularFreightTours")
class ModularTourConverterTest {

    @Test
    @DisplayName("converts a routed tour: id, times, latestEnd, stops snapped to the drt network")
    void convertsScheduledTour() {
        // carNetwork: 2-node line a->b (links "car_1","car_2"); drtNetwork: DIFFERENT link ids
        // ("drt_1","drt_2") at the same coordinates -> forces the nearest-link snap path.
        Network carNet = buildCarNet();
        Network drtNet = buildDrtNet();

        CarrierService s1 = CarrierService.Builder
                .newInstance(Id.create("dhl_0", CarrierService.class), Id.createLinkId("car_1"))
                .setCapacityDemand(3).setServiceDuration(360.0).build();
        CarrierService s2 = CarrierService.Builder
                .newInstance(Id.create("dhl_1", CarrierService.class), Id.createLinkId("car_2"))
                .setCapacityDemand(2).setServiceDuration(240.0).build();
        Carriers carriers = fixtureCarriersWithOneTour("dhl", Id.createLinkId("car_1"),
                /*departure*/ 8 * 3600.0, /*vehicleLatestEnd*/ 17 * 3600.0, List.of(s1, s2));

        List<ModularFreightTour> tours = ModularTourConverter.convert(carriers, carNet, drtNet);

        assertThat(tours).hasSize(1);
        ModularFreightTour t = tours.get(0);
        assertThat(t.tourId()).isEqualTo("dhl_t0");                    // carrier + tour index, NO UUID
        assertThat(t.provider()).isEqualTo("dhl");
        assertThat(t.tourIndex()).isEqualTo(0);                        // C7 interleave key
        assertThat(t.plannedStart()).isEqualTo(8 * 3600.0);
        assertThat(t.latestEnd()).isEqualTo(17 * 3600.0);
        assertThat(t.totalParcels()).isEqualTo(5);
        assertThat(t.stops()).hasSize(2);
        assertThat(t.stops().get(0).serviceDuration()).isEqualTo(360.0);
        // every link id exists in the DRT network (snap worked)
        assertThat(drtNet.getLinks()).containsKey(t.depotLink());
        t.stops().forEach(s -> assertThat(drtNet.getLinks()).containsKey(s.link()));
        // plannedDuration = leg times + service durations
        assertThat(t.plannedDuration()).isGreaterThanOrEqualTo(600.0);
        // submission = plannedStart - (lookahead + retooling)
        assertThat(t.submissionTime())
                .isEqualTo(8 * 3600.0 - (Modular.FREIGHT_LOOKAHEAD_S + Modular.RETOOLING_S));
    }

    @Test
    @DisplayName("deterministic order: carriers sorted by id, tours by plan order")
    void deterministicTourOrder() {
        Network carNet = buildCarNet();
        Network drtNet = buildDrtNet();

        CarrierService glsService = CarrierService.Builder
                .newInstance(Id.create("gls_0", CarrierService.class), Id.createLinkId("car_2"))
                .setCapacityDemand(1).setServiceDuration(120.0).build();
        CarrierService dhlService = CarrierService.Builder
                .newInstance(Id.create("dhl_0", CarrierService.class), Id.createLinkId("car_2"))
                .setCapacityDemand(1).setServiceDuration(120.0).build();

        Carriers carriers = new Carriers();
        // Insert 'gls' BEFORE 'dhl': Carriers is backed by a LinkedHashMap (insertion order), so
        // without an explicit sort-by-id the converter would emit [gls_t0, dhl_t0].
        addOneTourCarrier(carriers, "gls", Id.createLinkId("car_1"),
                8 * 3600.0, 17 * 3600.0, List.of(glsService));
        addOneTourCarrier(carriers, "dhl", Id.createLinkId("car_1"),
                8 * 3600.0, 17 * 3600.0, List.of(dhlService));

        List<ModularFreightTour> tours = ModularTourConverter.convert(carriers, carNet, drtNet);

        assertThat(tours).extracting(ModularFreightTour::tourId)
                .containsExactly("dhl_t0", "gls_t0");
    }

    // ---- fixtures ----

    /** 2-node line a(0,0)->b(1000,0); one link each direction, ids "car_1"/"car_2". */
    private static Network buildCarNet() {
        Network net = NetworkUtils.createNetwork();
        Node a = NetworkUtils.createAndAddNode(net, Id.createNodeId("a"), new Coord(0, 0));
        Node b = NetworkUtils.createAndAddNode(net, Id.createNodeId("b"), new Coord(1000, 0));
        NetworkUtils.createAndAddLink(net, Id.createLinkId("car_1"), a, b, 1000, 13.9, 1800, 1);
        NetworkUtils.createAndAddLink(net, Id.createLinkId("car_2"), b, a, 1000, 13.9, 1800, 1);
        return net;
    }

    /** SAME coordinates as {@link #buildCarNet()} but DIFFERENT link/node ids ("drt_1"/"drt_2"). */
    private static Network buildDrtNet() {
        Network net = NetworkUtils.createNetwork();
        Node a = NetworkUtils.createAndAddNode(net, Id.createNodeId("da"), new Coord(0, 0));
        Node b = NetworkUtils.createAndAddNode(net, Id.createNodeId("db"), new Coord(1000, 0));
        NetworkUtils.createAndAddLink(net, Id.createLinkId("drt_1"), a, b, 1000, 13.9, 1800, 1);
        NetworkUtils.createAndAddLink(net, Id.createLinkId("drt_2"), b, a, 1000, 13.9, 1800, 1);
        return net;
    }

    private static Carriers fixtureCarriersWithOneTour(String provider, Id<Link> depotLink,
            double departure, double vehicleLatestEnd, List<CarrierService> services) {
        Carriers carriers = new Carriers();
        addOneTourCarrier(carriers, provider, depotLink, departure, vehicleLatestEnd, services);
        return carriers;
    }

    /**
     * One carrier, one plan, one {@link ScheduledTour}: start at depot, [leg, service]* for every
     * service, a final leg, end at depot. Mirrors the shape {@code MatsimJspritFactory.createPlan}
     * produces (see {@code MarriedBaselineEndToEndTest:108-120} for the read-back side).
     */
    private static void addOneTourCarrier(Carriers carriers, String provider, Id<Link> depotLink,
            double departure, double vehicleLatestEnd, List<CarrierService> services) {
        VehicleType type = VehicleUtils.createVehicleType(Id.create("ushift_cargo_capsule", VehicleType.class));
        CarrierVehicle vehicle = CarrierVehicle.Builder
                .newInstance(Id.createVehicleId(provider + "_v1"), depotLink, type)
                .setLatestEnd(vehicleLatestEnd).build();

        Tour.Builder tourBuilder = Tour.Builder.newInstance(Id.create(provider + "_tour", Tour.class));
        tourBuilder.scheduleStart(depotLink);
        for (CarrierService s : services) {
            tourBuilder.addLeg(tourBuilder.createLeg(null, 0.0, 300.0));
            tourBuilder.scheduleService(s);
        }
        tourBuilder.addLeg(tourBuilder.createLeg(null, 0.0, 300.0));
        tourBuilder.scheduleEnd(depotLink);
        Tour tour = tourBuilder.build();

        ScheduledTour scheduledTour = ScheduledTour.newInstance(tour, vehicle, departure);
        Carrier carrier = CarriersUtils.createCarrier(Id.create(provider, Carrier.class));
        carrier.addPlan(new CarrierPlan(List.of(scheduledTour)));
        carriers.addCarrier(carrier);
    }
}
