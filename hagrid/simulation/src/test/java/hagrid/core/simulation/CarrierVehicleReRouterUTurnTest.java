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
 * loop (the selected route under the configured tariff).
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

    // The shared optimizer now owns constraints; verify the actual selected route instead of
    // observing a removed local constraint-manager implementation.
    @ParameterizedTest
    @ValueSource(doubles = {2.5, 0.0})
    void solveLoopUsesPenalty(double penalty) {
        CarriersUtils.setCarrierMode(carrier,"car");
        var c=NetworkUtils.createAndAddNode(network,Id.createNodeId("C"),new Coord(0,-500));
        var d=NetworkUtils.createAndAddNode(network,Id.createNodeId("D"),new Coord(1000,-500));
        var a=network.getNodes().get(Id.createNodeId("A"));
        var b=network.getNodes().get(Id.createNodeId("B"));
        for(var link:List.of(
                NetworkUtils.createAndAddLink(network,Id.createLinkId("link_CD"),c,d,1000,10,1000,1),
                NetworkUtils.createAndAddLink(network,Id.createLinkId("link_DB"),d,b,500,10,1000,1),
                NetworkUtils.createAndAddLink(network,Id.createLinkId("link_AC"),a,c,500,10,1000,1)))
            link.setAllowedModes(Set.of("car"));
        var registry=types();
        var alternative=CarrierVehicle.Builder.newInstance(Id.createVehicleId("alternative"),
                Id.createLinkId("link_CD"),type).setEarliestStart(0).setLatestEnd(86400).build();
        CarriersUtils.addCarrierVehicle(carrier,alternative);
        var costs=hagrid.core.routing.NativeModalTransportCosts.create(network,registry,"car");
        var original=carrier.getCarrierCapabilities().getCarrierVehicles().get(Id.createVehicleId("v1"));
        // Independent replay proves both full candidate costs before testing solver selection.
        assertThat(candidateCost(original,costs,penalty)).isEqualTo(14+2*penalty);
        assertThat(candidateCost(alternative,costs,penalty)).isEqualTo(16);
        var r=new CarrierVehicleReRouter(network,network,registry,FREE_SPEED,false,Map.of(),2,penalty);
        var plan=new CarrierPlan(carrier,new ArrayList<>());
        var module=r.createModule();module.prepareReplanning(()->25);module.handlePlan(plan);module.finishReplanning();
        assertThat(plan.getScheduledTours()).hasSize(1);
        // Same fixed10 for both: original travel4 + two reversals; alternative travel6, no reversal.
        // Tariff2.5 must select16 over19; tariff0 must select14 over16.
        assertThat(plan.getScheduledTours().iterator().next().getVehicle().getLinkId().toString())
                .isEqualTo(penalty==0?"link_AB":"link_CD");
        var replay=hagrid.core.routing.PlanValidator.validate(carrier,plan,costs,null,25200,penalty);
        assertThat(replay.violations()).isEmpty();
        assertThat(replay.finalCost()).isEqualTo(penalty==0?14.0:16.0);
    }
    private double candidateCost(CarrierVehicle vehicle,VRPTransportCosts costs,double penalty) {
        var tour=org.matsim.freight.carriers.Tour.Builder.newInstance(Id.create("candidate",org.matsim.freight.carriers.Tour.class));
        tour.scheduleStart(vehicle.getLinkId());tour.addLeg(tour.createLeg());
        tour.scheduleService(carrier.getServices().values().iterator().next());
        tour.addLeg(tour.createLeg());tour.scheduleEnd(vehicle.getLinkId());
        var plan=new CarrierPlan(List.of(org.matsim.freight.carriers.ScheduledTour.newInstance(tour.build(),vehicle,0)));
        plan.setCarrier(carrier);hagrid.core.routing.CarrierRoadRouter.routePlan(plan,costs);
        var replay=hagrid.core.routing.PlanValidator.validate(carrier,plan,costs,null,25200,penalty);
        assertThat(replay.violations()).isEmpty();return replay.finalCost();
    }

}
