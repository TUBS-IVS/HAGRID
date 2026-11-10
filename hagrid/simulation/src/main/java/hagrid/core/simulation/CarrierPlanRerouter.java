package hagrid.core.simulation;

import org.matsim.core.replanning.ReplanningContext;
import org.matsim.core.replanning.modules.GenericPlanStrategyModule;
import org.matsim.freight.carriers.CarrierPlan;

import org.matsim.freight.carriers.jsprit.VRPTransportCosts;
import java.util.Map;

/** Recalculates a changed tour using its modal provider, including the destination-link profile. */
final class CarrierPlanRerouter implements GenericPlanStrategyModule<CarrierPlan> {
    private final CarrierRoutingCosts costs;

    CarrierPlanRerouter(Map<String, VRPTransportCosts> costsByMode) {
        this.costs = new CarrierRoutingCosts(costsByMode);
    }

    @Override
    public void prepareReplanning(ReplanningContext context) {
        costs.prepare();
    }

    @Override
    public void handlePlan(CarrierPlan plan) {
        if (plan == null || plan.getCarrier() == null) {
            throw new IllegalArgumentException("Rerouting requires a plan attached to its carrier");
        }
        hagrid.core.routing.CarrierRoadRouter.routePlan(plan, costs.forCarrier(plan.getCarrier()));
    }

    @Override
    public void finishReplanning() {}
}
