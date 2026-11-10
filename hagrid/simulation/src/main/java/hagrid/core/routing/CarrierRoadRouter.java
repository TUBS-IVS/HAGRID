package hagrid.core.routing;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.freight.carriers.*;
import org.matsim.freight.carriers.jsprit.VRPTransportCosts;
import org.matsim.vehicles.*;
import org.matsim.core.population.routes.NetworkRoute;
import java.util.*;

/** Prepares all roads and timestamps before writing any tour leg. */
public final class CarrierRoadRouter {
    private CarrierRoadRouter() {}
    private record LegUpdate(Tour.Leg leg,double departure,RoadLeg road) {}
    private record ActivityUpdate(Tour.TourActivity activity,double arrival) {}
    public static void routePlan(CarrierPlan plan,VRPTransportCosts costs) {
        updatePlan(plan,costs,false);
    }
    /** Refresh execution times along the already selected roads, without finding new paths. */
    static void refreshStoredRoads(CarrierPlan plan,VRPTransportCosts costs) {
        updatePlan(plan,costs,true);
    }
    private static void updatePlan(CarrierPlan plan,VRPTransportCosts costs,boolean retainRoads) {
        Objects.requireNonNull(plan,"carrier plan");Objects.requireNonNull(costs,"transport costs");
        var legs=new ArrayList<LegUpdate>();var activities=new ArrayList<ActivityUpdate>();
        for(var scheduled:plan.getScheduledTours()) {
            var tour=scheduled.getTour();if(tour.getTourElements().isEmpty())continue;
            Vehicle vehicle=VehicleUtils.createVehicle(scheduled.getVehicle().getId(),scheduled.getVehicle().getType());
            double time=scheduled.getDeparture();Id<Link> location=tour.getStartLinkId();Tour.Leg pending=null;
            for(var element:tour.getTourElements()) {
                if(element instanceof Tour.Leg leg) {
                    if(pending!=null)throw new IllegalArgumentException("Consecutive road legs in " + tour.getId());
                    pending=leg;
                } else if(element instanceof Tour.TourActivity activity) {
                    if(pending==null)throw new IllegalArgumentException("Activity without road leg in " + tour.getId());
                    var road=retainRoads?storedRoad(pending,costs,time,vehicle):routeLeg(costs,location,activity.getLocation(),time,vehicle);legs.add(new LegUpdate(pending,time,road));
                    double arrival=time+road.travelTimeSeconds();activities.add(new ActivityUpdate(activity,arrival));
                    time=Math.max(arrival,activity.getTimeWindow().getStart())+activity.getDuration();
                    if(!Double.isFinite(time))throw new IllegalArgumentException("Invalid tour activity timing");
                    location=activity.getLocation();pending=null;
                } else throw new IllegalArgumentException("Unsupported carrier tour element");
            }
            if(pending==null)throw new IllegalArgumentException("Missing returning road leg in " + tour.getId());
            legs.add(new LegUpdate(pending,time,retainRoads?storedRoad(pending,costs,time,vehicle):routeLeg(costs,location,tour.getEndLinkId(),time,vehicle)));
        }
        for(var update:legs) {
            // Native Freight creates execution vehicle IDs from driver IDs at mobsim startup.
            // A template/type-adapter ID here would override that native binding in QSim.
            var route=update.road.route();route.setVehicleId(null);
            update.leg.setDepartureTime(update.departure);update.leg.setExpectedTransportTime(update.road.travelTimeSeconds());update.leg.setRoute(route);
        }
        activities.forEach(update -> update.activity.setExpectedArrival(update.arrival));
    }
    private static RoadLeg storedRoad(Tour.Leg leg,VRPTransportCosts costs,double departure,Vehicle vehicle) {
        if(!(leg.getRoute() instanceof NetworkRoute route))throw new IllegalArgumentException("Missing stored road route");
        var ids=new ArrayList<>(route.getLinkIds());
        if(!route.getStartLinkId().equals(route.getEndLinkId())||!ids.isEmpty())ids.add(route.getEndLinkId());
        double time=departure,distance=0;
        for(var id:ids) {
            var link=costs.getNetwork().getLinks().get(id);
            if(link==null)throw new IllegalArgumentException("Missing stored road link "+id);
            double seconds=costs.getTravelTime().getLinkTravelTime(link,time,null,vehicle);
            if(!Double.isFinite(seconds)||seconds<0)throw new IllegalArgumentException("Invalid stored road time "+id);
            time+=seconds;distance+=link.getLength();
        }
        var refreshed=(NetworkRoute)route.clone();refreshed.setTravelTime(time-departure);refreshed.setDistance(distance);
        var rates=vehicle.getType().getCostInformation();
        return new RoadLeg(refreshed,time-departure,distance,
                distance*rates.getCostsPerMeter()+(time-departure)*rates.getCostsPerSecond());
    }
    static RoadLeg routeLeg(VRPTransportCosts costs,Id<Link> from,Id<Link> to,double departure,Vehicle vehicle) {
        if(costs instanceof LinkRouteProvider provider)return provider.routeLeg(from,to,departure,vehicle);
        var network=costs.getNetwork();String mode=vehicle.getType().getNetworkMode();
        if(TurnRestrictionValidator.hasRestrictions(network,mode))
            throw new IllegalArgumentException("Transport provider needs LinkRouteProvider for turn restrictions");
        var origin=network.getLinks().get(from);var destination=network.getLinks().get(to);
        if(origin==null||destination==null||!origin.getAllowedModes().contains(mode)||!destination.getAllowedModes().contains(mode))
            throw new IllegalArgumentException("Missing/mode-forbidden road endpoints");
        if(from.equals(to))return RestrictedLinkPathFinder.road(from,to,List.of(),0,0,0,vehicle);
        var path=costs.getRouter().calcLeastCostPath(origin.getToNode(),destination.getFromNode(),departure,null,vehicle);
        if(path==null)throw new NoRouteException("No road path from " + from + " to " + to);
        double targetTime=costs.getTravelTime().getLinkTravelTime(destination,departure+path.travelTime,null,vehicle);
        double distance=destination.getLength()+path.links.stream().mapToDouble(Link::getLength).sum();
        var rates=vehicle.getType().getCostInformation();
        double cost=path.travelCost+destination.getLength()*rates.getCostsPerMeter()+targetTime*rates.getCostsPerSecond();
        return RestrictedLinkPathFinder.road(from,to,path.links.stream().map(Link::getId).toList(),path.travelTime+targetTime,distance,cost,vehicle);
    }
}
