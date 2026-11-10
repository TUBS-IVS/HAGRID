package hagrid.core.routing;

import com.sun.management.ThreadMXBean;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.router.util.TravelTime;

import java.lang.management.ManagementFactory;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Small capture/storage observation only; no solver runtime or result-quality comparison. */
public final class TravelTimeSnapshotProbe {
    public static void main(String[] args) {
        if (Runtime.version().feature() != 21 || Runtime.getRuntime().availableProcessors() != 1
                || Runtime.getRuntime().maxMemory() > 128L * 1024 * 1024) {
            throw new IllegalStateException("Expected Java 21, one active processor and at most 128 MiB heap");
        }
        var network = NetworkUtils.createNetwork();
        var a = network.getFactory().createNode(Id.createNodeId("a"), new Coord(0, 0));
        var b = network.getFactory().createNode(Id.createNodeId("b"), new Coord(100, 0));
        network.addNode(a);
        network.addNode(b);
        for (int i = 0; i < 1000; i++) {
            var link = network.getFactory().createLink(Id.createLinkId("l" + i), a, b);
            link.setLength(100);
            link.setFreespeed(10);
            link.setCapacity(1000);
            link.setNumberOfLanes(1);
            link.setAllowedModes(Set.of("car", "bike"));
            network.addLink(link);
        }
        Map<String, TravelTime> measured = Map.of("car",
                (l,t,p,v) -> l.getId().toString().equals("l0") && t >= 10800 && t < 21600 ? 20 : 10);
        Map<String, TravelTime> fallback = Map.of("bike", (l,t,p,v) -> 20);
        for (int i = 0; i < 2; i++) TravelTimeSnapshot.capture(network, measured, fallback, 25, 86400, 900);
        System.out.println("repeat,links,modes,bins,profiles,retained_values,numeric_payload_bytes,dense_numeric_reference_bytes,capture_ms,current_thread_allocated_bytes,process_heap_used_bytes,fingerprint");
        var allocation = ManagementFactory.getThreadMXBean() instanceof ThreadMXBean bean ? bean : null;
        boolean allocationAvailable = allocation != null && allocation.isThreadAllocatedMemorySupported();
        if (allocationAvailable && !allocation.isThreadAllocatedMemoryEnabled()) allocation.setThreadAllocatedMemoryEnabled(true);
        for (int repeat = 1; repeat <= 3; repeat++) {
            long beforeAllocation = allocationAvailable ? allocation.getThreadAllocatedBytes(Thread.currentThread().threadId()) : -1;
            long before = System.nanoTime();
            var snapshot = TravelTimeSnapshot.capture(network, measured, fallback, 25, 86400, 900);
            long duration = System.nanoTime() - before;
            long allocated = allocationAvailable ? allocation.getThreadAllocatedBytes(Thread.currentThread().threadId()) - beforeAllocation : -1;
            var stats = snapshot.storageStats();
            double initial = snapshot.forMode("car").getLinkTravelTime(network.getLinks().get(Id.createLinkId("l0")), 0, null, null);
            network.getLinks().get(Id.createLinkId("l0")).setFreespeed(1);
            double after = snapshot.forMode("car").getLinkTravelTime(network.getLinks().get(Id.createLinkId("l0")), 0, null, null);
            if (initial != 10 || after != initial || stats.profileCount() != 2000 || stats.retainedValueCount() != 2002) {
                throw new IllegalStateException("Snapshot fixture or compactness regression");
            }
            // Same numerical fields represented densely: per-profile length and 96 bin values.
            long dense = stats.profileCount() * (8 + 96 * 8);
            System.out.printf(Locale.ROOT, "%d,1000,2,96,%d,%d,%d,%d,%.6f,%d,%d,%s%n",
                    repeat, stats.profileCount(), stats.retainedValueCount(), stats.numericPayloadBytes(), dense,
                    duration / 1e6, allocated, ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(), snapshot.fingerprint());
        }
    }
}