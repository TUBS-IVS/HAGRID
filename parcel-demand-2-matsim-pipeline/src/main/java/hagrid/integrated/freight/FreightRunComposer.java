package hagrid.integrated.freight;

import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.replanning.GenericPlanStrategyImpl;
import org.matsim.core.replanning.selectors.KeepSelected;
import org.matsim.freight.carriers.CarriersUtils;
import org.matsim.freight.carriers.FreightCarriersConfigGroup;
import org.matsim.freight.carriers.controller.CarrierControllerUtils;
import org.matsim.freight.carriers.controller.CarrierModule;
import org.matsim.freight.carriers.controller.CarrierScoringFunctionFactory;
import org.matsim.freight.carriers.controller.CarrierStrategyManager;

/**
 * Composes the offline-routed LMD carriers into a MATSim run. Shared by the freight-only
 * {@code LMD_BASELINE} (maxIter=0) and the married {@code DRT_BASELINE} (pax DRT + LMD vans
 * in ONE Controler, maxIter&gt;0).
 *
 * <p>Carrier plans are produced offline by jsprit ({@link LausitzFreightPreprocessor}) and are
 * NOT innovated during the run: the strategy manager carries exactly one
 * {@code KeepSelected} strategy. An empty manager
 * ({@code createDefaultCarrierStrategyManager()} alone) throws
 * {@code RuntimeException} at the first replanning event — which only maxIter=0 runs survive.</p>
 */
public final class FreightRunComposer {

    private FreightRunComposer() {}

    /** Points the freight config group at the routed carriers and loads them into the scenario. */
    public static void addCarriers(Scenario scenario, String carriersFile, String vehicleTypesFile) {
        FreightCarriersConfigGroup freight =
                ConfigUtils.addOrGetModule(scenario.getConfig(), FreightCarriersConfigGroup.class);
        freight.setCarriersFile(carriersFile);
        freight.setCarriersVehicleTypesFile(vehicleTypesFile);
        CarriersUtils.loadCarriersAccordingToFreightConfig(scenario);
    }

    /**
     * Installs {@link CarrierModule} plus the two bindings it requires (scoring + strategy).
     * Safe for iterating runs: the KeepSelected strategy re-selects the fixed jsprit plan.
     */
    public static void installCarrierModules(Controler controler, Scenario scenario) {
        controler.addOverridingModule(new CarrierModule());
        controler.addOverridingModule(new AbstractModule() {
            @Override public void install() {
                bind(CarrierScoringFunctionFactory.class)
                        .toInstance(new hagrid.simulation.ScoringFunctions(scenario.getNetwork(), 0.0));
                bind(CarrierStrategyManager.class).toInstance(keepSelectedStrategyManager());
            }
        });
    }

    /** A carrier strategy manager whose single strategy re-selects the current plan (no innovation). */
    public static CarrierStrategyManager keepSelectedStrategyManager() {
        CarrierStrategyManager manager = CarrierControllerUtils.createDefaultCarrierStrategyManager();
        manager.addStrategy(new GenericPlanStrategyImpl<>(new KeepSelected<>()), null, 1.0);
        return manager;
    }
}
