package hagrid.integrated.drt;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.NetworkFactory;
import org.matsim.api.core.v01.network.Node;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Population;
import org.matsim.api.core.v01.population.PopulationFactory;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.io.NetworkWriter;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.transitSchedule.api.*;
import org.matsim.vehicles.*;

import java.io.DataOutputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared test fixture for rail + DRT integration tests.
 *
 * <p>Stages a minimal scenario in a temp directory that includes:</p>
 * <ul>
 *   <li>a 4-node car grid network (all nodes inside the 0..2000 service-area square);</li>
 *   <li>a tiny passenger population with DRT legs;</li>
 *   <li>a service-area shapefile (0..2000 square);</li>
 *   <li>a raw transit schedule with ONE rail line whose two stops are at (200,200) and
 *       (1800,1800) — both inside the service area — and the corresponding transit vehicles;</li>
 *   <li>the rail-filtered schedule + vehicles produced by {@link RailScheduleFilter};</li>
 *   <li>the DRT-augmented network, clipped plans, and DVRP fleet produced by
 *       {@link LausitzDrtPreprocessor}.</li>
 * </ul>
 *
 * <p>Returns a holder with all produced paths so callers can drive
 * {@link hagrid.simulation.DrtScenarioBuilder} directly without a full
 * {@link hagrid.simulation.HAGRIDSimulationConfig}.</p>
 *
 * <p>The helper methods ({@code buildGrid}, {@code addLink}, {@code buildDemand},
 * {@code writeSquareShapefile}, {@code writeIntLE}, {@code writeDoubleLE}) are copied
 * from {@link DrtBaselineEndToEndTest} — this matches the established codebase convention
 * of duplicating small test helpers across test classes rather than extracting a shared util.</p>
 */
public final class RailScenarioFixture {

    /** Service-area square: 0..2000. All fixture nodes are inside (100..1800). */
    private static final double AREA_SIZE = 2000.0;

    /** Public holder for all produced paths. */
    public final URL baseConfigUrl;
    public final String drtNetwork;
    public final String clippedPlans;
    public final String serviceAreaShp;
    public final String fleet;
    public final String railSchedule;
    public final String railVehicles;

    private RailScenarioFixture(URL baseConfigUrl, String drtNetwork, String clippedPlans,
                                 String serviceAreaShp, String fleet,
                                 String railSchedule, String railVehicles) {
        this.baseConfigUrl = baseConfigUrl;
        this.drtNetwork = drtNetwork;
        this.clippedPlans = clippedPlans;
        this.serviceAreaShp = serviceAreaShp;
        this.fleet = fleet;
        this.railSchedule = railSchedule;
        this.railVehicles = railVehicles;
    }

