package hagrid.core.routing.sections;

import hagrid.core.routing.RoadLeg;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.freight.carriers.*;
import org.matsim.vehicles.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class SectionPerformanceOracleTest {
 @Test void exhaustiveEightSectionOracleComparesCompleteTrace() {
  var carrier=CarriersUtils.createCarrier(Id.create("perf-oracle",Carrier.class));
  var type=VehicleUtils.createVehicleType(Id.create("perf-type",VehicleType.class),"car");
  type.getCapacity().setOther(20);type.getCostInformation().setCostsPerMeter(1.0).setCostsPerSecond(0.0);
  VehicleUtils.setCostsPerSecondWaiting(type.getCostInformation(),2.0);
  VehicleUtils.setCostsPerSecondInService(type.getCostInformation(),0.0);
  var vehicle=CarrierVehicle.Builder.newInstance(Id.createVehicleId("perf-v"),Id.createLinkId("d"),type).setEarliestStart(0).setLatestEnd(120).build();
  var sections=new ArrayList<DeliverySection>();
  for(int i=0;i<8;i++){
   String job="j"+i;CarriersUtils.addService(carrier,CarrierService.Builder.newInstance(Id.create(job,CarrierService.class),Id.createLinkId("d"))
    .setCapacityDemand(1).setServiceDuration(2).setServiceStartingTimeWindow(TimeWindow.newInstance(i*7,80+i*5)).build());
   sections.add(new DeliverySection("s"+i,List.of(job),1,List.of(
    new SectionVariant(13,List.of(job),List.of(Id.createLinkId(i+"a"))),
    new SectionVariant(-4,List.of(job),List.of(Id.createLinkId(i+"b"))))));
  }
  var roadCalls=new ArrayList<String>();
  SectionRoadCosts roads=new SectionRoadCosts(){
   public SectionLeg leg(Id<Link> from,Id<Link> to,VehicleType t){roadCalls.add(from+">"+to);int h=Math.floorMod((from.toString()+">"+to).hashCode(),7);return new SectionLeg(1+h,1+(h*3)%6,0);}
   public RoadLeg materialize(Id<Link> a,Id<Link> b,double time,Vehicle v){throw new UnsupportedOperationException();}
   public String fingerprint(){return "perf-oracle";}
  };
  double bestCost=Double.POSITIVE_INFINITY,bestEnd=Double.POSITIVE_INFINITY;List<SectionVisit> bestVisits=null;
  for(int mask=0;mask<256;mask++){
   double time=0,cost=0;Id<Link> at=vehicle.getLinkId();var visits=new ArrayList<SectionVisit>();boolean valid=true;
   for(int i=0;i<8;i++){
    int choice=(mask>>(7-i))&1;var next=Id.<Link>createLinkId(i+(choice==0?"a":"b"));var leg=roads.leg(at,next,type);
    time+=leg.seconds();double arrival=time;cost+=leg.meters();double start=Math.max(time,i*7);cost+=(start-time)*2;
    if(start>80+i*5){valid=false;break;}time=start+2;at=next;visits.add(new SectionVisit("s"+i,choice==0?13:-4,arrival,time));
   }
   if(!valid)continue;var home=roads.leg(at,vehicle.getLinkId(),type);time+=home.seconds();cost+=home.meters();if(time>120)continue;
   if(cost<bestCost||cost==bestCost&&(time<bestEnd||time==bestEnd&&lexLess(visits,bestVisits))){bestCost=cost;bestEnd=time;bestVisits=List.copyOf(visits);}
  }
  var actual=new SectionRouteEvaluator(new SectionCatalog(carrier,sections,"perf-oracle"),roads,120,120,0).evaluate(sections.stream().map(DeliverySection::id).toList(),vehicle,0);
  assertThat(bestVisits).isNotNull();assertThat(actual.feasible()).isTrue();assertThat(actual.variableCost()).isEqualTo(bestCost);
  assertThat(actual.endTime()).isEqualTo(bestEnd);assertThat(actual.visits()).isEqualTo(bestVisits);
  var catalog=new SectionCatalog(carrier,sections,"perf-oracle");
  var current=new SectionRouteEvaluator(catalog,roads,120,120,0);var legacy=new SectionRouteEvaluatorLegacy(catalog,roads,120,120,0);var stage1=new SectionRouteEvaluatorStage1(catalog,roads,120,120,0);var stage2=new SectionRouteEvaluatorStage2(catalog,roads,120,120,0);
  for(int length=0;length<=8;length++)for(double departure:new double[]{0,1,20,119}){
   var order=sections.subList(0,length).stream().map(DeliverySection::id).toList();
   roadCalls.clear();var actualResult=current.evaluate(order,vehicle,departure);var actualCalls=List.copyOf(roadCalls);
   roadCalls.clear();var controlResult=stage2.evaluate(order,vehicle,departure);var controlCalls=List.copyOf(roadCalls);
   assertThat(actualResult).as("length %s departure %s",length,departure).isEqualTo(controlResult).isEqualTo(legacy.evaluate(order,vehicle,departure)).isEqualTo(stage1.evaluate(order,vehicle,departure));
   assertThat(actualCalls).as("road call order length %s departure %s",length,departure).isEqualTo(controlCalls);
  }
 }
 private static boolean lexLess(List<SectionVisit> a,List<SectionVisit> b){
  if(b==null)return true;for(int i=0;i<a.size();i++){int c=Integer.compare(a.get(i).variant(),b.get(i).variant());if(c!=0)return c<0;}return false;
 }
}

