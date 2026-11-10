package hagrid.core.routing;

import com.graphhopper.jsprit.core.problem.Location;
import com.graphhopper.jsprit.core.problem.driver.Driver;
import hagrid.core.routing.RoutingContext.VehicleProfile;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.*;
import org.matsim.api.core.v01.population.Person;
import hagrid.core.routing.ModalRoutingEngine.TransportValues;
import org.matsim.core.router.util.*;
import org.matsim.vehicles.*;
import java.util.*;

/** Universal MATSim/jsprit adapter. Frozen graph/profile preparation belongs to the shared context. */
public final class ModalTransportCosts implements ReplanningTransportCosts, LinkRouteProvider {
    private final RoutingContext context;
    private final String preparationMode;
    private final ModalRoutingEngine engine;
    private final TravelTime travelTime;
    private final LeastCostPathCalculator router;
    private final ReplanningTransportCosts solverView;

    public ModalTransportCosts(RoutingRequest request) {
        Objects.requireNonNull(request); context=request.context(); preparationMode=request.preparationMode();
        try(var lease=context.acquireQuery()){engine=context.modalRouting();}
        travelTime=(link,time,person,vehicle)->{
            try(var lease=context.acquireQuery()) {
                VehicleProfile profile=engine.profile(vehicle,preparationMode);
                context.checkCancellation();
                return engine.mode(profile.mode()).time.getLinkTravelTime(engine.link(link.getId(),profile.mode()),time,person,engine.vehicle(profile));
            }
        };
        router=(from,to,time,person,vehicle)->{
            try(var lease=context.acquireQuery()) {
                VehicleProfile profile=engine.profile(vehicle,preparationMode);
                engine.validateTime(time);
                return engine.roadRoute(profile,from,to,time,person);
            }
        };
        solverView=engine.requiresExactTourFeasibility()?new ExactTemporalSolverCosts(this):this;
    }
    /** Immutable solver view: native forward timing uses actual link entries; public OD estimates retain their raster. */
    public ReplanningTransportCosts solverCosts(){context.requireOpen();return solverView;}
    @Override public RoadLeg routeLeg(Id<Link> from,Id<Link> to,double departure,Vehicle vehicle) {
        try(var lease=context.acquireQuery()) {
            var profile=engine.profile(vehicle,preparationMode);engine.validateTime(departure);
            var road=engine.roadLeg(profile,from,to,departure);
            validateArrival(departure,road.travelTimeSeconds());
            // Workers use type-profile vehicles. Bind a detached result to this caller only.
            var route=road.route();route.setVehicleId(vehicle==null?null:vehicle.getId());
            return new RoadLeg(route,road.travelTimeSeconds(),road.distanceMeters(),road.monetaryCost());
        }
    }
    void recordFullValidation(){try(var lease=context.acquireQuery()){engine.recordFullValidation();}}
    boolean requiresExactTourFeasibility(){try(var lease=context.acquireQuery()){return engine.requiresExactTourFeasibility();}}
    /** Actual link-entry propagation, deliberately distinct from the legacy half-hour OD estimate. */
    double exactTransportTime(Location from,Location to,double departure,com.graphhopper.jsprit.core.problem.vehicle.Vehicle vehicle) {
        try(var lease=context.acquireQuery()) {
            var profile=engine.profile(vehicle,preparationMode);engine.validateTime(departure);
            double seconds=engine.exactTime(profile,locationId(from),locationId(to),departure);
            validateArrival(departure,seconds);return seconds;
        }
    }
    /** Both quantities are needed by the same insertion step. Share its distance path lookup. */
    TransportValues insertionLeg(Location from,Location to,double departure,Driver driver,
                                 com.graphhopper.jsprit.core.problem.vehicle.Vehicle vehicle) {
        try(var lease=context.acquireQuery()) {
            var profile=engine.profile(vehicle,preparationMode);
            engine.validateTime(departure);
            if(!engine.rawDistance(profile)) {
                var priced=solverDistanceValues(from,to,departure,vehicle);
                return new TransportValues(exactTransportTime(from,to,departure,vehicle),priced.distanceMeters(),priced.monetaryCost());
            }
            var leg=engine.insertionLeg(profile,locationId(from),locationId(to),departure);
            validateArrival(departure,leg.timeSeconds());
            return leg;
        }
    }

