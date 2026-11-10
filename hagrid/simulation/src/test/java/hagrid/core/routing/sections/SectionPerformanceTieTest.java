package hagrid.core.routing.sections;

import hagrid.core.routing.RoadLeg;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.freight.carriers.*;
import org.matsim.vehicles.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class SectionPerformanceTieTest {
 @Test void choosesLexicographicallySmallestUnsortedVariantsAcrossDistinctPrefixes() {
  var carrier=CarriersUtils.createCarrier(Id.create("tie",Carrier.class));
  for(String id:List.of("first","second")) CarriersUtils.addService(carrier,
   CarrierService.Builder.newInstance(Id.create(id,CarrierService.class),Id.createLinkId(id))
    .setCapacityDemand(1).setServiceDuration(1).setServiceStartingTimeWindow(TimeWindow.newInstance(0,100)).build());
  var type=VehicleUtils.createVehicleType(Id.create("tie-type",VehicleType.class),"car");
  type.getCapacity().setOther(2);type.getCostInformation().setCostsPerMeter(0.0).setCostsPerSecond(0.0);
  var vehicle=CarrierVehicle.Builder.newInstance(Id.createVehicleId("tie-v"),Id.createLinkId("d"),type).setEarliestStart(0).setLatestEnd(100).build();
  var first=new DeliverySection("first-section",List.of("first"),1,List.of(
   new SectionVariant(7,List.of("first"),List.of(Id.createLinkId("a"))),
   new SectionVariant(-2,List.of("first"),List.of(Id.createLinkId("b")))));
  var second=new DeliverySection("second-section",List.of("second"),1,List.of(
   new SectionVariant(9,List.of("second"),List.of(Id.createLinkId("c"))),
   new SectionVariant(1,List.of("second"),List.of(Id.createLinkId("c")))));
  SectionRoadCosts roads=new SectionRoadCosts(){
   public SectionLeg leg(Id<Link> a,Id<Link> b,VehicleType t){return new SectionLeg(0,0,0);}
   public RoadLeg materialize(Id<Link> a,Id<Link> b,double time,Vehicle v){throw new UnsupportedOperationException();}
   public String fingerprint(){return "tie";}
  };
  var evaluator=new SectionRouteEvaluator(new SectionCatalog(carrier,List.of(first,second),"tie"),roads,100,100,0);
  var result=evaluator.evaluate(List.of("first-section","second-section"),vehicle,0);
  assertThat(result.feasible()).isTrue();assertThat(result.variableCost()).isZero();assertThat(result.endTime()).isEqualTo(2);
  assertThat(result.visits()).containsExactly(new SectionVisit("first-section",-2,0,1),new SectionVisit("second-section",1,1,2));
 }
 @Test void longTraceAndDuplicateExtremeIdsPreserveLexicographicChoice() {
  var carrier=CarriersUtils.createCarrier(Id.create("long-tie",Carrier.class));var sections=new ArrayList<DeliverySection>();
  for(int i=0;i<70;i++){
   String job="j"+i,section="s"+i;var halt=Id.<Link>createLinkId(job);
   CarriersUtils.addService(carrier,CarrierService.Builder.newInstance(Id.create(job,CarrierService.class),halt).setCapacityDemand(1).setServiceDuration(1).setServiceStartingTimeWindow(TimeWindow.newInstance(0,1000)).build());
   sections.add(new DeliverySection(section,List.of(job),1,List.of(new SectionVariant(Integer.MAX_VALUE,List.of(job),List.of(halt)),new SectionVariant(Integer.MIN_VALUE,List.of(job),List.of(halt)),new SectionVariant(Integer.MIN_VALUE,List.of(job),List.of(halt)))));
  }
  var type=VehicleUtils.createVehicleType(Id.create("long-tie-type",VehicleType.class),"car");type.getCapacity().setOther(100);type.getCostInformation().setCostsPerMeter(0.0).setCostsPerSecond(0.0);
  var vehicle=CarrierVehicle.Builder.newInstance(Id.createVehicleId("long-tie-v"),Id.createLinkId("d"),type).setEarliestStart(0).setLatestEnd(1000).build();
  SectionRoadCosts roads=new SectionRoadCosts(){public SectionLeg leg(Id<Link> a,Id<Link> b,VehicleType t){return new SectionLeg(0,0,0);}public RoadLeg materialize(Id<Link> a,Id<Link> b,double time,Vehicle v){throw new UnsupportedOperationException();}public String fingerprint(){return "long-tie";}};
  var catalog=new SectionCatalog(carrier,sections,"long-tie");var order=sections.stream().map(DeliverySection::id).toList();
  var result=new SectionRouteEvaluator(catalog,roads,1000,1000,0).evaluate(order,vehicle,0);
  assertThat(result).isEqualTo(new SectionRouteEvaluatorLegacy(catalog,roads,1000,1000,0).evaluate(order,vehicle,0)).isEqualTo(new SectionRouteEvaluatorStage1(catalog,roads,1000,1000,0).evaluate(order,vehicle,0));
  assertThat(result.visits()).hasSize(70).allSatisfy(v->assertThat(v.variant()).isEqualTo(Integer.MIN_VALUE));assertThat(result.endTime()).isEqualTo(70);
 }
}

