package hagrid.integrated.shareduse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.contrib.drt.optimizer.insertion.InsertionCostCalculator;
import org.matsim.contrib.drt.optimizer.insertion.InsertionDetourTimeCalculator.DetourTimeInfo;
import org.matsim.contrib.drt.optimizer.insertion.InsertionDetourTimeCalculator.DropoffDetourInfo;
import org.matsim.contrib.drt.optimizer.insertion.InsertionDetourTimeCalculator.PickupDetourInfo;
import org.matsim.contrib.drt.passenger.DrtRequest;
import org.matsim.contrib.dvrp.load.DvrpLoadType;
import org.matsim.contrib.dvrp.load.IntegersLoadType;
import org.matsim.contrib.dvrp.optimizer.Request;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The χ-gate rejects a PARCEL insertion whose DRIVE-only time loss exceeds χ. Since
 * 2026-08-13 the request's own dwell is subtracted PER LEG —
 * {@code max(0, pickupTimeLoss − max(depotPickupSeconds(n), floor)) +
 * max(0, dropoffTimeLoss − max(segmentDwellSeconds(n), floor))} — with n read from the request's
 * own {@code DvrpLoad} "parcels" dimension and {@code floor} the schedule's minimum stop
 * duration. Before that the subtraction happened on the SUM with
 * both stops' nominal dwell, so an over-subtraction on a piggybacked leg paid for genuine
 * driving on the other (METHODS-LOG 2.35); the regression block below pins that down.
 * Two earlier revisions are also still pinned here: gating on the raw dwell-inclusive loss
 * made segments with n ≥ 5 structurally uninsertable at χ=600, and χ &lt; 0 is hard-closed
 * (EVERY parcel rejected, Task-10 leakage probe). Passenger requests are never gated; a kept
 * insertion returns the delegate's own cost unchanged (M6: gate on the raw time loss, NOT the
 * delegate's cost, which may include soft-constraint penalties).
 *
 * <p><b>Fixture convention.</b> Every {@code detour(...)} spells out the two legs separately.
 * The old single-argument helper put the whole time loss on the pickup leg and 0 on the
 * dropoff leg, which made every fixture blind to the difference between the summed and the
 * per-leg form — the defect's own test suite was green throughout.
 */
@DisplayName("ChiGateInsertionCostCalculator")
class ChiGateInsertionCostCalculatorTest {

    /** Same dimension layout SharedUseModule's fleet override produces. */
    private static final DvrpLoadType LOAD_TYPE = new IntegersLoadType("passengers", "parcels");

    /** The Lausitz {@code drtCfg.getStopDuration()} — the floor MinimumStopDurationAdapter applies. */
    private static final double MIN_STOP_S = 60.0;

    private static DrtRequest request(String personId) {
        return DrtRequest.newBuilder()
                .id(Id.create("r1", Request.class))
                .passengerIds(List.of(Id.createPersonId(personId)))
                .mode("drt")
                .build();
    }

    /** A parcel request carrying n parcels in its DvrpLoad (as DrtRequestCreator builds it). */
    private static DrtRequest parcelRequest(int parcels) {
        return DrtRequest.newBuilder()
                .id(Id.create("r1", Request.class))
                .passengerIds(List.of(Id.createPersonId("parcel_dhl_1_B2C")))
                .mode("drt")
                .load(DvrpLoadType.fromArray(LOAD_TYPE, 0, parcels))
                .build();
    }

    /** The two legs the gate now reads separately: pickupTimeLoss and dropoffTimeLoss. */
    private static DetourTimeInfo detour(double pickupTimeLoss, double dropoffTimeLoss) {
        return new DetourTimeInfo(
                new PickupDetourInfo(0.0, pickupTimeLoss),
                new DropoffDetourInfo(0.0, dropoffTimeLoss));
    }

    /**
     * A SOLO insertion: two brand-new stops, so each leg carries its own full stop duration —
     * under the {@link #MIN_STOP_S} floor the schedule imposes — plus the drive detour (here
     * attributed to the dropoff leg). This is the {@code InsertionDetourTimeCalculator} branch
     * where {@code stopDuration} equals the request's own duration, so {@code detourOnly} must
     * come out as {@code drive} exactly.
     */
    private static DetourTimeInfo solo(int parcels, double drive) {
        return detour(Math.max(SharedUse.depotPickupSeconds(parcels), MIN_STOP_S),
                Math.max(SharedUse.segmentDwellSeconds(parcels), MIN_STOP_S) + drive);
    }

