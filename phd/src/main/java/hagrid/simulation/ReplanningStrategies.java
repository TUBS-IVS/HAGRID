package hagrid.simulation;


import com.google.inject.Inject;
import com.google.inject.Provider;

import hagrid.utils.routing.VRPTransportCostsFactory;

import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Network;

import org.matsim.core.replanning.GenericPlanStrategy;
import org.matsim.core.replanning.GenericPlanStrategyImpl;
import org.matsim.core.replanning.GenericStrategyManager;
import org.matsim.core.replanning.selectors.BestPlanSelector;
import org.matsim.core.replanning.selectors.KeepSelected;
import org.matsim.core.replanning.strategies.TimeAllocationMutator;
import org.matsim.core.router.util.LeastCostPathCalculator;
import org.matsim.core.router.util.LeastCostPathCalculatorFactory;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierPlan;
import org.matsim.freight.carriers.CarrierVehicleTypes;
import org.matsim.freight.carriers.controler.CarrierControlerUtils;
import org.matsim.freight.carriers.controler.CarrierReRouteVehicles;
import org.matsim.freight.carriers.controler.CarrierStrategyManager;
import org.matsim.freight.carriers.controler.CarrierTimeAllocationMutator;
import org.matsim.freight.carriers.usecases.chessboard.CarrierTravelDisutilities;

import java.util.Map;

public class ReplanningStrategies implements  Provider<CarrierStrategyManager> {

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

	public ReplanningStrategies(CarrierVehicleTypes types, Boolean isUsingZones) {
		this.types = types;
		this.isUsingZones = isUsingZones;
	}

	@Override
	public CarrierStrategyManager  get() {
//		TravelDisutility travelDisutility = TravelDisutilities.createBaseDisutility(types, modeTravelTimes.get(TransportMode.car));
		
		Network bikeNetwork = null;
		if(scenario.getScenarioElement("bikeNetwork") != null ) {			
			bikeNetwork = (Network) scenario.getScenarioElement("bikeNetwork");
		}
		
		Network carNetwork = null;
		if(scenario.getScenarioElement("carNetwork") != null ) {
			carNetwork = (Network) scenario.getScenarioElement("carNetwork");
		}
		
		TravelTime myTravelTime = (link, v, person, vehicle) -> {

			TravelTime myCongestedTravelTime = new ByModeCongestedTravelTime(vehicle.getType().getNetworkMode(),
					modeTravelTimes.get(vehicle.getType().getNetworkMode()));
//            if (type.getId().equals(cargoBikeType.getId())) {
//                return myNonCongestedTravelTime.getLinkTravelTime(link, v, person, vehicle);
//            }  else {
			if (v < 0) {
				System.out.println("Error with Time Request. Time Negativ! Time: " + v);
				System.out.println("Error with Time Request. Vehicle: " + vehicle.getId());
				System.out.println("Error with Time Request. Type: " + vehicle.getType().getId());
				System.out.println("Error with Time Request. Link: " + link.getId());
			}

			return myCongestedTravelTime.getLinkTravelTime(link, v, person, vehicle);
//            }
		};

		TravelDisutility travelDisutility = CarrierTravelDisutilities.createBaseDisutility(types, myTravelTime);

		final LeastCostPathCalculator router = leastCostPathCalculatorFactory.createPathCalculator(network,
				travelDisutility, myTravelTime);

		// Strategies
		final CarrierStrategyManager carrierStrategyManager = CarrierControlerUtils.createDefaultCarrierStrategyManager();
		carrierStrategyManager.setMaxPlansPerAgent(5);

//		GenericPlanStrategyImpl<CarrierPlan, Carrier> strategy = new GenericPlanStrategyImpl<>(new ExpBetaPlanChanger<>(1.));
//		strategyManager.addStrategy(strategy, null, 1.0);		

		carrierStrategyManager.addStrategy(new GenericPlanStrategyImpl<>(new BestPlanSelector<>()), null, 1.0);

		GenericPlanStrategyImpl<CarrierPlan, Carrier> strategy2 = new GenericPlanStrategyImpl<>(new KeepSelected<>());

		strategy2.addStrategyModule(new CarrierTimeAllocationMutator.Factory().build());
//		strategy2.addStrategyModule(new TimeAllocationMutatorV2());
		strategy2.addStrategyModule( new CarrierReRouteVehicles.Factory(router, network, modeTravelTimes.get(TransportMode.car ) ).build());
//		strategy2.addStrategyModule(new ReRouteVehicles(router, network, myTravelTime, 1.0));
		carrierStrategyManager.addStrategy(strategy2, null, 0.5);
		carrierStrategyManager.addChangeRequest(125, strategy2, null, 0);

//		GenericPlanStrategy<CarrierPlan, Carrier> strategy3 = new SelectBestPlanAndOptimizeItsVehicleRouteFactory(network, types, modeTravelTimes.get(TransportMode.car),pathAlgo).createStrategy();
		GenericPlanStrategy<CarrierPlan, Carrier> strategy3 = new CarrierVehicleReRouter(carNetwork, bikeNetwork, types, myTravelTime,
				isUsingZones, vrpTransportCostsFactory.createVRPTransportCostsWithModeCongestedTravelTime())
				.createStrategy();
//		GenericPlanStrategy<CarrierPlan, Carrier> strategy3 = new CarrierVehicleReRouter(network, types, myTravelTime , vrpTransportCostsFactory.createVRPTransportCosts()).createStrategy();
		carrierStrategyManager.addStrategy(strategy3, null, 1.0);
		carrierStrategyManager.addChangeRequest(125, strategy3, null, 0);

		return carrierStrategyManager;
	}
}

