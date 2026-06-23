package hagrid.integrated.drt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.*;
import org.matsim.api.core.v01.population.*;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.matsim.core.network.io.NetworkWriter;
import org.matsim.core.population.PopulationUtils;

import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for {@link LausitzDrtPreprocessor}: verifies that the core {@code run(...)}
 * produces a drt-augmented (full) network, a person-only clipped population, and a fleet file.
 */
@DisplayName("LausitzDrtPreprocessor")
class LausitzDrtPreprocessorTest {

    // Service area: 0..2000 square. All "inside" nodes are at 100..1000, outside at 3000.
    private static final double AREA_SIZE = 2000.0;

    @Test
    @DisplayName("producesDrtNetworkClippedPlansAndFleet")
    void producesDrtNetworkClippedPlansAndFleet(@TempDir Path tmp) throws Exception {

        // ---- fixture network ------------------------------------------------
        // Car square (4 nodes, 8 directed links) — all inside the service area.
        // Plus one isolated rail/pt link outside the area (node coords at 3000).
        Network fullNet = buildFixtureNetwork();
        Path rawNetFile = tmp.resolve("raw_network.xml.gz");
        new NetworkWriter(fullNet).write(rawNetFile.toString());

        // ---- fixture population -----------------------------------------------
        // 2 "person" agents inside, 1 "person" agent outside, 1 "goodsTraffic" agent inside.
        Path rawPlansFile = tmp.resolve("raw_plans.xml.gz");
        PopulationUtils.writePopulation(buildFixturePopulation(), rawPlansFile.toString());

        // ---- service-area shapefile -------------------------------------------
        Path shpFile = tmp.resolve("service-area.shp");
        writeSquareShapefile(shpFile, AREA_SIZE);

        // ---- output paths -----------------------------------------------------
        Path drtNetOut   = tmp.resolve("drt_network.xml.gz");
        Path plansOut    = tmp.resolve("clipped_plans.xml.gz");
        Path fleetOut    = tmp.resolve("fleet.xml");   // plain XML so we can grep without decompressing
        int  fleetSize   = 3;

        // ---- invoke -----------------------------------------------------------
        LausitzDrtPreprocessor.run(
                rawNetFile.toString(),
                rawPlansFile.toString(),
                shpFile.toString(),
                drtNetOut.toString(),
                plansOut.toString(),
                fleetOut.toString(),
                fleetSize, 8, 0.0, 86400.0);

        // ===== (a) DRT network assertions =====
        Network drtNet = NetworkUtils.createNetwork();
        new MatsimNetworkReader(drtNet).readFile(drtNetOut.toString());

        // Full network preserved: same link count as the input network.
        assertThat(drtNet.getLinks().size())
                .as("full network link count must be preserved")
                .isEqualTo(fullNet.getLinks().size());

        // Inside car links must carry "drt".
        for (Link link : drtNet.getLinks().values()) {
            String id = link.getId().toString();
            if (id.startsWith("car_")) {
                assertThat(link.getAllowedModes())
                        .as("car link %s inside area must have drt mode", id)
                        .contains(TransportMode.drt);
            } else if (id.equals("rail_0")) {
                // The rail link must NOT get the drt mode.
                assertThat(link.getAllowedModes())
                        .as("rail link must NOT have drt mode")
                        .doesNotContain(TransportMode.drt);
            }
        }

        // ===== (b) Clipped plans assertions =====
        Population clippedPop = PopulationUtils.readPopulation(plansOut.toString());

        // Only the 2 inside-person agents must be present.
        assertThat(clippedPop.getPersons().size())
                .as("only the 2 inside-person agents should remain")
                .isEqualTo(2);
        assertThat(clippedPop.getPersons().keySet())
                .extracting(Id::toString)
                .containsExactlyInAnyOrder("person_inside_0", "person_inside_1");

        // ===== (c) Fleet file assertions =====
        // The fleet file must exist and contain exactly fleetSize vehicles.
        assertThat(fleetOut).exists();
        String fleetXml = java.nio.file.Files.readString(fleetOut);
        long vehicleCount = fleetXml.lines()
                .filter(l -> l.contains("id=\"drt_"))
                .count();
        assertThat(vehicleCount)
                .as("fleet file must contain exactly %d vehicles", fleetSize)
                .isEqualTo(fleetSize);
    }

