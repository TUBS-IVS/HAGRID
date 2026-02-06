package hagrid.demand;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import hagrid.HagridConfig;
import hagrid.pipeline.CarrierMergeLog;
import hagrid.utils.GeoUtils;
import hagrid.utils.demand.Delivery;
import hagrid.utils.demand.Hub;
import hagrid.utils.demand.Delivery.ParcelType;
import hagrid.utils.general.HAGRIDSummary;
import hagrid.utils.general.HAGRIDUtils;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.geotools.api.feature.simple.SimpleFeature;
import org.jxmapviewer.viewer.util.GeoUtil;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.utils.geometry.CoordUtils;
import org.matsim.core.utils.geometry.GeometryUtils;
import org.matsim.freight.carriers.*;
import org.matsim.freight.carriers.CarrierCapabilities.FleetSize;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;

import java.util.Map;
import java.util.Optional;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.ConcurrentModificationException;

/**
 * The CarrierGenerator class is responsible for converting sorted demand
 * into Carrier objects and validating the totals.
 */
// @Singleton
public class CarrierGenerator implements Runnable {

        private static final Logger LOGGER = LogManager.getLogger(CarrierGenerator.class);
        private static Random random = new Random();

        /**
         * Set the global random seed for deterministic behavior (should be called
         * before any random logic).
         * 
         * @param seed the seed to use (e.g. runId.hashCode())
         */
        public static void setGlobalRandomSeed(long seed) {
                random = new Random(seed);
        }

        @Inject
        private Scenario scenario;

        @Inject
        private HagridConfig hagridConfig;

        private CarrierVehicleFactory carrierVehicleFactory;

        /**
         * Executes the carrier generation process. This method retrieves the necessary
         * scenario elements, generates carriers and their services, and validates
         * the generated carriers against the original delivery summary.
         */
        @Override
        public void run() {
                try {
                        // Set deterministic random seed based on runId for full reproducibility
                        String runId = hagridConfig.getRunId();
                        long seed = (runId != null) ? runId.hashCode() : 42L;
                        setGlobalRandomSeed(seed);
                        CarrierVehicleFactory.setGlobalRandomSeed(seed);

                        LOGGER.info("Generating carriers from sorted deliveries and parcels...");

                        // Get scenario elements
                        LOGGER.info("Getting scenario elements...");
                        final Map<String, ArrayList<Delivery>> deliveries = HAGRIDUtils.getScenarioElementAs(
                                        "deliveries",
                                        scenario);
                        final Network subNetwork = HAGRIDUtils.getScenarioElementAs("parcelServiceNetwork", scenario);
                        final CarrierVehicleTypes vehicleTypes = HAGRIDUtils.getScenarioElementAs("carrierVehicleTypes",
                                        scenario);
                        final Map<Id<Hub>, Hub> hubList = HAGRIDUtils.getScenarioElementAs("hubList", scenario);
                        LOGGER.info("Scenario elements retrieved.");

                        // Create an instance of CarrierVehicleFactory with the retrieved vehicle types
                        carrierVehicleFactory = new CarrierVehicleFactory(vehicleTypes);

                        // Process the deliveries to create carriers
                        // minVehicleCapacity: Used for splitting parcels into vehicle-sized portions
                        // carrierMergeThreshold: Fixed threshold for merging small carriers (scenario-independent)
                        // demandBorder (600): Used for KMeans clustering and max services per carrier (routing performance)
                        final int minVehicleCapacity = hagridConfig.getMinVehicleCapacity();
                        final int carrierMergeThreshold = hagridConfig.getCarrierMergeThreshold();
                        final int maxServicesPerCarrier = hagridConfig.getDemandBorder();
                        LOGGER.info("Using min vehicle capacity for parcel split: {}", minVehicleCapacity);
                        LOGGER.info("Using fixed carrier merge threshold: {}", carrierMergeThreshold);
                        LOGGER.info("Using demand border for max services per carrier: {}", maxServicesPerCarrier);
                        
                        final Carriers carriers = generateCarriersAndCarrierServices(deliveries, subNetwork,
                                        vehicleTypes, hubList, carrierMergeThreshold,
                                        maxServicesPerCarrier);

                        // Validate the generated carriers and supply demand
                        validateCarriers(carriers);
                        validateSupplyDemand(carriers, hubList);

                        // Check and log attributes of all carriers
                        HAGRIDUtils.checkAndLogCarrierAttributes(carriers);

                        LOGGER.info("Carrier generation completed.");
                        

                        new CarrierPlanWriter(carriers).write(hagridConfig.io().deliveryCarriersUnrouted());
                        LOGGER.info("Written: {}", hagridConfig.io().deliveryCarriersUnrouted());

                        new CarrierVehicleTypeWriter(vehicleTypes).write(hagridConfig.io().vehicleTypesOutput());
                        LOGGER.info("Written: {}", hagridConfig.io().vehicleTypesOutput());
                        // HAGRIDUtils.convertDemandFromParcelsToShapeFile(carriers,
                        // "phd/output/delivery_carriers.shp");

                        

                } catch (Exception e) {
                        LOGGER.error("Error generating carriers", e);
                }
        }

        /**
         * Initializes the delivery rate map based on the providers listed in the
         * HagridConfig.
         *
         * @return A map containing the delivery rates for each provider.
         */
        private Map<String, Double> initializeDeliveryRate() {
                final Map<String, Double> deliveryRate = new HashMap<>();

                // Get the delivery rates from the HagridConfig
                deliveryRate.put("dhl", (double) hagridConfig.getDeliveryRateDhl());
                deliveryRate.put("hermes", (double) hagridConfig.getDeliveryRateHermes());
                deliveryRate.put("ups", (double) hagridConfig.getDeliveryRateUps());
                deliveryRate.put("amazon", (double) hagridConfig.getDeliveryRateAmazon());
                deliveryRate.put("dpd", (double) hagridConfig.getDeliveryRateDpd());
                deliveryRate.put("gls", (double) hagridConfig.getDeliveryRateGls());
                deliveryRate.put("fedex", (double) hagridConfig.getDeliveryRateFedex());
                deliveryRate.put("wl", (double) hagridConfig.getDeliveryRateWl());

                return deliveryRate;
        }

        /**
         * Generates carriers and their services based on the provided deliveries and
         * network.
         *
         * @param deliveries   Map containing the deliveries sorted by carrier ID.
         * @param subNetwork   The network used for parcel services.
         * @param hubList
         * @param vehicleTypes
         * @param mergeThreshold Fixed carrier merge threshold (parcels). Carriers below this get merged.
         * @return The generated carriers.
         */
        private Carriers generateCarriersAndCarrierServices(final Map<String, ArrayList<Delivery>> deliveries,
                        final Network subNetwork, CarrierVehicleTypes vehicleTypes, Map<Id<Hub>, Hub> hubList,
                        int mergeThreshold, int maxServiceSize) {
                final Carriers carriers = new Carriers();
                Map<String, Double> adjustedDeliveryRates = adjustDeliveryRatesConsideringB2B(initializeDeliveryRate(),
                                deliveries);

                deliveries.entrySet().stream().map(entry -> {
                        final String carrierID = entry.getKey();
                        final ArrayList<Delivery> carrierDeliveries = entry.getValue();

                        final Carrier carrier = CarriersUtils.createCarrier(Id.create(carrierID, Carrier.class));
                        CarriersUtils.setCarrierMode(carrier, "car");

                        setupCarrierAttributes(carrier, carrierID);

                        try {
                                addCarrierServicesToCarriers(carrier, carrierDeliveries, subNetwork,
                                                adjustedDeliveryRates);
                                addCarrierVehiclesToCarrier(carrier, hubList);
                        } catch (ServiceCreationException e) {
                                LOGGER.error(carrierID + ": Error creating carrier services", e);
                        }

                        return carrier;
                }).forEach(carriers::addCarrier);

                logAndValidateInsufficientCarrier(carriers, mergeThreshold, maxServiceSize);

                LOGGER.info("Carriers generated: {}", carriers.getCarriers().size());

                scenario.addScenarioElement("carriers", carriers);

                return carriers;
        }

