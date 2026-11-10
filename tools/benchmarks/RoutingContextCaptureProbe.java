package hagrid.core.routing;

import com.sun.management.ThreadMXBean;
import hagrid.core.HagridConfig;
import hagrid.core.config.HagridConfigResolver;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.router.util.TravelTime;
import org.matsim.freight.carriers.CarrierVehicleTypes;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;
import java.lang.management.ManagementFactory;
import java.util.*;

/** Small combined capture/owned-graph observation. No route solve or speedup claim. */
public final class RoutingContextCaptureProbe {
    public static void main(String[] args) {
        if (Runtime.version().feature()!=21 || Runtime.getRuntime().availableProcessors()!=1
                || Runtime.getRuntime().maxMemory()>128L*1024*1024) throw new IllegalStateException("Expected Java 21, one CPU, heap <=128 MiB");
        var network=NetworkUtils.createNetwork();
        var a=NetworkUtils.createAndAddNode(network,Id.createNodeId("a"),new Coord(0,0));
        var b=NetworkUtils.createAndAddNode(network,Id.createNodeId("b"),new Coord(100,0));
        for(int i=0;i<1000;i++) {
            var link=NetworkUtils.createAndAddLink(network,Id.createLinkId("l"+i),a,b,100,10,1000,1);
            link.setAllowedModes(Set.of("car","bike"));
        }
        var types=new CarrierVehicleTypes();
        for(String mode:List.of("car","bike")) {
            var type=VehicleUtils.createVehicleType(Id.create(mode,VehicleType.class),mode);
            type.setMaximumVelocity(mode.equals("bike") ? 20.0/3.6 : 10);
            type.getCostInformation().setFixedCost(1.0).setCostsPerMeter(0.0).setCostsPerSecond(1.0);
            types.getVehicleTypes().put(type.getId(),type);
        }
        var config=HagridConfigResolver.resolve(new HagridConfig(),HagridConfigResolver.hannoverTarget(),
                HagridConfigResolver.Overrides.of(Map.of()),Map.of());
        Map<String,TravelTime> measured=Map.of("car",(l,t,p,v) -> l.getId().toString().equals("l0") && t>=10800 && t<21600 ? 20 : 10);
        Map<String,TravelTime> fallback=Map.of("bike",(l,t,p,v) -> 20);
        var allocation=ManagementFactory.getThreadMXBean() instanceof ThreadMXBean bean ? bean : null;
        boolean allocations=allocation!=null && allocation.isThreadAllocatedMemorySupported();
        if(allocations && !allocation.isThreadAllocatedMemoryEnabled()) allocation.setThreadAllocatedMemoryEnabled(true);
        System.out.println("repeat,links,modes,bins,profiles,retained_values,numeric_payload_bytes,dense_numeric_reference_bytes,snapshot_capture_ms,context_build_ms,current_thread_allocated_bytes,process_heap_used_bytes,fingerprint");
        String expectedFingerprint=null;
        for(int repeat=-1;repeat<=3;repeat++) {
            long bytesBefore=allocations ? allocation.getThreadAllocatedBytes(Thread.currentThread().threadId()) : -1;
            long before=System.nanoTime();
            var snapshot=TravelTimeSnapshot.capture(network,measured,fallback,25,86400,900);
            long captured=System.nanoTime();
            try(var context=RoutingContext.create(network,types,snapshot,config)) {
                long built=System.nanoTime();
                long allocated=allocations ? allocation.getThreadAllocatedBytes(Thread.currentThread().threadId())-bytesBefore : -1;
                var sourceLink=network.getLinks().get(Id.createLinkId("l0"));
                sourceLink.setFreespeed(1);
                var frozenLink=context.network().getLinks().get(sourceLink.getId());
                if(context.network().getLinks().size()!=1000 || frozenLink.getFreespeed()!=10
                        || context.snapshot().forMode("car").getLinkTravelTime(frozenLink,0,null,null)!=10) {
                    throw new IllegalStateException("Owned context changed with the live graph");
                }
                sourceLink.setFreespeed(10);
                if(expectedFingerprint!=null && !expectedFingerprint.equals(context.fingerprint())) throw new IllegalStateException("Context input fingerprint drift");
                expectedFingerprint=context.fingerprint();
                if(repeat>0) {
                    var stats=snapshot.storageStats();
                    System.out.printf(Locale.ROOT,"%d,1000,2,96,%d,%d,%d,%d,%.6f,%.6f,%d,%d,%s%n",
                            repeat,stats.profileCount(),stats.retainedValueCount(),stats.numericPayloadBytes(),stats.profileCount()*(8+96*8),
                            (captured-before)/1e6,(built-captured)/1e6,allocated,
                            ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(),context.fingerprint());
                }
            }
        }
    }
}