package hagrid.core.routing;
import org.matsim.core.population.routes.NetworkRoute;
import java.util.Objects;
/** Defensive copies on construction and read keep mutable native routes out of shared state. */
public record RoadLeg(NetworkRoute route,double travelTimeSeconds,double distanceMeters,double monetaryCost) {
    public RoadLeg {
        route=(NetworkRoute)Objects.requireNonNull(route).clone();
        for(double value:new double[]{travelTimeSeconds,distanceMeters,monetaryCost})
            if(!Double.isFinite(value)||value<0)throw new IllegalArgumentException("Invalid road leg value " + value);
    }
    @Override public NetworkRoute route() { return (NetworkRoute)route.clone(); }
}