        /**
         * Adjusts the delivery success rates per provider based on the share of B2B and
         * B2C parcels.
         *
         * <p>
         * Since B2B parcels are assumed to be delivered with a guaranteed 100% success
         * rate,
         * this method computes the adjusted B2C delivery rate such that the overall
         * delivery
         * success rate per provider (including both B2B and B2C) matches the target
         * value from the configuration.
         * </p>
         *
         * <p>
         * This ensures more accurate simulation of missed parcel behavior and aligns
         * with real-world
         * expectations where B2C deliveries are less reliable than B2B.
         * </p>
         *
         * <p>
         * The adjustment is based on the formula:
         * 
         * <pre>{@code
         * adjustedB2C = (targetRate * totalParcels - b2bParcels) / b2cParcels
         * }</pre>
         * 
         * with proper clamping to the [0, 1] interval.
         * </p>
         *
         * @param initialRates A map of providers and their original delivery rates (as
         *                     percentage values from config, e.g. 94.0).
         * @param deliveries   A map of carrier IDs to their associated delivery
         *                     objects.
         * @return A map of providers and their adjusted B2C delivery rates (still in
         *         percentage format).
         */
        private Map<String, Double> adjustDeliveryRatesConsideringB2B(
                        Map<String, Double> initialRates,
                        Map<String, ArrayList<Delivery>> deliveries) {

                // Log initial (target overall) delivery rates per provider
                try {
                        String initialRatesStr = initialRates.entrySet().stream()
                                        .sorted(Map.Entry.comparingByKey())
                                        .map(e -> e.getKey() + "=" + String.format("%.2f%%", e.getValue()))
                                        .collect(Collectors.joining(", "));
                        LOGGER.info("[DeliveryRates] Initial target (overall) per provider: {}", initialRatesStr);
                } catch (Exception ignore) {
                        // logging best-effort only
                }

                // Store total parcel volume per provider
                Map<String, Long> totalParcelsPerProvider = new HashMap<>();

                // Store B2B parcel volume per provider
                Map<String, Long> b2bParcelsPerProvider = new HashMap<>();

                deliveries.forEach((carrierId, deliveryList) -> {
                        String provider = carrierId.split("_")[0].toLowerCase();
                        for (Delivery d : deliveryList) {
                                long amount = d.getAmount();
                                totalParcelsPerProvider.merge(provider, amount, Long::sum);
                                if (d.getParcelType() == Delivery.ParcelType.B2B) {
                                        b2bParcelsPerProvider.merge(provider, amount, Long::sum);
                                }
                        }
                });

                Map<String, Double> adjustedRates = new HashMap<>();

                for (Map.Entry<String, Double> entry : initialRates.entrySet()) {
                        String provider = entry.getKey(); // ensure variable kept (used in conditions & trace)
                        double targetRate = entry.getValue() / 100.0; // percent -> ratio

                        // NEW: For predominantly B2B carriers (UPS, FedEx) keep original target (no B2C
                        // back-calculation)
                        if ("ups".equals(provider) || "fedex".equals(provider)) {
                                adjustedRates.put(provider, entry.getValue());
                                if (LOGGER.isTraceEnabled())
                                        LOGGER.trace("[DeliveryRates] Provider {} kept at target {}% (B2B heavy)",
                                                        provider, entry.getValue());
                                continue;
                        }

                        long total = totalParcelsPerProvider.getOrDefault(provider, 0L);
                        long b2b = b2bParcelsPerProvider.getOrDefault(provider, 0L);
                        long b2c = total - b2b;

                        double adjustedB2CRate;
                        if (b2c == 0) {
                                // No B2C parcels; leave at original target (or 100?). Keep original target for
                                // consistency
                                adjustedB2CRate = targetRate * 100.0; // convert back to percent
                        } else {
                                adjustedB2CRate = (targetRate * total - b2b) / (double) b2c; // ratio
                                adjustedB2CRate = Math.min(1.0, Math.max(0.0, adjustedB2CRate));
                                adjustedB2CRate *= 100.0; // to percent
                        }
                        adjustedRates.put(provider, adjustedB2CRate);
                }

                // Log adjusted B2C delivery rates per provider
                try {
                        String adjustedRatesStr = adjustedRates.entrySet().stream()
                                        .sorted(Map.Entry.comparingByKey())
                                        .map(e -> e.getKey() + "=" + String.format("%.2f%%", e.getValue()))
                                        .collect(Collectors.joining(", "));
                        LOGGER.info("[DeliveryRates] Adjusted B2C per provider: {}", adjustedRatesStr);

                        if (LOGGER.isDebugEnabled()) {
                                String volumesStr = totalParcelsPerProvider.keySet().stream()
                                                .sorted()
                                                .map(p -> String.format("%s(total=%d, b2b=%d, b2c=%d)", p,
                                                                totalParcelsPerProvider.getOrDefault(p, 0L),
                                                                b2bParcelsPerProvider.getOrDefault(p, 0L),
                                                                totalParcelsPerProvider.getOrDefault(p, 0L)
                                                                                - b2bParcelsPerProvider.getOrDefault(p,
                                                                                                0L)))
                                                .collect(Collectors.joining(", "));
                                LOGGER.debug("[DeliveryRates] Volumes used for adjustment: {}", volumesStr);
                        }
                } catch (Exception ignore) {
                }

                return adjustedRates;
        }

        /**
         * Logs information about carriers, including the ones with the most and least
         * services,
         * and removes carriers with fewer than 5 services. -> Problem with Hannover
         * Shape -> there are some deliveries not corretly removed
         * since the shape is not 100% correct - or at least does not fit to the zip
         * code areas
         * -> not a solid solution, but at least a working on i hope TODO adjust Shape
         * Files that the are inline with the zip code areas
         * -> lol not working, there are actualy more problems then I thought^^ ->
         * 2024-06-20 18:49:56 INFO CarrierGenerator:196 - Number of carriers removed
         * with fewer than 5 services: 24
         * -> need to check
         * -> fixed using postal codes -> keep
         * -> UPDATED with merge funtionality to merge smaller carriers into larger ones
         *
         * @param carriers The Carriers object containing all carriers.
         * @throws RuntimeException if any carriers have fewer than 5 services.
         */
        private void logAndValidateInsufficientCarrier(Carriers carriers, int mergeThreshold, int maxServiceSize) {
                // Find the carrier with the most services
                Optional<Carrier> carrierWithMostServices = carriers.getCarriers().values().stream()
                                .max(Comparator.comparingInt(carrier -> carrier.getServices().size()));

                // Find the carrier with the least services
                Optional<Carrier> carrierWithLeastServices = carriers.getCarriers().values().stream()
                                .min(Comparator.comparingInt(carrier -> carrier.getServices().size()));

                // Log the carrier with the most services
                carrierWithMostServices
                                .ifPresent(carrier -> LOGGER.info("Carrier with the most services: {} with {} services",
                                                carrier.getId(), carrier.getServices().size()));

                // Log the carrier with the least services
                carrierWithLeastServices.ifPresent(
                                carrier -> LOGGER.info("Carrier with the least services: {} with {} services",
                                                carrier.getId(), carrier.getServices().size()));

                // Collect IDs of carriers with total parcel demand (summed over all services) <
                // minimum configured vehicle capacity
                List<Id<Carrier>> insufficientServiceCarrierIds = carriers.getCarriers().values().stream()
                                .filter(carrier -> carrier.getServices().values().stream()
                                                .mapToLong(service -> service.getCapacityDemand()) // Assuming
                                                                                                   // single
                                                                                                   // capacity
                                                                                                   // dimension
                                .sum() < mergeThreshold)
                                .map(Carrier::getId)
                                .collect(Collectors.toList());

                LOGGER.info("Number of carriers before merging smaller carriers: {}", carriers.getCarriers().size());
                // Log the carriers with insufficient services
                LOGGER.info("Number of carriers with fewer than {} parcels to delivery: {}", mergeThreshold,
                                insufficientServiceCarrierIds.size());

                // Create merge log and record pre-merge state
                CarrierMergeLog mergeLog = new CarrierMergeLog();
                mergeLog.setMergeThreshold(mergeThreshold);
                mergeLog.setCarriersBeforeMerge(carriers.getCarriers().size());
                mergeLog.setCarriersBelowThreshold(insufficientServiceCarrierIds.size());

                // Merge carriers with insufficient services into larger carriers from same
                // company
                mergeCarriersWithInsufficientServices(carriers.getCarriers(), insufficientServiceCarrierIds,
                                maxServiceSize, mergeLog);
                insufficientServiceCarrierIds.clear();

                // Re-Collect IDs of carriers with total parcel demand (summed over all
                // services) < fixed merge threshold
                // For validation to check if the merging was successful
                insufficientServiceCarrierIds = carriers.getCarriers().values().stream()
                                .filter(carrier -> carrier.getServices().values().stream()
                                                .mapToLong(service -> service.getCapacityDemand()) // Assuming
                                                                                                   // single
                                                                                                   // capacity
                                                                                                   // dimension
                                                .sum() < mergeThreshold)
                                .map(Carrier::getId)
                                .collect(Collectors.toList());

                if (!insufficientServiceCarrierIds.isEmpty()) {
                        throw new RuntimeException("There are carriers with parcel demand below mergeThreshold ("
                                        + mergeThreshold + "). IDs: "
                                        + insufficientServiceCarrierIds);
                }

                int remainingSize = carriers.getCarriers().size();
                LOGGER.info("Number of remaining carriers: {}", remainingSize);

                // Store merge log in scenario for summary writer
                mergeLog.setCarriersAfterMerge(remainingSize);
                scenario.addScenarioElement("carrierMergeLog", mergeLog);

        }