    // -------------------------------------------------------------------------
    // Fixture builders
    // -------------------------------------------------------------------------

    /**
     * Builds a network with:
     * <ul>
     *   <li>A 4-node car square (8 directed car links) — all nodes at 100..1000, inside the area.</li>
     *   <li>One rail link between two nodes at coord 3000 (outside the area).</li>
     * </ul>
     */
    private Network buildFixtureNetwork() {
        Network net = NetworkUtils.createNetwork();
        NetworkFactory f = net.getFactory();

        // Car square nodes — inside the service area (0..2000).
        double[][] xy = {{100, 100}, {1000, 100}, {1000, 1000}, {100, 1000}};
        Node[] nodes = new Node[4];
        for (int i = 0; i < 4; i++) {
            nodes[i] = f.createNode(Id.createNodeId("cn" + i), new Coord(xy[i][0], xy[i][1]));
            net.addNode(nodes[i]);
        }
        for (int i = 0; i < 4; i++) {
            addCarLink(net, f, "car_" + i,       nodes[i], nodes[(i + 1) % 4]);
            addCarLink(net, f, "car_" + i + "r", nodes[(i + 1) % 4], nodes[i]);
        }

        // Rail link — nodes outside the service area.
        Node rn0 = f.createNode(Id.createNodeId("rn0"), new Coord(3000, 3000));
        Node rn1 = f.createNode(Id.createNodeId("rn1"), new Coord(4000, 3000));
        net.addNode(rn0);
        net.addNode(rn1);
        Link rail = f.createLink(Id.createLinkId("rail_0"), rn0, rn1);
        rail.setLength(1000);
        rail.setFreespeed(30.0);
        rail.setCapacity(1000);
        rail.setNumberOfLanes(1);
        rail.setAllowedModes(Set.of("rail"));
        net.addLink(rail);

        return net;
    }

    private void addCarLink(Network net, NetworkFactory f, String id, Node a, Node b) {
        Link l = f.createLink(Id.createLinkId(id), a, b);
        l.setLength(1000);
        l.setFreespeed(13.9);
        l.setCapacity(1800);
        l.setNumberOfLanes(1);
        l.setAllowedModes(Set.of("car"));
        net.addLink(l);
    }

