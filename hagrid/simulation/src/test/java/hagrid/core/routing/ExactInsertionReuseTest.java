package hagrid.core.routing;

import com.graphhopper.jsprit.core.problem.misc.JobInsertionContext;
import com.graphhopper.jsprit.core.problem.solution.route.VehicleRoute;
import com.graphhopper.jsprit.core.problem.solution.route.activity.ServiceActivity;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExactInsertionReuseTest {
    @Test void insertionCanRepairAnOriginalLegBeyondTheCapturedHorizon() {
        try(var f=RawDistanceTemporalRoutingTest.fixture((l,t,p,v)->t<100?2000:5,10,10,1000)) {
            var stop=RawTemporalInsertionTest.service("stop","destination",0,120,0);
            var job=RawTemporalInsertionTest.service("repair","origin",0,1000,100);
            var route=VehicleRoute.Builder.newInstance(f.vehicle()).addService(stop).build();
            var facts=new JobInsertionContext(route,job,f.vehicle(),route.getDriver(),0);
            var activity=ServiceActivity.newInstance(job);var next=route.getActivities().getFirst();var roads=f.costs();
            var replay=new ExactInsertionReplay(roads,new HagridActivityCosts(5),500);
            assertThrows(TravelTimeSnapshot.RangeException.class,()->roads.exactTransportTime(f.vehicle().getStartLocation(),next.getLocation(),0,f.vehicle()));
            assertTrue(replay.evaluate(facts,activity,next,false).feasible());
            assertEquals(fullCost(roads,new HagridActivityCosts(5),java.util.List.of(activity,next),f.vehicle(),0),replay.evaluate(facts,activity,next,true).variableCost(),1e-9);
        }
    }

    @Test void pairedPickupIndexAndWindowsInvalidateCandidateAndRecheckTheFullPair() {
        try(var f=RawDistanceTemporalRoutingTest.fixture((l,t,p,v)->t>=100&&t<110?10:1000,10,10,10000)) {
            var stop=RawTemporalInsertionTest.service("stop","destination",0,120,0);
            var pair=com.graphhopper.jsprit.core.problem.job.Shipment.Builder.newInstance("pair")
                    .setPickupLocation(f.vehicle().getStartLocation()).setDeliveryLocation(f.vehicle().getStartLocation()).setPickupServiceTime(100).build();
            var pickup=new com.graphhopper.jsprit.core.problem.solution.route.activity.PickupShipment(pair);
            var delivery=new com.graphhopper.jsprit.core.problem.solution.route.activity.DeliverShipment(pair);
            pickup.setTheoreticalLatestOperationStartTime(10000);delivery.setTheoreticalLatestOperationStartTime(10000);
            var route=VehicleRoute.Builder.newInstance(f.vehicle()).addService(stop).build();
            var facts=new JobInsertionContext(route,pair,f.vehicle(),route.getDriver(),0);
            facts.getAssociatedActivities().add(pickup);facts.getAssociatedActivities().add(delivery);
            var related=new com.graphhopper.jsprit.core.problem.misc.ActivityContext();related.setInsertionIndex(0);facts.setRelatedActivityContext(related);
            var roads=f.costs();var replay=new ExactInsertionReplay(roads,new HagridActivityCosts(5),5000);
            var next=route.getActivities().getFirst();
            assertTrue(replay.evaluate(facts,delivery,next,false).feasible());
            assertEquals(fullCost(roads,new HagridActivityCosts(5),java.util.List.of(pickup,delivery,next),f.vehicle(),0),replay.evaluate(facts,delivery,next,true).variableCost(),1e-9);
            pickup.setTheoreticalEarliestOperationStartTime(200);
            assertFalse(replay.evaluate(facts,delivery,next,false).feasible());
            pickup.setTheoreticalEarliestOperationStartTime(0);related.setInsertionIndex(1);
            assertFalse(replay.evaluate(facts,delivery,route.getEnd(),false).feasible());
        }
    }

    @Test void changedOldVehicleDepartureAndInsertedWindowDoNotReuseStaleCosts() {
        try(var f=RawDistanceTemporalRoutingTest.fixture((l,t,p,v)->t<100?10:20,10,10,10000)) {
            var stop=RawTemporalInsertionTest.service("stop","destination",0,10000,0);
            var job=RawTemporalInsertionTest.service("new","origin",0,10000,25);
            var route=VehicleRoute.Builder.newInstance(f.vehicle()).addService(stop).build();
            var facts=new JobInsertionContext(route,job,f.vehicle(),route.getDriver(),0);
            var inserted=ServiceActivity.newInstance(job);var next=route.getActivities().getFirst();var roads=f.costs();
            var calc=new ExactTemporalInsertionCosts(roads,new HagridActivityCosts(5),0);
            calc.getCosts(facts,route.getStart(),next,inserted,0);
            inserted.setTheoreticalEarliestOperationStartTime(300);
            var other=com.graphhopper.jsprit.core.problem.vehicle.VehicleImpl.Builder.newInstance("other").setType(f.vehicle().getType()).setStartLocation(f.vehicle().getStartLocation()).setLatestArrival(10000).build();
            route.setVehicleAndDepartureTime(other,200);
            double fresh=new ExactTemporalInsertionCosts(roads,new HagridActivityCosts(5),0).getCosts(facts,route.getStart(),next,inserted,0);
            assertEquals(fresh,calc.getCosts(facts,route.getStart(),next,inserted,0),1e-9);
        }
    }

    @Test void cachedInsertionStillRejectsClosedContext() {
        var f=RawDistanceTemporalRoutingTest.fixture((l,t,p,v)->t<100?10:20,10,10,10000);
        try(f) {
            var a=RawTemporalInsertionTest.service("a","destination",0,10000,0);
            var job=RawTemporalInsertionTest.service("new","origin",0,10000,25);
            var route=VehicleRoute.Builder.newInstance(f.vehicle()).addService(a).build();
            var facts=new JobInsertionContext(route,job,f.vehicle(),route.getDriver(),0);
            var activity=ServiceActivity.newInstance(job);var next=route.getActivities().getFirst();
            var calc=new ExactTemporalInsertionCosts(f.costs(),new HagridActivityCosts(5),0);
            calc.getCosts(facts,route.getStart(),next,activity,0);
            f.close();
            assertThrows(IllegalStateException.class,()->calc.getCosts(facts,route.getStart(),next,activity,0));
        }
    }

    @Test void cachedPrefixesMatchFullReplayAcrossPositionsTrafficIntervalsAndChangedInputs() {
        try(var f=RawDistanceTemporalRoutingTest.fixture((l,t,p,v)->t>=100&&t<130?4:17,10,10,10000)) {
            var a=RawTemporalInsertionTest.service("a","destination",0,10000,7);
            var b=RawTemporalInsertionTest.service("b","return",120,10000,9);
            var job=RawTemporalInsertionTest.service("new","origin",0,10000,25);
            var route=VehicleRoute.Builder.newInstance(f.vehicle()).addService(a).addService(b).build();
            var activity=ServiceActivity.newInstance(job);var roads=f.costs();var activities=new HagridActivityCosts(5);
            var calc=new ExactTemporalInsertionCosts(roads,activities,0);
            for(double departure:new double[]{0,90,100,120,130}) {
                var facts=new JobInsertionContext(route,job,f.vehicle(),route.getDriver(),departure);
                for(double limit:new double[]{40,120,5000}) {
                    var replay=new ExactInsertionReplay(roads,activities,limit);
                    for(int at=0;at<=route.getActivities().size();at++) {
                        var next=at==route.getActivities().size()?route.getEnd():route.getActivities().get(at);
                        var previous=at==0?route.getStart():route.getActivities().get(at-1);
                        var candidate=new java.util.ArrayList<>(route.getActivities());candidate.add(at,activity);
                        boolean expected=ExactTemporalTourFeasibility.insertion(roads,facts,activity,next,limit)
                                ==com.graphhopper.jsprit.core.problem.constraint.HardActivityConstraint.ConstraintsStatus.FULFILLED;
                        assertEquals(expected,replay.evaluate(facts,activity,next,false).feasible());
                        double full=fullCost(roads,activities,candidate,f.vehicle(),departure);
                        assertEquals(full,replay.evaluate(facts,activity,next,true).variableCost(),1e-9);
                        double old=fullCost(roads,activities,route.getActivities(),route.getVehicle(),route.getDepartureTime());
                        assertEquals(full-old,calc.getCosts(facts,previous,next,activity,0),1e-9);
                    }
                }
            }
        }
    }

    private double fullCost(ModalTransportCosts roads,HagridActivityCosts activities,java.util.List<com.graphhopper.jsprit.core.problem.solution.route.activity.TourActivity> stops,
                            com.graphhopper.jsprit.core.problem.vehicle.Vehicle vehicle,double departure) {
        var previous=vehicle.getStartLocation();double time=departure,cost=0;
        for(var stop:stops) {
            cost+=roads.solverCosts().getTransportCost(previous,stop.getLocation(),time,null,vehicle);
            time+=roads.exactTransportTime(previous,stop.getLocation(),time,vehicle);
            cost+=activities.getActivityCost(stop,time,null,vehicle);
            time=Math.max(time,stop.getTheoreticalEarliestOperationStartTime())+stop.getOperationTime();previous=stop.getLocation();
        }
        if(vehicle.isReturnToDepot()&&!stops.isEmpty()) {
            cost+=roads.solverCosts().getTransportCost(previous,vehicle.getEndLocation(),time,null,vehicle);
            roads.exactTransportTime(previous,vehicle.getEndLocation(),time,vehicle);
        }
        return cost;
    }

    @Test void repeatedInsertionEvaluationReusesItsResultAndInvalidatesChangedRoute() {
        try(var f=RawDistanceTemporalRoutingTest.fixture((l,t,p,v)->t<100?10:20,10,10,10000)) {
            var a=RawTemporalInsertionTest.service("a","destination",0,10000,0);
            var b=RawTemporalInsertionTest.service("b","return",0,10000,0);
            var job=RawTemporalInsertionTest.service("new","origin",0,10000,25);
            var route=VehicleRoute.Builder.newInstance(f.vehicle()).addService(a).addService(b).build();
            var facts=new JobInsertionContext(route,job,f.vehicle(),route.getDriver(),0);
            var activity=ServiceActivity.newInstance(job);var next=route.getActivities().getFirst();
            var costs=f.costs();var calc=new ExactTemporalInsertionCosts(costs,new HagridActivityCosts(5),0);
            double expected=calc.getCosts(facts,route.getStart(),next,activity,0);
            var before=f.context().statistics();
            assertEquals(expected,calc.getCosts(facts,route.getStart(),next,activity,0));
            assertEquals(before.cacheHits(),f.context().statistics().cacheHits(),"A repeated insertion must reuse the evaluated tour, not visit its legs again");
            next.setTheoreticalEarliestOperationStartTime(200);
            double changed=calc.getCosts(facts,route.getStart(),next,activity,0);
            double fresh=new ExactTemporalInsertionCosts(costs,new HagridActivityCosts(5),0).getCosts(facts,route.getStart(),next,activity,0);
            assertEquals(fresh,changed,1e-12);
        }
    }

    @Test void everyPairedInsertionPositionMatchesIndependentFullReplay() {
        try(var f=RawDistanceTemporalRoutingTest.fixture((l,t,p,v)->t>=100&&t<140?4:17,10,10,10000)) {
            var route=VehicleRoute.Builder.newInstance(f.vehicle())
                    .addService(RawTemporalInsertionTest.service("a","destination",0,10000,7))
                    .addService(RawTemporalInsertionTest.service("b","return",120,10000,9))
                    .addService(RawTemporalInsertionTest.service("c","origin",0,10000,2)).build();
            var pair=com.graphhopper.jsprit.core.problem.job.Shipment.Builder.newInstance("pair")
                    .setPickupLocation(f.vehicle().getStartLocation())
                    .setDeliveryLocation(com.graphhopper.jsprit.core.problem.Location.newInstance("return"))
                    .setPickupServiceTime(23).setDeliveryServiceTime(11).build();
            var pickup=new com.graphhopper.jsprit.core.problem.solution.route.activity.PickupShipment(pair);
            var delivery=new com.graphhopper.jsprit.core.problem.solution.route.activity.DeliverShipment(pair);
            pickup.setTheoreticalLatestOperationStartTime(10000);delivery.setTheoreticalLatestOperationStartTime(10000);
            var roads=f.costs();var activities=new HagridActivityCosts(5);
            for(double departure:new double[]{0,95,125}) for(double limit:new double[]{60,180,5000}) {
                var facts=new JobInsertionContext(route,pair,f.vehicle(),route.getDriver(),departure);
                facts.getAssociatedActivities().add(pickup);facts.getAssociatedActivities().add(delivery);
                var related=new com.graphhopper.jsprit.core.problem.misc.ActivityContext();facts.setRelatedActivityContext(related);
                var replay=new ExactInsertionReplay(roads,activities,limit);
                for(int at=0;at<=route.getActivities().size();at++) for(int pick=0;pick<=at;pick++) {
                    related.setInsertionIndex(pick);
                    var next=at==route.getActivities().size()?route.getEnd():route.getActivities().get(at);
                    var candidate=new java.util.ArrayList<>(route.getActivities());
                    candidate.add(at,delivery);candidate.add(pick,pickup);
                    boolean expected=ExactTemporalTourFeasibility.insertion(roads,facts,delivery,next,limit)
                            ==com.graphhopper.jsprit.core.problem.constraint.HardActivityConstraint.ConstraintsStatus.FULFILLED;
                    assertEquals(expected,replay.evaluate(facts,delivery,next,false).feasible());
                    assertEquals(fullCost(roads,activities,candidate,f.vehicle(),departure),
                            replay.evaluate(facts,delivery,next,true).variableCost(),1e-9);
                }
            }
        }
    }

}
