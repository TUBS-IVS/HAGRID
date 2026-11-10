package hagrid.core.routing;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.matsim.api.core.v01.Id;
import org.matsim.freight.carriers.*;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;

class CarrierVehicleTypeInputsTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"file", "url", "stream"})
    void duplicateModernIdsRetainTheNativeDiagnosticForEveryEntryPoint(String entryPoint) throws Exception {
        Path file = fixture();
        Files.writeString(file, Files.readString(file).replace("id=\"second\"", "id=\"first\""));
        var target = CarrierVehicleTypeInputs.newInputCollection();
        assertThatThrownBy(() -> read(target, file, entryPoint))
                .hasMessageContaining("first").hasMessageContaining("type")
                .isNotInstanceOf(NullPointerException.class);
        assertThat(target.getVehicleTypes()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"file", "url", "stream"})
    void validModernInputsRetainRegisteredProfilesForEveryEntryPoint(String entryPoint) throws Exception {
        var target = CarrierVehicleTypeInputs.newInputCollection();
        read(target, fixture(), entryPoint);
        assertThat(target.getVehicleTypes()).hasSize(2);
        assertThat(target.getVehicleTypes().get(Id.create("first", VehicleType.class)).getNetworkMode()).isEqualTo("cargobike");
        assertThat(target.getVehicleTypes().get(Id.create("first", VehicleType.class)).getMaximumVelocity()).isEqualTo(5);
        assertThat(target.getVehicleTypes().get(Id.create("second", VehicleType.class)).getNetworkMode()).isEqualTo("car");
    }

    private Path fixture() {
        var types = new CarrierVehicleTypes();
        var bike = VehicleUtils.createVehicleType(Id.create("first", VehicleType.class), "cargobike");
        bike.setMaximumVelocity(5);
        var car = VehicleUtils.createVehicleType(Id.create("second", VehicleType.class), "car");
        car.setMaximumVelocity(20);
        types.getVehicleTypes().put(bike.getId(), bike);
        types.getVehicleTypes().put(car.getId(), car);
        var file = directory.resolve("types.xml");
        new CarrierVehicleTypeWriter(types).write(file.toString());
        return file;
    }

    private static void read(CarrierVehicleTypes target, Path file, String entryPoint) throws Exception {
        var reader = new CarrierVehicleTypeReader(target);
        switch (entryPoint) {
            case "file" -> reader.readFile(file.toString());
            case "url" -> reader.readURL(file.toUri().toURL());
            case "stream" -> { try (var stream = Files.newInputStream(file)) { reader.readStream(stream); } }
            default -> throw new AssertionError(entryPoint);
        }
    }
}
