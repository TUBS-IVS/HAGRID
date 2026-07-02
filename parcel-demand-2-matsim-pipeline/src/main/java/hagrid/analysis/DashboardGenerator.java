package hagrid.analysis;

import hagrid.analysis.CarrierXmlParser.*;
import hagrid.analysis.FreightEventHandler.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Generates a comprehensive single-file HTML dashboard from HAGRID simulation.
 *
 * <p><b>v3</b> — Vehicle-centric analysis aligned with the Python
 * {@code hagrid_output_analysis} pipeline.  Major sections:</p>
 * <ol>
 *   <li>KPI strip (fleet, parcels, utilisation, costs, distances)</li>
 *   <li>Interactive tour map — colour by provider <em>or</em> carrier,
 *       with vehicle picker and stop overlay</li>
 *   <li>Vehicle utilisation &amp; load factor analysis</li>
 *   <li>Tour timing — departure / arrival / duration distributions</li>
 *   <li>Distance distributions (histogram + box-style)</li>
 *   <li>Provider / vehicle-type breakdown charts</li>
 *   <li>Cost analysis by provider</li>
 *   <li>Network traffic heat-map (delivery / supply / all)</li>
 * </ol>
 *
 * <p>Uses Leaflet.js, Chart.js 4, and modern CSS (glassmorphism, dark mode).
 * All JS/CSS is inlined or loaded from CDN.  Zero server-side deps.</p>
 */
public class DashboardGenerator {

    private static final Logger LOG = LogManager.getLogger(DashboardGenerator.class);

    private final String runId;
    private final Network network;
    private final FreightEventHandler eventHandler;
    private final List<ParsedCarrier> carriers;
    private final Map<String, Double> vehicleTypeCapacities;
    private final Map<String, Double> vehicleTypeFixedCosts;
    private final Map<String, Double> vehicleTypeCostsPerKm;
    private final Path outputDir;

    // ── Low-utilisation filter ────────────────────────────────────────
    /** Vehicles with load factor below this threshold are excluded from analysis (default 5 %). */
    private double lowUtilThreshold = 0.05;
    /** Event-vehicle-IDs that were excluded due to low utilisation. */
    private Set<String> excludedLowUtilVehicles = Set.of();

    // ── Pre-computed event-based maps (filled once at generate() start) ──
    /** eventVehicleId → total tour km (sum of network link lengths for all link-leave events) */
    private Map<String, Double> evtTourKm;
    /** eventVehicleId → depot departure time (seconds of day) */
    private Map<String, Double> evtDepSec;
    /** eventVehicleId → depot arrival time (seconds of day) */
    private Map<String, Double> evtArrSec;
    /** eventVehicleId → actual total service duration (sum of paired service-start/end diffs) */
    private Map<String, Double> evtSvcDurSec;
    /** eventVehicleId → number of service-start events */
    private Map<String, Integer> evtStopCount;

    public DashboardGenerator(String runId, Network network,
                              FreightEventHandler eventHandler,
                              List<ParsedCarrier> carriers,
                              Map<String, Double> vehicleTypeCapacities,
                              Map<String, Double> vehicleTypeFixedCosts,
                              Map<String, Double> vehicleTypeCostsPerKm,
                              Path outputDir) {
        this.runId = runId;
        this.network = network;
        this.eventHandler = eventHandler;
        this.carriers = carriers;
        this.vehicleTypeCapacities = vehicleTypeCapacities != null ? vehicleTypeCapacities : Map.of();
        this.vehicleTypeFixedCosts = vehicleTypeFixedCosts != null ? vehicleTypeFixedCosts : Map.of();
        this.vehicleTypeCostsPerKm = vehicleTypeCostsPerKm != null ? vehicleTypeCostsPerKm : Map.of();
        this.outputDir = outputDir;
    }

    /**
     * Set the low-utilisation threshold.  Vehicles whose load factor (parcels / capacity)
     * is strictly below this value are excluded from all analysis charts and KPIs.
     * A notice is appended to the dashboard listing how many vehicles were excluded.
     *
     * @param threshold fraction, e.g. 0.05 for 5 %.  Use 0 to disable the filter.
     * @return this instance (for chaining)
     */
    public DashboardGenerator setLowUtilThreshold(double threshold) {
        this.lowUtilThreshold = threshold;
        return this;
    }

    /**
     * Pre-compute shared event-based maps once so every builder method uses the
     * same ground-truth data (MATSim events) instead of carrier-XML plans.
     */
    private void precomputeEventMaps() {
        // 1) Tour km per vehicle (from link-leave events + network lengths)
        evtTourKm = new HashMap<>();
        for (var entry : eventHandler.getVehicleTours().entrySet()) {
            double km = 0;
            for (LinkVisit lv : entry.getValue()) {
                Link link = network.getLinks().get(org.matsim.api.core.v01.Id.createLinkId(lv.linkId()));
                if (link != null) km += link.getLength() / 1000.0;
            }
            evtTourKm.put(entry.getKey(), km);
        }

        // 2) Depot departure / arrival from tour boundary events
        evtDepSec = new HashMap<>();
        evtArrSec = new HashMap<>();
        for (TourBoundaryEvent e : eventHandler.getTourStarts()) evtDepSec.put(e.vehicleId(), e.timeSec());
        for (TourBoundaryEvent e : eventHandler.getTourEnds())   evtArrSec.put(e.vehicleId(), e.timeSec());

        // 3) Service duration per vehicle — pair consecutive start/end events
        evtSvcDurSec = new HashMap<>();
        evtStopCount = new HashMap<>();
        for (var entry : eventHandler.getServiceEvents().entrySet()) {
            String vehId = entry.getKey();
            List<ServiceEvent> events = entry.getValue();
            double totalSvcDur = 0;
            int stops = 0;
            double lastStartTime = -1;
            for (ServiceEvent se : events) {
                if (se.isStart()) {
                    lastStartTime = se.timeSec();
                    stops++;
                } else if (lastStartTime >= 0) {
                    totalSvcDur += se.timeSec() - lastStartTime;
                    lastStartTime = -1;
                }
            }
            evtSvcDurSec.put(vehId, totalSvcDur);
            evtStopCount.put(vehId, stops);
        }

        LOG.info("Event maps: {} vehicles, {} with tours, {} with service events",
                evtTourKm.size(), evtDepSec.size(), evtSvcDurSec.size());
    }

    /**
     * Identify delivery vehicles whose load factor is strictly below
     * {@link #lowUtilThreshold} and store their event-vehicle-IDs in
     * {@link #excludedLowUtilVehicles} so every builder method can skip them.
     */
    private void precomputeExcludedVehicles() {
        if (lowUtilThreshold <= 0) {
            excludedLowUtilVehicles = Set.of();
            return;
        }
        Set<String> excluded = new HashSet<>();
        for (ParsedCarrier c : carriers) {
            if (c.isSupply()) continue;
            Map<String, ParsedService> svcMap = new HashMap<>();
            for (ParsedService s : c.services()) svcMap.put(s.serviceId(), s);

            for (ParsedTour t : c.tours()) {
                int parcels = 0;
                for (TourAct a : t.acts()) {
                    if ("service".equals(a.type()) && a.serviceId() != null) {
                        ParsedService svc = svcMap.get(a.serviceId());
                        parcels += svc != null ? svc.capacityDemand() : 1;
                    }
                }
                int cap = lookupCapacity(c, t.vehicleId());
                double lf = (parcels == 0 || cap <= 0) ? 0.0 : Math.min(1.0, (double) parcels / cap);
                if (lf < lowUtilThreshold) {
                    excluded.add(t.eventVehicleId());
                }
            }
        }
        excludedLowUtilVehicles = Collections.unmodifiableSet(excluded);
        if (!excluded.isEmpty()) {
            LOG.info("Low-utilisation filter (< {} %): excluded {} vehicles",
                    String.format(Locale.US, "%.0f", lowUtilThreshold * 100), excluded.size());
        }
    }

    // ====================================================================
    // GENERATE
    // ====================================================================

    public Path generate() throws IOException {
        LOG.info("Generating HAGRID Analysis Dashboard v3 for {}", runId);

        // 0) Pre-compute shared event-based maps (ground truth)
        precomputeEventMaps();

        // 0b) Identify vehicles below low-utilisation threshold
        precomputeExcludedVehicles();

        // 1) Build all data payloads
        String kpiJson        = buildKpiJson();
        String vehiclesJson   = buildVehiclesJson();          // per-vehicle rows
        String tourGeoJson    = buildTourGeoJson();
        String stopGeoJson    = buildStopGeoJson();
        String linkHeatJson   = buildLinkHeatJson("all");
        String linkHeatLmd    = buildLinkHeatJson("delivery");
        String linkHeatSup    = buildLinkHeatJson("supply");
        String providerJson   = buildProviderJson();
        String costJson       = buildCostJson();
        String timelineJson   = buildTimelineJson();
        String vehTypeJson    = buildVehicleTypeJson();
        String distHistoJson  = buildDistHistogramJson();
        String durHistoJson   = buildDurationHistogramJson();
        String utilizationJson= buildUtilizationJson();
        String linkVolumeJson = buildLinkVolumeGeoJson();
        String utilByTypeJson = buildUtilByTypeJson();
        String summaryTableJson = buildSummaryTableJson();
        String routingEffJson = buildRoutingEfficiencyJson();
        String networkBgJson  = buildNetworkBackgroundGeoJson();
        String scoringJson    = buildScoringConvergenceJson();
        String hourlySvcJson  = buildHourlyServiceByProviderJson();
        String activeVehJson  = buildActiveVehiclesJson();
        String depotTripsJson = buildDepotTripsJson();
        String carrierDetailJson = buildCarrierScoringDetailJson();

        // 2) Assemble HTML
        String html = assembleHtml(kpiJson, vehiclesJson, tourGeoJson, stopGeoJson,
                linkHeatJson, linkHeatLmd, linkHeatSup,
                providerJson, costJson, timelineJson, vehTypeJson,
                distHistoJson, durHistoJson, utilizationJson,
                linkVolumeJson, utilByTypeJson, summaryTableJson,
                routingEffJson, networkBgJson, scoringJson, hourlySvcJson,
                activeVehJson, depotTripsJson, carrierDetailJson);

        // 3) Write
        Files.createDirectories(outputDir);
        Path outFile = outputDir.resolve("HAGRID_Dashboard_" + runId + ".html");
        Files.writeString(outFile, html, StandardCharsets.UTF_8);
        LOG.info("Dashboard written to {}", outFile.toAbsolutePath());
        return outFile;
    }

    // ====================================================================
    // KPI JSON
    // ====================================================================

    private String buildKpiJson() {
        List<ParsedCarrier> delivery = carriers.stream().filter(ParsedCarrier::isDelivery).toList();
        List<ParsedCarrier> supply   = carriers.stream().filter(ParsedCarrier::isSupply).toList();

        int totalVehicles    = (int) eventHandler.getVehicleTours().keySet().stream()
                .filter(v -> !excludedLowUtilVehicles.contains(v)).count();
        int deliveryCarriers = delivery.size();
        int supplyCarriers   = supply.size();

        // Per-vehicle utilisation via carrier services + collect delivery event vehicle IDs
        List<Double> loadFactors = new ArrayList<>();
        Set<String> deliveryEventVehIds = new HashSet<>();
        int totalDeliveryVehicles = 0;
        for (ParsedCarrier c : delivery) {
            Map<String, ParsedService> svcMap = new HashMap<>();
            for (ParsedService s : c.services()) svcMap.put(s.serviceId(), s);

            for (ParsedTour t : c.tours()) {
                int vehParcels = 0;
                for (TourAct a : t.acts()) {
                    if ("service".equals(a.type()) && a.serviceId() != null) {
                        ParsedService svc = svcMap.get(a.serviceId());
                        vehParcels += svc != null ? svc.capacityDemand() : 1;
                    }
                }
                if (vehParcels > 0 && !excludedLowUtilVehicles.contains(t.eventVehicleId())) {
                    int cap = lookupCapacity(c, t.vehicleId());
                    loadFactors.add(Math.min(1.0, (double) vehParcels / cap));
                    totalDeliveryVehicles++;
                    deliveryEventVehIds.add(t.eventVehicleId());
                }
            }
        }
        double avgLoadFactor = loadFactors.stream().mapToDouble(Double::doubleValue).average().orElse(0);

        // Vehicle type counts (exclude low-util)
        long vanCount   = eventHandler.getVehicleTours().keySet().stream()
                .filter(v -> !excludedLowUtilVehicles.contains(v))
                .filter(v -> classifyForKpi(v) == VehicleType.VAN).count();
        long bikeCount  = eventHandler.getVehicleTours().keySet().stream()
                .filter(v -> !excludedLowUtilVehicles.contains(v))
                .filter(v -> classifyForKpi(v) == VehicleType.CARGOBIKE).count();
        long truckCount = eventHandler.getVehicleTours().keySet().stream()
                .filter(v -> !excludedLowUtilVehicles.contains(v))
                .filter(v -> { var t = classifyForKpi(v);
                    return t == VehicleType.TRUCK || t == VehicleType.TRUCK_LIGHT || t == VehicleType.SUPPLY_VAN; }).count();

        // Totals (only non-excluded vehicles)
        // Carrier variable costs are proportionally allocated to non-excluded tours.
        double totalCostBase = 0;
        double totalFixCost  = 0;
        for (ParsedCarrier c : delivery) {
            int allTours = c.tours().size();
            int nonExcl  = (int) c.tours().stream()
                    .filter(t -> !excludedLowUtilVehicles.contains(t.eventVehicleId())).count();
            totalCostBase += allTours > 0
                    ? (c.costDistance() + c.costTime() + c.costOvertime()) * nonExcl / allTours : 0;
            for (ParsedTour t : c.tours()) {
                if (!excludedLowUtilVehicles.contains(t.eventVehicleId())) {
                    totalFixCost += lookupFixedCost(c, t.vehicleId());
                }
            }
        }
        double totalCost      = totalCostBase + totalFixCost;

        // Event-based totals: distance + travel time from actual simulation events
        double totalDistanceKm = 0;
        double totalTravelTSec = 0;
        double totalTourDurSec = 0;
        for (String vid : deliveryEventVehIds) {
            totalDistanceKm += evtTourKm.getOrDefault(vid, 0.0);
            double dep = evtDepSec.getOrDefault(vid, 0.0);
            double arr = evtArrSec.getOrDefault(vid, 0.0);
            double svc = evtSvcDurSec.getOrDefault(vid, 0.0);
            double tourDur = arr > dep ? arr - dep : 0;
            totalTourDurSec += tourDur;
            double travelSec = tourDur - svc;
            if (travelSec > 0) totalTravelTSec += travelSec;
        }
        int totalStops = deliveryEventVehIds.stream()
                .mapToInt(vid -> evtStopCount.getOrDefault(vid, 0)).sum();

        // Parcel/service counts from non-excluded vehicles only
        int totalServices = 0, totalDemand = 0, totalParcels = 0;
        for (ParsedCarrier c : delivery) {
            Map<String, ParsedService> sm = new HashMap<>();
            for (ParsedService s : c.services()) sm.put(s.serviceId(), s);
            for (ParsedTour t : c.tours()) {
                if (excludedLowUtilVehicles.contains(t.eventVehicleId())) continue;
                for (TourAct a : t.acts()) {
                    if ("service".equals(a.type()) && a.serviceId() != null) {
                        totalServices++;
                        ParsedService svc = sm.get(a.serviceId());
                        totalDemand += svc != null ? svc.capacityDemand() : 1;
                        totalParcels += svc != null ? svc.capacityDemand() : 1;
                    }
                }
            }
        }
        int parcelBase = totalParcels > 0 ? totalParcels : totalServices;

        // Additional KPIs — proportionally allocate missed parcels to non-excluded tours
        int totalMissed = 0;
        for (ParsedCarrier c : delivery) {
            int allTours = c.tours().size();
            int nonExcl = (int) c.tours().stream()
                    .filter(t -> !excludedLowUtilVehicles.contains(t.eventVehicleId())).count();
            double ratio = allTours > 0 ? (double) nonExcl / allTours : 1.0;
            totalMissed += (int) Math.round(c.numMissed() * ratio);
        }
        double successRate = parcelBase > 0 ? 100.0 * (parcelBase - totalMissed) / parcelBase : 100.0;
        // Stops jsprit could not insert into any tour (7h route-duration cap / vehicle window / capacity)
        // — written by LausitzFreightPreprocessor.recordUnassignedJobs; 0 for the Hannover legacy path.
        int totalUnassignedParcels = sumIntAttr(delivery, "unassignedParcels");
        int totalUnassignedJobs = sumIntAttr(delivery, "unassignedJobs");
        double avgTourLengthKm = totalDeliveryVehicles > 0 ? totalDistanceKm / totalDeliveryVehicles : 0;
        double avgSpeedKmh = totalTravelTSec > 0 ? totalDistanceKm / (totalTravelTSec / 3600.0) : 0;
        Set<String> distinctTypes = new HashSet<>();
        for (ParsedCarrier c : delivery) {
            if (c.vehicleTypeMap() != null) distinctTypes.addAll(c.vehicleTypeMap().values());
        }
        int numVehicleTypes = distinctTypes.size();

        LOG.info("Vehicle utilisation: {} delivery vehicles, avg load factor={}", totalDeliveryVehicles,
                String.format(Locale.US, "%.1f%%", avgLoadFactor * 100));

        return String.format(Locale.US,
                """
                {
                  "totalVehicles":%d,"deliveryCarriers":%d,"supplyCarriers":%d,
                  "totalServices":%d,"totalParcels":%d,"totalDemand":%d,
                  "totalStops":%d,
                  "vanCount":%d,"bikeCount":%d,"truckCount":%d,
                  "deliveryVehicleCount":%d,"avgLoadFactor":%.3f,
                  "eventsProcessed":%d,
                  "totalCost":%.0f,"totalDistanceKm":%.1f,"totalDrivingTimeH":%.1f,
                  "totalTourDurationH":%.1f,
                  "totalMissed":%d,"successRate":%.1f,"avgTourLengthKm":%.1f,
                  "totalUnassignedParcels":%d,"totalUnassignedJobs":%d,
                  "numVehicleTypes":%d,"avgSpeedKmh":%.1f
                }""",
                totalVehicles, deliveryCarriers, supplyCarriers,
                totalServices, parcelBase, totalDemand,
                totalStops,
                vanCount, bikeCount, truckCount,
                totalDeliveryVehicles, avgLoadFactor,
                eventHandler.getTotalEventsProcessed(),
                totalCost, totalDistanceKm, totalTravelTSec / 3600.0,
                totalTourDurSec / 3600.0,
                totalMissed, successRate, avgTourLengthKm,
                totalUnassignedParcels, totalUnassignedJobs,
                numVehicleTypes, avgSpeedKmh
        );
    }

    /**
     * Sums an integer-valued carrier attribute across carriers; missing or non-numeric
     * values count as 0 (attributes arrive as strings from {@link CarrierXmlParser}).
     */
    static int sumIntAttr(List<CarrierXmlParser.ParsedCarrier> carriers, String key) {
        int sum = 0;
        for (CarrierXmlParser.ParsedCarrier c : carriers) {
            String v = c.carrierAttributes().getOrDefault(key, "0");
            try {
                sum += Integer.parseInt(v.trim());
            } catch (NumberFormatException ignored) {
                // non-numeric attribute -> treated as 0
            }
        }
        return sum;
    }

    // ====================================================================
    // VEHICLES JSON — per-vehicle row data for flexible charting
    // ====================================================================

    /**
     * Builds a JSON array with one object per vehicle.  Each object contains
     * provider, vehicleType, carrierId, tourKm, tourDurationH, travelDurationH,
     * serviceDurationH, depotDepartureH, parcels, loadFactor, cost, stops.
     * <p>This is the main data source for histograms and scatter plots.</p>
     */
    private String buildVehiclesJson() {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;

        for (ParsedCarrier c : carriers) {
            if (c.isSupply()) continue;

            Map<String, ParsedService> svcMap = new HashMap<>();
            for (ParsedService s : c.services()) svcMap.put(s.serviceId(), s);

            for (ParsedTour t : c.tours()) {
                String vehId = t.eventVehicleId();

                int parcels = 0;
                for (TourAct a : t.acts()) {
                    if ("service".equals(a.type()) && a.serviceId() != null) {
                        ParsedService svc = svcMap.get(a.serviceId());
                        parcels += svc != null ? svc.capacityDemand() : 1;
                    }
                }
                if (parcels == 0) continue;
                if (excludedLowUtilVehicles.contains(vehId)) continue;

                int cap = lookupCapacity(c, t.vehicleId());
                double loadFactor = Math.min(1.0, (double) parcels / cap);

                // All times / distances from pre-computed event maps
                double depSec     = evtDepSec.getOrDefault(vehId, 0.0);
                double arrSec     = evtArrSec.getOrDefault(vehId, 0.0);
                double svcDurSec  = evtSvcDurSec.getOrDefault(vehId, 0.0);
                double tourDurSec = arrSec > depSec ? arrSec - depSec : 0;
                double travelDurSec = Math.max(0, tourDurSec - svcDurSec);
                double km    = evtTourKm.getOrDefault(vehId, 0.0);
                int    stops = evtStopCount.getOrDefault(vehId, 0);

                // Resolve actual vehicle type ID from carrier's vehicle fleet
                String vtLabel = "Unknown";
                if (c.vehicleTypeMap() != null && c.vehicleTypeMap().containsKey(t.vehicleId())) {
                    vtLabel = c.vehicleTypeMap().get(t.vehicleId());
                } else {
                    VehicleType vtype = classifyForKpi(vehId);
                    vtLabel = vtype != null ? vtype.label() : "Unknown";
                }

                // First service distance (stem): sum network link lengths for the first leg's route
                double stemKm = 0;
                if (!t.legs().isEmpty()) {
                    for (String lid : t.legs().getFirst().routeLinkIds()) {
                        Link stemLink = network.getLinks().get(org.matsim.api.core.v01.Id.createLinkId(lid));
                        if (stemLink != null) stemKm += stemLink.getLength() / 1000.0;
                    }
                }

                if (!first) sb.append(",");
                first = false;
                sb.append(String.format(Locale.US,
                    """
                    {"vid":"%s","carrier":"%s","provider":"%s","vtype":"%s",\
                    "km":%.2f,"durH":%.3f,"travelH":%.3f,"svcH":%.3f,\
                    "depH":%.3f,"parcels":%d,"cap":%d,"loadFactor":%.3f,\
                    "stops":%d,"stemKm":%.2f,\
                    "costTotal":%.1f,"costDist":%.1f,"costTime":%.1f,"costFix":%.1f}""",
                    escJson(vehId), escJson(c.carrierId()), escJson(c.provider()), escJson(vtLabel),
                    km, tourDurSec / 3600.0, travelDurSec / 3600.0, svcDurSec / 3600.0,
                    depSec / 3600.0, parcels, cap, loadFactor,
                    stops, stemKm,
                    0.0, 0.0, 0.0, 0.0  // per-vehicle costs not available in carrier XML; use carrier-level
                ));
            }
        }
        sb.append("]");
        return sb.toString();
    }

    // ====================================================================
    // Tour GeoJSON — polylines enriched with provider + carrier info
    // ====================================================================

    private String buildTourGeoJson() {
        // Build carrier lookup by vehicle ID prefix
        Map<String, String> vehToProvider = new HashMap<>();
        Map<String, String> vehToCarrier = new HashMap<>();
        Map<String, Boolean> vehIsSupply = new HashMap<>();
        for (ParsedCarrier c : carriers) {
            for (ParsedTour t : c.tours()) {
                String vehId = t.eventVehicleId();
                vehToProvider.put(vehId, c.provider().isEmpty() ? "other" : c.provider());
                vehToCarrier.put(vehId, c.carrierId());
                vehIsSupply.put(vehId, c.isSupply());
            }
        }

        StringBuilder sb = new StringBuilder(1024 * 64);
        sb.append("{\"type\":\"FeatureCollection\",\"features\":[");
        boolean first = true;

        for (var entry : eventHandler.getVehicleTours().entrySet()) {
            String vehId = entry.getKey();
            List<LinkVisit> visits = entry.getValue();
            if (visits.size() < 2) continue;

            VehicleType vtype = classifyForKpi(vehId);
            String vtypeLabel = vtype != null ? vtype.label() : "Unknown";
            String provider = vehToProvider.getOrDefault(vehId, "other");
            String carrier  = vehToCarrier.getOrDefault(vehId, "");
            boolean supply  = vehIsSupply.getOrDefault(vehId, false);

            // Coordinate array from link centroids
            StringBuilder coords = new StringBuilder("[");
            boolean cfirst = true;
            for (LinkVisit lv : visits) {
                Link link = network.getLinks().get(org.matsim.api.core.v01.Id.createLinkId(lv.linkId()));
                if (link == null) continue;
                double[] ll = toLatLon(link.getCoord());
                if (!cfirst) coords.append(",");
                coords.append(String.format(Locale.US, "[%.6f,%.6f]", ll[1], ll[0]));
                cfirst = false;
            }
            coords.append("]");

            if (!first) sb.append(",");
            first = false;
            sb.append(String.format(Locale.US,
                    """
                    {"type":"Feature","properties":{"vid":"%s","vtype":"%s","provider":"%s","carrier":"%s","supply":%b,"stops":%d},\
                    "geometry":{"type":"LineString","coordinates":%s}}""",
                    escJson(vehId), escJson(vtypeLabel), escJson(provider), escJson(carrier),
                    supply, visits.size(), coords
            ));
        }
        sb.append("]}");
        return sb.toString();
    }

    // ====================================================================
    // Stop GeoJSON — service stop points
    // ====================================================================

    private String buildStopGeoJson() {
        // Build vehicle→provider lookup and per-vehicle ordered service demands
        Map<String, String> vehToProvider = new HashMap<>();
        Map<String, List<Integer>> demandByVehStop = new HashMap<>();
        for (ParsedCarrier c : carriers) {
            Map<String, ParsedService> svcMap = new HashMap<>();
            for (ParsedService s : c.services()) svcMap.put(s.serviceId(), s);
            for (ParsedTour t : c.tours()) {
                String vehId = t.eventVehicleId();
                vehToProvider.put(vehId, c.provider().isEmpty() ? "other" : c.provider());
                List<Integer> demands = new ArrayList<>();
                for (TourAct a : t.acts()) {
                    if ("service".equals(a.type()) && a.serviceId() != null) {
                        ParsedService svc = svcMap.get(a.serviceId());
                        demands.add(svc != null ? svc.capacityDemand() : 1);
                    }
                }
                demandByVehStop.put(vehId, demands);
            }
        }

        StringBuilder sb = new StringBuilder(1024 * 32);
        sb.append("{\"type\":\"FeatureCollection\",\"features\":[");
        boolean first = true;

        for (var entry : eventHandler.getServiceEvents().entrySet()) {
            String vehId = entry.getKey();
            List<ServiceEvent> events = entry.getValue();
            String provider = vehToProvider.getOrDefault(vehId, "other");

            int stopIdx = 0;
            for (int i = 0; i < events.size(); i++) {
                ServiceEvent ev = events.get(i);
                if (!ev.isStart()) continue;

                double endTime = ev.timeSec();
                for (int j = i + 1; j < events.size(); j++) {
                    if (!events.get(j).isStart() && events.get(j).linkId().equals(ev.linkId())) {
                        endTime = events.get(j).timeSec();
                        break;
                    }
                }

                Link link = network.getLinks().get(org.matsim.api.core.v01.Id.createLinkId(ev.linkId()));
                if (link == null) continue;
                double[] ll = toLatLon(link.getCoord());
                // Subtract 1 second: vehicle resumes at event end time
                double dur = Math.max(0, endTime - ev.timeSec() - 1);

                // Look up demand by stop index in the vehicle's tour
                List<Integer> demands = demandByVehStop.get(vehId);
                int demand = (demands != null && stopIdx < demands.size()) ? demands.get(stopIdx) : 1;

                if (!first) sb.append(",");
                first = false;
                sb.append(String.format(Locale.US,
                        """
                        {"type":"Feature","properties":{"vid":"%s","provider":"%s","idx":%d,\
                        "link":"%s","startSec":%.0f,"endSec":%.0f,"durSec":%.0f,\
                        "startHMS":"%s","endHMS":"%s","durHMS":"%s","demand":%d},\
                        "geometry":{"type":"Point","coordinates":[%.6f,%.6f]}}""",
                        escJson(vehId), escJson(provider), stopIdx, escJson(ev.linkId()),
                        ev.timeSec(), endTime, dur,
                        fmtHMS(ev.timeSec()), fmtHMS(endTime), fmtHMS(dur),
                        demand,
                        ll[1], ll[0]
                ));
                stopIdx++;
            }
        }
        sb.append("]}");
        return sb.toString();
    }

    // ====================================================================
    // Link heat data
    // ====================================================================

    private String buildLinkHeatJson(String mode) {
        List<Map.Entry<String, LinkCounts>> sorted = eventHandler.getLinkCountMap().entrySet().stream()
                .sorted((a, b) -> Integer.compare(countForMode(b.getValue(), mode), countForMode(a.getValue(), mode)))
                .filter(e -> countForMode(e.getValue(), mode) > 0)
                .limit(5000)
                .toList();
        int maxCount = sorted.isEmpty() ? 1 : countForMode(sorted.getFirst().getValue(), mode);

        StringBuilder sb = new StringBuilder(1024 * 16);
        sb.append("[");
        boolean first = true;
        for (var entry : sorted) {
            Link link = network.getLinks().get(org.matsim.api.core.v01.Id.createLinkId(entry.getKey()));
            if (link == null) continue;
            double[] ll = toLatLon(link.getCoord());
            double intensity = (double) countForMode(entry.getValue(), mode) / maxCount;
            if (!first) sb.append(",");
            first = false;
            sb.append(String.format(Locale.US, "[%.6f,%.6f,%.3f]", ll[0], ll[1], intensity));
        }
        sb.append("]");
        return sb.toString();
    }

    private static int countForMode(LinkCounts lc, String mode) {
        return switch (mode) {
            case "delivery" -> lc.totalDelivery();
            case "supply"   -> lc.totalSupply();
            default         -> lc.total();
        };
    }

    // ====================================================================
    // Provider breakdown
    // ====================================================================

    private String buildProviderJson() {
        List<ParsedCarrier> delivery = carriers.stream().filter(ParsedCarrier::isDelivery).toList();
        Map<String, int[]> byProv = new LinkedHashMap<>(); // [carriers, parcels, vehicles]
        for (ParsedCarrier c : delivery) {
            String p = c.provider().isEmpty() ? "other" : c.provider();
            byProv.computeIfAbsent(p, k -> new int[3]);
            byProv.get(p)[0]++;
            // Count parcels and vehicles from non-excluded tours only
            Map<String, ParsedService> sm = new HashMap<>();
            for (ParsedService s : c.services()) sm.put(s.serviceId(), s);
            for (ParsedTour t : c.tours()) {
                if (excludedLowUtilVehicles.contains(t.eventVehicleId())) continue;
                byProv.get(p)[2]++;
                for (TourAct a : t.acts()) {
                    if ("service".equals(a.type()) && a.serviceId() != null) {
                        ParsedService svc = sm.get(a.serviceId());
                        byProv.get(p)[1] += svc != null ? svc.capacityDemand() : 1;
                    }
                }
            }
        }
        String[] colors = {"#3b82f6","#ef4444","#10b981","#f59e0b","#8b5cf6","#ec4899","#06b6d4","#84cc16","#6b7280"};
        StringBuilder labels = new StringBuilder("[");
        StringBuilder pCount = new StringBuilder("["), pParcels = new StringBuilder("["), pVehs = new StringBuilder("[");
        StringBuilder pColors = new StringBuilder("[");
        int ci = 0;
        boolean first = true;
        for (var e : byProv.entrySet()) {
            if (!first) { labels.append(","); pCount.append(","); pParcels.append(","); pVehs.append(","); pColors.append(","); }
            first = false;
            labels.append("\"").append(escJson(e.getKey())).append("\"");
            pCount.append(e.getValue()[0]);
            pParcels.append(e.getValue()[1]);
            pVehs.append(e.getValue()[2]);
            pColors.append("\"").append(colors[ci % colors.length]).append("\"");
            ci++;
        }
        return String.format("{\"labels\":%s],\"carriers\":%s],\"parcels\":%s],\"vehicles\":%s],\"colors\":%s]}",
                labels, pCount, pParcels, pVehs, pColors);
    }

