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
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;
import org.matsim.contrib.dvrp.fleet.DvrpVehicleImpl;
import org.matsim.contrib.dvrp.fleet.ImmutableDvrpVehicleSpecification;
import org.matsim.contrib.dvrp.load.DvrpLoadType;
import org.matsim.contrib.dvrp.load.IntegerLoadType;
import org.matsim.contrib.dvrp.schedule.Task;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.router.costcalculators.OnlyTimeDependentTravelDisutility;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.trafficmonitoring.FreeSpeedTravelTime;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ModularTourScheduler")
class ModularTourSchedulerTest {

    /**
     * 4-node line network, all links drt-routable: n1 --start--> n2 --depot--> n3 --stop1--> n4
     * --stop2--> n3, plus a "depotRev" (n3 -> n2) link closing the loop back so the return leg
     * from stop2 to the depot has a real (non-trivial, 2-link) path to route. Every link length
     * is distinct so the excursionKmSplit test's expected metres cannot pass by an accidental
     * symmetry if deadhead/service were exchanged. Hand-computed distances (per
     * VrpPaths.calcDistance's "includes the to-link, not the from-link" convention, confirmed to
     * exist verbatim in dvrp 2025.0 - see report):
     * approach (start -> depot, adjacent at n2)   = length(depot)              = 800
     * return   (stop2 -> depot, via depotRev)     = length(depotRev)+length(depot) = 900+800=1700
     * deadhead total                                                          = 2500
     * stop1 leg (depot -> stop1, adjacent at n3)  = length(stop1)              = 600
     * stop2 leg (stop1 -> stop2, adjacent at n4)  = length(stop2)              = 700
     * service total                                                           = 1300
     */
    private Network network;
    private Id<Link> depotLink;
    private Id<Link> stopLink1;
    private Id<Link> stopLink2;
    private DvrpVehicle vehicle;
    private ModularTourScheduler scheduler;
    private DvrpLoadType loadType;

    @BeforeEach
    void setUp() {
        network = buildNetwork();
        depotLink = Id.createLinkId("depot");
        stopLink1 = Id.createLinkId("stop1");
        stopLink2 = Id.createLinkId("stop2");
        loadType = new IntegerLoadType("passengers");
        DrtTaskFactory taskFactory = new DrtTaskFactoryImpl();
        TravelTime travelTime = new FreeSpeedTravelTime();
        // VERIFY-SOURCE: the brief's fixture sketch names this "TimeAsTravelDisutility", but no
        // class of that name exists anywhere in matsim/dvrp/drt 2025.0 (checked every jar in the
        // local repo). The real class with this exact "time-only" semantics and a matching
        // single-TravelTime-arg constructor is OnlyTimeDependentTravelDisutility - see report.
        TravelDisutility travelDisutility = new OnlyTimeDependentTravelDisutility(travelTime);
        scheduler = new ModularTourScheduler(network, travelTime, travelDisutility, taskFactory, loadType);
        vehicle = fixtureVehicle(network.getLinks().get(Id.createLinkId("start")));
    }

