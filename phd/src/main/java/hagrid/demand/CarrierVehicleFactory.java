package hagrid.demand;

import java.util.Random;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;

import org.matsim.freight.carriers.CarrierVehicle;
import org.matsim.freight.carriers.CarrierVehicleTypes;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;

public class CarrierVehicleFactory {

    private static final Random random = new Random();

    /**
     * Creates a CEP vehicle with specified parameters.
     *
     * @param homeId          The ID of the home link.
     * @param depot           The depot identifier.
     * @param vehicleTypes    The types of vehicles available for assignment.
     * @param startTime       The start time for the vehicle in hours.
     * @param maxRouteDuration The maximum route duration in seconds.
     * @param size            The size of the vehicle ("l" or "m").
     * @return The created CarrierVehicle instance.
     * @throws IllegalArgumentException If an unsupported vehicle size is provided.
     */
    public CarrierVehicle createCEPVehicle(Id<Link> homeId, String depot, CarrierVehicleTypes vehicleTypes, int startTime, int maxRouteDuration, String size) {
        String suffix = String.valueOf(startTime);

        // Determine vehicle type based on size
        VehicleType type = getVehicleType(vehicleTypes, size);

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
     * Determines the vehicle type based on the provided size.
     *
     * @param vehicleTypes The available vehicle types.
     * @param size         The size of the vehicle ("l" or "m").
     * @return The determined VehicleType.
     * @throws IllegalArgumentException If an unsupported vehicle size is provided.
     */
    private static VehicleType getVehicleType(CarrierVehicleTypes vehicleTypes, String size) {
        switch (size.toLowerCase()) {
            case "l":
                return vehicleTypes.getVehicleTypes().get(Id.create("ct_cep_size_l", VehicleType.class));
            case "m":
                return vehicleTypes.getVehicleTypes().get(Id.create("ct_cep_size_m", VehicleType.class));
            default:
                throw new IllegalArgumentException("Unsupported vehicle size: " + size);
        }
    }

    /**
     * Applies a time shift based on the vehicle size.
     *
     * @param size The size of the vehicle ("l" or "m").
     * @return The calculated time shift in minutes.
     */
    private static double getTimeShift(String size) {
        return size.equalsIgnoreCase("l") ? random.nextGaussian() * 5 : random.nextGaussian() * 15;
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
     * Calculates the end time with max route duration and buffer, capped at 21:00:00.
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
