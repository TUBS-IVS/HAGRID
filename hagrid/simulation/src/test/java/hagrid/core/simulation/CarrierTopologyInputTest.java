package hagrid.core.simulation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.network.NetworkUtils;
import org.matsim.freight.carriers.*;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

class CarrierTopologyInputTest {
    @Test
    void disconnectedServiceFailsWithCarrierModeAndStopWithoutChangingInput() {
        var f = fixture("cargobike");
        cycle(f.network, "home", "h", "cargobike");
        cycle(f.network, "island", "i", "cargobike");
        vehicle(f, "vehicle", "home");
        service(f, "parcel", "island");
        assertThatIllegalArgumentException().isThrownBy(() -> validate(f))
                .withMessageContaining("carrier").withMessageContaining("cargobike")
                .withMessageContaining("parcel").withMessageContaining("island").withMessageContaining("reachable");
        assertThat(f.carrier.getServices().values().iterator().next().getServiceLinkId()).isEqualTo(Id.createLinkId("island"));
        assertThat(f.network.getLinks()).hasSize(4);
        assertThat(f.carrier.getAttributes().getAsMap()).isEmpty();
    }

    @Test
    void reachableStopWithoutReturnPathFails() {
        var f = fixture("car");
        link(f.network, "home", "a", "b", "car");
        link(f.network, "out", "b", "c", "car");
        link(f.network, "stop", "c", "d", "car");
        vehicle(f, "vehicle", "home");
        service(f, "parcel", "stop");
        assertThatIllegalArgumentException().isThrownBy(() -> validate(f)).withMessageContaining("reachable");
    }

    @Test
    void returnPathWithoutOutwardPathFails() {
        var f = fixture("car");
        link(f.network, "home", "a", "b", "car");
        link(f.network, "stop", "c", "d", "car");
        link(f.network, "back", "d", "a", "car");
        vehicle(f, "vehicle", "home");
        service(f, "parcel", "stop");
        assertThatIllegalArgumentException().isThrownBy(() -> validate(f)).withMessageContaining("reachable");
    }

    @Test
    void carOnlyConnectorsCannotMakeBikeJobsReachable() {
        var f = fixture("cargobike");
        cycle(f.network, "home", "h", "cargobike");
        cycle(f.network, "stop", "s", "cargobike");
        link(f.network, "out", "h1", "s0", "car");
        link(f.network, "back", "s1", "h0", "car");
        vehicle(f, "vehicle", "home");
        service(f, "parcel", "stop");
        assertThatIllegalArgumentException().isThrownBy(() -> validate(f)).withMessageContaining("cargobike");
    }

    @Test
    void anyCompatibleDepotMayServeAService() {
        var f = fixture("car");
        cycle(f.network, "home", "h", "car");
        cycle(f.network, "other", "o", "car");
        link(f.network, "service", "o1", "o0", "car");
        vehicle(f, "first", "home");
        vehicle(f, "second", "other");
        service(f, "parcel", "service");
        assertThat(validate(f)).containsEntry(f.carrier.getId(), "car");
    }

