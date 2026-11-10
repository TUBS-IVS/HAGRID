package hagrid.hannover.pipeline;

import com.google.inject.Injector;
import hagrid.core.routing.ThreadingType;
import hagrid.hannover.demand.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Scenario;

import java.util.Objects;

/** Runs required demand stages in order; success is published only after every stage succeeds. */
public final class PipelineExecutor {
    private static final Logger LOGGER = LogManager.getLogger(PipelineExecutor.class);
    private final Injector injector;

    public PipelineExecutor(Injector injector) { this.injector = Objects.requireNonNull(injector); }

    public void executeAll() {
        runNetworkProcessing();
        runLogisticsDataProcessing();
        runDemandProcessing();
        runDeliveryGeneration();
        runCarrierGeneration();
        runSupplyGeneration();
    }

    public void executeAll(boolean applyServiceSimplifier) {
        executeAll();
        if (applyServiceSimplifier) runCarrierServiceMerger(true);
    }

    public void executeAll(boolean applyServiceSimplifier, boolean runRouting) {
        var timing = new PipelineTiming();
        timing.startPipeline();
        timed(timing, "Network Processing", this::runNetworkProcessing);
        timed(timing, "Logistics Data", this::runLogisticsDataProcessing);
        timed(timing, "Demand Processing", this::runDemandProcessing);
        timed(timing, "Delivery Generation", this::runDeliveryGeneration);
        timed(timing, "Carrier Generation", this::runCarrierGeneration);
        timed(timing, "Supply Generation", this::runSupplyGeneration);
        if (applyServiceSimplifier) timed(timing, "Service Merge", () -> runCarrierServiceMerger(true));
        if (runRouting) timed(timing, "Routing", () -> runRouter(ThreadingType.COMPLETABLE_FUTURE));
        timing.endPipeline();
        stage("Pipeline Timing", () -> injector.getInstance(Scenario.class).addScenarioElement("pipelineTiming", timing));
        LOGGER.info("Pipeline completed in {} — {} modules.",
                PipelineTiming.formatDuration(timing.getTotalMs()), timing.getModuleDurations().size());
    }

    private void timed(PipelineTiming timing, String name, Runnable operation) {
        long started = System.nanoTime();
        operation.run();
        timing.recordModule(name, (System.nanoTime() - started) / 1_000_000);
    }

    private void run(String name, Class<? extends Runnable> type) {
        stage(name, () -> injector.getInstance(type).run());
    }

    private void stage(String name, Runnable operation) {
        LOGGER.info("Starting {}...", name);
        try {
            operation.run();
        } catch (PipelineStepException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new PipelineStepException(name, failure);
        }
        LOGGER.info("{} completed.", name);
    }

    public void runNetworkProcessing() { run("Network Processing", NetworkProcessor.class); }
    public void runLogisticsDataProcessing() { run("Logistics Data", LogisticsDataProcessor.class); }
    public void runDemandProcessing() { run("Demand Processing", DemandProcessor.class); }
    public void runDeliveryGeneration() { run("Delivery Generation", DeliveryGenerator.class); }
    public void runCarrierGeneration() { run("Carrier Generation", CarrierGenerator.class); }
    public void runSupplyGeneration() { run("Supply Generation", SupplyCarrierGenerator.class); }

    public void runCarrierServiceMerger(boolean fullMerge) {
        stage("Service Merge", () -> {
            var merger = injector.getInstance(CarrierServiceMerger.class);
            merger.setFullMerge(fullMerge);
            merger.run();
        });
    }

    public void runRouter(ThreadingType threadingType) {
        stage("Routing", () -> {
            var router = injector.getInstance(CarrierRouter.class);
            router.setThreadingType(threadingType);
            router.run();
        });
    }
}
