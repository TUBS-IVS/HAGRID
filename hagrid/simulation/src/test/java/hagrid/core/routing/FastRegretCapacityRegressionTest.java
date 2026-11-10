package hagrid.core.routing;

import com.graphhopper.jsprit.core.algorithm.recreate.InsertionBuilder;
import com.graphhopper.jsprit.core.algorithm.recreate.AdaptiveSpatialFilter;
import com.graphhopper.jsprit.core.algorithm.recreate.RegretInsertionFast;
import com.graphhopper.jsprit.core.algorithm.state.StateManager;
import com.graphhopper.jsprit.core.problem.Location;
import com.graphhopper.jsprit.core.problem.VehicleRoutingProblem;
import com.graphhopper.jsprit.core.problem.constraint.ConstraintManager;
import com.graphhopper.jsprit.core.problem.constraint.DependencyType;
import com.graphhopper.jsprit.core.problem.constraint.HardRouteConstraint;
import com.graphhopper.jsprit.core.problem.constraint.SoftRouteConstraint;
import com.graphhopper.jsprit.core.algorithm.recreate.listener.JobInsertedListener;
import com.graphhopper.jsprit.core.problem.job.Job;
import com.graphhopper.jsprit.core.problem.job.Service;
import com.graphhopper.jsprit.core.problem.solution.route.VehicleRoute;
import com.graphhopper.jsprit.core.problem.vehicle.FiniteFleetManagerFactory;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleImpl;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleTypeImpl;
import com.graphhopper.jsprit.core.util.VehicleRoutingTransportCostsMatrix;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.assertThat;

/** Real native load/skill constraints; deterministic insertion order, no solver clock or random seed. */
public class FastRegretCapacityRegressionTest {
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void doesNotPruneNewlyFeasibleCheapInsertionByDistance(boolean tracking) {
        var depot = Location.newInstance(0, 0);
        var farAway = Location.newInstance(1000, 0);
        var matrix = VehicleRoutingTransportCostsMatrix.Builder.newInstance(false);
        for (var from : List.of(depot, farAway)) for (var to : List.of(depot, farAway))
            matrix.addTransportDistance(from.getId(), to.getId(), 0).addTransportTime(from.getId(), to.getId(), 0);
        var builder = VehicleRoutingProblem.Builder.newInstance().setRoutingCost(matrix.build())
                .setFleetSize(VehicleRoutingProblem.FleetSize.FINITE);
        var type = VehicleTypeImpl.Builder.newInstance("van").addCapacityDimension(0, 3).build();
        var routes = new ArrayList<VehicleRoute>();
        for (String id : List.of("A", "B", "C")) {
            var vehicle = VehicleImpl.Builder.newInstance(id).setType(type).setStartLocation(depot).addSkill(id).build();
            var existing = service("existing-" + id, null, depot);
            builder.addVehicle(vehicle).addJob(existing);
            routes.add(VehicleRoute.Builder.newInstance(vehicle).addService(existing).build());
        }
        var marker = service("marker", "C", depot);
        var target = service("target", null, farAway);
        builder.addJob(marker).addJob(target);
        var problem = builder.build();
        routes.replaceAll(route -> VehicleRoute.Builder.newInstance(route.getVehicle())
                .setJobActivityFactory(problem::copyAndGetActivities)
                .addService((Service) route.getTourActivities().getJobs().iterator().next()).build());
        var states = new StateManager(problem);
        states.updateLoadStates(); states.updateSkillStates();
        var constraints = new ConstraintManager(problem, states);
        constraints.addLoadConstraint(); constraints.addSkillsConstraint();
        constraints.addConstraint((HardRouteConstraint) context -> !context.getJob().equals(target)
                || !context.getRoute().getVehicle().getId().equals("C")
                || context.getRoute().getTourActivities().getJobs().contains(marker));
        constraints.addConstraint((SoftRouteConstraint) context -> !context.getJob().equals(target) ? 0
                : switch(context.getRoute().getVehicle().getId()) { case "A" -> 1; case "B" -> 2; default -> 0; });
        var fleet = new FiniteFleetManagerFactory(problem.getVehicles()).createFleetManager(problem);
        routes.forEach(route -> fleet.lock(route.getVehicle()));
        var insertion = (RegretInsertionFast) new InsertionBuilder(problem, fleet, states, constraints)
                .setInsertionStrategy(InsertionBuilder.Strategy.REGRET).setFastRegret(true)
                .setAllowVehicleSwitch(false).build();
        insertion.addListener(states);
        insertion.setAffectedJobTrackingEnabled(tracking);
        insertion.setRegretScoringFunction((best, second, job) -> job.equals(marker) ? 2 : 1);
        assertThat(insertion.insertJobs(routes, List.of(marker, target))).isEmpty();
        assertThat(routes.stream().filter(route -> route.getTourActivities().getJobs().contains(target))
                .map(route -> route.getVehicle().getId())).containsExactly("C");
    }

    static Stream<Arguments> cases() {
        var cases = new ArrayList<Arguments>();
        for (boolean tracking : List.of(false, true))
            for (int k : List.of(2, 3))
                for (boolean coordinates : List.of(false, true))
                    for (boolean filter : List.of(false, true))
                        for (DependencyType dependency : new DependencyType[]{null, DependencyType.INTRA_ROUTE, DependencyType.INTER_ROUTE})
                            cases.add(Arguments.of(tracking, k, coordinates, filter, dependency));
        return cases.stream();
    }

