package hagrid.lausitz.freight;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import hagrid.core.routing.HAGRIDRouterUtils;
import hagrid.lausitz.freight.JspritCacheResult.Mode;
import hagrid.lausitz.freight.JspritCacheResult.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("JspritPlanCache")
class JspritPlanCacheTest {

    @TempDir
    Path tmp;

    private LmdPreprocessInputs inputs;
    private Path cacheDir;

    @BeforeEach
    void stage() throws IOException {
        inputs = JspritCacheKeyTest.stage(tmp.resolve("in")).baseline();
        cacheDir = tmp.resolve("hagrid-output").resolve("shared").resolve("jsprit-cache");   // does not exist yet
    }

    /** Writes fixed bytes, counts calls - a stand-in for jsprit plus CarriersUtils.writeCarriers. */
    static final class Counting implements JspritPlanCache.Computation {
        final byte[] content;
        int calls;

        Counting(String content) {
            this.content = content.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public void computeInto(Path target) throws IOException {
            calls++;
            Files.createDirectories(target.toAbsolutePath().getParent());
            Files.write(target, content);
        }
    }

    static final class CountingReplay implements JspritPlanCache.HitReplay {
        int calls;
        Path seen;

        @Override
        public void replay(Path cachedResult) {
            calls++;
            seen = cachedResult;
        }
    }

    private JspritPlanCache cache(Mode mode) {
        return cache(mode, Optional.of("fp-1"), p -> null);
    }

    private JspritPlanCache cache(Mode mode, Optional<String> fingerprint, UnaryOperator<String> props) {
        return new JspritPlanCache(cacheDir, mode, () -> fingerprint, props, "java-1");
    }

    /** produce() the way the preprocessor calls it: inputs hashed before "step 1", then again inside produce. */
    static JspritCacheResult produce(JspritPlanCache cache, LmdPreprocessInputs in, Path carriersOut,
                                     JspritPlanCache.HitReplay replay, JspritPlanCache.Computation computation)
            throws IOException {
        return cache.produce(in, cache.keyBeforeReading(in), carriersOut, replay, computation);
    }

    private Path onlyMismatchDir() throws IOException {
        try (Stream<Path> s = Files.list(cacheDir)) {
            List<Path> m = s.filter(p -> p.getFileName().toString().startsWith(".mismatch-")).toList();
            assertThat(m).hasSize(1);
            return m.get(0);
        }
    }

    /** A carriers file inside a run folder that does not exist yet (review focus 3). */
    private Path out(String runId) {
        return tmp.resolve("runs").resolve(runId).resolve("carriers").resolve(runId + "_lmd_carriers_routed.xml");
    }

