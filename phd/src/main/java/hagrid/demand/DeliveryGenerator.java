package hagrid.demand;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.geotools.api.feature.simple.SimpleFeature;

import org.locationtech.jts.geom.MultiPoint;
import org.locationtech.jts.geom.Point;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;

import hagrid.HagridConfigGroup;
import hagrid.utils.GeoUtils;
import hagrid.utils.demand.Delivery;
import hagrid.utils.demand.Hub;
import hagrid.utils.demand.WeightGenerator;
import hagrid.utils.demand.Delivery.DeliveryMode;
import hagrid.utils.demand.Delivery.ParcelType;
import hagrid.utils.general.ParcelStatisticsLogger;

import java.util.*;

import java.util.stream.Collectors;

/**
 * The ParcelGenerator class is responsible for converting sorted carrier demand
 * into Parcel objects and validating the totals.
 */
@Singleton
public class DeliveryGenerator implements Runnable {

        private static final Logger LOGGER = LogManager.getLogger(DeliveryGenerator.class);

        @Inject
        private Scenario scenario;

        @Inject
        private HagridConfigGroup hagridConfig;

        private Map<String, String> providerShapeMapping;

        private WeightGenerator parcelWeightGenerator = new WeightGenerator();

        @Override
        public void run() {
                try {
                        LOGGER.info("Generating parcels from sorted carrier demand...");
                        Map<String, List<SimpleFeature>> carrierDemand = Optional.ofNullable(
                                        (Map<String, List<SimpleFeature>>) scenario.getScenarioElement("carrierDemand"))
                                        .orElseThrow(() -> new IllegalStateException(
                                                        "Carrier demand data is missing in the scenario."));

                        Map<Id<Hub>, Hub> parcelLockerList = Optional
                                        .ofNullable((Map<Id<Hub>, Hub>) scenario.getScenarioElement("parcelLockerList"))
                                        .orElseThrow(() -> new IllegalStateException(
                                                        "Parcel locker list data is missing in the scenario."));

                        long totalParcels = calculateTotalParcels(carrierDemand);
                        LOGGER.info("Total Parcel Stops from carrier demand: {}", totalParcels);

                        Map<String, ArrayList<Delivery>> deliveries = processCarrierDemand(carrierDemand, totalParcels);

                        // Add parcel lockers to deliveries
                        addParcelLockerServices(deliveries, parcelLockerList);

                        // Log parcel statistics
                        ParcelStatisticsLogger logger = new ParcelStatisticsLogger(scenario, false); // Set to true for
                                                                                                     // detailed
                        // log
                        logger.logStatistics(deliveries);

                        // Store parcels in scenario
                        scenario.addScenarioElement("deliveries", deliveries);

                        LOGGER.info("Parcel generation completed.");

                } catch (Exception e) {
                        LOGGER.error("Error generating parcels", e);
                }
        }

        /**
         * Calculates the total number of parcels from the carrier demand.
         *
         * @param carrierDemand Map of carrier demands with SimpleFeatures.
         * @return Total number of parcels.
         */
        private long calculateTotalParcels(Map<String, List<SimpleFeature>> carrierDemand) {
                return carrierDemand.values().stream()
                                .mapToLong(List::size)
                                .sum();
        }

