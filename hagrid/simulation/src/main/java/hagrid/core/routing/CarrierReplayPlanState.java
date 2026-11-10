package hagrid.core.routing;

import org.matsim.api.core.v01.Id;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.freight.carriers.*;
import java.util.*;

/** Private replay metadata for fields the native carrier XML deliberately does not retain. */
record CarrierReplayPlanState(List<TourState> tours) {
    CarrierReplayPlanState { tours=List.copyOf(tours); }
    record Boundary(String link,double earliest,double latest,double arrival) {
        static Boundary capture(Tour.TourActivity a) {
            return new Boundary(a.getLocation().toString(),a.getTimeWindow().getStart(),a.getTimeWindow().getEnd(),a.getExpectedArrival());
        }
        TimeWindow window(){return TimeWindow.newInstance(earliest,latest);}
    }
    record Road(boolean present,String start,String end,List<String> intermediate,String vehicle,double distance,Double travelTime) {
        Road { intermediate=List.copyOf(intermediate); }
        static Road capture(Tour.Leg leg) {
            if(leg.getRoute()==null)return new Road(false,null,null,List.of(),null,Double.NaN,null);
            if(!(leg.getRoute() instanceof NetworkRoute r))throw new IllegalArgumentException("Replay requires a network road route");
            return new Road(true,r.getStartLinkId().toString(),r.getEndLinkId().toString(),r.getLinkIds().stream().map(Object::toString).toList(),
                    r.getVehicleId()==null?null:r.getVehicleId().toString(),r.getDistance(),r.getTravelTime().isDefined()?r.getTravelTime().seconds():null);
        }
        void restore(Tour.Leg leg) {
            if(!present){if(leg.getRoute()!=null)throw new IllegalArgumentException("Replay road presence differs");return;}
            if(!(leg.getRoute() instanceof NetworkRoute r)||!start.equals(r.getStartLinkId().toString())||!end.equals(r.getEndLinkId().toString())
                    ||!intermediate.equals(r.getLinkIds().stream().map(Object::toString).toList()))
                throw new IllegalArgumentException("Replay road link domain differs");
            r.setVehicleId(vehicle==null?null:Id.createVehicleId(vehicle));r.setDistance(distance);
            if(travelTime==null)r.setTravelTimeUndefined();else r.setTravelTime(travelTime);
        }
    }
    record TourState(String id,Boundary start,Boundary end,List<Road> roads) {
        TourState { roads=List.copyOf(roads); }
    }
    static CarrierReplayPlanState capture(CarrierPlan plan) {
        return new CarrierReplayPlanState(plan.getScheduledTours().stream().map(st->{
            var t=st.getTour();return new TourState(t.getId().toString(),Boundary.capture(t.getStart()),Boundary.capture(t.getEnd()),
                    t.getTourElements().stream().filter(Tour.Leg.class::isInstance).map(Tour.Leg.class::cast).map(Road::capture).toList());
        }).toList());
    }
    void restore(Carrier carrier) {
        var source=Objects.requireNonNull(carrier.getSelectedPlan(),"Missing previous replay plan");
        var scheduled=new ArrayList<>(source.getScheduledTours());
        if(scheduled.size()!=tours.size())throw new IllegalArgumentException("Replay tour domain differs");
        var restored=new ArrayList<ScheduledTour>();
        for(int i=0;i<scheduled.size();i++) {
            var st=scheduled.get(i);var t=st.getTour();var state=tours.get(i);
            if(!state.id.equals(t.getId().toString())||!state.start.link.equals(t.getStartLinkId().toString())||!state.end.link.equals(t.getEndLinkId().toString()))
                throw new IllegalArgumentException("Replay tour boundaries differ");
            var b=Tour.Builder.newInstance(t.getId());b.scheduleStart(t.getStartLinkId(),state.start.window());int legIndex=0;
            for(var element:t.getTourElements()) {
                if(element instanceof Tour.Leg leg) {
                    if(legIndex>=state.roads.size())throw new IllegalArgumentException("Replay leg domain differs");
                    var own=(Tour.Leg)leg.duplicate();state.roads.get(legIndex++).restore(own);b.addLeg(own);
                } else if(element instanceof Tour.ServiceActivity a)b.scheduleService(a.getService());
                else if(element instanceof Tour.Pickup a)b.schedulePickup(a.getShipment());
                else if(element instanceof Tour.Delivery a)b.scheduleDelivery(a.getShipment());
                else throw new IllegalArgumentException("Unsupported replay activity");
            }
            if(legIndex!=state.roads.size())throw new IllegalArgumentException("Replay leg domain differs");
            b.scheduleEnd(t.getEndLinkId(),state.end.window());var tour=b.build();
            tour.getStart().setExpectedArrival(state.start.arrival);tour.getEnd().setExpectedArrival(state.end.arrival);
            for(int e=0;e<t.getTourElements().size();e++)if(t.getTourElements().get(e) instanceof Tour.TourActivity a)
                ((Tour.TourActivity)tour.getTourElements().get(e)).setExpectedArrival(a.getExpectedArrival());
            restored.add(ScheduledTour.newInstance(tour,st.getVehicle(),st.getDeparture()));
        }
        var plan=new CarrierPlan(restored);plan.setCarrier(carrier);plan.setScore(source.getScore());
        source.getAttributes().getAsMap().forEach((key,value)->plan.getAttributes().putAttribute(key,RoutingData.copy(value)));
        carrier.getPlans().remove(source);carrier.addPlan(plan);carrier.setSelectedPlan(plan);
    }
}