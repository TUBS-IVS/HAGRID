package hagrid.core.routing.sections;
import hagrid.core.routing.NativeModalTransportCosts;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.*;
import org.matsim.api.core.v01.network.*;
import org.matsim.core.network.NetworkUtils;
import org.matsim.freight.carriers.*;
import org.matsim.vehicles.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class StaticSectionRoadCostsTest {
 @Test void tariffChangesTheChosenPathAndMaterializedRoadMatchesScalars(){
  var n=NetworkUtils.createNetwork();for(String id:List.of("a","b","c","z"))n.addNode(n.getFactory().createNode(Id.createNodeId(id),new Coord(0,0)));
  link(n,"forward","a","b",1);link(n,"reverse","b","a",1);link(n,"detour1","b","c",1);link(n,"detour2","c","a",1);link(n,"target","a","z",1);
  n.getLinks().get(Id.createLinkId("forward")).getAttributes().putAttribute("osm:way:id","road-1");n.getLinks().get(Id.createLinkId("reverse")).getAttributes().putAttribute("osm:way:id","road-1");
  var type=VehicleUtils.createVehicleType(Id.create("van",VehicleType.class),"car");type.setMaximumVelocity(1);type.getCapacity().setOther(10);
  type.getCostInformation().setCostsPerMeter(1.0).setCostsPerSecond(0.0);
  var v=CarrierVehicle.Builder.newInstance(Id.createVehicleId("v"),Id.createLinkId("forward"),type).setEarliestStart(0).setLatestEnd(100).build();
  var registry=new CarrierVehicleTypes();registry.getVehicleTypes().put(type.getId(),type);
  var provider=NativeModalTransportCosts.create(n,registry,"car");
  var children=new LinkedHashMap<Id<Link>,List<Id<Link>>>();n.getLinks().keySet().forEach(id->children.put(id,List.of(id)));
  var split=new SplitRoadNetwork(n,children,Map.of("stop",List.of(new HaltApproach(Id.createLinkId("target"),1,Id.createLinkId("target")))),"fixture");
  var low=StaticSectionRoadCosts.prepare(split,List.of(v),provider,0);var high=StaticSectionRoadCosts.prepare(split,List.of(v),provider,5);
  var from=Id.createLinkId("forward");var to=Id.createLinkId("target");
  assertThat(low.leg(from,to,type)).isEqualTo(new SectionLeg(2,2,1));
  assertThat(high.leg(from,to,type)).isEqualTo(new SectionLeg(3,3,0));
  var vehicle=VehicleUtils.createVehicle(v.getId(),type);var path=high.materialize(from,to,0,vehicle);
  assertThat(path.route().getLinkIds()).containsExactly(Id.createLinkId("detour1"),Id.createLinkId("detour2"));
  assertThat(path.distanceMeters()).isEqualTo(high.leg(from,to,type).meters());
  assertThat(low.fingerprint()).isNotEqualTo(high.fingerprint());
  assertThat(high.leg(to,to,type)).isEqualTo(new SectionLeg(0,0,0));
  assertThat(high.leg(to,from,type).meters()).isEqualTo(Double.POSITIVE_INFINITY);
  var equal=VehicleUtils.createVehicleType(type.getId(),"car");equal.setMaximumVelocity(1);equal.getCostInformation().setCostsPerMeter(1.0).setCostsPerSecond(0.0);
  assertThat(high.leg(from,to,equal)).isEqualTo(high.leg(from,to,type));int rowCount=high.cachedRowCount();
  equal.setMaximumVelocity(2);assertThatThrownBy(()->high.leg(from,to,equal)).hasMessageContaining("changed vehicle profile");equal.setMaximumVelocity(1);
  equal.setNetworkMode("bike");assertThatThrownBy(()->high.leg(from,to,equal)).hasMessageContaining("changed vehicle profile");equal.setNetworkMode("car");
  equal.getCostInformation().setCostsPerMeter(2.0);assertThatThrownBy(()->high.leg(from,to,equal)).hasMessageContaining("changed vehicle profile");equal.getCostInformation().setCostsPerMeter(1.0);
  equal.getCostInformation().setCostsPerSecond(-0.0);assertThatThrownBy(()->high.leg(from,to,equal)).hasMessageContaining("changed vehicle profile");equal.getCostInformation().setCostsPerSecond(0.0);
  assertThat(high.cachedRowCount()).isEqualTo(rowCount);assertThat(high.leg(from,to,equal)).isEqualTo(high.leg(from,to,type));
  var custom=NativeModalTransportCosts.create(n,registry,"car",(l,t,p,veh)->l.getLength());
  assertThatThrownBy(()->StaticSectionRoadCosts.prepare(split,List.of(v),custom,0)).hasMessageContaining("certified static");
 }
 private static void link(Network n,String id,String from,String to,double length){var l=n.getFactory().createLink(Id.createLinkId(id),n.getNodes().get(Id.createNodeId(from)),n.getNodes().get(Id.createNodeId(to)));l.setLength(length);l.setFreespeed(1);l.setCapacity(1000);l.setNumberOfLanes(1);l.setAllowedModes(Set.of("car"));n.addLink(l);}
 @Test void splitRoadHaltsRetainInternalDistanceAndOriginalTurnRestrictions(){
  var n=NetworkUtils.createNetwork();var a=NetworkUtils.createAndAddNode(n,Id.createNodeId("a"),new Coord(0,0));var b=NetworkUtils.createAndAddNode(n,Id.createNodeId("b"),new Coord(5000,0));
  var forward=NetworkUtils.createAndAddLink(n,Id.createLinkId("f"),a,b,5000,10,1000,1);var reverse=NetworkUtils.createAndAddLink(n,Id.createLinkId("r"),b,a,5000,10,1000,1);forward.setAllowedModes(Set.of("car"));reverse.setAllowedModes(Set.of("car"));
  var f=new PreparedHaltCatalog.Road("a","b",5000,5000,List.of(new PreparedHaltCatalog.Point(0,0),new PreparedHaltCatalog.Point(5000,0)));
  var r=new PreparedHaltCatalog.Road("b","a",5000,5000,List.of(new PreparedHaltCatalog.Point(5000,0),new PreparedHaltCatalog.Point(0,0)));
  var jobs=List.of(new PreparedHaltCatalog.Job("j100","f",List.of(new PreparedHaltCatalog.Approach("f",.02),new PreparedHaltCatalog.Approach("r",.98)),"",""),new PreparedHaltCatalog.Job("j180","f",List.of(new PreparedHaltCatalog.Approach("f",.036),new PreparedHaltCatalog.Approach("r",.964)),"",""));
  var prepared=new PreparedHaltCatalog("c",Map.of("f",f,"r",r),jobs,"f");
  var type=VehicleUtils.createVehicleType(Id.create("split-type",VehicleType.class),"car");type.setMaximumVelocity(10);type.getCapacity().setOther(10);type.getCostInformation().setCostsPerMeter(1.0).setCostsPerSecond(0.0);
  var v=CarrierVehicle.Builder.newInstance(Id.createVehicleId("split-v"),forward.getId(),type).setEarliestStart(0).setLatestEnd(10000).build();var registry=new CarrierVehicleTypes();registry.getVehicleTypes().put(type.getId(),type);
  var split=SplitRoadNetworkBuilder.build(n,prepared);var provider=NativeModalTransportCosts.create(split.network(),registry,"car");var roads=StaticSectionRoadCosts.prepare(split,List.of(v),provider,0.1);
  var f100=split.jobApproaches().get("j100").getFirst().haltLinkId();var f180=split.jobApproaches().get("j180").getFirst().haltLinkId();var r100=split.jobApproaches().get("j100").get(1).haltLinkId();
  assertThat(roads.leg(f100,f180,type).meters()).isCloseTo(80,within(1e-8));assertThat(roads.leg(f100,f180,type).seconds()).isCloseTo(8,within(1e-8));
  assertThat(roads.leg(f100,r100,type).meters()).isCloseTo(9800,within(1e-8));assertThat(roads.leg(f100,r100,type).uTurns()).isEqualTo(1);
  assertThat(roads.resolveOriginalLink(forward.getId())).isEqualTo(split.children().get(forward.getId()).getLast());
  var forbidden=new org.matsim.core.network.turnRestrictions.DisallowedNextLinks();forbidden.addDisallowedLinkSequence("car",List.of(reverse.getId()));NetworkUtils.setDisallowedNextLinks(forward,forbidden);
  var restricted=SplitRoadNetworkBuilder.build(n,prepared);var restrictedCosts=StaticSectionRoadCosts.prepare(restricted,List.of(v),NativeModalTransportCosts.create(restricted.network(),registry,"car"),0.1);
  assertThat(restrictedCosts.leg(restricted.jobApproaches().get("j100").getFirst().haltLinkId(),restricted.jobApproaches().get("j100").get(1).haltLinkId(),type).meters()).isEqualTo(Double.POSITIVE_INFINITY);
 }
 @Test void verifiedReverseDiffersFromParallelRoadAndAmbiguityFailsOnlyAffectedCell(){
  var n=NetworkUtils.createNetwork();for(String id:List.of("x","y","z","w"))n.addNode(n.getFactory().createNode(Id.createNodeId(id),new Coord(0,0)));
  link(n,"A","x","y",1);link(n,"B","y","x",1);link(n,"C","y","x",4);link(n,"U","y","x",0.5);link(n,"target","x","z",1);link(n,"safe","y","w",1);
  n.getLinks().get(Id.createLinkId("A")).getAttributes().putAttribute("osm:way:id","same");n.getLinks().get(Id.createLinkId("C")).getAttributes().putAttribute("osm:way:id","different");
  var type=VehicleUtils.createVehicleType(Id.create("parallel",VehicleType.class),"car");type.setMaximumVelocity(1);type.getCapacity().setOther(10);type.getCostInformation().setCostsPerMeter(1.0).setCostsPerSecond(0.0);
  var v=CarrierVehicle.Builder.newInstance(Id.createVehicleId("parallel"),Id.createLinkId("A"),type).setEarliestStart(0).setLatestEnd(100).build();var registry=new CarrierVehicleTypes();registry.getVehicleTypes().put(type.getId(),type);
  var children=new LinkedHashMap<Id<Link>,List<Id<Link>>>();n.getLinks().keySet().forEach(id->children.put(id,List.of(id)));
  var approaches=Map.of("pair",List.of(new HaltApproach(Id.createLinkId("A"),1,Id.createLinkId("A")),new HaltApproach(Id.createLinkId("B"),0,Id.createLinkId("B"))),"u",List.of(new HaltApproach(Id.createLinkId("U"),1,Id.createLinkId("U"))),"c",List.of(new HaltApproach(Id.createLinkId("C"),1,Id.createLinkId("C"))),"t",List.of(new HaltApproach(Id.createLinkId("target"),1,Id.createLinkId("target"))),"s",List.of(new HaltApproach(Id.createLinkId("safe"),1,Id.createLinkId("safe"))));
  var roads=StaticSectionRoadCosts.prepare(new SplitRoadNetwork(n,children,approaches,"parallel"),List.of(v),NativeModalTransportCosts.create(n,registry,"car"),5);
  var A=Id.<Link>createLinkId("A");var B=Id.<Link>createLinkId("B");var C=Id.<Link>createLinkId("C");var U=Id.<Link>createLinkId("U");var target=Id.<Link>createLinkId("target");var safe=Id.<Link>createLinkId("safe");
  assertThat(roads.classifyTransition(A,B)).isEqualTo(SectionRoadCosts.TurnClassification.TURN);assertThat(roads.classifyTransition(A,C)).isEqualTo(SectionRoadCosts.TurnClassification.NOT_TURN);assertThat(roads.classifyTransition(A,U)).isEqualTo(SectionRoadCosts.TurnClassification.UNKNOWN);
  assertThat(roads.leg(A,safe,type).meters()).isEqualTo(1);assertThat(roads.unknownTransitionCount()).isPositive();
  assertThat(roads.leg(A,C,type)).isEqualTo(new SectionLeg(4,4,0));
  assertThatThrownBy(()->roads.leg(A,target,type)).isInstanceOf(StaticSectionRoadCosts.AmbiguousRoadTurnException.class);
  assertThatThrownBy(()->roads.materialize(A,target,0,VehicleUtils.createVehicle(v.getId(),type))).isInstanceOf(StaticSectionRoadCosts.AmbiguousRoadTurnException.class);
  n.getLinks().get(U).setLength(4);var tied=StaticSectionRoadCosts.prepare(new SplitRoadNetwork(n,children,approaches,"tie"),List.of(v),NativeModalTransportCosts.create(n,registry,"car"),5);
  var evidence=new PreparedHaltCatalog.TurnEvidence("A","U","NOT_TURN","a".repeat(64),"x","y",1,4);
  var backed=StaticSectionRoadCosts.prepare(new SplitRoadNetwork(n,children,approaches,"evidence",List.of(evidence)),List.of(v),NativeModalTransportCosts.create(n,registry,"car"),5);
  assertThat(backed.classifyTransition(A,U)).isEqualTo(SectionRoadCosts.TurnClassification.NOT_TURN);
  assertThat(backed.classifyTransition(U,A)).isEqualTo(SectionRoadCosts.TurnClassification.UNKNOWN);
  assertThat(backed.leg(A,U,type).uTurns()).isZero();
  var forged=new PreparedHaltCatalog.TurnEvidence("A","U","NOT_TURN","a".repeat(64),"x","y",1,99);
  assertThatThrownBy(()->StaticSectionRoadCosts.prepare(new SplitRoadNetwork(n,children,approaches,"bad",List.of(forged)),List.of(v),NativeModalTransportCosts.create(n,registry,"car"),5)).hasMessageContaining("disagrees with network");  assertThat(tied.leg(A,target,type).meters()).isEqualTo(5);var materialized=tied.materialize(A,target,0,VehicleUtils.createVehicle(v.getId(),type));
  assertThat(materialized.distanceMeters()).isEqualTo(5);assertThat(materialized.route().getLinkIds()).containsExactly(C);
 }
 @Test void sharesTopologyAcrossScopedCarriersAndKeepsTheirRowsSeparate(){
  var n=NetworkUtils.createNetwork();for(String id:List.of("a","b","c"))n.addNode(n.getFactory().createNode(Id.createNodeId(id),new Coord(0,0)));
  link(n,"f","a","b",1);link(n,"r","b","a",1);link(n,"other","b","c",2);
  var type=VehicleUtils.createVehicleType(Id.create("shared",VehicleType.class),"car");type.setMaximumVelocity(1);type.getCapacity().setOther(10);type.getCostInformation().setCostsPerMeter(1.0).setCostsPerSecond(0.0);
  var v=CarrierVehicle.Builder.newInstance(Id.createVehicleId("shared"),Id.createLinkId("f"),type).setEarliestStart(0).setLatestEnd(100).build();
  var children=new LinkedHashMap<Id<Link>,List<Id<Link>>>();n.getLinks().keySet().forEach(id->children.put(id,List.of(id)));
  var f=Id.<Link>createLinkId("f");var r=Id.<Link>createLinkId("r");var other=Id.<Link>createLinkId("other");
  var first=new SplitRoadNetwork(n,children,Map.of("one",List.of(new HaltApproach(f,1,f))),"one");
  var second=new SplitRoadNetwork(n,children,Map.of("two",List.of(new HaltApproach(f,1,f),new HaltApproach(r,1,r)),"three",List.of(new HaltApproach(other,1,other))),"two");
  var topology=StaticSectionRoadCosts.prepareTopology(List.of(first,second));
  var a=StaticSectionRoadCosts.prepareFreeFlow(topology,first,List.of(v),0.1);var b=StaticSectionRoadCosts.prepareFreeFlow(topology,second,List.of(v),1);
  assertThat(a.classifyTransition(f,r)).isEqualTo(SectionRoadCosts.TurnClassification.TURN);
  assertThat(b.leg(f,r,type)).isEqualTo(new SectionLeg(1,1,1));assertThat(b.cachedRowCount()).isEqualTo(1);assertThat(a.cachedRowCount()).isZero();
  assertThatThrownBy(()->a.leg(f,other,type)).hasMessageContaining("Not a prepared halt");
  var foreign=new SplitRoadNetwork(n,children,Map.of(),"foreign");assertThatThrownBy(()->StaticSectionRoadCosts.prepareFreeFlow(topology,foreign,List.of(v),0)).hasMessageContaining("not included");
 }

 @Test void concurrentColdRowsPublishOnceAndWarmGuardsDoNotChangeCounts() throws Exception {
  var n=NetworkUtils.createNetwork();for(String id:List.of("a","b","c","d"))n.addNode(n.getFactory().createNode(Id.createNodeId(id),new Coord(0,0)));
  link(n,"one","a","b",1);link(n,"two","b","c",2);link(n,"three","c","d",3);
  var t=VehicleUtils.createVehicleType(Id.create("t",VehicleType.class),"car");t.setMaximumVelocity(1);t.getCostInformation().setCostsPerMeter(1.0).setCostsPerSecond(0.0);
  var t2=VehicleUtils.createVehicleType(Id.create("t2",VehicleType.class),"car");t2.setMaximumVelocity(1);t2.getCostInformation().setCostsPerMeter(1.0).setCostsPerSecond(0.0);
  var one=Id.<Link>createLinkId("one");var two=Id.<Link>createLinkId("two");var three=Id.<Link>createLinkId("three");
  var v=CarrierVehicle.Builder.newInstance(Id.createVehicleId("v"),one,t).setEarliestStart(0).setLatestEnd(100).build();
  var v2=CarrierVehicle.Builder.newInstance(Id.createVehicleId("v2"),two,t2).setEarliestStart(0).setLatestEnd(100).build();
  var children=new LinkedHashMap<Id<Link>,List<Id<Link>>>();n.getLinks().keySet().forEach(id->children.put(id,List.of(id)));var alias=Id.<Link>createLinkId("original-one");children.put(alias,List.of(one));
  var split=new SplitRoadNetwork(n,children,Map.of("two",List.of(new HaltApproach(two,1,two)),"three",List.of(new HaltApproach(three,1,three))),"cache-test");
  var topology=StaticSectionRoadCosts.prepareTopology(List.of(split));
  var serial=StaticSectionRoadCosts.prepareFreeFlow(topology,split,List.of(v,v2),0);var concurrent=StaticSectionRoadCosts.prepareFreeFlow(topology,split,List.of(v,v2),0);
  assertThat(concurrent.cachedRowCount()).isZero();var expected=serial.leg(one,three,t);long settled=serial.settledLinkCount();
  int workers=12;var pool=java.util.concurrent.Executors.newFixedThreadPool(workers);var barrier=new java.util.concurrent.CyclicBarrier(workers);
  try {var futures=new ArrayList<java.util.concurrent.Future<SectionLeg>>();for(int i=0;i<workers;i++)futures.add(pool.submit(()->{barrier.await(10,java.util.concurrent.TimeUnit.SECONDS);return concurrent.leg(alias,three,t);}));
   for(var future:futures)assertThat(future.get(20,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(expected);
  } finally {pool.shutdownNow();assertThat(pool.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();}
  assertThat(concurrent.cachedRowCount()).isEqualTo(1);assertThat(concurrent.settledLinkCount()).isEqualTo(settled);
  assertThat(concurrent.leg(one,two,t)).isEqualTo(serial.leg(one,two,t));assertThat(concurrent.settledLinkCount()).isEqualTo(settled);
  var detached=VehicleUtils.createVehicleType(t.getId(),"car");detached.setMaximumVelocity(1);detached.getCostInformation().setCostsPerMeter(1.0).setCostsPerSecond(0.0);
  assertThat(concurrent.leg(one,three,detached)).isEqualTo(expected);assertThat(concurrent.cachedRowCount()).isEqualTo(1);
  detached.setMaximumVelocity(2);assertThatThrownBy(()->concurrent.leg(one,three,detached)).isInstanceOf(IllegalArgumentException.class);
  var unknown=VehicleUtils.createVehicleType(Id.create("unknown",VehicleType.class),"car");assertThatThrownBy(()->concurrent.leg(one,three,unknown)).isInstanceOf(IllegalArgumentException.class);
  var missing=Id.<Link>createLinkId("missing");assertThatThrownBy(()->concurrent.leg(one,missing,t)).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->concurrent.leg(missing,three,t)).isInstanceOf(IllegalArgumentException.class);
  assertThat(concurrent.cachedRowCount()).isEqualTo(1);assertThat(concurrent.settledLinkCount()).isEqualTo(settled);
  var separate=StaticSectionRoadCosts.prepareFreeFlow(topology,split,List.of(v,v2),0);var pair=java.util.concurrent.Executors.newFixedThreadPool(2);var pairBarrier=new java.util.concurrent.CyclicBarrier(2);
  try {var left=pair.submit(()->{pairBarrier.await(10,java.util.concurrent.TimeUnit.SECONDS);return separate.leg(two,three,t);});var right=pair.submit(()->{pairBarrier.await(10,java.util.concurrent.TimeUnit.SECONDS);return separate.leg(one,three,t2);});
   assertThat(left.get(20,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(serial.leg(two,three,t));assertThat(right.get(20,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(serial.leg(one,three,t2));
  } finally {pair.shutdownNow();assertThat(pair.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();}
  assertThat(separate.cachedRowCount()).isEqualTo(2);
  var retry=StaticSectionRoadCosts.prepareFreeFlow(topology,split,List.of(v,v2),0);var secondLink=n.getLinks().get(two);secondLink.setFreespeed(0);
  assertThatThrownBy(()->retry.leg(one,three,t)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Invalid road speed");assertThat(retry.cachedRowCount()).isZero();
  secondLink.setFreespeed(1);assertThat(retry.leg(one,three,t)).isEqualTo(expected);assertThat(retry.cachedRowCount()).isEqualTo(1);long afterRetry=retry.settledLinkCount();
  assertThat(retry.leg(one,three,t)).isEqualTo(expected);assertThat(retry.settledLinkCount()).isEqualTo(afterRetry);
 }
}

