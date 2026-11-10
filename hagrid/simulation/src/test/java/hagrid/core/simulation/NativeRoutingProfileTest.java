package hagrid.core.simulation;

import hagrid.core.routing.*;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.core.router.util.TravelTime;
import org.matsim.freight.carriers.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class NativeRoutingProfileTest {
    @Test void mutationsAfterJspritUseEachCompletedProfileAndFinalCostsUseTheLastAcceptedProfile() {
        try (var f = new ReplanningCoordinatorTest.Fixture(1);
             var coordinator = new ReplanningCoordinator(f.config, 42)) {
            var carrier = carrier(f, "lsp-00");
            widenAvailability(carrier);
            coordinator.runGroup(25, f.carriers, () -> context(f, 24, (l,t,p,v) -> 10));
            var groupFingerprint = carrier.getAttributes().getAttribute("routingContextFingerprint");
            var captures = new AtomicInteger();
            for (int iteration = 26; iteration <= 27; iteration++) {
                double seconds = iteration == 26 ? 20 : 30;
                var latest = context(f, iteration - 1, (l,t,p,v) -> seconds);
                coordinator.evolve(iteration, Map.of(carrier, NativeCarrierPlanEvolution.Choice.MUTATE), () -> {
                    captures.incrementAndGet(); return latest;
                });
                assertThat(firstLeg(carrier.getSelectedPlan()).getExpectedTransportTime()).isEqualTo(seconds);
                // Three driven 100-metre links, .01/m, 1/s, fixed 1, service 1 second.
                assertThat(carrier.getAttributes().getAttribute("validatedRoutingCost"))
                        .isEqualTo(iteration == 26 ? 65.0 : 95.0);
                assertThatThrownBy(latest::requireOpen).isInstanceOf(IllegalStateException.class);
            }
            assertThat(captures).hasValue(2);
            assertThat(carrier.getAttributes().getAttribute("routingContextFingerprint")).isEqualTo(groupFingerprint);
            assertThat(carrier.getAttributes().getAttribute("routingMeasurementIteration")).isEqualTo(24);
            var accepted = carrier.getSelectedPlan();
            coordinator.evolve(28, Map.of(carrier, NativeCarrierPlanEvolution.Choice.MUTATE),
                    () -> context(f, 27, (l,t,p,v) -> 10000));
            assertThat(carrier.getSelectedPlan()).as("infeasible new timing cannot replace the accepted plan").isSameAs(accepted);
            carrier.getSelectedPlan().setScore(-123.0);
            var finalResult = coordinator.finalMetrics().getFirst().execution.orElseThrow();
            assertThat(finalResult.validationStatus()).isEqualTo("VALIDATED_FINAL");
            assertThat(finalResult.finalCost()).isEqualTo(95.0);
            assertThat(finalResult.matSimScore()).isEqualTo(-123.0);
        }
    }

    @Test void historicalSelectionReroutesOntoTheNewlyFasterRoadAndFinalReplayKeepsThatProfile() {
        try (var f = new ReplanningCoordinatorTest.Fixture(1);
             var coordinator = new ReplanningCoordinator(f.config, 42)) {
            Link back = f.network.getLinks().get(Id.createLinkId("back"));
            var shortcut = f.network.getFactory().createLink(Id.createLinkId("shortcut"), back.getFromNode(), back.getToNode());
            shortcut.setLength(100); shortcut.setFreespeed(10); shortcut.setCapacity(36000);
            shortcut.setNumberOfLanes(1); shortcut.setAllowedModes(Set.of("car")); f.network.addLink(shortcut);
            var original = context(f, 24, (l,t,p,v) -> l.getId().toString().equals("shortcut") ? 100 : 10);
            coordinator.runGroup(25, f.carriers, () -> original);
            var carrier = carrier(f, "lsp-00");
            carrier.getSelectedPlan().setScore(0.0);
            var historical = alternative(carrier, 10.0);
            assertThat(returnRoad(historical).getLinkIds()).contains(Id.createLinkId("back"));
            var latest = context(f, 25, (l,t,p,v) -> l.getId().toString().equals("back") ? 100 : 10);
            coordinator.evolve(26, Map.of(carrier, NativeCarrierPlanEvolution.Choice.BEST), () -> latest);
            assertThat(returnRoad(carrier.getSelectedPlan()).getLinkIds())
                    .contains(Id.createLinkId("shortcut")).doesNotContain(Id.createLinkId("back"));
            assertThat(returnRoad(historical).getLinkIds()).contains(Id.createLinkId("back"));
            assertThat(coordinator.finalMetrics().getFirst().execution.orElseThrow().finalCost()).isEqualTo(35.0);
        }
    }

    @Test void carriersFromDifferentGroupsShareOneNewProfileAndSupersededNativeArchivesAreReleased() throws Exception {
        var before = archiveRoots();
        try (var f = new ReplanningCoordinatorTest.Fixture(3, Map.of("optimization.carriersPerGroup", "2",
                "optimization.replanningIterations", "1")); var coordinator = new ReplanningCoordinator(f.config, 42)) {
            coordinator.runGroup(25, f.carriers, () -> f.context);
            coordinator.runGroup(26, f.carriers, () -> context(f, 25, (l,t,p,v) -> 20));
            var atGroupBoundary = new LinkedHashMap<Carrier,NativeCarrierPlanEvolution.Choice>();
            for (var carrier : f.carriers.getCarriers().values()) {
                carrier.getSelectedPlan().setScore(0.0); alternative(carrier, 10.0);
                atGroupBoundary.put(carrier,NativeCarrierPlanEvolution.Choice.BEST);
            }
            coordinator.evolve(26,atGroupBoundary,() -> { throw new AssertionError("Reuse this iteration's fresh group capture"); });
            for (var carrier : f.carriers.getCarriers().values())
                assertThat(firstLeg(carrier.getSelectedPlan()).getExpectedTransportTime()).isEqualTo(20.0);
            var roots = archiveRoots(); roots.removeAll(before);
            assertThat(roots).hasSize(1);
            Path archiveRoot = roots.iterator().next();
            var captures = new AtomicInteger();
            for (int iteration = 27; iteration <= 30; iteration++) {
                var choices = new LinkedHashMap<Carrier,NativeCarrierPlanEvolution.Choice>();
                for (var carrier : f.carriers.getCarriers().values()) {
                    carrier.getSelectedPlan().setScore(0.0); alternative(carrier, 10.0);
                    choices.put(carrier, NativeCarrierPlanEvolution.Choice.BEST);
                }
                double seconds = iteration;
                var latest = context(f, iteration - 1, (l,t,p,v) -> seconds);
                coordinator.evolve(iteration, choices, () -> { captures.incrementAndGet(); return latest; });
                for (var carrier : f.carriers.getCarriers().values())
                    assertThat(firstLeg(carrier.getSelectedPlan()).getExpectedTransportTime()).isEqualTo(seconds);
                try (var files = Files.list(archiveRoot.resolve(".contexts"))) {
                    assertThat(files.filter(p -> p.toString().endsWith(".zip")).count()).isLessThanOrEqualTo(3);
                }
            }
            assertThat(captures).hasValue(4);
            assertThat(coordinator.finalMetrics()).hasSize(3).allSatisfy(result ->
                    assertThat(result.execution.orElseThrow().finalCost()).isEqualTo(95.0));
        }
        var remaining = archiveRoots(); remaining.removeAll(before);
        assertThat(remaining).isEmpty();
    }

    @Test void completedGroupCannotBypassMissingOrStalePreviousMeasurements() {
        try (var f = new ReplanningCoordinatorTest.Fixture(1);
             var coordinator = new ReplanningCoordinator(f.config, 42)) {
            coordinator.runGroup(25, f.carriers, () -> f.context);
            var carrier = carrier(f, "lsp-00"); var plan = carrier.getSelectedPlan();
            var choices = Map.of(carrier, NativeCarrierPlanEvolution.Choice.MUTATE);
            assertThatThrownBy(() -> coordinator.evolve(26, choices, () -> {
                throw new IllegalStateException("No completed measurement");
            })).isInstanceOf(IllegalStateException.class).hasMessageContaining("completed");
            assertThat(carrier.getSelectedPlan()).isSameAs(plan);
            var stale = context(f, 24, (l,t,p,v) -> 20);
            assertThatThrownBy(() -> coordinator.evolve(26, choices, () -> stale))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("preceding");
            assertThat(carrier.getSelectedPlan()).isSameAs(plan);
            assertThatThrownBy(stale::requireOpen).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test void firstGroupedResultAndEmptyStrategyRoundsDoNotCaptureAnotherProfile() {
        try (var f = new ReplanningCoordinatorTest.Fixture(1);
             var coordinator = new ReplanningCoordinator(f.config, 42)) {
            coordinator.runGroup(25, f.carriers, () -> f.context);
            var carrier = carrier(f, "lsp-00"); var plan = carrier.getSelectedPlan();
            coordinator.evolve(25, Map.of(carrier, NativeCarrierPlanEvolution.Choice.MUTATE), () -> {
                throw new AssertionError("Do not replace the grouped result before its first QSim");
            });
            coordinator.evolve(26, Map.of(), () -> { throw new AssertionError("No changed plans"); });
            assertThat(carrier.getSelectedPlan()).isSameAs(plan);
        }
    }

    private static Carrier carrier(ReplanningCoordinatorTest.Fixture f, String id) {
        return f.carriers.getCarriers().get(Id.create(id, Carrier.class));
    }
    private static void widenAvailability(Carrier carrier) {
        var v = carrier.getCarrierCapabilities().getCarrierVehicles().values().iterator().next();
        carrier.setCarrierCapabilities(CarrierCapabilities.Builder.newInstance().addVehicle(CarrierVehicle.Builder
                .newInstance(v.getId(), v.getLinkId(), v.getType()).setEarliestStart(0).setLatestEnd(20000).build()).build());
        var job = carrier.getServices().values().iterator().next();
        carrier.getServices().put(job.getId(), CarrierService.Builder.newInstance(job.getId(), job.getServiceLinkId())
                .setCapacityDemand(1).setServiceDuration(1).setServiceStartingTimeWindow(TimeWindow.newInstance(0,19000)).build());
    }
    private static RoutingContext context(ReplanningCoordinatorTest.Fixture f, int iteration, TravelTime times) {
        return RoutingContext.create(f.network, f.types,
                TravelTimeSnapshot.capture(f.network, Map.of("car",times), Map.of(), iteration, 20000, 60), f.config);
    }
    private static CarrierPlan alternative(Carrier carrier, double score) {
        var tours = new ArrayList<ScheduledTour>();
        for (var t : carrier.getSelectedPlan().getScheduledTours())
            tours.add(ScheduledTour.newInstance(t.getTour().duplicate(),t.getVehicle(),t.getDeparture()));
        var plan = new CarrierPlan(tours); plan.setScore(score); carrier.addPlan(plan); return plan;
    }
    private static Tour.Leg firstLeg(CarrierPlan plan) {
        return (Tour.Leg) plan.getScheduledTours().iterator().next().getTour().getTourElements().getFirst();
    }
    private static NetworkRoute returnRoad(CarrierPlan plan) {
        return (NetworkRoute) ((Tour.Leg) plan.getScheduledTours().iterator().next().getTour().getTourElements().getLast()).getRoute();
    }
    private static Set<Path> archiveRoots() throws Exception {
        try (var files = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return new HashSet<>(files.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().startsWith("hagrid-replanning-contexts-")).toList());
        }
    }
}