    /**
     * Builds a population with:
     * <ul>
     *   <li>2 {@code person} agents whose home is inside the area (coord 150, 150).</li>
     *   <li>1 {@code person} agent whose home is outside the area (coord 3000, 3000).</li>
     *   <li>1 {@code goodsTraffic} agent whose home is inside the area.</li>
     * </ul>
     */
    private Population buildFixturePopulation() {
        Population pop = PopulationUtils.createPopulation(ConfigUtils.createConfig());
        PopulationFactory pf = pop.getFactory();

        // 2 inside persons
        for (int i = 0; i < 2; i++) {
            Person p = pf.createPerson(Id.createPersonId("person_inside_" + i));
            PopulationUtils.putSubpopulation(p, "person");
            Plan plan = pf.createPlan();
            Activity home = pf.createActivityFromCoord("home", new Coord(150, 150));
            home.setEndTime(8 * 3600 + i * 60);
            plan.addActivity(home);
            plan.addLeg(pf.createLeg(TransportMode.walk));
            plan.addActivity(pf.createActivityFromCoord("work", new Coord(900, 900)));
            p.addPlan(plan);
            p.setSelectedPlan(plan);
            pop.addPerson(p);
        }

        // 1 outside person
        Person outside = pf.createPerson(Id.createPersonId("person_outside_0"));
        PopulationUtils.putSubpopulation(outside, "person");
        Plan outsidePlan = pf.createPlan();
        Activity outsideHome = pf.createActivityFromCoord("home", new Coord(3000, 3000));
        outsideHome.setEndTime(9 * 3600);
        outsidePlan.addActivity(outsideHome);
        outsidePlan.addLeg(pf.createLeg(TransportMode.walk));
        outsidePlan.addActivity(pf.createActivityFromCoord("work", new Coord(3500, 3500)));
        outside.addPlan(outsidePlan);
        outside.setSelectedPlan(outsidePlan);
        pop.addPerson(outside);

        // 1 goodsTraffic agent inside
        Person goods = pf.createPerson(Id.createPersonId("goodsTraffic_0"));
        PopulationUtils.putSubpopulation(goods, "goodsTraffic");
        Plan goodsPlan = pf.createPlan();
        Activity goodsHome = pf.createActivityFromCoord("home", new Coord(150, 150));
        goodsHome.setEndTime(7 * 3600);
        goodsPlan.addActivity(goodsHome);
        goodsPlan.addLeg(pf.createLeg("goodsTraffic"));
        goodsPlan.addActivity(pf.createActivityFromCoord("delivery", new Coord(900, 900)));
        goods.addPlan(goodsPlan);
        goods.setSelectedPlan(goodsPlan);
        pop.addPerson(goods);

        return pop;
    }

