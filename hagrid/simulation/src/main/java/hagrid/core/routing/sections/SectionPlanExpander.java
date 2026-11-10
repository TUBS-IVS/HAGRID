package hagrid.core.routing.sections;

import hagrid.core.routing.CarrierOptimizationService;
import java.util.*;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.freight.carriers.*;
import org.matsim.vehicles.*;

/** Expand winning macro tours into original services and their exact selected roads. */
public final class SectionPlanExpander {
 private static final double EPS=1e-6;
 private SectionPlanExpander() {}
 public record ExpandedPlan(Carrier carrier,CarrierPlan plan,Map<String,Id<Link>> bindings) {
  public ExpandedPlan {bindings=Map.copyOf(bindings);}
 }
 public static ExpandedPlan expand(CarrierOptimizationService.SectionSearchResult result,SectionCatalog catalog,SectionRoadCosts roads) {
  Objects.requireNonNull(result);catalog.requireUnchanged();
  if(!result.solution().getUnassignedJobs().isEmpty())throw new IllegalArgumentException("Unassigned section jobs");
  var sections=new HashMap<String,DeliverySection>();for(var s:catalog.sections())sections.put(s.id(),s);
  var bindings=new LinkedHashMap<String,Id<Link>>();var selected=new IdentityHashMap<com.graphhopper.jsprit.core.problem.solution.route.VehicleRoute,List<SectionVariant>>();
  for(var route:result.solution().getRoutes()) {
   var choice=Objects.requireNonNull(result.evaluations().get(route),"Missing route evaluation");
   if(!choice.evaluation().feasible() || Double.compare(choice.departure(),route.getDepartureTime())!=0)throw new IllegalArgumentException("Invalid section evaluation");
   var order=SectionSearchState.order(route);var visits=choice.evaluation().visits();if(order.size()!=visits.size())throw new IllegalArgumentException("Section evaluation/order mismatch");
   var variants=new ArrayList<SectionVariant>();
   for(int i=0;i<order.size();i++) {
    var visit=visits.get(i);if(!order.get(i).equals(visit.sectionId()))throw new IllegalArgumentException("Section visit/order mismatch");
    var section=Objects.requireNonNull(sections.get(visit.sectionId()),"Unknown section");
    var variant=section.variants().stream().filter(v->v.index()==visit.variant()).findFirst().orElseThrow();variants.add(variant);
    for(int j=0;j<variant.orderedJobIds().size();j++)if(bindings.putIfAbsent(variant.orderedJobIds().get(j),variant.orderedHaltLinks().get(j))!=null)throw new IllegalArgumentException("Duplicate section member "+variant.orderedJobIds().get(j));
   }
   selected.put(route,List.copyOf(variants));
  }
  // The projection owns another deep snapshot, never the guarded canonical object.
  var carrier=new SectionCatalog(catalog.originalCarrier(),catalog.sections(),catalog.fingerprint()).originalCarrier();
  carrier.getAttributes().putAttribute(hagrid.core.routing.SectionBindingCompanion.MARKER,catalog.fingerprint());
  var originals=new ArrayList<>(carrier.getServices().values());carrier.getServices().clear();
  for(var original:originals) {
   var halt=bindings.get(original.getId().toString());if(halt==null)throw new IllegalArgumentException("Unserved member "+original.getId());
   var job=CarrierService.Builder.newInstance(original.getId(),halt,original.getCapacityDemand()).setServiceDuration(original.getServiceDuration()).setServiceStartingTimeWindow(original.getServiceStaringTimeWindow()).build();
   original.getAttributes().getAsMap().forEach(job.getAttributes()::putAttribute);carrier.getServices().put(job.getId(),job);
  }
  var oldFleet=carrier.getCarrierCapabilities();var fleet=CarrierCapabilities.Builder.newInstance().setFleetSize(oldFleet.getFleetSize());
  for(var v:oldFleet.getCarrierVehicles().values()) {
   var copy=CarrierVehicle.Builder.newInstance(v.getId(),roads.resolveOriginalLink(v.getLinkId()),v.getType()).setEarliestStart(v.getEarliestStartTime()).setLatestEnd(v.getLatestEndTime()).build();
   v.getAttributes().getAsMap().forEach(copy.getAttributes()::putAttribute);fleet.addVehicle(copy);
  }
  carrier.setCarrierCapabilities(fleet.build());var tours=new ArrayList<ScheduledTour>();int serial=0;
  for(var route:result.solution().getRoutes()) {
   var nativeVehicle=route.getVehicle();var template=carrier.getCarrierCapabilities().getCarrierVehicles().values().stream().filter(v->
    (carrier.getCarrierCapabilities().getFleetSize()==CarrierCapabilities.FleetSize.INFINITE || v.getId().toString().equals(nativeVehicle.getId()))
    && v.getType().getId().toString().equals(nativeVehicle.getType().getTypeId()) && v.getLinkId().toString().equals(nativeVehicle.getStartLocation().getId())
    && Double.compare(v.getEarliestStartTime(),nativeVehicle.getEarliestDeparture())==0 && Double.compare(v.getLatestEndTime(),nativeVehicle.getLatestArrival())==0).findFirst().orElseThrow(()->new IllegalArgumentException("Native vehicle does not match projected fleet"));
   var vehicle=VehicleUtils.createVehicle(template.getId(),template.getType());var choice=result.evaluations().get(route);
   var builder=Tour.Builder.newInstance(Id.create("section-tour:"+(serial++),Tour.class));builder.scheduleStart(template.getLinkId());
   double time=choice.departure(), cost=0;Id<Link> at=template.getLinkId();var arrivals=new ArrayList<Double>();
   var rates=template.getType().getCostInformation();Double wr=VehicleUtils.getCostsPerSecondWaiting(rates),sr=VehicleUtils.getCostsPerSecondInService(rates);double waitRate=wr==null?rates.getCostsPerSecond():wr,serviceRate=sr==null?rates.getCostsPerSecond():sr;
   for(var variant:selected.get(route))for(int i=0;i<variant.orderedJobIds().size();i++) {
    var id=variant.orderedJobIds().get(i);var service=carrier.getServices().get(Id.create(id,CarrierService.class));var halt=variant.orderedHaltLinks().get(i);
    var road=roads.materialize(at,halt,time,vehicle);var scalar=roads.leg(at,halt,template.getType());checkRoad(road,scalar);
    var nativeRoad=road.route();nativeRoad.setVehicleId(null);builder.addLeg(builder.createLeg(nativeRoad,time,road.travelTimeSeconds()));
    time+=road.travelTimeSeconds();cost+=road.monetaryCost();arrivals.add(time);builder.scheduleService(service);double start=Math.max(time,service.getServiceStaringTimeWindow().getStart());cost+=(start-time)*waitRate+service.getServiceDuration()*serviceRate;time=start+service.getServiceDuration();at=halt;
   }
   var road=roads.materialize(at,template.getLinkId(),time,vehicle);checkRoad(road,roads.leg(at,template.getLinkId(),template.getType()));
   var nativeRoad=road.route();nativeRoad.setVehicleId(null);builder.addLeg(builder.createLeg(nativeRoad,time,road.travelTimeSeconds()));time+=road.travelTimeSeconds();cost+=road.monetaryCost();builder.scheduleEnd(template.getLinkId());
   if(Math.abs(time-choice.evaluation().endTime())>EPS || Math.abs(cost-choice.evaluation().variableCost())>EPS)throw new IllegalStateException("Expanded time or cost differs from selected evaluation");
   var tour=builder.build();int activity=0;for(var e:tour.getTourElements())if(e instanceof Tour.ServiceActivity a)a.setExpectedArrival(arrivals.get(activity++));
   tours.add(ScheduledTour.newInstance(tour,template,choice.departure()));
  }
  var plan=new CarrierPlan(tours);plan.setCarrier(carrier);plan.setScore(result.solution().getCost());
  var violations=SectionBindingValidator.validatePlan(catalog,carrier,plan,bindings);if(!violations.isEmpty())throw new IllegalStateException("Invalid expanded bindings: "+violations);
  catalog.requireUnchanged();return new ExpandedPlan(carrier,plan,bindings);
 }
 private static void checkRoad(hagrid.core.routing.RoadLeg road,SectionLeg scalar) {
  if(Math.abs(road.distanceMeters()-scalar.meters())>EPS || Math.abs(road.travelTimeSeconds()-scalar.seconds())>EPS)throw new IllegalStateException("Materialized road differs from section scalars");
 }
}
