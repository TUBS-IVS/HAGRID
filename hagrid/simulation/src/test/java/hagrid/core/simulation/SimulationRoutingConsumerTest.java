package hagrid.core.simulation;

import hagrid.core.HagridConfig;
import hagrid.core.config.HagridConfigResolver;
import hagrid.core.config.ResolvedHagridConfig;
import hagrid.core.routing.*;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.network.NetworkUtils;
import org.matsim.freight.carriers.*;
import org.matsim.vehicles.VehicleUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

class SimulationRoutingConsumerTest {
    @Test
    void launchFreezesOneEffectiveConfigurationBeforeUse() {
        var launch = SimulationRunnerUtils.parseScenario(
                "concept=basecase,date=2025-05-13,jspritIter=2,cargoBikeMaxSpeedKmh=18,zoneCaching=false");
        var resolved = launch.getResolvedRoutingConfig();
        assertThat(launch.getResolvedRoutingConfig()).isSameAs(resolved);
        assertThat(resolved.optimization().initialIterations()).isZero();
        assertThat(resolved.optimization().replanningIterations()).hasValue(2);
        assertThat(resolved.fleet().cargoBikeMaxSpeedMps()).isEqualTo(5);
        assertThat(resolved.routing().zoneBasedCachingEnabled()).isFalse();
        assertThat(resolved.run().runId()).hasValue(launch.getRunId());
        assertThatIllegalStateException().isThrownBy(() -> launch.captureExplicitScenarioInputs(Map.of()));
    }

    @Test
    void offlineAndPassengerOnlyLaunchesDoNotAcquireReplanningSchedule() {
        var offline = SimulationRunnerUtils.parseScenario(
                "concept=lmd_baseline,date=2025-05-13,jspritIter=2,zoneCaching=false,uTurnPenalty=8");
        var resolved = offline.getResolvedRoutingConfig();
        assertThat(resolved.optimization().offlineIterations()).hasValue(2);
        assertThat(resolved.optimization().replanningStartIteration()).isEmpty();
        assertThat(resolved.routing().zoneBasedCachingEnabled()).isFalse();
        assertThat(resolved.tourRules().uTurnPenaltyCost()).isZero();
        var passenger = SimulationRunnerUtils.parseScenario("concept=drt_baseline,date=2025-05-13,freight=false");
        assertThat(passenger.getResolvedRoutingConfig().optimization().offlineIterations()).isEmpty();
    }

    @Test
    void replanningHonoursConfiguredStartGroupAndSearchBudget() {
        var f = fixture();
        var first = carrier("first", f);
        var second = carrier("second", f);
        var strategy = new CarrierVehicleReRouter(f.network, f.network, f.types,
                (l, t, p, v) -> 10, false, Map.of(), settings(Map.of(
                        "optimization.replanningStartIteration", "2", "optimization.carriersPerGroup", "1")))
                .createStrategy();
        strategy.init(() -> 1);
        strategy.run(first);
        strategy.finish();
        assertThat(first.getAttributes().getAttribute("hadFirstReplanning")).isNull();
        strategy.init(() -> 2);
        strategy.run(first);
        strategy.run(second);
        strategy.finish();
        assertThat(first.getAttributes().getAttribute("hadFirstReplanning")).isEqualTo(true);
        assertThat(second.getAttributes().getAttribute("hadFirstReplanning")).isNull();
        assertThat(CarriersUtils.getJspritIterations(first)).isEqualTo(2);
        assertThat(first.getSelectedPlan().getScheduledTours()).hasSize(1);
        assertThat(first.getSelectedPlan().getScheduledTours().iterator().next().getTour().getTourElements())
                .filteredOn(e -> e instanceof Tour.ServiceActivity).hasSize(2);
    }

    @Test
    void solverUsesConfiguredTourLimitRatherThanItsFormerConstant() {
        var f = fixture();
        var c = carrier("short-tour", f);
        CarriersUtils.setJspritIterations(c, 9);
        CarriersUtils.setJspritComputationTime(c, 12.5);
        var strategy = new CarrierVehicleReRouter(f.network, f.network, f.types,
                (l, t, p, v) -> 10, false, Map.of(), settings(Map.of(
                        "tourRules.maxRouteDurationSeconds", "60", "optimization.replanningStartIteration", "0")))
                .createStrategy();
        strategy.init(() -> 0);
        strategy.run(c);
        // Each service takes 120 s; none can fit a 60 s tour, even with zero drive time.
        // A correctly enforced limit cannot turn the incomplete result into a successful plan.
        assertThatThrownBy(strategy::finish)
                .hasStackTraceContaining("Invalid replanning result for carrier short-tour")
                .hasStackTraceContaining("Unassigned jobs [s0, s1]");
        assertThat(c.getSelectedPlan().getScheduledTours()).isEmpty();
        assertThat(c.getAttributes().getAttribute("hadFirstReplanning")).isNull();
        assertThat(CarriersUtils.getJspritIterations(c)).isEqualTo(9);
        assertThat(CarriersUtils.getJspritComputationTime(c)).isEqualTo(12.5);
    }

