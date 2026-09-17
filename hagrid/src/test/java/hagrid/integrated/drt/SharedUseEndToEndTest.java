package hagrid.integrated.drt;

import hagrid.integrated.freight.LmdTestShapefiles;
import hagrid.integrated.modular.Modular;
import hagrid.integrated.shareduse.SharedUse;
import hagrid.integrated.shareduse.SharedUseModule;
import hagrid.simulation.DrtScenarioBuilder;
import hagrid.simulation.HAGRIDSimulationConfig;
import hagrid.simulation.RunMetadataWriter;
import hagrid.utils.general.StudyArea;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.events.handler.PersonEntersVehicleEventHandler;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Population;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controler;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.algorithms.TransportModeNetworkFilter;
import org.matsim.core.network.io.NetworkWriter;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitRouteStop;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitScheduleFactory;
import org.matsim.pt.transitSchedule.api.TransitScheduleWriter;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.testcases.MatsimTestUtils;
import org.matsim.vehicles.MatsimVehicleWriter;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.Vehicles;
import org.matsim.vehicles.VehiclesFactory;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import hagrid.integrated.shareduse.ParcelDemandProvenance;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end proof of DRT_SHAREDUSE (cargo hitching), the first full run of this concept in the
 * test suite (1c Task 7). Exercises the production preprocessor
 * ({@link LausitzDrtPreprocessor#run(HAGRIDSimulationConfig)}, which injects the parcel
 * subpopulation via {@code ParcelAgentGenerator}), then composes the scenario in the SAME order
 * {@code SimulationRunnerUtils.runSimulation}'s sharedUse branch uses:
 * {@code DrtScenarioBuilder.build} -&gt; {@code DrtConfigComposer.composeSharedUse} (config-scope,
 * before the Controler exists) -&gt; {@code new Controler} -&gt; {@code DrtConfigComposer.installModules}
 * -&gt; {@code controler.addOverridingModule(new SharedUseModule(...))}.
 * {@code lastIteration=1} so the parcel-subpopulation selector strategy actually replans once.
 * Uses the base (2-arg) {@code installModules} rather than the depot-based return-to-depot
 * overload - see {@link #runsShareduseThroughOneReplanningIteration()} for why.
 *
 * <p>Proves: (a) the whole wiring composes and runs without a Guice/validator/runtime crash;
 * (b)+(c) {@code shareduse_channel_stats.csv} is written and its M3 conservation identity
 * ({@code delivered + rejected_final + pending_eod == submitted}) holds; (d) no
 * {@code output_carriers.xml.gz} exists (D7 - DRT_SHAREDUSE never runs the CarrierModule);
 * (e) at least one {@code parcel_*} person physically boarded a {@code drt_*} vehicle.</p>
 */
@DisplayName("DRT_SHAREDUSE end-to-end run (production path, 1c Task 7)")
class SharedUseEndToEndTest {

    @RegisterExtension
    public MatsimTestUtils utils = new MatsimTestUtils();

    /** Service-area square: 0..2000. All fixture car nodes are inside (100..1000). */
    private static final double AREA_SIZE = 2000.0;
    private static final int FLEET_SIZE = 4;
    /** Deliberately very permissive: this e2e proves the WIRING composes end to end, not
     *  chi-gate tuning (escalation guidance: raise chi rather than risk flaking on assertion (e)). */
    private static final double CHI_THRESHOLD = 999_999.0;

    @Test
    @DisplayName("runsShareduseThroughOneReplanningIteration - parcels ride the DRT fleet, KPI CSV conserves, no carriers")
    void runsShareduseThroughOneReplanningIteration() throws Exception {
        Path dir = Path.of(utils.getOutputDirectory()).toAbsolutePath();
        Files.createDirectories(dir);

        // ---- 1. production preprocessor: drt-shareduse fleet capacity (SEATS=8) + parcel
        //         subpopulation injected into the clipped plans ----
        HAGRIDSimulationConfig cfg = stageAndBuildConfig(dir);
        LausitzDrtPreprocessor.run(cfg);

        // ---- 2. scenario + composeSharedUse + Controler + installModules + SharedUseModule
        //         (exact Task 6 order: SimulationRunnerUtils.runSimulation's sharedUse branch) ----
        URL cfgUrl = getClass().getClassLoader().getResource("lausitz-native-like.config.xml");
        assertThat(cfgUrl)
                .as("test fixture lausitz-native-like.config.xml must be on the test classpath")
                .isNotNull();
        Path matsimOut = dir.resolve("matsim");

        // A SHORT literal MATSim run id (not cfg.getRunId(), which is long: CONCEPT_DDMMYYYY_TAG)
        // - MatsimTestUtils already nests the output dir under the full class+method name, and
        // the native DrtZonalWaitTimesAnalyzer's shutdown geopackage write
        // (".../<runId>.drt_waitStats_drt_zonal.gpkg") failed with a Windows-only SQLITE_CANTOPEN
        // once the combined absolute path crept past ~255 chars with the long auto-generated id
        // (reproduced with Task 7's own KPI-handler bindings, and even composeSharedUse/
        // SharedUseModule, REMOVED - so this is a path-length artifact of this test's directory
        // nesting, not a Task 4-7 DRT_SHAREDUSE defect; see the report for the isolation steps).
        String runId = "SHAREDUSE_E2E";
        Scenario scenario = DrtScenarioBuilder.build(
                cfgUrl.toString(),
                cfg.getDrtNetworkClipped(), cfg.getPassengerPlansClipped(),
                cfg.getDrtServiceAreaShapefile(), cfg.getDrtFleetFile(),
                matsimOut.toString(), runId, /*lastIteration*/ 1);

        long parcelPersons = scenario.getPopulation().getPersons().values().stream()
                .filter(p -> SharedUse.isParcelPerson(p.getId().toString())).count();
        assertThat(parcelPersons).as("parcel subpopulation must have been injected by the preprocessor")
                .isGreaterThanOrEqualTo(1);

        // composeSharedUse mutates the CONFIG and must run BEFORE `new Controler(scenario)` -
        // DRT config groups are read at controler construction time.
        DrtConfigComposer.composeSharedUse(scenario.getConfig());

        // NOTE: uses the base 2-arg installModules (no ReturnToDepotRebalancingModule), exactly
        // like MarriedBaselineEndToEndTest - the brief's sanctioned "manual build" escape hatch.
        // The depot-based 5-arg overload + a real (>=2 iteration) run trips an UNRELATED,
        // pre-existing bug in the native DrtZonalWaitTimesAnalyzer's shutdown geopackage writer
        // (reproduces identically with the Task-7 KPI handler bindings removed - see report);
        // return-to-depot rebalancing is not part of what this e2e needs to prove (2D-load /
        // dwell / chi-gate / retry-queue / KPI-handler composition), so it is deliberately left out.
        Controler controler = new Controler(scenario);
        DrtConfigComposer.installModules(controler);

        DrtConfigGroup drtCfg = MultiModeDrtConfigGroup.get(scenario.getConfig())
                .getModalElements().iterator().next();
        // LAST overriding module - overrides the base DRT bindings installed above, exactly
        // mirroring SimulationRunnerUtils.runSimulation's sharedUse branch.
        controler.addOverridingModule(new SharedUseModule(drtCfg, CHI_THRESHOLD));

        // ---- (a) the run completes: composition proof (Guice/validator/runtime) ----
        controler.run();

        // ---- (d) no carrier module ran (D7) ----
        try (var s = Files.walk(matsimOut)) {
            assertThat(s.filter(Files::isRegularFile)
                    .anyMatch(p -> p.getFileName().toString().endsWith("output_carriers.xml.gz")))
                    .as("DRT_SHAREDUSE must never run the CarrierModule (D7)").isFalse();
        }

        // ---- (b) + (c) shareduse_channel_stats.csv exists, segments_submitted >= 1, M3 conservation ----
        Path statsCsv = findFile(matsimOut, "shareduse_channel_stats.csv");
        Map<String, String> metrics = readCsv(statsCsv);
        int submitted = Integer.parseInt(metrics.get("segments_submitted"));
        int delivered = Integer.parseInt(metrics.get("segments_delivered"));
        int deliveredLate = Integer.parseInt(metrics.get("segments_delivered_late"));
        int rejectedFinal = Integer.parseInt(metrics.get("segments_rejected_final"));
        int pendingEod = Integer.parseInt(metrics.get("segments_pending_eod"));
        assertThat(submitted).as("segments_submitted").isGreaterThanOrEqualTo(1);
        // A real production-path signal, not just the (tautological, since pending_eod is
        // defined as the remainder) conservation identity below: at least one parcel segment
        // actually got PHYSICALLY delivered by the DRT fleet in this run (in-window or late -
        // the I1/F4 split moves a post-window dropoff to delivered_late, so the sum is the
        // stable physical-delivery signal here).
        assertThat(delivered + deliveredLate)
                .as("segments_delivered + segments_delivered_late").isGreaterThanOrEqualTo(1);
        assertThat(delivered + deliveredLate + rejectedFinal + pendingEod)
                .as("M3 conservation: delivered + delivered_late + rejected_final + pending_eod == submitted")
                .isEqualTo(submitted);

        // ---- (e) at least one parcel_* person physically boarded a drt_* vehicle ----
        Path eventsFile = findFile(matsimOut, "output_events.xml.gz");
        AtomicBoolean parcelBoarded = new AtomicBoolean(false);
        EventsManager events = EventsUtils.createEventsManager();
        events.addHandler((PersonEntersVehicleEventHandler) event -> {
            if (SharedUse.isParcelPerson(event.getPersonId().toString())
                    && event.getVehicleId().toString().startsWith("drt_")) {
                parcelBoarded.set(true);
            }
        });
        new MatsimEventsReader(events).readFile(eventsFile.toString());
        assertThat(parcelBoarded.get())
                .as("expected >=1 parcel_* person to physically board a drt_* vehicle").isTrue();
    }

    /**
     * Task 8 (spec 2026-08-17): end-to-end guard for district-based depot assignment (Task 6).
     * Same production preprocessor call as {@link #runsShareduseThroughOneReplanningIteration()},
     * but with {@code openDepots=hoy_sued} against a two-depot CSV (wittichenau + hoy_sued):
     * every parcel-person's origin activity must land on the SAME link, because every pooled
     * stop's nearest OPEN depot is now hoy_sued regardless of which depot is geometrically
     * closer overall. Does not run a full {@code Controler} - the production preprocessor
     * (parcel injection) is where {@code openDepots} threads through, and this stays fast by
     * stopping right after that.
     */
    @Test
    @DisplayName("sharedUseRunsWithASingleOpenDepot - openDepots=hoy_sued yields exactly one parcel "
            + "origin link, and run_metadata.json records the depot-sweep keys")
    void sharedUseRunsWithASingleOpenDepot() throws Exception {
        Path dir = Path.of(utils.getOutputDirectory()).toAbsolutePath();
        Files.createDirectories(dir);

        HAGRIDSimulationConfig cfg = stageAndBuildConfig(dir, List.of("hoy_sued"));
        LausitzDrtPreprocessor.run(cfg);

        Population pop = PopulationUtils.readPopulation(cfg.getPassengerPlansClipped());
        long parcelPersons = pop.getPersons().values().stream()
                .filter(p -> SharedUse.isParcelPerson(p.getId().toString())).count();
        assertThat(parcelPersons).as("no parcel persons were injected").isGreaterThan(0);

        Set<Id<Link>> depotLinks = pop.getPersons().values().stream()
                .filter(p -> SharedUse.isParcelPerson(p.getId().toString()))
                .map(p -> ((Activity) p.getSelectedPlan().getPlanElements().get(0)).getLinkId())
                .collect(Collectors.toSet());
        assertThat(depotLinks)
                .as("one open depot must yield exactly one origin link - none at the excluded "
                        + "wittichenau provider yard")
                .hasSize(1);

        // Identity, not just cardinality: the single origin link must be the link hoy_sued's OWN
        // coordinate snaps to on the drt sub-network - the exact snap the production preprocessor
        // performs for a depot (LausitzDrtPreprocessor L216-231 -> ParcelAgentGenerator's
        // NetworkUtils.getNearestLinkExactly(drtNetwork, district.depot().coord())). Both fixture
        // demand points ({300,300} / {800,800}) are geometrically nearer wittichenau (500,500)
        // than hoy_sued (950,300), so a regression that dropped cfg.getOpenDepots() at L228 (falling
        // back to "all depots open") would still land every parcel on a SINGLE link - wittichenau's
        // - and hasSize(1) alone would not notice. This assertion would catch that.
        Map<String, Coord> depotCoords = DrtDepotReader.readBySite(Path.of(cfg.getLmdDepotCsv()));
        Coord hoySuedCoord = depotCoords.get("hoy_sued");
        assertThat(hoySuedCoord).as("fixture must stage a hoy_sued depot coordinate").isNotNull();

        Network fullNet = NetworkUtils.readNetwork(cfg.getDrtNetworkClipped());
        Network drtNet = NetworkUtils.createNetwork();
        new TransportModeNetworkFilter(fullNet).filter(drtNet, Set.of(TransportMode.drt));
        Id<Link> hoySuedLinkId = NetworkUtils.getNearestLinkExactly(drtNet, hoySuedCoord).getId();

        assertThat(depotLinks)
                .as("the single origin link must be the link hoy_sued's coordinate snaps to on "
                        + "the drt sub-network, not wittichenau's - openDepots must actually "
                        + "restrict which depot districts pool through, not merely happen to "
                        + "still yield one link")
                .containsExactly(hoySuedLinkId);

        // run_metadata.json must record openDepots/maxJobsPerDistrict, else a sweep stage cannot
        // be identified after the fact and two stages could be compared as if they were the same
        // configuration (RunMetadataWriter keys: open_depots / max_jobs_per_district).
        Path metaFile = RunMetadataWriter.write(cfg, dir.resolve("run_metadata_check"));
        String meta = Files.readString(metaFile);
        assertThat(meta).as("run_metadata.json must record open_depots").contains("\"open_depots\"");
        assertThat(meta).as("run_metadata.json must record WHICH depots were open").contains("hoy_sued");
        assertThat(meta).as("run_metadata.json must record max_jobs_per_district")
                .contains("\"max_jobs_per_district\"");
    }

    // -------------------------------------------------------------------------
    // Fixture setup: stage raw inputs at the exact paths cfg's getters resolve to
    // (hagrid.pipeline.root -> a fresh temp dir per test), mirroring SharedUsePreprocessorTest.
    // -------------------------------------------------------------------------

    private HAGRIDSimulationConfig stageAndBuildConfig(Path dir) throws Exception {
        return stageAndBuildConfig(dir, List.of());
    }

    /**
     * Same fixture as {@link #stageAndBuildConfig(Path)}, but threading {@code openDepots} into
     * the config (Task 8, spec 2026-08-17) and, whenever the caller actually restricts to a
     * subset, staging a SECOND depot ({@code hoy_sued}) in the depot CSV alongside the original
     * {@code wittichenau} one. Without a second depot present, opening "only hoy_sued" would be
     * vacuous - there would be nothing else to exclude.
     */
    private HAGRIDSimulationConfig stageAndBuildConfig(Path dir, List<String> openDepots) throws Exception {
        System.setProperty("hagrid.pipeline.root", dir.toString());
        try {
            HAGRIDSimulationConfig cfg = new HAGRIDSimulationConfig(
                    "drt_shareduse", LocalDate.of(2025, 5, 13),
                    /*maxIterations*/ 1, /*jspritIterations*/ 1,
                    false, 0.0, 0.0, "shareduse_e2e",
                    StudyArea.LAUSITZ_HOYERSWERDA, FLEET_SIZE,
                    /*drtWithFreight*/ false, /*kpiDashboard*/ false, CHI_THRESHOLD,
                    /*noParcels*/ false, /*seed*/ 1337L,
                    Modular.DEFAULT_IDLE_THRESHOLD, Modular.DEFAULT_MAX_TOUR_DURATION_S,
                    openDepots, /*maxJobsPerDistrict*/ 300);

            // raw network
            createParentDirs(cfg.getLausitzNetworkRaw());
            new NetworkWriter(DrtE2eFixtures.buildGrid()).write(cfg.getLausitzNetworkRaw());

            // raw passenger plans
            createParentDirs(cfg.getPassengerPlansRaw());
            PopulationUtils.writePopulation(DrtE2eFixtures.buildDemand(), cfg.getPassengerPlansRaw());

            // DRT service-area shapefile
            createParentDirs(cfg.getDrtServiceAreaShapefile());
            DrtE2eFixtures.writeSquareShapefile(Path.of(cfg.getDrtServiceAreaShapefile()), AREA_SIZE);

            // LMD depot CSV (single depot at 500,500, or two when openDepots restricts to a
            // subset - see javadoc above). Needs a site column: run(cfg)'s shareduse branch now
            // districts via DrtDepotReader.readBySite (Task 6, spec 2026-08-17 D7), which throws
            // loudly on a column-3-less row.
            createParentDirs(cfg.getLmdDepotCsv());
            // hoy_sued sits at (950,300) - deliberately NOT on top of either demand segment
            // ({300,300} / {800,800} below), so opening it exercises real routing rather than a
            // same-link pickup/delivery coincidence (which ParcelAgentGenerator skips), AND far
            // enough from wittichenau (500,500) that the two snap to DIFFERENT links of the
            // 4-node square (wittichenau ties bottom/left at distance 400 each and resolves to
            // the bottom edge; hoy_sued is unambiguously nearest the right edge at distance 50) -
            // needed so sharedUseRunsWithASingleOpenDepot's link-identity assertion actually
            // discriminates (an earlier candidate, (700,200), also tied onto the SAME bottom-edge
            // link as wittichenau and could not tell the two depots apart).
            String depotCsvBody = openDepots.isEmpty()
                    ? "provider;x;y;site\ndhl;500.0;500.0;wittichenau\n"
                    : "provider;x;y;site\ndhl;500.0;500.0;wittichenau\ndhl;950.0;300.0;hoy_sued\n";
            Files.writeString(Path.of(cfg.getLmdDepotCsv()), depotCsvBody);

            // LMD parcel-demand shapefile: two segments, both B2C+B2B so multiple parcel-persons
            // are injected (only consumed by the shareduse post-step in run(cfg)).
            createParentDirs(cfg.getLmdDemandShapefile());
            LmdTestShapefiles.writeDemand(Path.of(cfg.getLmdDemandShapefile()),
                    new double[][]{{300, 300}, {800, 800}},
                    new long[]{3, 2},   // dhl B2C parcels
                    new long[]{1, 0},   // dhl B2B parcels
                    new long[]{0, 0});  // hermes: none

            // raw transit schedule + vehicles: run(cfg) always delegates to the 15-arg overload,
            // which unconditionally rail-filters when the schedule PATH STRING is non-blank
            // (cfg's getter always returns a real path string, whether or not a file exists there).
            createParentDirs(cfg.getLausitzTransitScheduleRaw());
            createParentDirs(cfg.getLausitzTransitVehiclesRaw());
            writeRawRailFixture(Path.of(cfg.getLausitzTransitScheduleRaw()),
                    Path.of(cfg.getLausitzTransitVehiclesRaw()));

            return cfg;
        } finally {
            System.clearProperty("hagrid.pipeline.root");
        }
    }

    /**
     * The preprocessing losses must reach disk, because nothing downstream can reconstruct them:
     * a stop dropped at its own yard gate never becomes an agent, so no plan and no event mentions
     * its parcels. Without this file the KPI layer can only report 1c's delivery rate on the
     * injected base, which reads 100 % and is indistinguishable from the Baseline's 100 % on the
     * full demand base (spec 2026-08-25 §3).
     *
     * <p>The injected count is cross-checked against the population that was actually written, not
     * just against the file's own total: a writer that emits self-consistent but wrong numbers
     * would satisfy the unit test and still be useless here.</p>
     */
    @Test
    @DisplayName("preprocessing writes the parcel-demand provenance next to the clipped population")
    void preprocessingWritesTheParcelDemandProvenance() throws Exception {
        Path dir = Path.of(utils.getOutputDirectory()).toAbsolutePath();
        Files.createDirectories(dir);

        HAGRIDSimulationConfig cfg = stageAndBuildConfig(dir, List.of("hoy_sued"));
        LausitzDrtPreprocessor.run(cfg);

        Path provenance = ParcelDemandProvenance.pathFor(
                Path.of(cfg.getPassengerPlansClipped()), cfg.getRunId());
        assertThat(provenance).as("provenance file must be written by the preprocessing run")
                .exists();

        Map<String, Integer> m = new LinkedHashMap<>();
        for (String line : Files.readAllLines(provenance)) {
            String[] kv = line.split(";", 2);
            if (kv.length == 2 && !kv[0].equals("metric")) {
                m.put(kv[0], Integer.parseInt(kv[1].trim()));
            }
        }

        Population pop = PopulationUtils.readPopulation(cfg.getPassengerPlansClipped());
        int parcelsInPopulation = pop.getPersons().values().stream()
                .filter(p -> SharedUse.isParcelPerson(p.getId().toString()))
                .mapToInt(p -> (int) p.getAttributes().getAttribute(SharedUse.LOAD_ATTRIBUTE))
                .sum();

        assertThat(m.get("parcels_injected_preprocessing"))
                .as("the file must agree with the population that was actually written")
                .isEqualTo(parcelsInPopulation);
        assertThat(m.get("parcels_offered"))
                .as("offered must equal injected plus both loss channels")
                .isEqualTo(m.get("parcels_injected_preprocessing")
                        + m.get("parcels_dropped_at_depot_link")
                        + m.get("parcels_clipped_outside_area"));
    }

    private static void createParentDirs(String path) throws IOException {
        Path parent = Path.of(path).getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    /** Minimal one-rail-line schedule + matching transit vehicle (mirrors
     *  RailScheduleFilterTest's / SharedUsePreprocessorTest's fixture) - just enough for
     *  {@link RailScheduleFilter#run} to read, filter and re-write. */
    private static void writeRawRailFixture(Path scheduleOut, Path vehiclesOut) {
        var scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        TransitSchedule schedule = scenario.getTransitSchedule();
        TransitScheduleFactory sf = schedule.getFactory();
        Vehicles vehicles = scenario.getTransitVehicles();
        VehiclesFactory vf = vehicles.getFactory();

        TransitStopFacility s1 = sf.createTransitStopFacility(
                Id.create("s1", TransitStopFacility.class), new Coord(0, 0), false);
        s1.setLinkId(Id.createLinkId("l0"));
        TransitStopFacility s2 = sf.createTransitStopFacility(
                Id.create("s2", TransitStopFacility.class), new Coord(1000, 0), false);
        s2.setLinkId(Id.createLinkId("l2"));
        schedule.addStopFacility(s1);
        schedule.addStopFacility(s2);

        VehicleType vt = vf.createVehicleType(Id.create("railVeh", VehicleType.class));
        vt.setNetworkMode("rail");
        vehicles.addVehicleType(vt);

        TransitLine line = sf.createTransitLine(Id.create("railLine", TransitLine.class));
        List<TransitRouteStop> stops = List.of(
                sf.createTransitRouteStop(s1, 0, 0),
                sf.createTransitRouteStop(s2, 600, 600));
        TransitRoute route = sf.createTransitRoute(
                Id.create("railLine_r", TransitRoute.class), null, stops, "rail");
        Vehicle v = vf.createVehicle(Id.createVehicleId("railV"), vt);
        vehicles.addVehicle(v);
        Departure dep = sf.createDeparture(Id.create("railDep", Departure.class), 8 * 3600);
        dep.setVehicleId(v.getId());
        route.addDeparture(dep);
        line.addRoute(route);
        schedule.addTransitLine(line);

        new TransitScheduleWriter(schedule).writeFile(scheduleOut.toString());
        new MatsimVehicleWriter(vehicles).writeFile(vehiclesOut.toString());
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

    private static Map<String, String> readCsv(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        assertThat(lines.get(0)).isEqualTo("metric;value");
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 1; i < lines.size(); i++) {
            String[] parts = lines.get(i).split(";", 2);
            map.put(parts[0], parts[1]);
        }
        return map;
    }
}
