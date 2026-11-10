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
class SectionPipelineRollbackTest {
 @TempDir Path dir;
 @Test void failedPublicationRestoresNetworkAndCarrierInputs() throws Exception {
  var scenario=ScenarioUtils.createScenario(ConfigUtils.createConfig());var net=scenario.getNetwork();
  var a=NetworkUtils.createAndAddNode(net,Id.createNodeId("a"),new Coord(0,0));var b=NetworkUtils.createAndAddNode(net,Id.createNodeId("b"),new Coord(100,0));var c=NetworkUtils.createAndAddNode(net,Id.createNodeId("c"),new Coord(200,0));
  for(var link:List.of(NetworkUtils.createAndAddLink(net,Id.createLinkId("home"),a,b,100,10,1000,1),NetworkUtils.createAndAddLink(net,Id.createLinkId("stop"),b,c,100,10,1000,1),NetworkUtils.createAndAddLink(net,Id.createLinkId("back"),c,a,100,10,1000,1)))link.setAllowedModes(Set.of("car"));
  var type=VehicleUtils.createVehicleType(Id.create("van",VehicleType.class),"car");type.setMaximumVelocity(10);type.getCapacity().setOther(10);type.getCostInformation().setFixedCost(1.0).setCostsPerMeter(.01).setCostsPerSecond(.01);
  var types=new CarrierVehicleTypes();types.getVehicleTypes().put(type.getId(),type);
  var selected=carrier("one",type,"j1","j2");var hub=new hagrid.core.util.Hub(Id.create("h",hagrid.core.util.Hub.class),"carrier",new Coord(0,0));hub.setAddress("actual generation metadata");hub.setLink(Id.createLinkId("stop"));hub.getAttributes().putAttribute("nested",new ArrayList<>(List.of("original")));selected.getAttributes().putAttribute("hub",hub);var other=carrier("other",type,"j1");var deliveries=new Carriers(List.of(selected,other));
  scenario.addScenarioElement("carriers",deliveries);scenario.addScenarioElement("supply",new Carriers());scenario.addScenarioElement("carrierVehicleTypes",types);
  var catalog=dir.resolve("halts.json");Files.writeString(catalog,"""
   {"schemaVersion":1,"carrierId":"one","depot":{"linkId":"home"},"links":{"stop":{"fromNodeId":"b","toNodeId":"c","lengthMeters":100,"geometryLengthMeters":100,"orientedXY":[[100,0],[200,0]]}},"jobs":[{"jobId":"j1","rawLinkId":"stop","approaches":[{"linkId":"stop","fraction":0.25}]},{"jobId":"j2","rawLinkId":"stop","approaches":[{"linkId":"stop","fraction":0.75}]}]}
  """);
  var config=new HagridConfig();set(config,"hagridPaths",new HagridPaths(dir));config.setSimulationDate(LocalDate.of(2025,5,13));config.routing().setJspritIterations(1);
  config.setAdditionalConfigurationValues(Map.of("routing.sectionMaxSpanMeters","100","routing.haltCatalogFile",catalog.toString()));
  var blocked=Path.of(config.io().networkFiltered());Files.createDirectories(blocked);
  var router=new CarrierRouter();set(router,"scenario",scenario);set(router,"hagridConfig",config);router.setThreadingType(ThreadingType.SINGLE_THREAD);
  assertThatThrownBy(router::run).isInstanceOf(IllegalStateException.class);
  assertThat(scenario.getNetwork()).isSameAs(net);assertThat(deliveries.getCarriers().get(selected.getId())).isSameAs(selected);assertThat(deliveries.getCarriers().get(other.getId())).isSameAs(other);
  assertThat(selected.getServices()).hasSize(2);assertThat(selected.getSelectedPlan()).isNull();assertThat(other.getSelectedPlan()).isNull();assertThat(hub.getLink()).isEqualTo(Id.createLinkId("stop"));
  assertThat(Path.of(config.io().deliveryCarriersRouted())).doesNotExist();
 }
 private static Carrier carrier(String id,VehicleType type,String...jobs){var carrier=CarriersUtils.createCarrier(Id.create(id,Carrier.class));CarriersUtils.setCarrierMode(carrier,"car");var vehicle=CarrierVehicle.Builder.newInstance(Id.createVehicleId(id),Id.createLinkId("home"),type).setEarliestStart(0).setLatestEnd(1000).build();carrier.setCarrierCapabilities(CarrierCapabilities.Builder.newInstance().addVehicle(vehicle).setFleetSize(CarrierCapabilities.FleetSize.INFINITE).build());for(String job:jobs)CarriersUtils.addService(carrier,CarrierService.Builder.newInstance(Id.create(job,CarrierService.class),Id.createLinkId("stop"),1).setServiceDuration(10).setServiceStartingTimeWindow(TimeWindow.newInstance(0,1000)).build());return carrier;}
 private static void set(Object target,String name,Object value)throws Exception{var field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);}
}
