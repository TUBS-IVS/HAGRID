package hagrid.integrated.modular;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.NetworkFactory;
import org.matsim.api.core.v01.network.Node;
import org.matsim.contrib.drt.schedule.DrtDriveTask;
import org.matsim.contrib.drt.schedule.DrtStayTask;
import org.matsim.contrib.drt.schedule.DrtTaskBaseType;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;
import org.matsim.contrib.dvrp.fleet.DvrpVehicleImpl;
import org.matsim.contrib.dvrp.fleet.ImmutableDvrpVehicleSpecification;
import org.matsim.contrib.dvrp.load.DvrpLoad;
import org.matsim.contrib.dvrp.load.IntegerLoadType;
import org.matsim.contrib.dvrp.path.VrpPathWithTravelData;
import org.matsim.contrib.dvrp.path.VrpPaths;
import org.matsim.contrib.dvrp.schedule.Schedule;
import org.matsim.contrib.dvrp.schedule.StayTask;
import org.matsim.core.network.NetworkUtils;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Modular")
class ModularTest {

    @Test
    @DisplayName("task types carry the DRT base types the timing machinery branches on")
    void taskTypeBaseTypes() {
        // VERIFY-SOURCE: DrtTaskType is a record `(String name, Optional<DrtTaskBaseType> baseType)` —
        // the plain record component accessor is `baseType()`, not `getDrtBaseType()`.
        assertThat(Modular.FREIGHT_STOP_TASK_TYPE.baseType())
                .contains(DrtTaskBaseType.STAY);
        assertThat(Modular.FREIGHT_DRIVE_TASK_TYPE.baseType())
                .contains(DrtTaskBaseType.DRIVE);
    }

    @Test
    @DisplayName("freight stop task preserves intended duration + metadata")
    void freightStopTaskMetadata() {
        Link link = fixtureLink();
        ModularFreightStopTask t = new ModularFreightStopTask(100.0, 340.0, link, 2, "dhl_t0", 1);
        assertThat(t.getIntendedDuration()).isEqualTo(240.0);
        assertThat(t.getParcels()).isEqualTo(2);
        assertThat(t.getTourId()).isEqualTo("dhl_t0");
        assertThat(t.getStopIndex()).isEqualTo(1);
        assertThat(t.getTaskType()).isEqualTo(Modular.FREIGHT_STOP_TASK_TYPE);
    }

    @Test
    @DisplayName("capacity-change task: native swap + tour identity, intended duration = retooling")
    void capacityChangeTaskMetadata() {
        Link link = fixtureLink();
        DvrpLoad zero = new IntegerLoadType("passengers").getEmptyLoad();
        ModularCapacityChangeTask t = new ModularCapacityChangeTask(
                600.0, 600.0 + Modular.RETOOLING_S, link, zero, "dhl_t0", false);
        assertThat(t.getChangedCapacity()).isEqualTo(zero);
        assertThat(t.getIntendedDuration()).isEqualTo(Modular.RETOOLING_S);
        assertThat(t.isSwapBack()).isFalse();
        assertThat(t.getTourId()).isEqualTo("dhl_t0");
    }

    @Test
    @DisplayName("hasUnperformedFreightTask: true from dispatch until the swap-back is PERFORMED")
    void commitmentPredicate() {
        Link link = fixtureLink();
        DvrpVehicle vehicle = fixtureVehicle(link);        // schedule: one initial STAY 0..86400
        Schedule schedule = vehicle.getSchedule();
        schedule.nextTask();                                // PLANNED -> STARTED, current = STAY
        assertThat(Modular.hasUnperformedFreightTask(schedule)).isFalse();

        // splice a minimal freight tail: truncate stay, add freight drive + swap + trailing stay
        StayTask stay = (StayTask) schedule.getCurrentTask();
        stay.setEndTime(100.0);
        schedule.addTask(new DrtDriveTask(fixturePath(link, 100.0), Modular.FREIGHT_DRIVE_TASK_TYPE));
        double arr = schedule.getTasks().get(schedule.getTaskCount() - 1).getEndTime();
        schedule.addTask(new ModularCapacityChangeTask(arr, arr + Modular.RETOOLING_S, link,
                new IntegerLoadType("passengers").getEmptyLoad(), "t", true));
        schedule.addTask(new DrtStayTask(arr + Modular.RETOOLING_S, 86400.0, link));
        assertThat(Modular.hasUnperformedFreightTask(schedule)).isTrue();

        schedule.nextTask();  // drive running
        assertThat(Modular.hasUnperformedFreightTask(schedule)).isTrue();
        schedule.nextTask();  // swap running (drive PERFORMED)
        assertThat(Modular.hasUnperformedFreightTask(schedule)).isTrue();
        schedule.nextTask();  // trailing stay running (swap PERFORMED)
        assertThat(Modular.hasUnperformedFreightTask(schedule)).isFalse();
    }

