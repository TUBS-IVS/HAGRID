package hagrid.integrated.modular;

import org.matsim.api.core.v01.network.Link;
import org.matsim.contrib.drt.schedule.DefaultDrtCapacityChangeTask;
import org.matsim.contrib.dvrp.load.DvrpLoad;

/**
 * The capsule swap: the NATIVE drt-core capacity change (spike §1 headline - the swap IS a
 * DefaultDrtCapacityChangeTask; DrtActionCreator maps it to VehicleCapacityChangeActivity, and
 * InsertionGenerator re-reads getChangedCapacity() per stop), plus the tour identity the
 * dispatcher/KPI side needs (plan C6). Swap-out changes capacity to 0 passengers; swap-back
 * restores vehicle.getCapacity(). The 216-parcel cargo side is documentation (D8/C5).
 *
 * <p>VERIFY-SOURCE (spike): DefaultDrtCapacityChangeTask extends DefaultDrtStopTask, so its
 * inherited DRT task type is the generic {@code DefaultDrtStopTask.TYPE} (base type STOP) -
 * the SAME type every ordinary DRT passenger stop uses. That is exactly why
 * {@link Modular#hasUnperformedFreightTask} identifies this task by {@code instanceof}
 * rather than by task-type equality: equality could never tell a swap apart from a passenger
 * stop.
 */
public final class ModularCapacityChangeTask extends DefaultDrtCapacityChangeTask {

    private final String tourId;
    private final boolean swapBack;

    public ModularCapacityChangeTask(double beginTime, double endTime, Link link,
                                     DvrpLoad changedCapacity, String tourId, boolean swapBack) {
        super(beginTime, endTime, link, changedCapacity);
        this.tourId = tourId;
        this.swapBack = swapBack;
    }

    /** Retooling is a fixed concept parameter - the intended duration under timing updates. */
    public double getIntendedDuration() { return Modular.RETOOLING_S; }
    public String getTourId() { return tourId; }
    /** false = swap-out (pax -> cargo) at excursion start; true = swap-back at the end. */
    public boolean isSwapBack() { return swapBack; }
}
