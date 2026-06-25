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
                /*durationPerParcelMin*/ 2, /*maxDurationPerStopMin*/ 15);

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
}
