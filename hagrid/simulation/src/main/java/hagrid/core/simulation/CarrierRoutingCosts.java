package hagrid.core.simulation;

import hagrid.core.routing.ReplanningTransportCosts;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.router.util.TravelTime;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierVehicleTypes;
import org.matsim.freight.carriers.CarriersUtils;
import org.matsim.freight.carriers.jsprit.VRPTransportCosts;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Exact mode lookup and the sole compatibility bridge to live legacy cost providers. */
final class CarrierRoutingCosts {
    private final Map<String, VRPTransportCosts> registered;
    private final Function<String, VRPTransportCosts> fallbackFactory;
    private final Map<String, VRPTransportCosts> fallback = new HashMap<>();

    CarrierRoutingCosts(Map<String, VRPTransportCosts> registered) {
        this(registered, mode -> null);
    }

    private CarrierRoutingCosts(Map<String, VRPTransportCosts> registered,
            Function<String, VRPTransportCosts> fallbackFactory) {
        this.registered = Map.copyOf(registered);
        this.fallbackFactory = fallbackFactory;
    }

    static CarrierRoutingCosts withLegacyFallback(Map<String, VRPTransportCosts> registered,
            Network car, Network bike, CarrierVehicleTypes types, TravelTime times) {
        return new CarrierRoutingCosts(registered, mode -> createNativeFallback(mode, car, bike, types, times));
    }

    synchronized void prepare() {
        Set<VRPTransportCosts> distinct = Collections.newSetFromMap(new IdentityHashMap<>());
        for (var costs : registered.values()) {
            if (distinct.add(costs) && costs instanceof ReplanningTransportCosts provider) provider.prepareForReplanning();
        }
        // Each required mode builds its graph once per preparation; unused modes allocate no graph.
        fallback.clear();
    }

    VRPTransportCosts forCarrier(Carrier carrier) {
        String mode = CarriersUtils.getCarrierMode(carrier);
        var costs = registered.isEmpty() ? fallback(mode) : registered.get(mode);
        if (costs == null) {
            throw new IllegalArgumentException("No transport costs registered for mode " + mode
                    + " (carrier " + carrier.getId() + ")");
        }
        return costs;
    }

    private synchronized VRPTransportCosts fallback(String mode) {
        return fallback.computeIfAbsent(mode, fallbackFactory);
    }

    static void logSummary(VRPTransportCosts costs, Carrier carrier) {
        if (costs instanceof ReplanningTransportCosts provider) {
            provider.logSummary("Carrier " + carrier.getId() + " (" + CarriersUtils.getCarrierMode(carrier) + ") reroute");
        }
    }

    private static VRPTransportCosts createNativeFallback(String mode, Network car, Network bike,
            CarrierVehicleTypes vehicleTypes, TravelTime times) {
        boolean needed = vehicleTypes.getVehicleTypes().values().stream()
                .anyMatch(type -> type.hasNetworkMode() && mode.equals(type.getNetworkMode()));
        if (!needed) return null;
        Network source = ("bike".equals(mode) || "cargobike".equals(mode)) ? bike : car;
        return hagrid.core.routing.NativeModalTransportCosts.create(source, vehicleTypes, mode, times);
    }
}