    private static ChiGateInsertionCostCalculator gate(double delegateCost, double chi) {
        return new ChiGateInsertionCostCalculator((req, ins, detour) -> delegateCost, chi, LOAD_TYPE,
                MIN_STOP_S);
    }

    /** The gate's own arithmetic, without routing it through the accept/reject verdict. */
    private static double residual(DetourTimeInfo info, int parcels) {
        return gate(0.0, 0.0).detourOnlySeconds(info, parcels);
    }

    // ---- per-leg arithmetic ----------------------------------------------------------

    @Test
    @DisplayName("solo insertion: detourOnly is exactly the drive detour, both legs cancelled")
    void soloInsertionLeavesOnlyTheDrive() {
        // n=5: depotPickup 150, segmentDwell 600 (both above the 60 s floor). Legs 150 / 600+420.
        assertEquals(420.0, residual(solo(5, 420.0), 5));
    }

    @Test
    @DisplayName("each leg is netted against ITS OWN duration (a swapped pair would read 0)")
    void legsAreNettedAgainstTheirOwnDuration() {
        // n=1: effective durations 60 (30 s load lifted to the floor) / 120. Feed the legs the
        // OTHER leg's duration: pickup 120, dropoff 30 -> max(0,120-60) + max(0,30-120) = 60.
        // Had the two constants been swapped in the implementation this would come out 0.
        assertEquals(60.0, residual(detour(120.0, 30.0), 1));
    }

    @Test
    @DisplayName("the 60 s stop-duration floor is subtracted, not the unfloored 30 s depot load")
    void singleParcelPickupIsNettedAgainstTheFloor() {
        // MinimumStopDurationAdapter floors every stop at drtCfg.getStopDuration()=60 s, so a
        // solo single-parcel insertion carries 60 s on the pickup leg even though the parcel's
        // own load time is 30 s. Subtracting the unfloored 30 s would leave 30 s of the request's
        // OWN dwell in the residual and read it as detour — at chi=0 that flips the verdict.
        DetourTimeInfo info = detour(60.0, 120.0);

        assertEquals(0.0, residual(info, 1));
        assertEquals(11.0, gate(11.0, 0.0).calculate(parcelRequest(1), null, info),
                "a zero-detour solo insertion must pass even at chi=0");
    }

    @Test
    @DisplayName("zero-detour parcel (each leg == its own dwell) is accepted at chi=0")
    void zeroDetourParcelAcceptedAtChiZero() {
        // n=4: legs 120 / 480 -> both cancel exactly -> detourOnly = 0 <= chi = 0 -> kept.
        var gate = gate(77.0, 0.0);
        assertEquals(77.0, gate.calculate(parcelRequest(4), null, solo(4, 0.0)));
    }

    @Test
    @DisplayName("large-dwell parcel (n=10) with detour exactly chi is kept (strict >)")
    void largeDwellParcelWithinChiIsKept() {
        // n=10: own dwell 300 + 900 = 1200 s >> chi. Under the dwell-inclusive gate this request
        // was structurally uninsertable; detour-only it passes when the drive detour <= chi.
        var gate = gate(123.0, 600.0);
        assertEquals(123.0, gate.calculate(parcelRequest(10), null, solo(10, 600.0)));
    }

    @Test
    @DisplayName("large-dwell parcel (n=10) with detour above chi is infeasible")
    void largeDwellParcelAboveChiIsInfeasible() {
        var gate = gate(123.0, 600.0);
        assertEquals(InsertionCostCalculator.INFEASIBLE_SOLUTION_COST,
                gate.calculate(parcelRequest(10), null, solo(10, 600.5)));
    }

    @Test
    @DisplayName("insertion piggybacking BOTH stops clamps to 0 on both legs and is accepted at chi=0")
    void piggybackClampedToZeroIsAccepted() {
        // n=5: own durations 150 / 600, but both stops are co-located with existing ones
        // (ParallelStopTimeCalculator max-semantics -> only the ADDITIONAL duration shows up,
        // and the same-link branches carry no drive terms), so the legs read 50 / 100.
        // Both clamp to 0 -> accepted even at chi=0, and no drive detour is hidden anywhere.
        var gate = gate(55.0, 0.0);
        assertEquals(55.0, gate.calculate(parcelRequest(5), null, detour(50.0, 100.0)));
    }

    // ---- regression: the summed subtraction paid for driving with a piggyback credit ---

