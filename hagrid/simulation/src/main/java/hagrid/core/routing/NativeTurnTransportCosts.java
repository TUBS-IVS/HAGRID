package hagrid.core.routing;

import com.graphhopper.jsprit.core.problem.Location;
import com.graphhopper.jsprit.core.problem.driver.Driver;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.*;
import org.matsim.core.router.util.*;
import org.matsim.freight.carriers.jsprit.VRPTransportCosts;
import java.util.*;

/** Search tariff for the roads selected by the native provider; does not change path selection.
 * The bounded scalar cache avoids replaying roads for repeated insertion queries. Final routing
 * retains base road costs: PlanValidator and execution scoring apply their own transition tariff.
 */
final class NativeTurnTransportCosts implements VRPTransportCosts, LinkRouteProvider {
    private static final int CACHE_LIMIT=8192;
    private final NativeLinkTransportCosts delegate;
    private final double tariff;
    private record Key(String from,String to,String type,long departure) {}
    private final Map<Key,Integer> counts=new LinkedHashMap<>(128,.75f,true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Key,Integer> entry){return size()>CACHE_LIMIT;}
    };
    NativeTurnTransportCosts(NativeLinkTransportCosts delegate,double tariff) {
        this.delegate=Objects.requireNonNull(delegate);
        if(!Double.isFinite(tariff)||tariff<=0)throw new IllegalArgumentException("Native reversal tariff must be positive");
        this.tariff=tariff;
    }
    private int turns(Location from,Location to,double departure,com.graphhopper.jsprit.core.problem.vehicle.Vehicle vehicle) {
        // Neutral regret/neighbourhood distances have no executable vehicle or selected road.
        // Preserve their native heuristic; every actual route/insertion has a vehicle profile.
        if(vehicle==null||from.getId().equals(to.getId()))return 0;
        var key=new Key(from.getId(),to.getId(),vehicle==null?"":vehicle.getType().getTypeId(),
                delegate.certifiedConstantProfile()?0:Double.doubleToLongBits(departure));
        synchronized(counts){var cached=counts.get(key);if(cached!=null)return cached;}
        var route=delegate.road(from,to,departure,vehicle).route();
        var ids=new ArrayList<>(route.getLinkIds());
        if(!route.getStartLinkId().equals(route.getEndLinkId())||!ids.isEmpty())ids.add(route.getEndLinkId());
        Link previous=getNetwork().getLinks().get(route.getStartLinkId());int count=0;
        for(var id:ids){Link next=getNetwork().getLinks().get(id);if(OriginalRoadReverse.roadTransition(previous,next))count++;previous=next;}
        synchronized(counts){counts.put(key,count);}return count;
    }
    @Override public double getTransportCost(Location from,Location to,double departure,Driver driver,com.graphhopper.jsprit.core.problem.vehicle.Vehicle vehicle){
        double base=delegate.getTransportCost(from,to,departure,driver,vehicle);
        return !Double.isFinite(base)||base==Double.MAX_VALUE?base:base+tariff*turns(from,to,departure,vehicle);
    }
    @Override public double getBackwardTransportCost(Location from,Location to,double arrival,Driver driver,com.graphhopper.jsprit.core.problem.vehicle.Vehicle vehicle){
        double base=delegate.getBackwardTransportCost(from,to,arrival,driver,vehicle);
        if(!Double.isFinite(base)||base==Double.MAX_VALUE)return base;
        double duration=delegate.getBackwardTransportTime(from,to,arrival,driver,vehicle);
        return base+tariff*turns(from,to,Math.max(0,arrival-duration),vehicle);
    }
    @Override public double getTransportTime(Location from,Location to,double departure,Driver driver,com.graphhopper.jsprit.core.problem.vehicle.Vehicle vehicle){return delegate.getTransportTime(from,to,departure,driver,vehicle);}
    @Override public double getBackwardTransportTime(Location from,Location to,double arrival,Driver driver,com.graphhopper.jsprit.core.problem.vehicle.Vehicle vehicle){return delegate.getBackwardTransportTime(from,to,arrival,driver,vehicle);}
    @Override public double getDistance(Location from,Location to,double departure,com.graphhopper.jsprit.core.problem.vehicle.Vehicle vehicle){return delegate.getDistance(from,to,departure,vehicle);}
    @Override public Network getNetwork(){return delegate.getNetwork();}
    @Override public TravelTime getTravelTime(){return delegate.getTravelTime();}
    @Override public LeastCostPathCalculator getRouter(){return delegate.getRouter();}
    @Override public RoadLeg routeLeg(Id<Link> from,Id<Link> to,double departure,org.matsim.vehicles.Vehicle vehicle){return delegate.routeLeg(from,to,departure,vehicle);}
}
