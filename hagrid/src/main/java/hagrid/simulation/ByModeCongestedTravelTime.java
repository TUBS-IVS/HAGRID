package hagrid.simulation;

import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.router.util.TravelTime;
import org.matsim.vehicles.Vehicle;

public class ByModeCongestedTravelTime implements TravelTime {

    private final String mode;
    private final TravelTime congestedTravelTime;

    public ByModeCongestedTravelTime(String mode, TravelTime congestedTravelTime) {
        this.mode = mode;
        this.congestedTravelTime = congestedTravelTime;
    }

    @Override
    public double getLinkTravelTime(Link link, double time, Person person, Vehicle vehicle) {
        if(link.getAllowedModes().contains(mode)){
            return congestedTravelTime.getLinkTravelTime(link, time, person, vehicle);
        } else {
            return link.getLength()/0.001;
        }
    }
}