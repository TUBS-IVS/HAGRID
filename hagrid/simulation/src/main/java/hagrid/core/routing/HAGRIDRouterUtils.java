package hagrid.core.routing;

import com.graphhopper.jsprit.core.algorithm.VehicleRoutingAlgorithm;
import com.graphhopper.jsprit.core.algorithm.box.Jsprit;
import com.graphhopper.jsprit.core.algorithm.listener.VehicleRoutingAlgorithmListeners;
import com.graphhopper.jsprit.core.algorithm.state.StateManager;
import com.graphhopper.jsprit.core.algorithm.state.UpdateEndLocationIfRouteIsOpen;
import com.graphhopper.jsprit.core.algorithm.termination.IterationWithoutImprovementTermination;
import com.graphhopper.jsprit.core.problem.VehicleRoutingProblem;
import com.graphhopper.jsprit.core.problem.constraint.ConstraintManager;
import com.graphhopper.jsprit.core.problem.constraint.ConstraintManager.Priority;
import com.graphhopper.jsprit.core.problem.constraint.ServiceDeliveriesFirstConstraint;
import com.graphhopper.jsprit.core.problem.constraint.SwitchNotFeasible;
import com.graphhopper.jsprit.core.problem.constraint.VehicleDependentTimeWindowConstraints;

import hagrid.core.HagridPaths;
import hagrid.core.config.ResolvedHagridConfig;

import com.graphhopper.jsprit.analysis.toolbox.StopWatch;

import org.apache.logging.log4j.Logger;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartUtils;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;

import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;
import java.awt.Color;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Random;

import org.apache.logging.log4j.LogManager;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Network;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierService;
import org.matsim.freight.carriers.jsprit.MatsimJspritFactory;

import org.matsim.freight.carriers.jsprit.VRPTransportCosts;

public class HAGRIDRouterUtils {

    private static final Logger LOGGER = LogManager.getLogger(HAGRIDRouterUtils.class);
    /** Compatibility alias only. Configure tourRules.maxRouteDurationSeconds for actual runs. */
    @Deprecated(forRemoval = false)
    public static final int MAXROUTEDURATION = hagrid.core.config.RoutingDefaults.SOLVER_MAX_ROUTE_DURATION_SECONDS;

    /**
     * System property that overrides jsprit's search seed, e.g. {@code -Dhagrid.jsprit.seed=1234}.
     *
     * <p>Why this exists: jsprit's ruin-and-recreate search is stochastic, and its default RNG is
     * seeded with a fixed 4711 ({@code RandomNumberGeneration.DEFAULT_SEED}). A run is therefore
     * reproducible, but its solution is a single draw — two runs that differ in some input also
     * differ by an unknown amount of pure search noise. Re-running with a different seed and an
     * otherwise identical input measures that noise floor, which is what tells you whether a KPI
     * difference between two scenarios is a real effect. The 2026-07-28 demand-band measurement
     * needed exactly this: vehicle-km moved non-monotonically across the three demand levels,
     * which is physically impossible and hence must be search noise.
     *
     * <p>Left unset, jsprit keeps its own default seed, so production runs are bit-identical to
     * before this property existed. Note the seed is applied per carrier (each carrier builds its
     * own algorithm), matching how the default already behaves.
     */
    public static final String JSPRIT_SEED_PROPERTY = "hagrid.jsprit.seed";

    /**
     * Diagnostic switch that restricts offline routing to a SINGLE carrier, e.g.
     * {@code -Dhagrid.jsprit.onlyCarrier=largest}. Accepts either an exact carrier id or the literal
     * {@code largest} (most services; ties broken by id, so the pick is deterministic).
     *
     * <p>Why this exists: a jsprit seed- or iteration-probe wants the carrier that dominates the
     * runtime, not the whole set. On Lausitz the largest of the 7 carriers is ~35 % of the jsprit
     * phase (824 of 2.975 jobs, METHODS-LOG §2.2), so probing it alone costs roughly a third of a
     * full arm. This is sound because carriers are routed independently — each builds its own
     * algorithm with its own RNG, and the shared {@code NetworkBasedTransportCosts} is a
     * deterministic memoisation — so the single-carrier solution equals the one that carrier would
     * get inside a full run (pinned by {@code LausitzCarrierSelectionTest}).
     *
     * <p><b>The resulting carrier file is deliberately INCOMPLETE</b> and must never feed a scenario
     * comparison. Left unset (the production default) every carrier is routed; a value that matches
     * no carrier fails loudly rather than routing nothing, because an empty carrier file would read
     * as a finished run.
     */
    public static final String JSPRIT_ONLY_CARRIER_PROPERTY = "hagrid.jsprit.onlyCarrier";

