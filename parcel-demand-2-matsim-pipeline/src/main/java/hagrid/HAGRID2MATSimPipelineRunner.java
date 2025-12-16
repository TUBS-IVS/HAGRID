package hagrid;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.appender.FileAppender;
import org.apache.logging.log4j.core.layout.PatternLayout;
import com.google.inject.Guice;
import com.google.inject.Injector;

import hagrid.demand.CarrierGenerator;
import hagrid.demand.CarrierRouter;
import hagrid.demand.CarrierServiceMerger;
import hagrid.demand.DeliveryGenerator;
import hagrid.demand.DemandProcessor;
import hagrid.demand.LogisticsDataProcessor;
import hagrid.demand.NetworkProcessor;
import hagrid.demand.SupplyCarrierGenerator;
import hagrid.utils.routing.ThreadingType;

public class HAGRID2MATSimPipelineRunner {

    private static final Logger LOGGER = LogManager.getLogger(HAGRID2MATSimPipelineRunner.class);
    private static final DateTimeFormatter RUN_ID_FORMAT = DateTimeFormatter.ofPattern("ddMMyyyy");
    private static final DateTimeFormatter RUN_TS_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    public static void main(String[] args) {
        LOGGER.info("Starting HAGRID Demand Pipeline for multiple concepts and dates...");

        PipelineConfig config = PipelineConfig.builder()
                // ----- customize your scenario selection here -----
                .concepts(List.of(
                        // "batchHigh",
                        // "batchMedium",
                        // "batchModerate",
                        "basecase"))
                .dates(List.of(
                        // LocalDate.of(2025, 5, 12),
                        LocalDate.of(2025, 5, 13)
                        // LocalDate.of(2025, 5, 14),
                        // LocalDate.of(2025, 5, 15),
                        // LocalDate.of(2025, 5, 16),
                        // LocalDate.of(2025, 5, 17)
                ))
                .applyServiceSimplifier(false)
        // Vehicle schedule options: FULL_WINDOW (default), SIMPLE_STAGGERED (8/11/14), EARLY_ONLY (start-1)
        .vehicleSchedule(PipelineConfig.VehicleScheduleOption.FULL_WINDOW)
        .vehicleSchedule("amazon", PipelineConfig.VehicleScheduleOption.EARLY_ONLY)
        .cepVehicleSizes(List.of("m", "l"))
                .providerVehicleSizes("amazon", List.of("l"))
                .deliveryWindow("default", 7, 14)
                .deliveryWindow("amazon", 9, 17)
        .filterRegions("Hannover")
        // ----- customize section ends -----
        .build();

        runScenarios(config);

        LOGGER.info("All demand pipeline scenarios (concept and date) completed.");
    }

    private static void runScenarios(PipelineConfig config) {
        for (String concept : config.concepts()) {
            LOGGER.info("--- Concept batch start: {} ---", concept);
            for (LocalDate date : config.dates()) {
                runScenario(concept, date, config);
            }
            LOGGER.info("--- Concept batch finished: {} ---", concept);
        }
    }