        /**
         * Merges underutilized carriers (with insufficient parcel volume) into nearby
         * larger carriers
         * of the same provider. Preserves total demand across all services.
         *
         * @param carrierMap             Full map of carriers (modifiable).
         * @param insufficientCarrierIds Set of carrier IDs previously identified as
         *                               under threshold.
         * @param mergeLog               Collects structured merge events for the summary.
         */
        public void mergeCarriersWithInsufficientServices(Map<Id<Carrier>, Carrier> carrierMap,
                        List<Id<Carrier>> insufficientCarrierIds, int maxServiceSize,
                        CarrierMergeLog mergeLog) {
                Logger LOGGER = LogManager.getLogger(getClass());

                // Helper: sum parcel demand of a carrier
                java.util.function.ToIntFunction<Carrier> parcelDemand = c -> c.getServices().values().stream()
                                .mapToInt(CarrierService::getCapacityDemand).sum();

                // Calculate initial parcel count to validate later
                long totalDemandBefore = carrierMap.values().stream()
                                .flatMap(c -> c.getServices().values().stream())
                                .mapToLong(CarrierService::getCapacityDemand)
                                .sum();

                // Tag providers from carrier IDs
                Map<String, List<Carrier>> carriersByProvider = carrierMap.values().stream()
                                .peek(c -> c.getAttributes().putAttribute("provider", extractProviderFromId(c.getId())))
                                .collect(Collectors.groupingBy(
                                                c -> c.getAttributes().getAttribute("provider").toString()));

                List<Id<Carrier>> carriersToRemove = new ArrayList<>();

                for (Map.Entry<String, List<Carrier>> entry : carriersByProvider.entrySet()) {
                        String provider = entry.getKey();
                        List<Carrier> carriers = entry.getValue();
                        // Use fixed carrier merge threshold (scenario-independent)
                        final int minCap = hagridConfig.getCarrierMergeThreshold();

                        // --- Legacy Initial Pass (exakte alte Logik für small->large Auswahl) ---
                        List<Carrier> initialSmall = carriers.stream()
                                        .filter(c -> insufficientCarrierIds.contains(c.getId()))
                                        .collect(Collectors.toList());
                        List<Carrier> initialLarge = carriers.stream()
                                        .filter(c -> !insufficientCarrierIds.contains(c.getId()))
                                        .filter(c -> c.getServices().size() <= (maxServiceSize)) // Pufffer -> has
                                                                                                 // Performance Reasons
                                        .collect(Collectors.toList());

                        for (Carrier smallCarrier : new ArrayList<>(initialSmall)) {
                                if (carriersToRemove.contains(smallCarrier.getId()))
                                        continue;
                                Coord smallCoord = GeoUtils.getMedianCoordOfStoredServiceCoords(smallCarrier);
                                Carrier nearestLarge = initialLarge.stream()
                                                .min(Comparator.comparingDouble(big -> CoordUtils.calcEuclideanDistance(
                                                                smallCoord,
                                                                GeoUtils.getMedianCoordOfStoredServiceCoords(big))))
                                                .orElse(null);

                                if (nearestLarge != null) {
                                        int srcServices = smallCarrier.getServices().size();
                                        int srcParcels = parcelDemand.applyAsInt(smallCarrier);
                                        int tgtServicesBefore = nearestLarge.getServices().size();
                                        int tgtParcelsBefore = parcelDemand.applyAsInt(nearestLarge);

                                        smallCarrier.getServices().values()
                                                        .forEach(svc -> CarriersUtils.addService(nearestLarge, svc));
                                        carriersToRemove.add(smallCarrier.getId());
                                        LOGGER.info("[Small->Large Merge] Merged carrier '{}' into '{}'",
                                                        smallCarrier.getId(), nearestLarge.getId());

                                        mergeLog.addEntry(new CarrierMergeLog.MergeEntry(
                                                        smallCarrier.getId().toString(),
                                                        nearestLarge.getId().toString(),
                                                        srcServices, srcParcels,
                                                        tgtServicesBefore,
                                                        nearestLarge.getServices().size(),
                                                        tgtParcelsBefore,
                                                        parcelDemand.applyAsInt(nearestLarge),
                                                        "Small→Large", 0));
                                } else {
                                        // Legacy Fallback (klein->klein) mit Scoring
                                        final double DIST_WEIGHT = 1.0;
                                        final double FILL_WEIGHT = 0;
                                        Carrier fallbackTarget = initialSmall.stream()
                                                        .filter(c -> !c.getId().equals(smallCarrier.getId()))
                                                        .filter(c -> !carriersToRemove.contains(c.getId()))
                                                        .filter(c -> (c.getServices().size() + smallCarrier
                                                                        .getServices().size()) <= maxServiceSize)
                                                        .min(Comparator.comparingDouble(other -> {
                                                                double dist = CoordUtils.calcEuclideanDistance(
                                                                                smallCoord,
                                                                                GeoUtils.getMedianCoordOfStoredServiceCoords(
                                                                                                other));
                                                                double projectedFillRatio = (other.getServices().size()
                                                                                + smallCarrier.getServices().size())
                                                                                / (double) maxServiceSize;
                                                                return DIST_WEIGHT * dist + FILL_WEIGHT
                                                                                * projectedFillRatio * dist;
                                                        }))
                                                        .orElse(null);
                                        if (fallbackTarget != null) {
                                                int srcServices = smallCarrier.getServices().size();
                                                int srcParcels = parcelDemand.applyAsInt(smallCarrier);
                                                int tgtParcelsBefore = parcelDemand.applyAsInt(fallbackTarget);
                                                int before = fallbackTarget.getServices().size();
                                                smallCarrier.getServices().values().forEach(
                                                                s -> CarriersUtils.addService(fallbackTarget, s));
                                                int after = fallbackTarget.getServices().size();
                                                carriersToRemove.add(smallCarrier.getId());
                                                double dist = CoordUtils.calcEuclideanDistance(smallCoord, GeoUtils
                                                                .getMedianCoordOfStoredServiceCoords(fallbackTarget));
                                                double fillPct = (after / (double) maxServiceSize) * 100.0;
                                                LOGGER.info("[Small->Small Merge] '{}' -> '{}' | dist={}.1f added={} newSize={} fill={}.1f%% (<= {})",
                                                                smallCarrier.getId(),
                                                                fallbackTarget.getId(),
                                                                dist,
                                                                (after - before),
                                                                after,
                                                                fillPct,
                                                                maxServiceSize);

                                                mergeLog.addEntry(new CarrierMergeLog.MergeEntry(
                                                                smallCarrier.getId().toString(),
                                                                fallbackTarget.getId().toString(),
                                                                srcServices, srcParcels,
                                                                before, after,
                                                                tgtParcelsBefore,
                                                                parcelDemand.applyAsInt(fallbackTarget),
                                                                "Small→Small", 0));
                                        } else {
                                                String blockedInfo = initialSmall.stream()
                                                                .filter(c -> !c.getId().equals(smallCarrier.getId()))
                                                                .filter(c -> !carriersToRemove.contains(c.getId()))
                                                                .filter(c -> (c.getServices().size() + smallCarrier
                                                                                .getServices().size()) > maxServiceSize)
                                                                .sorted(Comparator.comparingDouble(c -> CoordUtils
                                                                                .calcEuclideanDistance(smallCoord,
                                                                                                GeoUtils.getMedianCoordOfStoredServiceCoords(
                                                                                                                c))))
                                                                .limit(3)
                                                                .map(c -> {
                                                                        int combined = c.getServices().size()
                                                                                        + smallCarrier.getServices()
                                                                                                        .size();
                                                                        return c.getId() + "(would=" + combined + ">"
                                                                                        + maxServiceSize + ")";
                                                                })
                                                                .collect(Collectors.joining(", "));
                                                LOGGER.warn("No merge target found for '{}' (capacity). Closest blocked: {}",
                                                                smallCarrier.getId(),
                                                                blockedInfo.isEmpty() ? "-" : blockedInfo);
                                        }
                                }
                                // Update large list wie früher (basierend auf ursprünglicher
                                // insufficientCarrierIds)
                                initialLarge = carriers.stream()
                                                .filter(c -> !insufficientCarrierIds.contains(c.getId()))
                                                .filter(c -> c.getServices().size() <= maxServiceSize)
                                                .collect(Collectors.toList());
                        }

                        // --- Iterativer Zusatz-Pass nur für übrig gebliebene unter-Schwelle Carrier
                        // ---
                        boolean progress;
                        int iteration = 0;
                        do {
                                iteration++;
                                progress = false;
                                List<Carrier> active = carriers.stream()
                                                .filter(c -> !carriersToRemove.contains(c.getId()))
                                                .collect(Collectors.toList());
                                List<Carrier> smallDyn = active.stream()
                                                .filter(c -> c.getServices().values().stream()
                                                                .mapToLong(CarrierService::getCapacityDemand)
                                                                .sum() < minCap)
                                                .collect(Collectors.toList());
                                if (smallDyn.isEmpty())
                                        break;
                                List<Carrier> largeDyn = active.stream()
                                                .filter(c -> !smallDyn.contains(c))
                                                .filter(c -> c.getServices().size() <= maxServiceSize)
                                                .collect(Collectors.toList());
                                for (Carrier smallCarrier : new ArrayList<>(smallDyn)) {
                                        if (carriersToRemove.contains(smallCarrier.getId()))
                                                continue;
                                        Coord smallCoord = GeoUtils.getMedianCoordOfStoredServiceCoords(smallCarrier);
                                        Carrier nearestLarge = largeDyn.stream()
                                                        .min(Comparator.comparingDouble(
                                                                        big -> CoordUtils.calcEuclideanDistance(
                                                                                        smallCoord,
                                                                                        GeoUtils.getMedianCoordOfStoredServiceCoords(
                                                                                                        big))))
                                                        .orElse(null);
                                        if (nearestLarge != null) {
                                                int srcServices = smallCarrier.getServices().size();
                                                int srcParcels = parcelDemand.applyAsInt(smallCarrier);
                                                int tgtServicesBefore = nearestLarge.getServices().size();
                                                int tgtParcelsBefore = parcelDemand.applyAsInt(nearestLarge);

                                                smallCarrier.getServices().values().forEach(
                                                                svc -> CarriersUtils.addService(nearestLarge, svc));
                                                carriersToRemove.add(smallCarrier.getId());
                                                LOGGER.info("[Small->Large Merge][it={}] '{}' -> '{}'", iteration,
                                                                smallCarrier.getId(), nearestLarge.getId());
                                                progress = true;

                                                mergeLog.addEntry(new CarrierMergeLog.MergeEntry(
                                                                smallCarrier.getId().toString(),
                                                                nearestLarge.getId().toString(),
                                                                srcServices, srcParcels,
                                                                tgtServicesBefore,
                                                                nearestLarge.getServices().size(),
                                                                tgtParcelsBefore,
                                                                parcelDemand.applyAsInt(nearestLarge),
                                                                "Small→Large", iteration));
                                        } else {
                                                final double DIST_WEIGHT = 0.75;
                                                final double FILL_WEIGHT = 0.25;
                                                Carrier fallbackTarget = smallDyn.stream()
                                                                .filter(c -> !c.getId().equals(smallCarrier.getId()))
                                                                .filter(c -> !carriersToRemove.contains(c.getId()))
                                                                .filter(c -> (c.getServices().size() + smallCarrier
                                                                                .getServices()
                                                                                .size()) <= maxServiceSize)
                                                                .min(Comparator.comparingDouble(other -> {
                                                                        double dist = CoordUtils.calcEuclideanDistance(
                                                                                        smallCoord,
                                                                                        GeoUtils.getMedianCoordOfStoredServiceCoords(
                                                                                                        other));
                                                                        double projectedFillRatio = (other.getServices()
                                                                                        .size()
                                                                                        + smallCarrier.getServices()
                                                                                                        .size())
                                                                                        / (double) maxServiceSize;
                                                                        return DIST_WEIGHT * dist + FILL_WEIGHT
                                                                                        * projectedFillRatio * dist;
                                                                }))
                                                                .orElse(null);
                                                if (fallbackTarget != null) {
                                                        int srcServices = smallCarrier.getServices().size();
                                                        int srcParcels = parcelDemand.applyAsInt(smallCarrier);
                                                        int tgtParcelsBefore = parcelDemand.applyAsInt(fallbackTarget);
                                                        int before = fallbackTarget.getServices().size();
                                                        smallCarrier.getServices().values().forEach(s -> CarriersUtils
                                                                        .addService(fallbackTarget, s));
                                                        int after = fallbackTarget.getServices().size();
                                                        carriersToRemove.add(smallCarrier.getId());
                                                        double dist = CoordUtils.calcEuclideanDistance(smallCoord,
                                                                        GeoUtils.getMedianCoordOfStoredServiceCoords(
                                                                                        fallbackTarget));
                                                        double fillPct = (after / (double) maxServiceSize) * 100.0;
                                                        LOGGER.info("[Small->Small Merge][it={}] '{}' -> '{}' | dist={}.1f added={} newSize={} fill={}.1f%% (<= {})",
                                                                        iteration,
                                                                        smallCarrier.getId(),
                                                                        fallbackTarget.getId(),
                                                                        dist,
                                                                        (after - before),
                                                                        after,
                                                                        fillPct,
                                                                        maxServiceSize);
                                                        progress = true;

                                                        mergeLog.addEntry(new CarrierMergeLog.MergeEntry(
                                                                        smallCarrier.getId().toString(),
                                                                        fallbackTarget.getId().toString(),
                                                                        srcServices, srcParcels,
                                                                        before, after,
                                                                        tgtParcelsBefore,
                                                                        parcelDemand.applyAsInt(fallbackTarget),
                                                                        "Small→Small", iteration));
                                                } else {
                                                        String blockedInfo = smallDyn.stream()
                                                                        .filter(c -> !c.getId()
                                                                                        .equals(smallCarrier.getId()))
                                                                        .filter(c -> !carriersToRemove
                                                                                        .contains(c.getId()))
                                                                        .filter(c -> (c.getServices().size()
                                                                                        + smallCarrier.getServices()
                                                                                                        .size()) > maxServiceSize)
                                                                        .sorted(Comparator.comparingDouble(
                                                                                        c -> CoordUtils.calcEuclideanDistance(
                                                                                                        smallCoord,
                                                                                                        GeoUtils.getMedianCoordOfStoredServiceCoords(
                                                                                                                        c))))
                                                                        .limit(3)
                                                                        .map(c -> {
                                                                                int combined = c.getServices().size()
                                                                                                + smallCarrier.getServices()
                                                                                                                .size();
                                                                                return c.getId() + "(would=" + combined
                                                                                                + ">" + maxServiceSize
                                                                                                + ")";
                                                                        })
                                                                        .collect(Collectors.joining(", "));
                                                        LOGGER.warn("[Merge-Stall][it={}] No merge target for '{}' (capacity). Closest blocked: {}",
                                                                        iteration, smallCarrier.getId(),
                                                                        blockedInfo.isEmpty() ? "-" : blockedInfo);
                                                }
                                        }
                                }
                                if (!progress) {
                                        List<Carrier> stillSmall = carriers.stream()
                                                        .filter(c -> !carriersToRemove.contains(c.getId()))
                                                        .filter(c -> c.getServices().values().stream()
                                                                        .mapToLong(CarrierService::getCapacityDemand)
                                                                        .sum() < minCap)
                                                        .collect(Collectors.toList());
                                        if (!stillSmall.isEmpty()) {
                                                LOGGER.warn("[Merge-Result] Stalled with {} under-threshold carriers: {}",
                                                                stillSmall.size(),
                                                                stillSmall.stream().map(c -> c.getId().toString())
                                                                                .collect(Collectors.joining(",")));
                                        }
                                }
                        } while (progress);
                }

                // Remove all merged carriers from map
                carriersToRemove.forEach(carrierMap::remove);

                // Validate demand integrity
                long totalDemandAfter = carrierMap.values().stream()
                                .flatMap(c -> c.getServices().values().stream())
                                .mapToLong(CarrierService::getCapacityDemand)
                                .sum();

                if (totalDemandBefore != totalDemandAfter) {
                        throw new IllegalStateException(String.format(
                                        "Parcel count mismatch after merging carriers. Before: %d, After: %d, Diff: %d",
                                        totalDemandBefore, totalDemandAfter, (totalDemandAfter - totalDemandBefore)));
                }

                LOGGER.info("Merged {} small carriers. Parcel volume preserved.", carriersToRemove.size());
        }

