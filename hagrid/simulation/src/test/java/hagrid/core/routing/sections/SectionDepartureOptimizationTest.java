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
class SectionDepartureOptimizationTest {
 record Fixture(SectionCatalog catalog,SectionRoadCosts roads,CarrierVehicle vehicle,Network net) {}
 Fixture fixture(boolean twoDirections) {
  var carrier=CarriersUtils.createCarrier(Id.create("c",Carrier.class));
  for(var e:Map.of("a",0.0,"b",100.0).entrySet())CarriersUtils.addService(carrier,CarrierService.Builder.newInstance(Id.create(e.getKey(),CarrierService.class),Id.createLinkId(e.getKey()),1).setServiceDuration(0).setServiceStartingTimeWindow(TimeWindow.newInstance(e.getValue(),200)).build());
  var type=VehicleUtils.createVehicleType(Id.create("v",VehicleType.class),"car");type.getCapacity().setOther(3);type.getCostInformation().setCostsPerMeter(1.0).setCostsPerSecond(0.0).setFixedCost(0.0);VehicleUtils.setCostsPerSecondWaiting(type.getCostInformation(),10.0);
  var v=CarrierVehicle.Builder.newInstance(Id.createVehicleId("v"),Id.createLinkId("d"),type).setEarliestStart(0).setLatestEnd(200).build();carrier.setCarrierCapabilities(CarrierCapabilities.Builder.newInstance().addVehicle(v).setFleetSize(CarrierCapabilities.FleetSize.FINITE).build());
  var a=new DeliverySection("A",List.of("a"),1,twoDirections?List.of(new SectionVariant(0,List.of("a"),List.of(Id.createLinkId("a"))),new SectionVariant(1,List.of("a"),List.of(Id.createLinkId("r")))):List.of(new SectionVariant(0,List.of("a"),List.of(Id.createLinkId("a")))));
  var b=new DeliverySection("B",List.of("b"),1,List.of(new SectionVariant(0,List.of("b"),List.of(Id.createLinkId("b")))));
  SectionRoadCosts roads=new SectionRoadCosts(){public SectionLeg leg(Id<Link> from,Id<Link> to,VehicleType t){if(!twoDirections)return from.toString().equals("b")&&to.toString().equals("a")?new SectionLeg(Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,0):new SectionLeg(0,0,0);int q=Math.floorMod((from+">"+to).hashCode(),4);return new SectionLeg(q,q,0);}public RoadLeg materialize(Id<Link>x,Id<Link>y,double z,Vehicle t){throw new UnsupportedOperationException();}public String fingerprint(){return "static";}};
  var net=NetworkUtils.createNetwork();int x=0;for(String id:List.of("d","a","b","r")){var n=NetworkUtils.createAndAddNode(net,Id.createNodeId(id+"x"),new Coord(x++,0));var m=NetworkUtils.createAndAddNode(net,Id.createNodeId(id+"y"),new Coord(x++,0));NetworkUtils.createAndAddLink(net,Id.createLinkId(id),n,m,1,1,1,1);}
  return new Fixture(new SectionCatalog(carrier,List.of(a,b),"fixture"),roads,v,net);
 }
 @Test void laterReleaseRequiresLaterDepartureAndRouteChangesMayMoveEarlier(){
  var f=fixture(false);var state=new SectionSearchState(f.catalog(),f.roads(),20,20,0);var choice=state.evaluate(List.of("A","B"),f.vehicle(),0);
  assertThat(choice.evaluation().feasible()).isTrue();assertThat(choice.departure()).isEqualTo(100);assertThat(choice.evaluation().variableCost()).isEqualTo(0);
  var earlier=state.evaluate(List.of("A","B"),f.vehicle(),150);assertThat(earlier.departure()).isEqualTo(100);
 }
 @Test void nativeInsertionUsesLaterReleaseBeforeRejectingGap(){
  var f=fixture(false);var problem=SectionProblemFactory.build(f.catalog(),f.net(),f.roads()).problem();var state=new SectionSearchState(f.catalog(),f.roads(),20,20,0);var adapter=new SectionInsertionAdapter(state,problem);var states=new StateManager(problem);var constraints=new ConstraintManager(problem,states);constraints.addConstraint(adapter,ConstraintManager.Priority.CRITICAL);
  var vehicle=problem.getVehicles().iterator().next();var route=VehicleRoute.Builder.newInstance(vehicle).setDepartureTime(0).addService((Service)problem.getJobs().get("A")).build();var nativeCalc=new ServiceInsertionCalculatorFactory().create(problem,adapter,problem.getJobActivityFactory(),constraints);
  var insertion=nativeCalc.getInsertionData(route,problem.getJobs().get("B"),vehicle,0,route.getDriver(),Double.MAX_VALUE);assertThat(insertion.getDeliveryInsertionIndex()).isEqualTo(1);assertThat(insertion.getInsertionCost()).isEqualTo(0);
  var result=new hagrid.core.routing.CarrierOptimizationService().searchSections(f.catalog(),f.net(),f.roads(),20,20,0,4711,new SectionSearchPolicy(10,.25,.4));assertThat(result.solution().getUnassignedJobs()).isEmpty();var actual=result.solution().getRoutes().iterator().next();assertThat(actual.getDepartureTime()).isEqualTo(100);assertThat(result.solution().getCost()).isEqualTo(0);
 }
 @Test void selectedDepartureAndOrientationMatchExhaustiveIntegerOracle(){
  var f=fixture(true);var state=new SectionSearchState(f.catalog(),f.roads(),20,20,0);double best=Double.POSITIVE_INFINITY,bestEnd=Double.POSITIVE_INFINITY;int bestVariant=-1;
  for(int d=0;d<=200;d++)for(int variant=0;variant<2;variant++){
   var first=Id.<Link>createLinkId(variant==0?"a":"r");var a=f.roads().leg(Id.createLinkId("d"),first,f.vehicle().getType());var b=f.roads().leg(first,Id.createLinkId("b"),f.vehicle().getType());var c=f.roads().leg(Id.createLinkId("b"),Id.createLinkId("d"),f.vehicle().getType());
   double t=d+a.seconds(),cost=a.meters();if(t>200)continue;double start=Math.max(t,0);cost+=(start-t)*10;t=start+b.seconds();cost+=b.meters();start=Math.max(t,100);if(start>200)continue;cost+=(start-t)*10;t=start+c.seconds();cost+=c.meters();if(t>200||t-d>20)continue;
   if(cost<best||cost==best&&(t<bestEnd||t==bestEnd&&variant<bestVariant)){best=cost;bestEnd=t;bestVariant=variant;}
  }
  var selected=state.evaluate(List.of("A","B"),f.vehicle(),150);assertThat(selected.evaluation().feasible()).isTrue();assertThat(selected.evaluation().variableCost()).isEqualTo(best);assertThat(selected.evaluation().endTime()).isEqualTo(bestEnd);assertThat(selected.evaluation().visits().getFirst().variant()).isEqualTo(bestVariant);
 } @Test void zeroWaitingRateIncludesDurationLimitedEarliestFeasibleDeparture(){
  var f=fixture(false);VehicleUtils.setCostsPerSecondWaiting(f.vehicle().getType().getCostInformation(),0.0);
  var state=new SectionSearchState(f.catalog(),f.roads(),20,20,0);var choice=state.evaluate(List.of("A","B"),f.vehicle(),150);
  assertThat(choice.evaluation().feasible()).isTrue();assertThat(choice.departure()).isEqualTo(80);assertThat(choice.evaluation().endTime()).isEqualTo(100);
 } @Test void detectsCatalogMutationBeforeAndDuringSearch(){
  var f=fixture(false);f.catalog().originalCarrier().getServices().values().iterator().next().getAttributes().putAttribute("tampered","yes");
  assertThatThrownBy(()->new hagrid.core.routing.CarrierOptimizationService().searchSections(f.catalog(),f.net(),f.roads(),20,20,0,4711,new SectionSearchPolicy(1,.25,.4))).isInstanceOf(IllegalStateException.class).hasMessageContaining("mutated");
  var g=fixture(false);SectionRoadCosts mutating=new SectionRoadCosts(){boolean changed;public SectionLeg leg(Id<Link>a,Id<Link>b,VehicleType t){if(!changed){changed=true;g.catalog().originalCarrier().getServices().values().iterator().next().getAttributes().putAttribute("tampered","yes");}return g.roads().leg(a,b,t);}public RoadLeg materialize(Id<Link>a,Id<Link>b,double time,Vehicle v){return g.roads().materialize(a,b,time,v);}public String fingerprint(){return "mutation";}};
  assertThatThrownBy(()->new hagrid.core.routing.CarrierOptimizationService().searchSections(g.catalog(),g.net(),mutating,20,20,0,4711,new SectionSearchPolicy(1,.25,.4))).isInstanceOf(IllegalStateException.class).hasMessageContaining("mutated");
 } @Test void cacheEvictionAndNativeRouteCopyRecreateSameTrace(){
  var f=fixture(true);var state=new SectionSearchState(f.catalog(),f.roads(),20,20,0);var expected=state.evaluate(List.of("A","B"),f.vehicle(),0);
  for(int i=0;i<1100;i++)state.evaluate(List.of("A","B"),f.vehicle(),i);
  assertThat(state.evaluate(List.of("A","B"),f.vehicle(),0)).isEqualTo(expected);
  var p=SectionProblemFactory.build(f.catalog(),f.net(),f.roads()).problem();var v=p.getVehicles().iterator().next();var route=VehicleRoute.Builder.newInstance(v).setDepartureTime(expected.departure()).addService((Service)p.getJobs().get("A")).addService((Service)p.getJobs().get("B")).build();
  assertThat(state.evaluate(VehicleRoute.copyOf(route))).isEqualTo(state.evaluate(route));
  var ruined=VehicleRoute.copyOf(route);assertThat(ruined.getTourActivities().removeJob(p.getJobs().get("B"))).isTrue();state.evaluate(ruined);
  ruined.getTourActivities().addActivity(p.copyAndGetActivities(p.getJobs().get("B")).getFirst());assertThat(state.evaluate(ruined)).isEqualTo(state.evaluate(route));
 } @Test void invalidInitialRouteCannotSurviveAsBestEver(){
  var f=fixture(false);SectionRoadCosts blocked=new SectionRoadCosts(){public SectionLeg leg(Id<Link>a,Id<Link>b,VehicleType t){return b.toString().equals("b")?new SectionLeg(Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,0):f.roads().leg(a,b,t);}public RoadLeg materialize(Id<Link>a,Id<Link>b,double x,Vehicle v){throw new UnsupportedOperationException();}public String fingerprint(){return "blocked";}};
  var p=SectionProblemFactory.build(f.catalog(),f.net(),blocked).problem();var state=new SectionSearchState(f.catalog(),blocked,20,20,0);var a=SectionInsertionAdapter.configure(p,state,4711,new SectionSearchPolicy(1,.25,.4));
  var v=p.getVehicles().iterator().next();var route=VehicleRoute.Builder.newInstance(v).setJobActivityFactory(p.getJobActivityFactory()).addService((Service)p.getJobs().get("A")).addService((Service)p.getJobs().get("B")).build();var invalid=new com.graphhopper.jsprit.core.problem.solution.VehicleRoutingProblemSolution(List.of(route),0);
  var acceptor=a.getSearchStrategyManager().getStrategies().getFirst().getSolutionAcceptor();
  assertThat(acceptor.acceptSolution(new ArrayList<>(),invalid)).isFalse();assertThat(invalid.getCost()).isEqualTo(Double.POSITIVE_INFINITY);
  invalid.setCost(0);a.addInitialSolution(invalid);assertThatThrownBy(a::searchSolutions).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Invalid initial");
 } @Test void unreachableJobRemainsUnassignedWithNativePenaltyReconciled(){
  var f=fixture(false);SectionRoadCosts blocked=new SectionRoadCosts(){public SectionLeg leg(Id<Link>a,Id<Link>b,VehicleType t){return b.toString().equals("b")?new SectionLeg(Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,0):f.roads().leg(a,b,t);}public RoadLeg materialize(Id<Link>a,Id<Link>b,double x,Vehicle v){throw new UnsupportedOperationException();}public String fingerprint(){return "blocked";}};
  var p=SectionProblemFactory.build(f.catalog(),f.net(),blocked).problem();var neighborhoods=new com.graphhopper.jsprit.core.algorithm.ruin.JobNeighborhoodsFactory().createNeighborhoods(p,new com.graphhopper.jsprit.core.algorithm.ruin.distance.AvgServiceAndShipmentDistance(p.getTransportCosts()),(int)(p.getJobs().size()*.5));neighborhoods.initialise();double penalty=2*neighborhoods.getMaxDistance()*(11-p.getJobs().get("B").getPriority());
  var result=new hagrid.core.routing.CarrierOptimizationService().searchSections(f.catalog(),f.net(),blocked,20,20,0,4711,new SectionSearchPolicy(5,.25,.4));assertThat(result.solution().getUnassignedJobs()).extracting(com.graphhopper.jsprit.core.problem.job.Job::getId).containsExactly("B");
  double served=result.evaluations().values().stream().mapToDouble(c->c.evaluation().variableCost()).sum();assertThat(result.solution().getCost()).isEqualTo(served+penalty);
 } @Test void acceptancePersistsFeasibleOptimizedDepartureBeforeBestEver(){
  var f=fixture(false);var p=SectionProblemFactory.build(f.catalog(),f.net(),f.roads()).problem();var state=new SectionSearchState(f.catalog(),f.roads(),20,20,0);var alg=SectionInsertionAdapter.configure(p,state,4711,new SectionSearchPolicy(1,.25,.4));
  var v=p.getVehicles().iterator().next();var route=VehicleRoute.Builder.newInstance(v).setJobActivityFactory(p.getJobActivityFactory()).setDepartureTime(0).addService((Service)p.getJobs().get("A")).addService((Service)p.getJobs().get("B")).build();
  var candidate=new com.graphhopper.jsprit.core.problem.solution.VehicleRoutingProblemSolution(List.of(route),-42);var acceptor=alg.getSearchStrategyManager().getStrategies().getFirst().getSolutionAcceptor();
  assertThat(acceptor.acceptSolution(new ArrayList<>(),candidate)).isTrue();assertThat(route.getDepartureTime()).isEqualTo(100);assertThat(route.getStart().getEndTime()).isEqualTo(100);assertThat(alg.getObjectiveFunction().getCosts(candidate)).isEqualTo(candidate.getCost());
 }}












