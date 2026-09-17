package hagrid.integrated.shareduse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contrib.drt.optimizer.insertion.InsertionDetourTimeCalculator.DetourTimeInfo;
import org.matsim.contrib.drt.optimizer.insertion.InsertionDetourTimeCalculator.DropoffDetourInfo;
import org.matsim.contrib.drt.optimizer.insertion.InsertionDetourTimeCalculator.PickupDetourInfo;
import org.matsim.contrib.drt.passenger.DrtRequest;
import org.matsim.contrib.dvrp.load.DvrpLoadType;
import org.matsim.contrib.dvrp.load.IntegersLoadType;
import org.matsim.contrib.dvrp.optimizer.Request;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Detour instrumentation (2026-08-10, METHODS-LOG 2.31): the gate records the
 * {@code detourOnly} value of EVERY parcel evaluation, and {@link ChiGateStats} keeps the
 * per-segment MINIMUM.
 *
 * <p>Why the minimum and not another counter: {@code chi_blocked_segments} saturates at
 * {@code segments_submitted} in every measured run, so "was ever blocked" holds for the ~93 %
 * of segments that were DELIVERED too — the counter cannot separate success from failure and
 * the attribution it feeds is an identity. The smallest achievable detour is a lower bound on
 * the χ a segment would have needed, so it can.
 *
 * <p><b>Recorded value, since 2026-08-13.</b> {@code detourOnly} is the PER-LEG residual
 * (METHODS-LOG 2.35), so the recorded minimum is a drive-only detour. The runs measured under
 * the summed form ({@code chid600w21}, {@code chid600det}) understate it — that is where the
 * 1096 exact zeros came from — and their minima are not comparable with later runs.
 */
@DisplayName("ChiGateStats detour distribution")
class ChiGateDetourStatsTest {

    private static final DvrpLoadType LOAD_TYPE = new IntegersLoadType("passengers", "parcels");
    private static final Id<Person> PARCEL = Id.createPersonId("parcel_dhl_1_B2C");

    /** The Lausitz {@code drtCfg.getStopDuration()} — the floor MinimumStopDurationAdapter applies. */
    private static final double MIN_STOP_S = 60.0;

    private static DrtRequest parcelRequest(int parcels) {
        return DrtRequest.newBuilder()
                .id(Id.create("r1", Request.class))
                .passengerIds(List.of(PARCEL))
                .mode("drt")
                .load(DvrpLoadType.fromArray(LOAD_TYPE, 0, parcels))
                .build();
    }

    private static DrtRequest paxRequest() {
        return DrtRequest.newBuilder()
                .id(Id.create("r2", Request.class))
                .passengerIds(List.of(Id.createPersonId("person_42")))
                .mode("drt")
                .load(DvrpLoadType.fromArray(LOAD_TYPE, 1, 0))
                .build();
    }

    /** The two legs the gate reads separately: pickupTimeLoss and dropoffTimeLoss. */
    private static DetourTimeInfo detour(double pickupTimeLoss, double dropoffTimeLoss) {
        return new DetourTimeInfo(new PickupDetourInfo(0.0, pickupTimeLoss),
                new DropoffDetourInfo(0.0, dropoffTimeLoss));
    }

    /**
     * Solo insertion (two new stops): each leg carries its own dwell under the schedule's minimum
     * stop duration, {@code drive} on top.
     */
    private static DetourTimeInfo solo(int parcels, double drive) {
        return detour(Math.max(SharedUse.depotPickupSeconds(parcels), MIN_STOP_S),
                Math.max(SharedUse.segmentDwellSeconds(parcels), MIN_STOP_S) + drive);
    }

    private static ChiGateInsertionCostCalculator gate(double chi, ChiGateStats stats) {
        return new ChiGateInsertionCostCalculator((req, ins, d) -> 1.0, chi, LOAD_TYPE, MIN_STOP_S, stats);
    }

    @Test
    @DisplayName("keeps the SMALLEST detour a segment was ever offered, not the last one")
    void keepsTheMinimumAcrossCandidates() {
        ChiGateStats stats = new ChiGateStats();
        var g = gate(600.0, stats);
        // Three candidates for the same segment: 900 s (blocked), 200 s (kept), 750 s (blocked).
        // The order deliberately puts the minimum in the middle so a "last value wins" bug shows.
        g.calculate(parcelRequest(3), null, solo(3, 900.0));
        g.calculate(parcelRequest(3), null, solo(3, 200.0));
        g.calculate(parcelRequest(3), null, solo(3, 750.0));

        ChiGateStats.SegmentDetour d = stats.detourBySegment().get(PARCEL);
        assertThat(d.minDetourSeconds()).isEqualTo(200.0);
        assertThat(d.evaluations()).as("every evaluation counts, kept and blocked alike").isEqualTo(3);
        assertThat(d.parcels).isEqualTo(3);
    }

