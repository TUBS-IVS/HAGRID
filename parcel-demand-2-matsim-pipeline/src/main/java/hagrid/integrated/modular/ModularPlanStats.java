package hagrid.integrated.modular;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;

import java.util.Map;

/**
 * Plan-time (offline, pre-dispatch) accounting over the routed jsprit {@code Carriers} and their
 * converted {@link ModularFreightTour}s (Task 1, paper-readiness review F1/F3/F5/F7 -
 * METHODS-LOG.md &sect;2.16: {@code delta_parcels} was being measured against a demand base
 * already censored by jsprit's own unassigned-job drops, with nothing in any published CSV to
 * reveal it). Built ONCE by {@link ModularTourConverter#planStats} right after {@code convert},
 * and carried into {@link ModularKpiHandler} so the five plan-time metrics can be published
 * alongside the event-driven ones without the handler ever touching a {@code Carriers} object
 * itself.
 *
 * @param parcelsDemand           sum of every carrier's {@code numberOfParcels} attribute
 *                                ({@code LmdCarrierBuilder.buildCore}) - the RAW demand this
 *                                carrier was asked to deliver, BEFORE jsprit dropped anything.
 *                                Conservation identity 0: {@code parcelsDemand ==} (parcels
 *                                actually planned into the converted tours) {@code +
 *                                parcelsUnassignedJsprit}.
 * @param parcelsUnassignedJsprit sum of every carrier's {@code unassignedParcels} attribute
 *                                ({@code HAGRIDRouterUtils.recordUnassignedJobs}) - parcels
 *                                jsprit's best solution could not fit into any tour under the
 *                                current tour-duration cap.
 * @param parcelsMissedOverlay    sum of every carrier's {@code missedParcels} attribute - a
 *                                STATISTICAL overlay that does NOT reduce either side of
 *                                identity 0 (see {@code LmdCarrierBuilder.buildCore}).
 * @param maxParcelsPerTour       the largest {@link ModularFreightTour#totalParcels()} across the
 *                                converted tour list (0 if the list is empty).
 * @param depotByTourId           every converted tour's id mapped to its
 *                                {@link ModularFreightTour#depotLink()} - {@link
 *                                ModularKpiHandler} needs this because a {@code SWAP_DONE} event
 *                                carries a {@code tourId}, not a depot, and the GLOBAL
 *                                {@code peak_concurrent_swaps} is grouped per depot link.
 * @param districtByTourId        Task 10 (spec 2026-08-17, "make the idealisations
 *                                measurable"): every converted tour's id mapped to its
 *                                {@link ModularFreightTour#provider()} - which, for every tour
 *                                this converter ever builds (carriers always come from
 *                                {@code LmdCarrierBuilder.buildDistrict} in {@code runModular}),
 *                                IS the carrier's own {@code "district"} attribute (the
 *                                district/depot site id, e.g. {@code hoy_sued} or
 *                                {@code hoy_sued#0} when a catchment was split by
 *                                {@code maxJobsPerDistrict}). Deliberately DISTINCT from
 *                                {@link #depotByTourId}: two split sub-districts of the SAME
 *                                physical depot (e.g. {@code hoy_sued#0}/{@code hoy_sued#1})
 *                                share one {@code depotLink} but are two different sites here -
 *                                see {@link ModularKpiHandler}'s per-site swap-peak javadoc for
 *                                why that is the deliberate grouping for the PER-SITE
 *                                {@code peak_concurrent_swaps_<site>} rows, not a physical-yard
 *                                one; the pre-existing GLOBAL figure keeps using
 *                                {@link #depotByTourId} untouched.
 */
public record ModularPlanStats(long parcelsDemand, long parcelsUnassignedJsprit,
        long parcelsMissedOverlay, int maxParcelsPerTour, Map<String, Id<Link>> depotByTourId,
        Map<String, String> districtByTourId) {

    /** Defensive copy (the class-wide convention: e.g. {@code ModularFreightTour}'s
     *  {@code stops = List.copyOf(stops)}) - the caller's map must not be able to mutate this
     *  record's state after construction. */
    public ModularPlanStats {
        depotByTourId = Map.copyOf(depotByTourId);
        districtByTourId = Map.copyOf(districtByTourId);
    }
}
