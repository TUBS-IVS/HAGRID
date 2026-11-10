package hagrid.core.simulation;

import hagrid.core.config.ResolvedHagridConfig;
import hagrid.core.routing.*;
import org.matsim.freight.carriers.*;
import java.util.*;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** One deterministic pass. Complete groups are validated before controller-thread publication. */
public final class ReplanningCoordinator implements AutoCloseable {
    public enum Status {PENDING,RUNNING,COMPLETED,FAILED,CANCELLED}
    private final ResolvedHagridConfig config;
    private final long seed;
    private final OptimizationResultStore resultStore;
    private final CarrierBatchExecutor batches;
    private final ToLongFunction<Carrier> reservedBytes;
    private final CarrierOptimizationService service=new CarrierOptimizationService();
    private final Map<String,Status> statuses=new LinkedHashMap<>();
    private final Map<String,CarrierRoutingMetrics> completedMetrics=new LinkedHashMap<>();
    private List<Carrier> ordered;
    private RoutingContext context;
    private final List<Origin> origins=new ArrayList<>();
    private final Map<Carrier,Origin> nativeOrigins=new LinkedHashMap<>();
    private final Set<Path> nativeArchives=new LinkedHashSet<>();
    private final Set<Path> ownedArchives=new LinkedHashSet<>();
    private Path archiveDirectory;
    private final SharedRoutingContextArchives archives=new SharedRoutingContextArchives();
    private int nextIteration;
    private Thread owner;
    private int cacheHits;
    private boolean failed;
    private volatile boolean closed;
    private final Object publication=new Object();
    private record Prepared(Carrier carrier,CarrierPlan plan,CarrierOptimizationService.Result result,
                            double cost,boolean reused,CarrierOptimizationService.Publication publication){}
    private record Origin(int planningIteration,String fingerprint,Path archive,List<Carrier> carriers){}

    public ReplanningCoordinator(ResolvedHagridConfig config,long seed){
        this(config,seed,config.results().carrierPlanCacheEnabled()
                ? config.results().carrierPlanCacheDirectory().map(path->new OptimizationResultStore(
                    java.nio.file.Path.of(path).resolve("validated-replanning-v1"))).orElse(null) : null);
    }
    public ReplanningCoordinator(ResolvedHagridConfig config,long seed,OptimizationResultStore resultStore){
        this(config,seed,resultStore,CarrierAdmissionPolicy.open(config.effectiveValues(),config.resources()));
    }
    private ReplanningCoordinator(ResolvedHagridConfig config,long seed,OptimizationResultStore resultStore,
                                  CarrierAdmissionPolicy.Admission admission){
        this(config,seed,resultStore,admission.executor(),admission.reservedBytes());
    }
    /**
     * Owns the supplied executor. Parallel admission requires a run-specific byte budget and
     * reservation estimator; the compatibility constructors retain serial execution until calibrated.
     */
    public ReplanningCoordinator(ResolvedHagridConfig config,long seed,OptimizationResultStore resultStore,
                                 CarrierBatchExecutor batches,ToLongFunction<Carrier> reservedBytes){
        this.config=Objects.requireNonNull(config);this.seed=seed;this.resultStore=resultStore;
        this.batches=Objects.requireNonNull(batches);this.reservedBytes=Objects.requireNonNull(reservedBytes);
        config.optimization().replanningIterations().orElseThrow();
        config.optimization().replanningStartIteration().orElseThrow();
        config.optimization().carriersPerGroup().orElseThrow();
        nextIteration=config.optimization().replanningStartIteration().orElseThrow();
    }

    public int cacheHits(){return cacheHits;}
    public Map<String,Status> statuses(){return Collections.unmodifiableMap(new LinkedHashMap<>(statuses));}

