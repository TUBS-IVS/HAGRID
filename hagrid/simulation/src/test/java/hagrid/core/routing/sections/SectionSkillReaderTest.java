package hagrid.core.routing.sections;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.freight.carriers.*;
import org.matsim.vehicles.*;
import static org.assertj.core.api.Assertions.*;
class SectionSkillReaderTest {
 @Test void parsesFreshAttributesAndRetainsOnlyOneImmutableStringValue() {
  var reader=new SectionSkillReader(); var type=VehicleUtils.createVehicleType(Id.create("skills",VehicleType.class),"car");
  var service=CarrierService.Builder.newInstance(Id.create("job",CarrierService.class),Id.createLinkId("l")).build();
  for(String raw:new String[]{"",",",",a", "a,,b,", "a,a", " a,A", "x,y"}) {
   type.getAttributes().putAttribute("skills",raw);
   assertThat(reader.skills(type)).isEqualTo(Set.copyOf(CarriersUtils.getSkills(type)));
  }
  type.getAttributes().putAttribute("skills",new String("x,y")); var cached=reader.skills(type);
  assertThat(reader.skills(type)).isSameAs(cached); assertThatThrownBy(()->cached.add("z")).isInstanceOf(UnsupportedOperationException.class);
  service.getAttributes().putAttribute("skills","other");reader.skills(service);assertThat(reader.skills(type)).isNotSameAs(cached);
  type.getAttributes().removeAttribute("skills");assertThat(reader.skills(type)).isEmpty();type.getAttributes().putAttribute("skills","y,x");assertThat(reader.skills(type)).containsExactlyInAnyOrder("x","y");type.getAttributes().putAttribute("skills","x,y");assertThat(reader.skills(type)).containsExactlyInAnyOrder("x","y");
  var mutable=new StringBuilder("a");type.getAttributes().putAttribute("skills",mutable);assertThat(reader.skills(type)).containsExactly("a");mutable.append(",b");assertThat(reader.skills(type)).containsExactlyInAnyOrder("a","b");
 }
}

