package hagrid;

import hagrid.simulation.HAGRIDSimulationConfig;
import hagrid.simulation.SimulationRunnerUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;

/**
 * HAGRID Simulation Runner — configure scenarios and go.
 * <p>
 * Pass one or more scenario specs as command-line arguments.  Each spec is a
 * comma-separated list of {@code key=value} pairs:
 * <pre>
 *   concept=basecase,date=2025-05-13,tag=V1,maxIter=150,jspritIter=10000,writeDashboard=true
 * </pre>
 *
 * <h3>Required keys</h3>
 * <ul>
 *   <li>{@code concept} — scenario concept name</li>
 *   <li>{@code date}    — simulation date (yyyy-MM-dd)</li>
 * </ul>
 *
 * <h3>Optional keys</h3>
 * <ul>
 *   <li>{@code tag}            — version tag appended to run ID (e.g. V1)</li>
 *   <li>{@code maxIter}        — MATSim iterations (default 150)</li>
 *   <li>{@code jspritIter}     — jsprit iterations (default 100)</li>
 *   <li>{@code zoneCaching}    — enable zone-based caching (default false)</li>
 *   <li>{@code zoneThreshold}  — zone caching threshold in m (default 1500 when enabled)</li>
 *   <li>{@code uTurnPenalty}   — score penalty per U-turn (default 1.0)</li>
 *   <li>{@code writeDashboard} — generate analysis dashboard after simulation (default false)</li>
 * </ul>
 */
public class HAGRIDSimulationRunner {

    // Must run before any Logger is created → routes bootstrap logs to hagrid-matsim-output/
    static { SimulationRunnerUtils.initLogging(); }

    private static final Logger LOG = LogManager.getLogger(HAGRIDSimulationRunner.class);

    public static void main(String[] args) throws Exception {
        if (SimulationRunnerUtils.isHelpRequested(args)) {
            SimulationRunnerUtils.printUsage();
            return;
        }

        SimulationRunnerUtils.printStartBanner();

        List<HAGRIDSimulationConfig> scenarios = SimulationRunnerUtils.parseScenarios(args);
        SimulationRunnerUtils.validateAll(scenarios);

        boolean[] dashFlags = SimulationRunnerUtils.extractDashboardFlags(args);

        for (int i = 0; i < scenarios.size(); i++) {
            HAGRIDSimulationConfig cfg = scenarios.get(i);
            SimulationRunnerUtils.runSimulation(cfg);

            if (dashFlags[i]) {
                LOG.info("writeDashboard=true → generating dashboard...");
                SimulationRunnerUtils.generateDashboard(cfg);
            }
        }

        SimulationRunnerUtils.printEndBanner();
    }
}
