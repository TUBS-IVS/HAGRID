package hagrid.core.routing;

import com.graphhopper.jsprit.core.problem.Location;
import com.graphhopper.jsprit.core.problem.vehicle.Vehicle;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleImpl;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleTypeImpl;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.router.DijkstraFactory;
import org.matsim.core.router.util.LeastCostPathCalculator;
import org.matsim.core.router.util.LeastCostPathCalculatorFactory;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.*;

class ZoneBasedTransportCostsTest {

    private static final class Fixture {
        final String prefix = UUID.randomUUID().toString();
        final Network network = NetworkUtils.createNetwork();
        final Link origin;
        final Link middle;
        final Link destination;
        final String typeId = prefix + "-type";

        Fixture() {
            Node a = NetworkUtils.createAndAddNode(network, Id.createNodeId(prefix + "-a"), new Coord(0, 0));
            Node b = NetworkUtils.createAndAddNode(network, Id.createNodeId(prefix + "-b"), new Coord(100, 0));
            Node c = NetworkUtils.createAndAddNode(network, Id.createNodeId(prefix + "-c"), new Coord(300, 0));
            Node d = NetworkUtils.createAndAddNode(network, Id.createNodeId(prefix + "-d"), new Coord(600, 0));
            origin = NetworkUtils.createAndAddLink(network, Id.createLinkId(prefix + "-a"), a, b, 100, 10, 500, 1);
            middle = NetworkUtils.createAndAddLink(network, Id.createLinkId(prefix + "-b"), b, c, 200, 10, 500, 1);
            destination = NetworkUtils.createAndAddLink(network, Id.createLinkId(prefix + "-c"), c, d, 300, 10, 500, 1);
            for (Link link : network.getLinks().values()) link.setAllowedModes(Set.of("car"));
        }

        Location from() { return Location.newInstance(origin.getId().toString()); }
        Location to() { return Location.newInstance(destination.getId().toString()); }

        Vehicle vehicle(double speed) {
            return VehicleImpl.Builder.newInstance(prefix + "-vehicle")
                    .setType(VehicleTypeImpl.Builder.newInstance(typeId).setMaxVelocity(speed).build())
                    .setStartLocation(from()).build();
        }

        void addReverseLinks() {
            for (Link original : List.copyOf(network.getLinks().values())) {
                Link reverse = NetworkUtils.createAndAddLink(network, Id.createLinkId("reverse-" + original.getId()),
                        original.getToNode(), original.getFromNode(),original.getLength(),10,500,1);
                reverse.setAllowedModes(Set.of("car"));
                Object zone = original.getAttributes().getAttribute("zone");
                if (zone != null) reverse.getAttributes().putAttribute("zone",zone);
            }
        }

        Link isolatedLink(double x) {
            Node a = NetworkUtils.createAndAddNode(network,Id.createNodeId(prefix + "-isolated-a"),new Coord(x-50,0));
            Node b = NetworkUtils.createAndAddNode(network,Id.createNodeId(prefix + "-isolated-b"),new Coord(x+50,0));
            Link link = NetworkUtils.createAndAddLink(network,Id.createLinkId("0-"+prefix),a,b,100,10,500,1);
            link.setAllowedModes(Set.of("car")); link.getAttributes().putAttribute("zone",1);
            return link;
        }

        ZoneBasedTransportCosts.Builder builder() {
            ZoneBasedTransportCosts.Builder builder = ZoneBasedTransportCosts.Builder.newInstance(network);
            builder.addVehicleTypeSpecificCosts(typeId, 0, 1, 0);
            return builder;
        }

        ZoneBasedTransportCosts constantTime(double seconds) {
            return builder().setTravelTime((link, time, person, vehicle) -> seconds).build();
        }
    }

    @Test
    void separateCostModelsDoNotReuseEachOthersTravelTimes() {
        Fixture f = new Fixture();
        ZoneBasedTransportCosts first = f.constantTime(10);
        ZoneBasedTransportCosts second = f.constantTime(20);
        assertEquals(20, first.getTransportTime(f.from(), f.to(), 0, null, f.vehicle(10)));
        assertEquals(40, second.getTransportTime(f.from(), f.to(), 0, null, f.vehicle(10)));
    }

