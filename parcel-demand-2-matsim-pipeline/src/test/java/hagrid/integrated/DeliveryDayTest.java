package hagrid.integrated;

import hagrid.integrated.modular.Modular;
import hagrid.integrated.shareduse.SharedUse;
import hagrid.utils.demand.Delivery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.network.NetworkUtils;
import org.matsim.freight.carriers.Carrier;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the ONE thing {@link DeliveryDay} exists for: all three Lausitz arms must deliver within
 * the same window. They drifted apart once already — Baseline 08:00–20:00, 1c B2B 17:00 / B2C 20:00,
 * 1d 07:30–21:00 — which biased every delivery-rate comparison against the Baseline (METHODS-LOG
 * §1.2). Per-scenario constants invite exactly that drift, so it is pinned here rather than trusted.
 */
@DisplayName("DeliveryDay: one delivery window across all three arms")
class DeliveryDayTest {

    @Test
    @DisplayName("the shared window is 07:30-21:00")
    void sharedWindowValues() {
        assertThat(DeliveryDay.START_S).isEqualTo(7.5 * 3600.0);
        assertThat(DeliveryDay.END_S).isEqualTo(21 * 3600.0);
    }

    @Test
    @DisplayName("1d Modular uses the shared window")
    void modularUsesSharedWindow() {
        assertThat(Modular.DELIVERY_DAY_START_S).isEqualTo(DeliveryDay.START_S);
        assertThat(Modular.DELIVERY_DAY_END_S).isEqualTo(DeliveryDay.END_S);
    }

    @Test
    @DisplayName("1c Shared-Use uses the shared window, with no B2B/B2C split")
    void sharedUseUsesSharedWindow() {
        assertThat(SharedUse.WINDOW_END_S).isEqualTo(DeliveryDay.END_S);
        assertThat(SharedUse.SUBMIT_FROM_S).isEqualTo(DeliveryDay.START_S);
        // The per-type deadline split is gone for good: a reintroduced B2B constant would make 1c
        // stricter than the arms it is compared against.
        assertThat(SharedUse.class.getDeclaredFields())
                .extracting(java.lang.reflect.Field::getName)
                .doesNotContain("B2B_WINDOW_END_S", "B2C_WINDOW_END_S");
    }

    @Test
    @DisplayName("the Baseline's carrier services carry the shared window")
    void baselineUsesSharedWindow() {
        // The decisive one: LmdCarrierBuilder.build() is the Baseline path, and its DAY_START/DAY_END
        // were the values that differed. Asserted on a built carrier, not on the constants, so the
        // test fails if the window stops reaching the services for any reason.
        Network net = NetworkUtils.createNetwork();
        Node a = NetworkUtils.createAndAddNode(net, Id.createNodeId("a"), new Coord(0, 0));
        Node b = NetworkUtils.createAndAddNode(net, Id.createNodeId("b"), new Coord(1000, 0));
        NetworkUtils.createAndAddLink(net, Id.createLinkId("ab"), a, b, 1000.0, 13.9, 1800, 1);
        NetworkUtils.createAndAddLink(net, Id.createLinkId("ba"), b, a, 1000.0, 13.9, 1800, 1);

        VehicleType van = VehicleUtils.createVehicleType(Id.create("ct_cep_size_l", VehicleType.class));
        van.getCapacity().setOther(230);
        van.setNetworkMode("car");
        van.getCostInformation().setCostsPerMeter(0.0004).setCostsPerSecond(0.0).setFixedCost(170.0);

        List<Delivery> deliveries = List.of(
                Delivery.builder().id("d1_B2C").coordinate(new Coord(1000, 0)).provider("dhl")
                        .parcelType(Delivery.ParcelType.B2C).amount(4)
                        .deliveryMode(Delivery.DeliveryMode.HOME).postalCode("02977").build(),
                // A B2B parcel: under the old split this one had a 17:00 deadline.
                Delivery.builder().id("d2_B2B").coordinate(new Coord(1000, 0)).provider("dhl")
                        .parcelType(Delivery.ParcelType.B2B).amount(2)
                        .deliveryMode(Delivery.DeliveryMode.HOME).postalCode("02977").build());

        Carrier baseline = hagrid.integrated.freight.LmdCarrierBuilder.build("dhl", deliveries,
                Id.createLinkId("ab"), net, new VehicleType[]{van}, 2, 15, List.of(8), new Random(1));

        assertThat(baseline.getServices().values()).isNotEmpty();
        baseline.getServices().values().forEach(s -> {
            assertThat(s.getServiceStaringTimeWindow().getStart())
                    .as("Baseline service window start must be the shared 07:30")
                    .isEqualTo(DeliveryDay.START_S);
            assertThat(s.getServiceStaringTimeWindow().getEnd())
                    .as("Baseline service window end must be the shared 21:00")
                    .isEqualTo(DeliveryDay.END_S);
        });
    }
}
