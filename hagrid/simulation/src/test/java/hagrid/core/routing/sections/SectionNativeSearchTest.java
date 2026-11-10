package hagrid.core.routing.sections;

import hagrid.core.routing.RoadLeg;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.freight.carriers.*;
import org.matsim.vehicles.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class SectionNativeSearchTest {
 @Test void realNativeSearchKeepsSelectedDepartureAndRunsExactIterations() {
  var carrier=CarriersUtils.createCarrier(Id.create("native",Carrier.class));
  var service=CarrierService.Builder.newInstance(Id.create("j",CarrierService.class),Id.createLinkId("a"))
   .setCapacityDemand(1).setServiceDuration(1).setServiceStartingTimeWindow(TimeWindow.newInstance(100,200)).build();
  CarriersUtils.addService(carrier,service);
  var type=VehicleUtils.createVehicleType(Id.create("type",VehicleType.class),"car");type.getCapacity().setOther(10);
  type.getCostInformation().setCostsPerMeter(0.0).setCostsPerSecond(0.0).setFixedCost(5.0);
  VehicleUtils.setCostsPerSecondWaiting(type.getCostInformation(),10.0);VehicleUtils.setCostsPerSecondInService(type.getCostInformation(),0.0);
  var v=CarrierVehicle.Builder.newInstance(Id.createVehicleId("vehicle"),Id.createLinkId("d"),type).setEarliestStart(0).setLatestEnd(300).build();
  carrier.setCarrierCapabilities(CarrierCapabilities.Builder.newInstance().addVehicle(v).setFleetSize(CarrierCapabilities.FleetSize.FINITE).build());
  var net=org.matsim.core.network.NetworkUtils.createNetwork();
  var n1=org.matsim.core.network.NetworkUtils.createAndAddNode(net,Id.createNodeId("n1"),new org.matsim.api.core.v01.Coord(0,0));
  var n2=org.matsim.core.network.NetworkUtils.createAndAddNode(net,Id.createNodeId("n2"),new org.matsim.api.core.v01.Coord(10,0));
  org.matsim.core.network.NetworkUtils.createAndAddLink(net,Id.createLinkId("d"),n1,n2,10,1,100,1);
  org.matsim.core.network.NetworkUtils.createAndAddLink(net,Id.createLinkId("a"),n2,n1,10,1,100,1);
  var section=new DeliverySection("s",List.of("j"),1,List.of(new SectionVariant(0,List.of("j"),List.of(Id.createLinkId("a")))));
  SectionRoadCosts roads=new SectionRoadCosts(){public SectionLeg leg(Id<Link>a,Id<Link>b,VehicleType t){return new SectionLeg(0,10,0);} public RoadLeg materialize(Id<Link>a,Id<Link>b,double x,Vehicle y){throw new UnsupportedOperationException();}public String fingerprint(){return "fixture";}};
  var catalog=new SectionCatalog(carrier,List.of(section),"fixture");
  var result=new hagrid.core.routing.CarrierOptimizationService().searchSections(catalog,net,roads,300,300,0,4711,new SectionSearchPolicy(100,.25,.40));
  assertThat(result.iterations()).isEqualTo(100);assertThat(result.solution().getUnassignedJobs()).isEmpty();
  assertThat(result.solution().getRoutes()).hasSize(1);var route=result.solution().getRoutes().iterator().next();
  assertThat(route.getDepartureTime()).isEqualTo(90);assertThat(result.solution().getCost()).isEqualTo(5);
  assertThat(result.evaluations().get(route).evaluation().variableCost()).isEqualTo(0);
 } @Test void infiniteFleetPreservesTemplateOnMultipleRoutes() {
  var carrier=CarriersUtils.createCarrier(Id.create("native",Carrier.class));
  var service=CarrierService.Builder.newInstance(Id.create("j",CarrierService.class),Id.createLinkId("a"))
   .setCapacityDemand(1).setServiceDuration(1).setServiceStartingTimeWindow(TimeWindow.newInstance(100,200)).build();
  CarriersUtils.addService(carrier,service);
  var other=CarrierService.Builder.newInstance(Id.create("k",CarrierService.class),Id.createLinkId("a")).setCapacityDemand(1).setServiceDuration(1).setServiceStartingTimeWindow(TimeWindow.newInstance(100,200)).build();CarriersUtils.addService(carrier,other);
  var type=VehicleUtils.createVehicleType(Id.create("type",VehicleType.class),"car");type.getCapacity().setOther(1);
  type.getCostInformation().setCostsPerMeter(0.0).setCostsPerSecond(0.0).setFixedCost(5.0);
  VehicleUtils.setCostsPerSecondWaiting(type.getCostInformation(),10.0);VehicleUtils.setCostsPerSecondInService(type.getCostInformation(),0.0);
  var v=CarrierVehicle.Builder.newInstance(Id.createVehicleId("vehicle"),Id.createLinkId("d"),type).setEarliestStart(0).setLatestEnd(300).build();
  carrier.setCarrierCapabilities(CarrierCapabilities.Builder.newInstance().addVehicle(v).setFleetSize(CarrierCapabilities.FleetSize.INFINITE).build());
  var net=org.matsim.core.network.NetworkUtils.createNetwork();
  var n1=org.matsim.core.network.NetworkUtils.createAndAddNode(net,Id.createNodeId("n1"),new org.matsim.api.core.v01.Coord(0,0));
  var n2=org.matsim.core.network.NetworkUtils.createAndAddNode(net,Id.createNodeId("n2"),new org.matsim.api.core.v01.Coord(10,0));
  org.matsim.core.network.NetworkUtils.createAndAddLink(net,Id.createLinkId("d"),n1,n2,10,1,100,1);
  org.matsim.core.network.NetworkUtils.createAndAddLink(net,Id.createLinkId("a"),n2,n1,10,1,100,1);
  var section=new DeliverySection("s",List.of("j"),1,List.of(new SectionVariant(0,List.of("j"),List.of(Id.createLinkId("a")))));
  SectionRoadCosts roads=new SectionRoadCosts(){public SectionLeg leg(Id<Link>a,Id<Link>b,VehicleType t){return new SectionLeg(0,10,0);} public RoadLeg materialize(Id<Link>a,Id<Link>b,double x,Vehicle y){throw new UnsupportedOperationException();}public String fingerprint(){return "fixture";}};
  var section2=new DeliverySection("t",List.of("k"),1,List.of(new SectionVariant(0,List.of("k"),List.of(Id.createLinkId("a")))));
  var catalog=new SectionCatalog(carrier,List.of(section,section2),"fixture");
  var result=new hagrid.core.routing.CarrierOptimizationService().searchSections(catalog,net,roads,300,300,0,4711,new SectionSearchPolicy(100,.25,.40));
  assertThat(result.iterations()).isEqualTo(100);assertThat(result.solution().getUnassignedJobs()).isEmpty();
  assertThat(result.solution().getRoutes()).hasSize(2);
  assertThat(result.solution().getRoutes()).allSatisfy(r->{assertThat(r.getVehicle().getType().getTypeId()).isEqualTo("type");assertThat(r.getVehicle().getEarliestDeparture()).isEqualTo(0);assertThat(r.getVehicle().getLatestArrival()).isEqualTo(300);assertThat(r.getVehicle().getStartLocation().getId()).isEqualTo("d");});
  assertThat(result.solution().getCost()).isEqualTo(10);
  assertThat(result.evaluations().values()).allSatisfy(choice->{assertThat(choice.departure()).isEqualTo(90);assertThat(choice.evaluation().variableCost()).isEqualTo(0);});
 } @Test void nativeConstraintsRejectCapacityAndSkillMismatch() {
  var carrier=CarriersUtils.createCarrier(Id.create("native",Carrier.class));
  var service=CarrierService.Builder.newInstance(Id.create("j",CarrierService.class),Id.createLinkId("a"))
   .setCapacityDemand(1).setServiceDuration(1).setServiceStartingTimeWindow(TimeWindow.newInstance(100,200)).build();
  CarriersUtils.setSkills(service,Set.of("required"));CarriersUtils.addService(carrier,service);
  var type=VehicleUtils.createVehicleType(Id.create("type",VehicleType.class),"car");type.getCapacity().setOther(10);
  type.getCostInformation().setCostsPerMeter(0.0).setCostsPerSecond(0.0).setFixedCost(5.0);
  VehicleUtils.setCostsPerSecondWaiting(type.getCostInformation(),10.0);VehicleUtils.setCostsPerSecondInService(type.getCostInformation(),0.0);
  var v=CarrierVehicle.Builder.newInstance(Id.createVehicleId("vehicle"),Id.createLinkId("d"),type).setEarliestStart(0).setLatestEnd(300).build();
  var tooSmall=VehicleUtils.createVehicleType(Id.create("small",VehicleType.class),"car");tooSmall.getCapacity().setOther(0);tooSmall.getCostInformation().setCostsPerMeter(0.0).setCostsPerSecond(0.0).setFixedCost(0.0);CarriersUtils.setSkills(tooSmall,Set.of("required"));
  var good=VehicleUtils.createVehicleType(Id.create("good",VehicleType.class),"car");good.getCapacity().setOther(10);good.getCostInformation().setCostsPerMeter(0.0).setCostsPerSecond(0.0).setFixedCost(0.0);CarriersUtils.setSkills(good,Set.of("required"));VehicleUtils.setCostsPerSecondWaiting(good.getCostInformation(),10.0);VehicleUtils.setCostsPerSecondInService(good.getCostInformation(),0.0);
  var a=CarrierVehicle.Builder.newInstance(Id.createVehicleId("small"),Id.createLinkId("d"),tooSmall).setEarliestStart(0).setLatestEnd(300).build();
  var b=CarrierVehicle.Builder.newInstance(Id.createVehicleId("good"),Id.createLinkId("d"),good).setEarliestStart(0).setLatestEnd(300).build();
  carrier.setCarrierCapabilities(CarrierCapabilities.Builder.newInstance().addVehicle(v).addVehicle(a).addVehicle(b).setFleetSize(CarrierCapabilities.FleetSize.FINITE).build());
  var net=org.matsim.core.network.NetworkUtils.createNetwork();
  var n1=org.matsim.core.network.NetworkUtils.createAndAddNode(net,Id.createNodeId("n1"),new org.matsim.api.core.v01.Coord(0,0));
  var n2=org.matsim.core.network.NetworkUtils.createAndAddNode(net,Id.createNodeId("n2"),new org.matsim.api.core.v01.Coord(10,0));
  org.matsim.core.network.NetworkUtils.createAndAddLink(net,Id.createLinkId("d"),n1,n2,10,1,100,1);
  org.matsim.core.network.NetworkUtils.createAndAddLink(net,Id.createLinkId("a"),n2,n1,10,1,100,1);
  var section=new DeliverySection("s",List.of("j"),1,List.of(new SectionVariant(0,List.of("j"),List.of(Id.createLinkId("a")))));
  SectionRoadCosts roads=new SectionRoadCosts(){public SectionLeg leg(Id<Link>a,Id<Link>b,VehicleType t){return new SectionLeg(0,10,0);} public RoadLeg materialize(Id<Link>a,Id<Link>b,double x,Vehicle y){throw new UnsupportedOperationException();}public String fingerprint(){return "fixture";}};
  var catalog=new SectionCatalog(carrier,List.of(section),"fixture");
  var result=new hagrid.core.routing.CarrierOptimizationService().searchSections(catalog,net,roads,300,300,0,4711,new SectionSearchPolicy(100,.25,.40));
  assertThat(result.solution().getUnassignedJobs()).isEmpty();assertThat(result.solution().getRoutes()).hasSize(1);
  assertThat(result.solution().getRoutes().iterator().next().getVehicle().getId()).isEqualTo("good");
 }}







