package hagrid.core.simulation;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.inject.Guice;
import com.google.inject.Key;
import com.google.inject.TypeLiteral;
import com.google.inject.spi.Element;
import com.google.inject.spi.Elements;
import com.google.inject.spi.ProviderInstanceBinding;
import com.graphhopper.jsprit.core.algorithm.state.StateManager;
import com.graphhopper.jsprit.core.problem.VehicleRoutingProblem;
import com.graphhopper.jsprit.core.problem.constraint.ConstraintManager;

import hagrid.core.routing.UTurnSoftConstraint;
import hagrid.core.routing.VRPTransportCostsFactory;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.NetworkFactory;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.replanning.modules.GenericPlanStrategyModule;
import org.matsim.core.router.speedy.SpeedyDijkstraFactory;
import org.matsim.core.router.util.LeastCostPathCalculatorFactory;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierCapabilities;
import org.matsim.freight.carriers.CarrierPlan;
import org.matsim.freight.carriers.CarrierService;
import org.matsim.freight.carriers.CarrierVehicle;
import org.matsim.freight.carriers.CarrierVehicleTypes;
import org.matsim.freight.carriers.CarriersUtils;
import org.matsim.freight.carriers.controller.CarrierStrategyManager;
import org.matsim.freight.carriers.jsprit.MatsimJspritFactory;
import org.matsim.freight.carriers.jsprit.VRPTransportCosts;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

/**
 * The in-loop re-router plans the tours that are actually driven, so it must see the same
 * {@code uTurnPenalty} as Step-A jsprit ({@code HAGRIDRouterUtils.configureAlgorithm}) and the scoring.
 *
 * <p>Final review I1: each of the three wiring hops is exercised through the production path, not
 * only through the method the hop calls - {@code HAGRIDSimulationModule.install()} (the provider it
 * binds), {@code ReplanningStrategies.get()} (the re-router it creates) and the re-router's solve
 * loop (the constraint manager it builds). Removing any hop turns a test red (mutation-probed).
 */
@DisplayName("CarrierVehicleReRouter - U-turn constraint wiring")
class CarrierVehicleReRouterUTurnTest {

    private Network network;
    private VehicleType type;
    private Carrier carrier;
    private VehicleRoutingProblem vrp;

    private static final TravelTime FREE_SPEED = (link, time, person, vehicle) -> link.getLength() / link.getFreespeed();

    @BeforeEach
    void setUp() {
        network = NetworkUtils.createNetwork();
        NetworkFactory fac = network.getFactory();
        Node a = fac.createNode(Id.createNodeId("A"), new Coord(0, 0));
        Node b = fac.createNode(Id.createNodeId("B"), new Coord(1000, 0));
        network.addNode(a);
        network.addNode(b);
        for (Link l : List.of(fac.createLink(Id.createLinkId("link_AB"), a, b),
                fac.createLink(Id.createLinkId("link_BA"), b, a))) {
            l.setLength(1000);
            l.setFreespeed(10);
            l.setCapacity(1000);
            l.setAllowedModes(Set.of("car"));
            network.addLink(l);
        }

        type = VehicleUtils.createVehicleType(Id.create("van", VehicleType.class));
        type.getCapacity().setOther(10);
        type.setNetworkMode("car");
        type.getCostInformation().setCostsPerMeter(0.001).setCostsPerSecond(0.01).setFixedCost(10.0);

        carrier = CarriersUtils.createCarrier(Id.create("lsp", Carrier.class));
        carrier.getCarrierCapabilities().setFleetSize(CarrierCapabilities.FleetSize.INFINITE);
        CarriersUtils.addCarrierVehicle(carrier, CarrierVehicle.Builder
                .newInstance(Id.createVehicleId("v1"), Id.create("link_AB", Link.class), type)
                .setEarliestStart(0).setLatestEnd(86400).build());
        CarriersUtils.addService(carrier, CarrierService.Builder
                .newInstance(Id.create("s1", CarrierService.class), Id.create("link_BA", Link.class))
                .setCapacityDemand(1).build());

        vrp = MatsimJspritFactory.createRoutingProblemBuilder(carrier, network).build();
    }

