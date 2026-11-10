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

import com.graphhopper.jsprit.core.problem.VehicleRoutingProblem;
import com.graphhopper.jsprit.core.problem.constraint.DependencyType;
import com.graphhopper.jsprit.core.problem.job.Job;
import com.graphhopper.jsprit.core.problem.solution.route.VehicleRoute;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleFleetManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Insertion based on regret approach with affected-job tracking optimization.
 * <p>
 * <p>Basically calculates the insertion cost of the firstBest and the secondBest alternative. The score is then calculated as difference
 * between secondBest and firstBest, plus additional scoring variables that can defined in this.ScoringFunction.
 * The idea is that if the cost of the secondBest alternative is way higher than the first best, it seems to be important to insert this
 * customer immediatedly. If difference is not that high, it might not impact solution if this customer is inserted later.
 * <p>
 * <p><b>HAGRID correctness backport for jsprit 2.0.0:</b> Modified routes must
 * refresh every cached alternative, including alternatives outside the top two.
 * Uncached neighboring routes are evaluated without a geometric cost bound.
 * Unmodified routes retain their cached insertion data. An explicitly configured
 * spatial filter remains an approximation for uncached, non-neighbor routes.
 * See docs/superpowers/plans/2026-10-20-fast-regret-kapazitaetsfix.md for provenance.
 *
 * @author stefan schroeder
 */
public class RegretInsertionFast extends AbstractInsertionStrategy {

    private static final Logger logger = LoggerFactory.getLogger(RegretInsertionFast.class);

    private RegretScoringFunction regretScoringFunction;

    private RegretKScoringFunction regretKScoringFunction;

    private int regretK = 2;

    private final JobInsertionCostsCalculator insertionCostsCalculator;

    private final VehicleFleetManager fleetManager;

    private final Set<String> initialVehicleIds;

    private boolean switchAllowed = true;

    private DependencyType[] dependencyTypes = null;

    private AdaptiveSpatialFilter spatialFilter = null;

    private boolean affectedJobTrackingEnabled = true;

    public RegretInsertionFast(JobInsertionCostsCalculator jobInsertionCalculator, VehicleRoutingProblem vehicleRoutingProblem, VehicleFleetManager fleetManager) {
        super(vehicleRoutingProblem);
        this.regretScoringFunction = new DefaultRegretScoringFunction(new DefaultScorer(vehicleRoutingProblem));
        this.insertionCostsCalculator = jobInsertionCalculator;
        this.fleetManager = fleetManager;
        this.initialVehicleIds = getInitialVehicleIds(vehicleRoutingProblem);
        logger.debug("initialise {}", this);
    }

    /**
     * Sets the scoring function.
     * <p>
     * <p>By default, the this.TimeWindowScorer is used.
     *
     * @param scoringFunction to score
     */
    public void setScoringFunction(ScoringFunction scoringFunction) {
        this.regretScoringFunction = new DefaultRegretScoringFunction(scoringFunction);
    }

    public void setRegretScoringFunction(RegretScoringFunction regretScoringFunction) {
        this.regretScoringFunction = regretScoringFunction;
    }

    public void setRegretKScoringFunction(RegretKScoringFunction regretKScoringFunction) {
        this.regretKScoringFunction = regretKScoringFunction;
    }

    public void setRegretK(int k) {
        this.regretK = k;
    }

    public void setSwitchAllowed(boolean switchAllowed) {
        this.switchAllowed = switchAllowed;
    }

    public void setDependencyTypes(DependencyType[] dependencyTypes) {
        this.dependencyTypes = dependencyTypes;
    }

    public void setSpatialFilter(AdaptiveSpatialFilter spatialFilter) {
        this.spatialFilter = spatialFilter;
    }

    public AdaptiveSpatialFilter getSpatialFilter() {
        return spatialFilter;
    }

    /**
     * Enables or disables affected-job tracking optimization.
     * When enabled, cached alternatives and neighboring jobs are refreshed after a route modification.
     * Default is enabled.
     *
     * @param enabled true to enable (default), false to disable
     */
    public void setAffectedJobTrackingEnabled(boolean enabled) {
        this.affectedJobTrackingEnabled = enabled;
    }

    public boolean isAffectedJobTrackingEnabled() {
        return affectedJobTrackingEnabled;
    }

    private Set<String> getInitialVehicleIds(VehicleRoutingProblem vehicleRoutingProblem) {
        Set<String> ids = new HashSet<>();
        for(VehicleRoute r : vehicleRoutingProblem.getInitialVehicleRoutes()){
            ids.add(r.getVehicle().getId());
        }
        return ids;
    }

