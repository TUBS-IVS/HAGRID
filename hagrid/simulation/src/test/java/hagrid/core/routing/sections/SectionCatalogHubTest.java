package hagrid.core.routing.sections;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.*;
import org.matsim.freight.carriers.*;
import hagrid.core.util.Hub;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class SectionCatalogHubTest {
 @Test void generatedHubMetadataIsDetachedAndGuarded() {
  var source=CarriersUtils.createCarrier(Id.create("c",Carrier.class));
  var hub=new Hub(Id.create("h",Hub.class),"provider",new Coord(1,2));
  hub.setAddress("before");hub.setCapacityLimit(12);hub.setProviderWLSupplyDemand("p",4);
  var nested=new ArrayList<>(List.of("original"));hub.getAttributes().putAttribute("nested",nested);
  source.getAttributes().putAttribute("hub",hub);
  var catalog=new SectionCatalog(source,List.of(),"x");
  var copied=(Hub)catalog.originalCarrier().getAttributes().getAttribute("hub");
  nested.add("changed");hub.setAddress("after");
  assertThat((Collection<?>)copied.getAttributes().getAttribute("nested")).hasSize(1);
  assertThat(copied.getAddress()).isEqualTo("before");
  catalog.requireUnchanged();copied.setCapacityLimit(13);
  assertThatThrownBy(catalog::requireUnchanged).isInstanceOf(IllegalStateException.class);
 }
 @Test void detectsHiddenHubCapacityStateChanges() {
  var source=CarriersUtils.createCarrier(Id.create("c",Carrier.class));var hub=new Hub(Id.create("h",Hub.class),"p",new Coord(1,2));
  hub.setCapacityLimit(3);hub.setAssignedSupplyDemand(2);hub.setCapacityLimit(2);source.getAttributes().putAttribute("hub",hub);
  var catalog=new SectionCatalog(source,List.of(),"x");var detached=(Hub)catalog.originalCarrier().getAttributes().getAttribute("hub");assertThat(detached.hasCapacity(0)).isFalse();
  detached.setCapacityLimit(1);detached.setAssignedSupplyDemand(2);detached.setCapacityLimit(2);assertThat(detached.hasCapacity(0)).isFalse();
  assertThatThrownBy(catalog::requireUnchanged).isInstanceOf(IllegalStateException.class);
 }

}
