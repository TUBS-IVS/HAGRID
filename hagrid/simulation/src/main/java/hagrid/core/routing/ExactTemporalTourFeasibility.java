package hagrid.core.routing;

import com.graphhopper.jsprit.core.problem.Location;
import com.graphhopper.jsprit.core.problem.constraint.HardActivityConstraint.ConstraintsStatus;
import com.graphhopper.jsprit.core.problem.misc.JobInsertionContext;
import com.graphhopper.jsprit.core.problem.solution.route.VehicleRoute;
import com.graphhopper.jsprit.core.problem.solution.route.activity.*;
import com.graphhopper.jsprit.core.problem.vehicle.Vehicle;
import java.util.*;

/** Complete candidate replay for raw profiles; no scalar latest-start state or monotonic pruning. */
final class ExactTemporalTourFeasibility {
    private ExactTemporalTourFeasibility() {}

    static ConstraintsStatus insertion(ModalTransportCosts costs,JobInsertionContext facts,TourActivity inserted,
            TourActivity next,double limit) {
        var candidate=activitiesForInsertion(facts,inserted,next);
        boolean complete=!(inserted instanceof PickupShipment);
        return feasible(costs,candidate,facts.getNewVehicle(),facts.getNewDepTime(),limit,complete)
                ?ConstraintsStatus.FULFILLED:ConstraintsStatus.NOT_FULFILLED;
    }

    static List<TourActivity> activitiesForInsertion(JobInsertionContext facts,TourActivity inserted,TourActivity next) {
        var original=facts.getRoute().getActivities();
        int at=next instanceof End?original.size():original.indexOf(next);
        if(at<0)throw new IllegalArgumentException("Insertion successor is not in the original route");
        var candidate=new ArrayList<TourActivity>();
        if(inserted instanceof DeliverShipment) {
            var related=facts.getRelatedActivityContext();
            if(related==null||facts.getAssociatedActivities().size()!=2)
                throw new IllegalArgumentException("Shipment delivery needs its paired pickup insertion context");
            int pickup=related.getInsertionIndex();
            if(pickup<0||pickup>at)throw new IllegalArgumentException("Invalid paired pickup index");
            for(int i=0;i<=original.size();i++) {
                if(i==pickup)candidate.add(facts.getAssociatedActivities().getFirst());
                if(i==at)candidate.add(inserted);
                if(i<original.size())candidate.add(original.get(i));
            }
        } else {
            candidate.addAll(original.subList(0,at));candidate.add(inserted);
            // A pending delivery can change any later non-FIFO leg. Test the immutable prefix only
            // during pickup enumeration; delivery enumeration certifies the complete pair and suffix.
            if(!(inserted instanceof PickupShipment))candidate.addAll(original.subList(at,original.size()));
        }
        return candidate;
    }

    static boolean route(ModalTransportCosts costs,VehicleRoute route,double departure,double limit) {
        return feasible(costs,route.getActivities(),route.getVehicle(),departure,limit,true);
    }

    private static boolean feasible(ModalTransportCosts costs,List<TourActivity> activities,Vehicle vehicle,
            double departure,double limit,boolean complete) {
        if(!Double.isFinite(departure)||departure<vehicle.getEarliestDeparture())return false;
        Location previous=vehicle.getStartLocation();double time=departure;
        try {
            for(var activity:activities) {
                time+=costs.exactTransportTime(previous,activity.getLocation(),time,vehicle);
                time=Math.max(time,activity.getTheoreticalEarliestOperationStartTime());
                if(time>activity.getTheoreticalLatestOperationStartTime())return false;
                time+=activity.getOperationTime();previous=activity.getLocation();
                if(!Double.isFinite(time)||time-departure>limit||time>vehicle.getLatestArrival())return false;
            }
            if(complete&&vehicle.isReturnToDepot()&&!activities.isEmpty())
                time+=costs.exactTransportTime(previous,vehicle.getEndLocation(),time,vehicle);
            return Double.isFinite(time)&&time<=vehicle.getLatestArrival()&&time-departure<=limit;
        } catch(TravelTimeSnapshot.RangeException|NoRouteException outsideCapturedDomain) {return false;}
    }
}
