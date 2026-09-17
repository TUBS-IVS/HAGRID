package hagrid.integrated.shareduse;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;

import java.util.Collections;
import java.util.Map;
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
    private final Map<Id<Person>, SegmentDetour> detourBySegment = new ConcurrentHashMap<>();

    /** Called by the gate on every insertion evaluation it declares infeasible. */
    void recordBlocked(Id<Person> parcelPersonId) {
        blockedAttempts.incrementAndGet();
        blockedSegments.add(parcelPersonId);
    }

    /**
     * Called by the gate on EVERY parcel insertion evaluation — blocked or kept — with the
     * {@code detourOnly} value it just computed.
     *
     * <p><b>Why a minimum and not another counter (2026-08-10, METHODS-LOG 2.31).</b> The three
     * counters above cannot answer whether χ is the BINDING constraint, only whether the gate is
     * active: {@code chi_blocked_segments} saturates at {@code segments_submitted} in every
     * measured run (chid600i 2953/2953, chid600w21 3104/3104), because "was ever blocked once"
     * is practically guaranteed over a simulation day with thousands of dispatch rounds — it
     * holds for the ~93 % of segments that were ultimately DELIVERED just as much as for the
     * failures. {@code segments_window_expired_chi_blocked == segments_window_expired} is
     * therefore an identity, not a measurement.
     *
     * <p>The value the gate computes and then throws away is what actually discriminates: the
     * SMALLEST {@code detourOnly} any candidate ever offered a segment is a lower bound on the χ
     * that segment would have needed. Joined against the segment's outcome
     * ({@link SharedUseKpiHandler#writeDetourCsv}) it answers the question directly — if the
     * expired segments' minima sit just above χ, the threshold binds and the sweep belongs around
     * them; if they sit at several thousand seconds, χ is not the bottleneck and the lever is
     * fleet/window/segment size. Read as a cumulative distribution it approximates the whole
     * δ(χ) curve from a SINGLE run, which is what makes a sweep grid derivable instead of guessed.
     *
     * <p><b>What it is not.</b> Not a counterfactual: a higher χ accepts more parcels, which
     * changes vehicle states and therefore shifts every later minimum. Per round it is a bound;
     * over the run it is an approximation. It also only sees candidates that survived the
     * insertion search's earlier feasibility filters (capacity, time windows) and reached the
     * cost calculator at all.
     *
     * <p><b>Which value (2026-08-13, METHODS-LOG 2.35).</b> {@code detourOnly} is now the PER-LEG
     * residual, i.e. a drive-only detour. The two runs measured under the previous summed form
     * ({@code chid600w21}, {@code chid600det}) understate it by up to the segment's own dwell —
     * that is where {@code chid600det}'s 1096 exact zeros came from — so their minima are neither
     * citable as detours nor comparable with later runs.
     *
     * <p><b>Cost.</b> One map lookup plus one double comparison per evaluation; the CAS only
     * fires when a new minimum is actually found, so the hot path does no write once a segment
     * has seen its best candidate.
     */
    void recordEvaluation(Id<Person> parcelPersonId, int parcels, double detourOnly) {
        detourBySegment.computeIfAbsent(parcelPersonId, id -> new SegmentDetour(parcels))
                .record(detourOnly);
    }

    /** Read-only view for the writer; only touched at iteration end / shutdown. */
    Map<Id<Person>, SegmentDetour> detourBySegment() {
        return Collections.unmodifiableMap(detourBySegment);
    }

    /**
     * The smallest detour the insertion search ever offered one parcel segment, plus how many
     * candidate evaluations it saw. Mutated from the (possibly parallel) insertion search, read
     * only when no mobsim thread runs.
     */
    static final class SegmentDetour {
        /** Parcels in this segment — the dwell driver, and the axis the F1 concern is about. */
        final int parcels;
        private final AtomicLong minBits =
                new AtomicLong(Double.doubleToRawLongBits(Double.POSITIVE_INFINITY));
        private final AtomicLong evaluations = new AtomicLong();

        SegmentDetour(int parcels) {
            this.parcels = parcels;
        }

        void record(double detourOnly) {
            evaluations.incrementAndGet();
            long witnessed;
            do {
                witnessed = minBits.get();
                if (Double.longBitsToDouble(witnessed) <= detourOnly) {
                    return; // common case after warm-up: no write at all
                }
            } while (!minBits.compareAndSet(witnessed, Double.doubleToRawLongBits(detourOnly)));
        }

        double minDetourSeconds() {
            return Double.longBitsToDouble(minBits.get());
        }

        long evaluations() {
            return evaluations.get();
        }
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
        detourBySegment.clear();
    }
}
