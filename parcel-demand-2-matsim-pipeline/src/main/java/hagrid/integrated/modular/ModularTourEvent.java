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

    /**
     * {@code SPLICE_REJECTED} is emitted at most ONCE per tour, the first time
     * {@link ModularTourScheduler#schedule} returns {@code Optional.empty()} for it — see
     * {@link ModularTourDispatcher} for why that is a different failure from {@code EXPIRED}
     * and why the two must not be confused in the δ decomposition. Once-per-tour, not
     * once-per-attempt: a tour the splicer keeps rejecting is retried every simstep the gate is
     * open, which in the θ=0 arm would otherwise write tens of thousands of identical events
     * into {@code output_events.xml.gz} to say the one thing "this tour did not fit".
     */
    public enum Phase { PLANNED, EXPIRED, SPLICE_REJECTED, DISPATCHED, SWAP_DONE, STOP_SERVED,
        COMPLETED }

    private final String tourId;
    private final Phase phase;
    private final Id<DvrpVehicle> vehicleId;   // null for PLANNED / EXPIRED
    private final int parcels;                 // tour total (PLANNED/EXPIRED/DISPATCHED) or stop count (STOP_SERVED); 0 otherwise
    private final double deadheadMeters;        // DISPATCHED only: approach + return legs
    private final double serviceMeters;         // DISPATCHED only: inter-stop legs
    // Task 1 (paper-readiness review F1/F3/F5/F7): ADDITIVE, DISPATCHED-only fields - existing
    // regex-based Python consumers of the events file key off "phase=DISPATCHED" and read
    // deadheadMeters/serviceMeters by name, so two more named attributes do not break them.
    private final double plannedDurationS;      // DISPATCHED only: jsprit's car-network sum (tour.plannedDuration())
    private final double routedDurationS;       // DISPATCHED only: splicer's routed excursion length (completion - dispatch "now")

    private ModularTourEvent(double time, String tourId, Phase phase, Id<DvrpVehicle> vehicleId,
                             int parcels, double deadheadMeters, double serviceMeters,
                             double plannedDurationS, double routedDurationS) {
        super(time);
        this.tourId = tourId;
        this.phase = phase;
        this.vehicleId = vehicleId;
        this.parcels = parcels;
        this.deadheadMeters = deadheadMeters;
        this.serviceMeters = serviceMeters;
        this.plannedDurationS = plannedDurationS;
        this.routedDurationS = routedDurationS;
    }

    public static ModularTourEvent planned(double time, String tourId, int parcels) {
        return new ModularTourEvent(time, tourId, Phase.PLANNED, null, parcels, 0, 0, 0, 0);
    }
    public static ModularTourEvent expired(double time, String tourId, int parcels) {
        return new ModularTourEvent(time, tourId, Phase.EXPIRED, null, parcels, 0, 0, 0, 0);
    }
    /**
     * The splicer refused this tour on the vehicle offered to it. Carries the CANDIDATE vehicle
     * (the nearest idle one, which is what the envelope was tested against) so a reader can tell
     * a systematically-too-far candidate from a systematically-too-long tour.
     */
    public static ModularTourEvent spliceRejected(double time, String tourId,
                                                  Id<DvrpVehicle> vehicle, int parcels) {
        return new ModularTourEvent(time, tourId, Phase.SPLICE_REJECTED, vehicle, parcels, 0, 0, 0, 0);
    }
    /**
     * @param plannedDurationS jsprit's car-network planned duration for this tour
     *                         ({@code ModularFreightTour#plannedDuration()}) - the figure the
     *                         dispatcher's pending-expiry pre-check is computed from (design
     *                         §4.3/C4, METHODS-LOG 2.18).
     * @param routedDurationS  the splicer's actual routed excursion length on the DRT network
     *                         ({@code ScheduledExcursion#routedDurationS()}) - always &gt;=
     *                         approach + service + return + 2x retooling, and systematically
     *                         larger than {@code plannedDurationS} wherever DRT routing diverges
     *                         from jsprit's car-network routing (the same divergence
     *                         {@code tours_rejected_at_splice} exists to surface).
     */
    public static ModularTourEvent dispatched(double time, String tourId, Id<DvrpVehicle> vehicle,
                                              int parcels, double deadheadMeters, double serviceMeters,
                                              double plannedDurationS, double routedDurationS) {
        return new ModularTourEvent(time, tourId, Phase.DISPATCHED, vehicle, parcels,
                deadheadMeters, serviceMeters, plannedDurationS, routedDurationS);
    }
    public static ModularTourEvent swapDone(double time, String tourId, Id<DvrpVehicle> vehicle) {
        return new ModularTourEvent(time, tourId, Phase.SWAP_DONE, vehicle, 0, 0, 0, 0, 0);
    }
    public static ModularTourEvent stopServed(double time, String tourId, Id<DvrpVehicle> vehicle,
                                              int parcels) {
        return new ModularTourEvent(time, tourId, Phase.STOP_SERVED, vehicle, parcels, 0, 0, 0, 0);
    }
    public static ModularTourEvent completed(double time, String tourId, Id<DvrpVehicle> vehicle) {
        return new ModularTourEvent(time, tourId, Phase.COMPLETED, vehicle, 0, 0, 0, 0, 0);
    }

    public String getTourId() { return tourId; }
    public Phase getPhase() { return phase; }
    public Id<DvrpVehicle> getVehicleId() { return vehicleId; }
    public int getParcels() { return parcels; }
    public double getDeadheadMeters() { return deadheadMeters; }
    public double getServiceMeters() { return serviceMeters; }
    public double getPlannedDurationS() { return plannedDurationS; }
    public double getRoutedDurationS() { return routedDurationS; }

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
        attrs.put("plannedDurationS", Double.toString(plannedDurationS));
        attrs.put("routedDurationS", Double.toString(routedDurationS));
        return attrs;
    }
}
