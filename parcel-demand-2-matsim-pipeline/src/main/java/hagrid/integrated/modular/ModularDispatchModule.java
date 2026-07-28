package hagrid.integrated.modular;

import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.drt.optimizer.DefaultDrtOptimizer;
import org.matsim.contrib.drt.optimizer.DrtOptimizer;
import org.matsim.contrib.drt.optimizer.DrtRequestInsertionRetryQueue;
import org.matsim.contrib.drt.optimizer.VehicleDataEntryFactoryImpl;
import org.matsim.contrib.drt.optimizer.VehicleEntry;
import org.matsim.contrib.drt.optimizer.depot.DepotFinder;
import org.matsim.contrib.drt.optimizer.insertion.UnplannedRequestInserter;
import org.matsim.contrib.drt.optimizer.rebalancing.RebalancingStrategy;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.schedule.DrtStayTaskEndTimeCalculator;
import org.matsim.contrib.drt.schedule.DrtTaskFactory;
import org.matsim.contrib.drt.scheduler.DrtScheduleInquiry;
import org.matsim.contrib.drt.scheduler.EmptyVehicleRelocator;
import org.matsim.contrib.drt.stops.StopTimeCalculator;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;
import org.matsim.contrib.dvrp.fleet.Fleet;
import org.matsim.contrib.dvrp.load.DvrpLoadType;
import org.matsim.contrib.dvrp.run.AbstractDvrpModeModule;
import org.matsim.contrib.dvrp.run.AbstractDvrpModeQSimModule;
import org.matsim.contrib.dvrp.schedule.DriveTaskUpdater;
import org.matsim.contrib.dvrp.schedule.ScheduleTimingUpdater;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.mobsim.framework.MobsimTimer;
import org.matsim.core.router.costcalculators.TravelDisutilityFactory;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;

import java.util.List;