        /**
         * Extracts the provider name from a carrier ID (assuming ID format is
         * 'provider_something').
         *
         * @param id The carrier ID.
         * @return The provider prefix as lowercase.
         */
        private String extractProviderFromId(Id<Carrier> id) {
                return id.toString().split("_")[0].toLowerCase();
        }

        /**
         * Sets up the attributes for a given carrier based on its ID.
         *
         * This method initializes the fleet size to infinite and sets several
         * attributes
         * including the provider, postal code, missed parcels count, and a list to
         * track
         * missed parcels.
         *
         * @param carrier   The carrier whose attributes are to be set.
         * @param carrierID The ID of the carrier, used to extract and set specific
         *                  attributes.
         */
        private void setupCarrierAttributes(Carrier carrier, String carrierID) {
                // Set the fleet size of the carrier to infinite
                carrier.getCarrierCapabilities().setFleetSize(FleetSize.INFINITE);

                // Extract and set the provider attribute from the carrier ID
                carrier.getAttributes().putAttribute("provider", carrierID.split("_")[0]);

                // Extract and set the postal code (first 5 digits) attribute from the carrier
                // ID
                carrier.getAttributes().putAttribute("plz", carrierID.split("_")[1].substring(0, 5));

                // Initialize the missed parcels count to 0
                carrier.getAttributes().putAttribute("missedParcels", 0);

                // Initialize the missed parcels list to null
                carrier.getAttributes().putAttribute("missedParcelsAsList", null);

                // Initialize the carrier type attribute to CarrierType = delivery
                carrier.getAttributes().putAttribute("carrierType", "delivery");

                // Set the carrier mode to "car"
                // TODO: Add an implementation for all modes etc.
                CarriersUtils.setCarrierMode(carrier, "car");
        }

