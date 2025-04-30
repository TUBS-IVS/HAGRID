package hagrid.utils.routing;

/*******************************************************************************
 * Copyright (C) 2013  Stefan Schroeder
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either 
 * version 3.0 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public 
 * License along with this library.  If not, see <http://www.gnu.org/licenses/>.
 ******************************************************************************/

import java.util.Collection;

import com.graphhopper.jsprit.core.algorithm.listener.AlgorithmEndsListener;
import com.graphhopper.jsprit.core.algorithm.state.UpdateActivityTimes;
import com.graphhopper.jsprit.core.problem.VehicleRoutingProblem;
import com.graphhopper.jsprit.core.problem.cost.TransportTime;
import com.graphhopper.jsprit.core.problem.cost.VehicleRoutingActivityCosts;
import com.graphhopper.jsprit.core.problem.solution.VehicleRoutingProblemSolution;
import com.graphhopper.jsprit.core.problem.solution.route.RouteActivityVisitor;
import com.graphhopper.jsprit.core.problem.solution.route.VehicleRoute;
import com.graphhopper.jsprit.core.problem.solution.route.activity.TourActivity;
import com.graphhopper.jsprit.core.util.Solutions;

public class DepartureTimeReScheduler implements AlgorithmEndsListener {

	@Override
	public void informAlgorithmEnds(VehicleRoutingProblem problem,Collection<VehicleRoutingProblemSolution> solutions) {
		VehicleRoutingProblemSolution solution = Solutions.bestOf(solutions);
		
		for(VehicleRoute route : solution.getRoutes()){
			if(!route.isEmpty()){
				double earliestDepartureTime = route.getDepartureTime();
				TourActivity firstActivity = route.getActivities().get(0);
				double tpTime_startToFirst = problem.getTransportCosts().getTransportTime(route.getStart().getLocation(), firstActivity.getLocation(), 
						earliestDepartureTime, null, route.getVehicle());
				double newDepartureTime = Math.max(earliestDepartureTime, firstActivity.getTheoreticalEarliestOperationStartTime()-tpTime_startToFirst);
				route.setVehicleAndDepartureTime(route.getVehicle(), newDepartureTime);
				
				updateActivityTimes(route, problem.getTransportCosts() , problem.getActivityCosts());
			}
		}
	}
	
	public void updateActivityTimes(VehicleRoute route, TransportTime transportTimes, VehicleRoutingActivityCosts vehicleRoutingActivityCosts){
		RouteActivityVisitor routeVisitor = new RouteActivityVisitor();
		routeVisitor.addActivityVisitor(new UpdateActivityTimes(transportTimes , vehicleRoutingActivityCosts));
		routeVisitor.visit(route);
	}
	
}