package hagrid.integrated.modular;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.drt.scheduler.DrtScheduleInquiry;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;
import org.matsim.contrib.dvrp.fleet.Fleet;
import org.matsim.contrib.dvrp.schedule.StayTask;
import org.matsim.contrib.dvrp.schedule.Task;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.utils.geometry.CoordUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Online freight dispatcher (design §3.3): passenger-primary via the idle-share gate.
 * QSim-scoped -&gt; all state resets per iteration by construction (the 1c dd34b23 lesson).
 *
 * <p>Tick order (ModularOptimizer calls {@link #dispatch(double)} BEFORE the delegate's
 * rebalancing runs, so a just-spliced vehicle is already non-idle when MinCostFlow/ReturnToDepot
 * look for vehicles):
 * <ol>
 *   <li>activate tours whose submissionTime has arrived (PLANNED event),</li>
 *   <li>expire pending tours whose completion envelope has passed (EXPIRED - explicit
 *       reject-and-log, never the native silent drop; no replanning, spec §4.3 step 5),</li>
 *   <li>while idleShare &gt; idleThreshold (STRICT - theta=1.0 is the never-dispatch control arm):
 *       dispatch the longest-pending tour to the idle vehicle nearest its depot (deterministic
 *       tie-break by vehicle id). Pending order is (submissionTime, tourIndex, provider) -
 *       plan C7: with the day window ALL tours become pending at ~07:16, so a plain tourId sort
 *       would dispatch every dhl tour before the first gls tour and bias per-provider delta;
 *       interleaving by tour index removes that.</li>
 * </ol>
 *
 * <p><b>Two different envelopes, two different failures (review Finding 3).</b> Step 2's expiry
 * check and the splicer's feasibility check are NOT the same test, and a tour can pass the first
 * while failing the second on every attempt until it expires:
 * <ul>
 *   <li>expiry (here, {@code :95}) uses {@link ModularFreightTour#plannedDuration()} — jsprit's
 *       sum over the <b>car</b> network, with no approach leg;</li>
 *   <li>the splicer ({@link ModularTourScheduler#schedule}) uses the actual routed completion on
 *       the <b>DRT</b> network <i>plus</i> the approach leg from wherever the candidate vehicle
 *       happens to be — always larger, and systematically so wherever DRT routing diverges from
 *       jsprit's.</li>
 * </ul>
 * A splicer rejection therefore emits {@link ModularTourEvent.Phase#SPLICE_REJECTED} (once per
 * tour) and feeds {@code tours_rejected_at_splice}. Without it the tour later trips expiry and is
 * published as {@code tours_expired_pending}, attributing to "the gate was too tight" what was
 * really "the tour never fit" — and those call for opposite responses (lower θ vs. loosen the
 * tour cap), on the sweep that is this study's main 1d instrument.
 *
 * <p>The morning surge is accepted, not fixed: with the full delivery-day window every tour's
 * submissionTime falls at ~07:16, the fleet is still fully idle at that hour, and this gate's
 * while-loop therefore dispatches in ONE simstep until idleShare drops to theta - roughly
 * {@code (1 - theta) * fleetSize} vehicles leave at once. This is a deliberate user decision
 * ("erst Ergebnisse ansehen" - look at results first), not an oversight: no dispatch-rate limit,
 * demand forecast, or smoothing is added here; the theta-sweep plus passenger KPIs are the
 * intended instrument for making that cost visible, not a defect for a future reader to "fix".
 */
public class ModularTourDispatcher {

    private static final Logger LOG = LogManager.getLogger(ModularTourDispatcher.class);

    private final String mode;
    private final List<ModularFreightTour> tours;      // sorted by (submissionTime, tourIndex, provider) - C7
    private final double idleThreshold;
    private final Fleet fleet;
    private final DrtScheduleInquiry scheduleInquiry;
    private final ModularTourScheduler scheduler;
    private final Network network;
    private final EventsManager events;

    private int nextToActivate = 0;
    private final List<ModularFreightTour> pending = new ArrayList<>();
    /**
     * Tour ids already reported as rejected by the splicer, so the SPLICE_REJECTED event and its
     * log line fire ONCE per tour rather than once per retry — a tour the splicer keeps refusing
     * is re-offered every simstep the gate is open. QSim-scoped like the rest of this class, so
     * it resets per iteration by construction.
     */
    private final Set<String> spliceRejected = new LinkedHashSet<>();

    public ModularTourDispatcher(String mode, List<ModularFreightTour> tours, double idleThreshold,
                                 Fleet fleet, DrtScheduleInquiry scheduleInquiry,
                                 ModularTourScheduler scheduler, Network network,
                                 EventsManager events) {
        this.mode = mode;
        this.tours = tours.stream()
                .sorted(Comparator.comparingDouble(ModularFreightTour::submissionTime)
                        .thenComparingInt(ModularFreightTour::tourIndex)     // C7 interleave
                        .thenComparing(ModularFreightTour::provider))
                .toList();
        this.idleThreshold = idleThreshold;
        this.fleet = fleet;
        this.scheduleInquiry = scheduleInquiry;
        this.scheduler = scheduler;
        this.network = network;
        this.events = events;
    }

    public void dispatch(double now) {
        while (nextToActivate < tours.size()
                && tours.get(nextToActivate).submissionTime() <= now) {
            ModularFreightTour t = tours.get(nextToActivate++);
            pending.add(t);
            events.processEvent(ModularTourEvent.planned(now, t.tourId(), t.totalParcels()));
        }
        if (pending.isEmpty()) return;

        // C4 envelope: even an immediate dispatch (approach ~0) could not finish anymore
        pending.removeIf(t -> {
            if (now + 2 * Modular.RETOOLING_S + t.plannedDuration() > t.latestEnd()) {
                // review Finding 1: `mode` earns its place in the log line here - a multi-mode
                // DRT run would otherwise emit expiry warnings with no indication of which mode
                // they came from.
                LOG.warn("Modular tour {} (mode {}) expired pending at {} (latestEnd {}).",
                        t.tourId(), mode, now, t.latestEnd());
                events.processEvent(ModularTourEvent.expired(now, t.tourId(), t.totalParcels()));
                return true;
            }
            return false;
        });
        if (pending.isEmpty()) return;

        List<DvrpVehicle> idle = fleet.getVehicles().values().stream()
                .filter(scheduleInquiry::isIdle)
                .filter(v -> !Modular.hasUnperformedFreightTask(v.getSchedule()))
                .sorted(Comparator.comparing(v -> v.getId().toString()))
                .collect(Collectors.toCollection(ArrayList::new));
        int fleetSize = fleet.getVehicles().size();

        Iterator<ModularFreightTour> it = pending.iterator();
        while (it.hasNext() && !idle.isEmpty()
                && (double) idle.size() / fleetSize > idleThreshold) {
            ModularFreightTour tour = it.next();
            DvrpVehicle vehicle = nearestToDepot(idle, tour);
            Optional<ModularTourScheduler.ScheduledExcursion> excursion =
                    scheduler.schedule(vehicle, tour, now);
            if (excursion.isPresent()) {
                idle.remove(vehicle);
                it.remove();
                events.processEvent(ModularTourEvent.dispatched(now, tour.tourId(),
                        vehicle.getId(), tour.totalParcels(),
                        excursion.get().deadheadMeters(), excursion.get().serviceMeters(),
                        tour.plannedDuration(), excursion.get().routedDurationS()));
            } else if (spliceRejected.add(tour.tourId())) {
                // Review Finding 3: this branch used to be EMPTY - no event, no log, no counter.
                // The tour stayed pending and, when it later tripped the expiry check, was
                // published as tours_expired_pending / delta_share_undispatched, i.e. as
                // "the gate was too tight". It is not the same failure. The two envelopes are
                // different tests: the expiry check above uses plannedDuration - jsprit's sum
                // over the CAR network - while the splicer checks the actual DRT-routed
                // completion plus the approach leg, which is always larger and systematically
                // so wherever DRT routing diverges from jsprit's. A tour can therefore pass
                // expiry and still be refused every simstep until it expires. Confusing the two
                // points the theta sweep - the study's main 1d instrument - at the wrong knob:
                // "lower theta" is the answer to a gate that is too tight, "loosen the tour cap"
                // to a tour that never fit.
                LOG.warn("Modular tour {} (mode {}) rejected by the splicer at {} on candidate"
                        + " vehicle {}: the DRT-routed completion exceeds min(latestEnd {},"
                        + " vehicle service end). Tour stays pending; this is NOT the pending"
                        + " expiry check, which passed.",
                        tour.tourId(), mode, now, vehicle.getId(), tour.latestEnd());
                events.processEvent(ModularTourEvent.spliceRejected(now, tour.tourId(),
                        vehicle.getId(), tour.totalParcels()));
            }
            // infeasible for the nearest vehicle -> stays pending; expiry (above) is the exit
        }
    }

    /** Feed from ModularOptimizer.nextTask: previous = the task just PERFORMED. */
    public void observeTaskTransition(DvrpVehicle vehicle, Task previous, double now) {
        if (previous instanceof ModularFreightStopTask stop) {
            events.processEvent(ModularTourEvent.stopServed(now, stop.getTourId(),
                    vehicle.getId(), stop.getParcels()));
        } else if (previous instanceof ModularCapacityChangeTask swap) {
            events.processEvent(ModularTourEvent.swapDone(now, swap.getTourId(), vehicle.getId()));
            if (swap.isSwapBack()) {
                events.processEvent(ModularTourEvent.completed(now, swap.getTourId(),
                        vehicle.getId()));
            }
        }
    }

    /**
     * Euclidean distance from each idle candidate's current stay-task link to the tour's depot
     * (VERIFY-SOURCE: CoordUtils.calcEuclideanDistance, Link.getToNode().getCoord()). The cast to
     * {@link StayTask} is safe only because every candidate in {@code idle} already passed
     * {@link DrtScheduleInquiry#isIdle}, whose own definition (drt-core, confirmed by reading
     * DrtScheduleInquiry.java) requires the current task to be STAY-base-typed - every STAY-base
     * task class in this codebase (DrtStayTask, DefaultStayTask, ModularFreightStopTask)
     * implements StayTask, so this is not a defensive cast, it is a consequence of the filter
     * already applied upstream.
     */
    private DvrpVehicle nearestToDepot(List<DvrpVehicle> idle, ModularFreightTour tour) {
        Coord depot = resolveDepotLink(tour).getToNode().getCoord();
        DvrpVehicle best = null;
        double bestDist = Double.POSITIVE_INFINITY;
        for (DvrpVehicle v : idle) {   // idle is id-sorted -> '<' keeps the smallest id on ties
            StayTask stay = (StayTask) v.getSchedule().getCurrentTask();
            double d = CoordUtils.calcEuclideanDistance(
                    stay.getLink().getToNode().getCoord(), depot);
            if (d < bestDist) {
                bestDist = d;
                best = v;
            }
        }
        return best;
    }

    /**
     * Resolves a tour's depot link against this dispatcher's Network, or throws naming both the
     * tour and the missing link id (review Finding 2) - the same diagnostic
     * {@code ModularTourScheduler.resolveLink} gives for the identical failure mode (a Task 10
     * wiring bug: the Network injected here differs from the one the tour was converted against),
     * so an unguarded {@code NullPointerException} here would be an inconsistent, less useful
     * report of the same root cause.
     */
    private Link resolveDepotLink(ModularFreightTour tour) {
        Link link = network.getLinks().get(tour.depotLink());
        if (link == null) {
            throw new IllegalStateException("Modular tour " + tour.tourId() + " references link "
                    + tour.depotLink() + " which does not exist in the injected Network");
        }
        return link;
    }
}
