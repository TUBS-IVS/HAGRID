package hagrid.lausitz.modular;

import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.listener.IterationEndsListener;

import java.util.HashMap;
import java.util.Map;

/**
 * Self-referential estimate of how long a modular freight tour actually holds a vehicle, learned
 * from the run's own previous iterations (plan
 * {@code 2026-09-05-honest-envelope-and-urgency-ramp}, Task 1).
 *
 * <p><b>Why this class exists.</b> {@link ModularTourDispatcher} has to answer "can this tour
 * still be completed before its deadline" long before any vehicle is chosen, and it used to
 * answer with {@code 2*RETOOLING_S + }{@link ModularFreightTour#plannedDuration()} - jsprit's sum
 * over the <b>car</b> network, from the depot, with no approach leg. The splicer then decides
 * with the actual DRT-routed completion including the approach. Measured on the anchor arm
 * {@code d1d_dep7_f130_it250}, iteration 250, all 46 tours, the first quantity is optimistic by a
 * median of 943 s, a p90 of 1635 s and a maximum of 2403 s.
 *
 * <p>That gap killed the first budget arm ({@code d1d_f130_bud}). The dispatcher held tours back,
 * fired its "last dispatch opportunity" override against the optimistic deadline, and the splicer
 * refused the override in the same second because the honest completion no longer fitted - 31 of
 * 46 tours expired, and the failure was self-reinforcing across iterations. The fix is to stop
 * predicting the chain from a quantity that is structurally too small.
 *
 * <p><b>What is learned, and why {@code max} rather than a mean.</b> Every successful dispatch
 * yields {@link ModularTourScheduler.ScheduledExcursion#routedDurationS()} for exactly that tour:
 * the real routed excursion length, approach and both retoolings included. This class keeps, per
 * tour id, the maximum observed in each of the last {@code k} completed iterations, and reports
 * the maximum over those. The asymmetry is deliberate and is the whole point: an estimate that is
 * too small drops parcels (the failure above), one that is too large costs a single dispatch made
 * earlier than strictly necessary. Those two errors are not remotely comparable, so the estimator
 * is biased on purpose toward the survivable side. The {@code k}-iteration window is what keeps a
 * single pathological iteration from inflating the estimate forever.
 *
 * <p><b>Unknown is NaN, never zero</b> - the same rule as {@link PassengerLoadProfile}, for the
 * same reason: a tour never yet dispatched must read as "no information", so the caller falls
 * back to its documented bootstrap. Zero would read as "this tour takes no time", the most
 * permissive answer available and the one a bare {@code double} field would produce.
 *
 * <p><b>Why controller-scoped.</b> {@link ModularTourDispatcher} is QSim-scoped and resets every
 * iteration by construction (the 1c {@code dd34b23} lesson). A profile whose entire purpose is to
 * carry information from one iteration into the next must not live there. Bound exactly the way
 * {@link PassengerLoadProfile} is - but UNCONDITIONALLY, not only under {@code budgetMode=selfref}:
 * the honest envelope is a correctness fix, not a feature of the budget arm, and gating it on the
 * budget would mean the budget arm could never be compared one-factor against any other arm.
 *
 * <p><b>Determinism.</b> The maps are read by key lookup only - never iterated - so
 * {@link HashMap} order cannot reach any decision or any output. {@link #observe} takes a max,
 * which is associative and exact in floating point, so no summation-order question arises (the
 * one {@link PassengerLoadProfile} has to answer for its mean). Not thread-safe and does not need
 * to be: DVRP's optimizer loop is single-threaded and {@link #notifyIterationEnds} runs on the
 * controler thread between mobsims.
 */
public final class FreightChainProfile implements IterationEndsListener {

    /** Window: number of completed iterations the per-tour maximum is taken over. */
    private final int smoothingIterations;

    /**
     * Ring over the last {@code smoothingIterations} COMPLETED iterations; each slot maps tour id
     * to the longest routed excursion observed for it in that iteration. {@code null} slots are
     * iterations that have not happened yet.
     */
    private final Map<String, Double>[] history;

