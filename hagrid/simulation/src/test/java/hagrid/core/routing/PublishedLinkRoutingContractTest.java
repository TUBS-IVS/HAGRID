package hagrid.core.routing;

import com.graphhopper.jsprit.core.problem.Location;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleImpl;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleTypeImpl;
import hagrid.core.HagridConfig;
import hagrid.core.config.*;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.freight.carriers.*;
import org.matsim.freight.carriers.jsprit.VRPTransportCosts;
import org.matsim.vehicles.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PublishedLinkRoutingContractTest {
    @Test void bothActualProvidersRespectForbiddenDestinationTransition() {
        try(var f=new Fixture()) {
            for(VRPTransportCosts costs:List.of(f.modal,NativeModalTransportCosts.create(f.links.network,f.types,"car"))) {
                var road=((LinkRouteProvider)costs).routeLeg(Id.createLinkId("o"),Id.createLinkId("d"),0,f.vehicle);
                assertEquals(List.of(Id.createLinkId("x"),Id.createLinkId("y")),road.route().getLinkIds());
                assertEquals(30,road.travelTimeSeconds(),1e-9);assertEquals(300,road.distanceMeters(),1e-9);
                assertEquals(road.travelTimeSeconds(),costs.getTransportTime(Location.newInstance("o"),Location.newInstance("d"),0,null,f.jsprit),1e-9);
                assertTrue(TurnRestrictionValidator.validate(costs.getNetwork(),"car",
                        List.of(Id.createLinkId("o"),Id.createLinkId("x"),Id.createLinkId("y"),Id.createLinkId("d"))).isEmpty());
            }
        }
    }
    @Test void failingReturnLeavesEarlierRoadAndActivityUntouched() {
        try(var f=new Fixture()) {
            var job=CarrierService.Builder.newInstance(Id.create("job",CarrierService.class),Id.createLinkId("d"),1).build();
            var first=new Tour.Leg();first.setDepartureTime(9);first.setExpectedTransportTime(17);
            var last=new Tour.Leg();last.setDepartureTime(27);last.setExpectedTransportTime(19);
            var b=Tour.Builder.newInstance(Id.create("t",Tour.class));b.scheduleStart(Id.createLinkId("o"));
            b.addLeg(first);b.scheduleService(job);b.addLeg(last);b.scheduleEnd(Id.createLinkId("o"));
            var tour=b.build();var activity=(Tour.ServiceActivity)tour.getTourElements().get(1);activity.setExpectedArrival(88);
            var cv=CarrierVehicle.Builder.newInstance(Id.createVehicleId("canonical"),Id.createLinkId("o"),f.type).build();
            var plan=new CarrierPlan(List.of(ScheduledTour.newInstance(tour,cv,0)));
            assertThrows(NoRouteException.class,()->CarrierRoadRouter.routePlan(plan,f.modal));
            assertNull(first.getRoute());assertNull(last.getRoute());assertEquals(9,first.getExpectedDepartureTime());
            assertEquals(17,first.getExpectedTransportTime());assertEquals(27,last.getExpectedDepartureTime());
            assertEquals(19,last.getExpectedTransportTime());assertEquals(88,activity.getExpectedArrival());
        }
    }
    @Test void unequalEndpointsUseDestinationDistanceForBothProvidersAndWarmCache() {
        try(var f=new Fixture(false)) {
            for(VRPTransportCosts costs:List.of(f.modal,NativeModalTransportCosts.create(f.links.network,f.types,"car"))) {
                for(int repeat=0;repeat<2;repeat++) {
                    var from=Location.newInstance("o");var to=Location.newInstance("d");
                    var road=((LinkRouteProvider)costs).routeLeg(Id.createLinkId("o"),Id.createLinkId("d"),0,f.vehicle);
                    assertEquals(250,road.distanceMeters(),1e-9);assertEquals(25,road.travelTimeSeconds(),1e-9);
                    assertEquals(50,road.monetaryCost(),1e-9);
                    assertEquals(road.distanceMeters(),costs.getDistance(from,to,0,f.jsprit),1e-9);
                    assertEquals(road.travelTimeSeconds(),costs.getTransportTime(from,to,0,null,f.jsprit),1e-9);
                    assertEquals(road.monetaryCost(),costs.getTransportCost(from,to,0,null,f.jsprit),1e-9);
                    assertEquals(0,costs.getDistance(from,from,0,f.jsprit));
                }
            }
        }
    }
    @Test void publishedRoadUsesActualVehicleIdAcrossSameTypeQueries() {
        try(var f=new Fixture(false)) {
            var other=VehicleUtils.createVehicle(Id.createVehicleId("other-actual"),f.type);
            for(VRPTransportCosts costs:List.of(f.modal,NativeModalTransportCosts.create(f.links.network,f.types,"car"))) {
                for(Vehicle actual:List.of(f.vehicle,other,f.vehicle)) {
                    var road=((LinkRouteProvider)costs).routeLeg(Id.createLinkId("o"),Id.createLinkId("d"),0,actual);
                    assertEquals(actual.getId(),road.route().getVehicleId(),"Internal type adapters must not leak into a published road");
                    assertEquals(250,road.distanceMeters(),1e-9);
                }
            }
        }
    }

    @Test void carrierTourLeavesSimulationVehicleBindingToNativeFreight() {
        try(var f=new Fixture(false)) {
            for(VRPTransportCosts costs:List.of(f.modal,NativeModalTransportCosts.create(f.links.network,f.types,"car"))) {
                var depot=Id.createLinkId("o");
                var service=CarrierService.Builder.newInstance(Id.create("depot-job",CarrierService.class),depot,1).build();
                var b=Tour.Builder.newInstance(Id.create("native-binding",Tour.class));b.scheduleStart(depot);
                b.addLeg(new Tour.Leg());b.scheduleService(service);b.addLeg(new Tour.Leg());b.scheduleEnd(depot);
                var vehicle=CarrierVehicle.Builder.newInstance(Id.createVehicleId("template"),depot,f.type).build();
                var plan=new CarrierPlan(List.of(ScheduledTour.newInstance(b.build(),vehicle,0)));
                CarrierRoadRouter.routePlan(plan,costs);
                for(var element:plan.getScheduledTours().iterator().next().getTour().getTourElements())
                    if(element instanceof Tour.Leg leg)assertNull(((org.matsim.core.population.routes.NetworkRoute)leg.getRoute()).getVehicleId(),
                            "Native Freight creates execution vehicles from driver IDs, not template IDs");
            }
        }
    }
    private static final class Fixture implements AutoCloseable {
        final LinkRoutingContractTest.Fixture links=new LinkRoutingContractTest.Fixture(true);
        final CarrierVehicleTypes types=new CarrierVehicleTypes();final VehicleType type;
        final Vehicle vehicle;final com.graphhopper.jsprit.core.problem.vehicle.Vehicle jsprit;
        final RoutingContext context;final ModalTransportCosts modal;
        Fixture(){this(true);}
        Fixture(boolean restricted) {
            if(restricted)links.restrict("o",List.of("d"),"car");
            else {
                links.network.getLinks().get(Id.createLinkId("o")).setLength(80);
                links.network.getLinks().get(Id.createLinkId("d")).setLength(250);
            }
            type=VehicleUtils.createVehicleType(Id.create("van",VehicleType.class),"car");type.setMaximumVelocity(10);
            type.getCapacity().setOther(10);type.getCostInformation().setFixedCost(0.0).setCostsPerMeter(.1).setCostsPerSecond(1.0);
            types.getVehicleTypes().put(type.getId(),type);vehicle=VehicleUtils.createVehicle(Id.createVehicleId("actual"),type);
            jsprit=VehicleImpl.Builder.newInstance("v").setType(VehicleTypeImpl.Builder.newInstance("van")
                    .setMaxVelocity(10).setCostPerDistance(.1).setCostPerTransportTime(1).build()).setStartLocation(Location.newInstance("o")).build();
            var config=HagridConfigResolver.resolve(new HagridConfig(),HagridConfigResolver.Overrides.empty(),HagridConfigResolver.Overrides.empty(),Map.of());
            var times=TravelTimeSnapshot.capture(links.network,Map.of(),Map.of("car",(l,t,p,v)->l.getLength()/10),0,1000,60);
            context=RoutingContext.create(links.network,types,times,config);
            var carrier=CarriersUtils.createCarrier(Id.create("c",Carrier.class));CarriersUtils.setCarrierMode(carrier,"car");
            modal=new ModalTransportCosts(new RoutingRequest(carrier,context,"car"));
        }
        public void close(){context.close();}
    }
}