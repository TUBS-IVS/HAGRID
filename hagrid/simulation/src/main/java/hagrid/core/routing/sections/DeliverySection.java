package hagrid.core.routing.sections;
import java.util.List;
public record DeliverySection(String id, List<String> memberIds, int demand, List<SectionVariant> variants) {
 public DeliverySection { memberIds = List.copyOf(memberIds); variants = List.copyOf(variants); }
}
