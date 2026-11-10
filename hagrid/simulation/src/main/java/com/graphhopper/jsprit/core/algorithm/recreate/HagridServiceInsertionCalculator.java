/*
 * Licensed to GraphHopper GmbH under one or more contributor
 * license agreements. See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 *
 * GraphHopper GmbH licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.graphhopper.jsprit.core.algorithm.recreate;

import com.graphhopper.jsprit.core.problem.JobActivityFactory;
import com.graphhopper.jsprit.core.problem.constraint.ConstraintManager;
import com.graphhopper.jsprit.core.problem.constraint.HardActivityConstraint.ConstraintsStatus;
import com.graphhopper.jsprit.core.problem.constraint.HardConstraint;
import com.graphhopper.jsprit.core.problem.constraint.SoftActivityConstraint;
import com.graphhopper.jsprit.core.problem.constraint.SoftRouteConstraint;
import com.graphhopper.jsprit.core.problem.cost.VehicleRoutingActivityCosts;
import com.graphhopper.jsprit.core.problem.cost.VehicleRoutingTransportCosts;
import com.graphhopper.jsprit.core.problem.driver.Driver;
import com.graphhopper.jsprit.core.problem.job.Job;
import com.graphhopper.jsprit.core.problem.job.Service;
import com.graphhopper.jsprit.core.problem.misc.ActivityContext;
import com.graphhopper.jsprit.core.problem.misc.JobInsertionContext;
import com.graphhopper.jsprit.core.problem.solution.route.VehicleRoute;
import com.graphhopper.jsprit.core.problem.solution.route.activity.End;
import com.graphhopper.jsprit.core.problem.solution.route.activity.Start;
import com.graphhopper.jsprit.core.problem.solution.route.activity.TimeWindow;
import com.graphhopper.jsprit.core.problem.solution.route.activity.TourActivity;
import com.graphhopper.jsprit.core.problem.vehicle.Vehicle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

/**
 * Calculator that calculates the best insertion position for a {@link Service}.
 *
 * @author schroeder
 */
public final class HagridServiceInsertionCalculator extends AbstractInsertionCalculator {

    /**
     * Uses jsprit 2.0's public extension point. Diagnostics delegate to native jsprit.
     * This uniquely named class shares its package only for native insertion events;
     * it never shadows a class from the dependency. Derived from jsprit 2.0.0.
     */
    public static JobInsertionCostsCalculatorFactory factory(boolean diagnostics) {
        return factory(diagnostics, null);
    }

    /**
     * A caller-proven lower bound on the activity insertion calculator's contribution.
     * It excludes route-level soft costs and access/egress costs, which this calculator adds.
     * The bound must hold for every time window at the supplied gap and may be negative.
     * It is evaluated before hard activity constraints and before selecting a time window;
     * use the gap argument rather than the context's mutable activity context.
     *
     * <p>Implementations must be thread-safe and observational: evaluating the bound must not
     * mutate feasibility state or the cost model. No bound is inferred for arbitrary transport
     * costs. Return a nonfinite value when no bound is available; that retains full enumeration.
     */
    @FunctionalInterface
    public interface InsertionCostLowerBound {
        double lowerBound(JobInsertionContext context, int gap);
    }

    /**
     * Enables pruning only when a proven bound is supplied and no soft activity constraints
     * are present. Diagnostic mode always delegates to unfiltered native jsprit. Requests for
     * all insertion positions are never filtered by a best-position bound.
     */
    public static JobInsertionCostsCalculatorFactory factory(boolean diagnostics,
            InsertionCostLowerBound lowerBound) {
        return (problem, activity, jobs, constraints) -> diagnostics
                ? new ServiceInsertionCalculator(problem.getTransportCosts(), problem.getActivityCosts(), activity, constraints, jobs)
                : new HagridServiceInsertionCalculator(problem.getTransportCosts(), problem.getActivityCosts(), activity, constraints, jobs, lowerBound);
    }

    private double routeCost(JobInsertionContext context) {
        // With multiple constraints, preserve native grouping and compensated summation exactly.
        if (constraintManager.getSoftRouteConstraints().size() > 1)
            return constraintManager.getRouteCostsBreakdown(context).getTotal();
        double value = softRouteConstraint.getCosts(context);
        return value == 0.0 ? 0.0 : value;
    }