    private CarrierVehicleTypes types() {
        CarrierVehicleTypes types = new CarrierVehicleTypes();
        types.getVehicleTypes().put(type.getId(), type);
        return types;
    }

    private static List<UTurnSoftConstraint> uTurnConstraints(ConstraintManager cm) {
        return cm.getConstraints().stream().filter(c -> c instanceof UTurnSoftConstraint)
                .map(c -> (UTurnSoftConstraint) c).toList();
    }

    @Test
    @DisplayName("positive penalty registers the UTurnSoftConstraint")
    void reRouterRegistersUTurnConstraintWhenPenaltyPositive() {
        ConstraintManager cm = CarrierVehicleReRouter.buildConstraintManager(vrp, new StateManager(vrp), network, 1.0);
        assertThat(cm.getConstraints()).anyMatch(c -> c instanceof UTurnSoftConstraint);
    }

    @Test
    @DisplayName("zero penalty registers no UTurnSoftConstraint")
    void reRouterOmitsUTurnConstraintAtZero() {
        ConstraintManager cm = CarrierVehicleReRouter.buildConstraintManager(vrp, new StateManager(vrp), network, 0.0);
        assertThat(cm.getConstraints()).noneMatch(c -> c instanceof UTurnSoftConstraint);
    }

    @Test
    @DisplayName("module hands the penalty to ReplanningStrategies")
    void moduleHandsPenaltyToReplanningStrategies() {
        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        CarriersUtils.getOrAddCarrierVehicleTypes(scenario);
        HAGRIDSimulationModule m = new HAGRIDSimulationModule(scenario, true, 10, 1, false, 0.0, 2.5);
        assertThat(m.replanningStrategies().uTurnPenaltyCost()).isEqualTo(2.5);
    }

    // ------------------------------------------------------------------ hop (a): install()

