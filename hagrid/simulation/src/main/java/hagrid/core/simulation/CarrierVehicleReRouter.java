package hagrid.core.simulation;

import hagrid.core.config.ResolvedHagridConfig;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.replanning.GenericPlanStrategy;
import org.matsim.core.replanning.GenericPlanStrategyImpl;
import org.matsim.core.replanning.selectors.BestPlanSelector;
import org.matsim.core.router.util.TravelTime;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierPlan;
import org.matsim.freight.carriers.CarrierVehicleTypes;
import org.matsim.freight.carriers.jsprit.VRPTransportCosts;

import java.util.Map;
import java.util.function.Supplier;

/** Compatibility entry point assembling the carrier replanning strategy. */
public final class CarrierVehicleReRouter {
    private final Supplier<CarrierReplanningModule> moduleFactory;

    private final double uTurnPenaltyCost;

    public CarrierVehicleReRouter(Network carNetwork, Network bikeNetwork, CarrierVehicleTypes vehicleTypes,
            TravelTime travelTimes, Boolean isUsingZones, Map<String, VRPTransportCosts> costsByMode,
            int jspritIterations) {
        this(carNetwork, bikeNetwork, vehicleTypes, travelTimes, isUsingZones, costsByMode,
                jspritIterations, hagrid.core.config.RoutingDefaults.SOLVER_MAX_ROUTE_DURATION_SECONDS,
                hagrid.core.config.RoutingDefaults.REPLANNING_START_ITERATION,
                hagrid.core.config.RoutingDefaults.CARRIERS_PER_REPLANNING_GROUP, null);
    }

    /** Compatibility constructor retaining the explicitly configured simulation tariff. */
    public CarrierVehicleReRouter(Network carNetwork, Network bikeNetwork, CarrierVehicleTypes vehicleTypes,
            TravelTime travelTimes, Boolean isUsingZones, Map<String, VRPTransportCosts> costsByMode,
            int jspritIterations, double uTurnPenaltyCost) {
        this(carNetwork,bikeNetwork,vehicleTypes,travelTimes,isUsingZones,costsByMode,
                legacyConfiguration(jspritIterations,uTurnPenaltyCost));
    }
    private static ResolvedHagridConfig legacyConfiguration(int iterations,double penalty) {
        return hagrid.core.config.HagridConfigResolver.resolve(new hagrid.core.HagridConfig(),
                hagrid.core.config.HagridConfigResolver.hannoverLegacySolver(),
                hagrid.core.config.HagridConfigResolver.Overrides.of(Map.of(
                        "optimization.replanningIterations",Integer.toString(iterations),
                        "tourRules.uTurnPenaltyCost",Double.toString(penalty))),Map.of());
    }
    double uTurnPenaltyCost() { return uTurnPenaltyCost; }
    CarrierReplanningModule createModule() { return moduleFactory.get(); }

    public CarrierVehicleReRouter(Network carNetwork, Network bikeNetwork, CarrierVehicleTypes vehicleTypes,
            TravelTime travelTimes, Boolean isUsingZones, Map<String, VRPTransportCosts> costsByMode,
            ResolvedHagridConfig config) {
        this(carNetwork, bikeNetwork, vehicleTypes, travelTimes, isUsingZones, costsByMode,
                config.optimization().replanningIterations().orElseThrow(
                        () -> new IllegalArgumentException("Replanning requires a replanning search budget")),
                config.tourRules().maxRouteDurationSeconds(),
                config.optimization().replanningStartIteration().orElseThrow(
                        () -> new IllegalArgumentException("Replanning requires a start iteration")),
                config.optimization().carriersPerGroup().orElseThrow(
                        () -> new IllegalArgumentException("Replanning requires a carrier group size")), config);
    }

    private CarrierVehicleReRouter(Network carNetwork, Network bikeNetwork, CarrierVehicleTypes vehicleTypes,
            TravelTime travelTimes, Boolean isUsingZones, Map<String, VRPTransportCosts> costsByMode,
            int iterations, double maxRouteDuration, int startIteration, int carriersPerGroup,
            ResolvedHagridConfig config) {
        this.uTurnPenaltyCost = config == null ? 0 : config.tourRules().uTurnPenaltyCost();
        var registered = Map.copyOf(costsByMode);
        this.moduleFactory = () -> {
            var costs = CarrierRoutingCosts.withLegacyFallback(registered, carNetwork, bikeNetwork,
                    vehicleTypes, travelTimes);
            var optimizer = new LegacyCarrierPlanOptimizer(costs,
                    iterations, maxRouteDuration, config);
            return new CarrierReplanningModule(costs, optimizer, startIteration, carriersPerGroup);
        };
    }

    public GenericPlanStrategy<CarrierPlan, Carrier> createStrategy() {
        var strategy = new GenericPlanStrategyImpl<CarrierPlan, Carrier>(new BestPlanSelector<>());
        strategy.addStrategyModule(createModule());
        return strategy;
    }
}