    public void runGroup(int iteration,Carriers carriers,Supplier<RoutingContext> groupContext){
        if(closed)throw new IllegalStateException("Replanning coordinator is closed");
        if(failed)throw new IllegalStateException("Replanning coordinator has failed; no further publication");
        int start=config.optimization().replanningStartIteration().orElseThrow();
        if(iteration<start)return;
        requireControllerThread();
        initializeOrder(carriers);
        var group=ordered.stream().filter(c->statuses.get(c.getId().toString())==Status.PENDING)
                .limit(config.optimization().carriersPerGroup().orElseThrow()).toList();
        if(group.isEmpty())return;
        if(iteration!=nextIteration)
            throw new IllegalStateException("Expected replanning iteration "+nextIteration+", got "+iteration);
        try{
            if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("Replanning interrupted before capture");
            var archive=captureContext(groupContext,iteration);
            var inputs=new LinkedHashMap<String,Carrier>();
            var descriptors=new ArrayList<CarrierBatchExecutor.Descriptor>();
            for(var carrier:group){
                var id=carrier.getId().toString();
                inputs.put(id,carrier);
                descriptors.add(new CarrierBatchExecutor.Descriptor(id,carrier.getServices().size(),
                        carrier.getShipments().size(),carrier.getCarrierCapabilities().getVehicleTypes().size(),
                        seed^id.hashCode(),reservedBytes.applyAsLong(carrier)));
            }
            // RUNNING describes the group's lifecycle, not how many solver workers are active.
            group.forEach(c->statuses.put(c.getId().toString(),Status.RUNNING));
            var prepared=new ArrayList<Prepared>();
            batches.runStaged(config.resources().maxActiveSolvers().orElse(Integer.MAX_VALUE),
                    descriptors,d->prepareCarrier(inputs.get(d.carrierId()),d.seed()),value->{
                prepared.add(value);
                if(value.reused())cacheHits++;
            },()->{
                synchronized(publication){
                    if(closed||Thread.currentThread().isInterrupted())
                        throw new java.util.concurrent.CancellationException("Replanning interrupted before publication");
                    // Cheap certificates are checked for the entire batch before its first publication.
                    for(var value:prepared)value.publication().requireCurrent(value.carrier(),
                            new RoutingRequest(value.carrier(),context,CarrierModeResolver.resolve(value.carrier()).orElseThrow()),config);
                    for(var value:prepared)publish(value);
                    origins.add(new Origin(iteration,context.fingerprint(),archive,List.copyOf(group)));
                    nextIteration=iteration+1;
                }
            });
        }catch(RuntimeException|Error e){
            failed=true;
            var terminal=e instanceof java.util.concurrent.CancellationException?Status.CANCELLED:Status.FAILED;
            group.forEach(c->statuses.put(c.getId().toString(),terminal));
            throw e;
        }
    }

    private void requireControllerThread(){
        if(owner==null)owner=Thread.currentThread();
        if(owner!=Thread.currentThread())throw new IllegalStateException("Replanning must publish on its controller thread");
    }

