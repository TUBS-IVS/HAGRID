package hagrid.core.routing;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Reusable bounded carrier pool. Only metadata waits for admission; solve includes construction,
 * conversion and validation. Results are accepted in input order on the calling thread.
 * Acceptance should stage results: callers publish a complete group only after run returns.
 *
 * Byte budgets and estimates must come from the run's resource policy. They are reservations,
 * not measured heap usage or a guarantee against an inaccurate estimate.
 */
public final class CarrierBatchExecutor implements AutoCloseable {
    public record Descriptor(String carrierId, int serviceCount, int shipmentCount, int profileCount,
                             long seed, long estimatedBytes) {
        public Descriptor {
            Objects.requireNonNull(carrierId);
            if (carrierId.isBlank() || serviceCount < 0 || shipmentCount < 0 || profileCount < 0 || estimatedBytes < 0)
                throw new IllegalArgumentException("Invalid carrier descriptor: " + carrierId);
        }
        public long jobCount() { return (long) serviceCount + shipmentCount; }
        public long activityCount() { return (long) serviceCount + 2L * shipmentCount; }
    }

    private final int maxActive;
    private final LongSupplier byteBudget;
    private final long terminationNanos;
    private final boolean estimatesRequired;
    private final AtomicLong reserved = new AtomicLong();
    private final AtomicInteger workers = new AtomicInteger();
    private final AtomicBoolean running = new AtomicBoolean();
    private final ThreadLocal<Boolean> insideWorker = new ThreadLocal<>();
    private final Object lifecycle = new Object();
    private volatile ThreadPoolExecutor pool;
    private volatile Thread owner;
    private volatile CountDownLatch batchEnded = new CountDownLatch(0);
    private volatile boolean closed, unavailable;

    /** Conservative serial compatibility path until a measured run-specific byte policy is supplied. */
    public CarrierBatchExecutor() { this(1, () -> Long.MAX_VALUE, Duration.ofSeconds(60), false); }

    public CarrierBatchExecutor(int maxActive, long byteBudget, Duration terminationTimeout) {
        this(maxActive, constantBudget(byteBudget), terminationTimeout, true);
    }

    /** Fresh run-specific margin before each nonempty group; fixed throughout that group's lifetime. */
    public CarrierBatchExecutor(int maxActive, LongSupplier byteBudget, Duration terminationTimeout) {
        this(maxActive, byteBudget, terminationTimeout, true);
    }

    private static LongSupplier constantBudget(long bytes) {
        if (bytes < 1) throw new IllegalArgumentException("Positive memory limit required");
        return () -> bytes;
    }

    private CarrierBatchExecutor(int maxActive, LongSupplier byteBudget, Duration timeout, boolean estimatesRequired) {
        Objects.requireNonNull(timeout);
        if (maxActive < 1 || timeout.isZero() || timeout.isNegative())
            throw new IllegalArgumentException("Positive worker, memory and termination limits required");
        this.maxActive = maxActive;
        this.byteBudget = Objects.requireNonNull(byteBudget);
        this.terminationNanos = timeout.toNanos();
        this.estimatesRequired = estimatesRequired;
    }

    public long reservedBytes() { return reserved.get(); }
    public int createdWorkers() { return workers.get(); }

    /** Streaming acceptance transfers result ownership and its memory responsibility to the caller. */
    public <R> void run(List<Descriptor> group, Function<Descriptor, R> solve, Consumer<R> accept) {
        run(group, solve, accept, () -> {}, false);
    }

    /**
     * Staged groups retain every full carrier reservation through caller-thread publication.
     * This conservative bound needs no invented smaller result estimate. Whole-group fit is checked
     * before construction; shared context/previously published plans belong to the run's base budget.
     */
    public <R> void runStaged(List<Descriptor> group, Function<Descriptor, R> solve,
                              Consumer<R> stage, Runnable publish) {
        run(group, solve, stage, Objects.requireNonNull(publish), true);
    }

    /** A run's configured solver cap can only lower the owner's calibrated worker ceiling. */
    public <R> void runStaged(int maxActiveSolvers, List<Descriptor> group, Function<Descriptor, R> solve,
                              Consumer<R> stage, Runnable publish) {
        run(maxActiveSolvers, group, solve, stage, Objects.requireNonNull(publish), true);
    }

    private <R> void run(List<Descriptor> group, Function<Descriptor, R> solve, Consumer<R> accept,
                         Runnable publish, boolean staged) {
        run(maxActive, group, solve, accept, publish, staged);
    }

