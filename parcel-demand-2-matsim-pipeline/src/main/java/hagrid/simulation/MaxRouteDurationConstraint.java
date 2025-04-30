package hagrid.simulation;


import com.graphhopper.jsprit.core.algorithm.state.InternalStates;
import com.graphhopper.jsprit.core.algorithm.state.StateId;
import com.graphhopper.jsprit.core.algorithm.state.StateManager;
import com.graphhopper.jsprit.core.problem.constraint.HardActivityConstraint;
import com.graphhopper.jsprit.core.problem.cost.VehicleRoutingTransportCosts;
import com.graphhopper.jsprit.core.problem.job.Service;
import com.graphhopper.jsprit.core.problem.misc.JobInsertionContext;
import com.graphhopper.jsprit.core.problem.solution.route.activity.End;
import com.graphhopper.jsprit.core.problem.solution.route.activity.Start;
import com.graphhopper.jsprit.core.problem.solution.route.activity.TourActivity;
import com.graphhopper.jsprit.core.util.CalculationUtils;

/**
 * Created by HOTDESK1 on 4/8/2016.
 */
public class MaxRouteDurationConstraint implements HardActivityConstraint {

    private final StateManager stateManager;
    private final double maxRouteDuration;
    private final VehicleRoutingTransportCosts routingCosts;
    private final StateId stateId;

    public MaxRouteDurationConstraint(double maxRouteDuration, StateManager stateManager, VehicleRoutingTransportCosts routingCosts) {
        this.maxRouteDuration = maxRouteDuration;
        this.routingCosts = routingCosts;
        this.stateManager = stateManager;
        this.stateId = this.stateManager.createStateId("routeRealStartTime");
    }

    @Override
    public ConstraintsStatus fulfilled(JobInsertionContext iFacts, TourActivity prevAct, TourActivity newAct, TourActivity nextAct, double depTimeAtPrevAct) {

        Double oldDuration = 0.0;

        if (!iFacts.getRoute().isEmpty()) {
            double routeEndTime = iFacts.getRoute().getEnd().getArrTime();
            double routeRealStartTime = this.stateManager.getRouteState(iFacts.getRoute(), stateId, Double.class);
            oldDuration = routeEndTime - routeRealStartTime;
        }

        if (oldDuration > this.maxRouteDuration && !(prevAct instanceof Start))
            return ConstraintsStatus.NOT_FULFILLED_BREAK;

        double tp_time_prevAct_newAct = this.routingCosts.getTransportTime(prevAct.getLocation(), newAct.getLocation(), depTimeAtPrevAct, iFacts.getNewDriver(), iFacts.getNewVehicle());
        double newAct_arrTime = depTimeAtPrevAct + tp_time_prevAct_newAct;
        double newAct_startTime = Math.max(newAct_arrTime, newAct.getTheoreticalEarliestOperationStartTime());
        double newAct_endTime = newAct_startTime + newAct.getOperationTime();
        
      

        double routeDurationIncrease = 0.0;

        if (prevAct instanceof Start) {
            double tp_time_start_newAct_backward = this.routingCosts.getBackwardTransportTime(prevAct.getLocation(), newAct.getLocation(), depTimeAtPrevAct, iFacts.getNewDriver(), iFacts.getNewVehicle());
            double newRouteRealStartTime = newAct_startTime - tp_time_start_newAct_backward;
            if (iFacts.getRoute().isEmpty())
                routeDurationIncrease += depTimeAtPrevAct - newRouteRealStartTime;
            else
                routeDurationIncrease += this.stateManager.getRouteState(iFacts.getRoute(), stateId, Double.class) - newRouteRealStartTime;
        }

        if (nextAct instanceof End && !iFacts.getNewVehicle().isReturnToDepot()) {
            routeDurationIncrease += newAct_endTime - depTimeAtPrevAct;
        }
        else {
            double tp_time_newAct_nextAct = this.routingCosts.getTransportTime(newAct.getLocation(), nextAct.getLocation(), newAct_endTime, iFacts.getNewDriver(), iFacts.getNewVehicle());
            double nextAct_arrTime = newAct_endTime + tp_time_newAct_nextAct;
            double endTime_nextAct_new = CalculationUtils.getActivityEndTime(nextAct_arrTime, nextAct);

            double arrTime_nextAct = depTimeAtPrevAct + this.routingCosts.getTransportTime(prevAct.getLocation(), nextAct.getLocation(), prevAct.getEndTime(), iFacts.getRoute().getDriver(), iFacts.getRoute().getVehicle());
            double endTime_nextAct_old = CalculationUtils.getActivityEndTime(arrTime_nextAct, nextAct);

            double endTimeDelay_nextAct = Math.max(0.0D, endTime_nextAct_new - endTime_nextAct_old);
            Double futureWaiting = this.stateManager.getActivityState(nextAct, iFacts.getRoute().getVehicle(), InternalStates.FUTURE_WAITING, Double.class);
            if(futureWaiting == null) {
                futureWaiting = Double.valueOf(0.0D);
            }

            routeDurationIncrease += Math.max(0, endTimeDelay_nextAct - futureWaiting);
        }

        Double newDuration = oldDuration + routeDurationIncrease;
//        System.out.println("Route with Duration: "+ newDuration + "Compared to max Route Duration: " + this.maxRouteDuration);

        if (newDuration > this.maxRouteDuration) {
//        	System.out.println("Route with to long Duration! "+ newDuration + "Compared to max Route Duration: " + this.maxRouteDuration);        	
        	return ConstraintsStatus.NOT_FULFILLED;
        } else {
        	return ConstraintsStatus.FULFILLED;
        }   
             

    }
}

