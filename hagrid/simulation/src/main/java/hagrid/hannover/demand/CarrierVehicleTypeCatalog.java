package hagrid.hannover.demand;

import hagrid.core.routing.CarrierFleetPreparation;
import org.matsim.api.core.v01.Id;
import org.matsim.freight.carriers.CarrierVehicleTypes;
import org.matsim.vehicles.CostInformation;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;
import org.matsim.utils.objectattributes.attributable.AttributesUtils;

import java.util.Objects;

/** Resolves XML aliases and custom freight capacities within one owning registry. */
final class CarrierVehicleTypeCatalog {
    private final CarrierVehicleTypes vehicleTypes;

    CarrierVehicleTypeCatalog(CarrierVehicleTypes vehicleTypes, double cargoBikeMaxSpeedMps) {
        this.vehicleTypes = Objects.requireNonNull(vehicleTypes, "vehicle types");
        CarrierFleetPreparation.applySpeedLimits(vehicleTypes, cargoBikeMaxSpeedMps);
    }

    VehicleType resolve(String size) {
        if (size == null || size.trim().isEmpty()) {
            throw new IllegalArgumentException("Vehicle size string is null or empty.");
        }

        String trimmed = size.trim().toLowerCase();

        // Handle known aliases first - use the XML types directly (with original capacity)
        switch (trimmed) {
            case "m":
                VehicleType typeM = vehicleTypes.getVehicleTypes().get(Id.create("ct_cep_size_m", VehicleType.class));
                if (typeM == null) {
                    throw new IllegalStateException("Vehicle type 'ct_cep_size_m' not found in vehicleTypes.");
                }
                return typeM;
            case "l":
                VehicleType typeL = vehicleTypes.getVehicleTypes().get(Id.create("ct_cep_size_l", VehicleType.class));
                if (typeL == null) {
                    throw new IllegalStateException("Vehicle type 'ct_cep_size_l' not found in vehicleTypes.");
                }
                return typeL;
            case "bike":
                VehicleType typeBike = vehicleTypes.getVehicleTypes().get(Id.create("ct_cep_bike", VehicleType.class));
                if (typeBike == null) {
                    throw new IllegalStateException("Vehicle type 'ct_cep_bike' not found in vehicleTypes.");
                }
                return typeBike;
            case "supply_early":
            case "supply_late":
                return vehicleTypes.getVehicleTypes().get(Id.create("ct_truck_heavy", VehicleType.class));
        }

        // Check for "capacity_type" format (e.g., "60_m", "100_l", "50_bike")
        if (trimmed.contains("_")) {
            String[] parts = trimmed.split("_", 2);
            Integer capacity = tryParsePositiveInt(parts[0]);
            if (capacity != null && parts.length == 2) {
                String baseAlias = parts[1];
                if (isValidBaseAlias(baseAlias)) {
                    return createCustomCapacityType(capacity, baseAlias);
                }
            }
            throw new IllegalArgumentException("Invalid format: '" + size +
                    "'. Expected 'capacity_type' (e.g., '60_m', '100_l', '50_bike').");
        }

        // Try parsing as numeric capacity only (auto-select base type)
        Integer capacity = tryParsePositiveInt(trimmed);
        if (capacity != null) {
            // Auto-determine base type: use "m" for smaller capacities, "l" for larger
            String baseAlias = (capacity <= 165) ? "m" : "l";
            return createCustomCapacityType(capacity, baseAlias);
        }

        throw new IllegalArgumentException("Unsupported vehicle size: '" + size +
                "'. Use 'm', 'l', 'bike', 'capacity_type' (e.g., '60_m'), or a numeric capacity.");
    }

    /**
     * Checks if the given alias is a valid base type for custom capacity vehicles.
     */
    private static boolean isValidBaseAlias(String alias) {
        return "m".equals(alias) || "l".equals(alias) || "bike".equals(alias);
    }

