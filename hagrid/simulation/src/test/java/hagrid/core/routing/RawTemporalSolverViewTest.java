package hagrid.core.routing;

import com.graphhopper.jsprit.core.problem.VehicleRoutingProblem;
import com.graphhopper.jsprit.core.problem.job.Shipment;
import com.graphhopper.jsprit.core.problem.solution.spec.*;
import com.graphhopper.jsprit.core.util.Solutions;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RawTemporalSolverViewTest {
    @Test void immutableViewKeepsPublicHalfHourEstimateSeparateAndRejectsCoarseInverseDelegation() {
        try(var f=RawDistanceTemporalRoutingTest.fixture((l,t,p,v)->t<900?100:20,900,0,10800)) {
            var costs=f.costs();var exact=costs.solverCosts();assertSame(exact,costs.solverCosts());
            assertEquals(100,costs.getTransportTime(RawDistanceTemporalRoutingTest.from(),RawDistanceTemporalRoutingTest.to(),900,null,f.vehicle()));
            assertEquals(20,exact.getTransportTime(RawDistanceTemporalRoutingTest.from(),RawDistanceTemporalRoutingTest.to(),900,null,f.vehicle()));
            assertEquals(100,costs.getTransportTime(RawDistanceTemporalRoutingTest.from(),RawDistanceTemporalRoutingTest.to(),900,null,f.vehicle()));
            assertThrows(UnsupportedOperationException.class,()->exact.getBackwardTransportTime(RawDistanceTemporalRoutingTest.from(),RawDistanceTemporalRoutingTest.to(),950,null,f.vehicle()));
            assertThrows(UnsupportedOperationException.class,()->exact.getBackwardTransportCost(RawDistanceTemporalRoutingTest.from(),RawDistanceTemporalRoutingTest.to(),950,null,f.vehicle()));
            assertSame(costs,ExactTemporalSolverCosts.rawAdapter(exact));
        }
    }

    @Test void rawVrpFailsBeforeNativeScalarStatesCanBeInstalled() {
        try(var f=RawDistanceTemporalRoutingTest.fixture((l,t,p,v)->t<900?7:5.5,900,0,10800)) {
            var costs=f.costs();var stop=RawTemporalInsertionTest.service("s","destination",0,100,0);
            var problem=VehicleRoutingProblem.Builder.newInstance().addVehicle(f.vehicle()).addJob(stop).setRoutingCost(costs).build();
            var failure=assertThrows(IllegalArgumentException.class,()->HAGRIDRouterUtils.configureAlgorithm(problem,1,1,costs.getNetwork(),0,25200));
            assertTrue(failure.getMessage().contains("solverCosts"));
        }
    }

    @Test void nativeShipmentInsertionAndWarmSeedRunWithoutBackwardScalarStates() {
        try(var f=RawDistanceTemporalRoutingTest.fixture((l,t,p,v)->t<900?7:5.5,900,0,10800)) {
            var costs=f.costs();
            var shipment=Shipment.Builder.newInstance("shipment").setPickupLocation(RawDistanceTemporalRoutingTest.from())
                    .setDeliveryLocation(RawDistanceTemporalRoutingTest.to()).build();
            var problem=VehicleRoutingProblem.Builder.newInstance().addVehicle(f.vehicle()).addJob(shipment)
                    .setRoutingCost(costs.solverCosts()).setActivityCosts(new HagridActivityCosts(5)).build();
            for(boolean warm:new boolean[]{false,true}) {
                var algorithm=HAGRIDRouterUtils.configureAlgorithm(problem,1,1,costs.getNetwork(),0,25200);
                if(warm)algorithm.addInitialSolution(SolutionSpec.of(RouteSpec.of("van",ActivitySpec.pickup("shipment"),ActivitySpec.delivery("shipment"))));
                var solution=assertDoesNotThrow(()->Solutions.bestOf(algorithm.searchSolutions()));
                assertTrue(solution.getUnassignedJobs().isEmpty());assertEquals(1,solution.getRoutes().size());
                var route=solution.getRoutes().iterator().next();
                assertTrue(ExactTemporalTourFeasibility.route(costs,route,route.getDepartureTime(),25200));
            }
        }
    }

    @Test void existingDepartureProposalUsesExactWaitingAndRechecksItsActualSuffix() {
        try(var f=RawDistanceTemporalRoutingTest.fixture((l,t,p,v)->t<900?7:5.5,900,0,10800)) {
            var costs=f.costs();var stop=RawTemporalInsertionTest.service("s","destination",1000,1010,0);
            var problem=VehicleRoutingProblem.Builder.newInstance().addVehicle(f.vehicle()).addJob(stop)
                    .setRoutingCost(costs.solverCosts()).setActivityCosts(new HagridActivityCosts(5)).build();
            var solution=Solutions.bestOf(HAGRIDRouterUtils.configureAlgorithm(problem,1,1,costs.getNetwork(),0,25200).searchSolutions());
            assertTrue(solution.getUnassignedJobs().isEmpty());var route=solution.getRoutes().iterator().next();
            assertEquals(993,route.getDepartureTime());assertEquals(998.5,route.getActivities().getFirst().getArrTime());
            assertEquals(1011,route.getEnd().getArrTime());
            assertEquals(189.15+300*RawDistanceTemporalRoutingTest.RATE+15,solution.getCost(),1e-9);
        }
    }
}
