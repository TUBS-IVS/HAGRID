package hagrid.integrated.modular;

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
import org.matsim.core.router.speedy.SpeedyALTFactory;
import org.matsim.core.router.util.LeastCostPathCalculator;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Splices a freight excursion onto a live DRT schedule at dispatch time - the generalisation
 * of drt-extensions' ServiceTaskSchedulerImpl from 1 stop to N (spike §2). Invariants held:
 * (1) a DRT schedule always ends with a STAY task; (2) the currently-executing trailing STAY
 * is TRUNCATED, a pending one REMOVED; (3) never schedule past the completion envelope - and
 * unlike the native template, infeasibility is an EXPLICIT Optional.empty() with the schedule
 * untouched, never a silent no-op (spike §2 invariant + design §3.3). Feasibility is decided by
 * routing the WHOLE chain (approach + swap + every stop leg + return + swap-back) before a
 * single task is added or the trailing STAY is touched, so a rejection leaves the schedule
 * byte-identical to before the call.
 *
 * <p>Precondition (plan C3): the vehicle is IDLE (current task == trailing STAY). The dispatcher
 * only selects idle vehicles; the assert makes a violated assumption loud instead of corrupt.
 * The template's divert-from-RELOCATE branch is therefore unreachable and NOT implemented here.
 */
public class ModularTourScheduler {

    /** Planned-km split of one dispatched excursion (KPI payload for the DISPATCHED event). */
    public record ScheduledExcursion(double deadheadMeters, double serviceMeters,
                                     double plannedCompletion) {}

    private final Network network;
    private final TravelTime travelTime;
    private final LeastCostPathCalculator router;
    private final DrtTaskFactory taskFactory;
    private final DvrpLoadType loadType;

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
            throw new IllegalStateException("Modular dispatch expects an idle vehicle (trailing"
                    + " STAY running), got " + schedule.getCurrentTask() + " on " + vehicle.getId());
        }

        Link depot = network.getLinks().get(tour.depotLink());
        double departure = Math.max(lastStay.getBeginTime(), now);

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
        if (!from.getId().equals(depot.getId())) {
            approach = VrpPaths.calcAndCreatePath(from, depot, t, router, travelTime);
            t = approach.getArrivalTime();
            deadhead += VrpPaths.calcDistance(approach);
        }
        double swapOutBegin = t;
        t += Modular.RETOOLING_S;

        Link prev = depot;
        List<VrpPathWithTravelData> stopLegs = new ArrayList<>();
        for (ModularFreightTour.Stop stop : tour.stops()) {
            Link stopLink = network.getLinks().get(stop.link());
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

        double envelope = Math.min(tour.latestEnd(), vehicle.getServiceEndTime());
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
                swapOutBegin + Modular.RETOOLING_S, depot, loadType.getEmptyLoad(),
                tour.tourId(), false));

        for (int i = 0; i < tour.stops().size(); i++) {
            ModularFreightTour.Stop stop = tour.stops().get(i);
            VrpPathWithTravelData leg = stopLegs.get(i);
            schedule.addTask(taskFactory.createDriveTask(vehicle, leg,
                    Modular.FREIGHT_DRIVE_TASK_TYPE));
            double arrive = leg.getArrivalTime();
            schedule.addTask(new ModularFreightStopTask(arrive, arrive + stop.serviceDuration(),
                    network.getLinks().get(stop.link()), stop.parcels(), tour.tourId(), i));
        }
        schedule.addTask(taskFactory.createDriveTask(vehicle, back, Modular.FREIGHT_DRIVE_TASK_TYPE));
        schedule.addTask(new ModularCapacityChangeTask(swapBackBegin, completion, depot,
                vehicle.getCapacity(), tour.tourId(), true));
        schedule.addTask(taskFactory.createStayTask(vehicle, completion,
                Math.max(vehicle.getServiceEndTime(), completion), depot));

        return Optional.of(new ScheduledExcursion(deadhead, service, completion));
    }
}
