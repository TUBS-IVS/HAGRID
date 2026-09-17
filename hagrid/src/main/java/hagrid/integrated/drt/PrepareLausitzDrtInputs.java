package hagrid.integrated.drt;

import hagrid.simulation.HAGRIDSimulationConfig;
import hagrid.simulation.SimulationRunnerUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.List;

/**
 * CLI entry point that runs {@link LausitzDrtPreprocessor} for every DRT scenario
 * specified on the command line.
 *
 * <p>Accepts the same scenario-specification strings as
 * {@link hagrid.HAGRIDSimulationRunner} (e.g.
 * {@code concept=drt_baseline,date=2025-05-13,studyArea=LAUSITZ_HOYERSWERDA,fleetSize=20}).
 * Each spec is parsed by {@link SimulationRunnerUtils#parseScenarios(String[])} and then
 * handed to {@link #process(List)}.
 *
 * <p>Only DRT scenarios are accepted; a non-DRT config causes an immediate
 * {@link IllegalArgumentException}.
 */
public final class PrepareLausitzDrtInputs {

    private static final Logger LOG = LogManager.getLogger(PrepareLausitzDrtInputs.class);

    private PrepareLausitzDrtInputs() {}

    /**
     * CLI entry point.
     *
     * @param args one or more scenario specification strings
     * @throws IOException if any preprocessor call fails to write output files
     */
    public static void main(String[] args) throws IOException {
        List<HAGRIDSimulationConfig> configs = SimulationRunnerUtils.parseScenarios(args);
        process(configs);
    }

    /**
     * Processes a list of scenario configurations: rejects any non-DRT config with a clear
     * message, then runs {@link LausitzDrtPreprocessor#run(HAGRIDSimulationConfig)} for each
     * DRT scenario and logs the produced file paths.
     *
     * <p>Package-private to allow unit testing without invoking the heavy preprocessor
     * (callers can pass a non-DRT config and assert the rejection; a real DRT config would
     * trigger actual file I/O which requires staged input data).
     *
     * @param configs scenario configurations to process
     * @throws IllegalArgumentException if any config is not a DRT scenario
     * @throws IOException              if a preprocessor call fails
     */
    static void process(List<HAGRIDSimulationConfig> configs) throws IOException {
        for (HAGRIDSimulationConfig cfg : configs) {
            if (!cfg.isDrtScenario()) {
                throw new IllegalArgumentException(
                        "PrepareLausitzDrtInputs only handles DRT scenarios: " + cfg.getRunId());
            }
            LausitzDrtPreprocessor.run(cfg);
            LOG.info("[{}] DRT network   -> {}", cfg.getRunId(), cfg.getDrtNetworkClipped());
            LOG.info("[{}] Pax plans     -> {}", cfg.getRunId(), cfg.getPassengerPlansClipped());
            LOG.info("[{}] Fleet file    -> {}", cfg.getRunId(), cfg.getDrtFleetFile());
        }
    }
}
