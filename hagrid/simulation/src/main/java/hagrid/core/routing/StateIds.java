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
package hagrid.core.routing;

import com.graphhopper.jsprit.core.algorithm.state.StateId;
import com.graphhopper.jsprit.core.algorithm.state.StateManager;

/** Custom state identifiers owned by exactly one solver's StateManager. */
public final class StateIds {
    private final StateManager manager;
    public final StateId latestActivityStart;
    public final StateId departureAtDepot;
    public final StateId latestArrivalAtDepot;
    public final StateId arrivalAtDepot;

    public StateIds(StateManager manager) {
        this.manager = java.util.Objects.requireNonNull(manager);
        latestActivityStart = manager.createStateId("latest_start");
        departureAtDepot = manager.createStateId("departure");
        latestArrivalAtDepot = manager.createStateId("latestArrTimeAtDepot");
        arrivalAtDepot = manager.createStateId("arrTimeAtDepot");
    }

    static StateIds forStates(com.graphhopper.jsprit.core.problem.solution.route.state.RouteAndActivityStateGetter states) {
        if (states instanceof StateManager manager) return new StateIds(manager);
        throw new IllegalArgumentException("Custom solver state requires its owning StateManager");
    }

    void requireOwner(com.graphhopper.jsprit.core.problem.solution.route.state.RouteAndActivityStateGetter states) {
        if (states != manager) throw new IllegalArgumentException("StateIds belong to another StateManager");
    }
}