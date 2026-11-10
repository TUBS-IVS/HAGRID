package hagrid.hannover.demand;
import hagrid.core.config.SectionSettings;
import hagrid.core.routing.sections.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.*;
import org.matsim.core.network.NetworkUtils;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class SectionHagridInputsTest {
 @TempDir Path dir;
 @Test void hagridInputsDoNotRequireManualCatalogOrExternalSources() throws Exception {
  assertThat(SectionSettings.from(Map.of("routing.sectionMaxSpanMeters","200","routing.sectionHagridInputs","hagrid.json")).enabled()).isTrue();
  var file=dir.resolve("catalog.json");Files.writeString(file,"{\"schemaVersion\":1,\"carrierId\":\"test\",\"depot\":{\"linkId\":\"a\"},\"links\":{},\"jobs\":[]}");
  assertThat(SectionEvidencePreparation.prepare(NetworkUtils.createNetwork(),file,Map.of(),5)).isEqualTo(file.toAbsolutePath());
  assertThatThrownBy(()->SectionEvidencePreparation.prepare(NetworkUtils.createNetwork(),file,Map.of("routing.sectionEvidenceConfig","old-osm.json"),5)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("External section evidence");
 }
 @Test void autoInputsFollowActualDemandFileAndKeepPolicyOverrides() throws Exception {
  var file=dir.resolve("routing.properties");
  Files.writeString(file,"routing.sectionMaxSpanMeters=200\nrouting.sectionHagridInputs=auto\nrouting.sectionAllowStreetCrossing=false\nrouting.sectionUnknownTurnPolicy=NETWORK_REVERSAL\n");
  var config=new hagrid.core.HagridConfig(new hagrid.core.HagridPaths(dir));
  hagrid.core.config.HagridConfigFile.load(file).applyTo(config);
  var settings=SectionSettings.from(config.getAdditionalConfigurationValues());
  assertThat(settings.hagridInputsFile()).contains("auto");
  assertThat(SectionEvidencePreparation.resolveHagridInputs("auto",dir.resolve("demand/day.shp").toString()))
    .isEqualTo(dir.resolve("demand/hagrid-section-inputs.json").toAbsolutePath());
  assertThat(config.getAdditionalConfigurationValues()).containsEntry("routing.sectionAllowStreetCrossing","false").containsEntry("routing.sectionUnknownTurnPolicy","NETWORK_REVERSAL");
  assertThatThrownBy(()->SectionSettings.validate("routing.sectionUnknownTurnPolicy","guess")).isInstanceOf(IllegalArgumentException.class);
  assertThatThrownBy(()->SectionSettings.validate("routing.sectionAllowStreetCrossing","maybe")).isInstanceOf(IllegalArgumentException.class);
 }
 @Test void matchingEndpointsDoNotMakeDifferentCurvesReverseApproaches() {
  var net=NetworkUtils.createNetwork();var a=NetworkUtils.createAndAddNode(net,Id.createNodeId("a"),new Coord(0,0));var b=NetworkUtils.createAndAddNode(net,Id.createNodeId("b"),new Coord(100,0));
  NetworkUtils.createAndAddLink(net,Id.createLinkId("out"),a,b,120,10,1000,1);NetworkUtils.createAndAddLink(net,Id.createLinkId("back"),b,a,120,10,1000,1);
  var upper=List.of(new PreparedHaltCatalog.Point(0,0),new PreparedHaltCatalog.Point(50,20),new PreparedHaltCatalog.Point(100,0));
  var lower=List.of(new PreparedHaltCatalog.Point(100,0),new PreparedHaltCatalog.Point(50,-20),new PreparedHaltCatalog.Point(0,0));
  double length=2*Math.hypot(50,20);
  var roads=Map.of("out",new PreparedHaltCatalog.Road("a","b",120,length,upper),"back",new PreparedHaltCatalog.Road("b","a",120,length,lower));
  var job=new PreparedHaltCatalog.Job("j","out",List.of(new PreparedHaltCatalog.Approach("out",.5),new PreparedHaltCatalog.Approach("back",.5)),"HOME","s");
  assertThatThrownBy(()->SplitRoadNetworkBuilder.build(net,new PreparedHaltCatalog("test",roads,List.of(job),"out"))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Different road geometries");
 }
}
