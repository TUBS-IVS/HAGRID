package hagrid.core.routing;

import com.graphhopper.jsprit.core.problem.Location;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleImpl;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleTypeImpl;
import hagrid.core.HagridConfig;
import hagrid.core.config.HagridConfigResolver;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.*;
import org.matsim.api.core.v01.network.*;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.freight.carriers.*;
import org.matsim.freight.carriers.controller.CarrierTimeAndSpaceTourRouter;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TransportProviderContractTest {
    @Test void oneFactoryHandlesCarAndBikeWithCorrectTargetLinkTime() {
        try(var f=fixture()) {
            var factory=new DefaultVRPTransportCostsFactory();
            var costs=factory.createVRPTransportCosts(new RoutingRequest(f.carrier,f.context,"bike"));
            assertInstanceOf(LinkRouteProvider.class,costs);
            for(String mode:List.of("car","bike")) {
                var vehicle=VehicleUtils.createVehicle(Id.createVehicleId(mode),f.types.get(mode));
                var road=((LinkRouteProvider)costs).routeLeg(Id.createLinkId("origin"),Id.createLinkId("destination"),0,vehicle);
                assertEquals(mode.equals("car")?15:36,road.travelTimeSeconds(),1e-9);
                assertEquals(costs.getTransportTime(from(),to(),0,null,jspritVehicle(f.types.get(mode))),road.travelTimeSeconds(),1e-9);
                assertEquals(List.of(Id.createLinkId("destination")),List.of(road.route().getEndLinkId()));
            }
        }
    }
    @Test void exactProviderNeedsNoZoneClassAndPreparationModeIsExplicit() {
        try(var f=fixture()) {
            var costs=new DefaultVRPTransportCostsFactory().createVRPTransportCosts(new RoutingRequest(f.carrier,f.context,"bike"));
            assertEquals(36,costs.getTransportTime(from(),to(),0,null,null),1e-9);
            assertThrows(IllegalArgumentException.class,()->new RoutingRequest(f.carrier,f.context,""));
            assertEquals(18,costs.getTravelTime().getLinkTravelTime(f.context.network().getLinks().get(Id.createLinkId("destination")),0,null,null),1e-9);
        }
    }
    @Test void closingContextWaitsForActiveLease() throws Exception {
        var f=fixture(); var factory=new DefaultVRPTransportCostsFactory();
        var costs=factory.createVRPTransportCosts(new RoutingRequest(f.carrier,f.context,"bike"));
        var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<?> close;
            try(var lease=f.context.acquireQuery()) {
                close=pool.submit(f.context::close);
                long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                while(!f.context.isClosing() && System.nanoTime()<deadline)java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
                assertTrue(f.context.isClosing());assertFalse(close.isDone());
            }
            close.get(5,java.util.concurrent.TimeUnit.SECONDS);
            assertThrows(IllegalStateException.class,()->((ReplanningTransportCosts)costs).prepareForReplanning());
        } finally {pool.shutdownNow();f.close();}
    }
    private static Location from(){return Location.newInstance("origin");}
    private static Location to(){return Location.newInstance("destination");}
    private static com.graphhopper.jsprit.core.problem.vehicle.Vehicle jspritVehicle(VehicleType type) {
        var costs=type.getCostInformation();
        var jt=VehicleTypeImpl.Builder.newInstance(type.getId().toString()).setMaxVelocity(type.getMaximumVelocity())
                .setFixedCost(costs.getFixedCosts()).setCostPerDistance(costs.getCostsPerMeter()).setCostPerTransportTime(costs.getCostsPerSecond()).build();
        return VehicleImpl.Builder.newInstance(type.getId().toString()).setType(jt).setStartLocation(from()).build();
    }
    private static Fixture fixture() {
        var scenario=ScenarioUtils.createScenario(ConfigUtils.createConfig()); var n=scenario.getNetwork();
        var a=NetworkUtils.createAndAddNode(n,Id.createNodeId("a"),new Coord(0,0));
        var b=NetworkUtils.createAndAddNode(n,Id.createNodeId("b"),new Coord(80,0));
        var c=NetworkUtils.createAndAddNode(n,Id.createNodeId("c"),new Coord(180,0));
        var d=NetworkUtils.createAndAddNode(n,Id.createNodeId("d"),new Coord(280,0));
        var e=NetworkUtils.createAndAddNode(n,Id.createNodeId("e"),new Coord(130,100));
        link(n,"origin",a,b,80,Set.of("car","bike")); link(n,"destination",c,d,100,Set.of("car","bike"));
        link(n,"car1",b,e,100,Set.of("car")); link(n,"car2",e,c,100,Set.of("car"));
        link(n,"bike-shortcut",b,c,100,Set.of("bike")); link(n,"return",d,a,100,Set.of("car","bike"));
        var registry=new CarrierVehicleTypes(); var types=new HashMap<String,VehicleType>();
        for(String mode:List.of("car","bike")) {
            var type=VehicleUtils.createVehicleType(Id.create(mode+"-type",VehicleType.class),mode);
            type.setMaximumVelocity(mode.equals("bike") ? 20.0/3.6 : 30);
            type.getCostInformation().setFixedCost(1.0).setCostsPerMeter(0.2).setCostsPerSecond(1.0);
            registry.getVehicleTypes().put(type.getId(),type); types.put(mode,type);
        }
        var carrier=CarriersUtils.createCarrier(Id.create("bike-carrier",Carrier.class));
        carrier.setCarrierCapabilities(CarrierCapabilities.Builder.newInstance().addVehicle(CarrierVehicle.newInstance(Id.createVehicleId("bike"),Id.createLinkId("origin"),types.get("bike"))).build());
        var cfg=HagridConfigResolver.resolve(new HagridConfig(),HagridConfigResolver.hannoverTarget(),HagridConfigResolver.Overrides.of(Map.of()),Map.of());
        var snapshot=TravelTimeSnapshot.capture(n,Map.of("car",(l,t,p,v)->5,"bike",(l,t,p,v)->12),Map.of(),24,3600,900);
        return new Fixture(scenario,types,carrier,RoutingContext.create(n,registry,snapshot,cfg));
    }
    private static void link(Network n,String id,Node a,Node b,double length,Set<String> modes) {
        var l=NetworkUtils.createAndAddLink(n,Id.createLinkId(id),a,b,length,30,1000,1); l.setAllowedModes(modes);
    }
    private record Fixture(Scenario scenario,Map<String,VehicleType> types,Carrier carrier,RoutingContext context) implements AutoCloseable {
        public void close(){context.close();}
    }

}
