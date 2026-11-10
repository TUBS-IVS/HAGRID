package hagrid.core.simulation;
import hagrid.core.HagridConfig;
import hagrid.core.config.*;
import hagrid.core.routing.*;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.*;
import org.matsim.core.network.NetworkUtils;
import org.matsim.freight.carriers.*;
import org.matsim.vehicles.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
class ReplanningCoordinatorTest {
    @Test void seventeenCarriersUseTheActualPreviousIterationOfEachGroup(){try(var f=new Fixture(17);var coordinator=new ReplanningCoordinator(f.config,42)){
        var captures=new AtomicInteger();
        var second=context(f,25,20);
        coordinator.runGroup(24,f.carriers,()->{throw new AssertionError("Before start");});
        coordinator.runGroup(25,f.carriers,()->{captures.incrementAndGet();return f.context;});
        assertThat(coordinator.statuses().values().stream().filter(s->s==ReplanningCoordinator.Status.COMPLETED)).hasSize(16);
        coordinator.runGroup(26,f.carriers,()->{captures.incrementAndGet();return second;});
        coordinator.runGroup(27,f.carriers,()->{throw new AssertionError("Empty group");});
        assertThat(captures).hasValue(2);assertThat(coordinator.statuses().values()).containsOnly(ReplanningCoordinator.Status.COMPLETED);
        assertThat(f.carriers.getCarriers().values()).allSatisfy(c->{
            boolean last=c.getId().toString().equals("lsp-16");
            assertThat(c.getAttributes().getAttribute("hadFirstReplanning")).isEqualTo(true);
            assertThat(c.getAttributes().getAttribute("routingContextFingerprint")).isEqualTo(last?second.fingerprint():f.context.fingerprint());
            assertThat(c.getAttributes().getAttribute("routingMeasurementIteration")).isEqualTo(last?25:24);
            assertThat(c.getSelectedPlan().getScheduledTours()).hasSize(1);
            var leg=(Tour.Leg)c.getSelectedPlan().getScheduledTours().iterator().next().getTour().getTourElements().getFirst();
            assertThat(leg.getExpectedTransportTime()).isEqualTo(last?20.0:10.0);
        });
        assertThat(second.fingerprint()).isNotEqualTo(f.context.fingerprint());
        assertThatThrownBy(f.context::requireOpen).isInstanceOf(IllegalStateException.class);
    }}
    @Test void staleSecondContextCannotRelabelOrPublishTheNextGroup(){
        try(var f=new Fixture(2,Map.of("optimization.carriersPerGroup","1","optimization.replanningIterations","1"));
            var coordinator=new ReplanningCoordinator(f.config,42)){
            coordinator.runGroup(25,f.carriers,()->f.context);
            var first=f.carriers.getCarriers().get(Id.create("lsp-00",Carrier.class));
            var second=f.carriers.getCarriers().get(Id.create("lsp-01",Carrier.class));
            var prior=first.getSelectedPlan();var untouched=second.getSelectedPlan();
            var stale=context(f,24,20);
            assertThatThrownBy(()->coordinator.runGroup(26,f.carriers,()->stale))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("25");
            assertThat(first.getSelectedPlan()).isSameAs(prior);
            assertThat(second.getSelectedPlan()).isSameAs(untouched);
            assertThat(second.getAttributes().getAttribute("routingMeasurementIteration")).isNull();
            assertThatThrownBy(stale::requireOpen).isInstanceOf(IllegalStateException.class);
            assertThat(coordinator.finalMetrics()).hasSize(1);
        }
    }
    @Test void equalValuesStillUseDifferentCompletedIterationContextsAndRejectDuplicateIteration(){
        try(var f=new Fixture(3,Map.of("optimization.carriersPerGroup","1","optimization.replanningIterations","1"));
            var coordinator=new ReplanningCoordinator(f.config,42)){
            coordinator.runGroup(25,f.carriers,()->f.context);
            var second=context(f,25,10);
            coordinator.runGroup(26,f.carriers,()->second);
            var first=f.carriers.getCarriers().get(Id.create("lsp-00",Carrier.class));
            var next=f.carriers.getCarriers().get(Id.create("lsp-01",Carrier.class));
            assertThat(first.getAttributes().getAttribute("routingContextFingerprint"))
                    .isNotEqualTo(next.getAttributes().getAttribute("routingContextFingerprint"));
            assertThat(next.getAttributes().getAttribute("routingMeasurementIteration")).isEqualTo(25);
            assertThatThrownBy(()->coordinator.runGroup(26,f.carriers,()->{throw new AssertionError("Duplicate capture");}))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("iteration");
        }
    }
    @Test void finalSelectionsAreRecheckedUnderTheirOwnArchivedMeasurementContext(){
        try(var f=new Fixture(2,Map.of("optimization.carriersPerGroup","1","optimization.replanningIterations","1"));
            var coordinator=new ReplanningCoordinator(f.config,42)){
            coordinator.runGroup(25,f.carriers,()->f.context);
            coordinator.runGroup(26,f.carriers,()->context(f,25,20));
            var first=f.carriers.getCarriers().get(Id.create("lsp-00",Carrier.class));
            var second=f.carriers.getCarriers().get(Id.create("lsp-01",Carrier.class));
            var metrics=coordinator.finalMetrics();
            assertThat(metrics).hasSize(2);
            assertThat(metrics.get(0).execution.orElseThrow().finalCost()).isEqualTo(first.getAttributes().getAttribute("validatedRoutingCost"));
            assertThat(metrics.get(1).execution.orElseThrow().finalCost()).isEqualTo(second.getAttributes().getAttribute("validatedRoutingCost"));
            assertThat(first.getAttributes().getAttribute("validatedRoutingCost"))
                    .isNotEqualTo(second.getAttributes().getAttribute("validatedRoutingCost"));
            var empty=new CarrierPlan(new ArrayList<>());first.addPlan(empty);first.setSelectedPlan(empty);
            assertThat(coordinator.finalMetrics().get(0).execution.orElseThrow().validationStatus()).isEqualTo("INVALID_FINAL");
            assertThat(coordinator.finalMetrics().get(1).execution.orElseThrow().validationStatus()).isEqualTo("VALIDATED_FINAL");
        }
    }
    @Test void skippedGroupIterationCannotSilentlyChangeTheFixedPass(){
        try(var f=new Fixture(1);var coordinator=new ReplanningCoordinator(f.config,42)){
            assertThatThrownBy(()->coordinator.runGroup(26,f.carriers,()->f.context))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("iteration");
            assertThat(f.carriers.getCarriers().values()).allSatisfy(c->
                    assertThat(c.getAttributes().getAttribute("hadFirstReplanning")).isNull());
        }
    }
    @Test void failedGroupPublishesNothingAndCannotAdvance(){try(var f=new Fixture(2);var coordinator=new ReplanningCoordinator(f.config,42)){
        var bad=f.carriers.getCarriers().values().iterator().next();var old=bad.getServices().values().iterator().next();
        bad.getServices().put(old.getId(),CarrierService.Builder.newInstance(old.getId(),old.getServiceLinkId()).setCapacityDemand(11).build());
        var before=new HashMap<Carrier,CarrierPlan>();f.carriers.getCarriers().values().forEach(c->before.put(c,c.getSelectedPlan()));
        assertThatThrownBy(()->coordinator.runGroup(25,f.carriers,()->f.context)).hasMessageContaining("Invalid");
        assertThat(f.carriers.getCarriers().values()).allSatisfy(c->{assertThat(c.getSelectedPlan()).isSameAs(before.get(c));assertThat(c.getAttributes().getAttribute("hadFirstReplanning")).isNull();});
        assertThatThrownBy(()->coordinator.runGroup(26,f.carriers,()->f.context)).hasMessageContaining("failed");
    }}
    @Test void anEmptyGroupNeverCapturesOrSearches(){
        try(var f=new Fixture(0);var coordinator=new ReplanningCoordinator(f.config,42)){
            coordinator.runGroup(25,f.carriers,()->{throw new AssertionError("Empty group snapshot");});
            assertThat(coordinator.statuses()).isEmpty();
        }
    }    @Test void boundedWorkersMatchSerialNativeResultsAndPublishOnlyCompleteGroups() {
        try(var serial=new Fixture(3);var parallel=new Fixture(3);
            var serialCoordinator=new ReplanningCoordinator(serial.config,42);
            var executor=new CarrierBatchExecutor(2,150,java.time.Duration.ofSeconds(2));
            var coordinator=new ReplanningCoordinator(parallel.config,42,null,executor,c->50)) {
            serialCoordinator.runGroup(25,serial.carriers,()->serial.context);
            coordinator.runGroup(25,parallel.carriers,()->parallel.context);
            assertThat(executor.createdWorkers()).isEqualTo(2);
            assertThat(executor.reservedBytes()).isZero();
            assertThat(coordinator.statuses()).isEqualTo(serialCoordinator.statuses());
            for(var c:parallel.carriers.getCarriers().values()) {
                var expected=serial.carriers.getCarriers().get(c.getId());
                assertThat(c.getAttributes().getAttribute("validatedRoutingCost"))
                        .isEqualTo(expected.getAttributes().getAttribute("validatedRoutingCost"));
                assertThat(c.getSelectedPlan().getScheduledTours()).hasSize(1);
                assertThat(CarriersUtils.getJspritIterations(c)).isEqualTo(CarriersUtils.getJspritIterations(expected));
            }
        }
    }
    @Test void configuredPolicyReachesDefaultReplanningOwnerWithNativeResults(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        var policy=directory.resolve("admission.json");
        var threads=java.lang.management.ManagementFactory.getThreadMXBean();
        var before=java.util.Arrays.stream(threads.getAllThreadIds()).boxed().collect(java.util.stream.Collectors.toSet());
        try(var f=new Fixture(3,Map.of("optimization.replanningIterations","1","resources.maxActiveSolvers","2",
                "resources.carrierAdmissionPolicyFile",policy.toString()))) {
            CarrierAdmissionTestPolicies.write(policy,f.carriers.getCarriers().values());
            try(var coordinator=new ReplanningCoordinator(f.config,42)) {
                coordinator.runGroup(25,f.carriers,()->f.context);
                var created=java.util.Arrays.stream(threads.getThreadInfo(threads.getAllThreadIds()))
                    .filter(info->info!=null&&!before.contains(info.getThreadId())&&info.getThreadName().startsWith("hagrid-carrier-")).toList();
                assertThat(created).hasSize(Math.min(2,Runtime.getRuntime().availableProcessors()));
                assertThat(coordinator.statuses().values()).containsOnly(ReplanningCoordinator.Status.COMPLETED);
                assertThat(f.carriers.getCarriers().values()).allSatisfy(c->assertThat(c.getSelectedPlan().getScheduledTours()).hasSize(1));
            }
        }
    }

