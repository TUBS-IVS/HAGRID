package hagrid.core.routing;

import java.util.Map;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.Config;
import org.matsim.core.router.util.TravelTime;
import org.matsim.freight.carriers.Carriers;

/** Historical name only. Exact frozen routing share DefaultVRPTransportCostsFactory. */
@Deprecated(forRemoval=false)
public class ZoneBasedTransportCostsFactory extends DefaultVRPTransportCostsFactory {
    public ZoneBasedTransportCostsFactory(Scenario scenario,Carriers carriers,Map<String,TravelTime> travelTimes,
            Config config,boolean zoneBasedCachingEnabled,double thresholdMeters) {
        super(scenario,carriers,travelTimes,config,zoneBasedCachingEnabled,thresholdMeters);
    }
}