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
import org.matsim.core.router.util.TravelTime;
import org.matsim.freight.carriers.*;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TemporalRoutingContractTest {
    private static final double TOLERANCE=1e-6;
    @Test void destinationLinkCountedOnce() {
        try(var f=fixture((l,t,p,v)->l.getId().toString().equals("destination") ? 20:10,0)) {
            var costs=f.costs();
            assertEquals(300,costs.getDistance(from(),to(),0,f.vehicle),TOLERANCE);
            assertEquals(30,costs.getTransportTime(from(),to(),0,null,f.vehicle),TOLERANCE);
            assertEquals(60,costs.getTransportCost(from(),to(),0,null,f.vehicle),TOLERANCE);
        }
    }
    @Test void forwardDestinationEntryCrossesCapturedBoundary() {
        try(var f=fixture(TemporalRoutingContractTest::changingTime,0)) {
            assertEquals(100,f.costs().getTransportTime(from(),to(),80,null,f.vehicle),TOLERANCE);
        }
    }
    @Test void backwardTimeMatchesForwardArrival() {
        try(var f=fixture(TemporalRoutingContractTest::changingTime,0)) {
            var costs=f.costs(); double arrival=180;
            double duration=costs.getBackwardTransportTime(from(),to(),arrival,null,f.vehicle);
            assertEquals(100,duration,TOLERANCE);
            assertEquals(arrival,arrival-duration+costs.getTransportTime(from(),to(),arrival-duration,null,f.vehicle),TOLERANCE);
            assertEquals(200,costs.getBackwardTransportCost(from(),to(),arrival,null,f.vehicle),TOLERANCE);
        }
    }
    @Test void backwardAtAnArrivalGapIsConservativeAndIncludesSlack() {
        try(var f=fixture(TemporalRoutingContractTest::changingTime,0)) {
            var costs=f.costs(); double deadline=140;
            double duration=costs.getBackwardTransportTime(from(),to(),deadline,null,f.vehicle);
            assertEquals(80,duration,TOLERANCE);
            double departure=deadline-duration;
            assertTrue(departure+costs.getTransportTime(from(),to(),departure,null,f.vehicle)<=deadline+TOLERANCE);
            assertEquals(120,costs.getBackwardTransportCost(from(),to(),deadline,null,f.vehicle),TOLERANCE);
        }
    }
    @Test void arrivalBeforeAnyFeasibleDepartureReturnsUnreachable() {
        try(var f=fixture(TemporalRoutingContractTest::changingTime,0)) {
            assertEquals(Double.MAX_VALUE,f.costs().getBackwardTransportTime(from(),to(),10,null,f.vehicle));
        }
    }
    @Test void nonFifoProfileRejected() {
        try(var f=fixture((l,t,p,v)->t<100 ? 40:10,0)) {
            var failure=assertThrows(IllegalArgumentException.class,f::costs);
            assertTrue(failure.getMessage().contains("FIFO"));
        }
    }
    @Test void variableMixedCostObjectiveRequiresAnExplicitRoutingPolicy() {
        try(var f=fixture(TemporalRoutingContractTest::changingTime,0.2)) {
            var failure=assertThrows(IllegalArgumentException.class,f::costs);
            assertTrue(failure.getMessage().contains("time"));
        }
    }
    @Test void altBoundCoversChangedSpeeds() {
        try(var f=fixture((l,t,p,v)->1,0)) {
            // Native base freespeed is 1 m/s (100/200 s); captured background can be much faster.
            assertEquals(2,f.costs().getTransportTime(from(),to(),0,null,f.vehicle),TOLERANCE);
        }
    }
    @Test void staticBackwardNearHorizonEvaluatesAtDeparture() {
        try(var f=fixture((l,t,p,v)->20,0)) {
            var costs=f.costs();
            assertEquals(40,costs.getBackwardTransportTime(from(),to(),3599,null,f.vehicle),TOLERANCE);
            assertEquals(80,costs.getBackwardTransportCost(from(),to(),3599,null,f.vehicle),TOLERANCE);
        }
    }
    @Test void roadRouterRejectsNonFifoBeforeAQuery() {
        try(var f=fixture((l,t,p,v)->t<100 ? 40:10,0)) {
            assertThrows(IllegalArgumentException.class,()->f.costs().getRouter());
        }
    }
    @Test void nativeMixedCostPathSwitchCanBreakArrivalMonotonicityDespiteFifoLinks() {
        TravelTime time=(link,t,p,v)->link.getId().toString().equals("middle") ? (t<100 ? 40:80)
                : link.getId().toString().equals("destination") ? 1:5;
        try(var f=fixture(time,0.2,true)) {
            var type=f.carrier.getCarrierCapabilities().getCarrierVehicles().values().iterator().next().getType();
            var nativeCosts=org.matsim.freight.carriers.jsprit.NetworkBasedTransportCosts.Builder.newInstance(f.context.network(),List.of(type))
                    .setTimeSliceWidth(1).setTravelTime(f.context.snapshot().forMode("car"))
                    .setThreadSafeLeastCostPathCalculatorFactory(new org.matsim.core.router.speedy.SpeedyDijkstraFactory()).build();
            double earlier=99+nativeCosts.getTransportTime(from(),to(),99,null,f.vehicle);
            double later=100+nativeCosts.getTransportTime(from(),to(),100,null,f.vehicle);
            assertEquals(140,earlier,TOLERANCE); assertEquals(111,later,TOLERANCE);
            assertTrue(later<earlier,"A changed cheapest path invalidates binary-search arrival monotonicity");
            assertThrows(IllegalArgumentException.class,f::costs);
        }
    }
    private static double changingTime(Link link,double t,org.matsim.api.core.v01.population.Person p,org.matsim.vehicles.Vehicle v) {
        return link.getId().toString().equals("destination") ? (t<100 ? 20:60):40;
    }
    private static Location from(){return Location.newInstance("origin");}
    private static Location to(){return Location.newInstance("destination");}
    static Fixture fixture(TravelTime time,double perMeter) { return fixture(time,perMeter,false); }
    private static Fixture fixture(TravelTime time,double perMeter,boolean alternativePath) {
        var n=NetworkUtils.createNetwork();
        var a=NetworkUtils.createAndAddNode(n,Id.createNodeId("a"),new Coord(0,0));
        var b=NetworkUtils.createAndAddNode(n,Id.createNodeId("b"),new Coord(80,0));
        var c=NetworkUtils.createAndAddNode(n,Id.createNodeId("c"),new Coord(180,0));
        var d=NetworkUtils.createAndAddNode(n,Id.createNodeId("d"),new Coord(380,0));
        link(n,"origin",a,b,80); link(n,"middle",b,c,100); link(n,"destination",c,d,200);
        if(alternativePath) {
            var e=NetworkUtils.createAndAddNode(n,Id.createNodeId("e"),new Coord(130,100));
            link(n,"alternative1",b,e,250);link(n,"alternative2",e,c,250);
        }
        var type=VehicleUtils.createVehicleType(Id.create("van-type",VehicleType.class),"car"); type.setMaximumVelocity(Double.POSITIVE_INFINITY);
        type.getCostInformation().setFixedCost(1.0).setCostsPerMeter(perMeter).setCostsPerSecond(2.0);
        var types=new CarrierVehicleTypes();types.getVehicleTypes().put(type.getId(),type);
        var carrier=CarriersUtils.createCarrier(Id.create("carrier",Carrier.class));
        carrier.setCarrierCapabilities(CarrierCapabilities.Builder.newInstance().addVehicle(CarrierVehicle.newInstance(Id.createVehicleId("van"),Id.createLinkId("origin"),type)).build());
        var jt=VehicleTypeImpl.Builder.newInstance(type.getId().toString()).setMaxVelocity(Double.POSITIVE_INFINITY).setFixedCost(1.0).setCostPerDistance(perMeter).setCostPerTransportTime(2).build();
        var vehicle=VehicleImpl.Builder.newInstance("van").setType(jt).setStartLocation(from()).build();
        var config=HagridConfigResolver.resolve(new HagridConfig(),HagridConfigResolver.hannoverTarget(),HagridConfigResolver.Overrides.of(Map.of()),Map.of());
        var snapshot=TravelTimeSnapshot.capture(n,Map.of("car",time),Map.of(),24,3600,100);
        return new Fixture(carrier,RoutingContext.create(n,types,snapshot,config),vehicle);
    }
    private static void link(Network n,String id,Node from,Node to,double length){
        var link=NetworkUtils.createAndAddLink(n,Id.createLinkId(id),from,to,length,1,1000,1);link.setAllowedModes(Set.of("car"));
    }
    record Fixture(Carrier carrier,RoutingContext context,com.graphhopper.jsprit.core.problem.vehicle.Vehicle vehicle) implements AutoCloseable {
        ModalTransportCosts costs(){return new ModalTransportCosts(new RoutingRequest(carrier,context,"car"));}
        public void close(){context.close();}
    }
}