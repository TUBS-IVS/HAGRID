package hagrid.core.routing.sections;
import com.graphhopper.jsprit.core.algorithm.recreate.ServiceInsertionCalculatorFactory;
import com.graphhopper.jsprit.core.algorithm.state.StateManager;
import com.graphhopper.jsprit.core.problem.constraint.ConstraintManager;
import com.graphhopper.jsprit.core.problem.job.Service;
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
class SectionDepartureBoundaryTest {
 static final double RELEASE=25185.767296166716, ACCESS=1156.2893732306384;
 record Fixture(SectionCatalog catalog,SectionRoadCosts roads,Network net,CarrierVehicle vehicle){}
 Fixture fixture(boolean heterogeneous){return fixture(heterogeneous,false);}
 Fixture fixture(boolean heterogeneous,boolean vehicleBoundary){
  var carrier=CarriersUtils.createCarrier(Id.create("boundary",Carrier.class));var sections=new ArrayList<DeliverySection>();
  if(heterogeneous){var a=CarrierService.Builder.newInstance(Id.create("early",CarrierService.class),Id.createLinkId("early"),1).setServiceDuration(0).setServiceStartingTimeWindow(TimeWindow.newInstance(0,30000)).build();CarriersUtils.addService(carrier,a);sections.add(new DeliverySection("A",List.of("early"),1,List.of(new SectionVariant(0,List.of("early"),List.of(Id.createLinkId("early"))))));}
  var b=CarrierService.Builder.newInstance(Id.create("late",CarrierService.class),Id.createLinkId("late"),1).setServiceDuration(0).setServiceStartingTimeWindow(TimeWindow.newInstance(RELEASE,vehicleBoundary?30000:RELEASE)).build();CarriersUtils.addService(carrier,b);sections.add(new DeliverySection("B",List.of("late"),1,List.of(new SectionVariant(0,List.of("late"),List.of(Id.createLinkId("late"))))));
  var type=VehicleUtils.createVehicleType(Id.create("v",VehicleType.class),"car");type.getCapacity().setOther(10);type.getCostInformation().setCostsPerMeter(0.0).setCostsPerSecond(0.0).setFixedCost(0.0);VehicleUtils.setCostsPerSecondWaiting(type.getCostInformation(),10.0);VehicleUtils.setCostsPerSecondInService(type.getCostInformation(),0.0);
  var v=CarrierVehicle.Builder.newInstance(Id.createVehicleId("v"),Id.createLinkId("d"),type).setEarliestStart(0).setLatestEnd(vehicleBoundary?RELEASE:30000).build();carrier.setCarrierCapabilities(CarrierCapabilities.Builder.newInstance().addVehicle(v).setFleetSize(CarrierCapabilities.FleetSize.FINITE).build());
  SectionRoadCosts roads=new SectionRoadCosts(){public SectionLeg leg(Id<Link>a,Id<Link>b,VehicleType t){if(heterogeneous&&a.toString().equals("late")&&b.toString().equals("early"))return new SectionLeg(Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,0);return new SectionLeg(0,b.toString().equals("late")?ACCESS:0,0);}public RoadLeg materialize(Id<Link>a,Id<Link>b,double x,Vehicle v){throw new UnsupportedOperationException();}public String fingerprint(){return "boundary";}};
  var net=NetworkUtils.createNetwork();int x=0;for(String id:List.of("d","early","late")){var n=NetworkUtils.createAndAddNode(net,Id.createNodeId(id+"x"),new Coord(x++,0));var m=NetworkUtils.createAndAddNode(net,Id.createNodeId(id+"y"),new Coord(x++,0));NetworkUtils.createAndAddLink(net,Id.createLinkId(id),n,m,1,1,1,1);}
  return new Fixture(new SectionCatalog(carrier,sections,"boundary"),roads,net,v);
 }
 @Test void roundedFastPathBoundaryRetainsFeasiblePredecessor(){verify(false);}
 @Test void roundedHeterogeneousUpperBoundRetainsFeasiblePredecessor(){verify(true);}
 void verify(boolean hetero){
  var f=fixture(hetero);var order=hetero?List.of("A","B"):List.of("B");double rounded=RELEASE-ACCESS;var exact=new SectionRouteEvaluator(f.catalog(),f.roads(),1200,1200,0);
  assertThat(exact.evaluate(order,f.vehicle(),rounded).feasible()).isFalse();assertThat(exact.evaluate(order,f.vehicle(),Math.nextDown(rounded)).feasible()).isTrue();
  var state=new SectionSearchState(f.catalog(),f.roads(),1200,1200,0);var result=state.evaluate(order,f.vehicle(),0);assertThat(result.evaluation().feasible()).isTrue();assertThat(exact.evaluate(order,f.vehicle(),result.departure()).feasible()).isTrue();
  var p=SectionProblemFactory.build(f.catalog(),f.net(),f.roads()).problem();var vehicle=p.getVehicles().iterator().next();var route=hetero?VehicleRoute.Builder.newInstance(vehicle).setJobActivityFactory(p.getJobActivityFactory()).addService((Service)p.getJobs().get("A")).build():VehicleRoute.emptyRoute();
  var states=new StateManager(p);var constraints=new ConstraintManager(p,states);var adapter=new SectionInsertionAdapter(state,p);constraints.addConstraint(adapter,ConstraintManager.Priority.CRITICAL);var nativeCalc=new ServiceInsertionCalculatorFactory().create(p,adapter,p.getJobActivityFactory(),constraints);
  var insertion=nativeCalc.getInsertionData(route,p.getJobs().get("B"),vehicle,0,route.getDriver(),Double.MAX_VALUE);assertThat(insertion.getDeliveryInsertionIndex()).isEqualTo(hetero?1:0);assertThat(insertion.getInsertionCost()).isFinite();
  var solution=new hagrid.core.routing.CarrierOptimizationService().searchSections(f.catalog(),f.net(),f.roads(),1200,1200,0,4711,new SectionSearchPolicy(5,.25,.4));assertThat(solution.solution().getUnassignedJobs()).isEmpty();assertThat(solution.solution().getRoutes()).hasSize(1);var actual=solution.solution().getRoutes().iterator().next();assertThat(exact.evaluate(order,f.vehicle(),actual.getDepartureTime()).feasible()).isTrue();
 }
 @Test void accumulatedRoundingMayRequireSeveralPredecessors(){
  double[] parts={27.73976621305828,24.32729600468106,29.74612082666778,10.782084959327234,26.570394252894705,9.691842081319109,8.560228731669959,12.823629419030134,0.7881860212028813,21.72460758885448,2.7461016808331133,18.416516077597525,1.8610618456863393,12.873597644270921,7.655736010171042,28.36138198751801,9.024034181735207,10.757694449066731,9.278786851976797,3.835984243353289,19.359761068702266,9.149919001020194,8.205901293160794,9.446889548947773,10.655803850402922,23.825109236122785,6.570522891845161,3.803580327703553,9.98516867210439,17.14050995940055,8.545446283201484,3.377694395359158,10.78357147534839,28.8257141358066,26.886695701403728};
  double release=21669.167923690926;var carrier=CarriersUtils.createCarrier(Id.create("multi",Carrier.class));var jobs=new ArrayList<String>();var links=new ArrayList<Id<Link>>();
  for(int i=0;i<parts.length;i++){String id="j"+i;jobs.add(id);links.add(Id.createLinkId(id));double start=i==parts.length-1?release:0,end=i==parts.length-1?release:30000;CarriersUtils.addService(carrier,CarrierService.Builder.newInstance(Id.create(id,CarrierService.class),Id.createLinkId(id),1).setServiceDuration(0).setServiceStartingTimeWindow(TimeWindow.newInstance(start,end)).build());}
  var type=VehicleUtils.createVehicleType(Id.create("multi-type",VehicleType.class),"car");type.getCapacity().setOther(100);type.getCostInformation().setCostsPerMeter(0.0).setCostsPerSecond(0.0).setFixedCost(0.0);VehicleUtils.setCostsPerSecondWaiting(type.getCostInformation(),10.0);
  var vehicle=CarrierVehicle.Builder.newInstance(Id.createVehicleId("multi-v"),Id.createLinkId("d"),type).setEarliestStart(0).setLatestEnd(30000).build();var section=new DeliverySection("S",jobs,jobs.size(),List.of(new SectionVariant(0,jobs,links)));
  SectionRoadCosts roads=new SectionRoadCosts(){public SectionLeg leg(Id<Link>a,Id<Link>b,VehicleType t){String id=b.toString();return new SectionLeg(0,id.startsWith("j")?parts[Integer.parseInt(id.substring(1))]:0,0);}public RoadLeg materialize(Id<Link>a,Id<Link>b,double x,Vehicle v){throw new UnsupportedOperationException();}public String fingerprint(){return "multi";}};
  var catalog=new SectionCatalog(carrier,List.of(section),"multi");double elapsed=0;for(double part:parts)elapsed+=part;double candidate=release-elapsed;var exact=new SectionRouteEvaluator(catalog,roads,500,500,0);
  assertThat(exact.evaluate(List.of("S"),vehicle,candidate).feasible()).isFalse();assertThat(exact.evaluate(List.of("S"),vehicle,Math.nextDown(candidate)).feasible()).isFalse();
  var result=new SectionSearchState(catalog,roads,500,500,0).evaluate(List.of("S"),vehicle,0);assertThat(result.evaluation().feasible()).isTrue();assertThat(exact.evaluate(List.of("S"),vehicle,result.departure()).feasible()).isTrue();assertThat(result.evaluation().variableCost()).isLessThan(0.000001);
 } @Test void broadCommonReleaseKeepsBoundedCandidateWork(){
  var carrier=CarriersUtils.createCarrier(Id.create("broad",Carrier.class));var ids=List.of("a","b");var sections=new ArrayList<DeliverySection>();for(String id:ids){CarriersUtils.addService(carrier,CarrierService.Builder.newInstance(Id.create(id,CarrierService.class),Id.createLinkId(id),1).setServiceDuration(1).setServiceStartingTimeWindow(TimeWindow.newInstance(100,10000)).build());sections.add(new DeliverySection(id,List.of(id),1,List.of(new SectionVariant(0,List.of(id),List.of(Id.createLinkId(id))))));}
  var type=VehicleUtils.createVehicleType(Id.create("broad-type",VehicleType.class),"car");type.getCapacity().setOther(10);type.getCostInformation().setCostsPerMeter(0.0).setCostsPerSecond(0.0).setFixedCost(0.0);VehicleUtils.setCostsPerSecondWaiting(type.getCostInformation(),10.0);var vehicle=CarrierVehicle.Builder.newInstance(Id.createVehicleId("broad-v"),Id.createLinkId("d"),type).setEarliestStart(0).setLatestEnd(20000).build();var calls=new java.util.concurrent.atomic.AtomicInteger();
  SectionRoadCosts roads=new SectionRoadCosts(){public SectionLeg leg(Id<Link>a,Id<Link>b,VehicleType t){calls.incrementAndGet();return new SectionLeg(0,10,0);}public RoadLeg materialize(Id<Link>a,Id<Link>b,double x,Vehicle v){throw new UnsupportedOperationException();}public String fingerprint(){return "broad";}};
  var result=new SectionSearchState(new SectionCatalog(carrier,sections,"broad"),roads,1000,1000,0).evaluate(ids,vehicle,0);assertThat(result.evaluation().feasible()).isTrue();assertThat(result.departure()).isEqualTo(90);assertThat(calls.get()).isLessThanOrEqualTo(20);
 } @Test void roundedVehicleEndDeadlineRetainsFeasiblePredecessor(){
  var f=fixture(false,true);var order=List.of("B");var evaluator=new SectionRouteEvaluator(f.catalog(),f.roads(),1200,1200,0);double rounded=RELEASE-ACCESS;
  assertThat(evaluator.evaluate(order,f.vehicle(),rounded).feasible()).isFalse();assertThat(evaluator.evaluate(order,f.vehicle(),Math.nextDown(rounded)).feasible()).isTrue();
  var choice=new SectionSearchState(f.catalog(),f.roads(),1200,1200,0).evaluate(order,f.vehicle(),0);assertThat(choice.evaluation().feasible()).isTrue();assertThat(evaluator.evaluate(order,f.vehicle(),choice.departure()).feasible()).isTrue();
 }}






