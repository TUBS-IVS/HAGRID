package hagrid.simulation;

import hagrid.HagridPaths;
import hagrid.utils.general.StudyArea;
import hagrid.analysis.CarrierXmlParser;
import hagrid.analysis.CarrierXmlParser.ParsedCarrier;
import hagrid.analysis.DashboardGenerator;
import hagrid.analysis.FreightEventHandler;
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
        if (System.getProperty("hagrid.log.dir") == null) {
            try {
                Path logDir = Path.of("hagrid-matsim-output", "logs");
                Files.createDirectories(logDir);
                System.setProperty("hagrid.log.dir", logDir.toAbsolutePath().toString());
            } catch (Exception ignored) {
                // fallback: let log4j2.xml default handle it
            }
        }
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
     * Parses a single scenario specification of the form
     * {@code concept=...,date=...,maxIter=...,jspritIter=...,writeDashboard=true}.
     *
     * <p>Comma is both the delimiter BETWEEN {@code key=value} tokens and, for a multi-value key
     * like {@code openDepots} (district-based depot assignment, spec 2026-08-17, e.g.
     * {@code openDepots=wittichenau,hoy_sued,doergenhausen}), the delimiter WITHIN a value —
     * there is no escaping mechanism. A token with no {@code =} is therefore not rejected: it is
     * treated as a continuation of the previous key's value and re-joined with a comma, so a
     * multi-value key's own commas survive the top-level split.</p>
     *
     * @param spec comma-separated key=value string
     * @return parsed configuration
     */
    public static HAGRIDSimulationConfig parseScenario(String spec) {
        if (spec == null || spec.isBlank()) {
            throw new IllegalArgumentException("Empty scenario specification");
        }

        Map<String, String> map = new LinkedHashMap<>();
        String lastKey = null;
        for (String token : spec.split(",")) {
            if (!token.contains("=")) {
                if (lastKey == null || token.isBlank()) {
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
            map.put(lastKey, kv[1].trim());
        }

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
        double zoneThreshold = map.containsKey("zoneThreshold")
                ? nonNegDouble(map.get("zoneThreshold"), "zoneThreshold")
                : (zoneCaching ? 1500.0 : 0.0);
        double uTurnPenaltyCost = nonNegDouble(map.getOrDefault("uTurnPenalty", "1.0"), "uTurnPenalty");
        String tag = map.getOrDefault("tag", "").trim();

        StudyArea studyArea = StudyArea.valueOf(
                map.getOrDefault("studyArea", "HANNOVER").trim().toUpperCase());
        int fleetSize = positiveInt(map.getOrDefault("fleetSize", "50"), "fleetSize");
        boolean drtWithFreight = bool(map.getOrDefault("freight", "true"), "freight");
        boolean kpiDashboard = bool(map.getOrDefault("kpiDashboard", "true"), "kpiDashboard");
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
        // 1337 keeps identical specs identical. NOTE: the jsprit seed is a separate
        // system-property override and is NOT affected by this key.
        long seed = anyLong(map.getOrDefault("seed", "1337"), "seed");
        // Modular dispatch gate (design D6) and jsprit tour cap (design D5, seconds). Same
        // parse/ctor pattern as chiThreshold; harmless defaults for every other concept.
        double idleThreshold = nonNegDouble(map.getOrDefault("idleThreshold",
                Double.toString(hagrid.integrated.modular.Modular.DEFAULT_IDLE_THRESHOLD)), "idleThreshold");
        int maxTourDuration = positiveInt(map.getOrDefault("maxTourDuration",
                Integer.toString(hagrid.integrated.modular.Modular.DEFAULT_MAX_TOUR_DURATION_S)), "maxTourDuration");
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

        // Output-collision guard (review I2/M5): runId = CONCEPT_date[_tag], and MATSim's
        // deleteDirectoryIfExists wipes an existing output directory at startup. chiThreshold,
        // noParcels and openDepots are deliberately NOT encoded in the runId, so two sweep
        // points differing only in one of these with the same/no tag would silently destroy
        // each other's outputs. Require the tag to encode the sweep point instead of
        // auto-mangling the runId (run-dir naming conventions must stay stable).
        boolean isSharedUse;
        try {
            isSharedUse = hagrid.HagridConfig.Scenario.valueOf(concept.toUpperCase())
                    == hagrid.HagridConfig.Scenario.DRT_SHAREDUSE;
        } catch (IllegalArgumentException ex) {
            isSharedUse = false;
        }
        boolean isModular;
        try {
            isModular = hagrid.HagridConfig.Scenario.valueOf(concept.toUpperCase())
                    == hagrid.HagridConfig.Scenario.DRT_MODULAR;
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
            requiresLausitz = hagrid.HagridConfig.Scenario.valueOf(concept.toUpperCase()).requiresLausitz();
        } catch (IllegalArgumentException ex) {
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
                isLmd = hagrid.HagridConfig.Scenario.valueOf(concept.toUpperCase())
                        == hagrid.HagridConfig.Scenario.LMD_BASELINE;
            } catch (IllegalArgumentException ex) {
                isLmd = false;
            }
            if (!isLmd) {
                throw new IllegalArgumentException(
                        "maxIter=0 is only allowed for LMD_BASELINE; concept '"
                                + concept + "' requires maxIter > 0");
            }
        }

        LOG.info("Scenario: concept={} date={} tag={} maxIter={} jspritIter={} zoneCaching={} zoneThreshold={}m uTurnPenalty={} studyArea={} fleetSize={} freight={} kpiDashboard={} chiThreshold={} noParcels={} seed={} idleThreshold={} maxTourDuration={} openDepots={} maxJobsPerDistrict={}",
                concept, date, tag.isEmpty() ? "(none)" : tag, maxIter, jspritIter, zoneCaching, zoneThreshold, uTurnPenaltyCost, studyArea, fleetSize, drtWithFreight, kpiDashboard, chiThreshold, noParcels, seed, idleThreshold, maxTourDuration, openDepots.isEmpty() ? "all" : openDepots, maxJobsPerDistrict);

        return new HAGRIDSimulationConfig(concept, date, maxIter, jspritIter,
                zoneCaching, zoneThreshold, uTurnPenaltyCost, tag, studyArea, fleetSize,
                drtWithFreight, kpiDashboard, chiThreshold, noParcels, seed,
                idleThreshold, maxTourDuration, openDepots, maxJobsPerDistrict);
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
    static boolean runsCarrierModules(hagrid.HagridConfig.Scenario scenario, boolean drtWithFreight) {
        return drtWithFreight
                && scenario != hagrid.HagridConfig.Scenario.DRT_SHAREDUSE
                && scenario != hagrid.HagridConfig.Scenario.DRT_MODULAR;
    }

    /**
     * Runs a single HAGRID MATSim simulation.
     */
    public static void runSimulation(HAGRIDSimulationConfig cfg) throws Exception {
        Instant t0 = Instant.now();

        Path logDir = cfg.getOutputDirectory().resolve("logs");
        try { Files.createDirectories(logDir); } catch (IOException ignored) {}
        System.setProperty("hagrid.log.dir", logDir.toAbsolutePath().toString());

        LOG.info("─── Simulation '{}' ───", cfg.getRunId());

        // DRT path: passenger DRT; married baseline additionally carries the LMD carriers.
        if (cfg.isDrtScenario()) {
            hagrid.HagridConfig.Scenario concept =
                    hagrid.HagridConfig.Scenario.valueOf(cfg.getConcept().toUpperCase());
            // DRT_SHAREDUSE (cargo hitching) rides the SAME DRT fleet as the parcel carrier —
            // it never runs the offline jsprit routing / carrier modules (D7), even if the
            // scenario spec left freight=true (its default) unset.
            boolean sharedUse = concept == hagrid.HagridConfig.Scenario.DRT_SHAREDUSE;
            // DRT_MODULAR (1d, capsule swap): jsprit routing YES, CarrierModule NO — the DRT
            // fleet executes the tours itself via schedule splicing (design §3.4).
            boolean modular = concept == hagrid.HagridConfig.Scenario.DRT_MODULAR;
            boolean carrierModules = runsCarrierModules(concept, cfg.isDrtWithFreight());

            if (carrierModules) {
                // 1. offline jsprit routing - the exact same call the LMD_BASELINE uses,
                //    clipped to the SAME service-area shapefile (identical geography).
                hagrid.integrated.freight.LausitzFreightPreprocessor.run(
                        cfg.getLmdDemandShapefile(), cfg.getLmdDepotCsv(),
                        cfg.getLausitzNetworkRaw(), cfg.getLmdVehicleTypes(),
                        cfg.getLmdCarriersRouted(), cfg.getJspritIterations(),
                        cfg.getDrtServiceAreaShapefile());
            } else if (modular) {
                // jsprit YES (capsule type + tour cap), CarrierModule NO (design §3.4). Always
                // runs regardless of the freight flag — 1d needs the LMD trio unconditionally.
                hagrid.integrated.freight.LausitzFreightPreprocessor.runModular(
                        cfg.getLmdDemandShapefile(), cfg.getLmdDepotCsv(), cfg.getLausitzNetworkRaw(),
                        cfg.getLmdVehicleTypes(), cfg.getLmdCarriersRouted(), cfg.getJspritIterations(),
                        cfg.getDrtServiceAreaShapefile(), cfg.getMaxTourDurationSeconds(),
                        cfg.getOpenDepots(), cfg.getMaxJobsPerDistrict());
                LOG.info("DRT_MODULAR: jsprit tours routed (cap {}s, openDepots={}, "
                        + "maxJobsPerDistrict={}); freight flag ignored - the DRT fleet executes "
                        + "them (no CarrierModule).", cfg.getMaxTourDurationSeconds(),
                        cfg.getOpenDepots().isEmpty() ? "all" : cfg.getOpenDepots(),
                        cfg.getMaxJobsPerDistrict());
            } else if (sharedUse && cfg.isDrtWithFreight()) {
                LOG.info("DRT_SHAREDUSE: freight flag ignored - parcels ride the DRT fleet (no jsprit/carriers)");
            }

            Scenario scenario = DrtScenarioBuilder.build(cfg);
            if (sharedUse) {
                // Must run BEFORE `new Controler(scenario)` below: DRT config groups are read
                // at controler construction time.
                hagrid.integrated.drt.DrtConfigComposer.composeSharedUse(scenario.getConfig());
            }
            if (carrierModules) {
                hagrid.integrated.freight.FreightRunComposer.addCarriers(
                        scenario, cfg.getLmdCarriersRouted(), cfg.getLmdVehicleTypes());
            }

            Controler controler = new Controler(scenario);
            java.util.List<org.matsim.api.core.v01.Coord> depots =
                    hagrid.integrated.drt.DrtDepotReader.readCoords(java.nio.file.Path.of(cfg.getLmdDepotCsv()));
            double serviceEnd = 86400.0;       // matches LausitzDrtPreprocessor default
            double returnWindow = 5400.0;       // last 90 min target depots
            // Depot parking capacity = even fleet split (matches the spawn distribution): each depot
            // zone absorbs at most ceil(fleet/depots) returning vehicles, so the end-of-day return
            // fills the nearest depot first and overflows to the next once full.
            double perDepotCapacity = Math.ceil((double) cfg.getFleetSize() / Math.max(1, depots.size()));
            hagrid.integrated.drt.DrtConfigComposer.installModules(controler, depots,
                    serviceEnd - returnWindow, perDepotCapacity, 1800.0);
            if (carrierModules) {
                hagrid.integrated.freight.FreightRunComposer.installCarrierModules(controler, scenario);
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
                        new hagrid.integrated.shareduse.SharedUseModule(drtCfg, cfg.getChiThreshold()));
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
                org.matsim.freight.carriers.Carriers routed = hagrid.integrated.modular.ModularTourConverter
                        .read(cfg.getLmdCarriersRouted(),
                                hagrid.integrated.modular.ModularVehicleTypes
                                        .createCapsuleTypes(cfg.getLmdVehicleTypes()));
                org.matsim.api.core.v01.network.Network carNet =
                        hagrid.integrated.freight.LausitzFreightPreprocessor.carNetwork(
                                org.matsim.core.network.NetworkUtils.readNetwork(cfg.getLausitzNetworkRaw()));
                org.matsim.api.core.v01.network.Network drtNet =
                        org.matsim.core.network.NetworkUtils.createNetwork();
                new org.matsim.core.network.algorithms.TransportModeNetworkFilter(
                        org.matsim.core.network.NetworkUtils.readNetwork(cfg.getDrtNetworkClipped()))
                        .filter(drtNet, java.util.Set.of(org.matsim.api.core.v01.TransportMode.drt));
                java.util.List<hagrid.integrated.modular.ModularFreightTour> tours =
                        hagrid.integrated.modular.ModularTourConverter.convert(routed, carNet, drtNet);
                // Task 1 (paper-readiness review F1/F3/F5/F7, METHODS-LOG 2.16): plan-time
                // accounting computed ONCE right after convert(), same pattern ModularEndToEndTest
                // / ModularE2eStaging use, and handed into the module so ModularKpiHandler can
                // publish parcels_demand/parcels_unassigned_jsprit/parcels_missed_overlay/
                // max_parcels_per_tour/peak_concurrent_swaps without ever touching Carriers itself.
                hagrid.integrated.modular.ModularPlanStats planStats =
                        hagrid.integrated.modular.ModularTourConverter.planStats(routed, tours);
                controler.addOverridingModule(new hagrid.integrated.modular.ModularDispatchModule(
                        drtCfg, tours, cfg.getIdleThreshold(), planStats));
                LOG.info("MODULAR run '{}' (DRT fleet {}, {} freight tours, idleThreshold={}, cap={}s,"
                        + " unassigned={}).",
                        cfg.getRunId(), cfg.getFleetSize(), tours.size(), cfg.getIdleThreshold(),
                        cfg.getMaxTourDurationSeconds(), planStats.parcelsUnassignedJsprit());
            } else {
                LOG.info("DRT passenger-only run '{}' (fleet {}).", cfg.getRunId(), cfg.getFleetSize());
            }
            controler.run();
            logDuration("Simulation '" + cfg.getRunId() + "'", t0);
            writeRunMetadataSafely(cfg);
            KpiDashboardTrigger.triggerSafely(cfg);
            return;
        }

        // LMD baseline: dedicated conventional multi-LSP delivery on the Lausitz network.
        if (cfg.isLmdBaseline()) {
            // 1. preprocess: produce the routed carrier XML
            // Clip LMD demand to the SAME service-area shapefile the DRT uses, so both baselines
            // cover identical geography (no out-of-area outliers like Ruhland that DRT can't reach).
            hagrid.integrated.freight.LausitzFreightPreprocessor.run(
                    cfg.getLmdDemandShapefile(), cfg.getLmdDepotCsv(),
                    cfg.getLausitzNetworkRaw(), cfg.getLmdVehicleTypes(),
                    cfg.getLmdCarriersRouted(), cfg.getJspritIterations(),
                    cfg.getDrtServiceAreaShapefile());

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
            hagrid.integrated.freight.FreightRunComposer.addCarriers(
                    scenario, cfg.getLmdCarriersRouted(), cfg.getLmdVehicleTypes());

            Controler controler = new Controler(scenario);
            hagrid.integrated.freight.FreightRunComposer.installCarrierModules(controler, scenario);
            LOG.info("LMD baseline run '{}' on the Lausitz network.", cfg.getRunId());
            controler.run();
            logDuration("Simulation '" + cfg.getRunId() + "'", t0);
            writeRunMetadataSafely(cfg);
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
                scenario, true,
                cfg.getMaxIterations(), cfg.getJspritIterations(),
                cfg.isZoneBasedCachingEnabled(),
                cfg.getZoneBasedCachingThresholdMeters(),
                cfg.getUTurnPenaltyCost()));

        // Run
        LOG.info("Output: {}", cfg.getOutputDirectoryAsString());
        controler.run();

        logDuration("Simulation '" + cfg.getRunId() + "'", t0);
        writeRunMetadataSafely(cfg);
        KpiDashboardTrigger.triggerSafely(cfg);

        // GC hint between scenarios
        System.gc();
        try { Thread.sleep(5000); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Writes {@code run_metadata.json} for a completed run, never throwing: a bug in the
     * metadata writer must not kill a multi-hour MATSim run. Catches {@code Exception} (not just
     * {@code IOException}) so that any future unchecked failure inside {@code RunMetadataWriter}
     * is swallowed the same way.
     */
    private static void writeRunMetadataSafely(HAGRIDSimulationConfig cfg) {
        try {
            RunMetadataWriter.write(cfg, cfg.getOutputDirectory());
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
     */
    public static void generateDashboard(HAGRIDSimulationConfig cfg) throws Exception {
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
              java hagrid.HAGRIDSimulationRunner <scenario> [<scenario> ...]

            Each <scenario> is comma-separated key=value:
              concept        (required) scenario concept name
              date           (required) yyyy-MM-dd
              tag            version tag appended to run ID (optional, e.g. V1)
              maxIter        MATSim iterations (default 150)
              jspritIter     jsprit iterations (default 100)
              zoneCaching    true/false (default false)
              zoneThreshold  metres (default 1500 when zoneCaching=true)
              uTurnPenalty   score penalty per U-turn (default 1.0)
              studyArea      HANNOVER or LAUSITZ_HOYERSWERDA (default HANNOVER)
              fleetSize      DRT fleet size in vehicles (default 50; only used for DRT concepts)
              seed           MATSim global random seed (default 1337; vary for error-band replicates)
              writeDashboard true/false (default false) \u2014 generate dashboard after sim

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
        Duration d = Duration.between(start, Instant.now());
        LOG.info("{} completed in {:02d}:{:02d}:{:02d}",
                label, d.toHours(), d.toMinutesPart(), d.toSecondsPart());
    }
}
