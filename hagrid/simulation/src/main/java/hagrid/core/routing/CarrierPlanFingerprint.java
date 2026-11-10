package hagrid.core.routing;

import org.matsim.freight.carriers.*;
import java.util.*;

/** Semantic scheduled-plan identity shared by replay certification. */
final class CarrierPlanFingerprint {
    private CarrierPlanFingerprint() {}
    static String of(CarrierPlan plan) {
        if (plan == null) return "missing";
        var tours = new ArrayList<Object>();
        for (var scheduled : plan.getScheduledTours()) {
            var elements = new ArrayList<Object>();
            for (var element : scheduled.getTour().getTourElements()) {
                if (element instanceof Tour.Leg leg) {
                    var route = (org.matsim.core.population.routes.NetworkRoute) leg.getRoute();
                    elements.add(Arrays.asList("leg", leg.getExpectedDepartureTime(), leg.getExpectedTransportTime(),
                            route == null ? null : route.getStartLinkId(), route == null ? null : route.getLinkIds(),
                            route == null ? null : route.getEndLinkId(),route == null ? null : route.getVehicleId(),route == null ? null : route.getDistance(),
                            route == null ? null : route.getTravelTime().orElse(Double.NaN)));
                } else if (element instanceof Tour.ServiceActivity a) elements.add(List.of("service", a.getService().getId(), activityFingerprint(a)));
                else if (element instanceof Tour.Pickup a) elements.add(List.of("pickup", a.getShipment().getId(), activityFingerprint(a)));
                else if (element instanceof Tour.Delivery a) elements.add(List.of("delivery", a.getShipment().getId(), activityFingerprint(a)));
                else throw new IllegalArgumentException("Unsupported replay tour activity " + element.getClass());
            }
            var v = scheduled.getVehicle(); var t = v.getType();
            var vehicle = Arrays.asList(v.getId(), v.getLinkId(), v.getEarliestStartTime(), v.getLatestEndTime(),
                    t.getId(), t.getNetworkMode(), t.getMaximumVelocity(), t.getCapacity().getOther(),
                    t.getCostInformation().getFixedCosts(), t.getCostInformation().getCostsPerMeter(),
                    t.getCostInformation().getCostsPerSecond(), new TreeSet<>(CarriersUtils.getSkills(t)));
            tours.add(List.of(scheduled.getTour().getId(),vehicle,scheduled.getDeparture(),
                    activityFingerprint(scheduled.getTour().getStart()),elements,
                    activityFingerprint(scheduled.getTour().getEnd())));
        }
        return RoutingData.hashValues("replay-plan-v3", tours);
    }
    private static Object activityFingerprint(Tour.TourActivity activity) {
        var fields=new ArrayList<Object>(List.of(activity.getLocation(),activity.getDuration(),
                activity.getTimeWindow().getStart(),activity.getTimeWindow().getEnd(),activity.getExpectedArrival()));
        if(activity instanceof Tour.ServiceActivity a) {
            var j=a.getService();fields.add(List.of(j.getId(),j.getServiceLinkId(),j.getCapacityDemand(),
                    j.getServiceDuration(),j.getServiceStaringTimeWindow().getStart(),j.getServiceStaringTimeWindow().getEnd(),
                    new TreeSet<>(CarriersUtils.getSkills(j))));
        } else if(activity instanceof Tour.ShipmentBasedActivity a) {
            var j=a.getShipment();fields.add(List.of(j.getId(),j.getPickupLinkId(),j.getDeliveryLinkId(),
                    j.getCapacityDemand(),j.getPickupDuration(),j.getDeliveryDuration(),
                    j.getPickupStartingTimeWindow().getStart(),j.getPickupStartingTimeWindow().getEnd(),
                    j.getDeliveryStartingTimeWindow().getStart(),j.getDeliveryStartingTimeWindow().getEnd(),
                    new TreeSet<>(CarriersUtils.getSkills(j))));
        }
        return fields;
    }
}