    /** The literal accepted by {@link #JSPRIT_ONLY_CARRIER_PROPERTY} to pick the biggest carrier. */
    public static final String ONLY_CARRIER_LARGEST = "largest";

    /**
     * Configures the routing algorithm with an explicit route-duration cap, overriding
     * {@link #MAXROUTEDURATION}. Introduced for the 1d DRT_MODULAR path, whose capsule-swap tours
     * must fit the shorter 12600s (3.5h) round-trip budget instead of the 25200s (7h) driver-shift
     * cap used by Hannover and the Lausitz LMD_BASELINE.
     *
     * @param vrp                     The vehicle routing problem.
     * @param serviceCount            The number of services.
     * @param jspritIterations        Number of JSprit iterations (1=quick, 20-50=production)
     * @param network                 MATSim network for U-turn detection (null to disable)
     * @param uTurnPenaltyCost        Soft score penalty per U-turn (0 to disable)
     * @param maxRouteDurationSeconds Hard cap on jsprit route duration (seconds)
     * @return The configured vehicle routing algorithm.
     */
    public static VehicleRoutingAlgorithm configureAlgorithm(VehicleRoutingProblem vrp, int serviceCount,
                                                              int jspritIterations, Network network,
                                                              double uTurnPenaltyCost,
                                                              int maxRouteDurationSeconds) {
        return configureAlgorithm(vrp, serviceCount, jspritIterations, network, uTurnPenaltyCost,
                maxRouteDurationSeconds, maxRouteDurationSeconds, AlgorithmPhase.INITIAL, 1, null);
    }

    static VehicleRoutingAlgorithm configureInitialAlgorithm(VehicleRoutingProblem vrp, int iterations,
            Network network, double uTurnPenalty, int routeLimit, int threads,
            java.util.concurrent.ExecutorService executor, long seed) {
        if (threads > 1 && executor == null) throw new IllegalArgumentException("Initial solver requires an owned executor");
        return configureAlgorithm(vrp,vrp.getJobs().size(),iterations,network,uTurnPenalty,routeLimit,routeLimit,
                AlgorithmPhase.INITIAL,threads,seed,executor);
    }
    /** Preserve existing phase-specific ruin choices while sharing correctness constraints. */
    public enum AlgorithmPhase { INITIAL, OFFLINE, REPLANNING }

    public static VehicleRoutingAlgorithm configureAlgorithm(VehicleRoutingProblem vrp, ResolvedHagridConfig config,
            long seed, int iterations) {
        var phase = config.optimization().replanningIterations().isPresent() ? AlgorithmPhase.REPLANNING
                : config.optimization().offlineIterations().isPresent() ? AlgorithmPhase.OFFLINE : AlgorithmPhase.INITIAL;
        return configureAlgorithm(vrp, config, seed, iterations, phase);
    }

    public static VehicleRoutingAlgorithm configureAlgorithm(VehicleRoutingProblem vrp, ResolvedHagridConfig config,
            long seed, int iterations, AlgorithmPhase phase) {
        return configureAlgorithm(vrp, config, seed, iterations, phase, null);
    }

