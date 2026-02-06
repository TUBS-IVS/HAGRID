package hagrid.simulation;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import hagrid.HagridPaths;

/**
 * Container for configuration parameters of a single HAGRID simulation scenario.
 * <p>
 * The configuration includes input and output directories, run identifiers,
 * and iteration settings for both MATSim and jsprit. It also provides
 * convenience methods to resolve scenario specific file paths and validate
 * the existence of required input data.
 */
public class HAGRIDSimulationConfig {

    private static final Logger LOGGER = LogManager.getLogger(HAGRIDSimulationConfig.class);

    /**
     * Formatter used to generate the run identifier from the simulation date.
     */
    private static final DateTimeFormatter RUN_ID_DATE_FMT = DateTimeFormatter.ofPattern("ddMMyyyy");

    /**
     * Centralized path manager for all pipeline I/O.
     */
<<<<<<< Updated upstream
    private final Path baseInputDir = Paths.get("sim-input").toAbsolutePath();

    /**
     * Base directory where simulation outputs will be written.
     */
    private final Path baseOutputDir = Paths.get("sim-output").toAbsolutePath();
=======
    private final HagridPaths paths;
>>>>>>> Stashed changes

    /**
     * Scenario concept name, e.g. "basecase" or "policyA".
     */
    private final String concept;

    /**
     * Simulation date used to construct the run identifier and to select
     * dated input files.
     */
    private final LocalDate date;

    /**
     * Maximum number of MATSim iterations for this scenario.
     */
    private final int maxIterations;

    /**
     * Maximum number of jsprit iterations for the carrier routing step.
     */
    private final int jspritIterations;

    /**
     * Enables aggregation of transport cost cache entries by freight zone when true.
     */
    private final boolean zoneBasedCachingEnabled;

    /**
     * Minimum crow-fly distance (in metres) required to use zone-based caching between distinct zones.
     */
    private final double zoneBasedCachingThresholdMeters;

    /**
     * Soft U-turn penalty in cost units (≈ seconds). Applied both during JSprit
     * route optimization and in the MATSim carrier scoring function.
     * 0 = disabled.
     */
    private final double uTurnPenaltySeconds;

    /**
     * Unique run identifier, composed of the concept and the date.
     */
    private final String runId;

    /**
     * Creates a new scenario configuration.
     *
     * @param concept          scenario concept name
     * @param date             simulation date
     * @param maxIterations    maximum number of MATSim iterations
     * @param jspritIterations maximum number of jsprit iterations
     * @throws NullPointerException     if concept or date is null
     * @throws IllegalArgumentException if maxIterations or jspritIterations are not positive
     */
    public HAGRIDSimulationConfig(String concept, LocalDate date, int maxIterations, int jspritIterations,
                          boolean zoneBasedCachingEnabled, double zoneBasedCachingThresholdMeters,
                          double uTurnPenaltySeconds) {
        this.concept = Objects.requireNonNull(concept, "concept must not be null");
        this.date = Objects.requireNonNull(date, "date must not be null");
        if (maxIterations <= 0) {
            throw new IllegalArgumentException("maxIterations must be positive");
        }
        if (jspritIterations <= 0) {
            throw new IllegalArgumentException("jspritIterations must be positive");
        }
        if (zoneBasedCachingThresholdMeters < 0) {
            throw new IllegalArgumentException("zoneBasedCachingThresholdMeters must be >= 0");
        }
        this.maxIterations = maxIterations;
        this.jspritIterations = jspritIterations;
        this.zoneBasedCachingEnabled = zoneBasedCachingEnabled;
        this.zoneBasedCachingThresholdMeters = zoneBasedCachingThresholdMeters;
        this.uTurnPenaltySeconds = Math.max(0.0, uTurnPenaltySeconds);
        this.runId = concept.toUpperCase() + "_" + date.format(RUN_ID_DATE_FMT);
        this.paths = new HagridPaths();
        this.paths.initializeRun(runId);
    }

    // === GETTERS ===

    /**
     * Returns the unique run identifier of this scenario.
     *
     * @return run identifier
     */
    public String getRunId() {
        return runId;
    }

    /**
     * Returns the scenario concept name.
     *
     * @return concept name
     */
    public String getConcept() {
        return concept;
    }

    /**
     * Returns the scenario date formatted as ISO string.
     *
     * @return formatted date string
     */
    public String getFormattedDate() {
        return date.format(DateTimeFormatter.ISO_DATE);
    }

    /**
     * Returns the configured maximum number of MATSim iterations.
     *
     * @return MATSim iteration count
     */
    public int getMaxIterations() {
        return maxIterations;
    }

    /**
     * Returns the configured maximum number of jsprit iterations.
     *
     * @return jsprit iteration count
     */
    public int getJspritIterations() {
        return jspritIterations;
    }

    /**
     * Indicates whether zone-based transport-cost caching should be used for this scenario.
     */
    public boolean isZoneBasedCachingEnabled() {
        return zoneBasedCachingEnabled;
    }

