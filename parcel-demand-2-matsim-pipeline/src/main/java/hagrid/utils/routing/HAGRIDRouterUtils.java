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
     * {@code MaxRouteDurationConstraint} for BOTH the Hannover legacy path and the Lausitz LMD path.
     * Also referenced by {@code LmdCarrierBuilder} to derive per-wave vehicle operating windows
     * (Hannover parity) — do not change casually: it alters legacy Hannover routing results.
     */
    public static final int MAXROUTEDURATION = 25200;

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
    VehicleRoutingAlgorithm algorithm = Jsprit.Builder.newInstance(vrp)
                .setStateAndConstraintManager(stateManager, constraintManager)
                // .setProperty(Jsprit.Parameter.THREADS, String.valueOf(jspritThreads))
                .setProperty(Jsprit.Parameter.RADIAL_MIN_SHARE, String.valueOf(radialServicesReplanned))
                .setProperty(Jsprit.Parameter.RADIAL_MAX_SHARE, String.valueOf(radialServicesReplanned))
                .setProperty(Jsprit.Parameter.RANDOM_BEST_MIN_SHARE, String.valueOf(randomServicesReplanned))
                .setProperty(Jsprit.Parameter.RANDOM_BEST_MAX_SHARE, String.valueOf(randomServicesReplanned))
                .setProperty(Jsprit.Parameter.CONSTRUCTION, Jsprit.Construction.BEST_INSERTION.toString())
                .setProperty(Jsprit.Parameter.FAST_REGRET, "false")
                .buildAlgorithm();

        int iterations = Math.max(1, jspritIterations);
        int termination = calculateNoImprovementThreshold(iterations);

        algorithm.setMaxIterations(iterations);
        algorithm.addTerminationCriterion(new IterationWithoutImprovementTermination(termination));
        algorithm.getAlgorithmListeners().addListener(new StopWatch(), VehicleRoutingAlgorithmListeners.Priority.HIGH);
        algorithm.addListener(new DepartureTimeReScheduler());
  

        return algorithm;
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
}
