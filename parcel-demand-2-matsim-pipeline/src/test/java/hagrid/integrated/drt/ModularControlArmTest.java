package hagrid.integrated.drt;

import hagrid.integrated.modular.ModularDispatchModule;
import hagrid.integrated.modular.ModularFreightTour;
import hagrid.simulation.DrtScenarioBuilder;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Scenario;
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
import org.matsim.testcases.MatsimTestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Control-arm proof of DRT_MODULAR (1d Task 12): {@code idleThreshold=1.0} makes the dispatch
 * gate {@code idleShare > idleThreshold} (STRICT) unsatisfiable — {@code idle.size()==fleetSize}
 * gives at best {@code 1.0 > 1.0}, which is false — so NO tour is ever dispatched and NO schedule
 * is ever spliced. This is the 1d analog of 1c's {@code chi->0} arm, but STRONGER (design §6): with
 * the gate never opening there is no splice and — unlike 1c — not even an extra population member,
 * so the passenger side must come out BYTE-IDENTICAL to a run with no modular module at all, not
 * merely "within noise".
 *
 * <p><b>TWO arm-pairs, proving the same property under two different compositions (Task 12 review,
 * Important finding).</b> The first attempt at this test used the bare 1-arg
 * {@code DrtConfigComposer.installModules(Controler)} for both arms of a single pair — a real
 * property, but a THIRD wiring that is neither the production {@code DRT_MODULAR} run nor the
 * production 10-seat Baseline run those two both actually use
 * ({@code SimulationRunnerUtils.java:337-347}: every real DRT run unconditionally installs the
 * 5-arg overload with {@link ReturnToDepotRebalancingModule}). Since this control arm is what
 * licenses the study's headline pax-cost comparison, extrapolating from a stripped composition to
 * the production one is exactly the kind of gap a reader would poke at. Two pairs close it:</p>
 * <ul>
 *   <li><b>Pair 1 (A vs B), stripped composition</b> — plain {@code installModules(Controler)}
 *       (1-arg) for both arms, no rebalancing module. This isolates the WIRING-LEVEL leak cleanly:
 *       the {@code ScheduleTimingUpdater} decorator, the {@code VehicleEntry.EntryFactory} decorator
 *       and the {@code DrtOptimizer} rebind are all installed unconditionally at QSim startup
 *       (see {@link ModularDispatchModule}), so a leak in any of them would fire whether or not
 *       anything else is going on — this pair has the fewest possible confounds for finding one.</li>
 *   <li><b>Pair 2 (C vs D), production composition</b> — the 5-arg
 *       {@code installModules(Controler, depots, returnStart, perDepotCapacity,
 *       demandEstimationPeriod)} with {@link ReturnToDepotRebalancingModule}, i.e. the SAME
 *       composition every real {@code DRT_MODULAR} and 10-seat-Baseline run uses. This proves the
 *       SAME byte-identity property holds when rebalancing is actually live and actually relocating
 *       vehicles (asserted, not merely configured — see {@link #anyRelocateTaskStarted}), which is
 *       what licenses reading pair 1's property as representative of the runs the paper's headline
 *       numbers come from.</li>
 * </ul>
 * Both pairs share ONE staged fixture ({@link ModularE2eStaging}) and the SAME two pinned pax
 * output files; only the DRT config composition differs between pairs, and only whether
 * {@link ModularDispatchModule} is installed differs within a pair.
 *
 * <p><b>Pair 2's rebalancing parameters mirror Task 10's {@code ModularEndToEndTest}, not the design
 * spec's or production's literal values</b> — for the same reason Task 10 deviated:</p>
 * <ul>
 *   <li>rebalancing zone cell size shrunk from the composed 2000 m default to 300 m: this fixture's
 *       network spans 100..1000 m, so at 2000 m the WHOLE service area is one zone and MinCostFlow
 *       never has anything to move regardless of whether {@link ReturnToDepotRebalancingModule} is
 *       present — which would make "pair 2 actually exercises the rebalancing interaction" true only
 *       by vacuous absence of any relocation at all. Applied identically to run C and run D so the
 *       two arms of the pair stay identically staged.</li>
 *   <li>{@code returnStart} = 10:00, not the design spec's 84600 (23:30) nor production's literal
 *       {@code serviceEnd - 5400 = 81000} (22:30): every fixture DRT vehicle starts at the single
 *       depot link, so a depot pull engaging 30-90 min before service end has nothing left to pull —
 *       at 10:00 both the demand-based and the depot-targeting branch of
 *       {@code ReturnToDepotTargetCalculator} meet live vehicle activity. Must be a multiple of the
 *       1800 s rebalancing interval.</li>
 * </ul>
 *
 * <p><b>Staging: once, not four times.</b> {@link ModularE2eStaging#stage} writes every input file
 * (raw network, raw plans, service-area shapefile, depot csv, van type, LMD demand shapefile, the
 * jsprit-routed carriers, and — via {@code LausitzDrtPreprocessor.run} — the DRT-tagged network /
 * clipped plans / fleet file) to disk EXACTLY ONCE and converts the routed carriers into the
 * {@link ModularFreightTour} list ONCE; all four {@code DrtScenarioBuilder.build} calls (one per
 * run A/B/C/D) read the SAME files, and runs A/C additionally share the SAME tour list. There is
 * therefore no staging step left that could itself introduce a difference between arms within a
 * pair — the only thing that differs within a pair is whether {@link ModularDispatchModule} is
 * installed at all, and the only thing that differs between pairs is the DRT config composition.
 * (Also extracted into {@link ModularE2eStaging} because {@link ModularEndToEndTest} depends on
 * staging the identical fixture — Task 12 review, Minor 1: two independently maintained copies of
 * this recipe were a drift risk, not merely duplication.)</p>
 *
 * <p>Deliberately NOT reused from Task 10: the five extra {@code person_c*} "contention" agents.
 * Those exist to give the D2 lockout a live insertion search to be tested against WHILE a vehicle is
 * on freight; with the gate never opening in EITHER pair there is no freight excursion window for
 * them to contend inside, so they would be inert padding here. The stock
 * {@link DrtE2eFixtures#buildDemand()} population (5 {@code person_*} drt-mode agents + 1 {@code pt}
 * agent) is used as-is and already yields non-trivial DRT legs and customer-stats content (verified
 * below, not merely assumed). Confirmed empirically (Task 12 mutation-testing round): even a REAL
 * freight dispatch forced via a temporarily lowered {@code idleThreshold} left the pax CSVs of this
 * exact fixture completely unchanged, because nothing contends with the committed vehicle — which is
 * why a SEPARATE deliberate-leak mutation (in {@code ModularStayTaskEndTimeCalculator}, gate held
 * shut) was needed to prove the byte-identity assertion itself has teeth; see the Task 12 report.</p>
 */
@DisplayName("DRT_MODULAR control arm (idleThreshold=1.0, 1d Task 12)")
class ModularControlArmTest {

    @RegisterExtension
    public MatsimTestUtils utils = new MatsimTestUtils();

    /** Same literal run id fed to all four runs (separate output directories: matsimA/B/C/D) — see
     *  the class javadoc for why this is safe: {@code OutputDirectoryHierarchy} is a per-{@code
     *  Controler} instance keyed only by {@code config.controller().getOutputDirectory()}
     *  ({@code LausitzDrtPreprocessor.java:139-144} composes that from the explicit {@code outputDir}
     *  argument, not from any JVM-wide registry keyed by run id), which differs between every arm. */
    private static final String RUN_ID = "CONTROL_E2E";
    /** The control arm: {@code idleShare > idleThreshold} can be at most {@code 1.0 > 1.0} (all
     *  vehicles idle) which is false, so the gate never opens. Confirmed against
     *  {@code ModularTourDispatcher}'s literal condition, not merely the design doc. */
    private static final double IDLE_THRESHOLD_CONTROL = 1.0;

    // ---- pair 2 (production composition) parameters — see class javadoc for why these values ----
    /** Single fixture depot, matches {@code depots.csv} ("dhl;500.0;500.0") staged by
     *  {@link ModularE2eStaging}. */
    private static final List<Coord> DEPOT_COORDS = List.of(new Coord(500.0, 500.0));
    private static final double RETURN_START_S = 10 * 3600.0;
    /** Must equal the composed config's demandEstimationPeriod, else ZonalDemandEstimator's
     *  estimationPeriod==timeBinSize precondition fails (SharedUseRebalTest.java:129-134). */
    private static final double DEMAND_ESTIMATION_PERIOD_S = 1800.0;
    /** ceil(fleetSize / depots.size()) = ceil(4/1) — mirrors {@code SimulationRunnerUtils}'
     *  production formula for a single depot and {@code ModularE2eStaging.FLEET_SIZE}. */
    private static final double PER_DEPOT_CAPACITY = 4.0;
    private static final double REBAL_CELL_SIZE_M = 300.0;
    /** The two task types {@link EmptyVehicleRelocator} appends — used to confirm pair 2 actually
     *  exercised rebalancing, not merely configured it. */
    private static final Set<String> RELOCATE_TASK_TYPES = Set.of(
            EmptyVehicleRelocator.RELOCATE_VEHICLE_TASK_TYPE.name(),
            EmptyVehicleRelocator.RELOCATE_VEHICLE_TO_DEPOT_TASK_TYPE.name());

    @Test
    @DisplayName("theta=1.0: zero dispatches AND pax outputs byte-identical to a run without the"
            + " module, under BOTH the stripped and the production composition")
    void gateNeverOpensReproducesBaseline() throws Exception {
        Path dir = Path.of(utils.getOutputDirectory()).toAbsolutePath();
        ModularE2eStaging staging = ModularE2eStaging.stage(dir);

        // Ground truth: without this, "tours_dispatched == 0" would be vacuously true for a
        // fixture that produced no tours at all - proving nothing about the gate, only about an
        // empty plan. Shared by BOTH pairs: the tour list itself does not depend on which
        // composition (never) dispatches it.
        int expectedTours = staging.tours.size();
        int expectedParcels = staging.tours.stream().mapToInt(ModularFreightTour::totalParcels).sum();
        assertThat(expectedTours).as("fixture must produce >=1 dispatchable tour").isGreaterThan(0);
        assertThat(expectedParcels).as("fixture must plan >=1 parcel").isGreaterThan(0);

        // SoftAssertions (Task 12 review, Minor 2): group (1) zero-dispatch and group (2)
        // byte-identity are collected together so a regression that breaks BOTH surfaces both
        // failures at once, instead of fail-fast hiding the byte-identity diff - the assertion this
        // whole task exists to prove has teeth - behind the KPI-count failure.
        SoftAssertions softly = new SoftAssertions();

        // ================= PAIR 1: stripped composition (isolates the wiring-level leak) =========
        Path matsimOutA = dir.resolve("matsimA");
        Path matsimOutB = dir.resolve("matsimB");
        runStrippedArm(staging, matsimOutA, /*withModule*/ true);
        runStrippedArm(staging, matsimOutB, /*withModule*/ false);
        assertControlArm(softly, "pair 1 (stripped composition)", matsimOutA, matsimOutB,
                expectedTours, expectedParcels);

        // ================= PAIR 2: production composition (5-arg installModules + RTDRM) =========
        Path matsimOutC = dir.resolve("matsimC");
        Path matsimOutD = dir.resolve("matsimD");
        runProductionArm(staging, matsimOutC, /*withModule*/ true);
        runProductionArm(staging, matsimOutD, /*withModule*/ false);
        assertControlArm(softly, "pair 2 (production composition)", matsimOutC, matsimOutD,
                expectedTours, expectedParcels);

        // Pair 2 must actually exercise rebalancing while the module is installed, else it
        // silently degenerates into a slower copy of pair 1 and proves nothing about the
        // interaction (Task 12 review, Important finding).
        softly.assertThat(anyRelocateTaskStarted(matsimOutC))
                .as("run C (production composition, module installed) must emit >=1 RELOCATE task -"
                        + " otherwise pair 2 never actually exercised the rebalancing machinery and"
                        + " degenerates into a slower copy of pair 1")
                .isTrue();

        softly.assertAll();
    }

    // ------------------------------------------------------------------ arm builders

    /** One arm of pair 1: plain {@code installModules(Controler)} (1-arg), no rebalancing module. */
    private static void runStrippedArm(ModularE2eStaging staging, Path matsimOut, boolean withModule)
            throws Exception {
        Scenario scenario = DrtScenarioBuilder.build(staging.cfgUrl.toString(),
                staging.drtNetFile.toString(), staging.clippedPlans.toString(),
                staging.shpFile.toString(), staging.fleetFile.toString(),
                matsimOut.toString(), RUN_ID, /*lastIteration*/ 1);

        Controler controler = new Controler(scenario);
        DrtConfigComposer.installModules(controler);
        if (withModule) {
            DrtConfigGroup drtCfg = MultiModeDrtConfigGroup.get(scenario.getConfig())
                    .getModalElements().iterator().next();
            // LAST overriding module (SharedUseModule convention) - theta=1.0, the control arm.
            controler.addOverridingModule(
                    new ModularDispatchModule(drtCfg, staging.tours, IDLE_THRESHOLD_CONTROL));
        }
        controler.run();
    }

    /** One arm of pair 2: the production 5-arg {@code installModules} +
     *  {@link ReturnToDepotRebalancingModule}, rebalancing cell shrunk so MinCostFlow can actually
     *  relocate in this fixture (see class javadoc). Applied identically regardless of
     *  {@code withModule} so the two arms of the pair stay identically staged/configured. */
    private static void runProductionArm(ModularE2eStaging staging, Path matsimOut, boolean withModule)
            throws Exception {
        Scenario scenario = DrtScenarioBuilder.build(staging.cfgUrl.toString(),
                staging.drtNetFile.toString(), staging.clippedPlans.toString(),
                staging.shpFile.toString(), staging.fleetFile.toString(),
                matsimOut.toString(), RUN_ID, /*lastIteration*/ 1);
        DrtConfigGroup drtCfg = MultiModeDrtConfigGroup.get(scenario.getConfig())
                .getModalElements().iterator().next();
        // Config mutation MUST precede `new Controler(scenario)`: DRT config groups are read at
        // controler construction time.
        RebalancingParams rebalancing = drtCfg.getRebalancingParams().orElseThrow(
                () -> new AssertionError("composeConfig must install RebalancingParams"));
        ((SquareGridZoneSystemParams) rebalancing.getZoneSystemParams()).setCellSize(REBAL_CELL_SIZE_M);

        Controler controler = new Controler(scenario);
        DrtConfigComposer.installModules(controler, DEPOT_COORDS, RETURN_START_S,
                PER_DEPOT_CAPACITY, DEMAND_ESTIMATION_PERIOD_S);
        if (withModule) {
            controler.addOverridingModule(
                    new ModularDispatchModule(drtCfg, staging.tours, IDLE_THRESHOLD_CONTROL));
        }
        controler.run();
    }

    // ------------------------------------------------------------------ shared assertions

    /**
     * Asserts one arm-pair's control-arm property: the {@code withModule} run dispatched nothing
     * (ground-truthed, not vacuous), the {@code baseline} run never even had the KPI channel, and
     * the two pinned pax CSVs are byte-identical between them. Numeric and byte-identity checks are
     * SOFT (Task 12 review, Minor 2) so both surface together; the two existence/non-existence
     * preconditions stay hard/fail-fast since nothing downstream is meaningful if they are wrong.
     */
    private static void assertControlArm(SoftAssertions softly, String label,
                                          Path matsimOutWithModule, Path matsimOutBaseline,
                                          int expectedTours, int expectedParcels) throws IOException {
        Path kpiCsv = matsimOutWithModule.resolve(RUN_ID + ".modular_tour_stats.csv");
        assertThat(kpiCsv).as(label + ": KPI CSV must be written under the run-ID prefix").exists();
        assertThat(matsimOutBaseline.resolve(RUN_ID + ".modular_tour_stats.csv"))
                .as(label + ": baseline installs no ModularDispatchModule - the KPI CSV must not"
                        + " exist at all")
                .doesNotExist();

        Map<String, Double> stats = readMetricCsv(kpiCsv);

        // ---- group (1): zero dispatch, ground-truthed so an empty plan can't satisfy it ----
        softly.assertThat(stats.get("tours_planned"))
                .as(label + ": tours_planned (ground truth %s)", expectedTours)
                .isEqualTo((double) expectedTours);
        softly.assertThat(stats.get("tours_planned"))
                .as(label + ": tours_planned must be > 0 (else zero-dispatch below is vacuous)")
                .isGreaterThan(0.0);
        softly.assertThat(stats.get("parcels_planned"))
                .as(label + ": parcels_planned (ground truth %s)", expectedParcels)
                .isEqualTo((double) expectedParcels);
        softly.assertThat(stats.get("tours_dispatched")).as(label + ": tours_dispatched").isZero();
        softly.assertThat(stats.get("parcels_served")).as(label + ": parcels_served").isZero();
        softly.assertThat(stats.get("delta_parcels"))
                .as(label + ": delta_parcels == parcels_planned (every parcel undelivered)")
                .isEqualTo(stats.get("parcels_planned"));

        // ---- group (2): bit-identical pax outputs ----
        Path legsWith = matsimOutWithModule.resolve(RUN_ID + ".output_drt_legs_drt.csv");
        Path legsBase = matsimOutBaseline.resolve(RUN_ID + ".output_drt_legs_drt.csv");
        Path custWith = matsimOutWithModule.resolve(RUN_ID + ".drt_customer_stats_drt.csv");
        Path custBase = matsimOutBaseline.resolve(RUN_ID + ".drt_customer_stats_drt.csv");

        // Non-vacuous: both files must actually carry data rows, not merely a header - an
        // empty-vs-empty (or headers-only) comparison would pass for a reason unrelated to the
        // property under test.
        assertNonTrivial(legsWith, label + ": output_drt_legs_drt.csv (with module)");
        assertNonTrivial(legsBase, label + ": output_drt_legs_drt.csv (baseline)");
        assertNonTrivial(custWith, label + ": drt_customer_stats_drt.csv (with module)");
        assertNonTrivial(custBase, label + ": drt_customer_stats_drt.csv (baseline)");

        softly.assertThat(Files.mismatch(legsWith, legsBase))
                .as(label + ": output_drt_legs_drt.csv must be byte-identical between the module"
                        + " (theta=1.0) and no-module arms")
                .isEqualTo(-1L);
        softly.assertThat(Files.mismatch(custWith, custBase))
                .as(label + ": drt_customer_stats_drt.csv must be byte-identical between the module"
                        + " (theta=1.0) and no-module arms")
                .isEqualTo(-1L);
    }

    /** Reads the native {@code dvrpTaskStarted} event stream and reports whether ANY RELOCATE task
     *  (demand-based or depot-targeting) was started — mirrors {@code ModularEndToEndTest}'s
     *  event-collection machinery so pair 2's "rebalancing actually fired" claim is asserted, not
     *  assumed from configuration alone. */
    private static boolean anyRelocateTaskStarted(Path matsimOut) throws IOException {
        Path eventsFile = findFile(matsimOut, "output_events.xml.gz");
        Set<String> startedTaskTypes = new LinkedHashSet<>();
        EventsManager events = EventsUtils.createEventsManager();
        events.addHandler((BasicEventHandler) event -> {
            if ("dvrpTaskStarted".equals(event.getEventType())) {
                startedTaskTypes.add(event.getAttributes().get("taskType"));
            }
        });
        new MatsimEventsReader(events).readFile(eventsFile.toString());
        return startedTaskTypes.stream().anyMatch(RELOCATE_TASK_TYPES::contains);
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

    /** Asserts a CSV has at least one data row beyond its header — guards against a vacuous
     *  byte-identity pass on two files that are trivially identical (both empty / headers-only). */
    private static void assertNonTrivial(Path csv, String label) throws IOException {
        List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
        assertThat(lines.size())
                .as(label + " must contain a header PLUS at least one data row (found %s lines)"
                        + " - an empty/headers-only file would make the byte-identity comparison"
                        + " vacuous", lines.size())
                .isGreaterThanOrEqualTo(2);
    }
}
