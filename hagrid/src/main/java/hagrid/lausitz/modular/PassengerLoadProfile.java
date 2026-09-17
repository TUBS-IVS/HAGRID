package hagrid.lausitz.modular;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.listener.IterationEndsListener;

import java.util.Arrays;

/**
 * Self-referential passenger-load profile: the per-time-bin count of vehicles busy on PASSENGER
 * work, measured during the simulation's own previous iterations and handed back to
 * {@link ModularTourDispatcher} as a time-varying freight capacity budget (plan
 * {@code 2026-09-04-selfreferential-capacity-budget}, Task 2).
 *
 * <p><b>Why this class exists at all, i.e. why the budget is not a constant.</b> The scalar
 * idle-share gate (theta) measures the MODULAR fleet's own slack, which is larger precisely
 * because that fleet also carries the freight. It therefore cannot express "the passenger
 * operation needs these vehicles at 10:15". The measured collision is an overhang of 17.3
 * vehicle-hours concentrated 08:00-11:00 with a peak in a single 30-minute stretch - a quantity
 * that only exists as a function of time of day. Reading that function from a passenger-only
 * Baseline run would parameterise the 1d arm with information taken from the very arm it is
 * compared against; reading it from iteration N-1 of the SAME run removes that circularity and
 * keeps the rule scenario-independent (no Hoyerswerda constants, transferable to Hannover).
 *
 * <p><b>Why controller-scoped and not part of the dispatcher.</b> {@link ModularTourDispatcher}
 * is QSim-scoped: it is rebuilt every iteration and all its state resets by construction (the 1c
 * {@code dd34b23} lesson). That is exactly the right lifecycle for pending tours and exactly the
 * wrong one for a profile whose entire purpose is to survive from one iteration into the next.
 * This class is therefore bound the way {@link ModularKpiHandler} is - as an eager
 * controller-scoped singleton via {@code addControlerListenerBinding} (Task 5) - and injected
 * INTO the QSim-scoped dispatcher. The QSim injector is a child of the controler injector, so
 * this direction of injection is the legal one; the reverse would not be.
 *
 * <p><b>What is measured: passenger load only, never total load.</b> The caller reports the count
 * of vehicles busy on PASSENGER work - non-STAY and not holding an unperformed freight task, the
 * same {@link Modular#hasUnperformedFreightTask} predicate the dispatch lockout uses, so the
 * budget and the lockout can never disagree about what "on freight" means. This choice is the
 * load-bearing one: counting freight as "busy" would shrink the budget as freight is dispatched,
 * i.e. negative feedback converging to zero freight. Counting freight vehicles as available to
 * passengers instead makes this curve the in-run analogue of the Baseline's free-capacity curve,
 * which is the quantity the whole diagnosis rests on. This class does not compute that predicate
 * itself - it accepts the number - so that there is exactly ONE definition of "on freight" in the
 * codebase and it lives next to the lockout that enforces it.
 *
 * <p><b>Bin width is 900 s because that is the resolution at which the overhang was measured.</b>
 * Hourly bins hide the 10:15-10:45 peak entirely, which is precisely the part a coarser
 * instrument would have missed.
 *
 * <p><b>An unobserved bin is UNKNOWN, not zero.</b> This is the single most dangerous place in
 * the class to be sloppy: zero passenger load reads as "the whole fleet is free", the most
 * permissive answer the budget can give, and it would be produced by the most natural
 * implementation (a bare {@code double[]} that starts at 0.0). The failure would not be loud - it
 * would simply open the gate wide in exactly the bins nobody measured. Two mechanisms guard it:
 * <ul>
 *   <li>within an iteration, a bin that received no {@link #observe} call is stored as
 *       {@link Double#NaN}, never 0.0;</li>
 *   <li>across iterations, {@link #smoothedPassengerBusy(int)} averages ONLY over the buffered
 *       iterations that actually observed that bin. A naive mean over all {@code k} slots would
 *       divide a real observation by the number of iterations that never saw the bin - e.g. one
 *       observation of 30 busy vehicles with {@code k=5} would be published as 6, understating
 *       the passenger load fivefold and, again, in the permissive direction.</li>
 * </ul>
 * If NO buffered iteration observed the bin, the answer is {@code NaN} ("unknown") and
 * {@link #budget} returns {@link Double#POSITIVE_INFINITY}. That is deliberately permissive too,
 * but it is EXPLICIT permissiveness: the budget states that it has nothing to say about that bin
 * and the caller falls back to the theta gate alone, which is the documented bootstrap
 * behaviour - rather than silently asserting a load figure it never measured.
 *
 * <p><b>Bin-boundary convention: a tick exactly on a boundary belongs to the LATER bin.</b> Bins
 * are half-open {@code [i*900, (i+1)*900)}. {@code binOf(900.0) == 1}, not 0. Pinned by test so
 * that Task 3's span arithmetic and this class's accumulation can never drift apart.
 *
 * <p><b>Determinism.</b> No map iteration order and no RNG anywhere in this path: storage is a
 * plain {@code double[]} per iteration held in a fixed-size ring, and the smoothing sum runs the
 * kept iterations in CHRONOLOGICAL order (oldest kept first) rather than in ring-slot order.
 * Floating-point addition is not associative, so summing in slot order would make the result
 * depend on the ring's rotation; chronological order makes it depend only on which iterations are
 * kept. Not thread-safe, and does not need to be: DVRP's optimizer loop is single-threaded and
 * {@link #notifyIterationEnds} runs on the controler thread between mobsims.
 */
