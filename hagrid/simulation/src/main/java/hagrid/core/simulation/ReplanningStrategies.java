package hagrid.core.simulation;

import com.google.inject.Inject;
import com.google.inject.Provider;
import hagrid.core.routing.VRPTransportCostsFactory;
import hagrid.core.config.ResolvedHagridConfig;
import org.matsim.api.core.v01.Scenario;

import org.matsim.api.core.v01.network.Network;
import org.matsim.core.replanning.GenericPlanStrategy;
import org.matsim.core.replanning.GenericPlanStrategyImpl;
import org.matsim.core.replanning.selectors.BestPlanSelector;
import org.matsim.core.replanning.selectors.KeepSelected;

import org.matsim.core.router.util.TravelTime;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierPlan;
import org.matsim.freight.carriers.CarrierVehicleTypes;
import org.matsim.freight.carriers.jsprit.VRPTransportCosts;
import org.matsim.freight.carriers.controller.CarrierControllerUtils;

import org.matsim.freight.carriers.controller.CarrierStrategyManager;
import org.matsim.freight.carriers.controller.CarrierTimeAllocationMutator;

import java.util.Map;

/**
 * This class provides the carrier strategy manager for replanning in the HAGRID
 * simulation.
 * It includes setting up strategies for path calculation, vehicle rerouting,
 * and time allocation.
 */
public class ReplanningStrategies implements Provider<CarrierStrategyManager> {

	@Inject
	private Scenario scenario;

    @Inject
    private com.google.inject.Injector injector;

	@Inject
	private Map<String, TravelTime> modeTravelTimes;

	@Inject
	private VRPTransportCostsFactory vrpTransportCostsFactory;

	private final CarrierVehicleTypes types;
	private final Boolean isUsingZones;
	private final int jspritIterations;
    private ResolvedHagridConfig routingConfig;

    public ReplanningStrategies(CarrierVehicleTypes types, Boolean isUsingZones, int maxIterationsMATSim,
            ResolvedHagridConfig config) {
        this(types, isUsingZones, maxIterationsMATSim, config.optimization().replanningIterations().orElseThrow(
                () -> new IllegalArgumentException("Replanning requires a replanning search budget")),config.tourRules().uTurnPenaltyCost());
        this.routingConfig = config;
    }

	private int stopReplanningJsprit;
	private int stopReplanning;

	private final double uTurnPenaltyCost;

	double uTurnPenaltyCost() {
		return uTurnPenaltyCost;
	}

	/**
	 * Constructor for ReplanningStrategies.
	 *
	 * @param types        The carrier vehicle types used in the simulation.
	 * @param isUsingZones Indicates if zone-based routing is used.
	 * @param maxIterationsMATSim 
	 */
    public ReplanningStrategies(CarrierVehicleTypes types, Boolean isUsingZones, int maxIterationsMATSim, int jspritIterations) {
        this(types,isUsingZones,maxIterationsMATSim,jspritIterations,0);
    }

	public ReplanningStrategies(CarrierVehicleTypes types, Boolean isUsingZones, int maxIterationsMATSim, int jspritIterations,
			double uTurnPenaltyCost) {
		this.uTurnPenaltyCost = uTurnPenaltyCost;
		this.types = types;
		this.isUsingZones = isUsingZones;
		this.stopReplanningJsprit = (int) Math.round(maxIterationsMATSim - maxIterationsMATSim * 0.35);
		this.stopReplanning = (int) Math.round(maxIterationsMATSim - maxIterationsMATSim * 0.2);
		this.jspritIterations = jspritIterations;
	}

	@Override
	public CarrierStrategyManager get() {
		if (scenario == null ||
		modeTravelTimes == null || vrpTransportCostsFactory == null) {
		throw new IllegalStateException(
			"Dependencies not injected properly:\n" +
			"  scenario = " + scenario + "\n" +
			"  modeTravelTimes = " + modeTravelTimes + "\n" +
			"  vrpTransportCostsFactory = " + vrpTransportCostsFactory
			);
		}
		
        if (routingConfig != null) {
            // Only resolved runs install the singleton measurement and grouped-search lifecycle.
            return NativeCarrierPlanEvolution.manager(injector.getInstance(ValidatedCarrierReplanning.class),stopReplanning);
        }
		// Create the legacy fallback adapter; modal providers are shared by both routing strategies.
		TravelTime myTravelTime = createTravelTime();

		var costs = vrpTransportCostsFactory.createVRPTransportCostsWithModeCongestedTravelTime();

		// Set up networks for different modes
		Network bikeNetwork = (Network) scenario.getScenarioElement("bikeNetwork");
		Network carNetwork = (Network) scenario.getScenarioElement("carNetwork");

		// Set up the strategy manager
		final CarrierStrategyManager carrierStrategyManager = CarrierControllerUtils
				.createDefaultCarrierStrategyManager();
		carrierStrategyManager.setMaxPlansPerAgent(5);

		// Add the strategies
		addBestPlanStrategy(carrierStrategyManager);
		addTimeAllocationAndReroutingStrategy(carrierStrategyManager, costs);
		addVehicleReRoutingStrategy(carrierStrategyManager, bikeNetwork, carNetwork, myTravelTime, costs);

		return carrierStrategyManager;
	}