    @Test
    void resolvedStrategyStopsOnlyTimeInnovationAtEightyPercent() {
        var f = fixture();
        f.scenario.addScenarioElement("carNetwork", f.network);
        f.scenario.addScenarioElement("bikeNetwork", f.network);
        CarriersUtils.getOrAddCarrierVehicleTypes(f.scenario).getVehicleTypes().putAll(f.types.getVehicleTypes());
        Map<String, org.matsim.core.router.util.TravelTime> times = Map.of("car", (l, t, p, v) -> 10);
        var costsFactory = new hagrid.core.routing.ZoneBasedTransportCostsFactory(
                f.scenario, new Carriers(List.of()), times, f.scenario.getConfig(), false, 0);
        var settings = settings(Map.of("optimization.replanningStartIteration", "2", "optimization.carriersPerGroup", "1"));
        var provider = new ReplanningStrategies(f.types, false, 30, settings);
        f.scenario.addScenarioElement("carriers",new Carriers());
        var injector = com.google.inject.Guice.createInjector(new com.google.inject.AbstractModule() {
            @Override protected void configure() {
                bind(Network.class).toInstance(f.network);
                bind(org.matsim.api.core.v01.Scenario.class).toInstance(f.scenario);
                bind(org.matsim.core.config.Config.class).toInstance(f.scenario.getConfig());
                bind(ResolvedHagridConfig.class).toInstance(settings);
                bind(org.matsim.core.router.util.LeastCostPathCalculatorFactory.class)
                        .toInstance(new org.matsim.core.router.DijkstraFactory());
                bind(new com.google.inject.TypeLiteral<Map<String, org.matsim.core.router.util.TravelTime>>() {})
                        .toInstance(times);
                bind(hagrid.core.routing.VRPTransportCostsFactory.class).toInstance(costsFactory);
            }
        });
        injector.injectMembers(provider);
        var manager = provider.get();
        manager.run(List.of(),23,()->23);
        assertThat(manager.getWeights(null)).containsExactly(1.0,0.5);
        manager.run(List.of(),24,()->24);
        assertThat(manager.getWeights(null)).containsExactly(1.0,0.0);
    }

    @Test
    void offlineLaunchCannotBeWiredToConventionalReplanningModule() {
        var launch = SimulationRunnerUtils.parseScenario("concept=lmd_baseline,date=2025-05-13,jspritIter=2");
        var scenario = org.matsim.core.scenario.ScenarioUtils.createScenario(org.matsim.core.config.ConfigUtils.createConfig());
        assertThatIllegalArgumentException().isThrownBy(() ->
                new HAGRIDSimulationModule(scenario, true, 30, launch.getResolvedRoutingConfig()))
                .withMessageContaining("replanning");
    }

