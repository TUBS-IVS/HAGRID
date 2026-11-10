package hagrid.hannover.demand;

import hagrid.core.HagridConfig;
import hagrid.core.util.Hub;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Id;
import org.matsim.freight.carriers.*;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class SupplyBaselineRoundTripTest {
    @TempDir Path directory;

    @Test void largestRemaindersPreserveTotalAndResolveTiesByLaneId() {
        var north = new SupplyBaseline.Lane("hub", "north", SupplyBaseline.Flow.INBOUND, Id.createLinkId("n"), 3);
        var south = new SupplyBaseline.Lane("hub", "south", SupplyBaseline.Flow.INBOUND, Id.createLinkId("s"), 2);
        var baseline = SupplyBaseline.of(List.of(south, north));
        assertThat(baseline.allocate("hub", SupplyBaseline.Flow.INBOUND, 7)).containsEntry(north, 4L).containsEntry(south, 3L);
        var a = new SupplyBaseline.Lane("hub", "a", SupplyBaseline.Flow.INBOUND, Id.createLinkId("n"), 1);
        var b = new SupplyBaseline.Lane("hub", "b", SupplyBaseline.Flow.INBOUND, Id.createLinkId("s"), 1);
        assertThat(SupplyBaseline.of(List.of(b,a)).allocate("hub", SupplyBaseline.Flow.INBOUND, 1))
                .containsEntry(a, 1L).containsEntry(b, 0L);
    }

    @Test void allocationHandlesLongProductsWithoutOverflow() {
        var a = new SupplyBaseline.Lane("hub", "a", SupplyBaseline.Flow.INBOUND, Id.createLinkId("n"), Long.MAX_VALUE);
        var b = new SupplyBaseline.Lane("hub", "b", SupplyBaseline.Flow.INBOUND, Id.createLinkId("s"), Long.MAX_VALUE);
        var result = SupplyBaseline.of(List.of(a,b)).allocate("hub", SupplyBaseline.Flow.INBOUND, Long.MAX_VALUE);
        assertThat(result.get(a)).isEqualTo(Long.MAX_VALUE / 2 + 1);
        assertThat(result.get(b)).isEqualTo(Long.MAX_VALUE / 2);
    }

    @Test void positiveDemandCannotBeAllocatedAgainstZeroBasis() {
        var empty = new SupplyBaseline.Lane("hub", "a", SupplyBaseline.Flow.INBOUND, Id.createLinkId("n"), 0);
        assertThatIllegalArgumentException().isThrownBy(() -> SupplyBaseline.of(List.of(empty))
                .allocate("hub", SupplyBaseline.Flow.INBOUND, 1)).withMessageContaining("zero baseline");
    }

    @Test void generatedDirectionalBaselineCanBeReused() throws Exception {
        var original = new DemandModalFixture(directory.resolve("original"), "truck");
        var delivery = original.delivery.getCarriers().values().iterator().next();
        delivery.getServices().clear();
        CarriersUtils.addService(delivery, DemandModalFixture.service("demand", "car-stop", 200,
                new org.matsim.api.core.v01.Coord(75,0), 0, 86400));
        original.config.supply().setLinkDirection("north", "truck-home");
        original.config.supply().setLinkDirection("south", "truck-depot");
        original.config.supply().setDirectionProbability("north", .5);
        original.config.supply().setDirectionProbability("south", .5);
        original.config.supply().setDirectionProbability("east", 0);
        original.config.supply().setDirectionProbability("west", 0);
        original.supplyGenerator().run();
        var before = (Carriers)original.scenario.getScenarioElement("supply");
        // Legacy empty DHL templates are not active lanes; real parcels must remain exactly200.
        assertThat(before.getCarriers().values()).filteredOn(c -> c.getServices().values().stream()
                .mapToInt(CarrierService::getCapacityDemand).sum()>0).hasSize(2);
        assertThat(before.getCarriers().values().stream().flatMap(c -> c.getServices().values().stream())
                .mapToInt(CarrierService::getCapacityDemand).sum()).isEqualTo(200);
        Path baselineFile = Path.of(original.config.io().supplyCarriersSplitUnrouted());
        var baseline = SupplyBaseline.read(baselineFile, original.types, original.scenario.getNetwork(), original.hubs);
        assertThat(baseline.lanes()).hasSize(2);
        var regenerated = new DemandModalFixture(directory.resolve("regenerated"), "truck");
        var newDelivery = regenerated.delivery.getCarriers().values().iterator().next();
        newDelivery.getServices().clear();
        CarriersUtils.addService(newDelivery, DemandModalFixture.service("demand", "car-stop", 200,
                new org.matsim.api.core.v01.Coord(75,0), 0, 86400));
        regenerated.config.setScenario(HagridConfig.Scenario.WHITE_LABEL);
        regenerated.config.supply().setBaselineCarriersFile(baselineFile.toString());
        regenerated.supplyGenerator().run();
        var after = (Carriers)regenerated.scenario.getScenarioElement("supply");
        assertThat(channelAmounts(after)).isEqualTo(channelAmounts(before));
        var reread = SupplyBaseline.read(Path.of(regenerated.config.io().supplyCarriersSplitUnrouted()),
                regenerated.types, regenerated.scenario.getNetwork(), regenerated.hubs);
        assertThat(reread.lanes()).containsExactlyElementsOf(baseline.lanes());
    }

    @Test void dhlInboundAndOutboundRemainDistinct() throws Exception {
        var f = new DemandModalFixture(directory, "car");
        var satellite = DemandModalFixture.hub("dhl-satellite", "dhl", "car-stop", new org.matsim.api.core.v01.Coord(75,0));
        f.hubs.put(satellite.getId(), satellite);
        var delivery = f.delivery.getCarriers().values().iterator().next();
        delivery.getAttributes().putAttribute("hubId", satellite.getId().toString());
        delivery.getAttributes().putAttribute("hub", satellite);
        f.supplyGenerator().run();
        Path input = Path.of(f.config.io().supplyCarriersSplitUnrouted());
        var baseline = SupplyBaseline.read(input, f.types, f.scenario.getNetwork(), f.hubs);
        assertThat(baseline.lanes()).anyMatch(l -> l.flow() == SupplyBaseline.Flow.INBOUND && l.hubId().equals("dhl_hannover_anderten"))
                .anyMatch(l -> l.flow() == SupplyBaseline.Flow.OUTBOUND && l.hubId().equals("dhl-satellite"));
        ((org.matsim.core.scenario.MutableScenario)f.scenario).removeScenarioElement("supply");
        f.config.setScenario(HagridConfig.Scenario.WHITE_LABEL);
        f.config.supply().setBaselineCarriersFile(input.toString());
        f.supplyGenerator().run();
        var after = SupplyBaseline.read(Path.of(f.config.io().supplyCarriersSplitUnrouted()), f.types, f.scenario.getNetwork(), f.hubs);
        assertThat(after.lanes()).containsExactlyElementsOf(baseline.lanes());
    }

    @Test void unknownHubAndInvalidDepotFailBeforePublication() throws Exception {
        var f = new DemandModalFixture(directory, "truck");
        var baseline = new Carriers();
        var c = f.carrier("supply_unknown", "truck", "truck-home");
        c.getAttributes().putAttribute("hubId", "unknown");
        c.getAttributes().putAttribute("supplyLink", "truck-home");
        baseline.addCarrier(c);
        Path file = directory.resolve("invalid.xml");
        new CarrierPlanWriter(baseline).write(file.toString());
        assertThatIllegalArgumentException().isThrownBy(() -> SupplyBaseline.read(file, f.types, f.scenario.getNetwork(), f.hubs));
        c.getAttributes().putAttribute("hubId", "hermes-hub");
        c.getAttributes().putAttribute("supplyLink", "missing");
        new CarrierPlanWriter(baseline).write(file.toString());
        assertThatIllegalArgumentException().isThrownBy(() -> SupplyBaseline.read(file, f.types, f.scenario.getNetwork(), f.hubs));
        assertThat(f.scenario.getScenarioElement("supply")).isNull();
        assertThat(Path.of(f.config.io().supplyCarriersUnrouted())).doesNotExist();
    }

    private static Map<String,Integer> channelAmounts(Carriers carriers) {
        var result = new TreeMap<String,Integer>();
        carriers.getCarriers().values().stream().filter(c -> c.getServices().values().stream().mapToInt(CarrierService::getCapacityDemand).sum()>0).forEach(c -> result.put(c.getAttributes().getAttribute("supplyLaneId") + ":"
                + c.getAttributes().getAttribute("supplyFlow") + ":" + c.getAttributes().getAttribute("supplyLink"),
                c.getServices().values().stream().mapToInt(CarrierService::getCapacityDemand).sum()));
        return result;
    }
}
