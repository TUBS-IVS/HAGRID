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
import org.matsim.core.mobsim.framework.MobsimTimer;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.trafficmonitoring.FreeSpeedTravelTime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

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

        // now such that now + 2*RETOOLING + plannedDuration > latestEnd - derived from the
        // fixture's OWN numbers (not a hardcoded magic constant), exactly 1s past the boundary.
        double lateNow = tour.latestEnd() - 2 * Modular.RETOOLING_S - tour.plannedDuration() + 1.0;

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

    // --- helpers ---

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

    private ModularFreightTour tour(String tourId, String provider, int tourIndex, double plannedStart) {
        return new ModularFreightTour(tourId, provider, tourIndex, depotLink, plannedStart,
                /*plannedDuration*/ 600.0, /*latestEnd*/ 21 * 3600.0,
                List.of(new ModularFreightTour.Stop(depotLink, 240.0, 2)));
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
