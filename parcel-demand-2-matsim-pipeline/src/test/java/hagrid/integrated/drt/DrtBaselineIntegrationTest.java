package hagrid.integrated.drt;

import hagrid.integrated.PopulationClipper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.locationtech.jts.geom.*;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.*;
import org.matsim.api.core.v01.population.*;
import org.matsim.contrib.drt.routing.DrtRoute;
import org.matsim.contrib.drt.routing.DrtRouteFactory;
import org.matsim.contrib.dvrp.run.DvrpConfigGroup;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.io.NetworkWriter;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.testcases.MatsimTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

@DisplayName("DRT_BASELINE integration smoke test")
class DrtBaselineIntegrationTest {

    @RegisterExtension
    public MatsimTestUtils utils = new MatsimTestUtils();

    @Test
    @DisplayName("composed full-DVRP DRT runs one iteration and produces output")
    void runsOneIteration() throws Exception {
        Path dir = Path.of(utils.getOutputDirectory());

        // --- tiny grid network fully inside a 0..2000 service square ---
        Network net = grid();
        Geometry area = square(2000);
        Network drtNet = DrtNetworkPreparer.prepare(net, area);
        Path netFile = dir.resolve("drt_network.xml.gz");
        new NetworkWriter(drtNet).write(netFile.toString());

        // --- fleet ---
        Path fleet = dir.resolve("fleet.xml.gz");
        DrtFleetGenerator.write(drtNet, 4, 8, 0.0, 86400.0, fleet);

        // --- tiny population, all homes inside the area ---
        Population pop = demand(drtNet);
        Path plans = dir.resolve("plans.xml.gz");
        PopulationUtils.writePopulation(PopulationClipper.clip(pop, area), plans.toString());

        // --- config ---
        Config config = ConfigUtils.createConfig();
        config.global().setCoordinateSystem("EPSG:25832");
        config.network().setInputFile(netFile.toString());
        config.plans().setInputFile(plans.toString());
        config.controller().setOutputDirectory(dir.resolve("matsim").toString());
        config.controller().setLastIteration(0);
        config.controller().setOverwriteFileSetting(
                OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
        // generic activity scoring so agents can score
        ScoringConfigGroup.ActivityParams home = new ScoringConfigGroup.ActivityParams("home");
        home.setTypicalDuration(12 * 3600);
        config.scoring().addActivityParams(home);
        ScoringConfigGroup.ActivityParams work = new ScoringConfigGroup.ActivityParams("work");
        work.setTypicalDuration(8 * 3600);
        config.scoring().addActivityParams(work);

        // drt leg scoring params (needed by CharyparNagel scorer; DrtConfigs adds staging activity but not the leg mode)
        ScoringConfigGroup.ModeParams drtMode = new ScoringConfigGroup.ModeParams(TransportMode.drt);
        config.scoring().addModeParams(drtMode);

        DrtConfigComposer.composeConfig(config, "UNUSED_for_door2door.shp", fleet.toString());
        // For the smoke test, override to door2door so no real shapefile is needed.
        org.matsim.contrib.drt.run.MultiModeDrtConfigGroup.get(config).getModalElements()
                .iterator().next().operationalScheme =
                org.matsim.contrib.drt.run.DrtConfigGroup.OperationalScheme.door2door;
        org.matsim.contrib.drt.run.MultiModeDrtConfigGroup.get(config).getModalElements()
                .iterator().next().drtServiceAreaShapeFile = null;

        // Build scenario first so we can register the DrtRouteFactory before loading the
        // plans file — ScenarioUtils.loadScenario requires it to deserialise DrtRoute legs.
        // This mirrors LausitzDrtScenario / DrtAndIntermodalityOptions.configureDrtScenario.
        Scenario scenario = ScenarioUtils.createScenario(config);
        scenario.getPopulation().getFactory().getRouteFactories()
                .setRouteFactory(DrtRoute.class, new DrtRouteFactory());
        ScenarioUtils.loadScenario(scenario);

        Controler controler = new Controler(scenario);
        DrtConfigComposer.installModules(controler);
        controler.run();

        // DRT output produced
        Path drtOut = dir.resolve("matsim");
        assertThat(Files.exists(drtOut)).isTrue();
        assertThat(DvrpConfigGroup.get(config).networkModes).contains(TransportMode.drt);
        // a DRT-specific output file exists (customer stats / vehicle stats)
        try (var stream = Files.walk(drtOut)) {
            assertThat(stream.anyMatch(p -> p.getFileName().toString().toLowerCase().contains("drt")))
                    .as("expected at least one drt_* output file").isTrue();
        }
    }

    // --- helpers ---

    private Geometry square(double size) {
        GeometryFactory gf = new GeometryFactory();
        return gf.createPolygon(new Coordinate[]{
                new Coordinate(0, 0), new Coordinate(size, 0),
                new Coordinate(size, size), new Coordinate(0, size), new Coordinate(0, 0)});
    }

    private Network grid() {
        Network n = NetworkUtils.createNetwork();
        NetworkFactory f = n.getFactory();
        Node[] nodes = new Node[4];
        double[][] xy = {{100, 100}, {1000, 100}, {1000, 1000}, {100, 1000}};
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

    private Population demand(Network net) {
        Population pop = PopulationUtils.createPopulation(ConfigUtils.createConfig());
        PopulationFactory pf = pop.getFactory();
        for (int i = 0; i < 5; i++) {
            Person p = pf.createPerson(Id.createPersonId("p" + i));
            Plan plan = pf.createPlan();
            Activity h = pf.createActivityFromCoord("home", new Coord(150, 150));
            h.setEndTime(8 * 3600 + i * 60);
            plan.addActivity(h);
            Leg leg = pf.createLeg(TransportMode.drt);
            plan.addLeg(leg);
            plan.addActivity(pf.createActivityFromCoord("work", new Coord(950, 950)));
            p.addPlan(plan);
            p.setSelectedPlan(plan);
            pop.addPerson(p);
        }
        return pop;
    }
}
