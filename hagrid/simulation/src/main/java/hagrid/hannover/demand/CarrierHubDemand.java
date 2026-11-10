package hagrid.hannover.demand;
import hagrid.core.util.Hub;
import org.matsim.api.core.v01.Id;
import org.matsim.freight.carriers.*;
import java.util.*;

/** Derives supply demand from the final delivery assignment, without adding it twice. */
final class CarrierHubDemand {
    private CarrierHubDemand() {}
    static void assign(Carriers carriers, Map<Id<Hub>, Hub> hubs) {
        var counts = new LinkedHashMap<Id<Hub>, Integer>();
        hubs.keySet().forEach(id -> counts.put(id, 0));
        for (var carrier : carriers.getCarriers().values()) {
            Object recorded = carrier.getAttributes().getAttribute("hubId");
            Object object = carrier.getAttributes().getAttribute("hub");
            Id<Hub> id = recorded != null ? Id.create(recorded.toString(), Hub.class)
                    : object instanceof Hub h ? h.getId() : null;
            if (!hubs.containsKey(id) || object instanceof Hub h && !h.getId().equals(id)) {
                throw new IllegalArgumentException("Invalid hub assignment for carrier " + carrier.getId());
            }
            int parcels = 0;
            for (var service : carrier.getServices().values()) {
                if (service.getCapacityDemand() < 0) throw new IllegalArgumentException("Negative demand " + service.getId());
                parcels = Math.addExact(parcels, service.getCapacityDemand());
            }
            counts.put(id, Math.addExact(counts.get(id), parcels));
        }
        // Validate all assignments first; updates are replacements, never increments.
        counts.forEach((id, count) -> hubs.get(id).setAssignedSupplyDemand(count));
    }
}
