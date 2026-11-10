package hagrid.hannover.demand;

import hagrid.core.HagridConfig;
import hagrid.core.config.ResolvedHagridConfig;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.freight.carriers.CarrierVehicle;
import org.matsim.freight.carriers.CarrierVehicleTypes;
import org.matsim.freight.carriers.CarriersUtils;
import org.matsim.vehicles.Vehicle;

import java.util.Objects;
import java.util.Random;

/** Creates native freight vehicles; type/profile resolution belongs to the per-factory catalog. */
public class CarrierVehicleFactory {
    private final CarrierVehicleTypeCatalog types;
    private static volatile long legacySeed = 42L;
    private java.util.random.RandomGenerator random = new Random(legacySeed);
    private final double availabilityBufferSeconds;
    private final double vehicleDayEndSeconds;
    private final hagrid.core.config.VehicleDispatchSettings dispatch;

    /** Compatibility constructor uses the shared HAGRID default bike cap. */
    CarrierVehicleFactory(CarrierVehicleTypes vehicleTypes) {
        this(vehicleTypes,new HagridConfig.VehicleConfig().getCargoBikeMaxSpeedKmh()/3.6,new HagridConfig().routing());
    }

    CarrierVehicleFactory(CarrierVehicleTypes vehicleTypes, ResolvedHagridConfig.Fleet fleet) {
        this(vehicleTypes,fleet,new HagridConfig().routing());
    }
    CarrierVehicleFactory(CarrierVehicleTypes types,ResolvedHagridConfig.Fleet fleet,HagridConfig.RoutingConfig rules) {
        this(types,Objects.requireNonNull(fleet,"fleet").cargoBikeMaxSpeedMps(),rules);
    }
    CarrierVehicleFactory(CarrierVehicleTypes vehicleTypes, ResolvedHagridConfig.Fleet fleet, HagridConfig config) {
        this(vehicleTypes, fleet.cargoBikeMaxSpeedMps(), config.routing(), config.dispatch());
    }
    CarrierVehicleFactory(CarrierVehicleTypes vehicleTypes, ResolvedHagridConfig.Fleet fleet, HagridConfig config,
            java.util.random.RandomGenerator random) {
        this(vehicleTypes, fleet, config);
        this.random = Objects.requireNonNull(random, "random");
    }
    private CarrierVehicleFactory(CarrierVehicleTypes vehicleTypes,double bikeSpeed,HagridConfig.RoutingConfig rules) {
        this(vehicleTypes, bikeSpeed, rules, hagrid.core.config.VehicleDispatchSettings.defaults());
    }
    private CarrierVehicleFactory(CarrierVehicleTypes vehicleTypes,double bikeSpeed,HagridConfig.RoutingConfig rules,
            hagrid.core.config.VehicleDispatchSettings dispatch) {
        types=new CarrierVehicleTypeCatalog(vehicleTypes,bikeSpeed);
        this.dispatch=dispatch;
        availabilityBufferSeconds=rules.getVehicleAvailabilityBufferSeconds();
        vehicleDayEndSeconds=rules.getVehicleDayEndSeconds();
    }

    /** Compatibility only: seeds future legacy factories, never a live instance. */
    @Deprecated
    public static void setGlobalRandomSeed(long seed) {
        legacySeed = seed;
    }

    /** Resolves a single exact mode before vehicle creation or dispatch RNG consumption. */
    String networkModeFor(java.util.Collection<String> sizes) {
        var modes = new java.util.TreeSet<String>();
        for (String size : sizes) modes.add(types.resolve(size).getNetworkMode());
        if (modes.size() != 1) {
            throw new IllegalArgumentException("Generated fleet requires one mode; mixed or empty modes: " + modes);
        }
        return modes.first();
    }

    public CarrierVehicle createCEPVehicle(Id<Link> homeId, String depot,
                                          int startTime, int maxRouteDuration, String size) {
        return createCEPVehicle(homeId, depot, startTime, maxRouteDuration, size, random);
    }

    CarrierVehicle createCEPVehicle(Id<Link> homeId, String depot, int startTime, int maxRouteDuration, String size,
            java.util.random.RandomGenerator random) {
        var type = types.resolve(size);
        CarriersUtils.addSkill(type, "conventional");
        double start = startTime * 3600.0 + getTimeShift(size, random) * 60;
        var vehicle = CarrierVehicle.Builder.newInstance(
                Id.create("cep_size_" + size + "_" + startTime, Vehicle.class), homeId, type);
        vehicle.setEarliestStart(start);
        vehicle.setLatestEnd(Math.min(start + maxRouteDuration + availabilityBufferSeconds, vehicleDayEndSeconds));
        return vehicle.build();
    }

    public CarrierVehicle createSupplyVehicle(String carrierId, Id<Link> homeId, boolean early) {
        return createSupplyVehicle(carrierId, homeId, early, random);
    }

    CarrierVehicle createSupplyVehicle(String carrierId, Id<Link> homeId, boolean early,
            java.util.random.RandomGenerator random) {
        String size = early ? "supply_early" : "supply_late";
        var type = types.resolve(size);
        CarriersUtils.addSkill(type, "supply");
        var vehicle = CarrierVehicle.Builder.newInstance(Id.create(carrierId + "_" + size, Vehicle.class), homeId, type);
        // Preserve the existing native supply dispatch rounding and absolute end times.
        int start = (int) ((early ? dispatch.earlyStartSeconds() : dispatch.lateStartSeconds()) + getTimeShift(size, random));
        vehicle.setEarliestStart(start);
        vehicle.setLatestEnd(early ? dispatch.earlyEndSeconds() : dispatch.lateEndSeconds());
        return vehicle.build();
    }

    private double getTimeShift(String size, java.util.random.RandomGenerator random) {
        return switch (size.toLowerCase()) {
            case "l" -> random.nextGaussian() * dispatch.largeJitterMinutes();
            case "supply_early" -> random.nextGaussian() * dispatch.earlyJitterSeconds();
            case "supply_late" -> random.nextGaussian() * dispatch.lateJitterSeconds();
            default -> random.nextGaussian() * dispatch.defaultJitterMinutes();
        };
    }
}
