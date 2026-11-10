package hagrid.core.routing;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.*;
import org.matsim.api.core.v01.network.*;
import org.matsim.core.network.NetworkUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RawTemporalNodeRouterTest {
    @Test void inverseDurationRoundTripCannotRoundAcrossAnOpenIslandBoundary() {
        try(var f=RawDistanceTemporalRoutingTest.fixture((l,t,p,v)->t>=100&&t<110?10:20000,10,10,20000)) {
            var costs=f.costs();double deadline=10000;
            double departure=deadline-costs.getBackwardTransportTime(RawDistanceTemporalRoutingTest.from(),RawDistanceTemporalRoutingTest.to(),deadline,null,f.vehicle());
            assertTrue(departure>=100&&departure<110,"Scalar subtraction must still land inside the exact feasible interval");
            assertTrue(departure+costs.getTransportTime(RawDistanceTemporalRoutingTest.from(),RawDistanceTemporalRoutingTest.to(),departure,null,f.vehicle())<=deadline);
        }
    }
    @Test void equalDistanceNodePathDoesNotSwitchWithTimeDependentArrivalTie() {
        try(var f=RawDistanceTemporalRoutingTest.fixture((l,t,p,v)->l.getId().toString().equals("destination")?(t<900?1000:1):10,
                900,0,10800,RawTemporalNodeRouterTest::alternative)) {
            var router=f.costs().getRouter();var network=f.context().network();
            var a=network.getNodes().get(Id.createNodeId("b"));var b=network.getNodes().get(Id.createNodeId("c"));
            var early=router.calcLeastCostPath(a,b,0,null,f.nativeVehicle());
            var later=router.calcLeastCostPath(a,b,900,null,f.nativeVehicle());
            assertEquals(early.links.stream().map(Link::getId).toList(),later.links.stream().map(Link::getId).toList(),
                    "Distance-only tie resolution must not depend on the raw time profile");
        }
    }

    @Test void unreachableTimeOnAnUnusedAlternativeDoesNotAbortStaticDistanceSearch() {
        try(var f=RawDistanceTemporalRoutingTest.fixture((l,t,p,v)->l.getId().toString().equals("x")?(t<900?1000000:900000):7,
                900,0,10800,RawTemporalNodeRouterTest::alternative)) {
            var router=f.costs().getRouter();var network=f.context().network();
            var path=assertDoesNotThrow(()->router.calcLeastCostPath(network.getNodes().get(Id.createNodeId("b")),
                    network.getNodes().get(Id.createNodeId("c")),0,null,f.nativeVehicle()));
            assertEquals(List.of(Id.createLinkId("destination")),path.links.stream().map(Link::getId).toList());
            assertEquals(7,path.travelTime);
        }
    }

    private static void alternative(Network n) {
        var b=n.getNodes().get(Id.createNodeId("b"));var c=n.getNodes().get(Id.createNodeId("c"));
        var z=NetworkUtils.createAndAddNode(n,Id.createNodeId("z"),new Coord(150,100));
        for(var link:List.of(NetworkUtils.createAndAddLink(n,Id.createLinkId("x"),b,z,50,10,1000,1),
                NetworkUtils.createAndAddLink(n,Id.createLinkId("y"),z,c,50,10,1000,1)))link.setAllowedModes(Set.of("car"));
    }
}
