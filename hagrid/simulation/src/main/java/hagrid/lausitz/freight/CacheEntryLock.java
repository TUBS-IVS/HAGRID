package hagrid.lausitz.freight;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

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
 * <p>JDK only - no logging: the cross-process test starts this class with a minimal classpath.
 */
final class CacheEntryLock implements AutoCloseable {

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
        Files.createDirectories(lockFile.toAbsolutePath().getParent());
        try {
            Files.createFile(lockFile);
        } catch (FileAlreadyExistsException ignored) {
            // a lock file left by an earlier run is fine - the OS lock below is what counts
        }
        Path key = lockFile.toRealPath();
        ReentrantLock jvmLock = IN_JVM.computeIfAbsent(key, k -> new ReentrantLock());
        jvmLock.lock();
        FileChannel channel = null;
        try {
            channel = FileChannel.open(key, StandardOpenOption.WRITE);
            FileLock fileLock = channel.lock();
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
        try {
            fileLock.release();
            channel.close();
        } finally {
            jvmLock.unlock();
        }
    }
}
