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
import org.matsim.vehicles.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Literal native means and independent enumeration protect the raw distance-only contract. */
class RawDistanceTemporalRoutingTest {
    static final double RATE = 0.000386430173292559;

    @Test void nativeSevenToFivePointFiveMeansRemainUsableWithOriginalLCosts() {
        try (var f = fixture((l,t,p,v)->t<3600?7:5.5,900,0,10800)) {
            var costs = assertDoesNotThrow(f::costs);
            assertEquals(7,costs.getTransportTime(from(),to(),3599,null,f.vehicle),1e-9);
            assertEquals(5.5,costs.getTransportTime(from(),to(),3600,null,f.vehicle),1e-9);
            assertEquals(100*RATE,costs.getTransportCost(from(),to(),3600,null,f.vehicle),1e-12);
            assertEquals(7,f.context.snapshot().forMode("car").getLinkTravelTime(f.destination,3599,null,null));
            assertEquals(5.5,f.context.snapshot().forMode("car").getLinkTravelTime(f.destination,3600,null,null));
        }
    }

    @Test void halfHourOdEstimateDoesNotOverwriteNativeQuarterHourOrActualRoadEntry() {
        try (var f = fixture((l,t,p,v)->t<900?100:20,900,0,10800)) {
            var costs = assertDoesNotThrow(f::costs);
            assertEquals(100,costs.getTransportTime(from(),to(),1799,null,f.vehicle));
            assertEquals(20,costs.getTransportTime(from(),to(),1800,null,f.vehicle));
            assertEquals(20,costs.routeLeg(Id.createLinkId("origin"),Id.createLinkId("destination"),900,f.nativeVehicle).travelTimeSeconds());
            assertEquals(20,costs.getTravelTime().getLinkTravelTime(f.destination,900,null,f.nativeVehicle));
        }
    }

    @Test void backwardFindsLaterIslandEvenWhenDepartureZeroIsImpossible() {
        try (var f = fixture((l,t,p,v)->t>=100&&t<110?10:1000,10,10,2000)) {
            var costs = assertDoesNotThrow(f::costs);
            double duration = costs.getBackwardTransportTime(from(),to(),120,null,f.vehicle);
            double departure = 120-duration;
            assertTrue(departure>=100&&departure<110,"The feasible island is [100,110)");
            assertEquals(110,departure,1e-10);
            assertTrue(departure+costs.getTransportTime(from(),to(),departure,null,f.vehicle)<=120);
            assertEquals(100*RATE,costs.getBackwardTransportCost(from(),to(),120,null,f.vehicle),1e-12);
        }
    }

    @Test void inverseMatchesIndependentIntegerEnumerationAcrossAllIslands() {
        double[] raw = {1000,10,1000,5,1000,7,1000,1000};
        try (var f = fixture((l,t,p,v)->raw[Math.min(7,(int)(t/10))],10,10,80)) {
            var costs = assertDoesNotThrow(f::costs);
            for(int deadline=1;deadline<=80;deadline++) {
                int latest=-1;
                for(int departure=0;departure<80;departure++)
                    if(departure+raw[departure/10]<=deadline) latest=departure;
                double backward=costs.getBackwardTransportTime(from(),to(),deadline,null,f.vehicle);
                if(latest<0) assertEquals(Double.MAX_VALUE,backward,"deadline "+deadline);
                else {
                    double actual=deadline-backward;
                    assertTrue(actual>=latest&&actual<latest+1,"independent integer oracle, deadline "+deadline);
                    int bin=Math.min(7,(int)(actual/10));
                    assertTrue(actual+raw[bin]<=deadline,"forward feasibility at inverse answer");
                }
            }
        }
    }

    @Test void arrivalExactlyAtHorizonIsAllowedButNewEntryAtHorizonIsNot() {
        try (var f=fixture((l,t,p,v)->t<10?20:10,10,10,100)) {
            var costs=assertDoesNotThrow(f::costs);
            assertEquals(10,costs.getBackwardTransportTime(from(),to(),100,null,f.vehicle));
            assertEquals(10,costs.getTransportTime(from(),to(),90,null,f.vehicle));
            assertThrows(IllegalArgumentException.class,()->costs.getTransportTime(from(),to(),100,null,f.vehicle));
        }
    }

