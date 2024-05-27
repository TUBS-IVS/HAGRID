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
import java.util.stream.Collectors;

/**
 * The ParcelGenerator class is responsible for converting sorted carrier demand
 * into Parcel objects and validating the totals.
 */
@Singleton
public class ParcelGenerator implements Runnable {

    private static final Logger LOGGER = LogManager.getLogger(ParcelGenerator.class);

    @Inject
    private Scenario scenario;

    @Inject
    private HagridConfigGroup hagridConfig;

    private Map<String, String> providerShapeMapping;

    @Override
    public void run() {
        try {
            LOGGER.info("Generating parcels from sorted carrier demand...");
            Map<String, List<SimpleFeature>> carrierDemand = (Map<String, List<SimpleFeature>>) scenario.getScenarioElement("carrierDemand");

            if (carrierDemand == null) {
                throw new IllegalStateException("Carrier demand data is missing in the scenario.");
            }

            // Initialize provider shape mapping dynamically
            providerShapeMapping = generateProviderShapeMapping();

            long totalParcels = calculateTotalParcels(carrierDemand);
            LOGGER.info("Total Parcels from carrier demand: {}", totalParcels);

            Map<String, ArrayList<Parcel>> parcels = processCarrierDemand(carrierDemand, totalParcels);
            LOGGER.info("Parcel generation completed.");

            // Store parcels in scenario
            scenario.addScenarioElement("parcels", parcels);

        } catch (Exception e) {
            LOGGER.error("Error generating parcels", e);
        }
    }

    /**
     * Generates the provider shape mapping dynamically from the HagridConfigGroup.
     *
     * @return A map of provider names to their corresponding shapefile attributes.
     */
    private Map<String, String> generateProviderShapeMapping() {
        return hagridConfig.getShpProviders().stream()
                .collect(Collectors.toMap(
                        shpType -> shpType.split("_")[0], // Extract provider name
                        shpType -> shpType // Use the full shapefile attribute
                ));
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
     * Converts carrier demand from SimpleFeature to Parcel objects and validates the totals.
     *
     * @param carrierDemand Map of carrier demands with SimpleFeatures.
     * @param totalParcels  Expected total number of parcels.
     * @return Map of carrier demands with Parcel objects.
     */
    public Map<String, ArrayList<Parcel>> processCarrierDemand(Map<String, List<SimpleFeature>> carrierDemand, long totalParcels) {

        // Check the total parcels before conversion
        long totalParcelsBefore = getTotalParcelsFromFeatures(carrierDemand);
        if (totalParcels != totalParcelsBefore) {
            throw new IllegalStateException("Total parcels before conversion do not match expected total parcels.");
        }

        // Convert the demand from SimpleFeature to Parcel objects
        Map<String, ArrayList<Parcel>> carrierDemandWithParcels = convertDemandFromShapeToParcels(carrierDemand);

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
    private long getTotalParcelsFromParcelObjects(Map<String, ArrayList<Parcel>> demandWithParcels) {
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
    private Map<String, ArrayList<Parcel>> convertDemandFromShapeToParcels(Map<String, List<SimpleFeature>> carrierDemand) {
        return carrierDemand.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,  // Preserve the key (providerPLZ)
                        entry -> {  // Transform the value
                            String provider = entry.getKey().split("_")[0];
                            return entry.getValue().stream()  // Stream the SimpleFeature list
                                    .map(simpleFeature -> createParcel(simpleFeature, provider))  // Convert each SimpleFeature to Parcel
                                    .collect(Collectors.toCollection(ArrayList::new));  // Collect to ArrayList<Parcel>
                        }
                ));
    }

    /**
     * Creates a Parcel object from a SimpleFeature.
     *
     * @param feature  SimpleFeature object.
     * @param provider Provider name.
     * @return Parcel object.
     */
    private Parcel createParcel(SimpleFeature feature, String provider) {
        Point point = ((MultiPoint) feature.getAttribute(0)).getCentroid();
        Coord coord = new Coord(point.getX(), point.getY());

        String deliveryPointId = String.valueOf((Long) feature.getAttribute("id"));
        Long amount = (Long) feature.getAttribute(provider + "_tag");

        String b2bInfo = getB2BInformation(feature, provider);

        return new Parcel.Builder(deliveryPointId + "_" + deliveryPointId, coord)
                .withProvider(provider)
                .withAmount(amount.intValue())
                .withParcelType(b2bInfo)
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
            Object attribute = feature.getAttribute(nameInShape);
            if (attribute == null) {
                throw new IllegalArgumentException("No attribute found for provider: " + provider);
            }
            // Convert attribute to string if it's not already
            return attribute.toString();
        }
    }
}

