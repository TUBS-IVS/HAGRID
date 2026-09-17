package hagrid.integrated.freight;

import hagrid.integrated.DeliveryDistrictBuilder;
import hagrid.utils.demand.Delivery;
import hagrid.utils.routing.HAGRIDRouterUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.network.NetworkUtils;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierCapabilities.FleetSize;
import org.matsim.freight.carriers.CarrierService;
import org.matsim.freight.carriers.CarrierVehicle;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LmdCarrierBuilder")
class LmdCarrierBuilderTest {

    /** Same depot link id as {@link #net()}'s only link - reused as a constant by the district tests
     *  below since they pass it repeatedly. */
    private static final Id<Link> DEPOT_LINK = Id.createLinkId("ab");

    private Network net() {
        Network n = NetworkUtils.createNetwork();
        Node a = NetworkUtils.createAndAddNode(n, Id.createNodeId("a"), new Coord(0, 0));
        Node b = NetworkUtils.createAndAddNode(n, Id.createNodeId("b"), new Coord(1000, 0));
        NetworkUtils.createAndAddLink(n, Id.createLinkId("ab"), a, b, 1000, 13.9, 1800, 1);
        return n;
    }

    private VehicleType van(String id, double cap) {
        VehicleType t = VehicleUtils.createVehicleType(Id.create(id, VehicleType.class));
        t.getCapacity().setOther(cap);
        t.setNetworkMode("car");
        return t;
    }

    /** The two-van-type fleet already used inline by several tests in this file (e.g. {@code buildsCarrier}),
     *  pulled out because the district tests below all need the same array. */
    private VehicleType[] vanTypes() {
        return new VehicleType[]{van("ct_cep_size_m", 165), van("ct_cep_size_l", 230)};
    }

    /** Builds one {@link Delivery} at a coordinate, following the builder pattern already used by
     *  every other test in this file (HOME mode, fixed postal code - irrelevant to these assertions). */
    private Delivery deliveryAt(double x, double y, String provider, int amount, Delivery.ParcelType type) {
        return Delivery.builder().id(provider + "_" + type).coordinate(new Coord(x, y)).provider(provider)
                .parcelType(type).amount(amount)
                .deliveryMode(Delivery.DeliveryMode.HOME).postalCode("02977").build();
    }

    @Test
    @DisplayName("builds a carrier with one service per delivery + a van per type per dispatch hour at the depot")
    void buildsCarrier() {
        Network n = net();
        List<Delivery> deliveries = List.of(
                Delivery.builder().id("d1_B2C").coordinate(new Coord(100, 0)).provider("dhl")
                        .parcelType(Delivery.ParcelType.B2C).amount(10)
                        .deliveryMode(Delivery.DeliveryMode.HOME).postalCode("02977").build(),
                Delivery.builder().id("d2_B2B").coordinate(new Coord(900, 0)).provider("dhl")
                        .parcelType(Delivery.ParcelType.B2B).amount(3)
                        .deliveryMode(Delivery.DeliveryMode.HOME).postalCode("02977").build());
        VehicleType[] vans = {van("ct_cep_size_m", 165), van("ct_cep_size_l", 230)};

        // two dispatch waves: 08:00 and 14:00 -> 2 types × 2 hours = 4 vehicles
        Carrier carrier = LmdCarrierBuilder.build(
                "dhl", deliveries, Id.createLinkId("ab"), n, vans,
                /*durationPerParcelMin*/ 2, /*maxDurationPerStopMin*/ 15,
                List.of(8, 14), new Random(42));

        assertThat(carrier.getId().toString()).isEqualTo("dhl");
        assertThat(carrier.getServices()).hasSize(2);
        // 10 parcels -> 2*10=20 min > 15 cap -> 900s ; 3 parcels -> 2*3=6 min -> 360s
        assertThat(carrier.getServices().values())
                .extracting(CarrierService::getServiceDuration)
                .containsExactlyInAnyOrder(900.0, 360.0);
        // jittered copies per van type per dispatch hour: 2 types × 2 hours × copies
        var vehicles = carrier.getCarrierCapabilities().getCarrierVehicles();
        assertThat(vehicles).hasSize(2 * 2 * LmdCarrierBuilder.VEHICLES_PER_TYPE_PER_WAVE);
        assertThat(vehicles.values()).allMatch(v -> v.getLinkId().equals(Id.createLinkId("ab")));
        // morning wave clusters around 08:00, afternoon wave around 14:00 (Gaussian minute jitter)
        assertThat(vehicles.values()).anyMatch(v -> Math.abs(v.getEarliestStartTime() - 8 * 3600.0) < 3600.0);
        assertThat(vehicles.values()).anyMatch(v -> Math.abs(v.getEarliestStartTime() - 14 * 3600.0) < 3600.0);
    }

