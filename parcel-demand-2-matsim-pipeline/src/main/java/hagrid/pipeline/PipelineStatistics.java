package hagrid.pipeline;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Scenario;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierService;
import org.matsim.freight.carriers.Carriers;

import hagrid.utils.demand.Delivery.ParcelType;
import hagrid.utils.general.HAGRIDSummary;

/**
 * Lightweight snapshot of pipeline statistics collected after execution.
 * <p>
 * Extracted once from the MATSim {@link Scenario} and its scenario elements,
 * then passed to {@link ScenarioSummaryWriter} for human-readable reporting.
 * This avoids leaking the heavy {@code Scenario} object into the summary layer.
 *
 * @author HAGRID Team
 */
public final class PipelineStatistics {

    private static final Logger LOGGER = LogManager.getLogger(PipelineStatistics.class);

    // ── Demand overview ─────────────────────────────────────────────────────
    private final int totalDeliveryStops;
    private final int totalParcels;
    private final int totalB2BParcels;
    private final int totalB2CParcels;
    private final double b2bParcelRatio;
    private final double averageWeight;
    private final int totalLockerDeliveries;
    private final int totalLockerParcels;

    // ── Demand by provider (provider → parcels) ─────────────────────────────
    private final Map<String, ProviderStats> providerStats;

    // ── Carrier splitting (KMeans) ──────────────────────────────────────────
    private final int carrierKeysBeforeSplit;
    private final int carrierKeysAfterSplit;
    private final Map<String, SplitInfo> splitDetails;  // originalKey → info

    // ── Carrier merging (small → large) ─────────────────────────────────────
    private final int carriersBeforeMerge;
    private final int carriersAfterMerge;
    private final int carriersBelowThreshold;
    private final Map<String, ProviderMergeStats> providerMergeStats;

    // ── Service merging (same-stop consolidation) ───────────────────────────
    private final int servicesBeforeMerge;
    private final int servicesAfterMerge;
    private final int totalCapacity;

    // ── Final carrier statistics ─────────────────────────────────────────────
    private final int finalCarrierCount;
    private final int finalServiceCount;
    private final int minServicesPerCarrier;
    private final int maxServicesPerCarrier;
    private final double avgServicesPerCarrier;

    // ── Carrier merge log (from CarrierGenerator) ───────────────────────────
    private final CarrierMergeLog carrierMergeLog;

    // =====================================================================
    //  DTOs
    // =====================================================================

    /** Per-provider demand statistics. */
    public record ProviderStats(
            int carrierCount,
            int serviceCount,
            int totalParcels,
            int b2bParcels,
            int b2cParcels
    ) {}

    /** Info about a single KMeans split. */
    public record SplitInfo(
            String originalKey,
            int deliveriesBeforeSplit,
            int splitInto
    ) {}

    /** Per-provider carrier merge statistics. */
    public record ProviderMergeStats(
            int carriersBefore,
            int carriersAfter,
            int carriersMerged
    ) {}

    // =====================================================================
    //  Factory
    // =====================================================================

    /**
     * Collects all pipeline statistics from the scenario after pipeline execution.
     *
     * @param scenario the MATSim scenario containing all scenario elements
     * @return a fully populated {@code PipelineStatistics} snapshot
     */
    @SuppressWarnings("unchecked")
    public static PipelineStatistics collectFrom(Scenario scenario) {
        LOGGER.info("Collecting pipeline statistics for summary...");

        // ── HAGRIDSummary (demand overview) ──────────────────────────────
        HAGRIDSummary summary = null;
        try {
            summary = (HAGRIDSummary) scenario.getScenarioElement("summary");
        } catch (Exception e) {
            LOGGER.warn("Could not read HAGRIDSummary from scenario: {}", e.getMessage());
        }

        // ── Carriers (final state after merging) ─────────────────────────
        Carriers carriers = null;
        try {
            carriers = (Carriers) scenario.getScenarioElement("carriers");
        } catch (Exception e) {
            LOGGER.warn("Could not read Carriers from scenario: {}", e.getMessage());
        }

        // ── Carrier demand map (after KMeans splitting) ──────────────────
        Map<String, ?> carrierDemand = null;
        try {
            carrierDemand = (Map<String, ?>) scenario.getScenarioElement("carrierDemand");
        } catch (Exception e) {
            LOGGER.warn("Could not read carrierDemand from scenario: {}", e.getMessage());
        }

        // ── Carrier merge log ────────────────────────────────────────────
        CarrierMergeLog mergeLog = null;
        try {
            mergeLog = (CarrierMergeLog) scenario.getScenarioElement("carrierMergeLog");
        } catch (Exception e) {
            LOGGER.warn("Could not read CarrierMergeLog from scenario: {}", e.getMessage());
        }

        return new PipelineStatistics(summary, carriers, carrierDemand, mergeLog);
    }

