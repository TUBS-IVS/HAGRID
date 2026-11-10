package hagrid.core.routing;
import hagrid.core.config.ResolvedHagridConfig;
import org.matsim.freight.carriers.*;
import org.matsim.freight.carriers.jsprit.VRPTransportCosts;
import java.util.*;

/** Both plans are replayed under the same profile; valid ties keep the existing plan. No mutations. */
public final class PlanSelection {
    public record Selection(CarrierPlan selected,Optional<PlanValidator.Validation> previous,
            PlanValidator.Validation candidate,boolean completed,Optional<ValidatedCarrierCandidate> candidateProof){
        public Selection(CarrierPlan selected,Optional<PlanValidator.Validation> previous,PlanValidator.Validation candidate,boolean completed) {
            this(selected,previous,candidate,completed,Optional.empty());
        }
    }
    private PlanSelection(){}
    public static Selection choose(CarrierPlan previous,CarrierOptimizationService.Result candidate,Carrier carrier,RoutingRequest request,ResolvedHagridConfig config){
        if(!request.context().fingerprint().equals(candidate.contextFingerprint()))throw new IllegalArgumentException("Candidate routing context mismatch");
        if(!carrier.getId().toString().equals(candidate.carrierId()))throw new IllegalArgumentException("Candidate carrier mismatch");
        var costs=new ModalTransportCosts(request);double limit=config.tourRules().maxRouteDurationSeconds();
        var old=previous==null?Optional.<PlanValidator.Validation>empty():Optional.of(PlanValidator.validate(carrier,previous,costs,config,limit));
        var proof=candidate.validatedCandidate().filter(p->p.matches(carrier,request,config,limit,config.tourRules().uTurnPenaltyCost())
                && p.matchesPlan(candidate.plan()));
        var fresh=proof.isPresent()?proof.get().validation():PlanValidator.validate(carrier,candidate.plan(),costs,config,limit);
        return select(previous,candidate,old,fresh,proof);
    }
    public static Selection choose(CarrierPlan previous,CarrierOptimizationService.Result candidate,Carrier carrier,VRPTransportCosts costs,ResolvedHagridConfig config,double routeLimit){
        if(!carrier.getId().toString().equals(candidate.carrierId()))throw new IllegalArgumentException("Candidate carrier mismatch");
        var old=previous==null?Optional.<PlanValidator.Validation>empty():Optional.of(PlanValidator.validate(carrier,previous,costs,config,routeLimit));
        var fresh=PlanValidator.validate(carrier,candidate.plan(),costs,config,routeLimit);
        return select(previous,candidate,old,fresh,Optional.empty());
    }
    private static Selection select(CarrierPlan previous,CarrierOptimizationService.Result candidate,
            Optional<PlanValidator.Validation> old,PlanValidator.Validation fresh,Optional<ValidatedCarrierCandidate> proof){
        boolean complete=fresh.valid() && candidate.unassignedJobIds().isEmpty() && candidate.diagnostics().isEmpty();
        CarrierPlan selected=complete?candidate.plan():null;
        if(old.isPresent()&&old.get().valid()&&(!complete||old.get().finalCost()<=fresh.finalCost()+1e-6))selected=previous;
        return new Selection(selected,old,fresh,complete,proof);
    }
}