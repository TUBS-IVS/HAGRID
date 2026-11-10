package hagrid.core.simulation;
import hagrid.core.config.ResolvedHagridConfig;
import hagrid.core.routing.*;
import org.matsim.freight.carriers.*;

/** Compatibility strategy delegates its search and independent old/new assessment to the shared service. */
final class LegacyCarrierPlanOptimizer {
    record Candidate(CarrierPlan plan,int iterations,int visitedZones){}
    private final CarrierRoutingCosts costs;
    private final int iterations;
    private final double maxRouteDuration;
    private final ResolvedHagridConfig config;
    LegacyCarrierPlanOptimizer(CarrierRoutingCosts costs,int iterations,double maxRouteDuration,ResolvedHagridConfig config){
        this.costs=costs;this.iterations=iterations;this.maxRouteDuration=maxRouteDuration;this.config=config;
    }
    long seed() { return HAGRIDRouterUtils.resolveSolverSeed(config); }
    int maxActiveSolvers(){
        return config==null?Integer.MAX_VALUE:config.resources().maxActiveSolvers().orElse(Integer.MAX_VALUE);
    }
    Candidate optimize(Carrier carrier){
        var provider=costs.forCarrier(carrier);
        var result=new CarrierOptimizationService().optimize(carrier,provider,config,seed(),iterations,
                HAGRIDRouterUtils.AlgorithmPhase.REPLANNING,maxRouteDuration,"legacy-live-profile");
        var selection=PlanSelection.choose(carrier.getSelectedPlan(),result,carrier,provider,config,maxRouteDuration);
        if(!selection.completed())throw new IllegalStateException("Invalid replanning result for carrier "+carrier.getId()+": "+result.diagnostics()+selection.candidate().violations());
        var publish=CarrierOptimizationService.prepareForPublication(carrier,selection,result,provider,config,maxRouteDuration);
        CarrierRoutingCosts.logSummary(provider,carrier);
        return new Candidate(publish,result.iterations(),CarrierRoutingZoneMetrics.count(carrier,publish,provider.getNetwork()));
    }
}