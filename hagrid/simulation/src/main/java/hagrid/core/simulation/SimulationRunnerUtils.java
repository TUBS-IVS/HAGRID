package hagrid.core.simulation;

import hagrid.core.HagridPaths;
import hagrid.core.util.StudyArea;
import hagrid.hannover.analysis.CarrierXmlParser;
import hagrid.hannover.analysis.CarrierXmlParser.ParsedCarrier;
import hagrid.hannover.analysis.DashboardGenerator;
import hagrid.hannover.analysis.FreightEventHandler;
import hagrid.lausitz.simulation.DrtScenarioBuilder;
import hagrid.lausitz.simulation.KpiDashboardTrigger;
import hagrid.lausitz.simulation.RunMetadataWriter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.geotools.api.feature.simple.SimpleFeature;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.gis.GeoFileReader;
import org.matsim.freight.carriers.controller.CarrierModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * Shared utility methods for running HAGRID simulations and generating
 * analysis dashboards afterwards.
 * <p>
 * Extracted from the old monolithic {@code HAGRIDSimulationRunner} so that
 * the runner class itself stays clean and declarative.
 */
public final class SimulationRunnerUtils {

    private static final Logger LOG = LogManager.getLogger(SimulationRunnerUtils.class);

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

    private SimulationRunnerUtils() {} // utility class

    // ====================================================================
    // Bootstrap & logging
    // ====================================================================

    /**
     * Sets {@code hagrid.log.dir} to {@code hagrid-matsim-output/logs/} so that
     * Log4j2 bootstrap logs land inside the MATSim output tree instead of
     * creating a stale top-level {@code hagrid-output/} folder.
     * <p>
     * <b>Must be called before any Log4j2 Logger is obtained</b> (i.e. from a
     * {@code static} initializer in the main class).
     */
    public static void initLogging() {
        if (System.getProperty(LOG_DIR_PROPERTY) == null) {
            try {
                // Only the compile-time constants are read here (javac inlines them, so HagridPaths is
                // not class-initialised): HagridPaths has a static Logger, and no Log4j2 Logger may
                // exist before hagrid.log.dir is set. Same precedence/blank rule as
                // HagridPaths.resolveMatsimOutputBase: property beats env, blank = unset.
                String outRoot = System.getProperty(HagridPaths.OUTPUT_ROOT_PROPERTY);
                if (outRoot == null || outRoot.isBlank()) outRoot = System.getenv(HagridPaths.OUTPUT_ROOT_ENV);
                Path logDir = (outRoot == null || outRoot.isBlank())
                        ? Path.of("hagrid-matsim-output", "logs")
                        : Path.of(outRoot.strip(), "logs");
                Files.createDirectories(logDir);
                setOwnLogDir(logDir);
            } catch (Exception ignored) {
                // fallback: let log4j2.xml default handle it (no logger usable yet here)
            }
        }
    }

    private static final String LOG_DIR_PROPERTY = "hagrid.log.dir";

    /**
     * The last value THIS class wrote into {@code hagrid.log.dir}. Anything else found in the
     * property was set from outside (a {@code -D} flag) and is respected. Tracking our own
     * value, instead of a one-shot "was it set at startup" flag, keeps the second scenario of
     * a multi-scenario JVM from mistaking the first run's directory for an explicit choice.
     */
    private static volatile String ownLogDir;

    private static void setOwnLogDir(Path logDir) {
        ownLogDir = logDir.toAbsolutePath().toString();
        System.setProperty(LOG_DIR_PROPERTY, ownLogDir);
    }

    // ====================================================================
    // Console banners
    // ====================================================================

    /** Prints the startup banner to the log. */
    public static void printStartBanner() {
        LOG.info("═══════════════════════════════════════════════");
        LOG.info("  HAGRID Simulation Runner");
        LOG.info("═══════════════════════════════════════════════");
    }

    /** Prints the completion banner to the log. */
    public static void printEndBanner() {
        LOG.info("═══════════════════════════════════════════════");
        LOG.info("  All done.");
        LOG.info("═══════════════════════════════════════════════");
    }

    // ====================================================================
    // Argument parsing
    // ====================================================================

    /**
     * Parses one comma-separated scenario. List values may contain commas only for keys
     * declared by HagridConfigurationCatalog.listValued; bare tokens after scalars fail.
     * The captured file supplies RUN values and explicit CLI values take precedence.
     */
    /**
     * Every key {@link #parseScenario} reads, plus {@code writeDashboard}, which
     * {@link #extractDashboardFlags} reads from the raw spec. Anything else is rejected (review
     * 2026-10-02 #11): a key outside this set used to be dropped silently, so a typo such as
     * {@code budgetMdoe=selfref} ran the default with nothing but a log line to show for it. A key
     * added to parseScenario without being added here fails loudly on first use, never silently.
     */
    static final java.util.Set<String> KNOWN_KEYS = java.util.Set.of(
            "concept", "date", "tag", "maxIter", "jspritIter", "zoneCaching", "zoneThreshold",
            "uTurnPenalty", "studyArea", "fleetSize", "freight", "kpiDashboard", "chiThreshold",
            "noParcels", "seed", "idleThreshold", "maxTourDuration", "openDepots",
            "maxJobsPerDistrict", "maxConcurrentFreight", "freightWindows", "budgetMode",
            "budgetSmoothing", "budgetHeadroom", "budgetUrgencyLeadS", "overwrite",
            "writeDashboard", "cargoBikeMaxSpeedKmh", "eventsEvery", "plansEvery", "freightHistogramEvery",
            "solverThreads", "maxActiveSolvers", "solverMaxRouteDuration", "config");

    private static String unknownKeyMessage(String key) {
        for (String known : KNOWN_KEYS) {
            if (known.equalsIgnoreCase(key)) {
                return "Unknown key '" + key + "' - did you mean '" + known + "'? Keys are"
                        + " case-sensitive, and an unknown one used to be ignored silently.";
            }
        }
        return "Unknown key '" + key + "' - it would have been ignored silently and the run would"
                + " have used the default. Known keys: " + new java.util.TreeSet<>(KNOWN_KEYS);
    }

    /** Validates sparse launch input without constructing a launch or writing files. */
    public static void validateLaunchValues(Map<String,String> values) {
        values.forEach((key,value)->{
            switch(key) {
                case "maxIter", "maxConcurrentFreight" -> nonNegInt(value,key);
                case "jspritIter", "fleetSize", "solverThreads", "maxActiveSolvers", "eventsEvery", "plansEvery",
                        "freightHistogramEvery", "solverMaxRouteDuration", "maxTourDuration", "maxJobsPerDistrict", "budgetSmoothing" -> positiveInt(value,key);
                case "zoneCaching", "freight", "kpiDashboard", "writeDashboard", "noParcels", "overwrite" -> bool(value,key);
                case "zoneThreshold", "uTurnPenalty", "idleThreshold" -> {
                    double number=nonNegDouble(value,key);
                    if(!Double.isFinite(number)) throw new IllegalArgumentException(key+" must be finite");
                }
                case "cargoBikeMaxSpeedKmh", "budgetUrgencyLeadS" -> positiveDouble(value,key);
                case "budgetHeadroom" -> shareDouble(value,key);
                case "chiThreshold" -> anyDouble(value,key);
                case "seed" -> anyLong(value,key);
                case "budgetMode" -> budgetMode(value,key);
                case "freightWindows" -> hagrid.lausitz.modular.Modular.parseWindows(value);
                case "date" -> LocalDate.parse(value,DATE_FMT);
                case "studyArea" -> StudyArea.valueOf(value.trim().toUpperCase(Locale.ROOT));
                default -> { /* Canonical section values have their typed validators. */ }
            }
        });
    }

