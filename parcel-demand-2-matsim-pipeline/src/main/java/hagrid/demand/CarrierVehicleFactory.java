package hagrid.demand;

import java.util.Collections;
import java.util.Random;
import java.util.Set;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.freight.carriers.CarrierVehicle;
import org.matsim.freight.carriers.CarrierVehicleType;
import org.matsim.freight.carriers.CarrierVehicleTypes;
import org.matsim.freight.carriers.CarriersUtils;
import org.matsim.vehicles.CostInformation;
import org.matsim.vehicles.EngineInformation;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;
import org.matsim.utils.objectattributes.attributable.AttributesUtils;

public class CarrierVehicleFactory {

    private static CarrierVehicleTypes vehicleTypes;

    CarrierVehicleFactory(CarrierVehicleTypes vehicleTypes) {
        this.vehicleTypes = vehicleTypes;
    }

    private static Random random = new Random();

    /**
     * Set the global random seed for deterministic behavior (should be called
     * before any random logic).
     * 
     * @param seed the seed to use (e.g. runId.hashCode())
     */
    public static void setGlobalRandomSeed(long seed) {
        random = new Random(seed);
    }

    /**
     * Creates a CEP vehicle with specified parameters.
     *
     * @param homeId           The ID of the home link.
     * @param depot            The depot identifier.
     * @param vehicleTypes     The types of vehicles available for assignment.
     * @param startTime        The start time for the vehicle in hours.
     * @param maxRouteDuration The maximum route duration in seconds.
     * @param size             The size of the vehicle ("l" or "m").
     * @return The created CarrierVehicle instance.
     * @throws IllegalArgumentException If an unsupported vehicle size is provided.
     */
    public CarrierVehicle createCEPVehicle(Id<Link> homeId, String depot,
            int startTime, int maxRouteDuration, String size) {
        String suffix = String.valueOf(startTime);

        // Determine vehicle type based on size
        VehicleType vehicleType = getVehicleType(size);
        vehicleType.setNetworkMode("car");
        // not nesscary to add skill - but i guess i keep it for now
        CarriersUtils.addSkill(vehicleType, "conventional");

        // Create vehicle ID based on size and start time suffix
        CarrierVehicle.Builder vBuilder = CarrierVehicle.Builder
                .newInstance(Id.create("cep_size_" + size + "_" + suffix, Vehicle.class), homeId, vehicleType);

        // Apply time shift based on size
        double timeShift = getTimeShift(size);
        double timeWithShift = calculateTimeWithShift(startTime, timeShift);
        vBuilder.setEarliestStart(timeWithShift);

        // Calculate end time with max route duration and 1-hour buffer
        double end = calculateEndTime(timeWithShift, maxRouteDuration);
        vBuilder.setLatestEnd(end);

        return vBuilder.build();
    }

    /**
     * Creates a supply vehicle with specified parameters.
     *
     * @param carrierId    The ID of the carrier.
     * @param homeId       The ID of the home link.
     * @param vehicleTypes The types of vehicles available for assignment.
     * @param early        Whether the vehicle is an early shift vehicle.
     * @return The created CarrierVehicle instance.
     */
    public CarrierVehicle createSupplyVehicle(String carrierId, Id<Link> homeId,
            boolean early) {
        // Generate a unique vehicle ID by appending "early" or "late"
        String vehicleIdSuffix = early ? "early" : "late";
        vehicleIdSuffix = "supply_" + vehicleIdSuffix;
        Id<Vehicle> vehicleId = Id.create(carrierId.toString() + "_" + vehicleIdSuffix, Vehicle.class);

        // Get the vehicle type
        VehicleType vehicleType = getVehicleType(vehicleIdSuffix);
        vehicleType.setNetworkMode("car");

        // not nesscary to add skill - but i guess i keep it for now
        CarriersUtils.addSkill(vehicleType, "supply");

        // Create the vehicle builder
        CarrierVehicle.Builder vBuilder = CarrierVehicle.Builder.newInstance(vehicleId, homeId, vehicleType);

        double timeShift = getTimeShift(early ? "supply_early" : "supply_late");
        int start = (int) ((early ? 6 * 60 * 60 : 20 * 60 * 60) + timeShift);
        vBuilder.setEarliestStart(start);
        vBuilder.setLatestEnd(early ? 27900 : 24 * 3600);

        return vBuilder.build();
    }