    /**
     * Creates a custom vehicle type with the specified capacity, based on an existing type template.
     * <p>
     * The new type ID follows the pattern: {@code ct_cep_[capacity]_[baseAlias]}
     * (e.g., "ct_cep_60_m", "ct_cep_100_l", "ct_cep_50_bike")
     * </p>
     *
     * @param capacity the desired capacity
     * @param baseAlias the base type alias ("m", "l", or "bike")
     * @return a new VehicleType with the custom capacity and template properties
     */
    private VehicleType createCustomCapacityType(int capacity, String baseAlias) {
        // Map alias to XML type ID
        String baseTypeId;
        switch (baseAlias.toLowerCase()) {
            case "m":
                baseTypeId = "ct_cep_size_m";
                break;
            case "l":
                baseTypeId = "ct_cep_size_l";
                break;
            case "bike":
                baseTypeId = "ct_cep_bike";
                break;
            default:
                throw new IllegalArgumentException("Unknown base alias: '" + baseAlias +
                        "'. Use 'm', 'l', or 'bike'.");
        }

        VehicleType baseType = vehicleTypes.getVehicleTypes().get(Id.create(baseTypeId, VehicleType.class));
        if (baseType == null) {
            throw new IllegalStateException("Base vehicle type '" + baseTypeId + "' not found in vehicleTypes.");
        }

        // Create unique ID: ct_cep_[capacity]_[baseAlias] (e.g., ct_cep_60_m)
        Id<VehicleType> newTypeId = Id.create("ct_cep_" + capacity + "_" + baseAlias, VehicleType.class);

        // Check if we already created this type
        VehicleType existingType = vehicleTypes.getVehicleTypes().get(newTypeId);
        if (existingType != null) {
            return existingType;
        }

        // Create new type with custom capacity
        VehicleType newType = VehicleUtils.getFactory().createVehicleType(newTypeId);
        copyVehicleTypeAttributes(baseType, newType);
        newType.getCapacity().setOther((double) capacity);
        vehicleTypes.getVehicleTypes().put(newTypeId, newType);

        return newType;
    }

    private static Integer tryParsePositiveInt(String value) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed <= 0) {
                throw new IllegalArgumentException("Capacity must be positive but was: " + parsed);
            }
            return parsed;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void copyVehicleTypeAttributes(VehicleType from, VehicleType to) {
        // Basic properties
        CarrierFleetPreparation.validateRoutingType(from);
        to.setNetworkMode(from.getNetworkMode());
        to.setDescription(from.getDescription());
        to.setMaximumVelocity(from.getMaximumVelocity());

        // Physical dimensions
        to.setLength(from.getLength());
        to.setWidth(from.getWidth());

        // Traffic flow properties
        to.setPcuEquivalents(from.getPcuEquivalents());
        to.setFlowEfficiencyFactor(from.getFlowEfficiencyFactor());

        // Copy all free-form attributes (e.g. "skills")
        AttributesUtils.copyAttributesFromTo(from, to);

        // Cost information (standard fields + nested attributes like costsPerSecondWaiting)
        CostInformation fromCost = from.getCostInformation();
        CostInformation toCost = to.getCostInformation();
        if (fromCost != null && toCost != null) {
            toCost.setFixedCost(fromCost.getFixedCosts());
            toCost.setCostsPerMeter(fromCost.getCostsPerMeter());
            toCost.setCostsPerSecond(fromCost.getCostsPerSecond());
            // Copy extended cost attributes (costsPerSecondInService, costsPerSecondWaiting)
            AttributesUtils.copyAttributesFromTo(fromCost, toCost);
        }

        // Capacity (seats, standing room — other/freight capacity is set separately)
        to.getCapacity().setSeats(from.getCapacity().getSeats());
        to.getCapacity().setStandingRoom(from.getCapacity().getStandingRoom());
        to.getCapacity().setWeightInTons(from.getCapacity().getWeightInTons());
        to.getCapacity().setVolumeInCubicMeters(from.getCapacity().getVolumeInCubicMeters());
        AttributesUtils.copyAttributesFromTo(from.getCapacity(), to.getCapacity());
        AttributesUtils.copyAttributesFromTo(from.getEngineInformation(), to.getEngineInformation());
    }

}
