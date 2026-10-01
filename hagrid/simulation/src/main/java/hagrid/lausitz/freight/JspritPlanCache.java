package hagrid.lausitz.freight;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import hagrid.core.routing.HAGRIDRouterUtils;
import hagrid.lausitz.freight.JspritCacheResult.Mode;
import hagrid.lausitz.freight.JspritCacheResult.Status;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

/**
 * jsprit result cache of the Lausitz LMD preprocessing (spec 2026-10-01).
 *
 * <p>Wraps steps 5-6 of {@code LausitzFreightPreprocessor.run/runModular} (jsprit routing and
 * writing the carriers). Steps 1-4 always run, so every MATSim Id outside the jsprit search is
 * created by the same code in the same order as without the cache (spec section 4.1). On a hit the
 * cached file is read at the point where jsprit would have run ({@link HitReplay}) and copied
 * byte-for-byte to {@code carriersOut}.
 *
 * <p>Every access to an entry runs under {@link CacheEntryLock}; the lock is never held during
 * the jsprit computation itself. Publication never relies on {@code ATOMIC_MOVE} replacing an
 * existing target: under the lock the target is known to be absent (spec section 6.2).
 *
 * <p>Two results for one key that differ refute the cache itself, so a mismatch blocks the WHOLE
 * cache ({@link #BLOCKED_FILE}, spec section 6.4) until a person removes the marker. The inputs are
 * hashed twice, before step 1 ({@link #keyBeforeReading}) and in {@link #produce}; if they differ,
 * an input changed while being read and the run neither hits nor stores (spec section 6.5).
 */
public final class JspritPlanCache {

    public static final String MODE_PROPERTY = "hagrid.jsprit.cache";
    static final String RESULT_FILE = "lmd_carriers_routed.xml";
    static final String MANIFEST_FILE = "manifest.json";
    static final String BLOCKED_FILE = "BLOCKED.json";
    static final String FRESH_FILE = "fresh_" + RESULT_FILE;
    private static final String RUN_SUFFIX = "_lmd_carriers_routed.xml";
    private static final Logger LOG = LogManager.getLogger(JspritPlanCache.class);
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    /** Steps 5-6 of the preprocessing: route with jsprit and write the carriers to {@code target}. */
    @FunctionalInterface
    public interface Computation {
        void computeInto(Path target) throws IOException;
    }

    /** Reads a cached result where jsprit would have run, so MATSim Ids are created at the same stage. */
    @FunctionalInterface
    public interface HitReplay {
        void replay(Path cachedResult) throws IOException;
    }

    private final Path cacheDir;
    private final Mode mode;
    private final Supplier<Optional<String>> codeFingerprint;
    private final UnaryOperator<String> systemProperty;
    private final String javaRuntime;

    JspritPlanCache(Path cacheDir, Mode mode, Supplier<Optional<String>> codeFingerprint,
                    UnaryOperator<String> systemProperty, String javaRuntime) {
        this.cacheDir = cacheDir;
        this.mode = Objects.requireNonNull(mode, "mode");
        this.codeFingerprint = Objects.requireNonNull(codeFingerprint, "codeFingerprint");
        this.systemProperty = Objects.requireNonNull(systemProperty, "systemProperty");
        this.javaRuntime = Objects.requireNonNull(javaRuntime, "javaRuntime");
    }

    /** Production wiring. Parses {@code -Dhagrid.jsprit.cache} immediately: a typo fails here. */
    public static JspritPlanCache fromSystem(Path cacheDir) {
        return new JspritPlanCache(cacheDir, Mode.parse(System.getProperty(MODE_PROPERTY)),
                JarFingerprint::ofRunningCode, System::getProperty,
                System.getProperty("java.vm.vendor") + " " + System.getProperty("java.runtime.version"));
    }

