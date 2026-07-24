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

import java.util.List;
import java.util.Map;

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
                List.of(new Coord(500, 500)), pop, 4711L);

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
    }

    @Test
    void deterministicForFixedSeed() {
        Network net = hagrid.integrated.drt.DrtE2eFixtures.buildGrid();
        Map<String, List<Delivery>> demand = Map.of("dhl", List.of(
                Delivery.builder().id("s1").coordinate(new Coord(800, 800))
                        .provider("dhl").amount(1).parcelType(ParcelType.B2C).build()));
        Population p1 = ScenarioUtils.createScenario(ConfigUtils.createConfig()).getPopulation();
        Population p2 = ScenarioUtils.createScenario(ConfigUtils.createConfig()).getPopulation();
        ParcelAgentGenerator.generate(demand, square(2000), net, List.of(new Coord(500, 500)), p1, 4711L);
        ParcelAgentGenerator.generate(demand, square(2000), net, List.of(new Coord(500, 500)), p2, 4711L);
        Activity a1 = (Activity) p1.getPersons().values().iterator().next().getSelectedPlan().getPlanElements().get(0);
        Activity a2 = (Activity) p2.getPersons().values().iterator().next().getSelectedPlan().getPlanElements().get(0);
        assertEquals(a1.getEndTime().seconds(), a2.getEndTime().seconds(), 1e-9);
    }
}