    /**
     * Returns the minimum Euclidean distance (in metres) required to aggregate transport costs by zone.
     */
    public double getZoneBasedCachingThresholdMeters() {
        return zoneBasedCachingThresholdMeters;
    }

    /**
     * Returns the soft U-turn penalty in cost units (≈ seconds).
     * 0 = disabled.
     */
    public double getUTurnPenaltySeconds() {
        return uTurnPenaltySeconds;
    }

    /**
     * Returns the path to the freight zone shapefile.
     *
     * @return freight zone file path
     */
    public Path getFreightZonePath() {
        return Path.of(paths.zoneShapefile());
    }

    /**
     * Returns the path to the run-specific vehicle type definition XML
     * (generated by CarrierGenerator into hagrid-output/{RUN_ID}/vehicles/).
     *
     * @return vehicle types file path for this run
     */
    public Path getVehicleTypePath() {
        return Path.of(paths.vehicleTypesOutput());
    }

    /**
     * Returns the path to the car network file for this run.
     *
     * @return car network file path
     */
    public Path getCarNetworkPath() {
        return Path.of(paths.networkFiltered());
    }

    /**
     * Returns the path to the bike network file.
     *
     * @return bike network file path
     */
    public Path getBikeNetworkPath() {
        return Path.of(paths.cargobikeNetworkFile());
    }

    /**
     * Returns the path to the network change events file for this run.
     *
     * @return network change events file path
     */
    public Path getNetworkChangeEventPath() {
        return Path.of(paths.networkChangeEvents());
    }

    /**
     * Returns the directory with carrier files for this run.
     *
     * @return run specific carrier directory
     */
    public Path getRunCarrierDir() {
        return paths.carrierDir();
    }

    /**
     * Returns the path to the delivery carriers XML.
     *
     * @return delivery carriers file path
     */
    public Path getDeliveryCarrierPath() {
        return Path.of(paths.deliveryCarriersRouted());
    }

    /**
     * Returns the path to the supply carriers XML.
     *
     * @return supply carriers file path
     */
    public Path getSupplyCarrierPath() {
        return Path.of(paths.supplyCarriersRouted());
    }

    /**
     * Returns the path to the merged carriers file.
     *
     * @return merged carriers file path
     */
    public Path getMergedCarrierPath() {
        return Path.of(paths.carrierPlansCombined());
    }

    /**
     * Returns the path to the MATSim configuration XML.
     *
     * @return MATSim config file path
     */
    public Path getConfigPath() {
        return Path.of(paths.matsimConfigFile());
    }

    /**
     * Returns the directory where output for this run will be written.
     * <p>
     * Located under {@code hagrid-matsim-output/{runId}_iter{N}_jsprit{M}}
     * to avoid collisions between different iteration budgets.
     *
     * @return output directory path
     */
    public Path getOutputDirectory() {
        return paths.matsimRunDir(maxIterations, jspritIterations);
    }

    /**
     * Returns the output directory as a string.
     *
     * @return output directory string
     */
    public String getOutputDirectoryAsString() {
        return getOutputDirectory().toString();
    }

    // === VALIDATION ===

    /**
     * Validates that all required input files exist for this scenario and reports
     * the state of the output directory. Throws an exception if required inputs
     * are missing.
     */
    public void validateInputFiles() {
        List<String> missing = new ArrayList<>();

        checkFile(getConfigPath(), "Simulation config", missing);
        checkFile(getVehicleTypePath(), "Vehicle types", missing);
        checkFile(getCarNetworkPath(), "Car network", missing);
        checkFile(getBikeNetworkPath(), "Bike network", missing);
        checkFile(getNetworkChangeEventPath(), "Network change events", missing);
        checkFile(getFreightZonePath(), "Freight zone shapefile", missing);
        checkFile(getDeliveryCarrierPath(), "Delivery carriers", missing);
        checkFile(getSupplyCarrierPath(), "Supply carriers", missing);

        if (!missing.isEmpty()) {
            throw new IllegalStateException("Missing required input files:\n" + String.join("\n", missing));
        }

        if (getOutputDirectory().toFile().exists()) {
            LOGGER.warn("Output directory already exists: {}", getOutputDirectory().toAbsolutePath());
            LOGGER.warn("Contents may be overwritten depending on MATSim settings such as OverwriteFileSetting.");
        } else {
            LOGGER.info("Output directory will be created: {}", getOutputDirectory().toAbsolutePath());
        }
    }

    /**
     * Verifies the existence of a file and records it as missing if not found.
     * <p>
     * Used by {@link #validateInputFiles()} to collect missing file messages.
     *
     * @param path    the file path to be checked
     * @param label   descriptive label for the file, used in log messages
     * @param missing list that collects descriptions of missing files
     */
    private void checkFile(Path path, String label, List<String> missing) {
        if (!path.toFile().exists()) {
            missing.add("Missing " + label + ": " + path.toAbsolutePath());
        }
    }
}
