package hagrid.core.routing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.*;
import org.matsim.api.core.v01.network.*;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.freight.carriers.*;
import org.matsim.vehicles.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ExecutionException;
import static org.assertj.core.api.Assertions.*;

/** Real native helper calls must leave no idle solver pool behind, including failure paths. */
class NativeFreightExecutorLifecycleTest {
    @TempDir Path root;

    private Scenario scenario(int iterations) {
        var config = ConfigUtils.createConfig();
        config.controller().setOutputDirectory(root.resolve("native-" + iterations).toString());
        var scenario = ScenarioUtils.createScenario(config);
        var net = scenario.getNetwork();
        var a = net.getFactory().createNode(Id.createNodeId("a"), new Coord(0, 0));
        var b = net.getFactory().createNode(Id.createNodeId("b"), new Coord(100, 0));
        net.addNode(a); net.addNode(b);
        for (var direction : List.of(new Node[]{a, b}, new Node[]{b, a})) {
            var link = net.getFactory().createLink(Id.createLinkId(direction[0].getId().toString() + direction[1].getId()), direction[0], direction[1]);
            link.setLength(100); link.setFreespeed(10); link.setCapacity(1000); link.setNumberOfLanes(1);
            link.setAllowedModes(Set.of(TransportMode.car)); net.addLink(link);
        }
        var type = VehicleUtils.createVehicleType(Id.create("van", VehicleType.class));
        type.getCapacity().setOther(1.0); type.getCostInformation().setCostsPerMeter(1.0).setCostsPerSecond(0.0).setFixedCost(0.0);
        type.setMaximumVelocity(10);
        var types = new CarrierVehicleTypes(); types.getVehicleTypes().put(type.getId(), type);
        var carrier = CarriersUtils.createCarrier(Id.create("carrier", Carrier.class));
        carrier.setCarrierCapabilities(CarrierCapabilities.Builder.newInstance()
                .setFleetSize(CarrierCapabilities.FleetSize.FINITE)
                .addVehicle(CarrierVehicle.Builder.newInstance(Id.createVehicleId("van"), Id.createLinkId("ab"), type)
                        .setEarliestStart(0).setLatestEnd(1000).build()).build());
        CarriersUtils.addService(carrier, CarrierService.Builder.newInstance(Id.create("job", CarrierService.class), Id.createLinkId("ba"), 1).build());
        CarriersUtils.setJspritIterations(carrier, iterations);
        var carriers = new Carriers(); carriers.addCarrier(carrier);
        scenario.addScenarioElement("carriers", carriers);
        scenario.addScenarioElement("carrierVehicleTypes", types);
        return scenario;
    }

    private Set<Thread> threads() { return Set.copyOf(Thread.getAllStackTraces().keySet()); }

    private void assertNoNewWorkers(Set<Thread> before) {
        assertThat(Thread.getAllStackTraces().entrySet().stream()
                .filter(entry -> !before.contains(entry.getKey()) && entry.getKey().isAlive() && !entry.getKey().isDaemon())
                .filter(entry -> entry.getKey().getName().matches("pool-[0-9]+-thread-[0-9]+"))
                .filter(entry -> Arrays.stream(entry.getValue()).anyMatch(frame -> frame.getClassName().equals("java.util.concurrent.ThreadPoolExecutor")))
                .map(entry -> entry.getKey().getName()).toList())
                .as("native solver workers must terminate before returning").isEmpty();
    }

    @Test void repeatedSuccessfulCallsFinishAllNativeWorkers() throws Exception {
        var scenario = scenario(1);
        for (int repeat = 0; repeat < 2; repeat++) {
            var before = threads();
            CarriersUtils.runJsprit(scenario);
            assertThat(CarriersUtils.getCarriers(scenario).getCarriers().values().iterator().next().getSelectedPlan().getScheduledTours()).hasSize(1);
            assertNoNewWorkers(before);
        }
    }

    @Test void failedNativeCallStillFinishesItsWorkers() {
        var scenario = scenario(0);
        var before = threads();
        assertThatThrownBy(() -> CarriersUtils.runJsprit(scenario)).isInstanceOf(ExecutionException.class);
        assertNoNewWorkers(before);
    }
}
