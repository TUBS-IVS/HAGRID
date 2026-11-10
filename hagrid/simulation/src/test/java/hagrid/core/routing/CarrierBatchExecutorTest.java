package hagrid.core.routing;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;

class CarrierBatchExecutorTest {
    private static CarrierBatchExecutor.Descriptor job(String id, long bytes) {
        return new CarrierBatchExecutor.Descriptor(id, 2, 3, 1, 42, bytes);
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(3, TimeUnit.SECONDS)) throw new AssertionError("Latch timeout"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new CancellationException(); }
    }
    @Test void emptyBatchDoesNotCreateWorkers() {
        try (var executor=new CarrierBatchExecutor(2, 100, Duration.ofSeconds(2))) {
            executor.run(List.of(), d->{throw new AssertionError();}, r->{throw new AssertionError();});
            assertThat(executor.createdWorkers()).isZero();
        }
    }
    @Test void stagedBatchLimitCanChangeBetweenGroupsWithoutExceedingOwnerCapacity() {
        try(var executor=new CarrierBatchExecutor(2,200,Duration.ofSeconds(2))) {
            var active=new AtomicInteger();var maximum=new AtomicInteger();
            var group=List.of(job("first",50),job("second",50),job("third",50));
            var outputs=new ArrayList<String>();
            executor.runStaged(1,group,d->{
                maximum.accumulateAndGet(active.incrementAndGet(),Math::max);
                try{return d.carrierId();}finally{active.decrementAndGet();}
            },outputs::add,()->assertThat(executor.reservedBytes()).isEqualTo(150));
            assertThat(executor.createdWorkers()).isEqualTo(1);
            assertThat(maximum).hasValue(1);
            outputs.clear();maximum.set(0);
            var both=new CountDownLatch(2);
            executor.runStaged(4,group,d->{
                maximum.accumulateAndGet(active.incrementAndGet(),Math::max);
                try {
                    both.countDown();await(both);
                    return d.carrierId();
                } finally {active.decrementAndGet();}
            },outputs::add,()->assertThat(executor.reservedBytes()).isEqualTo(150));
            assertThat(maximum).hasValue(2);
            assertThat(outputs).containsExactly("first","second","third");
            assertThat(executor.reservedBytes()).isZero();
        }
    }
    @Test void invalidBatchLimitFailsBeforeWorkersOrPublication() {
        try(var executor=new CarrierBatchExecutor(2,100,Duration.ofSeconds(2))){
            for(int limit:new int[]{0,-1})
                assertThatThrownBy(()->executor.runStaged(limit,List.of(job("first",50)),d->d,
                        r->{throw new AssertionError();},()->{throw new AssertionError();}))
                        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("solver");
            assertThat(executor.createdWorkers()).isZero();
            assertThat(executor.reservedBytes()).isZero();
        }
    }
    @Test void shipmentMetadataCountsBothActivities() {
        var d=job("a", 30);
        assertThat(d.jobCount()).isEqualTo(5);
        assertThat(d.activityCount()).isEqualTo(8);
    }
    @Test void typedResultsAreAcceptedOnCallerBeforeReservationIsReleasedAndPoolIsReused() {
        try (var executor=new CarrierBatchExecutor(2, 100, Duration.ofSeconds(2))) {
            Thread caller=Thread.currentThread(); var values=new ArrayList<Integer>();
            for(int i=0;i<2;i++) executor.run(List.of(job("a", 60)), d->{
                assertThat(executor.reservedBytes()).isEqualTo(60);
                assertThat(Thread.currentThread()).isNotSameAs(caller);
                return 7;
            }, value->{
                assertThat(Thread.currentThread()).isSameAs(caller);
                assertThat(executor.reservedBytes()).isEqualTo(60);
                values.add(value);
            });
            assertThat(values).containsExactly(7,7);
            assertThat(executor.reservedBytes()).isZero();
            assertThat(executor.createdWorkers()).isLessThanOrEqualTo(2);
        }
    }
    @Test void memoryAdmissionPrecedesConstructionAndWaitingDescriptorsDoNotOccupyWorkers() {
        try (var executor=new CarrierBatchExecutor(2, 100, Duration.ofSeconds(2))) {
            var active=new AtomicInteger(); var maximum=new AtomicInteger(); var order=new ArrayList<String>();
            executor.run(List.of(job("first", 60),job("large", 80),job("small", 30)), d->{
                maximum.accumulateAndGet(active.incrementAndGet(),Math::max);
                assertThat(executor.reservedBytes()).isLessThanOrEqualTo(100);
                active.decrementAndGet();return d.carrierId();
            }, order::add);
            assertThat(maximum).hasValue(1);
            assertThat(order).containsExactly("first","large","small");
            assertThat(executor.reservedBytes()).isZero();
        }
    }
    @Test void independentWorkersOverlapWithinBothLimits() {
        try (var executor=new CarrierBatchExecutor(2, 100, Duration.ofSeconds(2))) {
            var started=new CountDownLatch(2); var active=new AtomicInteger();var max=new AtomicInteger();
            var order=new ArrayList<String>();
            executor.run(List.of(job("a",50),job("b",50),job("c",50)),d->{
                max.accumulateAndGet(active.incrementAndGet(),Math::max);
                started.countDown();await(started);
                assertThat(executor.reservedBytes()).isLessThanOrEqualTo(100);
                active.decrementAndGet();return d.carrierId();
            },order::add);
            assertThat(max).hasValue(2);assertThat(order).containsExactly("a","b","c");
        }
    }
    @Test void oversizedAndDuplicateInputsFailBeforeAnyConstruction() {
        try (var executor=new CarrierBatchExecutor(2, 100, Duration.ofSeconds(2))) {
            var calls=new AtomicInteger();
            assertThatThrownBy(()->executor.run(List.of(job("ok",10),job("oversized",101)),
                    d->calls.incrementAndGet(),r->{})).hasMessageContaining("oversized");
            assertThatThrownBy(()->executor.run(List.of(job("same",10),job("same",10)),
                    d->calls.incrementAndGet(),r->{})).hasMessageContaining("Duplicate");
            assertThat(calls).hasValue(0);assertThat(executor.createdWorkers()).isZero();
        }
    }
    @Test void consumerFailureReleasesReservationAndPreventsLaterAcceptance() {
        try (var executor=new CarrierBatchExecutor(1, 100, Duration.ofSeconds(2))) {
            var calls=new AtomicInteger();
            assertThatThrownBy(()->executor.run(List.of(job("a",20),job("b",20)),d->d,
                    r->{calls.incrementAndGet();throw new IllegalStateException("consumer failure");}))
                    .hasMessageContaining("consumer failure");
            assertThat(calls).hasValue(1);assertThat(executor.reservedBytes()).isZero();
        }
    }
    @Test void failureInLaterWorkerCancelsAndWaitsForEarlierWorkerWithoutPublishing() {
        try (var executor=new CarrierBatchExecutor(2, 100, Duration.ofSeconds(2))) {
            var started=new CountDownLatch(1);var stopped=new AtomicBoolean();var accepted=new AtomicInteger();
            assertThatThrownBy(()->executor.run(List.of(job("slow",50),job("bad",50)),d->{
                if(d.carrierId().equals("bad")) {await(started);throw new IllegalStateException("bad carrier");}
                started.countDown();
                try {Thread.sleep(10000);}catch(InterruptedException e){Thread.currentThread().interrupt();}
                finally {stopped.set(true);}
                return d;
            },r->accepted.incrementAndGet())).hasMessageContaining("bad carrier");
            assertThat(stopped).isTrue();assertThat(accepted).hasValue(0);
            assertThat(executor.reservedBytes()).isZero();
        }
    }
    @Test void cancellationDoesNotReleaseMemoryWhileWorkerIgnoresInterrupts() throws Exception {
        var executor=new CarrierBatchExecutor(2, 100, Duration.ofMillis(100));
        var started=new CountDownLatch(1);var release=new CountDownLatch(1);var ended=new CountDownLatch(1);
        try {
            assertThatThrownBy(()->executor.run(List.of(job("stubborn",50),job("bad",50)),d->{
                if(d.carrierId().equals("bad")) {await(started);throw new IllegalStateException("bad carrier");}
                started.countDown();
                try {while(release.getCount()>0) {try {release.await();}catch(InterruptedException ignored) {}}}
                finally {ended.countDown();}
                return d;
            },r->{throw new AssertionError("No publication");})).hasMessageContaining("did not terminate");
            assertThat(executor.reservedBytes()).isEqualTo(50);
            assertThatThrownBy(()->executor.run(List.of(job("new",10)),d->d,r->{}))
                    .hasMessageContaining("unavailable");
        } finally {release.countDown();await(ended);executor.close();}
        assertThat(executor.reservedBytes()).isZero();
    }
    @Test void stagedResultsKeepFullReservationsThroughCallerPublication() {
        try (var executor=new CarrierBatchExecutor(1,100,Duration.ofSeconds(2))) {
            var staged=new ArrayList<String>();
            Thread caller=Thread.currentThread();
            executor.runStaged(List.of(job("first",40),job("second",60)),d->{
                assertThat(executor.reservedBytes()).isEqualTo(d.carrierId().equals("first")?40:100);
                return d.carrierId();
            },staged::add,()->{
                assertThat(Thread.currentThread()).isSameAs(caller);
                assertThat(staged).containsExactly("first","second");
                assertThat(executor.reservedBytes()).isEqualTo(100);
            });
            assertThat(executor.reservedBytes()).isZero();
        }
    }
    @Test void stagedGroupOverflowAndBudgetExcessFailBeforeConstruction() {
        try (var executor=new CarrierBatchExecutor(2,Long.MAX_VALUE,Duration.ofSeconds(2))) {
            assertThatThrownBy(()->executor.runStaged(List.of(job("a",Long.MAX_VALUE),job("b",1)),
                    d->{throw new AssertionError("No construction");},r->{},()->{}))
                    .hasMessageContaining("group").hasMessageContaining("budget");
            assertThat(executor.createdWorkers()).isZero();
            assertThat(executor.reservedBytes()).isZero();
        }
    }
    @Test void stagedPublicationFailureReleasesFinishedResultsAndExecutorCanBeReused() {
        try (var executor=new CarrierBatchExecutor(1,100,Duration.ofSeconds(2))) {
            assertThatThrownBy(()->executor.runStaged(List.of(job("a",50)),d->d,r->{},
                    ()->{throw new IllegalStateException("publication");})).hasMessageContaining("publication");
            assertThat(executor.reservedBytes()).isZero();
            executor.runStaged(List.of(job("again",100)),d->d,r->{},()->{});
            assertThat(executor.reservedBytes()).isZero();
        }
    }
    @Test void stagedFailureNeverPublishesEarlierResultsAndReleasesTheirReservations() {
        try (var executor=new CarrierBatchExecutor(1,100,Duration.ofSeconds(2))) {
            var staged=new ArrayList<String>();
            assertThatThrownBy(()->executor.runStaged(List.of(job("a",40),job("bad",60)),d->{
                if(d.carrierId().equals("bad"))throw new IllegalStateException("bad staged carrier");
                return d.carrierId();
            },staged::add,()->{throw new AssertionError("No publication");}))
                    .hasMessageContaining("bad staged carrier");
            assertThat(staged).containsExactly("a");
            assertThat(executor.reservedBytes()).isZero();
        }
    }
    @Test void closingFromOwnWorkerIsRejectedImmediatelyRatherThanWaitingForItself() {
        try(var executor=new CarrierBatchExecutor(1,100,Duration.ofMillis(200))) {
            executor.run(List.of(job("a",10)),d->{
                assertThatThrownBy(executor::close).hasMessageContaining("worker");
                return d;
            },r->{});
        }
    }
    @Test void externalCloseInterruptsCallerAndWaitsForActualWorkerExit() throws Exception {
        var executor=new CarrierBatchExecutor(1,100,Duration.ofSeconds(2));
        var started=new CountDownLatch(1);var stopped=new AtomicBoolean();var parentEnded=new CountDownLatch(1);
        var problem=new AtomicReference<Throwable>();var restored=new AtomicBoolean();var accepted=new AtomicInteger();
        var caller=new Thread(()->{
            try {executor.run(List.of(job("a",10)),d->{
                started.countDown();
                try {Thread.sleep(10000);}catch(InterruptedException e){Thread.currentThread().interrupt();}
                finally {stopped.set(true);}
                return d;
            },r->accepted.incrementAndGet());}
            catch(Throwable e) {problem.set(e);restored.set(Thread.currentThread().isInterrupted());}
            finally {parentEnded.countDown();}
        });
        caller.setDaemon(true);caller.start();await(started);
        executor.close();await(parentEnded);
        assertThat(problem.get()).isInstanceOf(CancellationException.class);
        assertThat(restored).isTrue();assertThat(stopped).isTrue();assertThat(accepted).hasValue(0);
        assertThat(executor.reservedBytes()).isZero();
    }}
