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
     *  jsprit vehicle operating window AND the service-start time window. */
    public static final double DELIVERY_DAY_START_S = 7.5 * 3600.0;   // 07:30
    public static final double DELIVERY_DAY_END_S = 21 * 3600.0;      // 21:00

    /** Pure capsule-swap (retooling) duration, spec §6.1: 7 min. */
    public static final double RETOOLING_S = 420.0;
    /** Submission look-ahead base (spec §4.3): effective look-ahead = this + RETOOLING_S. */
    public static final double FREIGHT_LOOKAHEAD_S = 420.0;
    /** Passenger-first dispatch gate default (design D6 / spec §6.1). */
    public static final double DEFAULT_IDLE_THRESHOLD = 0.50;
    /** Tour-duration cap default: 3.5h concept parameter (design D5); 25200 = control arm. */
    public static final int DEFAULT_MAX_TOUR_DURATION_S = 12600;

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
