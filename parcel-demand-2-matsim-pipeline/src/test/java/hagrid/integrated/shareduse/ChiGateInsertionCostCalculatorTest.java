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
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The χ-gate rejects a PARCEL insertion whose DETOUR-ONLY time loss exceeds χ
 * (rev. 2026-07-27): {@code max(0, totalTimeLoss − ownDwell)} with
 * {@code ownDwell = depotPickupSeconds(n) + segmentDwellSeconds(n)}, n read from the
 * request's own {@code DvrpLoad} "parcels" dimension. The previous dwell-inclusive
 * gate structurally excluded segments with n ≥ 5 at χ=600 (dwell floor ≥ 150·n s).
 * χ &lt; 0 = hard-closed: EVERY parcel is rejected unconditionally (Task-10 leakage
 * probe). Passenger requests are never gated; a kept insertion returns the
 * delegate's own cost unchanged (M6: gate on raw time loss, NOT the delegate's
 * cost, which may include soft-constraint penalties).
 */
@DisplayName("ChiGateInsertionCostCalculator")
class ChiGateInsertionCostCalculatorTest {

    /** Same dimension layout SharedUseModule's fleet override produces. */
    private static final DvrpLoadType LOAD_TYPE = new IntegersLoadType("passengers", "parcels");

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

    /** A DetourTimeInfo whose getTotalTimeLoss() == the given raw time loss. */
    private static DetourTimeInfo detour(double totalTimeLoss) {
        return new DetourTimeInfo(
                new PickupDetourInfo(0.0, totalTimeLoss),
                new DropoffDetourInfo(0.0, 0.0));
    }

    /** depot pickup + door dropoff dwell the gated request itself adds. */
    private static double ownDwell(int parcels) {
        return SharedUse.depotPickupSeconds(parcels) + SharedUse.segmentDwellSeconds(parcels);
    }

    private static ChiGateInsertionCostCalculator gate(double delegateCost, double chi) {
        return new ChiGateInsertionCostCalculator((req, ins, detour) -> delegateCost, chi, LOAD_TYPE);
    }

    // ---- detour-only semantics ------------------------------------------------------

    @Test
    @DisplayName("zero-detour parcel (totalTimeLoss == own dwell) is accepted at chi=0")
    void zeroDetourParcelAcceptedAtChiZero() {
        // n=4: ownDwell = min(30*4,600) + min(120*4,900) = 120 + 480 = 600 s.
        // totalTimeLoss == ownDwell -> detourOnly = 0 <= chi = 0 -> kept, delegate cost returned.
        var gate = gate(77.0, 0.0);
        assertEquals(77.0, gate.calculate(parcelRequest(4), null, detour(ownDwell(4))));
    }

    @Test
    @DisplayName("large-dwell parcel (n=10) with detour exactly chi is kept (strict >)")
    void largeDwellParcelWithinChiIsKept() {
        // n=10: ownDwell = 300 + 900 = 1200 s >> chi. Under the old dwell-inclusive gate this
        // request was structurally uninsertable; detour-only it passes when detour <= chi.
        var gate = gate(123.0, 600.0);
        assertEquals(123.0, gate.calculate(parcelRequest(10), null, detour(ownDwell(10) + 600.0)));
    }

    @Test
    @DisplayName("large-dwell parcel (n=10) with detour above chi is infeasible")
    void largeDwellParcelAboveChiIsInfeasible() {
        var gate = gate(123.0, 600.0);
        assertEquals(InsertionCostCalculator.INFEASIBLE_SOLUTION_COST,
                gate.calculate(parcelRequest(10), null, detour(ownDwell(10) + 600.5)));
    }

    @Test
    @DisplayName("piggyback insertion (totalTimeLoss < own dwell) clamps to 0 and is accepted at chi=0")
    void piggybackClampedToZeroIsAccepted() {
        // n=5: ownDwell = 150 + 600 = 750 s, but the insertion shares existing stops
        // (ParallelStopTimeCalculator max-semantics) so the actual loss is only 300 s.
        // detourOnly = max(0, 300 - 750) = 0 -> accepted even at chi=0.
        var gate = gate(55.0, 0.0);
        assertEquals(55.0, gate.calculate(parcelRequest(5), null, detour(300.0)));
    }

    // ---- converted legacy cases (old dwell-inclusive fixtures, now n=1 dwell added) --

    @Test
    @DisplayName("parcel above chi (detour-only) is infeasible")
    void parcelAboveThresholdIsInfeasible() {
        // delegate cost is arbitrary (42) — the gate decides on the 700 s detour, not the cost.
        // n=1: ownDwell = 30 + 120 = 150 s; totalTimeLoss = 150 + 700 -> detourOnly = 700 > 600.
        var gate = gate(42.0, 600.0);
        assertEquals(InsertionCostCalculator.INFEASIBLE_SOLUTION_COST,
                gate.calculate(parcelRequest(1), null, detour(ownDwell(1) + 700.0)));
    }

    @Test
    @DisplayName("parcel below chi keeps the DELEGATE cost (not the raw loss)")
    void parcelBelowThresholdKeepsDelegateCost() {
        // detourOnly 500 < chi 600 -> kept; the returned value is the delegate's 123, proving we
        // return the delegate cost and do not substitute the time loss.
        var gate = gate(123.0, 600.0);
        assertEquals(123.0, gate.calculate(parcelRequest(1), null, detour(ownDwell(1) + 500.0)));
    }

    @Test
    @DisplayName("parcel exactly at chi is kept (strict >)")
    void parcelAtThresholdIsKept() {
        var gate = gate(88.0, 600.0);
        assertEquals(88.0, gate.calculate(parcelRequest(1), null, detour(ownDwell(1) + 600.0)));
    }

    // ---- hard-closed mode (chi < 0) --------------------------------------------------

    @Test
    @DisplayName("chi=-1 (hard-closed) rejects every parcel, even at totalTimeLoss=0")
    void hardClosedRejectsEveryParcel() {
        var gate = gate(1.0, -1.0);
        assertEquals(InsertionCostCalculator.INFEASIBLE_SOLUTION_COST,
                gate.calculate(parcelRequest(1), null, detour(0.0)));
    }

    @Test
    @DisplayName("chi=-1 (hard-closed) still delegates passenger requests")
    void hardClosedLeavesPaxUntouched() {
        var gate = gate(66.0, -1.0);
        assertEquals(66.0, gate.calculate(request("p42"), null, detour(1_000.0)));
    }

    // ---- pax + wiring guards ---------------------------------------------------------

    @Test
    @DisplayName("passenger request is never gated, even far above chi")
    void paxIsNeverGated() {
        var gate = gate(99_999.0, 600.0);
        assertEquals(99_999.0, gate.calculate(request("p42"), null, detour(99_999.0)));
    }

    @Test
    @DisplayName("parcel request without a load falls back to dwell-inclusive gating (conservative)")
    void nullLoadFallsBackToDwellInclusive() {
        // Cannot happen in production (DrtRequestCreator always sets a load), but the fallback
        // must stay conservative: ownDwell = 0 -> gate on the full totalTimeLoss.
        var gate = gate(42.0, 600.0);
        assertEquals(InsertionCostCalculator.INFEASIBLE_SOLUTION_COST,
                gate.calculate(request("parcel_dhl_1_B2C"), null, detour(700.0)));
    }

    @Test
    @DisplayName("load type without a 'parcels' dimension is a wiring bug -> constructor throws")
    void missingParcelsDimensionThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new ChiGateInsertionCostCalculator((req, ins, detour) -> 0.0, 600.0,
                        new IntegersLoadType("passengers")));
    }
}
