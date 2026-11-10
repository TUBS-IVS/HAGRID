package hagrid.core.routing;

import com.graphhopper.jsprit.core.algorithm.VehicleRoutingAlgorithm;
import com.graphhopper.jsprit.core.algorithm.acceptor.SchrimpfAcceptance;
import com.graphhopper.jsprit.core.problem.VehicleRoutingProblem;
import com.graphhopper.jsprit.core.problem.solution.VehicleRoutingProblemSolution;
import com.graphhopper.jsprit.core.util.Solutions;
import java.util.Collection;

/** Keeps native acceptance/score policy, adding whole-solution feasibility after non-FIFO ruin. */
final class ExactTemporalAcceptance extends SchrimpfAcceptance {
    private final ModalTransportCosts roads;
    private final double durationLimit;
    ExactTemporalAcceptance(ModalTransportCosts roads,double durationLimit) {
        // Pinned jsprit2.0 defaults used by HAGRID: memory1, alpha.15, initial best-cost fraction.03.
        super(1,.15);this.roads=roads;this.durationLimit=durationLimit;
    }
    private boolean feasible(VehicleRoutingProblemSolution solution) {
        for(var route:solution.getRoutes())
            if(!ExactTemporalTourFeasibility.route(roads,route,route.getDepartureTime(),durationLimit))return false;
        return true;
    }
    private boolean admissible(VehicleRoutingProblemSolution candidate) {
        if(feasible(candidate))return true;
        // VehicleRoutingAlgorithm memorizes bestEver even for rejected discoveries. A rejection alone
        // would therefore allow a cheap invalid ruined route to reappear in the final solution set.
        candidate.setCost(Double.POSITIVE_INFINITY);return false;
    }
    @Override public boolean acceptSolution(Collection<VehicleRoutingProblemSolution> solutions,VehicleRoutingProblemSolution candidate) {
        return admissible(candidate)&&super.acceptSolution(solutions,candidate);
    }
    @Override public boolean acceptSolution(VehicleRoutingProblemSolution current,VehicleRoutingProblemSolution candidate) {
        return admissible(candidate)&&super.acceptSolution(current,candidate);
    }
    @Override public void informAlgorithmStarts(VehicleRoutingProblem problem,VehicleRoutingAlgorithm algorithm,
            Collection<VehicleRoutingProblemSolution> solutions) {
        super.informAlgorithmStarts(problem,algorithm,solutions);
        // PrettyAlgorithmBuilder registers this native acceptor callback after initial construction /
        // initial-seed state calculation, before VehicleRoutingAlgorithm initializes bestEver.
        for(var solution:solutions)if(!feasible(solution))
            throw new IllegalArgumentException("Infeasible raw temporal initial solution");
    }
    @Override public void informIterationStarts(int iteration,VehicleRoutingProblem problem,
            Collection<VehicleRoutingProblemSolution> solutions) {
        super.informIterationStarts(iteration,problem,solutions);
        // Jsprit skips its default threshold initializer when a custom acceptor is supplied.
        if(iteration==1)setInitialThreshold(Solutions.bestOf(solutions).getCost()*.03);
    }
}