    @Test
    @DisplayName("records BLOCKED evaluations too - otherwise expired segments have no minimum at all")
    void recordsBlockedEvaluations() {
        ChiGateStats stats = new ChiGateStats();
        // Every candidate is far over chi, so under a "record only what passes" implementation
        // this segment - exactly the kind the analysis is about - would be missing entirely.
        gate(600.0, stats).calculate(parcelRequest(2), null, solo(2, 4000.0));

        assertThat(stats.detourBySegment()).containsKey(PARCEL);
        assertThat(stats.detourBySegment().get(PARCEL).minDetourSeconds()).isEqualTo(4000.0);
    }

    @Test
    @DisplayName("records the per-leg residual, like the gate decision itself")
    void recordsDetourOnlyNotRawTimeLoss() {
        ChiGateStats stats = new ChiGateStats();
        // n=10 -> own durations 300 (depot) / 900 (door). Legs 300 / 1200 -> residual 300.
        gate(600.0, stats).calculate(parcelRequest(10), null, detour(300.0, 1200.0));

        assertThat(stats.detourBySegment().get(PARCEL).minDetourSeconds())
                .as("recording the raw 1500 would put a piggyback insertion in the same bucket "
                        + "as a genuine 25-minute detour")
                .isEqualTo(300.0);
    }

    @Test
    @DisplayName("a piggyback credit on one leg is not recorded against the other leg's detour")
    void creditDoesNotCrossLegs() {
        ChiGateStats stats = new ChiGateStats();
        // n=13: own durations 390 / 900 (summed 1290). Depot stop piggybacked -> pickup leg 0;
        // the delivery costs 300 s of real driving on top of its 900 s dwell.
        // The summed form recorded max(0, 1200 - 1290) = 0 and called this a free ride.
        gate(600.0, stats).calculate(parcelRequest(13), null, detour(0.0, 1200.0));

        assertThat(stats.detourBySegment().get(PARCEL).minDetourSeconds()).isEqualTo(300.0);
    }

    @Test
    @DisplayName("clamps at 0 per leg for a fully piggybacked insertion, mirroring the gate")
    void clampsAtZero() {
        ChiGateStats stats = new ChiGateStats();
        // Both stops co-located: each leg carries only the ADDITIONAL duration (max-semantics of
        // ParallelStopTimeCalculator) and no drive terms, so both subtractions overshoot.
        gate(600.0, stats).calculate(parcelRequest(8), null, detour(10.0, 5.0));

        assertThat(stats.detourBySegment().get(PARCEL).minDetourSeconds()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("passenger requests are never recorded")
    void paxRequestsAreNotRecorded() {
        ChiGateStats stats = new ChiGateStats();
        gate(600.0, stats).calculate(paxRequest(), null, detour(5000.0, 5000.0));

        assertThat(stats.detourBySegment()).isEmpty();
    }

    @Test
    @DisplayName("hard-closed mode (chi<0) still records - it is the unperturbed reference run")
    void hardClosedModeStillRecords() {
        ChiGateStats stats = new ChiGateStats();
        // chi=-1 rejects unconditionally, so no parcel ever boards and the vehicle trajectory
        // stays the pure pax one. That makes its minima the reference distribution - but only if
        // the recording happens BEFORE the early return.
        gate(-1.0, stats).calculate(parcelRequest(5), null, solo(5, 333.0));

        assertThat(stats.detourBySegment().get(PARCEL).minDetourSeconds()).isEqualTo(333.0);
        assertThat(stats.blockedSegmentCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("reset() clears the detour map with the other per-iteration counters")
    void resetClearsDetours() {
        ChiGateStats stats = new ChiGateStats();
        gate(600.0, stats).calculate(parcelRequest(1), null, solo(1, 100.0));
        assertThat(stats.detourBySegment()).isNotEmpty();

        // C1 lesson: request ids restart every iteration. A detour map that survives the reset
        // would make the shutdown CSV a cross-iteration aggregate.
        stats.reset();
        assertThat(stats.detourBySegment()).isEmpty();
    }

    @Test
    @DisplayName("the returned view is read-only")
    void viewIsUnmodifiable() {
        ChiGateStats stats = new ChiGateStats();
        gate(600.0, stats).calculate(parcelRequest(1), null, solo(1, 0.0));
        var view = stats.detourBySegment();
        assertThat(view).hasSize(1);
        try {
            view.clear();
            org.junit.jupiter.api.Assertions.fail("the view must not be mutable");
        } catch (UnsupportedOperationException expected) {
            // exactly what we want
        }
    }
}
