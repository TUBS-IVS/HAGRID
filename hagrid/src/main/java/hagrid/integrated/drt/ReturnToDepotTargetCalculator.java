package hagrid.integrated.drt;

import org.matsim.contrib.common.zones.Zone;
import org.matsim.contrib.drt.optimizer.rebalancing.targetcalculator.RebalancingTargetCalculator;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;

import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/**
 * Time-switched rebalancing target: before {@code returnStart}, delegates to a
 * demand-based calculator (idle vehicles flow toward demand). From
 * {@code returnStart} onward, each depot zone pulls exactly its CAPACITY, so the
 * MinCostFlow relocator fills the nearest depot first and overflows to the next
 * once it is full — instead of an uncapped pull that legally parks the whole
 * fleet at one depot. Vehicles still serving at sim end stop at their last stop
 * and are handled by the KPI fallback (Task 6).
 */
public final class ReturnToDepotTargetCalculator implements RebalancingTargetCalculator {

    private final RebalancingTargetCalculator daytime;
    private final Map<Zone, Double> depotCapacities;
    private final double returnStart;

    public ReturnToDepotTargetCalculator(RebalancingTargetCalculator daytime,
                                         Map<Zone, Double> depotCapacities, double returnStart) {
        this.daytime = daytime;
        this.depotCapacities = depotCapacities;
        this.returnStart = returnStart;
    }

    @Override
    public ToDoubleFunction<Zone> calculate(double timeStep, Map<Zone, List<DvrpVehicle>> vehiclesByZone) {
        if (timeStep < returnStart) {
            return daytime.calculate(timeStep, vehiclesByZone);
        }
        return zone -> depotCapacities.getOrDefault(zone, 0.0);
    }
}
