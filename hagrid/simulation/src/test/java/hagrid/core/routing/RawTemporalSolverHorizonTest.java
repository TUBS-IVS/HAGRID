package hagrid.core.routing;

import com.graphhopper.jsprit.core.problem.VehicleRoutingProblem;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleImpl;
import com.graphhopper.jsprit.core.util.Solutions;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RawTemporalSolverHorizonTest {
    @Test void productionOptimizationBuildsExactSolverViewAndPublishesActualRoadTimes() {
        try(var f=RawDistanceTemporalRoutingTest.fixture((l,t,p,v)->t<900?1000:10,900,0,1000)) {
            var type=f.nativeVehicle().getType();
            var vehicle=org.matsim.freight.carriers.CarrierVehicle.Builder.newInstance(org.matsim.api.core.v01.Id.createVehicleId("van"),
                    org.matsim.api.core.v01.Id.createLinkId("origin"),type).setEarliestStart(900).setLatestEnd(1000).build();
            org.matsim.freight.carriers.CarriersUtils.addCarrierVehicle(f.carrier(),vehicle);
            var service=org.matsim.freight.carriers.CarrierService.Builder.newInstance(
                    org.matsim.api.core.v01.Id.create("late-stop",org.matsim.freight.carriers.CarrierService.class),org.matsim.api.core.v01.Id.createLinkId("destination"))
                    .setCapacityDemand(0).setServiceDuration(0).setServiceStartingTimeWindow(org.matsim.freight.carriers.TimeWindow.newInstance(900,950)).build();
            org.matsim.freight.carriers.CarriersUtils.addService(f.carrier(),service);
            var result=assertDoesNotThrow(()->new CarrierOptimizationService().optimize(f.carrier(),
                    new RoutingRequest(f.carrier(),f.context(),"car"),f.context().config(),42,1));
            assertTrue(result.diagnostics().isEmpty(),result.diagnostics().toString());assertTrue(result.unassignedJobIds().isEmpty());
            var tour=result.plan().getScheduledTours().iterator().next();
            assertEquals(900,tour.getDeparture());
            var legs=tour.getTour().getTourElements().stream().filter(e->e instanceof org.matsim.freight.carriers.Tour.Leg)
                    .map(e->(org.matsim.freight.carriers.Tour.Leg)e).toList();
            assertEquals(10,legs.get(0).getExpectedTransportTime());assertEquals(20,legs.get(1).getExpectedTransportTime());
            assertEquals(189.15+300*RawDistanceTemporalRoutingTest.RATE,result.finalCost(),1e-9);
        }
    }
    @Test void exactFeasibleLateTourSurvivesCoarseEstimateBeyondCapturedHorizon() {
        try(var f=RawDistanceTemporalRoutingTest.fixture((l,t,p,v)->t<900?1000:10,900,0,1000)) {
            var costs=f.costs();
            var vehicle=VehicleImpl.Builder.newInstance("late").setType(f.vehicle().getType())
                    .setStartLocation(RawDistanceTemporalRoutingTest.from()).setEarliestStart(900).setLatestArrival(1000).build();
            var stop=RawTemporalInsertionTest.service("late-stop","destination",900,950,0);
            var problem=VehicleRoutingProblem.Builder.newInstance().addVehicle(vehicle).addJob(stop)
                    .setRoutingCost(costs.solverCosts()).setActivityCosts(new HagridActivityCosts(5)).build();
            var solution=assertDoesNotThrow(()->Solutions.bestOf(HAGRIDRouterUtils.configureAlgorithm(problem,1,1,costs.getNetwork(),0,25200).searchSolutions()));
            assertTrue(solution.getUnassignedJobs().isEmpty(),"Actual departure900, destination910, depot930 are all captured and feasible");
            var route=solution.getRoutes().iterator().next();
            assertTrue(ExactTemporalTourFeasibility.route(costs,route,route.getDepartureTime(),25200));
        }
    }
}
