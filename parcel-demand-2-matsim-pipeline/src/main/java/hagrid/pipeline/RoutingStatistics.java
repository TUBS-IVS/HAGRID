package hagrid.pipeline;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.DoubleSummaryStatistics;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierPlan;
import org.matsim.freight.carriers.CarrierService;
import org.matsim.freight.carriers.CarrierVehicle;
import org.matsim.freight.carriers.Carriers;
import org.matsim.freight.carriers.CarriersUtils;
import org.matsim.freight.carriers.ScheduledTour;
import org.matsim.freight.carriers.Tour;
import org.matsim.api.core.v01.population.Route;
import org.matsim.core.population.routes.NetworkRoute;

import hagrid.utils.general.HAGRIDUtils;

/**
 * Routing statistics collected from carrier plans after JSprit routing completes.
 * <p>
 * Iterates over all routed carriers and their {@link ScheduledTour}s, computing
 * distances from network link lengths, tour counts, vehicle usage, utilization,
 * costs and timing — all without requiring a MATSim simulation run.
 * <p>
 * Results are structured for both the scenario summary (aggregated + per-provider)
 * and a per-carrier CSV export.
 *
 * @author HAGRID Team
 */
public final class RoutingStatistics {

    private static final Logger LOGGER = LogManager.getLogger(RoutingStatistics.class);

    // ── Per-carrier detail records ──────────────────────────────────────────
    private final List<CarrierDetail> carrierDetails;

    // ── Aggregated totals ───────────────────────────────────────────────────
    private final int totalCarriers;
    private final int totalVehicles;
    private final int totalTours;
    private final double totalDistanceKm;
    private final double totalRoutingTimeSeconds;
    private final double avgTourDistanceKm;
    private final double avgRoutingTimePerCarrier;
    private final double minRoutingTimeCarrier;
    private final double maxRoutingTimeCarrier;
    private final String minRoutingTimeCarrierId;
    private final String maxRoutingTimeCarrierId;

    // ── Per-provider aggregation ────────────────────────────────────────────
    private final Map<String, ProviderRoutingStats> providerStats;

    // ── Vehicle class breakdown ─────────────────────────────────────────────
    private final Map<String, VehicleClassStats> vehicleClassStats;

    // =====================================================================
    //  DTOs
    // =====================================================================

    /** Per-carrier routing detail — one row in the CSV. */
    public record CarrierDetail(
            String carrierId,
            String provider,
            String plz,
            int services,
            int parcels,
            int b2bParcels,
            int b2cParcels,
            int missedParcels,
            int vehicles,
            int tours,
            double totalDistanceKm,
            double avgTourDistanceKm,
            double minStartTime,
            double maxEndTime,
            double tourDurationHours,
            double fixCosts,
            double distanceCosts,
            double timeCosts,
            double totalCosts,
            double utilization,         // parcels / sum(vehicle capacities)
            double routingTimeSeconds,
            double score,               // MATSim plan score (NaN if not yet scored)
            String vehicleClasses       // comma-separated vehicle type IDs
    ) {
        /** Delivery quota: (parcels - missedParcels) / parcels */
        public double deliveryQuota() {
            return parcels > 0 ? (double) (parcels - missedParcels) / parcels : 1.0;
        }
    }

    /** Per-provider aggregated routing statistics. */
    public record ProviderRoutingStats(
            int carriers,
            int vehicles,
            int tours,
            int totalParcels,
            int totalMissedParcels,
            double totalDistanceKm,
            double avgTourDistanceKm,
            double minStartTime,
            double maxEndTime,
            double totalCosts,
            double totalFixCosts,
            double totalDistanceCosts,
            double totalTimeCosts,
            double deliveryQuota,
            double avgUtilization,
            double minUtilization,
            double maxUtilization
    ) {}

