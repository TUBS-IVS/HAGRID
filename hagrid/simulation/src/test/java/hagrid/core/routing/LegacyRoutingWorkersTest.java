package hagrid.core.routing;

import org.junit.jupiter.api.Test;
import org.matsim.core.router.util.LeastCostPathCalculator;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class LegacyRoutingWorkersTest {
    @Test void failedCreationReturnsTheSlotAndFailedCalculationReturnsTheWorker() {
        var created=new AtomicInteger();
        LeastCostPathCalculator worker=(from,to,time,person,vehicle)->null;
        var pool=new LegacyRoutingWorkers(1,()-> {
            if(created.incrementAndGet()==1)throw new IllegalStateException("factory failed");
            return worker;
        });
        assertThrows(IllegalStateException.class,()->pool.use(router->router));
        assertSame(worker,pool.use(router->router));
        var failure=new IllegalArgumentException("routing failed");
        assertSame(failure,assertThrows(IllegalArgumentException.class,()->pool.use(router->{throw failure;})));
        assertSame(worker,pool.use(router->router));assertEquals(2,created.get());
    }

    @Test void occupiedPooledWorkerIsNeverSharedWithAnotherThread() throws Exception {
        var created=new AtomicInteger();
        var pool=new LegacyRoutingWorkers(1,()-> {
            created.incrementAndGet();
            return new LeastCostPathCalculator() {
                public Path calcLeastCostPath(org.matsim.api.core.v01.network.Node a,
                    org.matsim.api.core.v01.network.Node b,double t,
                    org.matsim.api.core.v01.population.Person p,org.matsim.vehicles.Vehicle v){return null;}
            };
        });
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var executor=Executors.newFixedThreadPool(2);
        try {
            var first=executor.submit(()->pool.use(router->{entered.countDown();await(release);return router;}));
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            var second=executor.submit(()->pool.use(router->router));
            var fallback=second.get(5,TimeUnit.SECONDS);
            release.countDown();var pooled=first.get(5,TimeUnit.SECONDS);
            assertNotSame(pooled,fallback);assertEquals(2,created.get());
            assertSame(pooled,pool.use(router->router));
        } finally {release.countDown();executor.shutdownNow();}
    }

    @Test void directNativeRouterRetainsPerThreadIdentity() {
        var created=new AtomicInteger();
        var pool=new LegacyRoutingWorkers(1,()-> {
            created.incrementAndGet();
            return new LeastCostPathCalculator() {
                public Path calcLeastCostPath(org.matsim.api.core.v01.network.Node a,
                    org.matsim.api.core.v01.network.Node b,double t,
                    org.matsim.api.core.v01.population.Person p,org.matsim.vehicles.Vehicle v){return null;}
            };
        });
        assertSame(pool.getThreadRouter(),pool.getThreadRouter());
        assertEquals(1,created.get());
    }

    private static void await(CountDownLatch latch) {
        try {if(!latch.await(5,TimeUnit.SECONDS))throw new AssertionError("Latch timeout");}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError(interrupted);}
    }
}