    @Test
    @DisplayName("regression: a piggybacked depot pickup no longer cancels dropoff detour")
    void piggybackedPickupNoLongerPaysForDropoffDetour() {
        // n=1. The depot stop is co-located with one the vehicle already serves, so the pickup
        // leg reads 0; the delivery is a genuine 100 s drive detour on top of its 120 s dwell.
        DetourTimeInfo info = detour(0.0, 120.0 + 100.0);

        assertEquals(100.0, residual(info, 1),
                "per leg: max(0, 0-30) + max(0, 220-120)");
        // The summed form computed max(0, 220 - 150) = 70: the unearned 30 s pickup credit ate
        // 30 % of the real detour. chi=90 is between the two, so the two forms disagree on the
        // VERDICT and not merely on a reported number.
        assertEquals(InsertionCostCalculator.INFEASIBLE_SOLUTION_COST,
                gate(42.0, 90.0).calculate(parcelRequest(1), null, info));
    }

    @Test
    @DisplayName("regression: a 13-parcel piggyback no longer reads as a free ride")
    void largeSegmentPiggybackIsNotFree() {
        // n=13: depotPickup 390, segmentDwell capped at 900 -> summed ownDwell 1290. Pickup leg
        // piggybacked (0), delivery = 900 dwell + 300 s of real driving.
        // Summed: max(0, 1200 - 1290) = 0 -> the segment rode "for free" and passed even at
        // chi=0. This is the mechanism behind the 1096 exact zeros in chid600det.
        DetourTimeInfo info = detour(0.0, 900.0 + 300.0);

        assertEquals(300.0, residual(info, 13));
        assertEquals(InsertionCostCalculator.INFEASIBLE_SOLUTION_COST,
                gate(42.0, 200.0).calculate(parcelRequest(13), null, info));
        assertEquals(42.0, gate(42.0, 300.0).calculate(parcelRequest(13), null, info),
                "and it is admissible again once chi actually covers the 300 s");
    }

    @Test
    @DisplayName("regression: the credit cannot flow the other way either (piggybacked delivery)")
    void piggybackedDropoffNoLongerPaysForPickupDetour() {
        // Mirror image: n=8 (durations 240 / 900). The delivery joins an existing stop at the
        // same link (leg reads 0) while the depot detour costs 400 s of driving.
        // Summed: max(0, 640 - 1140) = 0. Per leg: 400.
        DetourTimeInfo info = detour(240.0 + 400.0, 0.0);

        assertEquals(400.0, residual(info, 8));
        assertEquals(InsertionCostCalculator.INFEASIBLE_SOLUTION_COST,
                gate(42.0, 300.0).calculate(parcelRequest(8), null, info));
    }

    // ---- converted legacy cases (n=1, drive detour on the dropoff leg) ----------------

    @Test
    @DisplayName("parcel above chi (drive-only) is infeasible")
    void parcelAboveThresholdIsInfeasible() {
        // delegate cost is arbitrary (42) — the gate decides on the 700 s detour, not the cost.
        var gate = gate(42.0, 600.0);
        assertEquals(InsertionCostCalculator.INFEASIBLE_SOLUTION_COST,
                gate.calculate(parcelRequest(1), null, solo(1, 700.0)));
    }

    @Test
    @DisplayName("parcel below chi keeps the DELEGATE cost (not the raw loss)")
    void parcelBelowThresholdKeepsDelegateCost() {
        // detourOnly 500 < chi 600 -> kept; the returned value is the delegate's 123, proving we
        // return the delegate cost and do not substitute the time loss.
        var gate = gate(123.0, 600.0);
        assertEquals(123.0, gate.calculate(parcelRequest(1), null, solo(1, 500.0)));
    }

    @Test
    @DisplayName("parcel exactly at chi is kept (strict >)")
    void parcelAtThresholdIsKept() {
        var gate = gate(88.0, 600.0);
        assertEquals(88.0, gate.calculate(parcelRequest(1), null, solo(1, 600.0)));
    }

    // ---- hard-closed mode (chi < 0) --------------------------------------------------

    @Test
    @DisplayName("chi=-1 (hard-closed) rejects every parcel, even at zero time loss")
    void hardClosedRejectsEveryParcel() {
        var gate = gate(1.0, -1.0);
        assertEquals(InsertionCostCalculator.INFEASIBLE_SOLUTION_COST,
                gate.calculate(parcelRequest(1), null, detour(0.0, 0.0)));
    }

    @Test
    @DisplayName("chi=-1 (hard-closed) still delegates passenger requests")
    void hardClosedLeavesPaxUntouched() {
        var gate = gate(66.0, -1.0);
        assertEquals(66.0, gate.calculate(request("p42"), null, detour(1_000.0, 0.0)));
    }