    private static void runScenario(String concept, LocalDate date, PipelineConfig config) {
        LOGGER.info("--------------------------------------------------");
        String runId = createRunId(concept, date);
        LOGGER.info("Processing scenario: {}", runId);
        LOGGER.info("--------------------------------------------------");

        // Prepare per-run logging into output/logs/<runId>_<timestamp>/runner.log
        LocalDateTime startedAt = LocalDateTime.now();
        String ts = startedAt.format(RUN_TS_FORMAT);
        Path runLogDir = config.pipelineRoot().resolve("output").resolve("logs").resolve(runId + "_" + ts);
        try {
            Files.createDirectories(runLogDir);
        } catch (Exception e) {
            LOGGER.warn("Could not create run log directory {}: {}", runLogDir, e.getMessage());
        }
        String appenderName = null;
        try {
            appenderName = attachPerRunFileAppender(runLogDir.resolve("runner.log").toString());
        } catch (Exception e) {
            LOGGER.warn("Could not attach per-run file appender: {}", e.getMessage());
        }

        // Set system properties to ensure Router cache activation (can be overridden
        // per run)
        Path cacheBase = config.pipelineRoot().resolve("routerCache");
        Path runCacheDir = cacheBase.resolve(runId);
        try {
            Files.createDirectories(runCacheDir);
        } catch (Exception e) {
            LOGGER.warn("Could not create cache dir {}: {}", runCacheDir, e.getMessage());
        }
        System.setProperty("hagrid.router.cache.enabled", "true");
        System.setProperty("hagrid.router.cache.dir", runCacheDir.toString());
        System.setProperty("hagrid.runId", runId); // expose runId globally for Router cache naming
        LOGGER.info("(SysProp) Router cache forced ENABLED at {}", runCacheDir.toAbsolutePath());

        // Create the Guice injector
    Injector injector = Guice.createInjector(new HagridModule(config.configXmlPath().toString()));
    HagridConfigGroup hagridConfig = injector.getInstance(HagridConfigGroup.class);
    hagridConfig.setConcept(concept);
        hagridConfig.setSimulationDate(date);
        hagridConfig.setFilterRegionsAsString(config.filterRegions());

        PipelineConfig.VehicleProfiles vehicleProfiles = config.vehicleProfiles();
        Map<String, List<String>> vehicleSizeLog = new LinkedHashMap<>();
        Map<String, PipelineConfig.VehicleScheduleOption> scheduleLog = new LinkedHashMap<>();
        Map<String, List<Integer>> dispatchHoursLog = new LinkedHashMap<>();

        hagridConfig.clearProviderVehicleSizes();
        PipelineConfig.VehicleProfile defaultVehicleProfile = vehicleProfiles.defaults();
        hagridConfig.setCepVehicleSizes(defaultVehicleProfile.vehicleSizes());
        vehicleSizeLog.put("default", defaultVehicleProfile.vehicleSizes());
        scheduleLog.put("default", defaultVehicleProfile.scheduleOption());

        vehicleProfiles.perProvider().forEach((provider, profile) -> {
            hagridConfig.setProviderVehicleSizes(provider, profile.vehicleSizes());
            vehicleSizeLog.put(provider, profile.vehicleSizes());
            scheduleLog.put(provider, profile.scheduleOption());
        });
        LOGGER.info("Configured CEP vehicle sizes per provider: {}", vehicleSizeLog);

        Map<String, DeliveryWindow> providerDeliveryHours = config.deliveryWindows();
        if (!providerDeliveryHours.isEmpty()) {
            providerDeliveryHours.forEach((provider, window) -> {
                if (window == null) {
                    return;
                }
                if ("default".equalsIgnoreCase(provider)) {
                    hagridConfig.setDefaultDeliveryHours(window.startHour(), window.endHour());
                } else {
                    hagridConfig.setDeliveryHours(provider, window.startHour(), window.endHour());
                }
            });
            String hoursLog = providerDeliveryHours.entrySet().stream()
                    .map(entry -> entry.getKey() + "=" + entry.getValue())
                    .collect(Collectors.joining(", "));
            LOGGER.info("Configured delivery hours per provider: {}", hoursLog);
        }

        hagridConfig.clearProviderDispatchHours();
        Set<String> scheduleProviders = new LinkedHashSet<>();
        scheduleProviders.add("default");
        scheduleProviders.addAll(providerDeliveryHours.keySet());
        scheduleProviders.addAll(vehicleProfiles.perProvider().keySet());

        for (String providerKey : scheduleProviders) {
            PipelineConfig.VehicleProfile profile = "default".equals(providerKey)
                    ? defaultVehicleProfile
                    : vehicleProfiles.perProvider().getOrDefault(providerKey, defaultVehicleProfile);

            scheduleLog.put(providerKey, profile.scheduleOption());

            int startHour = hagridConfig.getDeliveryStartTime(providerKey);
            int endHour = hagridConfig.getDeliveryEndTime(providerKey);
            List<Integer> dispatchHours = profile.scheduleOption().computeDispatchHours(startHour, endHour);
            dispatchHoursLog.put(providerKey, dispatchHours);
            if (!dispatchHours.isEmpty()) {
                hagridConfig.setProviderDispatchHours(providerKey, dispatchHours);
            }
        }
        LOGGER.info("Configured vehicle schedules per provider: {}", scheduleLog);
        LOGGER.info("Computed dispatch hours per provider: {}", dispatchHoursLog);
        // Retain reflective config (still write for consistency)
        invokeIfExists(hagridConfig, "setCarrierRoutingCacheEnabled", true);
        invokeIfExists(hagridConfig, "setCarrierRoutingCacheDir", runCacheDir.toString());
        invokeIfExists(hagridConfig, "setCarrierCacheEnabled", true);
        invokeIfExists(hagridConfig, "setCarrierCacheDir", runCacheDir.toString());
        invokeIfExists(hagridConfig, "setCacheDir", runCacheDir.toString());
        LOGGER.info("Configured carrier routing cache directory: {} (exists={})", runCacheDir,
                Files.exists(runCacheDir));

        // Configure routing cache (baseDir/routerCache/<runId>) via reflection setters
        // if available
        /*
         * Path cacheBase = Paths.get("parcel-demand-2-matsim-pipeline", "routerCache");
         * Path runCacheDir = cacheBase.resolve(runId);
         * try { Files.createDirectories(runCacheDir); } catch (Exception e) {
         * LOGGER.warn("Could not create cache dir {}: {}", runCacheDir,
         * e.getMessage()); }
         * invokeIfExists(config, "setCarrierRoutingCacheEnabled", true);
         * invokeIfExists(config, "setCarrierCacheEnabled", true); // fallback
         * alternative name
         * invokeIfExists(config, "setLoadCarrierCache", true); // legacy flag
         * invokeIfExists(config, "setCarrierRoutingCacheDir", runCacheDir.toString());
         * invokeIfExists(config, "setCarrierCacheDir", runCacheDir.toString());
         * invokeIfExists(config, "setCacheDir", runCacheDir.toString());
         * LOGGER.info("Configured carrier routing cache directory: {} (exists={})",
         * runCacheDir, Files.exists(runCacheDir));
         */

        // Execute processing steps in a structured manner
        runNetworkProcessing(injector); // Step 1: Process the network data
        runLogisticsDataProcessing(injector); // Step 2: Process the logistics data
        runDemandProcessing(injector); // Step 3: Process the freight demand data
        runDeliveryGeneration(injector); // Step 4: Generate parcels based on the processed demand data
        runCarrierGeneration(injector); // Step 5: Generate carriers based on the processed demand data
        runSupplyGeneration(injector); // Step 6: Generate supply carriers based on the generated carriers

        if (config.applyServiceSimplifier()) {
            LOGGER.info("Applying service simplifier...");
            runCarrierServiceMerger(injector, true); // Step 7: Merge carrier services to reduce the number of services
        }
        runRouter(injector, ThreadingType.COMPLETABLE_FUTURE); // Step 8: Run routing for delivery supply carriers based
                                                               // on the generated

    writeScenarioSummary(config.pipelineRoot(), runId, concept, date, startedAt, config,
        providerDeliveryHours, vehicleSizeLog, scheduleLog, dispatchHoursLog);

        System.gc();
        injector = null;

        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        } // gibt GC etwas Luft
        LOGGER.info("Finished scenario: {}", runId);

