package hagrid.core.routing.sections;

import com.graphhopper.jsprit.core.algorithm.VehicleRoutingAlgorithm;
import com.graphhopper.jsprit.core.algorithm.acceptor.SchrimpfAcceptance;
import com.graphhopper.jsprit.core.algorithm.box.Jsprit;
import com.graphhopper.jsprit.core.algorithm.recreate.ActivityInsertionCostsCalculator;
import com.graphhopper.jsprit.core.algorithm.ruin.JobNeighborhoodsFactory;
import com.graphhopper.jsprit.core.algorithm.ruin.distance.AvgServiceAndShipmentDistance;
import com.graphhopper.jsprit.core.algorithm.state.StateManager;
import com.graphhopper.jsprit.core.problem.VehicleRoutingProblem;
import com.graphhopper.jsprit.core.problem.constraint.*;
import com.graphhopper.jsprit.core.problem.misc.JobInsertionContext;
import com.graphhopper.jsprit.core.problem.solution.VehicleRoutingProblemSolution;
import com.graphhopper.jsprit.core.problem.solution.route.VehicleRoute;
import com.graphhopper.jsprit.core.problem.solution.route.activity.TourActivity;
import com.graphhopper.jsprit.core.util.Solutions;
import java.util.*;

/** Common native callbacks; all physical timing and costs come from complete section replay. */
public final class SectionInsertionAdapter implements HardActivityConstraint, ActivityInsertionCostsCalculator {
 private final SectionSearchState state;private final VehicleRoutingProblem problem;
 public SectionInsertionAdapter(SectionSearchState state,VehicleRoutingProblem problem){this.state=state;this.problem=problem;}
 private List<String> inserted(JobInsertionContext facts,TourActivity inserted){var order=new ArrayList<>(SectionSearchState.order(facts.getRoute()));order.add(facts.getActivityContext().getInsertionIndex(),((TourActivity.JobActivity)inserted).getJob().getId());return order;}
 private SectionSearchState.Choice candidate(JobInsertionContext facts,TourActivity inserted){return state.evaluate(inserted(facts,inserted),state.vehicle(facts.getNewVehicle()),facts.getNewDepTime());}
 @Override public ConstraintsStatus fulfilled(JobInsertionContext facts,TourActivity prev,TourActivity inserted,TourActivity next,double end){return candidate(facts,inserted).evaluation().feasible()?ConstraintsStatus.FULFILLED:ConstraintsStatus.NOT_FULFILLED;}
 @Override public double getCosts(JobInsertionContext facts,TourActivity previous,TourActivity next,TourActivity inserted,double end){
  var route=facts.getRoute();var proposed=candidate(facts,inserted).evaluation();if(!proposed.feasible())return Double.POSITIVE_INFINITY;
  double old=route.isEmpty()?0:state.evaluate(route).evaluation().variableCost();if(!Double.isFinite(old))old=0;
  double oldFixed=route.isEmpty()?0:route.getVehicle().getType().getVehicleCostParams().fix;
  double newFixed=facts.getNewVehicle().getType().getVehicleCostParams().fix;
  return proposed.variableCost()-old+newFixed-oldFixed-nativeAccess(facts);
 }
 private double nativeAccess(JobInsertionContext f){
  var r=f.getRoute();if(r.isEmpty())return 0;var first=r.getActivities().getFirst();var last=r.getActivities().getLast();var t=problem.getTransportCosts();var v=f.getNewVehicle();
  double a=t.getTransportCost(v.getStartLocation(),first.getLocation(),f.getNewDepTime(),f.getNewDriver(),v)-t.getTransportCost(r.getStart().getLocation(),first.getLocation(),r.getDepartureTime(),r.getDriver(),r.getVehicle());
  if(v.isReturnToDepot())a+=t.getTransportCost(last.getLocation(),v.getEndLocation(),0,f.getNewDriver(),v)-t.getTransportCost(last.getLocation(),r.getEnd().getLocation(),0,r.getDriver(),r.getVehicle());return a;
 }
 public static VehicleRoutingAlgorithm configure(VehicleRoutingProblem problem,SectionSearchState state,long seed,SectionSearchPolicy policy){
  Objects.requireNonNull(policy);return configureInternal(problem,state,seed,policy.exactIterations(),policy.radialShare(),policy.randomShare(),true,policy.fastRegret(),1,null);
 }
 public static VehicleRoutingAlgorithm configureNormal(VehicleRoutingProblem problem,SectionSearchState state,long seed,int iterations,int threads,java.util.concurrent.ExecutorService executor){
  return configureNormal(problem,state,seed,iterations,threads,executor,false);
 }
 public static VehicleRoutingAlgorithm configureNormal(VehicleRoutingProblem problem,SectionSearchState state,long seed,int iterations,int threads,java.util.concurrent.ExecutorService executor,boolean fastRegret){
  if(iterations<0||threads<1||(threads>1&&executor==null))throw new IllegalArgumentException("Invalid normal section search resources");
  if(fastRegret && threads!=1)throw new IllegalArgumentException("routing.sectionFastRegret requires resources.internalSolverThreads=1");
  if(fastRegret && problem.getFleetSize()!=VehicleRoutingProblem.FleetSize.INFINITE)throw new IllegalArgumentException("routing.sectionFastRegret requires INFINITE fleet");
  int count=problem.getJobs().size();double radial=count>500?.05:count>250?.12:.25, random=count>500?.10:count>250?.20:.40;
  return configureInternal(problem,state,seed,iterations,radial,random,false,fastRegret,threads,executor);
 }
 private static VehicleRoutingAlgorithm configureInternal(VehicleRoutingProblem problem,SectionSearchState state,long seed,int iterations,double radialShare,double randomShare,boolean exact,boolean fastRegret,int threads,java.util.concurrent.ExecutorService executor){
  var states=new StateManager(problem);states.updateLoadStates();states.updateSkillStates();states.addStateUpdater(state);
  var constraints=new ConstraintManager(problem,states);constraints.addLoadConstraint();constraints.addSkillsConstraint();
  var adapter=new SectionInsertionAdapter(state,problem);constraints.addConstraint(adapter,ConstraintManager.Priority.CRITICAL);
  var neighborhoods=new JobNeighborhoodsFactory().createNeighborhoods(problem,new AvgServiceAndShipmentDistance(problem.getTransportCosts()),(int)(problem.getJobs().size()*.5));neighborhoods.initialise();double max=neighborhoods.getMaxDistance();
  com.graphhopper.jsprit.core.problem.solution.SolutionCostCalculator objective=solution->{
    double cost=0;for(var route:solution.getRoutes()){if(route.isEmpty())continue;var result=state.evaluate(route).evaluation();if(!result.feasible())return Double.POSITIVE_INFINITY;cost+=route.getVehicle().getType().getVehicleCostParams().fix+result.variableCost();}
    for(var job:solution.getUnassignedJobs())cost+=2*max*(11-job.getPriority());return cost;};
  var acceptor=new Acceptance(state,objective);
  var builder=Jsprit.Builder.newInstance(problem).addCoreStateAndConstraintStuff(false).setStateAndConstraintManager(states,constraints)
   .setActivityInsertionCalculator(adapter).setCustomAcceptor(acceptor).setObjectiveFunction(objective)
   .setProperty(Jsprit.Parameter.THREADS,Integer.toString(threads)).setProperty(Jsprit.Parameter.CONSTRUCTION,Jsprit.Construction.REGRET_INSERTION.toString()).setProperty(Jsprit.Parameter.FAST_REGRET,Boolean.toString(fastRegret))
   .setProperty(Jsprit.Parameter.RADIAL_MIN_SHARE,Integer.toString(Math.max(1,(int)(problem.getJobs().size()*radialShare))))
   .setProperty(Jsprit.Parameter.RADIAL_MAX_SHARE,Integer.toString(Math.max(1,(int)(problem.getJobs().size()*radialShare))))
   .setProperty(Jsprit.Parameter.RANDOM_BEST_MIN_SHARE,Integer.toString(Math.max(1,(int)(problem.getJobs().size()*randomShare))))
   .setProperty(Jsprit.Parameter.RANDOM_BEST_MAX_SHARE,Integer.toString(Math.max(1,(int)(problem.getJobs().size()*randomShare)))).setRandom(new Random(seed));
  if(executor!=null)builder.setExecutorService(executor,threads);
  var algorithm=builder.buildAlgorithm();algorithm.setMaxIterations(iterations);
  if(!exact && iterations>0)algorithm.addTerminationCriterion(new com.graphhopper.jsprit.core.algorithm.termination.IterationWithoutImprovementTermination(hagrid.core.routing.HAGRIDRouterUtils.calculateNoImprovementThreshold(iterations)));
  return algorithm;
 }
 private static final class Acceptance extends SchrimpfAcceptance {
  private final SectionSearchState state;private final com.graphhopper.jsprit.core.problem.solution.SolutionCostCalculator objective;
  Acceptance(SectionSearchState state,com.graphhopper.jsprit.core.problem.solution.SolutionCostCalculator objective){super(1,.15);this.state=state;this.objective=objective;}
  private boolean valid(VehicleRoutingProblemSolution s){
   for(var r:s.getRoutes())if(!r.isEmpty()){
    var choice=state.evaluate(r);if(!choice.evaluation().feasible()){s.setCost(Double.POSITIVE_INFINITY);return false;}
    r.setVehicleAndDepartureTime(r.getVehicle(),choice.departure());
   }
   double cost=objective.getCosts(s);s.setCost(cost);return Double.isFinite(cost);
  }
  @Override public boolean acceptSolution(Collection<VehicleRoutingProblemSolution>s,VehicleRoutingProblemSolution c){return valid(c)&&super.acceptSolution(s,c);}
  @Override public boolean acceptSolution(VehicleRoutingProblemSolution s,VehicleRoutingProblemSolution c){return valid(c)&&super.acceptSolution(s,c);}
  @Override public void informAlgorithmStarts(VehicleRoutingProblem p,VehicleRoutingAlgorithm a,Collection<VehicleRoutingProblemSolution>s){super.informAlgorithmStarts(p,a,s);for(var x:s)if(!valid(x))throw new IllegalArgumentException("Invalid initial section route");}
  @Override public void informIterationStarts(int i,VehicleRoutingProblem p,Collection<VehicleRoutingProblemSolution>s){super.informIterationStarts(i,p,s);if(i==1)setInitialThreshold(Solutions.bestOf(s).getCost()*.03);}
 }
}


