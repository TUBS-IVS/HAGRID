package hagrid.core.simulation;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.network.NetworkUtils;
import org.matsim.freight.carriers.*;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

class CarrierNetworkInputTest {
    @Test
    void derivesUniqueModeWithoutChangingImportedAttributes() {
        var f = fixture("cargobike", "shared");
        var modes = CarrierInputValidator.validate(f.carriers, f.types, f.network);
        assertThat(modes).containsEntry(f.carrier.getId(), "cargobike");
        assertThat(f.carrier.getAttributes().getAttribute("carrierMode")).isNull();
        assertThat(f.carrier.getAttributes().getAttribute("jspritIterations")).isNull();
        assertThatThrownBy(() -> modes.clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void explicitCarrierModeMustAgreeWithVehicleMode() {
        var f = fixture("cargobike", "shared");
        CarriersUtils.setCarrierMode(f.carrier, "car");
        assertThatIllegalArgumentException().isThrownBy(() -> validate(f))
                .withMessageContaining("carrier").withMessageContaining("cargobike").withMessageContaining("car");
        assertThat(CarriersUtils.getCarrierMode(f.carrier)).isEqualTo("car");
    }

    @Test
    void mixedVehicleModesAreRejected() {
        var f = fixture("car", "shared");
        var bike = type("bike-type", "cargobike");
        f.types.getVehicleTypes().put(bike.getId(), bike);
        CarriersUtils.addCarrierVehicle(f.carrier, CarrierVehicle.newInstance(Id.createVehicleId("bike"), Id.createLinkId("shared"), bike));
        assertThatIllegalArgumentException().isThrownBy(() -> validate(f)).withMessageContaining("mixed network modes");
    }

    @Test
    void depotMustExistAndAllowTheVehicleMode() {
        var missing = fixture("car", "missing");
        assertThatIllegalArgumentException().isThrownBy(() -> validate(missing))
                .withMessageContaining("depot").withMessageContaining("missing");
        var forbidden = fixture("cargobike", "car-only");
        assertThatIllegalArgumentException().isThrownBy(() -> validate(forbidden))
                .withMessageContaining("depot").withMessageContaining("cargobike").withMessageContaining("car-only");
    }

    @Test
    void serviceMustExistAndAllowTheCarrierModeWithoutRemapping() {
        var f = fixture("cargobike", "shared");
        var service = CarrierService.Builder.newInstance(Id.create("service", CarrierService.class),
                Id.createLinkId("car-only"), 1).build();
        f.carrier.getServices().put(service.getId(), service);
        assertThatIllegalArgumentException().isThrownBy(() -> validate(f))
                .withMessageContaining("service").withMessageContaining("car-only");
        assertThat(service.getServiceLinkId()).isEqualTo(Id.createLinkId("car-only"));
    }

    @Test
    void shipmentPickupAndDeliveryAreCheckedSeparately() {
        var pickup = fixture("cargobike", "shared");
        addShipment(pickup, "car-only", "shared");
        assertThatIllegalArgumentException().isThrownBy(() -> validate(pickup)).withMessageContaining("pickup");
        var delivery = fixture("cargobike", "shared");
        addShipment(delivery, "shared", "missing");
        assertThatIllegalArgumentException().isThrownBy(() -> validate(delivery))
                .withMessageContaining("delivery").withMessageContaining("missing");
    }

    @Test
    void unknownTypeAndConflictingTypeDefinitionAreRejected() {
        var missing = fixture("car", "shared");
        missing.types.getVehicleTypes().clear();
        assertThatIllegalArgumentException().isThrownBy(() -> validate(missing)).withMessageContaining("Unknown vehicle type");
        var conflicting = fixture("car", "shared");
        conflicting.types.getVehicleTypes().put(conflicting.type.getId(), type("type", "cargobike"));
        assertThatIllegalArgumentException().isThrownBy(() -> validate(conflicting)).withMessageContaining("Conflicting vehicle type");
    }

    @Test
    void invalidTypeRegistryKeyIsDiagnosed() {
        var f = fixture("car", "shared");
        f.types.getVehicleTypes().clear();
        f.types.getVehicleTypes().put(Id.create("wrong", VehicleType.class), f.type);
        assertThatIllegalArgumentException().isThrownBy(() -> validate(f)).withMessageContaining("registry key");
    }

    @Test
    void vehicleModeMustBeExplicitAndSpeedMustBePositive() {
        var speed = fixture("car", "shared");
        speed.type.setMaximumVelocity(0);
        assertThatIllegalArgumentException().isThrownBy(() -> validate(speed)).withMessageContaining("maximum velocity");
        var mode = fixture("car", "shared");
        mode.type.setNetworkMode("");
        assertThatIllegalArgumentException().isThrownBy(() -> validate(mode)).withMessageContaining("network mode");
    }

    @Test
    void jobsWithoutVehiclesFailBeforeSolverConstruction() {
        var carrier = CarriersUtils.createCarrier(Id.create("empty-fleet", Carrier.class));
        var service = CarrierService.Builder.newInstance(Id.create("service", CarrierService.class), Id.createLinkId("shared"), 1).build();
        carrier.getServices().put(service.getId(), service);
        assertThatIllegalArgumentException().isThrownBy(() ->
                CarrierInputValidator.validate(new Carriers(List.of(carrier)), new CarrierVehicleTypes(), network()))
                .withMessageContaining("no vehicles");
    }

    @Test
    void emptyCarrierDoesNotAcquireInventedModeOrBudgets() {
        var carrier = CarriersUtils.createCarrier(Id.create("empty", Carrier.class));
        var modes = CarrierInputValidator.validate(new Carriers(List.of(carrier)), new CarrierVehicleTypes(), network());
        assertThat(modes).isEmpty();
        assertThat(carrier.getAttributes().getAsMap()).isEmpty();
    }

    @Test
    void wrongServiceMapKeyIsRejected() {
        var f = fixture("car", "shared");
        var service = CarrierService.Builder.newInstance(Id.create("actual", CarrierService.class), Id.createLinkId("shared"), 1).build();
        f.carrier.getServices().put(Id.create("wrong", CarrierService.class), service);
        assertThatIllegalArgumentException().isThrownBy(() -> validate(f)).withMessageContaining("service map key");
    }

    private static void validate(Fixture f) { CarrierInputValidator.validate(f.carriers, f.types, f.network); }
    private static void addShipment(Fixture f, String pickup, String delivery) {
        var shipment = CarrierShipment.Builder.newInstance(Id.create("shipment", CarrierShipment.class),
                Id.createLinkId(pickup), Id.createLinkId(delivery), 1).build();
        f.carrier.getShipments().put(shipment.getId(), shipment);
    }
    private static VehicleType type(String id, String mode) {
        var type = VehicleUtils.createVehicleType(Id.create(id, VehicleType.class), mode);
        type.setMaximumVelocity(10);
        type.getCapacity().setOther(20);
        type.getCostInformation().setFixedCost(1.0).setCostsPerMeter(1.0).setCostsPerSecond(1.0);
        return type;
    }
    private static Fixture fixture(String mode, String depot) {
        var type = type("type", mode);
        var types = new CarrierVehicleTypes();
        types.getVehicleTypes().put(type.getId(), type);
        var carrier = CarriersUtils.createCarrier(Id.create("carrier", Carrier.class));
        CarriersUtils.addCarrierVehicle(carrier, CarrierVehicle.newInstance(Id.createVehicleId("vehicle"), Id.createLinkId(depot), type));
        return new Fixture(carrier, new Carriers(List.of(carrier)), types, type, network());
    }
    private static Network network() {
        var network = NetworkUtils.createNetwork();
        var a = network.getFactory().createNode(Id.createNodeId("a"), new Coord(0, 0));
        var b = network.getFactory().createNode(Id.createNodeId("b"), new Coord(100, 0));
        network.addNode(a); network.addNode(b);
        for (var entry : Map.of("shared", Set.of("car", "cargobike"), "car-only", Set.of("car")).entrySet()) {
            var link = network.getFactory().createLink(Id.createLinkId(entry.getKey()), a, b);
            link.setLength(100); link.setFreespeed(10); link.setCapacity(1000); link.setNumberOfLanes(1);
            link.setAllowedModes(entry.getValue()); network.addLink(link);
        }
        return network;
    }
    private record Fixture(Carrier carrier, Carriers carriers, CarrierVehicleTypes types, VehicleType type, Network network) {}
}

