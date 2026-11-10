import java.nio.file.*;
import java.util.*;
import java.security.*;
import com.graphhopper.jsprit.core.algorithm.*;
import com.graphhopper.jsprit.core.algorithm.box.Jsprit;
import com.graphhopper.jsprit.core.algorithm.listener.*;
import com.graphhopper.jsprit.core.problem.*;
import com.graphhopper.jsprit.core.problem.job.*;
import com.graphhopper.jsprit.core.problem.cost.*;
import com.graphhopper.jsprit.core.problem.vehicle.*;
import com.graphhopper.jsprit.core.problem.driver.Driver;
import com.graphhopper.jsprit.core.problem.solution.route.*;
import com.graphhopper.jsprit.core.problem.solution.route.activity.*;
import com.graphhopper.jsprit.core.util.*;

/** Native jsprit ruin/recreate and acceptance, with exact direction-aware insertion deltas. */
public class OptimizedJspritRun {
 static boolean fastService,fastOrderCache,indexedInputs,positionPruning;
 static long boundCalls;
 static final hagrid.core.routing.directions.RouteOrderCache<DirectionalDP.Route> orderCache =
  new hagrid.core.routing.directions.RouteOrderCache<>(OptimizedJspritRun::rebuild,256);
 static DirectionalDP.Route rebuild(VehicleRoute r){routeDpBuilds++;return d.dp.new Route(order(r));}
 static FastDirectionProblem d;static long insertionCalls,objectiveCalls,routeDpBuilds,checkedDeltas,directionSwitches;static final Map<VehicleRoute,DirectionalDP.Route> cache=new IdentityHashMap<>();
 static int[] order(VehicleRoute r){return r.getActivities().stream().mapToInt(a->d.jobs.get(((TourActivity.JobActivity)a).getJob().getId())).toArray();}
 static DirectionalDP.Route route(VehicleRoute r){int[] order=order(r);var dp=cache.get(r);if(dp==null||!Arrays.equals(order,dp.order)){dp=d.dp.new Route(order);cache.put(r,dp);routeDpBuilds++;}return dp;}
 static String hash(Path p)throws Exception{var h=MessageDigest.getInstance("SHA-256");try(var f=Files.newInputStream(p)){byte[] b=new byte[65536];int n;while((n=f.read(b))!=-1)h.update(b,0,n);}return HexFormat.of().formatHex(h.digest());}
 public static void main(String[]args)throws Exception{
  var job=FastDirectionProblem.JSON.readTree(Path.of(args[0]).toFile());Path root=Path.of(job.path("inputRoot").asText()),out=Path.of(job.path("output").asText());Files.createDirectories(out.getParent());if(Files.exists(out))throw new IllegalArgumentException("Result exists");
  Path actual=Path.of(Jsprit.class.getProtectionDomain().getCodeSource().getLocation().toURI());if(!hash(actual).equals(job.path("jspritSha256").asText()))throw new AssertionError("Wrong jsprit JAR");
  for(var type:List.of(VehicleRoutingAlgorithm.class,Class.forName("com.graphhopper.jsprit.core.algorithm.recreate.ServiceInsertionCalculator")))if(!type.getProtectionDomain().getCodeSource().getLocation().equals(Jsprit.class.getProtectionDomain().getCodeSource().getLocation()))throw new AssertionError("Mixed jsprit versions");
  fastService=job.path("fastService").asBoolean(false);fastOrderCache=job.path("fastOrderCache").asBoolean(false); indexedInputs=job.path("indexedInputs").asBoolean(false);positionPruning=job.path("positionPruning").asBoolean(false)&&fastService&&fastOrderCache&&indexedInputs;long cold=System.nanoTime();d=new FastDirectionProblem(root,job.path("penalty").asDouble(),indexedInputs);long prepared=System.nanoTime();
  var transport=new AbstractForwardVehicleRoutingTransportCosts(){
   int j(Location l){return indexedInputs?l.getIndex():d.jobs.get(l.getId());}
   public double getDistance(Location a,Location b,double t,Vehicle v){return d.pairMeters[j(a)][j(b)];}
   public double getTransportCost(Location a,Location b,double t,Driver driver,Vehicle v){return d.pairCosts[j(a)][j(b)];}
   public double getTransportTime(Location a,Location b,double t,Driver driver,Vehicle v){return d.pairMeters[j(a)][j(b)]/38.8888888889;}
  };
  var type=VehicleTypeImpl.Builder.newInstance("ct_cep_size_l").addCapacityDimension(0,230).setMaxVelocity(38.8888888889).setFixedCost(FastDirectionProblem.FIXED).setCostPerDistance(FastDirectionProblem.RATE).setCostPerTransportTime(0).setCostPerServiceTime(0).setCostPerWaitingTime(0).build();
  var depot=Location.Builder.newInstance().setId("depot").setIndex(0).setCoordinate(Coordinate.newInstance(d.depotXY[0],d.depotXY[1])).build();
  var vehicle=VehicleImpl.Builder.newInstance("cep_size_l_14").setType(type).setStartLocation(depot).setEndLocation(depot).setEarliestStart(50360).setLatestArrival(75600).setReturnToDepot(true).build();
  var problemBuilder=VehicleRoutingProblem.Builder.newInstance().setFleetSize(VehicleRoutingProblem.FleetSize.FINITE).setRoutingCost(transport).addVehicle(vehicle);
  for(int j=1;j<d.tasks.size();j++){var t=d.tasks.get(j);var location=Location.Builder.newInstance().setId(t.path("id").asText()).setIndex(j).setCoordinate(Coordinate.newInstance(t.path("xy").get(0).asDouble(),t.path("xy").get(1).asDouble())).build();problemBuilder.addJob(Service.Builder.newInstance(t.path("id").asText()).setLocation(location).addSizeDimension(0,t.path("capacity").asInt()).setServiceTime(t.path("duration").asDouble()).setTimeWindow(TimeWindow.newInstance(t.path("earliest").asDouble(),t.path("latest").asDouble())).build());}
  var problem=problemBuilder.build();long seed=job.path("seed").asLong();int iterations=job.path("iterations").asInt();
  var builder=Jsprit.Builder.newInstance(problem).setRandom(new Random(seed));
  for(var strategy:Jsprit.Strategy.values())builder.setProperty(strategy,"0");
  for(var e:Map.of("radial_regret","0.5","random_best","0.5","random_regret","0.5","worst_regret","1.0","cluster_regret","1.0").entrySet())builder.setProperty(e.getKey(),e.getValue());
  var settings=new TreeMap<String,String>(Map.of("threads","1","iterations",Integer.toString(iterations),"regret.fast","false","vehicle_switch","false","fixed_cost_param","0","insertion.noise_prob","0","construction","regret_insertion","threshold.alpha","0.15","threshold.ini","0.03"));
  settings.forEach(builder::setProperty);
  builder.setRegretScorer((insertion,j)->{var s=(Service)j;int index=d.jobs.get(j.getId());return -.1*(s.getTimeWindow().getEnd()-s.getTimeWindow().getStart())+.05*d.pairCosts[0][index];});
  if(fastService)builder.setServiceInsertionCalculatorFactory(
   com.graphhopper.jsprit.core.algorithm.recreate.HagridServiceInsertionCalculator.factory(false,
    positionPruning?(context,gap)->{boundCalls++;var r=orderCache.get(context);int j=((Service)context.getJob()).getLocation().getIndex();return r.insertionDelta(j,gap)+d.activity(j)+(r.order.length==0?FastDirectionProblem.FIXED:0);}:null));
  boolean validateInsertions=job.path("validateInsertions").asBoolean(true);
  builder.setActivityInsertionCalculator((context,previous,next,inserted,time)->{
   insertionCalls++;var r=fastOrderCache?orderCache.get(context):route(context.getRoute());int gap=context.getActivityContext().getInsertionIndex(),j=indexedInputs?((Service)context.getJob()).getLocation().getIndex():d.jobs.get(context.getJob().getId());
   double delta=r.insertionDelta(j,gap);
   if(validateInsertions && (insertionCalls<=1000 || insertionCalls%10000==0)){int[] insertedOrder=new int[r.order.length+1];System.arraycopy(r.order,0,insertedOrder,0,gap);insertedOrder[gap]=j;System.arraycopy(r.order,gap,insertedOrder,gap+1,r.order.length-gap);if(Math.abs(delta-(d.dp.value(insertedOrder)-r.value))>1e-8)throw new AssertionError("Native insertion delta mismatch");checkedDeltas++;int[] before=r.chosenStates(),after=d.dp.new Route(insertedOrder).chosenStates();for(int old=0;old<r.order.length;old++)if(before[old+1]!=after[old+(old>=gap?2:1)])directionSwitches++;}
   return delta+d.activity(j)+(r.order.length==0?FastDirectionProblem.FIXED:0);
  });
  builder.setObjectiveFunction(solution->{objectiveCalls++;double cost=solution.getUnassignedJobs().size()*10000;for(var r:solution.getRoutes())cost+=d.fullCost(order(r));return cost;});
  var pf=builder.getClass().getDeclaredField("properties");pf.setAccessible(true);var properties=(Properties)pf.get(builder);var effective=new TreeMap<String,String>();for(var k:properties.stringPropertyNames())effective.put(k,properties.getProperty(k));
  var threadBean=(com.sun.management.ThreadMXBean)java.lang.management.ManagementFactory.getThreadMXBean(); long tid=Thread.currentThread().threadId(); long cpuStart=threadBean.getCurrentThreadCpuTime(),bytesStart=threadBean.getThreadAllocatedBytes(tid); long searchStart=System.nanoTime();var algorithm=builder.buildAlgorithm();algorithm.setMaxIterations(iterations);int[] completed={0};var improvements=new ArrayList<Object>();double[] best={Double.POSITIVE_INFINITY};
  algorithm.addListener((IterationEndsListener)(i,p,solutions)->{completed[0]=i;cache.clear();orderCache.clear();var s=Solutions.bestOf(solutions);if(s.getUnassignedJobs().isEmpty()&&s.getCost()<best[0]-1e-8){best[0]=s.getCost();improvements.add(Map.of("iteration",i,"cost",best[0],"seconds",(System.nanoTime()-searchStart)/1e9));}});
  double cap=job.path("capSeconds").asDouble(120);algorithm.setPrematureAlgorithmTermination(discovery->(System.nanoTime()-searchStart)/1e9>cap);
  var solution=Solutions.bestOf(algorithm.searchSolutions());double searchSeconds=(System.nanoTime()-searchStart)/1e9; double searchCpuSeconds=(threadBean.getCurrentThreadCpuTime()-cpuStart)/1e9; long searchAllocatedBytes=threadBean.getThreadAllocatedBytes(tid)-bytesStart;
  if(!solution.getUnassignedJobs().isEmpty()||solution.getRoutes().size()!=1)throw new AssertionError("Not a complete single-carrier tour");
  var route=solution.getRoutes().iterator().next();int[] order=order(route);if(order.length!=69||Arrays.stream(order).distinct().count()!=69)throw new AssertionError("Coverage");
  if(Math.abs(route.getDepartureTime()-50360)>1e-6)throw new AssertionError("Changed departure");
  if(Math.abs(solution.getCost()-d.fullCost(order))>1e-7)throw new AssertionError("Native selected objective mismatch");if(insertionCalls==0||objectiveCalls==0)throw new AssertionError("Adapter not used");
  var result=d.output(order);result.put("positionPruning",positionPruning);result.put("boundCalls",boundCalls);result.put("validateInsertions",validateInsertions);result.put("fastService",fastService);result.put("fastOrderCache",fastOrderCache);result.put("indexedInputs",indexedInputs);result.put("model","jsprit"+job.path("version").asText()+"_joint");result.put("version",job.path("version").asText());result.put("jspritJar",actual.toString());result.put("jspritSha256",hash(actual));result.put("seed",seed);result.put("requestedIterations",iterations);result.put("completedIterations",completed[0]);result.put("searchSeconds",searchSeconds);result.put("searchCpuSeconds",searchCpuSeconds);result.put("searchAllocatedBytes",searchAllocatedBytes);result.put("inputSeconds",(prepared-cold)/1e9);result.put("insertionCalls",insertionCalls);result.put("objectiveCalls",objectiveCalls);result.put("routeDpBuilds",routeDpBuilds);result.put("checkedInsertionDeltas",checkedDeltas);result.put("existingApproachSwitchesInCheckedInsertions",directionSwitches);result.put("settings",settings);result.put("effectiveProperties",effective);result.put("improvements",improvements);result.put("nativeSelectedCost",solution.getCost());result.put("searchTimeModel","optimistic directional pair bounds; full time-dependent QSim validation follows");
  FastDirectionProblem.JSON.writerWithDefaultPrettyPrinter().writeValue(out.toFile(),result);System.out.println("NATIVE_JSPRIT_COMPLETE "+result.get("model")+" p="+d.penalty+" seed="+seed+" iterations="+completed[0]+" km="+(double)result.get("meters")/1000+" turns="+result.get("turns")+" searchSeconds="+searchSeconds+" insertionCalls="+insertionCalls);
 }
}
