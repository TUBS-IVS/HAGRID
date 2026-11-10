package hagrid.hannover.demand;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;
import org.matsim.api.core.v01.network.Network;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierPlan;
import org.matsim.freight.carriers.Carriers;
import org.matsim.freight.carriers.CarriersUtils;
import org.matsim.freight.carriers.jsprit.VRPTransportCosts;


import hagrid.core.routing.HAGRIDRouterUtils;
import hagrid.core.routing.CarrierOptimizationService;
import hagrid.core.routing.CarrierBatchExecutor;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.function.ToLongFunction;
import hagrid.core.routing.PlanValidator;
import hagrid.core.routing.CarrierRoutingMetrics;



import hagrid.core.routing.ThreadingType;
import hagrid.core.routing.CarrierRoutingStatusLogger;



import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.Optional;
import org.matsim.freight.carriers.CarrierService;
import java.util.Locale;
import hagrid.core.HagridConfig;
import org.matsim.freight.carriers.CarrierVehicleTypes;

/** Initial modal groups share one executor; the owning run closes it after Delivery and Supply. */
public class Router implements AutoCloseable {

    private static final Logger LOGGER = LogManager.getLogger(Router.class);

    // Classification threshold counts services and both Shipment activities.
    private static final int BIG_THRESHOLD = 350;

    // jsprit iteration count is tracked per-carrier via CarriersUtils; no constant
    // needed here

    private final ThreadingType threadingType;
    private final CarrierRoutingStatusLogger statusLogger;
    private final HagridConfig hagridConfigRef;
    private final int solverMaxRouteDurationSeconds;
    private final hagrid.core.config.ResolvedHagridConfig.Resources resources;

    public Router(ThreadingType threadingType) {
        this(threadingType, null, null, null);
    }

    public Router(ThreadingType threadingType, CarrierRoutingStatusLogger statusLogger) {
        this(threadingType, statusLogger, null, null);
    }

    /** Compatibility constructor; uncertified legacy initial caches are preserved and ignored. */
    public Router(ThreadingType threadingType, CarrierRoutingStatusLogger statusLogger, HagridConfig hagridConfig) {
        this(threadingType, statusLogger, hagridConfig, null);
    }

    public Router(ThreadingType threadingType, CarrierRoutingStatusLogger statusLogger,
                  HagridConfig hagridConfig, CarrierVehicleTypes vehicleTypes) {
        this(threadingType,statusLogger,hagridConfig,vehicleTypes,hagrid.core.routing.CarrierAdmissionPolicy.open(
                hagridConfig==null?Map.of():hagridConfig.getAdditionalConfigurationValues(),
                hagridConfig==null?hagrid.core.config.ResolvedHagridConfig.Resources.defaults():hagridConfig.resources().snapshot()));
    }

    private Router(ThreadingType threadingType, CarrierRoutingStatusLogger statusLogger, HagridConfig hagridConfig,
                   CarrierVehicleTypes vehicleTypes,hagrid.core.routing.CarrierAdmissionPolicy.Admission admission) {
        this(threadingType,statusLogger,hagridConfig,vehicleTypes,admission.executor(),admission.reservedBytes());
    }

