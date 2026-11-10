package hagrid.core.routing.sections;
import java.util.*;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.freight.carriers.*;
import org.matsim.vehicles.*;

/** Exact orientation search for the static, tariff-optimal road policy. */
final class SectionRouteEvaluatorLegacy {
 private final SectionCatalog catalog; private final SectionRoadCosts roads;
 private final double routeLimit,driverLimit,turnPenalty;
 private final Map<String,DeliverySection> sections=new HashMap<>();
 private record Label(Id<Link> at,double time,double cost,Label previous,SectionVisit visit) {}
 public SectionRouteEvaluatorLegacy(SectionCatalog catalog,SectionRoadCosts roads,double routeLimit,double driverLimit,double uTurnPenalty) {
  this.catalog=Objects.requireNonNull(catalog);this.roads=Objects.requireNonNull(roads);
  this.routeLimit=routeLimit;this.driverLimit=driverLimit;this.turnPenalty=uTurnPenalty;
  if(uTurnPenalty<0||!Double.isFinite(uTurnPenalty))throw new IllegalArgumentException("Invalid U-turn tariff");
  for(var s:catalog.sections())if(sections.put(s.id(),s)!=null)throw new IllegalArgumentException("Duplicate section " + s.id());
  if(roads instanceof StaticSectionRoadCosts s && Double.compare(s.uTurnPenalty(),uTurnPenalty)!=0)throw new IllegalArgumentException("Road tariff differs from evaluator tariff");
 }
 public SectionEvaluation evaluate(List<String> order,CarrierVehicle vehicle,double departure) {
  Objects.requireNonNull(order);Objects.requireNonNull(vehicle);
  if(!Double.isFinite(departure)||departure<vehicle.getEarliestStartTime()||departure>vehicle.getLatestEndTime())return failure(0,0);
  var type=vehicle.getType();var rates=type.getCostInformation();
  Double waitValue=VehicleUtils.getCostsPerSecondWaiting(rates),serviceValue=VehicleUtils.getCostsPerSecondInService(rates);
  double wait=waitValue==null?rates.getCostsPerSecond():waitValue,service=serviceValue==null?rates.getCostsPerSecond():serviceValue;
  long demand=0;var seen=new HashSet<String>();for(String id:order){var s=sections.get(id);if(s==null||!seen.add(id))throw new IllegalArgumentException("Unknown or repeated section " + id);demand+=s.demand();}
  if(demand>type.getCapacity().getOther())return failure(0,0);
  List<Label> labels=List.of(new Label(roads.resolveOriginalLink(vehicle.getLinkId()),departure,0,null,null));long generated=0,maxRetained=1;
  var suffixRelease=new double[order.size()+1];
  for(int i=order.size()-1;i>=0;i--){
   double release=suffixRelease[i+1];
   for(String memberId:sections.get(order.get(i)).memberIds()){
    var member=catalog.originalCarrier().getServices().get(Id.create(memberId,CarrierService.class));
    if(member==null)throw new IllegalArgumentException("Missing service " + memberId);
    release=Math.max(release,member.getServiceStaringTimeWindow().getStart());
   }
   suffixRelease[i]=release;
  }
  for(int sectionIndex=0;sectionIndex<order.size();sectionIndex++) {
   String id=order.get(sectionIndex);final double release=suffixRelease[sectionIndex+1];   var section=sections.get(id);var next=new ArrayList<Label>();
   for(var label:labels) for(var variant:section.variants()) {
    generated++;if(variant.orderedJobIds().isEmpty()||variant.orderedJobIds().size()!=variant.orderedHaltLinks().size())throw new IllegalArgumentException("Invalid variant " + id);
    double t=label.time(),cost=label.cost(),arrival=Double.NaN;Id<Link> at=label.at();boolean valid=true;
    for(int i=0;i<variant.orderedJobIds().size();i++) {
     var halt=variant.orderedHaltLinks().get(i);var leg=roads.leg(at,halt,type);
     if(!Double.isFinite(leg.seconds())||!Double.isFinite(leg.meters())){valid=false;break;}
     t+=leg.seconds();cost+=leg.meters()*rates.getCostsPerMeter()+leg.seconds()*rates.getCostsPerSecond()+leg.uTurns()*turnPenalty;
     if(i==0)arrival=t;
     var member=catalog.originalCarrier().getServices().get(Id.create(variant.orderedJobIds().get(i),CarrierService.class));
     if(member==null)throw new IllegalArgumentException("Missing service " + variant.orderedJobIds().get(i));
     if(!CarriersUtils.getSkills(type).containsAll(CarriersUtils.getSkills(member))){valid=false;break;}
     double start=Math.max(t,member.getServiceStaringTimeWindow().getStart());
     if(start>member.getServiceStaringTimeWindow().getEnd()){valid=false;break;}
     cost+=(start-t)*wait+member.getServiceDuration()*service;t=start+member.getServiceDuration();at=halt;
    }
    if(!valid)continue;
    var candidate=new Label(at,t,cost,label,new SectionVisit(id,variant.index(),arrival,t));
    boolean dominated=false;for(var old:next)if(old.at().equals(at)&&dominates(old,candidate,wait,release)){dominated=true;break;}
    if(!dominated){next.removeIf(old->old.at().equals(candidate.at())&&dominates(candidate,old,wait,release));next.add(candidate);}
   }
   labels=next;maxRetained=Math.max(maxRetained,labels.size());if(labels.isEmpty())return failure(generated,maxRetained);
  }
  Label best=null;double bestEnd=0,bestCost=0;
  for(var label:labels){var leg=roads.leg(label.at(),roads.resolveOriginalLink(vehicle.getLinkId()),type);double end=label.time()+leg.seconds();
   if(!Double.isFinite(end)||end>vehicle.getLatestEndTime()||end-departure>routeLimit||end-departure>driverLimit)continue;
   double cost=label.cost()+leg.meters()*rates.getCostsPerMeter()+leg.seconds()*rates.getCostsPerSecond()+leg.uTurns()*turnPenalty;
   if(!Double.isFinite(cost))continue;
   if(best==null||cost<bestCost||cost==bestCost&&(end<bestEnd||end==bestEnd&&compareChoices(choices(label),choices(best))<0)){best=label;bestCost=cost;bestEnd=end;}
  }
  return best==null?failure(generated,maxRetained):new SectionEvaluation(true,bestCost,bestEnd,visits(best),generated,maxRetained);
 }
 private static boolean dominates(Label a,Label b,double wait,double release){double adjusted=a.cost()+(a.time()>=release?0:wait*(b.time()-a.time()));return a.time()<=b.time()&&(adjusted<b.cost()||adjusted==b.cost()&&compareChoices(choices(a),choices(b))<=0);}
 private static List<SectionVisit> visits(Label label){var result=new ArrayList<SectionVisit>();for(var p=label;p!=null&&p.visit()!=null;p=p.previous())result.add(p.visit());Collections.reverse(result);return List.copyOf(result);}
 private static List<Integer> choices(Label label){return visits(label).stream().map(SectionVisit::variant).toList();}
 private static int compareChoices(List<Integer>a,List<Integer>b){for(int i=0;i<Math.min(a.size(),b.size());i++){int c=Integer.compare(a.get(i),b.get(i));if(c!=0)return c;}return Integer.compare(a.size(),b.size());}
 private static SectionEvaluation failure(long generated,long retained){return new SectionEvaluation(false,Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,List.of(),generated,retained);}
}






