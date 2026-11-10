package hagrid.core.routing;

import com.graphhopper.jsprit.core.problem.Location;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleImpl;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleTypeImpl;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.core.network.NetworkUtils;
import org.matsim.freight.carriers.jsprit.NetworkBasedTransportCosts;

import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class NativeNeutralVehicleTest {
    private NetworkBasedTransportCosts costs(double secondsPerMeter) {
        var network=NetworkUtils.createNetwork();
        var nodes=new org.matsim.api.core.v01.network.Node[4];
        for(int i=0;i<4;i++) nodes[i]=NetworkUtils.createAndAddNode(network,Id.createNodeId(i),new Coord(i*100,0));
        for(int i=0;i<3;i++) {
            String id=String.valueOf((char)('a'+i));
            NetworkUtils.createAndAddLink(network,Id.createLinkId(id),nodes[i],nodes[i+1],100,10,1000,1);
            NetworkUtils.createAndAddLink(network,Id.createLinkId(id+"R"),nodes[i+1],nodes[i],100,10,1000,1);
        }
        var builder=NetworkBasedTransportCosts.Builder.newInstance(network);
        builder.addVehicleTypeSpecificCosts("van",0,0,1);
        builder.setTimeSliceWidth(300);
        builder.setTravelTime((link,time,person,vehicle) -> link.getLength()*secondsPerMeter*(time>=300?2:1));
        return builder.build();
    }

    @Test
    void neutralRequestsKeepExactCostsForChangingOriginsAndTimeBins() {
        var costs=costs(0.1);
        var van=VehicleImpl.Builder.newInstance("van").setType(VehicleTypeImpl.Builder.newInstance("van").build())
                .setStartLocation(Location.newInstance("a")).build();
        for(double time:new double[]{0,301,0}) for(String[] pair:new String[][]{{"a","c"},{"cR","aR"},{"b","c"}}) {
            var from=Location.newInstance(pair[0]);var to=Location.newInstance(pair[1]);
            assertThat(costs.getTransportTime(from,to,time,null,null)).isEqualTo(costs.getTransportTime(from,to,time,null,van));
            assertThat(costs.getTransportCost(from,to,time,null,null)).isEqualTo(costs.getTransportCost(from,to,time,null,van));
            assertThat(costs.getDistance(from,to,time,null)).isEqualTo(costs.getDistance(from,to,time,van));
        }
    }

    @Test
    void differentProvidersRetainIndependentNeutralProfiles() {
        var fast=costs(0.1);var slow=costs(0.2);
        var from=Location.newInstance("a");var to=Location.newInstance("c");
        assertThat(fast.getTransportTime(from,to,0,null,null)).isEqualTo(20);
        assertThat(slow.getTransportTime(from,to,0,null,null)).isEqualTo(40);
        assertThat(fast.getTransportTime(from,to,301,null,null)).isEqualTo(40);
        assertThat(slow.getTransportTime(from,to,301,null,null)).isEqualTo(80);
        assertThat(fast.getTransportTime(from,to,0,null,null)).isEqualTo(20);
    }

    @Test
    void parallelNeutralFirstRequestsRemainIndependentOfTheOrigin() throws Exception {
        var costs=costs(0.1);var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try {
            var forward=pool.submit(() -> {gate.await();return costs.getTransportTime(Location.newInstance("a"),Location.newInstance("c"),0,null,null);});
            var backward=pool.submit(() -> {gate.await();return costs.getTransportTime(Location.newInstance("cR"),Location.newInstance("aR"),0,null,null);});
            gate.countDown();
            assertThat(forward.get(15,TimeUnit.SECONDS)).isEqualTo(20);
            assertThat(backward.get(15,TimeUnit.SECONDS)).isEqualTo(20);
        } finally {
            pool.shutdownNow();assertThat(pool.awaitTermination(5,TimeUnit.SECONDS)).isTrue();
        }
    }
}