    @ParameterizedTest @MethodSource("cases")
    void refreshesThirdBestRouteAfterItFills(boolean tracking, int k, boolean coordinates, boolean filter, DependencyType dependency) {
        var location = coordinates ? Location.newInstance(0, 0) : Location.newInstance("depot");
        var type = VehicleTypeImpl.Builder.newInstance("van").addCapacityDimension(0, 2)
                .setCostPerDistance(0).setCostPerTransportTime(0).build();
        var costs = VehicleRoutingTransportCostsMatrix.Builder.newInstance(false)
                .addTransportDistance("depot", "depot", 0).addTransportTime("depot", "depot", 0).build();
        var builder = VehicleRoutingProblem.Builder.newInstance().setRoutingCost(costs)
                .setFleetSize(VehicleRoutingProblem.FleetSize.FINITE);
        var routes = new ArrayList<VehicleRoute>();
        for (String id : List.of("A", "B", "C")) {
            var vehicle = VehicleImpl.Builder.newInstance(id).setType(type)
                    .setStartLocation(location).addSkill(id).build();
            var existing = service("existing-" + id, null, location);
            builder.addVehicle(vehicle).addJob(existing);
            routes.add(VehicleRoute.Builder.newInstance(vehicle).addService(existing).build());
        }
        var fillC = service("fill-C", "C", location);
        var fillA = service("fill-A", "A", location);
        var fillB = service("fill-B", "B", location);
        var overflow = service("overflow", null, location);
        List<Job> pending = List.of(fillC, fillA, fillB, overflow);
        pending.forEach(builder::addJob);
        var problem = builder.build();
        routes.replaceAll(route -> VehicleRoute.Builder.newInstance(route.getVehicle())
                .setJobActivityFactory(problem::copyAndGetActivities)
                .addService((Service) route.getTourActivities().getJobs().iterator().next()).build());
        var states = new StateManager(problem);
        states.updateLoadStates();
        states.updateSkillStates();
        var constraints = new ConstraintManager(problem, states);
        constraints.addLoadConstraint();
        constraints.addSkillsConstraint();
        var fleet = new FiniteFleetManagerFactory(problem.getVehicles()).createFleetManager(problem);
        routes.forEach(route -> fleet.lock(route.getVehicle()));
        var insertion = (RegretInsertionFast) new InsertionBuilder(problem, fleet, states, constraints)
                .setInsertionStrategy(InsertionBuilder.Strategy.REGRET).setFastRegret(true)
                .setAllowVehicleSwitch(false).build();
        insertion.setAffectedJobTrackingEnabled(tracking);
        var dependencies = new DependencyType[problem.getJobs().size() + 2];
        Arrays.fill(dependencies, dependency);
        insertion.setDependencyTypes(dependencies);
        insertion.setRegretK(k);
        insertion.setRegretKScoringFunction((alternatives, job) -> 4 - pending.indexOf(job));
        // During learning all alternatives are cached, but C is outside overflow's
        // two-neighbor shortlist when coordinates are present. Its entry must still refresh.
        if (filter) {
            var spatial = new AdaptiveSpatialFilter(5, 2) {
                @Override public List<VehicleRoute> getNearestRoutes(Job job, Collection<VehicleRoute> candidates, int limit) {
                    return job.equals(overflow) ? candidates.stream().limit(2).toList() : new ArrayList<>(candidates);
                }
            };
            insertion.setSpatialFilter(spatial);
            // Four jobs populate the cache during learning. The first insertion
            // completes learning, so subsequent filtered refreshes omit C.
            insertion.addListener((JobInsertedListener) (job, route, data) ->
                    spatial.recordComparison(null, null, null, null));
        }
        insertion.addListener(states);
        // C is initially only the third zero-cost alternative for overflow. Fill it first,
        // then A and B. A stale C entry must not become usable when A and B disappear.
        insertion.setRegretScoringFunction((best, second, job) -> 4 - pending.indexOf(job));
        var unassigned = insertion.insertJobs(routes, pending);
        assertThat(routes).allSatisfy(route -> assertThat(route.getTourActivities().getJobs().size())
                .as("load on %s with affected tracking=%s", route.getVehicle().getId(), tracking).isLessThanOrEqualTo(2));
        assertThat(unassigned).containsExactly(overflow);
        assertThat(routes).allSatisfy(route -> assertThat(route.getTourActivities().getJobs()).hasSize(2));
    }

    @Test
    void backportRequiresReviewedUpstreamVersion() throws Exception {
        var properties = new Properties();
        try (var source = getClass().getClassLoader().getResourceAsStream(
                "META-INF/maven/com.graphhopper/jsprit-core/pom.properties")) {
            assertThat(source).isNotNull();
            properties.load(source);
        }
        // A new upstream release requires reviewing/removing the one-class backport.
        assertThat(properties.getProperty("version")).isEqualTo("2.0.0");
    }
    private static Service service(String id, String skill, Location location) {
        var builder = Service.Builder.newInstance(id).setLocation(location)
                .addSizeDimension(0, 1);
        if (skill != null) builder.addRequiredSkill(skill);
        return builder.build();
    }
}
