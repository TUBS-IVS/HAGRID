package hagrid.integrated.modular;

import org.matsim.contrib.drt.schedule.DrtTaskType;
import org.matsim.contrib.dvrp.schedule.Schedule;
import org.matsim.contrib.dvrp.schedule.Task;

import static org.matsim.contrib.drt.schedule.DrtTaskBaseType.DRIVE;
import static org.matsim.contrib.drt.schedule.DrtTaskBaseType.STAY;

/** Constants for the 1d Modular (U-Shift capsule swap) scenario. Extended in Task 3. */
public final class Modular {
    /** Cargo capsule parcel capacity (spec §6.1). DOCUMENTED NEVER-BINDING (design D8):
     *  216 x 2 min dwell = 7.2h exceeds any tour cap <= 7h, so time always binds first.
     *  It sizes the jsprit vehicle; it is NOT a DvrpLoad dimension (design D7 / plan C5). */
    public static final int CARGO_CAPACITY_PARCELS = 216;
    public static final String CARGO_CAPSULE_TYPE_ID = "ushift_cargo_capsule";

    /** Delivery day (plan C4 revised, user 2026-07-28): parcels arrive at the depot overnight,
     *  same-day delivery 07:30-21:00 is what counts - NO dispatch waves in 1d. Used as the
     *  jsprit vehicle operating window AND the service-start time window.
     *  Since 2026-07-30 this is the delivery day of ALL THREE arms, so it delegates to the single
     *  source of truth ({@link hagrid.integrated.DeliveryDay}) instead of restating the numbers. */
    public static final double DELIVERY_DAY_START_S = hagrid.integrated.DeliveryDay.START_S;
    public static final double DELIVERY_DAY_END_S = hagrid.integrated.DeliveryDay.END_S;

    /** Pure capsule-swap (retooling) duration, spec §6.1: 7 min. */
    public static final double RETOOLING_S = 420.0;
    /** Submission look-ahead base (spec §4.3): effective look-ahead = this + RETOOLING_S. */
    public static final double FREIGHT_LOOKAHEAD_S = 420.0;
    /** Passenger-first dispatch gate default (design D6 / spec §6.1). */
    public static final double DEFAULT_IDLE_THRESHOLD = 0.50;
    /** Tour-duration cap default: 3.5h concept parameter (design D5); 25200 = control arm. */
    public static final int DEFAULT_MAX_TOUR_DURATION_S = 12600;

    /** Concurrency cap on vehicles committed to freight; 0 = unlimited (= behaviour before
     *  2026-08-30). Motivation, measured on basew21_it250: the passenger-only baseline leaves
     *  27.0 / 33.9 / 25.0 free vehicles at 08 / 09 / 10 h, while the 1d arm at theta=0.15 puts
     *  32.5 / 34.4 / 34.0 vehicles on freight in exactly those hours - an overhang of 14.9
     *  vehicle-hours concentrated in three morning hours, against 133 vehicle-hours left unused
     *  in the same delivery window after 16:00. The idle-share gate cannot express this: it
     *  measures the MODULAR fleet's own slack, which is larger precisely because that fleet is
     *  larger. This cap states the passenger-side budget directly. */
    public static final int DEFAULT_MAX_CONCURRENT_FREIGHT = 0;

    /**
     * Self-referential capacity budget (plan 2026-09-04, Task 4). {@code OFF} is the default and
     * reproduces the pre-2026-09-04 gate exactly - no {@link PassengerLoadProfile} is even
     * constructed, so an OFF run does not pay for the per-tick passenger-busy scan.
     * {@code SELFREF} reads the budget from the run's OWN previous iteration (never from the
     * Baseline arm, which would parameterise 1d with information taken from the very arm it is
     * compared against).
     */
    public enum BudgetMode {
        /** Budget disabled: theta, {@code maxConcurrentFreight} and {@code freightWindows} only. */
        OFF,
        /** Budget derived from the smoothed passenger load of this run's own last k iterations. */
        SELFREF
    }

    /** Budget default: OFF, i.e. every existing run stays bit-identical. */
    public static final BudgetMode DEFAULT_BUDGET_MODE = BudgetMode.OFF;

    /**
     * Number of completed iterations the passenger-load profile averages over ({@code k}).
     * Default 5 (user decision 2026-09-04): {@code k=1} is the most responsive and the most
     * likely to oscillate, since the dispatcher becomes part of its own fixed point.
     */
    public static final int DEFAULT_BUDGET_SMOOTHING = 5;

