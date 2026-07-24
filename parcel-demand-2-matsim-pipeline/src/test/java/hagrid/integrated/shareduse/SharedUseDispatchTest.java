package hagrid.integrated.shareduse;

import hagrid.integrated.drt.DrtConfigComposer;
import hagrid.integrated.drt.DrtFleetGenerator;
import hagrid.integrated.drt.DrtNetworkPreparer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.NetworkFactory;
import org.matsim.api.core.v01.network.Node;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PopulationFactory;
import org.matsim.contrib.drt.passenger.events.DrtRequestSubmittedEvent;
import org.matsim.contrib.drt.passenger.events.DrtRequestSubmittedEventHandler;
import org.matsim.contrib.drt.routing.DrtRoute;
import org.matsim.contrib.drt.routing.DrtRouteFactory;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.io.NetworkWriter;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.api.core.v01.Scenario;
import org.matsim.testcases.MatsimTestUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boot-level test for Task 6's runner dispatch: the FIRST test that actually installs
 * {@link SharedUseModule} inside a real {@link Controler} injector (mirrors
 * {@code DrtBaselineIntegrationTest}'s composed-config, inline-fixture style; no real
 * Lausitz shapefiles/rail schedule needed).
 *
 * <p>Verifies two things Task 4/5's config-only and unit tests could not:
 * <ul>
 *   <li>the injector boots with {@link SharedUseModule} installed (no
 *       {@code BindingAlreadySet} / missing-binding crash — i.e. its controller- and
 *       QSim-scope overrides are compatible with the base {@code DrtModeModule}/
 *       {@code FleetModule}/{@code DrtModeOptimizerQSimModule} bindings); and</li>
 *   <li>a {@code parcel_}-prefixed person's DRT request is actually submitted at runtime
 *       ({@link DrtRequestSubmittedEvent} fires on request creation regardless of whether
 *       the request is later inserted/rejected, so this does not depend on tuning fleet
 *       size or χ for a successful pickup — see {@code DrtRequestCreator}/
 *       {@code DefaultPassengerEngine}).</li>
 * </ul>
 */
@DisplayName("DRT_SHAREDUSE dispatch boot test (SharedUseModule)")
class SharedUseDispatchTest {

    @RegisterExtension
    public MatsimTestUtils utils = new MatsimTestUtils();

    @Test
    @DisplayName("SharedUseModule boots and a parcel person's DRT request is submitted")
    void sharedUseModuleBootsAndSubmitsParcelRequest() throws Exception {
        Path dir = Path.of(utils.getOutputDirectory());

        // --- tiny grid network, fully inside a 0..2000 service square (same fixture shape
        // as DrtBaselineIntegrationTest) ---
        Network net = grid();
        Geometry area = square(2000);
        Network drtNet = DrtNetworkPreparer.prepare(net, area);
        Path netFile = dir.resolve("drt_network.xml.gz");
        new NetworkWriter(drtNet).write(netFile.toString());

        // --- fleet: 4 vehicles, base Shared-Use seat count ---
        Path fleet = dir.resolve("fleet.xml.gz");
        DrtFleetGenerator.write(drtNet, 4, SharedUse.SEATS, 0.0, 86400.0, fleet);

        // --- config: composed baseline + Shared-Use additions ---
        Config config = ConfigUtils.createConfig();
        config.global().setCoordinateSystem("EPSG:25832");
        config.network().setInputFile(netFile.toString());
        config.controller().setOutputDirectory(dir.resolve("matsim").toString());
        config.controller().setLastIteration(0);
        config.controller().setOverwriteFileSetting(
                OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);

        DrtConfigComposer.composeConfig(config, "UNUSED_for_door2door.shp", fleet.toString());
        // Smoke test: door2door so no real DRT service-area shapefile is needed.
        DrtConfigGroup drtCfg = MultiModeDrtConfigGroup.get(config).getModalElements().iterator().next();
        drtCfg.setOperationalScheme(DrtConfigGroup.OperationalScheme.door2door);
        drtCfg.setDrtServiceAreaShapeFile(null);
        // Same ordering as the runner dispatch: composeSharedUse AFTER composeConfig, BEFORE
        // the Controler is constructed (DRT config groups are read at construction time).
        DrtConfigComposer.composeSharedUse(config);

        Scenario scenario = ScenarioUtils.createScenario(config);
        // Register the DrtRoute factory BEFORE any routing happens (mirrors DrtScenarioBuilder /
        // DrtBaselineIntegrationTest): without it, DvrpRoutingModule's mode-keyed route lookup
        // falls back to a plain GenericRouteImpl, which DrtRouteCreator then fails to cast to
        // DrtRoute during PersonPrepareForSim.
        scenario.getPopulation().getFactory().getRouteFactories()
                .setRouteFactory(DrtRoute.class, new DrtRouteFactory());
        ScenarioUtils.loadScenario(scenario); // network only; population is built in-memory below

        // --- one parcel-person, hand-built the same way ParcelAgentGenerator does ---
        addParcelPerson(scenario);

        Controler controler = new Controler(scenario);
        DrtConfigComposer.installModules(controler);

        List<DrtRequestSubmittedEvent> submitted = new ArrayList<>();
        controler.getEvents().addHandler((DrtRequestSubmittedEventHandler) submitted::add);

        // LAST overriding module — must override the base bindings installed above.
        controler.addOverridingModule(new SharedUseModule(drtCfg, 600.0));

        controler.run();

        assertThat(submitted)
                .as("expected at least one DrtRequestSubmittedEvent for a parcel_ person")
                .anySatisfy(event -> assertThat(event.getPersonIds())
                        .anyMatch(id -> SharedUse.isParcelPerson(id.toString())));
    }

    // --- fixture helpers ---

    private void addParcelPerson(Scenario scenario) {
        PopulationFactory pf = scenario.getPopulation().getFactory();
        Person p = pf.createPerson(Id.createPersonId(SharedUse.PARCEL_PERSON_PREFIX + "test_1_B2C"));
        PopulationUtils.putSubpopulation(p, SharedUse.PARCEL_SUBPOPULATION);
        p.getAttributes().putAttribute(SharedUse.LOAD_ATTRIBUTE, 3);
        p.getAttributes().putAttribute(SharedUse.DWELL_ATTRIBUTE, SharedUse.segmentDwellSeconds(3));
        p.getAttributes().putAttribute(SharedUse.WINDOW_END_ATTRIBUTE, SharedUse.B2C_WINDOW_END_S);

        Plan plan = pf.createPlan();
        var depot = pf.createActivityFromLinkId(SharedUse.ACT_DEPOT, Id.createLinkId("l0"));
        depot.setEndTime(8 * 3600.0);
        plan.addActivity(depot);
        plan.addLeg(pf.createLeg("drt"));
        plan.addActivity(pf.createActivityFromLinkId(SharedUse.ACT_DELIVERY, Id.createLinkId("l2")));
        p.addPlan(plan);
        p.setSelectedPlan(plan);
        scenario.getPopulation().addPerson(p);
    }

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
}
