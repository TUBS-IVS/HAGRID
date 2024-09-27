package hagrid.simulation;

import com.google.inject.Inject;
import com.google.inject.Provides;
import com.google.inject.Singleton;

import hagrid.utils.routing.VRPTransportCostsFactory;
import hagrid.utils.routing.ZoneBasedTransportCostsFactory;

import org.matsim.api.core.v01.Scenario;

import org.matsim.core.config.Config;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.listener.IterationEndsListener;
import org.matsim.core.router.util.TravelTime;
import org.matsim.freight.carriers.CarrierVehicleTypes;
import org.matsim.freight.carriers.Carriers;
import org.matsim.freight.carriers.CarriersUtils;
import org.matsim.freight.carriers.controler.CarrierScoringFunctionFactory;
import org.matsim.freight.carriers.controler.CarrierStrategyManager;
import org.matsim.freight.carriers.usecases.analysis.CarrierScoreStats;
import org.matsim.freight.carriers.usecases.analysis.LegHistogram;

import java.util.Map;

public class HAGRIDSimulationModule extends AbstractModule {

	private final Scenario scenario;

	private final CarrierVehicleTypes types;

	private final Boolean isUsingZones;

	public HAGRIDSimulationModule(Scenario scenario, Boolean isUsingZones) {
		this.types = CarriersUtils.getCarrierVehicleTypes(scenario);
		this.scenario = scenario;
		this.isUsingZones = isUsingZones;
	}

	@Inject
	private Config config;

	@Override
	public void install() {
		// Scoring and replanning function
		ScoringFunctions scoringFunctionFactory = new ScoringFunctions(scenario.getNetwork());
		ReplanningStrategies strategyManagerFactory = new ReplanningStrategies(types, isUsingZones);

		bind(Carriers.class).toProvider(new CarrierProvider()).asEagerSingleton();
		bind(CarrierStrategyManager.class).toProvider(strategyManagerFactory);

		bind(CarrierScoringFunctionFactory.class).toInstance(scoringFunctionFactory);
		
		bind(VRPTransportCostsFactory.class).to(ZoneBasedTransportCostsFactory.class).in(Singleton.class);

//		bind(TourLengthAnalyzer.class).in(Singleton.class);
//		addControlerListenerBinding().to(CommercialTrafficAnalysisListener.class);

		Carriers carriers = CarriersUtils.getCarriers(scenario);

		final CarrierScoreStats scores = new CarrierScoreStats(carriers,
				config.controller().getOutputDirectory() + "/carrier_scores", true);
		final int statInterval = 1;
		final LegHistogram freightOnly = new LegHistogram(900);
		freightOnly.setInclPop(false);
		binder().requestInjection(freightOnly);
		final LegHistogram withoutFreight = new LegHistogram(900);
		binder().requestInjection(withoutFreight);

		addEventHandlerBinding().toInstance(withoutFreight);
		addEventHandlerBinding().toInstance(freightOnly);
		addControlerListenerBinding().toInstance(scores);
		addControlerListenerBinding().toInstance(new IterationEndsListener() {

			@Inject
			private OutputDirectoryHierarchy controlerIO;

			@Override
			public void notifyIterationEnds(IterationEndsEvent event) {
				if (event.getIteration() % statInterval != 0)
					return;
				// write plans
				String dir = controlerIO.getIterationPath(event.getIteration());
//				new CarrierPlanXmlWriterV2(carriers).write(dir + "/" + event.getIteration() + ".carrierPlans.xml");

				// write stats
				freightOnly.writeGraphic(dir + "/" + event.getIteration() + ".legHistogram_freight.png");
				freightOnly.reset(event.getIteration());

				withoutFreight.writeGraphic(dir + "/" + event.getIteration() + ".legHistogram_withoutFreight.png");
				withoutFreight.reset(event.getIteration());
			}
		});

	}

	private class CarrierProvider implements com.google.inject.Provider<Carriers> {
		@Inject
		Scenario scenario;

		private CarrierProvider() {
		}

		public Carriers get() {
			return CarriersUtils.getCarriers(this.scenario);
		}
	}

	@Provides
	@Singleton
	private ZoneBasedTransportCostsFactory provideNetworkBasedTransportCostsFactory(Scenario scenario,
			Carriers carriers, Map<String, TravelTime> travelTimes, Config config) {

		return new ZoneBasedTransportCostsFactory(scenario, carriers, travelTimes, config);
	}

}
