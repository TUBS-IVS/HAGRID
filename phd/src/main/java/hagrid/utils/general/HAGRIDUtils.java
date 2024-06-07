package hagrid.utils.general;

import org.matsim.api.core.v01.Scenario;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierService;
import org.matsim.freight.carriers.CarrierShipment;
import org.matsim.freight.carriers.Carriers;

import java.util.Map;
import java.util.HashMap;

/**
 * Utility class for HAGRID that includes methods for converting carrier attributes to strings and other general functions.
 */
public class HAGRIDUtils {

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

    /**
     * Converts all carrier attributes, carrier service attributes, and carrier shipment attributes to String.
     *
     * @param carriers The carriers whose attributes are to be converted.
     */
    public static void convertCarrierAttributesToString(Carriers carriers) {
        for (Carrier carrier : carriers.getCarriers().values()) {
            convertAttributesToString(carrier.getAttributes());

            carrier.getServices().values().forEach(service -> {
                convertAttributesToString(service.getAttributes());
            });

            carrier.getShipments().values().forEach(shipment -> {
                convertAttributesToString(shipment.getAttributes());
            });
        }
    }

    /**
     * Helper method to convert the attributes of an attributable to String.
     *
     * @param attributable The attributable whose attributes are to be converted.
     */
    private static void convertAttributesToString(org.matsim.utils.objectattributes.attributable.Attributes attributable) {
        Map<String, Object> attributesCopy = new HashMap<>(attributable.getAsMap());
        attributesCopy.forEach((key, value) -> {
            attributable.putAttribute(key, value == null ? "null" : value.toString());
        });
    }
}
