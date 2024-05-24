package hagrid;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.google.inject.Guice;
import com.google.inject.Injector;

import hagrid.demand.LogisticsDataProcessor;
import hagrid.demand.NetworkProcessor;

public class App {
    private static final Logger LOGGER = LogManager.getLogger(App.class);

    public static void main(String[] args) {
        LOGGER.info("Starting application...");

        // Create Injector with HagridModule
        Injector injector = Guice.createInjector(new HagridModule("phd/input/config.xml"));

        // Initialize components with injected dependencies
        LOGGER.info("Initializing NetworkProcessor...");
        NetworkProcessor networkProcessor = injector.getInstance(NetworkProcessor.class);

        // Parallelize NetworkProcessor
        LOGGER.info("Starting network processing...");
        networkProcessor.run();

        LOGGER.info("Network processing completed. Subnetworks created.");

        // Initialize LogisticsDataProcessor with injected dependencies
        LOGGER.info("Initializing LogisticsDataProcessor...");
        LogisticsDataProcessor logisticsDataProcessor = injector.getInstance(LogisticsDataProcessor.class);

        // Start LogisticsDataProcessor
        LOGGER.info("Starting logistics data processing...");
        logisticsDataProcessor.run();
        LOGGER.info("Logistics data processing completed.");

        // DemandProcessor demandProcessor = new
        // DemandProcessor(hagridConfig.getFreightDemandPath(),
        // hagridConfig.getConcept().equalsIgnoreCase("white_label"));
        // CarrierManager carrierManager = new
        // CarrierManager(demandProcessor.getCarrierDemand(), hagridConfig);
        // RoutingManager routingManager = new
        // RoutingManager(carrierManager.getCarriers(),
        // hagridConfig.getAlgorithmFile());
        // ResultWriter resultWriter = new ResultWriter(carrierManager.getCarriers(),
        // hagridConfig.getOutputDirectory());

        // Parallelize NetworkProcessor and DemandProcessor
        LOGGER.info("Starting demand processing...");

        // Sequential tasks
        // LOGGER.info("Initializing carriers...");
        // carrierManager.initializeCarriers(subNetwork); // Pass subNetwork to
        // CarrierManager if needed
        // LOGGER.info("Routing carriers...");
        // routingManager.routeCarriers();
        // LOGGER.info("Writing results...");
        // resultWriter.writeResults();

        LOGGER.info("Application finished.");
    }
}