    private double activityCost(JobInsertionContext context, TourActivity previous, TourActivity inserted,
                                TourActivity next, double departure) {
        if (constraintManager.getSoftActivityConstraints().size() > 1)
            return constraintManager.getActivityCostsBreakdown(context, previous, inserted, next, departure).getTotal();
        double value = softActivityConstraint.getCosts(context, previous, inserted, next, departure);
        return value == 0.0 ? 0.0 : value;
    }

    private static final Logger logger = LoggerFactory.getLogger(HagridServiceInsertionCalculator.class);

    private final SoftRouteConstraint softRouteConstraint;

    private final SoftActivityConstraint softActivityConstraint;

    private final VehicleRoutingTransportCosts transportCosts;

    private final VehicleRoutingActivityCosts activityCosts;

    private final ActivityInsertionCostsCalculator activityInsertionCostsCalculator;

    private final JobActivityFactory activityFactory;

    private final AdditionalAccessEgressCalculator additionalAccessEgressCalculator;

    private final ConstraintManager constraintManager;

    private final InsertionCostLowerBound lowerBound;

    private HagridServiceInsertionCalculator(VehicleRoutingTransportCosts routingCosts, VehicleRoutingActivityCosts activityCosts, ActivityInsertionCostsCalculator activityInsertionCostsCalculator, ConstraintManager constraintManager, JobActivityFactory activityFactory, InsertionCostLowerBound lowerBound) {
        super();
        this.lowerBound = lowerBound;
        this.transportCosts = routingCosts;
        this.activityCosts = activityCosts;
        this.constraintManager = constraintManager;
        softActivityConstraint = constraintManager;
        softRouteConstraint = constraintManager;
        this.activityInsertionCostsCalculator = activityInsertionCostsCalculator;
        additionalAccessEgressCalculator = new AdditionalAccessEgressCalculator(routingCosts);
        this.activityFactory = activityFactory;
        logger.debug("initialise {}", this);
    }

    @Override
    public String toString() {
        return "[name=calculatesServiceInsertion]";
    }

