package hagrid.core.routing;

import com.graphhopper.jsprit.core.algorithm.listener.IterationEndsListener;
import com.graphhopper.jsprit.core.problem.Location;
import com.graphhopper.jsprit.core.problem.VehicleRoutingProblem;
import com.graphhopper.jsprit.core.problem.job.Service;
import com.graphhopper.jsprit.core.problem.solution.route.activity.TourActivity;
import com.graphhopper.jsprit.core.problem.solution.route.activity.TimeWindow;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleImpl;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleTypeImpl;
import com.graphhopper.jsprit.core.util.Solutions;
import hagrid.core.routing.benchmark.RoutingComparison;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.*;
import org.matsim.core.network.NetworkUtils;
import org.matsim.freight.carriers.jsprit.NetworkBasedTransportCosts;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Same fixture and HAGRID solver entry point, compiled separately against each checkout. */
public final class RoutingComparisonProbe {
    private static final int JOBS = 60, ITERATIONS = 30, CAPACITY = 40;
    private static final String INPUT = "grid35-100m-10mps-services60-demand1234-cap40-service60-fix100-window25200-v1";
    private static final String RULES = "native-network-costs-regret-fastfalse-max25200-iterations30-v1";

    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !args[0].equals("services")) {
            throw new IllegalArgumentException("Usage: RoutingComparisonProbe services SEED HEAP_MB");
        }
        int seed = Integer.parseInt(args[1]), heapMb = Integer.parseInt(args[2]);
        if (Runtime.version().feature() != 21 || Runtime.getRuntime().availableProcessors() != 1
                || Runtime.getRuntime().maxMemory() > (long) heapMb * 1024 * 1024) {
            throw new IllegalStateException("Expected Java 21, one active processor and the declared heap ceiling");
        }
        run(seed, heapMb, false);
        run(seed, heapMb, false);
        run(seed, heapMb, true);
    }

    private static void run(int seed, int heapMb, boolean emit) throws Exception {
        System.setProperty(HAGRIDRouterUtils.JSPRIT_SEED_PROPERTY, Integer.toString(seed));
        AtomicLong peak = new AtomicLong(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
        ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor();
        sampler.scheduleAtFixedRate(() -> peak.accumulateAndGet(
                ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(), Math::max), 0, 5, TimeUnit.MILLISECONDS);
        long gcBefore = gcMillis(), start = System.nanoTime();
        var allocationBean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long allocationsBefore = allocationBean.isThreadAllocatedMemorySupported()
                ? allocationBean.getThreadAllocatedBytes(Thread.currentThread().threadId()) : -1;
        try {
            Network network = grid();
            List<Link> links = new ArrayList<>(network.getLinks().values());
            var costsBuilder = NetworkBasedTransportCosts.Builder.newInstance(network);
            costsBuilder.addVehicleTypeSpecificCosts("van", 100, 1, 0);
            var costs = costsBuilder.build();
            Location depot = Location.newInstance(links.getFirst().getId().toString());
            var type = VehicleTypeImpl.Builder.newInstance("van").addCapacityDimension(0, CAPACITY)
                    .setMaxVelocity(10).setFixedCost(100).setCostPerTransportTime(1).build();
            var vehicle = VehicleImpl.Builder.newInstance("van").setType(type).setStartLocation(depot)
                    .setLatestArrival(25200).build();
            var builder = VehicleRoutingProblem.Builder.newInstance().setRoutingCost(costs).addVehicle(vehicle)
                    .setFleetSize(VehicleRoutingProblem.FleetSize.INFINITE);
            Random demand = new Random(1234);
            for (int i = 0; i < JOBS; i++) {
                builder.addJob(Service.Builder.newInstance("job-" + i).addSizeDimension(0, 1).setServiceTime(60)
                        .setTimeWindow(TimeWindow.newInstance(0, 25200))
                        .setLocation(Location.newInstance(links.get(demand.nextInt(links.size())).getId().toString())).build());
            }
            long prepared = System.nanoTime();
            var algorithm = HAGRIDRouterUtils.configureAlgorithm(builder.build(), JOBS, ITERATIONS, null, 0, 25200);
            AtomicInteger iterations = new AtomicInteger();
            algorithm.addListener((IterationEndsListener) (i, p, s) -> iterations.incrementAndGet());
            long searchStart = System.nanoTime();
            var solution = Solutions.bestOf(algorithm.searchSolutions());
            long searchEnd = System.nanoTime();
            Set<String> served = new HashSet<>();
            List<String> canonicalRoutes = new ArrayList<>();
            int violations = 0;
            double exactCost = 0, meters = 0;
            for (var route : solution.getRoutes()) {
                double departure = route.getDepartureTime(), time = departure;
                String previous = route.getStart().getLocation().getId();
                List<String> order = new ArrayList<>();
                exactCost += 100;
                if (route.getActivities().size() > CAPACITY) violations++;
                for (var activity : route.getActivities()) {
                    String id = ((TourActivity.JobActivity) activity).getJob().getId();
                    if (!served.add(id)) violations++;
                    order.add(id);
                    String next = activity.getLocation().getId();
                    double leg = referenceSeconds(network, previous, next);
                    time += leg; exactCost += leg; meters += leg * 10;
                    if (time > activity.getTheoreticalLatestOperationStartTime() + 1e-7) violations++;
                    time = Math.max(time, activity.getTheoreticalEarliestOperationStartTime()) + 60;
                    previous = next;
                }
                double lastLeg = referenceSeconds(network, previous, route.getEnd().getLocation().getId());
                time += lastLeg; exactCost += lastLeg; meters += lastLeg * 10;
                if (time - departure > 25200 + 1e-7 || time > 25200 + 1e-7 || departure < 0) violations++;
                canonicalRoutes.add(String.join(",", order));
            }
            canonicalRoutes.sort(String::compareTo);
            long completed = System.nanoTime();
            double elapsed = (completed - start) / 1e6;
            peak.accumulateAndGet(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(), Math::max);
            long allocated = allocationsBefore < 0 ? -1 : allocationBean.getThreadAllocatedBytes(
                    Thread.currentThread().threadId()) - allocationsBefore;
            var metrics = new RoutingComparison.Metrics("services", sha(INPUT), sha(RULES), "fixed-freeflow",
                    seed, ITERATIONS, heapMb, JOBS, served.size(), violations, solution.getRoutes().size(),
                    exactCost, elapsed, peak.get());
            if (emit) {
                System.out.printf(Locale.ROOT,
                        "MEASUREMENT,services,%d,%d,%d,%d,%d,%d,%d,%d,%.9f,%.9f,%.3f,%.3f,%.3f,%.3f,%.3f,%d,%d,%d,%s,%s,%s%n",
                        seed, heapMb, ITERATIONS, iterations.get(), JOBS, served.size(), violations,
                        solution.getRoutes().size(), solution.getCost(), metrics.finalCost(), elapsed,
                        (searchStart - start) / 1e6, (prepared - start) / 1e6,
                        (searchEnd - searchStart) / 1e6, (completed - searchEnd) / 1e6, peak.get(), allocated,
                        gcMillis() - gcBefore, sha(String.join(";", canonicalRoutes)), sha(INPUT), sha(RULES));
                System.out.printf(Locale.ROOT, "DISTANCE,%.3f%n", meters);
            }
        } finally {
            sampler.shutdownNow();
            if (!sampler.awaitTermination(5, TimeUnit.SECONDS)) throw new IllegalStateException("Memory sampler did not stop");
        }
    }

    private static Network grid() {
        Network network = NetworkUtils.createNetwork();
        Node[][] nodes = new Node[35][35];
        for (int x = 0; x < 35; x++) for (int y = 0; y < 35; y++)
            nodes[x][y] = NetworkUtils.createAndAddNode(network, Id.createNodeId(x + "_" + y), new Coord(x * 100, y * 100));
        int id = 0;
        for (int x = 0; x < 35; x++) for (int y = 0; y < 35; y++) {
            if (x + 1 < 35) {
                add(network, id++, nodes[x][y], nodes[x + 1][y]);
                add(network, id++, nodes[x + 1][y], nodes[x][y]);
            }
            if (y + 1 < 35) {
                add(network, id++, nodes[x][y], nodes[x][y + 1]);
                add(network, id++, nodes[x][y + 1], nodes[x][y]);
            }
        }
        return network;
    }

    private static void add(Network network, int id, Node from, Node to) {
        Link link = NetworkUtils.createAndAddLink(network, Id.createLinkId(id), from, to, 100, 10, 1000, 1);
        link.setAllowedModes(Set.of("car"));
    }

    private static double referenceSeconds(Network network, String from, String to) {
        if (from.equals(to)) return 0;
        Coord a = network.getLinks().get(Id.createLinkId(from)).getToNode().getCoord();
        Coord b = network.getLinks().get(Id.createLinkId(to)).getFromNode().getCoord();
        return (Math.abs(a.getX() - b.getX()) + Math.abs(a.getY() - b.getY()) + 100) / 10;
    }

    private static long gcMillis() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(b -> Math.max(0, b.getCollectionTime())).sum();
    }

    private static String sha(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
