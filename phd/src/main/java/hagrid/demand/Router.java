package hagrid.demand;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.router.RouterUtils;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierPlan;
import org.matsim.freight.carriers.Carriers;
import org.matsim.freight.carriers.jsprit.MatsimJspritFactory;
import org.matsim.freight.carriers.jsprit.NetworkBasedTransportCosts;
import org.matsim.freight.carriers.jsprit.NetworkRouter;
import org.matsim.freight.carriers.jsprit.VRPTransportCosts;

import com.graphhopper.jsprit.core.algorithm.VehicleRoutingAlgorithm;
import com.graphhopper.jsprit.core.problem.VehicleRoutingProblem;
import com.graphhopper.jsprit.core.problem.solution.VehicleRoutingProblemSolution;
import com.graphhopper.jsprit.core.util.Solutions;

import hagrid.utils.routing.HAGRIDRouterUtils;
import hagrid.utils.routing.JspritCarrierTask;
import hagrid.utils.routing.JspritTreadPoolExecutor;
import hagrid.utils.routing.ThreadingType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class Router {

    private static final Logger LOGGER = LogManager.getLogger(Router.class);

    private final ThreadingType threadingType;

    public Router(ThreadingType threadingType) {
        this.threadingType = threadingType;
    }

    /**
     * Routes the carriers using the specified threading type.
     *
     * @param carriers      The carriers to be routed.
     * @param netBasedCosts The network-based transport costs.
     * @param network       The network.
     */
    public void routeCarriers(Carriers carriers, final VRPTransportCosts netBasedCosts, Network network , String carrierType) {
        LOGGER.info("Starting routing of carriers using {}...", threadingType);

        // Sort carriers by the number of services and shipments in descending order
        List<Carrier> sortedCarriers = carriers.getCarriers().values().stream()
                .sorted(Comparator
                        .comparingInt((Carrier carrier) -> carrier.getServices().size() + carrier.getShipments().size())
                        .reversed())
                .collect(Collectors.toList());

        AtomicInteger progress = new AtomicInteger();
        long startTime = System.currentTimeMillis();
        List<Long> routedTimes = new ArrayList<>();

        try {
            switch (threadingType) {
                case FORK_JOIN_POOL:
                    // Use ForkJoinPool for parallel processing with try-with-resources to ensure
                    // proper closure
                    try (ForkJoinPool forkJoinPool = new ForkJoinPool(Runtime.getRuntime().availableProcessors())) {
                        forkJoinPool.submit(() -> sortedCarriers.parallelStream()
                                .forEach(carrier -> {
                                    routeCarrier(carrier, netBasedCosts, network, progress, sortedCarriers.size());
                                    routedTimes.add(System.currentTimeMillis());
                                })).get();
                    }
                    break;
                case MAT_SIM_THREAD_POOL:
                    // Use MATSim's custom thread pool executor for parallel processing
                    ThreadPoolExecutor executor = new JspritTreadPoolExecutor(new PriorityBlockingQueue<>(),
                            Runtime.getRuntime().availableProcessors());
                    List<Future<?>> futures = sortedCarriers.stream()
                            .map(carrier -> new JspritCarrierTask(carrier, netBasedCosts, progress,
                                    sortedCarriers.size(), network))
                            .map(executor::submit)
                            .collect(Collectors.toList());

                    for (Future<?> future : futures) {
                        future.get();
                        routedTimes.add(System.currentTimeMillis());
                    }
                    executor.shutdown();
                    break;
                case COMPLETABLE_FUTURE:
                    // Use CompletableFuture for parallel processing
                    ExecutorService completableFutureExecutor = Executors
                            .newFixedThreadPool(Runtime.getRuntime().availableProcessors());
                    List<CompletableFuture<Void>> completableFutures = sortedCarriers.stream()
                            .map(carrier -> CompletableFuture.runAsync(() -> {
                                routeCarrier(carrier, netBasedCosts, network, progress, sortedCarriers.size());
                                routedTimes.add(System.currentTimeMillis());
                            }, completableFutureExecutor))
                            .collect(Collectors.toList());

                    CompletableFuture.allOf(completableFutures.toArray(new CompletableFuture[0])).get();
                    completableFutureExecutor.shutdown();
                    break;
            }
        } catch (InterruptedException | ExecutionException e) {
            LOGGER.error("Error in parallel routing execution", e);
        }

        long endTime = System.currentTimeMillis();
        LOGGER.info("Finished routing all carriers using {} in {} seconds.", threadingType,
                (endTime - startTime) / 1000);

        // Plotting the runtime
        HAGRIDRouterUtils.plotRoutingRuntime(startTime, endTime, routedTimes, threadingType.toString());
    }

    /**
     * Routes a single carrier.
     *
     * @param carrier       The carrier to be routed.
     * @param netBasedCosts The network-based transport costs.
     * @param network       The network.
     * @param progress      The current progress counter.
     * @param totalCarriers The total number of carriers.
     */
    private void routeCarrier(Carrier carrier, VRPTransportCosts netBasedCosts, Network network,
            AtomicInteger progress, int totalCarriers) {
        LOGGER.info("ROUTING CARRIER {} OUT OF {} TOTAL CARRIERS", progress.incrementAndGet(), totalCarriers);

        double start = System.currentTimeMillis();
        int serviceCount = carrier.getServices().size();

        VehicleRoutingProblem vrp = HAGRIDRouterUtils.createRoutingProblem(carrier, network, netBasedCosts);
        VehicleRoutingAlgorithm algorithm = HAGRIDRouterUtils.configureAlgorithm(vrp, serviceCount);

        VehicleRoutingProblemSolution solution = Solutions.bestOf(algorithm.searchSolutions());
        CarrierPlan newPlan = MatsimJspritFactory.createPlan(carrier, solution);

        LOGGER.info("Routing plan for carrier {}", carrier.getId());
        NetworkRouter.routePlan(newPlan, netBasedCosts);
        carrier.setSelectedPlan(newPlan);
        LOGGER.info(
                "Routing for carrier {} finished. Tour planning plus routing took {} seconds. Carrier has {} services",
                carrier.getId(), (System.currentTimeMillis() - start) / 1000, serviceCount);
    }

}