/**
 * Guice composition for DRT_MODULAR (1d, U-Shift capsule swap): one DRT fleet that serves
 * passengers AND executes offline jsprit-planned freight excursions. Controller half: the KPI
 * handler. QSim half (via {@code installOverridingQSimModule}, the {@code SharedUseModule}-proven
 * mechanism for QSim-scope keys): three REBINDS of native keys plus two new singletons.
 *
 * <ul>
 *   <li>{@link ScheduleTimingUpdater} — rebuilt exactly as {@code DrtModeOptimizerQSimModule}
 *       builds it (VERIFY-SOURCE: {@code DrtModeOptimizerQSimModule.java:187-190}), with Task 5's
 *       {@link ModularStayTaskEndTimeCalculator} wrapped AROUND the native
 *       {@link DrtStayTaskEndTimeCalculator}. The native calculator is constructed inline by the
 *       native module, so there is no separate key to decorate — the whole updater must be
 *       rebuilt. This is belt 1 against the silent duration corruption of spike §3.1 (a delayed
 *       freight dwell being DELETED, a shifted capsule swap being SHRUNK to the passenger
 *       stopDuration — both wrong timings with no exception).</li>
 *   <li>{@link VehicleEntry.EntryFactory} — {@link ModularEntryFactory} around the native
 *       {@link VehicleDataEntryFactoryImpl}: D2 strict lockout, so a vehicle with an unperformed
 *       freight task leaves the passenger insertion candidate set entirely.</li>
 *   <li>{@link DrtOptimizer} — {@link ModularOptimizer} around a MANUALLY constructed
 *       {@link DefaultDrtOptimizer} (the {@code DrtServiceOptimizerQSimModule} pattern from
 *       drt-extensions).</li>
 *   <li>new: {@link ModularTourScheduler} (the splicer) and {@link ModularTourDispatcher} (the
 *       online gate), both QSim-scope eager singletons, so all dispatch state resets per iteration
 *       by construction (the 1c {@code dd34b23} lesson).</li>
 * </ul>
 *
 * <p><b>Install order.</b> MUST be added LAST via {@code controler.addOverridingModule}, AFTER
 * {@code DrtConfigComposer.installModules} — each {@code addOverridingModule} call nests
 * {@code Modules.override(previous).with(this)}, so only a module added after the native DRT
 * modules can override their bindings.</p>
 *
 * <p><b>Binding rule (VERIFY-SOURCE: {@code AbstractModalQSimModule.addModalComponent}).</b>
 * {@link DrtOptimizer} is REBOUND with {@code bindModal(...).toProvider(...)} ONLY, never with
 * {@code addModalComponent}. {@code addModalComponent(X, provider)} expands to
 * {@code bindModal(X).toProvider(provider).asEagerSingleton()} PLUS
 * {@code addModalQSimComponentBinding().to(modalKey(X))} — the native
 * {@code DrtModeOptimizerQSimModule} already made that component registration, and a second one
 * would add a second multibinder entry for the same key, double-driving the optimizer
 * ({@code notifyMobsimBeforeSimStep} twice per simstep — i.e. the dispatcher ticking twice).
 * Rebinding the key alone is enough: the existing registration resolves through the (now
 * overridden) binding. The native {@code bindModal(VrpOptimizer.class).to(modalKey(DrtOptimizer
 * .class))} alias picks the decorator up for free.</p>
 *
 * <p><b>WIRING INVARIANT 1 (Task 6 review, both reviewers concurring — do not casually rewire).</b>
 * {@link ModularTourScheduler} MUST be constructed with the IDENTICAL modal {@link Network} object
 * instance that the DVRP fleet's {@link Link} references come from — not merely a network with
 * matching link ids. {@code ScheduleImpl.validateArgsBeforeAddingTask} enforces link continuity by
 * REFERENCE equality, and the splicer truncates the vehicle's trailing STAY before appending, with
 * no rollback: a foreign-but-same-id network therefore throws on the FIRST stop leg of EVERY tour
 * (every tour has at least one stop) and leaves a half-spliced schedule behind. VERIFY-SOURCE for
 * why {@code getter.getModal(Network.class)} is the right key: {@code FleetModule.java:114-116}
 * builds the fleet with {@code Fleets.createDefaultFleet(spec, getter.getModal(Network.class)
 * .getLinks()::get)}, and that modal binding resolves to the controller-scope eager singleton
 * {@code DvrpModule.java:125-127} installs for {@code @Named(DVRP_ROUTING) Network} (or, with
 * {@code useModeFilteredSubnetwork}, to {@code DvrpModeRoutingNetworkModule}'s own eager-singleton
 * subnetwork) — one instance per mode for the whole run either way. Because the invariant is
 * load-bearing and invisible at the call site, {@link #verifyFleetLinksComeFromNetwork} asserts it
 * at QSim startup instead of trusting the comment.</p>
 *
 * <p><b>WIRING INVARIANT 2 (Task 6 review, Minor 10).</b> {@link ModularTourScheduler} builds its
 * own {@code SpeedyALT} router in its constructor: ALT landmark preprocessing per instance, and the
 * router is NOT thread-safe. It is therefore bound {@code asEagerSingleton()} — one instance per
 * DVRP mode per QSim, never a per-injection instance. (The native module does the same for its own
 * {@code SpeedyALT} users: {@code DriveTaskUpdater} and {@code EmptyVehicleRelocator}.)</p>
 *
 * <p><b>WIRING INVARIANT 3 (Task 8 review).</b> {@link ModularOptimizer} and its
 * {@link DefaultDrtOptimizer} delegate share the SAME {@link ScheduleTimingUpdater} instance — the
 * one rebuilt around Task 5's decorator. Two instances would split belt 1 (the decorated end-time
 * calculator) from belt 2 ({@code ModularOptimizer.enforceIntendedDurations}), which is exactly the
 * silent-corruption class Tasks 5 and 8 exist to prevent. This is guaranteed structurally, not by
 * the binding scope: the provider resolves the updater ONCE into a local and passes that same
 * reference to both constructors.</p>
 *
 * <p><b>Coexistence with {@code ReturnToDepotRebalancingModule} (design §6).</b> Both the splicer
 * and the rebalancing side append to the trailing STAY of a schedule, and
 * {@code EmptyVehicleRelocator.relocateVehicleImpl} throws ("The current STAY task is not last") if
 * it ever lands on a tail the splicer already extended — it also casts the current task to
 * {@code DrtStayTask}, which a {@link ModularFreightStopTask} is NOT. Neither can happen: every
 * relocation candidate passes {@code DrtScheduleInquiry.isIdle}, which requires the current task to
 * BE the last task, and a spliced vehicle's current (truncated) STAY is no longer last. In the
 * other direction the dispatcher only selects {@code isIdle} vehicles and additionally filters on
 * {@link Modular#hasUnperformedFreightTask}. The ordering inside
 * {@link ModularOptimizer#notifyMobsimBeforeSimStep} completes the argument: the dispatcher ticks
 * BEFORE the delegate, so a vehicle committed in this simstep is already non-idle when the
 * delegate's rebalancing looks for relocatable vehicles.</p>
 */
