package hagrid.hannover.demand;

import hagrid.core.util.*;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.Id;
import org.matsim.core.scenario.MutableScenario;
import org.matsim.freight.carriers.CarrierVehicleTypes;
import org.matsim.vehicles.VehicleType;
import java.util.*;

/** Captures only caller-owned state that native carrier generation changes, without copying demand or networks. */
final class CarrierGenerationTransaction implements AutoCloseable {
    private record HubState(Hub hub, int demand, Map<String,Integer> providers) {}
    private record TypeState(VehicleType type, double speed, Map<String,Object> attributes) {}
    private final MutableScenario scenario;
    private final Map<String,Object> elements = new LinkedHashMap<>();
    private final List<HubState> hubs = new ArrayList<>();
    private final List<TypeState> types = new ArrayList<>();
    private final CarrierVehicleTypes registry;
    private final Map<Id<VehicleType>,VehicleType> originalTypes;
    private final HAGRIDSummary summary;
    private final int correction;
    private final int b2bCorrection;
    private boolean committed;

    CarrierGenerationTransaction(Scenario source, String role) {
        if (!(source instanceof MutableScenario mutable))
            throw new IllegalArgumentException("Carrier generation requires a mutable native scenario");
        scenario = mutable;
        for (String name : List.of(role, "carrierMergeLog")) elements.put(name, scenario.getScenarioElement(name));
        if (scenario.getScenarioElement("hubList") instanceof Map<?,?> all)
            for (Object value : all.values()) {
                if (!(value instanceof Hub hub)) throw new IllegalArgumentException("Invalid hub registry");
                hubs.add(new HubState(hub, hub.getAssignedSupplyDemand(), new LinkedHashMap<>(hub.getWLSupplyDemandByProvider())));
            }
        registry = scenario.getScenarioElement("carrierVehicleTypes") instanceof CarrierVehicleTypes value ? value : null;
        originalTypes = registry == null ? Map.of() : new LinkedHashMap<>(registry.getVehicleTypes());
        for (var type : originalTypes.values())
            types.add(new TypeState(type, type.getMaximumVelocity(), new LinkedHashMap<>(type.getAttributes().getAsMap())));
        summary = scenario.getScenarioElement("summary") instanceof HAGRIDSummary value ? value : null;
        correction = summary == null ? 0 : summary.getCorrectionFactor();
        b2bCorrection = summary == null ? 0 : summary.getB2bCorrectionFactor();
    }

    void commit() { committed = true; }
    @Override public void close() {
        if (committed) return;
        for (var saved : hubs) {
            saved.hub().setAssignedSupplyDemand(saved.demand());
            saved.hub().getWLSupplyDemandByProvider().clear();
            saved.hub().getWLSupplyDemandByProvider().putAll(saved.providers());
        }
        if (summary != null) {
            summary.setCorrectionFactor(correction);
            summary.setB2bCorrectionFactor(b2bCorrection);
        }
        if (registry != null) {
            registry.getVehicleTypes().clear(); registry.getVehicleTypes().putAll(originalTypes);
            for (var saved : types) {
                saved.type().setMaximumVelocity(saved.speed());
                for (String key : new ArrayList<>(saved.type().getAttributes().getAsMap().keySet()))
                    saved.type().getAttributes().removeAttribute(key);
                saved.attributes().forEach(saved.type().getAttributes()::putAttribute);
            }
        }
        elements.forEach((name, original) -> {
            if (scenario.getScenarioElement(name) != original) {
                scenario.removeScenarioElement(name);
                if (original != null) scenario.addScenarioElement(name, original);
            }
        });
    }
}