        /**
         * Adds services to the carriers based on the provided deliveries and delivery
         * rates.
         *
         * This method takes a carrier and its deliveries, determines the number of
         * services needed
         * based on the delivery rates and vehicle capacity, and adds the corresponding
         * services
         * to the carrier. It also calculates and updates the total weight of parcels
         * and the correction
         * factor in the summary.
         * We also calcualte the daily number of missed delivery based on simple
         * statistics
         * TODO: Add a more sophisticated and deeper model for missed deliveries in the
         * future!
         *
         * @param carrier           The carrier to which services are to be added.
         * @param carrierDeliveries The deliveries for the carrier.
         * @param subNetwork        The network used for parcel services.
         * @param deliveryRates     The delivery rates for each provider.
         * @throws ServiceCreationException if a service cannot be created.
         */
        private void addCarrierServicesToCarriers(final Carrier carrier, final ArrayList<Delivery> carrierDeliveries,
                        final Network subNetwork, final Map<String, Double> deliveryRates)
                        throws ServiceCreationException {

                // Retrieve the provider attribute from the carrier
                final String provider = (String) carrier.getAttributes().getAttribute("provider");

                // Check if the delivery rate for the provider is available
                if (!deliveryRates.containsKey(provider)) {
                        throw new IllegalStateException("Delivery rate not available for provider: " + provider);
                }

                int totalServices = 0;
                int correctionFactor = 0;
                double totalWeightForCarrier = 0.0;
                List<CarrierService> createdServices = new ArrayList<>();

                // Determine the standard deviation (sigma) for daily bias by provider
                double sigmaPercent = "dhl".equals(provider) ? 2.5 : 5.0;

                // Sample a daily bias around zero (in percentage points)
                double dailyBias = random.nextGaussian() * sigmaPercent; // DHL: ±2.5%, others: ±5%
                carrier.getAttributes().putAttribute("dailyDeliveryBias", dailyBias);

                // Iterate through each delivery associated with the carrier
                for (final Delivery carrierDelivery : carrierDeliveries) {

                        // Get the base delivery success rate (e.g. 94.0 for 94%)
                        // 100% is not possible -> so 98% ist max
                        double baseRate = deliveryRates.get(provider); // e.g. 94.0
                        double effectiveRate = Math.max(0.0, Math.min(98.0, baseRate + dailyBias));

                        // Set the delivery rate to 100% for B2B parcel types
                        if (carrierDelivery.getParcelType() == ParcelType.B2B) {
                                effectiveRate = 100.0;
                        }

                        final int amount = carrierDelivery.getAmount();
                        // Use minimum vehicle capacity for splitting to ensure each segment fits
                        final int minVehicleCap = hagridConfig.getMinVehicleCapacity();
                        final int numberOfServices = (int) Math.ceil((double) amount / minVehicleCap);
                        final int cap = minVehicleCap;
                        final List<Double> weights = new ArrayList<>(carrierDelivery.getIndividualWeights());

                        // Calculate the total weight of parcels for the carrier
                        totalWeightForCarrier += weights.stream().mapToDouble(Double::doubleValue).sum();
                        LOGGER.debug("Processing carrier delivery: " + carrierDelivery);
                        LOGGER.debug("Initial weights: " + weights);

                        // Find the nearest link in the sub-network for the delivery location
                        final Id<Link> linkId = NetworkUtils
                                        .getNearestLinkExactly(subNetwork, carrierDelivery.getCoordinate())
                                        .getId();

                        // Create and add services for the carrier delivery
                        for (int j = 0; j < numberOfServices - 1; j++) {
                                List<Double> serviceWeights = new ArrayList<>(weights.subList(0, cap));
                                LOGGER.debug("Service " + totalServices + ": Weights for this service: "
                                                + serviceWeights);
                                weights.subList(0, cap).clear();
                                LOGGER.debug("Remaining weights after clearing: " + weights);
                                CarrierService service = addAndGetCarrierService(carrier, linkId, effectiveRate, cap,
                                                carrierDelivery,
                                                totalServices++, serviceWeights);
                                createdServices.add(service);
                                correctionFactor++; // Increment correction factor for each split
                                LOGGER.debug("Incremented correction factor. New value: {}", correctionFactor);
                        }

                        // Handle the last segment which might be smaller than the cap
                        final int remainingCapacity = amount - ((numberOfServices - 1) * cap);
                        if (remainingCapacity > 0) {
                                List<Double> serviceWeights = new ArrayList<>(weights.subList(0, remainingCapacity));
                                LOGGER.debug("Service " + totalServices + ": Weights for this service: "
                                                + serviceWeights);
                                CarrierService service = addAndGetCarrierService(carrier, linkId, effectiveRate,
                                                remainingCapacity, carrierDelivery,
                                                totalServices++, serviceWeights);
                                createdServices.add(service);
                        } else {
                                LOGGER.warn("Remaining capacity is zero for delivery: " + carrierDelivery);
                        }
                }

                // Validate the created carrier services against the original deliveries
                validateCarrierServices(carrierDeliveries, createdServices);

                // Update the correction factor in the summary
                final HAGRIDSummary summary = (HAGRIDSummary) HAGRIDUtils.getScenarioElementAs("summary", scenario);
                summary.setCorrectionFactor(summary.getCorrectionFactor() + correctionFactor);

                LOGGER.debug("Total weight for carrier {}: {}", carrier.getId(), totalWeightForCarrier);
        }

