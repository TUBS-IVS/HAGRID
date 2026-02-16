package hagrid;

import hagrid.analysis.CarrierXmlParser;
import hagrid.analysis.CarrierXmlParser.ParsedCarrier;
import hagrid.analysis.DashboardGenerator;
import hagrid.analysis.FreightEventHandler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.io.MatsimNetworkReader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * Generates an interactive HTML analysis dashboard from a completed HAGRID
 * MATSim simulation run.
 * <p>
 * Reads the output events, carriers and network from the simulation output
 * directory and produces a single standalone HTML file with Leaflet.js maps,
 * Chart.js charts, and a modern glassmorphism dark-mode UI.
 * <p>
 * Usage:  {@code concept=basecase,date=2025-05-13,tag=V1,maxIter=150,jspritIter=1000}
 * <p>
 * The argument format mirrors {@link HAGRIDSimulationRunner} so that the same
 * scenario specification can be reused.
 */
public class HAGRIDAnalysisRunner {

    private static final Logger LOG = LogManager.getLogger(HAGRIDAnalysisRunner.class);

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final int DEFAULT_MAX_ITER = 150;
    private static final int DEFAULT_JSPRIT_ITER = 100;

    // ========================================================================
    // ENTRY POINT
    // ========================================================================

    public static void main(String[] args) throws Exception {
        LOG.info("═══════════════════════════════════════════════");
        LOG.info("  HAGRID Analysis Dashboard Generator");
        LOG.info("═══════════════════════════════════════════════");

        if (args.length == 0 || containsHelpFlag(args)) {
            printUsage();
            return;
        }

        for (String arg : args) {
            processScenario(arg.trim());
        }

        LOG.info("═══════════════════════════════════════════════");
        LOG.info("  All dashboards generated. Done.");
        LOG.info("═══════════════════════════════════════════════");
    }

    // ========================================================================
    // SCENARIO PROCESSING
    // ========================================================================

    private static void processScenario(String spec) throws Exception {
        Instant start = Instant.now();

        // ── 1. Parse arguments ──
        Map<String, String> map = parseKvPairs(spec);
        String concept = require(map, "concept");
        String dateStr = require(map, "date");
        LocalDate date;
        try {
            date = LocalDate.parse(dateStr, DATE_FMT);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("Invalid date (use yyyy-MM-dd): " + dateStr, ex);
        }
        int maxIter = intOrDefault(map, "maxIter", DEFAULT_MAX_ITER);
        int jspritIter = intOrDefault(map, "jspritIter", DEFAULT_JSPRIT_ITER);
        String tag = map.getOrDefault("tag", "").trim();

        // ── 2. Resolve paths ──
        HagridPaths paths = new HagridPaths();
        String baseRunId = concept.toUpperCase() + "_" + date.format(DateTimeFormatter.ofPattern("ddMMyyyy"));
        String runId = tag.isEmpty() ? baseRunId : baseRunId + "_" + tag;
        paths.initializeRun(runId);

        Path matsimDir = paths.matsimRunDir(maxIter, jspritIter);
        LOG.info("MATSim output directory: {}", matsimDir.toAbsolutePath());

        if (!Files.isDirectory(matsimDir)) {
            throw new IllegalStateException("MATSim output directory does not exist: " + matsimDir);
        }

        String filePrefix = runId;
        Path eventsFile = matsimDir.resolve(filePrefix + ".output_events.xml.gz");
        Path carriersFile = matsimDir.resolve(filePrefix + ".output_carriers.xml.gz");
        Path networkFile = matsimDir.resolve(filePrefix + ".output_network.xml.gz");

        // validate files
        List<String> missing = new ArrayList<>();
        if (!Files.exists(eventsFile)) missing.add("events: " + eventsFile);
        if (!Files.exists(carriersFile)) missing.add("carriers: " + carriersFile);
        if (!Files.exists(networkFile)) missing.add("network: " + networkFile);
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Missing output files:\n  " + String.join("\n  ", missing));
        }

        LOG.info("Events file  : {} ({} MB)", eventsFile.getFileName(),
                String.format("%.1f", Files.size(eventsFile) / 1_000_000.0));
        LOG.info("Carriers file: {} ({} MB)", carriersFile.getFileName(),
                String.format("%.1f", Files.size(carriersFile) / 1_000_000.0));
        LOG.info("Network file : {} ({} MB)", networkFile.getFileName(),
                String.format("%.1f", Files.size(networkFile) / 1_000_000.0));

        // ── 3. Load network ──
        LOG.info("─── Step 1/4: Loading network ───");
        Network network = NetworkUtils.createNetwork();
        new MatsimNetworkReader(network).readFile(networkFile.toAbsolutePath().toString());
        LOG.info("Network loaded: {} nodes, {} links",
                network.getNodes().size(), network.getLinks().size());

        // ── 4. Process events ──
        LOG.info("─── Step 2/4: Processing events ───");
        FreightEventHandler handler = new FreightEventHandler();
        EventsManager eventsManager = EventsUtils.createEventsManager();
        eventsManager.addHandler(handler);
        new MatsimEventsReader(eventsManager).readFile(eventsFile.toAbsolutePath().toString());
        LOG.info("Events processed: {} total, {} vehicles with tours, {} links tracked",
                handler.getTotalEventsProcessed(),
                handler.getVehicleTours().size(),
                handler.getLinkCountMap().size());

