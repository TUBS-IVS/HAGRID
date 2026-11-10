package hagrid.core.routing.sections;
import java.util.List;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
public record SectionVariant(int index, List<String> orderedJobIds, List<Id<Link>> orderedHaltLinks) {
 public SectionVariant { orderedJobIds = List.copyOf(orderedJobIds); orderedHaltLinks = List.copyOf(orderedHaltLinks); }
}
