package hagrid.core.routing;

import com.graphhopper.jsprit.core.algorithm.recreate.ActivityInsertionCostsCalculator;
import com.graphhopper.jsprit.core.problem.cost.*;
import com.graphhopper.jsprit.core.problem.driver.Driver;
import com.graphhopper.jsprit.core.problem.misc.JobInsertionContext;
import com.graphhopper.jsprit.core.problem.solution.route.activity.*;
import com.graphhopper.jsprit.core.problem.vehicle.Vehicle;
import java.util.List;

/** Full variable-cost deltas for native regret insertion, retaining its fixed/soft cost machinery. */
final class ExactTemporalInsertionCosts implements ActivityInsertionCostsCalculator {
    private final ModalTransportCosts roads;
    private final VehicleRoutingTransportCosts transport;
    private final VehicleRoutingActivityCosts activities;
    private final double deliverySoftCreditBound;
    private final ExactInsertionReplay replay;
    ExactTemporalInsertionCosts(ModalTransportCosts roads,VehicleRoutingActivityCosts activities,double uTurnPenalty) {
        this(roads,activities,uTurnPenalty,activities instanceof HagridActivityCosts h?new ExactInsertionReplay(roads,h,Double.MAX_VALUE):null);
    }
    ExactTemporalInsertionCosts(ModalTransportCosts roads,VehicleRoutingActivityCosts activities,double uTurnPenalty,ExactInsertionReplay replay) {
        this.roads=roads;this.transport=roads.solverCosts();this.activities=activities;this.replay=replay;
        if(!Double.isFinite(uTurnPenalty)||uTurnPenalty<0)throw new IllegalArgumentException("Invalid U-turn penalty");
        deliverySoftCreditBound=uTurnPenalty;
    }
    // jsprit AIC orders previous, next, inserted (unlike HardActivityConstraint's previous, inserted, next).
    public double getCosts(JobInsertionContext facts,TourActivity previous,TourActivity next,TourActivity inserted,double previousEnd) {
        var route=facts.getRoute();
        double oldCost=route.isEmpty()?0:replay==null?variableCost(route.getActivities(),route.getVehicle(),route.getDriver(),route.getDepartureTime()):replay.originalCost(facts,()->variableCost(route.getActivities(),route.getVehicle(),route.getDriver(),route.getDepartureTime()));
        // Removing a job can itself create an infeasible non-FIFO route. Such a partial route has no
        // finite cost delta; rank finite repairs by their absolute variable cost, without Infinity arithmetic.
        if(!Double.isFinite(oldCost))oldCost=0;
        double access=nativeAccessEgress(facts);
        if(inserted instanceof PickupShipment)return -oldCost-access-deliverySoftCreditBound;
        double newCost=replay!=null?replay.evaluate(facts,inserted,next,true).variableCost():variableCost(ExactTemporalTourFeasibility.activitiesForInsertion(facts,inserted,next),
                facts.getNewVehicle(),facts.getNewDriver(),facts.getNewDepTime());
        if(!Double.isFinite(newCost))return Double.POSITIVE_INFINITY;
        // Native shipment calculation sums pickup + delivery + its access/egress adjustment. Pickup
        // carries an admissible lower bound; delivery supplies the full paired cost, never a temporary suffix.
        return inserted instanceof DeliverShipment?newCost+deliverySoftCreditBound:newCost-oldCost-access;
    }
    private double variableCost(List<TourActivity> stops,Vehicle vehicle,Driver driver,double departure) {
        if(stops.isEmpty())return 0;
        var previous=vehicle.getStartLocation();double time=departure,cost=0;
        try {
            for(var stop:stops) {
                cost+=transport.getTransportCost(previous,stop.getLocation(),time,driver,vehicle);
                time+=roads.exactTransportTime(previous,stop.getLocation(),time,vehicle);
                cost+=activities.getActivityCost(stop,time,driver,vehicle);
                time=Math.max(time,stop.getTheoreticalEarliestOperationStartTime())+activities.getActivityDuration(stop,time,driver,vehicle);
                previous=stop.getLocation();
            }
            if(vehicle.isReturnToDepot()) {
                cost+=transport.getTransportCost(previous,vehicle.getEndLocation(),time,driver,vehicle);
                roads.exactTransportTime(previous,vehicle.getEndLocation(),time,vehicle);
            }
            return cost;
        } catch(TravelTimeSnapshot.RangeException|NoRouteException unavailable) {return Double.POSITIVE_INFINITY;}
    }
    /** jsprit2.0 AdditionalAccessEgressCalculator contribution, subtracted because full replay includes it. */
    double nativeAccessEgress(JobInsertionContext facts) {
        var route=facts.getRoute();var vehicle=facts.getNewVehicle();
        if(route.isEmpty()||route.getVehicle()==vehicle)return 0;
        var first=route.getActivities().getFirst();var last=route.getActivities().getLast();
        double access=transport.getTransportCost(vehicle.getStartLocation(),first.getLocation(),facts.getNewDepTime(),facts.getNewDriver(),vehicle)
                -transport.getTransportCost(route.getStart().getLocation(),first.getLocation(),route.getDepartureTime(),route.getDriver(),route.getVehicle());
        double egress=0;
        if(vehicle.isReturnToDepot()) {
            double shifted=Math.max(0,last.getEndTime()+facts.getNewDepTime()-route.getDepartureTime());
            egress=transport.getTransportCost(last.getLocation(),vehicle.getEndLocation(),shifted,facts.getNewDriver(),vehicle)
                    -transport.getTransportCost(last.getLocation(),route.getEnd().getLocation(),last.getEndTime(),route.getDriver(),route.getVehicle());
        }
        return access+egress;
    }
}
