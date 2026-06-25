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

import hagrid.HagridConfig;
import hagrid.HagridPaths;
import hagrid.utils.general.StudyArea;

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
    private final HagridPaths paths;

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
     * Soft U-turn penalty in score/cost units. Applied both during JSprit
     * route optimization and in the MATSim carrier scoring function.
     * Each detected U-turn subtracts this value from the MATSim utility score.
     * 0 = disabled.
     */
    private final double uTurnPenaltyCost;

    /**
     * Optional version tag appended to the run ID (empty string if not set).
     */
    private final String tag;

    /**
     * Unique run identifier, composed of the concept, date, and optional tag.
     */
    private final String runId;

    /**
     * Geographic study area for this scenario.
     */
    private final StudyArea studyArea;

    /**
     * DRT fleet size (number of vehicles). Only meaningful for DRT scenarios.
     */
    private final int fleetSize;

    /**
     * Creates a new scenario configuration, defaulting to {@link StudyArea#HANNOVER} and fleet size 50.
     *
     * @param concept          scenario concept name
     * @param date             simulation date
     * @param maxIterations    maximum number of MATSim iterations
     * @param jspritIterations maximum number of jsprit iterations
     * @param tag              optional version tag (null or empty to disable)
     * @throws NullPointerException     if concept or date is null
     * @throws IllegalArgumentException if maxIterations or jspritIterations are not positive
     */
    public HAGRIDSimulationConfig(String concept, LocalDate date, int maxIterations, int jspritIterations,
                          boolean zoneBasedCachingEnabled, double zoneBasedCachingThresholdMeters,
                          double uTurnPenaltyCost, String tag) {
        this(concept, date, maxIterations, jspritIterations,
                zoneBasedCachingEnabled, zoneBasedCachingThresholdMeters,
                uTurnPenaltyCost, tag, StudyArea.HANNOVER, 50);
    }

    /**
     * Creates a new scenario configuration with explicit study area and DRT fleet size.
     *
     * @param concept          scenario concept name
     * @param date             simulation date
     * @param maxIterations    maximum number of MATSim iterations
     * @param jspritIterations maximum number of jsprit iterations
     * @param tag              optional version tag (null or empty to disable)
     * @param studyArea        geographic study area
     * @param fleetSize        DRT fleet size (number of vehicles)
     * @throws NullPointerException     if concept, date, or studyArea is null
     * @throws IllegalArgumentException if maxIterations or jspritIterations are not positive
     */
    public HAGRIDSimulationConfig(String concept, LocalDate date, int maxIterations, int jspritIterations,
                          boolean zoneBasedCachingEnabled, double zoneBasedCachingThresholdMeters,
                          double uTurnPenaltyCost, String tag, StudyArea studyArea, int fleetSize) {
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
        this.uTurnPenaltyCost = Math.max(0.0, uTurnPenaltyCost);
        this.tag = tag != null ? tag.trim() : "";
        this.studyArea = Objects.requireNonNull(studyArea, "studyArea must not be null");
        this.fleetSize = fleetSize;
        String baseRunId = concept.toUpperCase() + "_" + date.format(RUN_ID_DATE_FMT);
        this.runId = this.tag.isEmpty() ? baseRunId : baseRunId + "_" + this.tag;
        this.paths = new HagridPaths(studyArea);
        this.paths.initializeRun(runId);

        // Ensure shared simulation inputs are available in hagrid-output/shared/
        try {
            this.paths.ensureSharedSimulationInputs();
        } catch (java.io.IOException e) {
            LOGGER.warn("Could not ensure shared simulation inputs: {}", e.getMessage());
        }
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
     * Returns the optional version tag (empty string if not set).
     *
     * @return version tag
     */
    public String getTag() {
        return tag;
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
     * Returns the soft U-turn penalty in score/cost units.
     * Each detected U-turn subtracts this value from the utility score.
     * 0 = disabled.
     */
    public double getUTurnPenaltyCost() {
        return uTurnPenaltyCost;
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

    // === DRT / STUDY AREA GETTERS ===

    /**
     * Returns the geographic study area for this scenario.
     *
     * @return study area
     */
    public StudyArea getStudyArea() {
        return studyArea;
    }

    /**
     * Returns the DRT fleet size (number of vehicles).
     * Only meaningful for DRT scenarios.
     *
     * @return fleet size
     */
    public int getFleetSize() {
        return fleetSize;
    }

    /**
     * Returns {@code true} when the concept maps to a DRT scenario
     * ({@link HagridConfig.Scenario#isDrt()} returns true).
     * Returns {@code false} for unknown concept strings.
     *
     * @return true if this is a DRT scenario
     */
    public boolean isDrtScenario() {
        try {
            return HagridConfig.Scenario.valueOf(concept.toUpperCase()).isDrt();
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    /** True iff the concept maps to the dedicated Lausitz LMD baseline. */
    public boolean isLmdBaseline() {
        try {
            return HagridConfig.Scenario.valueOf(concept.toUpperCase())
                    == HagridConfig.Scenario.LMD_BASELINE;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    /**
     * Returns the path to the DRT service-area shapefile.
     *
     * @return DRT service area shapefile path
     */
    public String getDrtServiceAreaShapefile() {
        return paths.drtServiceAreaShapefile();
    }

    /**
     * Returns the path to the native Lausitz base config
     * (scoring/activity-param source for DRT runs).
     *
     * @return Lausitz base config path
     */
    public String getLausitzBaseConfig() {
        return paths.lausitzBaseConfig();
    }

    /**
     * Returns the path to the raw Lausitz network file.
     *
     * @return raw Lausitz network path
     */
    public String getLausitzNetworkRaw() {
        return paths.lausitzNetworkRaw();
    }

    /**
     * Returns the path to the raw passenger plans file.
     *
     * @return raw passenger plans path
     */
    public String getPassengerPlansRaw() {
        return paths.passengerPlansRaw();
    }

    /**
     * Returns the path to the clipped DRT network file.
     *
     * @return clipped DRT network path
     */
    public String getDrtNetworkClipped() {
        return paths.drtNetworkClipped();
    }

    /**
     * Returns the path to the clipped passenger plans file.
     *
     * @return clipped passenger plans path
     */
    public String getPassengerPlansClipped() {
        return paths.passengerPlansClipped();
    }

    /**
     * Returns the path to the DRT fleet file.
     *
     * @return DRT fleet file path
     */
    public String getDrtFleetFile() {
        return paths.drtFleetFile();
    }

    /** Staged native transit schedule (full) before rail-filtering. */
    public String getLausitzTransitScheduleRaw() {
        return paths.lausitzTransitScheduleRaw();
    }

    /** Staged native transit vehicles (full) before rail-filtering. */
    public String getLausitzTransitVehiclesRaw() {
        return paths.lausitzTransitVehiclesRaw();
    }

    /** Staged native passenger vehicle-types (enables modeVehicleTypesFromVehiclesData). */
    public String getLausitzVehicleTypes() {
        return paths.lausitzVehicleTypes();
    }

    /** Rail-only transit schedule for this run. */
    public String getRailScheduleFiltered() {
        return paths.railScheduleFiltered();
    }

    /** Transit vehicles referenced by the rail-only schedule. */
    public String getRailTransitVehiclesFiltered() {
        return paths.railTransitVehiclesFiltered();
    }

    // === VALIDATION ===

    /**
     * Validates that all required input files exist for this scenario and reports
     * the state of the output directory. Throws an exception if required inputs
     * are missing.
     */
    public void validateInputFiles() {
        List<String> missing = new ArrayList<>();

        if (!isDrtScenario()) {
            checkFile(getConfigPath(), "Simulation config", missing);
            checkFile(getVehicleTypePath(), "Vehicle types", missing);
            checkFile(getCarNetworkPath(), "Car network", missing);
            checkFile(getBikeNetworkPath(), "Bike network", missing);
            checkFile(getNetworkChangeEventPath(), "Network change events", missing);
            checkFile(getFreightZonePath(), "Freight zone shapefile", missing);
            checkFile(getDeliveryCarrierPath(), "Delivery carriers", missing);
            checkFile(getSupplyCarrierPath(), "Supply carriers", missing);
        }

        if (isDrtScenario()) {
            checkFile(Path.of(getDrtNetworkClipped()), "DRT network (clipped)", missing);
            checkFile(Path.of(getPassengerPlansClipped()), "Passenger plans (clipped)", missing);
            checkFile(Path.of(getDrtFleetFile()), "DRT fleet file", missing);
            checkFile(Path.of(getDrtServiceAreaShapefile()), "DRT service area", missing);
            checkFile(Path.of(getLausitzBaseConfig()), "Lausitz base config", missing);
            // Rail PT is the standard layer for every Lausitz DRT scenario (run(cfg) always supplies
            // the rail/vehicle-types getters), so these raw inputs are required for a real run. The
            // null-rail DRT-only path is exercised only by unit tests, which bypass validateInputFiles().
            checkFile(Path.of(getLausitzTransitScheduleRaw()), "Lausitz transit schedule (raw)", missing);
            checkFile(Path.of(getLausitzTransitVehiclesRaw()), "Lausitz transit vehicles (raw)", missing);
            checkFile(Path.of(getLausitzVehicleTypes()), "Lausitz vehicle-types", missing);
        }

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
