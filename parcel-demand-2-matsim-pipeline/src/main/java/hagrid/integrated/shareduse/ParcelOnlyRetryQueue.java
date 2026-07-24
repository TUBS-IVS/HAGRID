package hagrid.integrated.shareduse;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contrib.drt.optimizer.DrtRequestInsertionRetryParams;
import org.matsim.contrib.drt.optimizer.DrtRequestInsertionRetryQueue;
import org.matsim.contrib.drt.passenger.DrtRequest;

import java.util.Map;
import java.util.Objects;

/**
 * Pending semantics for PARCELS ONLY.
 *
 * <p>The native {@link DrtRequestInsertionRetryQueue} keeps a rejected request
 * eligible for re-insertion until a single global {@code maxRequestAge} ceiling.
 * This subclass changes two things:
 * <ul>
 *   <li><b>Passengers are never retried.</b> A pax rejection returns {@code false}
 *       (native-immediate rejection), so passenger KPIs stay identical to
 *       DRT_BASELINE — the pending machinery only ever holds parcels.</li>
 *   <li><b>Per-request delivery window (M5).</b> A parcel stops being retried once
 *       wall-clock passes its OWN {@code WINDOW_END_ATTRIBUTE} (absolute deadline:
 *       B2B 17:00 / B2C 20:00), even when the global {@code maxRequestAge} would
 *       still accept it. This makes "delivered within the window" a hard property:
 *       a B2B parcel can never be counted as delivered at 20:00, and δ (undelivered
 *       share) becomes a clean feasibility signal rather than a soft-window artefact.</li>
 * </ul>
 *
 * <p>The window is enforced at the queue's re-add point ({@link #tryAddFailedRequest}),
 * which is public and non-final in 2025.0, so no fragile re-implementation of the
 * queue's retrieval internals is needed (M5 dispatcher-enforced path). Per-request
 * deadlines are supplied as an immutable snapshot taken from the {@code Population}
 * in the QSim module (parcel-persons are static, so a snapshot is safe).
 */
public final class ParcelOnlyRetryQueue extends DrtRequestInsertionRetryQueue {

    private final Map<Id<Person>, Double> windowEndById;

    public ParcelOnlyRetryQueue(DrtRequestInsertionRetryParams params,
                                Map<Id<Person>, Double> windowEndById) {
        super(params);
        this.windowEndById = Map.copyOf(windowEndById);
    }

    @Override
    public boolean tryAddFailedRequest(DrtRequest request, double now) {
        if (!ChiGateInsertionCostCalculator.isParcel(request)) {
            return false;   // passengers: native-immediate rejection, never pending
        }
        if (now > windowEnd(request)) {
            return false;   // past this parcel's own delivery window: stop retrying
        }
        return super.tryAddFailedRequest(request, now);
    }

    /**
     * The parcel's absolute delivery deadline. If several passenger ids carry a
     * window (defensive — parcel requests are single-person in practice), the most
     * restrictive (earliest) governs. A parcel with no recorded window never expires
     * here, so only the global {@code maxRequestAge} bounds it.
     */
    private double windowEnd(DrtRequest request) {
        return request.getPassengerIds().stream()
                .map(windowEndById::get)
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .min()
                .orElse(Double.POSITIVE_INFINITY);
    }
}
