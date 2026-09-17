package hagrid.integrated.modular;

import com.google.inject.Binding;
import com.google.inject.Guice;
import com.google.inject.Key;
import com.google.inject.Stage;
import com.google.inject.spi.Element;
import com.google.inject.spi.Elements;
import com.google.inject.spi.LinkedKeyBinding;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.matsim.contrib.drt.optimizer.DrtOptimizer;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;
import org.matsim.contrib.dvrp.optimizer.Request;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.listener.ControlerListener;
import org.matsim.core.mobsim.framework.events.MobsimBeforeSimStepEvent;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Module-level tests for {@link ModularDispatchModule}: the package-private
 * {@link ModularDispatchModule.OptimizerRebindGuard} (review J-F9) and the Guice ELEMENTS the
 * module declares for the self-referential capacity budget (plan 2026-09-04, Task 5).
 *
 * <p><b>Guard tests.</b> The guard itself already exists (Task 10 review, Item 2) - those tests PIN
 * its existing behaviour rather than drive new production code, so no red/green TDD cycle applies in
 * the usual sense. Discrimination reasoning (task-6-report.md has the full note): the guard's
 * single check is {@code !(inEffect instanceof ModularOptimizer)} - if that condition were
 * inverted to {@code (inEffect instanceof ModularOptimizer)}, {@link #firesWhenOptimizerIsNotModular()}
 * would wrongly pass through without throwing (FAIL) and {@link #doesNotFireWhenOptimizerIsModular()}
 * would wrongly throw (FAIL) - each test therefore fails if the guard's polarity is flipped, which
 * is the property that matters here.
 *
 * <p><b>Budget-wiring tests.</b> {@link Elements#getElements} records what a module BINDS without
 * building an injector, which is what makes "{@code budgetMode=off} creates no profile at all"
 * checkable in milliseconds rather than inside a mobsim. Two properties are asserted:
 * exactly ONE {@link PassengerLoadProfile} binding exists under {@code selfref} (never zero, never
 * two), and the controler-listener multibinding is a LINK to that same key rather than a separate
 * instance - the static half of the two-instance disaster. What element inspection CANNOT see is
 * whether the QSim-scoped dispatcher really resolves that same object across the controler/QSim
 * injector boundary; that is a runtime property and is asserted in a real MATSim run by
 * {@code hagrid.integrated.drt.ModularBudgetWiringTest}. Neither test replaces the other.</p>
 */
@DisplayName("ModularDispatchModule")
class ModularDispatchModuleTest {

    private static final ModularPlanStats NO_PLAN_STATS =
            new ModularPlanStats(0L, 0L, 0L, 0, Map.of(), Map.of());

    /**
     * Records the Guice elements {@link ModularDispatchModule#install()} declares for the given
     * budget mode, WITHOUT creating an injector (so no MATSim scenario, controler or mobsim is
     * needed). {@code bootstrapInjector} is injected first because
     * {@code AbstractModule.configure} resolves the {@link Config} through it.
     */
    private static List<Element> elementsFor(Modular.BudgetMode mode, int smoothing) {
        Config config = ConfigUtils.createConfig();
        DrtConfigGroup drtCfg = new DrtConfigGroup();
        ModularDispatchModule module = new ModularDispatchModule(drtCfg, List.of(),
                /*idleThreshold*/ 0.15, Modular.DEFAULT_MAX_CONCURRENT_FREIGHT, List.of(),
                mode, smoothing, Modular.DEFAULT_BUDGET_HEADROOM, NO_PLAN_STATS);
        Guice.createInjector(binder -> binder.bind(Config.class).toInstance(config))
                .injectMembers(module);
        return Elements.getElements(Stage.TOOL, module);
    }

    private static long profileBindings(List<Element> elements) {
        return elements.stream()
                .filter(e -> e instanceof Binding)
                .map(e -> (Binding<?>) e)
                .filter(b -> b.getKey().equals(Key.get(PassengerLoadProfile.class)))
                .count();
    }

    /** Controler-listener multibinder entries that LINK to the PassengerLoadProfile key. */
    private static long profileListenerLinks(List<Element> elements) {
        return elements.stream()
                .filter(e -> e instanceof LinkedKeyBinding)
                .map(e -> (LinkedKeyBinding<?>) e)
                .filter(b -> b.getKey().getTypeLiteral().getRawType() == ControlerListener.class)
                .filter(b -> b.getLinkedKey().equals(Key.get(PassengerLoadProfile.class)))
                .count();
    }

    private static long budgetStatsBindings(List<Element> elements) {
        return elements.stream()
                .filter(e -> e instanceof Binding)
                .map(e -> (Binding<?>) e)
                .filter(b -> b.getKey().equals(Key.get(ModularBudgetStats.class)))
                .count();
    }

    /** Controler-listener multibinder entries that LINK to the ModularBudgetStats key. */
    private static long budgetStatsListenerLinks(List<Element> elements) {
        return elements.stream()
                .filter(e -> e instanceof LinkedKeyBinding)
                .map(e -> (LinkedKeyBinding<?>) e)
                .filter(b -> b.getKey().getTypeLiteral().getRawType() == ControlerListener.class)
                .filter(b -> b.getLinkedKey().equals(Key.get(ModularBudgetStats.class)))
                .count();
    }

    @Test
    @DisplayName("budgetMode=off declares NO PassengerLoadProfile binding and no listener for it")
    void budgetOffBindsNoProfileAtAll() {
        List<Element> elements = elementsFor(Modular.BudgetMode.OFF, 5);
        assertThat(profileBindings(elements))
                .as("an off run must not even be able to construct a profile - no instance, no"
                        + " listener registration, and therefore no per-tick passenger-busy scan")
                .isZero();
        assertThat(profileListenerLinks(elements)).isZero();
        // Positive control: the module really was recorded, so 'zero' above is not vacuous.
        assertThat(profileBindings(elementsFor(Modular.BudgetMode.SELFREF, 5))).isOne();
    }

    @Test
    @DisplayName("budgetMode=selfref declares exactly ONE profile binding, and the listener LINKS to it")
    void budgetSelfrefBindsOneProfileAndLinksTheListenerToIt() {
        List<Element> elements = elementsFor(Modular.BudgetMode.SELFREF, 5);
        assertThat(profileBindings(elements))
                .as("exactly one binding: two would give the controler listener and the dispatcher"
                        + " different objects, and the dispatcher's copy would never bootstrap")
                .isOne();
        assertThat(profileListenerLinks(elements))
                .as("the ControlerListener multibinding must LINK to the profile key, not carry a"
                        + " second instance of its own")
                .isOne();
    }

    /**
     * Task 6. The counter object has the same two-instance hazard the profile has, with a worse
     * symptom: a second copy would leave {@link ModularKpiHandler} publishing an all-zero
     * blocked/override pair from an object the dispatcher never touched, i.e. a run that reads as
     * a clean pass of a stricter gate while the budget was in fact blocking constantly. The
     * listener link matters too - it is what zeroes the counters at each iteration start, so
     * without it the published numbers would be a whole-run total sitting in a table of
     * last-iteration numbers.
     */
    @Test
    @DisplayName("budgetMode=selfref declares exactly ONE ModularBudgetStats, listener LINKED to it")
    void budgetSelfrefBindsOneStatsObjectAndLinksTheListenerToIt() {
        List<Element> elements = elementsFor(Modular.BudgetMode.SELFREF, 5);
        assertThat(budgetStatsBindings(elements))
                .as("exactly one binding: two would give the KPI handler and the dispatcher"
                        + " different counters, and the published pair would be the untouched one")
                .isOne();
        assertThat(budgetStatsListenerLinks(elements))
                .as("the ControlerListener multibinding must LINK to the stats key - that link is"
                        + " what resets the counters per iteration")
                .isOne();
    }

    @Test
    @DisplayName("budgetMode=off declares NO ModularBudgetStats binding and no listener for it")
    void budgetOffBindsNoStatsObjectAtAll() {
        List<Element> elements = elementsFor(Modular.BudgetMode.OFF, 5);
        assertThat(budgetStatsBindings(elements))
                .as("absence is the published off-flag: the KPI handler receives null and writes"
                        + " budget_active;0 with no counter rows")
                .isZero();
        assertThat(budgetStatsListenerLinks(elements)).isZero();
        // Positive control: the module really was recorded, so 'zero' above is not vacuous.
        assertThat(budgetStatsBindings(elementsFor(Modular.BudgetMode.SELFREF, 5))).isOne();
    }

    @Test
    @DisplayName("the budget-off constructor overloads really default to off")
    void shorterModuleConstructorsDefaultToOff() {
        DrtConfigGroup drtCfg = new DrtConfigGroup();
        Config config = ConfigUtils.createConfig();
        for (ModularDispatchModule module : List.of(
                new ModularDispatchModule(drtCfg, List.of(), 0.15, NO_PLAN_STATS),
                new ModularDispatchModule(drtCfg, List.of(), 0.15,
                        Modular.DEFAULT_MAX_CONCURRENT_FREIGHT, List.of(), NO_PLAN_STATS))) {
            Guice.createInjector(binder -> binder.bind(Config.class).toInstance(config))
                    .injectMembers(module);
            assertThat(profileBindings(Elements.getElements(Stage.TOOL, module)))
                    .as("pre-2026-09-04 constructor overloads must stay budget-free")
                    .isZero();
        }
    }

    @Test
    @DisplayName("fires when the in-effect DrtOptimizer is NOT ModularOptimizer (review J-F9)")
    void firesWhenOptimizerIsNotModular() {
        DrtOptimizer notModular = new DrtOptimizer() {
            @Override
            public void requestSubmitted(Request request) {
            }

            @Override
            public void nextTask(DvrpVehicle vehicle) {
            }

            @Override
            public void notifyMobsimBeforeSimStep(@SuppressWarnings("rawtypes") MobsimBeforeSimStepEvent e) {
            }
        };

        assertThatThrownBy(() -> new ModularDispatchModule.OptimizerRebindGuard(notModular, "drt"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("composition is inert");
    }

    @Test
    @DisplayName("does NOT fire when the in-effect DrtOptimizer IS ModularOptimizer")
    void doesNotFireWhenOptimizerIsModular() {
        // The guard's constructor only performs an instanceof check on `inEffect` - none of
        // ModularOptimizer's own fields are ever touched by it, so null constructor args are
        // safe here (ModularOptimizer's constructor does plain field assignment, no validation)
        // and keep this test focused on exactly what the guard checks.
        ModularOptimizer modular = new ModularOptimizer(null, null, null, null);

        assertThatCode(() -> new ModularDispatchModule.OptimizerRebindGuard(modular, "drt"))
                .doesNotThrowAnyException();
    }
}
