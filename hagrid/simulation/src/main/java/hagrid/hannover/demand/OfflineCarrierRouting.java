package hagrid.hannover.demand;

import hagrid.core.routing.NativeModalTransportCosts;
import hagrid.core.simulation.CarrierInputMerger;
import hagrid.core.simulation.CarrierInputValidator;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Network;
import org.matsim.freight.carriers.*;
import org.matsim.freight.carriers.jsprit.VRPTransportCosts;
import java.util.LinkedHashMap;
import java.util.Map;

/** Validate both inputs before routing; share one provider per used mode for this run. */
final class OfflineCarrierRouting {
    private final Map<Id<Carrier>, String> modes;
    private final Map<String, VRPTransportCosts> costs;

    private OfflineCarrierRouting(Map<Id<Carrier>, String> modes, Map<String, VRPTransportCosts> costs) {
        this.modes = modes;
        this.costs = costs;
    }

    static OfflineCarrierRouting prepare(Carriers delivery, Carriers supply, CarrierVehicleTypes types, Network network) {
        Carriers merged = CarrierInputMerger.merge(delivery, supply);
        Map<Id<Carrier>, String> modes = CarrierInputValidator.validate(merged, types, network);
        var costs = new LinkedHashMap<String, VRPTransportCosts>();
        for (Carrier carrier : merged.getCarriers().values()) {
            String mode = modes.get(carrier.getId());
            if (mode != null && !carrier.getCarrierCapabilities().getCarrierVehicles().isEmpty()) {
                costs.computeIfAbsent(mode, key -> NativeModalTransportCosts.create(network, types, key));
            }
        }
        // Publish inferred metadata only after every input/provider has been prepared.
        modes.forEach((id, mode) -> CarriersUtils.setCarrierMode(merged.getCarriers().get(id), mode));
        return new OfflineCarrierRouting(modes, costs);
    }

    void route(Router router, Carriers carriers, String role) {
        var groups = new LinkedHashMap<String, Carriers>();
        for (Carrier carrier : carriers.getCarriers().values()) {
            String mode = modes.get(carrier.getId());
            if (mode == null || carrier.getCarrierCapabilities().getCarrierVehicles().isEmpty()) continue;
            groups.computeIfAbsent(mode, key -> new Carriers()).addCarrier(carrier);
        }
        groups.forEach((mode, group) -> {
            var provider = costs.get(mode);
            router.routeCarriers(group, provider, provider.getNetwork(), role);
        });
    }
}