    @Test
    void rebuildingABuilderUsesTheNewTravelTimeForCostsToo() {
        Fixture f = new Fixture();
        ZoneBasedTransportCosts.Builder builder = f.builder().setTravelTime((l, t, p, v) -> 10);
        ZoneBasedTransportCosts first = builder.build();
        ZoneBasedTransportCosts second = builder.setTravelTime((l, t, p, v) -> 20).build();
        assertEquals(20, first.getTransportCost(f.from(), f.to(), 0, null, f.vehicle(10)));
        assertEquals(40, second.getTransportCost(f.from(), f.to(), 0, null, f.vehicle(10)));
    }

    @Test
    void distanceCoversTheSameLinksAsTravelTimeAndCost() {
        Fixture f = new Fixture();
        ZoneBasedTransportCosts costs = f.builder().build();
        // Routing starts at the end of the origin link: middle (200 m) + destination (300 m).
        assertEquals(500, costs.getDistance(f.from(), f.to(), 0, f.vehicle(10)));
        assertEquals(50, costs.getTransportTime(f.from(), f.to(), 0, null, f.vehicle(10)));
        assertEquals(50, costs.getTransportCost(f.from(), f.to(), 0, null, f.vehicle(10)));
    }

    @Test
    void timeSlicesAreEvaluatedAtTheirStartRegardlessOfFirstRequest() {
        Fixture f = new Fixture();
        ZoneBasedTransportCosts costs = f.builder().setTimeSliceWidth(1800)
                .setTravelTime((link, time, person, vehicle) -> 10 + time / 100).build();
        // At t=0 the middle takes 10 s; destination is entered at t=10 and takes 10.1 s.
        assertEquals(20.1, costs.getTransportTime(f.from(), f.to(), 1799, null, f.vehicle(10)), 1e-9);
        assertEquals(20.1, costs.getTransportTime(f.from(), f.to(), 100, null, f.vehicle(10)), 1e-9);
    }

    @Test
    void exactResultsIgnoreZoneAttributesAndRequestOrder() {
        Fixture f = new Fixture();
        f.origin.getAttributes().putAttribute("zone", 1);
        f.middle.getAttributes().putAttribute("zone", 1);
        f.destination.getAttributes().putAttribute("zone", 2);
        f.addReverseLinks();
        ZoneBasedTransportCosts first = f.builder().build();
        ZoneBasedTransportCosts reversed = f.builder().build();
        first.getTransportCost(Location.newInstance(f.middle.getId().toString()), f.to(), 0, null, null);
        double afterDifferentPair = first.getTransportCost(f.from(), f.to(), 0, null, null);
        double requestedFirst = reversed.getTransportCost(f.from(), f.to(), 0, null, null);
        assertEquals(requestedFirst, afterDifferentPair, 1e-9);
        assertEquals(2,first.snapshotAndResetSummaryWindow().uniqueEntries());
    }

    @Test
    void isolatedZoneRepresentativeCannotHideAReachableOriginalPath() {
        Fixture f = new Fixture();
        f.origin.getAttributes().putAttribute("zone",1); f.destination.getAttributes().putAttribute("zone",2);
        f.addReverseLinks(); f.isolatedLink(50);
        ZoneBasedTransportCosts costs = f.builder().build();
        assertTrue(costs.getTransportTime(f.from(),f.to(),0,null,f.vehicle(10)) < Double.MAX_VALUE);
    }

    @Test
    void connectedZoneRepresentativesCannotHideAnUnreachableOriginalPath() {
        Fixture f = new Fixture();
        f.origin.getAttributes().putAttribute("zone",1); f.destination.getAttributes().putAttribute("zone",2);
        f.addReverseLinks(); Link isolated = f.isolatedLink(-950);
        ZoneBasedTransportCosts costs = f.builder().build();
        assertEquals(Double.MAX_VALUE,costs.getTransportTime(Location.newInstance(isolated.getId().toString()),f.to(),0,null,f.vehicle(10)));
    }

    @Test
    void vehiclesWithDifferentMaximumSpeedsDoNotShareTransportData() {
        Fixture f = new Fixture();
        ZoneBasedTransportCosts costs = f.builder().build();
        assertEquals(50, costs.getTransportTime(f.from(), f.to(), 0, null, f.vehicle(10)));
        assertEquals(100, costs.getTransportTime(f.from(), f.to(), 0, null, f.vehicle(5)));
    }

