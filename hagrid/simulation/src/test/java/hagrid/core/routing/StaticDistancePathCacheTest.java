package hagrid.core.routing;

import com.graphhopper.jsprit.core.problem.Location;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleImpl;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleTypeImpl;
import hagrid.core.HagridConfig;
import hagrid.core.config.HagridConfigResolver;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.*;
import org.matsim.api.core.v01.network.*;
import org.matsim.core.network.NetworkUtils;
import org.matsim.freight.carriers.*;
import org.matsim.vehicles.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Direct solver costs must reuse a path, not repeat the expensive link-state Dijkstra. */
class StaticDistancePathCacheTest {
    static final double RATE=0.000386430173292559;
    static final Location FROM=Location.newInstance("origin"),TO=Location.newInstance("destination");

    @Test void repeatedSolverDistanceAndCostQueriesAcrossDeparturesSearchOnlyOnce() {
        try(var f=fixture(Map.of())) {
            var solver=f.costs().solverCosts();
            assertEquals(100*RATE,solver.getTransportCost(FROM,TO,0,null,f.vehicle),1e-12);
            assertEquals(100,solver.getDistance(FROM,TO,900,f.vehicle));
            assertEquals(100*RATE,solver.getTransportCost(FROM,TO,1800,null,f.vehicle),1e-12);
            assertEquals(1,f.context.statistics().computations());
            assertEquals(1,f.context.statistics().cacheEntries());
            assertEquals(2,f.context.statistics().cacheHits());
        }
    }

    @Test void reusedPathStillReplaysActualTimeAndReturnsDetachedMutableRoadRoutes() {
        try(var f=fixture(Map.of())) {
            var costs=f.costs();var solver=costs.solverCosts();
            assertEquals(7,solver.getTransportTime(FROM,TO,0,null,f.vehicle));
            var road=costs.routeLeg(Id.createLinkId("origin"),Id.createLinkId("destination"),900,f.nativeVehicle);
            assertEquals(5.5,road.travelTimeSeconds());
            road.route().setVehicleId(Id.createVehicleId("changed"));
            road.route().setLinkIds(Id.createLinkId("origin"),List.of(Id.createLinkId("return")),Id.createLinkId("destination"));
            var again=costs.routeLeg(Id.createLinkId("origin"),Id.createLinkId("destination"),0,f.nativeVehicle);
            assertEquals(List.of(),again.route().getLinkIds());
            assertEquals(f.nativeVehicle.getId(),again.route().getVehicleId());
            assertEquals(7,again.travelTimeSeconds());
            assertEquals(100*RATE,again.monetaryCost(),1e-12);
            assertEquals(1,f.context.statistics().computations());
        }
    }

    @Test void disabledCacheRepeatsSearchAndRetainsNoPath() {
        try(var f=fixture(Map.of("routing.transportCacheEnabled","false"))) {
            var solver=f.costs().solverCosts();
            for(int i=0;i<3;i++)assertEquals(100,solver.getDistance(FROM,TO,i,f.vehicle));
            assertEquals(0,f.context.statistics().cacheEntries());
            assertEquals(3,f.context.statistics().computations());
            assertEquals(0,f.context.statistics().cacheHits());
        }
    }

    @Test void staticAndOrdinaryOdEntriesShareTheConfiguredLimitAndLruEviction() {
        try(var f=fixture(Map.of("routing.maximumCacheEntries","2"))) {
            var costs=f.costs();var solver=costs.solverCosts();
            assertEquals(100,solver.getDistance(FROM,TO,0,f.vehicle));
            assertEquals(100*RATE,costs.getTransportCost(FROM,TO,0,null,f.vehicle),1e-12);
            assertEquals(2,f.context.statistics().cacheEntries());
            assertEquals(200,solver.getDistance(FROM,Location.newInstance("return"),0,f.vehicle));
            assertEquals(2,f.context.statistics().cacheEntries());
            assertEquals(3,f.context.statistics().computations());
            assertEquals(100,solver.getDistance(FROM,TO,0,f.vehicle));
            assertEquals(4,f.context.statistics().computations());
            assertEquals(2,f.context.statistics().cacheEntries());
        }
    }

