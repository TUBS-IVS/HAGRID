package hagrid.demand;

import java.util.Random;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.freight.carriers.CarrierVehicle;
import org.matsim.freight.carriers.CarrierVehicleTypes;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;

public class CarrierVehicleFactory {

    private static CarrierVehicleTypes vehicleTypes;

    CarrierVehicleFactory(CarrierVehicleTypes vehicleTypes) {
        this.vehicleTypes = vehicleTypes;
    }

    private static final Random random = new Random();

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
        VehicleType type = getVehicleType(size);

        // Create vehicle ID based on size and start time suffix
        CarrierVehicle.Builder vBuilder = CarrierVehicle.Builder
                .newInstance(Id.create("cep_size_" + size + "_" + suffix, Vehicle.class), homeId, type);

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

        // Create the vehicle builder
        CarrierVehicle.Builder vBuilder = CarrierVehicle.Builder.newInstance(vehicleId, homeId, vehicleType);

        double timeShift = getTimeShift(early ? "supply_early" : "supply_late");
        int start = (int) ((early ? 6 * 60 * 60 : 20 * 60 * 60) + timeShift);
        vBuilder.setEarliestStart(start);
        vBuilder.setLatestEnd(early ? 27900 : 24 * 3600);

        return vBuilder.build();
    }

    /**
     * Determines the vehicle type based on the provided size.
     *
     * @param vehicleTypes The available vehicle types.
     * @param size         The size of the vehicle ("l" or "m").
     * @return The determined VehicleType.
     * @throws IllegalArgumentException If an unsupported vehicle size is provided.
     */
    private static VehicleType getVehicleType(String size) {
        switch (size.toLowerCase()) {
            case "l":
                return vehicleTypes.getVehicleTypes().get(Id.create("ct_cep_size_l", VehicleType.class));
            case "m":
                return vehicleTypes.getVehicleTypes().get(Id.create("ct_cep_size_m", VehicleType.class));
            case "supply_early":
            case "supply_late":
                return vehicleTypes.getVehicleTypes().get(Id.create("ct_truck_heavy", VehicleType.class));
            default:
                throw new IllegalArgumentException("Unsupported vehicle size: " + size);
        }
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
                throw new IllegalArgumentException("Unsupported vehicle size: " + size);
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
