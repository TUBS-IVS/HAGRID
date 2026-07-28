package hagrid.integrated.modular;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.NetworkFactory;
import org.matsim.api.core.v01.network.Node;
import org.matsim.contrib.drt.optimizer.VehicleEntry;
import org.matsim.contrib.drt.optimizer.Waypoint;
import org.matsim.contrib.drt.schedule.DrtDriveTask;
import org.matsim.contrib.drt.schedule.DrtStayTask;
import org.matsim.contrib.drt.schedule.DrtTaskFactory;
import org.matsim.contrib.drt.schedule.DrtTaskFactoryImpl;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;
import org.matsim.contrib.dvrp.fleet.DvrpVehicleImpl;
import org.matsim.contrib.dvrp.fleet.ImmutableDvrpVehicleSpecification;
import org.matsim.contrib.dvrp.load.DvrpLoadType;
import org.matsim.contrib.dvrp.load.IntegerLoadType;
import org.matsim.contrib.dvrp.path.VrpPathWithTravelData;
import org.matsim.contrib.dvrp.path.VrpPaths;
import org.matsim.contrib.dvrp.router.TimeAsTravelDisutility;
import org.matsim.contrib.dvrp.schedule.Schedule;
import org.matsim.contrib.dvrp.schedule.StayTask;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.trafficmonitoring.FreeSpeedTravelTime;

import com.google.common.collect.ImmutableList;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * VERIFY-SOURCE: {@code VehicleEntry.EntryFactory.create(DvrpVehicle, double)} - {@code null} is
 * the sanctioned "exclude this vehicle" signal (drt-extensions' {@code DrtServiceEntryFactory} is
 * the precedent, per the task brief). {@code VehicleEntry} has no mocking framework available in
 * this module (no Mockito dependency) - the "sentinel" is a genuinely constructed instance (a
 * degenerate but valid {@link Waypoint.Start} + empty stop list), used purely for reference
 * identity ({@code isSameAs}), never dereferenced for its own fields.
 */
@DisplayName("ModularEntryFactory")
class ModularEntryFactoryTest {

    @Test
    @DisplayName("returns null for a freight-committed vehicle (D2 strict lockout), delegates otherwise")
    void lockoutViaCommitmentPredicate() {
        Network network = buildNetwork();
        Link link = network.getLinks().get(Id.createLinkId("l0"));
        VehicleEntry sentinel = fixtureSentinel(link);
        ModularEntryFactory factory = new ModularEntryFactory((vehicle, time) -> sentinel);

        DvrpVehicle idle = fixtureVehicleIdle(link);
        assertThat(factory.create(idle, 0.0)).isSameAs(sentinel);

        DvrpVehicle committed = fixtureVehicleWithSplicedTour(network, link);
        assertThat(factory.create(committed, 0.0)).isNull();

        // walk the schedule to completion -> vehicle re-enters the pax candidate set
        performAllFreightTasks(committed);
        assertThat(factory.create(committed, 0.0)).isSameAs(sentinel);
    }

    /**
     * Self-review addition (mirrors Task 3's own discriminating test on {@code
     * Modular.hasUnperformedFreightTask} itself): the standard splice shape used above is, by
     * construction, one in which the swap-back is always exactly "one task before last" for the
     * WHOLE lifetime of the excursion (nothing is ever appended after the trailing stay) - so a
     * {@code ModularEntryFactory} that reimplemented a narrow "current task or one-before-last is
     * a freight task" check INSTEAD of delegating to {@link Modular#hasUnperformedFreightTask}
     * would pass every assertion in {@link #lockoutViaCommitmentPredicate()} unchanged. This test
     * breaks that coincidence: a plain (non-Modular) repositioning drive task is inserted AFTER
     * the swap-back and BEFORE the trailing stay, while the schedule's current task is still the
     * original (far earlier, un-advanced) stay. Only a factory that scans the WHOLE schedule
     * (i.e. genuinely delegates to the wide predicate) reports this vehicle as committed.
     */
    @Test
    @DisplayName("lockout is not fooled by a schedule where the swap is neither current nor "
            + "one-before-last (guards a factory that reimplements a narrow check)")
    void lockoutNotFooledByNarrowCurrentOrOneBeforeLastCheck() {
        Link link = fixtureLink();
        VehicleEntry sentinel = fixtureSentinel(link);
        ModularEntryFactory factory = new ModularEntryFactory((vehicle, time) -> sentinel);

        DvrpVehicle vehicle = fixtureVehicleIdle(link);
        Schedule schedule = vehicle.getSchedule();
        // current stays idx 0 (the original stay) for the rest of this test
        StayTask stay = (StayTask) schedule.getCurrentTask();
        stay.setEndTime(100.0);
        schedule.addTask(new DrtDriveTask(fixturePath(link, 100.0), Modular.FREIGHT_DRIVE_TASK_TYPE));
        double arr = schedule.getTasks().get(schedule.getTaskCount() - 1).getEndTime();
        schedule.addTask(new ModularCapacityChangeTask(arr, arr + Modular.RETOOLING_S, link,
                new IntegerLoadType("passengers").getEmptyLoad(), "t", true));
        double swapEnd = arr + Modular.RETOOLING_S;
        // plain post-swap repositioning leg: a NATIVE DrtDriveTask, not a Modular freight task -
        // this displaces the swap off the one-before-last slot.
        schedule.addTask(new DrtDriveTask(fixturePath(link, swapEnd), DrtDriveTask.TYPE));
        schedule.addTask(new DrtStayTask(swapEnd, 86400.0, link));

        // current is idx 0 (original stay), one-before-last is the plain repositioning drive
        // (idx 3 of 5) - neither is a freight task, yet the swap at idx 2 is still unperformed.
        assertThat(factory.create(vehicle, 0.0)).isNull();
    }

