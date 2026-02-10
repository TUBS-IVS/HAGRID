package hagrid;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * Centralized path management for the HAGRID pipeline.
 * 
 * <p>Provides consistent, well-structured paths for all pipeline inputs,
 * outputs, and MATSim simulation results. All outputs are organized
 * per-run under a unique run ID for full traceability.</p>
 * 
 * <h2>Directory Structure:</h2>
 * <pre>
 * {pipelineRoot}/
 * ├── hagrid-input/                        All pipeline inputs
 * │   ├── demand/{runId}/                  Demand shapefiles per scenario+date
 * │   ├── geodata/                         Region shapefiles
 * │   ├── hubs/                            Hub/depot data (KEP-hubs, shipping points)
 * │   ├── network/                         Road networks, zone files
 * │   └── vehicles/                        Vehicle type definitions
 * │
 * ├── hagrid-output/                       Pipeline results
 * │   ├── shared/                          Shared simulation inputs (same for ALL runs)
 * │   │   ├── sim-config.xml               MATSim base configuration
 * │   │   ├── cargobike_network.xml.gz     Cargobike network
 * │   │   ├── network_change_events.xml.gz Time-dependent network change events
 * │   │   └── zones/                       Freight zone shapefile (all parts)
 * │   │
 * │   └── {RUN_ID}/                        Run-specific results
 * │       ├── carriers/                    {RUN_ID}_delivery/supply_carriers_*.xml
 * │       ├── vehicles/                    {RUN_ID}_vehicle_types.xml
 * │       ├── network/                     {RUN_ID}_network_filtered.xml.gz
 * │       ├── routing/                     {RUN_ID}_routing_metrics/status.csv
 * │       ├── demand/clustering/           Demand analysis and clustering plots
 * │       ├── summary/                     {RUN_ID}_scenario_summary.txt
 * │       ├── cache/                       Routing cache
 * │       └── logs/                        Run-specific logs
 * │
 * └── hagrid-matsim-output/{RUN_ID}/       MATSim simulation results
 * </pre>
 * 
 * @author HAGRID Team
 */
public class HagridPaths {

    private static final Logger LOGGER = LogManager.getLogger(HagridPaths.class);
    private static final String PIPELINE_ROOT = "parcel-demand-2-matsim-pipeline";

    // =========================================================================
    // BASE DIRECTORIES
    // =========================================================================

    private final Path pipelineRoot;
    private final Path inputBase;       // hagrid-input/
    private final Path outputBase;      // hagrid-output/
    private final Path matsimOutputBase; // hagrid-matsim-output/

    // =========================================================================
    // RUN-SPECIFIC STATE
    // =========================================================================

    private String runId;
    private Path runDir;  // hagrid-output/{RUN_ID}/

    // =========================================================================
    // CONSTRUCTORS
    // =========================================================================

    public HagridPaths() {
        this(Paths.get(PIPELINE_ROOT));
    }

    public HagridPaths(Path pipelineRoot) {
        this.pipelineRoot = pipelineRoot;
        this.inputBase = pipelineRoot.resolve("hagrid-input");
        this.outputBase = pipelineRoot.resolve("hagrid-output");
        this.matsimOutputBase = pipelineRoot.resolve("hagrid-matsim-output");
    }

    /**
     * Initialize run-specific output paths. Called when the run ID becomes known.
     */
    public void initializeRun(String runId) {
        this.runId = runId;
        this.runDir = outputBase.resolve(runId);
        LOGGER.info("Initialized paths for run: {} -> {}", runId, runDir);
    }

    // =========================================================================
    // INPUT PATHS  (hagrid-input/)
    // =========================================================================

    public Path inputBase() { return inputBase; }

    // --- Demand ---
    public Path demandDir() { return inputBase.resolve("demand"); }
    public Path demandDir(String runId) { return demandDir().resolve(runId); }
    
    /** Build the full demand shapefile path for a given run ID, date, and day name. */
    public String demandShapefile(String runId, String isoDate, String dayOfWeek) {
        String fileName = "hagrid_parcel_demand_" + isoDate + "_(" + dayOfWeek + ").shp";
        return demandDir(runId).resolve(fileName).toString();
    }

    // --- Geodata ---
    public Path geodataDir() { return inputBase.resolve("geodata"); }

    // --- Hubs ---
    public Path hubsDir() { return inputBase.resolve("hubs"); }
    public String hubDataFile() { return hubsDir().resolve("KEP-hubs_v3.csv").toString(); }
    public String shippingPointsDir() { return hubsDir().resolve("standorte_von_paket.net").toString() + "/"; }
    public String parcelLockersFile() { return hubsDir().resolve("standorte_von_dhl.de.csv").toString(); }

    // --- Network (pipeline reads from input, simulation reads from shared output) ---
    public Path networkInputDir() { return inputBase.resolve("network"); }
    public String networkFile() { return networkInputDir().resolve("car_network_filtered_V2.xml.gz").toString(); }
    public String cargobikeNetworkFile() { return sharedDir().resolve("cargobike_network.xml.gz").toString(); }
    public String zoneShapefile() { return sharedZonesDir().resolve("RH_useful__zone.shp").toString(); }

