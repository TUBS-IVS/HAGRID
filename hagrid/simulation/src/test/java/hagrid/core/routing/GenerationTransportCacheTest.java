package hagrid.core.routing;

import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class GenerationTransportCacheTest {
    @Test void evictionAndWindowsRetainCorrectValuesAndLifetimeTotals() {
        var cache=new GenerationTransportCache<String,Integer>(1,()->{},()->{},()->{});
        assertEquals(10,cache.get("a",()->10));
        assertEquals(10,cache.get("a",()->999));
        assertEquals(20,cache.get("b",()->20));
        var first=cache.statistics();
        assertEquals(1,first.uniqueEntries());
        assertEquals(3,first.totalRequests());assertEquals(1,first.totalHits());
        assertEquals(2,first.totalMemoized());assertEquals(3,first.periodRequests());
        assertEquals(0,cache.statistics().periodRequests());
        cache.clear();
        var cleared=cache.statistics();
        assertEquals(0,cleared.uniqueEntries());assertEquals(3,cleared.totalRequests());
        assertEquals(11,cache.get("a",()->11));
    }

    @Test void concurrentRequestsShareOneCalculationAndCallbacks() throws Exception {
        var requests=new AtomicInteger();var hits=new AtomicInteger();var writes=new AtomicInteger();
        var calls=new AtomicInteger();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var cache=new GenerationTransportCache<String,Integer>(2,requests::incrementAndGet,hits::incrementAndGet,writes::incrementAndGet);
        var executor=Executors.newFixedThreadPool(2);
        try {
            var first=executor.submit(()->cache.get("key",()->{calls.incrementAndGet();entered.countDown();await(release);return 42;}));
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            var second=executor.submit(()->cache.get("key",()->{calls.incrementAndGet();return 99;}));
            release.countDown();
            assertEquals(42,first.get(5,TimeUnit.SECONDS));assertEquals(42,second.get(5,TimeUnit.SECONDS));
            assertEquals(1,calls.get());assertEquals(2,requests.get());assertEquals(1,hits.get());assertEquals(1,writes.get());
        } finally {release.countDown();executor.shutdownNow();}
    }

    @Test void oldInFlightWorkCannotPopulateTheNewGeneration() throws Exception {
        var cache=new GenerationTransportCache<String,Integer>(2,()->{},()->{},()->{});
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var executor=Executors.newSingleThreadExecutor();
        try {
            var old=executor.submit(()->cache.get("key",()->{entered.countDown();await(release);return 10;}));
            assertTrue(entered.await(5,TimeUnit.SECONDS));cache.clear();
            assertEquals(20,cache.get("key",()->20));
            release.countDown();assertEquals(10,old.get(5,TimeUnit.SECONDS));
            assertEquals(20,cache.get("key",()->99));assertEquals(1,cache.statistics().uniqueEntries());
        } finally {release.countDown();executor.shutdownNow();}
    }

    @Test void failedWorkIsNotCachedAndTheNextRequestRetries() {
        var cache=new GenerationTransportCache<String,Integer>(2,()->{},()->{},()->{});
        var expected=new IllegalArgumentException("network failure");
        assertSame(expected,assertThrows(IllegalArgumentException.class,()->cache.get("key",()->{throw expected;})));
        assertEquals(0,cache.statistics().uniqueEntries());
        assertEquals(42,cache.get("key",()->42));
    }

    @Test void fatalErrorsKeepTheirIdentityAndDoNotPoisonRetry() {
        var cache=new GenerationTransportCache<String,Integer>(2,()->{},()->{},()->{});
        var expected=new AssertionError("fatal routing error");
        assertSame(expected,assertThrows(AssertionError.class,()->cache.get("key",()->{throw expected;})));
        assertEquals(42,cache.get("key",()->42));
    }

    @Test void rejectsUnboundedOrInvalidCapacity() {
        assertThrows(IllegalArgumentException.class,()->new GenerationTransportCache<String,Integer>(0,()->{},()->{},()->{}));
        assertThrows(IllegalArgumentException.class,()->new GenerationTransportCache<String,Integer>(-1,()->{},()->{},()->{}));
    }

    private static void await(CountDownLatch latch) {
        try {if(!latch.await(5,TimeUnit.SECONDS))throw new AssertionError("Latch timeout");}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError(interrupted);}
    }
}