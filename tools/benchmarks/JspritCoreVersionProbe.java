package hagrid.core.routing.benchmark;
import com.graphhopper.jsprit.core.algorithm.box.Jsprit;
import com.graphhopper.jsprit.core.algorithm.listener.IterationEndsListener;
import com.graphhopper.jsprit.core.problem.*;
import com.graphhopper.jsprit.core.problem.job.Service;
import com.graphhopper.jsprit.core.problem.solution.VehicleRoutingProblemSolution;
import com.graphhopper.jsprit.core.problem.solution.route.activity.TourActivity;
import com.graphhopper.jsprit.core.problem.vehicle.*;
import com.graphhopper.jsprit.core.util.*;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Core-only version isolation: same static matrix/objective/dependencies; no MATSim/HAGRID change. */
public final class JspritCoreVersionProbe {
    private static final int JOBS=16, ITERATIONS=10, CAPACITY=8;
    private static final List<String> LOCATIONS=new ArrayList<>();
    static { for(int x=0;x<8;x++) for(int y=0;y<8;y++) LOCATIONS.add(x+"_"+y); }
    public static void main(String[] args) throws Exception {
        int seed=Integer.parseInt(args[0]);
        var loaded=Path.of(Jsprit.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
        if(!loaded.equals(Path.of(args[1]).toRealPath())) throw new IllegalStateException("Wrong loaded core binary");
        if(Runtime.version().feature()!=21 || Runtime.getRuntime().availableProcessors()!=1
                || Runtime.getRuntime().maxMemory()>384L*1024*1024) throw new IllegalStateException("Wrong JVM resource profile");
        for(int i=0;i<3;i++) run(seed,loaded,i==2);
    }
    private static double seconds(String from,String to) {
        var a=from.split("_");var b=to.split("_");
        return 10*(Math.abs(Integer.parseInt(a[0])-Integer.parseInt(b[0]))
                +Math.abs(Integer.parseInt(a[1])-Integer.parseInt(b[1])));
    }
    private static double objective(VehicleRoutingProblemSolution solution) {
        double cost=1_000_000.0*solution.getUnassignedJobs().size();
        for(var route:solution.getRoutes()) {
            cost+=100;String previous=route.getStart().getLocation().getId();
            for(var activity:route.getActivities()) {
                cost+=seconds(previous,activity.getLocation().getId());previous=activity.getLocation().getId();
            }
            cost+=seconds(previous,route.getEnd().getLocation().getId());
        }
        return cost;
    }
    private static void run(int seed,Path loaded,boolean emit) {
        var matrix=VehicleRoutingTransportCostsMatrix.Builder.newInstance(true);
        for(String a:LOCATIONS) for(String b:LOCATIONS) {
            matrix.addTransportTime(a,b,seconds(a,b));matrix.addTransportDistance(a,b,seconds(a,b)*10);
        }
        var type=VehicleTypeImpl.Builder.newInstance("van").addCapacityDimension(0,CAPACITY)
                .setFixedCost(100).setCostPerTransportTime(1).setCostPerDistance(0).build();
        var problem=VehicleRoutingProblem.Builder.newInstance().setRoutingCost(matrix.build())
                .setFleetSize(VehicleRoutingProblem.FleetSize.FINITE);
        for(int i=0;i<2;i++) problem.addVehicle(VehicleImpl.Builder.newInstance("van-"+i)
                .setType(type).setStartLocation(Location.newInstance("0_0")).setLatestArrival(25200).build());
        var demand=new Random(seed);
        var required=new HashSet<String>();var locations=new TreeMap<String,String>();
        for(int i=0;i<JOBS;i++) {
            String id="job-"+i,location=LOCATIONS.get(demand.nextInt(LOCATIONS.size()));
            required.add(id);locations.put(id,location);
            problem.addJob(Service.Builder.newInstance(id).addSizeDimension(0,1).setServiceTime(60)
                    .setLocation(Location.newInstance(location)).build());
        }
        var algorithm=Jsprit.Builder.newInstance(problem.build()).setRandom(new Random(seed))
                .setProperty(Jsprit.Parameter.THREADS,"1").setProperty(Jsprit.Parameter.FAST_REGRET,"false")
                .setObjectiveFunction(JspritCoreVersionProbe::objective).buildAlgorithm();
        algorithm.setMaxIterations(ITERATIONS);
        algorithm.setPrematureAlgorithmTermination(discovered -> false);
        var iterations=new AtomicInteger();
        algorithm.addListener((IterationEndsListener)(i,p,s) -> iterations.incrementAndGet());
        var allocation=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        boolean allocationAvailable=allocation.isThreadAllocatedMemorySupported()
                && allocation.isThreadAllocatedMemoryEnabled();
        long allocatedBefore=allocationAvailable ? allocation.getThreadAllocatedBytes(Thread.currentThread().threadId()) : -1;
        long start=System.nanoTime();
        var solution=Solutions.bestOf(algorithm.searchSolutions());
        long elapsed=System.nanoTime()-start;
        long allocatedAfter=allocationAvailable ? allocation.getThreadAllocatedBytes(Thread.currentThread().threadId()) : -1;
        // -1 explicitly means unavailable; zero must mean an observed zero delta.
        long allocated=allocatedBefore>=0 && allocatedAfter>=allocatedBefore ? allocatedAfter-allocatedBefore : -1;
        var served=new HashSet<String>();int violations=0;
        var canonical=new ArrayList<String>();
        for(var route:solution.getRoutes()) {
            if(route.getActivities().size()>CAPACITY) violations++;
            double time=route.getDepartureTime();String previous=route.getStart().getLocation().getId();
            var ids=new ArrayList<String>();
            for(var activity:route.getActivities()) {
                String id=((TourActivity.JobActivity)activity).getJob().getId();
                if(!served.add(id)||!Objects.equals(locations.get(id),activity.getLocation().getId())) violations++;
                time+=seconds(previous,activity.getLocation().getId())+60;
                previous=activity.getLocation().getId();ids.add(id);
            }
            time+=seconds(previous,route.getEnd().getLocation().getId());
            if(time>25200 || !route.getStart().getLocation().getId().equals("0_0")
                    || !route.getEnd().getLocation().getId().equals("0_0")) violations++;
            canonical.add(String.join(":",ids));
        }
        if(iterations.get()!=ITERATIONS || !served.equals(required) || violations!=0
                || !solution.getUnassignedJobs().isEmpty() || !Double.isFinite(solution.getCost())
                || Math.abs(solution.getCost()-objective(solution))>1e-9)
            throw new IllegalStateException("Incomplete or unequal-budget native solution");
        Collections.sort(canonical);
        if(emit) System.out.println("CORE_VERSION,"+seed+","+iterations.get()+","+JOBS+","+served.size()+","
                +violations+","+solution.getRoutes().size()+","+solution.getCost()+","+objective(solution)+","
                +elapsed+","+allocated+",\""+loaded+"\",\""+String.join(";",canonical)+"\"");
    }
}
