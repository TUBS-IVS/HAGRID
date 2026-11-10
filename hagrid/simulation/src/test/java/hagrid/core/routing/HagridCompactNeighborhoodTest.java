package hagrid.core.routing;

import com.graphhopper.jsprit.core.algorithm.ruin.JobNeighborhoods;
import com.graphhopper.jsprit.core.algorithm.ruin.JobNeighborhoodsFactory;
import com.graphhopper.jsprit.core.algorithm.ruin.distance.JobDistance;
import com.graphhopper.jsprit.core.problem.Location;
import com.graphhopper.jsprit.core.problem.VehicleRoutingProblem;
import com.graphhopper.jsprit.core.problem.job.*;
import com.graphhopper.jsprit.core.problem.solution.route.VehicleRoute;
import com.graphhopper.jsprit.core.problem.solution.route.activity.TimeWindow;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleImpl;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HagridCompactNeighborhoodTest {
    private static Service service(String id) {
        return Service.Builder.newInstance(id).setLocation(Location.newInstance(0,0)).build();
    }
    private static VehicleRoutingProblem problem(int n) {
        var builder=VehicleRoutingProblem.Builder.newInstance();
        for(int i=0;i<n;i++)builder.addJob(service("j"+i));
        return builder.build();
    }
    private static List<Job> neighbors(JobNeighborhoods neighborhoods,int n,Job source) {
        List<Job> result=new ArrayList<>();
        neighborhoods.getNearestNeighborsIterator(n,source).forEachRemaining(result::add);
        return result;
    }
    @Test void stableTiesFollowEncounterOrderAndPreserveJobIdentity() {
        Service source=service("source"),z=service("z"),a=service("a"),m=service("m");
        var problem=VehicleRoutingProblem.Builder.newInstance().addJob(source).addJob(z).addJob(a).addJob(m).build();
        var neighborhoods=new JobNeighborhoodsFactory().createNeighborhoods(problem,(x,y)->7,2);
        neighborhoods.initialise();
        var actual=neighbors(neighborhoods,99,source);
        assertEquals(2,actual.size());assertSame(z,actual.get(0));assertSame(a,actual.get(1));
        assertEquals(7,neighborhoods.getMaxDistance());
    }
    @Test void directedDistancesAndGlobalMaximumAreNotSymmetrizedOrTruncated() {
        Service a=service("a"),b=service("b"),c=service("c");
        var problem=VehicleRoutingProblem.Builder.newInstance().addJob(a).addJob(b).addJob(c).build();
        List<String> calls=new ArrayList<>();
        JobDistance distance=(x,y)->{calls.add(x.getId()+y.getId());return x==a?(y==b?2:500):(x==b?(y==a?8:1):(y==a?3:4));};
        var neighborhoods=new JobNeighborhoodsFactory().createNeighborhoods(problem,distance,1);
        neighborhoods.initialise();
        assertEquals(List.of("ab","ac","ba","bc","ca","cb"),calls);
        assertSame(b,neighbors(neighborhoods,1,a).get(0));
        assertSame(c,neighbors(neighborhoods,1,b).get(0));
        assertSame(a,neighbors(neighborhoods,1,c).get(0));
        assertEquals(500,neighborhoods.getMaxDistance());
    }
    @Test void specialFloatingValuesUseStableDoubleOrderingAndNativeMaximumRule() {
        var problem=problem(7);var jobs=new ArrayList<>(problem.getJobs().values());
        double[] values={0,Double.NaN,+0.0,-0.0,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,-2};
        var neighborhoods=new JobNeighborhoodsFactory().createNeighborhoods(problem,(a,b)->values[problem.getJobIndex(b)-1],7);
        neighborhoods.initialise();
        assertEquals(List.of(jobs.get(5),jobs.get(6),jobs.get(3),jobs.get(2),jobs.get(4),jobs.get(1)),neighbors(neighborhoods,9,jobs.get(0)));
        assertEquals(Double.POSITIVE_INFINITY,neighborhoods.getMaxDistance());
        var negative=new JobNeighborhoodsFactory().createNeighborhoods(problem,(a,b)->-3,1);negative.initialise();
        assertEquals(0,negative.getMaxDistance());
        var nan=new JobNeighborhoodsFactory().createNeighborhoods(problem,(a,b)->Double.NaN,1);nan.initialise();
        assertEquals(0,nan.getMaxDistance());
    }
    @Test void tinyCapacitiesInvalidJobsAndUninitialisedRowsRetainNativeBehavior() {
        for(int n:new int[]{0,1,2,3,17})for(int capacity:new int[]{0,1,n/2,n,n+2}) {
            var problem=problem(n);int[] calls={0};
            var neighborhoods=new JobNeighborhoodsFactory().createNeighborhoods(problem,(a,b)->{calls[0]++;return 1;},capacity);
            for(Job source:problem.getJobs().values())assertEquals(Collections.nCopies(capacity,null),neighbors(neighborhoods,capacity,source));
            assertThrows(NullPointerException.class,()->neighborhoods.getNearestNeighborsIterator(1,null));
            assertFalse(neighborhoods.getNearestNeighborsIterator(1,service("unknown")).hasNext());
            neighborhoods.initialise();assertEquals(capacity==0?0:n*(n-1),calls[0]);
            for(Job source:problem.getJobs().values())for(int request:new int[]{-2,0,1,capacity,capacity+1,n+2}) {
                List<Job> expected=new ArrayList<>(problem.getJobs().values());expected.removeIf(job->job==source);
                expected=expected.subList(0,Math.min(expected.size(),Math.min(capacity,Math.max(0,request))));
                assertEquals(expected,neighbors(neighborhoods,request,source));
            }
        }
        assertThrows(NegativeArraySizeException.class,()->new JobNeighborhoodsFactory().createNeighborhoods(problem(1),(a,b)->0,-1));
    }
    @Test void initialRoutesShipmentsAndNullLocationBreaksKeepNativeRowSemantics() {
        Service initial=service("initial"),ordinary=service("ordinary");
        var shipment=Shipment.Builder.newInstance("shipment").setPickupLocation(Location.newInstance(1,1)).setDeliveryLocation(Location.newInstance(2,2)).build();
        Break pause=Break.Builder.newInstance("pause").build();
        var vehicle=VehicleImpl.Builder.newInstance("vehicle").setStartLocation(Location.newInstance(0,0)).build();
        var problem=VehicleRoutingProblem.Builder.newInstance().addJob(ordinary).addJob(shipment)
            .addInitialVehicleRoute(VehicleRoute.Builder.newInstance(vehicle).addService(initial)
                .addBreak(pause,TimeWindow.newInstance(0,100),Location.newInstance(1,1)).build()).build();
        int[] calls={0};var neighborhoods=new JobNeighborhoodsFactory().createNeighborhoods(problem,(a,b)->{calls[0]++;assertNotSame(pause,a);assertNotSame(pause,b);return 1;},3);
        neighborhoods.initialise();assertEquals(6,calls[0]);
        assertEquals(List.of(shipment,initial),neighbors(neighborhoods,10,ordinary));
        assertEquals(Arrays.asList(null,null,null),neighbors(neighborhoods,10,pause));
    }
    @Test void retainedNeighborPayloadUsesAtMostTwoBytesPerStoredIndex() throws Exception {
        int n=200,capacity=100;var problem=problem(n);
        var neighborhoods=new JobNeighborhoodsFactory().createNeighborhoods(problem,(a,b)->Math.abs(problem.getJobIndex(a)-problem.getJobIndex(b)),capacity);
        neighborhoods.initialise();
        long bytes=0;Set<Object> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        for(Field field:neighborhoods.getClass().getDeclaredFields()) {
            field.setAccessible(true);Object value=field.get(neighborhoods);
            if(value instanceof char[][] rows)for(char[] row:rows)if(row!=null&&seen.add(row))bytes+=2L*row.length;
            if(value instanceof int[][] rows)for(int[] row:rows)if(row!=null&&seen.add(row))bytes+=4L*row.length;
        }
        assertTrue(bytes>0,"Must inspect actual retained neighborhood storage");
        assertTrue(bytes<=2L*(n+1)*capacity,"Neighbor payload must be compact without reducing requested capacity: "+bytes);
        for(Job source:problem.getJobs().values())assertEquals(capacity,neighbors(neighborhoods,capacity,source).size());
    }
    @Test void indicesAboveUnsignedCharRangeUseIntRowsWithoutQuadraticInitialisation() throws Exception {
        var problem=problem(65536);
        var neighborhoods=new JobNeighborhoodsFactory().createNeighborhoods(problem,(a,b)->{throw new AssertionError("No distance computation needed to test representation boundary");},1);
        int[][] rows=null;Job[] byIndex=null;
        for(Field field:neighborhoods.getClass().getDeclaredFields()) {
            field.setAccessible(true);Object value=field.get(neighborhoods);
            if(value instanceof int[][] found)rows=found;
            if(value instanceof Job[] found)byIndex=found;
        }
        assertNotNull(rows,"Indices above 65535 require full-width backing rows");assertNotNull(byIndex);
        Job first=problem.getJobs().get("j0"),last=problem.getJobs().get("j65535");
        assertEquals(65536,problem.getJobIndex(last));
        // Exercise the public decoder with a real VRP index; skip billions of pair distances.
        rows[problem.getJobIndex(first)-1]=new int[]{65536};byIndex[65536]=last;
        assertSame(last,neighbors(neighborhoods,1,first).get(0));
    }
}
