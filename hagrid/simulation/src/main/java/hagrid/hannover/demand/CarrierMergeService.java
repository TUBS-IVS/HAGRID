package hagrid.hannover.demand;

import org.matsim.api.core.v01.Id;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierService;

import java.util.*;

/** Transfers generated delivery jobs atomically; selection, hub identity and fleet stay with the caller. */
public final class CarrierMergeService {
    public void merge(Carrier target, Carrier source) {
        Objects.requireNonNull(target); Objects.requireNonNull(source);
        if (target == source || target.getId().equals(source.getId())) throw new IllegalArgumentException("Cannot merge a carrier into itself");
        if (!target.getShipments().isEmpty() || !source.getShipments().isEmpty()) {
            throw new IllegalArgumentException("Delivery service merge cannot discard shipment metadata");
        }
        if (!target.getPlans().isEmpty() || !source.getPlans().isEmpty()) {
            throw new IllegalArgumentException("Carrier merge must precede route planning");
        }
        var combined = new LinkedHashMap<>(target.getServices());
        for (var entry : source.getServices().entrySet()) {
            if (combined.putIfAbsent(entry.getKey(), entry.getValue()) != null) {
                throw new IllegalArgumentException("Duplicate service ID in carrier merge: " + entry.getKey());
            }
        }
        var missed = new ArrayList<>(CarrierMetadata.missedEntries(target));
        missed.addAll(CarrierMetadata.missedEntries(source));
        CarrierMetadata.validate(combined, missed);
        var targetBefore = State.capture(target);
        var sourceBefore = State.capture(source);
        try {
            sourceBefore.services().forEach(target.getServices()::put);
            CarrierMetadata.rebuild(target, missed);
            source.getServices().clear();
            CarrierMetadata.rebuild(source, List.of());
        } catch (RuntimeException | Error failure) {
            for (var saved : List.of(targetBefore, sourceBefore)) {
                try { saved.restore(); }
                catch (RuntimeException | Error recovery) { failure.addSuppressed(recovery); }
            }
            throw failure;
        }
    }

    record State(Carrier carrier, Map<Id<CarrierService>, CarrierService> services, Map<String, Object> attributes) {
        static State capture(Carrier carrier) {
            return new State(carrier, new LinkedHashMap<>(carrier.getServices()), new LinkedHashMap<>(carrier.getAttributes().getAsMap()));
        }
        void restore() {
            carrier.getServices().clear(); carrier.getServices().putAll(services);
            var current = carrier.getAttributes();
            for (var key : new ArrayList<>(current.getAsMap().keySet())) current.removeAttribute(key);
            attributes.forEach(current::putAttribute);
        }
    }
}