public final class ModularDispatchModule extends AbstractDvrpModeModule {

    private final DrtConfigGroup drtCfg;
    private final List<ModularFreightTour> tours;
    /** Passenger-first dispatch gate (design D6): dispatch only while the idle SHARE strictly
     *  exceeds this; 1.0 is the never-dispatch control arm, 0.0 dispatches whenever any vehicle
     *  is idle. */
    private final double idleThreshold;

    public ModularDispatchModule(DrtConfigGroup drtCfg, List<ModularFreightTour> tours,
                                 double idleThreshold) {
        super(drtCfg.getMode());
        this.drtCfg = drtCfg;
        this.tours = List.copyOf(tours);
        this.idleThreshold = idleThreshold;
    }

    @Override
    public void install() {
        // Controller scope: freight KPIs. Not modal - the handler keys everything by tour id
        // itself. Eager singleton bound ONCE and referenced by both the event-handler and the
        // controler-listener binding, so the CSV written at shutdown is fed by the same instance
        // that accumulated the events (the SharedUseKpiHandler precedent).
        bind(ModularKpiHandler.class).asEagerSingleton();
        addEventHandlerBinding().to(ModularKpiHandler.class);
        addControlerListenerBinding().to(ModularKpiHandler.class);

        // ---- QSim half ---------------------------------------------------------------------
        // ScheduleTimingUpdater, VehicleEntry.EntryFactory and DrtOptimizer are QSim-scope keys
        // bound by DrtModeOptimizerQSimModule. Binding them at CONTROLLER scope would be a second,
        // conflicting binding (Guice BindingAlreadySet); installOverridingQSimModule is what lets
        // these override the native QSim bindings for the same keys.
        installOverridingQSimModule(new AbstractDvrpModeQSimModule(getMode()) {
            @Override
            protected void configureQSim() {
                // Belt 1 (Task 5). Rebuilt verbatim from DrtModeOptimizerQSimModule.java:187-190
                // with the Modular decorator wrapped around the native calculator.
                bindModal(ScheduleTimingUpdater.class).toProvider(modalProvider(getter ->
                        new ScheduleTimingUpdater(getter.get(MobsimTimer.class),
                                new ModularStayTaskEndTimeCalculator(
                                        new DrtStayTaskEndTimeCalculator(
                                                getter.getModal(StopTimeCalculator.class))),
                                getter.getModal(DriveTaskUpdater.class)))).asEagerSingleton();

                // D2 strict lockout (Task 8) around the native entry factory.
                bindModal(VehicleEntry.EntryFactory.class).toProvider(modalProvider(getter ->
                        new ModularEntryFactory(getter.getModal(VehicleDataEntryFactoryImpl.class))))
                        .asEagerSingleton();

                // The splicer. asEagerSingleton: WIRING INVARIANT 2 (own SpeedyALT, not
                // thread-safe). Modal Network: WIRING INVARIANT 1, asserted below.
                bindModal(ModularTourScheduler.class).toProvider(modalProvider(getter -> {
                    Network network = getter.getModal(Network.class);
                    verifyFleetLinksComeFromNetwork(getter.getModal(Fleet.class), network, getMode());
                    TravelTime travelTime = getter.getModal(TravelTime.class);
                    TravelDisutility disutility = getter.getModal(TravelDisutilityFactory.class)
                            .createTravelDisutility(travelTime);
                    return new ModularTourScheduler(network, travelTime, disutility,
                            getter.getModal(DrtTaskFactory.class),
                            getter.getModal(DvrpLoadType.class));
                })).asEagerSingleton();

                // The online gate (Task 7). Same modal Network as the splicer, so a tour link the
                // splicer can resolve is one the dispatcher's nearest-vehicle search can too.
                // EventsManager is QSim-visible and NOT modal (VERIFY-SOURCE: the native module
                // resolves it the same way for DefaultUnplannedRequestInserter,
                // DrtModeOptimizerQSimModule.java:114).
                bindModal(ModularTourDispatcher.class).toProvider(modalProvider(getter ->
                        new ModularTourDispatcher(getMode(), tours, idleThreshold,
                                getter.getModal(Fleet.class),
                                getter.getModal(DrtScheduleInquiry.class),
                                getter.getModal(ModularTourScheduler.class),
                                getter.getModal(Network.class),
                                getter.get(EventsManager.class)))).asEagerSingleton();

                // REBIND only - never addModalComponent (see class javadoc, binding rule).
                // DefaultDrtOptimizer's 10 constructor arguments and their order are verbatim from
                // DrtModeOptimizerQSimModule.java:92-99; MobsimTimer is the only non-modal one
                // besides drtCfg.
                bindModal(DrtOptimizer.class).toProvider(modalProvider(getter -> {
                    // WIRING INVARIANT 3: resolved ONCE, shared by decorator and delegate.
                    ScheduleTimingUpdater timingUpdater = getter.getModal(ScheduleTimingUpdater.class);
                    MobsimTimer timer = getter.get(MobsimTimer.class);
                    DrtOptimizer delegate = new DefaultDrtOptimizer(drtCfg,
                            getter.getModal(Fleet.class),
                            timer,
                            getter.getModal(DepotFinder.class),
                            getter.getModal(RebalancingStrategy.class),
                            getter.getModal(DrtScheduleInquiry.class),
                            timingUpdater,
                            getter.getModal(EmptyVehicleRelocator.class),
                            getter.getModal(UnplannedRequestInserter.class),
                            getter.getModal(DrtRequestInsertionRetryQueue.class));
                    return new ModularOptimizer(delegate,
                            getter.getModal(ModularTourDispatcher.class), timingUpdater, timer);
                })).asEagerSingleton();
            }
        });
    }

