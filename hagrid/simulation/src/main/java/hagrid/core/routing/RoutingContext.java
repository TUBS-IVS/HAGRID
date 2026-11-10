package hagrid.core.routing;

import hagrid.core.config.ResolvedHagridConfig;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Network;
import org.matsim.freight.carriers.CarrierVehicleTypes;
import org.matsim.vehicles.VehicleType;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Owns one frozen routing graph and immutable routing profiles, shared across carrier requests. */
public final class RoutingContext implements AutoCloseable {
    public record VehicleProfile(Id<VehicleType> typeId, String mode, double maximumVelocityMps,
                                 double fixedCost, double costPerMeter, double costPerSecond) {}
    private final ResolvedHagridConfig config;
    private final String fingerprint;
    private Map<Id<VehicleType>,VehicleProfile> profiles;
    private RoutingNetworkSnapshot network;
    private TravelTimeSnapshot snapshot;
    private volatile boolean closed,closing;
    private final ReentrantReadWriteLock lifecycle=new ReentrantReadWriteLock(true);
    private final Object closeMonitor=new Object();
    private ModalRoutingEngine modalRouting;

    private RoutingContext(Network source,CarrierVehicleTypes types,TravelTimeSnapshot snapshot,ResolvedHagridConfig config) {
        this.config=Objects.requireNonNull(config);
        this.snapshot=Objects.requireNonNull(snapshot);
        Objects.requireNonNull(source);
        var profiles=new LinkedHashMap<Id<VehicleType>,VehicleProfile>();
        for(var entry:Objects.requireNonNull(types).getVehicleTypes().entrySet()) {
            VehicleType type=entry.getValue();
            if(type==null || !entry.getKey().equals(type.getId())) throw new IllegalArgumentException("Inconsistent vehicle type registry key "+entry.getKey());
            if(!type.hasNetworkMode() || type.getNetworkMode().isBlank()) throw new IllegalArgumentException("Vehicle type "+type.getId()+" needs an explicit network mode");
            snapshot.forMode(type.getNetworkMode());
            double velocity=type.getMaximumVelocity();
            if(Double.isNaN(velocity) || velocity<=0) throw new IllegalArgumentException("Invalid maximum velocity for type "+type.getId());
            var costs=type.getCostInformation();
            double fixed=cost(costs.getFixedCosts(),type,"fixed");
            double meter=cost(costs.getCostsPerMeter(),type,"per meter");
            double second=cost(costs.getCostsPerSecond(),type,"per second");
            profiles.put(type.getId(),new VehicleProfile(type.getId(),type.getNetworkMode(),velocity,fixed,meter,second));
        }
        this.profiles=Map.copyOf(profiles);
        network=source instanceof RoutingNetworkSnapshot frozen?frozen:new RoutingNetworkSnapshot(source);
        snapshot.validateNetwork(network);
        fingerprint=RoutingData.hashValues("hagrid-routing-context-v1",network.fingerprint(),snapshot.fingerprint(),this.profiles,
                config.run(),config.scenario(),config.demand(),config.fleet(),config.tourRules(),
                config.routing(),config.optimization(),config.resources(),config.results());
    }
    private static double cost(Double value,VehicleType type,String name) {
        if(value==null || !Double.isFinite(value) || value<0) throw new IllegalArgumentException("Invalid "+name+" routing cost for vehicle type "+type.getId());
        return value;
    }
    public static RoutingContext create(Network network,CarrierVehicleTypes types,TravelTimeSnapshot snapshot,ResolvedHagridConfig config) {
        return new RoutingContext(network,types,snapshot,config);
    }
    public String fingerprint() { return fingerprint; }
    public ResolvedHagridConfig config() { return config; }
    public synchronized Network network() { requireOpen(); return network; }
    synchronized void writeNetwork(java.io.OutputStream stream) { requireOpen(); network.writeNetwork(stream); }
    public synchronized TravelTimeSnapshot snapshot() { requireOpen(); return snapshot; }
    public synchronized Map<Id<VehicleType>,VehicleProfile> vehicleProfiles() { requireOpen(); return profiles; }
    public synchronized VehicleProfile vehicleProfile(Id<VehicleType> id) {
        requireOpen(); VehicleProfile profile=profiles.get(id);
        if(profile==null) throw new IllegalArgumentException("Unknown vehicle type "+id+" in routing context");
        return profile;
    }
    synchronized ModalRoutingEngine modalRouting() {
        requireOpen();
        if(modalRouting==null) modalRouting=new ModalRoutingEngine(this);
        return modalRouting;
    }
    public synchronized void requireOpen() {
        if(closed || (closing && lifecycle.getReadHoldCount()==0)) throw new IllegalStateException("Routing context is closing or closed");
    }
    public boolean isClosing(){return closing && !closed;}
    /** Admission/lifetime barrier for a context-owned query; close waits only for this context's leases. */
    public QueryLease acquireQuery(){
        if(closing || closed) throw new IllegalStateException("Routing context is closing or closed");
        lifecycle.readLock().lock();
        if(closing || closed){lifecycle.readLock().unlock();throw new IllegalStateException("Routing context is closing or closed");}
        return new QueryLease();
    }
    public final class QueryLease implements AutoCloseable {
        private final Thread owner=Thread.currentThread(); private boolean released;
        private QueryLease(){}
        public void close(){
            if(owner!=Thread.currentThread()) throw new IllegalStateException("Routing query lease must be released by its owner");
            if(!released){released=true;lifecycle.readLock().unlock();}
        }
    }
    void checkCancellation(){
        if(closing || closed) throw new java.util.concurrent.CancellationException("Routing context is closing");
    }
    public record Statistics(int cacheEntries,long cacheHits,long computations,long evictions,
                             int preparedGraphs,int createdRouters,int activeRouters,
                             long exactQueries,
                             long roadRoutingCalls,long fullValidationCalls) {
        public Statistics(int cacheEntries,long cacheHits,long computations,long evictions,int preparedGraphs,
                int createdRouters,int activeRouters,long exactQueries) {
            this(cacheEntries,cacheHits,computations,evictions,preparedGraphs,createdRouters,activeRouters,
                    exactQueries,0,0);
        }
        public Statistics(int cacheEntries,long cacheHits,long computations,long evictions,int preparedGraphs,int createdRouters,int activeRouters) {
            this(cacheEntries,cacheHits,computations,evictions,preparedGraphs,createdRouters,activeRouters,0,0,0);
        }
    }
    public synchronized Statistics statistics(){
        return modalRouting==null ? new Statistics(0,0,0,0,0,0,0) : modalRouting.statistics();
    }
    public void close() {
        if(lifecycle.getReadHoldCount()>0) throw new IllegalStateException("Cannot close routing context from its own active query");
        synchronized(closeMonitor) {
            if(closed) return;
            closing=true;
            lifecycle.writeLock().lock();
            try {
                synchronized(this) {
                    closed=true;
                    if(modalRouting!=null) modalRouting.close();
                    modalRouting=null;network=null;snapshot=null;profiles=Map.of();
                }
            } finally {lifecycle.writeLock().unlock();}
        }
    }
}