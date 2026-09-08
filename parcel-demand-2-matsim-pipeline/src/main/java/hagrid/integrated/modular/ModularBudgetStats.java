package hagrid.integrated.modular;

import org.matsim.core.controler.events.IterationStartsEvent;
import org.matsim.core.controler.listener.IterationStartsListener;

/**
 * The look-ahead capacity budget's two dispatch counters, and the ONE object that carries them
 * across the QSim/controler boundary (plan {@code 2026-09-04-selfreferential-capacity-budget},
 * Task 6).
 *
 * <p><b>Why this class exists: three causes, three numbers.</b> {@link ModularTourDispatcher}
 * already distinguishes two reasons a planned tour does not go out - the splicer refused it ("the
 * tour never fit", {@code tours_rejected_at_splice}) and the pending envelope lapsed ("the gate
 * was too tight", {@code tours_expired_pending}). Its javadoc explains at length why confusing
 * those two points the theta sweep - this study's main 1d instrument - at the wrong knob;
 * METHODS-LOG 2.18 records what that cost. The budget introduces a THIRD cause, and if it is not
 * published separately a budget arm is uninterpretable in exactly the same way: a low freight
 * throughput could then be the gate, the splicer or the budget, and the CSV could not say which.
 * Publishing all three distinguishably is the condition for the arm to be worth running.
 *
 * <p><b>Why the second counter matters as much as the first.</b> {@link #expiryOverrides()}
 * counts the dispatches the budget refused and expiry overrode anyway. A high count means the
 * budget was decorative and the run is really the old theta gate wearing a new name - a fact that
 * must be visible in the published CSV, not only to whoever reads the MATSim log.
 *
 * <p><b>How the numbers cross the QSim/controler boundary, and why this way.</b>
 * {@link ModularTourDispatcher} is QSim-scoped and is rebuilt every iteration;
 * {@link ModularKpiHandler}, which writes the CSV at shutdown, is controller-scoped. A blocked
 * dispatch is counted PER ATTEMPT, and when the budget is tight every pending tour is re-checked
 * every simstep - so emitting one MATSim event per blocked attempt would mean hundreds of
 * millions of events in the morning surge. Instead this object is a controller-scoped singleton
 * (bound by {@link ModularDispatchModule} the way {@link PassengerLoadProfile} is) that the
 * dispatcher increments directly. Two consequences are deliberate:
 * <ul>
 *   <li><b>Single storage, not a copy.</b> The dispatcher keeps no counters of its own -
 *       {@code ModularTourDispatcher.budgetBlockedDispatches()} reads THIS object. A "dispatcher
 *       counts, then publishes a summary" design would hold the same number twice and could drift
 *       (or lose the final tick's increments, since {@code dispatch()} has three early returns and
 *       there is no end-of-mobsim hook on the dispatcher at all).</li>
 *   <li><b>Reset per iteration, at iteration START.</b> {@link ModularKpiHandler} clears its own
 *       accumulators in {@code reset(int)} and writes at shutdown, so every row in
 *       {@code modular_tour_stats.csv} describes the LAST iteration. These counters must be on the
 *       same basis or the CSV would mix a whole-run total into a table of last-iteration numbers.
 *       {@link IterationStartsListener} fires before the mobsim, i.e. before the iteration's
 *       dispatcher is even constructed, which is the same side of the mobsim as the event
 *       handler's own reset.</li>
 * </ul>
 *
 * <p><b>Existence IS the on/off flag.</b> Under {@code budgetMode=off} this object is never bound
 * and {@link ModularKpiHandler} receives {@code null}, which is what makes the CSV able to say
 * "the budget was off" rather than "the budget was on and never bound" - two completely different
 * runs that must not produce identical output. See {@link ModularKpiHandler#notifyShutdown} for
 * the {@code budget_active} convention this drives.
 *
 * <p><b>Determinism / threading.</b> Plain int counters, no map iteration and no RNG. Not
 * thread-safe and does not need to be: DVRP's optimizer loop is single-threaded and
 * {@link #notifyIterationStarts} runs on the controler thread between mobsims.
 */
public final class ModularBudgetStats implements IterationStartsListener {

    /** Dispatch ATTEMPTS refused by the budget in the current iteration - not distinct tours. */
    private int blockedDispatches = 0;
    /** Dispatches the budget refused but expiry overrode in the current iteration. */
    private int expiryOverrides = 0;
    /**
     * Dispatches admitted in the current iteration ONLY because the urgency ramp granted extra
     * budget - i.e. they would have been refused at urgency 0, and the deadline had not yet
     * passed (that case is {@link #expiryOverrides}). Plan 2026-09-05, Fix 2.
     *
     * <p>This is the counter that says whether the ramp did anything at all. Zero means the arm
     * is the plain budget arm and the mechanism was never exercised - a run that looks healthy
     * but tested nothing.
     */
    private int urgencyAdmits = 0;