    // --- Vehicles ---
    public Path vehicleInputDir() { return inputBase.resolve("vehicles"); }
    public String vehicleTypesFile() { return vehicleInputDir().resolve("HAGRID_vehicleTypes2.0.xml").toString(); }

    // --- Config ---
    public Path configDir() { return inputBase.resolve("config"); }
    public String matsimConfigFile() { return sharedDir().resolve("sim-config.xml").toString(); }
    public String jspritAlgorithmFile() { return configDir().resolve("jsprit-algorithm.xml").toString(); }

    // =========================================================================
    // SHARED SIMULATION INPUTS  (hagrid-output/shared/)
    // =========================================================================

    /** Shared directory for files needed by ALL simulation runs (config, networks, zones). */
    public Path sharedDir() { return outputBase.resolve("shared"); }

    /** Zone shapefile directory under shared. */
    public Path sharedZonesDir() { return sharedDir().resolve("zones"); }

    /** Shared network change events (same for all runs). */
    public String sharedNetworkChangeEvents() { return sharedDir().resolve("network_change_events.xml.gz").toString(); }

    // =========================================================================
    // OUTPUT PATHS  (hagrid-output/{RUN_ID}/)
    // =========================================================================

    /** Root output directory for the current run. */
    public Path runDir() {
        checkRunInitialized();
        return runDir;
    }

    /** Run-ID prefix for unique filenames, e.g. "BASECASE_13052025_" */
    private String p() {
        checkRunInitialized();
        return runId + "_";
    }

    // --- Carriers ---
    public Path carrierDir() { return runDir().resolve("carriers"); }

    public String deliveryCarriersUnrouted() { return carrierDir().resolve(p() + "delivery_carriers_unrouted.xml").toString(); }
    public String deliveryCarriersMerged()   { return carrierDir().resolve(p() + "delivery_carriers_merged.xml").toString(); }
    public String deliveryCarriersRouted()   { return carrierDir().resolve(p() + "delivery_carriers_routed.xml").toString(); }

    public String supplyCarriersUnrouted()   { return carrierDir().resolve(p() + "supply_carriers_unrouted.xml").toString(); }
    public String supplyCarriersSplitUnrouted() { return carrierDir().resolve(p() + "supply_carriers_split_unrouted.xml").toString(); }
    public String supplyCarriersRouted()     { return carrierDir().resolve(p() + "supply_carriers_routed.xml").toString(); }

    public String carrierPlansCombined()     { return carrierDir().resolve(p() + "carrier_plans_combined.xml").toString(); }

    // --- Vehicles ---
    public Path vehicleOutputDir() { return runDir().resolve("vehicles"); }
    public String vehicleTypesOutput() { return vehicleOutputDir().resolve(p() + "vehicle_types.xml").toString(); }

    // --- Network ---
    public Path networkOutputDir() { return runDir().resolve("network"); }
    public String networkFiltered()     { return networkOutputDir().resolve(p() + "network_filtered.xml.gz").toString(); }
    /** Network change events — shared across all runs, stored in shared/ directory. */
    public String networkChangeEvents() { return sharedNetworkChangeEvents(); }

    // --- Routing ---
    public Path routingDir() { return runDir().resolve("routing"); }
    public String routingMetrics() { return routingDir().resolve(p() + "routing_metrics.csv").toString(); }
    public String routingStatus()  { return routingDir().resolve(p() + "routing_status.csv").toString(); }

    // --- Demand / Clustering ---
    public Path demandOutputDir() { return runDir().resolve("demand"); }
    public Path clusteringDir()   { return demandOutputDir().resolve("clustering"); }

    // --- Summary ---
    public Path summaryDir() { return runDir().resolve("summary"); }
    public String scenarioSummary() { return summaryDir().resolve(p() + "scenario_summary.txt").toString(); }
    public String carrierRoutingCsv() { return summaryDir().resolve(p() + "carrier_routing_detail.csv").toString(); }

    // --- Cache ---
    public Path cacheDir() { return runDir().resolve("cache"); }

    // --- Logs ---
    public Path logDir() { return runDir().resolve("logs"); }
    public String runnerLog() { return logDir().resolve("runner.log").toString(); }

    // =========================================================================
    // MATSIM OUTPUT  (hagrid-matsim-output/{RUN_ID}/)
    // =========================================================================

    public Path matsimOutputBase() { return matsimOutputBase; }
    
    public Path matsimRunDir() {
        checkRunInitialized();
        return matsimOutputBase.resolve(runId);
    }

    /** MATSim output directory with iteration/jsprit suffix for detailed tracking. */
    public Path matsimRunDir(int matsimIterations, int jspritIterations) {
        checkRunInitialized();
        String dirName = runId + "_iter" + matsimIterations + "_jsprit" + jspritIterations;
        return matsimOutputBase.resolve(dirName);
    }

    // =========================================================================
    // DIRECTORY CREATION
    // =========================================================================

