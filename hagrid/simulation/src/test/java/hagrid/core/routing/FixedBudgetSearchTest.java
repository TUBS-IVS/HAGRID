package hagrid.core.routing;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class FixedBudgetSearchTest {
    @Test void selectedPlanCannotChangeIndependentSearchAndValidationProofSurvivesRemoval() {
        try(var f=new OptimizationFixture(2)) {
            var a=f.service("a",1,0,500);var old=f.plan(25,a);
            f.carrier.addPlan(old);f.carrier.setSelectedPlan(old);
            var result=new CarrierOptimizationService().optimize(f.carrier,f.request,f.config,42,2);
            assertThat(result.iterations()).isEqualTo(2);
            assertThat(result.searchTerminationReason()).isEqualTo("MAXIMUM");
            assertThat(result.diagnostics()).isEmpty();
            assertThat(result.validatedCandidate()).isPresent();
            assertThat(result.metrics().routingStatistics).isPresent();
            assertThat(f.carrier.getSelectedPlan()).isSameAs(old);
            f.carrier.setSelectedPlan(null);
            var independent=new CarrierOptimizationService().optimize(f.carrier,f.request,f.config,42,2);
            assertThat(independent.finalCost()).isEqualTo(result.finalCost());
            assertThat(CarrierPlanFingerprint.of(independent.plan())).isEqualTo(CarrierPlanFingerprint.of(result.plan()));
        }
    }
    @Test void initialPlanningKeepsItsFixedIterationBudget() {
        try(var f=new OptimizationFixture()) {
            f.service("a",1,0,500);
            var result=new CarrierOptimizationService().optimize(f.carrier,f.costs,f.config,42,1,
                    HAGRIDRouterUtils.AlgorithmPhase.INITIAL,25200,f.context.fingerprint());
            assertThat(result.iterations()).isEqualTo(1);
            assertThat(result.searchTerminationReason()).isEqualTo("MAXIMUM");
            assertThat(result.diagnostics()).isEmpty();
        }
    }
}
