package hagrid.demand;

import com.google.inject.Inject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.algorithms.TransportModeNetworkFilter;
import org.matsim.core.network.io.MatsimNetworkReader;
import hagrid.HagridConfigGroup;
import hagrid.utils.general.Region;

import java.util.HashSet;
import java.util.Set;

/**
 * The NetworkProcessor class is responsible for processing the MATSim network,
 * adjusting link lengths and free speeds, and creating subnetworks based on specified criteria.
 * This class implements the Runnable interface to allow concurrent execution.
 */
public class NetworkProcessor implements Runnable {

    // Logger instance for logging information, debug messages, and errors
    private static final Logger LOGGER = LogManager.getLogger(NetworkProcessor.class);

    @Inject
    private Scenario scenario;
    @Inject
    private HagridConfigGroup hagridConfig;

    @Override
    public void run() {
        processNetwork();
    }

    /**
     * Processes the MATSim network by reading the network file, adjusting link lengths
     * and free speeds, and creating subnetworks with eligible links.
     */
    public void processNetwork() {
        try {
            LOGGER.info("Starting network processing...");

            hagridConfig.addRegion(Region.HANNOVER);

            // Read the network file
            LOGGER.info("Reading the network file from path: {}", hagridConfig.getNetworkXmlPath());
            Network network = scenario.getNetwork();
            new MatsimNetworkReader(network).readFile(hagridConfig.getNetworkXmlPath());

            // Adjust link lengths and free speeds
            LOGGER.info("Adjusting link lengths and free speeds...");
            for (Link link : network.getLinks().values()) {
                if (link.getLength() < hagridConfig.getMinLinkLength()) {
                    link.setLength(hagridConfig.getMinLinkLength());
                }
                if (link.getFreespeed() < hagridConfig.getMinFreeSpeed()) {
                    link.setFreespeed(hagridConfig.getMinFreeSpeed());
                }
            }
            LOGGER.info("Link adjustments completed.");

            // Filter network to only include car mode links
            LOGGER.info("Filtering network to include only car mode links...");
            Network carFilteredNetwork = NetworkUtils.createNetwork();
            Set<String> carMode = new HashSet<>();
            carMode.add("car");
            new TransportModeNetworkFilter(network).filter(carFilteredNetwork, carMode);
            LOGGER.info("Car mode network filtering completed.");

            // Create another filtered network based on custom criteria
            LOGGER.info("Creating parcel service network based on custom criteria...");
            Network parcelServiceNetwork = NetworkUtils.createNetwork();

            // Add all nodes from the carFilteredNetwork to the parcelServiceNetwork
            for (Node node : carFilteredNetwork.getNodes().values()) {
                parcelServiceNetwork.addNode(node);
            }

            int totalLinks = carFilteredNetwork.getLinks().size(); // Total number of links in the carFilteredNetwork
            LOGGER.info("Total number of links in the carFilteredNetwork: {}", totalLinks);
            int addedLinks = 0; // Counter for the number of links added to the parcelServiceNetwork

            // Iterate over all links in the carFilteredNetwork
            for (Link link : carFilteredNetwork.getLinks().values()) {
                String type = (String) link.getAttributes().getAttribute("osm:way:highway");

                // Check if the link meets the eligibility criteria
                if (isEligibleLink(type, link.getFreespeed())) {
                    // Add the link to the parcelServiceNetwork if it meets the criteria
                    parcelServiceNetwork.addLink(link);
                    addedLinks++;
                }
            }

            LOGGER.info("Parcel service network created successfully with {} links added.", addedLinks);
            LOGGER.info("Difference in number of links between carFilteredNetwork and parcel service network: {}", (totalLinks - addedLinks));

            // Add the networks to the scenario
            scenario.addScenarioElement("carFilteredNetwork", carFilteredNetwork);
            scenario.addScenarioElement("parcelServiceNetwork", parcelServiceNetwork);
        } catch (Exception e) {
            // Log any exceptions that occur during the network processing
            LOGGER.error("Error processing network", e);
        }
    }

    private boolean isEligibleLink(String type, double freeSpeed) {
        return type == null || (!type.contains("link") && !type.contains("motorway") && freeSpeed <= hagridConfig.getFreeSpeedThreshold());
    }
}