    /** Longest routed excursion per tour id in the CURRENT (in-progress) iteration. */
    private final Map<String, Double> current = new HashMap<>();

    private int completedIterations = 0;

    /**
     * @param smoothingIterations {@code k}, the number of completed iterations the per-tour
     *                            maximum is taken over. Must be at least 1. Handed the same
     *                            {@code budgetSmoothing} value as {@link PassengerLoadProfile} so
     *                            the two self-referential estimators in this package cannot drift
     *                            apart in responsiveness.
     */
    @SuppressWarnings("unchecked")
    public FreightChainProfile(int smoothingIterations) {
        if (smoothingIterations < 1) {
            throw new IllegalArgumentException(
                    "FreightChainProfile smoothing window k must be >= 1 (k=1 means 'previous"
                            + " iteration only'); got " + smoothingIterations);
        }
        this.smoothingIterations = smoothingIterations;
        this.history = new Map[smoothingIterations];
    }

    /**
     * Records one successful dispatch's actual routed excursion length.
     *
     * <p>Called from the dispatcher AFTER the splicer accepted, so every sample describes a chain
     * that really was scheduled. Repeated dispatches of the same tour within one iteration keep
     * the longest - a tour cannot be dispatched twice in the current design, but the max makes
     * that assumption harmless rather than load-bearing.
     *
     * @param tourId          the tour's id.
     * @param routedDurationS the splicer's routed excursion length in seconds, approach leg and
     *                        both retoolings included. Must be positive.
     */
    public void observe(String tourId, double routedDurationS) {
        if (!(routedDurationS > 0.0) || Double.isInfinite(routedDurationS)) {
            throw new IllegalArgumentException("routedDurationS must be positive and finite, got "
                    + routedDurationS + " for tour " + tourId);
        }
        current.merge(tourId, routedDurationS, Math::max);
    }

    /**
     * Rolls the current iteration's observations into the ring and clears the accumulator.
     *
     * <p>The in-progress iteration is deliberately NOT part of {@link #estimate(String)}: feeding
     * a tour's own within-mobsim measurement back to the gate that dispatched it would turn a
     * damped cross-iteration loop into an undamped within-iteration one. This is the "previous
     * iteration" in "self-referential".
     *
     * <p>An iteration that observed nothing still occupies a slot. That is intended: {@code k}
     * such iterations legitimately evict all knowledge and return every tour to the bootstrap,
     * which is more honest than serving a stale estimate indefinitely.
     */
    @Override
    public void notifyIterationEnds(IterationEndsEvent event) {
        history[completedIterations % smoothingIterations] = Map.copyOf(current);
        completedIterations++;
        current.clear();
    }

    /**
     * Longest routed excursion observed for {@code tourId} over the buffered completed
     * iterations, or {@link Double#NaN} if none of them dispatched it.
     *
     * <p>{@code NaN} means "no information" and the caller must fall back to its bootstrap - see
     * {@link ModularTourDispatcher} for that path. It must NOT be treated as zero.
     */
    public double estimate(String tourId) {
        int kept = Math.min(completedIterations, smoothingIterations);
        double best = Double.NaN;
        for (int age = 0; age < kept; age++) {
            int slot = (completedIterations - kept + age) % smoothingIterations;
            Double v = history[slot].get(tourId);
            if (v == null) {
                continue;
            }
            best = Double.isNaN(best) ? v : Math.max(best, v);
        }
        return best;
    }

    /** False until at least one iteration has completed; while false every tour bootstraps. */
    public boolean isBootstrapped() {
        return completedIterations > 0;
    }

    /** Completed iterations rolled in so far (diagnostics / KPI). */
    public int completedIterations() {
        return completedIterations;
    }

    /** Test/diagnostics hook: observations recorded in the in-progress iteration. */
    int currentIterationObservations() {
        return current.size();
    }
}
