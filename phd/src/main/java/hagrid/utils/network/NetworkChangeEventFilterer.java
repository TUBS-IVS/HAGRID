package hagrid.utils.network;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.network.NetworkChangeEvent;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.algorithms.NetworkCleaner;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.matsim.core.network.io.NetworkChangeEventsParser;
import org.matsim.core.network.io.NetworkChangeEventsWriter;
import org.matsim.core.network.io.NetworkWriter;

/**
 * This class filters out {@link NetworkChangeEvent}s whose associated links
 * are not present in a filtered MATSim network. It reads a full network,
 * a filtered network, and a list of change events, removes all change events
 * referencing missing links, and writes the filtered events to an output file.
 * 
 * <p>This is useful when working with a simplified or mode-specific network
 * where not all original links are included.</p>
 */
public class NetworkChangeEventFilterer {

    private static final Logger LOGGER = LogManager.getLogger(NetworkChangeEventFilterer.class);

    // Paths to input and output files
    private static final String INPUT_NETWORK_ORIGINAL_PATH = "phd/sim-input/network/car_cargobike_network_zones_MH_V3.xml.gz";
    private static final String INPUT_NETWORK_PATH = "phd/sim-input/network/car_network_filtered_V2.xml.gz";
    private static final String OUTPUT_NETWORK_PATH = "phd/sim-input/network/car_network_filtered_V2_clean.xml.gz";
    private static final String INPUT_CHANGE_EVENT_PATH = "phd/sim-input/network/hanover-v1.0-10pct-ct.resulting.networkChangeEvents.smooth.xml.gz";
    private static final String OUTPUT_CHANGE_EVENT_PATH = "phd/sim-input/network/car_network_filtered_V2_change_events.xml.gz";

    /**
     * Main method that performs the filtering process.
     * 
     * @param args command-line arguments (not used)
     * @throws IOException if any of the input files cannot be read or output file cannot be written
     */
    public static void main(String[] args) throws IOException {

        // Load the full network
        LOGGER.info("Reading full network...");
        Network networkOriginal = NetworkUtils.createNetwork();
        LOGGER.info("Full network XML path: {}", new File(INPUT_NETWORK_ORIGINAL_PATH).getAbsolutePath());
        new MatsimNetworkReader(networkOriginal).readFile(INPUT_NETWORK_ORIGINAL_PATH);

        // Load network change events
        LOGGER.info("Reading network change events...");
        LOGGER.info("Network change events XML path: {}", new File(INPUT_CHANGE_EVENT_PATH).getAbsolutePath());
        List<NetworkChangeEvent> networkChangeEvents = new ArrayList<>();
        new NetworkChangeEventsParser(networkOriginal, networkChangeEvents).readFile(INPUT_CHANGE_EVENT_PATH);
        LOGGER.info("Number of parsed network change events: {}", networkChangeEvents.size());

        // Load the filtered network
        LOGGER.info("Reading filtered network...");
        Network networkFiltered = NetworkUtils.createNetwork();
        LOGGER.info("Filtered network XML path: {}", new File(INPUT_NETWORK_PATH).getAbsolutePath());
        new MatsimNetworkReader(networkFiltered).readFile(INPUT_NETWORK_PATH);
        new NetworkCleaner().run(networkFiltered);

        int removedCounter = 0;
        int totalEvents = networkChangeEvents.size();
        int processedEvents = 0;

        LOGGER.info("Starting filtering of {} NetworkChangeEvents...", totalEvents);

        // Use iterator for safe removal
        Iterator<NetworkChangeEvent> eventIterator = networkChangeEvents.iterator();
        while (eventIterator.hasNext()) {
            NetworkChangeEvent event = eventIterator.next();
            boolean removeEvent = false;

            // Check if all links in the event exist in the filtered network
            for (Link link : event.getLinks()) {
                if (!networkFiltered.getLinks().containsKey(link.getId())) {
                    removeEvent = true;
                    break;
                }
            }

            if (removeEvent) {
                eventIterator.remove();
                removedCounter++;
            }

            processedEvents++;

            // Log progress every 10% or after changes
            int progressInterval = totalEvents / 10;
            if (progressInterval == 0 || processedEvents % progressInterval == 0) {
                double progressPercent = (double) processedEvents / totalEvents * 100;
                LOGGER.info("Progress: {}% ({} of {} processed, {} removed)",
                    String.format("%.2f", progressPercent), processedEvents, totalEvents, removedCounter);
            }
        }

        LOGGER.info("Filtering completed: {} of {} events removed.", removedCounter, totalEvents);
        LOGGER.info("Number of filtered network change events: {}", networkChangeEvents.size());

        LOGGER.info("Writing filtered events to file: {}", OUTPUT_CHANGE_EVENT_PATH);
        new NetworkChangeEventsWriter().write(OUTPUT_CHANGE_EVENT_PATH, networkChangeEvents);
        new NetworkWriter(networkFiltered).write(OUTPUT_NETWORK_PATH);
    }
}
