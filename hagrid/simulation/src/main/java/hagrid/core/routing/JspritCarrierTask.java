package hagrid.core.routing;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.network.Network;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarriersUtils;
import org.matsim.freight.carriers.jsprit.VRPTransportCosts;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Initial-search adapter. A group collector receives detached results; standalone calls publish only validated plans. */
@Deprecated(forRemoval = false)
public class JspritCarrierTask implements PrioritizedRunnable {
    private static final Logger LOGGER = LogManager.getLogger(JspritCarrierTask.class);
    private final Carrier carrier;
    private final VRPTransportCosts costs;
    private final AtomicInteger started;
    private final int taskCount;
    private final double uTurnPenalty;
    private final int iterations;
    private final Consumer<CarrierOptimizationService.Result> collector;

    public JspritCarrierTask(Carrier carrier, VRPTransportCosts costs, AtomicInteger started,
                             int taskCount, Network network, double uTurnPenalty) {
        this(carrier,costs,started,taskCount,network,uTurnPenalty,1,null);
    }
    public JspritCarrierTask(Carrier carrier, VRPTransportCosts costs, AtomicInteger started,
                             int taskCount, Network network) {
        this(carrier,costs,started,taskCount,network,0);
    }
    public JspritCarrierTask(Carrier carrier, VRPTransportCosts costs, AtomicInteger started,
                             int taskCount, Network network, double uTurnPenalty, int iterations,
                             Consumer<CarrierOptimizationService.Result> collector) {
        this.carrier=carrier;this.costs=costs;this.started=started;this.taskCount=taskCount;
        this.uTurnPenalty=uTurnPenalty;this.iterations=iterations;this.collector=collector;
    }
    public int getPriority() { return carrier.getServices().size(); }
    public int getServiceCount() { return carrier.getServices().size(); }
    public String getCarrierId() { return carrier.getId().toString(); }

    @Override public void run() {
        LOGGER.info("Initial search {} of {}: {}",started.incrementAndGet(),taskCount,carrier.getId());
        var result=new CarrierOptimizationService().optimizeInitial(carrier,costs,iterations,
                hagrid.core.config.RoutingDefaults.SOLVER_MAX_ROUTE_DURATION_SECONDS,uTurnPenalty);
        if(!result.diagnostics().isEmpty()) {
            throw new IllegalStateException("Invalid initial result for "+carrier.getId()+": "+result.diagnostics());
        }
        if(collector!=null) {
            collector.accept(result);
        } else {
            carrier.addPlan(result.plan());carrier.setSelectedPlan(result.plan());
            CarriersUtils.setJspritIterations(carrier,result.iterations());
            CarriersUtils.setJspritComputationTime(carrier,result.metrics().totalSeconds);
            HAGRIDRouterUtils.recordUnassignedJobs(carrier,result.unassignedJobIds());
            HAGRIDRouterUtils.recordUTurnPenalty(carrier,uTurnPenalty);
        }
    }
}
