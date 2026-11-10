package hagrid.hannover.demand;

import hagrid.core.HagridConfig;
import hagrid.core.HagridPaths;
import hagrid.core.routing.ThreadingType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.freight.carriers.*;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class OfflineCarrierModalRoutingTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"bike", "cargobike"})
    void actualOfflineRunServesBothModesOnTheCommonGraph(String mode) throws Exception {
        var fixture = fixture(mode);
        // Imported missing metadata must be inferred from the actual fleet.
        fixture.bike.getAttributes().removeAttribute("carrierMode");
        fixture.router.run();
        var outcomes = java.nio.file.Files.readAllLines(Path.of(fixture.config.io().routingMetrics()));
        assertThat(outcomes.getFirst()).contains("contextFingerprint,validationStatus,actualIterations");
        var columns=java.util.Arrays.asList(outcomes.getFirst().split(",",-1));
        assertThat(columns).contains("searchTerminationReason",
                "contextExactQueries");
        assertThat(outcomes.subList(1, outcomes.size())).allSatisfy(row -> {
            assertThat(row).contains("initial-native-profile,VALIDATED,1,");
            var fields=row.split(",",-1);assertThat(fields).hasSize(columns.size());
            assertThat(fields[columns.indexOf("searchTerminationReason")]).isEqualTo("MAXIMUM");
            for(String metric:java.util.List.of("contextExactQueries"))
                assertThat(fields[columns.indexOf(metric)]).as("Native path has no measured context counter: "+metric).isEmpty();
        });
        for (Carrier carrier : List.of(fixture.car, fixture.bike)) {
            assertThat(carrier.getSelectedPlan()).as(carrier.getId().toString()).isNotNull();
            assertThat(carrier.getSelectedPlan().getScheduledTours()).hasSize(1);
            String expectedMode = carrier == fixture.car ? "car" : mode;
            assertThat(CarriersUtils.getCarrierMode(carrier)).isEqualTo(expectedMode);
            assertThat(CarriersUtils.getJspritIterations(carrier)).isEqualTo(1);
            for (ScheduledTour scheduled : carrier.getSelectedPlan().getScheduledTours()) {
                assertThat(scheduled.getTour().getTourElements()).filteredOn(e -> e instanceof Tour.ServiceActivity).hasSize(1);
                for (var element : scheduled.getTour().getTourElements()) if (element instanceof Tour.Leg leg) {
                    var route = (NetworkRoute) leg.getRoute();
                    var links = new java.util.ArrayList<>(route.getLinkIds());
                    links.add(route.getStartLinkId());
                    links.add(route.getEndLinkId());
                    assertThat(links).allSatisfy(id -> assertThat(fixture.network.getLinks().get(id).getAllowedModes()).contains(expectedMode));
                }
            }
        }
        assertThat(Path.of(fixture.config.io().deliveryCarriersRouted())).exists();
        var restored = new Carriers();
        new CarrierPlanXmlReader(restored, fixture.types).readFile(fixture.config.io().deliveryCarriersRouted());
        assertThat(restored.getCarriers()).hasSize(2);
        assertThat(CarriersUtils.getCarrierMode(restored.getCarriers().get(fixture.bike.getId()))).isEqualTo(mode);
    }

    @Test
    void invalidSupplyIsDetectedBeforeSolvingOrPublishingDelivery() throws Exception {
        var fixture = fixture("car");
        var bad = carrier("invalid-supply", "missing-", fixture.car.getCarrierCapabilities().getCarrierVehicles().values().iterator().next().getType());
        fixture.supply.addCarrier(bad);
        assertThatThrownBy(fixture.router::run).isInstanceOf(IllegalStateException.class);
        assertThat(fixture.car.getSelectedPlan()).isNull();
        assertThat(fixture.bike.getSelectedPlan()).isNull();
        assertThat(Path.of(fixture.config.io().deliveryCarriersRouted())).doesNotExist();
        assertThat(Path.of(fixture.config.io().supplyCarriersRouted())).doesNotExist();
    }

    @Test
    void impossibleJobCannotPublishAnIncompleteOfflineFile() throws Exception {
        var f = fixture("bike");
        var job = CarrierService.Builder.newInstance(Id.create("too-large", CarrierService.class),
                Id.createLinkId("cycle-stop")).setCapacityDemand(11).setServiceDuration(10).build();
        CarriersUtils.addService(f.bike, job);
        assertThatThrownBy(f.router::run).isInstanceOf(IllegalStateException.class);
        assertThat(f.bike.getSelectedPlan()).isNull();
        assertThat(Path.of(f.config.io().deliveryCarriersRouted())).doesNotExist();
        assertThat(Path.of(f.config.io().supplyCarriersRouted())).doesNotExist();
    }

    @Test void aLaterModeFailureRestoresAllEarlierPlansAndInferredMetadata() throws Exception {
        var f=fixture("bike");f.car.getAttributes().removeAttribute("carrierMode");
        CarriersUtils.addService(f.bike,CarrierService.Builder.newInstance(Id.create("oversize",CarrierService.class),
                Id.createLinkId("cycle-stop")).setCapacityDemand(11).build());
        var before=new java.util.LinkedHashMap<>(f.car.getAttributes().getAsMap());
        assertThatThrownBy(f.router::run).isInstanceOf(IllegalStateException.class);
        assertThat(f.car.getSelectedPlan()).isNull();assertThat(f.car.getPlans()).isEmpty();
        assertThat(f.car.getAttributes().getAsMap()).isEqualTo(before);
        assertThat(f.bike.getSelectedPlan()).isNull();
    }

    @Test void aLaterSupplySearchFailureDoesNotRetainDeliveryCandidates() throws Exception {
        var f=fixture("bike");
        var supply=carrier("supply","road-",f.car.getCarrierCapabilities().getCarrierVehicles().values().iterator().next().getType());
        CarriersUtils.addService(supply,CarrierService.Builder.newInstance(Id.create("supply-oversize",CarrierService.class),
                Id.createLinkId("road-stop")).setCapacityDemand(11).build());f.supply.addCarrier(supply);
        var before=new java.util.LinkedHashMap<>(f.car.getAttributes().getAsMap());
        assertThatThrownBy(f.router::run).isInstanceOf(IllegalStateException.class);
        assertThat(f.car.getSelectedPlan()).isNull();assertThat(f.bike.getSelectedPlan()).isNull();
        assertThat(f.car.getAttributes().getAsMap()).isEqualTo(before);
        assertThat(Path.of(f.config.io().deliveryCarriersRouted())).doesNotExist();
        assertThat(Path.of(f.config.io().supplyCarriersRouted())).doesNotExist();
    }

    @Test void anInvalidSecondOutputPreservesPriorCompletePlansAndFiles() throws Exception {
        var f=fixture("bike");f.router.run();
        var newSupply=carrier("new-supply","road-",f.car.getCarrierCapabilities().getCarrierVehicles().values().iterator().next().getType());
        f.supply.addCarrier(newSupply);
        var carPlan=f.car.getSelectedPlan();var bikePlan=f.bike.getSelectedPlan();
        var attributes=new java.util.LinkedHashMap<>(f.car.getAttributes().getAsMap());
        var delivery=Path.of(f.config.io().deliveryCarriersRouted());
        var supply=Path.of(f.config.io().supplyCarriersRouted());
        byte[] deliveryBytes=java.nio.file.Files.readAllBytes(delivery);
        byte[] metricsBytes=java.nio.file.Files.readAllBytes(Path.of(f.config.io().routingMetrics()));
        // A foreign/non-file destination is not ours to replace or delete.
        java.nio.file.Files.delete(supply);java.nio.file.Files.createDirectory(supply);
        var marker=supply.resolve("preserve");java.nio.file.Files.writeString(marker,"owned by caller");
        assertThatThrownBy(f.router::run).isInstanceOf(IllegalStateException.class);
        assertThat(f.car.getSelectedPlan()).isSameAs(carPlan);assertThat(f.bike.getSelectedPlan()).isSameAs(bikePlan);
        assertThat(f.car.getAttributes().getAsMap()).isEqualTo(attributes);
        assertThat(newSupply.getSelectedPlan()).isNull();
        assertThat(java.nio.file.Files.readAllBytes(delivery)).isEqualTo(deliveryBytes);
        assertThat(java.nio.file.Files.readAllBytes(Path.of(f.config.io().routingMetrics()))).isEqualTo(metricsBytes);
        assertThat(java.nio.file.Files.readString(marker)).isEqualTo("owned by caller");
    }
    @Test
    void initialSearchDoesNotWriteSkillsOntoTheSharedVehicleType() throws Exception {
        var f = fixture("bike");
        var type = f.bike.getCarrierCapabilities().getCarrierVehicles().values().iterator().next().getType();
        CarriersUtils.setSkills(type, Set.of("bike-skill"));
        var before = type.getAttributes().getAsMap().toString();
        f.router.run();
        assertThat(type.getAttributes().getAsMap().toString()).isEqualTo(before);
        assertThat(f.bike.getSelectedPlan()).isNotNull();
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(ThreadingType.class)
    void everyExecutionModeUsesTheConfiguredInitialBudget(ThreadingType threading) throws Exception {
        var f = fixture("bike");
        f.router.setThreadingType(threading);
        f.config.routing().setJspritIterations(2);
        f.router.run();
        for (var c : List.of(f.car,f.bike)) {
            assertThat(c.getSelectedPlan().getScheduledTours()).hasSize(1);
            assertThat(CarriersUtils.getJspritIterations(c)).isEqualTo(2);
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(ThreadingType.class)
    void aFailedModalGroupDoesNotPublishItsOtherCompletedCandidate(ThreadingType threading) throws Exception {
        var f = fixture("car");
        f.router.setThreadingType(threading);
        CarriersUtils.addService(f.bike,CarrierService.Builder.newInstance(Id.create("oversize",CarrierService.class),
                Id.createLinkId("cycle-stop")).setCapacityDemand(11).build());
        assertThatThrownBy(f.router::run).isInstanceOf(IllegalStateException.class);
        assertThat(f.car.getSelectedPlan()).isNull();
        assertThat(f.bike.getSelectedPlan()).isNull();
        assertThat(Path.of(f.config.io().deliveryCarriersRouted())).doesNotExist();
    }

    @Test
    void aLegacyCacheNeverReplacesCurrentJobsOrVehicles() throws Exception {
        String enabled=System.getProperty("hagrid.router.cache.enabled");
        String cache=System.getProperty("hagrid.router.cache.dir");
        String run=System.getProperty("hagrid.runId");
        try {
            System.setProperty("hagrid.router.cache.enabled","true");
            System.setProperty("hagrid.router.cache.dir",directory.resolve("legacy-cache").toString());
            System.setProperty("hagrid.runId","run");
            var first=fixture("car");
            first.router.run();
            var legacyDirectory=directory.resolve("legacy-cache");
            java.nio.file.Files.createDirectories(legacyDirectory);
            first.bike.getAttributes().putAttribute("cacheVersion","v1");
            var legacyFile=legacyDirectory.resolve("carrier_run_cycle-carrier.xml");
            new org.matsim.freight.carriers.CarrierPlanWriter(new Carriers(List.of(first.bike))).write(legacyFile.toString());
            byte[] oldFile=java.nio.file.Files.readAllBytes(legacyFile);
            var second=fixture("car");
            var id=Id.create("cycle-carrier-service",CarrierService.class);
            var current=CarrierService.Builder.newInstance(id,Id.createLinkId("cycle-stop"))
                    .setCapacityDemand(3).setServiceDuration(15)
                    .setServiceStartingTimeWindow(TimeWindow.newInstance(0,50000)).build();
            second.bike.getServices().put(id,current);
            var vehicle=second.bike.getCarrierCapabilities().getCarrierVehicles().values().iterator().next();
            second.router.run();
            assertThat(second.bike.getServices().get(id)).isSameAs(current);
            assertThat(second.bike.getServices().get(id).getCapacityDemand()).isEqualTo(3);
            assertThat(second.bike.getCarrierCapabilities().getCarrierVehicles().get(vehicle.getId())).isSameAs(vehicle);
            assertThat(second.bike.getSelectedPlan().getScheduledTours()).hasSize(1);
            assertThat(java.nio.file.Files.readAllBytes(legacyFile)).isEqualTo(oldFile);
        } finally {
            restoreProperty("hagrid.router.cache.enabled",enabled);
            restoreProperty("hagrid.router.cache.dir",cache);
            restoreProperty("hagrid.runId",run);
        }
    }

    private static void restoreProperty(String name,String value) {
        if(value==null)System.clearProperty(name);else System.setProperty(name,value);
    }
    @Test
    void aPreselectedIncompletePlanMustNotBypassInitialValidation() throws Exception {
        var f=fixture("car");
        var incomplete=new org.matsim.freight.carriers.CarrierPlan(new java.util.ArrayList<>());
        f.car.addPlan(incomplete);f.car.setSelectedPlan(incomplete);
        assertThatThrownBy(f.router::run).isInstanceOf(IllegalStateException.class);
        assertThat(f.car.getSelectedPlan()).isSameAs(incomplete);
        assertThat(Path.of(f.config.io().deliveryCarriersRouted())).doesNotExist();
    }
    private Fixture fixture(String mode) throws Exception {
        var scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        var network = scenario.getNetwork();
        loop(network, "road-", "car");
        loop(network, "cycle-", mode);
        var road = type("road-type", "car", 10);
        var cycle = type("cycle-type", mode, 5);
        var types = new CarrierVehicleTypes();
        types.getVehicleTypes().put(road.getId(), road);
        types.getVehicleTypes().put(cycle.getId(), cycle);
        var car = carrier("road-carrier", "road-", road);
        var bike = carrier("cycle-carrier", "cycle-", cycle);
        var carriers = new Carriers(List.of(car, bike));
        var supply = new Carriers();
        var legacyCar = NetworkUtils.createNetwork();
        new org.matsim.core.network.algorithms.TransportModeNetworkFilter(network).filter(legacyCar, Set.of("car"));
        scenario.addScenarioElement("carFilteredNetwork", legacyCar);
        scenario.addScenarioElement("carrierVehicleTypes", types);
        scenario.addScenarioElement("carriers", carriers);
        scenario.addScenarioElement("supply", supply);
        var config = new HagridConfig();
        set(config, "hagridPaths", new HagridPaths(directory));
        config.setSimulationDate(LocalDate.of(2025, 5, 13));
        config.setCarrierRoutingCacheEnabled(false);
        config.routing().setJspritIterations(1);
        var router = new CarrierRouter();
        set(router, "scenario", scenario);
        set(router, "hagridConfig", config);
        router.setThreadingType(ThreadingType.SINGLE_THREAD);
        return new Fixture(router, config, network, types, car, bike, supply);
    }

    private static void loop(Network network, String prefix, String mode) {
        for (String node : List.of("a", "b", "c")) NetworkUtils.createAndAddNode(network, Id.createNodeId(prefix + node), new Coord(node.charAt(0) * 100, prefix.equals("road-") ? 0 : 100));
        link(network, prefix, "home", "a", "b", mode);
        link(network, prefix, "stop", "b", "c", mode);
        link(network, prefix, "back", "c", "a", mode);
    }

    private static void link(Network network, String prefix, String id, String from, String to, String mode) {
        var link = NetworkUtils.createAndAddLink(network, Id.createLinkId(prefix + id),
                network.getNodes().get(Id.createNodeId(prefix + from)), network.getNodes().get(Id.createNodeId(prefix + to)), 100, 10, 1000, 1);
        link.setAllowedModes(Set.of(mode));
    }

    private static VehicleType type(String id, String mode, double speed) {
        var type = VehicleUtils.createVehicleType(Id.create(id, VehicleType.class), mode);
        type.setMaximumVelocity(speed);
        type.getCapacity().setOther(10);
        type.getCostInformation().setFixedCost(1.0).setCostsPerMeter(0.01).setCostsPerSecond(1.0);
        return type;
    }

    private static Carrier carrier(String id, String prefix, VehicleType type) {
        var carrier = CarriersUtils.createCarrier(Id.create(id, Carrier.class));
        CarriersUtils.setCarrierMode(carrier, type.getNetworkMode());
        var vehicle = CarrierVehicle.Builder.newInstance(Id.createVehicleId(id + "-vehicle"), Id.createLinkId(prefix + "home"), type)
                .setEarliestStart(0).setLatestEnd(50000).build();
        carrier.setCarrierCapabilities(CarrierCapabilities.Builder.newInstance().addVehicle(vehicle).setFleetSize(CarrierCapabilities.FleetSize.INFINITE).build());
        CarriersUtils.addService(carrier, CarrierService.Builder.newInstance(Id.create(id + "-service", CarrierService.class), Id.createLinkId(prefix + "stop"))
                .setCapacityDemand(1).setServiceDuration(10).setServiceStartingTimeWindow(TimeWindow.newInstance(0, 50000)).build());
        return carrier;
    }

    private static void set(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(ThreadingType.class)
    void allExecutionLabelsProduceTheSameCompleteInitialDiagnostics(ThreadingType threading) throws Exception {
        var f=fixture("bike");f.router.setThreadingType(threading);f.router.run();
        var metrics=java.nio.file.Files.readAllLines(Path.of(f.config.io().routingMetrics()));
        assertThat(metrics).hasSize(3);
        assertThat(metrics.subList(1,metrics.size())).allSatisfy(row->assertThat(row).contains(","+threading+","));
        var status=java.nio.file.Files.readAllLines(Path.of(f.config.io().routingStatus()));
        assertThat(status.stream().filter(row->row.contains(",START,"))).hasSize(2);
        assertThat(status.stream().filter(row->row.contains(",DONE,"))).hasSize(2);
    }
    @ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(ThreadingType.class)
    void failedInitialGroupDoesNotWriteWorkerMetadata(ThreadingType threading) throws Exception {
        var f=fixture("car");f.router.setThreadingType(threading);
        CarriersUtils.addService(f.bike,CarrierService.Builder.newInstance(Id.create("oversize",CarrierService.class),
                Id.createLinkId("cycle-stop")).setCapacityDemand(11).build());
        assertThatThrownBy(f.router::run).isInstanceOf(IllegalStateException.class);
        assertThat(f.car.getAttributes().getAttribute("totalDeliveries")).isNull();
        assertThat(f.bike.getAttributes().getAttribute("totalDeliveries")).isNull();
        assertThat(f.car.getSelectedPlan()).isNull();assertThat(f.bike.getSelectedPlan()).isNull();
    }
    @Test void configuredShortInitialSolverCapCannotPublishAnOverlongTour() throws Exception {
        var f=fixture("car");f.config.routing().setSolverMaxRouteDurationSeconds(1);
        var costs=hagrid.core.routing.NativeModalTransportCosts.create(f.network,f.types,"car");
        try(var router=new Router(ThreadingType.COMPLETABLE_FUTURE,null,f.config,f.types)) {
            assertThatThrownBy(()->router.routeCarriers(new Carriers(List.of(f.car)),costs,f.network,"delivery"))
                .isInstanceOf(RuntimeException.class);
            assertThat(f.car.getSelectedPlan()).isNull();
        }
    }

    @Test void configuredPolicyReachesTheActualInitialPipelineAndUsesAvailableWorkers() throws Exception {
        var f=fixture("car");
        var policy=directory.resolve("admission.json");
        hagrid.core.routing.CarrierAdmissionTestPolicies.write(policy,List.of(f.car,f.bike));
        var file=directory.resolve("admission.properties");
        java.nio.file.Files.writeString(file,"resources.maxActiveSolvers=2\nresources.internalSolverThreads=1\nresources.carrierAdmissionPolicyFile=admission.json\n");
        hagrid.core.config.HagridConfigFile.load(file).applyTo(f.config);
        f.router.run();
        var status=java.nio.file.Files.readAllLines(Path.of(f.config.io().routingStatus()));
        var threads=status.stream().filter(line->line.contains(",START,")).map(line->line.split(",",-1)[9]).distinct().toList();
        assertThat(threads).hasSize(Math.min(2,Runtime.getRuntime().availableProcessors()));
        assertThat(f.car.getSelectedPlan()).isNotNull();assertThat(f.bike.getSelectedPlan()).isNotNull();
    }

    @Test void initialPoolIsLazyReusedAndClosedByTheOwningRouter() throws Exception {
        var f=fixture("car");var costs=hagrid.core.routing.NativeModalTransportCosts.create(f.network,f.types,"car");
        var executor=new hagrid.core.routing.CarrierBatchExecutor(2,100,java.time.Duration.ofSeconds(2));
        try(var router=new Router(ThreadingType.COMPLETABLE_FUTURE,null,f.config,f.types,executor,c->50)){
            router.routeCarriers(new Carriers(),costs,f.network,"delivery");
            assertThat(executor.createdWorkers()).isZero();
            router.routeCarriers(new Carriers(List.of(f.car,f.bike)),costs,f.network,"delivery");
            assertThat(executor.createdWorkers()).isEqualTo(2);assertThat(executor.reservedBytes()).isZero();
            var next=carrier("later","road-",f.car.getCarrierCapabilities().getCarrierVehicles().values().iterator().next().getType());
            router.routeCarriers(new Carriers(List.of(next)),costs,f.network,"supply");
            assertThat(executor.createdWorkers()).isEqualTo(2);
            assertThat(router.getMetrics()).hasSize(3);
        }
        assertThatThrownBy(()->executor.run(List.of(),d->d,r->{})).hasMessageContaining("unavailable");
    }
    @Test void initialRouterHonorsTheDemandConfigurationCapEvenWithALargerSuppliedPool() throws Exception {
        var f=fixture("car");f.config.resources().setInternalSolverThreads(2);f.config.resources().setMaxActiveSolvers(1);
        var costs=hagrid.core.routing.NativeModalTransportCosts.create(f.network,f.types,"car");
        var executor=new hagrid.core.routing.CarrierBatchExecutor(2,100,java.time.Duration.ofSeconds(2));
        try(var router=new Router(ThreadingType.COMPLETABLE_FUTURE,null,f.config,f.types,executor,c->50)) {
            router.routeCarriers(new Carriers(List.of(f.car,f.bike)),costs,f.network,"delivery");
            assertThat(executor.createdWorkers()).isEqualTo(1);assertThat(executor.reservedBytes()).isZero();
            assertThat(router.getMetrics()).hasSize(2);
        }
    }
    @Test void initialOversizeReservationFailsBeforeSolverOrCanonicalMetadata() throws Exception {
        var f=fixture("car");var costs=hagrid.core.routing.NativeModalTransportCosts.create(f.network,f.types,"car");
        var executor=new hagrid.core.routing.CarrierBatchExecutor(2,100,java.time.Duration.ofSeconds(2));
        try(var router=new Router(ThreadingType.REACTOR,null,f.config,f.types,executor,c->101)){
            assertThatThrownBy(()->router.routeCarriers(new Carriers(List.of(f.car)),costs,f.network,"delivery"))
                    .hasMessageContaining("budget");
            assertThat(executor.createdWorkers()).isZero();assertThat(router.getMetrics()).isEmpty();
            assertThat(f.car.getSelectedPlan()).isNull();
            assertThat(f.car.getAttributes().getAttribute("totalDeliveries")).isNull();
        }
    }
    @Test void shipmentDemandIsPresentInInitialMetricsAndCanonicalDeliveryTotal() throws Exception {
        var f=fixture("bike");
        var shipment=CarrierShipment.Builder.newInstance(Id.create("shipment",CarrierShipment.class),
                Id.createLinkId("road-stop"),Id.createLinkId("road-home"),3).setPickupServiceTime(5).setDeliveryServiceTime(5).build();
        f.car.getServices().clear();CarriersUtils.addShipment(f.car,shipment);f.router.run();
        var rows=java.nio.file.Files.readAllLines(Path.of(f.config.io().routingMetrics()));
        var row=rows.stream().filter(value->value.startsWith(f.car.getId()+",")).findFirst().orElseThrow().split(",");
        assertThat(row[2]).isEqualTo("0");assertThat(row[3]).isEqualTo("1");assertThat(row[4]).isEqualTo("3");
        assertThat(f.car.getAttributes().getAttribute("totalDeliveries")).isEqualTo(3);
        assertThat(f.car.getSelectedPlan().getScheduledTours()).isNotEmpty();
    }
    @Test void unsupportedMixedJobKindsAreRejectedBeforePublishingInferredModes() throws Exception {
        var f=fixture("bike");f.bike.getAttributes().removeAttribute("carrierMode");
        CarriersUtils.addShipment(f.car,CarrierShipment.Builder.newInstance(Id.create("mixed-shipment",CarrierShipment.class),
                Id.createLinkId("road-stop"),Id.createLinkId("road-home"),1).build());
        assertThatThrownBy(f.router::run).hasStackTraceContaining("mixed");
        assertThat(f.bike.getAttributes().getAttribute("carrierMode")).isNull();
        assertThat(f.car.getSelectedPlan()).isNull();assertThat(f.bike.getSelectedPlan()).isNull();
    }
    private record Fixture(CarrierRouter router, HagridConfig config, Network network, CarrierVehicleTypes types,
                           Carrier car, Carrier bike, Carriers supply) {}
}
