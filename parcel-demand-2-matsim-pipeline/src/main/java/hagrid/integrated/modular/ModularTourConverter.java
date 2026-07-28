package hagrid.integrated.modular;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.network.NetworkUtils;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierPlanXmlReader;
import org.matsim.freight.carriers.CarrierService;
import org.matsim.freight.carriers.Carriers;
import org.matsim.freight.carriers.CarrierVehicleTypes;
import org.matsim.freight.carriers.ScheduledTour;
import org.matsim.freight.carriers.Tour;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Converts routed {@code CarrierPlan}s ({@code LausitzFreightPreprocessor.runModular} output)
 * into {@link ModularFreightTour}s the online dispatcher (Task 7) can consume.
 *
 * <p>Deterministic by construction (global constraint, not an incidental property): carriers are
 * iterated sorted by id (never the raw {@code Carriers} map order, which is insertion order and
 * therefore an accident of demand-file iteration), tours are indexed in PLAN order within a
 * carrier, and every tour id is {@code "<carrier>_t<i>"} - never a {@code UUID}, so HAGRID runs
 * stay byte-reproducible (spike §3.5).
 *
 * <p>Stop and depot links are snapped from the CAR network (what jsprit routed on) onto the DRT
 * network (what the DRT fleet actually drives) by coordinate ({@code getNearestLinkExactly} on
 * the car link's to-node coord), mirroring {@code LausitzDrtPreprocessor}'s parcel-snap
 * precedent. A tour whose links are not DRT-routable is a tour the Task 6 splicer cannot execute.
 */
public final class ModularTourConverter {

    private static final Logger LOG = LogManager.getLogger(ModularTourConverter.class);

    private ModularTourConverter() {}

    /** Reads a routed carriers file (production entry point: the file {@code runModular} wrote). */
    public static Carriers read(String carriersFile, CarrierVehicleTypes types) {
        Carriers carriers = new Carriers();
        new CarrierPlanXmlReader(carriers, types).readFile(carriersFile);
        return carriers;
    }

    /**
     * Converts every carrier's SELECTED plan into its {@link ModularFreightTour}s. Carriers with
     * no selected plan (no demand was ever assigned to them) contribute nothing - there is no
     * partial/fallback tour to build from an absent plan.
     */
    public static List<ModularFreightTour> convert(Carriers carriers, Network carNetwork,
                                                    Network drtNetwork) {
        List<ModularFreightTour> tours = new ArrayList<>();
        carriers.getCarriers().values().stream()
                .sorted(Comparator.comparing(c -> c.getId().toString()))
                .forEach(carrier -> {
                    if (carrier.getSelectedPlan() == null) {
                        return;
                    }
                    int index = 0;
                    for (ScheduledTour st : carrier.getSelectedPlan().getScheduledTours()) {
                        // tourIndex reflects position in the ORIGINAL jsprit plan, not the
                        // filtered output count, so a skipped tour never shifts the index of the
                        // ones after it (see toModularTour's null-return contract below).
                        ModularFreightTour tour = toModularTour(
                                carrier.getId().toString(), index++, st, carNetwork, drtNetwork);
                        if (tour != null) {
                            tours.add(tour);
                        }
                    }
                });
        return tours;
    }

    /**
     * Builds one tour from one scheduled tour, or returns {@code null} if it has zero service
     * stops. Ambiguity #4: {@code MatsimJspritFactory.createPlan} only ever emits a
     * {@code ScheduledTour} for a route jsprit actually assigned jobs to, so an empty one should
     * not occur in production output - but {@link ModularFreightTour}'s compact constructor
     * REJECTS an empty stop list by design (a dispatchable tour needs somewhere to dispatch to),
     * so a defensive empty tour must not be allowed to surface that exception from deep inside a
     * batch conversion. Skipping with a WARN log (rather than throwing, and rather than silently
     * dropping without a trace) keeps one anomalous carrier from aborting the whole conversion
     * while still surfacing it for investigation - mirroring the
     * {@code LausitzFreightPreprocessor.recordUnassignedJobs} convention of logging routing
     * anomalies instead of crashing on them.
     */
    private static ModularFreightTour toModularTour(String carrierId, int index, ScheduledTour st,
                                                     Network carNetwork, Network drtNetwork) {
        List<ModularFreightTour.Stop> stops = new ArrayList<>();
        double duration = 0.0;
        for (Tour.TourElement el : st.getTour().getTourElements()) {
            if (el instanceof Tour.Leg leg) {
                duration += leg.getExpectedTransportTime();
            } else if (el instanceof Tour.ServiceActivity act) {
                CarrierService service = act.getService();
                duration += service.getServiceDuration();
                stops.add(new ModularFreightTour.Stop(
                        toDrtLink(service.getServiceLinkId(), carNetwork, drtNetwork),
                        service.getServiceDuration(),
                        service.getCapacityDemand()));
            }
        }
        String tourId = carrierId + "_t" + index;
        if (stops.isEmpty()) {
            LOG.warn("Scheduled tour {} (carrier {}, index {}) has no service stops - "
                    + "skipping (nothing to dispatch).", tourId, carrierId, index);
            return null;
        }
        return new ModularFreightTour(
                tourId,
                carrierId,
                index,
                toDrtLink(st.getVehicle().getLinkId(), carNetwork, drtNetwork),
                st.getDeparture(),
                duration,
                st.getVehicle().getLatestEndTime(),
                stops);
    }

    /**
     * Fast path: the car link id already exists in the DRT network (true whenever the DRT
     * network is a subset of the shared full network with matching ids) - use it unchanged.
     * Otherwise snap by coordinate onto the DRT network's nearest link, mirroring
     * {@code LausitzDrtPreprocessor}'s parcel-snap precedent. If the car link doesn't even exist
     * in the car network passed in, there is no coordinate to snap from at all: throwing
     * {@link IllegalStateException} is correct here - silently substituting some other link would
     * produce a tour the splicer routes to the wrong place.
     */
    private static Id<Link> toDrtLink(Id<Link> carLinkId, Network carNetwork, Network drtNetwork) {
        if (drtNetwork.getLinks().containsKey(carLinkId)) {
            return carLinkId;
        }
        Link carLink = carNetwork.getLinks().get(carLinkId);
        if (carLink == null) {
            throw new IllegalStateException("Tour link " + carLinkId + " in neither network");
        }
        return NetworkUtils.getNearestLinkExactly(drtNetwork, carLink.getToNode().getCoord()).getId();
    }
}
