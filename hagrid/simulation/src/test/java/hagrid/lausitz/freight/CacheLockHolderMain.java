package hagrid.lausitz.freight;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Child process for CacheEntryLockTest: takes the lock, signals readiness, holds, writes the
 * released marker immediately BEFORE it releases the lock, releases. JDK only (minimal classpath).
 *
 * <p>Arguments: lock file, ready file, hold milliseconds, released-marker file.
 */
public final class CacheLockHolderMain {

    private CacheLockHolderMain() {
    }

    public static void main(String[] args) throws Exception {
        Path lockFile = Path.of(args[0]);
        Path ready = Path.of(args[1]);
        long holdMillis = Long.parseLong(args[2]);
        Path released = Path.of(args[3]);
        try (CacheEntryLock lock = CacheEntryLock.acquire(lockFile)) {
            Files.writeString(ready, "locked");
            Thread.sleep(holdMillis);
            // still holding the lock: whoever acquires it next must already see this file
            Files.writeString(released, "released");
        }
    }
}
