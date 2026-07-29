package hagrid.integrated.modular;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.network.NetworkUtils;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierPlan;
import org.matsim.freight.carriers.CarrierPlanWriter;
import org.matsim.freight.carriers.CarrierPlanXmlReader;
import org.matsim.freight.carriers.CarrierService;
import org.matsim.freight.carriers.CarrierVehicle;
import org.matsim.freight.carriers.CarrierVehicleTypes;
import org.matsim.freight.carriers.Carriers;
import org.matsim.freight.carriers.CarriersUtils;
import org.matsim.freight.carriers.ScheduledTour;
import org.matsim.freight.carriers.Tour;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

import java.nio.file.Path;
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

    /**
     * Task 1 (paper-readiness review F1/F3, METHODS-LOG 2.16): {@code planStats} sums plan-time
     * carrier attributes that {@code convert()} never looks at, so delta_parcels stops being
     * measured against a jsprit-censored demand base. dhl's numbers are conservation-identity-0
     * consistent by construction (10 == 8 planned + 2 unassigned) so this test does not
     * incidentally exercise the identity-0 LOG.error path - that is covered separately by
     * {@link ModularKpiHandlerTest} at the CSV layer; here the point is purely the summation and
     * derivation logic.
     */
    @Test
    @DisplayName("planStats: sums numberOfParcels/unassignedParcels/missedParcels across carriers; "
            + "derives maxParcelsPerTour and depotByTourId from the converted tour list")
    void planStatsSumsCarrierAttributesAndDerivesTourStats() {
        Network carNet = buildCarNet();
        Network drtNet = buildDrtNet();

        CarrierService dhlBig = CarrierService.Builder
                .newInstance(Id.create("dhl_0", CarrierService.class), Id.createLinkId("car_2"))
                .setCapacityDemand(8).setServiceDuration(300.0).build();
        Carriers carriers = new Carriers();
        addOneTourCarrier(carriers, "dhl", Id.createLinkId("car_1"), 8 * 3600.0, 17 * 3600.0, List.of(dhlBig));
        putIntAttr(carriers, "dhl", "numberOfParcels", 10);
        putIntAttr(carriers, "dhl", "unassignedParcels", 2);
        putIntAttr(carriers, "dhl", "missedParcels", 1);

        CarrierService glsService = CarrierService.Builder
                .newInstance(Id.create("gls_0", CarrierService.class), Id.createLinkId("car_2"))
                .setCapacityDemand(5).setServiceDuration(300.0).build();
        addOneTourCarrier(carriers, "gls", Id.createLinkId("car_1"), 8 * 3600.0, 17 * 3600.0, List.of(glsService));
        putIntAttr(carriers, "gls", "numberOfParcels", 5);
        putIntAttr(carriers, "gls", "unassignedParcels", 0);
        putIntAttr(carriers, "gls", "missedParcels", 0);

        List<ModularFreightTour> tours = ModularTourConverter.convert(carriers, carNet, drtNet);
        ModularPlanStats stats = ModularTourConverter.planStats(carriers, tours);

        assertThat(stats.parcelsDemand()).isEqualTo(15);
        assertThat(stats.parcelsUnassignedJsprit()).isEqualTo(2);
        assertThat(stats.parcelsMissedOverlay()).isEqualTo(1);
        assertThat(stats.maxParcelsPerTour())
                .isEqualTo(tours.stream().mapToInt(ModularFreightTour::totalParcels).max().orElse(0));
        // Strengthened beyond the brief's literal "containsKeys(tours.get(0).tourId())": the
        // ambiguity-resolution contract is EVERY converted tour, not just the first, so a
        // depotByTourId that silently dropped tour 2+ would still pass the weaker check.
        assertThat(stats.depotByTourId())
                .as("depotByTourId must map EVERY converted tour, not just the first")
                .hasSize(tours.size())
                .containsKeys(tours.get(0).tourId());
        tours.forEach(t -> assertThat(stats.depotByTourId().get(t.tourId())).isEqualTo(t.depotLink()));
    }

    /**
     * Step 1's second brief case: a carrier with NO attributes at all must not throw (defensive
     * {@code getAttribute(...) == null} handling) and must contribute 0 to every sum - no WARN
     * required. The "ghost" carrier also has no plan at all (mirrors a carrier demand-file entry
     * that ended up with zero services), so it also contributes zero tours and the conservation
     * identity holds trivially (0 == 0 + 0) - deliberately NOT set up to trip the identity-0
     * LOG.error, since that path is a different, already-covered concern.
     */
    @Test
    @DisplayName("planStats: a carrier with no attributes at all contributes 0 to every sum, no WARN needed")
    void planStatsCarrierWithNoAttributesContributesZero() {
        Carriers carriers = new Carriers();
        Carrier ghost = CarriersUtils.createCarrier(Id.create("ghost", Carrier.class));
        carriers.addCarrier(ghost);   // no addPlan() -> no selected plan -> convert() skips it

        List<ModularFreightTour> tours = ModularTourConverter.convert(carriers, buildCarNet(), buildDrtNet());
        assertThat(tours).isEmpty();

        ModularPlanStats stats = ModularTourConverter.planStats(carriers, tours);
        assertThat(stats.parcelsDemand()).isZero();
        assertThat(stats.parcelsUnassignedJsprit()).isZero();
        assertThat(stats.parcelsMissedOverlay()).isZero();
        assertThat(stats.maxParcelsPerTour()).isZero();
        assertThat(stats.depotByTourId()).isEmpty();
    }

    /**
     * Review finding (coverage gap): the two {@code planStats} tests above build their
     * {@code Carriers} purely in-memory via {@code putIntAttr} - they never exercise the
     * production path, which ALWAYS calls {@code planStats} on a {@code Carriers} read back from
     * disk via {@code CarrierPlanXmlReader} ({@code SimulationRunnerUtils.java:397}, mirrored by
     * {@code ModularE2eStaging}/{@code LausitzFreightPreprocessorTest}). {@code intAttr}'s
     * {@code instanceof Integer} fast path was therefore only ever proven against attributes set
     * directly in memory, not against whatever boxed type an XML round-trip actually produces.
     *
     * <p>This test writes the SAME two-carrier fixture to disk via {@link CarrierPlanWriter} -
     * the exact writer {@code CarriersUtils.writeCarriers} (and therefore
     * {@code LausitzFreightPreprocessor.runModular}) uses in production - reads it back via
     * {@link CarrierPlanXmlReader}, and asserts {@code planStats} returns the SAME sums as the
     * in-memory equivalent computed from the pre-round-trip {@code Carriers}. The explicit
     * {@code isInstanceOf(Integer.class)} assertion below pins the type actually observed after
     * the round-trip (see the task report for what came back) BEFORE {@code intAttr} ever sees
     * it, so a future MATSim upgrade that changed the round-tripped boxed type would fail loudly
     * HERE with a clear message - not silently inside {@code intAttr}'s own defensive
     * {@code instanceof Number} fallback, which would still tolerate a Long but NOT a String
     * (which is not a {@code Number} and would silently fall back to contributing 0).
     */
    @Test
    @DisplayName("planStats: sums survive the production XML round-trip (CarrierPlanWriter -> "
            + "CarrierPlanXmlReader), not just in-memory attributes")
    void planStatsSumsSurviveCarrierXmlRoundTrip(@TempDir Path tmp) throws Exception {
        Network carNet = buildCarNet();
        Network drtNet = buildDrtNet();

        CarrierService dhlBig = CarrierService.Builder
                .newInstance(Id.create("dhl_0", CarrierService.class), Id.createLinkId("car_2"))
                .setCapacityDemand(8).setServiceDuration(300.0).build();
        Carriers carriers = new Carriers();
        addOneTourCarrier(carriers, "dhl", Id.createLinkId("car_1"), 8 * 3600.0, 17 * 3600.0, List.of(dhlBig));
        putIntAttr(carriers, "dhl", "numberOfParcels", 10);
        putIntAttr(carriers, "dhl", "unassignedParcels", 2);
        putIntAttr(carriers, "dhl", "missedParcels", 1);
        // CarrierPlanXmlWriterV2_1 needs BOTH of these to actually round-trip (buildScheduledTour
        // only embeds the vehicle/service into the ScheduledTour itself, never registers them on
        // the carrier - every other planStats test skips this because it never round-trips
        // through XML): (1) carrierCapabilities/vehicles must be non-empty (schema-enforced), (2)
        // every service the tour references must ALSO be in carrier.getServices(), or the writer
        // logs "not available in the list of services" and omits the service definition entirely,
        // which then throws a NullPointerException deep in the reader's state machine on read-back.
        registerCarrierVehicleForXmlRoundTrip(carriers, "dhl", Id.createLinkId("car_1"), 17 * 3600.0);
        registerServicesForXmlRoundTrip(carriers, "dhl", List.of(dhlBig));

        CarrierService glsService = CarrierService.Builder
                .newInstance(Id.create("gls_0", CarrierService.class), Id.createLinkId("car_2"))
                .setCapacityDemand(5).setServiceDuration(300.0).build();
        addOneTourCarrier(carriers, "gls", Id.createLinkId("car_1"), 8 * 3600.0, 17 * 3600.0, List.of(glsService));
        putIntAttr(carriers, "gls", "numberOfParcels", 5);
        putIntAttr(carriers, "gls", "unassignedParcels", 0);
        putIntAttr(carriers, "gls", "missedParcels", 0);
        registerCarrierVehicleForXmlRoundTrip(carriers, "gls", Id.createLinkId("car_1"), 17 * 3600.0);
        registerServicesForXmlRoundTrip(carriers, "gls", List.of(glsService));

        // Ground truth: computed from the PRE-round-trip Carriers, so the round-tripped result
        // below is compared against a freshly-computed value, not a literal that could silently
        // drift from the fixture above.
        List<ModularFreightTour> inMemoryTours = ModularTourConverter.convert(carriers, carNet, drtNet);
        ModularPlanStats inMemoryStats = ModularTourConverter.planStats(carriers, inMemoryTours);

        // Round-trip through the ACTUAL production writer/reader pair.
        Path carriersFile = tmp.resolve("carriers_roundtrip.xml");
        new CarrierPlanWriter(carriers).write(carriersFile.toString());

        CarrierVehicleTypes capsuleType = new CarrierVehicleTypes();
        VehicleType type = VehicleUtils.createVehicleType(Id.create("ushift_cargo_capsule", VehicleType.class));
        capsuleType.getVehicleTypes().put(type.getId(), type);
        Carriers roundTripped = new Carriers();
        new CarrierPlanXmlReader(roundTripped, capsuleType).readFile(carriersFile.toString());

        // Pin the ACTUAL boxed type BEFORE intAttr ever sees it (see javadoc above).
        Object roundTrippedNumberOfParcels = roundTripped.getCarriers()
                .get(Id.create("dhl", Carrier.class)).getAttributes().getAttribute("numberOfParcels");
        assertThat(roundTrippedNumberOfParcels)
                .as("the type CarrierPlanXmlReader hands intAttr for an int-valued attribute")
                .isInstanceOf(Integer.class);

        List<ModularFreightTour> roundTrippedTours =
                ModularTourConverter.convert(roundTripped, carNet, drtNet);
        ModularPlanStats roundTrippedStats =
                ModularTourConverter.planStats(roundTripped, roundTrippedTours);

        assertThat(roundTrippedStats.parcelsDemand())
                .isEqualTo(inMemoryStats.parcelsDemand()).isEqualTo(15);
        assertThat(roundTrippedStats.parcelsUnassignedJsprit())
                .isEqualTo(inMemoryStats.parcelsUnassignedJsprit()).isEqualTo(2);
        assertThat(roundTrippedStats.parcelsMissedOverlay())
                .isEqualTo(inMemoryStats.parcelsMissedOverlay()).isEqualTo(1);
    }

    /** Registers a "ushift_cargo_capsule" vehicle (same id scheme as {@link #buildScheduledTour}'s
     *  {@code provider + "_v0"}) into the carrier's {@code CarrierCapabilities} - needed ONLY for
     *  an actual XML round-trip: {@link CarrierPlanWriter}'s schema rejects an empty
     *  {@code vehicles} element, but {@code buildScheduledTour}'s vehicle lives solely inside the
     *  {@code ScheduledTour}, which every other (in-memory-only) planStats test never notices. */
    private static void registerCarrierVehicleForXmlRoundTrip(Carriers carriers, String provider,
            Id<Link> depotLink, double latestEnd) {
        VehicleType type = VehicleUtils.createVehicleType(Id.create("ushift_cargo_capsule", VehicleType.class));
        CarrierVehicle vehicle = CarrierVehicle.Builder
                .newInstance(Id.createVehicleId(provider + "_v0"), depotLink, type)
                .setLatestEnd(latestEnd).build();
        CarriersUtils.addCarrierVehicle(
                carriers.getCarriers().get(Id.create(provider, Carrier.class)), vehicle);
    }

    /** Registers each service on {@code carrier.getServices()} - required for an actual XML
     *  round-trip (see the call site's comment); {@code buildScheduledTour} only ever schedules a
     *  service INTO the tour, never onto the carrier's own service map. */
    private static void registerServicesForXmlRoundTrip(Carriers carriers, String provider,
            List<CarrierService> services) {
        Carrier carrier = carriers.getCarriers().get(Id.create(provider, Carrier.class));
        for (CarrierService s : services) {
            carrier.getServices().put(s.getId(), s);
        }
    }

    private static void putIntAttr(Carriers carriers, String provider, String key, int value) {
        carriers.getCarriers().get(Id.create(provider, Carrier.class)).getAttributes()
                .putAttribute(key, value);
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
