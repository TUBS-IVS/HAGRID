package hagrid.core.routing;

import com.graphhopper.jsprit.core.problem.Location;
import com.graphhopper.jsprit.core.problem.driver.Driver;
import hagrid.core.routing.RoutingContext.VehicleProfile;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.*;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.router.speedy.SpeedyDijkstra;
import org.matsim.core.router.speedy.SpeedyGraphBuilder;
import org.matsim.core.router.util.*;
import org.matsim.vehicles.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import org.matsim.core.router.speedy.SpeedyGraph;

/** Context-owned modal graphs, bounded caches and worker lifetime; no global state. */
final class ModalRoutingEngine {
    private sealed interface CacheValue permits TransportValues,DistancePath {}
    record TransportValues(double timeSeconds,double distanceMeters,double monetaryCost) implements CacheValue {}
    record DistancePath(TravelTimeSnapshot.IndexedMode.TimedPath timing,double distanceMeters,double monetaryCost) implements CacheValue {}

    static void validateType(VehicleType type,VehicleProfile profile){
        if(type.hasNetworkMode() && !profile.mode().equals(type.getNetworkMode())) throw conflict(profile,"network mode");
        if(Double.compare(type.getMaximumVelocity(),profile.maximumVelocityMps())!=0) throw conflict(profile,"maximum velocity");
        var costs=type.getCostInformation();
        checkCost(costs.getFixedCosts(),profile.fixedCost(),profile,"fixed cost");
        checkCost(costs.getCostsPerMeter(),profile.costPerMeter(),profile,"meter cost");
        checkCost(costs.getCostsPerSecond(),profile.costPerSecond(),profile,"second cost");
    }
    private static void checkCost(Double actual,double expected,VehicleProfile profile,String field){
        // Native helper wrappers deliberately omit cost metadata; the registered frozen profile supplies it.
        if(actual!=null && Double.compare(actual,expected)!=0) throw conflict(profile,field);
    }
    private static IllegalArgumentException conflict(VehicleProfile profile,String field){
        return new IllegalArgumentException("Vehicle type "+profile.typeId()+" conflicts with frozen "+field);
    }

