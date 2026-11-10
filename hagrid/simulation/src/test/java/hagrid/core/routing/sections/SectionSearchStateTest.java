package hagrid.core.routing.sections;

import hagrid.core.routing.RoadLeg;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.freight.carriers.*;
import org.matsim.vehicles.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class SectionSearchStateTest {
 @Test void insertionUsesDelayedDepartureBeforeComparingWaitingCost() throws Exception {
  var carrier=CarriersUtils.createCarrier(Id.create("c",Carrier.class));
  var service=CarrierService.Builder.newInstance(Id.create("a",CarrierService.class),Id.createLinkId("a"))
   .setCapacityDemand(1).setServiceDuration(1).setServiceStartingTimeWindow(TimeWindow.newInstance(100,200)).build();
  CarriersUtils.addService(carrier,service);
  var type=VehicleUtils.createVehicleType(Id.create("v",VehicleType.class),"car");
  type.getCapacity().setOther(10); type.getCostInformation().setCostsPerMeter(0.0).setCostsPerSecond(0.0);
  VehicleUtils.setCostsPerSecondWaiting(type.getCostInformation(),10.0);
  VehicleUtils.setCostsPerSecondInService(type.getCostInformation(),0.0);
  CarriersUtils.setSkills(type,Set.of("warm"));
  var vehicle=CarrierVehicle.Builder.newInstance(Id.createVehicleId("v"),Id.createLinkId("d"),type).setEarliestStart(0).setLatestEnd(300).build();
  var section=new DeliverySection("s",List.of("a"),1,List.of(new SectionVariant(0,List.of("a"),List.of(Id.createLinkId("a")))));
  SectionRoadCosts roads=new SectionRoadCosts(){
   public SectionLeg leg(Id<Link> a,Id<Link> b,VehicleType t){return new SectionLeg(0,10,0);}
   public RoadLeg materialize(Id<Link> a,Id<Link> b,double time,Vehicle v){throw new UnsupportedOperationException();}
   public String fingerprint(){return "test";}
  };
  var catalog=new SectionCatalog(carrier,List.of(section),"test");
  var state=new SectionSearchState(catalog,roads,300,300,0);
  var choice=state.evaluate(List.of("s"),vehicle,0);
  assertThat(choice.departure()).isEqualTo(90);
  assertThat(choice.evaluation().feasible()).isTrue();
  assertThat(choice.evaluation().variableCost()).isEqualTo(0);
  assertThat(state.evaluate(List.of("s"),vehicle,0)).isSameAs(choice);
  type.getCapacity().setOther(0);var insufficient=state.evaluate(List.of("s"),vehicle,0);assertThat(insufficient.evaluation().feasible()).isFalse();
  assertThat(insufficient).isEqualTo(new SectionSearchState(catalog,roads,300,300,0).evaluate(List.of("s"),vehicle,0));type.getCapacity().setOther(10);
  type.getCostInformation().setCostsPerSecond(2.0);var changed=state.evaluate(List.of("s"),vehicle,0);
  assertThat(changed).isNotSameAs(choice);assertThat(changed).isEqualTo(new SectionSearchState(catalog,roads,300,300,0).evaluate(List.of("s"),vehicle,0));
  type.getCostInformation().setCostsPerSecond(0.0);assertThat(state.evaluate(List.of("s"),vehicle,0)).isSameAs(choice);
  CarriersUtils.setSkills(type,Set.of("cold"));var skilled=state.evaluate(List.of("s"),vehicle,0);
  assertThat(skilled).isNotSameAs(choice);assertThat(skilled).isEqualTo(new SectionSearchState(catalog,roads,300,300,0).evaluate(List.of("s"),vehicle,0));
  CarriersUtils.setSkills(type,Set.of("warm"));assertThat(state.evaluate(List.of("s"),vehicle,0)).isSameAs(choice);
  VehicleUtils.setCostsPerSecondWaiting(type.getCostInformation(),11.0);var waitingChanged=state.evaluate(List.of("s"),vehicle,0);
  assertThat(waitingChanged).isNotSameAs(choice);assertThat(waitingChanged).isEqualTo(new SectionSearchState(catalog,roads,300,300,0).evaluate(List.of("s"),vehicle,0));
  VehicleUtils.setCostsPerSecondWaiting(type.getCostInformation(),10.0);
  var executor=java.util.concurrent.Executors.newSingleThreadExecutor();
  try{var worker=executor.submit(()->state.evaluate(List.of("s"),vehicle,0)).get();assertThat(worker).isEqualTo(choice).isNotSameAs(choice);}finally{executor.shutdownNow();}
  for(int i=1;i<=1024;i++)state.evaluate(List.of("s"),vehicle,i);
  var evicted=state.evaluate(List.of("s"),vehicle,0);assertThat(evicted).isEqualTo(choice).isNotSameAs(choice);
 }
}

