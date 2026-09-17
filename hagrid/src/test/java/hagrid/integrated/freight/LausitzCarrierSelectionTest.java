package hagrid.integrated.freight;

import hagrid.utils.demand.Delivery;
import hagrid.utils.routing.HAGRIDRouterUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.network.NetworkUtils;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierVehicleTypes;
import org.matsim.freight.carriers.Carriers;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.*;

/**
 * The {@code hagrid.jsprit.onlyCarrier} diagnostic switch: routes a single carrier instead of the
 * whole set, so a jsprit seed/iteration probe can target the dominant carrier without paying for
 * the other six (the largest Lausitz carrier is ~35 % of the jsprit phase, METHODS-LOG section 2.2).
 */
@DisplayName("LausitzFreightPreprocessor: single-carrier routing switch")
class LausitzCarrierSelectionTest {

    private static final int JSPRIT_ITERS = 10;

    @AfterEach
    void clearProperty() {
        System.clearProperty(HAGRIDRouterUtils.JSPRIT_ONLY_CARRIER_PROPERTY);
    }

    @Test
    @DisplayName("unset: every carrier is routed (regression guard for production runs)")
    void unsetRoutesAll() {
        Fixture f = new Fixture();
        LausitzFreightPreprocessor.routeWithDurationCap(f.carriers, f.net, f.types, JSPRIT_ITERS);

        assertThat(f.carriers.getCarriers().values())
                .allSatisfy(c -> assertThat(c.getSelectedPlan())
                        .as("carrier %s must be routed when the switch is unset", c.getId())
                        .isNotNull());
    }

    @Test
    @DisplayName("'largest' routes only the carrier with the most services")
    void largestRoutesOnlyTheBiggest() {
        Fixture f = new Fixture();
        System.setProperty(HAGRIDRouterUtils.JSPRIT_ONLY_CARRIER_PROPERTY, "largest");

        LausitzFreightPreprocessor.routeWithDurationCap(f.carriers, f.net, f.types, JSPRIT_ITERS);

        // The others are dropped from the container, not merely left unrouted: writeCarriers()
        // persists the container, and a file with plan-less carriers would read as a complete run.
        assertThat(f.carriers.getCarriers()).hasSize(1);
        assertThat(f.carrier("big").getSelectedPlan()).as("the largest carrier is routed").isNotNull();
    }

    @Test
    @DisplayName("an explicit carrier id routes exactly that carrier")
    void explicitIdRoutesThatCarrier() {
        Fixture f = new Fixture();
        // Resolve the real id rather than assuming the builder's naming scheme.
        System.setProperty(HAGRIDRouterUtils.JSPRIT_ONLY_CARRIER_PROPERTY,
                f.carrier("mid").getId().toString());

        LausitzFreightPreprocessor.routeWithDurationCap(f.carriers, f.net, f.types, JSPRIT_ITERS);

        assertThat(f.carriers.getCarriers()).hasSize(1);
        assertThat(f.carrier("mid").getSelectedPlan()).isNotNull();
    }

