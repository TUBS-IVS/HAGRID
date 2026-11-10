package com.graphhopper.jsprit.core.algorithm.recreate;

import com.graphhopper.jsprit.core.algorithm.state.StateManager;
import com.graphhopper.jsprit.core.problem.*;
import com.graphhopper.jsprit.core.problem.constraint.*;
import com.graphhopper.jsprit.core.problem.driver.DriverImpl;
import com.graphhopper.jsprit.core.problem.job.Service;
import com.graphhopper.jsprit.core.problem.misc.JobInsertionContext;
import com.graphhopper.jsprit.core.problem.solution.route.VehicleRoute;
import com.graphhopper.jsprit.core.problem.solution.route.activity.TourActivity;
import com.graphhopper.jsprit.core.problem.vehicle.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HagridServiceInsertionCalculatorTest {
    static final class CountingConstraints extends ConstraintManager {
        int breakdowns;
        CountingConstraints(VehicleRoutingProblem p) { super(p, new StateManager(p)); }
        public InsertionCostBreakdown getRouteCostsBreakdown(JobInsertionContext c) {
            breakdowns++; return super.getRouteCostsBreakdown(c);
        }
        public InsertionCostBreakdown getActivityCostsBreakdown(JobInsertionContext c, TourActivity a, TourActivity b, TourActivity d, double t) {
            breakdowns++; return super.getActivityCostsBreakdown(c,a,b,d,t);
        }
    }
    static final class Fixture {
        final Vehicle vehicle = VehicleImpl.Builder.newInstance("van").setStartLocation(Location.newInstance(0,0))
                .setType(VehicleTypeImpl.Builder.newInstance("type").addCapacityDimension(0,10).build()).build();
        final Service old = Service.Builder.newInstance("old").setLocation(Location.newInstance(1,0)).build();
        final Service added = Service.Builder.newInstance("added").setLocation(Location.newInstance(2,0)).build();
        final VehicleRoutingProblem problem = VehicleRoutingProblem.Builder.newInstance().addVehicle(vehicle).addJob(old).addJob(added).build();
        final CountingConstraints constraints = new CountingConstraints(problem);
        final VehicleRoute route = VehicleRoute.Builder.newInstance(vehicle).addService(old).build();
        JobInsertionCostsCalculator calculator(boolean diagnostics) {
            return HagridServiceInsertionCalculator.factory(diagnostics).create(problem, (c,a,b,d,t)->7,
                    job -> problem.getActivities(job), constraints);
        }
        InsertionData best(boolean diagnostics) {
            return calculator(diagnostics).getInsertionData(route,added,vehicle,0,DriverImpl.noDriver(),Double.MAX_VALUE);
        }
    }
    @Test void fastModeAvoidsUnusedBreakdownsForBestAndAllPositions() {
        var f = new Fixture();
        var best = f.best(false);
        assertEquals(7,best.getInsertionCost());
        assertNull(best.getCostBreakdown());
        var all = f.calculator(false).getAllInsertionPositions(f.route,f.added,f.vehicle,0,DriverImpl.noDriver());
        assertEquals(2,all.size());
        assertTrue(all.stream().allMatch(x->x.getCostBreakdown()==null));
        assertEquals(0,f.constraints.breakdowns,"Unused diagnostics must not allocate breakdowns");
    }
    @Test void nativeDiagnosticsRemainAvailable() {
        var f = new Fixture(); var result=f.best(true);
        assertNotNull(result.getCostBreakdown());
        assertEquals(7,result.getCostBreakdown().getTotal());
        assertTrue(f.constraints.breakdowns>0);
    }
    @Test void softCostsAreStillCharged() {
        var f=new Fixture();
        f.constraints.addConstraint((SoftRouteConstraint)c->2);
        f.constraints.addConstraint((SoftActivityConstraint)(c,a,b,d,t)->3);
        var fast=f.best(false); var nativeResult=f.best(true);
        assertEquals(12,fast.getInsertionCost());
        assertEquals(nativeResult.getInsertionCost(),fast.getInsertionCost());
        assertEquals(nativeResult.getDeliveryInsertionIndex(),fast.getDeliveryInsertionIndex());
    }
    @Test void multipleSoftCostsPreserveNativeAggregationExactly() {
        var f=new Fixture();
        for(double value:new double[]{1e16,1,-1e16,2})
            f.constraints.addConstraint((SoftActivityConstraint)(c,a,b,d,t)->value);
        assertEquals(Double.doubleToLongBits(f.best(true).getInsertionCost()),Double.doubleToLongBits(f.best(false).getInsertionCost()));
    }
    @Test void hardRouteRejectionIsPreserved() {
        var f=new Fixture(); f.constraints.addConstraint((HardRouteConstraint)c->false);
        assertFalse(f.best(false).isFound());
        assertTrue(f.calculator(false).getAllInsertionPositions(f.route,f.added,f.vehicle,0,DriverImpl.noDriver()).isEmpty());
    }
    @Test void rejectedGapIsNotReturnedAsBestOrAsAnAlternative() {
        var f=new Fixture();
        f.constraints.addConstraint((HardActivityConstraint)(c,a,b,d,t)->c.getActivityContext().getInsertionIndex()==0
                ? HardActivityConstraint.ConstraintsStatus.NOT_FULFILLED : HardActivityConstraint.ConstraintsStatus.FULFILLED,ConstraintManager.Priority.HIGH);
        assertEquals(1,f.best(false).getDeliveryInsertionIndex());
        var all=f.calculator(false).getAllInsertionPositions(f.route,f.added,f.vehicle,0,DriverImpl.noDriver());
        assertEquals(1,all.size()); assertEquals(1,all.getFirst().getDeliveryInsertionIndex());
    }
    @Test void changedVehicleAccessAndEgressStillMatchNative() {
        var f=new Fixture();
        var other=VehicleImpl.Builder.newInstance("other").setStartLocation(Location.newInstance(10,0)).setType(f.vehicle.getType()).build();
        var fast=f.calculator(false).getInsertionData(f.route,f.added,other,0,DriverImpl.noDriver(),Double.MAX_VALUE);
        var nativeResult=f.calculator(true).getInsertionData(f.route,f.added,other,0,DriverImpl.noDriver(),Double.MAX_VALUE);
        assertEquals(23,fast.getInsertionCost());
        assertEquals(nativeResult.getInsertionCost(),fast.getInsertionCost());
    }
}
