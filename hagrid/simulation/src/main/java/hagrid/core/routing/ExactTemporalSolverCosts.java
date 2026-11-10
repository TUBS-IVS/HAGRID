package hagrid.core.routing;

import com.graphhopper.jsprit.core.problem.Location;
import com.graphhopper.jsprit.core.problem.cost.VehicleRoutingTransportCosts;
import com.graphhopper.jsprit.core.problem.driver.Driver;
import com.graphhopper.jsprit.core.problem.vehicle.Vehicle;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.*;
import org.matsim.core.router.util.*;

/**
 * Immutable forward-only jsprit view for the raw distance objective. Complete actual-route constraints
 * replace native backward scalar states. Native insertion/state costs therefore use actual waiting
 * times with unchanged vehicle rates. The public adapter retains legacy half-hour OD estimates.
 */
final class ExactTemporalSolverCosts implements ReplanningTransportCosts,LinkRouteProvider {
    private final ModalTransportCosts delegate;
    ExactTemporalSolverCosts(ModalTransportCosts delegate){this.delegate=delegate;}
    static ModalTransportCosts rawAdapter(VehicleRoutingTransportCosts costs) {
        var result=costs instanceof ExactTemporalSolverCosts exact?exact.delegate:
                costs instanceof ModalTransportCosts modal?modal:null;
        return result!=null&&result.requiresExactTourFeasibility()?result:null;
    }
    public double getTransportTime(Location from,Location to,double departure,Driver driver,Vehicle vehicle){
        if(Double.isNaN(departure)||departure<0)throw new IllegalArgumentException("Invalid solver departure: "+departure);
        // Native shipment enumeration also advances provisional pickup-only suffixes. An uncaptured
        // traversal is unreachable, not a fabricated finite time; complete-pair replay decides feasibility.
        if(departure==Double.POSITIVE_INFINITY)return Double.POSITIVE_INFINITY;
        try{return delegate.exactTransportTime(from,to,departure,vehicle);}
        catch(TravelTimeSnapshot.RangeException unavailable){return Double.POSITIVE_INFINITY;}
    }
    public double getTransportCost(Location from,Location to,double departure,Driver driver,Vehicle vehicle){return delegate.solverDistanceValues(from,to,departure,vehicle).monetaryCost();}
    public double getDistance(Location from,Location to,double departure,Vehicle vehicle){return delegate.solverDistanceValues(from,to,departure,vehicle).distanceMeters();}
    public double getBackwardTransportTime(Location from,Location to,double arrival,Driver driver,Vehicle vehicle){throw inverseUnsupported();}
    public double getBackwardTransportCost(Location from,Location to,double arrival,Driver driver,Vehicle vehicle){throw inverseUnsupported();}
    private UnsupportedOperationException inverseUnsupported(){return new UnsupportedOperationException("Exact temporal solver view uses complete forward tour feasibility; half-hour OD inverse belongs to ModalTransportCosts");}
    public Network getNetwork(){return delegate.getNetwork();}
    public TravelTime getTravelTime(){return delegate.getTravelTime();}
    public LeastCostPathCalculator getRouter(){return delegate.getRouter();}
    public RoadLeg routeLeg(Id<Link> from,Id<Link> to,double departure,org.matsim.vehicles.Vehicle vehicle){return delegate.routeLeg(from,to,departure,vehicle);}
    public void prepareForReplanning(){delegate.prepareForReplanning();}
    public void logSummary(String label){delegate.logSummary(label);}
}