    // =====================================================================
    //  Private constructor — extracts everything
    // =====================================================================

    private PipelineStatistics(HAGRIDSummary summary, Carriers carriers,
                               Map<String, ?> carrierDemand, CarrierMergeLog mergeLog) {

        // ── Demand overview from HAGRIDSummary ───────────────────────────
        if (summary != null) {
            this.totalDeliveryStops = summary.getTotalDeliveries();
            this.totalParcels = summary.getTotalParcels();
            this.totalB2BParcels = summary.getTotalB2BParcels();
            this.totalB2CParcels = summary.getTotalParcels() - summary.getTotalB2BParcels();
            this.b2bParcelRatio = summary.getB2bParcelRatio();
            this.averageWeight = summary.getAverageWeight();
            this.totalLockerDeliveries = summary.getTotalLockerDeliveries();
            this.totalLockerParcels = summary.getTotalLockerParcels();
        } else {
            this.totalDeliveryStops = -1;
            this.totalParcels = -1;
            this.totalB2BParcels = -1;
            this.totalB2CParcels = -1;
            this.b2bParcelRatio = -1;
            this.averageWeight = -1;
            this.totalLockerDeliveries = -1;
            this.totalLockerParcels = -1;
        }

        // ── Provider stats + carrier merge stats from Carriers object ────
        Map<String, ProviderStats> provStats = new TreeMap<>();
        Map<String, ProviderMergeStats> provMerge = new TreeMap<>();
        int svcBeforeMerge = 0;
        int svcAfterMerge = 0;
        int totalCap = 0;
        int finalCarriers = 0;
        int finalServices = 0;
        int minSvc = Integer.MAX_VALUE;
        int maxSvc = 0;
        int belowThreshold = 0;

        if (carriers != null) {
            // Group carriers by provider
            Map<String, List<Carrier>> byProvider = carriers.getCarriers().values().stream()
                    .collect(Collectors.groupingBy(c -> {
                        Object prov = c.getAttributes().getAttribute("provider");
                        return prov != null ? prov.toString() : "unknown";
                    }, TreeMap::new, Collectors.toList()));

            for (var entry : byProvider.entrySet()) {
                String provider = entry.getKey();
                List<Carrier> provCarriers = entry.getValue();

                int pCarriers = provCarriers.size();
                int pServices = 0;
                int pParcels = 0;
                int pB2B = 0;
                int pB2C = 0;

                for (Carrier c : provCarriers) {
                    int cServices = c.getServices().size();
                    pServices += cServices;

                    for (CarrierService svc : c.getServices().values()) {
                        int cap = svc.getCapacityDemand();
                        pParcels += cap;

                        Object b2bObj = svc.getAttributes().getAttribute("b2b");
                        Object b2cObj = svc.getAttributes().getAttribute("b2c");
                        if (b2bObj != null && b2cObj != null) {
                            pB2B += parseIntSafe(b2bObj);
                            pB2C += parseIntSafe(b2cObj);
                        } else {
                            ParcelType type = (ParcelType) svc.getAttributes().getAttribute("type");
                            if (type == ParcelType.B2B) pB2B += cap;
                            else if (type == ParcelType.B2C) pB2C += cap;
                        }
                    }
                }

                provStats.put(provider, new ProviderStats(pCarriers, pServices, pParcels, pB2B, pB2C));
            }

            // Final carrier/service stats
            for (Carrier c : carriers.getCarriers().values()) {
                finalCarriers++;
                int svcCount = c.getServices().size();
                finalServices += svcCount;
                minSvc = Math.min(minSvc, svcCount);
                maxSvc = Math.max(maxSvc, svcCount);

                for (CarrierService svc : c.getServices().values()) {
                    totalCap += svc.getCapacityDemand();
                }
            }

            svcAfterMerge = finalServices;
        }

        if (finalCarriers == 0) minSvc = 0;

        this.providerStats = Collections.unmodifiableMap(provStats);
        this.providerMergeStats = Collections.unmodifiableMap(provMerge);
        this.servicesBeforeMerge = svcBeforeMerge; // will be set from pre-stats if available
        this.servicesAfterMerge = svcAfterMerge;
        this.totalCapacity = totalCap;
        this.finalCarrierCount = finalCarriers;
        this.finalServiceCount = finalServices;
        this.minServicesPerCarrier = minSvc;
        this.maxServicesPerCarrier = maxSvc;
        this.avgServicesPerCarrier = finalCarriers > 0
                ? (double) finalServices / finalCarriers : 0.0;

        // ── Carrier demand (KMeans split info) ───────────────────────────
        // Keys are like "dhl_30159" or "dhl_30159_0" (after split)
        // A key with _N suffix where N is a digit indicates it was split
        Map<String, SplitInfo> splits = new LinkedHashMap<>();
        int keysBeforeSplit = 0;
        int keysAfterSplit = 0;

        if (carrierDemand != null) {
            keysAfterSplit = carrierDemand.size();

            // Group by base key (without trailing _N)
            Map<String, List<String>> grouped = carrierDemand.keySet().stream()
                    .collect(Collectors.groupingBy(PipelineStatistics::baseKey));

            keysBeforeSplit = grouped.size();

            for (var entry : grouped.entrySet()) {
                List<String> subKeys = entry.getValue();
                if (subKeys.size() > 1) {
                    // This was split
                    int totalDeliveries = subKeys.stream()
                            .mapToInt(k -> {
                                Object val = carrierDemand.get(k);
                                if (val instanceof List<?> list) return list.size();
                                return 0;
                            })
                            .sum();
                    splits.put(entry.getKey(), new SplitInfo(entry.getKey(), totalDeliveries, subKeys.size()));
                }
            }
        }

        this.carrierKeysBeforeSplit = keysBeforeSplit;
        this.carrierKeysAfterSplit = keysAfterSplit;
        this.splitDetails = Collections.unmodifiableMap(splits);

        // Carrier merge info — use actual merge log if available
        if (mergeLog != null) {
            this.carriersBeforeMerge = mergeLog.getCarriersBeforeMerge();
            this.carriersAfterMerge = mergeLog.getCarriersAfterMerge();
            this.carriersBelowThreshold = mergeLog.getCarriersBelowThreshold();
        } else {
            this.carriersBeforeMerge = keysAfterSplit;
            this.carriersAfterMerge = finalCarriers;
            this.carriersBelowThreshold = belowThreshold;
        }

        this.carrierMergeLog = mergeLog;

        LOGGER.info("Pipeline statistics collected: {} carriers, {} services, {} parcels",
                finalCarriers, finalServices, totalCap);
    }

