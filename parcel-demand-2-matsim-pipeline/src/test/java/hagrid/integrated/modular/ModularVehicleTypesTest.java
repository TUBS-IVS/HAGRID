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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ModularVehicleTypes")
class ModularVehicleTypesTest {

    /**
     * Review J-F7: a van type whose XML never sets an 'other' (parcel) capacity defaults to
     * {@code Double.POSITIVE_INFINITY} (VehicleCapacity's own default) - which silently WINS the
     * cost-donor max() against every properly-specified van, regardless of the real vans' actual
     * sizes. Without the guard this fixture would clone costs from {@code ct_cep_undefined}
     * (0.0009/0.0/999.0) instead of the real largest van {@code ct_cep_size_l}
     * (0.0006/0.0/200.0), a silent wrong result. Fixture mirrors {@link #capsuleClonesLargestVanCosts}
     * plus one additional van that deliberately never calls {@code getCapacity().setOther(...)}.
     */
    @Test
    @DisplayName("donor with +Infinity 'other' capacity (unset in the XML) -> IllegalStateException naming the file")
    void undefinedCapacityDonorThrowsNamingFile(@TempDir Path tmp) throws Exception {
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
        // Deliberately NOT calling getCapacity().setOther(...) here - stays at the class default
        // (Double.POSITIVE_INFINITY), which is exactly the bug this guard exists to catch.
        VehicleType undefined = VehicleUtils.createVehicleType(
                Id.create("ct_cep_undefined", VehicleType.class));
        undefined.setNetworkMode("car");
        undefined.getCostInformation().setCostsPerMeter(0.0009).setCostsPerSecond(0.0).setFixedCost(999.0);
        vans.getVehicleTypes().put(undefined.getId(), undefined);
        Path typesFile = tmp.resolve("vans_undefined.xml");
        new CarrierVehicleTypeWriter(vans).write(typesFile.toString());

        assertThatThrownBy(() -> ModularVehicleTypes.createCapsuleTypes(typesFile.toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ct_cep_undefined")
                .hasMessageContaining(typesFile.toString());
    }

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