    /** Owns the supplied executor; byte estimates/budget must be supplied by the run resource policy. */
    public Router(ThreadingType threadingType, CarrierRoutingStatusLogger statusLogger, HagridConfig hagridConfig,
                  CarrierVehicleTypes vehicleTypes, CarrierBatchExecutor batches, ToLongFunction<Carrier> reservedBytes) {
        this.threadingType = Objects.requireNonNull(threadingType);
        this.statusLogger = statusLogger;
        this.hagridConfigRef = hagridConfig;
        this.solverMaxRouteDurationSeconds = hagridConfig == null
                ? hagrid.core.config.RoutingDefaults.SOLVER_MAX_ROUTE_DURATION_SECONDS
                : hagridConfig.routing().getSolverMaxRouteDurationSeconds();
        this.resources = hagridConfig == null ? hagrid.core.config.ResolvedHagridConfig.Resources.defaults()
                : hagridConfig.resources().snapshot();
        this.batches = Objects.requireNonNull(batches);
        this.reservedBytes = Objects.requireNonNull(reservedBytes);
        reportUncertifiedLegacyCache();
    }
    private void reportUncertifiedLegacyCache() {
        // v1 identifies only a carrier name. Neither input, network nor travel-time provenance is certified.
        boolean requested = "true".equalsIgnoreCase(System.getProperty("hagrid.router.cache.enabled"))
                || System.getProperty("hagrid.router.cache.dir") != null
                || (hagridConfigRef != null && hagridConfigRef.isCarrierRoutingCacheEnabled());
        if (requested) LOGGER.warn("Legacy carrier cache v1 is ignored: current jobs, fleet and routing context cannot be certified. Existing files are preserved.");
    }
    /**
     * Gets initial improvement steps from HagridConfig or the construction-only default.
     */
    private int getJspritIterations() {
        if (hagridConfigRef != null) {
            try {
                return hagridConfigRef.routing().getJspritIterations();
            } catch (Exception e) {
                LOGGER.debug("Could not get jspritIterations from config, using default: {}", e.getMessage());
            }
        }
        return hagrid.core.config.RoutingDefaults.INITIAL_SOLVER_ITERATIONS;
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
    private final CarrierBatchExecutor batches;
    private final ToLongFunction<Carrier> reservedBytes;
    private final Object publication = new Object();
    private volatile boolean closed;
    private record Completed(Carrier carrier, CarrierOptimizationService.Result result,
                             CarrierRoutingMetrics metrics, int totalDeliveries) {}
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

    // Status reporting observes admitted work; solver concurrency belongs to CarrierBatchExecutor.

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
    public void routeCarriers(Carriers carriers, final VRPTransportCosts costs, Network network, String carrierType) {
        if(closed)throw new IllegalStateException("Initial router is closed");
        LOGGER.info("Initial routing label={}, execution=shared carrier executor",threadingType);
        long existing=validateExistingPlans(carriers,costs);
        var pending=carriers.getCarriers().values().stream().filter(c->c.getSelectedPlan()==null)
                .sorted(Comparator.comparingLong(Router::activityCount).reversed()
                        .thenComparing(c->c.getId().toString())).toList();
        if(pending.isEmpty())return;

        var inputs=new LinkedHashMap<String,Carrier>();
        var descriptors=new ArrayList<CarrierBatchExecutor.Descriptor>();
        for(var carrier:pending){
            var id=carrier.getId().toString();
            inputs.put(id,carrier);
            descriptors.add(new CarrierBatchExecutor.Descriptor(id,carrier.getServices().size(),
                    carrier.getShipments().size(),carrier.getCarrierCapabilities().getVehicleTypes().size(),
                    (hagridConfigRef == null ? HAGRIDRouterUtils.resolveSolverSeed() : HAGRIDRouterUtils.resolveSolverSeed(hagridConfigRef.randomSeeds().seed())),reservedBytes.applyAsLong(carrier)));
        }
        var completed=new LinkedHashMap<Carrier,Completed>();
        var progress=new AtomicInteger();
        long start=System.nanoTime();
        startStatusSchedulerIfNeeded();
        try{
            batches.runStaged(resources.maxActiveSolvers().orElse(Integer.MAX_VALUE),descriptors,d->routeCarrier(inputs.get(d.carrierId()),costs,progress,pending.size(),carrierType),
                    value->completed.put(value.carrier(),value),()->{
                synchronized(publication){
                    if(closed||Thread.currentThread().isInterrupted())
                        throw new CancellationException("Initial routing interrupted before publication");
                    if(completed.size()!=pending.size())throw new IllegalStateException("Incomplete initial group");
                    for(var carrier:pending)publish(completed.get(carrier));
                }
            });
            LOGGER.info("Initial routing complete: {} newly routed, {} existing checked, {} seconds",
                    pending.size(),existing,(System.nanoTime()-start)/1e9);
        }finally{
            stopStatusScheduler();
        }
    }

    private long validateExistingPlans(Carriers carriers,VRPTransportCosts costs){
        long checked=0;
        for(var carrier:carriers.getCarriers().values()){
            if(carrier.getSelectedPlan()==null)continue;
            var validation=PlanValidator.validate(carrier,carrier.getSelectedPlan(),costs,null,
                    solverMaxRouteDurationSeconds,getUTurnPenaltyCost());
            if(!validation.valid())throw new IllegalStateException("Invalid existing initial plan for "
                    +carrier.getId()+": "+validation.violations());
            checked++;
        }
        return checked;
    }

    private static long activityCount(Carrier carrier){
        return (long)carrier.getServices().size()+2L*carrier.getShipments().size();
    }

    /** Runs only after admission and returns detached data; canonical plans/metadata remain caller-owned. */
    private Completed routeCarrier(Carrier carrier,VRPTransportCosts costs,AtomicInteger progress,
                                   int total,String overrideCarrierType){
        int services=carrier.getServices().size();
        int deliveries=totalDeliveries(carrier);
        String sizeClass=activityCount(carrier)>=BIG_THRESHOLD?"LARGE":"SMALL";
        String provider=Optional.ofNullable(carrier.getAttributes().getAttribute("provider"))
                .map(Object::toString).orElse("unknown");
        String role=overrideCarrierType!=null&&!overrideCarrierType.isBlank()?overrideCarrierType
                :Optional.ofNullable(carrier.getAttributes().getAttribute("carrierType")).map(Object::toString).orElse("delivery");
        long start=System.currentTimeMillis();
        String worker=Thread.currentThread().getName();
        LOGGER.info("[START] Carrier {} ({}/{}) services={} shipments={} class={}",carrier.getId(),
                progress.incrementAndGet(),total,services,carrier.getShipments().size(),sizeClass);
        if(statusLogger!=null)statusLogger.appendStart(role,carrier.getId().toString(),provider,services,
                sizeClass,threadingType.toString(),worker);
        activeCarriers.put(carrier.getId().toString(),new ActiveCarrier(carrier.getId().toString(),provider,
                role,services,deliveries,sizeClass,start,worker));
        Thread heartbeat=startHeartbeat(carrier.getId().toString(),provider,role,services,deliveries,sizeClass,start,worker);
        try{
            var result=new CarrierOptimizationService().optimizeInitial(carrier,costs,getJspritIterations(),
                    solverMaxRouteDurationSeconds,getUTurnPenaltyCost(),resources,hagridConfigRef == null ? HAGRIDRouterUtils.resolveSolverSeed() : HAGRIDRouterUtils.resolveSolverSeed(hagridConfigRef.randomSeeds().seed()));
            if(!result.diagnostics().isEmpty())throw new IllegalStateException("Invalid initial result: "+result.diagnostics());
            double elapsed=(System.currentTimeMillis()-start)/1000.0;
            int demand=carrier.getServices().values().stream().mapToInt(CarrierService::getCapacityDemand).sum()
                    +carrier.getShipments().values().stream().mapToInt(org.matsim.freight.carriers.CarrierShipment::getSize).sum();
            var summary=new CarrierRoutingMetrics(carrier.getId().toString(),provider,services,carrier.getShipments().size(),
                    demand,parcelCount(carrier,"b2b"),parcelCount(carrier,"b2c"),sizeClass,threadingType.toString(),role,
                    result.metrics().jspritSeconds,result.metrics().routeSeconds,elapsed,result.metrics().execution);
            if(statusLogger!=null)statusLogger.appendDone(role,carrier.getId().toString(),provider,services,
                    sizeClass,threadingType.toString(),elapsed,worker);
            return new Completed(carrier,result,summary,deliveries);
        }catch(RuntimeException e){
            LOGGER.error("Initial search failed for {}",carrier.getId(),e);
            if(statusLogger!=null)statusLogger.appendError(role,carrier.getId().toString(),provider,services,
                    sizeClass,threadingType.toString(),(System.currentTimeMillis()-start)/1000.0,worker,e.toString());
            if(e instanceof CancellationException)throw e;
            throw new IllegalStateException("Initial search failed for "+carrier.getId(),e);
        }finally{
            heartbeat.interrupt();
            activeCarriers.remove(carrier.getId().toString());
        }
    }

    private static int parcelCount(Carrier carrier,String key){
        return carrier.getServices().values().stream().map(s->s.getAttributes().getAttribute(key))
                .mapToInt(value->value instanceof Integer count?count:0).sum();
    }

    private void publish(Completed value){
        var carrier=value.carrier();var result=value.result();
        carrier.addPlan(result.plan());carrier.setSelectedPlan(result.plan());
        CarriersUtils.setJspritIterations(carrier,result.iterations());
        CarriersUtils.setJspritComputationTime(carrier,result.metrics().totalSeconds);
        HAGRIDRouterUtils.recordUnassignedJobs(carrier,result.unassignedJobIds());
        HAGRIDRouterUtils.recordUTurnPenalty(carrier,getUTurnPenaltyCost());
        if(!(carrier.getAttributes().getAttribute("totalDeliveries") instanceof Integer))
            carrier.getAttributes().putAttribute("totalDeliveries",value.totalDeliveries());
        metrics.add(value.metrics());
    }

    @Override public void close(){
        synchronized(publication){closed=true;}
        batches.close();
        stopStatusScheduler();
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

    private int totalDeliveries(Carrier carrier) {
        Object v = carrier.getAttributes().getAttribute("totalDeliveries");
        if (v instanceof Integer) return (Integer) v;
        int sum = carrier.getServices().values().stream().mapToInt(CarrierService::getCapacityDemand).sum()
                +carrier.getShipments().values().stream().mapToInt(org.matsim.freight.carriers.CarrierShipment::getSize).sum();

        return sum;
    }

}
