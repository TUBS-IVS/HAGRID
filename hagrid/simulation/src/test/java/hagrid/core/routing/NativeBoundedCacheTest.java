package hagrid.core.routing;

import com.graphhopper.jsprit.core.problem.Location;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleImpl;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleTypeImpl;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.core.network.NetworkUtils;
import org.matsim.freight.carriers.jsprit.NetworkBasedTransportCosts;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class NativeBoundedCacheTest {
    @Test
    void preparationCannotEvictActualVehicleResultsAndBothCachesStayBounded() throws Exception {
        var costs=builder().setCacheLimits(32,8).build();
        var queries=new AtomicInteger();
        costs.getInternalListeners().add(new NetworkBasedTransportCosts.InternalLeastCostPathCalculatorListener(){
            public void startCalculation(long id){queries.incrementAndGet();}
            public void endCalculation(long id){}
        });
        var van=vehicle("van");
        assertThat(costs.getTransportTime(loc(0),loc(2),0,null,van)).isEqualTo(20);
        for(int i=0;i<200;i++) costs.getTransportCost(loc(i%10),loc((i/10)%10),i*300,null,null);
        int before=queries.get();
        assertThat(costs.getTransportCost(loc(0),loc(2),0,null,van)).isEqualTo(200);
        assertThat(queries.get()).as("neutral preparation must not evict the van entry").isEqualTo(before);
        for(int i=0;i<200;i++) costs.getTransportCost(loc(i%10),loc((i/10)%10),i*300,null,van);
        assertThat(size(costs,"costCache")).isBetween(1,32);
        assertThat(size(costs,"neutralCostCache")).isBetween(1,8);
    }

    @Test
    void evictionPreservesDirectionTimeAndVehicleSpecificValues() throws Exception {
        var costs=builder().setCacheLimits(2,2).build();
        var van=vehicle("van");var special=vehicle("special");
        for(int repeat=0;repeat<3;repeat++) {
            assertThat(costs.getTransportCost(loc(0),loc(2),0,null,van)).isEqualTo(200);
            assertThat(costs.getTransportTime(loc(0),loc(2),0,null,van)).isEqualTo(20);
            assertThat(costs.getTransportTime(loc(0),loc(2),300,null,van)).isEqualTo(40);
            assertThat(costs.getTransportCost(loc(0),loc(2),0,null,special)).isEqualTo(400);
            assertThat(costs.getTransportCost(loc(2),loc(0),0,null,van)).isEqualTo(400);
            assertThat(costs.getDistance(loc(0),loc(2),0,van)).isEqualTo(200);
            assertThat(costs.getBackwardTransportTime(loc(0),loc(2),300,null,van)).isEqualTo(40);
            assertThat(size(costs,"costCache")).isLessThanOrEqualTo(2);
        }
    }

    @Test
    void concurrentMissesDoNotGrowPastLimitOrCorruptResults() throws Exception {
        var costs=builder().setCacheLimits(32,8).build();
        try(var workers=Executors.newFixedThreadPool(4)) {
            var jobs=new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for(int thread=0;thread<8;thread++) jobs.add(workers.submit(() -> {
                var van=vehicle("van");
                for(int i=0;i<100;i++) {
                    double time=i*300;
                    assertThat(costs.getTransportCost(loc(0),loc(2),time,null,van)).isEqualTo(200);
                    assertThat(costs.getTransportTime(loc(0),loc(2),time,null,van)).isEqualTo(time==0?20:40);
                    assertThat(costs.getTransportCost(loc(2),loc(0),time,null,null)).isEqualTo(400);
                }
            }));
            for(var job:jobs)job.get(30,TimeUnit.SECONDS);
        }
        assertThat(size(costs,"costCache")).isLessThanOrEqualTo(32);
        assertThat(size(costs,"neutralCostCache")).isLessThanOrEqualTo(8);
    }

    @Test
    void invalidLimitsFailBeforeAnyRouting() {
        assertThatIllegalArgumentException().isThrownBy(()->builder().setCacheLimits(0,1));
        assertThatIllegalArgumentException().isThrownBy(()->builder().setCacheLimits(1,-1));
    }

    private static int size(Object owner,String name) throws Exception {
        var field=owner.getClass().getDeclaredField(name);field.setAccessible(true);
        Object cache=field.get(owner);
        if(cache instanceof Map<?,?> map)return map.size();
        var values=cache.getClass().getDeclaredField("values");values.setAccessible(true);
        return ((Map<?,?>)values.get(cache)).size();
    }
    private static Location loc(int i){return Location.newInstance("f"+i);}
    private static VehicleImpl vehicle(String type){return VehicleImpl.Builder.newInstance(type)
        .setType(VehicleTypeImpl.Builder.newInstance(type).build()).setStartLocation(loc(0)).build();}
    private static NetworkBasedTransportCosts.Builder builder() {
        var network=NetworkUtils.createNetwork();
        var nodes=new org.matsim.api.core.v01.network.Node[11];
        for(int i=0;i<nodes.length;i++)nodes[i]=NetworkUtils.createAndAddNode(network,Id.createNodeId(i),new Coord(i*100,0));
        for(int i=0;i<10;i++) {
            NetworkUtils.createAndAddLink(network,Id.createLinkId("f"+i),nodes[i],nodes[i+1],100,10,1000,1);
            NetworkUtils.createAndAddLink(network,Id.createLinkId("r"+i),nodes[i+1],nodes[i],100,10,1000,1);
        }
        var b=NetworkBasedTransportCosts.Builder.newInstance(network);
        b.addVehicleTypeSpecificCosts("van",0,0,1);b.addVehicleTypeSpecificCosts("special",0,0,2);
        b.setTimeSliceWidth(300);b.setTravelTime((l,t,p,v)->l.getLength()/(t>=300?5:10));return b;
    }
}