    public static HAGRIDSimulationConfig parseScenario(String spec) {
        if (spec == null || spec.isBlank()) {
            throw new IllegalArgumentException("Empty scenario specification");
        }

        Map<String, String> map = new LinkedHashMap<>();
        String lastKey = null;
        for (String token : spec.split(",")) {
            if (!token.contains("=")) {
                // The shared catalog defines lists; unrelated bare tokens remain errors.
                if (lastKey == null || !hagrid.core.config.HagridConfigurationCatalog.listValued(lastKey) || token.isBlank()) {
                    throw new IllegalArgumentException("Invalid token: " + token);
                }
                map.put(lastKey, map.get(lastKey) + "," + token.trim());
                continue;
            }
            String[] kv = token.split("=", 2);
            if (kv.length != 2 || kv[0].isBlank() || kv[1].isBlank()) {
                throw new IllegalArgumentException("Invalid token: " + token);
            }
            lastKey = kv[0].trim();
            if (!KNOWN_KEYS.contains(lastKey) && !hagrid.core.config.HagridConfigurationCatalog.known(lastKey)) {
                throw new IllegalArgumentException(unknownKeyMessage(lastKey));
            }
            if (map.containsKey(lastKey)) {
                // Review 2026-10-02 #11: the later value used to win silently. One comparison
                // rested on a .bat whose BASE variable already carried fleetSize.
                throw new IllegalArgumentException("Key '" + lastKey + "' is set twice ('"
                        + map.get(lastKey) + "' and '" + kv[1].trim() + "') - the later value"
                        + " used to win silently. Set it once.");
            }
            map.put(lastKey, kv[1].trim());
        }

        Map<String,String> explicitCli = Map.copyOf(map);
        map = new LinkedHashMap<>(hagrid.core.config.HagridConfigurationCatalog.launchValues(map));
        hagrid.core.config.HagridConfigFile configurationFile = null;
        if(map.containsKey("config")) {
            configurationFile = hagrid.core.config.HagridConfigFile.load(Path.of(map.get("config")));
            Map<String,String> merged = new LinkedHashMap<>(configurationFile.launchValues());
            merged.putAll(map);
            map=merged;
        }
        validateLaunchValues(map);
        Map<String,String> canonicalValidation = new LinkedHashMap<>();
        map.forEach((key,value)->{
            String canonical=hagrid.core.config.HagridConfigurationCatalog.canonicalName(key);
            if(hagrid.core.config.HagridConfigResolver.isCanonicalKey(canonical) && (!explicitCli.containsKey(key) || key.equals(canonical))) {
                try { hagrid.core.config.HagridConfigResolver.validateCanonicalValue(canonical,value); }
                catch(IllegalArgumentException invalid) { throw new IllegalArgumentException(key + " ("+canonical+"): "+invalid.getMessage(), invalid); }
                canonicalValidation.put(canonical,value);
            }
        });
        hagrid.core.config.DrtSettings.from(canonicalValidation);
        hagrid.core.config.VehicleDispatchSettings.from(canonicalValidation);
        String concept = require(map, "concept");
        LocalDate date;
        try {
            date = LocalDate.parse(require(map, "date"), DATE_FMT);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("Invalid date (use yyyy-MM-dd): " + map.get("date"), ex);
        }

        int maxIter      = nonNegInt(map.getOrDefault("maxIter", "150"), "maxIter");
        int jspritIter   = positiveInt(map.getOrDefault("jspritIter", "100"), "jspritIter");
        boolean zoneCaching = bool(map.getOrDefault("zoneCaching", "false"), "zoneCaching");
        hagrid.core.config.ResolvedHagridConfig.rejectRemoved("routing.zoneBasedCachingEnabled",zoneCaching);
        double zoneThreshold = map.containsKey("zoneThreshold") ? nonNegDouble(map.get("zoneThreshold"), "zoneThreshold") : 0;
        double uTurnPenaltyCost = nonNegDouble(map.getOrDefault("uTurnPenalty", "1.0"), "uTurnPenalty");
        if (map.containsKey("cargoBikeMaxSpeedKmh")) {
            double speedKmh = positiveDouble(map.get("cargoBikeMaxSpeedKmh"), "cargoBikeMaxSpeedKmh");
            if (speedKmh / 3.6 <= 0) {
                throw new IllegalArgumentException("cargoBikeMaxSpeedKmh is too small to represent in m/s");
            }
        }
        for (String key : java.util.List.of("eventsEvery", "plansEvery", "freightHistogramEvery", "solverThreads", "maxActiveSolvers", "solverMaxRouteDuration")) {
            if (map.containsKey(key)) positiveInt(map.get(key), key);
        }
        String tag = map.getOrDefault("tag", "").trim();

        StudyArea studyArea = StudyArea.valueOf(
                map.getOrDefault("studyArea", "HANNOVER").trim().toUpperCase());
        int fleetSize = positiveInt(map.getOrDefault("fleetSize", "50"), "fleetSize");
        boolean drtWithFreight = bool(map.getOrDefault("freight", "true"), "freight");
        boolean kpiDashboard = bool(map.getOrDefault("kpiDashboard", "true"), "kpiDashboard");
        bool(map.getOrDefault("writeDashboard","false"),"writeDashboard");
        // chi-gate threshold (seconds): max acceptable DRIVE-only added vehicle time per parcel
        // insertion (the gate subtracts each leg's own stop duration from that leg). Negative are
        // deliberately ACCEPTED: < 0 = gate hard-closed, rejects ALL parcels (Task-10 leakage
        // probe). Only consumed by DRT_SHAREDUSE (SharedUseModule); harmless default otherwise.
        double chiThreshold = anyDouble(map.getOrDefault("chiThreshold", "600.0"), "chiThreshold");
        // Reference-run switch: skip parcel injection for a DRT_SHAREDUSE run (8-seat DRT with the
        // Shared-Use module stack installed but inert). Leakage control for the χ→0 validation
        // (Task 10 / D10 (e)). Harmless default for every other concept (none inject parcels).
        boolean noParcels = bool(map.getOrDefault("noParcels", "false"), "noParcels");
        // MATSim global random seed (review F3): vary for error-band replicate runs. Default
        // 1337 is the common master of MATSim, configured jsprit and generation streams.
        // The legacy system-property solver override must match the configured master.
        long seed = anyLong(map.getOrDefault("seed", "1337"), "seed");
        // Modular dispatch gate (design D6) and jsprit tour cap (design D5, seconds). Same
        // parse/ctor pattern as chiThreshold; harmless defaults for every other concept.
        double idleThreshold = nonNegDouble(map.getOrDefault("idleThreshold",
                Double.toString(hagrid.lausitz.modular.Modular.DEFAULT_IDLE_THRESHOLD)), "idleThreshold");
        int maxTourDuration = positiveInt(map.getOrDefault("maxTourDuration",
                Integer.toString(hagrid.lausitz.modular.Modular.DEFAULT_MAX_TOUR_DURATION_S)), "maxTourDuration");
        if (concept.equalsIgnoreCase("drt_modular") && map.containsKey("solverMaxRouteDuration")) {
            int commonCap=positiveInt(map.get("solverMaxRouteDuration"), "solverMaxRouteDuration");
            if (map.containsKey("maxTourDuration") && maxTourDuration != commonCap)
                throw new IllegalArgumentException("Conflicting modular duration values: maxTourDuration and solverMaxRouteDuration");
            maxTourDuration=commonCap;
        }
        // District-based depot assignment for the INTEGRATED arms (spec 2026-08-17). Ignored by
        // the Baseline, which keeps one depot per provider. Same parse pattern as chiThreshold.
        String openDepotsRaw = map.getOrDefault("openDepots", "all").trim();
        List<String> openDepots = "all".equalsIgnoreCase(openDepotsRaw)
                ? List.of()
                : Arrays.stream(openDepotsRaw.split(","))
                        .map(s -> s.trim().toLowerCase())
                        .filter(s -> !s.isEmpty())
                        .toList();
        int maxJobsPerDistrict = positiveInt(map.getOrDefault("maxJobsPerDistrict", "300"), "maxJobsPerDistrict");
        // Two-wave freight dispatch (2026-08-30). BOTH default to the pre-2026-08-30 behaviour,
        // so an unset key leaves every existing run bit-identical. maxConcurrentFreight states
        // the PASSENGER-side budget the idle-share gate cannot express; freightWindows shifts
        // work off the passenger peak. See Modular.parseWindows for why a window later than
        // ~16:38 expires tours instead of deferring them.
        int maxConcurrentFreight = nonNegInt(map.getOrDefault("maxConcurrentFreight",
                Integer.toString(hagrid.lausitz.modular.Modular.DEFAULT_MAX_CONCURRENT_FREIGHT)),
                "maxConcurrentFreight");
        java.util.List<hagrid.lausitz.modular.Modular.DispatchWindow> freightWindows =
                hagrid.lausitz.modular.Modular.parseWindows(
                        map.getOrDefault("freightWindows", "").trim());
        // Self-referential capacity budget (plan 2026-09-04). All three default to the
        // pre-2026-09-04 behaviour: budgetMode=off constructs no PassengerLoadProfile at all, so
        // an unset key leaves every existing run bit-identical AND free of the per-tick
        // passenger-busy scan. These are scalar keys, deliberately not declared as lists,
        // which exists only for keys whose own value may contain a comma.
        hagrid.lausitz.modular.Modular.BudgetMode budgetMode = budgetMode(
                map.getOrDefault("budgetMode",
                        hagrid.lausitz.modular.Modular.DEFAULT_BUDGET_MODE.name()),
                "budgetMode");
        int budgetSmoothing = positiveInt(map.getOrDefault("budgetSmoothing",
                Integer.toString(hagrid.lausitz.modular.Modular.DEFAULT_BUDGET_SMOOTHING)),
                "budgetSmoothing");
        // Share of fleet size, not a vehicle count; the default IS theta, which is what makes the
        // first budget arm a one-factor experiment (see Modular.DEFAULT_BUDGET_HEADROOM).
        double budgetHeadroom = shareDouble(map.getOrDefault("budgetHeadroom",
                Double.toString(hagrid.lausitz.modular.Modular.DEFAULT_BUDGET_HEADROOM)),
                "budgetHeadroom");
        // Urgency ramp lead (plan 2026-09-05). Seconds of remaining slack over which a pending
        // tour's urgency ramps from 0 to the full reserve, replacing the binary "last dispatch
        // opportunity" override that killed d1d_f130_bud. Inert while budgetMode=off - the ramp
        // only offsets the budget - but the honest chain duration it is measured against applies
        // in every arm.
        double budgetUrgencyLeadS = positiveDouble(map.getOrDefault("budgetUrgencyLeadS",
                Double.toString(hagrid.lausitz.modular.Modular.DEFAULT_BUDGET_URGENCY_LEAD_S)),
                "budgetUrgencyLeadS");
        // Launch decision, not a scenario parameter: may this launch replace a COMPLETED run in
        // its output directory? See HAGRIDSimulationConfig#validateInputFiles (review 2026-10-02 #2).
        boolean overwrite = bool(map.getOrDefault("overwrite", "false"), "overwrite");

        // Output-collision guard (review I2/M5): runId = CONCEPT_date[_tag], and MATSim's
        // deleteDirectoryIfExists wipes an existing output directory at startup. chiThreshold,
        // noParcels and openDepots are deliberately NOT encoded in the runId, so two sweep
        // points differing only in one of these with the same/no tag would silently destroy
        // each other's outputs. Require the tag to encode the sweep point instead of
        // auto-mangling the runId (run-dir naming conventions must stay stable).
        boolean isSharedUse;
        try {
            isSharedUse = hagrid.core.HagridConfig.Scenario.valueOf(concept.toUpperCase())
                    == hagrid.core.HagridConfig.Scenario.DRT_SHAREDUSE;
        } catch (IllegalArgumentException ex) {
            isSharedUse = false;
        }
        boolean isModular;
        try {
            isModular = hagrid.core.HagridConfig.Scenario.valueOf(concept.toUpperCase())
                    == hagrid.core.HagridConfig.Scenario.DRT_MODULAR;
        } catch (IllegalArgumentException ex) {
            isModular = false;
        }
        // DRT_SHAREDUSE always requires a tag (chiThreshold/noParcels are never the runId-default
        // case); DRT_MODULAR only needs one once openDepots deviates from "all" (idleThreshold/
        // maxTourDuration are out of scope for this guard - task 7 covers openDepots only).
        boolean nonDefaultOpenDepots = !openDepots.isEmpty();
        if ((isSharedUse || (isModular && nonDefaultOpenDepots)) && tag.isEmpty()) {
            throw new IllegalArgumentException(
                    "DRT_SHAREDUSE requires a non-blank tag; DRT_MODULAR requires one whenever"
                            + " openDepots is set: chiThreshold/noParcels/openDepots are not part"
                            + " of the runId (CONCEPT_date[_tag]) and MATSim deletes an existing"
                            + " output directory, so two sweep points differing only in one of"
                            + " these would silently overwrite each other. Encode the sweep point"
                            + " in the tag, e.g. tag=chi600, tag=chi0noparcels or tag=depot3.");
        }

        // Lausitz-bound concepts (all DRT scenarios + LMD_BASELINE) require LAUSITZ_HOYERSWERDA.
        boolean requiresLausitz;
        try {
            requiresLausitz = hagrid.core.HagridConfig.Scenario.valueOf(concept.toUpperCase()).requiresLausitz();
        } catch (IllegalArgumentException ex) {
            // Every legitimate concept, Hannover's included, is a Scenario constant, so this
            // branch means a typo. Behaviour kept (treated as non-Lausitz), but not silently.
            LOG.warn("Unknown concept '{}' (not a HagridConfig.Scenario) - treated as a non-Lausitz"
                    + " concept; check the spelling", concept);
            requiresLausitz = false;
        }
        if (requiresLausitz) {
            if (!map.containsKey("studyArea")) {
                studyArea = StudyArea.LAUSITZ_HOYERSWERDA;   // default it for the user
            } else if (studyArea != StudyArea.LAUSITZ_HOYERSWERDA) {
                throw new IllegalArgumentException(
                        "concept '" + concept + "' requires studyArea=LAUSITZ_HOYERSWERDA, got " + studyArea);
            }
        }

        // maxIter=0 is a routing-only shortcut exclusively for LMD_BASELINE.
        // All other concepts (including DRT and BASECASE) require at least one MATSim iteration.
        if (maxIter == 0) {
            boolean isLmd;
            try {
                isLmd = hagrid.core.HagridConfig.Scenario.valueOf(concept.toUpperCase())
                        == hagrid.core.HagridConfig.Scenario.LMD_BASELINE;
            } catch (IllegalArgumentException ex) {
                isLmd = false;
            }
            if (!isLmd) {
                throw new IllegalArgumentException(
                        "maxIter=0 is only allowed for LMD_BASELINE; concept '"
                                + concept + "' requires maxIter > 0");
            }
        }

        LOG.info("Scenario: concept={} date={} tag={} maxIter={} jspritIter={} uTurnPenalty={} studyArea={} fleetSize={} freight={} kpiDashboard={} chiThreshold={} noParcels={} seed={} idleThreshold={} maxTourDuration={} openDepots={} maxJobsPerDistrict={} maxConcurrentFreight={} freightWindows={} budgetMode={} budgetSmoothing={} budgetHeadroom={} budgetUrgencyLeadS={} overwrite={}",
                concept, date, tag.isEmpty() ? "(none)" : tag, maxIter, jspritIter, uTurnPenaltyCost, studyArea, fleetSize, drtWithFreight, kpiDashboard, chiThreshold, noParcels, seed, idleThreshold, maxTourDuration, openDepots.isEmpty() ? "all" : openDepots, maxJobsPerDistrict,
                maxConcurrentFreight == 0 ? "unlimited" : maxConcurrentFreight,
                freightWindows.isEmpty() ? "always-open" : freightWindows,
                budgetMode, budgetSmoothing, budgetHeadroom, budgetUrgencyLeadS, overwrite);

        HAGRIDSimulationConfig cfg = new HAGRIDSimulationConfig(concept, date, maxIter, jspritIter,
                zoneCaching, zoneThreshold, uTurnPenaltyCost, tag, studyArea, fleetSize,
                drtWithFreight, kpiDashboard, chiThreshold, noParcels, seed,
                idleThreshold, maxTourDuration, openDepots, maxJobsPerDistrict,
                maxConcurrentFreight, freightWindows,
                budgetMode, budgetSmoothing, budgetHeadroom, budgetUrgencyLeadS);
        if(configurationFile!=null) cfg.captureConfigurationFile(configurationFile);
        cfg.captureExplicitScenarioInputs(explicitCli);
        cfg.setOverwriteCompletedRun(overwrite);
        return cfg;
    }

