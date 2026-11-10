package hagrid.core.simulation;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.freight.carriers.*;

import java.util.*;

/** Preserves input carrier objects and attributes; it does not invent routing settings. */
public final class CarrierInputMerger {
    private CarrierInputMerger() {}

    /** The native XML reader inserts directly into this map; reject duplicate IDs there too. */
    static Carriers newInputCollection() {
        var registry = new LinkedHashMap<Id<Carrier>, Carrier>() {
            @Override public Carrier put(Id<Carrier> id, Carrier carrier) {
                validateEntry(id, carrier);
                if (containsKey(id)) throw duplicate(id);
                return super.put(id, carrier);
            }
            @Override public void putAll(Map<? extends Id<Carrier>, ? extends Carrier> values) {
                values.forEach(this::put);
            }
        };
        return new Carriers() {
            @Override public Map<Id<Carrier>, Carrier> getCarriers() { return registry; }
            @Override public void addCarrier(Carrier carrier) { registry.put(carrier.getId(), carrier); }
        };
    }

    public static Carriers merge(Carriers... inputs) {
        var registry = new LinkedHashMap<Id<Carrier>, Carrier>();
        for (var input : inputs) {
            Objects.requireNonNull(input, "carrier input");
            for (var entry : input.getCarriers().entrySet()) {
                validateEntry(entry.getKey(), entry.getValue());
                if (registry.putIfAbsent(entry.getKey(), entry.getValue()) != null) throw duplicate(entry.getKey());
            }
        }
        return new Carriers(registry.values());
    }

    /** Registers already parsed objects through the native public API, without an XML round trip. */
    static void register(Scenario scenario, Carriers carriers, CarrierVehicleTypes types) {
        Objects.requireNonNull(scenario, "scenario");
        Objects.requireNonNull(carriers, "carriers");
        Objects.requireNonNull(types, "types");
        var targetCarriers = CarriersUtils.addOrGetCarriers(scenario);
        var targetTypes = CarriersUtils.getOrAddCarrierVehicleTypes(scenario);
        if (!targetCarriers.getCarriers().isEmpty() || !targetTypes.getVehicleTypes().isEmpty()) {
            throw new IllegalArgumentException("Scenario already contains carrier inputs; refusing to overwrite them");
        }
        targetTypes.getVehicleTypes().putAll(types.getVehicleTypes());
        targetCarriers.getCarriers().putAll(carriers.getCarriers());
    }

    private static void validateEntry(Id<Carrier> id, Carrier carrier) {
        if (carrier == null || id == null || !id.equals(carrier.getId())) {
            throw new IllegalArgumentException("Carrier input map key must match its carrier ID");
        }
    }

    private static IllegalArgumentException duplicate(Id<Carrier> id) {
        return new IllegalArgumentException("Duplicate carrier ID in input: " + id);
    }
}