    @Test
    @DisplayName("depot departures are stochastically jittered (legacy parity), not all hard on the hour")
    void jitteredDepotDepartures() {
        // Hannover CarrierVehicleFactory.getTimeShift: Gaussian minute jitter per vehicle
        // (sigma 15 min for size m, 5 min for size l). With INFINITE fleet jsprit clones one
        // template, so realistic per-tour spread needs SEVERAL jittered copies per type+wave.
        Network n = net();
        List<Delivery> deliveries = List.of(
                Delivery.builder().id("d1").coordinate(new Coord(100, 0)).provider("dhl")
                        .parcelType(Delivery.ParcelType.B2C).amount(1)
                        .deliveryMode(Delivery.DeliveryMode.HOME).postalCode("02977").build());

        Carrier carrier = LmdCarrierBuilder.build(
                "dhl", deliveries, Id.createLinkId("ab"), n, new VehicleType[]{van("ct_cep_size_m", 165)},
                2, 15, List.of(8), new Random(42));

        var vehicles = carrier.getCarrierCapabilities().getCarrierVehicles().values();
        assertThat(vehicles).hasSize(LmdCarrierBuilder.VEHICLES_PER_TYPE_PER_WAVE);
        var starts = vehicles.stream().map(CarrierVehicle::getEarliestStartTime).distinct().toList();
        assertThat(starts).as("jitter must spread the copies, not stack them on one start")
                .hasSizeGreaterThan(1);
        // sigma 15 min: all starts stay near the wave hour (+-1h is > 3 sigma)
        assertThat(vehicles).allMatch(v -> Math.abs(v.getEarliestStartTime() - 8 * 3600.0) < 3600.0);
        // wave-relative window survives the jitter: end = start + 7h cap + 1h buffer (cap 21:00)
        assertThat(vehicles).allMatch(v ->
                v.getLatestEndTime() == Math.min(v.getEarliestStartTime() + 8 * 3600.0, 21 * 3600.0));
        // deterministic: same seed -> identical departure times (reproducible runs)
        Carrier again = LmdCarrierBuilder.build(
                "dhl", deliveries, Id.createLinkId("ab"), n, new VehicleType[]{van("ct_cep_size_m", 165)},
                2, 15, List.of(8), new Random(42));
        assertThat(again.getCarrierCapabilities().getCarrierVehicles().values()
                .stream().map(CarrierVehicle::getEarliestStartTime).toList())
                .containsExactlyInAnyOrderElementsOf(
                        vehicles.stream().map(CarrierVehicle::getEarliestStartTime).toList());
    }

    @Test
    @DisplayName("vehicle operating window is wave-relative (Hannover parity): start + 7h cap + 1h buffer, capped 21:00")
    void waveRelativeVehicleWindows() {
        // Hannover CarrierVehicleFactory.calculateEndTime: latestEnd = start + maxRouteDuration + 1h,
        // capped at 21:00. This is what makes the 14:00 wave REAL: an 08:00 van may only operate until
        // 16:00, so late-afternoon workload can only be served by the 14:00 wave. A fixed latestEnd of
        // 20:00 for both waves (the old Lausitz port) makes wave choice cost-neutral and arbitrary.
        Network n = net();
        List<Delivery> deliveries = List.of(
                Delivery.builder().id("d1").coordinate(new Coord(100, 0)).provider("dhl")
                        .parcelType(Delivery.ParcelType.B2C).amount(1)
                        .deliveryMode(Delivery.DeliveryMode.HOME).postalCode("02977").build());

        Carrier carrier = LmdCarrierBuilder.build(
                "dhl", deliveries, Id.createLinkId("ab"), n, new VehicleType[]{van("ct_cep_size_m", 165)},
                2, 15, List.of(8, 14), new Random(42));

        var vehicles = carrier.getCarrierCapabilities().getCarrierVehicles().values();
        assertThat(vehicles).hasSize(2 * LmdCarrierBuilder.VEHICLES_PER_TYPE_PER_WAVE);
        // 08:00 wave (jittered): window is start + 7h + 1h, well below the 21:00 cap
        assertThat(vehicles).anyMatch(v ->
                Math.abs(v.getEarliestStartTime() - 8 * 3600.0) < 3600.0
                        && v.getLatestEndTime() == v.getEarliestStartTime() + 8 * 3600.0);
        // 14:00 wave (jittered): start + 8h > 21:00 -> capped at 21:00
        assertThat(vehicles).anyMatch(v ->
                Math.abs(v.getEarliestStartTime() - 14 * 3600.0) < 3600.0
                        && v.getLatestEndTime() == 21 * 3600.0);
    }

