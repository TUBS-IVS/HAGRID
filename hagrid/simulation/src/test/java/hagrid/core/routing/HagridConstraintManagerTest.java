package hagrid.core.routing;

import com.graphhopper.jsprit.core.algorithm.recreate.InsertionCostBreakdown;
import com.graphhopper.jsprit.core.algorithm.state.StateManager;
import com.graphhopper.jsprit.core.problem.VehicleRoutingProblem;
import com.graphhopper.jsprit.core.problem.constraint.ConstraintManager;
import com.graphhopper.jsprit.core.problem.constraint.HardActivityConstraint;
import com.graphhopper.jsprit.core.problem.constraint.HardRouteConstraint;
import com.graphhopper.jsprit.core.problem.constraint.SoftActivityConstraint;
import com.graphhopper.jsprit.core.problem.constraint.SoftRouteConstraint;
import com.graphhopper.jsprit.core.problem.misc.JobInsertionContext;
import com.graphhopper.jsprit.core.problem.solution.route.activity.TourActivity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class HagridConstraintManagerTest {
    private ConstraintManager manager(boolean optimized) {
        var problem = VehicleRoutingProblem.Builder.newInstance().build();
        var states = new StateManager(problem);
        return optimized ? new HagridConstraintManager(problem, states) : new ConstraintManager(problem, states);
    }

    @Test
    void repeatedEmptyActivityDiagnosticsReuseOneImmutableZero() {
        var manager = manager(true);
        var first = manager.getActivityCostsBreakdown(null, null, null, null, 0);
        var next = manager.getActivityCostsBreakdown(null, null, null, null, 10);
        assertThat(next).isSameAs(first);
        assertThat(first.getComponents()).isEmpty();
        assertThat(first.getTotal()).isZero();
        assertThatThrownBy(() -> first.add("contamination", 4)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> first.merge(new InsertionCostBreakdown())).isInstanceOf(UnsupportedOperationException.class);
        assertThat(next.getTotal()).isZero();
    }

    @Test
    void zeroValuedConstraintsAreStillEvaluatedForEveryPosition() {
        var manager = manager(true);
        var calls = new AtomicInteger();
        manager.addConstraint((SoftActivityConstraint) (context, previous, inserted, next, departure) -> {
            calls.incrementAndGet();
            return 0;
        });
        var first = manager.getActivityCostsBreakdown(null, null, null, null, 0);
        var next = manager.getActivityCostsBreakdown(null, null, null, null, 25);
        assertThat(next).isSameAs(first);
        assertThat(calls.get()).isEqualTo(2);
        assertThat(next.getTotal()).isZero();
    }

    @Test
    void constraintsRegisteredAfterTheFirstZeroRemainVisible() {
        var manager = manager(true);
        var zero = manager.getActivityCostsBreakdown(null, null, null, null, 0);
        manager.addConstraint(new ActivityCost(7));
        var nonzero = manager.getActivityCostsBreakdown(null, null, null, null, 0);
        assertThat(nonzero.getTotal()).isEqualTo(7);
        assertThat(nonzero.getComponents()).containsEntry("Activity:ActivityCost", 7.0);
        assertThat(zero.getTotal()).isZero();
    }

    @Test
    void componentGroupingAndFloatingPointTotalsMatchNativeExactly() {
        for (var values : List.of(new double[]{3, 4, -1}, new double[]{1e16, 1, -1e16},
                new double[]{0, -0.0, 0}, new double[]{Double.POSITIVE_INFINITY},
                new double[]{Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}, new double[]{Double.NaN})) {
            var nativeManager = manager(false);
            var optimized = manager(true);
            for (double value : values) {
                nativeManager.addConstraint(new ActivityCost(value));
                optimized.addConstraint(new ActivityCost(value));
                nativeManager.addConstraint(new RouteCost(value));
                optimized.addConstraint(new RouteCost(value));
            }
            same(nativeManager.getActivityCostsBreakdown(null, null, null, null, 0),
                    optimized.getActivityCostsBreakdown(null, null, null, null, 0));
            same(nativeManager.getRouteCostsBreakdown(null), optimized.getRouteCostsBreakdown(null));
        }
    }

    @Test
    void routeDiagnosticsRemainIndependentAndAcceptNativeAccessEgressUpdates() {
        var manager = manager(true);
        var first = manager.getRouteCostsBreakdown(null);
        var second = manager.getRouteCostsBreakdown(null);
        first.add("AccessEgress", 11);
        assertThat(first.getTotal()).isEqualTo(11);
        assertThat(second.getComponents()).isEmpty();
        assertThat(second.getTotal()).isZero();
    }

    @Test
    void nativeWinnerDiagnosticsCanMergeTheOptimizedResults() {
        var manager = manager(true);
        var result = new InsertionCostBreakdown();
        result.merge(manager.getActivityCostsBreakdown(null, null, null, null, 0));
        manager.addConstraint(new ActivityCost(5));
        manager.addConstraint(new RouteCost(3));
        result.merge(manager.getActivityCostsBreakdown(null, null, null, null, 0));
        result.merge(manager.getRouteCostsBreakdown(null));
        result.add("ActivityInsertion", 17);
        assertThat(result.getTotal()).isEqualTo(25);
        assertThat(result.getComponents()).containsEntry("Activity:ActivityCost", 5.0)
                .containsEntry("Route:RouteCost", 3.0).containsEntry("ActivityInsertion", 17.0);
    }

    @Test
    void nonzeroDiagnosticsRemainIndependentAcrossCalls() {
        var manager = manager(true);
        manager.addConstraint(new ActivityCost(3));
        var first = manager.getActivityCostsBreakdown(null, null, null, null, 0);
        var second = manager.getActivityCostsBreakdown(null, null, null, null, 0);
        first.add("caller detail", 17);
        assertThat(second.getTotal()).isEqualTo(3);
        assertThat(second.getComponents()).doesNotContainKey("caller detail");
    }

    @Test
    void hardConstraintsStillUseTheNativeManagers() {
        var manager = manager(true);
        manager.addConstraint((HardRouteConstraint) context -> false);
        manager.addConstraint((HardActivityConstraint) (context, previous, inserted, next, departure) ->
                HardActivityConstraint.ConstraintsStatus.NOT_FULFILLED_BREAK, ConstraintManager.Priority.CRITICAL);
        assertThat(manager.fulfilled((JobInsertionContext) null)).isFalse();
        assertThat(manager.fulfilled(null, null, null, null, 0))
                .isEqualTo(HardActivityConstraint.ConstraintsStatus.NOT_FULFILLED_BREAK);
    }

    @Test
    void distinctComponentTotalsRetainNativeCompensatedSummation() {
        var nativeManager = manager(false);
        var optimized = manager(true);
        for (var manager : List.of(nativeManager, optimized)) {
            for (Component cost : List.of(new FirstComponent(1e16), new SecondComponent(1),
                    new ThirdComponent(-1e16))) {
                manager.addConstraint((SoftActivityConstraint) cost);
                manager.addConstraint((SoftRouteConstraint) cost);
            }
        }
        var expectedActivity = nativeManager.getActivityCostsBreakdown(null, null, null, null, 0);
        assertThat(expectedActivity.getComponents()).hasSize(3);
        same(expectedActivity, optimized.getActivityCostsBreakdown(null, null, null, null, 0));
        same(nativeManager.getRouteCostsBreakdown(null), optimized.getRouteCostsBreakdown(null));
    }

    private interface Component extends SoftActivityConstraint, SoftRouteConstraint {
        double value();
        @Override default double getCosts(JobInsertionContext context) { return value(); }
        @Override default double getCosts(JobInsertionContext context, TourActivity previous,
                TourActivity inserted, TourActivity next, double departure) { return value(); }
    }
    private record FirstComponent(double value) implements Component { }
    private record SecondComponent(double value) implements Component { }
    private record ThirdComponent(double value) implements Component { }

    private void same(InsertionCostBreakdown expected, InsertionCostBreakdown actual) {
        assertThat(actual.getComponents()).isEqualTo(expected.getComponents());
        assertThat(Double.doubleToLongBits(actual.getTotal())).isEqualTo(Double.doubleToLongBits(expected.getTotal()));
    }

    private record ActivityCost(double value) implements SoftActivityConstraint {
        @Override public double getCosts(JobInsertionContext context, TourActivity previous,
                TourActivity inserted, TourActivity next, double departure) { return value; }
    }

    private record RouteCost(double value) implements SoftRouteConstraint {
        @Override public double getCosts(JobInsertionContext context) { return value; }
    }
}