    private static String read(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    private static Map<String, Object> json(Path p) throws IOException {
        return new ObjectMapper().readValue(p.toFile(), new TypeReference<LinkedHashMap<String, Object>>() { });
    }

    private static void assertSidecar(Path carriersOut, String status) throws IOException {
        Path sidecar = JspritPlanCache.sidecarPathFor(carriersOut);
        assertThat(sidecar.getFileName().toString()).endsWith("_lmd_carriers_routed.cache.json");
        assertThat(json(sidecar).get("status")).isEqualTo(status);
    }

    private List<Path> entries() throws IOException {
        if (!Files.isDirectory(cacheDir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(cacheDir)) {
            return s.filter(Files::isDirectory).filter(p -> !p.getFileName().toString().startsWith(".")).toList();
        }
    }

    private Path onlyEntry() throws IOException {
        List<Path> e = entries();
        assertThat(e).hasSize(1);
        return e.get(0);
    }

    @Test
    void firstCallMissesSecondCallHits() throws IOException {
        Counting first = new Counting("X");
        JspritCacheResult r1 = produce(cache(Mode.ON), inputs, out("RUN_A"), new CountingReplay(), first);

        assertThat(r1.status()).isEqualTo(Status.MISS);
        assertThat(first.calls).isEqualTo(1);
        Path entry = onlyEntry();
        assertThat(entry.getFileName().toString()).matches("baseline-[0-9a-f]{16}");
        assertThat(read(entry.resolve(JspritPlanCache.RESULT_FILE))).isEqualTo("X");
        assertSidecar(out("RUN_A"), "miss");

        Counting second = new Counting("Y");
        CountingReplay replay = new CountingReplay();
        JspritCacheResult r2 = produce(cache(Mode.ON), inputs, out("RUN_B"), replay, second);

        assertThat(r2.status()).isEqualTo(Status.HIT);
        assertThat(second.calls).as("no jsprit on a hit").isZero();
        assertThat(replay.calls).as("the hit reads the entry where jsprit would have run").isEqualTo(1);
        assertThat(replay.seen).isEqualTo(entry.resolve(JspritPlanCache.RESULT_FILE));
        assertThat(read(out("RUN_B"))).isEqualTo("X");
        assertThat(r2.key()).isEqualTo(r1.key());
        assertThat(r2.sourceRun()).isEqualTo("RUN_A");
        assertThat(r2.computeSeconds()).isEqualTo(r1.computeSeconds());
        assertSidecar(out("RUN_B"), "hit");
    }

    @Test
    void offModeComputesAndStoresNothing() throws IOException {
        Counting c = new Counting("X");
        JspritCacheResult r = produce(cache(Mode.OFF), inputs, out("RUN_A"), new CountingReplay(), c);
        assertThat(r.status()).isEqualTo(Status.OFF);
        assertThat(r.reason()).isEqualTo("property");
        assertThat(r.key()).isNull();
        assertThat(c.calls).isEqualTo(1);
        assertThat(entries()).isEmpty();
        assertSidecar(out("RUN_A"), "off");
    }

    @Test
    void onlyCarrierBypassesEvenAValidEntry() throws IOException {
        produce(cache(Mode.ON), inputs, out("RUN_A"), new CountingReplay(), new Counting("X"));
        UnaryOperator<String> onlyCarrier =
                p -> p.equals(HAGRIDRouterUtils.JSPRIT_ONLY_CARRIER_PROPERTY) ? "largest" : null;
        Counting c = new Counting("PARTIAL");
        JspritCacheResult r = produce(cache(Mode.ON, Optional.of("fp-1"), onlyCarrier),
                inputs, out("RUN_DIAG"), new CountingReplay(), c);
        assertThat(r.status()).isEqualTo(Status.BYPASS);
        assertThat(c.calls).isEqualTo(1);
        assertThat(read(onlyEntry().resolve(JspritPlanCache.RESULT_FILE))).isEqualTo("X");
        assertSidecar(out("RUN_DIAG"), "bypass");
    }

    @Test
    void withoutAJarTheCacheIsOff() throws IOException {
        JspritCacheResult r = produce(cache(Mode.ON, Optional.empty(), p -> null),
                inputs, out("RUN_A"), new CountingReplay(), new Counting("X"));
        assertThat(r.status()).isEqualTo(Status.OFF);
        assertThat(r.reason()).isEqualTo("no-jar");
        assertThat(entries()).isEmpty();
    }

    @Test
    void withoutACacheDirTheCacheIsOff() throws IOException {
        JspritCacheResult r = produce(new JspritPlanCache(null, Mode.ON, () -> Optional.of("fp-1"), p -> null, "java-1"),
                inputs, out("RUN_A"), new CountingReplay(), new Counting("X"));
        assertThat(r.status()).isEqualTo(Status.OFF);
        assertThat(r.reason()).isEqualTo("no-cache-dir");
    }

    @Test
    void verifyAgainstAnEqualEntryIsVerified() throws IOException {
        produce(cache(Mode.ON), inputs, out("RUN_A"), new CountingReplay(), new Counting("X"));
        Counting c = new Counting("X");
        JspritCacheResult r = produce(cache(Mode.VERIFY), inputs, out("RUN_V"), new CountingReplay(), c);
        assertThat(r.status()).isEqualTo(Status.VERIFIED);
        assertThat(c.calls).as("verify always computes").isEqualTo(1);
        assertThat(r.sourceRun()).isEqualTo("RUN_A");
        assertSidecar(out("RUN_V"), "verified");
    }

    @Test
    void verifyAgainstAConsistentButDifferentEntryAborts() throws IOException {
        produce(cache(Mode.ON), inputs, out("RUN_A"), new CountingReplay(), new Counting("X"));
        Path entry = onlyEntry();
        // tamper consistently: other content AND a matching checksum, so the entry is VALID, not corrupt
        Files.writeString(entry.resolve(JspritPlanCache.RESULT_FILE), "Z", StandardCharsets.UTF_8);
        Map<String, Object> manifest = json(entry.resolve(JspritPlanCache.MANIFEST_FILE));
        manifest.put("result_sha256", Sha256.ofFile(entry.resolve(JspritPlanCache.RESULT_FILE)));
        new ObjectMapper().writeValue(entry.resolve(JspritPlanCache.MANIFEST_FILE).toFile(), manifest);

        assertThatThrownBy(() -> produce(cache(Mode.VERIFY), inputs, out("RUN_V"), new CountingReplay(),
                new Counting("X")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("VERIFY FAILED");
        assertThat(read(out("RUN_V"))).as("the fresh result stays for diffing").isEqualTo("X");
        Map<String, Object> sidecar = json(JspritPlanCache.sidecarPathFor(out("RUN_V")));
        assertThat(sidecar.get("status")).isEqualTo("mismatch");
        assertThat(sidecar.get("mode")).isEqualTo("verify");
        // spec section 6.4: the contradiction blocks the cache and keeps both results side by side
        assertThat(cacheDir.resolve(JspritPlanCache.BLOCKED_FILE)).exists();
        Path evidence = onlyMismatchDir();
        assertThat(read(evidence.resolve(JspritPlanCache.RESULT_FILE))).isEqualTo("Z");
        assertThat(read(evidence.resolve(JspritPlanCache.FRESH_FILE))).isEqualTo("X");
        assertThat(entries()).as("the contradicted entry is moved aside").isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = Mode.class, names = {"ON", "VERIFY"})
    void aCorruptEntryIsMovedAsideAndReplaced(Mode mode) throws IOException {
        produce(cache(Mode.ON), inputs, out("RUN_A"), new CountingReplay(), new Counting("X"));
        Path entry = onlyEntry();
        Files.writeString(entry.resolve(JspritPlanCache.RESULT_FILE), "garbage");   // checksum no longer matches

        Counting c = new Counting("X");
        JspritCacheResult r = produce(cache(mode), inputs, out("RUN_R"), new CountingReplay(), c);

        assertThat(r.status()).isEqualTo(Status.MISS);
        assertThat(r.reason()).isEqualTo("repaired");
        assertThat(c.calls).isEqualTo(1);
        try (Stream<Path> s = Files.list(cacheDir)) {
            assertThat(s.map(p -> p.getFileName().toString())).anyMatch(n -> n.startsWith(".corrupt-"));
        }
        JspritCacheResult again = produce(cache(Mode.ON), inputs, out("RUN_C"), new CountingReplay(), new Counting("Y"));
        assertThat(again.status()).as("the repaired entry is valid").isEqualTo(Status.HIT);
        assertThat(read(out("RUN_C"))).isEqualTo("X");
    }

    /** While this run computes (no lock held), another run publishes a DIFFERENT result for the same key. */
    private JspritPlanCache.Computation racedByADifferentResult() {
        return target -> {
            produce(cache(Mode.ON), inputs, out("RUN_OTHER"), new CountingReplay(), new Counting("Y"));
            Files.createDirectories(target.toAbsolutePath().getParent());
            Files.writeString(target, "X");
        };
    }

    @Test
    void aDifferentEntryPublishedMeanwhileIsAMismatchThatBlocksTheCache() throws IOException {
        JspritCacheResult r = produce(cache(Mode.ON), inputs, out("RUN_A"), new CountingReplay(),
                racedByADifferentResult());

        assertThat(r.status()).as("no abort in on mode").isEqualTo(Status.MISMATCH);
        assertThat(r.sourceRun()).isEqualTo("RUN_OTHER");
        assertThat(read(out("RUN_A"))).as("the run keeps its own fresh result").isEqualTo("X");
        assertSidecar(out("RUN_A"), "mismatch");
        assertThat(cacheDir.resolve(JspritPlanCache.BLOCKED_FILE)).exists();
        Path evidence = onlyMismatchDir();
        assertThat(read(evidence.resolve(JspritPlanCache.RESULT_FILE))).as("the published result").isEqualTo("Y");
        assertThat(read(evidence.resolve(JspritPlanCache.FRESH_FILE))).as("this run's result").isEqualTo("X");
        assertThat(entries()).as("the contradicted entry no longer serves hits").isEmpty();

        Counting next = new Counting("X");
        CountingReplay replay = new CountingReplay();
        JspritCacheResult blocked = produce(cache(Mode.ON), inputs, out("RUN_NEXT"), replay, next);
        assertThat(blocked.status()).isEqualTo(Status.BLOCKED);
        assertThat(next.calls).as("blocked means computed fresh").isEqualTo(1);
        assertThat(replay.calls).isZero();
        assertThat(entries()).as("nothing is stored while blocked").isEmpty();
        assertSidecar(out("RUN_NEXT"), "blocked");
    }

    @ParameterizedTest
    @EnumSource(value = Mode.class, names = {"ON", "VERIFY"})
    void aMismatchBlocksOtherKeysTooWithoutAbort(Mode mode) throws IOException {
        JspritPlanCache otherCode = cache(Mode.ON, Optional.of("fp-2"), p -> null);
        produce(otherCode, inputs, out("RUN_FP2"), new CountingReplay(), new Counting("W"));   // valid fp-2 entry
        produce(cache(Mode.ON), inputs, out("RUN_A"), new CountingReplay(), racedByADifferentResult());   // fp-1 mismatch

        Counting c = new Counting("W");
        CountingReplay replay = new CountingReplay();
        JspritCacheResult r = produce(cache(mode, Optional.of("fp-2"), p -> null), inputs, out("RUN_FP2_AGAIN"),
                replay, c);
        assertThat(r.status()).isEqualTo(Status.BLOCKED);
        assertThat(c.calls).isEqualTo(1);
        assertThat(replay.calls).as("no hit on the still valid fp-2 entry").isZero();
    }

    @Test
    void inputsThatChangeWhileBeingReadAreNeitherHitNorStored() throws IOException {
        JspritPlanCache cache = cache(Mode.ON);
        JspritCacheKey beforeReading = cache.keyBeforeReading(inputs);
        Files.writeString(inputs.network(), "net changed while steps 1-4 ran", StandardCharsets.UTF_8);
        produce(cache(Mode.ON), inputs, out("RUN_NEW"), new CountingReplay(), new Counting("N"));   // entry for the NEW content

        Counting c = new Counting("X");
        CountingReplay replay = new CountingReplay();
        JspritCacheResult r = cache.produce(inputs, beforeReading, out("RUN_A"), replay, c);

        assertThat(r.status()).isEqualTo(Status.BYPASS);
        assertThat(r.reason()).isEqualTo("inputs-changed");
        assertThat(c.calls).isEqualTo(1);
        assertThat(replay.calls).as("steps 1-4 may have read the old content").isZero();
        assertThat(read(onlyEntry().resolve(JspritPlanCache.RESULT_FILE))).as("nothing stored").isEqualTo("N");
        assertSidecar(out("RUN_A"), "bypass");
    }

    @Test
    void keyBeforeReadingIsNullExactlyWhenTheCacheIsNotUsed() {
        assertThat(cache(Mode.OFF).keyBeforeReading(inputs)).isNull();
        assertThat(cache(Mode.ON, Optional.empty(), p -> null).keyBeforeReading(inputs)).isNull();
        assertThat(cache(Mode.ON).keyBeforeReading(inputs)).isNotNull();
        assertThat(cache(Mode.VERIFY).keyBeforeReading(inputs)).isNotNull();
    }

    @Test
    void anActiveCacheWithoutAKeyBeforeReadingIsAWiringError() {
        assertThatThrownBy(() -> cache(Mode.ON).produce(inputs, null, out("RUN_A"), new CountingReplay(),
                new Counting("X")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("keyBeforeReading");
    }

    @Test
    void anEqualEntryPublishedMeanwhileIsAPlainMiss() throws IOException {
        JspritPlanCache.Computation racing = target -> {
            produce(cache(Mode.ON), inputs, out("RUN_OTHER"), new CountingReplay(), new Counting("X"));
            Files.createDirectories(target.toAbsolutePath().getParent());
            Files.writeString(target, "X");
        };
        JspritCacheResult r = produce(cache(Mode.ON), inputs, out("RUN_A"), new CountingReplay(), racing);
        assertThat(r.status()).isEqualTo(Status.MISS);
        assertThat(r.reason()).isEqualTo("concurrent-equal");
        assertThat(entries()).hasSize(1);
    }

    @Test
    void aCacheDirThatIsAFileKeepsTheRunGoing() throws IOException {
        Files.createDirectories(cacheDir.getParent());
        Files.writeString(cacheDir, "not a directory");
        Counting c = new Counting("X");
        JspritCacheResult r = produce(cache(Mode.ON), inputs, out("RUN_A"), new CountingReplay(), c);
        assertThat(r.status()).isEqualTo(Status.MISS);
        assertThat(r.reason()).isEqualTo("store-failed");
        assertThat(read(out("RUN_A"))).isEqualTo("X");
        assertSidecar(out("RUN_A"), "miss");
    }

    @Test
    void leftoversOfAKilledRunAreIgnored() throws IOException {
        Files.createDirectories(cacheDir.resolve(".tmp-dead"));
        Files.writeString(cacheDir.resolve(".tmp-dead").resolve(JspritPlanCache.RESULT_FILE), "half");
        Files.createDirectories(cacheDir.resolve(".corrupt-old"));
        Files.writeString(cacheDir.resolve("baseline-ffffffffffffffff.lock"), "");

        JspritCacheResult r1 = produce(cache(Mode.ON), inputs, out("RUN_A"), new CountingReplay(), new Counting("X"));
        JspritCacheResult r2 = produce(cache(Mode.ON), inputs, out("RUN_B"), new CountingReplay(), new Counting("Y"));
        assertThat(r1.status()).isEqualTo(Status.MISS);
        assertThat(r2.status()).isEqualTo(Status.HIT);
        assertThat(entries()).hasSize(1);
    }

    @Test
    void describeChangeNamesWhatChangedSinceTheNewestEntry() throws IOException {
        JspritCacheKey first = JspritCacheKey.of(inputs, "fp-1", p -> null, "java-1");
        assertThat(JspritPlanCache.describeChange(Optional.empty(), first)).isEqualTo("first entry for baseline");

        JspritPlanCache.Manifest newest = new JspritPlanCache.Manifest(first.fullHash(), "baseline",
                first.components(), "sha", "2026-10-01T10:00:00", "RUN_A", 8100.0, "dev");
        JspritCacheKey changed = JspritCacheKey.of(inputs, "fp-2", p -> null, "java-1");
        assertThat(JspritPlanCache.describeChange(Optional.of(newest), changed))
                .contains("RUN_A").contains("code").doesNotContain("network");
    }

    @Test
    void sidecarNameReplacesTheXmlSuffix() {
        assertThat(JspritPlanCache.sidecarPathFor(Path.of("c", "R_lmd_carriers_routed.xml")))
                .isEqualTo(Path.of("c", "R_lmd_carriers_routed.cache.json"));
        assertThat(JspritPlanCache.sidecarPathFor(Path.of("c", "plain")))
                .isEqualTo(Path.of("c", "plain.cache.json"));
    }

    // --- review round 1 fixes ------------------------------------------------------------------

    /**
     * F1: there is no way to make the real {@link CacheEntryLock#close()} fail from outside this
     * package without changing {@code CacheEntryLock.java} (not permitted - see fix report). This
     * exercises the {@code releaseQuietly} helper directly through the {@code Releasable} seam added
     * for exactly this purpose: a release failure must be swallowed (logged), never thrown onward,
     * so it can never replace a result (or exception) the caller already decided on.
     */
    @Test
    void releaseQuietlySwallowsAReleaseFailureInsteadOfPropagatingIt() throws IOException {
        JspritCacheKey key = JspritCacheKey.of(inputs, "fp-1", p -> null, "java-1");
        JspritPlanCache.Releasable failingClose = () -> {
            throw new IOException("simulated lock release failure");
        };
        assertThatCode(() -> JspritPlanCache.releaseQuietly(failingClose, key)).doesNotThrowAnyException();
    }

    /** F2 (promoted Minor 1a): spec section 5.4 - a 16-character prefix collision is a miss, never a
     *  false hit, because the full key is re-checked against the manifest under the lock. */
    @Test
    void aManifestWithAForeignFullKeyIsTreatedAsCorruptNotAsAHit() throws IOException {
        produce(cache(Mode.ON), inputs, out("RUN_A"), new CountingReplay(), new Counting("X"));
        Path entry = onlyEntry();
        Map<String, Object> manifest = json(entry.resolve(JspritPlanCache.MANIFEST_FILE));
        manifest.put("key", "f".repeat(64));   // a different full hash - would-be prefix collision
        new ObjectMapper().writeValue(entry.resolve(JspritPlanCache.MANIFEST_FILE).toFile(), manifest);

        Counting c = new Counting("X");
        CountingReplay replay = new CountingReplay();
        JspritCacheResult r = produce(cache(Mode.ON), inputs, out("RUN_R"), replay, c);

        assertThat(r.status()).isEqualTo(Status.MISS);
        assertThat(r.reason()).isEqualTo("repaired");
        assertThat(c.calls).as("no hit on a manifest key mismatch").isEqualTo(1);
        assertThat(replay.calls).isZero();
        try (Stream<Path> s = Files.list(cacheDir)) {
            assertThat(s.map(p -> p.getFileName().toString())).anyMatch(n -> n.startsWith(".corrupt-"));
        }
        JspritCacheResult again = produce(cache(Mode.ON), inputs, out("RUN_C"), new CountingReplay(), new Counting("Y"));
        assertThat(again.status()).as("the repaired entry is valid").isEqualTo(Status.HIT);
        assertThat(read(out("RUN_C"))).isEqualTo("X");
    }

    /** F3 (promoted Minor 1b): the BLOCKED check inside {@code inspect} runs again under the lock in
     *  {@code publish}, not only in {@code decide} before the computation - a mismatch published by
     *  someone else WHILE this run's own jsprit was computing must still block. */
    @Test
    void aBlockWrittenWhileComputingStillBlocksPublish() throws IOException {
        JspritPlanCache.Computation blockDuringComputation = target -> {
            Files.createDirectories(cacheDir);
            Files.writeString(cacheDir.resolve(JspritPlanCache.BLOCKED_FILE), "{}");
            Files.createDirectories(target.toAbsolutePath().getParent());
            Files.writeString(target, "X");
        };
        JspritCacheResult r = produce(cache(Mode.ON), inputs, out("RUN_A"), new CountingReplay(),
                blockDuringComputation);
        assertThat(r.status()).isEqualTo(Status.BLOCKED);
        assertThat(entries()).as("nothing is stored while blocked").isEmpty();
    }

    /** F4 (promoted Minor 3): spec section 7 - the sidecar always reflects the current preprocessing,
     *  never a stale value from an earlier run with the same run id that aborted. */
    @Test
    void aFailingComputationLeavesNoStaleSidecarBehind() throws IOException {
        Path carriersOut = out("RUN_A");
        Path sidecar = JspritPlanCache.sidecarPathFor(carriersOut);
        Files.createDirectories(sidecar.getParent());
        Files.writeString(sidecar, "{\"status\":\"hit\"}", StandardCharsets.UTF_8);

        JspritPlanCache.Computation failing = target -> {
            throw new IOException("jsprit failed");
        };
        assertThatThrownBy(() -> produce(cache(Mode.ON), inputs, carriersOut, new CountingReplay(), failing))
                .isInstanceOf(IOException.class);
        assertThat(Files.exists(sidecar)).as("the stale sidecar from an earlier run must not survive an abort")
                .isFalse();
    }
}
