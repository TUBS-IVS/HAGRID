package hagrid.simulation;

import com.google.inject.Inject;
import com.google.inject.Provider;
import hagrid.utils.routing.VRPTransportCostsFactory;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.replanning.GenericPlanStrategy;
import org.matsim.core.replanning.GenericPlanStrategyImpl;
import org.matsim.core.replanning.selectors.BestPlanSelector;
import org.matsim.core.replanning.selectors.KeepSelected;
import org.matsim.core.router.util.LeastCostPathCalculator;
import org.matsim.core.router.util.LeastCostPathCalculatorFactory;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierPlan;
import org.matsim.freight.carriers.CarrierVehicleTypes;
import org.matsim.freight.carriers.controller.CarrierControllerUtils;
import org.matsim.freight.carriers.controller.CarrierReRouteVehicles;
import org.matsim.freight.carriers.controller.CarrierStrategyManager;
import org.matsim.freight.carriers.controller.CarrierTimeAllocationMutator;
import org.matsim.freight.carriers.usecases.chessboard.CarrierTravelDisutilities;

import java.util.Map;

/**
 * This class provides the carrier strategy manager for replanning in the HAGRID
 * simulation.
 * It includes setting up strategies for path calculation, vehicle rerouting,
 * and time allocation.
 */
public class ReplanningStrategies implements Provider<CarrierStrategyManager> {

	@Inject
	private Network network;

	@Inject
	private Scenario scenario;

	@Inject
	private LeastCostPathCalculatorFactory leastCostPathCalculatorFactory;

	@Inject
	private Map<String, TravelTime> modeTravelTimes;

	@Inject
	private VRPTransportCostsFactory vrpTransportCostsFactory;

	private final CarrierVehicleTypes types;
	private final Boolean isUsingZones;

	// Shared path calculator, initialized once per iteration
	private LeastCostPathCalculator sharedPathCalculator;

	/**
	 * Constructor for ReplanningStrategies.
	 *
	 * @param types        The carrier vehicle types used in the simulation.
	 * @param isUsingZones Indicates if zone-based routing is used.
	 */
	public ReplanningStrategies(CarrierVehicleTypes types, Boolean isUsingZones) {
		this.types = types;
		this.isUsingZones = isUsingZones;
	}

	@Override
	public CarrierStrategyManager get() {
		if (network == null || scenario == null || leastCostPathCalculatorFactory == null ||
		modeTravelTimes == null || vrpTransportCostsFactory == null) {
		throw new IllegalStateException(
			"Dependencies not injected properly:\n" +
			"  network = " + network + "\n" +
			"  scenario = " + scenario + "\n" +
			"  leastCostPathCalculatorFactory = " + leastCostPathCalculatorFactory + "\n" +
			"  modeTravelTimes = " + modeTravelTimes + "\n" +
			"  vrpTransportCostsFactory = " + vrpTransportCostsFactory
			);
		}
		
		// Create the travel time and disutility
		TravelTime myTravelTime = createTravelTime();

		// Create the shared path calculator if not already created
		if (sharedPathCalculator == null) {
			TravelDisutility travelDisutility = CarrierTravelDisutilities.createBaseDisutility(types, myTravelTime);
			sharedPathCalculator = leastCostPathCalculatorFactory.createPathCalculator(network, travelDisutility,
					myTravelTime);
		}

		// Set up networks for different modes
		Network bikeNetwork = (Network) scenario.getScenarioElement("bikeNetwork");
		Network carNetwork = (Network) scenario.getScenarioElement("carNetwork");

		// Set up the strategy manager
		final CarrierStrategyManager carrierStrategyManager = CarrierControllerUtils
				.createDefaultCarrierStrategyManager();
		carrierStrategyManager.setMaxPlansPerAgent(5);

		// Add the strategies
		addBestPlanStrategy(carrierStrategyManager);
		addTimeAllocationAndReroutingStrategy(carrierStrategyManager);
		addVehicleReRoutingStrategy(carrierStrategyManager, bikeNetwork, carNetwork, myTravelTime);

		return carrierStrategyManager;
	}

	/**
	 * Creates and returns a travel time object that considers congestion for
	 * various modes.
	 *
	 * @return TravelTime object for use in the simulation.
	 */
	private TravelTime createTravelTime() {
		return (link, time, person, vehicle) -> {
			TravelTime congestedTravelTime = new ByModeCongestedTravelTime(vehicle.getType().getNetworkMode(),
					modeTravelTimes.get(vehicle.getType().getNetworkMode()));

			if (time < 0) {
				System.err.println("Error with Time Request. Time Negative! Time: " + time);
				System.err.println("Vehicle: " + vehicle.getId());
				System.err.println("Type: " + vehicle.getType().getId());
				System.err.println("Link: " + link.getId());
			}

			return congestedTravelTime.getLinkTravelTime(link, time, person, vehicle);
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
	private void addTimeAllocationAndReroutingStrategy(CarrierStrategyManager carrierStrategyManager) {
		GenericPlanStrategyImpl<CarrierPlan, Carrier> strategy = new GenericPlanStrategyImpl<>(new KeepSelected<>());
		strategy.addStrategyModule(new CarrierTimeAllocationMutator.Factory().build());
		strategy.addStrategyModule(new CarrierReRouteVehicles.Factory(sharedPathCalculator, network,
				modeTravelTimes.get(TransportMode.car)).build());
		carrierStrategyManager.addStrategy(strategy, null, 0.5);
		carrierStrategyManager.addChangeRequest(125, strategy, null, 0);
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
			Network carNetwork, TravelTime myTravelTime) {
		// Ensure the shared path calculator is created if not already available
		if (sharedPathCalculator == null) {
			sharedPathCalculator = leastCostPathCalculatorFactory.createPathCalculator(
					scenario.getNetwork(),
					CarrierTravelDisutilities.createBaseDisutility(types, myTravelTime),
					myTravelTime);
		}

		// Create the strategy using the shared path calculator
		GenericPlanStrategy<CarrierPlan, Carrier> strategy3 = new CarrierVehicleReRouter(carNetwork, bikeNetwork, types, myTravelTime,
				isUsingZones, vrpTransportCostsFactory.createVRPTransportCostsWithModeCongestedTravelTime())
				.createStrategy();

		carrierStrategyManager.addStrategy(strategy3, null, 1.0);
		carrierStrategyManager.addChangeRequest(125, strategy3, null, 0);
	}
}
