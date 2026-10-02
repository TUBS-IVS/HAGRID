package hagrid.lausitz.freight;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

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

    /**
     * I2(b): a thread that holds the lock and asks again gets an IllegalStateException - NOT the
     * OverlappingFileLockException (also an IllegalStateException) that a second channel would throw,
     * whose close drops the outer lock on Linux. The outer lock stays effective afterwards.
     */
    @Test
    void theSameThreadCannotAcquireTwiceAndTheFirstLockStaysEffective() throws Exception {
        Path lockFile = tmp.resolve("r.lock");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            try (CacheEntryLock outer = CacheEntryLock.acquire(lockFile)) {
                assertThatThrownBy(() -> CacheEntryLock.acquire(lockFile))
                        .isExactlyInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("CacheEntryLock is not reentrant");
                Future<Throwable> other = pool.submit(() -> acquireAndReport(lockFile, Duration.ofMillis(500)));
                assertThat(other.get(60, TimeUnit.SECONDS))
                        .as("another thread must still be kept out by the outer lock")
                        .isInstanceOf(IOException.class)
                        .hasMessageContaining("timed out waiting for");
            }
            Future<Throwable> afterClose = pool.submit(() -> acquireAndReport(lockFile, Duration.ofSeconds(30)));
            assertThat(afterClose.get(60, TimeUnit.SECONDS)).as("free again once the outer lock is closed").isNull();
        } finally {
            pool.shutdownNow();
        }
    }

    /** @return {@code null} if the lock was acquired (and released), else what acquire threw */
    private static Throwable acquireAndReport(Path lockFile, Duration timeout) {
        try (CacheEntryLock lock = CacheEntryLock.acquire(lockFile, timeout)) {
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    /** One CacheLockHolderMain child. Its files live outside the @TempDir (T3: Windows handle release). */
    private record Child(Process process, Path dir, Path lockFile, Path ready, Path released, Path log) {

        static Child start(long holdMillis) throws Exception {
            Path dir = Files.createTempDirectory("hagrid-cache-lock-test-");
            Path lockFile = dir.resolve("x.lock");
            Path ready = dir.resolve("ready.txt");
            Path released = dir.resolve("released.txt");
            Path log = dir.resolve("child.log");
            String classpath = codeSource(CacheEntryLock.class) + File.pathSeparator
                    + codeSource(CacheLockHolderMain.class);
            Process p = new ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", classpath, CacheLockHolderMain.class.getName(),
                    lockFile.toString(), ready.toString(), Long.toString(holdMillis), released.toString())
                    .redirectErrorStream(true)
                    .redirectOutput(log.toFile())
                    .start();
            return new Child(p, dir, lockFile, ready, released, log);
        }

        void awaitLocked() throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (!Files.exists(ready)) {
                assertThat(process.isAlive()).as("child died early, see %s", log).isTrue();
                assertThat(System.nanoTime()).as("child never took the lock, see %s", log).isLessThan(deadline);
                Thread.sleep(20);
            }
        }

        /** Never fails the test: kill if needed, wait for exit, delete best-effort. */
        void cleanUp() throws InterruptedException {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
            process.waitFor(30, TimeUnit.SECONDS);
            try (Stream<Path> s = Files.walk(dir)) {
                s.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // a virus scanner may still hold child.log; the OS temp dir is cleaned eventually
                    }
                });
            } catch (IOException ignored) {
                // best effort
            }
        }
    }

    @Test
    void theLockHoldsAcrossProcesses() throws Exception {
        Child child = Child.start(2000);
        try {
            child.awaitLocked();
            try (CacheEntryLock lock = CacheEntryLock.acquire(child.lockFile())) {
                // the child writes this file while still holding the lock: no timing assumption needed
                assertThat(child.released()).as("the parent must wait for the child's lock").exists();
            }
            assertThat(child.process().waitFor(60, TimeUnit.SECONDS)).isTrue();
            assertThat(child.process().exitValue()).as("child exit code, see %s", child.log()).isZero();
        } finally {
            child.cleanUp();
        }
    }

    /** I2(d): a holder that never lets go makes acquire fail with an IOException after the deadline. */
    @Test
    void aHolderInAnotherProcessMakesAcquireTimeOut() throws Exception {
        Child child = Child.start(TimeUnit.MINUTES.toMillis(10));
        try {
            child.awaitLocked();
            Duration deadline = Duration.ofSeconds(1);
            long t0 = System.nanoTime();
            assertThatThrownBy(() -> CacheEntryLock.acquire(child.lockFile(), deadline))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("timed out waiting for")
                    .hasMessageContaining("x.lock");
            long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            assertThat(waitedMs).as("waits the whole deadline").isGreaterThanOrEqualTo(deadline.toMillis());
            assertThat(waitedMs).as("and gives up roughly then, not after the default").isLessThan(60_000);
            assertThat(child.released()).as("the child still held the lock").doesNotExist();

            child.process().destroyForcibly();
            assertThat(child.process().waitFor(60, TimeUnit.SECONDS)).isTrue();
            // the timed-out attempt left nothing held: the same thread acquires (no reentrancy error)
            try (CacheEntryLock lock = CacheEntryLock.acquire(child.lockFile(), Duration.ofSeconds(60))) {
                assertThat(lock).isNotNull();
            }
        } finally {
            child.cleanUp();
        }
    }

    private static String codeSource(Class<?> c) throws Exception {
        return Path.of(c.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }
}
