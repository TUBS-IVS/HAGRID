package hagrid.core.routing;

import hagrid.core.HagridConfig;
import hagrid.core.config.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.freight.carriers.*;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class OptimizationResultStoreTest {
    @TempDir Path directory;
    private OptimizationResultStore store() {
        return new OptimizationResultStore(directory,()->Optional.of("test-code"));
    }
    @Test void publishingPlanningIterationDoesNotInvalidateTheCompletedSearchCache(){
        try(var f=new OptimizationFixture()){
            var job=f.service("a",1,0,900);var store=store();
            store.save(f.carrier,f.result(f.plan(0,job),1),f.request,f.config,42);
            assertThat(store.load(f.carrier,f.request,f.config,42)).isPresent();
            f.carrier.getAttributes().putAttribute("routingPlanningIteration",25);
            var loaded=store.load(f.carrier,f.request,f.config,42);
            assertThat(loaded).isPresent();
            assertThat(loaded.orElseThrow().contextFingerprint()).isEqualTo(f.context.fingerprint());
            assertThat(loaded.orElseThrow().diagnostics()).isEmpty();
            assertThat(f.carrier.getAttributes().getAttribute("routingPlanningIteration")).isEqualTo(25);
        }
    }
    @Test void unsupportedOptionalMetadataDisablesResumeWithoutAnIdentityKey() {
        try(var f=new OptimizationFixture()) {
            var job=f.service("a",1,0,900);var store=store();
            store.save(f.carrier,f.result(f.plan(0,job),1),f.request,f.config,42);
            var metadata=new Object();f.carrier.getAttributes().putAttribute("externalMetadata",metadata);
            assertThat(store.load(f.carrier,f.request,f.config,42)).isEmpty();
            assertThat(f.carrier.getAttributes().getAttribute("externalMetadata")).isSameAs(metadata);
            try(var files=Files.list(directory)){assertThat(files.toList()).hasSize(1);}
            catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}
        }
    }
    @Test void changedSelectedPlanDoesNotInvalidateAnIndependentFixedBudgetSearch() {
        try (var f=new OptimizationFixture()) {
            var a=f.service("a",1,0,900);var b=f.service("b",1,0,900);
            var old=f.plan(0,a,b);f.carrier.addPlan(old);f.carrier.setSelectedPlan(old);
            var config=HagridConfigResolver.resolve(new HagridConfig(),HagridConfigResolver.hannoverTarget(),
                    HagridConfigResolver.Overrides.of(Map.of("optimization.warmStartEnabled","false")),Map.of());
            try (var context=RoutingContext.create(f.context.network(),f.types,f.context.snapshot(),config)) {
                var request=new RoutingRequest(f.carrier,context,"car");
                var result=new CarrierOptimizationService().optimize(f.carrier,request,config,42,2);
                var store=store();store.save(f.carrier,result,request,config,42);
                assertThat(store.load(f.carrier,request,config,42)).isPresent();
                var changed=f.plan(0,b,a);f.carrier.addPlan(changed);f.carrier.setSelectedPlan(changed);
                assertThat(PlanValidator.validate(f.carrier,changed,request,config).valid()).isTrue();
                assertThat(store.load(f.carrier,request,config,42)).isPresent();
                assertThat(f.carrier.getSelectedPlan()).isSameAs(changed);
            }
        }
    }
    @Test void unavailableCodeFingerprintIsAMiss() {
        try(var f=new OptimizationFixture()) {
            f.service("a",1,0,900);
            var unavailable=new OptimizationResultStore(directory,()->{throw new IllegalStateException("Code source unavailable");});
            assertThat(unavailable.load(f.carrier,f.request,f.config,42)).isEmpty();
            assertThat(f.carrier.getSelectedPlan()).isNull();
        }
    }
    @Test void optionalCacheFailureDoesNotAbortAValidCarrierPass() throws Exception {
        var config=HagridConfigResolver.resolve(new HagridConfig(),HagridConfigResolver.hannoverTarget(),
                HagridConfigResolver.Overrides.of(Map.of("optimization.replanningIterations","1")),Map.of());
        try(var f=new OptimizationFixture()) {
            var carrier=resumeCarrier(f,"optional",1);
            var metadata="immutable external metadata";carrier.getAttributes().putAttribute("externalMetadata",metadata);
            var blocker=directory.resolve("cache-is-a-file");Files.writeString(blocker,"not a directory");
            var brokenCache=new OptimizationResultStore(blocker,()->Optional.of("test-code"));
            var context=RoutingContext.create(f.context.network(),f.types,f.context.snapshot(),config);
            try(var coordinator=new hagrid.core.simulation.ReplanningCoordinator(config,42,brokenCache)) {
                coordinator.runGroup(25,new Carriers(List.of(carrier)),()->context);
                assertThat(coordinator.cacheHits()).isZero();
                assertThat(coordinator.statuses()).containsEntry("optional",hagrid.core.simulation.ReplanningCoordinator.Status.COMPLETED);
                assertThat(carrier.getSelectedPlan().getScheduledTours()).hasSize(1);
                assertThat(carrier.getAttributes().getAttribute("externalMetadata")).isSameAs(metadata);
                assertThat(PlanValidator.validate(carrier,carrier.getSelectedPlan(),new RoutingRequest(carrier,context,"car"),config).valid()).isTrue();
                var fresh=resumeCarrier(f,"optional",1);
                var freshContext=RoutingContext.create(f.context.network(),f.types,f.context.snapshot(),config);
                try(var baseline=new hagrid.core.simulation.ReplanningCoordinator(config,42,null)){
                    baseline.runGroup(25,new Carriers(List.of(fresh)),()->freshContext);
                    assertThat(carrier.getAttributes().getAttribute("validatedRoutingCost")).isEqualTo(fresh.getAttributes().getAttribute("validatedRoutingCost"));
                }
            }
        }
    }
    @Test void unsupportedMutableMetadataCannotPublishACandidate() {
        var config=HagridConfigResolver.resolve(new HagridConfig(),HagridConfigResolver.hannoverTarget(),
                HagridConfigResolver.Overrides.of(Map.of("optimization.replanningIterations","1")),Map.of());
        try(var f=new OptimizationFixture()) {
            var carrier=resumeCarrier(f,"unsupported",1);
            var metadata=new Object();carrier.getAttributes().putAttribute("externalMetadata",metadata);
            var context=RoutingContext.create(f.context.network(),f.types,f.context.snapshot(),config);
            try(var coordinator=new hagrid.core.simulation.ReplanningCoordinator(config,42,store())) {
                assertThatThrownBy(()->coordinator.runGroup(25,new Carriers(List.of(carrier)),()->context))
                        .hasMessageContaining("Unsupported mutable routing attribute");
                assertThat(carrier.getSelectedPlan()).isNull();
                assertThat(carrier.getAttributes().getAttribute("externalMetadata")).isSameAs(metadata);
            }
        }
    }
    @Test void anInvalidRequestStillFailsBeforeOptionalCacheAccess() {
        try(var f=new OptimizationFixture()) {
            var other=resumeCarrier(f,"other",1);
            assertThatThrownBy(()->store().load(other,f.request,f.config,42))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("another carrier");
        }
    }
    @Test void aCorruptPackageIsDiagnosedAndPreservedAsAMiss() throws Exception {
        try(var f=new OptimizationFixture()){
            var job=f.service("a",1,0,900);var store=store();
            store.save(f.carrier,f.result(f.plan(0,job),1),f.request,f.config,42);
            Path file;try(var entries=Files.list(directory)){file=entries.filter(p->p.toString().endsWith(".zip")).findFirst().orElseThrow();}
            Files.writeString(file,"interrupted archive");
            assertThat(store.load(f.carrier,f.request,f.config,42)).isEmpty();
            assertThat(Files.readString(file)).isEqualTo("interrupted archive");
        }
    }
    @Test void aChecksummedButInvalidRoadIsRejectedOnLoad() throws Exception {
        try(var f=new OptimizationFixture()){
            var job=f.service("a",1,0,900);var store=store();
            store.save(f.carrier,f.result(f.plan(0,job),1),f.request,f.config,42);
            Path file;try(var entries=Files.list(directory)){file=entries.filter(p->p.toString().endsWith(".zip")).findFirst().orElseThrow();}
            byte[] xml,manifest;
            try(var zip=new java.util.zip.ZipFile(file.toFile())){
                xml=zip.getInputStream(zip.getEntry("carrier.xml")).readAllBytes();
                manifest=zip.getInputStream(zip.getEntry("manifest.json")).readAllBytes();
            }
            String changed=new String(xml,java.nio.charset.StandardCharsets.UTF_8).replaceFirst("<route>\\s*road</route>","<route>return</route>");
            assertThat(changed).isNotEqualTo(new String(xml,java.nio.charset.StandardCharsets.UTF_8));
            var json=new com.fasterxml.jackson.databind.ObjectMapper();
            var node=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(manifest);
            var digest=java.security.MessageDigest.getInstance("SHA-256").digest(changed.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            node.put("xmlDigest",java.util.HexFormat.of().formatHex(digest));
            try(var zip=new java.util.zip.ZipOutputStream(Files.newOutputStream(file))){
                zip.putNextEntry(new java.util.zip.ZipEntry("carrier.xml"));zip.write(changed.getBytes(java.nio.charset.StandardCharsets.UTF_8));zip.closeEntry();
                zip.putNextEntry(new java.util.zip.ZipEntry("manifest.json"));zip.write(json.writeValueAsBytes(node));zip.closeEntry();
            }
            assertThat(store.load(f.carrier,f.request,f.config,42)).isEmpty();
            assertThat(f.carrier.getSelectedPlan()).isNull();
        }
    }
    @Test void interruptedReplacementPreservesThePreviousCompletedResult() throws Exception {
        try(var f=new OptimizationFixture()){
            var job=f.service("a",1,0,900);var result=f.result(f.plan(0,job),1);var store=store();
            store.save(f.carrier,result,f.request,f.config,42);
            var broken=new OptimizationResultStore(directory,()->Optional.of("test-code"),
                    (from,to)->{throw new IOException("Interrupted publication");});
            assertThatThrownBy(()->broken.save(f.carrier,result,f.request,f.config,42)).isInstanceOf(java.io.UncheckedIOException.class);
            assertThat(store.load(f.carrier,f.request,f.config,42)).isPresent();
            try(var entries=Files.list(directory)){assertThat(entries.toList()).hasSize(1);}
        }
    }
    @Test void aNewCoordinatorReusesOnlyTheMatchingCompletedSearch() {
        var config=HagridConfigResolver.resolve(new HagridConfig(),HagridConfigResolver.hannoverTarget(),
                HagridConfigResolver.Overrides.of(Map.of("optimization.replanningIterations","1")),Map.of());
        var store=store();
        try(var first=new OptimizationFixture();var second=new OptimizationFixture()){
            first.service("a",1,0,900);second.service("a",1,0,900);
            var context1=RoutingContext.create(first.context.network(),first.types,first.context.snapshot(),config);
            var context2=RoutingContext.create(second.context.network(),second.types,second.context.snapshot(),config);
            try(var a=new hagrid.core.simulation.ReplanningCoordinator(config,42,store);
                var b=new hagrid.core.simulation.ReplanningCoordinator(config,42,store)){
                a.runGroup(25,new Carriers(List.of(first.carrier)),()->context1);
                b.runGroup(25,new Carriers(List.of(second.carrier)),()->context2);
                assertThat(a.cacheHits()).isZero();assertThat(b.cacheHits()).isEqualTo(1);
                assertThat(first.carrier.getAttributes().getAttribute("jspritComputationTime")).isInstanceOf(Double.class);
                assertThat(second.carrier.getAttributes().getAttribute("jspritComputationTime"))
                        .as("the historical solver runtime is absent, not the measured cache-load time").isNull();
                assertThat(second.carrier.getSelectedPlan().getScheduledTours()).hasSize(1);
                assertThat(second.carrier.getAttributes().getAttribute("validatedRoutingCost"))
                        .isEqualTo(first.carrier.getAttributes().getAttribute("validatedRoutingCost"));
            }
        }
    }
    @Test void shipmentPairsAndSkillsSurviveTheNativePackage() {
        try(var f=new OptimizationFixture()){
            CarriersUtils.setSkills(f.type,Set.of("cold"));
            var shipment=CarrierShipment.Builder.newInstance(
                    org.matsim.api.core.v01.Id.create("shipment",CarrierShipment.class),
                    org.matsim.api.core.v01.Id.createLinkId("road"),
                    org.matsim.api.core.v01.Id.createLinkId("stop"),3)
                    .setPickupDuration(5).setDeliveryDuration(7)
                    .setPickupStartingTimeWindow(TimeWindow.newInstance(0,900))
                    .setDeliveryStartingTimeWindow(TimeWindow.newInstance(0,900)).build();
            CarriersUtils.setSkills(shipment,Set.of("cold"));shipment.getAttributes().putAttribute("b2b",3);
            f.carrier.getShipments().put(shipment.getId(),shipment);
            var builder=Tour.Builder.newInstance(org.matsim.api.core.v01.Id.create("shipment-tour",Tour.class));
            builder.scheduleStart(f.vehicle.getLinkId());builder.addLeg(new Tour.Leg());builder.schedulePickup(shipment);
            builder.addLeg(new Tour.Leg());builder.scheduleDelivery(shipment);builder.addLeg(new Tour.Leg());builder.scheduleEnd(f.vehicle.getLinkId());
            var plan=new CarrierPlan(new ArrayList<>(List.of(ScheduledTour.newInstance(builder.build(),f.vehicle,0))));
            plan.setCarrier(f.carrier);org.matsim.freight.carriers.jsprit.NetworkRouter.routePlan(plan,f.costs);
            var store=store();store.save(f.carrier,f.result(plan,1),f.request,f.config,42);
            var loaded=store.load(f.carrier,f.request,f.config,42).orElseThrow();
            assertThat(PlanValidator.validate(f.carrier,loaded.plan(),f.request,f.config).valid()).isTrue();
            assertThat(loaded.plan().getCarrier().getShipments().get(shipment.getId()).getAttributes().getAttribute("b2b")).isEqualTo(3);
            assertThat(f.carrier.getShipments().get(shipment.getId())).isSameAs(shipment);
        }
    }
    @Test void fractionalTimesAndFinalCostSurviveExactly() {
        try(var f=new OptimizationFixture()){
            var job=f.service("fractional",1,0.75,900.75);
            var plan=f.plan(0.25,job);var store=store();
            var before=PlanValidator.validate(f.carrier,plan,f.request,f.config);
            store.save(f.carrier,f.result(plan,1),f.request,f.config,42);
            var loaded=store.load(f.carrier,f.request,f.config,42).orElseThrow();
            assertThat(loaded.plan().getScheduledTours().iterator().next().getDeparture()).isEqualTo(0.25);
            assertThat(loaded.finalCost()).isEqualTo(before.finalCost());
            assertThat(loaded.plan().getCarrier().getServices().get(job.getId()).getServiceStaringTimeWindow().getEnd()).isEqualTo(900.75);
        }
    }
    @Test void aFailedGroupReusesItsCompletedCarrierAndSolvesTheRest() {
        var config=HagridConfigResolver.resolve(new HagridConfig(),HagridConfigResolver.hannoverTarget(),
                HagridConfigResolver.Overrides.of(Map.of("optimization.replanningIterations","1")),Map.of());
        try(var f=new OptimizationFixture()){
            var store=store();var a=resumeCarrier(f,"a",1);var bad=resumeCarrier(f,"z",11);
            var before=RoutingContext.create(f.context.network(),f.types,f.context.snapshot(),config);
            try(var interrupted=new hagrid.core.simulation.ReplanningCoordinator(config,42,store)){
                assertThatThrownBy(()->interrupted.runGroup(25,new Carriers(List.of(a,bad)),()->before)).hasMessageContaining("Invalid");
                assertThat(a.getSelectedPlan()).isNull();assertThat(bad.getSelectedPlan()).isNull();
            }
            var resumedA=resumeCarrier(f,"a",1);var resumedZ=resumeCarrier(f,"z",1);
            var resumedContext=RoutingContext.create(f.context.network(),f.types,f.context.snapshot(),config);
            try(var resumed=new hagrid.core.simulation.ReplanningCoordinator(config,42,store)){
                resumed.runGroup(25,new Carriers(List.of(resumedA,resumedZ)),()->resumedContext);
                assertThat(resumed.cacheHits()).isEqualTo(1);
                assertThat(resumed.statuses().values()).containsOnly(hagrid.core.simulation.ReplanningCoordinator.Status.COMPLETED);
            }
            var freshA=resumeCarrier(f,"a",1);var freshZ=resumeCarrier(f,"z",1);
            var freshContext=RoutingContext.create(f.context.network(),f.types,f.context.snapshot(),config);
            try(var fresh=new hagrid.core.simulation.ReplanningCoordinator(config,42,null)){
                fresh.runGroup(25,new Carriers(List.of(freshA,freshZ)),()->freshContext);
                assertThat(resumedA.getAttributes().getAttribute("validatedRoutingCost")).isEqualTo(freshA.getAttributes().getAttribute("validatedRoutingCost"));
                assertThat(resumedZ.getAttributes().getAttribute("validatedRoutingCost")).isEqualTo(freshZ.getAttributes().getAttribute("validatedRoutingCost"));
            }
        }
    }
    private static Carrier resumeCarrier(OptimizationFixture f,String id,int demand){
        var c=CarriersUtils.createCarrier(org.matsim.api.core.v01.Id.create(id,Carrier.class));
        CarriersUtils.setCarrierMode(c,"car");CarriersUtils.addCarrierVehicle(c,f.vehicle);
        CarriersUtils.addService(c,CarrierService.Builder.newInstance(
                org.matsim.api.core.v01.Id.create(id+"-job",CarrierService.class),org.matsim.api.core.v01.Id.createLinkId("stop"))
                .setCapacityDemand(demand).setServiceDuration(10).setServiceStartingTimeWindow(TimeWindow.newInstance(0,900)).build());
        return c;
    }
    @Test void matchingEntryIsRevalidatedWithoutReplacingInput() throws Exception {
        try(var f=new OptimizationFixture()) {
            var job=f.service("a",1,0,900);
            var result=f.result(f.plan(0,job),9999);
            var store=store();store.save(f.carrier,result,f.request,f.config,42);
            var loaded=store.load(f.carrier,f.request,f.config,42).orElseThrow();
            assertThat(loaded.finalCost()).isEqualTo(PlanValidator.validate(f.carrier,result.plan(),f.request,f.config).finalCost());
            assertThat(loaded.finalCost()).isNotEqualTo(9999);
            assertThat(f.carrier.getServices().get(job.getId())).isSameAs(job);
            assertThat(f.carrier.getSelectedPlan()).isNull();
            assertThat(loaded.plan().getCarrier()).isNotNull().isNotSameAs(f.carrier);
        }
    }
    @Test void interruptedWriteIsNotACompletedResult() throws Exception {
        try(var f=new OptimizationFixture()) {
            var job=f.service("a",1,0,900);
            var broken=new OptimizationResultStore(directory,()->Optional.of("test-code"),
                    (from,to)->{throw new IOException("Interrupted publication");});
            assertThatThrownBy(()->broken.save(f.carrier,f.result(f.plan(0,job),1),f.request,f.config,42))
                    .isInstanceOf(java.io.UncheckedIOException.class);
            assertThat(store().load(f.carrier,f.request,f.config,42)).isEmpty();
            try(var entries=Files.list(directory)){assertThat(entries.toList()).isEmpty();}
        }
    }
    @Test void changedSnapshotIsCacheMiss() {
        try(var f=new OptimizationFixture()) {
            var job=f.service("a",1,0,900);var store=store();
            store.save(f.carrier,f.result(f.plan(0,job),1),f.request,f.config,42);
            var snapshot=TravelTimeSnapshot.capture(f.context.network(),Map.of(),
                    Map.of("car",(org.matsim.core.router.util.TravelTime)(l,t,p,v)->l.getLength()/5),24,2000,60);
            try(var context=RoutingContext.create(f.context.network(),f.types,snapshot,f.config)){
                assertThat(store.load(f.carrier,new RoutingRequest(f.carrier,context,"car"),f.config,42)).isEmpty();
            }
        }
    }
    @Test void changedConfigurationIsCacheMiss() {
        try(var f=new OptimizationFixture()){
            var job=f.service("a",1,0,900);var store=store();
            store.save(f.carrier,f.result(f.plan(0,job),1),f.request,f.config,42);
            var changed=HagridConfigResolver.resolve(new HagridConfig(),HagridConfigResolver.hannoverTarget(),
                    HagridConfigResolver.Overrides.of(Map.of("optimization.initialIterations","2")),Map.of());
            assertThat(store.load(f.carrier,f.request,changed,42)).isEmpty();
            assertThat(store.load(f.carrier,f.request,f.config,43)).isEmpty();
        }
    }
    @Test void changedJobsAndFleetAreCacheMisses() {
        try(var f=new OptimizationFixture()){
            var job=f.service("a",1,0,900);var store=store();
            store.save(f.carrier,f.result(f.plan(0,job),1),f.request,f.config,42);
            f.carrier.getServices().put(job.getId(),CarrierService.Builder.newInstance(job.getId(),job.getServiceLinkId()).setCapacityDemand(2).build());
            assertThat(store.load(f.carrier,f.request,f.config,42)).isEmpty();
            f.carrier.getServices().put(job.getId(),job);f.type.getCapacity().setOther(20);
            assertThat(store.load(f.carrier,f.request,f.config,42)).isEmpty();
        }
    }
    @Test void incompleteOrWrongContextCannotBeSaved() {
        try(var f=new OptimizationFixture()){
            var job=f.service("a",1,0,900);var plan=f.plan(0,job);var store=store();
            var incomplete=new CarrierOptimizationService.Result("test",plan,Set.of("a"),1,1,1,null,f.context.fingerprint(),List.of());
            assertThatThrownBy(()->store.save(f.carrier,incomplete,f.request,f.config,42)).isInstanceOf(IllegalArgumentException.class);
            var wrong=new CarrierOptimizationService.Result("test",plan,Set.of(),1,1,1,null,"other-context",List.of());
            assertThatThrownBy(()->store.save(f.carrier,wrong,f.request,f.config,42)).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void ideCacheRemainsDisabledWithoutCodeFingerprint(){
        try(var f=new OptimizationFixture()){
            var job=f.service("a",1,0,900);
            var disabled=new OptimizationResultStore(directory,Optional::empty);
            disabled.save(f.carrier,f.result(f.plan(0,job),1),f.request,f.config,42);
            assertThat(disabled.load(f.carrier,f.request,f.config,42)).isEmpty();
            assertThat(directory.resolve("completed")).doesNotExist();
        }
    }
}
