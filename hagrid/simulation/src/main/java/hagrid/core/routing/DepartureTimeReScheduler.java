package hagrid.core.routing;

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
import java.util.Objects;

import com.graphhopper.jsprit.core.algorithm.listener.AlgorithmEndsListener;
import com.graphhopper.jsprit.core.algorithm.state.StateManager;
import com.graphhopper.jsprit.core.algorithm.state.UpdateActivityTimes;
import com.graphhopper.jsprit.core.problem.VehicleRoutingProblem;
import com.graphhopper.jsprit.core.problem.cost.TransportTime;
import com.graphhopper.jsprit.core.problem.cost.VehicleRoutingActivityCosts;
import com.graphhopper.jsprit.core.problem.solution.SolutionCostCalculator;
import com.graphhopper.jsprit.core.problem.solution.VehicleRoutingProblemSolution;
import com.graphhopper.jsprit.core.problem.solution.route.RouteActivityVisitor;
import com.graphhopper.jsprit.core.problem.solution.route.VehicleRoute;
import com.graphhopper.jsprit.core.problem.solution.route.activity.TourActivity;
import com.graphhopper.jsprit.core.util.Solutions;

/** Moves departures only when the resulting schedule is feasible and does not worsen the search objective. */
public class DepartureTimeReScheduler implements AlgorithmEndsListener {
    private static final double EPSILON = 1e-6;
    private final StateManager states;
    private final SolutionCostCalculator objective;
    private final double maxRouteDuration;
    private final double maxDriverDuration;

    /**
     * Compatibility for callers of updateActivityTimes. Without the search objective,
     * algorithm-end rescheduling is disabled: its effect on solution costs cannot be verified.
     */
    @Deprecated
    public DepartureTimeReScheduler() {
        states = null;
        objective = null;
        maxRouteDuration = Double.MAX_VALUE;
        maxDriverDuration = Double.MAX_VALUE;
    }

    public DepartureTimeReScheduler(StateManager states, SolutionCostCalculator objective,
                                    double maxRouteDuration, double maxDriverDuration) {
        this.states = Objects.requireNonNull(states);
        this.objective = Objects.requireNonNull(objective);
        if (!Double.isFinite(maxRouteDuration) || maxRouteDuration <= 0
                || !Double.isFinite(maxDriverDuration) || maxDriverDuration <= 0) {
            throw new IllegalArgumentException("Departure scheduling requires finite positive duration limits");
        }
        this.maxRouteDuration = maxRouteDuration;
        this.maxDriverDuration = maxDriverDuration;
    }

    @Override
    public void informAlgorithmEnds(VehicleRoutingProblem problem, Collection<VehicleRoutingProblemSolution> solutions) {
        if (objective == null || solutions.isEmpty()) return;
        if (states.getVrp() != problem) {
            throw new IllegalArgumentException("Departure scheduling states belong to another problem");
        }
        VehicleRoutingProblemSolution solution = Solutions.bestOf(solutions);
        double currentCost = objective.getCosts(solution);
        if (!Double.isFinite(currentCost)) throw new IllegalStateException("Non-finite solution cost before departure scheduling");

        for (VehicleRoute route : solution.getRoutes()) {
            if (route.isEmpty()) continue;
            double oldDeparture = route.getDepartureTime();
            TourActivity first = route.getActivities().getFirst();
            double travelTime = problem.getTransportCosts().getTransportTime(
                    route.getStart().getLocation(), first.getLocation(), oldDeparture, route.getDriver(), route.getVehicle());
            double candidate = Math.max(oldDeparture, first.getTheoreticalEarliestOperationStartTime() - travelTime);
            if (!Double.isFinite(travelTime) || travelTime < 0 || !Double.isFinite(candidate)
                    || candidate <= oldDeparture + EPSILON) continue;

            VehicleRoute original = VehicleRoute.copyOf(route);
            boolean accepted = false;
            try {
                route.setVehicleAndDepartureTime(route.getVehicle(), candidate);
                updateActivityTimes(route, problem.getTransportCosts(), problem.getActivityCosts());
                var modal=ExactTemporalSolverCosts.rawAdapter(problem.getTransportCosts());
                if (hasFeasibleTimes(route) && (modal==null || ExactTemporalTourFeasibility.route(modal,route,candidate,
                                Math.min(maxRouteDuration,maxDriverDuration)))) {
                    states.reCalculateStates(route);
                    double candidateCost = objective.getCosts(solution);
                    if (Double.isFinite(candidateCost) && candidateCost <= currentCost) {
                        currentCost = candidateCost;
                        accepted = true;
                    }
                }
            } catch(TravelTimeSnapshot.RangeException|NoRouteException infeasibleProposal) {
                // A speculative shift can leave the captured domain or lose its road path. Restore
                // the already validated original below; unrelated failures still propagate.
            } finally {
                if (!accepted) {
                    restoreTimes(route, original);
                    states.reCalculateStates(route);
                }
            }
        }
        solution.setCost(currentCost);
    }

    private boolean hasFeasibleTimes(VehicleRoute route) {
        double departure = route.getDepartureTime();
        if (!Double.isFinite(departure) || departure + EPSILON < route.getVehicle().getEarliestDeparture()) return false;
        double previousEnd = departure;
        for (TourActivity activity : route.getActivities()) {
            double arrival = activity.getArrTime();
            double start = Math.max(arrival, activity.getTheoreticalEarliestOperationStartTime());
            double end = activity.getEndTime();
            if (!Double.isFinite(arrival) || !Double.isFinite(start) || !Double.isFinite(end)
                    || arrival + EPSILON < previousEnd || end + EPSILON < start
                    || start > activity.getTheoreticalLatestOperationStartTime() + EPSILON) return false;
            previousEnd = end;
        }
        double arrival = route.getEnd().getArrTime();
        double duration = arrival - departure;
        return Double.isFinite(arrival) && arrival + EPSILON >= previousEnd
                && arrival <= route.getVehicle().getLatestArrival() + EPSILON
                && duration <= maxRouteDuration + EPSILON && duration <= maxDriverDuration + EPSILON;
    }

    private static void restoreTimes(VehicleRoute route, VehicleRoute original) {
        route.setVehicleAndDepartureTime(route.getVehicle(), original.getDepartureTime());
        route.getStart().setArrTime(original.getStart().getArrTime());
        route.getStart().setEndTime(original.getStart().getEndTime());
        for (int i = 0; i < route.getActivities().size(); i++) {
            route.getActivities().get(i).setArrTime(original.getActivities().get(i).getArrTime());
            route.getActivities().get(i).setEndTime(original.getActivities().get(i).getEndTime());
        }
        route.getEnd().setArrTime(original.getEnd().getArrTime());
        route.getEnd().setEndTime(original.getEnd().getEndTime());
    }

    public void updateActivityTimes(VehicleRoute route, TransportTime transportTimes, VehicleRoutingActivityCosts activityCosts) {
        RouteActivityVisitor visitor = new RouteActivityVisitor();
        visitor.addActivityVisitor(new UpdateActivityTimes(transportTimes, activityCosts));
        visitor.visit(route);
    }
}
