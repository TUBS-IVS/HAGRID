package hagrid.utils.network;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import org.geotools.api.feature.simple.SimpleFeature;

import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.NetworkFactory;
import org.matsim.api.core.v01.network.NetworkWriter;
import org.matsim.api.core.v01.network.Node;

import org.matsim.core.network.DisallowedNextLinks;
import org.matsim.core.network.NetworkChangeEvent;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.TimeDependentNetwork;
import org.matsim.core.network.algorithms.TransportModeNetworkFilter;
import org.matsim.core.network.io.MatsimNetworkReader;

import org.matsim.core.utils.gis.GeoFileReader;
import org.matsim.utils.objectattributes.attributable.AttributesUtils;

import hagrid.utils.GeoUtils;

/**
 * This class filters and reduces a MATSim network based on specific criteria.
 * It reads a network file, adjusts link attributes, filters the network to include only specific modes,
 * and further filters the network based on a shapefile boundary.
 */
public class NetworkFilterAndReducer {

    private static final Logger LOGGER = LogManager.getLogger(NetworkFilterAndReducer.class);

    // Paths to input and output files
    private static final String NETWORK_XML_PATH = "U:/USEfUL-XT/matsim-hanover/01_MATSimModelCreator/Sim-Input/car_cargobike_network_zones_MH_V3.xml.gz";
    private static final String BOUNDARY_SHAPEFILE_PATH = "phd/sim-input/network/RH_useful__zone.shp";
    private static final String OUTPUT_NETWORK_PATH = "phd/sim-input/network/car_network_filtered_V2.xml.gz";

    // Thresholds for link attributes
    private static final double MIN_LINK_LENGTH = 5.0; // Minimum link length in meters
    private static final double MIN_FREE_SPEED = 2.777778; // Minimum free speed in m/s (10 km/h)

    /**
     * Main method to execute the network filtering and reduction process.
     *
     * @param args Command-line arguments (not used)
     */
    public static void main(String[] args) throws IOException {
        try {
            // Load the boundary features from the shapefile
            LOGGER.info("Reading boundary shapefile from path: {}", BOUNDARY_SHAPEFILE_PATH);
            LOGGER.info("Boundary Path: " + new File(BOUNDARY_SHAPEFILE_PATH).getAbsolutePath());

            Collection<SimpleFeature> boundaryFeatures = new GeoFileReader().readFileAndInitialize(BOUNDARY_SHAPEFILE_PATH);

            // Initialize the network
            LOGGER.info("Initializing network...");
            Network network = NetworkUtils.createNetwork();
            LOGGER.info("Network XML Path: " + new File(NETWORK_XML_PATH).getAbsolutePath());
            new MatsimNetworkReader(network).readFile(NETWORK_XML_PATH);

            // Adjust link lengths and free speeds
            adjustLinkAttributes(network);

            // Filter network to include only links with the mode "car"
            Network carFilteredNetwork = filterNetworkByMode(network, "car");

            // Further filter the network based on the boundary shapefile
            Network boundaryFilteredNetwork = filterNetworkByBoundary(carFilteredNetwork, boundaryFeatures);

            // Check if the filtered network has any links
            if (boundaryFilteredNetwork.getLinks().isEmpty()) {
                throw new RuntimeException(
                    "The filtered network has no links. Possible cause: mismatched coordinate systems between network and shapefile."
                );
            }

            // Copy network change events (if any)
            copyNetworkChangeEvents(carFilteredNetwork, boundaryFilteredNetwork);

            // Write the filtered network to a file
            LOGGER.info("Writing the filtered network to file: {}", OUTPUT_NETWORK_PATH);
            new NetworkWriter(boundaryFilteredNetwork).write(OUTPUT_NETWORK_PATH);

            LOGGER.info("Network filtering and reduction completed successfully.");

        } catch (RuntimeException e) {
            LOGGER.error("Runtime exception occurred: {}", e.getMessage());
        }
    }

    /**
     * Adjusts the link attributes of the network to ensure minimum link lengths and free speeds.
     *
     * @param network The MATSim network to adjust
     */
    private static void adjustLinkAttributes(Network network) {
        LOGGER.info("Adjusting link lengths and free speeds...");
        for (Link link : network.getLinks().values()) {
            // Ensure minimum link length
            if (link.getLength() < MIN_LINK_LENGTH) {
                link.setLength(MIN_LINK_LENGTH);
            }
            // Ensure minimum free speed
            if (link.getFreespeed() < MIN_FREE_SPEED) {
                link.setFreespeed(MIN_FREE_SPEED);
            }
        }
        LOGGER.info("Link adjustments completed.");
    }

    /**
     * Filters the network to include only links that allow the specified transport mode.
     *
     * @param network The original MATSim network
     * @param mode    The transport mode to filter by (e.g., "car")
     * @return A new MATSim network containing only the links that allow the specified mode
     */
    private static Network filterNetworkByMode(Network network, String mode) {
        LOGGER.info("Filtering network to include only '{}' mode links...", mode);
        Network filteredNetwork = NetworkUtils.createNetwork();
        Set<String> modes = new HashSet<>();
        modes.add(mode);
        new TransportModeNetworkFilter(network).filter(filteredNetwork, modes);
        LOGGER.info("'{}' mode network filtering completed.", mode);
        return filteredNetwork;
    }

