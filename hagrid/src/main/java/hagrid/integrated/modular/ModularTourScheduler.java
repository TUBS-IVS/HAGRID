package hagrid.integrated.modular;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.drt.schedule.DrtTaskFactory;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;
import org.matsim.contrib.dvrp.load.DvrpLoadType;
import org.matsim.contrib.dvrp.path.VrpPathWithTravelData;
import org.matsim.contrib.dvrp.path.VrpPaths;
import org.matsim.contrib.dvrp.schedule.Schedule;
import org.matsim.contrib.dvrp.schedule.Schedules;
import org.matsim.contrib.dvrp.schedule.StayTask;
import org.matsim.contrib.dvrp.schedule.Task;
import org.matsim.contrib.dvrp.router.TimeAsTravelDisutility;
import org.matsim.core.router.speedy.SpeedyALTFactory;
import org.matsim.core.router.util.LeastCostPathCalculator;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.trafficmonitoring.FreeSpeedTravelTime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Splices a freight excursion onto a live DRT schedule at dispatch time - the generalisation
 * of drt-extensions' ServiceTaskSchedulerImpl from 1 stop to N (spike §2). Invariants held:
 * (1) a DRT schedule always ends with a STAY task; (2) the currently-executing trailing STAY
 * is TRUNCATED - a PENDING (not-yet-started) one would need to be REMOVED instead, but that
 * branch is unreachable under plan C3 (see the precondition note below) and is deliberately NOT
 * implemented: a pending trailing STAY can only exist on a non-idle vehicle, which the
 * precondition already rejects before any task inspection happens; (3) never schedule past the
 * completion envelope - and unlike the native template, infeasibility is an EXPLICIT
 * Optional.empty() with the schedule untouched, never a silent no-op (spike §2 invariant + design
 * §3.3). Feasibility is decided by routing the WHOLE chain (approach + swap + every stop leg +
 * return + swap-back) before a single task is added or the trailing STAY is touched, so a
 * rejection leaves the schedule byte-identical to before the call.
 *
 * <p>Ahead of that routing sits one cheap filter, {@link #minimumChainDurationS}: a tour whose
 * time-independent lower bound already overruns the envelope is rejected without routing. It
 * changes no verdict - the bound proves what the routing would have concluded - and exists because
 * the dispatcher re-offers each still-pending tour on every simstep its gate is open, so a tour
 * that no longer fits was otherwise re-routed in full, once per simstep, until it expired.
 *
 * <p>Precondition (plan C3): the vehicle is IDLE (current task == trailing STAY). The dispatcher
 * only selects idle vehicles; the assert makes a violated assumption loud instead of corrupt.
 * The template's divert-from-RELOCATE branch is therefore unreachable and NOT implemented here.
 */
public class ModularTourScheduler {

    /**
     * Planned-km split of one dispatched excursion (KPI payload for the DISPATCHED event).
     *
     * @param routedDurationS Task 1 (paper-readiness review): the excursion's actual routed
     *                        length on the DRT network, {@code plannedCompletion - now} (the
     *                        dispatch-time "now" passed to {@link #schedule}) - i.e. the SAME
     *                        {@code completion} variable this method already computes for its own
     *                        envelope check, reused verbatim rather than re-derived.
     */
    public record ScheduledExcursion(double deadheadMeters, double serviceMeters,
                                     double plannedCompletion, double routedDurationS) {}

    private final Network network;
    private final TravelTime travelTime;
    private final LeastCostPathCalculator router;
    private final DrtTaskFactory taskFactory;
    private final DvrpLoadType loadType;

    /**
     * Free-flow twin of {@link #router}, for {@link #minimumChainDurationS} only. Built LAZILY:
     * SpeedyALT preprocesses the whole network per instance, and this scheduler is constructed on
     * every 1d QSim whether or not a single dispatch is ever attempted.
     */
    private LeastCostPathCalculator freeFlowRouter;
    private final TravelTime freeFlowTravelTime = new FreeSpeedTravelTime();
    /**
     * tourId -&gt; {@link #minimumChainDurationS}. Plain HashMap on purpose: this class is bound
     * asEagerSingleton in QSim scope precisely because its router is not thread-safe (WIRING
     * INVARIANT 2), so there is no concurrent access to guard against, and a QSim-scoped instance
     * means the cache cannot outlive the network/tour set it was computed against.
     */
    private final Map<String, Double> minChainDurationS = new HashMap<>();

    public ModularTourScheduler(Network network, TravelTime travelTime,
                                TravelDisutility travelDisutility,
                                DrtTaskFactory taskFactory, DvrpLoadType loadType) {
        this.network = network;
        this.travelTime = travelTime;
        this.router = new SpeedyALTFactory().createPathCalculator(network, travelDisutility, travelTime);
        this.taskFactory = taskFactory;
        this.loadType = loadType;
    }

    public Optional<ScheduledExcursion> schedule(DvrpVehicle vehicle, ModularFreightTour tour,
                                                 double now) {
        Schedule schedule = vehicle.getSchedule();
        Task last = Schedules.getLastTask(schedule);
        if (!(last instanceof StayTask lastStay)) {
            throw new IllegalStateException("DRT schedule must end with STAY: " + vehicle.getId());
        }
        boolean stayIsCurrent = schedule.getStatus() == Schedule.ScheduleStatus.STARTED
                && schedule.getCurrentTask() == lastStay;
        // C3 precondition: dispatcher hands us idle vehicles only
        if (!stayIsCurrent) {
            // Schedule.getCurrentTask() itself throws a bare, message-less IllegalStateException
            // (via ScheduleImpl.failIfNotStarted()) whenever status != STARTED - exactly one of
            // the two cases this diagnostic exists to explain. Only resolve it when it is safe to
            // call (review finding 4) so the intended message is the one that actually surfaces.
            String current = schedule.getStatus() == Schedule.ScheduleStatus.STARTED
                    ? String.valueOf(schedule.getCurrentTask())
                    : "status=" + schedule.getStatus();
            throw new IllegalStateException("Modular dispatch expects an idle vehicle (trailing"
                    + " STAY running), got " + current + " on " + vehicle.getId());
        }

        Link depot = resolveLink(tour.depotLink(), tour);
        double departure = Math.max(lastStay.getBeginTime(), now);
        double envelope = Math.min(tour.latestEnd(), vehicle.getServiceEndTime());

        // ---- 0. PROVABLE infeasibility: reach step 1's verdict without routing ----
        // The dispatcher re-offers every still-pending tour on every simstep its idle-share gate
        // is open, so a tour that no longer fits is re-routed - approach + swap + N stop legs +
        // return, N up to a few hundred - once per simstep until it expires, every time only to
        // reach the rejection at the end of step 1. minimumChainDurationS is a LOWER bound on the
        // chain, so exceeding the envelope already at the bound PROVES the full routing would be
        // rejected: same Optional.empty(), same untouched schedule, at the cost of one map lookup.
        // Not a heuristic and not a cache of a previous verdict - see that method for why the
        // bound holds regardless of how travel times move.
        if (departure + minimumChainDurationS(tour) > envelope) {
            return Optional.empty();
        }

        // ---- 1. route the ENTIRE chain first: feasibility before any mutation ----
        Link from = lastStay.getLink();
        double t = departure;
        VrpPathWithTravelData approach = null;
        double deadhead = 0.0;
        double service = 0.0;
        // Id comparison, NOT reference equality (brief defect #2 fix): the vehicle's current
        // link and the depot link are looked up independently and are not guaranteed to be the
        // same object forever, even though today both come from this scheduler's one Network.
        // Getting this wrong would insert a spurious zero-length approach drive - or skip a
        // needed one - depending on which way the accident of object identity happened to fall.
        if (!sameLink(from, depot)) {
            approach = VrpPaths.calcAndCreatePath(from, depot, t, router, travelTime);
            t = approach.getArrivalTime();
            deadhead += VrpPaths.calcDistance(approach);
        }
        // The swap-out task's link must be reference-continuous with lastStay (about to be
        // truncated, not replaced), not with `depot`: when the vehicle is already there
        // (approach == null), `from` IS the schedule's own current link object, while `depot` was
        // resolved independently via this.network and is not guaranteed to be the same object
        // (review finding 1). ScheduleImpl.addTask enforces this exact boundary via REFERENCE
        // equality (Tasks.getEndLink(previousTask) == beginLink) - using `depot` here would
        // truncate the stay and then throw on the very next addTask, worse than the native
        // silent drop this class exists to avoid, since there is no rollback. When approach !=
        // null, VrpPaths.createPath embeds `depot` verbatim as the path's own toLink, so
        // approach.getToLink() == depot always - this is a no-op in that branch.
        Link swapLink = approach != null ? approach.getToLink() : from;
        double swapOutBegin = t;
        t += Modular.RETOOLING_S;

        // Review Minor 8 (documented, not guarded): unlike the approach leg, a stop leg is never
        // checked against sameLink(prev, stopLink) first. A stop on the depot link, or two
        // consecutive stops sharing one link, therefore splices a zero-duration
        // MODULAR_FREIGHT_DRIVE task (VrpPaths.createZeroLengthPath's fromLink==toLink handling
        // inside calcAndCreatePath makes this safe - departure==arrival, no router call). This is
        // ACCEPTED, not a bug: skipping the drive task in that case would change the chain shape
        // (task count/positions) that Task 7's dispatch-event accounting and Task 9's KPIs are
        // written against, for a saving of one zero-length task.
        Link prev = depot;
        List<VrpPathWithTravelData> stopLegs = new ArrayList<>();
        for (ModularFreightTour.Stop stop : tour.stops()) {
            Link stopLink = resolveLink(stop.link(), tour);
            VrpPathWithTravelData leg = VrpPaths.calcAndCreatePath(prev, stopLink, t, router, travelTime);
            stopLegs.add(leg);
            service += VrpPaths.calcDistance(leg);
            t = leg.getArrivalTime() + stop.serviceDuration();
            prev = stopLink;
        }
        VrpPathWithTravelData back = VrpPaths.calcAndCreatePath(prev, depot, t, router, travelTime);
        deadhead += VrpPaths.calcDistance(back);
        double swapBackBegin = back.getArrivalTime();
        double completion = swapBackBegin + Modular.RETOOLING_S;

        if (completion > envelope) {
            return Optional.empty();      // explicit reject - schedule untouched
        }

        // ---- 2. mutate: truncate the running trailing STAY, append the chain ----
        lastStay.setEndTime(departure);

        if (approach != null) {
            schedule.addTask(taskFactory.createDriveTask(vehicle, approach,
                    Modular.FREIGHT_DRIVE_TASK_TYPE));
        }
        schedule.addTask(new ModularCapacityChangeTask(swapOutBegin,
                swapOutBegin + Modular.RETOOLING_S, swapLink, loadType.getEmptyLoad(),
                tour.tourId(), false));

        for (int i = 0; i < tour.stops().size(); i++) {
            ModularFreightTour.Stop stop = tour.stops().get(i);
            VrpPathWithTravelData leg = stopLegs.get(i);
            schedule.addTask(taskFactory.createDriveTask(vehicle, leg,
                    Modular.FREIGHT_DRIVE_TASK_TYPE));
            double arrive = leg.getArrivalTime();
            // The leg's own endpoint, not a fresh network lookup (review Minor 7): structurally
            // the drive task's destination, so the stop task's begin link is guaranteed
            // reference-continuous with it regardless of which Network instance a second
            // stop.link() lookup would resolve against.
            schedule.addTask(new ModularFreightStopTask(arrive, arrive + stop.serviceDuration(),
                    leg.getToLink(), stop.parcels(), tour.tourId(), i));
        }
        schedule.addTask(taskFactory.createDriveTask(vehicle, back, Modular.FREIGHT_DRIVE_TASK_TYPE));
        schedule.addTask(new ModularCapacityChangeTask(swapBackBegin, completion, depot,
                vehicle.getCapacity(), tour.tourId(), true));
        schedule.addTask(taskFactory.createStayTask(vehicle, completion,
                Math.max(vehicle.getServiceEndTime(), completion), depot));

        return Optional.of(new ScheduledExcursion(deadhead, service, completion, completion - now));
    }

    /**
     * A time-INDEPENDENT lower bound on how long {@code tour}'s excursion chain can possibly take,
     * measured from its departure: two capsule swaps + every stop's jsprit service duration +
     * the free-flow travel time of the depot -&gt; stop_1 -&gt; ... -&gt; stop_n -&gt; depot ring.
     * Computed once per tour and cached.
     *
     * <p><b>Why this is a bound and not a guess.</b> Three properties have to hold, and each one
     * is the reason for a specific choice here:
     * <ol>
     *   <li><b>Per link, free flow is the floor.</b> A MATSim link's travel time is never below
     *       {@code length / freespeed}, so every leg's real (congested, event-updated) time is
     *       &ge; its free-flow time. The free-flow router minimises TRAVEL TIME
     *       ({@link TimeAsTravelDisutility} over {@link FreeSpeedTravelTime}), not the injected
     *       disutility - otherwise it could return a time-expensive least-COST path and the
     *       "floor" would not be one.</li>
     *   <li><b>The approach leg is left out</b> - it is the only vehicle-dependent piece, and
     *       dropping a non-negative term keeps the bound valid while making it cacheable per TOUR
     *       instead of per (tour, vehicle, position) triple.</li>
     *   <li><b>Time-independence</b> is what makes the bound usable as a re-offer filter at all:
     *       it cannot move between simsteps, so a tour that fails {@code departure + bound &le;
     *       envelope} once fails it at every later departure too. The alternative design - cache
     *       the previous rejection and reuse it - would NOT be sound: DVRP travel times are
     *       binned and therefore not strictly FIFO, so "it did not fit at 17:00" does not by
     *       itself prove "it does not fit at 17:01".</li>
     * </ol>
     * Together: {@code departure + bound <= completion}, so {@code departure + bound > envelope}
     * implies {@code completion > envelope}, which is exactly the rejection
     * {@link #schedule}'s step 1 would reach. The comparison is strict on both sides, so an exact
     * tie never short-circuits.
     *
     * <p>Package-private for the tests, which pin both directions: that a feasible tour is never
     * vetoed (the bound must stay below the routed completion, including at the envelope boundary),
     * and that a vetoed re-offer performs no routing at all.
     */
    double minimumChainDurationS(ModularFreightTour tour) {
        Double cached = minChainDurationS.get(tour.tourId());
        if (cached != null) {
            return cached;
        }
        if (freeFlowRouter == null) {
            freeFlowRouter = new SpeedyALTFactory().createPathCalculator(
                    network, new TimeAsTravelDisutility(freeFlowTravelTime), freeFlowTravelTime);
        }
        Link depot = resolveLink(tour.depotLink(), tour);
        // Departure 0 for every leg: with a time-independent travel time the leg times do not
        // depend on when the chain starts, which is the whole point (see 3. above). VrpPaths is
        // the same helper step 1 routes with, so both sides carry the same FIRST_LINK_TT and the
        // same "includes the to-link" convention - a hand-rolled sum would not.
        double travel = 0.0;
        Link prev = depot;
        for (ModularFreightTour.Stop stop : tour.stops()) {
            Link stopLink = resolveLink(stop.link(), tour);
            travel += VrpPaths.calcAndCreatePath(prev, stopLink, 0, freeFlowRouter,
                    freeFlowTravelTime).getTravelTime();
            prev = stopLink;
        }
        travel += VrpPaths.calcAndCreatePath(prev, depot, 0, freeFlowRouter, freeFlowTravelTime)
                .getTravelTime();
        double service = tour.stops().stream()
                .mapToDouble(ModularFreightTour.Stop::serviceDuration).sum();
        double bound = 2 * Modular.RETOOLING_S + service + travel;
        minChainDurationS.put(tour.tourId(), bound);
        return bound;
    }

    /**
     * Id-based link identity (review Minor 9): NOT reference equality. The vehicle's current
     * link and a link freshly resolved from {@link #network} are looked up independently and are
     * not guaranteed to be the same object forever, even though today both come from this
     * scheduler's one Network instance. Package-private so it can be unit-tested directly at
     * this granularity - the one place a {@code ==}-vs-id divergence can be forced apart without
     * a live {@code Schedule}'s own reference-equality continuity check getting in the way first.
     */
    static boolean sameLink(Link a, Link b) {
        return a.getId().equals(b.getId());
    }

    /**
     * Resolves a tour-carried link id against this scheduler's Network, or throws naming both
     * the missing id and the tour (review finding 5). Task 4's ModularTourConverter already
     * snaps every depot/stop link onto the DRT network at conversion time and throws there if a
     * link exists in neither network - so by construction every ModularFreightTour reaching this
     * method carries DRT-network link ids. An unresolvable id here means the Network this
     * scheduler was constructed with differs from the one the tour was converted against: a
     * Task 10 wiring bug, not a business-level infeasibility. Optional.empty() stays reserved for
     * TEMPORAL infeasibility (the envelope check) - silently treating a wiring bug as "try again
     * later" would drop parcels without a trace, exactly the failure mode this class exists to
     * eliminate.
     */
    private Link resolveLink(Id<Link> linkId, ModularFreightTour tour) {
        Link link = network.getLinks().get(linkId);
        if (link == null) {
            throw new IllegalStateException("Modular tour " + tour.tourId() + " references link "
                    + linkId + " which does not exist in the injected Network");
        }
        return link;
    }
}