    @Test void growingRunBasisBlocksNextNativeGroupAndKeepsEarlierPublishedPlans() {
        var shared=new java.util.concurrent.atomic.AtomicLong(400);
        var samples=new AtomicInteger();
        var budget=new CarrierRunBudget(()->{
            samples.incrementAndGet();
            return new CarrierRunBudget.State(1000,shared.get(),100,2000,1000,200);
        });
        try(var f=new Fixture(4,Map.of("optimization.carriersPerGroup","2","optimization.replanningIterations","1"));
            var executor=new CarrierBatchExecutor(2,budget,java.time.Duration.ofSeconds(2));
            var coordinator=new ReplanningCoordinator(f.config,42,null,executor,c->200)){
            var old=new HashMap<Carrier,CarrierPlan>();
            f.carriers.getCarriers().values().forEach(c->old.put(c,c.getSelectedPlan()));
            coordinator.runGroup(25,f.carriers,()->f.context);
            var first=new HashMap<Carrier,CarrierPlan>();
            for(var c:f.carriers.getCarriers().values()){
                if(c.getId().toString().compareTo("lsp-02")<0){
                    assertThat(c.getSelectedPlan().getScheduledTours()).hasSize(1);
                    assertThat(PlanValidator.validate(c,c.getSelectedPlan(),new RoutingRequest(c,f.context,"car"),f.config).valid()).isTrue();
                    first.put(c,c.getSelectedPlan());
                }else assertThat(c.getSelectedPlan()).isSameAs(old.get(c));
            }
            shared.set(600); // Supplied bytes are contract fixtures, not measured native heap.
            assertThatThrownBy(()->coordinator.runGroup(26,f.carriers,()->context(f,25,20)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Staged");
            assertThat(samples).hasValue(2);
            assertThat(executor.reservedBytes()).isZero();
            assertThat(coordinator.statuses()).containsEntry("lsp-00",ReplanningCoordinator.Status.COMPLETED)
                    .containsEntry("lsp-01",ReplanningCoordinator.Status.COMPLETED)
                    .containsEntry("lsp-02",ReplanningCoordinator.Status.FAILED)
                    .containsEntry("lsp-03",ReplanningCoordinator.Status.FAILED);
            for(var c:f.carriers.getCarriers().values())
                assertThat(c.getSelectedPlan()).isSameAs(first.getOrDefault(c,old.get(c)));
        }
    }
    @Test void centralSolverLimitCapsAnExplicitlyLargerPool() {
        try (var f=new Fixture(3,Map.of("optimization.replanningIterations","1","resources.maxActiveSolvers","1"));
             var executor=new CarrierBatchExecutor(2,150,java.time.Duration.ofSeconds(2));
             var coordinator=new ReplanningCoordinator(f.config,42,null,executor,c->50)) {
            coordinator.runGroup(25,f.carriers,()->f.context);
            assertThat(executor.createdWorkers()).isEqualTo(1);
            assertThat(executor.reservedBytes()).isZero();
            assertThat(coordinator.statuses().values()).containsOnly(ReplanningCoordinator.Status.COMPLETED);
            assertThat(f.carriers.getCarriers().values()).allSatisfy(c ->
                    assertThat(c.getSelectedPlan().getScheduledTours()).hasSize(1));
        }
    }
    @Test void completeStagedGroupMustFitBudgetBeforeAnySearch() {
        try (var f=new Fixture(3); var executor=new CarrierBatchExecutor(2,100,java.time.Duration.ofSeconds(2));
             var coordinator=new ReplanningCoordinator(f.config,42,null,executor,c->50)) {
            var before=new HashMap<Carrier,CarrierPlan>();
            f.carriers.getCarriers().values().forEach(c->before.put(c,c.getSelectedPlan()));
            assertThatThrownBy(()->coordinator.runGroup(25,f.carriers,()->f.context))
                    .hasMessageContaining("group").hasMessageContaining("budget");
            assertThat(executor.createdWorkers()).isZero();
            assertThat(executor.reservedBytes()).isZero();
            f.carriers.getCarriers().values().forEach(c->assertThat(c.getSelectedPlan()).isSameAs(before.get(c)));
        }
    }    @Test void reservationPreflightRejectsWholeGroupBeforeAnySearchOrPublication() {
        try(var f=new Fixture(2);var executor=new CarrierBatchExecutor(2,100,java.time.Duration.ofSeconds(2));
            var coordinator=new ReplanningCoordinator(f.config,42,null,executor,c->c.getId().toString().endsWith("01")?101:10)) {
            var before=new HashMap<Carrier,CarrierPlan>();
            f.carriers.getCarriers().values().forEach(c->before.put(c,c.getSelectedPlan()));
            assertThatThrownBy(()->coordinator.runGroup(25,f.carriers,()->f.context))
                    .hasMessageContaining("lsp-01").hasMessageContaining("budget");
            assertThat(executor.createdWorkers()).isZero();
            assertThat(f.carriers.getCarriers().values()).allSatisfy(c->{
                assertThat(c.getSelectedPlan()).isSameAs(before.get(c));
                assertThat(c.getAttributes().getAttribute("hadFirstReplanning")).isNull();
            });
        }
    }
    @Test void failedParallelGroupDoesNotPublishEarlierValidResults() {
        try(var f=new Fixture(2);var executor=new CarrierBatchExecutor(2,100,java.time.Duration.ofSeconds(2));
            var coordinator=new ReplanningCoordinator(f.config,42,null,executor,c->50)) {
            var bad=f.carriers.getCarriers().values().stream().filter(c->c.getId().toString().endsWith("01")).findFirst().orElseThrow();
            var old=bad.getServices().values().iterator().next();
            bad.getServices().put(old.getId(),CarrierService.Builder.newInstance(old.getId(),old.getServiceLinkId()).setCapacityDemand(11).build());
            var before=new HashMap<Carrier,CarrierPlan>();f.carriers.getCarriers().values().forEach(c->before.put(c,c.getSelectedPlan()));
            assertThatThrownBy(()->coordinator.runGroup(25,f.carriers,()->f.context)).hasMessageContaining("Invalid");
            assertThat(executor.reservedBytes()).isZero();
            assertThat(coordinator.statuses().values()).containsOnly(ReplanningCoordinator.Status.FAILED);
            assertThat(f.carriers.getCarriers().values()).allSatisfy(c->{
                assertThat(c.getSelectedPlan()).isSameAs(before.get(c));
                assertThat(c.getAttributes().getAttribute("hadFirstReplanning")).isNull();
            });
        }
    }
    @Test void callerCancellationIsReportedSeparatelyAndPublishesNothing() {
        try(var f=new Fixture(2);var coordinator=new ReplanningCoordinator(f.config,42)) {
            var before=new HashMap<Carrier,CarrierPlan>();f.carriers.getCarriers().values().forEach(c->before.put(c,c.getSelectedPlan()));
            Thread.currentThread().interrupt();
            try {
                assertThatThrownBy(()->coordinator.runGroup(25,f.carriers,()->f.context))
                        .isInstanceOf(java.util.concurrent.CancellationException.class);
                assertThat(coordinator.statuses().values()).containsOnly(ReplanningCoordinator.Status.CANCELLED);
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
                assertThat(f.carriers.getCarriers().values()).allSatisfy(c->assertThat(c.getSelectedPlan()).isSameAs(before.get(c)));
            } finally {Thread.interrupted();}
        }
    }
    @Test void finalReportReplaysCurrentSelectedRoadAndReadsItsMatSimScore() {
        try (var f = new Fixture(1); var coordinator = new ReplanningCoordinator(f.config, 42)) {
            coordinator.runGroup(25, f.carriers, () -> f.context);
            var carrier = f.carriers.getCarriers().values().iterator().next();
            double oldCost = (Double) carrier.getAttributes().getAttribute("validatedRoutingCost");
            var plan = carrier.getSelectedPlan();
            var leg = (Tour.Leg) plan.getScheduledTours().iterator().next().getTour().getTourElements().getFirst();
            var road = (org.matsim.core.population.routes.NetworkRoute) leg.getRoute();
            road.setLinkIds(road.getStartLinkId(), List.of(Id.createLinkId("stop"), Id.createLinkId("back"), Id.createLinkId("depot")), road.getEndLinkId());
            plan.setScore(-123.5);
            var outcome = coordinator.finalMetrics().getFirst().execution.orElseThrow();
            var validation = PlanValidator.validate(carrier, plan, new RoutingRequest(carrier, f.context, "car"), f.config);
            assertThat(validation.valid()).isTrue();
            assertThat(outcome.validationStatus()).isEqualTo("VALIDATED_FINAL");
            assertThat(outcome.finalCost()).isEqualTo(validation.finalCost()).isGreaterThan(oldCost);
            assertThat(outcome.matSimScore()).isEqualTo(-123.5);
            assertThat(carrier.getAttributes().getAttribute("validatedRoutingCost")).isEqualTo(oldCost);
        }
    }
    @Test void invalidCurrentSelectionCannotInheritAnEarlierValidationCertificate() {
        try (var f = new Fixture(1); var coordinator = new ReplanningCoordinator(f.config, 42)) {
            coordinator.runGroup(25, f.carriers, () -> f.context);
            var carrier = f.carriers.getCarriers().values().iterator().next();
            var empty = new CarrierPlan(new ArrayList<>());
            carrier.addPlan(empty);
            carrier.setSelectedPlan(empty);
            var outcome = coordinator.finalMetrics().getFirst().execution.orElseThrow();
            assertThat(outcome.validationStatus()).isEqualTo("INVALID_FINAL");
            assertThat(outcome.finalCost()).isInfinite();
            assertThat(outcome.matSimScore()).isNull();
        }
    }
    static RoutingContext context(Fixture f,int iteration,double seconds){
        return RoutingContext.create(f.network,f.types,TravelTimeSnapshot.capture(f.network,Map.of("car",(l,t,p,v)->seconds),Map.of(),iteration,1000,60),f.config);
    }
    static final class Fixture implements AutoCloseable {
        final org.matsim.api.core.v01.network.Network network=NetworkUtils.createNetwork();
        final CarrierVehicleTypes types=new CarrierVehicleTypes();final Carriers carriers=new Carriers();
        final ResolvedHagridConfig config;final RoutingContext context;
        Fixture(int count){this(count,Map.of("optimization.replanningIterations","1"));}
        Fixture(int count,Map<String,String> settings){
            for(int i=0;i<3;i++)network.addNode(network.getFactory().createNode(Id.createNodeId("n"+i),new Coord(i*100,0)));
            String[] ids={"depot","stop","back"};for(int i=0;i<3;i++){
                var l=network.getFactory().createLink(Id.createLinkId(ids[i]),network.getNodes().get(Id.createNodeId("n"+i)),network.getNodes().get(Id.createNodeId("n"+((i+1)%3))));
                l.setLength(100);l.setFreespeed(10);l.setCapacity(36000);l.setNumberOfLanes(1);l.setAllowedModes(Set.of("car"));network.addLink(l);
            }
            var type=VehicleUtils.createVehicleType(Id.create("van",VehicleType.class),"car");type.setMaximumVelocity(10);type.getCapacity().setOther(10);
            type.getCostInformation().setFixedCost(1.0).setCostsPerMeter(0.01).setCostsPerSecond(1.0);types.getVehicleTypes().put(type.getId(),type);
            for(int i=count-1;i>=0;i--){
                var c=CarriersUtils.createCarrier(Id.create(String.format("lsp-%02d",i),Carrier.class));
                var v=CarrierVehicle.Builder.newInstance(Id.createVehicleId("v"+i),Id.createLinkId("depot"),type).setEarliestStart(0).setLatestEnd(500).build();
                CarriersUtils.addCarrierVehicle(c,v);CarriersUtils.setCarrierMode(c,"car");
                CarriersUtils.addService(c,CarrierService.Builder.newInstance(Id.create("job-"+i,CarrierService.class),Id.createLinkId("stop"))
                        .setCapacityDemand(1).setServiceDuration(1).setServiceStartingTimeWindow(TimeWindow.newInstance(0,400)).build());
                c.addPlan(new CarrierPlan(new ArrayList<>()));carriers.addCarrier(c);
            }
            config=HagridConfigResolver.resolve(new HagridConfig(),HagridConfigResolver.hannoverTarget(),HagridConfigResolver.Overrides.of(settings),Map.of());
            context=RoutingContext.create(network,types,TravelTimeSnapshot.capture(network,Map.of(),Map.of("car",(l,t,p,v)->l.getLength()/10),24,1000,60),config);
        }
        public void close(){context.close();}
    }
}
