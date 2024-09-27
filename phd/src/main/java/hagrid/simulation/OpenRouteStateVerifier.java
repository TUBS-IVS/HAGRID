package hagrid.simulation;

import com.graphhopper.jsprit.core.algorithm.state.StateUpdater;
import com.graphhopper.jsprit.core.problem.solution.route.VehicleRoute;
import com.graphhopper.jsprit.core.problem.solution.route.activity.End;
import com.graphhopper.jsprit.core.problem.solution.route.activity.ReverseActivityVisitor;
import com.graphhopper.jsprit.core.problem.solution.route.activity.TourActivity;

public class OpenRouteStateVerifier implements StateUpdater, ReverseActivityVisitor {

	private End end;

	private boolean firstAct = true;

	private com.graphhopper.jsprit.core.problem.vehicle.Vehicle vehicle;

	@Override
	public void begin(VehicleRoute route) {
		end = route.getEnd();
		vehicle = route.getVehicle();
	}

	@Override
	public void visit(TourActivity activity) {
		if (firstAct) {
			firstAct = false;
            assert vehicle.isReturnToDepot() || activity.getLocation().getId().equals(end.getLocation().getId())
                    : "route end and last activity are not equal even route is open. this should not be.";
		}

	}

	@Override
	public void finish() {
		firstAct = true;
	}

}