    static VehicleRoutingAlgorithm configureAlgorithm(VehicleRoutingProblem vrp, ResolvedHagridConfig config,
            long seed, int iterations, AlgorithmPhase phase, java.util.concurrent.ExecutorService executor) {
        if (config.resources().internalSolverThreads() > 1 && executor == null)
            throw new IllegalArgumentException("Multiple solver threads require an owned executor; use withConfiguredAlgorithm");
        if (iterations < 0 || (phase != AlgorithmPhase.INITIAL && iterations == 0)) throw new IllegalArgumentException("Solver iterations must be nonnegative for INITIAL and positive otherwise");
        Network network = vrp.getTransportCosts() instanceof VRPTransportCosts costs ? costs.getNetwork() : null;
        if (config.tourRules().uTurnPenaltyCost() > 0 && network == null) {
            throw new IllegalArgumentException("U-turn penalty requires the actual routing network");
        }
        double routeLimit = config.tourRules().maxRouteDurationSeconds();
        // Existing solver constrained both to the tour cap; a tighter explicit driver rule also applies.
        double driverLimit = Math.min(routeLimit, config.tourRules().maxDriverTimeSeconds());
        return configureAlgorithm(vrp, vrp.getJobs().size(), iterations, network,
                config.tourRules().uTurnPenaltyCost(), routeLimit, driverLimit, phase,
                config.resources().internalSolverThreads(), seed, executor);
    }

    /** Runs caller setup/search with an owned pool; no native jsprit shutdown hook is installed. */
    public static <T> T withConfiguredAlgorithm(VehicleRoutingProblem vrp, ResolvedHagridConfig config,
            long seed, int iterations, AlgorithmPhase phase,
            java.util.function.Function<VehicleRoutingAlgorithm, T> search) {
        java.util.Objects.requireNonNull(search);
        try (var workers = new SolverWorkers(config.resources().internalSolverThreads())) {
            return search.apply(configureAlgorithm(vrp,config,seed,iterations,phase,workers.executor()));
        }
    }
    /** Transitional raw constructor adapter; preserves the previous replanning search choices. */
    public static VehicleRoutingAlgorithm configureLegacyReplanningAlgorithm(VehicleRoutingProblem vrp,
            int iterations, double routeLimit) {
        return configureAlgorithm(vrp, vrp.getJobs().size(), iterations, null, 0, routeLimit, routeLimit,
                AlgorithmPhase.REPLANNING, 1, null);
    }

    private static VehicleRoutingAlgorithm configureAlgorithm(VehicleRoutingProblem vrp, int serviceCount,
            int jspritIterations, Network network, double uTurnPenaltyCost, double maxRouteDurationSeconds,
            double maxDriverDurationSeconds, AlgorithmPhase phase, int threads, Long seed) {
        return configureAlgorithm(vrp,serviceCount,jspritIterations,network,uTurnPenaltyCost,maxRouteDurationSeconds,
                maxDriverDurationSeconds,phase,threads,seed,null);
    }