    /**
     * Filters the network to include only links that intersect with the given boundary features.
     *
     * @param network          The MATSim network to filter
     * @param boundaryFeatures The collection of boundary features (from a shapefile)
     * @return A new MATSim network containing only the links that intersect with the boundary
     */
    private static Network filterNetworkByBoundary(Network network, Collection<SimpleFeature> boundaryFeatures) {
        LOGGER.info("Filtering network based on boundary shapefile...");

        // Create a new network to store the filtered links
        Network filteredNetwork = NetworkUtils.createNetwork();
        NetworkFactory factory = filteredNetwork.getFactory();

        // Get the boundary geometry (assuming the shapefile contains one or more polygons)
        Geometry boundaryGeometry = GeoUtils.getBoundaryGeometry(boundaryFeatures);

        // Note: It is assumed that both the network and the shapefile are in the same coordinate system.
        // Ensure that this is the case; otherwise, the spatial operations will not work correctly.

        // Copy nodes
        for (Node node : network.getNodes().values()) {
            Node newNode = factory.createNode(node.getId(), node.getCoord());
            AttributesUtils.copyAttributesFromTo(node, newNode);
            filteredNetwork.addNode(newNode);
        }

        // Copy links that intersect with the boundary
        Set<Id<Node>> nodesToInclude = new HashSet<>();
        for (Link link : network.getLinks().values()) {
            // Convert the link to a LineString geometry
            LineString linkGeometry = GeoUtils.createLineStringFromLink(link);

            // Check if the link intersects with the boundary geometry
            if (linkGeometry.intersects(boundaryGeometry)) {
                nodesToInclude.add(link.getFromNode().getId());
                nodesToInclude.add(link.getToNode().getId());

                Node fromNode = filteredNetwork.getNodes().get(link.getFromNode().getId());
                Node toNode = filteredNetwork.getNodes().get(link.getToNode().getId());

                Link newLink = factory.createLink(link.getId(), fromNode, toNode);

                // Copy attributes
                newLink.setAllowedModes(new HashSet<>(link.getAllowedModes()));
                newLink.setCapacity(link.getCapacity());
                newLink.setFreespeed(link.getFreespeed());
                newLink.setLength(link.getLength());
                newLink.setNumberOfLanes(link.getNumberOfLanes());
                NetworkUtils.setType(newLink, NetworkUtils.getType(link));
                AttributesUtils.copyAttributesFromTo(link, newLink);

                // Copy DisallowedNextLinks (if any)
                DisallowedNextLinks disallowedNextLinks = NetworkUtils.getDisallowedNextLinks(link);
                if (disallowedNextLinks != null) {
                    NetworkUtils.setDisallowedNextLinks(newLink, disallowedNextLinks.copyOnlyModes(newLink.getAllowedModes()));
                }

                filteredNetwork.addLink(newLink);
            }
        }

        // Remove nodes that are not used by the valid links
        Set<Id<Node>> nodesToRemove = new HashSet<>();
        for (Node node : filteredNetwork.getNodes().values()) {
            if (!nodesToInclude.contains(node.getId())) {
                nodesToRemove.add(node.getId());
            }
        }
        for (Id<Node> nodeId : nodesToRemove) {
            filteredNetwork.removeNode(nodeId);
        }

        LOGGER.info("Boundary-based network filtering completed.");
        return filteredNetwork;
    }

    /**
     * Copies network change events (NetworkChangeEvents) from the original network
     * to the filtered network, if any.
     *
     * @param originalNetwork The original network
     * @param filteredNetwork The filtered network
     */
    private static void copyNetworkChangeEvents(Network originalNetwork, Network filteredNetwork) {
        if (originalNetwork instanceof TimeDependentNetwork) {
            TimeDependentNetwork timeDependentOriginal = (TimeDependentNetwork) originalNetwork;
            TimeDependentNetwork timeDependentFiltered = (TimeDependentNetwork) filteredNetwork;

            if (timeDependentOriginal.getNetworkChangeEvents().size() > 0) {
                LOGGER.info("Copying network change events...");
                for (NetworkChangeEvent event : timeDependentOriginal.getNetworkChangeEvents()) {
                    NetworkChangeEvent filteredEvent = new NetworkChangeEvent(event.getStartTime());

                    for (Link link : event.getLinks()) {
                        Link filteredLink = filteredNetwork.getLinks().get(link.getId());
                        if (filteredLink != null) {
                            filteredEvent.addLink(filteredLink);
                        }
                    }

                    if (!filteredEvent.getLinks().isEmpty()) {
                        filteredEvent.setFlowCapacityChange(event.getFlowCapacityChange());
                        filteredEvent.setFreespeedChange(event.getFreespeedChange());
                        filteredEvent.setLanesChange(event.getLanesChange());

                        timeDependentFiltered.addNetworkChangeEvent(filteredEvent);
                    }
                }
                LOGGER.info("Network change events copied.");
            }
        }
    }
}
