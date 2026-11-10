package hagrid.core.simulation;
import hagrid.core.HagridConfig;
import hagrid.core.config.*;
import hagrid.core.routing.*;
import hagrid.core.routing.sections.*;
import hagrid.core.simulation.scoring.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.*;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controler;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.scenario.*;
import org.matsim.freight.carriers.*;
import org.matsim.freight.carriers.controller.CarrierModule;
import org.matsim.vehicles.*;
import java.nio.file.Path;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class SectionQSimTest {
 @TempDir Path output;
 @ParameterizedTest @ValueSource(ints={1,2}) void exactOriginMarkerExecutesOriginalService(int jobs) {
  var cfg=ConfigUtils.createConfig();cfg.controller().setLastIteration(0);cfg.controller().setOutputDirectory(output.resolve("jobs-"+jobs).toString());cfg.global().setNumberOfThreads(1);cfg.qsim().setNumberOfThreads(1);cfg.qsim().setEndTime(400);cfg.qsim().setMainModes(List.of("car"));cfg.routing().setNetworkModes(List.of("car"));
  var scenario=ScenarioUtils.createScenario(cfg);var network=scenario.getNetwork();
  var a=NetworkUtils.createAndAddNode(network,Id.createNodeId("a"),new Coord(0,0));var b=NetworkUtils.createAndAddNode(network,Id.createNodeId("b"),new Coord(100,0));var c=NetworkUtils.createAndAddNode(network,Id.createNodeId("c"),new Coord(200,0));
  for(var link:List.of(NetworkUtils.createAndAddLink(network,Id.createLinkId("home"),a,b,100,10,36000,1),NetworkUtils.createAndAddLink(network,Id.createLinkId("stop"),b,c,100,10,36000,1),NetworkUtils.createAndAddLink(network,Id.createLinkId("back"),c,a,100,10,36000,1)))link.setAllowedModes(Set.of("car"));
  var type=VehicleUtils.createVehicleType(Id.create("van",VehicleType.class),"car");type.setMaximumVelocity(10);type.getCapacity().setOther(1);type.getCostInformation().setFixedCost(0.0).setFixedCost(0.0).setCostsPerMeter(0.0).setCostsPerSecond(1.0);
  var types=new CarrierVehicleTypes();types.getVehicleTypes().put(type.getId(),type);
  var original=CarriersUtils.createCarrier(Id.create("marker",Carrier.class));CarriersUtils.setCarrierMode(original,"car");
  var vehicle=CarrierVehicle.Builder.newInstance(Id.createVehicleId("template"),Id.createLinkId("home"),type).setEarliestStart(20).setLatestEnd(350).build();original.setCarrierCapabilities(CarrierCapabilities.Builder.newInstance().addVehicle(vehicle).setFleetSize(CarrierCapabilities.FleetSize.INFINITE).build());
  var job=CarrierService.Builder.newInstance(Id.create("j",CarrierService.class),Id.createLinkId("stop"),1).setServiceDuration(5).setServiceStartingTimeWindow(TimeWindow.newInstance(20,300)).build();CarriersUtils.addService(original,job);if(jobs==2)CarriersUtils.addService(original,CarrierService.Builder.newInstance(Id.create("j2",CarrierService.class),Id.createLinkId("stop"),1).setServiceDuration(5).setServiceStartingTimeWindow(TimeWindow.newInstance(20,300)).build());
  var road=new PreparedHaltCatalog.Road("b","c",100,100,List.of(new PreparedHaltCatalog.Point(100,0),new PreparedHaltCatalog.Point(200,0)));
  var haltJobs=new ArrayList<PreparedHaltCatalog.Job>();haltJobs.add(new PreparedHaltCatalog.Job("j","stop",List.of(new PreparedHaltCatalog.Approach("stop",0)),"",""));if(jobs==2)haltJobs.add(new PreparedHaltCatalog.Job("j2","stop",List.of(new PreparedHaltCatalog.Approach("stop",0)),"",""));var input=new PreparedHaltCatalog("marker",Map.of("stop",road),haltJobs,"home");
  var split=SplitRoadNetworkBuilder.build(network,input);var marker=split.jobApproaches().get("j").getFirst().haltLinkId();assertThat(split.network().getLinks().get(marker).getLength()).isZero();
  var catalog=SectionCatalogBuilder.build(original,split,100,10);var costs=NativeModalTransportCosts.create(split.network(),types,"car");var roads=StaticSectionRoadCosts.prepare(split,original.getCarrierCapabilities().getCarrierVehicles().values(),costs,0);
  var result=new CarrierOptimizationService().searchSections(catalog,split.network(),roads,300,300,0,4711,new SectionSearchPolicy(2,.25,.4));var expanded=SectionPlanExpander.expand(result,catalog,roads);
  assertThat(expanded.bindings().get("j")).isEqualTo(marker);expanded.carrier().addPlan(expanded.plan());expanded.carrier().setSelectedPlan(expanded.plan());
  ((MutableScenario)scenario).setNetwork(split.network());scenario.addScenarioElement("carrierVehicleTypes",types);scenario.addScenarioElement("carriers",new Carriers(List.of(expanded.carrier())));scenario.addScenarioElement("carNetwork",split.network());scenario.addScenarioElement("bikeNetwork",split.network());
  SectionBindingCompanion.install(scenario,Map.of(expanded.carrier().getId().toString(),SectionBindingCompanion.capture(expanded.carrier(),roads,split.network())));
  var resolved=HagridConfigResolver.resolve(new HagridConfig(),HagridConfigResolver.hannoverTarget(),HagridConfigResolver.Overrides.of(Map.of("optimization.replanningStartIteration","2","optimization.replanningIterations","1")),Map.of());
  var controller=new Controler(scenario);controller.addOverridingModule(new CarrierModule());controller.addOverridingModule(new HAGRIDSimulationModule(scenario,false,0,resolved));controller.run();
  var execution=controller.getInjector().getInstance(CarrierExecutionLedger.class).complete(expanded.carrier().getId());assertThat(execution.complete()).as(execution.violations().toString()).isTrue();assertThat(execution.activities()).hasSize(jobs);assertThat(execution.legs()).hasSize(2*jobs);assertThat(execution.activities().stream().map(CarrierExecutionLedger.ExecutedActivity::jobId)).contains("service:j");assertThat(execution.tours().stream().map(CarrierExecutionLedger.ExecutedTour::vehicleId).distinct()).hasSize(jobs);
  var planned=expanded.plan().getScheduledTours().iterator().next();var plannedActivity=(Tour.ServiceActivity)planned.getTour().getTourElements().get(1);
  System.out.printf(java.util.Locale.ROOT,"SECTION_ORIGIN_MARKER plannedArrival=%.3f executedStart=%.3f executedEnd=%.3f%n",plannedActivity.getExpectedArrival(),execution.activities().getFirst().startSeconds(),execution.activities().getFirst().endSeconds());
 }
 @org.junit.jupiter.api.Test void nativeModuleCountsCertifiedTurnAndTariff() {
  var cfg=ConfigUtils.createConfig();cfg.controller().setLastIteration(0);cfg.controller().setOutputDirectory(output.resolve("turn").toString());cfg.global().setNumberOfThreads(1);cfg.qsim().setNumberOfThreads(1);cfg.qsim().setEndTime(500);cfg.qsim().setMainModes(List.of("car"));cfg.routing().setNetworkModes(List.of("car"));
  var scenario=ScenarioUtils.createScenario(cfg);var n=scenario.getNetwork();var a=NetworkUtils.createAndAddNode(n,Id.createNodeId("a"),new Coord(0,0));var b=NetworkUtils.createAndAddNode(n,Id.createNodeId("b"),new Coord(100,0));
  var home=NetworkUtils.createAndAddLink(n,Id.createLinkId("home"),a,b,100,10,36000,1);var reverse=NetworkUtils.createAndAddLink(n,Id.createLinkId("reverse"),b,a,100,10,36000,1);var parallel=NetworkUtils.createAndAddLink(n,Id.createLinkId("parallel"),b,a,400,10,36000,1);home.setAllowedModes(Set.of("car"));reverse.setAllowedModes(Set.of("car"));parallel.setAllowedModes(Set.of("car"));home.getAttributes().putAttribute("osm:way:id","same");parallel.getAttributes().putAttribute("osm:way:id","different");
  var type=VehicleUtils.createVehicleType(Id.create("van",VehicleType.class),"car");type.setMaximumVelocity(10);type.getCapacity().setOther(2);type.getCostInformation().setFixedCost(0.0).setCostsPerMeter(0.0).setCostsPerSecond(1.0);var types=new CarrierVehicleTypes();types.getVehicleTypes().put(type.getId(),type);
  var carrier=CarriersUtils.createCarrier(Id.create("turn",Carrier.class));CarriersUtils.setCarrierMode(carrier,"car");var vehicle=CarrierVehicle.Builder.newInstance(Id.createVehicleId("v"),home.getId(),type).setEarliestStart(20).setLatestEnd(400).build();carrier.setCarrierCapabilities(CarrierCapabilities.Builder.newInstance().addVehicle(vehicle).setFleetSize(CarrierCapabilities.FleetSize.INFINITE).build());
  var job=CarrierService.Builder.newInstance(Id.create("job",CarrierService.class),reverse.getId(),1).setServiceDuration(5).setServiceStartingTimeWindow(TimeWindow.newInstance(0,400)).build();CarriersUtils.addService(carrier,job);var job2=CarrierService.Builder.newInstance(Id.create("job2",CarrierService.class),parallel.getId(),1).setServiceDuration(5).setServiceStartingTimeWindow(TimeWindow.newInstance(0,400)).build();CarriersUtils.addService(carrier,job2);
  var tour=Tour.Builder.newInstance(Id.create("t",Tour.class));tour.scheduleStart(home.getId());tour.addLeg(tour.createLeg(org.matsim.core.population.routes.RouteUtils.createLinkNetworkRouteImpl(home.getId(),reverse.getId()),20,20));tour.scheduleService(job);tour.addLeg(tour.createLeg(org.matsim.core.population.routes.RouteUtils.createLinkNetworkRouteImpl(reverse.getId(),List.of(home.getId()),parallel.getId()),45,60));tour.scheduleService(job2);tour.addLeg(tour.createLeg(org.matsim.core.population.routes.RouteUtils.createLinkNetworkRouteImpl(parallel.getId(),home.getId()),110,20));tour.scheduleEnd(home.getId());
  var plan=new CarrierPlan(List.of(ScheduledTour.newInstance(tour.build(),vehicle,20)));carrier.addPlan(plan);carrier.setSelectedPlan(plan);carrier.getAttributes().putAttribute(SectionBindingCompanion.MARKER,"fixture");
  var children=new LinkedHashMap<Id<org.matsim.api.core.v01.network.Link>,List<Id<org.matsim.api.core.v01.network.Link>>>();n.getLinks().keySet().forEach(id->children.put(id,List.of(id)));
  var approaches=Map.of("pair",List.of(new HaltApproach(home.getId(),1,home.getId()),new HaltApproach(reverse.getId(),0,reverse.getId())),"parallel",List.of(new HaltApproach(parallel.getId(),1,parallel.getId())));
  var roads=StaticSectionRoadCosts.prepare(new SplitRoadNetwork(n,children,approaches,"native-turn"),List.of(vehicle),NativeModalTransportCosts.create(n,types,"car"),7);
  assertThat(roads.classifyTransition(home.getId(),reverse.getId())).isEqualTo(SectionRoadCosts.TurnClassification.TURN);assertThat(roads.classifyTransition(home.getId(),parallel.getId())).isEqualTo(SectionRoadCosts.TurnClassification.NOT_TURN);
  var evidence=SectionBindingCompanion.capture(carrier,roads,n);scenario.addScenarioElement("carrierVehicleTypes",types);scenario.addScenarioElement("carriers",new Carriers(List.of(carrier)));scenario.addScenarioElement("carNetwork",n);scenario.addScenarioElement("bikeNetwork",n);SectionBindingCompanion.install(scenario,Map.of("turn",evidence));
  var resolved=HagridConfigResolver.resolve(new HagridConfig(),HagridConfigResolver.hannoverTarget(),HagridConfigResolver.Overrides.of(Map.of("optimization.replanningStartIteration","2","optimization.replanningIterations","1","tourRules.uTurnPenaltyCost","7")),Map.of());
  var controller=new Controler(scenario);controller.addOverridingModule(new CarrierModule());controller.addOverridingModule(new HAGRIDSimulationModule(scenario,false,0,resolved));try { controller.run(); } finally { org.matsim.core.controler.OutputDirectoryLogging.closeOutputDirLogging(); }
  var execution=controller.getInjector().getInstance(CarrierExecutionLedger.class).complete(carrier.getId());assertThat(execution.complete()).as(execution.violations().toString()).isTrue();
  assertThat(carrier.getAttributes().getAttribute("uTurnCount")).isEqualTo(2);
  var evaluation=CarrierCostCalculator.calculate(carrier,execution,resolved,n,evidence);assertThat(evaluation.costs().uTurnCount()).isEqualTo(2);assertThat(evaluation.score()).isEqualTo(-evaluation.costs().totalCosts()-14);
 }

}


