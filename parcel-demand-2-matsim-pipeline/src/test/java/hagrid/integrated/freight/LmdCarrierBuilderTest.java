package hagrid.integrated.freight;

import hagrid.utils.demand.Delivery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.network.NetworkUtils;
import org.matsim.freight.carriers.Carrier;
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
    @DisplayName("builds a carrier with one service per delivery + a van per type at the depot")
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

        Carrier carrier = LmdCarrierBuilder.build(
                "dhl", deliveries, Id.createLinkId("ab"), n, vans,
                /*durationPerParcelMin*/ 2, /*maxDurationPerStopMin*/ 15, new Random(42));

        assertThat(carrier.getId().toString()).isEqualTo("dhl");
        assertThat(carrier.getServices()).hasSize(2);
        // 10 parcels -> 2*10=20 min > 15 cap -> 900s ; 3 parcels -> 2*3=6 min -> 360s
        assertThat(carrier.getServices().values())
                .extracting(CarrierService::getServiceDuration)
                .containsExactlyInAnyOrder(900.0, 360.0);
        // one vehicle per van type, both at the depot link
        assertThat(carrier.getCarrierCapabilities().getCarrierVehicles()).hasSize(2);
        assertThat(carrier.getCarrierCapabilities().getCarrierVehicles().values())
                .allMatch(v -> v.getLinkId().equals(Id.createLinkId("ab")));
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
                "dhl", deliveries, Id.createLinkId("ab"), n, vans, 2, 15, new Random(42));

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
    @DisplayName("B2B parcels are delivered ~always (rate 99%) -> far fewer misses than B2C")
    void b2bIsMoreReliableThanB2c() {
        Network n = net();
        VehicleType[] vans = {van("ct_cep_size_m", 165)};
        Coord stop = new Coord(100, 0);

        Carrier b2c = LmdCarrierBuilder.build("dhl", List.of(
                Delivery.builder().id("x_B2C").coordinate(stop).provider("dhl")
                        .parcelType(Delivery.ParcelType.B2C).amount(1000)
                        .deliveryMode(Delivery.DeliveryMode.HOME).postalCode("02977").build()),
                Id.createLinkId("ab"), n, vans, 2, 15, new Random(7));
        Carrier b2b = LmdCarrierBuilder.build("dhl", List.of(
                Delivery.builder().id("x_B2B").coordinate(stop).provider("dhl")
                        .parcelType(Delivery.ParcelType.B2B).amount(1000)
                        .deliveryMode(Delivery.DeliveryMode.HOME).postalCode("02977").build()),
                Id.createLinkId("ab"), n, vans, 2, 15, new Random(7));

        assertThat((int) b2b.getAttributes().getAttribute("missedParcels"))
                .isLessThan((int) b2c.getAttributes().getAttribute("missedParcels"));
    }
}
