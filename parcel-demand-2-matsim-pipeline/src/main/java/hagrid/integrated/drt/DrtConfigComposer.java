package hagrid.integrated.drt;

import org.matsim.api.core.v01.TransportMode;
import org.matsim.contrib.common.zones.systems.grid.square.SquareGridZoneSystemParams;
import org.matsim.contrib.drt.optimizer.DrtRequestInsertionRetryParams;
import org.matsim.contrib.drt.optimizer.constraints.DrtOptimizationConstraintsParams;
import org.matsim.contrib.drt.optimizer.constraints.DrtOptimizationConstraintsSetImpl;
import org.matsim.contrib.drt.optimizer.insertion.extensive.ExtensiveInsertionSearchParams;
import org.matsim.contrib.drt.optimizer.rebalancing.RebalancingParams;
import org.matsim.contrib.drt.optimizer.rebalancing.mincostflow.MinCostFlowRebalancingStrategyParams;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.run.DrtConfigs;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.contrib.drt.run.MultiModeDrtModule;
import org.matsim.contrib.dvrp.load.DvrpLoadParams;
import org.matsim.contrib.dvrp.run.DvrpConfigGroup;
import org.matsim.contrib.dvrp.run.DvrpModule;
import org.matsim.contrib.dvrp.run.DvrpQSimComponents;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.QSimConfigGroup;
import org.matsim.core.config.groups.ReplanningConfigGroup;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.controler.Controler;