    @Test
    void originalVehicleNetworkModeIsPreserved() {
        Fixture f = new Fixture();
        for (Link link : f.network.getLinks().values()) link.setAllowedModes(Set.of("bike"));
        VehicleType type = VehicleUtils.createVehicleType(Id.create(f.typeId, VehicleType.class));
        type.setNetworkMode("bike");
        type.getCostInformation().setCostsPerSecond(1.0);
        type.getCostInformation().setCostsPerMeter(0.0);
        ZoneBasedTransportCosts costs = ZoneBasedTransportCosts.Builder.newInstance(f.network, List.of(type)).build();
        assertEquals(50, costs.getTransportTime(f.from(), f.to(), 0, null, f.vehicle(10)));
    }

    @Test
    void originalVehicleWithoutModeDefaultsToCar() {
        Fixture f = new Fixture();
        VehicleType type = VehicleUtils.createVehicleType(Id.create(f.typeId, VehicleType.class));
        type.getCostInformation().setCostsPerSecond(1.0);
        type.getCostInformation().setCostsPerMeter(0.0);
        ZoneBasedTransportCosts costs = ZoneBasedTransportCosts.Builder.newInstance(f.network, List.of(type)).build();
        assertEquals(50, costs.getTransportTime(f.from(), f.to(), 0, null, f.vehicle(10)));
    }

    @Test
    void disconnectedRelationsReturnInfeasibleCostsInsteadOfThrowing() {
        Fixture f = new Fixture();
        f.network.removeLink(f.middle.getId());
        ZoneBasedTransportCosts costs = f.builder().setThreadSafeLeastCostPathCalculatorFactory(new DijkstraFactory()).build();
        assertEquals(Double.MAX_VALUE, costs.getTransportTime(f.from(), f.to(), 0, null, f.vehicle(10)));
        assertEquals(Double.MAX_VALUE, costs.getTransportCost(f.from(), f.to(), 0, null, f.vehicle(10)));
        assertEquals(Double.MAX_VALUE, costs.getDistance(f.from(), f.to(), 0, f.vehicle(10)));
    }

    @Test
    void rejectsNonPositiveTimeSlices() {
        Fixture f = new Fixture();
        assertThrows(IllegalArgumentException.class, () -> f.builder().setTimeSliceWidth(0));
        assertThrows(IllegalArgumentException.class, () -> f.builder().setTimeSliceWidth(-1));
    }

    @Test
    void clearingAfterTravelTimeUpdatesStartsAFreshCacheGeneration() {
        Fixture f = new Fixture();
        AtomicInteger seconds = new AtomicInteger(10);
        ZoneBasedTransportCosts costs = f.builder().setTravelTime((l, t, p, v) -> seconds.get()).build();
        assertEquals(20, costs.getTransportTime(f.from(), f.to(), 0, null, f.vehicle(10)));
        seconds.set(20);
        costs.clearCache();
        assertEquals(40, costs.getTransportTime(f.from(), f.to(), 0, null, f.vehicle(10)));
    }