    private static void setField(Class<?> owner, Object target, String name, Object value) throws Exception {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    /**
     * Records the bindings install() makes (Guice SPI, no injector) and reads back the provider
     * bound for CarrierStrategyManager: it must be a ReplanningStrategies carrying the module's
     * penalty. Both config fields are set reflectively because MATSim normally injects them from
     * its bootstrap injector before install().
     */
    @ParameterizedTest
    @ValueSource(doubles = {2.5, 0.0})
    @DisplayName("install() binds a ReplanningStrategies provider with the module's penalty")
    void installBindsProviderWithPenalty(double penalty) throws Exception {
        Config config = ConfigUtils.createConfig();
        Path out = Path.of("target", "uturn-module-wiring");
        Files.createDirectories(out);
        // CarrierScoreStats opens its writer in the constructor; target/ survives an open handle on Windows
        config.controller().setOutputDirectory(out.toString());
        Scenario scenario = ScenarioUtils.createScenario(config);
        CarriersUtils.getOrAddCarrierVehicleTypes(scenario);
        CarriersUtils.addOrGetCarriers(scenario);
        HAGRIDSimulationModule m = new HAGRIDSimulationModule(scenario, true, 10, 1, false, 0.0, penalty);
        setField(org.matsim.core.controler.AbstractModule.class, m, "config", config);
        setField(HAGRIDSimulationModule.class, m, "config", config);

        List<Element> elements = Elements.getElements(m);

        List<Object> providers = new ArrayList<>();
        for (Element e : elements) {
            if (e instanceof ProviderInstanceBinding<?> b && b.getKey().equals(Key.get(CarrierStrategyManager.class))) {
                providers.add(b.getUserSuppliedProvider());
            }
        }
        assertThat(providers).hasSize(1);
        assertThat(providers.get(0)).isInstanceOf(ReplanningStrategies.class);
        assertThat(((ReplanningStrategies) providers.get(0)).uTurnPenaltyCost()).isEqualTo(penalty);
    }

    // ------------------------------------------------------------------ hop (b): ReplanningStrategies.get()

    /** Captures the re-router get() creates through the createReRouter seam. */
    private static final class CapturingStrategies extends ReplanningStrategies {
        final List<CarrierVehicleReRouter> created = new ArrayList<>();

        CapturingStrategies(CarrierVehicleTypes types, double penalty) {
            super(types, false, 10, 1, penalty);
        }

        @Override
        CarrierVehicleReRouter createReRouter(Network carNetwork, Network bikeNetwork, TravelTime travelTime,
                Map<String, VRPTransportCosts> byModeVRPTransportCosts) {
            CarrierVehicleReRouter r = super.createReRouter(carNetwork, bikeNetwork, travelTime, byModeVRPTransportCosts);
            created.add(r);
            return r;
        }
    }

    @ParameterizedTest
    @ValueSource(doubles = {2.5, 0.0})
    @DisplayName("ReplanningStrategies.get() builds the re-router with its penalty")
    void getBuildsReRouterWithPenalty(double penalty) {
        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        CapturingStrategies rs = new CapturingStrategies(types(), penalty);
        VRPTransportCostsFactory costs = new VRPTransportCostsFactory() {
            @Override public VRPTransportCosts createVRPTransportCosts() { return null; }
            @Override public Map<String, VRPTransportCosts> createVRPTransportCostsWithModeCongestedTravelTime() {
                return Map.of();
            }
        };
        Guice.createInjector(new com.google.inject.AbstractModule() {
            @Override
            protected void configure() {
                bind(Network.class).toInstance(network);
                bind(Scenario.class).toInstance(scenario);
                bind(LeastCostPathCalculatorFactory.class).toInstance(new SpeedyDijkstraFactory());
                bind(new TypeLiteral<Map<String, TravelTime>>() { }).toInstance(Map.of("car", FREE_SPEED));
                bind(VRPTransportCostsFactory.class).toInstance(costs);
            }
        }).injectMembers(rs);

        assertThat(rs.get()).isNotNull();

        assertThat(rs.created).hasSize(1);
        assertThat(rs.created.get(0).uTurnPenaltyCost()).isEqualTo(penalty);
    }

    // ------------------------------------------------------------------ hop (c): the solve loop

    @ParameterizedTest
    @ValueSource(doubles = {2.5, 0.0})
    @DisplayName("constraintManagerFor uses the re-router's own penalty")
    void instanceSeamUsesOwnPenalty(double penalty) {
        CarrierVehicleReRouter r = new CarrierVehicleReRouter(network, network, types(), FREE_SPEED, false, Map.of(), 1,
                penalty);
        List<UTurnSoftConstraint> u = uTurnConstraints(r.constraintManagerFor(vrp, new StateManager(vrp), network));
        if (penalty > 0) {
            assertThat(u).singleElement().extracting(UTurnSoftConstraint::penaltyCost).isEqualTo(penalty);
        } else {
            assertThat(u).isEmpty();
        }
    }

    /**
     * Drives the real strategy module (prepare -> handlePlan -> finish, iteration inside the
     * optimisation window) on a one-service carrier, so jsprit actually solves; the observer sees
     * the constraint manager the solve loop built.
     */
    @ParameterizedTest
    @ValueSource(doubles = {2.5, 0.0})
    @DisplayName("solve loop builds its constraints through the seam with the penalty")
    void solveLoopUsesPenalty(double penalty) {
        CarrierVehicleReRouter r = new CarrierVehicleReRouter(network, network, types(), FREE_SPEED, false, Map.of(), 1,
                penalty);
        List<ConstraintManager> seen = new CopyOnWriteArrayList<>();
        r.constraintManagerObserver = seen::add;

        CarrierPlan plan = new CarrierPlan(carrier, new ArrayList<>());
        GenericPlanStrategyModule<CarrierPlan> module = r.createModule();
        module.prepareReplanning(() -> 25);
        module.handlePlan(plan);
        module.finishReplanning();

        assertThat(seen).hasSize(1);
        List<UTurnSoftConstraint> u = uTurnConstraints(seen.get(0));
        if (penalty > 0) {
            assertThat(u).singleElement().extracting(UTurnSoftConstraint::penaltyCost).isEqualTo(penalty);
        } else {
            assertThat(u).isEmpty();
        }
        assertThat(plan.getScheduledTours()).isNotEmpty();
    }
}