    @Test
    @DisplayName("an unknown carrier id fails loudly instead of routing nothing")
    void unknownIdThrows() {
        Fixture f = new Fixture();
        System.setProperty(HAGRIDRouterUtils.JSPRIT_ONLY_CARRIER_PROPERTY, "does_not_exist");

        // A silent no-match would produce an empty carrier file that reads as a finished run.
        assertThatThrownBy(() ->
                LausitzFreightPreprocessor.routeWithDurationCap(f.carriers, f.net, f.types, JSPRIT_ITERS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does_not_exist")
                .hasMessageContaining(HAGRIDRouterUtils.JSPRIT_ONLY_CARRIER_PROPERTY);
        assertThat(f.carriers.getCarriers())
                .as("a failed match must not have dropped anything")
                .hasSize(3);
    }

    @Test
    @DisplayName("the filtered carrier's plan is IDENTICAL to routing it among all carriers")
    void filteringDoesNotChangeTheResult() {
        // THE decisive property for the seed probe: carriers are routed independently (own VRP, own
        // algorithm, own RNG), and the shared NetworkBasedTransportCosts is a deterministic
        // memoisation. So a single-carrier probe measures exactly what a full-arm run would measure
        // for that carrier. If this ever breaks, the probe's results stop transferring.
        Fixture full = new Fixture();
        LausitzFreightPreprocessor.routeWithDurationCap(full.carriers, full.net, full.types, JSPRIT_ITERS);
        double costAmongAll = full.carrier("big").getSelectedPlan().getJspritScore();
        int toursAmongAll = full.carrier("big").getSelectedPlan().getScheduledTours().size();

        Fixture solo = new Fixture();
        System.setProperty(HAGRIDRouterUtils.JSPRIT_ONLY_CARRIER_PROPERTY, "big");
        LausitzFreightPreprocessor.routeWithDurationCap(solo.carriers, solo.net, solo.types, JSPRIT_ITERS);

        assertThat(solo.carrier("big").getSelectedPlan().getScheduledTours()).hasSize(toursAmongAll);
        assertThat(solo.carrier("big").getSelectedPlan().getJspritScore())
                .as("routing one carrier alone must yield the same solution as routing it in the set")
                .isEqualTo(costAmongAll);
    }

    /** Three carriers of clearly different size ("big" 6 stops, "mid" 4, "small" 2) on a line network. */
    private static final class Fixture {
        final Network net = NetworkUtils.createNetwork();
        final CarrierVehicleTypes types = new CarrierVehicleTypes();
        final Carriers carriers = new Carriers();

        Fixture() {
            Node depot = NetworkUtils.createAndAddNode(net, Id.createNodeId("D"), new Coord(0, -1));
            Node c = NetworkUtils.createAndAddNode(net, Id.createNodeId("C"), new Coord(0, 0));
            NetworkUtils.createAndAddLink(net, Id.createLinkId("D_C"), depot, c, 1.0, 13.9, 1800, 1);
            NetworkUtils.createAndAddLink(net, Id.createLinkId("C_D"), c, depot, 1.0, 13.9, 1800, 1);
            Node prev = c;
            for (int i = 1; i <= 6; i++) {
                Node n = NetworkUtils.createAndAddNode(net, Id.createNodeId("N" + i), new Coord(2000.0 * i, 0));
                NetworkUtils.createAndAddLink(net, Id.createLinkId("L" + i), prev, n, 2000.0, 13.9, 1800, 1);
                NetworkUtils.createAndAddLink(net, Id.createLinkId("R" + i), n, prev, 2000.0, 13.9, 1800, 1);
                prev = n;
            }

            VehicleType van = VehicleUtils.createVehicleType(Id.create("ct_cep_size_l", VehicleType.class));
            van.getCapacity().setOther(230);
            van.setNetworkMode("car");
            van.getCostInformation().setCostsPerMeter(0.0004).setCostsPerSecond(0.0).setFixedCost(170.0);
            types.getVehicleTypes().put(van.getId(), van);

            addCarrier("big", 6, van);
            addCarrier("mid", 4, van);
            addCarrier("small", 2, van);
        }

        private void addCarrier(String provider, int stops, VehicleType van) {
            List<Delivery> deliveries = new ArrayList<>();
            for (int i = 1; i <= stops; i++) {
                deliveries.add(Delivery.builder().id(provider + "_" + i)
                        .coordinate(new Coord(2000.0 * i, 0)).provider(provider)
                        .parcelType(Delivery.ParcelType.B2B).amount(1)
                        .deliveryMode(Delivery.DeliveryMode.HOME).postalCode("02977").build());
            }
            carriers.addCarrier(LmdCarrierBuilder.build(provider, deliveries, Id.createLinkId("D_C"),
                    net, new VehicleType[]{van}, 2, 15, List.of(8), new Random(1)));
        }

        Carrier carrier(String provider) {
            Carrier found = carriers.getCarriers().values().stream()
                    .filter(x -> x.getId().toString().contains(provider))
                    .findFirst().orElseThrow(() -> new AssertionError("no carrier for " + provider));
            return found;
        }
    }
}
