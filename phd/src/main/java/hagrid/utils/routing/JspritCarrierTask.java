package hagrid.utils.routing;

import com.graphhopper.jsprit.core.algorithm.VehicleRoutingAlgorithm;
import com.graphhopper.jsprit.core.problem.VehicleRoutingProblem;
import com.graphhopper.jsprit.core.problem.solution.VehicleRoutingProblemSolution;
import com.graphhopper.jsprit.core.util.Solutions;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;
import org.matsim.api.core.v01.network.Network;

import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierPlan;
import org.matsim.freight.carriers.jsprit.MatsimJspritFactory;
import org.matsim.freight.carriers.jsprit.NetworkBasedTransportCosts;
import org.matsim.freight.carriers.jsprit.NetworkRouter;
import org.matsim.freight.carriers.jsprit.VRPTransportCosts;

import java.util.concurrent.atomic.AtomicInteger;

public class JspritCarrierTask implements Runnable {
    private static final Logger LOGGER = LogManager.getLogger(JspritCarrierTask.class);
    private final Carrier carrier;
    private final VRPTransportCosts netBasedCosts;
    private final AtomicInteger startedVRPCounter;
    private final int taskCount;
    private final Network network;

    /**
     * Constructs a new JspritCarrierTask.
     * Used from MATSim CarriersUtils Class 
     *
     * @param carrier            The carrier to route.
     * @param netBasedCosts      The network-based transport costs.
     * @param startedVRPCounter  The counter for started VRP tasks.
     * @param taskCount          The total number of tasks.
     * @param network            The network.
     */
    public JspritCarrierTask(Carrier carrier, VRPTransportCosts netBasedCosts,
                             AtomicInteger startedVRPCounter, int taskCount, Network network) {
        this.carrier = carrier;
        this.netBasedCosts = netBasedCosts;
        this.startedVRPCounter = startedVRPCounter;
        this.taskCount = taskCount;
        this.network = network;
    }

    public int getPriority() {
        return carrier.getServices().size();
    }

    @Override
    public void run() {
        LOGGER.info("Started VRP solving for carrier number {} out of {} carriers. Thread id: {}. Priority: {}",
                startedVRPCounter.incrementAndGet(), taskCount, Thread.currentThread().getId(), this.getPriority());

        double start = System.currentTimeMillis();
        int serviceCount = carrier.getServices().size();

        VehicleRoutingProblem vrp = HAGRIDRouterUtils.createRoutingProblem(carrier, network, netBasedCosts);
        VehicleRoutingAlgorithm algorithm = HAGRIDRouterUtils.configureAlgorithm(vrp, serviceCount);

        VehicleRoutingProblemSolution solution = Solutions.bestOf(algorithm.searchSolutions());
        CarrierPlan newPlan = MatsimJspritFactory.createPlan(carrier, solution);

        LOGGER.info("Routing plan for carrier {}", carrier.getId());
        NetworkRouter.routePlan(newPlan, netBasedCosts);
        carrier.setSelectedPlan(newPlan);
        LOGGER.info("Routing for carrier {} finished. Tour planning plus routing took {} seconds. Carrier has {} services",
                carrier.getId(), (System.currentTimeMillis() - start) / 1000, serviceCount);
    }
}

