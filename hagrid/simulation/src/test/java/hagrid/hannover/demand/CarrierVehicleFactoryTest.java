package hagrid.hannover.demand;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.matsim.api.core.v01.Id;
import org.matsim.freight.carriers.CarrierVehicleTypes;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

import static org.assertj.core.api.Assertions.*;

class CarrierVehicleFactoryTest {
    @Test void centralFileChangesSupplyWindowsAndDispatchJitter(@org.junit.jupiter.api.io.TempDir java.nio.file.Path temp) throws Exception {
        var file=java.nio.file.Files.writeString(temp.resolve("hagrid.properties"),
                "supply.earlyStartSeconds=10000\nsupply.earlyEndSeconds=15000\nsupply.lateStartSeconds=60000\n"
                +"supply.lateEndSeconds=70000\nsupply.earlyDispatchJitterSeconds=0\nsupply.lateDispatchJitterSeconds=0\n"
                +"fleet.largeDispatchJitterMinutes=0\nfleet.defaultDispatchJitterMinutes=0\n");
        var config=new hagrid.core.HagridConfig(); config.loadConfiguration(file);
        var types=types("cargobike",10);
        var truck=VehicleUtils.createVehicleType(Id.create("ct_truck_heavy",VehicleType.class),"car");
        truck.setMaximumVelocity(25); types.getVehicleTypes().put(truck.getId(),truck);
        var fleet=hagrid.core.config.HagridConfigResolver.resolve(config,hagrid.core.config.HagridConfigResolver.Overrides.empty(),
                hagrid.core.config.HagridConfigResolver.Overrides.empty(),java.util.Map.of()).fleet();
        var factory=new CarrierVehicleFactory(types,fleet,config);
        var early=factory.createSupplyVehicle("early",Id.createLinkId("home"),true);
        var late=factory.createSupplyVehicle("late",Id.createLinkId("home"),false);
        assertThat(early.getEarliestStartTime()).isEqualTo(10000);
        assertThat(early.getLatestEndTime()).isEqualTo(15000);
        assertThat(late.getEarliestStartTime()).isEqualTo(60000);
        assertThat(late.getLatestEndTime()).isEqualTo(70000);
        assertThat(factory.createCEPVehicle(Id.createLinkId("home"),"depot",7,25200,"m").getEarliestStartTime()).isEqualTo(25200);
    }

