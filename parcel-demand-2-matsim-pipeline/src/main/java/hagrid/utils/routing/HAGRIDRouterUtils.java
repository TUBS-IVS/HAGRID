package hagrid.utils.routing;

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

import hagrid.HagridPaths;
import hagrid.simulation.MaxRouteDurationConstraint;
import hagrid.simulation.OpenRouteStateVerifier;
import hagrid.simulation.RouteRealStartTimeMemorizer;
import hagrid.simulation.TimeWindowConstraintWithDriverTime;

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
    /**
     * Hard cap on jsprit route duration (7h driver shift), enforced via
     * {@code MaxRouteDurationConstraint}. Do not change casually: it alters Hannover routing results.
     */
    public static final int MAXROUTEDURATION = 25200;

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
     * Configures the routing algorithm (no U-turn penalty).
     *
     * @param vrp          The vehicle routing problem.
     * @param serviceCount The number of services.
     * @return The configured vehicle routing algorithm.
     */
    public static VehicleRoutingAlgorithm configureAlgorithm(VehicleRoutingProblem vrp, int serviceCount) {
        return configureAlgorithm(vrp, serviceCount, 1, null, 0.0);
    }

    /**
     * Configures the routing algorithm with custom iteration count (no U-turn penalty).
     */
    public static VehicleRoutingAlgorithm configureAlgorithm(VehicleRoutingProblem vrp, int serviceCount, int jspritIterations) {
        return configureAlgorithm(vrp, serviceCount, jspritIterations, null, 0.0);
    }

    /**
     * Configures the routing algorithm with custom iteration count and optional U-turn penalty.
     * Delegates to the 6-arg overload with {@link #MAXROUTEDURATION} — byte-identical to
     * before the cap became a parameter.
     *
     * @param vrp                  The vehicle routing problem.
     * @param serviceCount         The number of services.
     * @param jspritIterations     Number of JSprit iterations (1=quick, 20-50=production)
     * @param network              MATSim network for U-turn detection (null to disable)
     * @param uTurnPenaltyCost  Soft score penalty per U-turn (0 to disable)
     * @return The configured vehicle routing algorithm.
     */
    public static VehicleRoutingAlgorithm configureAlgorithm(VehicleRoutingProblem vrp, int serviceCount,
                                                              int jspritIterations, Network network,
                                                              double uTurnPenaltyCost) {
        return configureAlgorithm(vrp, serviceCount, jspritIterations, network, uTurnPenaltyCost,
                MAXROUTEDURATION);
    }

    /**
     * Configures the routing algorithm with an explicit route-duration cap, overriding
     * {@link #MAXROUTEDURATION}. Used by tests that need a small, duration-bound fixture, and by
     * any scenario whose tours must fit a shorter budget than the 25200s (7h) driver shift.
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
        StateManager stateManager = new StateManager(vrp);
        ConstraintManager constraintManager = new ConstraintManager(vrp, stateManager);

        stateManager.addStateUpdater(new RouteRealStartTimeMemorizer(stateManager, vrp.getTransportCosts()));
        stateManager.updateLoadStates();
        stateManager.updateTimeWindowStates();
        //TODO TEST
        // stateManager.updateSkillStates();
        stateManager.addStateUpdater(new UpdateEndLocationIfRouteIsOpen());
        stateManager.addStateUpdater(new OpenRouteStateVerifier());
        stateManager.addStateUpdater(new UpdateDepartureTimeAndPracticalTimeWindows(stateManager,
                vrp.getTransportCosts(), maxRouteDurationSeconds));

        constraintManager.addConstraint(
                new MaxRouteDurationConstraint(maxRouteDurationSeconds, stateManager, vrp.getTransportCosts()),
                Priority.CRITICAL);
        constraintManager.addConstraint(
                new TimeWindowConstraintWithDriverTime(stateManager, vrp.getTransportCosts(), maxRouteDurationSeconds),
                Priority.CRITICAL);

        constraintManager.addConstraint(new VehicleDependentTimeWindowConstraints(stateManager,
                vrp.getTransportCosts(), vrp.getActivityCosts()), ConstraintManager.Priority.HIGH);
        constraintManager.addConstraint(new ServiceDeliveriesFirstConstraint(),
                ConstraintManager.Priority.CRITICAL);

        constraintManager.addTimeWindowConstraint();
        constraintManager.addLoadConstraint();
        // TODO 
        // constraintManager.addSkillsConstraint();
        constraintManager.addConstraint(new SwitchNotFeasible(stateManager));

        // Soft U-turn penalty: discourages reverse-link maneuvers in route solutions
        if (network != null && uTurnPenaltyCost > 0.0) {
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

    // int jspritThreads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
    Jsprit.Builder builder = Jsprit.Builder.newInstance(vrp)
                .setStateAndConstraintManager(stateManager, constraintManager)
                // .setProperty(Jsprit.Parameter.THREADS, String.valueOf(jspritThreads))
                .setProperty(Jsprit.Parameter.RADIAL_MIN_SHARE, String.valueOf(radialServicesReplanned))
                .setProperty(Jsprit.Parameter.RADIAL_MAX_SHARE, String.valueOf(radialServicesReplanned))
                .setProperty(Jsprit.Parameter.RANDOM_BEST_MIN_SHARE, String.valueOf(randomServicesReplanned))
                .setProperty(Jsprit.Parameter.RANDOM_BEST_MAX_SHARE, String.valueOf(randomServicesReplanned))
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
                // jsprit's default is false as well; kept explicit because regretFast() is the
                // next candidate knob (see BACKLOG "jsprit-Upgrade 1.8 -> 2.x", spike question i).
                .setProperty(Jsprit.Parameter.FAST_REGRET, "false");

        // Must be set on the builder, not via RandomNumberGeneration.setSeed(): Jsprit.Builder
        // takes its RNG from RandomNumberGeneration.newInstance(), which always uses the fixed
        // DEFAULT_SEED and therefore ignores setSeed() entirely.
        applySeedOverride(builder);

        VehicleRoutingAlgorithm algorithm = builder.buildAlgorithm();

        int iterations = Math.max(1, jspritIterations);
        int termination = calculateNoImprovementThreshold(iterations);

        algorithm.setMaxIterations(iterations);
        algorithm.addTerminationCriterion(new IterationWithoutImprovementTermination(termination));
        algorithm.getAlgorithmListeners().addListener(new StopWatch(), VehicleRoutingAlgorithmListeners.Priority.HIGH);
        algorithm.addListener(new DepartureTimeReScheduler());
  

        return algorithm;
    }

    /**
     * Applies {@link #JSPRIT_SEED_PROPERTY} to the builder, if set. No-op otherwise, so the
     * default stays jsprit's own fixed seed. A malformed value fails loudly rather than
     * silently falling back: a seed sweep whose seed was ignored would produce identical runs
     * and read as "no search noise", the exact opposite of the truth.
     */
    static void applySeedOverride(Jsprit.Builder builder) {
        String raw = System.getProperty(JSPRIT_SEED_PROPERTY);
        if (raw == null || raw.isBlank()) {
            return;
        }
        long seed;
        try {
            seed = Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    JSPRIT_SEED_PROPERTY + " must be a long, was: '" + raw + "'", e);
        }
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
    public static VehicleRoutingProblem createRoutingProblem(Carrier carrier, Network network,
            VRPTransportCosts netBasedCosts) {
        VehicleRoutingProblem.Builder vrpBuilder = MatsimJspritFactory.createRoutingProblemBuilder(carrier, network);
        vrpBuilder.setRoutingCost(netBasedCosts);
        return vrpBuilder.build();
    }

    /**
     * Persists the jobs the best jsprit solution could NOT insert as carrier attributes, so they
     * surface as a dashboard KPI instead of silently disappearing from the tours while
     * {@code numberOfParcels} still counts them as attempted demand. Called from {@code Router}.
     * <p>
     * The routing path uses an INFINITE fleet, so an unassigned stop is never "out of vehicles":
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
}
