package hagrid.core.routing;

import java.util.*;
import org.matsim.freight.carriers.*;
import org.matsim.utils.objectattributes.attributable.Attributes;

/** Detached native plans: cloned roads, own jobs/attributes and own vehicle tariffs. */
final class CarrierPlanCopies {
    private CarrierPlanCopies() {}
    static CarrierPlan copy(CarrierPlan source, Carrier canonical) {
        CanonicalJobValidator.requireConsistentPlan(canonical,source);
        var own=CarrierSearchInput.copy(canonical);
        copyAttributes(canonical.getAttributes(),own.getAttributes());
        for(var v:own.getCarrierCapabilities().getCarrierVehicles().values()) {
            var original=canonical.getCarrierCapabilities().getCarrierVehicles().get(v.getId());
            copyAttributes(original.getAttributes(),v.getAttributes());
            var a=original.getType();var b=v.getType();
            copyAttributes(a.getAttributes(),b.getAttributes());
            copyAttributes(a.getCapacity().getAttributes(),b.getCapacity().getAttributes());
            copyAttributes(a.getCostInformation().getAttributes(),b.getCostInformation().getAttributes());
            copyAttributes(a.getEngineInformation().getAttributes(),b.getEngineInformation().getAttributes());
        }
        own.getServices().clear();own.getShipments().clear();
        for(var s:canonical.getServices().values()) {
            var clone=CarrierService.Builder.newInstance(s.getId(),s.getServiceLinkId(),s.getCapacityDemand())
                    .setServiceDuration(s.getServiceDuration()).setServiceStartingTimeWindow(s.getServiceStaringTimeWindow()).build();
            copyAttributes(s.getAttributes(),clone.getAttributes());own.getServices().put(clone.getId(),clone);
        }
        for(var s:canonical.getShipments().values()) {
            var clone=CarrierShipment.Builder.newInstance(s.getId(),s.getPickupLinkId(),s.getDeliveryLinkId(),s.getSize())
                    .setPickupDuration(s.getPickupDuration()).setDeliveryDuration(s.getDeliveryDuration())
                    .setPickupStartingTimeWindow(s.getPickupStartingTimeWindow()).setDeliveryStartingTimeWindow(s.getDeliveryStartingTimeWindow()).build();
            copyAttributes(s.getAttributes(),clone.getAttributes());own.getShipments().put(clone.getId(),clone);
        }
        var tours=new ArrayList<ScheduledTour>();
        for(var st:source.getScheduledTours()) {
            var original=st.getTour(); var b=Tour.Builder.newInstance(original.getId());
            b.scheduleStart(original.getStartLinkId(),original.getStart().getTimeWindow());
            for(var element:original.getTourElements()) {
                if(element instanceof Tour.Leg leg)b.addLeg((Tour.Leg)leg.duplicate());
                else if(element instanceof Tour.ServiceActivity a)b.scheduleService(own.getServices().get(a.getService().getId()));
                else if(element instanceof Tour.Pickup a)b.schedulePickup(own.getShipments().get(a.getShipment().getId()));
                else if(element instanceof Tour.Delivery a)b.scheduleDelivery(own.getShipments().get(a.getShipment().getId()));
                else throw new IllegalArgumentException("Unknown carrier activity "+element.getClass());
            }
            b.scheduleEnd(original.getEndLinkId(),original.getEnd().getTimeWindow());var tour=b.build();
            tour.getStart().setExpectedArrival(original.getStart().getExpectedArrival());
            tour.getEnd().setExpectedArrival(original.getEnd().getExpectedArrival());
            for(int i=0;i<original.getTourElements().size();i++)
                if(original.getTourElements().get(i) instanceof Tour.TourActivity a)
                    ((Tour.TourActivity)tour.getTourElements().get(i)).setExpectedArrival(a.getExpectedArrival());
            var vehicle=own.getCarrierCapabilities().getCarrierVehicles().get(st.getVehicle().getId());
            if(vehicle==null && own.getCarrierCapabilities().getFleetSize()==CarrierCapabilities.FleetSize.INFINITE) {
                var sourceVehicle=st.getVehicle();var sourceType=sourceVehicle.getType();
                var template=own.getCarrierCapabilities().getCarrierVehicles().values().stream().filter(v->
                    v.getType().getId().equals(sourceType.getId()) && v.getLinkId().equals(sourceVehicle.getLinkId())
                    && Double.compare(v.getEarliestStartTime(),sourceVehicle.getEarliestStartTime())==0
                    && Double.compare(v.getLatestEndTime(),sourceVehicle.getLatestEndTime())==0).findFirst()
                    .orElseThrow(()->new IllegalArgumentException("Infinite clone has no matching canonical template"));
                var expected=template.getType();var a=sourceType.getCostInformation();var expectedCosts=expected.getCostInformation();
                if(!Objects.equals(sourceType.getNetworkMode(),expected.getNetworkMode())
                    || Double.compare(sourceType.getMaximumVelocity(),expected.getMaximumVelocity())!=0
                    || Double.compare(sourceType.getCapacity().getOther(),expected.getCapacity().getOther())!=0
                    || !Objects.equals(a.getFixedCosts(),expectedCosts.getFixedCosts()) || !Objects.equals(a.getCostsPerMeter(),expectedCosts.getCostsPerMeter())
                    || !Objects.equals(a.getCostsPerSecond(),expectedCosts.getCostsPerSecond())
                    || !new HashSet<>(CarriersUtils.getSkills(sourceType)).equals(new HashSet<>(CarriersUtils.getSkills(expected))))
                    throw new IllegalArgumentException("Infinite clone changed its canonical vehicle profile");
                vehicle=CarrierVehicle.Builder.newInstance(sourceVehicle.getId(),template.getLinkId(),expected)
                    .setEarliestStart(template.getEarliestStartTime()).setLatestEnd(template.getLatestEndTime()).build();
                copyAttributes(sourceVehicle.getAttributes(),vehicle.getAttributes());
            }
            if(vehicle==null)throw new IllegalArgumentException("Scheduled vehicle absent from canonical fleet");
            tours.add(ScheduledTour.newInstance(tour,vehicle,st.getDeparture()));
        }
        var plan=new CarrierPlan(tours);plan.setCarrier(own);plan.setScore(source.getScore());copyAttributes(source.getAttributes(),plan.getAttributes());
        return plan;
    }
    private static void copyAttributes(Attributes from,Attributes to) {
        to.clear();from.getAsMap().forEach((key,value)->to.putAttribute(key,copyValue(value)));
    }
    private static Object copyValue(Object value) {
        if(value instanceof TimeWindow)return value;
        return RoutingData.copy(value);
    }
}