    // ====================================================================
    // Cost breakdown by provider
    // ====================================================================

    private String buildCostJson() {
        List<ParsedCarrier> delivery = carriers.stream().filter(ParsedCarrier::isDelivery).toList();
        Map<String, double[]> byProv = new LinkedHashMap<>();
        for (ParsedCarrier c : delivery) {
            String p = c.provider().isEmpty() ? "other" : c.provider();
            byProv.computeIfAbsent(p, k -> new double[7]);
            double[] v = byProv.get(p);
            // Proportionally allocate carrier variable costs to non-excluded tours
            int allTours = c.tours().size();
            int nonExcl  = (int) c.tours().stream()
                    .filter(t -> !excludedLowUtilVehicles.contains(t.eventVehicleId())).count();
            double ratio = allTours > 0 ? (double) nonExcl / allTours : 0;
            v[1] += c.costDistance() * ratio;
            v[2] += c.costTime() * ratio;
            v[4] += c.costActivity() * ratio;
            v[5] += c.costOvertime() * ratio;
            v[6] += c.costTimeWindowPenalty() * ratio;
            // Fixed costs only for non-excluded vehicles
            for (ParsedTour t : c.tours()) {
                if (!excludedLowUtilVehicles.contains(t.eventVehicleId())) {
                    v[3] += lookupFixedCost(c, t.vehicleId());
                }
            }
            v[0] = v[1] + v[2] + v[3] + v[5]; // total = distance + time + fixed + overtime
        }
        StringBuilder labels = new StringBuilder("[");
        StringBuilder total = new StringBuilder("["), dist = new StringBuilder("["),
                time = new StringBuilder("["), fix = new StringBuilder("["),
                activity = new StringBuilder("["), overtime = new StringBuilder("["),
                twPen = new StringBuilder("[");
        boolean first = true;
        for (var e : byProv.entrySet()) {
            if (!first) { labels.append(","); total.append(","); dist.append(",");
                time.append(","); fix.append(","); activity.append(","); overtime.append(","); twPen.append(","); }
            first = false;
            labels.append("\"").append(escJson(e.getKey())).append("\"");
            double[] v = e.getValue();
            total.append(fmt(v[0])); dist.append(fmt(v[1])); time.append(fmt(v[2]));
            fix.append(fmt(v[3])); activity.append(fmt(v[4])); overtime.append(fmt(v[5]));
            twPen.append(fmt(v[6]));
        }
        return String.format(
                "{\"labels\":%s],\"total\":%s],\"distance\":%s],\"time\":%s],\"fix\":%s],\"activity\":%s],\"overtime\":%s],\"twPenalty\":%s]}",
                labels, total, dist, time, fix, activity, overtime, twPen);
    }

    // ====================================================================
    // Scoring convergence — read carrier_scores.txt
    // ====================================================================

    private String buildScoringConvergenceJson() {
        // Look for carrier_scores.txt in the MATSim output (parent of our output dir)
        Path scoresFile = outputDir.getParent().resolve("carrier_scores.txt");
        if (!Files.exists(scoresFile)) {
            LOG.info("No carrier_scores.txt found at {}, skipping scoring convergence", scoresFile);
            return "{\"iterations\":[],\"executed\":[],\"worst\":[],\"avg\":[],\"best\":[]}";
        }
        try {
            List<String> lines = Files.readAllLines(scoresFile, StandardCharsets.UTF_8);
            StringBuilder iters = new StringBuilder("["), exec = new StringBuilder("["),
                    worst = new StringBuilder("["), avg = new StringBuilder("["),
                    best = new StringBuilder("[");
            boolean first = true;
            for (int i = 1; i < lines.size(); i++) { // skip header
                String line = lines.get(i).trim();
                if (line.isEmpty()) continue;
                String[] parts = line.split("\t");
                if (parts.length < 5) continue;
                if (!first) { iters.append(","); exec.append(","); worst.append(","); avg.append(","); best.append(","); }
                first = false;
                iters.append(parts[0]);
                exec.append(String.format(Locale.US, "%.2f", Double.parseDouble(parts[1])));
                worst.append(String.format(Locale.US, "%.2f", Double.parseDouble(parts[2])));
                avg.append(String.format(Locale.US, "%.2f", Double.parseDouble(parts[3])));
                best.append(String.format(Locale.US, "%.2f", Double.parseDouble(parts[4])));
            }
            LOG.info("Scoring convergence loaded: {} iterations from {}", lines.size() - 1, scoresFile.getFileName());
            return String.format("{\"iterations\":%s],\"executed\":%s],\"worst\":%s],\"avg\":%s],\"best\":%s]}",
                    iters, exec, worst, avg, best);
        } catch (Exception e) {
            LOG.warn("Failed to read carrier_scores.txt: {}", e.getMessage());
            return "{\"iterations\":[],\"executed\":[],\"worst\":[],\"avg\":[],\"best\":[]}";
        }
    }

    // ====================================================================
    // Timeline — hourly departures + service starts
    // ====================================================================

    private String buildTimelineJson() {
        int[] departures    = new int[25];
        int[] serviceStarts = new int[25];
        for (TourBoundaryEvent ts : eventHandler.getTourStarts()) {
            if (excludedLowUtilVehicles.contains(ts.vehicleId())) continue;
            int h = Math.min(24, (int) (ts.timeSec() / 3600));
            departures[h]++;
        }
        for (var events : eventHandler.getServiceEvents().entrySet()) {
            if (excludedLowUtilVehicles.contains(events.getKey())) continue;
            for (ServiceEvent ev : events.getValue()) {
                if (ev.isStart()) {
                    int h = Math.min(24, (int) (ev.timeSec() / 3600));
                    serviceStarts[h]++;
                }
            }
        }
        StringBuilder hours = new StringBuilder("["), deps = new StringBuilder("["), svcs = new StringBuilder("[");
        for (int h = 4; h <= 23; h++) {
            if (h > 4) { hours.append(","); deps.append(","); svcs.append(","); }
            hours.append(String.format("\"%02d:00\"", h));
            deps.append(departures[h]);
            svcs.append(serviceStarts[h]);
        }
        return String.format("{\"hours\":%s],\"departures\":%s],\"services\":%s]}", hours, deps, svcs);
    }

    // ====================================================================
    // Hourly service activity by provider (for TW analysis)
    // ====================================================================

    /**
     * Builds JSON with per-hour, per-provider service counts and TW-late counts.
     * TW end is derived dynamically from delivery carriers' service latestEnd.
     * Output: {hours:["04:00",...,"23:00"], providers:["dhl",...],
     *          services:{"dhl":[0,0,5,...],...}, late:{"dhl":[0,0,0,...],...},
     *          twEndHour:<dynamic>}
     */
    private String buildHourlyServiceByProviderJson() {
        // derive TW end from delivery carriers' service latestEnd (fallback 72000 = 20:00)
        int twEndSec = 0;
        for (ParsedCarrier c : carriers) {
            if (!c.isDelivery()) continue;
            for (ParsedService s : c.services()) {
                if (s.latestEndSec() > twEndSec) twEndSec = s.latestEndSec();
            }
        }
        if (twEndSec == 0) twEndSec = 72000; // fallback 20:00
        final int TW_END_SEC = twEndSec;
        // Build vehicle→provider lookup (delivery only)
        Map<String, String> vehToProvider = new HashMap<>();
        for (ParsedCarrier c : carriers) {
            if (!c.isDelivery()) continue;
            for (ParsedTour t : c.tours()) {
                vehToProvider.put(t.eventVehicleId(), c.provider().isEmpty() ? "other" : c.provider());
            }
        }
        // Collect unique providers in sorted order
        List<String> provList = vehToProvider.values().stream().distinct().sorted().toList();
        // Accumulate: provider → int[25] services, int[25] late
        Map<String, int[]> svcByProv = new LinkedHashMap<>();
        Map<String, int[]> lateByProv = new LinkedHashMap<>();
        for (String p : provList) {
            svcByProv.put(p, new int[25]);
            lateByProv.put(p, new int[25]);
        }
        for (var entry : eventHandler.getServiceEvents().entrySet()) {
            String vehId = entry.getKey();
            if (excludedLowUtilVehicles.contains(vehId)) continue;
            String prov = vehToProvider.get(vehId);
            if (prov == null) continue; // skip supply vehicles
            int[] svc = svcByProv.get(prov);
            int[] late = lateByProv.get(prov);
            if (svc == null) continue;
            for (ServiceEvent ev : entry.getValue()) {
                if (!ev.isStart()) continue;
                int h = Math.min(24, (int) (ev.timeSec() / 3600));
                svc[h]++;
                if (ev.timeSec() > TW_END_SEC) late[h]++;
            }
        }
        // Build JSON
        StringBuilder hours = new StringBuilder("[");
        for (int h = 4; h <= 23; h++) {
            if (h > 4) hours.append(",");
            hours.append(String.format("\"%02d:00\"", h));
        }
        hours.append("]");
        StringBuilder provJson = new StringBuilder("[");
        StringBuilder svcJson = new StringBuilder("{");
        StringBuilder lateJson = new StringBuilder("{");
        boolean first = true;
        for (String p : provList) {
            if (!first) { provJson.append(","); svcJson.append(","); lateJson.append(","); }
            first = false;
            provJson.append("\"").append(escJson(p)).append("\"");
            svcJson.append("\"").append(escJson(p)).append("\":[");
            lateJson.append("\"").append(escJson(p)).append("\":[");
            for (int h = 4; h <= 23; h++) {
                if (h > 4) { svcJson.append(","); lateJson.append(","); }
                svcJson.append(svcByProv.get(p)[h]);
                lateJson.append(lateByProv.get(p)[h]);
            }
            svcJson.append("]");
            lateJson.append("]");
        }
        int twEndHour = TW_END_SEC / 3600;
        int twEndMin  = (TW_END_SEC % 3600) / 60;
        return String.format("{\"hours\":%s,\"providers\":%s],\"services\":%s},\"late\":%s},\"twEndHour\":%d,\"twEndLabel\":\"%02d:%02d\"}",
                hours, provJson, svcJson, lateJson, twEndHour, twEndHour, twEndMin);
    }

    // ====================================================================
    // Active vehicles over time (per provider, per minute)
    // ====================================================================

    /**
     * Builds JSON with per-minute active vehicle counts by provider.
     * A vehicle is "active" from its tour start (depot departure) until tour end (depot return).
     * Resolution: 1 minute, range: 04:00–23:59.
     * Output: {minutes:["04:00","04:01",...], providers:["dhl",...],
     *          active:{"dhl":[0,0,1,...], ...}}
     */
    private String buildActiveVehiclesJson() {
        // Build vehicle→provider lookup (delivery only)
        Map<String, String> vehToProvider = new HashMap<>();
        for (ParsedCarrier c : carriers) {
            if (!c.isDelivery()) continue;
            for (ParsedTour t : c.tours()) {
                vehToProvider.put(t.eventVehicleId(), c.provider().isEmpty() ? "other" : c.provider());
            }
        }
        // Tour start/end times per vehicle
        Map<String, Double> depTimes = new HashMap<>();
        Map<String, Double> arrTimes = new HashMap<>();
        for (TourBoundaryEvent e : eventHandler.getTourStarts()) depTimes.put(e.vehicleId(), e.timeSec());
        for (TourBoundaryEvent e : eventHandler.getTourEnds())   arrTimes.put(e.vehicleId(), e.timeSec());

        // Collect providers
        List<String> provList = vehToProvider.values().stream().distinct().sorted().toList();

        // Minute range: 04:00 (240) to 23:59 (1439) → 1200 minutes
        final int START_MIN = 240;  // 04:00
        final int END_MIN = 1439;   // 23:59
        final int NUM_MINUTES = END_MIN - START_MIN + 1;

        // For each provider, count active vehicles per minute
        Map<String, int[]> active = new LinkedHashMap<>();
        for (String p : provList) active.put(p, new int[NUM_MINUTES]);

        for (var entry : vehToProvider.entrySet()) {
            String vehId = entry.getKey();
            if (excludedLowUtilVehicles.contains(vehId)) continue;
            String prov = entry.getValue();
            Double dep = depTimes.get(vehId);
            Double arr = arrTimes.get(vehId);
            if (dep == null || arr == null) continue;

            int startMin = Math.max(START_MIN, (int) Math.floor(dep / 60.0));
            int endMin   = Math.min(END_MIN,   (int) Math.floor(arr / 60.0));
            int[] counts = active.get(prov);
            if (counts == null) continue;
            for (int m = startMin; m <= endMin; m++) {
                int idx = m - START_MIN;
                if (idx >= 0 && idx < NUM_MINUTES) counts[idx]++;
            }
        }

        // Build JSON — sample every 5 minutes for a cleaner chart (240 points)
        final int STEP = 5;
        StringBuilder minutes = new StringBuilder("[");
        int labelCount = 0;
        for (int m = START_MIN; m <= END_MIN; m += STEP) {
            if (labelCount++ > 0) minutes.append(",");
            minutes.append(String.format("\"%02d:%02d\"", m / 60, m % 60));
        }
        minutes.append("]");

        StringBuilder provJson = new StringBuilder("[");
        StringBuilder actJson = new StringBuilder("{");
        boolean first = true;
        for (String p : provList) {
            if (!first) { provJson.append(","); actJson.append(","); }
            first = false;
            provJson.append("\"").append(escJson(p)).append("\"");
            actJson.append("\"").append(escJson(p)).append("\":[");
            int[] counts = active.get(p);
            int valCount = 0;
            for (int m = START_MIN; m <= END_MIN; m += STEP) {
                if (valCount++ > 0) actJson.append(",");
                actJson.append(counts[m - START_MIN]);
            }
            actJson.append("]");
        }
        provJson.append("]");
        actJson.append("}");

        return String.format("{\"minutes\":%s,\"providers\":%s,\"active\":%s}", minutes, provJson, actJson);
    }

    // ====================================================================
    // Depot departures & arrivals over time (per provider)
    // ====================================================================

    /**
     * Builds JSON with per-5-min departure and arrival counts by provider.
     * Departures = tour start (leaving depot), Arrivals = tour end (back at depot).
     * Output: {minutes:["04:00",...], providers:["dhl",...],
     *          departures:{"dhl":[0,0,1,...],...}, arrivals:{"dhl":[0,0,0,...],...}}
     */
    private String buildDepotTripsJson() {
        // Build vehicle→provider lookup (delivery only)
        Map<String, String> vehToProvider = new HashMap<>();
        for (ParsedCarrier c : carriers) {
            if (!c.isDelivery()) continue;
            for (ParsedTour t : c.tours()) {
                vehToProvider.put(t.eventVehicleId(), c.provider().isEmpty() ? "other" : c.provider());
            }
        }
        List<String> provList = vehToProvider.values().stream().distinct().sorted().toList();

        final int START_MIN = 240;  // 04:00
        final int END_MIN = 1439;   // 23:59
        final int STEP = 5;
        int numBins = (END_MIN - START_MIN) / STEP + 1;

        Map<String, int[]> depByProv = new LinkedHashMap<>();
        Map<String, int[]> arrByProv = new LinkedHashMap<>();
        for (String p : provList) {
            depByProv.put(p, new int[numBins]);
            arrByProv.put(p, new int[numBins]);
        }

        for (TourBoundaryEvent e : eventHandler.getTourStarts()) {
            if (excludedLowUtilVehicles.contains(e.vehicleId())) continue;
            String prov = vehToProvider.get(e.vehicleId());
            if (prov == null) continue;
            int min = (int) Math.floor(e.timeSec() / 60.0);
            int idx = (min - START_MIN) / STEP;
            if (idx >= 0 && idx < numBins) {
                int[] arr = depByProv.get(prov);
                if (arr != null) arr[idx]++;
            }
        }
        for (TourBoundaryEvent e : eventHandler.getTourEnds()) {
            if (excludedLowUtilVehicles.contains(e.vehicleId())) continue;
            String prov = vehToProvider.get(e.vehicleId());
            if (prov == null) continue;
            int min = (int) Math.floor(e.timeSec() / 60.0);
            int idx = (min - START_MIN) / STEP;
            if (idx >= 0 && idx < numBins) {
                int[] arr = arrByProv.get(prov);
                if (arr != null) arr[idx]++;
            }
        }

        // Build JSON
        StringBuilder minutes = new StringBuilder("[");
        int lc = 0;
        for (int m = START_MIN; m <= END_MIN; m += STEP) {
            if (lc++ > 0) minutes.append(",");
            minutes.append(String.format("\"%02d:%02d\"", m / 60, m % 60));
        }
        minutes.append("]");

        StringBuilder provJson = new StringBuilder("[");
        StringBuilder depJson = new StringBuilder("{");
        StringBuilder arrJson = new StringBuilder("{");
        boolean first = true;
        for (String p : provList) {
            if (!first) { provJson.append(","); depJson.append(","); arrJson.append(","); }
            first = false;
            provJson.append("\"").append(escJson(p)).append("\"");
            depJson.append("\"").append(escJson(p)).append("\":");
            arrJson.append("\"").append(escJson(p)).append("\":");
            depJson.append(Arrays.toString(depByProv.get(p)));
            arrJson.append(Arrays.toString(arrByProv.get(p)));
        }
        provJson.append("]");
        depJson.append("}");
        arrJson.append("}");

        return String.format("{\"minutes\":%s,\"providers\":%s,\"departures\":%s,\"arrivals\":%s}",
                minutes, provJson, depJson, arrJson);
    }

    // ====================================================================
    // Carrier Scoring Detail — interactive per-carrier breakdown
    // ====================================================================

    /**
     * Build a JSON array with per-carrier scoring details including vehicle fleet composition,
     * tour stats, cost components, and vehicle time windows. Used by the interactive carrier
     * explorer table in the dashboard.
     */
    private String buildCarrierScoringDetailJson() {
        List<ParsedCarrier> delivery = carriers.stream().filter(ParsedCarrier::isDelivery).toList();
        StringBuilder sb = new StringBuilder(4096);
        sb.append("[");
        boolean first = true;
        for (ParsedCarrier c : delivery) {
            if (!first) sb.append(",");
            first = false;

            String provider = c.provider().isEmpty() ? "other" : c.provider();

            // Build vehicle fleet array
            StringBuilder vehArr = new StringBuilder("[");
            boolean vFirst = true;
            for (var ve : c.vehicleTypeMap().entrySet()) {
                if (!vFirst) vehArr.append(",");
                vFirst = false;
                String vId = ve.getKey();
                String vType = ve.getValue();
                Double cap = vehicleTypeCapacities.get(vType);
                double fixCost = lookupFixedCost(c, vId);
                int[] tw = c.vehicleTimeWindows().getOrDefault(vId, new int[]{0, 0});
                vehArr.append(String.format(Locale.US,
                    "{\"id\":\"%s\",\"type\":\"%s\",\"cap\":%.0f,\"fix\":%.1f,\"twStart\":%d,\"twEnd\":%d}",
                    escJson(vId), escJson(vType), cap != null ? cap : 0, fixCost, tw[0], tw[1]));
            }
            vehArr.append("]");

            // Build tour summary array — dep/arr/stops from events (non-excluded only)
            StringBuilder tourArr = new StringBuilder("[");
            boolean tFirst = true;
            int nonExclTourCount = 0;
            int nonExclParcels = 0;
            int nonExclServices = 0;
            for (ParsedTour t : c.tours()) {
                String evtVid = t.eventVehicleId();
                if (excludedLowUtilVehicles.contains(evtVid)) continue;
                nonExclTourCount++;
                if (!tFirst) tourArr.append(",");
                tFirst = false;
                String vId = t.vehicleId();
                String vType = c.vehicleTypeMap().getOrDefault(vId, "?");

                // Stops + parcels from carrier XML (parcel demand assignment)
                int stops = evtStopCount.getOrDefault(evtVid, 0);
                int parcels = 0;
                int svcCount = 0;
                for (TourAct a : t.acts()) {
                    if ("service".equals(a.type()) && a.serviceId() != null) {
                        svcCount++;
                        for (ParsedService svc : c.services()) {
                            if (svc.serviceId().equals(a.serviceId())) {
                                parcels += svc.capacityDemand();
                                break;
                            }
                        }
                    }
                }
                nonExclParcels += parcels;
                nonExclServices += svcCount;

                // Dep/Arr from events (ground truth)
                double depTimeSec = evtDepSec.getOrDefault(evtVid, 0.0);
                double arrTimeSec = evtArrSec.getOrDefault(evtVid, 0.0);

                tourArr.append(String.format(Locale.US,
                    "{\"vid\":\"%s\",\"vtype\":\"%s\",\"stops\":%d,\"parcels\":%d,\"depSec\":%.0f,\"arrSec\":%.0f}",
                    escJson(vId), escJson(vType), stops, parcels, depTimeSec, arrTimeSec));
            }
            tourArr.append("]");

            // Compute fix cost total from non-excluded tours only
            double fixTotal = 0;
            for (ParsedTour t : c.tours()) {
                if (excludedLowUtilVehicles.contains(t.eventVehicleId())) continue;
                fixTotal += lookupFixedCost(c, t.vehicleId());
            }

            // Event-based distance + travel time for this carrier (non-excluded only)
            double carrierDistKm = 0;
            double carrierTravelH = 0;
            for (ParsedTour t : c.tours()) {
                String vid = t.eventVehicleId();
                if (excludedLowUtilVehicles.contains(vid)) continue;
                carrierDistKm += evtTourKm.getOrDefault(vid, 0.0);
                double dep = evtDepSec.getOrDefault(vid, 0.0);
                double arr = evtArrSec.getOrDefault(vid, 0.0);
                double svc = evtSvcDurSec.getOrDefault(vid, 0.0);
                double tourDur = arr > dep ? arr - dep : 0;
                carrierTravelH += Math.max(0, tourDur - svc) / 3600.0;
            }

            // Proportional variable cost allocation for non-excluded tours
            int allTours = c.tours().size();
            double ratio = allTours > 0 ? (double) nonExclTourCount / allTours : 0;
            double costDist = c.costDistance() * ratio;
            double costTime = c.costTime() * ratio;
            double costAct  = c.costActivity() * ratio;
            double costOT   = c.costOvertime() * ratio;
            double costTW   = c.costTimeWindowPenalty() * ratio;
            double costTotal = costDist + costTime + fixTotal + costOT;

            // Missed parcels proportional
            int nonExclMissed = allTours > 0 ? (int) Math.round(c.numMissed() * ratio) : 0;
            double successRate = nonExclParcels > 0
                ? (1.0 - (double) nonExclMissed / nonExclParcels) * 100.0
                : 100.0;

            sb.append(String.format(Locale.US,
                "{\"id\":\"%s\",\"prov\":\"%s\",\"plz\":\"%s\"," +
                "\"svc\":%d,\"parcels\":%d,\"missed\":%d,\"tours\":%d," +
                "\"costTotal\":%.1f,\"costDist\":%.1f,\"costTime\":%.1f," +
                "\"costFix\":%.1f,\"costAct\":%.1f,\"costOT\":%.1f,\"costTW\":%.1f," +
                "\"distKm\":%.1f,\"travelH\":%.2f,\"successRate\":%.1f," +
                "\"vehicles\":%s,\"tourDetails\":%s}",
                escJson(c.carrierId()), escJson(provider), escJson(c.plz()),
                nonExclServices, nonExclParcels,
                nonExclMissed, nonExclTourCount,
                costTotal, costDist, costTime,
                fixTotal, costAct, costOT, costTW,
                carrierDistKm, carrierTravelH,
                successRate,
                vehArr, tourArr));
        }
        sb.append("]");
        return sb.toString();
    }

    // ====================================================================
    // Vehicle type breakdown
    // ====================================================================

    /** Curated color palette for vehicle types — cycles if more types than colors. */
    private static final String[] VT_COLORS = {
        "#3b82f6", "#10b981", "#f59e0b", "#ef4444", "#8b5cf6",
        "#ec4899", "#06b6d4", "#84cc16", "#f97316", "#6366f1"
    };