        /**
         * Adds a single service to a carrier and determines missed parcels based on the
         * delivery rate.
         *
         * This method creates and adds a service to a given carrier, setting various
         * attributes
         * and determining missed parcels if the delivery rate is less than 100%. The
         * service
         * duration, start time window, and other attributes are configured based on the
         * provided
         * parameters and the carrier's delivery information.
         *
         * @param carrier         The carrier to which the service is to be added.
         * @param linkId          The link ID where the service is located.
         * @param rate            The delivery rate for the service.
         * @param capacityDemand  The capacity demand of the service.
         * @param carrierDelivery The delivery information.
         * @param serviceNumber   The service number for unique identification.
         * @param weights         The weights of the parcels for this service.
         * @return The created CarrierService.
         * @throws ServiceCreationException if the service could not be created.
         */
        @SuppressWarnings({ "deprecation" })
        private CarrierService addAndGetCarrierService(final Carrier carrier, final Id<Link> linkId, final double rate,
                        final int capacityDemand, final Delivery carrierDelivery,
                        final int serviceNumber, final List<Double> weights) throws ServiceCreationException {

                List<Double> weightsCopy = null;
                try {
                        final double serviceDuration = Math.min(
                                        (hagridConfig.getDurationPerParcel() * 60) * capacityDemand,
                                        hagridConfig.getMaxDurationPerStop() * 60);

                        final double begin = hagridConfig.getDeliveryTimeWindowStart();
                        final double end = hagridConfig.getDeliveryTimeWindowEnd();

                        final String serviceId = String.format("service_%s_%s_%d", carrierDelivery.getParcelType(),
                                        carrier.getId(), serviceNumber);

                        final CarrierService.Builder serviceBuilder = CarrierService.Builder.newInstance(
                                        Id.create(serviceId, CarrierService.class), linkId);

                        serviceBuilder.setCapacityDemand(capacityDemand);
                        serviceBuilder.setServiceDuration(serviceDuration);
                        serviceBuilder.setServiceStartingTimeWindow(TimeWindow.newInstance(begin, end));

                        final CarrierService service = serviceBuilder.build();
                        service.getAttributes().putAttribute("provider",
                                        carrier.getAttributes().getAttribute("provider"));
                        service.getAttributes().putAttribute("coord", carrierDelivery.getCoordinate());
                        service.getAttributes().putAttribute("type", carrierDelivery.getParcelType());
                        service.getAttributes().putAttribute("carrierMode", carrierDelivery.getDeliveryMode());
                        service.getAttributes().putAttribute("postalcode", carrierDelivery.getPostalCode());
                        // Handle case where weights is null
                        if (weights == null) {
                                throw new IllegalArgumentException("Weights list is null");
                        }

                        // Create a copy of the weights list to avoid ConcurrentModificationException
                        weightsCopy = new ArrayList<>(weights);

                        // Convert the list of Doubles to a single String
                        String weightsString = weightsCopy.stream()
                                        .map(String::valueOf)
                                        .collect(Collectors.joining(";"));

                        service.getAttributes().putAttribute("weights", weightsString);

                        if (rate < 100.0) {
                                determineMissedParcels(carrier, service, rate);
                        }

                        CarriersUtils.addSkill(service, "conventional");
                        CarriersUtils.addService(carrier, service);

                        return service;

                } catch (IllegalArgumentException e) {
                        // Log all necessary information for debugging
                        String weightsStr = (weightsCopy != null) ? weightsCopy.toString()
                                        : ((weights != null) ? weights.toString() : "null");
                        LOGGER.error("Failed to add service. Carrier: " + carrier.getId() +
                                        ", LinkId: " + linkId + ", Rate: " + rate +
                                        ", CapacityDemand: " + capacityDemand +
                                        ", CarrierDelivery: " + carrierDelivery +
                                        ", ServiceNumber: " + serviceNumber +
                                        ", Weights: " + weightsStr, e);
                        throw new ServiceCreationException("Failed to add service due to illegal argument.", e);
                } catch (ConcurrentModificationException e) {
                        // Log and handle concurrent modification
                        String weightsStr = (weightsCopy != null) ? weightsCopy.toString()
                                        : ((weights != null) ? weights.toString() : "null");
                        LOGGER.error("Concurrent modification detected. Carrier: " + carrier.getId() +
                                        ", LinkId: " + linkId + ", Rate: " + rate +
                                        ", CapacityDemand: " + capacityDemand +
                                        ", CarrierDelivery: " + carrierDelivery +
                                        ", ServiceNumber: " + serviceNumber +
                                        ", Weights: " + weightsStr, e);
                        throw new ServiceCreationException("Concurrent modification detected while adding service.", e);
                }
        }

        /**
         * Determines missed parcels based on the delivery rate and updates the
         * carrier's attributes accordingly.
         *
         * @param carrier The carrier for which missed parcels are being determined.
         * @param service The service being added.
         * @param rate    The delivery rate for the service.
         */
        private void determineMissedParcels(final Carrier carrier, final CarrierService service, final double rate) {

                final int amount = service.getCapacityDemand();
                int missed = 0;
                final ArrayList<Id<CarrierService>> missedDeliveries = new ArrayList<>();

                for (int a = 0; a < amount; a++) {
                        final double randomNumber = random.nextDouble() * 100;

                        if (randomNumber > rate) {
                                missedDeliveries.add(service.getId());
                                missed++;
                        }
                }

                final int currentMissed = (int) carrier.getAttributes().getAttribute("missedParcels");
                final int newMissed = currentMissed + missed;

                carrier.getAttributes().putAttribute("missedParcels", newMissed);

                // Get the current list of missed parcels and append the new missed deliveries
                @SuppressWarnings("unchecked")
                ArrayList<Id<CarrierService>> currentMissedList = (ArrayList<Id<CarrierService>>) carrier
                                .getAttributes().getAttribute("missedParcelsAsList");

                if (currentMissedList == null) {
                        currentMissedList = new ArrayList<>();
                }

                currentMissedList.addAll(missedDeliveries);

                carrier.getAttributes().putAttribute("missedParcelsAsList", currentMissedList);
        }

        /**
         * Adds vehicles to a carrier based on the carrier ID and closest hub.
         *
         * This method determines the closest hub for a carrier, sets the relevant
         * attributes, and adds vehicles to the carrier with appropriate start times
         * based on the provider.
         *
         * @param carrier The carrier to which vehicles will be added.
         * @param hubs    A map of hubs used to find the closest hub.
         */
        private void addCarrierVehiclesToCarrier(final Carrier carrier,
                        final Map<Id<Hub>, Hub> hubs) {

                // Find the closest hub for the carrier based on its ID and number of parcels
                Hub closestHub = getClosestHub(carrier, hubs);

                // Set hub attributes for the carrier
                carrier.getAttributes().putAttribute("hub", closestHub);
                carrier.getAttributes().putAttribute("hubId", closestHub.getId().toString());

                // Retrieve start and end times from configuration
                Object providerAttribute = carrier.getAttributes().getAttribute("provider");
                String provider = providerAttribute == null ? "default"
                                : providerAttribute.toString().toLowerCase(Locale.ROOT);

                int start = hagridConfig.getDeliveryStartTime(provider);
                int end = hagridConfig.getDeliveryEndTime(provider);
                int maxRouteDuration = hagridConfig.getMaxRouteDuration();

                List<String> vehicleSizes = hagridConfig.getVehicleSizesForProvider(provider);
                List<Integer> dispatchHours = hagridConfig.getDispatchHours(provider);

                // Add vehicles to the carrier respecting provider-specific deployment profiles
                if (!dispatchHours.isEmpty()) {
                        for (int startTime : dispatchHours) {
                                for (String sizeAlias : vehicleSizes) {
                                        CarrierVehicle vehicle = carrierVehicleFactory.createCEPVehicle(
                                                        closestHub.getLink(),
                                                        closestHub.getId().toString(), startTime, maxRouteDuration,
                                                        sizeAlias);
                                        CarriersUtils.addCarrierVehicle(carrier, vehicle);
                                }
                        }
                } else {
                        for (int startTime = start; startTime <= end; startTime++) {
                                for (String sizeAlias : vehicleSizes) {
                                        CarrierVehicle vehicle = carrierVehicleFactory.createCEPVehicle(
                                                        closestHub.getLink(),
                                                        closestHub.getId().toString(), startTime, maxRouteDuration,
                                                        sizeAlias);
                                        CarriersUtils.addCarrierVehicle(carrier, vehicle);
                                }
                        }
                }

                // Log the creation of the carrier and its services
                LOGGER.debug("Created Carrier {} with a number of {} services", carrier.getId(),
                                carrier.getServices().values().size());

        }

        /**
         * Finds the closest hub with enough capacity for the given number of parcels.
         *
         * @param carrier The carrier with services to be assigned to a hub.
         * @param hubList A map of hub IDs to Hub objects.
         * @return The closest Hub with sufficient capacity.
         */
        private static Hub getClosestHub(Carrier carrier, Map<Id<Hub>, Hub> hubList) {

                // Create a GeometryFactory for point creation
                GeometryFactory geometryFactory = new GeometryFactory();

                // Determine the number of parcels for the carrier
                int numberOfParcels = carrier.getServices().values().stream()
                                .mapToInt(CarrierService::getCapacityDemand)
                                .sum();
                carrier.getAttributes().putAttribute("numberOfParcels", numberOfParcels);

                // Extract the provider from the carrier's attributes
                String provider = (String) carrier.getAttributes().getAttribute("provider");

                // Find the closest hub
                Optional<Hub> closestHub = carrier.getServices().values().stream()
                                .flatMap(service -> {
                                        Coord serviceCoord = (Coord) service.getAttributes().getAttribute("coord");
                                        Point servicePoint = geometryFactory
                                                        .createPoint(new Coordinate(serviceCoord.getX(),
                                                                        serviceCoord.getY()));
                                        return hubList.values().stream()
                                                        .filter(hub -> hub.getProvider().contains(provider)
                                                                        && hub.hasCapacity(numberOfParcels))
                                                        .map(hub -> new Object[] { hub, servicePoint, hub.getCoord() });
                                })
                                .min(Comparator.comparingDouble(hubServiceCoord -> NetworkUtils.getEuclideanDistance(
                                                ((Point) hubServiceCoord[1]).getX(),
                                                ((Point) hubServiceCoord[1]).getY(),
                                                ((Coord) hubServiceCoord[2]).getX(),
                                                ((Coord) hubServiceCoord[2]).getY())))
                                .map(hubServiceCoord -> (Hub) hubServiceCoord[0]);

                // Throw an exception if no suitable hub is found
                if (!closestHub.isPresent()) {
                        throw new IllegalStateException("No suitable hub found for provider: " + provider);
                }

                Hub foundHub = closestHub.get();
                // Increase the assigned supply demand for the closest hub
                foundHub.increaseAssignedSupplyDemand(numberOfParcels);

                // Log the found hub
                LOGGER.debug("Found hub: {} for provider: {}", closestHub.get().getId(), provider);
                return foundHub;
        }

