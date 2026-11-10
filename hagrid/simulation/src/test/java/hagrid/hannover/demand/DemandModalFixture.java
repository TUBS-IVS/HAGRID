package hagrid.hannover.demand;
import hagrid.core.*;
import hagrid.core.util.*;
import org.matsim.api.core.v01.*;
import org.matsim.api.core.v01.network.*;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.freight.carriers.*;
import org.matsim.vehicles.*;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.*;

final class DemandModalFixture {
    final Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
    final HagridConfig config = new HagridConfig();
    final CarrierVehicleTypes types = new CarrierVehicleTypes();
    final Map<Id<Hub>, Hub> hubs = new LinkedHashMap<>();
    final Carriers delivery = new Carriers();

    DemandModalFixture(Path directory, String supplyMode) throws Exception {
        cycle(scenario.getNetwork(), "car-", "car", 0);
        if (!supplyMode.equals("car")) cycle(scenario.getNetwork(), supplyMode + "-", supplyMode, 10);
        var carNetwork = NetworkUtils.createNetwork();
        cycle(carNetwork, "car-", "car", 0);
        scenario.addScenarioElement("carFilteredNetwork", carNetwork);
        scenario.addScenarioElement("parcelServiceNetwork", carNetwork);
        for (var entry : Map.of("ct_cep_size_m", "car", "ct_truck_heavy", supplyMode).entrySet()) {
            var type = VehicleUtils.createVehicleType(Id.create(entry.getKey(), VehicleType.class), entry.getValue());
            type.setMaximumVelocity(25); type.getCapacity().setOther(10);
            types.getVehicleTypes().put(type.getId(), type);
        }
        scenario.addScenarioElement("carrierVehicleTypes", types);
        set(config, "hagridPaths", new HagridPaths(directory));
        config.setSimulationDate(java.time.LocalDate.of(2025, 5, 13));
        config.supply().setVehicleCapacity(10);
        for (String direction : List.of("north", "south", "east", "west")) {
            config.supply().setLinkDirection(direction, (supplyMode.equals("car") ? "car-" : supplyMode + "-") + "depot");
        }
        var hub = hub("hermes-hub", "hermes", "car-stop", new Coord(75, 10));
        hub.setAssignedSupplyDemand(4);
        hubs.put(hub.getId(), hub);
        var central = hub("dhl_hannover_anderten", "dhl", "car-home", new Coord(25, 10));
        hubs.put(central.getId(), central);
        var carrier = carrier("hermes_12345_0", "car", "car-home");
        carrier.getAttributes().putAttribute("hub", hub);
        carrier.getAttributes().putAttribute("hubId", hub.getId().toString());
        CarriersUtils.addService(carrier, service("delivery", "car-stop", 4, new Coord(75, 0), 0, 86400));
        delivery.addCarrier(carrier);
        scenario.addScenarioElement("carriers", delivery);
        scenario.addScenarioElement("hubList", hubs);
        java.nio.file.Files.createDirectories(Path.of(config.io().supplyCarriersUnrouted()).getParent());
    }

    Carrier carrier(String name, String mode, String depot) {
        var c = CarriersUtils.createCarrier(Id.create(name, Carrier.class));
        var type = types.getVehicleTypes().values().stream().filter(t -> t.getNetworkMode().equals(mode)).findFirst().orElseThrow();
        CarriersUtils.addCarrierVehicle(c, CarrierVehicle.Builder.newInstance(Id.create(name + "-v", Vehicle.class), Id.createLinkId(depot), type).build());
        CarriersUtils.setCarrierMode(c, mode);
        return c;
    }

    SupplyCarrierGenerator supplyGenerator() throws Exception {
        var generator = new SupplyCarrierGenerator();
        set(generator, "scenario", scenario); set(generator, "hagridConfig", config);
        return generator;
    }

    static CarrierService service(String id, String link, int amount, Coord coord, double start, double end) {
        var s = CarrierService.Builder.newInstance(Id.create(id, CarrierService.class), Id.createLinkId(link), amount)
                .setServiceDuration(30).setServiceStartingTimeWindow(TimeWindow.newInstance(start, end)).build();
        s.getAttributes().putAttribute("coord", coord);
        s.getAttributes().putAttribute("type", Delivery.ParcelType.B2C);
        s.getAttributes().putAttribute("provider", "hermes");
        s.getAttributes().putAttribute("carrierMode", "HOME");
        s.getAttributes().putAttribute("weights", String.join(";", Collections.nCopies(amount, "1.0")));
        CarriersUtils.addSkill(s, "conventional");
        return s;
    }

    static void cycle(Network n, String prefix, String mode, double y) {
        var a = NetworkUtils.createAndAddNode(n, Id.createNodeId(prefix + "a"), new Coord(0, y));
        var b = NetworkUtils.createAndAddNode(n, Id.createNodeId(prefix + "b"), new Coord(50, y));
        var c = NetworkUtils.createAndAddNode(n, Id.createNodeId(prefix + "c"), new Coord(100, y));
        var d = NetworkUtils.createAndAddNode(n, Id.createNodeId(prefix + "d"), new Coord(0, y + 20));
        var nodes = List.of(a, b, c, d);
        var ids = List.of("home", "stop", "depot", "return");
        for (int i=0; i<4; i++) {
            var link = NetworkUtils.createAndAddLink(n, Id.createLinkId(prefix + ids.get(i)), nodes.get(i), nodes.get((i+1)%4), 100, 10, 1000, 1);
            link.setAllowedModes(Set.of(mode));
        }
    }

    static Hub hub(String id, String provider, String link, Coord coord) {
        var h = new Hub(Id.create(id, Hub.class), provider, coord); h.setLink(Id.createLinkId(link)); return h;
    }

    static void set(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
    static Object invoke(Object target, String name, Class<?>[] sig, Object... args) throws Exception {
        var m=target.getClass().getDeclaredMethod(name,sig);m.setAccessible(true);
        try { return m.invoke(target,args); } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) throw cause; throw e;
        }
    }
}
