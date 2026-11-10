package hagrid.core.routing;

import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierVehicle;
import org.matsim.freight.carriers.CarriersUtils;
import org.matsim.vehicles.VehicleType;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;

/** One exact mode per carrier. Resolving imported metadata never mutates the input. */
public final class CarrierModeResolver {
    private CarrierModeResolver() {}

    public static Optional<String> resolve(Carrier carrier) {
        Objects.requireNonNull(carrier, "carrier");
        var capabilities = carrier.getCarrierCapabilities();
        if (capabilities == null) throw invalid(carrier, "missing vehicle capabilities");
        var modes = new TreeSet<String>();
        for (VehicleType type : capabilities.getVehicleTypes()) modes.add(modeOf(carrier, type));
        for (CarrierVehicle vehicle : capabilities.getCarrierVehicles().values()) {
            if (vehicle == null) throw invalid(carrier, "missing vehicle");
            modes.add(modeOf(carrier, vehicle.getType()));
        }
        if (modes.size() > 1) throw invalid(carrier, "has unsupported mixed network modes " + modes);
        Object recorded = carrier.getAttributes().getAttribute("carrierMode");
        if (recorded != null && (!(recorded instanceof String) || ((String) recorded).isBlank())) {
            throw invalid(carrier, "carrierMode must be a nonblank mode name");
        }
        String mode = (String) recorded;
        if (!modes.isEmpty()) {
            String vehicleMode = modes.first();
            if (mode != null && !mode.equals(vehicleMode)) {
                throw invalid(carrier, "carrier mode " + mode + " disagrees with vehicle mode " + vehicleMode);
            }
            mode = vehicleMode;
        }
        return Optional.ofNullable(mode);
    }

    /** Called after generation has finished assigning the carrier's fleet. */
    public static void assign(Carrier carrier) {
        resolve(carrier).ifPresent(mode -> CarriersUtils.setCarrierMode(carrier, mode));
    }

    private static String modeOf(Carrier carrier, VehicleType type) {
        if (type == null || !type.hasNetworkMode() || type.getNetworkMode() == null || type.getNetworkMode().isBlank()) {
            throw invalid(carrier, "vehicle type " + (type == null ? "<missing>" : type.getId()) + " needs an explicit network mode");
        }
        return type.getNetworkMode();
    }

    private static IllegalArgumentException invalid(Carrier carrier, String reason) {
        return new IllegalArgumentException("Invalid carrier " + carrier.getId() + ": " + reason);
    }
}
