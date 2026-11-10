package hagrid.core.routing;

import org.matsim.api.core.v01.Id;
import org.matsim.freight.carriers.CarrierVehicleTypeReader;
import org.matsim.freight.carriers.CarrierVehicleTypes;
import org.matsim.vehicles.VehicleType;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** One native parse into a local registry; duplicate definitions never overwrite a type. */
public final class CarrierVehicleTypeInputs {
    private CarrierVehicleTypeInputs() {}

    public static CarrierVehicleTypes read(Path file) {
        Objects.requireNonNull(file, "vehicle type file");
        CarrierVehicleTypes types = newInputCollection();
        new CarrierVehicleTypeReader(types).readFile(file.toAbsolutePath().toString());
        return types;
    }

    static CarrierVehicleTypes newInputCollection() {
        var registry = new LinkedHashMap<Id<VehicleType>, VehicleType>() {
            @Override public VehicleType put(Id<VehicleType> id, VehicleType type) {
                if (id == null || type == null || !id.equals(type.getId())) {
                    throw new IllegalArgumentException("Vehicle type registry key must match its type ID");
                }
                if (containsKey(id)) throw new IllegalArgumentException("Duplicate vehicle type ID in input: " + id);
                return super.put(id, type);
            }
            @Override public void putAll(Map<? extends Id<VehicleType>, ? extends VehicleType> entries) {
                entries.forEach(this::put);
            }
        };
        return new CarrierVehicleTypes() {
            @Override public Map<Id<VehicleType>, VehicleType> getVehicleTypes() { return registry; }
        };
    }
}