        /**
         * Converts carrier demand from SimpleFeature to Parcel objects and validates
         * the totals.
         *
         * @param carrierDemand Map of carrier demands with SimpleFeatures.
         * @param totalStops    Expected total number of parcels.
         * @return Map of carrier demands with Parcel objects.
         */
        private Map<String, ArrayList<Delivery>> processCarrierDemand(Map<String, List<SimpleFeature>> carrierDemand,
                        long totalStops) {

                // Check the total parcels before conversion
                long totalParcelStopsBefore = getTotalParcelStopsFromFeatures(carrierDemand);
                if (totalStops != totalParcelStopsBefore) {
                        throw new IllegalStateException(
                                        "Total parcels before conversion do not match expected total parcel stops.");
                }

                // Convert the demand from SimpleFeature to Parcel objects
                Map<String, ArrayList<Delivery>> carrierDemandWithDeliveries = convertDemandFromShapeToParcels(
                                carrierDemand);

                // Check the total parcels after conversion
                long totalParcelsAfter = getTotalParcelsFromParcelObjects(carrierDemandWithDeliveries);
                long expectedTotalParcels = calculateExpectedParcelsFromFeatures(carrierDemand);

                if (expectedTotalParcels != totalParcelsAfter) {
                        long diff = totalParcelsAfter - expectedTotalParcels;
                        // Log-Level ERROR oder WARN, je nach Schwere
                        LOGGER.error("Parcel count mismatch, expected: {}, actual: {}, difference: {}",
                                        expectedTotalParcels, totalParcelsAfter, diff);

                        throw new IllegalStateException(String.format(
                                        "Total parcels after conversion do not match expected total parcels: expected=%d, actual=%d, diff=%d",
                                        expectedTotalParcels, totalParcelsAfter, diff));
                }

                return carrierDemandWithDeliveries;
        }

        /**
         * Helper method to get total parcels stops from the original SimpleFeature map.
         *
         * @param demand Map of carrier demands with SimpleFeatures.
         * @return Total number of parcels.
         */
        private long getTotalParcelStopsFromFeatures(Map<String, List<SimpleFeature>> demand) {
                return demand.values().stream()
                                .mapToLong(List::size)
                                .sum();
        }

        /**
         * Helper method to calculate the total number of parcels
         * by summing the amount field from all Delivery objects.
         *
         * @param demandWithParcels Map of carrier demands with Delivery objects.
         * @return Total number of parcels across all deliveries.
         */
        private long getTotalParcelsFromParcelObjects(Map<String, ArrayList<Delivery>> demandWithParcels) {
                return demandWithParcels.values().stream()
                                .flatMap(List::stream) // flatten all delivery lists
                                .mapToLong(Delivery::getAmount) // sum up amount per delivery
                                .sum();
        }

        /**
         * /**
         * Calculates the expected total number of parcels from grouped carrier demand
         * features.
         * Only evaluates the carrier relevant for each group (based on the map key).
         * 
         * Each feature may contain total demand in <carrier>_tag and b2b in
         * <carrier>_type.
         * b2c is derived as (tag - type), and total = b2b + b2c.
         *
         * @param carrierDemand Map where key = "carrier_plz" (e.g., "dhl_30159") and
         *                      value = features
         * @return Total expected parcel count
         */
        private long calculateExpectedParcelsFromFeatures(Map<String, List<SimpleFeature>> carrierDemand) {
                return carrierDemand.entrySet().stream()
                                .mapToLong(entry -> {
                                        String key = entry.getKey(); // e.g. "dhl_30159"
                                        String[] parts = key.split("_");
                                        String carrierAbbr = parts[0].toLowerCase(); // extract "dhl"

                                        return entry.getValue().stream()
                                                        .mapToLong(feature -> {
                                                                long total = getLongAttribute(feature,
                                                                                carrierAbbr + "_tag");
                                                                long b2b = getLongAttribute(feature,
                                                                                carrierAbbr + "_type");
                                                                long b2c = Math.max(0L, total - b2b);
                                                                return b2b + b2c;
                                                        })
                                                        .sum();
                                })
                                .sum();
        }

