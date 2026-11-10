package hagrid.core.routing.sections;

import hagrid.core.routing.*;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.*;
import org.matsim.api.core.v01.network.*;
import org.matsim.core.network.NetworkUtils;
import org.matsim.freight.carriers.*;
import org.matsim.vehicles.*;
import java.util.*;
import java.nio.file.*;
import hagrid.core.HagridConfig;
import hagrid.core.config.*;
import hagrid.core.simulation.scoring.*;
import static org.assertj.core.api.Assertions.*;

class SectionTurnPolicyTest {
 @Test void equalCostKnownPrefixDoesNotHideFasterRouteBeforeLaterUnknown() {
  var n=NetworkUtils.createNetwork();for(String id:List.of("o","s","u","q","r"))NetworkUtils.createAndAddNode(n,Id.createNodeId(id),new Coord(0,0));
  addLink(n,"origin","o","s",1,1);addLink(n,"slow1","s","u",2,.1);addLink(n,"slow2","u","q",1,1);
  addLink(n,"back","s","o",1,1);addLink(n,"fast","o","q",1,1);addLink(n,"common","q","r",1,1);addLink(n,"target","r","q",1,1);addLink(n,"return","q","o",1,1);
  var type=VehicleUtils.createVehicleType(Id.create("tie",VehicleType.class),"car");type.setMaximumVelocity(1);type.getCapacity().setOther(10);type.getCostInformation().setFixedCost(0.0).setCostsPerMeter(1.0).setCostsPerSecond(0.0);
  var origin=Id.<Link>createLinkId("origin");var target=Id.<Link>createLinkId("target");var vehicle=CarrierVehicle.Builder.newInstance(Id.createVehicleId("tie"),origin,type).setEarliestStart(0).setLatestEnd(100).build();
  var children=new LinkedHashMap<Id<Link>,List<Id<Link>>>();n.getLinks().keySet().forEach(id->children.put(id,List.of(id)));
  var split=new SplitRoadNetwork(n,children,Map.of("j",List.of(new HaltApproach(target,1,target))),"tie-prefix");
  var roads=StaticSectionRoadCosts.prepareFreeFlow(StaticSectionRoadCosts.prepareTopology(List.of(split)),split,List.of(vehicle),1,SectionRoadCosts.TurnPolicy.NETWORK_REVERSAL);
  assertThat(roads.leg(origin,target,type)).isEqualTo(new SectionLeg(4,4,0,2));
  var materialized=roads.materialize(origin,target,0,VehicleUtils.createVehicle(vehicle.getId(),type));assertThat(materialized.monetaryCost()).isEqualTo(6);assertThat(materialized.travelTimeSeconds()).isEqualTo(4);
  assertThat(materialized.route().getLinkIds()).containsExactly(Id.createLinkId("back"),Id.createLinkId("fast"),Id.createLinkId("common"));
  var carrier=CarriersUtils.createCarrier(Id.create("tie",Carrier.class));CarriersUtils.setCarrierMode(carrier,"car");CarriersUtils.addCarrierVehicle(carrier,vehicle);CarriersUtils.addService(carrier,CarrierService.Builder.newInstance(Id.create("j",CarrierService.class),target,1).setServiceDuration(0).setServiceStartingTimeWindow(TimeWindow.newInstance(0,100)).build());
  var catalog=new SectionCatalog(carrier,List.of(new DeliverySection("s",List.of("j"),1,List.of(new SectionVariant(0,List.of("j"),List.of(target))))),"tie-catalog");
  var evaluation=new SectionRouteEvaluator(catalog,roads,10,10,1).evaluate(List.of("s"),vehicle,0);
  assertThat(evaluation.feasible()).isTrue();assertThat(evaluation.endTime()).isEqualTo(6);assertThat(evaluation.variableCost()).isEqualTo(8);
 }
 private static void addLink(Network n,String id,String from,String to,double length,double speed){var link=NetworkUtils.createAndAddLink(n,Id.createLinkId(id),n.getNodes().get(Id.createNodeId(from)),n.getNodes().get(Id.createNodeId(to)),length,speed,1000,1);link.setAllowedModes(Set.of("car"));}
 @Test void unresolvedReverseRemainsTraversableAndPaysConservativePenalty() throws Exception {
  var f=new Fixture();
  var strict=StaticSectionRoadCosts.prepareFreeFlow(f.topology,f.split,List.of(f.vehicle),7);
  assertThatThrownBy(()->strict.leg(f.a,f.b,f.type)).isInstanceOf(StaticSectionRoadCosts.AmbiguousRoadTurnException.class);
  var robust=robust(f,7);
  assertThat(robust.classifyTransition(f.a,f.b)).isEqualTo(SectionRoadCosts.TurnClassification.UNKNOWN);
  var scalar=robust.leg(f.a,f.b,f.type);
  assertThat(scalar.meters()).isEqualTo(1);assertThat(scalar.uTurns()).isZero();
  assertThat(scalar.uncertainReversals()).isEqualTo(1);assertThat(scalar.chargedTurns()).isEqualTo(1);
  assertThat(robust.materialize(f.a,f.b,0,VehicleUtils.createVehicle(f.vehicle.getId(),f.type)).monetaryCost()).isEqualTo(8);
  assertThat(robust.fingerprint()).isNotEqualTo(strict.fingerprint());
  var free=robust(f,0);
  assertThat(free.materialize(f.a,f.b,0,VehicleUtils.createVehicle(f.vehicle.getId(),f.type)).monetaryCost()).isEqualTo(1);
  assertThat(free.leg(f.a,f.b,f.type).uTurns()).isZero();
 }
 @Test void conservativeTariffChangesPathWithoutBanningUncertainRoads() {
  var f=new Fixture(true);var vehicle=VehicleUtils.createVehicle(f.vehicle.getId(),f.type);
  var charged=robust(f,7);var free=robust(f,0);
  assertThat(charged.leg(f.a,f.b,f.type)).isEqualTo(new SectionLeg(4,4,0,0));
  assertThat(charged.materialize(f.a,f.b,0,vehicle).route().getLinkIds()).containsExactly(Id.createLinkId("d1"),Id.createLinkId("d2"),Id.createLinkId("d3"));
  assertThat(free.leg(f.a,f.b,f.type)).isEqualTo(new SectionLeg(1,1,0,1));
  assertThat(free.materialize(f.a,f.b,0,vehicle).monetaryCost()).isEqualTo(1);
  var restrictions=new org.matsim.core.network.turnRestrictions.DisallowedNextLinks();restrictions.addDisallowedLinkSequence("car",List.of(f.b));NetworkUtils.setDisallowedNextLinks(f.split.network().getLinks().get(f.a),restrictions);
  var restricted=StaticSectionRoadCosts.prepareFreeFlow(StaticSectionRoadCosts.prepareTopology(List.of(f.split)),f.split,List.of(f.vehicle),0,SectionRoadCosts.TurnPolicy.NETWORK_REVERSAL);
  assertThat(restricted.leg(f.a,f.b,f.type)).isEqualTo(new SectionLeg(4,4,0,0));
 }
 @Test void provenDifferentRoadDoesNotReceiveUncertaintyTariff() {
  var f=new Fixture();var proof=new PreparedHaltCatalog.TurnEvidence("forward","back","NOT_TURN","a".repeat(64),"x","y",1,1);
  var split=new SplitRoadNetwork(f.split.network(),f.split.children(),f.split.jobApproaches(),"different-road",List.of(proof));
  var roads=StaticSectionRoadCosts.prepareFreeFlow(StaticSectionRoadCosts.prepareTopology(List.of(split)),split,List.of(f.vehicle),7,SectionRoadCosts.TurnPolicy.NETWORK_REVERSAL);
  assertThat(roads.classifyTransition(f.a,f.b)).isEqualTo(SectionRoadCosts.TurnClassification.NOT_TURN);
  assertThat(roads.leg(f.a,f.b,f.type)).isEqualTo(new SectionLeg(1,1,0,0));
  assertThat(roads.materialize(f.a,f.b,0,VehicleUtils.createVehicle(f.vehicle.getId(),f.type)).monetaryCost()).isEqualTo(1);
 }
 @Test void searchReplayCompanionReloadAndObservedScoreKeepUnknownSeparate() throws Exception {
  var f=new Fixture();var roads=robust(f,7);var n=f.split.network();
  var carrier=CarriersUtils.createCarrier(Id.create("c",Carrier.class));CarriersUtils.setCarrierMode(carrier,"car");
  carrier.setCarrierCapabilities(CarrierCapabilities.Builder.newInstance().addVehicle(f.vehicle).setFleetSize(CarrierCapabilities.FleetSize.INFINITE).build());
  var job=CarrierService.Builder.newInstance(Id.create("j",CarrierService.class),f.b,1).setServiceDuration(0).setServiceStartingTimeWindow(TimeWindow.newInstance(0,100)).build();CarriersUtils.addService(carrier,job);
  var catalog=new SectionCatalog(carrier,List.of(new DeliverySection("s",List.of("j"),1,List.of(new SectionVariant(0,List.of("j"),List.of(f.b))))),"fixture");
  var search=new CarrierOptimizationService().searchSections(catalog,n,roads,100,100,7,4711,new SectionSearchPolicy(2,.25,.4));
  assertThat(search.solution().getCost()).isEqualTo(16);
  var expanded=SectionPlanExpander.expand(search,catalog,roads);var output=expanded.carrier();output.addPlan(expanded.plan());output.setSelectedPlan(expanded.plan());
  var types=new CarrierVehicleTypes();types.getVehicleTypes().put(f.type.getId(),f.type);var nativeCosts=NativeModalTransportCosts.create(n,types,"car");
  var replay=PlanValidator.validateSections(output,expanded.plan(),nativeCosts,null,100,7,roads);
  assertThat(replay.violations()).isEmpty();assertThat(replay.finalCost()).isEqualTo(16);
  var strict=StaticSectionRoadCosts.prepareFreeFlow(f.topology,f.split,List.of(f.vehicle),7);
  assertThat(PlanValidator.validateSections(output,expanded.plan(),nativeCosts,null,100,7,strict).valid()).isFalse();
  var evidence=SectionBindingCompanion.capture(output,roads,n);
  assertThat(evidence.records()).hasSize(2).allSatisfy(record->assertThat(record.get("classification")).isEqualTo("UNKNOWN"));
  assertThat(evidence.isTurn(f.a,f.b)).isFalse();assertThat(evidence.chargesTransition(f.a,f.b)).isTrue();
  assertThatThrownBy(()->SectionBindingCompanion.capture(output,strict,n)).isInstanceOf(IllegalStateException.class);
  var doc=new LinkedHashMap<String,Object>();doc.put("catalogFingerprint","fixture");doc.put("executionNetworkFingerprint",SectionBindingCompanion.networkFingerprint(n));doc.put("executionFingerprint",SectionBindingCompanion.executionFingerprint(output));doc.put("turnPolicy",evidence.turnPolicy().name());doc.put("turnTransitions",evidence.records());doc.put("bindings",Map.of("j",f.b.toString()));doc.put("allowed",Map.of("j",List.of(f.b.toString())));doc.put("canonical",Map.of("services",Map.of("j",Map.of("demand",1,"duration",0,"windowStart",0,"windowEnd",100))));
  var json=new com.fasterxml.jackson.databind.ObjectMapper();var file=Files.createTempFile("section-policy-",".json");
  try {
   json.writeValue(file.toFile(),Map.of("c",SectionBindingCompanion.seal(doc)));
   var restored=SectionBindingCompanion.verifyAndRead(file,new Carriers(List.of(output)),n,true).get("c");
   assertThat(restored).isEqualTo(evidence);assertThat(restored.turnPolicy()).isEqualTo(SectionRoadCosts.TurnPolicy.NETWORK_REVERSAL);
   var scheduled=expanded.plan().getScheduledTours().iterator().next();var tour=scheduled.getTour().getId();var vehicle=scheduled.getVehicle().getId();double start=scheduled.getDeparture();
   var execution=new CarrierExecutionLedger.CarrierExecution(List.of(new CarrierExecutionLedger.ExecutedTour(vehicle,tour,start,start+2)),List.of(new CarrierExecutionLedger.ExecutedLeg(vehicle,tour,start,start+1,List.of(f.b),f.a,f.b),new CarrierExecutionLedger.ExecutedLeg(vehicle,tour,start+1,start+2,List.of(f.a),f.b,f.a)),List.of(new CarrierExecutionLedger.ExecutedActivity(vehicle,tour,"service:j",start+1,start+1)),true,List.of());
   var config=HagridConfigResolver.resolve(new HagridConfig(),HagridConfigResolver.Overrides.empty(),HagridConfigResolver.Overrides.of(Map.of("tourRules.uTurnPenaltyCost","7")),Map.of());
   var score=CarrierCostCalculator.calculate(output,execution,config,n,restored);
   assertThat(score.score()).isEqualTo(-16);assertThat(score.costs().uTurnCount()).isZero();assertThat(score.costs().uncertainReversalCount()).isEqualTo(2);assertThat(score.costs().chargedReversalCount()).isEqualTo(2);
   CarrierCostAttributeWriter.write(output,score.costs());assertThat(output.getAttributes().getAttribute("uTurnCount")).isEqualTo(0);assertThat(output.getAttributes().getAttribute("uncertainReversalCount")).isEqualTo(2);
   var freeConfig=HagridConfigResolver.resolve(new HagridConfig(),HagridConfigResolver.Overrides.empty(),HagridConfigResolver.Overrides.of(Map.of("tourRules.uTurnPenaltyCost","0")),Map.of());
   assertThat(CarrierCostCalculator.calculate(output,execution,freeConfig,n,restored).score()).isEqualTo(-2);
   doc.remove("turnPolicy");json.writeValue(file.toFile(),Map.of("c",SectionBindingCompanion.seal(doc)));
   assertThatThrownBy(()->SectionBindingCompanion.verifyAndRead(file,new Carriers(List.of(output)),n,true)).isInstanceOf(IllegalStateException.class);
   doc.put("turnPolicy","invented");json.writeValue(file.toFile(),Map.of("c",SectionBindingCompanion.seal(doc)));
   assertThatThrownBy(()->SectionBindingCompanion.verifyAndRead(file,new Carriers(List.of(output)),n,true)).isInstanceOf(IllegalStateException.class);
   // An older companion with certified transitions and no policy remains valid and strict.
   n.getLinks().values().forEach(link->link.getAttributes().putAttribute("osm:way:id","same-road"));
   var certified=StaticSectionRoadCosts.prepareFreeFlow(StaticSectionRoadCosts.prepareTopology(List.of(f.split)),f.split,List.of(f.vehicle),7);
   var oldEvidence=SectionBindingCompanion.capture(output,certified,n);doc.remove("turnPolicy");doc.put("turnTransitions",oldEvidence.records());doc.put("executionNetworkFingerprint",SectionBindingCompanion.networkFingerprint(n));
   json.writeValue(file.toFile(),Map.of("c",SectionBindingCompanion.seal(doc)));
   var oldRestored=SectionBindingCompanion.verifyAndRead(file,new Carriers(List.of(output)),n,true).get("c");
   assertThat(oldRestored.turnPolicy()).isEqualTo(SectionRoadCosts.TurnPolicy.STRICT);
   var knownScore=CarrierCostCalculator.calculate(output,execution,config,n,oldRestored);
   assertThat(knownScore.score()).isEqualTo(-16);assertThat(knownScore.costs().uTurnCount()).isEqualTo(2);assertThat(knownScore.costs().uncertainReversalCount()).isZero();
  } finally {Files.deleteIfExists(file);}
 }
 private static StaticSectionRoadCosts robust(Fixture f,double penalty) {
  return StaticSectionRoadCosts.prepareFreeFlow(f.topology,f.split,List.of(f.vehicle),penalty,SectionRoadCosts.TurnPolicy.NETWORK_REVERSAL);
 }
 private static final class Fixture {
  final Id<Link> a=Id.createLinkId("forward"),b=Id.createLinkId("back");
  final VehicleType type=VehicleUtils.createVehicleType(Id.create("van",VehicleType.class),"car");
  final CarrierVehicle vehicle;final SplitRoadNetwork split;final StaticSectionRoadCosts.Topology topology;
  Fixture(){this(false);}
  Fixture(boolean detour){
   var n=NetworkUtils.createNetwork();var x=NetworkUtils.createAndAddNode(n,Id.createNodeId("x"),new Coord(0,0));var y=NetworkUtils.createAndAddNode(n,Id.createNodeId("y"),new Coord(1,0));
   for(var l:List.of(NetworkUtils.createAndAddLink(n,a,x,y,1,1,1000,1),NetworkUtils.createAndAddLink(n,b,y,x,1,1,1000,1)))l.setAllowedModes(Set.of("car"));
   if(detour){var z=NetworkUtils.createAndAddNode(n,Id.createNodeId("z"),new Coord(2,0));var w=NetworkUtils.createAndAddNode(n,Id.createNodeId("w"),new Coord(2,1));for(var l:List.of(NetworkUtils.createAndAddLink(n,Id.createLinkId("d1"),y,z,1,1,1000,1),NetworkUtils.createAndAddLink(n,Id.createLinkId("d2"),z,w,1,1,1000,1),NetworkUtils.createAndAddLink(n,Id.createLinkId("d3"),w,y,1,1,1000,1)))l.setAllowedModes(Set.of("car"));}
   type.setMaximumVelocity(1);type.getCapacity().setOther(10);type.getCostInformation().setFixedCost(0.0).setCostsPerMeter(1.0).setCostsPerSecond(0.0);
   vehicle=CarrierVehicle.Builder.newInstance(Id.createVehicleId("v"),a,type).setEarliestStart(0).setLatestEnd(100).build();
   var children=new LinkedHashMap<Id<Link>,List<Id<Link>>>();n.getLinks().keySet().forEach(id->children.put(id,List.of(id)));
   split=new SplitRoadNetwork(n,children,Map.of("j",List.of(new HaltApproach(b,1,b))),"uncertain");topology=StaticSectionRoadCosts.prepareTopology(List.of(split));
  }
 }
}
