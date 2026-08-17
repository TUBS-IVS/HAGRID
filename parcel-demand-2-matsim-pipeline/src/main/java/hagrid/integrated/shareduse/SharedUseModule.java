package hagrid.integrated.shareduse;

import com.google.inject.Provider;
import com.google.inject.TypeLiteral;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.contrib.common.zones.ZoneSystem;
import org.matsim.contrib.drt.optimizer.DrtRequestInsertionRetryParams;
import org.matsim.contrib.drt.optimizer.DrtRequestInsertionRetryQueue;
import org.matsim.contrib.drt.optimizer.insertion.CostCalculationStrategy;
import org.matsim.contrib.drt.optimizer.insertion.DefaultInsertionCostCalculator;
import org.matsim.contrib.drt.optimizer.insertion.InsertionCostCalculator;
import org.matsim.contrib.drt.optimizer.rebalancing.RebalancingModule;
import org.matsim.contrib.drt.optimizer.rebalancing.demandestimator.ZonalDemandEstimator;
import org.matsim.contrib.drt.optimizer.rebalancing.mincostflow.MinCostFlowRebalancingStrategyParams;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.stops.MinimumStopDurationAdapter;
import org.matsim.contrib.drt.stops.ParallelStopTimeCalculator;
import org.matsim.contrib.drt.stops.PassengerStopDurationProvider;
import org.matsim.contrib.drt.stops.StopTimeCalculator;
import org.matsim.contrib.dvrp.load.DvrpLoadFromFleet;
import org.matsim.contrib.dvrp.load.DvrpLoadType;
import org.matsim.contrib.dvrp.run.AbstractDvrpModeModule;
import org.matsim.contrib.dvrp.run.AbstractDvrpModeQSimModule;

import java.util.Map;

/**
 * Shared-Use (cargo-hitching) composition for a DRT mode. This is the
 * CONTROLLER-scope half: per-request stop dwell and the 2D fleet capacity.
 *
 * <p>It overrides three bindings that {@code DrtModeModule}/{@code FleetModule}
 * install by default, so it MUST be added via {@code controler.addOverridingModule}
 * AFTER {@code DrtConfigComposer.installModules} (the same override-ordering
 * mechanism {@code PtAndDrtFareModule} relies on):
 * <ul>
 *   <li>{@link PassengerStopDurationProvider} — replaced by a
 *       {@link SharedUseStopDurationProvider} snapshotting parcel load + dwell;</li>
 *   <li>{@link StopTimeCalculator} — a {@link ParallelStopTimeCalculator} (prices a
 *       shared pax+parcel stop as {@code max(durations)}) wrapped by a
 *       {@link MinimumStopDurationAdapter} floor, mirroring the native prebooking
 *       branch of {@code DrtModeModule};</li>
 *   <li>{@link DvrpLoadFromFleet} — gives every vehicle {@link SharedUse#PARCEL_SLOTS}
 *       parcel slots in addition to the fleet-XML seat scalar (the default fleet
 *       loader would leave {@code "parcels"} at 0);</li>
 *   <li>the modal {@code ZonalDemandEstimator} — replaced by a
 *       {@link PaxOnlyPreviousIterationDrtDemandEstimator} (1c M7) so rebalancing sees
 *       PASSENGER demand only, excluding the parcel phantom depot departures.</li>
 * </ul>
 *
 * <p>The QSim overrides — the χ-gate on parcel insertion
 * ({@link ChiGateInsertionCostCalculator}) and the parcel-only pending/retry queue
 * ({@link ParcelOnlyRetryQueue}) — are installed via {@code installOverridingQSimModule}
 * at the end of {@link #install()} (QSim-scope keys must not be bound at controller scope).
 */
public final class SharedUseModule extends AbstractDvrpModeModule {

    private final DrtConfigGroup drtCfg;
    /** χ-gate threshold (seconds of max acceptable DRIVE-only added vehicle time per
     *  parcel insertion — each leg's own stop duration is subtracted from that leg;
     *  &lt; 0 = gate hard-closed, rejects all parcels); consumed by the QSim-half
     *  {@link ChiGateInsertionCostCalculator}. */
    private final double chiThreshold;

    public SharedUseModule(DrtConfigGroup drtCfg, double chiThreshold) {
        super(drtCfg.getMode());
        this.drtCfg = drtCfg;
        this.chiThreshold = chiThreshold;
    }

