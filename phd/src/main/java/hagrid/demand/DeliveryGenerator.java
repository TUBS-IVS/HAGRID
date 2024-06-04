package hagrid.demand;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.geotools.api.feature.simple.SimpleFeature;
import org.jline.utils.Log;
import org.locationtech.jts.geom.MultiPoint;
import org.locationtech.jts.geom.Point;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.core.network.NetworkUtils;

import hagrid.HagridConfigGroup;
import hagrid.demand.Delivery.DeliveryMode;
import hagrid.utils.GeoUtils;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
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

                        // Initialize provider shape mapping dynamically
                        providerShapeMapping = generateProviderShapeMapping();

                        long totalParcels = calculateTotalParcels(carrierDemand);
                        LOGGER.info("Total Parcels from carrier demand: {}", totalParcels);

                        Map<String, ArrayList<Delivery>> deliveries = processCarrierDemand(carrierDemand, totalParcels);

                        // Add parcel lockers to deliveries
                        addParcelLockerServices(deliveries, parcelLockerList);

                        // Log parcel statistics
                        ParcelStatisticsLogger logger = new ParcelStatisticsLogger(false); // Set to true for detailed log
                        logger.logStatistics(deliveries);

                        // Store parcels in scenario
                        scenario.addScenarioElement("deliveries", deliveries);

                        LOGGER.info("Parcel generation completed.");

                } catch (Exception e) {
                        LOGGER.error("Error generating parcels", e);
                }
        }

        /**
         * Generates the provider shape mapping dynamically from the HagridConfigGroup.
         * 
         * This method ensures that the correct suffix ('_type' or '_typ') is applied to
         * provider names.
         * Due to the input data from the shapefile (SHP) being limited to 12
         * characters, some provider
         * names might have truncated suffixes such as '_typ' instead of '_type'. This
         * method handles
         * these cases by mapping '_type' for most providers and '_typ' for 'hermes' and
         * 'amazon'.
         *
         * @return A map of provider names to their corresponding shapefile attributes.
         */
        private Map<String, String> generateProviderShapeMapping() {
                return hagridConfig.getShpProviders().stream()
                                .collect(Collectors.toMap(
                                                shpType -> shpType.split("_")[0], // Extract provider name
                                                shpType -> {
                                                        String provider = shpType.split("_")[0];
                                                        if (provider.equals("hermes") || provider.equals("amazon")) {
                                                                return provider + "_typ"; // Use '_typ' for 'hermes' and
                                                                                          // 'amazon'
                                                        } else {
                                                                return provider + "_type"; // Use '_type' for all other
                                                                                           // providers
                                                        }
                                                }));
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
         * @param totalParcels  Expected total number of parcels.
         * @return Map of carrier demands with Parcel objects.
         */
        public Map<String, ArrayList<Delivery>> processCarrierDemand(Map<String, List<SimpleFeature>> carrierDemand,
                        long totalParcels) {

                // Check the total parcels before conversion
                long totalParcelsBefore = getTotalParcelsFromFeatures(carrierDemand);
                if (totalParcels != totalParcelsBefore) {
                        throw new IllegalStateException(
                                        "Total parcels before conversion do not match expected total parcels.");
                }

                // Convert the demand from SimpleFeature to Parcel objects
                Map<String, ArrayList<Delivery>> carrierDemandWithDeliveries = convertDemandFromShapeToParcels(
                                carrierDemand);

                // Check the total parcels after conversion
                long totalParcelsAfter = getTotalParcelsFromParcelObjects(carrierDemandWithDeliveries);
                if (totalParcels != totalParcelsAfter) {
                        throw new IllegalStateException(
                                        "Total parcels after conversion do not match expected total parcels.");
                }

                return carrierDemandWithDeliveries;
        }

        /**
         * Helper method to get total parcels from the original SimpleFeature map.
         *
         * @param demand Map of carrier demands with SimpleFeatures.
         * @return Total number of parcels.
         */
        private long getTotalParcelsFromFeatures(Map<String, List<SimpleFeature>> demand) {
                return demand.values().stream()
                                .mapToLong(List::size)
                                .sum();
        }

        /**
         * Helper method to get total parcels from the converted Parcel map.
         *
         * @param demandWithParcels Map of carrier demands with Parcel objects.
         * @return Total number of parcels.
         */
        private long getTotalParcelsFromParcelObjects(Map<String, ArrayList<Delivery>> demandWithParcels) {
                return demandWithParcels.values().stream()
                                .mapToLong(List::size)
                                .sum();
        }

        /**
         * Converts the carrier demand from SimpleFeature to Delivery objects.
         *
         * @param carrierDemand Map of carrier demands with SimpleFeatures.
         * @return Map of carrier demands with delivery objects.
         */
        private Map<String, ArrayList<Delivery>> convertDemandFromShapeToParcels(
                        Map<String, List<SimpleFeature>> carrierDemand) {
                return carrierDemand.entrySet().stream()
                                .collect(Collectors.toMap(
                                                Map.Entry::getKey, // Preserve the key (providerPLZ)
                                                entry -> { // Transform the value
                                                        String provider = entry.getKey().split("_")[0];
                                                        return entry.getValue().stream() // Stream the SimpleFeature
                                                                                         // list
                                                                        .map(simpleFeature -> createDelivery(
                                                                                        simpleFeature, provider,
                                                                                        DeliveryMode.HOME)) // Convert
                                                                        // each
                                                                        // SimpleFeature
                                                                        // to
                                                                        // Delivery
                                                                        .collect(Collectors
                                                                                        .toCollection(ArrayList::new)); // Collect
                                                                                                                        // to
                                                                                                                        // ArrayList<Delivery>
                                                }));
        }

        /**
         * Creates a Delivery object from a SimpleFeature.
         *
         * @param feature  SimpleFeature object.
         * @param provider Provider name.
         * @return Delivery object.
         */
        private Delivery createDelivery(SimpleFeature feature, String provider, Delivery.DeliveryMode mode) {
                Point point = ((MultiPoint) feature.getAttribute(0)).getCentroid();
                Coord coord = new Coord(point.getX(), point.getY());

                String deliveryPointId = String.valueOf((Long) feature.getAttribute("id"));
                String postalCode = (String) feature.getAttribute("postal_cod");

                Long amount = (Long) feature.getAttribute(provider + "_tag");

                String b2bInfo = getB2BInformation(feature, provider).toLowerCase();
                boolean isB2B = "b2b".equalsIgnoreCase(b2bInfo);

                ArrayList<Double> individualWeights = new ArrayList<>();
                for (int i = 0; i < amount; i++) {
                        double weight = parcelWeightGenerator.generateWeight(isB2B);
                        individualWeights.add(weight);
                }

                return new Delivery.Builder(deliveryPointId + "_" + deliveryPointId, coord)
                                .withProvider(provider)
                                .withAmount(amount.intValue())
                                .withParcelType(b2bInfo)
                                .withPostalCode(postalCode)
                                .withIndividualWeights(individualWeights)
                                .withDeliveryMode(mode)
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
         * @return String containing B2B information.
         * @throws IllegalArgumentException if the attribute for the provider is not
         *                                  found.
         */
        private String getB2BInformation(SimpleFeature feature, String provider) {
                if (hagridConfig.isWhiteLabel()) {
                        return "wl";
                } else {
                        String nameInShape = providerShapeMapping.get(provider);
                        String attributeValue = (String) feature.getAttribute(nameInShape);

                        if ("amazon".equals(provider) && (attributeValue == null || attributeValue.isEmpty())) {
                                return "b2c";
                        }

                        if (attributeValue == null || attributeValue.isEmpty()) {
                                throw new IllegalArgumentException(
                                                "No attribute found or attribute is empty for provider: " + provider
                                                                + " and attribute name: " + nameInShape
                                                                + " in feature: " + feature.getAttribute("id"));
                        }

                        return attributeValue;
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
                        double weight = parcelWeightGenerator.generateWeight(false); // Assuming parcel locker deliveries are
                                                                               // not B2B
                        individualWeights.add(weight);
                }

                return new Delivery.Builder(hub.getId().toString(), hub.getCoord())
                                .withProvider("dhl") // Assuming the provider is DHL for parcel lockers
                                .withAmount(parcelLockerDemand) // Set the number of parcels as per the configuration
                                .withParcelType("b2c") // Assuming parcel locker deliveries are B2C
                                .withPostalCode(hub.getAttributes().getAttribute("plz").toString())
                                .withIndividualWeights(individualWeights) // Set the generated individual weights
                                .withDeliveryMode(DeliveryMode.PARCEL_LOCKER_EXISTING)
                                .build();
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