    // --- fixture helpers ---

    private VehicleEntry fixtureSentinel(Link link) {
        DvrpLoadType loadType = new IntegerLoadType("passengers");
        Waypoint.Start start = new Waypoint.Start(null, link, 0.0, loadType.getEmptyLoad());
        return new VehicleEntry(null, start, ImmutableList.of(), new double[] {0.0}, List.of(), 0.0);
    }

    /** Vehicle with an initial 0..86400 STAY on {@code link}, STARTED; capacity 10. */
    private DvrpVehicle fixtureVehicleIdle(Link link) {
        ImmutableDvrpVehicleSpecification spec = ImmutableDvrpVehicleSpecification.newBuilder()
                .id(Id.create("drt_1", DvrpVehicle.class))
                .startLinkId(link.getId())
                .capacity(10)
                .serviceBeginTime(0.0)
                .serviceEndTime(86400.0)
                .build();
        DvrpVehicle vehicle = new DvrpVehicleImpl(spec, link);
        vehicle.getSchedule().addTask(new DrtStayTask(0.0, 86400.0, link));
        vehicle.getSchedule().nextTask();
        return vehicle;
    }

    /**
     * Reuses Task 6's real splicer ({@link ModularTourScheduler}), not a hand-rolled fake
     * schedule - the vehicle starts AT the depot link and the tour's one stop is also on the
     * depot link (Task 6's Minor 8 / Task 7's fixture convention: a zero-length stop/return leg
     * is safe and lets one link serve the whole fixture, no router network needed beyond it).
     */
    private DvrpVehicle fixtureVehicleWithSplicedTour(Network network, Link link) {
        DvrpLoadType loadType = new IntegerLoadType("passengers");
        DrtTaskFactory taskFactory = new DrtTaskFactoryImpl();
        TravelTime travelTime = new FreeSpeedTravelTime();
        TravelDisutility travelDisutility = new TimeAsTravelDisutility(travelTime);
        ModularTourScheduler scheduler = new ModularTourScheduler(network, travelTime, travelDisutility,
                taskFactory, loadType);
        DvrpVehicle vehicle = fixtureVehicleIdle(link);
        ModularFreightTour tour = new ModularFreightTour("dhl_t0", "dhl", 0, link.getId(),
                8 * 3600.0, 0.0, 21 * 3600.0,
                List.of(new ModularFreightTour.Stop(link.getId(), 240.0, 2)));
        Optional<ModularTourScheduler.ScheduledExcursion> result =
                scheduler.schedule(vehicle, tour, 8 * 3600.0);
        if (result.isEmpty()) {
            throw new IllegalStateException("fixture: splice unexpectedly rejected");
        }
        return vehicle;
    }

    private void performAllFreightTasks(DvrpVehicle vehicle) {
        Schedule schedule = vehicle.getSchedule();
        while (Modular.hasUnperformedFreightTask(schedule)) {
            schedule.nextTask();
        }
    }

    private VrpPathWithTravelData fixturePath(Link link, double departureTime) {
        return VrpPaths.createZeroLengthPath(link, departureTime, false);
    }

    private Link fixtureLink() {
        return buildNetwork().getLinks().get(Id.createLinkId("l0"));
    }

    private Network buildNetwork() {
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
        return net;
    }
}
