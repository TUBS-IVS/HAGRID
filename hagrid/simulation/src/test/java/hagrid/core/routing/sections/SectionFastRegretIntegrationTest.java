package hagrid.core.routing.sections;

import hagrid.core.routing.CarrierOptimizationService;
import org.junit.jupiter.api.Test;
import org.matsim.freight.carriers.CarrierCapabilities;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

/** Run on both certified native core classpaths, with serial FAST_REGRET. */
class SectionFastRegretIntegrationTest {
 @Test void fastInsertionAtFrontMovesCommonReleaseDeparture() throws Exception {
  var fixture = new SectionDepartureOptimizationTest().fixture(false);
  var carrier = fixture.catalog().originalCarrier();
  carrier.getServices().clear();
  for (String id : List.of("a", "b")) {
   var service = org.matsim.freight.carriers.CarrierService.Builder.newInstance(
     org.matsim.api.core.v01.Id.create(id, org.matsim.freight.carriers.CarrierService.class),
     org.matsim.api.core.v01.Id.createLinkId(id), 1).setServiceDuration(0)
     .setServiceStartingTimeWindow(org.matsim.freight.carriers.TimeWindow.newInstance(100, 200)).build();
   org.matsim.freight.carriers.CarriersUtils.addService(carrier, service);
  }
  var template = carrier.getCarrierCapabilities().getCarrierVehicles().values().iterator().next();
  template.getType().getCostInformation().setFixedCost(100.0);
  carrier.setCarrierCapabilities(CarrierCapabilities.Builder.newInstance().addVehicle(template)
    .setFleetSize(CarrierCapabilities.FleetSize.INFINITE).build());
  var catalog = new SectionCatalog(carrier, fixture.catalog().sections(), "fast-first");
  SectionRoadCosts roads = new SectionRoadCosts() {
   public SectionLeg leg(org.matsim.api.core.v01.Id<org.matsim.api.core.v01.network.Link> from,
       org.matsim.api.core.v01.Id<org.matsim.api.core.v01.network.Link> to, org.matsim.vehicles.VehicleType type) {
    double seconds = switch (from + ">" + to) {
     case "d>a" -> 10;
     case "d>b" -> 20;
     case "b>a" -> 1;
     case "a>b" -> Double.POSITIVE_INFINITY;
     default -> 0;
    };
    return new SectionLeg(Double.isFinite(seconds) ? 0 : seconds, seconds, 0);
   }
   public hagrid.core.routing.RoadLeg materialize(org.matsim.api.core.v01.Id<org.matsim.api.core.v01.network.Link> a,
       org.matsim.api.core.v01.Id<org.matsim.api.core.v01.network.Link> b, double time, org.matsim.vehicles.Vehicle v) {
    throw new UnsupportedOperationException();
   }
   public String fingerprint() { return "fast-first"; }
  };
  var problem = SectionProblemFactory.build(catalog, fixture.net(), roads).problem();
  var vehicle = problem.getVehicles().iterator().next();
  var state = new SectionSearchState(catalog, roads, 200, 200, 0);
  var states = new com.graphhopper.jsprit.core.algorithm.state.StateManager(problem);
  states.updateLoadStates(); states.updateSkillStates(); states.addStateUpdater(state);
  var constraints = new com.graphhopper.jsprit.core.problem.constraint.ConstraintManager(problem, states);
  constraints.addLoadConstraint(); constraints.addSkillsConstraint();
  var adapter = new SectionInsertionAdapter(state, problem);
  constraints.addConstraint(adapter, com.graphhopper.jsprit.core.problem.constraint.ConstraintManager.Priority.CRITICAL);
  var route = com.graphhopper.jsprit.core.problem.solution.route.VehicleRoute.Builder.newInstance(vehicle)
    .setJobActivityFactory(problem.getJobActivityFactory()).setDepartureTime(90)
    .addService((com.graphhopper.jsprit.core.problem.job.Service) problem.getJobs().get("A")).build();
  assertThat(state.evaluate(route).departure()).isEqualTo(90);
  var insertion = new com.graphhopper.jsprit.core.algorithm.recreate.InsertionBuilder(problem, fleet(problem), states, constraints)
    .setInsertionStrategy(com.graphhopper.jsprit.core.algorithm.recreate.InsertionBuilder.Strategy.REGRET)
    .setFastRegret(true).setAllowVehicleSwitch(true).setActivityInsertionCostCalculator(adapter).build();
  insertion.addListener(states);
  var routes = new java.util.ArrayList<com.graphhopper.jsprit.core.problem.solution.route.VehicleRoute>();
  routes.add(route);
  assertThat(insertion.insertJobs(routes, List.of(problem.getJobs().get("B")))).isEmpty();
  assertThat(routes).hasSize(1);
  assertThat(SectionSearchState.order(route)).containsExactly("B", "A");
  assertThat(route.getDepartureTime()).isEqualTo(80);
  var replay = new SectionRouteEvaluator(catalog, roads, 200, 200, 0)
    .evaluate(SectionSearchState.order(route), state.vehicle(route.getVehicle()), route.getDepartureTime());
  assertThat(replay.feasible()).isTrue();
  assertThat(replay.visits()).extracting(SectionVisit::sectionId).containsExactly("B", "A");
  assertThat(replay.endTime()).isEqualTo(101);
  assertThat(replay.variableCost()).isEqualTo(0);
  catalog.requireUnchanged();
 }
 @Test void insertionMovesDepartureAndStoredRouteReplaysPhysically() {
  var fixture = new SectionDepartureOptimizationTest().fixture(false);
  var carrier = fixture.catalog().originalCarrier();
  carrier.setCarrierCapabilities(CarrierCapabilities.Builder.newInstance()
    .addVehicle(fixture.vehicle()).setFleetSize(CarrierCapabilities.FleetSize.INFINITE).build());
  var catalog = new SectionCatalog(carrier, fixture.catalog().sections(), "fast-departure");
  var vehicle = catalog.originalCarrier().getCarrierCapabilities().getCarrierVehicles().values().iterator().next();
  var before = new SectionSearchState(catalog, fixture.roads(), 20, 20, 0)
    .evaluate(List.of("A"), vehicle, 0);
  assertThat(before.departure()).isEqualTo(0);
  var result = new CarrierOptimizationService().searchSections(catalog, fixture.net(), fixture.roads(),
    20, 20, 0, 4711, new SectionSearchPolicy(100, .25, .40, true));
  assertThat(result.iterations()).isEqualTo(100);
  assertThat(result.solution().getUnassignedJobs()).isEmpty();
  assertThat(result.solution().getRoutes()).hasSize(1);
  var route = result.solution().getRoutes().iterator().next();
  assertThat(SectionSearchState.order(route)).containsExactly("A", "B");
  assertThat(route.getDepartureTime()).isEqualTo(100);
  var physicalVehicle = new SectionSearchState(catalog, fixture.roads(), 20, 20, 0).vehicle(route.getVehicle());
  var replay = new SectionRouteEvaluator(catalog, fixture.roads(), 20, 20, 0)
    .evaluate(SectionSearchState.order(route), physicalVehicle, route.getDepartureTime());
  assertThat(replay.feasible()).isTrue();
  assertThat(replay.variableCost()).isEqualTo(0);
  assertThat(replay.endTime()).isEqualTo(100);
  assertThat(replay.visits()).extracting(SectionVisit::sectionId).containsExactly("A", "B");
  catalog.requireUnchanged();
 }
 @Test void insertionReorientsEarlierSectionAndStoredRouteReplays() throws Exception {
  var fixture = new SectionDepartureOptimizationTest().fixture(true);
  var carrier = fixture.catalog().originalCarrier();
  var template = carrier.getCarrierCapabilities().getCarrierVehicles().values().iterator().next();
  template.getType().getCostInformation().setFixedCost(100.0);
  carrier.setCarrierCapabilities(CarrierCapabilities.Builder.newInstance()
    .addVehicle(template).setFleetSize(CarrierCapabilities.FleetSize.INFINITE).build());
  var catalog = new SectionCatalog(carrier, fixture.catalog().sections(), "fast-orientation");
  SectionRoadCosts roads = new SectionRoadCosts() {
   public SectionLeg leg(org.matsim.api.core.v01.Id<org.matsim.api.core.v01.network.Link> from,
       org.matsim.api.core.v01.Id<org.matsim.api.core.v01.network.Link> to,
       org.matsim.vehicles.VehicleType type) {
    String key = from + ">" + to;
    double distance = switch (key) {
     case "d>a", "a>d", "r>d", "r>b", "b>d" -> 1;
     case "d>r" -> 5;
     case "a>b" -> 100;
     case "b>a", "b>r" -> Double.POSITIVE_INFINITY;
     default -> 50;
    };
    return new SectionLeg(distance, Double.isFinite(distance) ? 1 : distance, 0);
   }
   public hagrid.core.routing.RoadLeg materialize(org.matsim.api.core.v01.Id<org.matsim.api.core.v01.network.Link> a,
       org.matsim.api.core.v01.Id<org.matsim.api.core.v01.network.Link> b, double time, org.matsim.vehicles.Vehicle v) {
    throw new UnsupportedOperationException();
   }
   public String fingerprint() { return "fast-orientation"; }
  };
  var vehicle = catalog.originalCarrier().getCarrierCapabilities().getCarrierVehicles().values().iterator().next();
  var before = new SectionSearchState(catalog, roads, 200, 200, 0).evaluate(List.of("A"), vehicle, 0);
  assertThat(before.evaluation().visits().getFirst().variant()).isEqualTo(0);
  var problem = SectionProblemFactory.build(catalog, fixture.net(), roads).problem();
  var state = new SectionSearchState(catalog, roads, 200, 200, 0);
  var states = new com.graphhopper.jsprit.core.algorithm.state.StateManager(problem);
  states.updateLoadStates(); states.updateSkillStates(); states.addStateUpdater(state);
  var constraints = new com.graphhopper.jsprit.core.problem.constraint.ConstraintManager(problem, states);
  constraints.addLoadConstraint(); constraints.addSkillsConstraint();
  var adapter = new SectionInsertionAdapter(state, problem);
  constraints.addConstraint(adapter, com.graphhopper.jsprit.core.problem.constraint.ConstraintManager.Priority.CRITICAL);
  var nativeVehicle = problem.getVehicles().iterator().next();
  var initial = com.graphhopper.jsprit.core.problem.solution.route.VehicleRoute.Builder.newInstance(nativeVehicle)
    .setJobActivityFactory(problem.getJobActivityFactory())
    .addService((com.graphhopper.jsprit.core.problem.job.Service) problem.getJobs().get("A")).build();
  var insertion = new com.graphhopper.jsprit.core.algorithm.recreate.InsertionBuilder(problem, fleet(problem), states, constraints)
    .setInsertionStrategy(com.graphhopper.jsprit.core.algorithm.recreate.InsertionBuilder.Strategy.REGRET)
    .setFastRegret(true).setAllowVehicleSwitch(true).setActivityInsertionCostCalculator(adapter).build();
  insertion.addListener(states);
  var insertedRoutes = new java.util.ArrayList<com.graphhopper.jsprit.core.problem.solution.route.VehicleRoute>();
  insertedRoutes.add(initial);
  assertThat(insertion.insertJobs(insertedRoutes, List.of(problem.getJobs().get("B")))).isEmpty();
  assertThat(insertedRoutes).hasSize(1);
  assertThat(SectionSearchState.order(initial)).containsExactly("A", "B");
  var directReplay = new SectionRouteEvaluator(catalog, roads, 200, 200, 0)
    .evaluate(SectionSearchState.order(initial), state.vehicle(initial.getVehicle()), initial.getDepartureTime());
  assertThat(directReplay.feasible()).isTrue();
  assertThat(directReplay.visits().getFirst().variant()).isEqualTo(1);
  assertThat(directReplay.variableCost()).isEqualTo(7);
  var result = new CarrierOptimizationService().searchSections(catalog, fixture.net(), roads,
    200, 200, 0, 4711, new SectionSearchPolicy(100, .25, .40, true));
  assertThat(result.iterations()).isEqualTo(100);
  assertThat(result.solution().getUnassignedJobs()).isEmpty();
  assertThat(result.solution().getRoutes()).hasSize(1);
  var route = result.solution().getRoutes().iterator().next();
  assertThat(SectionSearchState.order(route)).containsExactly("A", "B");
  var physicalVehicle = new SectionSearchState(catalog, roads, 200, 200, 0).vehicle(route.getVehicle());
  var replay = new SectionRouteEvaluator(catalog, roads, 200, 200, 0)
    .evaluate(SectionSearchState.order(route), physicalVehicle, route.getDepartureTime());
  assertThat(replay.feasible()).isTrue();
  assertThat(replay.visits()).extracting(SectionVisit::sectionId).containsExactly("A", "B");
  assertThat(replay.visits().getFirst().variant()).isEqualTo(1);
  assertThat(replay.variableCost()).isEqualTo(7);
  catalog.requireUnchanged();
 }
 @Test void fastInsertionSwitchesSameTypeShiftTemplate() throws Exception {
  var fixture = new SectionDepartureOptimizationTest().fixture(false);
  var carrier = fixture.catalog().originalCarrier();
  var base = carrier.getCarrierCapabilities().getCarrierVehicles().values().iterator().next();
  base.getType().getCostInformation().setFixedCost(100.0);
  var early = org.matsim.freight.carriers.CarrierVehicle.Builder.newInstance(
    org.matsim.api.core.v01.Id.createVehicleId("early"), base.getLinkId(), base.getType())
    .setEarliestStart(0).setLatestEnd(50).build();
  var late = org.matsim.freight.carriers.CarrierVehicle.Builder.newInstance(
    org.matsim.api.core.v01.Id.createVehicleId("late"), base.getLinkId(), base.getType())
    .setEarliestStart(0).setLatestEnd(200).build();
  carrier.setCarrierCapabilities(CarrierCapabilities.Builder.newInstance().addVehicle(early).addVehicle(late)
    .setFleetSize(CarrierCapabilities.FleetSize.INFINITE).build());
  var catalog = new SectionCatalog(carrier, fixture.catalog().sections(), "fast-switch");
  var problem = SectionProblemFactory.build(catalog, fixture.net(), fixture.roads()).problem();
  var nativeEarly = problem.getVehicles().stream().filter(v -> v.getId().equals("early")).findFirst().orElseThrow();
  var state = new SectionSearchState(catalog, fixture.roads(), 20, 20, 0);
  var states = new com.graphhopper.jsprit.core.algorithm.state.StateManager(problem);
  states.updateLoadStates(); states.updateSkillStates(); states.addStateUpdater(state);
  var constraints = new com.graphhopper.jsprit.core.problem.constraint.ConstraintManager(problem, states);
  constraints.addLoadConstraint(); constraints.addSkillsConstraint();
  var adapter = new SectionInsertionAdapter(state, problem);
  constraints.addConstraint(adapter, com.graphhopper.jsprit.core.problem.constraint.ConstraintManager.Priority.CRITICAL);
  var fleet = fleet(problem);
  var route = com.graphhopper.jsprit.core.problem.solution.route.VehicleRoute.Builder.newInstance(nativeEarly)
    .setJobActivityFactory(problem.getJobActivityFactory())
    .addService((com.graphhopper.jsprit.core.problem.job.Service) problem.getJobs().get("A")).build();
  var insertion = new com.graphhopper.jsprit.core.algorithm.recreate.InsertionBuilder(problem, fleet, states, constraints)
    .setInsertionStrategy(com.graphhopper.jsprit.core.algorithm.recreate.InsertionBuilder.Strategy.REGRET)
    .setFastRegret(true).setAllowVehicleSwitch(true).setActivityInsertionCostCalculator(adapter).build();
  insertion.addListener(states);
  var routes = new java.util.ArrayList<com.graphhopper.jsprit.core.problem.solution.route.VehicleRoute>();
  routes.add(route);
  assertThat(insertion.insertJobs(routes, List.of(problem.getJobs().get("B")))).isEmpty();
  assertThat(routes).hasSize(1);
  assertThat(route.getVehicle().getId()).startsWith("late");
  assertThat(SectionSearchState.order(route)).containsExactly("A", "B");
  assertThat(route.getDepartureTime()).isEqualTo(100);
  var replay = new SectionRouteEvaluator(catalog, fixture.roads(), 20, 20, 0)
    .evaluate(SectionSearchState.order(route), state.vehicle(route.getVehicle()), route.getDepartureTime());
  assertThat(replay.feasible()).isTrue();
  assertThat(replay.visits()).extracting(SectionVisit::sectionId).containsExactly("A", "B");
  assertThat(replay.endTime()).isEqualTo(100);
  catalog.requireUnchanged();
 }
 private static com.graphhopper.jsprit.core.problem.vehicle.VehicleFleetManager fleet(
   com.graphhopper.jsprit.core.problem.VehicleRoutingProblem problem) throws Exception {
  var factory = new com.graphhopper.jsprit.core.problem.vehicle.InfiniteFleetManagerFactory(problem.getVehicles());
  try {
   return (com.graphhopper.jsprit.core.problem.vehicle.VehicleFleetManager) factory.getClass()
     .getMethod("createFleetManager", com.graphhopper.jsprit.core.problem.VehicleRoutingProblem.class).invoke(factory, problem);
  } catch (NoSuchMethodException oldCore) {
   return (com.graphhopper.jsprit.core.problem.vehicle.VehicleFleetManager) factory.getClass()
     .getMethod("createFleetManager").invoke(factory);
  }
 }
 @Test void oldConstructorAndExplicitFalseHaveSamePolicy() {
  assertThat(new SectionSearchPolicy(100, .25, .40))
    .isEqualTo(new SectionSearchPolicy(100, .25, .40, false));
 }
}
