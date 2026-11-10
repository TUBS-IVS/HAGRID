package hagrid.core.routing;

import com.graphhopper.jsprit.core.algorithm.state.StateManager;
import com.graphhopper.jsprit.core.problem.VehicleRoutingProblem;
import com.graphhopper.jsprit.core.problem.constraint.VehicleDependentTimeWindowConstraints;
import com.graphhopper.jsprit.core.problem.constraint.HardActivityConstraint.ConstraintsStatus;
import com.graphhopper.jsprit.core.problem.misc.JobInsertionContext;
import com.graphhopper.jsprit.core.problem.solution.route.VehicleRoute;
import com.graphhopper.jsprit.core.problem.solution.route.activity.ServiceActivity;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeTemporalGuardTest {
    @Test void nativeEarlyBreakRejectsAValidInsertionThatReachesALaterFastTrafficInterval() {
        try(var f=RawDistanceTemporalRoutingTest.fixture((l,t,p,v)->t>=100&&t<110?10:1000,10,10,10000)) {
            var roads=f.costs();var exact=roads.solverCosts();
            var stop=RawTemporalInsertionTest.service("stop","destination",0,120,0);
            var inserted=RawTemporalInsertionTest.service("inserted","origin",0,10000,100);
            var route=VehicleRoute.Builder.newInstance(f.vehicle()).addService(stop).build();
            var problem=VehicleRoutingProblem.Builder.newInstance().addVehicle(f.vehicle()).addJob(stop).addJob(inserted).setRoutingCost(exact).build();
            var states=new StateManager(problem);
            var facts=new JobInsertionContext(route,inserted,f.vehicle(),route.getDriver(),0);
            var activity=ServiceActivity.newInstance(inserted);
            var next=problem.copyAndGetActivities(stop).getFirst();
            route.getTourActivities().removeActivity(route.getActivities().getFirst());
            route.getTourActivities().addActivity(next);
            next.setTheoreticalEarliestOperationStartTime(0);next.setTheoreticalLatestOperationStartTime(120);
            // Direct departure at zero arrives1000>120. Serving the inserted job first reaches the
            // same road at100 and the existing stop at110<=120, with return before the horizon.
            assertEquals(1000,exact.getTransportTime(route.getStart().getLocation(),next.getLocation(),0,null,f.vehicle()));
            assertEquals(10,exact.getTransportTime(activity.getLocation(),next.getLocation(),100,null,f.vehicle()));
            assertEquals(ConstraintsStatus.NOT_FULFILLED_BREAK,
                    new VehicleDependentTimeWindowConstraints(states,exact,new HagridActivityCosts(5))
                            .fulfilled(facts,route.getStart(),activity,next,0));
            assertEquals(ConstraintsStatus.FULFILLED,
                    new TimeWindowConstraintWithDriverTime(states,exact,5000).fulfilled(facts,route.getStart(),activity,next,0));
            assertTrue(new ExactInsertionReplay(roads,new HagridActivityCosts(5),5000).evaluate(facts,activity,next,false).feasible());
        }
    }
}