        /**
         * Validates the carriers by comparing the generated carrier services with the
         * original delivery summary.
         *
         * @param carriers The carriers to be validated.
         */
        private void validateCarriers(final Carriers carriers) {

                LOGGER.info("Validating carrier missed deliveries...");

                try {
                        validateMissedParcelDeliveries(carriers);
                } catch (ServiceCreationException e) {
                        LOGGER.error("Error validating missed deliveries", e);
                }
                LOGGER.info("Validation of carrier missed deliveries completed.");

                LOGGER.info("Validating carrier services...");

                final HAGRIDSummary summary = (HAGRIDSummary) HAGRIDUtils.getScenarioElementAs("summary", scenario);
                final Map<String, Double> deliveryRates = initializeDeliveryRate(); // Initialize delivery rates

                final int totalServices = carriers.getCarriers().values().stream()
                                .mapToInt(carrier -> carrier.getServices().size())
                                .sum();

                final int totalParcels = carriers.getCarriers().values().stream()
                                .flatMap(carrier -> carrier.getServices().values().stream())
                                .mapToInt(CarrierService::getCapacityDemand)
                                .sum();

                final int totalB2BServices = (int) carriers.getCarriers().values().stream()
                                .flatMap(carrier -> carrier.getServices().values().stream())
                                .filter(service -> ParcelType.B2B.toString()
                                                .equals(service.getAttributes().getAttribute("type").toString()))
                                .count();

                final int totalB2BParcels = carriers.getCarriers().values().stream()
                                .flatMap(carrier -> carrier.getServices().values().stream())
                                .filter(service -> ParcelType.B2B.toString()
                                                .equals(service.getAttributes().getAttribute("type").toString()))
                                .mapToInt(CarrierService::getCapacityDemand)
                                .sum();

                final int correctionFactor = summary.getCorrectionFactor(); // Get the correction factor from the
                                                                            // summary
                LOGGER.info(String.format("Correction factor for Delivery Size Adjustment: %d", correctionFactor));

                final StringBuilder validationErrors = new StringBuilder();

                if (totalServices != summary.getTotalDeliveries() + correctionFactor) {
                        validationErrors.append(
                                        String.format("Validation failed: Total deliveries do not match. Expected %d, but got %d.%n",
                                                        summary.getTotalDeliveries(),
                                                        totalServices - correctionFactor));
                }

                if (totalParcels != summary.getTotalParcels()) {
                        validationErrors.append(
                                        String.format("Validation failed: Total parcels do not match. Expected %d, but got %d.%n",
                                                        summary.getTotalParcels(), totalParcels));
                }

                if (totalB2BServices != summary.getTotalB2BDeliveries() + correctionFactor) {
                        validationErrors.append(
                                        String.format("Validation failed: Total B2B deliveries do not match. Expected %d, but got %d.%n",
                                                        summary.getTotalB2BDeliveries(),
                                                        totalB2BServices - correctionFactor));
                }

                if (totalB2BParcels != summary.getTotalB2BParcels()) {
                        validationErrors.append(
                                        String.format("Validation failed: Total B2B parcels do not match. Expected %d, but got %d.%n",
                                                        summary.getTotalB2BParcels(), totalB2BParcels));
                }

                // Validate capacityDemand matches the number of weights
                carriers.getCarriers().values().forEach(carrier -> {
                        final double[] totalServiceWeight = { 0.0 }; // Use an array to hold the weight

                        carrier.getServices().values().forEach(service -> {
                                String weightsString = (String) service.getAttributes().getAttribute("weights");
                                List<Double> weights = parseWeights(weightsString);
                                totalServiceWeight[0] += weights.stream().mapToDouble(Double::doubleValue).sum();
                                if (weights.size() != service.getCapacityDemand()) {
                                        validationErrors.append(
                                                        String.format("Validation failed: Service %s has capacityDemand %d but %d weights.%n",
                                                                        service.getId(), service.getCapacityDemand(),
                                                                        weights.size()));
                                }
                        });

                        LOGGER.debug("Total service weight for carrier {}: {}", carrier.getId(), totalServiceWeight[0]);
                });

                // Map to store the sum of parcels for each provider
                final Map<String, List<Double>> providerParcelRates = new HashMap<>();

                carriers.getCarriers().values().forEach(carrier -> {
                        final String provider = (String) carrier.getAttributes().getAttribute("provider");
                        final double totalCarrierParcels = carrier.getServices().values().stream()
                                        .mapToInt(CarrierService::getCapacityDemand)
                                        .sum();

                        // Handle the type conversion properly
                        final Object missedParcelsObj = carrier.getAttributes().getAttribute("missedParcels");
                        final double missedCarrierParcels;
                        if (missedParcelsObj instanceof Integer) {
                                missedCarrierParcels = ((Integer) missedParcelsObj).doubleValue();
                        } else if (missedParcelsObj instanceof Double) {
                                missedCarrierParcels = (Double) missedParcelsObj;
                        } else {
                                missedCarrierParcels = 0.0;
                        }

                        if (totalCarrierParcels > 0) {
                                double missedParcelRate = missedCarrierParcels / totalCarrierParcels;
                                providerParcelRates.computeIfAbsent(provider, k -> new ArrayList<>())
                                                .add(missedParcelRate);
                        }
                });

                // Log the missed parcels for each provider and compare with the rate
                providerParcelRates.forEach((provider, rates) -> {
                        double averageMissedRate = 1
                                        - rates.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
                        LOGGER.info(String.format("Missed parcels for provider %s: average rate %.2f%%", provider,
                                        averageMissedRate * 100));

                        double expectedRate = deliveryRates.get(provider);
                        if ("amazon".equals(provider)) {
                                // No adjustment for Amazon
                        } else if ("ups".equals(provider) || "fedex".equals(provider) || "dpd".equals(provider)) {
                                expectedRate += 3;
                        } else {
                                expectedRate += 2;
                        }
                        LOGGER.info(String.format("Expected rate for provider %s: %.2f%%", provider, expectedRate));
                });

                // Log the overall weight statistics and their distribution by provider
                StringBuilder logBuilder = new StringBuilder();
                logBuilder.append("=== Delivery Statistics by Provider ===\n");

                long totalServiceCount = 0;
                long totalB2BServiceCount = 0;
                long totalParcelCount = 0;
                long totalB2BParcelCount = 0;
                double totalWeight = 0.0;
                double totalB2BWeight = 0.0;

                Map<String, List<CarrierService>> servicesByProvider = carriers.getCarriers().values().stream()
                                .flatMap(carrier -> carrier.getServices().values().stream())
                                .collect(Collectors.groupingBy(
                                                service -> (String) service.getAttributes().getAttribute("provider")));

                for (Map.Entry<String, List<CarrierService>> entry : servicesByProvider.entrySet()) {
                        String provider = entry.getKey();
                        List<CarrierService> services = entry.getValue();

                        long providerServiceCount = services.size();
                        long providerB2BServiceCount = services.stream()
                                        .filter(service -> ParcelType.B2B.toString()
                                                        .equals(service.getAttributes().getAttribute("type")
                                                                        .toString()))
                                        .count();
                        long providerParcelCount = services.stream()
                                        .mapToLong(CarrierService::getCapacityDemand)
                                        .sum();
                        long providerB2BParcelCount = services.stream()
                                        .filter(service -> ParcelType.B2B.toString()
                                                        .equals(service.getAttributes().getAttribute("type")
                                                                        .toString()))
                                        .mapToLong(CarrierService::getCapacityDemand)
                                        .sum();

                        double providerTotalWeight = services.stream()
                                        .flatMapToDouble(service -> parseWeights(
                                                        (String) service.getAttributes().getAttribute("weights"))
                                                        .stream()
                                                        .mapToDouble(Double::doubleValue))
                                        .sum();
                        double providerB2BWeight = services.stream()
                                        .filter(service -> ParcelType.B2B.toString()
                                                        .equals(service.getAttributes().getAttribute("type")
                                                                        .toString()))
                                        .flatMapToDouble(service -> parseWeights(
                                                        (String) service.getAttributes().getAttribute("weights"))
                                                        .stream()
                                                        .mapToDouble(Double::doubleValue))
                                        .sum();
                        double providerAverageWeight = providerParcelCount == 0 ? 0
                                        : providerTotalWeight / providerParcelCount;
                        double providerAverageB2BWeight = providerB2BParcelCount == 0 ? 0
                                        : providerB2BWeight / providerB2BParcelCount;

                        totalServiceCount += providerServiceCount;
                        totalB2BServiceCount += providerB2BServiceCount;
                        totalParcelCount += providerParcelCount;
                        totalB2BParcelCount += providerB2BParcelCount;
                        totalWeight += providerTotalWeight;
                        totalB2BWeight += providerB2BWeight;

                        logBuilder.append(String.format(
                                        "Provider: %s\n  Total Services     : %,d\n  B2B Services       : %,d\n  Total Parcels      : %,d\n  B2B Parcels        : %,d\n  B2B Service Ratio  : %.2f%%\n  B2B Parcel Ratio   : %.2f%%\n  Total Weight       : %.2f\n  Total B2B Weight   : %.2f\n  Average Weight     : %.2f\n  Average B2B Weight : %.2f\n\n",
                                        provider, providerServiceCount, providerB2BServiceCount, providerParcelCount,
                                        providerB2BParcelCount,
                                        (double) providerB2BServiceCount / providerServiceCount * 100,
                                        (double) providerB2BParcelCount / providerParcelCount * 100,
                                        providerTotalWeight, providerB2BWeight, providerAverageWeight,
                                        providerAverageB2BWeight));
                }

                double totalAverageWeight = totalParcelCount == 0 ? 0 : totalWeight / totalParcelCount;
                double totalAverageB2BWeight = totalB2BParcelCount == 0 ? 0 : totalB2BWeight / totalB2BParcelCount;

                logBuilder.append("=== Total Statistics ===\n");
                logBuilder.append(String.format(
                                "Total Services     : %,d\nTotal B2B Services : %,d\nTotal Parcels      : %,d\nTotal B2B Parcels  : %,d\nTotal Weight       : %.2f\nTotal B2B Weight   : %.2f\nAverage Weight     : %.2f\nAverage B2B Weight : %.2f\n",
                                totalServiceCount, totalB2BServiceCount, totalParcelCount, totalB2BParcelCount,
                                totalWeight, totalB2BWeight, totalAverageWeight, totalAverageB2BWeight));

                LOGGER.info(logBuilder.toString());

                if (validationErrors.length() > 0) {
                        throw new IllegalStateException(validationErrors.toString());
                } else {
                        LOGGER.info("Validation passed: All carrier services match the original deliveries summary.");
                        LOGGER.info(String.format("Total deliveries match: %d.", totalServices - correctionFactor));
                        LOGGER.info(String.format("Total parcels match: %d.", totalParcels));
                        LOGGER.info(String.format("Total B2B deliveries match: %d.",
                                        totalB2BServices - correctionFactor));
                        LOGGER.info(String.format("Total B2B parcels match: %d.", totalB2BParcels));
                }

                LOGGER.info("Validation of carrier completed.");
        }

