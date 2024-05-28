package hagrid.demand;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.geotools.api.feature.simple.SimpleFeature;
import org.locationtech.jts.geom.MultiPoint;
import org.locationtech.jts.geom.Point;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Scenario;
import hagrid.HagridConfigGroup;

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

    @Override
    public void run() {
        try {
            LOGGER.info("Generating parcels from sorted carrier demand...");
            Map<String, List<SimpleFeature>> carrierDemand = (Map<String, List<SimpleFeature>>) scenario
                    .getScenarioElement("carrierDemand");

            if (carrierDemand == null) {
                throw new IllegalStateException("Carrier demand data is missing in the scenario.");
            }

            // Initialize provider shape mapping dynamically
            providerShapeMapping = generateProviderShapeMapping();

            long totalParcels = calculateTotalParcels(carrierDemand);
            LOGGER.info("Total Parcels from carrier demand: {}", totalParcels);

            Map<String, ArrayList<Delivery>> deliveries = processCarrierDemand(carrierDemand, totalParcels);

            // Log parcel statistics
            logParcelStatistics(deliveries);

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
                                return provider + "_typ"; // Use '_typ' for 'hermes' and 'amazon'
                            } else {
                                return provider + "_type"; // Use '_type' for all other providers
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
            throw new IllegalStateException("Total parcels before conversion do not match expected total parcels.");
        }

        // Convert the demand from SimpleFeature to Parcel objects
        Map<String, ArrayList<Delivery>> carrierDemandWithParcels = convertDemandFromShapeToParcels(carrierDemand);

        // Check the total parcels after conversion
        long totalParcelsAfter = getTotalParcelsFromParcelObjects(carrierDemandWithParcels);
        if (totalParcels != totalParcelsAfter) {
            throw new IllegalStateException("Total parcels after conversion do not match expected total parcels.");
        }

        return carrierDemandWithParcels;
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
     * Converts the carrier demand from SimpleFeature to Parcel objects.
     *
     * @param carrierDemand Map of carrier demands with SimpleFeatures.
     * @return Map of carrier demands with Parcel objects.
     */
    private Map<String, ArrayList<Delivery>> convertDemandFromShapeToParcels(
            Map<String, List<SimpleFeature>> carrierDemand) {
        return carrierDemand.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey, // Preserve the key (providerPLZ)
                        entry -> { // Transform the value
                            String provider = entry.getKey().split("_")[0];
                            return entry.getValue().stream() // Stream the SimpleFeature list
                                    .map(simpleFeature -> createDelivery(simpleFeature, provider)) // Convert each
                                                                                                   // SimpleFeature to
                                                                                                   // Parcel
                                    .collect(Collectors.toCollection(ArrayList::new)); // Collect to ArrayList<Parcel>
                        }));
    }

    /**
     * Creates a Delivery object from a SimpleFeature.
     *
     * @param feature  SimpleFeature object.
     * @param provider Provider name.
     * @return Delivery object.
     */
    private Delivery createDelivery(SimpleFeature feature, String provider) {
        Point point = ((MultiPoint) feature.getAttribute(0)).getCentroid();
        Coord coord = new Coord(point.getX(), point.getY());

        String deliveryPointId = String.valueOf((Long) feature.getAttribute("id"));
        String postalCode = (String) feature.getAttribute("postal_cod");

        Long amount = (Long) feature.getAttribute(provider + "_tag");

        String b2bInfo = getB2BInformation(feature, provider);
        boolean isB2B = "b2b".equalsIgnoreCase(b2bInfo);

        ArrayList<Double> individualWeights = new ArrayList<>();
        for (int i = 0; i < amount; i++) {
            double weight = WeightGenerator.generateWeight(
                    isB2B ? WeightGenerator.getDefaultB2BWeightClasses() : WeightGenerator.getDefaultWeightClasses(),
                    isB2B ? WeightGenerator.getDefaultB2BWeightRanges() : WeightGenerator.getDefaultWeightRanges(),
                    isB2B ? WeightGenerator.getDefaultAlphaParamsB2B() : WeightGenerator.getDefaultAlphaParamsRegular(),
                    isB2B ? WeightGenerator.getDefaultBetaParamsB2B() : WeightGenerator.getDefaultBetaParamsRegular());
            individualWeights.add(weight); 
        }

        return new Delivery.Builder(deliveryPointId + "_" + deliveryPointId, coord)
                .withProvider(provider)
                .withAmount(amount.intValue())
                .withParcelType(b2bInfo)
                .withPostalCode(postalCode)
                .withIndividualWeights(individualWeights)
                .build();
    }

    /**
     * Retrieves B2B information for the given feature and provider.
     *
     * @param feature  SimpleFeature object.
     * @param provider Provider name.
     * @return B2B information.
     */
    private String getB2BInformation(SimpleFeature feature, String provider) {
        if (hagridConfig.isWhiteLabel()) {
            return "wl";
        } else {
            String nameInShape = providerShapeMapping.get(provider);
            return Optional.ofNullable((String) feature.getAttribute(nameInShape))
                    .orElseThrow(() -> new IllegalArgumentException(
                            "No attribute found for provider: " + provider + " and " + nameInShape));
        }
    }

