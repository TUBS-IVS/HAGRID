package hagrid.core.routing;

import com.graphhopper.jsprit.core.problem.Location;
import com.graphhopper.jsprit.core.problem.driver.Driver;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.*;
import org.matsim.core.router.util.*;
import org.matsim.freight.carriers.CarrierVehicleTypes;
import org.matsim.freight.carriers.jsprit.VRPTransportCosts;
import org.matsim.vehicles.*;

/** Native compatibility retains the native cache on unrestricted networks and adds exact link routing. */
final class NativeLinkTransportCosts implements VRPTransportCosts,LinkRouteProvider {
    private final VRPTransportCosts delegate;private final CarrierVehicleTypes types;private final String mode;private final boolean constantProfile;
    private final RestrictedLinkPathFinder restricted;private final Vehicle neutral;
    NativeLinkTransportCosts(VRPTransportCosts delegate,CarrierVehicleTypes types,String mode,boolean constantProfile) {
        this.delegate=delegate;this.types=types;this.mode=mode;this.constantProfile=constantProfile;
        restricted=TurnRestrictionValidator.hasRestrictions(delegate.getNetwork(),mode)?new RestrictedLinkPathFinder(delegate.getNetwork(),mode,delegate.getTravelTime()):null;
        if(restricted!=null&&!constantProfile)throw new IllegalArgumentException(
                "Turn-restricted native routing needs a certified constant profile; use a validated frozen RoutingContext for variable times");
        var type=VehicleUtils.createVehicleType(Id.create("__hagrid_neutral_" + mode,VehicleType.class),mode);
        type.getCostInformation().setCostsPerMeter(0.0).setCostsPerSecond(1.0).setFixedCost(0.0);
        neutral=VehicleUtils.createVehicle(Id.createVehicleId("__hagrid_neutral_"+mode),type);
    }
    boolean certifiedConstantProfile(){return constantProfile;}
    @Override public Network getNetwork(){return delegate.getNetwork();}
    @Override public TravelTime getTravelTime(){return delegate.getTravelTime();}
    @Override public LeastCostPathCalculator getRouter(){
        if(restricted!=null)throw new IllegalStateException("Turn-restricted native costs require link-based routing");
        return delegate.getRouter();
    }
    @Override public RoadLeg routeLeg(Id<Link> from,Id<Link> to,double departure,Vehicle vehicle) {
        Vehicle actual=vehicle==null?neutral:vehicle;
        if(actual.getType().hasNetworkMode()&&!mode.equals(actual.getType().getNetworkMode()))throw new IllegalArgumentException("Vehicle disagrees with native mode " + mode);
        if(restricted!=null)return restricted.route(from,to,departure,actual);
        return CarrierRoadRouter.routeLeg(delegate,from,to,departure,actual);
    }
    private Vehicle adapt(com.graphhopper.jsprit.core.problem.vehicle.Vehicle vehicle) {
        if(vehicle==null)return neutral;
        var type=types.getVehicleTypes().get(Id.create(vehicle.getType().getTypeId(),VehicleType.class));
        if(type==null)throw new IllegalArgumentException("Unregistered native routing type " + vehicle.getType().getTypeId());
        if(!mode.equals(type.getNetworkMode()))throw new IllegalArgumentException("Vehicle type disagrees with native mode " + mode);
        return VehicleUtils.createVehicle(Id.createVehicleId(vehicle.getId()),type);
    }
    RoadLeg road(Location from,Location to,double time,com.graphhopper.jsprit.core.problem.vehicle.Vehicle vehicle) {
        return routeLeg(Id.createLinkId(from.getId()),Id.createLinkId(to.getId()),time,adapt(vehicle));
    }
    @Override public double getTransportTime(Location from,Location to,double time,Driver driver,com.graphhopper.jsprit.core.problem.vehicle.Vehicle vehicle){
        return restricted==null?delegate.getTransportTime(from,to,time,driver,vehicle):road(from,to,time,vehicle).travelTimeSeconds();
    }
    @Override public double getTransportCost(Location from,Location to,double time,Driver driver,com.graphhopper.jsprit.core.problem.vehicle.Vehicle vehicle){
        return restricted==null?delegate.getTransportCost(from,to,time,driver,vehicle):road(from,to,time,vehicle).monetaryCost();
    }
    @Override public double getDistance(Location from,Location to,double time,com.graphhopper.jsprit.core.problem.vehicle.Vehicle vehicle){
        if(restricted!=null)return road(from,to,time,vehicle).distanceMeters();
        if(from.getId().equals(to.getId()))return 0;
        // Native cached distance counts origin instead of destination. Keep its chosen path/cache,
        // replacing exactly that endpoint contribution to match final link roads.
        double distance=delegate.getDistance(from,to,time,vehicle);
        if(distance==Double.MAX_VALUE)return distance;
        var origin=delegate.getNetwork().getLinks().get(Id.createLinkId(from.getId()));
        var destination=delegate.getNetwork().getLinks().get(Id.createLinkId(to.getId()));
        if(origin==null||destination==null)throw new IllegalArgumentException("Missing native road endpoints");
        return distance-origin.getLength()+destination.getLength();
    }
    private double backwardDeparture(Location from,Location to,double arrival,com.graphhopper.jsprit.core.problem.vehicle.Vehicle vehicle) {
        if(!Double.isFinite(arrival)||arrival<0)throw new IllegalArgumentException("Invalid backward arrival");
        double duration=road(from,to,0,vehicle).travelTimeSeconds();
        return duration>arrival?Double.NaN:arrival-duration;
    }
    @Override public double getBackwardTransportTime(Location from,Location to,double arrival,Driver driver,com.graphhopper.jsprit.core.problem.vehicle.Vehicle vehicle){
        if(restricted==null)return delegate.getBackwardTransportTime(from,to,arrival,driver,vehicle);
        double departure=backwardDeparture(from,to,arrival,vehicle);return Double.isNaN(departure)?Double.MAX_VALUE:arrival-departure;
    }
    @Override public double getBackwardTransportCost(Location from,Location to,double arrival,Driver driver,com.graphhopper.jsprit.core.problem.vehicle.Vehicle vehicle){
        if(restricted==null)return delegate.getBackwardTransportCost(from,to,arrival,driver,vehicle);
        double departure=backwardDeparture(from,to,arrival,vehicle);return Double.isNaN(departure)?Double.MAX_VALUE:road(from,to,departure,vehicle).monetaryCost();
    }
}

