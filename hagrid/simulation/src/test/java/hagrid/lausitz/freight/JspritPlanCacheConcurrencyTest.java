package hagrid.lausitz.freight;

import hagrid.lausitz.freight.JspritCacheResult.Mode;
import hagrid.lausitz.freight.JspritCacheResult.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("JspritPlanCache under real concurrency")
class JspritPlanCacheConcurrencyTest {

    @TempDir
    Path tmp;

    private LmdPreprocessInputs inputs;
    private Path cacheDir;

    @BeforeEach
    void stage() throws IOException {
        inputs = JspritCacheKeyTest.stage(tmp.resolve("in")).baseline();
        cacheDir = tmp.resolve("jsprit-cache");
    }

    private JspritPlanCache cache() {
        return new JspritPlanCache(cacheDir, Mode.ON, () -> Optional.of("fp-1"), p -> null, "java-1");
    }

    private Path out(String runId) {
        return tmp.resolve(runId).resolve(runId + "_lmd_carriers_routed.xml");
    }

    /** Both computations wait at the barrier, so both have missed before either publishes. */
    private JspritPlanCache.Computation writesAfterBarrier(CyclicBarrier barrier, String content) {
        return target -> {
            try {
                barrier.await(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IOException(e);
            }
            Files.createDirectories(target.toAbsolutePath().getParent());
            Files.writeString(target, content, StandardCharsets.UTF_8);
        };
    }

    private List<JspritCacheResult> runBoth(Callable<JspritCacheResult> a, Callable<JspritCacheResult> b)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<JspritCacheResult> fa = pool.submit(a);
            Future<JspritCacheResult> fb = pool.submit(b);
            return List.of(fa.get(60, TimeUnit.SECONDS), fb.get(60, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    private List<String> names() throws IOException {
        try (Stream<Path> s = Files.list(cacheDir)) {
            return s.map(p -> p.getFileName().toString()).toList();
        }
    }

    private List<Path> entries() throws IOException {
        try (Stream<Path> s = Files.list(cacheDir)) {
            return s.filter(Files::isDirectory).filter(p -> !p.getFileName().toString().startsWith(".")).toList();
        }
    }

    private List<Path> mismatchDirs() throws IOException {
        try (Stream<Path> s = Files.list(cacheDir)) {
            return s.filter(p -> p.getFileName().toString().startsWith(".mismatch-")).toList();
        }
    }

    @Test
    void twoEqualPublishersLeaveExactlyOneEntry() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<JspritCacheResult> r = runBoth(
                () -> JspritPlanCacheTest.produce(cache(), inputs, out("RUN_A"), new JspritPlanCacheTest.CountingReplay(),
                        writesAfterBarrier(barrier, "X")),
                () -> JspritPlanCacheTest.produce(cache(), inputs, out("RUN_B"), new JspritPlanCacheTest.CountingReplay(),
                        writesAfterBarrier(barrier, "X")));

        assertThat(r).extracting(JspritCacheResult::status).containsOnly(Status.MISS);
        assertThat(r).extracting(JspritCacheResult::reason).containsExactlyInAnyOrder(null, "concurrent-equal");
        assertThat(entries()).hasSize(1);
        assertThat(names()).noneMatch(n -> n.startsWith(".tmp-"));
    }

    @Test
    void twoDifferentPublishersGiveOneMissAndOneMismatchAndBlockTheCache() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<JspritCacheResult> r = runBoth(
                () -> JspritPlanCacheTest.produce(cache(), inputs, out("RUN_A"), new JspritPlanCacheTest.CountingReplay(),
                        writesAfterBarrier(barrier, "X")),
                () -> JspritPlanCacheTest.produce(cache(), inputs, out("RUN_B"), new JspritPlanCacheTest.CountingReplay(),
                        writesAfterBarrier(barrier, "Y")));

        assertThat(r).extracting(JspritCacheResult::status).containsExactlyInAnyOrder(Status.MISS, Status.MISMATCH);
        Path winnerOut = r.get(0).status() == Status.MISS ? out("RUN_A") : out("RUN_B");
        Path loserOut = winnerOut.equals(out("RUN_A")) ? out("RUN_B") : out("RUN_A");
        assertThat(entries()).as("the contradicted entry is moved aside").isEmpty();
        assertThat(cacheDir.resolve(JspritPlanCache.BLOCKED_FILE)).exists();
        List<Path> evidence = mismatchDirs();
        assertThat(evidence).hasSize(1);
        assertThat(Files.readString(evidence.get(0).resolve(JspritPlanCache.RESULT_FILE)))
                .isEqualTo(Files.readString(winnerOut));
        assertThat(Files.readString(evidence.get(0).resolve(JspritPlanCache.FRESH_FILE)))
                .isEqualTo(Files.readString(loserOut));
        assertThat(names()).noneMatch(n -> n.startsWith(".tmp-"));
    }

    @RepeatedTest(10)
    void aReaderDuringARepairSeesEitherAMissOrTheWholeNewEntry() throws Exception {
        JspritPlanCacheTest.produce(cache(), inputs, out("RUN_SEED"), new JspritPlanCacheTest.CountingReplay(),
                new JspritPlanCacheTest.Counting("X"));
        Path entry = entries().get(0);
        Files.writeString(entry.resolve(JspritPlanCache.RESULT_FILE), "garbage");   // corrupt

        CyclicBarrier start = new CyclicBarrier(2);
        Callable<JspritCacheResult> repairer = () -> {
            start.await(30, TimeUnit.SECONDS);
            return JspritPlanCacheTest.produce(cache(), inputs, out("RUN_W"), new JspritPlanCacheTest.CountingReplay(),
                    new JspritPlanCacheTest.Counting("X"));
        };
        Callable<JspritCacheResult> reader = () -> {
            start.await(30, TimeUnit.SECONDS);
            return JspritPlanCacheTest.produce(cache(), inputs, out("RUN_R"), new JspritPlanCacheTest.CountingReplay(),
                    new JspritPlanCacheTest.Counting("X"));
        };
        List<JspritCacheResult> r = runBoth(repairer, reader);

        assertThat(r.get(1).status()).isIn(Status.HIT, Status.MISS);
        assertThat(Files.readString(out("RUN_R"))).as("never a partial or the corrupt file").isEqualTo("X");
        JspritCacheResult after = JspritPlanCacheTest.produce(cache(), inputs, out("RUN_AFTER"), new JspritPlanCacheTest.CountingReplay(),
                new JspritPlanCacheTest.Counting("Y"));
        assertThat(after.status()).isEqualTo(Status.HIT);
        assertThat(Files.readString(out("RUN_AFTER"))).isEqualTo("X");
        assertThat(names()).noneMatch(n -> n.startsWith(".tmp-"));
    }
}
