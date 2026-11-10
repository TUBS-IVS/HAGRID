package hagrid.core.simulation;

import hagrid.core.HagridConfig;
import hagrid.core.config.HagridConfigResolver;
import hagrid.core.routing.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.*;
import org.matsim.core.controler.listener.BeforeMobsimListener;
import org.matsim.core.controler.listener.IterationEndsListener;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.freight.carriers.*;

import java.nio.file.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class CarrierPlanEvolutionIntegrationTest {
    @TempDir Path output;

    @Test void nativeControllerUsesLatestCompletedTimesThenRevisitsScoredHistory() throws Exception {
        try (var f = new ReplanningCoordinatorTest.Fixture(3)) {
            var settings = HagridConfigResolver.resolve(new HagridConfig(), HagridConfigResolver.hannoverTarget(),
                    HagridConfigResolver.Overrides.of(Map.of("optimization.replanningStartIteration", "2",
                            "optimization.replanningIterations", "1", "optimization.carriersPerGroup", "2")), Map.of());
            var config = ConfigUtils.createConfig();
            config.controller().setLastIteration(20);
            config.controller().setOutputDirectory(output.toString());
            config.controller().setDumpDataAtEnd(false);
            config.controller().setCreateGraphsInterval(0);
            config.controller().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.overwriteExistingFiles);
            config.qsim().setStartTime(0);
            config.qsim().setEndTime(12000);
            config.qsim().setNumberOfThreads(1);
            config.global().setNumberOfThreads(1);
            var scenario = ScenarioUtils.createScenario(config);
            org.matsim.core.network.NetworkUtils.setNetworkChangeEvents(scenario.getNetwork(), List.of());
            f.network.getNodes().values().forEach(scenario.getNetwork()::addNode);
            f.network.getLinks().values().forEach(scenario.getNetwork()::addLink);
            scenario.addScenarioElement("carriers", f.carriers);
            scenario.addScenarioElement("carrierVehicleTypes", f.types);
            scenario.addScenarioElement("carNetwork", scenario.getNetwork());
            scenario.addScenarioElement("bikeNetwork", scenario.getNetwork());
            var initial = NativeModalTransportCosts.create(scenario.getNetwork(), f.types, "car");
            for (Carrier carrier : f.carriers.getCarriers().values()) {
                var vehicle = carrier.getCarrierCapabilities().getCarrierVehicles().values().iterator().next();
                carrier.setCarrierCapabilities(CarrierCapabilities.Builder.newInstance()
                        .addVehicle(CarrierVehicle.Builder.newInstance(vehicle.getId(), vehicle.getLinkId(), vehicle.getType())
                                .setEarliestStart(0).setLatestEnd(11500).build()).build());
                var job = carrier.getServices().values().iterator().next();
                carrier.getServices().put(job.getId(), CarrierService.Builder.newInstance(job.getId(), job.getServiceLinkId())
                        .setCapacityDemand(1).setServiceDuration(1).setServiceStartingTimeWindow(TimeWindow.newInstance(0, 11000)).build());
                var result = new CarrierOptimizationService().optimize(carrier, initial, settings, 42, 1,
                        HAGRIDRouterUtils.AlgorithmPhase.INITIAL, 25200, "initial");
                assertThat(result.diagnostics()).isEmpty();
                carrier.clearPlans();
                carrier.addPlan(result.plan());
            }

            var times = new ReplanningLifecycleIntegrationTest.ResettingTimes(f.carriers);
            var driven = new ReplanningLifecycleIntegrationTest.Driven();
            var departures = new HashMap<Carrier, Set<Double>>();
            var favourites = new HashMap<Carrier, CarrierPlan>();
            var revisited = new HashSet<Carrier>();
            var lastSeen = new HashMap<Carrier,CarrierPlan>();
            var routedSeconds = new HashMap<Carrier,Double>();
            var nativeRefreshes = new HashSet<Integer>();
            var controller = new Controler(scenario);
            controller.addOverridingModule(new org.matsim.freight.carriers.controller.CarrierModule());
            controller.addOverridingModule(new HAGRIDSimulationModule(scenario, false, 20, settings));
            controller.addOverridingModule(new AbstractModule() {
                public void install() {
                    addTravelTimeBinding("car").toInstance(times);
                    addEventHandlerBinding().toInstance(times);
                    addEventHandlerBinding().toInstance(driven);
                    addControlerListenerBinding().toInstance((BeforeMobsimListener) event -> {
                        for (var carrier : f.carriers.getCarriers().values()) {
                            assertThat(carrier.getPlans()).hasSizeLessThanOrEqualTo(5);
                            var origin = (Integer) carrier.getAttributes().getAttribute("routingPlanningIteration");
                            if (origin == null) continue;
                            var selected = carrier.getSelectedPlan();
                            var tour = selected.getScheduledTours().iterator().next();
                            boolean changed = lastSeen.put(carrier,selected) != selected;
                            if (changed) {
                                // ResettingTimes publishes 10*(previousIteration+1), before handler reset.
                                routedSeconds.put(carrier,10.0 * event.getIteration());
                                if (event.getIteration() > origin) {
                                    nativeRefreshes.add(event.getIteration());
                                }
                            }
                            double expectedSeconds = routedSeconds.get(carrier);
                            var leg = (Tour.Leg) tour.getTour().getTourElements().getFirst();
                            assertThat(leg.getExpectedTransportTime()).isEqualTo(expectedSeconds);
                            if (changed && event.getIteration() > origin)
                                assertThat(selected.getAttributes().getAttribute("nativeRoutingMeasurementIteration"))
                                        .isEqualTo(event.getIteration()-1);
                            if (event.getIteration() < 16)
                                departures.computeIfAbsent(carrier, unused -> new HashSet<>()).add(tour.getDeparture());
                            if (event.getIteration() == 16 && favourites.containsKey(carrier)
                                    && departure(selected) == departure(favourites.get(carrier))
                                    && Objects.equals(selected.getScore(),1e9)) revisited.add(carrier);
                        }
                    });
                    addControlerListenerBinding().toInstance((IterationEndsListener) event -> {
                        // At the 80% switch, give a previously executed departure the best measured score.
                        if (event.getIteration() != 15) return;
                        for (var carrier : f.carriers.getCarriers().values()) {
                            double current = departure(carrier.getSelectedPlan());
                            carrier.getPlans().stream().filter(p -> p.getScore() != null && departure(p) != current)
                                    .filter(p -> departures.get(carrier).contains(departure(p))).findFirst().ifPresent(p -> {
                                        p.setScore(1e9);
                                        favourites.put(carrier, p);
                                        var invalid = new CarrierPlan(new ArrayList<>());
                                        invalid.setScore(2e9);
                                        carrier.addPlan(invalid);
                                    });
                        }
                    });
                }
            });
            controller.run();
            assertThat(departures.values()).as("QSim receives distinct native departure-time mutations after jsprit")
                    .anySatisfy(values -> assertThat(values).hasSizeGreaterThan(1));
            assertThat(favourites).as("an executed alternative remains in carrier memory").isNotEmpty();
            assertThat(nativeRefreshes).as("native routing refreshes measurements after grouped jsprit")
                    .anySatisfy(iteration -> assertThat(iteration).isGreaterThan(3));
            assertThat(revisited).as("BestPlan selects the highest valid scored departure at iteration 16")
                    .containsExactlyInAnyOrderElementsOf(favourites.keySet());
            assertThat(driven.iterations).contains(2, 3, 15, 16, 20);
            assertThat(driven.stuck).isZero();
            var rows = Files.readAllLines(output.resolve("carrier-routing-outcomes.csv"));
            assertThat(rows).hasSize(4);
            for (var carrier : f.carriers.getCarriers().values()) {
                var row = rows.stream().filter(line -> line.startsWith(carrier.getId()+","))
                        .findFirst().orElseThrow().split(",",-1);
                assertThat(row[14]).isEqualTo("VALIDATED_FINAL");
                assertThat(Double.parseDouble(row[21])).isEqualTo(carrier.getAttributes().getAttribute("validatedRoutingCost"));
            }
        }
    }

    private static double departure(CarrierPlan plan) {
        return plan.getScheduledTours().iterator().next().getDeparture();
    }
}
