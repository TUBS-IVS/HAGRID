package hagrid.integrated.modular;

import org.matsim.contrib.drt.optimizer.DrtOptimizer;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;
import org.matsim.contrib.dvrp.optimizer.Request;
import org.matsim.contrib.dvrp.schedule.Schedule;
import org.matsim.contrib.dvrp.schedule.ScheduleTimingUpdater;
import org.matsim.contrib.dvrp.schedule.Schedules;
import org.matsim.contrib.dvrp.schedule.Task;
import org.matsim.core.mobsim.framework.MobsimTimer;
import org.matsim.core.mobsim.framework.events.MobsimBeforeSimStepEvent;

import java.util.List;

/**
 * {@link DrtOptimizer} decorator (pattern: drt-extensions {@code DrtServiceTaskOptimizer}). All
 * passenger handling delegates to the native optimizer unchanged; this class adds exactly three
 * behaviours: (1) the tour dispatcher ticks each simstep BEFORE the delegate, so a
 * freshly-committed vehicle is already non-idle by the time the delegate's own rebalancing looks
 * for relocatable vehicles; (2) {@code nextTask} re-asserts the intended durations of upcoming
 * freight tasks (belt 2 against spike §3.1, Task 5's {@link ModularStayTaskEndTimeCalculator}
 * being belt 1); (3) {@code nextTask} feeds performed-task transitions to the dispatcher, which
 * turns schedule progress into the {@code STOP_SERVED}/{@code SWAP_DONE}/{@code COMPLETED} KPI
 * events.
 *
 * <p><b>VERIFY-SOURCE / the "double timing update" question (task-8-report.md has the full
 * finding):</b> {@code DefaultDrtOptimizer.nextTask} (read directly from drt-2025.0-sources.jar)
 * is exactly:
 * <pre>
 * public void nextTask(DvrpVehicle vehicle) {
 *     scheduleTimingUpdater.updateBeforeNextTask(vehicle);
 *     vehicle.getSchedule().nextTask();
 * }
 * </pre>
 * i.e. the delegate ALREADY calls {@code updateBeforeNextTask} itself. This class therefore does
 * NOT call it a second time - {@link #enforceIntendedDurations} runs strictly AFTER {@code
 * delegate.nextTask(vehicle)}, on the schedule's final, already-updated, post-transition state.
 * Two independent reasons ruled out the brief's literal draft (explicit call, then belt 2, then
 * {@code delegate.nextTask()}):
 * <ol>
 *   <li><b>Not idempotent under a real, reachable DRT config.</b> {@code
 *       ScheduleTimingUpdater.updateTimingsStartingFromCurrentTask}'s own guard is {@code
 *       currentTask.getEndTime() != newEndTime || driveTaskUpdater != DriveTaskUpdater.NOOP} -
 *       when {@code drtCfg.isUpdateRoutes()} is {@code true} (a per-DRT-mode config flag, so it
 *       can be {@code true} for this same fleet's passenger side), that OR-branch is {@code true}
 *       UNCONDITIONALLY, so a second, delegate-internal call to {@code updateBeforeNextTask}
 *       would unconditionally re-walk {@code updateTimingsStartingFromTaskIdx} over every
 *       downstream task again - silently re-running whatever {@code StayTaskEndTimeCalculator} is
 *       wired and overwriting a belt-2 fix that had already been applied in between the two
 *       calls. This is exactly the silent-corruption class Tasks 5 and 8 exist to prevent -
 *       empirically confirmed in task-8-report.md by temporarily reverting to the literal-draft
 *       structure and observing {@code enforceIntendedDuration} go RED.</li>
 *   <li><b>Examining the OLD current task doesn't make sense.</b> Calling {@code
 *       enforceIntendedDurations} BEFORE {@code delegate.nextTask()} means "current" is still the
 *       task about to be marked PERFORMED - a task whose real completion time already happened
 *       (that is WHY the framework called {@code nextTask} in the first place). Note this is
 *       NOT a legality problem: the task is still STARTED at that point, and {@code
 *       AbstractTask.setEndTime} only rejects a task already PERFORMED, so the draft's call would
 *       not have thrown. The real problem is a schedule/mobsim DESYNC: pushing that task's end
 *       further into the future leaves the very next task's begin time ahead of the mobsim's
 *       {@code now} - a task whose window hasn't opened yet from the simulation's point of view.
 *       Task 5's belt 1 never attempts this either (it only protects tasks reached via {@code
 *       updateTimingsStartingFromTaskIdx}, i.e. strictly downstream of "current"). Running belt 2
 *       AFTER the transition means "current" is the task that just STARTED - matching the brief's
 *       own "any UPCOMING freight task" framing, with no such desync (the schedule and the mobsim
 *       agree on what "now" is at that point).
 * </ol>
 */
