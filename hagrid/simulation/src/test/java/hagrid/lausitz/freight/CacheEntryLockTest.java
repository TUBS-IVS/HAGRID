package hagrid.lausitz.freight;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CacheEntryLock and JspritCacheResult")
class CacheEntryLockTest {

    @TempDir
    Path tmp;

    @Test
    void modeParsesCaseInsensitivelyDefaultsToOnAndRejectsTypos() {
        assertThat(JspritCacheResult.Mode.parse(null)).isEqualTo(JspritCacheResult.Mode.ON);
        assertThat(JspritCacheResult.Mode.parse("  ")).isEqualTo(JspritCacheResult.Mode.ON);
        assertThat(JspritCacheResult.Mode.parse(" Verify ")).isEqualTo(JspritCacheResult.Mode.VERIFY);
        assertThat(JspritCacheResult.Mode.parse("OFF")).isEqualTo(JspritCacheResult.Mode.OFF);
        assertThatThrownBy(() -> JspritCacheResult.Mode.parse("verfy"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hagrid.jsprit.cache=verfy")
                .hasMessageContaining("on|off|verify");
    }

    @Test
    void wireNamesAreLowerCase() {
        assertThat(JspritCacheResult.Status.MISMATCH.wireName()).isEqualTo("mismatch");
        assertThat(JspritCacheResult.Status.BLOCKED.wireName()).isEqualTo("blocked");
        assertThat(JspritCacheResult.Mode.VERIFY.wireName()).isEqualTo("verify");
    }

    @Test
    void aStaleLockFileFromAKilledRunDoesNotBlock() throws Exception {
        Path lockFile = tmp.resolve("cache").resolve("baseline-0123456789abcdef.lock");
        Files.createDirectories(lockFile.getParent());
        Files.writeString(lockFile, "left behind");
        try (CacheEntryLock lock = CacheEntryLock.acquire(lockFile)) {
            assertThat(lock).isNotNull();
        }
    }

    @Test
    void twoThreadsAreSerialised() throws Exception {
        Path lockFile = tmp.resolve("k.lock");
        AtomicLong firstReleased = new AtomicLong();
        AtomicLong secondAcquired = new AtomicLong();
        CountDownLatch firstHolds = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(() -> {
                try (CacheEntryLock lock = CacheEntryLock.acquire(lockFile)) {
                    firstHolds.countDown();
                    Thread.sleep(300);
                    firstReleased.set(System.nanoTime());
                }
                return null;
            });
            firstHolds.await(10, TimeUnit.SECONDS);
            Future<?> second = pool.submit(() -> {
                try (CacheEntryLock lock = CacheEntryLock.acquire(lockFile)) {
                    secondAcquired.set(System.nanoTime());
                }
                return null;
            });
            first.get(30, TimeUnit.SECONDS);
            second.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(secondAcquired.get()).isGreaterThanOrEqualTo(firstReleased.get());
    }

    @Test
    void relativeAndAbsoluteSpellingsShareOneLock() throws Exception {
        Path absolute = tmp.resolve("rel").resolve("k.lock").toAbsolutePath();
        Files.createDirectories(absolute.getParent());
        Path relative = Path.of("").toAbsolutePath().relativize(absolute);
        assertThat(relative.isAbsolute()).isFalse();
        CountDownLatch firstHolds = new CountDownLatch(1);
        AtomicLong firstReleased = new AtomicLong();
        AtomicLong secondAcquired = new AtomicLong();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(() -> {
                try (CacheEntryLock lock = CacheEntryLock.acquire(absolute)) {
                    firstHolds.countDown();
                    Thread.sleep(300);
                    firstReleased.set(System.nanoTime());
                }
                return null;
            });
            firstHolds.await(10, TimeUnit.SECONDS);
            Future<?> second = pool.submit(() -> {
                try (CacheEntryLock lock = CacheEntryLock.acquire(relative)) {
                    secondAcquired.set(System.nanoTime());
                }
                return null;
            });
            first.get(30, TimeUnit.SECONDS);
            second.get(30, TimeUnit.SECONDS);   // an OverlappingFileLockException would surface here
        } finally {
            pool.shutdownNow();
        }
        assertThat(secondAcquired.get()).isGreaterThanOrEqualTo(firstReleased.get());
    }

    @Test
    void theLockHoldsAcrossProcesses() throws Exception {
        Path lockFile = tmp.resolve("x.lock");
        Path ready = tmp.resolve("ready.txt");
        Path childLog = tmp.resolve("child.log");
        String classpath = codeSource(CacheEntryLock.class) + File.pathSeparator + codeSource(CacheLockHolderMain.class);
        Process child = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classpath, CacheLockHolderMain.class.getName(),
                lockFile.toString(), ready.toString(), "2000")
                .redirectErrorStream(true)
                .redirectOutput(childLog.toFile())
                .start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!Files.exists(ready)) {
            assertThat(child.isAlive()).as("child died early, see %s", childLog).isTrue();
            assertThat(System.nanoTime()).as("child never took the lock").isLessThan(deadline);
            Thread.sleep(20);
        }
        long t0 = System.nanoTime();
        try (CacheEntryLock lock = CacheEntryLock.acquire(lockFile)) {
            long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            assertThat(waitedMs).as("the parent must wait for the child's lock").isGreaterThan(1000);
        }
        assertThat(child.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(child.exitValue()).as("child exit code, see %s", childLog).isZero();
    }

    private static String codeSource(Class<?> c) throws Exception {
        return Path.of(c.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }
}
