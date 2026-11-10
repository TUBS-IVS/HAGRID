package hagrid.core.routing;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class CombinedInsertionLegTest {
    @Test void costAndNativeTimeShareOneCachedPathWithoutUsingTheOdTimeRaster() {
        try(var f=StaticDistancePathCacheTest.fixture(Map.of())) {
            var roads=f.costs();
            double expected=roads.solverCosts().getTransportCost(StaticDistancePathCacheTest.FROM,
                    StaticDistancePathCacheTest.TO,900,null,f.vehicle());
            long hits=f.context().statistics().cacheHits();
            var leg=roads.insertionLeg(StaticDistancePathCacheTest.FROM,StaticDistancePathCacheTest.TO,900,null,f.vehicle());
            assertEquals(expected,leg.monetaryCost());
            assertEquals(5.5,leg.timeSeconds());
            assertEquals(100,leg.distanceMeters());
            assertEquals(hits+1,f.context().statistics().cacheHits());
            assertEquals(1,f.context().statistics().computations());
            assertThrows(TravelTimeSnapshot.RangeException.class,()->roads.insertionLeg(
                    StaticDistancePathCacheTest.FROM,StaticDistancePathCacheTest.TO,10799,null,f.vehicle()));
            assertThrows(NoRouteException.class,()->roads.insertionLeg(StaticDistancePathCacheTest.FROM,
                    com.graphhopper.jsprit.core.problem.Location.newInstance("isolated"),0,null,f.vehicle()));
            assertEquals(0,f.context().statistics().activeRouters());
            f.close();
            assertThrows(IllegalStateException.class,()->roads.insertionLeg(StaticDistancePathCacheTest.FROM,
                    StaticDistancePathCacheTest.TO,900,null,f.vehicle()));
        }
    }

    @Test void constantProfilesAndSameLinkTripsKeepTheirCostsAndTiming() {
        try(var f=RawDistanceTemporalRoutingTest.fixture((l,t,p,v)->7,900,0,10800)) {
            var roads=f.costs();
            var from=RawDistanceTemporalRoutingTest.from();var to=RawDistanceTemporalRoutingTest.to();
            var leg=roads.insertionLeg(from,to,900,null,f.vehicle());
            assertEquals(roads.solverCosts().getTransportCost(from,to,900,null,f.vehicle()),leg.monetaryCost());
            assertEquals(roads.exactTransportTime(from,to,900,f.vehicle()),leg.timeSeconds());
            var zero=roads.insertionLeg(from,from,900,null,f.vehicle());
            assertEquals(0,zero.timeSeconds());assertEquals(0,zero.distanceMeters());assertEquals(0,zero.monetaryCost());
        }
    }

    @Test void simultaneousInsertionQueriesReuseThePathWithSingleRouterAdmission() throws Exception {
        try(var f=StaticDistancePathCacheTest.fixture(Map.of("routing.maximumConcurrentRouters","1"))) {
            var roads=f.costs();var start=new CountDownLatch(1);var workers=Executors.newFixedThreadPool(4);
            try {
                var work=new ArrayList<Future<Double>>();
                for(int i=0;i<16;i++) {
                    double departure=i%2==0?0:900;
                    work.add(workers.submit(()->{start.await();return roads.insertionLeg(
                            StaticDistancePathCacheTest.FROM,StaticDistancePathCacheTest.TO,departure,null,f.vehicle()).timeSeconds();}));
                }
                start.countDown();
                for(int i=0;i<work.size();i++)assertEquals(i%2==0?7:5.5,work.get(i).get(10,TimeUnit.SECONDS));
                assertEquals(1,f.context().statistics().computations());
                assertEquals(15,f.context().statistics().cacheHits());
                assertEquals(0,f.context().statistics().activeRouters());
            } finally {workers.shutdownNow();assertTrue(workers.awaitTermination(5,TimeUnit.SECONDS));}
        }
    }
}
