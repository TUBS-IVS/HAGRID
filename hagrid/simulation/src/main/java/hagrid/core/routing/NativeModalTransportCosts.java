package hagrid.core.routing;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.router.util.TravelTime;
import org.matsim.freight.carriers.CarrierVehicleTypes;
import org.matsim.freight.carriers.jsprit.NetworkBasedTransportCosts;
import org.matsim.freight.carriers.jsprit.VRPTransportCosts;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Native compatibility provider, bound to one exact mode and one preparation.
 * Its bounded native caches are independent of the snapshot engine.
 */
public final class NativeModalTransportCosts {
    private NativeModalTransportCosts() {}

    public static boolean isCertifiedStatic(VRPTransportCosts costs) {
        return costs instanceof NativeLinkTransportCosts nativeCosts && nativeCosts.certifiedConstantProfile();
    }

    public static VRPTransportCosts create(Network source, CarrierVehicleTypes registry, String mode) {
        return create(source, registry, mode, (link, time, person, vehicle) -> {
            double speed = Math.min(vehicle.getType().getMaximumVelocity(), link.getFreespeed(time));
            if (!(speed > 0)) throw new IllegalArgumentException("Invalid routing speed on link " + link.getId());
            return link.getLength() / speed;
        }, !(source instanceof org.matsim.core.network.TimeDependentNetwork dynamic) || dynamic.getNetworkChangeEvents().isEmpty());
    }

    public static VRPTransportCosts create(Network source, CarrierVehicleTypes registry, String mode, TravelTime times) {
        return create(source,registry,mode,times,false);
    }

    private static VRPTransportCosts create(Network source, CarrierVehicleTypes registry, String mode, TravelTime times, boolean constantProfile) {
        Objects.requireNonNull(source, "source network");
        Objects.requireNonNull(registry, "vehicle types");
        Objects.requireNonNull(times, "travel time");
        if (mode == null || mode.isBlank()) throw new IllegalArgumentException("Transport costs require an explicit mode");
        var types = registry.getVehicleTypes().values().stream().peek(CarrierFleetPreparation::validateRoutingType)
                .filter(type -> mode.equals(type.getNetworkMode())).toList();
        if (types.isEmpty()) throw new IllegalArgumentException("No vehicle types registered for mode " + mode);
        Network filtered = ModalRoutingNetwork.forMode(source, mode);
        if (filtered.getLinks().isEmpty()) throw new IllegalArgumentException("No network links permit mode " + mode);

        // Native jsprit wrappers omit networkMode, including the neutral regret-scoring
        // vehicle. Restore registered profiles once per wrapper, rather than per link query.
        var neutralType = VehicleUtils.createVehicleType(Id.create("__hagrid_neutral_" + mode, VehicleType.class), mode);
        var neutral = VehicleUtils.createVehicle(Id.createVehicleId("__hagrid_neutral_" + mode), neutralType);
        var adapters = new ConcurrentHashMap<Vehicle, Vehicle>();
        TravelTime modalTime = (link, time, person, vehicle) -> {
            if (!link.getAllowedModes().contains(mode)) {
                throw new IllegalArgumentException("Link " + link.getId() + " does not allow mode " + mode);
            }
            Vehicle adapted = vehicle;
            if (vehicle == null) adapted = neutral;
            else if (!vehicle.getType().hasNetworkMode()) {
                adapted = adapters.computeIfAbsent(vehicle, wrapper -> {
                    VehicleType type = registry.getVehicleTypes().get(wrapper.getType().getId());
                    if (type == null) return neutral;
                    if (!mode.equals(type.getNetworkMode())) {
                        throw new IllegalArgumentException("Vehicle type " + type.getId() + " disagrees with mode " + mode);
                    }
                    return VehicleUtils.createVehicle(wrapper.getId(), type);
                });
            } else if (!mode.equals(vehicle.getType().getNetworkMode())) {
                throw new IllegalArgumentException("Vehicle mode " + vehicle.getType().getNetworkMode() + " disagrees with mode " + mode);
            }
            return times.getLinkTravelTime(link, time, person, adapted);
        };
        var builder = NetworkBasedTransportCosts.Builder.newInstance(filtered, types);
        builder.setTravelTime(modalTime);
        // The default free-flow callback with no network events is constant during this
        // preparation. Repeating those values for every 30-minute bin wastes both
        // path calculations and RAM. Custom callbacks/event-bearing networks retain bins.
        // As with all memoization here, network and registered vehicle profiles must
        // remain unchanged for the lifetime of this provider.
        builder.setTimeSliceWidth(constantProfile ? Integer.MAX_VALUE : 1800);
        return new NativeLinkTransportCosts(builder.build(),registry,mode,constantProfile);
    }
}

