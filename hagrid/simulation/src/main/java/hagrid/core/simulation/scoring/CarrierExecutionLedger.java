package hagrid.core.simulation.scoring;

import hagrid.core.config.ResolvedHagridConfig;
import org.matsim.api.core.v01.*;
import org.matsim.api.core.v01.events.*;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.events.handler.BasicEventHandler;
import org.matsim.freight.carriers.*;
import org.matsim.freight.carriers.events.*;
import org.matsim.vehicles.Vehicle;
import java.util.*;

/** One global event feed. Native freight IDs and boarding events supply every association. */
public final class CarrierExecutionLedger implements BasicEventHandler {
    public record ExecutedTour(Id<Vehicle> vehicleId, Id<Tour> tourId, double startSeconds, double endSeconds) {}
    public record ExecutedLeg(Id<Vehicle> vehicleId, Id<Tour> tourId, double departureSeconds, double arrivalSeconds,
            List<Id<Link>> enteredLinks,Id<Link> departureLinkId,Id<Link> arrivalLinkId) {
        public ExecutedLeg { enteredLinks = List.copyOf(enteredLinks); }
        public ExecutedLeg(Id<Vehicle> vehicleId,Id<Tour> tourId,double departureSeconds,double arrivalSeconds,List<Id<Link>> enteredLinks) {
            this(vehicleId,tourId,departureSeconds,arrivalSeconds,enteredLinks,null,null);
        }
    }
    /** Job IDs have explicit service:, pickup: or delivery: roles, never inferred from ID spelling. */
    public record ExecutedActivity(Id<Vehicle> vehicleId, Id<Tour> tourId, String jobId, double startSeconds, double endSeconds) {}
    public record CarrierExecution(List<ExecutedTour> tours, List<ExecutedLeg> legs, List<ExecutedActivity> activities, boolean complete, List<String> violations) {
        public CarrierExecution { tours=List.copyOf(tours); legs=List.copyOf(legs); activities=List.copyOf(activities); violations=List.copyOf(violations); }
    }
    private static final class State {
        final Id<Carrier> carrier; final Id<Vehicle> vehicle; final Id<Tour> tour; final double start;
        Double departure; Id<Link> departureLink; String activity; double activityStart;
        final List<Id<Link>> links=new ArrayList<>();
        State(CarrierTourStartEvent e) { carrier=e.getCarrierId(); vehicle=e.getVehicleId(); tour=e.getTourId(); start=e.getTime(); }
    }
    private static final class Observations {
        final List<ExecutedTour> tours=new ArrayList<>(); final List<ExecutedLeg> legs=new ArrayList<>();
        final List<ExecutedActivity> activities=new ArrayList<>(); final List<String> violations=new ArrayList<>();
        final Set<Id<Tour>> started=new HashSet<>();
    }
    private final Carriers carriers;
    private final Map<Id<Carrier>,Observations> observations=new HashMap<>();
    private final Map<Id<Vehicle>,State> active=new HashMap<>();
    private final Map<Id<Person>,Id<Vehicle>> drivers=new HashMap<>();
    private final List<Event> sameTime=new ArrayList<>();
    private double timestamp=Double.NEGATIVE_INFINITY;
    public CarrierExecutionLedger(Scenario scenario, ResolvedHagridConfig config) {
        carriers=CarriersUtils.getCarriers(scenario); Objects.requireNonNull(config);
    }
    @Override public synchronized void reset(int iteration) {
        observations.clear(); active.clear(); drivers.clear(); sameTime.clear(); timestamp=Double.NEGATIVE_INFINITY;
    }
    @Override public synchronized void handleEvent(Event event) {
        if (!(event instanceof AbstractCarrierEvent || event instanceof PersonEntersVehicleEvent
                || event instanceof VehicleEntersTrafficEvent || event instanceof VehicleLeavesTrafficEvent
                || event instanceof PersonDepartureEvent || event instanceof PersonArrivalEvent
                || event instanceof LinkEnterEvent || event instanceof PersonStuckEvent)) return;
        if (!Double.isFinite(event.getTime()) || event.getTime()<timestamp)
            throw new IllegalArgumentException("Non-monotonic carrier execution event stream");
        if (event.getTime()>timestamp) { flush(); timestamp=event.getTime(); }
        sameTime.add(event);
    }
    private Observations data(Id<Carrier> carrier) { return observations.computeIfAbsent(carrier, ignored -> new Observations()); }
    private void flush() {
        if (sameTime.isEmpty()) return;
        // Native creators may emit the tour-start after the boarding/departure that caused it.
        // Resolve identities first; preserve the native order within each driver's actions.
        for (Event e:sameTime) if (e instanceof CarrierTourStartEvent start) start(start);
        for (Event e:sameTime) {
            if (e instanceof PersonEntersVehicleEvent enter && active.containsKey(enter.getVehicleId())) drivers.put(enter.getPersonId(),enter.getVehicleId());
            if (e instanceof VehicleEntersTrafficEvent enter && active.containsKey(enter.getVehicleId())) drivers.put(enter.getPersonId(),enter.getVehicleId());
        }
        for (Event e:sameTime) if (!(e instanceof CarrierTourStartEvent || e instanceof CarrierTourEndEvent)) consume(e);
        for (Event e:sameTime) if (e instanceof CarrierTourEndEvent end) end(end);
        sameTime.clear();
    }
    private void start(CarrierTourStartEvent event) {
        if (!carriers.getCarriers().containsKey(event.getCarrierId())) return;
        var data=data(event.getCarrierId());
        if (event.getVehicleId()==null) { data.violations.add("Missing native tour vehicle " + event.getTourId()); return; }
        if (!data.started.add(event.getTourId()) || active.containsKey(event.getVehicleId())) {
            data.violations.add("Duplicate/overlapping tour start " + event.getTourId()); return;
        }
        active.put(event.getVehicleId(),new State(event));
    }
    private void consume(Event event) {
        if (event instanceof PersonDepartureEvent departure) {
            State state=active.get(drivers.get(departure.getPersonId()));
            if (state!=null) {
                if (state.departure!=null) data(state.carrier).violations.add("Duplicate departure " + state.tour);
                else { state.departure=departure.getTime(); state.departureLink=departure.getLinkId(); state.links.clear(); }
            }
        } else if (event instanceof LinkEnterEvent link) {
            State state=active.get(link.getVehicleId());
            if (state!=null) {
                if (state.departure==null) data(state.carrier).violations.add("Link entered without departure " + state.tour);
                else state.links.add(link.getLinkId());
            }
        } else if (event instanceof PersonArrivalEvent arrival) {
            State state=active.get(drivers.get(arrival.getPersonId()));
            if (state!=null) {
                if (state.departure==null) data(state.carrier).violations.add("Arrival without departure " + state.tour);
                else {
                    data(state.carrier).legs.add(new ExecutedLeg(state.vehicle,state.tour,state.departure,arrival.getTime(),state.links,state.departureLink,arrival.getLinkId()));
                    state.departure=null; state.departureLink=null; state.links.clear();
                }
            }
        } else if (event instanceof PersonStuckEvent stuck) {
            State state=active.get(drivers.get(stuck.getPersonId()));
            if (state!=null) data(state.carrier).violations.add("Driver stuck in tour " + state.tour);
        } else if (event instanceof AbstractCarrierEvent freight) {
            State state=active.get(freight.getVehicleId());
            if (state==null || !state.carrier.equals(freight.getCarrierId())) {
                if (carriers.getCarriers().containsKey(freight.getCarrierId())) data(freight.getCarrierId()).violations.add("Freight activity without matching active tour");
                return;
            }
            String role=null; boolean opening=false;
            if (freight instanceof CarrierServiceStartEvent e) { role="service:"+e.getServiceId(); opening=true; }
            else if (freight instanceof CarrierServiceEndEvent e) role="service:"+e.getServiceId();
            else if (freight instanceof CarrierShipmentPickupStartEvent e) { role="pickup:"+e.getShipmentId(); opening=true; }
            else if (freight instanceof CarrierShipmentPickupEndEvent e) role="pickup:"+e.getShipmentId();
            else if (freight instanceof CarrierShipmentDeliveryStartEvent e) { role="delivery:"+e.getShipmentId(); opening=true; }
            else if (freight instanceof CarrierShipmentDeliveryEndEvent e) role="delivery:"+e.getShipmentId();
            if (role==null) return;
            if (opening) {
                if (state.activity!=null) data(state.carrier).violations.add("Overlapping freight activities " + state.tour);
                else { state.activity=role; state.activityStart=event.getTime(); }
            } else if (!role.equals(state.activity)) data(state.carrier).violations.add("Freight activity end without matching start " + role);
            else {
                data(state.carrier).activities.add(new ExecutedActivity(state.vehicle,state.tour,role,state.activityStart,event.getTime())); state.activity=null;
            }
        }
    }
    private void end(CarrierTourEndEvent event) {
        if (!carriers.getCarriers().containsKey(event.getCarrierId())) return;
        State state=active.get(event.getVehicleId());
        if (state==null || !state.tour.equals(event.getTourId()) || !state.carrier.equals(event.getCarrierId())) {
            data(event.getCarrierId()).violations.add("Tour end without matching start " + event.getTourId()); return;
        }
        if (state.departure!=null || state.activity!=null) data(state.carrier).violations.add("Unclosed leg/activity at tour end " + state.tour);
        data(state.carrier).tours.add(new ExecutedTour(state.vehicle,state.tour,state.start,event.getTime()));
        active.remove(state.vehicle);
        drivers.values().removeIf(state.vehicle::equals);
    }
    public synchronized CarrierExecution complete(Id<Carrier> carrierId) {
        flush(); Carrier carrier=carriers.getCarriers().get(carrierId);
        if (carrier==null) throw new IllegalArgumentException("Unknown carrier " + carrierId);
        var data=data(carrierId); var errors=new ArrayList<>(data.violations);
        active.values().stream().filter(s -> s.carrier.equals(carrierId)).forEach(s -> errors.add("Missing tour end " + s.tour));
        var expected=new HashMap<Id<Tour>,ScheduledTour>();
        if (carrier.getSelectedPlan()!=null) for (var tour:carrier.getSelectedPlan().getScheduledTours()) {
            if (tour.getTour().getTourElements().isEmpty()) continue;
            if (expected.put(tour.getTour().getId(),tour)!=null) errors.add("Non-unique selected tour ID " + tour.getTour().getId());
        }
        var ended=new HashSet<Id<Tour>>();
        for (var tour:data.tours) if (!ended.add(tour.tourId())) errors.add("Duplicate tour end " + tour.tourId());
        if (!ended.equals(expected.keySet())) errors.add("Executed tours differ from selected plan: expected " + expected.keySet() + ", observed " + ended);
        expected.forEach((id,scheduled) -> {
            long legs=scheduled.getTour().getTourElements().stream().filter(Tour.Leg.class::isInstance).count();
            if (data.legs.stream().filter(l -> l.tourId().equals(id)).count()!=legs) errors.add("Incomplete leg observations " + id);
            var jobs=scheduled.getTour().getTourElements().stream().filter(Tour.TourActivity.class::isInstance)
                    .map(Tour.TourActivity.class::cast).map(CarrierExecutionLedger::jobRole).toList();
            var observed=data.activities.stream().filter(a -> a.tourId().equals(id)).map(ExecutedActivity::jobId).toList();
            if (!jobs.equals(observed)) errors.add("Incomplete/changed activity observations " + id);
        });
        return new CarrierExecution(data.tours,data.legs,data.activities,errors.isEmpty(),errors);
    }
    static String jobRole(Tour.TourActivity activity) {
        if (activity instanceof Tour.ServiceActivity a) return "service:"+a.getService().getId();
        if (activity instanceof Tour.Pickup a) return "pickup:"+a.getShipment().getId();
        if (activity instanceof Tour.Delivery a) return "delivery:"+a.getShipment().getId();
        throw new IllegalArgumentException("Unsupported freight activity " + activity);
    }
}