    /**
     * Create all output directories for the current run.
     * Also ensures the shared simulation directory exists and copies
     * static simulation input files (config, networks, zones) there.
     * Call this once after {@link #initializeRun(String)}.
     */
    public void createOutputDirectories() throws IOException {
        checkRunInitialized();
        // Run-specific directories
        Files.createDirectories(carrierDir());
        Files.createDirectories(vehicleOutputDir());
        Files.createDirectories(networkOutputDir());
        Files.createDirectories(routingDir());
        Files.createDirectories(clusteringDir());
        Files.createDirectories(summaryDir());
        Files.createDirectories(cacheDir());
        Files.createDirectories(logDir());
        LOGGER.info("Created output directories under: {}", runDir);

        // Shared simulation inputs (only copied once, idempotent)
        copySharedSimulationInputs();
    }

    /**
     * Ensures that the shared simulation input directory ({@code hagrid-output/shared/})
     * is populated with all static files needed for MATSim runs.
     * <p>
     * Safe to call multiple times — only copies files that are not yet present.
     * Can be called from both the pipeline ({@link #createOutputDirectories()})
     * and the simulation runner ({@link hagrid.simulation.HAGRIDSimulationConfig}).
     *
     * @throws IOException if any file-system operation fails
     */
    public void ensureSharedSimulationInputs() throws IOException {
        copySharedSimulationInputs();
    }

    /**
     * Copies static simulation input files into {@code hagrid-output/shared/}.
     * <p>These files are the same for ALL simulation runs and don't change
     * between scenarios or dates:</p>
     * <ul>
     *   <li>{@code sim-config.xml} — MATSim base configuration (from hagrid-input/config/)</li>
     *   <li>{@code cargobike_network.xml.gz} — Cargobike routing network (from hagrid-input/network/)</li>
     *   <li>{@code network_change_events.xml.gz} — Time-dependent link speed changes (from hagrid-input/network/)</li>
     *   <li>{@code zones/RH_useful__zone.*} — Freight zone shapefile (from hagrid-input/network/)</li>
     * </ul>
     * <p>Files are only copied if missing. Existing files are not overwritten.</p>
     */
    private void copySharedSimulationInputs() throws IOException {
        Files.createDirectories(sharedDir());
        Files.createDirectories(sharedZonesDir());

        // Source locations (all files now live in hagrid-input/)
        Path configInputDir = inputBase.resolve("config");
        Path networkInputDir = inputBase.resolve("network");

        // 1) sim-config.xml
        copyIfMissing(configInputDir.resolve("sim-config.xml"),
                      sharedDir().resolve("sim-config.xml"), "sim-config.xml");

        // 2) Cargobike network
        copyIfMissing(networkInputDir.resolve("cargobike_network_zones_MH_V3_clean.xml.gz"),
                      sharedDir().resolve("cargobike_network.xml.gz"), "cargobike network");

        // 3) Network change events
        copyIfMissing(networkInputDir.resolve("car_network_filtered_V2_change_events.xml.gz"),
                      sharedDir().resolve("network_change_events.xml.gz"), "network change events");

        // 4) Zone shapefile (all parts: .shp, .dbf, .shx, .prj, .cpg, .ctf)
        //    Sources on disk may be upper-case; destinations use lower-case.
        String[][] zonePairs = {
            { ".SHP", ".shp" }, { ".DBF", ".dbf" }, { ".SHX", ".shx" },
            { ".PRJ", ".prj" }, { ".CPG", ".cpg" }, { ".CTF", ".ctf" }
        };
        String zoneBase = "RH_useful__zone";
        for (String[] pair : zonePairs) {
            Path src = networkInputDir.resolve(zoneBase + pair[0]);
            Path dst = sharedZonesDir().resolve(zoneBase + pair[1]);
            if (Files.exists(src)) {
                copyIfMissing(src, dst, "zone " + pair[1]);
            }
        }

        LOGGER.info("Shared simulation inputs ready at: {}", sharedDir().toAbsolutePath());
    }

    /** Copy a file only if the destination does not yet exist. */
    private void copyIfMissing(Path source, Path destination, String label) throws IOException {
        if (Files.exists(destination)) {
            LOGGER.debug("[shared] {} already exists, skipping", label);
            return;
        }
        if (!Files.exists(source)) {
            LOGGER.warn("[shared] Source for {} not found: {} — skipping", label, source);
            return;
        }
        Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES);
        LOGGER.info("[shared] Copied {} → {}", label, destination.toAbsolutePath());
    }

    // =========================================================================
    // ACCESSORS
    // =========================================================================

    public String getRunId() { return runId; }
    public Path getPipelineRoot() { return pipelineRoot; }

    // =========================================================================
    // INTERNAL
    // =========================================================================

    private void checkRunInitialized() {
        if (runId == null || runDir == null) {
            throw new IllegalStateException(
                "HagridPaths not initialized for run - call initializeRun(runId) first");
        }
    }

    @Override
    public String toString() {
        return String.format("HagridPaths[run=%s, input=%s, output=%s, matsim=%s]",
            runId, inputBase, outputBase, matsimOutputBase);
    }
}
