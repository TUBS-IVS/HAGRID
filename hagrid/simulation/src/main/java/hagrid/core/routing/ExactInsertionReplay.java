package hagrid.core.routing;

import com.graphhopper.jsprit.core.problem.misc.JobInsertionContext;
import com.graphhopper.jsprit.core.problem.solution.route.activity.*;
import com.graphhopper.jsprit.core.problem.vehicle.*;
import java.util.*;
import java.util.function.DoubleSupplier;

/** One bounded, worker-local enumeration state. Replays only the changed suffix; no FIFO pruning. */
final class ExactInsertionReplay {
    record Evaluation(boolean feasible,double variableCost) {}
    private record Activity(TourActivity identity,String location,double earliest,double latest,double duration) {
        Activity(TourActivity a){this(a,a.getLocation().getId(),a.getTheoreticalEarliestOperationStartTime(),a.getTheoreticalLatestOperationStartTime(),a.getOperationTime());}
        boolean matches(TourActivity a){return identity==a && location.equals(a.getLocation().getId())
                && earliest==a.getTheoreticalEarliestOperationStartTime() && latest==a.getTheoreticalLatestOperationStartTime() && duration==a.getOperationTime();}
    }
    private final ModalTransportCosts roads;
    private final HagridActivityCosts activities;
    private final double limit;
    private final Map<Thread,State> current=Collections.synchronizedMap(new WeakHashMap<>());

    ExactInsertionReplay(ModalTransportCosts roads,HagridActivityCosts activities,double limit) {
        this.roads=roads;this.activities=activities;this.limit=limit;
    }
    private State state(JobInsertionContext facts) {
        roads.solverCosts(); // Cached evaluations must retain the context's close/replacement guard.
        var worker=Thread.currentThread();
        var state=current.get(worker);
        if(state==null||!state.matches(facts)){state=new State(facts);current.put(worker,state);}
        return state;
    }
    Evaluation evaluate(JobInsertionContext facts,TourActivity inserted,TourActivity next,boolean needCost) {
        return state(facts).evaluate(facts,inserted,next,needCost);
    }
    double originalCost(JobInsertionContext facts,DoubleSupplier calculate) {
        var state=state(facts);
        if(state.originalCost==null)state.originalCost=calculate.getAsDouble();
        return state.originalCost;
    }
    private static boolean immutable(Vehicle v) {
        return v.getClass()==VehicleImpl.class && v.getType().getClass()==VehicleTypeImpl.class;
    }
    private final class State {
        final JobInsertionContext facts;
        final Vehicle vehicle,oldVehicle;
        final Object driver,oldDriver;
        final double departure,oldDeparture;
        final Activity[] original;
        final double[] prefixEnd,prefixCost;
        final boolean[] prefixFeasible;
        Double originalCost;
        Activity insertedKey,pickupKey;
        TourActivity nextKey;
        int pickupIndex;
        Evaluation last;

