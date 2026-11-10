package hagrid.core.routing;

import com.graphhopper.jsprit.core.problem.Location;
import com.graphhopper.jsprit.core.problem.cost.VehicleRoutingActivityCosts;
import com.graphhopper.jsprit.core.problem.job.Service;
import com.graphhopper.jsprit.core.problem.job.Shipment;
import com.graphhopper.jsprit.core.problem.solution.route.activity.*;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleImpl;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleTypeImpl;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class CarrierRulesTest {
    static Stream<TourActivity> activities() {
        var service = Service.Builder.newInstance("service").setLocation(Location.newInstance(0, 0))
                .setServiceTime(120).build();
        var shipment = Shipment.Builder.newInstance("shipment")
                .setPickupLocation(Location.newInstance(0, 0)).setDeliveryLocation(Location.newInstance(1, 0))
                .setPickupServiceTime(120).setDeliveryServiceTime(120).build();
        return Stream.of(ServiceActivity.newInstance(service), new PickupShipment(shipment), new DeliverShipment(shipment));
    }

    @ParameterizedTest @MethodSource("activities")
    void serviceTimeDoesNotDependOnUncomputedActivityEndTimes(TourActivity activity) throws Exception {
        // Fresh activities have no computed arrival/end timestamps, but already define 120 s service.
        assertThat(replanningActivityCosts().getActivityDuration(activity, 0, null, vehicle())).isEqualTo(120.0);
    }

    @ParameterizedTest @MethodSource("activities")
    void serviceCostUsesServiceRateRatherThanWaitingRate(TourActivity activity) throws Exception {
        assertThat(replanningActivityCosts().getActivityCost(activity, 0, null, vehicle())).isEqualTo(60.0);
    }

    @ParameterizedTest @MethodSource("activities")
    void waitingAndServiceUseIndependentCostRates(TourActivity activity) throws Exception {
        activity.setTheoreticalEarliestOperationStartTime(150);
        activity.setTheoreticalLatestOperationStartTime(300);
        // Arrival 100: 50 s waiting * 2 + 120 s service * 0.5 = 160.
        assertThat(replanningActivityCosts().getActivityCost(activity, 100, null, vehicle())).isEqualTo(160.0);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"25200,25200,true", "25201,25200,false", "5400,5400,true", "5401,5400,false"})
    void routeBoundaryRespectsHannoverAndExplicitLausitzLimit(int serviceSeconds, int limit, boolean feasible) {
        var job = Service.Builder.newInstance("boundary").setLocation(Location.newInstance(0, 0))
                .setServiceTime(serviceSeconds).build();
        var van = VehicleImpl.Builder.newInstance("boundary-van").setType(VehicleTypeImpl.Builder.newInstance("type")
                .addCapacityDimension(0, 10).build()).setStartLocation(Location.newInstance(0, 0)).setLatestArrival(100000).build();
        var vrp = com.graphhopper.jsprit.core.problem.VehicleRoutingProblem.Builder.newInstance()
                .addVehicle(van).addJob(job).build();
        var solution = com.graphhopper.jsprit.core.util.Solutions.bestOf(
                HAGRIDRouterUtils.configureAlgorithm(vrp, 1, 1, null, 0, limit).searchSolutions());
        assertThat(solution.getUnassignedJobs()).hasSize(feasible ? 0 : 1);
        assertThat(solution.getRoutes()).hasSize(feasible ? 1 : 0);
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.CsvSource({"119,false", "120,true"})
    void sharedConfiguredSolverHonoursDriverLimit(int driverSeconds, boolean feasible) {
        var config = configured(Map.of("tourRules.maxDriverTimeSeconds", Integer.toString(driverSeconds)));
        var solution = com.graphhopper.jsprit.core.util.Solutions.bestOf(
                HAGRIDRouterUtils.configureAlgorithm(singleServiceProblem(), config, 42, 1).searchSolutions());
        assertThat(solution.getUnassignedJobs()).hasSize(feasible ? 0 : 1);
    }

    @org.junit.jupiter.api.Test
    void explicitSolverSeedDoesNotGetReplacedByLegacyGlobalProperty() {
        String previous = System.getProperty(HAGRIDRouterUtils.JSPRIT_SEED_PROPERTY);
        try {
            System.setProperty(HAGRIDRouterUtils.JSPRIT_SEED_PROPERTY, "invalid-global-seed");
            var solution = com.graphhopper.jsprit.core.util.Solutions.bestOf(
                    HAGRIDRouterUtils.configureAlgorithm(singleServiceProblem(), configured(Map.of()), 42, 1).searchSolutions());
            assertThat(solution.getUnassignedJobs()).isEmpty();
        } finally {
            if (previous == null) System.clearProperty(HAGRIDRouterUtils.JSPRIT_SEED_PROPERTY);
            else System.setProperty(HAGRIDRouterUtils.JSPRIT_SEED_PROPERTY, previous);
        }
    }

    @org.junit.jupiter.api.Test
    void configuredUTurnPenaltyRequiresItsActualNetworkContext() {
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException().isThrownBy(() ->
                HAGRIDRouterUtils.configureAlgorithm(singleServiceProblem(),
                        configured(Map.of("tourRules.uTurnPenaltyCost", "2")), 42, 1))
                .withMessageContaining("network");
    }

    @org.junit.jupiter.api.Test
    void sharedSolverRejectsJobsWithoutRequiredVehicleSkills() {
        var service = Service.Builder.newInstance("cold-parcels").setLocation(Location.newInstance(0, 0))
                .addRequiredSkill("cold").build();
        var vrp = com.graphhopper.jsprit.core.problem.VehicleRoutingProblem.Builder.newInstance()
                .addVehicle(vehicle()).addJob(service).build();
        var solution = com.graphhopper.jsprit.core.util.Solutions.bestOf(
                HAGRIDRouterUtils.configureAlgorithm(vrp, configured(Map.of()), 42, 1).searchSolutions());
        assertThat(solution.getUnassignedJobs()).containsExactly(service);
    }

    @org.junit.jupiter.api.Test
    void defaultSolverSeedPreservesPinnedLibraryRandomSequence() {
        String previous = System.getProperty(HAGRIDRouterUtils.JSPRIT_SEED_PROPERTY);
        try {
            System.clearProperty(HAGRIDRouterUtils.JSPRIT_SEED_PROPERTY);
            long first = new java.util.Random(HAGRIDRouterUtils.resolveSolverSeed()).nextLong();
            assertThat(first).isEqualTo(com.graphhopper.jsprit.core.util.RandomNumberGeneration.newInstance().nextLong());
        } finally {
            if (previous != null) System.setProperty(HAGRIDRouterUtils.JSPRIT_SEED_PROPERTY, previous);
        }
    }

    private static hagrid.core.config.ResolvedHagridConfig configured(Map<String, String> overrides) {
        var settings = new java.util.LinkedHashMap<>(overrides);
        settings.putIfAbsent("tourRules.uTurnPenaltyCost", "0");
        settings.put("tourRules.maxRouteDurationSeconds", "1000");
        settings.put("optimization.replanningIterations", "2");
        return hagrid.core.config.HagridConfigResolver.resolve(new hagrid.core.HagridConfig(),
                hagrid.core.config.HagridConfigResolver.hannoverTarget(),
                hagrid.core.config.HagridConfigResolver.Overrides.of(settings), Map.of());
    }

    private static com.graphhopper.jsprit.core.problem.VehicleRoutingProblem singleServiceProblem() {
        var service = Service.Builder.newInstance("single").setLocation(Location.newInstance(0, 0)).setServiceTime(120).build();
        return com.graphhopper.jsprit.core.problem.VehicleRoutingProblem.Builder.newInstance()
                .addJob(service).addVehicle(vehicle()).setActivityCosts(new HagridActivityCosts(5)).build();
    }

    private static VehicleImpl vehicle() {
        var type = VehicleTypeImpl.Builder.newInstance("van").addCapacityDimension(0, 10)
                .setCostPerWaitingTime(2).setCostPerServiceTime(0.5).build();
        return VehicleImpl.Builder.newInstance("van").setType(type).setStartLocation(Location.newInstance(0, 0)).build();
    }

    private static VehicleRoutingActivityCosts replanningActivityCosts() throws Exception {
        return new HagridActivityCosts(5);
    }
}
