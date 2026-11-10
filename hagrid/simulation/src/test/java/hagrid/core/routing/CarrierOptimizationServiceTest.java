package hagrid.core.routing;
import org.junit.jupiter.api.Test;
import org.matsim.freight.carriers.CarrierPlan;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class CarrierOptimizationServiceTest {
    @Test void actualOneIterationSearchIsDetachedAndIndependentlyValidated(){try(var f=new OptimizationFixture()){
        f.service("a",1,0,500);var before=new CarrierPlan(List.of());f.carrier.addPlan(before);f.carrier.setSelectedPlan(before);
        var result=new CarrierOptimizationService().optimize(f.carrier,f.request,f.config,42,1);
        assertThat(result.unassignedJobIds()).isEmpty();assertThat(result.iterations()).isEqualTo(1);
        assertThat(result.contextFingerprint()).isEqualTo(f.context.fingerprint());
        assertThat(result.diagnostics()).isEmpty();assertThat(result.finalCost()).isFinite().isPositive();
        assertThat(PlanValidator.validate(f.carrier,result.plan(),f.request,f.config).valid()).isTrue();
        assertThat(f.carrier.getSelectedPlan()).isSameAs(before);assertThat(f.carrier.getPlans()).containsExactly(before);
        assertThat(result.plan().getCarrier()).isNotSameAs(f.carrier);
    }}
    @Test void aFailedConcurrentInsertionTerminatesItsInternalWorkersBeforeReturning() throws Exception {
        var config=hagrid.core.config.HagridConfigResolver.resolve(new hagrid.core.HagridConfig(),
                hagrid.core.config.HagridConfigResolver.hannoverTarget(),
                hagrid.core.config.HagridConfigResolver.Overrides.of(Map.of("resources.internalSolverThreads","2")),Map.of());
        try(var f=new OptimizationFixture(config)) {
            f.service("a",1,0,500);f.service("b",1,0,500);f.service("c",1,0,500);
            var caller=Thread.currentThread();
            var workers=java.util.concurrent.ConcurrentHashMap.<Thread>newKeySet();
            var costs=(org.matsim.freight.carriers.jsprit.VRPTransportCosts)java.lang.reflect.Proxy.newProxyInstance(
                    getClass().getClassLoader(),new Class<?>[]{org.matsim.freight.carriers.jsprit.VRPTransportCosts.class},
                    (proxy,method,args)->{
                        if(Thread.currentThread()!=caller && method.getName().startsWith("getTransport")) {
                            workers.add(Thread.currentThread());
                            throw new IllegalStateException("insertion failed");
                        }
                        try{return method.invoke(f.costs,args);}
                        catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}
                    });
            assertThatThrownBy(()->new CarrierOptimizationService().optimize(f.carrier,costs,config,42,2,
                    HAGRIDRouterUtils.AlgorithmPhase.REPLANNING,25200,f.context.fingerprint()))
                    .isInstanceOf(RuntimeException.class);
            assertThat(workers).isNotEmpty();
            for(var thread:workers)thread.join(1000);
            assertThat(workers).allSatisfy(thread->assertThat(thread.isAlive()).as(thread.getName()).isFalse());
            assertThat(f.carrier.getSelectedPlan()).isNull();
        }
    }
    @Test void initialSearchHonorsRequestedInternalWorkersAndClosesThem() {
        try(var f=new OptimizationFixture()) {
            f.service("a",1,0,500);f.service("b",1,0,500);
            var caller=Thread.currentThread();var threads=java.util.concurrent.ConcurrentHashMap.<Thread>newKeySet();
            var costs=(org.matsim.freight.carriers.jsprit.VRPTransportCosts)java.lang.reflect.Proxy.newProxyInstance(
                    getClass().getClassLoader(),new Class<?>[]{org.matsim.freight.carriers.jsprit.VRPTransportCosts.class},
                    (proxy,method,args)->{
                        if(Thread.currentThread()!=caller)threads.add(Thread.currentThread());
                        try{return method.invoke(f.costs,args);}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}
                    });
            var resources=new hagrid.core.config.ResolvedHagridConfig.Resources(2,OptionalInt.of(1));
            var result=new CarrierOptimizationService().optimizeInitial(f.carrier,costs,1,25200,0,resources);
            assertThat(result.diagnostics()).isEmpty();assertThat(result.iterations()).isEqualTo(1);
            assertThat(threads).isNotEmpty().allSatisfy(thread->{
                assertThat(thread.getName()).startsWith("hagrid-jsprit-");assertThat(thread.isAlive()).isFalse();
            });
            assertThat(f.carrier.getSelectedPlan()).isNull();
        }
    }
    @Test void configurationCannotContradictTheFrozenRoutingRequest() {
        try(var f=new OptimizationFixture()) {
            f.service("a",1,0,500);
            var other=hagrid.core.config.HagridConfigResolver.resolve(new hagrid.core.HagridConfig(),
                    hagrid.core.config.HagridConfigResolver.hannoverTarget(),
                    hagrid.core.config.HagridConfigResolver.Overrides.of(Map.of("routing.zoneBasedCachingEnabled","false")),Map.of());
            var before=f.context.statistics();
            assertThatThrownBy(()->new CarrierOptimizationService().optimize(f.carrier,f.request,other,42,1))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("configuration");
            assertThat(f.context.statistics()).isEqualTo(before);
            assertThat(f.carrier.getSelectedPlan()).isNull();
        }
    }
    @Test void impossibleDemandIsReportedAndCannotPublishACompleteResult(){try(var f=new OptimizationFixture()){
        f.service("too-large",11,0,500);
        var result=new CarrierOptimizationService().optimize(f.carrier,f.request,f.config,42,1);
        assertThat(result.unassignedJobIds()).containsExactly("too-large");
        assertThat(result.diagnostics()).isNotEmpty();assertThat(result.finalCost()).isInfinite();
        assertThat(f.carrier.getSelectedPlan()).isNull();
    }}
    @Test void alreadyInterruptedSearchStopsBeforeProducingAnyCandidate() {try(var f=new OptimizationFixture()) {
        f.service("a",1,0,500);
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(()->new CarrierOptimizationService().optimize(f.carrier,f.request,f.config,42,1))
                    .isInstanceOf(java.util.concurrent.CancellationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(f.carrier.getSelectedPlan()).isNull();
        } finally {Thread.interrupted();}
    }}
    @Test void interruptedPublicationStopsBeforeDuplicatingOrRoutingWinner() {try(var f=new OptimizationFixture()) {
        var s=f.service("a",1,0,500);var plan=f.plan(0,s);var result=f.result(plan,1000);
        var selection=PlanSelection.choose(null,result,f.carrier,f.request,f.config);
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(()->CarrierOptimizationService.prepareForPublication(
                    f.carrier,selection,result,f.costs,f.config,f.config.tourRules().maxRouteDurationSeconds()))
                    .isInstanceOf(java.util.concurrent.CancellationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(f.carrier.getSelectedPlan()).isNull();
        } finally {Thread.interrupted();}
    }}    @Test void interruptedInitialSearchDoesNotEvenAccessNetworkForProblemConstruction() {try(var f=new OptimizationFixture()) {
        f.service("a",1,0,500);var networkReads=new java.util.concurrent.atomic.AtomicInteger();
        var costs=(org.matsim.freight.carriers.jsprit.VRPTransportCosts)java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(),new Class<?>[]{org.matsim.freight.carriers.jsprit.VRPTransportCosts.class},
                (proxy,method,args)->{
                    if(method.getName().equals("getNetwork")) networkReads.incrementAndGet();
                    try {return method.invoke(f.costs,args);}
                    catch(java.lang.reflect.InvocationTargetException e) {throw e.getCause();}
                });
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(()->new CarrierOptimizationService().optimizeInitial(f.carrier,costs,1,25200,0))
                    .isInstanceOf(java.util.concurrent.CancellationException.class);
            assertThat(networkReads).hasValue(0);
        } finally {Thread.interrupted();}
    }}}
