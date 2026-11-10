package hagrid.core.routing.sections;
import java.util.*;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.freight.carriers.*;
import org.matsim.vehicles.*;

/** Exact orientation search for the static, tariff-optimal road policy. */
public final class SectionRouteEvaluator {
 private final SectionCatalog catalog; private final SectionRoadCosts roads;
 private final double routeLimit,driverLimit,turnPenalty;
 private final SectionMemberIndex index; private final Map<String,SectionMemberIndex.Section> sections; private final SectionSkillReader skills;
 private static final class Label {
  private final Id<Link> at; private final double time,cost; private final Label previous; private final String sectionId; private final int variant; private final double arrival; private int lexRank;
  private Label(Id<Link> at,double time,double cost,Label previous,String sectionId,int variant,double arrival){this.at=at;this.time=time;this.cost=cost;this.previous=previous;this.sectionId=sectionId;this.variant=variant;this.arrival=arrival;}
  Id<Link> at(){return at;} double time(){return time;} double cost(){return cost;} Label previous(){return previous;}
 }
 public SectionRouteEvaluator(SectionCatalog catalog,SectionRoadCosts roads,double routeLimit,double driverLimit,double uTurnPenalty) {
  this(catalog,roads,routeLimit,driverLimit,uTurnPenalty,new SectionMemberIndex(catalog),new SectionSkillReader());
 }
 SectionRouteEvaluator(SectionCatalog catalog,SectionRoadCosts roads,double routeLimit,double driverLimit,double uTurnPenalty,SectionMemberIndex index,SectionSkillReader skills) {
  this.catalog=Objects.requireNonNull(catalog);this.roads=Objects.requireNonNull(roads);this.index=index;this.sections=index.sections;this.skills=skills;
  this.routeLimit=routeLimit;this.driverLimit=driverLimit;this.turnPenalty=uTurnPenalty;
  if(uTurnPenalty<0||!Double.isFinite(uTurnPenalty))throw new IllegalArgumentException("Invalid U-turn tariff");
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
  List<Label> labels=List.of(new Label(roads.resolveOriginalLink(vehicle.getLinkId()),departure,0,null,null,0,0));long generated=0,maxRetained=1;
  var bindings=index.bindings();var suffixRelease=new double[order.size()+1];
  for(int i=order.size()-1;i>=0;i--){
   double release=suffixRelease[i+1];
   for(int slot:sections.get(order.get(i)).slots()){
    var member=index.resolve(catalog,bindings,slot);
    release=Math.max(release,member.getServiceStaringTimeWindow().getStart());
   }
   suffixRelease[i]=release;
  }
  for(int sectionIndex=0;sectionIndex<order.size();sectionIndex++) {
   String id=order.get(sectionIndex);final double release=suffixRelease[sectionIndex+1];   var section=sections.get(id);var next=new ArrayList<Label>();
   boolean batch=labels.size()>=16;var candidates=batch?new ArrayList<Label>():null;boolean batchSafe=batch;
   for(int labelIndex=0;labelIndex<labels.size();labelIndex++) for(int variantIndex=0;variantIndex<section.variants().size();variantIndex++) {
    var label=labels.get(labelIndex);var variant=section.variants().get(variantIndex);
    generated++;if(variant.slots().length==0||variant.slots().length!=variant.halts().size())throw new IllegalArgumentException("Invalid variant " + id);
    double t=label.time(),cost=label.cost(),arrival=Double.NaN;Id<Link> at=label.at();boolean valid=true;
    for(int i=0;i<variant.slots().length;i++) {
     var halt=variant.halts().get(i);var leg=roads.leg(at,halt,type);
     if(!Double.isFinite(leg.seconds())||!Double.isFinite(leg.meters())){valid=false;break;}
     t+=leg.seconds();cost+=leg.meters()*rates.getCostsPerMeter()+leg.seconds()*rates.getCostsPerSecond()+leg.chargedTurns()*turnPenalty;
     if(i==0)arrival=t;
     var member=index.resolve(catalog,bindings,variant.slots()[i]);
     if(!skills.skills(type).containsAll(skills.skills(member))){valid=false;break;}
     double start=Math.max(t,member.getServiceStaringTimeWindow().getStart());
     if(start>member.getServiceStaringTimeWindow().getEnd()){valid=false;break;}
     cost+=(start-t)*wait+member.getServiceDuration()*service;t=start+member.getServiceDuration();at=halt;
    }
    if(!valid)continue;
    var candidate=new Label(at,t,cost,label,id,variant.index(),arrival);
    if(batch){candidates.add(candidate);if(!Double.isFinite(t)||!Double.isFinite(cost)||!(t>=release))batchSafe=false;}
    else addCandidate(next,candidate,wait,release);
   }
   if(batch){if(batchSafe)next=batchPrune(candidates);else for(var candidate:candidates)addCandidate(next,candidate,wait,release);}
   rankSurvivors(next);labels=next;maxRetained=Math.max(maxRetained,labels.size());if(labels.isEmpty())return failure(generated,maxRetained);
  }
  Label best=null;double bestEnd=0,bestCost=0;
  for(int labelIndex=0;labelIndex<labels.size();labelIndex++){var label=labels.get(labelIndex);var leg=roads.leg(label.at(),roads.resolveOriginalLink(vehicle.getLinkId()),type);double end=label.time()+leg.seconds();
   if(!Double.isFinite(end)||end>vehicle.getLatestEndTime()||end-departure>routeLimit||end-departure>driverLimit)continue;
   double cost=label.cost()+leg.meters()*rates.getCostsPerMeter()+leg.seconds()*rates.getCostsPerSecond()+leg.chargedTurns()*turnPenalty;
   if(!Double.isFinite(cost))continue;
   if(best==null||cost<bestCost||cost==bestCost&&(end<bestEnd||end==bestEnd&&Integer.compare(label.lexRank,best.lexRank)<0)){best=label;bestCost=cost;bestEnd=end;}
  }
  return best==null?failure(generated,maxRetained):new SectionEvaluation(true,bestCost,bestEnd,visits(best),generated,maxRetained);
 }
 // Preserve the original incremental insertion semantics for every fallback case.
 private static void addCandidate(ArrayList<Label> next,Label candidate,double wait,double release){
  boolean dominated=false;for(int i=0;i<next.size();i++){var old=next.get(i);if(old.at().equals(candidate.at())&&dominates(old,candidate,wait,release)){dominated=true;break;}}
  if(!dominated){int size=next.size(),write=0;for(int read=0;read<size;read++){var old=next.get(read);if(!(old.at().equals(candidate.at())&&dominates(candidate,old,wait,release))){if(write!=read)next.set(write,old);write++;}}for(int i=size-1;i>=write;i--)next.remove(i);next.add(candidate);}
 }
 // Finite, post-release candidates have ordinary time/cost dominance. Sort within
 // each endpoint, then restore generation order before assigning lexicographic ranks.
 private static ArrayList<Label> batchPrune(List<Label> candidates){
  var groups=new HashMap<Id<Link>,ArrayList<Integer>>();for(int i=0;i<candidates.size();i++)groups.computeIfAbsent(candidates.get(i).at(),k->new ArrayList<>()).add(i);
  boolean[] keep=new boolean[candidates.size()];
  for(var group:groups.values()){
   group.sort((i,j)->{var a=candidates.get(i);var b=candidates.get(j);int c=a.time()<b.time()?-1:a.time()>b.time()?1:0;if(c!=0)return c;c=a.cost()<b.cost()?-1:a.cost()>b.cost()?1:0;if(c!=0)return c;c=comparePair(a,b);return c!=0?c:Integer.compare(i,j);});
   Label best=null;for(int i:group){var x=candidates.get(i);boolean dominated=best!=null&&(best.cost()<x.cost()||best.cost()==x.cost()&&comparePair(best,x)<=0);if(!dominated)keep[i]=true;if(best==null||x.cost()<best.cost()||x.cost()==best.cost()&&comparePair(x,best)<0)best=x;}
  }
  var result=new ArrayList<Label>();for(int i=0;i<candidates.size();i++)if(keep[i])result.add(candidates.get(i));return result;
 }
 private static boolean dominates(Label a,Label b,double wait,double release){double adjusted=a.cost()+(a.time()>=release?0:wait*(b.time()-a.time()));return a.time()<=b.time()&&(adjusted<b.cost()||adjusted==b.cost()&&comparePair(a,b)<=0);}
 private static List<SectionVisit> visits(Label label){var result=new ArrayList<SectionVisit>();for(var p=label;p.previous()!=null;p=p.previous())result.add(new SectionVisit(p.sectionId,p.variant,p.arrival,p.time));Collections.reverse(result);return List.copyOf(result);}
 private static int comparePair(Label a,Label b){int c=Integer.compare(a.previous.lexRank,b.previous.lexRank);return c!=0?c:Integer.compare(a.variant,b.variant);}
 private static void rankSurvivors(List<Label> labels){
  var sorted=labels.toArray(Label[]::new);Arrays.sort(sorted,SectionRouteEvaluator::comparePair);int rank=0;
  for(int i=0;i<sorted.length;i++){if(i>0&&comparePair(sorted[i-1],sorted[i])!=0)rank++;sorted[i].lexRank=rank;}
 }
 private static SectionEvaluation failure(long generated,long retained){return new SectionEvaluation(false,Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,List.of(),generated,retained);}
}






