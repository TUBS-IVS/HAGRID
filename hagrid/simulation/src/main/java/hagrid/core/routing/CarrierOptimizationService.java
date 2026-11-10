package hagrid.core.routing;
import com.graphhopper.jsprit.core.algorithm.listener.IterationStartsListener;
import com.graphhopper.jsprit.core.util.Solutions;
import hagrid.core.config.ResolvedHagridConfig;
import org.matsim.freight.carriers.*;
import org.matsim.freight.carriers.jsprit.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** One detached search/routing/validation boundary, shared by initial and replanning callers. */
public final class CarrierOptimizationService {
    public record Result(String carrierId,CarrierPlan plan,Set<String> unassignedJobIds,double searchCost,
                         double finalCost,int iterations,CarrierRoutingMetrics metrics,String contextFingerprint,List<String> diagnostics,String searchTerminationReason,Optional<ValidatedCarrierCandidate> validatedCandidate){
        public Result {unassignedJobIds=Set.copyOf(unassignedJobIds);diagnostics=List.copyOf(diagnostics);validatedCandidate=Objects.requireNonNull(validatedCandidate);}
        public Result(String carrierId,CarrierPlan plan,Set<String> unassignedJobIds,double searchCost,
                double finalCost,int iterations,CarrierRoutingMetrics metrics,String contextFingerprint,List<String> diagnostics,
                String searchTerminationReason) {
            this(carrierId,plan,unassignedJobIds,searchCost,finalCost,iterations,metrics,contextFingerprint,diagnostics,
                    searchTerminationReason,Optional.empty());
        }
        public Result withValidatedCandidate(ValidatedCarrierCandidate proof) {
            return new Result(carrierId,plan,unassignedJobIds,searchCost,finalCost,iterations,metrics,contextFingerprint,
                    diagnostics,searchTerminationReason,Optional.of(proof));
        }
        /** Historic/cache results have no observed search termination. */
        public Result(String carrierId,CarrierPlan plan,Set<String> unassignedJobIds,double searchCost,
                      double finalCost,int iterations,CarrierRoutingMetrics metrics,String contextFingerprint,List<String> diagnostics) {
            this(carrierId,plan,unassignedJobIds,searchCost,finalCost,iterations,metrics,contextFingerprint,diagnostics,"UNKNOWN");
        }
    }
    public Result optimize(Carrier carrier,RoutingRequest request,ResolvedHagridConfig config,long seed,int iterations){
        checkInterrupted();
        if(request.carrier()!=carrier)throw new IllegalArgumentException("Request belongs to another carrier");
        if(!request.context().config().equals(config))throw new IllegalArgumentException("Search configuration differs from frozen routing context");
        return search(carrier,new ModalTransportCosts(request),config,seed,iterations,
                HAGRIDRouterUtils.AlgorithmPhase.REPLANNING,config.tourRules().maxRouteDurationSeconds(),request.context().fingerprint(),
                config.tourRules().uTurnPenaltyCost(),request);
    }
    public Result optimize(Carrier carrier,VRPTransportCosts costs,ResolvedHagridConfig config,long seed,int iterations,
                           HAGRIDRouterUtils.AlgorithmPhase phase,double routeLimit,String fingerprint){
        return search(carrier,costs,config,seed,iterations,phase,routeLimit,fingerprint,
                config==null?0:config.tourRules().uTurnPenaltyCost(),null);
    }

    /** Preserve the existing initial/offline search and activity-cost policy while sharing validation. */
    public Result optimizeInitial(Carrier carrier,VRPTransportCosts costs,int iterations,int routeLimit,double uTurnPenalty){
        return search(carrier,costs,null,HAGRIDRouterUtils.resolveSolverSeed(),iterations,HAGRIDRouterUtils.AlgorithmPhase.INITIAL,
                routeLimit,"initial-native-profile",uTurnPenalty,null);
    }

