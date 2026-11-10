package hagrid.core.config;

import java.util.Map;
import java.util.Set;

/** One master seed for configured HAGRID runs; named streams isolate generation domains. */
public record RandomSeedSettings(long seed) {
    public static final long DEFAULT_SEED = 1337L;
    public static final String ALGORITHM_VERSION = "sha256-length-prefixed-v1";
    private static final Set<String> ROOT_KEYS = Set.of("run.seed");
    public static final String VERSION_KEY = "randomSeeds.algorithmVersion";

    public static Map<String, String> defaultsValues() {
        return Map.of("run.seed", Long.toString(DEFAULT_SEED), VERSION_KEY, ALGORITHM_VERSION);
    }

    public static boolean contains(String key) { return ROOT_KEYS.contains(key) || VERSION_KEY.equals(key); }

    public static void validate(String key, String value) {
        if (VERSION_KEY.equals(key)) {
            if (!ALGORITHM_VERSION.equals(value)) throw new IllegalArgumentException("read-only algorithm version: " + ALGORITHM_VERSION);
        } else if (ROOT_KEYS.contains(key)) {
            Long.parseLong(value.trim());
        } else {
            throw new IllegalArgumentException("unknown generation seed: " + key);
        }
    }

    public static RandomSeedSettings from(Map<String, String> values) {
        return new RandomSeedSettings(root(values, "run.seed"));
    }

    public String algorithmVersion() { return ALGORITHM_VERSION; }

    private static long root(Map<String, String> values, String key) {
        try { return Long.parseLong(values.getOrDefault(key, Long.toString(DEFAULT_SEED)).trim()); }
        catch (IllegalArgumentException | NullPointerException invalid) {
            throw new IllegalArgumentException(key + ": requires a signed 64-bit integer", invalid);
        }
    }
}
