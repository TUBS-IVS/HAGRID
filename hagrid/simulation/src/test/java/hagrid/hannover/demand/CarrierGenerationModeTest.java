package hagrid.hannover.demand;

import hagrid.core.HagridConfig;
import hagrid.core.util.Hub;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.freight.carriers.*;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class CarrierGenerationModeTest {
    @ParameterizedTest
    @ValueSource(strings = {"bike", "cargobike", "car"})
    void actualDeliveryVehicleAssignmentUsesTheFleetMode(String mode) throws Exception {
        var config = new HagridConfig();
        config.vehicles().setActiveSizes(List.of(mode.equals("car") ? "m" : "bike"));
        config.vehicles().setDefaultDispatchHours(List.of(7));
        var generator = new CarrierGenerator();
        set(generator, "hagridConfig", config);
        set(generator, "carrierVehicleFactory", factory(mode));
        set(generator, "scenario", scenario(mode));
        var carrier = delivery(generator);
        var hub = hub();
        invoke(generator, "addCarrierVehiclesToCarrier", new Class<?>[]{Carrier.class, Map.class}, carrier, Map.of(hub.getId(), hub));
        assertThat(CarriersUtils.getCarrierMode(carrier)).isEqualTo(mode);
        assertThat(carrier.getCarrierCapabilities().getCarrierVehicles()).hasSize(1);
        assertThat(carrier.getCarrierCapabilities().getCarrierVehicles().values())
                .allSatisfy(v -> assertThat(v.getType().getNetworkMode()).isEqualTo(mode));
    }

    @Test
    void mixedGeneratedFleetIsRejectedInsteadOfPretendingToBeCar() throws Exception {
        var config = new HagridConfig();
        config.vehicles().setActiveSizes(List.of("m", "bike"));
        config.vehicles().setDefaultDispatchHours(List.of(7));
        var generator = new CarrierGenerator();
        set(generator, "hagridConfig", config);
        set(generator, "carrierVehicleFactory", factory("cargobike"));
        set(generator, "scenario", scenario("cargobike"));
        var carrier = delivery(generator);
        var hub = hub();
        assertThatIllegalArgumentException().isThrownBy(() ->
                invoke(generator, "addCarrierVehiclesToCarrier", new Class<?>[]{Carrier.class, Map.class}, carrier, Map.of(hub.getId(), hub)))
                .withMessageContaining("mixed");
        assertThat(carrier.getAttributes().getAttribute("carrierMode")).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"car", "truck"})
    void actualSupplyCreationUsesTheDeclaredTruckMode(String mode) throws Exception {
        var generator = new SupplyCarrierGenerator();
        set(generator, "hagridConfig", new hagrid.core.HagridConfig());
        var types = new CarrierVehicleTypes();
        var type = VehicleUtils.createVehicleType(Id.create("ct_truck_heavy", VehicleType.class), mode);
        type.setMaximumVelocity(25);
        types.getVehicleTypes().put(type.getId(), type);
        set(generator, "carrierVehicleFactory", new CarrierVehicleFactory(types));
        var carrier = (Carrier) invoke(generator, "createSupplyCarrier",
                new Class<?>[]{Hub.class, org.matsim.api.core.v01.Id.class, String.class, boolean.class},
                hub(), Id.createLinkId("home"), "supply_fixture", false);
        assertThat(CarriersUtils.getCarrierMode(carrier)).isEqualTo(mode);
        assertThat(carrier.getCarrierCapabilities().getCarrierVehicles()).hasSize(2);
    }

    private static org.matsim.api.core.v01.Scenario scenario(String mode) {
        var scenario = org.matsim.core.scenario.ScenarioUtils.createScenario(org.matsim.core.config.ConfigUtils.createConfig());
        var n = scenario.getNetwork();
        var a = org.matsim.core.network.NetworkUtils.createAndAddNode(n, Id.createNodeId("a"), new Coord(0, 0));
        var b = org.matsim.core.network.NetworkUtils.createAndAddNode(n, Id.createNodeId("b"), new Coord(1, 1));
        var c = org.matsim.core.network.NetworkUtils.createAndAddNode(n, Id.createNodeId("c"), new Coord(2, 0));
        for (var link : List.of(
                org.matsim.core.network.NetworkUtils.createAndAddLink(n, Id.createLinkId("home"), a, b, 1, 10, 1000, 1),
                org.matsim.core.network.NetworkUtils.createAndAddLink(n, Id.createLinkId("stop"), b, c, 1, 10, 1000, 1),
                org.matsim.core.network.NetworkUtils.createAndAddLink(n, Id.createLinkId("return"), c, a, 1, 10, 1000, 1))) {
            link.setAllowedModes(java.util.Set.of(mode));
        }
        scenario.addScenarioElement("parcelServiceNetwork", n);
        return scenario;
    }

    private static Carrier delivery(CarrierGenerator generator) throws Exception {
        var carrier = CarriersUtils.createCarrier(Id.create("dhl_12345_fixture", Carrier.class));
        invoke(generator, "setupCarrierAttributes", new Class<?>[]{Carrier.class, String.class}, carrier, carrier.getId().toString());
        var service = CarrierService.Builder.newInstance(Id.create("stop", CarrierService.class), Id.createLinkId("stop"), 1).build();
        service.getAttributes().putAttribute("coord", new Coord(1, 1));
        CarriersUtils.addService(carrier, service);
        return carrier;
    }

    private static Hub hub() {
        var hub = new Hub(Id.create("depot", Hub.class), "dhl", new Coord(0, 0));
        hub.setLink(Id.createLinkId("home"));
        return hub;
    }

    private static CarrierVehicleFactory factory(String mode) {
        var types = new CarrierVehicleTypes();
        for (var entry : Map.of("ct_cep_bike", mode, "ct_cep_size_m", "car").entrySet()) {
            var type = VehicleUtils.createVehicleType(Id.create(entry.getKey(), VehicleType.class), entry.getValue());
            type.setMaximumVelocity(25);
            type.getCapacity().setOther(23);
            types.getVehicleTypes().put(type.getId(), type);
        }
        return new CarrierVehicleFactory(types);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object invoke(Object target, String name, Class<?>[] signature, Object... args) throws Exception {
        var method = target.getClass().getDeclaredMethod(name, signature);
        method.setAccessible(true);
        try { return method.invoke(target, args); }
        catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) throw cause;
            throw e;
        }
    }
}