        // ── 5. Parse carriers ──
        LOG.info("─── Step 3/4: Parsing carriers ───");
        List<ParsedCarrier> carriers = CarrierXmlParser.parse(carriersFile);
        long deliveryCount = carriers.stream().filter(ParsedCarrier::isDelivery).count();
        long supplyCount   = carriers.stream().filter(ParsedCarrier::isSupply).count();
        int totalParcels = carriers.stream().filter(ParsedCarrier::isDelivery)
                .mapToInt(ParsedCarrier::numberOfParcels).sum();
        int totalMissed = carriers.stream().filter(ParsedCarrier::isDelivery)
                .mapToInt(ParsedCarrier::numMissed).sum();
        int parcelBase = totalParcels > 0 ? totalParcels : carriers.stream()
                .filter(ParsedCarrier::isDelivery).mapToInt(ParsedCarrier::numServices).sum();
        double successRate = parcelBase > 0 ? 100.0 * (parcelBase - totalMissed) / parcelBase : 100.0;
        LOG.info("Carriers parsed: {} total ({} delivery, {} supply), {} parcels, {} missed ({}% success)",
                carriers.size(), deliveryCount, supplyCount, parcelBase, totalMissed,
                String.format("%.1f", successRate));

        // ── 5.5 Parse vehicle types ──
        Path vehicleTypesFile = matsimDir.resolve(filePrefix + ".output_carriersVehicleTypes.xml.gz");
        Map<String, Double> vehicleTypeCapacities;
        Map<String, Double> vehicleTypeFixedCosts;
        Map<String, Double> vehicleTypeCostsPerKm;
        if (Files.exists(vehicleTypesFile)) {
            vehicleTypeCapacities = CarrierXmlParser.parseVehicleTypes(vehicleTypesFile);
            vehicleTypeFixedCosts = CarrierXmlParser.parseVehicleTypeFixedCosts(vehicleTypesFile);
            vehicleTypeCostsPerKm = CarrierXmlParser.parseVehicleTypeCostsPerKm(vehicleTypesFile);
        } else {
            LOG.warn("Vehicle types file not found: {}, using heuristic capacities", vehicleTypesFile);
            vehicleTypeCapacities = Map.of();
            vehicleTypeFixedCosts = Map.of();
            vehicleTypeCostsPerKm = Map.of();
        }

        // ── 6. Generate dashboard ──
        LOG.info("─── Step 4/4: Generating dashboard ───");
        String dashRunId = runId + "_iter" + maxIter + "_jsprit" + jspritIter;
        Path dashboardDir = matsimDir.resolve("analysis");
        DashboardGenerator generator = new DashboardGenerator(
                dashRunId, network, handler, carriers, vehicleTypeCapacities, vehicleTypeFixedCosts, vehicleTypeCostsPerKm, dashboardDir)
                .setLowUtilThreshold(0.05);
        Path htmlFile = generator.generate();

        Duration elapsed = Duration.between(start, Instant.now());
        LOG.info("────────────────────────────────────────────");
        LOG.info("  Dashboard ready: {}", htmlFile.toAbsolutePath());
        LOG.info("  Time elapsed:    {}m {}s",
                elapsed.toMinutesPart(), elapsed.toSecondsPart());
        LOG.info("────────────────────────────────────────────");
    }

    // ========================================================================
    // ARGUMENT PARSING
    // ========================================================================

    private static Map<String, String> parseKvPairs(String spec) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String token : spec.split(",")) {
            String[] kv = token.split("=", 2);
            if (kv.length != 2 || kv[0].isBlank() || kv[1].isBlank()) {
                throw new IllegalArgumentException("Bad token: " + token);
            }
            map.put(kv[0].trim(), kv[1].trim());
        }
        return map;
    }

    private static String require(Map<String, String> map, String key) {
        String v = map.get(key);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("Missing required key: " + key);
        }
        return v;
    }

    private static int intOrDefault(Map<String, String> map, String key, int def) {
        String v = map.get(key);
        if (v == null) return def;
        try {
            int val = Integer.parseInt(v);
            if (val <= 0) throw new IllegalArgumentException(key + " must be positive");
            return val;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid integer for " + key + ": " + v);
        }
    }

    private static boolean containsHelpFlag(String[] args) {
        for (String a : args) {
            if ("-h".equals(a) || "--help".equals(a) || "-help".equals(a)) return true;
        }
        return false;
    }

    private static void printUsage() {
        System.out.println("""
            
             HAGRID Analysis Dashboard Generator
             ────────────────────────────────────
            
             Generates an interactive HTML dashboard from MATSim simulation output.
            
             Usage:
               java hagrid.HAGRIDAnalysisRunner <scenario_spec> [<scenario_spec> ...]
            
             Scenario specification (comma-separated key=value):
               concept=<name>       Required. Scenario concept (e.g. basecase)
               date=<yyyy-MM-dd>    Required. Scenario date
               tag=<name>           Optional. Version tag (e.g. V1)
               maxIter=<N>          Optional. MATSim iterations (default 150)
               jspritIter=<N>       Optional. jsprit iterations (default 100)
            
             Example:
               concept=basecase,date=2025-05-13,tag=V1,maxIter=150,jspritIter=1000
            
             The dashboard is written to:
               hagrid-matsim-output/{runId}_iter{N}_jsprit{M}/analysis/
            """);
    }
}
