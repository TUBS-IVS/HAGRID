package hagrid.core.routing;
import java.util.*;
import java.util.function.IntSupplier;
import com.graphhopper.jsprit.core.algorithm.*;
import com.graphhopper.jsprit.core.algorithm.acceptor.*;
import com.graphhopper.jsprit.core.algorithm.listener.*;
import com.graphhopper.jsprit.core.problem.*;
import com.graphhopper.jsprit.core.problem.solution.VehicleRoutingProblemSolution;
import com.graphhopper.jsprit.core.util.Solutions;

/** Equal wall-clock cooling; delegates every actual acceptance/feasibility decision. */
public final class SharedTimeAcceptor implements SolutionAcceptor {
 private final SolutionAcceptor original;private final com.graphhopper.jsprit.core.algorithm.acceptor.SchrimpfAcceptance cooling;
 private final VehicleRoutingProblem problem;private final IntSupplier elapsed;private final int budget;
 private boolean initialized;
 private SharedTimeAcceptor(SolutionAcceptor original,VehicleRoutingProblem problem,IntSupplier elapsed,int budget){
  if(!(original instanceof SchrimpfAcceptance s)||budget<1)throw new IllegalArgumentException("Expected actual native Schrimpf/ExactTemporalAcceptance");
  this.original=original;cooling=s;this.problem=problem;this.elapsed=elapsed;this.budget=budget;
 }
 public static SharedTimeAcceptor wrap(SolutionAcceptor original,VehicleRoutingProblem problem,IntSupplier elapsed,int budget){return new SharedTimeAcceptor(original,problem,elapsed,budget);}
 private void initialize(double initialCost){cooling.setInitialThreshold(initialCost*.03);initialized=true;}
 @Override public boolean acceptSolution(Collection<VehicleRoutingProblemSolution> memory,VehicleRoutingProblemSolution candidate){
  if(!initialized)initialize(Solutions.bestOf(memory).getCost());
  cooling.setMaxIterations(budget);
  cooling.informIterationStarts(Math.max(0,elapsed.getAsInt()),problem,memory);
  return original.acceptSolution(memory,candidate);
 }
 static List<String> install(VehicleRoutingAlgorithm algorithm,VehicleRoutingProblem problem,IntSupplier elapsed,int budget)throws Exception{
  var wrappers=new IdentityHashMap<SolutionAcceptor,SharedTimeAcceptor>();var field=SearchStrategy.class.getDeclaredField("solutionAcceptor");field.setAccessible(true);
  for(var strategy:algorithm.getSearchStrategyManager().getStrategies()){
   var before=strategy.getSolutionAcceptor();var wrapper=wrappers.computeIfAbsent(before,key->wrap(key,problem,elapsed,budget));field.set(strategy,wrapper);
   if(strategy.getSolutionAcceptor()!=wrapper)throw new IllegalStateException("Native acceptance delegate was not installed");
  }
  algorithm.getAlgorithmListeners().addListener((IterationStartsListener)(i,p,memory)->{
   if(i==1){double initial=Solutions.bestOf(memory).getCost();wrappers.values().forEach(w->w.initialize(initial));}
  },VehicleRoutingAlgorithmListeners.Priority.LOW);
  return wrappers.keySet().stream().map(x->x.getClass().getName()).sorted().toList();
 }
}
