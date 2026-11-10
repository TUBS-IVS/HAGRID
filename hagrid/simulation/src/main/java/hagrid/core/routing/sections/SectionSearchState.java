package hagrid.core.routing.sections;

import com.graphhopper.jsprit.core.algorithm.state.StateUpdater;
import com.graphhopper.jsprit.core.problem.solution.route.RouteVisitor;
import com.graphhopper.jsprit.core.problem.solution.route.VehicleRoute;
import com.graphhopper.jsprit.core.problem.solution.route.activity.TourActivity;
import org.matsim.api.core.v01.Id;
import org.matsim.freight.carriers.*;
import org.matsim.vehicles.VehicleType;
import java.util.*;

/** Replays complete section orders, including a section-aware first-stop departure proposal. */
public final class SectionSearchState implements RouteVisitor, StateUpdater {
 public record Choice(double departure, SectionEvaluation evaluation) {}
 private record Profile(Id<VehicleType> id,String mode,double speed,Double capacity,Double fixed,Double perMeter,Double perSecond,Double waiting,Double service,Set<String> skills) {}
 private record Key(List<String> order, String vehicle, Profile type, String depot, double earliest, double latest, double departure) {}
 private final SectionCatalog catalog;
 private final SectionRoadCosts roads;
 private final SectionRouteEvaluator evaluator;
 private final SectionMemberIndex index; private final SectionSkillReader skills;
 private final double durationLimit,routeLimit,driverLimit,penalty;
 private static final int MAX_NUMERIC_REPLAYS=256;
 private final java.util.concurrent.atomic.LongAdder numericRetryEvaluations=new java.util.concurrent.atomic.LongAdder();
 private final java.util.concurrent.atomic.LongAdder numericRetryExhaustions=new java.util.concurrent.atomic.LongAdder();
 public long numericRetryEvaluations(){return numericRetryEvaluations.sum();}
 public long numericRetryExhaustions(){return numericRetryExhaustions.sum();}
 private static final int MAX_DEPARTURE_PREFIXES=500_000;
 private final Map<String,DeliverySection> sections;
 private final ThreadLocal<LinkedHashMap<Key,Choice>> cache=ThreadLocal.withInitial(()->new LinkedHashMap<>(128,.75f,true) {
  @Override protected boolean removeEldestEntry(Map.Entry<Key,Choice> e){return size()>1024;}
 });
 public SectionSearchState(SectionCatalog catalog, SectionRoadCosts roads, double routeLimit,double driverLimit,double penalty) {
  this.catalog=Objects.requireNonNull(catalog);this.roads=Objects.requireNonNull(roads);
  if(!Double.isFinite(routeLimit)||routeLimit<=0||!Double.isFinite(driverLimit)||driverLimit<=0)throw new IllegalArgumentException("Invalid section duration limit");
  this.routeLimit=routeLimit;this.driverLimit=driverLimit;this.penalty=penalty;durationLimit=Math.min(routeLimit,driverLimit);index=new SectionMemberIndex(catalog);skills=new SectionSkillReader();evaluator=new SectionRouteEvaluator(catalog,roads,routeLimit,driverLimit,penalty,index,skills);
  var byId=new HashMap<String,DeliverySection>(); for(var section:catalog.sections())byId.put(section.id(),section);sections=Map.copyOf(byId);
 }
 public Choice evaluate(List<String> order, CarrierVehicle vehicle, double departure) {
  var immutable=List.copyOf(order);var type=vehicle.getType();
  var rates=type.getCostInformation(); var profile=new Profile(type.getId(),type.getNetworkMode(),type.getMaximumVelocity(),type.getCapacity().getOther(),rates.getFixedCosts(),rates.getCostsPerMeter(),rates.getCostsPerSecond(),org.matsim.vehicles.VehicleUtils.getCostsPerSecondWaiting(rates),org.matsim.vehicles.VehicleUtils.getCostsPerSecondInService(rates),skills.skills(type));
  var key=new Key(immutable,vehicle.getId().toString(),profile,vehicle.getLinkId().toString(),vehicle.getEarliestStartTime(),vehicle.getLatestEndTime(),departure);
  var local=cache.get();var previous=local.get(key);if(previous!=null)return previous;
  var candidates=departureCandidates(immutable,vehicle);
  Choice best=null;var failed=new TreeSet<Double>();
  for(double candidate:candidates) {
   var evaluation=evaluator.evaluate(immutable,vehicle,candidate);
   if(!evaluation.feasible()){failed.add(candidate);continue;}
   if(best==null || compare(evaluation,candidate,best)<0)best=new Choice(candidate,evaluation);
  }
  if(best!=null){double selected=best.departure();failed.removeIf(d->d<=selected);}
  if(!failed.isEmpty() && potentiallyNumerical(immutable,vehicle,failed)){
   var nearby=searchNumericNeighborhood(immutable,vehicle,failed);
   if(nearby!=null && (best==null||compare(nearby.evaluation(),nearby.departure(),best)<0))best=nearby;
  }
  if(best==null)best=new Choice(vehicle.getEarliestStartTime(),evaluator.evaluate(immutable,vehicle,vehicle.getEarliestStartTime()));
  local.put(key,best);return best;
 }
 /** Conservative timing roundoff allowance used only to trigger extra strict replays. */
 private double numericEnvelope(List<String> order,CarrierVehicle vehicle) {
  int operations=2;for(String id:order)operations+=2*sections.get(id).memberIds().size()+1;
  double scale=Math.max(1,Math.max(Math.abs(vehicle.getEarliestStartTime()),Math.abs(vehicle.getLatestEndTime())));
  scale=Math.max(scale,Math.max(routeLimit,driverLimit));
  return Math.ulp(scale)*Math.max(32.0,8.0*operations);
 }
 private boolean potentiallyNumerical(List<String> order,CarrierVehicle vehicle,SortedSet<Double> candidates) {
  if(order.isEmpty())return false;var bindings=index.bindings();long demand=0;var type=vehicle.getType();double error=numericEnvelope(order,vehicle);boolean tight=false;
  for(String id:order){var section=sections.get(id);demand+=section.demand();for(int slot:index.sections.get(id).slots()){
   var service=index.resolve(catalog,bindings,slot);
   if(!skills.skills(type).containsAll(skills.skills(service)))return false;
   double release=service.getServiceStaringTimeWindow().getStart(),end=service.getServiceStaringTimeWindow().getEnd();
   if(end-release<=error || Math.abs(release-vehicle.getLatestEndTime())<=error)tight=true;
   for(double d:candidates)if(d>vehicle.getEarliestStartTime() && (Math.abs(d+routeLimit-release)<=error || Math.abs(d+driverLimit-release)<=error || (end<=vehicle.getLatestEndTime()+error && (Math.abs(d+routeLimit-end)<=error || Math.abs(d+driverLimit-end)<=error))))tight=true;
  }}
  if(demand>type.getCapacity().getOther())return false;
  if(!tight){
   var relaxed=CarrierVehicle.Builder.newInstance(vehicle.getId(),vehicle.getLinkId(),type).setEarliestStart(vehicle.getEarliestStartTime()).setLatestEnd(Double.MAX_VALUE).build();
   var replay=new SectionRouteEvaluator(catalog,roads,Double.MAX_VALUE,Double.MAX_VALUE,penalty,index,skills);
   for(double d:candidates){var result=replay.evaluate(order,relaxed,d);if(result.feasible()){
    double end=result.endTime(),duration=end-d;
    if(Math.abs(end-vehicle.getLatestEndTime())<=error || Math.abs(duration-routeLimit)<=error || Math.abs(duration-driverLimit)<=error){tight=true;break;}
   }}
  }
  return tight && hasConnectedTrace(order,vehicle);
 }
 /** Reject disconnected orders as such, without calling their failed replay a rounding ambiguity. */
 private boolean hasConnectedTrace(List<String> order,CarrierVehicle vehicle) {
  Set<org.matsim.api.core.v01.Id<org.matsim.api.core.v01.network.Link>> ends=Set.of(roads.resolveOriginalLink(vehicle.getLinkId()));
  for(String id:order){var next=new HashSet<org.matsim.api.core.v01.Id<org.matsim.api.core.v01.network.Link>>();
   for(var from:ends)for(var variant:sections.get(id).variants()){
    var at=from;boolean connected=true;for(var halt:variant.orderedHaltLinks()){if(!Double.isFinite(roads.leg(at,halt,vehicle.getType()).seconds())){connected=false;break;}at=halt;}
    if(connected)next.add(at);
   }
   ends=next;if(ends.isEmpty())return false;
  }
  var depot=roads.resolveOriginalLink(vehicle.getLinkId());for(var end:ends)if(Double.isFinite(roads.leg(end,depot,vehicle.getType()).seconds()))return true;return false;
 }
 /** Strictly replay neighboring binary64 departures; never relax a service or shift boundary. */
 private Choice searchNumericNeighborhood(List<String> order,CarrierVehicle vehicle,SortedSet<Double> candidates) {
  double error=numericEnvelope(order,vehicle),earliest=vehicle.getEarliestStartTime(),latest=vehicle.getLatestEndTime();
  var anchors=new ArrayList<>(candidates);var down=new ArrayList<>(anchors);var up=new ArrayList<>(anchors);int replays=0;
  while(replays<MAX_NUMERIC_REPLAYS){boolean advanced=false;
   for(int i=0;i<anchors.size();i++){
    double anchor=anchors.get(i),left=Math.nextDown(down.get(i)),right=Math.nextUp(up.get(i));down.set(i,left);up.set(i,right);
    for(double d:new double[]{left,right})if(Double.isFinite(d)&&d>=earliest&&d<=latest&&Math.abs(d-anchor)<=error){
     advanced=true;if(++replays>MAX_NUMERIC_REPLAYS)break;numericRetryEvaluations.increment();var evaluation=evaluator.evaluate(order,vehicle,d);if(evaluation.feasible())return new Choice(d,evaluation);
    }
    if(replays>=MAX_NUMERIC_REPLAYS)break;
   }
   if(!advanced)break;
  }
  numericRetryExhaustions.increment();return null;
 }
 /** A prefix represents every departure-independent timing constraint of one orientation path. */
 private record Prefix(org.matsim.api.core.v01.Id<org.matsim.api.core.v01.network.Link> at,double elapsed,double barrier,double upper) {}
 private SortedSet<Double> departureCandidates(List<String> order,CarrierVehicle vehicle) {
  var result=new TreeSet<Double>();double earliest=vehicle.getEarliestStartTime(),latest=vehicle.getLatestEndTime();addCandidate(result,earliest,earliest,latest);
  if(order.isEmpty())return result;
  var bindings=index.bindings();Double common=null;boolean sameRelease=true;
  for(String sectionId:order)for(int slot:Objects.requireNonNull(index.sections.get(sectionId),"unknown section").slots()) {
   var service=index.resolve(catalog,bindings,slot);
   double release=service.getServiceStaringTimeWindow().getStart();if(common==null)common=release;else if(Double.compare(common,release)!=0)sameRelease=false;
  }
  var rates=vehicle.getType().getCostInformation();Double configured=org.matsim.vehicles.VehicleUtils.getCostsPerSecondWaiting(rates);
  double waiting=configured==null?rates.getCostsPerSecond():configured;
  if(sameRelease && common!=null && waiting>0) {
   // A common release can cause waiting only before the first member. Once the
   // vehicle reaches that member at the release, all later release barriers are satisfied.
   for(var variant:sections.get(order.getFirst()).variants()) {
    var leg=roads.leg(roads.resolveOriginalLink(vehicle.getLinkId()),variant.orderedHaltLinks().getFirst(),vehicle.getType());
    if(Double.isFinite(leg.seconds()))addCandidate(result,Math.max(earliest,common-leg.seconds()),earliest,latest);
   }
   return result;
  }
  Set<Prefix> frontier=Set.of(new Prefix(roads.resolveOriginalLink(vehicle.getLinkId()),0,Double.NEGATIVE_INFINITY,Double.POSITIVE_INFINITY));
  for(String sectionId:order) {
   var next=new HashSet<Prefix>();
   for(var prefix:frontier)for(var variant:index.sections.get(sectionId).variants()) {
    var at=prefix.at();double elapsed=prefix.elapsed(),barrier=prefix.barrier(),upper=prefix.upper();boolean possible=true;
    for(int i=0;i<variant.slots().length;i++) {
     var halt=variant.halts().get(i);var leg=roads.leg(at,halt,vehicle.getType());
     if(!Double.isFinite(leg.seconds())){possible=false;break;}
     if(leg.seconds()<0)throw new IllegalArgumentException("Negative section road time");
     elapsed+=leg.seconds();var service=index.resolve(catalog,bindings,variant.slots()[i]);
     barrier=Math.max(barrier,service.getServiceStaringTimeWindow().getStart()-elapsed);
     upper=Math.min(upper,service.getServiceStaringTimeWindow().getEnd()-elapsed);
     if(barrier>upper+1e-9){possible=false;break;}
     elapsed+=service.getServiceDuration();at=halt;
    }
    if(possible)next.add(new Prefix(at,elapsed,barrier,upper));
    if(next.size()>MAX_DEPARTURE_PREFIXES)throw new IllegalStateException("Exact section departure prefix limit exceeded: "+MAX_DEPARTURE_PREFIXES);
   }
   frontier=next;if(frontier.isEmpty())return result;
  }
  var depot=roads.resolveOriginalLink(vehicle.getLinkId());
  for(var prefix:frontier) {
   var leg=roads.leg(prefix.at(),depot,vehicle.getType());if(!Double.isFinite(leg.seconds()))continue;
   if(leg.seconds()<0)throw new IllegalArgumentException("Negative section road time");
   double total=prefix.elapsed()+leg.seconds(),barrier=prefix.barrier();if(total>durationLimit+1e-9||barrier+total>latest+1e-9)continue;
   double lower=Math.max(earliest,barrier+total-durationLimit),upper=Math.min(prefix.upper(),latest-total);
   if(lower>upper+1e-9)continue;
   addCandidate(result,lower,earliest,latest);addCandidate(result,upper,earliest,latest);
   addCandidate(result,Math.max(lower,Math.min(upper,barrier)),earliest,latest);
  }
  return result;
 }
 private static void addCandidate(Set<Double> out,double value,double earliest,double latest) {
  if(!Double.isFinite(value))return;
  for(double d:new double[]{value,Math.nextUp(value)})if(d>=earliest&&d<=latest)out.add(d);
 }
 private static int compare(SectionEvaluation a,double departure,Choice b) {
  int c=Double.compare(a.variableCost(),b.evaluation().variableCost());if(c!=0)return c;
  c=Double.compare(a.endTime(),b.evaluation().endTime());if(c!=0)return c;
  var x=a.visits();var y=b.evaluation().visits();for(int i=0;i<Math.min(x.size(),y.size());i++){c=Integer.compare(x.get(i).variant(),y.get(i).variant());if(c!=0)return c;}
  c=Integer.compare(x.size(),y.size());return c!=0?c:Double.compare(departure,b.departure());
 }
 public static List<String> order(VehicleRoute route) {
  var result=new ArrayList<String>();for(var a:route.getActivities())result.add(((TourActivity.JobActivity)a).getJob().getId());return List.copyOf(result);
 }
 public CarrierVehicle vehicle(com.graphhopper.jsprit.core.problem.vehicle.Vehicle vehicle) {
  if(!(vehicle.getType().getUserData() instanceof VehicleType type))throw new IllegalArgumentException("Missing MATSim vehicle type for "+vehicle.getId());
  return CarrierVehicle.Builder.newInstance(Id.createVehicleId(vehicle.getId()),Id.createLinkId(vehicle.getStartLocation().getId()),type)
   .setEarliestStart(vehicle.getEarliestDeparture()).setLatestEnd(vehicle.getLatestArrival()).build();
 }
 public Choice evaluate(VehicleRoute route) {return evaluate(order(route),vehicle(route.getVehicle()),route.getDepartureTime());}
 @Override public void visit(VehicleRoute route) {
  if(route.isEmpty())return;var choice=evaluate(route);
  if(choice.evaluation().feasible())route.setVehicleAndDepartureTime(route.getVehicle(),choice.departure());
 }
}







