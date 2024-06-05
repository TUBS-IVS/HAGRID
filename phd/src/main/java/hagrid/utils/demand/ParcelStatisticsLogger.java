package hagrid.utils.demand;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * The ParcelStatisticsLogger class is responsible for logging detailed statistics
 * about the generated deliveries, both by provider and by postal code.
 *
 * This class aggregates and logs information such as the total number of deliveries,
 * B2B deliveries, total parcels, and B2B parcels for each provider and each postal code.
 * It also calculates and logs the ratios of B2B deliveries and parcels to the totals,
 * as well as the average weight per provider and postal code.
 *
 * Depending on the configuration, it can either log detailed statistics or just an overall summary.
 */
public class ParcelStatisticsLogger {

    private static final Logger LOGGER = LogManager.getLogger(ParcelStatisticsLogger.class);
    private boolean detailedLog;

    /**
     * Constructor for ParcelStatisticsLogger.
     *
     * @param detailedLog Boolean flag indicating whether to log detailed statistics.
     */
    public ParcelStatisticsLogger(boolean detailedLog) {
        this.detailedLog = detailedLog;
    }

    /**
     * Logs detailed statistics about the generated deliveries, both by provider and
     * by postal code.
     *
     * This method aggregates and logs information such as the total number of
     * deliveries, B2B deliveries, total parcels, and B2B parcels for each provider
     * and each postal code. It also calculates and logs the ratios of B2B
     * deliveries and parcels to the totals, as well as the average weight per provider
     * and postal code.
     *
     * @param deliveries Map of carrier demands with Delivery objects.
     */
    public void logStatistics(Map<String, ArrayList<Delivery>> deliveries) {
        AtomicLong totalDeliveries = new AtomicLong();
        AtomicLong totalB2BDeliveries = new AtomicLong();
        AtomicLong totalParcels = new AtomicLong();
        AtomicLong totalB2BParcels = new AtomicLong();
        AtomicLong totalWeight = new AtomicLong();
        AtomicLong totalB2BWeight = new AtomicLong();
        AtomicLong totalLockerDeliveries = new AtomicLong();
        AtomicLong totalLockerParcels = new AtomicLong();

        Map<String, Long> providerDeliveryCounts = new HashMap<>();
        Map<String, Long> providerParcelCounts = new HashMap<>();
        Map<String, Long> postalCodeProviderDeliveryCounts = new HashMap<>();
        Map<String, Long> postalCodeProviderParcelCounts = new HashMap<>();

        StringBuilder logBuilder = new StringBuilder();

        deliveries.values().stream()
                .flatMap(List::stream)
                .forEach(delivery -> {
                    totalDeliveries.incrementAndGet();
                    if (Delivery.ParcelType.B2B.equals(delivery.getParcelType())) {
                        totalB2BDeliveries.incrementAndGet();
                    }
                    totalParcels.addAndGet(delivery.getAmount());
                    if (Delivery.ParcelType.B2B.equals(delivery.getParcelType())) {
                        totalB2BParcels.addAndGet(delivery.getAmount());
                    }
                    totalWeight.addAndGet((long) delivery.getIndividualWeights().stream().mapToDouble(Double::doubleValue).sum());
                    if (Delivery.ParcelType.B2B.equals(delivery.getParcelType())) {
                        totalB2BWeight.addAndGet((long) delivery.getIndividualWeights().stream().mapToDouble(Double::doubleValue).sum());
                    }
                    if (Delivery.DeliveryMode.PARCEL_LOCKER.equals(delivery.getDeliveryMode()) ||
                            Delivery.DeliveryMode.PARCEL_LOCKER_EXISTING.equals(delivery.getDeliveryMode())) {
                        totalLockerDeliveries.incrementAndGet();
                        totalLockerParcels.addAndGet(delivery.getAmount());
                    }
                });

        if (detailedLog) {
            // Logging statistics by provider
            logBuilder.append("=== Delivery Statistics by Provider ===\n");

            deliveries.values().stream()
                    .flatMap(List::stream)
                    .collect(Collectors.groupingBy(Delivery::getProvider))
                    .forEach((provider, deliveryList) -> {
                        long providerDeliveryCount = deliveryList.size();
                        long providerB2BDeliveryCount = deliveryList.stream()
                                .filter(delivery -> Delivery.ParcelType.B2B.equals(delivery.getParcelType()))
                                .count();
                        long providerParcelCount = deliveryList.stream()
                                .mapToLong(Delivery::getAmount)
                                .sum();
                        long providerB2BParcelCount = deliveryList.stream()
                                .filter(delivery -> Delivery.ParcelType.B2B.equals(delivery.getParcelType()))
                                .mapToLong(Delivery::getAmount)
                                .sum();

                        double providerTotalWeight = deliveryList.stream()
                                .flatMapToDouble(delivery -> delivery.getIndividualWeights()
                                        .stream().mapToDouble(Double::doubleValue))
                                .sum();
                        double providerB2BWeight = deliveryList.stream()
                                .filter(delivery -> Delivery.ParcelType.B2B.equals(delivery.getParcelType()))
                                .flatMapToDouble(delivery -> delivery.getIndividualWeights()
                                        .stream().mapToDouble(Double::doubleValue))
                                .sum();
                        double providerAverageWeight = providerParcelCount == 0 ? 0 : providerTotalWeight / providerParcelCount;
                        double providerAverageB2BWeight = providerB2BParcelCount == 0 ? 0
                                : providerB2BWeight / providerB2BParcelCount;

                        providerDeliveryCounts.merge(provider, providerDeliveryCount, Long::sum);
                        providerParcelCounts.merge(provider, providerParcelCount, Long::sum);

                        logBuilder.append(String.format(
                                "Provider: %s\n  Total Deliveries     : %,d\n  B2B Deliveries       : %,d\n  Total Parcels        : %,d\n  B2B Parcels          : %,d\n  B2B Delivery Ratio   : %.2f%%\n  B2B Parcel Ratio     : %.2f%%\n  Average Weight       : %.2f\n  Average B2B Weight   : %.2f\n\n",
                                provider, providerDeliveryCount, providerB2BDeliveryCount,
                                providerParcelCount, providerB2BParcelCount,
                                (double) providerB2BDeliveryCount / providerDeliveryCount * 100,
                                (double) providerB2BParcelCount / providerParcelCount * 100,
                                providerAverageWeight, providerAverageB2BWeight));
                    });

            // Logging summary by postal code
            logBuilder.append("=== Summary by Postal Code ===\n");

            deliveries.values().stream()
                    .flatMap(List::stream)
                    .collect(Collectors.groupingBy(Delivery::getPostalCode))
                    .forEach((postalCode, deliveryList) -> {
                        long postalCodeDeliveryCount = deliveryList.size();
                        long postalCodeB2BDeliveryCount = deliveryList.stream()
                                .filter(delivery -> Delivery.ParcelType.B2B.equals(delivery.getParcelType()))
                                .count();
                        long postalCodeParcelCount = deliveryList.stream()
                                .mapToLong(Delivery::getAmount)
                                .sum();
                        long postalCodeB2BParcelCount = deliveryList.stream()
                                .filter(delivery -> Delivery.ParcelType.B2B.equals(delivery.getParcelType()))
                                .mapToLong(Delivery::getAmount)
                                .sum();

                        double postalCodeTotalWeight = deliveryList.stream()
                                .flatMapToDouble(delivery -> delivery.getIndividualWeights()
                                        .stream().mapToDouble(Double::doubleValue))
                                .sum();
                        double postalCodeB2BWeight = deliveryList.stream()
                                .filter(delivery -> Delivery.ParcelType.B2B.equals(delivery.getParcelType()))
                                .flatMapToDouble(delivery -> delivery.getIndividualWeights()
                                        .stream().mapToDouble(Double::doubleValue))
                                .sum();
                        double postalCodeAverageWeight = postalCodeParcelCount == 0 ? 0 : postalCodeTotalWeight / postalCodeParcelCount;
                        double postalCodeAverageB2BWeight = postalCodeB2BParcelCount == 0 ? 0
                                : postalCodeB2BWeight / postalCodeB2BParcelCount;

                        deliveryList.forEach(delivery -> {
                            String provider = delivery.getProvider();
                            String key = postalCode + "_" + provider;
                            postalCodeProviderDeliveryCounts.merge(key, 1L, Long::sum);
                            postalCodeProviderParcelCounts.merge(key, (long) delivery.getAmount(), Long::sum);
                        });

                        logBuilder.append(String.format(
                                "Postal Code: %s\n  Total Deliveries     : %,d\n  B2B Deliveries       : %,d\n  Total Parcels        : %,d\n  B2B Parcels          : %,d\n  B2B Delivery Ratio   : %.2f%%\n  B2B Parcel Ratio     : %.2f%%\n  Average Weight       : %.2f\n  Average B2B Weight   : %.2f\n",
                                postalCode, postalCodeDeliveryCount, postalCodeB2BDeliveryCount,
                                postalCodeParcelCount,
                                postalCodeB2BParcelCount,
                                (double) postalCodeB2BDeliveryCount / postalCodeDeliveryCount * 100,
                                (double) postalCodeB2BParcelCount / postalCodeParcelCount * 100,
                                postalCodeAverageWeight, postalCodeAverageB2BWeight));
                        logBuilder.append("\n");
                    });

            logBuilder.append("=== Global Provider Proportions ===\n");
            providerDeliveryCounts.forEach((provider, count) -> {
                double deliveryProportion = (double) count / totalDeliveries.get() * 100;
                double parcelProportion = (double) providerParcelCounts.get(provider) / totalParcels.get() * 100;
                logBuilder.append(String.format(
                        "Provider: %s\n  Delivery Proportion: %.2f%% | Parcel Proportion: %.2f%%\n",
                        provider, deliveryProportion, parcelProportion));
            });
        }

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
        logBuilder.append(String.format("  Average Weight        : %.2f\n",
                (double) totalWeight.get() / totalParcels.get()));
        logBuilder.append(String.format("  Average B2B Weight    : %.2f\n", totalB2BParcels.get() == 0 ? 0
                : (double) totalB2BWeight.get() / totalB2BParcels.get()));
        logBuilder.append(String.format("  Total Locker Deliveries: %,d\n", totalLockerDeliveries.get()));
        logBuilder.append(String.format("  Total Locker Parcels: %,d\n", totalLockerParcels.get()));
        logBuilder.append(String.format("  Locker Delivery Ratio : %.2f%%\n",
                (double) totalLockerDeliveries.get() / totalDeliveries.get() * 100));
        logBuilder.append(String.format("  Locker Parcel Ratio : %.2f%%\n",
                (double) totalLockerParcels.get() / totalParcels.get() * 100));

        LOGGER.info(logBuilder.toString());
    }
}