    @Test
    @DisplayName("splices the full excursion chain and keeps the spike §2 invariants")
    void splicesFullChain() {
        ModularFreightTour tour = tour(depotLink, /*latestEnd*/ 21 * 3600.0,
                stop(stopLink1, 240.0, 2), stop(stopLink2, 360.0, 3));

        Optional<ModularTourScheduler.ScheduledExcursion> result =
                scheduler.schedule(vehicle, tour, /*now*/ 8 * 3600.0);

        assertThat(result).isPresent();
        List<? extends Task> tasks = vehicle.getSchedule().getTasks();
        // [0] initial STAY truncated to now (invariant: currently-executing trailing STAY is
        //     truncated, not removed)
        assertThat(tasks.get(0).getEndTime()).isEqualTo(8 * 3600.0);
        // chain: drive - swapOut - drive - stop1 - drive - stop2 - drive - swapBack - STAY
        assertThat(tasks.get(1).getTaskType()).isEqualTo(Modular.FREIGHT_DRIVE_TASK_TYPE);
        assertThat(tasks.get(2)).isInstanceOf(ModularCapacityChangeTask.class);
        assertThat(((ModularCapacityChangeTask) tasks.get(2)).isSwapBack()).isFalse();
        assertThat(((ModularCapacityChangeTask) tasks.get(2)).getChangedCapacity())
                .isEqualTo(loadType.getEmptyLoad());              // 0 seats during cargo phase
        assertThat(tasks.get(3).getTaskType()).isEqualTo(Modular.FREIGHT_DRIVE_TASK_TYPE);
        assertThat(tasks.get(4)).isInstanceOf(ModularFreightStopTask.class);
        assertThat(((ModularFreightStopTask) tasks.get(4)).getParcels()).isEqualTo(2);
        assertThat(tasks.get(6)).isInstanceOf(ModularFreightStopTask.class);
        assertThat(tasks.get(7).getTaskType()).isEqualTo(Modular.FREIGHT_DRIVE_TASK_TYPE);
        ModularCapacityChangeTask swapBack = (ModularCapacityChangeTask) tasks.get(8);
        assertThat(swapBack.isSwapBack()).isTrue();
        assertThat(swapBack.getChangedCapacity()).isEqualTo(vehicle.getCapacity()); // 10 seats restored
        // invariant: schedule ends with STAY reaching serviceEndTime
        Task last = tasks.get(tasks.size() - 1);
        assertThat(last).isInstanceOf(DrtStayTask.class);
        assertThat(last.getEndTime()).isEqualTo(vehicle.getServiceEndTime());
        // swap durations
        assertThat(tasks.get(2).getEndTime() - tasks.get(2).getBeginTime())
                .isEqualTo(Modular.RETOOLING_S);
        // stop dwell = jsprit service duration
        assertThat(tasks.get(4).getEndTime() - tasks.get(4).getBeginTime()).isEqualTo(240.0);
        // commitment predicate flips
        assertThat(Modular.hasUnperformedFreightTask(vehicle.getSchedule())).isTrue();
    }

    @Test
    @DisplayName("REJECTS (returns empty, schedule untouched) when completion would overrun the envelope")
    void rejectsOverrun() {
        // latestEnd barely after now -> even immediate dispatch cannot finish
        ModularFreightTour tour = tour(depotLink, /*latestEnd*/ 8 * 3600.0 + 60.0,
                stop(stopLink1, 240.0, 2));
        int before = vehicle.getSchedule().getTaskCount();

        assertThat(scheduler.schedule(vehicle, tour, 8 * 3600.0)).isEmpty();
        // NOT the native silent-drop: the schedule must be COMPLETELY unmodified
        assertThat(vehicle.getSchedule().getTaskCount()).isEqualTo(before);
        assertThat(vehicle.getSchedule().getTasks().get(0).getEndTime())
                .isEqualTo(vehicle.getServiceEndTime());
        assertThat(Modular.hasUnperformedFreightTask(vehicle.getSchedule())).isFalse();
    }

    @Test
    @DisplayName("vehicle already at the depot link: no zero-length approach drive is inserted")
    void vehicleAlreadyAtDepot() {
        // Same network/Network instance, so the vehicle's start link IS (by reference, not just
        // by id) the same object the scheduler resolves tour.depotLink() to - the realistic
        // "today" case the brief's defect note describes. A genuinely cross-Network-instance
        // fixture is not constructible here: MATSim's own ScheduleImpl.addTask enforces link
        // continuity via REFERENCE equality, so mutating the schedule with a foreign-but-same-id
        // link would make the framework itself throw, not the code under test - seeing this
        // through Optional/task-shape assertions is the mechanism available at this layer.
        Link atDepot = network.getLinks().get(depotLink);
        DvrpVehicle atDepotVehicle = fixtureVehicle(atDepot, "drt_2");

        ModularFreightTour tour = tour(depotLink, 21 * 3600.0, stop(stopLink1, 240.0, 2));
        Optional<ModularTourScheduler.ScheduledExcursion> result =
                scheduler.schedule(atDepotVehicle, tour, 8 * 3600.0);

        assertThat(result).isPresent();
        List<? extends Task> tasks = atDepotVehicle.getSchedule().getTasks();
        // truncated stay, then DIRECTLY the swap-out - no FREIGHT_DRIVE approach leg in between.
        assertThat(tasks).hasSize(7);
        assertThat(tasks.get(0).getEndTime()).isEqualTo(8 * 3600.0);
        assertThat(tasks.get(1)).isInstanceOf(ModularCapacityChangeTask.class);
        assertThat(((ModularCapacityChangeTask) tasks.get(1)).isSwapBack()).isFalse();
        assertThat(tasks.get(1).getBeginTime()).isEqualTo(8 * 3600.0);
        // still holds: trailing invariants
        Task last = tasks.get(tasks.size() - 1);
        assertThat(last).isInstanceOf(DrtStayTask.class);
        assertThat(last.getEndTime()).isEqualTo(atDepotVehicle.getServiceEndTime());
    }

