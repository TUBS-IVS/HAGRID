package hagrid.core.routing;

import hagrid.core.config.ResolvedHagridConfig;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.*;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.freight.carriers.*;
import org.matsim.freight.carriers.jsprit.VRPTransportCosts;
import org.matsim.vehicles.*;
import java.util.*;

/** Replays the stored roads and canonical jobs. Neither solver scores nor expected arrival fields are certificates. */
public final class PlanValidator {
    private static final double EPS=1e-6;
    public record Validation(boolean valid, Set<String> servedJobIds, List<String> violations, double finalCost) {
        public Validation { servedJobIds=Set.copyOf(servedJobIds); violations=List.copyOf(violations); }
    }
    private PlanValidator() {}
    public static Validation validate(Carrier carrier,CarrierPlan plan,RoutingRequest request,ResolvedHagridConfig config){
        if(request.carrier()!=carrier) throw new IllegalArgumentException("Routing request belongs to another carrier");
        return validate(carrier,plan,new ModalTransportCosts(request),config,config.tourRules().maxRouteDurationSeconds());
    }
    /** Replay a native MATSim plan with the same canonical road, job, window and availability checks. */
    public static Validation validateNativeExecution(Carrier carrier,CarrierPlan plan,VRPTransportCosts costs,ResolvedHagridConfig config){
        // The route duration guides jsprit search. Native departures may incur overtime within driver availability.
        return validate(carrier,plan,costs,config,Double.POSITIVE_INFINITY);
    }
    /** Compatibility boundary for callers being migrated to frozen contexts. */
    public static Validation validate(Carrier carrier,CarrierPlan plan,VRPTransportCosts costs,ResolvedHagridConfig config,double routeLimit){
        return validate(carrier,plan,costs,config,routeLimit,config==null?0:config.tourRules().uTurnPenaltyCost());
    }
    /** Legacy initial callers supply their explicit penalty without acquiring a replanning configuration. */
    public static Validation validate(Carrier carrier,CarrierPlan plan,VRPTransportCosts costs,ResolvedHagridConfig config,double routeLimit,double uTurnPenalty){
        return validateInternal(carrier,plan,costs,config,routeLimit,uTurnPenalty,null);
    }
    public static Validation validateSections(Carrier carrier,CarrierPlan plan,VRPTransportCosts costs,ResolvedHagridConfig config,double routeLimit,double uTurnPenalty,hagrid.core.routing.sections.SectionRoadCosts sectionRoads) {
        return validateInternal(carrier,plan,costs,config,routeLimit,uTurnPenalty,Objects.requireNonNull(sectionRoads));
    }
    private static Validation validateInternal(Carrier carrier,CarrierPlan plan,VRPTransportCosts costs,ResolvedHagridConfig config,double routeLimit,double uTurnPenalty,hagrid.core.routing.sections.SectionRoadCosts sectionRoads){
        if(costs instanceof ModalTransportCosts modal)modal.recordFullValidation();
        var errors=new ArrayList<String>();var served=new HashSet<String>();var pickups=new HashSet<String>();
        var required=new HashSet<String>();carrier.getServices().keySet().forEach(id->required.add(id.toString()));
        carrier.getShipments().keySet().forEach(id->{if(!required.add(id.toString()))errors.add("Colliding service/shipment ID "+id);});
        if(plan==null)return new Validation(false,Set.of(),List.of("Missing plan"),Double.POSITIVE_INFINITY);
        String mode=CarrierModeResolver.resolve(carrier).orElse(null);
        var occupancy=new HashMap<String,List<double[]>>();var chargedVehicles=new HashSet<String>();double total=0;
        if(!carrier.getServices().isEmpty()&&!carrier.getShipments().isEmpty())errors.add("Mixed service/shipment load semantics are unsupported");
        for(ScheduledTour scheduled:plan.getScheduledTours()){
            try{
                var vehicle=scheduled.getVehicle();var type=vehicle.getType();var tour=scheduled.getTour();
                var prototype=carrier.getCarrierCapabilities().getCarrierVehicles().values().stream()
                        .filter(v->(carrier.getCarrierCapabilities().getFleetSize()==CarrierCapabilities.FleetSize.INFINITE || v.getId().equals(vehicle.getId())) && v.getType().getId().equals(type.getId()) && v.getLinkId().equals(vehicle.getLinkId())
                                && Math.abs(v.getEarliestStartTime()-vehicle.getEarliestStartTime())<EPS
                                && Math.abs(v.getLatestEndTime()-vehicle.getLatestEndTime())<EPS).findFirst().orElse(null);
                if(prototype==null)throw new IllegalArgumentException("Unknown vehicle/depot/availability "+vehicle.getId());
                if(!type.hasNetworkMode()||!Objects.equals(mode,type.getNetworkMode()))throw new IllegalArgumentException("Vehicle mode mismatch "+vehicle.getId());
                if(carrier.getCarrierCapabilities().getFleetSize()==CarrierCapabilities.FleetSize.FINITE
                        && !carrier.getCarrierCapabilities().getCarrierVehicles().containsKey(vehicle.getId()))errors.add("Unknown finite-fleet vehicle "+vehicle.getId());
                if(!tour.getStartLinkId().equals(vehicle.getLinkId())||!tour.getEndLinkId().equals(vehicle.getLinkId()))errors.add("Tour depot mismatch "+tour.getId());
                var expected=prototype.getType();
                if(Double.compare(type.getMaximumVelocity(),expected.getMaximumVelocity())!=0
                        ||Double.compare(type.getCapacity().getOther(),expected.getCapacity().getOther())!=0
                        ||!Objects.equals(type.getCostInformation().getFixedCosts(),expected.getCostInformation().getFixedCosts())
                        ||!Objects.equals(type.getCostInformation().getCostsPerMeter(),expected.getCostInformation().getCostsPerMeter())
                        ||!Objects.equals(type.getCostInformation().getCostsPerSecond(),expected.getCostInformation().getCostsPerSecond())
                        ||!new HashSet<>(CarriersUtils.getSkills(type)).equals(new HashSet<>(CarriersUtils.getSkills(expected))))
                    throw new IllegalArgumentException("Changed candidate vehicle profile " + vehicle.getId());
                var nativeVehicle=VehicleUtils.createVehicle(vehicle.getId(),expected);
                double time=scheduled.getDeparture();finite(time,"departure");
                if(time<vehicle.getEarliestStartTime()-EPS)errors.add("Departure before vehicle availability "+vehicle.getId());
                double capacity=prototype.getType().getCapacity().getOther();
                double load=tour.getTourElements().stream().filter(e->e instanceof Tour.ServiceActivity)
                        .map(e->(Tour.ServiceActivity)e).mapToInt(a->{var s=carrier.getServices().get(a.getService().getId());return s==null?0:s.getCapacityDemand();}).sum();
                checkLoad(load,capacity,errors);
                Id<Link> location=tour.getStartLinkId();var onboard=new HashSet<String>();boolean previousLeg=false;
                for(var element:tour.getTourElements()){
                    if(element instanceof Tour.Leg leg){
                        if(previousLeg)errors.add("Consecutive tour legs "+tour.getId());
                        var driven=replay(leg,location,time,mode,nativeVehicle,costs,uTurnPenalty,sectionRoads);
                        time+=driven.seconds();total+=driven.cost();location=driven.destination();previousLeg=true;
                    }else if(element instanceof Tour.TourActivity activity){
                        if(!previousLeg)errors.add("Activity without preceding leg "+tour.getId());
                        if(!location.equals(activity.getLocation()))errors.add("Road destination differs from activity "+activity.getLocation());
                        errors.addAll(CanonicalJobValidator.validate(carrier,activity));
                        TimeWindow window;double duration;List<String> skills;
                        if(activity instanceof Tour.ServiceActivity serviceActivity){
                            var job=carrier.getServices().get(serviceActivity.getService().getId());
                            if(job==null)throw new IllegalArgumentException("Unknown service "+serviceActivity.getService().getId());
                            if(!served.add(job.getId().toString()))errors.add("Duplicate service "+job.getId());
                            if(!job.getServiceLinkId().equals(activity.getLocation()))errors.add("Changed service location "+job.getId());
                            load-=job.getCapacityDemand();window=job.getServiceStaringTimeWindow();duration=job.getServiceDuration();skills=CarriersUtils.getSkills(job);
                        }else if(activity instanceof Tour.ShipmentBasedActivity shipmentActivity){
                            var job=carrier.getShipments().get(shipmentActivity.getShipment().getId());
                            if(job==null)throw new IllegalArgumentException("Unknown shipment "+shipmentActivity.getShipment().getId());
                            String id=job.getId().toString();skills=CarriersUtils.getSkills(job);
                            if(activity instanceof Tour.Pickup){
                                if(!pickups.add(id))errors.add("Duplicate shipment pickup "+id);
                                onboard.add(id);load+=job.getSize();window=job.getPickupStartingTimeWindow();duration=job.getPickupDuration();
                                if(!job.getPickupLinkId().equals(activity.getLocation()))errors.add("Changed pickup location "+id);
                            }else if(activity instanceof Tour.Delivery){
                                if(!onboard.remove(id))errors.add("Shipment delivery before pickup/on another vehicle "+id);
                                if(!served.add(id))errors.add("Duplicate shipment delivery "+id);
                                load-=job.getSize();window=job.getDeliveryStartingTimeWindow();duration=job.getDeliveryDuration();
                                if(!job.getDeliveryLinkId().equals(activity.getLocation()))errors.add("Changed delivery location "+id);
                            }else throw new IllegalArgumentException("Unsupported shipment activity");
                        }else throw new IllegalArgumentException("Unsupported tour activity");
                        checkLoad(load,capacity,errors);
                        if(!CarriersUtils.getSkills(prototype.getType()).containsAll(skills))errors.add("Missing vehicle skills at "+activity.getLocation());
                        if(Math.abs(activity.getDuration()-duration)>EPS)errors.add("Changed canonical job duration at "+activity.getLocation());
                        finite(duration,"activity duration");if(duration<0)errors.add("Negative service duration");
                        double waiting=Math.max(0,window.getStart()-time);time+=waiting;
                        if(time>window.getEnd()+EPS)errors.add("Missed time window at "+activity.getLocation());
                        Double waitRate=VehicleUtils.getCostsPerSecondWaiting(prototype.getType().getCostInformation());
                        Double serviceRate=VehicleUtils.getCostsPerSecondInService(prototype.getType().getCostInformation());
                        double base=prototype.getType().getCostInformation().getCostsPerSecond();
                        total+=waiting*(waitRate==null?base:waitRate)+duration*(serviceRate==null?base:serviceRate);
                        time+=duration;previousLeg=false;
                    }else errors.add("Unsupported tour element");
                }
                if(!previousLeg||!location.equals(tour.getEndLinkId()))errors.add("Missing final depot leg "+tour.getId());
                if(!onboard.isEmpty()||Math.abs(load)>EPS)errors.add("Undelivered load at depot "+tour.getId());
                if(time>vehicle.getLatestEndTime()+EPS)errors.add("Return after vehicle availability "+vehicle.getId());
                double duration=time-scheduled.getDeparture();
                if(duration>routeLimit+EPS)errors.add("Route duration exceeds limit "+tour.getId());
                if(config!=null&&duration>config.tourRules().maxDriverTimeSeconds()+EPS)errors.add("Driver duration exceeds limit "+tour.getId());
                boolean infinite=carrier.getCarrierCapabilities().getFleetSize()==CarrierCapabilities.FleetSize.INFINITE;
                // Native MATSim creates one distinct driver/vehicle for every infinite-template tour.
                if(!infinite){
                    var used=occupancy.computeIfAbsent(vehicle.getId().toString(),k->new ArrayList<>());
                    for(var interval:used)if(scheduled.getDeparture()<interval[1]-EPS && time>interval[0]+EPS)errors.add("Overlapping use of vehicle "+vehicle.getId());
                    used.add(new double[]{scheduled.getDeparture(),time});
                }
                if(infinite||chargedVehicles.add(vehicle.getId().toString()))total+=prototype.getType().getCostInformation().getFixedCosts();
            }catch(IllegalArgumentException|IllegalStateException|NullPointerException e){errors.add("Tour "+scheduled.getTour().getId()+": "+e.getMessage());}
        }
        var missing=new TreeSet<>(required);missing.removeAll(served);if(!missing.isEmpty())errors.add("Unserved jobs "+missing);
        var unexpected=new TreeSet<>(served);unexpected.removeAll(required);if(!unexpected.isEmpty())errors.add("Unknown served jobs "+unexpected);
        if(!Double.isFinite(total)||total<0)errors.add("Invalid final cost");
        return new Validation(errors.isEmpty(),served,errors,errors.isEmpty()?total:Double.POSITIVE_INFINITY);
    }
    private record Driven(Id<Link> destination,double seconds,double cost){}
    private static Driven replay(Tour.Leg leg,Id<Link> origin,double departure,String mode,Vehicle vehicle,VRPTransportCosts costs,double uTurn,hagrid.core.routing.sections.SectionRoadCosts sectionRoads){
        if(!(leg.getRoute() instanceof NetworkRoute route))throw new IllegalArgumentException("Missing native road route");
        if(!origin.equals(route.getStartLinkId()))throw new IllegalArgumentException("Road start differs from previous activity");
        if(route.getVehicleId()!=null&&!vehicle.getId().equals(route.getVehicleId()))throw new IllegalArgumentException("Road vehicle differs from scheduled vehicle");
        var ids=new ArrayList<Id<Link>>();ids.add(route.getStartLinkId());ids.addAll(route.getLinkIds());
        if(!route.getStartLinkId().equals(route.getEndLinkId())||!route.getLinkIds().isEmpty())ids.add(route.getEndLinkId());
        Network network=costs.getNetwork();
        var restrictions=TurnRestrictionValidator.validate(network,mode,ids);
        if(!restrictions.isEmpty())throw new IllegalArgumentException(String.join("; ",restrictions));
        Link previous=null;double time=departure,distance=0,penalty=0;
        for(int i=0;i<ids.size();i++){
            Link link=network.getLinks().get(ids.get(i));
            if(link==null||!link.getAllowedModes().contains(mode))throw new IllegalArgumentException("Missing/mode-forbidden road link "+ids.get(i));
            if(previous!=null){
                if(!previous.getToNode().getId().equals(link.getFromNode().getId()))throw new IllegalArgumentException("Disconnected stored road links "+previous.getId()+" -> "+link.getId());
                if(!previous.getId().equals(link.getId())) {
                    if(sectionRoads==null) {if(OriginalRoadReverse.roadTransition(previous,link))penalty+=uTurn;}
                    else if(sectionRoads.chargesTransition(previous.getId(),link.getId()))penalty+=uTurn;
                }
            }
            if(i>0){double seconds=costs.getTravelTime().getLinkTravelTime(link,time,null,vehicle);finite(seconds,"link travel time");
                if(seconds<0)throw new IllegalArgumentException("Negative link time");
                time+=seconds;distance+=link.getLength();}
            previous=link;
        }
        var rates=vehicle.getType().getCostInformation();
        return new Driven(route.getEndLinkId(),time-departure,distance*rates.getCostsPerMeter()+(time-departure)*rates.getCostsPerSecond()+penalty);
    }
    private static void checkLoad(double load,double capacity,List<String> errors){if(load<-EPS||load>capacity+EPS)errors.add("Capacity/load violation "+load+" / "+capacity);}
    private static void finite(double value,String label){if(!Double.isFinite(value))throw new IllegalArgumentException("Non-finite "+label);}
}
