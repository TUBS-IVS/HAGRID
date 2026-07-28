package hagrid.integrated.drt;

import hagrid.integrated.freight.LausitzFreightPreprocessor;
import hagrid.integrated.freight.LmdTestShapefiles;
import hagrid.integrated.modular.Modular;
import hagrid.integrated.modular.ModularDispatchModule;
import hagrid.integrated.modular.ModularFreightTour;
import hagrid.integrated.modular.ModularTourConverter;
import hagrid.integrated.modular.ModularTourEvent;
import hagrid.integrated.modular.ModularVehicleTypes;
import hagrid.simulation.DrtScenarioBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.events.handler.PersonEntersVehicleEventHandler;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.common.zones.systems.grid.square.SquareGridZoneSystemParams;
import org.matsim.contrib.drt.optimizer.rebalancing.RebalancingParams;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.contrib.drt.scheduler.EmptyVehicleRelocator;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.controler.Controler;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;
import org.matsim.core.events.handler.BasicEventHandler;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.algorithms.TransportModeNetworkFilter;
import org.matsim.core.network.io.NetworkWriter;
import org.matsim.core.population.PopulationUtils;
import org.matsim.freight.carriers.CarrierVehicleTypeWriter;
import org.matsim.freight.carriers.CarrierVehicleTypes;
import org.matsim.freight.carriers.Carriers;
import org.matsim.testcases.MatsimTestUtils;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end proof of DRT_MODULAR (U-Shift capsule swap, 1d Task 10) — the FIRST run of the whole
 * Tasks 3-9 stack inside a real MATSim mobsim. Everything before this task was unit-tested in
 * isolation; this run is the only evidence that the Guice composition
 * ({@link ModularDispatchModule}) resolves, that the spliced freight tasks are executable by the
 * DRT {@code DrtActionCreator}/mobsim at all, and that the splicer and HAGRID's
 * {@link ReturnToDepotRebalancingModule} can both append to the same schedule tail without
 * corrupting it.
 *
 * <p>Fixture recipe: {@code MarriedBaselineEndToEndTest} (grid network, 5 pax + 1 pt agent, square
 * shapefile, single {@code dhl} depot at 500/500, one van type, {@code LmdTestShapefiles.writeDemand}
 * with 6 dhl parcels over 2 drop points, {@code LausitzDrtPreprocessor.run} with capacity 10) — with
 * the freight half switched from {@code LausitzFreightPreprocessor.run} to {@code runModular} (216-parcel
 * capsule type, 3.5 h tour cap, full-delivery-day window, no dispatch waves).</p>
 *
 * <p><b>What each assertion actually discriminates</b> (a repeated failure mode in this plan is a
 * literal test sketch that cannot fail for the reason it names):</p>
 * <ul>
 *   <li>(a) the run completing is not decoration: an incorrectly wired {@code Network} instance
 *       leaves a half-spliced schedule and {@code ScheduleImpl.addTask} throws; a rebalancing
 *       relocation landing on a spliced tail makes
 *       {@code EmptyVehicleRelocator.relocateVehicleImpl} throw ("The current STAY task is not
 *       last"); a double-registered {@code DrtOptimizer} QSim component double-drives dispatch.</li>
 *   <li>(b) the KPI identities alone are satisfiable by an all-zero CSV, so
 *       {@code tours_planned} and {@code parcels_planned} are additionally pinned against
 *       ground truth computed HERE from the converted tour list, and the physical-progress
 *       metrics ({@code tours_completed}, {@code parcels_served}, {@code swaps_completed},
 *       {@code freight_vehicle_hours}) are asserted strictly positive.</li>
 *   <li>(c) the "no carriers" predicate is the exact one
 *       {@code MarriedBaselineEndToEndTest} asserts POSITIVE for a run that does install
 *       {@code CarrierModule} — so it is known to fire when carriers run. Backed up by asserting
 *       the scenario carries no {@code "carriers"} scenario element at all (the container
 *       {@code CarrierModule} needs), i.e. the double-delivery channel does not merely produce no
 *       output, it does not exist.</li>
 *   <li>(d) {@code phase=COMPLETED} is emitted ONLY when a swap-BACK task is observed as performed
 *       (Task 7 {@code observeTaskTransition}), so it cannot appear unless a full excursion ran to
 *       the end. Reinforced with the native {@code dvrpTaskStarted} stream: the mobsim really
 *       started {@code MODULAR_FREIGHT_DRIVE} and {@code MODULAR_FREIGHT_STOP} tasks, and the same
 *       VEHICLE also had a {@code RELOCATE} task appended to its tail — the coexistence
 *       requirement (design §6) in its literal "same schedule tail" form, not merely "both
 *       mechanisms ran somewhere in this run".</li>
 *   <li>(e, added beyond the brief) the D2 strict lockout under REAL contention. The shared
 *       {@code DrtE2eFixtures} demand departs at 08:00-08:04, i.e. after the excursion is already
 *       over, so this test adds five {@code person_c*} agents departing INSIDE the excursion window.
 *       Without them "the committed vehicle served no passenger" is vacuously true — there would be
 *       no passenger request to serve. Task 8 unit-tests {@code ModularEntryFactory} in isolation;
 *       this is the only place the lockout meets a live insertion search, and the failure it guards
 *       against (a passenger stop spliced into the middle of a freight chain) is precisely the kind
 *       only a mobsim run can surface.</li>
 * </ul>
 *
 * <p><b>Three deliberate deviations from the brief's staging sketch</b>, all made because the
 * brief's literal values cannot exercise the property the brief asks this test to prove:</p>
 * <ol>
 *   <li>the rebalancing zone cell size is shrunk from the composed 2000 m to 300 m. The fixture
 *       network spans 100..1000 m, so at 2000 m the ENTIRE network is ONE zone and MinCostFlow can
 *       never emit a relocation at all — which would make the coexistence assertion vacuous. This
 *       is the load-bearing deviation: with 300 m the run really does relocate.</li>
 *   <li>{@code returnStart} is 10:00, not 84600 (23:30), so BOTH branches of
 *       {@link ReturnToDepotTargetCalculator} are exercised against live freight schedules — the
 *       demand-based branch during the morning (when the excursion is in flight) and the
 *       depot-targeting branch afterwards. At 84600 the depot branch would engage 30 min before
 *       service end, when nothing is left to interact with.</li>
 *   <li>five extra {@code person_c*} passenger agents are added to the loaded POPULATION, departing
 *       inside the excursion window — the largest of the three deviations, and the reason assertion
 *       (e) below is not vacuous. The shared {@code DrtE2eFixtures.buildDemand()} agents depart
 *       08:00-08:04, after an excursion that ends ~07:48, so the stock fixture leaves the fleet
 *       entirely uncontended for the whole excursion. {@code DrtE2eFixtures} itself is shared with
 *       the other DRT e2e suites and is NOT touched; the agents are created here (see
 *       {@link #addContentionPaxPerson}) and mirror the fixture's own agents exactly.</li>
 * </ol>
 * No deviation changes WHAT is proven; all three are what make the named properties observable.
 */
@DisplayName("DRT_MODULAR end-to-end run (capsule swap, 1d Task 10)")
class ModularEndToEndTest {

    @RegisterExtension
    public MatsimTestUtils utils = new MatsimTestUtils();

    /** Service-area square: 0..2000. All fixture nodes are inside (100..1000). */
    private static final double AREA_SIZE = 2000.0;
    private static final int FLEET_SIZE = 4;
    /** 1d baseline seat count (the capsule swap trades these seats for cargo volume). */
    private static final int PAX_CAPACITY = 10;
    /** SHORT literal run id: keeps the native DrtZonalWaitTimesAnalyzer's shutdown geopackage path
     *  well under the Windows ~255-char limit (see SharedUseEndToEndTest.java:105-113). */
    private static final String RUN_ID = "MODULAR_E2E";
    /** Depot pull start — see deviation (2) in the class javadoc. Must be a multiple of the 1800 s
     *  rebalancing interval, else no rebalancing step ever falls in the depot-targeting window. */
    private static final double RETURN_START_S = 10 * 3600.0;
    /** Must equal the composed config's demandEstimationPeriod, else ZonalDemandEstimator's
     *  estimationPeriod==timeBinSize precondition fails (SharedUseRebalTest.java:129-134). */
    private static final double DEMAND_ESTIMATION_PERIOD_S = 1800.0;
    /** See deviation (1) in the class javadoc. */
    private static final double REBAL_CELL_SIZE_M = 300.0;
    /** theta=0: the gate opens whenever ANY vehicle is idle -> deterministic dispatch at submission. */
    private static final double IDLE_THRESHOLD = 0.0;
    /** The two task types {@link EmptyVehicleRelocator} appends — the rebalancing side of the
     *  design §6 coexistence requirement (demand-based by day, depot-targeting after returnStart). */
    private static final Set<String> RELOCATE_TASK_TYPES = Set.of(
            EmptyVehicleRelocator.RELOCATE_VEHICLE_TASK_TYPE.name(),
            EmptyVehicleRelocator.RELOCATE_VEHICLE_TO_DEPOT_TASK_TYPE.name());

    @Test
    @DisplayName("capsules swap, parcels leave on DRT vehicles, KPI CSV conserves, no carriers")
    void runsModularThroughOneIteration() throws Exception {
        Path dir = Path.of(utils.getOutputDirectory()).toAbsolutePath();
        Files.createDirectories(dir);

        // ---- shared raw fixtures (identical to MarriedBaselineEndToEndTest) ----
        Network rawNet = DrtE2eFixtures.buildGrid();
        Path rawNetFile = dir.resolve("raw_network.xml.gz");
        new NetworkWriter(rawNet).write(rawNetFile.toString());
        Path rawPlansFile = dir.resolve("raw_plans.xml.gz");
        PopulationUtils.writePopulation(DrtE2eFixtures.buildDemand(), rawPlansFile.toString());
        Path shpFile = dir.resolve("service-area.shp");
        DrtE2eFixtures.writeSquareShapefile(shpFile, AREA_SIZE);
        Path depotCsv = dir.resolve("depots.csv");
        Files.writeString(depotCsv, "provider;x;y\ndhl;500.0;500.0\n");

        // ---- freight side: van type (cost donor for the capsule) + tiny PANDA-like demand ----
        CarrierVehicleTypes types = new CarrierVehicleTypes();
        VehicleType van = VehicleUtils.createVehicleType(Id.create("ct_cep_size_m", VehicleType.class));
        van.getCapacity().setOther(165);
        van.setNetworkMode("car");
        van.getCostInformation().setCostsPerMeter(0.0004).setCostsPerSecond(0.0).setFixedCost(170.0);
        types.getVehicleTypes().put(van.getId(), van);
        Path typesFile = dir.resolve("vans.xml");
        new CarrierVehicleTypeWriter(types).write(typesFile.toString());

        Path demandShp = dir.resolve("demand.shp");
        LmdTestShapefiles.writeDemand(demandShp,
                new double[][]{{300, 200}, {800, 600}},
                new long[]{3, 2},    // dhl B2C parcels
                new long[]{1, 0},    // dhl B2B parcels
                new long[]{0, 0});   // hermes: none

        // ---- offline jsprit half: runModular (capsule type, 3.5 h cap, no waves) ----
        Path carriersOut = dir.resolve("modular_carriers_routed.xml");
        LausitzFreightPreprocessor.runModular(demandShp.toString(), depotCsv.toString(),
                rawNetFile.toString(), typesFile.toString(), carriersOut.toString(),
                /*jspritIterations*/ 1, shpFile.toString(), Modular.DEFAULT_MAX_TOUR_DURATION_S);
        assertThat(carriersOut).exists();

        // ---- DRT side: production preprocessor (drt-tagged net, person plans, fleet) ----
        Path drtNetFile = dir.resolve("drt_network.xml.gz");
        Path clippedPlans = dir.resolve("clipped_plans.xml.gz");
        Path fleetFile = dir.resolve("fleet.xml.gz");
        LausitzDrtPreprocessor.run(
                rawNetFile.toString(), rawPlansFile.toString(), shpFile.toString(),
                depotCsv.toString(), drtNetFile.toString(), clippedPlans.toString(),
                fleetFile.toString(), FLEET_SIZE, PAX_CAPACITY,
                /*serviceBegin*/ 0.0, /*serviceEnd*/ 86400.0);

        URL cfgUrl = getClass().getClassLoader().getResource("lausitz-native-like.config.xml");
        assertThat(cfgUrl)
                .as("test fixture lausitz-native-like.config.xml must be on the test classpath")
                .isNotNull();
        Path matsimOut = dir.resolve("matsim");

        Scenario scenario = DrtScenarioBuilder.build(cfgUrl.toString(), drtNetFile.toString(),
                clippedPlans.toString(), shpFile.toString(), fleetFile.toString(),
                matsimOut.toString(), RUN_ID, /*lastIteration*/ 1);

        // ---- tours: read the routed carriers, convert against the car + DRT networks ----
        Carriers routed = ModularTourConverter.read(carriersOut.toString(),
                ModularVehicleTypes.createCapsuleTypes(typesFile.toString()));
        Network carNet = LausitzFreightPreprocessor.carNetwork(
                NetworkUtils.readNetwork(rawNetFile.toString()));
        // Exactly how DvrpGlobalRoutingNetworkProvider builds the modal DVRP network the fleet's
        // Link references come from (TransportModeNetworkFilter on the dvrp networkModes, NO
        // cleaning) - so the tour link ids the splicer resolves are the injected network's own.
        Network drtNet = NetworkUtils.createNetwork();
        new TransportModeNetworkFilter(NetworkUtils.readNetwork(drtNetFile.toString()))
                .filter(drtNet, Set.of(TransportMode.drt));
        List<ModularFreightTour> tours = ModularTourConverter.convert(routed, carNet, drtNet);
        // EXACTLY one tour, not merely "at least one" (Task 10 review, Item 4): the excursion-window
        // collection below (excursionStart/End/Vehicle) is last-wins across the modularTour event
        // stream, which is correct for one tour but with two would let the "window" span both tours
        // or invert, silently pinning the coexistence and D2 assertions to the wrong vehicle over
        // the wrong interval. With this fixture (6 parcels, 216-parcel capsule, 3.5 h cap) jsprit
        // always produces one tour; if a future fixture change makes it two, this fails HERE with a
        // clear reason instead of degrading the two assertions further down.
        assertThat(tours)
                .as("this fixture must yield exactly one dispatchable tour - the excursion-window"
                        + " collection below assumes it (see comment)")
                .hasSize(1);
        // Ground truth for (b), computed independently of anything the run writes.
        int expectedTours = tours.size();
        int expectedParcels = tours.stream().mapToInt(ModularFreightTour::totalParcels).sum();
        assertThat(expectedParcels).isGreaterThanOrEqualTo(1);

        // Contention agents (see the D2-lockout paragraph in the class javadoc): the shared
        // DrtE2eFixtures demand departs at 08:00-08:04, i.e. AFTER the excursion is over, so
        // without these the run would contain no passenger request at all while a vehicle is on
        // freight - and the lockout assertions below would be vacuously true. These depart inside
        // the excursion window. Added to the loaded population BEFORE `new Controler`; they reuse
        // the fixture's activity types and "person" subpopulation, so the configurator's scoring
        // params and replanning strategies already cover them.
        for (int i = 0; i < 5; i++) {
            addContentionPaxPerson(scenario, "person_c" + i, 7 * 3600.0 + 20 * 60.0 + i * 120.0);
        }

        DrtConfigGroup drtCfg = MultiModeDrtConfigGroup.get(scenario.getConfig())
                .getModalElements().iterator().next();
        // Deviation (1), see class javadoc. Config mutation MUST precede `new Controler(scenario)`:
        // the DRT config groups are read at controler construction time.
        RebalancingParams rebalancing = drtCfg.getRebalancingParams().orElseThrow(
                () -> new AssertionError("composeConfig must install RebalancingParams"));
        ((SquareGridZoneSystemParams) rebalancing.getZoneSystemParams()).setCellSize(REBAL_CELL_SIZE_M);

        Controler controler = new Controler(scenario);
        // 5-arg installModules -> ReturnToDepotRebalancingModule ACTIVE: this e2e must prove the
        // splicer and the return-to-depot pull coexist on the same schedule tail (design §6).
        DrtConfigComposer.installModules(controler, List.of(new Coord(500.0, 500.0)),
                RETURN_START_S, /*perDepotCapacity*/ 4.0, DEMAND_ESTIMATION_PERIOD_S);
        // LAST overriding module, after installModules (the SharedUseModule ordering).
        controler.addOverridingModule(new ModularDispatchModule(drtCfg, tours, IDLE_THRESHOLD));

        // ---- (a) the run completes: Guice / validator / runtime / schedule-integrity proof ----
        controler.run();

        // ---- (b) run-ID-prefixed KPI CSV exists and conserves ----
        Path csv = matsimOut.resolve(RUN_ID + ".modular_tour_stats.csv");
        assertThat(csv).as("KPI CSV must be written under the run-ID prefix (1c bug 89f1ee5)").exists();
        Map<String, Double> stats = readMetricCsv(csv);

        // Pinned against ground truth: an all-zero CSV (which would satisfy every identity below)
        // fails here.
        assertThat(stats.get("tours_planned")).as("tours_planned").isEqualTo((double) expectedTours);
        assertThat(stats.get("parcels_planned")).as("parcels_planned")
                .isEqualTo((double) expectedParcels);
        // Physical progress: the fleet really executed excursions.
        assertThat(stats.get("tours_dispatched")).as("tours_dispatched").isGreaterThanOrEqualTo(1.0);
        assertThat(stats.get("tours_completed")).as("tours_completed").isGreaterThanOrEqualTo(1.0);
        assertThat(stats.get("parcels_served")).as("parcels_served").isGreaterThanOrEqualTo(1.0);
        assertThat(stats.get("swaps_completed")).as("swaps_completed (swap-out + swap-back)")
                .isGreaterThanOrEqualTo(2.0);
        assertThat(stats.get("freight_vehicle_hours")).as("freight_vehicle_hours").isGreaterThan(0.0);
        assertThat(stats.get("service_km_planned")).as("service_km_planned").isGreaterThan(0.0);
        // retooling_hours is derived from swaps_completed: cross-check the derivation, not a copy.
        assertThat(stats.get("retooling_hours")).as("retooling_hours == swaps * 420 s")
                .isEqualTo(stats.get("swaps_completed") * Modular.RETOOLING_S / 3600.0);

        // Identities 1 and 3 are tautological by construction (pending_eod is a residual) - kept as
        // executable documentation. Identities 2 and 4 are the ones with real power: both sides are
        // counted independently by ModularKpiHandler.
        assertThat(stats.get("tours_planned")).as("identity 1")
                .isEqualTo(stats.get("tours_expired_pending") + stats.get("tours_dispatched")
                        + stats.get("tours_pending_eod"));
        assertThat(stats.get("tours_dispatched")).as("identity 2")
                .isEqualTo(stats.get("tours_completed") + stats.get("tours_dispatched_incomplete"));
        assertThat(stats.get("parcels_planned")).as("identity 3")
                .isEqualTo(stats.get("parcels_expired_pending") + stats.get("parcels_dispatched")
                        + stats.get("parcels_pending_eod"));
        assertThat(stats.get("parcels_dispatched")).as("identity 4")
                .isEqualTo(stats.get("parcels_served") + stats.get("parcels_dispatched_unserved"));
        assertThat(stats.get("delta_parcels")).as("identity 5")
                .isEqualTo(stats.get("parcels_planned") - stats.get("parcels_served"));

        // ---- (c) NO CarrierModule output — the double-delivery guard (design §3.4) ----
        // Same predicate MarriedBaselineEndToEndTest asserts POSITIVE for a carrier run, so it is
        // known to fire when the CarrierModule is installed.
        List<String> carrierOutputs = new ArrayList<>();
        try (var files = Files.walk(matsimOut)) {
            files.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.contains("output_carriers"))
                    .forEach(carrierOutputs::add);
        }
        assertThat(carrierOutputs)
                .as("DRT_MODULAR must never run the CarrierModule - carriers would deliver every"
                        + " parcel a SECOND time and silently double every freight KPI")
                .isEmpty();
        // Stronger than "no output": the container CarrierModule needs was never added at all.
        // (CarriersUtils' element name constant is private; the literal is verified against
        // CarriersUtils.java:61 `private static final String CARRIERS = "carriers"`.)
        assertThat(scenario.getScenarioElement("carriers"))
                .as("no 'carriers' scenario element - the second delivery channel does not exist")
                .isNull();

        // ---- (d) events: the modular tour phases + the native task stream ----
        Path eventsFile = findFile(matsimOut, "output_events.xml.gz");
        Set<String> modularPhases = new LinkedHashSet<>();
        Set<String> modularTourIds = new LinkedHashSet<>();
        Set<String> startedTaskTypes = new LinkedHashSet<>();
        Set<String> freightVehicles = new LinkedHashSet<>();
        // RELOCATE start times per vehicle, NOT just the set of relocated vehicles (Task 10 review,
        // Finding 1): the coexistence claim is DIRECTIONAL - the rebalancer appending onto the tail
        // the SPLICER created - and a vehicle-identity-only check is satisfied identically by a
        // RELOCATE that happened BEFORE the dispatch, which is the other direction.
        Map<String, List<Double>> relocateTimesByVehicle = new LinkedHashMap<>();
        int[] swapDoneCount = {0};
        // D2 lockout: the excursion window is only known once every event is read, so the
        // passenger-service events are collected here and filtered against the window afterwards.
        double[] excursionStart = {Double.NaN};
        double[] excursionEnd = {Double.NaN};
        String[] excursionVehicle = {null};
        List<Double> paxRequestTimes = new ArrayList<>();
        List<Map.Entry<Double, String>> paxServiceEvents = new ArrayList<>();
        AtomicBoolean paxBoarded = new AtomicBoolean(false);
        EventsManager events = EventsUtils.createEventsManager();
        // Custom / DVRP event types have no reader mapping here, so MatsimEventsReader hands them
        // over as GenericEvent (EventsReaderXMLv1.java:244-245) - which a BasicEventHandler sees
        // with its attributes intact. That is the point of Task 3 writing every field into
        // getAttributes(): the events file, not an in-memory handler, is the evidence.
        events.addHandler((BasicEventHandler) event -> {
            if (ModularTourEvent.EVENT_TYPE.equals(event.getEventType())) {
                String phase = event.getAttributes().get("phase");
                modularPhases.add(phase);
                modularTourIds.add(event.getAttributes().get("tourId"));
                if (ModularTourEvent.Phase.SWAP_DONE.name().equals(phase)) {
                    swapDoneCount[0]++;
                }
                if (ModularTourEvent.Phase.DISPATCHED.name().equals(phase)) {
                    excursionStart[0] = event.getTime();
                    excursionVehicle[0] = event.getAttributes().get("vehicle");
                } else if (ModularTourEvent.Phase.COMPLETED.name().equals(phase)) {
                    excursionEnd[0] = event.getTime();
                }
            } else if ("dvrpTaskStarted".equals(event.getEventType())) {
                String taskType = event.getAttributes().get("taskType");
                String vehicle = event.getAttributes().get("dvrpVehicle");
                startedTaskTypes.add(taskType);
                if (taskType.startsWith("MODULAR_FREIGHT_")) {
                    freightVehicles.add(vehicle);
                } else if (RELOCATE_TASK_TYPES.contains(taskType)) {
                    relocateTimesByVehicle.computeIfAbsent(vehicle, k -> new ArrayList<>())
                            .add(event.getTime());
                }
            } else if ("DrtRequest submitted".equals(event.getEventType())) {
                paxRequestTimes.add(event.getTime());
            } else if ("passenger picked up".equals(event.getEventType())
                    || "passenger dropped off".equals(event.getEventType())) {
                paxServiceEvents.add(Map.entry(event.getTime(),
                        event.getAttributes().get("vehicle")));
            }
        });
        events.addHandler((PersonEntersVehicleEventHandler) event -> {
            if (event.getPersonId().toString().startsWith("person_")
                    && event.getVehicleId().toString().startsWith("drt_")) {
                paxBoarded.set(true);
            }
        });
        new MatsimEventsReader(events).readFile(eventsFile.toString());

        assertThat(modularPhases)
                .as("every tour phase must round-trip through output_events.xml.gz; COMPLETED is"
                        + " emitted ONLY when a swap-BACK task is observed performed, so it cannot"
                        + " appear unless a full excursion ran to the end")
                .contains(ModularTourEvent.Phase.PLANNED.name(),
                        ModularTourEvent.Phase.DISPATCHED.name(),
                        ModularTourEvent.Phase.SWAP_DONE.name(),
                        ModularTourEvent.Phase.STOP_SERVED.name(),
                        ModularTourEvent.Phase.COMPLETED.name());
        assertThat(swapDoneCount[0]).as("both capsule swaps (out + back) performed").isGreaterThanOrEqualTo(2);
        assertThat(modularTourIds)
                .as("event tour ids must be the converted tours' ids")
                .isSubsetOf(tours.stream().map(ModularFreightTour::tourId).toList());

        // The mobsim really executed the spliced tasks (not just: the schedule contained them).
        assertThat(startedTaskTypes)
                .as("the mobsim must have started the spliced freight drive and stop tasks")
                .contains(Modular.FREIGHT_DRIVE_TASK_TYPE.name(), Modular.FREIGHT_STOP_TASK_TYPE.name());
        // The excursion window, needed by both the coexistence and the D2 assertions below.
        assertThat(excursionVehicle[0]).as("DISPATCHED event must name the vehicle").isNotNull();
        assertThat(excursionStart[0]).as("excursion start").isNotNaN();
        assertThat(excursionEnd[0]).as("excursion end").isNotNaN();
        // Cross-check: the vehicle the DISPATCHED event names is the vehicle that really executed
        // the freight tasks (the KPI event stream and the native task stream must agree).
        assertThat(freightVehicles)
                .as("the DISPATCHED event's vehicle must be the one that ran the freight tasks")
                .containsExactly(excursionVehicle[0]);

        // ---- design §6 coexistence, DIRECTIONALLY (Task 10 review, Finding 1) -----------------
        // The claim is not "both mechanisms ran somewhere in this run" - that is satisfied by a
        // RELOCATE that happened BEFORE the dispatch, which is the opposite direction. What must be
        // pinned is that the rebalancer appended onto the trailing STAY the SPLICER created: once
        // the swap-back is performed the vehicle idles on that stay, which makes it a rebalancing
        // candidate again, and that is exactly the boundary where
        // EmptyVehicleRelocator.relocateVehicleImpl would throw ("The current STAY task is not
        // last") - or cast-fail on a ModularFreightStopTask - if the lockout logic were wrong.
        assertThat(startedTaskTypes)
                .as("the rebalancing side must have appended at least one RELOCATE task in this run,"
                        + " else splicer/return-to-depot coexistence is not actually exercised")
                .containsAnyElementsOf(RELOCATE_TASK_TYPES);
        List<Double> relocatesAfterExcursion = relocateTimesByVehicle
                .getOrDefault(excursionVehicle[0], List.of()).stream()
                .filter(t -> t > excursionEnd[0])
                .toList();
        assertThat(relocatesAfterExcursion)
                .as("vehicle %s must be relocated AFTER its excursion ended at %s - i.e. the"
                        + " rebalancer appended to the trailing STAY the splicer itself created."
                        + " All relocate times for that vehicle: %s",
                        excursionVehicle[0], excursionEnd[0],
                        relocateTimesByVehicle.get(excursionVehicle[0]))
                .isNotEmpty();

        // Passenger primacy: withdrawing a vehicle for freight must not break pax service.
        assertThat(paxBoarded.get())
                .as("expected >=1 person_* agent to physically board a drt_* vehicle").isTrue();

        // ---- D2 strict lockout, under real contention -----------------------------------------
        // Task 8 unit-tests ModularEntryFactory in isolation; this is the only place the lockout is
        // exercised against a live insertion search. First: contention really existed (otherwise
        // the second assertion is vacuously true).
        //
        // What this pair does NOT establish (do not over-read it): that the insertion search WOULD
        // have chosen the committed vehicle had ModularEntryFactory not excluded it. Proving the
        // counterfactual would need a control run with the lockout removed, which no e2e can do from
        // inside one Controler. "No passenger was served by the committed vehicle while a real
        // request queue existed" is the strongest statement available at this level; the exclusion
        // mechanism itself is what ModularEntryFactoryTest covers directly.
        long requestsDuringExcursion = paxRequestTimes.stream()
                .filter(t -> t >= excursionStart[0] && t <= excursionEnd[0])
                .count();
        assertThat(requestsDuringExcursion)
                .as("the contention agents must submit drt requests WHILE a vehicle is on freight,"
                        + " else the lockout assertion below proves nothing")
                .isGreaterThanOrEqualTo(1);
        // Second: the committed vehicle served no passenger for the whole excursion. Without the
        // lockout the insertion search could pick it, splicing a passenger stop into the freight
        // chain (or crashing the mobsim on the reference-continuity check).
        List<Map.Entry<Double, String>> paxOnFreightVehicle = paxServiceEvents.stream()
                .filter(e -> excursionVehicle[0].equals(e.getValue()))
                .filter(e -> e.getKey() >= excursionStart[0] && e.getKey() <= excursionEnd[0])
                .toList();
        assertThat(paxOnFreightVehicle)
                .as("D2 strict lockout: vehicle %s must serve NO passenger between %s and %s",
                        excursionVehicle[0], excursionStart[0], excursionEnd[0])
                .isEmpty();
    }

    // ------------------------------------------------------------------ helpers

    /**
     * One extra passenger agent departing INSIDE the freight-excursion window, so the D2 lockout is
     * tested against a live insertion search rather than against an empty request queue. Mirrors
     * {@code DrtE2eFixtures.buildDemand}'s agents exactly (same "person" subpopulation, same
     * {@code home_57600}/{@code work_28800} typed activities, same coords) so no additional scoring
     * or replanning configuration is required.
     */
    private static void addContentionPaxPerson(Scenario scenario, String id, double departureTime) {
        var pf = scenario.getPopulation().getFactory();
        var person = pf.createPerson(Id.createPersonId(id));
        PopulationUtils.putSubpopulation(person, "person");
        var plan = pf.createPlan();
        var home = pf.createActivityFromCoord("home_57600", new Coord(150, 150));
        home.setEndTime(departureTime);
        plan.addActivity(home);
        plan.addLeg(pf.createLeg(TransportMode.drt));
        plan.addActivity(pf.createActivityFromCoord("work_28800", new Coord(950, 950)));
        person.addPlan(plan);
        person.setSelectedPlan(plan);
        scenario.getPopulation().addPerson(person);
    }

    private static Path findFile(Path root, String suffix) throws IOException {
        try (var s = Files.walk(root)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(suffix))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "expected a file ending with '" + suffix + "' under " + root));
        }
    }

    /** Reads a {@code metric;value} KPI CSV (the 1c extractor convention). */
    private static Map<String, Double> readMetricCsv(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        assertThat(lines.get(0)).isEqualTo("metric;value");
        Map<String, Double> map = new LinkedHashMap<>();
        for (int i = 1; i < lines.size(); i++) {
            String[] parts = lines.get(i).split(";", 2);
            map.put(parts[0], Double.parseDouble(parts[1]));
        }
        return map;
    }
}
