package hagrid.hannover.demand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;
import org.matsim.freight.carriers.*;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class SectionMergeBypassTest {
 @TempDir Path dir;
 @Test void configuredSectionCarrierBypassesFullMergeWhileOthersMerge() throws Exception {
  var f=new DemandModalFixture(dir,"car");var selected=f.carrier("selected","car","car-home");var ordinary=f.carrier("ordinary","car","car-home");
  for(var c:List.of(selected,ordinary)){CarriersUtils.addService(c,DemandModalFixture.service("a","car-stop",1,new Coord(75,0),0,86400));CarriersUtils.addService(c,DemandModalFixture.service("b","car-stop",1,new Coord(75,0),0,86400));}
  var file=dir.resolve("halts.json");Files.writeString(file,"{\"schemaVersion\":1,\"carrierId\":\"selected\",\"depot\":{\"linkId\":\"car-home\"},\"links\":{},\"jobs\":[]}");
  f.config.setAdditionalConfigurationValues(Map.of("routing.sectionMaxSpanMeters","100","routing.haltCatalogFile",file.toString()));
  CarrierServiceMerger.mergeServices(new Carriers(List.of(selected,ordinary)),f.scenario,f.config,true);
  assertThat(selected.getServices()).hasSize(2);assertThat(ordinary.getServices()).hasSize(1);
 }
}