    @Test
    @DisplayName("records a missed-delivery overlay (Fehlzustellung) as legacy-compatible carrier attributes")
    void recordsMissedDeliveries() {
        Network n = net();
        // 1000 B2C dhl parcels at one stop: with a ~94% rate, some (but not all) parcels are missed.
        List<Delivery> deliveries = List.of(
                Delivery.builder().id("d1_B2C").coordinate(new Coord(100, 0)).provider("dhl")
                        .parcelType(Delivery.ParcelType.B2C).amount(1000)
                        .deliveryMode(Delivery.DeliveryMode.HOME).postalCode("02977").build());
        VehicleType[] vans = {van("ct_cep_size_m", 165)};

        Carrier carrier = LmdCarrierBuilder.build(
                "dhl", deliveries, Id.createLinkId("ab"), n, vans, 2, 15, List.of(8), new Random(42));

        int numberOfParcels = (int) carrier.getAttributes().getAttribute("numberOfParcels");
        int missedParcels = (int) carrier.getAttributes().getAttribute("missedParcels");
        @SuppressWarnings("unchecked")
        List<Object> missedList = (List<Object>) carrier.getAttributes().getAttribute("missedParcelsAsList");
        String missedStr = (String) carrier.getAttributes().getAttribute("missedParcelDeliveriesAsString");

        assertThat(numberOfParcels).isEqualTo(1000);
        assertThat(carrier.getAttributes().getAttribute("provider")).isEqualTo("dhl");
        // some parcels fail, but not all (delivery rate strictly between 0% and 100%)
        assertThat(missedParcels).isGreaterThan(0).isLessThan(1000);
        // legacy invariant: count attribute matches the list size (CarrierGenerator.validateMissedParcelDeliveries)
        assertThat(missedList).hasSize(missedParcels);
        // serialized form the dashboard parses (CarrierXmlParser strips [ ] spaces, splits on ',')
        assertThat(missedStr).startsWith("[").endsWith("]").contains("dhl_0");
    }

    @Test
    @DisplayName("buildSingleWindow: explicit 07:30-21:00 window, no waves, no jitter; legacy build untouched")
    void singleWindowBuildsExplicitWindow() {
        Network n = net();
        List<Delivery> deliveries = List.of(
                Delivery.builder().id("d1_B2C").coordinate(new Coord(100, 0)).provider("dhl")
                        .parcelType(Delivery.ParcelType.B2C).amount(10)
                        .deliveryMode(Delivery.DeliveryMode.HOME).postalCode("02977").build(),
                Delivery.builder().id("d2_B2B").coordinate(new Coord(900, 0)).provider("dhl")
                        .parcelType(Delivery.ParcelType.B2B).amount(3)
                        .deliveryMode(Delivery.DeliveryMode.HOME).postalCode("02977").build());
        VehicleType[] vanTypes = {van("ct_cep_size_m", 165), van("ct_cep_size_l", 230)};
        Id<Link> depotLink = Id.createLinkId("ab");

        Carrier modular = LmdCarrierBuilder.buildSingleWindow("dhl", deliveries, depotLink, n,
                vanTypes, 2, 15, new Random(1), 27000.0, 75600.0, 27000.0, 75600.0);

        // exactly ONE vehicle per van type, window exactly as passed (no wave copies, no jitter)
        assertThat(modular.getCarrierCapabilities().getCarrierVehicles()).hasSize(vanTypes.length);
        modular.getCarrierCapabilities().getCarrierVehicles().values().forEach(v -> {
            assertThat(v.getEarliestStartTime()).isEqualTo(27000.0);
            assertThat(v.getLatestEndTime()).isEqualTo(75600.0);
        });
        // services carry the ALIGNED start window (C4 revised: 07:30-21:00). Since the 2026-07-30
        // unification DAY_START/DAY_END hold the same values, but this test deliberately passes them
        // EXPLICITLY - it pins that buildSingleWindow honours its arguments rather than the constants.
        modular.getServices().values().forEach(s -> {
            assertThat(s.getServiceStaringTimeWindow().getStart()).isEqualTo(27000.0);
            assertThat(s.getServiceStaringTimeWindow().getEnd()).isEqualTo(75600.0);
        });
        // missed-delivery overlay still applied (same RNG core as build); numberOfParcels is a plain
        // sum of delivery amounts (10 + 3), so - stronger than mere non-nullness - it is asserted exactly.
        assertThat((int) modular.getAttributes().getAttribute("numberOfParcels")).isEqualTo(13);
        // FleetSize.INFINITE, same as legacy build (jsprit decides tour count from an unbounded fleet)
        assertThat(modular.getCarrierCapabilities().getFleetSize()).isEqualTo(FleetSize.INFINITE);

        // legacy 9-arg build: wave-relative window EXACTLY as before (byte-identity guard)
        Carrier legacy = LmdCarrierBuilder.build("dhl", deliveries, depotLink, n, vanTypes,
                2, 15, List.of(8), new Random(1));
        assertThat(legacy.getCarrierCapabilities().getFleetSize()).isEqualTo(FleetSize.INFINITE);
        legacy.getCarrierCapabilities().getCarrierVehicles().values().forEach(v ->
                assertThat(v.getLatestEndTime()).isEqualTo(Math.min(
                        v.getEarliestStartTime() + HAGRIDRouterUtils.MAXROUTEDURATION + 3600.0,
                        21 * 3600.0)));
    }