	/**
	 * Creates and returns a travel time object that considers congestion for
	 * various modes.
	 *
	 * @return TravelTime object for use in the simulation.
	 */
    private TravelTime createTravelTime() {
        Map<String, TravelTime> byMode = new java.util.HashMap<>();
        modeTravelTimes.forEach((mode, time) -> byMode.put(mode, new ByModeCongestedTravelTime(mode, time)));
        Map<org.matsim.api.core.v01.Id<org.matsim.vehicles.VehicleType>, String> registeredModes = new java.util.HashMap<>();
        types.getVehicleTypes().forEach((id, type) -> {
            if (type.hasNetworkMode()) registeredModes.put(id, type.getNetworkMode());
        });
        var modes = new java.util.HashSet<>(registeredModes.values());
        String preparationMode = modes.size() == 1 ? modes.iterator().next() : null;
        return (link, time, person, vehicle) -> {
            if (vehicle == null) {
                if (preparationMode == null || !byMode.containsKey(preparationMode)) {
                    throw new IllegalArgumentException("Vehicleless replanning travel time has no unambiguous mode");
                }
                return byMode.get(preparationMode).getLinkTravelTime(link, time, person, null);
            }
            var type = vehicle.getType();
            // Native NetworkBasedTransportCosts uses a minimal wrapper with the original type ID,
            // but does not copy its network mode. Resolve that ID against the registered types.
            String mode = type.hasNetworkMode() ? type.getNetworkMode() : registeredModes.get(type.getId());
            // jsprit also asks for costs without a vehicle during regret scoring. MATSim creates
            // a virtual default type for that query. Only a single registered mode is unambiguous.
            if (mode == null && !type.hasNetworkMode()) mode = preparationMode;
            TravelTime travelTime = mode == null ? null : byMode.get(mode);
            if (travelTime == null) {
                throw new IllegalArgumentException("No replanning travel time for vehicle type " + type.getId()
                        + " (mode=" + mode + ")");
            }
            return travelTime.getLinkTravelTime(link, time, person, vehicle);
        };
    }

	/**
	 * Adds the "Best Plan" strategy to the strategy manager.
	 *
	 * @param carrierStrategyManager The strategy manager to which the strategy is
	 *                               added.
	 */
	private void addBestPlanStrategy(CarrierStrategyManager carrierStrategyManager) {
		carrierStrategyManager.addStrategy(new GenericPlanStrategyImpl<>(new BestPlanSelector<>()), null, 1.0);
	}

	/**
	 * Adds the "Time Allocation and Rerouting" strategy to the strategy manager.
	 *
	 * @param carrierStrategyManager The strategy manager to which the strategy is
	 *                               added.
	 */
	private void addTimeAllocationAndReroutingStrategy(CarrierStrategyManager carrierStrategyManager, Map<String, VRPTransportCosts> costs) {
		GenericPlanStrategyImpl<CarrierPlan, Carrier> strategy = new GenericPlanStrategyImpl<>(new KeepSelected<>());
		strategy.addStrategyModule(new CarrierTimeAllocationMutator.Factory().build());
		strategy.addStrategyModule(new CarrierPlanRerouter(costs));
		carrierStrategyManager.addStrategy(strategy, null, 0.5);
		carrierStrategyManager.addChangeRequest(stopReplanning, strategy, null, 0);
	}

	/**
	 * Adds the vehicle re-routing strategy to the strategy manager.
	 *
	 * @param carrierStrategyManager The strategy manager to which the strategy is
	 *                               added.
	 * @param bikeNetwork            The network for bike routes.
	 * @param carNetwork             The network for car routes.
	 * @param myTravelTime           The travel time object for the simulation.
	 */
	private void addVehicleReRoutingStrategy(CarrierStrategyManager carrierStrategyManager, Network bikeNetwork,
			Network carNetwork, TravelTime myTravelTime, Map<String, VRPTransportCosts> costs) {
		// Both strategies share the same modal providers and prepared graphs.
        var rerouter = createReRouter(carNetwork,bikeNetwork,myTravelTime,costs);
        GenericPlanStrategy<CarrierPlan, Carrier> strategy3 = rerouter.createStrategy();

		carrierStrategyManager.addStrategy(strategy3, null, 1.0);
		carrierStrategyManager.addChangeRequest(stopReplanningJsprit, strategy3, null, 0);
	}

	/**
	 * Builds the in-loop re-router with this provider's U-turn penalty. Package-private and
	 * overridable so a wiring test can observe the instance {@link #get()} creates (final review I1).
	 */
	CarrierVehicleReRouter createReRouter(Network carNetwork, Network bikeNetwork, TravelTime travelTime,
			Map<String, VRPTransportCosts> byModeVRPTransportCosts) {
        return routingConfig == null
                ? new CarrierVehicleReRouter(carNetwork,bikeNetwork,types,travelTime,isUsingZones,
                        byModeVRPTransportCosts,jspritIterations,uTurnPenaltyCost)
                : new CarrierVehicleReRouter(carNetwork,bikeNetwork,types,travelTime,isUsingZones,
                        byModeVRPTransportCosts,routingConfig);
	}
}