    public Result optimizeInitial(Carrier carrier,VRPTransportCosts costs,int iterations,int routeLimit,
                                  double uTurnPenalty,ResolvedHagridConfig.Resources resources) {
        return search(carrier,costs,null,HAGRIDRouterUtils.resolveSolverSeed(),iterations,HAGRIDRouterUtils.AlgorithmPhase.INITIAL,
                routeLimit,"initial-native-profile",uTurnPenalty,null,Objects.requireNonNull(resources));
    }
    /** Explicit root for configured initial searches, also used by native LMD preprocessing. */
    public Result optimizeInitial(Carrier carrier,VRPTransportCosts costs,int iterations,int routeLimit,
                                  double uTurnPenalty,ResolvedHagridConfig.Resources resources,long seed) {
        return search(carrier,costs,null,seed,iterations,HAGRIDRouterUtils.AlgorithmPhase.INITIAL,
                routeLimit,"initial-native-profile",uTurnPenalty,null,Objects.requireNonNull(resources));
    }
    private Result search(Carrier carrier,VRPTransportCosts costs,ResolvedHagridConfig config,long seed,int iterations,
                          HAGRIDRouterUtils.AlgorithmPhase phase,double routeLimit,String fingerprint,double uTurnPenalty,RoutingRequest routingRequest){
        return search(carrier,costs,config,seed,iterations,phase,routeLimit,fingerprint,uTurnPenalty,routingRequest,
                config==null ? ResolvedHagridConfig.Resources.defaults() : config.resources());
    }