    /**
     * Key of the inputs as they are BEFORE step 1 reads them (spec section 6.5), or {@code null} when
     * this run does not use the cache - then nothing is hashed.
     *
     * @throws UncheckedIOException if an input cannot be hashed, e.g. a missing file
     */
    public JspritCacheKey keyBeforeReading(LmdPreprocessInputs in) {
        if (skip().isPresent()) {
            return null;
        }
        try {
            return JspritCacheKey.of(in, codeFingerprint.get().orElseThrow(), systemProperty, javaRuntime);
        } catch (IOException e) {
            throw new UncheckedIOException("jsprit cache: cannot hash the LMD preprocessing inputs", e);
        }
    }

    /**
     * Steps 5-6 through the cache. {@code keyBeforeReading} is what {@link #keyBeforeReading} returned
     * before step 1; {@code null} is only allowed when this run does not use the cache.
     */
    public JspritCacheResult produce(LmdPreprocessInputs in, JspritCacheKey keyBeforeReading, Path carriersOut,
                                     HitReplay replay, Computation computation) throws IOException {
        JspritCacheResult result;
        try {
            result = decide(in, keyBeforeReading, carriersOut, replay, computation);
        } catch (VerifyMismatch e) {
            writeSidecar(carriersOut, e.result);
            throw e;
        }
        writeSidecar(carriersOut, result);
        return result;
    }

    /** Sidecar next to the routed carriers: {@code .xml} replaced by {@code .cache.json}. */
    public static Path sidecarPathFor(Path carriersOut) {
        String name = carriersOut.getFileName().toString();
        String base = name.toLowerCase(Locale.ROOT).endsWith(".xml") ? name.substring(0, name.length() - 4) : name;
        return carriersOut.resolveSibling(base + ".cache.json");
    }

    /** Why this run does not use the cache at all. */
    private record Skip(Status status, String reason) { }

    private Optional<Skip> skip() {
        String onlyCarrier = systemProperty.apply(HAGRIDRouterUtils.JSPRIT_ONLY_CARRIER_PROPERTY);
        if (onlyCarrier != null && !onlyCarrier.isBlank()) {
            return Optional.of(new Skip(Status.BYPASS, "onlyCarrier"));
        }
        if (mode == Mode.OFF) {
            return Optional.of(new Skip(Status.OFF, "property"));
        }
        if (cacheDir == null) {
            return Optional.of(new Skip(Status.OFF, "no-cache-dir"));
        }
        if (codeFingerprint.get().isEmpty()) {
            return Optional.of(new Skip(Status.OFF, "no-jar"));
        }
        return Optional.empty();
    }

    private JspritCacheResult decide(LmdPreprocessInputs in, JspritCacheKey keyBeforeReading, Path carriersOut,
                                     HitReplay replay, Computation computation) throws IOException {
        Optional<Skip> skip = skip();
        if (skip.isPresent()) {
            return computeUncached(in, carriersOut, computation, skip.get().status(), skip.get().reason());
        }
        if (keyBeforeReading == null) {
            throw new IllegalArgumentException("jsprit cache: keyBeforeReading is missing - call keyBeforeReading(in)"
                    + " before step 1 reads the inputs (spec section 6.5)");
        }

        JspritCacheKey key = JspritCacheKey.of(in, codeFingerprint.get().orElseThrow(), systemProperty, javaRuntime);
        if (!key.fullHash().equals(keyBeforeReading.fullHash())) {
            LOG.warn("jsprit cache: inputs changed while steps 1-4 read them ({}) - computing without the cache",
                    String.join(", ", key.changedComponents(keyBeforeReading.components())));
            return computeUncached(in, carriersOut, computation, Status.BYPASS, "inputs-changed");
        }
        Path entry = cacheDir.resolve(key.dirName());
        Optional<String> blocked = blockedNote();
        if (blocked.isPresent()) {
            LOG.error("jsprit cache BLOCKED ({}, see {}) - computing without the cache. Remove the marker only after"
                    + " the mismatch is understood; the safe reset is deleting {}", blocked.get(), blockedFile(), cacheDir);
            double seconds = timed(computation, carriersOut);
            return result(Status.BLOCKED, "cache-blocked", key, null, seconds);
        }
        if (mode == Mode.ON) {
            Optional<JspritCacheResult> hit = tryHit(key, entry, carriersOut, replay);
            if (hit.isPresent()) {
                return hit.get();
            }
        }
        LOG.info("jsprit cache {} {}: {}", mode == Mode.VERIFY ? "VERIFY" : "MISS", key.dirName(),
                describeChange(newestManifest(key.variant()), key));
        double seconds = timed(computation, carriersOut);
        return publish(key, entry, carriersOut, seconds);
    }

