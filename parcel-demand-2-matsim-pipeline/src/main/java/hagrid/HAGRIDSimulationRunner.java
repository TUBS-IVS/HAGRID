package hagrid;

import hagrid.simulation.HAGRIDScenarioBuilder;
import hagrid.simulation.HAGRIDSimulationConfig;
import hagrid.simulation.HAGRIDSimulationModule;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.geotools.api.feature.simple.SimpleFeature;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.controler.Controler;
import org.matsim.core.utils.gis.GeoFileReader;
import org.matsim.freight.carriers.controller.CarrierModule;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Executes a sequence of HAGRID simulations based on predefined configuration scenarios.
 * <p>
 * For each scenario, input validation is performed prior to simulation. Simulations are
 * executed sequentially, with runtime and result logging for monitoring and debugging.
 */
public class HAGRIDSimulationRunner {

    private static final Logger LOGGER = LogManager.getLogger(HAGRIDSimulationRunner.class);

    /**
     * Entry point for the HAGRID batch simulation runner.
     * <p>
     * Defines and validates one or more simulation configurations, then runs each
     * simulation sequentially.
     *
     * @param args command-line arguments (not used)
     * @throws Exception if any error occurs during the execution of a simulation
     */
    public static void main(String[] args) throws Exception {
        LOGGER.info("===============================================");
        LOGGER.info("HAGRID Batch Simulation Runner Started");
        LOGGER.info("===============================================");

        // Define simulation scenarios to run
        List<HAGRIDSimulationConfig> scenarios = List.of(
                // new HAGRIDSimulationConfig("basecase", LocalDate.of(2025, 5, 12), 150)
                new HAGRIDSimulationConfig("basecase", LocalDate.of(2025, 5, 13), 150)
                // new HAGRIDSimulationConfig("basecase", LocalDate.of(2025, 5, 14), 150),
                // new HAGRIDSimulationConfig("basecase", LocalDate.of(2025, 5, 15), 150),
                // new HAGRIDSimulationConfig("basecase", LocalDate.of(2025, 5, 16), 150),
                // new HAGRIDSimulationConfig("basecase", LocalDate.of(2025, 5, 17), 150)
                // new HAGRIDSimulationConfig("bike_plus", LocalDate.of(2025, 4, 17), 100)
        );

        // Validate input files for all scenarios
        validateAllInputFiles(scenarios);

        // Run simulations sequentially
        for (HAGRIDSimulationConfig simConfig : scenarios) {
            runSingleSimulation(simConfig);
        }

        LOGGER.info("===============================================");
        LOGGER.info("HAGRID Batch Simulation Runner Finished");
        LOGGER.info("===============================================");
    }

    /**
     * Runs a single HAGRID simulation scenario.
     * <p>
     * Loads scenario-specific data, initializes the MATSim scenario and modules,
     * and starts the simulation. Runtime is logged for monitoring purposes.
     *
     * @param simConfig the configuration of the scenario to run
     * @throws Exception if any error occurs during scenario execution
     */
    private static void runSingleSimulation(HAGRIDSimulationConfig simConfig) throws Exception {
        Instant startTime = Instant.now();

        LOGGER.info("------------------------------------------------------------");
        LOGGER.info("Starting simulation for '{}'", simConfig.getRunId());
        LOGGER.info("------------------------------------------------------------");

        // Step 1: Configuration
        LOGGER.info("Step 1/5: Load HAGRID simulation configuration...");
        LOGGER.info("Configuration loaded successfully for runId '{}'", simConfig.getRunId());

        // Step 2: Load freight zones
        LOGGER.info("Step 2/5: Load freight zone shapefile...");
        Collection<SimpleFeature> freightZoneFeatures =
                GeoFileReader.getAllFeatures(simConfig.getFreightZonePath().toString());
        LOGGER.info("Freight zones loaded: {} features", freightZoneFeatures.size());

        // Step 3: Build scenario
        LOGGER.info("Step 3/5: Build MATSim scenario...");
        Scenario scenario = HAGRIDScenarioBuilder.build(simConfig, freightZoneFeatures);
        LOGGER.info("Scenario successfully created");

        // Step 4: Add modules
        LOGGER.info("Step 4/5: Setup simulation modules...");
        Controler controler = new Controler(scenario);
        controler.addOverridingModule(new CarrierModule());
        controler.addOverridingModule(new HAGRIDSimulationModule(scenario, true, simConfig.getMaxIterations()));
        LOGGER.info("Modules successfully added");

        // Step 5: Run simulation
        LOGGER.info("Step 5/5: Run simulation...");
        LOGGER.info("Output directory: {}", simConfig.getOutputDirectoryAsString());
        controler.run();

        // Measure runtime
        Instant endTime = Instant.now();
        Duration duration = Duration.between(startTime, endTime);
        long hours = duration.toHours();
        long minutes = duration.toMinutesPart();
        long seconds = duration.toSecondsPart();
        String runtimeFormatted = String.format("%02d:%02d:%02d", hours, minutes, seconds);

        LOGGER.info("Simulation '{}' completed successfully!", simConfig.getRunId());        
        LOGGER.info("Total runtime: {} (hh:mm:ss)", runtimeFormatted);
        LOGGER.info("------------------------------------------------------------");

        System.gc();  

        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } // gibt GC etwas Luft

    }

    /**
     * Validates the input files for all provided simulation configurations.
     * <p>
     * Aggregates errors from all configurations and throws an exception if any
     * required input is missing.
     *
     * @param configs the list of simulation configurations to validate
     * @throws IllegalStateException if any configuration is missing required input files
     */
    private static void validateAllInputFiles(List<HAGRIDSimulationConfig> configs) {
        LOGGER.info("");
        LOGGER.info("Step 0: Validating input files for all scenarios...");
        List<String> validationErrors = new ArrayList<>();

        for (HAGRIDSimulationConfig config : configs) {
            try {
                config.validateInputFiles();
            } catch (IllegalStateException e) {
                validationErrors.add("[" + config.getRunId() + "] " + e.getMessage());
            }
        }

        if (!validationErrors.isEmpty()) {
            LOGGER.error("---------------------------------------------------");
            LOGGER.error("Input validation failed. Missing input files:");
            LOGGER.error("---------------------------------------------------");
            validationErrors.forEach(LOGGER::error);
            throw new IllegalStateException("Aborting batch run due to missing input files.");
        }

        LOGGER.info("All input files validated successfully.\n");
    }
}
