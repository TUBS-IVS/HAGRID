package hagrid.core.routing.sections;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
public record HaltApproach(Id<Link> originalLinkId, double fraction, Id<Link> haltLinkId) {}