    @Test
    @DisplayName("B2B parcels are delivered ~always (rate 99%) -> far fewer misses than B2C")
    void b2bIsMoreReliableThanB2c() {
        Network n = net();
        VehicleType[] vans = {van("ct_cep_size_m", 165)};
        Coord stop = new Coord(100, 0);

        Carrier b2c = LmdCarrierBuilder.build("dhl", List.of(
                Delivery.builder().id("x_B2C").coordinate(stop).provider("dhl")
                        .parcelType(Delivery.ParcelType.B2C).amount(1000)
                        .deliveryMode(Delivery.DeliveryMode.HOME).postalCode("02977").build()),
                Id.createLinkId("ab"), n, vans, 2, 15, List.of(8), new Random(7));
        Carrier b2b = LmdCarrierBuilder.build("dhl", List.of(
                Delivery.builder().id("x_B2B").coordinate(stop).provider("dhl")
                        .parcelType(Delivery.ParcelType.B2B).amount(1000)
                        .deliveryMode(Delivery.DeliveryMode.HOME).postalCode("02977").build()),
                Id.createLinkId("ab"), n, vans, 2, 15, List.of(8), new Random(7));

        assertThat((int) b2b.getAttributes().getAttribute("missedParcels"))
                .isLessThan((int) b2c.getAttributes().getAttribute("missedParcels"));
    }

    @Test
    @DisplayName("buildDistrict: a district spanning two providers applies each parcel's own rate, not the 90 percent default")
    void districtCarrierAppliesEachParcelsOwnProviderRate() {
        // dhl 94%, gls 91% - a district spanning both must NOT collapse to the 90% default.
        List<DeliveryDistrictBuilder.PooledStop> stops = List.of(
                new DeliveryDistrictBuilder.PooledStop(new Coord(100, 100), 200,
                        List.of(deliveryAt(100, 100, "dhl", 100, Delivery.ParcelType.B2C),
                                deliveryAt(100, 100, "gls", 100, Delivery.ParcelType.B2C))));

        Carrier c = LmdCarrierBuilder.buildDistrict("bez0", stops, DEPOT_LINK, net(),
                vanTypes(), 2, 15, new Random(1L), 27000.0, 75600.0, 27000.0, 75600.0);

        assertThat(c.getServices()).as("one pooled stop = one service").hasSize(1);
        assertThat((int) c.getAttributes().getAttribute("numberOfParcels"))
                .as("numberOfParcels must be the plain sum of the pooled stop's parcels (100 dhl + 100 gls)")
                .isEqualTo(200);
        // Exact value, not a band: this quantity is fully deterministic (fixed seed, no concurrency).
        // Correct (per-parcel provider rate) gives 15 with Random(1L); the regression this test exists
        // to catch - buildCore's shape, ONE carrier-level bias keyed off districtId ("bez0", so
        // sigma=5.0 since "dhl".equals("bez0") is false) and DELIVERY_RATES.getOrDefault("bez0", 90.0)
        // applied to every parcel regardless of provider - gives 6 with the same seed. A band such as
        // (3, 35) does not separate 15 from 6; only the exact value does.
        int missed = (int) c.getAttributes().getAttribute("missedParcels");
        assertThat(missed)
                .as("missedParcels must reflect each parcel's own provider rate (dhl 94% / gls 91%), "
                        + "not a single carrier-level 90% default draw")
                .isEqualTo(15);
    }

