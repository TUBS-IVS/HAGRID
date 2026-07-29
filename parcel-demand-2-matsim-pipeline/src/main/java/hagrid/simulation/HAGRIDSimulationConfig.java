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
import hagrid.integrated.modular.Modular;
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
     * Whether a DRT run also carries the offline-routed LMD carriers in the SAME Controler
     * (the "married" Baseline). Only meaningful when {@link #isDrtScenario()} is true.
     */
    private final boolean drtWithFreight;

    /**
     * Scenario option {@code kpiDashboard} (default true): whether to build the Python
     * KPI dashboard after the run completes.
     */
    private final boolean kpiDashboard;

    /**
     * χ-gate threshold (seconds): the maximum acceptable DETOUR-ONLY added vehicle time for a
     * single parcel insertion — the gate subtracts the request's own depot-pickup + door-dropoff
     * dwell from the raw {@code totalTimeLoss} before comparing (rev. 2026-07-27). &lt; 0 = gate
     * closed (rejects all parcels; leakage-control probe). Only meaningful for
     * {@code DRT_SHAREDUSE}, where it is passed to {@code SharedUseModule}'s
     * {@code ChiGateInsertionCostCalculator}; harmless for every other concept.
     *
     * <p><b>Output-collision warning (I2/M5):</b> this value is NOT part of the runId
     * ({@code CONCEPT_date[_tag]}), and MATSim deletes an existing output directory at startup —
     * so the {@code tag} MUST encode the sweep point (e.g. {@code tag=chi600}) or two χ sweep
     * runs will silently overwrite each other. {@code SimulationRunnerUtils.parseScenario}
     * rejects a {@code DRT_SHAREDUSE} spec with a blank tag for exactly this reason, and
     * {@code RunMetadataWriter} persists the value as {@code chi_threshold} in
     * {@code run_metadata.json} so finished runs stay attributable.</p>
     */
    private final double chiThreshold;

    /**
     * Reference-run switch (default {@code false}): when {@code true}, a {@code DRT_SHAREDUSE}
     * run skips the parcel-subpopulation injection entirely, yielding an 8-seat DRT with the
     * Shared-Use module stack installed but inert (no parcel ever exists to gate, retry or
     * count). This is the leakage control for the χ→0 validation (Task 10 / D10 (e)): comparing
     * its pax KPIs against a χ=0 run (parcels present but never boarding) isolates exactly one
     * variable — the mere presence of parcel-agents in the QSim — with everything else held
     * identical. Ignored for every non-{@code DRT_SHAREDUSE} concept (they never inject parcels).
     */
    private final boolean noParcels;

    /**
     * MATSim global random seed; vary for error-band replicate runs. Applied to
     * {@code config.global().setRandomSeed(...)} on every simulation path (review F3);
     * default 1337. The jsprit seed is a separate system-property override and is NOT
     * affected. Persisted as {@code matsim_seed} in {@code run_metadata.json} so
     * sweep/error-band assembly can bind replicates to their seed.
     */
    private final long seed;

    /**
     * Passenger-first dispatch gate (design D6, spec §6.1): the DRT_MODULAR tour dispatcher only
     * commits a vehicle to a freight excursion while the idle-vehicle SHARE strictly exceeds this
     * threshold. {@code 0.0} dispatches whenever any vehicle is idle; {@code 1.0} is the
     * never-dispatch control arm. Only meaningful for {@code DRT_MODULAR}, harmless otherwise.
     */
    private final double idleThreshold;

    /**
     * Jsprit tour-duration cap in seconds (design D5): the maximum route duration a single
     * DRT_MODULAR freight excursion may plan for. Default {@link Modular#DEFAULT_MAX_TOUR_DURATION_S}
     * (3.5h); 25200 (7h) is the control arm. Only meaningful for {@code DRT_MODULAR}, harmless
     * otherwise.
     */
    private final int maxTourDurationSeconds;

    /**
     * Creates a new scenario configuration, defaulting to {@link StudyArea#HANNOVER} and fleet size 50.
     *
     * @param concept          scenario concept name
     * @param date             simulation date
     * @param maxIterations    maximum number of MATSim iterations
     * @param jspritIterations maximum number of jsprit iterations
     * @param tag              optional version tag (null or empty to disable)
     * @throws NullPointerException     if concept or date is null
     * @throws IllegalArgumentException if maxIterations &lt; 0; if maxIterations == 0 and the concept is not
     *                                  {@code LMD_BASELINE}; or if jspritIterations is not positive (&gt;= 1)
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
     * @throws IllegalArgumentException if maxIterations &lt; 0; if maxIterations == 0 and the concept is not
     *                                  {@code LMD_BASELINE}; or if jspritIterations is not positive (&gt;= 1)
     */
    public HAGRIDSimulationConfig(String concept, LocalDate date, int maxIterations, int jspritIterations,
                          boolean zoneBasedCachingEnabled, double zoneBasedCachingThresholdMeters,
                          double uTurnPenaltyCost, String tag, StudyArea studyArea, int fleetSize) {
        this(concept, date, maxIterations, jspritIterations,
                zoneBasedCachingEnabled, zoneBasedCachingThresholdMeters,
                uTurnPenaltyCost, tag, studyArea, fleetSize, /*drtWithFreight*/ true);
    }

    /**
     * Creates a new scenario configuration with explicit study area, DRT fleet size, and
     * married-freight flag, defaulting {@code kpiDashboard} to {@code true}.
     *
     * @param concept          scenario concept name
     * @param date             simulation date
     * @param maxIterations    maximum number of MATSim iterations
     * @param jspritIterations maximum number of jsprit iterations
     * @param tag              optional version tag (null or empty to disable)
     * @param studyArea        geographic study area
     * @param fleetSize        DRT fleet size (number of vehicles)
     * @param drtWithFreight   whether a DRT run also carries the offline-routed LMD carriers
     *                         (married Baseline); ignored for non-DRT concepts
     * @throws NullPointerException     if concept, date, or studyArea is null
     * @throws IllegalArgumentException if maxIterations &lt; 0; if maxIterations == 0 and the concept is not
     *                                  {@code LMD_BASELINE}; or if jspritIterations is not positive (&gt;= 1)
     */
    public HAGRIDSimulationConfig(String concept, LocalDate date, int maxIterations, int jspritIterations,
                          boolean zoneBasedCachingEnabled, double zoneBasedCachingThresholdMeters,
                          double uTurnPenaltyCost, String tag, StudyArea studyArea, int fleetSize,
                          boolean drtWithFreight) {
        this(concept, date, maxIterations, jspritIterations,
                zoneBasedCachingEnabled, zoneBasedCachingThresholdMeters,
                uTurnPenaltyCost, tag, studyArea, fleetSize, drtWithFreight, /*kpiDashboard*/ true);
    }

    /**
     * Creates a new scenario configuration with explicit study area, DRT fleet size,
     * married-freight flag, and KPI-dashboard trigger.
     *
     * @param concept          scenario concept name
     * @param date             simulation date
     * @param maxIterations    maximum number of MATSim iterations
     * @param jspritIterations maximum number of jsprit iterations
     * @param tag              optional version tag (null or empty to disable)
     * @param studyArea        geographic study area
     * @param fleetSize        DRT fleet size (number of vehicles)
     * @param drtWithFreight   whether a DRT run also carries the offline-routed LMD carriers
     *                         (married Baseline); ignored for non-DRT concepts
     * @param kpiDashboard     whether to build the Python KPI dashboard after the run completes
     * @throws NullPointerException     if concept, date, or studyArea is null
     * @throws IllegalArgumentException if maxIterations &lt; 0; if maxIterations == 0 and the concept is not
     *                                  {@code LMD_BASELINE}; or if jspritIterations is not positive (&gt;= 1)
     */
    public HAGRIDSimulationConfig(String concept, LocalDate date, int maxIterations, int jspritIterations,
                          boolean zoneBasedCachingEnabled, double zoneBasedCachingThresholdMeters,
                          double uTurnPenaltyCost, String tag, StudyArea studyArea, int fleetSize,
                          boolean drtWithFreight, boolean kpiDashboard) {
        this(concept, date, maxIterations, jspritIterations,
                zoneBasedCachingEnabled, zoneBasedCachingThresholdMeters,
                uTurnPenaltyCost, tag, studyArea, fleetSize, drtWithFreight, kpiDashboard,
                /*chiThreshold*/ 600.0);
    }

    /**
     * Creates a new scenario configuration with explicit study area, DRT fleet size,
     * married-freight flag, KPI-dashboard trigger, and χ-gate threshold.
     *
     * @param concept          scenario concept name
     * @param date             simulation date
     * @param maxIterations    maximum number of MATSim iterations
     * @param jspritIterations maximum number of jsprit iterations
     * @param tag              optional version tag (null or empty to disable)
     * @param studyArea        geographic study area
     * @param fleetSize        DRT fleet size (number of vehicles)
     * @param drtWithFreight   whether a DRT run also carries the offline-routed LMD carriers
     *                         (married Baseline); ignored for non-DRT concepts
     * @param kpiDashboard     whether to build the Python KPI dashboard after the run completes
     * @param chiThreshold     χ-gate threshold (seconds, detour-only; &lt; 0 = gate closed,
     *                         rejects all parcels); only meaningful for {@code DRT_SHAREDUSE}
     * @throws NullPointerException     if concept, date, or studyArea is null
     * @throws IllegalArgumentException if maxIterations &lt; 0; if maxIterations == 0 and the concept is not
     *                                  {@code LMD_BASELINE}; or if jspritIterations is not positive (&gt;= 1)
     */
    public HAGRIDSimulationConfig(String concept, LocalDate date, int maxIterations, int jspritIterations,
                          boolean zoneBasedCachingEnabled, double zoneBasedCachingThresholdMeters,
                          double uTurnPenaltyCost, String tag, StudyArea studyArea, int fleetSize,
                          boolean drtWithFreight, boolean kpiDashboard, double chiThreshold) {
        this(concept, date, maxIterations, jspritIterations, zoneBasedCachingEnabled,
                zoneBasedCachingThresholdMeters, uTurnPenaltyCost, tag, studyArea, fleetSize,
                drtWithFreight, kpiDashboard, chiThreshold, /*noParcels*/ false);
    }

    /**
     * Creates a new scenario configuration with the {@link #noParcels} reference-run switch,
     * defaulting the MATSim random {@code seed} to 1337.
     *
     * @param noParcels when {@code true}, a {@code DRT_SHAREDUSE} run skips parcel injection
     *                  (leakage control for the χ→0 validation, D10 (e)); ignored otherwise
     */
    public HAGRIDSimulationConfig(String concept, LocalDate date, int maxIterations, int jspritIterations,
                          boolean zoneBasedCachingEnabled, double zoneBasedCachingThresholdMeters,
                          double uTurnPenaltyCost, String tag, StudyArea studyArea, int fleetSize,
                          boolean drtWithFreight, boolean kpiDashboard, double chiThreshold,
                          boolean noParcels) {
        this(concept, date, maxIterations, jspritIterations, zoneBasedCachingEnabled,
                zoneBasedCachingThresholdMeters, uTurnPenaltyCost, tag, studyArea, fleetSize,
                drtWithFreight, kpiDashboard, chiThreshold, noParcels, /*seed*/ 1337L);
    }

    /**
     * Creates a new scenario configuration with the MATSim global random {@code seed} (review F3),
     * defaulting {@code idleThreshold} / {@code maxTourDurationSeconds} to
     * {@link Modular#DEFAULT_IDLE_THRESHOLD} / {@link Modular#DEFAULT_MAX_TOUR_DURATION_S}.
     *
     * @param seed MATSim global random seed; vary for error-band replicate runs
     */
    public HAGRIDSimulationConfig(String concept, LocalDate date, int maxIterations, int jspritIterations,
                          boolean zoneBasedCachingEnabled, double zoneBasedCachingThresholdMeters,
                          double uTurnPenaltyCost, String tag, StudyArea studyArea, int fleetSize,
                          boolean drtWithFreight, boolean kpiDashboard, double chiThreshold,
                          boolean noParcels, long seed) {
        this(concept, date, maxIterations, jspritIterations, zoneBasedCachingEnabled,
                zoneBasedCachingThresholdMeters, uTurnPenaltyCost, tag, studyArea, fleetSize,
                drtWithFreight, kpiDashboard, chiThreshold, noParcels, seed,
                Modular.DEFAULT_IDLE_THRESHOLD, Modular.DEFAULT_MAX_TOUR_DURATION_S);
    }

    /**
     * Fullest constructor: adds the DRT_MODULAR (1d) dispatch-gate/tour-cap keys,
     * {@code idleThreshold} and {@code maxTourDurationSeconds}, on top of every other parameter.
     * All shorter constructors default them to {@link Modular#DEFAULT_IDLE_THRESHOLD} /
     * {@link Modular#DEFAULT_MAX_TOUR_DURATION_S}.
     *
     * @param idleThreshold           passenger-first dispatch gate (design D6); must be in
     *                                {@code [0.0, 1.0]} ({@code 1.0} = never-dispatch control arm)
     * @param maxTourDurationSeconds  jsprit tour-duration cap in seconds (design D5); must be positive
     * @throws IllegalArgumentException if {@code idleThreshold} is outside {@code [0.0, 1.0]} or
     *                                  {@code maxTourDurationSeconds} is not positive
     */
    public HAGRIDSimulationConfig(String concept, LocalDate date, int maxIterations, int jspritIterations,
                          boolean zoneBasedCachingEnabled, double zoneBasedCachingThresholdMeters,
                          double uTurnPenaltyCost, String tag, StudyArea studyArea, int fleetSize,
                          boolean drtWithFreight, boolean kpiDashboard, double chiThreshold,
                          boolean noParcels, long seed, double idleThreshold, int maxTourDurationSeconds) {
        this.concept = Objects.requireNonNull(concept, "concept must not be null");
        this.date = Objects.requireNonNull(date, "date must not be null");
        if (maxIterations < 0) {
            throw new IllegalArgumentException("maxIterations must be >= 0");
        }
        if (maxIterations == 0) {
            boolean isLmd;
            try {
                isLmd = HagridConfig.Scenario.valueOf(concept.toUpperCase())
                        == HagridConfig.Scenario.LMD_BASELINE;
            } catch (IllegalArgumentException ex) {
                isLmd = false;
            }
            if (!isLmd) {
                throw new IllegalArgumentException(
                        "maxIterations=0 is only valid for LMD_BASELINE; concept '"
                                + concept + "' requires maxIterations > 0");
            }
        }
        if (jspritIterations <= 0) {
            throw new IllegalArgumentException("jspritIterations must be positive");
        }
        if (zoneBasedCachingThresholdMeters < 0) {
            throw new IllegalArgumentException("zoneBasedCachingThresholdMeters must be >= 0");
        }
        if (idleThreshold < 0.0 || idleThreshold > 1.0) {
            throw new IllegalArgumentException(
                    "idleThreshold must be in [0.0, 1.0] (1.0 = never-dispatch control arm): " + idleThreshold);
        }
        if (maxTourDurationSeconds <= 0) {
            throw new IllegalArgumentException("maxTourDurationSeconds must be positive: " + maxTourDurationSeconds);
        }
        this.maxIterations = maxIterations;
        this.jspritIterations = jspritIterations;
        this.zoneBasedCachingEnabled = zoneBasedCachingEnabled;
        this.zoneBasedCachingThresholdMeters = zoneBasedCachingThresholdMeters;
        this.uTurnPenaltyCost = Math.max(0.0, uTurnPenaltyCost);
        this.tag = tag != null ? tag.trim() : "";
        this.studyArea = Objects.requireNonNull(studyArea, "studyArea must not be null");
        this.fleetSize = fleetSize;
        this.drtWithFreight = drtWithFreight;
        this.kpiDashboard = kpiDashboard;
        this.chiThreshold = chiThreshold;
        this.noParcels = noParcels;
        this.seed = seed;
        this.idleThreshold = idleThreshold;
        this.maxTourDurationSeconds = maxTourDurationSeconds;
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

    /** True iff this is a DRT scenario that also carries the LMD carriers (married Baseline). */
    public boolean isDrtWithFreight() {
        return isDrtScenario() && drtWithFreight;
    }

    /** Scenario option {@code kpiDashboard} (default true): build the Python KPI dashboard after the run. */
    public boolean isKpiDashboardEnabled() {
        return kpiDashboard;
    }

    /**
     * Returns the χ-gate threshold (seconds): the maximum acceptable DETOUR-ONLY added vehicle
     * time for a single parcel insertion (the request's own dwell is subtracted by the gate).
     * &lt; 0 = gate closed (rejects all parcels; leakage-control probe). Only meaningful for
     * {@code DRT_SHAREDUSE} (default 600.0).
     *
     * @return chi threshold in seconds
     */
    public double getChiThreshold() {
        return chiThreshold;
    }

    /**
     * Returns the {@link #noParcels} reference-run switch. When {@code true}, a
     * {@code DRT_SHAREDUSE} run injects no parcels (8-seat DRT, inert Shared-Use stack) — the
     * leakage control for the χ→0 validation. Default {@code false}.
     *
     * @return {@code true} to skip parcel injection for this Shared-Use run
     */
    public boolean isNoParcels() {
        return noParcels;
    }

    /**
     * Returns the MATSim global random seed; vary for error-band replicate runs (review F3).
     * Default 1337. Applied to {@code config.global().setRandomSeed(...)} on every simulation
     * path; the jsprit seed is a separate system-property override and is NOT affected.
     *
     * @return MATSim global random seed
     */
    public long getSeed() {
        return seed;
    }

    /**
     * Returns the passenger-first dispatch gate (design D6): the DRT_MODULAR tour dispatcher
     * only commits a vehicle to a freight excursion while the idle-vehicle SHARE strictly
     * exceeds this threshold. Always in {@code [0.0, 1.0]}; {@code 1.0} is the never-dispatch
     * control arm. Default {@link Modular#DEFAULT_IDLE_THRESHOLD}. Only meaningful for
     * {@code DRT_MODULAR}.
     *
     * @return idle-share dispatch gate threshold
     */
    public double getIdleThreshold() {
        return idleThreshold;
    }

    /**
     * Returns the jsprit tour-duration cap in seconds (design D5): the maximum route duration a
     * single DRT_MODULAR freight excursion may plan for. Default
     * {@link Modular#DEFAULT_MAX_TOUR_DURATION_S} (3.5h); 25200 (7h) is the control arm. Only
     * meaningful for {@code DRT_MODULAR}.
     *
     * @return maximum tour duration in seconds
     */
    public int getMaxTourDurationSeconds() {
        return maxTourDurationSeconds;
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

    /** Fingerprint of the prepared DRT inputs (written by {@code LausitzDrtPreprocessor}). */
    public String getDrtInputsFingerprint() {
        return paths.drtInputsFingerprint();
    }

    /** Provider-tagged synthetic LMD depot CSV (one row per LSP). */
    public String getLmdDepotCsv() {
        return paths.lmdDepotCsv();
    }

    /** PANDA parcel-demand shapefile staged for the LMD baseline (date-named as exported). */
    public String getLmdDemandShapefile() {
        return paths.lmdDemandShapefile();
    }

    /** Lausitz freight van vehicle-types (ct_cep_size_m / _l only). */
    public String getLmdVehicleTypes() {
        return paths.lmdVehicleTypes();
    }

    /** Routed LMD carrier plans for this run (jsprit output). */
    public String getLmdCarriersRouted() {
        return paths.lmdCarriersRouted();
    }

    // === VALIDATION ===

    /**
     * Validates that all required input files exist for this scenario and reports
     * the state of the output directory. Throws an exception if required inputs
     * are missing.
     */
    public void validateInputFiles() {
        List<String> missing = new ArrayList<>();

        if (!isDrtScenario() && !isLmdBaseline()) {
            checkFile(getConfigPath(), "Simulation config", missing);
            checkFile(getVehicleTypePath(), "Vehicle types", missing);
            checkFile(getCarNetworkPath(), "Car network", missing);
            checkFile(getBikeNetworkPath(), "Bike network", missing);
            checkFile(getNetworkChangeEventPath(), "Network change events", missing);
            checkFile(getFreightZonePath(), "Freight zone shapefile", missing);
            checkFile(getDeliveryCarrierPath(), "Delivery carriers", missing);
            checkFile(getSupplyCarrierPath(), "Supply carriers", missing);
        }

        if (isLmdBaseline()) {
            checkFile(Path.of(getLmdDemandShapefile()), "LMD demand shapefile", missing);
            checkFile(Path.of(getLmdDepotCsv()), "LMD depot CSV", missing);
            checkFile(Path.of(getLmdVehicleTypes()), "LMD vehicle types", missing);
            checkFile(Path.of(getLausitzNetworkRaw()), "Lausitz network", missing);
        }

        if (isDrtScenario()) {
            checkFile(Path.of(getDrtNetworkClipped()), "DRT network (clipped)", missing);
            checkFile(Path.of(getPassengerPlansClipped()), "Passenger plans (clipped)", missing);
            checkFile(Path.of(getDrtFleetFile()), "DRT fleet file", missing);
            checkFile(Path.of(getDrtServiceAreaShapefile()), "DRT service area", missing);
            checkFile(Path.of(getLausitzBaseConfig()), "Lausitz base config", missing);
            checkFile(Path.of(getLmdDepotCsv()), "LMD depot CSV", missing);
            // Rail PT is the standard layer for every Lausitz DRT scenario (run(cfg) always supplies
            // the rail/vehicle-types getters), so these raw inputs are required for a real run. The
            // null-rail DRT-only path is exercised only by unit tests, which bypass validateInputFiles().
            checkFile(Path.of(getLausitzTransitScheduleRaw()), "Lausitz transit schedule (raw)", missing);
            checkFile(Path.of(getLausitzTransitVehiclesRaw()), "Lausitz transit vehicles (raw)", missing);
            checkFile(Path.of(getLausitzVehicleTypes()), "Lausitz vehicle-types", missing);

            // DRT_MODULAR always needs the LMD preprocessing inputs regardless of the freight flag
            // (it always runs the offline jsprit preprocessing - runsCarrierModules() is what
            // gates whether the CarrierModule ALSO runs, not whether jsprit does); the married
            // baseline needs them only when drtWithFreight is set. concept.toUpperCase() cannot
            // throw here: isDrtScenario() being true already proved it parses.
            boolean modular = HagridConfig.Scenario.valueOf(concept.toUpperCase())
                    == HagridConfig.Scenario.DRT_MODULAR;
            if (isDrtWithFreight() || modular) {
                checkFile(Path.of(getLmdDemandShapefile()), "LMD demand shapefile", missing);
                checkFile(Path.of(getLmdDepotCsv()), "LMD depot CSV", missing);
                checkFile(Path.of(getLmdVehicleTypes()), "LMD vehicle types", missing);
                checkFile(Path.of(getLausitzNetworkRaw()), "Lausitz network (raw, jsprit routing)", missing);
            }

            // Existence is not enough: the prepared artifacts above are keyed only by
            // CONCEPT_date[_tag], so inputs prepared for a different fleetSize / seat count /
            // noParcels setting live at exactly the same paths. Only run this once the files
            // are actually there — otherwise a first-time user gets a confusing drift report
            // on top of the plain "missing file" list.
            if (missing.isEmpty()) {
                missing.addAll(hagrid.integrated.drt.DrtInputsFingerprint.mismatches(
                        this, Path.of(getDrtInputsFingerprint())));
            }
        }

        if (!missing.isEmpty()) {
            throw new IllegalStateException("Missing or stale required inputs:\n"
                    + String.join("\n", missing));
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
