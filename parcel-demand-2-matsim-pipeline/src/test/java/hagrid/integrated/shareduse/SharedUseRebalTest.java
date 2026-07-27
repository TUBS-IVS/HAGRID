package hagrid.integrated.shareduse;

import com.google.inject.Key;
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
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.events.PersonDepartureEvent;
import org.matsim.api.core.v01.events.handler.PersonDepartureEventHandler;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.NetworkFactory;
import org.matsim.api.core.v01.network.Node;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PopulationFactory;
import org.matsim.contrib.drt.optimizer.rebalancing.demandestimator.ZonalDemandEstimator;
import org.matsim.contrib.drt.routing.DrtRoute;
import org.matsim.contrib.drt.routing.DrtRouteFactory;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.contrib.dvrp.run.DvrpModes;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ReplanningConfigGroup;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.io.NetworkWriter;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.testcases.MatsimTestUtils;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for 1c final-review C2/I2: the pax-only rebalancing demand estimator (M7).
 * Installs the FULL production dispatch - the depot-aware
 * {@link DrtConfigComposer#installModules(Controler, List, double, double, double)} overload
 * (which adds {@code ReturnToDepotRebalancingModule}) - WITH parcels present, and asserts that
 * the {@link PaxOnlyPreviousIterationDrtDemandEstimator} bound as the modal
 * {@link ZonalDemandEstimator} EXCLUDES parcel departures from the rebalancing demand while
 * counting every passenger departure.
 *
 * <p>This is the coverage gap that hid C2: no prior test exercised rebalancing + parcels
 * together. Modelled on {@code SharedUseDispatchTest}'s light inline fixture (door2door, no
 * shapefiles) so the native {@code DrtZonalWaitTimesAnalyzer} geopackage write stays on a short
 * path (SQLITE_CANTOPEN guard); a SHORT run id is implied by the short output dir.</p>
 */
@DisplayName("DRT_SHAREDUSE pax-only rebalancing demand (M7, 1c final-review C2/I2)")
class SharedUseRebalTest {

    @RegisterExtension
    public MatsimTestUtils utils = new MatsimTestUtils();

