package hagrid.core.routing;

import com.graphhopper.jsprit.core.problem.VehicleRoutingProblem;
import com.graphhopper.jsprit.core.problem.job.Shipment;
import com.graphhopper.jsprit.core.problem.solution.route.VehicleRoute;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleImpl;
import com.graphhopper.jsprit.core.util.Solutions;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.freight.carriers.*;
import static org.junit.jupiter.api.Assertions.*;

class RawTemporalCandidateFailureTest {
    @Test void productionKeepsValidOriginalWhenExistingDepartureProposalLeavesCapturedDomain() {
        try(var f=RawDistanceTemporalRoutingTest.fixture((l,t,p,v)->l.getId().toString().equals("destination")?(t<50?10:1000):1,50,0,500)) {
            CarriersUtils.addCarrierVehicle(f.carrier(),CarrierVehicle.Builder.newInstance(Id.createVehicleId("van"),
                    Id.createLinkId("origin"),f.nativeVehicle().getType()).setEarliestStart(0).setLatestEnd(500).build());
            CarriersUtils.addService(f.carrier(),CarrierService.Builder.newInstance(Id.create("stop",CarrierService.class),Id.createLinkId("destination"))
                    .setCapacityDemand(0).setServiceDuration(0).setServiceStartingTimeWindow(TimeWindow.newInstance(100,200)).build());
            var result=assertDoesNotThrow(()->new CarrierOptimizationService().optimize(f.carrier(),
                    new RoutingRequest(f.carrier(),f.context(),"car"),f.context().config(),42,1));
            assertTrue(result.diagnostics().isEmpty(),result.diagnostics().toString());assertTrue(result.unassignedJobIds().isEmpty());
            var tour=result.plan().getScheduledTours().iterator().next();assertEquals(0,tour.getDeparture());
            var legs=tour.getTour().getTourElements().stream().filter(e->e instanceof Tour.Leg).map(e->(Tour.Leg)e).toList();
            assertEquals(10,legs.getFirst().getExpectedTransportTime());assertEquals(2,legs.getLast().getExpectedTransportTime());
            assertTrue(Double.isFinite(result.finalCost()));
        }
    }

    @Test void nativeShipmentKeepsFeasiblePairWhenLaterTemporaryPickupSuffixLeavesHorizon() {
        try(var f=RawDistanceTemporalRoutingTest.fixture((l,t,p,v)->l.getId().toString().equals("destination")?(t<950?10:t<990?1000:1):1,10,0,1000)) {
            var costs=f.costs();
            var vehicle=VehicleImpl.Builder.newInstance("late").setType(f.vehicle().getType()).setStartLocation(RawDistanceTemporalRoutingTest.from())
                    .setEarliestStart(900).setLatestArrival(1000).build();
            var stop=RawTemporalInsertionTest.service("existing","destination",900,1000,0);
            var shipment=Shipment.Builder.newInstance("shipment").setPickupLocation(RawDistanceTemporalRoutingTest.from())
                    .setDeliveryLocation(RawDistanceTemporalRoutingTest.from()).setPickupServiceTime(60).setDeliveryServiceTime(30).build();
            var initial=VehicleRoute.Builder.newInstance(vehicle).addService(stop).build();
            var problem=VehicleRoutingProblem.Builder.newInstance().setFleetSize(VehicleRoutingProblem.FleetSize.FINITE)
                    .addVehicle(vehicle).addInitialVehicleRoute(initial).addJob(shipment)
                    .setRoutingCost(costs.solverCosts()).setActivityCosts(new HagridActivityCosts(5)).build();
            var solution=assertDoesNotThrow(()->Solutions.bestOf(HAGRIDRouterUtils.configureAlgorithm(problem,2,1,costs.getNetwork(),0,25200).searchSolutions()));
            assertTrue(solution.getUnassignedJobs().isEmpty());assertEquals(1,solution.getRoutes().size());
            var route=solution.getRoutes().iterator().next();
            assertTrue(ExactTemporalTourFeasibility.route(costs,route,route.getDepartureTime(),25200));
            assertTrue(Double.isFinite(solution.getCost()));
        }
    }
}
