package hagrid.core.util;

import hagrid.core.config.RandomSeedSettings;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.random.RandomGenerator;

/** Stateless seed derivation. Each returned stream is owned and consumed by its caller. */
public final class RunRandomStreams {
    private static final Set<String> DOMAINS = Set.of("demand.cluster", "demand.weight", "demand.missed",
            "dispatch.departure", "supply.direction", "supply.departure");
    private final RandomSeedSettings seeds;

    public RunRandomStreams(RandomSeedSettings seeds) { this.seeds = Objects.requireNonNull(seeds, "seeds"); }

    public RandomGenerator forKey(String domain, String stableId) {
        if (!DOMAINS.contains(Objects.requireNonNull(domain, "domain")))
            throw new IllegalArgumentException("Unknown generation random domain: " + domain);
        Objects.requireNonNull(stableId, "stable ID");
        long root = seeds.seed();
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (String field : new String[]{Long.toString(root), domain, stableId}) {
                byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return new Random(ByteBuffer.wrap(digest.digest()).getLong());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Required SHA-256 unavailable", impossible);
        }
    }

    /** Collision-free composite IDs, including delimiters in user-supplied IDs. */
    public static String key(String... fields) {
        var result = new StringBuilder();
        for (String field : fields) {
            Objects.requireNonNull(field, "key field");
            result.append(field.length()).append(':').append(field);
        }
        return result.toString();
    }
}
