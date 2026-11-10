package hagrid.hannover.demand;

import hagrid.core.routing.ModalNetworkConnectivity;
import hagrid.core.routing.ModalRoutingNetwork;
import hagrid.core.routing.RoutingNetworkValidator;
import hagrid.core.util.Hub;
import org.matsim.api.core.v01.*;
import org.matsim.api.core.v01.network.*;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.utils.geometry.CoordUtils;
import org.matsim.freight.carriers.Carrier;

import java.util.*;

/** Access links for newly generated demand; never rewrites shared hubs or imported plans. */
final class CarrierGenerationAccess {
    record Depot(Hub hub, Id<Link> link) {}
    private record HubMode(Id<Hub> hub, String mode) {}

    private final Network network;
    private final Set<Id<Link>> carStops;
    private final ModalNetworkConnectivity connectivity;
    private final Map<String, Network> modeNetworks = new HashMap<>();
    private final Map<String, Network> stopNetworks = new HashMap<>();
    private final Map<HubMode, Id<Link>> hubLinks = new HashMap<>();

    CarrierGenerationAccess(Network network, Network parcelServiceNetwork) {
        this.network = Objects.requireNonNull(network, "generation network");
        RoutingNetworkValidator.validate(network);
        carStops = Set.copyOf(Objects.requireNonNull(parcelServiceNetwork, "parcel service network").getLinks().keySet());
        connectivity = new ModalNetworkConnectivity(network);
    }

    Network stops(String mode) {
        return stopNetworks.computeIfAbsent(mode, key -> {
            // Preserve the existing car parcel-road selection. Exact bike modes
            // use the actual network's explicit permissions, including exclusive links.
            var result = key.equals("car")
                    ? ModalRoutingNetwork.forMode(network, key, link -> carStops.contains(link.getId()))
                    : modal(key);
            if (result.getLinks().isEmpty()) {
                throw new IllegalArgumentException("No generated service access links for mode " + key);
            }
            return result;
        });
    }

    Depot selectDepot(Carrier carrier, Collection<Hub> hubs, String mode) {
        String provider = (String) carrier.getAttributes().getAttribute("provider");
        int parcels = carrier.getServices().values().stream().mapToInt(s -> s.getCapacityDemand()).sum();
        return hubs.stream()
                .filter(h -> h.getProvider().contains(provider) && h.hasCapacity(parcels))
                .map(h -> new Depot(h, depotLink(h, mode)))
                .filter(d -> canServe(carrier, d.link(), mode))
                .min(Comparator.comparingDouble((Depot d) -> distance(carrier, d.hub().getCoord()))
                        .thenComparing(d -> d.hub().getId().toString()))
                .orElseThrow(() -> new IllegalArgumentException("Invalid carrier " + carrier.getId()
                        + ": no reachable returning depot with capacity for mode " + mode));
    }

    Id<Link> depotLink(Hub hub, String mode) {
        return hubLinks.computeIfAbsent(new HubMode(hub.getId(), mode), ignored -> {
            var original = network.getLinks().get(hub.getLink());
            if (original != null && original.getAllowedModes().contains(mode)) return original.getId();
            var nearest = NetworkUtils.getNearestLinkExactly(modal(mode), hub.getCoord());
            if (nearest == null) throw new IllegalArgumentException("No depot access links for mode " + mode);
            return nearest.getId();
        });
    }

    private boolean canServe(Carrier carrier, Id<Link> depot, String mode) {
        var access = connectivity.depots(List.of(depot), mode);
        return carrier.getServices().values().stream().allMatch(s -> access.canServe(s.getServiceLinkId()))
                && carrier.getShipments().values().stream()
                .allMatch(s -> access.canServeShipment(s.getPickupLinkId(), s.getDeliveryLinkId()));
    }

    private Network modal(String mode) {
        return modeNetworks.computeIfAbsent(mode, key -> {
            var result = ModalRoutingNetwork.forMode(network, key);
            if (result.getLinks().isEmpty()) throw new IllegalArgumentException("No generation links for mode " + key);
            return result;
        });
    }

    private static double distance(Carrier carrier, Coord hub) {
        return carrier.getServices().values().stream()
                .map(s -> (Coord) s.getAttributes().getAttribute("coord"))
                .mapToDouble(coord -> CoordUtils.calcEuclideanDistance(coord, hub)).min().orElse(Double.POSITIVE_INFINITY);
    }
}