    @Test
    void nativeMutationCanWorkOverSevenHoursWithoutChangingItsSourceOrExceedingDriverAvailability() {
        var f = fixture();
        var c = carrier("overtime",f);
        c.clearPlans();
        c.getAttributes().putAttribute("hadFirstReplanning",true);
        c.getServices().replaceAll((id,job) -> CarrierService.Builder.newInstance(id,job.getServiceLinkId())
                .setCapacityDemand(1).setServiceDuration(120)
                .setServiceStartingTimeWindow(TimeWindow.newInstance(40000,50000)).build());
        var settings = settings(Map.of("tourRules.maxRouteDurationSeconds","25200","tourRules.maxDriverTimeSeconds","36000"));
        var snapshot = TravelTimeSnapshot.capture(f.network,Map.of("car",(l,t,p,v)->10),Map.of(),24,60000,900);
        try (var context = RoutingContext.create(f.network,f.types,snapshot,settings)) {
            var costs = new ModalTransportCosts(new RoutingRequest(c,context,"car"));
            var source = addScheduledPlan(c,16000,costs);
            var sourceLeg = (Tour.Leg)source.getScheduledTours().iterator().next().getTour().getTourElements().getFirst();
            var sourceRoad = sourceLeg.getRoute();
            org.matsim.core.gbl.MatsimRandom.reset(0);
            NativeCarrierPlanEvolution.apply(c,NativeCarrierPlanEvolution.Choice.MUTATE,context);
            var chosen = c.getSelectedPlan();
            assertThat(chosen).isNotSameAs(source);
            var tour = chosen.getScheduledTours().iterator().next();
            double duration = 40260 - tour.getDeparture();
            assertThat(duration).isBetween(25201.0,36000.0);
            // Return at 40260, four hundred metres at .01 per metre and one fixed unit.
            assertThat((Double)c.getAttributes().getAttribute("validatedRoutingCost")).isCloseTo(duration+5,within(1e-6));
            assertThat(sourceLeg.getRoute()).isSameAs(sourceRoad);
            assertThat(sourceLeg.getExpectedDepartureTime()).isEqualTo(16000);

            var earlier = addScheduledPlan(c,6000,costs);
            var before = List.copyOf(c.getPlans());
            org.matsim.core.gbl.MatsimRandom.reset(0);
            NativeCarrierPlanEvolution.apply(c,NativeCarrierPlanEvolution.Choice.MUTATE,context);
            assertThat(c.getSelectedPlan()).isSameAs(earlier);
            assertThat(c.getPlans()).containsExactlyElementsOf(before);
            assertThat(earlier.getScheduledTours().iterator().next().getDeparture()).isEqualTo(6000);
        } finally { org.matsim.core.gbl.MatsimRandom.reset(); }
    }

    @Test
    void refreshingEqualScoreHistoryKeepsTheFirstBestDepartureAcrossLaterSelections() {
        var f = fixture();
        var c = carrier("tie",f);
        c.clearPlans();
        var snapshot = TravelTimeSnapshot.capture(f.network,Map.of("car",(l,t,p,v)->10),Map.of(),24,60000,900);
        try (var context = RoutingContext.create(f.network,f.types,snapshot,settings(Map.of()))) {
            var costs = new ModalTransportCosts(new RoutingRequest(c,context,"car"));
            addScheduledPlan(c,100,costs);
            addScheduledPlan(c,200,costs);
            NativeCarrierPlanEvolution.apply(c,NativeCarrierPlanEvolution.Choice.BEST,context);
            var winner = c.getSelectedPlan();
            assertThat(winner.getScheduledTours().iterator().next().getDeparture()).isEqualTo(100);
            NativeCarrierPlanEvolution.apply(c,NativeCarrierPlanEvolution.Choice.BEST,context);
            assertThat(c.getSelectedPlan()).isSameAs(winner);
        }
    }

    private static CarrierPlan addScheduledPlan(Carrier carrier,double departure,ModalTransportCosts costs) {
        var vehicle = carrier.getCarrierCapabilities().getCarrierVehicles().values().iterator().next();
        var tour = Tour.Builder.newInstance(Id.create("tour",Tour.class)).scheduleStart(vehicle.getLinkId());
        for (var job : carrier.getServices().values()) {
            tour.addLeg(new Tour.Leg());
            tour.scheduleService(job);
        }
        tour.addLeg(new Tour.Leg()).scheduleEnd(vehicle.getLinkId());
        var plan = new CarrierPlan(List.of(ScheduledTour.newInstance(tour.build(),vehicle,departure)));
        CarrierRoadRouter.routePlan(plan,costs);
        plan.setScore(-10.0);
        carrier.addPlan(plan);
        carrier.setSelectedPlan(plan);
        return plan;
    }

    @Test
    void resolvingLegacyRulesKeepsServedJobsVehicleCountAndRoutedTimes() {
        var f = fixture();
        var legacy = carrier("legacy", f);
        var resolved = carrier("resolved", f);
        var oldStrategy = new CarrierVehicleReRouter(f.network, f.network, f.types,
                (l, t, p, v) -> 10, false, Map.of(), 2).createStrategy();
        var newStrategy = new CarrierVehicleReRouter(f.network, f.network, f.types,
                (l, t, p, v) -> 10, false, Map.of(), settings(Map.of())).createStrategy();
        oldStrategy.init(() -> 25);
        oldStrategy.run(legacy);
        oldStrategy.finish();
        newStrategy.init(() -> 25);
        newStrategy.run(resolved);
        newStrategy.finish();
        assertThat(summary(legacy)).isEqualTo(summary(resolved));
        assertThat(summary(resolved).jobs()).containsExactly("s0", "s1");
        assertThat(summary(resolved).tours()).isEqualTo(1);
        assertThat(CarriersUtils.getJspritIterations(legacy)).isEqualTo(2);
        assertThat(CarriersUtils.getJspritIterations(resolved)).isEqualTo(2);
    }

