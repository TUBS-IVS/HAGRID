package hagrid.integrated.modular;

import org.matsim.contrib.dvrp.fleet.DvrpVehicle;
import org.matsim.contrib.dvrp.schedule.ScheduleTimingUpdater;
import org.matsim.contrib.dvrp.schedule.StayTask;

/**
 * MANDATORY (spike §3.1): without this decorator the core timing update silently DELETES a
 * delayed freight dwell (STAY branch returns REMOVE_STAY_TASK once oldEnd <= newBegin) and
 * silently SHRINKS a shifted capsule swap (STOP branch recomputes a DrtCapacityChangeTask's
 * duration via the PASSENGER stop-time calculator - empty pickup/dropoff sets -> generic
 * stopDuration instead of 7min retooling). Both failure modes are wrong timings without any
 * exception. Pattern: ShiftDrtStayTaskEndTimeCalculator (drt-extensions/operations, template).
 * Belt 2 (enforceIntendedDuration in the optimizer, Task 8) re-asserts the same durations.
 */
public final class ModularStayTaskEndTimeCalculator
        implements ScheduleTimingUpdater.StayTaskEndTimeCalculator {

    private final ScheduleTimingUpdater.StayTaskEndTimeCalculator delegate;

    public ModularStayTaskEndTimeCalculator(ScheduleTimingUpdater.StayTaskEndTimeCalculator delegate) {
        this.delegate = delegate;
    }

    @Override
    public double calcNewEndTime(DvrpVehicle vehicle, StayTask task, double newBeginTime) {
        if (task instanceof ModularFreightStopTask stop) {
            return newBeginTime + stop.getIntendedDuration();
        }
        if (task instanceof ModularCapacityChangeTask swap) {
            return newBeginTime + swap.getIntendedDuration();
        }
        return delegate.calcNewEndTime(vehicle, task, newBeginTime);
    }
}