    TransportValues solverDistanceValues(Location from,Location to,double departure,com.graphhopper.jsprit.core.problem.vehicle.Vehicle vehicle) {
        try(var lease=context.acquireQuery()) {
            var profile=engine.profile(vehicle,preparationMode);
            if(!engine.rawDistance(profile))return values(from,to,departure,vehicle);
            context.checkCancellation();
            var path=engine.distanceLeg(profile,Id.createLinkId(locationId(from)),Id.createLinkId(locationId(to)));
            return new TransportValues(0,path.distanceMeters(),path.monetaryCost());
        }
    }
    public Network getNetwork(){return context.network();}
    public TravelTime getTravelTime(){context.requireOpen(); return travelTime;}
    public LeastCostPathCalculator getRouter(){context.requireOpen(); return router;}
    public double getTransportTime(Location from,Location to,double time,Driver driver,com.graphhopper.jsprit.core.problem.vehicle.Vehicle vehicle){return values(from,to,time,vehicle).timeSeconds();}
    public double getTransportCost(Location from,Location to,double time,Driver driver,com.graphhopper.jsprit.core.problem.vehicle.Vehicle vehicle){return values(from,to,time,vehicle).monetaryCost();}
    public double getDistance(Location from,Location to,double time,com.graphhopper.jsprit.core.problem.vehicle.Vehicle vehicle){return values(from,to,time,vehicle).distanceMeters();}
    /** Latest feasible departure; a jump in arrivals can leave conservative slack at the destination. */
    public double getBackwardTransportTime(Location from,Location to,double arrival,Driver driver,com.graphhopper.jsprit.core.problem.vehicle.Vehicle vehicle){
        try(var lease=context.acquireQuery()){return backward(from,to,arrival,vehicle).durationSeconds();}
    }
    public double getBackwardTransportCost(Location from,Location to,double arrival,Driver driver,com.graphhopper.jsprit.core.problem.vehicle.Vehicle vehicle){
        try(var lease=context.acquireQuery()){return backward(from,to,arrival,vehicle).monetaryCost();}
    }
    @Override public void prepareForReplanning() {
        try(var lease=context.acquireQuery()){context.checkCancellation();}
    }
    @Override public void logSummary(String label) {
        try(var lease=context.acquireQuery()) {
            context.checkCancellation();
            var stats=engine.statistics();
            org.apache.logging.log4j.LogManager.getLogger(ModalTransportCosts.class).info(
                    "[RoutingCostCache] {} | entries={} hits={} calculations={} evictions={} graphs={} workers={} active={}",
                    label,stats.cacheEntries(),stats.cacheHits(),stats.computations(),stats.evictions(),
                    stats.preparedGraphs(),stats.createdRouters(),stats.activeRouters());
        }
    }
    private record BackwardValues(double durationSeconds,double monetaryCost) {}
    private BackwardValues backward(Location from,Location to,double arrival,com.graphhopper.jsprit.core.problem.vehicle.Vehicle vehicle){
        var profile=engine.profile(vehicle,preparationMode);
        if(!Double.isFinite(arrival)||arrival<0||arrival>engine.horizonSeconds)
            throw new TravelTimeSnapshot.RangeException("Arrival deadline outside captured horizon: "+arrival);
        if(engine.rawDistance(profile)) {
            var intervals=feasibleDepartureIntervals(from,to,arrival,vehicle);
            if(intervals.isEmpty())return new BackwardValues(Double.MAX_VALUE,Double.MAX_VALUE);
            for(int i=intervals.size()-1;i>=0;i--) {
                var interval=intervals.get(i);double duration=arrival-interval.lastDeparture();
                // The caller subtracts this duration from the deadline. That round trip can otherwise
                // round nextDown(binBoundary) onto the forbidden next bin at large deadlines.
                while(arrival-duration>interval.lastDeparture())duration=Math.nextUp(duration);
                double departure=arrival-duration;
                if(departure<interval.firstDeparture())continue;
                var actual=values(from,to,departure,vehicle);
                if(departure+actual.timeSeconds()>arrival)throw new IllegalStateException("Inverse interval failed forward check");
                return new BackwardValues(duration,actual.monetaryCost());
            }
            return new BackwardValues(Double.MAX_VALUE,Double.MAX_VALUE);
        }
        TransportValues early;
        try { early=values(from,to,0,vehicle); }
        catch(TravelTimeSnapshot.RangeException beyondHorizon){return new BackwardValues(Double.MAX_VALUE,Double.MAX_VALUE);}
        if(early.timeSeconds()>arrival) return new BackwardValues(Double.MAX_VALUE,Double.MAX_VALUE);
        if(engine.isConstant(profile.mode())) {
            double departure=arrival-early.timeSeconds();
            var actual=values(from,to,departure,vehicle);
            return new BackwardValues(early.timeSeconds(),actual.monetaryCost());
        }
        double low=0,high=arrival; TransportValues feasible=early;
        for(int i=0;i<80 && high-low>1e-7;i++) {
            double candidate=(low+high)/2;
            TransportValues actual;
            try { actual=values(from,to,candidate,vehicle); }
            catch(TravelTimeSnapshot.RangeException beyondHorizon){high=candidate;continue;}
            if(candidate+actual.timeSeconds()<=arrival){low=candidate;feasible=actual;}
            else high=candidate;
        }
        // Strict feasibility is retained; roundoff/tolerance never makes a too-late departure admissible.
        var checked=values(from,to,low,vehicle);
        if(low+checked.timeSeconds()>arrival) throw new IllegalStateException("Backward departure failed its forward check");
        return new BackwardValues(arrival-low,checked.monetaryCost());
    }
    /** Closed representable-double intervals. Gaps are retained; no FIFO/prefix feasibility assumption. */
    record DepartureInterval(double firstDeparture,double lastDeparture) {}
    List<DepartureInterval> feasibleDepartureIntervals(Location from,Location to,double arrival,
            com.graphhopper.jsprit.core.problem.vehicle.Vehicle vehicle) {
        try(var lease=context.acquireQuery()) {
            if(!Double.isFinite(arrival)||arrival<0||arrival>engine.horizonSeconds)
                throw new TravelTimeSnapshot.RangeException("Arrival deadline outside captured horizon: "+arrival);
            var profile=engine.profile(vehicle,preparationMode);
            if(!engine.rawDistance(profile))throw new IllegalArgumentException("OD intervals require variable distance-only routing");
            var result=new ArrayList<DepartureInterval>();double width=engine.rasterSeconds();
            for(long bin=0;bin*width<engine.horizonSeconds&&bin*width<=arrival;bin++) {
                context.checkCancellation();double lower=bin*width;
                TransportValues sample;
                try {sample=values(from,to,lower,vehicle);}
                catch(TravelTimeSnapshot.RangeException unavailable){continue;}
                double upper=Math.min(Math.nextDown(Math.min((bin+1)*width,engine.horizonSeconds)),arrival-sample.timeSeconds());
                while(upper>=lower&&upper+sample.timeSeconds()>arrival)upper=Math.nextDown(upper);
                if(upper>=lower)result.add(new DepartureInterval(lower,upper));
            }
            return List.copyOf(result);
        }
    }
    private TransportValues values(Location from,Location to,double time,com.graphhopper.jsprit.core.problem.vehicle.Vehicle vehicle){
        try(var lease=context.acquireQuery()) {
            VehicleProfile profile=engine.profile(vehicle,preparationMode);
            String originId=locationId(from),destinationId=locationId(to);
            engine.validateTime(time); context.checkCancellation();
            if(originId.equals(destinationId)) {
                engine.link(Id.createLinkId(originId),profile.mode());
                return new TransportValues(0,0,0);
            }
            double keyTime=engine.isConstant(profile.mode()) ? 0:
                    engine.rawDistance(profile)?Math.floor(time/engine.rasterSeconds())*engine.rasterSeconds():time;
            var exactKey=engine.key(originId,destinationId,profile,keyTime,ModalRoutingEngine.Kind.EXACT);
            var result=engine.cached(exactKey);
            if(result==null) {
                result=engine.values(exactKey,()->calculateQuery(exactKey,profile,keyTime));
            }
            validateArrival(time,result.timeSeconds());
            return result;
        }
    }
    private TransportValues calculateQuery(ModalRoutingEngine.Key key,VehicleProfile profile,double time) {
        engine.exactQueries.incrementAndGet();
        return calculate(engine,profile,engine.link(Id.createLinkId(key.from()),profile.mode()),engine.link(Id.createLinkId(key.to()),profile.mode()),time);
    }

    private TransportValues calculate(ModalRoutingEngine engine,VehicleProfile profile,Link origin,Link destination,double time){
        var road=engine.mode(profile.mode()).routeLinks(origin,destination,time,engine.vehicle(profile));
        validateArrival(time,road.travelTimeSeconds());
        return new TransportValues(road.travelTimeSeconds(),road.distanceMeters(),road.monetaryCost());
    }
    private void validateArrival(double departure,double duration){
        if(departure+duration>engine.horizonSeconds)
            throw new TravelTimeSnapshot.RangeException("Trip arrival exceeds captured horizon: "+(departure+duration));
    }    private static String locationId(Location location){
        if(location==null || location.getId()==null) throw new IllegalArgumentException("Routing location must identify a network link");
        return location.getId();
    }
    static void validateType(VehicleType type,VehicleProfile profile) {
        ModalRoutingEngine.validateType(type,profile);
    }
}
