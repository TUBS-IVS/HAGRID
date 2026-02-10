package hagrid.demand;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;
import org.matsim.api.core.v01.network.Network;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierPlan;
import org.matsim.freight.carriers.Carriers;
import org.matsim.freight.carriers.CarriersUtils;
import org.matsim.freight.carriers.jsprit.MatsimJspritFactory;
import org.matsim.freight.carriers.jsprit.NetworkRouter;
import org.matsim.freight.carriers.jsprit.VRPTransportCosts;

import com.graphhopper.jsprit.core.algorithm.VehicleRoutingAlgorithm;
import com.graphhopper.jsprit.core.algorithm.listener.IterationEndsListener;
import com.graphhopper.jsprit.core.problem.VehicleRoutingProblem;
import com.graphhopper.jsprit.core.problem.solution.VehicleRoutingProblemSolution;
import com.graphhopper.jsprit.core.util.Solutions;

import hagrid.utils.routing.HAGRIDRouterUtils;
import hagrid.utils.routing.CarrierRoutingMetrics;
import hagrid.utils.routing.GatedCarrierTask;
import hagrid.utils.routing.JspritCarrierTask;
import hagrid.utils.routing.JspritTreadPoolExecutor;
import hagrid.utils.routing.ThreadingType;
import hagrid.utils.routing.CarrierRoutingStatusLogger;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.Optional;
import org.matsim.freight.carriers.CarrierService;
import java.util.Locale;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import hagrid.HagridConfig;
import org.matsim.freight.carriers.CarrierPlanWriter;
import org.matsim.freight.carriers.CarrierPlanXmlReader;
import org.matsim.freight.carriers.CarrierVehicleTypes;

public class Router {

    private static final Logger LOGGER = LogManager.getLogger(Router.class);

    // Classification threshold for "big" carriers by number of services
    private static final int BIG_THRESHOLD = 350;

    // jsprit iteration count is tracked per-carrier via CarriersUtils; no constant
    // needed here

    private final ThreadingType threadingType;
    private final CarrierRoutingStatusLogger statusLogger;
    private final HagridConfig hagridConfigRef; // keep reference for lazy cache init
    private final CarrierVehicleTypes sharedVehicleTypes; // may be null if not provided

    // ===== Carrier routing cache (configurable) =====
    private boolean carrierCacheEnabled; // no longer final -> can be lazily enabled
    private Path carrierCacheDir;        // no longer final
    private static final String CACHE_FILE_PREFIX = "carrier_";
    private static final String CACHE_FILE_SUFFIX = ".xml"; // plain xml (no gzip)
    // version removed from file name pattern per requirement; keep constant if needed for future invalidation
    private static final String CACHE_VERSION = "v1"; 
    // =================================================

