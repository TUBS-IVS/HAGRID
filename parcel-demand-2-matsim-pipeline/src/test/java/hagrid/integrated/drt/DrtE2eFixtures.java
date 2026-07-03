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
import org.matsim.core.population.PopulationUtils;

import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * Shared raw-fixture builders for the DRT end-to-end tests (passenger-only and married).
 * Moved verbatim out of {@link DrtBaselineEndToEndTest} so {@link MarriedBaselineEndToEndTest}
 * can reuse the identical network/demand/shapefile fixtures without duplicating them.
 */
public final class DrtE2eFixtures {

    private DrtE2eFixtures() {} // fixture holder

    /** 4-node car square (8 directed car links), all nodes at 100..1000 (inside the area). */
    public static Network buildGrid() {
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

    public static void addLink(Network n, NetworkFactory f, String id, Node a, Node b) {
        Link l = f.createLink(Id.createLinkId(id), a, b);
        l.setLength(1000);
        l.setFreespeed(13.9);
        l.setCapacity(1800);
        l.setNumberOfLanes(1);
        l.setAllowedModes(Set.of("car"));
        n.addLink(l);
    }

    /**
     * Five {@code person}-subpopulation agents making a drt trip to work, plus ONE agent
     * whose plan contains a {@code pt} leg (home -> pt -> work).
     *
     * <p>The pt-leg agent exercises the teleported-pt-router boot fix: when
     * {@code transit} is disabled, {@code PersonPrepareForSim} still re-routes every
     * initial leg at iteration-0 start, and without a registered teleported-pt routing
     * module MATSim would throw {@code TripRouter$UnknownModeException}.  Adding this agent
     * to the fixture population ensures the fix is load-tested in the e2e run; if
     * {@code addTeleportedModeParams(pt)} were removed from the configurator the run would
     * crash here rather than silently passing.</p>
     *
     * <p>Activity types use the VSP typed convention
     * ({@code home_<sec>} / {@code work_<sec>}, durations a multiple of 600s within
     * 600..97200) so {@link org.matsim.contrib.vsp.scenario.SnzActivities#addScoringParams}
     * (called by the configurator) registers matching scoring params.</p>
     */
    public static Population buildDemand() {
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
        // ONE agent with a pt leg - exercises the teleported-pt-router boot fix
        // (boot fix: addTeleportedModeParams(pt) in LausitzDrtConfigurator.build()).
        // PersonPrepareForSim re-routes this leg at iteration-0 start through the
        // beeline teleported router.  Without the fix the Controler would throw
        // TripRouter$UnknownModeException("unregistered main mode |pt|") here.
        Person ptAgent = pf.createPerson(Id.createPersonId("pt_legacy_agent"));
        PopulationUtils.putSubpopulation(ptAgent, "person");
        Plan ptPlan = pf.createPlan();
        Activity ptHome = pf.createActivityFromCoord("home_57600", new Coord(150, 150));
        ptHome.setEndTime(9 * 3600);
        ptPlan.addActivity(ptHome);
        ptPlan.addLeg(pf.createLeg(TransportMode.pt));
        ptPlan.addActivity(pf.createActivityFromCoord("work_28800", new Coord(950, 950)));
        ptAgent.addPlan(ptPlan);
        ptAgent.setSelectedPlan(ptPlan);
        pop.addPerson(ptAgent);
        return pop;
    }

    // -------------------------------------------------------------------------
    // Minimal shapefile writer (square polygon), bypassing GeoTools feature
    // serialization (JTS 1.16 / GeoTools 31.1 LinearRing return-type conflict).
    // Mirrors LausitzDrtPreprocessorTest#writeSquareShapefile. The produced .shp is
    // read at run time by both LausitzDrtPreprocessor (GeoFileReader.getAllFeatures)
    // and the DRT module (ShpGeometryUtils.loadPreparedGeometries -> same reader).
    // -------------------------------------------------------------------------

    public static void writeSquareShapefile(Path shpPath, double size) throws Exception {
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

    public static void writeIntLE(DataOutputStream out, int v) throws java.io.IOException {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
        out.write((v >> 16) & 0xFF);
        out.write((v >> 24) & 0xFF);
    }

    public static void writeDoubleLE(DataOutputStream out, double v) throws java.io.IOException {
        long bits = Double.doubleToLongBits(v);
        for (int i = 0; i < 8; i++) {
            out.write((int) (bits & 0xFF));
            bits >>= 8;
        }
    }
}
