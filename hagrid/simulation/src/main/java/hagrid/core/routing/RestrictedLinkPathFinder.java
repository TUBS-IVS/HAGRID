package hagrid.core.routing;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.*;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.router.util.TravelTime;
import org.matsim.vehicles.Vehicle;
import java.util.*;
import java.util.concurrent.CancellationException;

/** Dijkstra over road links and restriction-prefix states. Only restriction prefixes are retained. */
final class RestrictedLinkPathFinder {
    private final Network network;private final String mode;private final TravelTime time;
    private final Set<List<Id<Link>>> forbidden=new HashSet<>(),prefixes=new HashSet<>();
    private record State(Id<Link> link,List<Id<Link>> prefix) {}
    private record Label(State state,double arrival,double cost,Label previous) {}
    RestrictedLinkPathFinder(Network network,String mode,TravelTime time) {
        this.network=network;this.mode=mode;this.time=time;
        for(Link link:network.getLinks().values()) {
            var restrictions=NetworkUtils.getDisallowedNextLinks(link);if(restrictions==null)continue;
            for(var next:restrictions.getDisallowedLinkSequences(mode)) {
                var full=new ArrayList<Id<Link>>();full.add(link.getId());full.addAll(next);forbidden.add(List.copyOf(full));
                for(int i=1;i<full.size();i++)prefixes.add(List.copyOf(full.subList(0,i)));
            }
        }
    }
    private List<Id<Link>> advance(List<Id<Link>> prefix,Id<Link> link) {
        var next=new ArrayList<>(prefix);next.add(link);
        for(int start=0;start<next.size();start++)if(forbidden.contains(next.subList(start,next.size())))return null;
        for(int start=0;start<next.size();start++)if(prefixes.contains(next.subList(start,next.size())))return List.copyOf(next.subList(start,next.size()));
        return List.of();
    }
    RoadLeg route(Id<Link> from,Id<Link> to,double departure,Vehicle vehicle) {
        Link origin=require(from),destination=require(to);
        if(!Double.isFinite(departure)||departure<0)throw new IllegalArgumentException("Invalid road departure");
        if(from.equals(to))return road(from,to,List.of(),0,0,0,vehicle);
        var rates=vehicle.getType().getCostInformation();
        var queue=new PriorityQueue<Label>(Comparator.comparingDouble(Label::cost).thenComparingDouble(Label::arrival)
                .thenComparing(l -> l.state.link.toString()));
        var best=new HashMap<State,Label>();
        var initial=new Label(new State(origin.getId(),advance(List.of(),from)),departure,0,null);
        best.put(initial.state,initial);queue.add(initial);
        while(!queue.isEmpty()) {
            if(Thread.currentThread().isInterrupted())throw new CancellationException("Restricted road search interrupted");
            var current=queue.remove();if(best.get(current.state)!=current)continue;
            if(current.state.link.equals(destination.getId())) {
                var links=new ArrayList<Id<Link>>();double distance=0;
                for(Label step=current;step.previous!=null;step=step.previous){links.add(step.state.link);distance+=require(step.state.link).getLength();}
                Collections.reverse(links);
                return road(from,to,links.subList(0,links.size()-1),current.arrival-departure,distance,current.cost,vehicle);
            }
            for(Link link:require(current.state.link).getToNode().getOutLinks().values().stream().sorted(Comparator.comparing(l -> l.getId().toString())).toList()) {
                if(!link.getAllowedModes().contains(mode))continue;
                var prefix=advance(current.state.prefix,link.getId());if(prefix==null)continue;
                double seconds=time.getLinkTravelTime(link,current.arrival,null,vehicle);
                double cost=link.getLength()*rates.getCostsPerMeter()+seconds*rates.getCostsPerSecond();
                if(!Double.isFinite(seconds)||seconds<0||!Double.isFinite(cost)||cost<0)throw new IllegalArgumentException("Invalid restricted road travel/cost on " + link.getId());
                var state=new State(link.getId(),prefix);var previous=best.get(state);
                double candidateCost=current.cost+cost,candidateArrival=current.arrival+seconds;
                if(previous==null||candidateCost<previous.cost || candidateCost==previous.cost&&candidateArrival<previous.arrival) {
                    var candidate=new Label(state,candidateArrival,candidateCost,current);best.put(state,candidate);queue.add(candidate);
                }
            }
        }
        throw new NoRouteException("No " + mode + " link path from " + from + " to " + to);
    }
    private Link require(Id<Link> id) {
        var link=network.getLinks().get(id);
        if(link==null||!link.getAllowedModes().contains(mode))throw new IllegalArgumentException("Missing/mode-forbidden road link " + id);
        return link;
    }
    static RoadLeg road(Id<Link> from,Id<Link> to,List<Id<Link>> intermediate,double seconds,double distance,double cost,Vehicle vehicle) {
        var route=RouteUtils.createLinkNetworkRouteImpl(from,to);route.setLinkIds(from,intermediate,to);
        route.setTravelTime(seconds);route.setDistance(distance);if(vehicle!=null)route.setVehicleId(vehicle.getId());
        return new RoadLeg(route,seconds,distance,cost);
    }
}