    @Test
    void shipmentMustHavePickupAndDeliveryReachableFromTheSameDepot() {
        var f = fixture("car");
        cycle(f.network, "home", "h", "car");
        cycle(f.network, "other", "o", "car");
        vehicle(f, "first", "home");
        vehicle(f, "second", "other");
        var shipment = CarrierShipment.Builder.newInstance(Id.create("supply", CarrierShipment.class),
                Id.createLinkId("home"), Id.createLinkId("other"), 1).build();
        f.carrier.getShipments().put(shipment.getId(), shipment);
        assertThatIllegalArgumentException().isThrownBy(() -> validate(f))
                .withMessageContaining("shipment").withMessageContaining("supply").withMessageContaining("same depot");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void closedDirectedCycleAcceptsPureServiceOrShipment(boolean useShipment) {
        var f = fixture("cargobike");
        link(f.network, "home", "a", "b", "cargobike");
        link(f.network, "stop", "b", "c", "cargobike");
        link(f.network, "back", "c", "a", "cargobike");
        vehicle(f, "vehicle", "home");
        if (!useShipment) service(f, "parcel", "stop");
        var shipment = CarrierShipment.Builder.newInstance(Id.create("supply", CarrierShipment.class),
                Id.createLinkId("stop"), Id.createLinkId("back"), 1).build();
        if (useShipment) f.carrier.getShipments().put(shipment.getId(), shipment);
        assertThat(validate(f)).containsEntry(f.carrier.getId(), "cargobike");
        assertThat(f.network.getLinks()).hasSize(3);
    }

    @Test
    void sameDepotLinkNeedsNoInventedNetworkLoop() {
        var f = fixture("car");
        link(f.network, "home", "a", "b", "car");
        vehicle(f, "vehicle", "home");
        service(f, "parcel", "home");
        assertThat(validate(f)).containsEntry(f.carrier.getId(), "car");
    }

    @Test
    void unusedDisconnectedDepotWithNoJobsDoesNotRequireALoop() {
        var f = fixture("car");
        link(f.network, "home", "a", "b", "car");
        vehicle(f, "vehicle", "home");
        assertThat(validate(f)).containsEntry(f.carrier.getId(), "car");
    }

    @Test
    void longDirectedCycleDoesNotRequireRecursiveTraversal() {
        var f = fixture("car");
        for (int i = 0; i < 4000; i++) link(f.network, "l" + i, "n" + i, "n" + ((i + 1) % 4000), "car");
        vehicle(f, "vehicle", "l0");
        service(f, "parcel", "l2000");
        assertThat(validate(f)).containsEntry(f.carrier.getId(), "car");
    }

    private static java.util.Map<Id<Carrier>, String> validate(Fixture f) {
        return CarrierInputValidator.validate(new Carriers(List.of(f.carrier)), f.types, f.network);
    }

    private static Fixture fixture(String mode) {
        var type = VehicleUtils.createVehicleType(Id.create("type", VehicleType.class), mode);
        type.setMaximumVelocity(10);
        var types = new CarrierVehicleTypes();
        types.getVehicleTypes().put(type.getId(), type);
        return new Fixture(CarriersUtils.createCarrier(Id.create("carrier", Carrier.class)), types, type, NetworkUtils.createNetwork());
    }

    private static void vehicle(Fixture f, String id, String depot) {
        CarriersUtils.addCarrierVehicle(f.carrier, CarrierVehicle.newInstance(Id.createVehicleId(id), Id.createLinkId(depot), f.type));
    }

    private static void service(Fixture f, String id, String stop) {
        var service = CarrierService.Builder.newInstance(Id.create(id, CarrierService.class), Id.createLinkId(stop), 1).build();
        f.carrier.getServices().put(service.getId(), service);
    }

    private static void cycle(Network network, String link, String nodes, String mode) {
        link(network, link, nodes + "0", nodes + "1", mode);
        link(network, link + "-return", nodes + "1", nodes + "0", mode);
    }

    private static void link(Network network, String id, String from, String to, String mode) {
        for (String node : List.of(from, to)) {
            var key = Id.createNodeId(node);
            if (!network.getNodes().containsKey(key)) network.addNode(network.getFactory().createNode(key, new Coord(network.getNodes().size(), 0)));
        }
        var link = network.getFactory().createLink(Id.createLinkId(id),
                network.getNodes().get(Id.createNodeId(from)), network.getNodes().get(Id.createNodeId(to)));
        link.setAllowedModes(Set.of(mode));
        link.setLength(10);
        link.setFreespeed(10);
        link.setCapacity(1000);
        link.setNumberOfLanes(1);
        network.addLink(link);
    }

    private record Fixture(Carrier carrier, CarrierVehicleTypes types, VehicleType type, Network network) {}
}
