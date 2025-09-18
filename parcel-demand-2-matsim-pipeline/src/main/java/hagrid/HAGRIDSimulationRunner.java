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
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * Executes a sequence of HAGRID simulations based on command line scenario specifications.
 * <p>
 * Each scenario is passed as a single argument with comma separated key value pairs.
 * The runner parses and validates all inputs, constructs configurations, validates
 * scenario inputs, and executes simulations sequentially.
 * <p>
 * Required keys per scenario are {@code concept} and {@code date} with format yyyy-MM-dd.
 * Optional keys are {@code maxIter} for the MATSim iteration budget and {@code jspritIter}
 * for the jsprit iteration budget.
 */
public class HAGRIDSimulationRunner {

    private static final Logger LOGGER = LogManager.getLogger(HAGRIDSimulationRunner.class);

    /** Date format for parsing ISO style dates. */
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

    /** Default MATSim iterations if not specified. */
    private static final int DEFAULT_MAX_ITER = 150;

    /** Default jsprit iterations if not specified. */
    private static final int DEFAULT_JSPRIT_ITER = 100;

    /**
     * Entry point for the HAGRID batch simulation runner.
     * <p>
     * Parses scenario arguments, validates required inputs for each scenario,
     * and runs the simulations sequentially.
     *
     * @param args scenario specifications as key value pairs per argument
     * @throws Exception if any error occurs during simulation execution
     */
    public static void main(String[] args) throws Exception {
        LOGGER.info("===============================================");
        LOGGER.info("HAGRID Batch Simulation Runner Started");
        LOGGER.info("===============================================");

        if (args.length == 0 || containsHelpFlag(args)) {
            printUsage();
            return;
        }

        // Parse all scenarios from the command line
        List<HAGRIDSimulationConfig> scenarios = parseScenarios(args);

        // Validate input files for all scenarios before running any computation
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
     * Parses all scenario arguments into configuration objects.
     *
     * @param args array of scenario specifications
     * @return list of parsed configurations
     */
    private static List<HAGRIDSimulationConfig> parseScenarios(String[] args) {
        List<HAGRIDSimulationConfig> scenarios = new ArrayList<>();
        for (String arg : args) {
            HAGRIDSimulationConfig cfg = parseSingleScenario(arg.trim());
            scenarios.add(cfg);
        }
        LOGGER.info("Parsed {} scenario(s) from command line", scenarios.size());
        return scenarios;
    }

    /**
     * Parses one scenario specification of the form
     * {@code concept=...,date=...,maxIter=...,jspritIter=...}.
     *
     * @param spec scenario specification string
     * @return parsed configuration
     * @throws IllegalArgumentException if required keys are missing or invalid
     */
    private static HAGRIDSimulationConfig parseSingleScenario(String spec) {
        if (spec.isEmpty()) {
            throw new IllegalArgumentException("Empty scenario specification");
        }

        Map<String, String> map = new LinkedHashMap<>();
        String[] tokens = spec.split(",");
        for (String token : tokens) {
            String[] kv = token.split("=", 2);
            if (kv.length != 2) {
                throw new IllegalArgumentException("Invalid token in scenario specification: " + token);
            }
            String key = kv[0].trim();
            String value = kv[1].trim();
            if (key.isEmpty() || value.isEmpty()) {
                throw new IllegalArgumentException("Empty key or value in token: " + token);
            }
            map.put(key, value);
        }

        String concept = require(map, "concept");
        String dateStr = require(map, "date");
        LocalDate date;
        try {
            date = LocalDate.parse(dateStr, DATE_FMT);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("Invalid date. Use yyyy-MM-dd. Got " + dateStr, ex);
        }

        int maxIter = parsePositiveInt(map.getOrDefault("maxIter", String.valueOf(DEFAULT_MAX_ITER)), "maxIter");
        int jspritIter = parsePositiveInt(map.getOrDefault("jspritIter", String.valueOf(DEFAULT_JSPRIT_ITER)), "jspritIter");

        LOGGER.info("Scenario parsed concept={} date={} maxIter={} jspritIter={}", concept, date, maxIter, jspritIter);
        return new HAGRIDSimulationConfig(concept, date, maxIter, jspritIter);
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
                LOGGER.info("Validating scenario {}", config.getRunId());
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

        LOGGER.info("All input files validated successfully.");
        LOGGER.info("");
    }

    /**
     * Runs a single HAGRID simulation scenario.
     * <p>
     * Loads scenario specific data, initializes the MATSim scenario and modules,
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
        LOGGER.info("Configuration loaded for runId '{}'", simConfig.getRunId());

        // Step 2: Load freight zones
        LOGGER.info("Step 2/5: Load freight zone shapefile...");
        Collection<SimpleFeature> freightZoneFeatures =
                GeoFileReader.getAllFeatures(simConfig.getFreightZonePath().toString());
        LOGGER.info("Freight zones loaded: {} features", freightZoneFeatures.size());

        // Step 3: Build scenario
        LOGGER.info("Step 3/5: Build MATSim scenario...");
        Scenario scenario = HAGRIDScenarioBuilder.build(simConfig, freightZoneFeatures);
        LOGGER.info("Scenario created");

        // Step 4: Add modules
        LOGGER.info("Step 4/5: Setup simulation modules...");
        Controler controler = new Controler(scenario);
        controler.addOverridingModule(new CarrierModule());
        controler.addOverridingModule(
                new HAGRIDSimulationModule(scenario, true, simConfig.getMaxIterations(), simConfig.getJspritIterations())
        );
        LOGGER.info("Modules added");

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

        LOGGER.info("Simulation '{}' completed", simConfig.getRunId());
        LOGGER.info("Total runtime: {} (hh:mm:ss)", runtimeFormatted);
        LOGGER.info("------------------------------------------------------------");

        // Encourage early reclamation of large temporary structures between runs
        System.gc();

        // Give GC a short head start without blocking the pipeline for long
        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            LOGGER.warn("Sleep after GC was interrupted");
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Reads a required key from the map.
     *
     * @param map key value map
     * @param key required key
     * @return value string
     * @throws IllegalArgumentException if the key is missing
     */
    private static String require(Map<String, String> map, String key) {
        String v = map.get(key);
        if (v == null) {
            throw new IllegalArgumentException("Missing required key " + key);
        }
        return v;
    }

    /**
     * Parses a positive integer value.
     *
     * @param s    string value
     * @param name label for error messages
     * @return parsed positive integer
     * @throws IllegalArgumentException if the value is not a positive integer
     */
    private static int parsePositiveInt(String s, String name) {
        try {
            int v = Integer.parseInt(s);
            if (v <= 0) {
                throw new IllegalArgumentException(name + " must be positive but was " + v);
            }
            return v;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid integer for " + name + ": " + s, ex);
        }
    }

    /**
     * Checks whether the argument list contains a help request.
     *
     * @param args raw arguments
     * @return true if help is requested
     */
    private static boolean containsHelpFlag(String[] args) {
        for (String a : args) {
            String s = a.trim().toLowerCase(Locale.ROOT);
            if (s.equals("help") || s.equals("--help") || s.equals("-h")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Prints a concise usage description to the log.
     */
    private static void printUsage() {
        LOGGER.info("Usage");
        LOGGER.info("  java -cp target/classes hagrid.HAGRIDSimulationRunner <scenario> [<scenario> ...]");
        LOGGER.info("  Each <scenario> is a comma separated list of key=value items");
        LOGGER.info("  Required keys");
        LOGGER.info("    concept      scenario concept name");
        LOGGER.info("    date         simulation date in yyyy-MM-dd");
        LOGGER.info("  Optional keys");
        LOGGER.info("    maxIter      MATSim iterations default {}", DEFAULT_MAX_ITER);
        LOGGER.info("    jspritIter   jsprit iterations default {}", DEFAULT_JSPRIT_ITER);
        LOGGER.info("  Example");
        LOGGER.info("    java -cp target/classes hagrid.HAGRIDSimulationRunner concept=basecase,date=2025-05-15,maxIter=150,jspritIter=100");
    }
}
