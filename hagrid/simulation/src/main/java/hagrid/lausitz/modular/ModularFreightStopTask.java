package hagrid.lausitz.modular;

import org.matsim.api.core.v01.network.Link;
import org.matsim.contrib.dvrp.schedule.DefaultStayTask;

/**
 * A freight delivery dwell on a Modular excursion. Deliberately a PLAIN stay task, never a
 * DrtStopTask (design D7): parcels are not agents, the passenger engine never sees this stop,
 * and drt_customer_stats stays uncontaminated. Its DRT base type is STAY, so the native
 * DrtActionCreator renders it as an idle activity with no extra hook (verified against
 * DrtActionCreator's STAY case). Duration = jsprit service duration (min(2min x parcels,
 * 15min), LMD parity) x deliveryDwellFactor (1.0 in 1d; §4.4 autonomy hook).
 */
public final class ModularFreightStopTask extends DefaultStayTask {

    private final double intendedDuration;
    private final int parcels;
    private final String tourId;
    private final int stopIndex;
    private final double earliestStart;
    private final double latestStart;

    public ModularFreightStopTask(double beginTime, double endTime, Link link,
                                  int parcels, String tourId, int stopIndex) {
        this(beginTime,endTime,link,parcels,tourId,stopIndex,0,Double.POSITIVE_INFINITY);
    }
    public ModularFreightStopTask(double beginTime,double endTime,Link link,int parcels,String tourId,
            int stopIndex,double earliestStart,double latestStart) {
        super(Modular.FREIGHT_STOP_TASK_TYPE, beginTime, endTime, link);
        ModularTourTiming.validateWindow(earliestStart,latestStart);
        this.earliestStart=earliestStart;
        this.latestStart=latestStart;
        this.intendedDuration = endTime - beginTime;
        this.parcels = parcels;
        this.tourId = tourId;
        this.stopIndex = stopIndex;
    }

    /** The dwell the excursion plan intends; the end-time calculator preserves it under delays. */
    public double getIntendedDuration() { return intendedDuration; }
    public int getParcels() { return parcels; }
    public String getTourId() { return tourId; }
    public int getStopIndex() { return stopIndex; }
    public double getEarliestStart() { return earliestStart; }
    public double getLatestStart() { return latestStart; }
}
