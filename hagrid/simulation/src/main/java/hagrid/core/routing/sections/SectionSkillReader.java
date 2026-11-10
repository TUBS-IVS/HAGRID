package hagrid.core.routing.sections;
import java.util.Set;
import org.matsim.freight.carriers.*;
import org.matsim.vehicles.VehicleType;
final class SectionSkillReader {
 private static final class Memo { String raw; Set<String> value; }
 private final ThreadLocal<Memo> memo=ThreadLocal.withInitial(Memo::new);
 Set<String> skills(VehicleType type){
  Object raw=type.getAttributes().getAttribute("skills");if(raw==null)return Set.of();
  if(raw instanceof String text){var m=memo.get();if(text.equals(m.raw))return m.value;return remember(m,text,Set.copyOf(CarriersUtils.getSkills(type)));}
  return Set.copyOf(CarriersUtils.getSkills(type));
 }
 Set<String> skills(CarrierService service){
  Object raw=service.getAttributes().getAttribute("skills");if(raw==null)return Set.of();
  if(raw instanceof String text){var m=memo.get();if(text.equals(m.raw))return m.value;return remember(m,text,Set.copyOf(CarriersUtils.getSkills(service)));}
  return Set.copyOf(CarriersUtils.getSkills(service));
 }
 private static Set<String> remember(Memo m,String raw,Set<String> value){m.raw=raw;m.value=value;return value;}
}
