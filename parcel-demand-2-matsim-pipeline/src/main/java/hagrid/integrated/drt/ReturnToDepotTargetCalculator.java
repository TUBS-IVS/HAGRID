package hagrid.integrated.drt;

import org.matsim.contrib.common.zones.Zone;
import org.matsim.contrib.drt.optimizer.rebalancing.targetcalculator.RebalancingTargetCalculator;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToDoubleFunction;

/**
 * Time-switched rebalancing target: before {@code returnStart}, delegates to a
 * demand-based calculator (idle vehicles flow toward demand). From
 * {@code returnStart} onward, targets the depot zones so idle vehicles drive
 * home over the network (end-of-day return). Vehicles still serving at sim end
 * stop at their last stop and are handled by the KPI fallback (Task 6).
 */
public final class ReturnToDepotTargetCalculator implements RebalancingTargetCalculator {

    private final RebalancingTargetCalculator daytime;
    private final Set<Zone> depotZones;
    private final double returnStart;
    private final double targetPerDepotZone;

    public ReturnToDepotTargetCalculator(RebalancingTargetCalculator daytime, Set<Zone> depotZones,
                                         double returnStart, double targetPerDepotZone) {
        this.daytime = daytime;
        this.depotZones = depotZones;
        this.returnStart = returnStart;
        this.targetPerDepotZone = targetPerDepotZone;
    }

    @Override
    public ToDoubleFunction<Zone> calculate(double timeStep, Map<Zone, List<DvrpVehicle>> vehiclesByZone) {
        if (timeStep < returnStart) {
            return daytime.calculate(timeStep, vehiclesByZone);
        }
        return zone -> depotZones.contains(zone) ? targetPerDepotZone : 0.0;
    }
}