        // Detach per-run file appender at the very end
        if (appenderName != null) {
            try {
                detachPerRunFileAppender(appenderName);
            } catch (Exception e) {
                LOGGER.warn("Could not detach per-run file appender {}: {}", appenderName, e.getMessage());
            }
        }
    }

    private static String createRunId(String concept, LocalDate date) {
        return concept + "_" + date.format(RUN_ID_FORMAT);
    }

    /**
     * Runs the network processing step, initializing and executing the
     * NetworkProcessor.
     * This step processes the network data required for further analysis.
     * 
     * @param injector the Guice injector used for dependency injection.
     */
    private static void runNetworkProcessing(Injector injector) {
        LOGGER.info("Initializing NetworkProcessor...");
        NetworkProcessor networkProcessor = injector.getInstance(NetworkProcessor.class);

        LOGGER.info("Starting network processing...");
        networkProcessor.run();
        LOGGER.info("Network processing completed. Subnetworks created.");
    }

    /**
     * Runs the logistics data processing step, initializing and executing the
     * LogisticsDataProcessor.
     * This step processes logistics-related data such as hubs and shipping points.
     * 
     * @param injector the Guice injector used for dependency injection.
     */
    private static void runLogisticsDataProcessing(Injector injector) {
        LOGGER.info("Initializing LogisticsDataProcessor...");
        LogisticsDataProcessor logisticsDataProcessor = injector.getInstance(LogisticsDataProcessor.class);

        LOGGER.info("Starting logistics data processing...");
        logisticsDataProcessor.run();
        LOGGER.info("Logistics data processing completed.");
    }

    /**
     * Runs the demand processing step, initializing and executing the
     * DemandProcessor.
     * This step processes the freight demand data to split and sort the parcel
     * input data.
     * 
     * @param injector the Guice injector used for dependency injection.
     */
    private static void runDemandProcessing(Injector injector) {
        LOGGER.info("Initializing DemandProcessor...");
        DemandProcessor demandProcessor = injector.getInstance(DemandProcessor.class);

        LOGGER.info("Starting demand data processing...");
        demandProcessor.run();
        LOGGER.info("Demand data processing completed.");
    }

    /**
     * Runs the parcel generation step based on sorted demand, initializing and
     * executing the ParcelGenerator.
     * This step converts the processed demand data into parcel objects for further
     * routing and delivery simulation in MATSim.
     * 
     * @param injector the Guice injector used for dependency injection.
     */
    private static void runDeliveryGeneration(Injector injector) {
        LOGGER.info("Initializing DeliveryGenerator...");
        DeliveryGenerator deliveryGenerator = injector.getInstance(DeliveryGenerator.class);

        LOGGER.info("Starting delivery generation based on sorted Demand...");
        deliveryGenerator.run();
        LOGGER.info("Delivery and parcel generation completed.");
    }

    /**
     * Runs the carrier generation step based on sorted demand, initializing and
     * executing the CarrierGenerator.
     * This step converts the processed demand data into carrier objects for further
     * routing and delivery simulation in MATSim.
     * 
     * @param injector the Guice injector used for dependency injection.
     */
    private static void runCarrierGeneration(Injector injector) {
        LOGGER.info("Initializing CarrierGenerator...");
        CarrierGenerator carrierGenerator = injector.getInstance(CarrierGenerator.class);

        LOGGER.info("Starting carrier generation based on sorted demand...");
        carrierGenerator.run();
        LOGGER.info("Carrier generation completed.");
    }

    /**
     * Runs the supply carrier generation step based on the generated carriers,
     * initializing and executing the CarrierGenerator.
     * This step creates supply carriers responsible for delivering parcels to hubs
     * based on the previously generated carriers and their services.
     * 
     * @param injector the Guice injector used for dependency injection.
     */
    private static void runSupplyGeneration(Injector injector) {
        LOGGER.info("Initializing SupplyCarrierGenerator...");
        SupplyCarrierGenerator supplyCarrierGenerator = injector.getInstance(SupplyCarrierGenerator.class);

        LOGGER.info("Starting supply carrier generation based on generated carriers...");
        supplyCarrierGenerator.run();
        LOGGER.info("Supply carrier generation completed.");
    }

    /**
     * Executes the carrier service merging step to consolidate B2B/B2C services
     * per link and provider (e.g. DHL, Hermes, etc.). This reduces the number of
     * services for routing optimization while preserving original capacity, type,
     * and attribute information via embedded JSON metadata.
     *
     * The method uses dependency injection (via Guice) to initialize the
     * {@link CarrierServiceMerger} and runs it within the current MATSim scenario.
     *
     * @param injector Guice injector providing the necessary dependencies
     * @param string
     */
    private static void runCarrierServiceMerger(Injector injector, Boolean fullMerge) {
        LOGGER.info("Initializing CarrierServiceMerger for parcel service consolidation...");

        CarrierServiceMerger carrierServiceMerger = injector.getInstance(CarrierServiceMerger.class);

        LOGGER.info("Starting carrier service merge based on previously generated delivery carriers...");
        if (fullMerge) {
            carrierServiceMerger.setFullMerge(true);
        } else {
            carrierServiceMerger.setFullMerge(false);
        }
        carrierServiceMerger.run();
        LOGGER.info("Carrier service merge completed successfully.");
    }

    /**
     * Runs the routing process for both delivery and supply carriers,
     * initializing and executing the CarrierRouter.
     * This step performs the routing for the carriers based on the provided network
     * and costs,
     * utilizing the specified threading type for parallel processing.
     * 
     * @param injector      the Guice injector used for dependency injection.
     * @param threadingType the threading type to be used for parallel processing.
     */
    private static void runRouter(Injector injector, ThreadingType threadingType) {
        try {
            LOGGER.info("Initializing CarrierRouter with threading type: {}...", threadingType);
            CarrierRouter carrierRouter = injector.getInstance(CarrierRouter.class);
            if (carrierRouter == null) {
                LOGGER.error("CarrierRouter instance is null");
                return;
            }
            carrierRouter.setThreadingType(threadingType);

            LOGGER.info("Starting routing process for delivery and supply carriers...");
            carrierRouter.run();
            LOGGER.info("Routing process for delivery and supply carriers completed.");
        } catch (Exception e) {
            LOGGER.error("Error initializing or running CarrierRouter", e);
        }

    }

    /**
     * Attaches a temporary FileAppender to the root logger that writes to the given
     * file.
     * Returns the appender name so it can be removed later.
     */
    private static String attachPerRunFileAppender(String filePath) {
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        Configuration config = ctx.getConfiguration();

        String name = "PerRunFileAppender_" + System.nanoTime();
        PatternLayout layout = PatternLayout.newBuilder()
                .withConfiguration(config)
                .withPattern("%d{yyyy-MM-dd HH:mm:ss.SSS} [%t] %-5level %logger{36} - %msg%n")
                .build();

        FileAppender appender = FileAppender.newBuilder()
                .setName(name)
                .withFileName(filePath)
                .withAppend(true)
                .withLocking(false)
                .setLayout(layout)
                .setConfiguration(config)
                .build();
        appender.start();

        config.addAppender(appender);
        LoggerConfig root = config.getRootLogger();
        root.addAppender(appender, Level.INFO, null);
        ctx.updateLoggers();
        LOGGER.info("Attached per-run file logger to {}", filePath);
        return name;
    }

    /**
     * Detaches and stops a previously attached per-run FileAppender.
     */
    private static void detachPerRunFileAppender(String appenderName) {
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        Configuration config = ctx.getConfiguration();
        LoggerConfig root = config.getRootLogger();
        root.removeAppender(appenderName);
        if (config.getAppenders().containsKey(appenderName)) {
            config.getAppenders().get(appenderName).stop();
            config.getAppenders().remove(appenderName);
        }
        ctx.updateLoggers();
    }

    // Reflection helper for optional config setters
    private static void invokeIfExists(Object target, String method, Object value) {
        try {
            Class<?> clazz = target.getClass();
            for (var m : clazz.getMethods()) {
                if (m.getName().equals(method) && m.getParameterCount() == 1) {
                    Class<?> pt = m.getParameterTypes()[0];
                    if (value instanceof Boolean b && (pt == boolean.class || pt == Boolean.class)) {
                        m.invoke(target, b);
                        return;
                    }
                    if (value instanceof String s && pt == String.class) {
                        m.invoke(target, s);
                        return;
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static void writeScenarioSummary(Path pipelineRoot,
            String runId,
            String concept,
            LocalDate date,
            LocalDateTime startedAt,
            PipelineConfig config,
            Map<String, DeliveryWindow> deliveryWindows,
            Map<String, List<String>> vehicleSizeLog,
            Map<String, PipelineConfig.VehicleScheduleOption> scheduleLog,
            Map<String, List<Integer>> dispatchHoursLog) {

        Path runOutputDir = pipelineRoot.resolve("output").resolve(runId);
        try {
            Files.createDirectories(runOutputDir);
            Path summaryFile = runOutputDir.resolve(runId + "_configuration_summary.txt");

            String newline = System.lineSeparator();
            StringBuilder sb = new StringBuilder();
            sb.append("HAGRID demand pipeline run summary").append(newline);
            sb.append("=================================").append(newline);
            sb.append("Run ID: ").append(runId).append(newline);
            sb.append("Concept: ").append(concept).append(newline);
            sb.append("Simulation date: ").append(date).append(newline);
            sb.append("Started at: ").append(startedAt).append(newline);
            sb.append("Filter regions: ").append(config.filterRegions()).append(newline);
            sb.append("Pipeline root: ").append(config.pipelineRoot()).append(newline);
            sb.append("Config XML: ").append(config.configXmlPath()).append(newline);
            sb.append("Apply service simplifier: ").append(config.applyServiceSimplifier()).append(newline);
            sb.append("Threading type: ").append(ThreadingType.COMPLETABLE_FUTURE).append(newline);
            sb.append(newline);

            sb.append("Delivery windows:").append(newline);
            deliveryWindows.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> sb.append("  - ").append(entry.getKey())
                            .append(": ")
                            .append(String.format("%02d", entry.getValue().startHour()))
                            .append(" - ")
                            .append(String.format("%02d", entry.getValue().endHour()))
                            .append(newline));
            sb.append(newline);

            sb.append("Vehicle profiles:").append(newline);
            Set<String> providerOrder = new LinkedHashSet<>();
            providerOrder.add("default");
            providerOrder.addAll(deliveryWindows.keySet());
            providerOrder.addAll(vehicleSizeLog.keySet());
            providerOrder.addAll(scheduleLog.keySet());
            providerOrder.addAll(dispatchHoursLog.keySet());

            providerOrder.forEach(provider -> {
                sb.append("  - ").append(provider).append(":").append(newline);
                List<String> sizes = vehicleSizeLog.get(provider);
                if (sizes != null) {
                    sb.append("      sizes: ").append(sizes).append(newline);
                }
                PipelineConfig.VehicleScheduleOption schedule = scheduleLog.get(provider);
                if (schedule != null) {
                    sb.append("      schedule: ").append(schedule.describe()).append(newline);
                }
                List<Integer> hours = dispatchHoursLog.get(provider);
                if (hours != null && !hours.isEmpty()) {
                    sb.append("      dispatchHours: ").append(hours).append(newline);
                }
            });

            Files.writeString(summaryFile, sb.toString(), StandardCharsets.UTF_8);
            LOGGER.info("Wrote run configuration summary to {}", summaryFile.toAbsolutePath());
        } catch (Exception e) {
            LOGGER.warn("Could not write run configuration summary for {}: {}", runId, e.getMessage());
        }
    }

    private static final class PipelineConfig {
        private final List<String> concepts;
        private final List<LocalDate> dates;
        private final boolean applyServiceSimplifier;
        private final Map<String, DeliveryWindow> deliveryWindows;
        private final Path pipelineRoot;
        private final Path configXmlPath;
        private final String filterRegions;
        private final VehicleProfiles vehicleProfiles;

        private PipelineConfig(Builder builder, VehicleProfiles vehicleProfiles) {
            this.concepts = Collections.unmodifiableList(new ArrayList<>(builder.concepts));
            this.dates = Collections.unmodifiableList(new ArrayList<>(builder.dates));
            this.applyServiceSimplifier = builder.applyServiceSimplifier;

            Map<String, DeliveryWindow> windowsCopy = new LinkedHashMap<>();
            builder.deliveryWindows.forEach((provider, window) -> windowsCopy.put(provider, window));
            this.deliveryWindows = Collections.unmodifiableMap(windowsCopy);

            this.pipelineRoot = builder.pipelineRoot;
            Path resolvedConfig = builder.configXmlPath;
            if (resolvedConfig == null) {
                resolvedConfig = builder.pipelineRoot.resolve("input").resolve("config.xml");
            }
            this.configXmlPath = resolvedConfig;
            this.filterRegions = builder.filterRegions;
            this.vehicleProfiles = vehicleProfiles;
        }

        static Builder builder() {
            return new Builder();
        }

        List<String> concepts() {
            return concepts;
        }

        List<LocalDate> dates() {
            return dates;
        }

        boolean applyServiceSimplifier() {
            return applyServiceSimplifier;
        }

        Map<String, DeliveryWindow> deliveryWindows() {
            return deliveryWindows;
        }

        Path pipelineRoot() {
            return pipelineRoot;
        }

        Path configXmlPath() {
            return configXmlPath;
        }

        String filterRegions() {
            return filterRegions;
        }

        VehicleProfiles vehicleProfiles() {
            return vehicleProfiles;
        }

        private static final class Builder {
            private static final List<String> SUPPORTED_CEP_VEHICLE_SIZES = List.of("m", "l");

            private final List<String> concepts = new ArrayList<>();
            private final List<LocalDate> dates = new ArrayList<>();
            private boolean applyServiceSimplifier = false;
            private final List<String> cepVehicleSizes = new ArrayList<>();
            private final Map<String, DeliveryWindow> deliveryWindows = new LinkedHashMap<>();
            private final Map<String, List<String>> providerVehicleSizes = new LinkedHashMap<>();
            private final Map<String, VehicleScheduleOption> providerVehicleSchedules = new LinkedHashMap<>();
            private VehicleScheduleOption defaultVehicleSchedule = VehicleScheduleOption.FULL_WINDOW;
            private Path pipelineRoot = Paths.get("parcel-demand-2-matsim-pipeline");
            private Path configXmlPath;
            private String filterRegions = "Hannover";

            private Builder() {
                concepts.add("basecase");
                dates.add(LocalDate.of(2025, 5, 13));
                cepVehicleSizes.addAll(SUPPORTED_CEP_VEHICLE_SIZES);
                deliveryWindow("default", 7, 14);
                deliveryWindow("amazon", 9, 17);
            }

            Builder concepts(List<String> concepts) {
                Objects.requireNonNull(concepts, "concepts");
                if (concepts.isEmpty()) {
                    throw new IllegalArgumentException("At least one concept must be provided.");
                }
                this.concepts.clear();
                concepts.stream()
                        .map(s -> Objects.requireNonNull(s, "concept").trim())
                        .filter(s -> !s.isEmpty())
                        .forEach(this.concepts::add);
                if (this.concepts.isEmpty()) {
                    throw new IllegalArgumentException("Concept list must not be empty after trimming.");
                }
                return this;
            }

            Builder dates(List<LocalDate> dates) {
                Objects.requireNonNull(dates, "dates");
                if (dates.isEmpty()) {
                    throw new IllegalArgumentException("At least one date must be provided.");
                }
                this.dates.clear();
                dates.forEach(date -> this.dates.add(Objects.requireNonNull(date, "date")));
                return this;
            }

            Builder applyServiceSimplifier(boolean apply) {
                this.applyServiceSimplifier = apply;
                return this;
            }

            Builder cepVehicleSizes(List<String> sizes) {
                Objects.requireNonNull(sizes, "cepVehicleSizes");
                if (sizes.isEmpty()) {
                    throw new IllegalArgumentException("At least one CEP vehicle size must be provided.");
                }
                this.cepVehicleSizes.clear();
                sizes.stream()
                        .map(Builder::normalizeVehicleSize)
                        .forEach(this.cepVehicleSizes::add);
                return this;
            }

            Builder vehicleSchedule(VehicleScheduleOption option) {
                this.defaultVehicleSchedule = Objects.requireNonNull(option, "vehicleSchedule");
                return this;
            }

            Builder vehicleSchedule(String provider, VehicleScheduleOption option) {
                String key = normalizeProviderKey(provider);
                if ("default".equals(key)) {
                    return vehicleSchedule(option);
                }
                if (option == null) {
                    providerVehicleSchedules.remove(key);
                } else {
                    providerVehicleSchedules.put(key, option);
                }
                return this;
            }

            Builder providerVehicleSizes(String provider, List<String> sizes) {
                String key = normalizeProviderKey(provider);
                if ("default".equals(key)) {
                    return cepVehicleSizes(sizes);
                }
                Objects.requireNonNull(sizes, "vehicleSizes");
                if (sizes.isEmpty()) {
                    providerVehicleSizes.remove(key);
                    return this;
                }
                List<String> normalized = new ArrayList<>();
                sizes.forEach(size -> normalized.add(normalizeVehicleSize(size)));
                providerVehicleSizes.put(key, normalized);
                return this;
            }

            Builder deliveryWindow(String provider, int startHour, int endHour) {
                String normalizedKey = normalizeProviderKey(provider);
                deliveryWindows.put(normalizedKey, new DeliveryWindow(startHour, endHour));
                return this;
            }

            @SuppressWarnings("unused")
            Builder deliveryWindows(Map<String, DeliveryWindow> windows) {
                Objects.requireNonNull(windows, "deliveryWindows");
                windows.forEach((provider, window) -> deliveryWindow(provider, window.startHour(), window.endHour()));
                return this;
            }

            @SuppressWarnings("unused")
            Builder pipelineRoot(Path root) {
                this.pipelineRoot = Objects.requireNonNull(root, "pipelineRoot");
                return this;
            }

            @SuppressWarnings("unused")
            Builder configXmlPath(Path configXmlPath) {
                this.configXmlPath = Objects.requireNonNull(configXmlPath, "configXmlPath");
                return this;
            }

            Builder filterRegions(String filterRegions) {
                String value = Objects.requireNonNull(filterRegions, "filterRegions").trim();
                if (value.isEmpty()) {
                    throw new IllegalArgumentException("filterRegions must not be empty.");
                }
                this.filterRegions = value;
                return this;
            }

            PipelineConfig build() {
                if (concepts.isEmpty()) {
                    throw new IllegalStateException("No concepts configured.");
                }
                if (dates.isEmpty()) {
                    throw new IllegalStateException("No dates configured.");
                }
                if (cepVehicleSizes.isEmpty()) {
                    throw new IllegalStateException("No CEP vehicle sizes configured.");
                }
                if (!deliveryWindows.containsKey("default")) {
                    deliveryWindow("default", 7, 14);
                }
                return new PipelineConfig(this, buildVehicleProfiles());
            }

            private VehicleProfiles buildVehicleProfiles() {
                VehicleProfile defaults = new VehicleProfile(cepVehicleSizes, defaultVehicleSchedule);
                Map<String, VehicleProfile> perProvider = new LinkedHashMap<>();
                Set<String> providerKeys = new LinkedHashSet<>();
                providerKeys.addAll(providerVehicleSizes.keySet());
                providerKeys.addAll(providerVehicleSchedules.keySet());

                for (String provider : providerKeys) {
                    List<String> sizes = providerVehicleSizes.getOrDefault(provider, cepVehicleSizes);
                    VehicleScheduleOption schedule = providerVehicleSchedules.getOrDefault(provider,
                            defaultVehicleSchedule);
                    perProvider.put(provider, new VehicleProfile(sizes, schedule));
                }

                    return new VehicleProfiles(defaults, perProvider);
            }

            private static String normalizeVehicleSize(String size) {
                String normalized = Objects.requireNonNull(size, "vehicle size").trim().toLowerCase(Locale.ROOT);
                if (normalized.isEmpty()) {
                    throw new IllegalArgumentException("Vehicle size must not be empty.");
                }
                if (!SUPPORTED_CEP_VEHICLE_SIZES.contains(normalized)) {
                    throw new IllegalArgumentException("Unsupported vehicle size alias: " + size
                            + ". Supported values: " + SUPPORTED_CEP_VEHICLE_SIZES);
                }
                return normalized;
            }

            private static String normalizeProviderKey(String provider) {
                String key = Objects.requireNonNull(provider, "provider").trim().toLowerCase(Locale.ROOT);
                if (key.isEmpty()) {
                    throw new IllegalArgumentException("Provider identifier must not be empty.");
                }
                return key;
            }
        }

        private static final class VehicleProfiles {
            private final VehicleProfile defaults;
            private final Map<String, VehicleProfile> perProvider;

            private VehicleProfiles(VehicleProfile defaults, Map<String, VehicleProfile> perProvider) {
                this.defaults = defaults;
                this.perProvider = Collections.unmodifiableMap(new LinkedHashMap<>(perProvider));
            }

            VehicleProfile defaults() {
                return defaults;
            }

            Map<String, VehicleProfile> perProvider() {
                return perProvider;
            }
        }

        private static final class VehicleProfile {
            private final List<String> vehicleSizes;
            private final VehicleScheduleOption scheduleOption;

            private VehicleProfile(List<String> vehicleSizes, VehicleScheduleOption scheduleOption) {
                this.vehicleSizes = Collections.unmodifiableList(new ArrayList<>(vehicleSizes));
                this.scheduleOption = Objects.requireNonNull(scheduleOption, "scheduleOption");
            }

            List<String> vehicleSizes() {
                return vehicleSizes;
            }

            VehicleScheduleOption scheduleOption() {
                return scheduleOption;
            }
        }

        private enum VehicleScheduleOption {
            EARLY_ONLY {
                @Override
                List<Integer> computeDispatchHours(int startHour, int endHour) {
                    if (endHour < startHour) {
                        return List.of();
                    }
                    int early = Math.max(0, startHour - 1);
                    if (early > 23) {
                        return List.of();
                    }
                    return List.of(early);
                }

                @Override
                String describe() {
                    return "EARLY_ONLY (start hour minus one)";
                }
            },
            SIMPLE_STAGGERED {
                private final List<Integer> defaults = List.of(8, 11, 14);

                @Override
                List<Integer> computeDispatchHours(int startHour, int endHour) {
                    List<Integer> filtered = defaults.stream()
                            .filter(hour -> hour >= 0 && hour <= 23)
                            .filter(hour -> hour >= startHour && hour <= endHour)
                            .collect(Collectors.toList());
                    if (filtered.isEmpty()) {
                        int fallback = Math.max(0, Math.min(23, startHour));
                        return List.of(fallback);
                    }
                    return filtered;
                }

                @Override
                String describe() {
                    return "SIMPLE_STAGGERED (08/11/14 within window)";
                }
            },
            FULL_WINDOW {
                @Override
                List<Integer> computeDispatchHours(int startHour, int endHour) {
                    if (endHour < startHour) {
                        return List.of();
                    }
                    return IntStream.rangeClosed(Math.max(0, startHour), Math.min(23, endHour))
                            .boxed()
                            .collect(Collectors.toList());
                }

                @Override
                String describe() {
                    return "FULL_WINDOW (every hour within window)";
                }
            };

            abstract List<Integer> computeDispatchHours(int startHour, int endHour);

            abstract String describe();
        }
    }

    private static final class DeliveryWindow {
        private final int startHour;
        private final int endHour;

        private DeliveryWindow(int startHour, int endHour) {
            if (startHour < 0 || startHour > 23 || endHour < 0 || endHour > 23) {
                throw new IllegalArgumentException("Delivery hours must be within [0,23].");
            }
            if (endHour < startHour) {
                throw new IllegalArgumentException("End hour must be greater than or equal to start hour.");
            }
            this.startHour = startHour;
            this.endHour = endHour;
        }

        int startHour() {
            return startHour;
        }

        int endHour() {
            return endHour;
        }

        @Override
        public String toString() {
            return startHour + "-" + endHour;
        }
    }
}