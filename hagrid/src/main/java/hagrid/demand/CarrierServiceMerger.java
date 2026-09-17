package hagrid.demand;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;

import hagrid.HagridConfig;
import hagrid.demand.CarrierMergeValidator.CarrierMergeStats;
import hagrid.utils.demand.Delivery.ParcelType;
import hagrid.utils.general.HAGRIDUtils;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.utils.geometry.CoordUtils;
import org.matsim.freight.carriers.*;

import java.util.*;
import java.util.stream.Collectors;

public class CarrierServiceMerger implements Runnable {

    @Inject
    private HagridConfig hagridConfig;

    @Inject
    private Scenario scenario;

    private boolean fullMerge;

    private static final Logger LOGGER = LogManager.getLogger(CarrierServiceMerger.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * One capacity-bounded sub-stop produced when a merged group's pooled demand exceeds what a
     * single van can carry. {@link #capacity()} never exceeds {@code segCap}; {@link #duration()} is
     * the group duration apportioned proportionally to this segment's capacity; {@link #b2b()}/
     * {@link #b2c()} split the group's B2B/B2C parcel counts so both totals are preserved exactly.
     */
    public record MergeSegment(int capacity, double duration, int b2b, int b2c) {}

    /**
     * Splits a merged service group into capacity-bounded sub-stops. A merged stop must fit one van
     * (jsprit may not split a service; the INFINITE fleet only clones identical vans), so a group of
     * {@code totalCapacity} parcels is broken into {@code ceil(totalCapacity / segCap)} segments of at
     * most {@code segCap} parcels each — the last one carrying the remainder.
     * <p>
     * When {@code segCap >= totalCapacity} (e.g. high-capacity runs, where segCap = demandBorder) this
     * returns a single segment carrying the full group, i.e. the previous single-merged-service
     * behaviour unchanged. Duration is apportioned proportionally to capacity; B2B parcels fill the
     * earliest segments, then B2C, so every segment's {@code b2b + b2c == capacity} and both totals
     * are preserved. Deterministic (pure integer arithmetic).
     *
     * @param totalCapacity pooled parcel demand of the group (&gt; 0)
     * @param segCap        max parcels per sub-stop = min(demandBorder, minVehicleCapacity); &lt;= 0 means "no split"
     * @param totalDuration pooled service duration of the group (incl. any travel-time bonus)
     * @param b2b           pooled B2B parcel count ({@code b2b + b2c == totalCapacity})
     * @param b2c           pooled B2C parcel count
     */
    static List<MergeSegment> splitMergeGroup(int totalCapacity, int segCap, double totalDuration, int b2b, int b2c) {
        List<MergeSegment> segments = new ArrayList<>();
        if (segCap <= 0 || totalCapacity <= segCap) {
            segments.add(new MergeSegment(totalCapacity, totalDuration, b2b, b2c));
            return segments;
        }
        int numSegments = (int) Math.ceil((double) totalCapacity / segCap);
        int remainingB2b = b2b;
        int assigned = 0;
        for (int i = 0; i < numSegments; i++) {
            int cap = (i < numSegments - 1) ? segCap : (totalCapacity - assigned);
            assigned += cap;
            int segB2b = Math.min(cap, remainingB2b);
            remainingB2b -= segB2b;
            int segB2c = cap - segB2b;
            double dur = totalDuration * ((double) cap / totalCapacity);
            segments.add(new MergeSegment(cap, dur, segB2b, segB2c));
        }
        return segments;
    }

    /**
     * Speed and acceleration configuration used for realistic travel time
     * calculation between service coordinates.
     *
     * <p>
     * These parameters model simple kinematic behavior for two transport modes:
     * cargo bikes and delivery vans.
     * They are used inside the {@code computeRealisticTravelTime()} method to
     * estimate driving times based on
     * acceleration and braking, allowing the service duration of merged services to
     * reflect more realistic movement profiles.
     *
     * <ul>
     * <li>{@code ACC_CARGOBIKE}: Assumed acceleration for cargo bikes [m/s²]</li>
     * <li>{@code ACC_VAN}: Assumed acceleration for vans [m/s²]</li>
     * <li>{@code VMAX_CARGOBIKE}: Maximum velocity for cargo bikes, realistically
     * limited to ~25 km/h [m/s]</li>
     * <li>{@code VMAX_VAN}: Maximum velocity for vans, capped at ~150 km/h to
     * reflect rural and interurban conditions [m/s]</li>
     * </ul>
     *
     * <p>
     * Note: The actual speed is further limited by the MATSim
     * {@link org.matsim.api.core.v01.network.Link#getFreespeed()}
     * value at runtime, ensuring consistency with the road network constraints.
     */

    private static final double ACC_CARGOBIKE = 1.0; // m/s²
    private static final double ACC_VAN = 1.2;
    private static final double VMAX_CARGOBIKE = 6.94; // m/s
    private static final double VMAX_VAN = 41.66;

    private record MergeKey(Id<Link> linkId, Coord coord, String carrierMode, Set<String> skillSet) {

        public static MergeKey byLink(Id<Link> linkId, String carrierMode, Set<String> skillSet) {
            return new MergeKey(linkId, null, carrierMode, skillSet);
        }

        public static MergeKey byCoord(Coord coord, String carrierMode, Set<String> skillSet) {
            return new MergeKey(null, coord, carrierMode, skillSet);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (!(o instanceof MergeKey other))
                return false;
            return Objects.equals(linkId, other.linkId)
                    && Objects.equals(carrierMode, other.carrierMode)
                    && Objects.equals(skillSet, other.skillSet)
                    && Objects.equals(coord == null ? null : coord.getX(),
                            other.coord == null ? null : other.coord.getX())
                    && Objects.equals(coord == null ? null : coord.getY(),
                            other.coord == null ? null : other.coord.getY());
        }

        @Override
        public int hashCode() {
            return Objects.hash(linkId,
                    coord == null ? null : coord.getX(),
                    coord == null ? null : coord.getY(),
                    carrierMode,
                    skillSet);
        }
    }

    /**
     * Executes the carrier service merging procedure.
     * 
     * <p>
     * This method performs the following steps:
     * <ul>
     * <li>Retrieves the carrier definitions from the scenario.</li>
     * <li>Logs and captures pre-merge statistics (e.g., parcel counts,
     * capacities).</li>
     * <li>Performs merging of individual services into aggregated services, grouped
     * by link and attributes.</li>
     * <li>Validates the results of the merge by checking parcel and capacity
     * consistency.</li>
     * <li>Logs summary information for further inspection and debugging.</li>
     * </ul>
     * 
     * <p>
     * All steps are logged with INFO level for transparency during batch runs.
     * 
     * <p>
     * This method is intended to be called via pipeline setup using Guice
     * dependency injection.
     */
    @Override
    public void run() {
        // Retrieve the full carrier data structure from the scenario configuration
        // (provided by MATSim)
        Carriers carriers = HAGRIDUtils.getScenarioElementAs("carriers", scenario);

        // Log start of service merging process
        LOGGER.info("Starting service merging...");

        // Capture pre-merge statistics (number of services, B2B/B2C counts, capacity)
        CarrierMergeStats preStats = CarrierMergeValidator.capturePreMergeStats(carriers);

        // Perform the actual service merging logic: services with same link,
        // carrierMode & skills
        if (fullMerge) {
            LOGGER.info("Full merge of services per link and attributes.");
            mergeServices(carriers, scenario, hagridConfig, fullMerge);
        } else {
            LOGGER.info("B2B/B2C merge of services per Coordinate.");
            mergeServices(carriers, scenario, hagridConfig, fullMerge);
        }

        // Validate post-merge consistency (e.g. no lost parcels or mismatched capacity)
        // and print comparison summary
        CarrierMergeValidator.validatePostMerge(carriers, preStats, hagridConfig);

        // Final log statement for completed merge step
        LOGGER.info("Service merging completed.");
    }

    public static void mergeServices(Carriers carriers, Scenario scenario, HagridConfig hagridConfig,
            boolean fullMerge) {
        final Network subNetwork = HAGRIDUtils.getScenarioElementAs("parcelServiceNetwork", scenario);

        for (Carrier carrier : carriers.getCarriers().values()) {
            List<CarrierService> allServices = new ArrayList<>(carrier.getServices().values());

            // Gruppieren
            Map<MergeKey, List<CarrierService>> groups = new HashMap<>();
            for (CarrierService s : allServices) {
                String carrierMode = String.valueOf(s.getAttributes().getAttribute("carrierMode"));
                String skillsRaw = String.valueOf(s.getAttributes().getAttribute("skills"));
                Set<String> skillSet = new TreeSet<>(Arrays.asList(skillsRaw.split(",")));
                Coord coord = (Coord) s.getAttributes().getAttribute("coord");
                if (coord == null) {
                    throw new IllegalStateException("Service without coordinate: " + s.getId());
                }

                Id<Link> linkId = s.getServiceLinkId();
                MergeKey key = fullMerge
                        ? MergeKey.byLink(linkId, carrierMode, skillSet)
                        : MergeKey.byCoord(coord, carrierMode, skillSet);

                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(s);
            }

            int merged = 0, removed = 0;

            for (Map.Entry<MergeKey, List<CarrierService>> entry : groups.entrySet()) {
                List<CarrierService> services = entry.getValue();
                if (services.size() <= 1)
                    continue;

                MergeKey key = entry.getKey();
                // Link link = subNetwork.getLinks().get(key.linkId);
                // Link link = fullMerge ? null : subNetwork.getLinks().get(key.linkId);
                boolean isCargo = key.skillSet.contains("cargoBike");

                int totalCapacity = 0, b2b = 0, b2c = 0;
                double totalDuration = 0.0;

                List<Coord> coords = new ArrayList<>();
                Map<String, Object> mergedMeta = new LinkedHashMap<>();

                for (CarrierService s : services) {
                    totalCapacity += s.getCapacityDemand();
                    totalDuration += s.getServiceDuration();

                    ParcelType type = (ParcelType) s.getAttributes().getAttribute("type");
                    if (ParcelType.B2B == type)
                        b2b += s.getCapacityDemand();
                    if (ParcelType.B2C == type)
                        b2c += s.getCapacityDemand();

                    Coord coordObj = (Coord) s.getAttributes().getAttribute("coord");
                    if (coordObj instanceof Coord)
                        coords.add((Coord) coordObj);

                    Map<String, Object> singleMeta = new LinkedHashMap<>();

                    s.getAttributes().getAsMap().forEach((attrName, attrValue) -> {
                        if ("weights".equals(attrName)) {
                            // Split weights zur Liste
                            List<String> weightsList = Arrays.asList(String.valueOf(attrValue).split(";"));
                            singleMeta.put("weights", weightsList);
                        } else if ("coord".equals(attrName) && attrValue instanceof Coord coord) {
                            // Coord als [x, y]
                            singleMeta.put("coord", Arrays.asList(coord.getX(), coord.getY()));
                            coords.add(coord); // für Fahrtzeitberechnung
                        } else {
                            // alle anderen raw übernehmen
                            singleMeta.put(attrName, attrValue);
                        }
                    });

                    // Pflichtwerte (außerhalb attributes)
                    singleMeta.put("capacity", s.getCapacityDemand());
                    singleMeta.put("serviceDuration", s.getServiceDuration());

                    mergedMeta.put(s.getId().toString(), singleMeta);
                }

                // Validate capacity demand before service creation
                // Uses demand border (max services per carrier from config) as upper limit for merged services
                final int demandBorder = hagridConfig.getDemandBorder();
                if (totalCapacity > demandBorder) {
                    LOGGER.warn("Skipping merge group: total capacity {} exceeds demand border {} — keeping {} individual services. Carrier: {}, Link: {}",
                            totalCapacity, demandBorder, services.size(), carrier.getId(), key.linkId);
                    continue;
                }
                Id<Link> selectedLinkId = fullMerge
                        ? key.linkId
                        : services.get(0).getServiceLinkId();
                Link link = fullMerge
                        ? subNetwork.getLinks().get(key.linkId)
                        : null;

                double timeBonus = fullMerge ? computeRealisticTravelTime(coords, link, isCargo) : 0.0;

                double finalDuration = totalDuration + timeBonus;

                final double begin = hagridConfig.getDeliveryTimeWindowStart();
                final double end = hagridConfig.getDeliveryTimeWindowEnd();

                // Cap each merged stop at what a single van can carry. A service cannot be split
                // across vehicles, and the INFINITE fleet only clones identical vans, so a pooled
                // demand above the vehicle capacity would be structurally unroutable (left unassigned
                // by jsprit). segCap = min(demandBorder, minVehicleCapacity): at high capacities this
                // equals demandBorder -> one segment -> behaviour unchanged; at low capacities the
                // group is broken into ceil(total/segCap) sub-stops. See splitMergeGroup.
                final int segCap = Math.min(demandBorder, hagridConfig.getMinVehicleCapacity());
                List<MergeSegment> segments = splitMergeGroup(totalCapacity, segCap, finalDuration, b2b, b2c);

                String mergedMetaJson = null;
                try {
                    mergedMetaJson = objectMapper.writeValueAsString(mergedMeta);
                } catch (JsonProcessingException e) {
                    LOGGER.error("Failed to write merged service metadata", e);
                }

                int seg = 0;
                for (MergeSegment segment : segments) {
                    // Parcel type reflects this sub-stop's own B2B/B2C mix (a split may yield pure
                    // segments at the ends and MIXED at the B2B/B2C boundary).
                    ParcelType segType;
                    if (segment.b2b() > 0 && segment.b2c() > 0) {
                        segType = ParcelType.MIXED;
                    } else if (segment.b2b() > 0) {
                        segType = ParcelType.B2B;
                    } else if (segment.b2c() > 0) {
                        segType = ParcelType.B2C;
                    } else {
                        throw new IllegalStateException("Merged service segment has no parcel type (b2b=0, b2c=0)");
                    }

                    // Single-segment groups keep the original id shape (high-cap parity); split groups
                    // get a per-segment suffix so ids stay unique.
                    String newServiceId = segments.size() == 1
                            ? String.format("service_%s_%s_%d", "MIXED", carrier.getId().toString(), merged)
                            : String.format("service_%s_%s_%d_s%d", "MIXED", carrier.getId().toString(), merged, seg);

                    // Apportion the service/travel durations by capacity fraction (truncated, matching
                    // the previous (int) cast so single-segment output is byte-identical).
                    double segFraction = (double) segment.capacity() / totalCapacity;

                    CarrierService.Builder builder = CarrierService.Builder.newInstance(
                            Id.create(newServiceId, CarrierService.class), selectedLinkId);
                    builder.setCapacityDemand(segment.capacity());
                    builder.setServiceDuration(segment.duration());
                    builder.setServiceStartingTimeWindow(TimeWindow.newInstance(begin, end));

                    CarrierService mergedService = builder.build();
                    mergedService.getAttributes().putAttribute("carrierMode", key.carrierMode);
                    mergedService.getAttributes().putAttribute("skills", String.join(",", key.skillSet));
                    mergedService.getAttributes().putAttribute("b2b", segment.b2b());
                    mergedService.getAttributes().putAttribute("b2c", segment.b2c());
                    mergedService.getAttributes().putAttribute("serviceDuration", (int) (totalDuration * segFraction));
                    mergedService.getAttributes().putAttribute("travelDuration", (int) (timeBonus * segFraction));
                    mergedService.getAttributes().putAttribute("type", segType);
                    if (mergedMetaJson != null) {
                        mergedService.getAttributes().putAttribute("mergedMetadata", mergedMetaJson);
                    }

                    CarriersUtils.addService(carrier, mergedService);
                    seg++;
                }

                if (segments.size() > 1) {
                    LOGGER.debug("Carrier {}: split merged group of {} parcels into {} sub-stops (segCap={}). Link: {}",
                            carrier.getId(), totalCapacity, segments.size(), segCap, key.linkId);
                }

                // cleanup originals
                services.forEach(s -> carrier.getServices().remove(s.getId()));
                merged++;
                removed += services.size();
            }

            if (merged > 0) {
                LOGGER.debug("Carrier {}: Merged {} groups, removed {} original services",
                        carrier.getId(), merged, removed);
            }
        }

    }

    private static double computeRealisticTravelTime(List<Coord> coords, Link link, boolean isCargoBike) {
        if (coords.size() < 2)
            return 0.0;

        double a = isCargoBike ? ACC_CARGOBIKE : ACC_VAN;
        double vLimit = link.getFreespeed(); // [m/s]
        double vMax = isCargoBike ? Math.min(vLimit, VMAX_CARGOBIKE) : Math.min(vLimit, VMAX_VAN);

        double totalTime = 0.0;

        for (int i = 1; i < coords.size(); i++) {
            double s = CoordUtils.calcEuclideanDistance(coords.get(i - 1), coords.get(i));
            double s1 = (vMax * vMax) / (2 * a);

            if (2 * s1 >= s) {
                totalTime += 2 * Math.sqrt(s / a);
            } else {
                double tAcc = vMax / a;
                double tCruise = (s - 2 * s1) / vMax;
                totalTime += 2 * tAcc + tCruise;
            }
        }

        return totalTime;
    }

    public void setFullMerge(boolean fullMerge) {
        this.fullMerge = fullMerge;
    }

}
