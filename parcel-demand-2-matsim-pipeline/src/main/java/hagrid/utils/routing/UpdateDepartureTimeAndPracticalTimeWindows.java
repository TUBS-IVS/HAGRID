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
package hagrid.utils.routing;

import com.graphhopper.jsprit.core.algorithm.state.StateManager;
import com.graphhopper.jsprit.core.algorithm.state.StateUpdater;
import com.graphhopper.jsprit.core.problem.cost.VehicleRoutingTransportCosts;
import com.graphhopper.jsprit.core.problem.solution.route.VehicleRoute;
import com.graphhopper.jsprit.core.problem.solution.route.activity.ReverseActivityVisitor;
import com.graphhopper.jsprit.core.problem.solution.route.activity.TourActivity;

/**
 * Updates and memorizes latest operation start times at activities.
 * 
 * @author schroeder
 *
 */
public class UpdateDepartureTimeAndPracticalTimeWindows implements ReverseActivityVisitor, StateUpdater{

	private final StateManager states;
	
	private VehicleRoute route;
	
	private final VehicleRoutingTransportCosts transportCosts;
	
	private double latestArrTimeAtPrevAct;
	
	private TourActivity prevAct;
	
	private final double maxOperationTimeOfDriver;

	
	
	public UpdateDepartureTimeAndPracticalTimeWindows(StateManager states, VehicleRoutingTransportCosts transportCosts, double maxDriverTime) {
		super();
		this.states = states;
		this.transportCosts = transportCosts;
		maxOperationTimeOfDriver = maxDriverTime;
		new StateIds(states).createStateIds();
	}

	@Override
	public void begin(VehicleRoute route) {
		this.route = route;
		double newDepartureTime = getNewDepartureTime(route);
		double latestArrAtDepot = Math.min(route.getEnd().getTheoreticalLatestOperationStartTime(),newDepartureTime+maxOperationTimeOfDriver);
		states.putRouteState(route, StateIds.LATEST_ARR_AT_DEPOT, latestArrAtDepot);
		latestArrTimeAtPrevAct = latestArrAtDepot;
		prevAct = route.getEnd();
	}

	@Override
	public void visit(TourActivity activity) {
		double potentialLatestArrivalTimeAtCurrAct = latestArrTimeAtPrevAct - transportCosts.getBackwardTransportTime(activity.getLocation(), prevAct.getLocation(), latestArrTimeAtPrevAct, route.getDriver(),route.getVehicle()) - activity.getOperationTime();
		double latestArrivalTime = Math.min(activity.getTheoreticalLatestOperationStartTime(), potentialLatestArrivalTimeAtCurrAct);
		states.putActivityState(activity, StateIds.LATEST_ACTIVITY_START, latestArrivalTime);
		
		latestArrTimeAtPrevAct = latestArrivalTime;
		prevAct = activity;
	}

	@Override
	public void finish() {}
	
	
	public double getNewDepartureTime(VehicleRoute route){
		double earliestDepartureTime = route.getDepartureTime();
		TourActivity firstActivity = route.getActivities().get(0);
		double tpTime_startToFirst = transportCosts.getTransportTime(route.getStart().getLocation(), firstActivity.getLocation(), 
				earliestDepartureTime, null, route.getVehicle());
		double newDepartureTime = Math.max(earliestDepartureTime, firstActivity.getTheoreticalEarliestOperationStartTime()-tpTime_startToFirst);
		return newDepartureTime;
	}

}