    @Test void waitingRateIsStillTenPerSecondAndFixedChargeIsUnchanged() {
        try(var f=fixture((l,t,p,v)->t<900?7:5.5,900,0,10800)) {
            var costs=assertDoesNotThrow(f::costs);
            var service=com.graphhopper.jsprit.core.problem.job.Service.Builder.newInstance("s")
                    .setLocation(to()).setServiceTime(3).build();
            var activity=com.graphhopper.jsprit.core.problem.solution.route.activity.ServiceActivity.newInstance(service);
            activity.setTheoreticalEarliestOperationStartTime(20);
            activity.setTheoreticalLatestOperationStartTime(30);
            assertEquals(130,new HagridActivityCosts(5).getActivityCost(activity,7,null,f.vehicle));
            assertEquals(189.15,f.vehicle.getType().getVehicleCostParams().fix);
            assertEquals(100*RATE,costs.getTransportCost(from(),to(),0,null,f.vehicle),1e-12);
        }
    }

    static Location from(){return Location.newInstance("origin");}
    static Location to(){return Location.newInstance("destination");}
    static Fixture fixture(TravelTime time,int nativeBin,int raster,double horizon) {
        return fixture(time,nativeBin,raster,horizon,n->{});
    }
    static Fixture fixture(TravelTime time,int nativeBin,int raster,double horizon,java.util.function.Consumer<Network> configure) {
        var n=NetworkUtils.createNetwork();
        var a=NetworkUtils.createAndAddNode(n,Id.createNodeId("a"),new Coord(0,0));
        var b=NetworkUtils.createAndAddNode(n,Id.createNodeId("b"),new Coord(100,0));
        var c=NetworkUtils.createAndAddNode(n,Id.createNodeId("c"),new Coord(200,0));
        link(n,"origin",a,b);var destination=link(n,"destination",b,c);link(n,"return",c,a);
        configure.accept(n);
        var type=VehicleUtils.createVehicleType(Id.create("L",VehicleType.class),"car");
        type.setMaximumVelocity(Double.POSITIVE_INFINITY);
        type.getCostInformation().setFixedCost(189.15).setCostsPerMeter(RATE).setCostsPerSecond(0.0);
        var types=new CarrierVehicleTypes();types.getVehicleTypes().put(type.getId(),type);
        var carrier=CarriersUtils.createCarrier(Id.create("carrier",Carrier.class));
        CarriersUtils.addCarrierVehicle(carrier,CarrierVehicle.newInstance(Id.createVehicleId("van"),Id.createLinkId("origin"),type));
        var jt=VehicleTypeImpl.Builder.newInstance("L").setMaxVelocity(Double.POSITIVE_INFINITY)
                .setFixedCost(189.15).setCostPerDistance(RATE).setCostPerTransportTime(0).setCostPerWaitingTime(10).build();
        var vehicle=VehicleImpl.Builder.newInstance("van").setType(jt).setStartLocation(from()).setLatestArrival(horizon).build();
        var config=HagridConfigResolver.resolve(new HagridConfig(),HagridConfigResolver.hannoverTarget(),
                HagridConfigResolver.Overrides.of(Map.of("routing.odTimeQuantizationSeconds",Integer.toString(raster),"tourRules.uTurnPenaltyCost","0")),Map.of());
        var snapshot=TravelTimeSnapshot.capture(n,Map.of("car",time),Map.of(),24,horizon,nativeBin);
        return new Fixture(carrier,RoutingContext.create(n,types,snapshot,config),vehicle,
                VehicleUtils.createVehicle(Id.createVehicleId("van"),type),destination);
    }
    private static Link link(Network n,String id,Node a,Node b){
        var link=NetworkUtils.createAndAddLink(n,Id.createLinkId(id),a,b,100,10,1000,1);link.setAllowedModes(Set.of("car"));return link;
    }
    record Fixture(Carrier carrier,RoutingContext context,com.graphhopper.jsprit.core.problem.vehicle.Vehicle vehicle,
                   org.matsim.vehicles.Vehicle nativeVehicle,Link destination) implements AutoCloseable {
        ModalTransportCosts costs(){return new ModalTransportCosts(new RoutingRequest(carrier,context,"car"));}
        public void close(){context.close();}
    }
}
