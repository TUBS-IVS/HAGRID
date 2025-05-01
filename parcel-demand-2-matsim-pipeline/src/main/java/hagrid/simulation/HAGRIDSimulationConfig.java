package hagrid.simulation;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Configuration container for a single HAGRID simulation scenario.
 * <p>
 * Encapsulates paths, parameters, and identifiers used for preparing and running a scenario.
 * Includes functionality for input validation and directory resolution.
 */
public class HAGRIDSimulationConfig {

    private static final Logger LOGGER = LogManager.getLogger(HAGRIDSimulationConfig.class);

    private final Path baseInputDir = Paths.get("C:/Users/bienzeisler/HAGRID/HAGRID/phd/sim-input");
    private final Path baseOutputDir = Paths.get("C:/Users/bienzeisler/HAGRID/HAGRID/phd/sim-output");

    private final String concept;
    private final LocalDate date;
    private final int maxIterations;
    private final String runId;

    /**
     * Constructs a new simulation configuration for a given concept and simulation date.
     *
     * @param concept        the scenario concept name (e.g. "basecase")
     * @param date           the simulation date
     * @param maxIterations  maximum number of MATSim iterations
     */
    public HAGRIDSimulationConfig(String concept, LocalDate date, int maxIterations) {
        this.concept = concept;
        this.date = date;
        this.maxIterations = maxIterations;
        this.runId = concept + "_" + date.format(DateTimeFormatter.ofPattern("ddMMyyyy"));
    }

    // === GETTERS ===

    /**
     * Returns the unique run ID for this scenario (e.g. "basecase_16042025").
     *
     * @return the scenario run ID
     */
    public String getRunId() {
        return runId;
    }

    /**
     * Returns the scenario concept name.
     *
     * @return the scenario concept
     */
    public String getConcept() {
        return concept;
    }

    /**
     * Returns the scenario date as ISO string (e.g. "2025-04-16").
     *
     * @return the formatted date string
     */
    public String getFormattedDate() {
        return date.format(DateTimeFormatter.ISO_DATE);
    }

    /**
     * Returns the path to the freight zone shapefile.
     *
     * @return the freight zone path
     */
    public Path getFreightZonePath() {
        return baseInputDir.resolve("network/RH_useful__zone.shp");
    }

    /**
     * Returns the path to the vehicle type XML.
     *
     * @return the vehicle type definition file path
     */
    public Path getVehicleTypePath() {
        return baseInputDir.resolve("carrier/HAGRID_vehicleTypes2.0.xml");
    }

    /**
     * Returns the path to the car network file.
     *
     * @return the car network file path
     */
    public Path getCarNetworkPath() {
        return getRunCarrierDir().resolve("carFilteredCleanedNetwork.xml.gz");
    }

    /**
     * Returns the path to the bike network file.
     *
     * @return the bike network file path
     */
    public Path getBikeNetworkPath() {
        return baseInputDir.resolve("network/cargobike_network_zones_MH_V3_clean.xml.gz");
    }

    /**
     * Returns the path to the network change events file.
     *
     * @return the change events file path
     */
    public Path getNetworkChangeEventPath() {
        return getRunCarrierDir().resolve("car_network_filtered_V2_change_events.xml.gz");
    }

    /**
     * Returns the directory path containing carrier input files for this run.
     *
     * @return the run-specific carrier directory path
     */
    public Path getRunCarrierDir() {
        return baseInputDir.resolve("carrier/" + runId + "_carrier_files");
    }

    /**
     * Returns the path to the delivery carriers XML.
     *
     * @return the delivery carriers file path
     */
    public Path getDeliveryCarrierPath() {
        return getRunCarrierDir().resolve("delivery_carriers_routed.xml");
    }

    /**
     * Returns the path to the supply carriers XML.
     *
     * @return the supply carriers file path
     */
    public Path getSupplyCarrierPath() {
        return getRunCarrierDir().resolve("supply_carriers_routed.xml");
    }

    /**
     * Returns the path to the merged carriers file.
     *
     * @return the merged carrier plans file path
     */
    public Path getMergedCarrierPath() {
        return getRunCarrierDir().resolve("carrierPlans_total.xml");
    }

    /**
     * Returns the path to the simulation configuration XML.
     *
     * @return the MATSim config file path
     */
    public Path getConfigPath() {
        return baseInputDir.resolve("sim-config.xml");
    }

    /**
     * Returns the directory where simulation output will be written.
     *
     * @return the output directory path
     */
    public Path getOutputDirectory() {
        return baseOutputDir.resolve(runId);
    }

    /**
     * Returns the output directory path as a string.
     *
     * @return output directory as string
     */
    public String getOutputDirectoryAsString() {
        return getOutputDirectory().toString();
    }

    /**
     * Returns the configured maximum number of iterations.
     *
     * @return maximum number of iterations
     */
    public int getMaxIterations() {
        return maxIterations;
    }

    /**
     * Validates that all required input files exist for this scenario.
     * <p>
     * Logs any missing files and throws an {@link IllegalStateException} if any are not found.
     * Also warns if the output directory already exists.
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
            LOGGER.warn("Contents may be overwritten depending on MATSim settings (e.g. OverwriteFileSetting).");
        } else {
            LOGGER.info("Output directory will be created: {}", getOutputDirectory().toAbsolutePath());
        }
    }

    /**
     * Checks if a file exists and adds it to the list of missing files if not found.
     *
     * @param path    path to the file to check
     * @param label   user-readable label for logging
     * @param missing list of missing files to append to
     */
    private void checkFile(Path path, String label, List<String> missing) {
        if (!path.toFile().exists()) {
            missing.add("❌ " + label + ": " + path.toAbsolutePath());
        }
    }
}