        /**
         * Converts the carrier demand from SimpleFeature to Delivery objects.
         *
         * For each SimpleFeature, this method creates:
         * - A B2B delivery if the provider_type attribute > 0
         * - A B2C delivery if (provider_tag - provider_type) > 0
         *
         * The input key format is expected to be: "provider_plz" (e.g. "dhl_30159")
         *
         * @param carrierDemand Map where the key is "provider_plz", and the value is a
         *                      list of SimpleFeatures
         * @return Map with the same keys, each containing a list of generated Delivery
         *         objects.
         */
        private Map<String, ArrayList<Delivery>> convertDemandFromShapeToParcels(
                        Map<String, List<SimpleFeature>> carrierDemand) {

                return carrierDemand.entrySet().stream()
                                .collect(Collectors.toMap(
                                                Map.Entry::getKey, // keep key "provider_plz"
                                                entry -> {
                                                        String[] keyParts = entry.getKey().split("_");
                                                        if (keyParts.length < 2) {
                                                                throw new IllegalArgumentException(
                                                                                "Invalid carrier key format: "
                                                                                                + entry.getKey());
                                                        }

                                                        String provider = keyParts[0].toLowerCase(); // e.g. "dhl"
                                                        return entry.getValue().stream()
                                                                        .flatMap(feature -> {
                                                                                List<Delivery> deliveries = new ArrayList<>();

                                                                                // Handle shapefile truncation: limit to
                                                                                // 10 chars max
                                                                                String tagAttr = (provider + "_tag");
                                                                                String typeAttr = (provider + "_type");
                                                                                if (tagAttr.length() > 10)
                                                                                        tagAttr = tagAttr.substring(0,
                                                                                                        10);
                                                                                if (typeAttr.length() > 10)
                                                                                        typeAttr = typeAttr.substring(0,
                                                                                                        10);

                                                                                long total = getLongAttribute(feature,
                                                                                                tagAttr);
                                                                                long b2b = getLongAttribute(feature,
                                                                                                typeAttr);
                                                                                long b2c = Math.max(0, total - b2b);

                                                                                // Create B2B delivery
                                                                                if (b2b > 0) {
                                                                                        deliveries.add(createDelivery(
                                                                                                        feature,
                                                                                                        provider,
                                                                                                        DeliveryMode.HOME,
                                                                                                        ParcelType.B2B,
                                                                                                        b2b));
                                                                                }

                                                                                // Create B2C delivery
                                                                                if (b2c > 0) {
                                                                                        deliveries.add(createDelivery(
                                                                                                        feature,
                                                                                                        provider,
                                                                                                        DeliveryMode.HOME,
                                                                                                        ParcelType.B2C,
                                                                                                        b2c));
                                                                                }

                                                                                return deliveries.stream();
                                                                        })
                                                                        .collect(Collectors
                                                                                        .toCollection(ArrayList::new));
                                                }));
        }

        /**
         * Safely retrieves a numeric attribute value from a given feature and converts
         * it to a long.
         *
         * This method is designed to work with shapefile features that may contain
         * optional or
         * missing attributes. It ensures robust parsing of attribute values,
         * particularly for
         * numeric fields such as delivery demand counts.
         *
         * - If the attribute is found and is a valid Number (e.g., Integer, Double),
         * its long value is returned.
         * - If the attribute is missing, null, or not a Number, a fallback value of 0L
         * is returned.
         *
         * Example usage in delivery conversion:
         * - "dhl_tag" → total parcels
         * - "dhl_type" → business deliveries (B2B)
         *
         * @param feature  The SimpleFeature object from the shapefile.
         * @param attrName The name of the attribute to retrieve (e.g., "dhl_tag",
         *                 "amazon_type").
         * @return The long value of the attribute, or 0L if it is missing or invalid.
         */
        private long getLongAttribute(SimpleFeature feature, String attrName) {
                Object value = feature.getAttribute(attrName);

                // Check if the attribute exists and is numeric
                if (value instanceof Number) {
                        return ((Number) value).longValue(); // Convert to long
                }

                // Return 0L for missing or non-numeric attributes
                return 0L;
        }

