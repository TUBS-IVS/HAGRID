package hagrid.hannover.demand;

import com.google.inject.Inject;
import hagrid.core.HagridConfig;
import hagrid.core.routing.ModalRoutingNetwork;
import hagrid.core.util.OutputFileSet;
import hagrid.hannover.pipeline.PipelineStepException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.NetworkWriter;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.algorithms.NetworkCleaner;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.matsim.core.scenario.MutableScenario;

import java.nio.file.Path;
import java.util.List;

/** Owns the demand pipeline's single network import and publishes only fully prepared results. */
public class NetworkProcessor implements Runnable {
    private static final Logger LOGGER = LogManager.getLogger(NetworkProcessor.class);
    @Inject private Scenario scenario;
    @Inject private HagridConfig hagridConfig;

    @Override public void run() { processNetwork(); }

    public void processNetwork() {
        try {
            if (!(scenario instanceof MutableScenario mutable)) {
                throw new IllegalStateException("Network initialization requires a mutable demand scenario");
            }
            Network previous = scenario.getNetwork();
            if (!previous.getNodes().isEmpty() || !previous.getLinks().isEmpty()
                    || scenario.getScenarioElement("carFilteredNetwork") != null
                    || scenario.getScenarioElement("parcelServiceNetwork") != null) {
                throw new IllegalStateException("Demand network was already initialized; refusing a second import");
            }
            mutable.setNetwork(previous); // Check the demand lifecycle lock before reading or publishing files.
            Network network = NetworkUtils.createNetwork(scenario.getConfig());
            LOGGER.info("Reading demand network once: {}", hagridConfig.getNetworkXmlPath());
            readNetwork(network, hagridConfig.getNetworkXmlPath());
            for (Link link : network.getLinks().values()) {
                if (link.getLength() < hagridConfig.getMinLinkLength()) link.setLength(hagridConfig.getMinLinkLength());
                if (link.getFreespeed() < hagridConfig.getMinFreeSpeed()) link.setFreespeed(hagridConfig.getMinFreeSpeed());
            }
            Network car = ModalRoutingNetwork.forMode(network, "car");
            new NetworkCleaner().run(car);
            Network parcel = ModalRoutingNetwork.forMode(car, "car", link ->
                    isEligibleLink((String) link.getAttributes().getAttribute("osm:way:highway"), link.getFreespeed()));
            publish(mutable, network, car, parcel);
            LOGGER.info("Network processing completed: {} car links, {} parcel links", car.getLinks().size(), parcel.getLinks().size());
        } catch (Exception failure) {
            throw new PipelineStepException("Network Processing", failure);
        }
    }

    /** Single import boundary; also allows tests to count actual native reads. */
    protected void readNetwork(Network network, String path) { new MatsimNetworkReader(network).readFile(path); }

    private void publish(MutableScenario mutable, Network network, Network car, Network parcel) throws java.io.IOException {
        try {
            mutable.addScenarioElement("carFilteredNetwork", car);
            mutable.addScenarioElement("parcelServiceNetwork", parcel);
            OutputFileSet.publish(List.of(new OutputFileSet.Artifact(Path.of(hagridConfig.io().networkFiltered()),
                    temporary -> new NetworkWriter(car).write(temporary.toString()))));
            mutable.setNetwork(network);
        } catch (java.io.IOException | RuntimeException failure) {
            mutable.removeScenarioElement("parcelServiceNetwork");
            mutable.removeScenarioElement("carFilteredNetwork");
            throw failure;
        }
    }

    private boolean isEligibleLink(String type, double freeSpeed) {
        return type == null || (!type.contains("link") && !type.contains("motorway") && freeSpeed <= hagridConfig.getFreeSpeedThreshold());
    }
}
