package hagrid.integrated.modular;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.NetworkFactory;
import org.matsim.api.core.v01.network.Node;
import org.matsim.contrib.drt.schedule.DrtStayTask;
import org.matsim.contrib.dvrp.load.IntegerLoadType;
import org.matsim.contrib.dvrp.schedule.ScheduleTimingUpdater;
import org.matsim.core.network.NetworkUtils;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ModularStayTaskEndTimeCalculator")
class ModularStayTaskEndTimeCalculatorTest {

    private final ScheduleTimingUpdater.StayTaskEndTimeCalculator delegate =
            (vehicle, task, newBeginTime) -> 12345.0;   // sentinel: "delegated"

    private final ModularStayTaskEndTimeCalculator calc =
            new ModularStayTaskEndTimeCalculator(delegate);

    @Test
    @DisplayName("delayed freight stop keeps its intended duration (native branch would DELETE it)")
    void freightStopShiftPreservesDuration() {
        ModularFreightStopTask stop = new ModularFreightStopTask(1000.0, 1240.0, link(), 2, "t", 0);
        // upstream delay pushes begin past the old end (1240) - native STAY branch: REMOVE_STAY_TASK
        assertThat(calc.calcNewEndTime(null, stop, 1300.0)).isEqualTo(1300.0 + 240.0);
    }

    @Test
    @DisplayName("delayed swap keeps the 7-min retooling (native STOP branch would recompute via pax calculator)")
    void capacityChangeShiftPreservesRetooling() {
        ModularCapacityChangeTask swap = new ModularCapacityChangeTask(1000.0,
                1000.0 + Modular.RETOOLING_S, link(),
                new IntegerLoadType("passengers").getEmptyLoad(), "t", false);
        assertThat(calc.calcNewEndTime(null, swap, 2000.0)).isEqualTo(2000.0 + Modular.RETOOLING_S);
    }

    @Test
    @DisplayName("all other stay tasks delegate untouched")
    void delegatesEverythingElse() {
        DrtStayTask stay = new DrtStayTask(0.0, 100.0, link());
        assertThat(calc.calcNewEndTime(null, stay, 50.0)).isEqualTo(12345.0);
    }

    // --- fixture helpers (copied from ModularTest) ---

    private Link link() {
        Network net = NetworkUtils.createNetwork();
        NetworkFactory f = net.getFactory();
        Node a = f.createNode(Id.createNodeId("a"), new Coord(0, 0));
        Node b = f.createNode(Id.createNodeId("b"), new Coord(1000, 0));
        net.addNode(a);
        net.addNode(b);
        Link link = f.createLink(Id.createLinkId("l0"), a, b);
        link.setLength(1000);
        link.setFreespeed(13.9);
        link.setCapacity(1800);
        link.setNumberOfLanes(1);
        net.addLink(link);
        return link;
    }
}
