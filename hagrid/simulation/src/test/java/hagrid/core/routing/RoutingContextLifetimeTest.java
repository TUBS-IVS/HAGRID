package hagrid.core.routing;

import com.graphhopper.jsprit.core.problem.Location;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleImpl;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleTypeImpl;
import hagrid.core.HagridConfig;
import hagrid.core.config.HagridConfigResolver;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.*;
import org.matsim.core.network.NetworkUtils;
import org.matsim.freight.carriers.*;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import static org.junit.jupiter.api.Assertions.*;

class RoutingContextLifetimeTest {
    @Test void timeCostAndDistanceShareOneOwnedCacheEntry() {
        try(var f=fixture(10,Map.of())) {
            assertEquals(0,f.context.statistics().preparedGraphs());
            var costs=f.costs(); assertEquals(0,f.context.statistics().preparedGraphs());
            assertEquals(20,costs.getTransportTime(from(),to(),0,null,f.vehicle));
            assertEquals(40,costs.getTransportCost(from(),to(),0,null,f.vehicle));
            assertEquals(300,costs.getDistance(from(),to(),0,f.vehicle));
            var stats=f.context.statistics();
            assertEquals(1,stats.cacheEntries());assertEquals(1,stats.computations());assertEquals(2,stats.cacheHits());
            assertEquals(1,stats.preparedGraphs());assertEquals(1,stats.createdRouters());
        }
    }
    @Test void evictionBoundsMemoryAndRecomputesCorrectly() {
        try(var f=fixture(10,Map.of("routing.maximumCacheEntries","1"))) {
            var costs=f.costs();
            assertEquals(20,costs.getTransportTime(from(),to(),0,null,f.vehicle));
            assertEquals(10,costs.getTransportTime(Location.newInstance("middle"),to(),0,null,f.vehicle));
            assertEquals(20,costs.getTransportTime(from(),to(),0,null,f.vehicle));
            assertEquals(1,f.context.statistics().cacheEntries());assertEquals(3,f.context.statistics().computations());
            assertEquals(2,f.context.statistics().evictions());
        }
    }
    @Test void neutralPreparationNeverCollidesWithRegisteredVehicleCosts() {
        try(var f=fixture(10,Map.of())) {
            var costs=f.costs();
            assertEquals(20,costs.getTransportCost(from(),to(),0,null,null));
            assertEquals(40,costs.getTransportCost(from(),to(),0,null,f.vehicle));
            assertEquals(2,f.context.statistics().cacheEntries());assertEquals(2,f.context.statistics().computations());
        }
    }
    @Test void contextsNeverShareCachedValues() {
        try(var first=fixture(10,Map.of());var second=fixture(20,Map.of())) {
            var a=first.costs();var b=second.costs();
            assertEquals(20,a.getTransportTime(from(),to(),0,null,first.vehicle));
            assertEquals(40,b.getTransportTime(from(),to(),0,null,second.vehicle));
            first.context.close();
            assertEquals(40,b.getTransportTime(from(),to(),0,null,second.vehicle));
            assertEquals(1,second.context.statistics().cacheEntries());assertEquals(1,second.context.statistics().cacheHits());
        }
    }
    @Test void failedQueryCanRetryAndReturnsTheWorker() {
        try(var f=fixture(10,Map.of())) {
            var costs=f.costs();
            assertThrows(IllegalArgumentException.class,()->costs.getTransportTime(from(),to(),3599,null,f.vehicle));
            assertEquals(20,costs.getTransportTime(from(),to(),0,null,f.vehicle));
            assertEquals(1,f.context.statistics().cacheEntries());assertEquals(1,f.context.statistics().computations());
            assertEquals(1,f.context.statistics().createdRouters());assertEquals(0,f.context.statistics().activeRouters());
        }
    }
    @Test void explicitCacheDisableKeepsWorkersReusable() {
        try(var f=fixture(10,Map.of("routing.transportCacheEnabled","false"))) {
            var costs=f.costs();
            for(int i=0;i<3;i++) assertEquals(20,costs.getTransportTime(from(),to(),0,null,f.vehicle));
            assertEquals(0,f.context.statistics().cacheEntries());assertEquals(3,f.context.statistics().computations());
            assertEquals(1,f.context.statistics().createdRouters());
        }
    }
    @Test void routerOwnershipDoesNotGrowWithHistoricalCallerThreads() throws Exception {
        try(var f=fixture(10,Map.of("routing.transportCacheEnabled","false"))) {
            var costs=f.costs(); var failure=new AtomicReference<Throwable>();
            for(int i=0;i<6;i++) {
                var thread=new Thread(()->{try{assertEquals(20,costs.getTransportTime(from(),to(),0,null,f.vehicle));}catch(Throwable e){failure.set(e);}},"bounded-routing-caller");
                thread.start();thread.join(5000);assertFalse(thread.isAlive());
            }
            assertNull(failure.get()); assertEquals(1,f.context.statistics().createdRouters());
            assertEquals(6,f.context.statistics().computations());
        }
    }
    @Test void closeWaitsForOwnLeaseAndRejectsNewWorkWithoutClosingAnotherContext() throws Exception {
        try(var f=fixture(10,Map.of());var other=fixture(20,Map.of())) {
            var costs=f.costs();var unaffected=other.costs();
            var acquired=new CountDownLatch(1);var release=new CountDownLatch(1);
            var executor=Executors.newFixedThreadPool(2);
            try {
                var active=executor.submit(()->{try(var lease=f.context.acquireQuery()){acquired.countDown();release.await(5,TimeUnit.SECONDS);}return true;});
                assertTrue(acquired.await(5,TimeUnit.SECONDS));
                var closing=executor.submit(f.context::close);
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                while(!f.context.isClosing() && System.nanoTime()<deadline) LockSupport.parkNanos(1000000);
                assertTrue(f.context.isClosing()); assertFalse(closing.isDone());
                assertThrows(IllegalStateException.class,()->costs.getTransportTime(from(),to(),0,null,f.vehicle));
                assertEquals(40,unaffected.getTransportTime(from(),to(),0,null,other.vehicle));
                release.countDown(); assertTrue(active.get(5,TimeUnit.SECONDS)); closing.get(5,TimeUnit.SECONDS);
                assertEquals(0,f.context.statistics().cacheEntries());assertEquals(0,f.context.statistics().preparedGraphs());
                assertEquals(0,f.context.statistics().createdRouters());
            } finally {release.countDown();executor.shutdownNow();}
        }
    }
    @Test void closingFromOwnActiveQueryFailsClearlyRatherThanDeadlocking() {
        try(var f=fixture(10,Map.of())) {
            try(var lease=f.context.acquireQuery()){assertThrows(IllegalStateException.class,f.context::close);}
            assertFalse(f.context.isClosing());assertEquals(20,f.costs().getTransportTime(from(),to(),0,null,f.vehicle));
        }
    }
    @Test void warmedCacheNeverExtendsTheCapturedTripHorizon() {
        for(boolean enabled:List.of(true,false)) try(var f=fixture(10,Map.of("routing.transportCacheEnabled",Boolean.toString(enabled)))) {
            var costs=f.costs();
            assertEquals(20,costs.getTransportTime(from(),to(),0,null,f.vehicle));
            assertThrows(IllegalArgumentException.class,()->costs.getTransportTime(from(),to(),3599,null,f.vehicle));
            assertThrows(IllegalArgumentException.class,()->costs.getTransportTime(Location.newInstance("middle"),to(),3599,null,f.vehicle));
        }
    }
    @Test void unreachableRoadUsesBoundedNegativeCacheWithoutLosingAWorker() {
        try(var f=fixture(10,Map.of())) {
            var costs=f.costs();
            for(int i=0;i<2;i++) assertThrows(NoRouteException.class,()->costs.getTransportTime(to(),from(),0,null,f.vehicle));
            assertEquals(1,f.context.statistics().cacheEntries());assertEquals(1,f.context.statistics().computations());assertEquals(1,f.context.statistics().cacheHits());
            assertEquals(20,costs.getTransportTime(from(),to(),0,null,f.vehicle));
            assertEquals(2,f.context.statistics().cacheEntries());assertEquals(2,f.context.statistics().computations());
            assertEquals(1,f.context.statistics().createdRouters());assertEquals(0,f.context.statistics().activeRouters());
        }
    }
    private static Location from(){return Location.newInstance("origin");}
    private static Location to(){return Location.newInstance("destination");}
    private static Fixture fixture(double seconds,Map<String,String> cli) {
        var n=NetworkUtils.createNetwork();
        var a=NetworkUtils.createAndAddNode(n,Id.createNodeId("a"),new Coord(0,0));
        var b=NetworkUtils.createAndAddNode(n,Id.createNodeId("b"),new Coord(80,0));
        var c=NetworkUtils.createAndAddNode(n,Id.createNodeId("c"),new Coord(180,0));
        var d=NetworkUtils.createAndAddNode(n,Id.createNodeId("d"),new Coord(380,0));
        var origin=NetworkUtils.createAndAddLink(n,Id.createLinkId("origin"),a,b,80,10,1000,1);
        var middle=NetworkUtils.createAndAddLink(n,Id.createLinkId("middle"),b,c,100,10,1000,1);
        var destination=NetworkUtils.createAndAddLink(n,Id.createLinkId("destination"),c,d,200,10,1000,1);
        for(var link:List.of(origin,middle,destination)) link.setAllowedModes(Set.of("car"));
        var type=VehicleUtils.createVehicleType(Id.create("van-type",VehicleType.class),"car");type.setMaximumVelocity(Double.POSITIVE_INFINITY);
        type.getCostInformation().setFixedCost(1.0).setCostsPerMeter(0.0).setCostsPerSecond(2.0);
        var types=new CarrierVehicleTypes();types.getVehicleTypes().put(type.getId(),type);
        var carrier=CarriersUtils.createCarrier(Id.create("carrier",Carrier.class));
        carrier.setCarrierCapabilities(CarrierCapabilities.Builder.newInstance().addVehicle(CarrierVehicle.newInstance(Id.createVehicleId("van"),origin.getId(),type)).build());
        var jt=VehicleTypeImpl.Builder.newInstance(type.getId().toString()).setMaxVelocity(Double.POSITIVE_INFINITY).setFixedCost(1).setCostPerDistance(0).setCostPerTransportTime(2).build();
        var vehicle=VehicleImpl.Builder.newInstance("van").setType(jt).setStartLocation(from()).build();
        var config=HagridConfigResolver.resolve(new HagridConfig(),HagridConfigResolver.hannoverTarget(),HagridConfigResolver.Overrides.empty(),cli);
        var snapshot=TravelTimeSnapshot.capture(n,Map.of("car",(l,t,p,v)->seconds),Map.of(),24,3600,100);
        return new Fixture(carrier,RoutingContext.create(n,types,snapshot,config),vehicle);
    }
    private record Fixture(Carrier carrier,RoutingContext context,com.graphhopper.jsprit.core.problem.vehicle.Vehicle vehicle) implements AutoCloseable {
        ModalTransportCosts costs(){return new ModalTransportCosts(new RoutingRequest(carrier,context,"car"));}
        public void close(){context.close();}
    }
}