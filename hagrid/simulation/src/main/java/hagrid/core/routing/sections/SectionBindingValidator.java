package hagrid.core.routing.sections;

import java.util.*;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.freight.carriers.*;

/** Checks that a projected executable carrier still represents precisely the detached originals. */
public final class SectionBindingValidator {
 private SectionBindingValidator() {}
 public static List<String> validateProjection(SectionCatalog catalog,Carrier projected,Map<String,Id<Link>> bindings) {
  catalog.requireUnchanged();Objects.requireNonNull(projected);Objects.requireNonNull(bindings);
  var errors=new ArrayList<String>();var originals=catalog.originalCarrier().getServices();
  var expected=new TreeSet<String>();originals.keySet().forEach(id->expected.add(id.toString()));
  if(!catalog.originalCarrier().getId().equals(projected.getId()))errors.add("Changed carrier ID");
  if(!projected.getShipments().isEmpty())errors.add("Unexpected shipments");
  var projectedIds=new TreeSet<String>();projected.getServices().keySet().forEach(id->projectedIds.add(id.toString()));
  if(!expected.equals(projectedIds))errors.add("Projected job IDs differ from originals");
  if(!expected.equals(bindings.keySet()))errors.add("Binding IDs differ from originals");
  var allowed=new HashMap<String,Set<Id<Link>>>();var members=new HashSet<String>();
  for(var section:catalog.sections()) {
   for(var id:section.memberIds())if(!members.add(id))errors.add("Duplicate catalog member "+id);
   for(var variant:section.variants()) {
    if(variant.orderedJobIds().size()!=variant.orderedHaltLinks().size() || !new HashSet<>(variant.orderedJobIds()).equals(new HashSet<>(section.memberIds())) || variant.orderedJobIds().size()!=section.memberIds().size()) {errors.add("Invalid section variant "+section.id());continue;}
    for(int i=0;i<variant.orderedJobIds().size();i++)allowed.computeIfAbsent(variant.orderedJobIds().get(i),k->new HashSet<>()).add(variant.orderedHaltLinks().get(i));
   }
  }
  if(!members.equals(expected))errors.add("Catalog membership differs from originals");
  for(var original:originals.values()) {
   String id=original.getId().toString();var selected=bindings.get(id);var copy=projected.getServices().get(original.getId());
   if(selected==null || !allowed.getOrDefault(id,Set.of()).contains(selected))errors.add("Unauthorized binding "+id);
   if(copy==null)continue;
   if(!Objects.equals(selected,copy.getServiceLinkId()))errors.add("Projected link differs from binding "+id);
   var a=original.getServiceStaringTimeWindow();var b=copy.getServiceStaringTimeWindow();
   if(original.getCapacityDemand()!=copy.getCapacityDemand() || Double.compare(original.getServiceDuration(),copy.getServiceDuration())!=0 || b==null || Double.compare(a.getStart(),b.getStart())!=0 || Double.compare(a.getEnd(),b.getEnd())!=0 || !SplitRoadNetworkBuilder.canonical(original.getAttributes().getAsMap()).equals(SplitRoadNetworkBuilder.canonical(copy.getAttributes().getAsMap())))errors.add("Changed original service "+id);
  }
  return List.copyOf(errors);
 }
 public static List<String> validatePlan(SectionCatalog catalog,Carrier projected,CarrierPlan plan,Map<String,Id<Link>> bindings) {
  var errors=new ArrayList<>(validateProjection(catalog,projected,bindings));var seen=new HashSet<String>();
  if(plan==null){errors.add("Missing plan");return List.copyOf(errors);}
  var byMember=new HashMap<String,DeliverySection>();for(var section:catalog.sections())for(var id:section.memberIds())byMember.put(id,section);
  for(var tour:plan.getScheduledTours()) {
   var jobs=new ArrayList<String>();
   for(var e:tour.getTour().getTourElements())if(e instanceof Tour.ServiceActivity a){String id=a.getService().getId().toString();jobs.add(id);if(!seen.add(id))errors.add("Duplicate service "+id);if(!Objects.equals(bindings.get(id),a.getLocation()))errors.add("Activity differs from binding "+id);}else if(e instanceof Tour.TourActivity)errors.add("Unexpected activity");
   for(int i=0;i<jobs.size();) {
    var section=byMember.get(jobs.get(i));if(section==null){errors.add("Unknown member "+jobs.get(i));i++;continue;}
    int end=i+section.memberIds().size();if(end>jobs.size()){errors.add("Incomplete section "+section.id());break;}
    var sequence=jobs.subList(i,end);boolean match=section.variants().stream().anyMatch(v->v.orderedJobIds().equals(sequence) && java.util.stream.IntStream.range(0,sequence.size()).allMatch(j->Objects.equals(bindings.get(sequence.get(j)),v.orderedHaltLinks().get(j))));
    if(!match)errors.add("Invalid section order or orientation "+section.id());i=end;
   }
  }
  if(!seen.equals(bindings.keySet()))errors.add("Served IDs differ from bindings");
  return List.copyOf(errors);
 }
}
