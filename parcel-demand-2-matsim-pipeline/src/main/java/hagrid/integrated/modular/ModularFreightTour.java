package hagrid.integrated.modular;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;

import java.util.List;

/**
 * One offline-planned freight tour, ready for online dispatch (Task 4: jsprit plan ->
 * dispatchable tour, the bridge between {@code LausitzFreightPreprocessor.runModular}'s offline
 * routing and the Task 6 splicer / Task 7 dispatcher).
 *
 * <p>All link ids ({@link #depotLink()}, every {@link Stop#link()}) are ALREADY snapped onto the
 * DRT network by {@link ModularTourConverter} - jsprit routes on the CAR network, which has a
 * different link set than the one the DRT fleet actually drives.
 *
 * <p>{@link #latestEnd()} is the jsprit vehicle's operating-window end
 * ({@code CarrierVehicle.getLatestEndTime()}), which for every 1d tour equals
 * {@code Modular.DELIVERY_DAY_END_S} (21:00, plan C4 revised: full delivery day, no dispatch
 * waves) - NOT {@code DvrpVehicleSpecification.getServiceEndTime()} (the DRT fleet vehicle's
 * envelope, written as the vacuous 86400/midnight). Those are two different "vehicle" concepts;
 * only the jsprit/carrier vehicle's window carries the real freight deadline.
 *
 * <p>{@link #tourIndex()} is the C7 interleave key: because 1d has no dispatch waves, every tour
 * becomes pending at roughly the same instant, so Task 7's dispatcher sorts the pending pool by
 * {@code (submissionTime, tourIndex, provider)} rather than by {@link #tourId()} - sorting by id
 * would dispatch every tour of one provider before the first tour of the next and bias delta per
 * provider.
 */
public record ModularFreightTour(String tourId, String provider, int tourIndex,
                                 Id<Link> depotLink, double plannedStart, double plannedDuration,
                                 double latestEnd, List<Stop> stops) {

    /** A single delivery stop, already snapped to the DRT network. */
    public record Stop(Id<Link> link, double serviceDuration, int parcels) {}

    public ModularFreightTour {
        stops = List.copyOf(stops);
        if (stops.isEmpty()) {
            throw new IllegalArgumentException("tour without stops: " + tourId);
        }
    }

    public int totalParcels() {
        return stops.stream().mapToInt(Stop::parcels).sum();
    }

    /**
     * Spec §4.3: submission = plannedTourStart - (look-ahead base + retooling). Derived, not
     * stored, so it can never drift out of sync with {@link #plannedStart()}.
     */
    public double submissionTime() {
        return plannedStart - (Modular.FREIGHT_LOOKAHEAD_S + Modular.RETOOLING_S);
    }
}
