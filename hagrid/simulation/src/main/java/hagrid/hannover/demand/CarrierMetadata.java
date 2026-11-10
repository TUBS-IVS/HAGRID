package hagrid.hannover.demand;

import org.matsim.api.core.v01.Id;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierService;

import java.util.*;

/** Derived delivery metadata. A missed parcel is an entry; repeated service IDs are intentional. */
public final class CarrierMetadata {
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();
    private CarrierMetadata() {}

    public static void rebuild(Carrier carrier, List<Id<CarrierService>> missedParcels) {
        var missed = new ArrayList<>(missedParcels);
        int parcels = validate(carrier.getServices(), missed);
        String encoded;
        try { encoded = JSON.writeValueAsString(missed.stream().map(Id::toString).toList()); }
        catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new IllegalStateException("Cannot encode missed parcel IDs", failure); }
        var attributes = carrier.getAttributes();
        attributes.putAttribute("numberOfParcels", parcels);
        attributes.putAttribute("missedParcels", missed.size());
        attributes.putAttribute("missedParcelsAsList", missed);
        attributes.putAttribute("missedParcelDeliveriesAsString", missed.toString());
        // Native MATSim does not write collections of Id objects; a String attribute survives XML readback.
        attributes.putAttribute("missedParcelDeliveriesJson", encoded);
        attributes.putAttribute("deliverySuccessRatePercent", parcels == 0 ? 100.0 : 100.0 * (parcels - missed.size()) / parcels);
    }

    static List<Id<CarrierService>> missedEntries(Carrier carrier) {
        Object value = carrier.getAttributes().getAttribute("missedParcelsAsList");
        if (value == null) {
            Object encoded = carrier.getAttributes().getAttribute("missedParcelDeliveriesJson");
            if (encoded != null) {
                try { value = Arrays.asList(JSON.readValue(encoded.toString(), String[].class)); }
                catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new IllegalArgumentException("Invalid missed parcel JSON for " + carrier.getId(), failure); }
            } else if (carrier.getAttributes().getAttribute("missedParcelDeliveriesAsString") instanceof String legacy && !legacy.isBlank()) {
                if (!legacy.startsWith("[") || !legacy.endsWith("]")) throw new IllegalArgumentException("Invalid legacy missed parcel IDs for " + carrier.getId());
                String content = legacy.substring(1, legacy.length() - 1);
                value = content.isEmpty() ? List.of() : Arrays.asList(content.split(",\\s*", -1));
            }
        }
        var entries = new ArrayList<Id<CarrierService>>();
        if (value != null) {
            if (!(value instanceof List<?> list)) throw new IllegalArgumentException("Invalid missed parcel list for " + carrier.getId());
            for (Object item : list) {
                Id<?> id = item instanceof Id<?> typed ? typed : item instanceof String text ? Id.create(text, CarrierService.class) : null;
                if (id == null || !carrier.getServices().containsKey(id)) {
                    throw new IllegalArgumentException("Missed parcel references unknown service in " + carrier.getId() + ": " + item);
                }
                @SuppressWarnings("unchecked") var typed = (Id<CarrierService>) id;
                entries.add(typed);
            }
        }
        Object count = carrier.getAttributes().getAttribute("missedParcels");
        if (count != null && (!(count instanceof Number number) || number.doubleValue() != entries.size())) {
            throw new IllegalArgumentException("Missed parcel count differs from its list for " + carrier.getId());
        }
        validate(carrier.getServices(), entries);
        return entries;
    }

    static int validate(Map<Id<CarrierService>, CarrierService> services, List<Id<CarrierService>> missed) {
        int parcels = 0;
        for (var service : services.values()) {
            if (service.getCapacityDemand() < 0) throw new IllegalArgumentException("Negative parcel demand: " + service.getId());
            parcels = Math.addExact(parcels, service.getCapacityDemand());
        }
        var byService = new HashMap<Id<CarrierService>, Integer>();
        for (var id : missed) {
            var service = services.get(id);
            if (service == null || byService.merge(id, 1, Math::addExact) > service.getCapacityDemand()) {
                throw new IllegalArgumentException("Missed parcel entries exceed service demand: " + id);
            }
        }
        return parcels;
    }
}
