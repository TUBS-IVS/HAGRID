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
     * Base directory containing all input resources for the simulation pipeline.
     */
    private final Path baseInputDir = Paths.get("C:/Users/bienzeisler/HAGRID/HAGRID/parcel-demand-2-matsim-pipeline/sim-input");

    /**
     * Base directory where simulation outputs will be written.
     */
    private final Path baseOutputDir = Paths.get("C:/Users/bienzeisler/HAGRID/HAGRID/parcel-demand-2-matsim-pipeline/sim-output");

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
                          boolean zoneBasedCachingEnabled, double zoneBasedCachingThresholdMeters) {
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
        this.runId = concept + "_" + date.format(RUN_ID_DATE_FMT);
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
     * Returns the path to the freight zone shapefile.
     *
     * @return freight zone file path
     */
    public Path getFreightZonePath() {
        return baseInputDir.resolve("network/RH_useful__zone.shp");
    }

    /**
     * Returns the path to the vehicle type definition XML.
     *
     * @return vehicle types file path
     */
    public Path getVehicleTypePath() {
        return baseInputDir.resolve("carrier/HAGRID_vehicleTypes2.0.xml");
    }

    /**
     * Returns the path to the car network file for this run.
     *
     * @return car network file path
     */
    public Path getCarNetworkPath() {
        return getRunCarrierDir().resolve("carFilteredCleanedNetwork.xml.gz");
    }

    /**
     * Returns the path to the bike network file.
     *
     * @return bike network file path
     */
    public Path getBikeNetworkPath() {
        return baseInputDir.resolve("network/cargobike_network_zones_MH_V3_clean.xml.gz");
    }

    /**
     * Returns the path to the network change events file for this run.
     *
     * @return network change events file path
     */
    public Path getNetworkChangeEventPath() {
        return getRunCarrierDir().resolve("car_network_filtered_V2_change_events.xml.gz");
    }

    /**
     * Returns the directory with carrier input files for this run.
     *
     * @return run specific carrier directory
     */
    public Path getRunCarrierDir() {
        return baseInputDir.resolve("carrier/" + runId + "_carrier_files");
    }

    /**
     * Returns the path to the delivery carriers XML.
     *
     * @return delivery carriers file path
     */
    public Path getDeliveryCarrierPath() {
        return getRunCarrierDir().resolve("delivery_carriers_routed.xml");
    }

    /**
     * Returns the path to the supply carriers XML.
     *
     * @return supply carriers file path
     */
    public Path getSupplyCarrierPath() {
        return getRunCarrierDir().resolve("supply_carriers_routed.xml");
    }

    /**
     * Returns the path to the merged carriers file.
     *
     * @return merged carriers file path
     */
    public Path getMergedCarrierPath() {
        return getRunCarrierDir().resolve("carrierPlans_total.xml");
    }

    /**
     * Returns the path to the MATSim configuration XML.
     *
     * @return MATSim config file path
     */
    public Path getConfigPath() {
        return baseInputDir.resolve("sim-config.xml");
    }

    /**
     * Returns the directory where output for this run will be written.
     * <p>
     * The directory is derived from the base output path and the run identifier,
     * extended with iteration settings to avoid collisions when running the
     * same scenario with different MATSim or jsprit iteration budgets.
     * <p>
     * Example: {@code sim-output/basecase_12052025_iter150_jsprit100}
     *
     * @return output directory path
     */
    public Path getOutputDirectory() {
        return baseOutputDir.resolve(
                String.format("%s_iter%d_jsprit%d", runId, maxIterations, jspritIterations)
        );
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
