package hagrid.core.routing;

import hagrid.core.routing.benchmark.RoutingComparison;
import hagrid.core.routing.benchmark.RoutingComparison.Metrics;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RoutingComparisonContractTest {
    private static final Metrics BASELINE = sample(30, "rules", 10, 0, 100, 1000, 100_000);

    private static Metrics sample(int budget, String rules, int served, int violations,
                                  double cost, double milliseconds, long heap) {
        return new Metrics("services", "input", rules, "snapshot", 42, budget, 1024,
                10, served, violations, 2, cost, milliseconds, heap);
    }

    @Test void incompleteCheaperPlanIsNotAnImprovement() {
        var result = RoutingComparison.evaluate(BASELINE, sample(30, "rules", 9, 0, 50, 500, 50_000));
        assertThat(result.comparable()).isTrue();
        assertThat(result.improved()).isFalse();
        assertThat(result.reasons()).contains("candidate has unassigned jobs");
    }

    @Test void differentBudgetsAreFlagged() {
        var result = RoutingComparison.evaluate(BASELINE, sample(1, "rules", 10, 0, 100, 100, 50_000));
        assertThat(result.comparable()).isFalse();
        assertThat(result.improved()).isFalse();
        assertThat(result.reasons()).contains("different iteration budgets");
    }

    @Test void differentActualIterationsAreFlaggedEvenWithTheSameCeiling() {
        var result = RoutingComparison.evaluate(BASELINE,
                sample(30, "rules", 10, 0, 100, 900, 100_000), 30, 27);
        assertThat(result.comparable()).isFalse();
        assertThat(result.improved()).isFalse();
        assertThat(result.reasons()).contains("different actual iteration counts");
    }

    @Test void changedRulesAreNotCalledAQualityImprovement() {
        assertThat(RoutingComparison.evaluate(BASELINE,
                sample(30, "new-cost-rules", 10, 0, 50, 500, 50_000)).comparable()).isFalse();
    }

    @Test void fasterButMoreExpensiveIsNotAnImprovement() {
        assertThat(RoutingComparison.evaluate(BASELINE,
                sample(30, "rules", 10, 0, 101, 500, 50_000)).improved()).isFalse();
    }

    @Test void fasterButInfeasibleIsNotAnImprovement() {
        assertThat(RoutingComparison.evaluate(BASELINE,
                sample(30, "rules", 10, 1, 50, 500, 50_000)).improved()).isFalse();
    }

    @Test void equalMetricsAreNotAnImprovement() {
        assertThat(RoutingComparison.evaluate(BASELINE, BASELINE).improved()).isFalse();
    }

    @Test void fasterCompleteEquivalentSolutionIsAnImprovement() {
        assertThat(RoutingComparison.evaluate(BASELINE,
                sample(30, "rules", 10, 0, 100, 900, 100_000)).improved()).isTrue();
    }

    @Test void nonFiniteCostCannotProduceAnImprovement() {
        var result = RoutingComparison.evaluate(BASELINE,
                sample(30, "rules", 10, 0, Double.NaN, 500, 50_000));
        assertThat(result.comparable()).isFalse();
        assertThat(result.improved()).isFalse();
    }

    @Test void unknownMemoryCannotProduceAMemoryImprovement() {
        var result = RoutingComparison.evaluate(BASELINE,
                sample(30, "rules", 10, 0, 100, 1000, -1));
        assertThat(result.improved()).isFalse();
        assertThat(result.reasons()).contains("memory measurement unavailable");
    }
}
