package hagrid;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.google.inject.Guice;
import com.google.inject.Injector;

import hagrid.demand.CarrierGenerator;
import hagrid.demand.CarrierRouter;
import hagrid.demand.DeliveryGenerator;
import hagrid.demand.DemandProcessor;
import hagrid.demand.LogisticsDataProcessor;
import hagrid.demand.NetworkProcessor;
import hagrid.demand.SupplyCarrierGenerator;
import hagrid.utils.routing.ThreadingType;

public class App {
    private static final Logger LOGGER = LogManager.getLogger(App.class);

    public static void main(String[] args) {
        LOGGER.info("Starting application...");

        // Create the Guice injector with the HagridModule configuration
        Injector injector = Guice.createInjector(new HagridModule("phd/input/config.xml"));

        // Execute processing steps in a structured manner
        runNetworkProcessing(injector); // Step 1: Process the network data
        runLogisticsDataProcessing(injector); // Step 2: Process the logistics data
        runDemandProcessing(injector); // Step 3: Process the freight demand data
        runDeliveryGeneration(injector); // Step 4: Generate parcels based on the processed demand data
        runCarrierGeneration(injector); // Step 5: Generate carriers based on the processed demand data
        runSupplyGeneration(injector); // Step 6: Generate supply carriers based on the generated carriers
        
        // runRouter(injector, ThreadingType.COMPLETABLE_FUTURE); // Step 7: Run routing for delivery supply carriers based on the generated
                                                // carriers
        
        // runRouter(injector, ThreadingType.SINGLE_THREAD); // Step 7: Run routing for delivery supply carriers based on
        //                                                    // the generated
        // // carriers

        LOGGER.info("Application finished.");
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

}
