package hagrid.core.routing;

import com.graphhopper.jsprit.core.problem.cost.VehicleRoutingActivityCosts;
import com.graphhopper.jsprit.core.problem.driver.Driver;
import com.graphhopper.jsprit.core.problem.solution.route.activity.TourActivity;
import com.graphhopper.jsprit.core.problem.vehicle.Vehicle;

/** Service duration comes from the job; waiting and service have distinct vehicle cost rates. */
public final class HagridActivityCosts implements VehicleRoutingActivityCosts {
    private final double latenessPenaltyPerSecond;

    public HagridActivityCosts(double latenessPenaltyPerSecond) {
        if (!Double.isFinite(latenessPenaltyPerSecond) || latenessPenaltyPerSecond < 0) {
            throw new IllegalArgumentException("Lateness penalty must be finite and nonnegative");
        }
        this.latenessPenaltyPerSecond = latenessPenaltyPerSecond;
    }

    @Override
    public double getActivityCost(TourActivity activity, double arrivalTime, Driver driver, Vehicle vehicle) {
        var rates = vehicle.getType().getVehicleCostParams();
        double late = Math.max(0, arrivalTime - activity.getTheoreticalLatestOperationStartTime());
        double waiting = Math.max(0, activity.getTheoreticalEarliestOperationStartTime() - arrivalTime);
        return latenessPenaltyPerSecond * late + rates.perWaitingTimeUnit * waiting
                + rates.perServiceTimeUnit * activity.getOperationTime();
    }

    @Override
    public double getActivityDuration(TourActivity activity, double arrivalTime, Driver driver, Vehicle vehicle) {
        return activity.getOperationTime();
    }
}