    @Test
    @DisplayName("returned excursion carries the planned km split (deadhead vs service)")
    void excursionKmSplit() {
        ModularFreightTour tour = tour(depotLink, 21 * 3600.0,
                stop(stopLink1, 240.0, 2), stop(stopLink2, 360.0, 3));

        Optional<ModularTourScheduler.ScheduledExcursion> result =
                scheduler.schedule(vehicle, tour, 8 * 3600.0);

        assertThat(result).isPresent();
        ModularTourScheduler.ScheduledExcursion excursion = result.get();
        // hand-computed from the fixture's link lengths (see class javadoc): swapping the two
        // accumulators would turn 2500.0<->1300.0, so this is NOT a >0 sanity check.
        assertThat(excursion.deadheadMeters()).isEqualTo(2500.0);
        assertThat(excursion.serviceMeters()).isEqualTo(1300.0);

        List<? extends Task> tasks = vehicle.getSchedule().getTasks();
        Task swapBack = tasks.get(tasks.size() - 2); // one before the trailing stay
        assertThat(swapBack).isInstanceOf(ModularCapacityChangeTask.class);
        assertThat(((ModularCapacityChangeTask) swapBack).isSwapBack()).isTrue();
        assertThat(excursion.plannedCompletion()).isEqualTo(swapBack.getEndTime());
    }

    // --- fixture helpers ---

    private ModularFreightTour tour(Id<Link> depotLink, double latestEnd, ModularFreightTour.Stop... stops) {
        return new ModularFreightTour("dhl_t0", "dhl", 0, depotLink,
                /*plannedStart*/ 8 * 3600.0, /*plannedDuration*/ 0.0, latestEnd, List.of(stops));
    }

    private ModularFreightTour.Stop stop(Id<Link> link, double serviceDuration, int parcels) {
        return new ModularFreightTour.Stop(link, serviceDuration, parcels);
    }

    /**
     * n1(0,0) --start(500m)--> n2 --depot(800m)--> n3 --stop1(600m)--> n4 --stop2(700m)--> n3,
     * plus n3 --depotRev(900m)--> n2 so the return leg (stop2 -> depot) has a real path.
     * Freespeed uniform (10 m/s) so travel times never dominate/underflow anything the tests
     * assert on (only distances and task structure are asserted exactly; travel time is not).
     */
    private Network buildNetwork() {
        Network net = NetworkUtils.createNetwork();
        NetworkFactory f = net.getFactory();
        Node n1 = f.createNode(Id.createNodeId("n1"), new Coord(0, 0));
        Node n2 = f.createNode(Id.createNodeId("n2"), new Coord(1000, 0));
        Node n3 = f.createNode(Id.createNodeId("n3"), new Coord(2000, 0));
        Node n4 = f.createNode(Id.createNodeId("n4"), new Coord(3000, 0));
        net.addNode(n1);
        net.addNode(n2);
        net.addNode(n3);
        net.addNode(n4);
        addLink(net, "start", n1, n2, 500);
        addLink(net, "depot", n2, n3, 800);
        addLink(net, "depotRev", n3, n2, 900);
        addLink(net, "stop1", n3, n4, 600);
        addLink(net, "stop2", n4, n3, 700);
        return net;
    }

    private void addLink(Network net, String id, Node from, Node to, double length) {
        NetworkFactory f = net.getFactory();
        Link link = f.createLink(Id.createLinkId(id), from, to);
        link.setLength(length);
        link.setFreespeed(10.0);
        link.setCapacity(1800);
        link.setNumberOfLanes(1);
        net.addLink(link);
    }

    /** Vehicle with an initial 0..86400 STAY on {@code link}, STARTED (current = that STAY) so
     *  the scheduler's C3 idle-vehicle precondition is satisfied; capacity 10 (passenger seats). */
    private DvrpVehicle fixtureVehicle(Link link) {
        return fixtureVehicle(link, "drt_1");
    }

    private DvrpVehicle fixtureVehicle(Link link, String id) {
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
}
