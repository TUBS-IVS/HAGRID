package hagrid.integrated.freight;

import hagrid.utils.demand.Delivery;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the jsprit construction heuristic ({@code Jsprit.Parameter.CONSTRUCTION}) to
 * REGRET_INSERTION by asserting an OUTCOME, not the configured constant.
 *
 * <p>Why an outcome test: HAGRID used to override jsprit's default with BEST_INSERTION, which cost
 * roughly one vehicle per carrier. Measured 2026-08-11 on the real Lausitz carrier {@code dpd}
 * (408 services, jsprit 100 iterations, production inputs incl. service-area clip): 5 tours /
 * 248.6 km / 878.82 EUR with BEST_INSERTION versus 4 tours / 206.9 km / 746.15 EUR with
 * REGRET_INSERTION. BEST_INSERTION is greedy per job — each job lands wherever it is cheapest AT
 * THE MOMENT of insertion, and opening a fresh route costs nothing but the depot stub, because the
 * vehicle's fixed cost is invisible during insertion. Ruin-and-recreate cannot repair that
 * afterwards (a half-emptied tour still pays its full fixed cost, so every intermediate state
 * scores worse); verified by enlarging every ruin past jsprit's own 50/70 job caps, which left the
 * tour count unchanged.
 *
 * <p>A test asserting {@code CONSTRUCTION == REGRET_INSERTION} would only restate the production
 * line and would pass for any behaviour, so this routes a fixture on which the two heuristics
 * measurably disagree. The fixture is deliberately in the DURATION-bound regime (generous vehicle
 * capacity, short explicit route-duration cap), because that is the regime in which the regression
 * was found — a capacity-bound fixture would not discriminate, both heuristics pack unit demands
 * to capacity equally well.
 *
 * <p><b>If jsprit is upgraded</b> (see BACKLOG "jsprit-Upgrade 1.8 → 2.x"), re-measure
 * {@link #EXPECTED_TOURS} and {@link #BEST_INSERTION_TOURS} rather than deleting the test — the
 * point of the pin is that the tour count is a property somebody can silently regress.
 */
@DisplayName("jsprit construction heuristic: REGRET_INSERTION needs fewer vehicles than BEST_INSERTION")
class JspritConstructionHeuristicTest {

    /** 7x7 grid minus the depot corner = 48 stops; small enough to route in ~2 s. */
    private static final int GRID_SIDE = 7;
    private static final double SPACING_M = 800.0;
    /** Generous, so vehicle capacity never binds and the duration cap is what limits a tour. */
    private static final int VAN_CAPACITY = 200;
    /** Explicit short cap (1 h) instead of the 7 h shift, so the fixture can stay small. */
    private static final int ROUTE_DURATION_CAP_S = 3600;
    private static final int JSPRIT_ITERS = 20;

    /** Measured with REGRET_INSERTION (jsprit's default, and what production must use). */
    private static final int EXPECTED_TOURS = 4;
    /** Measured with BEST_INSERTION on the identical fixture — what this test must reject. */
    private static final int BEST_INSERTION_TOURS = 5;
    /** jsprit score (negative cost) of the BEST_INSERTION solution; REGRET reached -649.65. */
    private static final double BEST_INSERTION_SCORE = -809.20;

    @Test
    @DisplayName("48 duration-bound stops fit in 4 tours, not the 5 that greedy insertion opens")
    void regretInsertionNeedsFewerVehicles() {
        Fixture f = new Fixture();

        LausitzFreightPreprocessor.routeWithDurationCap(f.carriers, f.net, f.types,
                JSPRIT_ITERS, ROUTE_DURATION_CAP_S);

        Carrier carrier = f.carriers.getCarriers().values().iterator().next();
        assertThat(carrier.getSelectedPlan()).as("fixture must be routed").isNotNull();

        assertThat(carrier.getSelectedPlan().getScheduledTours())
                .as("REGRET_INSERTION must cover these 48 stops with %d tours; %d means the "
                        + "CONSTRUCTION heuristic was switched back to BEST_INSERTION "
                        + "(HAGRIDRouterUtils#configureAlgorithm), which costs ~1 vehicle per carrier",
                        EXPECTED_TOURS, BEST_INSERTION_TOURS)
                .hasSize(EXPECTED_TOURS);

        // Second, independent signal: the objective, not just the vehicle count. Loose bound rather
        // than the exact -649.65 so an unrelated cost-parameter change does not turn this into noise.
        assertThat(carrier.getSelectedPlan().getJspritScore())
                .as("solution must be clearly better than the BEST_INSERTION result (%.2f)",
                        BEST_INSERTION_SCORE)
                .isGreaterThan(BEST_INSERTION_SCORE + 50.0);
    }

    @Test
    @DisplayName("no stop is dropped — a cheaper plan that loses parcels would be a false pass")
    void everyStopIsServed() {
        Fixture f = new Fixture();

        LausitzFreightPreprocessor.routeWithDurationCap(f.carriers, f.net, f.types,
                JSPRIT_ITERS, ROUTE_DURATION_CAP_S);

        Carrier carrier = f.carriers.getCarriers().values().iterator().next();
        long served = carrier.getSelectedPlan().getScheduledTours().stream()
                .flatMap(t -> t.getTour().getTourElements().stream())
                .filter(e -> e instanceof org.matsim.freight.carriers.Tour.ServiceActivity)
                .count();
        // Fewer tours are only an improvement if the work still gets done; unassigned jobs would
        // otherwise let a broken configuration look like a better one.
        assertThat(served)
                .as("all %d services must be scheduled", f.stopCount)
                .isEqualTo(f.stopCount);
    }

    /**
     * A {@link #GRID_SIDE}x{@link #GRID_SIDE} grid network with one delivery per node except the
     * depot corner. A grid (rather than the line network used elsewhere in these tests) is what
     * makes the two heuristics diverge: on a line the visiting order is trivial, so greedy
     * insertion cannot go wrong.
     */
    private static final class Fixture {
        final Network net;
        final CarrierVehicleTypes types = new CarrierVehicleTypes();
        final Carriers carriers = new Carriers();
        final int stopCount;

        Fixture() {
            net = grid();
            VehicleType van = VehicleUtils.createVehicleType(
                    Id.create("ct_cep_size_s", VehicleType.class));
            van.getCapacity().setOther(VAN_CAPACITY);
            van.setNetworkMode("car");
            // Real ct_cep_size_s rates, so the objective has the production cost structure.
            van.getCostInformation().setCostsPerMeter(3.57223241590213E-4)
                    .setCostsPerSecond(0.0).setFixedCost(154.41);
            types.getVehicleTypes().put(van.getId(), van);

            List<Delivery> deliveries = new ArrayList<>();
            for (int x = 0; x < GRID_SIDE; x++) {
                for (int y = 0; y < GRID_SIDE; y++) {
                    if (x == 0 && y == 0) {
                        continue; // depot corner
                    }
                    deliveries.add(Delivery.builder().id("p_" + deliveries.size())
                            .coordinate(new Coord(SPACING_M * x, SPACING_M * y)).provider("probe")
                            .parcelType(Delivery.ParcelType.B2B).amount(1)
                            .deliveryMode(Delivery.DeliveryMode.HOME).postalCode("02977").build());
                }
            }
            stopCount = deliveries.size();
            carriers.addCarrier(LmdCarrierBuilder.build("probe", deliveries,
                    Id.createLinkId("dep_out"), net, new VehicleType[]{van},
                    2, 15, List.of(8), new Random(1)));
        }

        private static Network grid() {
            Network net = NetworkUtils.createNetwork();
            Node[][] nodes = new Node[GRID_SIDE][GRID_SIDE];
            for (int x = 0; x < GRID_SIDE; x++) {
                for (int y = 0; y < GRID_SIDE; y++) {
                    nodes[x][y] = NetworkUtils.createAndAddNode(net,
                            Id.createNodeId("n_" + x + "_" + y), new Coord(SPACING_M * x, SPACING_M * y));
                }
            }
            for (int x = 0; x < GRID_SIDE; x++) {
                for (int y = 0; y < GRID_SIDE; y++) {
                    if (x + 1 < GRID_SIDE) {
                        link(net, nodes[x][y], nodes[x + 1][y], "h" + x + "_" + y);
                    }
                    if (y + 1 < GRID_SIDE) {
                        link(net, nodes[x][y], nodes[x][y + 1], "v" + x + "_" + y);
                    }
                }
            }
            Node depot = NetworkUtils.createAndAddNode(net, Id.createNodeId("depot"),
                    new Coord(-SPACING_M, 0));
            NetworkUtils.createAndAddLink(net, Id.createLinkId("dep_out"), depot, nodes[0][0],
                    SPACING_M, 13.9, 1800, 1);
            NetworkUtils.createAndAddLink(net, Id.createLinkId("dep_in"), nodes[0][0], depot,
                    SPACING_M, 13.9, 1800, 1);
            return net;
        }

        private static void link(Network net, Node a, Node b, String id) {
            NetworkUtils.createAndAddLink(net, Id.createLinkId("f_" + id), a, b,
                    SPACING_M, 13.9, 1800, 1);
            NetworkUtils.createAndAddLink(net, Id.createLinkId("r_" + id), b, a,
                    SPACING_M, 13.9, 1800, 1);
        }
    }
}