        /**
         * Creates a Delivery object from a SimpleFeature.
         *
         * @param feature         SimpleFeature object.
         * @param provider        Provider name.
         * @param deliveryMode    Delivery mode (HOME, PARCEL_LOCKER, etc.).
         * @param parcelType      Parcel type (B2B, B2C, etc.).
         * @param numberOfParcels Number of parcels to be delivered at this stop.
         * @return Delivery object.
         */
        private Delivery createDelivery(SimpleFeature feature, String provider, Delivery.DeliveryMode mode,
                        ParcelType parcelType, long numberOfParcels) {
                Point point = ((Point) feature.getAttribute(0)).getCentroid();
                Coord coord = new Coord(point.getX(), point.getY());

                String deliveryPointId = String.valueOf((Long) feature.getAttribute("id"));
                String postalCode = (String) feature.getAttribute("postal_cod");

                Long amount = numberOfParcels;

                boolean isB2B = Delivery.ParcelType.B2B.equals(parcelType);

                ArrayList<Double> individualWeights = new ArrayList<>();
                for (int i = 0; i < amount; i++) {
                        double weight = parcelWeightGenerator.generateWeight(isB2B);
                        individualWeights.add(weight);
                }

                return Delivery.builder()
                                .id(deliveryPointId + "_" + deliveryPointId)
                                .coordinate(coord)
                                .provider(provider)
                                .amount(amount.intValue())
                                .parcelType(parcelType)
                                .postalCode(postalCode)
                                .individualWeights(individualWeights)
                                .deliveryMode(mode)
                                .build();
        }

        /**
         * Retrieves B2B information for the given feature and provider.
         *
         * This method checks if the configuration is set to white label. If so, it
         * returns "wl". Otherwise, it retrieves the attribute name corresponding to the
         * provider from the providerShapeMapping and attempts to fetch the attribute
         * value from the given feature. If the attribute is not found, it throws an
         * IllegalArgumentException.
         *
         * @param feature  SimpleFeature object representing a geographical feature.
         * @param provider String representing the provider name.
         * @return Delivery.ParcelType containing B2B information.
         * @throws IllegalArgumentException if the attribute for the provider is not
         *                                  found.
         */
        private Delivery.ParcelType getB2BInformation(SimpleFeature feature, String provider) {
                if (hagridConfig.isWhiteLabel()) {
                        return Delivery.ParcelType.WHITE_LABEL;
                } else {
                        String nameInShape = providerShapeMapping.get(provider);
                        String attributeValue = (String) feature.getAttribute(nameInShape);

                        if ("amazon".equals(provider) && (attributeValue == null || attributeValue.isEmpty())) {
                                return Delivery.ParcelType.B2C;
                        }

                        if (attributeValue == null || attributeValue.isEmpty()) {
                                throw new IllegalArgumentException(
                                                "No attribute found or attribute is empty for provider: " + provider
                                                                + " and attribute name: " + nameInShape
                                                                + " in feature: " + feature.getAttribute("id"));
                        }

                        return Delivery.ParcelType.valueOf(attributeValue.toUpperCase());
                }
        }

        /**
         * Adds parcel locker services to the delivery map. It finds the closest
         * delivery
         * to each parcel locker and assigns it as the supplier for that locker.
         *
         * @param deliveries       Map of carrier demands with Delivery objects.
         * @param parcelLockerList Map of parcel locker Hubs.
         */
        private void addParcelLockerServices(Map<String, ArrayList<Delivery>> deliveries,
                        Map<Id<Hub>, Hub> parcelLockerList) {
                parcelLockerList.values().stream()
                                .filter(hub -> hub.getType().contains("PACKSTATION"))
                                .forEach(hub -> {
                                        Integer plz = (Integer) hub.getAttributes().getAttribute("plz");
                                        List<String> possibleDeliveryKeys = findPossibleDeliveryKeys(deliveries, plz,
                                                        hagridConfig.isWhiteLabel());

                                        String closestDeliveryKey = getDeliveryKey(deliveries, hub,
                                                        possibleDeliveryKeys);

                                        // Create a new delivery object for the parcel locker
                                        Delivery parcelLockerDelivery = createParcelLockerDelivery(hub);

                                        // Add the parcel locker delivery to the corresponding delivery list
                                        deliveries.get(closestDeliveryKey).add(parcelLockerDelivery);
                                });
        }