    /**
     * Observed {@code routedDurationS / minimumChainDurationS} per dispatch in the current
     * iteration, kept so the CSV can publish a p90 and a max (plan 2026-09-05 §3).
     *
     * <p>These are the numbers that retire {@link Modular#CHAIN_BOOTSTRAP_FACTOR}: it is the one
     * guessed constant in the honest-envelope fix, and it can only be replaced by measurement if
     * the measurement is published. A plain list is enough - one entry per dispatched tour, i.e.
     * tens per iteration, not the millions the blocked-dispatch counter would have produced as
     * events.
     */
    private final java.util.List<Double> chainRatios = new java.util.ArrayList<>();

    /**
     * Package-private ON PURPOSE. Guice will JIT-create a binding for any class with a PUBLIC
     * no-arg constructor, which would silently manufacture a second, never-incremented instance if
     * {@link ModularDispatchModule}'s explicit binding were ever lost - exactly the failure this
     * class's identity matters most against. With the constructor package-private there is no JIT
     * path, so a missing binding fails loudly at injector-creation time instead.
     */
    ModularBudgetStats() {
    }

    /**
     * Records one dispatch ATTEMPT refused by the budget. Called per candidate tour per simstep,
     * so a single tour held all morning contributes many increments - that is the documented
     * semantics of {@code budget_blocked_dispatches}, and it is why this is a field increment
     * rather than an event.
     */
    void recordBlockedDispatch() {
        blockedDispatches++;
    }

    /** Records one dispatch the budget refused and the expiry envelope overrode. */
    void recordExpiryOverride() {
        expiryOverrides++;
    }

    /** Records one dispatch that only the urgency ramp made possible (deadline not yet passed). */
    void recordUrgencyAdmit() {
        urgencyAdmits++;
    }

    /**
     * Records one dispatch's {@code routedDurationS / minimumChainDurationS}. Rejects
     * non-positive and non-finite values rather than letting them poison the published p90 - the
     * ratio's denominator is a proven positive lower bound, so anything else means the caller is
     * passing something other than what this method is documented to take.
     */
    void recordChainRatio(double ratio) {
        if (!(ratio > 0.0) || Double.isInfinite(ratio)) {
            throw new IllegalArgumentException(
                    "chain ratio must be positive and finite, got " + ratio);
        }
        chainRatios.add(ratio);
    }

    /** Dispatch attempts refused by the budget in the current iteration ({@code per attempt}). */
    public int blockedDispatches() {
        return blockedDispatches;
    }

    /** Dispatches the budget refused but expiry overrode; a high count = decorative budget. */
    public int expiryOverrides() {
        return expiryOverrides;
    }

    /** Dispatches only the urgency ramp made possible; ZERO means the ramp was never exercised. */
    public int urgencyAdmits() {
        return urgencyAdmits;
    }

    /** Dispatches whose chain ratio was recorded in the current iteration. */
    public int chainRatioSamples() {
        return chainRatios.size();
    }

    /**
     * Nearest-rank p90 of the observed {@code routedDurationS / minimumChainDurationS}, or
     * {@link Double#NaN} when nothing was dispatched.
     *
     * <p>Nearest-rank rather than an interpolating percentile: with tens of samples the
     * interpolation would invent a value between two measurements, and the point of this number
     * is to report what was actually observed. Sorting a COPY - the insertion order of
     * {@link #chainRatios} is not otherwise meaningful, but mutating it here would make the
     * accessor order-dependent if it were ever called twice with a dispatch in between.
     */
    public double chainRatioP90() {
        return chainRatioQuantile(0.90);
    }

    /** Largest observed chain ratio, or {@link Double#NaN} when nothing was dispatched. */
    public double chainRatioMax() {
        return chainRatioQuantile(1.0);
    }

    private double chainRatioQuantile(double q) {
        if (chainRatios.isEmpty()) {
            return Double.NaN;
        }
        java.util.List<Double> sorted = new java.util.ArrayList<>(chainRatios);
        java.util.Collections.sort(sorted);
        int rank = (int) Math.ceil(q * sorted.size());
        return sorted.get(Math.min(sorted.size(), Math.max(1, rank)) - 1);
    }

    /**
     * Zeroes both counters so they describe ONE iteration, matching
     * {@link ModularKpiHandler#reset(int)}'s basis (see the class javadoc). Fires before the
     * mobsim, hence before the iteration's QSim-scoped dispatcher exists.
     */
    @Override
    public void notifyIterationStarts(IterationStartsEvent event) {
        blockedDispatches = 0;
        expiryOverrides = 0;
        urgencyAdmits = 0;
        chainRatios.clear();
    }
}
