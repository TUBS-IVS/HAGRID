package hagrid.core.routing;

import com.graphhopper.jsprit.core.problem.Location;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleImpl;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleTypeImpl;
import hagrid.core.HagridConfig;
import hagrid.core.config.HagridConfigResolver;
import org.matsim.api.core.v01.*;
import org.matsim.api.core.v01.network.*;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.algorithms.TransportModeNetworkFilter;
import org.matsim.freight.carriers.*;
import org.matsim.freight.carriers.jsprit.NetworkBasedTransportCosts;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;
import java.util.*;

/** Static paired VRP contract control, not a solver or final performance comparison. */
public final class ModalTransportCostsProbe {
    public static void main(String[] args) {
        if(Runtime.version().feature()!=21 || Runtime.getRuntime().availableProcessors()!=1
                || Runtime.getRuntime().maxMemory()>128L*1024*1024) throw new IllegalStateException("Expected Java21, one CPU, heap <=128 MiB");
        System.out.println("repeat,links,modes,bins,queries,native_cached_loop_ms,modal_loop_ms,modal_prepare_ms,modal_cache_enabled,car_time,bike_time,car_distance,bike_distance,car_cost,bike_cost,null_bike_time,null_bike_cost,native_car_distance,native_bike_distance,cache_entries,created_routers,fingerprint");
        String fingerprint=null;
        for(int repeat=-1;repeat<=3;repeat++) {
            var n=NetworkUtils.createNetwork();
            var a=NetworkUtils.createAndAddNode(n,Id.createNodeId("a"),new Coord(0,0));
            var b=NetworkUtils.createAndAddNode(n,Id.createNodeId("b"),new Coord(80,0));
            var c=NetworkUtils.createAndAddNode(n,Id.createNodeId("c"),new Coord(180,0));
            var d=NetworkUtils.createAndAddNode(n,Id.createNodeId("d"),new Coord(280,0));
            var e=NetworkUtils.createAndAddNode(n,Id.createNodeId("e"),new Coord(130,100));
            link(n,"origin",a,b,80,Set.of("car","bike")); link(n,"destination",c,d,100,Set.of("car","bike"));
            link(n,"car1",b,e,100,Set.of("car")); link(n,"car2",e,c,100,Set.of("car"));
            link(n,"bike-shortcut",b,c,100,Set.of("bike")); link(n,"return",d,a,100,Set.of("car","bike"));
            var registry=new CarrierVehicleTypes(); var types=new HashMap<String,VehicleType>();
            var jsprit=new HashMap<String,com.graphhopper.jsprit.core.problem.vehicle.Vehicle>();
            var from=Location.newInstance("origin"); var to=Location.newInstance("destination");
            for(String mode:List.of("car","bike")) {
                var type=VehicleUtils.createVehicleType(Id.create(mode+"-type",VehicleType.class),mode);
                type.setMaximumVelocity(mode.equals("bike") ? 20.0/3.6 : 30);
                type.getCostInformation().setFixedCost(1.0).setCostsPerMeter(0.2).setCostsPerSecond(1.0);
                registry.getVehicleTypes().put(type.getId(),type); types.put(mode,type);
                var jt=VehicleTypeImpl.Builder.newInstance(type.getId().toString()).setMaxVelocity(type.getMaximumVelocity())
                        .setFixedCost(1).setCostPerDistance(0.2).setCostPerTransportTime(1).build();
                jsprit.put(mode,VehicleImpl.Builder.newInstance(mode).setType(jt).setStartLocation(from).build());
            }
            var carrier=CarriersUtils.createCarrier(Id.create("bike-carrier",Carrier.class));
            carrier.setCarrierCapabilities(CarrierCapabilities.Builder.newInstance().addVehicle(CarrierVehicle.newInstance(Id.createVehicleId("bike"),Id.createLinkId("origin"),types.get("bike"))).build());
            var cfg=HagridConfigResolver.resolve(new HagridConfig(),HagridConfigResolver.hannoverTarget(),HagridConfigResolver.Overrides.of(Map.of("routing.transportCacheEnabled",System.getProperty("hagrid.benchmark.cache.enabled","true"))),Map.of());
            var snapshot=TravelTimeSnapshot.capture(n,Map.of("car",(l,t,p,v)->5,"bike",(l,t,p,v)->12),Map.of(),24,3600,900);
            try(var context=RoutingContext.create(n,registry,snapshot,cfg)) {
                long before=System.nanoTime();
                var modal=new ModalTransportCosts(new RoutingRequest(carrier,context,"bike"));
                double prepared=(System.nanoTime()-before)/1e6;
                var nativeByMode=new HashMap<String,NetworkBasedTransportCosts>();
                for(String mode:List.of("car","bike")) {
                    var filtered=NetworkUtils.createNetwork(); new TransportModeNetworkFilter(n).filter(filtered,Set.of(mode));
                    nativeByMode.put(mode,NetworkBasedTransportCosts.Builder.newInstance(filtered,List.of(types.get(mode)))
                            .setTimeSliceWidth(1).setTravelTime(snapshot.forMode(mode)).build());
                    var vehicle=jsprit.get(mode); var reference=nativeByMode.get(mode);
                    equal(modal.getTransportTime(from,to,0,null,vehicle),reference.getTransportTime(from,to,0,null,vehicle));
                    equal(modal.getTransportCost(from,to,0,null,vehicle),reference.getTransportCost(from,to,0,null,vehicle));
                    equal(modal.getDistance(from,to,0,vehicle),reference.getDistance(from,to,0,vehicle)+20); // Destination 100 m replaces origin 80 m.
                }
                equal(15,modal.getTransportTime(from,to,0,null,jsprit.get("car")));
                equal(36,modal.getTransportTime(from,to,0,null,jsprit.get("bike")));
                equal(36,modal.getTransportTime(from,to,0,null,null)); equal(36,modal.getTransportCost(from,to,0,null,null));
                long nativeStart=System.nanoTime(); double checksum=0;
                for(int i=0;i<5000;i++) for(String mode:List.of("car","bike")) checksum+=nativeByMode.get(mode).getTransportTime(from,to,0,null,jsprit.get(mode));
                long nativeEnd=System.nanoTime();
                for(int i=0;i<5000;i++) for(String mode:List.of("car","bike")) checksum-=modal.getTransportTime(from,to,0,null,jsprit.get(mode));
                long modalEnd=System.nanoTime(); equal(0,checksum);
                if(fingerprint!=null && !fingerprint.equals(context.fingerprint())) throw new IllegalStateException("Fixture input drift");
                fingerprint=context.fingerprint();
                if(repeat>0) System.out.printf(Locale.ROOT,"%d,6,2,4,10000,%.6f,%.6f,%.6f,%b,15,36,300,200,75,76,36,36,280,180,%d,%d,%s%n",
                        repeat,(nativeEnd-nativeStart)/1e6,(modalEnd-nativeEnd)/1e6,prepared,cfg.routing().transportCacheEnabled(),context.statistics().cacheEntries(),context.statistics().createdRouters(),fingerprint);
            }
        }
    }
    private static void equal(double expected,double actual){if(Math.abs(expected-actual)>1e-9) throw new IllegalStateException("Contract mismatch "+expected+" != "+actual);}
    private static void link(Network n,String id,Node from,Node to,double length,Set<String> modes){
        var link=NetworkUtils.createAndAddLink(n,Id.createLinkId(id),from,to,length,30,1000,1);link.setAllowedModes(modes);
    }
}