package hagrid.core.routing;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.*;
import org.matsim.api.core.v01.network.*;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.turnRestrictions.DisallowedNextLinks;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RawTemporalRestrictionsTest {
    @Test void distancePathRespectsDestinationRestrictionThenUsesActualIntermediateEntryTimes() {
        try(var f=RawDistanceTemporalRoutingTest.fixture((l,t,p,v)->switch(l.getId().toString()) {
            case "x"->10;case "y"->t<900?30:5;case "destination"->7;default->1;
        },900,0,10800,n->detour(n,false))) {
            var costs=f.costs();
            var road=costs.routeLeg(Id.createLinkId("origin"),Id.createLinkId("destination"),895,f.nativeVehicle());
            assertEquals(List.of(Id.createLinkId("x"),Id.createLinkId("y")),road.route().getLinkIds());
            assertEquals(22,road.travelTimeSeconds());
            assertEquals(300,road.distanceMeters());
            assertEquals(300*RawDistanceTemporalRoutingTest.RATE,road.monetaryCost(),1e-12);
            assertEquals(47,costs.getTransportTime(RawDistanceTemporalRoutingTest.from(),RawDistanceTemporalRoutingTest.to(),895,null,f.vehicle()));
        }
    }

    @Test void multiLinkRestrictionNeedsSecondLoopAndIsNotLostByDistanceStateDominance() {
        try(var f=RawDistanceTemporalRoutingTest.fixture((l,t,p,v)->t<900?10:5,900,0,10800,n->detour(n,true))) {
            var road=f.costs().routeLeg(Id.createLinkId("origin"),Id.createLinkId("destination"),900,f.nativeVehicle());
            assertEquals(List.of(Id.createLinkId("x"),Id.createLinkId("y"),Id.createLinkId("x"),Id.createLinkId("y")),road.route().getLinkIds());
            assertEquals(25,road.travelTimeSeconds());
            assertEquals(500,road.distanceMeters());
        }
    }

    @Test void inverseIntervalsRetainInfeasibleGapsInsteadOfPretendingEveryEarlierDepartureWorks() {
        try(var f=RawDistanceTemporalRoutingTest.fixture((l,t,p,v)->t>=10&&t<20||t>=40&&t<50?5:1000,10,10,2000)) {
            var intervals=f.costs().feasibleDepartureIntervals(RawDistanceTemporalRoutingTest.from(),RawDistanceTemporalRoutingTest.to(),60,f.vehicle());
            assertEquals(2,intervals.size());
            assertEquals(10,intervals.get(0).firstDeparture());assertEquals(Math.nextDown(20.0),intervals.get(0).lastDeparture());
            assertEquals(40,intervals.get(1).firstDeparture());assertEquals(Math.nextDown(50.0),intervals.get(1).lastDeparture());
        }
    }

    private static void detour(Network n,boolean multi) {
        var b=n.getNodes().get(Id.createNodeId("b"));
        var z=NetworkUtils.createAndAddNode(n,Id.createNodeId("z"),new Coord(100,100));
        for(var link:List.of(NetworkUtils.createAndAddLink(n,Id.createLinkId("x"),b,z,100,10,1000,1),
                NetworkUtils.createAndAddLink(n,Id.createLinkId("y"),z,b,100,10,1000,1)))link.setAllowedModes(Set.of("car"));
        var restriction=new DisallowedNextLinks();restriction.addDisallowedLinkSequence("car",List.of(Id.createLinkId("destination")));
        if(multi)restriction.addDisallowedLinkSequence("car",List.of(Id.createLinkId("x"),Id.createLinkId("y"),Id.createLinkId("destination")));
        NetworkUtils.setDisallowedNextLinks(n.getLinks().get(Id.createLinkId("origin")),restriction);
    }
}
