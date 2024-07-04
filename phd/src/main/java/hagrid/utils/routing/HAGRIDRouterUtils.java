package hagrid.utils.routing;

import com.graphhopper.jsprit.core.algorithm.VehicleRoutingAlgorithm;
import com.graphhopper.jsprit.core.algorithm.box.Jsprit;
import com.graphhopper.jsprit.core.algorithm.listener.VehicleRoutingAlgorithmListeners;
import com.graphhopper.jsprit.core.algorithm.state.StateManager;
import com.graphhopper.jsprit.core.algorithm.state.UpdateEndLocationIfRouteIsOpen;
import com.graphhopper.jsprit.core.algorithm.termination.IterationWithoutImprovementTermination;
import com.graphhopper.jsprit.core.problem.VehicleRoutingProblem;
import com.graphhopper.jsprit.analysis.toolbox.StopWatch;

import org.apache.logging.log4j.Logger;
import org.jfree.chart.ChartColor;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.ChartUtils;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.chart.ui.ApplicationFrame;

import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;
import java.awt.BasicStroke;
import java.awt.Color;
import java.io.File;
import java.io.IOException;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.matsim.api.core.v01.network.Network;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.jsprit.MatsimJspritFactory;

import org.matsim.freight.carriers.jsprit.VRPTransportCosts;

public class HAGRIDRouterUtils {

    private static final Logger LOGGER = LogManager.getLogger(HAGRIDRouterUtils.class);
    private static final int MAX_DRIVE_DURATION = 8 * 3600; // example value, adjust as needed

    /**
     * Configures the routing algorithm.
     *
     * @param vrp          The vehicle routing problem.
     * @param serviceCount The number of services.
     * @return The configured vehicle routing algorithm.
     */
    public static VehicleRoutingAlgorithm configureAlgorithm(VehicleRoutingProblem vrp, int serviceCount) {
        StateManager stateManager = new StateManager(vrp);
        stateManager.addStateUpdater(new UpdateEndLocationIfRouteIsOpen());
        stateManager.addStateUpdater(new UpdateDepartureTimeAndPracticalTimeWindows(stateManager,
                vrp.getTransportCosts(), MAX_DRIVE_DURATION));

        double RADIAL_SHARE = 0.3;
        double RANDOM_SHARE = 0.5;

        if (serviceCount > 250) {
            RADIAL_SHARE = 0.15;
            RANDOM_SHARE = 0.25;
        }

        int radialServicesReplanned = Math.max(1, (int) (serviceCount * RADIAL_SHARE));
        int randomServicesReplanned = Math.max(1, (int) (serviceCount * RANDOM_SHARE));

        VehicleRoutingAlgorithm algorithm = Jsprit.Builder.newInstance(vrp)
                .setStateAndConstraintManager(stateManager, null)
                .setProperty(Jsprit.Parameter.RADIAL_MIN_SHARE, String.valueOf(radialServicesReplanned))
                .setProperty(Jsprit.Parameter.RADIAL_MAX_SHARE, String.valueOf(radialServicesReplanned))
                .setProperty(Jsprit.Parameter.RANDOM_BEST_MIN_SHARE, String.valueOf(randomServicesReplanned))
                .setProperty(Jsprit.Parameter.RANDOM_BEST_MAX_SHARE, String.valueOf(randomServicesReplanned))
                .buildAlgorithm();

        int iterations = serviceCount > 250 ? 20 : 40;
        int termination = serviceCount > 250 ? 3 : 5;

        algorithm.setMaxIterations(iterations);
        algorithm.addTerminationCriterion(new IterationWithoutImprovementTermination(termination));
        algorithm.getAlgorithmListeners().addListener(new StopWatch(), VehicleRoutingAlgorithmListeners.Priority.HIGH);
        algorithm.addListener(new DepartureTimeReScheduler());

        return algorithm;
    }

    /**
     * Plots the routing runtime as a line chart.
     *
     * @param startTime   The start time of the routing process.
     * @param endTime     The end time of the routing process.
     * @param routedTimes A list of timestamps when each carrier routing finished.
     * @param fileName    The file name for the output plot.
     */
    public static void plotRoutingRuntime(long startTime, long endTime, List<Long> routedTimes, String fileName) {
        LOGGER.info("Plotting routing runtime...");

        // Create the XY series for the plot
        XYSeries series = new XYSeries("Routing Runtime");
        for (int i = 0; i < routedTimes.size(); i++) {
            long routedTime = routedTimes.get(i);
            series.add(i + 1, (routedTime - startTime) / 1000.0);
        }

        // Create a dataset
        XYSeriesCollection dataset = new XYSeriesCollection(series);

        // Create the chart
        JFreeChart chart = ChartFactory.createXYLineChart(
                "Routing Runtime",
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
            File outputFile = new File("phd/output/" + fileName + "_routing_runtime.png");
            ChartUtils.saveChartAsPNG(outputFile, chart, 800, 600);
            LOGGER.info("Routing runtime plot saved as {}", outputFile.getAbsolutePath());
        } catch (IOException e) {
            LOGGER.error("Error saving routing runtime plot", e);
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
}
