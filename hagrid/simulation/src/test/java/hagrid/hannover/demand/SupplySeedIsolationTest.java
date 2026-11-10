package hagrid.hannover.demand;

import hagrid.core.util.Hub;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.freight.carriers.*;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/** Native supply generation and XML readback exercise the master seed's actual consumers. */
class SupplySeedIsolationTest {
    @TempDir Path directory;

    @Test void tagsAndAnInterleavedUnrelatedRunCannotChangeSupplyJobsOrDepartures() throws Exception {
        var expected = generate("before", 1337);
        generate("unrelated", -987654321);
        assertThat(generate("after", 1337)).isEqualTo(expected);
        assertThat(expected.rows()).isNotEmpty();
    }

    @Test void changingTheMasterChangesActualSupplyDirectionOrDeparture() throws Exception {
        assertThat(generate("master-A", 1337).rows()).isNotEqualTo(generate("master-B", 987654321).rows());
    }

    private Snapshot generate(String tag, long seed) throws Exception {
        var f = new DemandModalFixture(directory.resolve(tag), "truck");
        f.config.setTag(tag); f.config.setSimulationDate(LocalDate.of(2025, 5, 13));
        f.config.setAdditionalConfigurationValues(Map.of("run.seed", Long.toString(seed)));
        var delivery = f.delivery.getCarriers().values().iterator().next();
        delivery.getServices().clear();
        CarriersUtils.addService(delivery, DemandModalFixture.service("large-delivery", "car-stop", 80, new Coord(75, 0), 0, 86400));
        f.supplyGenerator().run();
        var actual = (Carriers) f.scenario.getScenarioElement("supply");
        var loaded = new Carriers();
        new CarrierPlanXmlReader(loaded, f.types).readFile(f.config.io().supplyCarriersSplitUnrouted());
        assertThat(rows(loaded)).isEqualTo(rows(actual));
        assertThat(actual.getCarriers().values().stream().flatMap(c -> c.getServices().values().stream())
                .mapToInt(CarrierService::getCapacityDemand).sum()).isEqualTo(80);
        assertThat(f.hubs.get(Id.create("hermes-hub", Hub.class)).getAssignedSupplyDemand()).isEqualTo(80);
        return new Snapshot(rows(actual));
    }

    private static List<String> rows(Carriers carriers) {
        var rows = new ArrayList<String>();
        for (var c : carriers.getCarriers().values()) {
            for (var service : c.getServices().values()) {
                rows.add("job|" + c.getId() + "|" + service.getId() + "|" + service.getServiceLinkId() + "|"
                        + service.getCapacityDemand() + "|" + service.getServiceDuration() + "|"
                        + service.getServiceStaringTimeWindow().getStart() + "|" + service.getServiceStaringTimeWindow().getEnd());
            }
            for (var v : c.getCarrierCapabilities().getCarrierVehicles().values()) {
                rows.add("vehicle|" + c.getId() + "|" + v.getId() + "|" + v.getLinkId() + "|" + v.getType().getId()
                        + "|" + v.getEarliestStartTime() + "|" + v.getLatestEndTime());
            }
        }
        Collections.sort(rows); return List.copyOf(rows);
    }
    private record Snapshot(List<String> rows) {}
}
