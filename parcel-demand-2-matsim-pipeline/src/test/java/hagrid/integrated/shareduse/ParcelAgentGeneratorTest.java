package hagrid.integrated.shareduse;

import hagrid.integrated.DeliveryDistrictBuilder;
import hagrid.integrated.DepotNetwork;
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
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 6 (spec 2026-08-17 D2/D7/D8/D9): {@code ParcelAgentGenerator.generate} now consumes
 * {@link DeliveryDistrictBuilder.District}/{@code PooledStop} instead of a raw
 * {@code Map<String, List<Delivery>>} + a provider-&gt;depot map. Every test in this class was
 * migrated to build districts first (the old overload is deleted, not kept).
 *
 * <p>Three tests that existed before this task are GONE rather than migrated, because the
 * behaviour they proved (M4(b): each parcel physically originates at ITS OWN provider's tagged
 * depot, with a nearest-depot fallback for an untagged provider, case/whitespace-normalized) was
 * deleted in this task's {@code generate} rewrite, not merely relocated:
 * {@code assignsEachParcelToItsProviderDepotEvenWhenAnotherIsNearer},
 * {@code fallsBackToNearestDepotForUnknownProvider}, {@code normalizesProviderNameWhenResolvingDepot}.
 * Provider identity plays no role in depot assignment any more — every pooled stop already
 * carries its district's depot by the time {@code generate} sees it (assigned upstream by
 * {@link DeliveryDistrictBuilder}, which is covered by its own test class) — so there is nothing
 * left in {@code ParcelAgentGenerator} for those three tests to discriminate.</p>
 */
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

    private static Delivery deliveryAt(double x, double y, String provider, int amount) {
        return Delivery.builder().id(provider + "_" + x + "_" + y).coordinate(new Coord(x, y))
                .provider(provider).amount(amount).parcelType(ParcelType.B2C).build();
    }

    @Test
    void generatesOnePersonPerInAreaDeliveryWithLoadDwellAndPlan() {
        Network net = hagrid.integrated.drt.DrtE2eFixtures.buildGrid();
        Population pop = ScenarioUtils.createScenario(ConfigUtils.createConfig()).getPopulation();
        var districts = DeliveryDistrictBuilder.build(
                List.of(deliveryAt(800, 800, "dhl", 3),
                        deliveryAt(9_999_999, 0, "dhl", 2)),   // outside the service area
                List.of(new DepotNetwork.Depot("hoy_sued", new Coord(500, 500))), Integer.MAX_VALUE);

        var result = ParcelAgentGenerator.generate(districts, square(2000), net, pop, 4711L);

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
        assertEquals(SharedUse.WINDOW_END_S,
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
        // M2 (segment-split): a stop of 45 parcels exceeds SharedUse.PARCEL_SLOTS (20) and would
        // be undeliverable-by-construction as a single 2D load; it must be split into sub-persons
        // that each fit, all visiting the same physical depot/stop point.
        Network net = hagrid.integrated.drt.DrtE2eFixtures.buildGrid();
        Population pop = ScenarioUtils.createScenario(ConfigUtils.createConfig()).getPopulation();
        var districts = DeliveryDistrictBuilder.build(
                List.of(deliveryAt(800, 800, "dhl", 45)),
                List.of(new DepotNetwork.Depot("hoy_sued", new Coord(500, 500))), Integer.MAX_VALUE);

        var result = ParcelAgentGenerator.generate(districts, square(2000), net, pop, 4711L);

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
        // all sub-persons of one stop share the same depot link and the same delivery link
        assertEquals(1, persons.stream()
                .map(p -> ((Activity) p.getSelectedPlan().getPlanElements().get(0)).getLinkId()).distinct().count());
        assertEquals(1, persons.stream()
                .map(p -> ((Activity) p.getSelectedPlan().getPlanElements().get(2)).getLinkId()).distinct().count());
    }

    @Test
    void doesNotSplitSegmentAtExactlyCapacity() {
        Network net = hagrid.integrated.drt.DrtE2eFixtures.buildGrid();
        Population pop = ScenarioUtils.createScenario(ConfigUtils.createConfig()).getPopulation();
        var districts = DeliveryDistrictBuilder.build(
                List.of(deliveryAt(800, 800, "dhl", SharedUse.PARCEL_SLOTS)),
                List.of(new DepotNetwork.Depot("hoy_sued", new Coord(500, 500))), Integer.MAX_VALUE);

        var result = ParcelAgentGenerator.generate(districts, square(2000), net, pop, 4711L);

        assertEquals(1, result.personsAdded());
        assertEquals(SharedUse.PARCEL_SLOTS, result.parcels());
        Person p = pop.getPersons().values().iterator().next();
        assertEquals(SharedUse.PARCEL_SLOTS,
                (int) (Integer) p.getAttributes().getAttribute(SharedUse.LOAD_ATTRIBUTE));
        assertTrue(p.getId().toString().startsWith(SharedUse.PARCEL_PERSON_PREFIX));
    }

    @Test
    void deterministicForFixedSeed() {
        Network net = hagrid.integrated.drt.DrtE2eFixtures.buildGrid();
        var districts = DeliveryDistrictBuilder.build(
                List.of(deliveryAt(800, 800, "dhl", 1)),
                List.of(new DepotNetwork.Depot("hoy_sued", new Coord(500, 500))), Integer.MAX_VALUE);
        Population p1 = ScenarioUtils.createScenario(ConfigUtils.createConfig()).getPopulation();
        Population p2 = ScenarioUtils.createScenario(ConfigUtils.createConfig()).getPopulation();
        ParcelAgentGenerator.generate(districts, square(2000), net, p1, 4711L);
        ParcelAgentGenerator.generate(districts, square(2000), net, p2, 4711L);
        Activity a1 = (Activity) p1.getPersons().values().iterator().next().getSelectedPlan().getPlanElements().get(0);
        Activity a2 = (Activity) p2.getPersons().values().iterator().next().getSelectedPlan().getPlanElements().get(0);
        assertEquals(a1.getEndTime().seconds(), a2.getEndTime().seconds(), 1e-9);
    }

    // -------------------------------------------------------------------------------------
    // New district-pooling behaviour (Task 6, spec 2026-08-17 D2/D7/D8/D9).
    // -------------------------------------------------------------------------------------

    @Test
    void oneParcelPersonPerPooledStopNotPerProvider() {
        // Same segment, three providers -> today 3 persons; after pooling 1 person with 6 parcels.
        Network net = hagrid.integrated.drt.DrtE2eFixtures.buildGrid();
        Population pop = ScenarioUtils.createScenario(ConfigUtils.createConfig()).getPopulation();
        var districts = DeliveryDistrictBuilder.build(
                List.of(deliveryAt(800, 800, "dhl", 3),
                        deliveryAt(800, 800, "hermes", 2),
                        deliveryAt(800, 800, "gls", 1)),
                List.of(new DepotNetwork.Depot("hoy_sued", new Coord(500, 500))), Integer.MAX_VALUE);

        var r = ParcelAgentGenerator.generate(districts, square(2000), net, pop, 4711L);

        assertEquals(1, r.personsAdded());
        assertEquals(6, r.parcels());
        Person p = pop.getPersons().values().iterator().next();
        assertTrue(p.getId().toString().startsWith(SharedUse.PARCEL_PERSON_PREFIX),
                "the parcel_ prefix contract must survive pooling");
        assertEquals(6, (int) (Integer) p.getAttributes().getAttribute(SharedUse.LOAD_ATTRIBUTE));
        assertEquals(SharedUse.segmentDwellSeconds(6),
                (double) (Double) p.getAttributes().getAttribute(SharedUse.DWELL_ATTRIBUTE), 1e-9);
    }

    @Test
    void stopOriginIsTheDistrictDepotNotTheProviderDepot() {
        Network net = hagrid.integrated.drt.DrtE2eFixtures.buildGrid();
        Population pop = ScenarioUtils.createScenario(ConfigUtils.createConfig()).getPopulation();
        var districts = DeliveryDistrictBuilder.build(
                List.of(deliveryAt(800, 800, "gls", 1)),
                List.of(new DepotNetwork.Depot("hoy_sued", new Coord(500, 500))), Integer.MAX_VALUE);

        ParcelAgentGenerator.generate(districts, square(2000), net, pop, 4711L);

        Person p = pop.getPersons().values().iterator().next();
        Activity depot = (Activity) p.getSelectedPlan().getPlanElements().get(0);
        assertEquals(SharedUse.ACT_DEPOT, depot.getType());
        assertEquals(new Coord(500, 500), depot.getCoord(),
                "a gls parcel must now start at the hoy_sued district depot");
    }

    @Test
    void oversizedStopsStillSplitIntoParcelSlotChunks() {
        Network net = hagrid.integrated.drt.DrtE2eFixtures.buildGrid();
        Population pop = ScenarioUtils.createScenario(ConfigUtils.createConfig()).getPopulation();
        var districts = DeliveryDistrictBuilder.build(
                List.of(deliveryAt(800, 800, "dhl", 45)),
                List.of(new DepotNetwork.Depot("hoy_sued", new Coord(500, 500))), Integer.MAX_VALUE);

        var r = ParcelAgentGenerator.generate(districts, square(2000), net, pop, 4711L);

        assertEquals(3, r.personsAdded(), "45 parcels at 20 slots -> 20 + 20 + 5");
        assertEquals(45, r.parcels());
    }

    @Test
    void stopsOutsideTheServiceAreaAreStillClipped() {
        Network net = hagrid.integrated.drt.DrtE2eFixtures.buildGrid();
        Population pop = ScenarioUtils.createScenario(ConfigUtils.createConfig()).getPopulation();
        var districts = DeliveryDistrictBuilder.build(
                List.of(deliveryAt(800, 800, "dhl", 3), deliveryAt(9_999_999, 0, "dhl", 2)),
                List.of(new DepotNetwork.Depot("hoy_sued", new Coord(500, 500))), Integer.MAX_VALUE);

        var r = ParcelAgentGenerator.generate(districts, square(2000), net, pop, 4711L);

        assertEquals(1, r.personsAdded());
        assertEquals(1, r.clippedOutside());
    }

    /**
     * Fix round 1 finding (Important): channel resolution on a pooled stop must not just look at
     * part 0. If ANY part is B2B, {@code channelRepresentative} must pick a B2B part so its
     * mandatory door delivery is not silently overridden by an earlier B2C part. The resolver
     * itself can't discriminate this in Phase 1 (empty locker list -> DOOR for everything), so
     * this asserts on the resolution INPUT that {@code generate} feeds the resolver, which is the
     * only place the old index-0 bug is visible.
     */
    @Test
    void channelRepresentativePrefersB2BPartOverAnEarlierB2C() {
        Delivery b2c = deliveryAt(800, 800, "dhl", 3);
        Delivery b2b = Delivery.builder().id("hermes_800.0_800.0_b2b").coordinate(new Coord(800, 800))
                .provider("hermes").amount(2).parcelType(ParcelType.B2B).build();

        Delivery chosen = ParcelAgentGenerator.channelRepresentative(List.of(b2c, b2b));

        assertEquals(ParcelType.B2B, chosen.getParcelType(),
                "a B2B part later in the list must win over an earlier B2C part - "
                        + "the old code picked parts.get(0) and would have returned B2C here");
    }

    /**
     * The parcel WEIGHT of a stop dropped at its own yard gate, not just the stop count.
     *
     * <p>A pooled stop whose delivery link IS its district's depot link cannot become a DVRP
     * request ({@code DefaultPassengerRequestValidator} rejects {@code from == to}), so
     * {@code generate} drops it before any agent exists. {@code skippedSameLink} already counted
     * the STOP; nothing counted its parcels, so those parcels were invisible to every downstream
     * consumer: they are absent from {@code parcels_injected} and cannot be reconstructed from
     * the run output, because no agent, no plan and no event ever mentions them.
     *
     * <p>Measured consequence on the real scenario (spec 2026-08-25 section 3): 2 stops carrying
     * 15 parcels vanish at {@code openDepots=all}, while the Baseline -- which routes parcels as
     * jsprit CarrierServices and has no from==to constraint -- keeps all 6052. Reporting 1c's
     * delivery rate on the injected base would hide exactly that difference and make both arms
     * read as 100 percent.
     */
    @Test
    void reportsTheParcelWeightOfStopsDroppedAtTheirOwnDepotLink() {
        Network net = hagrid.integrated.drt.DrtE2eFixtures.buildGrid();
        Population pop = ScenarioUtils.createScenario(ConfigUtils.createConfig()).getPopulation();
        // The second delivery sits ON the depot coordinate, so it snaps to the depot link.
        var districts = DeliveryDistrictBuilder.build(
                List.of(deliveryAt(800, 800, "dhl", 3), deliveryAt(500, 500, "dhl", 7)),
                List.of(new DepotNetwork.Depot("hoy_sued", new Coord(500, 500))), Integer.MAX_VALUE);

        var r = ParcelAgentGenerator.generate(districts, square(2000), net, pop, 4711L);

        assertEquals(1, r.skippedSameLink(), "one stop must be dropped at the depot link");
        assertEquals(7, r.skippedSameLinkParcels(),
                "the dropped stop's 7 parcels must be reported, not just the stop count");
        assertEquals(3, r.parcels(), "only the surviving stop's parcels are injected");
    }

    /**
     * Same for the clipping path, so the two loss channels stay distinguishable: a stop outside
     * the service area is a demand-definition loss, a stop at its own yard gate is a model
     * artefact. Collapsing them into one number would make the artefact unattributable.
     */
    @Test
    void reportsTheParcelWeightOfStopsClippedOutsideTheServiceArea() {
        Network net = hagrid.integrated.drt.DrtE2eFixtures.buildGrid();
        Population pop = ScenarioUtils.createScenario(ConfigUtils.createConfig()).getPopulation();
        var districts = DeliveryDistrictBuilder.build(
                List.of(deliveryAt(800, 800, "dhl", 3), deliveryAt(9_999_999, 0, "dhl", 2)),
                List.of(new DepotNetwork.Depot("hoy_sued", new Coord(500, 500))), Integer.MAX_VALUE);

        var r = ParcelAgentGenerator.generate(districts, square(2000), net, pop, 4711L);

        assertEquals(1, r.clippedOutside());
        assertEquals(2, r.clippedOutsideParcels());
        assertEquals(0, r.skippedSameLinkParcels(),
                "a clipped stop must not be counted as a yard-gate drop");
    }

    /**
     * The parts must sum to the whole. Without this the two new counters could each be right in
     * isolation while a third, unnamed loss path quietly swallowed parcels -- which is precisely
     * how the 15 yard-gate parcels stayed invisible for a week.
     */
    @Test
    void injectedPlusBothLossChannelsEqualsTheTotalDemandOffered() {
        Network net = hagrid.integrated.drt.DrtE2eFixtures.buildGrid();
        Population pop = ScenarioUtils.createScenario(ConfigUtils.createConfig()).getPopulation();
        List<Delivery> offered = List.of(
                deliveryAt(800, 800, "dhl", 3),
                deliveryAt(1200, 900, "hermes", 11),
                deliveryAt(500, 500, "dhl", 7),          // on the depot link -> dropped
                deliveryAt(9_999_999, 0, "dhl", 2));     // outside the area -> clipped
        int offeredParcels = offered.stream().mapToInt(Delivery::getAmount).sum();
        var districts = DeliveryDistrictBuilder.build(offered,
                List.of(new DepotNetwork.Depot("hoy_sued", new Coord(500, 500))), Integer.MAX_VALUE);

        var r = ParcelAgentGenerator.generate(districts, square(2000), net, pop, 4711L);

        assertEquals(offeredParcels,
                r.parcels() + r.skippedSameLinkParcels() + r.clippedOutsideParcels(),
                "injected + yard-gate drops + clipped must account for every parcel offered");
    }
}