    @Test
    void legacyConstructorInputsCannotBeReplacedAfterResolution() {
        var launch = new HAGRIDSimulationConfig("basecase", java.time.LocalDate.of(2025, 5, 13),
                30, 2, false, 1500, 0, "");
        var resolved = launch.getResolvedRoutingConfig();
        assertThatIllegalStateException().isThrownBy(() ->
                launch.captureExplicitScenarioInputs(Map.of("jspritIter", "99")));
        assertThat(launch.getResolvedRoutingConfig()).isSameAs(resolved);
        assertThat(resolved.optimization().replanningIterations()).hasValue(2);
    }

    @Test void legacyEmptyBatchCreatesNoExecutorAndBudgetRejectionPublishesNothing() {
        var f=fixture();var c=carrier("oversized",f);
        var costs=CarrierRoutingCosts.withLegacyFallback(Map.of(),f.network,f.network,f.types,(l,t,p,v)->10);
        var optimizer=new LegacyCarrierPlanOptimizer(costs,2,25200,settings(Map.of()));
        var created=new java.util.concurrent.atomic.AtomicInteger();
        var worker=new java.util.concurrent.atomic.AtomicReference<hagrid.core.routing.CarrierBatchExecutor>();
        var module=new CarrierReplanningModule(costs,optimizer,25,16,()->{
            created.incrementAndGet();
            var executor=new hagrid.core.routing.CarrierBatchExecutor(2,100,java.time.Duration.ofSeconds(2));
            worker.set(executor);return executor;
        },carrier->101);
        module.prepareReplanning(()->25);module.finishReplanning();
        assertThat(created).hasValue(0);
        var before=c.getSelectedPlan();module.handlePlan(before);
        assertThatThrownBy(module::finishReplanning).hasMessageContaining("oversized").hasMessageContaining("budget");
        assertThat(created).hasValue(1);assertThat(worker.get().createdWorkers()).isZero();
        assertThat(c.getSelectedPlan()).isSameAs(before);
        assertThat(before.getScheduledTours()).isEmpty();
        assertThat(c.getAttributes().getAttribute("hadFirstReplanning")).isNull();
    }
    @Test void sharedLegacyExecutorPublishesACompleteNativeGroupAndTerminatesItsScopedPool() {
        var f=fixture();var first=carrier("first-batch",f);var second=carrier("second-batch",f);
        var costs=CarrierRoutingCosts.withLegacyFallback(Map.of(),f.network,f.network,f.types,(l,t,p,v)->10);
        var optimizer=new LegacyCarrierPlanOptimizer(costs,2,25200,settings(Map.of()));
        var executor=new hagrid.core.routing.CarrierBatchExecutor(2,100,java.time.Duration.ofSeconds(2));
        var module=new CarrierReplanningModule(costs,optimizer,25,16,()->executor,carrier->50);
        module.prepareReplanning(()->25);module.handlePlan(first.getSelectedPlan());module.handlePlan(second.getSelectedPlan());
        module.finishReplanning();
        assertThat(List.of(first,second)).allSatisfy(c->{
            assertThat(c.getSelectedPlan().getScheduledTours()).hasSize(1);
            assertThat(c.getAttributes().getAttribute("hadFirstReplanning")).isEqualTo(true);
        });
        assertThat(executor.reservedBytes()).isZero();
        assertThatThrownBy(()->executor.run(List.of(),d->d,r->{})).hasMessageContaining("unavailable");
    }
    @Test void resolvedLegacySolverCapAlsoLimitsTheSuppliedExecutor() {
        var f=fixture();var first=carrier("first-capped",f);var second=carrier("second-capped",f);
        var costs=CarrierRoutingCosts.withLegacyFallback(Map.of(),f.network,f.network,f.types,(l,t,p,v)->10);
        var optimizer=new LegacyCarrierPlanOptimizer(costs,2,25200,
                settings(Map.of("resources.maxActiveSolvers","1")));
        var executor=new hagrid.core.routing.CarrierBatchExecutor(2,100,java.time.Duration.ofSeconds(2));
        var module=new CarrierReplanningModule(costs,optimizer,25,16,()->executor,carrier->50);
        module.prepareReplanning(()->25);module.handlePlan(first.getSelectedPlan());module.handlePlan(second.getSelectedPlan());
        module.finishReplanning();
        assertThat(executor.createdWorkers()).isEqualTo(1);
        assertThat(executor.reservedBytes()).isZero();
        assertThat(List.of(first,second)).allSatisfy(c ->
                assertThat(c.getSelectedPlan().getScheduledTours()).hasSize(1));
        assertThatThrownBy(()->executor.run(List.of(),d->d,r->{})).hasMessageContaining("unavailable");
    }
    private record PlanSummary(List<String> jobs, int tours, double distance, double travelSeconds) {}

