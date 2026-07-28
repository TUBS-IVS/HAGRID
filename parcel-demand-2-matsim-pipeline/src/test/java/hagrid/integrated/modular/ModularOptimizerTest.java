package hagrid.integrated.modular;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.NetworkFactory;
import org.matsim.api.core.v01.network.Node;
import org.matsim.contrib.drt.optimizer.DrtOptimizer;
import org.matsim.contrib.drt.schedule.DrtStayTask;
import org.matsim.contrib.drt.schedule.DrtTaskFactory;
import org.matsim.contrib.drt.schedule.DrtTaskFactoryImpl;
import org.matsim.contrib.drt.scheduler.DrtScheduleInquiry;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;
import org.matsim.contrib.dvrp.fleet.DvrpVehicleImpl;
import org.matsim.contrib.dvrp.fleet.Fleet;
import org.matsim.contrib.dvrp.fleet.ImmutableDvrpVehicleSpecification;
import org.matsim.contrib.dvrp.load.DvrpLoadType;
import org.matsim.contrib.dvrp.load.IntegerLoadType;
import org.matsim.contrib.dvrp.optimizer.Request;
import org.matsim.contrib.dvrp.router.TimeAsTravelDisutility;
import org.matsim.contrib.dvrp.schedule.DriveTask;
import org.matsim.contrib.dvrp.schedule.DriveTaskUpdater;
import org.matsim.contrib.dvrp.schedule.Schedule;
import org.matsim.contrib.dvrp.schedule.ScheduleTimingUpdater;
import org.matsim.contrib.dvrp.schedule.Task;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.mobsim.framework.MobsimTimer;
import org.matsim.core.mobsim.framework.events.MobsimBeforeSimStepEvent;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.trafficmonitoring.FreeSpeedTravelTime;

