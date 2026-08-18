package hagrid.integrated.shareduse;

import hagrid.integrated.DeliveryDistrictBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.*;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.PopulationUtils;

import java.util.List;
import java.util.Random;

/**
 * Turns POOLED delivery-district stops (see {@link DeliveryDistrictBuilder}) into dummy
 * parcel-persons: plan = act(parcelDepot @ the district's depot, endTime = jittered submit
 * time) -> leg(drt) -> act(parcelDelivery @ the pooled stop's link). The drt departure triggers
 * the native request submission (spike Sec 2, path b).
 *
 * <p>Provider identity is dissolved both operationally (Einheitsunternehmen) AND structurally
 * as of spec 2026-08-17 (D2/D7/D8/D9): every provider's parcels at the same segment are already
 * pooled into ONE {@link DeliveryDistrictBuilder.PooledStop} upstream, and that stop originates
 * at its DISTRICT's depot, not at any one provider's own depot. The old M4(b) provider-tagged
 * depot assignment (and its nearest-depot fallback for an untagged provider) is gone —
 * {@code DeliveryDistrictBuilder} already assigned every stop to its nearest OPEN depot before
 * this class ever sees it, and 1c always calls it with {@code maxJobsPerDistrict =
 * Integer.MAX_VALUE} (D8 — 1c has no jsprit, so a catchment split would only rename persons).</p>
 */
public final class ParcelAgentGenerator {

    private static final Logger LOG = LogManager.getLogger(ParcelAgentGenerator.class);
    private static final GeometryFactory GF = new GeometryFactory();

    public record Result(int personsAdded, int parcels, int skippedSameLink, int clippedOutside) {
    }

    private ParcelAgentGenerator() {
    }

    /** M2 (segment-split): split a pooled stop's total parcel amount into sub-loads of at most
     *  SharedUse.PARCEL_SLOTS each (filling full slots first, then the remainder), e.g. 45 -> [20,20,5]. */
    private static List<Integer> splitLoad(int amount) {
        List<Integer> subLoads = new java.util.ArrayList<>();
        int remaining = amount;
        while (remaining > 0) {
            int chunk = Math.min(SharedUse.PARCEL_SLOTS, remaining);
            subLoads.add(chunk);
            remaining -= chunk;
        }
        return subLoads;
    }

    public static Result generate(List<DeliveryDistrictBuilder.District> districts,
                                  Geometry serviceArea, Network drtNetwork,
                                  Population population, long seed) {
        DeliveryChannelResolver resolver = new DeliveryChannelResolver(List.of(), 500.0); // Phase 1: no lockers
        Random rnd = new Random(seed);
        PopulationFactory pf = population.getFactory();

        int persons = 0, parcels = 0, skipped = 0, outside = 0, index = 0;
        for (DeliveryDistrictBuilder.District district : districts) {
            Link depotLink = NetworkUtils.getNearestLinkExactly(drtNetwork, district.depot().coord());
            for (DeliveryDistrictBuilder.PooledStop stop : district.stops()) {
                index++;
                if (!serviceArea.contains(GF.createPoint(new org.locationtech.jts.geom.Coordinate(
                        stop.coord().getX(), stop.coord().getY())))) {
                    outside++;
                    continue;
                }
                Link segmentLink = NetworkUtils.getNearestLinkExactly(drtNetwork, stop.coord());
                if (depotLink.getId().equals(segmentLink.getId())) {
                    skipped++;                       // DefaultPassengerRequestValidator rejects from==to
                    LOG.warn("parcel stop {} snaps to its depot link {} - skipped", index,
                            depotLink.getId());
                    continue;
                }

                // M2 (segment-split): a pooled stop can carry more parcels than a Shared-Use
                // vehicle has slots (PARCEL_SLOTS=20) - such a 2D load could never fit and would
                // be undeliverable-by-construction. Split into >=1 sub-loads of <=20 parcels
                // each, all visiting the same physical depot/stop point (dense point -> multiple
                // visits).
                List<Integer> subLoads = splitLoad(stop.totalParcels());
                // A pooled stop can mix channels; B2B presence forces door delivery, which is
                // what the Phase-1 (locker-free) resolver returns anyway.
                String channel = resolver.resolve(stop.parts().get(0)).name();
                // One deadline for every parcel type, and the same one the Baseline and 1d use
                // (user decision 2026-07-30). See DeliveryDay.
                double windowEnd = SharedUse.WINDOW_END_S;

                for (int part = 0; part < subLoads.size(); part++) {
                    int subLoad = subLoads.get(part);
                    String idSuffix = subLoads.size() > 1 ? "_p" + part : "";
                    Person p = pf.createPerson(Id.createPersonId(SharedUse.PARCEL_PERSON_PREFIX
                            + district.id() + "_" + index + idSuffix));
                    PopulationUtils.putSubpopulation(p, SharedUse.PARCEL_SUBPOPULATION);
                    p.getAttributes().putAttribute(SharedUse.LOAD_ATTRIBUTE, subLoad);
                    p.getAttributes().putAttribute(SharedUse.DWELL_ATTRIBUTE,
                            SharedUse.segmentDwellSeconds(subLoad));
                    p.getAttributes().putAttribute(SharedUse.CHANNEL_ATTRIBUTE, channel);
                    p.getAttributes().putAttribute("district", district.id());
                    p.getAttributes().putAttribute(SharedUse.WINDOW_END_ATTRIBUTE, windowEnd);

                    Plan plan = pf.createPlan();
                    Activity depot = pf.createActivityFromLinkId(SharedUse.ACT_DEPOT, depotLink.getId());
                    depot.setCoord(district.depot().coord());
                    depot.setEndTime(SharedUse.SUBMIT_FROM_S
                            + rnd.nextDouble() * (SharedUse.SUBMIT_TO_S - SharedUse.SUBMIT_FROM_S));
                    plan.addActivity(depot);
                    plan.addLeg(pf.createLeg("drt"));
                    Activity delivery = pf.createActivityFromLinkId(SharedUse.ACT_DELIVERY,
                            segmentLink.getId());
                    delivery.setCoord(stop.coord());
                    plan.addActivity(delivery);
                    p.addPlan(plan);
                    population.addPerson(p);
                    persons++;
                    parcels += subLoad;
                }
            }
        }
        LOG.info("ParcelAgentGenerator: {} parcel-persons ({} parcels), {} outside area, {} same-link skipped",
                persons, parcels, outside, skipped);
        return new Result(persons, parcels, skipped, outside);
    }
}