    /** Native changes share this iteration's completed preceding measurement, including after jsprit. */
    void evolve(int iteration,Map<Carrier,NativeCarrierPlanEvolution.Choice> choices,Supplier<RoutingContext> previous){
        requireControllerThread();
        synchronized(publication){
            if(closed||failed)throw new IllegalStateException("Replanning coordinator is unavailable");
            var pending=new LinkedHashMap<>(choices);
            for(var origin:origins){
                // Drive only this iteration's newly certified grouped results before native alternatives.
                if(iteration==origin.planningIteration())origin.carriers().forEach(pending::remove);
            }
            if(pending.isEmpty())return;
            if(context!=null && context.snapshot().measurementIteration()==iteration-1){
                applyNative(iteration,pending,context);
            }else{
                // Completed searches are archived already. Release their large stale graph/profile
                // before capturing a fresh native context; the searches themselves keep their origin.
                if(context!=null){context.close();context=null;}
                try(var completed=Objects.requireNonNull(previous.get(),"Missing completed native routing context")){
                    if(completed.snapshot().measurementIteration()!=iteration-1)
                        throw new IllegalStateException("Native replanning requires the preceding measurement");
                    if(!completed.config().equals(config))
                        throw new IllegalArgumentException("Native replanning context configuration mismatch");
                    applyNative(iteration,pending,completed);
                }
            }
        }
    }
    private void applyNative(int iteration,Map<Carrier,NativeCarrierPlanEvolution.Choice> choices,RoutingContext completed){
        var changed=new ArrayList<Carrier>();
        choices.forEach((carrier,choice)->{
            var before=carrier.getSelectedPlan();
            NativeCarrierPlanEvolution.apply(carrier,choice,completed);
            if(carrier.getSelectedPlan()!=before){
                var attributes=carrier.getSelectedPlan().getAttributes();
                attributes.putAttribute("nativeRoutingMeasurementIteration",completed.snapshot().measurementIteration());
                attributes.putAttribute("nativeRoutingContextFingerprint",completed.fingerprint());
                if(completedMetrics.containsKey(carrier.getId().toString()))changed.add(carrier);
            }
        });
        if(changed.isEmpty())return;
        try{
            // A same-iteration group capture may already own this exact archive.
            Path archive=origins.stream().filter(o->o.fingerprint().equals(completed.fingerprint()))
                    .map(Origin::archive).findFirst().orElse(null);
            if(archive==null){
                archive=archives.save(archiveDirectory,completed);
                if(ownedArchives.add(archive))nativeArchives.add(archive);
            }
            var origin=new Origin(iteration,completed.fingerprint(),archive,List.copyOf(changed));
            changed.forEach(carrier->nativeOrigins.put(carrier,origin));
            var referenced=new HashSet<Path>();
            nativeOrigins.values().forEach(o->referenced.add(o.archive()));
            for(var old:List.copyOf(nativeArchives))if(!referenced.contains(old)){
                ReplayContextArchive.deleteArchive(old);nativeArchives.remove(old);ownedArchives.remove(old);
            }
        }catch(IOException e){throw new UncheckedIOException("Cannot retain accepted native routing context",e);}
    }
    private void initializeOrder(Carriers carriers){
        if(ordered==null){
            ordered=carriers.getCarriers().values().stream().sorted(Comparator.comparing(c->c.getId().toString())).toList();
            ordered.forEach(c->statuses.put(c.getId().toString(),
                    c.getServices().isEmpty()&&c.getShipments().isEmpty()?Status.COMPLETED:Status.PENDING));
        }
        if(carriers.getCarriers().size()!=ordered.size()
                ||ordered.stream().anyMatch(c->carriers.getCarriers().get(c.getId())!=c))
            throw new IllegalStateException("Carrier registry changed during the fixed replanning pass");
    }
    private Path captureContext(Supplier<RoutingContext> supplier,int iteration){
        // No prior batch remains after runStaged: actual worker exit, not Future completion,
        // is the replacement boundary. close additionally drains any outstanding query leases.
        synchronized(publication){
            if(closed)throw new java.util.concurrent.CancellationException("Replanning coordinator is closed");
            RoutingContext candidate=null;
            try{
                candidate=Objects.requireNonNull(supplier.get(),"Missing completed previous-iteration routing context");
                if(candidate.snapshot().measurementIteration()!=iteration-1)
                    throw new IllegalArgumentException("Replanning requires previous iteration "+(iteration-1)+" snapshot");
                if(!candidate.config().equals(config))throw new IllegalArgumentException("Replanning context configuration mismatch");
                if(candidate==context)throw new IllegalArgumentException("Each replanning group requires a fresh context");
                if(archiveDirectory==null)archiveDirectory=Files.createTempDirectory("hagrid-replanning-contexts-");
                Path archive=archives.save(archiveDirectory,candidate);
                ownedArchives.add(archive);
                if(context!=null)context.close();
                context=candidate;
                return archive;
            }catch(IOException e){
                if(candidate!=null&&candidate!=context)candidate.close();
                throw new UncheckedIOException("Cannot archive previous-iteration routing context",e);
            }catch(RuntimeException|Error e){
                if(candidate!=null&&candidate!=context)candidate.close();
                throw e;
            }
        }
    }
    /** Runs after admission; all solver, conversion, replay and final checks belong to this reservation. */
    private Prepared prepareCarrier(Carrier carrier,long carrierSeed){
        var request=new RoutingRequest(carrier,context,CarrierModeResolver.resolve(carrier).orElseThrow());
        var result=resultStore==null?null:resultStore.load(carrier,request,config,carrierSeed).orElse(null);
        boolean reused=result!=null;
        if(!reused)result=service.optimize(carrier,request,config,carrierSeed,
                config.optimization().replanningIterations().orElseThrow());
        var selection=PlanSelection.choose(carrier.getSelectedPlan(),result,carrier,request,config);
        if(!selection.completed())throw new IllegalStateException("Invalid result for "+carrier.getId()+": "
                +result.diagnostics()+selection.candidate().violations());
        var publication=CarrierOptimizationService.prepareForPublication(carrier,selection,result,request,config);
        var plan=publication.plan();var validation=publication.validation();
        if(resultStore!=null&&!reused){
            try{resultStore.save(carrier,result,request,config,carrierSeed);}
            catch(RuntimeException e){
                org.apache.logging.log4j.LogManager.getLogger(ReplanningCoordinator.class)
                        .warn("Validated search for {} could not be cached: {}",carrier.getId(),e.toString());
            }
        }
        return new Prepared(carrier,plan,result,validation.finalCost(),reused,publication);
    }
    private void publish(Prepared value){
        value.carrier().addPlan(value.plan());value.carrier().setSelectedPlan(value.plan());
        CarriersUtils.setJspritIterations(value.carrier(),value.result().iterations());
        if(!value.reused())CarriersUtils.setJspritComputationTime(value.carrier(),value.result().metrics().totalSeconds);
        completedMetrics.put(value.carrier().getId().toString(),value.result().metrics());
        var attributes=value.carrier().getAttributes();
        attributes.putAttribute("routingContextFingerprint",context.fingerprint());
        attributes.putAttribute("routingMeasurementIteration",context.snapshot().measurementIteration());
        attributes.putAttribute("routingPlanningIteration",context.snapshot().measurementIteration()+1);
        attributes.putAttribute("validatedRoutingCost",value.cost());attributes.putAttribute("hadFirstReplanning",true);
        statuses.put(value.carrier().getId().toString(),Status.COMPLETED);
    }
    /** Recheck each end selection under its last accepted routing profile; search metrics retain their group origin. */
    public List<CarrierRoutingMetrics> finalMetrics(){
        requireControllerThread();
        synchronized(publication){
            if(closed)throw new IllegalStateException("Replanning coordinator is closed");
            var metrics=new ArrayList<CarrierRoutingMetrics>();
            var selectedOrigins=new LinkedHashMap<Path,List<Carrier>>();
            var profiles=new LinkedHashMap<Path,Origin>();
            for(var group:origins)for(var carrier:group.carriers()){
                var origin=nativeOrigins.getOrDefault(carrier,group);
                profiles.putIfAbsent(origin.archive(),origin);
                selectedOrigins.computeIfAbsent(origin.archive(),unused->new ArrayList<>()).add(carrier);
            }
            selectedOrigins.forEach((archive,carriers)->withArchived(profiles.get(archive),profile->appendMetrics(metrics,carriers,profile)));
            return metrics.stream().sorted(Comparator.comparing(m->m.carrierId)).toList();
        }
    }
    private void withArchived(Origin origin,java.util.function.Consumer<RoutingContext> action){
        if(context!=null&&context.fingerprint().equals(origin.fingerprint())){
            action.accept(context);
        }else try(var restored=archives.read(origin.archive())){
            if(!restored.fingerprint().equals(origin.fingerprint()))
                throw new IllegalStateException("Archived replanning context fingerprint mismatch");
            action.accept(restored);
        }catch(IOException e){throw new UncheckedIOException("Cannot restore selected replanning context",e);}
    }
    private void appendMetrics(List<CarrierRoutingMetrics> metrics,List<Carrier> carriers,RoutingContext profile){
        for(var carrier:carriers){
            var observed=completedMetrics.get(carrier.getId().toString());
            var selected=carrier.getSelectedPlan();
            var request=new RoutingRequest(carrier,profile,CarrierModeResolver.resolve(carrier).orElseThrow());
            var validation=PlanValidator.validateNativeExecution(carrier,selected,new ModalTransportCosts(request),config);
            metrics.add(observed.withFinalSelection(validation.valid(),validation.finalCost(),selected==null?null:selected.getScore()));
        }
    }

    public void close(){
        synchronized(publication){closed=true;}
        // Never close a shared routing context while an admitted worker still uses it.
        batches.close();
        synchronized(publication){
            if(context!=null)context.close();
            archives.close();
            try{
                for(var archive:ownedArchives)ReplayContextArchive.deleteArchive(archive);
                archives.deleteOwnedGraphs();
                if(archiveDirectory!=null){
                    Files.deleteIfExists(archiveDirectory.resolve(".graphs"));
                    Files.deleteIfExists(archiveDirectory.resolve(".contexts"));
                    Files.deleteIfExists(archiveDirectory);
                }
            }catch(IOException e){throw new UncheckedIOException("Cannot remove own replanning context archives",e);}
        }
    }
}
