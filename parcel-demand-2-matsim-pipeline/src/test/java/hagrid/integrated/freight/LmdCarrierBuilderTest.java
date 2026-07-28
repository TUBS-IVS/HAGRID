package hagrid.integrated.freight;

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
        // services carry the ALIGNED start window (C4 revised: 07:30-21:00, not DAY_START/DAY_END)
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
}