    private static final boolean CACHE_IO_AVAILABLE = initCacheIoFlag();
    private static boolean initCacheIoFlag() {
        try {
            Class.forName("org.matsim.freight.carriers.CarrierPlanWriter");
            Class.forName("org.matsim.freight.carriers.CarrierPlanXmlReader");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    public Router(ThreadingType threadingType) {
        this(threadingType, null, null, null);
    }

    public Router(ThreadingType threadingType, CarrierRoutingStatusLogger statusLogger) {
        this(threadingType, statusLogger, null, null);
    }

    /**
     * Extended constructor enabling optional per-carrier routing cache controlled by HagridConfig.
     * Expected config methods (adjust if names differ):
     *  - boolean isCarrierRoutingCacheEnabled()
     *  - String getCarrierRoutingCacheDir()
     * If methods differ, adapt in code (TODO markers left).
     */
    public Router(ThreadingType threadingType, CarrierRoutingStatusLogger statusLogger, HagridConfig hagridConfig) {
        this(threadingType, statusLogger, hagridConfig, null);
    }

    public Router(ThreadingType threadingType, CarrierRoutingStatusLogger statusLogger, HagridConfig hagridConfig, CarrierVehicleTypes vehicleTypes) {
        this.threadingType = threadingType;
        this.statusLogger = statusLogger;
        this.hagridConfigRef = hagridConfig;
        this.sharedVehicleTypes = vehicleTypes;
        initializeCacheFromConfig();
    }

    private void initializeCacheFromConfig() {
        // System property override (highest priority)
        String sysDir = System.getProperty("hagrid.router.cache.dir");
        String sysEnabled = System.getProperty("hagrid.router.cache.enabled");
        if (sysDir != null && !sysDir.isBlank() && ("true".equalsIgnoreCase(sysEnabled) || sysEnabled == null)) {
            try {
                Path dir = Paths.get(sysDir);
                Files.createDirectories(dir);
                this.carrierCacheEnabled = true;
                this.carrierCacheDir = dir;
                LOGGER.info("Carrier routing cache ENABLED via system properties at {}", dir.toAbsolutePath());
                return; // skip config based resolution
            } catch (Exception e) {
                LOGGER.warn("Failed to init cache via system property dir {}: {}", sysDir, e.toString());
            }
        }
        boolean enabled = false;
        String dirStr = null;
        if (hagridConfigRef != null) {
            try {
                enabled = invokeBoolean(hagridConfigRef, "isCarrierRoutingCacheEnabled", false,
                        invokeBoolean(hagridConfigRef, "isLoadCarrierCache", false,
                                invokeBoolean(hagridConfigRef, "isLoadCache", false, false)));
                dirStr = invokeString(hagridConfigRef, "getCarrierRoutingCacheDir", null,
                        invokeString(hagridConfigRef, "getCarrierCacheDir", null,
                                invokeString(hagridConfigRef, "getCacheDir", null, null)));
            } catch (Exception ignored) { }
        }
        if (enabled && dirStr != null && !dirStr.isBlank()) {
            Path dir = Paths.get(dirStr);
            try { Files.createDirectories(dir); } catch (Exception e) { LOGGER.warn("Cannot create carrier cache directory {}: {}", dir, e.toString()); return; }
            this.carrierCacheEnabled = true;
            this.carrierCacheDir = dir;
            LOGGER.info("Carrier routing cache ENABLED (lazy init) at {}", dir.toAbsolutePath());
        } else {
            this.carrierCacheEnabled = false;
            this.carrierCacheDir = null;
        }
    }

    private boolean cacheInitialized = false;

    private void ensureCacheInitialized() {
        if (!cacheInitialized) {
            initializeCacheFromConfig();
            cacheInitialized = true;
        }
    }

    /**
     * Gets JSprit iterations from HagridConfig or returns default (1).
     */
    private int getJspritIterations() {
        if (hagridConfigRef != null) {
            try {
                return hagridConfigRef.routing().getJspritIterations();
            } catch (Exception e) {
                LOGGER.debug("Could not get jspritIterations from config, using default: {}", e.getMessage());
            }
        }
        return 1; // default: 1 iteration for initial model
    }

    /**
     * Gets U-turn penalty from HagridConfig or returns default (1.0).
     * This is a SCORE penalty (cost units), not real seconds or euros.
     */
    private double getUTurnPenaltyCost() {
        if (hagridConfigRef != null) {
            try {
                return hagridConfigRef.routing().getUTurnPenaltyCost();
            } catch (Exception e) {
                LOGGER.debug("Could not get uTurnPenaltyCost from config, using default: {}", e.getMessage());
            }
        }
        return 1.0; // default: score penalty per U-turn
    }

    // Thread-safe store for per-carrier metrics
    private final ConcurrentLinkedQueue<CarrierRoutingMetrics> metrics = new ConcurrentLinkedQueue<>();

    // Track currently active carrier routings for global status summary
    private static final class ActiveCarrier {
        final String carrierId;
        final String provider;
        final String carrierType;
        final int services;
        final int deliveries; // total capacity demand (sum of service capacity)
        final String sizeClass;
        final long startMs;
        final String threadName;

        ActiveCarrier(String carrierId, String provider, String carrierType, int services,
                int deliveries, String sizeClass, long startMs, String threadName) {
            this.carrierId = carrierId;
            this.provider = provider;
            this.carrierType = carrierType;
            this.services = services;
            this.deliveries = deliveries;
            this.sizeClass = sizeClass;
            this.startMs = startMs;
            this.threadName = threadName;
        }
    }

    private final java.util.concurrent.ConcurrentHashMap<String, ActiveCarrier> activeCarriers = new java.util.concurrent.ConcurrentHashMap<>();
    private final AtomicBoolean statusSchedulerRunning = new AtomicBoolean(false);
    private ScheduledExecutorService statusScheduler;

    public Collection<CarrierRoutingMetrics> getMetrics() {
        return metrics;
    }

    // no extra config helpers needed when concurrency is hardcoded

    // Start a periodic global status log every 10 minutes summarizing running
    // carriers
    private void startStatusSchedulerIfNeeded() {
        if (statusSchedulerRunning.compareAndSet(false, true)) {
            statusScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "Router-Status");
                t.setDaemon(true);
                return t;
            });
            statusScheduler.scheduleAtFixedRate(this::logGlobalStatus, 10, 10, TimeUnit.MINUTES);
        }
    }

    private void stopStatusScheduler() {
        if (statusScheduler != null) {
            statusScheduler.shutdownNow();
            statusScheduler = null;
        }
        statusSchedulerRunning.set(false);
    }

    private void logGlobalStatus() {
        try {
            int n = activeCarriers.size();
            if (n == 0) {
                LOGGER.info("[STATUS] No active carriers — scheduler tick.");
                return;
            }
            long now = System.currentTimeMillis();
            List<String> normals = new ArrayList<>();
            List<String> criticals = new ArrayList<>();
            for (ActiveCarrier ac : activeCarriers.values()) {
                double min = (now - ac.startMs) / 60000.0;
                String entry = String.format(java.util.Locale.ROOT,
                        "- %-22s %-7s services=%4d deliveries=%5d type=%-8s t=%5.1fm thr=%s",
                        ac.carrierId + "[" + ac.provider + "]",
                        "(" + ac.sizeClass + ")",
                        ac.services,
                        ac.deliveries,
                        ac.carrierType,
                        min,
                        ac.threadName);
                if (min >= 10.0) {
                    criticals.add(entry);
                    if (statusLogger != null) {
                        statusLogger.appendCriticalHeartbeat(ac.carrierType, ac.carrierId, ac.provider, ac.services,
                                ac.sizeClass, threadingType.toString(), min * 60.0, ac.threadName, ">10m running");
                    }
                } else {
                    normals.add(entry);
                }
            }
            LOGGER.info("[STATUS] Active carriers: {} (critical={}) threading={}", n, criticals.size(), threadingType);
            if (!normals.isEmpty()) LOGGER.info("[STATUS] Normal:\n{}", String.join(System.lineSeparator(), normals));
            if (!criticals.isEmpty()) LOGGER.warn("[CRITICAL] Over 10 minutes:\n{}", String.join(System.lineSeparator(), criticals));
        } catch (Throwable t) {
            LOGGER.error("Global status scheduler encountered an error: {}", t.toString());
        }
    }

    /**
     * Routes the carriers using the specified threading type.
     *
     * @param carriers      The carriers to be routed.
     * @param netBasedCosts The network-based transport costs.
     * @param network       The network.
     */
    public void routeCarriers(Carriers carriers, final VRPTransportCosts netBasedCosts, Network network,
            String carrierType) {
        ensureCacheInitialized();
        LOGGER.info("Starting routing of carriers using {}...", threadingType);

        long cacheFileCount = 0L;
        if (carrierCacheEnabled) {
            try {
                cacheFileCount = Files.list(carrierCacheDir)
                        .filter(p -> p.getFileName().toString().startsWith(CACHE_FILE_PREFIX) && p.getFileName().toString().endsWith(CACHE_FILE_SUFFIX))
                        .count();
            } catch (Exception e) {
                LOGGER.warn("Could not enumerate cache directory {}: {}", carrierCacheDir, e.toString());
            }
        }

        int cachedBefore = 0;
        if (carrierCacheEnabled) {
            cachedBefore = preloadCarrierPlansFromCache(carriers);
            LOGGER.info("[CACHE] preload restoredPlans={} existingCacheFiles={} totalCarriers={} (dir={})", cachedBefore, cacheFileCount, carriers.getCarriers().size(), carrierCacheDir);
        }

        // Carriers already having a plan after preload
        long withPlan = carriers.getCarriers().values().stream().filter(c -> c.getSelectedPlan() != null).count();

        List<Carrier> carriersNeedingRouting = carriers.getCarriers().values().stream()
                .filter(c -> c.getSelectedPlan() == null)
                .collect(Collectors.toList());

        // Enhanced professional cache summary log
        int totalCarriersCount = carriers.getCarriers().size();
        double pctCached = totalCarriersCount == 0 ? 0d : (withPlan * 100.0 / totalCarriersCount);
        double pctToRoute = totalCarriersCount == 0 ? 0d : (carriersNeedingRouting.size() * 100.0 / totalCarriersCount);
        LOGGER.info(String.format(Locale.ROOT,
                "\n==================== CARRIER CACHE SUMMARY ====================\n" +
                " Total carriers         : %d\n" +
                " Cached (have plan)     : %d (%.1f%%)\n" +
                " To route now           : %d (%.1f%%)\n" +
                " Cache enabled          : %s\n" +
                " Cache version          : %s\n" +
                " Cache directory        : %s\n" +
                "==============================================================",
                totalCarriersCount, withPlan, pctCached,
                carriersNeedingRouting.size(), pctToRoute,
                carrierCacheEnabled, CACHE_VERSION,
                carrierCacheDir));

        if (carriersNeedingRouting.isEmpty()) {
            LOGGER.info("All {} carriers already have plans (from cache). Nothing to route.", carriers.getCarriers().size());
            stopStatusScheduler();
            return;
        }

        // Sort remaining carriers by the number of services+shipments ascending
        List<Carrier> sortedCarriers = carriersNeedingRouting.stream()
                .sorted(Comparator.comparingInt(c -> c.getServices().size() + c.getShipments().size()))
                .collect(Collectors.toList());

        final int threads = Runtime.getRuntime().availableProcessors();
        final int smallBatchSize = Math.max(threads - 1, 1);

        List<Carrier> largeCarriers = sortedCarriers.stream()
                .filter(c -> c.getServices().size() >= BIG_THRESHOLD)
                .sorted(Comparator.comparingInt((Carrier c) -> c.getServices().size()).reversed())
                .collect(Collectors.toList());

        List<Carrier> smallCarriers = sortedCarriers.stream()
                .filter(c -> c.getServices().size() < BIG_THRESHOLD)
                .collect(Collectors.toList());

        List<Carrier> interleavedSchedule = new ArrayList<>(sortedCarriers.size());
        int iBig = 0, iSmall = 0;
        while (iBig < largeCarriers.size() || iSmall < smallCarriers.size()) {
            // take one big if available
            if (iBig < largeCarriers.size()) {
                interleavedSchedule.add(largeCarriers.get(iBig++));
            }
            // then up to (threads-1) small ones
            int taken = 0;
            while (taken < smallBatchSize && iSmall < smallCarriers.size()) {
                interleavedSchedule.add(smallCarriers.get(iSmall++));
                taken++;
            }
        }

        // Log schedule summary
        LOGGER.info("Routing schedule prepared: toRoute={} (cachedAlready={}), large={}, small={}, threads={}, smallBatchSize={}",
                sortedCarriers.size(), cachedBefore, largeCarriers.size(), smallCarriers.size(), threads, smallBatchSize);
        if (!largeCarriers.isEmpty()) {
            String bigPreview = largeCarriers.stream()
                    .map(c -> c.getId() + "(" + c.getServices().size() + ")")
                    .collect(Collectors.joining(", "));
            LOGGER.info("Largest remaining carriers: {}", bigPreview);
        }

        // Start global status scheduler
        startStatusSchedulerIfNeeded();

        AtomicInteger progress = new AtomicInteger();
        long startTime = System.currentTimeMillis();
        List<Long> routedTimes = new ArrayList<>();

        // Adaptive gating: allow only 1 LARGE concurrently while SMALLs exist; when
        // SMALLs are done,
        // open up to desiredLargeConcurrency to improve utilization
        final int smallTotal = smallCarriers.size();
        final AtomicInteger smallRemaining = new AtomicInteger(smallTotal);
        final int largeTotal = largeCarriers.size();
        final AtomicInteger largeRemaining = new AtomicInteger(largeTotal);
        final AtomicInteger finishedCounter = new AtomicInteger(0);
        final AtomicBoolean bumpedLargeConcurrency = new AtomicBoolean(false);
    // Allow as many LARGE in parallel as threads (full utilization at the end)
    final int desiredLargeConcurrency = threads;
    final int initialLargeConcurrency = desiredLargeConcurrency;
    LOGGER.info(
        "Large-carrier concurrency (max threads): initialPermits={} desiredPermits={} (threads={}), smallRemaining={}",
        initialLargeConcurrency, desiredLargeConcurrency, threads, smallTotal);

        try {
            switch (threadingType) {
                case FORK_JOIN_POOL:
                    // Use ForkJoinPool for parallel processing with try-with-resources to ensure
                    // proper closure
                    try (ForkJoinPool forkJoinPool = new ForkJoinPool(threads)) {
                        final Semaphore bigGateFjp = new Semaphore(initialLargeConcurrency);
                        forkJoinPool.submit(() -> interleavedSchedule.parallelStream()
                                .forEach(carrier -> {
                                    long started = System.currentTimeMillis();
                                    int services = carrier.getServices().size();
                                    boolean acquired = false;
                                    try {
                                        if (services >= BIG_THRESHOLD) {
                                            bigGateFjp.acquire();
                                            acquired = true;
                                        }
                                        routeCarrier(carrier, netBasedCosts, network, progress, sortedCarriers.size(), carrierType);
                                    } catch (InterruptedException ie) {
                                        Thread.currentThread().interrupt();
                                        LOGGER.error("Interrupted during gating", ie);
                                    } finally {
                                        if (acquired)
                                            bigGateFjp.release();
                                        if (services < BIG_THRESHOLD) {
                                            int left = smallRemaining.decrementAndGet();
                                            if (left == 0 && bumpedLargeConcurrency.compareAndSet(false, true)) {
                                                int delta = Math.max(0,
                                                        desiredLargeConcurrency - initialLargeConcurrency);
                                                if (delta > 0) {
                                                    bigGateFjp.release(delta);
                                                    LOGGER.info(
                                                            "All SMALL carriers done. Increased LARGE concurrency permits by {} -> now {}.",
                                                            delta, desiredLargeConcurrency);
                                                }
                                            }
                                        } else {
                                            largeRemaining.decrementAndGet();
                                        }
                                        int done = finishedCounter.incrementAndGet();
                                        double elapsed = (System.currentTimeMillis() - started) / 1000.0;
                                        String clazzDone = services >= BIG_THRESHOLD ? "LARGE" : "SMALL";
                                        LOGGER.info(
                                                "[UPDATE] Finished carrier {} class={} ({} / {}) in {}s — remaining small={} large={}",
                                                carrier.getId(), clazzDone, done, sortedCarriers.size(),
                                                String.format(Locale.ROOT, "%.3f", elapsed),
                                                smallRemaining.get(), largeRemaining.get());
                                        routedTimes.add(System.currentTimeMillis());
                                    }
                                })).get();
                    }
                    break;
                case MAT_SIM_THREAD_POOL:
                    // Use MATSim's custom thread pool executor for parallel processing
                    ThreadPoolExecutor executor = new JspritTreadPoolExecutor(new LinkedBlockingQueue<>(),
                            threads);
                    final Semaphore bigGate = new Semaphore(initialLargeConcurrency);
                    List<Future<?>> futures = interleavedSchedule.stream()
                            .map(carrier -> {
                                JspritCarrierTask task = new JspritCarrierTask(carrier, netBasedCosts, progress,
                                        sortedCarriers.size(), network, getUTurnPenaltyCost());
                                Runnable gated = new GatedCarrierTask(task, bigGate, BIG_THRESHOLD);
                                boolean isSmall = carrier.getServices().size() < BIG_THRESHOLD;
                                return (Runnable) () -> {
                                    long started = System.currentTimeMillis();
                                    try {
                                        gated.run();
                                    } finally {
                                        if (isSmall) {
                                            int left = smallRemaining.decrementAndGet();
                                            if (left == 0 && bumpedLargeConcurrency.compareAndSet(false, true)) {
                                                int delta = Math.max(0,
                                                        desiredLargeConcurrency - initialLargeConcurrency);
                                                if (delta > 0) {
                                                    bigGate.release(delta);
                                                    LOGGER.info(
                                                            "All SMALL carriers done. Increased LARGE concurrency permits by {} -> now {}.",
                                                            delta, desiredLargeConcurrency);
                                                }
                                            }
                                        } else {
                                            largeRemaining.decrementAndGet();
                                        }
                                        int done = finishedCounter.incrementAndGet();
                                        double elapsed = (System.currentTimeMillis() - started) / 1000.0;
                                        String clazzDone = isSmall ? "SMALL" : "LARGE";
                                        LOGGER.info(
                                                "[UPDATE] Finished carrier {} class={} ({} / {}) in {}s — remaining small={} large={}",
                                                carrier.getId(), clazzDone, done, sortedCarriers.size(),
                                                String.format(Locale.ROOT, "%.3f", elapsed),
                                                smallRemaining.get(), largeRemaining.get());
                                    }
                                };
                            })
                            .map(executor::submit)
                            .collect(Collectors.toList());

                    for (Future<?> future : futures) {
                        future.get();
                        routedTimes.add(System.currentTimeMillis());
                    }
                    executor.shutdown();
                    break;
                case COMPLETABLE_FUTURE:
                    ExecutorService completableFutureExecutor = Executors.newFixedThreadPool(
                            Runtime.getRuntime().availableProcessors());
                    final Semaphore bigGateCf = new Semaphore(initialLargeConcurrency);

                    List<CompletableFuture<Void>> completableFutures = interleavedSchedule.stream()
                            .map(carrier -> CompletableFuture.runAsync(() -> {
                                long start = System.currentTimeMillis();
                                final int services = carrier.getServices().size();
                                try {
                                    boolean acquired = false;
                                    try {
                                        if (services >= BIG_THRESHOLD) {
                                            bigGateCf.acquire();
                                            acquired = true;
                                        }
                                        routeCarrier(carrier, netBasedCosts, network, progress, sortedCarriers.size(), carrierType);
                                    } finally {
                                        if (acquired)
                                            bigGateCf.release();
                                        if (services < BIG_THRESHOLD) {
                                            int left = smallRemaining.decrementAndGet();
                                            if (left == 0 && bumpedLargeConcurrency.compareAndSet(false, true)) {
                                                int delta = Math.max(0,
                                                        desiredLargeConcurrency - initialLargeConcurrency);
                                                if (delta > 0) {
                                                    bigGateCf.release(delta);
                                                    LOGGER.info(
                                                            "All SMALL carriers done. Increased LARGE concurrency permits by {} -> now {}.",
                                                            delta, desiredLargeConcurrency);
                                                }
                                            }
                                        }
                                    }
                                } catch (Exception ex) {
                                    LOGGER.error("Routing failed for carrier {}: {}", carrier.getId(),
                                            ex.getMessage(), ex);
                                } finally {
                                    routedTimes.add(System.currentTimeMillis());
                                    int done = finishedCounter.incrementAndGet();
                                    if (services >= BIG_THRESHOLD) {
                                        largeRemaining.decrementAndGet();
                                    }
                                    double elapsed = (System.currentTimeMillis() - start) / 1000.0;
                                    String clazzDone = (services >= BIG_THRESHOLD) ? "LARGE" : "SMALL";
                                    LOGGER.info(
                                            "[UPDATE] Finished carrier {} class={} ({} / {}) in {}s -> remaining small={} large={}",
                                            carrier.getId(), clazzDone, done, sortedCarriers.size(),
                                            String.format(Locale.ROOT, "%.3f", elapsed),
                                            smallRemaining.get(), largeRemaining.get());
                                }
                            }, completableFutureExecutor))
                            .collect(Collectors.toList());

                    try {
                        // Ohne Timeout – einfach warten, bis alle fertig sind
                        CompletableFuture.allOf(completableFutures.toArray(new CompletableFuture[0]))
                                .get();
                    } catch (Exception e) {
                        LOGGER.error("Error during routing completion", e);
                    } finally {
                        completableFutureExecutor.shutdown();
                        try {
                            if (!completableFutureExecutor.awaitTermination(60, TimeUnit.SECONDS)) {
                                LOGGER.warn("Executor did not shut down gracefully – forcing...");
                                List<Runnable> dropped = completableFutureExecutor.shutdownNow();
                                LOGGER.warn("{} tasks were forcefully terminated.", dropped.size());
                            }
                        } catch (InterruptedException e) {
                            LOGGER.error("Thread interrupted during executor shutdown", e);
                            Thread.currentThread().interrupt();
                        }
                    }
                    break;
                case SINGLE_THREAD:
                    // Use single thread for sequential processing
                    interleavedSchedule.forEach(carrier -> {
                        long started = System.currentTimeMillis();
                        routeCarrier(carrier, netBasedCosts, network, progress, sortedCarriers.size(), carrierType);
                        routedTimes.add(System.currentTimeMillis());
                        boolean isSmall = carrier.getServices().size() < BIG_THRESHOLD;
                        if (isSmall) {
                            smallRemaining.decrementAndGet();
                        } else {
                            largeRemaining.decrementAndGet();
                        }
                        int done = finishedCounter.incrementAndGet();
                        double elapsed = (System.currentTimeMillis() - started) / 1000.0;
                        String clazzDone = isSmall ? "SMALL" : "LARGE";
                        LOGGER.info(
                                "[UPDATE] Finished carrier {} class={} ({} / {}) in {}s — remaining small={} large={}",
                                carrier.getId(), clazzDone, done, sortedCarriers.size(),
                                String.format(Locale.ROOT, "%.3f", elapsed),
                                smallRemaining.get(), largeRemaining.get());
                    });
                    break;
                case REACTOR:
                    // Use Reactor for parallel processing
                    final Semaphore bigGateRx = new Semaphore(initialLargeConcurrency);
                    Flux.fromIterable(interleavedSchedule)
                            .parallel()
                            .runOn(Schedulers.parallel())
                            .doOnNext(carrier -> {
                                long started = System.currentTimeMillis();
                                int services = carrier.getServices().size();
                                boolean acquired = false;
                                try {
                                    if (services >= BIG_THRESHOLD) {
                                        bigGateRx.acquire();
                                        acquired = true;
                                    }
                                    routeCarrier(carrier, netBasedCosts, network, progress, sortedCarriers.size(), carrierType);
                                } catch (InterruptedException ie) {
                                    Thread.currentThread().interrupt();
                                    LOGGER.error("Interrupted during gating", ie);
                                } finally {
                                    if (acquired)
                                        bigGateRx.release();
                                    if (services < BIG_THRESHOLD) {
                                        int left = smallRemaining.decrementAndGet();
                                        if (left == 0 && bumpedLargeConcurrency.compareAndSet(false, true)) {
                                            int delta = Math.max(0, desiredLargeConcurrency - initialLargeConcurrency);
                                            if (delta > 0) {
                                                bigGateRx.release(delta);
                                                LOGGER.info(
                                                        "All SMALL carriers done. Increased LARGE concurrency permits by {} -> now {}.",
                                                        delta, desiredLargeConcurrency);
                                            }
                                        }
                                    } else {
                                        largeRemaining.decrementAndGet();
                                    }
                                    int done = finishedCounter.incrementAndGet();
                                    double elapsed = (System.currentTimeMillis() - started) / 1000.0;
                                    String clazzDone = services >= BIG_THRESHOLD ? "LARGE" : "SMALL";
                                    LOGGER.info(
                                            "[UPDATE] Finished carrier {} class={} ({} / {}) in {}s — remaining small={} large={}",
                                            carrier.getId(), clazzDone, done, sortedCarriers.size(),
                                            String.format(Locale.ROOT, "%.3f", elapsed),
                                            smallRemaining.get(), largeRemaining.get());
                                    routedTimes.add(System.currentTimeMillis());
                                }
                            })
                            .sequential()
                            .blockLast();
                    break;
            }
        } catch (InterruptedException | ExecutionException e) {
            LOGGER.error("Error in parallel routing execution", e);
        }

        long endTime = System.currentTimeMillis();
        // Stop global status scheduler
        stopStatusScheduler();
        LOGGER.info("Finished routing ({} newly routed + {} cached) using {} in {} seconds.",
                carriersNeedingRouting.size(), cachedBefore, threadingType, (endTime - startTime) / 1000);

        // Plotting the runtime
        // HAGRIDRouterUtils.plotCumulativeRoutingRuntime(startTime, endTime,
        // routedTimes, threadingType.toString(), carrierType);
        // HAGRIDRouterUtils.plotIndividualRoutingRuntime(startTime, routedTimes,
        // threadingType.toString(), carrierType);
    }

    /**
     * Routes a single carrier.
     *
     * @param carrier       The carrier to be routed.
     * @param netBasedCosts The network-based transport costs.
     * @param network       The network.
     * @param progress      The current progress counter.
     * @param totalCarriers The total number of carriers.
     */
    private void routeCarrier(Carrier carrier, VRPTransportCosts netBasedCosts, Network network,
            AtomicInteger progress, int totalCarriers, String overrideCarrierType) {
        int services = carrier.getServices().size();
        int deliveriesTotal = getOrComputeTotalDeliveries(carrier);
        String clazz = services >= BIG_THRESHOLD ? "LARGE" : "SMALL";
        int idx = progress.incrementAndGet();
        LOGGER.info("[START] {} carrier {} ({}/{}) services={} deliveries={} class={}",
                clazz, carrier.getId(), idx, totalCarriers, services, deliveriesTotal, clazz);
        String provider = Optional.ofNullable(carrier.getAttributes().getAttribute("provider"))
                .map(Object::toString).orElse("unknown");
        String attrCarrierType = carrier.getAttributes().getAttribute("carrierType") == null ? "delivery"
                : carrier.getAttributes().getAttribute("carrierType").toString();
        String effectiveCarrierType = (overrideCarrierType != null && !overrideCarrierType.isBlank()) ? overrideCarrierType : attrCarrierType;
        if (carrierCacheEnabled) {
            try {
                if (tryRestoreCarrierPlanFromCache(carrier, services, clazz)) {
                    deliveriesTotal = getOrComputeTotalDeliveries(carrier); // ensure attribute
                    int shipments = carrier.getShipments().size();
                    int totalCapacityDemand = deliveriesTotal;
                    int b2b = carrier.getServices().values().stream()
                            .map(s -> s.getAttributes().getAttribute("b2b"))
                            .mapToInt(o -> (o instanceof Integer) ? (Integer) o : 0)
                            .sum();
                    int b2c = carrier.getServices().values().stream()
                            .map(s -> s.getAttributes().getAttribute("b2c"))
                            .mapToInt(o -> (o instanceof Integer) ? (Integer) o : 0)
                            .sum();
                    metrics.add(new CarrierRoutingMetrics(
                            carrier.getId().toString(), provider, services, shipments, totalCapacityDemand,
                            b2b, b2c, clazz, threadingType.toString(), effectiveCarrierType,
                            -1, -1, 0));
                    LOGGER.info("[CACHE-HIT] Using cached plan for carrier {} (services={} deliveries={})", carrier.getId(), services, deliveriesTotal);
                    return;
                }
            } catch (Exception e) {
                LOGGER.warn("Cache restore failed for carrier {}: {} -> continue without cache", carrier.getId(), e.toString());
            }
        }
        double start = System.currentTimeMillis();
        String workerThread = Thread.currentThread().getName();
        if (statusLogger != null) {
            statusLogger.appendStart(effectiveCarrierType, carrier.getId().toString(), provider,
                    services, clazz, threadingType.toString(), workerThread);
        }
        activeCarriers.put(carrier.getId().toString(), new ActiveCarrier(
                carrier.getId().toString(), provider, effectiveCarrierType, services, deliveriesTotal, clazz, (long) start, workerThread));
        Thread heartbeat = startHeartbeat(carrier.getId().toString(), provider, effectiveCarrierType,
                services, deliveriesTotal, clazz, (long) start, workerThread);
        int serviceCount = services;
        int jspritIterations = getJspritIterations();
        try {
            VehicleRoutingProblem vrp = HAGRIDRouterUtils.createRoutingProblem(carrier, network, netBasedCosts);
            VehicleRoutingAlgorithm algorithm = HAGRIDRouterUtils.configureAlgorithm(
                    vrp, serviceCount, jspritIterations, network, getUTurnPenaltyCost());
            AtomicInteger iterationCounter = new AtomicInteger(0);
            algorithm.addListener(
                    (IterationEndsListener) (iteration, problem, solutions) -> iterationCounter.incrementAndGet());
            algorithm.addListener(new IterationEndsListener() {
                @Override
                public void informIterationEnds(int iteration,
                        VehicleRoutingProblem problem,
                        Collection<VehicleRoutingProblemSolution> solutions) {
                    if (solutions.isEmpty()) {
                        LOGGER.warn("Carrier {}: Iteration {} no solution yet.", carrier.getId(), iteration);
                        return;
                    }
                    VehicleRoutingProblemSolution best = Solutions.bestOf(solutions);
                    LOGGER.info("Carrier {}: Iteration {} BestCost={} Routes={} Unassigned={} VehiclesUsed={} TransportTime={}s",
                            carrier.getId(),
                            iteration,
                            String.format("%.2f", best.getCost()),
                            best.getRoutes().size(),
                            best.getUnassignedJobs().size(),
                            best.getRoutes().stream().map(route -> route.getVehicle().getId()).distinct().count(),
                            getTotalTransportTime(best));
                }
            });
            long jspritStart = System.currentTimeMillis();
            VehicleRoutingProblemSolution solution = Solutions.bestOf(algorithm.searchSolutions());
            long jspritEnd = System.currentTimeMillis();
            LOGGER.info("JSPRIT solution for carrier {} found. Search took {}s (class={} deliveries={})",
                    carrier.getId(), (System.currentTimeMillis() - start) / 1000, clazz, deliveriesTotal);
            CarriersUtils.setJspritIterations(carrier, iterationCounter.get());
            CarrierPlan newPlan = MatsimJspritFactory.createPlan(solution);
            long routeStart = System.currentTimeMillis();
            NetworkRouter.routePlan(newPlan, netBasedCosts);
            long routeEnd = System.currentTimeMillis();
            carrier.addPlan(newPlan);
            carrier.setSelectedPlan(newPlan);
            double timeForPlanningAndRouting = (System.currentTimeMillis() - start) / 1000;
            CarriersUtils.setJspritComputationTime(carrier, timeForPlanningAndRouting);
            LOGGER.info("[END] {} carrier {} took {}s; services={} deliveries={}",
                    clazz, carrier.getId(), (System.currentTimeMillis() - start) / 1000, serviceCount, deliveriesTotal);
            if (heartbeat != null) { heartbeat.interrupt(); }
            int shipments = carrier.getShipments().size();
            int totalCapacityDemand = carrier.getServices().values().stream()
                    .mapToInt(CarrierService::getCapacityDemand).sum();
            int b2b = carrier.getServices().values().stream()
                    .map(s -> s.getAttributes().getAttribute("b2b"))
                    .mapToInt(o -> (o instanceof Integer) ? (Integer) o : 0)
                    .sum();
            int b2c = carrier.getServices().values().stream()
                    .map(s -> s.getAttributes().getAttribute("b2c"))
                    .mapToInt(o -> (o instanceof Integer) ? (Integer) o : 0)
                    .sum();
            double jspritSeconds = (jspritEnd - jspritStart) / 1000.0;
            double routeSeconds = (routeEnd - routeStart) / 1000.0;
            double totalSeconds = (System.currentTimeMillis() - start) / 1000.0;
            metrics.add(new CarrierRoutingMetrics(
                    carrier.getId().toString(), provider, services, shipments, totalCapacityDemand,
                    b2b, b2c, clazz, threadingType.toString(), effectiveCarrierType,
                    jspritSeconds, routeSeconds, totalSeconds));
            if (statusLogger != null) {
                statusLogger.appendDone(effectiveCarrierType, carrier.getId().toString(), provider,
                        services, clazz, threadingType.toString(), totalSeconds, workerThread);
            }
            activeCarriers.remove(carrier.getId().toString());
            // ---- Persist to cache AFTER successful routing ----
            if (carrierCacheEnabled) {
                try { persistCarrierToCache(carrier); } catch (Exception e) { LOGGER.warn("Cache persist failed {}: {}", carrier.getId(), e.toString()); }
            }
        } catch (Exception ex) {
            LOGGER.error("Routing failed for carrier {}: {} (services={} deliveries={})", carrier.getId(), ex.getMessage(), services, deliveriesTotal, ex);
            if (heartbeat != null) heartbeat.interrupt();
            if (statusLogger != null) {
                double elapsed = (System.currentTimeMillis() - start) / 1000.0;
                statusLogger.appendError(effectiveCarrierType, carrier.getId().toString(), provider,
                        services, clazz, threadingType.toString(), elapsed, workerThread, ex.toString());
            }
            activeCarriers.remove(carrier.getId().toString());
        }
    }

    private double getTotalTransportTime(VehicleRoutingProblemSolution solution) {
        return solution.getRoutes().stream()
                .mapToDouble(route -> route.getEnd().getArrTime() - route.getStart().getEndTime())
                .sum();
    }

    // Periodic status log while a thread is still routing a carrier (also logs to
    // status CSV)
    private Thread startHeartbeat(String carrierId, String provider, String carrierType,
            int services, int deliveries, String clazz, long startMs, String workerThreadName) {
        Thread t = new Thread(() -> {
            try {
                boolean longWarned = false;
                while (true) {
                    Thread.sleep(120_000);
                    double minutes = (System.currentTimeMillis() - startMs) / 60000.0;
                    String minStr = String.format(Locale.ROOT, "%.1f", minutes);
                    if (minutes >= 10.0) {
                        if (!longWarned) {
                            longWarned = true;
                            LOGGER.warn(
                                    "================= LONG-RUNNING ROUTE (>10m) =================\n[HEARTBEAT] carrier={} services={} deliveries={} class={} elapsed={} min thread={}\n===============================================================",
                                    carrierId, services, deliveries, clazz, minStr, workerThreadName);
                        } else {
                            LOGGER.warn("[HEARTBEAT 10m+] carrier={} services={} deliveries={} class={} elapsed={} min thread={}",
                                    carrierId, services, deliveries, clazz, minStr, workerThreadName);
                        }
                    } else {
                        LOGGER.info(
                                "[HEARTBEAT] Still routing carrier {} (services={} deliveries={} class={}) -> elapsed ~{} min on thread {}",
                                carrierId, services, deliveries, clazz, minStr, workerThreadName);
                    }
                    if (statusLogger != null) {
                        statusLogger.appendHeartbeat(carrierType, carrierId, provider, services, clazz,
                                threadingType.toString(), minutes * 60.0, workerThreadName);
                    }
                }
            } catch (InterruptedException ie) { }
        }, "Heartbeat-" + carrierId);
        t.setDaemon(true);
        t.start();
        return t;
    }

    // ===== Cache helper methods =====
    private boolean tryRestoreCarrierPlanFromCache(Carrier targetCarrier, int services, String clazz) {
        if (!carrierCacheEnabled || !CACHE_IO_AVAILABLE) return false;
        Path file = buildCacheFilePath(targetCarrier.getId().toString());
        if (file == null || !Files.exists(file)) return false;
        long t0 = System.currentTimeMillis();
        Carriers tmp = new Carriers();
        CarrierVehicleTypes vehicleTypesForRead = this.sharedVehicleTypes != null ? this.sharedVehicleTypes : new CarrierVehicleTypes();
        try {
            // Read single-carrier plan file
            new CarrierPlanXmlReader(tmp, vehicleTypesForRead).readFile(file.toString());
            Carrier cached = tmp.getCarriers().get(targetCarrier.getId());
            if (cached == null) return false;
            Object v = cached.getAttributes().getAttribute("cacheVersion");
            if (v == null || !CACHE_VERSION.equals(v.toString())) return false;

            // If we restore a plan from cache, we must also ensure that the services and vehicles are exactly as in the cached carrier.
            // Remove all existing services and vehicles, then add all from the cached carrier for full consistency.
            if (targetCarrier.getPlans().isEmpty()) {
                // Remove all existing services
                targetCarrier.getServices().clear();
                // Remove all existing vehicles
                targetCarrier.getCarrierCapabilities().getCarrierVehicles().clear();
                // Add all services from cache
                cached.getServices().forEach((id, service) -> CarriersUtils.addService(targetCarrier, service));
                // Add all vehicles from cache
                cached.getCarrierCapabilities().getCarrierVehicles().forEach((id, vehicle) -> targetCarrier.getCarrierCapabilities().getCarrierVehicles().put(id, vehicle));
                // Add plans
                cached.getPlans().forEach(targetCarrier::addPlan);
                if (cached.getSelectedPlan() != null) targetCarrier.setSelectedPlan(cached.getSelectedPlan());
            }
            // If plan was not loaded, do not touch services (keep as is)
            copyAttrIfExists(cached, targetCarrier, "jspritIterations");
            copyAttrIfExists(cached, targetCarrier, "jspritComputationTime");
            // compute and store deliveries
            int deliveriesTotal = getOrComputeTotalDeliveries(targetCarrier);
            double dt = (System.currentTimeMillis() - t0) / 1000.0;
            LOGGER.info("[CACHE-RESTORED] carrier={} services={} deliveries={} class={} time={}s", targetCarrier.getId(), services, deliveriesTotal, clazz, String.format(java.util.Locale.ROOT, "%.3f", dt));
            return true;
        } catch (Exception e) {
            LOGGER.warn("Cache read failed (CarrierPlanXmlReader) for {}: {}", targetCarrier.getId(), e.toString());
            return false;
        }
    }

    private void persistCarrierToCache(Carrier carrier) {
        if (!carrierCacheEnabled || !CACHE_IO_AVAILABLE) return;
        if (carrier.getSelectedPlan() == null) return;
        try {
            carrier.getAttributes().putAttribute("cacheVersion", CACHE_VERSION);
            getOrComputeTotalDeliveries(carrier);
            Carriers wrapper = new Carriers();
            wrapper.addCarrier(carrier);
            Path file = buildCacheFilePath(carrier.getId().toString());
            if (file == null) return;
            Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
            long t0 = System.currentTimeMillis();
            new CarrierPlanWriter(wrapper).write(tmp.toString()); // plain xml
            try {
                Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception e) {
                try { Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING); } catch (Exception ignored) {}
            }
            double dt = (System.currentTimeMillis() - t0) / 1000.0;
            LOGGER.info("[CACHE-WRITE] carrier={} file={} time={}s deliveries={} services={}", carrier.getId(), file.getFileName(), String.format(java.util.Locale.ROOT, "%.3f", dt), getOrComputeTotalDeliveries(carrier), carrier.getServices().size());
        } catch (Exception ex) {
            LOGGER.warn("Cache write failed (CarrierPlanWriter) for {}: {}", carrier.getId(), ex.toString());
        }
    }

    private Path buildCacheFilePath(String carrierId) {
        if (carrierCacheDir == null) return null;
        String runId = getRunIdSafe();
        return carrierCacheDir.resolve(CACHE_FILE_PREFIX + runId + "_" + carrierId + CACHE_FILE_SUFFIX);
    }
    private void copyAttrIfExists(Carrier src, Carrier dst, String key) { Object val = src.getAttributes().getAttribute(key); if (val != null) dst.getAttributes().putAttribute(key, val); }

    // Reflection helpers to avoid compile issues if config API differs
    private boolean invokeBoolean(Object target, String method, boolean def, boolean fallback) {
        try {
            return (boolean) target.getClass().getMethod(method).invoke(target);
        } catch (Exception e) {
            return fallback;
        }
    }

    private String invokeString(Object target, String method, String def, String fallback) {
        try {
            return (String) target.getClass().getMethod(method).invoke(target);
        } catch (Exception e) {
            return fallback;
        }
    }

    /**
     * Preload cached plans for carriers that have individual cache files.
     * Adds metrics for cache hits, so downstream reporting includes them.
     * @return number of carriers restored from cache
     */
    private int preloadCarrierPlansFromCache(Carriers carriers) {
        if (!carrierCacheEnabled || !CACHE_IO_AVAILABLE) return 0;
        int hits = 0;
        for (Carrier c : carriers.getCarriers().values()) {
            if (c.getSelectedPlan() != null) continue; // already has plan
            int services = c.getServices().size();
            String clazz = services >= BIG_THRESHOLD ? "LARGE" : "SMALL";
            if (tryRestoreCarrierPlanFromCache(c, services, clazz) && c.getSelectedPlan() != null) {
                // Only count restored plan; do NOT generate metrics here (metrics reflect runtime routing only)
                hits++;
            }
        }
        return hits;
    }

    private int getOrComputeTotalDeliveries(Carrier carrier) {
        Object v = carrier.getAttributes().getAttribute("totalDeliveries");
        if (v instanceof Integer) return (Integer) v;
        int sum = carrier.getServices().values().stream().mapToInt(CarrierService::getCapacityDemand).sum();
        carrier.getAttributes().putAttribute("totalDeliveries", sum);
        return sum;
    }

    // Helper to obtain runId from config (fallback to 'run')
    private String getRunIdSafe() {
        String sys = System.getProperty("hagrid.runId");
        if (sys != null && !sys.isBlank()) return sys;
        if (hagridConfigRef != null) {
            try {
                Object v = hagridConfigRef.getClass().getMethod("getRunId").invoke(hagridConfigRef);
                if (v != null) return v.toString();
            } catch (Exception ignored) {}
        }
        return "run";
    }
}
