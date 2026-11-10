package hagrid.core.simulation.scoring;

import hagrid.core.config.ResolvedHagridConfig;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Network;
import org.matsim.freight.carriers.*;
import java.util.*;
import static hagrid.core.simulation.scoring.CarrierExecutionLedger.*;

/** Scores observed durations per tour; planned timestamps are never execution evidence. */
public final class CarrierCostCalculator {
    public record Evaluation(double score, CarrierCostBreakdown costs) {}
    private CarrierCostCalculator() {}
    public static Evaluation calculate(Carrier carrier, CarrierExecution execution, ResolvedHagridConfig config) {
        return calculate(carrier,execution,config,null);
    }
    public static Evaluation calculate(Carrier carrier, CarrierExecution execution, ResolvedHagridConfig config, Network network) {
        return calculate(carrier,execution,config,network,null);
    }
    public static Evaluation calculate(Carrier carrier, CarrierExecution execution, ResolvedHagridConfig config, Network network,
            hagrid.core.routing.SectionBindingCompanion.TurnEvidence evidence) {
        boolean section=carrier.getAttributes().getAttribute(hagrid.core.routing.SectionBindingCompanion.MARKER)!=null;
        if(section && evidence==null)throw new IllegalStateException("Missing verified section turn evidence for "+carrier.getId());
        if(section){evidence.requireOwner(carrier);evidence.requireNetwork(network);}
        return calculateVerified(carrier,execution,config,network,evidence);
    }
    public static Evaluation calculateBound(Carrier carrier,CarrierExecution execution,ResolvedHagridConfig config,Network network,
            hagrid.core.routing.SectionBindingCompanion.BoundContext binding) {
        var evidence=binding==null?null:binding.forCarrier(carrier,network);
        if(carrier.getAttributes().getAttribute(hagrid.core.routing.SectionBindingCompanion.MARKER)!=null && evidence==null)throw new IllegalStateException("Missing verified section turn evidence for "+carrier.getId());
        return calculateVerified(carrier,execution,config,network,evidence);
    }
    private static Evaluation calculateVerified(Carrier carrier,CarrierExecution execution,ResolvedHagridConfig config,Network network,
            hagrid.core.routing.SectionBindingCompanion.TurnEvidence evidence) {
        boolean section=carrier.getAttributes().getAttribute(hagrid.core.routing.SectionBindingCompanion.MARKER)!=null;
        if (!execution.complete() || !execution.violations().isEmpty())
            throw new IllegalArgumentException("Incomplete carrier execution " + carrier.getId() + ": " + execution.violations());
        var scheduled=new HashMap<Id<Tour>,ScheduledTour>();
        if (carrier.getSelectedPlan()!=null) carrier.getSelectedPlan().getScheduledTours().forEach(t -> scheduled.put(t.getTour().getId(),t));
        var settings=config.scoring();
        double fixed=0,distance=0,time=0,activity=0,overtime=0,windows=0,meters=0,seconds=0;
        int uTurns=0;var sectionTurns=new int[2]; var charged=new HashSet<String>();
        for (var tour:execution.tours()) {
            var planned=requireTour(scheduled,tour.tourId());
            double shift=duration(tour.startSeconds(),tour.endSeconds());
            if (shift>settings.maxWorkDurationSeconds()) overtime+=settings.overtimePenalty();
            if (carrier.getCarrierCapabilities().getFleetSize()==CarrierCapabilities.FleetSize.INFINITE
                    || charged.add(planned.getVehicle().getId().toString())) fixed+=planned.getVehicle().getType().getCostInformation().getFixedCosts();
        }
        var sectionPrevious=new HashMap<Id<Tour>,Id<org.matsim.api.core.v01.network.Link>>();
        for (var leg:execution.legs()) {
            var tour=requireTour(scheduled,leg.tourId()); var rates=tour.getVehicle().getType().getCostInformation();
            double travel=duration(leg.departureSeconds(),leg.arrivalSeconds()); seconds+=travel; time+=travel*rates.getCostsPerSecond();
            if (network==null && !leg.enteredLinks().isEmpty() && (rates.getCostsPerMeter()!=0 || config.tourRules().uTurnPenaltyCost()!=0))
                throw new IllegalArgumentException("Network needed for observed link distance/U-turns");
            if(section && network==null)throw new IllegalArgumentException("Network needed for section execution");
            if(section && (leg.departureLinkId()==null || leg.arrivalLinkId()==null))throw new IllegalStateException("Missing section leg boundary link");
            if(section && leg.departureLinkId()!=null) {
                requireLink(network,leg.departureLinkId());
                var old=sectionPrevious.put(leg.tourId(),leg.departureLinkId());
                countSectionTransition(evidence,old,leg.departureLinkId(),sectionTurns);
            }
            org.matsim.api.core.v01.network.Link previous=network==null || leg.departureLinkId()==null
                    ? null : requireLink(network,leg.departureLinkId());
            for (var id:leg.enteredLinks()) {
                if (network==null) continue;
                var link=network.getLinks().get(id);
                if (link==null) throw new IllegalArgumentException("Missing executed road link " + id);
                meters+=link.getLength(); distance+=link.getLength()*rates.getCostsPerMeter();
                if(section) { var old=sectionPrevious.put(leg.tourId(),id);countSectionTransition(evidence,old,id,sectionTurns); }
                else if(isReverse(previous,link))uTurns++;
                previous=link;
            }
            // Native short legs can end without a LinkEnter. Apply the recorded endpoint classification,
            // but do not invent unobserved distance or intermediate links.
            if(network!=null && leg.arrivalLinkId()!=null) {
                var arrival=requireLink(network,leg.arrivalLinkId());
                if(section) { var old=sectionPrevious.put(leg.tourId(),leg.arrivalLinkId());countSectionTransition(evidence,old,leg.arrivalLinkId(),sectionTurns); }
                else if(isReverse(previous,arrival))uTurns++;
            }
        }
        for (var observed:execution.activities()) {
            requireTour(scheduled,observed.tourId());
            double elapsed=duration(observed.startSeconds(),observed.endSeconds());
            activity+=elapsed*settings.activityCostPerSecond();
            String role=observed.jobId(); double end;
            if (role.startsWith("service:")) {
                var job=carrier.getServices().get(Id.create(role.substring(8),CarrierService.class));
                if (job==null) throw new IllegalArgumentException("Unknown executed service " + role);
                end=job.getServiceStaringTimeWindow().getEnd();
            } else {
                boolean pickup=role.startsWith("pickup:");
                if (!pickup && !role.startsWith("delivery:")) throw new IllegalArgumentException("Unknown activity role " + role);
                var job=carrier.getShipments().get(Id.create(role.substring(pickup?7:9),CarrierShipment.class));
                if (job==null) throw new IllegalArgumentException("Unknown executed shipment " + role);
                end=(pickup?job.getPickupStartingTimeWindow():job.getDeliveryStartingTimeWindow()).getEnd();
            }
            windows+=Math.max(0,observed.startSeconds()-end)*settings.timeWindowPenaltyPerSecond();
        }
        var costs=new CarrierCostBreakdown(fixed,distance,time,activity,overtime,windows,meters,seconds,uTurns+sectionTurns[0],sectionTurns[1]);
        return new Evaluation(-costs.totalCosts()-costs.chargedReversalCount()*config.tourRules().uTurnPenaltyCost(),costs);
    }
    private static void countSectionTransition(hagrid.core.routing.SectionBindingCompanion.TurnEvidence evidence,Id<org.matsim.api.core.v01.network.Link> from,Id<org.matsim.api.core.v01.network.Link> to,int[] counts) {
        if(from==null||from.equals(to))return;
        var classification=evidence.classification(from,to);
        if(evidence.turnPolicy().charges(classification))counts[classification==hagrid.core.routing.sections.SectionRoadCosts.TurnClassification.TURN?0:1]++;
    }
    private static org.matsim.api.core.v01.network.Link requireLink(Network network,Id<org.matsim.api.core.v01.network.Link> id) {
        var link=network.getLinks().get(id);if(link==null)throw new IllegalArgumentException("Missing executed road link "+id);return link;
    }
    private static boolean isReverse(org.matsim.api.core.v01.network.Link from,org.matsim.api.core.v01.network.Link to) {
        return hagrid.core.routing.OriginalRoadReverse.roadTransition(from,to);
    }
    private static ScheduledTour requireTour(Map<Id<Tour>,ScheduledTour> scheduled,Id<Tour> id) {
        var tour=scheduled.get(id); if(tour==null) throw new IllegalArgumentException("Unknown executed tour " + id); return tour;
    }
    private static double duration(double start,double end) {
        if (!Double.isFinite(start)||!Double.isFinite(end)||end<start) throw new IllegalArgumentException("Invalid observed interval");
        return end-start;
    }
}
