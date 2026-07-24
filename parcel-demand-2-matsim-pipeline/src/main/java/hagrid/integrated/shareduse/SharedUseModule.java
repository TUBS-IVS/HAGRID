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

import java.util.HashMap;
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
    /** χ-gate threshold (seconds of max acceptable added vehicle time per parcel
     *  insertion); consumed by the QSim-half {@link ChiGateInsertionCostCalculator}. */
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
            Map<Id<Person>, Integer> loadById = new HashMap<>();
            Map<Id<Person>, Double> dwellById = new HashMap<>();
            population.getPersons().values().stream()
                    .filter(p -> SharedUse.isParcelPerson(p.getId().toString()))
                    .forEach(p -> {
                        Object load = p.getAttributes().getAttribute(SharedUse.LOAD_ATTRIBUTE);
                        if (load instanceof Number n) {
                            loadById.put(p.getId(), n.intValue());
                        }
                        Object dwell = p.getAttributes().getAttribute(SharedUse.DWELL_ATTRIBUTE);
                        if (dwell instanceof Number n) {
                            dwellById.put(p.getId(), n.doubleValue());
                        }
                    });
            return new SharedUseStopDurationProvider(drtCfg.getStopDuration(), loadById, dwellById);
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
                // DrtModeOptimizerQSimModule does) and rejects a parcel insertion whose raw
                // totalTimeLoss exceeds χ; pax and sub-χ parcels keep the delegate's cost.
                bindModal(InsertionCostCalculator.class).toProvider(modalProvider(getter ->
                        new ChiGateInsertionCostCalculator(
                                new DefaultInsertionCostCalculator(
                                        getter.getModal(CostCalculationStrategy.class),
                                        drtCfg.addOrGetDrtOptimizationConstraintsParams()
                                                .addOrGetDefaultDrtOptimizationConstraintsSet()),
                                chiThreshold)));

                // Parcel-only pending queue: pax rejections stay native-immediate; parcels retry
                // until the global maxRequestAge OR their own per-request delivery window (M5).
                // Must be a singleton — it holds the shared pending-queue state.
                bindModal(DrtRequestInsertionRetryQueue.class).toProvider(modalProvider(getter -> {
                    Population population = getter.get(Population.class);
                    Map<Id<Person>, Double> windowEndById = new HashMap<>();
                    population.getPersons().values().stream()
                            .filter(p -> SharedUse.isParcelPerson(p.getId().toString()))
                            .forEach(p -> {
                                Object windowEnd = p.getAttributes().getAttribute(SharedUse.WINDOW_END_ATTRIBUTE);
                                if (windowEnd instanceof Number n) {
                                    windowEndById.put(p.getId(), n.doubleValue());
                                }
                            });
                    DrtRequestInsertionRetryParams retryParams = drtCfg.getDrtRequestInsertionRetryParams()
                            .orElseGet(DrtRequestInsertionRetryParams::new);
                    return new ParcelOnlyRetryQueue(retryParams, windowEndById);
                })).asEagerSingleton();
            }
        });
    }
}
