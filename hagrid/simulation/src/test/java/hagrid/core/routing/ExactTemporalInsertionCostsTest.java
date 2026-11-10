package hagrid.core.routing;

import com.graphhopper.jsprit.core.problem.Location;
import com.graphhopper.jsprit.core.problem.job.Shipment;
import com.graphhopper.jsprit.core.problem.misc.*;
import com.graphhopper.jsprit.core.problem.solution.route.VehicleRoute;
import com.graphhopper.jsprit.core.problem.solution.route.activity.*;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleImpl;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExactTemporalInsertionCostsTest {
    @Test void pickupBoundIncludesPossibleNegativeDeliveryUturnCredit() {
        try(var f=RawDistanceTemporalRoutingTest.fixture((l,t,p,v)->t<900?7:5.5,900,0,10800,n->{
            var link=org.matsim.core.network.NetworkUtils.createAndAddLink(n,org.matsim.api.core.v01.Id.createLinkId("reverse"),
                    n.getNodes().get(org.matsim.api.core.v01.Id.createNodeId("c")),n.getNodes().get(org.matsim.api.core.v01.Id.createNodeId("b")),100,10,1000,1);
            link.setAllowedModes(java.util.Set.of("car"));
        })) {
            var first=RawTemporalInsertionTest.service("first","destination",0,1000,0);
            var second=RawTemporalInsertionTest.service("second","reverse",0,1000,0);
            var shipment=Shipment.Builder.newInstance("pair").setPickupLocation(Location.newInstance("origin"))
                    .setDeliveryLocation(Location.newInstance("origin")).build();
            var pickup=new PickupShipment(shipment);var delivery=new DeliverShipment(shipment);
            pickup.setTheoreticalLatestOperationStartTime(1000);delivery.setTheoreticalLatestOperationStartTime(1000);
            var route=VehicleRoute.Builder.newInstance(f.vehicle()).addService(first).addService(second).build();
            var facts=new JobInsertionContext(route,shipment,f.vehicle(),route.getDriver(),0);
            facts.getAssociatedActivities().add(pickup);facts.getAssociatedActivities().add(delivery);
            var calculator=new ExactTemporalInsertionCosts(f.costs(),new HagridActivityCosts(5),1);
            double bound=calculator.getCosts(facts,route.getStart(),route.getActivities().getFirst(),pickup,0);
            var related=new ActivityContext();related.setInsertionIndex(0);facts.setRelatedActivityContext(related);
            double completion=calculator.getCosts(facts,route.getActivities().getFirst(),route.getActivities().getLast(),delivery,7);
            double credit=new UTurnSoftConstraint(f.context().network(),1).getCosts(facts,route.getActivities().getFirst(),delivery,route.getActivities().getLast(),7);
            assertEquals(-1,credit);
            // Hand-enumerated roads: old500m, paired800m; removing one existing U-turn credits1.
            double fullDelta=300*RawDistanceTemporalRoutingTest.RATE-1;
            assertEquals(fullDelta,bound+completion+credit,1e-12);
            assertTrue(bound<=fullDelta,"A native negative best cost must not prune this better delivery completion");
        }
    }
    @Test void fullSuffixWaitingDeltaUsesChangedLaterLinkTimeRatherThanLocalDelay() {
        try(var f=RawDistanceTemporalRoutingTest.fixture((l,t,p,v)->switch(l.getId().toString()) {
            case "destination"->10;case "return"->t<55?10:1;default->1;
        },5,0,1000)) {
            var next=RawTemporalInsertionTest.service("next","destination",0,1000,0);
            var suffix=RawTemporalInsertionTest.service("suffix","return",100,200,0);
            var inserted=RawTemporalInsertionTest.service("inserted","origin",0,1000,50);
            var route=VehicleRoute.Builder.newInstance(f.vehicle()).addService(next).addService(suffix).build();
            var facts=new JobInsertionContext(route,inserted,f.vehicle(),route.getDriver(),0);
            var activity=ServiceActivity.newInstance(inserted);activity.setTheoreticalLatestOperationStartTime(1000);
            double delta=new ExactTemporalInsertionCosts(f.costs(),new HagridActivityCosts(5),0).getCosts(
                    facts,route.getStart(),route.getActivities().getFirst(),activity,0);
            // Original suffix arrives20:80s waiting. Candidate arrives61:39s waiting. Both travel300m.
            assertEquals(-410,delta,1e-9);
        }
    }

    @Test void pickupBoundAllowsNegativePairedDeltaAndPairIsChargedExactlyOnce() {
        try(var f=RawDistanceTemporalRoutingTest.fixture((l,t,p,v)->switch(l.getId().toString()) {
            case "destination"->10;case "return"->t<55?10:1;default->1;
        },5,0,1000)) {
            var next=RawTemporalInsertionTest.service("next","destination",0,1000,0);
            var suffix=RawTemporalInsertionTest.service("suffix","return",100,200,0);
            var shipment=Shipment.Builder.newInstance("pair").setPickupLocation(Location.newInstance("origin"))
                    .setDeliveryLocation(Location.newInstance("origin")).setPickupServiceTime(20).setDeliveryServiceTime(30).build();
            var pickup=new PickupShipment(shipment);var delivery=new DeliverShipment(shipment);
            pickup.setTheoreticalLatestOperationStartTime(1000);delivery.setTheoreticalLatestOperationStartTime(1000);
            var route=VehicleRoute.Builder.newInstance(f.vehicle()).addService(next).addService(suffix).build();
            var facts=new JobInsertionContext(route,shipment,f.vehicle(),route.getDriver(),0);
            facts.getAssociatedActivities().add(pickup);facts.getAssociatedActivities().add(delivery);
            var calculator=new ExactTemporalInsertionCosts(f.costs(),new HagridActivityCosts(5),0);
            double bound=calculator.getCosts(facts,route.getStart(),route.getActivities().getFirst(),pickup,0);
            assertEquals(-800-300*RawDistanceTemporalRoutingTest.RATE,bound,1e-9);
            var related=new ActivityContext();related.setInsertionIndex(0);facts.setRelatedActivityContext(related);
            double completion=calculator.getCosts(facts,pickup,route.getActivities().getFirst(),delivery,20);
            assertEquals(390+300*RawDistanceTemporalRoutingTest.RATE,completion,1e-9);
            assertEquals(-410,bound+completion,1e-9);assertTrue(bound<=-410);
        }
    }

    @Test void vehicleSwitchAccessAlreadyAddedByNativeCalculatorIsNotDoubleCharged() {
        try(var f=RawDistanceTemporalRoutingTest.fixture((l,t,p,v)->t<900?7:5.5,900,0,10800)) {
            var stop=RawTemporalInsertionTest.service("stop","destination",0,1000,0);
            var inserted=RawTemporalInsertionTest.service("inserted","return",0,1000,0);
            var route=VehicleRoute.Builder.newInstance(f.vehicle()).addService(stop).build();
            var switched=VehicleImpl.Builder.newInstance("open").setType(f.vehicle().getType())
                    .setStartLocation(Location.newInstance("return")).setReturnToDepot(false).setLatestArrival(1000).build();
            var facts=new JobInsertionContext(route,inserted,switched,route.getDriver(),0);
            var activity=ServiceActivity.newInstance(inserted);activity.setTheoreticalLatestOperationStartTime(1000);
            var calculator=new ExactTemporalInsertionCosts(f.costs(),new HagridActivityCosts(5),0);
            double contribution=calculator.getCosts(facts,route.getStart(),route.getActivities().getFirst(),activity,0);
            // Old closed tour300m, new open tour200m. Native access adds100m, AIC must subtract200m.
            assertEquals(100*RawDistanceTemporalRoutingTest.RATE,calculator.nativeAccessEgress(facts),1e-12);
            assertEquals(-200*RawDistanceTemporalRoutingTest.RATE,contribution,1e-12);
            assertEquals(-100*RawDistanceTemporalRoutingTest.RATE,contribution+calculator.nativeAccessEgress(facts),1e-12);
        }
    }
}