    @Test
    @DisplayName("pax-only estimator counts passenger drt departures and excludes parcel phantom departures")
    void excludesParcelDepartures() throws Exception {
        Path dir = Path.of(utils.getOutputDirectory());

        Network net = grid();
        Geometry area = square(2000);
        Network drtNet = DrtNetworkPreparer.prepare(net, area);
        Path netFile = dir.resolve("net.xml.gz");
        new NetworkWriter(drtNet).write(netFile.toString());

        Path fleet = dir.resolve("fleet.xml.gz");
        DrtFleetGenerator.write(drtNet, 4, SharedUse.SEATS, 0.0, 86400.0, fleet);

        Config config = ConfigUtils.createConfig();
        config.global().setCoordinateSystem("EPSG:25832");
        config.network().setInputFile(netFile.toString());
        config.controller().setOutputDirectory(dir.resolve("matsim").toString());
        config.controller().setLastIteration(0);
        config.controller().setOverwriteFileSetting(
                OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);

        // Composed baseline (adds MinCostFlow rebalancing with PreviousIterationDemand -> a stock
        // ZonalDemandEstimator binding exists to override) + Shared-Use additions.
        DrtConfigComposer.composeConfig(config, "UNUSED_for_door2door.shp", fleet.toString());
        DrtConfigGroup drtCfg = MultiModeDrtConfigGroup.get(config).getModalElements().iterator().next();
        drtCfg.setOperationalScheme(DrtConfigGroup.OperationalScheme.door2door);
        drtCfg.setDrtServiceAreaShapeFile(null);
        DrtConfigComposer.composeSharedUse(config);

        // Passenger subpopulation needs a replanning strategy (composeSharedUse only adds the
        // parcel-subpop selector). Pure selector: one plan, no innovation.
        ReplanningConfigGroup.StrategySettings paxSelector = new ReplanningConfigGroup.StrategySettings();
        paxSelector.setStrategyName("ChangeExpBeta");
        paxSelector.setWeight(1.0);
        config.replanning().addStrategySettings(paxSelector);

        // Non-scoring pax activity types (avoid needing SnzActivities typical-duration params).
        for (String actType : List.of("home", "work")) {
            ScoringConfigGroup.ActivityParams params = new ScoringConfigGroup.ActivityParams(actType);
            params.setScoringThisActivityAtAll(false);
            config.scoring().addActivityParams(params);
        }

        Scenario scenario = ScenarioUtils.createScenario(config);
        scenario.getPopulation().getFactory().getRouteFactories()
                .setRouteFactory(DrtRoute.class, new DrtRouteFactory());
        ScenarioUtils.loadScenario(scenario); // network only; population built in-memory below

        int paxCount = 3;
        int parcelCount = 2;
        for (int i = 0; i < paxCount; i++) {
            addPaxPerson(scenario, "pax_" + i, 7 * 3600.0 + i * 120);
        }
        for (int i = 0; i < parcelCount; i++) {
            addParcelPerson(scenario, SharedUse.PARCEL_PERSON_PREFIX + "dhl_" + i + "_B2C");
        }

        Controler controler = new Controler(scenario);
        // FULL production dispatch incl. ReturnToDepotRebalancingModule. demandEstimationPeriod MUST
        // equal the config's demand-estimation period (== the pax-only estimator's timeBinSize), else
        // ZonalDemandEstimator#getExpectedDemand's estimationPeriod==timeBinSize precondition fails.
        DrtConfigComposer.installModules(controler,
                List.of(new Coord(500, 500)), /*returnStart*/ 18 * 3600.0,
                /*perDepotCapacity*/ 4, /*demandEstimationPeriod*/ 1800.0);
        // LAST overriding module: overrides the stock ZonalDemandEstimator with the pax-only one.
        controler.addOverridingModule(new SharedUseModule(drtCfg, 999_999.0));

        // Spy: independently count drt departures, split pax vs parcel, over iteration 0.
        AtomicInteger spyPaxDrt = new AtomicInteger();
        AtomicInteger spyParcelDrt = new AtomicInteger();
        controler.getEvents().addHandler((PersonDepartureEventHandler) event -> {
            if (!TransportMode.drt.equals(event.getLegMode())) {
                return;
            }
            if (SharedUse.isParcelPerson(event.getPersonId().toString())) {
                spyParcelDrt.incrementAndGet();
            } else {
                spyPaxDrt.incrementAndGet();
            }
        });

        controler.run();

        // The pax-only estimator is THE bound rebalancing demand estimator (the override won).
        Object bound = controler.getInjector()
                .getInstance(Key.get(ZonalDemandEstimator.class, DvrpModes.mode(TransportMode.drt)));
        assertThat(bound)
                .as("rebalancing ZonalDemandEstimator must be the pax-only estimator (SharedUseModule override wins)")
                .isInstanceOf(PaxOnlyPreviousIterationDrtDemandEstimator.class);
        PaxOnlyPreviousIterationDrtDemandEstimator estimator = (PaxOnlyPreviousIterationDrtDemandEstimator) bound;

        assertThat(spyParcelDrt.get())
                .as("parcel persons DO depart on a drt leg at the depot - the phantom demand C2 is about")
                .isGreaterThanOrEqualTo(1);
        assertThat(spyPaxDrt.get()).as("passenger drt demand must be present").isGreaterThanOrEqualTo(1);

        assertThat(estimator.parcelDeparturesSkipped())
                .as("every parcel drt departure was EXCLUDED from the rebalancing demand (M7)")
                .isEqualTo(spyParcelDrt.get());
        assertThat(estimator.acceptedDepartures())
                .as("every passenger drt departure was counted as rebalancing demand")
                .isEqualTo(spyPaxDrt.get());
    }

    // --- fixture helpers (mirror SharedUseDispatchTest) ---

    private void addPaxPerson(Scenario scenario, String id, double departureTime) {
        PopulationFactory pf = scenario.getPopulation().getFactory();
        Person p = pf.createPerson(Id.createPersonId(id));
        Plan plan = pf.createPlan();
        var home = pf.createActivityFromLinkId("home", Id.createLinkId("l0"));
        home.setEndTime(departureTime);
        plan.addActivity(home);
        plan.addLeg(pf.createLeg(TransportMode.drt));
        plan.addActivity(pf.createActivityFromLinkId("work", Id.createLinkId("l2")));
        p.addPlan(plan);
        p.setSelectedPlan(plan);
        scenario.getPopulation().addPerson(p);
    }

    private void addParcelPerson(Scenario scenario, String id) {
        PopulationFactory pf = scenario.getPopulation().getFactory();
        Person p = pf.createPerson(Id.createPersonId(id));
        org.matsim.core.population.PopulationUtils.putSubpopulation(p, SharedUse.PARCEL_SUBPOPULATION);
        p.getAttributes().putAttribute(SharedUse.LOAD_ATTRIBUTE, 3);
        p.getAttributes().putAttribute(SharedUse.DWELL_ATTRIBUTE, SharedUse.segmentDwellSeconds(3));
        p.getAttributes().putAttribute(SharedUse.WINDOW_END_ATTRIBUTE, SharedUse.B2C_WINDOW_END_S);
        // Must mirror ParcelAgentGenerator's FULL attribute set — see ParcelAttributes.
        p.getAttributes().putAttribute(SharedUse.CHANNEL_ATTRIBUTE,
                DeliveryChannelResolver.Channel.DOOR.name());

        Plan plan = pf.createPlan();
        var depot = pf.createActivityFromLinkId(SharedUse.ACT_DEPOT, Id.createLinkId("l0"));
        depot.setEndTime(8 * 3600.0);
        plan.addActivity(depot);
        plan.addLeg(pf.createLeg(TransportMode.drt));
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
