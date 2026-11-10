package hagrid.core.routing;

import com.graphhopper.jsprit.core.problem.Location;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleImpl;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleTypeImpl;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.router.speedy.SpeedyALTFactory;
import org.matsim.freight.carriers.jsprit.NetworkBasedTransportCosts;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class NativeTransportCacheHitTest {
    @Test
    void cachedReadNeedsNoThreadRouterWhileTimeAndVehicleMissesStillRoute() throws Exception {
        var network = NetworkUtils.createNetwork();
        var nodes = new org.matsim.api.core.v01.network.Node[4];
        for (int i = 0; i < nodes.length; i++) {
            nodes[i] = NetworkUtils.createAndAddNode(network, Id.createNodeId(i), new Coord(i * 100, 0));
        }
        for (int i = 0; i < 3; i++) {
            String id = String.valueOf((char) ('a' + i));
            NetworkUtils.createAndAddLink(network, Id.createLinkId(id), nodes[i], nodes[i + 1], 100, 10, 1000, 1);
            NetworkUtils.createAndAddLink(network, Id.createLinkId(id + "R"), nodes[i + 1], nodes[i], 100, 10, 1000, 1);
        }
        var routerBuilds = new AtomicInteger();
        var starts = new AtomicInteger();
        var ends = new AtomicInteger();
        var builder = NetworkBasedTransportCosts.Builder.newInstance(network);
        builder.addVehicleTypeSpecificCosts("van", 0, 0, 1);
        builder.addVehicleTypeSpecificCosts("special", 0, 0, 2);
        builder.setTimeSliceWidth(300);
        builder.setTravelTime((link, time, person, vehicle) -> link.getLength() / (time >= 300 ? 5 : 10));
        builder.setThreadSafeLeastCostPathCalculatorFactory((n, disutility, times) -> {
            routerBuilds.incrementAndGet();
            return new SpeedyALTFactory().createPathCalculator(n, disutility, times);
        });
        var costs = builder.build();
        costs.getInternalListeners().add(new NetworkBasedTransportCosts.InternalLeastCostPathCalculatorListener() {
            @Override public void startCalculation(long routerId) { starts.incrementAndGet(); }
            @Override public void endCalculation(long routerId) { ends.incrementAndGet(); }
        });
        var from = Location.newInstance("a");
        var to = Location.newInstance("c");
        var van = VehicleImpl.Builder.newInstance("van").setType(VehicleTypeImpl.Builder.newInstance("van").build())
                .setStartLocation(from).build();
        var special = VehicleImpl.Builder.newInstance("special").setType(VehicleTypeImpl.Builder.newInstance("special").build())
                .setStartLocation(from).build();
        assertThat(costs.getTransportTime(from, to, 0, null, van)).isEqualTo(20);
        assertThat(routerBuilds).hasValue(1);
        var pool = Executors.newSingleThreadExecutor();
        try {
            assertThat(pool.submit(() -> costs.getTransportCost(from, to, 100, null, van)).get(15, TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(routerBuilds).as("a shared cached value needs no additional path calculator").hasValue(1);
            assertThat(pool.submit(() -> costs.getTransportCost(from, to, 301, null, van)).get(15, TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(routerBuilds).hasValue(2);
            assertThat(pool.submit(() -> costs.getTransportTime(from, to, 301, null, van)).get(15, TimeUnit.SECONDS)).isEqualTo(40);
            assertThat(pool.submit(() -> costs.getTransportCost(from, to, 0, null, special)).get(15, TimeUnit.SECONDS)).isEqualTo(400);
            assertThat(routerBuilds).hasValue(2);
            assertThat(starts).hasValue(3);
            assertThat(ends).hasValue(3);
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }
}
