package hagrid.integrated.shareduse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.contrib.drt.optimizer.insertion.InsertionCostCalculator;
import org.matsim.contrib.drt.optimizer.insertion.InsertionDetourTimeCalculator.DetourTimeInfo;
import org.matsim.contrib.drt.optimizer.insertion.InsertionDetourTimeCalculator.DropoffDetourInfo;
import org.matsim.contrib.drt.optimizer.insertion.InsertionDetourTimeCalculator.PickupDetourInfo;
import org.matsim.contrib.drt.passenger.DrtRequest;
import org.matsim.contrib.dvrp.optimizer.Request;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The χ-gate rejects a PARCEL insertion whose RAW marginal vehicle-time loss
 * ({@link DetourTimeInfo#getTotalTimeLoss()}, seconds) exceeds χ (M6: gate on the
 * raw time loss, NOT the delegate's cost, which may include soft-constraint
 * penalties when a Discourage strategy is bound). Passenger requests are never
 * gated; a kept insertion returns the delegate's own cost unchanged.
 */
@DisplayName("ChiGateInsertionCostCalculator")
class ChiGateInsertionCostCalculatorTest {

    private static DrtRequest request(String personId) {
        return DrtRequest.newBuilder()
                .id(Id.create("r1", Request.class))
                .passengerIds(List.of(Id.createPersonId(personId)))
                .mode("drt")
                .build();
    }

    /** A DetourTimeInfo whose getTotalTimeLoss() == the given raw time loss. */
    private static DetourTimeInfo detour(double totalTimeLoss) {
        return new DetourTimeInfo(
                new PickupDetourInfo(0.0, totalTimeLoss),
                new DropoffDetourInfo(0.0, 0.0));
    }

    @Test
    @DisplayName("parcel above χ (raw time loss) is infeasible")
    void parcelAboveThresholdIsInfeasible() {
        // delegate cost is arbitrary (42) — the gate decides on the raw 700 s loss, not the cost
        var gate = new ChiGateInsertionCostCalculator((req, ins, detour) -> 42.0, 600.0);
        assertEquals(InsertionCostCalculator.INFEASIBLE_SOLUTION_COST,
                gate.calculate(request("parcel_dhl_1_B2C"), null, detour(700.0)));
    }

    @Test
    @DisplayName("parcel below χ keeps the DELEGATE cost (not the raw loss)")
    void parcelBelowThresholdKeepsDelegateCost() {
        // raw loss 500 < χ 600 -> kept; the returned value is the delegate's 123, proving we
        // return the delegate cost and do not substitute the raw time loss.
        var gate = new ChiGateInsertionCostCalculator((req, ins, detour) -> 123.0, 600.0);
        assertEquals(123.0, gate.calculate(request("parcel_dhl_1_B2C"), null, detour(500.0)));
    }

    @Test
    @DisplayName("parcel exactly at χ is kept (strict >)")
    void parcelAtThresholdIsKept() {
        var gate = new ChiGateInsertionCostCalculator((req, ins, detour) -> 88.0, 600.0);
        assertEquals(88.0, gate.calculate(request("parcel_dhl_1_B2C"), null, detour(600.0)));
    }

    @Test
    @DisplayName("passenger request is never gated, even far above χ")
    void paxIsNeverGated() {
        var gate = new ChiGateInsertionCostCalculator((req, ins, detour) -> 99_999.0, 600.0);
        assertEquals(99_999.0, gate.calculate(request("p42"), null, detour(99_999.0)));
    }
}
