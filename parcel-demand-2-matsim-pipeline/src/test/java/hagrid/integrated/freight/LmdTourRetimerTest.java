package hagrid.integrated.freight;

import hagrid.utils.routing.HAGRIDRouterUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierPlan;
import org.matsim.freight.carriers.CarrierService;
import org.matsim.freight.carriers.CarrierVehicle;
import org.matsim.freight.carriers.CarriersUtils;
import org.matsim.freight.carriers.ScheduledTour;
import org.matsim.freight.carriers.Tour;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Root-cause context (2026-07-30): with {@code FleetSize.INFINITE} jsprit clones ONE vehicle
 * template per provider x type, so every clone tour departs at the identical jittered second
 * (observed: 14 dhl tours at 07:41:32 in {@code bandz_central_seed1234}). Legacy Hannover only
 * looks staggered because its 187 provider-x-hub carriers each draw their own jitter. The
 * retimer restores per-vehicle staggering AFTER jsprit: every scheduled tour gets its own
 * fresh Gaussian draw around its template's wave hour, on its own vehicle copy.
 */
@DisplayName("LmdTourRetimer")
class LmdTourRetimerTest {

    private static final Id<Link> DEPOT = Id.createLinkId("ab");
    private static final double H8 = 8 * 3600.0;
    private static final double H21 = 21 * 3600.0;

    private VehicleType van(String id) {
        VehicleType t = VehicleUtils.createVehicleType(Id.create(id, VehicleType.class));
        t.getCapacity().setOther(165);
        t.setNetworkMode("car");
        return t;
    }

    private CarrierVehicle template(String vehId, VehicleType type, double start) {
        return CarrierVehicle.Builder.newInstance(Id.createVehicleId(vehId), DEPOT, type)
                .setEarliestStart(start)
                .setLatestEnd(Math.min(start + HAGRIDRouterUtils.MAXROUTEDURATION + 3600.0, H21))
                .build();
    }

    /**
     * Builds a jsprit-shaped scheduled tour (start - leg - service - leg - end) whose leg
     * expected times are consistent with {@code departure}, so the planned duration readable
     * from the last leg is exactly {@code durationSec}.
     */
    private ScheduledTour tour(String tourId, CarrierVehicle vehicle, double departure,
                               double durationSec) {
        double legTime = 600.0;
        double serviceDuration = durationSec - 2 * legTime;
        CarrierService service = CarrierService.Builder
                .newInstance(Id.create(tourId + "_svc", CarrierService.class), DEPOT)
                .setServiceDuration(serviceDuration)
                .build();
        Tour.Builder tb = Tour.Builder.newInstance(Id.create(tourId, Tour.class));
        tb.scheduleStart(DEPOT);
        tb.addLeg(tb.createLeg(null, departure, legTime));
        tb.scheduleService(service);
        tb.addLeg(tb.createLeg(null, departure + legTime + serviceDuration, legTime));
        tb.scheduleEnd(DEPOT);
        return ScheduledTour.newInstance(tb.build(), vehicle, departure);
    }

    private Carrier carrier() {
        return CarriersUtils.createCarrier(Id.create("dhl", Carrier.class));
    }

    @Test
    @DisplayName("clone tours of ONE template get distinct per-tour departures near the wave hour")
    void spreadsCloneToursOfOneTemplate() {
        Carrier carrier = carrier();
        VehicleType m = van("ct_cep_size_m");
        CarrierVehicle tpl = template("dhl_ct_cep_size_m_h8_v2", m, 27692.0); // 07:41:32
        CarriersUtils.addCarrierVehicle(carrier, tpl);

        List<ScheduledTour> tours = new ArrayList<>(List.of(
                tour("t1", tpl, 27692.0, 2 * 3600.0),
                tour("t2", tpl, 27692.0, 2 * 3600.0),
                tour("t3", tpl, 27692.0, 2 * 3600.0)));
        // captured BEFORE retime: the plan mutates the tour list in place, so asserting against
        // "tours" afterwards would compare the plan with itself
        List<Tour> originalTours = tours.stream().map(ScheduledTour::getTour).toList();
        CarrierPlan plan = new CarrierPlan(tours);
        plan.setJspritScore(-123.0);

        LmdTourRetimer.retime(carrier, plan, new Random(42),
                HAGRIDRouterUtils.MAXROUTEDURATION);

        assertThat(plan.getScheduledTours()).hasSize(3);
        List<Double> departures = plan.getScheduledTours().stream()
                .map(ScheduledTour::getDeparture).distinct().toList();
        assertThat(departures)
                .as("every clone tour must get its own departure second, not the template's")
                .hasSize(3);
        // sigma 15 min for size m: all draws stay near the 08:00 wave hour (1h > 3 sigma)
        assertThat(plan.getScheduledTours())
                .allMatch(st -> Math.abs(st.getDeparture() - H8) < 3600.0);
        for (ScheduledTour st : plan.getScheduledTours()) {
            // each tour rides its own vehicle copy whose window matches the new departure
            assertThat(st.getVehicle().getEarliestStartTime()).isEqualTo(st.getDeparture());
            assertThat(st.getVehicle().getLatestEndTime()).isEqualTo(Math.min(
                    st.getDeparture() + HAGRIDRouterUtils.MAXROUTEDURATION + 3600.0, H21));
            assertThat(st.getVehicle().getType()).isSameAs(m);
            assertThat(st.getVehicle().getLinkId()).isEqualTo(DEPOT);
            // the new vehicle must be registered, or the written carrier XML cannot be re-read
            assertThat(carrier.getCarrierCapabilities().getCarrierVehicles())
                    .containsKey(st.getVehicle().getId());
        }
        // distinct vehicle ids per tour
        assertThat(plan.getScheduledTours().stream()
                .map(st -> st.getVehicle().getId()).distinct()).hasSize(3);
        // tour composition untouched (same Tour objects), jsprit score preserved
        assertThat(plan.getScheduledTours()).extracting(ScheduledTour::getTour)
                .containsExactlyInAnyOrderElementsOf(originalTours);
        assertThat(plan.getJspritScore()).isEqualTo(-123.0);
    }

