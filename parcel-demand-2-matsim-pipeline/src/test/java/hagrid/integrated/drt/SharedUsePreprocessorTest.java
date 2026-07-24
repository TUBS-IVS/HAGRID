package hagrid.integrated.drt;

import hagrid.integrated.freight.LmdTestShapefiles;
import hagrid.integrated.shareduse.SharedUse;
import hagrid.simulation.HAGRIDSimulationConfig;
import hagrid.utils.general.StudyArea;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.NetworkFactory;
import org.matsim.api.core.v01.network.Node;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.io.NetworkWriter;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.transitSchedule.api.*;
import org.matsim.testcases.MatsimTestUtils;
import org.matsim.vehicles.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers 1c Task 3: {@link LausitzDrtPreprocessor#run(HAGRIDSimulationConfig)} must (a) write the
 * DVRP fleet with {@code capacity = SharedUse.SEATS} (8) for {@code DRT_SHAREDUSE} and
 * {@code SharedUse.BASE_SEATS} (10) for every other concept, and (b) for {@code DRT_SHAREDUSE}
 * ONLY, inject the {@code parcel} subpopulation into the clipped passenger plans (post the
 * person-only filter) via {@link hagrid.integrated.shareduse.ParcelAgentGenerator}.
 */
@DisplayName("LausitzDrtPreprocessor.run(cfg) - Shared-Use capacity + parcel injection (1c Task 3)")
class SharedUsePreprocessorTest {

    @RegisterExtension
    public MatsimTestUtils utils = new MatsimTestUtils();

    /** Service-area square: 0..2000. All fixture car nodes are inside (100..1000). */
    private static final double AREA_SIZE = 2000.0;

    @Test
    @DisplayName("drt_shareduse: parcel subpopulation injected, fleet capacity = SEATS (8)")
    void sharedUseInjectsParcelsAndCapacity8() throws Exception {
        HAGRIDSimulationConfig cfg = stageAndBuildConfig("drt_shareduse", "shareduse_test");

        LausitzDrtPreprocessor.run(cfg);

        Population prepared = PopulationUtils.readPopulation(cfg.getPassengerPlansClipped());
        long parcelPersons = prepared.getPersons().values().stream()
                .filter(p -> p.getId().toString().startsWith(SharedUse.PARCEL_PERSON_PREFIX)).count();
        long paxPersons = prepared.getPersons().size() - parcelPersons;
        assertTrue(parcelPersons >= 1, "parcel subpopulation missing");
        assertTrue(paxPersons >= 1, "pax must survive the person-filter");

        String fleetXml = readGzToString(cfg.getDrtFleetFile());
        assertTrue(fleetXml.contains("capacity=\"8\""));
    }

    @Test
    @DisplayName("drt_baseline: no parcel persons, fleet capacity = BASE_SEATS (10) - regression")
    void baselineHasNoParcelsAndCapacity10() throws Exception {
        HAGRIDSimulationConfig cfg = stageAndBuildConfig("drt_baseline", "baseline_test");

        LausitzDrtPreprocessor.run(cfg);

        Population prepared = PopulationUtils.readPopulation(cfg.getPassengerPlansClipped());
        long parcelPersons = prepared.getPersons().values().stream()
                .filter(p -> p.getId().toString().startsWith(SharedUse.PARCEL_PERSON_PREFIX)).count();
        assertThat(parcelPersons).as("no parcel persons expected for DRT_BASELINE").isZero();
        assertThat(prepared.getPersons()).as("pax population must survive the person-filter").isNotEmpty();

        String fleetXml = readGzToString(cfg.getDrtFleetFile());
        assertTrue(fleetXml.contains("capacity=\"10\""));
    }

    /**
     * Review follow-up (1c Task 3): the parcel post-step in {@code run(cfg)} used to snap
     * against {@code NetworkUtils.readNetwork(cfg.getDrtNetworkClipped())} directly -- the FULL
     * augmented network, where {@code drt} is added only to eligible in-area {@code car} links.
     * {@link hagrid.integrated.shareduse.ParcelAgentGenerator} snaps with the mode-agnostic
     * {@code NetworkUtils.getNearestLinkExactly}, so a parcel could land on a geometrically
     * nearer NON-drt link (e.g. a bike-only spur) instead of the drt subnetwork the fleet uses.
     *
     * <p>This fixture adds exactly such a spur: a {@code bike}-only link (never tagged
     * {@code drt} by {@code PrepareNetwork.prepareDrtNetwork}, which only touches {@code car}
     * links) placed a few metres from a delivery point that is otherwise far (~700 m) from every
     * drt-tagged link. Pre-fix, the delivery activity would land on the untagged spur link;
     * post-fix (filtering to the drt-only subnetwork before snapping, mirroring the existing
     * fleet-anchoring pattern in {@code run(cfg)}) it must land on a drt-tagged link.</p>
     */
    @Test
    @DisplayName("drt_shareduse: parcel delivery activity snaps to a drt-tagged link, not a nearer non-drt spur")
    void sharedUseSnapsParcelsOnlyOnDrtTaggedLinks() throws Exception {
        HAGRIDSimulationConfig cfg = stageAndBuildConfig("drt_shareduse", "shareduse_snap_test",
                buildGridWithNonDrtSpur(),
                new double[][]{{1500, 1500}},   // far from the drt grid, right next to the spur
                new long[]{2},                  // dhl B2C parcels
                new long[]{0},                  // dhl B2B parcels
                new long[]{0});                 // hermes: none

        LausitzDrtPreprocessor.run(cfg);

        Population prepared = PopulationUtils.readPopulation(cfg.getPassengerPlansClipped());
        Person parcelPerson = prepared.getPersons().values().stream()
                .filter(p -> p.getId().toString().startsWith(SharedUse.PARCEL_PERSON_PREFIX))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "expected at least one parcel-person near the non-drt spur link"));

        Activity delivery = parcelPerson.getSelectedPlan().getPlanElements().stream()
                .filter(el -> el instanceof Activity a && SharedUse.ACT_DELIVERY.equals(a.getType()))
                .map(el -> (Activity) el)
                .findFirst()
                .orElseThrow(() -> new AssertionError("parcel plan missing its delivery activity"));

        Network writtenNet = NetworkUtils.readNetwork(cfg.getDrtNetworkClipped());
        Link deliveryLink = writtenNet.getLinks().get(delivery.getLinkId());
        assertThat(deliveryLink).as("delivery link must exist in the written drt network").isNotNull();
        assertThat(deliveryLink.getAllowedModes())
                .as("parcel delivery activity must snap to a drt-tagged link, not the nearer "
                        + "non-drt spur link " + deliveryLink.getId())
                .contains(TransportMode.drt);
    }

    /**
     * The base 4-corner grid plus one isolated {@code bike}-only 20 m link near (1500,1500) --
     * far outside the grid's 100..1000 footprint, but still inside the 0..2000 service area.
     * {@code PrepareNetwork.prepareDrtNetwork} only tags {@code car} links as {@code drt}, so
     * this spur never becomes drt-taggable, while remaining geometrically nearest to a delivery
     * point placed right next to it.
     */
    private static Network buildGridWithNonDrtSpur() {
        Network n = DrtE2eFixtures.buildGrid();
        NetworkFactory f = n.getFactory();
        Node a = f.createNode(Id.createNodeId("spurA"), new Coord(1490, 1500));
        Node b = f.createNode(Id.createNodeId("spurB"), new Coord(1510, 1500));
        n.addNode(a);
        n.addNode(b);
        Link spur = f.createLink(Id.createLinkId("spur"), a, b);
        spur.setLength(20);
        spur.setFreespeed(5.0);
        spur.setCapacity(600);
        spur.setNumberOfLanes(1);
        spur.setAllowedModes(Set.of("bike"));   // NOT "car" -> prepareDrtNetwork never tags it drt
        n.addLink(spur);
        return n;
    }

    // -------------------------------------------------------------------------
    // Fixture setup: stage raw inputs at the exact paths cfg's getters resolve to
    // (hagrid.pipeline.root -> a fresh temp dir per test), then build the config.
    // -------------------------------------------------------------------------

    private HAGRIDSimulationConfig stageAndBuildConfig(String concept, String tag) throws Exception {
        return stageAndBuildConfig(concept, tag, DrtE2eFixtures.buildGrid(),
                new double[][]{{800, 800}},
                new long[]{3},   // dhl B2C parcels
                new long[]{1},   // dhl B2B parcels
                new long[]{0});  // hermes: none
    }

    private HAGRIDSimulationConfig stageAndBuildConfig(String concept, String tag, Network rawNetwork,
                                                        double[][] demandXy, long[] dhlTag,
                                                        long[] dhlType, long[] hermesTag) throws Exception {
        Path dir = Path.of(utils.getOutputDirectory()).toAbsolutePath().resolve(tag);
        Files.createDirectories(dir);

        System.setProperty("hagrid.pipeline.root", dir.toString());
        try {
            HAGRIDSimulationConfig cfg = new HAGRIDSimulationConfig(
                    concept, LocalDate.of(2025, 5, 13),
                    /*maxIterations*/ 1, /*jspritIterations*/ 1,
                    false, 0.0, 0.0, tag,
                    StudyArea.LAUSITZ_HOYERSWERDA, /*fleetSize*/ 4,
                    /*drtWithFreight*/ false, /*kpiDashboard*/ false);

            // raw network
            createParentDirs(cfg.getLausitzNetworkRaw());
            new NetworkWriter(rawNetwork).write(cfg.getLausitzNetworkRaw());

            // raw passenger plans
            createParentDirs(cfg.getPassengerPlansRaw());
            PopulationUtils.writePopulation(DrtE2eFixtures.buildDemand(), cfg.getPassengerPlansRaw());

            // DRT service-area shapefile
            createParentDirs(cfg.getDrtServiceAreaShapefile());
            DrtE2eFixtures.writeSquareShapefile(Path.of(cfg.getDrtServiceAreaShapefile()), AREA_SIZE);

            // LMD depot CSV (single depot at 500,500 - proven collision-free with the (800,800)
            // demand point used by the default fixture, see ParcelAgentGeneratorTest)
            createParentDirs(cfg.getLmdDepotCsv());
            Files.writeString(Path.of(cfg.getLmdDepotCsv()), "provider;x;y\ndhl;500.0;500.0\n");

            // LMD parcel-demand shapefile (only consumed by the shareduse branch, harmless
            // for baseline).
            createParentDirs(cfg.getLmdDemandShapefile());
            LmdTestShapefiles.writeDemand(Path.of(cfg.getLmdDemandShapefile()), demandXy, dhlTag, dhlType, hermesTag);

            // raw transit schedule + vehicles (run(cfg) always delegates to the 15-arg overload,
            // which unconditionally rail-filters when the schedule path is non-blank)
            createParentDirs(cfg.getLausitzTransitScheduleRaw());
            createParentDirs(cfg.getLausitzTransitVehiclesRaw());
            writeRawRailFixture(Path.of(cfg.getLausitzTransitScheduleRaw()),
                    Path.of(cfg.getLausitzTransitVehiclesRaw()));

            return cfg;
        } finally {
            System.clearProperty("hagrid.pipeline.root");
        }
    }

    private static void createParentDirs(String path) throws IOException {
        Path parent = Path.of(path).getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    /** Minimal one-rail-line schedule + matching transit vehicle (mirrors RailScheduleFilterTest's
     *  fixture) - just enough for {@link RailScheduleFilter#run} to read, filter and re-write. */
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

    private static String readGzToString(String gzPath) throws IOException {
        try (GZIPInputStream in = new GZIPInputStream(Files.newInputStream(Path.of(gzPath)))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
