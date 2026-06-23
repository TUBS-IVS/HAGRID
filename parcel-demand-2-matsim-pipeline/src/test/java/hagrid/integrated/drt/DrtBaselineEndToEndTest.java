package hagrid.integrated.drt;

import hagrid.simulation.DrtScenarioBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.*;
import org.matsim.api.core.v01.population.*;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controler;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.io.NetworkWriter;
import org.matsim.core.population.PopulationUtils;
import org.matsim.testcases.MatsimTestUtils;

import java.io.DataOutputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end runtime proof for the passenger-only DRT_BASELINE pipeline.
 *
 * <p>Unlike {@link DrtBaselineIntegrationTest} (which builds its config inline, from scratch,
 * and overrides the operational scheme to {@code door2door}), this test boots a real
 * one-iteration MATSim DRT run through the <em>production</em> code path:</p>
 * <ol>
 *   <li>{@link LausitzDrtPreprocessor#run} produces the drt-augmented network, the
 *       person-only clipped plans, and the DVRP fleet from tiny in-code fixtures + a
 *       real (hand-rolled) service-area shapefile;</li>
 *   <li>{@link DrtScenarioBuilder#build} composes the config via
 *       {@link LausitzDrtConfigurator#build} (native-like base config: PT + counts +
 *       a remote {@code vehicles} ref present, so the strip/redirect/vehicles-hardening
 *       path is exercised), composes full-DVRP DRT via {@link DrtConfigComposer}, and
 *       performs the two-step create + register-DrtRouteFactory + load;</li>
 *   <li>a {@link Controler} runs one iteration with {@link DrtConfigComposer#installModules}.</li>
 * </ol>
 *
 * <p>The assertion (a {@code drt_*} output file exists after {@code controler.run()} with
 * {@code lastIteration=0}) reflects a genuine simulation, not a stub. The operational scheme
 * stays {@code serviceAreaBased} (the production default), so the run reads the real
 * service-area shapefile via the DRT module — proving the whole passenger-only path works.</p>
 */
@DisplayName("DRT_BASELINE end-to-end run (production path)")
class DrtBaselineEndToEndTest {

    @RegisterExtension
    public MatsimTestUtils utils = new MatsimTestUtils();

    /** Service-area square: 0..2000. All fixture car nodes are inside (100..1000). */
    private static final double AREA_SIZE = 2000.0;

    @Test
    @DisplayName("runsDrtBaselineOneIteration — full production path produces a drt_* output file")
    void runsDrtBaselineOneIteration() throws Exception {
        // Absolute output dir: the base config is loaded from a classpath URL, so MATSim sets
        // its context to target/test-classes and would resolve RELATIVE network/plans paths
        // against that context (not the CWD the writers used) — mismatching the written files.
        // Absolute paths sidestep the context resolution entirely.
        Path dir = Path.of(utils.getOutputDirectory()).toAbsolutePath();
        Files.createDirectories(dir);

        // ---- raw fixtures: full network, full population, service-area shapefile ----
        Network rawNet = buildGrid();
        Path rawNetFile = dir.resolve("raw_network.xml.gz");
        new NetworkWriter(rawNet).write(rawNetFile.toString());

        Path rawPlansFile = dir.resolve("raw_plans.xml.gz");
        PopulationUtils.writePopulation(buildDemand(), rawPlansFile.toString());

        Path shpFile = dir.resolve("service-area.shp");
        writeSquareShapefile(shpFile, AREA_SIZE);

        // ---- run-scoped DRT input files via the production preprocessor ----
        Path drtNetFile = dir.resolve("drt_network.xml.gz");
        Path clippedPlans = dir.resolve("clipped_plans.xml.gz");
        Path fleetFile = dir.resolve("fleet.xml.gz");
        LausitzDrtPreprocessor.run(
                rawNetFile.toString(),
                rawPlansFile.toString(),
                shpFile.toString(),
                drtNetFile.toString(),
                clippedPlans.toString(),
                fleetFile.toString(),
                /*fleetSize*/ 4, /*capacity*/ 8, /*serviceBegin*/ 0.0, /*serviceEnd*/ 86400.0);

        // ---- base config: portable native-like fixture (PT/counts/vehicles present) ----
        URL cfgUrl = getClass().getClassLoader().getResource("lausitz-native-like.config.xml");
        assertThat(cfgUrl)
                .as("test fixture lausitz-native-like.config.xml must be on the test classpath")
                .isNotNull();

        Path matsimOut = dir.resolve("matsim");

        // ---- build the scenario through the production builder (configurator + composer) ----
        Scenario scenario = DrtScenarioBuilder.build(
                cfgUrl.toString(),
                drtNetFile.toString(),
                clippedPlans.toString(),
                shpFile.toString(),          // real shapefile: serviceAreaBased reads it at run time
                fleetFile.toString(),
                matsimOut.toString(),
                "DRT_BASELINE_E2E",
                /*lastIteration*/ 0);

        // Sanity: plans + drt-mode links + fleet were wired in before the run.
        assertThat(scenario.getPopulation().getPersons())
                .as("clipped person population must be non-empty").isNotEmpty();
        assertThat(scenario.getNetwork().getLinks().values().stream()
                .anyMatch(l -> l.getAllowedModes().contains(TransportMode.drt)))
                .as("network must carry drt-mode links").isTrue();

        // ---- run one real iteration through the production install path ----
        Controler controler = new Controler(scenario);
        DrtConfigComposer.installModules(controler);
        controler.run();

        // ---- assert a genuine drt_* output file was produced ----
        assertThat(Files.isDirectory(matsimOut))
                .as("MATSim output directory must exist after the run").isTrue();
        try (var stream = Files.walk(matsimOut)) {
            assertThat(stream
                    .filter(Files::isRegularFile)
                    .anyMatch(p -> p.getFileName().toString().toLowerCase().contains("drt")))
                    .as("expected at least one drt_* output file produced by a real DRT iteration")
                    .isTrue();
        }
    }

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    /** 4-node car square (8 directed car links), all nodes at 100..1000 (inside the area). */
    private Network buildGrid() {
        Network n = NetworkUtils.createNetwork();
        NetworkFactory f = n.getFactory();
        double[][] xy = {{100, 100}, {1000, 100}, {1000, 1000}, {100, 1000}};
        Node[] nodes = new Node[4];
        for (int i = 0; i < 4; i++) {
            nodes[i] = f.createNode(Id.createNodeId("n" + i), new Coord(xy[i][0], xy[i][1]));
            n.addNode(nodes[i]);
        }
        for (int i = 0; i < 4; i++) {
            addLink(n, f, "l" + i, nodes[i], nodes[(i + 1) % 4]);
            addLink(n, f, "l" + i + "r", nodes[(i + 1) % 4], nodes[i]);
        }
        return n;
    }

    private void addLink(Network n, NetworkFactory f, String id, Node a, Node b) {
        Link l = f.createLink(Id.createLinkId(id), a, b);
        l.setLength(1000);
        l.setFreespeed(13.9);
        l.setCapacity(1800);
        l.setNumberOfLanes(1);
        l.setAllowedModes(Set.of("car"));
        n.addLink(l);
    }

    /**
     * Five {@code person}-subpopulation agents whose home is inside the area, each making
     * a drt trip to work. Activity types use the VSP typed convention
     * ({@code home_<sec>} / {@code work_<sec>}, durations a multiple of 600s within
     * 600..97200) so {@link org.matsim.contrib.vsp.scenario.SnzActivities#addScoringParams}
     * (called by the configurator) registers matching scoring params — otherwise MATSim
     * would abort with no scoring params for the activity type.
     */
    private Population buildDemand() {
        Population pop = PopulationUtils.createPopulation(ConfigUtils.createConfig());
        PopulationFactory pf = pop.getFactory();
        for (int i = 0; i < 5; i++) {
            Person p = pf.createPerson(Id.createPersonId("person_" + i));
            PopulationUtils.putSubpopulation(p, "person");
            Plan plan = pf.createPlan();
            Activity h = pf.createActivityFromCoord("home_57600", new Coord(150, 150));
            h.setEndTime(8 * 3600 + i * 60);
            plan.addActivity(h);
            plan.addLeg(pf.createLeg(TransportMode.drt));
            plan.addActivity(pf.createActivityFromCoord("work_28800", new Coord(950, 950)));
            p.addPlan(plan);
            p.setSelectedPlan(plan);
            pop.addPerson(p);
        }
        return pop;
    }

    // -------------------------------------------------------------------------
    // Minimal shapefile writer (square polygon), bypassing GeoTools feature
    // serialization (JTS 1.16 / GeoTools 31.1 LinearRing return-type conflict).
    // Mirrors LausitzDrtPreprocessorTest#writeSquareShapefile. The produced .shp is
    // read at run time by both LausitzDrtPreprocessor (GeoFileReader.getAllFeatures)
    // and the DRT module (ShpGeometryUtils.loadPreparedGeometries → same reader).
    // -------------------------------------------------------------------------

    private void writeSquareShapefile(Path shpPath, double size) throws Exception {
        double[] xs = {0, size, size, 0, 0};
        double[] ys = {0, 0, size, size, 0};
        int numPoints = xs.length;

        int contentBytes = 4 + (4 * 8) + 4 + 4 + 4 + numPoints * 16;
        int fileLength16 = (100 + 8 + contentBytes) / 2;
        int recContentLen16 = contentBytes / 2;

        java.io.ByteArrayOutputStream shpBuf = new java.io.ByteArrayOutputStream();
        DataOutputStream shp = new DataOutputStream(shpBuf);
        shp.writeInt(9994);
        for (int i = 0; i < 5; i++) shp.writeInt(0);
        shp.writeInt(fileLength16);
        writeIntLE(shp, 1000);
        writeIntLE(shp, 5);
        writeDoubleLE(shp, 0.0);
        writeDoubleLE(shp, 0.0);
        writeDoubleLE(shp, size);
        writeDoubleLE(shp, size);
        for (int i = 0; i < 4; i++) writeDoubleLE(shp, 0.0);
        shp.writeInt(1);
        shp.writeInt(recContentLen16);
        writeIntLE(shp, 5);
        writeDoubleLE(shp, 0.0);
        writeDoubleLE(shp, 0.0);
        writeDoubleLE(shp, size);
        writeDoubleLE(shp, size);
        writeIntLE(shp, 1);
        writeIntLE(shp, numPoints);
        writeIntLE(shp, 0);
        for (int i = 0; i < numPoints; i++) {
            writeDoubleLE(shp, xs[i]);
            writeDoubleLE(shp, ys[i]);
        }
        shp.flush();
        Files.write(shpPath, shpBuf.toByteArray());

        int shxFileLen16 = (100 + 8) / 2;
        int offsetWords = 100 / 2;
        java.io.ByteArrayOutputStream shxBuf = new java.io.ByteArrayOutputStream();
        DataOutputStream shx = new DataOutputStream(shxBuf);
        shx.writeInt(9994);
        for (int i = 0; i < 5; i++) shx.writeInt(0);
        shx.writeInt(shxFileLen16);
        writeIntLE(shx, 1000);
        writeIntLE(shx, 5);
        writeDoubleLE(shx, 0.0); writeDoubleLE(shx, 0.0);
        writeDoubleLE(shx, size); writeDoubleLE(shx, size);
        for (int i = 0; i < 4; i++) writeDoubleLE(shx, 0.0);
        shx.writeInt(offsetWords);
        shx.writeInt(recContentLen16);
        shx.flush();
        Path shxPath = shpPath.resolveSibling(
                shpPath.getFileName().toString().replace(".shp", ".shx"));
        Files.write(shxPath, shxBuf.toByteArray());

        Path dbfPath = shpPath.resolveSibling(
                shpPath.getFileName().toString().replace(".shp", ".dbf"));
        byte[] dbf = new byte[33];
        dbf[0] = 3;
        dbf[1] = 26;
        dbf[2] = 1;
        dbf[3] = 1;
        dbf[8] = 33; dbf[9] = 0;
        dbf[10] = 1; dbf[11] = 0;
        dbf[32] = 0x0D;
        Files.write(dbfPath, dbf);
    }

    private static void writeIntLE(DataOutputStream out, int v) throws java.io.IOException {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
        out.write((v >> 16) & 0xFF);
        out.write((v >> 24) & 0xFF);
    }

    private static void writeDoubleLE(DataOutputStream out, double v) throws java.io.IOException {
        long bits = Double.doubleToLongBits(v);
        for (int i = 0; i < 8; i++) {
            out.write((int) (bits & 0xFF));
            bits >>= 8;
        }
    }
}
