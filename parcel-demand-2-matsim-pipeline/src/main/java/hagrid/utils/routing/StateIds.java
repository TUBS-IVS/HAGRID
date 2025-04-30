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

import com.graphhopper.jsprit.core.algorithm.state.StateId;
import com.graphhopper.jsprit.core.algorithm.state.StateManager;

public class StateIds {	
	
	private final StateManager manager;
	public static StateId LATEST_ACTIVITY_START = null;
	public static StateId DEPARTURE_AT_DEPOT = null;
	public static StateId LATEST_ARR_AT_DEPOT = null;
	public static StateId ARRIVAL_AT_DEPOT = null;

	public  StateIds(StateManager manager) {		
		this.manager = manager;
	}	
	
	public void createStateIds() {
		LATEST_ACTIVITY_START = manager.createStateId("latest_start");
		DEPARTURE_AT_DEPOT = manager.createStateId("departure");		
		LATEST_ARR_AT_DEPOT = manager.createStateId("latestArrTimeAtDepot");
		ARRIVAL_AT_DEPOT = manager.createStateId("arrTimeAtDepot");
    }
//	public static final StateId LATEST_ACTIVITY_START = StateFactory.createId("latest_start", 10); 
	
	


}