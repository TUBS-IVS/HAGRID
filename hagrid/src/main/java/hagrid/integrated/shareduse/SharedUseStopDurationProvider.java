package hagrid.integrated.shareduse;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contrib.drt.passenger.DrtRequest;
import org.matsim.contrib.drt.stops.PassengerStopDurationProvider;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;

import java.util.Map;

/**
 * Per-request stop-duration provider for Shared-Use (cargo hitching).
 *
 * <p>Passengers keep the native DRT stop timing (pickup = drt stopDuration,
 * dropoff = 0), mirroring {@code StaticPassengerStopDurationProvider.of(stopDuration, 0)}.
 * Parcel requests instead get:
 * <ul>
 *   <li><b>pickup</b> = the depot bulk-load time, which SCALES with the parcel
 *       count via {@link SharedUse#depotPickupSeconds(int)} (M4: the flat
 *       pickup constant was replaced by a per-request scaled model);</li>
 *   <li><b>dropoff</b> = the door-delivery segment dwell, the person attribute
 *       {@link SharedUse#DWELL_ATTRIBUTE} written by {@code ParcelAgentGenerator}.</li>
 * </ul>
 *
 * <p>Both the parcel load count and the segment dwell are supplied as immutable
 * snapshots taken from the population at module install time (parcel-persons are
 * static — no plan innovation — so a snapshot is safe). Used inside
 * {@link org.matsim.contrib.drt.stops.ParallelStopTimeCalculator}, so a shared
 * pax+parcel stop takes {@code max(durations)} and the insertion search prices it.
 */
public final class SharedUseStopDurationProvider implements PassengerStopDurationProvider {

    private final double paxStopDuration;
    private final Map<Id<Person>, Integer> parcelLoadById;
    private final Map<Id<Person>, Double> parcelDwellById;

    public SharedUseStopDurationProvider(double paxStopDuration,
                                         Map<Id<Person>, Integer> parcelLoadById,
                                         Map<Id<Person>, Double> parcelDwellById) {
        this.paxStopDuration = paxStopDuration;
        this.parcelLoadById = Map.copyOf(parcelLoadById);
        this.parcelDwellById = Map.copyOf(parcelDwellById);
    }

    double pickupDurationFor(Id<Person> personId) {
        if (SharedUse.isParcelPerson(personId.toString())) {
            return SharedUse.depotPickupSeconds(require(parcelLoadById, personId, "load"));
        }
        return paxStopDuration;
    }

    double dropoffDurationFor(Id<Person> personId) {
        if (SharedUse.isParcelPerson(personId.toString())) {
            return require(parcelDwellById, personId, "dwell");
        }
        return 0.0;
    }

    /**
     * Snapshot lookup that refuses to guess.
     *
     * <p>The previous fallbacks ("1 parcel" / {@code segmentDwellSeconds(1)}) turned a parcel
     * missing from the snapshot into the CHEAPEST possible request — 30 s at the depot instead
     * of up to {@value SharedUse#MAX_PICKUP_DURATION_S} s, 120 s at the door instead of up to
     * 900 s — so the insertion search under-priced it and every dwell-derived KPI came out
     * optimistic, with nothing in the log to show for it.
     *
     * <p>{@link ParcelAttributes} validates the whole population at install time, and
     * parcel-persons are static (no plan innovation), so a miss here can only mean the
     * snapshot-is-complete invariant broke — which must surface, not be absorbed.
     */
    private static <T> T require(Map<Id<Person>, T> snapshot, Id<Person> personId, String what) {
        T value = snapshot.get(personId);
        if (value == null) {
            throw new IllegalStateException("parcel-person " + personId + " is missing from the "
                    + what + " snapshot taken at module install. Shared-Use cannot price its stop"
                    + " without guessing; the population must have changed after install.");
        }
        return value;
    }

    @Override
    public double calcPickupDuration(DvrpVehicle vehicle, DrtRequest request) {
        return request.getPassengerIds().stream().mapToDouble(this::pickupDurationFor).max()
                .orElse(paxStopDuration);
    }

    @Override
    public double calcDropoffDuration(DvrpVehicle vehicle, DrtRequest request) {
        return request.getPassengerIds().stream().mapToDouble(this::dropoffDurationFor).max().orElse(0.0);
    }
}