    @Override
    public String toString() {
        return "[name=regretInsertionFast][regretK=" + regretK + "][affectedJobTracking=" + affectedJobTrackingEnabled + "]";
    }


    /**
     * Runs insertion.
     * <p>
     * <p>Before inserting a job, all unassigned jobs are scored according to its best- and secondBest-insertion plus additional scoring variables.
     */
    @Override
    public Collection<Job> insertUnassignedJobs(Collection<VehicleRoute> routes, Collection<Job> unassignedJobs) {
        List<Job> badJobs = new ArrayList<>(unassignedJobs.size());
        Set<Job> jobs = new LinkedHashSet<>(unassignedJobs);

        // Use BoundedInsertionQueue instead of TreeSet - O(R) per job instead of O(n*R)
        BoundedInsertionQueue[] queues = new BoundedInsertionQueue[vrp.getJobs().values().size() + 2];

        // Affected-job tracker for incremental updates
        AffectedJobTracker tracker = affectedJobTrackingEnabled ? new AffectedJobTracker(vrp) : null;

        VehicleRoute lastModified = null;
        boolean firstRun = true;

        while (!jobs.isEmpty()) {
            List<ScoredJob> badJobList = new ArrayList<>();

            if (!firstRun && lastModified == null) {
                throw new IllegalStateException("last modified route is null. this should not be.");
            }

            if (firstRun) {
                updateInsertionDataFirstRun(queues, routes, jobs, tracker);
                firstRun = false;
            } else {
                updateInsertionDataIncremental(queues, routes, jobs, lastModified, tracker);
            }

            ScoredJob bestScoredJob;
            if (regretKScoringFunction != null && regretK != 2) {
                bestScoredJob = InsertionDataUpdater.getBestWithKBounded(switchAllowed, initialVehicleIds, fleetManager, insertionCostsCalculator, regretKScoringFunction, regretK, queues, jobs, badJobList, vrp);
            } else {
                bestScoredJob = InsertionDataUpdater.getBestBounded(switchAllowed, initialVehicleIds, fleetManager, insertionCostsCalculator, regretScoringFunction, queues, jobs, badJobList, vrp);
            }

            if (bestScoredJob != null) {
                boolean isNewRoute = bestScoredJob.isNewRoute();
                if (isNewRoute) {
                    routes.add(bestScoredJob.getRoute());
                }
                insertJob(bestScoredJob.getJob(), bestScoredJob.getInsertionData(), bestScoredJob.getRoute());
                jobs.remove(bestScoredJob.getJob());
                lastModified = bestScoredJob.getRoute();

                // Update tracker: remove inserted job, invalidate route centroid
                if (tracker != null) {
                    tracker.removeJob(bestScoredJob.getJob());
                    tracker.invalidateRouteCentroid(lastModified);

                    // When a new route is added, register it in all remaining jobs' neighborhoods
                    if (isNewRoute) {
                        for (Job remainingJob : jobs) {
                            tracker.registerSpatialNeighborhood(remainingJob, Collections.singletonList(lastModified));
                        }
                    }
                }
            } else {
                lastModified = null;
            }

            for (ScoredJob bad : badJobList) {
                Job unassigned = bad.getJob();
                jobs.remove(unassigned);
                badJobs.add(unassigned);
                markUnassigned(unassigned, bad.getInsertionData().getFailedConstraintNames());
                if (tracker != null) {
                    tracker.removeJob(unassigned);
                }
            }
        }

        return badJobs;
    }

    /**
     * First run: calculate insertion data for all jobs against all routes.
     */
    private void updateInsertionDataFirstRun(BoundedInsertionQueue[] queues, Collection<VehicleRoute> routes,
                                              Collection<Job> unassignedJobs, AffectedJobTracker tracker) {
        for (Job unassignedJob : unassignedJobs) {
            int jobIndex = vrp.getJobIndex(unassignedJob);
            if (queues[jobIndex] == null) {
                queues[jobIndex] = new BoundedInsertionQueue();
            }

            // Calculate insertions for all routes with optional spatial filtering
            InsertionDataUpdater.updateBoundedWithFilter(switchAllowed, initialVehicleIds, fleetManager,
                insertionCostsCalculator, queues[jobIndex], unassignedJob, routes, spatialFilter);

            // Update tracker with initial state
            if (tracker != null) {
                tracker.updateJobTracking(unassignedJob, queues[jobIndex]);
                // Register spatial neighborhood if spatial filter is active
                if (spatialFilter != null && spatialFilter.isFilteringEnabled()) {
                    List<VehicleRoute> nearRoutes = spatialFilter.getNearestRoutes(unassignedJob, routes, spatialFilter.getK());
                    tracker.registerSpatialNeighborhood(unassignedJob, nearRoutes);
                } else {
                    // No spatial filtering - all routes are neighbors
                    tracker.registerSpatialNeighborhood(unassignedJob, routes);
                }
            }
        }
    }

