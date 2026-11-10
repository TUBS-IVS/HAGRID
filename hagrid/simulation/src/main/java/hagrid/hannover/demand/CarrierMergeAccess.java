package hagrid.hannover.demand;
import hagrid.core.routing.CarrierModeResolver;
import hagrid.core.routing.ModalNetworkConnectivity;
import org.matsim.api.core.v01.network.Network;
import org.matsim.freight.carriers.Carrier;
import java.util.*;

/** Shared necessary checks before transferring generated jobs to another carrier. */
final class CarrierMergeAccess {
    private final ModalNetworkConnectivity connectivity;
    private final Map<Carrier, ModalNetworkConnectivity.DepotAccess> depots = new IdentityHashMap<>();
    CarrierMergeAccess(Network network) { connectivity = new ModalNetworkConnectivity(network); }

    boolean canMerge(Carrier source, Carrier target) {
        String mode = CarrierModeResolver.resolve(source).orElse(null);
        if (mode == null || !Objects.equals(mode, CarrierModeResolver.resolve(target).orElse(null))) return false;
        // This legacy operation moves services only; never discard a shipment carrier.
        if (!source.getShipments().isEmpty() || !target.getShipments().isEmpty()) return false;
        if (source.getServices().keySet().stream().anyMatch(target.getServices()::containsKey)) return false;
        var access = depots.computeIfAbsent(target, ignored -> connectivity.depots(
                target.getCarrierCapabilities().getCarrierVehicles().values().stream().map(v -> v.getLinkId()).toList(), mode));
        return source.getServices().values().stream().allMatch(s -> access.canServe(s.getServiceLinkId()));
    }
}
