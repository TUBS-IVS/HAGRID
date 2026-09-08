package hagrid.integrated.drt;

import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.TypeLiteral;
import hagrid.integrated.modular.FreightChainProfile;
import hagrid.integrated.modular.Modular;
import hagrid.integrated.modular.ModularDispatchModule;
import hagrid.integrated.modular.ModularTourDispatcher;
import hagrid.integrated.modular.PassengerLoadProfile;
import hagrid.simulation.DrtScenarioBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.matsim.api.core.v01.Scenario;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.contrib.dvrp.run.AbstractDvrpModeQSimModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.listener.ControlerListener;
import org.matsim.testcases.MatsimTestUtils;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves - inside a real MATSim run, not by reasoning about Guice - that
 * {@link ModularDispatchModule} gives the QSim-scoped {@link ModularTourDispatcher} the very SAME
 * {@link PassengerLoadProfile} object that is registered as a controler listener (plan 2026-09-04,
 * Task 5), and that {@code budgetMode=off} creates none at all.
 *
 * <p><b>Why this needs a run and cannot be an element/binding inspection.</b> The profile is bound
 * at CONTROLLER scope while the dispatcher is bound at QSIM scope; the QSim injector is a child of
 * the controler injector (VERIFY-SOURCE: {@code QSimProvider.get()} ->
 * {@code injector.createChildInjector(module)}), so {@code getter.get(PassengerLoadProfile.class)}
 * inside the dispatcher's provider is EXPECTED to resolve the parent's singleton. "Expected" is
 * the problem: if it ever produced a second instance instead, nothing would fail. The QSim copy
 * would be rebuilt every iteration, never receive {@code notifyIterationEnds}, never bootstrap,
 * and {@code budget()} would return {@code POSITIVE_INFINITY} in every bin forever - an unbounded
 * budget, i.e. a 16 h run that reports {@code budgetMode=selfref} in its own log line and behaves
 * exactly like the old theta gate. {@link ModularDispatchModule}'s Guice elements are checked
 * statically by {@code ModularDispatchModuleTest}; this is the half of the property that only a
 * live injector hierarchy can answer.
 *
 * <p><b>Two independent assertions, deliberately.</b> Reference identity
 * ({@code isSameAs}) says the objects are one. {@link PassengerLoadProfile#completedIterations()}
 * says so from the other direction and without reflection on identity at all: the dispatcher's
 * profile has observed the iteration-end callback, which ONLY the registered listener receives. A
 * duplicated instance would still be a {@code PassengerLoadProfile}, still be non-null, and still
 * report {@code completedIterations() == 0} - so the second assertion fails on its own even if the
 * first were somehow satisfied by a proxy.
 *
 * <p>Fixture: {@link ModularE2eStaging} (shared with {@link ModularEndToEndTest} and
 * {@link ModularControlArmTest}), the STRIPPED composition ({@code installModules(Controler)}, no
 * rebalancing) and {@code lastIteration = 0} - this test is about wiring, not about mobsim
 * behaviour, so one QSim is all it needs.</p>
 */
@DisplayName("DRT_MODULAR budget wiring: one shared PassengerLoadProfile across controler/QSim")
class ModularBudgetWiringTest {

    @RegisterExtension
    public MatsimTestUtils utils = new MatsimTestUtils();

    /** SHORT literal run id - keeps the shutdown geopackage path under the Windows limit. */
    private static final String RUN_ID = "MODULAR_BUD";
    /** theta = 0: the gate opens whenever any vehicle is idle, so the dispatcher really ticks. */
    private static final double IDLE_THRESHOLD = 0.0;
    private static final int SMOOTHING = 5;
    private static final double HEADROOM = Modular.DEFAULT_BUDGET_HEADROOM;

    /**
     * QSim-scoped eager singleton whose only job is to capture what the QSim injector handed the
     * dispatcher. Bound under its OWN key via {@code addOverridingQSimModule}, so it adds no second
     * override of any key {@link ModularDispatchModule} rebinds (the hazard
     * {@code OptimizerRebindGuard} exists for).
     */
    private static final class DispatcherProbe {
        private final ModularTourDispatcher dispatcher;

        DispatcherProbe(ModularTourDispatcher dispatcher) {
            this.dispatcher = dispatcher;
        }
    }

    @Test
    @DisplayName("selfref: the dispatcher's profile IS the registered controler listener, and it ticked")
    void selfrefSharesExactlyOneProfileAcrossTheInjectorBoundary() throws Exception {
        AtomicReference<DispatcherProbe> probe = new AtomicReference<>();
        Controler controler = buildRun(Modular.BudgetMode.SELFREF, probe);
        controler.run();

        ModularTourDispatcher dispatcher = probe.get().dispatcher;
        PassengerLoadProfile fromDispatcher = profileFieldOf(dispatcher);
        assertThat(fromDispatcher)
                .as("budgetMode=selfref must hand the dispatcher a profile, not null")
                .isNotNull();

        Injector injector = controler.getInjector();
        PassengerLoadProfile fromControler = injector.getInstance(PassengerLoadProfile.class);

        // The controler-listener multibinding is the channel that receives notifyIterationEnds.
        List<PassengerLoadProfile> listeners = injector
                .getInstance(Key.get(new TypeLiteral<Set<ControlerListener>>() {}))
                .stream()
                .filter(PassengerLoadProfile.class::isInstance)
                .map(PassengerLoadProfile.class::cast)
                .toList();
        assertThat(listeners)
                .as("exactly one PassengerLoadProfile may be registered as a controler listener")
                .hasSize(1);

        // (1) reference identity across the controler/QSim boundary
        assertThat(fromDispatcher)
                .as("the QSim-scoped dispatcher must hold the SAME object the controler injector"
                        + " bound and registered as a listener - a second instance would reset"
                        + " every iteration, never bootstrap, and leave the budget unbounded"
                        + " forever while the run reported budgetMode=selfref")
                .isSameAs(fromControler)
                .isSameAs(listeners.get(0));

        // (2) the same property read from the other end: only the REGISTERED listener is notified,
        // so a duplicated instance in the dispatcher would still sit at 0 completed iterations.
        assertThat(fromDispatcher.completedIterations())
                .as("the dispatcher's profile must have received notifyIterationEnds for the one"
                        + " iteration that ran - this is what makes it the registered listener")
                .isEqualTo(1);
        assertThat(fromDispatcher.isBootstrapped())
                .as("one completed iteration is enough to bootstrap the profile")
                .isTrue();
    }

    @Test
    @DisplayName("off: no PassengerLoadProfile is created, bound or registered anywhere")
    void offCreatesNoProfileAtAll() throws Exception {
        AtomicReference<DispatcherProbe> probe = new AtomicReference<>();
        Controler controler = buildRun(Modular.BudgetMode.OFF, probe);
        controler.run();

        assertThat(profileFieldOf(probe.get().dispatcher))
                .as("budgetMode=off must hand the dispatcher the null sentinel - that null is what"
                        + " switches off the per-tick passenger-busy scan, so a 'harmless' profile"
                        + " passed here would make every off run pay for a feature it does not use")
                .isNull();

        Injector injector = controler.getInjector();
        assertThat(injector.getExistingBinding(Key.get(PassengerLoadProfile.class)))
                .as("off must not even declare the binding")
                .isNull();
        assertThat(injector.getInstance(Key.get(new TypeLiteral<Set<ControlerListener>>() {})))
                .as("off must register no profile as a controler listener")
                .noneMatch(PassengerLoadProfile.class::isInstance);
    }

    /**
     * The learned chain duration is bound UNCONDITIONALLY (plan 2026-09-05, Fix 1), which is the
     * one place its wiring differs from {@link PassengerLoadProfile}'s. It is a correctness fix,
     * not a feature of the budget arm: gating it on {@code budgetMode} would mean the budget arm
     * differs from every existing arm in TWO ways and could never be compared one-factor.
     *
     * <p>The failure this guards against is the same silent one the profile has: a second instance
     * would be taught by the dispatcher and asked by nobody - or asked and never taught - so
     * {@code estimate()} would return NaN forever and every tour would sit on
     * {@code CHAIN_BOOTSTRAP_FACTOR} for the whole run. Nothing would throw, no counter would move,
     * and the CSV would look exactly like a healthy run.
     */
    @Test
    @DisplayName("off: the chain profile IS still bound, shared and ticking (it is not a budget feature)")
    void chainProfileIsBoundEvenWhenTheBudgetIsOff() throws Exception {
        AtomicReference<DispatcherProbe> probe = new AtomicReference<>();
        Controler controler = buildRun(Modular.BudgetMode.OFF, probe);
        controler.run();

        FreightChainProfile fromDispatcher =
                fieldOf(probe.get().dispatcher, "chainProfile", FreightChainProfile.class);
        assertThat(fromDispatcher)
                .as("budgetMode=off must STILL hand the dispatcher a chain profile")
                .isNotNull();

        Injector injector = controler.getInjector();
        assertThat(fromDispatcher)
                .as("one object across the controler/QSim boundary, or the dispatcher teaches one"
                        + " instance and asks another")
                .isSameAs(injector.getInstance(FreightChainProfile.class));

        List<FreightChainProfile> listeners = injector
                .getInstance(Key.get(new TypeLiteral<Set<ControlerListener>>() {}))
                .stream()
                .filter(FreightChainProfile.class::isInstance)
                .map(FreightChainProfile.class::cast)
                .toList();
        assertThat(listeners)
                .as("exactly one FreightChainProfile may be registered as a controler listener")
                .hasSize(1);
        assertThat(fromDispatcher).isSameAs(listeners.get(0));

        assertThat(fromDispatcher.completedIterations())
                .as("read from the other end: only the REGISTERED listener is notified, so a"
                        + " duplicated instance would still sit at 0")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("selfref: the chain profile is shared too, and has learned from the iteration")
    void chainProfileIsSharedAndLearnsUnderSelfref() throws Exception {
        AtomicReference<DispatcherProbe> probe = new AtomicReference<>();
        Controler controler = buildRun(Modular.BudgetMode.SELFREF, probe);
        controler.run();

        FreightChainProfile fromDispatcher =
                fieldOf(probe.get().dispatcher, "chainProfile", FreightChainProfile.class);
        assertThat(fromDispatcher)
                .isSameAs(controler.getInjector().getInstance(FreightChainProfile.class));
        assertThat(fromDispatcher.isBootstrapped())
                .as("one completed iteration is enough to bootstrap")
                .isTrue();
    }

    // ------------------------------------------------------------------ fixture

    /**
     * Stages the shared fixture and builds a one-iteration DRT_MODULAR controler with the given
     * budget mode, plus the {@link DispatcherProbe}. Not run yet - the caller runs it, so a failure
     * inside {@code run()} is attributed to the test that owns it.
     */
    private Controler buildRun(Modular.BudgetMode mode, AtomicReference<DispatcherProbe> probe)
            throws Exception {
        Path dir = Path.of(utils.getOutputDirectory()).toAbsolutePath();
        ModularE2eStaging staging = ModularE2eStaging.stage(dir);
        Scenario scenario = DrtScenarioBuilder.build(staging.cfgUrl.toString(),
                staging.drtNetFile.toString(), staging.clippedPlans.toString(),
                staging.shpFile.toString(), staging.fleetFile.toString(),
                dir.resolve("matsim").toString(), RUN_ID, /*lastIteration*/ 0);

        DrtConfigGroup drtCfg = MultiModeDrtConfigGroup.get(scenario.getConfig())
                .getModalElements().iterator().next();
        Controler controler = new Controler(scenario);
        DrtConfigComposer.installModules(controler);
        controler.addOverridingModule(new ModularDispatchModule(drtCfg, staging.tours,
                IDLE_THRESHOLD, Modular.DEFAULT_MAX_CONCURRENT_FREIGHT, List.of(),
                mode, SMOOTHING, HEADROOM, staging.stats));

        String drtMode = drtCfg.getMode();
        controler.addOverridingQSimModule(new AbstractDvrpModeQSimModule(drtMode) {
            @Override
            protected void configureQSim() {
                bindModal(DispatcherProbe.class).toProvider(modalProvider(getter -> {
                    DispatcherProbe p =
                            new DispatcherProbe(getter.getModal(ModularTourDispatcher.class));
                    probe.set(p);
                    return p;
                })).asEagerSingleton();
            }
        });
        return controler;
    }

    /**
     * Reads any private field of the dispatcher by name. Same reflective compromise as
     * {@link #profileFieldOf}: the dispatcher exposes no accessor, and adding one purely so a test
     * could see the wiring would be the test dictating production API.
     */
    private static <T> T fieldOf(ModularTourDispatcher dispatcher, String name, Class<T> type)
            throws Exception {
        Field f = ModularTourDispatcher.class.getDeclaredField(name);
        f.setAccessible(true);
        return type.cast(f.get(dispatcher));
    }

    /**
     * Reads {@code ModularTourDispatcher.profile}. Reflection deliberately: the dispatcher exposes
     * no accessor for it, and Task 5 must not change that class (its 45 green tests are the
     * baseline this task is measured against). Adding a getter purely so a test could see the
     * field would also weaken it - {@code null} is a load-bearing sentinel there, not state a
     * caller should read.
     */
    private static PassengerLoadProfile profileFieldOf(ModularTourDispatcher dispatcher)
            throws Exception {
        Field f = ModularTourDispatcher.class.getDeclaredField("profile");
        f.setAccessible(true);
        return (PassengerLoadProfile) f.get(dispatcher);
    }
}