    /**
     * Incremental update: only recalculate affected jobs after a route modification.
     */
    private void updateInsertionDataIncremental(BoundedInsertionQueue[] queues, Collection<VehicleRoute> routes,
                                                 Collection<Job> unassignedJobs, VehicleRoute lastModified,
                                                 AffectedJobTracker tracker) {
        if (tracker == null || !affectedJobTrackingEnabled) {
            // Fallback to original behavior
            updateInsertionDataLegacy(queues, routes, unassignedJobs, lastModified);
            return;
        }

        // Get jobs affected by the route modification (route was in their top-2)
        Set<Job> affectedJobs = tracker.getAffectedJobs(lastModified);

        // Get jobs that need cheap check (route in neighborhood but not top-2)
        Set<Job> cheapCheckJobs = tracker.getJobsNeedingCheapCheck(lastModified, affectedJobs);

        for (Job unassignedJob : unassignedJobs) {
            int jobIndex = vrp.getJobIndex(unassignedJob);

            // Check for inter-route dependencies
            boolean hasDependency = dependencyTypes != null && dependencyTypes[jobIndex] != null;
            if (hasDependency) {
                DependencyType dependencyType = dependencyTypes[jobIndex];
                if (dependencyType.equals(DependencyType.INTER_ROUTE) || dependencyType.equals(DependencyType.INTRA_ROUTE)) {
                    // Dependencies invalidate all route alternatives; a spatial filter must not preserve stale entries.
                    InsertionDataUpdater.updateBounded(switchAllowed, initialVehicleIds, fleetManager,
                        insertionCostsCalculator, queues[jobIndex], unassignedJob, routes);
                    tracker.updateJobTracking(unassignedJob, queues[jobIndex]);
                    continue;
                }
            }

            // Any cached insertion for the modified route is stale, even when it was
            // only the third (or later) alternative. Otherwise it can become best
            // later without its capacity/time-window constraints ever being checked.
            // For neighbors with no cached insertion, evaluate the route as well:
            // Euclidean distance is not an admissible bound on generic insertion
            // cost (different units, zero/negative marginal costs, custom costs).
            if (queues[jobIndex].getForRoute(lastModified) != null
                    || affectedJobs.contains(unassignedJob)
                    || cheapCheckJobs.contains(unassignedJob)) {
                InsertionDataUpdater.updateBounded(switchAllowed, initialVehicleIds, fleetManager,
                    insertionCostsCalculator, queues[jobIndex], unassignedJob, Collections.singletonList(lastModified));
                tracker.updateJobTracking(unassignedJob, queues[jobIndex]);
            }
            // An uncached route outside an explicitly configured spatial neighborhood remains filtered.
        }
    }

    /**
     * Legacy update method (original behavior without affected-job tracking).
     */
    private void updateInsertionDataLegacy(BoundedInsertionQueue[] queues, Collection<VehicleRoute> routes,
                                            Collection<Job> unassignedJobs, VehicleRoute lastModified) {
        for (Job unassignedJob : unassignedJobs) {
            int jobIndex = vrp.getJobIndex(unassignedJob);
            if (queues[jobIndex] == null) {
                queues[jobIndex] = new BoundedInsertionQueue();
            }

            if (dependencyTypes == null || dependencyTypes[jobIndex] == null) {
                // Only update the modified route
                InsertionDataUpdater.updateBounded(switchAllowed, initialVehicleIds, fleetManager,
                    insertionCostsCalculator, queues[jobIndex], unassignedJob, Collections.singletonList(lastModified));
            } else {
                DependencyType dependencyType = dependencyTypes[jobIndex];
                if (dependencyType.equals(DependencyType.INTER_ROUTE) || dependencyType.equals(DependencyType.INTRA_ROUTE)) {
                    // Dependencies invalidate all route alternatives; a spatial filter must not preserve stale entries.
                    InsertionDataUpdater.updateBounded(switchAllowed, initialVehicleIds, fleetManager,
                        insertionCostsCalculator, queues[jobIndex], unassignedJob, routes);
                } else {
                    // Only update the modified route
                    InsertionDataUpdater.updateBounded(switchAllowed, initialVehicleIds, fleetManager,
                        insertionCostsCalculator, queues[jobIndex], unassignedJob, Collections.singletonList(lastModified));
                }
            }
        }
    }
}