public final class PassengerLoadProfile implements IterationEndsListener {

    private static final Logger LOG = LogManager.getLogger(PassengerLoadProfile.class);

    /** Bin width in seconds. See the class javadoc: 900 s is the measurement resolution. */
    public static final double BIN_S = 900.0;

    /**
     * Number of bins: 144 * 900 s = 36 h.
     *
     * <p>The 1d delivery day ends at 21:00, but the horizon must not be sized off the delivery
     * window: the dispatch envelope admits a tour up to {@code latestEnd}, MATSim's conventional
     * simulation horizon runs to 30 h (06:00 of the following day) to let overnight activity and
     * vehicle service ends resolve, and Task 3 queries this profile for EVERY bin a candidate
     * tour would span - so the query range is "commitment time plus retooling plus tour duration",
     * not "delivery day". 36 h covers the 30 h horizon with six hours of slack at a cost of 144
     * doubles (1.2 kB) per buffered iteration, which is not worth economising on. Times beyond
     * the horizon are folded into the final bin (see {@link #binOf(double)}) rather than throwing
     * mid-mobsim.
     */
    public static final int BIN_COUNT = 144;

    /** Smoothing window: number of completed iterations averaged over. */
    private final int smoothingIterations;

    /**
     * Ring over the last {@code smoothingIterations} COMPLETED iterations. Slot content is a
     * per-bin mean passenger-busy count, with {@link Double#NaN} for bins that iteration never
     * observed. {@code null} slots are iterations that have not happened yet.
     */
    private final double[][] history;

    /** Running sum of the observations in the CURRENT (in-progress) iteration, per bin. */
    private final double[] currentSum = new double[BIN_COUNT];
    /** Number of observations in the CURRENT iteration, per bin; 0 = bin not observed yet. */
    private final int[] currentCount = new int[BIN_COUNT];

    /** Completed iterations rolled in so far; also drives the ring's write position. */
    private int completedIterations = 0;

    /** One-shot guard so a run past the 36 h horizon warns once, not once per simstep. */
    private boolean horizonWarned = false;

    /**
     * @param smoothingIterations {@code k}, the number of completed iterations the profile is
     *                            averaged over. {@code k=1} is "previous iteration only" - the
     *                            most responsive setting and the one most likely to oscillate,
     *                            since the dispatcher then reacts to a single noisy sample of a
     *                            system it is itself perturbing. Must be at least 1.
     */
    public PassengerLoadProfile(int smoothingIterations) {
        if (smoothingIterations < 1) {
            throw new IllegalArgumentException(
                    "PassengerLoadProfile smoothing window k must be >= 1 (k=1 means 'previous"
                            + " iteration only'); got " + smoothingIterations);
        }
        this.smoothingIterations = smoothingIterations;
        this.history = new double[smoothingIterations][];
        Arrays.fill(currentSum, 0.0);
    }

