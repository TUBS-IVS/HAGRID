package hagrid.core.simulation;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.freight.carriers.*;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class ScenarioModalNetworksTest {
    @Test
    void carOnlyNeverLoadsBikeFileAndReusesSimulationNetwork() {
        var scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        ScenarioModalNetworks.attach(scenario, carriers("car"), () -> {
            throw new AssertionError("Car-only run must not read a bike file");
        });
        assertThat(scenario.getScenarioElement("carNetwork")).isSameAs(scenario.getNetwork());
        assertThat(scenario.getScenarioElement("bikeNetwork")).isSameAs(scenario.getNetwork());
    }

    @Test
    void emptyFreightRunDoesNotLoadBikeFile() {
        var scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        ScenarioModalNetworks.attach(scenario, new Carriers(), () -> { throw new AssertionError("Unexpected bike load"); });
        assertThat(scenario.getScenarioElement("carNetwork")).isSameAs(scenario.getNetwork());
    }

    @Test
    void activeCargoBikeFleetLoadsItsInputOnce() {
        var scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        var bike = NetworkUtils.createNetwork();
        var calls = new AtomicInteger();
        ScenarioModalNetworks.attach(scenario, carriers("cargobike"), () -> { calls.incrementAndGet(); return bike; });
        assertThat(calls).hasValue(1);
        assertThat(scenario.getScenarioElement("bikeNetwork")).isSameAs(scenario.getNetwork());
        assertThat(scenario.getScenarioElement("carNetwork")).isSameAs(scenario.getNetwork());
    }

    @Test
    void failedBikeInputDoesNotPublishPartialAliases() {
        var scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        assertThatIllegalArgumentException().isThrownBy(() ->
                ScenarioModalNetworks.attach(scenario, carriers("cargobike"), () -> {
                    throw new IllegalArgumentException("Missing cargo-bike input");
                })).withMessageContaining("Missing");
        assertThat(scenario.getScenarioElement("carNetwork")).isNull();
        assertThat(scenario.getScenarioElement("bikeNetwork")).isNull();
    }

    @Test
    void refusesToOverwriteAlreadyAttachedNetworks() {
        var scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        var previous = NetworkUtils.createNetwork();
        scenario.addScenarioElement("carNetwork", previous);
        assertThatIllegalArgumentException().isThrownBy(() ->
                ScenarioModalNetworks.attach(scenario, carriers("cargobike"), () -> {
                    throw new AssertionError("Existing aliases must fail before any input read");
                }))
                .withMessageContaining("already contains");
        assertThat(scenario.getScenarioElement("carNetwork")).isSameAs(previous);
        assertThat(scenario.getScenarioElement("bikeNetwork")).isNull();
    }

    @Test
    void aCommonInputWithAllCargoBikeLocationsNeedsNoSecondNetwork() {
        var scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        populate(scenario.getNetwork(), "depot", java.util.Set.of("car", "cargobike"));
        var original = scenario.getNetwork();
        ScenarioModalNetworks.attach(scenario, carriers("cargobike"), () -> {
            throw new AssertionError("Common input needs no legacy bike file");
        });
        assertThat(scenario.getNetwork()).isSameAs(original);
        assertThat(scenario.getScenarioElement("carNetwork")).isSameAs(original);
        assertThat(scenario.getScenarioElement("bikeNetwork")).isSameAs(original);
    }

    @Test
    void compatibleModalInputsBecomeTheActualScenarioNetworkWithDetachedEvents() {
        var config = ConfigUtils.createConfig();
        config.network().setTimeVariantNetwork(true);
        var scenario = ScenarioUtils.createScenario(config);
        populate(scenario.getNetwork(), "depot", java.util.Set.of("car"));
        populate(scenario.getNetwork(), "car-only", java.util.Set.of("car"));
        var original = scenario.getNetwork();
        var event = new org.matsim.core.network.NetworkChangeEvent(100);
        event.addLink(original.getLinks().get(Id.createLinkId("depot")));
        event.setFreespeedChange(new org.matsim.core.network.NetworkChangeEvent.ChangeValue(
                org.matsim.core.network.NetworkChangeEvent.ChangeType.ABSOLUTE_IN_SI_UNITS, 2));
        NetworkUtils.setNetworkChangeEvents(original, List.of(event));
        var bike = NetworkUtils.createNetwork();
        populate(bike, "depot", java.util.Set.of("cargobike"));
        populate(bike, "bike-only", java.util.Set.of("cargobike"));
        ScenarioModalNetworks.attach(scenario, carriers("cargobike"), () -> bike);
        var common = scenario.getNetwork();
        assertThat(common).isNotSameAs(original).isNotSameAs(bike);
        assertThat(scenario.getScenarioElement("carNetwork")).isSameAs(common);
        assertThat(scenario.getScenarioElement("bikeNetwork")).isSameAs(common);
        assertThat(common.getLinks()).hasSize(3);
        assertThat(common.getLinks().get(Id.createLinkId("depot")).getAllowedModes())
                .containsExactlyInAnyOrder("car", "cargobike");
        assertThat(common.getLinks().get(Id.createLinkId("car-only")).getAllowedModes()).containsExactly("car");
        assertThat(common.getLinks().get(Id.createLinkId("bike-only")).getFreespeed(100)).isEqualTo(10);
        assertThat(NetworkUtils.getNetworkChangeEvents(common)).hasSize(1);
        assertThat(NetworkUtils.getNetworkChangeEvents(common).peek().getLinks().iterator().next())
                .isSameAs(common.getLinks().get(Id.createLinkId("depot")));
        assertThat(common.getLinks().get(Id.createLinkId("depot")).getFreespeed(100)).isEqualTo(2);
        assertThat(original.getLinks()).hasSize(2);
    }

    @Test
    void conflictingModalInputCannotReplaceTheSimulationGraphOrPublishAliases() {
        var scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        populate(scenario.getNetwork(), "depot", java.util.Set.of("car"));
        var original = scenario.getNetwork();
        var bike = NetworkUtils.createNetwork();
        populate(bike, "depot", java.util.Set.of("cargobike"));
        bike.getNodes().get(Id.createNodeId("b")).setCoord(new org.matsim.api.core.v01.Coord(200, 0));
        assertThatIllegalArgumentException().isThrownBy(() ->
                ScenarioModalNetworks.attach(scenario, carriers("cargobike"), () -> bike))
                .withMessageContaining("coordinates");
        assertThat(scenario.getNetwork()).isSameAs(original);
        assertThat(scenario.getScenarioElement("carNetwork")).isNull();
        assertThat(scenario.getScenarioElement("bikeNetwork")).isNull();
    }

    @Test
    void shipmentDeliveryMissingFromCommonInputStillRequiresAdditionalBikeInput() {
        var scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        populate(scenario.getNetwork(), "depot", java.util.Set.of("car", "cargobike"));
        var carriers = carriers("cargobike");
        var carrier = carriers.getCarriers().values().iterator().next();
        CarriersUtils.addShipment(carrier, CarrierShipment.Builder.newInstance(
                Id.create("shipment", CarrierShipment.class), Id.createLinkId("depot"), Id.createLinkId("bike-only"), 1).build());
        var bike = NetworkUtils.createNetwork();
        populate(bike, "depot", java.util.Set.of("cargobike"));
        populate(bike, "bike-only", java.util.Set.of("cargobike"));
        var calls = new AtomicInteger();
        ScenarioModalNetworks.attach(scenario, carriers, () -> { calls.incrementAndGet(); return bike; });
        assertThat(calls).hasValue(1);
        assertThat(scenario.getNetwork().getLinks()).containsKey(Id.createLinkId("bike-only"));
    }

    private static void populate(org.matsim.api.core.v01.network.Network network, String id, java.util.Set<String> modes) {
        var a = network.getNodes().get(Id.createNodeId("a"));
        var b = network.getNodes().get(Id.createNodeId("b"));
        if (a == null) {
            a = NetworkUtils.createAndAddNode(network, Id.createNodeId("a"), new org.matsim.api.core.v01.Coord(0, 0));
            b = NetworkUtils.createAndAddNode(network, Id.createNodeId("b"), new org.matsim.api.core.v01.Coord(100, 0));
        }
        var link = NetworkUtils.createAndAddLink(network, Id.createLinkId(id), a, b, 100, 10, 1000, 1);
        link.setAllowedModes(modes);
    }

    private static Carriers carriers(String mode) {
        var type = VehicleUtils.createVehicleType(Id.create("type", VehicleType.class), mode);
        var carrier = CarriersUtils.createCarrier(Id.create("carrier", Carrier.class));
        CarriersUtils.addCarrierVehicle(carrier, CarrierVehicle.newInstance(Id.createVehicleId("vehicle"),
                Id.createLinkId("depot"), type));
        return new Carriers(List.of(carrier));
    }
}