    /**
     * Extracts the base key by removing a trailing _N digit suffix.
     * "dhl_30159_0" → "dhl_30159", "dhl_30159" → "dhl_30159"
     */
    static String baseKey(String key) {
        // Pattern: last segment after _ is purely numeric → it's a split suffix
        int lastUnderscore = key.lastIndexOf('_');
        if (lastUnderscore > 0) {
            String suffix = key.substring(lastUnderscore + 1);
            if (suffix.matches("\\d+") && !isPostalCode(suffix)) {
                return key.substring(0, lastUnderscore);
            }
        }
        return key;
    }

    /** PLZ (German postal codes) are 5-digit strings. Split indices are typically 0-9. */
    private static boolean isPostalCode(String s) {
        return s.length() == 5 && s.chars().allMatch(Character::isDigit);
    }

    private static int parseIntSafe(Object value) {
        if (value instanceof Integer i) return i;
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
        }
        return 0;
    }

    // =====================================================================
    //  Getters
    // =====================================================================

    public int getTotalDeliveryStops() { return totalDeliveryStops; }
    public int getTotalParcels() { return totalParcels; }
    public int getTotalB2BParcels() { return totalB2BParcels; }
    public int getTotalB2CParcels() { return totalB2CParcels; }
    public double getB2bParcelRatio() { return b2bParcelRatio; }
    public double getAverageWeight() { return averageWeight; }
    public int getTotalLockerDeliveries() { return totalLockerDeliveries; }
    public int getTotalLockerParcels() { return totalLockerParcels; }

    public Map<String, ProviderStats> getProviderStats() { return providerStats; }

    public int getCarrierKeysBeforeSplit() { return carrierKeysBeforeSplit; }
    public int getCarrierKeysAfterSplit() { return carrierKeysAfterSplit; }
    public Map<String, SplitInfo> getSplitDetails() { return splitDetails; }

    public int getCarriersBeforeMerge() { return carriersBeforeMerge; }
    public int getCarriersAfterMerge() { return carriersAfterMerge; }
    public int getCarriersBelowThreshold() { return carriersBelowThreshold; }
    public Map<String, ProviderMergeStats> getProviderMergeStats() { return providerMergeStats; }

    public int getServicesBeforeMerge() { return servicesBeforeMerge; }
    public int getServicesAfterMerge() { return servicesAfterMerge; }
    public int getTotalCapacity() { return totalCapacity; }

    public int getFinalCarrierCount() { return finalCarrierCount; }
    public int getFinalServiceCount() { return finalServiceCount; }
    public int getMinServicesPerCarrier() { return minServicesPerCarrier; }
    public int getMaxServicesPerCarrier() { return maxServicesPerCarrier; }
    public double getAvgServicesPerCarrier() { return avgServicesPerCarrier; }

    public CarrierMergeLog getCarrierMergeLog() { return carrierMergeLog; }
}
