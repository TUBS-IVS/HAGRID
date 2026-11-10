package hagrid.core.routing.sections;

import hagrid.core.routing.RoadLeg;
import com.graphhopper.jsprit.core.problem.constraint.HardActivityConstraint.ConstraintsStatus;
import com.graphhopper.jsprit.core.problem.job.Service;
import com.graphhopper.jsprit.core.problem.misc.*;
import com.graphhopper.jsprit.core.problem.solution.route.VehicleRoute;
import org.matsim.core.network.NetworkUtils;
import org.matsim.api.core.v01.*;
import org.matsim.api.core.v01.network.*;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.freight.carriers.*;
import org.matsim.vehicles.*;
import java.util.*;


public class SectionVersionBridge {
 public static void main(String[] args) {
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
  if(result.iterations()!=100 || !result.solution().getUnassignedJobs().isEmpty() || result.solution().getRoutes().size()!=1)throw new AssertionError("Iteration/assignment mismatch");
  var route=result.solution().getRoutes().iterator().next();
  if(route.getDepartureTime()!=90 || result.solution().getCost()!=5 || result.evaluations().get(route).evaluation().variableCost()!=0)throw new AssertionError("Departure/cost mismatch");
  gapFixture();
  infiniteFixture();
  switchFixture();
  laterReleaseFixture();
  ruinFixture();
  System.out.println("SECTION_BRIDGE_OK core="+com.graphhopper.jsprit.core.algorithm.box.Jsprit.class.getProtectionDomain().getCodeSource().getLocation()+" iterations="+result.iterations()+" departure="+route.getDepartureTime()+" cost="+result.solution().getCost());
 }
 static void gapFixture() {
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
  if(before.evaluation().visits().getFirst().variant()!=0 || after.evaluation().visits().getFirst().variant()!=1)throw new AssertionError("orientation mismatch");
  if(adapter.fulfilled(facts,route.getActivities().getFirst(),inserted,route.getEnd(),0)!=ConstraintsStatus.FULFILLED)throw new AssertionError("hard callback mismatch");
  if(adapter.getCosts(facts,route.getActivities().getFirst(),route.getEnd(),inserted,0)!=5)throw new AssertionError("gap delta mismatch");
  var result=new hagrid.core.routing.CarrierOptimizationService().searchSections(catalog,net,roads,1000,1000,0,4711,new SectionSearchPolicy(100,.25,.40));
  if(!result.solution().getUnassignedJobs().isEmpty() || result.solution().getRoutes().size()!=1 || result.solution().getCost()!=12)throw new AssertionError("native objective mismatch "+result.solution().getCost());
  var actual=result.evaluations().values().iterator().next().evaluation();
  if(actual.visits().size()!=2 || actual.visits().getFirst().variant()!=1)throw new AssertionError("native orientation mismatch");
  System.out.println("SECTION_GAP_OK delta=5 objective=12 orientation=1");
 } static void infiniteFixture() {
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
  if(result.iterations()!=100 || !result.solution().getUnassignedJobs().isEmpty() || result.solution().getRoutes().size()!=2 || result.solution().getCost()!=10)throw new AssertionError("Infinite fleet mismatch");
  for(var route:result.solution().getRoutes())if(route.getDepartureTime()!=90 || route.getVehicle().getEarliestDeparture()!=0 || route.getVehicle().getLatestArrival()!=300 || !route.getVehicle().getStartLocation().getId().equals("d"))throw new AssertionError("Infinite template mismatch");
  System.out.println("SECTION_INFINITE_OK routes=2 cost=10");
 } static void switchFixture() {
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
  var t=problem.getTransportCosts();var first=route.getActivities().getFirst().getLocation();
  double nativeAccess=t.getTransportCost(switched.getStartLocation(),first,0,route.getDriver(),switched)-t.getTransportCost(route.getStart().getLocation(),first,0,route.getDriver(),vehicle)
    +t.getTransportCost(first,switched.getEndLocation(),0,route.getDriver(),switched)-t.getTransportCost(first,route.getEnd().getLocation(),0,route.getDriver(),vehicle);
  if(adapter.fulfilled(facts,route.getActivities().getFirst(),inserted,route.getEnd(),0)!=ConstraintsStatus.FULFILLED)throw new AssertionError("Switch rejected");
  if(adapter.getCosts(facts,route.getActivities().getFirst(),route.getEnd(),inserted,0)+nativeAccess!=12)throw new AssertionError("Switch delta");
  var states=new com.graphhopper.jsprit.core.algorithm.state.StateManager(problem);
  var constraints=new com.graphhopper.jsprit.core.problem.constraint.ConstraintManager(problem,states);
  constraints.addConstraint(adapter,com.graphhopper.jsprit.core.problem.constraint.ConstraintManager.Priority.CRITICAL);
  var nativeCalculator=new com.graphhopper.jsprit.core.algorithm.recreate.ServiceInsertionCalculatorFactory().create(problem,adapter,problem.getJobActivityFactory(),constraints);
  var nativeInsertion=nativeCalculator.getInsertionData(route,job,switched,0,route.getDriver(),Double.MAX_VALUE);
  if(nativeInsertion.getDeliveryInsertionIndex()!=1)throw new AssertionError("Switch gap");
  if(nativeInsertion.getInsertionCost()!=12)throw new AssertionError("Native switch cost "+nativeInsertion.getInsertionCost());
  var shortShift=com.graphhopper.jsprit.core.problem.vehicle.VehicleImpl.Builder.newInstance(switched).setLatestArrival(2).build();
  var impossible=new JobInsertionContext(route,job,shortShift,route.getDriver(),0);impossible.setActivityContext(context);
  if(adapter.fulfilled(impossible,route.getActivities().getFirst(),inserted,route.getEnd(),0)!=ConstraintsStatus.NOT_FULFILLED)throw new AssertionError("Expected non-breaking rejection");
  System.out.println("SECTION_SWITCH_OK delta=12 gap=1 calculator="+nativeCalculator.getClass().getProtectionDomain().getCodeSource().getLocation());
 }
 record Fixture(SectionCatalog catalog,SectionRoadCosts roads,CarrierVehicle vehicle,Network net) {}
 static Fixture departureFixture(boolean twoDirections) {
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

 static void laterReleaseFixture(){
  var f=departureFixture(false);var problem=SectionProblemFactory.build(f.catalog(),f.net(),f.roads()).problem();var state=new SectionSearchState(f.catalog(),f.roads(),20,20,0);var adapter=new SectionInsertionAdapter(state,problem);var states=new com.graphhopper.jsprit.core.algorithm.state.StateManager(problem);var constraints=new com.graphhopper.jsprit.core.problem.constraint.ConstraintManager(problem,states);constraints.addConstraint(adapter,com.graphhopper.jsprit.core.problem.constraint.ConstraintManager.Priority.CRITICAL);
  var vehicle=problem.getVehicles().iterator().next();var route=VehicleRoute.Builder.newInstance(vehicle).setDepartureTime(0).addService((Service)problem.getJobs().get("A")).build();var calc=new com.graphhopper.jsprit.core.algorithm.recreate.ServiceInsertionCalculatorFactory().create(problem,adapter,problem.getJobActivityFactory(),constraints);
  var insertion=calc.getInsertionData(route,problem.getJobs().get("B"),vehicle,0,route.getDriver(),Double.MAX_VALUE);if(insertion.getDeliveryInsertionIndex()!=1 || insertion.getInsertionCost()!=0)throw new AssertionError("later release native gap");
  var result=new hagrid.core.routing.CarrierOptimizationService().searchSections(f.catalog(),f.net(),f.roads(),20,20,0,4711,new SectionSearchPolicy(10,.25,.4));if(!result.solution().getUnassignedJobs().isEmpty()||result.solution().getRoutes().size()!=1||result.solution().getRoutes().iterator().next().getDepartureTime()!=100)throw new AssertionError("later release search");
  var earlier=state.evaluate(List.of("A","B"),f.vehicle(),150);if(earlier.departure()!=100)throw new AssertionError("earlier route change");
  System.out.println("SECTION_LATER_RELEASE_OK gap=1 departure=100");
 }
 static void ruinFixture(){
  for(int n:List.of(250,251,500,501)){
   var type=com.graphhopper.jsprit.core.problem.vehicle.VehicleTypeImpl.Builder.newInstance("v").addCapacityDimension(0,n+1).build();
   var depot=com.graphhopper.jsprit.core.problem.Location.Builder.newInstance().setId("d").setCoordinate(com.graphhopper.jsprit.core.util.Coordinate.newInstance(0,0)).build();
   var vehicle=com.graphhopper.jsprit.core.problem.vehicle.VehicleImpl.Builder.newInstance("v").setType(type).setStartLocation(depot).build();
   var builder=com.graphhopper.jsprit.core.problem.VehicleRoutingProblem.Builder.newInstance().addVehicle(vehicle).setRoutingCost(new SectionProblemFactory.GeometryCosts());
   var jobs=new ArrayList<Service>();for(int i=0;i<n;i++){var loc=com.graphhopper.jsprit.core.problem.Location.Builder.newInstance().setId("j"+i).setCoordinate(com.graphhopper.jsprit.core.util.Coordinate.newInstance(i+1,0)).build();var j=Service.Builder.newInstance("j"+i).setLocation(loc).addSizeDimension(0,1).build();jobs.add(j);builder.addJob(j);}
   var p=builder.build();var carrier=CarriersUtils.createCarrier(Id.create("empty",Carrier.class));var catalog=new SectionCatalog(carrier,List.of(),"test");
   SectionRoadCosts roads=new SectionRoadCosts(){public SectionLeg leg(Id<Link>a,Id<Link>b,VehicleType t){return new SectionLeg(0,0,0);}public RoadLeg materialize(Id<Link>a,Id<Link>b,double x,org.matsim.vehicles.Vehicle v){throw new UnsupportedOperationException();}public String fingerprint(){return "test";}};
   var algorithm=SectionInsertionAdapter.configure(p,new SectionSearchState(catalog,roads,10000,10000,0),4711,new SectionSearchPolicy(1,.25,.4));
   var rb=VehicleRoute.Builder.newInstance(p.getVehicles().iterator().next());for(var job:jobs)rb.addService(job);var route=rb.build();
   boolean radial=false,random=false;
   for(var strategy:algorithm.getSearchStrategyManager().getStrategies())for(var module:strategy.getSearchStrategyModules())if(module instanceof com.graphhopper.jsprit.core.algorithm.module.RuinAndRecreateModule r){String name=strategy.getId().toLowerCase(Locale.ROOT);if(!radial&&name.contains("radial")){if(r.getRuin().ruin(new ArrayList<>(List.of(VehicleRoute.copyOf(route)))).size()!=Math.max(1,(int)(n*.25)))throw new AssertionError("radial count "+n);radial=true;}if(!random&&name.contains("random")){if(r.getRuin().ruin(new ArrayList<>(List.of(VehicleRoute.copyOf(route)))).size()!=Math.max(1,(int)(n*.4)))throw new AssertionError("random count "+n);random=true;}}
   if(!radial||!random)throw new AssertionError("Missing ruin strategies");
  }
  System.out.println("SECTION_RUIN_OK sizes=250,251,500,501 shares=.25,.4");
 }
}