    @Test void profileCostsDoNotLeakBetweenStaticPathEntries() {
        try(var f=fixture(Map.of())) {
            var solver=f.costs().solverCosts();
            assertEquals(100*RATE,solver.getTransportCost(FROM,TO,0,null,f.vehicle),1e-12);
            assertEquals(200*RATE,solver.getTransportCost(FROM,TO,900,null,f.other),1e-12);
            assertEquals(100*RATE,solver.getTransportCost(FROM,TO,1800,null,f.vehicle),1e-12);
            assertEquals(2,f.context.statistics().computations());
            assertEquals(2,f.context.statistics().cacheEntries());
        }
    }

    @Test void concurrentSolverAndRoadQueriesReuseOneStaticSearchWithoutNestedAdmissionDeadlock() throws Exception {
        try(var f=fixture(Map.of())) {
            var costs=f.costs();var solver=costs.solverCosts();
            var pool=Executors.newFixedThreadPool(4);var start=new CountDownLatch(1);
            try {
                var work=new ArrayList<Future<Double>>();
                for(int i=0;i<16;i++) {
                    final boolean early=i%2==0;
                    work.add(pool.submit(()->{start.await();return solver.getTransportTime(FROM,TO,early?0:900,null,f.vehicle);}));
                }
                start.countDown();
                for(int i=0;i<work.size();i++)assertEquals(i%2==0?7:5.5,work.get(i).get(10,TimeUnit.SECONDS));
                assertEquals(1,f.context.statistics().computations());
                assertEquals(15,f.context.statistics().cacheHits());
                assertEquals(0,f.context.statistics().activeRouters());
            } finally {pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
        }
    }

    @Test void unreachableStaticPathIsReusedWithoutInventingFiniteCosts() {
        try(var f=fixture(Map.of())) {
            var solver=f.costs().solverCosts();
            for(int i=0;i<3;i++)assertThrows(NoRouteException.class,()->solver.getDistance(FROM,Location.newInstance("isolated"),0,f.vehicle));
            assertEquals(1,f.context.statistics().computations());
            assertEquals(2,f.context.statistics().cacheHits());
            assertEquals(1,f.context.statistics().cacheEntries());
        }
    }

    @Test void closedContextCannotServeCachedPathsAndNewContextStartsFresh() {
        var first=fixture(Map.of());var costs=first.costs().solverCosts();
        assertEquals(100,costs.getDistance(FROM,TO,0,first.vehicle));first.close();
        assertThrows(IllegalStateException.class,()->costs.getDistance(FROM,TO,0,first.vehicle));
        try(var second=fixture(Map.of())) {
            assertEquals(100,second.costs().solverCosts().getDistance(FROM,TO,0,second.vehicle));
            assertEquals(1,second.context.statistics().computations());
            assertEquals(0,second.context.statistics().cacheHits());
        }
    }

    @Test void cachedRestrictedPathKeepsItsDetourAndReplaysIntermediateBoundaryCrossings() {
        try(var f=fixture(Map.of(),n->{
            var b=n.getNodes().get(Id.createNodeId("b"));var z=node(n,"z",150);
            link(n,"x",b,z);link(n,"y",z,b);
            var forbidden=new org.matsim.core.network.turnRestrictions.DisallowedNextLinks();
            forbidden.addDisallowedLinkSequence("car",List.of(Id.createLinkId("destination")));
            NetworkUtils.setDisallowedNextLinks(n.getLinks().get(Id.createLinkId("origin")),forbidden);
        })) {
            var costs=f.costs();
            var early=costs.routeLeg(Id.createLinkId("origin"),Id.createLinkId("destination"),0,f.nativeVehicle);
            var crossing=costs.routeLeg(Id.createLinkId("origin"),Id.createLinkId("destination"),895,f.nativeVehicle);
            assertEquals(List.of(Id.createLinkId("x"),Id.createLinkId("y")),early.route().getLinkIds());
            assertEquals(early.route().getLinkIds(),crossing.route().getLinkIds());
            assertEquals(21,early.travelTimeSeconds());
            assertEquals(18,crossing.travelTimeSeconds());
            assertEquals(300*RATE,crossing.monetaryCost(),1e-12);
            assertEquals(1,f.context.statistics().computations());
        }
    }

    static Fixture fixture(Map<String,String> overrides) {
        return fixture(overrides,n->{});
    }
    static Fixture fixture(Map<String,String> overrides,java.util.function.Consumer<Network> configure) {
        var n=NetworkUtils.createNetwork();
        Node a=node(n,"a",0),b=node(n,"b",100),c=node(n,"c",200),x=node(n,"x",1000),y=node(n,"y",1100);
        link(n,"origin",a,b);link(n,"destination",b,c);link(n,"return",c,a);link(n,"isolated",x,y);
        configure.accept(n);
        var types=new CarrierVehicleTypes();var carrier=CarriersUtils.createCarrier(Id.create("carrier",Carrier.class));
        var type=type("L",RATE);var other=type("other",2*RATE);
        types.getVehicleTypes().put(type.getId(),type);types.getVehicleTypes().put(other.getId(),other);
        CarriersUtils.addCarrierVehicle(carrier,CarrierVehicle.newInstance(Id.createVehicleId("van"),Id.createLinkId("origin"),type));
        var config=HagridConfigResolver.resolve(new HagridConfig(),HagridConfigResolver.hannoverTarget(),
                HagridConfigResolver.Overrides.of(overrides),Map.of());
        var snapshot=TravelTimeSnapshot.capture(n,Map.of("car",(org.matsim.core.router.util.TravelTime)(l,t,p,v)->t<900?7:5.5),Map.of(),24,10800,900);
        return new Fixture(carrier,RoutingContext.create(n,types,snapshot,config),jsprit("L",RATE),jsprit("other",2*RATE),
                VehicleUtils.createVehicle(Id.createVehicleId("van"),type));
    }
    static Node node(Network n,String id,double x){return NetworkUtils.createAndAddNode(n,Id.createNodeId(id),new Coord(x,0));}
    static void link(Network n,String id,Node a,Node b){NetworkUtils.createAndAddLink(n,Id.createLinkId(id),a,b,100,10,1000,1).setAllowedModes(Set.of("car"));}
    static VehicleType type(String id,double rate){var t=VehicleUtils.createVehicleType(Id.create(id,VehicleType.class),"car");t.setMaximumVelocity(Double.POSITIVE_INFINITY);t.getCostInformation().setFixedCost(189.15).setCostsPerMeter(rate).setCostsPerSecond(0.0);return t;}
    static com.graphhopper.jsprit.core.problem.vehicle.Vehicle jsprit(String id,double rate){return VehicleImpl.Builder.newInstance(id).setType(VehicleTypeImpl.Builder.newInstance(id).setMaxVelocity(Double.POSITIVE_INFINITY).setFixedCost(189.15).setCostPerDistance(rate).setCostPerTransportTime(0).build()).setStartLocation(FROM).build();}
    record Fixture(Carrier carrier,RoutingContext context,com.graphhopper.jsprit.core.problem.vehicle.Vehicle vehicle,
                   com.graphhopper.jsprit.core.problem.vehicle.Vehicle other,org.matsim.vehicles.Vehicle nativeVehicle) implements AutoCloseable {
        ModalTransportCosts costs(){return new ModalTransportCosts(new RoutingRequest(carrier,context,"car"));}
        public void close(){context.close();}
    }
}
