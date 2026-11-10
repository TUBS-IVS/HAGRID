package hagrid.core.routing;

import com.graphhopper.jsprit.core.problem.Location;
import hagrid.core.HagridConfig;
import hagrid.core.config.*;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.*;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.turnRestrictions.DisallowedNextLinks;
import org.matsim.freight.carriers.*;
import org.matsim.vehicles.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import static org.assertj.core.api.Assertions.*;

class ZoneFallbackTest {
    @Test void exactOriginalEndpointsKeepTurnRestrictionsAndSeparateCacheKeys() {
        try(var f=new Fixture()) {
            assertThat(f.context.network().getLinks().get(Id.createLinkId("b")).getAttributes().getAttribute("zone")).isEqualTo(1);
            assertThat(f.cost("b","c")).isEqualTo(200);
            assertThat(f.cost("b","d")).isEqualTo(400);
            long computations=f.context.statistics().computations();
            assertThat(f.cost("b","c")).isEqualTo(200);
            assertThat(f.context.statistics().computations()).isEqualTo(computations);
            assertThat(f.context.statistics().exactQueries()).isEqualTo(2);
        }
    }
    @Test void unreachablePairsAndOtherFailuresKeepTheirMeaning() {
        try(var f=new Fixture()) {
            assertThatExceptionOfType(NoRouteException.class).isThrownBy(() -> f.cost("a","d"));
            assertThatIllegalArgumentException().isThrownBy(() -> f.cost("unknown","c"));
            Thread.currentThread().interrupt();
            try { assertThatExceptionOfType(CancellationException.class).isThrownBy(() -> f.cost("b","c")); }
            finally { Thread.interrupted(); }
            assertThatExceptionOfType(TravelTimeSnapshot.RangeException.class).isThrownBy(() -> f.costs.getTransportTime(
                    Location.newInstance("b"),Location.newInstance("c"),f.context.snapshot().horizonSeconds(),null,null));
        }
    }
    @Test void cacheDisabledStillUsesExactEndpointsAndRetainsNoEntries() {
        try(var f=new Fixture(false)) {
            assertThat(f.cost("b","c")).isEqualTo(200);assertThat(f.cost("b","c")).isEqualTo(200);
            assertThat(f.context.statistics().cacheEntries()).isZero();
        }
    }
    @Test void concurrentExactRequestsShareOneCompletedPair() throws Exception {
        try(var f=new Fixture()) {
            var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
            try {
                var one=pool.submit(()->f.cost("b","c"));var two=pool.submit(()->f.cost("b","c"));
                assertThat(one.get(5,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(200);
                assertThat(two.get(5,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(200);
                assertThat(f.cost("b","c")).isEqualTo(200);
                assertThat(f.context.statistics().exactQueries()).isEqualTo(1);
            } finally {pool.shutdownNow();}
        }
    }
    private static final class Fixture implements AutoCloseable {
        final RoutingContext context;final ModalTransportCosts costs;
        Fixture(){this(true);}
        Fixture(boolean cache) {
            var n=NetworkUtils.createNetwork();var coords=List.of(new Coord(0,0),new Coord(1000,0),new Coord(1000,1000),new Coord(0,1000));
            for(int i=0;i<4;i++)NetworkUtils.createAndAddNode(n,Id.createNodeId(i),coords.get(i));
            String[] ids={"a","b","c","d"};
            for(int i=0;i<4;i++) {
                var l=NetworkUtils.createAndAddLink(n,Id.createLinkId(ids[i]),n.getNodes().get(Id.createNodeId(i)),n.getNodes().get(Id.createNodeId((i+1)%4)),1000,10,1000,1);
                l.setAllowedModes(Set.of("car"));l.getAttributes().putAttribute("zone",i<2?1:2);
            }
            var restriction=new DisallowedNextLinks();restriction.addDisallowedLinkSequence("car",List.of(Id.createLinkId("b")));
            NetworkUtils.setDisallowedNextLinks(n.getLinks().get(Id.createLinkId("a")),restriction);
            var type=VehicleUtils.createVehicleType(Id.create("van",VehicleType.class),"car");type.setMaximumVelocity(10);
            type.getCostInformation().setFixedCost(0.0).setCostsPerMeter(.1).setCostsPerSecond(1.0);
            var types=new CarrierVehicleTypes();types.getVehicleTypes().put(type.getId(),type);
            var carrier=CarriersUtils.createCarrier(Id.create("c",Carrier.class));CarriersUtils.setCarrierMode(carrier,"car");
            var config=HagridConfigResolver.resolve(new HagridConfig(),HagridConfigResolver.Overrides.empty(),
                    HagridConfigResolver.Overrides.of(Map.of("routing.zoneBasedCachingThresholdMeters","0","routing.transportCacheEnabled",Boolean.toString(cache))),Map.of());
            var times=TravelTimeSnapshot.capture(n,Map.of(),Map.of("car",(l,t,p,v)->l.getLength()/10),0,2000,60);
            context=RoutingContext.create(n,types,times,config);costs=new ModalTransportCosts(new RoutingRequest(carrier,context,"car"));
        }
        double cost(String from,String to){return costs.getTransportCost(Location.newInstance(from),Location.newInstance(to),0,null,
                com.graphhopper.jsprit.core.problem.vehicle.VehicleImpl.Builder.newInstance("v")
                        .setType(com.graphhopper.jsprit.core.problem.vehicle.VehicleTypeImpl.Builder.newInstance("van").setMaxVelocity(10).setCostPerDistance(.1).setCostPerTransportTime(1).build())
                        .setStartLocation(Location.newInstance("b")).build());}
        public void close(){context.close();}
    }
}
