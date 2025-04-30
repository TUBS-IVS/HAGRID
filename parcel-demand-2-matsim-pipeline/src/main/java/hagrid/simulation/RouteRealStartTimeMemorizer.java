package hagrid.simulation;


import com.graphhopper.jsprit.core.algorithm.state.StateId;
import com.graphhopper.jsprit.core.algorithm.state.StateManager;
import com.graphhopper.jsprit.core.algorithm.state.StateUpdater;
import com.graphhopper.jsprit.core.problem.cost.VehicleRoutingTransportCosts;
import com.graphhopper.jsprit.core.problem.solution.route.RouteVisitor;
import com.graphhopper.jsprit.core.problem.solution.route.VehicleRoute;
import com.graphhopper.jsprit.core.problem.solution.route.activity.TourActivity;

import java.util.Iterator;

/**
 * Created by HOTDESK1 on 5/8/2016.
 */
public class RouteRealStartTimeMemorizer implements StateUpdater, RouteVisitor {

    private final StateManager stateManager;
    private final VehicleRoutingTransportCosts routingCosts;
    private final StateId stateId;

    public RouteRealStartTimeMemorizer(StateManager stateManager, VehicleRoutingTransportCosts routingCosts) {
        super();
        this.stateManager = stateManager;
        this.routingCosts = routingCosts;
        this.stateId = this.stateManager.createStateId("routeRealStartTime");
    }

    @Override
    public void visit(VehicleRoute route) {
        if (!route.isEmpty()) {
            Iterator<TourActivity> tourActivityIterator = route.getTourActivities().iterator();
            TourActivity firstAct = tourActivityIterator.next();
            double routeStartTime = route.getStart().getEndTime();
            double tp_time_start_firstAct = this.routingCosts.getTransportTime(
                    route.getStart().getLocation(), firstAct.getLocation(),
                    routeStartTime, route.getDriver(), route.getVehicle());
            double firstActArrTime = routeStartTime + tp_time_start_firstAct;
            double firstActStartTime = Math.max(
                    firstActArrTime,
                    firstAct.getTheoreticalEarliestOperationStartTime());
            double tp_time_start_firstAct_backward = this.routingCosts.getBackwardTransportTime(
                    route.getStart().getLocation(), firstAct.getLocation(),
                    firstActStartTime, route.getDriver(), route.getVehicle());
            double routeRealStartTime = firstActStartTime - tp_time_start_firstAct_backward;
            this.stateManager.putRouteState(route, this.stateId, routeRealStartTime);
        }
    }
}
