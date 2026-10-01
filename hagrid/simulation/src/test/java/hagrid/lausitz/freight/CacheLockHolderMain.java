package hagrid.lausitz.freight;

import java.nio.file.Files;
import java.nio.file.Path;

/** Child process for CacheEntryLockTest: takes the lock, signals readiness, holds, releases. */
public final class CacheLockHolderMain {

    private CacheLockHolderMain() {
    }

    public static void main(String[] args) throws Exception {
        Path lockFile = Path.of(args[0]);
        Path ready = Path.of(args[1]);
        long holdMillis = Long.parseLong(args[2]);
        try (CacheEntryLock lock = CacheEntryLock.acquire(lockFile)) {
            Files.writeString(ready, "locked");
            Thread.sleep(holdMillis);
        }
    }
}
