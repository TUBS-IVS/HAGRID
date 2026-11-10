package hagrid.core.routing;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Owns a search's insertion workers, including failed setup and interrupted searches. */
final class SolverWorkers implements AutoCloseable {
    private static final AtomicInteger SEARCH_IDS = new AtomicInteger();
    private final ExecutorService executor;

    SolverWorkers(int threads) {
        if (threads < 1) throw new IllegalArgumentException("Solver threads must be positive");
        if (threads == 1) { executor = null; return; }
        int searchId = SEARCH_IDS.incrementAndGet();
        var workerIds = new AtomicInteger();
        executor = Executors.newFixedThreadPool(threads, task -> {
            var thread = new Thread(task, "hagrid-jsprit-" + searchId + "-" + workerIds.incrementAndGet());
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        });
    }

    ExecutorService executor() { return executor; }

    @Override public void close() {
        if (executor == null) return;
        boolean interrupted = Thread.interrupted();
        executor.shutdownNow();
        try {
            // An outer carrier reservation must remain held until every internal callback exits.
            // The outer batch drain timeout still reports an uncooperative callback as unavailable.
            while (!executor.isTerminated()) {
                try { executor.awaitTermination(100, TimeUnit.MILLISECONDS); }
                catch (InterruptedException e) { interrupted = true; }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}