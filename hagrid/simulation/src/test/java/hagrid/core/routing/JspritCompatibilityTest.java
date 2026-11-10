package hagrid.core.routing;

import com.graphhopper.jsprit.core.algorithm.VehicleRoutingAlgorithm;
import com.graphhopper.jsprit.core.algorithm.box.Jsprit;
import com.graphhopper.jsprit.core.algorithm.state.StateManager;
import com.graphhopper.jsprit.core.problem.Location;
import com.graphhopper.jsprit.core.problem.VehicleRoutingProblem;
import com.graphhopper.jsprit.core.problem.constraint.ConstraintManager;
import com.graphhopper.jsprit.core.problem.job.Service;
import com.graphhopper.jsprit.core.problem.job.Shipment;
import com.graphhopper.jsprit.core.problem.solution.spec.ActivitySpec;
import com.graphhopper.jsprit.core.problem.solution.spec.RouteSpec;
import com.graphhopper.jsprit.core.problem.solution.spec.SolutionSpec;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleImpl;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleTypeImpl;
import com.graphhopper.jsprit.core.util.Solutions;
import com.graphhopper.jsprit.io.algorithm.AlgorithmConfig;
import com.graphhopper.jsprit.io.algorithm.AlgorithmConfigXmlReader;
import com.graphhopper.jsprit.io.algorithm.VehicleRoutingAlgorithms;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/** Actual binary/runtime contracts needed by HAGRID and its bundled MATSim freight sources. */
class JspritCompatibilityTest {
    @TempDir Path tmp;

    private VehicleRoutingProblem problem() {
        var type = VehicleTypeImpl.Builder.newInstance("van").addCapacityDimension(0, 3).build();
        var vehicle = VehicleImpl.Builder.newInstance("van").setType(type)
                .setStartLocation(Location.newInstance(0, 0)).setLatestArrival(1000).build();
        return VehicleRoutingProblem.Builder.newInstance().addVehicle(vehicle)
                .setFleetSize(VehicleRoutingProblem.FleetSize.FINITE)
                .addJob(Service.Builder.newInstance("service").setLocation(Location.newInstance(1, 0))
                        .addSizeDimension(0, 1).setServiceTime(2).build())
                .addJob(Shipment.Builder.newInstance("shipment").addSizeDimension(0, 1)
                        .setPickupLocation(Location.newInstance(4, 0))
                        .setDeliveryLocation(Location.newInstance(8, 0)).build()).build();
    }

    @Test
    void supportsSolutionSpecWithoutLockingJobs() {
        var problem = problem();
        var algorithm = Jsprit.Builder.newInstance(problem).setRandom(new Random(42))
                .setProperty(Jsprit.Parameter.FAST_REGRET, "false").buildAlgorithm();
        algorithm.addInitialSolution(SolutionSpec.of(RouteSpec.of("van", ActivitySpec.visit("service"),
                ActivitySpec.pickup("shipment"), ActivitySpec.delivery("shipment"))));
        algorithm.setMaxIterations(3);

        // VRP initial routes lock jobs out of optimization; a SolutionSpec must leave them available.
        assertThat(problem.getInitialVehicleRoutes()).isEmpty();
        assertThat(problem.getJobs()).containsOnlyKeys("service", "shipment");
        assertCompleteServiceAndShipment(algorithm);
    }

    @Test
    void existingIoAlgorithmXmlEntryPointsRemainExecutable() throws Exception {
        Path xml = tmp.resolve("algorithm.xml");
        Files.writeString(xml, """
                <?xml version="1.0" encoding="UTF-8"?>
                <algorithm xmlns="http://www.w3schools.com"
                    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                    xsi:schemaLocation="http://www.w3schools.com algorithm_schema.xsd">
                  <iterations>3</iterations>
                  <construction><insertion name="bestInsertion"/></construction>
                  <strategy><memory>1</memory><searchStrategies>
                    <searchStrategy name="randomRuinAndRecreate">
                      <selector name="selectBest"/><acceptor name="acceptNewRemoveWorst"/>
                      <modules><module name="ruin_and_recreate">
                        <ruin name="randomRuin"><share>0.5</share></ruin>
                        <insertion name="bestInsertion"/>
                      </module></modules><probability>1.0</probability>
                    </searchStrategy>
                  </searchStrategies></strategy>
                </algorithm>
                """);
        var problem = problem();
        assertCompleteServiceAndShipment(VehicleRoutingAlgorithms.readAndCreateAlgorithm(problem, xml.toUri().toURL()));

        var config = new AlgorithmConfig();
        new AlgorithmConfigXmlReader(config).read(xml.toUri().toURL());
        var states = new StateManager(problem);
        var constraints = new ConstraintManager(problem, states);
        assertCompleteServiceAndShipment(VehicleRoutingAlgorithms.readAndCreateAlgorithm(
                problem, config, 0, null, states, constraints, true));
    }

    private void assertCompleteServiceAndShipment(VehicleRoutingAlgorithm algorithm) {
        var solution = Solutions.bestOf(algorithm.searchSolutions());
        assertThat(solution.getUnassignedJobs()).isEmpty();
        assertThat(solution.getRoutes()).hasSize(1);
        var route = solution.getRoutes().iterator().next();
        assertThat(route.getActivities()).hasSize(3);
        var activities = route.getActivities().stream().map(a -> ActivitySpec.from(
                (com.graphhopper.jsprit.core.problem.solution.route.activity.TourActivity.JobActivity) a)).toList();
        assertThat(activities).containsExactlyInAnyOrder(ActivitySpec.visit("service"),
                ActivitySpec.pickup("shipment"), ActivitySpec.delivery("shipment"));
        assertThat(activities.indexOf(ActivitySpec.pickup("shipment")))
                .isLessThan(activities.indexOf(ActivitySpec.delivery("shipment")));
        assertThat(solution.getCost()).isFinite().isPositive();
    }
}