    private <R> void run(int maxActiveSolvers, List<Descriptor> group, Function<Descriptor, R> solve,
                         Consumer<R> accept, Runnable publish, boolean staged) {
        if (maxActiveSolvers < 1) throw new IllegalArgumentException("Positive active solver cap required");
        int admissionLimit = Math.min(maxActive, maxActiveSolvers);
        Objects.requireNonNull(solve); Objects.requireNonNull(accept);
        var descriptors = List.copyOf(group);
        synchronized (lifecycle) {
            ensureAvailable();
            if (!running.compareAndSet(false, true)) throw new IllegalStateException("A carrier batch is already running");
            owner = Thread.currentThread();
            batchEnded = new CountDownLatch(1);
        }
        var admitted = new ArrayList<Work<R>>();
        var retained = new ArrayList<Work<R>>();
        var failure = new AtomicReference<Throwable>();
        var aborting = new AtomicBoolean();
        try {
            checkCaller(failure);
            // Observe only after owning the group: a concurrent caller must not carry a stale
            // snapshot across another group's publication. Failures still release the claim.
            long budget = descriptors.isEmpty() ? 1L : byteBudget.getAsLong();
            if (budget < 1) throw new IllegalStateException("No memory budget available for carrier admission");
            preflight(descriptors, budget);
            if (staged) preflightStaged(descriptors, budget);
            int next = 0;
            while (next < descriptors.size() || !admitted.isEmpty()) {
                checkCaller(failure);
                // FIFO: a small later carrier cannot continually displace a large waiting one.
                while (next < descriptors.size() && admitted.size() < admissionLimit) {
                    var descriptor = descriptors.get(next);
                    if (descriptor.estimatedBytes() > budget - reserved.get()) break;
                    var work = new Work<R>(descriptor, solve, failure, aborting);
                    reserved.addAndGet(descriptor.estimatedBytes());
                    admitted.add(work);
                    executor(admissionLimit).execute(work);
                    next++;
                }
                if (admitted.isEmpty()) throw new IllegalStateException("No carrier fits the remaining reservation budget");
                var first = admitted.getFirst();
                R result;
                try { result = first.get(25, TimeUnit.MILLISECONDS); }
                catch (TimeoutException ignored) { continue; }
                catch (ExecutionException e) { throw propagate(e.getCause()); }
                // Future completion can precede the worker's finally block. Wait for actual task exit.
                while (!first.terminated.await(25, TimeUnit.MILLISECONDS)) checkCaller(failure);
                // Acceptance and close have one linearization boundary.
                synchronized (lifecycle) {
                    checkCaller(failure);
                    if (staged) retained.add(first);
                    accept.accept(result);
                    if (!staged) first.release();
                    admitted.removeFirst();
                }
            }
            synchronized (lifecycle) {
                checkCaller(failure);
                publish.run();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            abort(admitted, aborting, new CancellationException("Carrier batch interrupted"));
        } catch (RuntimeException | Error e) {
            abort(admitted, aborting, e);
        } finally {
            retained.forEach(Work::release);
            synchronized (lifecycle) {
                owner = null;
                running.set(false);
                batchEnded.countDown();
            }
        }
    }

    private void preflight(List<Descriptor> group, long byteBudget) {
        var ids = new HashSet<String>();
        for (var descriptor : group) {
            if (!ids.add(descriptor.carrierId()))
                throw new IllegalArgumentException("Duplicate carrier: " + descriptor.carrierId());
            if (estimatesRequired && descriptor.estimatedBytes() == 0)
                throw new IllegalArgumentException("Missing byte estimate for carrier " + descriptor.carrierId());
            if (descriptor.estimatedBytes() > byteBudget)
                throw new IllegalArgumentException("Carrier " + descriptor.carrierId() + " exceeds reservation budget: "
                        + descriptor.estimatedBytes() + " > " + byteBudget);
        }
    }

    private void preflightStaged(List<Descriptor> group, long byteBudget) {
        long total = 0;
        for (var descriptor : group) {
            if (descriptor.estimatedBytes() > byteBudget - total)
                throw new IllegalArgumentException("Staged carrier group exceeds reservation budget before carrier "
                        + descriptor.carrierId() + ": cumulative reservations > " + byteBudget);
            total += descriptor.estimatedBytes();
        }
    }
    private ExecutorService executor(int admissionLimit) {
        synchronized (lifecycle) {
            ensureAvailable();
            if (pool == null) pool = (ThreadPoolExecutor) Executors.newFixedThreadPool(admissionLimit, action -> {
                var thread = new Thread(action, "hagrid-carrier-" + workers.incrementAndGet());
                thread.setDaemon(true);
                thread.setPriority(Thread.MIN_PRIORITY);
                return thread;
            });
            // No prior batch tasks remain here; both admission and pool width use the same cap.
            if (pool.getMaximumPoolSize() < admissionLimit) {
                pool.setMaximumPoolSize(admissionLimit);
                pool.setCorePoolSize(admissionLimit);
            } else if (pool.getCorePoolSize() > admissionLimit) {
                pool.setCorePoolSize(admissionLimit);
                pool.setMaximumPoolSize(admissionLimit);
            }
            return pool;
        }
    }

    private void ensureAvailable() {
        if (closed || unavailable) throw new IllegalStateException("Carrier executor is unavailable");
    }

    private void checkCaller(AtomicReference<Throwable> failure) {
        if (Thread.currentThread().isInterrupted() || closed)
            throw new CancellationException("Carrier batch interrupted or closed");
        var cause = failure.get();
        if (cause != null) throw propagate(cause);
    }

    private <R> void abort(List<Work<R>> admitted, AtomicBoolean aborting, Throwable cause) {
        aborting.set(true);
        boolean interrupted = Thread.interrupted();
        long deadline = System.nanoTime() + terminationNanos;
        admitted.forEach(work -> work.cancel(true));
        try {
            for (var work : admitted) {
                while (work.terminated.getCount() != 0) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        unavailable = true;
                        throw new IllegalStateException("Carrier workers did not terminate; running reservations retained", cause);
                    }
                    try { work.terminated.await(remaining, TimeUnit.NANOSECONDS); }
                    catch (InterruptedException e) { interrupted = true; }
                }
                work.release();
            }
        } finally {
            // Workers that outlive the drain timeout release only from their actual exit.
            admitted.stream().filter(work -> work.terminated.getCount() == 0).forEach(Work::release);
            if (interrupted) Thread.currentThread().interrupt();
        }
        throw propagate(cause);
    }

    private static RuntimeException propagate(Throwable cause) {
        if (cause instanceof Error error) throw error;
        if (cause instanceof RuntimeException exception) return exception;
        return new IllegalStateException("Carrier computation failed", cause);
    }

    private final class Work<R> extends FutureTask<R> {
        private final Descriptor descriptor;
        private final AtomicBoolean aborting;
        private final AtomicInteger state = new AtomicInteger(); // queued, running, terminated
        private final AtomicBoolean released = new AtomicBoolean();
        private final CountDownLatch terminated = new CountDownLatch(1);

        Work(Descriptor descriptor, Function<Descriptor, R> solve, AtomicReference<Throwable> failure,
             AtomicBoolean aborting) {
            super(() -> {
                try { return solve.apply(descriptor); }
                catch (RuntimeException | Error e) { failure.compareAndSet(null, e); throw e; }
            });
            this.descriptor = descriptor;
            this.aborting = aborting;
        }
        @Override public void run() {
            if (!state.compareAndSet(0, 1)) return;
            insideWorker.set(true);
            try { super.run(); }
            finally { insideWorker.remove(); state.set(2); if (aborting.get()) release(); terminated.countDown(); }
        }
        @Override public boolean cancel(boolean interrupt) {
            boolean cancelled = super.cancel(interrupt);
            if (state.compareAndSet(0, 2)) {
                if (aborting.get()) release();
                terminated.countDown();
            }
            return cancelled;
        }
        void release() {
            if (released.compareAndSet(false, true)) reserved.addAndGet(-descriptor.estimatedBytes());
        }
    }

    @Override public void close() {
        if (Boolean.TRUE.equals(insideWorker.get())) throw new IllegalStateException("Cannot close executor from its own worker");
        CountDownLatch ended;
        ExecutorService executor;
        synchronized (lifecycle) {
            if (owner == Thread.currentThread()) throw new IllegalStateException("Cannot close executor from its active batch");
            closed = true;
            ended = batchEnded;
            executor = pool;
            if (owner != null) owner.interrupt();
            if (executor != null) executor.shutdownNow();
        }
        boolean interrupted = Thread.interrupted();
        long deadline = System.nanoTime() + terminationNanos;
        try {
            while (ended.getCount() != 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new IllegalStateException("Carrier batch did not terminate during close");
                try { ended.await(remaining, TimeUnit.NANOSECONDS); }
                catch (InterruptedException e) { interrupted = true; }
            }
            if (executor != null) {
                for (;;) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0 && !executor.isTerminated())
                        throw new IllegalStateException("Carrier workers did not terminate during close");
                    try { if (executor.awaitTermination(Math.max(0, remaining), TimeUnit.NANOSECONDS)) break; }
                    catch (InterruptedException e) { interrupted = true; }
                }
            }
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
}