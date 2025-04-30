package hagrid.demand;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.router.RouterUtils;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierPlan;
import org.matsim.freight.carriers.Carriers;
import org.matsim.freight.carriers.CarriersUtils;
import org.matsim.freight.carriers.jsprit.MatsimJspritFactory;
import org.matsim.freight.carriers.jsprit.NetworkBasedTransportCosts;
import org.matsim.freight.carriers.jsprit.NetworkRouter;
import org.matsim.freight.carriers.jsprit.VRPTransportCosts;

import com.graphhopper.jsprit.analysis.toolbox.AlgorithmSearchProgressChartListener;
import com.graphhopper.jsprit.core.algorithm.VehicleRoutingAlgorithm;
import com.graphhopper.jsprit.core.algorithm.listener.IterationEndsListener;
import com.graphhopper.jsprit.core.algorithm.listener.VehicleRoutingAlgorithmListeners;
import com.graphhopper.jsprit.core.problem.VehicleRoutingProblem;
import com.graphhopper.jsprit.core.problem.solution.VehicleRoutingProblemSolution;
import com.graphhopper.jsprit.core.util.Solutions;

import hagrid.simulation.CarrierVehicleReRouter;
import hagrid.utils.routing.HAGRIDRouterUtils;
import hagrid.utils.routing.JspritCarrierTask;
import hagrid.utils.routing.JspritTreadPoolExecutor;
import hagrid.utils.routing.ThreadingType;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class Router {

    private static final Logger LOGGER = LogManager.getLogger(Router.class);

    private static final String JSPRIT_ITERATIONS = "jspritIterations";

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
    public void routeCarriers(Carriers carriers, final VRPTransportCosts netBasedCosts, Network network,
            String carrierType) {
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
                    ExecutorService completableFutureExecutor = Executors.newFixedThreadPool(
                            Runtime.getRuntime().availableProcessors());

                    AtomicInteger finishedCounter = new AtomicInteger();

                    List<CompletableFuture<Void>> completableFutures = sortedCarriers.stream()
                            .map(carrier -> CompletableFuture.runAsync(() -> {
                                long start = System.currentTimeMillis();
                                try {
                                    routeCarrier(carrier, netBasedCosts, network, progress, sortedCarriers.size());
                                } catch (Exception ex) {
                                    LOGGER.error("Routing failed for carrier {}: {}", carrier.getId(),
                                            ex.getMessage(), ex);
                                } finally {
                                    routedTimes.add(System.currentTimeMillis());
                                    int done = finishedCounter.incrementAndGet();
                                    LOGGER.info("Finished routing for carrier {} ({} / {}) in {}s",
                                            carrier.getId(), done, sortedCarriers.size(),
                                            (System.currentTimeMillis() - start) / 1000.0);
                                }
                            }, completableFutureExecutor))
                            .collect(Collectors.toList());

                    try {
                        // Ohne Timeout – einfach warten, bis alle fertig sind
                        CompletableFuture.allOf(completableFutures.toArray(new CompletableFuture[0]))
                                .get();
                    } catch (Exception e) {
                        LOGGER.error("Error during routing completion", e);
                    } finally {
                        completableFutureExecutor.shutdown();
                        try {
                            if (!completableFutureExecutor.awaitTermination(60, TimeUnit.SECONDS)) {
                                LOGGER.warn("Executor did not shut down gracefully – forcing...");
                                List<Runnable> dropped = completableFutureExecutor.shutdownNow();
                                LOGGER.warn("{} tasks were forcefully terminated.", dropped.size());
                            }
                        } catch (InterruptedException e) {
                            LOGGER.error("Thread interrupted during executor shutdown", e);
                            Thread.currentThread().interrupt();
                        }
                    }
                    break;
                case SINGLE_THREAD:
                    // Use single thread for sequential processing
                    sortedCarriers.forEach(carrier -> {
                        routeCarrier(carrier, netBasedCosts, network, progress, sortedCarriers.size());
                        routedTimes.add(System.currentTimeMillis());
                    });
                    break;
                case REACTOR:
                    // Use Reactor for parallel processing
                    Flux.fromIterable(sortedCarriers)
                            .parallel()
                            .runOn(Schedulers.parallel())
                            .doOnNext(carrier -> {
                                routeCarrier(carrier, netBasedCosts, network, progress, sortedCarriers.size());
                                routedTimes.add(System.currentTimeMillis());
                            })
                            .sequential()
                            .blockLast();
                    break;
            }
        } catch (InterruptedException | ExecutionException e) {
            LOGGER.error("Error in parallel routing execution", e);
        }

        long endTime = System.currentTimeMillis();
        LOGGER.info("Finished routing all carriers using {} in {} seconds.", threadingType,
                (endTime - startTime) / 1000);

        // Plotting the runtime
        // HAGRIDRouterUtils.plotCumulativeRoutingRuntime(startTime, endTime,
        // routedTimes, threadingType.toString(), carrierType);
        // HAGRIDRouterUtils.plotIndividualRoutingRuntime(startTime, routedTimes,
        // threadingType.toString(), carrierType);
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

        // Iterationen counter
        AtomicInteger iterationCounter = new AtomicInteger(0);
        algorithm.addListener(
                (IterationEndsListener) (iteration, problem, solutions) -> iterationCounter.incrementAndGet());

        // // Make sure output dir exists
        // File outputDir = new File("phd/output/jsprit");
        // if (!outputDir.exists() && !outputDir.mkdirs()) {
        // throw new RuntimeException("Could not create output directory: " +
        // outputDir.getAbsolutePath());
        // }
        // // Build output file name: carrierId + "_jsprit.png"
        // String chartFile = new File(outputDir, carrier.getId() +
        // "_jsprit.png").getAbsolutePath();

        // algorithm.getAlgorithmListeners().addListener(
        // new AlgorithmSearchProgressChartListener(chartFile),
        // VehicleRoutingAlgorithmListeners.Priority.HIGH);

        algorithm.addListener(new IterationEndsListener() {
            @Override
            public void informIterationEnds(int iteration,
                    VehicleRoutingProblem problem,
                    Collection<VehicleRoutingProblemSolution> solutions) {
                if (solutions.isEmpty()) {
                    LOGGER.warn("Carrier {}: Iteration {} no solution found yet.", carrier.getId(), iteration);
                    return;
                }

                VehicleRoutingProblemSolution best = Solutions.bestOf(solutions);
                LOGGER.info(
                        "Carrier {}: Iteration {} finished. " +
                                "Best Cost: {}, " +
                                "Tours: {}, " +
                                "Unassigned Jobs: {}, " +
                                "Vehicles Used: {}, " +
                                "Total Transport Time: {} s",
                        carrier.getId(),
                        iteration,
                        String.format("%.2f", best.getCost()),
                        best.getRoutes().size(),
                        best.getUnassignedJobs().size(),
                        best.getRoutes().stream().map(route -> route.getVehicle().getId()).distinct().count(),
                        getTotalTransportTime(best));
            }
        });

        VehicleRoutingProblemSolution solution = Solutions.bestOf(algorithm.searchSolutions());

        LOGGER.info(
                "JSPRIT Solution for carrier {} found. Search took {} seconds. Carrier has {} services",
                carrier.getId(), (System.currentTimeMillis() - start) / 1000, serviceCount);

        CarriersUtils.setJspritIterations(carrier, iterationCounter.get());

        CarrierPlan newPlan = MatsimJspritFactory.createPlan(solution);

        LOGGER.info("Routing plan for carrier {}", carrier.getId());
        NetworkRouter.routePlan(newPlan, netBasedCosts);
        carrier.addPlan(newPlan);
        carrier.setSelectedPlan(newPlan);

        double timeForPlanningAndRouting = (System.currentTimeMillis() - start) / 1000;
        CarriersUtils.setJspritComputationTime(carrier, timeForPlanningAndRouting);
        LOGGER.info(
                "Routing for carrier {} finished. Tour planning plus routing took {} seconds. Carrier has {} services",
                carrier.getId(), (System.currentTimeMillis() - start) / 1000, serviceCount);
    }

    private double getTotalTransportTime(VehicleRoutingProblemSolution solution) {
        return solution.getRoutes().stream()
                .mapToDouble(route -> route.getEnd().getArrTime() - route.getStart().getEndTime())
                .sum();
    }

}
