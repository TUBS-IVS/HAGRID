package hagrid.core.routing;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.vehicles.Vehicle;
/** Costs and published roads share an exact link-to-link contract. */
public interface LinkRouteProvider {
    RoadLeg routeLeg(Id<Link> from,Id<Link> to,double departure,Vehicle vehicle);
}
