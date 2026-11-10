package hagrid.lausitz.modular;

import org.matsim.api.core.v01.network.Link;
import org.matsim.contrib.dvrp.schedule.DefaultStayTask;

/** Wait for an absolute delivery-window opening, never repeat the original wait after a delay. */
public final class ModularFreightWaitTask extends DefaultStayTask {
    private final double earliestStart;
    public ModularFreightWaitTask(double arrival, double serviceStart, Link link, double earliestStart) {
        super(Modular.FREIGHT_WAIT_TASK_TYPE, arrival, serviceStart, link);
        this.earliestStart = earliestStart;
    }
    public double getEarliestStart() { return earliestStart; }
}
