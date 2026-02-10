package hagrid.analysis;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.*;
import org.matsim.api.core.v01.events.handler.*;
import org.matsim.api.core.v01.network.Link;

import java.util.*;

/**
 * Single-pass MATSim event handler that collects freight-related metrics:
 * <ul>
 *     <li>Per-vehicle tour link sequences with timestamps</li>
 *     <li>Per-link traffic counts by vehicle type</li>
 *     <li>Service start/end events (delivery stop timings)</li>
 *     <li>Tour start/end events (depot departure/arrival)</li>
 * </ul>
 */
public class FreightEventHandler implements
        LinkLeaveEventHandler,
        ActivityStartEventHandler,
        ActivityEndEventHandler {

    private static final Logger LOG = LogManager.getLogger(FreightEventHandler.class);

    private static final double DAY_END = 30 * 3600.0; // 30h cutoff

    private static final String[] FREIGHT_KEYWORDS = {
        "_Supply_Vehicle_", "_veh_supply_",
        "_CEP_Vehicle_", "_veh_cep_",
        "_egrocery_van_",
        "_cargoBike_", "_cargobike_"
    };

    // ── results ──

    /** vehicle_id → ordered list of (link_id, time_seconds) */
    private final Map<String, List<LinkVisit>> vehicleTours = new LinkedHashMap<>();

    /** link_id → per-type counts */
    private final Map<String, LinkCounts> linkCountMap = new LinkedHashMap<>();

    /** Collected service events per vehicle */
    private final Map<String, List<ServiceEvent>> serviceEvents = new LinkedHashMap<>();

    /** Tour start (departure from depot) events */
    private final List<TourBoundaryEvent> tourStarts = new ArrayList<>();

    /** Tour end (arrival at depot) events */
    private final List<TourBoundaryEvent> tourEnds = new ArrayList<>();

    private final Map<String, String> lastLink = new HashMap<>();
    private int totalEventsProcessed = 0;

    // ── event handling ──

    @Override
    public void handleEvent(LinkLeaveEvent event) {
        if (event.getTime() > DAY_END) return;
        totalEventsProcessed++;

        String vehicleId = event.getVehicleId().toString();
        String linkId = event.getLinkId().toString();
        VehicleType vtype = classifyVehicle(vehicleId);
        if (vtype == null) return;

        // deduplicate consecutive same-link
        if (linkId.equals(lastLink.get(vehicleId))) return;
        lastLink.put(vehicleId, linkId);

        vehicleTours.computeIfAbsent(vehicleId, k -> new ArrayList<>())
                .add(new LinkVisit(linkId, event.getTime()));

        linkCountMap.computeIfAbsent(linkId, k -> new LinkCounts())
                .increment(vtype);
        linkCountMap.get(linkId).incrementHourly(vtype, event.getTime());
    }

    @Override
    public void handleEvent(ActivityStartEvent event) {
        if (event.getTime() > DAY_END) return;
        totalEventsProcessed++;

        String person = event.getPersonId().toString();
        String actType = event.getActType();
        String linkId = event.getLinkId().toString();

        if ("end".equals(actType) && isFreight(person)) {
            // Tour end = arrival back at depot
            vehicleTours.computeIfAbsent(person, k -> new ArrayList<>())
                    .add(new LinkVisit(linkId, event.getTime()));
            tourEnds.add(new TourBoundaryEvent(person, linkId, event.getTime()));
        } else if ("service".equals(actType)) {
            serviceEvents.computeIfAbsent(person, k -> new ArrayList<>())
                    .add(new ServiceEvent(person, linkId, event.getTime(), true));
        }
    }

    @Override
    public void handleEvent(ActivityEndEvent event) {
        if (event.getTime() > DAY_END) return;
        totalEventsProcessed++;

        String person = event.getPersonId().toString();
        String actType = event.getActType();
        String linkId = event.getLinkId().toString();

        if ("start".equals(actType) && isFreight(person)) {
            tourStarts.add(new TourBoundaryEvent(person, linkId, event.getTime()));
        } else if ("service".equals(actType)) {
            serviceEvents.computeIfAbsent(person, k -> new ArrayList<>())
                    .add(new ServiceEvent(person, linkId, event.getTime(), false));
        }
    }

    // ── classification ──

    private static boolean isFreight(String id) {
        for (String kw : FREIGHT_KEYWORDS) {
            if (id.contains(kw)) return true;
        }
        return false;
    }

    static VehicleType classifyVehicle(String vehicle) {
        if (vehicle.contains("_Supply_Vehicle_") || vehicle.contains("_veh_supply_")) {
            if (vehicle.contains("supply_light_van")) return VehicleType.SUPPLY_VAN;
            if (vehicle.contains("light")) return VehicleType.TRUCK_LIGHT;
            return VehicleType.TRUCK;
        }
        if (vehicle.contains("_CEP_Vehicle_") || vehicle.contains("_veh_cep_") || vehicle.contains("_egrocery_van_"))
            return VehicleType.VAN;
        if (vehicle.contains("_cargoBike_") || vehicle.contains("_cargobike_"))
            return VehicleType.CARGOBIKE;
        return null;
    }

    // ── getters ──

    public Map<String, List<LinkVisit>> getVehicleTours() { return vehicleTours; }
    public Map<String, LinkCounts> getLinkCountMap() { return linkCountMap; }
    public Map<String, List<ServiceEvent>> getServiceEvents() { return serviceEvents; }
    public List<TourBoundaryEvent> getTourStarts() { return tourStarts; }
    public List<TourBoundaryEvent> getTourEnds() { return tourEnds; }
    public int getTotalEventsProcessed() { return totalEventsProcessed; }

    // ── inner types ──

    public enum VehicleType {
        VAN, CARGOBIKE, TRUCK, TRUCK_LIGHT, SUPPLY_VAN;

        public String label() {
            return switch (this) {
                case VAN -> "CEP Van";
                case CARGOBIKE -> "Cargobike";
                case TRUCK -> "Truck (heavy)";
                case TRUCK_LIGHT -> "Truck (light)";
                case SUPPLY_VAN -> "Supply Van";
            };
        }

        public String color() {
            return switch (this) {
                case VAN -> "#3b82f6";
                case CARGOBIKE -> "#10b981";
                case TRUCK -> "#ef4444";
                case TRUCK_LIGHT -> "#f59e0b";
                case SUPPLY_VAN -> "#8b5cf6";
            };
        }
    }

    public record LinkVisit(String linkId, double timeSec) {}

    public record ServiceEvent(String vehicleId, String linkId, double timeSec, boolean isStart) {}

    public record TourBoundaryEvent(String vehicleId, String linkId, double timeSec) {}

    public static class LinkCounts {
        private final int[] counts = new int[VehicleType.values().length];
        /** Hourly total counts (index 0 = 00:00–01:00, … 23 = 23:00–24:00) */
        private final int[] hourlyTotal = new int[24];
        /** Hourly delivery counts */
        private final int[] hourlyDelivery = new int[24];
        /** Hourly supply counts */
        private final int[] hourlySuppply = new int[24];

        public void increment(VehicleType type) { counts[type.ordinal()]++; }
        public void incrementHourly(VehicleType type, double timeSec) {
            int h = Math.min(23, Math.max(0, (int)(timeSec / 3600)));
            hourlyTotal[h]++;
            if (type == VehicleType.VAN || type == VehicleType.CARGOBIKE) {
                hourlyDelivery[h]++;
            } else {
                hourlySuppply[h]++;
            }
        }
        public int get(VehicleType type) { return counts[type.ordinal()]; }
        public int total() {
            int s = 0; for (int c : counts) s += c; return s;
        }
        public int totalDelivery() { return get(VehicleType.VAN) + get(VehicleType.CARGOBIKE); }
        public int totalSupply() { return get(VehicleType.TRUCK) + get(VehicleType.TRUCK_LIGHT) + get(VehicleType.SUPPLY_VAN); }
        public int[] getHourlyTotal() { return hourlyTotal; }
        public int[] getHourlyDelivery() { return hourlyDelivery; }
        public int[] getHourlySupply() { return hourlySuppply; }
    }
}
