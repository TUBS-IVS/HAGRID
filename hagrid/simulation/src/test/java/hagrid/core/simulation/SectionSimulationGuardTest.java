package hagrid.core.simulation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Id;
import org.matsim.freight.carriers.*;
import java.nio.file.*;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
class SectionSimulationGuardTest {
 @TempDir Path dir;
 @Test void sectionsRejectReplanningButOrdinaryCarriersAreUnaffected(){
  var carrier=CarriersUtils.createCarrier(Id.create("c",Carrier.class));var carriers=new Carriers(List.of(carrier));
  hagrid.core.routing.SectionBindingCompanion.requireStaticExecution(carriers,0,100);
  carrier.getAttributes().putAttribute("section.catalogFingerprint","abc");
  hagrid.core.routing.SectionBindingCompanion.requireStaticExecution(carriers,0,0);
  assertThatThrownBy(()->hagrid.core.routing.SectionBindingCompanion.requireStaticExecution(carriers,0,1)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("replanning");
 }
 @Test void ordinaryRunIgnoresStaleSectionCompanion() throws Exception {
  var carrier=CarriersUtils.createCarrier(Id.create("c",Carrier.class));var carriers=new Carriers(List.of(carrier));var old=dir.resolve("stale.json");Files.writeString(old,"{\"old\":{}}");
  hagrid.core.routing.SectionBindingCompanion.verify(old,carriers,null,false);
  assertThatThrownBy(()->hagrid.core.routing.SectionBindingCompanion.verify(old,carriers,null,true)).isInstanceOf(IllegalStateException.class);
 }
}
