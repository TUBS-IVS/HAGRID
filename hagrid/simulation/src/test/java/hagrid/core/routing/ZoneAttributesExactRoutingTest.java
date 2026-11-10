package hagrid.core.routing;

import com.graphhopper.jsprit.core.problem.Location;
import hagrid.core.HagridConfig;
import hagrid.core.config.*;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.*;
import org.matsim.api.core.v01.network.*;
import org.matsim.core.network.NetworkUtils;
import org.matsim.freight.carriers.*;
import org.matsim.vehicles.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ZoneAttributesExactRoutingTest {
    @Test void zoneAttributesNeverReplaceOriginalEndpoints() {
        try(var f=new Fixture(false,0,false)) {
            assertThat(f.costs.getTransportTime(Location.newInstance("b"),Location.newInstance("c"),0,null,null)).isEqualTo(20);
            assertThat(f.costs.getTransportTime(Location.newInstance("a"),Location.newInstance("c"),0,null,null)).isEqualTo(40);
            assertThat(f.context.statistics().exactQueries()).isEqualTo(2);
            assertThat(f.context.network().getLinks().get(Id.createLinkId("b")).getAttributes().getAttribute("zone")).isEqualTo(1);
        }
    }
    @Test void disabledZonesAndDistanceThresholdPreserveExactSearchValues() {
        for(boolean enabled:new boolean[]{false})try(var f=new Fixture(enabled,10000,false)){
            assertThat(f.costs.getTransportTime(Location.newInstance("b"),Location.newInstance("c"),0,null,null)).isEqualTo(20);
            assertThat(f.costs.getTransportTime(Location.newInstance("a"),Location.newInstance("c"),0,null,null)).isEqualTo(40);
            assertThat(f.context.statistics().computations()).isEqualTo(2);
        }
    }
    @Test void absentZoneAttributesKeepExactSearchValues() {
        try(var f=new Fixture(false,0,true)){
            assertThat(f.costs.getTransportTime(Location.newInstance("b"),Location.newInstance("c"),0,null,null)).isEqualTo(20);
        }
    }

    @Test void exactCacheStaysSeparateForCarAndCargoBikeProfiles() {
        try(var f=new Fixture(false,0,false)){
            var cargo=com.graphhopper.jsprit.core.problem.vehicle.VehicleImpl.Builder.newInstance("cargo")
                    .setType(com.graphhopper.jsprit.core.problem.vehicle.VehicleTypeImpl.Builder.newInstance("cargo")
                            .setMaxVelocity(5).setFixedCost(0).setCostPerDistance(0).setCostPerTransportTime(1).build())
                    .setStartLocation(Location.newInstance("a")).build();
            assertThat(f.costs.getTransportTime(Location.newInstance("b"),Location.newInstance("c"),0,null,null)).isEqualTo(20);
            assertThat(f.costs.getTransportTime(Location.newInstance("b"),Location.newInstance("c"),0,null,cargo)).isEqualTo(40);
            assertThat(f.costs.getTransportTime(Location.newInstance("a"),Location.newInstance("c"),0,null,cargo)).isEqualTo(80);
            assertThat(f.context.statistics().computations()).isEqualTo(3);
            assertThat(f.context.statistics().exactQueries()).isEqualTo(3);
            assertThat(f.costs.getTransportTime(Location.newInstance("b"),Location.newInstance("c"),0,null,cargo)).isEqualTo(40);
            assertThat(f.context.statistics().computations()).isEqualTo(3);
        }
    }
    @Test void independentPlanValidationUsesConcreteStopsAndCanonicalWindows() {
        try(var f=new Fixture(false,0,false);var exact=new Fixture(false,0,false)){
            var carrier=f.request.carrier();
            var original=carrier.getCarrierCapabilities().getCarrierVehicles().values().iterator().next();
            var vehicle=CarrierVehicle.Builder.newInstance(original.getId(),Id.createLinkId("b"),original.getType()).build();
            carrier.getCarrierCapabilities().getCarrierVehicles().put(vehicle.getId(),vehicle);
            var job=CarrierService.Builder.newInstance(Id.create("stop",CarrierService.class),Id.createLinkId("c"))
                    .setCapacityDemand(1).setServiceStartingTimeWindow(TimeWindow.newInstance(0,25)).build();
            CarriersUtils.addService(carrier,job);
            var tour=Tour.Builder.newInstance(Id.create("checked",Tour.class));
            tour.scheduleStart(vehicle.getLinkId());tour.addLeg(new Tour.Leg());tour.scheduleService(job);tour.addLeg(new Tour.Leg());tour.scheduleEnd(vehicle.getLinkId());
            var plan=new CarrierPlan(new ArrayList<>(List.of(ScheduledTour.newInstance(tour.build(),vehicle,0))));plan.setCarrier(carrier);
            org.matsim.freight.carriers.jsprit.NetworkRouter.routePlan(plan,f.costs);
            var validated=PlanValidator.validate(carrier,plan,f.request,f.context.config());
            var baseline=PlanValidator.validate(carrier,plan,exact.costs,exact.context.config(),25200);
            assertThat(validated.valid()).isTrue();
            assertThat(validated.finalCost()).isEqualTo(baseline.finalCost());
            assertThat(((Tour.Leg)plan.getScheduledTours().iterator().next().getTour().getTourElements().get(0)).getExpectedTransportTime()).isEqualTo(20);
            carrier.getServices().put(job.getId(),CarrierService.Builder.newInstance(job.getId(),job.getServiceLinkId())
                    .setCapacityDemand(1).setServiceStartingTimeWindow(TimeWindow.newInstance(0,15)).build());
            assertThat(PlanValidator.validate(carrier,plan,f.request,f.context.config()).valid()).isFalse();
        }
    }
    private static final class Fixture implements AutoCloseable {
        final Network network=NetworkUtils.createNetwork();
        final RoutingContext context;final RoutingRequest request;final ModalTransportCosts costs;
        Fixture(boolean zones,double threshold,boolean missing){this(zones,threshold,missing,false);}
        Fixture(boolean zones,double threshold,boolean missing,boolean variable){
            for(int i=0;i<4;i++)NetworkUtils.createAndAddNode(network,Id.createNodeId("n"+i),new Coord(i*200,0));
            add("a",0,1,1);add("b",1,2,1);add("c",2,3,2);
            add("reverse-a",1,0,1);add("reverse-b",2,1,1);add("reverse-c",3,2,2);
            if(missing)network.getLinks().values().forEach(l->l.getAttributes().removeAttribute("zone"));
            var type=VehicleUtils.createVehicleType(Id.create("van",VehicleType.class),"car");
            type.setMaximumVelocity(10);type.getCapacity().setOther(10);
            type.getCostInformation().setFixedCost(0.0);type.getCostInformation().setCostsPerMeter(0.0);type.getCostInformation().setCostsPerSecond(1.0);
            var types=new CarrierVehicleTypes();types.getVehicleTypes().put(type.getId(),type);
            var cargo=VehicleUtils.createVehicleType(Id.create("cargo",VehicleType.class),"cargobike");cargo.setMaximumVelocity(5);cargo.getCapacity().setOther(10);
            cargo.getCostInformation().setFixedCost(0.0);cargo.getCostInformation().setCostsPerMeter(0.0);cargo.getCostInformation().setCostsPerSecond(1.0);types.getVehicleTypes().put(cargo.getId(),cargo);
            var carrier=CarriersUtils.createCarrier(Id.create("zone",Carrier.class));CarriersUtils.setCarrierMode(carrier,"car");
            CarriersUtils.addCarrierVehicle(carrier,CarrierVehicle.Builder.newInstance(Id.createVehicleId("van"),Id.createLinkId("a"),type).build());
            var config=HagridConfigResolver.resolve(new HagridConfig(),HagridConfigResolver.hannoverTarget(),
                    HagridConfigResolver.Overrides.of(Map.of("routing.zoneBasedCachingEnabled",Boolean.toString(zones),
                            "routing.zoneBasedCachingThresholdMeters",Double.toString(threshold))),Map.of());
            var snapshot=TravelTimeSnapshot.capture(network,Map.of(),Map.of("car",
                    (org.matsim.core.router.util.TravelTime)(l,t,p,v)->variable&&t>=100?30:20,
                    "cargobike",(org.matsim.core.router.util.TravelTime)(l,t,p,v)->40),24,2000,100);
            context=RoutingContext.create(network,types,snapshot,config);request=new RoutingRequest(carrier,context,"car");
            costs=variable?null:new ModalTransportCosts(request);
        }
        void add(String id,int from,int to,int zone){
            var link=NetworkUtils.createAndAddLink(network,Id.createLinkId(id),network.getNodes().get(Id.createNodeId("n"+from)),
                    network.getNodes().get(Id.createNodeId("n"+to)),200,10,1000,1);
            link.setAllowedModes(Set.of("car","cargobike"));link.getAttributes().putAttribute("zone",zone);
        }
        public void close(){context.close();}
    }
}