    private Result search(Carrier carrier,VRPTransportCosts costs,ResolvedHagridConfig config,long seed,int iterations,
                          HAGRIDRouterUtils.AlgorithmPhase phase,double routeLimit,String fingerprint,double uTurnPenalty,
                          RoutingRequest routingRequest,ResolvedHagridConfig.Resources resources){
        checkInterrupted();
        if(iterations<0 || (phase!=HAGRIDRouterUtils.AlgorithmPhase.INITIAL && iterations==0))throw new IllegalArgumentException("Solver iterations must be nonnegative for INITIAL and positive otherwise");
        if(!Double.isFinite(uTurnPenalty)||uTurnPenalty<0)throw new IllegalArgumentException("Invalid U-turn penalty");
        if(uTurnPenalty>0 && costs instanceof NativeLinkTransportCosts nativeCosts)
            costs=new NativeTurnTransportCosts(nativeCosts,uTurnPenalty);
        long start=System.nanoTime();String validationInput=CarrierInputFingerprint.of(carrier);var worker=CarrierSearchInput.copy(carrier);
        var builder=MatsimJspritFactory.createRoutingProblemBuilder(worker,costs.getNetwork());
        builder.setRoutingCost(costs instanceof ModalTransportCosts modal ? modal.solverCosts() : costs);
        if(phase==HAGRIDRouterUtils.AlgorithmPhase.REPLANNING)builder.setActivityCosts(new HagridActivityCosts(5));
        var problem=builder.build();
        checkInterrupted();
        var count=new AtomicInteger();
        long prepared,searched;
        com.graphhopper.jsprit.core.problem.solution.VehicleRoutingProblemSolution solution;
        try (var workers = new SolverWorkers(resources.internalSolverThreads())) {
            var algorithm=config==null
                    ? (phase==HAGRIDRouterUtils.AlgorithmPhase.REPLANNING
                        ? HAGRIDRouterUtils.configureLegacyReplanningAlgorithm(problem,iterations,routeLimit)
                        : HAGRIDRouterUtils.configureInitialAlgorithm(problem,iterations,
                            costs.getNetwork(),uTurnPenalty,(int)routeLimit,resources.internalSolverThreads(),workers.executor(),seed))
                    :HAGRIDRouterUtils.configureAlgorithm(problem,config,seed,iterations,phase,workers.executor());
            // jsprit skips IterationEnds when its criterion breaks after executing the strategy.
            algorithm.addListener((IterationStartsListener)(i,p,s)->count.incrementAndGet());
            prepared=System.nanoTime();
            solution=Solutions.bestOf(algorithm.searchSolutions());searched=System.nanoTime();
        }
        checkInterrupted();
        if(solution==null)throw new IllegalStateException("No jsprit solution for carrier "+carrier.getId());
        var unassigned=new TreeSet<String>();solution.getUnassignedJobs().forEach(job->unassigned.add(job.getId()));
        var plan=MatsimJspritFactory.createPlan(solution);plan.setCarrier(worker);hagrid.core.routing.CarrierRoadRouter.routePlan(plan,costs);
        checkInterrupted();
        long routed=System.nanoTime();
        if(!validationInput.equals(CarrierInputFingerprint.of(carrier)))throw new IllegalStateException("Carrier changed during search");
        var validation=PlanValidator.validate(carrier,plan,costs,config,routeLimit,uTurnPenalty);
        if(!validationInput.equals(CarrierInputFingerprint.of(carrier)))throw new IllegalStateException("Carrier changed during validation");
        checkInterrupted();
        long end=System.nanoTime();
        var diagnostics=new ArrayList<>(validation.violations());if(!unassigned.isEmpty())diagnostics.add("Unassigned jobs "+unassigned);
        String searchTerminationReason=count.get()>=iterations?"MAXIMUM":"LEGACY_NO_IMPROVEMENT";
        double finalCost=diagnostics.isEmpty()?validation.finalCost():Double.POSITIVE_INFINITY;
        var phases=new CarrierRoutingMetrics.PhaseTimings((prepared-start)/1e9,(searched-prepared)/1e9,
                (routed-searched)/1e9,(end-routed)/1e9);
        var execution=new CarrierRoutingMetrics.Execution(fingerprint,diagnostics.isEmpty()?"VALIDATED":"INVALID",count.get(),
                Optional.of(phases),solution.getCost(),finalCost,plan.getScore(),searchTerminationReason);
        var metrics=new CarrierRoutingMetrics(carrier.getId().toString(),String.valueOf(carrier.getAttributes().getAttribute("provider")),
                carrier.getServices().size(),carrier.getShipments().size(),carrier.getServices().values().stream().mapToInt(CarrierService::getCapacityDemand).sum()
                +carrier.getShipments().values().stream().mapToInt(CarrierShipment::getSize).sum(),0,0,"UNCLASSIFIED","SERVICE",
                String.valueOf(carrier.getAttributes().getAttribute("carrierType")),(searched-start)/1e9,(end-searched)/1e9,(end-start)/1e9,
                Optional.of(execution));
        if(routingRequest!=null)metrics=metrics.withRoutingStatistics(routingRequest.context().statistics());
        var result=new Result(carrier.getId().toString(),plan,unassigned,solution.getCost(),diagnostics.isEmpty()?validation.finalCost():Double.POSITIVE_INFINITY,
                count.get(),metrics,fingerprint,diagnostics,searchTerminationReason);
        if(routingRequest!=null && diagnostics.isEmpty())result=result.withValidatedCandidate(
                ValidatedCarrierCandidate.checked(plan,carrier,routingRequest,config,routeLimit,uTurnPenalty,validation,validationInput));
        return result;
    }
    public record Publication(CarrierPlan plan,PlanValidator.Validation validation,ValidatedCarrierCandidate proof) {
        public void requireCurrent(Carrier canonical,RoutingRequest request,ResolvedHagridConfig config) {
            if(!proof.matches(canonical,request,config,config.tourRules().maxRouteDurationSeconds(),config.tourRules().uTurnPenaltyCost())
                    || !proof.matchesPlan(plan))throw new IllegalStateException("Candidate changed before publication");
        }
    }
    /** Reuse only an unchanged certified candidate; an old winner is freshly routed and checked. */
    public static Publication prepareForPublication(Carrier carrier,PlanSelection.Selection selection,Result result,
            RoutingRequest request,ResolvedHagridConfig config) {
        checkInterrupted();
        if(request.carrier()!=carrier || !request.context().config().equals(config)
                || !carrier.getId().toString().equals(result.carrierId())
                || !request.context().fingerprint().equals(result.contextFingerprint()))
            throw new IllegalArgumentException("Publication request/configuration/context mismatch");
        if(!selection.completed())throw new IllegalStateException("Invalid candidate cannot be published");
        var proof=selection.candidateProof().orElse(null);
        if(selection.selected()==result.plan() && proof!=null
                && proof.matches(carrier,request,config,config.tourRules().maxRouteDurationSeconds(),config.tourRules().uTurnPenaltyCost())
                && proof.matchesPlan(result.plan())) {
            var publication=new Publication(proof.materialize(carrier),proof.validation(),proof);
            publication.requireCurrent(carrier,request,config);return publication;
        }
        var checked=prepareCheckedPublication(carrier,selection,result,new ModalTransportCosts(request),config,
                config.tourRules().maxRouteDurationSeconds());
        var certified=ValidatedCarrierCandidate.checked(checked.plan(),carrier,request,config,
                config.tourRules().maxRouteDurationSeconds(),config.tourRules().uTurnPenaltyCost(),
                checked.validation(),checked.inputFingerprint());
        var publication=new Publication(certified.materialize(carrier),certified.validation(),certified);
        publication.requireCurrent(carrier,request,config);return publication;
    }
    /** Refresh a detached winner without publishing a plan worse than the selected stored roads. */
    public static CarrierPlan prepareForPublication(Carrier carrier,PlanSelection.Selection selection,Result result,
                                                     VRPTransportCosts costs,ResolvedHagridConfig config,double routeLimit){
        return prepareCheckedPublication(carrier,selection,result,costs,config,routeLimit).plan();
    }
    private record CheckedPublication(CarrierPlan plan,PlanValidator.Validation validation,String inputFingerprint) {}
    private static CheckedPublication prepareCheckedPublication(Carrier carrier,PlanSelection.Selection selection,Result result,
                                                     VRPTransportCosts costs,ResolvedHagridConfig config,double routeLimit){
        checkInterrupted();
        if(!selection.completed())throw new IllegalStateException("Invalid candidate cannot be published");
        String input=CarrierInputFingerprint.of(carrier);
        double ceiling=selection.selected()==result.plan()?selection.candidate().finalCost()
                :selection.previous().orElseThrow().finalCost();
        var stored=copyForRouting(selection.selected(),carrier);
        var plan=copyForRouting(stored,carrier);
        PlanValidator.Validation refreshed;
        try {
            CarrierRoadRouter.routePlan(plan,costs);
            refreshed=PlanValidator.validate(carrier,plan,costs,config,routeLimit);
        } catch(TravelTimeSnapshot.RangeException|NoRouteException infeasibleRerouting) {
            refreshed=new PlanValidator.Validation(false,Set.of(),List.of(infeasibleRerouting.getMessage()),Double.POSITIVE_INFINITY);
        }
        if(!refreshed.valid()||refreshed.finalCost()>ceiling+1e-6){
            // A locally shortest leg can spoil a complete non-FIFO tour. Retain the selected
            // stored roads, but refresh their times and independently check them before publication.
            plan=stored;CarrierRoadRouter.refreshStoredRoads(plan,costs);
            refreshed=PlanValidator.validate(carrier,plan,costs,config,routeLimit);
        }
        checkInterrupted();
        if(!input.equals(CarrierInputFingerprint.of(carrier)))throw new IllegalStateException("Carrier changed during publication validation");
        if(!refreshed.valid()||refreshed.finalCost()>ceiling+1e-6)
            throw new IllegalStateException("Invalid or worse final publication: "+refreshed.violations());
        return new CheckedPublication(plan,refreshed,input);
    }
    private static CarrierPlan copyForRouting(CarrierPlan source,Carrier carrier) {
        // Keep native infinite-fleet vehicle IDs; certification performs the independent deep copy.
        var tours=new ArrayList<ScheduledTour>();
        for(var scheduled:source.getScheduledTours())tours.add(ScheduledTour.newInstance(
                scheduled.getTour().duplicate(),scheduled.getVehicle(),scheduled.getDeparture()));
        var plan=new CarrierPlan(tours);plan.setCarrier(CarrierSearchInput.copy(carrier));plan.setScore(source.getScore());
        source.getAttributes().getAsMap().forEach(plan.getAttributes()::putAttribute);return plan;
    }
    private static void checkInterrupted(){
        if(Thread.currentThread().isInterrupted())
            throw new java.util.concurrent.CancellationException("Carrier optimization interrupted");
    }    /** A section search stops at the native solution boundary; expansion validates original jobs separately. */
    public record SectionSearchResult(com.graphhopper.jsprit.core.problem.solution.VehicleRoutingProblemSolution solution,
            Map<com.graphhopper.jsprit.core.problem.solution.route.VehicleRoute,hagrid.core.routing.sections.SectionSearchState.Choice> evaluations,int iterations,long numericRetryEvaluations,long numericRetryExhaustions) {
        public SectionSearchResult {evaluations=Map.copyOf(evaluations);}
        public SectionSearchResult(com.graphhopper.jsprit.core.problem.solution.VehicleRoutingProblemSolution solution,Map<com.graphhopper.jsprit.core.problem.solution.route.VehicleRoute,hagrid.core.routing.sections.SectionSearchState.Choice> evaluations,int iterations){this(solution,evaluations,iterations,0,0);}
    }
    public SectionSearchResult searchSections(hagrid.core.routing.sections.SectionCatalog catalog,
            org.matsim.api.core.v01.network.Network network,hagrid.core.routing.sections.SectionRoadCosts roads,
            double routeLimit,double driverLimit,double penalty,long seed,hagrid.core.routing.sections.SectionSearchPolicy policy) {
        return searchSectionsInternal(catalog,network,roads,routeLimit,driverLimit,penalty,seed,Objects.requireNonNull(policy),0,ResolvedHagridConfig.Resources.defaults(),false);
    }
    /** Ordinary section search retains construction-only mode, size-based ruin and early termination. */
    public SectionSearchResult searchSections(hagrid.core.routing.sections.SectionCatalog catalog,
            org.matsim.api.core.v01.network.Network network,hagrid.core.routing.sections.SectionRoadCosts roads,
            double routeLimit,double driverLimit,double penalty,long seed,int iterations,ResolvedHagridConfig.Resources resources) {
        return searchSections(catalog,network,roads,routeLimit,driverLimit,penalty,seed,iterations,resources,false);
    }
    public SectionSearchResult searchSections(hagrid.core.routing.sections.SectionCatalog catalog,
            org.matsim.api.core.v01.network.Network network,hagrid.core.routing.sections.SectionRoadCosts roads,
            double routeLimit,double driverLimit,double penalty,long seed,int iterations,ResolvedHagridConfig.Resources resources,boolean fastRegret) {
        if(iterations<0)throw new IllegalArgumentException("Section iterations must be nonnegative");
        Objects.requireNonNull(resources);
        if(fastRegret && resources.internalSolverThreads()!=1)throw new IllegalArgumentException("routing.sectionFastRegret requires resources.internalSolverThreads=1");
        if(fastRegret && catalog.originalCarrier().getCarrierCapabilities().getFleetSize()!=org.matsim.freight.carriers.CarrierCapabilities.FleetSize.INFINITE)throw new IllegalArgumentException("routing.sectionFastRegret requires INFINITE fleet");
        return searchSectionsInternal(catalog,network,roads,routeLimit,driverLimit,penalty,seed,null,iterations,resources,fastRegret);
    }
    private SectionSearchResult searchSectionsInternal(hagrid.core.routing.sections.SectionCatalog catalog,
            org.matsim.api.core.v01.network.Network network,hagrid.core.routing.sections.SectionRoadCosts roads,
            double routeLimit,double driverLimit,double penalty,long seed,hagrid.core.routing.sections.SectionSearchPolicy policy,int iterations,ResolvedHagridConfig.Resources resources,boolean fastRegret) {
        catalog.requireUnchanged();
        var p=hagrid.core.routing.sections.SectionProblemFactory.build(catalog,network,roads).problem();
        var state=new hagrid.core.routing.sections.SectionSearchState(catalog,roads,routeLimit,driverLimit,penalty);
        var count=new AtomicInteger();com.graphhopper.jsprit.core.problem.solution.VehicleRoutingProblemSolution solution;
        try(var workers=new SolverWorkers(resources.internalSolverThreads())) {
            var algorithm=policy==null?hagrid.core.routing.sections.SectionInsertionAdapter.configureNormal(p,state,seed,iterations,resources.internalSolverThreads(),workers.executor(),fastRegret):HAGRIDRouterUtils.configureSectionAlgorithm(p,state,seed,policy);
            algorithm.addListener((IterationStartsListener)(i,problem,solutions)->count.incrementAndGet());
            solution=Solutions.bestOf(algorithm.searchSolutions());
        }
        if(solution==null || !Double.isFinite(solution.getCost()))throw new IllegalStateException("No feasible native section solution");
        var evaluations=new IdentityHashMap<com.graphhopper.jsprit.core.problem.solution.route.VehicleRoute,hagrid.core.routing.sections.SectionSearchState.Choice>();
        for(var route:solution.getRoutes()) {var choice=state.evaluate(route);if(!choice.evaluation().feasible() || Double.compare(choice.departure(),route.getDepartureTime())!=0)throw new IllegalStateException("Invalid or unscheduled native section solution");evaluations.put(route,choice);}
        catalog.requireUnchanged();return new SectionSearchResult(solution,evaluations,count.get(),state.numericRetryEvaluations(),state.numericRetryExhaustions());
    }
}

