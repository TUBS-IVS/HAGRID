package hagrid.core.routing;

import java.lang.management.ManagementFactory;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Run-owned available reservation ceiling. Shared MATSim/context/cache/published-plan heap and
 * native/physical reserve are separate from estimated carrier bytes. Query only between groups.
 * This is admission arithmetic, not a live memory watchdog or a calibrated carrier estimator.
 */
public final class CarrierRunBudget implements LongSupplier {
    public record State(long heapLimitBytes, long sharedHeapBytes, long heapReserveBytes,
                        long availablePhysicalBytes, long physicalReserveBytes, long nativeHeadroomBytes) {
        public State {
            if (heapLimitBytes < 1 || sharedHeapBytes < 0 || heapReserveBytes < 0 ||
                    availablePhysicalBytes < 0 || physicalReserveBytes < 0 || nativeHeadroomBytes < 0)
                throw new IllegalArgumentException("Invalid run memory state");
        }
        public long availableCarrierBytes() {
            long heap = margin(heapLimitBytes, sharedHeapBytes, heapReserveBytes, "heap");
            long physical = margin(availablePhysicalBytes, physicalReserveBytes, nativeHeadroomBytes, "physical");
            return Math.min(heap, physical);
        }
        private static long margin(long total, long occupied, long reserve, String domain) {
            // Subtract in order: adding occupied+reserve first could overflow into an allowed budget.
            if (occupied >= total || reserve >= total - occupied)
                throw new IllegalStateException("No " + domain + " memory remaining for carrier reservations");
            return total - occupied - reserve;
        }
    }

    private final Supplier<State> state;

    public CarrierRunBudget(Supplier<State> state) {
        this.state = Objects.requireNonNull(state);
    }

    @Override public long getAsLong() {
        return Objects.requireNonNull(state.get(), "Missing run memory observation").availableCarrierBytes();
    }

    /**
     * Optional aggregate JVM observation, including shared retained data and uncollected garbage.
     * It does not attribute a global heap difference to a carrier. Observations/estimates can lag;
     * the owner supplies conservative reserves and a valid physical probe, and retains live guarding.
     * No forced GC, native query dependency, worker creation or default reserve is introduced here.
     */
    public static CarrierRunBudget forCurrentJvm(LongSupplier availablePhysicalBytes,
            long physicalReserveBytes, long heapReserveBytes, long nativeHeadroomBytes) {
        Objects.requireNonNull(availablePhysicalBytes);
        if (physicalReserveBytes < 0 || heapReserveBytes < 0 || nativeHeadroomBytes < 0)
            throw new IllegalArgumentException("Run memory reserves must be nonnegative");
        var memory = ManagementFactory.getMemoryMXBean();
        return new CarrierRunBudget(() -> new State(Runtime.getRuntime().maxMemory(),
                memory.getHeapMemoryUsage().getUsed(), heapReserveBytes, availablePhysicalBytes.getAsLong(),
                physicalReserveBytes, nativeHeadroomBytes));
    }
}