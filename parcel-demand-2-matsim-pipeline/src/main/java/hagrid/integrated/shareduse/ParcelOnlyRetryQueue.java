package hagrid.integrated.shareduse;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contrib.drt.optimizer.DrtRequestInsertionRetryParams;
import org.matsim.contrib.drt.optimizer.DrtRequestInsertionRetryQueue;
import org.matsim.contrib.drt.passenger.DrtRequest;

import java.util.List;
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
 * <p>The window is enforced on BOTH queue paths, so "delivered within the window" holds
 * regardless of when wall-clock crosses the deadline relative to enqueue:
 * <ul>
 *   <li>the re-add point ({@link #tryAddFailedRequest}) — a parcel is never (re-)enqueued
 *       once its window has passed; and</li>
 *   <li>the retrieval point ({@link #getRequestsToRetryNow}) — a parcel enqueued just
 *       BEFORE its window but retrieved just AFTER it is filtered out, so it can never be
 *       handed back to the dispatcher and physically (re-)scheduled past its deadline.</li>
 * </ul>
 * Both native methods are public and non-final in 2025.0, so no fragile re-implementation
 * of the queue's internal deque is needed (M5 dispatcher-enforced path). Per-request
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
     * Retrieval-path window guard (mirror of {@link #tryAddFailedRequest}). The native
     * queue dequeues purely on the retry interval
     * ({@code lastAttemptTime <= now - retryInterval}) and is therefore window-unaware:
     * a parcel enqueued just BEFORE its window would be handed back on the first optimizer
     * step after the window and — if a feasible sub-χ insertion now exists — scheduled and
     * PHYSICALLY DELIVERED past its deadline, silently violating M5. We therefore drop any
     * retrieved request whose window has already passed, so the window guard governs the
     * success path too, not only the re-add path.
     *
     * <p>A dropped past-window parcel is already removed from the internal deque by the
     * native retrieval ({@code removeFirst}) and is simply not returned — no rejection event
     * is emitted (this matches the native no-event-for-unserved behaviour). Task 7's KPI
     * layer surfaces these as undelivered-at-EOD (submitted − delivered); δ / expired-window
     * classification is that layer's job, not the queue's, so no custom event is added here.
     */
    @Override
    public List<DrtRequest> getRequestsToRetryNow(double now) {
        List<DrtRequest> due = super.getRequestsToRetryNow(now);
        due.removeIf(request -> now > windowEnd(request));
        return due;
    }

    /**
     * The parcel's absolute delivery deadline. If several passenger ids carry a
     * window (defensive — parcel requests are single-person in practice), the most
     * restrictive (earliest) governs.
     *
     * <p>A parcel with NO recorded window used to fall back to {@code POSITIVE_INFINITY},
     * i.e. "never expires" — which silently switched M5 off for that request: it would keep
     * retrying past its deadline and could be delivered at any hour, while δ still looked
     * clean. {@link ParcelAttributes#windowEnds} now validates the whole population at QSim
     * module install, so a miss here means the snapshot went stale and must surface.
     */
    private double windowEnd(DrtRequest request) {
        return request.getPassengerIds().stream()
                .map(windowEndById::get)
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .min()
                .orElseThrow(() -> new IllegalStateException(
                        "parcel request " + request.getId() + " (passengers "
                        + request.getPassengerIds() + ") has no delivery window in the snapshot;"
                        + " refusing to treat it as never-expiring, which would disable M5"
                        + " for this request."));
    }
}
