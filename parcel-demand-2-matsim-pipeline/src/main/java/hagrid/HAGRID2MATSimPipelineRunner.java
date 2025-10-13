package hagrid;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

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

        // List of diffrent concepts to process
        List<String> concepts = List.of(
                // "batchHigh",
                // "batchMedium"
        // "batchModerate",
        "basecase"
        );

        // Servo flag for service merger
        boolean applyServiceSimplifier = false;

        // Datumsserie
        List<LocalDate> dates = List.of(
                // LocalDate.of(2025, 5, 12),
                LocalDate.of(2025, 5, 13));
                // LocalDate.of(2025, 5, 14),
                // LocalDate.of(2025, 5, 15),
                // LocalDate.of(2025, 5, 16),
                // LocalDate.of(2025, 5, 17));

        // Verschachtelte Iteration: jedes Konzept × jedes Datum
        for (String concept : concepts) {
            LOGGER.info("--- Concept batch start: {} ---", concept);
            for (LocalDate date : dates) {
                String runId = createRunId(concept, date);
                runPipeline(runId, date, applyServiceSimplifier);
            }
            LOGGER.info("--- Concept batch finished: {} ---", concept);
        }

        LOGGER.info("All demand pipeline scenarios (concept and date) completed.");
    }

    private static void runPipeline(String runId, LocalDate date, Boolean applyServiceSimplifier) {
        LOGGER.info("--------------------------------------------------");
        LOGGER.info("Processing scenario: {}", runId);
        LOGGER.info("--------------------------------------------------");

        // Prepare per-run logging into output/logs/<runId>_<timestamp>/runner.log
        LocalDateTime startedAt = LocalDateTime.now();
        String ts = startedAt.format(RUN_TS_FORMAT);
        Path runLogDir = Paths.get("parcel-demand-2-matsim-pipeline", "output", "logs", runId + "_" + ts);
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
        Path cacheBase = Paths.get("parcel-demand-2-matsim-pipeline", "routerCache");
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
        Injector injector = Guice.createInjector(new HagridModule("parcel-demand-2-matsim-pipeline/input/config.xml"));
        HagridConfigGroup config = injector.getInstance(HagridConfigGroup.class);
        config.setConcept(runId.split("_")[0]);
        config.setSimulationDate(date);
        config.setFilterRegionsAsString("Hannover");
        // Retain reflective config (still write for consistency)
        invokeIfExists(config, "setCarrierRoutingCacheEnabled", true);
        invokeIfExists(config, "setCarrierRoutingCacheDir", runCacheDir.toString());
        invokeIfExists(config, "setCarrierCacheEnabled", true);
        invokeIfExists(config, "setCarrierCacheDir", runCacheDir.toString());
        invokeIfExists(config, "setCacheDir", runCacheDir.toString());
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

        if (applyServiceSimplifier) {
            LOGGER.info("Applying service simplifier...");
            runCarrierServiceMerger(injector, true); // Step 7: Merge carrier services to reduce the number of services
        }
        runRouter(injector, ThreadingType.COMPLETABLE_FUTURE); // Step 8: Run routing for delivery supply carriers based
                                                               // on the generated
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
}