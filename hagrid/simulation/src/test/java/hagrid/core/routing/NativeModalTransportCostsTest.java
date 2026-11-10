package hagrid.core.routing;

import com.graphhopper.jsprit.core.problem.Location;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleImpl;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleTypeImpl;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.core.config.groups.NetworkConfigGroup;
import org.matsim.core.network.NetworkChangeEvent;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.TimeDependentNetwork;
import org.matsim.freight.carriers.CarrierVehicleTypes;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class NativeModalTransportCostsTest {
    @Test
    void excludedModeEventsDoNotBecomeEmptyEvents() {
        var f = fixture();
        var event = new NetworkChangeEvent(100);
        event.setFreespeedChange(new NetworkChangeEvent.ChangeValue(NetworkChangeEvent.ChangeType.ABSOLUTE_IN_SI_UNITS, 5));
        event.addLink(f.network.getLinks().get(Id.createLinkId("car-only")));
        NetworkUtils.setNetworkChangeEvents(f.network, List.of(event));
        var costs = NativeModalTransportCosts.create(f.network, f.types, "cargobike");
        assertThat(((TimeDependentNetwork) costs.getNetwork()).getNetworkChangeEvents()).isEmpty();
        assertThat(((TimeDependentNetwork) f.network).getNetworkChangeEvents()).containsExactly(event);
    }

    @Test
    void nativeWrappersRestoreRegisteredModeAndSpeedWhileNeutralQueriesUseTheBoundMode() {
        var f = fixture();
        var costs = NativeModalTransportCosts.create(f.network, f.types, "cargobike", (l, t, p, v) -> {
            assertThat(v.getType().getNetworkMode()).isEqualTo("cargobike");
            if (v.getType().getId().equals(f.type.getId())) assertThat(v.getType()).isSameAs(f.type);
            return l.getLength() / Math.min(v.getType().getMaximumVelocity(), l.getFreespeed(t));
        });
        var vehicle = VehicleImpl.Builder.newInstance("bike")
                .setType(VehicleTypeImpl.Builder.newInstance("bike-type").setMaxVelocity(5).build())
                .setStartLocation(Location.newInstance("home")).build();
        assertThat(costs.getTransportTime(Location.newInstance("home"), Location.newInstance("stop"), 0, null, vehicle)).isEqualTo(20);
        assertThat(costs.getTransportTime(Location.newInstance("home"), Location.newInstance("stop"), 0, null, null)).isEqualTo(10);
    }

    @Test
    void retainedEventUsesTheCopiedLinkAndPreservesItsTimedSpeed() {
        var f = fixture();
        var sourceLink = f.network.getLinks().get(Id.createLinkId("stop"));
        var event = new NetworkChangeEvent(100);
        event.setFreespeedChange(new NetworkChangeEvent.ChangeValue(NetworkChangeEvent.ChangeType.ABSOLUTE_IN_SI_UNITS, 2));
        event.addLink(sourceLink);
        NetworkUtils.setNetworkChangeEvents(f.network, List.of(event));
        var costs = NativeModalTransportCosts.create(f.network, f.types, "cargobike");
        var copied = costs.getNetwork().getLinks().get(sourceLink.getId());
        var events = ((TimeDependentNetwork) costs.getNetwork()).getNetworkChangeEvents();
        assertThat(events).hasSize(1);
        assertThat(events.iterator().next()).isNotSameAs(event);
        assertThat(events.iterator().next().getLinks()).containsExactly(copied);
        assertThat(copied).isNotSameAs(sourceLink);
        assertThat(costs.getTravelTime().getLinkTravelTime(copied, 50, null, null)).isEqualTo(10);
        assertThat(costs.getTravelTime().getLinkTravelTime(copied, 150, null, null)).isEqualTo(50);
    }

    @Test
    void filteringPreservesNetworkUnitsAndGeometrySettings() {
        var f = fixture();
        f.network.setCapacityPeriod(1800);
        f.network.setEffectiveCellSize(5);
        f.network.setEffectiveLaneWidth(2);
        var costs = NativeModalTransportCosts.create(f.network, f.types, "cargobike");
        assertThat(costs.getNetwork().getCapacityPeriod()).isEqualTo(1800);
        assertThat(costs.getNetwork().getEffectiveCellSize()).isEqualTo(5);
        assertThat(costs.getNetwork().getEffectiveLaneWidth()).isEqualTo(2);
    }

    @Test
    void wrongDeclaredModeFailsBeforeCallingTheProfile() {
        var f = fixture();
        var costs = NativeModalTransportCosts.create(f.network, f.types, "cargobike",
                (l, t, p, v) -> { throw new AssertionError("Mode must be checked before profile"); });
        var wrong = VehicleUtils.createVehicle(Id.createVehicleId("wrong"),
                VehicleUtils.createVehicleType(Id.create("car-type", VehicleType.class), "car"));
        assertThatIllegalArgumentException().isThrownBy(() ->
                costs.getTravelTime().getLinkTravelTime(costs.getNetwork().getLinks().get(Id.createLinkId("stop")), 0, null, wrong))
                .withMessageContaining("disagrees").withMessageContaining("cargobike");
    }

    private static Fixture fixture() {
        var cfg = new NetworkConfigGroup();
        cfg.setTimeVariantNetwork(true);
        var network = NetworkUtils.createNetwork(cfg);
        for (String node : List.of("a", "b", "c")) NetworkUtils.createAndAddNode(network, Id.createNodeId(node), new Coord(node.charAt(0) * 100, 0));
        for (String[] link : List.of(new String[]{"home", "a", "b"}, new String[]{"stop", "b", "c"}, new String[]{"back", "c", "a"}, new String[]{"car-only", "a", "c"})) {
            var edge = NetworkUtils.createAndAddLink(network, Id.createLinkId(link[0]), network.getNodes().get(Id.createNodeId(link[1])),
                    network.getNodes().get(Id.createNodeId(link[2])), 100, 10, 1000, 1);
            edge.setAllowedModes(Set.of(link[0].equals("car-only") ? "car" : "cargobike"));
        }
        var type = VehicleUtils.createVehicleType(Id.create("bike-type", VehicleType.class), "cargobike");
        type.setMaximumVelocity(5);
        type.getCostInformation().setFixedCost(1.0).setCostsPerMeter(0.01).setCostsPerSecond(1.0);
        var types = new CarrierVehicleTypes();
        types.getVehicleTypes().put(type.getId(), type);
        return new Fixture(network, types, type);
    }
    private record Fixture(org.matsim.api.core.v01.network.Network network, CarrierVehicleTypes types, VehicleType type) {}
}