    /**
     * Records the passenger-busy vehicle count of the CURRENT iteration into the bin containing
     * {@code now}. Called by the dispatcher once per simstep, so a 900 s bin receives up to 900
     * observations; they are averaged, because the budget's unit is "vehicles busy during this
     * quarter hour", not "vehicles busy at the last instant of it". A single-simstep spike is
     * therefore damped WITHIN the bin as well as across iterations - deliberate: the budget
     * refuses commitments spanning hours, so it should be driven by the bin's typical load and
     * not by its worst simstep.
     *
     * @param now           simulation time in seconds.
     * @param passengerBusy vehicles busy on passenger work (non-STAY and not holding an
     *                      unperformed freight task). Must not be negative.
     */
    public void observe(double now, int passengerBusy) {
        if (passengerBusy < 0) {
            throw new IllegalArgumentException(
                    "passengerBusy must not be negative, got " + passengerBusy + " at t=" + now);
        }
        warnOnceIfBeyondHorizon(now);
        int bin = binOf(now);
        currentSum[bin] += passengerBusy;
        currentCount[bin]++;
    }

    /**
     * Rolls the current iteration's accumulated bins into the ring and resets the accumulator.
     *
     * <p>The accumulator is reset HERE and nowhere else. The in-progress iteration is deliberately
     * NOT part of {@link #smoothedPassengerBusy(int)}: including it would feed the dispatcher its
     * own freight decisions back within the same mobsim, turning a damped cross-iteration loop
     * into an undamped within-iteration one. This is the "previous iteration" in
     * "self-referential from iteration N-1".
     *
     * <p>An iteration in which nothing was observed still counts as completed and still occupies a
     * ring slot (all-NaN). That is intended: {@code k} such iterations legitimately evict all
     * knowledge and return the profile to the unknown/unbounded state, which is a more honest
     * answer than serving a stale curve indefinitely.
     */
    @Override
    public void notifyIterationEnds(IterationEndsEvent event) {
        double[] rolled = new double[BIN_COUNT];
        for (int bin = 0; bin < BIN_COUNT; bin++) {
            // NaN, never 0.0: "not observed" must not read as "no passenger load". See class doc.
            rolled[bin] = currentCount[bin] == 0 ? Double.NaN : currentSum[bin] / currentCount[bin];
        }
        history[completedIterations % smoothingIterations] = rolled;
        completedIterations++;
        Arrays.fill(currentSum, 0.0);
        Arrays.fill(currentCount, 0);
    }

    /**
     * False until at least one iteration has completed. While false the profile has no history at
     * all and {@link #budget} is unbounded, so behaviour falls back to the theta gate alone -
     * the documented iteration-0 bootstrap.
     */
    public boolean isBootstrapped() {
        return completedIterations > 0;
    }

    /** Completed iterations rolled in so far (diagnostics / KPI). */
    public int completedIterations() {
        return completedIterations;
    }

    /**
     * Freight capacity budget for a bin, in vehicles:
     * {@code fleetSize - smoothedPassengerBusy(bin) - headroomShare * fleetSize}.
     *
     * <p>{@code headroomShare} is a SHARE of fleet size, not an absolute vehicle count, and its
     * default is theta. That is what makes the first arm a one-factor experiment: with
     * {@code h = theta} the budget reproduces the calibrated gate in the CURRENT bin
     * ({@code idle/fleet > theta} is algebraically {@code freightBusy < fleet - passengerBusy -
     * theta*fleet}) and changes only the refusal of commitments that would collide in a LATER
     * bin. Setting {@code h = 0} would have altered the gate's sharpness and added look-ahead at
     * the same time, leaving the two effects inseparable afterwards.
     *
     * <p>Returns {@link Double#POSITIVE_INFINITY} when the profile cannot speak about the bin -
     * either because no iteration has completed yet, or because no buffered iteration observed
     * that bin. Unbounded means "no constraint from here", so the caller's remaining gates
     * (theta, {@code maxConcurrentFreight}, windows) decide alone. The value may also be NEGATIVE
     * where the passenger operation already uses more than {@code (1-h)} of the fleet; callers
     * must treat that as "no freight capacity", not clamp it to zero, because the magnitude is
     * the overhang this study is trying to measure.
     *
     * @param binIndex      bin, as produced by {@link #binOf(double)}.
     * @param fleetSize     total vehicles in the modular fleet; must be positive.
     * @param headroomShare reserve as a share of fleet size, in [0, 1].
     */
    public double budget(int binIndex, int fleetSize, double headroomShare) {
        if (fleetSize <= 0) {
            throw new IllegalArgumentException("fleetSize must be positive, got " + fleetSize);
        }
        if (!(headroomShare >= 0.0 && headroomShare <= 1.0)) {
            throw new IllegalArgumentException(
                    "headroomShare is a SHARE of fleet size and must lie in [0,1], got "
                            + headroomShare);
        }
        double smoothed = smoothedPassengerBusy(binIndex);
        if (Double.isNaN(smoothed)) {
            return Double.POSITIVE_INFINITY;
        }
        return fleetSize - smoothed - headroomShare * fleetSize;
    }

