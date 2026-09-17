package hagrid.integrated.modular;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.drt.scheduler.DrtScheduleInquiry;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;
import org.matsim.contrib.dvrp.fleet.Fleet;
import org.matsim.contrib.dvrp.schedule.StayTask;
import org.matsim.contrib.dvrp.schedule.Task;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.utils.geometry.CoordUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Online freight dispatcher (design §3.3): passenger-primary via the idle-share gate.
 * QSim-scoped -&gt; all state resets per iteration by construction (the 1c dd34b23 lesson).
 *
 * <p>Tick order (ModularOptimizer calls {@link #dispatch(double)} BEFORE the delegate's
 * rebalancing runs, so a just-spliced vehicle is already non-idle when MinCostFlow/ReturnToDepot
 * look for vehicles):
 * <ol>
 *   <li>activate tours whose submissionTime has arrived (PLANNED event),</li>
 *   <li>expire pending tours whose completion envelope has passed (EXPIRED - explicit
 *       reject-and-log, never the native silent drop; no replanning, spec §4.3 step 5),</li>
 *   <li>while idleShare &gt; idleThreshold (STRICT - theta=1.0 is the never-dispatch control arm):
 *       dispatch the longest-pending tour to the idle vehicle nearest its depot (deterministic
 *       tie-break by vehicle id). Pending order is (submissionTime, tourIndex, provider) -
 *       plan C7: with the day window ALL tours become pending at ~07:16, so a plain tourId sort
 *       would dispatch every dhl tour before the first gls tour and bias per-provider delta;
 *       interleaving by tour index removes that.</li>
 * </ol>
 *
 * <p><b>Two different envelopes, two different failures (review Finding 3).</b> Step 2's expiry
 * check and the splicer's feasibility check are NOT the same test, and a tour can pass the first
 * while failing the second on every attempt until it expires:
 * <ul>
 *   <li>expiry (here) uses {@link #chainDuration} against {@link #deadlineCap} — since
 *       2026-09-05 the learned routed excursion length, or the free-flow DRT-network lower bound
 *       times {@link Modular#CHAIN_BOOTSTRAP_FACTOR} for a tour never yet dispatched;</li>
 *   <li>the splicer ({@link ModularTourScheduler#schedule}) uses the actual routed completion on
 *       the <b>DRT</b> network <i>plus</i> the approach leg from wherever the candidate vehicle
 *       happens to be.</li>
 * </ul>
 * <p>Until 2026-09-05 the first was {@code 2*RETOOLING_S + plannedDuration} — jsprit's sum over
 * the <b>car</b> network, from the depot, no approach leg — and was optimistic against the
 * splicer by a measured median of 943 s (p90 1635 s, max 2403 s). That gap is what killed the
 * first budget arm: the gate held tours back, the binary "last opportunity" override fired
 * against the optimistic deadline, and the splicer refused it in the same second, so 31 of 46
 * tours expired. See {@link #chainDuration} for the fix and {@link #urgencyOffset} for why the
 * override became a ramp. The two envelopes are still not the same test, so a splicer rejection
 * is still reported separately (METHODS-LOG 2.18).
 * A splicer rejection therefore emits {@link ModularTourEvent.Phase#SPLICE_REJECTED} (once per
 * tour) and feeds {@code tours_rejected_at_splice}. Without it the tour later trips expiry and is
 * published as {@code tours_expired_pending}, attributing to "the gate was too tight" what was
 * really "the tour never fit" — and those call for opposite responses (lower θ vs. loosen the
 * tour cap), on the sweep that is this study's main 1d instrument.
 *
 * <p><b>The look-ahead capacity budget (plan 2026-09-04, Task 3).</b> Optional and OFF when the
 * injected {@link PassengerLoadProfile} is {@code null}. When on it is one MORE conjunct, never a
 * replacement: theta, {@code maxConcurrentFreight} and the windows all still apply exactly as
 * before, and every one of them can still be the binding constraint. What the budget adds is the
 * only thing a scalar cap structurally cannot do — refusing a commitment now because of a
 * collision with the passenger operation HOURS later. See {@link #budgetAdmitsSpan} for the span
 * arithmetic, {@link #urgencyOffset} for how an approaching deadline unlocks the reserve, and
 * {@link PassengerLoadProfile} for why the profile is read from the run's own previous iteration
 * rather than from the passenger-only Baseline arm.
 *
 * <p>The morning surge is accepted, not fixed: with the full delivery-day window every tour's
 * submissionTime falls at ~07:16, the fleet is still fully idle at that hour, and this gate's
 * while-loop therefore dispatches in ONE simstep until idleShare drops to theta - roughly
 * {@code (1 - theta) * fleetSize} vehicles leave at once. This is a deliberate user decision
 * ("erst Ergebnisse ansehen" - look at results first), not an oversight: no dispatch-rate limit,
 * demand forecast, or smoothing is added here; the theta-sweep plus passenger KPIs are the
 * intended instrument for making that cost visible, not a defect for a future reader to "fix".
 */
public class ModularTourDispatcher {

    private static final Logger LOG = LogManager.getLogger(ModularTourDispatcher.class);

    private final String mode;
    private final List<ModularFreightTour> tours;      // sorted by (submissionTime, tourIndex, provider) - C7
    private final double idleThreshold;
    /** 0 = unlimited, see {@link Modular#DEFAULT_MAX_CONCURRENT_FREIGHT}. */
    private final int maxConcurrentFreight;
    /** Empty = always open. */
    private final List<Modular.DispatchWindow> windows;
    private final Fleet fleet;
    private final DrtScheduleInquiry scheduleInquiry;
    private final ModularTourScheduler scheduler;
    private final Network network;
    private final EventsManager events;

    /**
     * Self-referential passenger-load profile, or {@code null} when the budget is OFF
     * ({@code budgetMode=off}, the default). {@code null} is a deliberate sentinel rather than a
     * "null object" with an infinite budget: the off path must not merely produce the same
     * decisions, it must not execute the budget code at all — no per-tick fleet scan for
     * {@link #countPassengerBusy()}, no span loop, no bin bookkeeping — so that a run with the
     * feature off is byte-identical AND costs nothing.
     */
    private final PassengerLoadProfile profile;
    /** Reserve held back in every spanned bin, as a SHARE of fleet size. Run default = theta. */
    private final double headroomShare;

    /**
     * Learned per-tour routed excursion length, or {@code null} to use the bootstrap for every
     * tour (the older constructors, i.e. the unit tests).
     *
     * <p>Unlike {@link #profile} this is NOT gated on {@code budgetMode}: the honest chain
     * duration is a correctness fix, not a feature of the budget arm. See
     * {@link #chainDuration(ModularFreightTour)}.
     */
    private final FreightChainProfile chainProfile;

    /**
     * Seconds of remaining slack over which a pending tour's urgency ramps from 0 to the full
     * headroom reserve. See {@link #urgencyOffset} and {@link Modular#DEFAULT_BUDGET_URGENCY_LEAD_S}.
     */
    private final double urgencyLeadS;

    /**
     * {@code max} over the fleet of {@code getServiceEndTime()}, computed on first use and then
     * cached: vehicle service ends do not change within an iteration, and this class is rebuilt
     * every iteration. NaN = not yet computed.
     */
    private double maxServiceEndCache = Double.NaN;

    /**
     * Vehicles this dispatcher has already committed to freight in each 900 s bin, i.e. the
     * left-hand side of the budget inequality. QSim-scoped like everything else here, so it
     * resets per iteration by construction (the 1c {@code dd34b23} lesson) — deliberately NO
     * cross-iteration state lives in this class; the only thing that survives an iteration is
     * {@link PassengerLoadProfile}, which is controler-scoped for exactly that reason.
     *
     * <p>Bins are absolute simulation time, never relative to "now", so entries for bins already
     * past are simply never consulted again; nothing has to be aged out.
     */
    private final int[] projectedFreight = new int[PassengerLoadProfile.BIN_COUNT];

    /**
     * The budget's two dispatch counters ({@code budget_blocked_dispatches} /
     * {@code budget_overrides_expiry}, Task 6). NEVER null: when the caller supplies none - the
     * older constructors, i.e. every budget-OFF path - this class allocates a private one, so the
     * two getters below always have somewhere to read from and the increment sites need no null
     * check. Storage lives HERE and nowhere else: when the module supplies the controller-scoped
     * singleton, that object IS this field, so the published CSV numbers and the numbers this
     * class's own tests assert cannot be two copies that drift apart. See
     * {@link ModularBudgetStats} for why the crossing works this way at all.
     */
    private final ModularBudgetStats budgetStats;

    /**
     * Simulation time of the previous {@link #dispatch(double)} tick, or NaN before the first.
     * Only used to estimate the spacing to the NEXT tick.
     */
    private double previousTickTime = Double.NaN;
    /**
     * Observed spacing between consecutive dispatcher ticks, seeded with MATSim's default 1 s
     * simstep and replaced by the measured spacing from the second tick onward.
     *
     * <p><b>Restored 2026-09-06 after it was wrongly deleted.</b> The 2026-09-05 plan removed the
     * tick spacing on the reasoning that the urgency ramp had replaced the binary override, so no
     * decision depended on the discrete clock any more. That was wrong, and {@code d1d_f130_bud2}
     * proved it: the ramp tops out at {@code headroomShare * fleetSize} and cannot carry a tour
     * the passenger load has priced out, so the terminal {@code +INFINITY} branch is still the
     * last resort - and with the deadline a floating-point quantity it falls BETWEEN two ticks, so
     * {@code slack <= 0} was never observed. The arm dispatched 30 of 46 tours with
     * {@code budget_overrides_expiry == 0}: the branch existed and was unreachable. See
     * {@link #urgencyOffset}.
     */
    private double observedSimstepS = 1.0;

    private int nextToActivate = 0;
    private final List<ModularFreightTour> pending = new ArrayList<>();
    /**
     * Tour ids already reported as rejected by the splicer, so the SPLICE_REJECTED event and its
     * log line fire ONCE per tour rather than once per retry — a tour the splicer keeps refusing
     * is re-offered every simstep the gate is open. QSim-scoped like the rest of this class, so
     * it resets per iteration by construction.
     */
    private final Set<String> spliceRejected = new LinkedHashSet<>();

    /** Gate with theta only: no concurrency cap, always open. Behaviour before 2026-08-30. */
    public ModularTourDispatcher(String mode, List<ModularFreightTour> tours, double idleThreshold,
                                 Fleet fleet, DrtScheduleInquiry scheduleInquiry,
                                 ModularTourScheduler scheduler, Network network,
                                 EventsManager events) {
        this(mode, tours, idleThreshold, Modular.DEFAULT_MAX_CONCURRENT_FREIGHT, List.of(),
                fleet, scheduleInquiry, scheduler, network, events);
    }

    /** Gate with theta + cap + windows, budget OFF. Behaviour before 2026-09-04. */
    public ModularTourDispatcher(String mode, List<ModularFreightTour> tours, double idleThreshold,
                                 int maxConcurrentFreight, List<Modular.DispatchWindow> windows,
                                 Fleet fleet, DrtScheduleInquiry scheduleInquiry,
                                 ModularTourScheduler scheduler, Network network,
                                 EventsManager events) {
        this(mode, tours, idleThreshold, maxConcurrentFreight, windows, null, 0.0,
                fleet, scheduleInquiry, scheduler, network, events);
    }

    /**
     * Full gate with the look-ahead budget but PRIVATE counters (the pre-Task-6 form): the two
     * budget counters are kept, and {@link #budgetBlockedDispatches()} /
     * {@link #budgetOverridesExpiry()} read them, but nothing outside this instance can see them -
     * so a run built this way publishes no budget rows. Production always goes through the
     * 13-argument form below.
     */
    public ModularTourDispatcher(String mode, List<ModularFreightTour> tours, double idleThreshold,
                                 int maxConcurrentFreight, List<Modular.DispatchWindow> windows,
                                 PassengerLoadProfile profile, double headroomShare,
                                 Fleet fleet, DrtScheduleInquiry scheduleInquiry,
                                 ModularTourScheduler scheduler, Network network,
                                 EventsManager events) {
        this(mode, tours, idleThreshold, maxConcurrentFreight, windows, profile, headroomShare,
                null, fleet, scheduleInquiry, scheduler, network, events);
    }

    /**
     * The 13-argument form, kept so every pre-2026-09-05 caller and test compiles unchanged: no
     * {@link FreightChainProfile} (every tour bootstraps) and the default urgency lead.
     */
    public ModularTourDispatcher(String mode, List<ModularFreightTour> tours, double idleThreshold,
                                 int maxConcurrentFreight, List<Modular.DispatchWindow> windows,
                                 PassengerLoadProfile profile, double headroomShare,
                                 ModularBudgetStats budgetStats,
                                 Fleet fleet, DrtScheduleInquiry scheduleInquiry,
                                 ModularTourScheduler scheduler, Network network,
                                 EventsManager events) {
        this(mode, tours, idleThreshold, maxConcurrentFreight, windows, profile, headroomShare,
                budgetStats, null, Modular.DEFAULT_BUDGET_URGENCY_LEAD_S,
                fleet, scheduleInquiry, scheduler, network, events);
    }

    /**
     * Full gate: theta, concurrency cap, windows, the look-ahead capacity budget AND the honest
     * dispatch envelope (plan 2026-09-05).
     *
     * @param profile       self-referential passenger-load profile, or {@code null} to switch the
     *                      budget off entirely ({@code budgetMode=off}).
     * @param headroomShare reserve held back in every spanned bin, as a share of fleet size, in
     *                      [0,1]. The run default is theta (0.15), which makes the first arm a
     *                      one-factor experiment — but this class deliberately does NOT know that
     *                      number; it uses whatever it is handed, so the sensitivity sweep
     *                      (plan Task 8) needs no code change here.
     * @param budgetStats   the controller-scoped counter object the KPI layer publishes from
     *                      (Task 6), or {@code null} for a private, unpublished one. This is the
     *                      ONLY storage for the budget counters — see the field's javadoc.
     * @param chainProfile  learned per-tour routed excursion length, or {@code null} to bootstrap
     *                      every tour from {@code minimumChainDurationS}. Deliberately NOT gated
     *                      on {@code budgetMode}: see {@link #chainDuration}.
     * @param urgencyLeadS  slack over which urgency ramps to the full reserve; must be positive.
     */
    public ModularTourDispatcher(String mode, List<ModularFreightTour> tours, double idleThreshold,
                                 int maxConcurrentFreight, List<Modular.DispatchWindow> windows,
                                 PassengerLoadProfile profile, double headroomShare,
                                 ModularBudgetStats budgetStats,
                                 FreightChainProfile chainProfile, double urgencyLeadS,
                                 Fleet fleet, DrtScheduleInquiry scheduleInquiry,
                                 ModularTourScheduler scheduler, Network network,
                                 EventsManager events) {
        this.budgetStats = budgetStats != null ? budgetStats : new ModularBudgetStats();
        this.chainProfile = chainProfile;
        if (!(urgencyLeadS > 0.0) || Double.isInfinite(urgencyLeadS)) {
            // Zero would divide by zero in the ramp and, worse, would silently restore the binary
            // override this plan exists to remove. Fail at wiring time, not on the first tick.
            throw new IllegalArgumentException(
                    "budget urgency lead must be positive and finite (seconds of slack over which"
                            + " urgency ramps to the full reserve), got " + urgencyLeadS);
        }
        this.urgencyLeadS = urgencyLeadS;
        if (!(headroomShare >= 0.0 && headroomShare <= 1.0)) {
            // Checked here and not only inside PassengerLoadProfile.budget: a bad share must fail
            // at wiring time, not on the first tick of a 16 h mobsim.
            throw new IllegalArgumentException(
                    "budget headroom is a SHARE of fleet size and must lie in [0,1], got "
                            + headroomShare);
        }
        this.profile = profile;
        this.headroomShare = headroomShare;
        this.mode = mode;
        this.maxConcurrentFreight = maxConcurrentFreight;
        this.windows = List.copyOf(windows);
        this.tours = tours.stream()
                .sorted(Comparator.comparingDouble(ModularFreightTour::submissionTime)
                        .thenComparingInt(ModularFreightTour::tourIndex)     // C7 interleave
                        .thenComparing(ModularFreightTour::provider))
                .toList();
        this.idleThreshold = idleThreshold;
        this.fleet = fleet;
        this.scheduleInquiry = scheduleInquiry;
        this.scheduler = scheduler;
        this.network = network;
        this.events = events;
    }

    public void dispatch(double now) {
        // Tick spacing first: measured, not assumed - see observedSimstepS and urgencyOffset.
        if (!Double.isNaN(previousTickTime) && now > previousTickTime) {
            observedSimstepS = now - previousTickTime;
        }
        previousTickTime = now;

        // The profile observation happens HERE, at the very top, BEFORE every early return in
        // this method (empty pending list, everything expired, shut window). Those returns are
        // about FREIGHT having nothing to do; the passenger load is a property of the fleet and
        // exists regardless - and the bins the early returns cover are precisely the quiet
        // freight hours whose passenger load the budget most needs in order to say anything at
        // all about later iterations. A bin left unobserved is NaN, i.e. "unknown", which makes
        // budget() unbounded there: skipping observations would therefore not fail loudly, it
        // would silently switch the budget off in exactly those bins.
        if (profile != null) {
            profile.observe(now, countPassengerBusy());
        }

        while (nextToActivate < tours.size()
                && tours.get(nextToActivate).submissionTime() <= now) {
            ModularFreightTour t = tours.get(nextToActivate++);
            pending.add(t);
            events.processEvent(ModularTourEvent.planned(now, t.tourId(), t.totalParcels()));
        }
        if (pending.isEmpty()) return;

        // C4 envelope: even an immediate dispatch could not finish anymore. Uses the HONEST chain
        // duration and the HONEST cap (plan 2026-09-05) - see chainDuration/deadlineCap for what
        // the previous "2*RETOOLING_S + plannedDuration > latestEnd" left out and what that cost.
        pending.removeIf(t -> {
            if (now + chainDuration(t) > deadlineCap(t)) {
                // review Finding 1: `mode` earns its place in the log line here - a multi-mode
                // DRT run would otherwise emit expiry warnings with no indication of which mode
                // they came from.
                LOG.warn("Modular tour {} (mode {}) expired pending at {} (chain {} s, cap {} s,"
                                + " latestEnd {}).",
                        t.tourId(), mode, now, chainDuration(t), deadlineCap(t), t.latestEnd());
                events.processEvent(ModularTourEvent.expired(now, t.tourId(), t.totalParcels()));
                return true;
            }
            return false;
        });
        if (pending.isEmpty()) return;

        // Time window (2026-08-30). Deliberately AFTER the expiry sweep above: a tour whose
        // envelope has passed must still be reported as expired while the window is shut, not
        // silently held. Empty window list = always open.
        if (!windows.isEmpty() && windows.stream().noneMatch(w -> w.contains(now))) {
            return;
        }

        List<DvrpVehicle> idle = fleet.getVehicles().values().stream()
                .filter(scheduleInquiry::isIdle)
                .filter(v -> !Modular.hasUnperformedFreightTask(v.getSchedule()))
                .sorted(Comparator.comparing(v -> v.getId().toString()))
                .collect(Collectors.toCollection(ArrayList::new));
        int fleetSize = fleet.getVehicles().size();

        // Vehicles already committed to freight, counted over the WHOLE fleet - not over `idle`,
        // which excludes them by construction. Same commitment predicate as the idle filter, so
        // cap and lockout can never disagree about what "on freight" means.
        long committed = fleet.getVehicles().values().stream()
                .filter(v -> Modular.hasUnperformedFreightTask(v.getSchedule()))
                .count();

        Iterator<ModularFreightTour> it = pending.iterator();
        while (it.hasNext() && !idle.isEmpty()
                && (maxConcurrentFreight <= 0 || committed < maxConcurrentFreight)
                && (double) idle.size() / fleetSize > idleThreshold) {
            ModularFreightTour tour = it.next();

            if (profile != null) {
                double urgency = urgencyOffset(tour, now, fleetSize);
                if (!budgetAdmitsSpan(tour, now, fleetSize, urgency)) {
                    // Refused for THIS tour only, not for the simstep: the loop moves on to the
                    // next pending tour. That is what a look-ahead budget is for - a 3.5 h tour
                    // colliding with the 10:15 passenger peak must not also block the 40-minute
                    // tour behind it, which fits in the same gap. See the class javadoc; it is
                    // also why the budget is NOT literally a conjunct of the while-condition the
                    // way theta and the cap are (its predicate depends on the candidate tour).
                    budgetStats.recordBlockedDispatch();
                    continue;
                }
                // Admitted. Which of the three regimes carried it is what makes the arm
                // interpretable, so both non-trivial ones are counted (plan §3): a run where
                // urgencyAdmits is 0 never exercised the ramp, and a run where expiryOverrides is
                // large is the old theta gate wearing a new name.
                if (urgency > 0.0 && !budgetAdmitsSpan(tour, now, fleetSize, 0.0)) {
                    if (Double.isInfinite(urgency)) {
                        budgetStats.recordExpiryOverride();
                        LOG.warn("Modular tour {} (mode {}) dispatched at {} AGAINST the capacity"
                                + " budget: its honest deadline ({} s, chain {} s) has passed, so"
                                + " there is no later opportunity. Expiry beats the budget - an"
                                + " arm that drops parcels answers no question. Counted in"
                                + " budget_overrides_expiry.",
                                tour.tourId(), mode, now, dispatchDeadline(tour),
                                chainDuration(tour));
                    } else {
                        budgetStats.recordUrgencyAdmit();
                    }
                }
            }

            DvrpVehicle vehicle = nearestToDepot(idle, tour);
            Optional<ModularTourScheduler.ScheduledExcursion> excursion =
                    scheduler.schedule(vehicle, tour, now);
            if (excursion.isPresent()) {
                idle.remove(vehicle);
                it.remove();
                committed++;
                // Booked AFTER the splicer accepted, never before: a tour the splicer refuses is
                // not occupying anything, and pre-booking it would let a rejected candidate crowd
                // out the tours behind it for the rest of the day.
                if (profile != null) {
                    bookProjectedFreight(tour, now);
                }
                // The honest chain duration, measured (plan 2026-09-05 Fix 1). Recorded AFTER the
                // splicer accepted, so every sample describes a chain that was really scheduled -
                // and recorded unconditionally, because the learned envelope is not a feature of
                // the budget arm. The ratio against the free-flow lower bound is what retires
                // CHAIN_BOOTSTRAP_FACTOR with data instead of leaving it a guess.
                if (chainProfile != null) {
                    chainProfile.observe(tour.tourId(), excursion.get().routedDurationS());
                }
                budgetStats.recordChainRatio(
                        excursion.get().routedDurationS() / scheduler.minimumChainDurationS(tour));
                events.processEvent(ModularTourEvent.dispatched(now, tour.tourId(),
                        vehicle.getId(), tour.totalParcels(),
                        excursion.get().deadheadMeters(), excursion.get().serviceMeters(),
                        tour.plannedDuration(), excursion.get().routedDurationS()));
            } else if (spliceRejected.add(tour.tourId())) {
                // Review Finding 3: this branch used to be EMPTY - no event, no log, no counter.
                // The tour stayed pending and, when it later tripped the expiry check, was
                // published as tours_expired_pending / delta_share_undispatched, i.e. as
                // "the gate was too tight". It is not the same failure, and confusing the two
                // points the theta sweep - the study's main 1d instrument - at the wrong knob:
                // "lower theta" is the answer to a gate that is too tight, "loosen the tour cap"
                // to a tour that never fit. Why the two envelopes are different tests is stated
                // ONCE, in this class's javadoc; it is deliberately not restated here (that
                // duplicate is how the retracted F2 claim - "the splicer's number is always
                // larger" - survived the javadoc's own correction, see METHODS-LOG 2.18).
                LOG.warn("Modular tour {} (mode {}) rejected by the splicer at {} on candidate"
                        + " vehicle {}: the DRT-routed completion exceeds min(latestEnd {},"
                        + " vehicle service end). Tour stays pending; this is NOT the pending"
                        + " expiry check, which passed.",
                        tour.tourId(), mode, now, vehicle.getId(), tour.latestEnd());
                events.processEvent(ModularTourEvent.spliceRejected(now, tour.tourId(),
                        vehicle.getId(), tour.totalParcels()));
            }
            // infeasible for the nearest vehicle -> stays pending; expiry (above) is the exit
        }
    }

    // ------------------------------------------------------------------ capacity budget (Task 3)

    /**
     * Vehicles busy on PASSENGER work: not idle per {@link DrtScheduleInquiry#isIdle}, AND not
     * holding an unperformed freight task per {@link Modular#hasUnperformedFreightTask}.
     *
     * <p>The second clause is the load-bearing one and it is deliberately NOT "busy". Counting a
     * freight-committed vehicle as busy would shrink the budget as freight is dispatched —
     * negative feedback that converges to zero freight, and it would do so quietly, looking like
     * "the passenger operation is simply very busy". Counting freight vehicles as available to
     * passengers instead makes this curve the in-run analogue of the passenger-only Baseline's
     * free-capacity curve, which is the quantity the whole diagnosis rests on. It is the same
     * predicate the D2 lockout uses, so the budget and the lockout can never disagree about what
     * "on freight" means.
     *
     * <p>Note this is NOT {@code fleetSize - idle.size()}: a freight-committed vehicle is neither
     * idle nor passenger-busy, so the two disagree by exactly the freight commitment — which is
     * the whole point.
     */
    private int countPassengerBusy() {
        int busy = 0;
        for (DvrpVehicle v : fleet.getVehicles().values()) {
            if (!scheduleInquiry.isIdle(v) && !Modular.hasUnperformedFreightTask(v.getSchedule())) {
                busy++;
            }
        }
        return busy;
    }

    /**
     * True if committing {@code tour} at {@code now} leaves every bin it would span within budget:
     * {@code projectedFreight(bin) + 1 <= profile.budget(bin, fleetSize, headroomShare)} for every
     * bin from {@code binOf(now)} to {@code binOf(now + 2*RETOOLING_S + plannedDuration)}
     * inclusive.
     *
     * <p><b>Why the span and not just the current bin.</b> A commitment made at 08:00 occupies the
     * vehicle until ~11:30. A scalar cap can only ask "how many vehicles are on freight right
     * now"; it structurally cannot refuse a commitment because of a collision three hours later,
     * and that refusal is the entire mechanism this arm exists to test.
     *
     * <p><b>Span, not routed duration.</b> The occupancy booked on dispatch
     * ({@link #bookProjectedFreight}) uses the SAME expression, although by then the splicer has
     * already returned a more accurate DRT-routed duration. Deliberate: booking a longer span than
     * the one that was checked would let a tour pass the gate and then occupy bins the gate never
     * looked at. Both sides use jsprit's car-network figure so that "what was checked" and "what
     * was booked" are the same quantity.
     *
     * <p>{@code budget()} returns {@link Double#POSITIVE_INFINITY} for a bin the profile cannot
     * speak about (not bootstrapped, or never observed), so {@code x > POSITIVE_INFINITY} is false
     * and such a bin admits everything — the documented bootstrap fallback to theta alone. It may
     * also return a NEGATIVE value where the passenger operation already exceeds {@code (1-h)} of
     * the fleet; that is compared as-is and simply blocks. It must NOT be clamped to zero: the
     * magnitude is the overhang this study measures, and Task 6 publishes it.
     */
    private boolean budgetAdmitsSpan(ModularFreightTour tour, double now, int fleetSize,
                                     double urgency) {
        int firstBin = PassengerLoadProfile.binOf(now);
        int lastBin = PassengerLoadProfile.binOf(spanEnd(tour, now));
        for (int bin = firstBin; bin <= lastBin; bin++) {
            if (projectedFreight[bin] + 1
                    > profile.budget(bin, fleetSize, headroomShare) + urgency) {
                return false;
            }
        }
        return true;
    }

    /** Books one vehicle in every bin the dispatched tour spans. Same span as the gate checked. */
    private void bookProjectedFreight(ModularFreightTour tour, double now) {
        int firstBin = PassengerLoadProfile.binOf(now);
        int lastBin = PassengerLoadProfile.binOf(spanEnd(tour, now));
        for (int bin = firstBin; bin <= lastBin; bin++) {
            projectedFreight[bin]++;
        }
    }

    /**
     * Latest instant the vehicle would still be bound by this tour: the HONEST chain duration
     * (approach, both retoolings, service, return) added to {@code now}.
     *
     * <p>Deliberately no longer {@code 2*RETOOLING_S + plannedDuration}: {@link #chainDuration}
     * already includes both retoolings, so adding them again here would double-count them.
     */
    private double spanEnd(ModularFreightTour tour, double now) {
        return now + chainDuration(tour);
    }

    /**
     * How long this tour will actually hold a vehicle, in seconds - the one quantity every
     * deadline question in this class goes through (plan 2026-09-05, Fix 1).
     *
     * <p><b>What it replaced, and what that cost.</b> Until 2026-09-05 the answer was
     * {@code 2*RETOOLING_S + }{@link ModularFreightTour#plannedDuration()}: jsprit's sum over the
     * <b>car</b> network, starting at the depot, with no approach leg. The splicer meanwhile
     * decides with the DRT-routed completion including the approach. Measured on the anchor arm
     * (it.250, all 46 tours) the old expression is optimistic by a median of 943 s, a p90 of
     * 1635 s and a max of 2403 s. {@code d1d_f130_bud} died of exactly that gap: the budget held
     * tours back, the "last opportunity" override fired against the optimistic deadline, and the
     * splicer refused the override in the same second - 31 of 46 tours expired.
     *
     * <p><b>Two sources, in order.</b>
     * <ol>
     *   <li>{@link FreightChainProfile#estimate} - the longest routed excursion actually observed
     *       for this tour in the buffered iterations. Measured, so it needs no safety factor.</li>
     *   <li>{@link ModularTourScheduler#minimumChainDurationS} times
     *       {@link Modular#CHAIN_BOOTSTRAP_FACTOR}, for a tour no buffered iteration dispatched.
     *       The bound is free-flow travel on the DRT network over the real stop sequence, both
     *       retoolings and all service times - it omits only the approach leg and congestion,
     *       which is what the factor covers.</li>
     * </ol>
     *
     * <p><b>Not gated on the budget.</b> This is a correctness fix, not a feature of the budget
     * arm: the expiry sweep uses it whether or not a {@link PassengerLoadProfile} is bound.
     * Gating it would mean the budget arm differs from every existing arm in TWO ways and could
     * never be compared one-factor. Expected to be inert on arms with generous slack - the anchor
     * dispatches all 46 tours at ~07:16 against a 21:00 {@code latestEnd}, i.e. ~10 h of slack
     * against a ~3.8 h chain, and expires zero - which is an assumption pinned by test, not a
     * re-measured fact.
     */
    private double chainDuration(ModularFreightTour tour) {
        double learned = chainProfile == null ? Double.NaN : chainProfile.estimate(tour.tourId());
        if (!Double.isNaN(learned)) {
            return learned;
        }
        return scheduler.minimumChainDurationS(tour) * Modular.CHAIN_BOOTSTRAP_FACTOR;
    }

    /**
     * The latest instant this tour may still be COMPLETED: {@code min(latestEnd, maxServiceEnd)}.
     *
     * <p>The splicer's own envelope is {@code min(tour.latestEnd(),
     * vehicle.getServiceEndTime())} ({@code ModularTourScheduler:121}); the dispatcher used to
     * compare against {@code latestEnd} alone, so a fleet whose vehicles end service before
     * {@code latestEnd} would keep tours pending that no vehicle could take. The maximum over the
     * fleet is the right reduction here and not the candidate's own service end: this cap is
     * evaluated in the expiry sweep, before any vehicle has been chosen, and a tour is dead only
     * when NO vehicle could still do it.
     */
    private double deadlineCap(ModularFreightTour tour) {
        return Math.min(tour.latestEnd(), maxServiceEnd());
    }

    /** Latest instant a dispatch may still START. Slack is this minus {@code now}. */
    private double dispatchDeadline(ModularFreightTour tour) {
        return deadlineCap(tour) - chainDuration(tour);
    }

    /**
     * Extra budget, in vehicles, granted to a tour whose deadline is approaching (plan
     * 2026-09-05, Fix 2 - the user's "penalty for expiring tours", as a ramp).
     *
     * <pre>{@code
     * slack   = dispatchDeadline(tour) - now
     * urgency = 0                                            slack >= lead
     *         = (1 - slack/lead) * headroomShare * fleetSize  0 < slack < lead
     *         = +INFINITY                                    slack <= 0
     * }</pre>
     *
     * <p><b>Why the ramp tops out at exactly the headroom reserve.</b> The headroom IS a reserve,
     * and urgency is what unlocks it. At full ramp the gate reads
     * {@code projectedFreight + 1 <= fleetSize - passengerBusy}, i.e. "admit iff a vehicle is
     * genuinely free in that bin" - no reserve held back, but no overcommitment either. That
     * endpoint falls out of the arithmetic rather than being chosen, so the ramp introduces no
     * scale constant of its own; {@code lead} is its only parameter.
     *
     * <p><b>Why a ramp at all, rather than the binary override it replaces.</b> The old override
     * fired at the last instant the envelope allowed and the splicer refused it in the same
     * second (see {@link #chainDuration}). A ramp removes the knife edge: pressure builds over
     * {@code lead} seconds, so the tour leaves while the splicer can still place it, and the
     * precision demanded of the deadline drops from minutes to order-of-magnitude. The
     * {@code +INFINITY} branch is the old override, kept - now against an honest deadline, and
     * still counted, because "the budget refused and the deadline overrode it anyway" must stay
     * visible: a large count still means the budget was decorative.
     *
     * <p><b>Ordering.</b> The pending list keeps its {@code (submissionTime, tourIndex, provider)}
     * C7 order and is NOT re-sorted by urgency. Ordering emerges from admission instead - a less
     * urgent tour earlier in the list is refused where a more urgent one later passes - which
     * gets the effect without disturbing the interleave that exists to keep per-provider delta
     * unbiased.
     */
    private double urgencyOffset(ModularFreightTour tour, double now, int fleetSize) {
        double slack = dispatchDeadline(tour) - now;
        if (slack >= urgencyLeadS) {
            return 0.0;
        }
        // THE LAST-TICK TEST, not the last-instant test (restored 2026-09-06). This simstep is the
        // tour's final opportunity exactly when the NEXT tick would find it already expired, i.e.
        // when the remaining slack no longer covers one tick. Writing `slack <= 0` instead - which
        // is what the 2026-09-05 version did - makes the branch UNREACHABLE in practice: the
        // deadline is latestEnd minus a learned floating-point chain duration, so it lands
        // between two integer-second ticks. At the tick before, slack is a positive fraction and
        // only the ramp applies; at the tick after, the expiry sweep at the top of dispatch() has
        // already removed the tour and the gate never sees it again. d1d_f130_bud2 published
        // budget_overrides_expiry == 0 while losing 16 of 46 tours - the branch was there and
        // never fired once.
        if (slack <= observedSimstepS) {
            return Double.POSITIVE_INFINITY;
        }
        return (1.0 - slack / urgencyLeadS) * headroomShare * fleetSize;
    }

    /**
     * {@code max} over the fleet of {@code getServiceEndTime()}, computed once per iteration.
     * Cached because this class is QSim-scoped (rebuilt every iteration) and the fleet's service
     * ends are fixed within one, while {@link #deadlineCap} is called per pending tour per
     * simstep.
     */
    private double maxServiceEnd() {
        if (Double.isNaN(maxServiceEndCache)) {
            double max = Double.NEGATIVE_INFINITY;
            for (DvrpVehicle v : fleet.getVehicles().values()) {
                max = Math.max(max, v.getServiceEndTime());
            }
            maxServiceEndCache = max;
        }
        return maxServiceEndCache;
    }

    /** Task 6 KPI {@code budget_blocked_dispatches}: per-ATTEMPT, not per tour. */
    int budgetBlockedDispatches() {
        return budgetStats.blockedDispatches();
    }

    /** Task 6 KPI {@code budget_overrides_expiry}: a high count means the budget was decorative. */
    int budgetOverridesExpiry() {
        return budgetStats.expiryOverrides();
    }

    /** KPI {@code budget_urgency_admits}: ZERO means the urgency ramp was never exercised. */
    int budgetUrgencyAdmits() {
        return budgetStats.urgencyAdmits();
    }

    /** Feed from ModularOptimizer.nextTask: previous = the task just PERFORMED. */
    public void observeTaskTransition(DvrpVehicle vehicle, Task previous, double now) {
        if (previous instanceof ModularFreightStopTask stop) {
            events.processEvent(ModularTourEvent.stopServed(now, stop.getTourId(),
                    vehicle.getId(), stop.getParcels()));
        } else if (previous instanceof ModularCapacityChangeTask swap) {
            events.processEvent(ModularTourEvent.swapDone(now, swap.getTourId(), vehicle.getId()));
            if (swap.isSwapBack()) {
                events.processEvent(ModularTourEvent.completed(now, swap.getTourId(),
                        vehicle.getId()));
            }
        }
    }

    /**
     * Euclidean distance from each idle candidate's current stay-task link to the tour's depot
     * (VERIFY-SOURCE: CoordUtils.calcEuclideanDistance, Link.getToNode().getCoord()). The cast to
     * {@link StayTask} is safe only because every candidate in {@code idle} already passed
     * {@link DrtScheduleInquiry#isIdle}, whose own definition (drt-core, confirmed by reading
     * DrtScheduleInquiry.java) requires the current task to be STAY-base-typed - every STAY-base
     * task class in this codebase (DrtStayTask, DefaultStayTask, ModularFreightStopTask)
     * implements StayTask, so this is not a defensive cast, it is a consequence of the filter
     * already applied upstream.
     */
    private DvrpVehicle nearestToDepot(List<DvrpVehicle> idle, ModularFreightTour tour) {
        Coord depot = resolveDepotLink(tour).getToNode().getCoord();
        DvrpVehicle best = null;
        double bestDist = Double.POSITIVE_INFINITY;
        for (DvrpVehicle v : idle) {   // idle is id-sorted -> '<' keeps the smallest id on ties
            StayTask stay = (StayTask) v.getSchedule().getCurrentTask();
            double d = CoordUtils.calcEuclideanDistance(
                    stay.getLink().getToNode().getCoord(), depot);
            if (d < bestDist) {
                bestDist = d;
                best = v;
            }
        }
        return best;
    }

    /**
     * Resolves a tour's depot link against this dispatcher's Network, or throws naming both the
     * tour and the missing link id (review Finding 2) - the same diagnostic
     * {@code ModularTourScheduler.resolveLink} gives for the identical failure mode (a Task 10
     * wiring bug: the Network injected here differs from the one the tour was converted against),
     * so an unguarded {@code NullPointerException} here would be an inconsistent, less useful
     * report of the same root cause.
     */
    private Link resolveDepotLink(ModularFreightTour tour) {
        Link link = network.getLinks().get(tour.depotLink());
        if (link == null) {
            throw new IllegalStateException("Modular tour " + tour.tourId() + " references link "
                    + tour.depotLink() + " which does not exist in the injected Network");
        }
        return link;
    }
}
