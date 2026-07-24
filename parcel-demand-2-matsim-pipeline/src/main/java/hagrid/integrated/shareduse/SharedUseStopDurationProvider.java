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
            Integer load = parcelLoadById.get(personId);
            return SharedUse.depotPickupSeconds(load != null ? load : 1);
        }
        return paxStopDuration;
    }

    double dropoffDurationFor(Id<Person> personId) {
        if (SharedUse.isParcelPerson(personId.toString())) {
            Double dwell = parcelDwellById.get(personId);
            return dwell != null ? dwell : SharedUse.segmentDwellSeconds(1);
        }
        return 0.0;
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
