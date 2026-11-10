package hagrid.core.routing;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.vehicles.VehicleUtils;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ModalNetworkPreparationTest {
    @Test void unusedBikeModeDoesNotAllocateGraphAndIdentityStaysStable() {
        try(var f=ModalTransportCostsTest.fixture()) {
            var context=f.context();String fingerprint=context.fingerprint();
            var costs=new DefaultVRPTransportCostsFactory().createVRPTransportCosts(new RoutingRequest(f.carrier(),context,"bike"));
            assertEquals(0,context.statistics().preparedGraphs());
            var car=VehicleUtils.createVehicle(Id.createVehicleId("car"),f.types().get("car"));
            ((LinkRouteProvider)costs).routeLeg(Id.createLinkId("origin"),Id.createLinkId("destination"),0,car);
            assertEquals(1,context.statistics().preparedGraphs());
            assertEquals(fingerprint,context.fingerprint());
            var bike=VehicleUtils.createVehicle(Id.createVehicleId("bike"),f.types().get("bike"));
            assertEquals(36,((LinkRouteProvider)costs).routeLeg(Id.createLinkId("origin"),Id.createLinkId("destination"),0,bike).travelTimeSeconds(),1e-9);
            assertEquals(2,context.statistics().preparedGraphs());
            assertEquals(fingerprint,context.fingerprint());
        }
    }
    @Test void simultaneousFirstModeQueriesPrepareExactlyOneGraph() throws Exception {
        try(var f=ModalTransportCostsTest.fixture()) {
            var costs=new ModalTransportCosts(new RoutingRequest(f.carrier(),f.context(),"bike"));
            var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
            try {
                Callable<Double> query=()->{start.await();return costs.routeLeg(Id.createLinkId("origin"),Id.createLinkId("destination"),0,null).travelTimeSeconds();};
                var a=pool.submit(query);var b=pool.submit(query);start.countDown();
                assertEquals(36,a.get(5,TimeUnit.SECONDS));assertEquals(36,b.get(5,TimeUnit.SECONDS));
                assertEquals(1,f.context().statistics().preparedGraphs());
            } finally {pool.shutdownNow();}
        }
    }
    @Test void unknownModeAndCloseCannotPublishAPartialMode() {
        var f=ModalTransportCostsTest.fixture();
        var costs=new ModalTransportCosts(new RoutingRequest(f.carrier(),f.context(),"bike"));
        assertThrows(IllegalArgumentException.class,()->new RoutingRequest(f.carrier(),f.context(),"absent"));
        assertEquals(0,f.context().statistics().preparedGraphs());f.close();
        assertThrows(IllegalStateException.class,()->costs.routeLeg(Id.createLinkId("origin"),Id.createLinkId("destination"),0,null));
        assertEquals(0,f.context().statistics().preparedGraphs());
    }
}