public class ModularOptimizer implements DrtOptimizer {

    private final DrtOptimizer delegate;
    private final ModularTourDispatcher dispatcher;
    private final ScheduleTimingUpdater scheduleTimingUpdater;
    private final MobsimTimer timer;

    public ModularOptimizer(DrtOptimizer delegate, ModularTourDispatcher dispatcher,
                            ScheduleTimingUpdater scheduleTimingUpdater, MobsimTimer timer) {
        this.delegate = delegate;
        this.dispatcher = dispatcher;
        this.scheduleTimingUpdater = scheduleTimingUpdater;
        this.timer = timer;
    }

    @Override
    public void requestSubmitted(Request request) {
        delegate.requestSubmitted(request);
    }

    @Override
    public void nextTask(DvrpVehicle vehicle) {
        Task previous = currentOrNull(vehicle);
        delegate.nextTask(vehicle);          // native update-before-next-task + schedule advance
        enforceIntendedDurations(vehicle);   // belt 2, on the final post-transition state
        Task next = currentOrNull(vehicle);
        if (previous != null && previous != next) {
            dispatcher.observeTaskTransition(vehicle, previous, timer.getTimeOfDay());
        }
    }

    @Override
    public void notifyMobsimBeforeSimStep(@SuppressWarnings("rawtypes") MobsimBeforeSimStepEvent e) {
        dispatcher.dispatch(e.getSimulationTime());
        delegate.notifyMobsimBeforeSimStep(e);
    }

    /**
     * Belt 2 (spike §3.1): if the timing update the delegate just ran undershot an intended
     * freight duration, push the end back out and ripple the shift downstream
     * (DrtServiceTaskOptimizer pattern). Runs on tasks from the (new, just-started) current task
     * onward - i.e. every task not yet PERFORMED, matching "any upcoming freight task".
     */
    private void enforceIntendedDurations(DvrpVehicle vehicle) {
        Schedule schedule = vehicle.getSchedule();
        if (schedule.getStatus() != Schedule.ScheduleStatus.STARTED) {
            return;
        }
        Task current = schedule.getCurrentTask();
        // Nothing to ripple past the trailing STAY, and by construction (ModularTourScheduler)
        // the last task is always that plain trailing STAY, never a freight task itself - so
        // this is a cheap-exit guard, not a correctness short-circuit that could skip repairing
        // a freight task sitting in the last position.
        if (current == Schedules.getLastTask(schedule)) {
            return;
        }
        int currentIdx = current.getTaskIdx();
        // review Finding 1: schedule.getTasks() is a live (unmodifiable) VIEW over ScheduleImpl's
        // backing ArrayList. updateTimingsStartingFromTaskIdx (called below, mid-loop) can call
        // schedule.removeTask(...) whenever a StayTaskEndTimeCalculator returns REMOVE_STAY_TASK -
        // an ordinary DRT occurrence for a plain, non-last STAY whose old end has been overtaken,
        // and belt 1 delegates plain (non-Modular) stays straight through to that native
        // calculator. Removing from the live backing list mid-iteration throws
        // ConcurrentModificationException on the next Iterator.next() and kills the mobsim. The
        // drt-extensions DrtServiceTaskOptimizer template this class is patterned on materialises
        // its own task list first for exactly this reason - do the same here.
        for (Task t : List.copyOf(schedule.getTasks())) {
            if (t.getTaskIdx() < currentIdx) {
                continue;
            }
            double intended;
            if (t instanceof ModularFreightStopTask stop) {
                intended = stop.getIntendedDuration();
            } else if (t instanceof ModularCapacityChangeTask swap) {
                intended = swap.getIntendedDuration();
            } else {
                continue;
            }
            double durationNow = t.getEndTime() - t.getBeginTime();
            if (durationNow < intended) {
                double end = t.getBeginTime() + intended;
                t.setEndTime(end);
                scheduleTimingUpdater.updateTimingsStartingFromTaskIdx(vehicle, t.getTaskIdx() + 1, end);
            }
        }
    }

    private static Task currentOrNull(DvrpVehicle vehicle) {
        Schedule schedule = vehicle.getSchedule();
        return schedule.getStatus() == Schedule.ScheduleStatus.STARTED
                ? schedule.getCurrentTask() : null;
    }
}