    /**
     * Mean passenger-busy count in a bin over the buffered COMPLETED iterations that actually
     * observed it, or {@link Double#NaN} if none did (see the class javadoc on unknown vs. zero).
     * The in-progress iteration is excluded.
     *
     * <p>Package-visible: Task 3's gate goes through {@link #budget}, and the KPI layer reports
     * the smoothed curve; nothing outside this package needs the raw quantity.
     */
    double smoothedPassengerBusy(int binIndex) {
        checkBin(binIndex);
        int kept = Math.min(completedIterations, smoothingIterations);
        double sum = 0.0;
        int observing = 0;
        for (int age = 0; age < kept; age++) {
            // Chronological order (oldest kept first), NOT slot order - see the determinism note
            // in the class javadoc.
            int slot = (completedIterations - kept + age) % smoothingIterations;
            double v = history[slot][binIndex];
            if (Double.isNaN(v)) {
                continue;
            }
            sum += v;
            observing++;
        }
        return observing == 0 ? Double.NaN : sum / observing;
    }

    /**
     * Bin containing {@code time}. Bins are half-open {@code [i*BIN_S, (i+1)*BIN_S)}, so a time
     * exactly ON a boundary belongs to the LATER bin - {@code binOf(900.0) == 1}. Package-visible
     * so Task 3's span arithmetic uses THIS function rather than deriving its own copy of the
     * convention; two independent implementations of a boundary rule is how off-by-one-bin
     * mismatches survive review.
     *
     * <p>Out-of-range times are clamped rather than rejected: a negative time is not reachable
     * from the mobsim clock, and a time past the 36 h horizon must not abort a 16 h run over an
     * accounting detail. The clamp folds the tail into the final bin. Note that the final bins
     * are in practice never observed, so a clamped QUERY reads as unknown -&gt; unbounded, which
     * is the safe direction (the theta gate still applies).
     */
    static int binOf(double time) {
        int bin = (int) Math.floor(time / BIN_S);
        if (bin < 0) {
            return 0;
        }
        if (bin >= BIN_COUNT) {
            return BIN_COUNT - 1;
        }
        return bin;
    }

    private void checkBin(int binIndex) {
        if (binIndex < 0 || binIndex >= BIN_COUNT) {
            throw new IllegalArgumentException("bin index out of range [0," + BIN_COUNT + "): "
                    + binIndex + " - callers must go through binOf(double)");
        }
    }

    /** Test/diagnostics hook: observations recorded in the in-progress iteration for a bin. */
    int currentIterationObservations(int binIndex) {
        checkBin(binIndex);
        return currentCount[binIndex];
    }

    /**
     * One-shot horizon warning, kept OUT of {@link #binOf} so that stays a pure function usable
     * from Task 3's span loop without emitting a log line per candidate bin.
     */
    private void warnOnceIfBeyondHorizon(double time) {
        if (!horizonWarned && time >= BIN_COUNT * BIN_S) {
            horizonWarned = true;
            LOG.warn("PassengerLoadProfile: time {} is past the {} h horizon; it and all later"
                    + " times are folded into the final bin.", time, BIN_COUNT * BIN_S / 3600.0);
        }
    }
}