    /**
     * Parses an array of scenario specification strings.
     */
    public static List<HAGRIDSimulationConfig> parseScenarios(String[] args) {
        List<HAGRIDSimulationConfig> list = new ArrayList<>();
        for (String arg : args) {
            list.add(parseScenario(arg.trim()));
        }
        LOG.info("Parsed {} scenario(s)", list.size());
        return list;
    }

    // ====================================================================
    // Validation
    // ====================================================================

    /**
     * Validates required input files for all scenarios. Throws on first batch
     * of errors to prevent running with incomplete inputs.
     */
    public static void validateAll(List<HAGRIDSimulationConfig> configs) {
        // Review 2026-10-02 #2: two specs of ONE launch with the same output directory run one
        // after the other, and the second deletes the first at its own startup. The completed-run
        // guard in validateInputFiles cannot see this - at validation time neither has run yet.
        Map<Path, Integer> firstSpec = new LinkedHashMap<>();
        List<String> collisions = new ArrayList<>();
        for (int i = 0; i < configs.size(); i++) {
            Path out = configs.get(i).getOutputDirectory().toAbsolutePath().normalize();
            Integer first = firstSpec.putIfAbsent(out, i);
            if (first != null) {
                collisions.add("scenario " + (first + 1) + " and " + (i + 1) + " -> " + out);
            }
        }
        if (!collisions.isEmpty()) {
            throw new IllegalStateException("Scenarios of this launch write to the same output"
                    + " directory, so the later one would delete the earlier one's results."
                    + " Give each its own tag: " + String.join("; ", collisions));
        }

        // Final review I2: the per-run guard in runSimulation would let scenario k abort only after
        // 1..k-1 spent their compute; check every DRT scenario's output path up front (the per-run
        // check stays, for callers that skip validateAll).
        OutputPathGuard.enforceAll(configs);

        LOG.info("Validating input files for {} scenario(s)...", configs.size());
        List<String> errors = new ArrayList<>();
        for (HAGRIDSimulationConfig cfg : configs) {
            try {
                cfg.validateInputFiles();
            } catch (IllegalStateException e) {
                errors.add("[" + cfg.getRunId() + "] " + e.getMessage());
            }
        }
        if (!errors.isEmpty()) {
            errors.forEach(LOG::error);
            throw new IllegalStateException("Aborting — missing input files (see above).");
        }
        LOG.info("All input files OK.");
    }

