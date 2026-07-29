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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
     * Task 1 (paper-readiness review F1/F3/F5/F7, METHODS-LOG.md &sect;2.16): plan-time
     * accounting over the ROUTED carriers (before any dispatch happens), so that parcels jsprit
     * could not fit into any tour under the current cap no longer vanish from every downstream
     * number. Null-safe by design: a carrier missing an attribute (never routed through
     * {@code recordUnassignedJobs} / {@code LmdCarrierBuilder.buildCore}, e.g. a legacy fixture)
     * contributes 0 to every sum, no warning - mirroring {@code extract_freight}'s own tolerance
     * for a missing attribute.
     *
     * <p>Conservation identity 0 ({@code parcelsDemand ==} parcels actually planned into
     * {@code tours} {@code + parcelsUnassignedJsprit}) is checked here and logged loudly but
     * NON-FATALLY on mismatch - an empty-tour skip in {@link #convert} (ambiguity #4) can
     * legitimately cause one, and this class never aborts a run for an accounting anomaly (user
     * decision: count + warn loudly, never abort).
     */
    public static ModularPlanStats planStats(Carriers carriers, List<ModularFreightTour> tours) {
        long parcelsDemand = 0;
        long parcelsUnassignedJsprit = 0;
        long parcelsMissedOverlay = 0;
        // Sorted by id, same as convert() - not load-bearing for the sums (commutative), but kept
        // consistent with this class's stated determinism invariant for anyone reading logs.
        for (Carrier carrier : carriers.getCarriers().values().stream()
                .sorted(Comparator.comparing(c -> c.getId().toString())).toList()) {
            parcelsDemand += intAttr(carrier, "numberOfParcels");
            parcelsUnassignedJsprit += intAttr(carrier, "unassignedParcels");
            parcelsMissedOverlay += intAttr(carrier, "missedParcels");
        }

        int maxParcelsPerTour = tours.stream().mapToInt(ModularFreightTour::totalParcels)
                .max().orElse(0);
        Map<String, Id<Link>> depotByTourId = new LinkedHashMap<>();
        for (ModularFreightTour tour : tours) {
            depotByTourId.put(tour.tourId(), tour.depotLink());
        }

        long plannedFromTours = tours.stream().mapToInt(ModularFreightTour::totalParcels).sum();
        if (parcelsDemand != plannedFromTours + parcelsUnassignedJsprit) {
            LOG.error("Modular plan-stats conservation identity 0 VIOLATED: parcelsDemand={} != "
                    + "plannedFromTours={} + parcelsUnassignedJsprit={} (sum={}) - an empty-tour "
                    + "skip in convert() can legitimately cause this (see its javadoc); CSV is "
                    + "still written, this is loud but non-fatal.",
                    parcelsDemand, plannedFromTours, parcelsUnassignedJsprit,
                    plannedFromTours + parcelsUnassignedJsprit);
        }
        if (parcelsUnassignedJsprit > 0) {
            LOG.warn("jsprit left {} parcels UNPLANNED under the current tour cap - delta_parcels"
                    + " is measured against the post-assignment base; see METHODS-LOG 2.16",
                    parcelsUnassignedJsprit);
        }

        return new ModularPlanStats(parcelsDemand, parcelsUnassignedJsprit, parcelsMissedOverlay,
                maxParcelsPerTour, depotByTourId);
    }

    /** Null-safe int carrier attribute read: an absent attribute contributes 0, never throws. */
    private static int intAttr(Carrier carrier, String key) {
        Object v = carrier.getAttributes().getAttribute(key);
        if (v instanceof Integer i) return i;
        if (v instanceof Number n) return n.intValue();
        return 0;
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
        // Every leg's expected transport time is summed here, INCLUDING the final leg back to the
        // depot (a tour's tourElements end on a leg, not an activity - Tour.Builder.scheduleEnd
        // stores "end" as a separate field, never appending it to tourElements; see Tour.java).
        // Task 7's expiry formula (now + 2xRETOOLING_S + plannedDuration > latestEnd) is computed
        // from this exact sum, so dropping the return leg would systematically UNDERSTATE
        // plannedDuration and let an actually-too-late tour look dispatchable.
        //
        // Only Leg/ServiceActivity are handled here: Pickup/Delivery (CarrierShipment-based
        // activities) are silently skipped. That is safe TODAY because this codebase only ever
        // builds CarrierService jobs (LmdCarrierBuilder) - never a CarrierShipment - so neither
        // ever appears in a HAGRID tour in practice. If shipments are ever introduced, this loop
        // must be extended, or a shipment-based tour will silently lose its stops and duration.
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