    @Test
    void anOldInFlightCalculationCannotPopulateTheNewGeneration() throws Exception {
        Fixture f = new Fixture();
        var seconds = new AtomicInteger(10);
        var calls = new AtomicInteger();
        var oldPathReady = new CountDownLatch(1);
        var releaseOldPath = new CountDownLatch(1);
        LeastCostPathCalculatorFactory factory = (network, disutility, travelTime) -> {
            var delegate = new DijkstraFactory().createPathCalculator(network, disutility, travelTime);
            return (from,to,time,person,vehicle) -> {
                var path = delegate.calcLeastCostPath(from,to,time,person,vehicle);
                if (calls.incrementAndGet() == 1) {
                    oldPathReady.countDown();
                    try {
                        if (!releaseOldPath.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("Test timed out");
                    } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                }
                return path;
            };
        };
        var costs = f.builder().setTravelTime((l,t,p,v)->seconds.get()).setThreadSafeLeastCostPathCalculatorFactory(factory).build();
        var executor = Executors.newFixedThreadPool(2);
        try {
            var oldRequest = executor.submit(()->costs.getTransportTime(f.from(),f.to(),0,null,f.vehicle(10)));
            assertTrue(oldPathReady.await(3,TimeUnit.SECONDS));
            seconds.set(20); costs.clearCache();
            var newRequest = executor.submit(()->costs.getTransportTime(f.from(),f.to(),0,null,f.vehicle(10)));
            assertEquals(40,newRequest.get(3,TimeUnit.SECONDS));
            releaseOldPath.countDown();
            assertEquals(30,oldRequest.get(3,TimeUnit.SECONDS));
            assertEquals(40,costs.getTransportTime(f.from(),f.to(),0,null,f.vehicle(10)));
            assertEquals(1,costs.snapshotAndResetSummaryWindow().uniqueEntries());
        } finally { releaseOldPath.countDown(); executor.shutdownNow(); }
    }

    @Test
    void evictionBoundsMemoryAndRecomputedValuesRemainCorrect() {
        Fixture f = new Fixture();
        ZoneBasedTransportCosts costs = f.builder().setMaximumCacheEntries(1).build();
        assertEquals(50, costs.getTransportTime(f.from(), f.to(), 0, null, f.vehicle(10)));
        assertEquals(30, costs.getTransportTime(Location.newInstance(f.middle.getId().toString()), f.to(), 0, null, f.vehicle(10)));
        assertEquals(1, costs.snapshotAndResetSummaryWindow().uniqueEntries());
        assertEquals(50, costs.getTransportTime(f.from(), f.to(), 0, null, f.vehicle(10)));
        assertEquals(3, costs.snapshotAndResetSummaryWindow().totalMemoized());
    }

    @Test
    void builtCostModelDoesNotChangeWhenBuilderCostsAreOverwritten() {
        Fixture f = new Fixture();
        ZoneBasedTransportCosts.Builder builder = f.builder();
        ZoneBasedTransportCosts first = builder.build();
        builder.addVehicleTypeSpecificCosts(f.typeId, 0, 2, 0);
        assertEquals(50, first.getTransportCost(f.from(), f.to(), 0, null, f.vehicle(10)));
        assertEquals(100, builder.build().getTransportCost(f.from(), f.to(), 0, null, f.vehicle(10)));
    }

    @Test
    void concurrentIdenticalRequestsCalculateOnlyOneRealNetworkPath() throws Exception {
        Fixture f = new Fixture();
        AtomicInteger pathCalculations = new AtomicInteger();
        CountDownLatch releaseCalculation = new CountDownLatch(1);
        LeastCostPathCalculatorFactory factory = (network, disutility, travelTime) -> {
            LeastCostPathCalculator delegate = new DijkstraFactory().createPathCalculator(network, disutility, travelTime);
            return (from, to, time, person, vehicle) -> {
                pathCalculations.incrementAndGet();
                try {
                    if (!releaseCalculation.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Test timed out");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return delegate.calcLeastCostPath(from, to, time, person, vehicle);
            };
        };
        ZoneBasedTransportCosts costs = f.builder().setTravelTime((l, t, p, v) -> 10)
                .setThreadSafeLeastCostPathCalculatorFactory(factory).build();
        var executor = Executors.newFixedThreadPool(4);
        try {
            List<Future<Double>> results = new ArrayList<>();
            for (int i = 0; i < 4; i++) results.add(executor.submit(() ->
                    costs.getTransportTime(f.from(), f.to(), 0, null, f.vehicle(10))));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (costs.snapshotAndResetSummaryWindow().totalRequests() < 4 && System.nanoTime() < deadline)
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
            assertEquals(4, costs.snapshotAndResetSummaryWindow().totalRequests());
            releaseCalculation.countDown();
            for (Future<Double> result : results) assertEquals(20, result.get(5, TimeUnit.SECONDS));
            assertEquals(1, pathCalculations.get());
        } finally {
            releaseCalculation.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void aRoutingFailureReturnsTheBorrowedRouterAndAllowsRetry() {
        Fixture f = new Fixture();
        AtomicInteger creations = new AtomicInteger();
        AtomicInteger calls = new AtomicInteger();
        LeastCostPathCalculatorFactory factory = (network, disutility, travelTime) -> {
            creations.incrementAndGet();
            LeastCostPathCalculator delegate = new DijkstraFactory().createPathCalculator(network, disutility, travelTime);
            return (from, to, time, person, vehicle) -> {
                if (calls.incrementAndGet() == 1) throw new IllegalStateException("First route failed");
                return delegate.calcLeastCostPath(from, to, time, person, vehicle);
            };
        };
        ZoneBasedTransportCosts costs = f.builder().setTravelTime((l, t, p, v) -> 10).setRouterPoolSize(1)
                .setThreadSafeLeastCostPathCalculatorFactory(factory).build();
        assertThrows(IllegalStateException.class, () -> costs.getTransportTime(f.from(), f.to(), 0, null, f.vehicle(10)));
        assertEquals(20, costs.getTransportTime(f.from(), f.to(), 0, null, f.vehicle(10)));
        assertEquals(1, creations.get());
    }
}
