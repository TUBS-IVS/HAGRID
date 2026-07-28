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
import java.util.List;
import java.util.Optional;
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
                LOG.warn("Modular tour {} expired pending at {} (latestEnd {}).",
                        t.tourId(), now, t.latestEnd());
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
                        excursion.get().deadheadMeters(), excursion.get().serviceMeters()));
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
        Coord depot = network.getLinks().get(tour.depotLink()).getToNode().getCoord();
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
}
