package hagrid.hannover.demand;

import hagrid.core.HagridConfig;
import hagrid.core.HagridPaths;
import hagrid.core.config.*;
import hagrid.core.util.Region;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.locationtech.jts.geom.*;
import org.matsim.api.core.v01.*;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.gis.ShapeFileWriter;
import org.matsim.freight.carriers.*;
import org.matsim.vehicles.VehicleUtils;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class LegacyLocationInputsTest {
 @TempDir Path root;
 private record Fixture(HagridConfig config,Scenario scenario,LogisticsDataProcessor reader){}
 private Fixture fixture() throws Exception {
  var config=new HagridConfig(new HagridPaths(root));
  Files.createDirectories(root.resolve("input/hannover/geodata"));
  Files.createDirectories(root.resolve("input/hannover/hubs"));
  Files.createDirectories(root.resolve("input/hannover/vehicles"));
  var schema=new SimpleFeatureTypeBuilder();schema.setName("regions");schema.add("the_geom",MultiPolygon.class);schema.add("NAME_3",String.class);
  var type=schema.buildFeatureType();var gf=new GeometryFactory();
  var polygon=gf.createPolygon(new Coordinate[]{new Coordinate(499000,5799000),new Coordinate(501000,5799000),new Coordinate(501000,5801000),new Coordinate(499000,5801000),new Coordinate(499000,5799000)});
  var features=new ArrayList<org.geotools.api.feature.simple.SimpleFeature>();
  for(var region:Region.values())if(region!=Region.ALL)features.add(SimpleFeatureBuilder.build(type,new Object[]{gf.createMultiPolygon(new Polygon[]{polygon}),region.name()},region.name()));
  ShapeFileWriter.writeGeometries(features,root.resolve("input/hannover/geodata/Region Hannover.shp").toString());
  Files.writeString(Path.of(config.getHubDataPath()),"x;y;company;unused;type;street;zip;location\n500010;5800010;DHL;x;depot;street;30159;Hannover\n");
  var types=new CarrierVehicleTypes();var vehicleType=VehicleUtils.createVehicleType(Id.create("van",org.matsim.vehicles.VehicleType.class));vehicleType.getCapacity().setOther(100);vehicleType.setNetworkMode("car");
  types.getVehicleTypes().put(vehicleType.getId(),vehicleType);new CarrierVehicleTypeWriter(types).write(config.getVehicleTypePath());
  var scenario=ScenarioUtils.createScenario(ConfigUtils.createConfig());var network=scenario.getNetwork();var factory=network.getFactory();
  var a=factory.createNode(Id.createNodeId("a"),new Coord(500000,5800000));var b=factory.createNode(Id.createNodeId("b"),new Coord(500100,5800000));
  network.addNode(a);network.addNode(b);network.addLink(factory.createLink(Id.createLinkId("ab"),a,b));
  var reader=new LogisticsDataProcessor();
  for(var name:List.of("scenario","hagridConfig")){var field=LogisticsDataProcessor.class.getDeclaredField(name);field.setAccessible(true);field.set(reader,name.equals("scenario")?scenario:config);}
  return new Fixture(config,scenario,reader);
 }
 private Path properties(String text)throws Exception{return Files.writeString(root.resolve("test.properties"),text);}
 @Test void explicitCalendarProfilePublishesRequiredInputsWithoutUnusedLegacyCsvs() throws Exception {
  var f=fixture();Path file=properties("demand.filterRegions=HANNOVER\nhubs.loadLegacyLocations=false\nhubs.fixedParcelLockerDemand=false\n");
  assertThatCode(()->f.config.loadConfiguration(file)).doesNotThrowAnyException();
  assertThatCode(f.reader::run).doesNotThrowAnyException();
  assertThat((Map<?,?>)f.scenario.getScenarioElement("hubList")).hasSize(1);
  assertThat((Map<?,?>)f.scenario.getScenarioElement("parcelLockerList")).isEmpty();
  assertThat((Map<?,?>)f.scenario.getScenarioElement("shippingPointList")).isEmpty();
  assertThat(f.scenario.getScenarioElement("carrierVehicleTypes")).isNotNull();
 }
 @Test void historicDefaultStillFailsForMissingLegacyCsvRatherThanSilentlySkipping() throws Exception {
  var f=fixture();assertThatThrownBy(f.reader::run).hasRootCauseInstanceOf(java.io.FileNotFoundException.class);
  assertThat(f.scenario.getScenarioElement("hubList")).isNull();
 }
 @Test void calendarSwitchDoesNotMakeRequiredDepotsOptional() throws Exception {
  var f=fixture();Path file=properties("hubs.loadLegacyLocations=false\n");
  assertThatCode(()->f.config.loadConfiguration(file)).doesNotThrowAnyException();
  Files.delete(Path.of(f.config.getHubDataPath()));
  assertThatThrownBy(f.reader::run).hasRootCauseInstanceOf(java.io.FileNotFoundException.class);
  assertThat(f.scenario.getScenarioElement("carrierVehicleTypes")).isNull();
 }
 @Test void extraFixedLockerDemandCannotDisableItsRequiredLocationSource() throws Exception {
  Path file=properties("hubs.loadLegacyLocations=false\nhubs.fixedParcelLockerDemand=true\n");
  assertThatThrownBy(()->HagridConfigFile.load(file)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("fixedParcelLockerDemand");
 }
 @Test void centralRegionSelectionReachesConsumerAndResolvedEvidence() throws Exception {
  var config=new HagridConfig();Path file=properties("demand.filterRegions=HANNOVER\n");
  assertThatCode(()->config.loadConfiguration(file)).doesNotThrowAnyException();
  assertThat(config.getFilterRegions()).containsExactly(Region.HANNOVER);
  var resolved=HagridConfigResolver.resolve(config,HagridConfigResolver.Overrides.empty(),HagridConfigResolver.Overrides.empty(),Map.of());
  assertThat(resolved.effectiveValues()).containsEntry("demand.filterRegions","HANNOVER");
  assertThatThrownBy(()->HagridConfigFile.load(properties("demand.filterRegions=UNKNOWN_PLACE\n"))).isInstanceOf(IllegalArgumentException.class);
 }
}