    /** Vehicle class (type) breakdown. */
    public record VehicleClassStats(
            String vehicleTypeId,
            int count,
            double shareOfFleet,        // count / totalVehicles
            double totalDistanceKm,
            double shareOfDistance,      // km / totalKm
            double capacity
    ) {}

    // =====================================================================
    //  Factory
    // =====================================================================

    /**
     * Collects routing statistics from the scenario after the routing module completes.
     *
     * @param scenario the MATSim scenario with routed carriers and network
     * @return populated statistics, or {@code null} if no routed carriers found
     */
    public static RoutingStatistics collectFrom(Scenario scenario) {
        Network network = scenario.getNetwork();
        if (network == null || network.getLinks().isEmpty()) {
            LOGGER.warn("No network available — cannot collect routing statistics.");
            return null;
        }

        Carriers carriers;
        try {
            carriers = HAGRIDUtils.getScenarioElementAs("carriers", scenario);
        } catch (Exception e) {
            LOGGER.warn("No carriers in scenario: {}", e.getMessage());
            return null;
        }

        // Check if any carrier actually has a routed plan
        boolean anyRouted = carriers.getCarriers().values().stream()
                .anyMatch(c -> c.getSelectedPlan() != null
                        && !c.getSelectedPlan().getScheduledTours().isEmpty());
        if (!anyRouted) {
            LOGGER.info("No routed carriers found — skipping routing statistics.");
            return null;
        }

        return new RoutingStatistics(carriers, network);
    }

    // =====================================================================
    //  Private constructor — computes everything
    // =====================================================================