    private static PlanSummary summary(Carrier c) {
        var tours = c.getSelectedPlan().getScheduledTours();
        var elements = tours.stream().flatMap(t -> t.getTour().getTourElements().stream()).toList();
        var jobs = elements.stream().filter(e -> e instanceof Tour.ServiceActivity)
                .map(e -> ((Tour.ServiceActivity) e).getService().getId().toString()).sorted().toList();
        var legs = elements.stream().filter(e -> e instanceof Tour.Leg).map(e -> (Tour.Leg) e).toList();
        return new PlanSummary(jobs, tours.size(), legs.stream().mapToDouble(l -> l.getRoute().getDistance()).sum(),
                legs.stream().mapToDouble(Tour.Leg::getExpectedTransportTime).sum());
    }

    private static ResolvedHagridConfig settings(Map<String, String> run) {
        var values = new java.util.LinkedHashMap<>(run);
        values.put("optimization.replanningIterations", "2");
        return HagridConfigResolver.resolve(new HagridConfig(), HagridConfigResolver.hannoverTarget(),
                HagridConfigResolver.Overrides.of(values), Map.of());
    }

    private record Fixture(Network network, CarrierVehicleTypes types, org.matsim.api.core.v01.Scenario scenario) {}

    private static Fixture fixture() {
        var scenario = org.matsim.core.scenario.ScenarioUtils.createScenario(org.matsim.core.config.ConfigUtils.createConfig());
        var network = scenario.getNetwork();
        var a = NetworkUtils.createAndAddNode(network, Id.createNodeId("a"), new Coord(0, 0));
        var b = NetworkUtils.createAndAddNode(network, Id.createNodeId("b"), new Coord(100, 0));
        var c = NetworkUtils.createAndAddNode(network, Id.createNodeId("c"), new Coord(200, 0));
        var depot = NetworkUtils.createAndAddLink(network, Id.createLinkId("depot"), a, b, 100, 10, 1000, 1);
        var stop = NetworkUtils.createAndAddLink(network, Id.createLinkId("stop"), b, c, 100, 10, 1000, 1);
        var back = NetworkUtils.createAndAddLink(network, Id.createLinkId("back"), c, a, 200, 10, 1000, 1);
        List.of(depot, stop, back).forEach(l -> l.setAllowedModes(Set.of("car")));
        var type = VehicleUtils.getFactory().createVehicleType(Id.create("van", org.matsim.vehicles.VehicleType.class));
        type.setNetworkMode("car");
        type.getCapacity().setOther(10.0);
        type.getCostInformation().setFixedCost(1.0);
        type.getCostInformation().setCostsPerSecond(1.0);
        type.getCostInformation().setCostsPerMeter(0.01);
        var types = new CarrierVehicleTypes();
        types.getVehicleTypes().put(type.getId(), type);
        return new Fixture(network, types, scenario);
    }

    private static Carrier carrier(String id, Fixture f) {
        var c = CarriersUtils.createCarrier(Id.create(id, Carrier.class));
        CarriersUtils.setCarrierMode(c, "car");
        var type = f.types.getVehicleTypes().values().iterator().next();
        var vehicle = CarrierVehicle.Builder.newInstance(Id.createVehicleId("van-" + id), Id.createLinkId("depot"), type)
                .setEarliestStart(0).setLatestEnd(50000).build();
        c.setCarrierCapabilities(CarrierCapabilities.Builder.newInstance().addVehicle(vehicle)
                .setFleetSize(CarrierCapabilities.FleetSize.INFINITE).build());
        for (int i = 0; i < 2; i++) {
            CarriersUtils.addService(c, CarrierService.Builder.newInstance(Id.create("s" + i, CarrierService.class),
                    Id.createLinkId("stop")).setCapacityDemand(1).setServiceDuration(120)
                    .setServiceStartingTimeWindow(TimeWindow.newInstance(0, 50000)).build());
        }
        var plan = new CarrierPlan(new java.util.ArrayList<>());
        plan.setScore(0.0);
        c.addPlan(plan);
        c.setSelectedPlan(plan);
        return c;
    }
}