    // ====================================================================
    // Simulation execution
    // ====================================================================

    /**
     * Whether a run carries the offline-routed LMD carriers INSIDE the mobsim ({@code CarrierModule}).
     * DRT_SHAREDUSE: parcels ride the DRT fleet as passengers - no carriers (1c D7).
     * DRT_MODULAR: the DRT fleet executes the jsprit tours itself via task splicing - running
     * the CarrierModule too would deliver every parcel TWICE (phantom vans + DRT), silently
     * corrupting every freight KPI (design §3.4). This is the SINGLE source of that rule; every
     * call site in {@link #runSimulation} routes through this method instead of re-deriving it.
     */
    static boolean runsCarrierModules(hagrid.core.HagridConfig.Scenario scenario, boolean drtWithFreight) {
        return drtWithFreight
                && scenario != hagrid.core.HagridConfig.Scenario.DRT_SHAREDUSE
                && scenario != hagrid.core.HagridConfig.Scenario.DRT_MODULAR;
    }

    /**
     * Points {@code hagrid.log.dir} at this run's {@code logs/} directory -- unless it was set
     * explicitly with {@code -Dhagrid.log.dir}. Before 2026-09-28 the explicit value was
     * overwritten unconditionally; on Windows the MATSim controler then clears the run
     * directory while hagrid.log is open inside it, and the run dies before iteration 0
     * (the reason logging/log4j2_runlocal.xml exists). With the explicit value kept, a
     * {@code -Dhagrid.log.dir} outside the run directory is enough. Logging destination only.
     */
    static void pointLogDirAt(Path logDir) {
        String current = System.getProperty(LOG_DIR_PROPERTY);
        if (current != null && !current.equals(ownLogDir)) {
            LOG.info("hagrid.log.dir set explicitly ({}) - kept, not repointed into the run directory",
                    current);
            return;
        }
        try {
            Files.createDirectories(logDir);
        } catch (IOException e) {
            LOG.warn("Could not create run log directory {}: {}", logDir, e.toString());
        }
        setOwnLogDir(logDir);
    }

