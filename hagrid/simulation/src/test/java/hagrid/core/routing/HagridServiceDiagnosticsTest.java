package hagrid.core.routing;

import com.graphhopper.jsprit.core.algorithm.listener.AlgorithmEventAdapter;
import com.graphhopper.jsprit.core.algorithm.listener.events.JobInserted;
import com.graphhopper.jsprit.core.problem.Location;
import com.graphhopper.jsprit.core.problem.VehicleRoutingProblem;
import com.graphhopper.jsprit.core.problem.job.Service;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleImpl;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleTypeImpl;
import com.graphhopper.jsprit.core.util.Solutions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;

@ResourceLock("java.lang.System.properties")
class HagridServiceDiagnosticsTest {
    @Test void productionBuilderSkipsUnusedDiagnosticsByDefault() { run(false); }
    @Test void productionBuilderCanRestoreNativeDiagnostics() { run(true); }
    private void run(boolean diagnostics) {
        String key="hagrid.jsprit.insertionDiagnostics", previous=System.getProperty(key);
        try {
            if(diagnostics)System.setProperty(key,"true");else System.clearProperty(key);
            var vehicle=VehicleImpl.Builder.newInstance("van").setStartLocation(Location.newInstance(0,0))
                    .setType(VehicleTypeImpl.Builder.newInstance("van").addCapacityDimension(0,10).build()).build();
            var service=Service.Builder.newInstance("delivery").setLocation(Location.newInstance(1,0)).build();
            var problem=VehicleRoutingProblem.Builder.newInstance().addVehicle(vehicle).addJob(service).build();
            var algorithm=HAGRIDRouterUtils.configureAlgorithm(problem,1,1,null,0,1000);
            var events=new ArrayList<JobInserted>();
            algorithm.addListener(new AlgorithmEventAdapter(algorithm));
            algorithm.addEventListener(e->{if(e instanceof JobInserted inserted)events.add(inserted);});
            var solution=Solutions.bestOf(algorithm.searchSolutions());
            assertTrue(solution.getUnassignedJobs().isEmpty());
            assertEquals(1,solution.getRoutes().stream().mapToInt(r->r.getActivities().size()).sum());
            assertFalse(events.isEmpty(),"Must observe real insertions through the production builder");
            assertTrue(events.stream().allMatch(e->(e.costBreakdown()!=null)==diagnostics));
        } finally {
            if(previous==null)System.clearProperty(key);else System.setProperty(key,previous);
        }
    }
}
