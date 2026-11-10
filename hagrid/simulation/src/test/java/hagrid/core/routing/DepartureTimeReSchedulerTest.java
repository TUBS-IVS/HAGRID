package hagrid.core.routing;

import com.graphhopper.jsprit.core.algorithm.box.Jsprit;
import com.graphhopper.jsprit.core.problem.Location;
import com.graphhopper.jsprit.core.problem.VehicleRoutingProblem;
import com.graphhopper.jsprit.core.problem.cost.VehicleRoutingTransportCosts;
import com.graphhopper.jsprit.core.problem.driver.Driver;
import com.graphhopper.jsprit.core.problem.job.Service;
import com.graphhopper.jsprit.core.problem.solution.VehicleRoutingProblemSolution;
import com.graphhopper.jsprit.core.problem.solution.route.VehicleRoute;
import com.graphhopper.jsprit.core.problem.solution.route.activity.TimeWindow;
import com.graphhopper.jsprit.core.problem.vehicle.Vehicle;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleImpl;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleTypeImpl;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DepartureTimeReSchedulerTest {
    @Test
    void laterDepartureMustNotViolateDeliveryWindowInCongestion() {
        var fixture = fixture(150, 1000, true);
        scheduler(fixture, 1000, 1000).informAlgorithmEnds(fixture.problem, List.of(fixture.solution));
        assertThat(fixture.route.getDepartureTime()).isEqualTo(0);
        assertThat(fixture.route.getActivities().getFirst().getArrTime()).isEqualTo(10);
        assertThat(fixture.route.getEnd().getArrTime()).isEqualTo(120);
    }

    @Test
    void laterDepartureMustNotViolateVehicleLatestArrival() {
        var fixture = fixture(1000, 125, true);
        scheduler(fixture, 1000, 1000).informAlgorithmEnds(fixture.problem, List.of(fixture.solution));
        assertThat(fixture.route.getDepartureTime()).isEqualTo(0);
        assertThat(fixture.route.getEnd().getArrTime()).isEqualTo(120);
    }

    @Test
    void actualConfiguredAlgorithmReturnsCostOfItsShiftedRoute() {
        var fixture = fixture(150, 1000, false);
        var algorithm = HAGRIDRouterUtils.configureAlgorithm(fixture.problem, 1, 1, null, 0, 25200);
        var result = com.graphhopper.jsprit.core.util.Solutions.bestOf(algorithm.searchSolutions());
        assertThat(result.getUnassignedJobs()).isEmpty();
        assertThat(result.getRoutes().iterator().next().getDepartureTime()).isEqualTo(90);
        assertThat(result.getCost()).isEqualTo(28);
        assertThat(result.getCost()).isEqualTo(algorithm.getObjectiveFunction().getCosts(result));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"150,1000", "1000,150"})
    void laterDepartureMustRespectBothTourAndDriverDuration(double routeLimit, double driverLimit) {
        var fixture = fixture(1000, 1000, true);
        double originalCost = fixture.solution.getCost();
        scheduler(fixture, routeLimit, driverLimit).informAlgorithmEnds(fixture.problem, List.of(fixture.solution));
        assertThat(fixture.route.getDepartureTime()).isEqualTo(0);
        assertThat(fixture.route.getEnd().getArrTime()).isEqualTo(120);
        assertThat(fixture.solution.getCost()).isEqualTo(originalCost);
    }

    @Test
    void feasibleButMoreExpensiveDepartureIsRolledBack() {
        var fixture = fixture(150, 1000, false);
        var originalObjective = Jsprit.Builder.newInstance(fixture.problem).buildAlgorithm().getObjectiveFunction();
        com.graphhopper.jsprit.core.problem.solution.SolutionCostCalculator tollObjective = solution ->
                originalObjective.getCosts(solution)
                        + (solution.getRoutes().iterator().next().getDepartureTime() > 0 ? 1000 : 0);
        var scheduler = new DepartureTimeReScheduler(
                new com.graphhopper.jsprit.core.algorithm.state.StateManager(fixture.problem), tollObjective, 1000, 1000);
        double originalCost = fixture.solution.getCost();
        scheduler.informAlgorithmEnds(fixture.problem, List.of(fixture.solution));
        assertThat(fixture.route.getDepartureTime()).isEqualTo(0);
        assertThat(fixture.route.getActivities().getFirst().getArrTime()).isEqualTo(10);
        assertThat(fixture.solution.getCost()).isEqualTo(originalCost);
    }

    @Test
    void schedulingPreservesUnassignedJobPenaltyOfTheSearchObjective() {
        var fixture = fixture(150, 1000, false);
        var missing = Service.Builder.newInstance("missing").setLocation(Location.newInstance("depot")).build();
        var solution = new VehicleRoutingProblemSolution(fixture.solution.getRoutes(), List.of(missing), 0);
        var objective = Jsprit.Builder.newInstance(fixture.problem).buildAlgorithm().getObjectiveFunction();
        double before = objective.getCosts(solution);
        var scheduler = new DepartureTimeReScheduler(
                new com.graphhopper.jsprit.core.algorithm.state.StateManager(fixture.problem), objective, 1000, 1000);
        scheduler.informAlgorithmEnds(fixture.problem, List.of(solution));
        assertThat(solution.getUnassignedJobs()).containsExactly(missing);
        assertThat(solution.getCost()).isEqualTo(objective.getCosts(solution));
        assertThat(before - solution.getCost()).isEqualTo(180);
    }

    @Test
    void contextFreeCompatibilityConstructorDoesNotMutateSolution() {
        var fixture = fixture(150, 1000, false);
        double before = fixture.solution.getCost();
        new DepartureTimeReScheduler().informAlgorithmEnds(fixture.problem, List.of(fixture.solution));
        assertThat(fixture.route.getDepartureTime()).isEqualTo(0);
        assertThat(fixture.solution.getCost()).isEqualTo(before);
    }

    @Test
    void noSolutionsAndEmptyRoutesNeedNoDepartureShift() {
        var fixture = fixture(150, 1000, false);
        var scheduler = scheduler(fixture, 1000, 1000);
        scheduler.informAlgorithmEnds(fixture.problem, List.of());
        var empty = new VehicleRoutingProblemSolution(List.of(), 0);
        scheduler.informAlgorithmEnds(fixture.problem, List.of(empty));
        assertThat(empty.getRoutes()).isEmpty();
        assertThat(empty.getCost()).isEqualTo(0);
    }
    private static DepartureTimeReScheduler scheduler(Fixture fixture, double routeLimit, double driverLimit) {
        return new DepartureTimeReScheduler(new com.graphhopper.jsprit.core.algorithm.state.StateManager(fixture.problem),
                Jsprit.Builder.newInstance(fixture.problem).buildAlgorithm().getObjectiveFunction(), routeLimit, driverLimit);
    }

    private static Fixture fixture(double latestWindow, double latestArrival, boolean congestion) {
        var type = VehicleTypeImpl.Builder.newInstance("type").addCapacityDimension(0, 10)
                .setFixedCost(3).setCostPerWaitingTime(2).setCostPerServiceTime(0.5).build();
        var vehicle = VehicleImpl.Builder.newInstance("van").setType(type)
                .setStartLocation(Location.newInstance("depot")).setLatestArrival(latestArrival).build();
        var service = Service.Builder.newInstance("parcel").setLocation(Location.newInstance("stop"))
                .setServiceTime(10).setTimeWindow(TimeWindow.newInstance(100, latestWindow)).build();
        var costs = new VehicleRoutingTransportCosts() {
            public double getTransportTime(Location from, Location to, double time, Driver driver, Vehicle van) {
                if (from.getId().equals(to.getId())) return 0;
                return congestion && ((from.getId().equals("depot") && time >= 50)
                        || (from.getId().equals("stop") && time >= 200)) ? 100 : 10;
            }
            public double getBackwardTransportTime(Location from, Location to, double time, Driver driver, Vehicle van) {
                return getTransportTime(from, to, time, driver, van);
            }
            public double getTransportCost(Location from, Location to, double time, Driver driver, Vehicle van) {
                return getTransportTime(from, to, time, driver, van);
            }
            public double getBackwardTransportCost(Location from, Location to, double time, Driver driver, Vehicle van) {
                return getTransportCost(from, to, time, driver, van);
            }
            public double getDistance(Location from, Location to, double time, Vehicle van) {
                return from.getId().equals(to.getId()) ? 0 : 10;
            }
        };
        var problem = VehicleRoutingProblem.Builder.newInstance().addVehicle(vehicle).addJob(service)
                .setRoutingCost(costs).setActivityCosts(new HagridActivityCosts(5)).build();
        var route = VehicleRoute.Builder.newInstance(vehicle).addService(service).build();
        new DepartureTimeReScheduler().updateActivityTimes(route, costs, problem.getActivityCosts());
        var solution = new VehicleRoutingProblemSolution(List.of(route), 0);
        solution.setCost(Jsprit.Builder.newInstance(problem).buildAlgorithm().getObjectiveFunction().getCosts(solution));
        return new Fixture(problem, route, solution);
    }

    private record Fixture(VehicleRoutingProblem problem, VehicleRoute route, VehicleRoutingProblemSolution solution) {}
}