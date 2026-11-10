package hagrid.hannover.demand;

import org.matsim.freight.carriers.*;
import java.util.*;

/** Restores caller-owned selections, plan lists and metadata if any later offline group or output fails. */
final class CarrierRoutingTransaction implements AutoCloseable {
    private record Before(Carrier carrier, List<CarrierPlan> plans, CarrierPlan selected, Map<String,Object> attributes) {}
    private final List<Before> before = new ArrayList<>();
    private boolean committed;

    CarrierRoutingTransaction(Carriers... inputs) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<Carrier,Boolean>());
        for (var input : inputs) for (var carrier : input.getCarriers().values()) {
            if (seen.add(carrier)) before.add(new Before(carrier, List.copyOf(carrier.getPlans()),
                    carrier.getSelectedPlan(), new LinkedHashMap<>(carrier.getAttributes().getAsMap())));
        }
    }
    void commit() { committed = true; }

    @Override public void close() {
        if (committed) return;
        for (var saved : before) {
            var carrier = saved.carrier();
            carrier.getPlans().clear(); carrier.getPlans().addAll(saved.plans());
            carrier.setSelectedPlan(saved.selected());
            for (String key : new ArrayList<>(carrier.getAttributes().getAsMap().keySet()))
                carrier.getAttributes().removeAttribute(key);
            saved.attributes().forEach(carrier.getAttributes()::putAttribute);
        }
    }
}