    @Override
    public void install() {
        // Per-request dwell. Snapshot parcel load (for scaled depot pickup) and door dwell
        // (dropoff) from the population; parcel-persons are static, so the snapshot stays valid.
        bindModal(PassengerStopDurationProvider.class).toProvider(modalProvider(getter -> {
            Population population = getter.get(Population.class);
            // STRICT: a parcel-person without a usable load/dwell attribute aborts the run here
            // (eager singleton -> at startup). The previous lenient snapshot let such a person
            // through and the provider then dwelled it as a single parcel, quietly understating
            // both depot pickup and door service. See ParcelAttributes.
            return new SharedUseStopDurationProvider(drtCfg.getStopDuration(),
                    ParcelAttributes.loads(population), ParcelAttributes.dwells(population));
        })).asEagerSingleton();

        bindModal(StopTimeCalculator.class).toProvider(modalProvider(getter ->
                new MinimumStopDurationAdapter(
                        new ParallelStopTimeCalculator(getter.getModal(PassengerStopDurationProvider.class)),
                        drtCfg.getStopDuration()))).asEagerSingleton();

        // 2D fleet capacity: fleet XML scalar -> seats ("passengers"), plus fixed parcel slots.
        bindModal(DvrpLoadFromFleet.class).toProvider(modalProvider(getter -> {
            DvrpLoadType loadType = getter.getModal(DvrpLoadType.class);
            return (capacity, vehicleId) -> loadType.fromMap(Map.<String, Number>of(
                    "passengers", capacity,                 // fleet XML scalar = seats
                    "parcels", SharedUse.PARCEL_SLOTS));     // repurposed back-bench volume
        })).asEagerSingleton();

        // delta / channel KPIs (Task 7): tracks submitted/delivered/rejected parcel requests
        // via the native DVRP passenger events and writes shareduse_channel_stats.csv at
        // shutdown. Controller-scope singleton (not modal): the handler keys everything by
        // request id / person id itself, so it never needs a modal binding.
        // M6 χ-gate instrumentation. Controller-scope singleton BECAUSE the gate is rebuilt in
        // the QSim child injector every iteration: counters living on the gate would be thrown
        // away before the KPI handler could read them. The gate writes, the handler reads and
        // resets — so both MUST receive this same instance.
        bind(ChiGateStats.class).asEagerSingleton();

        bind(SharedUseKpiHandler.class).asEagerSingleton();
        addEventHandlerBinding().to(SharedUseKpiHandler.class);
        addControlerListenerBinding().to(SharedUseKpiHandler.class);

        // M7 (Task, final-review C2): PASSENGER-only rebalancing demand. The stock
        // PreviousIterationDrtDemandEstimator (bound by DrtModeMinCostFlowRebalancingModule) counts
        // EVERY drt PersonDepartureEvent - including the parcel phantom departures at the depot - so
        // idle-vehicle rebalancing chases parcel demand and the χ=0 pax validation is confounded.
        // Rebind the modal ZonalDemandEstimator to a pax-only estimator. Because SharedUseModule is
        // added LAST via addOverridingModule (each call nests Modules.override(previous).with(this)),
        // this controller-scope binding OVERRIDES the stock one; both DrtModeMinCostFlowRebalancingModule
        // and ReturnToDepotRebalancingModule resolve the estimator via getModal(ZonalDemandEstimator.class)
        // (visible from their QSim child injector), so they pick up the pax-only demand. Bound ONLY when
        // the stock estimator would be bound (MinCostFlow + PreviousIterationDemand); otherwise there is
        // no binding to override and the eager-singleton provider would fail resolving the (unbound)
        // rebalancing zone system. The stock estimator singleton still exists but its output is shadowed.
        drtCfg.getRebalancingParams().ifPresent(rebalancing -> {
            if (rebalancing.getRebalancingStrategyParams() instanceof MinCostFlowRebalancingStrategyParams mcf
                    && mcf.getZonalDemandEstimatorType()
                        == MinCostFlowRebalancingStrategyParams.ZonalDemandEstimatorType.PreviousIterationDemand) {
                int period = mcf.getDemandEstimationPeriod();
                bindModal(PaxOnlyPreviousIterationDrtDemandEstimator.class).toProvider(modalProvider(getter -> {
                    ZoneSystem zones = getter.getModal(new TypeLiteral<Map<String, Provider<ZoneSystem>>>() {})
                            .get(RebalancingModule.REBALANCING_ZONE_SYSTEM).get();
                    return new PaxOnlyPreviousIterationDrtDemandEstimator(zones, getMode(), period);
                })).asEagerSingleton();
                bindModal(ZonalDemandEstimator.class).to(modalKey(PaxOnlyPreviousIterationDrtDemandEstimator.class));
                addEventHandlerBinding().to(modalKey(PaxOnlyPreviousIterationDrtDemandEstimator.class));
                addControlerListenerBinding().to(modalKey(PaxOnlyPreviousIterationDrtDemandEstimator.class));
            }
        });

        // ---- QSim half (Task 5) --------------------------------------------------------
        // The χ-acceptance gate and the parcel-only pending/retry queue are QSim-scope keys.
        // They MUST be bound inside a QSim module (binding them at controller scope would
        // collide with the native bindings -> BindingAlreadySet). installOverridingQSimModule
        // lets this override the native DrtModeOptimizerQSimModule bindings for the same keys.
        installOverridingQSimModule(new AbstractDvrpModeQSimModule(getMode()) {
            @Override
            protected void configureQSim() {
                // χ-gate wraps the native DefaultInsertionCostCalculator (constructed exactly as
                // DrtModeOptimizerQSimModule does) and rejects a parcel insertion whose DRIVE-only
                // time loss (each leg's time loss minus THAT leg's own stop duration, clamped per
                // leg — see ChiGateInsertionCostCalculator, METHODS-LOG 2.35) exceeds χ;
                // χ<0 = hard-closed (rejects all parcels). The modal DvrpLoadType (controller-scope,
                // visible from the QSim child injector) lets the gate read the request's parcel
                // count off its DvrpLoad. Pax and kept parcels keep the delegate's cost.
                bindModal(InsertionCostCalculator.class).toProvider(modalProvider(getter ->
                        new ChiGateInsertionCostCalculator(
                                new DefaultInsertionCostCalculator(
                                        getter.getModal(CostCalculationStrategy.class),
                                        drtCfg.addOrGetDrtOptimizationConstraintsParams()
                                                .addOrGetDefaultDrtOptimizationConstraintsSet()),
                                chiThreshold,
                                getter.getModal(DvrpLoadType.class),
                                // The SAME floor the MinimumStopDurationAdapter above got. Read
                                // from drtCfg both times on purpose: two literals would let the
                                // gate subtract a dwell the schedule never contained.
                                drtCfg.getStopDuration(),
                                // Controller-scope singleton, visible from the QSim child
                                // injector (same resolution path as DvrpLoadType above).
                                getter.get(ChiGateStats.class))));

                // Parcel-only pending queue: pax rejections stay native-immediate; parcels retry
                // until the global maxRequestAge OR their own per-request delivery window (M5).
                // Must be a singleton — it holds the shared pending-queue state.
                bindModal(DrtRequestInsertionRetryQueue.class).toProvider(modalProvider(getter -> {
                    Population population = getter.get(Population.class);
                    // STRICT (see ParcelAttributes): a parcel-person without a delivery window
                    // used to be treated as "never expires", silently disabling M5 for it.
                    Map<Id<Person>, Double> windowEndById = ParcelAttributes.windowEnds(population);
                    // NOT orElseGet(new DrtRequestInsertionRetryParams()): the MATSim default
                    // is maxRequestAge=0, i.e. NO RETRY. Substituting it would silently turn
                    // every chi-rejected parcel into a hard rejection, disable the M5 per-request
                    // delivery window this queue exists to enforce, and move the whole delta
                    // signal from segments_window_expired to segments_rejected_final — a wrong
                    // result with no crash. The params are installed by
                    // DrtConfigComposer.composeSharedUse (86400 s / 300 s); their absence means
                    // the Shared-Use config composition was skipped, which is a wiring bug.
                    DrtRequestInsertionRetryParams retryParams = drtCfg.getDrtRequestInsertionRetryParams()
                            .orElseThrow(() -> new IllegalStateException(
                                    "Shared-Use requires drtRequestInsertionRetryParams on mode '"
                                    + getMode() + "'. Call DrtConfigComposer.composeSharedUse(config)"
                                    + " before installing SharedUseModule."));
                    return new ParcelOnlyRetryQueue(retryParams, windowEndById);
                })).asEagerSingleton();
            }
        });
    }
}
