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
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.mobsim.framework.MobsimTimer;
import org.matsim.core.router.costcalculators.TravelDisutilityFactory;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;

import java.util.List;

/**
 * Guice composition for DRT_MODULAR (1d, U-Shift capsule swap): one DRT fleet that serves
 * passengers AND executes offline jsprit-planned freight excursions. Controller half: the KPI
 * handler, and - only when {@code budgetMode=selfref} - the {@link PassengerLoadProfile} (plan
 * 2026-09-04, Task 5). QSim half (via {@code installOverridingQSimModule}, the
 * {@code SharedUseModule}-proven mechanism for QSim-scope keys): three REBINDS of native keys plus
 * two new singletons.
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
 * <p><b>Install order — convention, and what actually depends on it (VERIFY-SOURCE, corrected).</b>
 * Add this LAST via {@code controler.addOverridingModule}, after
 * {@code DrtConfigComposer.installModules}. That is the {@code SharedUseModule} convention and the
 * right habit, but be precise about why, because the obvious reasoning is wrong for this module:
 * <ul>
 *   <li>For CONTROLLER-scope keys, each {@code addOverridingModule} nests
 *       {@code Modules.override(previous).with(this)}, so order does decide the winner. This module
 *       overrides NO controller-scope key — it only ADDS {@link ModularKpiHandler} plus two
 *       multibinder contributions, and multibindings merge regardless of order. Nothing here needs
 *       the ordering today; a future controller-scope override WOULD.</li>
 *   <li>For the QSim-scope keys this module rebinds, order is IRRELEVANT:
 *       {@code AbstractModule.installOverridingQSimModule} contributes to a separate multibinder
 *       (@Named {@code "overridesFromAbstractModule"}), and {@code QSimProvider.get()} composes the
 *       QSim injector as {@code overrideQSimModules(plainModules, …)} FIRST and then applies every
 *       overriding contribution on top. The native {@code DrtModeOptimizerQSimModule} arrives as a
 *       PLAIN module ({@code MultiModeDrtModule} → {@code installQSimModule(new
 *       DrtModeQSimModule(drtCfg))} → {@code install(optimizerQSimModule)}), so this module's
 *       {@link DrtOptimizer} rebinding wins whether it was added before or after the native DRT
 *       modules.</li>
 * </ul>
 * The residual QSim-scope hazard is therefore NOT install order but a SECOND overriding QSim module
 * claiming the same key: {@code overridesFromAbstractModule} is a {@code Set} whose contributions are
 * applied in unspecified iteration order, so two overrides of {@code modalKey(DrtOptimizer.class)}
 * would resolve non-deterministically. No such conflict exists today
 * ({@code ReturnToDepotRebalancingModule} overrides {@code RebalancingTargetCalculator};
 * {@code SharedUseModule} overrides {@code InsertionCostCalculator} and
 * {@code DrtRequestInsertionRetryQueue}), and {@link OptimizerRebindGuard} fails loudly if one ever
 * appears — see its javadoc for the failure it prevents and the one it cannot.</p>
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
 * <p><b>NOTE FOR THE NEXT MATSim UPGRADER — the one silent hole in this composition.</b> "Rebinding
 * alone suffices" holds only while the native module keeps registering the QSim component under
 * {@code modalKey(DrtOptimizer.class)}. If a future drt version registered a DIFFERENT key, this
 * module would still bind a perfectly good {@link ModularOptimizer} that nothing ever drives:
 * {@code notifyMobsimBeforeSimStep} would never be called, the dispatcher would never tick, the run
 * would complete GREEN, and every freight KPI would be zero with no error anywhere. Every OTHER
 * native key this module consumes fails loudly instead — an unbound key is a Guice error at startup,
 * and the hand-built {@link DefaultDrtOptimizer} argument list is compile-time checked — so this is
 * the single place where a version bump can go quietly wrong. {@link OptimizerRebindGuard} does NOT
 * catch it (the binding would still be this module's); what catches it is
 * {@code ModularEndToEndTest}, whose {@code tours_dispatched >= 1} and {@code MODULAR_FREIGHT_*}
 * task-stream assertions fail if nothing drives the optimizer. Re-check
 * {@code DrtModeOptimizerQSimModule}'s {@code addModalComponent} call on every MATSim upgrade, and
 * treat that e2e as the upgrade gate.</p>
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
    private final int maxConcurrentFreight;
    private final java.util.List<Modular.DispatchWindow> freightWindows;
    /** Task 4/5 (plan 2026-09-04): {@code OFF} builds NO {@link PassengerLoadProfile} at all. */
    private final Modular.BudgetMode budgetMode;
    /** {@code k} handed to {@link PassengerLoadProfile}; inert while {@link #budgetMode} is OFF. */
    private final int budgetSmoothing;
    /** Reserve as a SHARE of fleet size; inert while {@link #budgetMode} is OFF. */
    private final double budgetHeadroom;
    /**
     * Slack over which a pending tour's urgency ramps to the full reserve (plan 2026-09-05).
     * Inert while {@link #budgetMode} is OFF - the ramp only offsets the budget, and there is no
     * budget to offset - but the honest chain duration it is measured against applies always.
     */
    private final double budgetUrgencyLeadS;
    /** Task 1: plan-time accounting the module hands the KPI handler (see {@link #install}'s
     *  provider binding, below - {@link ModularKpiHandler} is no longer {@code @Inject}-
     *  constructed because Guice has no binding for this plain, caller-supplied record). */
    private final ModularPlanStats planStats;

    public ModularDispatchModule(DrtConfigGroup drtCfg, List<ModularFreightTour> tours,
                                 double idleThreshold, ModularPlanStats planStats) {
        this(drtCfg, tours, idleThreshold, Modular.DEFAULT_MAX_CONCURRENT_FREIGHT,
                java.util.List.of(), planStats);
    }

    /** Budget OFF: the pre-2026-09-04 composition, no {@link PassengerLoadProfile} anywhere. */
    public ModularDispatchModule(DrtConfigGroup drtCfg, java.util.List<ModularFreightTour> tours,
                                 double idleThreshold, int maxConcurrentFreight,
                                 java.util.List<Modular.DispatchWindow> freightWindows,
                                 ModularPlanStats planStats) {
        this(drtCfg, tours, idleThreshold, maxConcurrentFreight, freightWindows,
                Modular.DEFAULT_BUDGET_MODE, Modular.DEFAULT_BUDGET_SMOOTHING,
                Modular.DEFAULT_BUDGET_HEADROOM, planStats);
    }

    /**
     * Fullest form: adds the self-referential capacity budget (plan 2026-09-04, Task 5).
     *
     * @param budgetMode      {@code OFF} (default) binds no {@link PassengerLoadProfile} and hands
     *                        the dispatcher {@code null}, so an OFF run does not even pay for the
     *                        per-tick passenger-busy scan; {@code SELFREF} binds exactly one
     *                        controller-scoped profile and injects THAT instance
     * @param budgetSmoothing {@code k} for the profile; inert while {@code budgetMode} is OFF
     * @param budgetHeadroom  reserve as a SHARE of fleet size; inert while OFF
     */
    public ModularDispatchModule(DrtConfigGroup drtCfg, java.util.List<ModularFreightTour> tours,
                                 double idleThreshold, int maxConcurrentFreight,
                                 java.util.List<Modular.DispatchWindow> freightWindows,
                                 Modular.BudgetMode budgetMode, int budgetSmoothing,
                                 double budgetHeadroom, ModularPlanStats planStats) {
        this(drtCfg, tours, idleThreshold, maxConcurrentFreight, freightWindows, budgetMode,
                budgetSmoothing, budgetHeadroom, Modular.DEFAULT_BUDGET_URGENCY_LEAD_S, planStats);
    }

    /**
     * Fullest form: adds the urgency ramp's lead time (plan 2026-09-05, Fix 2).
     *
     * @param budgetUrgencyLeadS slack over which urgency ramps to the full reserve, in seconds
     */
    public ModularDispatchModule(DrtConfigGroup drtCfg, java.util.List<ModularFreightTour> tours,
                                 double idleThreshold, int maxConcurrentFreight,
                                 java.util.List<Modular.DispatchWindow> freightWindows,
                                 Modular.BudgetMode budgetMode, int budgetSmoothing,
                                 double budgetHeadroom, double budgetUrgencyLeadS,
                                 ModularPlanStats planStats) {
        super(drtCfg.getMode());
        this.budgetUrgencyLeadS = budgetUrgencyLeadS;
        this.drtCfg = drtCfg;
        this.tours = List.copyOf(tours);
        this.idleThreshold = idleThreshold;
        this.maxConcurrentFreight = maxConcurrentFreight;
        this.freightWindows = java.util.List.copyOf(freightWindows);
        this.budgetMode = budgetMode == null ? Modular.DEFAULT_BUDGET_MODE : budgetMode;
        this.budgetSmoothing = budgetSmoothing;
        this.budgetHeadroom = budgetHeadroom;
        this.planStats = planStats;
    }

    @Override
    public void install() {
        // Controller scope: freight KPIs. Not modal - the handler keys everything by tour id
        // itself. Eager singleton bound ONCE and referenced by both the event-handler and the
        // controler-listener binding, so the CSV written at shutdown is fed by the same instance
        // that accumulated the events (the SharedUseKpiHandler precedent).
        //
        // MULTI-MODE CONSEQUENCE (single-DRT-mode assumption, made explicit): because this binding is
        // NOT modal and ModularKpiHandler.FILE_NAME is a fixed filename, installing this module for
        // two DRT modes would silently collapse both modes into ONE accumulator writing ONE
        // <runId>.modular_tour_stats.csv - the second bind() simply wins under addOverridingModule
        // nesting, with no Guice error. The numbers would still be self-consistent (the handler keys
        // by tour id, and Task 4 tour ids are carrier-scoped), but they would be a silent SUM across
        // modes, not per-mode KPIs. 1d is single-mode (drt) by design; a multi-mode variant must make
        // this binding modal and put the mode in the filename.
        //
        // Task 1: ModularKpiHandler's ctor now also takes ModularPlanStats - a plain,
        // caller-supplied record Guice has no binding for, so it can no longer be @Inject-
        // constructed. bind(...).toProvider(...) resolves OutputDirectoryHierarchy through Guice
        // (via a Provider, since it is not available yet at install() time) and closes over this
        // module's OWN planStats field - the module "holds the stats", per design.
        com.google.inject.Provider<OutputDirectoryHierarchy> controlerIOProvider =
                binder().getProvider(OutputDirectoryHierarchy.class);
        // Task 6 (plan 2026-09-04): the KPI handler also publishes the budget's two dispatch
        // counters, so it needs the counter object - and needs to be able to tell "the budget was
        // OFF" from "the budget was ON and never bound", which are completely different runs. The
        // discriminator is the object's EXISTENCE: under budgetMode=off nothing is bound, the
        // handler receives null, and it writes budget_active;0 with no counter rows at all.
        // binder().getProvider(...) is legal before the key is bound further down (Guice resolves
        // providers lazily) - but it is only DEREFERENCED when budgetOn, so an off run never asks
        // Guice for a binding that does not exist.
        final boolean budgetOn = budgetMode == Modular.BudgetMode.SELFREF;
        final com.google.inject.Provider<ModularBudgetStats> budgetStatsProvider =
                budgetOn ? binder().getProvider(ModularBudgetStats.class) : null;
        bind(ModularKpiHandler.class)
                .toProvider(() -> new ModularKpiHandler(controlerIOProvider.get(), planStats,
                        budgetStatsProvider == null ? null : budgetStatsProvider.get()))
                .asEagerSingleton();
        addEventHandlerBinding().to(ModularKpiHandler.class);
        addControlerListenerBinding().to(ModularKpiHandler.class);

        // Controller scope, plan 2026-09-04 Task 5: the self-referential passenger-load profile.
        // Bound the SAME way ModularKpiHandler is - one eager singleton, referenced by both the
        // controler-listener binding and (below) the QSim-scoped dispatcher provider - because it
        // must OUTLIVE the dispatcher: ModularTourDispatcher is QSim-scoped and resets every
        // iteration by construction (the 1c dd34b23 lesson), while the whole point of the profile
        // is to carry iteration N-1's measurement into iteration N.
        //
        // WHY THIS DIRECTION OF INJECTION IS THE LEGAL ONE (VERIFY-SOURCE:
        // QSimProvider.get() -> `injector.createChildInjector(module)`): the QSim injector is a
        // CHILD of the controler injector, so a QSim-scoped provider can resolve a controller-scope
        // binding, and getter.get(PassengerLoadProfile.class) below returns THIS singleton rather
        // than a second copy. The reverse would not work. A second instance would be a silent
        // disaster rather than an error: the QSim copy would be reconstructed every iteration,
        // never receive notifyIterationEnds, never bootstrap, and budget() would return
        // POSITIVE_INFINITY forever - a run that looks like it used the feature and did not.
        // ModularBudgetWiringTest asserts the identity inside a real MATSim run, not by reasoning.
        //
        // budgetMode=off binds NOTHING here: no instance is created and none is registered, so an
        // OFF run cannot accidentally pay for the dispatcher's per-tick passenger-busy scan (the
        // dispatcher's `profile == null` sentinel is what switches that scan off). PassengerLoadProfile
        // has no injectable constructor, so if this binding is absent, an accidental
        // getInstance(PassengerLoadProfile.class) anywhere fails loudly instead of JIT-creating one.
        if (budgetOn) {
            bind(PassengerLoadProfile.class)
                    .toProvider(() -> new PassengerLoadProfile(budgetSmoothing))
                    .asEagerSingleton();
            addControlerListenerBinding().to(PassengerLoadProfile.class);
            // Task 6: the counter object, bound EXACTLY like the profile - one eager singleton,
            // referenced by the controler-listener binding (which zeroes it at every iteration
            // start, so the counters describe the same single iteration every other row in
            // modular_tour_stats.csv describes) and by the QSim-scoped dispatcher provider below
            // (which increments it). Two instances here would be the same silent disaster the
            // profile's comment describes: the KPI handler would publish an all-zero pair from an
            // object the dispatcher never touched, i.e. a run that looks like a clean pass of a
            // stricter gate. ModularBudgetStats has no injectable constructor either, so a missing
            // binding fails loudly instead of JIT-creating a second copy.
            bind(ModularBudgetStats.class)
                    .toProvider(ModularBudgetStats::new)
                    .asEagerSingleton();
            addControlerListenerBinding().to(ModularBudgetStats.class);
        }

        // The learned chain duration is bound UNCONDITIONALLY, outside the budgetOn branch (plan
        // 2026-09-05, Fix 1). It is not a feature of the budget arm: the expiry sweep needs an
        // honest answer to "how long does this tour hold a vehicle" in every arm, and gating it
        // on budgetMode would mean the budget arm differs from every existing arm in TWO ways and
        // could never be compared one-factor. Same shape as the profile above - one eager
        // singleton, referenced by the listener binding that rolls it and by the QSim-scoped
        // dispatcher that feeds and reads it - for the same reason: two instances would mean the
        // dispatcher teaches one object and asks another, which never bootstraps and silently
        // leaves every tour on the bootstrap factor forever.
        bind(FreightChainProfile.class)
                .toProvider(() -> new FreightChainProfile(budgetSmoothing))
                .asEagerSingleton();
        addControlerListenerBinding().to(FreightChainProfile.class);

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
                //
                // Task 5: the budget arguments. getter.get(...) - NOT getModal - because the
                // profile is a plain controller-scope key, resolved through the QSim injector's
                // parent (see the binding above). When the budget is OFF the profile is neither
                // looked up nor created and the dispatcher receives exactly the (null, 0.0) pair
                // its own budget-off overload would have passed, so the OFF path is the old path.
                bindModal(ModularTourDispatcher.class).toProvider(modalProvider(getter ->
                        new ModularTourDispatcher(getMode(), tours, idleThreshold,
                                maxConcurrentFreight, freightWindows,
                                budgetOn ? getter.get(PassengerLoadProfile.class) : null,
                                budgetOn ? budgetHeadroom : 0.0,
                                budgetOn ? getter.get(ModularBudgetStats.class) : null,
                                // Unconditional, unlike the three above - see the binding.
                                getter.get(FreightChainProfile.class),
                                budgetUrgencyLeadS,
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

                // Asserts the rebind above actually won the key (see OptimizerRebindGuard).
                bindModal(OptimizerRebindGuard.class).toProvider(modalProvider(getter ->
                        new OptimizerRebindGuard(getter.getModal(DrtOptimizer.class), getMode())))
                        .asEagerSingleton();
            }
        });
    }

    /**
     * Fails the run at QSim startup unless the {@link DrtOptimizer} actually in effect for this mode
     * is this module's {@link ModularOptimizer} (Task 10 review, Item 2). Symmetric in spirit with
     * {@link #verifyFleetLinksComeFromNetwork}: both convert a silent-wrong-result failure into a
     * loud startup one.
     *
     * <p><b>What it catches.</b> Any configuration in which something else wins
     * {@code modalKey(DrtOptimizer.class)} — realistically a SECOND module calling
     * {@code installOverridingQSimModule} with its own binding for that key, which
     * {@code QSimProvider} resolves in unspecified {@code Set} iteration order (class javadoc,
     * "Install order"). Also catches this module simply not being installed on some future runner
     * path while the rest of the modular wiring is. Without the guard the symptom is a GREEN run with
     * an all-zero KPI CSV: the optimizer is never decorated, so {@code dispatch()} never ticks, no
     * tour is ever spliced, and nothing anywhere reports an error.</p>
     *
     * <p><b>What it deliberately does NOT catch</b>, so nobody over-reads it: the case where this
     * module's binding wins but no QSim component drives it (a future MATSim registering the
     * optimizer component under a different key). The binding would be correct and the guard would
     * pass — see the upgrader note in the class javadoc; {@code ModularEndToEndTest} is what covers
     * that. Nor does it check install ORDER, which for these QSim keys does not decide the winner.</p>
     */
    static final class OptimizerRebindGuard {
        OptimizerRebindGuard(DrtOptimizer inEffect, String mode) {
            if (!(inEffect instanceof ModularOptimizer)) {
                throw new IllegalStateException("DRT_MODULAR composition is inert on mode '" + mode
                        + "': the DrtOptimizer in effect is " + inEffect.getClass().getName()
                        + ", not ModularOptimizer, so the freight dispatcher would never tick and"
                        + " every freight KPI would be zero without any error. Something else won"
                        + " the modal DrtOptimizer key - most likely a second module calling"
                        + " installOverridingQSimModule for it (resolved in unspecified order), or"
                        + " ModularDispatchModule not being installed at all. Install"
                        + " ModularDispatchModule via controler.addOverridingModule after"
                        + " DrtConfigComposer.installModules, and ensure no other overriding QSim"
                        + " module rebinds DrtOptimizer for this mode.");
            }
        }
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