    /**
     * Runs a single HAGRID MATSim simulation.
     */
    private static int solverRouteDurationSeconds(hagrid.core.config.ResolvedHagridConfig config) {
        double seconds=config.tourRules().maxRouteDurationSeconds();
        if(seconds != Math.rint(seconds) || seconds <= 0 || seconds > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Lausitz solver route duration must be positive whole seconds");
        return (int)seconds;
    }

    public static void runSimulation(HAGRIDSimulationConfig cfg) throws Exception {
        // Fail fast on an output path native SQLite cannot write (METHODS-LOG 2.51), before any work.
        OutputPathGuard.enforce(cfg);

        Instant t0 = Instant.now();
        var routingConfig = cfg.getResolvedRoutingConfig();
        // Validate native preprocessing units before creating output or log directories.
        Integer lausitzSolverDuration = null;
        if (cfg.isLmdBaseline()) {
            lausitzSolverDuration = solverRouteDurationSeconds(routingConfig);
        } else if (cfg.isDrtScenario()) {
            var concept = hagrid.core.HagridConfig.Scenario.valueOf(cfg.getConcept().toUpperCase());
            if (concept == hagrid.core.HagridConfig.Scenario.DRT_MODULAR
                    || runsCarrierModules(concept, cfg.isDrtWithFreight())) {
                lausitzSolverDuration = solverRouteDurationSeconds(routingConfig);
            }
        }

        if(cfg.isLmdBaseline() || hagrid.core.HagridConfig.Scenario.valueOf(cfg.getConcept().toUpperCase()).name().equals("DRT_MODULAR")
                || (cfg.isDrtScenario() && cfg.isDrtWithFreight() && !cfg.getConcept().equalsIgnoreCase("DRT_SHAREDUSE")))
            hagrid.lausitz.freight.LmdTourRules.from(routingConfig);
        pointLogDirAt(cfg.getOutputDirectory().resolve("logs"));

        LOG.info("─── Simulation '{}' ───", cfg.getRunId());

        // DRT path: passenger DRT; married baseline additionally carries the LMD carriers.
        if (cfg.isDrtScenario()) {
            hagrid.core.HagridConfig.Scenario concept =
                    hagrid.core.HagridConfig.Scenario.valueOf(cfg.getConcept().toUpperCase());
            // DRT_SHAREDUSE (cargo hitching) rides the SAME DRT fleet as the parcel carrier —
            // it never runs the offline jsprit routing / carrier modules (D7), even if the
            // scenario spec left freight=true (its default) unset.
            boolean sharedUse = concept == hagrid.core.HagridConfig.Scenario.DRT_SHAREDUSE;
            // DRT_MODULAR (1d, capsule swap): jsprit routing YES, CarrierModule NO — the DRT
            // fleet executes the tours itself via schedule splicing (design §3.4).
            boolean modular = concept == hagrid.core.HagridConfig.Scenario.DRT_MODULAR;
            boolean carrierModules = runsCarrierModules(concept, cfg.isDrtWithFreight());

            hagrid.lausitz.freight.JspritCacheResult jspritCache = null;
            if (carrierModules) {
                // 1. offline jsprit routing - the exact same call the LMD_BASELINE uses,
                //    clipped to the SAME service-area shapefile (identical geography).
                jspritCache = hagrid.lausitz.freight.LausitzFreightPreprocessor.runConfigured(
                        cfg.getLmdDemandShapefile(), cfg.getLmdDepotCsv(),
                        cfg.getLausitzNetworkRaw(), cfg.getLmdVehicleTypes(),
                        cfg.getLmdCarriersRouted(), cfg.getJspritIterations(),
                        cfg.getDrtServiceAreaShapefile(), cfg.getJspritCacheDir(), routingConfig);
            } else if (modular) {
                // jsprit YES (capsule type + tour cap), CarrierModule NO (design §3.4). Always
                // runs regardless of the freight flag — 1d needs the LMD trio unconditionally.
                jspritCache = hagrid.lausitz.freight.LausitzFreightPreprocessor.runModularConfigured(
                        cfg.getLmdDemandShapefile(), cfg.getLmdDepotCsv(), cfg.getLausitzNetworkRaw(),
                        cfg.getLmdVehicleTypes(), cfg.getLmdCarriersRouted(), cfg.getJspritIterations(),
                        cfg.getDrtServiceAreaShapefile(),
                        cfg.getOpenDepots(), cfg.getMaxJobsPerDistrict(), cfg.getJspritCacheDir(), routingConfig);
                LOG.info("DRT_MODULAR: jsprit tours routed (cap {}s, openDepots={}, "
                        + "maxJobsPerDistrict={}); freight flag ignored - the DRT fleet executes "
                        + "them (no CarrierModule).", lausitzSolverDuration,
                        cfg.getOpenDepots().isEmpty() ? "all" : cfg.getOpenDepots(),
                        cfg.getMaxJobsPerDistrict());
            } else if (sharedUse && cfg.isDrtWithFreight()) {
                LOG.info("DRT_SHAREDUSE: freight flag ignored - parcels ride the DRT fleet (no jsprit/carriers)");
            }

            Scenario scenario = DrtScenarioBuilder.build(cfg);
            if (sharedUse) {
                // Must run BEFORE `new Controler(scenario)` below: DRT config groups are read
                // at controler construction time.
                hagrid.lausitz.drt.DrtConfigComposer.composeSharedUse(scenario.getConfig(),routingConfig.drt());
            }
            if (carrierModules) {
                hagrid.lausitz.freight.FreightRunComposer.addCarriers(
                        scenario, cfg.getLmdCarriersRouted(), cfg.getLmdVehicleTypes());
            }

            Controler controler = new Controler(scenario);
            java.util.List<org.matsim.api.core.v01.Coord> depots =
                    hagrid.lausitz.drt.DrtDepotReader.readCoords(java.nio.file.Path.of(cfg.getLmdDepotCsv()));
            double serviceEnd = routingConfig.drt().serviceEndSeconds();
            double returnWindow = routingConfig.drt().returnWindowSeconds();
            // Depot parking capacity = even fleet split (matches the spawn distribution): each depot
            // zone absorbs at most ceil(fleet/depots) returning vehicles, so the end-of-day return
            // fills the nearest depot first and overflows to the next once full.
            double perDepotCapacity = Math.ceil((double) cfg.getFleetSize() / Math.max(1, depots.size()));
            hagrid.lausitz.drt.DrtConfigComposer.installModules(controler, depots,
                    serviceEnd - returnWindow, perDepotCapacity, routingConfig.drt().depotArrivalIntervalSeconds());
            if (carrierModules) {
                hagrid.lausitz.freight.FreightRunComposer.installCarrierModules(controler, scenario, routingConfig);
                LOG.info("MARRIED baseline run '{}' (DRT fleet {} + LMD carriers).",
                        cfg.getRunId(), cfg.getFleetSize());
            } else if (sharedUse) {
                // LAST overriding module: overrides the base DRT bindings installed above
                // (PassengerStopDurationProvider / StopTimeCalculator / DvrpLoadFromFleet at
                // controller scope, InsertionCostCalculator / DrtRequestInsertionRetryQueue at
                // QSim scope) with the Shared-Use cargo-hitching versions.
                org.matsim.contrib.drt.run.DrtConfigGroup drtCfg =
                        org.matsim.contrib.drt.run.MultiModeDrtConfigGroup.get(scenario.getConfig())
                                .getModalElements().iterator().next();
                controler.addOverridingModule(
                        new hagrid.lausitz.shareduse.SharedUseModule(drtCfg, cfg.getChiThreshold(), cfg.getResolvedRoutingConfig().sharedUse()));
                if (cfg.isNoParcels()) {
                    LOG.info("SHARED-USE run '{}' (DRT fleet {}, noParcels=true - 8-seat leakage "
                            + "control, module stack inert, chiThreshold={}s ignored).",
                            cfg.getRunId(), cfg.getFleetSize(), cfg.getChiThreshold());
                } else {
                    LOG.info("SHARED-USE run '{}' (DRT fleet {} carrying parcels, chiThreshold={}s).",
                            cfg.getRunId(), cfg.getFleetSize(), cfg.getChiThreshold());
                }
            } else if (modular) {
                org.matsim.contrib.drt.run.DrtConfigGroup drtCfg =
                        org.matsim.contrib.drt.run.MultiModeDrtConfigGroup.get(scenario.getConfig())
                                .getModalElements().iterator().next();
                // Tour-loading sequence mirrors ModularEndToEndTest (Task 10) exactly: read the
                // routed carriers this same runSimulation() call just wrote above (sequential,
                // single-threaded — the file exists by the time this executes), build the car and
                // DRT networks, convert, and install the dispatch module.
                // NOTE (Task 11 review, positional note): ModularEndToEndTest computes `tours`
                // BEFORE `new Controler(scenario)` (it needs them earlier only incidentally, to
                // mutate the rebalancing zone-cell-size config first) — here they are read AFTER
                // `installModules`/`new Controler` instead. This is not drift: reading the routed
                // carriers off disk and filtering the network are pure file/network reads with no
                // dependency on Controler state, so the two orderings are equivalent. Both must
                // still run before `controler.run()`, which they do.
                org.matsim.freight.carriers.Carriers routed = hagrid.lausitz.modular.ModularTourConverter
                        .read(cfg.getLmdCarriersRouted(),
                                hagrid.lausitz.modular.ModularVehicleTypes
                                        .createCapsuleTypes(cfg.getLmdVehicleTypes()));
                org.matsim.api.core.v01.network.Network carNet =
                        hagrid.lausitz.freight.LausitzFreightPreprocessor.carNetwork(
                                org.matsim.core.network.NetworkUtils.readNetwork(cfg.getLausitzNetworkRaw()));
                org.matsim.api.core.v01.network.Network drtNet =
                        org.matsim.core.network.NetworkUtils.createNetwork();
                new org.matsim.core.network.algorithms.TransportModeNetworkFilter(
                        org.matsim.core.network.NetworkUtils.readNetwork(cfg.getDrtNetworkClipped()))
                        .filter(drtNet, java.util.Set.of(org.matsim.api.core.v01.TransportMode.drt));
                java.util.List<hagrid.lausitz.modular.ModularFreightTour> tours =
                        hagrid.lausitz.modular.ModularTourConverter.convert(routed, carNet, drtNet);
                // Task 1 (paper-readiness review F1/F3/F5/F7, METHODS-LOG 2.16): plan-time
                // accounting computed ONCE right after convert(), same pattern ModularEndToEndTest
                // / ModularE2eStaging use, and handed into the module so ModularKpiHandler can
                // publish parcels_demand/parcels_unassigned_jsprit/parcels_missed_overlay/
                // max_parcels_per_tour/peak_concurrent_swaps without ever touching Carriers itself.
                hagrid.lausitz.modular.ModularPlanStats planStats =
                        hagrid.lausitz.modular.ModularTourConverter.planStats(routed, tours);
                controler.addOverridingModule(new hagrid.lausitz.modular.ModularDispatchModule(
                        drtCfg, tours, cfg.getIdleThreshold(), cfg.getMaxConcurrentFreight(),
                        cfg.getFreightWindows(), cfg.getBudgetMode(), cfg.getBudgetSmoothing(),
                        cfg.getBudgetHeadroom(), cfg.getBudgetUrgencyLeadS(), planStats, cfg.getResolvedRoutingConfig().modular()));
                LOG.info("MODULAR run '{}' (DRT fleet {}, {} freight tours, idleThreshold={}, cap={}s,"
                        + " maxConcurrentFreight={}, freightWindows={}, budgetMode={},"
                        + " budgetSmoothing={}, budgetHeadroom={}, budgetUrgencyLeadS={},"
                        + " unassigned={}).",
                        cfg.getRunId(), cfg.getFleetSize(), tours.size(), cfg.getIdleThreshold(),
                        cfg.getMaxTourDurationSeconds(), cfg.getMaxConcurrentFreight(),
                        cfg.getFreightWindows(), cfg.getBudgetMode(), cfg.getBudgetSmoothing(),
                        cfg.getBudgetHeadroom(), cfg.getBudgetUrgencyLeadS(),
                        planStats.parcelsUnassignedJsprit());
            } else {
                LOG.info("DRT passenger-only run '{}' (fleet {}).", cfg.getRunId(), cfg.getFleetSize());
            }
            controler.run();
            logDuration("Simulation '" + cfg.getRunId() + "'", t0);
            writeRunMetadataSafely(cfg, jspritCache);
            KpiDashboardTrigger.triggerSafely(cfg);
            return;
        }

        // LMD baseline: dedicated conventional multi-LSP delivery on the Lausitz network.
        if (cfg.isLmdBaseline()) {
            // 1. preprocess: produce the routed carrier XML
            // Clip LMD demand to the SAME service-area shapefile the DRT uses, so both baselines
            // cover identical geography (no out-of-area outliers like Ruhland that DRT can't reach).
            hagrid.lausitz.freight.JspritCacheResult jspritCache = hagrid.lausitz.freight.LausitzFreightPreprocessor.runConfigured(
                    cfg.getLmdDemandShapefile(), cfg.getLmdDepotCsv(),
                    cfg.getLausitzNetworkRaw(), cfg.getLmdVehicleTypes(),
                    cfg.getLmdCarriersRouted(), cfg.getJspritIterations(),
                    cfg.getDrtServiceAreaShapefile(), cfg.getJspritCacheDir(), routingConfig);

            // 2. build the run scenario on the Lausitz network with the routed carriers
            Config config = ConfigUtils.createConfig();
            // F3: runner-key seed (default 1337; was the MATSim default 4711 before the key
            // existed). LMD KPIs are plan-pinned/deterministic, but keep every simulation
            // path consistently seeded so error-band replicates only vary via seed=.
            config.global().setRandomSeed(cfg.getSeed());
            config.network().setInputFile(cfg.getLausitzNetworkRaw());
            config.controller().setOutputDirectory(cfg.getOutputDirectoryAsString());
            config.controller().setRunId(cfg.getRunId());
            config.controller().setLastIteration(cfg.getMaxIterations());
            config.controller().setOverwriteFileSetting(
                    OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
            Scenario scenario = ScenarioUtils.loadScenario(config);
            hagrid.lausitz.freight.FreightRunComposer.addCarriers(
                    scenario, cfg.getLmdCarriersRouted(), cfg.getLmdVehicleTypes());

            Controler controler = new Controler(scenario);
            hagrid.lausitz.freight.FreightRunComposer.installCarrierModules(controler, scenario, routingConfig);
            LOG.info("LMD baseline run '{}' on the Lausitz network.", cfg.getRunId());
            controler.run();
            logDuration("Simulation '" + cfg.getRunId() + "'", t0);
            writeRunMetadataSafely(cfg, jspritCache);
            KpiDashboardTrigger.triggerSafely(cfg);
            return;
        }

        // --- Freight / Hannover path (unchanged) ---

        // Load freight zones
        Collection<SimpleFeature> zones =
                GeoFileReader.getAllFeatures(cfg.getFreightZonePath().toString());
        LOG.info("Freight zones: {} features", zones.size());

        // Build scenario
        Scenario scenario = HAGRIDScenarioBuilder.build(cfg, zones);

        // Setup MATSim
        Controler controler = new Controler(scenario);
        controler.addOverridingModule(new CarrierModule());
        controler.addOverridingModule(new HAGRIDSimulationModule(
                scenario, true, cfg.getMaxIterations(), routingConfig));

        // Run
        LOG.info("Output: {}", cfg.getOutputDirectoryAsString());
        controler.run();

        logDuration("Simulation '" + cfg.getRunId() + "'", t0);
        writeRunMetadataSafely(cfg, null);
        KpiDashboardTrigger.triggerSafely(cfg);


    }

    /**
     * Writes {@code run_metadata.json} for a completed run, never throwing: a bug in the
     * metadata writer must not kill a multi-hour MATSim run. Catches {@code Exception} (not just
     * {@code IOException}) so that any future unchecked failure inside {@code RunMetadataWriter}
     * is swallowed the same way.
     */
    private static void writeRunMetadataSafely(HAGRIDSimulationConfig cfg,
                                               hagrid.lausitz.freight.JspritCacheResult jspritCache) {
        try {
            RunMetadataWriter.write(cfg, cfg.getOutputDirectory(), jspritCache);
            LOG.info("run_metadata.json written to {}", cfg.getOutputDirectory());
        } catch (Exception e) {
            LOG.warn("Could not write run_metadata.json (analysis falls back to dir-name parsing)", e);
        }
    }

    // ====================================================================
    // Dashboard generation (reuses HAGRIDAnalysisRunner logic)
    // ====================================================================

    /**
     * Generates the analysis dashboard for a completed simulation run.
     * Reads events, carriers, network from MATSim output → produces HTML.
     *
     * <p>No-op for scenarios that do not run the carrier modules (1c/1d): this dashboard is built
     * from {@code output_carriers.xml.gz}, which those runs never write. Before the guard, a
     * {@code writeDashboard=true} spec made every 1c/1d run exit non-zero from
     * {@code HAGRIDSimulationRunner.main} although simulation, KPIs and the v2 dashboard had all
     * completed. Their analysis channel is the Python v2 dashboard ({@link KpiDashboardTrigger}).</p>
     */
    public static void generateDashboard(HAGRIDSimulationConfig cfg) throws Exception {
        hagrid.core.HagridConfig.Scenario concept =
                hagrid.core.HagridConfig.Scenario.valueOf(cfg.getConcept().toUpperCase());
        // The `isDrtScenario()` prefix is load-bearing, not defensive: isDrtWithFreight() is
        // `isDrtScenario() && drtWithFreight`, so it is false for LMD_BASELINE and for the
        // Hannover BASECASE — gating on runsCarrierModules alone would silently skip the
        // dashboard the Hannover sweep reads its SUMMARY blob from.
        if (cfg.isDrtScenario() && !runsCarrierModules(concept, cfg.isDrtWithFreight())) {
            LOG.warn("writeDashboard=true ignored for {}: the legacy dashboard needs carriers, "
                    + "which {} never routes. Use the v2 KPI dashboard (kpiDashboard=true).",
                    cfg.getRunId(), concept);
            return;
        }
        Instant t0 = Instant.now();
        HagridPaths paths = new HagridPaths();
        paths.initializeRun(cfg.getRunId());

        Path matsimDir = paths.matsimRunDir(cfg.getMaxIterations(), cfg.getJspritIterations());
        LOG.info("─── Dashboard for '{}' ───", cfg.getRunId());

        String prefix = cfg.getRunId();
        Path eventsFile  = matsimDir.resolve(prefix + ".output_events.xml.gz");
        Path carriersFile = matsimDir.resolve(prefix + ".output_carriers.xml.gz");
        Path networkFile  = matsimDir.resolve(prefix + ".output_network.xml.gz");

        List<String> missing = new ArrayList<>();
        if (!Files.exists(eventsFile))   missing.add("events: " + eventsFile);
        if (!Files.exists(carriersFile)) missing.add("carriers: " + carriersFile);
        if (!Files.exists(networkFile))  missing.add("network: " + networkFile);
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Missing output files:\n  " + String.join("\n  ", missing));
        }

        // Network
        Network network = NetworkUtils.createNetwork();
        new MatsimNetworkReader(network).readFile(networkFile.toAbsolutePath().toString());
        LOG.info("Network: {} nodes, {} links", network.getNodes().size(), network.getLinks().size());

        // Events
        FreightEventHandler handler = new FreightEventHandler();
        EventsManager em = EventsUtils.createEventsManager();
        em.addHandler(handler);
        new MatsimEventsReader(em).readFile(eventsFile.toAbsolutePath().toString());
        LOG.info("Events: {} processed, {} vehicles", handler.getTotalEventsProcessed(),
                handler.getVehicleTours().size());

        // Carriers
        List<ParsedCarrier> carriers = CarrierXmlParser.parse(carriersFile);
        LOG.info("Carriers: {} total", carriers.size());

        // Vehicle types
        Path vtFile = matsimDir.resolve(prefix + ".output_carriersVehicleTypes.xml.gz");
        Map<String, Double> caps  = Files.exists(vtFile) ? CarrierXmlParser.parseVehicleTypes(vtFile) : Map.of();
        Map<String, Double> fixes = Files.exists(vtFile) ? CarrierXmlParser.parseVehicleTypeFixedCosts(vtFile) : Map.of();
        Map<String, Double> cpkm  = Files.exists(vtFile) ? CarrierXmlParser.parseVehicleTypeCostsPerKm(vtFile) : Map.of();

        // Generate
        String dashRunId = prefix + "_iter" + cfg.getMaxIterations() + "_jsprit" + cfg.getJspritIterations();
        Path outDir = matsimDir.resolve("analysis");
        DashboardGenerator gen = new DashboardGenerator(dashRunId, network, handler, carriers, caps, fixes, cpkm, outDir)
                .setLowUtilThreshold(0.05);
        Path html = gen.generate();

        logDuration("Dashboard '" + html.getFileName() + "'", t0);
    }

    // ====================================================================
    // Help / usage
    // ====================================================================

    public static boolean isHelpRequested(String[] args) {
        if (args.length == 0) return true;
        for (String a : args) {
            String s = a.trim().toLowerCase(Locale.ROOT);
            if (s.equals("help") || s.equals("--help") || s.equals("-h")) return true;
        }
        return false;
    }

    public static void printUsage() {
        LOG.info("""
            Usage:
              java hagrid.core.simulation.HAGRIDSimulationRunner <scenario> [<scenario> ...]

            Each <scenario> is comma-separated key=value:
              concept        (required) scenario concept name
              date           (required) yyyy-MM-dd
              tag            version tag appended to run ID (optional, e.g. V1)
              maxIter        MATSim iterations (default 150)
              jspritIter     jsprit iterations (default 100)
              cargoBikeMaxSpeedKmh maximum cargo bike speed in km/h (default 20; routing adapter)
              solverMaxRouteDuration  positive route cap in seconds (default 25200; modular tour default preserved)
              solverThreads  positive internal jsprit worker count (default 1; owned per search)
              maxActiveSolvers positive outer solver limit (serial default; larger pools need calibrated budgets)
              eventsEvery    positive event output interval (unset keeps native XML; final retained)
              plansEvery     positive plan output interval (unset keeps native XML; final retained)
              freightHistogramEvery positive freight/population histogram interval (default 1; final retained)
              uTurnPenalty   score penalty per U-turn (default 1.0)
              studyArea      HANNOVER or LAUSITZ_HOYERSWERDA (default HANNOVER)
              fleetSize      DRT fleet size in vehicles (default 50; only used for DRT concepts)
              seed           MATSim global random seed (default 1337; vary for error-band replicates)
              writeDashboard true/false (default false) \u2014 generate dashboard after sim
              overwrite      true/false (default false) \u2014 allow replacing a COMPLETED run
                             (one with run_metadata.json) in the output directory; a crashed
                             run restarts without it

            Keys are case-sensitive; an unknown key or a key set twice aborts the launch.

            Note (DRT scenarios): the clipped network, clipped population, and fleet file must be
              pre-generated (by DrtNetworkPreparer / PopulationClipper / DrtFleetGenerator) before
              running a DRT concept; validateInputFiles() will report them missing otherwise.

            Example (freight):
              concept=basecase,date=2025-05-13,tag=V1,maxIter=150,jspritIter=10000,writeDashboard=true
            Example (DRT):
              concept=drt_baseline,date=2025-05-13,studyArea=LAUSITZ_HOYERSWERDA,fleetSize=20
            """);
    }

    // ====================================================================
    // Internal helpers
    // ====================================================================

    private static String require(Map<String, String> map, String key) {
        String v = map.get(key);
        if (v == null || v.isBlank()) throw new IllegalArgumentException("Missing required key: " + key);
        return v;
    }

    private static int positiveInt(String s, String name) {
        try {
            int v = Integer.parseInt(s.trim());
            if (v <= 0) throw new IllegalArgumentException(name + " must be positive: " + v);
            return v;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid integer for " + name + ": " + s, ex);
        }
    }

    private static int nonNegInt(String s, String name) {
        try {
            int v = Integer.parseInt(s.trim());
            if (v < 0) throw new IllegalArgumentException(name + " must be >= 0: " + v);
            return v;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid integer for " + name + ": " + s, ex);
        }
    }

    private static boolean bool(String s, String name) {
        return switch (s.trim().toLowerCase(Locale.ROOT)) {
            case "true", "1", "yes" -> true;
            case "false", "0", "no" -> false;
            default -> throw new IllegalArgumentException("Invalid boolean for " + name + ": " + s);
        };
    }

    /**
     * Parses {@code budgetMode} (plan 2026-09-04). Case-insensitive on the way in and mapped to
     * the enum constant, the same convention {@code studyArea} uses
     * ({@code StudyArea.valueOf(raw.trim().toUpperCase())}); the loud, allowed-set-naming failure
     * message is the {@link #bool(String, String)} convention. {@code valueOf} alone would name
     * the bad value but not what IS allowed, and a typo here silently switching the budget off
     * would produce a run that looks like it used the feature and did not.
     */
    private static hagrid.lausitz.modular.Modular.BudgetMode budgetMode(String s, String name) {
        String raw = s.trim();
        for (hagrid.lausitz.modular.Modular.BudgetMode m
                : hagrid.lausitz.modular.Modular.BudgetMode.values()) {
            if (m.name().equalsIgnoreCase(raw)) {
                return m;
            }
        }
        StringBuilder allowed = new StringBuilder();
        for (hagrid.lausitz.modular.Modular.BudgetMode m
                : hagrid.lausitz.modular.Modular.BudgetMode.values()) {
            if (allowed.length() > 0) allowed.append(", ");
            allowed.append(m.name().toLowerCase(Locale.ROOT));
        }
        throw new IllegalArgumentException("Invalid " + name + ": '" + s + "' - allowed values are "
                + allowed + " (case-insensitive)");
    }

    /**
     * Parses a strictly positive, finite double (e.g. {@code budgetUrgencyLeadS}).
     *
     * <p>Zero is rejected, not clamped: a zero urgency lead would divide by zero in the ramp and,
     * worse, would silently restore the binary override the ramp exists to replace - a run that
     * looks configured and is not.
     */
    private static double positiveDouble(String s, String name) {
        try {
            double v = Double.parseDouble(s.trim());
            if (!(v > 0.0) || Double.isInfinite(v)) {
                throw new IllegalArgumentException(
                        name + " must be positive and finite: " + v);
            }
            return v;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid number for " + name + ": " + s, ex);
        }
    }

    /** Parses a SHARE: a double that must lie in [0.0, 1.0] (e.g. {@code budgetHeadroom}). */
    private static double shareDouble(String s, String name) {
        try {
            double v = Double.parseDouble(s.trim());
            if (!(v >= 0.0 && v <= 1.0)) {
                throw new IllegalArgumentException(
                        name + " is a SHARE and must lie in [0.0, 1.0]: " + v);
            }
            return v;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid number for " + name + ": " + s, ex);
        }
    }

    public static boolean parseBool(String s) {
        if (s == null) return false;
        return switch (s.trim().toLowerCase(Locale.ROOT)) {
            case "true", "1", "yes" -> true;
            default -> false;
        };
    }

    /**
     * Extracts the {@code writeDashboard} flag from each raw scenario spec.
     *
     * @param args raw CLI arguments (one spec per element)
     * @return boolean array aligned with {@code args}
     */
    public static boolean[] extractDashboardFlags(String[] args) {
        boolean[] flags = new boolean[args.length];
        for (int i = 0; i < args.length; i++) {
            for (String token : args[i].split(",")) {
                String[] kv = token.split("=", 2);
                if (kv.length == 2 && kv[0].trim().equalsIgnoreCase("writeDashboard")) {
                    flags[i] = parseBool(kv[1]);
                    break;
                }
            }
        }
        return flags;
    }

    private static double nonNegDouble(String s, String name) {
        try {
            double v = Double.parseDouble(s.trim());
            if (v < 0) throw new IllegalArgumentException(name + " must be >= 0: " + v);
            return v;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid number for " + name + ": " + s, ex);
        }
    }

    /** Plain long parse (any value allowed — a seed is just a seed). */
    private static long anyLong(String s, String name) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid long for " + name + ": " + s, ex);
        }
    }

    /** Plain double parse (negatives allowed — e.g. chiThreshold&lt;0 = closed gate); rejects NaN. */
    private static double anyDouble(String s, String name) {
        try {
            double v = Double.parseDouble(s.trim());
            if (Double.isNaN(v)) throw new IllegalArgumentException(name + " must not be NaN: " + s);
            return v;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid number for " + name + ": " + s, ex);
        }
    }

    private static void logDuration(String label, Instant start) {
        logDuration(label, Duration.between(start, Instant.now()));
    }

    static void logDuration(String label, Duration d) {
        // log4j only knows {} - the former {:02d} placeholders were printed literally
        LOG.info("{} completed in {}", label, String.format("%02d:%02d:%02d",
                d.toHours(), d.toMinutesPart(), d.toSecondsPart()));
    }
}
