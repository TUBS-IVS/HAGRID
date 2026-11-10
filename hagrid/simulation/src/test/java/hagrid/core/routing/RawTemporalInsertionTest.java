package hagrid.core.routing;

import com.graphhopper.jsprit.core.algorithm.state.StateManager;
import com.graphhopper.jsprit.core.problem.VehicleRoutingProblem;
import com.graphhopper.jsprit.core.problem.Location;
import com.graphhopper.jsprit.core.problem.constraint.HardActivityConstraint.ConstraintsStatus;
import com.graphhopper.jsprit.core.problem.job.Service;
import com.graphhopper.jsprit.core.problem.misc.JobInsertionContext;
import com.graphhopper.jsprit.core.problem.solution.route.VehicleRoute;
import com.graphhopper.jsprit.core.problem.solution.route.activity.ServiceActivity;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RawTemporalInsertionTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"0,100","100,0"})
    void shipmentDeliveryReconstructsPairedPickupAndPickupDoesNotPruneRepairableSuffix(int pickupSeconds,int deliverySeconds) {
        try(var f=RawDistanceTemporalRoutingTest.fixture((l,t,p,v)->t>=100&&t<110?10:1000,10,10,10000)) {
            var costs=f.costs();var stop=service("stop","destination",0,120,0);
            var shipment=com.graphhopper.jsprit.core.problem.job.Shipment.Builder.newInstance("shipment")
                    .setPickupLocation(Location.newInstance("origin")).setDeliveryLocation(Location.newInstance("origin"))
                    .setPickupServiceTime(pickupSeconds).setDeliveryServiceTime(deliverySeconds).build();
            var pickup=new com.graphhopper.jsprit.core.problem.solution.route.activity.PickupShipment(shipment);
            var delivery=new com.graphhopper.jsprit.core.problem.solution.route.activity.DeliverShipment(shipment);
            pickup.setTheoreticalLatestOperationStartTime(10000);delivery.setTheoreticalLatestOperationStartTime(10000);
            var route=VehicleRoute.Builder.newInstance(f.vehicle()).addService(stop).build();
            var state=new StateManager(VehicleRoutingProblem.Builder.newInstance().addVehicle(f.vehicle()).addJob(stop).addJob(shipment).setRoutingCost(costs).build());
            var facts=new JobInsertionContext(route,shipment,f.vehicle(),route.getDriver(),0);
            facts.getAssociatedActivities().add(pickup);facts.getAssociatedActivities().add(delivery);
            var constraint=new TimeWindowConstraintWithDriverTime(state,costs,5000);
            assertEquals(ConstraintsStatus.FULFILLED,constraint.fulfilled(facts,route.getStart(),pickup,route.getActivities().getFirst(),0));
            var related=new com.graphhopper.jsprit.core.problem.misc.ActivityContext();related.setInsertionIndex(0);facts.setRelatedActivityContext(related);
            assertEquals(ConstraintsStatus.FULFILLED,constraint.fulfilled(facts,pickup,delivery,route.getActivities().getFirst(),pickupSeconds));
        }
    }

    @Test void insertionMayRepairAnInfeasibleDirectLegByReachingALaterIsland() {
        try(var f=RawDistanceTemporalRoutingTest.fixture((l,t,p,v)->t>=100&&t<110?10:1000,10,10,10000)) {
            var costs=assertDoesNotThrow(f::costs);
            var stop=service("stop","destination",0,120,0);
            var inserted=service("inserted","origin",0,10000,100);
            var route=VehicleRoute.Builder.newInstance(f.vehicle()).addService(stop).build();
            var state=new StateManager(VehicleRoutingProblem.Builder.newInstance().addVehicle(f.vehicle()).addJob(stop).addJob(inserted).setRoutingCost(costs).build());
            var fact=new JobInsertionContext(route,inserted,f.vehicle(),route.getDriver(),0);
            var status=new TimeWindowConstraintWithDriverTime(state,costs,5000).fulfilled(fact,route.getStart(),
                    ServiceActivity.newInstance(inserted),route.getActivities().getFirst(),0);
            assertEquals(ConstraintsStatus.FULFILLED,status,"Insertion reaches destination at 110; direct arrival 1000 must not prune it");
        }
    }

    @Test void checksEntireActualSuffixBeyondNextActivityInsteadOfCoarseOdSlack() {
        try(var f=RawDistanceTemporalRoutingTest.fixture((l,t,p,v)->t<100?10:1000,100,0,10000)) {
            var costs=assertDoesNotThrow(f::costs);
            var next=service("next","destination",0,5000,0);
            var suffix=service("suffix","return",0,2000,0);
            var inserted=service("inserted","origin",0,10000,100);
            var route=VehicleRoute.Builder.newInstance(f.vehicle()).addService(next).addService(suffix).build();
            var state=new StateManager(VehicleRoutingProblem.Builder.newInstance().addVehicle(f.vehicle()).addJob(next).addJob(suffix).addJob(inserted).setRoutingCost(costs).build());
            var fact=new JobInsertionContext(route,inserted,f.vehicle(),route.getDriver(),0);
            var status=new TimeWindowConstraintWithDriverTime(state,costs,9000).fulfilled(fact,route.getStart(),
                    ServiceActivity.newInstance(inserted),route.getActivities().getFirst(),0);
            assertEquals(ConstraintsStatus.NOT_FULFILLED,status,"Actual suffix arrival 2100 violates 2000 although half-hour estimates suggest 120");
        }
    }

    @Test void sevenHourLimitUsesFullActualRouteAndNeverBreaksLaterPositions() {
        try(var f=RawDistanceTemporalRoutingTest.fixture((l,t,p,v)->t<100?10:20000,100,0,100000)) {
            var costs=assertDoesNotThrow(f::costs);
            var next=service("next","destination",0,90000,0);
            var inserted=service("inserted","origin",0,90000,100);
            var route=VehicleRoute.Builder.newInstance(f.vehicle()).addService(next).build();
            var state=new StateManager(VehicleRoutingProblem.Builder.newInstance().addVehicle(f.vehicle()).addJob(next).addJob(inserted).setRoutingCost(costs).build());
            var fact=new JobInsertionContext(route,inserted,f.vehicle(),route.getDriver(),0);
            assertEquals(ConstraintsStatus.NOT_FULFILLED,new MaxRouteDurationConstraint(25200,state,costs).fulfilled(fact,route.getStart(),
                    ServiceActivity.newInstance(inserted),route.getActivities().getFirst(),0));
        }
    }

    @Test void sharedSolverAcceptsOriginalDistanceOnlyProfileAndRetainsWaitingCosts() {
        try(var f=RawDistanceTemporalRoutingTest.fixture((l,t,p,v)->t<900?7:5.5,900,0,10800)) {
            var costs=assertDoesNotThrow(f::costs);
            var stop=service("stop","destination",20,100,3);
            var problem=VehicleRoutingProblem.Builder.newInstance().addVehicle(f.vehicle()).addJob(stop)
                    .setRoutingCost(costs.solverCosts()).setActivityCosts(new HagridActivityCosts(5)).build();
            var solution=com.graphhopper.jsprit.core.util.Solutions.bestOf(HAGRIDRouterUtils.configureAlgorithm(problem,1,1,costs.getNetwork(),0,25200).searchSolutions());
            assertTrue(solution.getUnassignedJobs().isEmpty());
            assertEquals(1,solution.getRoutes().size());
            var route=solution.getRoutes().iterator().next();
            assertTrue(route.getEnd().getArrTime()-route.getDepartureTime()<=25200);
            assertTrue(solution.getCost()>=189.15+300*RawDistanceTemporalRoutingTest.RATE-1e-9);
        }
    }

    static Service service(String id,String link,double start,double end,double seconds){
        return Service.Builder.newInstance(id).setLocation(Location.newInstance(link)).setServiceTime(seconds)
                .setTimeWindow(com.graphhopper.jsprit.core.problem.solution.route.activity.TimeWindow.newInstance(start,end)).build();
    }
}
