package hagrid.core.simulation;

import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.router.util.TravelTime;
import org.matsim.vehicles.Vehicle;
import java.util.Objects;

/** A mode guard around an explicitly supplied travel-time profile. */
public final class ByModeCongestedTravelTime implements TravelTime {
    private final String mode;
    private final TravelTime travelTime;

    public ByModeCongestedTravelTime(String mode, TravelTime travelTime) {
        if (mode == null || mode.isBlank()) throw new IllegalArgumentException("Travel time requires an explicit mode");
        this.mode=mode;
        this.travelTime=Objects.requireNonNull(travelTime,"travelTime");
    }

    @Override
    public double getLinkTravelTime(Link link, double time, Person person, Vehicle vehicle) {
        if (!link.getAllowedModes().contains(mode)) {
            throw new IllegalArgumentException("Link " + link.getId() + " is not permitted for mode " + mode);
        }
        if (vehicle != null && vehicle.getType() != null && vehicle.getType().hasNetworkMode()
                && !mode.equals(vehicle.getType().getNetworkMode())) {
            throw new IllegalArgumentException("Vehicle mode " + vehicle.getType().getNetworkMode() + " disagrees with travel-time mode " + mode);
        }
        return travelTime.getLinkTravelTime(link,time,person,vehicle);
    }
}