    private JspritCacheResult computeUncached(LmdPreprocessInputs in, Path carriersOut, Computation computation,
                                              Status status, String reason) throws IOException {
        LOG.info("jsprit cache {} ({}): computing without the cache", status.wireName(), reason);
        double seconds = timed(computation, carriersOut);
        return new JspritCacheResult(status, mode, reason, null, variantName(in.variant()), null, null, seconds);
    }

    private Optional<JspritCacheResult> tryHit(JspritCacheKey key, Path entry, Path carriersOut, HitReplay replay) {
        try (CacheEntryLock lock = CacheEntryLock.acquire(lockFile(key))) {
            EntryState state = inspect(entry, key);
            if (state instanceof EntryState.Corrupt corrupt) {
                LOG.warn("jsprit cache entry {} is corrupt ({}) - recomputing", key.dirName(), corrupt.why());
            }
            if (!(state instanceof EntryState.Valid valid)) {
                return Optional.empty();   // absent, corrupt, or blocked since the check in decide()
            }
            Path cached = entry.resolve(RESULT_FILE);
            replay.replay(cached);
            Files.createDirectories(carriersOut.toAbsolutePath().getParent());
            Files.copy(cached, carriersOut, StandardCopyOption.REPLACE_EXISTING);
            Manifest m = valid.manifest();
            LOG.info("jsprit cache HIT {} (computed by {} on {}, saves ~{})", key.dirName(), m.producedByRun(),
                    m.created(), formatDuration(m.computeSeconds()));
            return Optional.of(result(Status.HIT, null, key, m, m.computeSeconds()));
        } catch (IOException e) {
            LOG.warn("jsprit cache lookup of {} failed ({}) - computing without it", key.dirName(), e.toString());
            return Optional.empty();
        }
    }

    private JspritCacheResult publish(JspritCacheKey key, Path entry, Path carriersOut, double seconds) {
        try (CacheEntryLock lock = CacheEntryLock.acquire(lockFile(key))) {
            EntryState state = inspect(entry, key);
            if (state instanceof EntryState.Blocked) {
                LOG.error("jsprit cache BLOCKED while {} was computing - not storing", key.dirName());
                return result(Status.BLOCKED, "cache-blocked", key, null, seconds);
            }
            if (state instanceof EntryState.Valid valid) {
                return compareWithPublished(key, entry, carriersOut, valid.manifest(), seconds);
            }
            String reason = mode == Mode.VERIFY ? "verify-no-entry" : null;
            if (state instanceof EntryState.Corrupt corrupt) {
                Path aside = cacheDir.resolve(".corrupt-" + UUID.randomUUID());
                Files.move(entry, aside);
                LOG.warn("jsprit cache entry {} was corrupt ({}) - moved aside to {} and replaced",
                        key.dirName(), corrupt.why(), aside.getFileName());
                reason = "repaired";
            }
            store(key, entry, carriersOut, seconds);
            return result(Status.MISS, reason, key, null, seconds);
        } catch (IOException e) {
            LOG.warn("jsprit cache: could not store {} ({}) - the run continues with its fresh result",
                    key.dirName(), e.toString());
            return result(Status.MISS, "store-failed", key, null, seconds);
        }
    }

