package hagrid.integrated.shareduse;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * χ-gate instrumentation (M6 pre-sweep): counts how often
 * {@link ChiGateInsertionCostCalculator} actually blocked a parcel insertion, so
 * "does the χ threshold bind at all?" becomes a measurement instead of an inference.
 *
 * <p><b>Why this exists.</b> A χ-blocked parcel is never terminally rejected: it returns to
 * the {@link ParcelOnlyRetryQueue} and, if every retry is blocked until its delivery window
 * closes, it is dropped on the retrieval path WITHOUT a rejection event
 * ({@link ParcelOnlyRetryQueue#getRequestsToRetryNow}). It then lands in
 * {@code segments_window_expired}, indistinguishable from a segment that simply never had a
 * vehicle nearby or found every vehicle's parcel slots full. Consequently
 * {@code segments_rejected_final == 0} — the situation in every run so far — is fully
 * compatible with a gate that fires on every single insertion attempt, and says nothing about
 * whether χ is the binding constraint. The gate itself was the only place that knows, and it
 * kept no record.
 *
 * <p>Three counters, all per-iteration (see {@link #reset()}):
 * <ul>
 *   <li>{@code chi_blocked_insertion_attempts} — how many insertion EVALUATIONS the gate
 *       declared infeasible. This is an attempt counter, not a request counter: the insertion
 *       search evaluates many (vehicle, pickup-index, dropoff-index) candidates per request
 *       per dispatch round, and one blocked request contributes many increments. Useful only
 *       as an order-of-magnitude "is the gate hot or cold" signal — never as a rate
 *       denominator.</li>
 *   <li>{@code chi_blocked_segments} — how many DISTINCT segments were blocked at least once.
 *       Comparable against {@code segments_submitted}.</li>
 *   <li>{@code segments_window_expired_chi_blocked} — of the segments that ran out of
 *       delivery window, how many had ever been χ-blocked. This is the attribution counter:
 *       if it is ~0, the expired segments failed for reasons other than χ and the threshold is
 *       demonstrably not the bottleneck; if it approaches
 *       {@code segments_window_expired}, χ is implicated (necessary, not sufficient — a
 *       segment can be blocked once early and later fail for an unrelated reason).</li>
 * </ul>
 *
 * <p><b>Keyed by parcel-person, not request id.</b> A parcel segment is a single-person
 * request and its request id is stable across retries, so either key works within an
 * iteration — but {@link SharedUseKpiHandler#computeTotals()} classifies the expired bucket
 * by person (it resolves each request's person to read its delivery window), so keying here by
 * person lets the attribution join happen without a second lookup table.
 *
 * <p><b>Thread-safety.</b> The DRT insertion search may evaluate candidates on a pool
 * (parallel insertion providers / detour path calculators), so both counters are concurrent.
 * The read side ({@link SharedUseKpiHandler}) only reads at iteration end / shutdown, when no
 * mobsim thread is running.
 *
 * <p><b>Scope.</b> Controller-scope singleton bound by {@link SharedUseModule}: the gate is
 * rebuilt per iteration inside the QSim child injector, so the counters cannot live on the
 * gate itself or they would be discarded before anyone reads them.
 */
public final class ChiGateStats {

    private final AtomicLong blockedAttempts = new AtomicLong();
    private final Set<Id<Person>> blockedSegments = ConcurrentHashMap.newKeySet();

    /** Called by the gate on every insertion evaluation it declares infeasible. */
    void recordBlocked(Id<Person> parcelPersonId) {
        blockedAttempts.incrementAndGet();
        blockedSegments.add(parcelPersonId);
    }

    long blockedAttempts() {
        return blockedAttempts.get();
    }

    int blockedSegmentCount() {
        return blockedSegments.size();
    }

    boolean wasBlocked(Id<Person> parcelPersonId) {
        return blockedSegments.contains(parcelPersonId);
    }

    /**
     * Clears both counters. Driven by {@link SharedUseKpiHandler#reset(int)} so the χ counters
     * share the handler's per-iteration lifecycle exactly: the iteration row is appended at
     * {@code notifyIterationEnds}, which fires BEFORE the events manager resets handlers for
     * the next iteration. Resetting anywhere else would risk clearing the counters between the
     * mobsim and the row that reports them.
     */
    void reset() {
        blockedAttempts.set(0);
        blockedSegments.clear();
    }
}
