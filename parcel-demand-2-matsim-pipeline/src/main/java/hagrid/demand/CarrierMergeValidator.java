package hagrid.demand;

import java.util.List;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierPlanWriter;
import org.matsim.freight.carriers.CarrierService;
import org.matsim.freight.carriers.Carriers;

import com.google.inject.Inject;

import hagrid.HagridConfigGroup;
import hagrid.utils.demand.Delivery.ParcelType;
import hagrid.utils.general.HAGRIDUtils;

/**
 * Validates the integrity of carrier services before and after merging.
 * Ensures that capacityDemand, B2B and B2C parcel counts are preserved.
 * Also logs stats before and after merging.
 */
public class CarrierMergeValidator {

    private static final Logger LOGGER = LogManager.getLogger(CarrierMergeValidator.class);

    /**
     * Captures stats before merging services.
     */
    public static CarrierMergeStats capturePreMergeStats(Carriers carriers) {
        int totalServices = 0;
        int totalB2B = 0;
        int totalB2C = 0;
        int totalCapacity = 0;

        for (Carrier carrier : carriers.getCarriers().values()) {
            for (CarrierService service : carrier.getServices().values()) {
                totalServices++;
                int cap = service.getCapacityDemand();
                totalCapacity += cap;

                ParcelType typeObj = (ParcelType) service.getAttributes().getAttribute("type");
                if (typeObj instanceof ParcelType type) {
                    if (type == ParcelType.B2B) {
                        totalB2B += cap;
                    } else if (type == ParcelType.B2C) {
                        totalB2C += cap;
                    } else if (type == ParcelType.MIXED) {
                        // Mixed-Service: B2B & B2C getrennt zählen
                        Object b2bAttr = service.getAttributes().getAttribute("b2b");
                        Object b2cAttr = service.getAttributes().getAttribute("b2c");

                        int b2b = (b2bAttr instanceof Integer) ? (Integer) b2bAttr : 0;
                        int b2c = (b2cAttr instanceof Integer) ? (Integer) b2cAttr : 0;

                        totalB2B += b2b;
                        totalB2C += b2c;
                    }
                }
            }
        }

        LOGGER.info("BEFORE MERGE: totalServices = {}, b2bParcels = {}, b2cParcels = {}, capacityDemand = {}",
                totalServices, totalB2B, totalB2C, totalCapacity);

        logCarrierServiceStats(carriers);

        return new CarrierMergeStats(totalServices, totalB2B, totalB2C, totalCapacity);
    }

    /**
     * Validates the post-merge stats and asserts consistency.
     */
    public static void validatePostMerge(Carriers carriers, CarrierMergeStats preStats, HagridConfigGroup hagridConfig) {
        int totalServices = 0;
        int totalB2B = 0;
        int totalB2C = 0;
        int totalCapacity = 0;

        for (Carrier carrier : carriers.getCarriers().values()) {
            for (CarrierService service : carrier.getServices().values()) {
                totalServices++;
                int cap = service.getCapacityDemand();
                totalCapacity += cap;

                Object b2bObj = service.getAttributes().getAttribute("b2b");
                Object b2cObj = service.getAttributes().getAttribute("b2c");

                int b2b = parseIntSafe(b2bObj);
                int b2c = parseIntSafe(b2cObj);

                if (b2bObj != null && b2cObj != null) {
                    totalB2B += b2b;
                    totalB2C += b2c;
                } else {
                    // If b2b or b2c is null, we need to determine the type of service
                    ParcelType type = (ParcelType) service.getAttributes().getAttribute("type");

                    if (type == null) {
                        LOGGER.warn("ParcelType missing for service {} – skipping capacity breakdown!",
                                service.getId());
                        continue;
                    }

                    switch (type) {
                        case B2B -> {
                            totalB2B += cap;
                            b2b = cap;
                            b2c = 0;
                        }
                        case B2C -> {
                            totalB2C += cap;
                            b2b = 0;
                            b2c = cap;
                        }
                        case MIXED -> {
                            throw new IllegalStateException(
                                    "Mixed ParcelType but no b2b/b2c attributes for service " + service.getId());
                        }
                        case C2C -> throw new UnsupportedOperationException("Unimplemented case: " + type);
                        case WHITE_LABEL -> throw new UnsupportedOperationException("Unimplemented case: " + type);
                        default -> throw new IllegalArgumentException("Unexpected value: " + type);
                    }

                    // Set b2b and b2c attributes for future reference
                    service.getAttributes().putAttribute("b2b", b2b);
                    service.getAttributes().putAttribute("b2c", b2c);
                }
            }
        }

        LOGGER.info("AFTER MERGE: totalServices = {}, b2bParcels = {}, b2cParcels = {}, capacityDemand = {}",
                totalServices, totalB2B, totalB2C, totalCapacity);

        logCarrierServiceStats(carriers);

        assertEqual(preStats.totalCapacity(), totalCapacity, "Capacity demand mismatch after merging!");
        assertEqual(preStats.totalB2B(), totalB2B, "B2B parcel count mismatch after merging!");
        assertEqual(preStats.totalB2C(), totalB2C, "B2C parcel count mismatch after merging!");

        LOGGER.info("Merge validation passed: all parcel counts and capacity are consistent.");

    String baseDir = System.getProperty("user.dir");
    String outputDir = baseDir + java.io.File.separator + "parcel-demand-2-matsim-pipeline" + java.io.File.separator + "output" + java.io.File.separator + hagridConfig.getRunId() + java.io.File.separator;
    HAGRIDUtils.createDirectoryIfNotExists(outputDir);

    String outputPath = outputDir + hagridConfig.getRunId() + "_delivery_carriers_merged_services.xml";
    new CarrierPlanWriter(carriers).write(outputPath);
    }

