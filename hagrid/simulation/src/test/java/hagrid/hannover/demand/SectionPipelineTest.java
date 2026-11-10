package hagrid.hannover.demand;
import hagrid.core.*;
import hagrid.core.routing.ThreadingType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.*;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.freight.carriers.*;
import org.matsim.vehicles.*;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class SectionPipelineTest {
 @TempDir Path dir;
 @Test void normalCarrierRouterUsesFastWhenOptedIn() throws Exception { runPipeline(true); }
 @Test void normalCarrierRouterUsesOrdinaryInsertionByDefault() throws Exception { runPipeline(false); }
 private void runPipeline(boolean fast) throws Exception {
  var scenario=ScenarioUtils.createScenario(ConfigUtils.createConfig());var net=scenario.getNetwork();
  var a=NetworkUtils.createAndAddNode(net,Id.createNodeId("a"),new Coord(0,0));var b=NetworkUtils.createAndAddNode(net,Id.createNodeId("b"),new Coord(100,0));var c=NetworkUtils.createAndAddNode(net,Id.createNodeId("c"),new Coord(200,0));
  for(var link:List.of(NetworkUtils.createAndAddLink(net,Id.createLinkId("home"),a,b,100,10,1000,1),NetworkUtils.createAndAddLink(net,Id.createLinkId("stop"),b,c,100,10,1000,1),NetworkUtils.createAndAddLink(net,Id.createLinkId("back"),c,a,100,10,1000,1)))link.setAllowedModes(Set.of("car"));
  var type=VehicleUtils.createVehicleType(Id.create("van",VehicleType.class),"car");type.setMaximumVelocity(10);type.getCapacity().setOther(10);type.getCostInformation().setFixedCost(1.0).setCostsPerMeter(.01).setCostsPerSecond(.01);
  var types=new CarrierVehicleTypes();types.getVehicleTypes().put(type.getId(),type);
  var selected=carrier("one",type,"j1","j2");var oldVehicle=selected.getCarrierCapabilities().getCarrierVehicles().values().iterator().next();var preciseVehicle=CarrierVehicle.Builder.newInstance(oldVehicle.getId(),oldVehicle.getLinkId(),type).setEarliestStart(97.5).setLatestEnd(1000.75).build();selected.setCarrierCapabilities(CarrierCapabilities.Builder.newInstance().addVehicle(preciseVehicle).setFleetSize(CarrierCapabilities.FleetSize.INFINITE).build());for(var original:new ArrayList<>(selected.getServices().values())){var precise=CarrierService.Builder.newInstance(original.getId(),original.getServiceLinkId(),original.getCapacityDemand()).setServiceDuration(10.25).setServiceStartingTimeWindow(TimeWindow.newInstance(100.0,900.75)).build();selected.getServices().put(precise.getId(),precise);}var hub=new hagrid.core.util.Hub(Id.create("h",hagrid.core.util.Hub.class),"carrier",new Coord(0,0));hub.setAddress("actual generation metadata");hub.setLink(Id.createLinkId("stop"));hub.getAttributes().putAttribute("nested",new ArrayList<>(List.of("original")));selected.getAttributes().putAttribute("hub",hub);var other=carrier("other",type,"j1");var deliveries=new Carriers(List.of(selected,other));
  scenario.addScenarioElement("carriers",deliveries);scenario.addScenarioElement("supply",new Carriers());scenario.addScenarioElement("carrierVehicleTypes",types);
  var catalog=dir.resolve("halts.json");Files.writeString(catalog,"""
   {"schemaVersion":1,"carrierId":"one","depot":{"linkId":"home"},"links":{"stop":{"fromNodeId":"b","toNodeId":"c","lengthMeters":100,"geometryLengthMeters":100,"orientedXY":[[100,0],[200,0]]}},"jobs":[{"jobId":"j1","rawLinkId":"stop","approaches":[{"linkId":"stop","fraction":0.25}]},{"jobId":"j2","rawLinkId":"stop","approaches":[{"linkId":"stop","fraction":0.75}]}]}
  """);
  var config=new HagridConfig();set(config,"hagridPaths",new HagridPaths(dir));config.setSimulationDate(LocalDate.of(2025,5,13));config.routing().setJspritIterations(1);
  var properties=dir.resolve("routing.properties");Files.writeString(properties,"routing.sectionMaxSpanMeters=100\nrouting.haltCatalogFile=halts.json\n"+(fast?"routing.sectionFastRegret=true\n":"")+"resources.internalSolverThreads=1\n");
  hagrid.core.config.HagridConfigFile.load(properties).applyTo(config);
  assertThat(hagrid.core.config.SectionSettings.from(config.getAdditionalConfigurationValues()).fastRegret()).isEqualTo(fast);
  // Production entry rejects unsupported internal parallelism before any output is published.
  if(fast) {
  var targets=List.of(Path.of(config.io().deliveryCarriersRouted()),Path.of(config.io().supplyCarriersRouted()),Path.of(config.io().routingMetrics()),Path.of(config.io().networkFiltered()),Path.of(config.io().deliveryCarriersRouted()+".section-originals.xml.gz"),Path.of(config.io().deliveryCarriersRouted()+".section-bindings.json"));
  Files.createDirectories(targets.getFirst().getParent());Files.writeString(targets.getFirst(),"sentinel");var priorNetwork=scenario.getNetwork();var priorServices=Map.copyOf(selected.getServices());var priorCarriers=Map.copyOf(deliveries.getCarriers());
  config.resources().setInternalSolverThreads(2);var rejected=new CarrierRouter();set(rejected,"scenario",scenario);set(rejected,"hagridConfig",config);rejected.setThreadingType(ThreadingType.SINGLE_THREAD);
  assertThatThrownBy(rejected::run).hasRootCauseInstanceOf(IllegalArgumentException.class).hasStackTraceContaining("internalSolverThreads");
  assertUnpublished(targets,scenario,priorNetwork,selected,priorServices,deliveries,priorCarriers);config.resources().setInternalSolverThreads(1);
  var infinite=selected.getCarrierCapabilities();var finite=CarrierCapabilities.Builder.newInstance().addVehicle(preciseVehicle).setFleetSize(CarrierCapabilities.FleetSize.FINITE).build();selected.setCarrierCapabilities(finite);
  assertThatThrownBy(rejected::run).hasRootCauseInstanceOf(IllegalArgumentException.class).hasStackTraceContaining("INFINITE");
  assertUnpublished(targets,scenario,priorNetwork,selected,priorServices,deliveries,priorCarriers);selected.setCarrierCapabilities(infinite);Files.delete(targets.getFirst());
  }
  var router=new CarrierRouter();set(router,"scenario",scenario);set(router,"hagridConfig",config);router.setThreadingType(ThreadingType.SINGLE_THREAD);
  var sentinel=Map.of("sentinel","unchanged");scenario.addScenarioElement(hagrid.core.routing.SectionBindingCompanion.CONTEXT,sentinel);
  var blocked=Path.of(config.io().deliveryCarriersRouted()+".section-bindings.json");Files.createDirectories(blocked);
  assertThatThrownBy(router::run).hasStackTraceContaining("Output target must be a regular file");
  assertThat(scenario.getScenarioElement(hagrid.core.routing.SectionBindingCompanion.CONTEXT)).isSameAs(sentinel);assertThat(scenario.getNetwork()).isSameAs(net);Files.delete(blocked);
  try(var log=new SimpleInsertionLogCapture()) {
   router.run();
   if(fast)assertThat(log.output()).contains("initialise [name=regretInsertionFast]");
   else assertThat(log.output()).contains("initialise [name=regretInsertion]").doesNotContain("initialise [name=regretInsertionFast]");
  }
  assertThat(hagrid.core.routing.SectionBindingCompanion.context(scenario)).containsOnlyKeys("one");
  assertThatThrownBy(()->hagrid.core.routing.SectionBindingCompanion.context(scenario).clear()).isInstanceOf(UnsupportedOperationException.class);
  var output=deliveries.getCarriers().get(Id.create("one",Carrier.class));var remaining=deliveries.getCarriers().get(Id.create("other",Carrier.class));
  assertThat(output.getServices()).hasSize(2);assertThat(output.getServices().values()).extracting(CarrierService::getServiceDuration).containsOnly(10.25);
  assertThat(((hagrid.core.util.Hub)output.getAttributes().getAttribute("hub")).getLink()).isEqualTo(Id.createLinkId("section-link:stop:3"));assertThat(hub.getLink()).isEqualTo(Id.createLinkId("stop"));
  assertThat(output.getSelectedPlan()).isNotNull();assertThat(remaining.getSelectedPlan()).isNotNull();
  assertThat(scenario.getNetwork().getLinks()).doesNotContainKey(Id.createLinkId("stop"));
  assertThat(remaining.getServices().values().iterator().next().getServiceLinkId()).isEqualTo(Id.createLinkId("section-link:stop:3"));
  assertThat(Path.of(config.io().networkFiltered())).exists();assertThat(Path.of(config.io().deliveryCarriersRouted())).exists();
  var restoredNetwork=NetworkUtils.createNetwork();new org.matsim.core.network.io.MatsimNetworkReader(restoredNetwork).readFile(config.io().networkFiltered());
  var restored=new Carriers();new CarrierPlanXmlReader(restored,types).readFile(config.io().deliveryCarriersRouted());
  var restoredSelected=restored.getCarriers().get(Id.create("one",Carrier.class));assertThat(restoredSelected.getServices()).hasSize(2);
  assertThat(output.getSelectedPlan().getScheduledTours().iterator().next().getDeparture()).isEqualTo(97.5);
  assertThat(restoredSelected.getSelectedPlan().getScheduledTours().iterator().next().getDeparture()).isEqualTo(97.5);
  assertThat(restoredSelected.getServices().values()).allSatisfy(v->{assertThat(v.getServiceDuration()).isEqualTo(10.25);assertThat(v.getServiceStaringTimeWindow().getEnd()).isEqualTo(900.75);});
  assertThat(restoredSelected.getCarrierCapabilities().getCarrierVehicles().values()).singleElement().satisfies(v->{assertThat(v.getEarliestStartTime()).isEqualTo(97.5);assertThat(v.getLatestEndTime()).isEqualTo(1000.75);});
  for(var scheduled:restoredSelected.getSelectedPlan().getScheduledTours())for(var element:scheduled.getTour().getTourElements())if(element instanceof Tour.Leg leg){var route=(org.matsim.core.population.routes.NetworkRoute)leg.getRoute();assertThat(route.getVehicleId()).isNull();assertThat(restoredNetwork.getLinks()).containsKey(route.getStartLinkId()).containsKey(route.getEndLinkId());}
  var json=new com.fasterxml.jackson.databind.ObjectMapper().readTree(Path.of(config.io().deliveryCarriersRouted()+".section-bindings.json").toFile());
  assertThat(json.path("one").path("canonical").path("attributes").path("hub").path("address").asText()).isEqualTo("actual generation metadata");
  var split=hagrid.core.routing.sections.SplitRoadNetworkBuilder.build(net,hagrid.core.routing.sections.PreparedHaltCatalog.read(catalog));
  var originalCatalog=hagrid.core.routing.sections.SectionCatalogBuilder.build(selected,split,100,config.routing().getDemandBorder());
  var restoredCosts=hagrid.core.routing.NativeModalTransportCosts.create(restoredNetwork,types,"car");
  var roads=hagrid.core.routing.sections.StaticSectionRoadCosts.prepare(split,selected.getCarrierCapabilities().getCarrierVehicles().values(),hagrid.core.routing.NativeModalTransportCosts.create(split.network(),types,"car"),config.routing().getUTurnPenaltyCost());
  var bindings=new java.util.LinkedHashMap<String,Id<org.matsim.api.core.v01.network.Link>>();json.path("one").path("bindings").fields().forEachRemaining(e->bindings.put(e.getKey(),Id.createLinkId(e.getValue().asText())));
  assertThat(hagrid.core.routing.sections.SectionBindingValidator.validatePlan(originalCatalog,restoredSelected,restoredSelected.getSelectedPlan(),bindings)).isEmpty();
  assertThat(hagrid.core.routing.PlanValidator.validateSections(restoredSelected,restoredSelected.getSelectedPlan(),restoredCosts,null,config.routing().getSolverMaxRouteDurationSeconds(),config.routing().getUTurnPenaltyCost(),roads).violations()).isEmpty();
  var companion=Path.of(config.io().deliveryCarriersRouted()+".section-bindings.json");hagrid.core.routing.SectionBindingCompanion.verify(companion,restored,restoredNetwork,true);
  var changedLink=restoredNetwork.getLinks().get(Id.createLinkId("home"));double savedLength=changedLink.getLength();changedLink.setLength(savedLength+1);assertThatThrownBy(()->hagrid.core.routing.SectionBindingCompanion.verify(companion,restored,restoredNetwork,true)).isInstanceOf(IllegalStateException.class);changedLink.setLength(savedLength);
  var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
  var originalJson=Files.readString(companion);
  var tampered=mapper.readTree(originalJson);var record=(com.fasterxml.jackson.databind.node.ObjectNode)tampered.path("one");
  ((com.fasterxml.jackson.databind.node.ArrayNode)record.path("turnTransitions")).removeAll();
  var raw=mapper.convertValue(record,new com.fasterxml.jackson.core.type.TypeReference<java.util.LinkedHashMap<String,Object>>(){});raw.remove("digest");
  ((com.fasterxml.jackson.databind.node.ObjectNode)tampered).set("one",mapper.valueToTree(hagrid.core.routing.SectionBindingCompanion.seal(raw)));mapper.writeValue(companion.toFile(),tampered);
  assertThatThrownBy(()->hagrid.core.routing.SectionBindingCompanion.verifyAndRead(companion,restored,restoredNetwork,true)).isInstanceOf(IllegalStateException.class).hasMessageContaining("transition set");
  Files.writeString(companion,originalJson);
  tampered=mapper.readTree(originalJson);record=(com.fasterxml.jackson.databind.node.ObjectNode)tampered.path("one");
  var transitions=(com.fasterxml.jackson.databind.node.ArrayNode)record.path("turnTransitions");assertThat(transitions.isEmpty()).isFalse();
  ((com.fasterxml.jackson.databind.node.ObjectNode)transitions.get(0)).put("classification","UNKNOWN");
  raw=mapper.convertValue(record,new com.fasterxml.jackson.core.type.TypeReference<java.util.LinkedHashMap<String,Object>>(){});raw.remove("digest");
  ((com.fasterxml.jackson.databind.node.ObjectNode)tampered).set("one",mapper.valueToTree(hagrid.core.routing.SectionBindingCompanion.seal(raw)));mapper.writeValue(companion.toFile(),tampered);
  assertThatThrownBy(()->hagrid.core.routing.SectionBindingCompanion.verifyAndRead(companion,restored,restoredNetwork,true)).isInstanceOf(IllegalStateException.class);
  Files.writeString(companion,originalJson);
  for(String mutation:List.of("duplicate","conflict","malformed")) {
   var mutated=mapper.readTree(originalJson);var item=(com.fasterxml.jackson.databind.node.ObjectNode)mutated.path("one");var entries=(com.fasterxml.jackson.databind.node.ArrayNode)item.path("turnTransitions");
   var extra=entries.get(0).deepCopy();if(mutation.equals("conflict"))((com.fasterxml.jackson.databind.node.ObjectNode)extra).put("classification",extra.path("classification").asText().equals("TURN")?"NOT_TURN":"TURN");
   if(mutation.equals("malformed"))((com.fasterxml.jackson.databind.node.ObjectNode)extra).remove("to");entries.add(extra);
   var fields=mapper.convertValue(item,new com.fasterxml.jackson.core.type.TypeReference<java.util.LinkedHashMap<String,Object>>(){});fields.remove("digest");((com.fasterxml.jackson.databind.node.ObjectNode)mutated).set("one",mapper.valueToTree(hagrid.core.routing.SectionBindingCompanion.seal(fields)));mapper.writeValue(companion.toFile(),mutated);
   assertThatThrownBy(()->hagrid.core.routing.SectionBindingCompanion.verifyAndRead(companion,restored,restoredNetwork,true)).isInstanceOf(IllegalStateException.class);
  }
  Files.writeString(companion,originalJson);
  assertThatThrownBy(()->hagrid.core.routing.SectionBindingCompanion.verifyAndRead(companion,restored,null,true)).isInstanceOf(NullPointerException.class);
  var verified=hagrid.core.routing.SectionBindingCompanion.verifyAndRead(companion,restored,restoredNetwork,true);
  var alteredScenario=ScenarioUtils.createScenario(ConfigUtils.createConfig());
  assertThatThrownBy(()->hagrid.core.routing.SectionBindingCompanion.install(alteredScenario,verified)).isInstanceOf(IllegalStateException.class);
  assertThat(verified.get("one")).isEqualTo(hagrid.core.routing.SectionBindingCompanion.capture(output,roads,scenario.getNetwork()));
  var qconfig=ConfigUtils.createConfig();qconfig.controller().setLastIteration(0);qconfig.controller().setOutputDirectory(dir.resolve("qsim").toString());qconfig.global().setNumberOfThreads(1);qconfig.qsim().setNumberOfThreads(1);qconfig.qsim().setEndTime(1200);qconfig.qsim().setMainModes(List.of("car"));qconfig.routing().setNetworkModes(List.of("car"));
  var qscenario=ScenarioUtils.createScenario(qconfig);((org.matsim.core.scenario.MutableScenario)qscenario).setNetwork(restoredNetwork);qscenario.addScenarioElement("carrierVehicleTypes",types);qscenario.addScenarioElement("carriers",restored);qscenario.addScenarioElement("carNetwork",restoredNetwork);qscenario.addScenarioElement("bikeNetwork",restoredNetwork);
  hagrid.core.routing.SectionBindingCompanion.install(qscenario,verified);
  var resolved=hagrid.core.config.HagridConfigResolver.resolve(new HagridConfig(),hagrid.core.config.HagridConfigResolver.hannoverTarget(),hagrid.core.config.HagridConfigResolver.Overrides.of(Map.of("optimization.replanningStartIteration","2","optimization.replanningIterations","1")),Map.of());
  var controller=new org.matsim.core.controler.Controler(qscenario);controller.addOverridingModule(new org.matsim.freight.carriers.controller.CarrierModule());controller.addOverridingModule(new hagrid.core.simulation.HAGRIDSimulationModule(qscenario,false,0,resolved));controller.run();
  var ledger=controller.getInjector().getInstance(hagrid.core.simulation.scoring.CarrierExecutionLedger.class);var selectedExecution=ledger.complete(restoredSelected.getId());assertThat(selectedExecution.complete()).as(selectedExecution.violations().toString()).isTrue();assertThat(selectedExecution.activities()).hasSize(2);
  int independentlyCounted=0;var previous=new java.util.HashMap<Id<Tour>,Id<org.matsim.api.core.v01.network.Link>>();
  for(var leg:selectedExecution.legs()) {var links=new java.util.ArrayList<Id<org.matsim.api.core.v01.network.Link>>();links.add(leg.departureLinkId());links.addAll(leg.enteredLinks());links.add(leg.arrivalLinkId());for(var id:links)if(id!=null){var old=previous.put(leg.tourId(),id);if(old!=null&&!old.equals(id)){var classification=roads.classifyTransition(old,id);assertThat(classification).isNotEqualTo(hagrid.core.routing.sections.SectionRoadCosts.TurnClassification.UNKNOWN);if(classification==hagrid.core.routing.sections.SectionRoadCosts.TurnClassification.TURN)independentlyCounted++;}}}
  assertThat(restoredSelected.getAttributes().getAttribute("uTurnCount")).isEqualTo(independentlyCounted);


  ((com.fasterxml.jackson.databind.node.ObjectNode)json.path("one").path("bindings")).put("j1","stop");new com.fasterxml.jackson.databind.ObjectMapper().writeValue(companion.toFile(),json);
  assertThatThrownBy(()->hagrid.core.routing.SectionBindingCompanion.verify(companion,restored)).isInstanceOf(IllegalStateException.class);
  assertThatThrownBy(()->hagrid.core.routing.SectionBindingCompanion.verify(dir.resolve("missing.json"),restored)).isInstanceOf(IllegalStateException.class);
 }
 private static void assertUnpublished(List<Path> paths,org.matsim.api.core.v01.Scenario scenario,org.matsim.api.core.v01.network.Network network,Carrier selected,Map<?,?> services,Carriers delivery,Map<?,?> carriers) throws Exception {
  assertThat(Files.readString(paths.getFirst())).isEqualTo("sentinel");for(var path:paths.subList(1,paths.size()))assertThat(path).doesNotExist();
  assertThat(scenario.getNetwork()).isSameAs(network);assertThat(selected.getServices()).isEqualTo(services);assertThat(delivery.getCarriers()).isEqualTo(carriers);
 }
 private static Carrier carrier(String id,VehicleType type,String...jobs){var carrier=CarriersUtils.createCarrier(Id.create(id,Carrier.class));CarriersUtils.setCarrierMode(carrier,"car");var vehicle=CarrierVehicle.Builder.newInstance(Id.createVehicleId(id),Id.createLinkId("home"),type).setEarliestStart(0).setLatestEnd(1000).build();carrier.setCarrierCapabilities(CarrierCapabilities.Builder.newInstance().addVehicle(vehicle).setFleetSize(CarrierCapabilities.FleetSize.INFINITE).build());for(String job:jobs)CarriersUtils.addService(carrier,CarrierService.Builder.newInstance(Id.create(job,CarrierService.class),Id.createLinkId("stop"),1).setServiceDuration(10).setServiceStartingTimeWindow(TimeWindow.newInstance(0,1000)).build());return carrier;}
 private static void set(Object target,String name,Object value)throws Exception{var field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);}
}


