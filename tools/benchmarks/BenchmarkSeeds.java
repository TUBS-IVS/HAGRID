package hagrid.core.routing.benchmark;

/** Distinct bounded experiment streams; repeated groups intentionally reuse the same input. */
public final class BenchmarkSeeds {
    private BenchmarkSeeds() {}

    public static long current(int rootSeed, int carrierIndex) {
        if (carrierIndex < 0 || carrierIndex >= 4)
            throw new IllegalArgumentException("Expected carrier index 0..3");
        // Eight slots per signed-int root: even slots for current/input, odd for old-reference search.
        return 8L * rootSeed + 2L * carrierIndex;
    }

    public static long previous(int rootSeed, int carrierIndex) {
        return current(rootSeed, carrierIndex) + 1L;
    }
}