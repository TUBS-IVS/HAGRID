package hagrid.core.routing.benchmark;

import hagrid.core.HagridConfig;
import hagrid.core.config.*;
import hagrid.core.routing.*;
import org.matsim.api.core.v01.*;
import org.matsim.core.network.NetworkUtils;
import org.matsim.freight.carriers.*;
import org.matsim.vehicles.*;
import java.nio.file.*;
import java.util.*;

/** Tiny packaged API fixture; exported cases are then replayed by the actual standalone JAR CLI. */
public final class PackageReplaySmoke {
    public static void main(String[] args) {
        if (args.length != 1) throw new IllegalArgumentException("Expected new case root");
        var network = NetworkUtils.createNetwork();
        for (int i=0;i<4;i++) network.addNode(network.getFactory().createNode(Id.createNodeId("n"+i),new Coord(i*100,0)));
        String[] links={"depot","road","stop","return"};
        for (int i=0;i<4;i++) {
            var l=network.getFactory().createLink(Id.createLinkId(links[i]),network.getNodes().get(Id.createNodeId("n"+i)),
                    network.getNodes().get(Id.createNodeId("n"+((i+1)%4))));
            l.setLength(100);l.setFreespeed(10);l.setCapacity(1000);l.setNumberOfLanes(1);l.setAllowedModes(Set.of("car"));
            network.addLink(l);
        }
        var type=VehicleUtils.createVehicleType(Id.create("van",VehicleType.class),"car");
        type.setMaximumVelocity(10);type.getCapacity().setOther(10);
        type.getCostInformation().setFixedCost(10.0).setCostsPerMeter(0.1).setCostsPerSecond(1.0);
        var types=new CarrierVehicleTypes();types.getVehicleTypes().put(type.getId(),type);
        var config=HagridConfigResolver.resolve(new HagridConfig(),HagridConfigResolver.hannoverTarget(),
                HagridConfigResolver.Overrides.of(Map.of("optimization.replanningIterations","2",
                        "optimization.warmStartEnabled","false")),Map.of());
        var snapshot=TravelTimeSnapshot.capture(network,Map.of(),Map.of("car",(l,t,p,v)->l.getLength()/10),24,2000,60);
        try (var context=RoutingContext.create(network,types,snapshot,config)) {
            for (String name:List.of("services","shipments")) {
                var carrier=CarriersUtils.createCarrier(Id.create(name,Carrier.class));
                CarriersUtils.setCarrierMode(carrier,"car");
                CarriersUtils.addCarrierVehicle(carrier,CarrierVehicle.Builder.newInstance(Id.createVehicleId("van"),
                        Id.createLinkId("depot"),type).setEarliestStart(0).setLatestEnd(1000).build());
                if (name.equals("services")) {
                    CarriersUtils.addService(carrier,CarrierService.Builder.newInstance(Id.create("a",CarrierService.class),Id.createLinkId("stop"))
                            .setCapacityDemand(1).setServiceDuration(10.25).setServiceStartingTimeWindow(TimeWindow.newInstance(.25,900.75)).build());
                } else {
                    var shipment=CarrierShipment.Builder.newInstance(Id.create("s",CarrierShipment.class),Id.createLinkId("road"),
                            Id.createLinkId("stop"),1).setPickupDuration(1.25).setDeliveryDuration(2.75)
                            .setPickupStartingTimeWindow(TimeWindow.newInstance(.25,800.75))
                            .setDeliveryStartingTimeWindow(TimeWindow.newInstance(.5,900.25)).build();
                    carrier.getShipments().put(shipment.getId(),shipment);
                }
                var request=new RoutingRequest(carrier,context,"car");
                var result=new CarrierOptimizationService().optimize(carrier,request,config,42,2);
                if (!result.unassignedJobIds().isEmpty() || !result.diagnostics().isEmpty())
                    throw new IllegalStateException("Invalid packaged fixture "+result.diagnostics());
                carrier.addPlan(result.plan());carrier.setSelectedPlan(result.plan());
                CarrierReplay.exportCase(Path.of(args[0]).resolve(name),carrier,request,config,42);
                System.out.println("exported="+name+" context="+context.fingerprint()+" finalCost="+result.finalCost());
            }
        }
    }
}