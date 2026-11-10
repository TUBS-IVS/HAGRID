package hagrid.core.routing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class RoutingOutputConsistencyTest {
    @TempDir Path output;
    @Test void phaseMeasurementsAndCostsDescribeTheIndependentlyValidatedCandidate() throws Exception {
        try (var f = new OptimizationFixture()) {
            f.service("a", 1, 0, 500);
            var result = new CarrierOptimizationService().optimize(f.carrier, f.request, f.config, 42, 1);
            var execution = result.metrics().execution.orElseThrow();
            var phases = execution.phases().orElseThrow();
            assertThat(execution.contextFingerprint()).isEqualTo(f.context.fingerprint());
            assertThat(execution.validationStatus()).isEqualTo("VALIDATED");
            assertThat(execution.actualIterations()).isEqualTo(result.iterations());
            assertThat(execution.searchCost()).isEqualTo(result.searchCost());
            assertThat(execution.finalCost()).isEqualTo(PlanValidator.validate(f.carrier, result.plan(), f.request, f.config).finalCost());
            assertThat(execution.matSimScore()).isNull();
            assertThat(result.plan().getJspritScore()).isNotNull();
            assertThat(phases.preparationSeconds()).isGreaterThanOrEqualTo(0);
            assertThat(phases.searchSeconds()).isGreaterThanOrEqualTo(0);
            assertThat(phases.finalRoutingSeconds()).isGreaterThanOrEqualTo(0);
            assertThat(phases.validationSeconds()).isGreaterThanOrEqualTo(0);
            assertThat(phases.preparationSeconds()+phases.searchSeconds()+phases.finalRoutingSeconds()+phases.validationSeconds())
                    .isCloseTo(result.metrics().totalSeconds, within(1e-8));
            var csv = output.resolve("outcomes.csv");
            RoutingMetricsCsv.write(List.of(result.metrics()), csv);
            var lines = Files.readAllLines(csv);
            assertThat(lines).hasSize(2);
            assertThat(lines.getFirst()).startsWith("carrierId,provider,services,shipments,totalCapacityDemand")
                    .contains("contextFingerprint,validationStatus,actualIterations")
                    .contains("preparationSeconds,searchSeconds,finalRoutingSeconds,validationSeconds")
                    .contains("searchCost,finalCost,matSimScore,searchTerminationReason");
            assertThat(lines.get(1)).contains(f.context.fingerprint()+",VALIDATED,1,");
            assertThat(lines.get(1)).contains(",MAXIMUM,");
        }
    }
    @Test void oldMetricsDoNotInventHistoricalPhaseTimesOrScores() throws Exception {
        var old = new CarrierRoutingMetrics("c", "two,LSP", 1, 0, 1, 0, 1,
                "SMALL", "LEGACY", "delivery", 0, 0, 0);
        assertThat(old.execution).isEmpty();
        var csv = output.resolve("legacy.csv");
        RoutingMetricsCsv.write(List.of(old), csv);
        var lines = Files.readAllLines(csv);
        assertThat(lines.get(1)).contains("\"two,LSP\"");
        assertThat(lines.get(1)).doesNotContain("NaN", "Infinity");
        assertThat(lines.get(1)).endsWith(",".repeat(12));
    }
    @Test void cachedResultKeepsHistoryUnknownInsteadOfCallingCacheLoadSolverTime() {
        try (var f = new OptimizationFixture()) {
            f.service("a", 1, 0, 500);
            var result = new CarrierOptimizationService().optimize(f.carrier, f.request, f.config, 42, 1);
            var store = new OptimizationResultStore(output, () -> Optional.of("fixture-build"));
            store.save(f.carrier, result, f.request, f.config, 42);
            var cached = store.load(f.carrier, f.request, f.config, 42).orElseThrow();
            assertThat(cached.metrics().jspritSeconds).isNaN();
            assertThat(cached.metrics().routeSeconds).isNaN();
            var execution = cached.metrics().execution.orElseThrow();
            assertThat(execution.validationStatus()).isEqualTo("VALIDATED_CACHE");
            assertThat(execution.phases()).isEmpty();
            assertThat(execution.finalCost()).isEqualTo(result.finalCost());
            assertThat(execution.searchCost()).isEqualTo(result.searchCost());
            assertThat(execution.matSimScore()).isNull();
        }
    }
    @Test void incompleteSearchIsNeverLabeledValidated() {
        try (var f = new OptimizationFixture()) {
            f.service("too-large", 11, 0, 500);
            var result = new CarrierOptimizationService().optimize(f.carrier, f.request, f.config, 42, 1);
            var execution = result.metrics().execution.orElseThrow();
            assertThat(execution.validationStatus()).isEqualTo("INVALID");
            assertThat(execution.finalCost()).isInfinite();
        }
    }
}