    private static VehicleRoutingAlgorithm configureAlgorithm(VehicleRoutingProblem vrp, int serviceCount,
            int jspritIterations, Network network, double uTurnPenaltyCost, double maxRouteDurationSeconds,
            double maxDriverDurationSeconds, AlgorithmPhase phase, int threads, Long seed,
            java.util.concurrent.ExecutorService executor) {
        if (jspritIterations < 0 || (phase != AlgorithmPhase.INITIAL && jspritIterations == 0)) throw new IllegalArgumentException("Solver iterations must be nonnegative for INITIAL and positive otherwise");
        StateManager stateManager = new StateManager(vrp);
        StateIds stateIds = new StateIds(stateManager);
        ConstraintManager constraintManager = new HagridConstraintManager(vrp, stateManager);
        boolean rawTemporal=ExactTemporalSolverCosts.rawAdapter(vrp.getTransportCosts())!=null;
        if(rawTemporal&&!(vrp.getTransportCosts() instanceof ExactTemporalSolverCosts))
            throw new IllegalArgumentException("Build raw distance-profile VRPs with ModalTransportCosts.solverCosts() for exact solver timing");

        if(!rawTemporal)stateManager.addStateUpdater(new RouteRealStartTimeMemorizer(stateManager, vrp.getTransportCosts()));
        stateManager.updateLoadStates();
        if(!rawTemporal)stateManager.updateTimeWindowStates();
        stateManager.updateSkillStates();
        stateManager.addStateUpdater(new UpdateEndLocationIfRouteIsOpen());
        stateManager.addStateUpdater(new OpenRouteStateVerifier());
        if(!rawTemporal)stateManager.addStateUpdater(new UpdateDepartureTimeAndPracticalTimeWindows(stateManager,
                vrp.getTransportCosts(), maxDriverDurationSeconds, stateIds));
        if(rawTemporal) {
            // Native scalar latest-arrival/switch states assume FIFO. Exact candidate replay below
            // replaces them; the immutable solver view supplies actual forward timings and unchanged rates.
            stateManager.addStateUpdater(new com.graphhopper.jsprit.core.algorithm.state.UpdateActivityTimes(
                    vrp.getTransportCosts(),vrp.getActivityCosts()));
            stateManager.addStateUpdater(new com.graphhopper.jsprit.core.algorithm.state.UpdateVariableCosts(
                    vrp.getActivityCosts(),vrp.getTransportCosts(),stateManager));
            stateManager.addStateUpdater(new com.graphhopper.jsprit.core.algorithm.state.UpdateFutureWaitingTimes(
                    stateManager,vrp.getTransportCosts()));
        }

        var rawRoads=ExactTemporalSolverCosts.rawAdapter(vrp.getTransportCosts());
        var insertionReplay=rawTemporal && vrp.getActivityCosts() instanceof HagridActivityCosts h
                ?new ExactInsertionReplay(rawRoads,h,Math.min(maxRouteDurationSeconds,maxDriverDurationSeconds)):null;
        if(insertionReplay!=null) {
            constraintManager.addConstraint((com.graphhopper.jsprit.core.problem.constraint.HardActivityConstraint)(facts,prev,inserted,next,end)->
                    insertionReplay.evaluate(facts,inserted,next,false).feasible()
                            ?com.graphhopper.jsprit.core.problem.constraint.HardActivityConstraint.ConstraintsStatus.FULFILLED
                            :com.graphhopper.jsprit.core.problem.constraint.HardActivityConstraint.ConstraintsStatus.NOT_FULFILLED,Priority.CRITICAL);
        }else {
        constraintManager.addConstraint(
                new MaxRouteDurationConstraint(maxRouteDurationSeconds, stateManager, vrp.getTransportCosts()),
                Priority.CRITICAL);
        constraintManager.addConstraint(
                new TimeWindowConstraintWithDriverTime(stateManager, vrp.getTransportCosts(), maxDriverDurationSeconds, stateIds),
                Priority.CRITICAL);

        }

        if(!rawTemporal)constraintManager.addConstraint(new VehicleDependentTimeWindowConstraints(stateManager,
                vrp.getTransportCosts(), vrp.getActivityCosts()), ConstraintManager.Priority.HIGH);
        constraintManager.addConstraint(new ServiceDeliveriesFirstConstraint(),
                ConstraintManager.Priority.CRITICAL);

        if(!rawTemporal)constraintManager.addTimeWindowConstraint();
        constraintManager.addLoadConstraint();
        constraintManager.addSkillsConstraint();
        if(!rawTemporal)constraintManager.addConstraint(new SwitchNotFeasible(stateManager));

        // Soft U-turn penalty: discourages reverse-link maneuvers in route solutions
        if (network != null && uTurnPenaltyCost > 0.0 && !(vrp.getTransportCosts() instanceof NativeTurnTransportCosts)) {
            constraintManager.addConstraint(new UTurnSoftConstraint(network, uTurnPenaltyCost));
        }

        double radialShare = 0.25; // lower default shares to reduce ruin size
        double randomShare = 0.40;

        if (serviceCount > 500) { // very large instance: use very small ruin to get a solution faster
            radialShare = 0.05;
            randomShare = 0.10;
        } else if (serviceCount > 250) { // large instance
            radialShare = 0.12;
            randomShare = 0.20;
        }

        int radialServicesReplanned = Math.max(1, (int) (serviceCount * radialShare));
        int randomServicesReplanned = Math.max(1, (int) (serviceCount * randomShare));

        Jsprit.Builder builder = Jsprit.Builder.newInstance(vrp)
                .addCoreStateAndConstraintStuff(!rawTemporal)
                .setStateAndConstraintManager(stateManager, constraintManager)
                .setProperty(Jsprit.Parameter.THREADS, Integer.toString(threads))
                // REGRET_INSERTION is jsprit's own default; HAGRID used to override it with
                // BEST_INSERTION, which cost roughly one vehicle per carrier. BEST_INSERTION is
                // greedy per job — each job goes wherever it is cheapest AT THE MOMENT it is
                // inserted, and opening a fresh route costs nothing but the depot stub because the
                // vehicle's fixed cost is invisible during insertion (jsprit's FIXED_COST_PARAM
                // defaults to 0, and the fixed-cost branch of JobInsertionCostsCalculatorBuilder is
                // commented out in 1.8). That over-opens routes, and ruin-and-recreate cannot undo
                // it afterwards: a half-emptied tour still pays its full fixed cost, so every
                // intermediate state scores worse and the acceptor rejects the path out.
                // Measured 2026-08-11 on carrier dpd (408 services, jsprit 100 iters, production
                // inputs incl. service-area clip): 5 tours / 248.6 km / 878.82 EUR with
                // BEST_INSERTION vs 4 tours / 206.9 km / 746.15 EUR with REGRET_INSERTION
                // (-1 vehicle, -15.1 % cost, shift utilisation 74.2 % -> 88.5 %). Over the full
                // 7-carrier route: 52 -> 41 tours, -33.9 % km, -17.9 % cost.
                // It COSTS runtime, contrary to what this comment claimed until 2026-08-12: the
                // paired full-run measurement gives +9.2 % wall / +9.3 % jsprit time at identical
                // 100/100 iterations, i.e. a higher per-iteration cost (regret evaluates every
                // route per job, not just the best position). The earlier "not slower" came from
                // carrier dpd alone and did not generalise (METHODS-LOG §2.34).
                // Verified NOT to be a ruin-size problem: enlarging every ruin past jsprit's own
                // 50/70 caps (radial 122, random 163) leaves the tour count at 5.
                // Pinned by JspritConstructionHeuristicTest — do not "restore" BEST_INSERTION.
                .setProperty(Jsprit.Parameter.CONSTRUCTION, Jsprit.Construction.REGRET_INSERTION.toString())
                // Keep standard regret insertion in the single production search configuration.
                .setProperty(Jsprit.Parameter.FAST_REGRET, "false");

        // The scalar fast path retains native insertion decisions. Opt in only when
        // a diagnostic consumer needs the detailed insertion-cost breakdown.
        builder.setServiceInsertionCalculatorFactory(
                com.graphhopper.jsprit.core.algorithm.recreate.HagridServiceInsertionCalculator.factory(
                        Boolean.getBoolean("hagrid.jsprit.insertionDiagnostics")));

        // Must be set on the builder, not via RandomNumberGeneration.setSeed(): Jsprit.Builder
        // takes its RNG from RandomNumberGeneration.newInstance(), which always uses the fixed
        // DEFAULT_SEED and therefore ignores setSeed() entirely.
        if (phase != AlgorithmPhase.REPLANNING) {
            builder.setProperty(Jsprit.Parameter.RADIAL_MIN_SHARE, Integer.toString(radialServicesReplanned))
                    .setProperty(Jsprit.Parameter.RADIAL_MAX_SHARE, Integer.toString(radialServicesReplanned))
                    .setProperty(Jsprit.Parameter.RANDOM_BEST_MIN_SHARE, Integer.toString(randomServicesReplanned))
                    .setProperty(Jsprit.Parameter.RANDOM_BEST_MAX_SHARE, Integer.toString(randomServicesReplanned));
        }
        if (seed == null) applySeedOverride(builder);
        else builder.setRandom(new Random(seed));

        if(rawTemporal)builder.setActivityInsertionCalculator(new ExactTemporalInsertionCosts(
                ExactTemporalSolverCosts.rawAdapter(vrp.getTransportCosts()),vrp.getActivityCosts(),uTurnPenaltyCost,insertionReplay));
        if(rawTemporal)builder.setCustomAcceptor(new ExactTemporalAcceptance(
                ExactTemporalSolverCosts.rawAdapter(vrp.getTransportCosts()),Math.min(maxRouteDurationSeconds,maxDriverDurationSeconds)));
        if (executor != null) builder.setExecutorService(executor, threads);
        VehicleRoutingAlgorithm algorithm = builder.buildAlgorithm();

        algorithm.setMaxIterations(jspritIterations);
        algorithm.addTerminationCriterion(new IterationWithoutImprovementTermination(calculateNoImprovementThreshold(jspritIterations)));
        algorithm.getAlgorithmListeners().addListener(new StopWatch(), VehicleRoutingAlgorithmListeners.Priority.HIGH);
        algorithm.addListener(new DepartureTimeReScheduler(stateManager, algorithm.getObjectiveFunction(),
                maxRouteDurationSeconds, maxDriverDurationSeconds));


        return algorithm;
    }