        State(JobInsertionContext f) {
            facts=f;vehicle=f.getNewVehicle();oldVehicle=f.getRoute().getVehicle();driver=f.getNewDriver();oldDriver=f.getRoute().getDriver();
            departure=f.getNewDepTime();oldDeparture=f.getRoute().getDepartureTime();
            original=f.getRoute().getActivities().stream().map(Activity::new).toArray(Activity[]::new);
            prefixEnd=new double[original.length+1];prefixCost=new double[original.length+1];prefixFeasible=new boolean[original.length+1];
            prefixEnd[0]=departure;prefixFeasible[0]=Double.isFinite(departure)&&departure>=vehicle.getEarliestDeparture();
            var previous=vehicle.getStartLocation();
            for(int i=0;i<original.length;i++) {
                var stop=original[i].identity();double time=prefixEnd[i],cost=prefixCost[i];
                try {
                    var leg=roads.insertionLeg(previous,stop.getLocation(),time,f.getNewDriver(),vehicle);
                    cost+=leg.monetaryCost();
                    time+=leg.timeSeconds();
                    cost+=activities.getActivityCost(stop,time,f.getNewDriver(),vehicle);
                    boolean ok=Math.max(time,stop.getTheoreticalEarliestOperationStartTime())<=stop.getTheoreticalLatestOperationStartTime();
                    time=Math.max(time,stop.getTheoreticalEarliestOperationStartTime())+stop.getOperationTime();
                    prefixEnd[i+1]=time;prefixCost[i+1]=cost;
                    prefixFeasible[i+1]=prefixFeasible[i]&&ok&&allowedEnd(time);
                    previous=stop.getLocation();
                }catch(TravelTimeSnapshot.RangeException|NoRouteException unavailable){
                    Arrays.fill(prefixEnd,i+1,prefixEnd.length,Double.NaN);Arrays.fill(prefixCost,i+1,prefixCost.length,Double.POSITIVE_INFINITY);break;
                }
            }
        }
        boolean allowedEnd(double time){return Double.isFinite(time)&&time-departure<=limit&&time<=vehicle.getLatestArrival();}
        boolean matches(JobInsertionContext f) {
            if(facts!=f||!immutable(vehicle)||!immutable(oldVehicle)||vehicle!=f.getNewVehicle()||oldVehicle!=f.getRoute().getVehicle()
                    ||driver!=f.getNewDriver()||oldDriver!=f.getRoute().getDriver()||departure!=f.getNewDepTime()||oldDeparture!=f.getRoute().getDepartureTime())return false;
            var stops=f.getRoute().getActivities();if(stops.size()!=original.length)return false;
            for(int i=0;i<original.length;i++)if(!original[i].matches(stops.get(i)))return false;
            return true;
        }
        Evaluation evaluate(JobInsertionContext f,TourActivity inserted,TourActivity next,boolean needCost) {
            int index=inserted instanceof DeliverShipment?f.getRelatedActivityContext().getInsertionIndex():-1;
            TourActivity pickup=inserted instanceof DeliverShipment?f.getAssociatedActivities().getFirst():null;
            if(last!=null&&nextKey==next&&insertedKey.matches(inserted)&&pickupIndex==index
                    &&(pickup==null?pickupKey==null:pickupKey!=null&&pickupKey.matches(pickup))
                    &&(!needCost||!Double.isNaN(last.variableCost())))return last;
            var route=f.getRoute().getActivities();
            int at=next instanceof End?route.size():route.indexOf(next);
            if(at<0)throw new IllegalArgumentException("Insertion successor is not in the original route");
            boolean paired=inserted instanceof DeliverShipment;
            if(paired&&(f.getAssociatedActivities().size()!=2||index<0||index>at))
                throw new IllegalArgumentException("Invalid paired pickup index");
            // Walk a virtual insertion so every candidate can reuse the original activity array.
            // Shipment delivery adds two positions; pickup-only checks end at the new pickup.
            int prefix=paired?index:at;
            int insertedAt=paired?at+1:at;
            int size=inserted instanceof PickupShipment?at+1:original.length+(paired?2:1);
            double time=prefixEnd[prefix],cost=prefixCost[prefix];boolean feasible=prefixFeasible[prefix];
            var previous=prefix==0?vehicle.getStartLocation():original[prefix-1].identity().getLocation();
            if(feasible||needCost)try {
                for(int i=prefix;i<size;i++) {
                    TourActivity stop;
                    if(paired&&i==index)stop=pickup;
                    else if(i==insertedAt)stop=inserted;
                    else stop=original[i-(i>insertedAt?1:0)-(paired&&i>index?1:0)].identity();
                    var leg=roads.insertionLeg(previous,stop.getLocation(),time,f.getNewDriver(),vehicle);
                    cost+=leg.monetaryCost();
                    time+=leg.timeSeconds();
                    cost+=activities.getActivityCost(stop,time,f.getNewDriver(),vehicle);
                    feasible&=Math.max(time,stop.getTheoreticalEarliestOperationStartTime())<=stop.getTheoreticalLatestOperationStartTime();
                    time=Math.max(time,stop.getTheoreticalEarliestOperationStartTime())+stop.getOperationTime();
                    feasible&=allowedEnd(time);previous=stop.getLocation();
                    if(!feasible&&!needCost)break;
                }
                if((feasible||needCost)&&!(inserted instanceof PickupShipment)&&vehicle.isReturnToDepot()) {
                    var leg=roads.insertionLeg(previous,vehicle.getEndLocation(),time,f.getNewDriver(),vehicle);
                    cost+=leg.monetaryCost();
                    time+=leg.timeSeconds();
                }
                feasible&=allowedEnd(time);
            }catch(TravelTimeSnapshot.RangeException|NoRouteException unavailable){feasible=false;cost=Double.POSITIVE_INFINITY;}
            if(!feasible&&!needCost)cost=Double.NaN;
            insertedKey=new Activity(inserted);nextKey=next;pickupIndex=index;pickupKey=pickup==null?null:new Activity(pickup);
            return last=new Evaluation(feasible,cost);
        }
    }
}
