package hagrid.integrated.modular;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Id;
import org.matsim.freight.carriers.CarrierVehicleTypeWriter;
import org.matsim.freight.carriers.CarrierVehicleTypes;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ModularVehicleTypes")
class ModularVehicleTypesTest {

    @Test
    @DisplayName("capsule type: 216 parcel capacity, id ushift_cargo_capsule, costs cloned from the largest van")
    void capsuleClonesLargestVanCosts(@TempDir Path tmp) throws Exception {
        // van fixture mirrors MarriedBaselineEndToEndTest lines 63-70
        CarrierVehicleTypes vans = new CarrierVehicleTypes();
        VehicleType m = VehicleUtils.createVehicleType(Id.create("ct_cep_size_m", VehicleType.class));
        m.getCapacity().setOther(165);
        m.setNetworkMode("car");
        m.getCostInformation().setCostsPerMeter(0.0004).setCostsPerSecond(0.0).setFixedCost(170.0);
        vans.getVehicleTypes().put(m.getId(), m);
        VehicleType l = VehicleUtils.createVehicleType(Id.create("ct_cep_size_l", VehicleType.class));
        l.getCapacity().setOther(250);
        l.setNetworkMode("car");
        l.getCostInformation().setCostsPerMeter(0.0006).setCostsPerSecond(0.0).setFixedCost(200.0);
        vans.getVehicleTypes().put(l.getId(), l);
        Path typesFile = tmp.resolve("vans.xml");
        new CarrierVehicleTypeWriter(vans).write(typesFile.toString());

        CarrierVehicleTypes result = ModularVehicleTypes.createCapsuleTypes(typesFile.toString());

        assertThat(result.getVehicleTypes()).hasSize(1);
        VehicleType capsule = result.getVehicleTypes().values().iterator().next();
        assertThat(capsule.getId().toString()).isEqualTo(Modular.CARGO_CAPSULE_TYPE_ID);
        assertThat(capsule.getCapacity().getOther()).isEqualTo((double) Modular.CARGO_CAPACITY_PARCELS);
        assertThat(capsule.getNetworkMode()).isEqualTo("car");
        // cost donor = LARGEST-capacity van (deterministic; ties impossible with m/l)
        assertThat(capsule.getCostInformation().getCostsPerMeter()).isEqualTo(0.0006);
        assertThat(capsule.getCostInformation().getFixedCosts()).isEqualTo(200.0);
    }
}
