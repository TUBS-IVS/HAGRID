/*
 * Cost-breakdown methods adapted from GraphHopper jsprit 2.0.0 ConstraintManager.
 * Copyright GraphHopper GmbH and contributors. Licensed under the Apache License, Version 2.0.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 * See the License for the specific language governing permissions and limitations.
 */
package hagrid.core.routing;

import com.graphhopper.jsprit.core.algorithm.recreate.InsertionCostBreakdown;
import com.graphhopper.jsprit.core.algorithm.state.StateManager;
import com.graphhopper.jsprit.core.problem.VehicleRoutingProblem;
import com.graphhopper.jsprit.core.problem.constraint.ConstraintManager;
import com.graphhopper.jsprit.core.problem.constraint.SoftActivityConstraint;
import com.graphhopper.jsprit.core.problem.constraint.SoftRouteConstraint;
import com.graphhopper.jsprit.core.problem.misc.JobInsertionContext;
import com.graphhopper.jsprit.core.problem.solution.route.activity.TourActivity;

import java.util.Collection;

/**
 * Keeps native constraints and complete nonzero diagnostics, avoiding empty per-position maps/streams.
 * Native insertion calculators only read activity breakdowns; route breakdowns remain independently
 * mutable because native calculators append access/egress costs. No insertion/search rules change.
 */
final class HagridConstraintManager extends ConstraintManager {
    private static final InsertionCostBreakdown EMPTY_ACTIVITY = new ImmutableEmptyBreakdown();
    private final Collection<SoftActivityConstraint> activityConstraints;
    private final Collection<SoftRouteConstraint> routeConstraints;

    HagridConstraintManager(VehicleRoutingProblem problem, StateManager states) {
        super(problem, states);
        // These native unmodifiable views remain live when the builder registers more constraints.
        activityConstraints = super.getSoftActivityConstraints();
        routeConstraints = super.getSoftRouteConstraints();
    }

    @Override
    public InsertionCostBreakdown getActivityCostsBreakdown(JobInsertionContext context,
            TourActivity previous, TourActivity inserted, TourActivity next, double departure) {
        InsertionCostBreakdown breakdown = null;
        for (SoftActivityConstraint constraint : activityConstraints) {
            double cost = constraint.getCosts(context, previous, inserted, next, departure);
            if (cost != 0) {
                if (breakdown == null) breakdown = new InsertionCostBreakdown();
                breakdown.add("Activity:" + constraint.getClass().getSimpleName(), cost);
            }
        }
        return breakdown == null ? EMPTY_ACTIVITY : breakdown;
    }

    @Override
    public InsertionCostBreakdown getRouteCostsBreakdown(JobInsertionContext context) {
        var breakdown = new InsertionCostBreakdown();
        for (SoftRouteConstraint constraint : routeConstraints) {
            double cost = constraint.getCosts(context);
            if (cost != 0) breakdown.add("Route:" + constraint.getClass().getSimpleName(), cost);
        }
        return breakdown;
    }

    private static final class ImmutableEmptyBreakdown extends InsertionCostBreakdown {
        @Override public double getTotal() { return 0; }
        @Override public void add(String name, double cost) {
            throw new UnsupportedOperationException("Shared empty activity diagnostics are immutable");
        }
        @Override public void merge(InsertionCostBreakdown other) {
            throw new UnsupportedOperationException("Shared empty activity diagnostics are immutable");
        }
    }
}
