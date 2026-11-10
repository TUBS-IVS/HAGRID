package hagrid.core.routing;

import com.graphhopper.jsprit.core.problem.Location;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleImpl;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleTypeImpl;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.*;
import org.matsim.api.core.v01.network.*;
import org.matsim.core.network.NetworkUtils;
import org.matsim.freight.carriers.CarrierVehicle;
import org.matsim.freight.carriers.jsprit.VehicleTypeDependentRoadPricingCalculator;
import org.matsim.vehicles.VehicleUtils;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("deprecation")
class LegacyRoutingCostsTest {
    private final Network network=NetworkUtils.createNetwork();
    private final Node a=NetworkUtils.createAndAddNode(network,Id.createNodeId("a"),new Coord(0,0));
    private final Node b=NetworkUtils.createAndAddNode(network,Id.createNodeId("b"),new Coord(100,0));
    private final Link link=NetworkUtils.createAndAddLink(network,Id.createLinkId("link"),a,b,100,10,1000,1);

    private org.matsim.vehicles.Vehicle vehicle(String id) {
        return VehicleUtils.createVehicle(Id.createVehicleId(id),
                VehicleUtils.createVehicleType(Id.create(id,org.matsim.vehicles.VehicleType.class),"car"));
    }
    private VehicleRoutingDisutility costs() {
        return new VehicleRoutingDisutility(Map.of("van",new VehicleRoutingDisutility.VariableCosts(2,3),
                "cargo",new VehicleRoutingDisutility.VariableCosts(1,5)),(l,t,p,v)->7);
    }

    @Test void distanceTimeRatesAndMinimumStayVehicleSpecific() {
        var costs=costs();
        assertEquals(221,costs.getLinkTravelDisutility(link,123,null,vehicle("van")));
        assertEquals(135,costs.getLinkTravelDisutility(link,123,null,vehicle("cargo")));
        assertEquals(150,costs.getLinkMinimumTravelDisutility(link));
    }

    @Test void unknownTypeStillRejectsMissingCostMetadata() {
        assertThrows(IllegalStateException.class,()->costs().getLinkTravelDisutility(link,0,null,vehicle("unknown")));
    }

    @Test void tollUsesActualTypeLinkAndEntryTimeExactlyOnce() {
        var calls=new AtomicInteger();
        var toll=new VehicleTypeDependentRoadPricingCalculator() {
            public double getTollAmount(Id<org.matsim.vehicles.VehicleType> type,Link road,double time) {
                assertEquals("van",type.toString());assertSame(link,road);assertEquals(123,time);
                calls.incrementAndGet();return 7;
            }
        };
        var costs=new TollRoutingDisutility(costs(),toll);
        assertEquals(228,costs.getLinkTravelDisutility(link,123,null,vehicle("van")));
        assertEquals(1,calls.get());assertEquals(150,costs.getLinkMinimumTravelDisutility(link));
    }

    @Test void jspritAdapterRetainsIdSpeedExplicitModeAndIndependentAttributes() {
        var vehicle=VehicleImpl.Builder.newInstance("bike")
                .setType(VehicleTypeImpl.Builder.newInstance("cargo").setMaxVelocity(5).build())
                .setStartLocation(Location.newInstance("link")).build();
        var adapter=new LegacyRoutingVehicle(vehicle,"cargobike");
        assertEquals("bike",adapter.getId().toString());assertEquals("cargo",adapter.getType().getId().toString());
        assertEquals(5,adapter.getType().getMaximumVelocity());assertEquals("cargobike",adapter.getType().getNetworkMode());
        adapter.getAttributes().putAttribute("marker",42);
        assertNull(new LegacyRoutingVehicle(vehicle,"cargobike").getAttributes().getAttribute("marker"));
        assertEquals("car",new ZoneBasedTransportCosts.MatsimVehicleWrapper(vehicle).getType().getNetworkMode());
    }

    @Test void carrierAdapterAndCompatibilityApiRetainNativeTypeIdentity() {
        var type=VehicleUtils.createVehicleType(Id.create("cargo",org.matsim.vehicles.VehicleType.class),"cargobike");
        var vehicle=CarrierVehicle.newInstance(Id.createVehicleId("native"),link.getId(),type);
        assertSame(type,new LegacyRoutingVehicle(vehicle).getType());
        assertSame(type,new ZoneBasedTransportCosts.MatsimVehicleWrapper(vehicle).getType());
    }
}