    private JspritCacheResult compareWithPublished(JspritCacheKey key, Path entry, Path carriersOut,
                                                   Manifest published, double seconds) throws IOException {
        boolean equal = Files.mismatch(entry.resolve(RESULT_FILE), carriersOut) == -1L;
        if (equal) {
            if (mode == Mode.VERIFY) {
                LOG.info("jsprit cache VERIFIED {}: the fresh result is byte-identical to the entry computed by {}",
                        key.dirName(), published.producedByRun());
                return result(Status.VERIFIED, "verify", key, published, seconds);
            }
            return result(Status.MISS, "concurrent-equal", key, null, seconds);
        }
        Path evidence = quarantine(key, entry, carriersOut, published);
        String where = evidence == null ? entry.toAbsolutePath().toString() : evidence.toAbsolutePath().toString();
        if (mode == Mode.VERIFY) {
            throw new VerifyMismatch(result(Status.MISMATCH, "verify", key, published, seconds),
                    "jsprit cache VERIFY FAILED: the fresh result " + carriersOut.toAbsolutePath()
                            + " differs from the cache entry computed by " + published.producedByRun()
                            + " (both results: " + where + "). Either the cache key misses an input, jsprit is not"
                            + " deterministic or the machine miscomputed - the cache is BLOCKED (" + blockedFile()
                            + ") until this is understood.");
        }
        LOG.error("jsprit cache MISMATCH {}: a concurrent run ({}) published a DIFFERENT result for the same key."
                        + " This run keeps its own fresh result; both results are in {}; the cache is BLOCKED ({}).",
                key.dirName(), published.producedByRun(), where, blockedFile());
        return result(Status.MISMATCH, "concurrent", key, published, seconds);
    }

