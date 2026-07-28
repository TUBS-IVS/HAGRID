package hagrid.simulation;

import hagrid.integrated.drt.DrtFleetGenerator;
import hagrid.integrated.drt.DrtNetworkPreparer;
import hagrid.integrated.PopulationClipper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.locationtech.jts.geom.*;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.*;
import org.matsim.api.core.v01.population.*;
import org.matsim.contrib.drt.routing.DrtRoute;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.io.NetworkWriter;
import org.matsim.core.population.PopulationUtils;

import java.net.URL;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for {@link DrtScenarioBuilder}.
 * <p>
 * Drives the package-private path-based {@code build(...)} overload with temp-dir
 * fixtures produced by the same helpers used in {@code DrtBaselineIntegrationTest}.
 * Asserts that the returned {@link Scenario} has:
 * <ul>
 *   <li>a non-empty population (proves plans were loaded)</li>
 *   <li>at least one network link carrying the {@code drt} mode (proves DrtNetworkPreparer
 *       ran and ScenarioUtils.loadScenario loaded the augmented network)</li>
 *   <li>the {@link DrtRoute} route-factory registered on the population factory (proves
 *       the two-step create+register+load pattern was used)</li>
 * </ul>
 */
@DisplayName("DrtScenarioBuilder")
class DrtScenarioBuilderTest {

    /** Size of the square service area (metres). All fixture nodes are inside this box. */
    private static final double AREA_SIZE = 2000.0;

    @Test
    @DisplayName("buildsDrtOnlyScenario — returns Scenario with drt-mode links and non-empty population")
    void buildsDrtOnlyScenario(@TempDir Path tmp) throws Exception {

        // --- 1. tiny grid network (all inside the 0..2000 service area) ---
        Network rawNet = buildGrid();
        Path rawNetFile = tmp.resolve("raw_network.xml.gz");
        new NetworkWriter(rawNet).write(rawNetFile.toString());

        // --- 2. Run DrtNetworkPreparer to produce a drt-annotated network ---
        Geometry area = square(AREA_SIZE);
        Network drtNet = DrtNetworkPreparer.prepare(rawNet, area);
        Path drtNetFile = tmp.resolve("drt_network.xml.gz");
        new NetworkWriter(drtNet).write(drtNetFile.toString());

        // --- 3. Fleet ---
        Path fleetFile = tmp.resolve("fleet.xml.gz");
        DrtFleetGenerator.write(drtNet, 4, 8, 0.0, 86400.0, fleetFile);

        // --- 4. Clipped population ---
        Population pop = buildDemand(rawNet);
        Path rawPlansFile = tmp.resolve("raw_plans.xml.gz");
        PopulationUtils.writePopulation(pop, rawPlansFile.toString());
        Path clippedPlansFile = tmp.resolve("clipped_plans.xml.gz");
        PopulationUtils.writePopulation(PopulationClipper.clip(pop, area), clippedPlansFile.toString());

        // --- 5. Base config — use the portable test fixture on the classpath ---
        URL cfgUrl = DrtScenarioBuilderTest.class.getClassLoader()
                .getResource("lausitz-native-like.config.xml");
        assertThat(cfgUrl).as("test fixture lausitz-native-like.config.xml must be on the test classpath")
                .isNotNull();
        String baseConfig = cfgUrl.toString();

        String outDir = tmp.resolve("matsim").toString();

        // --- 6. Invoke the path-based build overload (seed-aware 12-arg variant, review F3:
        // this is the exact overload the HAGRIDSimulationConfig path delegates to, so the
        // seed assertion below covers the real DRT simulation path) ---
        long seed = 20260728L;   // deliberately != the base-config/MATSim default (4711)
        Scenario scenario = DrtScenarioBuilder.build(
                baseConfig,
                drtNetFile.toString(),
                clippedPlansFile.toString(),
                "UNUSED_door2door.shp",  // service area shp — overridden to door2door below
                fleetFile.toString(),
                /*railScheduleFile*/ null, /*railTransitVehiclesFile*/ null, /*vehicleTypesFile*/ null,
                outDir,
                "DRT_TEST_BUILD",
                0,
                seed);

        // Override operational scheme to door2door so no real shapefile is needed.
        // (The Config was already built by LausitzDrtConfigurator; the scenario was loaded from it.)
        // We inspect what was loaded, not re-run the controler, so no override is needed here.

        // --- Assertions ---
        // (a) Non-empty population
        assertThat(scenario.getPopulation().getPersons())
                .as("population must be non-empty after loadScenario")
                .isNotEmpty();

        // (b) At least one link carries the drt mode
        boolean hasDrtLink = scenario.getNetwork().getLinks().values().stream()
                .anyMatch(l -> l.getAllowedModes().contains(TransportMode.drt));
        assertThat(hasDrtLink)
                .as("network must have at least one link with allowed mode 'drt'")
                .isTrue();

        // (c) DrtRoute factory is registered (proves the two-step pattern was used).
        // RouteFactories.getRouteClassForType(type) returns non-null when the factory is registered.
        Class<?> registeredClass = scenario.getPopulation().getFactory().getRouteFactories()
                .getRouteClassForType(DrtRoute.ROUTE_TYPE);
        assertThat(registeredClass)
                .as("DrtRoute route factory must be registered (getRouteClassForType(ROUTE_TYPE) != null)")
                .isNotNull()
                .isEqualTo(DrtRoute.class);

        // (d) F3 load-bearing assertion: the runner seed genuinely reaches the built MATSim
        // config (AbstractController resets MatsimRandom from it every iteration) — it must
        // override whatever the base config pins.
        assertThat(scenario.getConfig().global().getRandomSeed())
                .as("runner seed must reach config.global().randomSeed on the DRT path")
                .isEqualTo(seed);
    }

    // -------------------------------------------------------------------------
    // Fixture helpers (mirrors DrtBaselineIntegrationTest)
    // -------------------------------------------------------------------------

    private Geometry square(double size) {
        GeometryFactory gf = new GeometryFactory();
        return gf.createPolygon(new Coordinate[]{
                new Coordinate(0, 0), new Coordinate(size, 0),
                new Coordinate(size, size), new Coordinate(0, size), new Coordinate(0, 0)});
    }

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

    private Population buildDemand(Network net) {
        Population pop = PopulationUtils.createPopulation(ConfigUtils.createConfig());
        PopulationFactory pf = pop.getFactory();
        for (int i = 0; i < 3; i++) {
            Person p = pf.createPerson(Id.createPersonId("p" + i));
            Plan plan = pf.createPlan();
            Activity h = pf.createActivityFromCoord("home", new Coord(150, 150));
            h.setEndTime(8 * 3600 + i * 60);
            plan.addActivity(h);
            Leg leg = pf.createLeg(TransportMode.walk);
            plan.addLeg(leg);
            plan.addActivity(pf.createActivityFromCoord("work", new Coord(950, 950)));
            p.addPlan(plan);
            p.setSelectedPlan(plan);
            pop.addPerson(p);
        }
        return pop;
    }
}
