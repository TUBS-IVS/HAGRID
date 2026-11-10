package hagrid.core.routing;

import com.graphhopper.jsprit.core.problem.VehicleRoutingProblem;
import com.graphhopper.jsprit.core.problem.solution.spec.*;
import com.graphhopper.jsprit.core.util.Solutions;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RawTemporalSolutionObjectiveTest {
    @Test void removingDelayCannotMakeAnInvalidUnchangedRouteAFiniteWinningSolution() {
        try(var f=RawDistanceTemporalRoutingTest.fixture((l,t,p,v)->l.getId().toString().equals("destination")?(t<100?105:1):1,10,0,1000)) {
            var costs=f.costs();
            var delay=RawTemporalInsertionTest.service("delay","origin",0,0,100);
            var deadline=RawTemporalInsertionTest.service("deadline","destination",0,102,0);
            var wait=RawTemporalInsertionTest.service("wait","return",200,500,0);
            var problem=VehicleRoutingProblem.Builder.newInstance().setFleetSize(VehicleRoutingProblem.FleetSize.FINITE)
                    .addVehicle(f.vehicle()).addJob(delay).addJob(deadline).addJob(wait)
                    .setRoutingCost(costs.solverCosts()).setActivityCosts(new HagridActivityCosts(5)).build();
            var algorithm=HAGRIDRouterUtils.configureAlgorithm(problem,3,1,costs.getNetwork(),0,25200);
            algorithm.addInitialSolution(SolutionSpec.of(RouteSpec.of("van",ActivitySpec.visit("delay"),ActivitySpec.visit("deadline"),ActivitySpec.visit("wait"))));
            var valid=Solutions.bestOf(algorithm.searchSolutions());
            assertTrue(valid.getUnassignedJobs().isEmpty());assertTrue(Double.isFinite(algorithm.getObjectiveFunction().getCosts(valid)));
            var invalid=com.graphhopper.jsprit.core.problem.solution.VehicleRoutingProblemSolution.copyOf(valid);
            var route=invalid.getRoutes().iterator().next();assertTrue(route.getTourActivities().removeJob(delay));
            // Simulates ruin: destination now arrives105>102, though the remaining route is never an insertion target.
            new DepartureTimeReScheduler().updateActivityTimes(route,costs.solverCosts(),problem.getActivityCosts());
            assertEquals(105,route.getActivities().getFirst().getArrTime());
            assertFalse(ExactTemporalTourFeasibility.route(costs,route,route.getDepartureTime(),25200));
            invalid.setCost(algorithm.getObjectiveFunction().getCosts(invalid));assertTrue(Double.isFinite(invalid.getCost()));
            assertTrue(invalid.getCost()<valid.getCost(),"The native lateness score alone rewards this invalid ruin result");
            var pool=new java.util.ArrayList<>(java.util.List.of(valid));
            var acceptor=algorithm.getSearchStrategyManager().getStrategies().iterator().next().getSolutionAcceptor();
            assertFalse(acceptor.acceptSolution(pool,invalid));
            assertEquals(Double.POSITIVE_INFINITY,invalid.getCost(),"Native bestEver also inspects rejected discoveries");
            assertEquals(java.util.List.of(valid),pool,"Keep the valid incumbent unchanged");
        }
    }

    @Test void validCandidateThresholdDecisionsMatchThePinnedNativeAcceptor() {
        try(var f=RawDistanceTemporalRoutingTest.fixture((l,t,p,v)->t<900?7:5.5,900,0,10800)) {
            var costs=f.costs();var stop=RawTemporalInsertionTest.service("s","destination",0,1000,0);
            var problem=VehicleRoutingProblem.Builder.newInstance().addVehicle(f.vehicle()).addJob(stop).setRoutingCost(costs.solverCosts()).build();
            var algorithm=HAGRIDRouterUtils.configureAlgorithm(problem,1,100,costs.getNetwork(),0,25200);
            var actual=(com.graphhopper.jsprit.core.algorithm.acceptor.SchrimpfAcceptance)algorithm.getSearchStrategyManager().getStrategies().iterator().next().getSolutionAcceptor();
            var nativeAcceptor=new com.graphhopper.jsprit.core.algorithm.acceptor.SchrimpfAcceptance(1,.15);
            nativeAcceptor.setInitialThreshold(3);nativeAcceptor.setMaxIterations(100);actual.setMaxIterations(100);
            for(int iteration:new int[]{1,50,100}) {
                var incumbent=new com.graphhopper.jsprit.core.problem.solution.VehicleRoutingProblemSolution(java.util.List.of(),100);
                var candidate=new com.graphhopper.jsprit.core.problem.solution.VehicleRoutingProblemSolution(java.util.List.of(),101);
                var pool=new java.util.ArrayList<>(java.util.List.of(incumbent));
                algorithm.getAlgorithmListeners().iterationStarts(iteration,problem,pool);
                nativeAcceptor.informIterationStarts(iteration,problem,pool);
                assertEquals(nativeAcceptor.getCurrentThreshold(),actual.getCurrentThreshold(),1e-12);
                boolean expected=nativeAcceptor.acceptSolution(new java.util.ArrayList<>(java.util.List.of(incumbent)),candidate);
                assertEquals(iteration==1,expected);assertEquals(expected,actual.acceptSolution(pool,candidate));
                assertEquals(1,pool.size());
            }
        }
    }

    @Test void invalidProvidedWarmSeedIsRejectedBeforeItCanBecomeBestEver() {
        try(var f=RawDistanceTemporalRoutingTest.fixture((l,t,p,v)->l.getId().toString().equals("destination")?(t<100?105:1):1,10,0,1000)) {
            var costs=f.costs();var stop=RawTemporalInsertionTest.service("late","destination",0,102,0);
            var problem=VehicleRoutingProblem.Builder.newInstance().addVehicle(f.vehicle()).addJob(stop)
                    .setRoutingCost(costs.solverCosts()).setActivityCosts(new HagridActivityCosts(5)).build();
            var algorithm=HAGRIDRouterUtils.configureAlgorithm(problem,1,1,costs.getNetwork(),0,25200);
            algorithm.addInitialSolution(SolutionSpec.of(RouteSpec.of("van",ActivitySpec.visit("late"))));
            assertThrows(IllegalArgumentException.class,algorithm::searchSolutions);
        }
    }
}
