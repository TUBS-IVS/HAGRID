package hagrid.core.simulation;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierPlan;
import org.matsim.freight.carriers.Tour;

import java.util.HashSet;
import java.util.Set;

/** Job-zone summary, with native MATSim tour activities as a fallback for plans without jobs. */
final class CarrierRoutingZoneMetrics {
    private CarrierRoutingZoneMetrics() {}

    static int count(Carrier carrier, CarrierPlan plan, Network network) {
        if (network == null) return 0;
        Set<Integer> zones = new HashSet<>();
        carrier.getServices().values().forEach(service -> add(network, zones, service.getServiceLinkId()));
        carrier.getShipments().values().forEach(shipment -> {
            add(network, zones, shipment.getPickupLinkId());
            add(network, zones, shipment.getDeliveryLinkId());
        });
        if (zones.isEmpty()) {
            for (var scheduled : plan.getScheduledTours()) {
                for (var element : scheduled.getTour().getTourElements()) {
                    if (element instanceof Tour.TourActivity activity) add(network, zones, activity.getLocation());
                }
            }
        }
        return zones.size();
    }

    private static void add(Network network, Set<Integer> zones, Id<Link> linkId) {
        var link = linkId == null ? null : network.getLinks().get(linkId);
        if (link == null) return;
        Object value = link.getAttributes().getAttribute("zone");
        if (value instanceof Number number) zones.add(number.intValue());
        else if (value instanceof String text) {
            try {
                zones.add(Integer.parseInt(text.trim()));
            } catch (NumberFormatException ignored) {
                // Unclassified links contribute no zone to this diagnostic metric.
            }
        }
    }
}