    /**
     * Spec section 6.4: a contradiction blocks the whole cache. The marker comes first because it carries
     * the safety; then the entry and this run's fresh result go side by side into {@code .mismatch-<uuid>/}.
     * Never throws, so a failed move can never turn a mismatch into a miss.
     *
     * @return the evidence directory, or {@code null} if the entry could not be moved aside
     */
    private Path quarantine(JspritCacheKey key, Path entry, Path carriersOut, Manifest published) {
        Path evidence = cacheDir.resolve(".mismatch-" + UUID.randomUUID());
        Map<String, Object> marker = new LinkedHashMap<>();
        marker.put("blocked_since", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        marker.put("key", key.fullHash());
        marker.put("dir_name", key.dirName());
        marker.put("mode", mode.wireName());
        marker.put("published_by_run", published.producedByRun());
        marker.put("fresh_run", producedByRun(carriersOut));
        marker.put("evidence", evidence.getFileName().toString());
        marker.put("how_to_unblock", "Delete this file only after the mismatch is understood. The safe reset is"
                + " deleting the whole jsprit-cache directory after saving the .mismatch-* evidence.");
        try {
            Files.write(blockedFile(), JSON.writeValueAsBytes(marker), StandardOpenOption.CREATE_NEW);
            LOG.error("jsprit cache BLOCKED: wrote {}", blockedFile());
        } catch (FileAlreadyExistsException e) {
            LOG.error("jsprit cache: another mismatch ({}) while already blocked - keeping the first marker",
                    key.dirName());
        } catch (IOException | RuntimeException e) {
            LOG.error("jsprit cache: could NOT write the block marker {} ({}) - delete {} by hand before the next run",
                    blockedFile(), e.toString(), cacheDir);
        }
        try {
            Files.move(entry, evidence);   // under the lock; the uuid target does not exist
            Files.copy(carriersOut, evidence.resolve(FRESH_FILE));
            return evidence;
        } catch (IOException | RuntimeException e) {
            LOG.error("jsprit cache: could not move {} aside as evidence ({})", entry, e.toString());
            return Files.isDirectory(evidence) ? evidence : null;
        }
    }

    private Path blockedFile() {
        return cacheDir.resolve(BLOCKED_FILE);
    }

    /** Short description of the block marker, or empty when the cache is not blocked. */
    private Optional<String> blockedNote() {
        Path marker = blockedFile();
        if (!Files.exists(marker)) {
            return Optional.empty();
        }
        try {
            Map<String, Object> m = JSON.readValue(marker.toFile(), new TypeReference<LinkedHashMap<String, Object>>() { });
            return Optional.of("since " + m.get("blocked_since") + " after a mismatch on " + m.get("dir_name"));
        } catch (IOException | RuntimeException e) {
            return Optional.of("marker unreadable: " + e.getMessage());   // an unreadable marker still blocks
        }
    }

    private void store(JspritCacheKey key, Path entry, Path carriersOut, double seconds) throws IOException {
        Files.createDirectories(cacheDir);
        Path tmp = cacheDir.resolve(".tmp-" + UUID.randomUUID());
        try {
            Files.createDirectory(tmp);
            Path result = tmp.resolve(RESULT_FILE);
            Files.copy(carriersOut, result);
            Manifest m = new Manifest(key.fullHash(), variantName(key.variant()), key.components(),
                    Sha256.ofFile(result), LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                    producedByRun(carriersOut), seconds, hostName());
            JSON.writeValue(tmp.resolve(MANIFEST_FILE).toFile(), m.toMap());
            // the target is absent under the lock, so ATOMIC_MOVE never has to replace anything
            Files.move(tmp, entry, StandardCopyOption.ATOMIC_MOVE);
            LOG.info("jsprit cache STORED {} ({} s of jsprit)", key.dirName(), seconds);
        } finally {
            if (Files.exists(tmp)) {
                deleteRecursively(tmp);
            }
        }
    }

    sealed interface EntryState {
        record Blocked() implements EntryState { }

        record Absent() implements EntryState { }

        record Valid(Manifest manifest) implements EntryState { }

        record Corrupt(String why) implements EntryState { }
    }

    /** Re-evaluated under the lock every time - never carried over from an earlier check. */
    static EntryState inspect(Path entry, JspritCacheKey key) {
        if (Files.exists(entry.resolveSibling(BLOCKED_FILE))) {
            return new EntryState.Blocked();   // checked first: a blocked cache has no valid entries
        }
        if (!Files.exists(entry)) {
            return new EntryState.Absent();
        }
        if (!Files.isDirectory(entry)) {
            return new EntryState.Corrupt("not a directory");
        }
        Manifest m;
        try {
            m = Manifest.read(entry.resolve(MANIFEST_FILE));
        } catch (IOException | RuntimeException e) {
            return new EntryState.Corrupt("manifest unreadable: " + e.getMessage());
        }
        if (!key.fullHash().equals(m.key())) {
            return new EntryState.Corrupt("manifest key differs (prefix collision or tampering)");
        }
        Path result = entry.resolve(RESULT_FILE);
        if (!Files.isRegularFile(result)) {
            return new EntryState.Corrupt("result file missing");
        }
        try {
            if (!Sha256.ofFile(result).equals(m.resultSha256())) {
                return new EntryState.Corrupt("result_sha256 does not match");
            }
        } catch (IOException e) {
            return new EntryState.Corrupt("result unreadable: " + e.getMessage());
        }
        return new EntryState.Valid(m);
    }

    static String describeChange(Optional<Manifest> newest, JspritCacheKey key) {
        String variant = variantName(key.variant());
        if (newest.isEmpty()) {
            return "first entry for " + variant;
        }
        Manifest n = newest.get();
        var changed = key.changedComponents(n.components());
        if (changed.isEmpty()) {
            return "the newest " + variant + " entry has the same components but is missing or corrupt";
        }
        return "changed since the newest " + variant + " entry (" + n.created() + ", computed by "
                + n.producedByRun() + "): " + String.join(", ", changed);
    }

    private Optional<Manifest> newestManifest(LmdPreprocessInputs.Variant variant) {
        if (cacheDir == null || !Files.isDirectory(cacheDir)) {
            return Optional.empty();
        }
        String prefix = variantName(variant) + "-";
        try (Stream<Path> s = Files.list(cacheDir)) {
            return s.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().startsWith(prefix))
                    .map(p -> {
                        try {
                            return Manifest.read(p.resolve(MANIFEST_FILE));
                        } catch (IOException | RuntimeException e) {
                            return null;   // a broken neighbour only weakens the log message
                        }
                    })
                    .filter(Objects::nonNull)
                    .max(Comparator.comparing(Manifest::created));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private Path lockFile(JspritCacheKey key) {
        return cacheDir.resolve(key.dirName() + ".lock");
    }

    private JspritCacheResult result(Status status, String reason, JspritCacheKey key, Manifest source,
                                     Double seconds) {
        return new JspritCacheResult(status, mode, reason, key.fullHash(), variantName(key.variant()),
                source == null ? null : source.producedByRun(), source == null ? null : source.created(), seconds);
    }

    private static double timed(Computation computation, Path target) throws IOException {
        long t0 = System.nanoTime();
        computation.computeInto(target);
        return Math.round((System.nanoTime() - t0) / 1e8) / 10.0;
    }

    private static void writeSidecar(Path carriersOut, JspritCacheResult r) {
        Path sidecar = sidecarPathFor(carriersOut);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", r.status().wireName());
        m.put("mode", r.mode().wireName());
        m.put("reason", r.reason());
        m.put("key", r.key());
        m.put("variant", r.variant());
        m.put("source_run", r.sourceRun());
        m.put("source_created", r.sourceCreated());
        m.put("compute_seconds", r.computeSeconds());
        try {
            Files.createDirectories(sidecar.toAbsolutePath().getParent());
            JSON.writeValue(sidecar.toFile(), m);
        } catch (IOException e) {
            LOG.warn("jsprit cache: could not write {} ({})", sidecar, e.toString());
        }
    }

    static String producedByRun(Path carriersOut) {
        String name = carriersOut.getFileName().toString();
        return name.endsWith(RUN_SUFFIX) ? name.substring(0, name.length() - RUN_SUFFIX.length()) : name;
    }

    private static String variantName(LmdPreprocessInputs.Variant v) {
        return v.name().toLowerCase(Locale.ROOT);
    }

    private static String formatDuration(Double seconds) {
        if (seconds == null) {
            return "?";
        }
        long s = Math.round(seconds);
        return String.format(Locale.ROOT, "%dh%02dm", s / 3600, (s % 3600) / 60);
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (IOException e) {
            String computer = System.getenv("COMPUTERNAME");
            return computer == null ? "unknown" : computer;
        }
    }

    private static void deleteRecursively(Path dir) {
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    LOG.warn("jsprit cache: could not delete {} ({})", p, e.toString());
                }
            });
        } catch (IOException e) {
            LOG.warn("jsprit cache: could not clean up {} ({})", dir, e.toString());
        }
    }

    /** Carries the result so {@link #produce} can write the sidecar before the run aborts. */
    static final class VerifyMismatch extends IllegalStateException {
        final transient JspritCacheResult result;

        VerifyMismatch(JspritCacheResult result, String message) {
            super(message);
            this.result = result;
        }
    }

    record Manifest(String key, String variant, Map<String, String> components, String resultSha256,
                    String created, String producedByRun, double computeSeconds, String host) {

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", key);
            m.put("variant", variant);
            m.put("components", components);
            m.put("result_sha256", resultSha256);
            m.put("created", created);
            m.put("produced_by_run", producedByRun);
            m.put("compute_seconds", computeSeconds);
            m.put("host", host);
            return m;
        }

        static Manifest read(Path file) throws IOException {
            Map<String, Object> m = JSON.readValue(file.toFile(), new TypeReference<LinkedHashMap<String, Object>>() { });
            if (!(m.get("components") instanceof Map<?, ?> raw)) {
                throw new IOException("manifest without components: " + file);
            }
            Map<String, String> components = new LinkedHashMap<>();
            raw.forEach((k, v) -> components.put(String.valueOf(k), String.valueOf(v)));
            return new Manifest(text(m, "key", file), text(m, "variant", file), components,
                    text(m, "result_sha256", file), text(m, "created", file), text(m, "produced_by_run", file),
                    number(m, "compute_seconds", file), String.valueOf(m.getOrDefault("host", "unknown")));
        }

        private static String text(Map<String, Object> m, String field, Path file) throws IOException {
            if (!(m.get(field) instanceof String s)) {
                throw new IOException("manifest field '" + field + "' missing in " + file);
            }
            return s;
        }

        private static double number(Map<String, Object> m, String field, Path file) throws IOException {
            if (!(m.get(field) instanceof Number n)) {
                throw new IOException("manifest field '" + field + "' missing in " + file);
            }
            return n.doubleValue();
        }
    }
}