    /**
     * Calculates the marginal cost of inserting job i locally. This is based on the
     * assumption that cost changes can entirely covered by only looking at the predecessor i-1 and its successor i+1.
     */
    @Override
    public InsertionData getInsertionData(final VehicleRoute currentRoute, final Job jobToInsert, final Vehicle newVehicle, double newVehicleDepartureTime, final Driver newDriver, final double bestKnownCosts) {
        JobInsertionContext insertionContext = new JobInsertionContext(currentRoute, jobToInsert, newVehicle, newDriver, newVehicleDepartureTime);
        Service service = (Service) jobToInsert;
        int insertionIndex = InsertionData.NO_INDEX;

        TourActivity deliveryAct2Insert = activityFactory.createActivities(service).get(0);
        insertionContext.getAssociatedActivities().add(deliveryAct2Insert);

        /*
        check hard constraints at route level
         */
        InsertionData noInsertion = checkRouteConstraints(insertionContext, constraintManager);
        if (noInsertion != null) return noInsertion;

        Collection<HardConstraint> failedActivityConstraints = new ArrayList<>();

        /*
        check soft constraints at route level
         */
        double additionalICostsAtRouteLevel = routeCost(insertionContext);
        additionalICostsAtRouteLevel += additionalAccessEgressCalculator.getCosts(insertionContext);

        double bestCost = bestKnownCosts;
		TimeWindow bestTimeWindow = null;

        /*
        generate new start and end for new vehicle
         */
        Start start = new Start(newVehicle.getStartLocation(), newVehicle.getEarliestDeparture(), Double.MAX_VALUE);
        start.setEndTime(newVehicleDepartureTime);
        End end = new End(newVehicle.getEndLocation(), 0.0, newVehicle.getLatestArrival());

        TourActivity prevAct = start;
        double prevActStartTime = newVehicleDepartureTime;
        int actIndex = 0;
        Iterator<TourActivity> activityIterator = currentRoute.getActivities().iterator();
        boolean tourEnd = false;
        while(!tourEnd){
            TourActivity nextAct;
            if(activityIterator.hasNext()) nextAct = activityIterator.next();
            else{
                nextAct = end;
                tourEnd = true;
            }
            // Advancing time below remains mandatory even when this position cannot win.
            boolean rejectedByBound = false;
            if (lowerBound != null && constraintManager.getSoftActivityConstraints().isEmpty()) {
                double candidateBound = additionalICostsAtRouteLevel + lowerBound.lowerBound(insertionContext, actIndex);
                rejectedByBound = Double.isFinite(candidateBound) && candidateBound > bestCost + 1e-9;
            }
            boolean not_fulfilled_break = !rejectedByBound;
			if (!rejectedByBound) for(TimeWindow timeWindow : service.getTimeWindows()) {
                deliveryAct2Insert.setTheoreticalEarliestOperationStartTime(timeWindow.getStart());
                deliveryAct2Insert.setTheoreticalLatestOperationStartTime(timeWindow.getEnd());
                ActivityContext activityContext = new ActivityContext();
                activityContext.setInsertionIndex(actIndex);
                insertionContext.setActivityContext(activityContext);
                ConstraintsStatus status = fulfilled(insertionContext, prevAct, deliveryAct2Insert, nextAct, prevActStartTime, failedActivityConstraints, constraintManager);
                if (status.equals(ConstraintsStatus.FULFILLED)) {
                    double additionalICostsAtActLevel = activityCost(
                            insertionContext, prevAct, deliveryAct2Insert, nextAct, prevActStartTime);
                    double additionalTransportationCosts = activityInsertionCostsCalculator.getCosts(insertionContext, prevAct, nextAct, deliveryAct2Insert, prevActStartTime);
                    double totalCost = additionalICostsAtRouteLevel + additionalICostsAtActLevel + additionalTransportationCosts;
                    if (totalCost < bestCost) {
                        bestCost = totalCost;
                        insertionIndex = actIndex;
                        bestTimeWindow = timeWindow;
                    }
                    not_fulfilled_break = false;
                } else if (status.equals(ConstraintsStatus.NOT_FULFILLED)) {
                    not_fulfilled_break = false;
                }
			}
            if(not_fulfilled_break) break;
            double nextActArrTime = prevActStartTime + transportCosts.getTransportTime(prevAct.getLocation(), nextAct.getLocation(), prevActStartTime, newDriver, newVehicle);
            prevActStartTime = Math.max(nextActArrTime, nextAct.getTheoreticalEarliestOperationStartTime()) + activityCosts.getActivityDuration(prevAct, nextAct, nextActArrTime, newDriver, newVehicle);
            prevAct = nextAct;
            actIndex++;
        }
        if(insertionIndex == InsertionData.NO_INDEX) {
            InsertionData emptyInsertionData = new InsertionData.NoInsertionFound();
            for (HardConstraint c : failedActivityConstraints) {
                emptyInsertionData.addFailedConstrainName(c.getClass().getSimpleName());
            }
            return emptyInsertionData;
        }
        InsertionData insertionData = new InsertionData(bestCost, InsertionData.NO_INDEX, insertionIndex, newVehicle, newDriver);
        deliveryAct2Insert.setTheoreticalEarliestOperationStartTime(bestTimeWindow.getStart());
        deliveryAct2Insert.setTheoreticalLatestOperationStartTime(bestTimeWindow.getEnd());
        insertionData.getEvents().add(new InsertActivity(currentRoute, newVehicle, deliveryAct2Insert, insertionIndex));
        insertionData.getEvents().add(new SwitchVehicle(currentRoute,newVehicle,newVehicleDepartureTime));
        insertionData.setVehicleDepartureTime(newVehicleDepartureTime);
        return insertionData;
    }

