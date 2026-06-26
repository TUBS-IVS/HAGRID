package hagrid.integrated.drt;

import org.matsim.api.core.v01.TransportMode;
import org.matsim.contrib.common.zones.systems.grid.square.SquareGridZoneSystemParams;
import org.matsim.contrib.drt.analysis.zonal.DrtZoneSystemParams;
import org.matsim.contrib.drt.optimizer.constraints.DefaultDrtOptimizationConstraintsSet;
import org.matsim.contrib.drt.optimizer.constraints.DrtOptimizationConstraintsParams;
import org.matsim.contrib.drt.optimizer.insertion.extensive.ExtensiveInsertionSearchParams;
import org.matsim.contrib.drt.optimizer.rebalancing.RebalancingParams;
import org.matsim.contrib.drt.optimizer.rebalancing.mincostflow.MinCostFlowRebalancingStrategyParams;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.run.DrtConfigs;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.contrib.drt.run.MultiModeDrtModule;
import org.matsim.contrib.dvrp.run.DvrpConfigGroup;
import org.matsim.contrib.dvrp.run.DvrpModule;
import org.matsim.contrib.dvrp.run.DvrpQSimComponents;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.QSimConfigGroup;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.controler.Controler;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Composes the native Lausitz DRT configuration into a HAGRID {@link Config},
 * with two deliberate divergences from the native setup: full DVRP simulation
 * (real dispatched fleet) and DRT-only (no PT intermodality). Parameter values
 * mirror matsim-lausitz {@code LausitzDrtScenario}/{@code DrtAndIntermodalityOptions}.
 */
public final class DrtConfigComposer {

    // Native Lausitz DRT parameters (verbatim).
    private static final double STOP_DURATION_S = 60.0;
    private static final double MAX_WAIT_TIME_S = 1200.0;
    private static final double MAX_TRAVEL_TIME_ALPHA = 1.5;
    private static final double MAX_TRAVEL_TIME_BETA_S = 1200.0;

    // Depot-dispatching rebalancing (PoC defaults).
    private static final double ZONE_CELL_SIZE_M = 2000.0;
    private static final int REBALANCE_INTERVAL_S = 1800;
    private static final int DEMAND_ESTIMATION_PERIOD_S = 1800;

    private DrtConfigComposer() {}

    /**
     * Composes DRT config groups into {@code config} and also registers a
     * {@link ScoringConfigGroup.ModeParams} for the {@code drt} leg mode so that
     * CharyparNagel scoring does not crash on the first scored DRT leg.
     */
    public static void composeConfig(Config config, String serviceAreaShp, String fleetFile) {
        DvrpConfigGroup dvrp = ConfigUtils.addOrGetModule(config, DvrpConfigGroup.class);
        dvrp.networkModes = Set.of(TransportMode.drt);

        MultiModeDrtConfigGroup multi = ConfigUtils.addOrGetModule(config, MultiModeDrtConfigGroup.class);
        if (multi.getModalElements().isEmpty()) {
            DrtConfigGroup drt = new DrtConfigGroup();
            drt.mode = TransportMode.drt;
            drt.operationalScheme = DrtConfigGroup.OperationalScheme.serviceAreaBased;
            drt.stopDuration = STOP_DURATION_S;
            drt.simulationType = DrtConfigGroup.SimulationType.fullSimulation;
            drt.drtServiceAreaShapeFile = serviceAreaShp;
            drt.vehiclesFile = fleetFile;

            DrtOptimizationConstraintsParams constraints = drt.addOrGetDrtOptimizationConstraintsParams();
            DefaultDrtOptimizationConstraintsSet set =
                    (DefaultDrtOptimizationConstraintsSet) constraints.addOrGetDefaultDrtOptimizationConstraintsSet();
            set.maxWaitTime = MAX_WAIT_TIME_S;
            set.maxTravelTimeAlpha = MAX_TRAVEL_TIME_ALPHA;
            set.maxTravelTimeBeta = MAX_TRAVEL_TIME_BETA_S;

            drt.setDrtInsertionSearchParams(new ExtensiveInsertionSearchParams());

            // Rebalancing zones: square grid over the service area.
            DrtZoneSystemParams zoneParams = new DrtZoneSystemParams();
            zoneParams.targetLinkSelection = DrtZoneSystemParams.TargetLinkSelection.mostCentral;
            SquareGridZoneSystemParams gridParams = new SquareGridZoneSystemParams();
            gridParams.cellSize = ZONE_CELL_SIZE_M;
            zoneParams.addParameterSet(gridParams);
            drt.addParameterSet(zoneParams);

            // Demand-based MinCostFlow rebalancing (idle vehicles flow toward demand;
            // balanced zones keep their vehicles -> "stay put" emerges naturally).
            RebalancingParams rebalancing = new RebalancingParams();
            rebalancing.interval = REBALANCE_INTERVAL_S;
            MinCostFlowRebalancingStrategyParams mcf = new MinCostFlowRebalancingStrategyParams();
            mcf.rebalancingTargetCalculatorType =
                    MinCostFlowRebalancingStrategyParams.RebalancingTargetCalculatorType.EstimatedDemand;
            mcf.zonalDemandEstimatorType =
                    MinCostFlowRebalancingStrategyParams.ZonalDemandEstimatorType.PreviousIterationDemand;
            mcf.demandEstimationPeriod = DEMAND_ESTIMATION_PERIOD_S;
            mcf.targetAlpha = 0.5;
            mcf.targetBeta = 0.5;
            rebalancing.addParameterSet(mcf);
            drt.addParameterSet(rebalancing);

            multi.addParameterSet(drt);
        }

        // DynAgents need only the start time.
        config.qsim().setSimStarttimeInterpretation(QSimConfigGroup.StarttimeInterpretation.onlyUseStarttime);

        // Register drt leg-mode scoring params so CharyparNagel scorer does not crash.
        // DrtConfigs.adjustMultiModeDrtConfig only adds the staging-activity entry, not the leg mode.
        // Guard: pt params may not exist in a DRT-only setup.
        ScoringConfigGroup scoring = config.scoring();
        if (!scoring.getModes().containsKey(TransportMode.drt)) {
            ScoringConfigGroup.ModeParams drtParams = new ScoringConfigGroup.ModeParams(TransportMode.drt);
            ScoringConfigGroup.ModeParams ptParams = scoring.getModes().get(TransportMode.pt);
            if (ptParams != null) {
                drtParams.setConstant(ptParams.getConstant()); // align drt ASC with pt when present
            }
            drtParams.setMarginalUtilityOfTraveling(-0.0);
            scoring.addModeParams(drtParams);
        }

        // Staging activity + drt scoring/routing params (core helper, not Lausitz-specific).
        DrtConfigs.adjustMultiModeDrtConfig(multi, config.scoring(), config.routing());

        // Offer drt in mode choice (DRT-only: no intermodal access/egress).
        List<String> modes = new ArrayList<>(List.of(config.subtourModeChoice().getModes()));
        if (!modes.contains(TransportMode.drt)) {
            modes.add(TransportMode.drt);
            config.subtourModeChoice().setModes(modes.toArray(new String[0]));
        }
    }

    public static void installModules(Controler controler) {
        Config config = controler.getConfig();
        controler.addOverridingModule(new DvrpModule());
        controler.addOverridingModule(new MultiModeDrtModule());
        controler.configureQSimComponents(
                DvrpQSimComponents.activateAllModes(MultiModeDrtConfigGroup.get(config)));
    }
}
