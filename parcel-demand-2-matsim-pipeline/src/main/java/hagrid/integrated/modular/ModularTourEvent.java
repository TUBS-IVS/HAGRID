package hagrid.integrated.modular;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.Event;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;

import java.util.Map;

/**
 * The ONE typed event of the Modular scenario, phase-tagged. Freight stops are plain stay
 * tasks and emit no native events (design D7), so tour accounting flows exclusively through
 * these events into ModularTourEventHandler / Task 9's KPI handler. Attributes are written
 * into {@link #getAttributes()} (not just plain getters) so they serialize into
 * output_events.xml.gz and are assertable via {@code GenericEvent} in end-to-end tests.
 */
public final class ModularTourEvent extends Event {

    public static final String EVENT_TYPE = "modularTour";

    public enum Phase { PLANNED, EXPIRED, DISPATCHED, SWAP_DONE, STOP_SERVED, COMPLETED }

    private final String tourId;
    private final Phase phase;
    private final Id<DvrpVehicle> vehicleId;   // null for PLANNED / EXPIRED
    private final int parcels;                 // tour total (PLANNED/EXPIRED/DISPATCHED) or stop count (STOP_SERVED); 0 otherwise
    private final double deadheadMeters;        // DISPATCHED only: approach + return legs
    private final double serviceMeters;         // DISPATCHED only: inter-stop legs

    private ModularTourEvent(double time, String tourId, Phase phase, Id<DvrpVehicle> vehicleId,
                             int parcels, double deadheadMeters, double serviceMeters) {
        super(time);
        this.tourId = tourId;
        this.phase = phase;
        this.vehicleId = vehicleId;
        this.parcels = parcels;
        this.deadheadMeters = deadheadMeters;
        this.serviceMeters = serviceMeters;
    }

    public static ModularTourEvent planned(double time, String tourId, int parcels) {
        return new ModularTourEvent(time, tourId, Phase.PLANNED, null, parcels, 0, 0);
    }
    public static ModularTourEvent expired(double time, String tourId, int parcels) {
        return new ModularTourEvent(time, tourId, Phase.EXPIRED, null, parcels, 0, 0);
    }
    public static ModularTourEvent dispatched(double time, String tourId, Id<DvrpVehicle> vehicle,
                                              int parcels, double deadheadMeters, double serviceMeters) {
        return new ModularTourEvent(time, tourId, Phase.DISPATCHED, vehicle, parcels,
                deadheadMeters, serviceMeters);
    }
    public static ModularTourEvent swapDone(double time, String tourId, Id<DvrpVehicle> vehicle) {
        return new ModularTourEvent(time, tourId, Phase.SWAP_DONE, vehicle, 0, 0, 0);
    }
    public static ModularTourEvent stopServed(double time, String tourId, Id<DvrpVehicle> vehicle,
                                              int parcels) {
        return new ModularTourEvent(time, tourId, Phase.STOP_SERVED, vehicle, parcels, 0, 0);
    }
    public static ModularTourEvent completed(double time, String tourId, Id<DvrpVehicle> vehicle) {
        return new ModularTourEvent(time, tourId, Phase.COMPLETED, vehicle, 0, 0, 0);
    }

    public String getTourId() { return tourId; }
    public Phase getPhase() { return phase; }
    public Id<DvrpVehicle> getVehicleId() { return vehicleId; }
    public int getParcels() { return parcels; }
    public double getDeadheadMeters() { return deadheadMeters; }
    public double getServiceMeters() { return serviceMeters; }

    @Override
    public String getEventType() { return EVENT_TYPE; }

    @Override
    public Map<String, String> getAttributes() {
        Map<String, String> attrs = super.getAttributes();
        attrs.put("tourId", tourId);
        attrs.put("phase", phase.name());
        if (vehicleId != null) attrs.put("vehicle", vehicleId.toString());
        attrs.put("parcels", Integer.toString(parcels));
        attrs.put("deadheadMeters", Double.toString(deadheadMeters));
        attrs.put("serviceMeters", Double.toString(serviceMeters));
        return attrs;
    }
}