        /**
         * Creates a Delivery object for a parcel locker with newly calculated weights.
         *
         * @param hub The Hub object representing the parcel locker.
         * @return A Delivery object representing the parcel locker delivery.
         */
        private Delivery createParcelLockerDelivery(Hub hub) {
                // Retrieve the parcel locker demand from the configuration
                int parcelLockerDemand = hagridConfig.getParcelLockerDemand();

                // Generate new individual weights for the parcels
                ArrayList<Double> individualWeights = new ArrayList<>();
                for (int i = 0; i < parcelLockerDemand; i++) {
                        double weight = parcelWeightGenerator.generateWeight(false); // Assuming parcel locker
                                                                                     // deliveries are
                                                                                     // not B2B
                        individualWeights.add(weight);
                }

                return Delivery.builder() // Start building a new Delivery object using the Builder pattern
                                .id(hub.getId().toString() + "_locker") // Set the ID of the delivery point as a string
                                // representation of hub's ID
                                .coordinate(hub.getCoord()) // Set the coordinates of the delivery point using the hub's
                                                            // coordinates
                                .provider("dhl") // Assume the provider is DHL for parcel lockers
                                .amount(parcelLockerDemand) // Set the number of parcels as per the configuration
                                .parcelType(Delivery.ParcelType.B2C) // Assume parcel locker deliveries are
                                                                     // Business-to-Consumer (B2C)
                                .postalCode(hub.getAttributes().getAttribute("plz").toString()) // Set the postal code
                                                                                                // from hub's attributes
                                .individualWeights(individualWeights) // Set the generated individual weights for each
                                                                      // parcel
                                .deliveryMode(Delivery.DeliveryMode.PARCEL_LOCKER_EXISTING) // Set the delivery mode to
                                                                                            // existing parcel locker
                                .build(); // Build the Delivery object

        }

        /**
         * Finds the closest delivery key for a given hub.
         *
         * @param deliveries           Map of carrier demands with Delivery objects.
         * @param hub                  The parcel locker hub.
         * @param possibleDeliveryKeys List of possible delivery keys for the given
         *                             postal code.
         * @return The closest delivery key for the given hub.
         */
        private String getDeliveryKey(Map<String, ArrayList<Delivery>> deliveries, Hub hub,
                        List<String> possibleDeliveryKeys) {
                List<Delivery> possibleDeliveries = possibleDeliveryKeys.stream()
                                .flatMap(key -> deliveries.get(key).stream())
                                .collect(Collectors.toList());

                if (possibleDeliveries.isEmpty()) {
                        throw new IllegalStateException(
                                        "No deliveries found for PLZ: " + hub.getAttributes().getAttribute("plz"));
                }

                // Find the closest delivery to the current parcel locker hub
                Delivery closestDelivery = GeoUtils.findClosestDeliveryToCoord(possibleDeliveries, hub.getCoord());

                // Find the corresponding delivery key for the closest delivery
                return possibleDeliveryKeys.stream()
                                .filter(key -> deliveries.get(key).contains(closestDelivery))
                                .findFirst()
                                .orElseThrow(() -> new IllegalStateException(
                                                "No matching key found for closest delivery."));
        }

        /**
         * Finds possible delivery keys based on the provided postal code (PLZ) and
         * whether
         * it is a white label delivery or not. If no keys are found, it throws an
         * exception.
         *
         * @param deliveries   The map of all deliveries, where the key is the delivery
         *                     identifier.
         * @param plz          The postal code to search for in the delivery keys.
         * @param isWhiteLabel Whether the search is for white label deliveries.
         * @return A list of possible delivery keys that match the provided postal code
         *         and prefix.
         * @throws IllegalArgumentException If no matching delivery keys are found.
         */
        private static List<String> findPossibleDeliveryKeys(Map<String, ArrayList<Delivery>> deliveries, Integer plz,
                        boolean isWhiteLabel) {
                // Determine the prefix based on whether it is a white label delivery or not
                String prefix = isWhiteLabel ? "wl_" + plz : "dhl_" + plz;

                // Filter the delivery keys that start with the determined prefix
                List<String> possibleKeys = deliveries.keySet().stream()
                                .filter(key -> key.startsWith(prefix))
                                .collect(Collectors.toList());

                // If no keys are found, throw an exception
                if (possibleKeys.isEmpty()) {
                        throw new IllegalArgumentException(
                                        "No delivery keys found for PLZ: " + plz + " with prefix: " + prefix);
                }

                // Return the list of possible keys
                return possibleKeys;
        }

}
