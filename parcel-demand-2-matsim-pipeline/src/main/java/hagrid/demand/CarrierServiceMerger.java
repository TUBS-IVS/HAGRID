package hagrid.demand;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;

import hagrid.HagridConfigGroup;
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
    private HagridConfigGroup hagridConfig;

    @Inject
    private Scenario scenario;

    private boolean fullMerge;

    private static final Logger LOGGER = LogManager.getLogger(CarrierServiceMerger.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

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
        CarrierMergeValidator.validatePostMerge(carriers, preStats, hagridConfig.getRunId());

        // Final log statement for completed merge step
        LOGGER.info("Service merging completed.");
    }

    public static void mergeServices(Carriers carriers, Scenario scenario, HagridConfigGroup hagridConfig,
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
                // TODO: Implement a splitting of merged services if the demand exceeds the
                // demand border. But with our date it does not happen currently.

                if (totalCapacity > hagridConfig.getDemandBorder()) {
                    throw new IllegalStateException(String.format(
                            "Merged service exceeds demand border! Capacity = %d, Border = %d. Carrier: %s, Link: %s",
                            totalCapacity, hagridConfig.getDemandBorder(), carrier.getId(), key.linkId));
                }
                Id<Link> selectedLinkId = fullMerge
                        ? key.linkId
                        : services.get(0).getServiceLinkId();
                Link link = fullMerge
                        ? subNetwork.getLinks().get(key.linkId)
                        : null;

                double timeBonus = fullMerge ? computeRealisticTravelTime(coords, link, isCargo) : 0.0;

                double finalDuration = totalDuration + timeBonus;

                // Define parcel type based on b2b and b2c counts
                ParcelType mergedType;
                if (b2b > 0 && b2c > 0) {
                    mergedType = ParcelType.MIXED;
                } else if (b2b > 0) {
                    mergedType = ParcelType.B2B;
                } else if (b2c > 0) {
                    mergedType = ParcelType.B2C;
                } else {
                    throw new IllegalStateException("Merged service has no parcel type assigned (b2b=0, b2c=0)");
                }

                String newServiceId = String.format("service_%s_%s_%d",
                        "MIXED", carrier.getId().toString(), merged);

                CarrierService.Builder builder = CarrierService.Builder.newInstance(
                        Id.create(newServiceId, CarrierService.class), selectedLinkId);

                builder.setCapacityDemand(totalCapacity);
                builder.setServiceDuration(finalDuration);

                final double begin = hagridConfig.getDeliveryTimeWindowStart();
                final double end = hagridConfig.getDeliveryTimeWindowEnd();
                builder.setServiceStartingTimeWindow(TimeWindow.newInstance(begin, end));

                CarrierService mergedService = builder.build();

                mergedService.getAttributes().putAttribute("carrierMode", key.carrierMode);
                mergedService.getAttributes().putAttribute("skills", String.join(",", key.skillSet));
                mergedService.getAttributes().putAttribute("b2b", b2b);
                mergedService.getAttributes().putAttribute("b2c", b2c);
                mergedService.getAttributes().putAttribute("serviceDuration", (int) totalDuration);
                mergedService.getAttributes().putAttribute("travelDuration", (int) timeBonus);

                mergedService.getAttributes().putAttribute("type", mergedType);

                try {
                    String jsonMeta = objectMapper.writeValueAsString(mergedMeta);
                    mergedService.getAttributes().putAttribute("mergedMetadata", jsonMeta);
                } catch (JsonProcessingException e) {
                    LOGGER.error("Failed to write merged service metadata", e);
                }

                // add + cleanup
                CarriersUtils.addService(carrier, mergedService);
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
