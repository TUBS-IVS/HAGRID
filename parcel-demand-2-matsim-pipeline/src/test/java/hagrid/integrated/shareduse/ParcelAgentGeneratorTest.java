package hagrid.integrated.shareduse;

import hagrid.utils.demand.Delivery;
import hagrid.utils.demand.Delivery.ParcelType;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.*;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.config.ConfigUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class ParcelAgentGeneratorTest {

    private static Geometry square(double size) {
        var gf = new GeometryFactory();
        return gf.createPolygon(new org.locationtech.jts.geom.Coordinate[]{
                new org.locationtech.jts.geom.Coordinate(0, 0),
                new org.locationtech.jts.geom.Coordinate(size, 0),
                new org.locationtech.jts.geom.Coordinate(size, size),
                new org.locationtech.jts.geom.Coordinate(0, size),
                new org.locationtech.jts.geom.Coordinate(0, 0)});
    }

    @Test
    void generatesOnePersonPerInAreaDeliveryWithLoadDwellAndPlan() {
        Network net = hagrid.integrated.drt.DrtE2eFixtures.buildGrid();
        Population pop = ScenarioUtils.createScenario(ConfigUtils.createConfig()).getPopulation();
        Map<String, List<Delivery>> demand = Map.of("dhl", List.of(
                Delivery.builder().id("s1").coordinate(new Coord(800, 800))
                        .provider("dhl").amount(3).parcelType(ParcelType.B2C).build(),
                Delivery.builder().id("s2").coordinate(new Coord(9_999_999, 0))   // outside
                        .provider("dhl").amount(2).parcelType(ParcelType.B2B).build()));

        var result = ParcelAgentGenerator.generate(demand, square(2000), net,
                Map.of("dhl", new Coord(500, 500)), pop, 4711L);

        assertEquals(1, result.personsAdded());
        assertEquals(3, result.parcels());
        assertEquals(1, result.clippedOutside());

        Person p = pop.getPersons().values().iterator().next();
        assertTrue(p.getId().toString().startsWith(SharedUse.PARCEL_PERSON_PREFIX));
        assertEquals(SharedUse.PARCEL_SUBPOPULATION, PopulationUtils.getSubpopulation(p));
        assertEquals(3, (int) (Integer) p.getAttributes().getAttribute(SharedUse.LOAD_ATTRIBUTE));
        assertEquals(SharedUse.segmentDwellSeconds(3),
                (double) (Double) p.getAttributes().getAttribute(SharedUse.DWELL_ATTRIBUTE), 1e-9);
        assertEquals("DOOR", p.getAttributes().getAttribute(SharedUse.CHANNEL_ATTRIBUTE));
        assertEquals(SharedUse.B2C_WINDOW_END_S,
                (double) (Double) p.getAttributes().getAttribute(SharedUse.WINDOW_END_ATTRIBUTE), 1e-9);

        Plan plan = p.getSelectedPlan();
        assertEquals(3, plan.getPlanElements().size());
        Activity depot = (Activity) plan.getPlanElements().get(0);
        Leg leg = (Leg) plan.getPlanElements().get(1);
        Activity delivery = (Activity) plan.getPlanElements().get(2);
        assertEquals(SharedUse.ACT_DEPOT, depot.getType());
        assertEquals("drt", leg.getMode());
        assertEquals(SharedUse.ACT_DELIVERY, delivery.getType());
        assertTrue(depot.getEndTime().seconds() >= SharedUse.SUBMIT_FROM_S
                && depot.getEndTime().seconds() <= SharedUse.SUBMIT_TO_S);
        assertNotEquals(depot.getLinkId(), delivery.getLinkId());   // validator guard
        // Coords must be set (not link-only): serviceAreaBased DRT routing needs a resolvable
        // activity coord to find an access/egress stop via ClosestAccessEgressFacilityFinder -
        // without it the trip silently falls back to a teleported walk leg (1c Task 3 review).
        assertEquals(new Coord(500, 500), depot.getCoord());
        assertEquals(new Coord(800, 800), delivery.getCoord());
    }

    @Test
    void splitsOversizedSegmentIntoSubPersonsEachWithinCapacity() {
        // M2 (segment-split): a segment of 45 parcels exceeds SharedUse.PARCEL_SLOTS (20) and would
        // be undeliverable-by-construction as a single 2D load; it must be split into sub-persons
        // that each fit, all visiting the same physical depot/segment point.
        Network net = hagrid.integrated.drt.DrtE2eFixtures.buildGrid();
        Population pop = ScenarioUtils.createScenario(ConfigUtils.createConfig()).getPopulation();
        Map<String, List<Delivery>> demand = Map.of("dhl", List.of(
                Delivery.builder().id("s1").coordinate(new Coord(800, 800))
                        .provider("dhl").amount(45).parcelType(ParcelType.B2C).build()));

        var result = ParcelAgentGenerator.generate(demand, square(2000), net,
                Map.of("dhl", new Coord(500, 500)), pop, 4711L);

        assertEquals(3, result.personsAdded());
        assertEquals(45, result.parcels());
        assertEquals(0, result.clippedOutside());
        assertEquals(0, result.skippedSameLink());

        List<Person> persons = List.copyOf(pop.getPersons().values());
        assertEquals(3, persons.size());

        List<Integer> loads = persons.stream()
                .map(p -> (Integer) p.getAttributes().getAttribute(SharedUse.LOAD_ATTRIBUTE))
                .sorted()
                .collect(Collectors.toList());
        assertEquals(List.of(5, 20, 20), loads);

        Set<String> ids = new HashSet<>();
        for (Person p : persons) {
            assertTrue((Integer) p.getAttributes().getAttribute(SharedUse.LOAD_ATTRIBUTE) <= SharedUse.PARCEL_SLOTS);
            assertTrue(p.getId().toString().startsWith(SharedUse.PARCEL_PERSON_PREFIX));
            assertTrue(ids.add(p.getId().toString()), "person ids must be distinct");

            Plan plan = p.getSelectedPlan();
            Activity depot = (Activity) plan.getPlanElements().get(0);
            Activity delivery = (Activity) plan.getPlanElements().get(2);
            assertEquals(new Coord(500, 500), depot.getCoord());
            assertEquals(new Coord(800, 800), delivery.getCoord());
            assertEquals(SharedUse.segmentDwellSeconds(
                            (Integer) p.getAttributes().getAttribute(SharedUse.LOAD_ATTRIBUTE)),
                    (double) (Double) p.getAttributes().getAttribute(SharedUse.DWELL_ATTRIBUTE), 1e-9);
        }
        // all sub-persons of one segment share the same depot link and the same delivery link
        assertEquals(1, persons.stream()
                .map(p -> ((Activity) p.getSelectedPlan().getPlanElements().get(0)).getLinkId()).distinct().count());
        assertEquals(1, persons.stream()
                .map(p -> ((Activity) p.getSelectedPlan().getPlanElements().get(2)).getLinkId()).distinct().count());
    }

    @Test
    void doesNotSplitSegmentAtExactlyCapacity() {
        Network net = hagrid.integrated.drt.DrtE2eFixtures.buildGrid();
        Population pop = ScenarioUtils.createScenario(ConfigUtils.createConfig()).getPopulation();
        Map<String, List<Delivery>> demand = Map.of("dhl", List.of(
                Delivery.builder().id("s1").coordinate(new Coord(800, 800))
                        .provider("dhl").amount(SharedUse.PARCEL_SLOTS).parcelType(ParcelType.B2C).build()));

        var result = ParcelAgentGenerator.generate(demand, square(2000), net,
                Map.of("dhl", new Coord(500, 500)), pop, 4711L);

        assertEquals(1, result.personsAdded());
        assertEquals(SharedUse.PARCEL_SLOTS, result.parcels());
        Person p = pop.getPersons().values().iterator().next();
        assertEquals(SharedUse.PARCEL_SLOTS,
                (int) (Integer) p.getAttributes().getAttribute(SharedUse.LOAD_ATTRIBUTE));
        assertTrue(p.getId().toString().startsWith(SharedUse.PARCEL_PERSON_PREFIX));
    }

    /**
     * M4(b) discriminating case: each parcel must originate at ITS provider's tagged depot,
     * even when the OTHER provider's depot is geometrically nearer. The dhl segment at
     * (900,900) is right next to the hermes depot (1000,1000) and far from the dhl depot
     * (100,100) — nearest-depot assignment (pre-M4(b)) would cross-dock it at hermes.
     */
    @Test
    void assignsEachParcelToItsProviderDepotEvenWhenAnotherIsNearer() {
        Network net = hagrid.integrated.drt.DrtE2eFixtures.buildGrid();
        Population pop = ScenarioUtils.createScenario(ConfigUtils.createConfig()).getPopulation();
        Map<String, List<Delivery>> demand = Map.of(
                "dhl", List.of(Delivery.builder().id("s1").coordinate(new Coord(900, 900))
                        .provider("dhl").amount(2).parcelType(ParcelType.B2C).build()),
                "hermes", List.of(Delivery.builder().id("s2").coordinate(new Coord(200, 200))
                        .provider("hermes").amount(1).parcelType(ParcelType.B2C).build()));
        Map<String, Coord> depots = Map.of(
                "dhl", new Coord(100, 100),
                "hermes", new Coord(1000, 1000));

        var result = ParcelAgentGenerator.generate(demand, square(2000), net, depots, pop, 4711L);

        assertEquals(2, result.personsAdded());
        for (Person p : pop.getPersons().values()) {
            String provider = (String) p.getAttributes().getAttribute("provider");
            Activity depot = (Activity) p.getSelectedPlan().getPlanElements().get(0);
            assertEquals(depots.get(provider), depot.getCoord(),
                    "parcel of " + provider + " must originate at its provider depot");
        }
    }

    /** M4(b) fallback: a provider without a tagged depot gets the nearest depot (plus a WARN). */
    @Test
    void fallsBackToNearestDepotForUnknownProvider() {
        Network net = hagrid.integrated.drt.DrtE2eFixtures.buildGrid();
        Population pop = ScenarioUtils.createScenario(ConfigUtils.createConfig()).getPopulation();
        // delivery (800,800) vs depots: dhl (500,500) is nearest (~424 m), hermes (100,100)
        // is far (~989 m) - the (500,500)/(800,800) pair is proven same-link-collision-free
        // (see generatesOnePersonPerInAreaDeliveryWithLoadDwellAndPlan).
        Map<String, List<Delivery>> demand = Map.of("ups", List.of(
                Delivery.builder().id("s1").coordinate(new Coord(800, 800))
                        .provider("ups").amount(1).parcelType(ParcelType.B2C).build()));
        Map<String, Coord> depots = Map.of(
                "dhl", new Coord(500, 500),
                "hermes", new Coord(100, 100));

        var result = ParcelAgentGenerator.generate(demand, square(2000), net, depots, pop, 4711L);

        assertEquals(1, result.personsAdded());
        Person p = pop.getPersons().values().iterator().next();
        Activity depot = (Activity) p.getSelectedPlan().getPlanElements().get(0);
        assertEquals(new Coord(500, 500), depot.getCoord(),
                "unknown provider must fall back to the nearest depot");
    }

    /** M4(b): provider names are matched case/whitespace-insensitively (LmdDepotLoader idiom). */
    @Test
    void normalizesProviderNameWhenResolvingDepot() {
        Network net = hagrid.integrated.drt.DrtE2eFixtures.buildGrid();
        Population pop = ScenarioUtils.createScenario(ConfigUtils.createConfig()).getPopulation();
        Map<String, List<Delivery>> demand = Map.of("DHL", List.of(
                Delivery.builder().id("s1").coordinate(new Coord(900, 900))
                        .provider(" DHL ").amount(1).parcelType(ParcelType.B2C).build()));
        Map<String, Coord> depots = Map.of(
                "dhl", new Coord(100, 100),
                "hermes", new Coord(1000, 1000));

        ParcelAgentGenerator.generate(demand, square(2000), net, depots, pop, 4711L);

        Person p = pop.getPersons().values().iterator().next();
        Activity depot = (Activity) p.getSelectedPlan().getPlanElements().get(0);
        assertEquals(new Coord(100, 100), depot.getCoord(),
                "' DHL ' must resolve to the 'dhl' depot, not fall back to nearest (hermes)");
    }

    @Test
    void deterministicForFixedSeed() {
        Network net = hagrid.integrated.drt.DrtE2eFixtures.buildGrid();
        Map<String, List<Delivery>> demand = Map.of("dhl", List.of(
                Delivery.builder().id("s1").coordinate(new Coord(800, 800))
                        .provider("dhl").amount(1).parcelType(ParcelType.B2C).build()));
        Population p1 = ScenarioUtils.createScenario(ConfigUtils.createConfig()).getPopulation();
        Population p2 = ScenarioUtils.createScenario(ConfigUtils.createConfig()).getPopulation();
        ParcelAgentGenerator.generate(demand, square(2000), net, Map.of("dhl", new Coord(500, 500)), p1, 4711L);
        ParcelAgentGenerator.generate(demand, square(2000), net, Map.of("dhl", new Coord(500, 500)), p2, 4711L);
        Activity a1 = (Activity) p1.getPersons().values().iterator().next().getSelectedPlan().getPlanElements().get(0);
        Activity a2 = (Activity) p2.getPersons().values().iterator().next().getSelectedPlan().getPlanElements().get(0);
        assertEquals(a1.getEndTime().seconds(), a2.getEndTime().seconds(), 1e-9);
    }
}
