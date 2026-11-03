package hagrid.core.simulation;

import static org.assertj.core.api.Assertions.assertThat;

import com.graphhopper.jsprit.core.algorithm.state.StateManager;
import com.graphhopper.jsprit.core.problem.VehicleRoutingProblem;
import com.graphhopper.jsprit.core.problem.constraint.ConstraintManager;

import hagrid.core.routing.UTurnSoftConstraint;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.NetworkFactory;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierCapabilities;
import org.matsim.freight.carriers.CarrierService;
import org.matsim.freight.carriers.CarrierVehicle;
import org.matsim.freight.carriers.CarriersUtils;
import org.matsim.freight.carriers.jsprit.MatsimJspritFactory;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

/**
 * The in-loop re-router plans the tours that are actually driven, so it must see the same
 * {@code uTurnPenalty} as Step-A jsprit ({@code HAGRIDRouterUtils.configureAlgorithm}) and the scoring.
 */
@DisplayName("CarrierVehicleReRouter - U-turn constraint wiring")
class CarrierVehicleReRouterUTurnTest {

    private Network network;
    private VehicleRoutingProblem vrp;

    @BeforeEach
    void setUp() {
        network = NetworkUtils.createNetwork();
        NetworkFactory fac = network.getFactory();
        Node a = fac.createNode(Id.createNodeId("A"), new Coord(0, 0));
        Node b = fac.createNode(Id.createNodeId("B"), new Coord(1000, 0));
        network.addNode(a);
        network.addNode(b);
        network.addLink(fac.createLink(Id.createLinkId("link_AB"), a, b));
        network.addLink(fac.createLink(Id.createLinkId("link_BA"), b, a));

        VehicleType type = VehicleUtils.createVehicleType(Id.create("van", VehicleType.class));
        type.getCapacity().setOther(10);
        type.setNetworkMode("car");
        type.getCostInformation().setCostsPerMeter(0.001).setCostsPerSecond(0.01).setFixedCost(10.0);

        Carrier carrier = CarriersUtils.createCarrier(Id.create("lsp", Carrier.class));
        carrier.getCarrierCapabilities().setFleetSize(CarrierCapabilities.FleetSize.INFINITE);
        CarriersUtils.addCarrierVehicle(carrier, CarrierVehicle.Builder
                .newInstance(Id.createVehicleId("v1"), Id.create("link_AB", Link.class), type)
                .setEarliestStart(0).setLatestEnd(86400).build());
        CarriersUtils.addService(carrier, CarrierService.Builder
                .newInstance(Id.create("s1", CarrierService.class), Id.create("link_BA", Link.class))
                .setCapacityDemand(1).build());

        vrp = MatsimJspritFactory.createRoutingProblemBuilder(carrier, network).build();
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
}
