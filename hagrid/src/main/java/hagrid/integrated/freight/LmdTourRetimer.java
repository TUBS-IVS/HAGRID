package hagrid.integrated.freight;

import org.matsim.api.core.v01.Id;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierPlan;
import org.matsim.freight.carriers.CarrierVehicle;
import org.matsim.freight.carriers.CarriersUtils;
import org.matsim.freight.carriers.ScheduledTour;
import org.matsim.freight.carriers.Tour;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Post-jsprit departure staggering for the Lausitz LMD baseline (root cause 2026-07-30, see
 * BACKLOG "LMD Dispatch-Stunden besser streuen"): with {@code FleetSize.INFINITE} jsprit clones
 * ONE vehicle template per provider x van type, so every clone tour inherits the identical
 * jittered start second — with only 7 region-wide carriers the departures visibly group (e.g.
 * 14 dhl tours at 07:41:32). Legacy Hannover only looks per-vehicle staggered because its 187
 * provider-x-hub carriers each draw independent jitter.
 *
 * <p>The retimer restores that per-vehicle individuality WITHOUT touching the jsprit solution:
 * each scheduled tour keeps its exact stop sequence but gets a fresh Gaussian departure draw
 * around its template's wave hour (same sigmas as {@code CarrierVehicleFactory.getTimeShift})
 * on its own vehicle copy with a wave-relative operating window. Callers re-route the plan
 * afterwards ({@code NetworkRouter.routePlan}) so leg times stay consistent with the shifted
 * departures. Draws happen in a content-sorted tour order from a per-carrier seeded RNG, so
 * results are reproducible run-to-run.
 *
 * <p>Only vehicles following the wave-template id pattern {@code ..._h<hour>_v<copy>} (built by
 * {@link LmdCarrierBuilder#build}) are retimed; anything else (e.g. the 1d modular
 * {@code _day_v0} vehicles) passes through untouched.
 */
final class LmdTourRetimer {

    /** Wave-template vehicle id suffix, e.g. {@code dhl_ct_cep_size_m_h8_v2}. */
    private static final Pattern WAVE_TEMPLATE_ID = Pattern.compile("_h(\\d+)_v\\d+$");

    /** Absolute cap on any vehicle's operating end (Hannover parity: 21:00). */
    private static final double LATEST_VEHICLE_END = hagrid.integrated.DeliveryDay.END_S;

    private LmdTourRetimer() {}

    /**
     * Re-times every wave-template tour of {@code plan} in place: new per-tour departure, new
     * per-tour vehicle copy (registered on {@code carrier} so the written XML stays readable).
     * The planned tour duration is read from the routed legs, and the departure is clamped so
     * the tour still ends by 21:00 (relevant for the 14:00 wave).
     */
    static void retime(Carrier carrier, CarrierPlan plan, Random random, int maxRouteDurationSeconds) {
        List<ScheduledTour> tours = new ArrayList<>(plan.getScheduledTours());
        // content-based draw order, independent of jsprit's route collection order
        tours.sort(Comparator
                .comparing((ScheduledTour st) -> st.getVehicle().getId().toString())
                .thenComparing(LmdTourRetimer::firstServiceId));

        Map<Id<?>, Integer> copyCounter = new HashMap<>();
        List<ScheduledTour> retimed = new ArrayList<>(tours.size());
        for (ScheduledTour st : tours) {
            CarrierVehicle template = st.getVehicle();
            Matcher m = WAVE_TEMPLATE_ID.matcher(template.getId().toString());
            if (!m.find()) {
                retimed.add(st);
                continue;
            }
            int waveHour = Integer.parseInt(m.group(1));
            double sigmaMinutes = LmdCarrierBuilder.jitterSigmaMinutes(template.getType());
            double start = waveHour * 3600.0 + random.nextGaussian() * sigmaMinutes * 60.0;
            // keep the planned tour inside the 21:00 hard end (binds on the 14:00 wave)
            start = Math.max(0.0, Math.min(start, LATEST_VEHICLE_END - plannedDuration(st)));
            double end = Math.min(start + maxRouteDurationSeconds + 3600.0, LATEST_VEHICLE_END);

            int copy = copyCounter.merge(template.getId(), 1, Integer::sum) - 1;
            CarrierVehicle vehicle = CarrierVehicle.Builder
                    .newInstance(Id.createVehicleId(template.getId() + "_t" + copy),
                            template.getLinkId(), template.getType())
                    .setEarliestStart(start)
                    .setLatestEnd(end)
                    .build();
            CarriersUtils.addCarrierVehicle(carrier, vehicle);
            retimed.add(ScheduledTour.newInstance(st.getTour(), vehicle, start));
        }

        Collection<ScheduledTour> planTours = plan.getScheduledTours();
        planTours.clear();
        planTours.addAll(retimed);
    }

    /**
     * Planned tour duration read from the routed legs: the jsprit/NetworkRouter tour shape is
     * start - leg - ... - leg - end, so the last element is the depot-return leg whose expected
     * departure + transport time is the tour end. Falls back to 0 (no clamping) if the tour has
     * no routed legs.
     */
    private static double plannedDuration(ScheduledTour st) {
        List<Tour.TourElement> elements = st.getTour().getTourElements();
        if (elements.isEmpty()
                || !(elements.get(elements.size() - 1) instanceof Tour.Leg lastLeg)) {
            return 0.0;
        }
        double end = lastLeg.getExpectedDepartureTime() + lastLeg.getExpectedTransportTime();
        return Math.max(0.0, end - st.getDeparture());
    }

    /** Stable content-based sort key: id of the tour's first service activity ("" if none). */
    private static String firstServiceId(ScheduledTour st) {
        for (Tour.TourElement e : st.getTour().getTourElements()) {
            if (e instanceof Tour.ServiceActivity act) {
                return act.getService().getId().toString();
            }
        }
        return "";
    }
}