import hagrid.integrated.shareduse.SharedUse;

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

    private static final org.apache.logging.log4j.Logger LOG =
            org.apache.logging.log4j.LogManager.getLogger(DrtConfigComposer.class);

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
        dvrp.setNetworkModes(Set.of(TransportMode.drt));

        MultiModeDrtConfigGroup multi = ConfigUtils.addOrGetModule(config, MultiModeDrtConfigGroup.class);
        if (multi.getModalElements().isEmpty()) {
            DrtConfigGroup drt = new DrtConfigGroup();
            drt.setMode(TransportMode.drt);
            drt.setOperationalScheme(DrtConfigGroup.OperationalScheme.serviceAreaBased);
            drt.setStopDuration(STOP_DURATION_S);
            drt.setSimulationType(DrtConfigGroup.SimulationType.fullSimulation);
            drt.setDrtServiceAreaShapeFile(serviceAreaShp);
            drt.setVehiclesFile(fleetFile);

            DrtOptimizationConstraintsParams constraints = drt.addOrGetDrtOptimizationConstraintsParams();
            DrtOptimizationConstraintsSetImpl set =
                    constraints.addOrGetDefaultDrtOptimizationConstraintsSet();
            set.maxWaitTime = MAX_WAIT_TIME_S;
            set.maxTravelTimeAlpha = MAX_TRAVEL_TIME_ALPHA;
            set.maxTravelTimeBeta = MAX_TRAVEL_TIME_BETA_S;

            drt.setDrtInsertionSearchParams(new ExtensiveInsertionSearchParams());

            // Demand-based MinCostFlow rebalancing (idle vehicles flow toward demand;
            // balanced zones keep their vehicles -> "stay put" emerges naturally).
            RebalancingParams rebalancing = new RebalancingParams();
            rebalancing.setInterval(REBALANCE_INTERVAL_S);
            // Rebalancing zones: square grid over the service area, targeting the most central
            // link. In matsim 2025.0 the zone system + target-link selection live on
            // RebalancingParams (were DrtZoneSystemParams on the DRT group in PR3552).
            rebalancing.setTargetLinkSelection(RebalancingParams.TargetLinkSelection.mostCentral);
            SquareGridZoneSystemParams gridParams = new SquareGridZoneSystemParams();
            gridParams.setCellSize(ZONE_CELL_SIZE_M);
            rebalancing.addParameterSet(gridParams);

            MinCostFlowRebalancingStrategyParams mcf = new MinCostFlowRebalancingStrategyParams();
            mcf.setRebalancingTargetCalculatorType(
                    MinCostFlowRebalancingStrategyParams.RebalancingTargetCalculatorType.EstimatedDemand);
            mcf.setZonalDemandEstimatorType(
                    MinCostFlowRebalancingStrategyParams.ZonalDemandEstimatorType.PreviousIterationDemand);
            mcf.setDemandEstimationPeriod(DEMAND_ESTIMATION_PERIOD_S);
            // alpha=1, beta=0 documents what actually runs: ReturnToDepotRebalancingModule
            // overrides the RebalancingTargetCalculator with a plain
            // DemandEstimatorAsTargetCalculator (target = 1.0 * estimated demand + 0), so any
            // other alpha/beta here would be dead config misleading output_config readers.
            // (The fields are bean-validated >= 0, they cannot simply be omitted.)
            mcf.setTargetAlpha(1.0);
            mcf.setTargetBeta(0.0);
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

    /**
     * SHAREDUSE (cargo-hitching) additions on top of the composed baseline DRT config
     * (call AFTER {@link #composeConfig}). Applies:
     * <ul>
     *   <li>a native 2D {@code IntegersLoadType} via {@code DvrpLoadParams}: the fleet
     *       XML capacity scalar maps to {@code "passengers"} (seats), plus a separate
     *       {@code "parcels"} dimension (the per-vehicle 20-slot capacity is injected in
     *       {@code SharedUseModule} via a {@code DvrpLoadFromFleet} override);</li>
     *   <li>an all-day request-retry window so rejected (parcel) requests stay pending
     *       until day end — Task 5's {@code ParcelOnlyRetryQueue} narrows this to the
     *       per-request M5 delivery window on top of this global ceiling;</li>
     *   <li>a selector-only ({@code ChangeExpBeta}) replanning strategy for the parcel
     *       subpopulation: parcel-persons are static, so no plan innovation;</li>
     *   <li>non-scoring parcel activity types (depot + delivery segment).</li>
     * </ul>
     */
    public static void composeSharedUse(Config config) {
        DrtConfigGroup drt = MultiModeDrtConfigGroup.get(config).getModalElements().iterator().next();

        // 2D load: two dimensions built into an IntegersLoadType by DvrpLoadModule.
        DvrpLoadParams load = drt.addOrGetLoadParams();          // param set "load"
        load.setDimensions(List.of("passengers", "parcels"));
        load.setMapFleetCapacity("passengers");                  // fleet XML scalar -> seats
        load.setDefaultRequestDimension("passengers");           // pax legs carry no load attributes

        // All-day pending window (global ceiling); per-request window is Task 5's queue concern.
        DrtRequestInsertionRetryParams retry = new DrtRequestInsertionRetryParams();
        retry.setRetryInterval(300);
        retry.setMaxRequestAge(86400.0);
        drt.addParameterSet(retry);

        // Parcel subpopulation: pure selector, no innovation.
        ReplanningConfigGroup.StrategySettings parcelSelector = new ReplanningConfigGroup.StrategySettings();
        parcelSelector.setStrategyName("ChangeExpBeta");
        parcelSelector.setSubpopulation(SharedUse.PARCEL_SUBPOPULATION);
        parcelSelector.setWeight(1.0);
        config.replanning().addStrategySettings(parcelSelector);

        // Non-scoring parcel activity types.
        for (String actType : List.of(SharedUse.ACT_DEPOT, SharedUse.ACT_DELIVERY)) {
            ScoringConfigGroup.ActivityParams params = new ScoringConfigGroup.ActivityParams(actType);
            params.setScoringThisActivityAtAll(false);
            config.scoring().addActivityParams(params);
        }
    }

    public static void installModules(Controler controler) {
        Config config = controler.getConfig();
        controler.addOverridingModule(new DvrpModule());
        controler.addOverridingModule(new MultiModeDrtModule());
        controler.configureQSimComponents(
                DvrpQSimComponents.activateAllModes(MultiModeDrtConfigGroup.get(config)));

        // PT/DRT fares: the native PtAndDrtFareModule prices DRT exactly like PT (VVO zone /
        // Deutschlandtarif from the ptFare config, composed in LausitzDrtConfigurator) and caps
        // chained DRT+PT rides via the fare upper bound — without it pt AND drt ride monetarily
        // free while car pays per km, making drt structurally over-attractive in mode choice.
        // Guarded: the module touches the pt scoring ModeParams at install time, so only add it
        // when fares are composed AND pt params exist (both true on the Lausitz paths).
        // NOTE: check module PRESENCE without addOrGetModule — materialising an EMPTY
        // PtFareConfigGroup fails MATSim's consistency check ("No parameter sets found").
        org.matsim.core.config.ConfigGroup ptFare = config.getModules()
                .get(org.matsim.contrib.vsp.pt.fare.PtFareConfigGroup.MODULE_NAME);
        boolean faresComposed = ptFare != null && !ptFare.getParameterSets(
                org.matsim.contrib.vsp.pt.fare.FareZoneBasedPtFareParams.SET_TYPE).isEmpty();
        boolean ptScoringPresent = config.scoring().getModes().containsKey(TransportMode.pt);
        if (faresComposed && ptScoringPresent) {
            controler.addOverridingModule(new org.matsim.drt.PtAndDrtFareModule());
            LOG.info("PtAndDrtFareModule installed - drt is fared like pt (VVO zone / Deutschlandtarif).");
        } else {
            // Skipping is a MODE-CHOICE-CHANGING outcome, not a detail: drt and pt then ride
            // monetarily free while car keeps paying per km, which is exactly the asymmetry this
            // module exists to remove. Say which precondition failed - silently taking this
            // branch is how a run ends up with structurally over-attractive drt.
            LOG.warn("PtAndDrtFareModule NOT installed (faresComposed={}, ptScoringParams={}):"
                    + " drt and pt ride MONETARILY FREE while car pays per km, so mode choice is"
                    + " biased toward drt. Expected on the Lausitz paths is both true - check"
                    + " LausitzDrtConfigurator.composePtFareConfig and the base config's pt scoring.",
                    faresComposed, ptScoringPresent);
        }
    }

    /**
     * Installs DRT modules AND a {@link ReturnToDepotRebalancingModule} that
     * overrides the rebalancing target calculator with a time-switched one:
     * demand-based before {@code returnStart}, depot-targeting thereafter.
     *
     * @param depotCoords          depot coordinates in the network CRS
     * @param returnStart          simulation time (seconds) at which vehicles start homing
     * @param perDepotCapacity     parking capacity per depot: each depot zone pulls at most
     *                             this many vehicles, so MinCostFlow fills the nearest depot
     *                             and overflows to the next once full
     * @param demandEstimationPeriod  length of the demand-estimation window (seconds)
     */
    public static void installModules(Controler controler,
                                      java.util.List<org.matsim.api.core.v01.Coord> depotCoords,
                                      double returnStart,
                                      double perDepotCapacity,
                                      double demandEstimationPeriod) {
        installModules(controler);
        controler.addOverridingModule(new ReturnToDepotRebalancingModule(
                org.matsim.api.core.v01.TransportMode.drt,
                depotCoords, returnStart, perDepotCapacity, demandEstimationPeriod));
    }
}