    /**
     * Returns all feasible insertion positions for a service in the route.
     * Used for position-based regret calculation.
     */
    @Override
    public List<InsertionData> getAllInsertionPositions(final VehicleRoute currentRoute, final Job jobToInsert,
            final Vehicle newVehicle, double newVehicleDepartureTime, final Driver newDriver) {

        List<InsertionData> allPositions = new ArrayList<>();

        JobInsertionContext insertionContext = new JobInsertionContext(currentRoute, jobToInsert, newVehicle, newDriver, newVehicleDepartureTime);
        Service service = (Service) jobToInsert;

        TourActivity deliveryAct2Insert = activityFactory.createActivities(service).get(0);
        insertionContext.getAssociatedActivities().add(deliveryAct2Insert);

        // Check hard constraints at route level
        InsertionData noInsertion = checkRouteConstraints(insertionContext, constraintManager);
        if (noInsertion != null) {
            return allPositions; // Empty list - route constraints not satisfied
        }

        Collection<HardConstraint> failedActivityConstraints = new ArrayList<>();

        // Calculate route-level costs (same for all positions)
        double additionalICostsAtRouteLevel = routeCost(insertionContext);
        additionalICostsAtRouteLevel += additionalAccessEgressCalculator.getCosts(insertionContext);

        // Generate start and end for new vehicle
        Start start = new Start(newVehicle.getStartLocation(), newVehicle.getEarliestDeparture(), Double.MAX_VALUE);
        start.setEndTime(newVehicleDepartureTime);
        End end = new End(newVehicle.getEndLocation(), 0.0, newVehicle.getLatestArrival());

        TourActivity prevAct = start;
        double prevActStartTime = newVehicleDepartureTime;
        int actIndex = 0;
        Iterator<TourActivity> activityIterator = currentRoute.getActivities().iterator();
        boolean tourEnd = false;

        while (!tourEnd) {
            TourActivity nextAct;
            if (activityIterator.hasNext()) {
                nextAct = activityIterator.next();
            } else {
                nextAct = end;
                tourEnd = true;
            }

            boolean notFulfilledBreak = true;
            for (TimeWindow timeWindow : service.getTimeWindows()) {
                deliveryAct2Insert.setTheoreticalEarliestOperationStartTime(timeWindow.getStart());
                deliveryAct2Insert.setTheoreticalLatestOperationStartTime(timeWindow.getEnd());
                ActivityContext activityContext = new ActivityContext();
                activityContext.setInsertionIndex(actIndex);
                insertionContext.setActivityContext(activityContext);

                ConstraintsStatus status = fulfilled(insertionContext, prevAct, deliveryAct2Insert, nextAct,
                        prevActStartTime, failedActivityConstraints, constraintManager);

                if (status.equals(ConstraintsStatus.FULFILLED)) {
                    // Calculate costs for this position
                    double additionalICostsAtActLevel = activityCost(
                            insertionContext, prevAct, deliveryAct2Insert, nextAct, prevActStartTime);
                    double additionalTransportationCosts = activityInsertionCostsCalculator.getCosts(
                            insertionContext, prevAct, nextAct, deliveryAct2Insert, prevActStartTime);
                    double totalCost = additionalICostsAtRouteLevel + additionalICostsAtActLevel + additionalTransportationCosts;

                    // Create InsertionData for this position
                    InsertionData insertionData = new InsertionData(totalCost, InsertionData.NO_INDEX, actIndex, newVehicle, newDriver);

                    // Create fresh activity for this position's events
                    TourActivity actForPosition = activityFactory.createActivities(service).get(0);
                    actForPosition.setTheoreticalEarliestOperationStartTime(timeWindow.getStart());
                    actForPosition.setTheoreticalLatestOperationStartTime(timeWindow.getEnd());
                    insertionData.getEvents().add(new InsertActivity(currentRoute, newVehicle, actForPosition, actIndex));
                    insertionData.getEvents().add(new SwitchVehicle(currentRoute, newVehicle, newVehicleDepartureTime));
                    insertionData.setVehicleDepartureTime(newVehicleDepartureTime);

                    allPositions.add(insertionData);
                    notFulfilledBreak = false;
                } else if (status.equals(ConstraintsStatus.NOT_FULFILLED)) {
                    notFulfilledBreak = false;
                }
            }
            if (notFulfilledBreak) break;

            double nextActArrTime = prevActStartTime + transportCosts.getTransportTime(
                    prevAct.getLocation(), nextAct.getLocation(), prevActStartTime, newDriver, newVehicle);
            prevActStartTime = Math.max(nextActArrTime, nextAct.getTheoreticalEarliestOperationStartTime())
                    + activityCosts.getActivityDuration(prevAct, nextAct, nextActArrTime, newDriver, newVehicle);
            prevAct = nextAct;
            actIndex++;
        }

        return allPositions;
    }

}
