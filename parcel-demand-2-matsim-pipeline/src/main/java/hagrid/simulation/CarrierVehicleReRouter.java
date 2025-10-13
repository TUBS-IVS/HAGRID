package hagrid.simulation;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import com.google.inject.Inject;
import com.graphhopper.jsprit.analysis.toolbox.AlgorithmSearchProgressChartListener;
import com.graphhopper.jsprit.analysis.toolbox.StopWatch;
import com.graphhopper.jsprit.core.algorithm.VehicleRoutingAlgorithm;
import com.graphhopper.jsprit.core.algorithm.box.Jsprit;
import com.graphhopper.jsprit.core.algorithm.listener.IterationEndsListener;
import com.graphhopper.jsprit.core.algorithm.listener.VehicleRoutingAlgorithmListeners;
import com.graphhopper.jsprit.core.algorithm.state.StateManager;
import com.graphhopper.jsprit.core.algorithm.state.UpdateEndLocationIfRouteIsOpen;
import com.graphhopper.jsprit.core.algorithm.termination.IterationWithoutImprovementTermination;
import com.graphhopper.jsprit.core.problem.VehicleRoutingProblem;
import com.graphhopper.jsprit.core.problem.constraint.ConstraintManager;
import com.graphhopper.jsprit.core.problem.constraint.ConstraintManager.Priority;
import com.graphhopper.jsprit.core.problem.constraint.HardRouteConstraint;
import com.graphhopper.jsprit.core.problem.constraint.ServiceDeliveriesFirstConstraint;
import com.graphhopper.jsprit.core.problem.constraint.SwitchNotFeasible;
import com.graphhopper.jsprit.core.problem.constraint.VehicleDependentTimeWindowConstraints;
import com.graphhopper.jsprit.core.problem.cost.VehicleRoutingActivityCosts;
import com.graphhopper.jsprit.core.problem.driver.Driver;
import com.graphhopper.jsprit.core.problem.misc.JobInsertionContext;
import com.graphhopper.jsprit.core.problem.solution.VehicleRoutingProblemSolution;
import com.graphhopper.jsprit.core.problem.solution.route.activity.TourActivity;
import com.graphhopper.jsprit.core.problem.vehicle.Vehicle;
import com.graphhopper.jsprit.core.util.Solutions;
// import com.graphhopper.jsprit.io.algorithm.AlgorithmConfig;
// import com.graphhopper.jsprit.io.algorithm.AlgorithmConfigXmlReader;
// import com.graphhopper.jsprit.io.algorithm.VehicleRoutingAlgorithms;

import hagrid.utils.routing.DepartureTimeReScheduler;
import hagrid.utils.routing.UpdateDepartureTimeAndPracticalTimeWindows;
import hagrid.utils.routing.ZoneBasedTransportCosts;

import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Link;

import org.matsim.core.replanning.GenericPlanStrategy;
import org.matsim.core.replanning.GenericPlanStrategyImpl;
import org.matsim.core.replanning.ReplanningContext;
import org.matsim.core.replanning.modules.GenericPlanStrategyModule;
import org.matsim.core.replanning.selectors.BestPlanSelector;
import org.matsim.core.router.util.TravelTime;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierPlan;
import org.matsim.freight.carriers.CarrierVehicleTypes;
import org.matsim.freight.carriers.CarriersUtils;
import org.matsim.freight.carriers.jsprit.MatsimJspritFactory;
import org.matsim.freight.carriers.jsprit.NetworkBasedTransportCosts;
import org.matsim.freight.carriers.jsprit.NetworkRouter;
import org.matsim.freight.carriers.jsprit.VRPTransportCosts;

public final class CarrierVehicleReRouter {

    private static final Logger log = org.apache.logging.log4j.LogManager.getLogger(CarrierVehicleReRouter.class);

    private final Network carNetwork;

    private final CarrierVehicleTypes vehicleTypes;

    private final TravelTime travelTimes;

    private String pathAlgo;

