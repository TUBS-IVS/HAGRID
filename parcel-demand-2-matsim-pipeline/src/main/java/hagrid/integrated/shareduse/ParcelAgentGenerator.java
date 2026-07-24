package hagrid.integrated.shareduse;

import hagrid.integrated.DepotNetwork;
import hagrid.utils.demand.Delivery;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.*;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.PopulationUtils;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

/**
 * Turns the segment-aggregated PANDA deliveries into dummy parcel-persons:
 * plan = act(parcelDepot @ nearest depot link, endTime = jittered submit time)
 *        -> leg(drt) -> act(parcelDelivery @ segment link).
 * The drt departure triggers the native request submission (spike §2, path b).
 * Provider identity is dissolved (Einheitsunternehmen) but kept as attribute.
 */
public final class ParcelAgentGenerator {

    private static final Logger LOG = LogManager.getLogger(ParcelAgentGenerator.class);
    private static final GeometryFactory GF = new GeometryFactory();

    public record Result(int personsAdded, int parcels, int skippedSameLink, int clippedOutside) {
    }

    private ParcelAgentGenerator() {
    }

    public static Result generate(Map<String, List<Delivery>> byProvider, Geometry serviceArea,
                                  Network drtNetwork, List<Coord> depotCoords,
                                  Population population, long seed) {
        DepotNetwork depots = new DepotNetwork(depotCoords.stream()
                .map(c -> new DepotNetwork.Depot("depot_" + c.getX() + "_" + c.getY(), c))
                .collect(Collectors.toList()));
        DeliveryChannelResolver resolver = new DeliveryChannelResolver(List.of(), 500.0); // Phase 1: no lockers
        Random rnd = new Random(seed);
        PopulationFactory pf = population.getFactory();

        int persons = 0, parcels = 0, skipped = 0, outside = 0, index = 0;
        for (Map.Entry<String, List<Delivery>> e : byProvider.entrySet()) {
            for (Delivery d : e.getValue()) {
                index++;                                     // DBF has no id column -> index is the identity
                if (!serviceArea.contains(GF.createPoint(new org.locationtech.jts.geom.Coordinate(
                        d.getCoordinate().getX(), d.getCoordinate().getY())))) {
                    outside++;
                    continue;
                }
                Coord depotCoord = depots.nearestDepot(d.getCoordinate()).coord();
                Link depotLink = NetworkUtils.getNearestLinkExactly(drtNetwork, depotCoord);
                Link segmentLink = NetworkUtils.getNearestLinkExactly(drtNetwork, d.getCoordinate());
                if (depotLink.getId().equals(segmentLink.getId())) {
                    skipped++;                               // DefaultPassengerRequestValidator rejects from==to
                    LOG.warn("parcel segment {} snaps to its depot link {} - skipped", index, depotLink.getId());
                    continue;
                }

                Person p = pf.createPerson(Id.createPersonId(SharedUse.PARCEL_PERSON_PREFIX
                        + d.getProvider() + "_" + index + "_" + d.getParcelType()));
                PopulationUtils.putSubpopulation(p, SharedUse.PARCEL_SUBPOPULATION);
                p.getAttributes().putAttribute(SharedUse.LOAD_ATTRIBUTE, d.getAmount());
                p.getAttributes().putAttribute(SharedUse.DWELL_ATTRIBUTE,
                        SharedUse.segmentDwellSeconds(d.getAmount()));
                p.getAttributes().putAttribute(SharedUse.CHANNEL_ATTRIBUTE, resolver.resolve(d).name());
                p.getAttributes().putAttribute("provider", d.getProvider());
                double windowEnd = d.getParcelType() == Delivery.ParcelType.B2B
                        ? SharedUse.B2B_WINDOW_END_S : SharedUse.B2C_WINDOW_END_S;
                p.getAttributes().putAttribute(SharedUse.WINDOW_END_ATTRIBUTE, windowEnd);

                Plan plan = pf.createPlan();
                Activity depot = pf.createActivityFromLinkId(SharedUse.ACT_DEPOT, depotLink.getId());
                depot.setEndTime(SharedUse.SUBMIT_FROM_S
                        + rnd.nextDouble() * (SharedUse.SUBMIT_TO_S - SharedUse.SUBMIT_FROM_S));
                plan.addActivity(depot);
                plan.addLeg(pf.createLeg("drt"));
                plan.addActivity(pf.createActivityFromLinkId(SharedUse.ACT_DELIVERY, segmentLink.getId()));
                p.addPlan(plan);
                population.addPerson(p);
                persons++;
                parcels += d.getAmount();
            }
        }
        LOG.info("ParcelAgentGenerator: {} parcel-persons ({} parcels), {} outside area, {} same-link skipped",
                persons, parcels, outside, skipped);
        return new Result(persons, parcels, skipped, outside);
    }
}