    /**
     * Self-review guard (see task-3 process notes): the brief's {@link #commitmentPredicate()}
     * splices a MINIMAL tail (drive + swap + trailing stay) in which the swap task is always
     * structurally "one task before the last" — so a predicate narrowed to the drt-extensions
     * template's current-task/one-before-last check would happen to agree with the correct one
     * on every assertion in that test, and the test would not catch a regression to the narrow
     * check. This test breaks that coincidence: it inserts a plain (non-Modular) repositioning
     * DriveTask AFTER the swap-back and BEFORE the trailing stay (a realistic "leave the depot"
     * leg), which pushes the still-unperformed swap off the one-before-last slot while the
     * schedule's current task is still the ORIGINAL stay, far earlier still. Only a predicate
     * that scans every task from current onward (design D2, the wide check) finds the pending
     * swap here; current-task/one-before-last alone would wrongly report "not committed".
     */
    @Test
    @DisplayName("hasUnperformedFreightTask: detects a pending swap that is neither the current task nor one-before-last")
    void commitmentPredicateNotFooledByNarrowCurrentOrOneBeforeLastCheck() {
        Link link = fixtureLink();
        DvrpVehicle vehicle = fixtureVehicle(link);
        Schedule schedule = vehicle.getSchedule();
        schedule.nextTask();  // current = initial STAY (idx 0); stays current for the rest of this test

        StayTask stay = (StayTask) schedule.getCurrentTask();
        stay.setEndTime(100.0);
        schedule.addTask(new DrtDriveTask(fixturePath(link, 100.0), Modular.FREIGHT_DRIVE_TASK_TYPE));
        double arr = schedule.getTasks().get(schedule.getTaskCount() - 1).getEndTime();
        schedule.addTask(new ModularCapacityChangeTask(arr, arr + Modular.RETOOLING_S, link,
                new IntegerLoadType("passengers").getEmptyLoad(), "t", true));
        double swapEnd = arr + Modular.RETOOLING_S;
        // plain post-swap repositioning leg: a NATIVE DrtDriveTask (base DRIVE type), not a
        // Modular freight task — this is what displaces the swap from the one-before-last slot.
        schedule.addTask(new DrtDriveTask(fixturePath(link, swapEnd), DrtDriveTask.TYPE));
        schedule.addTask(new DrtStayTask(swapEnd, 86400.0, link));

        // current is still idx 0 (the original stay) and one-before-last is now the plain
        // repositioning drive (idx 3 of 5) - neither is a freight task, yet the swap at idx 2
        // is still unperformed.
        assertThat(Modular.hasUnperformedFreightTask(schedule)).isTrue();
    }

    @Test
    @DisplayName("DEFAULT_IDLE_THRESHOLD / DEFAULT_MAX_TOUR_DURATION_S pin the plan's literal values (Task 11 review Minor)")
    void defaultsPinLiteralPlanValues() {
        // Every prior test/consumer (parseScenario/HAGRIDSimulationConfig defaults included)
        // checks these constants against THEMSELVES, not against the plan's literal numbers -
        // so a silent change to either constant would flow straight into published runs with
        // every test still green. Pin the literals here, once, at the source.
        assertThat(Modular.DEFAULT_IDLE_THRESHOLD).isEqualTo(0.50);
        assertThat(Modular.DEFAULT_MAX_TOUR_DURATION_S).isEqualTo(12600);
    }

    @Test
    @DisplayName("event round-trips its attributes")
    void eventAttributes() {
        ModularTourEvent e = ModularTourEvent.dispatched(3600.0, "dhl_t0",
                Id.create("drt_1", DvrpVehicle.class), 12, 2500.0, 4200.0);
        assertThat(e.getEventType()).isEqualTo(ModularTourEvent.EVENT_TYPE);
        assertThat(e.getAttributes())
                .containsEntry("tourId", "dhl_t0")
                .containsEntry("phase", "DISPATCHED")
                .containsEntry("vehicle", "drt_1")
                .containsEntry("parcels", "12")
                .containsEntry("deadheadMeters", "2500.0")
                .containsEntry("serviceMeters", "4200.0");
    }

    // --- fixture helpers ---

    private Link fixtureLink() {
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

    /** Vehicle with an initial 0..86400 STAY on {@code link}; capacity 10 (passenger seats). */
    private DvrpVehicle fixtureVehicle(Link link) {
        ImmutableDvrpVehicleSpecification spec = ImmutableDvrpVehicleSpecification.newBuilder()
                .id(Id.create("drt_1", DvrpVehicle.class))
                .startLinkId(link.getId())
                .capacity(10)
                .serviceBeginTime(0.0)
                .serviceEndTime(86400.0)
                .build();
        DvrpVehicle vehicle = new DvrpVehicleImpl(spec, link);
        vehicle.getSchedule().addTask(new DrtStayTask(0.0, 86400.0, link));
        return vehicle;
    }

    /**
     * VERIFY-SOURCE: a same-link "path" is cheapest via {@link VrpPaths#createZeroLengthPath}
     * (departure == arrival, travelTime 0 when not diverting) — no router/TravelTime needed at
     * all, since {@code calcAndCreatePath}'s router call is only reached when fromLink != toLink.
     */
    private VrpPathWithTravelData fixturePath(Link link, double departureTime) {
        return VrpPaths.createZeroLengthPath(link, departureTime, false);
    }
}
