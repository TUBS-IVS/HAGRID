package hagrid.core.routing.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import hagrid.core.routing.CarrierReplay;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Cold certified replay envelope, not an exclusive solver peak or an admission estimator. */
public final class ReplayMemoryProbe {
    public record Measurement(String carrierId, String contextFingerprint, int jobs, int iterations,
            int vehicles, double finalCost, long heapBeforeBytes, long wholeJvmSampledPeakBytes,
            long heapAfterBytes, long returnedResultGraphBytes, long callerThreadAllocatedBytes,
            long elapsedNanos, long garbageCollections, long garbageCollectionMillis,
            long maximumHeapBytes, int activeProcessors, int samplingMillis) {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected a certified replay directory");
        var directory = Path.of(args[0]);
        var json = new ObjectMapper();
        var manifest = json.readTree(directory.resolve("manifest.json").toFile());
        var allocation = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!allocation.isThreadAllocatedMemorySupported())
            throw new IllegalStateException("Caller allocation observation unavailable");
        allocation.setThreadAllocatedMemoryEnabled(true);
        var memory = ManagementFactory.getMemoryMXBean();
        long caller = Thread.currentThread().threadId();
        long heapBefore = memory.getHeapMemoryUsage().getUsed();
        long allocatedBefore = allocation.getThreadAllocatedBytes(caller);
        long collectionsBefore = collections(false), gcMillisBefore = collections(true);
        var peak = new AtomicLong(heapBefore);
        var samplingFailure = new AtomicReference<Throwable>();
        var sampler = Executors.newSingleThreadScheduledExecutor(r -> {
            var thread = new Thread(r, "replay-heap-observer");
            thread.setDaemon(true); thread.setPriority(Thread.MIN_PRIORITY); return thread;
        });
        var sampling = sampler.scheduleAtFixedRate(() -> {
            try { peak.accumulateAndGet(memory.getHeapMemoryUsage().getUsed(), Math::max); }
            catch (Throwable failure) { samplingFailure.compareAndSet(null, failure); }
        }, 0, 5, TimeUnit.MILLISECONDS);
        try {
            long start = System.nanoTime();
            var result = CarrierReplay.replay(directory);
            long elapsed = System.nanoTime() - start;
            long heapAfter = memory.getHeapMemoryUsage().getUsed();
            long allocated = allocation.getThreadAllocatedBytes(caller) - allocatedBefore;
            long collections = collections(false) - collectionsBefore;
            long gcMillis = collections(true) - gcMillisBefore;
            sampling.cancel(false);
            sampler.shutdown();
            // Drain any in-flight observation before sizing a graph or freezing the replay peak.
            if (!sampler.awaitTermination(2, TimeUnit.SECONDS))
                throw new IllegalStateException("Own replay observer did not terminate");
            peak.accumulateAndGet(heapAfter, Math::max);
            long observedPeak = peak.get();
            if (samplingFailure.get() != null)
                throw new IllegalStateException("Heap observation failed", samplingFailure.get());
            if (!result.unassignedJobIds().isEmpty() || !result.diagnostics().isEmpty()
                    || !Double.isFinite(result.finalCost())
                    || !result.carrierId().equals(manifest.path("carrierId").asText())
                    || !result.contextFingerprint().equals(manifest.path("contextFingerprint").asText()))
                throw new IllegalStateException("Replay result is not completely certified");
            var carrier = result.plan().getCarrier();
            int jobs = Math.addExact(carrier.getServices().size(), carrier.getShipments().size());
            // The replay already closed its context. This is only the reachable returned result.
            long graphBytes = RetainedGraphAgent.measure(List.of(result), Set.of()).bytes();
            var observation = new Measurement(result.carrierId(), result.contextFingerprint(), jobs,
                    result.iterations(), result.plan().getScheduledTours().size(), result.finalCost(),
                    heapBefore, observedPeak, heapAfter, graphBytes, allocated, elapsed, collections,
                    gcMillis, Runtime.getRuntime().maxMemory(), Runtime.getRuntime().availableProcessors(), 5);
            System.out.println("REPLAY_MEMORY_JSON " + json.writeValueAsString(observation));
        } finally {
            sampling.cancel(false); sampler.shutdownNow();
            if (!sampler.awaitTermination(2, TimeUnit.SECONDS))
                throw new IllegalStateException("Own replay observer did not terminate");
        }
    }

    private static long collections(boolean milliseconds) {
        long total = 0;
        for (var collector : ManagementFactory.getGarbageCollectorMXBeans()) {
            long value = milliseconds ? collector.getCollectionTime() : collector.getCollectionCount();
            if (value < 0) throw new IllegalStateException("GC observation unavailable");
            total = Math.addExact(total, value);
        }
        return total;
    }
}
