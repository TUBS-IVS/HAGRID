package hagrid.core.routing.directions;

import com.graphhopper.jsprit.core.problem.Location;
import com.graphhopper.jsprit.core.problem.job.Service;
import com.graphhopper.jsprit.core.problem.misc.JobInsertionContext;
import com.graphhopper.jsprit.core.problem.solution.route.VehicleRoute;
import com.graphhopper.jsprit.core.problem.solution.route.activity.TourActivity;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleImpl;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RouteOrderCacheTest {
    static Service job(String id) { return Service.Builder.newInstance(id).setLocation(Location.newInstance(1,1)).build(); }
    static VehicleRoute route() {return VehicleRoute.Builder.newInstance(VehicleImpl.Builder.newInstance("van").setStartLocation(Location.newInstance(0,0)).build()).addService(job("a")).addService(job("b")).build();}
    static JobInsertionContext query(VehicleRoute r) {return new JobInsertionContext(r,job("new"),r.getVehicle(),r.getDriver(),r.getDepartureTime());}
    static List<String> order(VehicleRoute r) {return r.getActivities().stream().map(a->((TourActivity.JobActivity)a).getJob().getId()).toList();}
    @Test void repeatedGapsAndNewQueriesDoNotRebuildAnUnchangedRoute() {
        var calls=new AtomicInteger();var cache=new RouteOrderCache<List<String>>(r->{calls.incrementAndGet();return order(r);},2);
        var r=route();var query=query(r);for(int i=0;i<100;i++)assertEquals(List.of("a","b"),cache.get(query));
        assertEquals(List.of("a","b"),cache.get(query(r)));
        assertEquals(1,calls.get(),"A candidate position must not rebuild the whole route snapshot");
    }
    @Test void sameLengthReorderBetweenQueriesInvalidatesTheCache() {
        var cache=new RouteOrderCache<List<String>>(RouteOrderCacheTest::order,2);var r=route();
        assertEquals(List.of("a","b"),cache.get(query(r)));
        var first=r.getActivities().getFirst();r.getTourActivities().removeActivity(first);r.getTourActivities().addActivity(first);
        assertEquals(List.of("b","a"),cache.get(query(r)));
    }
    @Test void removeThenReplaceWithSameLengthDoesNotReturnOldJobs() {
        var cache=new RouteOrderCache<List<String>>(RouteOrderCacheTest::order,2);var r=route();cache.get(query(r));
        r.getTourActivities().removeActivity(r.getActivities().getFirst());
        var replacement=VehicleRoute.Builder.newInstance(r.getVehicle()).addService(job("c")).build().getActivities().getFirst();
        r.getTourActivities().addActivity(replacement);
        assertEquals(List.of("b","c"),cache.get(query(r)));
    }
    @Test void copiedRoutesAndClearDoNotKeepStaleEntries() {
        var calls=new AtomicInteger();var cache=new RouteOrderCache<List<String>>(r->{calls.incrementAndGet();return order(r);},2);
        var r=route();cache.get(query(r));var copy=VehicleRoute.copyOf(r);
        assertEquals(List.of("a","b"),cache.get(query(copy)));assertEquals(2,calls.get());
        cache.clear();cache.get(query(copy));assertEquals(3,calls.get());
    }
    @Test void routeRetentionIsBounded() {
        var calls=new AtomicInteger();var cache=new RouteOrderCache<List<String>>(r->{calls.incrementAndGet();return order(r);},2);
        var a=route();cache.get(query(a));cache.get(query(route()));cache.get(query(route()));cache.get(query(a));
        assertEquals(4,calls.get());
    }
    @Test void parallelWorkersHaveIndependentSnapshots() throws Exception {
        var calls=new AtomicInteger();var cache=new RouteOrderCache<List<String>>(r->{calls.incrementAndGet();return order(r);},2);
        var r=route();var context=query(r);var barrier=new CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<List<String>> work=()->{barrier.await();for(int i=0;i<100;i++)cache.get(context);return cache.get(context);};
            var a=pool.submit(work);var b=pool.submit(work);
            assertEquals(List.of("a","b"),a.get(5,TimeUnit.SECONDS));assertEquals(List.of("a","b"),b.get(5,TimeUnit.SECONDS));
        }
        assertEquals(2,calls.get());
    }
}
