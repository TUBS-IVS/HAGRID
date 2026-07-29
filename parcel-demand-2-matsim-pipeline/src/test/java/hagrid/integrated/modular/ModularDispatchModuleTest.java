package hagrid.integrated.modular;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.matsim.contrib.drt.optimizer.DrtOptimizer;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;
import org.matsim.contrib.dvrp.optimizer.Request;
import org.matsim.core.mobsim.framework.events.MobsimBeforeSimStepEvent;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Direct-construction tests for the package-private {@link ModularDispatchModule.OptimizerRebindGuard}
 * (review J-F9). The guard itself already exists (Task 10 review, Item 2) - these tests PIN its
 * existing behaviour rather than drive new production code, so no red/green TDD cycle applies in
 * the usual sense. Discrimination reasoning (task-6-report.md has the full note): the guard's
 * single check is {@code !(inEffect instanceof ModularOptimizer)} - if that condition were
 * inverted to {@code (inEffect instanceof ModularOptimizer)}, {@link #firesWhenOptimizerIsNotModular()}
 * would wrongly pass through without throwing (FAIL) and {@link #doesNotFireWhenOptimizerIsModular()}
 * would wrongly throw (FAIL) - each test therefore fails if the guard's polarity is flipped, which
 * is the property that matters here.
 */
@DisplayName("ModularDispatchModule.OptimizerRebindGuard")
class ModularDispatchModuleTest {

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
