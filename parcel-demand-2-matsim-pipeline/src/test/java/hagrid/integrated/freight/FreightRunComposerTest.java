package hagrid.integrated.freight;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.freight.carriers.*;
import org.matsim.freight.carriers.controller.CarrierStrategyManager;
import org.matsim.testcases.MatsimTestUtils;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("FreightRunComposer - carriers into a (married) scenario")
class FreightRunComposerTest {

    @RegisterExtension
    public MatsimTestUtils utils = new MatsimTestUtils();

    @Test
    @DisplayName("keepSelectedStrategyManager registers exactly ONE strategy (empty manager would crash replanning)")
    void keepSelectedManagerHasOneStrategy() {
        CarrierStrategyManager manager = FreightRunComposer.keepSelectedStrategyManager();
        assertThat(manager.getStrategies(null))
                .as("married runs iterate (maxIter>0): an empty manager throws at the first replanning")
                .hasSize(1);
    }

    @Test
    @DisplayName("addCarriers loads the routed carriers + vehicle types into the scenario")
    void addCarriersLoadsCarriers() throws Exception {
        Path dir = Path.of(utils.getOutputDirectory());

        // vehicle types XML
        CarrierVehicleTypes types = new CarrierVehicleTypes();
        VehicleType van = VehicleUtils.createVehicleType(Id.create("ct_cep_size_m", VehicleType.class));
        van.getCapacity().setOther(165);
        van.setNetworkMode("car");
        van.getCostInformation().setCostsPerMeter(0.0004).setCostsPerSecond(0.0).setFixedCost(170.0);
        types.getVehicleTypes().put(van.getId(), van);
        Path typesFile = dir.resolve("vans.xml");
        new CarrierVehicleTypeWriter(types).write(typesFile.toString());

        // one carrier with a vehicle + service, written the way the preprocessor writes them
        Carrier carrier = CarriersUtils.createCarrier(Id.create("dhl", Carrier.class));
        carrier.getCarrierCapabilities().setFleetSize(CarrierCapabilities.FleetSize.INFINITE);
        CarriersUtils.addCarrierVehicle(carrier, CarrierVehicle.Builder
                .newInstance(Id.createVehicleId("dhl_van_1"), Id.createLinkId("l0"), van)
                .setEarliestStart(8 * 3600).setLatestEnd(16 * 3600).build());
        CarriersUtils.addService(carrier, CarrierService.Builder
                .newInstance(Id.create("dhl_1", CarrierService.class), Id.createLinkId("l1"))
                .setCapacityDemand(3).build());
        Carriers carriers = new Carriers();
        carriers.addCarrier(carrier);
        Path carriersFile = dir.resolve("carriers.xml");
        CarriersUtils.writeCarriers(carriers, carriersFile.toString());

        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        FreightRunComposer.addCarriers(scenario, carriersFile.toString(), typesFile.toString());

        assertThat(CarriersUtils.getCarriers(scenario).getCarriers())
                .containsKey(Id.create("dhl", Carrier.class));
        assertThat(CarriersUtils.getCarrierVehicleTypes(scenario).getVehicleTypes())
                .containsKey(Id.create("ct_cep_size_m", VehicleType.class));
    }
}