    private String buildVehicleTypeJson() {
        // Build vehicle-ID → typeId lookup from all carriers
        Map<String, String> globalVehTypeMap = new LinkedHashMap<>();
        for (ParsedCarrier c : carriers) {
            if (c.vehicleTypeMap() != null) {
                for (ParsedTour t : c.tours()) {
                    String typeId = c.vehicleTypeMap().get(t.vehicleId());
                    if (typeId != null) {
                        globalVehTypeMap.put(t.eventVehicleId(), typeId);
                    }
                }
            }
        }

        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String vehId : eventHandler.getVehicleTours().keySet()) {
            if (excludedLowUtilVehicles.contains(vehId)) continue;
            String label = globalVehTypeMap.getOrDefault(vehId, null);
            if (label == null) {
                VehicleType vt = classifyForKpi(vehId);
                label = vt != null ? vt.label() : "Other";
            }
            counts.merge(label, 1, Integer::sum);
        }
        StringBuilder labels = new StringBuilder("["), values = new StringBuilder("["), colors = new StringBuilder("[");
        boolean first = true;
        int colorIdx = 0;
        for (var entry : counts.entrySet()) {
            if (!first) { labels.append(","); values.append(","); colors.append(","); }
            first = false;
            labels.append("\"").append(escJson(entry.getKey())).append("\"");
            values.append(entry.getValue());
            colors.append("\"").append(VT_COLORS[colorIdx % VT_COLORS.length]).append("\"");
            colorIdx++;
        }
        return String.format("{\"labels\":%s],\"values\":%s],\"colors\":%s]}", labels, values, colors);
    }

    // ====================================================================
    // Distance histogram data (10km bins)
    // ====================================================================

    private String buildDistHistogramJson() {
        // Compute tour km per delivery vehicle from events
        Map<String, Double> vehKm = new HashMap<>();
        for (var entry : eventHandler.getVehicleTours().entrySet()) {
            String vehId = entry.getKey();
            if (excludedLowUtilVehicles.contains(vehId)) continue;
            if (classifyForKpi(vehId) != VehicleType.VAN && classifyForKpi(vehId) != VehicleType.CARGOBIKE) continue;
            double km = 0;
            for (LinkVisit lv : entry.getValue()) {
                Link link = network.getLinks().get(org.matsim.api.core.v01.Id.createLinkId(lv.linkId()));
                if (link != null) km += link.getLength() / 1000.0;
            }
            vehKm.put(vehId, km);
        }

        // Bin into 10km buckets 0-200+
        int numBins = 21;
        int binSize = 10;
        int[] counts = new int[numBins];
        for (double km : vehKm.values()) {
            int bin = Math.min(numBins - 1, (int) (km / binSize));
            counts[bin]++;
        }

        StringBuilder labels = new StringBuilder("["), vals = new StringBuilder("[");
        for (int i = 0; i < numBins; i++) {
            if (i > 0) { labels.append(","); vals.append(","); }
            labels.append(i < numBins - 1 ? String.format("\"%d-%d\"", i * binSize, (i + 1) * binSize)
                    : String.format("\"%d+\"", (numBins - 1) * binSize));
            vals.append(counts[i]);
        }
        return String.format("{\"labels\":%s],\"counts\":%s]}", labels, vals);
    }

    // ====================================================================
    // Duration histogram data (0.5h bins)
    // ====================================================================

    private String buildDurationHistogramJson() {
        Map<String, Double> depotDep = new HashMap<>();
        Map<String, Double> depotArr = new HashMap<>();
        for (TourBoundaryEvent e : eventHandler.getTourStarts()) depotDep.put(e.vehicleId(), e.timeSec());
        for (TourBoundaryEvent e : eventHandler.getTourEnds())   depotArr.put(e.vehicleId(), e.timeSec());

        // Only delivery vehicles
        int numBins = 25; // 0-12.5h in 0.5h bins
        double binH = 0.5;
        int[] counts = new int[numBins];
        for (var entry : depotDep.entrySet()) {
            String vehId = entry.getKey();
            if (excludedLowUtilVehicles.contains(vehId)) continue;
            VehicleType vt = classifyForKpi(vehId);
            if (vt != VehicleType.VAN && vt != VehicleType.CARGOBIKE) continue;
            Double arr = depotArr.get(vehId);
            if (arr == null) continue;
            double durH = (arr - entry.getValue()) / 3600.0;
            if (durH <= 0) continue;
            int bin = Math.min(numBins - 1, (int) (durH / binH));
            counts[bin]++;
        }

        StringBuilder labels = new StringBuilder("["), vals = new StringBuilder("[");
        for (int i = 0; i < numBins; i++) {
            if (i > 0) { labels.append(","); vals.append(","); }
            labels.append(String.format(Locale.US, "\"%.1f\"", (i + 1) * binH));
            vals.append(counts[i]);
        }
        return String.format("{\"labels\":%s],\"counts\":%s]}", labels, vals);
    }

    // ====================================================================
    // Utilisation data by provider (avg load factor + avg parcels/vehicle)
    // ====================================================================

    private String buildUtilizationJson() {
        Map<String, double[]> byProv = new LinkedHashMap<>(); // [sumLoadFactor, sumParcels, count]
        for (ParsedCarrier c : carriers) {
            if (c.isSupply()) continue;
            String p = c.provider().isEmpty() ? "other" : c.provider();
            Map<String, ParsedService> svcMap = new HashMap<>();
            for (ParsedService s : c.services()) svcMap.put(s.serviceId(), s);

            for (ParsedTour t : c.tours()) {
                int parcels = 0;
                for (TourAct a : t.acts()) {
                    if ("service".equals(a.type()) && a.serviceId() != null) {
                        ParsedService svc = svcMap.get(a.serviceId());
                        parcels += svc != null ? svc.capacityDemand() : 1;
                    }
                }
                if (parcels == 0) continue;
                if (excludedLowUtilVehicles.contains(t.eventVehicleId())) continue;
                int cap = lookupCapacity(c, t.vehicleId());
                double lf = Math.min(1.0, (double) parcels / cap);
                byProv.computeIfAbsent(p, k -> new double[3]);
                byProv.get(p)[0] += lf;
                byProv.get(p)[1] += parcels;
                byProv.get(p)[2] += 1;
            }
        }

        StringBuilder labels = new StringBuilder("["), avgLf = new StringBuilder("["), avgParcels = new StringBuilder("[");
        boolean first = true;
        for (var e : byProv.entrySet()) {
            if (!first) { labels.append(","); avgLf.append(","); avgParcels.append(","); }
            first = false;
            labels.append("\"").append(escJson(e.getKey())).append("\"");
            double cnt = e.getValue()[2];
            avgLf.append(String.format(Locale.US, "%.1f", cnt > 0 ? e.getValue()[0] / cnt * 100 : 0));
            avgParcels.append(String.format(Locale.US, "%.0f", cnt > 0 ? e.getValue()[1] / cnt : 0));
        }
        return String.format("{\"labels\":%s],\"avgLoadFactorPct\":%s],\"avgParcels\":%s]}", labels, avgLf, avgParcels);
    }

    // ====================================================================
    // Link volume GeoJSON — polylines with traffic counts
    // ====================================================================

    private String buildLinkVolumeGeoJson() {
        List<Map.Entry<String, LinkCounts>> sorted = eventHandler.getLinkCountMap().entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue().total(), a.getValue().total()))
                .filter(e -> e.getValue().total() > 0)
                .toList();
        LOG.info("Link volume GeoJSON: {} links with traffic", sorted.size());

        StringBuilder sb = new StringBuilder(1024 * 128);
        sb.append("{\"type\":\"FeatureCollection\",\"features\":[");
        boolean first = true;
        for (var entry : sorted) {
            Link link = network.getLinks().get(org.matsim.api.core.v01.Id.createLinkId(entry.getKey()));
            if (link == null) continue;
            double[] fromLL = toLatLon(link.getFromNode().getCoord());
            double[] toLL = toLatLon(link.getToNode().getCoord());
            LinkCounts lc = entry.getValue();

            // Build hourly arrays
            StringBuilder hTotal = new StringBuilder("[");
            StringBuilder hDeliv = new StringBuilder("[");
            StringBuilder hSuply = new StringBuilder("[");
            for (int h = 0; h < 24; h++) {
                if (h > 0) { hTotal.append(","); hDeliv.append(","); hSuply.append(","); }
                hTotal.append(lc.getHourlyTotal()[h]);
                hDeliv.append(lc.getHourlyDelivery()[h]);
                hSuply.append(lc.getHourlySupply()[h]);
            }
            hTotal.append("]"); hDeliv.append("]"); hSuply.append("]");

            if (!first) sb.append(",");
            first = false;
            sb.append(String.format(Locale.US,
                "{\"type\":\"Feature\",\"properties\":{\"total\":%d,\"delivery\":%d,\"supply\":%d," +
                "\"cap\":%.0f," +
                "\"ht\":%s,\"hd\":%s,\"hs\":%s}," +
                "\"geometry\":{\"type\":\"LineString\",\"coordinates\":[[%.6f,%.6f],[%.6f,%.6f]]}}",
                lc.total(), lc.totalDelivery(), lc.totalSupply(),
                link.getCapacity(),
                hTotal, hDeliv, hSuply,
                fromLL[1], fromLL[0], toLL[1], toLL[0]
            ));
        }
        sb.append("]}");
        return sb.toString();
    }

    // ====================================================================
    // Background network (sampled lightweight geometry for context)
    // ====================================================================

    private String buildNetworkBackgroundGeoJson() {
        // First compute the bounding box of all traffic links (with 20% margin)
        double tMinLat = Double.MAX_VALUE, tMaxLat = -Double.MAX_VALUE;
        double tMinLon = Double.MAX_VALUE, tMaxLon = -Double.MAX_VALUE;
        for (var entry : eventHandler.getLinkCountMap().entrySet()) {
            if (entry.getValue().total() <= 0) continue;
            Link link = network.getLinks().get(org.matsim.api.core.v01.Id.createLinkId(entry.getKey()));
            if (link == null) continue;
            double[] ll = toLatLon(link.getCoord());
            tMinLat = Math.min(tMinLat, ll[0]); tMaxLat = Math.max(tMaxLat, ll[0]);
            tMinLon = Math.min(tMinLon, ll[1]); tMaxLon = Math.max(tMaxLon, ll[1]);
        }
        double marginLat = (tMaxLat - tMinLat) * 0.2;
        double marginLon = (tMaxLon - tMinLon) * 0.2;
        tMinLat -= marginLat; tMaxLat += marginLat;
        tMinLon -= marginLon; tMaxLon += marginLon;

        // Now sample network links within that bounding box
        int totalLinks = network.getLinks().size();
        int step = Math.max(1, totalLinks / 50000);
        StringBuilder sb = new StringBuilder(1024 * 256);
        sb.append("{\"type\":\"FeatureCollection\",\"features\":[");
        boolean first = true;
        int idx = 0;
        int included = 0;
        for (Link link : network.getLinks().values()) {
            if (idx++ % step != 0) continue;
            double[] fromLL = toLatLon(link.getFromNode().getCoord());
            double[] toLL = toLatLon(link.getToNode().getCoord());
            // Spatial filter: skip links outside the traffic area + margin
            double midLat = (fromLL[0] + toLL[0]) / 2;
            double midLon = (fromLL[1] + toLL[1]) / 2;
            if (midLat < tMinLat || midLat > tMaxLat || midLon < tMinLon || midLon > tMaxLon) continue;
            if (!first) sb.append(",");
            first = false;
            included++;
            sb.append(String.format(Locale.US,
                "{\"type\":\"Feature\",\"geometry\":{\"type\":\"LineString\"," +
                "\"coordinates\":[[%.6f,%.6f],[%.6f,%.6f]]}}",
                fromLL[1], fromLL[0], toLL[1], toLL[0]));
        }
        sb.append("]}");
        LOG.info("Background network GeoJSON: {} links within traffic area (from {} total, step={})", included, totalLinks, step);
        return sb.toString();
    }

    // ====================================================================
    // Utilisation by vehicle type
    // ====================================================================

    private String buildUtilByTypeJson() {
        Map<String, double[]> byType = new LinkedHashMap<>(); // [sumLf, sumParcels, count, sumCap]
        for (ParsedCarrier c : carriers) {
            if (c.isSupply()) continue;
            Map<String, ParsedService> svcMap = new HashMap<>();
            for (ParsedService s : c.services()) svcMap.put(s.serviceId(), s);

            for (ParsedTour t : c.tours()) {
                int parcels = 0;
                for (TourAct a : t.acts()) {
                    if ("service".equals(a.type()) && a.serviceId() != null) {
                        ParsedService svc = svcMap.get(a.serviceId());
                        parcels += svc != null ? svc.capacityDemand() : 1;
                    }
                }
                if (parcels == 0) continue;
                if (excludedLowUtilVehicles.contains(t.eventVehicleId())) continue;
                int cap = lookupCapacity(c, t.vehicleId());
                double lf = Math.min(1.0, (double) parcels / cap);
                String typeId = c.vehicleTypeMap() != null ? c.vehicleTypeMap().get(t.vehicleId()) : null;
                String label = typeId != null ? typeId : "Other";
                byType.computeIfAbsent(label, k -> new double[4]);
                byType.get(label)[0] += lf;
                byType.get(label)[1] += parcels;
                byType.get(label)[2] += 1;
                byType.get(label)[3] += cap;
            }
        }
        StringBuilder labels = new StringBuilder("["), avgLf = new StringBuilder("["),
                avgParcels = new StringBuilder("["), avgCap = new StringBuilder("["),
                counts = new StringBuilder("[");
        boolean first = true;
        for (var e : byType.entrySet()) {
            if (!first) { labels.append(","); avgLf.append(","); avgParcels.append(","); avgCap.append(","); counts.append(","); }
            first = false;
            labels.append("\"").append(escJson(e.getKey())).append("\"");
            double cnt = e.getValue()[2];
            avgLf.append(String.format(Locale.US, "%.1f", cnt > 0 ? e.getValue()[0] / cnt * 100 : 0));
            avgParcels.append(String.format(Locale.US, "%.0f", cnt > 0 ? e.getValue()[1] / cnt : 0));
            avgCap.append(String.format(Locale.US, "%.0f", cnt > 0 ? e.getValue()[3] / cnt : 0));
            counts.append(String.format(Locale.US, "%.0f", cnt));
        }
        return String.format("{\"labels\":%s],\"avgLoadFactorPct\":%s],\"avgParcels\":%s],\"avgCap\":%s],\"counts\":%s]}",
            labels, avgLf, avgParcels, avgCap, counts);
    }

    // ====================================================================
    // Summary table JSON — per-provider aggregated statistics
    // ====================================================================

    private String buildSummaryTableJson() {
        List<ParsedCarrier> delivery = carriers.stream().filter(ParsedCarrier::isDelivery).toList();
        Map<String, double[]> stats = new LinkedHashMap<>();
        // [carriers, vehicles, parcels, missed, totalKm, totalDrivingH, totalCost, sumLf, lfCount,
        //  nonExclParcels, nonExclCost, totalTourDurH]

        // Collect per-vehicle rows grouped by provider
        // Each vehicle row: {carrier, vid, vtype, parcels, stops, distKm, durH, cap, loadFactor, fixCost, missed}
        Map<String, List<String>> vehByProv = new LinkedHashMap<>();

        for (ParsedCarrier c : delivery) {
            String p = c.provider().isEmpty() ? "other" : c.provider();
            stats.computeIfAbsent(p, k -> new double[12]);
            vehByProv.computeIfAbsent(p, k -> new ArrayList<>());
            double[] row = stats.get(p);
            row[0]++;
            // row[1] (vehicle count) is incremented per non-excluded tour with parcels > 0 below
            int carrierParcels = c.numberOfParcels() > 0 ? c.numberOfParcels() : c.numServices();
            row[2] += carrierParcels;
            // Proportionally allocate missed parcels to non-excluded tours (consistent with KPI)
            int allTours = c.tours().size();
            int nonExclToursCnt = (int) c.tours().stream()
                    .filter(t -> !excludedLowUtilVehicles.contains(t.eventVehicleId())).count();
            double missedRatio = allTours > 0 ? (double) nonExclToursCnt / allTours : 1.0;
            row[3] += (int) Math.round(c.numMissed() * missedRatio);

            // Build service map for parcel counting
            Map<String, ParsedService> svcMap = new HashMap<>();
            for (ParsedService s : c.services()) svcMap.put(s.serviceId(), s);

            // Carrier-level delivery rate (for per-vehicle display)
            double carrierDelRate = carrierParcels > 0
                    ? (carrierParcels - c.numMissed()) / (double) carrierParcels * 100 : 100;

            // Event-based distance + travel time aggregated from tours
            for (ParsedTour t : c.tours()) {
                String vid = t.eventVehicleId();
                if (excludedLowUtilVehicles.contains(vid)) continue;
                // row[1] incremented below only if parcels > 0 (consistent with KPI deliveryVehicleCount)
                double distKm = evtTourKm.getOrDefault(vid, 0.0);
                row[4] += distKm;
                double dep = evtDepSec.getOrDefault(vid, 0.0);
                double arr = evtArrSec.getOrDefault(vid, 0.0);
                double svc = evtSvcDurSec.getOrDefault(vid, 0.0);
                double tourDur = arr > dep ? arr - dep : 0;
                double travelH = Math.max(0, tourDur - svc) / 3600.0;
                row[5] += travelH;
                row[11] += tourDur / 3600.0;

                // Per-vehicle: parcels, stops, load factor
                int stops = evtStopCount.getOrDefault(vid, 0);
                int parcels = 0;
                for (TourAct a : t.acts()) {
                    if ("service".equals(a.type()) && a.serviceId() != null) {
                        ParsedService sv = svcMap.get(a.serviceId());
                        parcels += sv != null ? sv.capacityDemand() : 1;
                    }
                }
                int cap = lookupCapacity(c, t.vehicleId());
                double lf = cap > 0 ? Math.min(100.0, (double) parcels / cap * 100) : 0;
                if (parcels > 0) {
                    row[1]++; // count only non-excluded vehicles with parcels (consistent with KPI)
                    row[7] += Math.min(1.0, (double) parcels / cap);
                    row[8]++;
                    row[9] += parcels; // non-excluded parcels
                }
                double fixCost = lookupFixedCost(c, t.vehicleId());
                double cpk = lookupCostPerKm(c, t.vehicleId());
                String vType = c.vehicleTypeMap().getOrDefault(t.vehicleId(), "?");
                double totalDurH = tourDur / 3600.0;

                vehByProv.get(p).add(String.format(Locale.US,
                    "{\"carrier\":\"%s\",\"vid\":\"%s\",\"vtype\":\"%s\"," +
                    "\"parcels\":%d,\"stops\":%d,\"distKm\":%.1f,\"durH\":%.2f,\"travelH\":%.2f," +
                    "\"cap\":%d,\"loadFactor\":%.1f,\"fixCost\":%.1f,\"costPerKm\":%.4f," +
                    "\"delRate\":%.1f,\"depSec\":%.0f,\"arrSec\":%.0f}",
                    escJson(c.carrierId()), escJson(t.vehicleId()), escJson(vType),
                    parcels, stops, distKm, totalDurH, travelH,
                    cap, lf, fixCost, cpk,
                    carrierDelRate, dep, arr));
            }

            // Real cost = distance + time + overtime + vehicle fixed costs
            double carrierCost = c.costDistance() + c.costTime() + c.costOvertime();
            for (ParsedTour t : c.tours()) carrierCost += lookupFixedCost(c, t.vehicleId());
            row[6] += carrierCost;

            // Non-excluded cost: carrier variable cost proportionally allocated
            // to non-excluded tours only, plus their fixed costs
            int totalTours = c.tours().size();
            int nonExclTours = (int) c.tours().stream()
                    .filter(t -> !excludedLowUtilVehicles.contains(t.eventVehicleId())).count();
            double carrierVarCost = c.costDistance() + c.costTime() + c.costOvertime();
            double nonExclVarCost = totalTours > 0 ? carrierVarCost * nonExclTours / totalTours : 0;
            double nonExclFixCost = 0;
            for (ParsedTour t : c.tours()) {
                if (!excludedLowUtilVehicles.contains(t.eventVehicleId())) {
                    nonExclFixCost += lookupFixedCost(c, t.vehicleId());
                }
            }
            row[10] += nonExclVarCost + nonExclFixCost;
        }

        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (var e : stats.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            double[] r = e.getValue();
            double avgLf = r[8] > 0 ? r[7] / r[8] * 100 : 0;
            double successRate = r[9] > 0 ? (r[9] - r[3]) / r[9] * 100 : 100;
            double avgPpv = r[1] > 0 ? r[9] / r[1] : 0;
            double avgKmPv = r[1] > 0 ? r[4] / r[1] : 0;
            double costPerParcel = r[9] > 0 ? r[10] / r[9] : 0;

            // Build vehicle detail array
            List<String> vehs = vehByProv.getOrDefault(e.getKey(), List.of());
            String vehJson = "[" + String.join(",", vehs) + "]";

            sb.append(String.format(Locale.US,
                "{\"provider\":\"%s\",\"carriers\":%.0f,\"vehicles\":%.0f,\"parcels\":%.0f,\"missed\":%.0f," +
                "\"successRate\":%.1f,\"distKm\":%.0f,\"drivingH\":%.1f,\"tourDurH\":%.1f,\"cost\":%.0f,\"avgLoadFactor\":%.1f," +
                "\"avgParcelsPerVeh\":%.0f,\"avgKmPerVeh\":%.1f,\"costPerParcel\":%.2f,\"vehDetails\":%s}",
                escJson(e.getKey()), r[0], r[1], r[9], r[3], successRate, r[4], r[5], r[11], r[10], avgLf,
                avgPpv, avgKmPv, costPerParcel, vehJson));
        }
        sb.append("]");
        return sb.toString();
    }

    // ====================================================================
    // Routing Efficiency JSON — VRP solution quality metrics
    // ====================================================================

    /**
     * Builds per-provider routing efficiency KPIs for evaluating VRP solution quality:
     * stopsPerTour, kmPerTour, durPerTour, stopsPerHour, travelRatio,
     * stemRatio, parcelsPerKm, circuity factor, etc.
     */
    private String buildRoutingEfficiencyJson() {
        // Aggregate per provider
        // [sumStops, sumKm, sumDurH, sumTravelH, sumSvcH, sumParcels, sumStemKm, count, sumCost, sumCap]
        Map<String, double[]> byProv = new LinkedHashMap<>();

        for (ParsedCarrier c : carriers) {
            if (c.isSupply()) continue;
            String prov = c.provider().isEmpty() ? "other" : c.provider();
            Map<String, ParsedService> svcMap = new HashMap<>();
            for (ParsedService s : c.services()) svcMap.put(s.serviceId(), s);

            for (ParsedTour t : c.tours()) {
                String vehId = t.eventVehicleId();
                int parcels = 0;
                for (TourAct a : t.acts()) {
                    if ("service".equals(a.type()) && a.serviceId() != null) {
                        ParsedService svc = svcMap.get(a.serviceId());
                        parcels += svc != null ? svc.capacityDemand() : 1;
                    }
                }
                if (parcels == 0) continue;
                if (excludedLowUtilVehicles.contains(vehId)) continue;

                int cap = lookupCapacity(c, t.vehicleId());

                // All from pre-computed event maps
                double km        = evtTourKm.getOrDefault(vehId, 0.0);
                double depSec    = evtDepSec.getOrDefault(vehId, 0.0);
                double arrSec    = evtArrSec.getOrDefault(vehId, 0.0);
                double svcDurSec = evtSvcDurSec.getOrDefault(vehId, 0.0);
                double durH      = arrSec > depSec ? (arrSec - depSec) / 3600.0 : 0;
                double travelH   = Math.max(0, durH - svcDurSec / 3600.0);
                int    stops     = evtStopCount.getOrDefault(vehId, 0);

                // Stem distance: sum network link lengths for the first leg's route
                double stemKm = 0;
                if (!t.legs().isEmpty()) {
                    for (String lid : t.legs().getFirst().routeLinkIds()) {
                        Link stemLink = network.getLinks().get(org.matsim.api.core.v01.Id.createLinkId(lid));
                        if (stemLink != null) stemKm += stemLink.getLength() / 1000.0;
                    }
                }

                byProv.computeIfAbsent(prov, k -> new double[10]);
                double[] row = byProv.get(prov);
                row[0] += stops;
                row[1] += km;
                row[2] += durH;
                row[3] += travelH;
                row[4] += svcDurSec / 3600.0;
                row[5] += parcels;
                row[6] += stemKm;
                row[7] += 1; // count
                // Real cost per tour: share of carrier's dist+time+overtime cost + vehicle fixed cost
                // Divide carrier variable cost by non-excluded tour count (not total)
                // so that cost allocated to included tours is not diluted by excluded ones.
                long nonExclCount = c.tours().stream()
                        .filter(tt -> !excludedLowUtilVehicles.contains(tt.eventVehicleId())
                                && countTourParcels(c, tt) > 0).count();
                double carrierShareCost = (c.costDistance() + c.costTime() + c.costOvertime()) / Math.max(1, nonExclCount);
                double vehicleFixCost = lookupFixedCost(c, t.vehicleId());
                row[8] += carrierShareCost + vehicleFixCost;
                row[9] += cap;
            }
        }

        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (var e : byProv.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            double[] r = e.getValue();
            double n = r[7]; // tour count
            double avgStops = n > 0 ? r[0] / n : 0;
            double avgKm = n > 0 ? r[1] / n : 0;
            double avgDurH = n > 0 ? r[2] / n : 0;
            double avgTravelH = n > 0 ? r[3] / n : 0;
            double avgSvcH = n > 0 ? r[4] / n : 0;
            double avgParcels = n > 0 ? r[5] / n : 0;
            double stopsPerHour = r[2] > 0 ? r[0] / r[2] : 0;
            double parcelsPerKm = r[1] > 0 ? r[5] / r[1] : 0;
            double travelRatio = r[2] > 0 ? r[3] / r[2] * 100 : 0; // % of tour spent driving
            double svcRatio = r[2] > 0 ? r[4] / r[2] * 100 : 0;
            double stemRatio = r[1] > 0 ? r[6] / r[1] * 100 : 0;
            double avgCostPerTour = n > 0 ? r[8] / n : 0;
            double kmPerParcel = r[5] > 0 ? r[1] / r[5] : 0;
            double costPerParcel = r[5] > 0 ? r[8] / r[5] : 0;
            double avgCap = n > 0 ? r[9] / n : 0;
            double avgLoadFactor = r[9] > 0 ? r[5] / r[9] * 100 : 0;

            sb.append(String.format(Locale.US,
                "{\"provider\":\"%s\",\"tours\":%.0f,\"avgStops\":%.1f,\"avgKm\":%.1f," +
                "\"avgDurH\":%.2f,\"avgTravelH\":%.2f,\"avgSvcH\":%.2f,\"avgParcels\":%.1f," +
                "\"stopsPerHour\":%.1f,\"parcelsPerKm\":%.2f,\"kmPerParcel\":%.2f," +
                "\"travelPct\":%.1f,\"svcPct\":%.1f,\"stemPct\":%.1f," +
                "\"avgCostPerTour\":%.1f,\"costPerParcel\":%.2f,\"avgCap\":%.0f,\"avgLoadPct\":%.1f}",
                escJson(e.getKey()), n, avgStops, avgKm,
                avgDurH, avgTravelH, avgSvcH, avgParcels,
                stopsPerHour, parcelsPerKm, kmPerParcel,
                travelRatio, svcRatio, stemRatio,
                avgCostPerTour, costPerParcel, avgCap, avgLoadFactor));
        }
        sb.append("]");
        return sb.toString();
    }

    // ====================================================================
    // Helpers
    // ====================================================================

    private static VehicleType classifyForKpi(String v) {
        return FreightEventHandler.classifyVehicle(v);
    }

    /** Rough vehicle capacity estimate from vehicle ID naming convention. */
    private static int estimateCapacity(String vehicleId) {
        String lower = vehicleId.toLowerCase();
        if (lower.contains("cargobike") || lower.contains("cargo_bike")) return 30;
        if (lower.contains("size_m"))  return 80;
        if (lower.contains("size_l"))  return 230;
        if (lower.contains("size_s"))  return 50;
        if (lower.contains("light_van") || lower.contains("supply_light")) return 80;
        if (lower.contains("supply"))  return 350;
        return 230; // default CEP van
    }

    /** Count the number of parcels on a tour by summing service capacity demands. */
    private int countTourParcels(ParsedCarrier carrier, ParsedTour tour) {
        Map<String, ParsedService> svcMap = new HashMap<>();
        for (ParsedService s : carrier.services()) svcMap.put(s.serviceId(), s);
        int parcels = 0;
        for (TourAct a : tour.acts()) {
            if ("service".equals(a.type()) && a.serviceId() != null) {
                ParsedService svc = svcMap.get(a.serviceId());
                parcels += svc != null ? svc.capacityDemand() : 1;
            }
        }
        return parcels;
    }

    /** Look up actual vehicle capacity from vehicle types data. Falls back to heuristic. */
    private int lookupCapacity(ParsedCarrier carrier, String vehicleId) {
        // Try exact lookup via carrier's vehicle→typeId mapping
        String typeId = carrier.vehicleTypeMap() != null ? carrier.vehicleTypeMap().get(vehicleId) : null;
        if (typeId != null) {
            Double cap = vehicleTypeCapacities.get(typeId);
            if (cap != null && cap > 0) return (int) Math.round(cap);
        }
        // Fallback: derive typeId from naming convention (ct_ + strip trailing _N)
        String derived = "ct_" + vehicleId.replaceAll("_\\d+$", "");
        Double cap = vehicleTypeCapacities.get(derived);
        if (cap != null && cap > 0) return (int) Math.round(cap);
        return estimateCapacity(vehicleId);
    }

    /** Look up cost per km (€/km) for a vehicle from its type. */
    private double lookupCostPerKm(ParsedCarrier carrier, String vehicleId) {
        String typeId = carrier.vehicleTypeMap() != null ? carrier.vehicleTypeMap().get(vehicleId) : null;
        if (typeId != null) {
            Double cpk = vehicleTypeCostsPerKm.get(typeId);
            if (cpk != null) return cpk;
        }
        String derived = "ct_" + vehicleId.replaceAll("_\\d+$", "");
        Double cpk = vehicleTypeCostsPerKm.get(derived);
        return cpk != null ? cpk : 0;
    }

    /** Look up fixed cost per day for a vehicle from its type. */
    private double lookupFixedCost(ParsedCarrier carrier, String vehicleId) {
        String typeId = carrier.vehicleTypeMap() != null ? carrier.vehicleTypeMap().get(vehicleId) : null;
        if (typeId != null) {
            Double fc = vehicleTypeFixedCosts.get(typeId);
            if (fc != null) return fc;
        }
        String derived = "ct_" + vehicleId.replaceAll("_\\d+$", "");
        Double fc = vehicleTypeFixedCosts.get(derived);
        return fc != null ? fc : 0;
    }

    private static String fmt(double v) { return String.format(Locale.US, "%.1f", v); }

    private static String escJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private static String escHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String fmtHMS(double sec) {
        int s = Math.max(0, (int) Math.round(sec));
        return String.format("%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60);
    }

    /**
     * Returns an HTML section shown at the bottom of the dashboard when
     * vehicles have been excluded due to low utilisation.
     * Lists every excluded vehicle with carrier, type, parcels, capacity,
     * load factor, distance, and duration so that the exclusion is transparent.
     * Returns an empty string when no vehicles were excluded.
     */
    private String buildLowUtilNoticeHtml() {
        if (excludedLowUtilVehicles.isEmpty()) return "";

        // Collect detail rows for every excluded vehicle
        // Each row: carrier, provider, vehicleId, vehicleType, parcels, capacity, loadFactor%, distKm, durH
        List<String> rows = new ArrayList<>();
        for (ParsedCarrier c : carriers) {
            if (c.isSupply()) continue;
            Map<String, ParsedService> svcMap = new HashMap<>();
            for (ParsedService s : c.services()) svcMap.put(s.serviceId(), s);

            for (ParsedTour t : c.tours()) {
                String evtId = t.eventVehicleId();
                if (!excludedLowUtilVehicles.contains(evtId)) continue;

                int parcels = 0;
                for (TourAct a : t.acts()) {
                    if ("service".equals(a.type()) && a.serviceId() != null) {
                        ParsedService sv = svcMap.get(a.serviceId());
                        parcels += sv != null ? sv.capacityDemand() : 1;
                    }
                }
                int cap = lookupCapacity(c, t.vehicleId());
                double lf = cap > 0 ? Math.min(100.0, (double) parcels / cap * 100) : 0;
                double distKm = evtTourKm.getOrDefault(evtId, 0.0);
                double depSec = evtDepSec.getOrDefault(evtId, 0.0);
                double arrSec = evtArrSec.getOrDefault(evtId, 0.0);
                double durH = arrSec > depSec ? (arrSec - depSec) / 3600.0 : 0;
                int stops = evtStopCount.getOrDefault(evtId, 0);
                String vType = c.vehicleTypeMap() != null
                        ? c.vehicleTypeMap().getOrDefault(t.vehicleId(), "?") : "?";

                rows.add(String.format(Locale.US,
                    "<tr style=\"border-bottom:1px solid rgba(148,163,184,.06)\">"
                    + "<td style=\"padding:4px 8px;color:var(--dim);font-size:.7rem;white-space:nowrap\">%s</td>"
                    + "<td style=\"padding:4px 8px;font-weight:600;color:%s\">%s</td>"
                    + "<td style=\"padding:4px 8px;white-space:nowrap\">%s</td>"
                    + "<td style=\"padding:4px 8px;color:var(--dim)\">%s</td>"
                    + "<td style=\"padding:4px 8px;text-align:right\">%d</td>"
                    + "<td style=\"padding:4px 8px;text-align:right\">%d</td>"
                    + "<td style=\"padding:4px 8px;text-align:right\">%d</td>"
                    + "<td style=\"padding:4px 8px;text-align:right;color:#f87171;font-weight:700\">%.1f</td>"
                    + "<td style=\"padding:4px 8px;text-align:right\">%.1f</td>"
                    + "<td style=\"padding:4px 8px;text-align:right\">%.2f</td>"
                    + "</tr>",
                    escHtml(c.carrierId()),
                    providerColor(c.provider()),
                    escHtml(c.provider().isEmpty() ? "other" : c.provider()),
                    escHtml(t.vehicleId()),
                    escHtml(vType.replace("ct_", "")),
                    parcels, cap, stops, lf, distKm, durH));
            }
        }

        StringBuilder sb = new StringBuilder();
        // Section title
        sb.append("<div class=\"stit\">Excluded Low-Utilisation Vehicles</div>");
        // Info box
        sb.append(String.format(Locale.US,
            "<div style=\"margin:0 22px 10px;padding:14px 18px;background:rgba(251,191,36,.10);"
            + "border:1px solid rgba(251,191,36,.30);border-radius:10px;color:#fbbf24;"
            + "font-size:.82rem;line-height:1.6\">"
            + "<strong>&#9888; Low-utilisation filter (threshold: &lt;&thinsp;%.0f&thinsp;%%):</strong> "
            + "%d vehicle(s) were excluded because their load factor (parcels&thinsp;/&thinsp;capacity) "
            + "fell below the threshold. "
            + "<br><br><b>Affected sections:</b> All KPI indicators at the top of this page "
            + "(vehicle counts, parcels, stops, utilisation, costs, distances, delivery rate), "
            + "Provider Summary table, Cost Analysis, VRP Routing Efficiency, "
            + "Tour Structure histograms, Departure &amp; Service Timing charts, "
            + "Active Vehicles timeline, Depot Trips, Vehicle Type breakdown, "
            + "Utilisation by Provider, Utilisation by Type, and Carrier Scoring Explorer. "
            + "<br><b>Not affected:</b> Tour map layers (all routes remain visible), "
            + "Link Traffic heatmaps, Scoring Convergence, and the carrier/supply carrier counts."
            + "</div>",
            lowUtilThreshold * 100, excludedLowUtilVehicles.size()));
        // Table
        sb.append("<div style=\"padding:0 22px 18px\"><div class=\"cc\" style=\"padding:0;overflow:hidden\">");
        sb.append("<table class=\"rtbl\" style=\"width:100%%;border-collapse:collapse;font-size:.76rem\">");
        sb.append("<thead><tr style=\"border-bottom:2px solid rgba(148,163,184,.15)\">"
            + "<th style=\"padding:6px 8px;text-align:left\">Carrier</th>"
            + "<th style=\"padding:6px 8px;text-align:left\">Provider</th>"
            + "<th style=\"padding:6px 8px;text-align:left\">Vehicle ID</th>"
            + "<th style=\"padding:6px 8px;text-align:left\">Type</th>"
            + "<th style=\"padding:6px 8px;text-align:right\">Parcels</th>"
            + "<th style=\"padding:6px 8px;text-align:right\">Capacity</th>"
            + "<th style=\"padding:6px 8px;text-align:right\">Stops</th>"
            + "<th style=\"padding:6px 8px;text-align:right;color:#f87171;font-weight:700\">Load&thinsp;%</th>"
            + "<th style=\"padding:6px 8px;text-align:right\">Dist&thinsp;(km)</th>"
            + "<th style=\"padding:6px 8px;text-align:right\">Dur&thinsp;(h)</th>"
            + "</tr></thead><tbody>");
        for (String row : rows) sb.append(row);
        sb.append("</tbody></table></div></div>");

        return sb.toString();
    }

    /** Return a CSS colour string for a provider name (consistent with PROV_COLORS in JS). */
    private static String providerColor(String provider) {
        if (provider == null || provider.isEmpty()) return "#6b7280";
        return switch (provider.toLowerCase(Locale.ROOT)) {
            case "dhl"     -> "#ffcc00";
            case "hermes"  -> "#3b82f6";
            case "dpd"     -> "#ef4444";
            case "ups"     -> "#7c3a12";
            case "gls"     -> "#10b981";
            case "fedex"   -> "#8b5cf6";
            case "amazon"  -> "#f59e0b";
            default        -> "#6b7280";
        };
    }

    // ====================================================================
    // UTM → WGS84
    // ====================================================================

    private static double[] toLatLon(Coord c) {
        double easting = c.getX(), northing = c.getY();
        double lon0 = 9.0, k0 = 0.9996, a = 6378137.0;
        double f = 1.0 / 298.257223563, e2 = 2 * f - f * f;
        double e1 = (1 - Math.sqrt(1 - e2)) / (1 + Math.sqrt(1 - e2));
        double x = easting - 500000.0, y = northing;
        double M = y / k0;
        double mu = M / (a * (1 - e2/4 - 3*e2*e2/64 - 5*e2*e2*e2/256));
        double phi1 = mu + (3*e1/2 - 27*e1*e1*e1/32)*Math.sin(2*mu)
                + (21*e1*e1/16 - 55*e1*e1*e1*e1/32)*Math.sin(4*mu)
                + (151*e1*e1*e1/96)*Math.sin(6*mu);
        double sinP = Math.sin(phi1), cosP = Math.cos(phi1), tanP = sinP/cosP;
        double N1 = a/Math.sqrt(1-e2*sinP*sinP);
        double T1 = tanP*tanP, C1 = (e2/(1-e2))*cosP*cosP;
        double R1 = a*(1-e2)/Math.pow(1-e2*sinP*sinP,1.5);
        double D = x/(N1*k0);
        double lat = phi1 - (N1*tanP/R1)*(D*D/2
                - (5+3*T1+10*C1-4*C1*C1-9*e2/(1-e2))*D*D*D*D/24
                + (61+90*T1+298*C1+45*T1*T1-252*e2/(1-e2)-3*C1*C1)*D*D*D*D*D*D/720);
        double lon = (D - (1+2*T1+C1)*D*D*D/6
                + (5-2*C1+28*T1-3*C1*C1+8*e2/(1-e2)+24*T1*T1)*D*D*D*D*D/120)/cosP;
        return new double[]{Math.toDegrees(lat), Math.toDegrees(lon)+lon0};
    }

    // ====================================================================
    // HTML Assembly
    // ====================================================================

    private String assembleHtml(String kpiJson, String vehiclesJson,
                                String tourGeoJson, String stopGeoJson,
                                String linkHeatJson, String linkHeatLmd, String linkHeatSup,
                                String providerJson, String costJson,
                                String timelineJson, String vehTypeJson,
                                String distHistoJson, String durHistoJson,
                                String utilizationJson,
                                String linkVolumeJson, String utilByTypeJson,
                                String summaryTableJson,
                                String routingEffJson,
                                String networkBgJson,
                                String scoringJson,
                                String hourlySvcJson,
                String activeVehJson,
                String depotTripsJson,
                String carrierDetailJson) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));

        double minLat = Double.MAX_VALUE, maxLat = -Double.MAX_VALUE;
        double minLon = Double.MAX_VALUE, maxLon = -Double.MAX_VALUE;
        int sampled = 0;
        for (Link link : network.getLinks().values()) {
            if (sampled++ % 50 != 0) continue;
            double[] ll = toLatLon(link.getCoord());
            minLat = Math.min(minLat, ll[0]); maxLat = Math.max(maxLat, ll[0]);
            minLon = Math.min(minLon, ll[1]); maxLon = Math.max(maxLon, ll[1]);
        }
        double centerLat = (minLat + maxLat) / 2;
        double centerLon = (minLon + maxLon) / 2;

        // Provider + carrier lists for filters
        List<String> providers = carriers.stream().filter(ParsedCarrier::isDelivery)
                .map(c -> c.provider().isEmpty() ? "other" : c.provider()).distinct().sorted().toList();
        List<String> carrierIds = carriers.stream().filter(ParsedCarrier::isDelivery)
                .map(ParsedCarrier::carrierId).sorted().toList();

        StringBuilder providerListJson = new StringBuilder("[");
        for (int i = 0; i < providers.size(); i++) {
            if (i > 0) providerListJson.append(",");
            providerListJson.append("\"").append(escJson(providers.get(i))).append("\"");
        }
        providerListJson.append("]");

        StringBuilder carrierListJson = new StringBuilder("[");
        for (int i = 0; i < carrierIds.size(); i++) {
            if (i > 0) carrierListJson.append(",");
            carrierListJson.append("\"").append(escJson(carrierIds.get(i))).append("\"");
        }
        carrierListJson.append("]");

        // Carrier→provider map for cascading filter in UI
        StringBuilder carrierProvMapJson = new StringBuilder("{");
        boolean cpFirst = true;
        for (ParsedCarrier c : carriers) {
            if (c.isSupply()) continue;
            if (!cpFirst) carrierProvMapJson.append(",");
            cpFirst = false;
            carrierProvMapJson.append("\"").append(escJson(c.carrierId())).append("\":\"")
                    .append(escJson(c.provider().isEmpty() ? "other" : c.provider())).append("\"");
        }
        carrierProvMapJson.append("}");

        // Format each part separately to stay below Java's 65535-byte constant limit.
        // PART1 uses %1$s..%5$s, PART2 uses %6$s..%27$s — but String.format requires
        // all args for positional references, so we pass the full arg list to both.
        Object[] fmtArgs = new Object[]{
                /* 1 */ escHtml(runId),
                /* 2 */ timestamp,
                /* 3 */ centerLat,
                /* 4 */ centerLon,
                /* 5 */ String.format(Locale.US, "[[%.6f,%.6f],[%.6f,%.6f]]", minLat, minLon, maxLat, maxLon),
                /* 6 */ kpiJson,
                /* 7 */ vehiclesJson,
                /* 8 */ tourGeoJson,
                /* 9 */ stopGeoJson,
                /* 10 */ linkHeatJson,
                /* 11 */ linkHeatLmd,
                /* 12 */ linkHeatSup,
                /* 13 */ providerJson,
                /* 14 */ costJson,
                /* 15 */ timelineJson,
                /* 16 */ vehTypeJson,
                /* 17 */ distHistoJson,
                /* 18 */ durHistoJson,
                /* 19 */ utilizationJson,
                /* 20 */ providerListJson.toString(),
                /* 21 */ carrierListJson.toString(),
                /* 22 */ linkVolumeJson,
                /* 23 */ utilByTypeJson,
                /* 24 */ summaryTableJson,
                /* 25 */ routingEffJson,
                /* 26 */ carrierProvMapJson.toString(),
                /* 27 */ networkBgJson,
                /* 28 */ scoringJson,
                /* 29 */ hourlySvcJson,
                /* 30 */ activeVehJson,
                /* 31 */ depotTripsJson,
                /* 32 */ carrierDetailJson,
                /* 33 */ buildLowUtilNoticeHtml()
        };
        return String.format(Locale.US, HTML_PART1, fmtArgs)
             + String.format(Locale.US, HTML_PART2, fmtArgs)
             + String.format(Locale.US, HTML_PART2B, fmtArgs)
             + String.format(Locale.US, HTML_PART3, fmtArgs);
    }

    // ====================================================================
    // HTML TEMPLATE
    // ====================================================================

    // Template is split into parts to stay below Java's 65535-byte string constant limit.
    private static final String HTML_PART1 = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>HAGRID Dashboard – %1$s</title>
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css"/>
<link rel="stylesheet" href="https://unpkg.com/leaflet.markercluster@1.5.3/dist/MarkerCluster.css"/>
<link rel="stylesheet" href="https://unpkg.com/leaflet.markercluster@1.5.3/dist/MarkerCluster.Default.css"/>
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
<script src="https://unpkg.com/leaflet.markercluster@1.5.3/dist/leaflet.markercluster.js"></script>
<script src="https://unpkg.com/leaflet.heat@0.2.0/dist/leaflet-heat.js"></script>
<script src="https://cdn.jsdelivr.net/npm/chart.js@4.4.4/dist/chart.umd.min.js"></script>
<style>
:root{--bg:#0f172a;--surface:rgba(30,41,59,.85);--glass:rgba(51,65,85,.45);--text:#e2e8f0;--dim:#94a3b8;--accent:#38bdf8;--accent2:#818cf8;--success:#34d399;--danger:#f87171;--warn:#fbbf24;--r:14px}
*{margin:0;padding:0;box-sizing:border-box}
body{font-family:'Inter',-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;background:var(--bg);color:var(--text);overflow-x:hidden;min-height:100vh}
.hdr{background:linear-gradient(135deg,#0f172a 0%%,#1e293b 30%%,#0f172a 60%%,#1e1b4b 100%%);padding:20px 28px;display:flex;align-items:center;gap:18px;border-bottom:1px solid rgba(56,189,248,.15);position:relative;overflow:hidden}
.hdr::before{content:'';position:absolute;inset:-50%%;width:200%%;height:200%%;background:radial-gradient(circle at 30%% 50%%,rgba(56,189,248,.08) 0%%,transparent 50%%),radial-gradient(circle at 70%% 80%%,rgba(129,140,248,.06) 0%%,transparent 50%%);animation:shim 8s ease-in-out infinite alternate}
@keyframes shim{0%%{transform:translateX(-10%%)}100%%{transform:translateX(10%%)}}
.hdr h1{font-size:1.5rem;font-weight:700;z-index:1;background:linear-gradient(135deg,#38bdf8,#818cf8);-webkit-background-clip:text;-webkit-text-fill-color:transparent}
.hdr .meta{font-size:.78rem;color:var(--dim);z-index:1}
.hdr .logo{width:44px;height:44px;border-radius:11px;background:linear-gradient(135deg,#38bdf8,#818cf8);display:flex;align-items:center;justify-content:center;font-size:1.3rem;font-weight:900;color:#0f172a;z-index:1;box-shadow:0 0 18px rgba(56,189,248,.3)}
.kstrip{display:grid;grid-template-columns:repeat(auto-fit,minmax(140px,1fr));gap:8px;padding:12px 22px}
.kpi{background:var(--glass);backdrop-filter:blur(16px);border-radius:var(--r);padding:12px 14px;border:1px solid rgba(148,163,184,.1);transition:transform .2s,box-shadow .2s}
.kpi:hover{transform:translateY(-2px);box-shadow:0 8px 28px rgba(56,189,248,.12)}
.kpi .v{font-size:1.5rem;font-weight:800;line-height:1;background:linear-gradient(135deg,var(--accent),var(--accent2));-webkit-background-clip:text;-webkit-text-fill-color:transparent}
.kpi .l{font-size:.68rem;color:var(--dim);margin-top:3px;text-transform:uppercase;letter-spacing:.5px}
.kpi.ok .v{background:linear-gradient(135deg,#34d399,#38bdf8);-webkit-background-clip:text;-webkit-text-fill-color:transparent}
.kpi.warn .v{background:linear-gradient(135deg,#fbbf24,#f97316);-webkit-background-clip:text;-webkit-text-fill-color:transparent}
.main{display:grid;grid-template-columns:280px 1fr 340px;gap:0;min-height:calc(100vh - 220px)}
@media(max-width:1200px){.main{grid-template-columns:240px 1fr 300px}}
@media(max-width:900px){.main{grid-template-columns:1fr;grid-template-rows:auto 500px auto}}
.lp{background:var(--surface);backdrop-filter:blur(20px);border-right:1px solid rgba(148,163,184,.1);padding:10px;overflow-y:auto;max-height:calc(100vh - 220px)}
.lp h3{font-size:.72rem;font-weight:700;text-transform:uppercase;letter-spacing:1px;color:var(--accent);margin:10px 0 5px}
.lp h3:first-child{margin-top:0}
.lp-sec{background:var(--glass);border-radius:var(--r);padding:8px 10px;margin-bottom:8px;border:1px solid rgba(148,163,184,.08)}
.lp label{font-size:.76rem;color:var(--dim);cursor:pointer;display:flex;align-items:center;gap:4px;padding:1px 0}
.lp label:hover{color:var(--text)}
.lp input[type=checkbox],.lp input[type=radio]{accent-color:var(--accent);margin:0}
.lp select{width:100%%;padding:5px 8px;border-radius:8px;background:rgba(15,23,42,.7);color:var(--text);border:1px solid rgba(148,163,184,.2);font-size:.78rem;cursor:pointer;outline:none;margin-top:3px}
.lp select:focus{border-color:var(--accent);box-shadow:0 0 0 2px rgba(56,189,248,.2)}
.mode-btns{display:flex;gap:0;border-radius:10px;overflow:hidden;border:1px solid rgba(148,163,184,.15);margin-bottom:6px}
.mode-btn{flex:1;padding:7px 4px;font-size:.72rem;font-weight:600;text-align:center;cursor:pointer;background:transparent;color:var(--dim);border:none;transition:all .15s;text-transform:uppercase;letter-spacing:.5px}
.mode-btn.active{background:var(--accent);color:#0f172a}
.mode-btn:hover:not(.active){background:rgba(56,189,248,.12);color:var(--text)}
.prov-grid{display:flex;flex-direction:column;gap:2px}
.prov-it{display:flex;align-items:center;gap:5px;padding:3px 6px;border-radius:6px;cursor:pointer;transition:background .15s}
.prov-it:hover{background:rgba(56,189,248,.08)}
.prov-it .dot{width:8px;height:8px;border-radius:50%%;flex-shrink:0}
.prov-it input{accent-color:var(--accent);margin:0}
.prov-it span{font-size:.76rem;color:var(--dim)}
.prov-it.active span{color:var(--text);font-weight:600}
.veh-count{font-size:.65rem;color:var(--dim);margin-left:auto;font-family:monospace}
.color-pill{display:inline-flex;background:var(--glass);border-radius:8px;border:1px solid rgba(148,163,184,.08);overflow:hidden;margin-top:4px}
.color-pill button{padding:4px 10px;font-size:.7rem;background:transparent;color:var(--dim);border:none;cursor:pointer;transition:all .2s}
.color-pill button.active{background:var(--accent);color:#0f172a;font-weight:700}
.status-bar{padding:6px 10px;font-size:.72rem;color:var(--dim);background:rgba(56,189,248,.05);border-radius:8px;margin-top:6px;text-align:center}
#map{width:100%%;height:100%%;min-height:580px;background:#1e293b}
.leaflet-container{background:#1e293b !important}
.leaflet-tile-pane{filter:brightness(.85) contrast(1.1) saturate(.85)}
.sb{background:var(--surface);backdrop-filter:blur(20px);border-left:1px solid rgba(148,163,184,.1);padding:12px;overflow-y:auto;max-height:calc(100vh - 220px)}
.sb h3{font-size:.72rem;font-weight:700;text-transform:uppercase;letter-spacing:1px;color:var(--accent);margin:10px 0 5px}
.sb h3:first-child{margin-top:0}
.cc{background:var(--glass);border-radius:var(--r);padding:10px;margin-bottom:8px;border:1px solid rgba(148,163,184,.08)}
.cc canvas{width:100%% !important;max-height:180px}
.cc h4{color:var(--accent);margin:0 0 5px;font-size:.68rem;letter-spacing:1px;text-transform:uppercase;font-weight:700}
.stit{font-size:.82rem;font-weight:700;color:var(--accent);text-transform:uppercase;letter-spacing:1.5px;padding:16px 22px 4px;display:flex;align-items:center;gap:10px}
.stit::after{content:'';flex:1;height:1px;background:rgba(56,189,248,.15)}
.cstrip{display:grid;grid-template-columns:repeat(auto-fit,minmax(360px,1fr));gap:10px;padding:12px 22px}
.stop-badge{width:22px;height:22px;background:rgba(15,23,42,.9);border:2px solid var(--accent);border-radius:6px;font-family:monospace;font-weight:700;font-size:10px;line-height:18px;text-align:center;color:#38bdf8;box-shadow:0 2px 8px rgba(0,0,0,.4);cursor:pointer}
.stop-badge-active{border-color:#f59e0b !important;color:#f59e0b !important;background:rgba(245,158,11,.15) !important;box-shadow:0 0 12px rgba(245,158,11,.6)}
@keyframes glowPulse{0%%,100%%{opacity:.7;filter:drop-shadow(0 0 6px rgba(245,158,11,.8))}50%%{opacity:1;filter:drop-shadow(0 0 14px rgba(245,158,11,1))}}
@keyframes pinkGlow{0%%,100%%{opacity:.6;filter:drop-shadow(0 0 8px rgba(236,72,153,.8))}50%%{opacity:1;filter:drop-shadow(0 0 16px rgba(236,72,153,1))}}
.map-legend{position:absolute;bottom:20px;left:20px;z-index:1000;background:rgba(15,23,42,.9);backdrop-filter:blur(12px);border-radius:12px;padding:10px 14px;border:1px solid rgba(148,163,184,.15);font-size:.73rem;max-height:300px;overflow-y:auto}
.map-legend .it{display:flex;align-items:center;gap:7px;margin:2px 0}
.map-legend .dt{width:10px;height:10px;border-radius:50%%;display:inline-block}
.leaflet-popup-content-wrapper{background:rgba(30,41,59,.95) !important;color:var(--text) !important;border-radius:12px !important;border:1px solid rgba(56,189,248,.2) !important;backdrop-filter:blur(12px)}
.leaflet-popup-tip{background:rgba(30,41,59,.95) !important}
.leaflet-popup-content{font-family:monospace;font-size:.8rem;line-height:1.5}
::-webkit-scrollbar{width:6px}::-webkit-scrollbar-track{background:transparent}::-webkit-scrollbar-thumb{background:rgba(148,163,184,.3);border-radius:3px}
.rtbl{width:100%%;border-collapse:collapse;font-size:.76rem;margin:0;background:transparent}
.rtbl th{background:rgba(56,189,248,.12);color:var(--accent);font-weight:700;padding:8px 10px;text-align:left;white-space:nowrap;font-size:.7rem;text-transform:uppercase;letter-spacing:.5px;border-bottom:2px solid rgba(56,189,248,.2);position:relative;cursor:help}
.rtbl th[data-tip]:hover::after{content:attr(data-tip);position:absolute;left:50%%;transform:translateX(-50%%);top:calc(100%% + 6px);background:#1e293b;color:#e2e8f0;border:1px solid rgba(56,189,248,.3);border-radius:6px;padding:8px 12px;font-size:.72rem;font-weight:400;text-transform:none;letter-spacing:0;white-space:normal;min-width:220px;max-width:340px;z-index:9999;box-shadow:0 8px 24px rgba(0,0,0,.5);line-height:1.4;pointer-events:none}
.rtbl td[data-tip]{position:relative;cursor:help}
.rtbl td[data-tip]:hover::after{content:attr(data-tip);position:absolute;right:0;top:calc(100%% + 4px);background:#1e293b;color:#e2e8f0;border:1px solid rgba(56,189,248,.3);border-radius:6px;padding:8px 12px;font-size:.72rem;font-weight:400;white-space:normal;min-width:200px;max-width:340px;z-index:9999;box-shadow:0 8px 24px rgba(0,0,0,.5);line-height:1.4;pointer-events:none}
.rtbl td{padding:6px 10px;border-bottom:1px solid rgba(148,163,184,.08);white-space:nowrap}
.rtbl tr:hover td{background:rgba(56,189,248,.05)}
.rtbl .num{text-align:right;font-family:'Fira Mono','Cascadia Code',monospace}
.rtbl .good{color:var(--success)}.rtbl .mid{color:var(--warn)}.rtbl .bad{color:var(--danger)}
#volMap{width:100%%;height:500px;background:#1e293b;border-radius:var(--r)}
.tour-leg-item{display:flex;align-items:center;gap:6px;padding:2px 4px;border-radius:4px;cursor:pointer;font-size:.72rem;color:var(--dim);transition:background .15s}
.tour-leg-item:hover{background:rgba(56,189,248,.1);color:var(--text)}
.tour-leg-item .tl-dot{width:10px;height:3px;border-radius:2px;flex-shrink:0}
.tour-list-wrap{max-height:200px;overflow-y:auto;margin-top:4px}
</style>
</head>
<body>

<div class="hdr">
  <div class="logo">H</div>
  <div><h1>HAGRID Analysis Dashboard</h1><div class="meta">Run: %1$s &nbsp;|&nbsp; %2$s</div></div>
</div>

<div class="kstrip" id="kpiStrip"></div>

<div class="main">
  <!-- LEFT: Filter & Browse Panel -->
  <div class="lp">
    <h3>Display Mode</h3>
    <div class="lp-sec">
      <div class="mode-btns">
        <button class="mode-btn active" data-mode="tours">Tours</button>
        <button class="mode-btn" data-mode="stops">Services</button>
        <button class="mode-btn" data-mode="heat">Heatmap</button>
      </div>
      <div id="heatOpts" style="display:none;padding:4px 0">
        <label><input type="radio" name="heat" value="all" checked> All vehicles</label>
        <label><input type="radio" name="heat" value="delivery"> Delivery only</label>
        <label><input type="radio" name="heat" value="supply"> Supply only</label>
      </div>
      <label style="margin-top:4px"><input type="checkbox" id="cbShowStops" checked> Show stops on tours</label>
    </div>

    <h3>Filters</h3>
    <div class="lp-sec">
      <label style="margin-bottom:4px"><input type="checkbox" id="cbSupply" checked> Include supply vehicles</label>
    </div>

    <h3>Provider</h3>
    <div class="lp-sec">
      <div class="prov-grid" id="provGrid"></div>
    </div>

    <h3>Carrier</h3>
    <div class="lp-sec">
      <select id="carrSel"><option value="">All carriers</option></select>
      <div class="tour-list-wrap" id="tourListWrap" style="display:none"></div>
    </div>

    <h3>Vehicle</h3>
    <div class="lp-sec">
      <select id="vehSel"><option value="">All vehicles (filtered)</option></select>
      <div class="status-bar" id="statusBar">Select a provider or carrier to display tours</div>
    </div>
  </div>

  <!-- CENTER: Map -->
  <div style="position:relative">
    <div id="map"></div>
    <div class="map-legend" id="legend"></div>
  </div>

  <!-- RIGHT: Charts Sidebar -->
  <div class="sb">
    <h3>Fleet Composition</h3>
    <div class="cc"><canvas id="donut"></canvas></div>

    <h3>Providers (Parcels)</h3>
    <div class="cc"><canvas id="provDonut"></canvas></div>

    <h3>Hourly Activity</h3>
    <div class="cc"><canvas id="timeline"></canvas></div>

    <h3>Utilisation (by Provider)</h3>
    <div class="cc"><canvas id="utilChart"></canvas></div>

    <h3>Utilisation (by Type)</h3>
    <div class="cc"><canvas id="utilTypeChart"></canvas></div>
  </div>
</div>

<!-- SECTION: Routing Efficiency -->
<div class="stit">VRP Routing Efficiency</div>
<div style="padding:6px 22px 12px;overflow-x:auto">
<div class="cc" style="padding:0;overflow:hidden">
<table class="rtbl" id="routEffTable">
<thead><tr><th title="Logistics service provider / CEP carrier">Provider</th><th class="num" title="Total number of delivery tours (one per vehicle)">Tours</th><th class="num" title="Average number of delivery stops per tour">Avg Stops</th><th class="num" title="Average tour distance in km (depot \u2192 deliveries \u2192 depot)">Avg km</th><th class="num" title="Average tour duration in hours (departure to return)">Avg Dur (h)</th><th class="num" title="Delivery stops per hour \u2014 route density efficiency">Stops/h</th><th class="num" title="Delivery stops per km driven \u2014 spatial density">Stops/km</th><th class="num" title="Parcels delivered per km driven">Parcels/km</th><th class="num" title="Parcels delivered per hour (incl. service time)">Parcels/h</th><th class="num" title="Kilometres driven per parcel delivered (inverse of Parcels/km). Lower = better">km/Parcel</th><th class="num" title="Average driving speed = Tour km / Travel time (excl. service time at stops)">Speed (km/h)</th><th class="num" title="Percentage of tour time spent driving (vs. standing/servicing)">Travel %%</th><th class="num" title="Percentage of tour time spent at delivery stops (loading/unloading)">Service %%</th><th class="num" title="Stem distance: %% of total km from depot to first delivery stop (dead mileage)">Stem %%</th><th class="num" title="Average vehicle utilisation = parcels / vehicle capacity">Utilisation %%</th><th class="num" title="Average cost per tour = (distance cost + time cost + overtime) / tours + vehicle fixedCostsPerDay">\u20AC/Tour</th><th class="num" title="Average cost per parcel delivered = total provider cost / parcels delivered">\u20AC/Parcel</th></tr></thead>
<tbody id="routEffBody"></tbody>
</table>
</div>
</div>
<div class="cstrip">
  <div class="cc"><h4>Stops per Hour by Provider</h4><canvas id="stopsPerHourChart" style="max-height:260px"></canvas></div>
  <div class="cc"><h4>Parcels per km by Provider</h4><canvas id="parcelsPerKmChart" style="max-height:260px"></canvas></div>
  <div class="cc"><h4>Time Split: Travel vs Service (%%) by Provider</h4><canvas id="timeSplitChart" style="max-height:260px"></canvas></div>
  <div class="cc"><h4>Avg Tour Distance &amp; Stops by Provider</h4><canvas id="tourProfileChart" style="max-height:260px"></canvas></div>
</div>

<!-- Vehicle Type Analytics -->
<div class="stit">Vehicle Type Analytics</div>
<div class="cstrip">
  <div class="cc" style="position:relative"><h4 style="display:flex;align-items:center;gap:8px"><span style="font-size:1.2em">\ud83d\ude9a</span> Total Distance by Vehicle Type</h4><canvas id="vtKmPolar" style="max-height:320px"></canvas></div>
  <div class="cc" style="position:relative"><h4 style="display:flex;align-items:center;gap:8px"><span style="font-size:1.2em">\u2b50</span> Vehicle Type Performance Radar</h4><canvas id="vtRadar" style="max-height:320px"></canvas></div>
  <div class="cc" style="position:relative"><h4 style="display:flex;align-items:center;gap:8px"><span style="font-size:1.2em">\ud83d\udce6</span> Avg Load Factor by Vehicle Type</h4><canvas id="vtLoadGauge" style="max-height:320px"></canvas></div>
  <div class="cc" style="position:relative"><h4 style="display:flex;align-items:center;gap:8px"><span style="font-size:1.2em">\ud83d\udcca</span> Avg km &amp; Stops per Tour by Type</h4><canvas id="vtKmStops" style="max-height:320px"></canvas></div>
</div>

<!-- SECTION: Summary Table -->
<div class="stit">Provider Summary</div>
<div style="padding:6px 22px 12px;overflow-x:auto">
<div class="cc" style="padding:0;overflow:hidden">
<table class="rtbl" id="summaryTable">
<thead><tr><th style="width:20px"></th><th title="Logistics service provider (CEP carrier group)">Provider</th><th class="num" title="Number of delivery carriers belonging to this provider">Carriers</th><th class="num" title="Total delivery vehicles (tours) operated by this provider">Vehicles</th><th class="num" title="Total parcel demand assigned to this provider (from carrier XML numberOfParcels)">Parcels</th><th class="num" title="Parcels where the recipient was not available (failed delivery attempt)">Missed</th><th class="num" title="Delivery Rate = (Parcels \u2212 Missed) / Parcels \u00D7 100. Share of parcels successfully delivered.">Delivery Rate %%</th><th class="num" title="Total distance driven by all delivery vehicles of this provider (km)">Dist (km)</th><th class="num" title="Total tour duration of all vehicles (depot departure to depot return, hours). Includes driving + service time at stops.">Tour (h)</th><th class="num" title="Pure driving time of all vehicles (hours). Excludes service time at delivery stops.">Drive (h)</th><th class="num" title="Total cost = distance cost + time cost + overtime penalty + vehicle fixed costs (from vehicle type fixedCostsPerDay)">Cost (\u20AC)</th><th class="num" title="Average vehicle utilisation = parcels / vehicle capacity, averaged across all tours">Utilisation %%</th><th class="num" title="Average parcels delivered per vehicle = Parcels / Vehicles">Parcels/Veh</th><th class="num" title="Average km driven per vehicle = Dist / Vehicles">km/Veh</th><th class="num" title="Cost efficiency = Total Cost / Parcels">\u20AC/Parcel</th></tr></thead>
<tbody id="summaryBody"></tbody>
</table>
</div>
</div>

<!-- SECTION: Tour Structure -->
<div class="stit">Tour Structure</div>
<div class="cstrip">
  <div class="cc"><h4>Tour Distance Distribution (delivery)</h4><canvas id="distHist" style="max-height:250px"></canvas></div>
  <div class="cc"><h4>Tour Duration Distribution (delivery)</h4><canvas id="durHist" style="max-height:250px"></canvas></div>
</div>

<!-- SECTION: Timing -->
<div class="stit">Departure &amp; Service Timing</div>
<div class="cstrip">
  <div class="cc"><h4>Departures &amp; Service Starts per Hour</h4><canvas id="depChart" style="max-height:250px"></canvas></div>
  <div class="cc"><h4>Vehicles &amp; Parcels by Provider</h4><canvas id="distProvChart" style="max-height:250px"></canvas></div>
</div>

<!-- SECTION: Costs -->
<div class="stit">Cost Analysis</div>
<div class="cstrip">
  <div class="cc"><h4>Total Cost by Provider</h4><canvas id="costBar" style="max-height:260px"></canvas></div>
  <div class="cc"><h4>Cost Components by Provider</h4><canvas id="costStack" style="max-height:260px"></canvas></div>
</div>

<!-- SECTION: Scoring -->
<div class="stit">Scoring Analysis</div>
<div style="padding:0 22px 10px"><div class="cc"><h4>Scoring Convergence (Carrier Scores)</h4><canvas id="scoreConvergence" style="max-height:300px"></canvas></div></div>
<div class="cstrip">
  <div class="cc"><h4>Final Iteration Score Distribution</h4><canvas id="scoreDistChart" style="max-height:260px"></canvas></div>
  <div class="cc"><h4>Scoring Breakdown by Provider</h4><canvas id="scoringStack" style="max-height:260px"></canvas></div>
  <div class="cc"><h4>Scoring Component Weights (relative share)</h4><canvas id="scoringWeights" style="max-height:260px"></canvas></div>
</div>

<!-- SECTION: Carrier Scoring Explorer -->
<div class="stit">Carrier Scoring Explorer</div>
<div style="padding:0 22px 12px">
<div class="cc" style="padding:16px 20px">
  <div style="display:flex;gap:12px;align-items:center;flex-wrap:wrap;margin-bottom:12px">
    <span style="font-size:.82rem;font-weight:700;color:var(--accent);text-transform:uppercase;letter-spacing:.5px">Filter</span>
    <select id="cseProvFilter" style="background:var(--card);color:var(--text);border:1px solid rgba(148,163,184,.18);border-radius:6px;padding:4px 10px;font-size:.78rem">
      <option value="">All Providers</option></select>
    <input id="cseSearch" type="text" placeholder="Search carrier ID or PLZ\u2026" style="background:var(--card);color:var(--text);border:1px solid rgba(148,163,184,.18);border-radius:6px;padding:4px 10px;font-size:.78rem;min-width:180px">
    <select id="cseSortBy" style="background:var(--card);color:var(--text);border:1px solid rgba(148,163,184,.18);border-radius:6px;padding:4px 10px;font-size:.78rem">
      <option value="costTW-desc">TW Penalty \u2193</option><option value="costTotal-desc">Total Cost \u2193</option>
      <option value="costTotal-asc">Total Cost \u2191</option><option value="parcels-desc">Parcels \u2193</option>
      <option value="missed-desc">Missed \u2193</option><option value="id-asc">Carrier ID A\u2192Z</option></select>
    <span id="cseCount" style="font-size:.72rem;color:var(--dim);margin-left:auto"></span>
  </div>
  <div id="cseTableWrap" style="max-height:620px;overflow-y:auto;border:1px solid rgba(148,163,184,.08);border-radius:8px">
    <table id="cseTable" style="width:100%%;border-collapse:collapse;font-size:.76rem">
      <thead style="position:sticky;top:0;z-index:2;background:rgba(15,23,42,.96)">
        <tr style="border-bottom:2px solid rgba(148,163,184,.15)">
          <th style="padding:8px 6px;text-align:left;white-space:nowrap"></th>
          <th style="padding:8px 6px;text-align:left">Carrier</th>
          <th style="padding:8px 6px;text-align:left">Provider</th>
          <th style="padding:8px 6px;text-align:left">PLZ</th>
          <th style="padding:8px 6px;text-align:right">Svc</th>
          <th style="padding:8px 6px;text-align:right">Parcels</th>
          <th style="padding:8px 6px;text-align:right">Missed</th>
          <th style="padding:8px 6px;text-align:right">Tours</th>
          <th style="padding:8px 6px;text-align:right">km</th>
          <th style="padding:8px 6px;text-align:right">Dist\u20ac</th>
          <th style="padding:8px 6px;text-align:right">Time\u20ac</th>
          <th style="padding:8px 6px;text-align:right">Fix\u20ac</th>
          <th style="padding:8px 6px;text-align:right">Act\u20ac</th>
          <th style="padding:8px 6px;text-align:right">OT\u20ac</th>
          <th style="padding:8px 6px;text-align:right;color:#ec4899;font-weight:700">TW\u20ac</th>
          <th style="padding:8px 6px;text-align:right;font-weight:700">Total\u20ac</th>
          <th style="padding:8px 6px;text-align:right">Delivery Rate</th>
        </tr>
      </thead>
      <tbody id="cseTbody"></tbody>
    </table>
  </div>
</div>
</div>

<!-- Provider Chart Filter -->
<div id="chartProvFilter" style="position:sticky;top:0;z-index:900;margin:0 22px 8px;padding:8px 16px;background:rgba(15,23,42,.92);backdrop-filter:blur(12px);border:1px solid rgba(148,163,184,.12);border-radius:10px;display:flex;align-items:center;gap:10px;flex-wrap:wrap">
  <span style="font-size:.75rem;font-weight:700;color:var(--accent);letter-spacing:.5px;text-transform:uppercase;white-space:nowrap">Chart Provider Filter</span>
  <label style="font-size:.72rem;color:var(--text);cursor:pointer;display:flex;align-items:center;gap:4px"><input type="checkbox" id="chartProvAll" checked style="accent-color:var(--accent)"> <b>All</b></label>
  <span id="chartProvCbs" style="display:flex;gap:8px;flex-wrap:wrap"></span>
</div>
<div style="padding:0 22px 10px"><div class="cc"><h4>Hourly Service Activity by Provider</h4><canvas id="hourlySvcChart" style="max-height:320px"></canvas></div></div>
<div style="padding:0 22px 10px"><div class="cc"><h4>Active Vehicles Over Time by Provider</h4><canvas id="activeVehChart" style="max-height:320px"></canvas></div></div>
<div style="padding:0 22px 10px"><div class="cc"><h4>Depot Departures &amp; Arrivals by Provider</h4><canvas id="depotTripsChart" style="max-height:640px"></canvas></div></div>

<!-- SECTION: Operational Overview -->
<div class="stit">Operational Overview</div>
<div class="cstrip">
  <div class="cc"><h4>Utilisation vs Tour Distance</h4><canvas id="scatterUtilDist" style="max-height:280px"></canvas></div>
  <div class="cc"><h4>Parcels Delivered vs Tour Duration</h4><canvas id="scatterParcelsDur" style="max-height:280px"></canvas></div>
  <div class="cc"><h4>Drop Density (Stops/km) by Provider</h4><canvas id="dropDensityChart" style="max-height:260px"></canvas></div>
  <div class="cc"><h4>Cost per Parcel by Provider</h4><canvas id="costPerParcelChart" style="max-height:260px"></canvas></div>
</div>

<!-- SECTION: Link Traffic Volume -->
<div class="stit">Link Traffic Volume</div>
<div style="padding:6px 22px 12px;">
  <div class="cc" style="position:relative;padding:0;overflow:hidden">
    <div style="padding:10px 14px;display:flex;gap:14px;align-items:center;flex-wrap:wrap;background:rgba(30,41,59,.65);border-bottom:1px solid rgba(148,163,184,.1)">
      <span style="font-size:.72rem;font-weight:700;color:var(--accent);text-transform:uppercase;letter-spacing:1px">Mode:</span>
      <label style="font-size:.76rem;color:var(--dim);cursor:pointer"><input type="radio" name="volMode" value="all" checked style="accent-color:var(--accent)"> All</label>
      <label style="font-size:.76rem;color:var(--dim);cursor:pointer"><input type="radio" name="volMode" value="delivery" style="accent-color:var(--accent)"> Delivery</label>
      <label style="font-size:.76rem;color:var(--dim);cursor:pointer"><input type="radio" name="volMode" value="supply" style="accent-color:var(--accent)"> Supply</label>
      <span style="width:1px;height:20px;background:rgba(148,163,184,.2)"></span>
      <span style="font-size:.72rem;font-weight:700;color:var(--accent);text-transform:uppercase;letter-spacing:1px">Time:</span>
      <label style="font-size:.76rem;color:var(--dim);cursor:pointer"><input type="radio" name="volTime" value="day" checked style="accent-color:var(--accent)"> Full Day</label>
      <label style="font-size:.76rem;color:var(--dim);cursor:pointer"><input type="radio" name="volTime" value="hour" style="accent-color:var(--accent)"> Hourly</label>
      <div id="sliderWrap" style="display:none;flex:1;min-width:200px;max-width:500px">
        <div style="display:flex;align-items:center;gap:8px">
          <input type="range" id="hourSlider" min="0" max="23" value="8" style="flex:1;accent-color:var(--accent);cursor:pointer">
          <span id="hourLabel" style="font-size:.85rem;font-weight:700;color:var(--accent);min-width:60px;text-align:center">08:00</span>
        </div>
        <div style="display:flex;justify-content:space-between;padding:0 2px;margin-top:-2px">
          <span style="font-size:.55rem;color:var(--dim)">00:00</span>
          <span style="font-size:.55rem;color:var(--dim)">06:00</span>
          <span style="font-size:.55rem;color:var(--dim)">12:00</span>
          <span style="font-size:.55rem;color:var(--dim)">18:00</span>
          <span style="font-size:.55rem;color:var(--dim)">23:00</span>
        </div>
      </div>
    </div>
    <!-- Graphic controls row -->
    <div style="padding:6px 14px 8px;display:flex;gap:18px;align-items:center;flex-wrap:wrap;background:rgba(30,41,59,.45);border-bottom:1px solid rgba(148,163,184,.06)">
      <span style="font-size:.68rem;font-weight:700;color:var(--dim);text-transform:uppercase;letter-spacing:1px">Rendering:</span>
      <div style="display:flex;align-items:center;gap:5px">
        <span style="font-size:.68rem;color:var(--dim)">Width</span>
        <input type="range" id="volWidth" min="1" max="14" value="7" step="0.5" style="width:80px;accent-color:var(--accent);cursor:pointer">
        <span id="volWidthVal" style="font-size:.7rem;font-weight:600;color:var(--accent);min-width:22px">7</span>
      </div>
      <div style="display:flex;align-items:center;gap:5px">
        <span style="font-size:.68rem;color:var(--dim)">Opacity</span>
        <input type="range" id="volOpacity" min="10" max="100" value="75" style="width:80px;accent-color:var(--accent);cursor:pointer">
        <span id="volOpacityVal" style="font-size:.7rem;font-weight:600;color:var(--accent);min-width:30px">75%%</span>
      </div>
      <div style="display:flex;align-items:center;gap:5px">
        <span style="font-size:.68rem;color:var(--dim)">Scale</span>
        <input type="range" id="volScale" min="20" max="150" value="60" style="width:80px;accent-color:var(--accent);cursor:pointer">
        <span id="volScaleVal" style="font-size:.7rem;font-weight:600;color:var(--accent);min-width:28px">0.6</span>
      </div>
      <div style="display:flex;align-items:center;gap:5px">
        <span style="font-size:.68rem;color:var(--dim)">Network</span>
        <label style="font-size:.7rem;color:var(--dim);cursor:pointer"><input type="checkbox" id="showNet" checked style="accent-color:var(--accent)"> show</label>
        <input type="range" id="netOpacity" min="5" max="60" value="18" style="width:60px;accent-color:#64748b;cursor:pointer">
        <span id="netOpacityVal" style="font-size:.7rem;color:#64748b;min-width:28px">18%%</span>
      </div>
      <span style="width:1px;height:18px;background:rgba(148,163,184,.15)"></span>
      <div style="display:flex;align-items:center;gap:5px">
        <span style="font-size:.68rem;color:var(--dim)">Colors</span>
        <select id="volColorMap" style="font-size:.7rem;background:rgba(30,41,59,.8);color:var(--accent);border:1px solid rgba(148,163,184,.15);border-radius:6px;padding:2px 6px;cursor:pointer">
          <option value="thermal" selected>Thermal</option>
          <option value="viridis">Viridis</option>
          <option value="plasma">Plasma</option>
          <option value="inferno">Inferno</option>
          <option value="cool">Cool</option>
          <option value="electric">Electric</option>
        </select>
        <div id="cmapPreview" style="width:80px;height:12px;border-radius:4px;border:1px solid rgba(148,163,184,.1)"></div>
      </div>
    </div>
    <div id="volMap"></div>
    <div id="volStats" style="padding:8px 14px;background:rgba(30,41,59,.65);border-top:1px solid rgba(148,163,184,.1);font-size:.72rem;color:var(--dim);display:flex;gap:20px;flex-wrap:wrap"></div>
    <div id="volLegend" style="padding:4px 14px 8px;background:rgba(30,41,59,.45);display:flex;align-items:center;gap:10px;font-size:.68rem;color:var(--dim)"></div>
  </div>
</div>
<div class="cstrip">
  <div class="cc"><h4>Hourly Link Utilisation (Active Links per Hour)</h4><canvas id="hourlyLinksChart" style="max-height:260px"></canvas></div>
  <div class="cc"><h4>Hourly Volume Distribution (Vehicles on Network)</h4><canvas id="hourlyVolChart" style="max-height:260px"></canvas></div>
</div>

<!-- Link Utilisation Histogram -->
<div style="padding:6px 22px 12px">
  <div class="cc" style="position:relative;padding:0;overflow:hidden">
    <div style="padding:10px 14px;display:flex;gap:14px;align-items:center;flex-wrap:wrap;background:rgba(30,41,59,.65);border-bottom:1px solid rgba(148,163,184,.1)">
      <span style="font-size:.72rem;font-weight:700;color:var(--accent);text-transform:uppercase;letter-spacing:1px">Link Traffic Load</span>
      <span style="width:1px;height:20px;background:rgba(148,163,184,.2)"></span>
      <span style="font-size:.72rem;font-weight:700;color:var(--accent);text-transform:uppercase;letter-spacing:1px">Time:</span>
      <label style="font-size:.76rem;color:var(--dim);cursor:pointer"><input type="radio" name="utilTime" value="day" checked style="accent-color:var(--accent)"> Full Day</label>
      <label style="font-size:.76rem;color:var(--dim);cursor:pointer"><input type="radio" name="utilTime" value="hour" style="accent-color:var(--accent)"> Hourly</label>
      <div id="utilSliderWrap" style="display:none;flex:1;min-width:200px;max-width:500px">
        <div style="display:flex;align-items:center;gap:8px">
          <input type="range" id="utilHourSlider" min="0" max="23" value="8" style="flex:1;accent-color:var(--accent);cursor:pointer">
          <span id="utilHourLabel" style="font-size:.85rem;font-weight:700;color:var(--accent);min-width:60px;text-align:center">08:00</span>
        </div>
        <div style="display:flex;justify-content:space-between;padding:0 2px;margin-top:-2px">
          <span style="font-size:.55rem;color:var(--dim)">00:00</span>
          <span style="font-size:.55rem;color:var(--dim)">06:00</span>
          <span style="font-size:.55rem;color:var(--dim)">12:00</span>
          <span style="font-size:.55rem;color:var(--dim)">18:00</span>
          <span style="font-size:.55rem;color:var(--dim)">23:00</span>
        </div>
      </div>
    </div>
    <div style="padding:14px">
      <canvas id="utilHistChart" style="max-height:300px"></canvas>
    </div>
    <div id="utilHistStats" style="padding:8px 14px;background:rgba(30,41,59,.65);border-top:1px solid rgba(148,163,184,.1);font-size:.72rem;color:var(--dim);display:flex;gap:20px;flex-wrap:wrap"></div>
  </div>
</div>

""";
    private static final String HTML_PART2 = """
<script>
// === DATA ===
var KPI=  %6$s;
var VEHS= %7$s;
var TOURS=%8$s;
var STOPS=%9$s;
var HEAT_ALL=%10$s;
var HEAT_LMD=%11$s;
var HEAT_SUP=%12$s;
var PROV= %13$s;
var COSTS=%14$s;
var TL=   %15$s;
var VTYPES=%16$s;
var DIST_H=%17$s;
var DUR_H= %18$s;
var UTIL=  %19$s;
var PROV_LIST=%20$s;
var CARR_LIST=%21$s;
var LINK_VOL=%22$s;
var UTIL_BY_TYPE=%23$s;
var SUMMARY=%24$s;
var ROUT_EFF=%25$s;
var CARR_PROV=%26$s;
var NET_BG=%27$s;
var SCORING=%28$s;
var HOURLY_SVC=%29$s;
var ACTIVE_VEH=%30$s;
var DEPOT_TRIPS=%31$s;
var CARRIER_DETAIL=%32$s;

// === CONSTANTS ===
var PROV_COLORS={};
var _PC=['#3b82f6','#ef4444','#10b981','#f59e0b','#8b5cf6','#ec4899','#06b6d4','#84cc16','#fb923c'];
PROV_LIST.forEach(function(p,i){PROV_COLORS[p]=_PC[i%%_PC.length]});
var VTYPE_COLORS={'CEP Van':'#3b82f6','Cargobike':'#10b981','Truck (heavy)':'#ef4444','Truck (light)':'#f59e0b','Supply Van':'#8b5cf6','Unknown':'#6b7280'};

// Distinct colors for individual tours within a carrier
var TOUR_COLORS=['#3b82f6','#ef4444','#10b981','#f59e0b','#8b5cf6','#ec4899','#06b6d4','#84cc16','#fb923c','#14b8a6','#f472b6','#a78bfa','#fbbf24','#22d3ee','#e879f9','#4ade80','#f97316','#2dd4bf','#c084fc','#facc15'];

// === BUILD INDEXES ===
var tourMeta={};
var toursByProv={};
var toursByCarr={};
var tourFeatureIdx={};

TOURS.features.forEach(function(f,i){
  var p=f.properties;
  tourMeta[p.vid]={provider:p.provider,carrier:p.carrier,supply:p.supply,vtype:p.vtype};
  tourFeatureIdx[p.vid]=i;
  if(!toursByProv[p.provider])toursByProv[p.provider]=[];
  toursByProv[p.provider].push(p.vid);
  if(!toursByCarr[p.carrier])toursByCarr[p.carrier]=[];
  toursByCarr[p.carrier].push(p.vid);
});

var stopsByVeh={};
STOPS.features.forEach(function(f){
  var vid=f.properties.vid;
  if(!stopsByVeh[vid])stopsByVeh[vid]=[];
  stopsByVeh[vid].push(f);
});

// === STATE ===
var displayMode='tours';
var provState={};
PROV_LIST.forEach(function(p){provState[p]=true});
var showSupply=true;
var showStopsOnTours=true;

// === KPIs ===
(function(){
  var s=document.getElementById('kpiStrip');
  var fm=function(n){return typeof n==='number'?n.toLocaleString('en-US'):n};
  var items=[
    {v:KPI.totalVehicles,l:'Active Vehicles',t:'Total vehicles with at least one link visit event during simulation'},
    {v:KPI.deliveryCarriers,l:'Delivery Carriers',t:'Number of carriers handling last-mile parcel delivery'},
    {v:KPI.supplyCarriers,l:'Supply Carriers',t:'Number of carriers handling hub-to-hub or long-haul supply'},
    {v:KPI.vanCount,l:'CEP Vans',t:'Vehicles classified as CEP delivery vans (ct_cep_size_m, ct_cep_size_l)'},
    {v:KPI.bikeCount,l:'Cargobikes',t:'Vehicles classified as cargo bikes for last-mile delivery'},
    {v:KPI.truckCount,l:'Trucks/Supply',t:'Heavy trucks, light trucks, and supply vehicles'},
    {v:KPI.totalParcels,l:'Total Parcels',t:'Total parcel demand (sum of capacityDemand from all scheduled services of non-excluded delivery vehicles)'},
    {v:KPI.totalStops,l:'Total Stops',t:'Total service-start events observed in the MATSim event stream'},
    {v:KPI.deliveryVehicleCount,l:'Delivery Vehicles',t:'Vehicles in delivery carriers with at least one parcel assigned'},
    {v:KPI.numVehicleTypes,l:'Vehicle Types',t:'Distinct vehicle type IDs used across all delivery carriers'},
    {v:(KPI.avgLoadFactor*100).toFixed(1)+'%%',l:'Avg Utilisation',cls:KPI.avgLoadFactor>=.8?'ok':KPI.avgLoadFactor>=.5?'':'warn',t:'Mean load factor = parcels / vehicle capacity across all delivery vehicles'},
    {v:KPI.successRate.toFixed(1)+'%%',l:'Delivery Rate',cls:KPI.successRate>=95?'ok':KPI.successRate>=85?'':'warn',t:'Delivery Rate = (Parcels \\u2212 Missed) / Parcels \\u00D7 100. Share of parcels successfully delivered.'},
    {v:KPI.totalUnassignedParcels||0,l:'Unassigned Parcels',cls:(KPI.totalUnassignedParcels||0)>0?'warn':'ok',t:'Parcels at stops jsprit could NOT insert into any tour (7h route-duration cap, vehicle end window, or capacity) \\u2014 these stops are never driven. Distinct from Missed (statistical not-at-home overlay). Stops affected: '+(KPI.totalUnassignedJobs||0)},
    {v:KPI.avgTourLengthKm.toFixed(1)+' km',l:'Avg Tour Length',t:'Total delivery distance / number of delivery vehicles'},
    {v:KPI.avgSpeedKmh.toFixed(1)+' km/h',l:'Avg Speed',t:'Total km / total travel hours (excluding service time at stops)'},
    {v:'\\u20AC '+Math.round(KPI.totalCost).toLocaleString('en-US'),l:'Total Cost',t:'Sum of distance + time + fixed (per vehicle type) + overtime costs across all delivery carriers'},
    {v:Math.round(KPI.totalDistanceKm).toLocaleString('en-US')+' km',l:'Total Distance',t:'Sum of all delivery vehicle travel distances in km'},
    {v:KPI.totalTourDurationH.toFixed(0)+' h',l:'Tour Duration',t:'Sum of all delivery vehicle tour durations (depot departure to depot return, includes driving + service time)'},
    {v:KPI.totalDrivingTimeH.toFixed(0)+' h',l:'Driving Time',t:'Sum of all delivery vehicle pure driving time (excludes service time at delivery stops)'},
    {v:KPI.eventsProcessed.toLocaleString('en-US'),l:'Events Processed',t:'Total MATSim events parsed (link leaves, activity starts/ends)'}
  ];
  items.forEach(function(it){var d=document.createElement('div');d.className='kpi'+(it.cls?' '+it.cls:'');if(it.t)d.title=it.t;d.innerHTML='<div class="v">'+fm(it.v)+'</div><div class="l">'+it.l+'</div>';s.appendChild(d)});
})();

// === PROVIDER GRID ===
(function(){
  var g=document.getElementById('provGrid');
  var allIt=document.createElement('div');allIt.className='prov-it active';
  allIt.innerHTML='<input type="checkbox" id="provAll" checked><span style="font-weight:700;color:var(--text)">All Providers</span>';
  g.appendChild(allIt);
  var allCb=allIt.querySelector('input');
  allCb.onchange=function(){
    var checked=allCb.checked;
    PROV_LIST.forEach(function(p){provState[p]=checked});
    g.querySelectorAll('.prov-cb').forEach(function(cb){cb.checked=checked});
    populateCarrierDropdown();
    rebuildMap();
  };
  PROV_LIST.forEach(function(p){
    var cnt=toursByProv[p]?toursByProv[p].length:0;
    var it=document.createElement('div');it.className='prov-it active';
    it.innerHTML='<div class="dot" style="background:'+PROV_COLORS[p]+'"></div><input type="checkbox" class="prov-cb" checked data-prov="'+p+'"><span>'+p+'</span><span class="veh-count">'+cnt+'</span>';
    g.appendChild(it);
    var cb=it.querySelector('input');
    cb.onchange=function(){
      provState[p]=cb.checked;
      it.className=cb.checked?'prov-it active':'prov-it';
      populateCarrierDropdown();
      rebuildMap();
    };
  });
})();

// === CARRIER DROPDOWN (cascaded by provider) ===
function populateCarrierDropdown(){
  var cSel=document.getElementById('carrSel');
  var prev=cSel.value;
  cSel.innerHTML='<option value="">All carriers</option>';
  CARR_LIST.forEach(function(c){
    var prov=CARR_PROV[c];
    if(prov&&!provState[prov])return;
    var o=document.createElement('option');o.value=c;
    var label=c.length>45?c.substring(0,45)+'...':c;
    o.text=label+' ('+prov+')';
    cSel.appendChild(o);
  });
  if(prev){cSel.value=prev;if(!cSel.value)cSel.value=''}
}
populateCarrierDropdown();

var carrSel=document.getElementById('carrSel');
var vehSel=document.getElementById('vehSel');
var tourListWrap=document.getElementById('tourListWrap');

carrSel.onchange=function(){
  populateVehicleDropdown();
  updateTourList();
  rebuildMap();
};
vehSel.onchange=rebuildMap;

function populateVehicleDropdown(){
  vehSel.innerHTML='<option value="">All vehicles (filtered)</option>';
  var cv=carrSel.value;
  var vids=[];
  if(cv&&toursByCarr[cv]){
    vids=toursByCarr[cv].slice().sort();
  }else{
    PROV_LIST.forEach(function(p){
      if(provState[p]&&toursByProv[p])vids=vids.concat(toursByProv[p]);
    });
    vids.sort();
  }
  vids.forEach(function(v){
    var m=tourMeta[v];
    if(!showSupply&&m&&m.supply)return;
    var o=document.createElement('option');o.value=v;
    var label=v.replace(/^freight_/,'').replace(/_veh_/,' > ');
    o.text=label.length>50?label.substring(0,50)+'...':label;
    vehSel.appendChild(o);
  });
}

function updateTourList(){
  var cv=carrSel.value;
  if(!cv||!toursByCarr[cv]){tourListWrap.style.display='none';return}
  tourListWrap.style.display='block';
  tourListWrap.innerHTML='';
  var vids=toursByCarr[cv].slice().sort();
  vids.forEach(function(v,i){
    if(!showSupply&&tourMeta[v]&&tourMeta[v].supply)return;
    var color=TOUR_COLORS[i%%TOUR_COLORS.length];
    var label=v.replace(/^freight_/,'').replace(/_veh_/,' > ');
    if(label.length>35)label=label.substring(0,35)+'...';
    var el=document.createElement('div');el.className='tour-leg-item';
    el.innerHTML='<span class="tl-dot" style="background:'+color+'"></span><span>'+label+'</span>';
    el.onclick=function(){vehSel.value=v;rebuildMap()};
    tourListWrap.appendChild(el);
  });
}

// === DISPLAY MODE BUTTONS ===
document.querySelectorAll('.mode-btn').forEach(function(btn){
  btn.onclick=function(){
    document.querySelectorAll('.mode-btn').forEach(function(b){b.classList.remove('active')});
    btn.classList.add('active');
    displayMode=btn.dataset.mode;
    document.getElementById('heatOpts').style.display=displayMode==='heat'?'block':'none';
    rebuildMap();
  };
});

// Show stops toggle
document.getElementById('cbShowStops').onchange=function(){
  showStopsOnTours=this.checked;rebuildMap();
};

// === SUPPLY TOGGLE ===
document.getElementById('cbSupply').onchange=function(){
  showSupply=this.checked;
  populateCarrierDropdown();
  populateVehicleDropdown();
  rebuildMap();
};

// === MAP ===
var map=L.map('map',{zoomControl:true,preferCanvas:true}).setView([%3$.6f,%4$.6f],12);
L.tileLayer('https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png',{
  attribution:'OpenStreetMap, CARTO',maxZoom:19,subdomains:'abcd'
}).addTo(map);
map.fitBounds(%5$s);

var hO={radius:14,blur:18,maxZoom:15,gradient:{0.1:'#1e3a5f',0.3:'#38bdf8',0.5:'#fbbf24',0.7:'#f97316',1.0:'#ef4444'}};
var hAll=L.heatLayer(HEAT_ALL,hO);
var hLmd=L.heatLayer(HEAT_LMD,{radius:14,blur:18,maxZoom:15,gradient:{0.1:'#1e3a5f',0.3:'#3b82f6',0.5:'#38bdf8',0.7:'#06b6d4',1.0:'#22d3ee'}});
var hSup=L.heatLayer(HEAT_SUP,{radius:14,blur:18,maxZoom:15,gradient:{0.1:'#2d1b4e',0.3:'#8b5cf6',0.5:'#a78bfa',0.7:'#c084fc',1.0:'#e879f9'}});

var activeLayers=[];
var activeHeat=null;

function clearMap(){
  activeLayers.forEach(function(l){map.removeLayer(l)});
  activeLayers=[];
  if(activeHeat){map.removeLayer(activeHeat);activeHeat=null}
  if(typeof clearGlow==='function')clearGlow();
}

function updateLegend(items){
  var lg=document.getElementById('legend');
  lg.innerHTML='<b style="color:#38bdf8;font-size:.78rem">Legend</b>';
  items.forEach(function(it){
    lg.innerHTML+='<div class="it"><span class="dt" style="background:'+it.color+'"></span>'+it.label+'</div>';
  });
}

// Glow route highlight layer
var glowLayers=[];
var activeStopEl=null;
function clearGlow(){
  glowLayers.forEach(function(l){map.removeLayer(l)});
  glowLayers=[];
  if(activeStopEl){activeStopEl.classList.remove('stop-badge-active');activeStopEl=null}
}
function addGlowSegment(from,to){
  var pts=[[from[1],from[0]],[to[1],to[0]]];
  var outer=L.polyline(pts,{color:'#f59e0b',weight:10,opacity:.35,lineCap:'round',interactive:false});
  var inner=L.polyline(pts,{color:'#fbbf24',weight:4,opacity:.9,lineCap:'round',dashArray:'8,6',interactive:false});
  outer.addTo(map);inner.addTo(map);
  glowLayers.push(outer,inner);
  setTimeout(function(){
    [outer,inner].forEach(function(l){
      if(l._path){l._path.style.animation='glowPulse 1.5s ease-in-out infinite'}
    });
  },50);
}
function addRouteGlow(latlngs){
  if(latlngs.length<2)return;
  var outer=L.polyline(latlngs,{color:'#ec4899',weight:12,opacity:.3,lineCap:'round',lineJoin:'round',interactive:false});
  var inner=L.polyline(latlngs,{color:'#f472b6',weight:4,opacity:.9,lineCap:'round',lineJoin:'round',dashArray:'10,6',interactive:false});
  outer.addTo(map);inner.addTo(map);
  glowLayers.push(outer,inner);
  setTimeout(function(){
    [outer,inner].forEach(function(l){
      if(l._path){l._path.style.animation='pinkGlow 1.2s ease-in-out infinite'}
    });
  },50);
}
function findRouteSegment(vid,fromCoord,toCoord){
  var feat=null;
  TOURS.features.forEach(function(f){if(f.properties.vid===vid)feat=f});
  if(!feat)return null;
  var coords=feat.geometry.coordinates;
  function closestIdx(target){
    var minD=Infinity,idx=0;
    for(var i=0;i<coords.length;i++){var dx=coords[i][0]-target[0],dy=coords[i][1]-target[1];var d=dx*dx+dy*dy;if(d<minD){minD=d;idx=i}}
    return idx;
  }
  var si=closestIdx(fromCoord),ei=closestIdx(toCoord);
  if(si>ei){var tmp=si;si=ei;ei=tmp}
  return coords.slice(si,ei+1).map(function(c){return[c[1],c[0]]});
}
map.on('click',function(e){if(!e.originalEvent._stopGlow)clearGlow()});

// Build marker cluster for stops (noCluster=true skips clustering for single-vehicle view)
function buildStopLayer(features,noCluster){
  var container;
  if(noCluster){
    container=L.featureGroup();
  }else{
    container=L.markerClusterGroup({
      spiderfyOnMaxZoom:true,disableClusteringAtZoom:18,showCoverageOnHover:false,maxClusterRadius:40,
      iconCreateFunction:function(cl){return L.divIcon({html:'<div style="background:rgba(56,189,248,.9);color:#0f172a;border-radius:50%%;width:28px;height:28px;line-height:28px;text-align:center;font-weight:700;font-size:11px;box-shadow:0 2px 8px rgba(0,0,0,.4)">'+cl.getChildCount()+'</div>',className:'',iconSize:[28,28]})}
    });
  }
  var byVid={};
  features.forEach(function(f){
    var v=f.properties.vid;
    if(!byVid[v])byVid[v]=[];
    byVid[v].push(f);
  });
  Object.keys(byVid).forEach(function(v){byVid[v].sort(function(a,b){return a.properties.idx-b.properties.idx})});

  features.forEach(function(f){
    var co=f.geometry.coordinates;
    var p=f.properties;
    var pop='<b>'+p.vid.replace('freight_','')+'</b><br>Stop #'+p.idx
      +'<br>Demand: <b>'+p.demand+(p.demand===1?' parcel':' parcels')+'</b>'
      +'<br>Link: '+p.link
      +'<br>'+p.startHMS+' \\u2013 '+p.endHMS
      +'<br>Duration: '+p.durHMS;
    var ic=L.divIcon({html:'<div class="stop-badge">'+p.idx+'</div>',iconSize:[22,22],iconAnchor:[11,11],className:''});
    var mk=L.marker([co[1],co[0]],{icon:ic}).bindPopup(pop);
    mk.on('click',function(e){
      e.originalEvent._stopGlow=true;
      clearGlow();
      var el=mk.getElement();
      if(el){var badge=el.querySelector('.stop-badge');if(badge){badge.classList.add('stop-badge-active');activeStopEl=badge}}
      var vStops=byVid[p.vid]||[];
      var myI=-1;
      for(var i=0;i<vStops.length;i++){if(vStops[i].properties.idx===p.idx){myI=i;break}}
      if(myI<0)return;
      // Gold straight-line connectors
      if(myI>0){addGlowSegment(vStops[myI-1].geometry.coordinates,co)}
      if(myI<vStops.length-1){addGlowSegment(co,vStops[myI+1].geometry.coordinates)}
      // Pink route path from tour geometry
      if(myI>0){var seg=findRouteSegment(p.vid,vStops[myI-1].geometry.coordinates,co);if(seg)addRouteGlow(seg)}
      if(myI<vStops.length-1){var seg2=findRouteSegment(p.vid,co,vStops[myI+1].geometry.coordinates);if(seg2)addRouteGlow(seg2)}
    });
    container.addLayer(mk);
  });
  return container;
}

// === CORE: REBUILD MAP ===
function rebuildMap(){
  clearMap();
  var vid=vehSel.value;
  var carr=carrSel.value;
  var bar=document.getElementById('statusBar');

  function featureFilter(f){
    var p=f.properties;
    if(!showSupply&&p.supply)return false;
    if(vid)return p.vid===vid;
    if(carr)return p.carrier===carr;
    if(!provState[p.provider])return false;
    return true;
  }

  if(displayMode==='tours'){
    // Count matching
    var matchVids=[];
    TOURS.features.forEach(function(f){if(featureFilter(f))matchVids.push(f.properties.vid)});
    var matchCount=matchVids.length;

    if(matchCount===0){
      bar.textContent='No matching tours';bar.style.color='var(--warn)';
      updateLegend([]);return;
    }

    // Determine coloring: if carrier is selected, each tour gets unique color
    var usePerTourColor=!!carr;
    var carrTourIdx={};
    if(usePerTourColor&&toursByCarr[carr]){
      toursByCarr[carr].forEach(function(v,i){carrTourIdx[v]=i});
    }

    function tourColor(f){
      if(usePerTourColor){return TOUR_COLORS[carrTourIdx[f.properties.vid]%%TOUR_COLORS.length]||'#6b7280'}
      return PROV_COLORS[f.properties.provider]||'#6b7280';
    }

    var weight=matchCount<=5?4:matchCount<=20?3:matchCount<=80?2:1.5;
    var opacity=matchCount<=5?0.9:matchCount<=20?0.7:matchCount<=80?0.5:0.35;

    var tourLayer=L.geoJSON(TOURS,{
      filter:featureFilter,
      style:function(f){return{color:tourColor(f),weight:weight,opacity:opacity}},
      onEachFeature:function(f,layer){
        var p=f.properties;
        layer.bindPopup('<b>'+p.vid.replace('freight_','')+'</b><br>Provider: '+p.provider+'<br>Carrier: '+p.carrier.replace('carrier_','')+'<br>Type: '+p.vtype+'<br>Stops: '+p.stops);
        layer.on('mouseover',function(){this.setStyle({weight:Math.max(weight,4),opacity:1})});
        layer.on('mouseout',function(){this.setStyle({weight:weight,opacity:opacity})});
      }
    }).addTo(map);
    activeLayers.push(tourLayer);

    // Show stops on tours
    if(showStopsOnTours){
      var stopFeats=[];
      matchVids.forEach(function(v){
        if(stopsByVeh[v])stopFeats=stopFeats.concat(stopsByVeh[v]);
      });
      if(stopFeats.length>0&&stopFeats.length<=2000){
        var sl=buildStopLayer(stopFeats,!!vid);
        sl.addTo(map);activeLayers.push(sl);
      }
    }

    // Legend
    if(usePerTourColor){
      var legItems=[];
      matchVids.sort().forEach(function(v,i){
        legItems.push({color:TOUR_COLORS[carrTourIdx[v]%%TOUR_COLORS.length]||'#6b7280',
          label:v.replace('freight_','').replace(/_veh_/,' > ')});
      });
      updateLegend(legItems);
    }else{
      var seenProv={};var legItems=[];
      matchVids.forEach(function(v){var p=tourMeta[v].provider;
        if(!seenProv[p]){seenProv[p]=true;legItems.push({color:PROV_COLORS[p]||'#6b7280',label:p})}});
      updateLegend(legItems);
    }

    bar.textContent=matchCount+(matchCount===1?' tour':' tours')+' displayed'+(showStopsOnTours?' (with stops)':'');
    bar.style.color='var(--success)';

    if(matchCount>150&&!vid&&!carr){
      bar.textContent+=' \\u2013 many tours, select carrier for detail';
      bar.style.color='var(--warn)';
    }

  }else if(displayMode==='stops'){
    var stopFeats=[];
    STOPS.features.forEach(function(f){
      var m=tourMeta[f.properties.vid];
      if(!m)return;
      if(!showSupply&&m.supply)return;
      if(vid&&f.properties.vid!==vid)return;
      if(carr&&m.carrier!==carr)return;
      if(!vid&&!carr&&!provState[m.provider])return;
      stopFeats.push(f);
    });
    bar.textContent=stopFeats.length+' service stops displayed';
    bar.style.color='var(--success)';
    if(stopFeats.length>0){
      var sl=buildStopLayer(stopFeats);
      sl.addTo(map);activeLayers.push(sl);
    }
    updateLegend([{color:'#38bdf8',label:'Service stops'}]);

  }else if(displayMode==='heat'){
    var hm=document.querySelector('input[name="heat"]:checked');
    var val=hm?hm.value:'all';
    if(val==='all'){hAll.addTo(map);activeHeat=hAll}
    if(val==='delivery'){hLmd.addTo(map);activeHeat=hLmd}
    if(val==='supply'){hSup.addTo(map);activeHeat=hSup}
    bar.textContent='Heatmap: '+val;bar.style.color='var(--accent)';
    updateLegend([{color:'#ef4444',label:'High density'},{color:'#fbbf24',label:'Medium'},{color:'#38bdf8',label:'Low'}]);
  }
}

document.querySelectorAll('input[name="heat"]').forEach(function(r){r.onchange=function(){if(displayMode==='heat')rebuildMap()}});

// === INITIAL RENDER ===
rebuildMap();

// === ROUTING EFFICIENCY TABLE ===
(function(){
  var tb=document.getElementById('routEffBody');
  var fm=function(n){return typeof n==='number'?n.toLocaleString('en-US'):n};
  ROUT_EFF.forEach(function(r){
    var spCls=r.stopsPerHour>=15?'good':r.stopsPerHour>=8?'mid':'bad';
    var stopsKm=r.avgKm>0?r.avgStops/r.avgKm:0;
    var skmCls=stopsKm>=0.8?'good':stopsKm>=0.3?'mid':'bad';
    var ppkCls=r.parcelsPerKm>=1.5?'good':r.parcelsPerKm>=0.5?'mid':'bad';
    var pph=r.avgDurH>0?r.avgParcels/r.avgDurH:0;
    var pphCls=pph>=20?'good':pph>=10?'mid':'bad';
    var spd=r.avgTravelH>0?r.avgKm/r.avgTravelH:0;
    var lfCls=r.avgLoadPct>=70?'good':r.avgLoadPct>=40?'mid':'bad';
    tb.innerHTML+='<tr><td style="font-weight:700;color:'+(PROV_COLORS[r.provider]||'var(--text)')+'">'+r.provider+
      '</td><td class="num">'+fm(r.tours)+'</td><td class="num">'+r.avgStops.toFixed(1)+
      '</td><td class="num">'+r.avgKm.toFixed(1)+'</td><td class="num">'+r.avgDurH.toFixed(2)+
      '</td><td class="num '+spCls+'">'+r.stopsPerHour.toFixed(1)+
      '</td><td class="num '+skmCls+'">'+stopsKm.toFixed(2)+
      '</td><td class="num '+ppkCls+'">'+r.parcelsPerKm.toFixed(2)+
      '</td><td class="num '+pphCls+'">'+pph.toFixed(1)+
      '</td><td class="num">'+r.kmPerParcel.toFixed(2)+
      '</td><td class="num">'+spd.toFixed(1)+
      '</td><td class="num">'+r.travelPct.toFixed(1)+
      '</td><td class="num">'+r.svcPct.toFixed(1)+
      '</td><td class="num">'+r.stemPct.toFixed(1)+
      '</td><td class="num '+lfCls+'">'+r.avgLoadPct.toFixed(1)+
      '</td><td class="num">'+r.avgCostPerTour.toFixed(1)+
      '</td><td class="num">'+r.costPerParcel.toFixed(2)+'</td></tr>';
  });
  // --- TOTAL row ---
  var tTours=0,tStops=0,tKm=0,tDurH=0,tTravelH=0,tSvcH=0,tParcels=0,tStemKm=0,tCost=0,tCap=0;
  ROUT_EFF.forEach(function(r){
    tTours+=r.tours;
    tStops+=r.avgStops*r.tours;
    tKm+=r.avgKm*r.tours;
    tDurH+=r.avgDurH*r.tours;
    tTravelH+=r.avgTravelH*r.tours;
    tSvcH+=r.avgSvcH*r.tours;
    tParcels+=r.avgParcels*r.tours;
    tStemKm+=(r.stemPct/100)*r.avgKm*r.tours;
    tCost+=r.avgCostPerTour*r.tours;
    tCap+=r.avgCap*r.tours;
  });
  var tAvgStops=tTours>0?tStops/tTours:0;
  var tAvgKm=tTours>0?tKm/tTours:0;
  var tAvgDurH=tTours>0?tDurH/tTours:0;
  var tStopsPerHour=tDurH>0?tStops/tDurH:0;
  var tStopsKm=tKm>0?tStops/tKm:0;
  var tParcelsPerKm=tKm>0?tParcels/tKm:0;
  var tPph=tDurH>0?tParcels/tDurH:0;
  var tKmPerParcel=tParcels>0?tKm/tParcels:0;
  var tSpd=tTravelH>0?tKm/tTravelH:0;
  var tTravelPct=tDurH>0?tTravelH/tDurH*100:0;
  var tSvcPct=tDurH>0?tSvcH/tDurH*100:0;
  var tStemPct=tKm>0?tStemKm/tKm*100:0;
  var tAvgLoadPct=tCap>0?tParcels/tCap*100:0;
  var tAvgCostPerTour=tTours>0?tCost/tTours:0;
  var tCostPerParcel=tParcels>0?tCost/tParcels:0;
  var tSpCls=tStopsPerHour>=15?'good':tStopsPerHour>=8?'mid':'bad';
  var tSkmCls=tStopsKm>=0.8?'good':tStopsKm>=0.3?'mid':'bad';
  var tPpkCls=tParcelsPerKm>=1.5?'good':tParcelsPerKm>=0.5?'mid':'bad';
  var tPphCls=tPph>=20?'good':tPph>=10?'mid':'bad';
  var tLfCls=tAvgLoadPct>=70?'good':tAvgLoadPct>=40?'mid':'bad';
  tb.innerHTML+='<tr style="font-weight:700;border-top:2px solid var(--accent)"><td>TOTAL</td>'+
    '<td class="num" title="Sum of all provider tours">'+fm(tTours)+'</td>'+
    '<td class="num" title="Weighted avg: \u03A3(avgStops \u00D7 tours) / \u03A3(tours) = '+tStops.toFixed(0)+' / '+tTours+' = '+tAvgStops.toFixed(1)+'">'+tAvgStops.toFixed(1)+'</td>'+
    '<td class="num" title="Weighted avg: \u03A3(avgKm \u00D7 tours) / \u03A3(tours) = '+tKm.toFixed(0)+' km / '+tTours+' = '+tAvgKm.toFixed(1)+' km">'+tAvgKm.toFixed(1)+'</td>'+
    '<td class="num" title="Weighted avg: \u03A3(avgDur \u00D7 tours) / \u03A3(tours) = '+tDurH.toFixed(1)+'h / '+tTours+' = '+tAvgDurH.toFixed(2)+'h">'+tAvgDurH.toFixed(2)+'</td>'+
    '<td class="num '+tSpCls+'" title="\u03A3(stops) / \u03A3(tour duration) = '+tStops.toFixed(0)+' / '+tDurH.toFixed(1)+'h = '+tStopsPerHour.toFixed(1)+'">'+tStopsPerHour.toFixed(1)+'</td>'+
    '<td class="num '+tSkmCls+'" title="\u03A3(stops) / \u03A3(km) = '+tStops.toFixed(0)+' / '+tKm.toFixed(0)+'km = '+tStopsKm.toFixed(2)+'">'+tStopsKm.toFixed(2)+'</td>'+
    '<td class="num '+tPpkCls+'" title="\u03A3(parcels) / \u03A3(km) = '+tParcels.toFixed(0)+' / '+tKm.toFixed(0)+'km = '+tParcelsPerKm.toFixed(2)+'">'+tParcelsPerKm.toFixed(2)+'</td>'+
    '<td class="num '+tPphCls+'" title="\u03A3(parcels) / \u03A3(tour duration) = '+tParcels.toFixed(0)+' / '+tDurH.toFixed(1)+'h = '+tPph.toFixed(1)+'">'+tPph.toFixed(1)+'</td>'+
    '<td class="num" title="\u03A3(km) / \u03A3(parcels) = '+tKm.toFixed(0)+'km / '+tParcels.toFixed(0)+' = '+tKmPerParcel.toFixed(2)+'">'+tKmPerParcel.toFixed(2)+'</td>'+
    '<td class="num" title="\u03A3(km) / \u03A3(travel hours) = '+tKm.toFixed(0)+'km / '+tTravelH.toFixed(1)+'h = '+tSpd.toFixed(1)+' km/h">'+tSpd.toFixed(1)+'</td>'+
    '<td class="num" title="\u03A3(travel hours) / \u03A3(tour duration) \u00D7 100 = '+tTravelH.toFixed(1)+'h / '+tDurH.toFixed(1)+'h = '+tTravelPct.toFixed(1)+'%%">'+tTravelPct.toFixed(1)+'</td>'+
    '<td class="num" title="\u03A3(service hours) / \u03A3(tour duration) \u00D7 100 = '+tSvcH.toFixed(1)+'h / '+tDurH.toFixed(1)+'h = '+tSvcPct.toFixed(1)+'%%">'+tSvcPct.toFixed(1)+'</td>'+
    '<td class="num" title="\u03A3(stem km) / \u03A3(km) \u00D7 100 = '+tStemKm.toFixed(1)+'km / '+tKm.toFixed(0)+'km = '+tStemPct.toFixed(1)+'%%">'+tStemPct.toFixed(1)+'</td>'+
    '<td class="num '+tLfCls+'" title="\u03A3(parcels) / \u03A3(capacity) \u00D7 100 = '+tParcels.toFixed(0)+' / '+tCap.toFixed(0)+' = '+tAvgLoadPct.toFixed(1)+'%%">'+tAvgLoadPct.toFixed(1)+'</td>'+
    '<td class="num" title="\u03A3(cost) / \u03A3(tours) = '+tCost.toFixed(0)+'\u20AC / '+tTours+' = '+tAvgCostPerTour.toFixed(1)+'\u20AC">'+tAvgCostPerTour.toFixed(1)+'</td>'+
    '<td class="num" title="\u03A3(cost) / \u03A3(parcels) = '+tCost.toFixed(0)+'\u20AC / '+tParcels.toFixed(0)+' = '+tCostPerParcel.toFixed(2)+'\u20AC">'+tCostPerParcel.toFixed(2)+'</td></tr>';
})();

// === SUMMARY TABLE ===
(function(){
  var tb=document.getElementById('summaryBody');
  var fm=function(n){return typeof n==='number'?n.toLocaleString('en-US'):n};
  var fmtSec=function(s){var h=Math.floor(s/3600);var m=Math.floor((s%%3600)/60);return (h<10?'0':'')+h+':'+(m<10?'0':'')+m};
  var totals={carriers:0,vehicles:0,parcels:0,missed:0,distKm:0,tourDurH:0,drivingH:0,cost:0};
  var expanded={};
  var NCOLS=15;

  function render(){
    var html='';
    SUMMARY.forEach(function(r,idx){
      var cls=r.successRate>=95?'good':r.successRate>=80?'mid':'bad';
      var lfCls=r.avgLoadFactor>=70?'good':r.avgLoadFactor>=40?'mid':'bad';
      var isExp=expanded[r.provider];
      html+='<tr class="sum-row" data-prov="'+r.provider+'" style="cursor:pointer">';
      html+='<td style="padding:5px 4px;color:var(--dim);font-size:.7rem">'+(isExp?'\u25BC':'\u25B6')+'</td>';
      html+='<td style="font-weight:700;color:'+(PROV_COLORS[r.provider]||'var(--text)')+'">'+r.provider+
        '</td><td class="num">'+fm(r.carriers)+'</td><td class="num">'+fm(r.vehicles)+
        '</td><td class="num">'+fm(r.parcels)+'</td><td class="num">'+fm(r.missed)+
        '</td><td class="num '+cls+'">'+r.successRate.toFixed(1)+'</td><td class="num">'+fm(Math.round(r.distKm))+
        '</td><td class="num">'+r.tourDurH.toFixed(1)+'</td><td class="num">'+r.drivingH.toFixed(1)+'</td><td class="num">'+fm(Math.round(r.cost))+
        '</td><td class="num '+lfCls+'">'+r.avgLoadFactor.toFixed(1)+'</td><td class="num">'+fm(Math.round(r.avgParcelsPerVeh))+
        '</td><td class="num">'+r.avgKmPerVeh.toFixed(1)+'</td><td class="num">'+r.costPerParcel.toFixed(2)+'</td></tr>';
      if(isExp&&r.vehDetails&&r.vehDetails.length>0){
        html+='<tr class="sum-detail"><td colspan="'+NCOLS+'" style="padding:0 0 0 28px;background:rgba(148,163,184,.04)">';
        html+='<table style="width:100%%;font-size:.72rem;border-collapse:collapse;margin:6px 0 10px">';
        html+='<thead><tr style="border-bottom:1px solid rgba(148,163,184,.15)">';
        html+='<th style="text-align:left;padding:3px 6px">Carrier</th>';
        html+='<th style="text-align:left;padding:3px 6px">Vehicle</th>';
        html+='<th style="text-align:left;padding:3px 6px">Type</th>';
        html+='<th style="text-align:right;padding:3px 6px">Cap</th>';
        html+='<th style="text-align:right;padding:3px 6px">Parcels</th>';
        html+='<th style="text-align:right;padding:3px 6px">Stops</th>';
        html+='<th style="text-align:right;padding:3px 6px">Load %%</th>';
        html+='<th style="text-align:right;padding:3px 6px">Dist (km)</th>';
        html+='<th style="text-align:right;padding:3px 6px">Tour (h)</th>';
        html+='<th style="text-align:right;padding:3px 6px">Drive (h)</th>';
        html+='<th style="text-align:right;padding:3px 6px">Fix \u20AC</th>';
        html+='<th style="text-align:right;padding:3px 6px">\u20AC/km</th>';
        html+='<th style="text-align:right;padding:3px 6px">Del Rate %%</th>';
        html+='<th style="text-align:right;padding:3px 6px">Dep</th>';
        html+='<th style="text-align:right;padding:3px 6px">Arr</th>';
        html+='</tr></thead><tbody>';
        r.vehDetails.forEach(function(v){
          var lfCls2=v.loadFactor>=70?'good':v.loadFactor>=40?'mid':'bad';
          html+='<tr style="border-bottom:1px solid rgba(148,163,184,.05)">';
          html+='<td style="padding:3px 6px;color:var(--dim);font-size:.68rem;white-space:nowrap">'+v.carrier+'</td>';
          html+='<td style="padding:3px 6px;white-space:nowrap">'+v.vid+'</td>';
          html+='<td style="padding:3px 6px;color:var(--dim)">'+v.vtype.replace('ct_','')+'</td>';
          html+='<td style="padding:3px 6px;text-align:right">'+v.cap+'</td>';
          html+='<td style="padding:3px 6px;text-align:right;font-weight:600">'+v.parcels+'</td>';
          html+='<td style="padding:3px 6px;text-align:right">'+v.stops+'</td>';
          html+='<td style="padding:3px 6px;text-align:right" class="'+lfCls2+'">'+v.loadFactor.toFixed(1)+'</td>';
          html+='<td style="padding:3px 6px;text-align:right">'+v.distKm.toFixed(1)+'</td>';
          html+='<td style="padding:3px 6px;text-align:right">'+v.durH.toFixed(2)+'</td>';
          html+='<td style="padding:3px 6px;text-align:right">'+v.travelH.toFixed(2)+'</td>';
          html+='<td style="padding:3px 6px;text-align:right">'+v.fixCost.toFixed(1)+'</td>';
          html+='<td style="padding:3px 6px;text-align:right">'+v.costPerKm.toFixed(4)+'</td>';
          var drCls=v.delRate>=95?'good':v.delRate>=80?'mid':'bad';
          html+='<td style="padding:3px 6px;text-align:right" class="'+drCls+'">'+v.delRate.toFixed(1)+'</td>';
          html+='<td style="padding:3px 6px;text-align:right">'+fmtSec(v.depSec)+'</td>';
          html+='<td style="padding:3px 6px;text-align:right">'+fmtSec(v.arrSec)+'</td>';
          html+='</tr>';
        });
        html+='</tbody></table></td></tr>';
      }
    });
    // TOTAL row
    var ts=totals;var sp=ts.parcels>0?100*(ts.parcels-ts.missed)/ts.parcels:100;
    html+='<tr style="font-weight:700;border-top:2px solid var(--accent)"><td></td><td>TOTAL</td>'+
      '<td class="num" title="Sum of all provider carriers">'+fm(ts.carriers)+'</td>'+
      '<td class="num" title="Sum of all provider vehicles (tours)">'+fm(ts.vehicles)+'</td>'+
      '<td class="num" title="Sum of all provider parcels">'+fm(ts.parcels)+'</td>'+
      '<td class="num" title="Sum of all missed parcels">'+fm(ts.missed)+'</td>'+
      '<td class="num" title="(Parcels \u2212 Missed) / Parcels \u00D7 100 = ('+ts.parcels+' \u2212 '+ts.missed+') / '+ts.parcels+' = '+sp.toFixed(1)+'%%">'+sp.toFixed(1)+'</td>'+
      '<td class="num" title="Sum of all provider distances">'+fm(Math.round(ts.distKm))+'</td>'+
      '<td class="num" title="Sum of all tour durations (depot departure to return)">'+ts.tourDurH.toFixed(1)+'</td>'+
      '<td class="num" title="Sum of all driving hours (excl. service time)">'+ts.drivingH.toFixed(1)+'</td>'+
      '<td class="num" title="Sum of all provider costs (dist + time + overtime + fixed)">'+fm(Math.round(ts.cost))+'</td>'+
      '<td class="num" title="Not applicable for total (per-provider weighted avg)">-</td>'+
      '<td class="num" title="Total Parcels / Total Vehicles = '+ts.parcels+' / '+ts.vehicles+' = '+(ts.vehicles>0?Math.round(ts.parcels/ts.vehicles):'-')+'">'+
      (ts.vehicles>0?fm(Math.round(ts.parcels/ts.vehicles)):'-')+'</td>'+
      '<td class="num" title="Total Dist / Total Vehicles = '+Math.round(ts.distKm)+'km / '+ts.vehicles+' = '+(ts.vehicles>0?(ts.distKm/ts.vehicles).toFixed(1):'-')+'km">'+
      (ts.vehicles>0?(ts.distKm/ts.vehicles).toFixed(1):'-')+'</td>'+
      '<td class="num" title="Total Cost / Total Parcels = '+Math.round(ts.cost)+'\u20AC / '+ts.parcels+' = '+(ts.parcels>0?(ts.cost/ts.parcels).toFixed(2):'-')+'\u20AC">'+
      (ts.parcels>0?(ts.cost/ts.parcels).toFixed(2):'-')+'</td></tr>';
    tb.innerHTML=html;
    // re-attach click listeners
    tb.querySelectorAll('.sum-row').forEach(function(row){
      row.addEventListener('click',function(){
        var p=this.getAttribute('data-prov');
        expanded[p]=!expanded[p];
        render();
      });
    });
  }
  // compute totals once
  SUMMARY.forEach(function(r){
    totals.carriers+=r.carriers;totals.vehicles+=r.vehicles;totals.parcels+=r.parcels;totals.missed+=r.missed;
    totals.distKm+=r.distKm;totals.tourDurH+=r.tourDurH;totals.drivingH+=r.drivingH;totals.cost+=r.cost;
  });
  render();
})();

// Convert title attributes to data-tip for CSS tooltips (removes native title delay)
document.querySelectorAll('.rtbl th[title]').forEach(function(th){
  th.setAttribute('data-tip',th.getAttribute('title'));
  th.removeAttribute('title');
});

""";
    private static final String HTML_PART2B = """
// === TRAFFIC VOLUME MAP ===
(function(){
  if(!LINK_VOL||!LINK_VOL.features||LINK_VOL.features.length===0){
    document.getElementById('volMap').innerHTML='<div style="padding:40px;text-align:center;color:var(--dim)">No traffic volume data available</div>';
    return;
  }

  // Compute bounding box from TRAFFIC data only (ensures correct zoom)
  var mnLat=90,mxLat=-90,mnLon=180,mxLon=-180;
  LINK_VOL.features.forEach(function(f){
    f.geometry.coordinates.forEach(function(c){
      if(c[1]<mnLat)mnLat=c[1];if(c[1]>mxLat)mxLat=c[1];
      if(c[0]<mnLon)mnLon=c[0];if(c[0]>mxLon)mxLon=c[0];
    });
  });
  var dataBounds=[[mnLat,mnLon],[mxLat,mxLon]];

  var vmEl=document.getElementById('volMap');
  vmEl.style.height='600px';
  var vm=L.map('volMap',{zoomControl:true}).setView([(mnLat+mxLat)/2,(mnLon+mxLon)/2],12);
  L.tileLayer('https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png',{
    attribution:'OpenStreetMap, CARTO',maxZoom:19,subdomains:'abcd'
  }).addTo(vm);
  vm.fitBounds(dataBounds,{padding:[20,20]});
  vm.createPane('netPane');vm.getPane('netPane').style.zIndex=420;
  vm.createPane('volPane');vm.getPane('volPane').style.zIndex=650;

  // Background network (single multi-polyline — one render object)
  var netLayer=null;
  function buildNetLayer(){
    if(netLayer){vm.removeLayer(netLayer);netLayer=null}
    if(!document.getElementById('showNet').checked)return;
    if(!NET_BG||!NET_BG.features||NET_BG.features.length===0)return;
    var op=parseInt(document.getElementById('netOpacity').value)/100;
    var ac=[];NET_BG.features.forEach(function(f){ac.push(f.geometry.coordinates.map(function(c){return[c[1],c[0]]}))});
    netLayer=L.polyline(ac,{color:'#64748b',weight:0.8,opacity:op,interactive:false,pane:'netPane',smoothFactor:0}).addTo(vm);
  }
  buildNetLayer();

  var volLayer=null;
  var curMode='all';
  var curTimeMode='day';
  var curHour=8;

  // Graphic slider values
  var gfxWidth=parseFloat(document.getElementById('volWidth').value);
  var gfxOpacity=parseInt(document.getElementById('volOpacity').value)/100;
  var gfxScale=parseInt(document.getElementById('volScale').value)/100;
  var curCmap='thermal';

  // ── Color maps (designed for dark basemap) ──
  var CMAPS={
    thermal:[[0,[50,180,255]],[0.2,[60,130,250]],[0.45,[200,120,50]],[0.7,[255,180,30]],[1,[255,255,120]]],
    viridis:[[0,[120,50,200]],[0.2,[80,110,220]],[0.45,[50,200,180]],[0.7,[130,230,80]],[1,[253,231,37]]],
    plasma:[[0,[160,50,240]],[0.2,[200,60,200]],[0.45,[240,90,120]],[0.7,[255,160,50]],[1,[240,250,40]]],
    inferno:[[0,[120,40,180]],[0.2,[190,50,120]],[0.45,[240,90,50]],[0.7,[255,170,20]],[1,[255,255,130]]],
    cool:[[0,[0,255,255]],[0.5,[140,130,255]],[1,[255,50,255]]],
    electric:[[0,[100,50,240]],[0.15,[160,40,250]],[0.35,[220,70,230]],[0.55,[250,120,170]],[0.8,[255,190,80]],[1,[255,255,210]]]
  };
  function cmapColor(ratio,name){
    var stops=CMAPS[name]||CMAPS.thermal;
    if(ratio<=0)return stops[0][1];
    if(ratio>=1)return stops[stops.length-1][1];
    for(var i=1;i<stops.length;i++){
      if(ratio<=stops[i][0]){
        var t=(ratio-stops[i-1][0])/(stops[i][0]-stops[i-1][0]);
        var a=stops[i-1][1],b=stops[i][1];
        return[Math.round(a[0]+(b[0]-a[0])*t),Math.round(a[1]+(b[1]-a[1])*t),Math.round(a[2]+(b[2]-a[2])*t)];
      }
    }
    return stops[stops.length-1][1];
  }
  function updateCmapPreview(){
    var el=document.getElementById('cmapPreview');
    var stops=CMAPS[curCmap]||CMAPS.thermal;
    var grad='linear-gradient(to right';
    stops.forEach(function(s){grad+=',rgb('+s[1][0]+','+s[1][1]+','+s[1][2]+') '+(s[0]*100)+'%%'});
    el.style.background=grad+')';
  }
  updateCmapPreview();

  // Precompute full-day max per mode so hourly colour scale matches full-day reference
  var globalDayMax={all:0,delivery:0,supply:0};
  LINK_VOL.features.forEach(function(f){
    var p=f.properties;
    if(p.total>globalDayMax.all)globalDayMax.all=p.total;
    if(p.delivery>globalDayMax.delivery)globalDayMax.delivery=p.delivery;
    if(p.supply>globalDayMax.supply)globalDayMax.supply=p.supply;
  });

  function updateVolLegend(maxV){
    var el=document.getElementById('volLegend');
    if(!maxV||maxV<=0){el.innerHTML='';return}
    var stops=CMAPS[curCmap]||CMAPS.thermal;
    var grad='linear-gradient(to right';
    stops.forEach(function(s){grad+=',rgb('+s[1][0]+','+s[1][1]+','+s[1][2]+') '+(s[0]*100)+'%%'});
    grad+=')';
    var pw=gfxScale;
    var nTicks=6;var ticks='';
    for(var i=0;i<=nTicks;i++){
      var frac=i/nTicks;
      var val=Math.round(Math.pow(frac,1/pw)*maxV);
      ticks+='<span style=\"flex:1;text-align:'+(i===0?'left':i===nTicks?'right':'center')+';font-size:.62rem;color:var(--dim)\">'+val+'</span>';
    }
    el.innerHTML='<span style=\"font-size:.65rem;font-weight:700;color:var(--accent);letter-spacing:.5px;text-transform:uppercase;white-space:nowrap\">Volume Scale</span>'+
      '<div style=\"flex:1;min-width:180px;max-width:400px\">'+
        '<div style=\"height:10px;border-radius:4px;background:'+grad+';border:1px solid rgba(148,163,184,.12)\"></div>'+
        '<div style=\"display:flex;margin-top:1px\">'+ticks+'</div>'+
      '</div>'+
      '<span style=\"font-size:.62rem;color:var(--dim)\">vehicles/link</span>';
  }

  function getVal(p,mode,timeMode,hour){
    if(timeMode==='hour'){
      if(mode==='delivery')return p.hd?p.hd[hour]:0;
      if(mode==='supply')return p.hs?p.hs[hour]:0;
      return p.ht?p.ht[hour]:0;
    }
    if(mode==='delivery')return p.delivery;
    if(mode==='supply')return p.supply;
    return p.total;
  }

  function buildVolLayer(){
    if(volLayer){vm.removeLayer(volLayer);volLayer=null}
    var maxV=0;var totalVol=0;var activeLinks=0;
    LINK_VOL.features.forEach(function(f){
      var v=getVal(f.properties,curMode,curTimeMode,curHour);
      if(v>maxV)maxV=v;
      if(v>0){totalVol+=v;activeLinks++}
    });
    // In hourly mode always use full-day max so legend+colours stay fixed across hours
    var colorMax=(curTimeMode==='hour')?(globalDayMax[curMode]||maxV):maxV;
    if(maxV===0){updateVolStats(0,0,0);updateVolLegend(colorMax);return}
    var baseW=gfxWidth,baseOp=gfxOpacity,pw=gfxScale,cname=curCmap;
    // Bucket links by volume into 12 color groups (12 render objects vs 22K)
    var NB=12,buckets=[];
    for(var b=0;b<NB;b++)buckets.push([]);
    LINK_VOL.features.forEach(function(f){
      var v=getVal(f.properties,curMode,curTimeMode,curHour);
      if(v<=0)return;
      var ratio=Math.pow(v/colorMax,pw);
      buckets[Math.min(NB-1,Math.floor(ratio*NB))].push(f.geometry.coordinates.map(function(c){return[c[1],c[0]]}));
    });
    var layers=[];
    buckets.forEach(function(coords,i){
      if(!coords.length)return;
      var ratio=(i+0.5)/NB;
      var rgb=cmapColor(ratio,cname);
      var w=Math.max(0.1,baseW*0.3+ratio*baseW*0.7);
      var op=baseOp*(0.45+ratio*0.55);
      layers.push(L.polyline(coords,{
        color:'rgb('+rgb[0]+','+rgb[1]+','+rgb[2]+')',weight:w,opacity:op,
        lineCap:'round',lineJoin:'round',smoothFactor:0,pane:'volPane'
      }));
    });
    volLayer=L.featureGroup(layers).addTo(vm);
    updateVolStats(activeLinks,totalVol,maxV);
    updateVolLegend(colorMax);
  }

  function findPeakHour(p){
    if(!p.ht)return'-';
    var mx=0,mh=0;for(var h=0;h<24;h++){if(p.ht[h]>mx){mx=p.ht[h];mh=h}}
    return(mh<10?'0':'')+mh+':00 ('+mx+' vehicles)';
  }

  function updateVolStats(links,vol,maxV){
    var s=document.getElementById('volStats');
    var label=curTimeMode==='hour'?(curHour<10?'0':'')+curHour+':00\\u2013'+(curHour<9?'0':'')+(curHour+1)+':00':'Full Day';
    s.innerHTML='<span><b style=\"color:var(--accent)\">'+label+'</b></span>'+
      '<span>Active Links: <b style=\"color:var(--text)\">'+links.toLocaleString()+'</b></span>'+
      '<span>Total Traversals: <b style=\"color:var(--text)\">'+vol.toLocaleString()+'</b></span>'+
      '<span>Max per Link: <b style=\"color:var(--text)\">'+maxV+'</b></span>'+
      '<span>Mode: <b style=\"color:var(--accent)\">'+curMode+'</b></span>'+
      '<span>Links in dataset: <b style=\"color:var(--dim)\">'+LINK_VOL.features.length.toLocaleString()+'</b></span>';
  }

  // Hour slider
  var slider=document.getElementById('hourSlider');
  var hourLabel=document.getElementById('hourLabel');
  var sliderWrap=document.getElementById('sliderWrap');
  slider.oninput=function(){
    curHour=parseInt(this.value);
    hourLabel.textContent=(curHour<10?'0':'')+curHour+':00';
    buildVolLayer();
  };
  document.querySelectorAll('input[name=\"volTime\"]').forEach(function(r){
    r.onchange=function(){
      curTimeMode=r.value;
      sliderWrap.style.display=curTimeMode==='hour'?'block':'none';
      buildVolLayer();
    };
  });
  document.querySelectorAll('input[name=\"volMode\"]').forEach(function(r){
    r.onchange=function(){curMode=r.value;buildVolLayer()};
  });

  // Graphic sliders
  document.getElementById('volWidth').oninput=function(){
    gfxWidth=parseFloat(this.value);
    document.getElementById('volWidthVal').textContent=this.value;
    buildVolLayer();
  };
  document.getElementById('volOpacity').oninput=function(){
    gfxOpacity=parseInt(this.value)/100;
    document.getElementById('volOpacityVal').textContent=this.value+'%%';
    buildVolLayer();
  };
  document.getElementById('volScale').oninput=function(){
    gfxScale=parseInt(this.value)/100;
    document.getElementById('volScaleVal').textContent=(parseInt(this.value)/100).toFixed(1);
    buildVolLayer();
  };
  document.getElementById('showNet').onchange=function(){buildNetLayer()};
  document.getElementById('netOpacity').oninput=function(){
    document.getElementById('netOpacityVal').textContent=this.value+'%%';
    buildNetLayer();
  };
  document.getElementById('volColorMap').onchange=function(){
    curCmap=this.value;
    updateCmapPreview();
    buildVolLayer();
  };

  // Deferred init
  var inited=false;
  function initOnce(){
    if(inited)return;inited=true;
    vm.invalidateSize();
    setTimeout(function(){vm.invalidateSize();vm.fitBounds(dataBounds,{padding:[15,15]});buildNetLayer();buildVolLayer()},200);
  }
  buildVolLayer();
  var obs=new IntersectionObserver(function(entries){
    entries.forEach(function(e){if(e.isIntersecting){initOnce();obs.unobserve(e.target)}});
  },{threshold:0.05});
  obs.observe(vmEl);
  setTimeout(initOnce,800);

  // Compute hourly chart data from LINK_VOL features
  var hourlyActiveLinks=[],hourlyTotalVol=[],hourlyDeliveryVol=[],hourlySupplyVol=[];
  for(var h=0;h<24;h++){
    var aLinks=0,tVol=0,dVol=0,sVol=0;
    LINK_VOL.features.forEach(function(f){
      var p=f.properties;
      var t=p.ht?p.ht[h]:0;var d=p.hd?p.hd[h]:0;var sv=p.hs?p.hs[h]:0;
      if(t>0)aLinks++;
      tVol+=t;dVol+=d;sVol+=sv;
    });
    hourlyActiveLinks.push(aLinks);hourlyTotalVol.push(tVol);hourlyDeliveryVol.push(dVol);hourlySupplyVol.push(sVol);
  }
  var hlabels=[];for(var h=0;h<24;h++)hlabels.push((h<10?'0':'')+h+':00');

  new Chart(document.getElementById('hourlyLinksChart'),{type:'bar',
    data:{labels:hlabels,datasets:[
      {label:'Active Links',data:hourlyActiveLinks,backgroundColor:'rgba(56,189,248,.5)',borderRadius:2,barPercentage:.9,categoryPercentage:.9}
    ]},
    options:{responsive:true,
      scales:{x:{grid:{display:false},ticks:{font:{size:9},maxRotation:45}},
        y:{beginAtZero:true,grid:{color:'rgba(148,163,184,.08)'},title:{display:true,text:'links with traffic',color:'#94a3b8',font:{size:9}}}},
      plugins:{legend:{display:false},tooltip:{callbacks:{label:function(c){return c.parsed.y.toLocaleString()+' active links at '+c.label}}}}}});

  new Chart(document.getElementById('hourlyVolChart'),{type:'bar',
    data:{labels:hlabels,datasets:[
      {label:'Delivery',data:hourlyDeliveryVol,backgroundColor:'rgba(56,189,248,.55)',stack:'s',borderRadius:1},
      {label:'Supply',data:hourlySupplyVol,backgroundColor:'rgba(139,92,246,.55)',stack:'s',borderRadius:1}
    ]},
    options:{responsive:true,
      scales:{x:{stacked:true,grid:{display:false},ticks:{font:{size:9},maxRotation:45}},
        y:{stacked:true,beginAtZero:true,grid:{color:'rgba(148,163,184,.08)'},title:{display:true,text:'link traversals',color:'#94a3b8',font:{size:9}}}},
      plugins:{legend:lgOpt,tooltip:{mode:'index',intersect:false}}}});
})();

// === LINK TRAFFIC LOAD HISTOGRAM ===
(function(){
  var feats=LINK_VOL.features;
  if(!feats||!feats.length)return;

  /* pre-compute absolute load arrays per link */
  var linkLoads=[];
  feats.forEach(function(f){
    var p=f.properties;
    var dayVol=p.total||0;
    var hu=[];
    for(var h=0;h<24;h++) hu.push(p.ht[h]||0);
    linkLoads.push({dayVol:dayVol,hourVol:hu});
  });

  /* adaptive binning — compute sensible bin width from data */
  function makeBins(vals){
    if(!vals.length)return {bins:[],labels:[],stats:{count:0,mean:0,median:0,p95:0,max:0,total:0}};
    var sorted=vals.slice().sort(function(a,b){return a-b});
    var mx=sorted[sorted.length-1];
    var p95=sorted[Math.floor(sorted.length*0.95)];
    var sum=0;sorted.forEach(function(v){sum+=v});
    var mean=sum/sorted.length;
    var med=sorted.length%%2===0?(sorted[sorted.length/2-1]+sorted[sorted.length/2])/2:sorted[Math.floor(sorted.length/2)];
    /* choose bin width: target ~20 bins up to p95 */
    var rawW=p95/20;
    var niceSteps=[1,2,5,10,20,25,50,100,200,500,1000];
    var binW=1;
    for(var s=0;s<niceSteps.length;s++){if(niceSteps[s]>=rawW){binW=niceSteps[s];break;}}
    if(binW<1)binW=1;
    var nBins=Math.min(30,Math.ceil(mx/binW)+1);
    var bins=new Array(nBins).fill(0);
    var labels=[];
    for(var b=0;b<nBins;b++){
      if(b===nBins-1)labels.push('>'+(binW*(nBins-1)));
      else labels.push((binW*b)+'-'+(binW*(b+1)));
    }
    vals.forEach(function(v){
      var idx=Math.min(Math.floor(v/binW),nBins-1);
      bins[idx]++;
    });
    return {bins:bins,labels:labels,stats:{count:sorted.length,mean:mean,median:med,p95:p95,max:mx,total:sum}};
  }

  function getVals(mode,hour){
    if(mode==='day')return linkLoads.map(function(l){return l.dayVol});
    return linkLoads.map(function(l){return l.hourVol[hour]});
  }

  function gradient(ctx,nBins){
    var g=ctx.createLinearGradient(0,0,ctx.canvas.width,0);
    g.addColorStop(0,'#38bdf8');g.addColorStop(0.5,'#a78bfa');g.addColorStop(1,'#f43f5e');
    return g;
  }

  var ctx=document.getElementById('utilHistChart').getContext('2d');
  var chart=new Chart(ctx.canvas,{type:'bar',
    data:{labels:[],datasets:[{label:'Links',data:[],borderRadius:3,barPercentage:1,categoryPercentage:.95}]},
    options:{responsive:true,animation:{duration:350,easing:'easeOutQuart'},
      scales:{
        x:{grid:{display:false},title:{display:true,text:'Vehicle traversals per link',color:'#94a3b8',font:{size:10,weight:'bold'}},
          ticks:{font:{size:8},maxRotation:50,autoSkip:true,maxTicksLimit:20}},
        y:{beginAtZero:true,grid:{color:'rgba(148,163,184,.06)',lineWidth:1},
          title:{display:true,text:'Number of links',color:'#94a3b8',font:{size:10,weight:'bold'}},
          ticks:{callback:function(v){return v>=1000?(v/1000).toFixed(1)+'k':v},font:{size:9}}}},
      plugins:{legend:{display:false},
        tooltip:{backgroundColor:'rgba(15,23,42,.92)',titleFont:{size:11},bodyFont:{size:11},
          callbacks:{label:function(c){return c.parsed.y.toLocaleString()+' links  ('+chart.data.labels[c.dataIndex]+' traversals)'}}}}}});

  var statsEl=document.getElementById('utilHistStats');

  function updateHist(mode,hour){
    var vals=getVals(mode,hour);
    var r=makeBins(vals);
    chart.data.labels=r.labels;
    chart.data.datasets[0].data=r.bins;
    chart.data.datasets[0].backgroundColor=gradient(ctx,r.bins.length);
    chart.update();
    var s=r.stats;
    statsEl.innerHTML=
      '<span><b style="color:var(--accent)">'+s.count.toLocaleString()+'</b> links</span>'+
      '<span>Total traversals: <b style="color:var(--accent)">'+s.total.toLocaleString()+'</b></span>'+
      '<span>Mean: <b style="color:var(--accent)">'+s.mean.toFixed(1)+'</b></span>'+
      '<span>Median: <b style="color:var(--accent)">'+s.median.toFixed(0)+'</b></span>'+
      '<span>95th pct: <b style="color:#a78bfa">'+s.p95.toFixed(0)+'</b></span>'+
      '<span>Max: <b style="color:#f43f5e">'+s.max.toLocaleString()+'</b></span>';
  }

  updateHist('day',8);

  var radios=document.querySelectorAll('input[name="utilTime"]');
  var sliderWrap=document.getElementById('utilSliderWrap');
  var slider=document.getElementById('utilHourSlider');
  var hourLabel=document.getElementById('utilHourLabel');
  radios.forEach(function(r){
    r.addEventListener('change',function(){
      var isHour=this.value==='hour';
      sliderWrap.style.display=isHour?'':'none';
      updateHist(this.value,parseInt(slider.value));
    });
  });
  slider.addEventListener('input',function(){
    var h=parseInt(this.value);
    hourLabel.textContent=(h<10?'0':'')+h+':00';
    updateHist('hour',h);
  });
})();

// === CHARTS ===
Chart.defaults.color='#94a3b8';
Chart.defaults.borderColor='rgba(148,163,184,.1)';
Chart.defaults.font.family="'Inter',sans-serif";
var lgOpt={position:'bottom',labels:{padding:7,usePointStyle:true,pointStyleWidth:8,font:{size:10}}};

new Chart(document.getElementById('donut'),{type:'doughnut',
  data:{labels:VTYPES.labels,datasets:[{data:VTYPES.values,backgroundColor:VTYPES.colors,borderWidth:0,hoverOffset:8}]},
  options:{responsive:true,cutout:'65%%',plugins:{legend:lgOpt}}});

new Chart(document.getElementById('provDonut'),{type:'doughnut',
  data:{labels:PROV.labels,datasets:[{data:PROV.parcels,backgroundColor:PROV.colors,borderWidth:0,hoverOffset:8}]},
  options:{responsive:true,cutout:'65%%',plugins:{legend:lgOpt,tooltip:{callbacks:{label:function(c){return c.label+': '+c.parsed.toLocaleString()+' parcels'}}}}}});

new Chart(document.getElementById('timeline'),{type:'bar',
  data:{labels:TL.hours,datasets:[
    {label:'Departures',data:TL.departures,backgroundColor:'rgba(56,189,248,.6)',borderRadius:3,barPercentage:.8},
    {label:'Service starts',data:TL.services,backgroundColor:'rgba(129,140,248,.5)',borderRadius:3,barPercentage:.8}
  ]},
  options:{responsive:true,interaction:{mode:'index',intersect:false},
    scales:{x:{grid:{display:false}},y:{beginAtZero:true,grid:{color:'rgba(148,163,184,.08)'}}},
    plugins:{legend:lgOpt}}});

new Chart(document.getElementById('utilChart'),{type:'bar',
  data:{labels:UTIL.labels,datasets:[
    {label:'Avg Utilisation %%',data:UTIL.avgLoadFactorPct,backgroundColor:'rgba(56,189,248,.6)',borderRadius:3,yAxisID:'y'},
    {label:'Avg Parcels',data:UTIL.avgParcels,backgroundColor:'rgba(52,211,153,.5)',borderRadius:3,yAxisID:'y1'}
  ]},
  options:{responsive:true,
    scales:{x:{grid:{display:false}},
      y:{beginAtZero:true,max:100,position:'left',grid:{color:'rgba(148,163,184,.08)'},title:{display:true,text:'%%',color:'#38bdf8',font:{size:9}}},
      y1:{beginAtZero:true,position:'right',grid:{drawOnChartArea:false},title:{display:true,text:'parcels',color:'#34d399',font:{size:9}}}},
    plugins:{legend:lgOpt}}});

new Chart(document.getElementById('utilTypeChart'),{type:'bar',
  data:{labels:UTIL_BY_TYPE.labels,datasets:[
    {label:'Avg Utilisation %%',data:UTIL_BY_TYPE.avgLoadFactorPct,backgroundColor:'rgba(129,140,248,.6)',borderRadius:3,yAxisID:'y'},
    {label:'Avg Capacity',data:UTIL_BY_TYPE.avgCap,backgroundColor:'rgba(245,158,11,.4)',borderRadius:3,yAxisID:'y1'},
    {label:'Avg Parcels',data:UTIL_BY_TYPE.avgParcels,backgroundColor:'rgba(52,211,153,.5)',borderRadius:3,yAxisID:'y1'}
  ]},
  options:{responsive:true,
    scales:{x:{grid:{display:false}},
      y:{beginAtZero:true,max:100,position:'left',grid:{color:'rgba(148,163,184,.08)'},title:{display:true,text:'%%',color:'#818cf8',font:{size:9}}},
      y1:{beginAtZero:true,position:'right',grid:{drawOnChartArea:false},title:{display:true,text:'count',color:'#34d399',font:{size:9}}}},
    plugins:{legend:lgOpt}}});

// === VEHICLE TYPE ANALYTICS (4 fancy charts) ===
(function(){
  /* Aggregate VEHS data by vehicle type */
  var byType={};
  VEHS.forEach(function(v){
    var t=v.vtype||'Unknown';
    if(!byType[t])byType[t]={km:0,stops:0,parcels:0,loadSum:0,durH:0,travelH:0,cap:0,count:0,stemKm:0};
    var b=byType[t];
    b.km+=v.km;b.stops+=v.stops;b.parcels+=v.parcels;
    b.loadSum+=v.loadFactor;b.durH+=v.durH;b.travelH+=v.travelH;
    b.cap+=v.cap;b.count++;b.stemKm+=v.stemKm;
  });
  var vtLabels=Object.keys(byType);
  /* Dynamic color palette matching Java-side VT_COLORS */
  var VT_PAL=['#3b82f6','#10b981','#f59e0b','#ef4444','#8b5cf6','#ec4899','#06b6d4','#84cc16','#f97316','#6366f1'];
  var vtCols=vtLabels.map(function(l,i){return VT_PAL[i%%VT_PAL.length]});

  /* --- 1. Polar Area: Total km by type --- */
  new Chart(document.getElementById('vtKmPolar'),{type:'polarArea',
    data:{labels:vtLabels,datasets:[{data:vtLabels.map(function(l){return Math.round(byType[l].km)}),
      backgroundColor:vtCols.map(function(c){return c+'99'}),borderColor:vtCols,borderWidth:2}]},
    options:{responsive:true,animation:{animateRotate:true,animateScale:true},
      scales:{r:{grid:{color:'rgba(148,163,184,.08)'},ticks:{display:false},pointLabels:{display:false}}},
      plugins:{legend:{position:'bottom',labels:{padding:10,usePointStyle:true,pointStyleWidth:10,font:{size:10}}},
        tooltip:{callbacks:{label:function(c){return c.label+': '+c.parsed.r.toLocaleString()+' km'}}}}}});

  /* --- 2. Radar: normalised performance metrics --- */
  function norm(arr){var mx=Math.max.apply(null,arr);return mx>0?arr.map(function(v){return Math.round(v/mx*100)}):arr;}
  var avgKm=vtLabels.map(function(l){var b=byType[l];return b.count?b.km/b.count:0});
  var avgStops=vtLabels.map(function(l){var b=byType[l];return b.count?b.stops/b.count:0});
  var avgParcels=vtLabels.map(function(l){var b=byType[l];return b.count?b.parcels/b.count:0});
  var avgLF=vtLabels.map(function(l){var b=byType[l];return b.count?b.loadSum/b.count*100:0});
  var stopsPerH=vtLabels.map(function(l){var b=byType[l];return b.durH>0?b.stops/b.durH:0});
  var pcsPerKm=vtLabels.map(function(l){var b=byType[l];return b.km>0?b.parcels/b.km:0});
  new Chart(document.getElementById('vtRadar'),{type:'radar',
    data:{labels:['Avg km/tour','Avg stops/tour','Avg parcels/tour','Load factor','Stops/hour','Parcels/km'],
      datasets:vtLabels.map(function(l,i){return {
        label:l,data:[norm(avgKm)[i],norm(avgStops)[i],norm(avgParcels)[i],norm(avgLF)[i],norm(stopsPerH)[i],norm(pcsPerKm)[i]],
        backgroundColor:vtCols[i]+'33',borderColor:vtCols[i],borderWidth:2,pointBackgroundColor:vtCols[i],pointRadius:4,pointHoverRadius:6
      }})},
    options:{responsive:true,animation:{duration:800,easing:'easeOutBack'},
      scales:{r:{beginAtZero:true,max:100,grid:{color:'rgba(148,163,184,.12)'},angleLines:{color:'rgba(148,163,184,.12)'},
        ticks:{display:false},pointLabels:{color:'#94a3b8',font:{size:10}}}},
      plugins:{legend:{position:'bottom',labels:{padding:10,usePointStyle:true,pointStyleWidth:10,font:{size:10}}},
        tooltip:{callbacks:{label:function(c){
          var actArrs=[avgKm,avgStops,avgParcels,avgLF,stopsPerH,pcsPerKm];
          var units=[' km',' stops',' parcels','%%',' stops/h',' pcs/km'];
          var actual=actArrs[c.dataIndex][c.datasetIndex];
          return c.dataset.label+': '+c.parsed.r+'%% ('+actual.toFixed(1)+units[c.dataIndex]+')';
        }}}
      }}});

  /* --- 3. Doughnut with centre label: avg load factor by type --- */
  var avgLFabs=vtLabels.map(function(l){var b=byType[l];return b.count?Math.round(b.loadSum/b.count*100):0});
  var gaugeChart=new Chart(document.getElementById('vtLoadGauge'),{type:'doughnut',
    data:{labels:vtLabels,datasets:[{data:avgLFabs,backgroundColor:vtCols.map(function(c){return c+'cc'}),borderWidth:0,hoverOffset:10}]},
    options:{responsive:true,cutout:'60%%',animation:{animateRotate:true},
      plugins:{legend:{position:'bottom',labels:{padding:10,usePointStyle:true,pointStyleWidth:10,font:{size:10}}},
        tooltip:{callbacks:{label:function(c){return c.label+': '+c.parsed+'%% load factor'}}}}},
    plugins:[{id:'centerText',afterDraw:function(ch){
      var ctx2=ch.ctx;var w=ch.width,h=ch.height;
      ctx2.save();ctx2.textAlign='center';ctx2.textBaseline='middle';
      var total=0,wSum=0;avgLFabs.forEach(function(v,i){var n=byType[vtLabels[i]].count;total+=n;wSum+=v*n;});
      var wAvg=total>0?Math.round(wSum/total):0;
      ctx2.font='bold 28px Inter,sans-serif';ctx2.fillStyle='#38bdf8';ctx2.fillText(wAvg+'%%',w/2,h/2-6);
      ctx2.font='11px Inter,sans-serif';ctx2.fillStyle='#94a3b8';ctx2.fillText('weighted avg',w/2,h/2+16);
      ctx2.restore();
    }}]});

  /* --- 4. Grouped horizontal bar: avg km + avg stops per tour --- */
  new Chart(document.getElementById('vtKmStops'),{type:'bar',
    data:{labels:vtLabels,datasets:[
      {label:'Avg km/tour',data:avgKm.map(function(v){return +v.toFixed(1)}),backgroundColor:vtCols.map(function(c){return c+'bb'}),borderRadius:6,borderSkipped:false,yAxisID:'y'},
      {label:'Avg stops/tour',data:avgStops.map(function(v){return +v.toFixed(1)}),backgroundColor:vtCols.map(function(c){return c+'55'}),borderRadius:6,borderSkipped:false,yAxisID:'y1'}
    ]},
    options:{responsive:true,indexAxis:'y',
      scales:{
        x:{display:false},
        y:{position:'left',grid:{display:false},ticks:{font:{size:11,weight:'bold'},color:'#e2e8f0'}},
        y1:{display:false}},
      plugins:{legend:{position:'bottom',labels:{padding:10,usePointStyle:true,pointStyleWidth:10,font:{size:10}}},
        tooltip:{callbacks:{label:function(c){return c.dataset.label+': '+c.parsed.x}}}}}});
})();

// === ROUTING EFFICIENCY CHARTS ===
var reLabels=ROUT_EFF.map(function(r){return r.provider});
var reColors=reLabels.map(function(p){return PROV_COLORS[p]||'#6b7280'});

new Chart(document.getElementById('stopsPerHourChart'),{type:'bar',
  data:{labels:reLabels,datasets:[{label:'Stops/h',data:ROUT_EFF.map(function(r){return r.stopsPerHour}),backgroundColor:reColors.map(function(c){return c+'99'}),borderRadius:4}]},
  options:{responsive:true,indexAxis:'y',
    scales:{x:{grid:{color:'rgba(148,163,184,.08)'},title:{display:true,text:'stops per hour',color:'#94a3b8',font:{size:9}}},y:{grid:{display:false}}},
    plugins:{legend:{display:false}}}});

new Chart(document.getElementById('parcelsPerKmChart'),{type:'bar',
  data:{labels:reLabels,datasets:[{label:'Parcels/km',data:ROUT_EFF.map(function(r){return r.parcelsPerKm}),backgroundColor:reColors.map(function(c){return c+'99'}),borderRadius:4}]},
  options:{responsive:true,indexAxis:'y',
    scales:{x:{grid:{color:'rgba(148,163,184,.08)'},title:{display:true,text:'parcels per km',color:'#94a3b8',font:{size:9}}},y:{grid:{display:false}}},
    plugins:{legend:{display:false}}}});

new Chart(document.getElementById('timeSplitChart'),{type:'bar',
  data:{labels:reLabels,datasets:[
    {label:'Travel %%',data:ROUT_EFF.map(function(r){return r.travelPct}),backgroundColor:'rgba(56,189,248,.6)',stack:'s'},
    {label:'Service %%',data:ROUT_EFF.map(function(r){return r.svcPct}),backgroundColor:'rgba(52,211,153,.6)',stack:'s'}
  ]},
  options:{responsive:true,indexAxis:'y',
    scales:{x:{stacked:true,max:100,grid:{color:'rgba(148,163,184,.08)'},title:{display:true,text:'%%',color:'#94a3b8',font:{size:9}}},y:{stacked:true,grid:{display:false}}},
    plugins:{legend:lgOpt}}});

new Chart(document.getElementById('tourProfileChart'),{type:'bar',
  data:{labels:reLabels,datasets:[
    {label:'Avg km/tour',data:ROUT_EFF.map(function(r){return r.avgKm}),backgroundColor:'rgba(56,189,248,.6)',borderRadius:3,yAxisID:'y'},
    {label:'Avg stops/tour',data:ROUT_EFF.map(function(r){return r.avgStops}),backgroundColor:'rgba(245,158,11,.6)',borderRadius:3,yAxisID:'y1'}
  ]},
  options:{responsive:true,
    scales:{x:{grid:{display:false}},
      y:{beginAtZero:true,position:'left',grid:{color:'rgba(148,163,184,.08)'},title:{display:true,text:'km',color:'#38bdf8',font:{size:9}}},
      y1:{beginAtZero:true,position:'right',grid:{drawOnChartArea:false},title:{display:true,text:'stops',color:'#f59e0b',font:{size:9}}}},
    plugins:{legend:lgOpt}}});

// === EXISTING CHARTS ===
new Chart(document.getElementById('distHist'),{type:'bar',
  data:{labels:DIST_H.labels,datasets:[{label:'Vehicles',data:DIST_H.counts,backgroundColor:'rgba(56,189,248,.55)',borderRadius:3,barPercentage:1,categoryPercentage:1}]},
  options:{responsive:true,scales:{x:{grid:{display:false},title:{display:true,text:'km',color:'#94a3b8',font:{size:9}},ticks:{font:{size:9},maxRotation:45}},y:{beginAtZero:true,grid:{color:'rgba(148,163,184,.08)'}}},
    plugins:{legend:{display:false}}}});

new Chart(document.getElementById('durHist'),{type:'bar',
  data:{labels:DUR_H.labels,datasets:[{label:'Vehicles',data:DUR_H.counts,backgroundColor:'rgba(129,140,248,.55)',borderRadius:3,barPercentage:1,categoryPercentage:1}]},
  options:{responsive:true,scales:{x:{grid:{display:false},title:{display:true,text:'hours',color:'#94a3b8',font:{size:9}},ticks:{font:{size:9}}},y:{beginAtZero:true,grid:{color:'rgba(148,163,184,.08)'}}},
    plugins:{legend:{display:false}}}});

new Chart(document.getElementById('depChart'),{type:'bar',
  data:{labels:TL.hours,datasets:[
    {label:'Depot departures',data:TL.departures,backgroundColor:'rgba(56,189,248,.55)',borderRadius:3},
    {label:'Service starts',data:TL.services,backgroundColor:'rgba(245,158,11,.45)',borderRadius:3}
  ]},
  options:{responsive:true,interaction:{mode:'index',intersect:false},
    scales:{x:{grid:{display:false}},y:{beginAtZero:true,stacked:false,grid:{color:'rgba(148,163,184,.08)'}}},
    plugins:{legend:lgOpt}}});

new Chart(document.getElementById('distProvChart'),{type:'bar',
  data:{labels:PROV.labels,datasets:[
    {label:'Vehicles',data:PROV.vehicles,backgroundColor:'rgba(56,189,248,.55)',borderRadius:3,yAxisID:'y'},
    {label:'Parcels (k)',data:PROV.parcels.map(function(v){return Math.round(v/1000)}),backgroundColor:'rgba(52,211,153,.5)',borderRadius:3,yAxisID:'y1'}
  ]},
  options:{responsive:true,
    scales:{x:{grid:{display:false}},
      y:{beginAtZero:true,position:'left',grid:{color:'rgba(148,163,184,.08)'},title:{display:true,text:'vehicles',color:'#38bdf8',font:{size:9}}},
      y1:{beginAtZero:true,position:'right',grid:{drawOnChartArea:false},title:{display:true,text:'parcels (k)',color:'#34d399',font:{size:9}}}},
    plugins:{legend:lgOpt}}});

new Chart(document.getElementById('costBar'),{type:'bar',
  data:{labels:COSTS.labels,datasets:[{label:'Total (\\u20AC)',data:COSTS.total,backgroundColor:'rgba(56,189,248,.6)',borderRadius:4}]},
  options:{responsive:true,indexAxis:'y',
    scales:{x:{grid:{color:'rgba(148,163,184,.08)'}},y:{grid:{display:false},ticks:{font:{size:11}}}},
    plugins:{legend:lgOpt,tooltip:{callbacks:{label:function(c){return '\\u20AC '+Math.round(c.parsed.x).toLocaleString()}}}}}});

new Chart(document.getElementById('costStack'),{type:'bar',
  data:{labels:COSTS.labels,datasets:[
    {label:'Distance',data:COSTS.distance,backgroundColor:'rgba(59,130,246,.6)',stack:'s'},
    {label:'Time',data:COSTS.time,backgroundColor:'rgba(16,185,129,.6)',stack:'s'},
    {label:'Fixed',data:COSTS.fix,backgroundColor:'rgba(245,158,11,.6)',stack:'s'},
    {label:'Overtime',data:COSTS.overtime,backgroundColor:'rgba(248,113,113,.6)',stack:'s'}
  ]},
  options:{responsive:true,indexAxis:'y',
    scales:{x:{stacked:true,grid:{color:'rgba(148,163,184,.08)'}},y:{stacked:true,grid:{display:false},ticks:{font:{size:11}}}},
    plugins:{legend:lgOpt,tooltip:{callbacks:{label:function(c){return c.dataset.label+': \u20AC '+Math.round(c.parsed.x).toLocaleString()}}}}}});

new Chart(document.getElementById('scoringStack'),{type:'bar',
  data:{labels:COSTS.labels,datasets:[
    {label:'Distance',data:COSTS.distance,backgroundColor:'rgba(59,130,246,.5)',stack:'s'},
    {label:'Time',data:COSTS.time,backgroundColor:'rgba(16,185,129,.5)',stack:'s'},
    {label:'Fixed',data:COSTS.fix,backgroundColor:'rgba(245,158,11,.5)',stack:'s'},
    {label:'Activity',data:COSTS.activity,backgroundColor:'rgba(129,140,248,.5)',stack:'s'},
    {label:'Overtime',data:COSTS.overtime,backgroundColor:'rgba(248,113,113,.5)',stack:'s'},
    {label:'TW Penalty',data:COSTS.twPenalty,backgroundColor:'rgba(236,72,153,.5)',stack:'s'}
  ]},
  options:{responsive:true,indexAxis:'y',
    scales:{x:{stacked:true,grid:{color:'rgba(148,163,184,.08)'}},y:{stacked:true,grid:{display:false},ticks:{font:{size:11}}}},
    plugins:{legend:lgOpt,tooltip:{callbacks:{label:function(c){return c.dataset.label+': '+Math.round(c.parsed.x).toLocaleString()}}}}}});

// Scoring Convergence
if(SCORING.iterations.length>0){
  new Chart(document.getElementById('scoreConvergence'),{type:'line',
    data:{labels:SCORING.iterations,datasets:[
      {label:'Best',data:SCORING.best,borderColor:'#10b981',backgroundColor:'rgba(16,185,129,.1)',fill:true,tension:.3,pointRadius:0,borderWidth:2},
      {label:'Avg',data:SCORING.avg,borderColor:'#38bdf8',backgroundColor:'rgba(56,189,248,.08)',fill:true,tension:.3,pointRadius:0,borderWidth:1.5},
      {label:'Executed',data:SCORING.executed,borderColor:'#f59e0b',backgroundColor:'transparent',tension:.3,pointRadius:0,borderWidth:1.5,borderDash:[4,2]},
      {label:'Worst',data:SCORING.worst,borderColor:'#ef4444',backgroundColor:'rgba(239,68,68,.06)',fill:true,tension:.3,pointRadius:0,borderWidth:1,borderDash:[2,2]}
    ]},
    options:{responsive:true,interaction:{mode:'index',intersect:false},
      scales:{x:{title:{display:true,text:'Iteration',color:'#94a3b8',font:{size:9}},grid:{color:'rgba(148,163,184,.06)'},ticks:{maxTicksLimit:20,font:{size:8}}},
        y:{title:{display:true,text:'Score',color:'#94a3b8',font:{size:9}},grid:{color:'rgba(148,163,184,.06)'}}},
      plugins:{legend:lgOpt,tooltip:{callbacks:{label:function(c){return c.dataset.label+': '+c.parsed.y.toFixed(1)}}}}}});
}else{
  var ctx=document.getElementById('scoreConvergence');
  if(ctx){ctx.parentElement.innerHTML+='<div style=\"text-align:center;color:var(--dim);padding:40px;font-style:italic\">No carrier_scores.txt found</div>';}
}

// Scoring Component Weights (Doughnut)
(function(){
  var comps=['Distance','Time','Fixed','Activity','Overtime','TW Penalty'];
  var totals=[0,0,0,0,0,0];
  COSTS.distance.forEach(function(v,i){totals[0]+=Math.abs(v);totals[1]+=Math.abs(COSTS.time[i]);totals[2]+=Math.abs(COSTS.fix[i]);totals[3]+=Math.abs(COSTS.activity[i]);totals[4]+=Math.abs(COSTS.overtime[i]);totals[5]+=Math.abs(COSTS.twPenalty[i])});
  var cols=['rgba(59,130,246,.7)','rgba(16,185,129,.7)','rgba(245,158,11,.7)','rgba(129,140,248,.7)','rgba(248,113,113,.7)','rgba(236,72,153,.7)'];
  new Chart(document.getElementById('scoringWeights'),{type:'doughnut',
    data:{labels:comps,datasets:[{data:totals.map(function(v){return Math.round(v)}),backgroundColor:cols,borderWidth:0,hoverOffset:8}]},
    options:{responsive:true,cutout:'55%%',
      plugins:{legend:{position:'bottom',labels:{padding:10,usePointStyle:true,pointStyleWidth:10,font:{size:10}}},
        tooltip:{callbacks:{label:function(c){var sum=c.dataset.data.reduce(function(a,b){return a+b},0);return c.label+': '+c.parsed.toLocaleString()+' ('+(sum>0?(c.parsed/sum*100).toFixed(1):0)+'%%)';}}}}}});
})();

// Final Iteration Score Distribution  
(function(){
  var last=SCORING.iterations.length-1;
  if(last<0)return;
  var labels=['Best','Avg','Executed','Worst'];
  var vals=[SCORING.best[last],SCORING.avg[last],SCORING.executed[last],SCORING.worst[last]];
  var cols=['rgba(16,185,129,.7)','rgba(56,189,248,.7)','rgba(245,158,11,.7)','rgba(239,68,68,.7)'];
  new Chart(document.getElementById('scoreDistChart'),{type:'bar',
    data:{labels:labels,datasets:[{label:'Score @ iter '+SCORING.iterations[last],data:vals,backgroundColor:cols,borderRadius:6}]},
    options:{responsive:true,indexAxis:'y',
      scales:{x:{grid:{color:'rgba(148,163,184,.08)'},title:{display:true,text:'Score (optimization utility)',color:'#94a3b8',font:{size:9}}},y:{grid:{display:false}}},
      plugins:{legend:{display:false},tooltip:{callbacks:{label:function(c){return c.label+': '+c.parsed.x.toFixed(1)}}}}}});
})();

// === HOURLY SERVICE ACTIVITY BY PROVIDER ===
(function(){
  if(!HOURLY_SVC||!HOURLY_SVC.providers||HOURLY_SVC.providers.length===0)return;
  var datasets=[];
  var twIdx=HOURLY_SVC.twEndHour-4; // index of TW end hour in hours array (starts at 04:00)
  var twLabel=HOURLY_SVC.twEndLabel||(''+HOURLY_SVC.twEndHour+':00');
  HOURLY_SVC.providers.forEach(function(p){
    var col=PROV_COLORS[p]||'#6b7280';
    // Total services (stacked bar)
    datasets.push({label:p,data:HOURLY_SVC.services[p],backgroundColor:col+'88',stack:'svc',
      borderColor:col,borderWidth:1,borderRadius:2});
  });
  // TW-late overlay: sum all late across providers per hour
  var lateTotal=HOURLY_SVC.hours.map(function(_,i){
    var sum=0;HOURLY_SVC.providers.forEach(function(p){sum+=HOURLY_SVC.late[p][i]});return sum;
  });
  datasets.push({label:'TW Late (after '+twLabel+')',data:lateTotal,type:'line',
    borderColor:'#ef4444',backgroundColor:'rgba(239,68,68,.15)',fill:true,
    tension:.3,pointRadius:2,pointBackgroundColor:'#ef4444',borderWidth:2,yAxisID:'y'});
  new Chart(document.getElementById('hourlySvcChart'),{type:'bar',
    data:{labels:HOURLY_SVC.hours,datasets:datasets},
    options:{responsive:true,interaction:{mode:'index',intersect:false},
      scales:{
        x:{stacked:true,grid:{color:'rgba(148,163,184,.08)'},ticks:{font:{size:10}}},
        y:{stacked:true,grid:{color:'rgba(148,163,184,.08)'},title:{display:true,text:'Services',color:'#94a3b8',font:{size:9}}}
      },
      plugins:{
        legend:{position:'bottom',labels:{padding:8,usePointStyle:true,pointStyleWidth:10,font:{size:10}}},
        tooltip:{callbacks:{label:function(c){return c.dataset.label+': '+c.parsed.y}}},
        annotation:undefined
      }
    },
    plugins:[{id:'twLine',afterDraw:function(chart){
      var xScale=chart.scales.x;
      var yScale=chart.scales.y;
      if(twIdx>=0&&twIdx<xScale.ticks.length){
        var x=xScale.getPixelForValue(twIdx);
        var ctx=chart.ctx;
        ctx.save();
        ctx.strokeStyle='#ef4444';ctx.lineWidth=2;ctx.setLineDash([6,3]);
        ctx.beginPath();ctx.moveTo(x,yScale.top);ctx.lineTo(x,yScale.bottom);ctx.stroke();
        ctx.fillStyle='#ef4444';ctx.font='bold 10px sans-serif';ctx.textAlign='center';
        ctx.fillText('TW End '+twLabel,x,yScale.top-6);
        ctx.restore();
      }
    }}]
  });
})();

""";
    private static final String HTML_PART3 = """
// === ACTIVE VEHICLES OVER TIME ===
(function(){
  if(typeof ACTIVE_VEH==='undefined'||!ACTIVE_VEH.minutes)return;
  var ds=[];
  ACTIVE_VEH.providers.forEach(function(p){
    ds.push({label:p,data:ACTIVE_VEH.active[p],
      borderColor:PROV_COLORS[p]||'#6b7280',backgroundColor:(PROV_COLORS[p]||'#6b7280')+'22',
      fill:false,tension:.3,pointRadius:0,borderWidth:2});
  });
  // total line
  var total=ACTIVE_VEH.minutes.map(function(_,i){
    var s=0;ACTIVE_VEH.providers.forEach(function(p){s+=ACTIVE_VEH.active[p][i]});return s;
  });
  ds.push({label:'Total',data:total,borderColor:'#f8fafc',backgroundColor:'rgba(248,250,252,.10)',
    fill:true,tension:.3,pointRadius:0,borderWidth:2,borderDash:[6,3]});
  new Chart(document.getElementById('activeVehChart'),{type:'line',
    data:{labels:ACTIVE_VEH.minutes,datasets:ds},
    options:{responsive:true,interaction:{mode:'index',intersect:false},
      scales:{
        x:{grid:{color:'rgba(148,163,184,.08)'},ticks:{font:{size:9},autoSkip:false,maxRotation:0,
          callback:function(v,i){var l=ACTIVE_VEH.minutes[i];return l&&l.endsWith(':00')?l:null}}},
        y:{beginAtZero:true,grid:{color:'rgba(148,163,184,.08)'},
          title:{display:true,text:'Active Vehicles',color:'#94a3b8',font:{size:9}}}
      },
      plugins:{
        legend:{position:'bottom',labels:{padding:8,usePointStyle:true,pointStyleWidth:10,font:{size:10}}},
        tooltip:{callbacks:{label:function(c){return c.dataset.label+': '+c.parsed.y+' vehicles'}}}
      }
    }
  });
})();

// === DEPOT DEPARTURES & ARRIVALS ===
(function(){
  if(typeof DEPOT_TRIPS==='undefined'||!DEPOT_TRIPS.minutes)return;
  var ds=[];
  // Departure bars (positive, stacked)
  DEPOT_TRIPS.providers.forEach(function(p){
    var col=PROV_COLORS[p]||'#6b7280';
    ds.push({label:p+' departures',data:DEPOT_TRIPS.departures[p],
      backgroundColor:col+'88',borderColor:col,borderWidth:1,borderRadius:2,
      stack:'dep',yAxisID:'y'});
  });
  // Arrival bars (negative, stacked)
  DEPOT_TRIPS.providers.forEach(function(p){
    var col=PROV_COLORS[p]||'#6b7280';
    ds.push({label:p+' arrivals',data:DEPOT_TRIPS.arrivals[p].map(function(v){return -v}),
      backgroundColor:col+'44',borderColor:col,borderWidth:1,borderRadius:2,
      stack:'arr',yAxisID:'y'});
  });
  new Chart(document.getElementById('depotTripsChart'),{type:'bar',
    data:{labels:DEPOT_TRIPS.minutes,datasets:ds},
    options:{responsive:true,interaction:{mode:'index',intersect:false},
      scales:{
        x:{stacked:true,grid:{color:'rgba(148,163,184,.08)'},ticks:{font:{size:9},autoSkip:false,maxRotation:0,
          callback:function(v,i){var l=DEPOT_TRIPS.minutes[i];return l&&l.endsWith(':00')?l:null}}},
        y:{stacked:true,grid:{color:'rgba(148,163,184,.08)'},
          title:{display:true,text:'Departures \u2191 / Arrivals \u2193',color:'#94a3b8',font:{size:9}},
          ticks:{callback:function(v){return Math.abs(v)}}}
      },
      plugins:{
        legend:{position:'bottom',labels:{padding:8,usePointStyle:true,pointStyleWidth:10,font:{size:10},
          filter:function(item){return item.text.indexOf('departures')>=0}}},
        tooltip:{callbacks:{label:function(c){var v=c.parsed.y;var abs=Math.abs(v);
          return c.dataset.label+': '+abs}}}
      }
    }
  });
})();

// === GLOBAL CHART PROVIDER TOGGLE ===
(function(){
  var chartProvState={};
  PROV_LIST.forEach(function(p){chartProvState[p]=true});
  // IDs of canvases with per-provider DATASETS (label = provider name or contains it)
  var dsChartIds=['hourlySvcChart','activeVehChart','depotTripsChart','scatterUtilDist','scatterParcelsDur'];
  // IDs of canvases with providers on AXIS (single dataset, labels = provider names)
  var axChartIds=['dropDensityChart','costPerParcelChart','stopsPerHourChart','parcelsPerKmChart','timeSplitChart','tourProfileChart'];
  // Backup original data for axis charts (data + colors)
  var axBackup={};
  function backupAxData(){
    axChartIds.forEach(function(id){
      var el=document.getElementById(id);if(!el)return;
      var ch=Chart.getChart(el);if(!ch)return;
      axBackup[id]={labels:ch.data.labels.slice(),datasets:ch.data.datasets.map(function(ds){
        return {data:ds.data.slice(),bg:Array.isArray(ds.backgroundColor)?ds.backgroundColor.slice():null};
      })};
    });
  }
  // delay backup until charts are rendered
  setTimeout(backupAxData,500);

  function applyProvFilter(){
    // Dataset-based charts: toggle hidden by matching label
    dsChartIds.forEach(function(id){
      var el=document.getElementById(id);if(!el)return;
      var ch=Chart.getChart(el);if(!ch)return;
      ch.data.datasets.forEach(function(ds){
        // skip utility lines (Total, TW Late)
        if(ds.label==='Total'||ds.label.indexOf('TW Late')===0)return;
        // match provider: label might be "dhl" or "dhl departures" or "dhl arrivals"
        var found=false;
        PROV_LIST.forEach(function(p){
          if(ds.label===p||ds.label.indexOf(p+' ')===0){
            ds.hidden=!chartProvState[p];found=true;
          }
        });
      });
      ch.update('none');
    });
    // Axis-based charts: filter out hidden providers, restore visible ones
    axChartIds.forEach(function(id){
      var el=document.getElementById(id);if(!el)return;
      var ch=Chart.getChart(el);if(!ch)return;
      var bk=axBackup[id];if(!bk)return;
      var newLabels=[];var newDatasets=bk.datasets.map(function(){return{data:[],bg:[]}});
      bk.labels.forEach(function(lbl,i){
        if(chartProvState[lbl]!==false){
          newLabels.push(lbl);
          bk.datasets.forEach(function(orig,di){
            newDatasets[di].data.push(orig.data[i]);
            if(orig.bg)newDatasets[di].bg.push(orig.bg[i]);
          });
        }
      });
      ch.data.labels=newLabels;
      ch.data.datasets.forEach(function(ds,di){
        ds.data=newDatasets[di].data;
        if(newDatasets[di].bg.length>0)ds.backgroundColor=newDatasets[di].bg;
      });
      ch.update('none');
    });
  }

  // Build checkboxes
  var cbWrap=document.getElementById('chartProvCbs');
  PROV_LIST.forEach(function(p){
    var lbl=document.createElement('label');
    lbl.style.cssText='font-size:.72rem;color:var(--text);cursor:pointer;display:flex;align-items:center;gap:3px';
    var cb=document.createElement('input');cb.type='checkbox';cb.checked=true;
    cb.style.accentColor=PROV_COLORS[p]||'var(--accent)';
    cb.onchange=function(){chartProvState[p]=cb.checked;applyProvFilter()};
    var dot=document.createElement('span');
    dot.style.cssText='display:inline-block;width:8px;height:8px;border-radius:50%%;background:'+PROV_COLORS[p];
    lbl.appendChild(cb);lbl.appendChild(dot);
    lbl.appendChild(document.createTextNode(' '+p));
    cbWrap.appendChild(lbl);
  });
  // All toggle
  document.getElementById('chartProvAll').onchange=function(){
    var checked=this.checked;
    PROV_LIST.forEach(function(p){chartProvState[p]=checked});
    cbWrap.querySelectorAll('input[type=checkbox]').forEach(function(cb){cb.checked=checked});
    applyProvFilter();
  };
})();

// === CARRIER SCORING EXPLORER ===
(function(){
  if(!CARRIER_DETAIL||!CARRIER_DETAIL.length)return;
  var data=CARRIER_DETAIL;
  var tbody=document.getElementById('cseTbody');
  var provSel=document.getElementById('cseProvFilter');
  var searchBox=document.getElementById('cseSearch');
  var sortSel=document.getElementById('cseSortBy');
  var countEl=document.getElementById('cseCount');

  // populate provider dropdown
  var provs=[]; data.forEach(function(c){if(provs.indexOf(c.prov)<0)provs.push(c.prov)});
  provs.sort().forEach(function(p){var o=document.createElement('option');o.value=p;o.textContent=p;provSel.appendChild(o)});

  function fmtSec(s){var h=Math.floor(s/3600);var m=Math.floor((s%%3600)/60);return (h<10?'0':'')+h+':'+(m<10?'0':'')+m}
  function fmtN(v){return Math.round(v).toLocaleString('de-DE')}
  function fmtD(v){return v.toFixed(1)}

  // expanded state
  var expanded={};

  function render(){
    var prov=provSel.value;
    var q=searchBox.value.toLowerCase().trim();
    var sort=sortSel.value.split('-');var sortKey=sort[0];var sortDir=sort[1]==='desc'?-1:1;
    var filtered=data.filter(function(c){
      if(prov&&c.prov!==prov)return false;
      if(q&&c.id.toLowerCase().indexOf(q)<0&&c.plz.indexOf(q)<0&&c.prov.toLowerCase().indexOf(q)<0)return false;
      return true;
    });
    filtered.sort(function(a,b){
      var av,bv;
      if(sortKey==='costTW'){av=a.costTW;bv=b.costTW}
      else if(sortKey==='costTotal'){av=a.costTotal;bv=b.costTotal}
      else if(sortKey==='parcels'){av=a.parcels;bv=b.parcels}
      else if(sortKey==='missed'){av=a.missed;bv=b.missed}
      else{av=a.id;bv=b.id;return sortDir*(av<bv?-1:av>bv?1:0)}
      return sortDir*(av-bv);
    });
    countEl.textContent=filtered.length+' of '+data.length+' carriers';

    var html='';
    filtered.forEach(function(c){
      var col=PROV_COLORS[c.prov]||'#6b7280';
      var twBg=c.costTW>100?'rgba(236,72,153,.18)':c.costTW>0?'rgba(236,72,153,.08)':'transparent';
      var isExp=expanded[c.id];
      html+='<tr class="cse-row" data-id="'+c.id+'" style="border-bottom:1px solid rgba(148,163,184,.06);cursor:pointer;background:'+twBg+'">';
      html+='<td style="padding:5px 6px;color:var(--dim);font-size:.7rem">'+(isExp?'\u25BC':'\u25B6')+'</td>';
      html+='<td style="padding:5px 6px;font-weight:600;white-space:nowrap">'+c.id+'</td>';
      html+='<td style="padding:5px 6px"><span style="display:inline-block;width:8px;height:8px;border-radius:50%%;background:'+col+';margin-right:4px;vertical-align:middle"></span>'+c.prov+'</td>';
      html+='<td style="padding:5px 6px;color:var(--dim)">'+c.plz+'</td>';
      html+='<td style="padding:5px 6px;text-align:right">'+c.svc+'</td>';
      html+='<td style="padding:5px 6px;text-align:right;font-weight:600">'+fmtN(c.parcels)+'</td>';
      html+='<td style="padding:5px 6px;text-align:right;color:'+(c.missed>0?'#f87171':'var(--dim)')+'">'+c.missed+'</td>';
      html+='<td style="padding:5px 6px;text-align:right">'+c.tours+'</td>';
      html+='<td style="padding:5px 6px;text-align:right">'+fmtD(c.distKm)+'</td>';
      html+='<td style="padding:5px 6px;text-align:right">'+fmtD(c.costDist)+'</td>';
      html+='<td style="padding:5px 6px;text-align:right">'+fmtD(c.costTime)+'</td>';
      html+='<td style="padding:5px 6px;text-align:right">'+fmtD(c.costFix)+'</td>';
      html+='<td style="padding:5px 6px;text-align:right">'+fmtD(c.costAct)+'</td>';
      html+='<td style="padding:5px 6px;text-align:right;color:'+(c.costOT>0?'#fbbf24':'var(--dim)')+'">'+fmtD(c.costOT)+'</td>';
      html+='<td style="padding:5px 6px;text-align:right;font-weight:700;color:'+(c.costTW>0?'#ec4899':'var(--dim)')+'">'+fmtN(c.costTW)+'</td>';
      html+='<td style="padding:5px 6px;text-align:right;font-weight:700">'+fmtN(c.costTotal)+'</td>';
      html+='<td style="padding:5px 6px;text-align:right;color:'+(c.successRate<95?'#f87171':c.successRate<100?'#fbbf24':'#34d399')+'">'+fmtD(c.successRate)+'%%</td>';
      html+='</tr>';
      // expanded detail row
      if(isExp){
        html+='<tr style="background:rgba(148,163,184,.04)"><td colspan="17" style="padding:8px 16px 12px">';
        // vehicle fleet + cost mini-bar
        html+='<div style="display:flex;gap:24px;flex-wrap:wrap">';
        // Vehicle Fleet
        html+='<div style="flex:1;min-width:240px"><div style="font-size:.72rem;font-weight:700;color:var(--accent);text-transform:uppercase;letter-spacing:.5px;margin-bottom:6px">\u25A3 Vehicle Fleet</div>';
        html+='<table style="width:100%%;font-size:.72rem;border-collapse:collapse">';
        html+='<tr style="border-bottom:1px solid rgba(148,163,184,.12)"><th style="text-align:left;padding:3px 6px">ID</th><th style="text-align:left;padding:3px 6px">Type</th><th style="text-align:right;padding:3px 6px">Cap</th><th style="text-align:right;padding:3px 6px">Fix\u20ac</th><th style="text-align:center;padding:3px 6px">Time Window</th></tr>';
        c.vehicles.forEach(function(v){
          var twStr=fmtSec(v.twStart)+' \u2013 '+fmtSec(v.twEnd);
          var twDurH=((v.twEnd-v.twStart)/3600).toFixed(1);
          html+='<tr style="border-bottom:1px solid rgba(148,163,184,.05)"><td style="padding:3px 6px;color:var(--text)">'+v.id+'</td>';
          html+='<td style="padding:3px 6px;color:var(--dim)">'+v.type.replace('ct_','')+'</td>';
          html+='<td style="padding:3px 6px;text-align:right">'+v.cap+'</td>';
          html+='<td style="padding:3px 6px;text-align:right">'+fmtD(v.fix)+'</td>';
          html+='<td style="padding:3px 6px;text-align:center"><span style="font-size:.68rem;background:rgba(99,102,241,.15);padding:1px 6px;border-radius:4px">'+twStr+' ('+twDurH+'h)</span></td></tr>';
        });
        html+='</table></div>';
        // Tour Details
        html+='<div style="flex:1;min-width:240px"><div style="font-size:.72rem;font-weight:700;color:var(--accent);text-transform:uppercase;letter-spacing:.5px;margin-bottom:6px">\u25B7 Tour Plan</div>';
        html+='<table style="width:100%%;font-size:.72rem;border-collapse:collapse">';
        html+='<tr style="border-bottom:1px solid rgba(148,163,184,.12)"><th style="text-align:left;padding:3px 6px">Vehicle</th><th style="text-align:left;padding:3px 6px">Type</th><th style="text-align:right;padding:3px 6px">Stops</th><th style="text-align:right;padding:3px 6px">Parcels</th><th style="text-align:right;padding:3px 6px">Dep</th><th style="text-align:right;padding:3px 6px">Arr</th></tr>';
        c.tourDetails.forEach(function(t){
          html+='<tr style="border-bottom:1px solid rgba(148,163,184,.05)">';
          html+='<td style="padding:3px 6px;color:var(--text)">'+t.vid+'</td>';
          html+='<td style="padding:3px 6px;color:var(--dim)">'+t.vtype.replace('ct_','')+'</td>';
          html+='<td style="padding:3px 6px;text-align:right">'+t.stops+'</td>';
          html+='<td style="padding:3px 6px;text-align:right;font-weight:600">'+t.parcels+'</td>';
          html+='<td style="padding:3px 6px;text-align:right">'+fmtSec(t.depSec)+'</td>';
          html+='<td style="padding:3px 6px;text-align:right">'+fmtSec(t.arrSec)+'</td></tr>';
        });
        html+='</table></div>';
        // Cost Breakdown mini bar chart
        var costs=[{l:'Distance',v:c.costDist,bg:'rgba(59,130,246,.6)'},{l:'Time',v:c.costTime,bg:'rgba(16,185,129,.6)'},{l:'Fixed',v:c.costFix,bg:'rgba(245,158,11,.6)'},{l:'Activity',v:c.costAct,bg:'rgba(129,140,248,.6)'},{l:'Overtime',v:c.costOT,bg:'rgba(248,113,113,.6)'},{l:'TW Penalty',v:c.costTW,bg:'rgba(236,72,153,.7)'}];
        var maxCost=Math.max.apply(null,costs.map(function(x){return x.v}));
        if(maxCost<=0)maxCost=1;
        html+='<div style="flex:0 0 200px;min-width:180px"><div style="font-size:.72rem;font-weight:700;color:var(--accent);text-transform:uppercase;letter-spacing:.5px;margin-bottom:6px">\u25C9 Score Split</div>';
        costs.forEach(function(x){
          var pct=Math.min(100,(x.v/maxCost)*100);
          html+='<div style="display:flex;align-items:center;gap:6px;margin-bottom:3px"><span style="min-width:52px;font-size:.68rem;text-align:right;color:var(--dim)">'+x.l+'</span>';
          html+='<div style="flex:1;height:14px;background:rgba(148,163,184,.07);border-radius:3px;overflow:hidden"><div style="height:100%%;width:'+pct+'%%;background:'+x.bg+';border-radius:3px"></div></div>';
          html+='<span style="min-width:48px;font-size:.68rem;text-align:right;color:var(--text)">'+fmtD(x.v)+'</span></div>';
        });
        html+='</div>';
        html+='</div></td></tr>';
      }
    });
    tbody.innerHTML=html;
    // click handlers for expand/collapse
    tbody.querySelectorAll('.cse-row').forEach(function(row){
      row.onclick=function(){var id=row.getAttribute('data-id');expanded[id]=!expanded[id];render()};
    });
  }
  provSel.onchange=render;searchBox.oninput=render;sortSel.onchange=render;
  render();
})();

// === OPERATIONAL OVERVIEW CHARTS ===
(function(){
  var byProv={};
  VEHS.forEach(function(v){if(!byProv[v.provider])byProv[v.provider]=[];byProv[v.provider].push(v)});

  // Scatter: Utilisation vs Tour Distance
  var ds1=[];
  Object.keys(byProv).forEach(function(p){
    ds1.push({label:p,data:byProv[p].map(function(v){return{x:v.km,y:v.loadFactor*100}}),
      backgroundColor:(PROV_COLORS[p]||'#6b7280')+'88',pointRadius:2.5,pointHoverRadius:5});
  });
  new Chart(document.getElementById('scatterUtilDist'),{type:'scatter',data:{datasets:ds1},
    options:{responsive:true,
      scales:{x:{title:{display:true,text:'Tour Distance (km)',color:'#94a3b8',font:{size:10}},grid:{color:'rgba(148,163,184,.08)'}},
              y:{title:{display:true,text:'Utilisation (%%)',color:'#94a3b8',font:{size:10}},min:0,max:105,grid:{color:'rgba(148,163,184,.08)'}}},
      plugins:{legend:lgOpt,tooltip:{callbacks:{label:function(c){return c.dataset.label+': '+c.parsed.x.toFixed(1)+' km, '+c.parsed.y.toFixed(1)+'%%'}}}}}});

  // Scatter: Parcels vs Duration
  var ds2=[];
  Object.keys(byProv).forEach(function(p){
    ds2.push({label:p,data:byProv[p].map(function(v){return{x:v.durH,y:v.parcels}}),
      backgroundColor:(PROV_COLORS[p]||'#6b7280')+'88',pointRadius:2.5,pointHoverRadius:5});
  });
  new Chart(document.getElementById('scatterParcelsDur'),{type:'scatter',data:{datasets:ds2},
    options:{responsive:true,
      scales:{x:{title:{display:true,text:'Tour Duration (h)',color:'#94a3b8',font:{size:10}},grid:{color:'rgba(148,163,184,.08)'}},
              y:{title:{display:true,text:'Parcels Delivered',color:'#94a3b8',font:{size:10}},beginAtZero:true,grid:{color:'rgba(148,163,184,.08)'}}},
      plugins:{legend:lgOpt,tooltip:{callbacks:{label:function(c){return c.dataset.label+': '+c.parsed.x.toFixed(1)+'h, '+c.parsed.y+' pcs'}}}}}});

  // Drop Density (Stops/km) by Provider
  var ddLabels=ROUT_EFF.map(function(r){return r.provider});
  var ddData=ROUT_EFF.map(function(r){return r.avgKm>0?+(r.avgStops/r.avgKm).toFixed(2):0});
  var ddColors=ddLabels.map(function(p){return(PROV_COLORS[p]||'#6b7280')+'99'});
  new Chart(document.getElementById('dropDensityChart'),{type:'bar',
    data:{labels:ddLabels,datasets:[{label:'Stops/km',data:ddData,backgroundColor:ddColors,borderRadius:4}]},
    options:{responsive:true,indexAxis:'y',
      scales:{x:{grid:{color:'rgba(148,163,184,.08)'},title:{display:true,text:'stops per km',color:'#94a3b8',font:{size:9}}},y:{grid:{display:false}}},
      plugins:{legend:{display:false}}}});

  // Cost per Parcel by Provider
  var cppLabels=SUMMARY.map(function(r){return r.provider});
  var cppData=SUMMARY.map(function(r){return r.costPerParcel});
  var cppColors=cppLabels.map(function(p){return(PROV_COLORS[p]||'#6b7280')+'99'});
  new Chart(document.getElementById('costPerParcelChart'),{type:'bar',
    data:{labels:cppLabels,datasets:[{label:'\\u20AC/parcel',data:cppData,backgroundColor:cppColors,borderRadius:4}]},
    options:{responsive:true,indexAxis:'y',
      scales:{x:{grid:{color:'rgba(148,163,184,.08)'},title:{display:true,text:'\\u20AC per parcel',color:'#94a3b8',font:{size:9}}},y:{grid:{display:false}}},
      plugins:{legend:{display:false}}}});
})();
</script>
%33$s
</body>
</html>
""";
}