        /**
         * Validates that the assigned supply demand to all hubs matches the total
         * parcels.
         *
         * @param carriers A map of carrier IDs to Carrier objects.
         * @param hubs     A map of hub IDs to Hub objects.
         * @throws IllegalStateException If the validation fails.
         */
        private void validateSupplyDemand(Carriers carriers, Map<Id<Hub>, Hub> hubs) {
                // Log the start of the validation process
                LOGGER.info("Starting validation of supply demand...");

                // Calculate the total parcels across all carriers
                final int totalParcels = carriers.getCarriers().values().stream()
                                .flatMap(carrier -> carrier.getServices().values().stream())
                                .mapToInt(CarrierService::getCapacityDemand)
                                .sum();

                // Calculate the total assigned supply demand across all hubs
                final int totalAssignedSupplyDemand = hubs.values().stream()
                                .mapToInt(Hub::getAssignedSupplyDemand)
                                .sum();

                // Log the assigned supply demand for each hub
                hubs.forEach((hubId, hub) -> {
                        int assignedSupplyDemand = hub.getAssignedSupplyDemand();
                        LOGGER.info("Hub ID: {}, Assigned Supply Demand: {}", hubId, assignedSupplyDemand);
                });

                // Log the validation result
                LOGGER.info("Total Parcels: {}, Total Assigned Supply Demand: {}", totalParcels,
                                totalAssignedSupplyDemand);

                // Throw an exception if the total assigned supply demand does not match the
                // total parcels
                if (totalParcels != totalAssignedSupplyDemand) {
                        throw new IllegalStateException("Validation failed: Total parcels (" + totalParcels +
                                        ") do not match total assigned supply demand (" + totalAssignedSupplyDemand
                                        + ").");
                }

                // Log successful validation
                LOGGER.info("Validation successful: Total parcels match total assigned supply demand.");
        }

        /**
         * Validates that the created services match the original deliveries.
         *
         * @param carrierDeliveries The original deliveries.
         * @param createdServices   The created carrier services.
         * @throws ServiceCreationException if validation fails.
         */
        private void validateCarrierServices(final ArrayList<Delivery> carrierDeliveries,
                        final List<CarrierService> createdServices) throws ServiceCreationException {
                Map<String, Double> originalWeightsMap = carrierDeliveries.stream()
                                .collect(Collectors.toMap(
                                                d -> String.format("%s_%s", d.getParcelType(), d.getCoordinate()),
                                                d -> d.getIndividualWeights().stream().mapToDouble(Double::doubleValue)
                                                                .sum(),
                                                Double::sum));

                Map<String, Double> createdWeightsMap = createdServices.stream()
                                .collect(Collectors.toMap(
                                                s -> String.format("%s_%s", s.getAttributes().getAttribute("type"),
                                                                s.getAttributes().getAttribute("coord")),
                                                s -> Arrays.stream(((String) s.getAttributes().getAttribute("weights"))
                                                                .split(";"))
                                                                .mapToDouble(Double::parseDouble).sum(),
                                                Double::sum));

                for (Map.Entry<String, Double> entry : originalWeightsMap.entrySet()) {
                        String key = entry.getKey();
                        double originalWeight = entry.getValue();
                        double createdWeight = createdWeightsMap.getOrDefault(key, 0.0);

                        double epsilon = 1e-9; // Epsilon for floating point comparison

                        if (Math.abs(originalWeight - createdWeight) > epsilon) {
                                throw new ServiceCreationException("Validation failed for key: " + key +
                                                ". Original weight: " + originalWeight + ", Created weight: "
                                                + createdWeight, null);
                        }
                }

                LOGGER.debug("Validation passed for all deliveries and created services.");
        }

        /**
         * Validates that the missed deliveries match the expected missed deliveries for
         * each carrier.
         *
         * @param carriers The list of carriers to validate.
         * @throws ServiceCreationException if validation fails.
         */
        private void validateMissedParcelDeliveries(Carriers carriers) throws ServiceCreationException {
                for (Carrier carrier : carriers.getCarriers().values()) {
                        int expectedMissedDeliveries = (int) carrier.getAttributes().getAttribute("missedParcels");
                        @SuppressWarnings("unchecked")
                        List<Id<CarrierService>> missedDeliveries = (List<Id<CarrierService>>) carrier.getAttributes()
                                        .getAttribute("missedParcelsAsList");
                        int missedSize = 0; // Initialize missedSize to 0
                        if (missedDeliveries != null) {
                                missedSize = missedDeliveries.size();
                        }

                        if (missedSize != expectedMissedDeliveries) {
                                throw new ServiceCreationException("Validation failed for carrier: " + carrier.getId() +
                                                ". Expected missed parcel deliveries: " + expectedMissedDeliveries +
                                                ", Actual missed parcel deliveries: " + missedDeliveries.size(), null);
                        }
                        carrier.getAttributes().putAttribute("missedParcelDeliveriesAsString",
                                        missedDeliveries.toString());
                }

                LOGGER.info("Validation passed for all carriers and their missed parcel deliveries.");
        }

        /**
         * Parses a semicolon-separated string of weights into a list of Double values.
         *
         * This method takes a string representation of weights, where individual
         * weights
         * are separated by semicolons, and converts it into a list of Double objects.
         * If
         * the input string is null or empty, the method returns an empty list.
         *
         * @param weightsString A string containing weights separated by semicolons.
         * @return A list of Double objects representing the weights.
         */
        private List<Double> parseWeights(String weightsString) {
                // Check if the input string is null or empty
                if (weightsString == null || weightsString.isEmpty()) {
                        // Return an empty list if the input string is null or empty
                        return new ArrayList<>();
                }

                // Split the input string by semicolons, convert each segment to a Double,
                // and collect the results into a list
                return Arrays.stream(weightsString.split(";"))
                                .map(Double::valueOf)
                                .collect(Collectors.toList());
        }
}