    @ParameterizedTest
    @ValueSource(strings = {"bike", "cargobike"})
    void creationPreservesTheExactBikeMode(String mode) {
        var types = types(mode, 10);
        var vehicle = new CarrierVehicleFactory(types).createCEPVehicle(Id.createLinkId("home"), "depot", 7, 25200, "bike");
        assertThat(vehicle.getType().getNetworkMode()).isEqualTo(mode);
        assertThat(vehicle.getType()).isSameAs(types.getVehicleTypes().get(Id.create("ct_cep_bike", VehicleType.class)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"bike", "cargobike"})
    void defaultBikeMaximumIs20KmhInNativeMetresPerSecond(String mode) {
        var types = types(mode, 10);
        var vehicle = new CarrierVehicleFactory(types).createCEPVehicle(Id.createLinkId("home"), "depot", 7, 25200, "bike");
        assertThat(vehicle.getType().getMaximumVelocity()).isEqualTo(20.0 / 3.6);
    }

    @Test
    void customBikeCapacityPreservesModeSpeedCostsAndRegistryIdentity() {
        var types = types("cargobike", 10);
        var vehicle = new CarrierVehicleFactory(types).createCEPVehicle(Id.createLinkId("home"), "depot", 7, 25200, "30_bike");
        assertThat(vehicle.getType().getNetworkMode()).isEqualTo("cargobike");
        assertThat(vehicle.getType().getMaximumVelocity()).isEqualTo(20.0 / 3.6);
        assertThat(vehicle.getType().getCapacity().getOther()).isEqualTo(30);
        assertThat(vehicle.getType().getCostInformation().getCostsPerMeter()).isEqualTo(0.25);
        assertThat(types.getVehicleTypes().get(vehicle.getType().getId())).isSameAs(vehicle.getType());
    }

    @Test
    void constructingAnotherFactoryCannotReplaceTheFirstFactoryRegistry() {
        var first = types("cargobike", 10);
        var firstFactory = new CarrierVehicleFactory(first);
        var second = types("bike", 3);
        new CarrierVehicleFactory(second);
        var vehicle = firstFactory.createCEPVehicle(Id.createLinkId("home"), "depot", 7, 25200, "m");
        assertThat(vehicle.getType()).isSameAs(first.getVehicleTypes().get(Id.create("ct_cep_size_m", VehicleType.class)));
        assertThat(second.getVehicleTypes()).hasSize(3);
    }

    @Test
    void roadVehicleSpeedAndAvailabilityCalculationArePreserved() {
        var types = types("cargobike", 10);
        CarrierVehicleFactory.setGlobalRandomSeed(42);
        var vehicle = new CarrierVehicleFactory(types).createCEPVehicle(Id.createLinkId("home"), "depot", 7, 25200, "m");
        assertThat(vehicle.getType().getNetworkMode()).isEqualTo("car");
        assertThat(vehicle.getType().getMaximumVelocity()).isEqualTo(25);
        assertThat(vehicle.getLatestEndTime()).isEqualTo(Math.min(vehicle.getEarliestStartTime() + 25200 + 3600, 21 * 3600));
    }

    @Test
    void slowerXmlBikeMaximumIsNeverRaisedToTheCap() {
        var types = types("cargobike", 3);
        var vehicle = new CarrierVehicleFactory(types).createCEPVehicle(Id.createLinkId("home"), "depot", 7, 25200, "bike");
        assertThat(vehicle.getType().getMaximumVelocity()).isEqualTo(3);
    }

    @Test
    void missingDeclaredModeCannotBeReplacedWithCarDuringCreation() {
        var types = types("", 10);
        assertThatIllegalArgumentException().isThrownBy(() ->
                new CarrierVehicleFactory(types).createCEPVehicle(Id.createLinkId("home"), "depot", 7, 25200, "bike"))
                .withMessageContaining("network mode").withMessageContaining("ct_cep_bike");
    }

    @Test
    void invalidBikeVelocityFailsBeforeVehicleCreation() {
        var types = types("cargobike", Double.NaN);
        assertThatIllegalArgumentException().isThrownBy(() ->
                new CarrierVehicleFactory(types).createCEPVehicle(Id.createLinkId("home"), "depot", 7, 25200, "bike"))
                .withMessageContaining("velocity").withMessageContaining("ct_cep_bike");
    }

    @ParameterizedTest
    @ValueSource(strings = {"bike", "30_bike"})
    void resolvedFleetControlsBothXmlAndCustomBikeTypes(String size) {
        var config = new hagrid.core.HagridConfig();
        config.vehicles().setCargoBikeMaxSpeedKmh(18);
        var fleet = hagrid.core.config.HagridConfigResolver.resolve(config,
                hagrid.core.config.HagridConfigResolver.Overrides.empty(),
                hagrid.core.config.HagridConfigResolver.Overrides.empty(), java.util.Map.of()).fleet();
        var types = types("cargobike", 10);
        var vehicle = new CarrierVehicleFactory(types, fleet).createCEPVehicle(Id.createLinkId("home"), "depot", 7, 25200, size);
        assertThat(vehicle.getType().getMaximumVelocity()).isEqualTo(5);
        assertThat(vehicle.getType().getNetworkMode()).isEqualTo("cargobike");
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"m,23", "l,23", "60_m,60", "240_l,240", "80,80", "200,200"})
    void roadCapacityAliasesRetainXmlCostsAndRegisteredTypes(String size, double capacity) {
        var types = types("cargobike", 10);
        var vehicle = new CarrierVehicleFactory(types).createCEPVehicle(Id.createLinkId("home"), "depot", 7, 25200, size);
        assertThat(vehicle.getType().getCapacity().getOther()).isEqualTo(capacity);
        assertThat(vehicle.getType().getCostInformation().getCostsPerMeter()).isEqualTo(0.25);
        assertThat(vehicle.getType().getMaximumVelocity()).isEqualTo(25);
        assertThat(types.getVehicleTypes().get(vehicle.getType().getId())).isSameAs(vehicle.getType());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void supplyVehiclesRetainNativeTypeIdAndLegacyDispatchTimes(boolean early) {
        var types = types("cargobike", 10);
        var truck = VehicleUtils.createVehicleType(Id.create("ct_truck_heavy", VehicleType.class), "car");
        truck.setMaximumVelocity(25);
        types.getVehicleTypes().put(truck.getId(), truck);
        CarrierVehicleFactory.setGlobalRandomSeed(42);
        var vehicle = new CarrierVehicleFactory(types).createSupplyVehicle("hub", Id.createLinkId("home"), early);
        double shift = new java.util.Random(42).nextGaussian() * (early ? 10 : 20);
        assertThat(vehicle.getEarliestStartTime()).isEqualTo((int) ((early ? 6 * 3600 : 20 * 3600) + shift));
        assertThat(vehicle.getLatestEndTime()).isEqualTo(early ? 27900 : 24 * 3600);
        assertThat(vehicle.getId().toString()).isEqualTo(early ? "hub_supply_early" : "hub_supply_late");
        assertThat(vehicle.getType()).isSameAs(truck);
        assertThat(truck.getMaximumVelocity()).isEqualTo(25);
    }

    @ParameterizedTest
    @ValueSource(strings = {"30_bike", "60_m"})
    void customCapacityRetainsEngineAndAllOtherCapacityMetadata(String size) {
        var types = types("cargobike", 10);
        var base = types.getVehicleTypes().get(Id.create(size.endsWith("bike") ? "ct_cep_bike" : "ct_cep_size_m", VehicleType.class));
        base.setLength(6).setWidth(2).setPcuEquivalents(1.5).setFlowEfficiencyFactor(0.8).setDescription("template");
        base.getCapacity().setSeats(2).setStandingRoom(3).setWeightInTons(1.2).setVolumeInCubicMeters(7.5);
        base.getCapacity().getAttributes().putAttribute("loading", "rear");
        base.getEngineInformation().getAttributes().putAttribute("fuel", "electricity");
        base.getEngineInformation().getAttributes().putAttribute("consumption", 0.04);
        base.getCostInformation().getAttributes().putAttribute("costsPerSecondWaiting", 0.75);
        var created = new CarrierVehicleFactory(types).createCEPVehicle(Id.createLinkId("home"), "depot", 7, 25200, size).getType();
        assertThat(created.getCapacity().getWeightInTons()).isEqualTo(1.2);
        assertThat(created.getCapacity().getVolumeInCubicMeters()).isEqualTo(7.5);
        assertThat(created.getCapacity().getAttributes().getAttribute("loading")).isEqualTo("rear");
        assertThat(created.getEngineInformation().getAttributes().getAttribute("fuel")).isEqualTo("electricity");
        assertThat(created.getEngineInformation().getAttributes().getAttribute("consumption")).isEqualTo(0.04);
        assertThat(created.getCostInformation().getAttributes().getAttribute("costsPerSecondWaiting")).isEqualTo(0.75);
        assertThat(created.getCapacity().getSeats()).isEqualTo(2);
        assertThat(created.getCapacity().getStandingRoom()).isEqualTo(3);
        assertThat(created.getWidth()).isEqualTo(2);
        assertThat(created.getLength()).isEqualTo(6);
        assertThat(created.getPcuEquivalents()).isEqualTo(1.5);
        assertThat(created.getFlowEfficiencyFactor()).isEqualTo(0.8);
        assertThat(created.getDescription()).isEqualTo("template");
        assertThat(created.getCapacity().getOther()).isEqualTo(size.startsWith("30") ? 30 : 60);
        assertThat(base.getCapacity().getOther()).isEqualTo(23);
        created.getEngineInformation().getAttributes().putAttribute("fuel", "other");
        created.getCapacity().getAttributes().putAttribute("loading", "other");
        assertThat(base.getEngineInformation().getAttributes().getAttribute("fuel")).isEqualTo("electricity");
        assertThat(base.getCapacity().getAttributes().getAttribute("loading")).isEqualTo("rear");
        assertThat(types.getVehicleTypes().get(created.getId())).isSameAs(created);
    }

    private static CarrierVehicleTypes types(String bikeMode, double bikeSpeed) {
        var types = new CarrierVehicleTypes();
        for (var entry : java.util.Map.of("ct_cep_bike", bikeMode, "ct_cep_size_m", "car", "ct_cep_size_l", "car").entrySet()) {
            var type = VehicleUtils.createVehicleType(Id.create(entry.getKey(), VehicleType.class), entry.getValue());
            type.setMaximumVelocity(entry.getKey().equals("ct_cep_bike") ? bikeSpeed : 25);
            type.getCapacity().setOther(23);
            type.getCostInformation().setCostsPerMeter(0.25).setCostsPerSecond(0.5).setFixedCost(1.0);
            types.getVehicleTypes().put(type.getId(), type);
        }
        return types;
    }
}
