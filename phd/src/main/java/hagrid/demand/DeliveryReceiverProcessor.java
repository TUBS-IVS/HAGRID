package hagrid.demand;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import org.matsim.api.core.v01.Scenario;


import hagrid.HagridConfigGroup;


@Singleton
public class DeliveryReceiverProcessor {

    private static final Logger LOGGER = LogManager.getLogger(DeliveryReceiverProcessor.class);

    @Inject
    private Scenario scenario;

    @Inject
    private HagridConfigGroup hagridConfig;

    public void run() {
        try {
            LOGGER.info("Reading delivery receiver data...");

            // TODO

            LOGGER.info("Delivery receiver completed.");
        } catch (Exception e) {
            LOGGER.error("Error reading logistics data", e);
        }
    }

    // Weitere Methoden können hier hinzugefügt werden

}
