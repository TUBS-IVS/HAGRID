package hagrid.lausitz.modular;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Coord;
import org.matsim.core.network.NetworkUtils;
import static org.junit.jupiter.api.Assertions.*;

class ModularTimeWindowIntegrationTest {
    @Test void earlyArrivalWaitsForDeliveryStart() {
        var timing=ModularTourTiming.service(26640, 120, 27000, 28800).orElseThrow();
        assertEquals(360, timing.waitSeconds());
        assertEquals(27000, timing.startSeconds());
        assertEquals(27120, timing.endSeconds());
    }
    @Test void latestIsAnInclusiveStartLimit() {
        assertTrue(ModularTourTiming.service(28860,120,27000,28800).isEmpty());
        assertEquals(28920,ModularTourTiming.service(28800,120,27000,28800).orElseThrow().endSeconds());
    }
    @Test void rejectsMalformedWindowsAndServices() {
        for(double bad:new double[]{Double.NaN,Double.NEGATIVE_INFINITY,-1})
            assertThrows(IllegalArgumentException.class,()->new ModularFreightTour.Stop(Id.createLinkId("s"),1,1,bad,100));
        assertThrows(IllegalArgumentException.class,()->new ModularFreightTour.Stop(Id.createLinkId("s"),1,1,101,100));
        assertThrows(IllegalArgumentException.class,()->new ModularFreightTour.Stop(Id.createLinkId("s"),1,1,0,Double.NaN));
        assertThrows(IllegalArgumentException.class,()->new ModularFreightTour.Stop(Id.createLinkId("s"),-1,1));
        assertEquals(Double.POSITIVE_INFINITY,new ModularFreightTour.Stop(Id.createLinkId("s"),1,1).latestStart());
    }
    @Test void timingUpdateConsumesAbsoluteWaitInsteadOfRepeatingIt() {
        var net=NetworkUtils.createNetwork(); var f=net.getFactory();
        var a=f.createNode(Id.createNodeId("a"),new Coord(0,0)); var b=f.createNode(Id.createNodeId("b"),new Coord(1,0));
        net.addNode(a);net.addNode(b); var link=f.createLink(Id.createLinkId("s"),a,b);net.addLink(link);
        var calc=new ModularStayTaskEndTimeCalculator((v,t,start)->-123);
        var wait=new ModularFreightWaitTask(26640,27000,link,27000);
        assertEquals(27000,calc.calcNewEndTime(null,wait,26940));
        assertEquals(28860,calc.calcNewEndTime(null,wait,28860));
        var stop=new ModularFreightStopTask(27000,27120,link,1,"t",0,27000,28800);
        assertEquals(27120,calc.calcNewEndTime(null,stop,26640));
        assertEquals(28980,calc.calcNewEndTime(null,stop,28860));
    }
}
