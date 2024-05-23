package hagrid.demand;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.io.MatsimNetworkReader;
import hagrid.HagridConfigGroup;

public class NetworkProcessor implements Runnable {
    private static final Logger LOGGER = LogManager.getLogger(NetworkProcessor.class);

    private Scenario scenario;
    private Network subNetwork;
    private HagridConfigGroup hagridConfig;

    public NetworkProcessor(Scenario scenario) {
        this.scenario = scenario;
        this.hagridConfig = (HagridConfigGroup) scenario.getConfig().getModules().get(HagridConfigGroup.GROUPNAME);
    }

    @Override
    public void run() {
        processNetwork();
    }

    public void processNetwork() {
        try {
            LOGGER.info("Reading the network file...");
            Network network = scenario.getNetwork();

            new MatsimNetworkReader(network).readFile(hagridConfig.getNetworkXmlPath());

            LOGGER.info("Adjusting link lengths and free speeds...");
            for (Link link : network.getLinks().values()) {
                if (link.getLength() < hagridConfig.getMinLinkLength()) {
                    link.setLength(hagridConfig.getMinLinkLength());
                }
                if (link.getFreespeed() < hagridConfig.getMinFreeSpeed()) {
                    link.setFreespeed(hagridConfig.getMinFreeSpeed());
                }
            }

            LOGGER.info("Creating subnetwork...");
            subNetwork = NetworkUtils.createNetwork();

            for (Node node : network.getNodes().values()) {
                subNetwork.addNode(node);
            }

            int totalLinks = network.getLinks().size();
            LOGGER.debug("Total number of links before adding to subnetwork: " + totalLinks);
            int addedLinks = 0;

            for (Link link : network.getLinks().values()) {
                for (String mode : link.getAllowedModes()) {
                    if (mode.equals("car")) {
                        String type = (String) link.getAttributes().getAttribute("osm:way:highway");

                        if (isEligibleLink(type, link.getFreespeed())) {
                            subNetwork.addLink(link);
                            addedLinks++;
                            break;
                        }
                    }
                }
            }

            LOGGER.info("Subnetwork created successfully.");
            LOGGER.debug("Before logging total links and added links.");
            LOGGER.info("Total number of links in the main network: {}", totalLinks);
            LOGGER.info("Number of links added to the subnetwork: {}", addedLinks);
            LOGGER.info("Difference in number of links between main network and subnetwork: {}", (totalLinks - addedLinks));
            LOGGER.debug("After logging total links and added links.");
        } catch (Exception e) {
            LOGGER.error("Error processing network", e);
        }
    }

    private boolean isEligibleLink(String type, double freeSpeed) {
        return type == null || (!type.contains("link") && !type.contains("motorway") && freeSpeed <= hagridConfig.getFreeSpeedThreshold());
    }

    public Network getSubNetwork() {
        return subNetwork;
    }
}
