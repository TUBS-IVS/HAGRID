package hagrid.core.simulation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.matsim.api.core.v01.Id;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.freight.carriers.*;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class CarrierInputConsistencyTest {
    @TempDir Path directory;

    @Test
    void duplicateCarrierIdFailsBeforeInputsAreMutated() {
        var a = carrier("duplicate");
        var b = carrier("duplicate");
        var first = new Carriers(List.of(a));
        var second = new Carriers(List.of(b));
        assertThatIllegalArgumentException().isThrownBy(() -> CarrierInputMerger.merge(first, second))
                .withMessageContaining("Duplicate carrier ID").withMessageContaining("duplicate");
        assertThat(first.getCarriers().get(a.getId())).isSameAs(a);
        assertThat(second.getCarriers().get(b.getId())).isSameAs(b);
    }

    @Test
    void preservesRecordedIterationsAndMode() {
        var bike = carrier("bike");
        CarriersUtils.setCarrierMode(bike, "cargobike");
        CarriersUtils.setJspritIterations(bike, 7);
        CarriersUtils.setJspritComputationTime(bike, 12.5);
        var plan = new CarrierPlan(List.of());
        bike.addPlan(plan);
        var merged = CarrierInputMerger.merge(new Carriers(List.of(bike)));
        var result = merged.getCarriers().get(bike.getId());
        assertThat(result).isSameAs(bike);
        assertThat(result.getSelectedPlan()).isSameAs(plan);
        assertThat(CarriersUtils.getCarrierMode(result)).isEqualTo("cargobike");
        assertThat(CarriersUtils.getJspritIterations(result)).isEqualTo(7);
        assertThat(CarriersUtils.getJspritComputationTime(result)).isEqualTo(12.5);
    }

    @Test
    void missingRecordedSettingsRemainMissing() {
        var bike = carrier("missing");
        var merged = CarrierInputMerger.merge(new Carriers(List.of(bike)));
        var attributes = merged.getCarriers().get(bike.getId()).getAttributes();
        assertThat(attributes.getAttribute("carrierMode")).isNull();
        assertThat(attributes.getAttribute("jspritIterations")).isNull();
        assertThat(attributes.getAttribute("jspritComputationTime")).isNull();
    }

    @Test
    void rejectsMismatchedMapKey() {
        var input = new Carriers();
        input.getCarriers().put(Id.create("wrong", Carrier.class), carrier("actual"));
        assertThatIllegalArgumentException().isThrownBy(() -> CarrierInputMerger.merge(input))
                .withMessageContaining("map key");
    }

    @Test
    void registersTheParsedObjectsAndTypesWithoutRoundTrip() {
        var scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        var bike = carrier("bike");
        var carriers = new Carriers(List.of(bike));
        var types = new CarrierVehicleTypes();
        var type = VehicleUtils.createVehicleType(Id.create("bike-type", VehicleType.class), "cargobike");
        types.getVehicleTypes().put(type.getId(), type);
        CarrierInputMerger.register(scenario, carriers, types);
        assertThat(CarriersUtils.getCarriers(scenario).getCarriers().get(bike.getId())).isSameAs(bike);
        assertThat(CarriersUtils.getCarrierVehicleTypes(scenario).getVehicleTypes().get(type.getId())).isSameAs(type);
    }

    @Test
    void refusesToOverwriteScenarioInputs() {
        var scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        var existing = carrier("existing");
        CarriersUtils.addOrGetCarriers(scenario).addCarrier(existing);
        assertThatIllegalArgumentException().isThrownBy(() -> CarrierInputMerger.register(scenario,
                new Carriers(List.of(carrier("new"))), new CarrierVehicleTypes())).withMessageContaining("overwrite");
        assertThat(CarriersUtils.getCarriers(scenario).getCarriers().values()).containsExactly(existing);
    }

    @ParameterizedTest
    @ValueSource(strings = {"file", "url", "stream"})
    void nativeXmlReaderCannotSilentlyReplaceDuplicateCarrierIds(String entryPoint) throws Exception {
        var file = directory.resolve("duplicate.xml");
        var types = xmlTypes();
        var type = types.getVehicleTypes().values().iterator().next();
        new CarrierPlanWriter(new Carriers(List.of(xmlCarrier("first", type), xmlCarrier("second", type)))).write(file.toString());
        String original = Files.readString(file);
        assertThat(original).contains("id=\"second\"");
        Files.writeString(file, original.replace("id=\"second\"", "id=\"first\""));
        Throwable failure = catchThrowable(() -> read(CarrierInputMerger.newInputCollection(), types, file, entryPoint));
        assertThat(failure).hasMessageContaining("Duplicate carrier ID").hasMessageContaining("first");
        if (entryPoint.equals("stream")) assertThat(failure).isInstanceOf(java.io.UncheckedIOException.class);
        else assertThat(failure).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"file", "url", "stream"})
    void nativeXmlRoundTripRetainsModeIterationsAndActualComputationTime(String entryPoint) throws Exception {
        var file = directory.resolve("valid.xml");
        var types = xmlTypes();
        var type = types.getVehicleTypes().values().iterator().next();
        var input = xmlCarrier("bike", type);
        CarriersUtils.setCarrierMode(input, "cargobike");
        CarriersUtils.setJspritIterations(input, 7);
        CarriersUtils.setJspritComputationTime(input, 12.5);
        new CarrierPlanWriter(new Carriers(List.of(input))).write(file.toString());
        var output = CarrierInputMerger.newInputCollection();
        read(output, types, file, entryPoint);
        var parsed = output.getCarriers().get(input.getId());
        assertThat(CarriersUtils.getCarrierMode(parsed)).isEqualTo("cargobike");
        assertThat(CarriersUtils.getJspritIterations(parsed)).isEqualTo(7);
        assertThat(CarriersUtils.getJspritComputationTime(parsed)).isEqualTo(12.5);
        assertThat(parsed.getCarrierCapabilities().getCarrierVehicles().values().iterator().next().getType()).isSameAs(type);
    }

    private static void read(Carriers carriers, CarrierVehicleTypes types, Path file, String entryPoint) throws Exception {
        var reader = new CarrierPlanXmlReader(carriers, types);
        switch (entryPoint) {
            case "file" -> reader.readFile(file.toString());
            case "url" -> reader.readURL(file.toUri().toURL());
            case "stream" -> {
                try (var input = Files.newInputStream(file)) { reader.readStream(input); }
            }
            default -> throw new IllegalArgumentException("Unknown reader entry point " + entryPoint);
        }
    }

    private static CarrierVehicleTypes xmlTypes() {
        var types = new CarrierVehicleTypes();
        var type = VehicleUtils.createVehicleType(Id.create("xml-bike", VehicleType.class), "cargobike");
        types.getVehicleTypes().put(type.getId(), type);
        return types;
    }

    private static Carrier xmlCarrier(String id, VehicleType type) {
        var carrier = carrier(id);
        CarriersUtils.addCarrierVehicle(carrier, CarrierVehicle.newInstance(
                Id.createVehicleId(id + "-vehicle"), Id.createLinkId("depot"), type));
        return carrier;
    }

    private static Carrier carrier(String id) {
        return CarriersUtils.createCarrier(Id.create(id, Carrier.class));
    }
}

