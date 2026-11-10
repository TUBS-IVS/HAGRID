package hagrid.core.routing.sections;
import com.graphhopper.jsprit.core.problem.constraint.HardActivityConstraint.ConstraintsStatus;
import com.graphhopper.jsprit.core.problem.job.Service;
import com.graphhopper.jsprit.core.problem.misc.*;
import com.graphhopper.jsprit.core.problem.solution.route.VehicleRoute;
import hagrid.core.routing.RoadLeg;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.*;
import org.matsim.api.core.v01.network.*;
import org.matsim.core.network.NetworkUtils;
import org.matsim.freight.carriers.*;
import org.matsim.vehicles.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class SectionInsertionAdapterTest {
 @Test void insertionReorientsEarlierSectionAndChargesExactDelta() {
  var carrier=CarriersUtils.createCarrier(Id.create("c",Carrier.class));
  for(String id:List.of("ja","jb"))CarriersUtils.addService(carrier,CarrierService.Builder.newInstance(Id.create(id,CarrierService.class),Id.createLinkId(id.equals("ja")?"a0":"b"),1).setServiceDuration(0).setServiceStartingTimeWindow(TimeWindow.newInstance(0,1000)).build());
  var type=VehicleUtils.createVehicleType(Id.create("type",VehicleType.class),"car");type.getCapacity().setOther(10);type.getCostInformation().setCostsPerMeter(1.0).setCostsPerSecond(0.0).setFixedCost(5.0);
  var v=CarrierVehicle.Builder.newInstance(Id.createVehicleId("v"),Id.createLinkId("d"),type).setEarliestStart(0).setLatestEnd(1000).build();carrier.setCarrierCapabilities(CarrierCapabilities.Builder.newInstance().addVehicle(v).setFleetSize(CarrierCapabilities.FleetSize.FINITE).build());
  var net=NetworkUtils.createNetwork();int x=0;for(String id:List.of("d","a0","a1","b")){var n1=NetworkUtils.createAndAddNode(net,Id.createNodeId(id+"x"),new Coord(x++,0));var n2=NetworkUtils.createAndAddNode(net,Id.createNodeId(id+"y"),new Coord(x++,0));NetworkUtils.createAndAddLink(net,Id.createLinkId(id),n1,n2,1,1,1,1);}
  var a=new DeliverySection("A",List.of("ja"),1,List.of(new SectionVariant(0,List.of("ja"),List.of(Id.createLinkId("a0"))),new SectionVariant(1,List.of("ja"),List.of(Id.createLinkId("a1")))));
  var b=new DeliverySection("B",List.of("jb"),1,List.of(new SectionVariant(0,List.of("jb"),List.of(Id.createLinkId("b")))));
  SectionRoadCosts roads=new SectionRoadCosts(){public SectionLeg leg(Id<Link> from,Id<Link> to,VehicleType t){String k=from+">"+to;double m=switch(k){case "d>a0","a0>d","a1>d","a1>b","b>d"->1;case "d>a1"->5;case "a0>b"->100;default->50;};return new SectionLeg(m,1,0);} public RoadLeg materialize(Id<Link>x,Id<Link>y,double z,Vehicle w){throw new UnsupportedOperationException();}public String fingerprint(){return "oracle";}};
  var catalog=new SectionCatalog(carrier,List.of(a,b),"oracle");var problem=SectionProblemFactory.build(catalog,net,roads).problem();var state=new SectionSearchState(catalog,roads,1000,1000,0);var adapter=new SectionInsertionAdapter(state,problem);
  var vehicle=problem.getVehicles().iterator().next();var route=VehicleRoute.Builder.newInstance(vehicle).setDepartureTime(0).addService((Service)problem.getJobs().get("A")).build();var job=(Service)problem.getJobs().get("B");var inserted=problem.getJobActivityFactory().createActivities(job).getFirst();
  var facts=new JobInsertionContext(route,job,vehicle,route.getDriver(),0);var context=new ActivityContext();context.setInsertionIndex(1);facts.setActivityContext(context);
  var before=state.evaluate(route);var after=state.evaluate(List.of("A","B"),state.vehicle(vehicle),0);
  assertThat(before.evaluation().visits().getFirst().variant()).isEqualTo(0);assertThat(after.evaluation().visits().getFirst().variant()).isEqualTo(1);
  assertThat(adapter.fulfilled(facts,route.getActivities().getFirst(),inserted,route.getEnd(),0)).isEqualTo(ConstraintsStatus.FULFILLED);
  assertThat(adapter.getCosts(facts,route.getActivities().getFirst(),route.getEnd(),inserted,0)).isEqualTo(5);
 }
 @Test void vehicleSwitchSubtractsNativeAccessAndIncludesFixedDifference() {
  var carrier=CarriersUtils.createCarrier(Id.create("c",Carrier.class));
  for(String id:List.of("ja","jb"))CarriersUtils.addService(carrier,CarrierService.Builder.newInstance(Id.create(id,CarrierService.class),Id.createLinkId(id.equals("ja")?"a0":"b"),1).setServiceDuration(0).setServiceStartingTimeWindow(TimeWindow.newInstance(0,1000)).build());
  var type=VehicleUtils.createVehicleType(Id.create("type",VehicleType.class),"car");type.getCapacity().setOther(10);type.getCostInformation().setCostsPerMeter(1.0).setCostsPerSecond(0.0).setFixedCost(5.0);
  var otherType=VehicleUtils.createVehicleType(Id.create("other",VehicleType.class),"car");otherType.getCapacity().setOther(10);otherType.getCostInformation().setCostsPerMeter(1.0).setCostsPerSecond(0.0).setFixedCost(7.0);
  var other=CarrierVehicle.Builder.newInstance(Id.createVehicleId("w"),Id.createLinkId("e"),otherType).setEarliestStart(0).setLatestEnd(10).build();
  var v=CarrierVehicle.Builder.newInstance(Id.createVehicleId("v"),Id.createLinkId("d"),type).setEarliestStart(0).setLatestEnd(1000).build();carrier.setCarrierCapabilities(CarrierCapabilities.Builder.newInstance().addVehicle(v).addVehicle(other).setFleetSize(CarrierCapabilities.FleetSize.FINITE).build());
  var net=NetworkUtils.createNetwork();int x=0;for(String id:List.of("d","a0","a1","b","e")){var n1=NetworkUtils.createAndAddNode(net,Id.createNodeId(id+"x"),new Coord(x++,0));var n2=NetworkUtils.createAndAddNode(net,Id.createNodeId(id+"y"),new Coord(x++,0));NetworkUtils.createAndAddLink(net,Id.createLinkId(id),n1,n2,1,1,1,1);}
  var a=new DeliverySection("A",List.of("ja"),1,List.of(new SectionVariant(0,List.of("ja"),List.of(Id.createLinkId("a0"))),new SectionVariant(1,List.of("ja"),List.of(Id.createLinkId("a1")))));
  var b=new DeliverySection("B",List.of("jb"),1,List.of(new SectionVariant(0,List.of("jb"),List.of(Id.createLinkId("b")))));
  SectionRoadCosts roads=new SectionRoadCosts(){public SectionLeg leg(Id<Link> from,Id<Link> to,VehicleType t){String k=from+">"+to;double m=switch(k){case "d>a0","a0>d","a1>d","a1>b","b>d"->1;case "d>a1"->5;case "e>a0"->3;case "e>a1"->10;case "b>e"->1;case "a0>b"->100;default->50;};return new SectionLeg(m,k.equals("e>a0")?20:1,0);} public RoadLeg materialize(Id<Link>x,Id<Link>y,double z,Vehicle w){throw new UnsupportedOperationException();}public String fingerprint(){return "oracle";}};
  var catalog=new SectionCatalog(carrier,List.of(a,b),"oracle");var problem=SectionProblemFactory.build(catalog,net,roads).problem();var state=new SectionSearchState(catalog,roads,1000,1000,0);var adapter=new SectionInsertionAdapter(state,problem);
  var vehicle=problem.getVehicles().stream().filter(candidate->candidate.getId().equals("v")).findFirst().orElseThrow();var switched=problem.getVehicles().stream().filter(candidate->candidate.getId().equals("w")).findFirst().orElseThrow();var route=VehicleRoute.Builder.newInstance(vehicle).setDepartureTime(0).addService((Service)problem.getJobs().get("A")).build();var job=(Service)problem.getJobs().get("B");var inserted=problem.getJobActivityFactory().createActivities(job).getFirst();
  var facts=new JobInsertionContext(route,job,switched,route.getDriver(),0);var context=new ActivityContext();context.setInsertionIndex(1);facts.setActivityContext(context);
  var before=state.evaluate(route);var after=state.evaluate(List.of("A","B"),state.vehicle(switched),0);
  assertThat(before.evaluation().variableCost()).isEqualTo(2);assertThat(after.evaluation().variableCost()).isEqualTo(12);
  var t=problem.getTransportCosts();var first=route.getActivities().getFirst().getLocation();
  double nativeAccess=t.getTransportCost(switched.getStartLocation(),first,0,route.getDriver(),switched)-t.getTransportCost(route.getStart().getLocation(),first,0,route.getDriver(),vehicle)
    +t.getTransportCost(first,switched.getEndLocation(),0,route.getDriver(),switched)-t.getTransportCost(first,route.getEnd().getLocation(),0,route.getDriver(),vehicle);
  assertThat(adapter.fulfilled(facts,route.getActivities().getFirst(),inserted,route.getEnd(),0)).isEqualTo(ConstraintsStatus.FULFILLED);
  assertThat(adapter.getCosts(facts,route.getActivities().getFirst(),route.getEnd(),inserted,0)+nativeAccess).isEqualTo(12);
  var states=new com.graphhopper.jsprit.core.algorithm.state.StateManager(problem);
  var constraints=new com.graphhopper.jsprit.core.problem.constraint.ConstraintManager(problem,states);
  constraints.addConstraint(adapter,com.graphhopper.jsprit.core.problem.constraint.ConstraintManager.Priority.CRITICAL);
  var nativeCalculator=new com.graphhopper.jsprit.core.algorithm.recreate.ServiceInsertionCalculatorFactory().create(problem,adapter,problem.getJobActivityFactory(),constraints);
  var nativeInsertion=nativeCalculator.getInsertionData(route,job,switched,0,route.getDriver(),Double.MAX_VALUE);
  assertThat(nativeInsertion.getDeliveryInsertionIndex()).isEqualTo(1);
  assertThat(nativeInsertion.getInsertionCost()).isEqualTo(12);
  var shortShift=com.graphhopper.jsprit.core.problem.vehicle.VehicleImpl.Builder.newInstance(switched).setLatestArrival(2).build();
  var impossible=new JobInsertionContext(route,job,shortShift,route.getDriver(),0);impossible.setActivityContext(context);
  assertThat(adapter.fulfilled(impossible,route.getActivities().getFirst(),inserted,route.getEnd(),0)).isEqualTo(ConstraintsStatus.NOT_FULFILLED);
 }}






