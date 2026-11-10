package hagrid.core.simulation;

import com.graphhopper.jsprit.core.problem.Location;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleImpl;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleTypeImpl;
import hagrid.core.routing.ZoneBasedTransportCosts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.router.util.TravelTime;
import org.matsim.freight.carriers.*;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class ReplanningModalNetworkTest {
    @ParameterizedTest
    @ValueSource(strings = {"car", "bike", "cargobike"})
    void explicitProviderSuppliesTheProblemGraphEvenWithoutZones(String mode) {
        var f = fixture(mode);
        var costs = ZoneBasedTransportCosts.Builder.newInstance(f.network, List.of(f.type))
                .setTravelTime((l, t, p, v) -> l.getLength() / 10).build();
        var strategy = new CarrierVehicleReRouter(NetworkUtils.createNetwork(), NetworkUtils.createNetwork(),
                f.types, (l, t, p, v) -> { throw new AssertionError("Registered provider must be used"); },
                false, Map.of(mode, costs), 2).createStrategy();
        solve(strategy, f.carrier);
        assertServed(f);
    }

    @ParameterizedTest
    @ValueSource(strings = {"bike", "cargobike"})
    void nativeFallbackUsesTheExactBikeModeAndExcludesCarOnlyShortcut(String mode) {
        var f = fixture(mode);
        addLink(f.network, "car-shortcut", "b", "c", 1, Set.of("car"));
        TravelTime checked = (l, t, p, v) -> {
            assertThat(l.getAllowedModes()).contains(mode);
            return l.getLength() / 10;
        };
        var strategy = new CarrierVehicleReRouter(NetworkUtils.createNetwork(), f.network, f.types,
                checked, false, Map.of(), 2).createStrategy();
        solve(strategy, f.carrier);
        assertServed(f);
        var legs = f.carrier.getSelectedPlan().getScheduledTours().iterator().next().getTour().getTourElements()
                .stream().filter(e -> e instanceof Tour.Leg).map(e -> (Tour.Leg) e).toList();
        assertThat(legs).allSatisfy(l -> {
            assertThat(l.getRoute().getStartLinkId()).isIn(f.network.getLinks().keySet());
            assertThat(l.getRoute().getEndLinkId()).isIn(f.network.getLinks().keySet());
            assertThat(((org.matsim.core.population.routes.NetworkRoute) l.getRoute()).getLinkIds()).doesNotContain(Id.createLinkId("car-shortcut"));
        });
    }

    @Test
    void registeredLiveProfileCacheIsRefreshedAlsoWhenZoneApproximationIsOff() {
        var f = fixture("car");
        var seconds = new AtomicInteger(10);
        var costs = ZoneBasedTransportCosts.Builder.newInstance(f.network, List.of(f.type))
                .setTravelTime((l, t, p, v) -> seconds.get()).build();
        var vehicle = VehicleImpl.Builder.newInstance("vehicle")
                .setType(VehicleTypeImpl.Builder.newInstance("type").build())
                .setStartLocation(Location.newInstance("depot")).build();
        var from = Location.newInstance("depot");
        var to = Location.newInstance("stop");
        double before = costs.getTransportTime(from, to, 0, null, vehicle);
        seconds.set(20);
        var strategy = new CarrierVehicleReRouter(f.network, f.network, f.types,
                (l, t, p, v) -> seconds.get(), false, Map.of("car", costs), 2).createStrategy();
        strategy.init(() -> 25);
        assertThat(costs.getTransportTime(from, to, 0, null, vehicle)).isEqualTo(before * 2);
        strategy.finish();
    }

    @Test
    void incompleteProviderRegistryFailsExplicitlyRatherThanUsingAnotherMode() {
        var f = fixture("cargobike");
        var car = fixture("car");
        var costs = ZoneBasedTransportCosts.Builder.newInstance(car.network, List.of(car.type))
                .setTravelTime((l, t, p, v) -> 10).build();
        var strategy = new CarrierVehicleReRouter(f.network, f.network, f.types,
                (l, t, p, v) -> 10, false, Map.of("car", costs), 2).createStrategy();
        strategy.init(() -> 25);
        strategy.run(f.carrier);
        assertThatThrownBy(strategy::finish).isInstanceOf(IllegalArgumentException.class)
                .hasStackTraceContaining("No transport costs registered for mode cargobike");
    }

    @ParameterizedTest
    @ValueSource(strings = {"car", "cargobike"})
    void timeAllocationReroutingUsesTheSameModalProviderAndTargetLinkProfile(String mode) {
        var f = fixture(mode);
        var costs = ZoneBasedTransportCosts.Builder.newInstance(f.network, List.of(f.type))
                .setTravelTime((l, t, p, v) -> l.getLength() / 5).build();
        solve(new CarrierVehicleReRouter(f.network, f.network, f.types,
                (l, t, p, v) -> 999, false, Map.of(mode, costs), 2).createStrategy(), f.carrier);
        var scenario = org.matsim.core.scenario.ScenarioUtils.createScenario(
                org.matsim.core.config.ConfigUtils.createConfig());
        scenario.addScenarioElement("carNetwork", f.network);
        scenario.addScenarioElement("bikeNetwork", f.network);
        var factory = new hagrid.core.routing.VRPTransportCostsFactory() {
            @Override public org.matsim.freight.carriers.jsprit.VRPTransportCosts createVRPTransportCosts() {
                throw new AssertionError("No separate car-only provider");
            }
            @Override public Map<String, org.matsim.freight.carriers.jsprit.VRPTransportCosts>
                    createVRPTransportCostsWithModeCongestedTravelTime() {
                return Map.of(mode, costs);
            }
        };
        var provider = new ReplanningStrategies(f.types, false, 30, 2);
        var injector = com.google.inject.Guice.createInjector(new com.google.inject.AbstractModule() {
            @Override protected void configure() {
                bind(Network.class).toInstance(f.network);
                bind(org.matsim.api.core.v01.Scenario.class).toInstance(scenario);
                bind(org.matsim.core.router.util.LeastCostPathCalculatorFactory.class)
                        .toInstance((n, d, t) -> { throw new AssertionError("Do not build an unused global router"); });
                bind(new com.google.inject.TypeLiteral<Map<String, TravelTime>>() {})
                        .toInstance(Map.of(mode, (l, t, p, v) -> 999));
                bind(hagrid.core.routing.VRPTransportCostsFactory.class).toInstance(factory);
            }
        });
        injector.injectMembers(provider);
        var timeAllocation = provider.get().getStrategies(null).get(1);
        timeAllocation.init(() -> 1);
        timeAllocation.run(f.carrier);
        timeAllocation.finish();
        assertServed(f);
        var legs = f.carrier.getSelectedPlan().getScheduledTours().iterator().next().getTour().getTourElements()
                .stream().filter(e -> e instanceof Tour.Leg).map(e -> (Tour.Leg) e).toList();
        assertThat(legs).allSatisfy(leg -> {
            var route = (org.matsim.core.population.routes.NetworkRoute) leg.getRoute();
            double expected = route.getStartLinkId().equals(route.getEndLinkId()) ? 0
                    : (route.getLinkIds().stream().mapToDouble(id -> f.network.getLinks().get(id).getLength()).sum()
                    + f.network.getLinks().get(route.getEndLinkId()).getLength()) / 5;
            assertThat(leg.getExpectedTransportTime()).isCloseTo(expected, within(1e-9));
        });
    }


    @Test
    void queuedCarrierIsOnlyMarkedAfterSuccessfulOptimization() {
        var f = fixture("car");
        var costs = ZoneBasedTransportCosts.Builder.newInstance(f.network, List.of(f.type))
                .setTravelTime((l, t, p, v) -> 10).build();
        var strategy = new CarrierVehicleReRouter(f.network, f.network, f.types,
                (l, t, p, v) -> 10, false, Map.of("car", costs), 2).createStrategy();
        strategy.init(() -> 25);
        strategy.run(f.carrier);
        assertThat(f.carrier.getAttributes().getAttribute("hadFirstReplanning")).isNull();
        strategy.finish();
        assertThat(f.carrier.getAttributes().getAttribute("hadFirstReplanning")).isEqualTo(true);
        assertServed(f);
    }

    @Test
    void failedOptimizationDoesNotPreventRetryOrOverwriteRecordedIterations() {
        var f = fixture("car");
        CarriersUtils.setJspritIterations(f.carrier, 9);
        var fail = new java.util.concurrent.atomic.AtomicBoolean(true);
        var costs = ZoneBasedTransportCosts.Builder.newInstance(f.network, List.of(f.type))
                .setTravelTime((l, t, p, v) -> {
                    if (fail.get()) throw new IllegalStateException("temporary profile failure");
                    return 10;
                }).build();
        var strategy = new CarrierVehicleReRouter(f.network, f.network, f.types,
                (l, t, p, v) -> 10, false, Map.of("car", costs), 2).createStrategy();
        strategy.init(() -> 25);
        strategy.run(f.carrier);
        assertThatThrownBy(strategy::finish).hasStackTraceContaining("temporary profile failure");
        assertThat(f.carrier.getAttributes().getAttribute("hadFirstReplanning")).isNull();
        assertThat(CarriersUtils.getJspritIterations(f.carrier)).isEqualTo(9);
        fail.set(false);
        strategy.init(() -> 26);
        strategy.run(f.carrier);
        strategy.finish();
        assertServed(f);
        assertThat(f.carrier.getAttributes().getAttribute("hadFirstReplanning")).isEqualTo(true);
    }

    @ParameterizedTest
    @ValueSource(ints = {24, 101})
    void optimizationWindowIsReevaluatedForEveryPreparation(int iteration) {
        var f = fixture("car");
        var costs = ZoneBasedTransportCosts.Builder.newInstance(f.network, List.of(f.type))
                .setTravelTime((l, t, p, v) -> { throw new AssertionError("No optimization outside its window"); })
                .build();
        var strategy = new CarrierVehicleReRouter(f.network, f.network, f.types,
                (l, t, p, v) -> 10, false, Map.of("car", costs), 2).createStrategy();
        strategy.init(() -> 25);
        strategy.finish();
        strategy.init(() -> iteration);
        strategy.run(f.carrier);
        strategy.finish();
        assertThat(f.carrier.getAttributes().getAttribute("hadFirstReplanning")).isNull();
        assertThat(f.carrier.getSelectedPlan().getScheduledTours()).isEmpty();
    }


    @Test
    void zoneMetricsCanReadNativeMatsimTourActivitiesWhenJobsAreAbsent() {
        var f = fixture("car");
        f.network.getLinks().get(Id.createLinkId("stop")).getAttributes().putAttribute("zone", 17);
        var costs = ZoneBasedTransportCosts.Builder.newInstance(f.network, List.of(f.type))
                .setTravelTime((l, t, p, v) -> 10).build();
        var strategy = new CarrierVehicleReRouter(f.network, f.network, f.types,
                (l, t, p, v) -> 10, false, Map.of("car", costs), 2).createStrategy();
        solve(strategy, f.carrier);
        f.carrier.getServices().clear();
        assertThat(CarrierRoutingZoneMetrics.count(f.carrier, f.carrier.getSelectedPlan(), f.network)).isEqualTo(1);
    }

    @Test
    void candidateOptimizationDoesNotPublishToursOrIterationsToTheScenarioCarrier() {
        var f = fixture("car");
        var mutablePlan = new CarrierPlan(new java.util.ArrayList<>());
        f.carrier.addPlan(mutablePlan);
        f.carrier.setSelectedPlan(mutablePlan);
        CarriersUtils.setJspritIterations(f.carrier, 9);
        var costs = ZoneBasedTransportCosts.Builder.newInstance(f.network, List.of(f.type))
                .setTravelTime((l, t, p, v) -> 10).build();
        var registry = new CarrierRoutingCosts(Map.of("car", costs));
        registry.prepare();
        var candidate = new LegacyCarrierPlanOptimizer(registry, 2, 25200, null).optimize(f.carrier);
        assertThat(candidate.plan().getScheduledTours()).hasSize(1);
        assertThat(candidate.iterations()).isEqualTo(2);
        assertThat(f.carrier.getSelectedPlan().getScheduledTours()).isEmpty();
        assertThat(CarriersUtils.getJspritIterations(f.carrier)).isEqualTo(9);
        assertThat(f.carrier.getAttributes().getAttribute("hadFirstReplanning")).isNull();
    }


    @Test
    void repeatedCarrierSelectionPublishesToTheLatestWorkingCopyOnly() {
        var f = fixture("car");
        var costs = ZoneBasedTransportCosts.Builder.newInstance(f.network, List.of(f.type))
                .setTravelTime((l, t, p, v) -> 10).build();
        var strategy = new CarrierVehicleReRouter(f.network, f.network, f.types,
                (l, t, p, v) -> 10, false, Map.of("car", costs), 2).createStrategy();
        strategy.init(() -> 25);
        strategy.run(f.carrier);
        var first = f.carrier.getSelectedPlan();
        strategy.run(f.carrier);
        var latest = f.carrier.getSelectedPlan();
        assertThat(latest).isNotSameAs(first);
        strategy.finish();
        assertServed(f);
        assertThat(first.getScheduledTours()).isEmpty();
    }

    @Test
    void failedGroupPublishesNeitherSuccessfulNorFailedCandidates() {
        var good = fixture("car", "good");
        var bad = fixture("cargobike", "bad");
        CarriersUtils.setJspritIterations(good.carrier, 9);
        CarriersUtils.setJspritIterations(bad.carrier, 12);
        var costs = ZoneBasedTransportCosts.Builder.newInstance(good.network, List.of(good.type))
                .setTravelTime((l, t, p, v) -> 10).build();
        var strategy = new CarrierVehicleReRouter(good.network, bad.network, good.types,
                (l, t, p, v) -> 10, false, Map.of("car", costs), 2).createStrategy();
        strategy.init(() -> 25);
        strategy.run(good.carrier);
        strategy.run(bad.carrier);
        assertThatThrownBy(strategy::finish).hasStackTraceContaining("No transport costs registered for mode cargobike");
        assertThat(good.carrier.getSelectedPlan().getScheduledTours()).isEmpty();
        assertThat(bad.carrier.getSelectedPlan().getScheduledTours()).isEmpty();
        assertThat(CarriersUtils.getJspritIterations(good.carrier)).isEqualTo(9);
        assertThat(CarriersUtils.getJspritIterations(bad.carrier)).isEqualTo(12);
        assertThat(good.carrier.getAttributes().getAttribute("hadFirstReplanning")).isNull();
        assertThat(bad.carrier.getAttributes().getAttribute("hadFirstReplanning")).isNull();
    }

    @Test
    void emptyLegacyBatchDoesNotBuildOrReadAnUnusedModalNetwork() {
        var f = fixture("car");
        var strategy = new CarrierVehicleReRouter(NetworkUtils.createNetwork(), NetworkUtils.createNetwork(),
                f.types, (l, t, p, v) -> { throw new AssertionError("No unused profile reads"); },
                false, Map.of(), 2).createStrategy();
        strategy.init(() -> 25);
        strategy.finish();
        strategy.init(() -> 26);
        strategy.run(f.carrier);
        assertThatThrownBy(strategy::finish).hasStackTraceContaining("No network links permit mode car");
    }


    @Test
    void unusedRegisteredBikeTypeDoesNotRequireItsNetworkInTheLegacyCarFallback() {
        var f = fixture("car");
        var unused = VehicleUtils.createVehicleType(Id.create("unused-bike-type", VehicleType.class), "cargobike");
        f.types.getVehicleTypes().put(unused.getId(), unused);
        var strategy = new CarrierVehicleReRouter(f.network, NetworkUtils.createNetwork(), f.types,
                (l, t, p, v) -> 10, false, Map.of(), 2).createStrategy();
        solve(strategy, f.carrier);
        assertServed(f);
    }

    @Test
    void unassignedJobsDoNotCountAsSuccessfulReplanning() {
        var f = fixture("car");
        var original = f.carrier.getServices().values().iterator().next();
        f.carrier.getServices().put(original.getId(), CarrierService.Builder.newInstance(original.getId(),
                original.getServiceLinkId()).setCapacityDemand(11).setServiceDuration(10)
                .setServiceStartingTimeWindow(TimeWindow.newInstance(0, 50000)).build());
        CarriersUtils.setJspritIterations(f.carrier, 9);
        var costs = ZoneBasedTransportCosts.Builder.newInstance(f.network, List.of(f.type))
                .setTravelTime((l, t, p, v) -> 10).build();
        var strategy = new CarrierVehicleReRouter(f.network, f.network, f.types,
                (l, t, p, v) -> 10, false, Map.of("car", costs), 2).createStrategy();
        strategy.init(() -> 25);
        strategy.run(f.carrier);
        assertThatThrownBy(strategy::finish).hasStackTraceContaining("Unassigned jobs").hasStackTraceContaining("service");
        assertThat(f.carrier.getAttributes().getAttribute("hadFirstReplanning")).isNull();
        assertThat(CarriersUtils.getJspritIterations(f.carrier)).isEqualTo(9);
        assertThat(f.carrier.getServices().values().iterator().next().getCapacityDemand()).isEqualTo(11);
    }

    private static void solve(org.matsim.core.replanning.GenericPlanStrategy<CarrierPlan, Carrier> strategy, Carrier carrier) {
        strategy.init(() -> 25);
        strategy.run(carrier);
        strategy.finish();
    }

    private static void assertServed(Fixture f) {
        assertThat(f.carrier.getSelectedPlan().getScheduledTours()).hasSize(1);
        assertThat(f.carrier.getSelectedPlan().getScheduledTours().iterator().next().getTour().getTourElements())
                .filteredOn(e -> e instanceof Tour.ServiceActivity).hasSize(1);
        assertThat(f.carrier.getAttributes().getAttribute("carrierMode")).isEqualTo(f.type.getNetworkMode());
    }

    private static Fixture fixture(String mode) {
        return fixture(mode, "carrier");
    }

    private static Fixture fixture(String mode, String carrierId) {
        var net = NetworkUtils.createNetwork();
        NetworkUtils.createAndAddNode(net, Id.createNodeId("a"), new Coord(0, 0));
        NetworkUtils.createAndAddNode(net, Id.createNodeId("b"), new Coord(100, 0));
        NetworkUtils.createAndAddNode(net, Id.createNodeId("c"), new Coord(200, 0));
        addLink(net, "depot", "a", "b", 100, Set.of(mode));
        addLink(net, "stop", "b", "c", 100, Set.of(mode));
        addLink(net, "back", "c", "a", 200, Set.of(mode));
        var type = VehicleUtils.createVehicleType(Id.create("type", VehicleType.class), mode);
        type.setMaximumVelocity(10);
        type.getCapacity().setOther(10);
        type.getCostInformation().setFixedCost(1.0).setCostsPerMeter(0.01).setCostsPerSecond(1.0);
        var types = new CarrierVehicleTypes();
        types.getVehicleTypes().put(type.getId(), type);
        var carrier = CarriersUtils.createCarrier(Id.create(carrierId, Carrier.class));
        CarriersUtils.setCarrierMode(carrier, mode);
        var vehicle = CarrierVehicle.Builder.newInstance(Id.createVehicleId("vehicle"), Id.createLinkId("depot"), type)
                .setEarliestStart(0).setLatestEnd(50000).build();
        carrier.setCarrierCapabilities(CarrierCapabilities.Builder.newInstance().addVehicle(vehicle)
                .setFleetSize(CarrierCapabilities.FleetSize.INFINITE).build());
        CarriersUtils.addService(carrier, CarrierService.Builder.newInstance(Id.create("service", CarrierService.class),
                Id.createLinkId("stop")).setCapacityDemand(1).setServiceDuration(10)
                .setServiceStartingTimeWindow(TimeWindow.newInstance(0, 50000)).build());
        var plan = new CarrierPlan(List.of());
        plan.setScore(0.0);
        carrier.addPlan(plan);
        carrier.setSelectedPlan(plan);
        return new Fixture(net, type, types, carrier);
    }

    private static void addLink(Network network, String id, String from, String to, double length, Set<String> modes) {
        var link = NetworkUtils.createAndAddLink(network, Id.createLinkId(id), network.getNodes().get(Id.createNodeId(from)),
                network.getNodes().get(Id.createNodeId(to)), length, 10, 1000, 1);
        link.setAllowedModes(modes);
    }

    private record Fixture(Network network, VehicleType type, CarrierVehicleTypes types, Carrier carrier) {}

}