    private RoutingStatistics(Carriers carriers, Network network) {

        List<CarrierDetail> details = new ArrayList<>();

        // ── Vehicle class accumulators ───────────────────────────────────
        // vehicleTypeId → (count, totalDistanceMeters)
        Map<String, int[]> vehClassCount = new LinkedHashMap<>();
        Map<String, double[]> vehClassKm = new LinkedHashMap<>();
        Map<String, Double> vehClassCapacity = new LinkedHashMap<>();

        int totalVeh = 0;
        int totalTrs = 0;
        double totalDistM = 0;
        double totalRoutingSec = 0;

        for (Carrier carrier : carriers.getCarriers().values()) {
            CarrierPlan plan = carrier.getSelectedPlan();
            if (plan == null || plan.getScheduledTours().isEmpty()) continue;

            String carrierId = carrier.getId().toString();
            String provider = attrString(carrier, "provider", "unknown");
            String plz = attrString(carrier, "plz", "");
            int missedParcels = attrInt(carrier, "missedParcels");

            // ── Count services/parcels ───────────────────────────────────
            int services = carrier.getServices().size();
            int parcels = 0;
            int b2b = 0;
            int b2c = 0;
            for (CarrierService svc : carrier.getServices().values()) {
                int cap = svc.getCapacityDemand();
                parcels += cap;
                Object b2bObj = svc.getAttributes().getAttribute("b2b");
                Object b2cObj = svc.getAttributes().getAttribute("b2c");
                if (b2bObj != null && b2cObj != null) {
                    b2b += parseIntSafe(b2bObj);
                    b2c += parseIntSafe(b2cObj);
                } else {
                    Object typeObj = svc.getAttributes().getAttribute("type");
                    if (typeObj != null) {
                        String typeName = typeObj.toString();
                        if (typeName.contains("B2B")) b2b += cap;
                        else if (typeName.contains("B2C")) b2c += cap;
                    }
                }
            }

            // ── Iterate scheduled tours ──────────────────────────────────
            Collection<ScheduledTour> scheduledTours = plan.getScheduledTours();
            int carrierTours = scheduledTours.size();
            double carrierDistM = 0;
            double carrierMinStart = Double.MAX_VALUE;
            double carrierMaxEnd = Double.MIN_VALUE;
            double totalVehicleCapacity = 0;

            // Track vehicles (unique by ID)
            Map<String, String> vehicleTypeIds = new LinkedHashMap<>();  // vehId → typeId

            for (ScheduledTour st : scheduledTours) {
                double departure = st.getDeparture();
                carrierMinStart = Math.min(carrierMinStart, departure);

                CarrierVehicle vehicle = st.getVehicle();
                String vehId = vehicle.getId().toString();
                String typeId = vehicle.getType().getId().toString();
                vehicleTypeIds.put(vehId, typeId);
                totalVehicleCapacity += vehicle.getType().getCapacity().getOther();

                // Accumulate vehicle class info
                vehClassCount.computeIfAbsent(typeId, k -> new int[]{0})[0]++;
                vehClassCapacity.putIfAbsent(typeId, vehicle.getType().getCapacity().getOther());

                // ── Iterate tour elements ────────────────────────────────
                double tourDistM = 0;
                double tourEndTime = departure;

                for (Tour.TourElement element : st.getTour().getTourElements()) {
                    if (element instanceof Tour.Leg leg) {
                        Route route = leg.getRoute();
                        if (route instanceof NetworkRoute nRoute) {
                            // Start link
                            Link startLink = network.getLinks().get(nRoute.getStartLinkId());
                            if (startLink != null) tourDistM += startLink.getLength();
                            // Intermediate links
                            for (Id<Link> linkId : nRoute.getLinkIds()) {
                                Link link = network.getLinks().get(linkId);
                                if (link != null) tourDistM += link.getLength();
                            }
                            // End link
                            Link endLink = network.getLinks().get(nRoute.getEndLinkId());
                            if (endLink != null) tourDistM += endLink.getLength();
                        }
                        // Track end time
                        double travelTime = leg.getExpectedTransportTime();
                        if (!Double.isNaN(travelTime) && travelTime > 0) {
                            tourEndTime += travelTime;
                        }
                    } else if (element instanceof Tour.TourActivity activity) {
                        tourEndTime = Math.max(tourEndTime, activity.getExpectedArrival());
                        tourEndTime += activity.getDuration();
                    }
                }

                carrierDistM += tourDistM;
                carrierMaxEnd = Math.max(carrierMaxEnd, tourEndTime);

                // Per-vehicle-type distance
                vehClassKm.computeIfAbsent(typeId, k -> new double[]{0})[0] += tourDistM;
            }

            if (carrierMinStart == Double.MAX_VALUE) carrierMinStart = 0;
            if (carrierMaxEnd == Double.MIN_VALUE) carrierMaxEnd = 0;

            int carrierVehicles = vehicleTypeIds.size();

            // ── Costs (from vehicle type cost parameters) ────────────────
            double fixCosts = 0;
            double distCosts = 0;
            double timeCosts = 0;
            for (ScheduledTour st : scheduledTours) {
                var costInfo = st.getVehicle().getType().getCostInformation();
                if (!st.getTour().getTourElements().isEmpty()) {
                    fixCosts += costInfo.getFixedCosts();
                }
            }
            // Distance and time costs: compute from per-tour data
            for (ScheduledTour st : scheduledTours) {
                var costInfo = st.getVehicle().getType().getCostInformation();
                double perMeter = costInfo.getCostsPerMeter();
                double perSecond = costInfo.getCostsPerSecond();

                double tourDist = 0;
                double tourTime = 0;
                for (Tour.TourElement element : st.getTour().getTourElements()) {
                    if (element instanceof Tour.Leg leg && leg.getRoute() instanceof NetworkRoute nRoute) {
                        Link sL = network.getLinks().get(nRoute.getStartLinkId());
                        if (sL != null) tourDist += sL.getLength();
                        for (Id<Link> lid : nRoute.getLinkIds()) {
                            Link l = network.getLinks().get(lid);
                            if (l != null) tourDist += l.getLength();
                        }
                        Link eL = network.getLinks().get(nRoute.getEndLinkId());
                        if (eL != null) tourDist += eL.getLength();
                        tourTime += leg.getExpectedTransportTime();
                    }
                }
                distCosts += tourDist * perMeter;
                timeCosts += Math.max(0, tourTime) * perSecond;
            }
            double totalCosts = fixCosts + distCosts + timeCosts;

            // ── Utilization ──────────────────────────────────────────────
            double utilization = totalVehicleCapacity > 0
                    ? (double) parcels / totalVehicleCapacity : 0.0;

            // ── Routing time (from jsprit computation time attribute) ────
            double routingTime = 0;
            try {
                double raw = CarriersUtils.getJspritComputationTime(carrier);
                if (raw != Integer.MIN_VALUE) routingTime = raw;
            } catch (Exception ignored) {
                // may not be set
            }

            // ── Vehicle classes string ───────────────────────────────────
            String vehicleClassesStr = vehicleTypeIds.values().stream()
                    .distinct()
                    .sorted()
                    .collect(Collectors.joining(","));

            double carrierDistKm = carrierDistM / 1000.0;
            double avgTourKm = carrierTours > 0 ? carrierDistKm / carrierTours : 0;
            double durationHours = (carrierMaxEnd - carrierMinStart) / 3600.0;

            // ── Plan score (set by MATSim simulation; NaN if not yet scored) ─
            double planScore = (plan.getScore() != null) ? plan.getScore() : Double.NaN;

            CarrierDetail detail = new CarrierDetail(
                    carrierId, provider, plz,
                    services, parcels, b2b, b2c, missedParcels,
                    carrierVehicles, carrierTours,
                    carrierDistKm, avgTourKm,
                    carrierMinStart, carrierMaxEnd, durationHours,
                    fixCosts, distCosts, timeCosts, totalCosts,
                    utilization, routingTime, planScore, vehicleClassesStr);

            details.add(detail);

            totalVeh += carrierVehicles;
            totalTrs += carrierTours;
            totalDistM += carrierDistM;
            totalRoutingSec += routingTime;
        }

        // Sort details by provider, then carrierId
        details.sort(Comparator.comparing(CarrierDetail::provider)
                .thenComparing(CarrierDetail::carrierId));

        this.carrierDetails = Collections.unmodifiableList(details);
        this.totalCarriers = details.size();
        this.totalVehicles = totalVeh;
        this.totalTours = totalTrs;
        this.totalDistanceKm = totalDistM / 1000.0;
        this.totalRoutingTimeSeconds = totalRoutingSec;
        this.avgTourDistanceKm = totalTrs > 0 ? this.totalDistanceKm / totalTrs : 0;
        this.avgRoutingTimePerCarrier = details.size() > 0 ? totalRoutingSec / details.size() : 0;

        // Min/Max routing time carrier
        CarrierDetail minRt = details.stream().min(Comparator.comparingDouble(CarrierDetail::routingTimeSeconds)).orElse(null);
        CarrierDetail maxRt = details.stream().max(Comparator.comparingDouble(CarrierDetail::routingTimeSeconds)).orElse(null);
        this.minRoutingTimeCarrier = minRt != null ? minRt.routingTimeSeconds() : 0;
        this.maxRoutingTimeCarrier = maxRt != null ? maxRt.routingTimeSeconds() : 0;
        this.minRoutingTimeCarrierId = minRt != null ? minRt.carrierId() : "";
        this.maxRoutingTimeCarrierId = maxRt != null ? maxRt.carrierId() : "";

        // ── Per-provider aggregation ─────────────────────────────────────
        Map<String, ProviderRoutingStats> provMap = new TreeMap<>();
        Map<String, List<CarrierDetail>> byProvider = details.stream()
                .collect(Collectors.groupingBy(CarrierDetail::provider, TreeMap::new, Collectors.toList()));

        for (var entry : byProvider.entrySet()) {
            List<CarrierDetail> pDetails = entry.getValue();
            int pCarriers = pDetails.size();
            int pVehicles = pDetails.stream().mapToInt(CarrierDetail::vehicles).sum();
            int pTours = pDetails.stream().mapToInt(CarrierDetail::tours).sum();
            int pParcels = pDetails.stream().mapToInt(CarrierDetail::parcels).sum();
            int pMissed = pDetails.stream().mapToInt(CarrierDetail::missedParcels).sum();
            double pDistKm = pDetails.stream().mapToDouble(CarrierDetail::totalDistanceKm).sum();
            double pAvgTourKm = pTours > 0 ? pDistKm / pTours : 0;
            double pMinStart = pDetails.stream().mapToDouble(CarrierDetail::minStartTime).min().orElse(0);
            double pMaxEnd = pDetails.stream().mapToDouble(CarrierDetail::maxEndTime).max().orElse(0);
            double pTotalCosts = pDetails.stream().mapToDouble(CarrierDetail::totalCosts).sum();
            double pFixCosts = pDetails.stream().mapToDouble(CarrierDetail::fixCosts).sum();
            double pDistCosts = pDetails.stream().mapToDouble(CarrierDetail::distanceCosts).sum();
            double pTimeCosts = pDetails.stream().mapToDouble(CarrierDetail::timeCosts).sum();
            double pDeliveryQuota = pParcels > 0 ? (double) (pParcels - pMissed) / pParcels : 1.0;

            DoubleSummaryStatistics utilStats = pDetails.stream()
                    .mapToDouble(CarrierDetail::utilization)
                    .summaryStatistics();

            provMap.put(entry.getKey(), new ProviderRoutingStats(
                    pCarriers, pVehicles, pTours, pParcels, pMissed,
                    pDistKm, pAvgTourKm, pMinStart, pMaxEnd,
                    pTotalCosts, pFixCosts, pDistCosts, pTimeCosts,
                    pDeliveryQuota, utilStats.getAverage(),
                    utilStats.getMin(), utilStats.getMax()));
        }
        this.providerStats = Collections.unmodifiableMap(provMap);

        // ── Vehicle class breakdown ──────────────────────────────────────
        Map<String, VehicleClassStats> vehMap = new LinkedHashMap<>();
        for (var entry : vehClassCount.entrySet()) {
            String typeId = entry.getKey();
            int count = entry.getValue()[0];
            double distM = vehClassKm.getOrDefault(typeId, new double[]{0})[0];
            double distKm = distM / 1000.0;
            double cap = vehClassCapacity.getOrDefault(typeId, 0.0);
            vehMap.put(typeId, new VehicleClassStats(
                    typeId, count,
                    totalVeh > 0 ? (double) count / totalVeh : 0,
                    distKm,
                    this.totalDistanceKm > 0 ? distKm / this.totalDistanceKm : 0,
                    cap));
        }
        // Sort by count descending
        Map<String, VehicleClassStats> sorted = vehMap.entrySet().stream()
                .sorted(Map.Entry.<String, VehicleClassStats>comparingByValue(
                        Comparator.comparingInt(VehicleClassStats::count).reversed()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (e1, e2) -> e1, LinkedHashMap::new));
        this.vehicleClassStats = Collections.unmodifiableMap(sorted);

        LOGGER.info("Routing statistics collected: {} carriers, {} tours, {} km total",
                totalCarriers, totalTours, String.format(Locale.US, "%.1f", totalDistanceKm));
    }

