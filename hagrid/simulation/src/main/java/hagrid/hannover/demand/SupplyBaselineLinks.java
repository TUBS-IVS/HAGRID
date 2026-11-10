package hagrid.hannover.demand;

import hagrid.core.util.Hub;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.*;
import org.matsim.freight.carriers.CarrierVehicleTypes;
import java.nio.file.Path;
import java.util.*;

/** Compatibility projection. Callers needing multiple channels must use SupplyBaseline. */
final class SupplyBaselineLinks {
    private SupplyBaselineLinks() {}
    static Map<String,Id<Link>> read(String file, CarrierVehicleTypes types, Network network, Map<Id<Hub>,Hub> hubs) {
        var baseline = SupplyBaseline.read(Path.of(file),types,network,hubs);
        var result = new LinkedHashMap<String,Id<Link>>();
        var counts = new HashMap<String,Integer>();
        for (var lane : baseline.lanes()) {
            if (counts.merge(lane.hubId(),1,Integer::sum) != 1)
                throw new IllegalArgumentException("Multiple baseline channels for hub " + lane.hubId() + "; use SupplyBaseline");
            result.put(lane.hubId(),lane.depotLink());
        }
        return Map.copyOf(result);
    }
}
