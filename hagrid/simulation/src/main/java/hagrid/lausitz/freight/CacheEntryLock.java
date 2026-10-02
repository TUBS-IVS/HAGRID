package hagrid.lausitz.freight;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Exclusive lock for one cache key (spec 2026-10-01 section 6.1), across JVMs and threads.
 *
 * <p>Across JVMs: an OS-level {@link FileLock} on {@code <variant>-<key16>.lock}. The OS holds it,
 * not the file's existence, so a killed run releases it and a left-over lock file is harmless.
 * Within one JVM: a {@link ReentrantLock} per real path in addition, because a {@code FileLock} is
 * held for the whole JVM and a second thread would otherwise fail with
 * {@code OverlappingFileLockException}. The key is {@link Path#toRealPath}, so relative and absolute
 * spellings of the same file share one lock.
 *
 * <p><b>Not reentrant.</b> A thread that already holds the lock for a file and asks for it again gets
 * an {@link IllegalStateException}: a second {@link FileChannel} on the same file would, on Linux
 * (POSIX {@code fcntl} locks belong to the process, not to the channel), silently drop the outer
 * lock when it is closed. {@link #close()} must run on the thread that acquired the lock.
 *
 * <p><b>Bounded wait.</b> {@link #acquire} gives up after {@link #DEFAULT_TIMEOUT} (in-JVM and OS
 * wait together) with an {@link IOException}, so a stuck holder makes the caller compute without the
 * cache instead of blocking the run.
 *
 * <p>JDK only - no logging: the cross-process test starts this class with a minimal classpath. A
 * caller that wants to log a long wait passes a callback.
 */
final class CacheEntryLock implements AutoCloseable {

    /** Total wait of one acquisition before it fails. An entry access takes seconds, not minutes. */
    static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(10);
    /** After this long, {@code onLongWait} is told (once) that the acquisition is still waiting. */
    static final Duration LONG_WAIT = Duration.ofSeconds(10);
    private static final long POLL_MILLIS = 200;

    private static final ConcurrentHashMap<Path, ReentrantLock> IN_JVM = new ConcurrentHashMap<>();

    private final ReentrantLock jvmLock;
    private final FileChannel channel;
    private final FileLock fileLock;

    private CacheEntryLock(ReentrantLock jvmLock, FileChannel channel, FileLock fileLock) {
        this.jvmLock = jvmLock;
        this.channel = channel;
        this.fileLock = fileLock;
    }

    static CacheEntryLock acquire(Path lockFile) throws IOException {
        return acquire(lockFile, DEFAULT_TIMEOUT, ignored -> { });
    }

    /** Test seam for the deadline (I2(d)); production uses {@link #DEFAULT_TIMEOUT}. */
    static CacheEntryLock acquire(Path lockFile, Duration timeout) throws IOException {
        return acquire(lockFile, timeout, ignored -> { });
    }

    /**
     * @param timeout    total wait (in-JVM plus OS lock) before an {@link IOException} "timed out waiting for"
     * @param onLongWait called once, with the lock file, when the wait exceeds {@link #LONG_WAIT}
     * @throws IOException           on a timeout, an interrupt ({@link InterruptedIOException}) or a file error
     * @throws IllegalStateException if the current thread already holds this lock (not reentrant)
     */
    static CacheEntryLock acquire(Path lockFile, Duration timeout, Consumer<Path> onLongWait) throws IOException {
        Wait wait = new Wait(timeout, onLongWait);
        Files.createDirectories(lockFile.toAbsolutePath().getParent());
        try {
            Files.createFile(lockFile);
        } catch (FileAlreadyExistsException ignored) {
            // a lock file left by an earlier run is fine - the OS lock below is what counts
        }
        Path key = lockFile.toRealPath();
        ReentrantLock jvmLock = IN_JVM.computeIfAbsent(key, k -> new ReentrantLock());
        if (jvmLock.isHeldByCurrentThread()) {
            // before a second channel is opened: closing it would drop the outer lock on Linux
            throw new IllegalStateException("CacheEntryLock is not reentrant: " + key);
        }
        while (!tryLockInJvm(jvmLock, key)) {
            wait.check(key);
        }
        FileChannel channel = null;
        try {
            channel = FileChannel.open(key, StandardOpenOption.WRITE);
            FileLock fileLock;
            // null = another process holds it. OverlappingFileLockException cannot occur: the in-JVM
            // lock above admits one thread per file, and that thread is not already holding it.
            while ((fileLock = channel.tryLock()) == null) {
                wait.check(key);
                pause(key);
            }
            return new CacheEntryLock(jvmLock, channel, fileLock);
        } catch (IOException | RuntimeException | Error e) {
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException suppressed) {
                    e.addSuppressed(suppressed);
                }
            }
            jvmLock.unlock();
            throw e;
        }
    }

    @Override
    public void close() throws IOException {
        // each step runs even if the one before failed: a channel left open keeps the OS lock until the
        // JVM exits, and a jvmLock left locked makes every later acquire of this file in this JVM time out
        try {
            fileLock.release();
        } finally {
            try {
                channel.close();
            } finally {
                jvmLock.unlock();
            }
        }
    }

    private static boolean tryLockInJvm(ReentrantLock jvmLock, Path key) throws InterruptedIOException {
        try {
            return jvmLock.tryLock(POLL_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            throw interrupted(key, e);
        }
    }

    private static void pause(Path key) throws InterruptedIOException {
        try {
            Thread.sleep(POLL_MILLIS);
        } catch (InterruptedException e) {
            throw interrupted(key, e);
        }
    }

    private static InterruptedIOException interrupted(Path key, InterruptedException e) {
        Thread.currentThread().interrupt();
        InterruptedIOException io = new InterruptedIOException("interrupted while waiting for " + key);
        io.initCause(e);
        return io;
    }

    /** Deadline and the one-time long-wait notice of one acquisition. */
    private static final class Wait {
        private final long start = System.nanoTime();
        private final long timeoutNanos;
        private final Consumer<Path> onLongWait;
        private boolean told;

        Wait(Duration timeout, Consumer<Path> onLongWait) {
            this.timeoutNanos = timeout.toNanos();
            this.onLongWait = onLongWait;
        }

        void check(Path lockFile) throws IOException {
            long waited = System.nanoTime() - start;
            if (waited >= timeoutNanos) {
                throw new IOException("timed out waiting for " + lockFile + " after "
                        + TimeUnit.NANOSECONDS.toMillis(waited) + " ms");
            }
            if (!told && waited >= LONG_WAIT.toNanos()) {
                told = true;
                onLongWait.accept(lockFile);
            }
        }
    }
}