import com.google.common.collect.ImmutableMap;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * VERIFY-SOURCE (drt-2025.0-sources.jar, read directly for this task - see task-8-report.md):
 * {@code DefaultDrtOptimizer.nextTask} calls {@code scheduleTimingUpdater.updateBeforeNextTask
 * (vehicle)} itself, THEN {@code vehicle.getSchedule().nextTask()}. Because of that, the delegate
 * stub used below faithfully reproduces those exact two lines (not a trivial "just advance"
 * stub) - this is what lets {@link #enforceIntendedDuration()} actually exercise (and, via a
 * temporary reverted-structure mutation recorded in the report, discriminate) the ordering
 * decision documented on {@link ModularOptimizer#nextTask}.
 */
@DisplayName("ModularOptimizer")
class ModularOptimizerTest {

    private Network network;
    private Link link;
    private MobsimTimer timer;

    @BeforeEach
    void setUp() {
        network = buildNetwork();
        link = network.getLinks().get(Id.createLinkId("l0"));
        timer = new MobsimTimer(1.0);
    }

    @Test
    @DisplayName("simstep: dispatcher ticks BEFORE the delegate")
    void dispatchesBeforeDelegate() {
        List<String> order = new ArrayList<>();
        RecordingDispatcher dispatcher = buildDispatcher(order);
        RecordingDelegate delegate = new RecordingDelegate(order, null, false, false);
        ScheduleTimingUpdater updater = new ScheduleTimingUpdater(timer,
                (v, t, newBegin) -> newBegin, DriveTaskUpdater.NOOP);
        ModularOptimizer optimizer = new ModularOptimizer(delegate, dispatcher, updater, timer);

        timer.setTime(100.0);
        optimizer.notifyMobsimBeforeSimStep(new MobsimBeforeSimStepEvent<>(null, 100.0));

        assertThat(order).containsExactly("dispatch", "delegate");
    }

    /**
     * Belt 2 (spike §3.1). The fixture deliberately uses a "dumb" (non-Modular-aware) stay-task
     * calculator - NOT {@link ModularStayTaskEndTimeCalculator} (belt 1) - because if belt 1 were
     * wired here, the delegate's OWN single {@code updateBeforeNextTask} call would already fix
     * the corrupted swap duration on its own, and this test would pass even if belt 2's ripple
     * were deleted entirely (self-review: a test must fail if the property it names is broken).
     * It also deliberately uses a {@link DriveTaskUpdater} that is NOT {@code DriveTaskUpdater
     * .NOOP} (still a no-op behaviourally, but a DIFFERENT instance): {@code
     * ScheduleTimingUpdater.updateTimingsStartingFromCurrentTask}'s own guard re-walks the whole
     * downstream chain whenever {@code driveTaskUpdater != DriveTaskUpdater.NOOP}, REGARDLESS of
     * whether the current task's end time actually changed - this is exactly the condition that
     * would let a SECOND {@code updateBeforeNextTask} call (had {@code ModularOptimizer} made its
     * own explicit call in addition to the delegate's) silently re-run the dumb calculator over
     * the already-fixed swap task and undo belt 2's fix. See task-8-report.md for the mutation
     * evidence (temporarily reverting {@code nextTask} to that structure reproduces exactly this
     * failure).
     */
    @Test
    @DisplayName("nextTask enforces intended durations (belt 2 of spike §3.1)")
    void enforceIntendedDuration() {
        DvrpVehicle vehicle = fixtureVehicleForBelt2();
        Schedule schedule = vehicle.getSchedule();
        Task swap = schedule.getTasks().get(1);        // corrupted: duration 15s, should be RETOOLING_S
        Task downstream = schedule.getTasks().get(2);

        DriveTaskUpdater notNoop = new DriveTaskUpdater() {
            @Override
            public void updateCurrentDriveTask(DvrpVehicle v, DriveTask task) {
            }

            @Override
            public void updatePlannedDriveTask(DvrpVehicle v, DriveTask task, double beginTime) {
            }
        };
        ScheduleTimingUpdater.StayTaskEndTimeCalculator dumb = (v, t, newBegin) -> newBegin + 15.0;
        ScheduleTimingUpdater updater = new ScheduleTimingUpdater(timer, dumb, notNoop);
        RecordingDispatcher dispatcher = buildDispatcher(null);
        RecordingDelegate delegate = new RecordingDelegate(null, updater, true, true);
        ModularOptimizer optimizer = new ModularOptimizer(delegate, dispatcher, updater, timer);

        timer.setTime(1000.0);
        optimizer.nextTask(vehicle);

        assertThat(swap.getEndTime()).isEqualTo(swap.getBeginTime() + Modular.RETOOLING_S);
        assertThat(downstream.getBeginTime()).isEqualTo(swap.getEndTime());
    }

    @Test
    @DisplayName("nextTask forwards the PERFORMED task to dispatcher.observeTaskTransition")
    void forwardsTaskTransitions() {
        DvrpVehicle vehicle = fixtureSimpleTwoTaskSchedule();
        Task task0 = vehicle.getSchedule().getTasks().get(0);

        ScheduleTimingUpdater updater = new ScheduleTimingUpdater(timer,
                (v, t, newBegin) -> newBegin + 100.0, DriveTaskUpdater.NOOP);
        RecordingDispatcher dispatcher = buildDispatcher(null);
        RecordingDelegate delegate = new RecordingDelegate(null, updater, true, true); // advances for real
        ModularOptimizer optimizer = new ModularOptimizer(delegate, dispatcher, updater, timer);

        timer.setTime(500.0);
        optimizer.nextTask(vehicle);

        assertThat(dispatcher.observedPrevious).containsExactly(task0);
    }

    /**
     * Ambiguity resolution #3/self-review: the brief's own {@link #forwardsTaskTransitions()},
     * with only ONE {@code nextTask} call whose delegate DOES advance, cannot by itself
     * distinguish "forwards only on a real transition" from "forwards unconditionally on every
     * nextTask call" - both would report exactly one event. This test isolates the guard: the
     * delegate stub does NOT advance the schedule (current task is unchanged), so a correct
     * decorator must forward NOTHING, while a decorator that fired unconditionally would still
     * report one (wrong) event here.
     */
    @Test
    @DisplayName("does NOT forward when the delegate leaves the current task unchanged "
            + "(guards against firing unconditionally on every nextTask call)")
    void doesNotForwardWhenScheduleUnchanged() {
        DvrpVehicle vehicle = fixtureSimpleTwoTaskSchedule();

        ScheduleTimingUpdater updater = new ScheduleTimingUpdater(timer,
                (v, t, newBegin) -> newBegin + 100.0, DriveTaskUpdater.NOOP);
        RecordingDispatcher dispatcher = buildDispatcher(null);
        RecordingDelegate delegate = new RecordingDelegate(null, updater, true, false); // does NOT advance
        ModularOptimizer optimizer = new ModularOptimizer(delegate, dispatcher, updater, timer);

        timer.setTime(500.0);
        optimizer.nextTask(vehicle);

        assertThat(dispatcher.observedPrevious).isEmpty();
    }

    // --- fixture helpers ---

    private RecordingDispatcher buildDispatcher(List<String> order) {
        DvrpLoadType loadType = new IntegerLoadType("passengers");
        DrtTaskFactory taskFactory = new DrtTaskFactoryImpl();
        TravelTime travelTime = new FreeSpeedTravelTime();
        TravelDisutility travelDisutility = new TimeAsTravelDisutility(travelTime);
        ModularTourScheduler scheduler = new ModularTourScheduler(network, travelTime, travelDisutility,
                taskFactory, loadType);
        Fleet fleet = () -> ImmutableMap.of();
        DrtScheduleInquiry scheduleInquiry = new DrtScheduleInquiry(timer);
        return new RecordingDispatcher(order, "drt", List.of(), 0.5, fleet, scheduleInquiry,
                scheduler, network, EventsUtils.createEventsManager());
    }

    /**
     * task0 = DrtStayTask(0,1000) [current, STARTED]; task1 = ModularCapacityChangeTask
     * (1000,1015) [CORRUPTED - duration 15s, should be RETOOLING_S=420]; task2 =
     * DrtStayTask(1015,2000) [downstream]. Everything on one link (no router needed).
     */
    private DvrpVehicle fixtureVehicleForBelt2() {
        DvrpLoadType loadType = new IntegerLoadType("passengers");
        ImmutableDvrpVehicleSpecification spec = ImmutableDvrpVehicleSpecification.newBuilder()
                .id(Id.create("drt_belt2", DvrpVehicle.class))
                .startLinkId(link.getId())
                .capacity(10)
                .serviceBeginTime(0.0)
                .serviceEndTime(2000.0)
                .build();
        DvrpVehicle vehicle = new DvrpVehicleImpl(spec, link);
        Schedule schedule = vehicle.getSchedule();
        schedule.addTask(new DrtStayTask(0.0, 1000.0, link));
        schedule.nextTask(); // PLANNED -> STARTED, current = task0
        schedule.addTask(new ModularCapacityChangeTask(1000.0, 1015.0, link,
                loadType.getEmptyLoad(), "t", false));
        schedule.addTask(new DrtStayTask(1015.0, 2000.0, link));
        return vehicle;
    }

    private DvrpVehicle fixtureSimpleTwoTaskSchedule() {
        ImmutableDvrpVehicleSpecification spec = ImmutableDvrpVehicleSpecification.newBuilder()
                .id(Id.create("drt_simple", DvrpVehicle.class))
                .startLinkId(link.getId())
                .capacity(10)
                .serviceBeginTime(0.0)
                .serviceEndTime(2000.0)
                .build();
        DvrpVehicle vehicle = new DvrpVehicleImpl(spec, link);
        Schedule schedule = vehicle.getSchedule();
        schedule.addTask(new DrtStayTask(0.0, 1000.0, link));
        schedule.nextTask(); // PLANNED -> STARTED, current = task0
        schedule.addTask(new DrtStayTask(1000.0, 2000.0, link));
        return vehicle;
    }

    private Network buildNetwork() {
        Network net = NetworkUtils.createNetwork();
        NetworkFactory f = net.getFactory();
        Node a = f.createNode(Id.createNodeId("a"), new Coord(0, 0));
        Node b = f.createNode(Id.createNodeId("b"), new Coord(1000, 0));
        net.addNode(a);
        net.addNode(b);
        Link l = f.createLink(Id.createLinkId("l0"), a, b);
        l.setLength(1000);
        l.setFreespeed(13.9);
        l.setCapacity(1800);
        l.setNumberOfLanes(1);
        net.addLink(l);
        return net;
    }

    // --- recording test doubles ---

    /** Records order-of-call and every {@code previous} task the dispatcher was asked to observe. */
    private static class RecordingDispatcher extends ModularTourDispatcher {
        private final List<String> order;
        final List<Task> observedPrevious = new ArrayList<>();

        RecordingDispatcher(List<String> order, String mode, List<ModularFreightTour> tours,
                            double idleThreshold, Fleet fleet, DrtScheduleInquiry scheduleInquiry,
                            ModularTourScheduler scheduler, Network network, EventsManager events) {
            super(mode, tours, idleThreshold, fleet, scheduleInquiry, scheduler, network, events);
            this.order = order;
        }

        @Override
        public void dispatch(double now) {
            if (order != null) {
                order.add("dispatch");
            }
            super.dispatch(now);
        }

        @Override
        public void observeTaskTransition(DvrpVehicle vehicle, Task previous, double now) {
            observedPrevious.add(previous);
            super.observeTaskTransition(vehicle, previous, now);
        }
    }

    /**
     * Recording {@code DrtOptimizer} stub. When {@code callUpdateBeforeNextTask} is set, {@code
     * nextTask} faithfully reproduces {@code DefaultDrtOptimizer.nextTask}'s own two lines
     * (VERIFY-SOURCE, see class javadoc) rather than a simplified stand-in.
     */
    private static class RecordingDelegate implements DrtOptimizer {
        private final List<String> order;
        private final ScheduleTimingUpdater scheduleTimingUpdater;
        private final boolean callUpdateBeforeNextTask;
        private final boolean advanceSchedule;
        final List<DvrpVehicle> nextTaskCalls = new ArrayList<>();

        RecordingDelegate(List<String> order, ScheduleTimingUpdater scheduleTimingUpdater,
                          boolean callUpdateBeforeNextTask, boolean advanceSchedule) {
            this.order = order;
            this.scheduleTimingUpdater = scheduleTimingUpdater;
            this.callUpdateBeforeNextTask = callUpdateBeforeNextTask;
            this.advanceSchedule = advanceSchedule;
        }

        @Override
        public void requestSubmitted(Request request) {
        }

        @Override
        public void nextTask(DvrpVehicle vehicle) {
            nextTaskCalls.add(vehicle);
            if (callUpdateBeforeNextTask) {
                scheduleTimingUpdater.updateBeforeNextTask(vehicle);
            }
            if (advanceSchedule) {
                vehicle.getSchedule().nextTask();
            }
        }

        @Override
        public void notifyMobsimBeforeSimStep(@SuppressWarnings("rawtypes") MobsimBeforeSimStepEvent e) {
            if (order != null) {
                order.add("delegate");
            }
        }
    }
}
