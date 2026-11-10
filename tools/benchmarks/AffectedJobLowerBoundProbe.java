package com.graphhopper.jsprit.core.algorithm.recreate;

import com.graphhopper.jsprit.core.problem.Location;
import com.graphhopper.jsprit.core.problem.VehicleRoutingProblem;
import com.graphhopper.jsprit.core.problem.job.Service;
import com.graphhopper.jsprit.core.problem.solution.route.VehicleRoute;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleImpl;

/** Minimal counterexample for jsprit 2.0's affected-job pruning; uses its real queue and tracker. */
public final class AffectedJobLowerBoundProbe {
    public static void main(String[] args) {
        var left = Service.Builder.newInstance("left").setLocation(Location.newInstance(0,0)).build();
        var right = Service.Builder.newInstance("right").setLocation(Location.newInstance(100,0)).build();
        var job = Service.Builder.newInstance("middle").setLocation(Location.newInstance(50,0)).build();
        var v1 = VehicleImpl.Builder.newInstance("v1").setStartLocation(Location.newInstance(0,0)).build();
        var v2 = VehicleImpl.Builder.newInstance("v2").setStartLocation(Location.newInstance(0,0)).build();
        var v3 = VehicleImpl.Builder.newInstance("v3").setStartLocation(Location.newInstance(0,0)).setReturnToDepot(false).build();
        var vrp = VehicleRoutingProblem.Builder.newInstance().addJob(left).addJob(right).addJob(job)
                .addVehicle(v1).addVehicle(v2).addVehicle(v3).build();
        var first = VehicleRoute.Builder.newInstance(v1).build();
        var second = VehicleRoute.Builder.newInstance(v2).build();
        var candidate = VehicleRoute.Builder.newInstance(v3).addService(left).addService(right).build();
        var tracker = new AffectedJobTracker(vrp);
        var queue = new BoundedInsertionQueue();
        queue.addOrReplace(new InsertionData(5,0,0,null,null),first);
        queue.addOrReplace(new InsertionData(10,0,0,null,null),second);
        tracker.updateJobTracking(job,queue);
        double actualMarginalDistance = 50 + 50 - 100;
        boolean allowed = tracker.couldBeCompetitive(job,candidate);
        System.out.printf("actual_marginal_distance=%.1f; second_best_cost=10.0; pruning_allows_candidate=%s%n",actualMarginalDistance,allowed);
        if (actualMarginalDistance >= 10 || allowed) throw new IllegalStateException("Counterexample not reproduced");
    }
}
