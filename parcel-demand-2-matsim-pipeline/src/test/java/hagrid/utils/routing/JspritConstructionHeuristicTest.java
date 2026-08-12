package hagrid.utils.routing;

import com.graphhopper.jsprit.core.algorithm.VehicleRoutingAlgorithm;
import com.graphhopper.jsprit.core.problem.VehicleRoutingProblem;
import com.graphhopper.jsprit.core.problem.solution.VehicleRoutingProblemSolution;
import com.graphhopper.jsprit.core.util.Solutions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.network.NetworkUtils;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierCapabilities.FleetSize;
import org.matsim.freight.carriers.CarrierService;
import org.matsim.freight.carriers.CarrierVehicle;
import org.matsim.freight.carriers.CarriersUtils;
import org.matsim.freight.carriers.TimeWindow;
import org.matsim.freight.carriers.jsprit.NetworkBasedTransportCosts;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the jsprit construction heuristic ({@code Jsprit.Parameter.CONSTRUCTION}) to
 * REGRET_INSERTION by asserting an OUTCOME, not the configured constant.
 *
 * <p>Why: HAGRID used to override jsprit's own default with BEST_INSERTION, which cost roughly one
 * vehicle per carrier. BEST_INSERTION is greedy per job — each job lands wherever it is cheapest AT
 * THE MOMENT of insertion, and opening a fresh route costs nothing but the depot stub, because the
 * vehicle's fixed cost is invisible during insertion (jsprit's FIXED_COST_PARAM defaults to 0 and
 * the fixed-cost branch of JobInsertionCostsCalculatorBuilder is commented out in 1.8).
 * Ruin-and-recreate cannot repair that afterwards: a half-emptied tour still pays its full fixed
 * cost, so every intermediate state scores worse and the acceptor rejects the path out.
 *
 * <p>A test asserting {@code CONSTRUCTION == REGRET_INSERTION} would only restate the production
 * line and would pass for any behaviour, so this routes a fixture on which the two heuristics
 * measurably disagree, through the production entry points
 * ({@link HAGRIDRouterUtils#createRoutingProblem} + {@link HAGRIDRouterUtils#configureAlgorithm}).
 *
 * <p>The fixture is deliberately DURATION-bound (generous vehicle capacity, short explicit route cap):
 * a capacity-bound fixture does not discriminate, both heuristics pack unit demands to capacity
 * equally well. Both constants below were MEASURED on this exact fixture by shadow-compiling
 * HAGRIDRouterUtils with the single CONSTRUCTION line flipped (2026-08-12):
 *
 * <pre>
 *   iterations   REGRET (production)      BEST_INSERTION
 *   20           3 routes / 497.52        4 routes / 654.22
 *   100          3 routes / 497.52        4 routes / 651.93
 * </pre>
 *
 * <p>The test runs at BOTH iteration counts on purpose. On smaller fixtures many parameter
 * combinations show a gap at 20 iterations that ruin-and-recreate closes again by 100 — the gap
 * would then be an artefact of a short search, not the regression. This combination keeps the gap
 * at both, which is the property worth pinning.
 *
 * <p><b>If jsprit is upgraded</b>, re-measure {@link #EXPECTED_ROUTES} and
 * {@link #BEST_INSERTION_ROUTES} rather than deleting the test — the point of the pin is that the
 * vehicle count is a property somebody can silently regress.
 */
@DisplayName("jsprit construction heuristic: REGRET_INSERTION needs fewer vehicles than BEST_INSERTION")
class JspritConstructionHeuristicTest {

    /** 7x7 grid minus the depot corner = 48 stops; small enough to route in a few seconds. */
    private static final int GRID_SIDE = 7;
    private static final double SPACING_M = 800.0;
    /** Generous, so vehicle capacity never binds and the duration cap is what limits a tour. */
    private static final int VAN_CAPACITY = 200;
    /** Explicit short cap (1 h) instead of the 7 h shift, so the fixture can stay small. */
    private static final int ROUTE_DURATION_CAP_S = 3600;
    private static final int SERVICE_DURATION_S = 60;
    private static final double WINDOW_START = 8 * 3600;
    private static final double WINDOW_END = 21 * 3600;

    /** Measured with REGRET_INSERTION (jsprit's default, and what production must use). */
    private static final int EXPECTED_ROUTES = 3;
    /** Measured with BEST_INSERTION on the identical fixture — what this test must reject. */
    private static final int BEST_INSERTION_ROUTES = 4;
    /** Cheapest BEST_INSERTION solution cost seen across both iteration counts (conservative bound). */
    private static final double BEST_INSERTION_COST = 651.93;

    @ParameterizedTest(name = "{0} jsprit iterations")
    @ValueSource(ints = { 20, 100 })
    @DisplayName("48 duration-bound stops fit in 3 tours, not the 4 that greedy insertion opens")
    void regretInsertionNeedsFewerVehicles(int iterations) {
        Fixture f = new Fixture();
        VehicleRoutingProblemSolution solution = route(f, iterations);

        assertThat(solution.getRoutes())
                .as("REGRET_INSERTION must cover these %d stops with %d routes; %d means the "
                        + "CONSTRUCTION heuristic was switched back to BEST_INSERTION in "
                        + "HAGRIDRouterUtils#configureAlgorithm, which costs ~1 vehicle per carrier",
                        f.stopCount, EXPECTED_ROUTES, BEST_INSERTION_ROUTES)
                .hasSize(EXPECTED_ROUTES);

        // Second, independent signal: the objective, not just the vehicle count. Loose bound rather
        // than the exact 497.52 so an unrelated cost-parameter change does not turn this into noise.
        assertThat(solution.getCost())
                .as("solution must be clearly cheaper than the BEST_INSERTION result (%.2f)",
                        BEST_INSERTION_COST)
                .isLessThan(BEST_INSERTION_COST - 50.0);
    }

    @ParameterizedTest(name = "{0} jsprit iterations")
    @ValueSource(ints = { 20, 100 })
    @DisplayName("no stop is dropped — a cheaper plan that loses parcels would be a false pass")
    void everyStopIsServed(int iterations) {
        Fixture f = new Fixture();
        VehicleRoutingProblemSolution solution = route(f, iterations);

        // Fewer routes are only an improvement if the work still gets done; unassigned jobs would
        // otherwise let a broken configuration look like a better one.
        assertThat(solution.getUnassignedJobs())
                .as("every one of the %d stops must be assigned", f.stopCount)
                .isEmpty();
        long served = solution.getRoutes().stream().mapToLong(r -> r.getTourActivities().getJobs().size()).sum();
        assertThat(served).as("all %d services must be routed", f.stopCount).isEqualTo(f.stopCount);
    }

    /** Routes the fixture through the production configuration path. */
    private static VehicleRoutingProblemSolution route(Fixture f, int iterations) {
        NetworkBasedTransportCosts.Builder costBuilder =
                NetworkBasedTransportCosts.Builder.newInstance(f.net, List.of(f.van));
        costBuilder.setTimeSliceWidth(1800);

        VehicleRoutingProblem vrp = HAGRIDRouterUtils.createRoutingProblem(
                f.carrier, f.net, costBuilder.build());
        VehicleRoutingAlgorithm algorithm = HAGRIDRouterUtils.configureAlgorithm(
                vrp, f.stopCount, iterations, f.net, 0.0, ROUTE_DURATION_CAP_S);
        return Solutions.bestOf(algorithm.searchSolutions());
    }

    /**
     * A {@link #GRID_SIDE}x{@link #GRID_SIDE} grid network with one stop per node except the depot
     * corner. A grid (rather than a line network) is what makes the two heuristics diverge: on a
     * line the visiting order is trivial, so greedy insertion cannot go wrong.
     */
    private static final class Fixture {
        final Network net;
        final VehicleType van;
        final Carrier carrier;
        final int stopCount;

        Fixture() {
            Map<String, String> stopLinks = new LinkedHashMap<>();
            net = grid(stopLinks);

            van = VehicleUtils.createVehicleType(Id.create("ct_cep_size_s", VehicleType.class));
            van.getCapacity().setOther(VAN_CAPACITY);
            van.setNetworkMode("car");
            van.setMaximumVelocity(13.9);
            // Real ct_cep_size_s rates, so the objective has the production cost structure
            // (no time term — see METHODS-LOG on the LMD cost function).
            van.getCostInformation().setCostsPerMeter(3.57223241590213E-4)
                    .setCostsPerSecond(0.0).setFixedCost(154.41);

            carrier = CarriersUtils.createCarrier(Id.create("probe", Carrier.class));
            // INFINITE, as both production routing paths use: an unassigned stop is then never
            // "out of vehicles", it is structurally infeasible.
            carrier.getCarrierCapabilities().setFleetSize(FleetSize.INFINITE);
            CarrierVehicle.Builder vehicle = CarrierVehicle.Builder.newInstance(
                    Id.create("probe_veh", Vehicle.class), Id.createLinkId("dep_out"), van);
            vehicle.setEarliestStart(WINDOW_START);
            vehicle.setLatestEnd(WINDOW_END);
            CarriersUtils.addCarrierVehicle(carrier, vehicle.build());

            int i = 0;
            for (String linkId : stopLinks.values()) {
                CarrierService.Builder service = CarrierService.Builder.newInstance(
                        Id.create("s_" + i++, CarrierService.class), Id.createLinkId(linkId));
                service.setCapacityDemand(1);
                service.setServiceDuration(SERVICE_DURATION_S);
                service.setServiceStartingTimeWindow(TimeWindow.newInstance(WINDOW_START, WINDOW_END));
                CarriersUtils.addService(carrier, service.build());
            }
            stopCount = carrier.getServices().size();
        }

        /** Builds the grid and maps each stop node to one distinct incident link id. */
        private static Network grid(Map<String, String> stopLinks) {
            Network net = NetworkUtils.createNetwork();
            Node[][] nodes = new Node[GRID_SIDE][GRID_SIDE];
            for (int x = 0; x < GRID_SIDE; x++) {
                for (int y = 0; y < GRID_SIDE; y++) {
                    nodes[x][y] = NetworkUtils.createAndAddNode(net, Id.createNodeId("n_" + x + "_" + y),
                            new Coord(SPACING_M * x, SPACING_M * y));
                }
            }
            List<String> forwardLinks = new ArrayList<>();
            for (int x = 0; x < GRID_SIDE; x++) {
                for (int y = 0; y < GRID_SIDE; y++) {
                    if (x + 1 < GRID_SIDE) {
                        forwardLinks.add(link(net, nodes[x][y], nodes[x + 1][y], "h" + x + "_" + y));
                    }
                    if (y + 1 < GRID_SIDE) {
                        forwardLinks.add(link(net, nodes[x][y], nodes[x][y + 1], "v" + x + "_" + y));
                    }
                }
            }
            for (int x = 0; x < GRID_SIDE; x++) {
                for (int y = 0; y < GRID_SIDE; y++) {
                    if (x == 0 && y == 0) {
                        continue; // depot corner carries no stop
                    }
                    String lid = x > 0 ? "f_h" + (x - 1) + "_" + y : "f_v" + x + "_" + (y - 1);
                    if (!forwardLinks.contains(lid)) {
                        // Fail loudly: a silently missing stop link would shrink the fixture and
                        // make the pinned route count meaningless.
                        throw new IllegalStateException("fixture is broken, no such link: " + lid);
                    }
                    stopLinks.put(x + "_" + y, lid);
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

        private static String link(Network net, Node a, Node b, String id) {
            NetworkUtils.createAndAddLink(net, Id.createLinkId("f_" + id), a, b, SPACING_M, 13.9, 1800, 1);
            NetworkUtils.createAndAddLink(net, Id.createLinkId("r_" + id), b, a, SPACING_M, 13.9, 1800, 1);
            return "f_" + id;
        }
    }
}
