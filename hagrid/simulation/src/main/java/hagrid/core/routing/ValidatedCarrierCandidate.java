package hagrid.core.routing;

import hagrid.core.config.ResolvedHagridConfig;
import org.matsim.freight.carriers.*;
import java.util.Objects;

/** Private independent plan template, bound to the exact jobs/context/evaluation rules checked. */
public final class ValidatedCarrierCandidate {
    private final CarrierPlan template;
    private final String inputFingerprint,planFingerprint,contextFingerprint,rulesFingerprint;
    private final PlanValidator.Validation validation;
    private ValidatedCarrierCandidate(CarrierPlan plan,Carrier canonical,RoutingRequest request,
            ResolvedHagridConfig config,double routeLimit,double uTurn,PlanValidator.Validation validation,String checkedInput) {
        if(!Objects.equals(checkedInput,CarrierInputFingerprint.of(canonical)))
            throw new IllegalStateException("Carrier changed after validation");
        if(!validation.valid())throw new IllegalArgumentException("Invalid candidate: "+validation.violations());
        if(request.carrier()!=canonical || !request.context().config().equals(config))
            throw new IllegalArgumentException("Candidate request/configuration mismatch");
        template=CarrierPlanCopies.copy(plan,canonical);
        inputFingerprint=CarrierInputFingerprint.of(canonical);
        if(!checkedInput.equals(inputFingerprint))throw new IllegalStateException("Carrier changed during candidate copy");
        planFingerprint=CarrierPlanFingerprint.of(template);
        contextFingerprint=request.context().fingerprint();
        rulesFingerprint=rules(config,routeLimit,uTurn);this.validation=validation;
    }
    static ValidatedCarrierCandidate capture(CarrierPlan plan,Carrier canonical,RoutingRequest request,
            ResolvedHagridConfig config,double routeLimit,double uTurn) {
        String input=CarrierInputFingerprint.of(canonical);
        return checked(plan,canonical,request,config,routeLimit,uTurn,
                PlanValidator.validate(canonical,plan,new ModalTransportCosts(request),config,routeLimit,uTurn),input);
    }
    /** Package-only trusted boundary: search and archive reload already performed full road validation. */
    static ValidatedCarrierCandidate checked(CarrierPlan plan,Carrier canonical,RoutingRequest request,
            ResolvedHagridConfig config,double routeLimit,double uTurn,PlanValidator.Validation validation,String checkedInput) {
        return new ValidatedCarrierCandidate(plan,canonical,request,config,routeLimit,uTurn,validation,checkedInput);
    }
    public boolean matches(Carrier canonical,RoutingRequest request,ResolvedHagridConfig config,double routeLimit,double uTurn) {
        request.context().requireOpen();
        return request.carrier()==canonical && request.context().config().equals(config)
                && inputFingerprint.equals(CarrierInputFingerprint.of(canonical))
                && contextFingerprint.equals(request.context().fingerprint())
                && rulesFingerprint.equals(rules(config,routeLimit,uTurn))
                && planFingerprint.equals(CarrierPlanFingerprint.of(template));
    }
    boolean matchesPlan(CarrierPlan plan){return planFingerprint.equals(CarrierPlanFingerprint.of(plan));}
    public PlanValidator.Validation validation(){return validation;}
    public CarrierPlan materialize(Carrier canonical) {
        if(!inputFingerprint.equals(CarrierInputFingerprint.of(canonical)))
            throw new IllegalArgumentException("Candidate canonical input changed");
        return CarrierPlanCopies.copy(template,canonical);
    }
    private static String rules(ResolvedHagridConfig config,double routeLimit,double uTurn) {
        Objects.requireNonNull(config);
        if(!Double.isFinite(routeLimit)||routeLimit<=0||!Double.isFinite(uTurn)||uTurn<0)
            throw new IllegalArgumentException("Invalid candidate evaluation bounds");
        return RoutingData.hashValues("validated-candidate-v1",config,routeLimit,uTurn);
    }
}