    /**
     * Enforces WIRING INVARIANT 1 at QSim startup: every fleet vehicle's start {@link Link} must be
     * the very OBJECT {@code network} holds under that id, not an equal-id twin. Unreachable under
     * DVRP's normal modal binding (both come from the same eager singleton), but the splicer cannot
     * check it and the failure it prevents is a half-spliced schedule mid-run — an
     * {@code IllegalStateException} thrown by {@code ScheduleImpl.addTask} after the trailing STAY
     * has already been truncated, with no rollback, for every tour. Failing here instead is O(fleet
     * size), happens once per iteration before any vehicle moves, and names the actual cause.
     *
     * <p>Deliberately reference ({@code !=}) and not id comparison: an id comparison would pass in
     * exactly the broken case this exists to catch.</p>
     */
    static void verifyFleetLinksComeFromNetwork(Fleet fleet, Network network, String mode) {
        for (DvrpVehicle vehicle : fleet.getVehicles().values()) {   // ImmutableMap: ordered
            Link startLink = vehicle.getStartLink();
            if (network.getLinks().get(startLink.getId()) != startLink) {
                throw new IllegalStateException("DRT_MODULAR wiring invariant violated on mode '"
                        + mode + "': the Network injected into ModularTourScheduler is NOT the"
                        + " instance the DVRP fleet's Link references come from (vehicle "
                        + vehicle.getId() + ", start link " + startLink.getId() + "). The splicer"
                        + " would truncate a vehicle's trailing STAY and then fail"
                        + " ScheduleImpl's reference-equality link-continuity check on the first"
                        + " freight stop leg, leaving a half-spliced schedule with no rollback."
                        + " Bind ModularTourScheduler with the modal Network key"
                        + " (getter.getModal(Network.class)).");
            }
        }
    }
}
