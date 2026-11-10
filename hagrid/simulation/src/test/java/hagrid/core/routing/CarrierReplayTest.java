package hagrid.core.routing;

import hagrid.core.HagridConfig;
import hagrid.core.config.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Id;
import org.matsim.freight.carriers.*;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class CarrierReplayTest {
    @TempDir Path directory;
    @Test void sectionFastRegretSurvivesReplayAndOldConfigsDefaultFalse() throws Exception {
        var base = new hagrid.core.HagridConfig();
        var old = hagrid.core.config.HagridConfigResolver.resolve(base, hagrid.core.config.HagridConfigResolver.Overrides.empty(), hagrid.core.config.HagridConfigResolver.Overrides.empty(), java.util.Map.of());
        assertThat(ReplayConfiguration.of(old).restore().sections().fastRegret()).isFalse();
        var values = java.util.Map.of("routing.sectionMaxSpanMeters", "200", "routing.haltCatalogFile", "halts.json", "routing.sectionFastRegret", "true");
        var enabled = hagrid.core.config.HagridConfigResolver.resolve(base, hagrid.core.config.HagridConfigResolver.Overrides.empty(), hagrid.core.config.HagridConfigResolver.Overrides.of(values), java.util.Map.of());
        var restored = ReplayConfiguration.of(enabled).restore();
        assertThat(restored.sections().fastRegret()).isTrue();
        assertThat(restored.effectiveValues()).containsEntry("routing.sectionFastRegret", "true");
        assertThat(restored.provenance()).containsEntry("routing.sectionFastRegret", hagrid.core.config.ResolvedHagridConfig.Source.RUN);
        try (var fixture = new OptimizationFixture(enabled)) {
            Path archive=ReplayContextArchive.save(directory.resolve("fast-context"),fixture.context);
            try(var actual=ReplayContextArchive.read(archive)) {
                assertThat(actual.config().sections().fastRegret()).isTrue();
                assertThat(actual.config().effectiveValues()).containsEntry("routing.sectionFastRegret","true");
                assertThat(actual.config().provenance()).containsEntry("routing.sectionFastRegret",ResolvedHagridConfig.Source.RUN);
            }
        }
    }
    @Test void changedReferencedJobCannotBeCertifiedAsReplayInput() {
        try(var f=new OptimizationFixture(2)) {
            var original=f.service("a",1,600,900);var old=f.plan(0,original);
            f.carrier.addPlan(old);f.carrier.setSelectedPlan(old);f.service("a",1,0,100);
            Path target=directory.resolve("stale");
            assertThatIllegalArgumentException().isThrownBy(() -> CarrierReplay.exportCase(target,f.carrier,f.request,f.config,42))
                    .withMessageContaining("canonical");
            assertThat(target).doesNotExist();
        }
    }

    @Test void replayUsesIdenticalInputAndContextAfterOriginalContextCloses() throws Exception {
        CarrierOptimizationService.Result expected;
        String fingerprint;
        Path exported = directory.resolve("first");
        try (var f = new OptimizationFixture(2)) {
            var job = f.service("a", 1, 0.25, 900.75);
            var old = f.plan(0, job); f.carrier.addPlan(old); f.carrier.setSelectedPlan(old);
            expected = new CarrierOptimizationService().optimize(f.carrier, f.request, f.config, 42, 2);
            fingerprint = f.context.fingerprint();
            CarrierReplay.exportCase(exported, f.carrier, f.request, f.config, 42);
            assertThat(f.carrier.getSelectedPlan()).isSameAs(old);
        }
        var actual = CarrierReplay.replay(exported);
        assertThat(actual.contextFingerprint()).isEqualTo(fingerprint);
        assertThat(actual.finalCost()).isEqualTo(expected.finalCost());
        assertThat(actual.diagnostics()).isEmpty();
        assertThat(actual.unassignedJobIds()).isEmpty();
    }

    @Test void replayPreservesExplicitOutputSettingsWithoutResolvingNewDefaults() throws Exception {
        var config=HagridConfigResolver.resolve(new HagridConfig(),HagridConfigResolver.hannoverTarget(),
                HagridConfigResolver.Overrides.of(Map.of("optimization.replanningIterations","2",
                        "results.eventsWriteInterval","3","results.plansWriteInterval","4",
                        "results.freightHistogramWriteInterval","5")),Map.of());
        try(var f=new OptimizationFixture(config)) {
            f.service("a",1,0,900);
            CarrierReplay.exportCase(directory.resolve("outputs"),f.carrier,f.request,config,42);
            try(var files=Files.list(directory.resolve(".contexts"));
                var restored=ReplayContextArchive.read(files.findFirst().orElseThrow())) {
                assertThat(restored.config()).isEqualTo(config);
                assertThat(restored.fingerprint()).isEqualTo(f.context.fingerprint());
            }
        }
        assertThat(CarrierReplay.replay(directory.resolve("outputs")).diagnostics()).isEmpty();
    }
    @Test void missingContextFailsClearlyWithoutFreeSpeedFallback() throws Exception {
        try (var f = new OptimizationFixture(2)) {
            f.service("a", 1, 0, 900);
            CarrierReplay.exportCase(directory.resolve("case"), f.carrier, f.request, f.config, 42);
        }
        try (var contexts = Files.list(directory.resolve(".contexts"))) {
            Files.delete(contexts.findFirst().orElseThrow());
        }
        assertThatThrownBy(() -> CarrierReplay.replay(directory.resolve("case")))
                .hasMessageContaining("context");
    }

    @Test void twoCasesReferenceOnlyOneSharedContext() throws Exception {
        try (var f = new OptimizationFixture(2)) {
            f.service("a", 1, 0, 900);
            CarrierReplay.exportCase(directory.resolve("one"), f.carrier, f.request, f.config, 42);
            CarrierReplay.exportCase(directory.resolve("two"), f.carrier, f.request, f.config, 43);
        }
        try (var contexts = Files.list(directory.resolve(".contexts"))) {
            assertThat(contexts.toList()).hasSize(1);
        }
        assertThat(CarrierReplay.replay(directory.resolve("two")).unassignedJobIds()).isEmpty();
    }

    @Test void fractionalShipmentPairAndNeutralLegacyConfigurationSurviveReplay() throws Exception {
        try (var f = new OptimizationFixture(2)) {
            var shipment = CarrierShipment.Builder.newInstance(Id.create("s", CarrierShipment.class),
                    Id.createLinkId("road"), Id.createLinkId("stop"), 1)
                    .setPickupDuration(1.25).setDeliveryDuration(2.75)
                    .setPickupStartingTimeWindow(TimeWindow.newInstance(0.25, 800.75))
                    .setDeliveryStartingTimeWindow(TimeWindow.newInstance(0.5, 900.25)).build();
            f.carrier.getShipments().put(shipment.getId(), shipment);
            var config = HagridConfigResolver.resolve(new HagridConfig(), HagridConfigResolver.hannoverTarget(),
                    HagridConfigResolver.Overrides.of(Map.of("optimization.warmStartEnabled", "false",
                            "optimization.replanningIterations", "2")), Map.of());
            try (var context = RoutingContext.create(f.context.network(), f.types, f.context.snapshot(), config)) {
                var request = new RoutingRequest(f.carrier, context, "car");
                var expected = new CarrierOptimizationService().optimize(f.carrier, request, config, 42, 2);
                f.carrier.addPlan(expected.plan()); f.carrier.setSelectedPlan(expected.plan());
                CarrierReplay.exportCase(directory.resolve("shipment"), f.carrier, request, config, 42);
                var replay = CarrierReplay.replay(directory.resolve("shipment"));
                assertThat(replay.finalCost()).isEqualTo(expected.finalCost());
                assertThat(replay.diagnostics()).isEmpty();
            }
        }
    }

    @Test void corruptedCaseDoesNotStartAReplacementSearch() throws Exception {
        try (var f = new OptimizationFixture(2)) {
            f.service("a", 1, 0, 900);
            CarrierReplay.exportCase(directory.resolve("bad"), f.carrier, f.request, f.config, 42);
        }
        Files.writeString(directory.resolve("bad/carrier.xml"), "corrupt");
        assertThatThrownBy(() -> CarrierReplay.replay(directory.resolve("bad")))
                .hasMessageContaining("checksum");
    }

    @Test void variableMeasuredTimesAndFractionalNetworkEventsKeepExactContext() throws Exception {
        try (var f = new OptimizationFixture(2)) {
            f.type.getCostInformation().setCostsPerMeter(0.0);
            f.service("a", 1, 0.25, 900.75);
            var networkConfig = new org.matsim.core.config.groups.NetworkConfigGroup();
            networkConfig.setTimeVariantNetwork(true);
            var n = org.matsim.core.network.NetworkUtils.createNetwork(networkConfig);
            for (var node : f.context.network().getNodes().values())
                n.addNode(n.getFactory().createNode(node.getId(), node.getCoord()));
            for (var link : f.context.network().getLinks().values()) {
                var copy = n.getFactory().createLink(link.getId(), n.getNodes().get(link.getFromNode().getId()),
                        n.getNodes().get(link.getToNode().getId()));
                copy.setLength(link.getLength()); copy.setFreespeed(link.getFreespeed());
                copy.setCapacity(link.getCapacity()); copy.setNumberOfLanes(link.getNumberOfLanes());
                copy.setAllowedModes(link.getAllowedModes()); n.addLink(copy);
            }
            var event = new org.matsim.core.network.NetworkChangeEvent(60.125);
            event.addLink(n.getLinks().get(Id.createLinkId("road")));
            event.setFreespeedChange(new org.matsim.core.network.NetworkChangeEvent.ChangeValue(
                    org.matsim.core.network.NetworkChangeEvent.ChangeType.FACTOR, 0.5));
            org.matsim.core.network.NetworkUtils.setNetworkChangeEvents(n, List.of(event));
            var snapshot = TravelTimeSnapshot.capture(n, Map.of("car", (l,t,p,v) -> t < 60 ? 15.125 : 20.875),
                    Map.of(), 24, 2000, 60);
            try (var context = RoutingContext.create(n, f.types, snapshot, f.config)) {
                var request = new RoutingRequest(f.carrier, context, "car");
                var expected = new CarrierOptimizationService().optimize(f.carrier, request, f.config, 42, 2);
                CarrierReplay.exportCase(directory.resolve("measured"), f.carrier, request, f.config, 42);
                var replay = CarrierReplay.replay(directory.resolve("measured"));
                assertThat(replay.contextFingerprint()).isEqualTo(context.fingerprint());
                assertThat(replay.finalCost()).isEqualTo(expected.finalCost());
                try (var files = Files.list(directory.resolve(".contexts"));
                     var restored = ReplayContextArchive.read(files.findFirst().orElseThrow())) {
                    assertThat(restored.snapshot().fingerprint()).isEqualTo(snapshot.fingerprint());
                    assertThat(restored.snapshot().storageStats()).isEqualTo(snapshot.storageStats());
                    assertThat(restored.config()).isEqualTo(f.config);
                    assertThat(restored.snapshot().forMode("car").getLinkTravelTime(
                            restored.network().getLinks().get(Id.createLinkId("road")), 60, null, null)).isEqualTo(20.875);
                }
            }
        }
    }

    @Test void previousInfiniteFleetCloneDoesNotBecomeNewCanonicalDemand() throws Exception {
        try (var f = new OptimizationFixture(2)) {
            f.carrier.getCarrierCapabilities().setFleetSize(CarrierCapabilities.FleetSize.INFINITE);
            var job = f.service("a", 1, 0, 900);
            var tour = f.plan(0, job).getScheduledTours().iterator().next();
            var clone = CarrierVehicle.Builder.newInstance(Id.createVehicleId("historical-clone"), f.vehicle.getLinkId(), f.type)
                    .setEarliestStart(0).setLatestEnd(1000).build();
            var old = new CarrierPlan(new ArrayList<>(List.of(ScheduledTour.newInstance(tour.getTour(), clone, 0))));
            old.setCarrier(f.carrier); f.carrier.addPlan(old); f.carrier.setSelectedPlan(old);
            CarrierReplay.exportCase(directory.resolve("clone"), f.carrier, f.request, f.config, 42);
            var actual = CarrierReplay.replay(directory.resolve("clone"));
            assertThat(actual.diagnostics()).isEmpty();
            assertThat(f.carrier.getCarrierCapabilities().getCarrierVehicles()).containsOnlyKeys(f.vehicle.getId());
        }
    }

    @Test void neutralLegacyConfigurationIsRestoredWithoutResolvingNewDefaults() {
        var config=HagridConfigResolver.resolve(new HagridConfig(),HagridConfigResolver.hannoverTarget(),
                HagridConfigResolver.Overrides.of(Map.of("optimization.adaptiveSearchEnabled","false","optimization.replanningIterations","20")),Map.of());
        assertThat(ReplayConfiguration.of(config).restore()).isEqualTo(config);
    }

    @Test void standaloneMainRunsInItsOwnBoundedJvmAfterExporterContextCloses() throws Exception {
        Path target = directory.resolve("standalone");
        try (var f = new OptimizationFixture(2)) {
            f.service("a", 1, 0, 900);
            CarrierReplay.exportCase(target, f.carrier, f.request, f.config, 42);
        }
        String cp = System.getProperty("java.class.path").replace("\\", "\\\\").replace("\"", "\\\"");
        Path arguments = directory.resolve("java.args");
        Files.writeString(arguments, "-Xms32m\n-Xmx384m\n-XX:ActiveProcessorCount=1\n-cp\n\"" + cp
                + "\"\nhagrid.core.routing.CarrierReplay\n\"" + target.toString().replace("\\", "\\\\") + "\"\n");
        Path output = directory.resolve("standalone.log");
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                "@" + arguments).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertThat(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).withFailMessage(Files.readString(output)).isZero();
            assertThat(Files.readString(output)).contains("carrier=test", "iterations=2", "unassigned=[]", "diagnostics=[]");
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly(); process.waitFor();
            }
        }
    }
    @Test void obsoletePreviousActivityDurationCannotBeSilentlyReboundToNewDemand() {
        try (var f = new OptimizationFixture(2)) {
            var oldJob = f.service("a", 1, 0, 900);
            var old = f.plan(0, oldJob); f.carrier.addPlan(old); f.carrier.setSelectedPlan(old);
            var updated = CarrierService.Builder.newInstance(oldJob.getId(), oldJob.getServiceLinkId())
                    .setCapacityDemand(1).setServiceDuration(20)
                    .setServiceStartingTimeWindow(TimeWindow.newInstance(0, 900)).build();
            f.carrier.getServices().put(updated.getId(), updated);
            assertThat(PlanValidator.validate(f.carrier, old, f.request, f.config).valid()).isFalse();
            assertThatThrownBy(() -> CarrierReplay.exportCase(directory.resolve("obsolete"), f.carrier, f.request, f.config, 42))
                    .hasMessageContaining("previous plan");
            assertThat(directory.resolve("obsolete/manifest.json")).doesNotExist();
            assertThat(f.carrier.getSelectedPlan()).isSameAs(old);
        }
    }
    @Test void alteredPreciseDepotWindowCannotReuseOriginalPreviousPlanFingerprint() throws Exception {
        try(var f=new OptimizationFixture(2)) {
            var job=f.service("a",1,0,900);var plan=f.plan(5.25,job);
            f.carrier.addPlan(plan);f.carrier.setSelectedPlan(plan);
            var target=directory.resolve("altered-precise-window");
            CarrierReplay.exportCase(target,f.carrier,f.request,f.config,42);
            var json=new com.fasterxml.jackson.databind.ObjectMapper();
            var manifest=json.readTree(target.resolve("manifest.json").toFile());
            ((com.fasterxml.jackson.databind.node.ObjectNode)manifest.path("planState").path("tours").get(0).path("start"))
                    .put("earliest",4.25);
            json.writeValue(target.resolve("manifest.json").toFile(),manifest);
            assertThatThrownBy(()->CarrierReplay.replay(target)).hasMessageContaining("previous plan fingerprint mismatch");
            assertThat(f.carrier.getSelectedPlan()).isSameAs(plan);
        }
    }
    @Test void existingCaseCannotBeSilentlyOverwritten() throws Exception {
        try (var f = new OptimizationFixture(2)) {
            f.service("a", 1, 0, 900);
            var target = directory.resolve("existing");
            CarrierReplay.exportCase(target, f.carrier, f.request, f.config, 42);
            String before = Files.readString(target.resolve("manifest.json"));
            assertThatThrownBy(() -> CarrierReplay.exportCase(target, f.carrier, f.request, f.config, 43))
                    .hasMessageContaining("exists");
            assertThat(Files.readString(target.resolve("manifest.json"))).isEqualTo(before);
        }
    }
}