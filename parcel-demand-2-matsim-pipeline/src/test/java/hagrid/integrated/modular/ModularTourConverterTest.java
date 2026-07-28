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

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TDD for Task 4: {@link ModularTourConverter} turns routed {@code CarrierPlan}s (jsprit, CAR
 * network) into dispatchable {@link ModularFreightTour}s (DRT network). All tests build the
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
        // plannedDuration = leg times + service durations = 3 legs*300.0 + (360.0+240.0) = 1500.0
        // (review finding 2: EXACT, not >=600.0 - an implementation that dropped all leg times
        // would compute exactly 600.0 and must be caught, since Task 7's expiry formula
        // (now + 2xRETOOLING_S + plannedDuration > latestEnd) is computed from this number).
        assertThat(t.plannedDuration()).isEqualTo(1500.0);
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

    /**
     * Review finding 1 (Important): the same-id FAST path (`toDrtLink` returns the car link id
     * unchanged when the DRT network already contains it) was previously untested - both other
     * fixtures use disjoint id namespaces, so only the coordinate-snap fallback was ever
     * exercised, even though the fast path is the DOMINANT branch in real data
     * ({@code LausitzDrtPreprocessor} builds the DRT network by mode-filtering the SAME full
     * network jsprit routed on, which preserves original link ids).
     *
     * <p>The DRT fixture here deliberately contains "shared_link" (same id as the car network's
     * depot/stop link) placed FAR from the car link's to-node coordinate, AND a differently-named
     * "near_link" placed exactly at that coordinate - i.e. the link a coordinate snap would
     * actually pick if the fast path were ever skipped. This distinguishes "fast path taken" from
     * "snap merely happened to land on the same link": if {@code toDrtLink} fell through to the
     * snap unconditionally, this test would observe "near_link" instead of "shared_link" and fail.
     */
    @Test
    @DisplayName("toDrtLink fast path: an id already present in the DRT network is returned unchanged (no snap)")
    void fastPathReturnsSameIdWithoutSnapping() {
        Network carNet = NetworkUtils.createNetwork();
        Node a = NetworkUtils.createAndAddNode(carNet, Id.createNodeId("a"), new Coord(0, 0));
        Node b = NetworkUtils.createAndAddNode(carNet, Id.createNodeId("b"), new Coord(1000, 0));
        NetworkUtils.createAndAddLink(carNet, Id.createLinkId("shared_link"), a, b, 1000, 13.9, 1800, 1);

        Network drtNet = NetworkUtils.createNetwork();
        // "shared_link" itself sits FAR AWAY from (1000,0) - if the fast path were skipped, a
        // coordinate snap would never pick it on proximity grounds.
        Node far1 = NetworkUtils.createAndAddNode(drtNet, Id.createNodeId("far1"), new Coord(5000, 5000));
        Node far2 = NetworkUtils.createAndAddNode(drtNet, Id.createNodeId("far2"), new Coord(6000, 5000));
        NetworkUtils.createAndAddLink(drtNet, Id.createLinkId("shared_link"), far1, far2, 1000, 13.9, 1800, 1);
        // "near_link" sits exactly where the car link's to-node is - the snap's obvious pick.
        Node near1 = NetworkUtils.createAndAddNode(drtNet, Id.createNodeId("near1"), new Coord(999, 0));
        Node near2 = NetworkUtils.createAndAddNode(drtNet, Id.createNodeId("near2"), new Coord(1001, 0));
        NetworkUtils.createAndAddLink(drtNet, Id.createLinkId("near_link"), near1, near2, 2, 13.9, 1800, 1);

        CarrierService s = CarrierService.Builder
                .newInstance(Id.create("dhl_0", CarrierService.class), Id.createLinkId("shared_link"))
                .setCapacityDemand(1).setServiceDuration(60.0).build();
        Carriers carriers = fixtureCarriersWithOneTour("dhl", Id.createLinkId("shared_link"),
                8 * 3600.0, 17 * 3600.0, List.of(s));

        List<ModularFreightTour> tours = ModularTourConverter.convert(carriers, carNet, drtNet);

        assertThat(tours).hasSize(1);
        ModularFreightTour t = tours.get(0);
        assertThat(t.depotLink()).isEqualTo(Id.createLinkId("shared_link"));
        assertThat(t.stops()).extracting(ModularFreightTour.Stop::link)
                .containsExactly(Id.createLinkId("shared_link"));
    }

    /**
     * Review findings 3+4 (Minor, self-disclosed, closed together per the reviewer's note that
     * they compose): {@code tourIndex} is the C7 interleave key whose entire purpose is
     * distinguishing tour 0 from tour 1+ WITHIN a carrier, and the zero-service-activity skip
     * path (ambiguity #4) must not renumber the tours after the one it skips. A 3-tour carrier
     * where the MIDDLE tour has zero stops pins both at once: {@code index++} in
     * {@code ModularTourConverter.convert} runs unconditionally before the skip check, so the
     * third tour keeps index 2 (not renumbered to 1) even though only 2 tours survive.
     */
    @Test
    @DisplayName("multi-tour carrier: tourIndex distinguishes tour 0/1+, and a skipped zero-stop tour does not renumber later tours")
    void multiTourIndexingSurvivesASkippedEmptyTour() {
        Network carNet = buildCarNet();
        Network drtNet = buildDrtNet();

        CarrierService first = CarrierService.Builder
                .newInstance(Id.create("dhl_a", CarrierService.class), Id.createLinkId("car_2"))
                .setCapacityDemand(1).setServiceDuration(60.0).build();
        CarrierService third = CarrierService.Builder
                .newInstance(Id.create("dhl_b", CarrierService.class), Id.createLinkId("car_2"))
                .setCapacityDemand(1).setServiceDuration(90.0).build();

        Carriers carriers = new Carriers();
        // tour 0: one stop; tour 1: ZERO stops (ambiguity-#4 skip path); tour 2: one stop.
        addMultiTourCarrier(carriers, "dhl", Id.createLinkId("car_1"), 8 * 3600.0, 17 * 3600.0,
                List.of(List.of(first), List.of(), List.of(third)));

        List<ModularFreightTour> tours = ModularTourConverter.convert(carriers, carNet, drtNet);

        assertThat(tours).extracting(ModularFreightTour::tourId)
                .containsExactly("dhl_t0", "dhl_t2");
        assertThat(tours).extracting(ModularFreightTour::tourIndex)
                .containsExactly(0, 2);
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

    /** One carrier, one plan, one {@link ScheduledTour} built from {@link #buildScheduledTour}. */
    private static void addOneTourCarrier(Carriers carriers, String provider, Id<Link> depotLink,
            double departure, double vehicleLatestEnd, List<CarrierService> services) {
        ScheduledTour scheduledTour =
                buildScheduledTour(provider, 0, depotLink, departure, vehicleLatestEnd, services);
        Carrier carrier = CarriersUtils.createCarrier(Id.create(provider, Carrier.class));
        carrier.addPlan(new CarrierPlan(List.of(scheduledTour)));
        carriers.addCarrier(carrier);
    }

    /**
     * One carrier, one plan, MULTIPLE {@link ScheduledTour}s in plan order - one entry of
     * {@code toursServices} per tour (an empty inner list builds a zero-stop tour, i.e. start ->
     * leg -> end with no service scheduled in between). See findings 3+4 above.
     */
    private static void addMultiTourCarrier(Carriers carriers, String provider, Id<Link> depotLink,
            double departure, double vehicleLatestEnd, List<List<CarrierService>> toursServices) {
        List<ScheduledTour> scheduledTours = new ArrayList<>();
        for (int i = 0; i < toursServices.size(); i++) {
            scheduledTours.add(buildScheduledTour(
                    provider, i, depotLink, departure, vehicleLatestEnd, toursServices.get(i)));
        }
        Carrier carrier = CarriersUtils.createCarrier(Id.create(provider, Carrier.class));
        carrier.addPlan(new CarrierPlan(scheduledTours));
        carriers.addCarrier(carrier);
    }

    /**
     * Builds ONE {@link ScheduledTour}: start at depot, [leg, service]* for every service (zero
     * services is legal - see {@link #addMultiTourCarrier}), a final leg, end at depot. Mirrors
     * the shape {@code MatsimJspritFactory.createPlan} produces (see
     * {@code MarriedBaselineEndToEndTest:108-120} for the read-back side).
     */
    private static ScheduledTour buildScheduledTour(String provider, int tourNum, Id<Link> depotLink,
            double departure, double vehicleLatestEnd, List<CarrierService> services) {
        VehicleType type = VehicleUtils.createVehicleType(Id.create("ushift_cargo_capsule", VehicleType.class));
        CarrierVehicle vehicle = CarrierVehicle.Builder
                .newInstance(Id.createVehicleId(provider + "_v" + tourNum), depotLink, type)
                .setLatestEnd(vehicleLatestEnd).build();

        Tour.Builder tourBuilder =
                Tour.Builder.newInstance(Id.create(provider + "_tour" + tourNum, Tour.class));
        tourBuilder.scheduleStart(depotLink);
        for (CarrierService s : services) {
            tourBuilder.addLeg(tourBuilder.createLeg(null, 0.0, 300.0));
            tourBuilder.scheduleService(s);
        }
        tourBuilder.addLeg(tourBuilder.createLeg(null, 0.0, 300.0));
        tourBuilder.scheduleEnd(depotLink);
        Tour tour = tourBuilder.build();

        return ScheduledTour.newInstance(tour, vehicle, departure);
    }
}