    private static final double MAXROUTEDURATIONHOUR = 7.0;
    private static final int MAXROUTEDURATION = (int) MAXROUTEDURATIONHOUR * 3600;

    private static final int STARTOPTIMIZATION = 25;
    private static final int STOPOPTIMIZATION = 100;

    private static final int MAXREPLANNINGSIZE = 16;

    private final double timeParameter = 0.008;

    private final VehicleRoutingActivityCosts activityCosts;

    private final Boolean isUsingZones;

    private final Map<String, VRPTransportCosts> byModeVRPTransportCosts;

    private final Network bikeNetwork;

    private int jspritIterations;

    public CarrierVehicleReRouter(Network carNetwork, Network bikeNetwork, CarrierVehicleTypes vehicleTypes,
            TravelTime travelTimes,
            Boolean isUsingZones, Map<String, VRPTransportCosts> byModeVRPTransportCosts, int jspritIterations) {
        super();
        this.carNetwork = carNetwork;
        this.bikeNetwork = bikeNetwork;
        this.vehicleTypes = vehicleTypes;
        this.travelTimes = travelTimes;
        this.isUsingZones = isUsingZones;
        this.byModeVRPTransportCosts = byModeVRPTransportCosts;
        this.jspritIterations = jspritIterations;

        this.activityCosts = createVehicleRoutingActivityCosts();

    }

    private VehicleRoutingActivityCosts createVehicleRoutingActivityCosts() {
        // Activity costs
        VehicleRoutingActivityCosts activityCosts = new VehicleRoutingActivityCosts() {

            private final double penalty4missedTws = 5.0;

            @Override
            public double getActivityCost(TourActivity act, double arrivalTime, Driver arg2, Vehicle vehicle) {
                double tooLate = Math.max(0, arrivalTime - act.getTheoreticalLatestOperationStartTime());
                double waiting = Math.max(0, act.getTheoreticalEarliestOperationStartTime() - arrivalTime);
                double service = act.getOperationTime() * vehicle.getType().getVehicleCostParams().perWaitingTimeUnit;
                return penalty4missedTws * tooLate
                        + vehicle.getType().getVehicleCostParams().perWaitingTimeUnit * waiting + service;

            }

            @Override
            public double getActivityDuration(TourActivity act, double arrivalTime, Driver driver, Vehicle vehicle) {
                return Math.max(0, act.getEndTime() - act.getArrTime());
            }
        };
        return activityCosts;
    }

