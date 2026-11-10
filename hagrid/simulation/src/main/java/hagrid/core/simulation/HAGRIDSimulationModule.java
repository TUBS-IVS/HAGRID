package hagrid.core.simulation;

import com.google.inject.Inject;
import com.google.inject.Provides;
import com.google.inject.Singleton;

import hagrid.core.routing.VRPTransportCostsFactory;
import hagrid.core.config.ResolvedHagridConfig;
import hagrid.core.routing.DefaultVRPTransportCostsFactory;

import org.matsim.api.core.v01.Scenario;

import org.matsim.core.config.Config;
import org.matsim.core.controler.AbstractModule;



import org.matsim.core.router.util.TravelTime;
import org.matsim.freight.carriers.CarrierVehicleTypes;
import org.matsim.freight.carriers.Carriers;
import org.matsim.freight.carriers.CarriersUtils;
import org.matsim.freight.carriers.controller.CarrierScoringFunctionFactory;
import org.matsim.freight.carriers.controller.CarrierStrategyManager;
import org.matsim.freight.carriers.usecases.analysis.CarrierScoreStats;
import org.matsim.freight.carriers.usecases.analysis.LegHistogram;

import java.util.Map;

public class HAGRIDSimulationModule extends AbstractModule {

	private final Scenario scenario;

	private final CarrierVehicleTypes types;

	private final Boolean isUsingZones;

	private int maxIterationsMATSim;
	private int jspritIterations;





	private final double uTurnPenaltyCost;
    private ResolvedHagridConfig routingConfig;

    public HAGRIDSimulationModule(Scenario scenario, Boolean isUsingZones, int maxIterationsMATSim,
            ResolvedHagridConfig config) {
        this(scenario, isUsingZones, maxIterationsMATSim,
                config.optimization().replanningIterations().orElseThrow(
                        () -> new IllegalArgumentException("Module requires a replanning search budget")),
                false, 0,
                config.tourRules().uTurnPenaltyCost());
        this.routingConfig = config;
    }

	public HAGRIDSimulationModule(Scenario scenario, Boolean isUsingZones, int maxIterationsMATSim, int jspritIterations,
			boolean zoneBasedCachingEnabled, double zoneBasedEuclideanThresholdMeters,
			double uTurnPenaltyCost) {
		this.types = CarriersUtils.getCarrierVehicleTypes(scenario);
		this.scenario = scenario;
		this.isUsingZones = isUsingZones;
		this.maxIterationsMATSim = maxIterationsMATSim;
		this.jspritIterations = jspritIterations;
		hagrid.core.config.ResolvedHagridConfig.rejectRemoved("routing.zoneBasedCachingEnabled",zoneBasedCachingEnabled);

		this.uTurnPenaltyCost = uTurnPenaltyCost;
	}

	ReplanningStrategies replanningStrategies() {
		return routingConfig == null
                ? new ReplanningStrategies(types,isUsingZones,maxIterationsMATSim,jspritIterations,uTurnPenaltyCost)
                : new ReplanningStrategies(types,isUsingZones,maxIterationsMATSim,routingConfig);
	}

	@Inject
	private Config config;

	@Override
	public void install() {
		// Scoring and replanning function
        var scoringConfig = routingConfig == null ? hagrid.core.config.HagridConfigResolver.resolve(
                new hagrid.core.HagridConfig(), hagrid.core.config.HagridConfigResolver.Overrides.empty(),
                hagrid.core.config.HagridConfigResolver.Overrides.of(Map.of("tourRules.uTurnPenaltyCost",Double.toString(uTurnPenaltyCost))), Map.of()) : routingConfig;
        var executionLedger = new hagrid.core.simulation.scoring.CarrierExecutionLedger(scenario,scoringConfig);
        bind(hagrid.core.simulation.scoring.CarrierExecutionLedger.class).toInstance(executionLedger);
        addEventHandlerBinding().toInstance(executionLedger);
        ScoringFunctions scoringFunctionFactory = new ScoringFunctions(scenario.getNetwork(),scoringConfig,executionLedger,hagrid.core.routing.SectionBindingCompanion.context(scenario));

		bind(Carriers.class).toProvider(new CarrierProvider()).asEagerSingleton();
        if (routingConfig != null) {
            bind(ResolvedHagridConfig.class).toInstance(routingConfig);
            bind(ValidatedCarrierReplanning.class).in(Singleton.class);
            addControlerListenerBinding().to(ValidatedCarrierReplanning.class);
        }
        bind(CarrierStrategyManager.class).toProvider(replanningStrategies());

		bind(CarrierScoringFunctionFactory.class).toInstance(scoringFunctionFactory);

		bind(VRPTransportCostsFactory.class).to(DefaultVRPTransportCostsFactory.class).in(Singleton.class);


		Carriers carriers = CarriersUtils.getCarriers(scenario);
        hagrid.core.routing.SectionBindingCompanion.requireStaticExecution(carriers,config.controller().getFirstIteration(),config.controller().getLastIteration());

		final CarrierScoreStats scores = new CarrierScoreStats(carriers,
				config.controller().getOutputDirectory() + "/carrier_scores", true);

		final LegHistogram freightOnly = new LegHistogram(900);
		freightOnly.setInclPop(false);
		binder().requestInjection(freightOnly);
		final LegHistogram withoutFreight = new LegHistogram(900);
		binder().requestInjection(withoutFreight);

		addEventHandlerBinding().toInstance(withoutFreight);
		addEventHandlerBinding().toInstance(freightOnly);
		addControlerListenerBinding().toInstance(scores);
        int histogramInterval = routingConfig == null ? 1 : routingConfig.results().freightHistogramWriteInterval();
        addControlerListenerBinding().toInstance(new FreightHistogramOutput(freightOnly, withoutFreight, histogramInterval));

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
	private DefaultVRPTransportCostsFactory provideNetworkBasedTransportCostsFactory(Scenario scenario,
			Carriers carriers, Map<String, TravelTime> travelTimes, Config config) {

		return new DefaultVRPTransportCostsFactory(scenario, carriers, travelTimes, config,
				false, 0);
	}

}
