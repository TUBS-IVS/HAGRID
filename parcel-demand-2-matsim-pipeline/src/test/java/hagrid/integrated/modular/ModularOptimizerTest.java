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
import org.matsim.contrib.drt.passenger.DrtRequest;
import org.matsim.contrib.drt.schedule.DrtStayTask;
import org.matsim.contrib.drt.schedule.DrtStayTaskEndTimeCalculator;
import org.matsim.contrib.drt.schedule.DrtStopTask;
import org.matsim.contrib.drt.schedule.DrtTaskFactory;
import org.matsim.contrib.drt.schedule.DrtTaskFactoryImpl;
import org.matsim.contrib.drt.scheduler.DrtScheduleInquiry;
import org.matsim.contrib.drt.stops.StopTimeCalculator;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

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

    /**
     * Belt-2 CME guard (review Finding 1 / J-F8). {@code enforceIntendedDurations} iterates
     * {@code List.copyOf(schedule.getTasks())} (ModularOptimizer.java:135) precisely because the
     * timing updater it calls mid-loop can trigger the NATIVE {@code
     * DrtStayTaskEndTimeCalculator}'s {@code REMOVE_STAY_TASK} branch: a plain, non-last STAY
     * whose old end time has been overtaken by the ripple is silently REMOVED from the schedule's
     * live backing list. Iterating that live (unmodifiable-VIEW-over-the-same-ArrayList) list
     * directly would then throw {@code ConcurrentModificationException} on the very next {@code
     * Iterator.next()} - this test builds exactly that scenario.
     *
     * <p>Fixture (4 tasks, all on one link): idx0 {@code DrtStayTask(0,1000)} [current, PERFORMED
     * by this call's own {@code nextTask()} advance]; idx1 {@code ModularCapacityChangeTask
     * (1000,1015)} [CORRUPTED - 15s, should be {@code RETOOLING_S}=420 - becomes current/STARTED
     * after the advance]; idx2 {@code DrtStayTask(1015,1020)} [a plain, non-last, "delayed" STAY
     * - its old end 1020 is overtaken once idx1's fix pushes the ripple's {@code newBeginTime} to
     * 1420]; idx3 {@code DrtStayTask(1020,5000)} [trailing STAY, always last]. Uses the REAL
     * {@link ModularStayTaskEndTimeCalculator} wrapping the REAL native {@link
     * DrtStayTaskEndTimeCalculator} (not the "dumb" calculator {@link #enforceIntendedDuration()}
     * uses) - belt 1 deliberately delegates a plain {@code DrtStayTask} straight through to the
     * native calculator, and it is the native calculator's own {@code REMOVE_STAY_TASK} branch
     * this test needs.</p>
     *
     * <p><b>MUTATION EVIDENCE (task-6-report.md has both runs verbatim):</b> temporarily
     * reverting {@code List.copyOf(schedule.getTasks())} to plain {@code schedule.getTasks()} at
     * {@code ModularOptimizer.java:135} makes this test FAIL with {@code
     * ConcurrentModificationException}; restoring makes it PASS again.</p>
     */
    @Test
    @DisplayName("enforceIntendedDurations survives a downstream REMOVE_STAY_TASK ripple mid-loop "
            + "(belt-2 CME guard, review Finding 1 / J-F8)")
    void enforceIntendedDurationsSurvivesDownstreamStayRemoval() {
        DvrpVehicle vehicle = fixtureVehicleForCmeGuard();
        Schedule schedule = vehicle.getSchedule();
        Task swap = schedule.getTasks().get(1);          // corrupted: duration 15s, should be RETOOLING_S
        Task delayedStay = schedule.getTasks().get(2);   // will be REMOVED by the ripple
        Task trailingStay = schedule.getTasks().get(3);  // always-last STAY

        StopTimeCalculator neverInvokedStopCalculator = new StopTimeCalculator() {
            @Override
            public double initEndTimeForPickup(DvrpVehicle v, double beginTime, DrtRequest request) {
                throw new UnsupportedOperationException("no STOP task in this fixture");
            }

            @Override
            public double updateEndTimeForPickup(DvrpVehicle v, DrtStopTask stop, double insertionTime,
                                                  DrtRequest request) {
                throw new UnsupportedOperationException("no STOP task in this fixture");
            }

            @Override
            public double initEndTimeForDropoff(DvrpVehicle v, double beginTime, DrtRequest request) {
                throw new UnsupportedOperationException("no STOP task in this fixture");
            }

            @Override
            public double updateEndTimeForDropoff(DvrpVehicle v, DrtStopTask stop, double insertionTime,
                                                  DrtRequest request) {
                throw new UnsupportedOperationException("no STOP task in this fixture");
            }

            @Override
            public double shiftEndTime(DvrpVehicle v, DrtStopTask stop, double beginTime) {
                throw new UnsupportedOperationException("no STOP task in this fixture");
            }
        };
        ScheduleTimingUpdater.StayTaskEndTimeCalculator calculator = new ModularStayTaskEndTimeCalculator(
                new DrtStayTaskEndTimeCalculator(neverInvokedStopCalculator));
        ScheduleTimingUpdater updater = new ScheduleTimingUpdater(timer, calculator, DriveTaskUpdater.NOOP);
        RecordingDispatcher dispatcher = buildDispatcher(null);
        // callUpdateBeforeNextTask=false: this test isolates belt 2's OWN ripple call; the
        // delegate here only advances the schedule (idx0 -> PERFORMED, idx1 -> current/STARTED).
        RecordingDelegate delegate = new RecordingDelegate(null, updater, false, true);
        ModularOptimizer optimizer = new ModularOptimizer(delegate, dispatcher, updater, timer);

        timer.setTime(1000.0);
        assertThatCode(() -> optimizer.nextTask(vehicle)).doesNotThrowAnyException();

        assertThat(swap.getEndTime()).isEqualTo(swap.getBeginTime() + Modular.RETOOLING_S);
        assertThat(schedule.getTasks().contains(delayedStay)).as("delayed STAY removed by the ripple").isFalse();
        assertThat(trailingStay.getBeginTime()).isEqualTo(swap.getEndTime());
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

    /**
     * Review Minor: a schedule reaching its LAST task must still notify - this is what mints a
     * tour's final {@code COMPLETED} KPI event (fed from the swap-back's own {@code observeTaskTransition}
     * call). VERIFY-SOURCE (Task 3/7 reports, re-confirmed by the reviewer): {@code
     * ScheduleImpl.nextTask()}, when the old current was the schedule's last task, sets {@code
     * currentTask = null} and {@code status = COMPLETED} - it does NOT throw. {@code currentOrNull}
     * must therefore report {@code null} here (via the status guard, not a crash), and the
     * {@code previous != null && previous != next} check must still fire since {@code next} is
     * {@code null} while {@code previous} (the completed last task) is not.
     */
    @Test
    @DisplayName("schedule completing (last task -> COMPLETED) still notifies the dispatcher")
    void forwardsTaskTransitionOnScheduleCompletion() {
        DvrpVehicle vehicle = fixtureSingleTaskSchedule(); // one task only: also the LAST task
        Task onlyTask = vehicle.getSchedule().getTasks().get(0);

        ScheduleTimingUpdater updater = new ScheduleTimingUpdater(timer,
                (v, t, newBegin) -> newBegin + 100.0, DriveTaskUpdater.NOOP);
        RecordingDispatcher dispatcher = buildDispatcher(null);
        RecordingDelegate delegate = new RecordingDelegate(null, updater, true, true); // advances -> COMPLETES
        ModularOptimizer optimizer = new ModularOptimizer(delegate, dispatcher, updater, timer);

        timer.setTime(500.0);
        optimizer.nextTask(vehicle);

        assertThat(vehicle.getSchedule().getStatus()).isEqualTo(Schedule.ScheduleStatus.COMPLETED);
        assertThat(dispatcher.observedPrevious).containsExactly(onlyTask);
    }

    /**
     * Review Minor: the reverse boundary - a schedule that was never started (still PLANNED)
     * before this {@code nextTask} call must NOT notify, since there is no genuine "previous"
     * task that was ever current. {@code currentOrNull} returns {@code null} before the call
     * (status PLANNED), so the {@code previous != null} guard must suppress the notify even
     * though the delegate's own advance (PLANNED -&gt; STARTED) is a perfectly real schedule
     * transition.
     */
    @Test
    @DisplayName("schedule never started (PLANNED -> STARTED) does NOT notify - no real 'previous'")
    void doesNotForwardOnFirstStart() {
        DvrpVehicle vehicle = fixtureNeverStartedSchedule(); // status stays PLANNED until this call

        ScheduleTimingUpdater updater = new ScheduleTimingUpdater(timer,
                (v, t, newBegin) -> newBegin + 100.0, DriveTaskUpdater.NOOP);
        RecordingDispatcher dispatcher = buildDispatcher(null);
        RecordingDelegate delegate = new RecordingDelegate(null, updater, true, true); // advances for real
        ModularOptimizer optimizer = new ModularOptimizer(delegate, dispatcher, updater, timer);

        timer.setTime(500.0);
        optimizer.nextTask(vehicle);

        assertThat(vehicle.getSchedule().getStatus()).isEqualTo(Schedule.ScheduleStatus.STARTED);
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

    /**
     * idx0 = DrtStayTask(0,1000) [current, STARTED]; idx1 = ModularCapacityChangeTask(1000,1015)
     * [CORRUPTED - 15s, should be RETOOLING_S=420]; idx2 = DrtStayTask(1015,1020) [plain,
     * non-last, "delayed" STAY - overtaken once idx1's fix ripples a newBeginTime of 1420 past
     * its old end 1020]; idx3 = DrtStayTask(1020,5000) [trailing STAY, always last]. Everything
     * on one link (no router needed). See {@link #enforceIntendedDurationsSurvivesDownstreamStayRemoval()}.
     */
    private DvrpVehicle fixtureVehicleForCmeGuard() {
        DvrpLoadType loadType = new IntegerLoadType("passengers");
        ImmutableDvrpVehicleSpecification spec = ImmutableDvrpVehicleSpecification.newBuilder()
                .id(Id.create("drt_cme", DvrpVehicle.class))
                .startLinkId(link.getId())
                .capacity(10)
                .serviceBeginTime(0.0)
                .serviceEndTime(5000.0)
                .build();
        DvrpVehicle vehicle = new DvrpVehicleImpl(spec, link);
        Schedule schedule = vehicle.getSchedule();
        schedule.addTask(new DrtStayTask(0.0, 1000.0, link));
        schedule.nextTask(); // PLANNED -> STARTED, current = idx0
        schedule.addTask(new ModularCapacityChangeTask(1000.0, 1015.0, link,
                loadType.getEmptyLoad(), "t", false));
        schedule.addTask(new DrtStayTask(1015.0, 1020.0, link));
        schedule.addTask(new DrtStayTask(1020.0, 5000.0, link));
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

    /** A single-task schedule, STARTED: that one task is simultaneously current AND last. */
    private DvrpVehicle fixtureSingleTaskSchedule() {
        ImmutableDvrpVehicleSpecification spec = ImmutableDvrpVehicleSpecification.newBuilder()
                .id(Id.create("drt_single", DvrpVehicle.class))
                .startLinkId(link.getId())
                .capacity(10)
                .serviceBeginTime(0.0)
                .serviceEndTime(1000.0)
                .build();
        DvrpVehicle vehicle = new DvrpVehicleImpl(spec, link);
        Schedule schedule = vehicle.getSchedule();
        schedule.addTask(new DrtStayTask(0.0, 1000.0, link));
        schedule.nextTask(); // PLANNED -> STARTED, current = the only (and last) task
        return vehicle;
    }

    /** A schedule with one task added but {@code nextTask()} deliberately never called - status
     *  stays PLANNED until the test itself drives the transition via {@code optimizer.nextTask}. */
    private DvrpVehicle fixtureNeverStartedSchedule() {
        ImmutableDvrpVehicleSpecification spec = ImmutableDvrpVehicleSpecification.newBuilder()
                .id(Id.create("drt_unstarted", DvrpVehicle.class))
                .startLinkId(link.getId())
                .capacity(10)
                .serviceBeginTime(0.0)
                .serviceEndTime(1000.0)
                .build();
        DvrpVehicle vehicle = new DvrpVehicleImpl(spec, link);
        vehicle.getSchedule().addTask(new DrtStayTask(0.0, 1000.0, link));
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
