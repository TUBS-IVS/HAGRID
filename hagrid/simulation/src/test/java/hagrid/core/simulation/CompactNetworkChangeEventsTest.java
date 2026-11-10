package hagrid.core.simulation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkChangeEvent;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.io.NetworkChangeEventsWriter;
import org.matsim.core.scenario.ScenarioUtils;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CompactNetworkChangeEventsTest {
    @TempDir Path temporary;
    static Network network() {
        var config=ConfigUtils.createConfig();config.network().setTimeVariantNetwork(true);
        var n=NetworkUtils.createNetwork(config);
        var a=NetworkUtils.createAndAddNode(n,Id.createNodeId("a"),new Coord(0,0));
        var b=NetworkUtils.createAndAddNode(n,Id.createNodeId("b"),new Coord(100,0));
        for(int i=0;i<4;i++)NetworkUtils.createAndAddLink(n,Id.createLinkId("l"+i),a,b,100,10,1800,1);
        return n;
    }
    static NetworkChangeEvent event(Network n,double t,int link,NetworkChangeEvent.ChangeType type,double value) {
        var e=new NetworkChangeEvent(t);e.addLink(n.getLinks().get(Id.createLinkId("l"+link)));
        e.setFreespeedChange(new NetworkChangeEvent.ChangeValue(type,value));return e;
    }
    @Test void equalChangesShareOneEventWithoutLosingAnyLinks() {
        var n=network();var sink=new CompactNetworkChangeEvents.Collector(100);
        for(int i=0;i<4;i++)sink.add(event(n,20,i,NetworkChangeEvent.ChangeType.ABSOLUTE_IN_SI_UNITS,5));
        var result=sink.finish();assertEquals(1,result.size());assertEquals(4,result.getFirst().getLinks().size());
        assertEquals(4,sink.inputEvents());
    }
    @Test void allThreeAttributesAndUnsortedTimesMatchNativeFunctions() {
        var original=network();var compact=network();var nativeEvents=new ArrayList<NetworkChangeEvent>();
        var sink=new CompactNetworkChangeEvents.Collector(100);
        for(var n:List.of(original,compact)) {
            var events=new ArrayList<NetworkChangeEvent>();
            events.add(event(n,20,0,NetworkChangeEvent.ChangeType.ABSOLUTE_IN_SI_UNITS,5));
            events.add(event(n,15,1,NetworkChangeEvent.ChangeType.ABSOLUTE_IN_SI_UNITS,7));
            var last=event(n,20,1,NetworkChangeEvent.ChangeType.ABSOLUTE_IN_SI_UNITS,5);
            last.setFlowCapacityChange(new NetworkChangeEvent.ChangeValue(NetworkChangeEvent.ChangeType.FACTOR,.5));
            last.setLanesChange(new NetworkChangeEvent.ChangeValue(NetworkChangeEvent.ChangeType.OFFSET_IN_SI_UNITS,1));
            events.add(last);
            events.add(event(n,40,0,NetworkChangeEvent.ChangeType.FACTOR,2));
            events.add(event(n,10,2,NetworkChangeEvent.ChangeType.OFFSET_IN_SI_UNITS,3));
            if(n==original)nativeEvents.addAll(events);else events.forEach(sink::add);
        }
        NetworkUtils.setNetworkChangeEvents(original,nativeEvents);
        NetworkUtils.setNetworkChangeEvents(compact,sink.finish());
        assertFunctionsEqual(original,compact);
    }
    static void assertFunctionsEqual(Network a,Network b) {
        for(var id:a.getLinks().keySet())for(double t:new double[]{0,9.999,10,19.999,20,39.999,40,100}) {
            var x=a.getLinks().get(id);var y=b.getLinks().get(id);
            assertEquals(x.getFreespeed(t),y.getFreespeed(t),0,"speed "+id+" at "+t);
            assertEquals(x.getCapacity(t),y.getCapacity(t),0,"capacity "+id+" at "+t);
            assertEquals(x.getNumberOfLanes(t),y.getNumberOfLanes(t),0,"lanes "+id+" at "+t);
        }
    }
    @Test void duplicateLinkTimePreservesNativeFailure() {
        var a=network();var b=network();var events=new ArrayList<NetworkChangeEvent>();
        var sink=new CompactNetworkChangeEvents.Collector(100);
        for(var n:List.of(a,b))for(int i=0;i<2;i++) {
            var e=event(n,20,0,NetworkChangeEvent.ChangeType.ABSOLUTE_IN_SI_UNITS,5+i);
            if(n==a)events.add(e);else sink.add(e);
        }
        NetworkUtils.setNetworkChangeEvents(a,events);NetworkUtils.setNetworkChangeEvents(b,sink.finish());
        var original=assertThrows(RuntimeException.class,()->a.getLinks().get(Id.createLinkId("l0")).getFreespeed(20));
        var compact=assertThrows(RuntimeException.class,()->b.getLinks().get(Id.createLinkId("l0")).getFreespeed(20));
        assertEquals(original.getMessage(),compact.getMessage());
    }
    @Test void chunkLimitBoundsTheGroupingWindow() {
        var n=network();var sink=new CompactNetworkChangeEvents.Collector(2);
        for(int i=0;i<4;i++)sink.add(event(n,20,i,NetworkChangeEvent.ChangeType.ABSOLUTE_IN_SI_UNITS,5));
        assertEquals(2,sink.finish().size());
    }
    @Test void emptyDuplicateAndNonfiniteEventsRemainUnmodified() {
        var n=network();var sink=new CompactNetworkChangeEvents.Collector(100);
        var empty=new NetworkChangeEvent(0);sink.add(empty);
        var duplicate=event(n,1,0,NetworkChangeEvent.ChangeType.FACTOR,2);
        duplicate.addLinks(duplicate.getLinks().stream().toList());sink.add(duplicate);
        var nan=event(n,Double.NaN,1,NetworkChangeEvent.ChangeType.ABSOLUTE_IN_SI_UNITS,5);sink.add(nan);
        var infinite=event(n,1,1,NetworkChangeEvent.ChangeType.ABSOLUTE_IN_SI_UNITS,Double.POSITIVE_INFINITY);sink.add(infinite);
        assertEquals(List.of(empty,duplicate,nan,infinite),sink.finish());
    }
    @Test void signedZeroAndDifferentChangeTypesAreNotMerged() {
        var n=network();var sink=new CompactNetworkChangeEvents.Collector(100);
        sink.add(event(n,-0.,0,NetworkChangeEvent.ChangeType.FACTOR,1));
        sink.add(event(n,+0.,1,NetworkChangeEvent.ChangeType.FACTOR,1));
        sink.add(event(n,+0.,2,NetworkChangeEvent.ChangeType.ABSOLUTE_IN_SI_UNITS,1));
        assertEquals(3,sink.finish().size());
    }
    @Test void parserUsesNativeValuesAndCompacts() throws Exception {
        var n=network();var events=new ArrayList<NetworkChangeEvent>();
        for(int i=0;i<4;i++)events.add(event(n,20,i,NetworkChangeEvent.ChangeType.ABSOLUTE_IN_SI_UNITS,5));
        var file=temporary.resolve("changes.xml.gz");new NetworkChangeEventsWriter().write(file.toString(),events);
        var result=CompactNetworkChangeEvents.load(n,file.toUri().toURL());
        assertEquals(4,result.inputEvents());assertEquals(1,result.outputEvents());
        assertEquals(5,n.getLinks().get(Id.createLinkId("l3")).getFreespeed(20));
    }
    @Test void scenarioLoaderRestoresInputPathOnSuccessAndFailure() throws Exception {
        var n=network();var networkFile=temporary.resolve("network.xml.gz");
        new org.matsim.api.core.v01.network.NetworkWriter(n).write(networkFile.toString());
        var changes=temporary.resolve("changes.xml.gz");
        new NetworkChangeEventsWriter().write(changes.toString(),List.of(event(n,20,0,NetworkChangeEvent.ChangeType.FACTOR,.5)));
        var cfg=ConfigUtils.createConfig();cfg.network().setTimeVariantNetwork(true);
        cfg.network().setInputFile(networkFile.toString());cfg.network().setChangeEventsInputFile(changes.toString());
        var scenario=ScenarioUtils.createScenario(cfg);CompactNetworkChangeEvents.loadScenario(scenario);
        assertEquals(changes.toString(),cfg.network().getChangeEventsInputFile());
        assertEquals(5,scenario.getNetwork().getLinks().get(Id.createLinkId("l0")).getFreespeed(20));
        var broken=ConfigUtils.createConfig();broken.network().setTimeVariantNetwork(true);
        broken.network().setChangeEventsInputFile(changes.toString());
        broken.network().setInputFile(temporary.resolve("missing.xml").toString());
        assertThrows(RuntimeException.class,()->CompactNetworkChangeEvents.loadScenario(ScenarioUtils.createScenario(broken)));
        assertEquals(changes.toString(),broken.network().getChangeEventsInputFile());
    }
}