    private static int parseIntSafe(Object value) {
        if (value instanceof Integer)
            return (int) value;
        if (value instanceof String)
            return Integer.parseInt((String) value);
        if (value instanceof Double)
            return ((Double) value).intValue();
        return 0;
    }

    private static void assertEqual(int expected, int actual, String message) {
        if (expected != actual) {
            throw new IllegalStateException(message + " Expected: " + expected + ", but got: " + actual);
        }
    }

    /**
     * Simple DTO for merge stats.
     */
    public record CarrierMergeStats(int totalServices, int totalB2B, int totalB2C, int totalCapacity) {
    }

    /**
     * Computes and logs statistical metrics regarding the number of services per
     * carrier.
     * This includes min, max, average, median, and interquartile range (25% / 75%
     * percentiles).
     * Useful for analyzing the distribution of services and spotting irregularities
     * across carriers.
     *
     * @param carriers the full set of carriers to analyze
     */
    private static void logCarrierServiceStats(Carriers carriers) {
        List<Integer> serviceCounts = carriers.getCarriers().values().stream()
                .map(c -> c.getServices().size())
                .sorted()
                .collect(Collectors.toList());

        if (serviceCounts.isEmpty()) {
            LOGGER.warn("No services found for any carrier. Skipping service distribution stats.");
            return;
        }

        int min = serviceCounts.get(0);
        int max = serviceCounts.get(serviceCounts.size() - 1);
        int avg = (int) serviceCounts.stream().mapToInt(i -> i).average().orElse(0.0);
        int median = percentile(serviceCounts, 50);
        int q25 = percentile(serviceCounts, 25);
        int q75 = percentile(serviceCounts, 75);

        LOGGER.info("Service Distribution Stats Across Carriers:");
        LOGGER.info(" Total carriers         : {}", serviceCounts.size());
        LOGGER.info(" Min services per carrier : {}", min);
        LOGGER.info(" Max services per carrier : {}", max);
        LOGGER.info(" Average (mean)          : {}", avg);
        LOGGER.info(" Median (50%)            : {}", median);
        LOGGER.info(" 25th percentile (Q1)    : {}", q25);
        LOGGER.info(" 75th percentile (Q3)    : {}", q75);
    }

    /**
     * Utility method to compute percentiles from a sorted integer list.
     * 
     * @param sortedList a sorted list of integers
     * @param percentile the percentile to calculate (0-100)
     * @return the percentile value
     */
    private static int percentile(List<Integer> sortedList, int percentile) {
        if (sortedList.isEmpty())
            return 0;

        double index = percentile / 100.0 * (sortedList.size() - 1);
        int lower = (int) Math.floor(index);
        int upper = (int) Math.ceil(index);

        if (lower == upper)
            return sortedList.get(lower);
        double weight = index - lower;
        return (int) Math.round(sortedList.get(lower) * (1 - weight) + sortedList.get(upper) * weight);
    }

}
