package hagrid.core.routing;

import org.matsim.freight.carriers.CarrierVehicleTypes;
import org.matsim.vehicles.VehicleType;

import java.util.Objects;

/** Apply the configured bike cap once before native vehicles and routing contexts are prepared. */
public final class CarrierFleetPreparation {
    private CarrierFleetPreparation() {}

    /** Native velocity is in m/s. A slower XML limit always remains authoritative. */
    public static void applySpeedLimits(CarrierVehicleTypes types, double cargoBikeMaxSpeedMps) {
        Objects.requireNonNull(types, "vehicle types");
        if (!Double.isFinite(cargoBikeMaxSpeedMps) || cargoBikeMaxSpeedMps <= 0) {
            throw new IllegalArgumentException("cargoBikeMaxSpeedMps must be finite and positive");
        }
        // Validate the complete registry before applying any changes.
        types.getVehicleTypes().values().forEach(CarrierFleetPreparation::validateRoutingType);
        for (var type : types.getVehicleTypes().values()) {
            if (type.getNetworkMode().equals("bike") || type.getNetworkMode().equals("cargobike")) {
                type.setMaximumVelocity(Math.min(type.getMaximumVelocity(), cargoBikeMaxSpeedMps));
            }
        }
    }

    public static void validateRoutingType(VehicleType type) {
        Objects.requireNonNull(type, "vehicle type");
        if (!type.hasNetworkMode() || type.getNetworkMode() == null || type.getNetworkMode().isBlank()) {
            throw new IllegalArgumentException("Vehicle type " + type.getId() + " needs an explicit network mode");
        }
        if (Double.isNaN(type.getMaximumVelocity()) || type.getMaximumVelocity() <= 0) {
            throw new IllegalArgumentException("Invalid maximum velocity for vehicle type " + type.getId());
        }
    }
}
