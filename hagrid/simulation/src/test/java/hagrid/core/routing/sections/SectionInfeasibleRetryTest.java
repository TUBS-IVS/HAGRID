package hagrid.core.routing.sections;
import hagrid.core.routing.RoadLeg;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.*;
import org.matsim.api.core.v01.network.*;
import org.matsim.core.network.NetworkUtils;
import org.matsim.freight.carriers.*;
import org.matsim.vehicles.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class SectionInfeasibleRetryTest {
 @Test void impossibleRouteDurationRemainsUnassignedInsteadOfAborting(){verify(100,100,1000,50);}
 @Test void impossibleVehicleReturnRemainsUnassignedInsteadOfAborting(){verify(1000,2000,1000,500);}
 void verify(double release,double deadline,double shiftEnd,double limit){
  var carrier=CarriersUtils.createCarrier(Id.create("retry",Carrier.class));
  var bad=CarrierService.Builder.newInstance(Id.create("bad",CarrierService.class),Id.createLinkId("bad"),1).setServiceDuration(0).setServiceStartingTimeWindow(TimeWindow.newInstance(release,deadline)).build();
  var good=CarrierService.Builder.newInstance(Id.create("good",CarrierService.class),Id.createLinkId("good"),1).setServiceDuration(0).setServiceStartingTimeWindow(TimeWindow.newInstance(0,900)).build();CarriersUtils.addService(carrier,bad);CarriersUtils.addService(carrier,good);
  var type=VehicleUtils.createVehicleType(Id.create("type",VehicleType.class),"car");type.getCapacity().setOther(10);type.getCostInformation().setCostsPerMeter(0.0).setCostsPerSecond(0.0).setFixedCost(0.0);VehicleUtils.setCostsPerSecondWaiting(type.getCostInformation(),10.0);VehicleUtils.setCostsPerSecondInService(type.getCostInformation(),0.0);
  var vehicle=CarrierVehicle.Builder.newInstance(Id.createVehicleId("v"),Id.createLinkId("d"),type).setEarliestStart(0).setLatestEnd(shiftEnd).build();carrier.setCarrierCapabilities(CarrierCapabilities.Builder.newInstance().addVehicle(vehicle).setFleetSize(CarrierCapabilities.FleetSize.FINITE).build());
  var sections=List.of(new DeliverySection("bad",List.of("bad"),1,List.of(new SectionVariant(0,List.of("bad"),List.of(Id.createLinkId("bad"))))),new DeliverySection("good",List.of("good"),1,List.of(new SectionVariant(0,List.of("good"),List.of(Id.createLinkId("good"))))));
  SectionRoadCosts roads=new SectionRoadCosts(){public SectionLeg leg(Id<Link>a,Id<Link>b,VehicleType t){return new SectionLeg(0,a.toString().equals("bad")||b.toString().equals("bad")?100:0,0);}public RoadLeg materialize(Id<Link>a,Id<Link>b,double x,Vehicle v){throw new UnsupportedOperationException();}public String fingerprint(){return "retry";}};
  var catalog=new SectionCatalog(carrier,sections,"retry");var state=new SectionSearchState(catalog,roads,limit,limit,0);
  assertThat(state.evaluate(List.of("bad"),vehicle,0).evaluation().feasible()).isFalse();
  assertThat(state.numericRetryEvaluations()).isPositive().isLessThanOrEqualTo(256);assertThat(state.numericRetryExhaustions()).isEqualTo(1);
  long retries=state.numericRetryEvaluations();state.evaluate(List.of("bad"),vehicle,0);assertThat(state.numericRetryEvaluations()).isEqualTo(retries);
  var net=NetworkUtils.createNetwork();int x=0;for(String id:List.of("d","bad","good")){var n=NetworkUtils.createAndAddNode(net,Id.createNodeId(id+"x"),new Coord(x++,0));var m=NetworkUtils.createAndAddNode(net,Id.createNodeId(id+"y"),new Coord(x++,0));NetworkUtils.createAndAddLink(net,Id.createLinkId(id),n,m,1,1,1,1);}
  var p=SectionProblemFactory.build(catalog,net,roads).problem();var nativeVehicle=p.getVehicles().iterator().next();var empty=com.graphhopper.jsprit.core.problem.solution.route.VehicleRoute.emptyRoute();var states=new com.graphhopper.jsprit.core.algorithm.state.StateManager(p);var constraints=new com.graphhopper.jsprit.core.problem.constraint.ConstraintManager(p,states);var adapter=new SectionInsertionAdapter(state,p);constraints.addConstraint(adapter,com.graphhopper.jsprit.core.problem.constraint.ConstraintManager.Priority.CRITICAL);var nativeCalc=new com.graphhopper.jsprit.core.algorithm.recreate.ServiceInsertionCalculatorFactory().create(p,adapter,p.getJobActivityFactory(),constraints);
  assertThat(nativeCalc.getInsertionData(empty,p.getJobs().get("bad"),nativeVehicle,0,empty.getDriver(),Double.MAX_VALUE).isFound()).isFalse();
  var result=new hagrid.core.routing.CarrierOptimizationService().searchSections(catalog,net,roads,limit,limit,0,4711,new SectionSearchPolicy(5,.25,.4));
  assertThat(result.solution().getUnassignedJobs()).extracting(com.graphhopper.jsprit.core.problem.job.Job::getId).containsExactly("bad");
  assertThat(result.solution().getRoutes()).hasSize(1);assertThat(SectionSearchState.order(result.solution().getRoutes().iterator().next())).containsExactly("good");
  assertThat(result.numericRetryEvaluations()).isPositive();assertThat(result.numericRetryExhaustions()).isPositive();
 }
 @Test void exhaustedRefinementPreservesTheFeasibleNominalRoute(){
  var carrier=CarriersUtils.createCarrier(Id.create("preserve",Carrier.class));var job=CarrierService.Builder.newInstance(Id.create("j",CarrierService.class),Id.createLinkId("a"),1).setServiceDuration(0).setServiceStartingTimeWindow(TimeWindow.newInstance(100,100)).build();CarriersUtils.addService(carrier,job);
  var type=VehicleUtils.createVehicleType(Id.create("vtype",VehicleType.class),"car");type.getCapacity().setOther(2);type.getCostInformation().setCostsPerMeter(0.0).setCostsPerSecond(0.0).setFixedCost(0.0);VehicleUtils.setCostsPerSecondWaiting(type.getCostInformation(),10.0);
  var vehicle=CarrierVehicle.Builder.newInstance(Id.createVehicleId("v"),Id.createLinkId("d"),type).setEarliestStart(0).setLatestEnd(1000).build();var section=new DeliverySection("s",List.of("j"),1,List.of(new SectionVariant(0,List.of("j"),List.of(Id.createLinkId("a"))),new SectionVariant(1,List.of("j"),List.of(Id.createLinkId("b")))));
  SectionRoadCosts roads=new SectionRoadCosts(){public SectionLeg leg(Id<Link>a,Id<Link>b,VehicleType t){double seconds=b.toString().equals("a")?100:a.toString().equals("b")&&b.toString().equals("d")?1000:0;return new SectionLeg(0,seconds,0);}public RoadLeg materialize(Id<Link>a,Id<Link>b,double x,Vehicle v){throw new UnsupportedOperationException();}public String fingerprint(){return "preserve";}};
  var state=new SectionSearchState(new SectionCatalog(carrier,List.of(section),"preserve"),roads,1200,1200,0);var result=state.evaluate(List.of("s"),vehicle,0);
  assertThat(result.evaluation().feasible()).isTrue();assertThat(result.departure()).isEqualTo(0);assertThat(result.evaluation().visits().getFirst().variant()).isEqualTo(0);
  assertThat(state.numericRetryEvaluations()).isPositive().isLessThanOrEqualTo(256);assertThat(state.numericRetryExhaustions()).isEqualTo(1);
 }}