    /**
     * Writes a square polygon of the given size (0..size, 0..size) as a minimal Shapefile
     * using raw binary output — bypassing GeoTools' feature-serialization path which triggers
     * a JTS version conflict (GeoTools 31.1 compiled against JTS ≥1.18 calls LinearRing
     * Polygon.getExteriorRing(), but the runtime JTS on the classpath is 1.16.1 where the
     * return type is still LineString).
     *
     * <p>The three written files are:
     * <ul>
     *   <li>{@code .shp} — main shape file (100-byte file header + 8-byte record header +
     *       shape-type-5 polygon content)</li>
     *   <li>{@code .shx} — index file (100-byte file header + 8-byte index record)</li>
     *   <li>{@code .dbf} — empty dBase III attribute table (required by ShpOptions)</li>
     * </ul>
     */
    private void writeSquareShapefile(Path shpPath, double size) throws Exception {
        // Ring: 5 points (closed), counter-clockwise: SW→SE→NE→NW→SW.
        double[] xs = {0, size, size, 0,    0};
        double[] ys = {0, 0,    size, size, 0};
        int numPoints = xs.length;

        // ---- .shp content ---------------------------------------------------
        // Polygon shape type = 5.
        // Content after shape type:
        //   Bounding box (4 doubles LE): minX, minY, maxX, maxY
        //   NumParts (1 int LE)
        //   NumPoints (1 int LE)
        //   Parts[0] (1 int LE) = 0
        //   Points: numPoints * (double x, double y) LE
        // contentBytes = shape-type(4) + bbox(32) + numParts(4) + numPoints(4) + parts[0](4) + points
        int contentBytes = 4 + (4 * 8) + 4 + 4 + 4 + numPoints * 16;
        // file length in 16-bit words: 100-byte header + 8-byte record header + content
        int fileLength16 = (100 + 8 + contentBytes) / 2;
        // Record content length in 16-bit words = contentBytes / 2
        int recContentLen16 = contentBytes / 2;

        java.io.ByteArrayOutputStream shpBuf = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream shp = new java.io.DataOutputStream(shpBuf);
        // File header (100 bytes)
        shp.writeInt(9994);             // file code (big-endian)
        for (int i = 0; i < 5; i++) shp.writeInt(0); // unused
        shp.writeInt(fileLength16);     // file length in 16-bit words (BE)
        writeIntLE(shp, 1000);          // version (LE)
        writeIntLE(shp, 5);             // shape type = Polygon (LE)
        writeDoubleLE(shp, 0.0);        // Xmin
        writeDoubleLE(shp, 0.0);        // Ymin
        writeDoubleLE(shp, size);       // Xmax
        writeDoubleLE(shp, size);       // Ymax
        for (int i = 0; i < 4; i++) writeDoubleLE(shp, 0.0); // Zmin,Zmax,Mmin,Mmax
        // Record header (8 bytes, big-endian)
        shp.writeInt(1);                // record number (1-based, BE)
        shp.writeInt(recContentLen16);  // content length in 16-bit words (BE)
        // Record content (LE)
        writeIntLE(shp, 5);             // shape type = Polygon
        writeDoubleLE(shp, 0.0);        // Xmin
        writeDoubleLE(shp, 0.0);        // Ymin
        writeDoubleLE(shp, size);       // Xmax
        writeDoubleLE(shp, size);       // Ymax
        writeIntLE(shp, 1);             // NumParts
        writeIntLE(shp, numPoints);     // NumPoints
        writeIntLE(shp, 0);             // Parts[0]
        for (int i = 0; i < numPoints; i++) {
            writeDoubleLE(shp, xs[i]);
            writeDoubleLE(shp, ys[i]);
        }
        shp.flush();
        byte[] shpBytes = shpBuf.toByteArray();
        java.nio.file.Files.write(shpPath, shpBytes);

        // ---- .shx content ---------------------------------------------------
        // 100-byte header + one 8-byte record: (offset, content-length) in 16-bit words BE.
        int shxFileLen16 = (100 + 8) / 2;
        int offsetWords = 100 / 2; // offset of first record in .shp, in 16-bit words
        java.io.ByteArrayOutputStream shxBuf = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream shx = new java.io.DataOutputStream(shxBuf);
        shx.writeInt(9994);
        for (int i = 0; i < 5; i++) shx.writeInt(0);
        shx.writeInt(shxFileLen16);
        writeIntLE(shx, 1000);
        writeIntLE(shx, 5);
        writeDoubleLE(shx, 0.0); writeDoubleLE(shx, 0.0);
        writeDoubleLE(shx, size); writeDoubleLE(shx, size);
        for (int i = 0; i < 4; i++) writeDoubleLE(shx, 0.0);
        shx.writeInt(offsetWords);      // offset of record 1 in .shp
        shx.writeInt(recContentLen16);  // content length
        shx.flush();
        Path shxPath = shpPath.resolveSibling(
                shpPath.getFileName().toString().replace(".shp", ".shx"));
        java.nio.file.Files.write(shxPath, shxBuf.toByteArray());

        // ---- .dbf — minimal dBase III header (no records, no fields) --------
        // Header: 32 bytes minimum; terminated by 0x0D.
        Path dbfPath = shpPath.resolveSibling(
                shpPath.getFileName().toString().replace(".shp", ".dbf"));
        byte[] dbf = new byte[33];
        dbf[0] = 3;         // version dBase III
        dbf[1] = 26;        // year
        dbf[2] = 1;         // month
        dbf[3] = 1;         // day
        // records count (LE int32) at offset 4 = 0
        // header size (LE int16) at offset 8
        dbf[8] = 33; dbf[9] = 0;  // header size = 33 bytes
        // record size (LE int16) at offset 10 = 1 (just the deletion flag)
        dbf[10] = 1; dbf[11] = 0;
        // terminator
        dbf[32] = 0x0D;
        java.nio.file.Files.write(dbfPath, dbf);
    }

    private static void writeIntLE(java.io.DataOutputStream out, int v) throws java.io.IOException {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
        out.write((v >> 16) & 0xFF);
        out.write((v >> 24) & 0xFF);
    }

    private static void writeDoubleLE(java.io.DataOutputStream out, double v) throws java.io.IOException {
        long bits = Double.doubleToLongBits(v);
        for (int i = 0; i < 8; i++) {
            out.write((int)(bits & 0xFF));
            bits >>= 8;
        }
    }
}
