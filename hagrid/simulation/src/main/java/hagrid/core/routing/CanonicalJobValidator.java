package hagrid.core.routing;

import org.matsim.freight.carriers.*;
import java.util.*;

/** Value consistency, not reference identity. An old plan is never silently repaired. */
final class CanonicalJobValidator {
    private CanonicalJobValidator() {}
    static List<String> validate(Carrier carrier,Tour.TourActivity activity) {
        var errors=new ArrayList<String>();
        if(activity instanceof Tour.ServiceActivity a) {
            var actual=a.getService();var canonical=carrier.getServices().get(actual.getId());
            if(canonical==null)return List.of("Unknown canonical service " + actual.getId());
            same(actual.getServiceLinkId(),canonical.getServiceLinkId(),"service location",errors);
            same(actual.getCapacityDemand(),canonical.getCapacityDemand(),"service capacity",errors);
            same(actual.getServiceDuration(),canonical.getServiceDuration(),"service duration",errors);
            window(actual.getServiceStaringTimeWindow(),canonical.getServiceStaringTimeWindow(),"service time window",errors);
            skills(CarriersUtils.getSkills(actual),CarriersUtils.getSkills(canonical),errors);
            window(activity.getTimeWindow(),canonical.getServiceStaringTimeWindow(),"activity time window",errors);
        } else if(activity instanceof Tour.ShipmentBasedActivity a) {
            var actual=a.getShipment();var canonical=carrier.getShipments().get(actual.getId());
            if(canonical==null)return List.of("Unknown canonical shipment " + actual.getId());
            same(actual.getPickupLinkId(),canonical.getPickupLinkId(),"shipment pickup location",errors);
            same(actual.getDeliveryLinkId(),canonical.getDeliveryLinkId(),"shipment delivery location",errors);
            same(actual.getCapacityDemand(),canonical.getCapacityDemand(),"shipment capacity",errors);
            same(actual.getPickupDuration(),canonical.getPickupDuration(),"shipment pickup duration",errors);
            same(actual.getDeliveryDuration(),canonical.getDeliveryDuration(),"shipment delivery duration",errors);
            window(actual.getPickupStartingTimeWindow(),canonical.getPickupStartingTimeWindow(),"shipment pickup time window",errors);
            window(actual.getDeliveryStartingTimeWindow(),canonical.getDeliveryStartingTimeWindow(),"shipment delivery time window",errors);
            skills(CarriersUtils.getSkills(actual),CarriersUtils.getSkills(canonical),errors);
            var expected=activity instanceof Tour.Pickup?canonical.getPickupStartingTimeWindow():canonical.getDeliveryStartingTimeWindow();
            window(activity.getTimeWindow(),expected,"activity time window",errors);
        } else errors.add("Unsupported canonical activity " + activity.getClass().getSimpleName());
        return List.copyOf(errors);
    }
    static void requireConsistentPlan(Carrier carrier,CarrierPlan plan) {
        if(plan==null)return;
        var errors=new ArrayList<String>();
        plan.getScheduledTours().forEach(t -> t.getTour().getTourElements().forEach(e -> {
            if(e instanceof Tour.TourActivity a)errors.addAll(validate(carrier,a));
        }));
        if(!errors.isEmpty())throw new IllegalArgumentException("Non-canonical plan jobs: " + errors);
    }
    private static void same(Object actual,Object expected,String field,List<String> errors) {
        if(!Objects.equals(actual,expected)) errors.add("Changed canonical " + field + ": " + actual + " != " + expected);
    }
    private static void window(TimeWindow actual,TimeWindow expected,String field,List<String> errors) {
        if(actual==null||expected==null||Double.compare(actual.getStart(),expected.getStart())!=0||Double.compare(actual.getEnd(),expected.getEnd())!=0)
            errors.add("Changed canonical " + field);
    }
    private static void skills(Collection<String> actual,Collection<String> expected,List<String> errors) {
        if(!new HashSet<>(actual).equals(new HashSet<>(expected)))errors.add("Changed canonical job skills");
    }
}