    /**
     * Reserve held back in every spanned bin, as a SHARE of fleet size - NOT a vehicle count.
     *
     * <p><b>The default 0.15 is theta, and that is deliberate: it makes the first budget arm a
     * one-factor experiment</b> (user decision 2026-09-04). For the CURRENT bin the two gates are
     * algebraically the same condition:
     * <pre>
     *   theta gate: idle/fleet &gt; theta   &lt;=&gt;  freightBusy &lt; fleet - passengerBusy - theta*fleet
     *   budget:     projFreight(bin) + 1  &lt;=   fleet - passengerBusy(bin) - h*fleet
     * </pre>
     * With {@code h = theta = 0.15} the budget therefore reproduces the calibrated gate in the
     * current bin and changes ONLY the refusal of commitments that would collide in a LATER bin -
     * which is exactly the mechanism this feature adds. Setting {@code h = 0} would have altered
     * the gate's sharpness AND added look-ahead at the same time, leaving the two effects
     * inseparable afterwards. Whether the reserve is still right once the gate looks ahead is a
     * separate question, measured by the headroom sweep (plan Task 8), not guessed here.
     */
    public static final double DEFAULT_BUDGET_HEADROOM = 0.15;

    /**
     * Lead time over which a pending tour's urgency ramps from 0 to the full headroom reserve
     * (plan {@code 2026-09-05-honest-envelope-and-urgency-ramp}, Fix 2), in seconds.
     *
     * <p>Replaces the binary "last dispatch opportunity" override that killed
     * {@code d1d_f130_bud}: that override fired at the last instant the (optimistic) envelope
     * allowed, and the splicer refused it in the same second because the honest routed completion
     * no longer fitted. A ramp removes the knife edge - a tour approaching its deadline is
     * progressively allowed to eat into the reserve instead of being refused outright until one
     * final, already-too-late attempt.
     *
     * <p><b>Basis for 3600 s.</b> The envelope error measured on the anchor arm
     * {@code d1d_dep7_f130_it250} (it.250, all 46 tours) is a p90 of 1635 s and a maximum of
     * 2403 s. One hour is 2.2x the p90 and 1.5x the max. Note this is TOLERANCE, not correction:
     * the deadline itself is made honest by the learned chain duration
     * ({@link FreightChainProfile}), and the ramp only has to absorb the residual - which is why
     * its exact value is not critical and does not need its own sweep before the mechanism can be
     * tested.
     */
    public static final double DEFAULT_BUDGET_URGENCY_LEAD_S = 3600.0;

    /**
     * Uplift applied to {@code ModularTourScheduler.minimumChainDurationS} for a tour that no
     * buffered iteration has dispatched yet, i.e. the bootstrap for {@link FreightChainProfile}.
     *
     * <p>{@code minimumChainDurationS} is a PROVEN lower bound on the chain - free-flow travel on
     * the DRT network over the real stop sequence, both retoolings, all service times - but it
     * omits the approach leg from wherever the vehicle happens to be, and free flow is not
     * congested flow. This factor covers both. It is the only magic number this plan introduces
     * and it binds only before a tour's FIRST observation, i.e. in practice only in iteration 0,
     * where the fleet is fully idle at ~07:16 and no tour is anywhere near its deadline.
     *
     * <p><b>MEASURED 2026-09-06, no longer a guess.</b> {@code d1d_f130_bud2} published
     * {@code chain_ratio_p90 = 1.094} and {@code chain_ratio_max = 1.137} over 30 dispatches -
     * the observed {@code routedDurationS / minimumChainDurationS}. The original 1.25 was set
     * blind and over-protected by ~10 %. 1.15 sits just above the observed maximum: still
     * conservative, which is the direction that matters here (too small drops parcels, too large
     * costs one early dispatch), but no longer an invention.
     *
     * <p>The over-protection was harmless in practice, since this factor binds only before a
     * tour's FIRST observation - iteration 0, where the fleet is idle at ~07:16 and nothing is
     * near its deadline. It is corrected because a guessed constant that has since been measured
     * should not stay guessed, not because it changed a result.
     */
    public static final double CHAIN_BOOTSTRAP_FACTOR = 1.15;

    /** A half-open dispatch window [startS, endS) in seconds after midnight. */
    public record DispatchWindow(double startS, double endS) {
        public DispatchWindow {
            if (endS <= startS) {
                throw new IllegalArgumentException(
                        "dispatch window end must be after start: " + startS + "-" + endS);
            }
        }
        public boolean contains(double t) { return t >= startS && t < endS; }
    }

