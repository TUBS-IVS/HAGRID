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

class ModalTransportCostsTest {
    @Test void allAccessPathsUseVehicleMode() {
        try(var f=fixture()) {
            var costs=new ModalTransportCosts(new RoutingRequest(f.carrier,f.context,"bike"));
            for(String mode:List.of("car","bike")) {
                double expected=mode.equals("car") ? 15 : 36;
                var jsprit=jspritVehicle(f.types.get(mode));
                var nativeVehicle=VehicleUtils.createVehicle(Id.createVehicleId(mode),f.types.get(mode));
                var n=costs.getNetwork();
                var path=costs.getRouter().calcLeastCostPath(n.getLinks().get(Id.createLinkId("origin")).getToNode(),
                        n.getLinks().get(Id.createLinkId("destination")).getFromNode(),0,null,nativeVehicle);
                assertNotNull(path);
                assertEquals(mode.equals("car") ? List.of("car1","car2") : List.of("bike-shortcut"),
                        path.links.stream().map(l->l.getId().toString()).toList());
                double destination=costs.getTravelTime().getLinkTravelTime(n.getLinks().get(Id.createLinkId("destination")),path.travelTime,null,nativeVehicle);
                assertEquals(expected,path.travelTime+destination,1e-9);
                assertEquals(expected,costs.getTransportTime(from(),to(),0,null,jsprit),1e-9);
                assertEquals(mode.equals("car") ? 75 : 76,costs.getTransportCost(from(),to(),0,null,jsprit),1e-9);
                // Same driven links as time and cost; destination included, origin excluded.
                assertEquals(mode.equals("car") ? 300 : 200,costs.getDistance(from(),to(),0,jsprit),1e-9);
                assertEquals(expected,costs.getBackwardTransportTime(from(),to(),100,null,jsprit),1e-9);
                assertEquals(mode.equals("car") ? 75 : 76,costs.getBackwardTransportCost(from(),to(),100,null,jsprit),1e-9);
            }
        }
    }
    @Test void actualFreightTourUsesBikeRouterAndDestinationProfile() {
        try(var f=fixture()) {
            var costs=new ModalTransportCosts(new RoutingRequest(f.carrier,f.context,"bike"));
            var service=CarrierService.Builder.newInstance(Id.create("stop",CarrierService.class),Id.createLinkId("destination"))
                    .setServiceDuration(0).build();
            var builder=Tour.Builder.newInstance(Id.create("tour",Tour.class));
            var outbound=new Tour.Leg(); var returning=new Tour.Leg();
            builder.scheduleStart(Id.createLinkId("origin"));
            builder.addLeg(outbound); builder.scheduleService(service); builder.addLeg(returning);
            builder.scheduleEnd(Id.createLinkId("origin"));
            var vehicle=f.carrier.getCarrierCapabilities().getCarrierVehicles().values().iterator().next();
            var tour=ScheduledTour.newInstance(builder.build(),vehicle,0);
            new CarrierTimeAndSpaceTourRouter(costs.getRouter(),costs.getNetwork(),costs.getTravelTime()).route(tour);
            assertEquals(36,outbound.getExpectedTransportTime(),1e-9);
            var route=(org.matsim.core.population.routes.NetworkRoute)outbound.getRoute();
            assertEquals(List.of(Id.createLinkId("bike-shortcut")),route.getLinkIds());
        }
    }
    @Test void nullVehicleUsesDeclaredPreparationMode() {
        try(var f=fixture()) {
            var costs=new ModalTransportCosts(new RoutingRequest(f.carrier,f.context,"bike"));
            assertEquals(36,costs.getTransportTime(from(),to(),0,null,null),1e-9);
            assertEquals(36,costs.getTransportCost(from(),to(),0,null,null),1e-9);
            var n=costs.getNetwork();
            var path=costs.getRouter().calcLeastCostPath(n.getNodes().get(Id.createNodeId("b")),n.getNodes().get(Id.createNodeId("c")),0,null,null);
            assertEquals(List.of("bike-shortcut"),path.links.stream().map(l->l.getId().toString()).toList());
            assertEquals(18,costs.getTravelTime().getLinkTravelTime(n.getLinks().get(Id.createLinkId("destination")),0,null,null),1e-9);
        }
    }
    @Test void unknownVehicleTypeAndConflictingTypeIdFail() {
        try(var f=fixture()) {
            var costs=new ModalTransportCosts(new RoutingRequest(f.carrier,f.context,"bike"));
            var unknown=VehicleImpl.Builder.newInstance("unknown").setType(VehicleTypeImpl.Builder.newInstance("unknown").build()).setStartLocation(from()).build();
            assertThrows(IllegalArgumentException.class,()->costs.getTransportTime(from(),to(),0,null,unknown));
            var altered=VehicleImpl.Builder.newInstance("bike").setType(VehicleTypeImpl.Builder.newInstance(f.types.get("bike").getId().toString()).setMaxVelocity(3).build()).setStartLocation(from()).build();
            assertThrows(IllegalArgumentException.class,()->costs.getTransportTime(from(),to(),0,null,altered));
            var spoof=VehicleUtils.createVehicleType(f.types.get("bike").getId(),"car");
            spoof.setMaximumVelocity(20.0/3.6);
            var v=VehicleUtils.createVehicle(Id.createVehicleId("spoof"),spoof);
            assertThrows(IllegalArgumentException.class,()->costs.getTravelTime().getLinkTravelTime(costs.getNetwork().getLinks().get(Id.createLinkId("destination")),0,null,v));
        }
    }
    @Test void requestRejectsMixedCarrierAndPreparationModeMismatch() {
        try(var f=fixture()) {
            assertThrows(IllegalArgumentException.class,()->new RoutingRequest(f.carrier,f.context,"car"));
            var mixed=CarriersUtils.createCarrier(Id.create("mixed",Carrier.class));
            mixed.setCarrierCapabilities(CarrierCapabilities.Builder.newInstance()
                    .addVehicle(CarrierVehicle.newInstance(Id.createVehicleId("car"),Id.createLinkId("origin"),f.types.get("car")))
                    .addVehicle(CarrierVehicle.newInstance(Id.createVehicleId("bike"),Id.createLinkId("origin"),f.types.get("bike"))).build());
            assertThrows(IllegalArgumentException.class,()->new RoutingRequest(mixed,f.context,"bike"));
        }
    }
    @Test void sameLinkStillChecksModeAndUnknownLink() {
        try(var f=fixture()) {
            var costs=new ModalTransportCosts(new RoutingRequest(f.carrier,f.context,"bike"));
            assertEquals(0,costs.getTransportTime(from(),from(),0,null,null));
            assertThrows(IllegalArgumentException.class,()->costs.getTransportTime(Location.newInstance("car1"),Location.newInstance("car1"),0,null,null));
            assertThrows(IllegalArgumentException.class,()->costs.getTransportTime(Location.newInstance("absent"),Location.newInstance("absent"),0,null,null));
        }
    }
    @Test void factoryRequestUsesTheFrozenContextRatherThanLegacyLiveProviders() {
        try(var f=fixture()) {
            var factory=new ZoneBasedTransportCostsFactory(f.scenario,new Carriers(List.of(f.carrier)),Map.of("bike",(l,t,p,v)->999),f.scenario.getConfig(),false,0);
            var costs=factory.createVRPTransportCosts(new RoutingRequest(f.carrier,f.context,"bike"));
            assertEquals(36,costs.getTransportTime(from(),to(),0,null,null),1e-9);
        }
    }
    @Test void contextCloseInvalidatesAlreadyObtainedFrameworkAdapters() {
        var f=fixture(); var costs=new ModalTransportCosts(new RoutingRequest(f.carrier,f.context,"bike"));
        var router=costs.getRouter(); var tt=costs.getTravelTime(); var network=costs.getNetwork();
        f.close();
        assertThrows(IllegalStateException.class,()->costs.getTransportTime(from(),to(),0,null,null));
        assertThrows(IllegalStateException.class,()->tt.getLinkTravelTime(network.getLinks().get(Id.createLinkId("destination")),0,null,null));
        assertThrows(IllegalStateException.class,()->router.calcLeastCostPath(network.getNodes().get(Id.createNodeId("b")),network.getNodes().get(Id.createNodeId("c")),0,null,null));
    }
    @Test void nativeModeLessWrapperResolvesRegisteredTypeAndBikeSpeed() {
        try(var f=fixture()) {
            var costs=new ModalTransportCosts(new RoutingRequest(f.carrier,f.context,"bike"));
            var wrapper=new org.matsim.freight.carriers.jsprit.NetworkBasedTransportCosts.MatsimVehicleWrapper(jspritVehicle(f.types.get("bike")));
            assertFalse(wrapper.getType().hasNetworkMode());
            var n=costs.getNetwork();
            var path=costs.getRouter().calcLeastCostPath(n.getNodes().get(Id.createNodeId("b")),n.getNodes().get(Id.createNodeId("c")),0,null,wrapper);
            assertEquals(18,path.travelTime,1e-9);
            assertEquals(18,costs.getTravelTime().getLinkTravelTime(n.getLinks().get(Id.createLinkId("destination")),18,null,wrapper),1e-9);
        }
    }
    @Test void sameNodeRoadQueryStillRejectsTimeOutsideSnapshot() {
        try(var f=fixture()) {
            var costs=new ModalTransportCosts(new RoutingRequest(f.carrier,f.context,"bike"));
            var node=costs.getNetwork().getNodes().get(Id.createNodeId("b"));
            assertThrows(IllegalArgumentException.class,()->costs.getRouter().calcLeastCostPath(node,node,3600,null,null));
        }
    }
    @Test void alteredCostWithSameTypeIdIsRejected() {
        try(var f=fixture()) {
            var costs=new ModalTransportCosts(new RoutingRequest(f.carrier,f.context,"bike"));
            f.types.get("bike").getCostInformation().setCostsPerSecond(2.0);
            assertThrows(IllegalArgumentException.class,()->costs.getTransportTime(from(),to(),0,null,jspritVehicle(f.types.get("bike"))));
            var nativeVehicle=VehicleUtils.createVehicle(Id.createVehicleId("bike"),f.types.get("bike"));
            assertThrows(IllegalArgumentException.class,()->costs.getTravelTime().getLinkTravelTime(costs.getNetwork().getLinks().get(Id.createLinkId("destination")),0,null,nativeVehicle));
        }
    }
    @Test void mutableCustomJspritTypeIsRevalidatedEvenAfterWarmingTheOdCache() {
        try(var f=fixture()) {
            var costs=new ModalTransportCosts(new RoutingRequest(f.carrier,f.context,"bike"));
            var original=jspritVehicle(f.types.get("bike")).getType();
            var current=new java.util.concurrent.atomic.AtomicReference<>(original.getVehicleCostParams());
            var custom=new com.graphhopper.jsprit.core.problem.vehicle.VehicleType(){
                public String getTypeId(){return original.getTypeId();}
                public com.graphhopper.jsprit.core.problem.Capacity getCapacityDimensions(){return original.getCapacityDimensions();}
                public double getMaxVelocity(){return original.getMaxVelocity();}
                public VehicleTypeImpl.VehicleCostParams getVehicleCostParams(){return current.get();}
                public String getProfile(){return original.getProfile();}
                public Object getUserData(){return original.getUserData();}
            };
            var vehicle=VehicleImpl.Builder.newInstance("mutable-custom").setType(custom).setStartLocation(from()).build();
            assertEquals(76,costs.getTransportCost(from(),to(),0,null,vehicle),1e-9);
            var changed=VehicleTypeImpl.Builder.newInstance(original.getTypeId()).setMaxVelocity(original.getMaxVelocity())
                    .setFixedCost(1.0).setCostPerDistance(0.2).setCostPerTransportTime(2).build();
            current.set(changed.getVehicleCostParams());
            assertThrows(IllegalArgumentException.class,()->costs.getTransportCost(from(),to(),0,null,vehicle));
        }
    }
    private static Location from(){return Location.newInstance("origin");}
    private static Location to(){return Location.newInstance("destination");}
    private static com.graphhopper.jsprit.core.problem.vehicle.Vehicle jspritVehicle(VehicleType type) {
        var costs=type.getCostInformation();
        var jt=VehicleTypeImpl.Builder.newInstance(type.getId().toString()).setMaxVelocity(type.getMaximumVelocity())
                .setFixedCost(costs.getFixedCosts()).setCostPerDistance(costs.getCostsPerMeter()).setCostPerTransportTime(costs.getCostsPerSecond()).build();
        return VehicleImpl.Builder.newInstance(type.getId().toString()).setType(jt).setStartLocation(from()).build();
    }
    static Fixture fixture() {
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
    record Fixture(Scenario scenario,Map<String,VehicleType> types,Carrier carrier,RoutingContext context) implements AutoCloseable {
        public void close(){context.close();}
    }

    @Test void replanningLifecycleRetainsFrozenProfileAndAlreadyComputedValues() {
        try(var f=fixture()) {
            var costs=new ModalTransportCosts(new RoutingRequest(f.carrier,f.context,"bike"));
            ReplanningTransportCosts lifecycle=costs;
            assertEquals(36,costs.getTransportTime(from(),to(),0,null,null),1e-9);
            var before=f.context.statistics();
            lifecycle.prepareForReplanning();lifecycle.logSummary("lifecycle test");
            assertEquals(36,costs.getTransportTime(from(),to(),0,null,null),1e-9);
            var after=f.context.statistics();
            assertEquals(before.computations(),after.computations());
            assertEquals(before.cacheEntries(),after.cacheEntries());
            assertEquals(before.cacheHits()+1,after.cacheHits());
        }
    }

    @Test void replanningLifecycleRejectsAClosedFrozenContext() {
        var f=fixture();
        var costs=new ModalTransportCosts(new RoutingRequest(f.carrier,f.context,"bike"));
        f.close();
        assertThrows(IllegalStateException.class,costs::prepareForReplanning);
        assertThrows(IllegalStateException.class,()->costs.logSummary("closed"));
    }}