    /**
     * Applies {@link #JSPRIT_SEED_PROPERTY} to the builder, if set. No-op otherwise, so the
     * default stays jsprit's own fixed seed. A malformed value fails loudly rather than
     * silently falling back: a seed sweep whose seed was ignored would produce identical runs
     * and read as "no search noise", the exact opposite of the truth.
     */
    public static long resolveSolverSeed() {
        String raw = System.getProperty(JSPRIT_SEED_PROPERTY);
        if (raw == null || raw.isBlank()) return 4711L; // Pinned jsprit 2.0.0 default; checked against newInstance() in tests.
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(JSPRIT_SEED_PROPERTY + " must be a long, was: '" + raw + "'", e);
        }
    }

    /** Configured HAGRID runs share the master seed; unconfigured compatibility callers retain the old entry point. */
    public static long resolveSolverSeed(ResolvedHagridConfig config) {
        return config == null ? resolveSolverSeed() : resolveSolverSeed(config.randomSeeds().seed());
    }

    public static long resolveSolverSeed(long masterSeed) {
        String legacy = System.getProperty(JSPRIT_SEED_PROPERTY);
        if (legacy != null && !legacy.isBlank() && resolveSolverSeed() != masterSeed)
            throw new IllegalArgumentException(JSPRIT_SEED_PROPERTY + " conflicts with run.seed=" + masterSeed
                    + "; configure the common seed using run.seed instead");
        return masterSeed;
    }

    static void applySeedOverride(Jsprit.Builder builder) {
        String raw = System.getProperty(JSPRIT_SEED_PROPERTY);
        if (raw == null || raw.isBlank()) {
            return;
        }
        long seed = resolveSolverSeed();
        builder.setRandom(new Random(seed));
        LOGGER.info("jsprit search seed overridden via -D{}={}", JSPRIT_SEED_PROPERTY, seed);
    }

    /**
     * Calculates the jsprit "no improvement" termination threshold as a
     * logarithmic function of the configured iteration count.
     *
     * <p>The curve flattens out for large iteration counts, giving roughly:
     * <ul>
     *   <li>100 iter  &rarr; 25</li>
     *   <li>1 000 iter &rarr; ~100</li>
     *   <li>10 000 iter &rarr; ~130</li>
     * </ul>
     *
     * For very small iteration counts the result is capped at iterations/4.
     *
     * @param jspritIterations the configured maximum jsprit iterations
     * @return no-improvement threshold (&ge; 1)
     */
    public static int calculateNoImprovementThreshold(int jspritIterations) {
        int iterations = Math.max(1, jspritIterations);
        int logBased   = (int) Math.round(14.0 * Math.log(iterations));
        int linearCap  = iterations / 4;
        return Math.max(1, Math.min(linearCap, logBased));
    }

    /**
     * Plots the cumulative routing runtime as a line chart.
     *
     * @param startTime   The start time of the routing process.
     * @param endTime     The end time of the routing process.
     * @param routedTimes A list of timestamps when each carrier routing finished.
     * @param fileName    The file name for the output plot.
     * @param carrierType The type of carrier (e.g., delivery, supply).
     */
    public static void plotCumulativeRoutingRuntime(long startTime, long endTime, List<Long> routedTimes,
            String fileName, String carrierType) {
        LOGGER.info("Plotting cumulative routing runtime...");

        // Create the XY series for the plot
        XYSeries series = new XYSeries("Cumulative Routing Runtime");
        for (int i = 0; i < routedTimes.size(); i++) {
            long routedTime = routedTimes.get(i);
            series.add(i + 1, (routedTime - startTime) / 1000.0);
        }

        // Create a dataset
        XYSeriesCollection dataset = new XYSeriesCollection(series);

        // Create the chart
        JFreeChart chart = ChartFactory.createXYLineChart(
                "Cumulative Routing Runtime",
                "Number of Routed Carriers",
                "Time (seconds)",
                dataset,
                PlotOrientation.VERTICAL,
                true,
                true,
                false);

        // Customize the plot appearance
        XYPlot plot = chart.getXYPlot();
        XYLineAndShapeRenderer renderer = new XYLineAndShapeRenderer();
        renderer.setSeriesPaint(0, Color.BLUE);
        renderer.setSeriesShapesVisible(0, true);
        plot.setRenderer(renderer);
        plot.setBackgroundPaint(Color.white);

        // Output the chart to a file
        try {
            File outputFile = new File(
                    "phd/output/" + fileName + "_" + carrierType + "_cumulative_routing_runtime.png");
            ChartUtils.saveChartAsPNG(outputFile, chart, 800, 600);
            LOGGER.info("Cumulative routing runtime plot saved as {}", outputFile.getAbsolutePath());
        } catch (IOException e) {
            LOGGER.error("Error saving cumulative routing runtime plot", e);
        }
    }

    /**
     * Plots the individual routing runtime per carrier as a line chart.
     *
     * @param startTime   The start time of the routing process.
     * @param routedTimes A list of timestamps when each carrier routing finished.
     * @param fileName    The file name for the output plot.
     * @param carrierType The type of carrier (e.g., delivery, supply).
     */
    public static void plotIndividualRoutingRuntime(long startTime, List<Long> routedTimes, String fileName,
            String carrierType) {
        LOGGER.info("Plotting individual routing runtime...");

        // Create the XY series for the plot
        XYSeries series = new XYSeries("Individual Routing Runtime");
        long previousTime = startTime;
        for (int i = 0; i < routedTimes.size(); i++) {
            long routedTime = routedTimes.get(i);
            series.add(i + 1, (routedTime - previousTime) / 1000.0);
            previousTime = routedTime;
        }

        // Create a dataset
        XYSeriesCollection dataset = new XYSeriesCollection(series);

        // Create the chart
        JFreeChart chart = ChartFactory.createXYLineChart(
                "Individual Routing Runtime",
                "Number of Routed Carriers",
                "Time (seconds)",
                dataset,
                PlotOrientation.VERTICAL,
                true,
                true,
                false);

        // Customize the plot appearance
        XYPlot plot = chart.getXYPlot();
        XYLineAndShapeRenderer renderer = new XYLineAndShapeRenderer();
        renderer.setSeriesPaint(0, Color.RED);
        renderer.setSeriesShapesVisible(0, true);
        plot.setRenderer(renderer);
        plot.setBackgroundPaint(Color.white);

        // Output the chart to a file
        try {
            File outputFile = new File(
                    new HagridPaths().getPipelineRoot().resolve("hagrid-output").resolve(
                            fileName + "_" + carrierType + "_individual_routing_runtime.png").toString());
            ChartUtils.saveChartAsPNG(outputFile, chart, 800, 600);
            LOGGER.info("Individual routing runtime plot saved as {}", outputFile.getAbsolutePath());
        } catch (IOException e) {
            LOGGER.error("Error saving individual routing runtime plot", e);
        }
    }

    /**
     * Creates the routing problem for the carrier.
     *
     * @param carrier       The carrier to route.
     * @param network       The network.
     * @param netBasedCosts The network-based transport costs.
     * @return The created vehicle routing problem.
     */
    @Deprecated(forRemoval = false)
    public static VehicleRoutingProblem createRoutingProblem(Carrier carrier, Network network,
            VRPTransportCosts netBasedCosts) {
        VehicleRoutingProblem.Builder vrpBuilder = MatsimJspritFactory.createRoutingProblemBuilder(carrier, network);
        vrpBuilder.setRoutingCost(netBasedCosts);
        return vrpBuilder.build();
    }

    /**
     * Persists the jobs the best jsprit solution could NOT insert as carrier attributes, so they
     * surface as a dashboard KPI instead of silently disappearing from the tours while
     * {@code numberOfParcels} still counts them as attempted demand. Shared by BOTH the Hannover
     * legacy {@code Router} and the Lausitz {@code LausitzFreightPreprocessor}.
     * <p>
     * Both routing paths use an INFINITE fleet, so an unassigned stop is never "out of vehicles":
     * it is a stop whose demand exceeds every (identical) van's capacity — e.g. a merged MIXED stop
     * of 179 parcels against a capacity-30 van — or that is infeasible under the 7h route-duration
     * cap / vehicle end window. Adding vehicles cannot help; the job is structurally infeasible.
     * <p>
     * {@code unassignedParcels} sums the resolved services' {@code capacityDemand} (parcel-level).
     * An unresolved id is still counted as a job but contributes ZERO parcels — it is deliberately
     * NOT defaulted to 1, which would silently invent demand.
     *
     * @param carrier           the routed carrier; attributes are written onto it
     * @param unassignedJobIds  ids of the jobs jsprit left unassigned (e.g. from
     *                          {@code solution.getUnassignedJobs()} mapped to {@code Job::getId})
     */
    public static void recordUnassignedJobs(Carrier carrier, Collection<String> unassignedJobIds) {
        List<String> ids = new ArrayList<>(unassignedJobIds);
        int unassignedParcels = 0;
        for (String id : ids) {
            CarrierService service = carrier.getServices().get(Id.create(id, CarrierService.class));
            if (service != null) {
                unassignedParcels += service.getCapacityDemand();
            }
        }
        carrier.getAttributes().putAttribute("unassignedJobs", ids.size());
        carrier.getAttributes().putAttribute("unassignedParcels", unassignedParcels);
        carrier.getAttributes().putAttribute("unassignedJobsAsString", ids.toString());
        if (!ids.isEmpty()) {
            LOGGER.warn("Carrier {}: jsprit left {} of {} stops UNASSIGNED ({} parcels) - not driven by any tour: {}",
                    carrier.getId(), ids.size(), carrier.getServices().size(), unassignedParcels, ids);
        }
    }

    /** Carrier attribute holding the U-turn penalty Step A (routing) used; read back by Step B. */
    public static final String UTURN_PENALTY_ATTR = "uTurnPenaltyStepA";

    /**
     * Records the U-turn penalty used while routing this carrier, so the simulation run (Step B)
     * can detect a mismatch with its own {@code uTurnPenalty} instead of silently diverging.
     *
     * @param carrier          the routed carrier; the attribute is written onto it
     * @param uTurnPenaltyCost the soft score penalty per U-turn that Step A applied (written as {@code Double})
     */
    public static void recordUTurnPenalty(Carrier carrier, double uTurnPenaltyCost) {
        carrier.getAttributes().putAttribute(UTURN_PENALTY_ATTR, Double.valueOf(uTurnPenaltyCost));
    }

    /** Configure jsprit's native search with complete section replay callbacks. */
    public static VehicleRoutingAlgorithm configureSectionAlgorithm(VehicleRoutingProblem problem,
            hagrid.core.routing.sections.SectionSearchState state,long seed,hagrid.core.routing.sections.SectionSearchPolicy policy) {
        return hagrid.core.routing.sections.SectionInsertionAdapter.configure(problem,state,seed,policy);

    }
}
