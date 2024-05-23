package hagrid;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;
import hagrid.demand.NetworkProcessor;

public class App {
    private static final Logger LOGGER = LogManager.getLogger(App.class);

    public static void main(String[] args) {
        LOGGER.info("Starting application...");

        // Load MATSim configuration
        LOGGER.info("Loading MATSim configuration...");
        Config config = ConfigUtils.loadConfig("phd/input/config.xml");

        // Load custom HagridConfigGroup
        LOGGER.info("Loading Hagrid configuration group...");
        ConfigUtils.addOrGetModule(config, HagridConfigGroup.class);

        // Create scenario
        LOGGER.info("Creating scenario...");
        Scenario scenario = ScenarioUtils.loadScenario(config);

        // Initialize components with HagridConfigGroup settings
        LOGGER.info("Initializing NetworkProcessor...");
        NetworkProcessor networkProcessor = new NetworkProcessor(scenario);

        // DemandProcessor demandProcessor = new DemandProcessor(hagridConfig.getFreightDemandPath(), hagridConfig.getConcept().equalsIgnoreCase("white_label"));
        // CarrierManager carrierManager = new CarrierManager(demandProcessor.getCarrierDemand(), hagridConfig);
        // RoutingManager routingManager = new RoutingManager(carrierManager.getCarriers(), hagridConfig.getAlgorithmFile());
        // ResultWriter resultWriter = new ResultWriter(carrierManager.getCarriers(), hagridConfig.getOutputDirectory());

        // Parallelize NetworkProcessor and DemandProcessor
        LOGGER.info("Starting network and demand processing...");
        ExecutorService executorService = Executors.newFixedThreadPool(2);
        executorService.submit(networkProcessor);
        // executorService.submit(demandProcessor);

        // Wait for both tasks to complete
        executorService.shutdown();
        try {
            executorService.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            LOGGER.error("Processing interrupted!", e);
        }

        // Retrieve the subnetwork for further processing if needed
        Network subNetwork = networkProcessor.getSubNetwork();
        LOGGER.info("Network processing completed. Subnetwork created.");

        // Sequential tasks
        // LOGGER.info("Initializing carriers...");
        // carrierManager.initializeCarriers(subNetwork); // Pass subNetwork to CarrierManager if needed
        // LOGGER.info("Routing carriers...");
        // routingManager.routeCarriers();
        // LOGGER.info("Writing results...");
        // resultWriter.writeResults();

        LOGGER.info("Application finished.");
    }
}
