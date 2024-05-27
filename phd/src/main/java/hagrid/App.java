package hagrid;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.google.inject.Guice;
import com.google.inject.Injector;

import hagrid.demand.DemandProcessor;
import hagrid.demand.LogisticsDataProcessor;
import hagrid.demand.NetworkProcessor;
import hagrid.demand.ParcelGenerator;

public class App {
    private static final Logger LOGGER = LogManager.getLogger(App.class);

    public static void main(String[] args) {
        LOGGER.info("Starting application...");

        // Create the Guice injector with the HagridModule configuration
        Injector injector = Guice.createInjector(new HagridModule("phd/input/config.xml"));

        // Execute processing steps in a structured manner
        runNetworkProcessing(injector);            // Step 1: Process the network data
        runLogisticsDataProcessing(injector);      // Step 2: Process the logistics data
        runDemandProcessing(injector);             // Step 3: Process the freight demand data
        runParcelGeneration(injector);             // Step 4: Generate parcels based on the processed demand data

        LOGGER.info("Application finished.");
    }

    /**
     * Runs the network processing step, initializing and executing the NetworkProcessor.
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
     * Runs the logistics data processing step, initializing and executing the LogisticsDataProcessor.
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
     * Runs the demand processing step, initializing and executing the DemandProcessor.
     * This step processes the freight demand data to split and sort the parcel input data. 
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
     * Runs the parcel generation step based on sorted demand, initializing and executing the ParcelGenerator.
     * This step converts the processed demand data into parcel objects for further routing and delivery simulation in MATSim.
     * 
     * @param injector the Guice injector used for dependency injection.
     */
    private static void runParcelGeneration(Injector injector) {
        LOGGER.info("Initializing ParcelGenerator...");
        ParcelGenerator parcelGenerator = injector.getInstance(ParcelGenerator.class);

        LOGGER.info("Starting parcel generation based on sorted Demand...");
        parcelGenerator.run();
        LOGGER.info("Parcel generation completed.");
    }
}
