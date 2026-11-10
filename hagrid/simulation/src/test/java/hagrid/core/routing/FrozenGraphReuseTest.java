package hagrid.core.routing;

import org.junit.jupiter.api.Test;
import org.matsim.freight.carriers.CarrierVehicleTypes;
import static org.junit.jupiter.api.Assertions.*;

class FrozenGraphReuseTest {
    @Test void homogeneousModeReusesTheAlreadyDetachedImmutableGraph() {
        try(var f=RawDistanceTemporalRoutingTest.fixture((l,t,p,v)->t<10?20:3,10,10,100)) {
            var frozen=f.context().network();
            var mode=RoutingNetworkSnapshot.forMode(frozen,"car");
            assertSame(frozen,mode,"A graph whose links already permit only car needs no mode copy");
            assertThrows(UnsupportedOperationException.class,()->mode.getLinks().clear());
            assertThrows(UnsupportedOperationException.class,()->mode.getLinks().values().iterator().next().setLength(4));
        }
    }

    @Test void anotherContextReusesFrozenGraphButRetainsItsOwnProfileAndLifetime() {
        try(var f=RawDistanceTemporalRoutingTest.fixture((l,t,p,v)->t<10?20:3,10,10,100)) {
            var types=new CarrierVehicleTypes();
            f.carrier().getCarrierCapabilities().getCarrierVehicles().values().forEach(v->types.getVehicleTypes().put(v.getType().getId(),v.getType()));
            var frozen=f.context().network();
            try(var other=RoutingContext.create(frozen,types,f.context().snapshot(),f.context().config())) {
                assertSame(frozen,other.network(),"A certified immutable graph should not be copied per context");
                assertEquals(f.context().fingerprint(),other.fingerprint());
                f.close();
                assertFalse(other.network().getLinks().isEmpty());
            }
        }
    }
}