    /**
     * Stages all fixture files under {@code dir} and returns a populated holder.
     *
     * @param dir absolute path to a writable temp/output directory
     * @return a {@link RailScenarioFixture} with all produced paths set
     */
    public static RailScenarioFixture stage(Path dir) throws Exception {
        Files.createDirectories(dir);

        // ---- raw fixtures: network, population, service-area shapefile ----
        Network rawNet = buildGrid();
        Path rawNetFile = dir.resolve("raw_network.xml.gz");
        new NetworkWriter(rawNet).write(rawNetFile.toString());

        Path rawPlansFile = dir.resolve("raw_plans.xml.gz");
        PopulationUtils.writePopulation(buildDemand(), rawPlansFile.toString());

        Path shpFile = dir.resolve("service-area.shp");
        writeSquareShapefile(shpFile, AREA_SIZE);

        // ---- raw rail schedule: one rail line with two stops inside the service area ----
        var schedCfg = ConfigUtils.createConfig();
        schedCfg.global().setCoordinateSystem("EPSG:25832");
        var schedScenario = ScenarioUtils.createScenario(schedCfg);
        TransitSchedule rawSchedule = schedScenario.getTransitSchedule();
        TransitScheduleFactory sf = rawSchedule.getFactory();

        // Two stop facilities whose coordinates are INSIDE the 0..2000 square.
        TransitStopFacility stop1 = sf.createTransitStopFacility(
                Id.create("rail_stop_1", TransitStopFacility.class),
                new Coord(200.0, 200.0), false);
        stop1.setLinkId(Id.createLinkId("l0"));
        rawSchedule.addStopFacility(stop1);

        TransitStopFacility stop2 = sf.createTransitStopFacility(
                Id.create("rail_stop_2", TransitStopFacility.class),
                new Coord(1800.0, 1800.0), false);
        stop2.setLinkId(Id.createLinkId("l2"));
        rawSchedule.addStopFacility(stop2);

        // One rail transit line + route + departure.
        TransitLine line = sf.createTransitLine(Id.create("rail_line_1", TransitLine.class));
        TransitRoute route = sf.createTransitRoute(
                Id.create("rail_route_1", TransitRoute.class),
                null,
                java.util.List.of(
                        sf.createTransitRouteStop(stop1, 0.0, 60.0),
                        sf.createTransitRouteStop(stop2, 3600.0, 3660.0)),
                "rail");
        Departure dep = sf.createDeparture(Id.create("dep_1", Departure.class), 7 * 3600.0);
        dep.setVehicleId(Id.createVehicleId("rail_veh_1"));
        route.addDeparture(dep);
        line.addRoute(route);
        rawSchedule.addTransitLine(line);

        // Raw transit vehicles.
        Vehicles rawVehicles = schedScenario.getTransitVehicles();
        VehiclesFactory vf = rawVehicles.getFactory();
        VehicleType vt = vf.createVehicleType(Id.create("rail_type", VehicleType.class));
        // MATSim requires networkMode to be set explicitly for non-car vehicle types.
        vt.setNetworkMode("rail");
        rawVehicles.addVehicleType(vt);
        Vehicle railVeh = vf.createVehicle(Id.createVehicleId("rail_veh_1"), vt);
        rawVehicles.addVehicle(railVeh);

        Path rawScheduleFile = dir.resolve("raw_transit_schedule.xml.gz");
        Path rawVehiclesFile = dir.resolve("raw_transit_vehicles.xml.gz");
        new TransitScheduleWriter(rawSchedule).writeFile(rawScheduleFile.toString());
        new MatsimVehicleWriter(rawVehicles).writeFile(rawVehiclesFile.toString());

        // ---- Rail-filtered schedule + vehicles (Task 1: RailScheduleFilter) ----
        Path railScheduleFile = dir.resolve("rail_schedule.xml.gz");
        Path railVehiclesFile = dir.resolve("rail_vehicles.xml.gz");
        RailScheduleFilter.run(
                rawScheduleFile.toString(),
                rawVehiclesFile.toString(),
                railScheduleFile.toString(),
                railVehiclesFile.toString());

        // ---- DRT inputs via the existing 10-arg LausitzDrtPreprocessor ----
        Path drtNetFile = dir.resolve("drt_network.xml.gz");
        Path clippedPlansFile = dir.resolve("clipped_plans.xml.gz");
        Path fleetFile = dir.resolve("fleet.xml.gz");
        LausitzDrtPreprocessor.run(
                rawNetFile.toString(),
                rawPlansFile.toString(),
                shpFile.toString(),
                drtNetFile.toString(),
                clippedPlansFile.toString(),
                fleetFile.toString(),
                /*fleetSize*/ 4, /*capacity*/ 8, /*serviceBegin*/ 0.0, /*serviceEnd*/ 86400.0);

        // ---- base config: rail-native-like fixture ----
        URL cfgUrl = RailScenarioFixture.class.getClassLoader()
                .getResource("lausitz-rail-native-like.config.xml");
        assertThat(cfgUrl)
                .as("test fixture lausitz-rail-native-like.config.xml must be on the test classpath")
                .isNotNull();

        return new RailScenarioFixture(
                cfgUrl,
                drtNetFile.toString(),
                clippedPlansFile.toString(),
                shpFile.toString(),
                fleetFile.toString(),
                railScheduleFile.toString(),
                railVehiclesFile.toString());
    }

    // -------------------------------------------------------------------------
    // Fixtures — copied from DrtBaselineEndToEndTest (established codebase pattern)
    // -------------------------------------------------------------------------

    /** 4-node car square (8 directed car links), all nodes at 100..1000 (inside the area). */
    private static Network buildGrid() {
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

    private static void addLink(Network n, NetworkFactory f, String id, Node a, Node b) {
        Link l = f.createLink(Id.createLinkId(id), a, b);
        l.setLength(1000);
        l.setFreespeed(13.9);
        l.setCapacity(1800);
        l.setNumberOfLanes(1);
        l.setAllowedModes(Set.of("car"));
        n.addLink(l);
    }

    /** Five {@code person}-subpopulation agents making a DRT trip to work. */
    private static Population buildDemand() {
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
    // Minimal shapefile writer (square polygon) — copied from DrtBaselineEndToEndTest
    // -------------------------------------------------------------------------

    private static void writeSquareShapefile(Path shpPath, double size) throws Exception {
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
