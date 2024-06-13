package hagrid.utils.general;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierService;
import org.matsim.freight.carriers.Carriers;
import java.util.Map;
import java.util.ConcurrentModificationException;
import java.util.HashMap;

/**
 * Utility class for HAGRID that includes methods for converting carrier
 * attributes to strings and other general functions.
 */
public class HAGRIDUtils {

    private static final Logger LOGGER = LogManager.getLogger(HAGRIDUtils.class);

    /**
     * Retrieves the specified scenario element.
     *
     * @param elementName The name of the scenario element to retrieve.
     * @param <T>         The type of the scenario element.
     * @return The scenario element.
     * @throws IllegalStateException If the scenario element is missing.
     */
    public static <T> T getScenarioElementAs(String elementName, Scenario scenario) {
        Object element = scenario.getScenarioElement(elementName);
        if (element == null) {
            throw new IllegalStateException(elementName + " data is missing in the scenario.");
        }
        return (T) element;
    }

    public static void checkAndLogCarrierAttributes(Carriers carriers) {
        LOGGER.info("Starting to check and log carrier attributes");

        // Explicitly specify the type arguments for HashMap
        Map<Id<Carrier>, Carrier> carrierMap = new HashMap<Id<Carrier>, Carrier>(carriers.getCarriers());

        for (Carrier carrier : carrierMap.values()) {
            Map<String, Object> carrierAttributes = new HashMap<String, Object>(carrier.getAttributes().getAsMap());
            checkAndLogAttributes(carrierAttributes, carrier.getId().toString());

            Map<Id<CarrierService>, CarrierService> servicesMap = new HashMap<Id<CarrierService>, CarrierService>(
                    carrier.getServices());

            for (CarrierService service : servicesMap.values()) {
                Map<String, Object> serviceAttributes = new HashMap<String, Object>(service.getAttributes().getAsMap());
                checkAndLogAttributes(serviceAttributes, carrier.getId().toString());
            }
        }
    }

    private static void checkAndLogAttributes(Map<String, Object> attributesMap, String carrierId) {
        for (Map.Entry<String, Object> entry : attributesMap.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            try {
                // Enhanced logging
                LOGGER.debug("Processing key: " + key + ", value: " + value + " for Carrier ID: " + carrierId);

                // Attempt to convert the attribute to a string
                String valueAsString = value == null ? "null" : value.toString();

                LOGGER.debug("Successfully processed key: " + key + ", valueAsString: " + valueAsString
                        + " for Carrier ID: " + carrierId);
            } catch (ConcurrentModificationException e) {
                LOGGER.error("ConcurrentModificationException for Carrier ID: " + carrierId + ", Key: " + key
                        , e);
                throw e; // Re-throw the exception after logging
            } catch (Exception e) {
                LOGGER.error("Exception for Carrier ID: " + carrierId + ", Key: " + key + ", Value: " + value, e);
            }
        }
    }

}
