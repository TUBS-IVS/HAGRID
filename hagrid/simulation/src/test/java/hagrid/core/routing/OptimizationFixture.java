package hagrid.core.routing;
import hagrid.core.HagridConfig;
import hagrid.core.config.*;
import org.matsim.api.core.v01.*;
import org.matsim.core.network.NetworkUtils;
import org.matsim.freight.carriers.*;
import org.matsim.freight.carriers.jsprit.NetworkRouter;
import org.matsim.vehicles.*;
import java.util.*;

final class OptimizationFixture implements AutoCloseable {
    final Carrier carrier=CarriersUtils.createCarrier(Id.create("test",Carrier.class));
    final CarrierVehicleTypes types=new CarrierVehicleTypes();
    final VehicleType type=VehicleUtils.createVehicleType(Id.create("van",VehicleType.class),"car");
    final CarrierVehicle vehicle;
    final ResolvedHagridConfig config;
    final RoutingContext context;
    final RoutingRequest request;
    final ModalTransportCosts costs;
    OptimizationFixture(){this((Integer)null);}
    OptimizationFixture(Integer replanningIterations){
        this(HagridConfigResolver.resolve(new HagridConfig(),HagridConfigResolver.hannoverTarget(),
                HagridConfigResolver.Overrides.of(replanningIterations==null ? Map.of() :
                        Map.of("optimization.replanningIterations",replanningIterations.toString())),Map.of()));
    }
    OptimizationFixture(ResolvedHagridConfig suppliedConfig){
        var n=NetworkUtils.createNetwork();
        for(int i=0;i<4;i++)n.addNode(n.getFactory().createNode(Id.createNodeId("n"+i),new Coord(i*100,0)));
        String[] ids={"depot","road","stop","return"};
        for(int i=0;i<4;i++){
            var l=n.getFactory().createLink(Id.createLinkId(ids[i]),n.getNodes().get(Id.createNodeId("n"+i)),n.getNodes().get(Id.createNodeId("n"+((i+1)%4))));
            l.setLength(100);l.setFreespeed(10);l.setCapacity(1000);l.setNumberOfLanes(1);l.setAllowedModes(Set.of("car"));n.addLink(l);
        }
        type.setMaximumVelocity(10);type.getCapacity().setOther(10);
        type.getCostInformation().setFixedCost(10.0);type.getCostInformation().setCostsPerMeter(0.1);type.getCostInformation().setCostsPerSecond(1.0);
        types.getVehicleTypes().put(type.getId(),type);
        vehicle=CarrierVehicle.Builder.newInstance(Id.createVehicleId("van"),Id.createLinkId("depot"),type).setEarliestStart(0).setLatestEnd(1000).build();
        CarriersUtils.addCarrierVehicle(carrier,vehicle);CarriersUtils.setCarrierMode(carrier,"car");
        config=suppliedConfig;
        var snapshot=TravelTimeSnapshot.capture(n,Map.of(),Map.of("car",(l,t,p,v)->l.getLength()/10),24,2000,60);
        context=RoutingContext.create(n,types,snapshot,config);request=new RoutingRequest(carrier,context,"car");costs=new ModalTransportCosts(request);
    }
    CarrierService service(String id,int demand,double start,double end){
        var s=CarrierService.Builder.newInstance(Id.create(id,CarrierService.class),Id.createLinkId("stop"))
                .setCapacityDemand(demand).setServiceDuration(10).setServiceStartingTimeWindow(TimeWindow.newInstance(start,end)).build();
        CarriersUtils.addService(carrier,s);return s;
    }
    CarrierPlan plan(double departure,CarrierService... services){
        var builder=Tour.Builder.newInstance(Id.create("tour",Tour.class));builder.scheduleStart(vehicle.getLinkId());
        for(var s:services){builder.addLeg(new Tour.Leg());builder.scheduleService(s);}builder.addLeg(new Tour.Leg());builder.scheduleEnd(vehicle.getLinkId());
        var result=new CarrierPlan(new ArrayList<>(List.of(ScheduledTour.newInstance(builder.build(),vehicle,departure))));result.setCarrier(carrier);
        NetworkRouter.routePlan(result,costs);return result;
    }
    CarrierOptimizationService.Result result(CarrierPlan plan,double searchCost){
        return new CarrierOptimizationService.Result("test",plan,Set.of(),searchCost,searchCost,1,null,context.fingerprint(),List.of());
    }
    CarrierOptimizationService.Result searchWith(ResolvedHagridConfig options,long seed,int iterations) {
        try (var own=RoutingContext.create(context.network(),types,context.snapshot(),options)) {
            return new CarrierOptimizationService().optimize(carrier,new RoutingRequest(carrier,own,"car"),options,seed,iterations);
        }
    }
    public void close(){context.close();}
}