package hagrid.core.routing;
import java.util.*;
import java.util.function.IntSupplier;
import com.graphhopper.jsprit.core.algorithm.acceptor.*;
import com.graphhopper.jsprit.core.problem.*;
import com.graphhopper.jsprit.core.problem.solution.VehicleRoutingProblemSolution;

/** Native acceptance must cool by actual elapsed time in both timed variants. */
public final class EqualClockContractTest {
 public static void main(String[] args)throws Exception {
  Class<?> policy;
  try{policy=Class.forName("hagrid.core.routing.SharedTimeAcceptor");}
  catch(ClassNotFoundException missing){throw new AssertionError("Shared acceptance-time cooling wrapper is missing",missing);}
  var method=policy.getMethod("wrap",SolutionAcceptor.class,VehicleRoutingProblem.class,IntSupplier.class,int.class);
  var p=VehicleRoutingProblem.Builder.newInstance().build();var nativeAcceptor=new SchrimpfAcceptance(1,.15);
  int[] ms={0};var wrapper=(SolutionAcceptor)method.invoke(null,nativeAcceptor,p,(IntSupplier)()->ms[0],30000);
  var memory=new ArrayList<VehicleRoutingProblemSolution>();memory.add(new VehicleRoutingProblemSolution(List.of(),1000));
  if(!wrapper.acceptSolution(memory,new VehicleRoutingProblemSolution(List.of(),1010)))throw new AssertionError("Early native threshold should allow10-unit exploration");
  memory.clear();memory.add(new VehicleRoutingProblemSolution(List.of(),1000));ms[0]=30000;
  if(wrapper.acceptSolution(memory,new VehicleRoutingProblemSolution(List.of(),1010)))throw new AssertionError("Late candidate must see current cooling time, even after a long iteration");
  if(!wrapper.acceptSolution(memory,new VehicleRoutingProblemSolution(List.of(),990)))throw new AssertionError("Native strict improvement must remain accepted");
  System.out.println("EQUAL_CLOCK_CONTRACT_PASS early-exploration/late-cooling/native-improvement");
 }
}
