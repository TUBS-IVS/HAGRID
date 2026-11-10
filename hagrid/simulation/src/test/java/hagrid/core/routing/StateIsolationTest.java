package hagrid.core.routing;

import com.graphhopper.jsprit.core.algorithm.state.StateManager;
import com.graphhopper.jsprit.core.problem.Location;
import com.graphhopper.jsprit.core.problem.VehicleRoutingProblem;
import com.graphhopper.jsprit.core.problem.job.Service;
import com.graphhopper.jsprit.core.problem.solution.route.VehicleRoute;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleImpl;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StateIsolationTest {
    @Test
    void solverRejectsStateIdentifiersOwnedByAnotherManager() {
        var problem = VehicleRoutingProblem.Builder.newInstance().build();
        var first = new StateManager(problem);
        var second = new StateManager(problem);
        var ids = new StateIds(first);
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException().isThrownBy(() ->
                new UpdateDepartureTimeAndPracticalTimeWindows(second, problem.getTransportCosts(), 100, ids));
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException().isThrownBy(() ->
                new TimeWindowConstraintWithDriverTime(second, problem.getTransportCosts(), 100, ids));
    }
    @Test
    void constructingAnotherSolverCannotRedirectExistingRouteStateWrites() {
        var service = Service.Builder.newInstance("stop").setLocation(Location.newInstance(0, 0)).build();
        var vehicle = VehicleImpl.Builder.newInstance("van").setStartLocation(Location.newInstance(0, 0))
                .setLatestArrival(10000).build();
        var problem = VehicleRoutingProblem.Builder.newInstance().addJob(service).addVehicle(vehicle).build();
        var costs = problem.getTransportCosts();
        var first = new StateManager(problem);
        first.createStateId("first-extra");
        var firstKey = first.createStateId("latestArrTimeAtDepot");
        var firstUpdater = new UpdateDepartureTimeAndPracticalTimeWindows(first, costs, 100);
        var firstRoute = VehicleRoute.Builder.newInstance(vehicle).addService(service).build();
        firstUpdater.begin(firstRoute);
        assertThat(first.getRouteState(firstRoute, firstKey, Double.class)).isEqualTo(100.0);

        var second = new StateManager(problem);
        for (int i = 0; i < 10; i++) second.createStateId("second-extra-" + i);
        var secondKey = second.createStateId("latestArrTimeAtDepot");
        var secondUpdater = new UpdateDepartureTimeAndPracticalTimeWindows(second, costs, 200);
        var secondRoute = VehicleRoute.Builder.newInstance(vehicle).addService(service).build();
        // Interleave independent solvers deterministically without a CPU-heavy parallel benchmark.
        secondUpdater.begin(secondRoute);
        firstUpdater.begin(firstRoute);
        assertThat(first.getRouteState(firstRoute, firstKey, Double.class)).isEqualTo(100.0);
        assertThat(second.getRouteState(secondRoute, secondKey, Double.class)).isEqualTo(200.0);
    }
}
