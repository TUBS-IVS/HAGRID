package hagrid.core.simulation;

import hagrid.core.routing.ReplanningTransportCosts;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.freight.carriers.*;
import org.matsim.freight.carriers.jsprit.VRPTransportCosts;
import org.matsim.vehicles.VehicleUtils;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class CarrierRoutingCostsLifecycleTest {
    @Test void sharedUniversalProviderPreparesOnlyOncePerBatchRegardlessOfConcreteClass() {
        var prepared=new AtomicInteger();
        var costs=provider(true,prepared,new AtomicReference<>());
        var registry=new CarrierRoutingCosts(Map.of("car",costs,"cargobike",costs));
        registry.prepare();assertEquals(1,prepared.get());
        registry.prepare();assertEquals(2,prepared.get());
        assertSame(costs,registry.forCarrier(carrier("car")));
        assertSame(costs,registry.forCarrier(carrier("cargobike")));
    }

    @Test void genericProviderReceivesCarrierDiagnosticLabel() {
        var label=new AtomicReference<String>();
        var approximate=provider(true,new AtomicInteger(),label);
        var exact=provider(false,new AtomicInteger(),new AtomicReference<>());
        CarrierRoutingCosts.logSummary(approximate,carrier("cargobike"));
        assertEquals("Carrier carrier-cargobike (cargobike) reroute",label.get());
    }

    @Test void nativeProviderWithoutLifecycleRemainsUsableAndHasNoInventedZoneCapability() {
        VRPTransportCosts nativeCosts=(VRPTransportCosts)Proxy.newProxyInstance(
                VRPTransportCosts.class.getClassLoader(),new Class<?>[]{VRPTransportCosts.class},
                (proxy,method,args)->{throw new AssertionError("Unexpected native callback "+method.getName());});
        var registry=new CarrierRoutingCosts(Map.of("car",nativeCosts));
        registry.prepare();assertSame(nativeCosts,registry.forCarrier(carrier("car")));
        CarrierRoutingCosts.logSummary(nativeCosts,carrier("car"));
    }

    @Test void lifecycleFailureIsReportedBeforeCarrierWorkCanStart() {
        var failure=new IllegalStateException("context unavailable");
        VRPTransportCosts costs=(VRPTransportCosts)Proxy.newProxyInstance(
                ReplanningTransportCosts.class.getClassLoader(),new Class<?>[]{ReplanningTransportCosts.class},
                (proxy,method,args)->{throw failure;});
        assertSame(failure,assertThrows(IllegalStateException.class,()->new CarrierRoutingCosts(Map.of("car",costs)).prepare()));
    }

    private static VRPTransportCosts provider(boolean zones,AtomicInteger prepared,AtomicReference<String> label) {
        return (VRPTransportCosts)Proxy.newProxyInstance(ReplanningTransportCosts.class.getClassLoader(),
                new Class<?>[]{ReplanningTransportCosts.class},(proxy,method,args)->switch(method.getName()){
                    case "prepareForReplanning"->{prepared.incrementAndGet();yield null;}
                    case "logSummary"->{label.set((String)args[0]);yield null;}
                    default->throw new AssertionError("Unexpected routing callback "+method.getName());
                });
    }
    private static Carrier carrier(String mode) {
        var carrier=CarriersUtils.createCarrier(Id.create("carrier-"+mode,Carrier.class));
        CarriersUtils.setCarrierMode(carrier,mode);
        var type=VehicleUtils.createVehicleType(Id.create("type-"+mode,org.matsim.vehicles.VehicleType.class),mode);
        carrier.setCarrierCapabilities(CarrierCapabilities.Builder.newInstance()
                .addVehicle(CarrierVehicle.newInstance(Id.createVehicleId("vehicle-"+mode),Id.createLinkId("depot"),type)).build());
        return carrier;
    }
}