    // =====================================================================
    //  CSV Export
    // =====================================================================

    /**
     * Writes a per-carrier routing results CSV.
     *
     * @param csvPath the output CSV path
     */
    public void writeCsv(Path csvPath) throws IOException {
        Files.createDirectories(csvPath.getParent());

        String header = String.join(";",
                "carrierId", "provider", "plz",
                "services", "parcels", "b2bParcels", "b2cParcels", "missedParcels",
                "vehicles", "tours",
                "totalDistanceKm", "avgTourDistanceKm",
                "minStartTime", "maxEndTime", "tourDurationHours",
                "fixCosts", "distanceCosts", "timeCosts", "totalCosts",
                "utilization", "deliveryQuota",
                "routingTimeSeconds", "score", "vehicleClasses"
        ) + System.lineSeparator();

        StringBuilder sb = new StringBuilder(header);
        for (CarrierDetail d : carrierDetails) {
            sb.append(String.join(";",
                    escape(d.carrierId()), escape(d.provider()), escape(d.plz()),
                    str(d.services()), str(d.parcels()), str(d.b2bParcels()), str(d.b2cParcels()), str(d.missedParcels()),
                    str(d.vehicles()), str(d.tours()),
                    fmt(d.totalDistanceKm()), fmt(d.avgTourDistanceKm()),
                    formatTime(d.minStartTime()), formatTime(d.maxEndTime()), fmt(d.tourDurationHours()),
                    fmt(d.fixCosts()), fmt(d.distanceCosts()), fmt(d.timeCosts()), fmt(d.totalCosts()),
                    fmt(d.utilization()), fmt(d.deliveryQuota()),
                    fmt(d.routingTimeSeconds()), fmtScore(d.score()), escape(d.vehicleClasses())
            )).append(System.lineSeparator());
        }

        Files.writeString(csvPath, sb.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        LOGGER.info("Wrote carrier routing results CSV: {} ({} rows)", csvPath.toAbsolutePath(), carrierDetails.size());
    }

    private static String escape(String s) {
        if (s == null) return "";
        if (s.contains(";") || s.contains("\"")) return "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
    }

    private static String str(int v) { return Integer.toString(v); }

    private static String fmt(double v) { return String.format(Locale.ROOT, "%.2f", v); }

    /** Format score — empty string for NaN (not yet scored). */
    private static String fmtScore(double v) { return Double.isNaN(v) ? "" : fmt(v); }

    /** Format seconds-from-midnight as HH:mm:ss */
    private static String formatTime(double seconds) {
        if (seconds <= 0 || Double.isNaN(seconds) || seconds > 172800) return "";
        int totalSec = (int) seconds;
        int h = totalSec / 3600;
        int m = (totalSec % 3600) / 60;
        int s = totalSec % 60;
        return String.format("%02d:%02d:%02d", h, m, s);
    }

    // =====================================================================
    //  Helpers
    // =====================================================================

    private static String attrString(Carrier carrier, String key, String defaultVal) {
        Object val = carrier.getAttributes().getAttribute(key);
        return val != null ? val.toString() : defaultVal;
    }

    private static int attrInt(Carrier carrier, String key) {
        Object val = carrier.getAttributes().getAttribute(key);
        if (val instanceof Integer i) return i;
        if (val instanceof Number n) return n.intValue();
        return 0;
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

    public List<CarrierDetail> getCarrierDetails() { return carrierDetails; }
    public int getTotalCarriers() { return totalCarriers; }
    public int getTotalVehicles() { return totalVehicles; }
    public int getTotalTours() { return totalTours; }
    public double getTotalDistanceKm() { return totalDistanceKm; }
    public double getTotalRoutingTimeSeconds() { return totalRoutingTimeSeconds; }
    public double getAvgTourDistanceKm() { return avgTourDistanceKm; }
    public double getAvgRoutingTimePerCarrier() { return avgRoutingTimePerCarrier; }
    public double getMinRoutingTimeCarrier() { return minRoutingTimeCarrier; }
    public double getMaxRoutingTimeCarrier() { return maxRoutingTimeCarrier; }
    public String getMinRoutingTimeCarrierId() { return minRoutingTimeCarrierId; }
    public String getMaxRoutingTimeCarrierId() { return maxRoutingTimeCarrierId; }
    public Map<String, ProviderRoutingStats> getProviderStats() { return providerStats; }
    public Map<String, VehicleClassStats> getVehicleClassStats() { return vehicleClassStats; }
}