    public GenericPlanStrategy<CarrierPlan, Carrier> createStrategy() {

        GenericPlanStrategyImpl<CarrierPlan, Carrier> replanningStrat = new GenericPlanStrategyImpl<CarrierPlan, Carrier>(
                new BestPlanSelector<CarrierPlan, Carrier>());
        GenericPlanStrategyModule<CarrierPlan> vraModule = new GenericPlanStrategyModule<CarrierPlan>() {

            private boolean startCarrierReplanning = false;

            private HashMap<CarrierPlan, Integer> carrierActivityCounterMap = null;
            // private ForkJoinPool forkJoinPool = null;

            private List<CarrierPlan> plansForOptimization = null;
            private List<CarrierPlan> plansForReOptimization = null;

            private VRPTransportCosts netBasedTransportCosts = null;

            private final AtomicInteger zoneBasedRoutingCount = new AtomicInteger();
            private final AtomicInteger networkRoutingCount = new AtomicInteger();

            record RoutingRunResult(boolean usedZoneRouting, int visitedZones) {}

            @Override
            public void handlePlan(CarrierPlan carrierPlan) {
                Carrier carrier = carrierPlan.getCarrier();

                if (startCarrierReplanning) {
                    if (carrier.getAttributes().getAttribute("hadFirstReplanning") == null) {
                        if (plansForOptimization.size() < MAXREPLANNINGSIZE) {
                            log.info("First Replanning for Carrier " + carrier.getId());

                            plansForOptimization.add(carrierPlan);
                            carrier.getAttributes().putAttribute("hadFirstReplanning", true);
                        }

                    } else {
                        // Random r = new Random();
                        // int result = r.nextInt(100 - 1) + 1;

                        // if (result <= 5) {
                        //     log.info("Simulation Replanning for Carrier " + carrier.getId());
                        //     plansForReOptimization.add(carrierPlan);

                        // }

                    }
                }

            }

            private RoutingRunResult createAndSolveRoutingProblem(CarrierPlan carrierPlan, double iterations, double termination) {

                Carrier carrier = carrierPlan.getCarrier();
                int serviceCount = carrier.getServices().size();
                // Iterationen counter

                if (!(carrier.getAttributes().getAttribute("algoRunTime") == null)) {
                    double algoRunTime = (double) carrier.getAttributes().getAttribute("algoRunTime");

                    // if (algoRunTime > (3600 / 4)) {
                    //     // iterations = Math.ceil(iterations / 2);
                    //     termination = Math.ceil(termination / 2);
                    // }

                    // if (termination < 4) {
                    //     termination = 4;
                    // }

                    // if (iterations < 10) {
                    //     iterations = 10;
                    // }
                }

                VehicleRoutingProblem.Builder vrpBuilder = null;
                VRPTransportCosts transportCosts;
                boolean usingZoneBasedRouting = false;

                if (isUsingZones) {
                    // TODO Different modes?
                    if (CarriersUtils.getCarrierMode(carrier).contains("cargobike")) {
                        vrpBuilder = MatsimJspritFactory.createRoutingProblemBuilder(carrier,
                                bikeNetwork);
                    } else {
                        vrpBuilder = MatsimJspritFactory.createRoutingProblemBuilder(carrier,
                                carNetwork);
                    }
                    transportCosts = byModeVRPTransportCosts.get(CarriersUtils.getCarrierMode(carrier));
                    if (transportCosts == null) {
                        log.warn("No zone-based transport costs registered for mode {}. Falling back to network-based costs for carrier {}.",
                                CarriersUtils.getCarrierMode(carrier), carrier.getId());
                        transportCosts = netBasedTransportCosts;
                    }
                    usingZoneBasedRouting = transportCosts instanceof ZoneBasedTransportCosts;
                } else {
                    vrpBuilder = MatsimJspritFactory.createRoutingProblemBuilder(carrier,
                            carNetwork);
                    transportCosts = netBasedTransportCosts;
                }

                vrpBuilder.setRoutingCost(transportCosts);
                vrpBuilder.setActivityCosts(activityCosts);

                // build the problem
                VehicleRoutingProblem vrp = vrpBuilder.build();

                StateManager stateManager = new StateManager(vrp);
                ConstraintManager constraintManager = new ConstraintManager(vrp, stateManager);

                stateManager.addStateUpdater(new RouteRealStartTimeMemorizer(stateManager, vrp.getTransportCosts()));
                stateManager.updateLoadStates();
                stateManager.updateTimeWindowStates();
                stateManager.updateSkillStates();
                stateManager.addStateUpdater(new UpdateEndLocationIfRouteIsOpen());
                stateManager.addStateUpdater(new OpenRouteStateVerifier());
                stateManager.addStateUpdater(new UpdateDepartureTimeAndPracticalTimeWindows(stateManager,
                        vrp.getTransportCosts(), MAXROUTEDURATION));

                constraintManager.addConstraint(
                        new MaxRouteDurationConstraint(MAXROUTEDURATION, stateManager, vrp.getTransportCosts()),
                        Priority.CRITICAL);
                constraintManager.addConstraint(
                        new TimeWindowConstraintWithDriverTime(stateManager, vrp.getTransportCosts(), MAXROUTEDURATION),
                        Priority.CRITICAL);

                constraintManager.addConstraint(new VehicleDependentTimeWindowConstraints(stateManager,
                        vrp.getTransportCosts(), vrp.getActivityCosts()), ConstraintManager.Priority.HIGH);
                constraintManager.addConstraint(new ServiceDeliveriesFirstConstraint(),
                        ConstraintManager.Priority.CRITICAL);

                constraintManager.addTimeWindowConstraint();
                constraintManager.addLoadConstraint();
                constraintManager.addSkillsConstraint();
                constraintManager.addConstraint(new SwitchNotFeasible(stateManager));

                double radialShare = 0.6; // standard radial share is 0.3
                double randomShare = 0.3; // standard random share is 0.5

                if (serviceCount > 250) { // if problem is huge, take only half the share for replanning
                    radialShare = 0.15;
                    randomShare = 0.25;
                }

                // int radialServicesReplanned = Math.max(1, (int) (serviceCount * radialShare));
                // int randomServicesReplanned = Math.max(1, (int) (serviceCount * randomShare));

                VehicleRoutingAlgorithm algorithm = Jsprit.Builder.newInstance(vrp)
                        .setStateAndConstraintManager(stateManager, constraintManager)
                        // .setProperty(Jsprit.Parameter.THREADS, String.valueOf(jspritThreads))
                        // .setProperty(Jsprit.Parameter.RADIAL_MIN_SHARE, String.valueOf(radialServicesReplanned))
                        // .setProperty(Jsprit.Parameter.RADIAL_MAX_SHARE, String.valueOf(radialServicesReplanned))
                        // .setProperty(Jsprit.Parameter.RANDOM_BEST_MIN_SHARE, String.valueOf(randomServicesReplanned))
                        // .setProperty(Jsprit.Parameter.RANDOM_BEST_MAX_SHARE, String.valueOf(randomServicesReplanned))
                        .buildAlgorithm();

                AtomicInteger iterationCounter = new AtomicInteger(0);
                algorithm.addListener(
                        (IterationEndsListener) (iteration, problem, solutions) -> iterationCounter.incrementAndGet());

                // VehicleRoutingAlgorithm algorithm =
                // VehicleRoutingAlgorithms.readAndCreateAlgorithm(vrp,
                // ALGORITHM_FILE, stateManager );
                algorithm.setMaxIterations((int) iterations);
                algorithm.addTerminationCriterion(new IterationWithoutImprovementTermination((int) termination));
                algorithm.getAlgorithmListeners().addListener(new StopWatch(),
                        VehicleRoutingAlgorithmListeners.Priority.HIGH);

                algorithm.addListener(new DepartureTimeReScheduler());

                // String basePath =
                // "C:/Users/bienzeisler/HAGRID/HAGRID/phd/sim-output/basecase/jsprit";
                // File outputDir = createUniqueRunDirectory(basePath);

                // String chartFile = new File(outputDir, carrier.getId() +
                // ".png").getAbsolutePath();
                // System.out.println("Generated chart file: " + chartFile);

                // algorithm.getAlgorithmListeners().addListener(
                // new AlgorithmSearchProgressChartListener(chartFile),
                // VehicleRoutingAlgorithmListeners.Priority.HIGH
                // );

                VehicleRoutingProblemSolution solution = Solutions.bestOf(algorithm.searchSolutions());
                CarriersUtils.setJspritIterations(carrier, iterationCounter.get());
                //
                CarrierPlan plan = MatsimJspritFactory.createPlan(solution);
                NetworkRouter.routePlan(plan, transportCosts);

                if (transportCosts instanceof ZoneBasedTransportCosts zoneCosts) {
                    String mode = CarriersUtils.getCarrierMode(carrier);
                    zoneCosts.logCacheSummary(String.format("Carrier %s (%s) reroute", carrier.getId(), mode));
                }

                int visitedZones = usingZoneBasedRouting ? countVisitedZones(carrier, plan) : -1;

                carrierPlan.getScheduledTours().clear();
                carrierPlan.getScheduledTours().addAll(plan.getScheduledTours());

                vrp = null;
                algorithm = null;
                stateManager = null;
                constraintManager = null;
                plan = null;
                return new RoutingRunResult(usingZoneBasedRouting, visitedZones);
            }

            @Override
            public void prepareReplanning(ReplanningContext replanningContext) {

                carrierActivityCounterMap = new HashMap<>();
                // forkJoinPool = new ForkJoinPool(Runtime.getRuntime().availableProcessors());
                plansForOptimization = new ArrayList<>();
                plansForReOptimization = new ArrayList<>();

                if (isUsingZones) {
                    // // Netbased transport costs
                    // ZoneBasedTransportCosts.Builder tpCostsBuilder =
                    // ZoneBasedTransportCosts.Builder
                    // .newInstance(network, vehicleTypes.getVehicleTypes().values());
                    // tpCostsBuilder.setTravelTime(travelTimes);
                    // tpCostsBuilder.setTimeSliceWidth(1800);
                    //// netBasedTransportCosts = tpCostsBuilder.build();
                    // ByModeVRPTransportCosts =
                    // vrpTransportCostsFactory.createVRPTransportCostsWithModeCongestedTravelTime();

                } else {
                    // Netbased transport costs
                    NetworkBasedTransportCosts.Builder tpCostsBuilder = NetworkBasedTransportCosts.Builder
                            .newInstance(carNetwork, vehicleTypes.getVehicleTypes().values());
                    tpCostsBuilder.setTravelTime(travelTimes);
                    tpCostsBuilder.setTimeSliceWidth(1800);
                    netBasedTransportCosts = tpCostsBuilder.build();
                }

                if (replanningContext.getIteration() >= STARTOPTIMIZATION
                        && replanningContext.getIteration() <= STOPOPTIMIZATION) {
                    startCarrierReplanning = true;
                }

            }

            @Override
            public void finishReplanning() {
                Collections.shuffle(plansForReOptimization);

                for (CarrierPlan carrierPlan : plansForReOptimization) {
                    if (plansForOptimization.size() < MAXREPLANNINGSIZE) {
                        plansForOptimization.add(carrierPlan);
                    }
                }


                List<CarrierPlan> tempList = plansForOptimization;
                int totalCarriers = carrierActivityCounterMap != null ? carrierActivityCounterMap.size() : tempList.size();
                AtomicInteger globalProgress = new AtomicInteger(0);

                ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

                List<CompletableFuture<Void>> futures = tempList.stream()
                        .map(carrierPlan -> CompletableFuture.runAsync(() -> {
                            int currentNumber = globalProgress.incrementAndGet();
                            Carrier carrier = carrierPlan.getCarrier();
                            int serviceCount = carrier.getServices().size();

                            log.info("[Routing {} / {}] START: Carrier {} ({} services)", currentNumber, tempList.size(), carrier.getId(), serviceCount);

                            long start = System.currentTimeMillis();

                            int noImprovementThreshold;
                            if (serviceCount > 250) {
                                // noImprovementThreshold = jspritIterations / 4;
                                noImprovementThreshold = 50;
                            } else {
                                // noImprovementThreshold = jspritIterations / 2;
                                noImprovementThreshold = 75;
                            }

                            RoutingRunResult runResult = createAndSolveRoutingProblem(carrierPlan, jspritIterations, noImprovementThreshold);
                            boolean usedZoneRouting = runResult.usedZoneRouting();
                            int visitedZones = runResult.visitedZones();

                            long algoRunTime = (System.currentTimeMillis() - start) / 1000;

                            if (usedZoneRouting) {
                                zoneBasedRoutingCount.incrementAndGet();
                            } else {
                                networkRoutingCount.incrementAndGet();
                            }

                String providerLabel = usedZoneRouting
                    ? String.format("zone-based (zones=%d)", Math.max(0, visitedZones))
                    : "network-based";

                log.info("[Routing {} / {}] DONE: Carrier {} | {} services | {}min (Tour planning + routing) | provider={}",
                    currentNumber, tempList.size(), carrier.getId(), serviceCount, algoRunTime/60,
                    providerLabel);

                            carrier.getAttributes().putAttribute("algoRunTime", (double) algoRunTime);
                            CarriersUtils.setJspritComputationTime(carrier, algoRunTime);

                        }, executor))
                        .collect(Collectors.toList());

                try {
                    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get();
                } catch (InterruptedException | ExecutionException e) {
                    log.error("Error during parallel carrier routing", e);
                    throw new RuntimeException("Parallel routing failed", e);
                } finally {
                    int totalZoneBased = zoneBasedRoutingCount.get();
                    int totalNetworkBased = networkRoutingCount.get();
                    if (totalZoneBased + totalNetworkBased > 0) {
                        log.info("Carrier routing provider usage summary: {} zone-based | {} network-based",
                                totalZoneBased, totalNetworkBased);
                    }
                    zoneBasedRoutingCount.set(0);
                    networkRoutingCount.set(0);

                    // ✅ Executor ordentlich beenden
                    executor.shutdown();
                    try {
                        if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                            log.warn("Executor did not terminate in 60 seconds. Forcing shutdown...");
                            List<Runnable> droppedTasks = executor.shutdownNow();
                            log.warn("{} tasks were forcefully terminated.", droppedTasks.size());
                        }
                    } catch (InterruptedException e) {
                        log.error("Interrupted while waiting for executor termination", e);
                        executor.shutdownNow();
                        Thread.currentThread().interrupt();
                    }

                    // Optional: CompletableFutures-Referenz nullen
                    futures.clear(); // clears references from list
                    // futures = null; // falls final erlaubt

                    // 🔍 Optional: Log Heap Info (nur für Debugging)
                    Runtime runtime = Runtime.getRuntime();
                    long usedMemoryMB = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
                    long maxMemoryMB = runtime.maxMemory() / (1024 * 1024);
                    log.info("Heap usage after routing: {} MB used of {} MB max", usedMemoryMB, maxMemoryMB);

                    // 
                    System.gc();
                }
            }

            private int countVisitedZones(Carrier carrier, CarrierPlan plan) {
                Network networkForCarrier = resolveNetworkForCarrier(carrier);
                if (networkForCarrier == null) {
                    return 0;
                }

                HashSet<Integer> uniqueZones = new HashSet<>();

                plan.getScheduledTours().forEach(scheduledTour ->
                        scheduledTour.getTour().getTourElements().forEach(element -> {
                            if (element instanceof TourActivity activity && activity.getLocation() != null) {
                                String locationId = activity.getLocation().getId();
                                if (locationId != null) {
                                    Link link = networkForCarrier.getLinks().get(Id.createLinkId(locationId));
                                    Integer zone = extractZoneIdentifier(link);
                                    if (zone != null) {
                                        uniqueZones.add(zone);
                                    }
                                }
                            }
                        }));

                return uniqueZones.size();
            }

            private Network resolveNetworkForCarrier(Carrier carrier) {
                String mode = CarriersUtils.getCarrierMode(carrier);
                if (mode != null && mode.contains("cargobike")) {
                    return bikeNetwork;
                }
                return carNetwork;
            }

            private Integer extractZoneIdentifier(Link link) {
                if (link == null) {
                    return null;
                }
                Object zoneAttr = link.getAttributes().getAttribute("zone");
                if (zoneAttr instanceof Number number) {
                    return number.intValue();
                }
                if (zoneAttr instanceof String stringValue) {
                    try {
                        return Integer.parseInt(stringValue.trim());
                    } catch (NumberFormatException ignored) {
                        return null;
                    }
                }
                return null;
            }

        };

        replanningStrat.addStrategyModule(vraModule);
        return replanningStrat;
    }

    private String getUniqueFileName(String baseName, String extension) {
        int counter = 0;
        String fileName = baseName + extension;
        File file = new File(fileName);

        while (file.exists()) {
            fileName = baseName + "_" + counter + extension;
            file = new File(fileName);
            counter++;
        }

        return fileName;
    }

    public static File createUniqueRunDirectory(String basePath) {
        int runIndex = 1;
        File runDir;

        do {
            runDir = new File(basePath, "run" + runIndex);
            runIndex++;
        } while (runDir.exists());

        if (runDir.mkdirs()) {
            System.out.println("Created directory: " + runDir.getAbsolutePath());
        } else {
            throw new RuntimeException("Could not create directory: " + runDir.getAbsolutePath());
        }

        return runDir;
    }

}
