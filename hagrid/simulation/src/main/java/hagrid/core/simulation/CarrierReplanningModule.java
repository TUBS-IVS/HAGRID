package hagrid.core.simulation;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.core.replanning.ReplanningContext;
import org.matsim.core.replanning.modules.GenericPlanStrategyModule;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierPlan;
import org.matsim.freight.carriers.CarriersUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;
import hagrid.core.routing.CarrierBatchExecutor;
import hagrid.core.routing.HAGRIDRouterUtils;






import java.util.concurrent.atomic.AtomicInteger;

/** Selects one legacy carrier group and publishes completed candidates on the calling controller thread. */
final class CarrierReplanningModule implements GenericPlanStrategyModule<CarrierPlan> {
    private static final Logger log = LogManager.getLogger(CarrierReplanningModule.class);
    private static final int STOP_ITERATION = 100;
    private static final String COMPLETED_ATTRIBUTE = "hadFirstReplanning";

    private final CarrierRoutingCosts costs;
    private final LegacyCarrierPlanOptimizer optimizer;
    private final int startIteration;
    private final int carriersPerGroup;
    private final Map<Id<Carrier>, CarrierPlan> queuedPlans = new LinkedHashMap<>();
    private boolean active;
    private final Supplier<CarrierBatchExecutor> executorFactory;
    private final ToLongFunction<Carrier> reservedBytes;

    private record Completed(CarrierPlan target, LegacyCarrierPlanOptimizer.Candidate candidate, double seconds) {}

    CarrierReplanningModule(CarrierRoutingCosts costs,LegacyCarrierPlanOptimizer optimizer,
                            int startIteration,int carriersPerGroup){
        this(costs,optimizer,startIteration,carriersPerGroup,CarrierBatchExecutor::new,carrier->0L);
    }
    /** Owns each factory-created executor for one legacy batch: native strategy has no shutdown hook. */
    CarrierReplanningModule(CarrierRoutingCosts costs,LegacyCarrierPlanOptimizer optimizer,
                            int startIteration,int carriersPerGroup,Supplier<CarrierBatchExecutor> executorFactory,
                            ToLongFunction<Carrier> reservedBytes){
        this.costs=Objects.requireNonNull(costs);this.optimizer=Objects.requireNonNull(optimizer);
        this.startIteration=startIteration;this.carriersPerGroup=carriersPerGroup;
        this.executorFactory=Objects.requireNonNull(executorFactory);this.reservedBytes=Objects.requireNonNull(reservedBytes);
    }
    @Override
    public void prepareReplanning(ReplanningContext context) {
        queuedPlans.clear();
        costs.prepare();
        active = context.getIteration() >= startIteration && context.getIteration() <= STOP_ITERATION;
    }

    @Override
    public void handlePlan(CarrierPlan plan) {
        if (!active) return;
        var carrier = plan.getCarrier();
        if (carrier.getAttributes().getAttribute(COMPLETED_ATTRIBUTE) == null
                && (queuedPlans.containsKey(carrier.getId()) || queuedPlans.size() < carriersPerGroup)) {
            // MATSim may hand us another selected copy of the same carrier. Solve it once
            // and publish to the latest working copy, preserving the original queue position.
            queuedPlans.put(carrier.getId(), plan);
        }
    }

    @Override
    public void finishReplanning() {
        if(queuedPlans.isEmpty())return;
        var batch=List.copyOf(queuedPlans.values());
        var inputs=new LinkedHashMap<String,CarrierPlan>();
        var descriptors=new ArrayList<CarrierBatchExecutor.Descriptor>();
        var completed=new ArrayList<Completed>();
        var progress=new AtomicInteger();
        try{
            for(var plan:batch){
                var carrier=plan.getCarrier();var id=carrier.getId().toString();
                inputs.put(id,plan);
                descriptors.add(new CarrierBatchExecutor.Descriptor(id,carrier.getServices().size(),
                        carrier.getShipments().size(),carrier.getCarrierCapabilities().getVehicleTypes().size(),
                        optimizer.seed(),reservedBytes.applyAsLong(carrier)));
            }
            // Default remains serial without measured byte estimates. All paths use identical admission/drain.
            try(var executor=Objects.requireNonNull(executorFactory.get())){
                executor.runStaged(optimizer.maxActiveSolvers(),descriptors,
                        d->compute(inputs.get(d.carrierId()),progress.incrementAndGet(),batch.size()),
                        completed::add,()->{
                    if(Thread.currentThread().isInterrupted())
                        throw new java.util.concurrent.CancellationException("Legacy replanning interrupted before publication");
                    completed.forEach(CarrierReplanningModule::publish);
                });
            }
            log.info("Carrier routing completed with exact network endpoints for {} carriers",batch.size());
        }finally{
            queuedPlans.clear();
        }
    }
    private Completed compute(CarrierPlan target, int number, int total) {
        var carrier = target.getCarrier();
        log.info("[Routing {} / {}] START: Carrier {} ({} services)", number, total,
                carrier.getId(), carrier.getServices().size());
        long start = System.nanoTime();
        var candidate = optimizer.optimize(carrier);
        double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
        log.info("[Routing {} / {}] DONE: Carrier {} | {} seconds | zones={}", number, total,
                carrier.getId(), seconds, candidate.visitedZones());
        return new Completed(target, candidate, seconds);
    }

    private static void publish(Completed result) {
        var carrier = result.target().getCarrier();
        result.target().getScheduledTours().clear();
        result.target().getScheduledTours().addAll(result.candidate().plan().getScheduledTours());
        CarriersUtils.setJspritIterations(carrier, result.candidate().iterations());
        carrier.getAttributes().putAttribute("algoRunTime", result.seconds());
        CarriersUtils.setJspritComputationTime(carrier, result.seconds());
        carrier.getAttributes().putAttribute(COMPLETED_ATTRIBUTE, true);
    }

}