    // ---- pax + wiring guards ---------------------------------------------------------

    @Test
    @DisplayName("passenger request is never gated, even far above chi")
    void paxIsNeverGated() {
        var gate = gate(99_999.0, 600.0);
        assertEquals(99_999.0, gate.calculate(request("p42"), null, detour(99_999.0, 99_999.0)));
    }

    @Test
    @DisplayName("parcel request without a load subtracts no dwell at all (conservative)")
    void nullLoadSubtractsNothing() {
        // Cannot happen in production (DrtRequestCreator always sets a load), but the fallback
        // must stay conservative: no own duration is subtracted from either leg, so the gate
        // sees the full dwell-inclusive time loss.
        var gate = gate(42.0, 600.0);
        assertEquals(InsertionCostCalculator.INFEASIBLE_SOLUTION_COST,
                gate.calculate(request("parcel_dhl_1_B2C"), null, detour(400.0, 300.0)));
    }

    // ---- M6 instrumentation ---------------------------------------------------------

    @Test
    @DisplayName("M6: a blocked parcel insertion is recorded (attempts + distinct segment)")
    void blockedInsertionIsRecorded() {
        ChiGateStats stats = new ChiGateStats();
        var gate = new ChiGateInsertionCostCalculator((req, ins, detour) -> 0.0, 600.0, LOAD_TYPE, MIN_STOP_S, stats);

        // Same request (same parcel-person) blocked twice: 2 attempts, 1 distinct segment.
        gate.calculate(parcelRequest(2), null, solo(2, 700.0));
        gate.calculate(parcelRequest(2), null, solo(2, 900.0));

        assertEquals(2L, stats.blockedAttempts());
        assertEquals(1, stats.blockedSegmentCount());
        assertTrue(stats.wasBlocked(Id.createPersonId("parcel_dhl_1_B2C")));
    }

    @Test
    @DisplayName("M6: an ACCEPTED parcel insertion is not recorded (the counter must not just track parcel traffic)")
    void acceptedInsertionIsNotRecorded() {
        ChiGateStats stats = new ChiGateStats();
        var gate = new ChiGateInsertionCostCalculator((req, ins, detour) -> 5.0, 600.0, LOAD_TYPE, MIN_STOP_S, stats);

        gate.calculate(parcelRequest(2), null, solo(2, 100.0));

        assertEquals(0L, stats.blockedAttempts());
        assertEquals(0, stats.blockedSegmentCount());
    }

    @Test
    @DisplayName("M6: a passenger request is never recorded (it is never gated)")
    void paxIsNotRecorded() {
        ChiGateStats stats = new ChiGateStats();
        var gate = new ChiGateInsertionCostCalculator((req, ins, detour) -> 1.0, 600.0, LOAD_TYPE, MIN_STOP_S, stats);

        gate.calculate(request("p42"), null, detour(99_999.0, 0.0));

        assertEquals(0L, stats.blockedAttempts());
    }

    @Test
    @DisplayName("M6: hard-closed mode (chi < 0) records its blocks too")
    void hardClosedModeIsRecorded() {
        ChiGateStats stats = new ChiGateStats();
        var gate = new ChiGateInsertionCostCalculator((req, ins, detour) -> 1.0, -1.0, LOAD_TYPE, MIN_STOP_S, stats);

        assertEquals(InsertionCostCalculator.INFEASIBLE_SOLUTION_COST,
                gate.calculate(parcelRequest(3), null, detour(0.0, 0.0)));
        assertEquals(1L, stats.blockedAttempts());
        assertEquals(1, stats.blockedSegmentCount());
    }

    @Test
    @DisplayName("M6: reset clears both counters")
    void resetClearsCounters() {
        ChiGateStats stats = new ChiGateStats();
        var gate = new ChiGateInsertionCostCalculator((req, ins, detour) -> 0.0, 600.0, LOAD_TYPE, MIN_STOP_S, stats);
        gate.calculate(parcelRequest(2), null, solo(2, 700.0));

        stats.reset();

        assertEquals(0L, stats.blockedAttempts());
        assertEquals(0, stats.blockedSegmentCount());
        assertFalse(stats.wasBlocked(Id.createPersonId("parcel_dhl_1_B2C")));
    }

    @Test
    @DisplayName("load type without a 'parcels' dimension is a wiring bug -> constructor throws")
    void missingParcelsDimensionThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new ChiGateInsertionCostCalculator((req, ins, detour) -> 0.0, 600.0,
                        new IntegersLoadType("passengers"), MIN_STOP_S));
    }
}
