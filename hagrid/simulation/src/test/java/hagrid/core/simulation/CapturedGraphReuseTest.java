package hagrid.core.simulation;

import hagrid.core.routing.RoutingContext;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.router.util.TravelTime;
import static org.junit.jupiter.api.Assertions.*;

class CapturedGraphReuseTest {
    @Test void missingObservationsUseTheCanonicalNativeTimeDependentFallback()throws Exception {
        try(var f=new ReplanningCoordinatorTest.Fixture(1)) {
            var road=f.network.getLinks().get(org.matsim.api.core.v01.Id.createLinkId("stop"));
            var canonical=(org.matsim.api.core.v01.network.Link)Proxy.newProxyInstance(road.getClass().getClassLoader(),
                    new Class<?>[]{org.matsim.api.core.v01.network.Link.class},(p,m,args)->
                    m.getName().equals("getFreespeed")&&m.getParameterCount()==1?20.0:m.invoke(road,args));
            var links=new java.util.LinkedHashMap<org.matsim.api.core.v01.Id<org.matsim.api.core.v01.network.Link>,org.matsim.api.core.v01.network.Link>();
            links.putAll(f.network.getLinks());links.put(road.getId(),canonical);
            var network=(org.matsim.api.core.v01.network.Network)Proxy.newProxyInstance(f.network.getClass().getClassLoader(),
                    new Class<?>[]{org.matsim.api.core.v01.network.Network.class},(p,m,args)->
                    m.getName().equals("getLinks")?links:m.invoke(f.network,args));
            TravelTime missing=(l,t,p,v)->{assertSame(links.get(l.getId()),l);return Double.NaN;};
            var scenario=(Scenario)Proxy.newProxyInstance(Scenario.class.getClassLoader(),new Class<?>[]{Scenario.class},(p,m,args)->network);
            var listener=new ValidatedCarrierReplanning(scenario,ConfigUtils.createConfig(),f.config,Map.of("car",missing));
            completed(listener,24,missing);
            try(var result=capture(listener,24,f.carriers)) {
                assertEquals(road.getLength()/20,result.snapshot().forMode("car").getLinkTravelTime(result.network().getLinks().get(road.getId()),900,null,null));
            }
        }
    }
    @Test void mutationDuringMeasuredProfileSamplingFailsClosed()throws Exception {
        try(var f=new ReplanningCoordinatorTest.Fixture(1)) {
            var changed=new java.util.concurrent.atomic.AtomicBoolean();
            TravelTime times=(link,t,p,v)->{if(changed.compareAndSet(false,true))link.setLength(link.getLength()+1);return 10;};
            var scenario=(Scenario)Proxy.newProxyInstance(Scenario.class.getClassLoader(),new Class<?>[]{Scenario.class},(p,m,args)->f.network);
            var listener=new ValidatedCarrierReplanning(scenario,ConfigUtils.createConfig(),f.config,Map.of("car",times));
            completed(listener,24,times);
            assertThrows(IllegalArgumentException.class,()->capture(listener,24,f.carriers));
        }
    }
    @Test void nestedSignedZeroCoordinateAttributeInvalidatesAnOtherwiseEqualGraph()throws Exception {
        try(var f=new ReplanningCoordinatorTest.Fixture(1)) {
            f.network.getAttributes().putAttribute("nested",List.of(Map.of("point",new org.matsim.api.core.v01.Coord(+0.0,1))));
            TravelTime times=(l,t,p,v)->10;
            var scenario=(Scenario)Proxy.newProxyInstance(Scenario.class.getClassLoader(),new Class<?>[]{Scenario.class},(p,m,args)->f.network);
            var listener=new ValidatedCarrierReplanning(scenario,ConfigUtils.createConfig(),f.config,Map.of("car",times));
            completed(listener,24,times);
            try(var first=capture(listener,24,f.carriers)) {
                f.network.getAttributes().putAttribute("nested",List.of(Map.of("point",new org.matsim.api.core.v01.Coord(-0.0,1))));
                completed(listener,25,times);
                try(var second=capture(listener,25,f.carriers)){assertNotSame(first.network(),second.network());}
            }
        }
    }
    @Test void freshMeasurementsReuseUnchangedGraphAndChangedGeometryGetsANewGraph() throws Exception {
        try(var f=new ReplanningCoordinatorTest.Fixture(1)) {
            var value=new AtomicReference<Double>(10.0);
            TravelTime times=(l,t,p,v)->value.get();
            var scenario=(Scenario)Proxy.newProxyInstance(Scenario.class.getClassLoader(),new Class<?>[]{Scenario.class},
                    (p,m,args)->{if(m.getName().equals("getNetwork"))return f.network;throw new UnsupportedOperationException(m.getName());});
            var listener=new ValidatedCarrierReplanning(scenario,ConfigUtils.createConfig(),f.config,Map.of("car",times));
            try {
                completed(listener,24,times);
                try(var first=capture(listener,24,f.carriers)) {
                    value.set(11.0);completed(listener,25,times);
                    try(var second=capture(listener,25,f.carriers)) {
                        assertSame(first.network(),second.network(),"Consecutive profiles must not clone the unchanged graph");
                        assertNotEquals(first.fingerprint(),second.fingerprint());
                        assertEquals(25,second.snapshot().measurementIteration());
                        var link=second.network().getLinks().values().iterator().next();
                        assertEquals(11,second.snapshot().forMode("car").getLinkTravelTime(link,0,null,null));
                        double length=link.getLength();f.network.getLinks().get(link.getId()).setLength(length+1);
                        completed(listener,26,times);
                        try(var third=capture(listener,26,f.carriers)) {
                            assertNotSame(second.network(),third.network());
                            assertEquals(length+1,third.network().getLinks().get(link.getId()).getLength());
                            assertEquals(length,second.network().getLinks().get(link.getId()).getLength());
                        }
                    }
                }
            }finally { // The listener's coordinator has no archives in this capture-only probe.
                var field=ValidatedCarrierReplanning.class.getDeclaredField("coordinator");field.setAccessible(true);((ReplanningCoordinator)field.get(listener)).close();
            }
        }
    }
    private void completed(ValidatedCarrierReplanning listener,int iteration,TravelTime source)throws Exception {
        var i=ValidatedCarrierReplanning.class.getDeclaredField("completedIteration");i.setAccessible(true);i.set(listener,iteration);
        var s=ValidatedCarrierReplanning.class.getDeclaredField("completedSources");s.setAccessible(true);s.set(listener,Map.of("car",source));
    }
    private RoutingContext capture(ValidatedCarrierReplanning listener,int iteration,org.matsim.freight.carriers.Carriers carriers)throws Exception {
        var m=ValidatedCarrierReplanning.class.getDeclaredMethod("capture",int.class,org.matsim.freight.carriers.Carriers.class);m.setAccessible(true);
        try{return (RoutingContext)m.invoke(listener,iteration,carriers);}catch(InvocationTargetException e){throw (Exception)e.getCause();}
    }
}