    @Test
    @DisplayName("buildDistrict: a single-provider district matches that provider's own rate band")
    void districtCarrierWithOneProviderMatchesTheProviderRateBand() {
        List<DeliveryDistrictBuilder.PooledStop> stops = List.of(
                new DeliveryDistrictBuilder.PooledStop(new Coord(100, 100), 1000,
                        List.of(deliveryAt(100, 100, "dhl", 1000, Delivery.ParcelType.B2C))));

        // Seed 1L was replaced with 2L (fix round 1): with a single dhl-only district and seed 1L,
        // the correct per-provider draw (bias sigma 2.5, base 94%) and the buildCore-shaped regression
        // (bias sigma 5.0, base 90% via DELIVERY_RATES.getOrDefault("bez0", 90.0)) both land at ~97.9%
        // effective rate and coincidentally both miss exactly 19 parcels - the fixture could not
        // discriminate the bug it exists to catch. Seed 2L was chosen by running both implementations
        // side by side (not guessed): correct=53, regressed=87, a 34-parcel gap far outside sampling
        // noise for n=1000.
        Carrier c = LmdCarrierBuilder.buildDistrict("bez0", stops, DEPOT_LINK, net(),
                vanTypes(), 2, 15, new Random(2L), 27000.0, 75600.0, 27000.0, 75600.0);

        assertThat((int) c.getAttributes().getAttribute("numberOfParcels"))
                .as("numberOfParcels must be the plain sum of the pooled stop's parcels (1000 dhl)")
                .isEqualTo(1000);
        int missed = (int) c.getAttributes().getAttribute("missedParcels");
        assertThat(missed)
                .as("missedParcels must reflect dhl's own 94% rate + Random(2L)'s daily bias, "
                        + "not the buildCore-shaped regression (one carrier-level bias off districtId, "
                        + "90% default) which gives 87 with this same seed")
                .isEqualTo(53);
    }

    @Test
    @DisplayName("buildDistrict: B2B parcels keep the B2B rate regardless of which provider carries them")
    void b2bParcelsInADistrictKeepTheB2BRate() {
        List<DeliveryDistrictBuilder.PooledStop> stops = List.of(
                new DeliveryDistrictBuilder.PooledStop(new Coord(100, 100), 1000,
                        List.of(deliveryAt(100, 100, "dpd", 1000, Delivery.ParcelType.B2B))));

        Carrier c = LmdCarrierBuilder.buildDistrict("bez0", stops, DEPOT_LINK, net(),
                vanTypes(), 2, 15, new Random(1L), 27000.0, 75600.0, 27000.0, 75600.0);

        assertThat((int) c.getAttributes().getAttribute("numberOfParcels"))
                .as("numberOfParcels must be the plain sum of the pooled stop's parcels (1000 dpd)")
                .isEqualTo(1000);
        // B2B_DELIVERY_RATE (99%) is a fixed constant applied regardless of provider or districtId, so
        // this quantity cannot distinguish the per-provider-rate regression covered by the two tests
        // above (both the correct and the buildCore-shaped regression apply the same 99% here) - it
        // pins the separate B2B-override contract instead. Still exact, not a band, since deterministic.
        int missed = (int) c.getAttributes().getAttribute("missedParcels");
        assertThat(missed)
                .as("B2B is ~99% reliable regardless of provider; with Random(1L) exactly 11 of 1000 miss")
                .isEqualTo(11);
    }

    @Test
    @DisplayName("buildDistrict: records the real per-provider parcel breakdown for the analysis layer")
    void districtCarrierRecordsItsProviderBreakdown() {
        List<DeliveryDistrictBuilder.PooledStop> stops = List.of(
                new DeliveryDistrictBuilder.PooledStop(new Coord(100, 100), 120,
                        List.of(deliveryAt(100, 100, "dhl", 100, Delivery.ParcelType.B2C),
                                deliveryAt(100, 100, "gls", 20, Delivery.ParcelType.B2C))));

        Carrier c = LmdCarrierBuilder.buildDistrict("bez0", stops, DEPOT_LINK, net(),
                vanTypes(), 2, 15, new Random(1L), 27000.0, 75600.0, 27000.0, 75600.0);

        assertThat(c.getAttributes().getAttribute("parcelsByProvider"))
                .as("the analysis layer needs the real provider split - a district mixes providers")
                .isEqualTo("dhl=100;gls=20");
    }
}