    /**
     * Parses {@code "08:00-11:00,16:00-17:00"} into dispatch windows. Blank or absent yields an
     * EMPTY list, which means "always open" - the behaviour before 2026-08-30, so an unset key
     * leaves every existing run bit-identical.
     *
     * <p><b>The late window is bounded by the expiry envelope, not by taste.</b>
     * {@code ModularTourDispatcher} drops a pending tour once
     * {@code now + 2*RETOOLING_S + plannedDuration > latestEnd}. With latestEnd =
     * {@link #DELIVERY_DAY_END_S} (21:00) and a full 3.5 h tour that puts the last admissible
     * dispatch at 17:16; measured vehicle binding was 3.85 h median / 4.36 h max, i.e. 16:38 in
     * the worst case. A second window opening later than that does not shift work into the
     * evening - it expires it. Verify {@code tours_expired_pending == 0} after any change here.
     */
    public static java.util.List<DispatchWindow> parseWindows(String raw) {
        if (raw == null || raw.isBlank()) {
            return java.util.List.of();
        }
        java.util.List<DispatchWindow> out = new java.util.ArrayList<>();
        for (String part : raw.split(",")) {
            String w = part.trim();
            if (w.isEmpty()) {
                continue;
            }
            String[] ends = w.split("-");
            if (ends.length != 2) {
                throw new IllegalArgumentException(
                        "freightWindows entry must be HH:MM-HH:MM: " + w);
            }
            out.add(new DispatchWindow(parseClock(ends[0].trim()), parseClock(ends[1].trim())));
        }
        return java.util.List.copyOf(out);
    }

    /** "HH:MM" (or "HH:MM:SS") -> seconds after midnight. */
    private static double parseClock(String hhmm) {
        String[] p = hhmm.split(":");
        if (p.length < 2 || p.length > 3) {
            throw new IllegalArgumentException("expected HH:MM, got: " + hhmm);
        }
        try {
            double s = Integer.parseInt(p[0]) * 3600.0 + Integer.parseInt(p[1]) * 60.0;
            return p.length == 3 ? s + Integer.parseInt(p[2]) : s;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("non-numeric time: " + hhmm, e);
        }
    }

    /** Freight stop = plain STAY-base task (design D7): parcels never touch the passenger engine. */
    public static final DrtTaskType FREIGHT_STOP_TASK_TYPE = new DrtTaskType("MODULAR_FREIGHT_STOP", STAY);
    /** Approach / inter-stop / return legs of a freight excursion. */
    public static final DrtTaskType FREIGHT_DRIVE_TASK_TYPE = new DrtTaskType("MODULAR_FREIGHT_DRIVE", DRIVE);

    private Modular() {}

    /**
     * TRUE while the schedule still holds any un-performed freight-excursion task. This is the
     * SINGLE commitment predicate (design D2 strict lockout) shared by ModularEntryFactory
     * (pax candidate exclusion, Task 8) and the tour dispatcher's idle pool (Task 7) -
     * deliberately WIDER than drt-extensions' current-task/one-before-last check, which is
     * insufficient for multi-stop tours: once a task other than the swap gets appended after
     * it (e.g. a post-swap repositioning leg, or later passenger insertions once the vehicle
     * incorrectly stayed in the candidate set), the swap is no longer "one before last" and a
     * narrow check would go blind to it while it is still un-performed (spike §3.3). This scans
     * every task from the current one onward instead, so it cannot be fooled that way.
     */
    public static boolean hasUnperformedFreightTask(Schedule schedule) {
        return switch (schedule.getStatus()) {
            case PLANNED -> schedule.getTasks().stream().anyMatch(Modular::isFreightTask);
            case STARTED -> {
                int from = schedule.getCurrentTask().getTaskIdx();
                yield schedule.getTasks().stream()
                        .filter(t -> t.getTaskIdx() >= from)
                        .anyMatch(t -> isFreightTask(t) && t.getStatus() != Task.TaskStatus.PERFORMED);
            }
            default -> false; // UNPLANNED / COMPLETED
        };
    }

    /**
     * The swap is matched by type ({@code instanceof}), not task-type equality: unlike the two
     * freight stop/drive types, {@link ModularCapacityChangeTask} inherits its DRT task type from
     * the NATIVE {@code DefaultDrtCapacityChangeTask} (base type STOP, the same generic type any
     * ordinary DRT stop uses) - so an equality check could never distinguish it. This asymmetry
     * is deliberate, not sloppiness (see ModularCapacityChangeTask's javadoc).
     */
    private static boolean isFreightTask(Task t) {
        return t.getTaskType().equals(FREIGHT_STOP_TASK_TYPE)
                || t.getTaskType().equals(FREIGHT_DRIVE_TASK_TYPE)
                || t instanceof ModularCapacityChangeTask;
    }
}