    @Test
    @DisplayName("deterministic: same seed -> identical departures (reproducible runs)")
    void deterministicUnderSameSeed() {
        List<List<Double>> results = new ArrayList<>();
        for (int run = 0; run < 2; run++) {
            Carrier carrier = carrier();
            VehicleType m = van("ct_cep_size_m");
            CarrierVehicle tpl = template("dhl_ct_cep_size_m_h8_v2", m, 27692.0);
            CarriersUtils.addCarrierVehicle(carrier, tpl);
            CarrierPlan plan = new CarrierPlan(new ArrayList<>(List.of(
                    tour("t1", tpl, 27692.0, 2 * 3600.0),
                    tour("t2", tpl, 27692.0, 2 * 3600.0))));
            LmdTourRetimer.retime(carrier, plan, new Random(4711),
                    HAGRIDRouterUtils.MAXROUTEDURATION);
            results.add(plan.getScheduledTours().stream().map(ScheduledTour::getDeparture).toList());
        }
        assertThat(results.get(0)).isEqualTo(results.get(1));
    }

    @Test
    @DisplayName("afternoon wave: a late draw is clamped so the tour still ends by 21:00")
    void clampsToLatestVehicleEnd() {
        Carrier carrier = carrier();
        VehicleType m = van("ct_cep_size_m");
        CarrierVehicle tpl = template("dhl_ct_cep_size_m_h14_v0", m, 14 * 3600.0);
        CarriersUtils.addCarrierVehicle(carrier, tpl);
        double duration = 6.5 * 3600.0; // planned 14:00-20:30
        CarrierPlan plan = new CarrierPlan(new ArrayList<>(List.of(
                tour("t1", tpl, 14 * 3600.0, duration))));

        // force a +2 sigma draw: raw start 15:00 -> tour would end 21:30, past the 21:00 cap
        Random alwaysPlusTwoSigma = new Random() {
            @Override
            public synchronized double nextGaussian() {
                return 2.0;
            }
        };
        LmdTourRetimer.retime(carrier, plan, alwaysPlusTwoSigma,
                HAGRIDRouterUtils.MAXROUTEDURATION);

        ScheduledTour st = plan.getScheduledTours().iterator().next();
        assertThat(st.getDeparture() + duration)
                .as("clamped departure must keep the planned tour inside the 21:00 hard end")
                .isLessThanOrEqualTo(H21);
        assertThat(st.getDeparture()).isEqualTo(H21 - duration); // 14:30
        assertThat(st.getVehicle().getEarliestStartTime()).isEqualTo(st.getDeparture());
    }

    @Test
    @DisplayName("vehicles without the wave-id pattern (e.g. modular _day_v0) stay untouched")
    void leavesNonWaveVehiclesAlone() {
        Carrier carrier = carrier();
        VehicleType m = van("ct_cep_size_m");
        CarrierVehicle day = CarrierVehicle.Builder
                .newInstance(Id.createVehicleId("dhl_ct_cep_size_m_day_v0"), DEPOT, m)
                .setEarliestStart(27000.0).setLatestEnd(75600.0).build();
        CarriersUtils.addCarrierVehicle(carrier, day);
        CarrierPlan plan = new CarrierPlan(new ArrayList<>(List.of(
                tour("t1", day, 27000.0, 2 * 3600.0))));

        LmdTourRetimer.retime(carrier, plan, new Random(42),
                HAGRIDRouterUtils.MAXROUTEDURATION);

        ScheduledTour st = plan.getScheduledTours().iterator().next();
        assertThat(st.getDeparture()).isEqualTo(27000.0);
        assertThat(st.getVehicle()).isSameAs(day);
        assertThat(carrier.getCarrierCapabilities().getCarrierVehicles()).hasSize(1);
    }
}