/**
 * Logs detailed statistics about the generated deliveries, both by provider and by
 * postal code.
 *
 * This method aggregates and logs information such as the total number of
 * deliveries, B2B deliveries, total parcels, and B2B parcels for each provider and each postal
 * code. It also calculates and logs the ratios of B2B deliveries and parcels to the
 * totals, as well as the average weight per provider and postal code.
 *
 * @param deliveries Map of carrier demands with Delivery objects.
 */
private void logParcelStatistics(Map<String, ArrayList<Delivery>> deliveries) {
    // Atomic variables for thread-safe summation
    AtomicLong totalDeliveries = new AtomicLong();
    AtomicLong totalB2BDeliveries = new AtomicLong();
    AtomicLong totalParcels = new AtomicLong();
    AtomicLong totalB2BParcels = new AtomicLong();
    AtomicLong totalWeight = new AtomicLong();
    AtomicLong totalB2BWeight = new AtomicLong();

    // StringBuilder for efficient log message creation
    StringBuilder logBuilder = new StringBuilder();

    // Logging statistics by provider
    logBuilder.append("=== Delivery Statistics by Provider ===\n");

    // Aggregate data by provider
    deliveries.values().stream()
            .flatMap(List::stream)
            .collect(Collectors.groupingBy(Delivery::getProvider))
            .forEach((provider, deliveryList) -> {
                long providerDeliveryCount = deliveryList.size();
                long providerB2BDeliveryCount = deliveryList.stream()
                        .filter(delivery -> "b2b".equalsIgnoreCase(delivery.getParcelType()))
                        .count();
                long providerParcelCount = deliveryList.stream()
                        .mapToLong(Delivery::getAmount)
                        .sum();
                long providerB2BParcelCount = deliveryList.stream()
                        .filter(delivery -> "b2b".equalsIgnoreCase(delivery.getParcelType()))
                        .mapToLong(Delivery::getAmount)
                        .sum();

                double providerTotalWeight = deliveryList.stream()
                        .flatMapToDouble(delivery -> delivery.getIndividualWeights().stream().mapToDouble(Double::doubleValue))
                        .sum();
                double providerB2BWeight = deliveryList.stream()
                        .filter(delivery -> "b2b".equalsIgnoreCase(delivery.getParcelType()))
                        .flatMapToDouble(delivery -> delivery.getIndividualWeights().stream().mapToDouble(Double::doubleValue))
                        .sum();
                double providerAverageWeight = providerTotalWeight / providerParcelCount;
                double providerAverageB2BWeight = providerB2BParcelCount == 0 ? 0 : providerB2BWeight / providerB2BParcelCount;

                // Update overall totals
                totalDeliveries.addAndGet(providerDeliveryCount);
                totalB2BDeliveries.addAndGet(providerB2BDeliveryCount);
                totalParcels.addAndGet(providerParcelCount);
                totalB2BParcels.addAndGet(providerB2BParcelCount);
                totalWeight.addAndGet((long) providerTotalWeight);
                totalB2BWeight.addAndGet((long) providerB2BWeight);

                // Append provider-specific statistics to log
                logBuilder.append(String.format(
                        "Provider: %s\n  Total Deliveries     : %,d\n  B2B Deliveries       : %,d\n  Total Parcels        : %,d\n  B2B Parcels          : %,d\n  B2B Delivery Ratio   : %.2f%%\n  B2B Parcel Ratio     : %.2f%%\n  Average Weight       : %.2f\n  Average B2B Weight   : %.2f\n\n",
                        provider, providerDeliveryCount, providerB2BDeliveryCount, providerParcelCount, providerB2BParcelCount,
                        (double) providerB2BDeliveryCount / providerDeliveryCount * 100,
                        (double) providerB2BParcelCount / providerParcelCount * 100,
                        providerAverageWeight, providerAverageB2BWeight));
            });

    // Logging summary by postal code
    logBuilder.append("=== Summary by Postal Code ===\n");

    // Aggregate data by postal code
    deliveries.values().stream()
            .flatMap(List::stream)
            .collect(Collectors.groupingBy(Delivery::getPostalCode))
            .forEach((postalCode, deliveryList) -> {
                long postalCodeDeliveryCount = deliveryList.size();
                long postalCodeB2BDeliveryCount = deliveryList.stream()
                        .filter(delivery -> "b2b".equalsIgnoreCase(delivery.getParcelType()))
                        .count();
                long postalCodeParcelCount = deliveryList.stream()
                        .mapToLong(Delivery::getAmount)
                        .sum();
                long postalCodeB2BParcelCount = deliveryList.stream()
                        .filter(delivery -> "b2b".equalsIgnoreCase(delivery.getParcelType()))
                        .mapToLong(Delivery::getAmount)
                        .sum();

                double postalCodeTotalWeight = deliveryList.stream()
                        .flatMapToDouble(delivery -> delivery.getIndividualWeights().stream().mapToDouble(Double::doubleValue))
                        .sum();
                double postalCodeB2BWeight = deliveryList.stream()
                        .filter(delivery -> "b2b".equalsIgnoreCase(delivery.getParcelType()))
                        .flatMapToDouble(delivery -> delivery.getIndividualWeights().stream().mapToDouble(Double::doubleValue))
                        .sum();
                double postalCodeAverageWeight = postalCodeTotalWeight / postalCodeParcelCount;
                double postalCodeAverageB2BWeight = postalCodeB2BParcelCount == 0 ? 0 : postalCodeB2BWeight / postalCodeB2BParcelCount;

                // Append postal code-specific statistics to log
                logBuilder.append(String.format(
                        "Postal Code: %s\n  Total Deliveries     : %,d\n  B2B Deliveries       : %,d\n  Total Parcels        : %,d\n  B2B Parcels          : %,d\n  B2B Delivery Ratio   : %.2f%%\n  B2B Parcel Ratio     : %.2f%%\n  Average Weight       : %.2f\n  Average B2B Weight   : %.2f\n\n",
                        postalCode, postalCodeDeliveryCount, postalCodeB2BDeliveryCount, postalCodeParcelCount,
                        postalCodeB2BParcelCount, (double) postalCodeB2BDeliveryCount / postalCodeDeliveryCount * 100,
                        (double) postalCodeB2BParcelCount / postalCodeParcelCount * 100,
                        postalCodeAverageWeight, postalCodeAverageB2BWeight));
            });

    // Logging overall summary
    logBuilder.append("=== Overall Summary ===\n");
    logBuilder.append(String.format("  Total Deliveries      : %,d\n", totalDeliveries.get()));
    logBuilder.append(String.format("  Total B2B Deliveries  : %,d\n", totalB2BDeliveries.get()));
    logBuilder.append(String.format("  Total Parcels         : %,d\n", totalParcels.get()));
    logBuilder.append(String.format("  Total B2B Parcels     : %,d\n", totalB2BParcels.get()));
    logBuilder.append(String.format("  B2B Delivery Ratio    : %.2f%%\n",
            (double) totalB2BDeliveries.get() / totalDeliveries.get() * 100));
    logBuilder.append(String.format("  B2B Parcel Ratio      : %.2f%%\n",
            (double) totalB2BParcels.get() / totalParcels.get() * 100));
    logBuilder.append(String.format("  Average Weight        : %.2f\n", (double) totalWeight.get() / totalParcels.get()));
    logBuilder.append(String.format("  Average B2B Weight    : %.2f\n", totalB2BParcels.get() == 0 ? 0 : (double) totalB2BWeight.get() / totalB2BParcels.get()));

    // Output the log message
    LOGGER.info(logBuilder.toString());
}

}
