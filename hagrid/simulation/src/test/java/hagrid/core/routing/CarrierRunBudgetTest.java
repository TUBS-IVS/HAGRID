package hagrid.core.routing;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;

class CarrierRunBudgetTest {
    private static CarrierBatchExecutor.Descriptor carrier(String id,long bytes) {
        return new CarrierBatchExecutor.Descriptor(id,2,3,1,42,bytes);
    }
    @Test void sharedHeapAndPhysicalReserveBothLimitCarrierReservations() {
        var state=new CarrierRunBudget.State(1000,400,100,2000,1000,200);
        assertThat(state.availableCarrierBytes()).isEqualTo(500);
        assertThat(new CarrierRunBudget.State(1000,400,100,1350,1000,200).availableCarrierBytes()).isEqualTo(150);
    }
    @Test void exhaustedOrOverflowingMarginsFailWithoutWrappingIntoPositiveCapacity() {
        for(var state:List.of(
                new CarrierRunBudget.State(100,90,10,1000,0,0),
                new CarrierRunBudget.State(100,101,0,1000,0,0),
                new CarrierRunBudget.State(Long.MAX_VALUE,Long.MAX_VALUE,Long.MAX_VALUE,Long.MAX_VALUE,0,0),
                new CarrierRunBudget.State(1000,0,0,Long.MAX_VALUE,Long.MAX_VALUE,Long.MAX_VALUE))) {
            assertThatThrownBy(state::availableCarrierBytes).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("memory");
        }
        assertThatThrownBy(()->new CarrierRunBudget.State(100,-1,0,100,0,0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new CarrierRunBudget.State(0,0,0,100,0,0)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void publishedPlansAndGrowingCachesReduceTheNextGroupBeforeAnyConstruction() {
        var shared=new AtomicLong(400);var calls=new AtomicInteger();var builds=new AtomicInteger();var outputs=new ArrayList<String>();
        var budget=new CarrierRunBudget(()->{calls.incrementAndGet();return new CarrierRunBudget.State(1000,shared.get(),100,2000,1000,200);});
        try(var executor=new CarrierBatchExecutor(2,budget,Duration.ofSeconds(2))){
            var group=List.of(carrier("a",200),carrier("b",200));
            executor.runStaged(group,d->{builds.incrementAndGet();return d.carrierId();},outputs::add,()->{
                assertThat(executor.reservedBytes()).isEqualTo(400);
                shared.set(600); // Owner includes newly retained plans/cache before the next group.
            });
            assertThat(outputs).containsExactly("a","b");assertThat(builds).hasValue(2);assertThat(calls).hasValue(1);
            outputs.clear();
            assertThatThrownBy(()->executor.runStaged(group,d->{builds.incrementAndGet();return d.carrierId();},
                    outputs::add,()->{throw new AssertionError("Unexpected publication");}))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Staged");
            assertThat(calls).hasValue(2);assertThat(builds).hasValue(2);assertThat(outputs).isEmpty();
            assertThat(executor.reservedBytes()).isZero();
        }
    }
    @Test void failingPhysicalMeasurementCannotBecomeAValidZeroOrUnlimitedBudget() {
        var budget=new CarrierRunBudget(()->{throw new IllegalStateException("Physical memory query failed");});
        try(var executor=new CarrierBatchExecutor(2,budget,Duration.ofSeconds(2))){
            assertThatThrownBy(()->executor.runStaged(List.of(carrier("a",10)),d->{throw new AssertionError();},
                    r->{throw new AssertionError();},()->{throw new AssertionError();}))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("query failed");
            assertThat(executor.createdWorkers()).isZero();assertThat(executor.reservedBytes()).isZero();
        }
    }
    @Test void oneBudgetSnapshotPerGroupRemainsFixedDuringPublication() {
        var calls=new AtomicInteger();
        try(var executor=new CarrierBatchExecutor(1,()->{calls.incrementAndGet();return 100L;},Duration.ofSeconds(2))){
            executor.runStaged(List.of(carrier("a",60)),d->{
                assertThat(calls).hasValue(1);return d.carrierId();
            },r->assertThat(calls).hasValue(1),()->assertThat(executor.reservedBytes()).isEqualTo(60));
            assertThat(calls).hasValue(1);
        }
    }
    @Test void nonpositiveRawBudgetFailsBeforeConstructionAndPublication() {
        for(long bytes:new long[]{0,-1}){
            try(var executor=new CarrierBatchExecutor(1,()->bytes,Duration.ofSeconds(2))){
                assertThatThrownBy(()->executor.runStaged(List.of(carrier("a",1)),d->{throw new AssertionError();},
                        r->{throw new AssertionError();},()->{throw new AssertionError();}))
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining("memory");
                assertThat(executor.createdWorkers()).isZero();
                assertThat(executor.reservedBytes()).isZero();
            }
        }
    }    private static void waitForBudgetBoundary(java.util.concurrent.CountDownLatch latch) {
        try {
            if(!latch.await(3,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("Budget boundary timeout");
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
    }
    @Test void concurrentCallerCannotReadDuringActiveGroupThenAdmitWithStaleBudget() throws Exception {
        var margin=new AtomicLong(100);var samples=new AtomicInteger();var secondBuilds=new AtomicInteger();
        var firstStarted=new java.util.concurrent.CountDownLatch(1);var releaseFirst=new java.util.concurrent.CountDownLatch(1);
        var firstEnded=new java.util.concurrent.CountDownLatch(1);var secondBoundary=new java.util.concurrent.CountDownLatch(1);
        var resumeSecondSample=new java.util.concurrent.CountDownLatch(1);
        var firstFailure=new AtomicReference<Throwable>();var secondFailure=new AtomicReference<Throwable>();
        try(var executor=new CarrierBatchExecutor(1,()->{
            long observed=margin.get();
            if(samples.incrementAndGet()==2){secondBoundary.countDown();waitForBudgetBoundary(resumeSecondSample);}
            return observed;
        },Duration.ofSeconds(2))){
            var first=new Thread(()->{
                try{executor.runStaged(List.of(carrier("first",60)),d->{firstStarted.countDown();waitForBudgetBoundary(releaseFirst);return d;},
                        r->{},()->margin.set(20));}
                catch(Throwable e){firstFailure.set(e);}
                finally{firstEnded.countDown();}
            },"budget-first-caller");
            var second=new Thread(()->{
                try{executor.runStaged(List.of(carrier("second",60)),d->{secondBuilds.incrementAndGet();return d;},r->{},()->{});}
                catch(Throwable e){secondFailure.set(e);}
                finally{secondBoundary.countDown();}
            },"budget-second-caller");
            first.setDaemon(true);second.setDaemon(true);
            try{
                first.start();waitForBudgetBoundary(firstStarted);
                second.start();waitForBudgetBoundary(secondBoundary);
                releaseFirst.countDown();waitForBudgetBoundary(firstEnded);
                resumeSecondSample.countDown();second.join(3000);
                assertThat(firstFailure.get()).isNull();
                assertThat(samples).hasValue(1);
                assertThat(secondBuilds).hasValue(0);
                assertThat(secondFailure.get()).isInstanceOf(IllegalStateException.class).hasMessageContaining("already running");
                assertThat(executor.reservedBytes()).isZero();
            }finally{
                releaseFirst.countDown();resumeSecondSample.countDown();
                first.join(3000);second.join(3000);
                assertThat(first.isAlive()).isFalse();assertThat(second.isAlive()).isFalse();
            }
        }
    }
    @Test void failedBudgetObservationReleasesCallerClaimForAValidRetry() {
        var calls=new AtomicInteger();var outputs=new ArrayList<String>();
        try(var executor=new CarrierBatchExecutor(1,()->{
            if(calls.getAndIncrement()==0)throw new IllegalStateException("Failed sample");
            return 100;
        },Duration.ofSeconds(2))){
            assertThatThrownBy(()->executor.runStaged(List.of(carrier("a",60)),d->d.carrierId(),outputs::add,()->{}))
                    .hasMessageContaining("Failed sample");
            executor.runStaged(List.of(carrier("a",60)),d->d.carrierId(),outputs::add,()->{});
            assertThat(outputs).containsExactly("a");assertThat(calls).hasValue(2);
            assertThat(executor.reservedBytes()).isZero();
        }
    }
    @Test void emptyGroupDoesNotQueryMemoryOrCreateWorkers() {
        try(var executor=new CarrierBatchExecutor(2,()->{throw new AssertionError("No work needs no query");},Duration.ofSeconds(2))){
            executor.runStaged(List.of(),d->{throw new AssertionError();},r->{throw new AssertionError();},()->{});
            assertThat(executor.createdWorkers()).isZero();
        }
    }
    @Test void currentJvmAggregateIsOptionalAndMeasurementFailuresStayExplicit() {
        var budget=CarrierRunBudget.forCurrentJvm(()->2000L,1000,0,200);
        assertThat(budget.getAsLong()).isPositive().isLessThanOrEqualTo(800);
        var invalid=CarrierRunBudget.forCurrentJvm(()->{throw new IllegalStateException("Missing physical sample");},1000,0,200);
        assertThatThrownBy(invalid::getAsLong).isInstanceOf(IllegalStateException.class).hasMessageContaining("Missing physical");
    }
}