    // /**
    // * Determines the vehicle type based on the provided size.
    // *
    // * @param vehicleTypes The available vehicle types.
    // * @param size The size of the vehicle ("l" or "m").
    // * @return The determined VehicleType.
    // * @throws IllegalArgumentException If an unsupported vehicle size is
    // provided.
    // */
    // private static VehicleType getVehicleType(String size) {

    // switch (size.toLowerCase()) {
    // case "l":
    // return vehicleTypes.getVehicleTypes().get(Id.create("ct_cep_size_l",
    // VehicleType.class));
    // case "m":
    // return vehicleTypes.getVehicleTypes().get(Id.create("ct_cep_size_m",
    // VehicleType.class));
    // case "supply_early":
    // case "supply_late":
    // return vehicleTypes.getVehicleTypes().get(Id.create("ct_truck_heavy",
    // VehicleType.class));
    // default:
    // throw new IllegalArgumentException("Unsupported vehicle size: " + size);
    // }
    // }

    /**
     * Resolves a VehicleType from the given size identifier.
     * <p>
     * Supported formats:
     * <ul>
     *   <li><b>"m"</b> → uses {@code ct_cep_size_m} (capacity 165) with all costs from XML</li>
     *   <li><b>"l"</b> → uses {@code ct_cep_size_l} (capacity 230) with all costs from XML</li>
     *   <li><b>"bike"</b> → uses {@code ct_cep_bike} (capacity 23) with all costs from XML</li>
     *   <li><b>"capacity_type"</b> (e.g., "60_m", "100_l") → creates a new type with the
     *       specified capacity, using the given type (m/l/bike) as template for costs, speed, etc.</li>
     *   <li><b>Numeric only (e.g., "80")</b> → auto-selects template: "m" for ≤165, "l" for >165</li>
     * </ul>
     * </p>
     *
     * @param size the size identifier (alias, "capacity_type", or numeric capacity)
     * @return the resolved VehicleType
     * @throws IllegalArgumentException if the size is not recognized
     */
    private static VehicleType getVehicleType(String size) {
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
    private static VehicleType createCustomCapacityType(int capacity, String baseAlias) {
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
        to.setNetworkMode(from.getNetworkMode() != null ? from.getNetworkMode() : TransportMode.car);
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
    }

    /**
     * Applies a time shift based on the vehicle size.
     *
     * @param size The size of the vehicle ("l", "m", "supply_early",
     *             "supply_late").
     * @return The calculated time shift in minutes.
     */
    private static double getTimeShift(String size) {
        switch (size.toLowerCase()) {
            case "l":
                return random.nextGaussian() * 5;
            case "m":
                return random.nextGaussian() * 15;
            case "supply_early":
                return random.nextGaussian() * 10;
            case "supply_late":
                return random.nextGaussian() * 20;
            default:
                return random.nextGaussian() * 15;
        }
    }

    /**
     * Calculates the time with shift applied.
     *
     * @param startTime The start time in hours.
     * @param timeShift The time shift in minutes.
     * @return The calculated time with shift in seconds.
     */
    private static double calculateTimeWithShift(int startTime, double timeShift) {
        return (startTime * 60 * 60) + (timeShift * 60);
    }

    /**
     * Calculates the end time with max route duration and buffer, capped at
     * 21:00:00.
     *
     * @param timeWithShift    The start time with shift in seconds.
     * @param maxRouteDuration The maximum route duration in seconds.
     * @return The calculated end time in seconds.
     */
    private static double calculateEndTime(double timeWithShift, int maxRouteDuration) {
        double end = timeWithShift + maxRouteDuration + (1 * 60 * 60); // Adding 1-hour buffer
        return Math.min(end, 21 * 60 * 60); // Cap end time at 21:00:00
    }
}
