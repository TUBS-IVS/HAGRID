package hagrid.lausitz.modular;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.NetworkFactory;
import org.matsim.api.core.v01.network.Node;
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
import org.matsim.contrib.dvrp.router.TimeAsTravelDisutility;
import org.matsim.contrib.dvrp.schedule.Schedule;
import org.matsim.contrib.dvrp.schedule.Task;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.groups.ControllerConfigGroup;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.events.ShutdownEvent;
import org.matsim.core.mobsim.framework.MobsimTimer;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.trafficmonitoring.FreeSpeedTravelTime;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * VERIFY-SOURCE (read from the dvrp/drt 2025.0 sources jars, see task-7-report.md): {@code
 * DrtScheduleInquiry(MobsimTimer)} + {@code isIdle(DvrpVehicle)} confirmed exact;
 * {@code MobsimTimer(double stepSize)} + {@code setTime(double)} confirmed exact - so the timer
 * is driven for real (not stubbed): {@link #at(double)} calls {@code timer.setTime(now)} before
 * every {@code dispatcher.dispatch(now)}, since {@code DrtScheduleInquiry.isIdle} reads
 * {@code timer.getTimeOfDay()} directly. {@code Fleet} is a one-method functional interface
 * ({@code ImmutableMap<Id<DvrpVehicle>, DvrpVehicle> getVehicles()}), so the fake fleet here is a
 * lambda over a plain {@code Map} - no framework fixture needed.
 */
@DisplayName("ModularTourDispatcher")
class ModularTourDispatcherTest {

    /**
     * n1 --vehA(200m)--> n2 --depot(50m)--> n3 --mid(50m)--> n4 --vehB(300000m)--> n5, every hop
     * paired with a reverse link so the router can approach the depot from either end. vehA's
     * stay-task link ends at n2 (50m from the depot's toNode n3); vehB's ends at n5 (~300050m
     * away) - the gap is large enough that nearest-vehicle selection cannot be ambiguous.
     * Freespeed is high (30 m/s) so even vehB's approach/return fits the 21:00 envelope from an
     * 08:00 "now".
     */
    private Network network;
    private Id<Link> depotLink;
    private Id<Link> vehALink;
    private Id<Link> vehBLink;
    private DvrpLoadType loadType;
    private ModularTourScheduler scheduler;
    private MobsimTimer timer;
    private DrtScheduleInquiry scheduleInquiry;
    private EventsManager events;
    private RecordingHandler recorder;

    private static final double T0 = 8 * 3600.0;

    @BeforeEach
    void setUp() {
        network = buildNetwork();
        depotLink = Id.createLinkId("depot");
        vehALink = Id.createLinkId("vehA");
        vehBLink = Id.createLinkId("vehB");
        loadType = new IntegerLoadType("passengers");
        DrtTaskFactory taskFactory = new DrtTaskFactoryImpl();
        TravelTime travelTime = new FreeSpeedTravelTime();
        TravelDisutility travelDisutility = new TimeAsTravelDisutility(travelTime);
        scheduler = new ModularTourScheduler(network, travelTime, travelDisutility, taskFactory, loadType);
        timer = new MobsimTimer(1.0);
        scheduleInquiry = new DrtScheduleInquiry(timer);
        events = EventsUtils.createEventsManager();
        recorder = new RecordingHandler();
        events.addHandler(recorder);
    }

    @Test
    @DisplayName("gate: dispatches only while idleShare > threshold (strict), theta=1.0 never opens")
    void gateRespectsThreshold() {
        Map<Id<DvrpVehicle>, DvrpVehicle> fleetVehicles = new LinkedHashMap<>();
        DvrpVehicle vehA = fixtureVehicle(vehALink, "vehA");
        DvrpVehicle vehB = fixtureVehicle(vehBLink, "vehB");
        fleetVehicles.put(vehA.getId(), vehA);
        fleetVehicles.put(vehB.getId(), vehB);
        Fleet fleet = fakeFleet(fleetVehicles);

        List<ModularFreightTour> tours = List.of(
                tour("dhl_t0", "dhl", 0, T0),
                tour("dhl_t1", "dhl", 1, T0));

        // fleet of 2, both idle -> share 1.0
        ModularTourDispatcher never = new ModularTourDispatcher("drt", tours, 1.0, fleet,
                scheduleInquiry, scheduler, network, events);
        at(never, T0);
        assertThat(recorded(ModularTourEvent.Phase.DISPATCHED)).isEmpty(); // 1.0 > 1.0 is false -> control arm

        ModularTourDispatcher open = new ModularTourDispatcher("drt", tours, 0.4, fleet,
                scheduleInquiry, scheduler, network, events);
        at(open, T0);
        // after the FIRST dispatch share drops to 1/2=0.5 > 0.4 -> second tour also dispatches;
        // after the second, share 0/2=0 -> stop. Assert exactly 2 DISPATCHED.
        assertThat(recorded(ModularTourEvent.Phase.DISPATCHED)).hasSize(2);
    }

    @Test
    @DisplayName("activation fires PLANNED at submissionTime, not before")
    void plannedAtSubmissionTime() {
        DvrpVehicle vehA = fixtureVehicle(vehALink, "vehA");
        Fleet fleet = fakeFleet(Map.of(vehA.getId(), vehA));
        ModularFreightTour tour = tour("dhl_t0", "dhl", 0, T0);
        ModularTourDispatcher dispatcher = new ModularTourDispatcher("drt", List.of(tour), 1.0,
                fleet, scheduleInquiry, scheduler, network, events);

        at(dispatcher, tour.submissionTime() - 1.0);
        assertThat(recorded(ModularTourEvent.Phase.PLANNED)).isEmpty();
        at(dispatcher, tour.submissionTime());
        assertThat(recorded(ModularTourEvent.Phase.PLANNED)).hasSize(1);
    }

    @Test
    @DisplayName("expiry: pending tour EXPIREs when immediate dispatch could no longer finish by latestEnd")
    void expiresWhenEnvelopePasses() {
        DvrpVehicle vehA = fixtureVehicle(vehALink, "vehA");
        Fleet fleet = fakeFleet(Map.of(vehA.getId(), vehA));
        ModularFreightTour tour = tour("dhl_t0", "dhl", 0, T0);
        ModularTourDispatcher dispatcher = new ModularTourDispatcher("drt", List.of(tour), 0.0,
                fleet, scheduleInquiry, scheduler, network, events);

        // now such that now + chainDuration > deadlineCap - derived from the fixture's OWN
        // numbers (not a hardcoded magic constant), exactly 1 s past the boundary. Since
        // 2026-09-05 the envelope is the HONEST chain duration, not
        // 2*RETOOLING + plannedDuration: see bootstrapChain.
        double lateNow = bootstrapDeadline(tour) + 1.0;

        at(dispatcher, lateNow);
        assertThat(recorded(ModularTourEvent.Phase.EXPIRED)).extracting(ModularTourEvent::getTourId)
                .containsExactly("dhl_t0");
        // an expired tour is gone: later gate-open ticks do not dispatch it (no replanning, spec §4.3)
        at(dispatcher, lateNow + 60.0);
        assertThat(recorded(ModularTourEvent.Phase.DISPATCHED)).isEmpty();
    }

    @Test
    @DisplayName("vehicle selection: nearest idle to the depot, deterministic id tie-break")
    void nearestIdleSelection() {
        DvrpVehicle vehA = fixtureVehicle(vehALink, "vehA");
        DvrpVehicle vehB = fixtureVehicle(vehBLink, "vehB");
        Fleet fleet = fakeFleet(orderedFleet(vehB, vehA)); // insertion order != distance order
        ModularFreightTour tour = tour("dhl_t0", "dhl", 0, T0);
        ModularTourDispatcher dispatcher = new ModularTourDispatcher("drt", List.of(tour), 0.0,
                fleet, scheduleInquiry, scheduler, network, events);

        at(dispatcher, T0);
        assertThat(recorded(ModularTourEvent.Phase.DISPATCHED).get(0).getVehicleId().toString())
                .isEqualTo("vehA");
    }

    @Test
    @DisplayName("equal submission: providers interleave (dhl_t0, gls_t0, ...), no alphabetical block (C7)")
    void providerInterleaving() {
        DvrpVehicle vehA = fixtureVehicle(vehALink, "vehA");
        DvrpVehicle vehB = fixtureVehicle(vehBLink, "vehB");
        Fleet fleet = fakeFleet(orderedFleet(vehA, vehB));

        // Three tours, IDENTICAL submissionTime (same plannedStart T0): dhl_t0 (idx 0),
        // dhl_t1 (idx 1), gls_t0 (idx 0). C7 sort key is (submissionTime, tourIndex, provider) -
        // NOT tourId - so the idx-0 pair (dhl_t0, gls_t0) sorts before dhl_t1, and within that
        // pair "dhl" < "gls" alphabetically. An alphabetical-tourId sort would instead produce
        // [dhl_t0, dhl_t1] here, since "dhl_t0" < "dhl_t1" < "gls_t0" as plain strings.
        ModularFreightTour dhlT0 = tour("dhl_t0", "dhl", 0, T0);
        ModularFreightTour dhlT1 = tour("dhl_t1", "dhl", 1, T0);
        ModularFreightTour glsT0 = tour("gls_t0", "gls", 0, T0);
        List<ModularFreightTour> tours = List.of(dhlT1, glsT0, dhlT0); // deliberately out of order

        // threshold sized so exactly 2 of the 2 idle vehicles get consumed (same 1.0/0.4-style
        // gate arithmetic as gateRespectsThreshold: 1.0 > 0.4, then 0.5 > 0.4, then 0.0 stops).
        ModularTourDispatcher dispatcher = new ModularTourDispatcher("drt", tours, 0.4, fleet,
                scheduleInquiry, scheduler, network, events);

        at(dispatcher, T0);
        assertThat(recorded(ModularTourEvent.Phase.DISPATCHED)).extracting(ModularTourEvent::getTourId)
                .containsExactly("dhl_t0", "gls_t0");
    }

    @Test
    @DisplayName("committed vehicles leave the idle pool (commitment predicate, not bookkeeping)")
    void committedVehicleExcluded() {
        DvrpVehicle vehA = fixtureVehicle(vehALink, "vehA");
        DvrpVehicle vehB = fixtureVehicle(vehBLink, "vehB");
        Fleet fleet = fakeFleet(orderedFleet(vehA, vehB));
        List<ModularFreightTour> tours = List.of(
                tour("dhl_t0", "dhl", 0, T0),
                tour("dhl_t1", "dhl", 1, T0));
        // theta=0.0: dispatch whenever ANY idle vehicle remains, so both tours are attempted in
        // this one tick - the only near vehicle (vehA) must not receive both.
        ModularTourDispatcher dispatcher = new ModularTourDispatcher("drt", tours, 0.0, fleet,
                scheduleInquiry, scheduler, network, events);

        at(dispatcher, T0);
        List<ModularTourEvent> dispatched = recorded(ModularTourEvent.Phase.DISPATCHED);
        assertThat(dispatched).hasSize(2);
        // never double-booked: the two DISPATCHED events name two DISTINCT vehicles.
        assertThat(dispatched).extracting(e -> e.getVehicleId().toString())
                .containsExactlyInAnyOrder("vehA", "vehB");
    }

    /**
     * Self-review (task brief's prescribed question): "would committedVehicleExcluded fail if the
     * idle filter dropped the commitment predicate and relied on isIdle alone?" For a schedule
     * built by the real splicer, the answer turns out to be NO by construction - appending the
     * excursion chain after the running trailing STAY always leaves the (unadvanced) current
     * task's index short of the new last-task index, so native isIdle already returns false for
     * any freshly-spliced vehicle, for entirely incidental reasons (ScheduleImpl's monotonic task
     * indices), not because of Modular.hasUnperformedFreightTask. This test isolates the
     * predicate itself: "vehTrap" is hand-built (bypassing the splicer) so its schedule's ONLY
     * task is an unperformed {@link ModularFreightStopTask} - which IS the current task AND the
     * last task, so native {@code DrtScheduleInquiry.isIdle} says true (STAY-base-typed, current
     * == last), while {@code Modular.hasUnperformedFreightTask} correctly still says true (that
     * very task is a freight task, not yet PERFORMED). vehTrap sits nearer the depot than the
     * genuinely-idle "vehFar", so an implementation relying on isIdle alone would pick vehTrap -
     * and {@link ModularTourScheduler#schedule} would NOT catch this either, since
     * ModularFreightStopTask extends DefaultStayTask and satisfies the splicer's own
     * "must end with STAY" / "current == trailing STAY" precondition, i.e. it would silently
     * splice a second excursion onto a vehicle still mid-delivery of the first.
     */
    @Test
    @DisplayName("committed vehicles excluded via the predicate itself, not merely isIdle (self-review)")
    void committedVehicleExcludedByPredicateNotIsIdleAlone() {
        Link vehALinkObj = network.getLinks().get(vehALink);
        DvrpVehicle vehTrap = trapVehicle(vehALinkObj, "vehTrap");
        DvrpVehicle vehFar = fixtureVehicle(vehBLink, "vehFar");
        Fleet fleet = fakeFleet(orderedFleet(vehTrap, vehFar));
        ModularFreightTour tour = tour("dhl_t0", "dhl", 0, T0);
        ModularTourDispatcher dispatcher = new ModularTourDispatcher("drt", List.of(tour), 0.0,
                fleet, scheduleInquiry, scheduler, network, events);

        timer.setTime(T0);
        assertThat(scheduleInquiry.isIdle(vehTrap)).isTrue();      // native check is fooled
        assertThat(Modular.hasUnperformedFreightTask(vehTrap.getSchedule())).isTrue(); // predicate is not

        at(dispatcher, T0);
        List<ModularTourEvent> dispatched = recorded(ModularTourEvent.Phase.DISPATCHED);
        assertThat(dispatched).hasSize(1);
        assertThat(dispatched.get(0).getVehicleId().toString()).isEqualTo("vehFar");
    }

    /**
     * Review Finding 4: the reviewer traced by inspection that an empty idle pool, an empty
     * fleet, and a zero-tour list are all handled safely (the {@code !idle.isEmpty()}
     * short-circuit in the gate's while-loop avoids ever evaluating
     * {@code idle.size() / fleetSize}), but nothing exercised it. This is the state a real run
     * spends its afternoon in once the morning surge has consumed the fleet: every vehicle is
     * already committed to an in-progress freight excursion, spliced here via the REAL scheduler
     * (not a hand-built fixture), so {@code DrtScheduleInquiry.isIdle} genuinely returns false for
     * both, for the ordinary reason (appended tasks push current off the last-task index).
     */
    @Test
    @DisplayName("degenerate fleet: every vehicle already committed to freight - no dispatch, no exception (review finding 4)")
    void allVehiclesCommittedNoDispatch() {
        DvrpVehicle vehA = fixtureVehicle(vehALink, "vehA");
        DvrpVehicle vehB = fixtureVehicle(vehBLink, "vehB");
        assertThat(scheduler.schedule(vehA, tour("committer_a", "dhl", 0, T0), T0)).isPresent();
        assertThat(scheduler.schedule(vehB, tour("committer_b", "dhl", 1, T0), T0)).isPresent();
        Fleet fleet = fakeFleet(orderedFleet(vehA, vehB));

        ModularFreightTour pendingTour = tour("dhl_t9", "dhl", 9, T0);
        ModularTourDispatcher dispatcher = new ModularTourDispatcher("drt", List.of(pendingTour), 0.0,
                fleet, scheduleInquiry, scheduler, network, events);

        assertThatCode(() -> at(dispatcher, T0)).doesNotThrowAnyException();
        assertThat(recorded(ModularTourEvent.Phase.DISPATCHED)).isEmpty();
    }

    /**
     * Review Finding 4: the deadhead/service split is pinned at both ends but not at the joint.
     * {@code ModularTourSchedulerTest} pins the {@code ScheduledExcursion} record, {@code
     * ModularTest} pins the event factory's attributes and {@code ModularKpiHandlerTest} pins the
     * CSV from a hand-built event — but NOTHING crossed the two adjacent {@code double} arguments
     * at the dispatcher's own {@code ModularTourEvent.dispatched(...)} call. Swap them and every
     * published deadhead and service kilometre is wrong while the whole suite stays green.
     *
     * <p>Expectations come from the scheduler run on a TWIN vehicle rather than from hardcoded
     * router arithmetic, so the test pins the PASS-THROUGH (the thing that can transpose) instead
     * of re-deriving MATSim's routing — which would make it fail on an unrelated routing change
     * and, worse, tempt a future reader to "fix" it by copying whatever the code now produces.
     * The fixture is deliberately asymmetric (stop on `mid`, not on the depot link, so neither
     * number is 0 and the two differ); the inequality is asserted first, because a symmetric
     * fixture would make the whole test blind to the transposition it exists to catch.
     */
    @Test
    @DisplayName("dispatch event carries deadhead and service the right way round (review finding 4)")
    void dispatchedEventDoesNotTransposeDeadheadAndService() {
        DvrpVehicle vehicle = fixtureVehicle(vehALink, "vehA");
        DvrpVehicle twin = fixtureVehicle(vehALink, "vehA");   // same id, same link, own schedule
        ModularFreightTour tour = tourViaMid("dhl_t0", 0);

        ModularTourScheduler.ScheduledExcursion expected =
                scheduler.schedule(twin, tour, T0).orElseThrow();
        assertThat(expected.deadheadMeters())
                .as("fixture must be asymmetric or this test cannot see a transposition")
                .isNotEqualTo(expected.serviceMeters());
        assertThat(expected.deadheadMeters()).isGreaterThan(0.0);
        assertThat(expected.serviceMeters()).isGreaterThan(0.0);

        Fleet fleet = fakeFleet(Map.of(vehicle.getId(), vehicle));
        ModularTourDispatcher dispatcher = new ModularTourDispatcher("drt", List.of(tour), 0.0,
                fleet, scheduleInquiry, scheduler, network, events);

        at(dispatcher, T0);

        List<ModularTourEvent> dispatched = recorded(ModularTourEvent.Phase.DISPATCHED);
        assertThat(dispatched).hasSize(1);
        assertThat(dispatched.get(0).getDeadheadMeters()).as("deadhead (approach + return)")
                .isEqualTo(expected.deadheadMeters());
        assertThat(dispatched.get(0).getServiceMeters()).as("service (inter-stop legs)")
                .isEqualTo(expected.serviceMeters());
        // Task 1: plannedDurationS is jsprit's car-network figure (tour.plannedDuration()),
        // routedDurationS is the splicer's own routedDurationS (completion - dispatch "now") -
        // pinned against the SAME twin-scheduled excursion as deadhead/service above, and against
        // each other's inequality, since a transposition here would be just as invisible to any
        // single-sided check as the deadhead/service one this test already exists to catch.
        assertThat(dispatched.get(0).getPlannedDurationS()).as("plannedDurationS (jsprit car-network)")
                .isEqualTo(tour.plannedDuration());
        assertThat(dispatched.get(0).getRoutedDurationS()).as("routedDurationS (splicer, DRT-routed)")
                .isEqualTo(expected.routedDurationS());
        assertThat(dispatched.get(0).getRoutedDurationS())
                .as("routed (DRT, includes the approach leg) must exceed jsprit's car-network figure")
                .isGreaterThan(dispatched.get(0).getPlannedDurationS());
    }

    /**
     * Review Finding 3. When the splicer returns {@code Optional.empty()} the tour stays pending
     * and NOTHING used to be recorded — no event, no log, no counter — so the tour later tripped
     * the expiry check and was published as {@code tours_expired_pending}, i.e. as "the gate was
     * too tight" when the truth was "the tour never fit".
     *
     * <p>The fixture forces exactly the divergence that makes the two envelopes different tests.
     * {@code plannedDuration} is jsprit's car-network figure and stays small (600 s), so the
     * dispatcher's expiry check ({@code now + 2*RETOOLING + plannedDuration <= latestEnd}) PASSES
     * with room to spare. The only idle vehicle, however, is 300 km from the depot, so the
     * splicer's DRT-routed completion — which includes the approach leg the expiry check knows
     * nothing about — lands about 11000 s out and blows the same {@code latestEnd}. Asserting the
     * tour is NOT expired is what separates the new counter from the bucket it used to vanish
     * into.
     */
    @Test
    @DisplayName("splicer rejection is recorded as SPLICE_REJECTED, once, and is not an expiry (review finding 3)")
    void spliceRejectionIsRecordedOncePerTour() {
        DvrpVehicle vehB = fixtureVehicle(vehBLink, "vehB");   // ~300 km from the depot
        Fleet fleet = fakeFleet(Map.of(vehB.getId(), vehB));
        // latestEnd leaves the expiry check comfortable (28800 + 840 + 600 = 30240 < 30800) but
        // is far short of the ~11000 s the real routed excursion needs.
        ModularFreightTour tour = new ModularFreightTour("dhl_t0", "dhl", 0, depotLink,
                T0, /*plannedDuration*/ 600.0, /*latestEnd*/ T0 + 2000.0,
                List.of(new ModularFreightTour.Stop(depotLink, 240.0, 2)));
        ModularTourDispatcher dispatcher = new ModularTourDispatcher("drt", List.of(tour), 0.0,
                fleet, scheduleInquiry, scheduler, network, events);

        // sanity: the splicer really does refuse this pairing (otherwise the test proves nothing)
        assertThat(scheduler.schedule(fixtureVehicle(vehBLink, "vehB"), tour, T0)).isEmpty();

        at(dispatcher, T0);

        assertThat(recorded(ModularTourEvent.Phase.DISPATCHED)).isEmpty();
        assertThat(recorded(ModularTourEvent.Phase.EXPIRED))
                .as("the pending-expiry envelope PASSES here - this is the splicer's rejection,"
                        + " and conflating the two is the whole point of the finding")
                .isEmpty();
        List<ModularTourEvent> rejected = recorded(ModularTourEvent.Phase.SPLICE_REJECTED);
        assertThat(rejected).hasSize(1);
        assertThat(rejected.get(0).getTourId()).isEqualTo("dhl_t0");
        assertThat(rejected.get(0).getVehicleId().toString())
                .as("names the CANDIDATE vehicle the envelope was tested against").isEqualTo("vehB");
        assertThat(rejected.get(0).getParcels()).isEqualTo(2);

        // Retried every simstep the gate is open, but reported ONCE: in the theta=0 arm a
        // per-attempt event would write tens of thousands of identical rows into the events file.
        at(dispatcher, T0 + 1.0);
        at(dispatcher, T0 + 2.0);
        assertThat(recorded(ModularTourEvent.Phase.SPLICE_REJECTED)).hasSize(1);
    }

    @Test
    @DisplayName("observeTaskTransition: performed stop -> STOP_SERVED; swap-back -> SWAP_DONE + COMPLETED")
    void taskTransitionEvents() {
        DvrpVehicle vehicle = fixtureVehicle(vehALink, "vehA");
        ModularFreightTour tour = new ModularFreightTour("dhl_t0", "dhl", 0, depotLink,
                T0, 600.0, 21 * 3600.0, List.of(
                new ModularFreightTour.Stop(depotLink, 240.0, 2),
                new ModularFreightTour.Stop(depotLink, 360.0, 3)));
        assertThat(scheduler.schedule(vehicle, tour, T0)).isPresent();

        Fleet fleet = fakeFleet(Map.of(vehicle.getId(), vehicle));
        ModularTourDispatcher dispatcher = new ModularTourDispatcher("drt", List.of(), 0.5, fleet,
                scheduleInquiry, scheduler, network, events);

        Schedule schedule = vehicle.getSchedule();
        double now = T0;
        while (schedule.getStatus() == Schedule.ScheduleStatus.STARTED) {
            Task previous = schedule.getCurrentTask();
            schedule.nextTask();
            dispatcher.observeTaskTransition(vehicle, previous, now);
            now += 1.0;
        }

        assertThat(recorded(ModularTourEvent.Phase.SWAP_DONE)).hasSize(2);
        List<ModularTourEvent> stopServed = recorded(ModularTourEvent.Phase.STOP_SERVED);
        assertThat(stopServed).extracting(ModularTourEvent::getParcels).containsExactly(2, 3);
        assertThat(recorded(ModularTourEvent.Phase.COMPLETED)).hasSize(1);
    }

    // --- two-wave gate (2026-08-30) ------------------------------------------

    @Test
    @DisplayName("windows: parse, half-open containment, blank = always open, malformed rejected")
    void windowParsing() {
        assertThat(Modular.parseWindows("")).isEmpty();
        assertThat(Modular.parseWindows("   ")).isEmpty();
        assertThat(Modular.parseWindows(null)).isEmpty();

        List<Modular.DispatchWindow> w = Modular.parseWindows("08:00-11:00, 16:00-17:00");
        assertThat(w).hasSize(2);
        assertThat(w.get(0).startS()).isEqualTo(8 * 3600.0);
        assertThat(w.get(1).endS()).isEqualTo(17 * 3600.0);
        // half-open [start, end): the end second belongs to the NEXT window, not this one
        assertThat(w.get(0).contains(8 * 3600.0)).isTrue();
        assertThat(w.get(0).contains(11 * 3600.0 - 1)).isTrue();
        assertThat(w.get(0).contains(11 * 3600.0)).isFalse();

        assertThatThrownBy(() -> Modular.parseWindows("08:00")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Modular.parseWindows("08:00-07:00")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Modular.parseWindows("ab:cd-11:00")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("cap: at most maxConcurrentFreight vehicles hold freight at once; 0 = unlimited")
    void concurrencyCapLimitsDispatch() {
        Map<Id<DvrpVehicle>, DvrpVehicle> fleetVehicles = new LinkedHashMap<>();
        DvrpVehicle vehA = fixtureVehicle(vehALink, "vehA");
        DvrpVehicle vehB = fixtureVehicle(vehBLink, "vehB");
        fleetVehicles.put(vehA.getId(), vehA);
        fleetVehicles.put(vehB.getId(), vehB);
        Fleet fleet = fakeFleet(fleetVehicles);
        List<ModularFreightTour> tours = List.of(
                tour("dhl_t0", "dhl", 0, T0), tour("dhl_t1", "dhl", 1, T0));

        // theta=0.4 alone would dispatch BOTH (pinned by gateRespectsThreshold) -- the cap is
        // therefore the only thing that can hold the second one back.
        ModularTourDispatcher capped = new ModularTourDispatcher("drt", tours, 0.4, 1, List.of(),
                fleet, scheduleInquiry, scheduler, network, events);
        at(capped, T0);
        assertThat(recorded(ModularTourEvent.Phase.DISPATCHED)).hasSize(1);
    }

    @Test
    @DisplayName("cap: 0 means unlimited, i.e. identical to the theta-only constructor")
    void capZeroIsUnlimited() {
        Map<Id<DvrpVehicle>, DvrpVehicle> fleetVehicles = new LinkedHashMap<>();
        DvrpVehicle vehA = fixtureVehicle(vehALink, "vehA");
        DvrpVehicle vehB = fixtureVehicle(vehBLink, "vehB");
        fleetVehicles.put(vehA.getId(), vehA);
        fleetVehicles.put(vehB.getId(), vehB);
        Fleet fleet = fakeFleet(fleetVehicles);
        List<ModularFreightTour> tours = List.of(
                tour("dhl_t0", "dhl", 0, T0), tour("dhl_t1", "dhl", 1, T0));

        ModularTourDispatcher open = new ModularTourDispatcher("drt", tours, 0.4, 0, List.of(),
                fleet, scheduleInquiry, scheduler, network, events);
        at(open, T0);
        assertThat(recorded(ModularTourEvent.Phase.DISPATCHED)).hasSize(2);
    }

    @Test
    @DisplayName("windows: shut = no dispatch; the same tour goes out once the window opens")
    void windowGatesDispatch() {
        DvrpVehicle vehA = fixtureVehicle(vehALink, "vehA");
        Fleet fleet = fakeFleet(Map.of(vehA.getId(), vehA));
        List<ModularFreightTour> tours = List.of(tour("dhl_t0", "dhl", 0, T0));

        ModularTourDispatcher d = new ModularTourDispatcher("drt", tours, 0.0, 0,
                Modular.parseWindows("09:00-10:00"), fleet, scheduleInquiry, scheduler, network, events);

        at(d, T0);                       // 08:00 -- outside the window
        assertThat(recorded(ModularTourEvent.Phase.DISPATCHED)).isEmpty();
        assertThat(recorded(ModularTourEvent.Phase.PLANNED)).hasSize(1);   // still activated

        at(d, 9 * 3600.0);               // inside
        assertThat(recorded(ModularTourEvent.Phase.DISPATCHED)).hasSize(1);
    }

    @Test
    @DisplayName("windows: a shut window still EXPIREs -- holding is never silent")
    void shutWindowStillExpires() {
        DvrpVehicle vehA = fixtureVehicle(vehALink, "vehA");
        Fleet fleet = fakeFleet(Map.of(vehA.getId(), vehA));
        ModularFreightTour t = tour("dhl_t0", "dhl", 0, T0);
        // window that is shut at the moment the envelope lapses
        ModularTourDispatcher d = new ModularTourDispatcher("drt", List.of(t), 0.0, 0,
                Modular.parseWindows("08:00-09:00"), fleet, scheduleInquiry, scheduler, network, events);

        double lateNow = bootstrapDeadline(t) + 1.0;
        at(d, lateNow);
        // if the window check ran BEFORE the expiry sweep this would be empty and the tour would
        // vanish without any event -- exactly the silent drop ModularTourEvent.EXPIRED exists for
        assertThat(recorded(ModularTourEvent.Phase.EXPIRED)).extracting(ModularTourEvent::getTourId)
                .containsExactly("dhl_t0");
    }

    // --- helpers ---

    // ------------------------------------------------- Task 1 guard (plan 2026-09-04)
    // Characterisation of the CURRENT gate, installed BEFORE the self-referential capacity
    // budget touches dispatch(). The budget adds one conjunct to the while-condition; these
    // tests are what prove that conjunct is inert while budgetMode is off.
    //
    // They pin the decision SEQUENCE -- which tour, to which vehicle, in which simstep -- not
    // just counts. A budget that silently reordered dispatch (say by refusing tour 0 for a
    // later bin and letting tour 1 through in its place) would leave every count intact and
    // every existing test green; only the sequence catches it.
    //
    // Plan: docs/superpowers/plans/2026-09-04-selfreferential-capacity-budget.md, Task 1.

    @Test
    @DisplayName("guard: tour->vehicle assignment sequence is pinned (theta=0, 3 idle vehicles)")
    void guardDispatchSequencePinned() {
        DvrpVehicle a1 = fixtureVehicle(vehALink, "vehA1");
        DvrpVehicle a2 = fixtureVehicle(vehALink, "vehA2");
        DvrpVehicle a3 = fixtureVehicle(vehALink, "vehA3");
        Fleet fleet = fakeFleet(orderedFleet(a1, a2, a3));
        List<ModularFreightTour> tours = List.of(
                tour("dhl_t0", "dhl", 0, T0),
                tour("dhl_t1", "dhl", 1, T0),
                tour("dhl_t2", "dhl", 2, T0));

        ModularTourDispatcher d = new ModularTourDispatcher("drt", tours, 0.0, 0, List.of(),
                fleet, scheduleInquiry, scheduler, network, events);
        at(d, T0);

        assertThat(dispatchSequence())
                .containsExactly("28800|dhl_t0|vehA1", "28800|dhl_t1|vehA2", "28800|dhl_t2|vehA3");
    }

    @Test
    @DisplayName("guard: theta arithmetic is pinned -- 3 vehicles at theta=0.4 dispatch exactly 2")
    void guardThetaBoundaryPinned() {
        DvrpVehicle a1 = fixtureVehicle(vehALink, "vehA1");
        DvrpVehicle a2 = fixtureVehicle(vehALink, "vehA2");
        DvrpVehicle a3 = fixtureVehicle(vehALink, "vehA3");
        Fleet fleet = fakeFleet(orderedFleet(a1, a2, a3));
        List<ModularFreightTour> tours = List.of(
                tour("dhl_t0", "dhl", 0, T0),
                tour("dhl_t1", "dhl", 1, T0),
                tour("dhl_t2", "dhl", 2, T0));

        ModularTourDispatcher d = new ModularTourDispatcher("drt", tours, 0.4, 0, List.of(),
                fleet, scheduleInquiry, scheduler, network, events);
        at(d, T0);
        // 3/3 > 0.4 -> dispatch, 2/3 > 0.4 -> dispatch, 1/3 is NOT > 0.4 -> stop. The third tour
        // stays pending, it is not expired and not rejected.
        assertThat(dispatchSequence())
                .containsExactly("28800|dhl_t0|vehA1", "28800|dhl_t1|vehA2");
        assertThat(recorded(ModularTourEvent.Phase.EXPIRED)).isEmpty();
        assertThat(recorded(ModularTourEvent.Phase.SPLICE_REJECTED)).isEmpty();

        // and it stays held on the next simstep for the same reason, rather than leaking out
        at(d, T0 + 1);
        assertThat(recorded(ModularTourEvent.Phase.DISPATCHED)).hasSize(2);
    }

    @Test
    @DisplayName("guard: equidistant vehicles resolve by id, smallest first")
    void guardTieBreakByIdPinned() {
        // deliberately inserted in DESCENDING id order: if nearestToDepot ever stopped sorting,
        // this would hand the tour to vehA3 and the assertion would catch it.
        DvrpVehicle a3 = fixtureVehicle(vehALink, "vehA3");
        DvrpVehicle a1 = fixtureVehicle(vehALink, "vehA1");
        Fleet fleet = fakeFleet(orderedFleet(a3, a1));
        List<ModularFreightTour> tours = List.of(tour("dhl_t0", "dhl", 0, T0));

        ModularTourDispatcher d = new ModularTourDispatcher("drt", tours, 0.0, 0, List.of(),
                fleet, scheduleInquiry, scheduler, network, events);
        at(d, T0);
        assertThat(dispatchSequence()).containsExactly("28800|dhl_t0|vehA1");
    }

    // ------------------------------------- Task 3: look-ahead capacity budget (plan 2026-09-04)
    // The budget is an ADDITIONAL conjunct, never a replacement: every test below leaves theta,
    // the concurrency cap and the windows exactly as the Task 1 guards pinned them, and the
    // budget-off case (profile == null) re-runs those guard sequences verbatim.
    //
    // Bin arithmetic used throughout, derived from the fixture and asserted where it matters:
    // T0 = 28800 s -> bin 32; a tour with plannedDuration 600 s binds the vehicle until
    // T0 + 2*RETOOLING_S + 600 = 30240 s -> bin 33. So the standard fixture tour spans exactly
    // two bins, which is the smallest fixture in which look-ahead is distinguishable at all.

    /**
     * THE DISCRIMINATION TEST (plan Task 3). A tour is refused although the CURRENT bin has room,
     * purely because a LATER bin it would span does not.
     *
     * <p><b>Why this cannot pass against a scalar {@code maxConcurrentFreight}.</b> The two halves
     * of this test are identical in every quantity a scalar cap can observe: same fleet, same
     * single idle vehicle, same tour, same theta, same simstep, and in both halves the number of
     * vehicles currently committed to freight is 0. The ONLY difference is the passenger load in
     * bin 33 — a bin that begins 900 s in the future. A concurrency cap reads the present
     * committed count and nothing else, so it takes the same value in both halves and cannot
     * separate them: any cap of 1 or more admits both, and 0 means "unlimited" in this codebase,
     * so it admits both as well. There is no scalar cap that refuses (a) while admitting (b).
     * That is the whole mechanism the budget exists to add, and half (b) is the positive control
     * that keeps this test from passing for the trivial reason "nothing is ever dispatched".
     */
    @Test
    @DisplayName("budget: refuses a tour because a LATER bin is full, although the current bin is free")
    void budgetRefusesForALaterBinAlthoughTheCurrentBinIsFree() {
        DvrpVehicle a1 = fixtureVehicle(vehALink, "vehA1");
        Fleet fleet = fakeFleet(orderedFleet(a1));
        List<ModularFreightTour> tours = List.of(tour("dhl_t0", "dhl", 0, T0));
        assertThat(PassengerLoadProfile.binOf(T0)).isEqualTo(32);
        assertThat(PassengerLoadProfile.binOf(T0 + 2 * Modular.RETOOLING_S + 600.0)).isEqualTo(33);

        // (a) the later bin is full -> refused, even though right now the whole fleet is free
        PassengerLoadProfile blocking = oneIteration(Map.of(32, 0, 33, 1));
        assertThat(blocking.budget(32, 1, 0.0)).as("current bin has room").isEqualTo(1.0);
        assertThat(blocking.budget(33, 1, 0.0)).as("the later bin does not").isEqualTo(0.0);
        ModularTourDispatcher refusing = new ModularTourDispatcher("drt", tours, 0.0, 0, List.of(),
                blocking, 0.0, fleet, scheduleInquiry, scheduler, network, events);
        at(refusing, T0);
        assertThat(dispatchSequence()).isEmpty();
        assertThat(refusing.budgetBlockedDispatches()).isEqualTo(1);
        assertThat(refusing.budgetOverridesExpiry()).isZero();
        // held, not lost: the budget must not masquerade as either of the two failure buckets
        assertThat(recorded(ModularTourEvent.Phase.EXPIRED)).isEmpty();
        assertThat(recorded(ModularTourEvent.Phase.SPLICE_REJECTED)).isEmpty();

        // (b) positive control: same everything, only bin 33's passenger load differs -> dispatched
        recorder.events.clear();
        PassengerLoadProfile permissive = oneIteration(Map.of(32, 0, 33, 0));
        ModularTourDispatcher admitting = new ModularTourDispatcher("drt", tours, 0.0, 0, List.of(),
                permissive, 0.0, fleet, scheduleInquiry, scheduler, network, events);
        at(admitting, T0);
        assertThat(dispatchSequence()).containsExactly("28800|dhl_t0|vehA1");
        assertThat(admitting.budgetBlockedDispatches()).isZero();
    }

    /**
     * Plan Task 1's still-open item: the three guards prove "no budget code at all" is inert; this
     * proves the explicit {@code budgetMode=off} PATH is inert too, running the same three
     * sequences through the new 12-argument constructor with {@code profile == null}.
     */
    @Test
    @DisplayName("budget off (profile == null) reproduces the Task 1 guard sequences exactly")
    void budgetOffReproducesGuardSequences() {
        // (a) guardDispatchSequencePinned
        Fleet f1 = fakeFleet(orderedFleet(fixtureVehicle(vehALink, "vehA1"),
                fixtureVehicle(vehALink, "vehA2"), fixtureVehicle(vehALink, "vehA3")));
        List<ModularFreightTour> three = List.of(
                tour("dhl_t0", "dhl", 0, T0),
                tour("dhl_t1", "dhl", 1, T0),
                tour("dhl_t2", "dhl", 2, T0));
        ModularTourDispatcher a = new ModularTourDispatcher("drt", three, 0.0, 0, List.of(),
                null, 0.0, f1, scheduleInquiry, scheduler, network, events);
        at(a, T0);
        assertThat(dispatchSequence())
                .containsExactly("28800|dhl_t0|vehA1", "28800|dhl_t1|vehA2", "28800|dhl_t2|vehA3");
        assertThat(a.budgetBlockedDispatches()).isZero();
        assertThat(a.budgetOverridesExpiry()).isZero();

        // (b) guardThetaBoundaryPinned
        recorder.events.clear();
        Fleet f2 = fakeFleet(orderedFleet(fixtureVehicle(vehALink, "vehA1"),
                fixtureVehicle(vehALink, "vehA2"), fixtureVehicle(vehALink, "vehA3")));
        ModularTourDispatcher b = new ModularTourDispatcher("drt", three, 0.4, 0, List.of(),
                null, 0.0, f2, scheduleInquiry, scheduler, network, events);
        at(b, T0);
        assertThat(dispatchSequence()).containsExactly("28800|dhl_t0|vehA1", "28800|dhl_t1|vehA2");
        assertThat(recorded(ModularTourEvent.Phase.EXPIRED)).isEmpty();
        assertThat(recorded(ModularTourEvent.Phase.SPLICE_REJECTED)).isEmpty();
        at(b, T0 + 1);
        assertThat(recorded(ModularTourEvent.Phase.DISPATCHED)).hasSize(2);

        // (c) guardTieBreakByIdPinned
        recorder.events.clear();
        Fleet f3 = fakeFleet(orderedFleet(fixtureVehicle(vehALink, "vehA3"),
                fixtureVehicle(vehALink, "vehA1")));
        ModularTourDispatcher c = new ModularTourDispatcher("drt",
                List.of(tour("dhl_t0", "dhl", 0, T0)), 0.0, 0, List.of(),
                null, 0.0, f3, scheduleInquiry, scheduler, network, events);
        at(c, T0);
        assertThat(dispatchSequence()).containsExactly("28800|dhl_t0|vehA1");
    }

    /**
     * Iteration 0 has no history, so {@code budget()} is unbounded everywhere and the gate falls
     * back to theta alone — the documented bootstrap. The second assertion matters as much as the
     * first: iteration 0 must still MEASURE, otherwise iteration 1 has nothing to constrain with.
     */
    @Test
    @DisplayName("budget: a bootstrap profile (no completed iteration) blocks nothing but still measures")
    void bootstrapProfileBlocksNothing() {
        PassengerLoadProfile bootstrap = new PassengerLoadProfile(5);
        assertThat(bootstrap.isBootstrapped()).isFalse();
        assertThat(bootstrap.budget(32, 3, 0.15)).isEqualTo(Double.POSITIVE_INFINITY);

        Fleet fleet = fakeFleet(orderedFleet(fixtureVehicle(vehALink, "vehA1"),
                fixtureVehicle(vehALink, "vehA2"), fixtureVehicle(vehALink, "vehA3")));
        List<ModularFreightTour> tours = List.of(
                tour("dhl_t0", "dhl", 0, T0),
                tour("dhl_t1", "dhl", 1, T0),
                tour("dhl_t2", "dhl", 2, T0));
        ModularTourDispatcher d = new ModularTourDispatcher("drt", tours, 0.0, 0, List.of(),
                bootstrap, 0.15, fleet, scheduleInquiry, scheduler, network, events);
        at(d, T0);

        assertThat(dispatchSequence())
                .containsExactly("28800|dhl_t0|vehA1", "28800|dhl_t1|vehA2", "28800|dhl_t2|vehA3");
        assertThat(d.budgetBlockedDispatches()).isZero();
        assertThat(bootstrap.currentIterationObservations(PassengerLoadProfile.binOf(T0)))
                .as("iteration 0 cannot constrain, but it must still observe").isEqualTo(1);
    }

    /**
     * Expiry beats the budget. The tour is budget-blocked at both simsteps; the difference is only
     * that at the second one the NEXT tick would find it expired, so it goes out anyway and the
     * override is counted. An arm that drops parcels answers no question (the {@code w1117} arm
     * expired 3 tours / 319 parcels and was unusable), which is why this override exists — and why
     * it is counted rather than silent: a high {@code budget_overrides_expiry} means the budget
     * was decorative and the result is the old gate wearing a new name.
     */
    @Test
    @DisplayName("budget: expiry overrides the budget on the last dispatch opportunity, and is counted")
    void expiryOverridesTheBudget() {
        DvrpVehicle a1 = fixtureVehicle(vehALink, "vehA1");
        Fleet fleet = fakeFleet(orderedFleet(a1));
        ModularFreightTour t = tour("dhl_t0", "dhl", 0, T0);
        // Derived from the fixture's own numbers, not hardcoded: at lastFeasible the pending
        // expiry sweep still keeps the tour (the comparison is strict), one second later it does
        // not. NOTE headroomShare is 0 in this fixture, so the urgency RAMP contributes nothing
        // (it unlocks the reserve, and there is no reserve) - what fires here is the terminal
        // +INFINITY branch alone. That is deliberate: it pins the boundary guard in isolation,
        // and the ramp itself is pinned by rampAdmitsAgainstTheBudgetAsTheDeadlineApproaches.
        double lastFeasible = bootstrapDeadline(t);
        assertThat(PassengerLoadProfile.binOf(lastFeasible - 1.0)).isEqualTo(82);
        assertThat(PassengerLoadProfile.binOf(lastFeasible + bootstrapChain(t))).isEqualTo(84);

        PassengerLoadProfile blocking = oneIteration(Map.of(82, 1, 83, 1, 84, 1));
        assertThat(blocking.budget(82, 1, 0.0)).isEqualTo(0.0);   // no room in any spanned bin
        ModularTourDispatcher d = new ModularTourDispatcher("drt", List.of(t), 0.0, 0, List.of(),
                blocking, 0.0, fleet, scheduleInquiry, scheduler, network, events);

        // TWO simsteps before the last chance: refused, NOT overridden. Two and not one,
        // because since 2026-09-06 the terminal branch fires when the remaining slack no longer
        // covers ONE TICK - i.e. deliberately one tick early. Over-firing costs a single extra
        // freight commitment; under-firing drops parcels, and those two errors are not remotely
        // symmetric (d1d_f130_bud2 lost 16 of 46 tours to the under-firing version).
        at(d, lastFeasible - 2.0);
        assertThat(dispatchSequence()).isEmpty();
        assertThat(d.budgetBlockedDispatches()).isEqualTo(1);
        assertThat(d.budgetOverridesExpiry()).isZero();

        // the last simstep at which the expiry sweep still keeps it: the tour goes out
        at(d, lastFeasible);
        assertThat(dispatchSequence()).containsExactly((long) lastFeasible + "|dhl_t0|vehA1");
        assertThat(d.budgetOverridesExpiry()).isEqualTo(1);
        assertThat(d.budgetBlockedDispatches())
                .as("an override is not a block; the two counters must not double-count").isEqualTo(1);
        assertThat(recorded(ModularTourEvent.Phase.EXPIRED)).isEmpty();
    }

    /**
     * THE RAMP (plan 2026-09-05, Fix 2 - the user's "penalty for expiring tours"). Same tour, same
     * budget, same fleet; the ONLY thing that differs between the two halves is how much slack is
     * left. Far from its deadline the tour is refused; inside the lead window the same tour
     * against the same budget goes out, because urgency has unlocked part of the reserve. This is
     * what the binary override could not do - there the tour waited until the last instant and
     * the splicer refused it, which is how 31 of 46 tours died in {@code d1d_f130_bud}.
     *
     * <p><b>Arithmetic, longhand.</b> Fleet 4, headroom 0.5, 2 passenger-busy vehicles in every
     * spanned bin: {@code budget = 4 - 2 - 0.5*4 = 0}. Admission needs
     * {@code projectedFreight + 1 = 1 <= 0 + urgency}, i.e. {@code urgency >= 1.0}. The ramp's
     * endpoint is {@code headroomShare * fleetSize = 0.5 * 4 = 2.0} and urgency at slack
     * {@code s} is {@code (1 - s/3600) * 2.0}, so the crossing sits at {@code s = 1800} - strictly
     * inside the lead window and strictly before the deadline, which is what makes the two halves
     * differ by slack alone.
     *
     * <p><b>Bins.</b> The deadline is 74250 s (see {@link #bootstrapChainDurationIsPinned}), so
     * the two probes at slack 3000 / 1500 sit at 71250 and 72750 and their spans reach 72600 and
     * 74100 - bins 79 through 82. The fixture loads 78..85 so that no probe can be admitted for
     * the trivial reason that its bin was never observed (which is exactly how the first draft of
     * this test passed for the wrong reason).
     */
    @Test
    @DisplayName("budget: the urgency ramp admits inside the lead window, and is counted separately")
    void rampAdmitsInsideTheLeadWindow() {
        Fleet fleet = fakeFleet(orderedFleet(
                fixtureVehicle(vehALink, "vehA1"), fixtureVehicle(vehALink, "vehA2"),
                fixtureVehicle(vehALink, "vehA3"), fixtureVehicle(vehALink, "vehA4")));
        ModularFreightTour t = tour("dhl_t0", "dhl", 0, T0);
        double deadline = bootstrapDeadline(t);
        assertThat(deadline).isEqualTo(74358.0);

        Map<Integer, Integer> busy = new LinkedHashMap<>();
        for (int bin = 78; bin <= 85; bin++) {
            busy.put(bin, 2);
        }
        PassengerLoadProfile p = oneIteration(busy);
        assertThat(p.budget(79, 4, 0.5)).isEqualTo(0.0);
        assertThat(p.budget(82, 4, 0.5)).isEqualTo(0.0);

        // ONE DISPATCHER PER PROBE, deliberately. Driving a single dispatcher with two ticks 1500 s
        // apart would make it MEASURE a 1500 s simstep (observedSimstepS), and the terminal branch
        // fires as soon as the remaining slack no longer covers one tick - so the second probe
        // would be carried by the terminal branch and the ramp would never be exercised. In
        // production the dispatcher ticks once per 1 s simstep; a fresh instance keeps the seeded
        // 1.0 s and reproduces that. This is a fixture artefact, not a property of the gate.
        ModularTourDispatcher far = new ModularTourDispatcher("drt", List.of(t), 0.0, 0, List.of(),
                p, 0.5, null, null, /*urgencyLeadS*/ 3600.0,
                fleet, scheduleInquiry, scheduler, network, events);
        // slack 3000 -> urgency = (1 - 3000/3600) * 2.0 = 0.333..., short of the 1.0 needed
        at(far, deadline - 3000.0);
        assertThat(dispatchSequence()).as("ramp has not crossed yet").isEmpty();
        assertThat(far.budgetBlockedDispatches()).isEqualTo(1);
        assertThat(far.budgetUrgencyAdmits()).isZero();

        ModularTourDispatcher near = new ModularTourDispatcher("drt", List.of(t), 0.0, 0, List.of(),
                p, 0.5, null, null, /*urgencyLeadS*/ 3600.0,
                fleet, scheduleInquiry, scheduler, network, events);
        // slack 1500 -> urgency = (1 - 1500/3600) * 2.0 = 1.1666..., past 1.0 -> admitted
        at(near, deadline - 1500.0);
        assertThat(dispatchSequence()).containsExactly("72858|dhl_t0|vehA1");
        assertThat(near.budgetUrgencyAdmits())
                .as("1500 s of slack is far more than one 1 s tick, so this can only be the RAMP")
                .isEqualTo(1);
        assertThat(near.budgetOverridesExpiry())
                .as("the deadline had not passed - this is the ramp, not the terminal override")
                .isZero();
        assertThat(recorded(ModularTourEvent.Phase.EXPIRED)).isEmpty();
    }

    /**
     * The lead window is the ramp's only parameter, and it must actually govern WHEN the crossing
     * happens. Identical fixture to {@link #rampAdmitsInsideTheLeadWindow}, identical probe at
     * slack 1500 - but with a lead of 1800 s instead of 3600 s the urgency there is
     * {@code (1 - 1500/1800) * 2.0 = 0.333}, short of 1.0, so the tour is refused. A ramp that
     * ignored its lead and, say, keyed off a fixed fraction of the chain duration would pass the
     * previous test and fail this one.
     */
    @Test
    @DisplayName("budget: the lead window governs where the ramp crosses")
    void leadWindowGovernsTheCrossing() {
        Fleet fleet = fakeFleet(orderedFleet(
                fixtureVehicle(vehALink, "vehA1"), fixtureVehicle(vehALink, "vehA2"),
                fixtureVehicle(vehALink, "vehA3"), fixtureVehicle(vehALink, "vehA4")));
        ModularFreightTour t = tour("dhl_t0", "dhl", 0, T0);
        double deadline = bootstrapDeadline(t);

        Map<Integer, Integer> busy = new LinkedHashMap<>();
        for (int bin = 78; bin <= 85; bin++) {
            busy.put(bin, 2);
        }
        ModularTourDispatcher d = new ModularTourDispatcher("drt", List.of(t), 0.0, 0, List.of(),
                oneIteration(busy), 0.5, null, null, /*urgencyLeadS*/ 1800.0,
                fleet, scheduleInquiry, scheduler, network, events);

        at(d, deadline - 1500.0);
        assertThat(dispatchSequence())
                .as("the same instant that ADMITS with a 3600 s lead must be refused with 1800 s")
                .isEmpty();
        assertThat(d.budgetUrgencyAdmits()).isZero();
    }

    /**
     * With {@code headroomShare = 0} the ramp is INERT by construction - it unlocks the reserve,
     * and there is no reserve - so only the terminal branch at slack 0 can carry a blocked tour.
     * This is a real property of the design, not an oversight, and it is pinned here so that a
     * future headroom sweep down to 0 is read correctly rather than as "the ramp broke".
     */
    @Test
    @DisplayName("budget: at headroom 0 the ramp is inert and only the deadline branch fires")
    void rampIsInertAtZeroHeadroom() {
        DvrpVehicle a1 = fixtureVehicle(vehALink, "vehA1");
        Fleet fleet = fakeFleet(orderedFleet(a1));
        ModularFreightTour t = tour("dhl_t0", "dhl", 0, T0);
        double deadline = bootstrapDeadline(t);

        Map<Integer, Integer> busy = new LinkedHashMap<>();
        for (int bin = 78; bin <= 85; bin++) {
            busy.put(bin, 1);
        }
        PassengerLoadProfile blocking = oneIteration(busy);
        assertThat(blocking.budget(82, 1, 0.0)).isEqualTo(0.0);

        ModularTourDispatcher d = new ModularTourDispatcher("drt", List.of(t), 0.0, 0, List.of(),
                blocking, 0.0, null, null, 3600.0,
                fleet, scheduleInquiry, scheduler, network, events);

        // deep inside the lead window, and still refused: h = 0 leaves nothing to unlock
        at(d, deadline - 60.0);
        assertThat(dispatchSequence()).isEmpty();
        assertThat(d.budgetUrgencyAdmits()).isZero();

        // at the deadline the terminal +INFINITY branch fires, counted as an expiry override
        at(d, deadline);
        assertThat(dispatchSequence()).containsExactly("74358|dhl_t0|vehA1");
        assertThat(d.budgetOverridesExpiry()).isEqualTo(1);
        assertThat(d.budgetUrgencyAdmits()).isZero();
        assertThat(recorded(ModularTourEvent.Phase.EXPIRED)).isEmpty();
    }

    /**
     * A tour with plenty of slack that the budget admits anyway must NOT be counted as a ramp
     * admission. Without this the counter would saturate - "was ever admitted" is 100% of every
     * dispatch - and could no longer answer the question it exists for, which is whether the ramp
     * carried any tour that would otherwise have been refused (feedback_instrument_design).
     */
    @Test
    @DisplayName("budget: an ordinary admission is not counted as a ramp admission")
    void ordinaryAdmissionIsNotCountedAsUrgency() {
        Fleet fleet = fakeFleet(orderedFleet(fixtureVehicle(vehALink, "vehA1")));
        ModularFreightTour t = tour("dhl_t0", "dhl", 0, T0);
        PassengerLoadProfile roomy = oneIteration(Map.of(32, 0, 33, 0));
        assertThat(roomy.budget(32, 1, 0.0)).isEqualTo(1.0);

        ModularTourDispatcher d = new ModularTourDispatcher("drt", List.of(t), 0.0, 0, List.of(),
                roomy, 0.0, null, null, 3600.0,
                fleet, scheduleInquiry, scheduler, network, events);
        at(d, T0);
        assertThat(dispatchSequence()).containsExactly("28800|dhl_t0|vehA1");
        assertThat(d.budgetUrgencyAdmits()).isZero();
        assertThat(d.budgetOverridesExpiry()).isZero();
        assertThat(d.budgetBlockedDispatches()).isZero();
    }

    /**
     * The THIRD part of the honest envelope, and the one no other test in this file can reach:
     * the cap is {@code min(latestEnd, max vehicle serviceEnd)}, not {@code latestEnd} alone.
     *
     * <p>Every other fixture here builds vehicles with {@code serviceEndTime = 86400}, later than
     * any {@code latestEnd} used, so the minimum is always {@code latestEnd} and an implementation
     * that simply ignored {@code serviceEnd} would pass the whole file. The splicer, meanwhile,
     * really does take {@code min(tour.latestEnd(), vehicle.getServiceEndTime())}
     * ({@code ModularTourScheduler:121}) - so without this test the dispatcher could keep offering
     * a tour no vehicle can take, every simstep, until it expires: the same "held until it dies"
     * shape that killed {@code d1d_f130_bud}, from a different cause.
     *
     * <p>Longhand: the fleet's only vehicle ends service at 40000 s, the tour's {@code latestEnd}
     * is 75600, and the chain is 1350 s. The cap is therefore 40000, the deadline 38650, and a
     * tick at 38651 must expire the tour. Against {@code latestEnd} alone the deadline would be
     * 74250 and the same tick would keep it pending for another ten hours.
     */
    @Test
    @DisplayName("chain: the deadline cap is min(latestEnd, serviceEnd), not latestEnd alone")
    void deadlineCapHonoursVehicleServiceEnd() {
        DvrpVehicle early = shortServiceVehicle(vehALink, "vehA1", /*serviceEnd*/ 40000.0);
        Fleet fleet = fakeFleet(orderedFleet(early));
        ModularFreightTour t = tour("dhl_t0", "dhl", 0, T0);
        assertThat(t.latestEnd()).isEqualTo(75600.0);

        ModularTourDispatcher d = new ModularTourDispatcher("drt", List.of(t), 0.0,
                fleet, scheduleInquiry, scheduler, network, events);

        // 1 s past min(75600, 40000) - 1242 = 38758
        at(d, 38759.0);
        assertThat(recorded(ModularTourEvent.Phase.EXPIRED))
                .as("the only vehicle stops serving at 40000; a chain of 1350 s can no longer"
                        + " finish, so the tour is dead even though latestEnd is hours away")
                .extracting(ModularTourEvent::getTourId)
                .containsExactly("dhl_t0");
    }

    /**
     * Same fixture one second EARLIER, as the positive control: without it the test above would
     * also pass against an implementation that expired the tour for some unrelated reason (or
     * expired everything).
     */
    @Test
    @DisplayName("chain: one second before the service-end deadline the tour still lives")
    void tourSurvivesUntilTheServiceEndDeadline() {
        DvrpVehicle early = shortServiceVehicle(vehALink, "vehA1", /*serviceEnd*/ 40000.0);
        Fleet fleet = fakeFleet(orderedFleet(early));
        ModularTourDispatcher d = new ModularTourDispatcher("drt",
                List.of(tour("dhl_t0", "dhl", 0, T0)), 1.0,
                fleet, scheduleInquiry, scheduler, network, events);

        // theta = 1.0 so nothing is dispatched and the ONLY observable is the expiry sweep
        at(d, 38758.0);
        assertThat(recorded(ModularTourEvent.Phase.EXPIRED)).isEmpty();
        at(d, 38759.0);
        assertThat(recorded(ModularTourEvent.Phase.EXPIRED)).hasSize(1);
    }

    /**
     * THE TEST THAT WAS MISSING, and whose absence cost a 22-hour run.
     *
     * <p>The 2026-09-05 version wrote the terminal branch as {@code slack <= 0}. Every fixture in
     * this file had an integer-valued chain duration, so every deadline landed exactly ON a tick
     * and {@code slack == 0} was reachable - the branch looked tested. In a real run the chain is
     * a LEARNED floating-point quantity, the deadline falls BETWEEN two ticks, and the branch is
     * unreachable: at the tick before, slack is a positive fraction and only the ramp applies; at
     * the tick after, the expiry sweep at the top of {@code dispatch} has already removed the
     * tour. {@code d1d_f130_bud2} published {@code budget_overrides_expiry == 0} while losing 16
     * of 46 tours.
     *
     * <p>This fixture reproduces exactly that: a learned chain of 1350.5 s puts the deadline at
     * 74249.5, strictly between ticks. The budget blocks every spanned bin and headroom is 0, so
     * the ramp contributes nothing - the ONLY thing that can dispatch this tour is the terminal
     * branch, and only if it tests against the tick spacing rather than against zero.
     */
    @Test
    @DisplayName("budget: the terminal branch fires on the last TICK, not the last instant (off-grid deadline)")
    void terminalBranchFiresOnTheLastTickNotTheLastInstant() {
        DvrpVehicle a1 = fixtureVehicle(vehALink, "vehA1");
        Fleet fleet = fakeFleet(orderedFleet(a1));
        ModularFreightTour t = tour("dhl_t0", "dhl", 0, T0);

        // A LEARNED chain, deliberately off the integer grid - this is what a real run has.
        FreightChainProfile chain = new FreightChainProfile(1);
        chain.observe("dhl_t0", 1350.5);
        chain.notifyIterationEnds(new IterationEndsEvent(null, 0, false));
        assertThat(chain.estimate("dhl_t0")).isEqualTo(1350.5);
        double deadline = 75600.0 - 1350.5;
        assertThat(deadline).as("must be off the 1 s tick grid").isEqualTo(74249.5);

        Map<Integer, Integer> busy = new LinkedHashMap<>();
        for (int bin = 78; bin <= 85; bin++) {
            busy.put(bin, 1);
        }
        ModularTourDispatcher d = new ModularTourDispatcher("drt", List.of(t), 0.0, 0, List.of(),
                oneIteration(busy), 0.0, null, chain, 3600.0,
                fleet, scheduleInquiry, scheduler, network, events);

        // two ticks out: refused (ramp is inert at headroom 0)
        at(d, 74248.0);
        assertThat(dispatchSequence()).isEmpty();
        assertThat(d.budgetOverridesExpiry()).isZero();

        // the last tick before the off-grid deadline: slack 0.5 s no longer covers a tick
        at(d, 74249.0);
        assertThat(dispatchSequence())
                .as("with the pre-2026-09-06 `slack <= 0` test this is EMPTY and the tour expires"
                        + " one tick later - the exact failure d1d_f130_bud2 shipped")
                .containsExactly("74249|dhl_t0|vehA1");
        assertThat(d.budgetOverridesExpiry()).isEqualTo(1);
        assertThat(recorded(ModularTourEvent.Phase.EXPIRED)).isEmpty();
    }

    /** A zero or negative urgency lead would divide by zero and silently restore the old cliff. */
    @Test
    @DisplayName("budget: a non-positive urgency lead is rejected at construction")
    void rejectsNonPositiveUrgencyLead() {
        Fleet fleet = fakeFleet(orderedFleet(fixtureVehicle(vehALink, "vehA1")));
        assertThatThrownBy(() -> new ModularTourDispatcher("drt",
                List.of(tour("dhl_t0", "dhl", 0, T0)), 0.0, 0, List.of(), null, 0.0, null, null,
                0.0, fleet, scheduleInquiry, scheduler, network, events))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("urgency lead must be positive");
    }

    /**
     * The one number every deadline in this file is derived from, pinned LONGHAND.
     *
     * <p>The standard fixture tour has a single stop ON the depot link with 240 s of service, so
     * the free-flow lower bound is {@code 2*RETOOLING_S + service + 0 travel = 2*420 + 240 = 1080}
     * and the bootstrap estimate is {@code 1080 * 1.25 = 1350}. Written out rather than
     * recomputed: the derived helpers {@link #bootstrapChain} / {@link #bootstrapDeadline} compose
     * the same two ingredients the production code does, so without this assertion a change to
     * either would move every deadline in this file in lockstep and no test would notice.
     *
     * <p>Note what this replaced: the OLD envelope was {@code 2*RETOOLING_S + plannedDuration =
     * 840 + 600 = 1440}. The fixture's {@code plannedDuration} is a hand-set 600 s that has no
     * relation to its stop list, which is exactly the kind of divergence between jsprit's
     * car-network figure and the real DRT-routed chain that the 2026-09-05 fix is about.
     */
    @Test
    @DisplayName("chain: the bootstrap estimate is the free-flow bound times the factor (pinned)")
    void bootstrapChainDurationIsPinned() {
        ModularFreightTour t = tour("dhl_t0", "dhl", 0, T0);
        assertThat(scheduler.minimumChainDurationS(t))
                .as("2*RETOOLING_S + 240 s service + zero-length depot->depot travel")
                .isEqualTo(1080.0);
        assertThat(Modular.CHAIN_BOOTSTRAP_FACTOR)
                .as("measured 2026-09-06 from d1d_f130_bud2: chain_ratio_p90 1.094, max 1.137")
                .isEqualTo(1.15);
        assertThat(bootstrapChain(t)).isEqualTo(1242.0);
        assertThat(bootstrapDeadline(t))
                .as("latestEnd 75600 (earlier than the fixture's 86400 service end) minus 1242")
                .isEqualTo(74358.0);
    }

    /**
     * The honest envelope must be INERT on an arm with generous slack. This is the assumption the
     * plan records in place of re-running the anchor: it dispatches all 46 tours at ~07:16 against
     * a 21:00 latestEnd, i.e. hours of slack against a ~3.8 h chain, and expires none. If the new
     * envelope changed decisions there, every existing 1d arm would have become incomparable.
     */
    @Test
    @DisplayName("chain: the honest envelope changes nothing when slack is generous (anchor inertness)")
    void honestEnvelopeIsInertWithGenerousSlack() {
        DvrpVehicle a1 = fixtureVehicle(vehALink, "vehA1");
        Fleet fleet = fakeFleet(orderedFleet(a1));
        ModularFreightTour t = tour("dhl_t0", "dhl", 0, T0);
        // 28800 + 1242 = 30042, far short of 75600 - and 28800 + 1440 (the OLD envelope) would
        // have been just as far short, which is the point: both answers agree here.
        ModularTourDispatcher d = new ModularTourDispatcher("drt", List.of(t), 0.0,
                fleet, scheduleInquiry, scheduler, network, events);
        at(d, T0);
        assertThat(dispatchSequence()).containsExactly("28800|dhl_t0|vehA1");
        assertThat(recorded(ModularTourEvent.Phase.EXPIRED)).isEmpty();
    }

    /**
     * A negative budget blocks. It must not be read as "the profile cannot speak" (which is what
     * {@link Double#POSITIVE_INFINITY} means and is the permissive direction), and it must not be
     * clamped: the magnitude is the overhang this study measures and Task 6 publishes it.
     *
     * <p>Honest note on discrimination: a literal {@code Math.max(0.0, budget)} clamp is provably
     * INERT at this gate and no test can catch it. The condition is
     * {@code projectedFreight + 1 > budget}; with {@code projectedFreight >= 0} the left-hand side
     * is at least 1, so for any {@code budget < 0} both the clamped and unclamped comparison are
     * true. What IS observable — and what this test pins — is the family of defects that turn a
     * negative budget permissive, e.g. treating any non-positive or non-finite value as "unknown".
     */
    @Test
    @DisplayName("budget: a NEGATIVE budget blocks and is not read as 'unknown'")
    void negativeBudgetBlocks() {
        Fleet fleet = fakeFleet(orderedFleet(
                fixtureVehicle(vehALink, "vehA1"), fixtureVehicle(vehALink, "vehA2"),
                fixtureVehicle(vehALink, "vehA3"), fixtureVehicle(vehALink, "vehA4")));
        // fleet 4, h = 0.5: bin 32 carries 3 passenger-busy vehicles -> 4 - 3 - 2 = -1, i.e. the
        // passenger operation already exceeds (1-h) of the fleet. Bin 33 is deliberately roomy
        // (4 - 0 - 2 = +2) so the refusal can only have come from the NEGATIVE bin.
        PassengerLoadProfile overloaded = oneIteration(Map.of(32, 3, 33, 0));
        assertThat(overloaded.budget(32, 4, 0.5))
                .as("fixture must really be negative or this test proves nothing").isEqualTo(-1.0);
        assertThat(overloaded.budget(33, 4, 0.5)).isEqualTo(2.0);

        ModularTourDispatcher d = new ModularTourDispatcher("drt",
                List.of(tour("dhl_t0", "dhl", 0, T0)), 0.0, 0, List.of(),
                overloaded, 0.5, fleet, scheduleInquiry, scheduler, network, events);
        at(d, T0);
        assertThat(dispatchSequence()).isEmpty();
        assertThat(d.budgetBlockedDispatches()).isEqualTo(1);
    }

    /**
     * The projected-freight counter is booked across the whole span, not only into the bin the
     * dispatch happens in. Tour 1 is refused solely because tour 0's booking reached one bin
     * further than the simstep it was dispatched in; an implementation that booked only
     * {@code binOf(now)} would let tour 1 through.
     */
    @Test
    @DisplayName("budget: projectedFreight is booked across the WHOLE span, not just the first bin")
    void projectedFreightIsBookedAcrossTheWholeSpan() {
        Fleet fleet = fakeFleet(orderedFleet(fixtureVehicle(vehALink, "vehA1"),
                fixtureVehicle(vehALink, "vehA2")));
        PassengerLoadProfile p = oneIteration(Map.of(32, 0, 33, 1));
        assertThat(p.budget(32, 2, 0.0)).isEqualTo(2.0);
        assertThat(p.budget(33, 2, 0.0)).isEqualTo(1.0);

        List<ModularFreightTour> tours = List.of(
                tour("dhl_t0", "dhl", 0, T0), tour("dhl_t1", "dhl", 1, T0));
        ModularTourDispatcher d = new ModularTourDispatcher("drt", tours, 0.0, 0, List.of(),
                p, 0.0, fleet, scheduleInquiry, scheduler, network, events);
        at(d, T0);

        // tour 0: 0+1 <= 2 in bin 32 AND 0+1 <= 1 in bin 33 -> out. tour 1: 1+1 <= 2 in bin 32,
        // but 1+1 > 1 in bin 33 -> refused, and ONLY because tour 0 was booked that far ahead.
        // Neither remaining gate can explain it: theta is 0.0 with a second vehicle still idle,
        // and the cap is 0 = unlimited.
        assertThat(dispatchSequence()).containsExactly("28800|dhl_t0|vehA1");
        assertThat(d.budgetBlockedDispatches()).isEqualTo(1);
    }

    /**
     * The number fed to {@code observe} is PASSENGER-busy, not busy. A freight-committed vehicle
     * counts as available to passengers — otherwise the budget shrinks as freight is dispatched,
     * which is negative feedback converging to zero freight, and it would do so quietly, looking
     * like "the passenger operation is simply very busy". The fleet here is one of each kind, so
     * the three plausible wrong implementations all give 2 and only the right one gives 1:
     * {@code !isIdle} counts the freight vehicle, {@code fleetSize - idle.size()} does too, and
     * {@code fleetSize - idle.size() - committed} would be right by accident only while every
     * non-idle vehicle is one of those two kinds.
     *
     * <p>The dispatcher gets NO tours, so {@code dispatch} takes the empty-pending early return —
     * the path most simsteps of a real run take, and the one that must still observe.
     */
    @Test
    @DisplayName("budget: the observed passenger-busy count excludes freight-committed vehicles")
    void observedPassengerBusyExcludesFreightCommittedVehicles() {
        DvrpVehicle pax = passengerBusyVehicle(vehALink, "vehPax");
        DvrpVehicle freight = fixtureVehicle(vehALink, "vehFreight");
        assertThat(scheduler.schedule(freight, tour("committer", "dhl", 0, T0), T0)).isPresent();
        DvrpVehicle free = fixtureVehicle(vehALink, "vehIdle");
        Fleet fleet = fakeFleet(orderedFleet(pax, freight, free));

        timer.setTime(T0);
        assertThat(scheduleInquiry.isIdle(pax)).isFalse();
        assertThat(Modular.hasUnperformedFreightTask(pax.getSchedule())).isFalse();
        assertThat(scheduleInquiry.isIdle(freight)).isFalse();
        assertThat(Modular.hasUnperformedFreightTask(freight.getSchedule())).isTrue();
        assertThat(scheduleInquiry.isIdle(free)).isTrue();

        PassengerLoadProfile p = new PassengerLoadProfile(1);
        ModularTourDispatcher d = new ModularTourDispatcher("drt", List.of(), 0.0, 0, List.of(),
                p, 0.0, fleet, scheduleInquiry, scheduler, network, events);
        at(d, T0);

        assertThat(p.currentIterationObservations(PassengerLoadProfile.binOf(T0)))
                .as("observe() must fire BEFORE the empty-pending early return").isEqualTo(1);
        p.notifyIterationEnds(new IterationEndsEvent(null, 0, false));
        assertThat(p.smoothedPassengerBusy(PassengerLoadProfile.binOf(T0)))
                .as("only vehPax is on passenger work; vehFreight is available to passengers")
                .isEqualTo(1.0);
    }

    // ------------------------------------------------- Task 6: the three causes, three CSV rows

    /**
     * THE THREE-CAUSE DISCRIMINATION TEST (plan Task 6). A planned tour can fail to go out for
     * three structurally different reasons, and {@code modular_tour_stats.csv} must attribute each
     * to its OWN row. Conflating any two points the theta sweep - this study's main 1d instrument -
     * at the wrong knob: "lower theta" answers a gate that is too tight, "loosen the tour cap"
     * answers a tour that never fit, and "raise the headroom / re-read the profile" answers a
     * budget refusal. METHODS-LOG 2.18 records what the last such conflation cost.
     *
     * <p>Each of the three halves runs a REAL dispatcher into a REAL {@link ModularKpiHandler} and
     * reads the CSV that handler actually wrote - not a hand-assembled event sequence, which could
     * only ever re-state what the test author already believed. All three run with the budget ON,
     * so no row is ever absent merely because the feature was off.
     *
     * <p>Every half asserts the full triple (budget-blocked / splice-rejected / expired), so a
     * mis-attribution fails on BOTH sides - the row that wrongly gained a count and the row that
     * wrongly lost one - rather than only where the author happened to look.
     */
    @Test
    @DisplayName("Task 6: budget-blocked, splice-rejected and expired are three separate CSV rows")
    void theThreeCausesAreSeparatelyObservableInTheCsv(@TempDir Path tmp) throws Exception {
        // (a) BUDGET-BLOCKED: the current bin is free, a later bin the tour would span is not.
        // Same fixture as budgetRefusesForALaterBinAlthoughTheCurrentBinIsFree.
        Map<String, Double> blocked = publishOneScenario(tmp, "BLOCKED", (ev, stats) -> {
            Fleet fleet = fakeFleet(orderedFleet(fixtureVehicle(vehALink, "vehA1")));
            ModularTourDispatcher d = new ModularTourDispatcher("drt",
                    List.of(tour("dhl_t0", "dhl", 0, T0)), 0.0, 0, List.of(),
                    oneIteration(Map.of(32, 0, 33, 1)), 0.0, stats,
                    fleet, scheduleInquiry, scheduler, network, ev);
            at(d, T0);
        });
        assertThat(blocked.get("budget_active")).isEqualTo(1.0);
        assertThat(blocked.get("budget_blocked_dispatches"))
                .as("the budget refused this tour - the third cause, on its own row").isEqualTo(1.0);
        assertThat(blocked.get("tours_rejected_at_splice"))
                .as("the splicer was never even asked; publishing this as 'the tour never fit'"
                        + " would point the reader at the tour cap").isZero();
        assertThat(blocked.get("tours_expired_pending"))
                .as("the envelope is comfortable; publishing this as 'the gate was too tight'"
                        + " would point the reader at theta - the METHODS-LOG 2.18 mistake").isZero();
        assertThat(blocked.get("budget_overrides_expiry"))
                .as("nothing was overridden - the refusal held").isZero();
        assertThat(blocked.get("tours_planned")).isEqualTo(1.0);
        assertThat(blocked.get("tours_dispatched")).isZero();
        assertThat(blocked.get("tours_pending_eod"))
                .as("a budget-blocked tour is HELD, and held is where it shows up").isEqualTo(1.0);

        // (b) SPLICE-REJECTED: the budget admits every spanned bin; the splicer refuses the only
        // candidate vehicle (~300 km away) against a latestEnd the expiry check still passes.
        Map<String, Double> rejected = publishOneScenario(tmp, "REJECTED", (ev, stats) -> {
            Fleet fleet = fakeFleet(orderedFleet(fixtureVehicle(vehBLink, "vehB")));
            ModularFreightTour tight = new ModularFreightTour("dhl_t0", "dhl", 0, depotLink,
                    T0, /*plannedDuration*/ 600.0, /*latestEnd*/ T0 + 2000.0,
                    List.of(new ModularFreightTour.Stop(depotLink, 240.0, 2)));
            ModularTourDispatcher d = new ModularTourDispatcher("drt", List.of(tight), 0.0, 0,
                    List.of(), oneIteration(Map.of(32, 0, 33, 0)), 0.0, stats,
                    fleet, scheduleInquiry, scheduler, network, ev);
            at(d, T0);
        });
        assertThat(rejected.get("budget_active")).isEqualTo(1.0);
        assertThat(rejected.get("tours_rejected_at_splice")).isEqualTo(1.0);
        assertThat(rejected.get("budget_blocked_dispatches"))
                .as("the budget admitted every bin this tour spans - it must claim no credit for"
                        + " the splicer's refusal").isZero();
        assertThat(rejected.get("tours_expired_pending")).isZero();
        assertThat(rejected.get("tours_dispatched")).isZero();

        // (c) EXPIRED: reached so late that even an instant excursion misses latestEnd.
        Map<String, Double> expired = publishOneScenario(tmp, "EXPIRED", (ev, stats) -> {
            Fleet fleet = fakeFleet(orderedFleet(fixtureVehicle(vehALink, "vehA1")));
            ModularFreightTour t = tour("dhl_t0", "dhl", 0, T0);
            ModularTourDispatcher d = new ModularTourDispatcher("drt", List.of(t), 0.0, 0,
                    List.of(), oneIteration(Map.of(32, 0, 33, 0)), 0.0, stats,
                    fleet, scheduleInquiry, scheduler, network, ev);
            at(d, bootstrapDeadline(t) + 1.0);
        });
        assertThat(expired.get("budget_active")).isEqualTo(1.0);
        assertThat(expired.get("tours_expired_pending")).isEqualTo(1.0);
        assertThat(expired.get("budget_blocked_dispatches"))
                .as("the tour was removed by the expiry sweep BEFORE the budget gate saw it")
                .isZero();
        assertThat(expired.get("tours_rejected_at_splice")).isZero();
        assertThat(expired.get("tours_dispatched")).isZero();
    }

    /**
     * Runs one scenario against its own {@link EventsManager}, its own {@link ModularBudgetStats}
     * and its own {@link ModularKpiHandler}, then returns the CSV that handler actually wrote.
     *
     * <p>ONE stats object is created here and handed to BOTH the handler and (through the scenario
     * lambda) the dispatcher - the production wiring's single-instance property reproduced without
     * a Guice injector. Two instances would publish an all-zero pair from an object nothing ever
     * incremented, and the three-cause assertions above would then blame the dispatcher for a
     * wiring fault. Own events manager per scenario because a KPI handler accumulates: sharing one
     * would let half (b)'s tour appear in half (a)'s CSV.
     */
    private Map<String, Double> publishOneScenario(Path root, String runId,
            java.util.function.BiConsumer<EventsManager, ModularBudgetStats> scenario)
            throws Exception {
        Path dir = root.resolve(runId);
        EventsManager ev = EventsUtils.createEventsManager();
        ModularBudgetStats stats = new ModularBudgetStats();
        ModularKpiHandler kpi = new ModularKpiHandler(
                new OutputDirectoryHierarchy(dir.toAbsolutePath().toString(), runId,
                        OutputDirectoryHierarchy.OverwriteFileSetting.overwriteExistingFiles,
                        ControllerConfigGroup.CompressionType.gzip),
                new ModularPlanStats(0L, 0L, 0L, 0, Map.of(), Map.of()), stats);
        ev.addHandler(kpi);
        scenario.accept(ev, stats);
        kpi.notifyShutdown(new ShutdownEvent(null, false, 0));

        Map<String, Double> csv = new LinkedHashMap<>();
        List<String> lines = Files.readAllLines(
                dir.resolve(runId + ".modular_tour_stats.csv"), StandardCharsets.UTF_8);
        assertThat(lines.get(0)).isEqualTo("metric;value");
        for (int i = 1; i < lines.size(); i++) {
            String[] parts = lines.get(i).split(";", 2);
            csv.put(parts[0], Double.parseDouble(parts[1]));
        }
        return csv;
    }

    /**
     * A profile with exactly ONE completed iteration, in which each named bin was observed once
     * with the given passenger-busy count. Bins NOT named stay unobserved, i.e. NaN -&gt; unbounded
     * budget, so a fixture like {@code Map.of(32, 0)} reads as "bin 32 measured empty, nothing
     * known about the rest" rather than "the rest is empty too". Bins are visited in ascending
     * order rather than in {@code Map.of}'s unspecified order, per the plan's determinism rule.
     */
    private PassengerLoadProfile oneIteration(Map<Integer, Integer> passengerBusyByBin) {
        PassengerLoadProfile p = new PassengerLoadProfile(1);
        new TreeMap<>(passengerBusyByBin)
                .forEach((bin, busy) -> p.observe(bin * PassengerLoadProfile.BIN_S, busy));
        p.notifyIterationEnds(new IterationEndsEvent(null, 0, false));
        return p;
    }

    /**
     * A vehicle busy on PASSENGER work: two chained STAY tasks with the current one not the last,
     * so native {@code DrtScheduleInquiry.isIdle} is false while
     * {@code Modular.hasUnperformedFreightTask} is also false. That combination — non-idle and
     * non-freight — is exactly what the budget counts, and no other fixture in this class produces
     * it ({@link #fixtureVehicle} is idle, {@link #trapVehicle} holds a freight task).
     */
    private DvrpVehicle passengerBusyVehicle(Id<Link> linkId, String id) {
        Link link = network.getLinks().get(linkId);
        ImmutableDvrpVehicleSpecification spec = ImmutableDvrpVehicleSpecification.newBuilder()
                .id(Id.create(id, DvrpVehicle.class))
                .startLinkId(link.getId())
                .capacity(10)
                .serviceBeginTime(0.0)
                .serviceEndTime(86400.0)
                .build();
        DvrpVehicle vehicle = new DvrpVehicleImpl(spec, link);
        vehicle.getSchedule().addTask(new DrtStayTask(0.0, 100.0, link));
        vehicle.getSchedule().addTask(new DrtStayTask(100.0, 86400.0, link));
        vehicle.getSchedule().nextTask(); // current = the FIRST stay, last = the second -> not idle
        return vehicle;
    }

    /** "time|tourId|vehicleId" per DISPATCHED event, in the order the dispatcher emitted them. */
    private List<String> dispatchSequence() {
        List<String> out = new ArrayList<>();
        for (ModularTourEvent e : recorded(ModularTourEvent.Phase.DISPATCHED)) {
            out.add((long) e.getTime() + "|" + e.getTourId() + "|" + e.getVehicleId());
        }
        return out;
    }

    private void at(ModularTourDispatcher dispatcher, double now) {
        timer.setTime(now);
        dispatcher.dispatch(now);
    }

    private List<ModularTourEvent> recorded(ModularTourEvent.Phase phase) {
        List<ModularTourEvent> out = new ArrayList<>();
        for (ModularTourEvent e : recorder.events) {
            if (e.getPhase() == phase) out.add(e);
        }
        return out;
    }

    private Fleet fakeFleet(Map<Id<DvrpVehicle>, DvrpVehicle> vehicles) {
        com.google.common.collect.ImmutableMap<Id<DvrpVehicle>, DvrpVehicle> immutable =
                com.google.common.collect.ImmutableMap.copyOf(vehicles);
        return () -> immutable;
    }

    private Map<Id<DvrpVehicle>, DvrpVehicle> orderedFleet(DvrpVehicle... vehicles) {
        Map<Id<DvrpVehicle>, DvrpVehicle> map = new LinkedHashMap<>();
        for (DvrpVehicle v : vehicles) map.put(v.getId(), v);
        return map;
    }

    /**
     * Like {@link #tour} but with its single stop on the "mid" link instead of on the depot link,
     * which is what makes deadhead and service both non-zero AND unequal: the default fixture's
     * stop sits ON the depot, so every inter-stop leg is a zero-length path and
     * {@code serviceMeters} comes out 0.0 — a symmetric-enough shape to hide an argument
     * transposition in one direction. Used by
     * {@link #dispatchedEventDoesNotTransposeDeadheadAndService}.
     */
    private ModularFreightTour tourViaMid(String tourId, int tourIndex) {
        return new ModularFreightTour(tourId, "dhl", tourIndex, depotLink, T0,
                /*plannedDuration*/ 600.0, /*latestEnd*/ 21 * 3600.0,
                List.of(new ModularFreightTour.Stop(Id.createLinkId("mid"), 240.0, 2)));
    }

    /**
     * The honest chain duration the dispatcher uses for a tour that no iteration has dispatched
     * yet (plan 2026-09-05): the scheduler's free-flow lower bound times the bootstrap factor.
     *
     * <p>Composed here from the two ingredients rather than copied from the dispatcher, and the
     * standard fixture's value is pinned longhand in {@link #bootstrapChainDurationIsPinned()} so
     * a change in either ingredient shows up as one failing assertion with a number in it, rather
     * than as a silent shift of every deadline in this file.
     */
    private double bootstrapChain(ModularFreightTour t) {
        return scheduler.minimumChainDurationS(t) * Modular.CHAIN_BOOTSTRAP_FACTOR;
    }

    /**
     * The last instant a dispatch of {@code t} may still START. Mirrors the dispatcher's
     * {@code deadlineCap - chainDuration}; the fixture vehicles all end service at 86400 s, which
     * is later than every {@code latestEnd} used here, so the cap is {@code latestEnd}.
     */
    private double bootstrapDeadline(ModularFreightTour t) {
        return Math.min(t.latestEnd(), 86400.0) - bootstrapChain(t);
    }

    private ModularFreightTour tour(String tourId, String provider, int tourIndex, double plannedStart) {
        return new ModularFreightTour(tourId, provider, tourIndex, depotLink, plannedStart,
                /*plannedDuration*/ 600.0, /*latestEnd*/ 21 * 3600.0,
                List.of(new ModularFreightTour.Stop(depotLink, 240.0, 2)));
    }

    /**
     * Like {@link #fixtureVehicle} but ending service EARLY, so {@code min(latestEnd, serviceEnd)}
     * is decided by the vehicle rather than by the tour. The trailing STAY is shortened to match:
     * a vehicle whose stay outlived its own service end would be a fixture that cannot occur.
     */
    private DvrpVehicle shortServiceVehicle(Id<Link> linkId, String id, double serviceEnd) {
        Link link = network.getLinks().get(linkId);
        ImmutableDvrpVehicleSpecification spec = ImmutableDvrpVehicleSpecification.newBuilder()
                .id(Id.create(id, DvrpVehicle.class))
                .startLinkId(link.getId())
                .capacity(10)
                .serviceBeginTime(0.0)
                .serviceEndTime(serviceEnd)
                .build();
        DvrpVehicle vehicle = new DvrpVehicleImpl(spec, link);
        vehicle.getSchedule().addTask(new DrtStayTask(0.0, serviceEnd, link));
        vehicle.getSchedule().nextTask();
        return vehicle;
    }

    /** Vehicle with an initial 0..86400 STAY on {@code link}, STARTED (current = that STAY). */
    private DvrpVehicle fixtureVehicle(Id<Link> linkId, String id) {
        Link link = network.getLinks().get(linkId);
        ImmutableDvrpVehicleSpecification spec = ImmutableDvrpVehicleSpecification.newBuilder()
                .id(Id.create(id, DvrpVehicle.class))
                .startLinkId(link.getId())
                .capacity(10)
                .serviceBeginTime(0.0)
                .serviceEndTime(86400.0)
                .build();
        DvrpVehicle vehicle = new DvrpVehicleImpl(spec, link);
        vehicle.getSchedule().addTask(new DrtStayTask(0.0, 86400.0, link));
        vehicle.getSchedule().nextTask(); // PLANNED -> STARTED, current = the initial STAY
        return vehicle;
    }

    /**
     * A vehicle whose ONLY (and therefore current AND last) task is an unperformed
     * {@link ModularFreightStopTask} - constructed directly (bypassing the real splicer) to force
     * native {@code DrtScheduleInquiry.isIdle} to disagree with
     * {@code Modular.hasUnperformedFreightTask}. See
     * {@link #committedVehicleExcludedByPredicateNotIsIdleAlone} for why this is a real, not
     * merely hypothetical, gap.
     */
    private DvrpVehicle trapVehicle(Link link, String id) {
        ImmutableDvrpVehicleSpecification spec = ImmutableDvrpVehicleSpecification.newBuilder()
                .id(Id.create(id, DvrpVehicle.class))
                .startLinkId(link.getId())
                .capacity(10)
                .serviceBeginTime(0.0)
                .serviceEndTime(86400.0)
                .build();
        DvrpVehicle vehicle = new DvrpVehicleImpl(spec, link);
        vehicle.getSchedule().addTask(new ModularFreightStopTask(0.0, 86400.0, link, 5, "trap_tour", 0));
        vehicle.getSchedule().nextTask(); // PLANNED -> STARTED, current = the freight stop (only + last task)
        return vehicle;
    }

    private Network buildNetwork() {
        Network net = NetworkUtils.createNetwork();
        NetworkFactory f = net.getFactory();
        Node n1 = f.createNode(Id.createNodeId("n1"), new Coord(0, 0));
        Node n2 = f.createNode(Id.createNodeId("n2"), new Coord(200, 0));
        Node n3 = f.createNode(Id.createNodeId("n3"), new Coord(250, 0));
        Node n4 = f.createNode(Id.createNodeId("n4"), new Coord(300, 0));
        Node n5 = f.createNode(Id.createNodeId("n5"), new Coord(300300, 0));
        net.addNode(n1);
        net.addNode(n2);
        net.addNode(n3);
        net.addNode(n4);
        net.addNode(n5);
        addLink(net, "vehA", n1, n2, 200);
        addLink(net, "vehARev", n2, n1, 200);
        addLink(net, "depot", n2, n3, 50);
        addLink(net, "depotRev", n3, n2, 50);
        addLink(net, "mid", n3, n4, 50);
        addLink(net, "midRev", n4, n3, 50);
        addLink(net, "vehB", n4, n5, 300000);
        addLink(net, "vehBRev", n5, n4, 300000);
        return net;
    }

    private void addLink(Network net, String id, Node from, Node to, double length) {
        NetworkFactory f = net.getFactory();
        Link link = f.createLink(Id.createLinkId(id), from, to);
        link.setLength(length);
        link.setFreespeed(30.0);
        link.setCapacity(1800);
        link.setNumberOfLanes(1);
        net.addLink(link);
    }

    private static class RecordingHandler implements ModularTourEventHandler {
        final List<ModularTourEvent> events = new ArrayList<>();

        @Override
        public void handleEvent(ModularTourEvent event) {
            events.add(event);
        }
    }
}