    private final RoutingContext context;
    private final java.util.function.BiFunction<Network,String,SpeedyGraph> graphBuilder;
    private final ConcurrentMap<String,Mode> modes=new ConcurrentHashMap<>();
    private final Map<Id<VehicleType>,Vehicle> vehicles=new HashMap<>();
    private final Map<String,VehicleProfile> neutral=new HashMap<>();
    private Map<Id<VehicleType>,VehicleProfile> profiles;
    private final Map<String,VehicleProfile> profilesByString=new HashMap<>();
    private final Map<String,com.graphhopper.jsprit.core.problem.vehicle.VehicleType> validatedTypes=new ConcurrentHashMap<>();
    private final Set<String> constantModes;
    final double horizonSeconds;
    private final Object cacheLock=new Object();
    private final LinkedHashMap<Key,CacheValue> cache=new LinkedHashMap<>(16,0.75f,true);
    private final Map<Key,CompletableFuture<CacheValue>> inFlight=new HashMap<>();
    private final LinkedHashMap<Key,String> unreachable=new LinkedHashMap<>(16,0.75f,true);
    final AtomicLong exactQueries=new AtomicLong();
    private final Semaphore admission;
    private final boolean cacheEnabled;
    private final int maximumEntries;
    private final AtomicLong hits=new AtomicLong(),computations=new AtomicLong(),evictions=new AtomicLong();
    private final AtomicInteger activeRouters=new AtomicInteger();
    private final AtomicLong roadRoutingCalls=new AtomicLong(),fullValidationCalls=new AtomicLong();
    void recordFullValidation(){fullValidationCalls.incrementAndGet();}
    enum Kind { EXACT, STATIC_DISTANCE }
    record Key(String from,String to,String mode,Id<VehicleType> profile,long timeBits,Kind kind) {}
    ModalRoutingEngine(RoutingContext context){this(context,SpeedyGraphBuilder::build);}
    /** Package-private preparation seam; production always uses the native Speedy builder. */
    ModalRoutingEngine(RoutingContext context,java.util.function.BiFunction<Network,String,SpeedyGraph> graphBuilder){
        this.context=context;this.graphBuilder=Objects.requireNonNull(graphBuilder);
        RoutingProfileCompatibility.requireSupported(context);
        profiles=context.vehicleProfiles();
        profiles.values().forEach(profile->profilesByString.put(profile.typeId().toString(),profile));
        constantModes=context.snapshot().modes().stream().filter(context.snapshot()::isConstant).collect(java.util.stream.Collectors.toUnmodifiableSet());
        horizonSeconds=context.snapshot().horizonSeconds();
        var limits=context.config().routing();
        cacheEnabled=limits.transportCacheEnabled();maximumEntries=limits.maximumCacheEntries();
        admission=new Semaphore(limits.maximumConcurrentRouters(),true);
        for(var profile:context.vehicleProfiles().values()) vehicles.put(profile.typeId(),adapter(profile));
        for(String mode:context.snapshot().modes()) {
            double maximum=context.vehicleProfiles().values().stream().filter(p->mode.equals(p.mode())).mapToDouble(VehicleProfile::maximumVelocityMps).max()
                    .orElse(mode.equals("bike") ? context.config().fleet().cargoBikeMaxSpeedMps() : Double.POSITIVE_INFINITY);
            var id=Id.create("hagrid-neutral-preparation:"+mode,VehicleType.class);
            if(context.vehicleProfiles().containsKey(id)) throw new IllegalArgumentException("Reserved preparation vehicle type ID "+id);
            boolean distanceOnly=context.vehicleProfiles().values().stream().filter(p->mode.equals(p.mode()))
                    .allMatch(RoutingProfileCompatibility::distanceOnly);
            var profile=new VehicleProfile(id,mode,maximum,0,distanceOnly?1:0,distanceOnly?0:1);
            neutral.put(mode,profile); vehicles.put(id,adapter(profile));

        }
    }
    VehicleProfile profile(Vehicle vehicle,String preparationMode){
        if(vehicle==null) return neutral.get(preparationMode);
        var profile=registered(vehicle.getType().getId()); validateType(vehicle.getType(),profile); return profile;
    }
    VehicleProfile profile(com.graphhopper.jsprit.core.problem.vehicle.Vehicle vehicle,String preparationMode){
        if(vehicle==null) return neutral.get(preparationMode);
        var type=vehicle.getType(); var profile=profilesByString.get(type.getTypeId());
        if(profile==null) throw new IllegalArgumentException("Unknown vehicle type "+type.getTypeId()+" in routing context");
        // Published 2.0 VehicleTypeImpl routing fields and VehicleCostParams are final primitives.
        // Exact class only: custom/mutable implementations always receive full validation.
        boolean immutable=type.getClass()==com.graphhopper.jsprit.core.problem.vehicle.VehicleTypeImpl.class;
        if(immutable && validatedTypes.get(type.getTypeId())==type) return profile;
        if(Double.compare(type.getMaxVelocity(),profile.maximumVelocityMps())!=0) throw conflict(profile,"maximum velocity");
        var costs=type.getVehicleCostParams();
        if(Double.compare(costs.fix,profile.fixedCost())!=0 || Double.compare(costs.perDistanceUnit,profile.costPerMeter())!=0
                || Double.compare(costs.perTransportTimeUnit,profile.costPerSecond())!=0) throw conflict(profile,"routing costs");
        if(immutable) validatedTypes.put(type.getTypeId(),type); // At most one reference per registered ID.
        return profile;
    }
    private VehicleProfile registered(Id<VehicleType> id){
        var profile=profiles.get(id);
        if(profile==null) throw new IllegalArgumentException("Unknown vehicle type "+id+" in routing context");
        return profile;
    }
    Key key(String from,String to,VehicleProfile profile,double time,Kind kind) {
        return new Key(from,to,profile.mode(),profile.typeId(),Double.doubleToLongBits(time),kind);
    }
    boolean isConstant(String mode){return constantModes.contains(mode);}
    boolean rawDistance(VehicleProfile profile){return !isConstant(profile.mode())&&RoutingProfileCompatibility.distanceOnly(profile);}
    double rasterSeconds(){double configured=context.config().routing().odTimeQuantizationSeconds();return configured>0?configured:1800;}
    boolean requiresExactTourFeasibility(){return profiles.values().stream().anyMatch(this::rawDistance);}
    TransportValues cached(Key key) {
        if(!cacheEnabled)return null;
        synchronized(cacheLock) {
            var found=(TransportValues)cache.get(key);if(found!=null)hits.incrementAndGet();return found;
        }
    }
    void validateTime(double time){
        if(!Double.isFinite(time) || time<0 || time>=horizonSeconds) throw new TravelTimeSnapshot.RangeException("Travel time outside captured range [0, "+horizonSeconds+"): "+time);
    }        VehicleProfile internalProfile(Vehicle vehicle){
        var profile=profiles.get(vehicle.getType().getId());
        if(profile!=null) return profile;
        var result=neutral.get(vehicle.getType().getNetworkMode());
        if(result==null || !result.typeId().equals(vehicle.getType().getId())) throw new IllegalArgumentException("Unknown internal routing vehicle");
        return result;
    }
    Mode mode(String mode){
        context.checkCancellation();context.snapshot().forMode(mode);
        // computeIfAbsent publishes only a fully prepared mode; failed construction leaves no entry.
        return modes.computeIfAbsent(mode,name->new Mode(RoutingNetworkSnapshot.forMode(context.network(),name),
                context.snapshot().forMode(name),this,name,context.config().routing().maximumConcurrentRouters()));
    }
    Vehicle vehicle(VehicleProfile profile){return vehicles.get(profile.typeId());}
    Link link(Id<Link> id,String mode){
        Link link=mode(mode).network.getLinks().get(id);
        if(link==null) throw new IllegalArgumentException("Link "+id+" is missing or forbidden for mode "+mode);
        return link;
    }
    private static Vehicle adapter(VehicleProfile profile){
        var type=VehicleUtils.createVehicleType(profile.typeId(),profile.mode()); type.setMaximumVelocity(profile.maximumVelocityMps());
        type.getCostInformation().setFixedCost(profile.fixedCost()).setCostsPerMeter(profile.costPerMeter()).setCostsPerSecond(profile.costPerSecond());
        return VehicleUtils.createVehicle(Id.createVehicleId("routing-profile:"+profile.typeId()),type);
    }
    TransportValues values(Key key,Supplier<TransportValues> calculate){
        if(cacheEnabled) synchronized(cacheLock) {
            var found=(TransportValues)cache.get(key);if(found!=null){hits.incrementAndGet();return found;}
            var noRoute=unreachable.get(key);if(noRoute!=null){hits.incrementAndGet();throw new NoRouteException(noRoute);}
        }
        enter();
        try {
            return (TransportValues)memoized(key,calculate);
        } finally {admission.release();}
    }
    /** The caller already owns routing admission; leaf path lookups must not reacquire it. */
    private CacheValue memoized(Key key,Supplier<? extends CacheValue> calculate) {
        context.checkCancellation();
        if(!cacheEnabled){computations.incrementAndGet();return calculate.get();}
        CompletableFuture<CacheValue> future; boolean owner;
        synchronized(cacheLock) {
            var found=cache.get(key);if(found!=null){hits.incrementAndGet();return found;}
            var noRoute=unreachable.get(key);if(noRoute!=null){hits.incrementAndGet();throw new NoRouteException(noRoute);}
            future=inFlight.get(key); owner=future==null;
            if(owner){future=new CompletableFuture<>();inFlight.put(key,future);}
            else hits.incrementAndGet();
        }
        if(!owner) {
            try{return future.join();}
            catch(CompletionException failure){
                if(failure.getCause() instanceof RuntimeException runtime) throw runtime;
                if(failure.getCause() instanceof Error error) throw error;
                throw failure;
            }
        }
        try {
            computations.incrementAndGet(); var result=calculate.get(); context.checkCancellation();
            synchronized(cacheLock) {
                cache.put(key,result);
                trimCache();
            }
            future.complete(result);return result;
        } catch(RuntimeException|Error failure){
            if(failure instanceof NoRouteException noRoute) synchronized(cacheLock) {
                unreachable.put(key,noRoute.getMessage());trimCache();
            }
            future.completeExceptionally(failure);throw failure;
        }
        finally {synchronized(cacheLock){inFlight.remove(key,future);}}
    }
    private void trimCache() {
        while(cache.size()+unreachable.size()>maximumEntries) {
            if(!unreachable.isEmpty())unreachable.remove(unreachable.keySet().iterator().next());
            else cache.remove(cache.keySet().iterator().next());
            evictions.incrementAndGet();
        }
    }
    private void enter(){
        try {admission.acquire();}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new CancellationException("Routing admission interrupted");}
    }
    LeastCostPathCalculator.Path roadRoute(VehicleProfile profile,Node from,Node to,double time,Person person){
        enter();
        try {context.checkCancellation();return mode(profile.mode()).route(from,to,time,person,vehicle(profile));}
        finally {admission.release();}
    }
    RoadLeg roadLeg(VehicleProfile profile,Id<Link> from,Id<Link> to,double departure) {
        enter();
        try { context.checkCancellation();roadRoutingCalls.incrementAndGet(); return mode(profile.mode()).routeLinks(link(from,profile.mode()),link(to,profile.mode()),departure,vehicle(profile)); }
        finally { admission.release(); }
    }
    /** Scalar native timing, separate from the public quantized OD estimates. */
    double exactTime(VehicleProfile profile,String from,String to,double departure) {
        enter();
        try {
            context.checkCancellation();roadRoutingCalls.incrementAndGet();
            return mode(profile.mode()).exactTime(link(Id.createLinkId(from),profile.mode()),link(Id.createLinkId(to),profile.mode()),departure,vehicle(profile));
        }finally {admission.release();}
    }

    TransportValues insertionLeg(VehicleProfile profile,String from,String to,double departure) {
        enter();
        try {
            context.checkCancellation();
            roadRoutingCalls.incrementAndGet();
            activeRouters.incrementAndGet();
            try {
                var mode=mode(profile.mode());
                var vehicle=vehicle(profile);
                var path=mode.distanceLeg(link(Id.createLinkId(from),profile.mode()),link(Id.createLinkId(to),profile.mode()),vehicle);
                double seconds=path.timing().travelSeconds(departure,profile.maximumVelocityMps());
                context.checkCancellation();
                return new TransportValues(seconds,path.distanceMeters(),path.monetaryCost());
            } finally {activeRouters.decrementAndGet();}
        } finally {admission.release();}
    }

    DistancePath distanceLeg(VehicleProfile profile,Id<Link> from,Id<Link> to) {
        enter();
        try {
            context.checkCancellation();activeRouters.incrementAndGet();
            try {
                var result=mode(profile.mode()).distanceLeg(link(from,profile.mode()),link(to,profile.mode()),vehicle(profile));
                context.checkCancellation();return result;
            } finally {activeRouters.decrementAndGet();}
        } finally {admission.release();}
    }
    RoutingContext.Statistics statistics(){
        synchronized(cacheLock) {
            return new RoutingContext.Statistics(cache.size()+unreachable.size(),hits.get(),computations.get(),evictions.get(),modes.size(),
                    modes.values().stream().mapToInt(Mode::createdRouters).sum(),activeRouters.get(),
                    exactQueries.get(),
                    roadRoutingCalls.get(),fullValidationCalls.get());
        }
    }
    void close(){
        synchronized(cacheLock){cache.clear();unreachable.clear();inFlight.clear();}
        modes.values().forEach(Mode::close);modes.clear();vehicles.clear();neutral.clear();
        validatedTypes.clear();profilesByString.clear();profiles=Map.of();
    }
    static final class Mode {
        final Network network; final TravelTime time;
        private final ModalRoutingEngine engine;
        private final SpeedyGraph graph;
        private final RestrictedLinkPathFinder restricted;
        private final RestrictedLinkPathFinder distancePaths;
        private final TravelTimeSnapshot.IndexedMode indexed;
        private final TravelDisutility costs;
        private final int maximumRouters;
        private final LinkedBlockingQueue<LeastCostPathCalculator> idle;
        private int created;
        Mode(Network network,TravelTime time,ModalRoutingEngine engine,String mode,int maximumRouters){
            this.network=network;this.time=time;this.engine=engine;this.maximumRouters=maximumRouters;
            indexed=engine.isConstant(mode)?null:engine.context.snapshot().new IndexedMode(mode);
            idle=new LinkedBlockingQueue<>(maximumRouters);
            costs=new TravelDisutility(){
                public double getLinkTravelDisutility(Link link,double entry,Person person,Vehicle vehicle){
                    var profile=engine.internalProfile(vehicle);
                    return link.getLength()*profile.costPerMeter()+time.getLinkTravelTime(link,entry,person,vehicle)*profile.costPerSecond();
                }
                public double getLinkMinimumTravelDisutility(Link link){return 0;}
            };
            restricted=TurnRestrictionValidator.hasRestrictions(network,mode)?new RestrictedLinkPathFinder(network,mode,time):null;
            // Static edge weights and zero search times make equal-distance ties independent of departure.
            // Only the selected path is replayed with the native captured times afterwards.
            distancePaths=new RestrictedLinkPathFinder(network,mode,(link,entry,person,vehicle)->0);
            graph=restricted==null?Objects.requireNonNull(engine.graphBuilder.apply(network,mode),"prepared routing graph"):null;
        }
        private LeastCostPathCalculator borrow(){
            synchronized(idle) {
                var worker=idle.poll();if(worker!=null)return worker;
                if(created<maximumRouters){var calculator=new SpeedyDijkstra(graph,time,costs);created++;return calculator;}
            }
            try {return idle.take();}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new CancellationException("Routing worker wait interrupted");}
        }
        /** Solver timing needs a scalar, not a fresh mutable NetworkRoute for every lookup. */
        double exactTime(Link from,Link to,double departure,Vehicle vehicle) {
            if(!engine.rawDistance(engine.internalProfile(vehicle)))
                return routeLinks(from,to,departure,vehicle).travelTimeSeconds();
            engine.activeRouters.incrementAndGet();
            try {
                var path=distanceLeg(from,to,vehicle);
                double seconds=path.timing().travelSeconds(departure,vehicle.getType().getMaximumVelocity());
                engine.context.checkCancellation();
                return seconds;
            }finally {engine.activeRouters.decrementAndGet();}
        }
        RoadLeg routeLinks(Link from,Link to,double departure,Vehicle vehicle) {
            engine.context.checkCancellation();
            if(engine.rawDistance(engine.internalProfile(vehicle))) {
                engine.activeRouters.incrementAndGet();
                try {
                    var path=distanceLeg(from,to,vehicle);
                    double seconds=path.timing().travelSeconds(departure,vehicle.getType().getMaximumVelocity());
                    engine.context.checkCancellation();
                    return RestrictedLinkPathFinder.road(from.getId(),to.getId(),path.timing().intermediateLinks(),seconds,
                            path.distanceMeters(),path.monetaryCost(),vehicle);
                } finally {engine.activeRouters.decrementAndGet();}
            }
            if (restricted != null) {
                engine.activeRouters.incrementAndGet();
                try { return restricted.route(from.getId(),to.getId(),departure,vehicle); }
                finally { engine.activeRouters.decrementAndGet(); }
            }
            if (from.getId().equals(to.getId())) return RestrictedLinkPathFinder.road(from.getId(),to.getId(),List.of(),0,0,0,vehicle);
            var worker=borrow();engine.activeRouters.incrementAndGet();
            try {
                var path=((SpeedyDijkstra)worker).calcLeastCostPath(from,to,departure,null,vehicle);
                if(path==null)throw new NoRouteException("No " + vehicle.getType().getNetworkMode() + " path from " + from.getId() + " to " + to.getId());
                double target=time.getLinkTravelTime(to,departure+path.travelTime,null,vehicle);
                var rates=vehicle.getType().getCostInformation();
                double distance=to.getLength()+path.links.stream().mapToDouble(Link::getLength).sum();
                var result=RestrictedLinkPathFinder.road(from.getId(),to.getId(),path.links.stream().map(Link::getId).toList(),
                        path.travelTime+target,distance,path.travelCost+to.getLength()*rates.getCostsPerMeter()+target*rates.getCostsPerSecond(),vehicle);
                engine.context.checkCancellation();return result;
            } finally { engine.activeRouters.decrementAndGet();if(!idle.offer(worker))throw new IllegalStateException("Routing worker returned twice"); }
        }
        DistancePath distanceLeg(Link from,Link to,Vehicle vehicle) {
            var key=engine.key(from.getId().toString(),to.getId().toString(),engine.internalProfile(vehicle),0,Kind.STATIC_DISTANCE);
            return (DistancePath)engine.memoized(key,()->{
                var path=distancePaths.route(from.getId(),to.getId(),0,vehicle);
                return new DistancePath(indexed.new TimedPath(path.route().getLinkIds(),from.getId(),to.getId()),path.distanceMeters(),path.monetaryCost());
            });
        }
        private record NodeLabel(Node node,double distance,NodeLabel previous,Link incoming) {}
        private LeastCostPathCalculator.Path distanceNodeRoute(Node from,Node to,double departure,Vehicle vehicle) {
            var queue=new PriorityQueue<NodeLabel>(Comparator.comparingDouble(NodeLabel::distance).thenComparing(l->l.node().getId().toString()));
            var best=new HashMap<Id<Node>,NodeLabel>();var initial=new NodeLabel(from,0,null,null);
            queue.add(initial);best.put(from.getId(),initial);
            while(!queue.isEmpty()) {
                engine.context.checkCancellation();var label=queue.remove();if(best.get(label.node().getId())!=label)continue;
                if(label.node().getId().equals(to.getId())) {
                    var links=new ArrayList<Link>();var nodes=new ArrayList<Node>();
                    for(var step=label;step!=null;step=step.previous()) {nodes.add(step.node());if(step.incoming()!=null)links.add(step.incoming());}
                    Collections.reverse(links);Collections.reverse(nodes);double entry=departure;
                    for(var link:links)entry+=time.getLinkTravelTime(link,entry,null,vehicle);
                    if(entry>engine.horizonSeconds)throw new TravelTimeSnapshot.RangeException("Node path arrival exceeds captured horizon: "+entry);
                    return new LeastCostPathCalculator.Path(nodes,links,entry-departure,label.distance()*vehicle.getType().getCostInformation().getCostsPerMeter());
                }
                for(var link:label.node().getOutLinks().values().stream().sorted(Comparator.comparing(l->l.getId().toString())).toList()) {
                    double distance=label.distance()+link.getLength();var prior=best.get(link.getToNode().getId());
                    if(prior==null||distance<prior.distance()) {
                        var next=new NodeLabel(link.getToNode(),distance,label,link);best.put(next.node().getId(),next);queue.add(next);
                    }
                }
            }
            return null;
        }
        LeastCostPathCalculator.Path route(Node from,Node to,double departure,Person person,Vehicle vehicle){
            if(restricted!=null)throw new IllegalArgumentException("Turn-restricted routing requires LinkRouteProvider, not node endpoints");
            engine.context.checkCancellation();
            Node origin=network.getNodes().get(from.getId()),destination=network.getNodes().get(to.getId());
            if(origin==null || destination==null) throw new IllegalArgumentException("Routing nodes are missing from the frozen network");
            if(!origin.getCoord().equals(from.getCoord()) || !destination.getCoord().equals(to.getCoord()))
                throw new IllegalArgumentException("Routing node ID conflicts with frozen coordinates");
            if(engine.rawDistance(engine.internalProfile(vehicle))) {
                engine.activeRouters.incrementAndGet();
                try{return distanceNodeRoute(origin,destination,departure,vehicle);}
                finally{engine.activeRouters.decrementAndGet();}
            }
            var worker=borrow();engine.activeRouters.incrementAndGet();
            try {
                engine.context.checkCancellation();
                var result=worker.calcLeastCostPath(origin,destination,departure,person,vehicle);
                engine.context.checkCancellation();return result;
            } finally {
                engine.activeRouters.decrementAndGet();
                if(!idle.offer(worker)) throw new IllegalStateException("Routing worker returned twice");
            }
        }
        int createdRouters(){synchronized(idle){return created;}}
        void close(){synchronized(idle){idle.clear();created=0;}}
    }
}
